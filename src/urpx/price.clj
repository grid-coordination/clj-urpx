(ns urpx.price
  "Resolve applicable per-energy prices for a URPX rate plan at a given instant.

  Given a coerced rate plan (`urpx.coerce/coerce-rate-plan`) and a
  ZonedDateTime, `resolve-prices` returns the season, the matched TOU period
  (if any), and one entry per active per-energy ledger (commodity, distribution,
  public benefits, etc.). Each ledger entry carries a `:tiers` vector — for
  block-tiered ledgers (CPAU E-1) every tier is surfaced with its unit-price
  and, where the applicable `urpx:ServiceBracket` is determined, its bounds, so
  the caller can pick the active one based on cumulative usage; a tier whose
  bounds selection cannot determine says so under
  `:urpx.resolved/tier-bounds-unresolved` instead of carrying them. For
  non-tiered ledgers the vector has a single entry.

  Skipped:
    - PriceDefinitions whose own `:urpx/hasCalculationMethod` is set
      (recurring fixed charges like a monthly customer charge — these are
      subscription-style and don't apply to instantaneous lookup).

  Day types honored (URPX ontology): urpx:allDays, urpx:weekdayDays,
  urpx:weekendDays, urpx:customDays. Holiday inclusion is expressed by the
  TimeBracket's optional urpx:includeHolidays / urpx:includeNonHolidays
  booleans (both default to true when omitted). Holiday detection sources,
  in priority order:

    1. Explicit `:holiday?` predicate in opts (highest precedence — overrides).
    2. The plan's embedded urpx:HolidayCalendar, derived via
       `urpx.holidays/derive-predicate`.
    3. Fallback: every date treated as a non-holiday.

  A modifier's brackets read the modifier's own calendar at step 2, and in a
  composition fall back to the base plan's before step 3.

  Plans without a HolidayCalendar and called without `:holiday?` retain the
  pre-existing 'every date is a non-holiday' fallback — so brackets that
  opt out of non-holidays (urpx:includeNonHolidays = false) will never match."
  (:require [clojure.string :as str]
            [urpx.holidays :as holidays]
            [urpx.index :as index]
            [urpx.schema :as schema])
  (:import [java.time DayOfWeek LocalDate ZoneId ZonedDateTime]))

;; ---------------------------------------------------------------------------
;; Day-type matching
;; ---------------------------------------------------------------------------

(def ^:private weekday-set
  #{DayOfWeek/MONDAY DayOfWeek/TUESDAY DayOfWeek/WEDNESDAY
    DayOfWeek/THURSDAY DayOfWeek/FRIDAY})

(def ^:private weekend-set
  #{DayOfWeek/SATURDAY DayOfWeek/SUNDAY})

(defn- day-of-week-matches?
  "True if `day-type-id` (the :jsonld/id of a urpx:DayType reference) accepts
   the given day-of-week. Recognized values come from the URPX ontology
   (urpx:allDays, urpx:weekdayDays, urpx:weekendDays, urpx:customDays). A
   bracket without dayType is permissive. Unknown values return false so
   misspellings or out-of-vocabulary day types fail loudly instead of
   silently matching every day."
  [day-type-id ^DayOfWeek dow]
  (case day-type-id
    "urpx:allDays"     true
    "urpx:weekdayDays" (boolean (weekday-set dow))
    "urpx:weekendDays" (boolean (weekend-set dow))
    ;; customDays semantics are not yet specified in URPX; treat permissively
    ;; until the ontology defines a calendar-of-custom-days property.
    "urpx:customDays"  true
    nil                true
    false))

(defn- holiday-status-matches?
  "True if the bracket's urpx:includeHolidays / urpx:includeNonHolidays gate
   accepts the date's holiday status. Both properties default to true when
   omitted, so a bracket that specifies neither matches both holidays and
   non-holidays (the implicit URPX default). Without a `holiday?` predicate
   the date is treated as a non-holiday."
  [bracket ^LocalDate date holiday?]
  (let [holiday (boolean (and holiday? (holiday? date)))
        accept  (if holiday
                  (:urpx/includeHolidays bracket)
                  (:urpx/includeNonHolidays bracket))]
    (not (false? accept))))

(defn- bracket-matches-day?
  [bracket ^LocalDate date ^DayOfWeek dow holiday?]
  (and (day-of-week-matches? (get-in bracket [:urpx/dayType :jsonld/id]) dow)
       (holiday-status-matches? bracket date holiday?)))

;; ---------------------------------------------------------------------------
;; Time-bracket containment
;; ---------------------------------------------------------------------------

(defn- time-in-bracket?
  "Half-open containment of `q`'s local time in [startTime, endTime). If
   startTime > endTime the bracket is treated as wrapping midnight. A bound
   with an offset is read on `q`'s date in `q`'s zone."
  [^ZonedDateTime q bracket]
  (let [t     (.toLocalTime q)
        date  (.toLocalDate q)
        zone  (.getZone q)
        start (schema/wall-time (:urpx/startTime bracket) date zone)
        end   (schema/wall-time (:urpx/endTime bracket) date zone)]
    (if (.isBefore start end)
      (and (not (.isBefore t start)) (.isBefore t end))
      ;; wraps midnight: [start, 24:00) ∪ [00:00, end)
      (or (not (.isBefore t start)) (.isBefore t end)))))

;; ---------------------------------------------------------------------------
;; Season / TOU lookup
;; ---------------------------------------------------------------------------

(defn- season-of
  "Return the Season whose :urpx/month vector contains the month-of-year of `date`,
   or nil if no season matches."
  [seasons ^LocalDate date]
  (let [m (.getMonthValue date)]
    (some (fn [s] (when (some #{m} (index/as-vec (:urpx/month s))) s)) seasons)))

(defn- season-refs
  "`node`'s urpx:appliesInSeason refs as a sequence, empty when it names none.

   One normalizer for every reader of the property, because they used to
   disagree about its shape as well as its strictness. The coercion decoder
   produces a vector, but a document assembled in code need not have been
   through it, and reading a bare map as a collection yields MapEntries that
   match no @id."
  [node]
  (index/as-vec (:urpx/appliesInSeason node)))

(defn- season-id-matcher
  "A bracket-season predicate for `find-tou-period`: refs naming `season-id`
   match, and a bracket naming no Season matches only a nil `season-id`, which
   is the season of a document declaring no urpx:hasSeasons."
  [season-id]
  (fn [refs]
    (if (empty? refs)
      (nil? season-id)
      (boolean (some #(= season-id (:jsonld/id %)) refs)))))

(defn- find-tou-period
  "Find the TOUPeriod whose TimeBracket matches the date, day of week and local
   time of `q`, a ZonedDateTime in the plan's zone, and whose urpx:appliesInSeason
   refs satisfy `in-season?`, across every TOUSchedule in `tou-schedules`.
   Walks every tier's periods (the tier hierarchy is for classification —
   what matters for resolution is the bracket match).

   BillingModel.hasTOUSchedule is a vector: one schedule per
   MetricInput when the rate plan TOU-zones multiple metrics. The first
   bracket-matching period across all schedules wins."
  [tou-schedules in-season? ^ZonedDateTime q holiday?]
  (when (seq tou-schedules)
    (some
     (fn [period]
       (when (some
              (fn [bracket]
                ;; Disjunctive across refs, matching every other reader of
                ;; urpx:appliesInSeason: TimeBracketShape sets sh:minCount 0
                ;; and no sh:maxCount, so a bracket naming two Seasons applies
                ;; in either.
                (and (in-season? (season-refs bracket))
                     (bracket-matches-day? bracket (.toLocalDate q) (.getDayOfWeek q) holiday?)
                     (time-in-bracket? q bracket)))
              (index/as-vec (:urpx/hasTimeBracket period)))
         period))
     (mapcat (fn [schedule]
               (mapcat (comp index/as-vec :urpx/hasTOUPeriod)
                       (index/as-vec (:urpx/hasTOUTier schedule))))
             tou-schedules))))

(defn- modifier-season-matcher
  "A bracket-season predicate for a modifier's own `tou-schedules`. A ref to one
   of `own-seasons` matches by identity with the modifier's own season at `date`,
   as `season-id-matcher` does. Any other ref resolves through `by-id`, which is
   composition-wide when composing, and matches when that urpx:Season's months
   include `date`'s.

   Refuses `:unresolvable-season-ref` when a bracket names a Season that neither
   `own-seasons` nor `by-id` declares, whichever bracket the instant reaches."
  [plan-id version-id tou-schedules own-seasons by-id ^LocalDate date]
  (let [own-ids    (into #{} (map :jsonld/id) own-seasons)
        own-id     (:jsonld/id (season-of own-seasons date))
        resolved   (fn [sid]
                     (let [n (get by-id sid)]
                       (when (= "urpx:Season" (:jsonld/type n)) n)))
        unresolved (->> tou-schedules
                        (mapcat (comp index/as-vec :urpx/hasTOUTier))
                        (mapcat (comp index/as-vec :urpx/hasTOUPeriod))
                        (mapcat (comp index/as-vec :urpx/hasTimeBracket))
                        (mapcat season-refs)
                        (map :jsonld/id)
                        (remove #(or (own-ids %) (resolved %)))
                        distinct
                        vec)
        m          (.getMonthValue date)]
    (when (seq unresolved)
      (throw (ex-info (str "urpx:TimeBracket of " (pr-str version-id) " names urpx:Season "
                           (pr-str unresolved) ", which no document passed to this call "
                           "declares, so the urpx:TOUPeriod in effect cannot be determined.")
                      {:reason       :unresolvable-season-ref
                       :plan-id      plan-id
                       :version-id   version-id
                       :season-ids   unresolved
                       :schedule-ids (mapv :jsonld/id tou-schedules)})))
    (fn [refs]
      (if (empty? refs)
        (nil? own-id)
        (boolean (some (fn [{sid :jsonld/id}]
                         (if (own-ids sid)
                           (= own-id sid)
                           (some #{m} (index/as-vec (:urpx/month (resolved sid))))))
                       refs))))))

(defn- require-tou-period
  "`find-tou-period`, refusing `:urpx.price/no-tou-period` when `tou-schedules`
   is non-empty and no bracket matches `q`. Nil when there is no schedule.

   A nil period gates out no Price carrying urpx:touPeriodNumber, so resolving
   on through a gap returns every period's Price at once. `season` is the
   document's own, and a nil one, from a document declaring no urpx:hasSeasons,
   matches only brackets naming no Season.

   `plan-id` and `version-id` name the RatePlan and the version whose schedule
   has the gap."
  [plan-id version-id tou-schedules season ^ZonedDateTime q holiday?]
  (when (seq tou-schedules)
    (or (find-tou-period tou-schedules (season-id-matcher (:jsonld/id season)) q holiday?)
        (throw (ex-info (str "No urpx:TOUPeriod of " (pr-str plan-id) " covers " q
                             (if season
                               (str " in season " (pr-str (:jsonld/id season)))
                               ", which falls in no urpx:Season this version declares")
                             ". Its urpx:TOUSchedule exists, so every Price gated by "
                             "period has no period to be gated against.")
                        {:reason       :urpx.price/no-tou-period
                         :plan-id      plan-id
                         :version-id   version-id
                         :at           q
                         :season-id    (:jsonld/id season)
                         :schedule-ids (mapv :jsonld/id tou-schedules)})))))

;; ---------------------------------------------------------------------------
;; Active-price lookup
;; ---------------------------------------------------------------------------

(defn- started?
  "True when `start`, an instant or nil for no start constraint, is not after `q`."
  [^ZonedDateTime start ^ZonedDateTime q]
  (or (nil? start) (not (.isAfter start q))))

(defn- price-set-alternative-for
  "Within a PriceSetAlternatives, find the alternative whose
   urpx:referencesProfileAlternative matches the caller-selected
   :jsonld/id. Returns the matching PriceSetAlternative or nil."
  [psas chosen-pa-id]
  (when chosen-pa-id
    (some (fn [psa]
            (when (= chosen-pa-id (get-in psa [:urpx/referencesProfileAlternative :jsonld/id]))
              psa))
          (index/as-vec (:urpx/hasPriceSetAlternative psas)))))

(defn- price-start
  "The instant in `zone` from which `price` is in effect, falling back to
   `container-start` (a PriceSet's). Nil means no start constraint.

   urpx:startDateTime is preferred over urpx:startDate when both are stated,
   mirroring how version-effective-at prefers effectiveDateTime. A startDateTime
   gates at its instant; a startDate gates from midnight of that day in `zone`."
  ^ZonedDateTime [price ^ZonedDateTime container-start ^ZoneId zone]
  (or (schema/in-zone (or (:urpx/startDateTime price) (:urpx/startDate price)) zone)
      container-start))

(defn- prices-from-alternatives
  "Walk `Prices.hasPriceSetAlternatives`. For each PriceSetAlternatives,
   look up the caller's chosen ProfileAlternative for the referenced
   ProfileAlternatives in `selection` (a {:jsonld/id of ProfileAlternatives
   → :jsonld/id of chosen ProfileAlternative} map). The matching
   PriceSetAlternative's hasPrice list contributes to the active-prices
   stream, gated by its start as usual.

   When the caller hasn't supplied a selection for a given
   PriceSetAlternatives, that set is silently skipped — prices for the
   unresolved alternative aren't surfaced. Programmatic ConditionExpression
   evaluation against a customer profile is a future enhancement; the
   caller drives selection explicitly via `:profile-alternatives` opts."
  [prices ^ZonedDateTime q selection]
  (mapcat (fn [psas]
            (let [pa-id   (get-in psas [:urpx/referencesProfileAlternatives :jsonld/id])
                  chosen  (get selection pa-id)
                  picked  (price-set-alternative-for psas chosen)]
              (when picked
                (keep (fn [p]
                        (let [sd (price-start p nil (.getZone q))]
                          (when (started? sd q) [p sd])))
                      (index/as-vec (:urpx/hasPrice picked))))))
          (index/as-vec (:urpx/hasPriceSetAlternatives prices))))

(defn- scoped-prices
  "Return a seq of [price start] pairs for every Price under `prices` that
   has started at `q` (see `price-start`). Walks the flat
   :urpx/hasPrice list, PriceSet-nested prices, and PriceSetAlternatives-
   nested prices when the caller supplies an explicit
   `:profile-alternatives` selection in opts.

   Per URPX SHACL, PriceSet.startDate is optional (sh:minCount 0). When the
   PriceSet carries one it acts as the start for any contained Price that
   does not declare its own; when neither states one the Price carries no start
   constraint and is in effect, which is what `price-start` settles."
  [prices ^ZonedDateTime q opts]
  (let [selection (:profile-alternatives opts {})
        zone      (.getZone q)]
    (concat
     (keep (fn [p]
             (let [sd (price-start p nil zone)]
               (when (started? sd q) [p sd])))
           (index/as-vec (:urpx/hasPrice prices)))
     (mapcat (fn [ps]
               (let [ps-sd (price-start ps nil zone)]
                 (when (started? ps-sd q)
                   (keep (fn [p]
                           (let [sd (price-start p ps-sd zone)]
                             (when (started? sd q) [p sd])))
                         (index/as-vec (:urpx/hasPrice ps))))))
             (index/as-vec (:urpx/hasPriceSet prices)))
     (prices-from-alternatives prices q selection))))

(defn- price-active-at-tou?
  "True if `price` may apply at the resolved TOU period.

   urpx:touPeriodNumber is rdfs:domain the union of urpx:Price,
   urpx:PriceDefinition, urpx:PriceSet and urpx:TOUPeriod, so a Price may carry
   the TOU link itself. Only the PriceDefinition was ever read, so such Prices
   were never gated, with two different wrong answers depending on the path:

     in a urpx:PriceSummary   every TOU variant of one charge summed into the
                              subtotal. SDG&E EV-TOU's CARE discount came out
                              -0.9255435 against a correct -0.2999885, 3.09x.
     in the tier path         the variants collided in the latest-wins dedupe,
                              five of six were discarded and the survivor was
                              reported at EVERY hour. The transmission charge
                              read at its on-peak value around the clock.

   A Price carrying no period is left to its PriceDefinition's gate. A nil
   resolved period means there is nothing to match against, so nothing is
   gated out."
  [price resolved-period-num]
  (let [n (:urpx/touPeriodNumber price)]
    (or (nil? n) (nil? resolved-period-num) (= n resolved-period-num))))

(defn- latest-by-pd
  "Group [price start] pairs into a map from PriceDefinition id to a
   vector of pairs, keeping only the latest-starting Price per cell. A cell is
   the composite key `(price-definition-id, serviceTierNumber,
   touPeriodNumber)`, so two distinct concrete patterns coexist without
   conflict:

     - Tier-stratified pricing (PG&E E-1 CIA): one PriceDefinition with
       multiple Prices, each carrying its own urpx:serviceTierNumber. Every
       tier's Price surfaces under one PD because the keys differ.
     - Temporal versioning: a single cell with multiple Prices over time. The
       one with the latest start wins. An undated Price sorts behind any dated
       one, so an explicitly dated Price supersedes an undated sibling.

   For non-tiered ledgers (one PD, one Price, no serviceTierNumber) the
   vector has a single entry."
  [pairs]
  (let [latest-per-key
        (reduce (fn [acc [price sd]]
                  ;; Keyed on the TOU period too. Without it, six TOU variants
                  ;; of one charge sharing a PriceDefinition collide and five
                  ;; are discarded; the Price-level gate removes the
                  ;; non-matching ones first, and this keeps them separable if
                  ;; there is no resolved period to gate against.
                  (let [k [(get-in price [:urpx/referencesPriceDefinition :jsonld/id])
                           (:urpx/serviceTierNumber price)
                           (:urpx/touPeriodNumber price)]
                        [_ existing-sd :as existing] (acc k)]
                    (if (or (nil? existing)
                            (and sd (or (nil? existing-sd)
                                        (.isAfter ^ZonedDateTime sd existing-sd))))
                      (assoc acc k [price sd])
                      acc)))
                {} pairs)]
    (reduce-kv (fn [acc [pd-id _ _] pair]
                 (update acc pd-id (fnil conj []) pair))
               {} latest-per-key)))

(defn- active-prices-by-pd
  "Build a map from PriceDefinition id to a vector of currently-active
   [price start] pairs, the latest per cell (see `latest-by-pd`)."
  ([prices ^ZonedDateTime q opts] (active-prices-by-pd prices q opts nil))
  ([prices ^ZonedDateTime q opts resolved-period-num]
   (latest-by-pd (filter #(price-active-at-tou? (first %) resolved-period-num)
                         (scoped-prices prices q opts)))))

(defn- alternative-price-pairs
  "The started [price start] pairs of each urpx:PriceSetAlternative under
   `psas-seq`, a seq of urpx:PriceSetAlternatives, one seq per alternative."
  [psas-seq ^ZonedDateTime q]
  (for [psas psas-seq
        psa  (index/as-vec (:urpx/hasPriceSetAlternative psas))]
    (keep (fn [p]
            (let [sd (price-start p nil (.getZone q))]
              (when (started? sd q) [p sd])))
          (index/as-vec (:urpx/hasPrice psa)))))

(defn- all-prices-from-alternatives
  "Walk Prices.hasPriceSetAlternatives and return the active [price
   start] pairs of EVERY PriceSetAlternative, one seq per alternative:
   the matrix-modifier surface-all contract (e.g. PG&E Vintage-PCIA's
   per-departure-vintage-year prices). Kept apart because each alternative is
   its own set of cells; its siblings are alternatives to it, not earlier or
   later versions of it. Distinct from `prices-from-alternatives`, which only
   surfaces the caller-selected alternative."
  [prices ^ZonedDateTime q]
  (alternative-price-pairs (index/as-vec (:urpx/hasPriceSetAlternatives prices)) q))

(defn- period-cells
  "`latest-by-pd` over the [price start] `pairs` that may apply at
   `resolved-period-num`."
  [pairs resolved-period-num]
  (latest-by-pd (filter #(price-active-at-tou? (first %) resolved-period-num) pairs)))

(defn- all-active-prices-by-pd
  "Build a map from PriceDefinition id to a vector of [price start] pairs
   for every active Price referencing that PD, the latest per cell (see
   `latest-by-pd`) within the flat and PriceSet prices and within each
   PriceSetAlternative. Used by modifier resolution where matrix-style pricing
   surfaces multiple concurrent prices that the caller selects from. Walks both
   the flat hasPrice list (CPAU E-HRA shape) and hasPriceSetAlternatives
   nesting (PG&E Vintage-PCIA shape: per-departure-vintage-year prices in
   PriceSetAlternative)."
  ([prices ^ZonedDateTime q] (all-active-prices-by-pd prices q nil nil))
  ([prices ^ZonedDateTime q opts] (all-active-prices-by-pd prices q opts nil))
  ([prices ^ZonedDateTime q opts resolved-period-num]
   ;; With a selection, surface only the chosen alternative, exactly as the
   ;; base plan path does. Without one, keep the surface-all contract so a
   ;; caller reading tiers directly still sees every vintage;
   ;; `unselected-alternative-ids` then marks the ledger so an AGGREGATE cannot
   ;; silently pick one of them.
   (if (seq (:profile-alternatives opts))
     (active-prices-by-pd prices q opts resolved-period-num)
     (let [cells #(period-cells % resolved-period-num)]
       (apply merge-with into
              (cells (scoped-prices prices q nil))
              (map cells (all-prices-from-alternatives prices q)))))))

;; ---------------------------------------------------------------------------
;; Service-tier lookup (block tiers, e.g. CPAU E-1 baseline / over-baseline)
;; ---------------------------------------------------------------------------

(defn- unselected-alternative-ids
  "The @ids of the Prices in `withheld`, a `withheld-prices-by-pd` map.

   These are ALTERNATIVES, not tiers: exactly one applies to any customer, and
   which one is the caller's to say. PG&E's vintage-PCIA ledger carries 18 of
   them spanning 0.06391/kWh. Surfacing them all is the documented contract for
   reading tiers; silently adding one of them to an aggregate is not, so the
   ledger is marked and `marginal-unit-rate` refuses rather than pick."
  [withheld]
  (into #{} (comp (mapcat val) (keep :jsonld/id)) withheld))

(defn- withheld-prices-by-pd
  "The Prices that exist only inside a urpx:PriceSetAlternatives the caller
   supplied no selection for, grouped by the @id of the PriceDefinition each
   references. Only those that would apply at `q` were their alternative
   selected are kept: started, the latest per cell within each
   urpx:PriceSetAlternative, and active at `resolved-period-num` (see
   `period-cells`).

   A ledger fed only by such definitions resolves to no tiers and is then
   elided, taking with it both the prices AND any signal that they were
   withheld. On the base plan path that is the whole failure: an unselected set
   contributes nothing, so the prices, the ledger and the evidence disappear
   together, and six shipped base versions carry one. On the modifier path it
   defeats an existing guard, because the unselected mark is computed from
   surviving tier entries, and a selection in another dimension leaves none.

   The Prices themselves are kept, not just their definition ids, because
   whether a refusal is owed depends on what was withheld: see
   `withheld-price-entry`."
  [prices selection ^ZonedDateTime q resolved-period-num]
  (let [unselected (remove #(get selection (get-in % [:urpx/referencesProfileAlternatives :jsonld/id]))
                           (index/as-vec (:urpx/hasPriceSetAlternatives prices)))]
    (-> (apply merge-with into {}
               (map #(period-cells % resolved-period-num)
                    (alternative-price-pairs unselected q)))
        (dissoc nil)
        (update-vals #(mapv first %)))))

(defn- recurring-interval
  "urpx:intervalDuration off `pd`'s calculation method: the field that makes a
   charge per-interval rather than per-unit.

   One reader, called from every site that builds a view of a price for
   marginal-unit-rate, because the present view and the withheld view of the
   same price must never disagree about it. They did: the modifier path built
   tiers without it while the withheld record carried it, so the marginal
   judged eighteen vintage-PCIA prices harmless-as-withheld and then summed all
   eighteen as present, returning 1.08811 where 0.24.0 refused."
  [pd]
  (get-in pd [:urpx/hasCalculationMethod :urpx/intervalDuration]))

(defn- withheld-price-entry
  "What marginal-unit-rate needs to know about a price it did not get.

   Carries the two fields the marginal sum excludes on, under the same keys a
   resolved tier uses, so one predicate answers \"would this have contributed\"
   for a withheld price and for a present tier alike.

   Withholding only earns a refusal when it makes the sum short. An event-driven
   or recurring price is already excluded from a per-unit marginal, so hiding it
   changes no number, and refusing would trade an answer for a question the
   caller cannot answer: CPAU's E-HRA cells are the utility's own Hydro
   Stabilization Reserve level and generation band, reset by City Council vote,
   which no consumer has a source for. Vintage-PCIA is the opposite case, an
   ordinary per-kWh price and an attribute of the customer the caller does know,
   and it still refuses."
  [pd price]
  (let [interval (recurring-interval pd)]
    (cond-> {:urpx.resolved/price-definition-id (:jsonld/id pd)
             :urpx.resolved/price-id            (:jsonld/id price)}
      (:urpx/hasDynamicPricing price)
      (assoc :urpx.resolved/dynamic-pricing (:urpx/hasDynamicPricing price))
      interval
      (assoc :urpx.resolved/interval-duration interval))))

(defn- service-tier-candidates
  "Every (ServiceTiers container, ServiceTier, ServiceBracket) triple under
   BillingModel.hasServiceTiers, grouped by :urpx/serviceTierNumber in document
   order. Empty map when the plan is not block-tiered.

   The container travels beside its tier because BOTH scopes qualify bounds:
   urpx:appliesInSeason and urpx:referencesProfileAlternative sit on
   urpx:ServiceTiers and on urpx:ServiceBracket alike (both carry the two
   properties in urpx.schema). A mapcat over hasServiceTier loses the
   container a tier came from, and with it half the question.

   Grouping, where this was a map from number to ONE tier: two containers
   declaring the same number last-won on insertion order, which is the silent
   pick urpx.index/single exists to prevent. They accumulate now, and
   `select-tier-bracket` refuses when the survivors disagree.

   :bracket is nil for a tier whose :urpx/hasServiceBracket is empty or absent.
   ServiceTierShape forbids that (sh:minCount 1), but the Malli schema types it
   as a plain vector on urpx.schema/ServiceTier, and so does `urpx.validate`, so a
   document that never met SHACL reaches here.

   hasServiceTiers is a vector, though every tiered fixture carries exactly one
   container. A filing stating a per-season or per-cell container is the case
   this grouping is for."
  [billing-model]
  (->> (for [container (index/as-vec (:urpx/hasServiceTiers billing-model))
             tier      (index/as-vec (:urpx/hasServiceTier container))
             bracket   (or (seq (index/as-vec (:urpx/hasServiceBracket tier))) [nil])]
         {:container container :tier tier :bracket bracket})
       (group-by #(:urpx/serviceTierNumber (:tier %)))))

(defn- qualified-tier-node?
  "True if `node`, a urpx:ServiceTiers or a urpx:ServiceBracket, carries either
   qualifier, so whether it applies depends on the instant or on the caller's
   selection.

   Worth naming because the unqualified case is everything that reaches here:
   the 18 tiers across the five tiered base-plan fixtures carry neither
   property, on the container or on the bracket. `select-tier-bracket`
   short-circuits them without reading the date or the selection at all, so
   what those fixtures resolve to cannot depend on any of this. Not every
   fixture is unqualified: PG&E's E-TOU-C baseline-tier modifier carries 120
   brackets with both properties, and no modifier's tiers reach this path.

   A single ref written as a bare map rather than a vector counts as qualified:
   `seq` on a non-empty map is truthy, which is what sends it to the
   normalizing gate instead of the short-circuit."
  [node]
  (boolean (or (seq (:urpx/appliesInSeason node))
               (:urpx/referencesProfileAlternative node))))

(defn- bracket-bounds-key
  "The four values a urpx:ServiceBracket states, in a form two brackets can be
   compared by.

   Bounds go through .stripTrailingZeros because = on BigDecimal is
   scale-sensitive: 0M and 0.0M are not =, so two brackets spelling one bound
   differently would read as a contradiction and refuse a document that states
   the same ladder twice. Operators compare by :jsonld/id alone, since a Ref
   carrying any other key would do the same.

   For comparison only. A winning bracket's bounds are emitted verbatim."
  [bracket]
  [(some-> ^java.math.BigDecimal (:urpx/lowerBound bracket) .stripTrailingZeros)
   (some-> ^java.math.BigDecimal (:urpx/upperBound bracket) .stripTrailingZeros)
   (:jsonld/id (:urpx/lowerBoundOperator bracket))
   (:jsonld/id (:urpx/upperBoundOperator bracket))])

(defn- select-tier-bracket
  "Which urpx:ServiceBracket states this tier's bounds at this instant, given
   every (container, tier, bracket) candidate carrying its serviceTierNumber.

   Both scopes qualify a candidate and they compose. The two properties on the
   urpx:ServiceTiers container decide whether that container's tiers are in
   effect at all; the same two on the urpx:ServiceBracket decide which of a
   tier's brackets states the bounds. Reading one and not the other leaves the
   other a silent no-op, which is what both were until now.

   Returns nil when the number names no tier, {:tier-name s :bracket b} when
   one set of bounds is determined, {:tier-name s :mark m} when it is not and
   the caller is told why, or throws.

   REFUSES rather than picking, three ways. A season ref resolving to no
   urpx:Season is a typo, and reading it as out-of-season leaves a rung with no
   bounds, which a consumer reads as unbounded. Two brackets that both apply
   and disagree, or two containers declaring one number with different bounds,
   are documents contradicting themselves: `first` of a set the document does
   not order is exactly what urpx.index/single refuses to take.

   WITHHOLDS instead, with a named mark, where the answer is unknown rather
   than contradictory. An unselected alternative is cleared by naming the cell;
   bounds nobody filed for this instant are not cleared by anything.

   Called only for a number a Price or PriceDefinition actually names, so a
   defect in a container whose tiers are never priced does not refuse the
   document."
  [candidates {:keys [season-status alt-status]}]
  (when (seq candidates)
    (let [one (first candidates)]
      (if (and (= 1 (count candidates))
               (not (qualified-tier-node? (:container one)))
               (not (qualified-tier-node? (:bracket one))))
        (cond-> {}
          (:urpx/name (:tier one)) (assoc :tier-name (:urpx/name (:tier one)))
          (:bracket one)           (assoc :bracket (:bracket one)))
        (let [;; Both gates run on both nodes of every candidate BEFORE any
              ;; filtering. A dangling season ref is a property of the
              ;; document, not of the query, and one that refused only for the
              ;; caller who happened to name the broken cell could be found by
              ;; no validator and no sweep.
              node-status
              (fn [node]
                (let [a (alt-status node)
                      s (season-status node)]
                  (cond
                    (or (= :not-selected a) (= :out-of-season s))  :drop
                    (or (= :unselected a)   (= :not-evaluable s))  :unknown
                    :else                                          :pass)))
              tagged     (mapv (fn [c]
                                 (assoc c
                                        :container-status (node-status (:container c))
                                        :bracket-status   (node-status (:bracket c))))
                               candidates)
              status     (fn [c] (let [a (:container-status c)
                                       b (:bracket-status c)]
                                   (cond
                                     (or (= :drop a) (= :drop b))       :drop
                                     (or (= :unknown a) (= :unknown b)) :unknown
                                     :else                              :pass)))
              applying   (filterv #(= :pass (status %)) tagged)
              unknown    (filterv #(= :unknown (status %)) tagged)
              ;; A container in effect means the bounds are the open question;
              ;; no container in effect means the tier itself is.
              scope      (if (some #(= :pass (:container-status %)) tagged)
                           :service-bracket
                           :service-tiers)
              ;; Every node at that scope, not the survivors, so the mark does
              ;; not vary with the instant and urpx.schedule can still collapse
              ;; equal intervals.
              ids        (into [] (comp (keep (if (= :service-bracket scope)
                                                #(:jsonld/id (:bracket %))
                                                #(:jsonld/id (:container %))))
                                        (distinct))
                               candidates)
              ;; Containers may name one rung differently, so a name is
              ;; reported only where the candidates still in play agree.
              considered (or (seq (concat applying unknown)) tagged)
              tier-name  (let [names (distinct (keep #(:urpx/name (:tier %)) considered))]
                           (when (= 1 (count names)) (first names)))
              named      (fn [m] (cond-> m tier-name (assoc :tier-name tier-name)))
              unselected (into [] (comp (mapcat (juxt :container :bracket))
                                        (filter #(= :unselected (alt-status %)))
                                        (keep #(:jsonld/id (:urpx/referencesProfileAlternative %)))
                                        (distinct))
                               unknown)]
          (cond
            ;; An unqualified bracket beside a cell-scoped one is NOT a
            ;; default: URPX orders nothing between them, so reading the
            ;; unqualified one as the fallback would invent a specificity rule.
            ;; A withheld bound is cleared by naming the cell; a wrong bound
            ;; already reported is not.
            (seq unknown)
            (named {:mark (cond-> {:reason (if (seq unselected)
                                             :alternative-unselected
                                             :season-not-evaluable)
                                   :scope  scope
                                   :ids    ids}
                            (seq unselected) (assoc :alternatives unselected))})

            (empty? applying)
            (named {:mark {:reason :no-applicable-bounds :scope scope :ids ids}})

            (= 1 (count (distinct (map #(bracket-bounds-key (:bracket %)) applying))))
            (named (cond-> {} (:bracket (first applying))
                           (assoc :bracket (:bracket (first applying)))))

            (< 1 (count (distinct (map :container applying))))
            (throw (ex-info
                    (str "urpx:serviceTierNumber " (pr-str (:urpx/serviceTierNumber (:tier one)))
                         " is declared with different bounds by " (count (distinct (map :container applying)))
                         " urpx:ServiceTiers containers that are all in effect here: "
                         (pr-str (mapv #(:jsonld/id (:container %)) applying))
                         ". Which ladder this rung belongs to is not stated. Scope the "
                         "containers with urpx:appliesInSeason or "
                         "urpx:referencesProfileAlternative so that one applies at a time.")
                    {:reason        :ambiguous-service-tier
                     :tier-number   (:urpx/serviceTierNumber (:tier one))
                     ;; Every container declaring this number, not the
                     ;; survivors, so the refusal is instant-independent and
                     ;; urpx.schedule can still collapse equal intervals. Read
                     ;; off the candidates rather than from `ids`, which is the
                     ;; bracket-scoped list whenever a container is in effect.
                     :container-ids (into [] (comp (keep #(:jsonld/id (:container %)))
                                                   (distinct))
                                          candidates)
                     :count         (count applying)}))

            :else
            (throw (ex-info
                    (str "urpx:serviceTierNumber " (pr-str (:urpx/serviceTierNumber (:tier one)))
                         " has " (count applying) " urpx:ServiceBrackets in effect here stating "
                         "different bounds: " (pr-str (mapv #(:jsonld/id (:bracket %)) applying))
                         ". urpx:hasServiceBracket is a set, not an ordered list, so there is no "
                         "first bracket to take. Scope them with urpx:appliesInSeason or "
                         "urpx:referencesProfileAlternative so that one applies at a time.")
                    {:reason          :ambiguous-service-bracket
                     :tier-number     (:urpx/serviceTierNumber (:tier one))
                     :service-tier-id (:jsonld/id (:tier one))
                     :bracket-ids     ids
                     :count           (count applying)}))))))))

(defn- tier-info
  "Pull tier metadata for a (PD, Price) pair into the resolved-entry shape.
   The serviceTierNumber may live on the Price (PG&E-style: many tier-Prices
   share one PD) or on the PD (CPAU-style: one PD per tier). The Price's
   number wins when both are present. Returns an empty map when the entry
   isn't block-tiered.

   Per upstream urpx commit 423e83f (Decision #111), bounds live on
   urpx:ServiceBracket(s) attached via ServiceTier.hasServiceBracket.
   `select-tier-bracket` chooses among them and among the urpx:ServiceTiers
   containers declaring this number, honoring urpx:appliesInSeason and
   urpx:referencesProfileAlternative at both scopes. It may instead return a
   mark saying the bounds are not determined, or throw.

   :urpx.resolved/tier-number is assoc'd from the Price or the PriceDefinition
   BEFORE any container or bracket is read. On the WITHHELD path that is the
   whole guarantee: the bounds are then the only thing selection changed, so
   every entry still surfaces, in the same order, and marginal-unit-rate still
   reduces the ladder by number exactly as before. A REFUSAL is not scoped that
   way. Nothing here catches it, so it leaves no entry and no ledger at all,
   which is the point of refusing rather than marking."
  [pd price tier-candidates tier-ctx]
  (let [tier-num   (or (:urpx/serviceTierNumber price) (:urpx/serviceTierNumber pd))
        selected   (when tier-num
                     (select-tier-bracket (tier-candidates tier-num) tier-ctx))
        bracket    (:bracket selected)
        ;; A recurring charge's unit price is per INTERVAL, not per unit of
        ;; consumption: SCE's base services charge is 0.794 per P1D, and its
        ;; ledger's metric input is a meter count carrying no measurement kind
        ;; or unit. Surfacing the number without the interval would hand a
        ;; consumer a figure nothing in the result explains, which is how a
        ;; $10-per-month charge becomes a wrong per-day rate.
        interval   (recurring-interval pd)]
    (cond-> {}
      interval
      (assoc :urpx.resolved/interval-duration interval)
      tier-num
      (assoc :urpx.resolved/tier-number tier-num)

      selected
      (merge (cond-> {}
               (:tier-name selected)
               (assoc :urpx.resolved/tier-name (:tier-name selected))
               (:mark selected)
               (assoc :urpx.resolved/tier-bounds-unresolved (:mark selected))
               (:urpx/lowerBound bracket)
               (assoc :urpx.resolved/tier-lower-bound (:urpx/lowerBound bracket))
               (:urpx/upperBound bracket)
               (assoc :urpx.resolved/tier-upper-bound (:urpx/upperBound bracket))
               (:urpx/lowerBoundOperator bracket)
               (assoc :urpx.resolved/tier-lower-bound-operator
                      (:urpx/lowerBoundOperator bracket))
               (:urpx/upperBoundOperator bracket)
               (assoc :urpx.resolved/tier-upper-bound-operator
                      (:urpx/upperBoundOperator bracket)))))))

;; ---------------------------------------------------------------------------
;; Per-instant applicability of a PriceDefinition
;; ---------------------------------------------------------------------------

(defn- profile-alternative-index
  "Two indexes over the document's urpx:ProfileAlternatives, built in one pass
   so they cannot disagree:

     :dim-of  urpx:ProfileAlternative @id -> the container @ids listing it
     :cells   container @id -> the set of urpx:ProfileAlternative @ids in it

   The `:profile-alternatives` selection is keyed by CONTAINER, since a
   container is one dimension of choice, while a PriceDefinition references the
   ALTERNATIVE it applies to. `:dim-of` bridges the two, and it needs no new
   vocabulary: urpx:hasProfileAlternative already links them. `:cells` is what
   lets a selection be checked against the dimension it was filed under instead
   of being compared for equality and silently read as a choice of some sibling.

   Multi-dimensional choices live in ONE container. URPX is explicit that each
   ProfileAlternatives holds exactly one urpx:AlternativeDimensions, which is
   the wrapper for several urpx:AlternativeDimension, and the ProfileAlternative
   instances enumerate the CELLS of the cross product. PG&E's baseline
   territory/code set is 2 dimensions and 20 alternatives.

   :dim-of maps to a VECTOR of containers rather than one, because nothing in
   the shapes stops an alternative @id being listed by two. Collapsing that
   last-wins would answer a selection the caller made correctly with a refusal
   they cannot clear, or drop the definition silently; `pd-alternative-status`
   refuses instead, and only if such an alternative is actually referenced."
  [plan-elements]
  (reduce (fn [acc pas]
            (let [cid  (:jsonld/id pas)
                  aids (keep :jsonld/id (index/as-vec (:urpx/hasProfileAlternative pas)))]
              (-> acc
                  (assoc-in [:cells cid] (set aids))
                  (update :dim-of
                          #(reduce (fn [m a]
                                     (update m a (fn [v] (vec (distinct (conj (or v []) cid))))))
                                   % aids)))))
          {:cells {} :dim-of {}}
          (index/as-vec (:urpx/hasProfileAlternatives plan-elements))))

(defn- pd-alternative-status
  "How `pd`'s urpx:referencesProfileAlternative stands against the caller's
   selection.

   Named for the PriceDefinition it was written for, but it reads only
   urpx:referencesProfileAlternative off whatever node it is handed, and
   `select-tier-bracket` hands it urpx:ServiceTiers containers and
   urpx:ServiceBracket nodes, which carry the same property (see
   urpx.schema/ServiceBracket and ServiceTiers). Its two refusals report :node-id and :node-type for that reason.

   One of:

     :unconstrained  the definition references no alternative
     :selected       the caller chose exactly the alternative it references
     :not-selected   the caller chose a DIFFERENT alternative in that dimension,
                     so this definition does not apply. A filter, not a refusal.
     :unselected     the caller chose nothing in that dimension, so which
                     definition applies is unknown. Also the answer when the
                     referenced alternative is declared by no container in this
                     document: which definition applies is then unknown for the
                     same reason, and a composition whose container lives in the
                     other document should withhold rather than hard-fail.

   Refuses on several references rather than inventing a conjunctive reading.
   PriceDefinitionShape permits them (sh:minCount 0, no sh:maxCount) but no
   filing uses them, and a matrix does not need them: the conjunction lives
   inside the cell's own condition expression, because the cells ARE the cross
   product. Defining semantics for a shape nothing has is how an arbitrary pick
   gets built in.

   Refuses twice more, both on the same principle. An alternative listed by two
   containers has no single dimension to consult. And a selection naming a value
   that is not a cell of the dimension it was filed under is a caller error, not
   a choice of some sibling cell: read as a sibling it drops every definition in
   that dimension and returns a confident short number, which on PG&E E-TOU-C is
   0.26104 against 0.46886 with nothing in the result to say so."
  [pd selection idx]
  (let [{:keys [dim-of]} idx
        refs (index/as-vec (:urpx/referencesProfileAlternative pd))]
    (cond
      (empty? refs) :unconstrained

      (< 1 (count refs))
      (throw (ex-info (str "urpx:referencesProfileAlternative names " (count refs)
                           " alternatives on " (pr-str (:jsonld/id pd))
                           ". A " (or (:jsonld/type pd) "node")
                           " scopes to ONE alternative, the cell; a "
                           "multi-dimensional choice is expressed as one ProfileAlternatives "
                           "whose AlternativeDimensions holds several dimensions and whose "
                           "alternatives enumerate the cells. There is no rule here for "
                           "combining several, and picking one would be arbitrary.")
                      {:reason    :multiple-profile-alternatives
                       :node-id   (:jsonld/id pd)
                       :node-type (:jsonld/type pd)
                       :ids (mapv :jsonld/id refs)}))

      :else
      (let [aid  (:jsonld/id (first refs))
            dims (get dim-of aid)]
        (when (< 1 (count dims))
          (throw (ex-info (str "urpx:ProfileAlternative " (pr-str aid) ", referenced by "
                               (pr-str (:jsonld/id pd)) ", is listed by " (count dims)
                               " urpx:ProfileAlternatives containers: " (pr-str (vec dims))
                               ". The selection is keyed by container, so there is no one "
                               "dimension to read the caller's choice from.")
                          {:reason        :ambiguous-profile-alternative
                           :node-id       (:jsonld/id pd)
                           :node-type     (:jsonld/type pd)
                           :alternative-id aid
                           :containers (vec dims)})))
        (let [dim    (first dims)
              chosen (get selection dim)]
          (cond
            (nil? chosen)  :unselected
            (= chosen aid) :selected
            :else          :not-selected))))))

(defn- merge-alternative-indexes
  "One alternatives index over several documents.

   A composition is resolved against ONE `:profile-alternatives` map, so it has
   to be read against ONE set of containers. Indexing each document separately
   made the same PriceDefinition :selected on the modifier's own tier path and
   :not-selected in a summary judged against the base plan's index, in a single
   result, which is a silently short number rather than a refusal. It also made
   a container declared by only one of the documents invisible to the other,
   so a definition scoped across the pair could never be selected at all.

   Merging on container @id, so a container declared identically by both
   documents collapses rather than reading as two dimensions. A ProfileAlternative
   genuinely listed by two DIFFERENT containers still refuses, in a composition
   for the same reason it refuses within one document."
  [indexes]
  (let [cells (reduce (fn [m i] (merge-with into m (:cells i))) {} indexes)]
    {:cells  cells
     :dim-of (reduce-kv (fn [m cid aids]
                          (reduce (fn [m a] (update m a (fnil conj []) cid)) m aids))
                        {} cells)}))

(defn- index-with-price-set-alternatives
  "Fold the alternatives that urpx:PriceSetAlternative entities actually
   reference into the index's cells.

   The container's urpx:hasProfileAlternative list and the alternatives its
   PriceSetAlternatives name are two different sets, and nothing in the shapes
   forces them to agree. Where they differ, validating only against the
   container would REFUSE a selection the price path resolves correctly, and
   rejecting a valid document is the expensive direction."
  [alt-index prices]
  (reduce (fn [idx psas]
            (let [cid  (get-in psas [:urpx/referencesProfileAlternatives :jsonld/id])
                  aids (keep #(get-in % [:urpx/referencesProfileAlternative :jsonld/id])
                             (index/as-vec (:urpx/hasPriceSetAlternative psas)))]
              (if (and cid (seq aids))
                (-> idx
                    (update-in [:cells cid] (fnil into #{}) aids)
                    (update :dim-of
                            #(reduce (fn [m a]
                                       (update m a (fn [v] (vec (distinct (conj (or v []) cid))))))
                                     % aids)))
                idx)))
          alt-index
          (index/as-vec (:urpx/hasPriceSetAlternatives prices))))

(defn- validate-selection!
  "Refuse a `:profile-alternatives` entry whose value is not a cell of the
   container it is keyed under, or whose key names no container at all.

   Checked once per document rather than per PriceDefinition, because the
   damage is not confined to the definition-level slot: the SAME typo on the
   urpx:PriceSetAlternatives path drops the whole ledger and answers short with
   no mark anywhere. On PG&E E-TOU-C composed with its vintage-PCIA modifier, a
   stale departure-vintage id returned 0.46886 where the correct id returns
   0.49859, and the PCIA ledger was simply absent from the result.

   A key is checked against `known-containers`, a delay over every container
   the documents being resolved declare in any version, and is dereferenced
   only for a key `alt-index` lacks. One selection map is passed to the base
   plan and to every modifier, and a horizon spans versions, so a key meant for
   another document or another version is accepted here; a key no document
   declares is a mistyped container, which withholds every price it was meant
   to select."
  [selection alt-index known-containers]
  (doseq [[dim chosen] selection
          :let [known (get (:cells alt-index) dim)]]
    (cond
      known
      (when-not (contains? known chosen)
        (throw (ex-info (str ":profile-alternatives chooses " (pr-str chosen)
                             " for " (pr-str dim) ", which does not list it. Name one of "
                             (pr-str (vec (sort known)))
                             ", or drop the key. Compared for equality a stale or mistyped id "
                             "reads as a deliberate choice of some other cell, which silently "
                             "drops every price in that dimension.")
                        {:reason :unknown-profile-alternative
                         :dimension dim
                         :chosen chosen
                         :alternatives (vec (sort known))})))

      (not (contains? @known-containers dim))
      (let [containers (vec (sort @known-containers))]
        (throw (ex-info (str ":profile-alternatives is keyed by " (pr-str dim)
                             ", which names no urpx:ProfileAlternatives container in the "
                             "documents being resolved. Key the selection by one of "
                             (pr-str containers) ", or drop the key. A mistyped container "
                             "selects nothing, so every price it was meant to select is withheld.")
                        {:reason :unknown-profile-alternatives-container
                         :dimension dim
                         :chosen chosen
                         :containers containers}))))))

(defn- pd-active-at-tou?
  "True if `pd` should be considered active at the resolved TOU period.
   PDs without :urpx/touPeriodNumber are always TOU-active (flat).
   PDs with :urpx/touPeriodNumber must match the resolved period number.

   This used to answer a second, unrelated question first: it returned false
   for any PD carrying its own urpx:CalculationMethod, excluding recurring
   charges. Whether a charge recurs has nothing to do with which TOU period is
   active, and bundling the two meant the recurring exclusion inherited this
   predicate's application. On the modifier path, where the TOU gate only runs
   when there is a period to gate against, the recurring test was skipped along
   with it, so the same PD shape was dropped from a base plan and kept in a
   modifier. Recurring charges now surface on both paths, carrying
   :urpx.resolved/interval-duration to say what they are charged per."
  [pd resolved-period-num]
  (or (nil? (:urpx/touPeriodNumber pd))
      (= (:urpx/touPeriodNumber pd) resolved-period-num)))

(defn- mi-by-id
  "Index PlanElements.hasMetricInput by :jsonld/id. Used to decorate each
   resolved Ledger with its MetricInput's type, measurementKind, and
   unitOfMeasure — without this, marginal-unit-rate can't tell a per-kWh
   energy ledger from a per-meter or per-household service-charge ledger."
  [plan-elements]
  (into {} (map (juxt :jsonld/id identity))
        (index/as-vec (:urpx/hasMetricInput plan-elements))))

(defn- ledger-metric-info
  "Return the resolved-shape keys that describe `ledger`'s referenced
   MetricInput. Looks up the first :urpx/referencesMetricInput Ref against
   `mi-index` (a {id -> MetricInput} map). Missing fields are simply
   omitted; consumers that need a particular dimension (e.g. energy-only
   marginal sums) test on what's present."
  [ledger mi-index]
  (let [mi-id (some-> ledger :urpx/referencesMetricInput index/as-vec first :jsonld/id)
        mi    (when mi-id (mi-index mi-id))
        spec  (index/single (:urpx/hasMetricSpecification mi)
                            "hasMetricSpecification nodes on one MetricInput")]
    (cond-> {}
      mi-id (assoc :urpx.resolved/metric-input-id mi-id)
      (:urpx/metricInputType mi)
      (assoc :urpx.resolved/metric-input-type (:urpx/metricInputType mi))
      (:urpx/measurementKind spec)
      (assoc :urpx.resolved/measurement-kind (:urpx/measurementKind spec))
      (:urpx/unitOfMeasure spec)
      (assoc :urpx.resolved/unit-of-measure (:urpx/unitOfMeasure spec))
      ;; urpx:flowDirection: forward, reverse, net or bidirectional. Surfaced
      ;; because an export ledger and an import ledger are not the same kind of
      ;; thing, and a consumer summing per-kWh charges needs to tell them apart.
      (:urpx/flowDirection spec)
      (assoc :urpx.resolved/flow-direction (:urpx/flowDirection spec)))))

(defn- pd-active-in-month?
  "True if `pd`'s urpx:hasApplicabilityRule constraints (if any) admit the
   query date's month-of-year. Each rule that carries a non-empty
   urpx:month list contributes a conjunctive constraint; a rule without
   urpx:month is permissive at this layer (any urpx:hasConditionExpression
   gating is deferred). PDs without applicability rules
   are always month-active.

   A rule may be stated inline OR as a bare {@id} reference to one defined
   elsewhere in the document, since RDF merges a reference with its definition.
   `rules-by-id` dereferences the latter. Without that step a bare reference
   carries no urpx:month, reads as permissive, and the month gate silently
   stops applying: the PD resolves in every month rather than the ones it
   names. That is a wrong answer rather than an error, which is why the
   dereference happens here rather than being left to the caller."
  [pd ^java.time.LocalDate date rules-by-id]
  (let [rules (index/as-vec (:urpx/hasApplicabilityRule pd))]
    (or (empty? rules)
        (let [m (.getMonthValue date)]
          (every? (fn [rule]
                    (let [rule   (if (:urpx/month rule)
                                   rule
                                   (or (get rules-by-id (:jsonld/id rule)) rule))
                          months (index/as-vec (:urpx/month rule))]
                      (or (empty? months)
                          (some #{m} months))))
                  rules)))))

(defn- condition-gates
  "The urpx:ConditionExpression gates on `price` and its PriceDefinition that
   this library does not evaluate, as a vector of {:gate-id, :gate-name,
   :on} maps, or nil when there are none.

   A gated Price fires UNCONDITIONALLY at resolution, because nothing here
   evaluates the predicate. That is a silent assumption of true, and it is
   epistemically the same position as an event-driven price: it may or may not
   apply. Composing SCE TOU-D-PRIME with its CPP modifier returns -0.13897 at
   summer peak and -0.46336 off-peak, a customer being paid to consume, because
   a non-event credit gated on \"is a CPP event called today\" and a credit
   gated on a grandfathered-customer attribute both apply at every instant.

   Marking rather than excluding, deliberately. Excluding wholesale is not
   available as a default: export price matrices are gated too, the gate being
   how the matrix is indexed, and dropping those would delete the filing. This
   surfaces the assumption so a consumer can compute a defensible number today,
   and decides nothing.

   Only gates carried by the Price or its PriceDefinition count. A condition
   inside a urpx:ProfileAlternative is correct by construction, since URPX
   requires one on every alternative; a condition inside a urpx:EligibilityRule
   answers who may take the tariff, not which price applies. Neither hangs off
   a Price, so reading only these two attachment points excludes both.

   A rule carrying only urpx:month is NOT a gate here: that constraint IS
   evaluated, by `pd-active-in-month?`. Marking it would report a gate the
   resolver honors, which is the false positive that would make the flag
   useless on the filings that use months heavily.

   A rule may be inline or a bare {@id} reference, so `by-id` dereferences
   the latter, for the same reason `pd-active-in-month?` does."
  [price pd by-id]
  (let [deref-rule (fn [r] (if (:urpx/hasConditionExpression r)
                             r
                             (or (get by-id (:jsonld/id r)) r)))
        gates (for [[owner src] [[price :price] [pd :price-definition]]
                    :when owner
                    rule  (concat (map deref-rule (index/as-vec (:urpx/hasApplicabilityRule owner)))
                                  (index/as-vec (:urpx/hasApplicabilityGroup owner)))
                    :when (:urpx/hasConditionExpression rule)]
                (cond-> {:urpx.resolved/gate-on src}
                  (:jsonld/id rule)   (assoc :urpx.resolved/gate-id (:jsonld/id rule))
                  (:urpx/name rule)   (assoc :urpx.resolved/gate-name (:urpx/name rule))))]
    (seq (vec (distinct gates)))))

(defn- node-season-status
  "Whether `node`'s urpx:appliesInSeason refs admit `date`'s month-of-year. One
   of :unqualified (the node names no season), :in-season, :out-of-season, or
   :not-evaluable.

   An unresolvable ref divides on `seasons-declared?`, which says whether the
   documents passed to this call declare any urpx:Season at all. If they do, a
   ref matching none of them is a typo and this REFUSES. If they declare none,
   the document that would have declared it was simply not passed, so the
   answer is unknown and this reports :not-evaluable. PG&E's E-TOU-C
   baseline-tier modifier is the second case: it declares no Season of its own
   and names the base plan's, so resolving it alone cannot evaluate the gate
   and resolving it composed can.

   That is the same line `pd-alternative-status` already draws for an
   alternative declared by no container in this document, and for the same
   stated reason: a composition whose other half lives in another document
   should withhold rather than hard-fail.

   Disjunctive across refs, which is what the shapes state: appliesInSeason is
   sh:minCount 0 with no sh:maxCount on ServiceBracketShape and on
   ServiceTiersShape alike, so one admitting season is enough. That is what
   lets one bracket serve two filings whose seasons are declared separately.

   Three things separate this from `pd-active-in-season?`, which gates
   PriceDefinitions and keeps its present behavior:

   - :not-evaluable is not :out-of-season. SeasonShape sets urpx:month
     sh:minCount 0 and offers startDate/endDate instead, so a Season carrying
     no month is valid and this library's month-only gate cannot read it.
     Reporting it as out of season would be a confident answer to a question
     never asked.
   - An unresolvable ref refuses instead of failing to match. A bracket is
     SELECTED by this answer, so a typo would silently reduce a rung to one
     with no bounds, which a consumer reads as unbounded; a PriceDefinition
     that fails to match merely does not apply.
   - A single ref written as a bare map is normalized. The coercion decoder
     produces a vector for urpx.schema/ServiceBracket, but a document
     assembled in code need
     not have been through it.

   `resolve-season` is a function of @id, not the seasons index itself, so a
   composition can widen what a ref reaches, and it runs in both directions: a
   base plan's bracket can name a urpx:Season a modifier declares, and a
   modifier's bracket can name one the base plan declares. PG&E's E-TOU-C
   baseline-tier overlay is the second case and declares no Season of its own,
   so composing is what makes its 120 brackets evaluable at all."
  [node resolve-season seasons-declared? ^java.time.LocalDate date]
  (let [refs (season-refs node)]
    (if (empty? refs)
      :unqualified
      (let [m       (.getMonthValue date)
            seasons (mapv (fn [ref]
                            (let [sid (:jsonld/id ref)]
                              (or (resolve-season sid)
                                  ;; Nothing here declares ANY Season, so the
                                  ;; ref is not a typo: the document that
                                  ;; declares it was not passed to this call.
                                  ;; Unknown, and the caller clears it by
                                  ;; composing or by supplying :by-id. This is
                                  ;; the line pd-alternative-status already
                                  ;; draws for an alternative no container in
                                  ;; this document declares.
                                  (when-not seasons-declared?
                                    {::absent true})
                                  (throw (ex-info
                                          (str "urpx:appliesInSeason on "
                                               (pr-str (:jsonld/id node)) " names urpx:Season "
                                               (pr-str sid) ", which is declared nowhere in the "
                                               "documents passed to this call, though others "
                                               "are. Season selection cannot be evaluated, and "
                                               "reading the ref as out-of-season would leave "
                                               "the rung with no bounds at all, which a "
                                               "consumer reads as unbounded.")
                                          {:reason    :unresolvable-season-ref
                                           :season-id sid
                                           :node-id   (:jsonld/id node)
                                           :node-type (:jsonld/type node)})))))
                          refs)]
        (cond
          (some (fn [s] (some #{m} (index/as-vec (:urpx/month s)))) seasons) :in-season
          ;; A monthless Season among the refs leaves the gate unevaluated, so
          ;; the answer is unknown rather than no, even when a sibling ref
          ;; carries months that exclude this one. An absent declaration is
          ;; unknown for the same reason and reports the same way.
          (some (fn [s] (or (::absent s) (empty? (index/as-vec (:urpx/month s))))) seasons) :not-evaluable
          :else                                               :out-of-season)))))

(defn- make-season-status
  "The single urpx:appliesInSeason reader, closed over every urpx:Season
   reachable from the documents in scope and over one query date.

   There were three readers of this property and they disagreed about more than
   strictness (URPX-8yp): one skipped an unresolvable ref silently, one refused
   on several refs, and one distinguished a Season carrying no urpx:month from a
   Season that excludes the month. Tier selection, PriceDefinition gating and
   urpx:PriceSummary member gating now ask the same question here.

   Whether ANY Season is reachable is computed once, because it is what
   separates a typo from a document that was not passed, and a per-ref guess at
   it would answer differently depending on which ref failed first. `by-id`
   spans every document in a composition, so a plan's node can name a Season a
   modifier declares."
  [seasons by-id ^java.time.LocalDate date]
  (let [own       (into {} (map (juxt :jsonld/id identity)) seasons)
        resolve-season (fn [sid]
                         (or (get own sid)
                             (let [n (get by-id sid)]
                               (when (= "urpx:Season" (:jsonld/type n)) n))))
        declared? (boolean (or (seq own)
                               (some #(= "urpx:Season" (:jsonld/type %)) (vals by-id))))]
    (fn [node] (node-season-status node resolve-season declared? date))))

(defn- pd-active-in-season?
  "Whether `pd`'s urpx:appliesInSeason gate admits this instant, given the
   `season-status` reader from `make-season-status`.

   Only :out-of-season excludes. A PriceDefinition naming no season applies, one
   in season applies, and one whose gate could NOT be evaluated also applies:
   this used to drop it instead, which turned a Season carrying no urpx:month
   into a price silently missing from the sum. That is the failure this library
   spends its refusals on, and the same reasoning `condition-gates` documents
   for a predicate nothing here evaluates. An unresolvable ref does not reach
   this at all, because `node-season-status` refuses it where a Season was
   reachable to match against.

   urpx:PriceSummary members do NOT go through this: `make-member-status` reads
   the status directly, because a subtotal has a third state for a member whose
   applicability is unknown and a boolean filter does not."
  [pd season-status]
  (not= :out-of-season (season-status pd)))

(defn- additive-plus-lowest-rung
  "The members of `xs` that contribute together: every untiered one, plus the
   lowest-numbered rung of the ladder if there is one.

   urpx:serviceTierNumber marks a block-tier ladder, which is mutually
   exclusive, so only the marginal rung applies; a bare entry is an additive
   component and all of them apply. marginal-unit-rate has resolved a ledger
   this way since 0.20.0. A urpx:PriceSummary has exactly the same problem one
   level down and was summing the whole ladder: SCE Schedule D's discountable
   subtotal names both Conservation Incentive Adjustment rungs, -0.03740
   baseline and +0.06368 over-baseline, which are alternatives per kWh and
   never additive. Summing both gave 0.37353, a subtotal matching no bill any
   customer can receive, and understated the CARE discount by 10.4%.

   `tier-of` reads the tier number, since a resolved tier and a raw Price spell
   it differently."
  [xs tier-of]
  (let [{tiered true bare false} (group-by #(some? (tier-of %)) xs)]
    (cond-> (vec bare)
      (seq tiered) (conj (first (sort-by tier-of tiered))))))

(defn- lowest-rung-ties
  "The members of `xs` at the lowest tier number, when there are two or more of
   them, else nil."
  [xs tier-of]
  (let [tiered (filter #(some? (tier-of %)) xs)]
    (when (seq tiered)
      (let [lowest (apply min (map tier-of tiered))
            at     (filterv #(= lowest (tier-of %)) tiered)]
        (when (< 1 (count at)) at)))))

(defn- make-member-status
  "Return a predicate deciding whether a urpx:PriceSummary member is in effect
   at the instant being resolved.

   A summary is a subtotal, and `price-value` turns it into a unit price AT AN
   INSTANT. Every other price path in this library gates on that instant, so
   summing a winter line item into a July resolution is simply the wrong
   number: a summary naming a summer price and a winter price used to return
   the sum of both. Members are therefore gated by exactly the predicates that
   gate a tier, applied to the member's own PriceDefinition.

   Gating rather than refusing is what lets ONE summary per base plan name all
   of the discountable line items and still be correct in every TOU period. The
   alternative would force one summary per (plan, period, season), which is the
   same information stated many times and many more chances to state it wrong.

   A member carrying no urpx:referencesPriceDefinition, or naming one absent
   from these documents, is kept: there is nothing to gate on, and dropping it
   would understate the subtotal silently.

   `period-num` is the TOU period resolved for the document the MEMBERS live
   in, which for a cross-document discount is the base plan, not the modifier.

   Returns :active, :inactive or :unknown rather than a boolean, because
   urpx:referencesProfileAlternative gives a member a third state. A definition
   scoped to an alternative the caller did not choose is :inactive, exactly as
   an out-of-season one is. A definition in a dimension the caller chose
   NOTHING in is :unknown: excluding it silently understates the subtotal and
   including it overstates it, so the summary is reported unvaluable and the
   caller gets a named refusal instead of a number.

   Gating tiers on the alternative while leaving summaries ungated is the
   failure this guards against, and it is a wrong number rather than a missing
   one: on SCE Schedule D composed with its CARE modifier, scoping the
   generation definition to an alternative and choosing a different one left
   the CARE discount at -0.10070125 against a correct -0.06247800, and the
   marginal rate came back 0.08043875 against 0.11866200, a third low with
   nothing in the result to say so.

   `period-num` ::no-period means there is no TOU period to match, so a member
   whose PriceDefinition names one is :unknown."
  [by-id period-num ^LocalDate date rules-by-id season-status alt-status]
  (let [no-period? (= ::no-period period-num)
        period-num (when-not no-period? period-num)]
    (fn [member]
      (let [pd (get by-id (get-in member [:urpx/referencesPriceDefinition :jsonld/id]))]
        ;; The member's OWN urpx:touPeriodNumber gates first, and independently of
        ;; whether it has a PriceDefinition. URPX puts that property on Price as
        ;; well as PriceDefinition, and only the PD was read: six TOU variants of
        ;; one charge, sharing an undifferentiated PD, all counted toward the
        ;; subtotal at every instant. SDG&E EV-TOU's CARE discount came out
        ;; -0.9255435 against a correct -0.2999885, overstated 3.09x.
        (cond
          (not (and (price-active-at-tou? member period-num)
                    (or (nil? pd)
                        (and (or no-period? (pd-active-at-tou? pd period-num))
                             (pd-active-in-month? pd date rules-by-id)
                             (not= :out-of-season (season-status pd))))))
          :inactive

          (nil? pd) :active

          (and no-period? (some? (:urpx/touPeriodNumber pd))) :unknown

          ;; A season gate that could not be evaluated leaves this member's
          ;; applicability UNKNOWN, and a subtotal has a state for that where a
          ;; boolean filter does not. Excluding it understates the subtotal and
          ;; including it overstates it, which is the same reasoning the
          ;; unselected-alternative case below already follows.
          (= :not-evaluable (season-status pd)) :unknown

          :else
          (case (alt-status pd)
            :not-selected :inactive
            :unselected   :unknown
            :active))))))

(defn- price-value
  "The unit price for `price`, and when it cannot be determined, why.

   Returns {:value BigDecimal-or-nil, :unvaluable {...}-or-nil}.

   Three cases:

     urpx:unitPrice present            the value, as always.
     urpx:multiplier + basedOnPrice    a DERIVED price: the multiplier times
                                       either the referenced Price's unit price
                                       or the sum of a referenced PriceSummary's
                                       urpx:includesPrice members. `by-id` is an
                                       @id index over the document being
                                       resolved.
     anything else                     unvaluable, with a reason.

   Reporting unvaluable rather than returning nil is the point. Tiers are the
   documented way to read block pricing, so a nil unit price is read by a
   consumer as free; a 20 percent discount silently becoming 100 percent is the
   worst available outcome. A named refusal is recoverable.

   Cross-document references are deliberately unvaluable, not an error: this
   library resolves one document at a time, and a summary whose members live in
   another file cannot be summed here. Valuing it wrong would be worse than
   declining to."
  [price by-id member-status]
  (letfn [(unvaluable [reason detail]
            {:value nil :unvaluable (cond-> {:reason reason :price-id (:jsonld/id price)}
                                      detail (assoc :detail detail))})
          (member-node [m]
            ;; a member may be inline or a bare reference into these documents
            (if (:urpx/unitPrice m) m (get by-id (:jsonld/id m))))]
    (cond
      (:urpx/unitPrice price)
      {:value (:urpx/unitPrice price) :unvaluable nil}

      (and (:urpx/multiplier price) (:urpx/basedOnPrice price))
      ;; 0..N upstream, read DISJUNCTIVELY: the values are candidate bases, of
      ;; which exactly one is expected to be present in the documents handed to
      ;; this call. That is what lets one discount Price serve a modifier
      ;; applying to many base plans: it names every base's subtotal, and the
      ;; composition selects by which base was actually passed in.
      ;;
      ;; The alternative reading is conjunctive (sum the bases), which
      ;; urpx:PriceSummary already covers through urpx:includesPrice. URPX
      ;; fixes the cardinality and says nothing about multiplicity; the
      ;; disjunctive reading is this library's, recorded in doc/ and raised
      ;; upstream rather than left implicit.
      ;;
      ;; Safety is unchanged in the case that matters: ZERO resolvable
      ;; candidates is still an error. Only a bare @id can be absent, because
      ;; index-walk indexes nodes carrying both @id and @type, so a mistyped
      ;; IRI cannot masquerade as a candidate belonging to another plan.
      (let [candidates (index/as-vec (:urpx/basedOnPrice price))
            ids        (mapv :jsonld/id candidates)
            present    (filterv #(contains? by-id %) ids)]
        (cond
          (empty? present)
          (unvaluable :unresolved-reference
                      (str "urpx:basedOnPrice names "
                           (if (= 1 (count ids))
                             (pr-str (first ids))
                             (str (count ids) " candidate bases " (pr-str ids)))
                           ", none of which is in the documents passed to this call. "
                           "clj-urpx resolves the documents it is given and does not "
                           "dereference across files."))

          (< 1 (count present))
          (unvaluable :ambiguous-basis
                      (str "urpx:basedOnPrice names " (count present)
                           " candidate bases that are ALL present here " (pr-str present)
                           ". Candidates are read as alternatives, exactly one of which "
                           "should be in scope; two or more is not a choice this library makes."))

          :else
          (let [target (get by-id (first present))]
            (cond

              (= "urpx:PriceSummary" (:jsonld/type target))
              (let [members (index/as-vec (:urpx/includesPrice target))
                    nodes   (mapv member-node members)]
                (cond
                  (some nil? nodes)
                  (unvaluable :summary-member-unresolved
                              (str "urpx:PriceSummary " (pr-str (:jsonld/id target))
                                   " names " (count members) " member(s), "
                                   (count (filter nil? nodes)) " of which are not in "
                                   "the documents passed to this call."))

                  :else
                  ;; only the members in effect at this instant: a subtotal that
                  ;; summed inactive line items would be a confident wrong number
                  (let [;; Price first, then its PriceDefinition. URPX puts
                        ;; urpx:serviceTierNumber on both, tier-info at the
                        ;; resolved-tier site has read it that way all along,
                        ;; and reading only the Price here made the fix
                        ;; unreachable for filings that carry the
                        ;; discriminator on the definition. The fixture that
                        ;; passed then carried it on the Price.
                        tier-of (fn [m]
                                  (or (:urpx/serviceTierNumber m)
                                      (:urpx/serviceTierNumber
                                       (get by-id (get-in m [:urpx/referencesPriceDefinition
                                                             :jsonld/id])))))
                        unknown (filterv #(= :unknown (member-status %)) nodes)
                        in-effect (filterv #(= :active (member-status %)) nodes)
                        ties   (lowest-rung-ties in-effect tier-of)
                        active (additive-plus-lowest-rung in-effect tier-of)
                        values (mapv :urpx/unitPrice active)]
                    (cond
                      ;; A member whose applicability is UNKNOWN cannot be
                      ;; excluded (the subtotal would be short) or included (it
                      ;; would be wrong). Refuse, and name it.
                      (seq unknown)
                      (unvaluable :summary-member-unselected
                                  (str "urpx:PriceSummary " (pr-str (:jsonld/id target))
                                       " names " (count unknown) " member(s) whose "
                                       "PriceDefinition is scoped to a urpx:ProfileAlternative "
                                       "the caller chose nothing for, so the subtotal is not "
                                       "defined here. The definitions are "
                                       (pr-str (vec (distinct (keep #(get-in % [:urpx/referencesPriceDefinition
                                                                                :jsonld/id])
                                                                    unknown))))
                                       "; read urpx:referencesProfileAlternative on each, and pass "
                                       ":profile-alternatives keyed by the urpx:ProfileAlternatives "
                                       "that lists the alternative it names."))

                      ties
                      (unvaluable :summary-ambiguous-tier
                                  (str "urpx:PriceSummary " (pr-str (:jsonld/id target))
                                       " has " (count ties) " members in effect here at "
                                       "the lowest tier number of its block-tier ladder, "
                                       (tier-of (first ties)) ", so the rung that enters "
                                       "the subtotal is undefined. The members are "
                                       (pr-str (mapv :jsonld/id ties)) "."))

                      (empty? active)
                      (unvaluable :summary-no-active-members
                                  (str "urpx:PriceSummary " (pr-str (:jsonld/id target))
                                       " names " (count members) " member(s), none of "
                                       "which is in effect at this instant, so the "
                                       "subtotal it aggregates is not defined here."))

                      (some nil? values)
                      (unvaluable :summary-member-unresolved
                                  (str "urpx:PriceSummary " (pr-str (:jsonld/id target))
                                       " has " (count (filter nil? values)) " member(s) in "
                                       "effect here that carry no urpx:unitPrice."))

                      :else
                      {:value (.multiply ^java.math.BigDecimal (:urpx/multiplier price)
                                         ^java.math.BigDecimal (reduce #(.add ^java.math.BigDecimal %1 %2)
                                                                       0M values))
                       :unvaluable nil}))))

              (:urpx/unitPrice target)
              {:value (.multiply ^java.math.BigDecimal (:urpx/multiplier price)
                                 ^java.math.BigDecimal (:urpx/unitPrice target))
               :unvaluable nil}

              :else
              (unvaluable :basis-not-valuable
                          (str "urpx:basedOnPrice resolves to " (pr-str (:jsonld/type target))
                               " which carries no urpx:unitPrice."))))))

      :else
      (unvaluable :no-unit-price
                  "carries no urpx:unitPrice, and no urpx:multiplier + urpx:basedOnPrice pair to derive one from"))))

(defn- price-fields
  "The resolved unit-price entry for `price`, plus :urpx.resolved/unvaluable
   when there is no value and a reason instead.

   One `price-value` call per price, deliberately: valuing a derived price sums
   a summary's members, and a filing carries thousands of prices."
  [price by-id member-status]
  (let [{:keys [value unvaluable]} (price-value price by-id member-status)]
    (cond-> {:urpx.resolved/unit-price value}
      unvaluable (assoc :urpx.resolved/unvaluable unvaluable))))

(defn plan-zone
  "The ZoneId a plan or modifier declares as urpx:timezoneIdentifier, or nil.

   THE ZONE A URPX DOCUMENT'S BOUNDS ARE WRITTEN IN. urpx:effectiveDateTime,
   urpx:expirationDateTime and every TOU bracket written without an offset are
   wall-clock readings IN THIS ZONE, so reading them in any other is reading a
   different moment.

   Read from the urpx:RatePlan or the urpx:RatePlanModifier, so both halves of
   a composition can be zoned."
  ^ZoneId [doc]
  (when-let [tz (:urpx/timezoneIdentifier doc)]
    (ZoneId/of tz)))

(defn require-zone
  "The plan's declared zone, or REFUSE.

   A document that states no zone states no moment: its bounds are wall-clock
   readings with nothing to anchor them, so whether an instant falls inside one
   has no answer. Falling back to the caller's zone is what this replaces, and
   it silently made the answer depend on how the caller spelled the instant."
  ^ZoneId [doc]
  (or (plan-zone doc)
      (throw (ex-info (str "No urpx:timezoneIdentifier on " (pr-str (:jsonld/id doc))
                           ", so its wall-clock bounds anchor to no moment and "
                           "whether an instant falls inside them has no answer. "
                           "Reading them in the caller's zone is what made the "
                           "same instant resolve differently depending on how it "
                           "was spelled.")
                      {:reason :urpx.price/no-timezone
                       :plan-id (:jsonld/id doc)}))))

(defn version-effective-at
  "The instant `version` takes effect, as a ZonedDateTime in `zone`, or nil if
   it does not say.

   ZONE REQUIRED, NOT OPTIONAL. A bound written without an offset is a naive
   wall-clock reading; it names a moment only once anchored, and every zone
   anchors it to a different one. A bound written with an offset names its
   instant already and is moved to `zone` unchanged. An optional zone would
   make the unzoned call the easy one and the wrong one, which is how this
   namespace came to compare a caller's wall clock against a publisher's.
   urpx:effectiveDateTime is preferred; urpx:effectiveDate is read as the
   start of that day. Both are sh:minCount 0, so a conformant version may state
   neither, and this returns nil rather than inventing an epoch for it.

   Public as THE SINGLE READER of a version's effective instant.
   urpx.scenario-cost-model re-derived this and got it subtly wrong, requiring
   urpx:effectiveDateTime and so silently dropping a conformant version that
   states only urpx:effectiveDate. Two namespaces disagreeing about the same
   date is the drift this prevents."
  [version ^ZoneId zone]
  (schema/in-zone (or (:urpx/effectiveDateTime version) (:urpx/effectiveDate version))
                  zone))

(defn version-expires-at
  "The instant `version` stops applying, as a ZonedDateTime in `zone`, or nil.

   Named rather than inlined because it was re-derived at three call sites and
   the resolved marks emitted it as a naive LocalDateTime while the comparison
   used something else. One reader, one type."
  [version ^ZoneId zone]
  (schema/in-zone (or (:urpx/expirationDateTime version) (:urpx/expirationDate version))
                  zone))

(defn version-expired-by?
  "True if `version` states a hard expiration at or before `t`.

   `t` is a ZonedDateTime and the bound is anchored in `zone` before comparing,
   so the answer is about one MOMENT rather than about two wall clocks that may
   be in different places.

   Public alongside `version-effective-at`, and for the same reason: it is the
   single reader of a version's end bound.

   urpx:expirationDate / urpx:expirationDateTime only. The version shapes also
   carry urpx:expectedEndDate and urpx:expectedEndDateTime, which are
   deliberately NOT read here: an expected end anticipates a successor rather
   than terminating anything, so a version past its expected end is still the
   one in force until a later version exists. Treating the two alike would
   silently drop a plan that outlived its own forecast."
  [version ^ZonedDateTime t ^ZoneId zone]
  (let [exp (version-expires-at version zone)]
    (boolean (and exp (not (.isAfter ^ZonedDateTime exp t))))))

(defn- select-version-at
  "Choose among several candidate snapshots for one plan: the latest whose
   urpx:effectiveDateTime is at or before `t` and which has not expired by `t`.

   Refuses rather than guesses in three cases, for the same reason every other
   refusal here exists. A candidate with no effective instant cannot be ordered
   against the others; a tie on the latest effective instant is not a choice
   this library can make; and if nothing has taken effect by `t` the honest
   answer is that the caller asked about an instant this plan does not cover,
   not the earliest version it happens to have.

   A version need not carry an expiration, so a version's span may be
   inferable only from the next version's start. That is exactly why the rule
   needs the full candidate set rather than one snapshot."
  [candidates ^ZonedDateTime t ^ZoneId zone plan-id]
  (let [undated (remove #(version-effective-at % zone) candidates)]
    (when (seq undated)
      (throw (ex-info (str "Cannot choose a version for " (pr-str plan-id) ": "
                           (count undated) " of " (count candidates) " candidate snapshot(s) "
                           "state no urpx:effectiveDateTime, so they cannot be ordered "
                           "against the others. Pass :version to choose explicitly.")
                      {:reason :version-without-effective-instant
                       :plan-id plan-id
                       :ids (mapv :jsonld/id candidates)})))
    (let [live (remove #(version-expired-by? % t zone) candidates)
          eligible (filter #(not (.isAfter ^ZonedDateTime (version-effective-at % zone) t)) live)]
      (cond
        (empty? eligible)
        (throw (ex-info (str "Cannot choose a version for " (pr-str plan-id) ": none of the "
                             (count candidates) " candidate snapshot(s) is in effect at " t
                             ". The earliest takes effect at "
                             (->> candidates (keep #(version-effective-at % zone)) sort first)
                             ". Resolving an instant before a plan existed is a question "
                             "about the caller's window, not about the plan.")
                        {:reason :no-version-in-effect
                         :plan-id plan-id
                         :at t
                         :effective (mapv #(version-effective-at % zone) candidates)}))

        :else
        (let [latest (->> eligible (map #(version-effective-at % zone)) (sort) (last))
              winners (filter #(= latest (version-effective-at % zone)) eligible)]
          (if (= 1 (count winners))
            (first winners)
            (throw (ex-info (str "Cannot choose a version for " (pr-str plan-id) ": "
                                 (count winners) " snapshots share the latest effective "
                                 "instant " latest ", so there is no rule here that "
                                 "picks among them. Pass :version to choose explicitly.")
                            {:reason :ambiguous-version
                             :plan-id plan-id
                             :at t
                             :ids (mapv :jsonld/id winners)}))))))))

(defn version-covers?
  "True if `version` describes the instant `t`: in effect by then, and not
   expired.

   `t` is a ZonedDateTime and the document's naive bounds are anchored in `zone`
   first, so the comparison is between MOMENTS. Comparing wall clocks instead
   answers a different question, and was how the same instant asked in two zones
   resolved differently: an LA-midnight expiry covered 2025-12-31T20:00-08:00
   and refused the identical 2026-01-01T04:00Z.

   Public as the single reader of a version window, so a caller asking whether
   a snapshot is current gets the same answer this namespace uses to resolve it.

   A version stating no effective instant covers everything. It cannot be
   ordered against anything and nothing says it has not started, so refusing
   it would reject a conformant document over a case the vocabulary permits."
  [version ^ZonedDateTime t ^ZoneId zone]
  (let [eff (version-effective-at version zone)]
    (and (not (and eff (.isAfter ^ZonedDateTime eff t)))
         (not (version-expired-by? version t zone)))))

(def ^:private version-back-pointer
  "Version @type -> the property naming the document it is a snapshot of.
   Mirrors urpx.index's own map; both are owl:inverseOf the nesting property
   and sh:minCount 1 on their version shapes at v0.5.1."
  {"urpx:RatePlanVersion"         :urpx/forRatePlan
   "urpx:RatePlanModifierVersion" :urpx/forRatePlanModifier})

(defn- version-belongs-to?
  "True if `version` is a snapshot OF `plan`.

   Two ways to tell, because URPX states the link in either direction: the
   document nests this exact node, or the node points back at the document's
   @id through urpx:forRatePlan / urpx:forRatePlanModifier."
  [version plan nested-key]
  (let [k   (version-back-pointer (:jsonld/type version))
        ptr (when k (:jsonld/id (get version k)))]
    (if ptr
      ;; Authoritative when present, and sh:minCount 1 on both version shapes
      ;; at v0.5.1 means it usually is.
      (= ptr (:jsonld/id plan))
      ;; No back pointer, so fall back to nesting, by MEMBERSHIP rather than
      ;; by equality with "the one" nested snapshot. index/nested-version
      ;; refuses when a document nests several, which is precisely the case a
      ;; caller passes :version to resolve, so asking it here would make the
      ;; opt useless exactly where it is needed. Membership answers the
      ;; question that is actually being asked, is it one of this document's,
      ;; and it answers it for one snapshot and for several alike.
      (boolean (some #(= version %) (index/as-vec (get plan nested-key)))))))

(defn- version-opt-for
  "The caller's `:version` for THIS document, or nil to resolve normally.

   `:version` used to short-circuit before anything looked at which document
   was being resolved, and `resolve-prices-with-modifiers` threads one opts map
   to the base plan and to every modifier. So a base plan's snapshot was handed
   to each modifier as its version; that node carries no
   urpx:applicableToRatePlan, `modifier-applies-to?` therefore said no, and
   every modifier was dropped with no mark and no refusal. SCE Schedule D
   composed with CARE returned 0.31085 instead of 0.19804875, the full
   undiscounted rate, 57% high. A variant double-counted the base plan's
   ledgers at 0.68538.

   Two accepted shapes:

     a version node   applies to the document it is a snapshot of, and is
                      IGNORED for any other. Ignoring is not the silent failure
                      it replaces: the other document then resolves by its own
                      rules, which is correct, where before it was discarded.
     {@id version}    scoped explicitly, like :versions-by-plan. A document the
                      map does not name resolves normally.

   A node that is neither a version snapshot nor such a map is a caller error
   and refuses, because reading on would silently ignore what they asked for."
  [plan opts nested-key]
  (when-let [v (:version opts)]
    (cond
      (version-back-pointer (:jsonld/type v))
      (when (version-belongs-to? v plan nested-key) v)

      (and (map? v) (not (:jsonld/type v)))
      (get v (:jsonld/id plan))

      :else
      (throw (ex-info (str ":version must be a resolved version snapshot, or a map from "
                           "document @id to one. Got " (pr-str (:jsonld/type v))
                           ", which is neither, so there is no document it could apply to.")
                      {:reason :version-opt-not-a-version
                       :type (:jsonld/type v)})))))

(defn- plan-version
  "Resolve the version snapshot to read plan content from, at `zdt`.

   URPX v0.5.1 un-nests the version: a document may state the plan-to-version
   link downward (`nested-key`, a nested map), upward (urpx:forRatePlan /
   urpx:forRatePlanModifier on a top-level snapshot), or both. Both directions
   are declared owl:inverseOf and the down-pointer is sh:minCount 0, so neither
   can be assumed present.

     1. `:version` in `opts`   an explicitly resolved snapshot always wins
     2. no candidate           refuses `:urpx.price/no-version`
     3. one candidate          used as-is, whether nested or back-pointed
     4. several candidates     the one in effect at `zdt`, or a refusal

   Candidates are the nested snapshot together with everything
   `urpx.index/versions-by-plan` has filed under this plan's @id, deduplicated.

   Selection used to be `(first ...)` over the back-pointer index, which took
   whatever the index happened to hold first rather than what was in force.
   That was invisible while every filing carried exactly one version. A
   forecast client loading several versions to cover a window is the case it
   was silently wrong for."
  [plan opts nested-key ^ZonedDateTime zdt]
  (or (version-opt-for plan opts nested-key)
      (let [nested (index/nested-version plan nested-key)
            plan-id (:jsonld/id plan)
            indexed (when-let [by-plan (:versions-by-plan opts)] (get by-plan plan-id))
            ;; Deduplicated BY VALUE, not by @id. The nested snapshot and its
            ;; entry in the back-pointer index are the same node and collapse.
            ;; Two nodes sharing an @id but differing in content do NOT, as when
            ;; two filings publish one version @id at the same effective instant
            ;; while differing in urpx:hasModifierOptions. Collapsing on @id
            ;; would pick one of those silently, which is the failure this whole
            ;; function exists to remove; as two candidates they tie and are
            ;; refused instead.
            candidates (distinct (remove nil? (cons nested indexed)))]
        (case (count candidates)
          0 (throw (ex-info (str "No version snapshot for " (pr-str plan-id) ": none is "
                                 "nested under it and none was supplied. Pass "
                                 ":versions-by-plan, built by urpx.index/versions-by-plan "
                                 "over urpx.load/documents, or load the documents with "
                                 "urpx.load/documents and resolve through "
                                 "urpx.scenario-cost-model.")
                            {:reason  :urpx.price/no-version
                             :plan-id plan-id}))
          ;; ONE candidate is used whatever the instant, and whether it COVERS
          ;; the instant is reported separately by `version-covers?` rather than
          ;; refused here. With several candidates and none in effect there is
          ;; no version to resolve against at all, which is why that case
          ;; throws; with one, there is a version, it simply may not describe
          ;; the window asked about. Refusing here would also kill the
          ;; structural questions resolve-prices answers, like which season and
          ;; TOU period an instant falls in, which are legitimate to ask of a
          ;; filing outside its own window.
          1 (first candidates)
          (select-version-at candidates zdt (.getZone zdt) plan-id)))))

(defn- declared-containers
  "Every urpx:ProfileAlternatives container @id declared, or referenced from a
   urpx:PriceSetAlternatives, by any version of the documents in `doc-keys`,
   pairs of a document and the key that nests its versions. A version counts
   whether nested in the document, handed in as its `:version`, or filed under
   it in `:versions-by-plan`, so an un-nested document reaches the same
   containers as its nested form."
  [opts doc-keys]
  (letfn [(walk [acc node]
            (cond
              (map? node)
              (let [ref (get-in node [:urpx/referencesProfileAlternatives :jsonld/id])
                    acc (cond-> (into acc (keep :jsonld/id)
                                      (index/as-vec (:urpx/hasProfileAlternatives node)))
                          (and (= "urpx:ProfileAlternatives" (:jsonld/type node))
                               (:jsonld/id node))
                          (conj (:jsonld/id node))

                          ref (conj ref))]
                (reduce-kv (fn [a _ v] (walk a v)) acc node))
              (sequential? node) (reduce walk acc node)
              :else acc))]
    (reduce walk #{}
            (for [[doc nested-key] doc-keys
                  node [doc
                        (version-opt-for doc opts nested-key)
                        (get (:versions-by-plan opts) (:jsonld/id doc))]]
              node))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn resolve-prices
  "Resolve all per-energy unit prices that apply to `rate-plan` at `zdt`
   (a java.time.ZonedDateTime in the rate plan's local timezone).

   Optional `opts` map:
     :holiday?  predicate `(LocalDate -> bool)` used to evaluate a bracket's
                urpx:includeHolidays / urpx:includeNonHolidays gates. A plain
                set of LocalDates works (sets are functions). When omitted,
                a predicate is derived from the plan's embedded
                urpx:HolidayCalendar via `urpx.holidays/derive-predicate`;
                if neither is available, every date is treated as a
                non-holiday — so brackets that opt out of non-holidays
                (urpx:includeNonHolidays = false, e.g. holiday-only off-peak
                overrides) will never match.

     :profile-alternatives  a {<ProfileAlternatives @id> <ProfileAlternative @id>}
                map driving customer-profile-driven price selection, keyed by
                the ProfileAlternatives CONTAINER. It scopes both
                Prices.hasPriceSetAlternatives (income-tier CARE/FERA/Standard,
                vintage-PCIA departure year, voltage configuration) and
                PriceDefinition.referencesProfileAlternative.

                A dimension the caller says nothing about is NOT skipped: its
                prices are withheld, the ledger carries
                :urpx.resolved/unselected-alternatives, and marginal-unit-rate
                refuses if withholding them would make its sum short. A value
                the keyed container does not list is refused outright, since
                compared for equality a stale id is indistinguishable from a
                deliberate choice of some other cell. A key naming no
                container in any document being resolved is refused too.

                Programmatic ConditionExpression evaluation against a customer
                profile is a future enhancement; a consumer names the cell.

   Examples of `:holiday?`:

     ;; Most rate plans embed urpx:HolidayCalendar — no opts needed:
     (resolve-prices plan zdt)

     ;; Static set — overrides any embedded calendar:
     (def us-federal-2026
       #{(LocalDate/of 2026 1 1)    ; New Year's Day
         (LocalDate/of 2026 1 19)   ; Martin Luther King Jr. Day
         (LocalDate/of 2026 2 16)   ; Presidents Day
         (LocalDate/of 2026 5 25)   ; Memorial Day
         (LocalDate/of 2026 6 19)   ; Juneteenth
         (LocalDate/of 2026 7 3)    ; Independence Day (observed)
         (LocalDate/of 2026 9 7)    ; Labor Day
         (LocalDate/of 2026 11 11)  ; Veterans Day
         (LocalDate/of 2026 11 26)  ; Thanksgiving
         (LocalDate/of 2026 12 25)})  ; Christmas
     (resolve-prices plan zdt {:holiday? us-federal-2026})

     ;; Composed predicate — if you maintain a calendar elsewhere:
     (resolve-prices plan zdt {:holiday? (fn [d] (calendar/observed? :us-federal d))})

   Returns:
     {:urpx.resolved/season-name      String or nil
      :urpx.resolved/tou-period-name  String or nil
      :urpx.resolved/tou-period-number int or nil
      :urpx.resolved/ledgers          [LedgerResult]}

   where each LedgerResult is:
     {:urpx.resolved/ledger-id           String
      :urpx.resolved/ledger-name         String
      :urpx.resolved/ledger-type         Ref
      :urpx.resolved/metric-input-id     String  (when the ledger references one)
      :urpx.resolved/metric-input-type   Ref     (e.g. urpx:measuredMetricInput)
      :urpx.resolved/measurement-kind    Ref     (e.g. urpx:energy — present when
                                                  the MetricInput carries one;
                                                  marginal-unit-rate filters on this)
      :urpx.resolved/unit-of-measure     Ref     (e.g. urpx:kWh)
      :urpx.resolved/tiers               [TierResult]
      :urpx.resolved/unselected-alternatives [String]
                                               (present iff prices on this
                                                ledger belong to alternatives
                                                the caller chose none of)
      :urpx.resolved/withheld-prices     [WithheldPrice]
                                               (what those alternatives would
                                                have priced, enough of it to
                                                say whether a marginal sum is
                                                actually short)}

   and each TierResult is:
     {:urpx.resolved/price-definition-id String
      :urpx.resolved/price-id             String
      :urpx.resolved/price-name           String
      :urpx.resolved/unit-price           BigDecimal
      :urpx.resolved/tier-number          int      (only on block-tiered ledgers)
      :urpx.resolved/tier-name            String
      :urpx.resolved/tier-lower-bound     BigDecimal
      :urpx.resolved/tier-upper-bound     BigDecimal
      :urpx.resolved/tier-lower-bound-operator Ref
      :urpx.resolved/tier-upper-bound-operator Ref
      :urpx.resolved/tier-bounds-unresolved Map    (present INSTEAD of the four
                                                    bound keys, when which
                                                    urpx:ServiceBracket applies
                                                    is not determined here:
                                                    {:reason :scope :ids} plus
                                                    :alternatives when a cell
                                                    the caller has not chosen
                                                    is what decides it. The
                                                    tier number and unit price
                                                    are unaffected, so a
                                                    marginal sum is not short)
      :urpx.resolved/dynamic-pricing      Map      (present iff the source Price
                                                    carries urpx:hasDynamicPricing
                                                    — a CPP Event Charge, RTP
                                                    price, or other event-driven
                                                    component; consumers that
                                                    need a non-event marginal
                                                    rate filter on the absence
                                                    of this key)
      :urpx.resolved/condition-gated     [Gate]   (present iff the source Price
                                                    or its PriceDefinition
                                                    carries a
                                                    urpx:ConditionExpression
                                                    this library does not
                                                    evaluate, so the price fired
                                                    on an ASSUMPTION that its
                                                    predicate holds. Each Gate
                                                    carries :gate-id, :gate-name
                                                    and :gate-on, the last being
                                                    :price or :price-definition.
                                                    Not excluded from any
                                                    aggregate: see
                                                    marginal-unit-rate)}

   For non-tiered ledgers, :tiers has a single entry with no tier-* keys.
   For block-tiered ledgers (e.g. CPAU E-1), :tiers carries every tier sorted
   by tier-number — the caller chooses based on cumulative billing-period usage.

   The TOU period keys are nil only for a version with no urpx:TOUSchedule. A
   version with one that no period covers at `zdt` refuses
   `:urpx.price/no-tou-period`.

   A plan with no version snapshot, neither nested nor supplied through
   `:version` or `:versions-by-plan`, refuses `:urpx.price/no-version`."
  ([rate-plan zdt] (resolve-prices rate-plan zdt nil))
  ([rate-plan ^ZonedDateTime zdt opts]
   ;; PROJECT INTO THE PLAN'S ZONE, ONCE, HERE. A bound in a URPX document
   ;; written without an offset is a naive wall-clock reading in the zone the
   ;; plan declares (one with an offset is moved into that zone), and every
   ;; comparison downstream reduces the query with .toLocalDateTime. Reducing
   ;; the CALLER's zone made the answer depend on how the instant was spelled:
   ;; one moment asked as 2026-07-15T16:00-07:00 and as 2026-07-15T23:00Z gave
   ;; Peak 0.46886 and Off-Peak 0.34586 on PG&E E-TOU-C, and a version window
   ;; expiring at LA midnight covered the instant in LA and refused it in UTC.
   ;; Projecting first makes every downstream reduction local IN THE PLAN'S
   ;; ZONE, which is the frame those bounds were written in.
   (let [^ZonedDateTime zdt (.withZoneSameInstant zdt (require-zone rate-plan))
         version       (plan-version rate-plan opts :urpx/hasRatePlanVersion zdt)
         plan-elements (:urpx/hasPlanElements version)
         seasons       (index/as-vec (:urpx/hasSeasons plan-elements))
         billing       (:urpx/hasBillingModel plan-elements)
         ledgers       (index/as-vec (:urpx/hasLedger billing))
         tou-schedules (index/as-vec (:urpx/hasTOUSchedule billing))
         tier-cands    (service-tier-candidates billing)
         prices        (:urpx/hasPrices version)
         holiday?      (or (:holiday? opts)
                           (holidays/derive-predicate rate-plan version))

         date          (.toLocalDate zdt)

         season        (season-of seasons date)
         tou-period    (require-tou-period (:jsonld/id rate-plan) (:jsonld/id version)
                                           tou-schedules season zdt holiday?)
         period-num    (:urpx/touPeriodNumber tou-period)

         pd->prices    (active-prices-by-pd prices zdt opts period-num)
         pds           (index/as-vec (:urpx/hasPriceDefinition billing))
         pds-by-ledger (group-by #(get-in % [:urpx/referencesLedger :jsonld/id]) pds)
         mi-index      (mi-by-id plan-elements)
         ;; ApplicabilityRules may be referenced by bare @id from a PD; index
         ;; the typed definitions so the month gate can dereference them.
         ;; Overridable via opts for callers resolving many instants against
         ;; one plan, which would otherwise rebuild it per call.
         rules-by-id   (or (:rules-by-id opts)
                           (index/entities-of-type version "urpx:ApplicabilityRule"))
         ;; @id index for urpx:basedOnPrice: a derived Price names either a
         ;; Price or a PriceSummary, and both are looked up here. Scoped to the
         ;; document being resolved, which is the limit of what this library sees.
         by-id         (or (:by-id opts) (index/build-index version))
         ;; Summary members are gated at the instant being resolved. Supplied by
         ;; resolve-prices-with-modifiers when composing, because a discount in a
         ;; modifier names a summary whose members live in the BASE plan and must
         ;; be judged in the base plan's TOU period, not the modifier's.
         ;; Supplied by resolve-prices-with-modifiers, which spans the base
         ;; plan and every modifier; built here when resolving a plan alone.
         alt-index     (or (:alt-index opts)
                           (-> (profile-alternative-index plan-elements)
                               (index-with-price-set-alternatives prices)))
         _             (validate-selection! (:profile-alternatives opts) alt-index
                                            (or (:alt-containers opts)
                                                (delay (declared-containers
                                                        opts [[rate-plan :urpx/hasRatePlanVersion]]))))
         alt-status    #(pd-alternative-status % (:profile-alternatives opts) alt-index)
         ;; What a ServiceBracket or ServiceTiers qualifier is read against.
         ;; The version's own hasSeasons first; then :by-id, type-checked.
         ;; :by-id is composition-wide when composing, spanning the base plan
         ;; and every modifier, so the fallback is what lets THIS plan's
         ;; bracket name a urpx:Season a modifier document declares. It covers
         ;; only this document when resolving alone, so it is then a no-op.
         ;; ONE season reader for this instant, shared by tier selection, the
         ;; PriceDefinition filter and the summary-member gate. Three readers
         ;; disagreeing about one property is what URPX-8yp was.
         season-status (make-season-status seasons by-id date)
         tier-ctx      {:alt-status alt-status :season-status season-status}
         ;; Ledger-independent, so it is built once rather than per ledger, and
         ;; it is the ONLY view that sees Prices nested under an alternatives
         ;; set the caller did not select: the base path's price lookup skips
         ;; those by design. Enumerating a withheld definition from that lookup
         ;; alone left it named in the mark and absent from the evidence, and
         ;; one unrelated excluded price on the same ledger was then enough to
         ;; drop the refusal and answer short.
         psa-prices    (withheld-prices-by-pd prices (:profile-alternatives opts) zdt period-num)
         member-status (or (:member-status opts)
                           (make-member-status by-id period-num date
                                               rules-by-id season-status alt-status))

         ledger-results
         (vec
          (for [ledger ledgers
                :let [scoped-pds (->> (pds-by-ledger (:jsonld/id ledger))
                                      (filter #(and (pd-active-at-tou? % period-num)
                                                    (pd-active-in-month? % date rules-by-id)
                                                    (pd-active-in-season? % season-status))))
                      ;; A definition scoped to a urpx:ProfileAlternative applies
                      ;; only when the caller chose that alternative. Choosing a
                      ;; different one in the same dimension filters it out
                      ;; silently and correctly; choosing NOTHING leaves which
                      ;; one applies unknown, so its prices are withheld and the
                      ;; ledger says so rather than quietly resolving all of them.
                      by-alt      (group-by alt-status scoped-pds)
                      ledger-pds  (concat (:unconstrained by-alt) (:selected by-alt))
                      alt-withheld (map :jsonld/id (:unselected by-alt))
                      tier-entries
                      (->> ledger-pds
                           (mapcat (fn [pd]
                                     (for [[price _] (pd->prices (:jsonld/id pd))
                                           :let [gates (condition-gates price pd by-id)]]
                                       (cond-> (merge
                                                {:urpx.resolved/price-definition-id (:jsonld/id pd)
                                                 :urpx.resolved/price-id            (:jsonld/id price)
                                                 :urpx.resolved/price-name          (:urpx/name price)}
                                                (price-fields price by-id member-status)
                                                (tier-info pd price tier-cands tier-ctx))
                                         (:urpx/hasDynamicPricing price)
                                         (assoc :urpx.resolved/dynamic-pricing
                                                (:urpx/hasDynamicPricing price))
                                         gates
                                         (assoc :urpx.resolved/condition-gated gates)))))
                           (sort-by #(or (:urpx.resolved/tier-number %) 0))
                           vec)
                      ;; The MARK keeps its existing condition: an alternatives
                      ;; set marks a ledger only when nothing else populated it.
                      psa-marked (when (empty? tier-entries) psa-prices)
                      withheld (seq (concat
                                     alt-withheld
                                     (filter (or psa-marked {})
                                             (map :jsonld/id ledger-pds))))
                      ;; The EVIDENCE is unconditional and covers both routes a
                      ;; withheld definition's prices can reach it.
                      withheld-prices
                      (into []
                            (distinct)
                            (concat
                             (for [pd (:unselected by-alt)
                                   price (concat (map first (pd->prices (:jsonld/id pd)))
                                                 (get psa-prices (:jsonld/id pd)))]
                               (withheld-price-entry pd price))
                             (for [pd ledger-pds
                                   price (get (or psa-marked {}) (:jsonld/id pd))]
                               (withheld-price-entry pd price))))]
                :when (or (seq tier-entries) withheld)]
            (merge {:urpx.resolved/ledger-id   (:jsonld/id ledger)
                    :urpx.resolved/ledger-name (:urpx/name ledger)
                    :urpx.resolved/ledger-type (:urpx/ledgerType ledger)
                    :urpx.resolved/tiers       (vec tier-entries)}
                   (when withheld
                     {:urpx.resolved/unselected-alternatives (vec withheld)
                      :urpx.resolved/withheld-prices         withheld-prices})
                   (ledger-metric-info ledger mi-index))))]

     (merge
      {:urpx.resolved/season-name       (:urpx/seasonName season)
       :urpx.resolved/tou-period-name   (:urpx/name tou-period)
       :urpx.resolved/tou-period-number period-num
       :urpx.resolved/ledgers           ledger-results}
      ;; The version was used, but it may not describe this instant. Reported
      ;; rather than refused, so the structural answers above stay available
      ;; for a filing queried outside its own window; marginal-unit-rate
      ;; refuses on the mark, because a PRICE from a version that does not
      ;; cover the instant is the confident wrong number this library refuses.
      ;; No per-instant field, the same rule the modifier ledger mark follows.
      ;; This map sits on the RESULT rather than on a ledger, so same-ledgers?
      ;; never saw it and price-schedule was unaffected; price-segments merges
      ;; on the whole resolved value and was not. A base filing queried across a
      ;; window it does not cover returned 31 segments over a month where it
      ;; returns 1, every one holding identical prices, from 0.22.0 until now.
      ;; :version-id, :effective and :expires identify the window; the instant
      ;; the caller asked about is the caller's own. :plan-id is the key
      ;; :versions-by-plan is looked up by.
      (when-not (version-covers? version zdt (.getZone zdt))
        {:urpx.resolved/outside-version-window
         {:plan-id    (:jsonld/id rate-plan)
          :version-id (:jsonld/id version)
          :effective  (version-effective-at version (.getZone zdt))
          :expires    (version-expires-at version (.getZone zdt))}})))))

;; ---------------------------------------------------------------------------
;; Modifier resolution
;; ---------------------------------------------------------------------------

(defn- modifier-applies-to?
  "True if `modifier` declares the rate plan @id `plan-id` in its
   :urpx/applicableToRatePlan list. Returns true when `plan-id` is nil so
   callers that don't have a plan id (e.g. resolving a modifier in isolation)
   are not blocked."
  ([modifier plan-id ^ZonedDateTime zdt] (modifier-applies-to? modifier plan-id nil zdt))
  ([modifier plan-id opts ^ZonedDateTime zdt]
   (or (nil? plan-id)
       (let [version (plan-version modifier opts :urpx/hasRatePlanModifierVersion zdt)
             refs    (index/as-vec (:urpx/applicableToRatePlan version))]
         (boolean (some #(= plan-id (:jsonld/id %)) refs))))))

(defn- modifier-ledger-results
  "Build a vector of LedgerResult entries for `modifier` at `zdt`.

   TOU gating for modifier PDs (issue #4):
   1. If the modifier carries its own urpx:hasTOUSchedule, resolve the
      modifier's own TOU period at `zdt` and gate PDs against THAT period
      number — symmetric with base PD gating. Where no period of that
      schedule covers `zdt`, PDs and Prices carrying :urpx/touPeriodNumber
      contribute nothing and the rest still apply. A bracket may name a
      urpx:Season declared only in `:by-id` (`modifier-season-matcher`).
   2. Else if the caller passes `base-period-num`, gate PDs that carry
      :urpx/touPeriodNumber against the base RatePlan's resolved period
      (e.g. smud:cpp's Mid-Peak/Off-Peak discounts ride the base's TOU).
   3. Else (no own TOUSchedule, no base context), don't gate by TOU — every
      active Price for each PD surfaces under :urpx.resolved/tiers. This
      preserves the caller-picks contract for matrix-style modifiers
      (e.g. CPAU E-HRA's 4x4 reserve-level x generation-tier matrix)
      whose PDs typically don't carry touPeriodNumber anyway."
  ([modifier zdt base-period-num holiday?]
   (modifier-ledger-results modifier zdt base-period-num holiday? nil))
  ([modifier ^ZonedDateTime zdt base-period-num holiday? opts]
   (let [version       (plan-version modifier opts :urpx/hasRatePlanModifierVersion zdt)
        ;; @id index for urpx:basedOnPrice. Supplied by
        ;; resolve-prices-with-modifiers spanning the base plan AND every
        ;; modifier, which is what makes a discount Price in a modifier able to
        ;; reach a urpx:PriceSummary defined in the base plan filing. Resolving
        ;; a modifier alone falls back to the modifier's own document, and a
        ;; reference out of it is reported unvaluable rather than valued wrong.
         by-id         (or (:by-id opts) (index/build-index version))
         plan-elts     (:urpx/hasPlanElements version)
         billing       (:urpx/hasBillingModel plan-elts)
         ledgers       (index/as-vec (:urpx/hasLedger billing))
         prices        (:urpx/hasPrices version)
         alt-index     (or (:alt-index opts)
                           (-> (profile-alternative-index plan-elts)
                               (index-with-price-set-alternatives prices)))
         _             (validate-selection! (:profile-alternatives opts) alt-index
                                            (or (:alt-containers opts)
                                                (delay (declared-containers
                                                        opts [[modifier :urpx/hasRatePlanModifierVersion]]))))
         alt-status    #(pd-alternative-status % (:profile-alternatives opts) alt-index)
         own-schedules (index/as-vec (:urpx/hasTOUSchedule billing))
         own-seasons   (index/as-vec (:urpx/hasSeasons plan-elts))
         query-date    (.toLocalDate zdt)
         own-rules     (or (:rules-by-id opts)
                           (index/entities-of-type version "urpx:ApplicabilityRule"))
         ;; A Season a modifier's PriceDefinition names may be declared by the
         ;; BASE plan: PG&E's E-TOU-C baseline-tier modifier declares none of
         ;; its own. make-season-status reads by-id after the modifier's own
         ;; seasons, and by-id is composition-wide when composing.
         season-status (make-season-status own-seasons by-id query-date)
         ;; A modifier may carry its own urpx:ServiceTiers, and its tiers were
         ;; surfaced UNTIERED: this path never called tier-info, so no entry got
         ;; a :urpx.resolved/tier-number or any bound however the tiers were
         ;; shaped. marginal-unit-rate reduces a ladder by that number, so a
         ;; modifier's own block ladder was summed as additive components
         ;; instead. Selection needs nothing new here: the container and bracket
         ;; qualifiers read the same alt-status and season-status the
         ;; PriceDefinition gate above already uses.
         tier-cands    (service-tier-candidates billing)
         tier-ctx      {:alt-status alt-status :season-status season-status}
         own-season    (season-of own-seasons query-date)
         ;; `holiday?` is the inherited predicate: the base plan's when
         ;; composing, nil standing alone.
         holiday?      (or (:holiday? opts)
                           (holidays/derive-predicate modifier version)
                           holiday?)
         ;; holiday? or a predicate, never (holiday? query-date):
         ;; find-tou-period takes the PREDICATE and applies it per bracket.
         ;; Passing the boolean made holiday-status-matches? call `true` as a
         ;; function, which threw ClassCastException once a bracket actually
         ;; reached the holiday check.
         own-period    (find-tou-period own-schedules
                                        (modifier-season-matcher
                                         (:jsonld/id modifier) (:jsonld/id version)
                                         own-schedules own-seasons by-id query-date)
                                        zdt (or holiday? (constantly false)))
         own-period-num (:urpx/touPeriodNumber own-period)
         ;; The modifier's OWN season and period, on each of its ledgers. The
         ;; result-level keys are the base plan's, and a period the base plan
         ;; gated this modifier by is already reported there.
         own-labels    (cond-> {}
                         own-season (assoc :urpx.resolved/season-name
                                           (:urpx/seasonName own-season))
                         own-period (assoc :urpx.resolved/tou-period-name
                                           (:urpx/name own-period)
                                           :urpx.resolved/tou-period-number
                                           own-period-num))
         ;; ::uncovered matches no touPeriodNumber, so outside the modifier's
         ;; own brackets its period-bound Prices are gated out.
         ;; price-active-at-tou? admits every Price at a nil period.
         gate-period (cond
                       (empty? own-schedules) base-period-num
                       own-period             own-period-num
                       :else                  ::uncovered)
         gate?       (or (seq own-schedules) (some? base-period-num))
         ;; A urpx:PriceSummary member is gated in the document it lives in:
         ;; this modifier's own context for its own Prices, and the composing
         ;; base plan's (:member-status) for any other. Resolved alone with no
         ;; TOUSchedule, there is no period to match.
         own-status  (make-member-status by-id
                                         (if (and (nil? (:member-status opts))
                                                  (empty? own-schedules))
                                           ::no-period
                                           gate-period)
                                         query-date
                                         own-rules season-status alt-status)
         member-status (if-let [other-status (:member-status opts)]
                         (let [own-ids (index/build-index version)]
                           (fn [member]
                             (if (or (contains? own-ids (:jsonld/id member))
                                     (contains? own-ids (get-in member [:urpx/referencesPriceDefinition
                                                                        :jsonld/id])))
                               (own-status member)
                               (other-status member))))
                         own-status)
         pd->prices  (all-active-prices-by-pd prices zdt opts gate-period)
         pds         (index/as-vec (:urpx/hasPriceDefinition billing))
         pds-by-ledger (group-by #(get-in % [:urpx/referencesLedger :jsonld/id]) pds)
        ;; A modifier's PriceDefinitions are scoped by urpx:referencesProfileAlternative
        ;; exactly as the base plan's are (alt-status, above). This path ignored
        ;; the property, which made the whole feature a no-op where it is most
        ;; needed: a matrix of mutually-exclusive cells is a modifier shape, so
        ;; a definition scoped to a cell the caller did not choose was summed
        ;; anyway, unmarked.
         psa-prices  (withheld-prices-by-pd prices (:profile-alternatives opts) zdt gate-period)
         unselected  (unselected-alternative-ids psa-prices)
         mi-index    (mi-by-id plan-elts)]
     (vec
      (for [ledger ledgers
            ;; The base path gates a PriceDefinition on three things
            ;; (pd-active-at-tou?, pd-active-in-month?, pd-active-in-season?).
            ;; This path gated on one, so a modifier discriminating two cells by
            ;; month applicability rule or by urpx:appliesInSeason surfaced BOTH
            ;; at every instant: SMUD's NEM1 export credit over smud:fixed is
            ;; that shape, and only its touPeriodNumber-discriminated cells were
            ;; buildable. TOU stays conditional on gate?, since without a
            ;; schedule or a base period there is no period to compare; month
            ;; and season need neither and so apply unconditionally.
            :let [scoped-pds (->> (pds-by-ledger (:jsonld/id ledger))
                                  (filter #(and (or (not gate?)
                                                    (pd-active-at-tou? % gate-period))
                                                (pd-active-in-month? % query-date own-rules)
                                                (pd-active-in-season? % season-status))))
                  by-alt     (group-by alt-status scoped-pds)
                  ledger-pds (concat (:unconstrained by-alt) (:selected by-alt))
                  alt-withheld (map :jsonld/id (:unselected by-alt))
                  tier-entries
                  (->> ledger-pds
                       (mapcat (fn [pd]
                                 (for [[price _] (pd->prices (:jsonld/id pd))
                                       :let [gates (condition-gates price pd by-id)]]
                                   ;; tier-info, as on the base path. It carries
                                   ;; the recurring interval, without which a
                                   ;; recurring modifier price could never be
                                   ;; excluded from a per-unit sum, AND the tier
                                   ;; number and bounds this path used to drop.
                                   (cond-> (merge
                                            {:urpx.resolved/price-definition-id (:jsonld/id pd)
                                             :urpx.resolved/price-id            (:jsonld/id price)
                                             :urpx.resolved/price-name          (:urpx/name price)}
                                            (price-fields price by-id member-status)
                                            (tier-info pd price tier-cands tier-ctx))
                                     (:urpx/hasDynamicPricing price)
                                     (assoc :urpx.resolved/dynamic-pricing
                                            (:urpx/hasDynamicPricing price))
                                     gates
                                     (assoc :urpx.resolved/condition-gated gates)))))
                      ;; Sorted like the base path. marginal-unit-rate documents
                      ;; itself as taking the LOWEST tier and implements that as
                      ;; `first`, which only holds if the vector is ordered by
                      ;; tier number. Unsorted, the modifier path handed it
                      ;; whatever came first in the document.
                       (sort-by #(or (:urpx.resolved/tier-number %) 0))
                       vec)
                  psa-marked (when (empty? tier-entries) psa-prices)
                  withheld (seq (concat
                                 alt-withheld
                                 (filter (or psa-marked {}) (map :jsonld/id ledger-pds))))
                  unselected-ids (seq (filter unselected
                                              (map :urpx.resolved/price-id tier-entries)))
                  withheld-prices
                  (into []
                        (distinct)
                        (concat
                        ;; A price that is BOTH present in :tiers and named by
                        ;; the mark contributes its own TIER ENTRY, not a
                        ;; reconstruction of it. marginal-unit-rate asks
                        ;; "would this have contributed" of the withheld view
                        ;; and then sums the present view, so the two must be
                        ;; the same map or the guard judges one and the sum
                        ;; takes the other.
                         (filter #(unselected (:urpx.resolved/price-id %)) tier-entries)
                         (for [pd (:unselected by-alt)
                               price (concat (map first (pd->prices (:jsonld/id pd)))
                                             (get psa-prices (:jsonld/id pd)))]
                           (withheld-price-entry pd price))
                         (for [pd ledger-pds
                               price (get (or psa-marked {}) (:jsonld/id pd))]
                           (withheld-price-entry pd price))))]
            :when (or (seq tier-entries) withheld)]
        (merge {:urpx.resolved/ledger-id   (:jsonld/id ledger)
                :urpx.resolved/ledger-name (:urpx/name ledger)
                :urpx.resolved/ledger-type (:urpx/ledgerType ledger)
                :urpx.resolved/tiers       (vec tier-entries)}
              ;; A modifier whose version does not describe this instant is
              ;; marked, not dropped, exactly as the base plan is. Dropping
              ;; would be a silent loss on a discount, where the bill quietly
              ;; goes UP; refusing here would kill the tiers a caller may
              ;; legitimately want to read from a superseded filing. The mark
              ;; travels on every ledger because one composition can mix an
              ;; expired modifier with live ones.
              ;; NO :at here, deliberately, unlike the result-level mark. This
              ;; map rides on a LEDGER, and urpx.schedule merges adjacent steps
              ;; by comparing :urpx.resolved/ledgers by value. A field that
              ;; changes every step makes every step distinct, so an hourly
              ;; month over a composition with an out-of-window modifier
              ;; returned 744 intervals where it returns 1. The instant the
              ;; caller asked about is the caller's own, and :version-id,
              ;; :effective and :expires identify the window without it.
              ;; :plan-id is the MODIFIER's own @id, which is what
              ;; :versions-by-plan keys a modifier's versions by.
               (when-not (version-covers? version zdt (.getZone zdt))
                 {:urpx.resolved/outside-version-window
                  {:plan-id    (:jsonld/id modifier)
                   :version-id (:jsonld/id version)
                   :effective  (version-effective-at version (.getZone zdt))
                   :expires    (version-expires-at version (.getZone zdt))}})
               (when-let [ids (seq (concat unselected-ids withheld))]
                 {:urpx.resolved/unselected-alternatives (vec ids)
                  :urpx.resolved/withheld-prices         withheld-prices})
               own-labels
               (ledger-metric-info ledger mi-index)))))))

(defn resolve-modifier-prices
  "Resolve every per-energy unit price that `modifier` (a coerced
   urpx:RatePlanModifier) contributes at `zdt`.

   Returns the same shape as `resolve-prices` minus the result-level
   season/TOU keys: {:urpx.resolved/ledgers [...]}. Each ledger carries
   :urpx.resolved/season-name when the modifier's own urpx:hasSeasons matches
   `zdt`, and :urpx.resolved/tou-period-name and
   :urpx.resolved/tou-period-number when its own urpx:hasTOUSchedule does. A
   modifier declaring neither carries neither.

   When the modifier carries its own urpx:hasTOUSchedule, PDs with
   :urpx/touPeriodNumber are gated against the modifier's resolved period,
   and at an instant no period covers they contribute nothing.
   Without an own TOUSchedule and no base context, every applicable Price
   is surfaced — matrix-style modifiers (e.g. CPAU E-HRA — 4x4 reserve
   level x generation tier driven by external state) rely on this so the
   caller can select the active value, since the URPX condition
   vocabulary (operator + label conditionValue) is not yet specified in a
   way that's programmatically interpretable. Filter the returned :tiers
   vector on :urpx.resolved/price-id when needed.

   Holidays are read from the modifier's own urpx:HolidayCalendar, and every
   date is a non-holiday when it declares none.

   A modifier with no version snapshot refuses `:urpx.price/no-version`, as
   `resolve-prices` does.

   `opts` takes the same keys as `resolve-prices`, and four of them change an
   answer here rather than only saving work. :holiday? overrides the
   modifier's calendar. :profile-alternatives lets a cell-scoped
   urpx:ServiceBracket's bounds be determined at all, which no caller could do
   here before.
   :by-id supplies a urpx:Season the modifier names but does not declare, which
   is otherwise unevaluable, and which a urpx:TimeBracket naming it refuses
   `:unresolvable-season-ref` without. :version chooses among several snapshots.

   The caller-picks contract above is unchanged and is NOT in tension with
   :profile-alternatives: surfacing every applicable Price is about prices whose
   applicability rests on urpx:ConditionExpression predicates nothing here
   evaluates, while :profile-alternatives is the declared selection layer. Where
   a definition or bracket is scoped to an alternative, the selection decides
   it; where applicability rests on an unevaluated predicate, the caller still
   filters the result."
  ([modifier ^ZonedDateTime zdt] (resolve-modifier-prices modifier zdt nil))
  ([modifier ^ZonedDateTime zdt opts]
   ;; The modifier's own declared zone: standing alone, it is the only frame its
   ;; bounds are written in. In a composition `resolve-prices-with-modifiers`
   ;; projects once against the BASE plan instead, so one query resolves in one
   ;; zone rather than in as many as the documents happen to declare.
   (let [^ZonedDateTime zdt (.withZoneSameInstant zdt (require-zone modifier))]
     {:urpx.resolved/ledgers
      (modifier-ledger-results modifier zdt nil nil opts)})))

(defn resolve-prices-with-modifiers
  "Resolve prices for `rate-plan` at `zdt` and append ledger results for
   each modifier in `modifiers` whose :urpx/applicableToRatePlan list
   includes the base plan's @id. Modifiers whose target lists don't include
   the plan are silently skipped.

   Returns the `resolve-prices` shape with :urpx.resolved/ledgers extended
   by the modifier ledgers. The result-level season and TOU keys are the base
   plan's. A modifier ledger carries its modifier's own season and period as
   `resolve-modifier-prices` describes; a modifier gated by the base plan's
   period carries none of its own.

   A modifier's brackets read its own urpx:HolidayCalendar, and the base
   plan's when it declares none. A caller's `:holiday?` overrides both."
  ([rate-plan modifiers ^ZonedDateTime zdt] (resolve-prices-with-modifiers rate-plan modifiers zdt nil))
  ([rate-plan modifiers ^ZonedDateTime zdt opts]
   (let [;; ONE ZONE FOR THE WHOLE COMPOSITION, the base plan's. A modifier may
         ;; declare its own, but a composition answers one question about one
         ;; moment and resolving its halves in different frames would
         ;; reintroduce the disagreement this projection exists to remove.
         ^ZonedDateTime zdt (.withZoneSameInstant zdt (require-zone rate-plan))
         ;; One @id index spanning the base plan and every modifier. This is
         ;; what lets a discount Price in a modifier dereference a
         ;; urpx:PriceSummary defined in the base plan filing: the caller has
         ;; already supplied both documents here, so no cross-file lookup is
         ;; invented, the index simply covers what was handed in. Built once
         ;; rather than per modifier.
         opts            (update opts :by-id
                                 #(or % (reduce (fn [acc doc]
                                                  (merge acc (index/build-index doc)))
                                                {} (cons rate-plan modifiers))))
         plan-id         (:jsonld/id rate-plan)
         base-version    (plan-version rate-plan opts :urpx/hasRatePlanVersion zdt)
         ;; ONE index over every document in the composition, because there is
         ;; one selection map. Threaded through opts so the base path, each
         ;; modifier's tier path and the summary member gate all read the same
         ;; containers; three indexes disagreeing about one PriceDefinition is
         ;; how a subtotal silently loses a member that the tiers kept.
         ;;
         ;; Fully folded HERE, PriceSetAlternatives included, rather than left
         ;; for each path to fold its own document's in afterwards. Folding
         ;; downstream reinstated the same disagreement for the half of the
         ;; index that PriceSetAlternatives declare: the base plan's check could
         ;; not see a cell only a modifier's price sets name, and the member
         ;; gate, reading the unfolded index, saw none of them at all.
         ;;
         ;; Only modifiers that actually apply are indexed: one that names a
         ;; different plan contributes nothing to this resolution, and reading
         ;; its version to index it would surface its version errors here.
         opts            (assoc opts :alt-index
                                (or (:alt-index opts)
                                    (let [versions
                                          (cons base-version
                                                (keep #(when (modifier-applies-to? % plan-id opts zdt)
                                                         (plan-version
                                                          % opts :urpx/hasRatePlanModifierVersion zdt))
                                                      modifiers))]
                                      (reduce (fn [idx v]
                                                (index-with-price-set-alternatives
                                                 idx (:urpx/hasPrices v)))
                                              (merge-alternative-indexes
                                               (map #(profile-alternative-index
                                                      (:urpx/hasPlanElements %))
                                                    versions))
                                              versions)))
                                :alt-containers
                                (or (:alt-containers opts)
                                    (delay (declared-containers
                                            opts
                                            (cons [rate-plan :urpx/hasRatePlanVersion]
                                                  (map #(vector % :urpx/hasRatePlanModifierVersion)
                                                       modifiers))))))
         base            (resolve-prices rate-plan zdt opts)
         base-period-num (:urpx.resolved/tou-period-number base)
         ;; A discount Price in a modifier names a summary whose members live in
         ;; the BASE plan, so their season, month and TOU gates are the base
         ;; plan's. Built here and passed down, because modifier-ledger-results
         ;; can only see the modifier's own seasons and rules.
         opts            (assoc opts :member-status
                                (or (:member-status opts)
                                    (make-member-status
                                     (:by-id opts)
                                     base-period-num
                                     (.toLocalDate zdt)
                                     (or (:rules-by-id opts)
                                         (index/entities-of-type base-version "urpx:ApplicabilityRule"))
                                     (make-season-status
                                      (index/as-vec (get-in base-version [:urpx/hasPlanElements :urpx/hasSeasons]))
                                      (:by-id opts)
                                      (.toLocalDate zdt))
                                     #(pd-alternative-status
                                       % (:profile-alternatives opts) (:alt-index opts)))))
         ;; base-version, not rate-plan alone. The 1-arity re-derives the
         ;; snapshot from the document by reading the nested one, which ignores
         ;; a caller's :version and REFUSES outright on a document nesting
         ;; several — exactly the document :version exists to settle. So a
         ;; composition threw from the holiday path even after :version had
         ;; already chosen, while the same call through resolve-prices
         ;; succeeded, because that path has passed its resolved version since
         ;; v0.5.1 un-nesting. The snapshot is in hand here; re-deriving it was
         ;; the whole defect.
         holiday?        (or (:holiday? opts)
                             (holidays/derive-predicate rate-plan base-version))
         ;; opts, not the 2-arity: without it plan-version can only follow the
         ;; nested down-pointer, so a v0.5.1 modifier that states the link only
         ;; through its urpx:forRatePlanModifier back-pointer resolved to a nil
         ;; version, matched no applicableToRatePlan, and was dropped from the
         ;; composition without a word.
         extra           (mapcat #(when (modifier-applies-to? % plan-id opts zdt)
                                    (modifier-ledger-results % zdt base-period-num holiday? opts))
                                 modifiers)]
     (update base :urpx.resolved/ledgers (fnil into []) extra))))

(defn- import-energy-ledger?
  "True if `ledger` measures energy flowing INTO the premises, which is what a
   per-kWh marginal rate is about.

   urpx:flowDirection urpx:reverse marks export: compensation for energy sent
   back, not a cost of the next kWh consumed. Summing the two conflates a
   credit with a charge. Composing PG&E E-ELEC with its NBT modifier produced
   a large negative rate per kWh, because the hourly avoided-cost values on its
   export ledgers were added to the import rate.

   urpx:net and urpx:bidirectional are treated as contributing, because
   excluding them would be speculation. A netted quantity in an import marginal
   is genuinely ambiguous, and this is the line to revisit when a filing uses
   one."
  [ledger]
  (not= "urpx:reverse" (get-in ledger [:urpx.resolved/flow-direction :jsonld/id])))

(defn measurement-kinds
  "The set of urpx:measurementKind @ids present across `resolved`'s ledgers.

   What a caller usually wants to know, having found that a plan prices nothing
   per kWh, is what it DOES price in, so this answers with the kinds rather than
   a bare yes or no and lets a sweeping consumer route instead of merely skip.

   May be empty. CPAU E-14 is the case: a street lighting schedule whose charges
   are all per-lamp-per-month and per-fixture-per-month, filed as recurring
   charges with their own CalculationMethod, which price resolution excludes.
   Nothing resolves, so nothing is reported, and an empty set says exactly that."
  [resolved]
  (into #{} (keep #(get-in % [:urpx.resolved/measurement-kind :jsonld/id]))
        (:urpx.resolved/ledgers resolved)))

(defn has-energy-ledger?
  "True if `resolved` carries any ledger measured in urpx:energy, which is the
   precondition for `marginal-unit-rate` returning a number.

   Ask this before calling it when sweeping a corpus. Plenty of real schedules
   price nothing per kWh (street lighting, fixed service charges), and for those
   a marginal per-kWh rate is not a small number, it is a question that does not
   apply."
  [resolved]
  (boolean (some #(and (= "urpx:energy" (get-in % [:urpx.resolved/measurement-kind :jsonld/id]))
                       (import-energy-ledger? %))
                 (:urpx.resolved/ledgers resolved))))

(defn marginal-unit-rate
  "The marginal $/kWh at the start of a billing period: summed across all
   **energy** ledgers, and within each ledger the sum of its ADDITIVE rate
   components plus the LOWEST rung of its block tier ladder, if it has one.

   A ledger is used for two different things in practice. A block tier ladder
   is mutually exclusive, so only the marginal rung counts; an additive rate
   stack (a delivery ledger carrying transmission, distribution, public purpose
   and the rest) is cumulative, so all of it counts. urpx:serviceTierNumber is
   what separates them: a numbered tier belongs to a ladder, a bare one is a
   component. URPX has no vocabulary saying which a ledger is, so this is the
   signal available rather than the signal designed, and a ledger mixing both
   contributes its components plus the lowest rung of that ladder.

   A ledger is treated as energy iff its referenced MetricInput's
   urpx:measurementKind is urpx:energy. Per-meter, per-customer, per-day, or
   per-month service charges (urpx:profileMetricInput, or non-energy
   measurementKinds) are excluded — they have a different shape and don't
   belong in a $/kWh sum.

   Useful for price signaling: when you don't know a customer's usage but want
   a single $/kWh number, this is the marginal rate at zero baseline usage.

   REFUSES, rather than returning a number, in these cases, each carrying a
   named `:reason` in its ex-data so a sweeping consumer can classify:

     :no-energy-ledger            this plan prices nothing per kWh, so the
                                  question does not apply to it. 0M would be
                                  indistinguishable from a plan whose energy
                                  ledgers genuinely sum to zero
     :ambiguous-marginal-tier     an energy ledger carries two or more entries
                                  at the lowest tier number of its ladder, so
                                  which one is the marginal rung is undefined
     :unvaluable-marginal-tier    a marginal tier could not be valued. A nil
                                  unit price reads to a consumer as free, and
                                  for a discount that is the worst available
                                  answer
     :unselected-alternatives     an energy ledger carries mutually-exclusive
                                  price alternatives with no caller selection,
                                  AND either at least one of the withheld prices
                                  would have entered this sum, or nothing is
                                  recorded about what was withheld. Withholding
                                  a price the sum would not have taken costs the
                                  caller nothing and does not refuse; not
                                  KNOWING what was withheld is not evidence that
                                  it did not matter, so that refuses
     :condition-gated-tier        a price in the sum is gated by a
                                  urpx:ConditionExpression this library does not
                                  evaluate, so including it would report an
                                  assumption as a number. Pass
                                  :assume-conditions-hold? true for the
                                  every-condition-true upper bound
     :all-energy-tiers-excluded   every energy tier was event-driven, recurring,
                                  or both. Free is not the answer either
     :outside-version-window      the version used does not describe this
                                  instant. :plan-id in ex-data, and on each
                                  :stale entry's mark, is the @id
                                  :versions-by-plan is keyed by

   The alternatives case is why composing a PG&E plan with its vintage-PCIA
   modifier needs `:profile-alternatives`. Taking the first of eighteen mutually
   exclusive prices added an arbitrary 0.02973 to the rate; the eighteen span
   0.06391/kWh, so there is no default worth inventing.

   EVENT-DRIVEN PRICES ARE EXCLUDED BY DEFAULT. A Price carrying
   urpx:hasDynamicPricing applies only while its event is called: a critical
   peak charge is real but exceptional, capped around 50 hours a summer against
   roughly 2,200 summer hours. Summing it unconditionally answered `what would
   this cost during an event', which read as the ordinary rate. The default is
   now the non-event baseline, which is what a price signal means by `the next
   kWh'. Unlike the alternatives above there IS a defensible default here,
   because no-event-today is the ordinary state rather than an arbitrary pick.

   Pass `{:include-dynamic-pricing? true}` for the during-event upper bound.
   Either way `:urpx.resolved/dynamic-pricing` on the tier says which prices
   are event-driven, so a caller can compute both."
  ([resolved] (marginal-unit-rate resolved nil))
  ([resolved opts]
   (let [include-dyn? (:include-dynamic-pricing? opts false)
         assume-conditions? (:assume-conditions-hold? opts false)
         exclude-tier? (fn [t]
                         (or
                         ;; A recurring charge is priced per interval, not per
                         ;; unit consumed, so it cannot go into a $/kWh sum
                         ;; whatever its ledger is measured in.
                          (:urpx.resolved/interval-duration t)
                          (and (not include-dyn?) (:urpx.resolved/dynamic-pricing t))))
         energy     (->> (:urpx.resolved/ledgers resolved)
                         (filter #(and (= "urpx:energy"
                                          (get-in % [:urpx.resolved/measurement-kind :jsonld/id]))
                                       (import-energy-ledger? %)
                                      ;; A ledger whose prices were WITHHELD by an
                                      ;; unselected alternatives set has no tiers and
                                      ;; still counts, or the refusal below would be
                                      ;; deleted by the very thing it guards against.
                                       (or (seq (:urpx.resolved/tiers %))
                                           (:urpx.resolved/unselected-alternatives %)))))
        ;; A ledger of mutually-exclusive alternatives has no marginal tier
        ;; until the caller says which alternative applies. Taking the first
        ;; added an arbitrary one of PG&E's 18 vintage-PCIA prices to every
        ;; marginal rate: 0.41784 became 0.44757, and the 18 span 0.06391/kWh.
        ;; Pass :profile-alternatives to choose; there is no default worth
        ;; inventing here.
        ;; Withholding earns a refusal only when it makes the sum short. If
        ;; every withheld price would have been excluded anyway, the number is
        ;; the same one the caller would have got, and refusing would trade it
        ;; for a question nobody can answer: CPAU's E-HRA cells are the
        ;; utility's own reserve level and generation band, not an attribute of
        ;; the customer. An empty record is treated as contributory rather than
        ;; harmless, because not knowing what was withheld is not evidence that
        ;; it did not matter.
         unselected (filter (fn [l]
                              (when (:urpx.resolved/unselected-alternatives l)
                                (let [w (:urpx.resolved/withheld-prices l)]
                                  (or (empty? w)
                                      (boolean (some (complement exclude-tier?) w))))))
                            energy)
        ;; Excluded BEFORE taking the first tier, not after. A ledger mixing an
        ;; event charge with an ordinary price should contribute the ordinary
        ;; one, where filtering afterwards would drop the whole ledger.
        ;; A ledger contributes the sum of its ADDITIVE components plus the
        ;; marginal rung of its LADDER, if it has one.
        ;;
        ;; Taking only the first tier is right for a block-tier ladder, which is
        ;; what this function was written for, and wrong for the additive rate
        ;; stacks that ledgers also carry. SDG&E EV-TOU's delivery ledger
        ;; carries ten components; keeping the first dropped nine of them and
        ;; reported 0.57086 where the components sum to 0.86302, a third low.
        ;; It is not a one-sided error either: SCE TOU-D-4-9PM OVERSTATED,
        ;; because the dropped set included a negative Baseline Credit.
        ;;
        ;; urpx:serviceTierNumber separates the two. URPX has no vocabulary
        ;; distinguishing additive from exclusive tiers, so the tier number is
        ;; the signal available rather than the signal designed.
        ;;
        ;; MIXED is summed-plus-lowest rather than refused. The fixture case,
        ;; sdge:dr-2026-04-01-ledger-energy-delivery, is ten UDC components plus
        ;; a single tier-numbered Baseline Credit, where this and plain summing
        ;; agree exactly. Refusing would decline a question that has an answer,
        ;; and plain summing would re-create the original bug the moment a
        ;; genuine ladder appeared beside additive components.
         marginal   (mapcat #(-> (remove exclude-tier? (:urpx.resolved/tiers %))
                                 (additive-plus-lowest-rung :urpx.resolved/tier-number))
                            energy)
         ambiguous  (keep (fn [l]
                            (when-let [ts (-> (remove exclude-tier? (:urpx.resolved/tiers l))
                                              (lowest-rung-ties :urpx.resolved/tier-number))]
                              {:urpx.resolved/ledger-id (:urpx.resolved/ledger-id l)
                               :tier-number (:urpx.resolved/tier-number (first ts))
                               :price-ids (mapv :urpx.resolved/price-id ts)}))
                          energy)
         unvaluable (filter :urpx.resolved/unvaluable marginal)
        ;; Only tiers that would actually ENTER the sum: `marginal` is already
        ;; import-energy-only and already past exclude-tier?, so a gated export
        ;; price or a gated event charge is not in scope here.
         gated      (filter :urpx.resolved/condition-gated marginal)
        ;; Same question on the MODIFIER path, where the mark rides on the
        ;; ledger rather than on the result: a composition can mix an expired
        ;; modifier with live ones. Scoped to ledgers that actually contribute
        ;; to this sum, so an expired modifier whose ledgers are all non-energy,
        ;; or all excluded, costs the caller nothing and does not refuse.
         stale      (filter #(and (:urpx.resolved/outside-version-window %)
                                  (seq (-> (remove exclude-tier? (:urpx.resolved/tiers %))
                                           (additive-plus-lowest-rung
                                            :urpx.resolved/tier-number))))
                            energy)]
    ;; A price from a version that does not describe this instant is the
    ;; confident wrong number this whole function exists to refuse. The
    ;; structural answers alongside it (season, TOU period) stay available on
    ;; the resolved map, which is why the mark is there rather than a throw
    ;; from resolve-prices.
     (when-let [w (or (:urpx.resolved/outside-version-window resolved)
                      (:urpx.resolved/outside-version-window (first stale)))]
       (let [from-modifier? (and (seq stale)
                                 (not (:urpx.resolved/outside-version-window resolved)))]
         (throw (ex-info (str "Cannot compute a marginal rate: "
                              (if from-modifier?
                             ;; Every stale ledger, grouped by the version that
                             ;; made it stale. A caller who clears one and
                             ;; re-runs should not meet a second refusal naming
                             ;; a modifier the first never mentioned, and with
                             ;; several versions involved there is no single
                             ;; "the version used" to name.
                                (str (count stale) " modifier ledger(s) come from "
                                     (count (distinct (map #(:version-id
                                                             (:urpx.resolved/outside-version-window %))
                                                           stale)))
                                     " version(s) that do not cover the instant resolved: "
                                     (str/join "; "
                                               (for [[v ls] (group-by
                                                             #(:version-id
                                                               (:urpx.resolved/outside-version-window %))
                                                             stale)
                                                     :let [w' (:urpx.resolved/outside-version-window
                                                               (first ls))]]
                                                 (str (pr-str v)
                                                      (when-let [e (:effective w')]
                                                        (str ", which takes effect " e))
                                                      (when-let [x (:expires w')]
                                                        (str " and expires " x))
                                                      ", on "
                                                      (str/join ", "
                                                                (map #(pr-str (:urpx.resolved/ledger-id %))
                                                                     ls))))))
                                (str "the version used, " (pr-str (:version-id w))
                                     ", does not cover the instant resolved"
                                     (when-let [e (:effective w)] (str "; it takes effect " e))
                                     (when-let [x (:expires w)] (str "; it expires " x))))
                              ". Resolve at an instant the filing describes, or load "
                              "the version that does and pass it in :versions-by-plan "
                              "under "
                              (str/join ", "
                                        (map pr-str
                                             (if from-modifier?
                                               (distinct (map #(:plan-id
                                                                (:urpx.resolved/outside-version-window %))
                                                              stale))
                                               [(:plan-id w)])))
                              ".")
                      ;; ex-data carries everything the message says, so a
                      ;; consumer classifying programmatically is not worse off
                      ;; than one reading prose. No :at on either mark: both
                      ;; carry only fields that identify the WINDOW, because a
                      ;; per-instant field makes every step of a horizon walk
                      ;; distinct and nothing merges.
                         (cond-> {:reason :outside-version-window
                                  :plan-id (:plan-id w)
                                  :version-id (:version-id w)
                                  :effective (:effective w)}
                           (:expires w) (assoc :expires (:expires w))
                           from-modifier?
                           (assoc :ledger-id (:urpx.resolved/ledger-id (first stale))
                                  :stale (mapv #(select-keys
                                                 % [:urpx.resolved/ledger-id
                                                    :urpx.resolved/outside-version-window])
                                               stale)))))))
    ;; No energy ledger at all is not an answer of zero, it is a malformed
    ;; question. A street lighting schedule prices per lamp per month; its
    ;; marginal unit is not a kWh. Returning 0M conflated that with a plan whose
    ;; energy ledgers genuinely sum to zero, and read to a consumer as free.
     (when (empty? energy)
       (throw (ex-info (str "Cannot compute a marginal rate: this plan has no energy "
                            "ledger, so it prices nothing per kWh and a marginal "
                            "per-kWh rate does not apply to it. "
                            (cond
                              (seq (measurement-kinds resolved))
                              (str "It prices in: "
                                   (pr-str (vec (sort (measurement-kinds resolved)))) ".")
                              (seq (:urpx.resolved/ledgers resolved))
                              (str (count (:urpx.resolved/ledgers resolved))
                                   " ledger(s) resolved, none of them measured in a "
                                   "urpx:measurementKind this library can sum.")
                              :else "Nothing resolved at this instant at all.")
                            " Call has-energy-ledger? first when sweeping.")
                       {:reason :no-energy-ledger
                        :measurement-kinds (measurement-kinds resolved)
                        :ledger-count (count (:urpx.resolved/ledgers resolved))})))
     (when (seq unselected)
       (throw (ex-info (str "Cannot compute a marginal rate: "
                            (count unselected) " energy ledger(s) carry mutually-exclusive "
                            "price alternatives with no selection. Pass :profile-alternatives "
                            "naming the applicable urpx:ProfileAlternative for each.")
                       {:reason :unselected-alternatives
                        :ledgers (mapv #(select-keys % [:urpx.resolved/ledger-id
                                                        :urpx.resolved/unselected-alternatives])
                                       unselected)})))
     (when (seq ambiguous)
       (throw (ex-info (str "Cannot compute a marginal rate: " (count ambiguous)
                            " energy ledger(s) carry two or more prices at the lowest "
                            "tier number of a block-tier ladder, so the marginal rung "
                            "is undefined. Inspect :ledgers in ex-data.")
                       {:reason :ambiguous-marginal-tier
                        :ledgers (vec ambiguous)})))
    ;; Refuse rather than sum around a price this library could not value.
    ;; Skipping would silently under-report the rate, which for a discount
    ;; means reporting the undiscounted price as though it were the real one.
     (when (seq unvaluable)
       (throw (ex-info (str "Cannot compute a marginal rate: "
                            (count unvaluable) " marginal energy price(s) could not be valued. "
                            "Inspect :urpx.resolved/unvaluable on the offending tiers.")
                       {:reason :unvaluable-marginal-tier
                        :unvaluable (mapv :urpx.resolved/unvaluable unvaluable)})))
    ;; A price whose condition this library cannot evaluate fired on the
    ;; assumption that the condition holds, and summing it reports that
    ;; assumption as a number. Two shapes reach here and both are wrong:
    ;;
    ;; SCE's CCA-CRS files twenty PCIA vintages as twenty condition-gated
    ;; prices, "Applies when customer's CCA Vintage Year = 2001" and so on.
    ;; Exactly one applies to any customer; summing all twenty took SCE
    ;; TOU-D-PRIME from 0.60067 to 0.95664 where the true range is 0.59423 to
    ;; 0.62691. That is the identical defect this function already refuses when
    ;; a filing states it as urpx:PriceSetAlternatives, and PG&E states the
    ;; SAME concept that way. Refusing both means the answer no longer depends
    ;; on which layer a filer happened to use.
    ;;
    ;; SCE's CPP credits are the other shape, a genuine predicate nobody can
    ;; evaluate: "on non-CPP-Event days" and "when the customer is
    ;; grandfathered". Applying them unconditionally made the marginal rate
    ;; NEGATIVE, -0.13897 at summer peak, which says a customer is paid to
    ;; consume.
    ;;
    ;; :assume-conditions-hold? true restores the old behavior for a caller who
    ;; wants the every-condition-true upper bound, the way
    ;; :include-dynamic-pricing? does for event prices.
     (when (and (seq gated) (not assume-conditions?))
       (throw (ex-info (str "Cannot compute a marginal rate: " (count gated)
                            " price(s) in the sum are gated by a urpx:ConditionExpression "
                            "this library does not evaluate, so including them would report "
                            "an assumption as a number. Inspect "
                            ":urpx.resolved/condition-gated on the tiers. Pass "
                            ":assume-conditions-hold? true for the every-condition-true "
                            "upper bound. Where the gated set is mutually exclusive, as a "
                            "vintage matrix is, the filing wants urpx:ProfileAlternatives "
                            "rather than predicates, and the choice then becomes yours to make.")
                       {:reason :condition-gated-tier
                        :gated (mapv #(select-keys % [:urpx.resolved/price-id
                                                      :urpx.resolved/condition-gated])
                                     gated)})))
    ;; Every energy tier excluded is not an answer of zero either. A modifier
    ;; resolved on its own whose only energy ledger IS the event charge would
    ;; otherwise report free.
     (when (empty? marginal)
       (throw (ex-info (str "Cannot compute a marginal rate: every energy tier was "
                            "excluded from the sum. "
                            (if include-dyn?
                              "All of them are recurring charges, priced per interval rather than per unit."
                              (str "They are event-driven prices (urpx:hasDynamicPricing), "
                                   "recurring charges, or both. Pass :include-dynamic-pricing? true "
                                   "for the during-event upper bound."))
                            " Inspect :urpx.resolved/dynamic-pricing and "
                            ":urpx.resolved/interval-duration on the tiers"
                            (when (some :urpx.resolved/unselected-alternatives energy)
                              (str ", and :urpx.resolved/withheld-prices on the "
                                   (count (filter :urpx.resolved/unselected-alternatives energy))
                                   " ledger(s) carrying prices that were WITHHELD rather than "
                                   "excluded: those are named there and nowhere in :tiers, and a "
                                   ":profile-alternatives selection would resolve them"))
                            ".")
                       {:reason :all-energy-tiers-excluded
                        :include-dynamic-pricing? include-dyn?
                        :energy-ledgers (mapv :urpx.resolved/ledger-id energy)})))
     (reduce (fn [^java.math.BigDecimal acc tier]
               (.add acc ^java.math.BigDecimal (:urpx.resolved/unit-price tier)))
             0M
             marginal))))

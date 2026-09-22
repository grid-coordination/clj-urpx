(ns urpx.price
  "Resolve applicable per-energy prices for a URPX rate plan at a given instant.

  Given a coerced rate plan (`urpx.coerce/coerce-rate-plan`) and a
  ZonedDateTime, `resolve-prices` returns the season, the matched TOU period
  (if any), and one entry per active per-energy ledger (commodity, distribution,
  public benefits, etc.). Each ledger entry carries a `:tiers` vector — for
  block-tiered ledgers (CPAU E-1) every tier's bound and unit-price is
  surfaced so the caller can pick the active one based on cumulative usage;
  for non-tiered ledgers the vector has a single entry.

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

  Plans without a HolidayCalendar and called without `:holiday?` retain the
  pre-existing 'every date is a non-holiday' fallback — so brackets that
  opt out of non-holidays (urpx:includeNonHolidays = false) will never match."
  (:require [urpx.holidays :as holidays]
            [urpx.index :as index])
  (:import [java.time DayOfWeek LocalDate LocalDateTime LocalTime ZonedDateTime]))

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
  "Half-open containment: [startTime, endTime). If startTime > endTime the
   bracket is treated as wrapping midnight."
  [^LocalTime t bracket]
  (let [start ^LocalTime (:urpx/startTime bracket)
        end   ^LocalTime (:urpx/endTime bracket)]
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
    (some (fn [s] (when (some #{m} (:urpx/month s)) s)) seasons)))

(defn- find-tou-period
  "Find the TOUPeriod whose TimeBracket matches the given season, date,
   day-of-week, and local-time across every TOUSchedule in `tou-schedules`.
   Walks every tier's periods (the tier hierarchy is for classification —
   what matters for resolution is the bracket match).

   BillingModel.hasTOUSchedule is a vector: one schedule per
   MetricInput when the rate plan TOU-zones multiple metrics. The first
   bracket-matching period across all schedules wins."
  [tou-schedules season-id ^LocalDate date ^LocalTime t ^DayOfWeek dow holiday?]
  (when (seq tou-schedules)
    (some
     (fn [period]
       (when (some
              (fn [bracket]
                (and (= season-id (:jsonld/id (index/single (:urpx/appliesInSeason bracket)
                                                            "appliesInSeason refs on one TimeBracket")))
                     (bracket-matches-day? bracket date dow holiday?)
                     (time-in-bracket? t bracket)))
              (:urpx/hasTimeBracket period))
         period))
     (mapcat (fn [schedule]
               (mapcat :urpx/hasTOUPeriod (:urpx/hasTOUTier schedule)))
             tou-schedules))))

;; ---------------------------------------------------------------------------
;; Active-price lookup
;; ---------------------------------------------------------------------------

(defn- not-after?
  [^LocalDate a ^LocalDate b]
  (not (.isAfter a b)))

(defn- price-set-alternative-for
  "Within a PriceSetAlternatives, find the alternative whose
   urpx:referencesProfileAlternative matches the caller-selected
   :jsonld/id. Returns the matching PriceSetAlternative or nil."
  [psas chosen-pa-id]
  (when chosen-pa-id
    (some (fn [psa]
            (when (= chosen-pa-id (get-in psa [:urpx/referencesProfileAlternative :jsonld/id]))
              psa))
          (:urpx/hasPriceSetAlternative psas))))

(def ^:private undated
  "Stand-in start date for a Price that states none.

   LocalDate/MIN rather than nil so an undated Price sorts BEHIND any dated
   one in the latest-wins dedupe: an explicitly dated Price supersedes an
   undated sibling on the same PriceDefinition, which is the only ordering
   that reads as intentional."
  LocalDate/MIN)

(defn- price-start
  "The date from which `price` is in effect, falling back to `container-date`
   (a PriceSet's) and then to `undated`.

   urpx:startDateTime is preferred over urpx:startDate when both are stated,
   mirroring how version-effective-at prefers effectiveDateTime. The schema
   accepts startDateTime, urpx.emit writes it, and nothing here read it: a
   filing stating its date that way priced as though it had no date at all.

   ABSENT MEANS NO START CONSTRAINT, not `drop this price`. The four scoping
   sites used when-let on urpx:startDate and silently discarded anything
   without one, while the PriceSet gate immediately above them treats a nil
   date as permissive. Two readings of the same absence, three lines apart,
   and the discarding one deleted content: a shipped PG&E baseline-tier
   modifier carries four Prices and no dates at all, resolved to zero ledgers,
   and composing it with its base plan changed nothing and said nothing.
   sh:minCount 0 on both PriceShape and PriceSetShape is the vocabulary
   agreeing that absence is legal rather than defective."
  ^LocalDate [price ^LocalDate container-date]
  (or (some-> (:urpx/startDateTime price) (.toLocalDate))
      (:urpx/startDate price)
      container-date
      undated))

(defn- prices-from-alternatives
  "Walk `Prices.hasPriceSetAlternatives`. For each PriceSetAlternatives,
   look up the caller's chosen ProfileAlternative for the referenced
   ProfileAlternatives in `selection` (a {:jsonld/id of ProfileAlternatives
   → :jsonld/id of chosen ProfileAlternative} map). The matching
   PriceSetAlternative's hasPrice list contributes to the active-prices
   stream, gated by start-date as usual.

   When the caller hasn't supplied a selection for a given
   PriceSetAlternatives, that set is silently skipped — prices for the
   unresolved alternative aren't surfaced. Programmatic ConditionExpression
   evaluation against a customer profile is a future enhancement; today
   the caller drives selection explicitly via `:profile-alternatives` opts."
  [prices ^LocalDate query-date selection]
  (mapcat (fn [psas]
            (let [pa-id   (get-in psas [:urpx/referencesProfileAlternatives :jsonld/id])
                  chosen  (get selection pa-id)
                  picked  (price-set-alternative-for psas chosen)]
              (when picked
                (keep (fn [p]
                        (let [sd (price-start p nil)]
                          (when (not-after? sd query-date) [p sd])))
                      (:urpx/hasPrice picked)))))
          (:urpx/hasPriceSetAlternatives prices)))

(defn- scoped-prices
  "Return a seq of [price effective-start-date] pairs for every Price under
   `prices` whose effective start-date is <= `query-date`. Walks the flat
   :urpx/hasPrice list, PriceSet-nested prices, and PriceSetAlternatives-
   nested prices when the caller supplies an explicit
   `:profile-alternatives` selection in opts.

   Per URPX SHACL, PriceSet.startDate is optional (sh:minCount 0). When the
   PriceSet carries one it acts as the date floor for any contained Price that
   does not declare its own; when neither states one the Price carries no start
   constraint and is in effect, which is what `price-start` settles."
  [prices ^LocalDate query-date opts]
  (let [selection (:profile-alternatives opts {})]
    (concat
     (keep (fn [p]
             (let [sd (price-start p nil)]
               (when (not-after? sd query-date) [p sd])))
           (:urpx/hasPrice prices))
     (mapcat (fn [ps]
               (let [ps-sd (price-start ps nil)]
                 (when (not-after? ps-sd query-date)
                   (keep (fn [p]
                           (let [sd (price-start p (when-not (= undated ps-sd) ps-sd))]
                             (when (not-after? sd query-date) [p sd])))
                         (:urpx/hasPrice ps)))))
             (:urpx/hasPriceSet prices))
     (prices-from-alternatives prices query-date selection))))

(defn- price-active-at-tou?
  "True if `price` may apply at the resolved TOU period.

   urpx:touPeriodNumber is rdfs:domain the union of urpx:Price,
   urpx:PriceDefinition, urpx:PriceSet and urpx:TOUPeriod, so a Price may carry
   the TOU link itself. 43 Prices across the corpus do. Only the
   PriceDefinition was ever read, so those Prices were never gated, with two
   different wrong answers depending on the path:

     in a urpx:PriceSummary   every TOU variant of one charge summed into the
                              subtotal. SDG&E EV-TOU's CARE discount came out
                              -0.9255435 against a correct -0.2999885, 3.09x.
     in the tier path         the variants collided in the latest-wins dedupe,
                              five of six were discarded and the survivor was
                              reported at EVERY hour. The transmission charge
                              read at its on-peak value around the clock.

   A Price carrying no period is left to its PriceDefinition's gate, which is
   how every other filing in the corpus states it. A nil resolved period means
   there is nothing to match against, so nothing is gated out."
  [price resolved-period-num]
  (let [n (:urpx/touPeriodNumber price)]
    (or (nil? n) (nil? resolved-period-num) (= n resolved-period-num))))

(defn- active-prices-by-pd
  "Build a map from PriceDefinition id to a vector of currently-active
   [price start-date] pairs. Within each PD, prices are deduped by the
   composite key `(price-definition-id, serviceTierNumber)` so that two
   distinct concrete patterns coexist without conflict:

     - Tier-stratified pricing (PG&E E-1 CIA): one PriceDefinition with
       multiple Prices, each carrying its own urpx:serviceTierNumber. Every
       tier's Price surfaces under one PD because the keys differ.
     - Temporal versioning: a single (PD, serviceTierNumber) key with
       multiple Prices over time — the one with the latest effective
       start-date wins.

   For non-tiered ledgers (one PD, one Price, no serviceTierNumber) the
   returned vector has a single entry."
  ([prices ^LocalDate query-date opts] (active-prices-by-pd prices query-date opts nil))
  ([prices ^LocalDate query-date opts resolved-period-num]
  (let [latest-per-key
        (reduce (fn [acc [price sd]]
                  ;; Keyed on the TOU period too. Without it, six TOU variants
                  ;; of one charge sharing a PriceDefinition collide and five
                  ;; are discarded; the Price-level gate above removes the
                  ;; non-matching ones first, and this keeps them separable if
                  ;; there is no resolved period to gate against.
                  (let [k [(get-in price [:urpx/referencesPriceDefinition :jsonld/id])
                           (:urpx/serviceTierNumber price)
                           (:urpx/touPeriodNumber price)]
                        [_ existing-sd] (acc k)]
                    (if (or (nil? existing-sd) (.isAfter sd existing-sd))
                      (assoc acc k [price sd])
                      acc)))
                {} (filter #(price-active-at-tou? (first %) resolved-period-num)
                           (scoped-prices prices query-date opts)))]
    (reduce-kv (fn [acc [pd-id _ _] pair]
                 (update acc pd-id (fnil conj []) pair))
               {} latest-per-key))))

(defn- all-prices-from-alternatives
  "Walk Prices.hasPriceSetAlternatives and return [price start-date] pairs
   for EVERY active Price across EVERY PriceSetAlternative — the matrix-
   modifier surface-all contract (e.g. PG&E Vintage-PCIA's per-departure-
   vintage-year prices). Distinct from `prices-from-alternatives`, which
   only surfaces the caller-selected alternative for the base RatePlan
   resolution path."
  [prices ^LocalDate query-date]
  (mapcat (fn [psas]
            (mapcat (fn [psa]
                      (keep (fn [p]
                              (let [sd (price-start p nil)]
                                (when (not-after? sd query-date) [p sd])))
                            (:urpx/hasPrice psa)))
                    (:urpx/hasPriceSetAlternative psas)))
          (:urpx/hasPriceSetAlternatives prices)))

(defn- all-active-prices-by-pd
  "Build a map from PriceDefinition id to a vector of [price start-date]
   pairs for every active Price referencing that PD. Used by modifier
   resolution where matrix-style pricing surfaces multiple concurrent
   prices that the caller selects from. Walks both the flat hasPrice list
   (CPAU E-HRA shape) and hasPriceSetAlternatives nesting (PG&E Vintage-PCIA
   shape — per-departure-vintage-year prices in PriceSetAlternative)."
  ([prices ^LocalDate query-date] (all-active-prices-by-pd prices query-date nil nil))
  ([prices ^LocalDate query-date opts] (all-active-prices-by-pd prices query-date opts nil))
  ([prices ^LocalDate query-date opts resolved-period-num]
   (let [selection (:profile-alternatives opts)]
     (reduce (fn [acc [price :as pair]]
               (let [pd-id (get-in price [:urpx/referencesPriceDefinition :jsonld/id])]
                 (update acc pd-id (fnil conj []) pair)))
             {}
             (filter
              #(price-active-at-tou? (first %) resolved-period-num)
              (concat (scoped-prices prices query-date nil)
                     ;; With a selection, surface only the chosen alternative,
                     ;; exactly as the base plan path does. Without one, keep the
                     ;; surface-all contract so a caller reading tiers directly
                     ;; still sees every vintage; `unselected-alternative-ids`
                     ;; then marks the ledger so an AGGREGATE cannot silently
                     ;; pick one of them.
                      (if (seq selection)
                        (prices-from-alternatives prices query-date selection)
                        (all-prices-from-alternatives prices query-date))))))))

;; ---------------------------------------------------------------------------
;; Service-tier lookup (block tiers, e.g. CPAU E-1 baseline / over-baseline)
;; ---------------------------------------------------------------------------

(defn- unselected-alternative-ids
  "The @ids of the Prices surfaced from a urpx:PriceSetAlternatives for which
   the caller supplied no selection.

   These are ALTERNATIVES, not tiers: exactly one applies to any customer, and
   which one is the caller's to say. PG&E's vintage-PCIA ledger carries 18 of
   them spanning 0.06391/kWh. Surfacing them all is the documented contract for
   reading tiers; silently adding one of them to an aggregate is not, so the
   ledger is marked and `marginal-unit-rate` refuses rather than pick."
  [prices selection]
  (into #{}
        (comp (remove #(get selection (get-in % [:urpx/referencesProfileAlternatives :jsonld/id])))
              (mapcat :urpx/hasPriceSetAlternative)
              (mapcat :urpx/hasPrice)
              (keep :jsonld/id))
        (:urpx/hasPriceSetAlternatives prices)))

(defn- withheld-prices-by-pd
  "The Prices that exist only inside a urpx:PriceSetAlternatives the caller
   supplied no selection for, grouped by the @id of the PriceDefinition each
   references.

   A ledger fed only by such definitions resolves to no tiers and is then
   elided, taking with it both the prices AND any signal that they were
   withheld. On the base plan path that is the whole failure: an unselected set
   contributes nothing, so the prices, the ledger and the evidence disappear
   together, and six shipped base versions carry one. On the modifier path it
   defeats an existing guard, because the unselected mark is computed from
   surviving tier entries and there are none: dating PG&E's 18 vintage-PCIA
   prices into the future took marginal-unit-rate from refusing to answering
   0.46886. Removing content should not remove a refusal.

   The Prices themselves are kept, not just their definition ids, because
   whether a refusal is owed depends on what was withheld: see
   `withheld-price-entry`."
  [prices selection]
  (->> (:urpx/hasPriceSetAlternatives prices)
       (remove #(get selection (get-in % [:urpx/referencesProfileAlternatives :jsonld/id])))
       (mapcat :urpx/hasPriceSetAlternative)
       (mapcat :urpx/hasPrice)
       (filter #(get-in % [:urpx/referencesPriceDefinition :jsonld/id]))
       (group-by #(get-in % [:urpx/referencesPriceDefinition :jsonld/id]))))

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

(defn- service-tiers-by-number
  "Index ServiceTier entities (across every ServiceTiers container at
   BillingModel.hasServiceTiers, walking each container's :urpx/hasServiceTier
   list) by :urpx/serviceTierNumber. Empty map when the plan is not
   block-tiered. hasServiceTiers is a vector: single
   ServiceTiers fixtures (CPAU E-1, PG&E E-1) coexist with
   matrix-of-ServiceTiers patterns (PG&E E-TOU-C baseline-tier modifier:
   one ServiceTiers per (territory, code) cell)."
  [billing-model]
  (into {} (map (juxt :urpx/serviceTierNumber identity))
        (mapcat :urpx/hasServiceTier (:urpx/hasServiceTiers billing-model))))

(defn- tier-info
  "Pull tier metadata for a (PD, Price) pair into the resolved-entry shape.
   The serviceTierNumber may live on the Price (PG&E-style: many tier-Prices
   share one PD) or on the PD (CPAU-style: one PD per tier). The Price's
   number wins when both are present. Returns an empty map when the entry
   isn't block-tiered.

   Per upstream urpx commit 423e83f (Decision #111), bounds live on
   urpx:ServiceBracket(s) attached via ServiceTier.hasServiceBracket. We
   read bounds from the first attached bracket — Path A (mechanical wrap)
   gives every ServiceTier exactly one ServiceBracket. Per-season /
   per-ProfileAlternative bracket selection across multiple brackets per
   tier is a future-day concern."
  [pd price tiers-by-num]
  (let [tier-num     (or (:urpx/serviceTierNumber price) (:urpx/serviceTierNumber pd))
        service-tier (when tier-num (tiers-by-num tier-num))
        bracket      (first (:urpx/hasServiceBracket service-tier))
        ;; A recurring charge's unit price is per INTERVAL, not per unit of
        ;; consumption: SCE's base services charge is 0.794 per P1D, and its
        ;; ledger's metric input is a meter count carrying no measurement kind
        ;; or unit. Surfacing the number without the interval would hand a
        ;; consumer a figure nothing in the result explains, which is how a
        ;; $10-per-month charge becomes a wrong per-day rate.
        interval     (recurring-interval pd)]
    (cond-> {}
      interval
      (assoc :urpx.resolved/interval-duration interval)
      tier-num
      (assoc :urpx.resolved/tier-number tier-num)

      service-tier
      (merge (cond-> {}
               (:urpx/name service-tier)
               (assoc :urpx.resolved/tier-name (:urpx/name service-tier))
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
                  aids (keep :jsonld/id (:urpx/hasProfileAlternative pas))]
              (-> acc
                  (assoc-in [:cells cid] (set aids))
                  (update :dim-of
                          #(reduce (fn [m a]
                                     (update m a (fn [v] (vec (distinct (conj (or v []) cid))))))
                                   % aids)))))
          {:cells {} :dim-of {}}
          (:urpx/hasProfileAlternatives plan-elements)))

(defn- pd-alternative-status
  "How `pd`'s urpx:referencesProfileAlternative stands against the caller's
   selection. One of:

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
        refs (:urpx/referencesProfileAlternative pd)
        refs (if (sequential? refs) refs (when refs [refs]))]
    (cond
      (empty? refs) :unconstrained

      (< 1 (count refs))
      (throw (ex-info (str "urpx:referencesProfileAlternative names " (count refs)
                           " alternatives on " (pr-str (:jsonld/id pd))
                           ". A PriceDefinition scopes to ONE alternative, the cell; a "
                           "multi-dimensional choice is expressed as one ProfileAlternatives "
                           "whose AlternativeDimensions holds several dimensions and whose "
                           "alternatives enumerate the cells. There is no rule here for "
                           "combining several, and picking one would be arbitrary.")
                      {:reason :multiple-profile-alternatives
                       :price-definition-id (:jsonld/id pd)
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
                          {:reason :ambiguous-profile-alternative
                           :price-definition-id (:jsonld/id pd)
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
   forces them to agree. Every one of the eight pairs in this corpus agrees, so
   this changes nothing today; it exists because validating only against the
   container would REFUSE a selection the price path resolves correctly, and
   rejecting a valid document is the expensive direction."
  [alt-index prices]
  (reduce (fn [idx psas]
            (let [cid  (get-in psas [:urpx/referencesProfileAlternatives :jsonld/id])
                  aids (keep #(get-in % [:urpx/referencesProfileAlternative :jsonld/id])
                             (:urpx/hasPriceSetAlternative psas))]
              (if (and cid (seq aids))
                (-> idx
                    (update-in [:cells cid] (fnil into #{}) aids)
                    (update :dim-of
                            #(reduce (fn [m a]
                                       (update m a (fn [v] (vec (distinct (conj (or v []) cid))))))
                                     % aids)))
                idx)))
          alt-index
          (:urpx/hasPriceSetAlternatives prices)))

(defn- validate-selection!
  "Refuse a `:profile-alternatives` entry whose value is not a cell of the
   container it is keyed under.

   Checked once per document rather than per PriceDefinition, because the
   damage is not confined to the definition-level slot: the SAME typo on the
   urpx:PriceSetAlternatives path, which every filing in the corpus uses, drops
   the whole ledger and answers short with no mark anywhere. On PG&E E-TOU-C
   composed with its vintage-PCIA modifier, a stale departure-vintage id
   returned 0.46886 where the correct id returns 0.49859, and the PCIA ledger
   was simply absent from the result.

   A key naming a container this document does not declare is left alone, not
   refused: one selection map is passed to the base plan and to every modifier,
   and a key meant for one of them is meaningless to the others by design."
  [selection alt-index]
  (doseq [[dim chosen] selection
          :let [known (get (:cells alt-index) dim)]
          :when known]
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
                       :alternatives (vec (sort known))})))))

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
        (:urpx/hasMetricInput plan-elements)))

(defn- ledger-metric-info
  "Return the resolved-shape keys that describe `ledger`'s referenced
   MetricInput. Looks up the first :urpx/referencesMetricInput Ref against
   `mi-index` (a {id -> MetricInput} map). Missing fields are simply
   omitted; consumers that need a particular dimension (e.g. energy-only
   marginal sums) test on what's present."
  [ledger mi-index]
  (let [mi-id (some-> ledger :urpx/referencesMetricInput first :jsonld/id)
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
  (let [rules (:urpx/hasApplicabilityRule pd)]
    (or (empty? rules)
        (let [m (.getMonthValue date)]
          (every? (fn [rule]
                    (let [rule   (if (:urpx/month rule)
                                   rule
                                   (or (get rules-by-id (:jsonld/id rule)) rule))
                          months (:urpx/month rule)]
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
   available as a default: most of the gated population is export price
   matrices where the gate is how the matrix is indexed, and dropping those
   would delete the filing. This surfaces the assumption so a consumer can
   compute a defensible number today, and decides nothing.

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
                    rule  (concat (map deref-rule (:urpx/hasApplicabilityRule owner))
                                  (:urpx/hasApplicabilityGroup owner))
                    :when (:urpx/hasConditionExpression rule)]
                (cond-> {:urpx.resolved/gate-on src}
                  (:jsonld/id rule)   (assoc :urpx.resolved/gate-id (:jsonld/id rule))
                  (:urpx/name rule)   (assoc :urpx.resolved/gate-name (:urpx/name rule))))]
    (seq (vec (distinct gates)))))

(defn- pd-active-in-season?
  "True if `pd`'s urpx:appliesInSeason refs (if any) admit the query date's
   month-of-year. Each ref is resolved via `seasons-by-id` to a Season; the
   Season's urpx:month list is the gate. Multiple refs are disjunctive (any
   admitting season is enough). PDs without urpx:appliesInSeason are always
   season-active.

   This complements `pd-active-in-month?` to support non-TOU seasonal pricing,
   where the seasonal partition isn't carried by a TOUPeriod indirection (no
   TOU schedule), so the only month-gating signal is the PD → Season pointer
   plus Season.month."
  [pd seasons-by-id ^java.time.LocalDate date]
  (let [refs (:urpx/appliesInSeason pd)]
    (or (empty? refs)
        (let [m (.getMonthValue date)]
          (boolean
           (some (fn [ref]
                   (when-let [season (seasons-by-id (:jsonld/id ref))]
                     (some #{m} (:urpx/month season))))
                 refs))))))

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
   nothing in the result to say so."
  [by-id period-num ^LocalDate date rules-by-id seasons-by-id alt-status]
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
                      (and (pd-active-at-tou? pd period-num)
                           (pd-active-in-month? pd date rules-by-id)
                           (pd-active-in-season? pd seasons-by-id date)))))
        :inactive

        (nil? pd) :active

        :else
        (case (alt-status pd)
          :not-selected :inactive
          :unselected   :unknown
          :active)))))

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
      (let [candidates (let [b (:urpx/basedOnPrice price)]
                         (if (sequential? b) (vec b) [b]))
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
              (let [members (:urpx/includesPrice target)
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
                        ;; unreachable for the 69 of 73 filings that carry the
                        ;; discriminator on the definition. The fixture that
                        ;; passed is pinned before that idiom was adopted.
                        tier-of (fn [m]
                                  (or (:urpx/serviceTierNumber m)
                                      (:urpx/serviceTierNumber
                                       (get by-id (get-in m [:urpx/referencesPriceDefinition
                                                             :jsonld/id])))))
                        unknown (filterv #(= :unknown (member-status %)) nodes)
                        active (-> (filterv #(= :active (member-status %)) nodes)
                                   (additive-plus-lowest-rung tier-of))
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

(defn- version-effective-at
  "The instant `version` takes effect, as a LocalDateTime, or nil if it does not
   say. urpx:effectiveDateTime is preferred; urpx:effectiveDate is read as the
   start of that day. Both are sh:minCount 0, so a conformant version may state
   neither, and this returns nil rather than inventing an epoch for it."
  [version]
  (or (:urpx/effectiveDateTime version)
      (some-> (:urpx/effectiveDate version) (.atStartOfDay))))

(defn- version-expired-by?
  "True if `version` states a hard expiration at or before `t`.

   urpx:expirationDate / urpx:expirationDateTime only. The version shapes also
   carry urpx:expectedEndDate and urpx:expectedEndDateTime, which are
   deliberately NOT read here: an expected end anticipates a successor rather
   than terminating anything, so a version past its expected end is still the
   one in force until a later version exists. Treating the two alike would
   silently drop a plan that outlived its own forecast."
  [version ^LocalDateTime t]
  (let [exp (or (:urpx/expirationDateTime version)
                (some-> (:urpx/expirationDate version) (.atStartOfDay)))]
    (boolean (and exp (not (.isAfter ^LocalDateTime exp t))))))

(defn- select-version-at
  "Choose among several candidate snapshots for one plan: the latest whose
   urpx:effectiveDateTime is at or before `t` and which has not expired by `t`.

   Refuses rather than guesses in three cases, for the same reason every other
   refusal here exists. A candidate with no effective instant cannot be ordered
   against the others; a tie on the latest effective instant is not a choice
   this library can make; and if nothing has taken effect by `t` the honest
   answer is that the caller asked about an instant this plan does not cover,
   not the earliest version it happens to have.

   No version in the corpus carries an expiration, so a version's span is
   inferable only from the next version's start. That is exactly why the rule
   needs the full candidate set rather than one snapshot."
  [candidates ^LocalDateTime t plan-id]
  (let [undated (remove version-effective-at candidates)]
    (when (seq undated)
      (throw (ex-info (str "Cannot choose a version for " (pr-str plan-id) ": "
                           (count undated) " of " (count candidates) " candidate snapshot(s) "
                           "state no urpx:effectiveDateTime, so they cannot be ordered "
                           "against the others. Pass :version to choose explicitly.")
                      {:reason :version-without-effective-instant
                       :plan-id plan-id
                       :ids (mapv :jsonld/id candidates)})))
    (let [live (remove #(version-expired-by? % t) candidates)
          eligible (filter #(not (.isAfter ^LocalDateTime (version-effective-at %) t)) live)]
      (cond
        (empty? eligible)
        (throw (ex-info (str "Cannot choose a version for " (pr-str plan-id) ": none of the "
                             (count candidates) " candidate snapshot(s) is in effect at " t
                             ". The earliest takes effect at "
                             (->> candidates (keep version-effective-at) sort first)
                             ". Resolving an instant before a plan existed is a question "
                             "about the caller's window, not about the plan.")
                        {:reason :no-version-in-effect
                         :plan-id plan-id
                         :at t
                         :effective (mapv version-effective-at candidates)}))

        :else
        (let [latest (->> eligible (map version-effective-at) (sort) (last))
              winners (filter #(= latest (version-effective-at %)) eligible)]
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

(defn- version-covers?
  "True if `version` describes the instant `t`: in effect by then, and not
   expired.

   A version stating no effective instant covers everything. It cannot be
   ordered against anything and nothing says it has not started, so refusing
   it would reject a conformant document over a case the vocabulary permits."
  [version ^LocalDateTime t]
  (let [eff (version-effective-at version)]
    (and (not (and eff (.isAfter ^LocalDateTime eff t)))
         (not (version-expired-by? version t)))))

(defn- plan-version
  "Resolve the version snapshot to read plan content from, at `zdt`.

   URPX v0.5.1 un-nests the version: a document may state the plan-to-version
   link downward (`nested-key`, a nested map), upward (urpx:forRatePlan /
   urpx:forRatePlanModifier on a top-level snapshot), or both. Both directions
   are declared owl:inverseOf and the down-pointer is sh:minCount 0, so neither
   can be assumed present.

     1. `:version` in `opts`   an explicitly resolved snapshot always wins
     2. one candidate          used as-is, whether nested or back-pointed
     3. several candidates     the one in effect at `zdt`, or a refusal

   Candidates are the nested snapshot together with everything
   `urpx.index/versions-by-plan` has filed under this plan's @id, deduplicated.

   Selection used to be `(first ...)` over the back-pointer index, which took
   whatever the index happened to hold first rather than what was in force.
   That was invisible while every filing carried exactly one version, and six
   SCE plans in the corpus already carry two. A forecast client loading several
   versions to cover a window is the case it was silently wrong for."
  [plan opts nested-key ^ZonedDateTime zdt]
  (or (:version opts)
      (let [nested (index/nested-version plan nested-key)
            plan-id (:jsonld/id plan)
            indexed (when-let [by-plan (:versions-by-plan opts)] (get by-plan plan-id))
            ;; Deduplicated BY VALUE, not by @id. The nested snapshot and its
            ;; entry in the back-pointer index are the same node and collapse.
            ;; Two nodes sharing an @id but differing in content do NOT: the
            ;; corpus has exactly that, where two CPAU e-1 filings both publish
            ;; cpau:e-1-2025-07-01 at the same effective instant while differing
            ;; in urpx:hasModifierOptions. Collapsing on @id would pick one of
            ;; those silently, which is the failure this whole function exists
            ;; to remove; as two candidates they tie and are refused instead.
            candidates (distinct (remove nil? (cons nested indexed)))]
        (case (count candidates)
          0 nil
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
          (select-version-at candidates (.toLocalDateTime zdt) plan-id)))))


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
                deliberate choice of some other cell.

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
   by tier-number — the caller chooses based on cumulative billing-period usage."
  ([rate-plan zdt] (resolve-prices rate-plan zdt nil))
  ([rate-plan ^ZonedDateTime zdt opts]
   (let [version       (plan-version rate-plan opts :urpx/hasRatePlanVersion zdt)
         plan-elements (:urpx/hasPlanElements version)
         seasons       (:urpx/hasSeasons plan-elements)
         seasons-by-id (into {} (map (juxt :jsonld/id identity)) seasons)
         billing       (:urpx/hasBillingModel plan-elements)
         ledgers       (:urpx/hasLedger billing)
         tou-schedules (:urpx/hasTOUSchedule billing)
         tiers-by-num  (service-tiers-by-number billing)
         prices        (:urpx/hasPrices version)
         holiday?      (or (:holiday? opts)
                           (holidays/derive-predicate rate-plan version))

         date          (.toLocalDate zdt)
         local-time    (.toLocalTime zdt)
         dow           (.getDayOfWeek zdt)

         season        (season-of seasons date)
         tou-period    (find-tou-period tou-schedules (:jsonld/id season)
                                        date local-time dow holiday?)
         period-num    (:urpx/touPeriodNumber tou-period)

         pd->prices    (active-prices-by-pd prices date opts period-num)
         pds           (:urpx/hasPriceDefinition billing)
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
         _             (validate-selection! (:profile-alternatives opts) alt-index)
         alt-status    #(pd-alternative-status % (:profile-alternatives opts) alt-index)
         ;; Ledger-independent, so it is built once rather than per ledger, and
         ;; it is the ONLY view that sees Prices nested under an alternatives
         ;; set the caller did not select: the base path's price lookup skips
         ;; those by design. Enumerating a withheld definition from that lookup
         ;; alone left it named in the mark and absent from the evidence, and
         ;; one unrelated excluded price on the same ledger was then enough to
         ;; drop the refusal and answer short.
         psa-prices    (withheld-prices-by-pd prices (:profile-alternatives opts))
         member-status (or (:member-status opts)
                           (make-member-status by-id period-num date
                                               rules-by-id seasons-by-id alt-status))

         ledger-results
         (vec
          (for [ledger ledgers
                :let [scoped-pds (->> (pds-by-ledger (:jsonld/id ledger))
                                      (filter #(and (pd-active-at-tou? % period-num)
                                                    (pd-active-in-month? % date rules-by-id)
                                                    (pd-active-in-season? % seasons-by-id date))))
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
                                                (tier-info pd price tiers-by-num))
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
      (when-not (version-covers? version (.toLocalDateTime zdt))
        {:urpx.resolved/outside-version-window
         {:version-id (:jsonld/id version)
          :effective  (version-effective-at version)
          :at         (.toLocalDateTime zdt)}})))))

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
            refs    (:urpx/applicableToRatePlan version)]
        (boolean (some #(= plan-id (:jsonld/id %)) refs))))))

(defn- modifier-ledger-results
  "Build a vector of LedgerResult entries for `modifier` at `zdt`.

   TOU gating for modifier PDs (issue #4):
   1. If the modifier carries its own urpx:hasTOUSchedule, resolve the
      modifier's own TOU period at `zdt` and gate PDs against THAT period
      number — symmetric with base PD gating.
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
        ledgers       (:urpx/hasLedger billing)
        prices        (:urpx/hasPrices version)
        alt-index     (or (:alt-index opts)
                          (-> (profile-alternative-index plan-elts)
                              (index-with-price-set-alternatives prices)))
        _             (validate-selection! (:profile-alternatives opts) alt-index)
        alt-status    #(pd-alternative-status % (:profile-alternatives opts) alt-index)
        member-status (or (:member-status opts)
                          ;; resolved on its own, a modifier has only its own
                          ;; context to judge by; composing supplies the base
                          ;; plan's, which is the one that matters.
                          (make-member-status by-id base-period-num (.toLocalDate zdt)
                                              {} {} alt-status))
        own-schedules (:urpx/hasTOUSchedule billing)
        own-seasons   (:urpx/hasSeasons plan-elts)
        query-date    (.toLocalDate zdt)
        own-period-num
        (when (seq own-schedules)
          (let [season-id  (:jsonld/id (season-of own-seasons query-date))
                local-time (.toLocalTime zdt)
                dow        (.getDayOfWeek zdt)
                hol?       (or holiday? (constantly false))]
            (:urpx/touPeriodNumber
             ;; hol?, not (hol? query-date): find-tou-period takes the PREDICATE
             ;; and applies it per bracket. Passing the boolean made
             ;; holiday-status-matches? call `true` as a function, which threw
             ;; ClassCastException once a bracket actually reached the holiday
             ;; check (it needs the modifier to carry its own seasons as well as
             ;; its own schedule, which is why no fixture caught it).
             (find-tou-period own-schedules season-id query-date local-time dow hol?))))
        gate-period (if (seq own-schedules) own-period-num base-period-num)
        gate?       (or (seq own-schedules) (some? base-period-num))
        pd->prices  (all-active-prices-by-pd prices query-date opts gate-period)
        unselected  (unselected-alternative-ids prices (:profile-alternatives opts))
        pds         (:urpx/hasPriceDefinition billing)
        pds-by-ledger (group-by #(get-in % [:urpx/referencesLedger :jsonld/id]) pds)
        ;; A modifier's PriceDefinitions are scoped by urpx:referencesProfileAlternative
        ;; exactly as the base plan's are (alt-status, above). This path ignored
        ;; the property, which made the whole feature a no-op where it is most
        ;; needed: a matrix of mutually-exclusive cells is a modifier shape, so
        ;; a definition scoped to a cell the caller did not choose was summed
        ;; anyway, unmarked.
        psa-prices  (withheld-prices-by-pd prices (:profile-alternatives opts))
        mi-index    (mi-by-id plan-elts)]
    (vec
     (for [ledger ledgers
           :let [scoped-pds (cond->> (pds-by-ledger (:jsonld/id ledger))
                              gate? (filter #(pd-active-at-tou? % gate-period)))
                 by-alt     (group-by alt-status scoped-pds)
                 ledger-pds (concat (:unconstrained by-alt) (:selected by-alt))
                 alt-withheld (map :jsonld/id (:unselected by-alt))
                 tier-entries
                 (->> ledger-pds
                      (mapcat (fn [pd]
                                ;; The interval is read here as well as on the
                                ;; base path. Without it a recurring modifier
                                ;; price could never be excluded from a per-unit
                                ;; sum, and the withheld view of that same price
                                ;; disagreed with the present view about whether
                                ;; it counts.
                                (let [interval (recurring-interval pd)]
                                  (for [[price _] (pd->prices (:jsonld/id pd))
                                        :let [gates (condition-gates price pd by-id)]]
                                    (cond-> (merge
                                             {:urpx.resolved/price-definition-id (:jsonld/id pd)
                                              :urpx.resolved/price-id            (:jsonld/id price)
                                              :urpx.resolved/price-name          (:urpx/name price)}
                                             (price-fields price by-id member-status))
                                      interval
                                      (assoc :urpx.resolved/interval-duration interval)
                                      (:urpx/hasDynamicPricing price)
                                      (assoc :urpx.resolved/dynamic-pricing
                                             (:urpx/hasDynamicPricing price))
                                      gates
                                      (assoc :urpx.resolved/condition-gated gates))))))
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
              (when-let [ids (seq (concat unselected-ids withheld))]
                {:urpx.resolved/unselected-alternatives (vec ids)
                 :urpx.resolved/withheld-prices         withheld-prices})
              (ledger-metric-info ledger mi-index)))))))

(defn resolve-modifier-prices
  "Resolve every per-energy unit price that `modifier` (a coerced
   urpx:RatePlanModifier) contributes at `zdt`.

   Returns the same shape as `resolve-prices` minus the season/TOU keys
   (modifier ledgers are flat add-ons): {:urpx.resolved/ledgers [...]}.

   When the modifier carries its own urpx:hasTOUSchedule, PDs with
   :urpx/touPeriodNumber are gated against the modifier's resolved period.
   Without an own TOUSchedule and no base context, every applicable Price
   is surfaced — matrix-style modifiers (e.g. CPAU E-HRA — 4x4 reserve
   level x generation tier driven by external state) rely on this so the
   caller can select the active value, since the URPX condition
   vocabulary (operator + label conditionValue) is not yet specified in a
   way that's programmatically interpretable. Filter the returned :tiers
   vector on :urpx.resolved/price-id when needed."
  [modifier ^ZonedDateTime zdt]
  {:urpx.resolved/ledgers (modifier-ledger-results modifier zdt nil nil)})

(defn resolve-prices-with-modifiers
  "Resolve prices for `rate-plan` at `zdt` and append ledger results for
   each modifier in `modifiers` whose :urpx/applicableToRatePlan list
   includes the base plan's @id. Modifiers whose target lists don't include
   the plan are silently skipped.

   Returns the `resolve-prices` shape with :urpx.resolved/ledgers extended
   by the modifier ledgers."
  ([rate-plan modifiers ^ZonedDateTime zdt] (resolve-prices-with-modifiers rate-plan modifiers zdt nil))
  ([rate-plan modifiers ^ZonedDateTime zdt opts]
   (let [;; One @id index spanning the base plan and every modifier. This is
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
                                              versions))))
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
                                     (into {} (map (juxt :jsonld/id identity))
                                           (get-in base-version [:urpx/hasPlanElements :urpx/hasSeasons]))
                                     #(pd-alternative-status
                                       % (:profile-alternatives opts) (:alt-index opts)))))
         holiday?        (or (:holiday? opts) (holidays/derive-predicate rate-plan))
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
   -213.15920 per kWh, because four export ledgers carrying 4,610 hourly
   avoided-cost values were added to a 0.56006 import rate.

   urpx:net and urpx:bidirectional are treated as contributing. Neither appears
   in the corpus, so excluding them would be speculation; if one shows up, a
   netted quantity in an import marginal is genuinely ambiguous and this is the
   line to revisit."
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

   REFUSES, rather than returning a number, in six cases, each carrying a
   named `:reason` in its ex-data so a sweeping consumer can classify:

     :no-energy-ledger            this plan prices nothing per kWh, so the
                                  question does not apply to it. 0M would be
                                  indistinguishable from a plan whose energy
                                  ledgers genuinely sum to zero
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
                                  instant

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
        ;; stacks that most of this corpus uses ledgers for. SDG&E EV-TOU's
        ;; delivery ledger carries ten components; keeping the first dropped
        ;; nine of them and reported 0.57086 where the components sum to
        ;; 0.86302, a third low. It is not a one-sided error either: SCE
        ;; TOU-D-4-9PM OVERSTATED by 0.05249 at summer on-peak, because the
        ;; dropped set included a negative Baseline Credit.
        ;;
        ;; urpx:serviceTierNumber separates the two, and cleanly: across 40 base
        ;; plans at four instants, multi-tier energy ledgers were 183 additive,
        ;; 31 ladder, and 3 mixed, all three being one ledger. URPX has no
        ;; vocabulary distinguishing additive from exclusive tiers, so the tier
        ;; number is the signal available rather than the signal designed.
        ;;
        ;; MIXED is summed-plus-lowest rather than refused. The one real case,
        ;; sdge:dr-2026-04-01-ledger-energy-delivery, is ten UDC components plus
        ;; a single tier-numbered Baseline Credit, where this and plain summing
        ;; agree exactly. Refusing would decline a question that has an answer,
        ;; and plain summing would re-create the original bug the moment a
        ;; genuine ladder appeared beside additive components.
        marginal   (mapcat #(-> (remove exclude-tier? (:urpx.resolved/tiers %))
                                (additive-plus-lowest-rung :urpx.resolved/tier-number))
                           energy)
        unvaluable (filter :urpx.resolved/unvaluable marginal)
        ;; Only tiers that would actually ENTER the sum: `marginal` is already
        ;; import-energy-only and already past exclude-tier?, so a gated export
        ;; price or a gated event charge is not in scope here.
        gated      (filter :urpx.resolved/condition-gated marginal)]
    ;; A price from a version that does not describe this instant is the
    ;; confident wrong number this whole function exists to refuse. The
    ;; structural answers alongside it (season, TOU period) stay available on
    ;; the resolved map, which is why the mark is there rather than a throw
    ;; from resolve-prices.
    (when-let [w (:urpx.resolved/outside-version-window resolved)]
      (throw (ex-info (str "Cannot compute a marginal rate: the version used, "
                           (pr-str (:version-id w)) ", does not cover " (:at w)
                           (when-let [e (:effective w)] (str "; it takes effect " e))
                           ". Resolve at an instant the filing describes, or load "
                           "the version that does and pass :versions-by-plan.")
                      {:reason :outside-version-window
                       :version-id (:version-id w)
                       :effective (:effective w)
                       :at (:at w)})))
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

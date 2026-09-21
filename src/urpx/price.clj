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
  (:import [java.time DayOfWeek LocalDate LocalTime ZonedDateTime]))

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

   BillingModel.hasTOUSchedule is a vector (URPX-qb5): one schedule per
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
                        (when-let [sd (:urpx/startDate p)]
                          (when (not-after? sd query-date) [p sd])))
                      (:urpx/hasPrice picked)))))
          (:urpx/hasPriceSetAlternatives prices)))

(defn- scoped-prices
  "Return a seq of [price effective-start-date] pairs for every Price under
   `prices` whose effective start-date is <= `query-date`. Walks the flat
   :urpx/hasPrice list, PriceSet-nested prices, and PriceSetAlternatives-
   nested prices when the caller supplies an explicit
   `:profile-alternatives` selection in opts (URPX-vlf).

   Per URPX SHACL, PriceSet.startDate is optional (sh:minCount 0). When the
   PriceSet carries one, it acts as the date floor for any contained Price
   that does not declare its own; when the PriceSet omits it, each contained
   Price must carry its own urpx:startDate (CPAU E-2 shape). A PriceSet
   whose startDate is in the future drops the whole set; otherwise contained
   Prices are walked individually and admitted by their own effective dates."
  [prices ^LocalDate query-date opts]
  (let [selection (:profile-alternatives opts {})]
    (concat
     (keep (fn [p]
             (when-let [sd (:urpx/startDate p)]
               (when (not-after? sd query-date) [p sd])))
           (:urpx/hasPrice prices))
     (mapcat (fn [ps]
               (let [ps-sd (:urpx/startDate ps)]
                 (when (or (nil? ps-sd) (not-after? ps-sd query-date))
                   (keep (fn [p]
                           (when-let [sd (or (:urpx/startDate p) ps-sd)]
                             (when (not-after? sd query-date) [p sd])))
                         (:urpx/hasPrice ps)))))
             (:urpx/hasPriceSet prices))
     (prices-from-alternatives prices query-date selection))))

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
  [prices ^LocalDate query-date opts]
  (let [latest-per-key
        (reduce (fn [acc [price sd]]
                  (let [k [(get-in price [:urpx/referencesPriceDefinition :jsonld/id])
                           (:urpx/serviceTierNumber price)]
                        [_ existing-sd] (acc k)]
                    (if (or (nil? existing-sd) (.isAfter sd existing-sd))
                      (assoc acc k [price sd])
                      acc)))
                {} (scoped-prices prices query-date opts))]
    (reduce-kv (fn [acc [pd-id _] pair]
                 (update acc pd-id (fnil conj []) pair))
               {} latest-per-key)))

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
                              (when-let [sd (:urpx/startDate p)]
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
  ([prices ^LocalDate query-date] (all-active-prices-by-pd prices query-date nil))
  ([prices ^LocalDate query-date opts]
   (let [selection (:profile-alternatives opts)]
     (reduce (fn [acc [price :as pair]]
               (let [pd-id (get-in price [:urpx/referencesPriceDefinition :jsonld/id])]
                 (update acc pd-id (fnil conj []) pair)))
             {}
             (concat (scoped-prices prices query-date nil)
                     ;; With a selection, surface only the chosen alternative,
                     ;; exactly as the base plan path does. Without one, keep the
                     ;; surface-all contract so a caller reading tiers directly
                     ;; still sees every vintage; `unselected-alternative-ids`
                     ;; then marks the ledger so an AGGREGATE cannot silently
                     ;; pick one of them.
                     (if (seq selection)
                       (prices-from-alternatives prices query-date selection)
                       (all-prices-from-alternatives prices query-date)))))))

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

(defn- service-tiers-by-number
  "Index ServiceTier entities (across every ServiceTiers container at
   BillingModel.hasServiceTiers, walking each container's :urpx/hasServiceTier
   list) by :urpx/serviceTierNumber. Empty map when the plan is not
   block-tiered. URPX-qb5/URPX-6hf: hasServiceTiers is a vector — single
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
        bracket      (first (:urpx/hasServiceBracket service-tier))]
    (cond-> {}
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

(defn- pd-active-at-tou?
  "True if `pd` should be considered active at the resolved TOU period.
   Skips recurring/subscription charges (PD has its own CalculationMethod).
   PDs without :urpx/touPeriodNumber are always TOU-active (flat).
   PDs with :urpx/touPeriodNumber must match the resolved period number."
  [pd resolved-period-num]
  (cond
    (:urpx/hasCalculationMethod pd) false
    (nil? (:urpx/touPeriodNumber pd)) true
    :else (= (:urpx/touPeriodNumber pd) resolved-period-num)))

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
      (assoc :urpx.resolved/unit-of-measure (:urpx/unitOfMeasure spec)))))

(defn- pd-active-in-month?
  "True if `pd`'s urpx:hasApplicabilityRule constraints (if any) admit the
   query date's month-of-year. Each rule that carries a non-empty
   urpx:month list contributes a conjunctive constraint; a rule without
   urpx:month is permissive at this layer (any urpx:hasConditionExpression
   gating is deferred — see URPX-axn). PDs without applicability rules
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

(defn- pd-active-in-season?
  "True if `pd`'s urpx:appliesInSeason refs (if any) admit the query date's
   month-of-year. Each ref is resolved via `seasons-by-id` to a Season; the
   Season's urpx:month list is the gate. Multiple refs are disjunctive (any
   admitting season is enough). PDs without urpx:appliesInSeason are always
   season-active.

   This complements `pd-active-in-month?` to support non-TOU seasonal pricing
   (URPX-2nk) — where the seasonal partition isn't carried by a TOUPeriod
   indirection (no TOU schedule), so the only month-gating signal is the
   PD → Season pointer plus Season.month."
  [pd seasons-by-id ^java.time.LocalDate date]
  (let [refs (:urpx/appliesInSeason pd)]
    (or (empty? refs)
        (let [m (.getMonthValue date)]
          (boolean
           (some (fn [ref]
                   (when-let [season (seasons-by-id (:jsonld/id ref))]
                     (some #{m} (:urpx/month season))))
                 refs))))))

(defn- make-member-active?
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
   in, which for a cross-document discount is the base plan, not the modifier."
  [by-id period-num ^LocalDate date rules-by-id seasons-by-id]
  (fn [member]
    (let [pd (get by-id (get-in member [:urpx/referencesPriceDefinition :jsonld/id]))]
      (or (nil? pd)
          (and (pd-active-at-tou? pd period-num)
               (pd-active-in-month? pd date rules-by-id)
               (pd-active-in-season? pd seasons-by-id date))))))

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
  [price by-id member-active?]
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
                  (let [active (filterv member-active? nodes)
                        values (mapv :urpx/unitPrice active)]
                    (cond
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
  [price by-id member-active?]
  (let [{:keys [value unvaluable]} (price-value price by-id member-active?)]
    (cond-> {:urpx.resolved/unit-price value}
      unvaluable (assoc :urpx.resolved/unvaluable unvaluable))))

(defn- plan-version
  "Resolve the version snapshot to read plan content from.

   URPX v0.5.1 un-nests the version: a document may state the plan-to-version
   link downward (`nested-key`, a nested map), upward (urpx:forRatePlan /
   urpx:forRatePlanModifier on a top-level snapshot), or both. Both directions
   are declared owl:inverseOf and the down-pointer is sh:minCount 0, so neither
   can be assumed present.

   Precedence, widest to narrowest:
     1. `:version` in `opts`   an explicitly resolved snapshot always wins
     2. the nested down-pointer  the v0.2.x shape, still legal at v0.5.1
     3. `:versions-by-plan` in `opts`, as produced by
        `urpx.index/versions-by-plan`, looked up by the plan's own @id

   Nothing here removes a nested read: the corpus is mixed-vocabulary for the
   duration of its migration and every filing in it today is nested."
  [plan opts nested-key]
  (or (:version opts)
      (index/nested-version plan nested-key)
      (when-let [by-plan (:versions-by-plan opts)]
        (first (get by-plan (:jsonld/id plan))))))


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
                map driving customer-profile-driven price selection. When the
                rate plan declares Prices.hasPriceSetAlternatives (URPX v0.2.0
                ProfileAlternatives idiom — income-tier CARE/FERA/Standard,
                vintage-PCIA default tier, voltage configuration, …) each
                PriceSetAlternatives surfaces the prices of the explicitly-
                chosen alternative. Unmapped alternatives are silently skipped
                — programmatic ConditionExpression evaluation against a
                customer profile is a future enhancement.

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
      :urpx.resolved/tiers               [TierResult]}

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
                                                    of this key)}

   For non-tiered ledgers, :tiers has a single entry with no tier-* keys.
   For block-tiered ledgers (e.g. CPAU E-1), :tiers carries every tier sorted
   by tier-number — the caller chooses based on cumulative billing-period usage."
  ([rate-plan zdt] (resolve-prices rate-plan zdt nil))
  ([rate-plan ^ZonedDateTime zdt opts]
   (let [version       (plan-version rate-plan opts :urpx/hasRatePlanVersion)
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

         pd->prices    (active-prices-by-pd prices date opts)
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
         member-active? (or (:member-active? opts)
                            (make-member-active? by-id period-num date
                                                 rules-by-id seasons-by-id))

         ledger-results
         (vec
          (for [ledger ledgers
                :let [ledger-pds (->> (pds-by-ledger (:jsonld/id ledger))
                                      (filter #(and (pd-active-at-tou? % period-num)
                                                    (pd-active-in-month? % date rules-by-id)
                                                    (pd-active-in-season? % seasons-by-id date))))
                      tier-entries
                      (->> ledger-pds
                           (mapcat (fn [pd]
                                     (for [[price _] (pd->prices (:jsonld/id pd))]
                                       (cond-> (merge
                                                {:urpx.resolved/price-definition-id (:jsonld/id pd)
                                                 :urpx.resolved/price-id            (:jsonld/id price)
                                                 :urpx.resolved/price-name          (:urpx/name price)}
                                                (price-fields price by-id member-active?)
                                                (tier-info pd price tiers-by-num))
                                         (:urpx/hasDynamicPricing price)
                                         (assoc :urpx.resolved/dynamic-pricing
                                                (:urpx/hasDynamicPricing price))))))
                           (sort-by #(or (:urpx.resolved/tier-number %) 0))
                           vec)]
                :when (seq tier-entries)]
            (merge {:urpx.resolved/ledger-id   (:jsonld/id ledger)
                    :urpx.resolved/ledger-name (:urpx/name ledger)
                    :urpx.resolved/ledger-type (:urpx/ledgerType ledger)
                    :urpx.resolved/tiers       tier-entries}
                   (ledger-metric-info ledger mi-index))))]

     {:urpx.resolved/season-name       (:urpx/seasonName season)
      :urpx.resolved/tou-period-name   (:urpx/name tou-period)
      :urpx.resolved/tou-period-number period-num
      :urpx.resolved/ledgers           ledger-results})))

;; ---------------------------------------------------------------------------
;; Modifier resolution (URPX-yos)
;; ---------------------------------------------------------------------------

(defn- modifier-applies-to?
  "True if `modifier` declares the rate plan @id `plan-id` in its
   :urpx/applicableToRatePlan list. Returns true when `plan-id` is nil so
   callers that don't have a plan id (e.g. resolving a modifier in isolation)
   are not blocked."
  ([modifier plan-id] (modifier-applies-to? modifier plan-id nil))
  ([modifier plan-id opts]
  (or (nil? plan-id)
      (let [version (plan-version modifier opts :urpx/hasRatePlanModifierVersion)
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
  (let [version       (plan-version modifier opts :urpx/hasRatePlanModifierVersion)
        ;; @id index for urpx:basedOnPrice. Supplied by
        ;; resolve-prices-with-modifiers spanning the base plan AND every
        ;; modifier, which is what makes a discount Price in a modifier able to
        ;; reach a urpx:PriceSummary defined in the base plan filing. Resolving
        ;; a modifier alone falls back to the modifier's own document, and a
        ;; reference out of it is reported unvaluable rather than valued wrong.
        by-id         (or (:by-id opts) (index/build-index version))
        member-active? (or (:member-active? opts)
                           ;; resolved on its own, a modifier has only its own
                           ;; context to judge by; composing supplies the base
                           ;; plan's, which is the one that matters.
                           (make-member-active? by-id base-period-num (.toLocalDate zdt) {} {}))
        plan-elts     (:urpx/hasPlanElements version)
        billing       (:urpx/hasBillingModel plan-elts)
        ledgers       (:urpx/hasLedger billing)
        prices        (:urpx/hasPrices version)
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
        pd->prices  (all-active-prices-by-pd prices query-date opts)
        unselected  (unselected-alternative-ids prices (:profile-alternatives opts))
        pds         (:urpx/hasPriceDefinition billing)
        pds-by-ledger (group-by #(get-in % [:urpx/referencesLedger :jsonld/id]) pds)
        mi-index    (mi-by-id plan-elts)]
    (vec
     (for [ledger ledgers
           :let [ledger-pds (cond->> (pds-by-ledger (:jsonld/id ledger))
                              gate? (filter #(pd-active-at-tou? % gate-period)))
                 tier-entries
                 (->> ledger-pds
                      (mapcat (fn [pd]
                                (for [[price _] (pd->prices (:jsonld/id pd))]
                                  (cond-> (merge
                                           {:urpx.resolved/price-definition-id (:jsonld/id pd)
                                            :urpx.resolved/price-id            (:jsonld/id price)
                                            :urpx.resolved/price-name          (:urpx/name price)}
                                           (price-fields price by-id member-active?))
                                    (:urpx/hasDynamicPricing price)
                                    (assoc :urpx.resolved/dynamic-pricing
                                           (:urpx/hasDynamicPricing price))))))
                      ;; Sorted like the base path. marginal-unit-rate documents
                      ;; itself as taking the LOWEST tier and implements that as
                      ;; `first`, which only holds if the vector is ordered by
                      ;; tier number. Unsorted, the modifier path handed it
                      ;; whatever came first in the document.
                      (sort-by #(or (:urpx.resolved/tier-number %) 0))
                      vec)]
           :when (seq tier-entries)]
       (merge {:urpx.resolved/ledger-id   (:jsonld/id ledger)
               :urpx.resolved/ledger-name (:urpx/name ledger)
               :urpx.resolved/ledger-type (:urpx/ledgerType ledger)
               :urpx.resolved/tiers       tier-entries}
              (when-let [ids (seq (filter unselected (map :urpx.resolved/price-id tier-entries)))]
                {:urpx.resolved/unselected-alternatives (vec ids)})
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
         base            (resolve-prices rate-plan zdt opts)
         plan-id         (:jsonld/id rate-plan)
         base-period-num (:urpx.resolved/tou-period-number base)
         ;; A discount Price in a modifier names a summary whose members live in
         ;; the BASE plan, so their season, month and TOU gates are the base
         ;; plan's. Built here and passed down, because modifier-ledger-results
         ;; can only see the modifier's own seasons and rules.
         base-version    (plan-version rate-plan opts :urpx/hasRatePlanVersion)
         opts            (assoc opts :member-active?
                                (or (:member-active? opts)
                                    (make-member-active?
                                     (:by-id opts)
                                     base-period-num
                                     (.toLocalDate zdt)
                                     (or (:rules-by-id opts)
                                         (index/entities-of-type base-version "urpx:ApplicabilityRule"))
                                     (into {} (map (juxt :jsonld/id identity))
                                           (get-in base-version [:urpx/hasPlanElements :urpx/hasSeasons])))))
         holiday?        (or (:holiday? opts) (holidays/derive-predicate rate-plan))
         ;; opts, not the 2-arity: without it plan-version can only follow the
         ;; nested down-pointer, so a v0.5.1 modifier that states the link only
         ;; through its urpx:forRatePlanModifier back-pointer resolved to a nil
         ;; version, matched no applicableToRatePlan, and was dropped from the
         ;; composition without a word.
         extra           (mapcat #(when (modifier-applies-to? % plan-id opts)
                                    (modifier-ledger-results % zdt base-period-num holiday? opts))
                                 modifiers)]
     (update base :urpx.resolved/ledgers (fnil into []) extra))))

(defn marginal-unit-rate
  "Sum the unit prices across all **energy** ledgers, picking the LOWEST tier
   in each block-tiered ledger as the marginal $/kWh at the start of a billing
   period.

   A ledger is treated as energy iff its referenced MetricInput's
   urpx:measurementKind is urpx:energy. Per-meter, per-customer, per-day, or
   per-month service charges (urpx:profileMetricInput, or non-energy
   measurementKinds) are excluded — they have a different shape and don't
   belong in a $/kWh sum.

   Useful for price signaling: when you don't know a customer's usage but want
   a single $/kWh number, this is the marginal rate at zero baseline usage.

   REFUSES, rather than returning a number, in two cases:

     a marginal tier could not be valued       a nil unit price reads to a
                                               consumer as free, and for a
                                               discount that is the worst
                                               available answer
     an energy ledger carries mutually-        these are alternatives, not
     exclusive price alternatives with no      tiers: exactly one applies to a
     caller selection                          customer, and which one is the
                                               caller's to say

   The second is why composing a PG&E plan with its vintage-PCIA modifier
   needs `:profile-alternatives`. Taking the first of eighteen mutually
   exclusive prices added an arbitrary 0.02973 to the rate; the eighteen span
   0.06391/kWh, so there is no default worth inventing."
  [resolved]
  (let [energy     (->> (:urpx.resolved/ledgers resolved)
                        (filter #(and (= "urpx:energy"
                                         (get-in % [:urpx.resolved/measurement-kind :jsonld/id]))
                                      (seq (:urpx.resolved/tiers %)))))
        ;; A ledger of mutually-exclusive alternatives has no marginal tier
        ;; until the caller says which alternative applies. Taking the first
        ;; added an arbitrary one of PG&E's 18 vintage-PCIA prices to every
        ;; marginal rate: 0.41784 became 0.44757, and the 18 span 0.06391/kWh.
        ;; Pass :profile-alternatives to choose; there is no default worth
        ;; inventing here.
        unselected (filter :urpx.resolved/unselected-alternatives energy)
        marginal   (map #(first (:urpx.resolved/tiers %)) energy)
        unvaluable (filter :urpx.resolved/unvaluable marginal)]
    (when (seq unselected)
      (throw (ex-info (str "Cannot compute a marginal rate: "
                           (count unselected) " energy ledger(s) carry mutually-exclusive "
                           "price alternatives with no selection. Pass :profile-alternatives "
                           "naming the applicable urpx:ProfileAlternative for each.")
                      {:ledgers (mapv #(select-keys % [:urpx.resolved/ledger-id
                                                       :urpx.resolved/unselected-alternatives])
                                      unselected)})))
    ;; Refuse rather than sum around a price this library could not value.
    ;; Skipping would silently under-report the rate, which for a discount
    ;; means reporting the undiscounted price as though it were the real one.
    (when (seq unvaluable)
      (throw (ex-info (str "Cannot compute a marginal rate: "
                           (count unvaluable) " marginal energy price(s) could not be valued. "
                           "Inspect :urpx.resolved/unvaluable on the offending tiers.")
                      {:unvaluable (mapv :urpx.resolved/unvaluable unvaluable)})))
    (reduce (fn [^java.math.BigDecimal acc tier]
              (.add acc ^java.math.BigDecimal (:urpx.resolved/unit-price tier)))
            0M
            marginal)))

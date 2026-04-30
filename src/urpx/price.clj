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
  (:require [urpx.holidays :as holidays])
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
   MetricInput when the filing TOU-zones multiple metrics. The first
   bracket-matching period across all schedules wins."
  [tou-schedules season-id ^LocalDate date ^LocalTime t ^DayOfWeek dow holiday?]
  (when (seq tou-schedules)
    (some
     (fn [period]
       (when (some
              (fn [bracket]
                (and (= season-id (get-in bracket [:urpx/appliesInSeason :jsonld/id]))
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

(defn- scoped-prices
  "Return a seq of [price effective-start-date] pairs for every Price under
   `prices` whose effective start-date is <= `query-date`. Walks both the
   flat :urpx/hasPrice list and PriceSet-nested prices.

   Per URPX SHACL, PriceSet.startDate is optional (sh:minCount 0). When the
   PriceSet carries one, it acts as the date floor for any contained Price
   that does not declare its own; when the PriceSet omits it, each contained
   Price must carry its own urpx:startDate (CPAU E-2 shape). A PriceSet
   whose startDate is in the future drops the whole set; otherwise contained
   Prices are walked individually and admitted by their own effective dates."
  [prices ^LocalDate query-date]
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
           (:urpx/hasPriceSet prices))))

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
  [prices ^LocalDate query-date]
  (let [latest-per-key
        (reduce (fn [acc [price sd]]
                  (let [k [(get-in price [:urpx/referencesPriceDefinition :jsonld/id])
                           (:urpx/serviceTierNumber price)]
                        [_ existing-sd] (acc k)]
                    (if (or (nil? existing-sd) (.isAfter sd existing-sd))
                      (assoc acc k [price sd])
                      acc)))
                {} (scoped-prices prices query-date))]
    (reduce-kv (fn [acc [pd-id _] pair]
                 (update acc pd-id (fnil conj []) pair))
               {} latest-per-key)))

(defn- all-active-prices-by-pd
  "Build a map from PriceDefinition id to a vector of [price start-date]
   pairs for every active Price referencing that PD. Used by modifier
   resolution where matrix-style pricing (e.g. CPAU E-HRA) surfaces
   multiple concurrent prices that the caller selects from."
  [prices ^LocalDate query-date]
  (reduce (fn [acc [price :as pair]]
            (let [pd-id (get-in price [:urpx/referencesPriceDefinition :jsonld/id])]
              (update acc pd-id (fnil conj []) pair)))
          {} (scoped-prices prices query-date)))

;; ---------------------------------------------------------------------------
;; Service-tier lookup (block tiers, e.g. CPAU E-1 baseline / over-baseline)
;; ---------------------------------------------------------------------------

(defn- service-tiers-by-number
  "Index ServiceTier entities (from BillingModel.hasServiceTiers.hasServiceTier)
   by their :urpx/serviceTierNumber. Empty map when the plan is not block-tiered."
  [billing-model]
  (into {} (map (juxt :urpx/serviceTierNumber identity))
        (get-in billing-model [:urpx/hasServiceTiers :urpx/hasServiceTier])))

(defn- tier-info
  "Pull tier metadata for a (PD, Price) pair into the resolved-entry shape.
   The serviceTierNumber may live on the Price (PG&E-style: many tier-Prices
   share one PD) or on the PD (CPAU-style: one PD per tier). The Price's
   number wins when both are present. Returns an empty map when the entry
   isn't block-tiered."
  [pd price tiers-by-num]
  (let [tier-num     (or (:urpx/serviceTierNumber price) (:urpx/serviceTierNumber pd))
        service-tier (when tier-num (tiers-by-num tier-num))]
    (cond-> {}
      tier-num
      (assoc :urpx.resolved/tier-number tier-num)

      service-tier
      (merge (cond-> {}
               (:urpx/name service-tier)
               (assoc :urpx.resolved/tier-name (:urpx/name service-tier))
               (:urpx/lowerBound service-tier)
               (assoc :urpx.resolved/tier-lower-bound (:urpx/lowerBound service-tier))
               (:urpx/upperBound service-tier)
               (assoc :urpx.resolved/tier-upper-bound (:urpx/upperBound service-tier))
               (:urpx/lowerBoundOperator service-tier)
               (assoc :urpx.resolved/tier-lower-bound-operator
                      (:urpx/lowerBoundOperator service-tier))
               (:urpx/upperBoundOperator service-tier)
               (assoc :urpx.resolved/tier-upper-bound-operator
                      (:urpx/upperBoundOperator service-tier)))))))

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
        spec  (:urpx/hasMetricSpecification mi)]
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
   are always month-active."
  [pd ^java.time.LocalDate date]
  (let [rules (:urpx/hasApplicabilityRule pd)]
    (or (empty? rules)
        (let [m (.getMonthValue date)]
          (every? (fn [rule]
                    (let [months (:urpx/month rule)]
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
      :urpx.resolved/tier-upper-bound-operator Ref}

   For non-tiered ledgers, :tiers has a single entry with no tier-* keys.
   For block-tiered ledgers (e.g. CPAU E-1), :tiers carries every tier sorted
   by tier-number — the caller chooses based on cumulative billing-period usage."
  ([rate-plan zdt] (resolve-prices rate-plan zdt nil))
  ([rate-plan ^ZonedDateTime zdt opts]
   (let [version       (:urpx/hasRatePlanVersion rate-plan)
         plan-elements (:urpx/hasPlanElements version)
         seasons       (:urpx/hasSeasons plan-elements)
         seasons-by-id (into {} (map (juxt :jsonld/id identity)) seasons)
         billing       (:urpx/hasBillingModel plan-elements)
         ledgers       (:urpx/hasLedger billing)
         tou-schedules (:urpx/hasTOUSchedule billing)
         tiers-by-num  (service-tiers-by-number billing)
         prices        (:urpx/hasPrices version)
         holiday?      (or (:holiday? opts)
                           (holidays/derive-predicate rate-plan))

         date          (.toLocalDate zdt)
         local-time    (.toLocalTime zdt)
         dow           (.getDayOfWeek zdt)

         season        (season-of seasons date)
         tou-period    (find-tou-period tou-schedules (:jsonld/id season)
                                        date local-time dow holiday?)
         period-num    (:urpx/touPeriodNumber tou-period)

         pd->prices    (active-prices-by-pd prices date)
         pds           (:urpx/hasPriceDefinition billing)
         pds-by-ledger (group-by #(get-in % [:urpx/referencesLedger :jsonld/id]) pds)
         mi-index      (mi-by-id plan-elements)

         ledger-results
         (vec
          (for [ledger ledgers
                :let [ledger-pds (->> (pds-by-ledger (:jsonld/id ledger))
                                      (filter #(and (pd-active-at-tou? % period-num)
                                                    (pd-active-in-month? % date)
                                                    (pd-active-in-season? % seasons-by-id date))))
                      tier-entries
                      (->> ledger-pds
                           (mapcat (fn [pd]
                                     (for [[price _] (pd->prices (:jsonld/id pd))]
                                       (merge
                                        {:urpx.resolved/price-definition-id (:jsonld/id pd)
                                         :urpx.resolved/price-id            (:jsonld/id price)
                                         :urpx.resolved/price-name          (:urpx/name price)
                                         :urpx.resolved/unit-price          (:urpx/unitPrice price)}
                                        (tier-info pd price tiers-by-num)))))
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
  [modifier plan-id]
  (or (nil? plan-id)
      (let [version (:urpx/hasRatePlanModifierVersion modifier)
            refs    (:urpx/applicableToRatePlan version)]
        (boolean (some #(= plan-id (:jsonld/id %)) refs)))))

(defn- modifier-ledger-results
  "Build a vector of LedgerResult entries for `modifier` at `query-date`.
   Modifier ledgers don't go through TOU/season/serviceTier machinery; every
   active Price for each PD is surfaced under :urpx.resolved/tiers so the
   caller can pick (matrix-style modifiers like CPAU E-HRA expose all 16
   prices and rely on caller selection)."
  [modifier ^LocalDate query-date]
  (let [version    (:urpx/hasRatePlanModifierVersion modifier)
        plan-elts  (:urpx/hasPlanElements version)
        billing    (:urpx/hasBillingModel plan-elts)
        ledgers    (:urpx/hasLedger billing)
        prices     (:urpx/hasPrices version)
        pd->prices (all-active-prices-by-pd prices query-date)
        pds        (:urpx/hasPriceDefinition billing)
        pds-by-ledger (group-by #(get-in % [:urpx/referencesLedger :jsonld/id]) pds)
        mi-index   (mi-by-id plan-elts)]
    (vec
     (for [ledger ledgers
           :let [tier-entries
                 (->> (pds-by-ledger (:jsonld/id ledger))
                      (mapcat (fn [pd]
                                (for [[price _] (pd->prices (:jsonld/id pd))]
                                  {:urpx.resolved/price-definition-id (:jsonld/id pd)
                                   :urpx.resolved/price-id            (:jsonld/id price)
                                   :urpx.resolved/price-name          (:urpx/name price)
                                   :urpx.resolved/unit-price          (:urpx/unitPrice price)})))
                      vec)]
           :when (seq tier-entries)]
       (merge {:urpx.resolved/ledger-id   (:jsonld/id ledger)
               :urpx.resolved/ledger-name (:urpx/name ledger)
               :urpx.resolved/ledger-type (:urpx/ledgerType ledger)
               :urpx.resolved/tiers       tier-entries}
              (ledger-metric-info ledger mi-index))))))

(defn resolve-modifier-prices
  "Resolve every per-energy unit price that `modifier` (a coerced
   urpx:RatePlanModifier) contributes at `zdt`.

   Returns the same shape as `resolve-prices` minus the season/TOU keys
   (modifier ledgers are flat add-ons): {:urpx.resolved/ledgers [...]}.

   For matrix-style modifiers (e.g. CPAU E-HRA — a 4x4 matrix of reserve
   level x generation tier driven by external state), every applicable
   Price for each PriceDefinition is surfaced under :urpx.resolved/tiers.
   The caller is responsible for selecting the active value because the
   URPX condition vocabulary (operator + label conditionValue) is not
   yet specified in a way that's programmatically interpretable. Filter
   the returned :tiers vector on :urpx.resolved/price-id."
  [modifier ^ZonedDateTime zdt]
  {:urpx.resolved/ledgers (modifier-ledger-results modifier (.toLocalDate zdt))})

(defn resolve-prices-with-modifiers
  "Resolve prices for `rate-plan` at `zdt` and append ledger results for
   each modifier in `modifiers` whose :urpx/applicableToRatePlan list
   includes the base plan's @id. Modifiers whose target lists don't include
   the plan are silently skipped.

   Returns the `resolve-prices` shape with :urpx.resolved/ledgers extended
   by the modifier ledgers."
  ([rate-plan modifiers ^ZonedDateTime zdt] (resolve-prices-with-modifiers rate-plan modifiers zdt nil))
  ([rate-plan modifiers ^ZonedDateTime zdt opts]
   (let [base       (resolve-prices rate-plan zdt opts)
         plan-id    (:jsonld/id rate-plan)
         date       (.toLocalDate zdt)
         extra      (mapcat #(when (modifier-applies-to? % plan-id)
                               (modifier-ledger-results % date))
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
   a single $/kWh number, this is the marginal rate at zero baseline usage."
  [resolved]
  (reduce
   (fn [^java.math.BigDecimal acc ledger]
     (if (and (= "urpx:energy"
                 (get-in ledger [:urpx.resolved/measurement-kind :jsonld/id]))
              (seq (:urpx.resolved/tiers ledger)))
       (.add acc ^java.math.BigDecimal
             (:urpx.resolved/unit-price (first (:urpx.resolved/tiers ledger))))
       acc))
   0M
   (:urpx.resolved/ledgers resolved)))

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

  Day types honored: urpx:allDays, urpx:weekday, urpx:weekend, urpx:holiday.
  Holiday matching requires the caller to pass a `:holiday?` predicate
  (LocalDate -> bool); without it, urpx:holiday brackets never match."
  (:import [java.time DayOfWeek LocalDate LocalTime ZonedDateTime]))

;; ---------------------------------------------------------------------------
;; Day-type matching
;; ---------------------------------------------------------------------------

(def ^:private weekday-set
  #{DayOfWeek/MONDAY DayOfWeek/TUESDAY DayOfWeek/WEDNESDAY
    DayOfWeek/THURSDAY DayOfWeek/FRIDAY})

(def ^:private weekend-set
  #{DayOfWeek/SATURDAY DayOfWeek/SUNDAY})

(defn- day-matches?
  "True if `day-type-id` (the :jsonld/id of a urpx:DayType reference) matches
   the given date.

   `holiday?` is an optional (LocalDate -> bool) predicate. A plain set of
   LocalDates works because sets are functions. Unknown day types match
   permissively (true) so plans using vocabulary outside our supported set
   still resolve."
  [day-type-id ^LocalDate date ^DayOfWeek dow holiday?]
  (case day-type-id
    "urpx:allDays" true
    "urpx:weekday" (boolean (weekday-set dow))
    "urpx:weekend" (boolean (weekend-set dow))
    "urpx:holiday" (boolean (and holiday? (holiday? date)))
    true))

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
   day-of-week, and local-time. Walks every tier's periods (the tier hierarchy
   is for classification — what matters for resolution is the bracket match)."
  [tou-schedule season-id ^LocalDate date ^LocalTime t ^DayOfWeek dow holiday?]
  (when tou-schedule
    (some
     (fn [period]
       (when (some
              (fn [bracket]
                (and (= season-id (get-in bracket [:urpx/appliesInSeason :jsonld/id]))
                     (day-matches? (get-in bracket [:urpx/dayType :jsonld/id]) date dow holiday?)
                     (time-in-bracket? t bracket)))
              (:urpx/hasTimeBracket period))
         period))
     (mapcat :urpx/hasTOUPeriod (:urpx/hasTOUTier tou-schedule)))))

;; ---------------------------------------------------------------------------
;; Active-price lookup
;; ---------------------------------------------------------------------------

(defn- not-after?
  [^LocalDate a ^LocalDate b]
  (not (.isAfter a b)))

(defn- active-prices-by-pd
  "Build a map from PriceDefinition id to the active Price (the one with the
   most recent effective start-date that is <= `query-date`).

   Effective start-date for a Price is its own :urpx/startDate, falling back
   to its enclosing PriceSet's :urpx/startDate when present."
  [prices ^LocalDate query-date]
  (let [pairs (concat
               ;; Flat top-level prices
               (keep (fn [p]
                       (when-let [sd (:urpx/startDate p)]
                         (when (not-after? sd query-date) [p sd])))
                     (:urpx/hasPrice prices))
               ;; Prices nested under PriceSets (PriceSet startDate scopes the set)
               (mapcat (fn [ps]
                         (let [sd (:urpx/startDate ps)]
                           (when (and sd (not-after? sd query-date))
                             (map (fn [p] [p (or (:urpx/startDate p) sd)])
                                  (:urpx/hasPrice ps)))))
                       (:urpx/hasPriceSet prices)))]
    (reduce (fn [acc [price sd]]
              (let [pd-id (get-in price [:urpx/referencesPriceDefinition :jsonld/id])
                    [_ existing-sd] (acc pd-id)]
                (if (or (nil? existing-sd) (.isAfter sd existing-sd))
                  (assoc acc pd-id [price sd])
                  acc)))
            {} pairs)))

;; ---------------------------------------------------------------------------
;; Service-tier lookup (block tiers, e.g. CPAU E-1 baseline / over-baseline)
;; ---------------------------------------------------------------------------

(defn- service-tiers-by-number
  "Index ServiceTier entities (from BillingModel.hasServiceTier) by their
   :urpx/serviceTierNumber. Empty map when the plan is not block-tiered."
  [billing-model]
  (into {} (map (juxt :urpx/serviceTierNumber identity))
        (:urpx/hasServiceTier billing-model)))

(defn- tier-info
  "Pull tier metadata from a PriceDefinition + matching ServiceTier into the
   resolved-entry shape. Returns an empty map when the PD is not block-tiered."
  [pd service-tier]
  (cond-> {}
    (:urpx/serviceTierNumber pd)
    (assoc :urpx.resolved/tier-number (:urpx/serviceTierNumber pd))

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
                    (:urpx/upperBoundOperator service-tier))))))

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

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn resolve-prices
  "Resolve all per-energy unit prices that apply to `rate-plan` at `zdt`
   (a java.time.ZonedDateTime in the rate plan's local timezone).

   Optional `opts` map:
     :holiday?  predicate `(LocalDate -> bool)` used to decide whether the
                query date hits a urpx:holiday day-type bracket. A plain set
                of LocalDates works (sets are functions). When omitted,
                urpx:holiday brackets never match.

   Examples of `:holiday?`:

     ;; Static set — quickest to set up:
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
     {:urpx.resolved/ledger-id    String
      :urpx.resolved/ledger-name  String
      :urpx.resolved/ledger-type  Ref
      :urpx.resolved/tiers        [TierResult]}

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
         billing       (:urpx/hasBillingModel plan-elements)
         ledgers       (:urpx/hasLedger billing)
         tou-schedule  (:urpx/hasTOUSchedule billing)
         tiers-by-num  (service-tiers-by-number billing)
         prices        (:urpx/hasPrices version)
         holiday?      (:holiday? opts)

         date          (.toLocalDate zdt)
         local-time    (.toLocalTime zdt)
         dow           (.getDayOfWeek zdt)

         season        (season-of seasons date)
         tou-period    (find-tou-period tou-schedule (:jsonld/id season)
                                        date local-time dow holiday?)
         period-num    (:urpx/touPeriodNumber tou-period)

         pd->price     (active-prices-by-pd prices date)

         ledger-results
         (vec
          (for [ledger ledgers
                :let [tier-entries
                      (->> (:urpx/hasPriceDefinition ledger)
                           (filter #(pd-active-at-tou? % period-num))
                           (keep (fn [pd]
                                   (when-let [[price _] (pd->price (:jsonld/id pd))]
                                     (merge
                                      {:urpx.resolved/price-definition-id (:jsonld/id pd)
                                       :urpx.resolved/price-id            (:jsonld/id price)
                                       :urpx.resolved/price-name          (:urpx/name price)
                                       :urpx.resolved/unit-price          (:urpx/unitPrice price)}
                                      (tier-info pd (tiers-by-num (:urpx/serviceTierNumber pd)))))))
                           (sort-by #(or (:urpx.resolved/tier-number %) 0))
                           vec)]
                :when (seq tier-entries)]
            {:urpx.resolved/ledger-id   (:jsonld/id ledger)
             :urpx.resolved/ledger-name (:urpx/name ledger)
             :urpx.resolved/ledger-type (:urpx/ledgerType ledger)
             :urpx.resolved/tiers       tier-entries}))]

     {:urpx.resolved/season-name       (:urpx/seasonName season)
      :urpx.resolved/tou-period-name   (:urpx/name tou-period)
      :urpx.resolved/tou-period-number period-num
      :urpx.resolved/ledgers           ledger-results})))

(defn marginal-unit-rate
  "Sum the unit prices across all ledgers, picking the LOWEST tier in each
   block-tiered ledger as the marginal rate at the start of a billing period.

   Useful for price signaling: when you don't know a customer's usage but want
   a single $/kWh number, this is the marginal rate at zero baseline usage."
  [resolved]
  (reduce
   (fn [^java.math.BigDecimal acc ledger]
     (if-let [first-tier (first (:urpx.resolved/tiers ledger))]
       (.add acc ^java.math.BigDecimal (:urpx.resolved/unit-price first-tier))
       acc))
   0M
   (:urpx.resolved/ledgers resolved)))

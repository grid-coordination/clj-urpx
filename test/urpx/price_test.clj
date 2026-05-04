(ns urpx.price-test
  (:require [clojure.test :refer [deftest is testing]]
            [urpx.coerce :as coerce]
            [urpx.core :as core]
            [urpx.emit :as emit]
            [urpx.price :as price]
            [urpx.test-fixtures :as fixtures])
  (:import [java.math BigDecimal]
           [java.time ZonedDateTime ZoneId]))

(def la-zone (ZoneId/of "America/Los_Angeles"))

(defn- zdt
  ([y mo d h] (zdt y mo d h 0))
  ([y mo d h mi] (ZonedDateTime/of y mo d h mi 0 0 la-zone)))

(defn- ledger-by-name [resolved name]
  (some #(when (= name (:urpx.resolved/ledger-name %)) %)
        (:urpx.resolved/ledgers resolved)))

;; ---------------------------------------------------------------------------
;; PG&E E-TOU-C — simple summer/winter peak/off-peak
;; ---------------------------------------------------------------------------

(deftest e-tou-c-summer-peak
  (let [plan (fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")
        ;; July 15, 2026 at 17:00 — Summer (Jun-Sep), peak hour (16:00-21:00)
        r (price/resolve-prices plan (zdt 2026 7 15 17))]
    (is (= "Summer" (:urpx.resolved/season-name r)))
    (is (= 1 (:urpx.resolved/tou-period-number r))
        "TOU period 1 = Summer Peak Period")))

(deftest e-tou-c-winter-off-peak
  (let [plan (fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")
        ;; January 15, 2026 at 12:00 — Winter, off-peak
        r (price/resolve-prices plan (zdt 2026 1 15 12))]
    (is (= "Winter" (:urpx.resolved/season-name r)))
    (is (= 4 (:urpx.resolved/tou-period-number r))
        "TOU period 4 = Winter Off-Peak Period")))

(deftest resolved-ledgers-carry-metric-input-info
  (testing "URPX-046: each resolved Ledger surfaces its MetricInput-derived
            type, measurementKind, and unitOfMeasure so consumers can
            distinguish energy ledgers from per-meter / per-household
            service-charge ledgers."
    (let [plan (fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")
          ;; April includes the Climate Credit ledger (per URPX-axn fix)
          r (price/resolve-prices plan (zdt 2026 4 15 17))
          energy (ledger-by-name r "Energy Supply Prices")
          credit (ledger-by-name r "California Climate Credit")]
      (is (some? energy))
      (is (= "urpx:measuredMetricInput"
             (get-in energy [:urpx.resolved/metric-input-type :jsonld/id])))
      (is (= "urpx:energy"
             (get-in energy [:urpx.resolved/measurement-kind :jsonld/id])))
      (is (= "urpx:kWh"
             (get-in energy [:urpx.resolved/unit-of-measure :jsonld/id])))
      (testing "profile-metric ledgers (e.g. household-count) carry the type
                but no measurementKind"
        (is (some? credit))
        (is (= "urpx:profileMetricInput"
               (get-in credit [:urpx.resolved/metric-input-type :jsonld/id])))
        (is (nil? (get-in credit [:urpx.resolved/measurement-kind :jsonld/id])))))))

(deftest marginal-unit-rate-excludes-non-energy-ledgers
  (testing "URPX-046: marginal-unit-rate must exclude ledgers whose
            MetricInput is not energy-shaped (urpx:profileMetricInput,
            non-energy measurementKind, or no measurementKind)."
    (let [plan (fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")
          ;; April resolution carries the Climate Credit ledger (-36.18 per
          ;; household). Without the energy filter, marginal sum would be
          ;; dominated by the credit's magnitude.
          april (price/resolve-prices plan (zdt 2026 4 15 17))
          rate  (price/marginal-unit-rate april)]
      (is (instance? java.math.BigDecimal rate))
      ;; Sanity: a residential $/kWh for E-TOU-C is in the [0, 1] range.
      ;; Pre-fix this was ~-35.88; post-fix it's ~0.29.
      (is (pos? rate))
      (is (< rate (BigDecimal. "1.0"))
          "marginal $/kWh from energy-only ledgers should be a sane rate"))))

(deftest e-tou-c-climate-credit-month-gated
  (testing "URPX-axn: PriceDefinition.hasApplicabilityRule.month gates resolution.
            E-TOU-C's Climate Credit PD is restricted to April + October; it must
            NOT appear in July resolution and MUST appear in April/October."
    (let [plan (fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")
          july    (price/resolve-prices plan (zdt 2026 7 15 17))
          april   (price/resolve-prices plan (zdt 2026 4 15 17))
          october (price/resolve-prices plan (zdt 2026 10 15 17))]
      (is (nil? (ledger-by-name july "California Climate Credit"))
          "July is outside the [April, October] applicability window")
      (is (some? (ledger-by-name april "California Climate Credit"))
          "April is in the window")
      (is (some? (ledger-by-name october "California Climate Credit"))
          "October is in the window"))))

(deftest e-tou-c-off-peak-wraps-midnight
  (testing "off-peak bracket 21:00-00:00 wraps midnight; both 22:00 and 06:00 hit off-peak"
    (let [plan (fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")
          late  (price/resolve-prices plan (zdt 2026 7 15 22))
          early (price/resolve-prices plan (zdt 2026 7 15 6))]
      (is (= 3 (:urpx.resolved/tou-period-number late)))
      (is (= 3 (:urpx.resolved/tou-period-number early))))))

;; ---------------------------------------------------------------------------
;; PG&E E-1 — block-tiered CIA ledger
;; ---------------------------------------------------------------------------

(deftest e-1-cia-block-tiers-surface-all-prices
  (testing "Conservation Incentive Adjustment ledger surfaces all six tier-Prices,
            sorted by serviceTierNumber"
    (let [plan (fixtures/load-rate-plan "pge-e-1-2026-03-01")
          r (price/resolve-prices plan (zdt 2026 8 1 12))
          cia (ledger-by-name r "Conservation Incentive Adjustment")
          tiers (:urpx.resolved/tiers cia)]
      (is (some? cia))
      (is (= 6 (count tiers)) "tiers 1..6 (Summer t1/t2/t2c, Winter t1/t2/t2c)")
      (is (= [1 2 3 4 5 6] (mapv :urpx.resolved/tier-number tiers)))
      (let [t1 (first tiers)]
        (is (= "pge:pd-cia" (:urpx.resolved/price-definition-id t1))
            "all six tiers share the single CIA PriceDefinition")
        (is (= (BigDecimal. "-0.04052") (:urpx.resolved/unit-price t1)))
        (is (= (BigDecimal. "0") (:urpx.resolved/tier-lower-bound t1)))
        (is (= (BigDecimal. "13.5") (:urpx.resolved/tier-upper-bound t1)))
        (is (some? (:urpx.resolved/tier-lower-bound-operator t1)))
        (is (some? (:urpx.resolved/tier-upper-bound-operator t1)))))))

(deftest e-1-base-charge-skipped-from-instant-resolution
  (testing "the daily base-charge PriceDefinition has hasCalculationMethod set,
            so its Prices are excluded from per-instant resolution"
    (let [plan (fixtures/load-rate-plan "pge-e-1-2026-03-01")
          r (price/resolve-prices plan (zdt 2026 8 1 12))
          names (set (map :urpx.resolved/ledger-name (:urpx.resolved/ledgers r)))]
      (is (not (contains? names "Base Services Charge"))))))

(deftest e-1-marginal-rate-uses-lowest-tier
  (let [plan (fixtures/load-rate-plan "pge-e-1-2026-03-01")
        r (price/resolve-prices plan (zdt 2026 8 1 12))
        rate (price/marginal-unit-rate r)]
    (is (instance? BigDecimal rate))
    ;; Tier-1 entries from CIA + the flat ledgers; we don't pin the exact value
    ;; (it's a sum of many components) but we verify it's positive and reasonable.
    (is (pos? rate))))

;; ---------------------------------------------------------------------------
;; PG&E E-TOU-D — dayType + holiday-aware brackets
;; ---------------------------------------------------------------------------
;;
;; E-TOU-D bracket structure (from the v0.2.0 fixture):
;;   1 Summer Peak       weekdayDays 17:00-20:00, includeHolidays:false
;;   2 Winter Peak       weekdayDays 17:00-20:00, includeHolidays:false
;;   3 Summer Off-Peak   weekdayDays 00-17 + 20-00 + holiday-only override; weekendDays 00-00
;;   4 Winter Off-Peak   same shape as Summer Off-Peak

(deftest e-tou-d-weekday-evening-is-summer-peak
  (testing "Wed 2026-07-15 18:00 — Summer Peak (weekdayDays + includeHolidays:false)"
    (let [plan (fixtures/load-rate-plan "pge-e-tou-d-2026-03-27")
          r (price/resolve-prices plan (zdt 2026 7 15 18) {:holiday? (constantly false)})]
      (is (= 1 (:urpx.resolved/tou-period-number r))))))

(deftest e-tou-d-weekend-evening-is-summer-off-peak
  (testing "Sat 2026-07-04 18:00 — weekendDays bracket wins; not Summer Peak"
    (let [plan (fixtures/load-rate-plan "pge-e-tou-d-2026-03-27")
          r (price/resolve-prices plan (zdt 2026 7 4 18) {:holiday? (constantly false)})]
      (is (= 3 (:urpx.resolved/tou-period-number r))))))

(deftest e-tou-d-holiday-on-weekday-is-off-peak
  (testing "Mon 2027-01-18 (treated as a holiday via :holiday? opts) 18:00 —
            peak window is suppressed by includeHolidays:false; the off-peak
            holiday-window override (includeHolidays:true + includeNonHolidays:false)
            takes over"
    (let [plan      (fixtures/load-rate-plan "pge-e-tou-d-2026-03-27")
          mlk       (java.time.LocalDate/of 2027 1 18)
          when-zdt  (zdt 2027 1 18 18)
          r         (price/resolve-prices plan when-zdt {:holiday? #{mlk}})]
      (is (= 4 (:urpx.resolved/tou-period-number r))
          "Winter Off-Peak (4) — explicit holiday suppresses Winter Peak (2)"))))

(deftest e-tou-d-mlk-not-in-embedded-calendar
  (testing "Mon 2027-01-18 (MLK Day) 18:00 with no :holiday? — embedded
            HolidayCalendar is auto-derived but does NOT list MLK, so this
            Monday matches the peak window like any other weekday."
    (let [plan (fixtures/load-rate-plan "pge-e-tou-d-2026-03-27")
          r    (price/resolve-prices plan (zdt 2027 1 18 18))]
      (is (= 2 (:urpx.resolved/tou-period-number r))))))

(deftest e-tou-d-derived-calendar-recognizes-memorial-day
  (testing "Mon 2026-05-25 (Memorial Day) 18:00 — embedded HolidayCalendar
            derives Memorial Day, so the Winter peak window is suppressed"
    (let [plan (fixtures/load-rate-plan "pge-e-tou-d-2026-03-27")
          r    (price/resolve-prices plan (zdt 2026 5 25 18))]
      (is (= 4 (:urpx.resolved/tou-period-number r))
          "Winter Off-Peak (4) — derived holiday suppresses Winter Peak (2)"))))

(deftest e-tou-d-derived-calendar-recognizes-labor-day
  (testing "Mon 2026-09-07 (Labor Day) 18:00 — Summer; derived calendar
            suppresses Summer Peak in favor of Summer Off-Peak"
    (let [plan (fixtures/load-rate-plan "pge-e-tou-d-2026-03-27")
          r    (price/resolve-prices plan (zdt 2026 9 7 18))]
      (is (= 3 (:urpx.resolved/tou-period-number r))))))

(deftest e-tou-d-explicit-holiday-overrides-derived
  (testing "Explicit :holiday? in opts overrides the embedded calendar"
    (let [plan (fixtures/load-rate-plan "pge-e-tou-d-2026-03-27")
          r    (price/resolve-prices plan (zdt 2026 5 25 18)
                                     {:holiday? (constantly false)})]
      (is (= 2 (:urpx.resolved/tou-period-number r))
          "Winter Peak (2) — explicit :holiday? wins"))))

(deftest no-embedded-calendar-keeps-fallback-behavior
  (testing "Plans without urpx:HolidayCalendar still get the historical
            'every date is a non-holiday' fallback when :holiday? is omitted."
    (let [plan (fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")
          r    (price/resolve-prices plan (zdt 2026 7 15 17))]
      (is (= 1 (:urpx.resolved/tou-period-number r))))))

(deftest unknown-day-type-fails-loud
  (testing "An out-of-vocabulary dayType returns no match (no silent fallthrough)."
    (let [plan-base (fixtures/load-rate-plan "pge-e-tou-d-2026-03-27")
          plan (update-in plan-base
                          [:urpx/hasRatePlanVersion
                           :urpx/hasPlanElements
                           :urpx/hasBillingModel
                           :urpx/hasTOUSchedule 0
                           :urpx/hasTOUTier 0
                           :urpx/hasTOUPeriod 0
                           :urpx/hasTimeBracket]
                          (fn [bs]
                            (mapv #(assoc % :urpx/dayType {:jsonld/id "urpx:nonsense"}) bs)))
          r (price/resolve-prices plan (zdt 2026 7 15 18))]
      (is (not= 1 (:urpx.resolved/tou-period-number r))))))

;; ---------------------------------------------------------------------------
;; Modifier resolution — sibling Vintage PCIA in @graph
;; ---------------------------------------------------------------------------

(deftest vintage-pcia-modifier-surfaces-all-vintage-prices
  (testing "PG&E E-1's bundled Vintaged-PCIA modifier exposes all vintage-year
            tiers; every Price shares the single Vintage-PCIA PriceDefinition"
    (let [modifier (fixtures/load-modifier "pge-e-1-2026-03-01")
          {:keys [urpx.resolved/ledgers]} (price/resolve-modifier-prices modifier (zdt 2026 7 15 12))]
      (is (= 1 (count ledgers)))
      (let [tiers (:urpx.resolved/tiers (first ledgers))]
        (is (pos? (count tiers)) "modifier surfaces every vintage-year tier")
        (is (every? #(= "pge:pd-vintage-pcia"
                        (:urpx.resolved/price-definition-id %))
                    tiers)
            "every vintage tier references the single Vintage-PCIA PD")))))

(deftest resolve-with-modifiers-appends-modifier-ledger
  (testing "Base PG&E E-1 ledgers + Vintage-PCIA ledger appear in the combined output"
    (let [plan (fixtures/load-rate-plan "pge-e-1-2026-03-01")
          modifier  (fixtures/load-modifier "pge-e-1-2026-03-01")
          when-zdt (zdt 2026 7 15 12)
          base (price/resolve-prices plan when-zdt)
          comb (price/resolve-prices-with-modifiers plan [modifier] when-zdt)]
      (is (= (inc (count (:urpx.resolved/ledgers base)))
             (count (:urpx.resolved/ledgers comb))))
      (is (some #(= "Vintaged Power Charge Indifference Adjustment"
                    (:urpx.resolved/ledger-name %))
                (:urpx.resolved/ledgers comb))))))

(deftest modifier-not-applicable-to-plan-is-skipped
  (testing "A modifier whose applicableToRatePlan does not list the base plan
            @id contributes no ledgers"
    (let [plan (fixtures/load-rate-plan "pge-e-1-2026-03-01")
          mod-base (fixtures/load-modifier "pge-e-1-2026-03-01")
          modifier (assoc-in mod-base
                             [:urpx/hasRatePlanModifierVersion :urpx/applicableToRatePlan]
                             [{:jsonld/id "urpx:not-this-plan"}])
          when-zdt (zdt 2026 7 15 12)
          base (price/resolve-prices plan when-zdt)
          comb (price/resolve-prices-with-modifiers plan [modifier] when-zdt)]
      (is (= (count (:urpx.resolved/ledgers base))
             (count (:urpx.resolved/ledgers comb)))))))

;; ---------------------------------------------------------------------------
;; Issue #4: modifier PDs gated by their own hasTOUSchedule (touPeriodNumber)
;; ---------------------------------------------------------------------------

(defn- own-tou-modifier-fixture
  "Modifier with own urpx:hasTOUSchedule (one TOUPeriod, midnight-6am, allDays,
   touPeriodNumber=1) and a single PD/Price gated by touPeriodNumber=1.
   Mirrors smud:pev-credit's shape."
  []
  {:jsonld/type "urpx:RatePlanModifier"
   :jsonld/id "test:overnight-credit"
   :urpx/name "Test overnight credit (midnight-6am)"
   :urpx/initialEffectiveDate (java.time.LocalDate/of 2026 1 1)
   :urpx/hasRatePlanModifierVersion
   {:jsonld/type "urpx:RatePlanModifierVersion"
    :urpx/effectiveDateTime (java.time.LocalDateTime/of 2026 1 1 0 0)
    :urpx/priceCurrency "USD"
    :urpx/applicableToRatePlan [{:jsonld/id "test:base-plan"}]
    :urpx/hasPlanElements
    {:jsonld/type "urpx:PlanElements"
     :urpx/hasMetricInput
     [{:jsonld/type "urpx:MetricInput"
       :jsonld/id "test:mi-energy"
       :urpx/name "kWh"
       :urpx/metricInputType {:jsonld/id "urpx:measuredMetricInput"}
       :urpx/hasMetricSpecification
       {:jsonld/type "urpx:MetricSpecification"
        :urpx/measurementKind {:jsonld/id "urpx:energy"}
        :urpx/unitOfMeasure {:jsonld/id "urpx:kWh"}
        :urpx/flowDirection {:jsonld/id "urpx:forward"}}}]
     :urpx/hasBillingModel
     {:jsonld/type "urpx:BillingModel"
      :urpx/hasTOUSchedule
      [{:jsonld/type "urpx:TOUSchedule"
        :urpx/hasTOUTier
        [{:jsonld/type "urpx:TOUTier"
          :urpx/touTierNumber 1
          :urpx/hasTOUPeriod
          [{:jsonld/type "urpx:TOUPeriod"
            :urpx/name "Overnight (midnight-6am)"
            :urpx/touPeriodNumber 1
            :urpx/hasTimeBracket
            [{:jsonld/type "urpx:TimeBracket"
              :urpx/startTime (java.time.LocalTime/of 0 0)
              :urpx/endTime (java.time.LocalTime/of 6 0)
              :urpx/dayType {:jsonld/id "urpx:allDays"}}]}]}]}]
      :urpx/hasLedger
      [{:jsonld/type "urpx:Ledger"
        :jsonld/id "test:ledger-credit"
        :urpx/name "Overnight credit"
        :urpx/ledgerType {:jsonld/id "urpx:bundledLedger"}
        :urpx/referencesMetricInput {:jsonld/id "test:mi-energy"}}]
      :urpx/hasPriceDefinition
      [{:jsonld/type "urpx:PriceDefinition"
        :jsonld/id "test:pd-overnight-credit"
        :urpx/name "Overnight credit (touPeriodNumber=1)"
        :urpx/touPeriodNumber 1
        :urpx/referencesLedger {:jsonld/id "test:ledger-credit"}}]}}
    :urpx/hasPrices
    {:jsonld/type "urpx:Prices"
     :urpx/hasPrice
     [{:jsonld/type "urpx:Price"
       :jsonld/id "test:price-overnight-credit"
       :urpx/name "Overnight credit"
       :urpx/unitPrice (BigDecimal. "-0.0150")
       :urpx/referencesPriceDefinition {:jsonld/id "test:pd-overnight-credit"}
       :urpx/startDate (java.time.LocalDate/of 2026 1 1)
       :urpx/touPeriodNumber 1}]}}})

(deftest modifier-own-tou-gates-out-of-window
  (testing "Modifier with own midnight-6am TOUSchedule does NOT surface its
            tier at 17:00 — the modifier's own TOU period (1) does not match
            because 17:00 is outside the bracket (issue #4 case 1)."
    (let [{:keys [urpx.resolved/ledgers]}
          (price/resolve-modifier-prices (own-tou-modifier-fixture) (zdt 2026 7 15 17))]
      (is (empty? ledgers)
          "no ledgers surface when probe instant is outside the modifier's window"))))

(deftest modifier-own-tou-fires-in-window
  (testing "Same modifier surfaces its tier at 03:00 — inside the
            midnight-6am bracket (issue #4 case 1, positive case)."
    (let [{:keys [urpx.resolved/ledgers]}
          (price/resolve-modifier-prices (own-tou-modifier-fixture) (zdt 2026 7 15 3))
          tiers (:urpx.resolved/tiers (first ledgers))]
      (is (= 1 (count ledgers)))
      (is (= 1 (count tiers)))
      (is (= (BigDecimal. "-0.0150") (:urpx.resolved/unit-price (first tiers)))))))

(defn- base-tou-rate-plan-fixture
  "Minimal RatePlan with two TOU periods (Peak=1 16-21, Off-Peak=2 21-16)
   and one ledger that has NO PriceDefinition — exists only to give
   resolve-prices a TOU resolution context. The price-server modifier path
   tests don't care about base prices firing, only about
   :urpx.resolved/tou-period-number being set."
  []
  {:jsonld/type "urpx:RatePlan"
   :jsonld/id "test:base-plan"
   :urpx/ratePlanUniqueIdentifier "TEST-BASE"
   :urpx/name "Test base plan"
   :urpx/initialEffectiveDate (java.time.LocalDate/of 2026 1 1)
   :urpx/hasRatePlanVersion
   {:jsonld/type "urpx:RatePlanVersion"
    :urpx/effectiveDateTime (java.time.LocalDateTime/of 2026 1 1 0 0)
    :urpx/priceCurrency "USD"
    :urpx/hasPlanElements
    {:jsonld/type "urpx:PlanElements"
     :urpx/hasMetricInput
     [{:jsonld/type "urpx:MetricInput"
       :jsonld/id "test:base-mi-energy"
       :urpx/name "kWh"
       :urpx/metricInputType {:jsonld/id "urpx:measuredMetricInput"}
       :urpx/hasMetricSpecification
       {:jsonld/type "urpx:MetricSpecification"
        :urpx/measurementKind {:jsonld/id "urpx:energy"}
        :urpx/unitOfMeasure {:jsonld/id "urpx:kWh"}}}]
     :urpx/hasBillingModel
     {:jsonld/type "urpx:BillingModel"
      :urpx/hasTOUSchedule
      [{:jsonld/type "urpx:TOUSchedule"
        :urpx/hasTOUTier
        [{:jsonld/type "urpx:TOUTier"
          :urpx/touTierNumber 1
          :urpx/hasTOUPeriod
          [{:jsonld/type "urpx:TOUPeriod"
            :urpx/name "Peak"
            :urpx/touPeriodNumber 1
            :urpx/hasTimeBracket
            [{:jsonld/type "urpx:TimeBracket"
              :urpx/startTime (java.time.LocalTime/of 16 0)
              :urpx/endTime (java.time.LocalTime/of 21 0)
              :urpx/dayType {:jsonld/id "urpx:allDays"}}]}
           {:jsonld/type "urpx:TOUPeriod"
            :urpx/name "Off-Peak"
            :urpx/touPeriodNumber 2
            :urpx/hasTimeBracket
            [{:jsonld/type "urpx:TimeBracket"
              :urpx/startTime (java.time.LocalTime/of 21 0)
              :urpx/endTime (java.time.LocalTime/of 16 0)
              :urpx/dayType {:jsonld/id "urpx:allDays"}}]}]}]}]
      :urpx/hasLedger []
      :urpx/hasPriceDefinition []}}
    :urpx/hasPrices {:jsonld/type "urpx:Prices" :urpx/hasPrice []}}})

(defn- rides-base-tou-modifier-fixture
  "Modifier with NO own urpx:hasTOUSchedule and a single PD/Price gated by
   touPeriodNumber=2 — meant to fire only when the BASE plan resolves to
   period 2. Mirrors smud:cpp's Mid-Peak/Off-Peak discount shape."
  []
  {:jsonld/type "urpx:RatePlanModifier"
   :jsonld/id "test:rides-base"
   :urpx/name "Test rides-base modifier"
   :urpx/initialEffectiveDate (java.time.LocalDate/of 2026 1 1)
   :urpx/hasRatePlanModifierVersion
   {:jsonld/type "urpx:RatePlanModifierVersion"
    :urpx/effectiveDateTime (java.time.LocalDateTime/of 2026 1 1 0 0)
    :urpx/priceCurrency "USD"
    :urpx/applicableToRatePlan [{:jsonld/id "test:base-plan"}]
    :urpx/hasPlanElements
    {:jsonld/type "urpx:PlanElements"
     :urpx/hasMetricInput
     [{:jsonld/type "urpx:MetricInput"
       :jsonld/id "test:rb-mi-energy"
       :urpx/name "kWh"
       :urpx/metricInputType {:jsonld/id "urpx:measuredMetricInput"}
       :urpx/hasMetricSpecification
       {:jsonld/type "urpx:MetricSpecification"
        :urpx/measurementKind {:jsonld/id "urpx:energy"}
        :urpx/unitOfMeasure {:jsonld/id "urpx:kWh"}}}]
     :urpx/hasBillingModel
     {:jsonld/type "urpx:BillingModel"
      :urpx/hasLedger
      [{:jsonld/type "urpx:Ledger"
        :jsonld/id "test:rb-ledger"
        :urpx/name "Off-Peak discount"
        :urpx/ledgerType {:jsonld/id "urpx:bundledLedger"}
        :urpx/referencesMetricInput {:jsonld/id "test:rb-mi-energy"}}]
      :urpx/hasPriceDefinition
      [{:jsonld/type "urpx:PriceDefinition"
        :jsonld/id "test:rb-pd"
        :urpx/name "Off-Peak discount (touPeriodNumber=2 in base)"
        :urpx/touPeriodNumber 2
        :urpx/referencesLedger {:jsonld/id "test:rb-ledger"}}]}}
    :urpx/hasPrices
    {:jsonld/type "urpx:Prices"
     :urpx/hasPrice
     [{:jsonld/type "urpx:Price"
       :jsonld/id "test:rb-price"
       :urpx/name "Off-Peak discount"
       :urpx/unitPrice (BigDecimal. "-0.0200")
       :urpx/referencesPriceDefinition {:jsonld/id "test:rb-pd"}
       :urpx/startDate (java.time.LocalDate/of 2026 1 1)
       :urpx/touPeriodNumber 2}]}}})

(deftest modifier-without-own-tou-rides-base-period-no-fire
  (testing "Modifier with no own TOUSchedule and PD touPeriodNumber=2 does
            NOT fire when the base resolves to period 1 (issue #4 case 2)."
    (let [plan (base-tou-rate-plan-fixture)
          modifier (rides-base-tou-modifier-fixture)
          ;; 17:00 is in base Peak window (16-21), touPeriodNumber=1
          comb (price/resolve-prices-with-modifiers plan [modifier] (zdt 2026 7 15 17))]
      (is (= 1 (:urpx.resolved/tou-period-number comb)))
      (is (empty? (:urpx.resolved/ledgers comb))
          "modifier PD gated to base period 2 does not surface at base period 1"))))

(deftest modifier-without-own-tou-rides-base-period-fires
  (testing "Same modifier DOES fire when base resolves to period 2 (issue #4
            case 2, positive case)."
    (let [plan (base-tou-rate-plan-fixture)
          modifier (rides-base-tou-modifier-fixture)
          ;; 12:00 is in base Off-Peak window (wraps 21-16), touPeriodNumber=2
          comb (price/resolve-prices-with-modifiers plan [modifier] (zdt 2026 7 15 12))
          ledger (ledger-by-name comb "Off-Peak discount")]
      (is (= 2 (:urpx.resolved/tou-period-number comb)))
      (is (some? ledger))
      (is (= (BigDecimal. "-0.0200")
             (:urpx.resolved/unit-price (first (:urpx.resolved/tiers ledger))))))))

;; ---------------------------------------------------------------------------
;; Issue #5 (1): surface urpx:hasDynamicPricing on resolved tier entries
;; ---------------------------------------------------------------------------

(defn- event-charge-modifier-fixture
  "Modifier carrying a single event-driven Price (CPP-style): touPeriodNumber-less
   PD, Price with urpx:hasDynamicPricing pointing at criticalPeakPricing /
   eventTriggeredUpdates, plus a PriceUpdateRule prose description.
   Mirrors smud:cpp's Event Charge shape and the urpx-org B6-PDP example."
  []
  {:jsonld/type "urpx:RatePlanModifier"
   :jsonld/id "test:event-charge"
   :urpx/name "Test event-driven charge"
   :urpx/initialEffectiveDate (java.time.LocalDate/of 2026 1 1)
   :urpx/hasRatePlanModifierVersion
   {:jsonld/type "urpx:RatePlanModifierVersion"
    :urpx/effectiveDateTime (java.time.LocalDateTime/of 2026 1 1 0 0)
    :urpx/priceCurrency "USD"
    :urpx/applicableToRatePlan [{:jsonld/id "test:base-plan"}]
    :urpx/hasPlanElements
    {:jsonld/type "urpx:PlanElements"
     :urpx/hasMetricInput
     [{:jsonld/type "urpx:MetricInput"
       :jsonld/id "test:ec-mi-energy"
       :urpx/name "kWh"
       :urpx/metricInputType {:jsonld/id "urpx:measuredMetricInput"}
       :urpx/hasMetricSpecification
       {:jsonld/type "urpx:MetricSpecification"
        :urpx/measurementKind {:jsonld/id "urpx:energy"}
        :urpx/unitOfMeasure {:jsonld/id "urpx:kWh"}}}]
     :urpx/hasBillingModel
     {:jsonld/type "urpx:BillingModel"
      :urpx/hasLedger
      [{:jsonld/type "urpx:Ledger"
        :jsonld/id "test:ec-ledger"
        :urpx/name "Event Charge"
        :urpx/ledgerType {:jsonld/id "urpx:bundledLedger"}
        :urpx/referencesMetricInput {:jsonld/id "test:ec-mi-energy"}}]
      :urpx/hasPriceDefinition
      [{:jsonld/type "urpx:PriceDefinition"
        :jsonld/id "test:ec-pd"
        :urpx/name "Event Charge (consumer-gated)"
        :urpx/referencesLedger {:jsonld/id "test:ec-ledger"}}]}}
    :urpx/hasPrices
    {:jsonld/type "urpx:Prices"
     :urpx/hasPrice
     [{:jsonld/type "urpx:Price"
       :jsonld/id "test:ec-price"
       :urpx/name "Event Charge"
       :urpx/unitPrice (BigDecimal. "0.5000")
       :urpx/referencesPriceDefinition {:jsonld/id "test:ec-pd"}
       :urpx/startDate (java.time.LocalDate/of 2026 1 1)
       :urpx/hasDynamicPricing
       {:jsonld/type "urpx:DynamicPricing"
        :urpx/dynamicPricingType {:jsonld/id "urpx:criticalPeakPricing"}
        :urpx/updateFrequency {:jsonld/id "urpx:eventTriggeredUpdates"}
        :urpx/hasPriceUpdateRule
        {:jsonld/type "urpx:PriceUpdateRule"
         :urpx/description "CPP events called by ops; day-ahead announcement; summer-only."}}}]}}})

(deftest dynamic-pricing-surfaces-on-modifier-tier
  (testing "Event-driven modifier Price exposes :urpx.resolved/dynamic-pricing
            on its tier entry, with the DynamicPricing payload passed through
            verbatim — type, updateFrequency, and PriceUpdateRule reachable."
    (let [plan (base-tou-rate-plan-fixture)
          modifier (event-charge-modifier-fixture)
          comb (price/resolve-prices-with-modifiers plan [modifier] (zdt 2026 7 15 17))
          tier (first (:urpx.resolved/tiers (ledger-by-name comb "Event Charge")))]
      (is (some? tier))
      (is (some? (:urpx.resolved/dynamic-pricing tier))
          "tier carries :urpx.resolved/dynamic-pricing")
      (is (= "urpx:criticalPeakPricing"
             (get-in tier [:urpx.resolved/dynamic-pricing
                           :urpx/dynamicPricingType :jsonld/id])))
      (is (= "urpx:eventTriggeredUpdates"
             (get-in tier [:urpx.resolved/dynamic-pricing
                           :urpx/updateFrequency :jsonld/id])))
      (is (some? (get-in tier [:urpx.resolved/dynamic-pricing
                               :urpx/hasPriceUpdateRule :urpx/description]))
          "nested PriceUpdateRule passes through with its prose description"))))

(deftest dynamic-pricing-absent-on-non-event-tier
  (testing "Modifier Prices without hasDynamicPricing have no
            :urpx.resolved/dynamic-pricing key on their tier entry — additive
            metadata, no impact on consumers that don't ask for it."
    (let [{:keys [urpx.resolved/ledgers]}
          (price/resolve-modifier-prices (own-tou-modifier-fixture) (zdt 2026 7 15 3))
          tier (first (:urpx.resolved/tiers (first ledgers)))]
      (is (some? tier))
      (is (not (contains? tier :urpx.resolved/dynamic-pricing))))))

;; ---------------------------------------------------------------------------
;; Multiple tier-Prices sharing a single PriceDefinition (PG&E CIA pattern)
;; ---------------------------------------------------------------------------

(defn- shared-pd-tier-fixture
  "A coerced RatePlan (in the v0.2.0 shape) with one ledger backed by a
   single PriceDefinition and three Prices, each carrying its own
   :urpx/serviceTierNumber. Used to exercise the (PD, tier-number) keying
   in active-prices-by-pd independently of any vendored fixture's evolution."
  []
  {:jsonld/type "urpx:RatePlan"
   :jsonld/id   "urpx:test-shared-pd"
   :urpx/ratePlanUniqueIdentifier "TEST-SHARED-PD"
   :urpx/name "Shared-PD tier-stratified test plan"
   :urpx/initialEffectiveDate (java.time.LocalDate/of 2026 1 1)
   :urpx/hasRatePlanVersion
   {:jsonld/type "urpx:RatePlanVersion"
    :urpx/effectiveDateTime (java.time.LocalDateTime/of 2026 1 1 0 0)
    :urpx/priceCurrency "USD"
    :urpx/hasPlanElements
    {:jsonld/type "urpx:PlanElements"
     :urpx/hasMetricInput
     [{:jsonld/type "urpx:MetricInput"
       :jsonld/id "test:mi-energy"
       :urpx/name "kWh"
       :urpx/metricInputType {:jsonld/id "urpx:measuredMetricInput"}}]
     :urpx/hasBillingModel
     {:jsonld/type "urpx:BillingModel"
      :urpx/hasServiceTiers
      [{:jsonld/type "urpx:ServiceTiers"
        :urpx/referencesMetricInput [{:jsonld/id "test:mi-energy"}]
        :urpx/hasServiceTier
        [{:jsonld/type "urpx:ServiceTier"
          :urpx/serviceTierNumber 1
          :urpx/name "Tier 1 (within baseline)"
          :urpx/lowerBound (BigDecimal. "0")
          :urpx/upperBound (BigDecimal. "100")
          :urpx/lowerBoundOperator {:jsonld/id "urpx:greaterThanOrEqualOperator"}
          :urpx/upperBoundOperator {:jsonld/id "urpx:lessThanOrEqualOperator"}}
         {:jsonld/type "urpx:ServiceTier"
          :urpx/serviceTierNumber 2
          :urpx/name "Tier 2 (101-400% of baseline)"
          :urpx/lowerBound (BigDecimal. "100")
          :urpx/upperBound (BigDecimal. "400")
          :urpx/lowerBoundOperator {:jsonld/id "urpx:greaterThanOperator"}
          :urpx/upperBoundOperator {:jsonld/id "urpx:lessThanOrEqualOperator"}}
         {:jsonld/type "urpx:ServiceTier"
          :urpx/serviceTierNumber 3
          :urpx/name "Tier 2 Continued (over 400%)"
          :urpx/lowerBound (BigDecimal. "400")
          :urpx/upperBound (BigDecimal. "999999")
          :urpx/lowerBoundOperator {:jsonld/id "urpx:greaterThanOperator"}
          :urpx/upperBoundOperator {:jsonld/id "urpx:lessThanOrEqualOperator"}}]}]
      :urpx/hasLedger
      [{:jsonld/type "urpx:Ledger"
        :jsonld/id "test:ledger-cia"
        :urpx/name "Conservation Incentive Adjustment"
        :urpx/ledgerType {:jsonld/id "urpx:bundledLedger"}}]
      :urpx/hasPriceDefinition
      [{:jsonld/type "urpx:PriceDefinition"
        :jsonld/id "test:pd-cia"
        :urpx/name "CIA"
        :urpx/referencesLedger {:jsonld/id "test:ledger-cia"}}]}}
    :urpx/hasPrices
    {:jsonld/type "urpx:Prices"
     :urpx/hasPrice
     [{:jsonld/type "urpx:Price"
       :jsonld/id "test:price-cia-t1"
       :urpx/name "CIA Tier 1"
       :urpx/unitPrice (BigDecimal. "-0.04052")
       :urpx/referencesPriceDefinition {:jsonld/id "test:pd-cia"}
       :urpx/startDate (java.time.LocalDate/of 2026 1 1)
       :urpx/serviceTierNumber 1}
      {:jsonld/type "urpx:Price"
       :jsonld/id "test:price-cia-t2"
       :urpx/name "CIA Tier 2"
       :urpx/unitPrice (BigDecimal. "0.04089")
       :urpx/referencesPriceDefinition {:jsonld/id "test:pd-cia"}
       :urpx/startDate (java.time.LocalDate/of 2026 1 1)
       :urpx/serviceTierNumber 2}
      {:jsonld/type "urpx:Price"
       :jsonld/id "test:price-cia-t2c"
       :urpx/name "CIA Tier 2 Continued"
       :urpx/unitPrice (BigDecimal. "0.04089")
       :urpx/referencesPriceDefinition {:jsonld/id "test:pd-cia"}
       :urpx/startDate (java.time.LocalDate/of 2026 1 1)
       :urpx/serviceTierNumber 3}]}}})

(deftest shared-pd-surfaces-every-tier-price
  (testing "All three Prices sharing one PD surface as separate tier entries,
            sorted by tier-number, each with the matching ServiceTier metadata"
    (let [r (price/resolve-prices (shared-pd-tier-fixture) (zdt 2026 6 15 12))
          ledger (ledger-by-name r "Conservation Incentive Adjustment")
          tiers (:urpx.resolved/tiers ledger)]
      (is (some? ledger))
      (is (= 3 (count tiers)))
      (is (= [1 2 3] (mapv :urpx.resolved/tier-number tiers)))
      (is (= [(BigDecimal. "-0.04052")
              (BigDecimal. "0.04089")
              (BigDecimal. "0.04089")]
             (mapv :urpx.resolved/unit-price tiers)))
      (is (= ["Tier 1 (within baseline)"
              "Tier 2 (101-400% of baseline)"
              "Tier 2 Continued (over 400%)"]
             (mapv :urpx.resolved/tier-name tiers))))))

(deftest temporal-versioning-still-picks-latest
  (testing "Two Prices on the same (PD, serviceTierNumber) with different
            startDates collapse to the latest"
    (let [base (shared-pd-tier-fixture)
          plan (update-in base
                          [:urpx/hasRatePlanVersion :urpx/hasPrices :urpx/hasPrice]
                          conj
                          {:jsonld/type "urpx:Price"
                           :jsonld/id "test:price-cia-t1-v2"
                           :urpx/name "CIA Tier 1 (revised)"
                           :urpx/unitPrice (BigDecimal. "-0.05000")
                           :urpx/referencesPriceDefinition {:jsonld/id "test:pd-cia"}
                           :urpx/startDate (java.time.LocalDate/of 2026 4 1)
                           :urpx/serviceTierNumber 1})
          r (price/resolve-prices plan (zdt 2026 6 15 12))
          tiers (:urpx.resolved/tiers (ledger-by-name r "Conservation Incentive Adjustment"))
          tier-1 (some #(when (= 1 (:urpx.resolved/tier-number %)) %) tiers)]
      (is (= 3 (count tiers)))
      (is (= "test:price-cia-t1-v2" (:urpx.resolved/price-id tier-1)))
      (is (= (BigDecimal. "-0.05000") (:urpx.resolved/unit-price tier-1))))))

(deftest dynamic-pricing-surfaces-on-base-plan-tier
  (testing "A base RatePlan Price with hasDynamicPricing also surfaces
            :urpx.resolved/dynamic-pricing — uniform with the modifier path,
            since the SHACL Price shape allows hasDynamicPricing on any Price."
    (let [base (shared-pd-tier-fixture)
          ;; Tag tier-2 as dynamically-priced (hypothetical RTP overlay)
          plan (update-in base
                          [:urpx/hasRatePlanVersion :urpx/hasPrices :urpx/hasPrice]
                          (fn [prices]
                            (mapv (fn [p]
                                    (if (= "test:price-cia-t2" (:jsonld/id p))
                                      (assoc p :urpx/hasDynamicPricing
                                             {:jsonld/type "urpx:DynamicPricing"
                                              :urpx/dynamicPricingType {:jsonld/id "urpx:realTimePricing"}})
                                      p))
                                  prices)))
          r (price/resolve-prices plan (zdt 2026 6 15 12))
          tiers (:urpx.resolved/tiers (ledger-by-name r "Conservation Incentive Adjustment"))
          t1 (some #(when (= 1 (:urpx.resolved/tier-number %)) %) tiers)
          t2 (some #(when (= 2 (:urpx.resolved/tier-number %)) %) tiers)]
      (is (not (contains? t1 :urpx.resolved/dynamic-pricing)))
      (is (= "urpx:realTimePricing"
             (get-in t2 [:urpx.resolved/dynamic-pricing
                         :urpx/dynamicPricingType :jsonld/id]))))))

;; ---------------------------------------------------------------------------
;; Non-TOU seasonal pricing — PriceDefinition.appliesInSeason → Season.month
;; (URPX-2nk; CPAU E-2 shape)
;; ---------------------------------------------------------------------------

(defn- non-tou-seasonal-fixture
  "A coerced RatePlan modeled on CPAU Schedule E-2 (effective 2025-07-01):
   non-TOU two-season pricing where PriceDefinitions point at Seasons via
   urpx:appliesInSeason and the seasonal partition lives entirely in the
   Season month lists (no TOUSchedule indirection). Used to exercise the
   PD.appliesInSeason → Season.month month-gating path independently of
   any vendored fixture's evolution."
  []
  {:jsonld/type "urpx:RatePlan"
   :jsonld/id   "urpx:test-cpau-e-2"
   :urpx/ratePlanUniqueIdentifier "TEST-CPAU-E-2"
   :urpx/name "Non-TOU two-season test plan"
   :urpx/initialEffectiveDate (java.time.LocalDate/of 2025 7 1)
   :urpx/hasRatePlanVersion
   {:jsonld/type "urpx:RatePlanVersion"
    :urpx/effectiveDateTime (java.time.LocalDateTime/of 2025 7 1 0 0)
    :urpx/priceCurrency "USD"
    :urpx/hasPlanElements
    {:jsonld/type "urpx:PlanElements"
     :urpx/hasSeasons
     [{:jsonld/type "urpx:Season"
       :jsonld/id "test:season-summer"
       :urpx/seasonName "Summer"
       :urpx/yearlyRecurrence true
       :urpx/month [5 6 7 8 9 10]}
      {:jsonld/type "urpx:Season"
       :jsonld/id "test:season-winter"
       :urpx/seasonName "Winter"
       :urpx/yearlyRecurrence true
       :urpx/month [11 12 1 2 3 4]}]
     :urpx/hasMetricInput
     [{:jsonld/type "urpx:MetricInput"
       :jsonld/id "test:mi-energy"
       :urpx/name "kWh"
       :urpx/metricInputType {:jsonld/id "urpx:profileMetricInput"}
       :urpx/hasMetricSpecification
       {:jsonld/type "urpx:MetricSpecification"
        :urpx/measurementKind {:jsonld/id "urpx:energy"}
        :urpx/unitOfMeasure   {:jsonld/id "urpx:kWh"}}}]
     :urpx/hasBillingModel
     {:jsonld/type "urpx:BillingModel"
      :urpx/hasLedger
      [{:jsonld/type "urpx:Ledger"
        :jsonld/id "test:ledger-commodity"
        :urpx/name "Commodity"
        :urpx/ledgerType {:jsonld/id "urpx:supplyLedger"}
        :urpx/referencesMetricInput [{:jsonld/id "test:mi-energy"}]}
       {:jsonld/type "urpx:Ledger"
        :jsonld/id "test:ledger-distribution"
        :urpx/name "Distribution"
        :urpx/ledgerType {:jsonld/id "urpx:distributionLedger"}
        :urpx/referencesMetricInput [{:jsonld/id "test:mi-energy"}]}
       {:jsonld/type "urpx:Ledger"
        :jsonld/id "test:ledger-public-benefits"
        :urpx/name "Public Benefits"
        :urpx/ledgerType {:jsonld/id "urpx:bundledLedger"}
        :urpx/referencesMetricInput [{:jsonld/id "test:mi-energy"}]}]
      :urpx/hasPriceDefinition
      [{:jsonld/type "urpx:PriceDefinition"
        :jsonld/id "test:pd-commodity-summer"
        :urpx/name "Commodity Summer"
        :urpx/appliesInSeason [{:jsonld/id "test:season-summer"}]
        :urpx/referencesLedger {:jsonld/id "test:ledger-commodity"}}
       {:jsonld/type "urpx:PriceDefinition"
        :jsonld/id "test:pd-commodity-winter"
        :urpx/name "Commodity Winter"
        :urpx/appliesInSeason [{:jsonld/id "test:season-winter"}]
        :urpx/referencesLedger {:jsonld/id "test:ledger-commodity"}}
       {:jsonld/type "urpx:PriceDefinition"
        :jsonld/id "test:pd-distribution-summer"
        :urpx/name "Distribution Summer"
        :urpx/appliesInSeason [{:jsonld/id "test:season-summer"}]
        :urpx/referencesLedger {:jsonld/id "test:ledger-distribution"}}
       {:jsonld/type "urpx:PriceDefinition"
        :jsonld/id "test:pd-distribution-winter"
        :urpx/name "Distribution Winter"
        :urpx/appliesInSeason [{:jsonld/id "test:season-winter"}]
        :urpx/referencesLedger {:jsonld/id "test:ledger-distribution"}}
       {:jsonld/type "urpx:PriceDefinition"
        :jsonld/id "test:pd-public-benefits-summer"
        :urpx/name "Public Benefits Summer"
        :urpx/appliesInSeason [{:jsonld/id "test:season-summer"}]
        :urpx/referencesLedger {:jsonld/id "test:ledger-public-benefits"}}
       {:jsonld/type "urpx:PriceDefinition"
        :jsonld/id "test:pd-public-benefits-winter"
        :urpx/name "Public Benefits Winter"
        :urpx/appliesInSeason [{:jsonld/id "test:season-winter"}]
        :urpx/referencesLedger {:jsonld/id "test:ledger-public-benefits"}}]}}
    :urpx/hasPrices
    {:jsonld/type "urpx:Prices"
     ;; CPAU E-2 wraps its Prices in a PriceSet that DOES NOT carry a
     ;; urpx:startDate (per-Price startDates are the date floor). Keep that
     ;; shape here so the fixture exercises URPX-453 alongside URPX-2nk.
     :urpx/hasPriceSet
     [{:jsonld/type "urpx:PriceSet"
       :jsonld/id "test:price-set"
       :urpx/hasPrice
       [{:jsonld/type "urpx:Price" :jsonld/id "test:price-commodity-summer"
         :urpx/name "Commodity Summer" :urpx/unitPrice (BigDecimal. "0.15075")
         :urpx/referencesPriceDefinition {:jsonld/id "test:pd-commodity-summer"}
         :urpx/startDate (java.time.LocalDate/of 2025 7 1)}
        {:jsonld/type "urpx:Price" :jsonld/id "test:price-commodity-winter"
         :urpx/name "Commodity Winter" :urpx/unitPrice (BigDecimal. "0.09334")
         :urpx/referencesPriceDefinition {:jsonld/id "test:pd-commodity-winter"}
         :urpx/startDate (java.time.LocalDate/of 2025 7 1)}
        {:jsonld/type "urpx:Price" :jsonld/id "test:price-distribution-summer"
         :urpx/name "Distribution Summer" :urpx/unitPrice (BigDecimal. "0.10806")
         :urpx/referencesPriceDefinition {:jsonld/id "test:pd-distribution-summer"}
         :urpx/startDate (java.time.LocalDate/of 2025 7 1)}
        {:jsonld/type "urpx:Price" :jsonld/id "test:price-distribution-winter"
         :urpx/name "Distribution Winter" :urpx/unitPrice (BigDecimal. "0.07352")
         :urpx/referencesPriceDefinition {:jsonld/id "test:pd-distribution-winter"}
         :urpx/startDate (java.time.LocalDate/of 2025 7 1)}
        {:jsonld/type "urpx:Price" :jsonld/id "test:price-public-benefits-summer"
         :urpx/name "Public Benefits Summer" :urpx/unitPrice (BigDecimal. "0.00604")
         :urpx/referencesPriceDefinition {:jsonld/id "test:pd-public-benefits-summer"}
         :urpx/startDate (java.time.LocalDate/of 2025 7 1)}
        {:jsonld/type "urpx:Price" :jsonld/id "test:price-public-benefits-winter"
         :urpx/name "Public Benefits Winter" :urpx/unitPrice (BigDecimal. "0.00604")
         :urpx/referencesPriceDefinition {:jsonld/id "test:pd-public-benefits-winter"}
         :urpx/startDate (java.time.LocalDate/of 2025 7 1)}]}]}}})

(deftest non-tou-seasonal-summer-resolves-summer-pds-only
  (testing "URPX-2nk: PDs with appliesInSeason → Summer match in July; Winter
            PDs are gated out via Season.month, not via hasApplicabilityRule."
    (let [plan (non-tou-seasonal-fixture)
          r    (price/resolve-prices plan (zdt 2026 7 15 17))
          ledgers (:urpx.resolved/ledgers r)
          price-ids (set (mapcat #(map :urpx.resolved/price-id (:urpx.resolved/tiers %)) ledgers))]
      (is (= 3 (count ledgers)) "one tier-entry-bearing ledger per energy component")
      (is (contains? price-ids "test:price-commodity-summer"))
      (is (contains? price-ids "test:price-distribution-summer"))
      (is (contains? price-ids "test:price-public-benefits-summer"))
      (is (not (contains? price-ids "test:price-commodity-winter")))
      (is (not (contains? price-ids "test:price-distribution-winter")))
      (is (not (contains? price-ids "test:price-public-benefits-winter"))))))

(deftest non-tou-seasonal-winter-resolves-winter-pds-only
  (testing "URPX-2nk: January (in the Winter season month list) resolves only
            the Winter-keyed PDs."
    (let [plan (non-tou-seasonal-fixture)
          r    (price/resolve-prices plan (zdt 2026 1 15 12))
          price-ids (set (mapcat #(map :urpx.resolved/price-id (:urpx.resolved/tiers %))
                                 (:urpx.resolved/ledgers r)))]
      (is (contains? price-ids "test:price-commodity-winter"))
      (is (contains? price-ids "test:price-distribution-winter"))
      (is (contains? price-ids "test:price-public-benefits-winter"))
      (is (not (contains? price-ids "test:price-commodity-summer")))
      (is (not (contains? price-ids "test:price-distribution-summer"))))))

(deftest non-tou-seasonal-marginal-rate-matches-summer-total
  (testing "URPX-2nk: marginal-unit-rate sums the three Summer energy ledgers
            to the published $0.26485/kWh CPAU E-2 Summer total."
    (let [plan (non-tou-seasonal-fixture)
          r    (price/resolve-prices plan (zdt 2026 7 15 17))
          rate (price/marginal-unit-rate r)]
      (is (= (BigDecimal. "0.26485") rate)))))

(deftest non-tou-seasonal-marginal-rate-matches-winter-total
  (let [plan (non-tou-seasonal-fixture)
        r    (price/resolve-prices plan (zdt 2026 1 15 12))
        rate (price/marginal-unit-rate r)]
    (is (= (BigDecimal. "0.17290") rate))))

;; ---------------------------------------------------------------------------
;; ProfileAlternatives + PriceSetAlternatives selection (URPX-vlf)
;; ---------------------------------------------------------------------------
;; Models the PG&E E-1 BSC (Basic Service Charge) income-tier pattern: three
;; mutually-exclusive alternatives — CARE, FERA, Standard — exposing
;; different BSC unit prices for the same PriceDefinition. The caller drives
;; selection explicitly via :profile-alternatives opts; programmatic
;; ConditionExpression evaluation against a customer profile is a future
;; enhancement.

(def ^:private trivial-condition-expression
  {:jsonld/type "urpx:ConditionExpression"
   :urpx/rootNode {:jsonld/type "urpx:ComparisonNode"
                   :urpx/operator {:jsonld/id "urpx:eq"}
                   :urpx/leftOperand  {:jsonld/type "urpx:CustomOperandNode"
                                       :urpx/customConditionProperty "incomeTier"}
                   :urpx/rightOperand {:jsonld/type "urpx:CustomOperandNode"
                                       :urpx/stringValue "placeholder"}}})

(defn- bsc-fixture
  "A coerced RatePlan with one Ledger (BSC) and three customer-profile
   alternatives (CARE / FERA / Standard) under a single PriceSetAlternatives.
   Each alternative's hasPrice references the same PD with a different unit
   price."
  []
  {:jsonld/type "urpx:RatePlan"
   :urpx/ratePlanUniqueIdentifier "TEST-BSC"
   :urpx/name "BSC income-tier alternatives test"
   :urpx/initialEffectiveDate (java.time.LocalDate/of 2026 1 1)
   :urpx/hasRatePlanVersion
   {:jsonld/type "urpx:RatePlanVersion"
    :urpx/effectiveDateTime (java.time.LocalDateTime/of 2026 1 1 0 0)
    :urpx/hasPlanElements
    {:jsonld/type "urpx:PlanElements"
     :urpx/hasMetricInput
     [{:jsonld/type "urpx:MetricInput"
       :jsonld/id "test:mi-energy"
       :urpx/name "kWh"
       :urpx/metricInputType {:jsonld/id "urpx:profileMetricInput"}
       :urpx/hasMetricSpecification
       {:jsonld/type "urpx:MetricSpecification"
        :urpx/measurementKind {:jsonld/id "urpx:energy"}
        :urpx/unitOfMeasure   {:jsonld/id "urpx:kWh"}}}]
     :urpx/hasProfileAlternatives
     [{:jsonld/type "urpx:ProfileAlternatives"
       :jsonld/id "test:bsc-income-tier"
       :urpx/name "BSC Income Tier"
       :urpx/hasAlternativeDimensions
       {:jsonld/type "urpx:AlternativeDimensions"
        :urpx/hasAlternativeDimension
        [{:jsonld/type "urpx:AlternativeDimension"
          :urpx/dimensionDisplayName "Income Tier"
          :urpx/dimensionProperty "https://schema.urpx.org/incomeTier"
          :urpx/hasPossibleValues [{:jsonld/id "urpx:incomeTierCare"}
                                   {:jsonld/id "urpx:incomeTierFera"}
                                   {:jsonld/id "urpx:incomeTierStandard"}]}]}
       :urpx/hasProfileAlternative
       [{:jsonld/type "urpx:ProfileAlternative"
         :jsonld/id "test:bsc-care"
         :urpx/name "CARE"
         :urpx/hasConditionExpression trivial-condition-expression}
        {:jsonld/type "urpx:ProfileAlternative"
         :jsonld/id "test:bsc-fera"
         :urpx/name "FERA"
         :urpx/hasConditionExpression trivial-condition-expression}
        {:jsonld/type "urpx:ProfileAlternative"
         :jsonld/id "test:bsc-standard"
         :urpx/name "Standard"
         :urpx/hasConditionExpression trivial-condition-expression}]}]
     :urpx/hasBillingModel
     {:jsonld/type "urpx:BillingModel"
      :urpx/hasLedger
      [{:jsonld/type "urpx:Ledger"
        :jsonld/id "test:ledger-bsc"
        :urpx/name "Basic Service Charge"
        :urpx/ledgerType {:jsonld/id "urpx:profileMetricLedger"}
        :urpx/referencesMetricInput [{:jsonld/id "test:mi-energy"}]}]
      :urpx/hasPriceDefinition
      [{:jsonld/type "urpx:PriceDefinition"
        :jsonld/id "test:pd-bsc"
        :urpx/name "BSC"
        :urpx/referencesLedger {:jsonld/id "test:ledger-bsc"}}]}}
    :urpx/hasPrices
    {:jsonld/type "urpx:Prices"
     :urpx/hasPriceSetAlternatives
     [{:jsonld/type "urpx:PriceSetAlternatives"
       :jsonld/id "test:bsc-prices"
       :urpx/name "BSC Prices"
       :urpx/referencesProfileAlternatives {:jsonld/id "test:bsc-income-tier"}
       :urpx/hasPriceSetAlternative
       [{:jsonld/type "urpx:PriceSetAlternative"
         :jsonld/id "test:bsc-psa-care"
         :urpx/alternativeLabel "CARE"
         :urpx/referencesProfileAlternative {:jsonld/id "test:bsc-care"}
         :urpx/hasPrice
         [{:jsonld/type "urpx:Price"
           :jsonld/id "test:bsc-price-care"
           :urpx/unitPrice (BigDecimal. "0.05")
           :urpx/referencesPriceDefinition {:jsonld/id "test:pd-bsc"}
           :urpx/startDate (java.time.LocalDate/of 2026 1 1)}]}
        {:jsonld/type "urpx:PriceSetAlternative"
         :jsonld/id "test:bsc-psa-fera"
         :urpx/alternativeLabel "FERA"
         :urpx/referencesProfileAlternative {:jsonld/id "test:bsc-fera"}
         :urpx/hasPrice
         [{:jsonld/type "urpx:Price"
           :jsonld/id "test:bsc-price-fera"
           :urpx/unitPrice (BigDecimal. "0.10")
           :urpx/referencesPriceDefinition {:jsonld/id "test:pd-bsc"}
           :urpx/startDate (java.time.LocalDate/of 2026 1 1)}]}
        {:jsonld/type "urpx:PriceSetAlternative"
         :jsonld/id "test:bsc-psa-standard"
         :urpx/alternativeLabel "Standard"
         :urpx/referencesProfileAlternative {:jsonld/id "test:bsc-standard"}
         :urpx/hasPrice
         [{:jsonld/type "urpx:Price"
           :jsonld/id "test:bsc-price-standard"
           :urpx/unitPrice (BigDecimal. "0.20")
           :urpx/referencesPriceDefinition {:jsonld/id "test:pd-bsc"}
           :urpx/startDate (java.time.LocalDate/of 2026 1 1)}]}]}]}}})

(deftest bsc-care-selection-yields-care-price
  (testing "URPX-vlf: :profile-alternatives selects the CARE alternative; only
            its BSC price surfaces"
    (let [plan (bsc-fixture)
          r (price/resolve-prices
             plan (zdt 2026 7 15 17)
             {:profile-alternatives {"test:bsc-income-tier" "test:bsc-care"}})
          tiers (:urpx.resolved/tiers (ledger-by-name r "Basic Service Charge"))]
      (is (= 1 (count tiers)))
      (is (= "test:bsc-price-care" (:urpx.resolved/price-id (first tiers))))
      (is (= (BigDecimal. "0.05")  (:urpx.resolved/unit-price (first tiers)))))))

(deftest bsc-standard-selection-yields-standard-price
  (testing "URPX-vlf: switching the selection switches the surfaced price"
    (let [plan (bsc-fixture)
          r (price/resolve-prices
             plan (zdt 2026 7 15 17)
             {:profile-alternatives {"test:bsc-income-tier" "test:bsc-standard"}})
          tiers (:urpx.resolved/tiers (ledger-by-name r "Basic Service Charge"))]
      (is (= 1 (count tiers)))
      (is (= (BigDecimal. "0.20") (:urpx.resolved/unit-price (first tiers)))))))

(deftest bsc-no-selection-skips-alternative
  (testing "URPX-vlf: without :profile-alternatives, alternatives are silently
            skipped (no BSC price surfaces). Future enhancement: programmatic
            ConditionExpression evaluation against a customer profile."
    (let [plan (bsc-fixture)
          r (price/resolve-prices plan (zdt 2026 7 15 17))]
      (is (nil? (ledger-by-name r "Basic Service Charge"))
          "ledger has no tier entries → it's omitted from the resolved output"))))

(deftest bsc-unknown-selection-skips-alternative
  (testing "URPX-vlf: a selection pointing at an unknown ProfileAlternative @id
            results in no match — the alternative is skipped silently"
    (let [plan (bsc-fixture)
          r (price/resolve-prices
             plan (zdt 2026 7 15 17)
             {:profile-alternatives {"test:bsc-income-tier" "test:bsc-nonexistent"}})]
      (is (nil? (ledger-by-name r "Basic Service Charge"))))))

(deftest bsc-fixture-round-trips-through-emit
  (testing "URPX-vlf: ProfileAlternatives + PriceSetAlternatives round-trip
            cleanly through urpx.emit/write-rate-plan + re-parse + re-coerce"
    (let [plan (bsc-fixture)
          re-coerced (-> (emit/write-rate-plan plan {:context nil})
                         java.io.StringReader.
                         core/parse-rate-plan
                         coerce/coerce-rate-plan)]
      (is (= plan re-coerced)
          "structural equivalence under emit + re-parse"))))

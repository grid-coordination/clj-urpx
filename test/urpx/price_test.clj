(ns urpx.price-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk]
            [urpx.coerce :as coerce]
            [urpx.core :as core]
            [urpx.emit :as emit]
            [urpx.index :as index]
            [urpx.schema :as schema]
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
        ;; January 15, 2026 at 12:00 — Winter, off-peak. This instant PREDATES
        ;; the filing's own 2026-03-01 version, so the result carries
        ;; :urpx.resolved/outside-version-window and marginal-unit-rate refuses.
        ;; Season and TOU period are structural questions about the plan's
        ;; shape and are still answered, which is why the mark is on the result
        ;; rather than a throw from resolve-prices.
        r (price/resolve-prices plan (zdt 2026 1 15 12))]
    (is (some? (:urpx.resolved/outside-version-window r))
        "sanity: this probe really is outside the filing's window")
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

(deftest e-1-base-charge-is-withheld-not-absent
  (testing "PG&E E-1's Base Services Charge sits behind an income-tier
            urpx:PriceSetAlternatives, so without a selection its prices are
            withheld and the ledger is marked.

            This test previously asserted the ledger was ABSENT, and attributed
            that to the PriceDefinition's urpx:hasCalculationMethod. That
            explanation stopped being true in 0.16.0, when recurring charges
            began to surface; the assertion kept passing only because the
            alternatives path was eliding the ledger for an unrelated reason.
            A test passing for a reason its own docstring does not state is
            worth as little as one that does not pass."
    (let [plan (fixtures/load-rate-plan "pge-e-1-2026-03-01")
          r (price/resolve-prices plan (zdt 2026 8 1 12))
          l (some #(when (= "Base Services Charge" (:urpx.resolved/ledger-name %)) %)
                  (:urpx.resolved/ledgers r))]
      (is (some? l) "present, so the caller can see a dimension went unchosen")
      (is (empty? (:urpx.resolved/tiers l)))
      (is (seq (:urpx.resolved/unselected-alternatives l))))))

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

(defn own-tou-modifier-fixture
  "Modifier with own urpx:hasTOUSchedule (one TOUPeriod, midnight-6am, allDays,
   touPeriodNumber=1) and a single PD/Price gated by touPeriodNumber=1.
   Mirrors smud:pev-credit's shape.

   Public because urpx.zone-test needs a modifier whose OWN schedule makes its
   result zone-sensitive, and no fixture is: zero modifier versions in either
   tree carry hasTOUSchedule. Duplicating sixty lines of document to avoid a
   test-to-test reference would be the worse trade."
  []
  {:jsonld/type "urpx:RatePlanModifier" :urpx/timezoneIdentifier "America/Los_Angeles"
   :jsonld/id "test:overnight-credit"
   :urpx/name "Test overnight credit (midnight-6am)"
   :urpx/initialEffectiveDate (java.time.LocalDate/of 2026 1 1)
   :urpx/hasRatePlanModifierVersion
   {:jsonld/type "urpx:RatePlanModifierVersion"
    :urpx/changeReason {:jsonld/id "urpx:rateCase"}
    :urpx/forRatePlanModifier {:jsonld/id "ex:parent"}
    :urpx/hasCoverageClaim {:jsonld/type "urpx:CoverageClaim" :urpx/completenessResult {:jsonld/id "urpx:complete"}}
    :urpx/serviceRole {:jsonld/id "urpx:bundledServiceRole"}
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
  {:jsonld/type "urpx:RatePlan" :urpx/timezoneIdentifier "America/Los_Angeles"
   :jsonld/id "test:base-plan"
   :urpx/ratePlanUniqueIdentifier "TEST-BASE"
   :urpx/name "Test base plan"
   :urpx/initialEffectiveDate (java.time.LocalDate/of 2026 1 1)
   :urpx/hasRatePlanVersion
   {:jsonld/type "urpx:RatePlanVersion"
    :urpx/changeReason {:jsonld/id "urpx:rateCase"}
    :urpx/forRatePlan {:jsonld/id "ex:parent"}
    :urpx/hasCoverageClaim {:jsonld/type "urpx:CoverageClaim" :urpx/completenessResult {:jsonld/id "urpx:complete"}}
    :urpx/serviceRole {:jsonld/id "urpx:bundledServiceRole"}
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
  {:jsonld/type "urpx:RatePlanModifier" :urpx/timezoneIdentifier "America/Los_Angeles"
   :jsonld/id "test:rides-base"
   :urpx/name "Test rides-base modifier"
   :urpx/initialEffectiveDate (java.time.LocalDate/of 2026 1 1)
   :urpx/hasRatePlanModifierVersion
   {:jsonld/type "urpx:RatePlanModifierVersion"
    :urpx/changeReason {:jsonld/id "urpx:rateCase"}
    :urpx/forRatePlanModifier {:jsonld/id "ex:parent"}
    :urpx/hasCoverageClaim {:jsonld/type "urpx:CoverageClaim" :urpx/completenessResult {:jsonld/id "urpx:complete"}}
    :urpx/serviceRole {:jsonld/id "urpx:bundledServiceRole"}
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
  {:jsonld/type "urpx:RatePlanModifier" :urpx/timezoneIdentifier "America/Los_Angeles"
   :jsonld/id "test:event-charge"
   :urpx/name "Test event-driven charge"
   :urpx/initialEffectiveDate (java.time.LocalDate/of 2026 1 1)
   :urpx/hasRatePlanModifierVersion
   {:jsonld/type "urpx:RatePlanModifierVersion"
    :urpx/changeReason {:jsonld/id "urpx:rateCase"}
    :urpx/forRatePlanModifier {:jsonld/id "ex:parent"}
    :urpx/hasCoverageClaim {:jsonld/type "urpx:CoverageClaim" :urpx/completenessResult {:jsonld/id "urpx:complete"}}
    :urpx/serviceRole {:jsonld/id "urpx:bundledServiceRole"}
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
  {:jsonld/type "urpx:RatePlan" :urpx/timezoneIdentifier "America/Los_Angeles"
   :jsonld/id   "urpx:test-shared-pd"
   :urpx/ratePlanUniqueIdentifier "TEST-SHARED-PD"
   :urpx/name "Shared-PD tier-stratified test plan"
   :urpx/initialEffectiveDate (java.time.LocalDate/of 2026 1 1)
   :urpx/hasRatePlanVersion
   {:jsonld/type "urpx:RatePlanVersion"
    :urpx/changeReason {:jsonld/id "urpx:rateCase"}
    :urpx/forRatePlan {:jsonld/id "ex:parent"}
    :urpx/hasCoverageClaim {:jsonld/type "urpx:CoverageClaim" :urpx/completenessResult {:jsonld/id "urpx:complete"}}
    :urpx/serviceRole {:jsonld/id "urpx:bundledServiceRole"}
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
          :urpx/hasServiceBracket
          [{:jsonld/type "urpx:ServiceBracket"
            :urpx/lowerBound (BigDecimal. "0")
            :urpx/upperBound (BigDecimal. "100")
            :urpx/lowerBoundOperator {:jsonld/id "urpx:greaterThanOrEqualOperator"}
            :urpx/upperBoundOperator {:jsonld/id "urpx:lessThanOrEqualOperator"}}]}
         {:jsonld/type "urpx:ServiceTier"
          :urpx/serviceTierNumber 2
          :urpx/name "Tier 2 (101-400% of baseline)"
          :urpx/hasServiceBracket
          [{:jsonld/type "urpx:ServiceBracket"
            :urpx/lowerBound (BigDecimal. "100")
            :urpx/upperBound (BigDecimal. "400")
            :urpx/lowerBoundOperator {:jsonld/id "urpx:greaterThanOperator"}
            :urpx/upperBoundOperator {:jsonld/id "urpx:lessThanOrEqualOperator"}}]}
         {:jsonld/type "urpx:ServiceTier"
          :urpx/serviceTierNumber 3
          :urpx/name "Tier 2 Continued (over 400%)"
          :urpx/hasServiceBracket
          [{:jsonld/type "urpx:ServiceBracket"
            :urpx/lowerBound (BigDecimal. "400")
            :urpx/lowerBoundOperator {:jsonld/id "urpx:greaterThanOperator"}}]}]}]
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
  {:jsonld/type "urpx:RatePlan" :urpx/timezoneIdentifier "America/Los_Angeles"
   :jsonld/id   "urpx:test-cpau-e-2"
   :urpx/ratePlanUniqueIdentifier "TEST-CPAU-E-2"
   :urpx/name "Non-TOU two-season test plan"
   :urpx/initialEffectiveDate (java.time.LocalDate/of 2025 7 1)
   :urpx/hasRatePlanVersion
   {:jsonld/type "urpx:RatePlanVersion"
    :urpx/changeReason {:jsonld/id "urpx:rateCase"}
    :urpx/forRatePlan {:jsonld/id "ex:parent"}
    :urpx/hasCoverageClaim {:jsonld/type "urpx:CoverageClaim" :urpx/completenessResult {:jsonld/id "urpx:complete"}}
    :urpx/serviceRole {:jsonld/id "urpx:bundledServiceRole"}
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
  {:jsonld/type "urpx:RatePlan" :urpx/timezoneIdentifier "America/Los_Angeles"
   :urpx/ratePlanUniqueIdentifier "TEST-BSC"
   :urpx/name "BSC income-tier alternatives test"
   :urpx/initialEffectiveDate (java.time.LocalDate/of 2026 1 1)
   :urpx/hasRatePlanVersion
   {:jsonld/type "urpx:RatePlanVersion"
    :urpx/changeReason {:jsonld/id "urpx:rateCase"}
    :urpx/forRatePlan {:jsonld/id "ex:parent"}
    :urpx/hasCoverageClaim {:jsonld/type "urpx:CoverageClaim" :urpx/completenessResult {:jsonld/id "urpx:complete"}}
    :urpx/serviceRole {:jsonld/id "urpx:bundledServiceRole"}
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

(deftest bsc-no-selection-marks-the-withheld-alternative
  (testing "without :profile-alternatives the alternative's prices are withheld,
            and the LEDGER now survives carrying
            :urpx.resolved/unselected-alternatives rather than disappearing.

            The earlier version of this test asserted the disappearance, in a
            docstring that described it as prices being `silently skipped`. That
            was the defect stated as the specification: the prices, the ledger
            and any evidence they existed all vanished together, so a consumer
            could not tell an unselected dimension from a plan that has none."
    (let [plan (bsc-fixture)
          r (price/resolve-prices plan (zdt 2026 7 15 17))
          l (ledger-by-name r "Basic Service Charge")]
      (is (some? l) "the ledger survives so the withholding is visible")
      (is (empty? (:urpx.resolved/tiers l)) "carrying no prices")
      (is (seq (:urpx.resolved/unselected-alternatives l))
          "and naming the definitions whose prices were withheld"))))

(deftest bsc-unknown-selection-is-refused
  (testing "a selection naming an @id the keyed container does not list is a
            caller error, and it is refused.

            This test previously asserted that the alternative was skipped
            silently, and described that as the specification, which is the
            same mistake the test above documents one case over. Compared for
            equality, a stale or mistyped id is indistinguishable from a
            deliberate choice of a sibling cell, so the whole dimension
            disappeared: here the Basic Service Charge ledger vanished
            entirely, and on PG&E E-TOU-C composed with its vintage-PCIA
            modifier the same typo returned 0.46886 where the correct id
            returns 0.49859, with nothing in the result to say so.

            The membership index that catches it is the one that already maps
            an alternative to its dimension, so the check costs nothing."
    (let [plan (bsc-fixture)]
      (is (= :unknown-profile-alternative
             (try (price/resolve-prices
                   plan (zdt 2026 7 15 17)
                   {:profile-alternatives {"test:bsc-income-tier" "test:bsc-nonexistent"}})
                  nil
                  (catch clojure.lang.ExceptionInfo e (:reason (ex-data e))))))
      (is (some? (ledger-by-name
                  (price/resolve-prices
                   plan (zdt 2026 7 15 17)
                   {:profile-alternatives {"test:bsc-income-tier" "test:bsc-care"}})
                  "Basic Service Charge"))
          "while a real cell still selects, unchanged")
      (let [l (ledger-by-name
               (price/resolve-prices
                plan (zdt 2026 7 15 17)
                {:profile-alternatives {"test:other-dimension" "test:whatever"}})
               "Basic Service Charge")]
        (is (some? l)
            "a key for a container this document does not declare is left alone,
             because one selection map is passed to a plan and to every modifier
             composed with it")
        (is (seq (:urpx.resolved/unselected-alternatives l))
            "so this dimension is simply unselected: withheld and marked, which
             is what a caller can act on, rather than refused or elided")))))

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

;; ---------------------------------------------------------------------------
;; v0.5.1 un-nesting: the version reached through forRatePlan rather than
;; nested under the plan
;; ---------------------------------------------------------------------------

(defn- un-nest
  "Split a v0.2.x coerced plan into the v0.5.1 shape: an identity shell with no
   nested version, plus a top-level snapshot pointing back via forRatePlan."
  [plan nested-key back-key]
  (let [version (nested-key plan)]
    [(dissoc plan nested-key)
      (assoc version back-key {:jsonld/id (:jsonld/id plan)})]))

(deftest un-nested-version-resolves-identically-to-nested
  (testing "a plan whose version is top-level and linked by forRatePlan
            resolves to exactly the same prices as the nested form, whether the
            snapshot arrives explicitly or through the back-pointer index"
    (let [plan     (fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")
          [shell snapshot] (un-nest plan :urpx/hasRatePlanVersion :urpx/forRatePlan)
          when-zdt (zdt 2026 7 15 17)
          nested   (price/resolve-prices plan when-zdt)
          explicit (price/resolve-prices shell when-zdt {:version snapshot})
          by-plan  (index/versions-by-plan [shell snapshot])
          indexed  (price/resolve-prices shell when-zdt {:versions-by-plan by-plan})]
      (is (seq (:urpx.resolved/ledgers nested)) "the nested baseline must be non-empty")
      (is (= nested explicit) "explicit :version must match the nested result")
      (is (= nested indexed)  "the back-pointer index must match the nested result")
      (testing "and the index really is what resolved it: the bare shell alone
                yields nothing, so the equality above is not accidental"
        (is (empty? (:urpx.resolved/ledgers (price/resolve-prices shell when-zdt))))))))

(deftest versions-by-plan-keys-on-the-back-pointer
  (testing "index/versions-by-plan finds a snapshot by the @id it points back at,
            for both the plan and the modifier back-pointers"
    (let [plan (fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")
          [shell snapshot] (un-nest plan :urpx/hasRatePlanVersion :urpx/forRatePlan)
          by-plan (index/versions-by-plan [shell snapshot])]
      (is (= [snapshot] (get by-plan (:jsonld/id plan))))
      (is (= 1 (count by-plan))))
    (let [modifier (fixtures/load-modifier "pge-e-tou-c-2026-03-01")
          [shell snapshot] (un-nest modifier :urpx/hasRatePlanModifierVersion
                                    :urpx/forRatePlanModifier)
          by-plan (index/versions-by-plan [shell snapshot])]
      (is (= [snapshot] (get by-plan (:jsonld/id modifier)))))))

(deftest un-nested-holiday-calendar-still-found
  (testing "holiday gating survives un-nesting: E-TOU-D peak is weekday-only and
            a holiday must read as off-peak through the un-nested path too"
    (let [plan (fixtures/load-rate-plan "pge-e-tou-d-2026-03-27")
          [shell snapshot] (un-nest plan :urpx/hasRatePlanVersion :urpx/forRatePlan)
          july4 (zdt 2026 7 3 18)]
      (is (= (price/resolve-prices plan july4)
             (price/resolve-prices shell july4 {:version snapshot}))))))

;; ---------------------------------------------------------------------------
;; v0.5.1 arity: the version pointers are 0..N upstream
;; ---------------------------------------------------------------------------

(deftest multiple-nested-versions-validate-and-refuse-to-guess
  (let [plan    (fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")
        version (:urpx/hasRatePlanVersion plan)
        when-zdt (zdt 2026 7 15 17)]
    (testing "a single nested snapshot keeps working unchanged, in both the map
              and the one-element vector shapes"
      (is (= (price/resolve-prices plan when-zdt)
             (price/resolve-prices (assoc plan :urpx/hasRatePlanVersion [version]) when-zdt))))
    (testing "two nested snapshots are VALID upstream (sh:minCount 0, no
              sh:maxCount) and must not be rejected by the schema"
      (let [two (assoc plan :urpx/hasRatePlanVersion [version version])]
        (is (nil? (coerce/explain schema/RatePlan two))
            "the document is conformant; rejecting it was the old defect")
        (testing "but resolution refuses to guess which one applies, rather than
                  silently picking one and reporting a confident wrong answer"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"Multiple nested version snapshots"
                                (price/resolve-prices two when-zdt))))
        (testing "and an explicitly supplied snapshot resolves it"
          (is (= (price/resolve-prices plan when-zdt)
                 (price/resolve-prices two when-zdt {:version version}))))))))

;; ---------------------------------------------------------------------------
;; A month gate stated by reference, not inline
;; ---------------------------------------------------------------------------

(deftest bare-applicability-rule-ref-still-gates-by-month
  (testing "a PriceDefinition may name its ApplicabilityRule by bare @id rather
            than inlining it, since RDF merges a reference with its definition.
            Before dereferencing, such a rule carried no urpx:month, read as
            permissive, and the PD resolved in EVERY month instead of the ones
            it names: a wrong answer rather than an error."
    (let [plan     (fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")
          version  (:urpx/hasRatePlanVersion plan)
          pds      (get-in version [:urpx/hasPlanElements :urpx/hasBillingModel
                                    :urpx/hasPriceDefinition])
          ;; the climate-credit PD is the one already month-gated inline
          gated    (some #(when (seq (:urpx/hasApplicabilityRule %)) %) pds)
          rule     (first (:urpx/hasApplicabilityRule gated))
          rule-id  "x:rule-april-only"
          ;; restate the same gate BY REFERENCE, with the definition parked
          ;; where an index can reach it
          defn'    (assoc rule :jsonld/type "urpx:ApplicabilityRule"
                          :jsonld/id rule-id
                          :urpx/month [4])
          by-ref   (assoc gated :urpx/hasApplicabilityRule [{:jsonld/id rule-id}])
          swap     (fn [v pd] (assoc-in v [:urpx/hasPlanElements :urpx/hasBillingModel
                                           :urpx/hasPriceDefinition]
                                        (mapv #(if (= (:jsonld/id %) (:jsonld/id pd)) pd %) pds)))
          april    (zdt 2026 4 15 17)
          july     (zdt 2026 7 15 17)
          names-of (fn [r] (set (map :urpx.resolved/ledger-name (:urpx.resolved/ledgers r))))]
      (is (some? gated) "the fixture must have a month-gated PD to rewrite")
      (let [plan'  (assoc plan :urpx/hasRatePlanVersion (swap version by-ref))
            idx    {rule-id defn'}
            in-apr (price/resolve-prices plan' april {:rules-by-id idx})
            in-jul (price/resolve-prices plan' july  {:rules-by-id idx})]
        (testing "the referenced rule gates April in and July out"
          (is (contains? (names-of in-apr) (:urpx/name gated)))
          (is (not (contains? (names-of in-jul) (:urpx/name gated)))))
        (testing "and without the index the reference is unresolvable, so the
                  gate is permissive: the regression this guards against"
          (is (contains? (names-of (price/resolve-prices plan' july {:rules-by-id {}}))
                         (:urpx/name gated))))))))

;; ---------------------------------------------------------------------------
;; Composition defects found by auditing the modifier path (2026-09-21)
;;
;; All three were reachable by conformant documents and invisible to the
;; corpus, because no vendored fixture happens to have the triggering shape.
;; Each test below FAILED before its fix.
;; ---------------------------------------------------------------------------

(defn- plan-elements [plan]
  (get-in plan [:urpx/hasRatePlanVersion :urpx/hasPlanElements]))

(deftest modifier-with-own-schedule-and-seasons-survives-a-holiday
  (testing "find-tou-period takes a holiday PREDICATE and applies it per
            bracket. The modifier path used to hand it the predicate's RESULT,
            so holiday-status-matches? called `true` as a function.

            It needed three things at once to bite, which is why nothing caught
            it: the modifier must carry its own urpx:hasTOUSchedule (otherwise
            find-tou-period is never called), its own urpx:hasSeasons (otherwise
            no season resolves, no bracket matches, and the holiday check is
            never reached), and the instant must fall on a holiday. Threw
            ClassCastException before the fix."
    (let [plan  (fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")
          modi  (fixtures/load-modifier "pge-e-tou-c-2026-03-01")
          pe    (plan-elements plan)
          modi' (-> modi
                    (assoc-in [:urpx/hasRatePlanModifierVersion :urpx/hasPlanElements
                               :urpx/hasBillingModel :urpx/hasTOUSchedule]
                              (get-in pe [:urpx/hasBillingModel :urpx/hasTOUSchedule]))
                    (assoc-in [:urpx/hasRatePlanModifierVersion :urpx/hasPlanElements
                               :urpx/hasSeasons]
                              (:urpx/hasSeasons pe)))]
      (doseq [holiday? [(constantly true) (constantly false)]]
        (is (= 5 (count (:urpx.resolved/ledgers
                         (price/resolve-prices-with-modifiers
                          plan [modi'] (zdt 2026 7 15 17) {:holiday? holiday?}))))
            "composition must not depend on whether the day is a holiday")))))

(deftest un-nested-v051-modifier-is-not-dropped
  (testing "a v0.5.1 document may state the modifier-to-version link only
            upward, through urpx:forRatePlanModifier, with the snapshot
            top-level. resolve-prices-with-modifiers called modifier-applies-to?
            without opts, so plan-version could only follow the nested
            down-pointer; the version came back nil, applicableToRatePlan was
            empty, and the modifier was dropped from the composition in
            silence. The composed result was indistinguishable from one with no
            modifier at all, which is the worst way to lose a whole ledger."
    (let [plan     (fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")
          modi     (fixtures/load-modifier "pge-e-tou-c-2026-03-01")
          mver     (:urpx/hasRatePlanModifierVersion modi)
          unnested (dissoc modi :urpx/hasRatePlanModifierVersion)
          mver'    (assoc mver :urpx/forRatePlanModifier {:jsonld/id (:jsonld/id modi)})
          opts     {:versions-by-plan (index/versions-by-plan [unnested mver'])}
          n-of     (fn [r] (count (:urpx.resolved/ledgers r)))
          base     (n-of (price/resolve-prices plan (zdt 2026 7 15 17)))
          nested   (n-of (price/resolve-prices-with-modifiers plan [modi] (zdt 2026 7 15 17)))]
      (is (< base nested) "sanity: the nested form does contribute a ledger")
      (is (= nested
             (n-of (price/resolve-prices-with-modifiers
                    plan [unnested] (zdt 2026 7 15 17) opts)))
          "the un-nested form must contribute the same ledgers as the nested one"))))

(deftest modifier-tiers-are-ordered-like-base-tiers
  (testing "marginal-unit-rate documents itself as taking the LOWEST tier and
            implements that as `first`, which holds only if the tier vector is
            ordered by tier number. resolve-prices sorted; the modifier path did
            not, so it handed marginal-unit-rate whatever came first in the
            document."
    (let [r (price/resolve-prices-with-modifiers
             (fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")
             [(fixtures/load-modifier "pge-e-tou-c-2026-03-01")]
             (zdt 2026 7 15 17))]
      (doseq [ledger (:urpx.resolved/ledgers r)]
        (let [ns- (map #(or (:urpx.resolved/tier-number %) 0) (:urpx.resolved/tiers ledger))]
          (is (= ns- (sort ns-))
              (str "tiers out of order in " (:urpx.resolved/ledger-id ledger))))))))

(deftest matrix-modifier-alternatives-are-not-tiers
  (testing "PG&E's vintage-PCIA ledger carries 18 prices selected by departure
            vintage: mutually-exclusive ALTERNATIVES, exactly one of which
            applies to any customer. marginal-unit-rate treated them as a block
            tier ladder and took the first, so composing the modifier silently
            added the 2009 vintage to every marginal rate (0.41784 became
            0.44757). The 18 span 0.06391/kWh, so the arbitrary pick was wrong
            by up to that much, and the pick was document order.

            Resolution still surfaces all 18, which is the documented
            caller-picks contract for reading tiers. It is the AGGREGATE that
            has no defensible answer without a selection, so the aggregate is
            what refuses."
    (let [plan (fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")
          modi (fixtures/load-modifier "pge-e-tou-c-2026-03-01")
          when- (zdt 2026 7 15 17)
          composed (price/resolve-prices-with-modifiers plan [modi] when-)]

      (testing "all 18 still surface, and the ledger says why it cannot be summed"
        ;; by id: the BASE plan now also carries a marked ledger, its
        ;; income-tier BSC, so `some` on the mark alone would pick either
        (let [l (some #(when (= "pge:ledger-vintage-pcia" (:urpx.resolved/ledger-id %)) %)
                      (:urpx.resolved/ledgers composed))]
          (is (some? l) "the alternatives-bearing ledger is marked")
          (is (= 18 (count (:urpx.resolved/tiers l))))
          (is (= 18 (count (:urpx.resolved/unselected-alternatives l))))))

      (testing "the aggregate refuses rather than picking one"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"mutually-exclusive price alternatives with no selection"
                              (price/marginal-unit-rate composed))))

      (testing "with a selection it resolves, and only the chosen vintage counts"
        (let [sel  {:profile-alternatives {"pge:pa-departure-vintage" "pge:pa-vintage-2020"}}
              r    (price/resolve-prices-with-modifiers plan [modi] when- sel)
              l    (some #(when (= "pge:ledger-vintage-pcia" (:urpx.resolved/ledger-id %)) %)
                         (:urpx.resolved/ledgers r))
              base (price/marginal-unit-rate (price/resolve-prices plan when-))]
          (is (nil? (:urpx.resolved/unselected-alternatives l))
              "a chosen alternatives set is no longer marked")
          (is (= 1 (count (:urpx.resolved/tiers l))) "only the chosen vintage surfaces")
          (is (= (+ base (:urpx.resolved/unit-price (first (:urpx.resolved/tiers l))))
                 (price/marginal-unit-rate r))
              "the marginal rate is the base plus exactly the chosen vintage")))

      (testing "the base plan alone is unaffected by any of this"
        (is (= 0.46886M (price/marginal-unit-rate (price/resolve-prices plan when-))))))))

(deftest no-energy-ledger-is-a-malformed-question-not-a-zero
  (testing "CPAU E-14 is a street lighting schedule: flat per-lamp-per-month and
            per-fixture-per-month charges, no per-kWh charge anywhere. Asking
            what its next kWh costs has no answer, and marginal-unit-rate used
            to say 0M, which reads to a consumer as free.

            This is the same failure the last three releases removed at the tier
            level, one level up: not a price that could not be valued, but an
            aggregate with nothing to aggregate."
    (let [plan (fixtures/load-rate-plan "cpau-e-14-2025-07-01")
          r    (price/resolve-prices plan (zdt 2026 7 15 17))]
      (is (false? (price/has-energy-ledger? r)))
      (is (empty? (price/measurement-kinds r)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"prices nothing per kWh"
                            (price/marginal-unit-rate r)))
      (testing "the refusal carries a reason a sweeping caller can classify on,
                rather than forcing a catch-all"
        (is (= :no-energy-ledger
               (try (price/marginal-unit-rate r) nil
                    (catch clojure.lang.ExceptionInfo e (:reason (ex-data e))))))))))

(deftest zero-energy-ledgers-is-distinguishable-from-a-genuine-zero
  (testing "the distinction that motivated the change. A plan with no energy
            ledger and a plan whose energy ledgers genuinely sum to zero both
            returned 0M, and they are not the same statement: the first has no
            answer, the second answers zero."
    (let [flat     (fixtures/load-rate-plan "cpau-e-14-2025-07-01")
          priced   (fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")
          ;; same plan, every unit price zeroed: energy ledgers still exist
          zeroed   (clojure.walk/postwalk
                    (fn [n] (if (and (map? n) (= "urpx:Price" (:jsonld/type n))
                                     (:urpx/unitPrice n))
                              (assoc n :urpx/unitPrice 0M) n))
                    priced)
          when-    (zdt 2026 7 15 17)
          r-flat   (price/resolve-prices flat when-)
          r-zeroed (price/resolve-prices zeroed when-)]

      (testing "no energy ledger: refuses"
        (is (false? (price/has-energy-ledger? r-flat)))
        (is (thrown? clojure.lang.ExceptionInfo (price/marginal-unit-rate r-flat))))

      (testing "energy ledgers that sum to zero: answers zero, because that IS
                the answer"
        (is (true? (price/has-energy-ledger? r-zeroed)))
        (is (contains? (price/measurement-kinds r-zeroed) "urpx:energy"))
        (is (zero? (price/marginal-unit-rate r-zeroed)))))))

(deftest has-energy-ledger-guards-the-aggregate
  (testing "the predicate exists so the refusal is not a tax on every caller.
            Sweeping a corpus, ask first; the throw is then the right answer for
            a caller who did not."
    (let [plans ["pge-e-tou-c-2026-03-01" "pge-e-1-2026-03-01"
                 "sce-d-2026-03-20" "sdge-dr-2026-04-01" "cpau-e-14-2025-07-01"]
          rs    (map #(price/resolve-prices (fixtures/load-rate-plan %) (zdt 2026 7 15 17)) plans)]
      (doseq [[name r] (map vector plans rs)]
        (if (price/has-energy-ledger? r)
          (is (some? (price/marginal-unit-rate r))
              (str name " claims an energy ledger, so the rate must compute"))
          (is (thrown? clojure.lang.ExceptionInfo (price/marginal-unit-rate r))
              (str name " claims no energy ledger, so the rate must refuse"))))
      (testing "and the sweep is not vacuous: both branches are exercised"
        (is (some price/has-energy-ledger? rs))
        (is (some (complement price/has-energy-ledger?) rs))))))

(deftest the-caller-supplies-the-instant
  (testing "no code under src/ may read an ambient clock. Every resolution takes
            the instant from its caller, which is what makes historic replay the
            same code path as a forecast rather than a special case, and what
            makes a pinned probe instant mean the same thing across runs.

            This is a test rather than a convention because a single
            (ZonedDateTime/now) added later would be invisible in review and
            would quietly make results depend on when they were computed."
    (let [;; Match the CLOCK CALL, not a particular spelling of the class: the
          ;; first version of this test required an unqualified class name and
          ;; sailed past an injected (java.time.ZonedDateTime/now), which is
          ;; exactly the false assurance a test like this must not give.
          clocks #"(?:ZonedDateTime|LocalDateTime|LocalDate|LocalTime|Instant|OffsetDateTime|Year|YearMonth|Clock)/(?:now|systemDefaultZone|systemUTC)|System/(?:currentTimeMillis|nanoTime)|java\.util\.Date\."
          hits   (for [f (file-seq (io/file "src"))
                       :when (and (.isFile f) (.endsWith (.getName f) ".clj"))
                       :let [src (slurp f)]
                       [n line] (map-indexed vector (str/split-lines src))
                       :when (re-find clocks line)]
                   (str (.getPath f) ":" (inc n) " " (str/trim line)))]
      (is (empty? hits)
          (str "ambient clock read under src/:\n  " (str/join "\n  " hits))))))

(deftest recurring-charges-surface-on-both-paths
  (testing "pd-active-at-tou? used to answer two unrelated questions, returning
            false for any PriceDefinition carrying its own CalculationMethod.
            Because the modifier path applies that predicate only when there is
            a TOU period to gate against, the recurring exclusion was skipped
            along with it: the same PD shape was dropped from a base plan and
            kept in a modifier.

            The asymmetry produced incoherent output on a filed document. SCE
            CARE's discounts ON the Base Services Charge surfaced while the
            Base Services Charge itself did not, so the result carried credits
            against a charge that was not in it."
    (let [plan (fixtures/load-rate-plan "sce-d-2026-03-20")
          care (fixtures/load-modifier "sce-care-2026-03-20")
          r    (price/resolve-prices-with-modifiers plan [care] (zdt 2026 7 15 17))
          ids  (set (map :urpx.resolved/price-id
                         (mapcat :urpx.resolved/tiers (:urpx.resolved/ledgers r))))]
      (is (contains? ids "sce:d-2026-03-20-price-base-services-charge")
          "the charge itself must be present")
      (is (contains? ids "sce:care-price-bsc-discount-2026-03-20")
          "and so must the discount on it, which always was")))

  (testing "a base plan whose every PriceDefinition is recurring reported
            nothing at all. CPAU E-14 is street lighting: 18 recurring PDs and,
            before this, 0 ledgers and 0 tiers."
    (let [r (price/resolve-prices (fixtures/load-rate-plan "cpau-e-14-2025-07-01")
                                  (zdt 2026 7 15 17))]
      (is (pos? (count (:urpx.resolved/ledgers r))))
      (is (= 18 (count (mapcat :urpx.resolved/tiers (:urpx.resolved/ledgers r))))))))

(deftest a-recurring-charge-says-what-it-is-charged-per
  (testing "a recurring charge's unit price is per INTERVAL, not per unit
            consumed. SCE's base services charge is 0.794 per P1D on a
            meter-count metric input that carries no measurement kind and no
            unit, so without the interval the result would hand a consumer a
            number that nothing in it explains. That is how a monthly charge
            becomes a wrong per-day rate."
    (let [r    (price/resolve-prices (fixtures/load-rate-plan "sce-d-2026-03-20")
                                     (zdt 2026 7 15 17))
          tier (->> (:urpx.resolved/ledgers r)
                    (mapcat :urpx.resolved/tiers)
                    (filter #(= "sce:d-2026-03-20-price-base-services-charge"
                                (:urpx.resolved/price-id %)))
                    first)]
      (is (some? tier))
      (is (= 0.794M (:urpx.resolved/unit-price tier)))
      (is (= (java.time.Period/ofDays 1) (:urpx.resolved/interval-duration tier))
          "the interval must travel with the number")))

  (testing "a per-unit price carries no interval, so the key distinguishes them"
    (let [r (price/resolve-prices (fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")
                                  (zdt 2026 7 15 17))]
      (is (not-any? :urpx.resolved/interval-duration
                    (mapcat :urpx.resolved/tiers
                            (filter #(= "urpx:energy"
                                        (get-in % [:urpx.resolved/measurement-kind :jsonld/id]))
                                    (:urpx.resolved/ledgers r))))))))

(deftest surfacing-recurring-charges-does-not-move-any-marginal-rate
  (testing "the charges now surface, but a per-interval figure must never enter
            a per-kWh sum. Pinned as exact values rather than as a comparison,
            so that a future change which moved BOTH the rate and its
            expectation could not pass."
    ;; These four moved in the additive-ledger fix, which is a different change
    ;; from the one this test is about. They are pinned as exact values so that
    ;; a rate moving for ANY reason has to be explained, which is what made the
    ;; additive-ledger defect visible here rather than silently absorbed.
    (doseq [[plan expected] [["pge-e-tou-c-2026-03-01" 0.46886M]
                             ["sce-d-2026-03-20"       0.31085M]
                             ["sdge-dr-2026-04-01"     0.42034M]
                             ["ladwp-r-1a-2026-04-01"  0.24362M]]]
      (is (= expected (price/marginal-unit-rate
                       (price/resolve-prices (fixtures/load-rate-plan plan)
                                             (zdt 2026 7 15 17))))
          plan)))

  (testing "CPAU E-14 now resolves ledgers but still has no energy ledger, so it
            still refuses, and the refusal says which of the two it is"
    (let [r (price/resolve-prices (fixtures/load-rate-plan "cpau-e-14-2025-07-01")
                                  (zdt 2026 7 15 17))]
      (is (false? (price/has-energy-ledger? r)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"none of them measured in a urpx:measurementKind"
                            (price/marginal-unit-rate r))))))

(deftest event-driven-prices-are-excluded-from-the-marginal-rate
  (testing "a Price carrying urpx:hasDynamicPricing applies only while its
            event is called. A critical peak charge is real but exceptional,
            capped around 50 hours a summer against roughly 2,200 summer hours,
            so summing it unconditionally answered `what would this cost during
            an event' while reading as the ordinary rate. A price server
            consuming that would have charged every summer kWh as an event
            hour.

            Unlike the mutually-exclusive alternatives this function also
            refuses on, there IS a defensible default here: no-event-today is
            the ordinary state rather than an arbitrary pick."
    (let [plan (fixtures/load-rate-plan "smud-tod-5-8pm-2026-01-01")
          cpp  (fixtures/load-modifier "smud-cpp-2026-01-01")
          when- (zdt 2026 7 15 17)
          base (price/resolve-prices plan when-)
          comp- (price/resolve-prices-with-modifiers plan [cpp] when-)]

      (testing "the event charge is surfaced and tagged, which option (1) did"
        (let [dyn (->> (:urpx.resolved/ledgers comp-)
                       (mapcat :urpx.resolved/tiers)
                       (filter :urpx.resolved/dynamic-pricing))]
          (is (= 1 (count dyn)))
          (is (= 0.5000M (:urpx.resolved/unit-price (first dyn))))
          (is (= "urpx:criticalPeakPricing"
                 (get-in (first dyn) [:urpx.resolved/dynamic-pricing
                                      :urpx/dynamicPricingType :jsonld/id])))))

      (testing "the default is the non-event baseline: composing the CPP
                modifier no longer changes the marginal rate"
        (is (= 0.3765M (price/marginal-unit-rate base)))
        (is (= 0.3765M (price/marginal-unit-rate comp-))))

      (testing "and the during-event upper bound is still available on request"
        (is (= 0.8765M (price/marginal-unit-rate comp- {:include-dynamic-pricing? true})))))))

(deftest excluding-every-energy-tier-is-refused-not-zeroed
  (testing "if every energy tier is excluded the sum would be 0M, which is the
            nil-reads-as-free failure one level up again: a modifier whose only
            energy ledger IS its event charge would report free.

            Tested on a constructed resolved value rather than a fixture,
            because no filing in the corpus has that shape: SMUD CPP comes
            closest and still carries two discount ledgers alongside its event
            charge, so excluding the charge leaves a real answer."
    (let [only-event {:urpx.resolved/ledgers
                      [{:urpx.resolved/ledger-id "x:ledger-event"
                        :urpx.resolved/measurement-kind {:jsonld/id "urpx:energy"}
                        :urpx.resolved/tiers
                        [{:urpx.resolved/price-id "x:event"
                          :urpx.resolved/unit-price 0.50M
                          :urpx.resolved/dynamic-pricing
                          {:urpx/dynamicPricingType {:jsonld/id "urpx:criticalPeakPricing"}}}]}]}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"every energy tier was excluded"
                            (price/marginal-unit-rate only-event)))
      (is (= :all-energy-tiers-excluded
             (try (price/marginal-unit-rate only-event) nil
                  (catch clojure.lang.ExceptionInfo e (:reason (ex-data e))))))
      (testing "and asking for the during-event bound resolves it"
        (is (= 0.50M (price/marginal-unit-rate only-event {:include-dynamic-pricing? true})))))))

(deftest condition-gated-prices-are-refused-rather-than-assumed
  (testing "This test used to pin the LIMIT of the event-price change: SCE
            PRIME-CPP carries an event charge tagged urpx:hasDynamicPricing and
            two CREDITS gated by urpx:ConditionExpression predicates this
            library does not evaluate (`is a CPP event called today' and a
            grandfathered-customer attribute). Excluding the event charge left
            both credits applying unconditionally and the marginal rate went
            NEGATIVE, -0.13897, which says a customer is paid to consume. The
            docstring here called that preferable to a plausible wrong number,
            which was true and was still a wrong number.

            It is now refused. Summing a price whose condition cannot be
            evaluated reports an assumption as a number, and this library
            refuses every other case of that. The caller can still ask for the
            every-condition-true upper bound explicitly."
    (let [plan (fixtures/load-rate-plan "sce-tou-d-prime-2026-03-20")
          cpp  (fixtures/load-modifier "sce-tou-d-prime-cpp-2026-03-20")
          when- (zdt 2026 7 15 17)
          comp- (price/resolve-prices-with-modifiers plan [cpp] when-)]
      (is (= 0.60067M (price/marginal-unit-rate (price/resolve-prices plan when-)))
          "the base plan alone is right")
      (is (= :condition-gated-tier
             (try (price/marginal-unit-rate comp-) nil
                  (catch clojure.lang.ExceptionInfo e (:reason (ex-data e)))))
          "and the composed rate refuses rather than reporting the assumption")
      (is (neg? (price/marginal-unit-rate comp- {:assume-conditions-hold? true}))
          "the number the refusal replaces is still reachable on request, and is
           still negative, which is why it is refused")
      (testing "the credits carry applicability rules, which is what marks them
                as gated by something unevaluated"
        (let [ids (->> (:urpx.resolved/ledgers comp-)
                       (mapcat :urpx.resolved/tiers)
                       (map :urpx.resolved/price-id)
                       (filter #(re-find #"tou-d-prime-cpp-price-.*credit" (str %))))]
          (is (= 2 (count ids)) (pr-str ids))))
      (testing "and neither credit is tagged as event-driven, which is exactly
                why excluding event-driven prices does not reach them"
        (is (not-any? :urpx.resolved/dynamic-pricing
                      (->> (:urpx.resolved/ledgers comp-)
                           (mapcat :urpx.resolved/tiers)
                           (filter #(re-find #"tou-d-prime-cpp-price-.*credit"
                                             (str (:urpx.resolved/price-id %)))))))))))

(deftest additive-rate-components-are-summed-not-sampled
  (testing "a ledger is used for two different things in this corpus. A block
            tier ladder is mutually exclusive, so only the marginal rung counts.
            An additive rate stack, which is how delivery charges are modeled,
            is cumulative and all of it counts.

            marginal-unit-rate took the first tier of every energy ledger, which
            is right for the ladder it was written for and wrong for the stack.
            SDG&E EV-TOU's delivery ledger carries ten components; keeping the
            first dropped nine and reported a third low. The error was not
            one-sided either, so no bound would have caught it: dropping a
            negative Baseline Credit made SCE TOU-D-4-9PM OVERSTATE."
    (let [when- (zdt 2026 7 15 18)
          rate  (fn [f] (price/marginal-unit-rate
                         (price/resolve-prices (fixtures/load-rate-plan f) when-)))
          tiers-sum (fn [f]
                      (->> (price/resolve-prices (fixtures/load-rate-plan f) when-)
                           :urpx.resolved/ledgers
                           (filter #(= "urpx:energy"
                                       (get-in % [:urpx.resolved/measurement-kind :jsonld/id])))
                           (mapcat :urpx.resolved/tiers)
                           (keep :urpx.resolved/unit-price)
                           (reduce + 0M)))]

      (testing "an all-additive ledger contributes every component"
        (is (= 0.60067M (rate "sce-tou-d-prime-2026-03-20")))
        (is (= (tiers-sum "sce-tou-d-prime-2026-03-20") (rate "sce-tou-d-prime-2026-03-20"))
            "nothing is dropped when no tier carries a number"))

      (testing "the MIXED ledger: ten bare UDC components plus one tier-numbered
                Baseline Credit. Summed-plus-lowest and plain summing agree here
                because there is exactly one numbered tier; they would diverge
                the moment a real ladder appeared beside the components, and
                only summed-plus-lowest stays right then."
        (let [l (->> (price/resolve-prices (fixtures/load-rate-plan "sdge-dr-2026-04-01") when-)
                     :urpx.resolved/ledgers
                     (some #(when (= "sdge:dr-2026-04-01-ledger-energy-delivery"
                                     (:urpx.resolved/ledger-id %)) %)))
              ts (:urpx.resolved/tiers l)]
          (is (= 1 (count (filter :urpx.resolved/tier-number ts))))
          (is (= 10 (count (remove :urpx.resolved/tier-number ts))))
          (is (= 0.42034M (rate "sdge-dr-2026-04-01")))))

      (testing "a genuine block-tier ladder still contributes only its lowest
                rung, which is the behavior this function was written for"
        (let [r (price/resolve-prices (fixtures/load-rate-plan "pge-e-1-2026-03-01") when-)
              ladders (->> (:urpx.resolved/ledgers r)
                           (filter #(= "urpx:energy"
                                       (get-in % [:urpx.resolved/measurement-kind :jsonld/id])))
                           (filter #(every? :urpx.resolved/tier-number (:urpx.resolved/tiers %)))
                           (filter #(< 1 (count (:urpx.resolved/tiers %)))))]
          (is (seq ladders) "sanity: PG&E E-1 really is block-tiered")
          (is (< (price/marginal-unit-rate r)
                 (->> (:urpx.resolved/ledgers r)
                      (filter #(= "urpx:energy"
                                  (get-in % [:urpx.resolved/measurement-kind :jsonld/id])))
                      (mapcat :urpx.resolved/tiers)
                      (keep :urpx.resolved/unit-price)
                      (reduce + 0M)))
              "a ladder's upper rungs are still excluded"))))))

(deftest export-ledgers-do-not-enter-an-import-marginal-rate
  (testing "urpx:flowDirection urpx:reverse marks an EXPORT ledger:
            compensation for energy sent back, not a cost of the next kWh
            consumed. Summing the two conflates a credit with a charge.

            Measured on the real corpus before this fix: composing PG&E E-ELEC
            with its NBT modifier returned -213.15920 per kWh, because four
            export ledgers carrying 4,610 hourly avoided-cost values were added
            to a 0.56006 import rate. Excluding them returns exactly 0.56006,
            the base plan's own rate.

            Constructed rather than fixture-driven: the PG&E NBT filing is 3 MB
            against a 976 KB fixture set, and the behavior under test is the
            flow-direction predicate rather than anything about that document."
    (let [import-ledger {:urpx.resolved/ledger-id "x:import"
                         :urpx.resolved/measurement-kind {:jsonld/id "urpx:energy"}
                         :urpx.resolved/flow-direction {:jsonld/id "urpx:forward"}
                         :urpx.resolved/tiers [{:urpx.resolved/unit-price 0.30M}]}
          export-ledger {:urpx.resolved/ledger-id "x:export"
                         :urpx.resolved/measurement-kind {:jsonld/id "urpx:energy"}
                         :urpx.resolved/flow-direction {:jsonld/id "urpx:reverse"}
                         :urpx.resolved/tiers [{:urpx.resolved/unit-price -192.95M}]}
          unspecified   {:urpx.resolved/ledger-id "x:unspecified"
                         :urpx.resolved/measurement-kind {:jsonld/id "urpx:energy"}
                         :urpx.resolved/tiers [{:urpx.resolved/unit-price 0.05M}]}]

      (testing "an export ledger contributes nothing"
        (is (= 0.30M (price/marginal-unit-rate
                      {:urpx.resolved/ledgers [import-ledger export-ledger]}))))

      (testing "a ledger declaring no direction still contributes, since most
                filings state none and excluding them would empty the rate"
        (is (= 0.35M (price/marginal-unit-rate
                      {:urpx.resolved/ledgers [import-ledger unspecified]}))))

      (testing "a plan with ONLY export energy refuses rather than reporting a
                credit as though it were a price"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"no energy ledger"
                              (price/marginal-unit-rate
                               {:urpx.resolved/ledgers [export-ledger]}))))

      (testing "has-energy-ledger? agrees with the aggregate rather than
                contradicting it, so the guard cannot say yes where the sum
                would refuse"
        (is (false? (price/has-energy-ledger? {:urpx.resolved/ledgers [export-ledger]})))
        (is (true? (price/has-energy-ledger? {:urpx.resolved/ledgers [import-ledger]}))))

      (testing "urpx:net and urpx:bidirectional contribute, deliberately:
                neither appears in the corpus, so excluding them would be
                speculation"
        (doseq [d ["urpx:net" "urpx:bidirectional"]]
          (is (= 0.30M (price/marginal-unit-rate
                        {:urpx.resolved/ledgers
                         [(assoc import-ledger :urpx.resolved/flow-direction {:jsonld/id d})]}))
              d))))))

(deftest a-price-carries-its-own-tou-period
  (testing "urpx:touPeriodNumber is rdfs:domain the union of urpx:Price,
            urpx:PriceDefinition, urpx:PriceSet and urpx:TOUPeriod, so a Price
            may carry the TOU link itself. Only the PriceDefinition was read,
            and the same blind spot produced two DIFFERENT wrong answers.

            Reported by the urpx-tariffs session, which found the summary half;
            the tier half turned up while confirming it. Their corpus fix has
            since collapsed these variants, so the live corpus no longer
            exercises this path at all. This fixture is pinned at the commit
            that still does, which is the whole reason fixtures are pinned."
    (let [plan (fixtures/load-rate-plan "sdge-ev-tou-2026-04-01")
          care (fixtures/load-modifier "sdge-care-2025-10-01")]

      (testing "the fixture really carries Price-level TOU links, or this test
                proves nothing"
        (let [ps (vals (index/entities-of-type plan "urpx:Price"))]
          (is (<= 12 (count (filter :urpx/touPeriodNumber ps))))))

      (testing "THE TIER HALF: six variants sharing one undifferentiated
                PriceDefinition collided in the latest-wins dedupe, five were
                discarded, and the survivor was reported at EVERY hour. The
                transmission charge read at its on-peak value around the clock."
        (doseq [[h period] [[17 1] [6 2] [2 3]]]
          (let [r (price/resolve-prices plan (zdt 2026 7 15 h))
                ts (->> (:urpx.resolved/ledgers r)
                        (mapcat :urpx.resolved/tiers)
                        (filter #(re-find #"udc-transmission"
                                          (str (:urpx.resolved/price-id %)))))]
            (is (= period (:urpx.resolved/tou-period-number r))
                (str "sanity: hour " h " should resolve to period " period))
            (is (= 1 (count ts)) "exactly one transmission price applies")
            (is (every? #(or (nil? (:urpx.resolved/tier-number %)) true) ts)))))

      (testing "THE SUMMARY HALF: every TOU variant of one charge summed into
                the subtotal, so the CARE discount came out 3.09x"
        (let [t (->> (price/resolve-prices-with-modifiers plan [care] (zdt 2026 7 15 17))
                     :urpx.resolved/ledgers
                     (mapcat :urpx.resolved/tiers)
                     (filter #(= "sdge:care-price-bill-discount-2025-10-01"
                                 (:urpx.resolved/price-id %)))
                     first)]
          (is (= -0.2999885M (:urpx.resolved/unit-price t))
              "the instant-correct discount; -0.9255435M was the overstatement"))))))

(deftest an-emptied-alternatives-ledger-still-refuses
  (testing "the guard used to be deletable by the very thing it guards against.
            The unselected mark was computed from SURVIVING tier entries, and a
            ledger with none is elided before anything can mark it, so any
            route that emptied an alternatives ledger also removed the refusal
            protecting it.

            Dating PG&E's 18 vintage-PCIA prices into the future took
            marginal-unit-rate from refusing to answering 0.46886, a rate
            silently missing a component spanning 0.06391/kWh."
    (let [plan (fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")
          modi (fixtures/load-modifier "pge-e-tou-c-2026-03-01")
          v18  (into #{} (map #(str "pge:price-vpcia-" %)) (range 2009 2027))
          futured (clojure.walk/postwalk
                   (fn [n] (if (and (map? n) (v18 (:jsonld/id n)))
                             (assoc n :urpx/startDate (java.time.LocalDate/of 2030 1 1))
                             n))
                   modi)
          rate (fn [m] (try (price/marginal-unit-rate
                             (price/resolve-prices-with-modifiers plan [m] (zdt 2026 7 15 17)))
                            (catch clojure.lang.ExceptionInfo e (:reason (ex-data e)))))]
      (is (= :unselected-alternatives (rate modi)) "intact: refuses, as it always did")
      (is (= :unselected-alternatives (rate futured))
          "emptied: must STILL refuse, or removing content removed the guard")

      (testing "and a selection resolves it either way, so the refusal is not a
                blanket one"
        (let [sel {:profile-alternatives {"pge:pa-departure-vintage" "pge:pa-vintage-2020"}}]
          (is (some? (price/marginal-unit-rate
                      (price/resolve-prices-with-modifiers plan [modi] (zdt 2026 7 15 17) sel)))))))))

(deftest a-withheld-non-energy-dimension-does-not-refuse-the-rate
  (testing "the refusal is energy-aware, deliberately. PG&E E-TOU-C's
            income-tier alternatives feed a Base Services Charge ledger whose
            metric input is not energy-kind, so withholding it cannot change a
            per-kWh figure and must not refuse one. The vintage-PCIA
            alternatives DO feed an energy ledger, and do refuse.

            Both shapes resolve on the same plan, which is why the distinction
            is made at the ledger rather than at the document."
    (let [plan (fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")
          r    (price/resolve-prices plan (zdt 2026 7 15 17))
          withheld (filter :urpx.resolved/unselected-alternatives (:urpx.resolved/ledgers r))]
      (is (seq withheld) "the base plan does withhold a dimension")
      (is (not-any? #(= "urpx:energy" (get-in % [:urpx.resolved/measurement-kind :jsonld/id]))
                    withheld)
          "and it is not an energy one")
      (is (= 0.46886M (price/marginal-unit-rate r))
          "so the per-kWh rate answers rather than refusing"))))

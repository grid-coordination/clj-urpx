(ns urpx.price-test
  (:require [clojure.test :refer [deftest is testing]]
            [urpx.price :as price]
            [urpx.test-fixtures :as fixtures])
  (:import [java.math BigDecimal]
           [java.time ZonedDateTime ZoneId]))

(def la-zone (ZoneId/of "America/Los_Angeles"))

(defn- zdt
  ([y mo d h] (zdt y mo d h 0))
  ([y mo d h mi] (ZonedDateTime/of y mo d h mi 0 0 la-zone)))

;; ---------------------------------------------------------------------------
;; CPAU TOU — seasons + TOU period matching
;; ---------------------------------------------------------------------------

(deftest cpau-tou-summer-peak
  (let [plan (fixtures/load-rate-plan "cpau-e-1-tou-2026-01-01")
        ;; July 15, 2026 at 17:00 — Summer (Jun-Sep), peak hour (16:00-21:00)
        r (price/resolve-prices plan (zdt 2026 7 15 17))]
    (is (= "Summer" (:urpx.resolved/season-name r)))
    (is (= "Summer Peak" (:urpx.resolved/tou-period-name r)))
    (is (= 1 (:urpx.resolved/tou-period-number r)))
    (testing "every ledger contributes a single un-tiered entry"
      (is (every? #(= 1 (count (:urpx.resolved/tiers %))) (:urpx.resolved/ledgers r))))
    (testing "matched commodity price is Summer Peak ($0.23354)"
      (let [commodity (->> (:urpx.resolved/ledgers r)
                           (some #(when (= "Commodity (Supply)" (:urpx.resolved/ledger-name %)) %)))
            tier (first (:urpx.resolved/tiers commodity))]
        (is (= "Commodity Summer Peak" (:urpx.resolved/price-name tier)))
        (is (= (BigDecimal. "0.23354") (:urpx.resolved/unit-price tier)))
        (is (nil? (:urpx.resolved/tier-number tier))
            "TOU-only ledger has no block-tier metadata")))
    (testing "marginal unit rate sums commodity + distribution + public benefits"
      (is (= (-> (BigDecimal. "0.23354")
                 (.add (BigDecimal. "0.09351"))
                 (.add (BigDecimal. "0.00604")))
             (price/marginal-unit-rate r))))))

(deftest cpau-tou-winter-super-off-peak
  (let [plan (fixtures/load-rate-plan "cpau-e-1-tou-2026-01-01")
        ;; January 15, 2026 at 12:00 — Winter (Oct-May), super off-peak (09:00-15:00)
        r (price/resolve-prices plan (zdt 2026 1 15 12))]
    (is (= "Winter" (:urpx.resolved/season-name r)))
    (is (= "Winter Super Off-Peak" (:urpx.resolved/tou-period-name r)))
    (is (= 6 (:urpx.resolved/tou-period-number r)))
    (let [commodity (->> (:urpx.resolved/ledgers r)
                         (some #(when (= "Commodity (Supply)" (:urpx.resolved/ledger-name %)) %)))
          tier (first (:urpx.resolved/tiers commodity))]
      (is (= (BigDecimal. "0.07835") (:urpx.resolved/unit-price tier))))))

(deftest cpau-tou-summer-off-peak-wraps-midnight
  (testing "off-peak bracket 21:00-09:00 wraps midnight; both 22:00 and 06:00 hit it"
    (let [plan (fixtures/load-rate-plan "cpau-e-1-tou-2026-01-01")
          late (price/resolve-prices plan (zdt 2026 7 15 22))
          early (price/resolve-prices plan (zdt 2026 7 15 6))]
      (is (= "Summer Off-Peak" (:urpx.resolved/tou-period-name late)))
      (is (= "Summer Off-Peak" (:urpx.resolved/tou-period-name early))))))

(deftest cpau-tou-skips-customer-charge
  (testing "monthly customer-charge ledger is excluded from per-instant resolution"
    (let [plan (fixtures/load-rate-plan "cpau-e-1-tou-2026-01-01")
          r (price/resolve-prices plan (zdt 2026 7 15 17))
          names (set (map :urpx.resolved/ledger-name (:urpx.resolved/ledgers r)))]
      (is (not (contains? names "Customer Charge"))))))

;; ---------------------------------------------------------------------------
;; CPAU E-1 (non-TOU, block-tiered)
;; ---------------------------------------------------------------------------

(deftest cpau-e1-block-tiers-surface-all-prices
  (testing "block-tiered ledgers expose every tier with its bounds and unit-price"
    (let [plan (fixtures/load-rate-plan "cpau-e-1-2025-07-01")
          r (price/resolve-prices plan (zdt 2025 8 1 12))
          commodity (->> (:urpx.resolved/ledgers r)
                         (some #(when (= "Commodity (Supply)" (:urpx.resolved/ledger-name %)) %)))
          tiers (:urpx.resolved/tiers commodity)]
      (is (= 2 (count tiers)) "tier 1 and tier 2 both surfaced")
      (is (= [1 2] (map :urpx.resolved/tier-number tiers))
          "tiers come back sorted by number")
      (let [t1 (first tiers)
            t2 (second tiers)]
        (is (= (BigDecimal. "0.10373") (:urpx.resolved/unit-price t1)))
        (is (= (BigDecimal. "0.13372") (:urpx.resolved/unit-price t2)))
        (is (= (BigDecimal. "0")  (:urpx.resolved/tier-lower-bound t1)))
        (is (= (BigDecimal. "15") (:urpx.resolved/tier-upper-bound t1)))
        (is (= (BigDecimal. "15")     (:urpx.resolved/tier-lower-bound t2)))
        (is (= (BigDecimal. "999999") (:urpx.resolved/tier-upper-bound t2)))
        (is (some? (:urpx.resolved/tier-lower-bound-operator t1)))
        (is (some? (:urpx.resolved/tier-upper-bound-operator t2)))))))

(deftest cpau-e1-marginal-rate-uses-lowest-tier
  (let [plan (fixtures/load-rate-plan "cpau-e-1-2025-07-01")
        r (price/resolve-prices plan (zdt 2025 8 1 12))]
    ;; Commodity tier 1 0.10373 + Distribution tier 1 0.09593 + Public Benefits 0.00604
    (is (= (-> (BigDecimal. "0.10373")
               (.add (BigDecimal. "0.09593"))
               (.add (BigDecimal. "0.00604")))
           (price/marginal-unit-rate r)))))

;; ---------------------------------------------------------------------------
;; URPX-oc2 — dayType vocabulary + holiday inclusion (PG&E E-TOU-D)
;; ---------------------------------------------------------------------------

;; E-TOU-D TOUPeriod numbering (from the fixture):
;;   1 = Summer Peak (weekdayDays + includeHolidays:false)
;;   2 = Winter Peak (weekdayDays + includeHolidays:false)
;;   3 = Summer Off-Peak (weekdayDays plain + weekday holiday-window override + weekendDays)
;;   4 = Winter Off-Peak (same shape as Summer Off-Peak)

(deftest e-tou-d-weekday-evening-is-summer-peak
  (testing "Wed 2026-07-15 18:00 — Summer Peak (weekdayDays + includeHolidays:false)"
    (let [plan (fixtures/load-rate-plan "pge-e-tou-d-tou-holiday-2026-03-01")
          r (price/resolve-prices plan (zdt 2026 7 15 18))]
      (is (= 1 (:urpx.resolved/tou-period-number r))))))

(deftest e-tou-d-weekend-evening-is-summer-off-peak
  (testing "Sat 2026-07-04 18:00 — weekendDays bracket wins; not Summer Peak
            (regression: would previously fall into permissive :else true and
            wrongly match the weekdayDays peak bracket)."
    (let [plan (fixtures/load-rate-plan "pge-e-tou-d-tou-holiday-2026-03-01")
          r (price/resolve-prices plan (zdt 2026 7 4 18))]
      (is (= 3 (:urpx.resolved/tou-period-number r))))))

(deftest e-tou-d-holiday-on-weekday-is-winter-off-peak
  (testing "Mon 2027-01-18 (MLK Day) 18:00 — peak window is suppressed by
            includeHolidays:false; the off-peak holiday-window override
            (includeHolidays:true + includeNonHolidays:false) takes over."
    (let [plan      (fixtures/load-rate-plan "pge-e-tou-d-tou-holiday-2026-03-01")
          mlk       (java.time.LocalDate/of 2027 1 18)
          when-zdt  (zdt 2027 1 18 18)
          r         (price/resolve-prices plan when-zdt {:holiday? #{mlk}})]
      (is (= 4 (:urpx.resolved/tou-period-number r))))))

(deftest e-tou-d-mlk-not-in-embedded-calendar
  (testing "Mon 2027-01-18 (MLK Day) 18:00 with no :holiday? — the embedded
            HolidayCalendar is auto-derived but does NOT list MLK, so this
            Monday matches the peak window like any other weekday."
    (let [plan (fixtures/load-rate-plan "pge-e-tou-d-tou-holiday-2026-03-01")
          r    (price/resolve-prices plan (zdt 2027 1 18 18))]
      (is (= 2 (:urpx.resolved/tou-period-number r))))))

;; ---------------------------------------------------------------------------
;; URPX-f9x — derive holiday set from embedded urpx:HolidayCalendar
;; ---------------------------------------------------------------------------

(deftest e-tou-d-derived-calendar-recognizes-memorial-day
  (testing "Mon 2026-05-25 (Memorial Day, Last Mon May) 18:00 with no :holiday? —
            the embedded HolidayCalendar derives Memorial Day, so the Winter
            peak window is suppressed; the off-peak holiday-window override
            takes over."
    (let [plan (fixtures/load-rate-plan "pge-e-tou-d-tou-holiday-2026-03-01")
          r    (price/resolve-prices plan (zdt 2026 5 25 18))]
      (is (= 4 (:urpx.resolved/tou-period-number r))
          "Winter Off-Peak (4) — derived holiday suppresses Winter Peak (2)"))))

(deftest e-tou-d-derived-calendar-recognizes-labor-day
  (testing "Mon 2026-09-07 (Labor Day, 1st Mon Sep) 18:00 — Summer; derived
            calendar suppresses Summer Peak in favor of Summer Off-Peak."
    (let [plan (fixtures/load-rate-plan "pge-e-tou-d-tou-holiday-2026-03-01")
          r    (price/resolve-prices plan (zdt 2026 9 7 18))]
      (is (= 3 (:urpx.resolved/tou-period-number r))))))

(deftest e-tou-d-explicit-holiday-overrides-derived
  (testing "Explicit :holiday? in opts overrides the embedded calendar — pass
            a constantly-false predicate and Memorial Day matches the peak
            window like any other Monday."
    (let [plan (fixtures/load-rate-plan "pge-e-tou-d-tou-holiday-2026-03-01")
          r    (price/resolve-prices plan (zdt 2026 5 25 18)
                                     {:holiday? (constantly false)})]
      (is (= 2 (:urpx.resolved/tou-period-number r))
          "Winter Peak (2) — explicit :holiday? wins"))))

(deftest no-embedded-calendar-keeps-fallback-behavior
  (testing "Plans without urpx:HolidayCalendar still get the historical
            'every date is a non-holiday' fallback when :holiday? is omitted."
    (let [plan (fixtures/load-rate-plan "cpau-e-1-tou-2026-01-01")
          ;; CPAU E-1 TOU has no holiday-aware brackets, so this just verifies
          ;; the resolution still works without throwing on a plan that lacks
          ;; a HolidayCalendar.
          r    (price/resolve-prices plan (zdt 2026 7 15 17))]
      (is (= "Summer Peak" (:urpx.resolved/tou-period-name r))))))

(deftest unknown-day-type-fails-loud
  (testing "An out-of-vocabulary dayType returns no match (no silent fallthrough)."
    (let [plan-base (fixtures/load-rate-plan "pge-e-tou-d-tou-holiday-2026-03-01")
          plan (update-in plan-base
                          [:urpx/hasRatePlanVersion
                           :urpx/hasPlanElements
                           :urpx/hasBillingModel
                           :urpx/hasTOUSchedule
                           :urpx/hasTOUTier 0
                           :urpx/hasTOUPeriod 0
                           :urpx/hasTimeBracket]
                          (fn [bs]
                            (mapv #(assoc % :urpx/dayType {:jsonld/id "urpx:nonsense"}) bs)))
          r (price/resolve-prices plan (zdt 2026 7 15 18))]
      (is (not= 1 (:urpx.resolved/tou-period-number r))))))

;; ---------------------------------------------------------------------------
;; URPX-yos — RatePlanModifier resolution
;; ---------------------------------------------------------------------------

(deftest eec-modifier-surfaces-single-export-compensation-price
  (testing "EEC adds a single Export Compensation ledger with one negative price"
    (let [eec (fixtures/load-rate-plan-modifier "cpau-e-eec-1-2025-09-01")
          ;; EEC's Price startDate is 2025-09-01; query after that date.
          {:keys [urpx.resolved/ledgers]} (price/resolve-modifier-prices eec (zdt 2026 1 15 12))]
      (is (= 1 (count ledgers)))
      (let [ledger (first ledgers)
            tier   (first (:urpx.resolved/tiers ledger))]
        (is (= "Export Electricity Compensation" (:urpx.resolved/ledger-name ledger)))
        (is (= 1 (count (:urpx.resolved/tiers ledger))))
        (is (= "urpx:cpau-eec-price-export-compensation" (:urpx.resolved/price-id tier)))
        (is (= (BigDecimal. "-0.12060") (:urpx.resolved/unit-price tier)))))))

(deftest nse-modifier-surfaces-single-net-surplus-price
  (testing "NSE adds a single Net Surplus Compensation ledger with one negative price"
    (let [nse (fixtures/load-rate-plan-modifier "cpau-e-nse-1-2025-09-01")
          ;; NSE's Price startDate is 2025-09-01; query after that date.
          {:keys [urpx.resolved/ledgers]} (price/resolve-modifier-prices nse (zdt 2026 1 15 12))]
      (is (= 1 (count ledgers)))
      (let [tier (first (:urpx.resolved/tiers (first ledgers)))]
        (is (= (BigDecimal. "-0.10120") (:urpx.resolved/unit-price tier)))))))

(deftest hra-modifier-surfaces-full-4x4-matrix
  (testing "HRA exposes all 16 matrix prices under one ledger; caller picks by id"
    (let [hra (fixtures/load-rate-plan-modifier "cpau-e-hra-2023-01-01")
          {:keys [urpx.resolved/ledgers]} (price/resolve-modifier-prices hra (zdt 2026 7 15 12))]
      (is (= 1 (count ledgers)))
      (let [tiers (:urpx.resolved/tiers (first ledgers))]
        (is (= 16 (count tiers)) "4 reserve levels x 4 generation tiers")
        (is (every? #(= "urpx:cpau-hra-pd-adjustment"
                        (:urpx.resolved/price-definition-id %))
                    tiers)
            "all 16 prices reference the single Hydro Rate Adjustment PD"))
      (testing "caller-side selection: pick Reserve <=25% / Generation < 319 GWh = $0.048"
        (let [tier (->> (:urpx.resolved/tiers (first ledgers))
                        (filter #(= "urpx:cpau-hra-price-below-25-gen-lt319"
                                    (:urpx.resolved/price-id %)))
                        first)]
          (is (= (BigDecimal. "0.048") (:urpx.resolved/unit-price tier))))))))

(deftest resolve-with-modifiers-appends-modifier-ledgers
  (testing "Base CPAU E-1 ledgers + EEC ledger appear in the combined output"
    (let [plan (fixtures/load-rate-plan "cpau-e-1-2025-07-01")
          eec  (fixtures/load-rate-plan-modifier "cpau-e-eec-1-2025-09-01")
          ;; Query after EEC's 2025-09-01 effective date.
          when-zdt (zdt 2026 1 15 12)
          base (price/resolve-prices plan when-zdt)
          comb (price/resolve-prices-with-modifiers plan [eec] when-zdt)
          ledger-names (mapv :urpx.resolved/ledger-name (:urpx.resolved/ledgers comb))]
      (is (= (inc (count (:urpx.resolved/ledgers base)))
             (count (:urpx.resolved/ledgers comb))))
      (is (some #{"Export Electricity Compensation"} ledger-names)))))

(deftest modifier-not-applicable-to-plan-is-skipped
  (testing "A modifier whose applicableToRatePlan does not list the base plan
            @id contributes no ledgers"
    (let [plan (fixtures/load-rate-plan "cpau-e-1-2025-07-01")
          eec-base (fixtures/load-rate-plan-modifier "cpau-e-eec-1-2025-09-01")
          eec (assoc-in eec-base
                        [:urpx/hasRatePlanModifierVersion :urpx/applicableToRatePlan]
                        [{:jsonld/id "urpx:not-this-plan"}])
          when-zdt (zdt 2026 1 15 12)
          base (price/resolve-prices plan when-zdt)
          comb (price/resolve-prices-with-modifiers plan [eec] when-zdt)]
      (is (= (count (:urpx.resolved/ledgers base))
             (count (:urpx.resolved/ledgers comb)))))))

(deftest hra-modifier-applies-to-both-cpau-e1-and-cpau-e1-tou
  (testing "HRA's applicableToRatePlan list contains both CPAU base plans"
    (let [hra (fixtures/load-rate-plan-modifier "cpau-e-hra-2023-01-01")
          plan-e1     (fixtures/load-rate-plan "cpau-e-1-2025-07-01")
          plan-e1-tou (fixtures/load-rate-plan "cpau-e-1-tou-2026-01-01")
          on-e1     (price/resolve-prices-with-modifiers plan-e1     [hra] (zdt 2026 1 15 12))
          on-e1-tou (price/resolve-prices-with-modifiers plan-e1-tou [hra] (zdt 2026 7 15 17))]
      (is (some #(= "Hydro Rate Adjustment" (:urpx.resolved/ledger-name %))
                (:urpx.resolved/ledgers on-e1)))
      (is (some #(= "Hydro Rate Adjustment" (:urpx.resolved/ledger-name %))
                (:urpx.resolved/ledgers on-e1-tou))))))

;; ---------------------------------------------------------------------------
;; URPX-gog — multiple tier-Prices sharing a single PriceDefinition
;; ---------------------------------------------------------------------------
;;
;; PG&E E-1's Conservation Incentive Adjustment ledger is the canonical
;; example: one PriceDefinition (pge:pd-cia) with multiple Prices, where each
;; Price (not the PD) carries the urpx:serviceTierNumber. The fixture below is
;; a minimal stand-in built directly in the post-coerce shape — three tiers,
;; one shared PD, no TOU machinery.

(defn- shared-pd-tier-fixture
  "Return a coerced RatePlan with one ledger backed by a single PriceDefinition
   and three Prices, each carrying its own :urpx/serviceTierNumber."
  []
  {:jsonld/type "urpx:RatePlan"
   :jsonld/id   "urpx:test-shared-pd"
   :urpx/ratePlanIdentifier "TEST-SHARED-PD"
   :urpx/name "Shared-PD tier-stratified test plan"
   :urpx/initialEffectiveDate (java.time.LocalDate/of 2026 1 1)
   :urpx/hasRatePlanVersion
   {:jsonld/type "urpx:RatePlanVersion"
    :urpx/effectiveDateTime (java.time.LocalDateTime/of 2026 1 1 0 0)
    :urpx/priceCurrency "USD"
    :urpx/hasPlanElements
    {:jsonld/type "urpx:PlanElements"
     :urpx/hasBillingModel
     {:jsonld/type "urpx:BillingModel"
      :urpx/hasServiceTier
      [{:jsonld/type "urpx:ServiceTier"
        :urpx/serviceTierNumber 1
        :urpx/name "Tier 1 (within baseline)"
        :urpx/lowerBound (BigDecimal. "0")
        :urpx/upperBound (BigDecimal. "100")}
       {:jsonld/type "urpx:ServiceTier"
        :urpx/serviceTierNumber 2
        :urpx/name "Tier 2 (101-400% of baseline)"
        :urpx/lowerBound (BigDecimal. "100")
        :urpx/upperBound (BigDecimal. "400")}
       {:jsonld/type "urpx:ServiceTier"
        :urpx/serviceTierNumber 3
        :urpx/name "Tier 2 Continued (over 400%)"
        :urpx/lowerBound (BigDecimal. "400")}]
      :urpx/hasLedger
      [{:jsonld/type "urpx:Ledger"
        :jsonld/id "test:ledger-cia"
        :urpx/name "Conservation Incentive Adjustment"
        :urpx/ledgerType {:jsonld/id "urpx:bundledLedger"}
        :urpx/hasPriceDefinition
        [{:jsonld/type "urpx:PriceDefinition"
          :jsonld/id "test:pd-cia"
          :urpx/name "CIA"}]}]}}
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
          ledger (->> (:urpx.resolved/ledgers r)
                      (some #(when (= "Conservation Incentive Adjustment"
                                      (:urpx.resolved/ledger-name %)) %)))
          tiers (:urpx.resolved/tiers ledger)]
      (is (some? ledger) "the CIA ledger appears in the resolved output")
      (is (= 3 (count tiers)) "all three tier-prices surface")
      (is (= [1 2 3] (mapv :urpx.resolved/tier-number tiers))
          "tiers are sorted by tier-number")
      (is (= [(BigDecimal. "-0.04052")
              (BigDecimal. "0.04089")
              (BigDecimal. "0.04089")]
             (mapv :urpx.resolved/unit-price tiers))
          "each tier surfaces its own unit-price, not the most-recent winner")
      (is (= ["Tier 1 (within baseline)"
              "Tier 2 (101-400% of baseline)"
              "Tier 2 Continued (over 400%)"]
             (mapv :urpx.resolved/tier-name tiers))
          "ServiceTier metadata is attached via the Price's serviceTierNumber"))))

(deftest temporal-versioning-still-picks-latest
  (testing "Two Prices on the same (PD, serviceTierNumber) with different
            startDates collapse to the latest — temporal versioning regression
            check after switching active-prices-by-pd to multi-valued."
    (let [base (shared-pd-tier-fixture)
          ;; Add a NEWER tier-1 price (same PD, same tier, later startDate)
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
          tiers (->> (:urpx.resolved/ledgers r)
                     (some #(when (= "Conservation Incentive Adjustment"
                                     (:urpx.resolved/ledger-name %)) %))
                     :urpx.resolved/tiers)
          tier-1 (->> tiers (some #(when (= 1 (:urpx.resolved/tier-number %)) %)))]
      (is (= 3 (count tiers)) "still 3 tiers — the newer t1 replaced the older")
      (is (= "test:price-cia-t1-v2" (:urpx.resolved/price-id tier-1))
          "the newer-startDate Price wins for tier 1")
      (is (= (BigDecimal. "-0.05000") (:urpx.resolved/unit-price tier-1))))))

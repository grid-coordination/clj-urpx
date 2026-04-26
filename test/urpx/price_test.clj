(ns urpx.price-test
  (:require [clojure.test :refer [deftest is testing]]
            [urpx.core :as core]
            [urpx.coerce :as coerce]
            [urpx.price :as price])
  (:import [java.math BigDecimal]
           [java.time ZonedDateTime ZoneId]))

(def cpau-tou-path
  "../urpx/test-cases/cpau-e-1-tou-2026-01-01/data/urpx-rate-plan_cpau_e-1-tou_2026-01-01.jsonld")

(def cpau-e1-path
  "../urpx/test-cases/cpau-e-1-2025-07-01/data/urpx-rate-plan_cpau_e-1_2025-07-01.jsonld")

(def la-zone (ZoneId/of "America/Los_Angeles"))

(defn- zdt
  ([y mo d h] (zdt y mo d h 0))
  ([y mo d h mi] (ZonedDateTime/of y mo d h mi 0 0 la-zone)))

;; ---------------------------------------------------------------------------
;; CPAU TOU — seasons + TOU period matching
;; ---------------------------------------------------------------------------

(deftest cpau-tou-summer-peak
  (let [plan (coerce/coerce-rate-plan (core/load-rate-plan cpau-tou-path))
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
  (let [plan (coerce/coerce-rate-plan (core/load-rate-plan cpau-tou-path))
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
    (let [plan (coerce/coerce-rate-plan (core/load-rate-plan cpau-tou-path))
          late (price/resolve-prices plan (zdt 2026 7 15 22))
          early (price/resolve-prices plan (zdt 2026 7 15 6))]
      (is (= "Summer Off-Peak" (:urpx.resolved/tou-period-name late)))
      (is (= "Summer Off-Peak" (:urpx.resolved/tou-period-name early))))))

(deftest cpau-tou-skips-customer-charge
  (testing "monthly customer-charge ledger is excluded from per-instant resolution"
    (let [plan (coerce/coerce-rate-plan (core/load-rate-plan cpau-tou-path))
          r (price/resolve-prices plan (zdt 2026 7 15 17))
          names (set (map :urpx.resolved/ledger-name (:urpx.resolved/ledgers r)))]
      (is (not (contains? names "Customer Charge"))))))

;; ---------------------------------------------------------------------------
;; CPAU E-1 (non-TOU, block-tiered)
;; ---------------------------------------------------------------------------

(deftest cpau-e1-block-tiers-surface-all-prices
  (testing "block-tiered ledgers expose every tier with its bounds and unit-price"
    (let [plan (coerce/coerce-rate-plan (core/load-rate-plan cpau-e1-path))
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
  (let [plan (coerce/coerce-rate-plan (core/load-rate-plan cpau-e1-path))
        r (price/resolve-prices plan (zdt 2025 8 1 12))]
    ;; Commodity tier 1 0.10373 + Distribution tier 1 0.09593 + Public Benefits 0.00604
    (is (= (-> (BigDecimal. "0.10373")
               (.add (BigDecimal. "0.09593"))
               (.add (BigDecimal. "0.00604")))
           (price/marginal-unit-rate r)))))

;; ---------------------------------------------------------------------------
;; URPX-oc2 — dayType vocabulary + holiday inclusion (PG&E E-TOU-D)
;; ---------------------------------------------------------------------------

(def pge-e-tou-d-path
  "../urpx/test-cases/pge-e-tou-d-tou-holiday-2026-03-01/data/urpx-rate-plan_pge_e-tou-d-tou-holiday_2026-03-01.jsonld")

;; E-TOU-D TOUPeriod numbering (from the fixture):
;;   1 = Summer Peak (weekdayDays + includeHolidays:false)
;;   2 = Winter Peak (weekdayDays + includeHolidays:false)
;;   3 = Summer Off-Peak (weekdayDays plain + weekday holiday-window override + weekendDays)
;;   4 = Winter Off-Peak (same shape as Summer Off-Peak)

(deftest e-tou-d-weekday-evening-is-summer-peak
  (testing "Wed 2026-07-15 18:00 — Summer Peak (weekdayDays + includeHolidays:false)"
    (let [plan (coerce/coerce-rate-plan (core/load-rate-plan pge-e-tou-d-path))
          r (price/resolve-prices plan (zdt 2026 7 15 18))]
      (is (= 1 (:urpx.resolved/tou-period-number r))))))

(deftest e-tou-d-weekend-evening-is-summer-off-peak
  (testing "Sat 2026-07-04 18:00 — weekendDays bracket wins; not Summer Peak
            (regression: would previously fall into permissive :else true and
            wrongly match the weekdayDays peak bracket)."
    (let [plan (coerce/coerce-rate-plan (core/load-rate-plan pge-e-tou-d-path))
          r (price/resolve-prices plan (zdt 2026 7 4 18))]
      (is (= 3 (:urpx.resolved/tou-period-number r))))))

(deftest e-tou-d-holiday-on-weekday-is-winter-off-peak
  (testing "Mon 2027-01-18 (MLK Day) 18:00 — peak window is suppressed by
            includeHolidays:false; the off-peak holiday-window override
            (includeHolidays:true + includeNonHolidays:false) takes over."
    (let [plan      (coerce/coerce-rate-plan (core/load-rate-plan pge-e-tou-d-path))
          mlk       (java.time.LocalDate/of 2027 1 18)
          when-zdt  (zdt 2027 1 18 18)
          r         (price/resolve-prices plan when-zdt {:holiday? #{mlk}})]
      (is (= 4 (:urpx.resolved/tou-period-number r))))))

(deftest e-tou-d-no-holiday-predicate-treats-day-as-non-holiday
  (testing "Same Mon 2027-01-18 18:00 with no holiday? predicate — date is
            treated as non-holiday, so the peak window matches as on any weekday."
    (let [plan (coerce/coerce-rate-plan (core/load-rate-plan pge-e-tou-d-path))
          r    (price/resolve-prices plan (zdt 2027 1 18 18))]
      (is (= 2 (:urpx.resolved/tou-period-number r))))))

(deftest unknown-day-type-fails-loud
  (testing "An out-of-vocabulary dayType returns no match (no silent fallthrough)."
    (let [plan-base (coerce/coerce-rate-plan (core/load-rate-plan pge-e-tou-d-path))
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

(def cpau-eec-path
  "../urpx/test-cases/cpau-e-eec-1-2025-09-01/data/urpx-rate-plan-modifier_cpau_e-eec-1_2025-09-01.jsonld")

(def cpau-nse-path
  "../urpx/test-cases/cpau-e-nse-1-2025-09-01/data/urpx-rate-plan-modifier_cpau_e-nse-1_2025-09-01.jsonld")

(def cpau-hra-path
  "../urpx/test-cases/cpau-e-hra-2023-01-01/data/urpx-rate-plan-modifier_cpau_e-hra_2023-01-01.jsonld")

(defn- load-modifier [path]
  (coerce/coerce-rate-plan-modifier (core/load-rate-plan path)))

(deftest eec-modifier-surfaces-single-export-compensation-price
  (testing "EEC adds a single Export Compensation ledger with one negative price"
    (let [eec (load-modifier cpau-eec-path)
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
    (let [nse (load-modifier cpau-nse-path)
          ;; NSE's Price startDate is 2025-09-01; query after that date.
          {:keys [urpx.resolved/ledgers]} (price/resolve-modifier-prices nse (zdt 2026 1 15 12))]
      (is (= 1 (count ledgers)))
      (let [tier (first (:urpx.resolved/tiers (first ledgers)))]
        (is (= (BigDecimal. "-0.10120") (:urpx.resolved/unit-price tier)))))))

(deftest hra-modifier-surfaces-full-4x4-matrix
  (testing "HRA exposes all 16 matrix prices under one ledger; caller picks by id"
    (let [hra (load-modifier cpau-hra-path)
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
    (let [plan (coerce/coerce-rate-plan (core/load-rate-plan cpau-e1-path))
          eec  (load-modifier cpau-eec-path)
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
    (let [plan (coerce/coerce-rate-plan (core/load-rate-plan cpau-e1-path))
          eec-base (load-modifier cpau-eec-path)
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
    (let [hra (load-modifier cpau-hra-path)
          plan-e1     (coerce/coerce-rate-plan (core/load-rate-plan cpau-e1-path))
          plan-e1-tou (coerce/coerce-rate-plan (core/load-rate-plan cpau-tou-path))
          on-e1     (price/resolve-prices-with-modifiers plan-e1     [hra] (zdt 2026 1 15 12))
          on-e1-tou (price/resolve-prices-with-modifiers plan-e1-tou [hra] (zdt 2026 7 15 17))]
      (is (some #(= "Hydro Rate Adjustment" (:urpx.resolved/ledger-name %))
                (:urpx.resolved/ledgers on-e1)))
      (is (some #(= "Hydro Rate Adjustment" (:urpx.resolved/ledger-name %))
                (:urpx.resolved/ledgers on-e1-tou))))))

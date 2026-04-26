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

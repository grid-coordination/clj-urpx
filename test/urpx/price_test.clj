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

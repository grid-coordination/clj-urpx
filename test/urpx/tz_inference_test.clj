(ns urpx.tz-inference-test
  (:require [clojure.test :refer [deftest is testing]]
            [urpx.test-fixtures :as fixtures]
            [urpx.tz-inference :as tz])
  (:import [java.time ZoneId]))

(def la (ZoneId/of "America/Los_Angeles"))
(def ny (ZoneId/of "America/New_York"))

;; v0.2.0 fixtures don't carry urpx:eiaId on Organization (the v0.1.x location
;; for that field). They do carry urpx:legalName, so legalName-based inference
;; covers the production path. The eia-id path remains supported for callers
;; that supply their own :eia-id->zone table; the tests below inject
;; :urpx/eiaId into a coerced fixture to exercise it.

(deftest pge-zone-inferred-from-legal-name
  (testing "v0.2.0 PG&E fixtures carry urpx:legalName but not urpx:eiaId,
            so inference resolves via the legalName table"
    (let [plan (fixtures/load-rate-plan "pge-e-1-2026-03-01")
          {:keys [zone inferred-from]} (tz/infer-zone plan)]
      (is (= la zone))
      (is (= :legal-name (:source inferred-from)))
      (is (= "Pacific Gas & Electric Company" (:value inferred-from))))))

(deftest unknown-utility-returns-nil
  (testing "A plan whose publisher is not in either table yields no inference"
    (let [base (fixtures/load-rate-plan "pge-e-1-2026-03-01")
          plan (update-in base
                          [:urpx/hasRatePlanVersion :urpx/publishedBy]
                          merge {:urpx/eiaId "99999"
                                 :urpx/legalName "Mystery Cooperative"})]
      (is (nil? (tz/infer-zone plan))))))

(deftest inference-also-works-for-modifiers
  (testing "RatePlanModifier publishedBy lives under hasRatePlanModifierVersion;
            inference handles either top-level type"
    (let [m (fixtures/load-modifier "pge-e-1-2026-03-01")
          {:keys [zone inferred-from]} (tz/infer-zone m)]
      (is (= la zone))
      (is (= :legal-name (:source inferred-from))))))

(deftest opts-extend-eia-table
  (testing "Caller-supplied :eia-id->zone is merged with defaults; caller entries win.
            v0.2.0 Organization doesn't carry urpx:eiaId by default, so the test
            injects one to exercise the lookup path."
    (let [base (fixtures/load-rate-plan "pge-e-1-2026-03-01")
          plan (assoc-in base
                         [:urpx/hasRatePlanVersion :urpx/publishedBy :urpx/eiaId]
                         "12345")
          opts {:eia-id->zone {"12345" "America/New_York"}}
          {:keys [zone inferred-from]} (tz/infer-zone plan opts)]
      (is (= ny zone))
      (is (= :eia-id (:source inferred-from)))
      (is (= "12345" (:value inferred-from))))))

(deftest caller-overrides-default-table-entry
  (testing "When caller passes the same EIA id as a built-in but a different zone,
            the caller's entry wins (built-ins are defaults, not overrides)"
    (let [base (fixtures/load-rate-plan "pge-e-1-2026-03-01")
          plan (assoc-in base
                         [:urpx/hasRatePlanVersion :urpx/publishedBy :urpx/eiaId]
                         "14328")  ;; PG&E's actual EIA id
          opts {:eia-id->zone {"14328" "America/New_York"}}
          {:keys [zone]} (tz/infer-zone plan opts)]
      (is (= ny zone)))))

(deftest plan-with-only-legal-name
  (testing "Falls through to legalName when eiaId is absent — the v0.2.0 default"
    (let [plan (fixtures/load-rate-plan "pge-e-1-2026-03-01")
          {:keys [inferred-from]} (tz/infer-zone plan)]
      (is (= :legal-name (:source inferred-from))))))

(deftest no-publisher-returns-nil
  (testing "Plan without publishedBy yields no inference"
    (let [base (fixtures/load-rate-plan "pge-e-1-2026-03-01")
          plan (update-in base
                          [:urpx/hasRatePlanVersion]
                          dissoc :urpx/publishedBy)]
      (is (nil? (tz/infer-zone plan))))))

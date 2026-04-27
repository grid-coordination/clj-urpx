(ns urpx.tz-inference-test
  (:require [clojure.test :refer [deftest is testing]]
            [urpx.test-fixtures :as fixtures]
            [urpx.tz-inference :as tz])
  (:import [java.time ZoneId]))

(def la (ZoneId/of "America/Los_Angeles"))
(def ny (ZoneId/of "America/New_York"))

(deftest cpau-zone-inferred-from-eia-id
  (testing "CPAU's eia-id 3203 hits the built-in table; eia-id is preferred over legalName"
    (let [plan (fixtures/load-rate-plan "cpau-e-1-tou-2026-01-01")
          {:keys [zone inferred-from]} (tz/infer-zone plan)]
      (is (= la zone))
      (is (= :eia-id (:source inferred-from)))
      (is (= "3203" (:value inferred-from)))
      (is (= "America/Los_Angeles" (:iana inferred-from))))))

(deftest pge-zone-inferred-from-legal-name
  (testing "PG&E fixtures don't carry urpx:eiaId, so inference falls through to legalName"
    (let [plan (fixtures/load-rate-plan "pge-e-elec-2026-01-01")
          {:keys [zone inferred-from]} (tz/infer-zone plan)]
      (is (= la zone))
      (is (= :legal-name (:source inferred-from)))
      (is (= "Pacific Gas & Electric Company" (:value inferred-from))))))

(deftest unknown-utility-returns-nil
  (testing "A plan whose publisher is not in either table yields no inference"
    (let [base (fixtures/load-rate-plan "cpau-e-1-tou-2026-01-01")
          plan (update-in base
                          [:urpx/hasRatePlanVersion :urpx/publishedBy]
                          merge {:urpx/eiaId "99999"
                                 :urpx/legalName "Mystery Cooperative"})]
      (is (nil? (tz/infer-zone plan))))))

(deftest inference-also-works-for-modifiers
  (testing "RatePlanModifier publishedBy lives under hasRatePlanModifierVersion;
            inference handles either top-level type"
    (let [mod (fixtures/load-rate-plan-modifier "cpau-e-eec-1-2025-09-01")
          {:keys [zone inferred-from]} (tz/infer-zone mod)]
      (is (= la zone))
      (is (= :eia-id (:source inferred-from))))))

(deftest opts-extend-eia-table
  (testing "Caller-supplied :eia-id->zone is merged with defaults; caller entries win"
    (let [base (fixtures/load-rate-plan "cpau-e-1-tou-2026-01-01")
          ;; Re-publish CPAU with an EIA id only the caller's table knows
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
    (let [plan (fixtures/load-rate-plan "cpau-e-1-tou-2026-01-01")
          opts {:eia-id->zone {"3203" "America/New_York"}}
          {:keys [zone]} (tz/infer-zone plan opts)]
      (is (= ny zone)))))

(deftest plan-with-only-legal-name
  (testing "Falls through to legalName when eiaId is absent"
    (let [base (fixtures/load-rate-plan "cpau-e-1-tou-2026-01-01")
          plan (update-in base
                          [:urpx/hasRatePlanVersion :urpx/publishedBy]
                          dissoc :urpx/eiaId)
          {:keys [inferred-from]} (tz/infer-zone plan)]
      (is (= :legal-name (:source inferred-from))))))

(deftest no-publisher-returns-nil
  (testing "Plan without publishedBy yields no inference (no signal at all)"
    (let [base (fixtures/load-rate-plan "cpau-e-1-tou-2026-01-01")
          plan (update-in base
                          [:urpx/hasRatePlanVersion]
                          dissoc :urpx/publishedBy)]
      (is (nil? (tz/infer-zone plan))))))

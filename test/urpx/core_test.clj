(ns urpx.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [urpx.core :as core]
            [urpx.test-fixtures :as fixtures]))

(deftest cpau-tou-fixture-available
  (is (some? (fixtures/fixture-resource "cpau-e-1-tou-2026-01-01"))
      "CPAU E-1 TOU JSON-LD fixture must be on the classpath"))

(deftest load-rate-plan-cpau-tou
  (let [plan (fixtures/load-raw "cpau-e-1-tou-2026-01-01")]

    (testing "top-level rate plan identity"
      (is (= "urpx:RatePlan" (:jsonld/type plan)))
      (is (= "urpx:cpau-e-1-tou" (:jsonld/id plan)))
      (is (= "E-1 TOU" (:urpx/ratePlanIdentifier plan)))
      (is (= "2026-01-01" (:urpx/initialEffectiveDate plan))))

    (testing "JSON-LD @context preserved"
      (is (map? (:jsonld/context plan)))
      (is (= "https://schema.urpx.org/" (get-in plan [:jsonld/context :urpx]))))

    (testing "rate plan version"
      (let [version (:urpx/hasRatePlanVersion plan)]
        (is (= "urpx:RatePlanVersion" (:jsonld/type version)))
        (is (= "2026-01-01" (:urpx/versionNumber version)))
        (is (= "USD" (:urpx/priceCurrency version)))
        (is (= {:jsonld/id "urpx:electricity"} (:urpx/commodity version)))))

    (testing "seasons"
      (let [seasons (get-in plan [:urpx/hasRatePlanVersion
                                  :urpx/hasPlanElements
                                  :urpx/hasSeasons])]
        (is (= 2 (count seasons)))
        (is (= #{"Summer" "Winter"} (set (map :urpx/seasonName seasons))))
        (let [summer (first (filter #(= "Summer" (:urpx/seasonName %)) seasons))]
          (is (= ["--06" "--07" "--08" "--09"] (:urpx/month summer))))))

    (testing "TOU schedule with tiers and time brackets"
      (let [tiers (get-in plan [:urpx/hasRatePlanVersion
                                :urpx/hasPlanElements
                                :urpx/hasBillingModel
                                :urpx/hasTOUSchedule
                                :urpx/hasTOUTier])]
        (is (= 3 (count tiers)))
        (is (= #{"Peak" "Off-Peak" "Super Off-Peak"}
               (set (map :urpx/tierSourceName tiers))))
        (let [peak (first (filter #(= "Peak" (:urpx/tierSourceName %)) tiers))
              summer-peak (first (:urpx/hasTOUPeriod peak))
              brackets (:urpx/hasTimeBracket summer-peak)]
          (is (= 1 (:urpx/touTierNumber peak)))
          (is (= "Summer Peak" (:urpx/name summer-peak)))
          (is (= 1 (count brackets)))
          (is (= "16:00:00" (:urpx/startTime (first brackets))))
          (is (= "21:00:00" (:urpx/endTime (first brackets)))))))

    (testing "prices include flat and TOU price sets"
      (let [prices (get-in plan [:urpx/hasRatePlanVersion :urpx/hasPrices])
            flat (:urpx/hasPrice prices)
            sets (:urpx/hasPriceSet prices)]
        (is (= 3 (count flat)))
        (is (= #{"Customer Charge" "Distribution" "Public Benefits"}
               (set (map :urpx/name flat))))
        (is (= 1 (count sets)))
        (is (= 6 (count (:urpx/hasPrice (first sets)))))
        (let [summer-peak (first (filter #(= "Commodity Summer Peak" (:urpx/name %))
                                         (:urpx/hasPrice (first sets))))]
          (is (= "0.23354" (:urpx/unitPrice summer-peak))))))))

(deftest prefixed-key-conversion
  (testing "via parse-rate-plan on a small inline document"
    (let [src (java.io.StringReader.
               "{\"@id\":\"urpx:foo\",\"@type\":\"urpx:Thing\",\"urpx:name\":\"x\",\"plain\":1}")
          parsed (core/parse-rate-plan src)]
      (is (= {:jsonld/id "urpx:foo"
              :jsonld/type "urpx:Thing"
              :urpx/name "x"
              :plain 1}
             parsed)))))

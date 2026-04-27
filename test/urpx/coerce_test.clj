(ns urpx.coerce-test
  (:require [clojure.test :refer [deftest is testing]]
            [urpx.coerce :as coerce]
            [urpx.schema :as schema]
            [urpx.test-fixtures :as fixtures])
  (:import [java.math BigDecimal]
           [java.time LocalDate LocalDateTime LocalTime]))

(def base-rate-plan-fixtures
  "Names of URPX test cases of @type urpx:RatePlan (excludes the
   RatePlanModifier docs, which need their own schema)."
  ["cpau-e-1-tou-2026-01-01"
   "cpau-e-1-2025-07-01"
   "pge-e-elec-2026-01-01"])

(deftest coerce-primitives-via-schema
  (testing "Decimal: string -> BigDecimal"
    (is (= (BigDecimal. "0.23354")
           (coerce/coerce schema/Decimal "0.23354"))))
  (testing "Date: string -> LocalDate"
    (is (= (LocalDate/of 2026 1 1)
           (coerce/coerce schema/Date "2026-01-01"))))
  (testing "Time: string -> LocalTime"
    (is (= (LocalTime/of 16 0)
           (coerce/coerce schema/Time "16:00:00"))))
  (testing "DateTime: string -> LocalDateTime"
    (is (= (LocalDateTime/of 2026 1 1 0 0)
           (coerce/coerce schema/DateTime "2026-01-01T00:00:00"))))
  (testing "MonthNumber: '--06' -> 6"
    (is (= 6 (coerce/coerce schema/MonthNumber "--06")))
    (is (= 12 (coerce/coerce schema/MonthNumber "--12"))))
  (testing "already-typed values pass through unchanged"
    (let [bd (BigDecimal. "1.5")]
      (is (identical? bd (coerce/coerce schema/Decimal bd))))))

(deftest coerce-cpau-tou-rate-plan
  (let [raw     (fixtures/load-raw "cpau-e-1-tou-2026-01-01")
        coerced (coerce/coerce-rate-plan raw)]

    (testing "the full plan validates against the RatePlan schema"
      (is (nil? (coerce/explain schema/RatePlan coerced))
          "no explanation should be returned for a valid plan"))

    (testing "top-level effective date is a LocalDate"
      (is (= (LocalDate/of 2026 1 1) (:urpx/initialEffectiveDate coerced))))

    (testing "RatePlanVersion's effectiveDateTime is a LocalDateTime"
      (is (= (LocalDateTime/of 2026 1 1 0 0)
             (get-in coerced [:urpx/hasRatePlanVersion :urpx/effectiveDateTime]))))

    (testing "Seasons have integer months"
      (let [seasons (get-in coerced [:urpx/hasRatePlanVersion
                                     :urpx/hasPlanElements
                                     :urpx/hasSeasons])
            summer (first (filter #(= "Summer" (:urpx/seasonName %)) seasons))]
        (is (= [6 7 8 9] (:urpx/month summer)))))

    (testing "TimeBrackets have LocalTime start/end"
      (let [bracket (-> coerced
                        (get-in [:urpx/hasRatePlanVersion
                                 :urpx/hasPlanElements
                                 :urpx/hasBillingModel
                                 :urpx/hasTOUSchedule
                                 :urpx/hasTOUTier])
                        first
                        :urpx/hasTOUPeriod
                        first
                        :urpx/hasTimeBracket
                        first)]
        (is (= (LocalTime/of 16 0) (:urpx/startTime bracket)))
        (is (= (LocalTime/of 21 0) (:urpx/endTime bracket)))))

    (testing "Prices have BigDecimal unitPrice"
      (let [prices (get-in coerced
                           [:urpx/hasRatePlanVersion
                            :urpx/hasPrices
                            :urpx/hasPriceSet
                            0
                            :urpx/hasPrice])
            summer-peak (first (filter #(= "Commodity Summer Peak" (:urpx/name %)) prices))]
        (is (= (BigDecimal. "0.23354") (:urpx/unitPrice summer-peak)))))

    (testing "Flat hasPriceDefinition (single map) is normalized to a vector"
      (let [ledgers (get-in coerced [:urpx/hasRatePlanVersion
                                     :urpx/hasPlanElements
                                     :urpx/hasBillingModel
                                     :urpx/hasLedger])
            distribution (first (filter #(= "Distribution (Delivery)" (:urpx/name %)) ledgers))]
        (is (vector? (:urpx/hasPriceDefinition distribution)))
        (is (= 1 (count (:urpx/hasPriceDefinition distribution))))
        (is (= "Distribution"
               (:urpx/name (first (:urpx/hasPriceDefinition distribution)))))))

    (testing "CalculationMethod intervalDuration parses to Duration"
      (let [ledgers (get-in coerced [:urpx/hasRatePlanVersion
                                     :urpx/hasPlanElements
                                     :urpx/hasBillingModel
                                     :urpx/hasLedger])
            customer (first (filter #(= "Customer Charge" (:urpx/name %)) ledgers))
            pd (first (:urpx/hasPriceDefinition customer))
            calc (:urpx/hasCalculationMethod pd)]
        (is (some? calc))
        (is (= (java.time.Period/parse "P1M") (:urpx/intervalDuration calc)))
        (is (= (BigDecimal. "1") (:urpx/baseQuantity pd)))))))

(deftest all-base-rate-plan-fixtures-validate
  (testing "every URPX RatePlan fixture coerces and validates against the schema"
    (doseq [name base-rate-plan-fixtures]
      (let [coerced (fixtures/load-rate-plan name)
            err (coerce/explain schema/RatePlan coerced)]
        (is (nil? err)
            (str name " should validate; got " (count (:errors err)) " errors"))))))

(def modifier-fixtures
  ["cpau-e-eec-1-2025-09-01"
   "cpau-e-nse-1-2025-09-01"
   "cpau-e-hra-2023-01-01"])

(deftest all-modifier-fixtures-validate
  (testing "every URPX RatePlanModifier fixture coerces and validates"
    (doseq [name modifier-fixtures]
      (let [coerced (fixtures/load-rate-plan-modifier name)
            err (coerce/explain schema/RatePlanModifier coerced)]
        (is (nil? err)
            (str name " should validate; got " (count (:errors err)) " errors"))))))

(deftest coerce-doc-dispatches-on-type
  (testing "coerce-doc handles RatePlan and RatePlanModifier docs"
    (let [base    (coerce/coerce-doc (fixtures/load-raw "cpau-e-1-tou-2026-01-01"))
          mod-doc (coerce/coerce-doc (fixtures/load-raw (first modifier-fixtures)))]
      (is (nil? (coerce/explain schema/RatePlan base)))
      (is (nil? (coerce/explain schema/RatePlanModifier mod-doc)))
      (is (nil? (coerce/coerce-doc {:jsonld/type "urpx:Unknown"}))
          "unknown @type returns nil")))

  (testing "EEC modifier carries a single applicableToRatePlan that normalizes to a vector"
    (let [m    (fixtures/load-rate-plan-modifier "cpau-e-eec-1-2025-09-01")
          refs (get-in m [:urpx/hasRatePlanModifierVersion :urpx/applicableToRatePlan])]
      (is (vector? refs))
      (is (= 1 (count refs)))
      (is (= "urpx:cpau-e-1" (:jsonld/id (first refs))))))

  (testing "NSE modifier applies to multiple base plans (vector preserved)"
    (let [m    (fixtures/load-rate-plan-modifier "cpau-e-nse-1-2025-09-01")
          refs (get-in m [:urpx/hasRatePlanModifierVersion :urpx/applicableToRatePlan])]
      (is (= ["urpx:cpau-e-1" "urpx:cpau-e-1-tou"] (mapv :jsonld/id refs))))))

(ns urpx.schema-test
  "Verify URPX malli schemas are well-formed and accept/reject the right shapes.

  These tests do NOT validate parsed-from-JSON-LD documents — coercion (URPX-p2y)
  is what turns the parser's string values into the typed shapes these schemas
  describe. Here we exercise the schemas directly with hand-built typed values."
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [urpx.schema :as s])
  (:import [java.math BigDecimal]
           [java.time Duration LocalDate LocalDateTime LocalTime]))

(deftest all-schemas-compile
  (testing "every public schema in urpx.schema is a valid malli schema"
    (doseq [[sym v] (ns-publics 'urpx.schema)
            :let [val (deref v)]
            :when (or (vector? val) (m/schema? val))]
      (is (m/schema val) (str sym " should compile to a malli schema")))))

(deftest ref-and-primitives
  (is (m/validate s/Ref {:jsonld/id "urpx:foo"}))
  (is (not (m/validate s/Ref {})))

  (is (m/validate s/Decimal (BigDecimal. "0.23354")))
  (is (not (m/validate s/Decimal "0.23354")))

  (is (m/validate s/Date (LocalDate/of 2026 1 1)))
  (is (m/validate s/DateTime (LocalDateTime/of 2026 1 1 0 0)))
  (is (m/validate s/Time (LocalTime/of 16 0)))
  (is (m/validate s/IsoDuration (Duration/parse "PT1H")))

  (is (m/validate s/MonthNumber 6))
  (is (not (m/validate s/MonthNumber 0)))
  (is (not (m/validate s/MonthNumber 13))))

(deftest season-shape
  (let [summer {:jsonld/type "urpx:Season"
                :jsonld/id "urpx:cpau-tou-season-summer"
                :urpx/seasonName "Summer"
                :urpx/month [6 7 8 9]}]
    (is (m/validate s/Season summer))
    (testing "string month rejected (must be coerced to int)"
      (is (not (m/validate s/Season (assoc summer :urpx/month ["--06"])))))
    (testing "type tag is fixed"
      (is (not (m/validate s/Season (assoc summer :jsonld/type "urpx:Other")))))))

(deftest tou-bracket-and-period
  (let [bracket {:jsonld/type "urpx:TimeBracket"
                 :urpx/startTime (LocalTime/of 16 0)
                 :urpx/endTime (LocalTime/of 21 0)
                 :urpx/appliesInSeason {:jsonld/id "urpx:cpau-tou-season-summer"}
                 :urpx/dayType {:jsonld/id "urpx:allDays"}}
        period {:jsonld/type "urpx:TOUPeriod"
                :jsonld/id "urpx:cpau-tou-period-summer-peak"
                :urpx/name "Summer Peak"
                :urpx/touPeriodNumber 1
                :urpx/hasTimeBracket [bracket]}]
    (is (m/validate s/TimeBracket bracket))
    (is (m/validate s/TOUPeriod period))
    (testing "string startTime rejected"
      (is (not (m/validate s/TimeBracket (assoc bracket :urpx/startTime "16:00:00")))))))

(deftest price-shape
  (let [price {:jsonld/type "urpx:Price"
               :jsonld/id "urpx:cpau-tou-price-commodity-summer-peak"
               :urpx/name "Commodity Summer Peak"
               :urpx/unitPrice (BigDecimal. "0.23354")
               :urpx/referencesPriceDefinition
               {:jsonld/id "urpx:cpau-tou-pd-commodity-summer-peak"}}]
    (is (m/validate s/Price price))
    (testing "string unitPrice rejected"
      (is (not (m/validate s/Price (assoc price :urpx/unitPrice "0.23354")))))
    (testing "missing PriceDefinition reference rejected"
      (is (not (m/validate s/Price (dissoc price :urpx/referencesPriceDefinition)))))))

(deftest rate-plan-minimal
  (let [version {:jsonld/type "urpx:RatePlanVersion"
                 :urpx/versionNumber "2026-01-01"
                 :urpx/effectiveDateTime (LocalDateTime/of 2026 1 1 0 0)
                 :urpx/priceCurrency "USD"
                 :urpx/hasPlanElements
                 {:jsonld/type "urpx:PlanElements"
                  :urpx/hasBillingModel
                  {:jsonld/type "urpx:BillingModel"
                   :urpx/hasLedger []}}
                 :urpx/hasPrices {:jsonld/type "urpx:Prices"}}
        plan {:jsonld/type "urpx:RatePlan"
              :urpx/ratePlanIdentifier "E-1 TOU"
              :urpx/name "Residential Electric Time of Use Service"
              :urpx/initialEffectiveDate (LocalDate/of 2026 1 1)
              :urpx/hasRatePlanVersion version}]
    (is (m/validate s/RatePlan plan))
    (testing "missing version is rejected"
      (is (not (m/validate s/RatePlan (dissoc plan :urpx/hasRatePlanVersion)))))))

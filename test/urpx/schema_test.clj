(ns urpx.schema-test
  "Verify URPX malli schemas are well-formed and accept/reject the right shapes.

  These tests do NOT validate parsed-from-JSON-LD documents — coercion is what
  turns the parser's string values into the typed shapes these schemas
  describe. Here we exercise the schemas directly with hand-built typed values."
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [urpx.coerce :as coerce]
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
                :jsonld/id "pge:season-summer"
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
                 :urpx/appliesInSeason {:jsonld/id "pge:season-summer"}
                 :urpx/dayType {:jsonld/id "urpx:allDays"}}
        period {:jsonld/type "urpx:TOUPeriod"
                :jsonld/id "pge:period-summer-peak"
                :urpx/name "Summer Peak"
                :urpx/touPeriodNumber 1
                :urpx/hasTimeBracket [bracket]}]
    (is (m/validate s/TimeBracket bracket))
    (is (m/validate s/TOUPeriod period))
    (testing "v0.2.0: dayType is now required on TimeBracket"
      (is (not (m/validate s/TimeBracket (dissoc bracket :urpx/dayType)))))
    (testing "string startTime rejected"
      (is (not (m/validate s/TimeBracket (assoc bracket :urpx/startTime "16:00:00")))))))

(deftest service-tier-shape-tightened
  (testing "v0.2.0: ServiceTier requires lowerBound, upperBound, and both operators"
    (let [tier {:jsonld/type "urpx:ServiceTier"
                :jsonld/id "pge:tier-1"
                :urpx/serviceTierNumber 1
                :urpx/name "Tier 1"
                :urpx/lowerBound (BigDecimal. "0")
                :urpx/upperBound (BigDecimal. "13.5")
                :urpx/lowerBoundOperator {:jsonld/id "urpx:greaterThanOrEqualOperator"}
                :urpx/upperBoundOperator {:jsonld/id "urpx:lessThanOrEqualOperator"}}]
      (is (m/validate s/ServiceTier tier))
      (is (not (m/validate s/ServiceTier (dissoc tier :urpx/lowerBound))))
      (is (not (m/validate s/ServiceTier (dissoc tier :urpx/lowerBoundOperator)))))))

(deftest price-shape
  (let [price {:jsonld/type "urpx:Price"
               :jsonld/id "pge:price-generation"
               :urpx/name "Generation"
               :urpx/unitPrice (BigDecimal. "0.12855")
               :urpx/referencesPriceDefinition {:jsonld/id "pge:pd-generation"}}]
    (is (m/validate s/Price price))
    (testing "string unitPrice rejected"
      (is (not (m/validate s/Price (assoc price :urpx/unitPrice "0.12855")))))
    (testing "missing PriceDefinition reference rejected"
      (is (not (m/validate s/Price (dissoc price :urpx/referencesPriceDefinition)))))))

(deftest condition-expression-recursive-tree
  (testing "ConditionExpression accepts a flat ComparisonNode root"
    (let [ce {:jsonld/type "urpx:ConditionExpression"
              :urpx/rootNode
              {:jsonld/type "urpx:ComparisonNode"
               :urpx/operator {:jsonld/id "urpx:equalsOperator"}
               :urpx/leftOperand
               {:jsonld/type "urpx:ObjectOperandNode"
                :urpx/conditionProperty {:jsonld/id "urpx:hasCustomerKind"}}
               :urpx/rightOperand
               {:jsonld/type "urpx:ObjectOperandNode"
                :urpx/referencesValue {:jsonld/id "urpx:residentialKind"}}}}]
      (is (m/validate s/ConditionExpression ce))))
  (testing "ConditionExpression accepts nested BooleanOperationNode"
    (let [ce {:jsonld/type "urpx:ConditionExpression"
              :urpx/rootNode
              {:jsonld/type "urpx:BooleanOperationNode"
               :urpx/operator {:jsonld/id "urpx:andOperator"}
               :urpx/operandNodes
               [{:jsonld/type "urpx:ComparisonNode"
                 :urpx/operator {:jsonld/id "urpx:notEqualsOperator"}
                 :urpx/leftOperand {:jsonld/type "urpx:CustomOperandNode"
                                    :urpx/customConditionProperty "departureVintage"}
                 :urpx/rightOperand {:jsonld/type "urpx:CustomOperandNode"
                                     :urpx/integerValue 2009}}
                {:jsonld/type "urpx:ComparisonNode"
                 :urpx/operator {:jsonld/id "urpx:notEqualsOperator"}
                 :urpx/leftOperand {:jsonld/type "urpx:CustomOperandNode"
                                    :urpx/customConditionProperty "departureVintage"}
                 :urpx/rightOperand {:jsonld/type "urpx:CustomOperandNode"
                                     :urpx/integerValue 2010}}]}}]
      (is (m/validate s/ConditionExpression ce)))))

(deftest rate-plan-minimal
  (testing "v0.2.0: ratePlanUniqueIdentifier is required"
    (let [version {:jsonld/type "urpx:RatePlanVersion"
                   :urpx/effectiveDateTime (LocalDateTime/of 2026 3 1 0 0)
                   :urpx/hasPlanElements
                   {:jsonld/type "urpx:PlanElements"
                    :urpx/hasMetricInput
                    [{:jsonld/type "urpx:MetricInput"
                      :urpx/name "kWh"
                      :urpx/metricInputType {:jsonld/id "urpx:measuredMetricInput"}}]
                    :urpx/hasBillingModel
                    {:jsonld/type "urpx:BillingModel"
                     :urpx/hasPriceDefinition []}}
                   :urpx/hasPrices {:jsonld/type "urpx:Prices"}}
          plan {:jsonld/type "urpx:RatePlan"
                :urpx/ratePlanUniqueIdentifier "E-1"
                :urpx/name "Residential Services"
                :urpx/initialEffectiveDate (LocalDate/of 2026 3 1)
                :urpx/hasRatePlanVersion version}]
      (is (m/validate s/RatePlan plan))
      (testing "missing ratePlanUniqueIdentifier rejected"
        (is (not (m/validate s/RatePlan (dissoc plan :urpx/ratePlanUniqueIdentifier)))))
      (testing "missing version rejected"
        (is (not (m/validate s/RatePlan (dissoc plan :urpx/hasRatePlanVersion))))))))

(deftest rate-plan-version-modifier-options
  (testing "URPX-1nq / Decisions #084 + #090: RatePlanVersion accepts
            hasModifierOptions and hasModifierVersionOptions, both as vectors
            of Refs (single-or-vec→vec normalizes the JSON-LD shape)."
    (let [version-base {:jsonld/type "urpx:RatePlanVersion"
                        :urpx/effectiveDateTime (LocalDateTime/of 2026 3 1 0 0)
                        :urpx/hasPlanElements
                        {:jsonld/type "urpx:PlanElements"
                         :urpx/hasMetricInput
                         [{:jsonld/type "urpx:MetricInput"
                           :urpx/name "kWh"
                           :urpx/metricInputType {:jsonld/id "urpx:measuredMetricInput"}}]
                         :urpx/hasBillingModel
                         {:jsonld/type "urpx:BillingModel"
                          :urpx/hasPriceDefinition []}}
                        :urpx/hasPrices {:jsonld/type "urpx:Prices"}}
          version-with-options
          (assoc version-base
                 :urpx/hasModifierOptions
                 [{:jsonld/id "pge:e-1-vintage-pcia"}]
                 :urpx/hasModifierVersionOptions
                 [{:jsonld/id "pge:e-1-vintage-pcia-2026-03-01"}])]
      (is (m/validate s/RatePlanVersion version-with-options))
      (testing "still validates without the new properties (both optional)"
        (is (m/validate s/RatePlanVersion version-base)))))
  (testing "single-or-vec decoder normalizes a single Ref to a one-element vector"
    (let [raw {:jsonld/type "urpx:RatePlanVersion"
               :urpx/effectiveDateTime "2026-03-01T00:00:00"
               :urpx/hasModifierOptions {:jsonld/id "pge:e-1-vintage-pcia"}
               :urpx/hasModifierVersionOptions {:jsonld/id "pge:e-1-vintage-pcia-2026-03-01"}
               :urpx/hasPlanElements
               {:jsonld/type "urpx:PlanElements"
                :urpx/hasMetricInput
                [{:jsonld/type "urpx:MetricInput"
                  :urpx/name "kWh"
                  :urpx/metricInputType {:jsonld/id "urpx:measuredMetricInput"}}]
                :urpx/hasBillingModel
                {:jsonld/type "urpx:BillingModel"
                 :urpx/hasPriceDefinition []}}
               :urpx/hasPrices {:jsonld/type "urpx:Prices"}}
          coerced (coerce/coerce s/RatePlanVersion raw)]
      (is (= [{:jsonld/id "pge:e-1-vintage-pcia"}]
             (:urpx/hasModifierOptions coerced)))
      (is (= [{:jsonld/id "pge:e-1-vintage-pcia-2026-03-01"}]
             (:urpx/hasModifierVersionOptions coerced))))))

(deftest rate-plan-modifier-no-isOptional
  (testing "v0.2.0: RatePlanModifier no longer has urpx:isOptional and uses
            modifierUniqueIdentifier (was per-version in v0.1.x)"
    (let [version {:jsonld/type "urpx:RatePlanModifierVersion"
                   :urpx/modifierVersionNumber "2026-03-01"
                   :urpx/effectiveDateTime (LocalDateTime/of 2026 3 1 0 0)
                   :urpx/applicableToRatePlan [{:jsonld/id "pge:e-1"}]
                   :urpx/hasPlanElements
                   {:jsonld/type "urpx:PlanElements"
                    :urpx/hasMetricInput
                    [{:jsonld/type "urpx:MetricInput"
                      :urpx/name "kWh"
                      :urpx/metricInputType {:jsonld/id "urpx:measuredMetricInput"}}]
                    :urpx/hasBillingModel
                    {:jsonld/type "urpx:BillingModel"
                     :urpx/hasPriceDefinition []}}
                   :urpx/hasPrices {:jsonld/type "urpx:Prices"}}
          modifier {:jsonld/type "urpx:RatePlanModifier"
                    :urpx/modifierUniqueIdentifier "E-1-VPCIA"
                    :urpx/name "Vintage PCIA"
                    :urpx/initialEffectiveDate (LocalDate/of 2009 1 1)
                    :urpx/hasRatePlanModifierVersion version}]
      (is (m/validate s/RatePlanModifier modifier))
      (testing "missing modifierUniqueIdentifier rejected"
        (is (not (m/validate s/RatePlanModifier
                             (dissoc modifier :urpx/modifierUniqueIdentifier)))))
      (testing "URPX-g7z: applicableToRatePlan is optional per SHACL — standalone
                modifier filings without it must still validate"
        (let [standalone (assoc modifier
                                :urpx/hasRatePlanModifierVersion
                                (dissoc version :urpx/applicableToRatePlan))]
          (is (m/validate s/RatePlanModifier standalone)))))))

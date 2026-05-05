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

;; ---------------------------------------------------------------------------
;; ProfileAlternatives + PriceSetAlternatives (URPX-vlf)
;; ---------------------------------------------------------------------------

(def ^:private trivial-condition-expression
  "A minimal valid urpx:ConditionExpression — a single ComparisonNode with
   two operand nodes. Used to satisfy the required-field constraint on
   ProfileAlternative.hasConditionExpression in the tests below; URPX-vlf
   doesn't yet evaluate condition expressions."
  {:jsonld/type "urpx:ConditionExpression"
   :urpx/rootNode
   {:jsonld/type "urpx:ComparisonNode"
    :urpx/operator {:jsonld/id "urpx:eq"}
    :urpx/leftOperand  {:jsonld/type "urpx:CustomOperandNode"
                        :urpx/customConditionProperty "incomeTier"}
    :urpx/rightOperand {:jsonld/type "urpx:CustomOperandNode"
                        :urpx/stringValue "CARE"}}})

(deftest alternative-dimension-shape
  (let [d {:jsonld/type "urpx:AlternativeDimension"
           :urpx/dimensionDisplayName "Income Tier"
           :urpx/dimensionProperty "https://schema.urpx.org/incomeTier"
           :urpx/hasPossibleValues [{:jsonld/id "urpx:incomeTierCare"}
                                    {:jsonld/id "urpx:incomeTierFera"}
                                    {:jsonld/id "urpx:incomeTierStandard"}]}]
    (is (m/validate s/AlternativeDimension d))
    (testing "single-or-vec decoder normalizes a single hasPossibleValue"
      (let [coerced (coerce/coerce s/AlternativeDimension
                                   (assoc d :urpx/hasPossibleValues
                                          {:jsonld/id "urpx:onlyOne"}))]
        (is (vector? (:urpx/hasPossibleValues coerced)))
        (is (= 1 (count (:urpx/hasPossibleValues coerced))))))
    (testing "customDimensionProperty fallback (urpx-org/urpx#30): exactly
              one of dimensionProperty / customDimensionProperty per SHACL,
              both shapes pass Malli validation"
      (let [d-custom (-> d
                         (dissoc :urpx/dimensionProperty)
                         (assoc :urpx/customDimensionProperty "departureVintage"))]
        (is (m/validate s/AlternativeDimension d-custom)
            "customDimensionProperty alone validates")
        (is (m/validate s/AlternativeDimension
                        (dissoc d :urpx/dimensionProperty))
            "neither property validates at Malli level (SHACL catches the xone)")))))

(deftest profile-alternatives-shape
  (let [pa-care {:jsonld/type "urpx:ProfileAlternative"
                 :urpx/name "CARE"
                 :urpx/hasConditionExpression trivial-condition-expression}
        pa-std  {:jsonld/type "urpx:ProfileAlternative"
                 :urpx/name "Standard"
                 :urpx/hasConditionExpression trivial-condition-expression}
        pas {:jsonld/type "urpx:ProfileAlternatives"
             :urpx/name "BSC Income Tier"
             :urpx/hasAlternativeDimensions
             {:jsonld/type "urpx:AlternativeDimensions"
              :urpx/hasAlternativeDimension
              [{:jsonld/type "urpx:AlternativeDimension"
                :urpx/dimensionDisplayName "Income Tier"
                :urpx/dimensionProperty "https://schema.urpx.org/incomeTier"
                :urpx/hasPossibleValues [{:jsonld/id "urpx:incomeTierCare"}
                                         {:jsonld/id "urpx:incomeTierStandard"}]}]}
             :urpx/hasProfileAlternative [pa-care pa-std]}]
    (is (m/validate s/ProfileAlternatives pas))
    (testing "missing required name is rejected"
      (is (not (m/validate s/ProfileAlternatives (dissoc pas :urpx/name)))))))

(deftest price-set-alternatives-shape
  (let [psa {:jsonld/type "urpx:PriceSetAlternative"
             :urpx/alternativeLabel "CARE"
             :urpx/referencesProfileAlternative {:jsonld/id "x:bsc-care"}
             :urpx/hasPrice [{:jsonld/type "urpx:Price"
                              :urpx/unitPrice (BigDecimal. "0.20")
                              :urpx/referencesPriceDefinition {:jsonld/id "x:pd-bsc"}}]}
        psas {:jsonld/type "urpx:PriceSetAlternatives"
              :urpx/name "BSC Prices"
              :urpx/referencesProfileAlternatives {:jsonld/id "x:bsc-income-tier"}
              :urpx/hasPriceSetAlternative [psa]}]
    (is (m/validate s/PriceSetAlternatives psas))
    (testing "PriceSetAlternative requires referencesProfileAlternative"
      (is (not (m/validate s/PriceSetAlternative
                           (dissoc psa :urpx/referencesProfileAlternative)))))
    (testing "PriceSetAlternative requires hasPrice (1..*)"
      (is (not (m/validate s/PriceSetAlternative
                           (dissoc psa :urpx/hasPrice)))))))

(deftest plan-elements-and-prices-accept-alternatives
  (testing "PlanElements gains hasProfileAlternatives; Prices gains hasPriceSetAlternatives"
    (let [pas {:jsonld/type "urpx:ProfileAlternatives"
               :urpx/name "BSC Income Tier"
               :urpx/hasAlternativeDimensions
               {:jsonld/type "urpx:AlternativeDimensions"
                :urpx/hasAlternativeDimension
                [{:jsonld/type "urpx:AlternativeDimension"
                  :urpx/dimensionDisplayName "Income Tier"
                  :urpx/dimensionProperty "https://schema.urpx.org/incomeTier"
                  :urpx/hasPossibleValues [{:jsonld/id "urpx:incomeTierCare"}
                                           {:jsonld/id "urpx:incomeTierStandard"}]}]}
               :urpx/hasProfileAlternative
               [{:jsonld/type "urpx:ProfileAlternative" :urpx/name "CARE"
                 :urpx/hasConditionExpression trivial-condition-expression}
                {:jsonld/type "urpx:ProfileAlternative" :urpx/name "Standard"
                 :urpx/hasConditionExpression trivial-condition-expression}]}
          pe {:jsonld/type "urpx:PlanElements"
              :urpx/hasMetricInput
              [{:jsonld/type "urpx:MetricInput"
                :urpx/name "kWh"
                :urpx/metricInputType {:jsonld/id "urpx:measuredMetricInput"}}]
              :urpx/hasBillingModel
              {:jsonld/type "urpx:BillingModel"
               :urpx/hasPriceDefinition []}
              :urpx/hasProfileAlternatives [pas]}
          prices {:jsonld/type "urpx:Prices"
                  :urpx/hasPriceSetAlternatives
                  [{:jsonld/type "urpx:PriceSetAlternatives"
                    :urpx/name "BSC Prices"
                    :urpx/referencesProfileAlternatives {:jsonld/id "x:bsc-income-tier"}
                    :urpx/hasPriceSetAlternative
                    [{:jsonld/type "urpx:PriceSetAlternative"
                      :urpx/referencesProfileAlternative {:jsonld/id "x:bsc-care"}
                      :urpx/hasPrice [{:jsonld/type "urpx:Price"
                                       :urpx/unitPrice (BigDecimal. "0.20")
                                       :urpx/referencesPriceDefinition
                                       {:jsonld/id "x:pd-bsc"}}]}]}]}]
      (is (m/validate s/PlanElements pe))
      (is (m/validate s/Prices prices)))))

(deftest billing-model-accepts-multiple-service-tiers
  (testing "URPX-6hf: BillingModel.hasServiceTiers is a vector — supports
            matrix-of-ServiceTiers patterns (one ServiceTiers per (territory,
            code) cell of a baseline-allowance matrix, e.g. PG&E E-TOU-C
            baseline-tier modifier with 20 ServiceTiers under one BillingModel).
            single-or-vec→vec keeps single-ServiceTiers fixtures compatible."
    (let [tier {:jsonld/type "urpx:ServiceTier"
                :urpx/serviceTierNumber 1
                :urpx/lowerBound (BigDecimal. "0")
                :urpx/upperBound (BigDecimal. "100")
                :urpx/lowerBoundOperator {:jsonld/id "urpx:greaterThanOrEqualOperator"}
                :urpx/upperBoundOperator {:jsonld/id "urpx:lessThanOrEqualOperator"}}
          st-T (fn [territory]
                 {:jsonld/type "urpx:ServiceTiers"
                  :jsonld/id (str "x:st-" territory)
                  :urpx/referencesMetricInput [{:jsonld/id "x:mi-energy"}]
                  :urpx/hasServiceTier [tier]})
          billing {:jsonld/type "urpx:BillingModel"
                   :urpx/hasServiceTiers [(st-T "T") (st-T "Q") (st-T "X")]
                   :urpx/hasPriceDefinition []}]
      (is (m/validate s/BillingModel billing))
      (testing "single-or-vec: one map auto-coerces to a one-element vector"
        (let [coerced (coerce/coerce s/BillingModel
                                     (assoc billing :urpx/hasServiceTiers (st-T "T")))]
          (is (vector? (:urpx/hasServiceTiers coerced)))
          (is (= 1 (count (:urpx/hasServiceTiers coerced)))))))))

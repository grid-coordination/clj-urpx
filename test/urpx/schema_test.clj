(ns urpx.schema-test
  "Verify URPX malli schemas are well-formed and accept/reject the right shapes.

  These tests do NOT validate parsed-from-JSON-LD documents — coercion is what
  turns the parser's string values into the typed shapes these schemas
  describe. Here we exercise the schemas directly with hand-built typed values."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
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

(deftest service-bracket-shape-flexible-bounds
  (testing "v0.2.0 + 423e83f: ServiceBracket carries the bounds + operators
            that previously lived inline on ServiceTier. SHACL enforces the
            sh:or three branches (lower-only, upper-only, both); Malli stays
            open-world."
    (let [both {:jsonld/type "urpx:ServiceBracket"
                :urpx/lowerBound (BigDecimal. "0")
                :urpx/upperBound (BigDecimal. "13.5")
                :urpx/lowerBoundOperator {:jsonld/id "urpx:greaterThanOrEqualOperator"}
                :urpx/upperBoundOperator {:jsonld/id "urpx:lessThanOrEqualOperator"}}
          lower-only {:jsonld/type "urpx:ServiceBracket"
                      :urpx/lowerBound (BigDecimal. "400")
                      :urpx/lowerBoundOperator {:jsonld/id "urpx:greaterThanOperator"}}
          upper-only {:jsonld/type "urpx:ServiceBracket"
                      :urpx/upperBound (BigDecimal. "100")
                      :urpx/upperBoundOperator {:jsonld/id "urpx:lessThanOrEqualOperator"}}
          with-season (assoc both :urpx/appliesInSeason
                             [{:jsonld/id "pge:season-summer"}])
          with-profile-alt (assoc both :urpx/referencesProfileAlternative
                                  {:jsonld/id "pge:pa-territory-p-code-b"})]
      (is (m/validate s/ServiceBracket both))
      (is (m/validate s/ServiceBracket lower-only)
          "lower-bound-only ServiceBracket validates (Tier-Top after dropping 999999)")
      (is (m/validate s/ServiceBracket upper-only)
          "upper-bound-only ServiceBracket validates (Tier 1 with no explicit lower)")
      (is (m/validate s/ServiceBracket with-season))
      (is (m/validate s/ServiceBracket with-profile-alt)))))

(deftest service-tier-shape-with-bracket
  (testing "v0.2.0 + 423e83f: ServiceTier carries only logical identity
            (serviceTierNumber + optional name) plus the required
            hasServiceBracket attaching 1+ bound-bearing brackets."
    (let [bracket {:jsonld/type "urpx:ServiceBracket"
                   :urpx/lowerBound (BigDecimal. "0")
                   :urpx/upperBound (BigDecimal. "13.5")
                   :urpx/lowerBoundOperator {:jsonld/id "urpx:greaterThanOrEqualOperator"}
                   :urpx/upperBoundOperator {:jsonld/id "urpx:lessThanOrEqualOperator"}}
          tier {:jsonld/type "urpx:ServiceTier"
                :jsonld/id "pge:tier-1"
                :urpx/serviceTierNumber 1
                :urpx/name "Tier 1"
                :urpx/hasServiceBracket [bracket]}]
      (is (m/validate s/ServiceTier tier))
      (is (not (m/validate s/ServiceTier (dissoc tier :urpx/hasServiceBracket)))
          "missing hasServiceBracket fails Malli validation"))
    (testing "single-or-vec decoder normalizes a single hasServiceBracket"
      (let [coerced (coerce/coerce
                     s/ServiceTier
                     {:jsonld/type "urpx:ServiceTier"
                      :urpx/serviceTierNumber 1
                      :urpx/hasServiceBracket
                      {:jsonld/type "urpx:ServiceBracket"
                       :urpx/upperBound (BigDecimal. "100")
                       :urpx/upperBoundOperator {:jsonld/id "urpx:lessThanOrEqualOperator"}}})]
        (is (vector? (:urpx/hasServiceBracket coerced)))
        (is (= 1 (count (:urpx/hasServiceBracket coerced))))))))

(deftest service-tiers-shape-seasonal-and-profile-alternative
  (testing "v0.2.0 + 0994aec: ServiceTiers accepts :urpx/appliesInSeason
            (vector of Refs, single-or-vec normalized) and
            :urpx/referencesProfileAlternative (single Ref)."
    (let [tier {:jsonld/type "urpx:ServiceTier"
                :urpx/serviceTierNumber 1
                :urpx/name "Baseline"
                :urpx/hasServiceBracket
                [{:jsonld/type "urpx:ServiceBracket"
                  :urpx/upperBound (BigDecimal. "100")
                  :urpx/upperBoundOperator {:jsonld/id "urpx:lessThanOrEqualOperator"}}]}
          base {:jsonld/type "urpx:ServiceTiers"
                :jsonld/id "pge:tiers-baseline-p-b-summer"
                :urpx/hasServiceTier [tier]}
          with-season (assoc base :urpx/appliesInSeason
                             [{:jsonld/id "pge:season-summer"}])
          with-profile-alt (assoc base :urpx/referencesProfileAlternative
                                  {:jsonld/id "pge:pa-territory-p-code-b"})]
      (is (m/validate s/ServiceTiers base))
      (is (m/validate s/ServiceTiers with-season))
      (is (m/validate s/ServiceTiers with-profile-alt))
      (testing "appliesInSeason single-or-vec decoder"
        (let [coerced (coerce/coerce s/ServiceTiers
                                     (assoc base :urpx/appliesInSeason
                                            {:jsonld/id "pge:season-summer"}))]
          (is (vector? (:urpx/appliesInSeason coerced)))
          (is (= 1 (count (:urpx/appliesInSeason coerced)))))))))

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
      (testing "v0.5.1: a RatePlan with no nested version is LEGAL. sh:minCount 0
                on urpx-shacl:RatePlanShape at 95eb8e22; the ontology calls
                RatePlan the stable identifier with no pricing content, and an
                un-nested filing states the link as forRatePlan on a sibling
                snapshot instead. This assertion previously required the nesting."
        (is (m/validate s/RatePlan (dissoc plan :urpx/hasRatePlanVersion))))
      (testing "a RatePlanVersion with no effectiveDateTime is LEGAL too, and
                always was upstream: sh:minCount 0 at every tag from v0.2.1 on"
        (is (m/validate s/RatePlan
                        (assoc plan :urpx/hasRatePlanVersion
                               (dissoc version :urpx/effectiveDateTime))))))))

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
                :urpx/hasServiceBracket
                [{:jsonld/type "urpx:ServiceBracket"
                  :urpx/lowerBound (BigDecimal. "0")
                  :urpx/upperBound (BigDecimal. "100")
                  :urpx/lowerBoundOperator {:jsonld/id "urpx:greaterThanOrEqualOperator"}
                  :urpx/upperBoundOperator {:jsonld/id "urpx:lessThanOrEqualOperator"}}]}
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

;; ---------------------------------------------------------------------------
;; hasApplicabilityRule accepts an inline rule or a bare @id reference
;; ---------------------------------------------------------------------------

(deftest applicability-rule-inline-or-bare-ref
  (let [inline {:jsonld/type "urpx:ApplicabilityRule"
                :jsonld/id "pge:nbt-ar-m01"
                :urpx/month [1 2]}
        bare   {:jsonld/id "pge:nbt-ar-m01"}
        price  {:jsonld/type "urpx:Price"
                :urpx/unitPrice (BigDecimal. "0.12855")
                :urpx/referencesPriceDefinition {:jsonld/id "pge:pd-generation"}}
        psa    {:jsonld/type "urpx:PriceSetAlternative"
                :urpx/referencesProfileAlternative {:jsonld/id "x:bsc-care"}
                :urpx/hasPrice [price]}]
    (testing "Price.hasApplicabilityRule takes an inline rule, a bare @id ref,
              or a mix of the two in one vector"
      (is (m/validate s/Price (assoc price :urpx/hasApplicabilityRule [inline])))
      (is (m/validate s/Price (assoc price :urpx/hasApplicabilityRule [bare])))
      (is (m/validate s/Price (assoc price :urpx/hasApplicabilityRule [inline bare]))))
    (testing "PriceSetAlternative.hasApplicabilityRule takes the same two shapes"
      (is (m/validate s/PriceSetAlternative
                      (assoc psa :urpx/hasApplicabilityRule [inline])))
      (is (m/validate s/PriceSetAlternative
                      (assoc psa :urpx/hasApplicabilityRule [bare]))))
    (testing "an inline rule still decodes its xsd:gMonth months through the
              richer schema; a bare ref passes through untouched"
      (let [coerced (coerce/coerce
                     s/Price
                     (assoc price :urpx/hasApplicabilityRule
                            [{:jsonld/type "urpx:ApplicabilityRule"
                              :jsonld/id "pge:nbt-ar-m01"
                              :urpx/month ["--01" "--02"]}
                             bare]))
            [decoded-inline decoded-ref] (:urpx/hasApplicabilityRule coerced)]
        (is (= [1 2] (:urpx/month decoded-inline)))
        (is (= bare decoded-ref))))
    (testing "a map that is neither a typed rule nor an @id reference is rejected"
      (is (not (m/validate s/Price
                           (assoc price :urpx/hasApplicabilityRule
                                  [{:urpx/name "neither"}])))))
    (testing "an @id alone does not buy a pass: the reference arm is closed, so a
              node carrying @id plus anything else must satisfy ApplicabilityRule.
              With an open Ref as the second arm these all validated, because the
              permissive arm matched first and the rule arm was never reached."
      (doseq [bad [{:jsonld/id "x:ar" :jsonld/type "urpx:TotalNonsense"}
                   {:jsonld/type "urpx:ApplicabilityRule" :jsonld/id "x:ar"
                    :urpx/month [13]}
                   {:jsonld/id "x:ar" :urpx/month "not-a-vector"}]]
        (is (not (m/validate s/Price (assoc price :urpx/hasApplicabilityRule [bad])))
            (str "must reject: " (pr-str bad)))
        (is (not (m/validate s/PriceSetAlternative
                             (assoc psa :urpx/hasApplicabilityRule [bad])))
            (str "must reject on PriceSetAlternative too: " (pr-str bad)))))
    (testing "ExclusiveRef itself: a bare reference passes, a reference carrying
              any other key does not"
      (is (m/validate s/ExclusiveRef {:jsonld/id "x:ar"}))
      (is (not (m/validate s/ExclusiveRef {:jsonld/id "x:ar" :urpx/month [1]})))
      (is (not (m/validate s/ExclusiveRef {:jsonld/type "urpx:ApplicabilityRule"})))
      (testing "a node asserting an @type is a node, not a reference: it must
                validate through the entity arm, so a real rule type passes and
                a bogus one does not"
        (is (m/validate s/Price
                        (assoc price :urpx/hasApplicabilityRule
                               [{:jsonld/id "x:ar" :jsonld/type "urpx:ApplicabilityRule"}])))
        (is (not (m/validate s/ExclusiveRef
                             {:jsonld/id "x:ar" :jsonld/type "urpx:ApplicabilityRule"})))))))

;; ---------------------------------------------------------------------------
;; v0.5.1 coverage machinery (CoverageClaim, Evaluation, ModelExclusion,
;; PriceDefinitionReference) on the version snapshots
;; ---------------------------------------------------------------------------

(def ^:private minimal-version
  {:jsonld/type "urpx:RatePlanVersion"
   :urpx/effectiveDateTime (LocalDateTime/of 2026 1 1 0 0)
   :urpx/hasPlanElements
   {:jsonld/type "urpx:PlanElements"
    :urpx/hasMetricInput [{:jsonld/type "urpx:MetricInput"
                           :urpx/name "kWh"
                           :urpx/metricInputType {:jsonld/id "urpx:measuredMetricInput"}}]
    :urpx/hasBillingModel {:jsonld/type "urpx:BillingModel"
                           :urpx/hasPriceDefinition []}}
   :urpx/hasPrices {:jsonld/type "urpx:Prices"}})

(deftest coverage-claim-on-version-snapshots
  (let [claim {:jsonld/type "urpx:CoverageClaim"
               :urpx/completenessResult {:jsonld/id "urpx:complete"}}]
    (testing "a snapshot carrying forRatePlan + an inline CoverageClaim validates"
      (is (m/validate s/RatePlanVersion
                      (assoc minimal-version
                             :urpx/forRatePlan {:jsonld/id "urpx:rp-1"}
                             :urpx/hasCoverageClaim claim))))
    (testing "hasCoverageClaim given as a bare @id reference also validates"
      (is (m/validate s/RatePlanVersion
                      (assoc minimal-version
                             :urpx/forRatePlan {:jsonld/id "urpx:rp-1"}
                             :urpx/hasCoverageClaim {:jsonld/id "urpx:cc-1"}))))
    (testing "both stay OPTIONAL until the last migration wave: v0.5.1 requires
              them 1..1, but the whole v0.2.x corpus omits them"
      (is (m/validate s/RatePlanVersion minimal-version)))
    (testing "a malformed claim cannot slip through the reference arm, because
              that arm is ExclusiveRef rather than the open Ref"
      (is (not (m/validate s/RatePlanVersion
                           (assoc minimal-version :urpx/hasCoverageClaim
                                  {:jsonld/id "urpx:cc-1"
                                   :urpx/completenessResult "not-a-ref"})))))
    (testing "RatePlanModifierVersion carries the same four entries, with
              forRatePlanModifier in place of forRatePlan"
      (let [mv {:jsonld/type "urpx:RatePlanModifierVersion"
                :urpx/modifierVersionNumber "1"
                :urpx/effectiveDateTime (LocalDateTime/of 2026 1 1 0 0)
                :urpx/hasPlanElements (:urpx/hasPlanElements minimal-version)
                :urpx/hasPrices {:jsonld/type "urpx:Prices"}}]
        (is (m/validate s/RatePlanModifierVersion
                        (assoc mv :urpx/forRatePlanModifier {:jsonld/id "urpx:m-1"}
                               :urpx/hasCoverageClaim claim)))))))

(deftest coverage-required-fields
  (testing "CoverageClaim requires completenessResult (sh:minCount 1)"
    (is (m/validate s/CoverageClaim
                    {:jsonld/type "urpx:CoverageClaim"
                     :urpx/completenessResult {:jsonld/id "urpx:partial"}}))
    (is (some? (m/explain s/CoverageClaim {:jsonld/type "urpx:CoverageClaim"}))))
  (testing "PriceDefinitionReference requires referenceInclusion (sh:minCount 1)"
    (is (m/validate s/PriceDefinitionReference
                    {:jsonld/type "urpx:PriceDefinitionReference"
                     :urpx/referenceInclusion {:jsonld/id "urpx:includedHere"}}))
    (is (some? (m/explain s/PriceDefinitionReference
                          {:jsonld/type "urpx:PriceDefinitionReference"}))))
  (testing "ModelExclusion requires both excludedModel and exclusionReason"
    (is (m/validate s/ModelExclusion
                    {:jsonld/type "urpx:ModelExclusion"
                     :urpx/excludedModel {:jsonld/id "urpx:pm-1"}
                     :urpx/exclusionReason {:jsonld/id "urpx:pruned"}}))
    (is (not (m/validate s/ModelExclusion
                         {:jsonld/type "urpx:ModelExclusion"
                          :urpx/excludedModel {:jsonld/id "urpx:pm-1"}}))))
  (testing "Evaluation nests ModelExclusion, and a single map normalizes to a
            vector through the single-or-vec decoder"
    (let [coerced (coerce/coerce s/Evaluation
                                 {:jsonld/type "urpx:Evaluation"
                                  :urpx/evaluationKind {:jsonld/id "urpx:validation"}
                                  :urpx/hasModelExclusion
                                  {:jsonld/type "urpx:ModelExclusion"
                                   :urpx/excludedModel {:jsonld/id "urpx:pm-1"}
                                   :urpx/exclusionReason {:jsonld/id "urpx:voided"}}})]
      (is (vector? (:urpx/hasModelExclusion coerced)))
      (is (m/validate s/Evaluation coerced)))))

;; ---------------------------------------------------------------------------
;; @context in all three JSON-LD encodings
;; ---------------------------------------------------------------------------

(deftest context-accepts-object-string-and-array
  (let [remote "https://urpx.org/ns/context/releases/v0.5.1/jsonld/urpx-context.jsonld"
        prefixes {"urpx" "https://urpx.org/ns/ontology/"}]
    (testing "all three forms the JSON-LD spec permits"
      (is (m/validate s/Context prefixes) "inline definition object")
      (is (m/validate s/Context remote) "remote context IRI")
      (is (m/validate s/Context [remote prefixes]) "array mixing the two")
      (is (m/validate s/Context [remote]) "single-element array")
      (is (m/validate s/Context [prefixes prefixes]) "array of objects"))
    (testing "the array form is what every shipped v0.5.1 test case uses, and it
              must validate on all four schemas that model @context"
      (doseq [[label schema base]
              [["RatePlan" s/RatePlan {:jsonld/type "urpx:RatePlan"
                                       :urpx/ratePlanUniqueIdentifier "P1"
                                       :urpx/name "Plan One"
                                       :urpx/initialEffectiveDate (LocalDate/of 2026 1 1)}]
               ["RatePlanModifier" s/RatePlanModifier
                {:jsonld/type "urpx:RatePlanModifier"
                 :urpx/modifierUniqueIdentifier "M1"
                 :urpx/name "Mod One"
                 :urpx/initialEffectiveDate (LocalDate/of 2026 1 1)}]
               ["URPXDocument" s/URPXDocument
                {:jsonld/type "urpx:URPXDocument"
                 :urpx/targetURPXVersion "https://example.com/v0.2.0"}]
               ["URPXPackage" s/URPXPackage
                {:jsonld/type "urpx:URPXPackage"
                 :urpx/targetURPXVersion "https://example.com/v0.5.1"}]]]
        (is (m/validate schema (assoc base :jsonld/context [remote prefixes]))
            (str label " must accept an array @context"))
        (is (m/validate schema (assoc base :jsonld/context prefixes))
            (str label " must still accept an object @context"))))
    (testing "a number is not a context in any encoding"
      (is (not (m/validate s/Context 42)))
      (is (not (m/validate s/Context [42]))))))

;; ---------------------------------------------------------------------------
;; v0.5.1 DataSourceReference flattening, and the publication split
;; ---------------------------------------------------------------------------

(deftest data-source-reference-accepts-flattened-and-nested
  (testing "v0.5.1 hoists leafStart/leafEnd/tariffStatementType inline and
            deletes urpx:TariffBookReference; the flattened node validates with
            no nested reference present"
    (let [flat {:jsonld/type "urpx:DataSourceReference"
                :urpx/fileName "x.pdf"
                :urpx/pageStart 1
                :urpx/pageEnd 6
                :urpx/leafStart "61096-E"
                :urpx/leafEnd "61101-E"
                :urpx/anchorLink "#sheet-61096"
                :urpx/tariffStatementType {:jsonld/id "urpx:distributionStatement"}
                :urpx/sourcePublication {:jsonld/id "pge:pub-1"}
                :urpx/hasSelector {:jsonld/id "pge:sel-1"}}]
      (is (nil? (m/explain s/DataSourceReference (coerce/coerce s/DataSourceReference flat))))))
  (testing "the v0.2.x nested form still validates: every filing in the corpus
            carries it and nothing is removed until the last wave"
    (let [nested {:jsonld/type "urpx:DataSourceReference"
                  :urpx/fileName "x.pdf"
                  :urpx/hasTariffBookReference
                  {:jsonld/type "urpx:TariffBookReference"
                   :urpx/pageStart 1 :urpx/pageEnd 6
                   :urpx/leafStart "61096-E" :urpx/leafEnd "61101-E"}}]
      (is (nil? (m/explain s/DataSourceReference (coerce/coerce s/DataSourceReference nested)))))))

(deftest source-publication-dual-accepts-both-vocabularies
  (let [base {:urpx/name "PG&E Electric Schedule E-1"
              :urpx/filingNumber "ELEC-4321-E"
              :urpx/issuingAuthority "CPUC"
              :urpx/publicationType {:jsonld/id "urpx:tariffBook"}}]
    (testing "both the v0.5.1 and the v0.2.x @type validate, because the corpus
              carries both at once through the waves"
      (is (m/validate s/SourcePublication (assoc base :jsonld/type "urpx:SourcePublication")))
      (is (m/validate s/SourcePublication (assoc base :jsonld/type "urpx:TariffPublication"))))
    (testing "an unrelated @type does not"
      (is (not (m/validate s/SourcePublication (assoc base :jsonld/type "urpx:RatePlan")))))
    (testing "the v0.2.1-only paths v0.5.1 drops are still accepted, since the
              vendored fixtures carry them"
      (is (m/validate s/SourcePublication
                      (assoc base :jsonld/type "urpx:TariffPublication"
                             :urpx/hasRatePlan [{:jsonld/id "pge:e-1"}]
                             :urpx/hasRatePlanModifier [{:jsonld/id "pge:pcia"}]))))
    (testing "publishedBy takes an inline Organization or a bare reference, and
              a malformed organization is not waved through the reference arm"
      (is (m/validate s/SourcePublication
                      (assoc base :jsonld/type "urpx:SourcePublication"
                             :urpx/publishedBy {:jsonld/id "org:pge"})))
      (is (m/validate s/SourcePublication
                      (assoc base :jsonld/type "urpx:SourcePublication"
                             :urpx/publishedBy {:jsonld/type "urpx:Organization"
                                                :urpx/legalName "PG&E"
                                                :urpx/organizationType
                                                {:jsonld/id "urpx:investorOwnedUtility"}})))
      (is (not (m/validate s/SourcePublication
                           (assoc base :jsonld/type "urpx:SourcePublication"
                                  :urpx/publishedBy {:jsonld/id "org:pge"
                                                     :urpx/legalName 42})))))))

(deftest tariff-filing-requires-contains-pricing-model
  (let [filing {:jsonld/type "urpx:TariffFiling"
                :urpx/name "Advice 1234-E"
                :urpx/containsPricingModel [{:jsonld/id "pge:pm-1"}]}]
    (is (m/validate s/TariffFiling filing))
    (testing "containsPricingModel is the shape's only sh:minCount 1"
      (is (not (m/validate s/TariffFiling (dissoc filing :urpx/containsPricingModel)))))
    (testing "a single map normalizes to a vector through the decoder"
      (let [coerced (coerce/coerce s/TariffFiling
                                   (assoc filing :urpx/containsPricingModel
                                          {:jsonld/id "pge:pm-1"}))]
        (is (vector? (:urpx/containsPricingModel coerced)))))
    (testing "TariffFiling is v0.5.1-only: there is no v0.2.x @type to accept"
      (is (not (m/validate s/TariffFiling (assoc filing :jsonld/type "urpx:TariffPublication")))))))

;; ---------------------------------------------------------------------------
;; Enum membership on the v0.5.1 reframe vocabularies
;; ---------------------------------------------------------------------------

(deftest reframe-enums-enforce-membership
  (testing "every member of each enum is accepted, and a bogus id is not"
    (doseq [[label schema members]
            [["CompletenessResult" s/CompletenessResult
              ["complete" "partial" "pending" "unsupported" "unknown" "noCoverage"]]
             ["ReferenceInclusion" s/ReferenceInclusion
              ["includedHere" "retrievedElsewhere"]]
             ["Confidence" s/Confidence ["verified" "estimated" "projected"]]
             ["EvaluationKind" s/EvaluationKind
              ["consolidation" "coverageEvaluation" "validation" "comparison" "benchmark"]]
             ["ExclusionReason" s/ExclusionReason
              ["pruned" "fullySuperseded" "voided" "outOfScope"]]]]
      (doseq [m members]
        (is (m/validate schema {:jsonld/id (str "urpx:" m)})
            (str label " must accept urpx:" m)))
      (is (not (m/validate schema {:jsonld/id "urpx:bogus"}))
          (str label " must reject a non-member"))
      (is (not (m/validate schema {:jsonld/id (str/upper-case (str "urpx:" (first members)))}))
          (str label " membership is exact, not case-insensitive")))))

(deftest publication-type-accepts-the-legacy-corpus-value
  (testing "the 8 v0.5.1 members are accepted"
    (doseq [m ["tariffBook" "tariffStatement" "tariffSupplement" "tariffAddendum"
               "supplyCharges" "derExports" "highlyDynamicPrices" "marketPriceFeed"]]
      (is (m/validate s/SourcePublicationType {:jsonld/id (str "urpx:" m)}))))
  (testing "urpx:regulatoryFiling is accepted as a documented legacy member: it
            belonged to the retired 10-member urpx:PublicationTypeValue, has no
            successor among the 8, and is the only publicationType value the
            corpus uses (8 occurrences across 6 filings). Enforcing the v0.5.1
            list alone would reject those filings."
    (is (m/validate s/SourcePublicationType {:jsonld/id "urpx:regulatoryFiling"})))
  (testing "the other retired members are NOT readmitted"
    (doseq [m ["amendment" "notice" "order" "supplement" "tariffBookLeaves"
               "ratePlanPublication" "ratePlanModifierPublication"]]
      (is (not (m/validate s/SourcePublicationType {:jsonld/id (str "urpx:" m)}))
          (str "urpx:" m " must not be accepted")))))

(deftest enums-enforced-at-their-carriers
  (testing "CoverageClaim rejects a non-member completenessResult"
    (is (m/validate s/CoverageClaim {:jsonld/type "urpx:CoverageClaim"
                                     :urpx/completenessResult {:jsonld/id "urpx:partial"}}))
    (is (not (m/validate s/CoverageClaim {:jsonld/type "urpx:CoverageClaim"
                                          :urpx/completenessResult {:jsonld/id "urpx:mostly"}}))))
  (testing "PriceDefinitionReference rejects a non-member referenceInclusion"
    (is (not (m/validate s/PriceDefinitionReference
                         {:jsonld/type "urpx:PriceDefinitionReference"
                          :urpx/referenceInclusion {:jsonld/id "urpx:somewhere"}}))))
  (testing "ModelExclusion rejects a non-member exclusionReason"
    (is (not (m/validate s/ModelExclusion
                         {:jsonld/type "urpx:ModelExclusion"
                          :urpx/excludedModel {:jsonld/id "urpx:pm-1"}
                          :urpx/exclusionReason {:jsonld/id "urpx:because"}}))))
  (testing "SourcePublication rejects a non-member publicationType"
    (is (not (m/validate s/SourcePublication
                         {:jsonld/type "urpx:SourcePublication"
                          :urpx/publicationType {:jsonld/id "urpx:pressRelease"}})))))

(ns urpx.emit-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [urpx.coerce :as coerce]
            [urpx.core :as core]
            [urpx.emit :as emit]
            [urpx.test-fixtures :as fixtures])
  (:import [java.math BigDecimal]
           [java.time LocalDate LocalDateTime]))

;; ---------------------------------------------------------------------------
;; Smoke tests — each public emit function returns valid JSON-LD-shaped data
;; ---------------------------------------------------------------------------

(def minimal-rate-plan
  {:jsonld/type "urpx:RatePlan"
   :jsonld/id   "x:plan"
   :urpx/ratePlanUniqueIdentifier "P1"
   :urpx/name "Plan One"
   :urpx/initialEffectiveDate (LocalDate/of 2026 1 1)
   :urpx/hasRatePlanVersion
   {:jsonld/type "urpx:RatePlanVersion"
    :urpx/effectiveDateTime (LocalDateTime/of 2026 1 1 0 0)
    :urpx/hasPlanElements
    {:jsonld/type "urpx:PlanElements"
     :urpx/hasMetricInput
     [{:jsonld/type "urpx:MetricInput"
       :urpx/name "kWh"
       :urpx/metricInputType {:jsonld/id "urpx:measuredMetricInput"}}]
     :urpx/hasBillingModel
     {:jsonld/type "urpx:BillingModel"
      :urpx/hasPriceDefinition []}}
    :urpx/hasPrices {:jsonld/type "urpx:Prices"}}})

(deftest write-rate-plan-returns-valid-json
  (let [out (emit/write-rate-plan minimal-rate-plan)]
    (is (string? out))
    (let [parsed (json/read-str out)]
      (is (= "urpx:RatePlan" (get parsed "@type")))
      (is (= "x:plan"        (get parsed "@id")))
      (is (= "Plan One"      (get parsed "urpx:name")))
      (is (= "2026-01-01"    (get parsed "urpx:initialEffectiveDate"))
          "LocalDate encoded back to ISO string"))))

(deftest write-rate-plan-modifier-returns-valid-json
  (let [modifier {:jsonld/type "urpx:RatePlanModifier"
                  :jsonld/id   "x:mod"
                  :urpx/modifierUniqueIdentifier "M1"
                  :urpx/name   "Modifier"
                  :urpx/initialEffectiveDate (LocalDate/of 2026 1 1)
                  :urpx/hasRatePlanModifierVersion
                  {:jsonld/type "urpx:RatePlanModifierVersion"
                   :urpx/modifierVersionNumber "1.0"
                   :urpx/effectiveDateTime (LocalDateTime/of 2026 1 1 0 0)
                   :urpx/hasPlanElements
                   {:jsonld/type "urpx:PlanElements"
                    :urpx/hasMetricInput
                    [{:jsonld/type "urpx:MetricInput"
                      :urpx/name "kWh"
                      :urpx/metricInputType {:jsonld/id "urpx:measuredMetricInput"}}]
                    :urpx/hasBillingModel
                    {:jsonld/type "urpx:BillingModel"
                     :urpx/hasPriceDefinition []}}
                   :urpx/hasPrices {:jsonld/type "urpx:Prices"}}}
        parsed (json/read-str (emit/write-rate-plan-modifier modifier))]
    (is (= "urpx:RatePlanModifier" (get parsed "@type")))
    (is (= "M1" (get parsed "urpx:modifierUniqueIdentifier")))))

(deftest write-urpx-document-returns-valid-json
  (let [doc {:jsonld/type "urpx:URPXDocument"
             :jsonld/id   "x:doc"
             :urpx/targetURPXVersion "https://example.com/urpx-v0.2.0"
             :urpx/hasRatePlan [minimal-rate-plan]}
        parsed (json/read-str (emit/write-urpx-document doc))]
    (is (= "urpx:URPXDocument" (get parsed "@type")))
    (is (= "https://example.com/urpx-v0.2.0"
           (get parsed "urpx:targetURPXVersion")))
    (is (vector? (get parsed "urpx:hasRatePlan")))))

;; ---------------------------------------------------------------------------
;; End-to-end round-trip — parse → coerce → emit → re-parse → re-coerce
;; ---------------------------------------------------------------------------

(deftest roundtrip-minimal-rate-plan
  (testing "URPX-biu: minimal rate-plan round-trips with logical equivalence"
    (let [emitted (emit/write-rate-plan minimal-rate-plan)
          re-parsed (-> emitted java.io.StringReader. core/parse-rate-plan)
          re-coerced (coerce/coerce-rate-plan re-parsed)]
      (is (= minimal-rate-plan re-coerced)))))

(deftest roundtrip-pge-fixture-rate-plan
  (testing "URPX-biu: a PG&E E-1 RatePlan (extracted from the @graph fixture)
            round-trips cleanly through emit + re-parse + re-coerce. Strongest
            available smoke test of the full encoder pipeline before URPX-739
            adds the dedicated round-trip suite over every vendored fixture."
    (let [original (fixtures/load-rate-plan "pge-e-1-2026-03-01")
          emitted (emit/write-rate-plan original)
          re-coerced (-> emitted
                         java.io.StringReader.
                         core/parse-rate-plan
                         coerce/coerce-rate-plan)]
      (is (= original re-coerced)
          "structural equivalence end-to-end"))))

(deftest roundtrip-typed-leaves
  (testing "encoders preserve typed values across the full pipeline"
    (let [plan (assoc-in minimal-rate-plan
                         [:urpx/hasRatePlanVersion :urpx/hasPrices]
                         {:jsonld/type "urpx:Prices"
                          :urpx/hasPrice
                          [{:jsonld/type "urpx:Price"
                            :jsonld/id "x:price"
                            :urpx/unitPrice (BigDecimal. "0.15075")
                            :urpx/startDate (LocalDate/of 2026 1 1)
                            :urpx/referencesPriceDefinition
                            {:jsonld/id "x:pd"}}]})
          re-coerced (-> plan
                         emit/write-rate-plan
                         java.io.StringReader.
                         core/parse-rate-plan
                         coerce/coerce-rate-plan)
          price (-> re-coerced
                    (get-in [:urpx/hasRatePlanVersion :urpx/hasPrices :urpx/hasPrice])
                    first)]
      (is (= (BigDecimal. "0.15075") (:urpx/unitPrice price))
          "BigDecimal scale preserved")
      (is (= (LocalDate/of 2026 1 1) (:urpx/startDate price))
          "LocalDate round-trips"))))

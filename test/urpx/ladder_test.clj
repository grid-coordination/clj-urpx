(ns urpx.ladder-test
  "marginal-unit-rate takes the lowest rung of a ledger's block-tier ladder.
  Two prices at that tier number leave the rung undefined, and it refuses."
  (:require [clojure.test :refer [deftest is testing]]
            [urpx.price :as price])
  (:import [java.time LocalDate LocalDateTime ZoneId ZonedDateTime]))

(def ^:private la (ZoneId/of "America/Los_Angeles"))

(def ^:private q (ZonedDateTime/of 2026 7 15 17 0 0 0 la))

(defn- price-
  [id pd unit & {:as extra}]
  (merge {:jsonld/type "urpx:Price" :jsonld/id id
          :urpx/unitPrice (BigDecimal. ^String unit)
          :urpx/referencesPriceDefinition {:jsonld/id pd}}
         extra))

(defn- pd- [id]
  {:jsonld/type "urpx:PriceDefinition" :jsonld/id id :urpx/name id
   :urpx/referencesLedger {:jsonld/id "test:ledger"}})

(defn- version-body [pds prices]
  {:urpx/changeReason {:jsonld/id "urpx:rateCase"}
   :urpx/hasCoverageClaim {:jsonld/type "urpx:CoverageClaim"
                           :urpx/completenessResult {:jsonld/id "urpx:complete"}}
   :urpx/serviceRole {:jsonld/id "urpx:bundledServiceRole"}
   :urpx/effectiveDateTime (LocalDateTime/of 2025 1 1 0 0)
   :urpx/priceCurrency "USD"
   :urpx/hasPlanElements
   {:jsonld/type "urpx:PlanElements"
    :urpx/hasMetricInput
    [{:jsonld/type "urpx:MetricInput" :jsonld/id "test:mi-energy" :urpx/name "kWh"
      :urpx/metricInputType {:jsonld/id "urpx:profileMetricInput"}
      :urpx/hasMetricSpecification
      {:jsonld/type "urpx:MetricSpecification"
       :urpx/measurementKind {:jsonld/id "urpx:energy"}
       :urpx/unitOfMeasure   {:jsonld/id "urpx:kWh"}}}]
    :urpx/hasBillingModel
    {:jsonld/type "urpx:BillingModel"
     :urpx/hasLedger [{:jsonld/type "urpx:Ledger" :jsonld/id "test:ledger"
                       :urpx/name "Energy"
                       :urpx/ledgerType {:jsonld/id "urpx:bundledLedger"}
                       :urpx/referencesMetricInput [{:jsonld/id "test:mi-energy"}]}]
     :urpx/hasPriceDefinition (mapv pd- pds)}}
   :urpx/hasPrices {:jsonld/type "urpx:Prices" :urpx/hasPrice prices}})

(defn- plan [pds prices]
  {:jsonld/type "urpx:RatePlan" :jsonld/id "test:base"
   :urpx/timezoneIdentifier "America/Los_Angeles"
   :urpx/ratePlanUniqueIdentifier "TEST-BASE"
   :urpx/name "Ladder test plan"
   :urpx/initialEffectiveDate (LocalDate/of 2025 1 1)
   :urpx/hasRatePlanVersion
   (merge (version-body pds prices)
          {:jsonld/type "urpx:RatePlanVersion" :jsonld/id "test:base-v1"
           :urpx/forRatePlan {:jsonld/id "test:base"}})})

(defn- modifier [pds prices]
  {:jsonld/type "urpx:RatePlanModifier" :jsonld/id "test:mod"
   :urpx/timezoneIdentifier "America/Los_Angeles"
   :urpx/name "Ladder test modifier"
   :urpx/initialEffectiveDate (LocalDate/of 2025 1 1)
   :urpx/hasRatePlanModifierVersion
   (merge (version-body pds prices)
          {:jsonld/type "urpx:RatePlanModifierVersion" :jsonld/id "test:mod-v1"
           :urpx/forRatePlanModifier {:jsonld/id "test:mod"}
           :urpx/applicableToRatePlan [{:jsonld/id "test:base"}]})})

(defn- refusal [resolved & [opts]]
  (try (price/marginal-unit-rate resolved opts) nil
       (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest two-definitions-at-the-lowest-tier-number-refuse
  (let [r (price/resolve-prices
           (plan ["test:pd-a" "test:pd-b" "test:pd-c"]
                 [(price- "test:a" "test:pd-a" "0.10" :urpx/serviceTierNumber 1)
                  (price- "test:b" "test:pd-b" "0.20" :urpx/serviceTierNumber 1)
                  (price- "test:c" "test:pd-c" "0.30" :urpx/serviceTierNumber 2)])
           q)
        e (refusal r)]
    (is (= :ambiguous-marginal-tier (:reason e)))
    (is (= [{:urpx.resolved/ledger-id "test:ledger"
             :tier-number 1
             :price-ids ["test:a" "test:b"]}]
           (update-in (:ledgers e) [0 :price-ids] (comp vec sort))))))

(deftest two-prices-of-one-modifier-definition-at-one-tier-number-refuse
  (testing "a modifier resolved alone has no period to gate on, so Prices of one
            definition told apart only by urpx:touPeriodNumber both surface"
    (let [r (price/resolve-modifier-prices
             (modifier ["test:pd-a"]
                       [(price- "test:a1" "test:pd-a" "0.10"
                                :urpx/serviceTierNumber 1 :urpx/touPeriodNumber 1)
                        (price- "test:a2" "test:pd-a" "0.20"
                                :urpx/serviceTierNumber 1 :urpx/touPeriodNumber 2)])
             q)]
      (is (= :ambiguous-marginal-tier (:reason (refusal r))))
      (is (= #{"test:a1" "test:a2"}
             (set (get-in (refusal r) [:ledgers 0 :price-ids])))))))

(deftest a-shared-higher-tier-number-does-not-refuse
  (let [r (price/resolve-prices
           (plan ["test:pd-a" "test:pd-b" "test:pd-c" "test:pd-x"]
                 [(price- "test:a" "test:pd-a" "0.10" :urpx/serviceTierNumber 1)
                  (price- "test:b" "test:pd-b" "0.20" :urpx/serviceTierNumber 2)
                  (price- "test:c" "test:pd-c" "0.30" :urpx/serviceTierNumber 2)
                  (price- "test:x" "test:pd-x" "0.01")])
           q)]
    (is (== 0.11M (price/marginal-unit-rate r)))))

(deftest an-excluded-price-does-not-share-the-rung
  (let [r (price/resolve-prices
           (plan ["test:pd-a" "test:pd-b"]
                 [(price- "test:a" "test:pd-a" "0.10" :urpx/serviceTierNumber 1)
                  (price- "test:b" "test:pd-b" "0.90" :urpx/serviceTierNumber 1
                          :urpx/hasDynamicPricing {:jsonld/id "test:event"
                                                  :jsonld/type "urpx:DynamicPricing"})])
           q)]
    (is (== 0.10M (price/marginal-unit-rate r)))
    (is (= :ambiguous-marginal-tier
           (:reason (refusal r {:include-dynamic-pricing? true}))))))

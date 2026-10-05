(ns urpx.withheld-gates-test
  "A Price withheld for want of a selection is listed only when it would apply
  at the instant were its alternative selected: started, the latest per cell
  within its urpx:PriceSetAlternative, and bound to the resolved TOU period or
  to none."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.walk]
            [urpx.price :as price]
            [urpx.test-fixtures :as fixtures])
  (:import [java.time LocalDate LocalDateTime ZoneId ZonedDateTime]))

(def ^:private la (ZoneId/of "America/Los_Angeles"))
(defn- zdt [h] (ZonedDateTime/of 2026 7 15 h 0 0 0 la))

(def ^:private income-tier "test:income-tier")

(defn- price- [id unit start]
  {:jsonld/type "urpx:Price" :jsonld/id id
   :urpx/unitPrice (BigDecimal. ^String unit)
   :urpx/referencesPriceDefinition {:jsonld/id "test:pd-alt"}
   :urpx/startDate start})

(def ^:private jan (LocalDate/of 2026 1 1))
(def ^:private apr (LocalDate/of 2026 4 1))
(def ^:private next-year (LocalDate/of 2027 1 1))

(defn- plan
  "A plan whose only ledger is fed by Prices nested in a PriceSetAlternatives,
   so with no selection the ledger is marked and lists them."
  [care-prices fera-prices]
  {:jsonld/type "urpx:RatePlan" :jsonld/id "test:base"
   :urpx/timezoneIdentifier "America/Los_Angeles"
   :urpx/ratePlanUniqueIdentifier "TEST-BASE"
   :urpx/name "Withheld gates test plan"
   :urpx/initialEffectiveDate (LocalDate/of 2025 1 1)
   :urpx/hasRatePlanVersion
   {:jsonld/type "urpx:RatePlanVersion" :jsonld/id "test:base-v1"
    :urpx/forRatePlan {:jsonld/id "test:base"}
    :urpx/changeReason {:jsonld/id "urpx:rateCase"}
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
     :urpx/hasProfileAlternatives
     [{:jsonld/type "urpx:ProfileAlternatives" :jsonld/id income-tier
       :urpx/name "Income Tier"
       :urpx/hasProfileAlternative
       [{:jsonld/type "urpx:ProfileAlternative" :jsonld/id "test:care" :urpx/name "CARE"}
        {:jsonld/type "urpx:ProfileAlternative" :jsonld/id "test:fera" :urpx/name "FERA"}]}]
     :urpx/hasBillingModel
     {:jsonld/type "urpx:BillingModel"
      :urpx/hasLedger [{:jsonld/type "urpx:Ledger" :jsonld/id "test:ledger"
                        :urpx/name "Adjustment"
                        :urpx/ledgerType {:jsonld/id "urpx:bundledLedger"}
                        :urpx/referencesMetricInput [{:jsonld/id "test:mi-energy"}]}]
      :urpx/hasPriceDefinition
      [{:jsonld/type "urpx:PriceDefinition" :jsonld/id "test:pd-alt" :urpx/name "By income tier"
        :urpx/referencesLedger {:jsonld/id "test:ledger"}}]}}
    :urpx/hasPrices
    {:jsonld/type "urpx:Prices"
     :urpx/hasPriceSetAlternatives
     [{:jsonld/type "urpx:PriceSetAlternatives" :jsonld/id "test:alt-prices"
       :urpx/referencesProfileAlternatives {:jsonld/id income-tier}
       :urpx/hasPriceSetAlternative
       [{:jsonld/type "urpx:PriceSetAlternative" :jsonld/id "test:psa-care"
         :urpx/referencesProfileAlternative {:jsonld/id "test:care"}
         :urpx/hasPrice care-prices}
        {:jsonld/type "urpx:PriceSetAlternative" :jsonld/id "test:psa-fera"
         :urpx/referencesProfileAlternative {:jsonld/id "test:fera"}
         :urpx/hasPrice fera-prices}]}]}}})

(defn- marked [r]
  (filter :urpx.resolved/unselected-alternatives (:urpx.resolved/ledgers r)))

(defn- withheld-ids [r]
  (->> (marked r)
       (mapcat :urpx.resolved/withheld-prices)
       (map :urpx.resolved/price-id)
       sort vec))

(defn- rate [r]
  (try (price/marginal-unit-rate r)
       (catch clojure.lang.ExceptionInfo e (:reason (ex-data e)))))

(deftest a-withheld-price-must-have-started-and-not-been-superseded
  (let [r (price/resolve-prices
           (plan [(price- "test:care-jan" "0.05" jan) (price- "test:care-apr" "0.06" apr)]
                 [(price- "test:fera-jan" "0.10" jan) (price- "test:fera-2027" "0.11" next-year)])
           (zdt 17))]
    (is (= ["test:care-apr" "test:fera-jan"] (withheld-ids r))
        "care-jan is superseded by care-apr; fera-2027 has not started")))

(deftest an-alternatives-set-with-no-started-price-withholds-nothing
  (let [r (price/resolve-prices
           (plan [(price- "test:care-2027" "0.05" next-year)]
                 [(price- "test:fera-2027" "0.10" next-year)])
           (zdt 17))]
    (is (empty? (marked r)))))

(defn- in-period
  "`doc` with every Price whose @id `ids` holds bound to `period`."
  [doc ids period]
  (clojure.walk/postwalk
   (fn [n] (if (and (map? n) (ids (:jsonld/id n)))
             (assoc n :urpx/touPeriodNumber period)
             n))
   doc))

(def ^:private base-charges
  #{"pge:price-base-charge-tier-1" "pge:price-base-charge-tier-2" "pge:price-base-charge-tier-3"})

(def ^:private vintages (into #{} (map #(str "pge:price-vpcia-" %)) (range 2009 2027)))

(deftest a-withheld-price-must-be-in-the-resolved-period
  (let [pge   (fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")
        modi  (fixtures/load-modifier "pge-e-tou-c-2026-03-01")
        off   (in-period pge base-charges 3)
        mod-off (in-period modi vintages 3)]
    (is (= 1 (:urpx.resolved/tou-period-number (price/resolve-prices pge (zdt 17))))
        "sanity: 17:00 is in period 1")
    (is (= 3 (:urpx.resolved/tou-period-number (price/resolve-prices pge (zdt 2))))
        "sanity: 02:00 is in period 3")
    (testing "on the base plan"
      (is (empty? (withheld-ids (price/resolve-prices off (zdt 17)))))
      (is (= (sort base-charges) (withheld-ids (price/resolve-prices off (zdt 2))))))
    (testing "on a modifier"
      (is (= 0.46886M (rate (price/resolve-prices-with-modifiers pge [mod-off] (zdt 17)))))
      (is (= :unselected-alternatives
             (rate (price/resolve-prices-with-modifiers pge [mod-off] (zdt 2))))))))

(deftest a-modifier-alternatives-set-with-no-started-price-does-not-refuse
  (let [pge  (fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")
        modi (clojure.walk/postwalk
              (fn [n] (if (and (map? n) (vintages (:jsonld/id n)))
                        (assoc n :urpx/startDate (LocalDate/of 2030 1 1))
                        n))
              (fixtures/load-modifier "pge-e-tou-c-2026-03-01"))]
    (is (= (rate (price/resolve-prices pge (zdt 17)))
           (rate (price/resolve-prices-with-modifiers pge [modi] (zdt 17)))))))

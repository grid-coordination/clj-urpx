(ns urpx.supersession-test
  "A later dated Price supersedes an earlier one for the same cell, keyed on
  [PriceDefinition, serviceTierNumber, touPeriodNumber], on a modifier as on a
  base plan. The modifier path surfaced every started Price for a cell, so a
  rate change filed as a second dated Price summed with the one it replaced.

  Each urpx:PriceSetAlternative is its own set of cells: its siblings are
  alternatives to it, never earlier or later versions of it."
  (:require [clojure.test :refer [deftest is testing]]
            [urpx.price :as price])
  (:import [java.time LocalDate LocalDateTime ZoneId ZonedDateTime]))

(def ^:private la (ZoneId/of "America/Los_Angeles"))

(def ^:private q
  "Months after both start dates, so day-granular start gating cannot affect
  which Prices have started."
  (ZonedDateTime/of 2026 7 15 17 0 0 0 la))

(def ^:private income-tier "test:income-tier")

(defn- price- [id pd unit start]
  {:jsonld/type "urpx:Price" :jsonld/id id
   :urpx/unitPrice (BigDecimal. ^String unit)
   :urpx/referencesPriceDefinition {:jsonld/id pd}
   :urpx/startDate start})

(def ^:private jan (LocalDate/of 2026 1 1))
(def ^:private apr (LocalDate/of 2026 4 1))

(def ^:private plan-elements
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
     :urpx/hasAlternativeDimensions
     {:jsonld/type "urpx:AlternativeDimensions"
      :urpx/hasAlternativeDimension
      [{:jsonld/type "urpx:AlternativeDimension"
        :urpx/dimensionDisplayName "Income Tier"
        :urpx/dimensionProperty "https://schema.urpx.org/incomeTier"
        :urpx/hasPossibleValues [{:jsonld/id "urpx:incomeTierCare"}
                                 {:jsonld/id "urpx:incomeTierFera"}]}]}
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
    [{:jsonld/type "urpx:PriceDefinition" :jsonld/id "test:pd-flat" :urpx/name "Flat"
      :urpx/referencesLedger {:jsonld/id "test:ledger"}}
     {:jsonld/type "urpx:PriceDefinition" :jsonld/id "test:pd-alt" :urpx/name "By income tier"
      :urpx/referencesLedger {:jsonld/id "test:ledger"}}]}})

(def ^:private prices
  {:jsonld/type "urpx:Prices"
   :urpx/hasPrice [(price- "test:flat-jan" "test:pd-flat" "0.01" jan)
                   (price- "test:flat-apr" "test:pd-flat" "0.02" apr)]
   :urpx/hasPriceSetAlternatives
   [{:jsonld/type "urpx:PriceSetAlternatives" :jsonld/id "test:alt-prices"
     :urpx/referencesProfileAlternatives {:jsonld/id income-tier}
     :urpx/hasPriceSetAlternative
     [{:jsonld/type "urpx:PriceSetAlternative" :jsonld/id "test:psa-care"
       :urpx/referencesProfileAlternative {:jsonld/id "test:care"}
       :urpx/hasPrice [(price- "test:care-jan" "test:pd-alt" "0.05" jan)
                       (price- "test:care-apr" "test:pd-alt" "0.06" apr)]}
      {:jsonld/type "urpx:PriceSetAlternative" :jsonld/id "test:psa-fera"
       :urpx/referencesProfileAlternative {:jsonld/id "test:fera"}
       :urpx/hasPrice [(price- "test:fera-jan" "test:pd-alt" "0.10" jan)]}]}]})

(def ^:private version-common
  {:urpx/changeReason {:jsonld/id "urpx:rateCase"}
   :urpx/hasCoverageClaim {:jsonld/type "urpx:CoverageClaim"
                           :urpx/completenessResult {:jsonld/id "urpx:complete"}}
   :urpx/serviceRole {:jsonld/id "urpx:bundledServiceRole"}
   :urpx/effectiveDateTime (LocalDateTime/of 2025 1 1 0 0)
   :urpx/priceCurrency "USD"
   :urpx/hasPlanElements plan-elements
   :urpx/hasPrices prices})

(def ^:private modifier
  {:jsonld/type "urpx:RatePlanModifier" :jsonld/id "test:mod"
   :urpx/timezoneIdentifier "America/Los_Angeles"
   :urpx/name "Supersession test modifier"
   :urpx/initialEffectiveDate (LocalDate/of 2025 1 1)
   :urpx/hasRatePlanModifierVersion
   (merge version-common
          {:jsonld/type "urpx:RatePlanModifierVersion" :jsonld/id "test:mod-v1"
           :urpx/forRatePlanModifier {:jsonld/id "test:mod"}
           :urpx/applicableToRatePlan [{:jsonld/id "test:base"}]})})

(def ^:private plan
  {:jsonld/type "urpx:RatePlan" :jsonld/id "test:base"
   :urpx/timezoneIdentifier "America/Los_Angeles"
   :urpx/ratePlanUniqueIdentifier "TEST-BASE"
   :urpx/name "Supersession test plan"
   :urpx/initialEffectiveDate (LocalDate/of 2025 1 1)
   :urpx/hasRatePlanVersion
   (merge version-common
          {:jsonld/type "urpx:RatePlanVersion" :jsonld/id "test:base-v1"
           :urpx/forRatePlan {:jsonld/id "test:base"}})})

(def ^:private care {:profile-alternatives {income-tier "test:care"}})

(defn- price-ids
  "The @ids of the Prices surfaced for `pd`, sorted."
  [r pd]
  (->> (:urpx.resolved/ledgers r)
       (mapcat :urpx.resolved/tiers)
       (filter #(= pd (:urpx.resolved/price-definition-id %)))
       (map :urpx.resolved/price-id)
       sort vec))

(deftest the-later-flat-price-supersedes-the-earlier-one
  (testing "on the base plan"
    (is (= ["test:flat-apr"] (price-ids (price/resolve-prices plan q) "test:pd-flat"))))
  (testing "and on a modifier, with and without a selection"
    (is (= ["test:flat-apr"]
           (price-ids (price/resolve-modifier-prices modifier q) "test:pd-flat")))
    (is (= ["test:flat-apr"]
           (price-ids (price/resolve-modifier-prices modifier q care) "test:pd-flat")))))

(deftest the-later-price-in-a-selected-alternative-supersedes-the-earlier-one
  (is (= ["test:care-apr"]
         (price-ids (price/resolve-prices plan q care) "test:pd-alt"))
      "base plan")
  (is (= ["test:care-apr"]
         (price-ids (price/resolve-modifier-prices modifier q care) "test:pd-alt"))
      "modifier"))

(deftest the-superseded-price-is-not-summed
  (is (== 0.08M (price/marginal-unit-rate (price/resolve-modifier-prices modifier q care)))
      "flat-apr 0.02 plus care-apr 0.06"))

(deftest unselected-alternatives-are-superseded-each-within-itself
  (testing "with no selection a modifier surfaces every alternative. Within one,
            the later Price supersedes the earlier; across them, the same cell
            in a sibling alternative is a different price, and both surface."
    (is (= ["test:care-apr" "test:fera-jan"]
           (price-ids (price/resolve-modifier-prices modifier q) "test:pd-alt")))))

(deftest before-the-later-start-the-earlier-price-applies
  (let [march (ZonedDateTime/of 2026 3 1 17 0 0 0 la)]
    (is (= ["test:flat-jan"]
           (price-ids (price/resolve-modifier-prices modifier march) "test:pd-flat")))
    (is (= ["test:care-jan" "test:fera-jan"]
           (price-ids (price/resolve-modifier-prices modifier march) "test:pd-alt")))))

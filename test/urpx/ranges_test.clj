(ns urpx.ranges-test
  "The entity registry, property ranges, and the references and inline nodes
  that fall outside them, read from documents and refused by the DSL."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [malli.core :as m]
            [urpx.coerce :as coerce]
            [urpx.core :as core]
            [urpx.dsl :as dsl]
            [urpx.index :as index]
            [urpx.schema :as schema]
            [urpx.test-fixtures :as fixtures]))

(deftest registry
  (testing "every entity schema is registered under each @type it admits,
            and no two claim the same @type"
    (let [entity-schemas (for [[sym v] (ns-publics 'urpx.schema)
                               :when (vector? @v)
                               t (schema/type-literals @v)]
                           [sym t @v])]
      (is (< 60 (count entity-schemas)))
      (doseq [[sym t s] entity-schemas]
        (is (= s (schema/schema-for-type t)) (str sym " " t)))))
  (testing "a schema is reachable by @type and by keyword"
    (is (= schema/Season (schema/schema-for-type "urpx:Season")))
    (is (= schema/Season (:urpx/Season schema/schemas)))
    (is (nil? (schema/schema-for-type "urpx:NoSuchThing")))
    (is (nil? (schema/schema-for-type "Season")) "an @type with no prefix"))
  (testing "both @type spellings a schema admits are registered"
    (is (= schema/SourcePublication
           (schema/schema-for-type "urpx:SourcePublication")
           (schema/schema-for-type "urpx:TariffPublication"))))
  (testing "the registry validates by name"
    (let [ledger {:jsonld/type "urpx:Ledger" :urpx/name "Commodity"
                  :urpx/ledgerType {:jsonld/id "urpx:supplyLedger"}}]
      (is (m/validate :urpx/Ledger ledger {:registry schema/registry}))
      (is (not (m/validate :urpx/Season ledger {:registry schema/registry}))))))

(deftest instance-of
  (is (schema/instance-of? "urpx:Season" "urpx:Season"))
  (is (schema/instance-of? "urpx:UtilityOperator" "urpx:Organization"))
  (is (schema/instance-of? "urpx:PriceSetAlternative" "urpx:PriceSet"))
  (is (not (schema/instance-of? "urpx:Organization" "urpx:UtilityOperator")))
  (is (not (schema/instance-of? "urpx:Ledger" "urpx:Season"))))

(deftest ranges
  (let [rs (schema/ranges schema/PriceDefinition)]
    (is (= "urpx:Season" (:urpx/appliesInSeason rs)))
    (is (= "urpx:Ledger" (:urpx/referencesLedger rs)))
    (is (not (contains? rs :urpx/name)))))

(defn- fixture-docs []
  (for [f (sort (filter #(str/ends-with? (str %) ".jsonld")
                        (file-seq (io/file (io/resource "urpx-fixtures")))))]
    [(.getName ^java.io.File f)
     (mapv #(or (coerce/coerce-doc %) %) (core/load-graph (.getPath ^java.io.File f)))]))

(deftest vendored-fixtures-are-well-typed
  (doseq [[nm entities] (fixture-docs)
          :let [judged (atom 0)
                idx (into {} (map index/build-index) entities)
                type-of (fn [id]
                          (when-let [t (:jsonld/type (get idx id))]
                            (swap! judged inc)
                            t))
                found (vec (mapcat #(schema/mistyped-references % type-of) entities))]]
    (is (= [] found) nm)
    (is (pos? @judged) (str nm " resolved no reference, so judged nothing"))))

(defn- retarget-first
  "`doc` with the first value of `property` anywhere in it pointed at `id`."
  [doc property id]
  (let [done (volatile! false)]
    (walk/prewalk (fn [x]
                    (if (and (map? x) (contains? x property) (not @done))
                      (do (vreset! done true)
                          (assoc x property {:jsonld/id id}))
                      x))
                  doc)))

(deftest a-reference-to-the-wrong-type
  (let [plan (fixtures/load-rate-plan "pge-e-1-2026-03-01")
        idx (index/build-index plan)
        season-id (some (fn [[id n]] (when (= "urpx:Season" (:jsonld/type n)) id)) idx)
        bad (retarget-first plan :urpx/referencesLedger season-id)
        [r & more] (index/mistyped-references [bad])]
    (is (some? season-id))
    (is (= [] (index/mistyped-references [plan])))
    (is (nil? more))
    (is (= {:property :urpx/referencesLedger :range "urpx:Ledger"
            :id season-id :types ["urpx:Season"] :document 0}
           (dissoc r :in)))
    (is (= {:jsonld/id season-id} (get-in bad (:in r))))))

(deftest references-across-documents
  (let [plan {:jsonld/type "urpx:Season" :jsonld/id "ex:summer"
              :urpx/seasonName "Summer"}
        pd {:jsonld/type "urpx:PriceDefinition" :jsonld/id "ex:pd"
            :urpx/name "PD"
            :urpx/referencesLedger {:jsonld/id "ex:summer"}
            :urpx/appliesInSeason [{:jsonld/id "ex:summer"}]}]
    (testing "the target is found in another document"
      (is (= [{:in [:urpx/referencesLedger] :property :urpx/referencesLedger
               :range "urpx:Ledger" :id "ex:summer" :types ["urpx:Season"]
               :document 1}]
             (index/mistyped-references [plan pd]))))
    (testing "a reference to a node in no document is not judged"
      (is (= [] (index/mistyped-references [pd]))))))

(deftest inline-nodes-and-subclasses
  (let [type-of {"ex:utility" "urpx:UtilityOperator" "ex:season" "urpx:Season"}]
    (testing "an inline node is judged by its own @type"
      (is (= [{:in [:urpx/replacedBy] :property :urpx/replacedBy
               :range "urpx:TariffFiling" :id nil :types ["urpx:Season"]}]
             (schema/mistyped-references
              {:jsonld/type "urpx:TariffFiling"
               :urpx/replacedBy {:jsonld/type "urpx:Season" :urpx/seasonName "S"}}
              type-of))))
    (testing "a subclass is in its class's range"
      (is (= [] (schema/mistyped-references
                 {:jsonld/type "urpx:TariffFiling"
                  :urpx/publishedBy {:jsonld/id "ex:utility"}}
                 type-of))))
    (testing "nested nodes are judged at their own paths"
      (is (= [[:urpx/hasPrice 0 :urpx/appliesInSeason 0]]
             (map :in (schema/mistyped-references
                       {:jsonld/type "urpx:Prices"
                        :urpx/hasPrice [{:jsonld/type "urpx:Price"
                                         :urpx/appliesInSeason [{:jsonld/id "ex:utility"}]}]}
                       type-of)))))))

(deftest dsl-scope-refuses-a-mistyped-reference
  (let [e (try (dsl/dsl-scope
                (dsl/ledger :as :ex/commodity :name "Commodity"
                            :ledger-type :urpx/supplyLedger)
                (dsl/price-definition :as :ex/pd :name "PD"
                                      :applies-in-season :ex/commodity
                                      :references-ledger :ex/commodity))
               nil
               (catch clojure.lang.ExceptionInfo e e))]
    (is (= {:mistyped [{:ref :ex/commodity :type "urpx:Ledger"
                        :by "urpx:PriceDefinition" :property :urpx/appliesInSeason
                        :range "urpx:Season"}]}
           (ex-data e))))
  (testing "a reference to the right type, or a subclass of it, passes"
    (is (= {:jsonld/id "ex:summer"}
           (first (:urpx/appliesInSeason (dsl/dsl-scope
                     (dsl/season :as :ex/summer :season-name "Summer" :month [6 7 8])
                     (dsl/ledger :as :ex/commodity :name "Commodity"
                                 :ledger-type :urpx/supplyLedger)
                     (dsl/price-definition :as :ex/pd :name "PD"
                                           :applies-in-season :ex/summer
                                           :references-ledger :ex/commodity))))))
    (is (some? (dsl/dsl-scope
                (dsl/utility-operator :as :ex/utility
                                      :organization-type :urpx/utility)
                (dsl/tariff-filing :contains-pricing-model []
                                   :published-by :ex/utility))))))

(deftest a-builder-refuses-a-mistyped-inline-node
  (let [e (try (dsl/tariff-filing :contains-pricing-model []
                                  :replaced-by (dsl/season :season-name "S"))
               nil
               (catch clojure.lang.ExceptionInfo e e))]
    (is (= [{:in [:urpx/replacedBy] :property :urpx/replacedBy
             :range "urpx:TariffFiling" :id nil :types ["urpx:Season"]}]
           (:mistyped (ex-data e))))))

(deftest union-ranges
  (testing "an sh:or of sh:class alternatives is a set range"
    (is (= #{"urpx:Price" "urpx:PriceSummary"}
           (:urpx/basedOnPrice (schema/ranges schema/Price))))
    (is (schema/in-range? "urpx:PriceSummary" #{"urpx:Price" "urpx:PriceSummary"}))
    (is (schema/in-range? "urpx:UtilityOperator" #{"urpx:Organization" "urpx:Season"}))
    (is (not (schema/in-range? "urpx:Ledger" #{"urpx:Price" "urpx:PriceSummary"}))))
  (testing "a reference into another document is judged against every member"
    (let [base {:jsonld/type "urpx:RatePlan" :jsonld/id "ex:plan"
                :urpx/hasPrices [{:jsonld/type "urpx:Price" :jsonld/id "ex:base-price"}
                                 {:jsonld/type "urpx:PriceSummary" :jsonld/id "ex:summary"}
                                 {:jsonld/type "urpx:Ledger" :jsonld/id "ex:ledger"}]}
          discount (fn [& ids]
                     {:jsonld/type "urpx:Price" :jsonld/id "ex:discount"
                      :urpx/basedOnPrice (mapv #(hash-map :jsonld/id %) ids)})]
      (is (= [] (index/mistyped-references [base (discount "ex:base-price" "ex:summary")])))
      (is (= [{:document 1 :in [:urpx/basedOnPrice 1] :property :urpx/basedOnPrice
               :range #{"urpx:Price" "urpx:PriceSummary"} :id "ex:ledger"
               :types ["urpx:Ledger"]}]
             (index/mistyped-references [base (discount "ex:base-price" "ex:ledger")])))))
  (testing "dsl-scope judges a keyword reference against every member"
    (let [build #(dsl/dsl-scope
                  (dsl/ledger :as :ex/commodity :name "Commodity"
                              :ledger-type :urpx/supplyLedger)
                  (dsl/price :as :ex/base :unit-price 0.1M
                             :references-price-definition {:jsonld/id "x:pd"})
                  (dsl/price :unit-price 0.9M
                             :references-price-definition {:jsonld/id "x:pd"}
                             :based-on-price %))]
      (is (= [{:jsonld/id "ex:base"}] (:urpx/basedOnPrice (build :ex/base))))
      (is (= [{:ref :ex/commodity :type "urpx:Ledger" :by "urpx:Price"
               :property :urpx/basedOnPrice :range #{"urpx:Price" "urpx:PriceSummary"}}]
             (:mistyped (ex-data (try (build :ex/commodity) nil
                                      (catch clojure.lang.ExceptionInfo e e)))))))))

(deftest an-adjusting-bracket-names-a-service-tier
  (let [type-of {"ex:tier" "urpx:ServiceTier" "ex:alt" "urpx:ProfileAlternative"}
        bracket (fn [id] {:jsonld/type "urpx:ServiceBracket"
                          :urpx/adjustsServiceTier [{:jsonld/id id}]})]
    (is (= [] (schema/mistyped-references (bracket "ex:tier") type-of)))
    (is (= [[:urpx/adjustsServiceTier 0]]
           (map :in (schema/mistyped-references (bracket "ex:alt") type-of))))))

(deftest dynamic-pricing-and-data-source-references
  (testing "Price and PriceDefinition type their data source references"
    (is (= "urpx:DataSourceReference"
           (:urpx/hasDataSourceReference (schema/ranges schema/Price))
           (:urpx/hasDataSourceReference (schema/ranges schema/PriceDefinition))))
    (is (= "urpx:DynamicPricing" (:urpx/hasDynamicPricing (schema/ranges schema/Price)))))
  (testing "a DynamicPricing node validates its enums"
    (let [dp {:jsonld/type "urpx:DynamicPricing"
              :urpx/dynamicPricingType {:jsonld/id "urpx:criticalPeakPricing"}
              :urpx/priceSource [{:jsonld/id "urpx:algorithmicSource"}]}]
      (is (m/validate :urpx/DynamicPricing dp {:registry schema/registry}))
      (is (not (m/validate :urpx/DynamicPricing
                           (assoc dp :urpx/dynamicPricingType {:jsonld/id "urpx:monthly"})
                           {:registry schema/registry})))))
  (testing "an inline node of the wrong type under hasDataSourceReference"
    (is (= [[:urpx/hasDataSourceReference 0]]
           (map :in (schema/mistyped-references
                     {:jsonld/type "urpx:Price"
                      :urpx/hasDataSourceReference [{:jsonld/type "urpx:Season"}]}
                     (constantly nil)))))))

(deftest properties-the-corpus-uses
  (let [valid? #(m/validate %1 %2 {:registry schema/registry})]
    (testing "CalculationMethod and MetricSpecification enforce their interval enums"
      (let [cm {:jsonld/type "urpx:CalculationMethod"
                :urpx/aggregationMethod {:jsonld/id "urpx:maximum"}
                :urpx/intervalType {:jsonld/id "urpx:fifteenMinuteInterval"}}]
        (is (valid? :urpx/CalculationMethod cm))
        (is (not (valid? :urpx/CalculationMethod
                         (assoc cm :urpx/intervalType {:jsonld/id "urpx:fortnightly"}))))
        (is (not (valid? :urpx/MetricSpecification
                         {:jsonld/type "urpx:MetricSpecification"
                          :urpx/intervalAlignment {:jsonld/id "urpx:sum"}})))))
    (testing "a PriceUpdateRule is an entity with a builder"
      (is (= {:jsonld/type "urpx:PriceUpdateRule" :urpx/name "Annual reset" :urpx/priority 1}
             (dsl/price-update-rule :name "Annual reset" :priority 1))))
    (testing "an operand's gYearValue decodes to a java.time.Year"
      (is (= (java.time.Year/of 2009)
             (:urpx/gYearValue (coerce/coerce schema/CustomOperandNode
                                              {:jsonld/type "urpx:CustomOperandNode"
                                               :urpx/gYearValue "2009"})))))))

(deftest classes-upstreams-examples-use
  (let [valid? #(m/validate %1 %2 {:registry schema/registry})
        model {:jsonld/type "urpx:PricingModel"
               :urpx/confidence {:jsonld/id "urpx:verified"}
               :urpx/pursuantTo [{:jsonld/id "ex:rule-1"}]}]
    (testing "a PricingModel requires its confidence"
      (is (valid? :urpx/PricingModel model))
      (is (not (valid? :urpx/PricingModel (dissoc model :urpx/confidence)))))
    (testing "a TariffFiling validates the models it contains"
      (let [filing #(hash-map :jsonld/type "urpx:TariffFiling"
                              :urpx/containsPricingModel [%])]
        (is (valid? :urpx/TariffFiling (filing model)))
        (is (not (valid? :urpx/TariffFiling (filing (dissoc model :urpx/confidence)))))
        (is (valid? :urpx/TariffFiling (filing {:jsonld/id "ex:model"})))))
    (testing "a GeneralRule needs an identifier and at least one source"
      (is (valid? :urpx/GeneralRule
                  {:jsonld/type "urpx:GeneralRule" :urpx/generalRuleID "Rule 1"
                   :urpx/hasDataSourceReference [{:jsonld/id "ex:dsr"}]}))
      (is (not (valid? :urpx/GeneralRule
                       {:jsonld/type "urpx:GeneralRule" :urpx/generalRuleID "Rule 1"
                        :urpx/hasDataSourceReference []}))))
    (testing "a service class is a typed reference; a RateGroup is one, by
              the v0.2.x subclass axiom"
      (let [plan {:jsonld/type "urpx:RatePlan"
                  :urpx/hasServiceClass [{:jsonld/id "ex:group"} {:jsonld/id "ex:summer"}]}]
        (is (= [[:urpx/hasServiceClass 1]]
               (map :in (schema/mistyped-references
                         plan {"ex:group" "urpx:RateGroup" "ex:summer" "urpx:Season"}))))))))

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
    (is (nil? (schema/schema-for-type "urpx:NoSuchThing"))))
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

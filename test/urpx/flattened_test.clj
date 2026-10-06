(ns urpx.flattened-test
  "JSON-LD flattened form: a document whose nodes sit at the top level linked by
  bare @id references reads as its nested equivalent.

  The flattened fixtures under test/resources/urpx-flattened are
  scripts/flatten-fixtures.py's output for vendored fixtures of the same name,
  so the flattener is independent of the reader under test."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [urpx.coerce :as coerce]
            [urpx.core :as core]
            [urpx.emit :as emit]
            [urpx.load :as load]
            [urpx.price :as price]
            [urpx.scenario-cost-model :as scm]
            [urpx.schema])
  (:import [java.io StringReader]
           [java.time ZoneId ZonedDateTime]))

(def ^:private at (ZonedDateTime/of 2026 7 15 17 0 0 0 (ZoneId/of "America/Los_Angeles")))

(def ^:private flattened
  ["pge-e-1-2026-03-01" "pge-e-tou-c-2026-03-01" "pge-e-tou-c-baseline-tier-2026-03-01"
   "sce-d-2026-03-20" "sce-care-2026-03-20"])

(defn- path [dir n] (.getPath (io/resource (str dir "/" n ".jsonld"))))
(defn- nested [n] (path "urpx-fixtures" n))
(defn- flat [n] (path "urpx-flattened" n))

(defn- plan [src] (coerce/coerce-rate-plan (core/load-rate-plan src)))
(defn- modifier [src] (coerce/coerce-rate-plan-modifier (core/load-rate-plan-modifier src)))

(defn- graph
  "`parse-graph` over `doc`, written as JSON."
  [doc]
  (core/parse-graph (StringReader. (json/write-str doc))))

;; ---------------------------------------------------------------------------
;; Flattened fixtures resolve as their nested originals
;; ---------------------------------------------------------------------------

(deftest the-flattened-fixtures-are-flattened
  (doseq [n flattened
          :let [g (get (json/read-str (slurp (flat n))) "@graph")]]
    (is (every? #(contains? % "@id") g) n)
    (is (not-any? #(= "urpx:RatePlanVersion" (get % "@type")) (graph (json/read-str (slurp (flat n)))))
        (str n ": no version is left at the top level"))))

(deftest a-flattened-plan-resolves-as-its-nested-original
  (doseq [[n opts] [["pge-e-1-2026-03-01" {}]
                    ["sce-d-2026-03-20" {}]
                    ["pge-e-tou-c-2026-03-01"
                     {:profile-alternatives {"pge:pa-income-tier" "pge:pa-income-tier-standard"}}]]]
    (let [r (price/resolve-prices (plan (nested n)) at opts)]
      (is (seq (:urpx.resolved/ledgers r)) n)
      (is (= r (price/resolve-prices (plan (flat n)) at opts)) n))))

(deftest a-flattened-modifier-composes-as-its-nested-original
  (testing "a separately filed modifier"
    (let [f #(price/resolve-prices-with-modifiers
              (plan (% "sce-d-2026-03-20")) [(modifier (% "sce-care-2026-03-20"))] at)]
      (is (= (f nested) (f flat)))))
  (testing "a modifier with ServiceTiers scoped by ProfileAlternatives, over an
            @graph bundle carrying the plan and its modifiers in a package"
    (let [sel {:profile-alternatives {"pge:pa-baseline-territory-code" "pge:pa-baseline-p-b"
                                      "pge:pa-income-tier" "pge:pa-income-tier-standard"}}
          f   #(price/resolve-prices-with-modifiers
                (plan (% "pge-e-tou-c-2026-03-01"))
                [(modifier (% "pge-e-tou-c-baseline-tier-2026-03-01"))] at sel)
          r   (f nested)]
      (is (some :urpx.resolved/tier-upper-bound
                (mapcat :urpx.resolved/tiers (:urpx.resolved/ledgers r))))
      (is (= r (f flat))))))

(deftest flattened-documents-enumerate-as-their-nested-originals
  (let [models #(scm/enumerate (load/documents (map % flattened))
                               {:at at :compatibility scm/named-versions-add})
        a      (models nested)
        b      (models flat)]
    (is (seq a))
    (is (= (map scm/identity-tuple a) (map scm/identity-tuple b)))
    (is (= (map #(scm/resolve-at % at) a) (map #(scm/resolve-at % at) b)))))

(deftest a-flattened-plan-round-trips-through-emit
  (let [p  (plan (flat "pge-e-1-2026-03-01"))
        rt (-> (emit/write-rate-plan p {:context nil})
               StringReader.
               core/parse-rate-plan
               coerce/coerce-rate-plan)]
    (is (= p rt))
    (is (= (price/resolve-prices (plan (nested "pge-e-1-2026-03-01")) at)
           (price/resolve-prices rt at)))))

(deftest a-nested-fixture-parses-unchanged
  (doseq [f (sort (filter #(str/ends-with? (str %) ".jsonld")
                          (file-seq (io/file (io/resource "urpx-fixtures")))))]
    (is (= (#'core/flatten-graph (core/parse-rate-plan f)) (core/parse-graph f))
        (.getName f))))

;; ---------------------------------------------------------------------------
;; Shapes
;; ---------------------------------------------------------------------------

(def ^:private ctx {"urpx" "https://urpx.org/ns/ontology/"})

(deftest a-version-embeds-under-its-plan-and-its-back-pointer-stays-a-reference
  (let [[p :as g] (graph {"@context" ctx
                          "@graph" [{"@id" "x:p" "@type" "urpx:RatePlan"
                                     "urpx:hasRatePlanVersion" {"@id" "x:v"}}
                                    {"@id" "x:v" "@type" "urpx:RatePlanVersion"
                                     "urpx:forRatePlan" {"@id" "x:p"}}]})]
    (is (= 1 (count g)))
    (is (= {:jsonld/id "x:v" :jsonld/type "urpx:RatePlanVersion"
            :urpx/forRatePlan {:jsonld/id "x:p"}}
           (:urpx/hasRatePlanVersion p)))))

(deftest a-containment-cycle-terminates-and-loses-nothing
  (let [g (graph {"@context" ctx
                  "@graph" [{"@id" "x:a" "@type" "urpx:ServiceTier"
                             "urpx:hasServiceBracket" {"@id" "x:b"}}
                            {"@id" "x:b" "@type" "urpx:ServiceBracket"
                             "urpx:hasServiceBracket" {"@id" "x:a"}}]})]
    (is (= [{:jsonld/id "x:a" :jsonld/type "urpx:ServiceTier"
             :urpx/hasServiceBracket {:jsonld/id "x:b" :jsonld/type "urpx:ServiceBracket"
                                      :urpx/hasServiceBracket {:jsonld/id "x:a"}}}]
           g))))

(deftest a-self-reference-stays-a-reference
  (is (= [{:jsonld/id "x:a" :jsonld/type "urpx:ServiceTier"
           :urpx/hasServiceBracket {:jsonld/id "x:a"}}]
         (graph {"@context" ctx
                 "@graph" [{"@id" "x:a" "@type" "urpx:ServiceTier"
                            "urpx:hasServiceBracket" {"@id" "x:a"}}]}))))

(deftest a-reference-to-a-node-the-document-does-not-state-stays-a-reference
  (let [doc {"@context" ctx
             "@graph" [{"@id" "x:pkg" "@type" "urpx:URPXPackage"
                        "urpx:packages" [{"@id" "x:elsewhere"} {"@id" "x:p"}]}
                       {"@id" "x:p" "@type" "urpx:RatePlan"
                        "urpx:hasPrices" {"@id" "x:missing"}}
                       {"@id" "x:missing"}]}
        [pkg :as g] (graph doc)]
    (is (= 2 (count g)) "a top-level node holding only an @id states nothing")
    (is (= [{:jsonld/id "x:elsewhere"}
            {:jsonld/id "x:p" :jsonld/type "urpx:RatePlan" :urpx/hasPrices {:jsonld/id "x:missing"}}]
           (:urpx/packages pkg)))))

(deftest a-reference-outside-the-containment-properties-stays-a-reference
  (is (= [{:jsonld/id "x:v" :jsonld/type "urpx:RatePlanModifierVersion"
           :urpx/applicableToRatePlan {:jsonld/id "x:p"}
           :urpx/offeredBy {:jsonld/id "x:org"}}
          {:jsonld/id "x:p" :jsonld/type "urpx:RatePlan"}
          {:jsonld/id "x:org" :jsonld/type "urpx:Organization"}]
         (graph {"@context" ctx
                 "@graph" [{"@id" "x:v" "@type" "urpx:RatePlanModifierVersion"
                            "urpx:applicableToRatePlan" {"@id" "x:p"}
                            "urpx:offeredBy" {"@id" "x:org"}}
                           {"@id" "x:p" "@type" "urpx:RatePlan"}
                           {"@id" "x:org" "@type" "urpx:Organization"}]}))))

(deftest an-id-stated-twice-at-the-top-level-is-not-embedded
  (let [doc {"@context" ctx
             "@graph" [{"@id" "x:p" "@type" "urpx:RatePlan"
                        "urpx:hasRatePlanVersion" {"@id" "x:v"}}
                       {"@id" "x:v" "@type" "urpx:RatePlanVersion" "urpx:name" "one"}
                       {"@id" "x:v" "@type" "urpx:RatePlanVersion" "urpx:name" "two"}]}]
    (is (= (#'core/flatten-graph (core/parse-rate-plan (StringReader. (json/write-str doc))))
           (graph doc)))))

(deftest a-blank-node-referenced-once-embeds-without-its-identifier
  (is (= [{:jsonld/id "x:v" :jsonld/type "urpx:RatePlanVersion"
           :urpx/hasCoverageClaim {:jsonld/type "urpx:CoverageClaim"
                                   :urpx/explainedBy {:jsonld/type "urpx:Evaluation"}}
           :urpx/documentedBy {:jsonld/type "urpx:DataSourceReference"}}]
         (graph {"@context" ctx
                 "@graph" [{"@id" "_:b0" "@type" "urpx:CoverageClaim"
                            "urpx:explainedBy" {"@id" "_:b1"}}
                           {"@id" "_:b1" "@type" "urpx:Evaluation"}
                           {"@id" "_:b2" "@type" "urpx:DataSourceReference"}
                           {"@id" "x:v" "@type" "urpx:RatePlanVersion"
                            "urpx:hasCoverageClaim" {"@id" "_:b0"}
                            "urpx:documentedBy" {"@id" "_:b2"}}]}))))

(deftest a-shared-node-embeds-at-each-reference-and-loads-once
  (let [doc {"@context" ctx
             "@graph" [{"@id" "x:pkg" "@type" "urpx:URPXPackage"
                        "urpx:packages" {"@id" "x:p"}
                        "urpx:hasRatePlan" {"@id" "x:p"}}
                       {"@id" "x:p" "@type" "urpx:RatePlan"}]}
        [pkg :as g] (graph doc)]
    (is (= 1 (count g)))
    (is (= (:urpx/hasRatePlan pkg) (:urpx/packages pkg)))
    (is (= [{:jsonld/id "x:p" :jsonld/type "urpx:RatePlan"}]
           (core/expand-document-entities pkg)))))

;; ---------------------------------------------------------------------------
;; The containment properties against urpx.schema
;; ---------------------------------------------------------------------------

(defn- ref-only?
  "True when every arm of schema `s` is a map holding only :jsonld/id."
  [s]
  (let [s (m/schema s)]
    (case (m/type s)
      (:vector :sequential :set) (ref-only? (first (m/children s)))
      :or (every? ref-only? (m/children s))
      :map (= [:jsonld/id] (map first (m/children s)))
      false)))

(defn- property-schemas
  "{property [schema ...]} for every map entry reachable in `s`."
  [acc s]
  (let [s (m/schema s)]
    (case (m/type s)
      :map (reduce (fn [a [k _ v]] (property-schemas (update a k (fnil conj []) v) v))
                   acc (m/children s))
      (:vector :sequential :set :or :and :maybe)
      (reduce property-schemas acc (filter #(try (m/schema %) (catch Exception _ nil))
                                           (m/children s)))
      acc)))

(def ^:private schema-properties
  (reduce (fn [acc v]
            (let [x @v]
              (if (try (m/schema x) (catch Exception _ nil)) (property-schemas acc x) acc)))
          {} (vals (ns-publics 'urpx.schema))))

(deftest no-containment-property-is-reference-only-in-the-schema
  (is (every? ref-only? (schema-properties :urpx/hasCalendar)))
  (is (not-any? ref-only? (schema-properties :urpx/hasPlanElements)))
  (is (empty? (filter #(some->> (schema-properties %) (every? ref-only?))
                      core/containment-properties))))

(deftest every-has-property-is-containment-or-reference-only
  (is (empty? (for [[k ss] schema-properties
                    :when (str/starts-with? (name k) "has")
                    :when (not (core/containment-properties k))
                    :when (not (every? ref-only? ss))]
                k))))

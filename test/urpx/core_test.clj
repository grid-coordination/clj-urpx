(ns urpx.core-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [urpx.core :as core]
            [urpx.test-fixtures :as fixtures]))

(deftest pge-fixture-available
  (is (some? (fixtures/fixture-resource "pge-e-1-2026-03-01"))
      "PG&E E-1 v0.2.0 fixture must be on the classpath"))

(deftest load-graph-pge-e-1
  (testing "PG&E E-1 is a multi-entity @graph bundle. At v0.5.1 the
            urpx:URPXPackage sits INSIDE that @graph as a sibling rather than
            wrapping it, because URPXPackageShape is sh:closed and admits
            neither urpx:ProgramEnrollment nor urpx:RateCase, which these
            filings carry. So load-graph, which returns top-level entities and
            does not descend, sees the package and the siblings but not the
            plan."
    (let [types (set (map :jsonld/type (fixtures/load-graph "pge-e-1-2026-03-01")))]
      (is (contains? types "urpx:URPXPackage"))
      (is (contains? types "urpx:ProgramEnrollment")
          "PG&E E-1 references named programs (CARE, FERA, CCA, DA, TBS)")
      (is (contains? types "urpx:SourcePublication")
          "the source the filing cites")
      (is (not (contains? types "urpx:RatePlan"))
          "the plan is embedded in the package, not a top-level sibling")))
  (testing "descending the wrapper reaches the plan and its modifier"
    (let [types (set (map :jsonld/type
                          (mapcat core/expand-document-entities
                                  (fixtures/load-graph "pge-e-1-2026-03-01"))))]
      (is (contains? types "urpx:RatePlan"))
      (is (contains? types "urpx:RatePlanModifier")))))

(deftest load-rate-plan-pge-e-1
  (testing "raw load_rate_plan extracts the unique urpx:RatePlan"
    (let [path (.getPath (fixtures/fixture-resource "pge-e-1-2026-03-01"))
          plan (core/load-rate-plan path)]
      (is (= "urpx:RatePlan" (:jsonld/type plan)))
      (is (= "pge:e-1" (:jsonld/id plan)))
      (testing "v0.2.0: ratePlanUniqueIdentifier is the unique-id field (string, raw)"
        (is (= "E-1" (:urpx/ratePlanUniqueIdentifier plan))))
      (testing "v0.2.0 BillingModel.hasPriceDefinition lives at the BillingModel level"
        (let [pds (get-in plan [:urpx/hasRatePlanVersion
                                :urpx/hasPlanElements
                                :urpx/hasBillingModel
                                :urpx/hasPriceDefinition])]
          (is (vector? pds))
          (is (every? #(some? (get-in % [:urpx/referencesLedger :jsonld/id])) pds)
              "every PD references its parent Ledger"))))))

(deftest load-rate-plan-accepts-url-and-reader
  (testing "load-rate-plan accepts any source io/reader can handle.
            Specifically, a java.net.URL — which is what (io/resource ...)
            returns — must work, both for `file:` (test classpath) and
            `jar:file:` (production uberjar) flavors.

            Reproduces the deploy bug seen when loading from an uberjar:
            previously load-rate-plan called (io/file path), which throws
            IllegalArgumentException 'Not a file: jar:file:...' on the
            jar:file: URL form."
    (let [resource-url (fixtures/fixture-resource "pge-e-1-2026-03-01")]
      (testing "URL form (io/resource result) works"
        (is (instance? java.net.URL resource-url))
        (let [plan (core/load-rate-plan resource-url)]
          (is (= "urpx:RatePlan" (:jsonld/type plan)))
          (is (= "pge:e-1" (:jsonld/id plan)))))
      (testing "load-graph accepts the URL too (the load-rate-plan-modifier
                / load-rate-plan / load-graph chain shares this code path)"
        (let [entities (mapcat core/expand-document-entities
                               (core/load-graph resource-url))]
          (is (some #(= "urpx:RatePlan" (:jsonld/type %)) entities))
          (is (some #(= "urpx:RatePlanModifier" (:jsonld/type %)) entities))))
      (testing "Reader is also accepted (io/reader IOFactory)"
        (with-open [r (io/reader resource-url)]
          (let [plan (core/load-rate-plan r)]
            (is (= "urpx:RatePlan" (:jsonld/type plan)))))))))

(deftest prefixed-key-conversion
  (testing "via parse-rate-plan on a small inline document"
    (let [src (java.io.StringReader.
               (json/write-str {"@id" "urpx:foo"
                                "@type" "urpx:Thing"
                                "urpx:name" "x"
                                "plain" 1}))
          parsed (core/parse-rate-plan src)]
      (is (= {:jsonld/id "urpx:foo"
              :jsonld/type "urpx:Thing"
              :urpx/name "x"
              :plain 1}
             parsed)))))

;; ---------------------------------------------------------------------------
;; @graph container handling
;; ---------------------------------------------------------------------------
;;
;; Fixtures are defined as Clojure data with string keys (matching the JSON-LD
;; property names) and serialized to JSON via clojure.data.json/write-str at
;; test time. Avoids the eye-bleed of escaped JSON-in-string literals.

(defn- jsonld-reader
  "Serialize `edn` to JSON and wrap in a StringReader (urpx.core/parse-* accept readers)."
  [edn]
  (java.io.StringReader. (json/write-str edn)))

(defn- with-temp-jsonld
  "Serialize `edn` to a temp .jsonld file, run `f` against the path; cleans up."
  [edn f]
  (let [tmp (java.io.File/createTempFile "urpx-test-" ".jsonld")]
    (try
      (spit tmp (json/write-str edn))
      (f (.getAbsolutePath tmp))
      (finally (.delete tmp)))))

(def context
  {"urpx" "https://urpx.org/ns/ontology/"})

(def single-entity-doc
  {"@type" "urpx:RatePlan"
   "@id" "x:plan"
   "urpx:ratePlanUniqueIdentifier" "P1"
   "urpx:name" "Plan One"
   "urpx:initialEffectiveDate" "2026-01-01"})

(def program-care
  {"@type" "urpx:ProgramEnrollment"
   "@id" "x:program-care"
   "urpx:programName" "CARE"})

(def plan-one
  {"@type" "urpx:RatePlan"
   "@id" "x:plan"
   "urpx:ratePlanUniqueIdentifier" "P1"
   "urpx:name" "Plan One"
   "urpx:initialEffectiveDate" "2026-01-01"})

(def modifier-m1
  {"@type" "urpx:RatePlanModifier"
   "@id" "x:mod"
   "urpx:modifierUniqueIdentifier" "M1"
   "urpx:name" "Modifier"
   "urpx:initialEffectiveDate" "2026-01-01"})

(def graph-with-plan-and-modifier
  {"@context" context
   "@graph" [program-care plan-one modifier-m1]})

(def graph-with-no-plan
  {"@context" context
   "@graph" [program-care]})

(def graph-with-two-plans
  {"@context" context
   "@graph" [{"@type" "urpx:RatePlan" "@id" "x:plan-a"
              "urpx:ratePlanUniqueIdentifier" "PA" "urpx:name" "Plan A"
              "urpx:initialEffectiveDate" "2026-01-01"}
             {"@type" "urpx:RatePlan" "@id" "x:plan-b"
              "urpx:ratePlanUniqueIdentifier" "PB" "urpx:name" "Plan B"
              "urpx:initialEffectiveDate" "2026-01-01"}]})

(def standalone-modifier-doc
  (assoc modifier-m1 "urpx:name" "Standalone Modifier"))

(def graph-with-two-modifiers
  {"@context" context
   "@graph" [{"@type" "urpx:RatePlanModifier" "@id" "x:mod-a"
              "urpx:modifierUniqueIdentifier" "MA" "urpx:name" "Modifier A"
              "urpx:initialEffectiveDate" "2026-01-01"}
             {"@type" "urpx:RatePlanModifier" "@id" "x:mod-b"
              "urpx:modifierUniqueIdentifier" "MB" "urpx:name" "Modifier B"
              "urpx:initialEffectiveDate" "2026-01-01"}]})

(def urpx-package-with-plan
  {"@context" context
   "@type" "urpx:URPXPackage"
   "@id" "x:pkg"
   "urpx:targetURPXVersion" "https://example.com/urpx-v0.5.1"
   "urpx:hasRatePlan" plan-one})

(def urpx-package-with-plan-and-modifier
  (assoc urpx-package-with-plan "urpx:hasRatePlanModifier" modifier-m1))

(def urpx-package-with-standalone-modifier
  {"@context" context
   "@type" "urpx:URPXPackage"
   "@id" "x:pkg"
   "urpx:targetURPXVersion" "https://example.com/urpx-v0.5.1"
   "urpx:hasRatePlanModifier" (assoc modifier-m1 "urpx:name" "Standalone Modifier")})

(def urpx-package-with-packages-pointer
  {"@context" context
   "@type" "urpx:URPXPackage"
   "@id" "x:pkg"
   "urpx:targetURPXVersion" "https://example.com/urpx-v0.5.1"
   "urpx:packages" [plan-one modifier-m1]})

(deftest parse-graph-single-entity-doc
  (testing "A document with no @graph yields a one-element vector containing the entity"
    (let [entities (core/parse-graph (jsonld-reader single-entity-doc))]
      (is (vector? entities))
      (is (= 1 (count entities)))
      (is (= "urpx:RatePlan" (:jsonld/type (first entities))))
      (is (= "x:plan" (:jsonld/id (first entities)))))))

(deftest parse-graph-multi-entity-doc
  (testing "A document with @graph yields the graph contents as a vector"
    (let [entities (core/parse-graph (jsonld-reader graph-with-plan-and-modifier))]
      (is (= 3 (count entities)))
      (is (= ["urpx:ProgramEnrollment" "urpx:RatePlan" "urpx:RatePlanModifier"]
             (mapv :jsonld/type entities))))))

(deftest parse-rate-plan-leaves-graph-untouched
  (testing "parse-rate-plan is the raw parser; @graph stays under :jsonld/graph"
    (let [parsed (core/parse-rate-plan (jsonld-reader graph-with-plan-and-modifier))]
      (is (vector? (:jsonld/graph parsed)))
      (is (= 3 (count (:jsonld/graph parsed))))
      (is (nil? (:jsonld/type parsed))
          "the wrapper has @context + @graph but no @type"))))

(deftest load-rate-plan-unwraps-graph-with-unique-plan
  (testing "When @graph contains exactly one urpx:RatePlan, load-rate-plan returns it"
    (with-temp-jsonld graph-with-plan-and-modifier
      (fn [path]
        (let [plan (core/load-rate-plan path)]
          (is (= "urpx:RatePlan" (:jsonld/type plan)))
          (is (= "x:plan" (:jsonld/id plan))))))))

(deftest load-rate-plan-throws-on-no-plan
  (testing "load-rate-plan throws clearly when the document has no urpx:RatePlan"
    (with-temp-jsonld graph-with-no-plan
      (fn [path]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"No urpx:RatePlan entity"
                              (core/load-rate-plan path)))))))

(deftest load-rate-plan-throws-on-multiple-plans
  (testing "load-rate-plan throws when @graph carries more than one urpx:RatePlan"
    (with-temp-jsonld graph-with-two-plans
      (fn [path]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Multiple urpx:RatePlan entities"
                              (core/load-rate-plan path)))))))

(deftest load-graph-exposes-modifier-from-bundled-doc
  (testing "load-graph lets callers pluck siblings (modifiers, programs) from a
            multi-entity document"
    (with-temp-jsonld graph-with-plan-and-modifier
      (fn [path]
        (let [entities (core/load-graph path)
              modifier (first (filter #(= "urpx:RatePlanModifier" (:jsonld/type %))
                                      entities))]
          (is (some? modifier))
          (is (= "x:mod" (:jsonld/id modifier))))))))

;; ---------------------------------------------------------------------------
;; load-rate-plan-modifier — standalone & bundled modifier filings
;; ---------------------------------------------------------------------------

(deftest load-rate-plan-modifier-standalone
  (testing "a document whose sole entity is urpx:RatePlanModifier
            (no @graph wrapper) — the CPAU E-2-G shape"
    (with-temp-jsonld standalone-modifier-doc
      (fn [path]
        (let [m (core/load-rate-plan-modifier path)]
          (is (= "urpx:RatePlanModifier" (:jsonld/type m)))
          (is (= "x:mod" (:jsonld/id m))))))))

(deftest load-rate-plan-modifier-from-bundled-graph
  (testing "When @graph carries a unique urpx:RatePlanModifier alongside a
            base RatePlan (PG&E-style bundling), load-rate-plan-modifier
            returns the modifier"
    (with-temp-jsonld graph-with-plan-and-modifier
      (fn [path]
        (let [m (core/load-rate-plan-modifier path)]
          (is (= "urpx:RatePlanModifier" (:jsonld/type m)))
          (is (= "x:mod" (:jsonld/id m))))))))

(deftest load-rate-plan-modifier-throws-on-no-modifier
  (testing "load-rate-plan-modifier throws when no urpx:RatePlanModifier is present"
    (with-temp-jsonld graph-with-no-plan
      (fn [path]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"No urpx:RatePlanModifier entity"
                              (core/load-rate-plan-modifier path)))))))

(deftest load-rate-plan-modifier-throws-on-multiple-modifiers
  (testing "load-rate-plan-modifier throws when @graph carries more than one modifier"
    (with-temp-jsonld graph-with-two-modifiers
      (fn [path]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Multiple urpx:RatePlanModifier entities"
                              (core/load-rate-plan-modifier path)))))))

;; ---------------------------------------------------------------------------
;; URPXPackage wrapper traversal
;; ---------------------------------------------------------------------------

(deftest load-rate-plan-descends-into-urpx-package
  (testing "a urpx:URPXPackage wrapper traverses urpx:hasRatePlan to find the
            inner plan"
    (with-temp-jsonld urpx-package-with-plan
      (fn [path]
        (let [plan (core/load-rate-plan path)]
          (is (= "urpx:RatePlan" (:jsonld/type plan)))
          (is (= "x:plan" (:jsonld/id plan))))))))

(deftest load-descends-into-urpx-package-packages-pointer
  (testing "urpx:packages, the general containment pointer added in v0.3.0,
            reaches both the plan and the modifier a package carries"
    (with-temp-jsonld urpx-package-with-packages-pointer
      (fn [path]
        (is (= "x:plan" (:jsonld/id (core/load-rate-plan path))))
        (is (= "x:mod"  (:jsonld/id (core/load-rate-plan-modifier path))))))))

(deftest load-rate-plan-modifier-descends-into-urpx-package
  (testing "load-rate-plan-modifier traverses urpx:hasRatePlanModifier
            on a URPXPackage-wrapped filing"
    (with-temp-jsonld urpx-package-with-plan-and-modifier
      (fn [path]
        (let [m (core/load-rate-plan-modifier path)]
          (is (= "urpx:RatePlanModifier" (:jsonld/type m)))
          (is (= "x:mod" (:jsonld/id m))))))))

(deftest load-rate-plan-modifier-from-standalone-urpx-package
  (testing "a URPXPackage carrying only a hasRatePlanModifier
            (no hasRatePlan) — the wrapped equivalent of a standalone modifier
            filing — yields the modifier via load-rate-plan-modifier"
    (with-temp-jsonld urpx-package-with-standalone-modifier
      (fn [path]
        (let [m (core/load-rate-plan-modifier path)]
          (is (= "urpx:RatePlanModifier" (:jsonld/type m)))
          (is (= "x:mod" (:jsonld/id m))))))))

(deftest load-rate-plan-still-throws-on-urpx-package-without-plan
  (testing "a URPXPackage with no inner urpx:RatePlan still
            errors clearly (the modifier-only doc isn't a base plan)"
    (with-temp-jsonld urpx-package-with-standalone-modifier
      (fn [path]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"No urpx:RatePlan entity"
                              (core/load-rate-plan path)))))))

;; ---------------------------------------------------------------------------
;; Key encoder — keyword → JSON-LD string-key form
;; ---------------------------------------------------------------------------

(deftest keyword->jsonld-key-cases
  (testing "reserved JSON-LD keys (:jsonld/X) → '@X'"
    (is (= "@type"    (core/keyword->jsonld-key :jsonld/type)))
    (is (= "@id"      (core/keyword->jsonld-key :jsonld/id)))
    (is (= "@context" (core/keyword->jsonld-key :jsonld/context)))
    (is (= "@graph"   (core/keyword->jsonld-key :jsonld/graph))))
  (testing "namespaced keywords → 'ns:local' CURIE form"
    (is (= "urpx:name"      (core/keyword->jsonld-key :urpx/name)))
    (is (= "urpx:unitPrice" (core/keyword->jsonld-key :urpx/unitPrice))))
  (testing "bare keywords → unprefixed string"
    (is (= "plain" (core/keyword->jsonld-key :plain)))))

(deftest keys->jsonld-walks-nested-structure
  (testing "keys->jsonld walks maps and sequences, converting every
            namespaced-keyword key back to its JSON-LD string form (leaves
            unchanged)"
    (let [typed {:jsonld/type "urpx:RatePlan" :urpx/timezoneIdentifier "America/Los_Angeles"
                 :jsonld/id "x:plan"
                 :urpx/name "Plan One"
                 :urpx/hasRatePlanVersion
                 {:jsonld/type "urpx:RatePlanVersion"
                  :urpx/changeReason {:jsonld/id "urpx:rateCase"}
                  :urpx/forRatePlan {:jsonld/id "ex:parent"}
                  :urpx/hasCoverageClaim {:jsonld/type "urpx:CoverageClaim" :urpx/completenessResult {:jsonld/id "urpx:complete"}}
                  :urpx/serviceRole {:jsonld/id "urpx:bundledServiceRole"}
                  :urpx/effectiveDateTime "2026-01-01T00:00:00"
                  :urpx/hasPriceDefinition [{:jsonld/type "urpx:PriceDefinition"
                                             :urpx/name "PD-1"}
                                            {:jsonld/type "urpx:PriceDefinition"
                                             :urpx/name "PD-2"}]}}
          out (core/keys->jsonld typed)]
      (is (= "urpx:RatePlan" (get out "@type")))
      (is (= "x:plan"        (get out "@id")))
      (is (= "Plan One"      (get out "urpx:name")))
      (let [version (get out "urpx:hasRatePlanVersion")]
        (is (= "urpx:RatePlanVersion" (get version "@type")))
        (is (= "2026-01-01T00:00:00"  (get version "urpx:effectiveDateTime")))
        (let [pds (get version "urpx:hasPriceDefinition")]
          (is (vector? pds))
          (is (= 2 (count pds)))
          (is (= "urpx:PriceDefinition" (get (first pds) "@type")))
          (is (= "PD-1"                  (get (first pds) "urpx:name"))))))))

(deftest keys->jsonld-roundtrips-against-parsed-data
  (testing "parse → keys->jsonld → write → parse yields the same
            JSON-LD-keyed shape (round-trip via the existing parser path)"
    (let [parsed (core/parse-rate-plan
                  (jsonld-reader graph-with-plan-and-modifier))
          json-keyed (core/keys->jsonld parsed)
          re-parsed (-> json-keyed
                        json/write-str
                        java.io.StringReader.
                        core/parse-rate-plan)]
      (is (= parsed re-parsed)
          "structural equivalence under transform-keys ∘ keys->jsonld"))))

(defn- doc-reader
  "A reader over `doc`, a keywordized JSON-LD map."
  [doc]
  (jsonld-reader (core/keys->jsonld doc)))

(def ^:private nested-plan {:jsonld/id "x:p" :jsonld/type "urpx:RatePlan"})
(def ^:private nested-modifier {:jsonld/id "x:m" :jsonld/type "urpx:RatePlanModifier"})
(def ^:private nested-sibling {:jsonld/id "x:s" :jsonld/type "urpx:SourcePublication"})

(deftest a-nested-graph-is-flattened
  (testing "a simple graph object inside the top-level @graph, as upstream
            test-cases/pge-e-tou-c has it, contributes its members"
    (let [doc {:jsonld/context {:urpx "https://urpx.org/ns#"}
               :jsonld/graph [{:jsonld/graph [nested-plan nested-modifier]} nested-sibling]}]
      (is (= [nested-plan nested-modifier nested-sibling] (core/load-graph (doc-reader doc))))
      (is (= "x:p" (:jsonld/id (core/load-rate-plan (doc-reader doc)))))
      (is (= "x:m" (:jsonld/id (core/load-rate-plan-modifier (doc-reader doc)))))))
  (testing "at any depth"
    (let [doc {:jsonld/graph [{:jsonld/graph [{:jsonld/graph [nested-plan]} nested-modifier]}]}]
      (is (= [nested-plan nested-modifier] (core/load-graph (doc-reader doc))))))
  (testing "@graph holding one node object rather than an array"
    (is (= [nested-plan] (core/load-graph (doc-reader {:jsonld/graph {:jsonld/graph nested-plan}})))))
  (testing "a graph object with an @id names a graph, and the name is not an entity"
    (let [doc {:jsonld/graph [{:jsonld/id "x:g" :jsonld/graph [nested-plan]} nested-modifier]}]
      (is (= [nested-plan nested-modifier] (core/load-graph (doc-reader doc)))))))

(deftest a-node-with-a-graph-and-properties-is-kept
  (testing "a node carrying a property a graph object may not is an entity too,
            and precedes its members without its @graph"
    (let [pkg {:jsonld/id "x:pkg" :jsonld/type "urpx:URPXPackage"}]
      (is (= [pkg nested-plan]
             (core/load-graph (doc-reader {:jsonld/graph [(assoc pkg :jsonld/graph [nested-plan])]}))))
      (is (= [pkg nested-plan]
             (core/load-graph (doc-reader (assoc pkg :jsonld/graph [nested-plan]))))
          "at the top level as well"))))

(deftest a-fixture-nested-one-graph-deeper-loads-the-same
  (testing "pge-e-1 reshaped the way upstream test-cases/pge-e-tou-c is: all but
            one of its @graph members moved into a graph object inside it"
    (let [parsed   (core/parse-rate-plan (fixtures/fixture-resource "pge-e-1-2026-03-01"))
          g        (:jsonld/graph parsed)
          moved    (vec (butlast g))
          reshaped (assoc parsed :jsonld/graph [{:jsonld/graph moved} (last g)])]
      (is (some #(= "urpx:URPXPackage" (:jsonld/type %)) moved) "the package is among those moved")
      (is (= (set (core/load-graph (doc-reader parsed)))
             (set (core/load-graph (doc-reader reshaped)))))
      (is (= "pge:e-1" (:jsonld/id (core/load-rate-plan (doc-reader reshaped))))))))

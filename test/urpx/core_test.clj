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
  (testing "PG&E E-1 fixture is a multi-entity @graph bundle"
    (let [entities (fixtures/load-graph "pge-e-1-2026-03-01")
          types (mapv :jsonld/type entities)]
      (is (some #{"urpx:RatePlan"} types))
      (is (some #{"urpx:RatePlanModifier"} types))
      (is (some #{"urpx:TariffPublication"} types)
          "PG&E filings carry a TariffPublication entity in the @graph")
      (is (some #{"urpx:ProgramEnrollment"} types)
          "PG&E E-1 references named programs (CARE, FERA, CCA, DA, TBS)"))))

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
  (testing "URPX-drt: load-rate-plan accepts any source io/reader can handle.
            Specifically, a java.net.URL — which is what (io/resource ...)
            returns — must work, both for `file:` (test classpath) and
            `jar:file:` (production uberjar) flavors.

            Reproduces the production deploy bug surfaced in PPS-6f0:
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
        (let [entities (core/load-graph resource-url)]
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
  {"urpx" "https://urpx-org.github.io/urpx/ontology/"})

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

(def urpx-document-with-plan
  {"@context" context
   "@type" "urpx:URPXDocument"
   "@id" "x:doc"
   "urpx:targetURPXVersion" "https://example.com/urpx-v0.2.0"
   "urpx:hasRatePlan" plan-one})

(def urpx-document-with-plan-and-modifier
  (assoc urpx-document-with-plan "urpx:hasRatePlanModifier" modifier-m1))

(def urpx-document-with-standalone-modifier
  {"@context" context
   "@type" "urpx:URPXDocument"
   "@id" "x:doc"
   "urpx:targetURPXVersion" "https://example.com/urpx-v0.2.0"
   "urpx:hasRatePlanModifier" (assoc modifier-m1 "urpx:name" "Standalone Modifier")})

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
;; load-rate-plan-modifier (URPX-g7z) — standalone & bundled modifier filings
;; ---------------------------------------------------------------------------

(deftest load-rate-plan-modifier-standalone
  (testing "URPX-g7z: a document whose sole entity is urpx:RatePlanModifier
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
;; URPXDocument wrapper traversal (URPX-8hq)
;; ---------------------------------------------------------------------------

(deftest load-rate-plan-descends-into-urpx-document
  (testing "URPX-8hq: a v0.2.0 URPXDocument-wrapped filing — load-rate-plan
            traverses urpx:hasRatePlan to find the inner plan"
    (with-temp-jsonld urpx-document-with-plan
      (fn [path]
        (let [plan (core/load-rate-plan path)]
          (is (= "urpx:RatePlan" (:jsonld/type plan)))
          (is (= "x:plan" (:jsonld/id plan))))))))

(deftest load-rate-plan-modifier-descends-into-urpx-document
  (testing "URPX-8hq: load-rate-plan-modifier traverses urpx:hasRatePlanModifier
            on a URPXDocument-wrapped filing"
    (with-temp-jsonld urpx-document-with-plan-and-modifier
      (fn [path]
        (let [m (core/load-rate-plan-modifier path)]
          (is (= "urpx:RatePlanModifier" (:jsonld/type m)))
          (is (= "x:mod" (:jsonld/id m))))))))

(deftest load-rate-plan-modifier-from-standalone-urpx-document
  (testing "URPX-8hq: a URPXDocument carrying only a hasRatePlanModifier
            (no hasRatePlan) — the wrapped equivalent of a standalone modifier
            filing — yields the modifier via load-rate-plan-modifier"
    (with-temp-jsonld urpx-document-with-standalone-modifier
      (fn [path]
        (let [m (core/load-rate-plan-modifier path)]
          (is (= "urpx:RatePlanModifier" (:jsonld/type m)))
          (is (= "x:mod" (:jsonld/id m))))))))

(deftest load-rate-plan-still-throws-on-urpx-document-without-plan
  (testing "URPX-8hq: a URPXDocument with no inner urpx:RatePlan still
            errors clearly (the modifier-only doc isn't a base plan)"
    (with-temp-jsonld urpx-document-with-standalone-modifier
      (fn [path]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"No urpx:RatePlan entity"
                              (core/load-rate-plan path)))))))

;; ---------------------------------------------------------------------------
;; Key encoder (URPX-moq) — keyword → JSON-LD string-key form
;; ---------------------------------------------------------------------------

(deftest keyword->jsonld-key-cases
  (testing "URPX-moq: reserved JSON-LD keys (:jsonld/X) → '@X'"
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
  (testing "URPX-moq: keys->jsonld walks maps and sequences, converting every
            namespaced-keyword key back to its JSON-LD string form (leaves
            unchanged)"
    (let [typed {:jsonld/type "urpx:RatePlan"
                 :jsonld/id "x:plan"
                 :urpx/name "Plan One"
                 :urpx/hasRatePlanVersion
                 {:jsonld/type "urpx:RatePlanVersion"
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
  (testing "URPX-moq: parse → keys->jsonld → write → parse yields the same
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

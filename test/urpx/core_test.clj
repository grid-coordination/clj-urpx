(ns urpx.core-test
  (:require [clojure.test :refer [deftest is testing]]
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

(deftest prefixed-key-conversion
  (testing "via parse-rate-plan on a small inline document"
    (let [src (java.io.StringReader.
               "{\"@id\":\"urpx:foo\",\"@type\":\"urpx:Thing\",\"urpx:name\":\"x\",\"plain\":1}")
          parsed (core/parse-rate-plan src)]
      (is (= {:jsonld/id "urpx:foo"
              :jsonld/type "urpx:Thing"
              :urpx/name "x"
              :plain 1}
             parsed)))))

;; ---------------------------------------------------------------------------
;; @graph container handling
;; ---------------------------------------------------------------------------

(defn- reader [^String s]
  (java.io.StringReader. s))

(def single-entity-doc
  "{\"@type\":\"urpx:RatePlan\",\"@id\":\"x:plan\",
    \"urpx:ratePlanUniqueIdentifier\":\"P1\",\"urpx:name\":\"Plan One\",
    \"urpx:initialEffectiveDate\":\"2026-01-01\"}")

(def graph-with-plan-and-modifier
  "{\"@context\":{\"urpx\":\"https://schema.urpx.org/\"},
    \"@graph\":[
      {\"@type\":\"urpx:ProgramEnrollment\",\"@id\":\"x:program-care\",
       \"urpx:programName\":\"CARE\"},
      {\"@type\":\"urpx:RatePlan\",\"@id\":\"x:plan\",
       \"urpx:ratePlanUniqueIdentifier\":\"P1\",\"urpx:name\":\"Plan One\",
       \"urpx:initialEffectiveDate\":\"2026-01-01\"},
      {\"@type\":\"urpx:RatePlanModifier\",\"@id\":\"x:mod\",
       \"urpx:modifierUniqueIdentifier\":\"M1\",\"urpx:name\":\"Modifier\",
       \"urpx:initialEffectiveDate\":\"2026-01-01\"}
    ]}")

(def graph-with-no-plan
  "{\"@context\":{\"urpx\":\"https://schema.urpx.org/\"},
    \"@graph\":[
      {\"@type\":\"urpx:ProgramEnrollment\",\"@id\":\"x:program-care\",
       \"urpx:programName\":\"CARE\"}
    ]}")

(def graph-with-two-plans
  "{\"@context\":{\"urpx\":\"https://schema.urpx.org/\"},
    \"@graph\":[
      {\"@type\":\"urpx:RatePlan\",\"@id\":\"x:plan-a\",
       \"urpx:ratePlanUniqueIdentifier\":\"PA\",\"urpx:name\":\"Plan A\",
       \"urpx:initialEffectiveDate\":\"2026-01-01\"},
      {\"@type\":\"urpx:RatePlan\",\"@id\":\"x:plan-b\",
       \"urpx:ratePlanUniqueIdentifier\":\"PB\",\"urpx:name\":\"Plan B\",
       \"urpx:initialEffectiveDate\":\"2026-01-01\"}
    ]}")

(deftest parse-graph-single-entity-doc
  (testing "A document with no @graph yields a one-element vector containing the entity"
    (let [entities (core/parse-graph (reader single-entity-doc))]
      (is (vector? entities))
      (is (= 1 (count entities)))
      (is (= "urpx:RatePlan" (:jsonld/type (first entities))))
      (is (= "x:plan" (:jsonld/id (first entities)))))))

(deftest parse-graph-multi-entity-doc
  (testing "A document with @graph yields the graph contents as a vector"
    (let [entities (core/parse-graph (reader graph-with-plan-and-modifier))]
      (is (= 3 (count entities)))
      (is (= ["urpx:ProgramEnrollment" "urpx:RatePlan" "urpx:RatePlanModifier"]
             (mapv :jsonld/type entities))))))

(deftest parse-rate-plan-leaves-graph-untouched
  (testing "parse-rate-plan is the raw parser; @graph stays under :jsonld/graph"
    (let [parsed (core/parse-rate-plan (reader graph-with-plan-and-modifier))]
      (is (vector? (:jsonld/graph parsed)))
      (is (= 3 (count (:jsonld/graph parsed))))
      (is (nil? (:jsonld/type parsed))
          "the wrapper has @context + @graph but no @type"))))

(defn- with-temp-jsonld
  "Write `s` to a temp .jsonld file and run `f` against the path; cleans up."
  [s f]
  (let [tmp (java.io.File/createTempFile "urpx-test-" ".jsonld")]
    (try
      (spit tmp s)
      (f (.getAbsolutePath tmp))
      (finally (.delete tmp)))))

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

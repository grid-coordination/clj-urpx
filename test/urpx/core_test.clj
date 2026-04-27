(ns urpx.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [urpx.core :as core]
            [urpx.test-fixtures :as fixtures]))

(deftest cpau-tou-fixture-available
  (is (some? (fixtures/fixture-resource "cpau-e-1-tou-2026-01-01"))
      "CPAU E-1 TOU JSON-LD fixture must be on the classpath"))

(deftest load-rate-plan-cpau-tou
  (let [plan (fixtures/load-raw "cpau-e-1-tou-2026-01-01")]

    (testing "top-level rate plan identity"
      (is (= "urpx:RatePlan" (:jsonld/type plan)))
      (is (= "urpx:cpau-e-1-tou" (:jsonld/id plan)))
      (is (= "E-1 TOU" (:urpx/ratePlanIdentifier plan)))
      (is (= "2026-01-01" (:urpx/initialEffectiveDate plan))))

    (testing "JSON-LD @context preserved"
      (is (map? (:jsonld/context plan)))
      (is (= "https://schema.urpx.org/" (get-in plan [:jsonld/context :urpx]))))

    (testing "rate plan version"
      (let [version (:urpx/hasRatePlanVersion plan)]
        (is (= "urpx:RatePlanVersion" (:jsonld/type version)))
        (is (= "2026-01-01" (:urpx/versionNumber version)))
        (is (= "USD" (:urpx/priceCurrency version)))
        (is (= {:jsonld/id "urpx:electricity"} (:urpx/commodity version)))))

    (testing "seasons"
      (let [seasons (get-in plan [:urpx/hasRatePlanVersion
                                  :urpx/hasPlanElements
                                  :urpx/hasSeasons])]
        (is (= 2 (count seasons)))
        (is (= #{"Summer" "Winter"} (set (map :urpx/seasonName seasons))))
        (let [summer (first (filter #(= "Summer" (:urpx/seasonName %)) seasons))]
          (is (= ["--06" "--07" "--08" "--09"] (:urpx/month summer))))))

    (testing "TOU schedule with tiers and time brackets"
      (let [tiers (get-in plan [:urpx/hasRatePlanVersion
                                :urpx/hasPlanElements
                                :urpx/hasBillingModel
                                :urpx/hasTOUSchedule
                                :urpx/hasTOUTier])]
        (is (= 3 (count tiers)))
        (is (= #{"Peak" "Off-Peak" "Super Off-Peak"}
               (set (map :urpx/tierSourceName tiers))))
        (let [peak (first (filter #(= "Peak" (:urpx/tierSourceName %)) tiers))
              summer-peak (first (:urpx/hasTOUPeriod peak))
              brackets (:urpx/hasTimeBracket summer-peak)]
          (is (= 1 (:urpx/touTierNumber peak)))
          (is (= "Summer Peak" (:urpx/name summer-peak)))
          (is (= 1 (count brackets)))
          (is (= "16:00:00" (:urpx/startTime (first brackets))))
          (is (= "21:00:00" (:urpx/endTime (first brackets)))))))

    (testing "prices include flat and TOU price sets"
      (let [prices (get-in plan [:urpx/hasRatePlanVersion :urpx/hasPrices])
            flat (:urpx/hasPrice prices)
            sets (:urpx/hasPriceSet prices)]
        (is (= 3 (count flat)))
        (is (= #{"Customer Charge" "Distribution" "Public Benefits"}
               (set (map :urpx/name flat))))
        (is (= 1 (count sets)))
        (is (= 6 (count (:urpx/hasPrice (first sets)))))
        (let [summer-peak (first (filter #(= "Commodity Summer Peak" (:urpx/name %))
                                         (:urpx/hasPrice (first sets))))]
          (is (= "0.23354" (:urpx/unitPrice summer-peak))))))))

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
;; URPX-zvl — JSON-LD @graph container handling
;; ---------------------------------------------------------------------------

(defn- reader [^String s]
  (java.io.StringReader. s))

(def single-entity-doc
  "{\"@type\":\"urpx:RatePlan\",\"@id\":\"x:plan\",
    \"urpx:ratePlanIdentifier\":\"P1\",\"urpx:name\":\"Plan One\",
    \"urpx:initialEffectiveDate\":\"2026-01-01\"}")

(def graph-with-plan-and-modifier
  "{\"@context\":{\"urpx\":\"https://schema.urpx.org/\"},
    \"@graph\":[
      {\"@type\":\"urpx:ProgramEnrollment\",\"@id\":\"x:program-care\",
       \"urpx:programName\":\"CARE\"},
      {\"@type\":\"urpx:RatePlan\",\"@id\":\"x:plan\",
       \"urpx:ratePlanIdentifier\":\"P1\",\"urpx:name\":\"Plan One\",
       \"urpx:initialEffectiveDate\":\"2026-01-01\"},
      {\"@type\":\"urpx:RatePlanModifier\",\"@id\":\"x:mod\",
       \"urpx:ratePlanIdentifier\":\"M1\",\"urpx:name\":\"Modifier\",
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
       \"urpx:ratePlanIdentifier\":\"PA\",\"urpx:name\":\"Plan A\",
       \"urpx:initialEffectiveDate\":\"2026-01-01\"},
      {\"@type\":\"urpx:RatePlan\",\"@id\":\"x:plan-b\",
       \"urpx:ratePlanIdentifier\":\"PB\",\"urpx:name\":\"Plan B\",
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

;; load-rate-plan goes through io/file, so the StringReader-based fixtures need
;; a tiny detour: write them to temp files and load from there.

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

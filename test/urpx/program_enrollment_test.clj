(ns urpx.program-enrollment-test
  "urpx:ProgramEnrollment survives load, emit and load again, as a top-level
  node and inline where it is referenced."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [urpx.coerce :as coerce]
            [urpx.core :as core]
            [urpx.emit :as emit]
            [urpx.load :as load]
            [urpx.schema :as schema]
            [urpx.test-fixtures :as fixtures])
  (:import [java.io StringReader]
           [java.time LocalDate Period]))

(def ^:private pge-fixtures
  ["pge-e-1-2026-03-01"
   "pge-e-tou-c-2026-03-01"
   "pge-e-tou-d-2026-03-27"
   "pge-ev2-2026-03-27"])

(defn- enrollments
  "Every urpx:ProgramEnrollment map anywhere in `x`."
  [x]
  (filter #(and (map? %)
                (= "urpx:ProgramEnrollment" (:jsonld/type %)))
          (tree-seq coll? seq x)))

(defn- reload
  "The documents `json` loads as, less the @context emit attaches."
  [json]
  (map #(dissoc % :jsonld/context)
       (load/documents [(StringReader. json)])))

(deftest the-pge-fixtures-load-their-enrollments-as-documents
  (doseq [f pge-fixtures
          :let [{:urpx.load/keys [documents uncoercible]}
                (load/scan [(fixtures/fixture-resource f)])
                pes (filter #(= "urpx:ProgramEnrollment" (:jsonld/type %)) documents)]]
    (testing f
      (is (empty? uncoercible))
      (is (= #{"pge:program-care" "pge:program-fera" "pge:program-cca"
               "pge:program-da" "pge:program-tbs"}
             (set (map :jsonld/id pes))))
      (is (every? #(nil? (coerce/explain schema/ProgramEnrollment %)) pes)))))

(deftest a-top-level-enrollment-survives-emit-and-reload
  (doseq [f pge-fixtures
          pe (enrollments (load/documents [(fixtures/fixture-resource f)]))]
    (testing (str f " " (:jsonld/id pe))
      (is (= [pe] (reload (emit/write-program-enrollment pe)))))))

(defn- inline-enrollments
  "`raw` with each bare reference to a node in `by-id` replaced by that node."
  [raw by-id]
  (walk/postwalk (fn [x]
                   (if-let [node (and (map? x)
                                      (= [:jsonld/id] (keys x))
                                      (by-id (:jsonld/id x)))]
                     node
                     x))
                 raw))

(deftest an-inline-enrollment-is-typed-and-survives-emit-and-reload
  (testing "the fixture's enrollments stated inline at each reference: under
            urpx:referencesValue in a condition and in urpx:hasPossibleValues"
    (let [graph (core/load-graph (.getPath (fixtures/fixture-resource "pge-e-tou-c-2026-03-01")))
          by-id (into {}
                      (comp (filter #(= "urpx:ProgramEnrollment" (:jsonld/type %)))
                            (map #(assoc % :urpx/enrollmentStartDate "2025-06-01"))
                            (map (juxt :jsonld/id identity)))
                      graph)
          raw   (->> graph
                     (mapcat core/expand-document-entities)
                     (filter #(= "urpx:RatePlan" (:jsonld/type %)))
                     first)
          plan  (coerce/coerce-rate-plan (inline-enrollments raw by-id))
          pes   (enrollments plan)]
      (is (seq pes))
      (is (every? #(= (LocalDate/of 2025 6 1) (:urpx/enrollmentStartDate %)) pes))
      (is (nil? (coerce/explain schema/RatePlan plan)))
      (is (= [plan] (reload (emit/write-rate-plan plan)))))))

(deftest a-customer-profile-holds-its-enrollments
  (let [profile (coerce/coerce schema/CustomerProfile
                               {:jsonld/type "urpx:CustomerProfile"
                                :urpx/hasProgramEnrollment
                                {:jsonld/type "urpx:ProgramEnrollment"
                                 :urpx/programName "CARE"
                                 :urpx/minimumTenure "P12M"
                                 :urpx/enrollmentStatus {:jsonld/id "urpx:activeStatus"}}})]
    (is (= [Period] (map (comp type :urpx/minimumTenure) (:urpx/hasProgramEnrollment profile))))
    (is (nil? (coerce/explain schema/CustomerProfile profile)))
    (is (some? (coerce/explain schema/CustomerProfile
                               (assoc-in profile [:urpx/hasProgramEnrollment 0 :urpx/enrollmentStatus]
                                         {:jsonld/id "urpx:lowIncomeProgram"})))
        "enrollmentStatus is a closed vocabulary")))

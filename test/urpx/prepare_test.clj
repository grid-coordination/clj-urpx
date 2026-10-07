(ns urpx.prepare-test
  "Documents prepared once answer as the documents themselves answer."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [urpx.load :as load]
            [urpx.scenario-cost-model :as scm]
            [urpx.test-fixtures :as fixtures])
  (:import [java.time LocalDateTime ZoneId ZonedDateTime]))

(def ^:private inst (ZonedDateTime/of 2026 7 15 17 0 0 0 (ZoneId/of "America/Los_Angeles")))

(defn- fixture-files []
  (sort (filter #(str/ends-with? (str %) ".jsonld")
                (file-seq (io/file (io/resource "urpx-fixtures"))))))

(defn- written-contexts []
  (mapv #(get (json/read-str (slurp %)) "@context") (fixture-files)))

(defn- outcome
  "What calling `f` gives: its value, or the ex-data it throws."
  [f]
  (try [:value (f)] (catch clojure.lang.ExceptionInfo e [:refused (ex-data e)])))

(defn- refusal [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(defn- plan-doc
  ([id vid] (plan-doc id vid nil))
  ([id vid extra]
   {:jsonld/id id :jsonld/type "urpx:RatePlan" :urpx/timezoneIdentifier "America/Los_Angeles"
    :urpx/hasRatePlanVersion
    (merge {:jsonld/id vid :jsonld/type "urpx:RatePlanVersion"
            :urpx/forRatePlan {:jsonld/id id}
            :urpx/effectiveDateTime (LocalDateTime/of 2026 1 1 0 0)}
           extra)}))

(def ^:private conflicting
  "One version @id naming two distinct snapshots."
  [(plan-doc "x:p" "x:p-v")
   (plan-doc "x:p" "x:p-v" {:urpx/version "differs"})])

(deftest prepared-documents-answer-as-the-documents-do
  (let [docs     (load/documents (fixture-files))
        prepared (scm/prepare docs)
        ctx      (written-contexts)]
    (is (scm/prepared? prepared))
    (is (not (scm/prepared? docs)))
    (doseq [opts [{} {:at inst} {:compatibility scm/named-versions-add}
                  {:at inst :compatibility scm/named-versions-add}]]
      (testing (pr-str opts)
        (let [models (scm/enumerate docs opts)]
          (is (seq models))
          (is (= models (scm/enumerate prepared opts)))
          (is (= (map scm/identity-tuple models)
                 (map scm/identity-tuple (scm/enumerate prepared opts))))
          (is (= (scm/enumerate-pairs docs opts) (scm/enumerate-pairs prepared opts)))
          (is (= (scm/plan-versions docs opts) (scm/plan-versions prepared opts)))
          (doseq [bv (scm/plan-versions docs opts)]
            (is (= (scm/applicable-modifiers docs bv opts)
                   (scm/applicable-modifiers prepared bv opts))
                (:jsonld/id bv)))
          (doseq [m models
                  :let [n          (scm/node m scm/rate-plans-iri {:context ctx})
                        node-opts  (assoc opts :context ctx)
                        read       (scm/from-node docs n node-opts)
                        read-prep  (scm/from-node prepared n node-opts)]]
            (testing (pr-str (scm/identity-tuple m))
              (is (= read read-prep))
              (is (= (outcome #(scm/resolve-at read inst))
                     (outcome #(scm/resolve-at read-prep inst)))))))))))

(deftest prepare-refuses-what-the-calls-refuse
  (is (= :urpx.scenario-cost-model/conflicting-version-id
         (:reason (refusal #(scm/prepare conflicting)))))
  (is (= :urpx.scenario-cost-model/unknown-package-zones
         (:reason (refusal #(scm/prepare [(plan-doc "x:p" "x:p-v")] {:package-zones :prefer})))))
  (is (= {:reason :uncoerced-document :who "urpx.scenario-cost-model/prepare"}
         (select-keys (refusal #(scm/prepare [(fixtures/load-graph "sce-d-2026-03-20")]))
                      [:reason :who]))))

(deftest a-prepared-value-fixes-repeated-ids-and-package-zones
  (let [union    (scm/prepare conflicting {:repeated-ids scm/repeated-ids-union})
        default  (scm/prepare [(plan-doc "x:p" "x:p-v")])
        node     {:jsonld/id "x:m" :jsonld/type "urpx:ScenarioCostModel"
                  :urpx/consumesVersion {:jsonld/id "x:p-v"}}]
    (testing "the policy it was prepared with applies"
      (is (= (scm/enumerate conflicting {:repeated-ids scm/repeated-ids-union})
             (scm/enumerate union))))
    (testing "restating the prepared value is accepted"
      (is (= (scm/enumerate union) (scm/enumerate union {:repeated-ids scm/repeated-ids-union})))
      (is (= (scm/enumerate default)
             (scm/enumerate default {:repeated-ids scm/repeated-ids-refuse :package-zones :agree}))
          "including a default the preparation did not state"))
    (testing "another value refuses, at every entry point"
      (doseq [[nm f] [["enumerate" #(scm/enumerate union {:repeated-ids scm/repeated-ids-refuse})]
                      ["enumerate-pairs" #(scm/enumerate-pairs union {:repeated-ids nil})]
                      ["plan-versions" #(scm/plan-versions default {:package-zones :ignore})]
                      ["applicable-modifiers"
                       #(scm/applicable-modifiers default (:urpx/hasRatePlanVersion (first conflicting))
                                                  {:package-zones :fallback})]
                      ["from-node" #(scm/from-node union node {:repeated-ids scm/repeated-ids-refuse})]]]
        (is (= :urpx.scenario-cost-model/prepared-option-conflict (:reason (refusal f))) nm)))
    (testing "the refusal names each option, with both values"
      (is (= {:package-zones {:prepared :agree :called :ignore}
              :repeated-ids  {:prepared scm/repeated-ids-refuse :called scm/repeated-ids-union}}
             (:conflict (refusal #(scm/enumerate default {:package-zones :ignore
                                                          :repeated-ids scm/repeated-ids-union}))))))))

(deftest a-prepared-value-takes-compatibility-from-each-call
  (let [docs     (load/documents (fixture-files))
        prepared (scm/prepare docs)
        none     {:compatibility (constantly false)}]
    (is (some (comp seq second) (scm/enumerate-pairs prepared)))
    (is (= (scm/enumerate-pairs docs none) (scm/enumerate-pairs prepared none)))
    (is (every? (comp empty? second) (scm/enumerate-pairs prepared none)))))

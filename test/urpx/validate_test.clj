(ns urpx.validate-test
  (:require [clojure.test :refer [deftest is testing]]
            [urpx.core :as core]
            [urpx.test-fixtures :as fixtures]
            [urpx.validate :as validate]))

(deftest check-file-descends-wrappers
  (testing "the whole point of this ns: load-graph returns top-level entities
            only, so a wrapper-wrapped document must be descended or the inner
            RatePlan is never checked and the file reads as ok"
    (let [path (.getPath (fixtures/fixture-resource "pge-e-1-2026-03-01"))
          {:keys [checked skipped]} (validate/check-file path)
          types (set (map :type checked))]
      (is (contains? types "urpx:RatePlan"))
      (is (contains? types "urpx:RatePlanModifier"))
      (is (every? nil? (map :error checked)) "the vendored fixture is valid")
      (testing "unmodeled types are reported rather than silently dropped, so a
                file yielding nothing recognizable is visible as such"
        (is (some #{"urpx:ProgramEnrollment"} skipped))))))

(deftest check-file-reports-the-failing-path
  (testing "a missing required key surfaces as an error naming it"
    (let [plan (fixtures/load-rate-plan "pge-e-1-2026-03-01")
          broken (dissoc plan :urpx/name)
          s (:error (first (:checked (with-redefs
                                       [core/load-graph (constantly [broken])
                                        core/expand-document-entities vector]
                                       (validate/check-file "synthetic")))))]
      (is (some? s))
      (is (some #(= [:urpx/name] (:in %)) (:errors s))))))

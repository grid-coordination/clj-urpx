(ns urpx.dsl-test
  (:require [clojure.test :refer [deftest is testing]]
            [urpx.coerce :as coerce]
            [urpx.dsl :as dsl]
            [urpx.schema :as schema]))

;; ---------------------------------------------------------------------------
;; Season builder spike (URPX-4wa)
;; ---------------------------------------------------------------------------

(deftest season-minimal
  (testing "name-only — produces a valid Season with @type inferred"
    (let [s (dsl/season "Summer")]
      (is (= "urpx:Season" (:jsonld/type s)))
      (is (= "Summer"      (:urpx/seasonName s)))
      (is (nil? (coerce/explain schema/Season s))))))

(deftest season-with-int-months
  (testing "ints pass through the MonthNumber decoder unchanged"
    (let [s (dsl/season "Summer" :months [5 6 7 8 9 10])]
      (is (= [5 6 7 8 9 10] (:urpx/month s)))
      (is (every? int? (:urpx/month s))))))

(deftest season-auto-coerces-gMonth-strings
  (testing "URPX-4wa: '--MM' xsd:gMonth strings auto-coerce via the schema's
            :decode/urpx-jsonld decoder. Validates that running coerce after
            map assembly is enough — no per-property handling in the builder."
    (let [s (dsl/season "Summer" :months ["--05" "--06" "--07" "--08" "--09" "--10"])]
      (is (= [5 6 7 8 9 10] (:urpx/month s))
          "string month form decoded to ints"))))

(deftest season-mixed-input-types
  (testing "auto-coercion is per-leaf — int + string can be mixed"
    (let [s (dsl/season "Summer" :months [5 "--06" 7 "--08"])]
      (is (= [5 6 7 8] (:urpx/month s))))))

(deftest season-trailing-map-kwargs
  (testing "Clojure 1.11+ trailing-map-as-kwargs unification — both call
            shapes produce identical output"
    (let [a (dsl/season "Summer" :months [5 6] :yearly-recurrence true)
          b (dsl/season "Summer" {:months [5 6] :yearly-recurrence true})]
      (is (= a b)))))

(deftest season-handles-optional-id
  (let [s (dsl/season "Summer" :id "x:summer" :months [5 6 7 8 9 10])]
    (is (= "x:summer" (:jsonld/id s)))))

(deftest season-yearly-recurrence-false-is-not-nil
  (testing "explicit false isn't treated as 'absent' — distinguish nil from false
            in the kwargs handling"
    (let [s (dsl/season "Special" :yearly-recurrence false)]
      (is (false? (:urpx/yearlyRecurrence s))))))

(deftest season-equals-hand-built
  (testing "URPX-4wa acceptance: DSL output equals the hand-built coerced Season"
    (let [hand-built {:jsonld/type "urpx:Season"
                      :jsonld/id "x:summer"
                      :urpx/seasonName "Summer"
                      :urpx/month [5 6 7 8 9 10]
                      :urpx/yearlyRecurrence true}
          dsl-built  (dsl/season "Summer"
                                 :id "x:summer"
                                 :months [5 6 7 8 9 10]
                                 :yearly-recurrence true)]
      (is (= hand-built dsl-built)))))

(deftest season-validates-against-schema
  (testing "every legal call yields a Season that validates"
    (doseq [s [(dsl/season "Summer")
               (dsl/season "Summer" :months [6 7 8])
               (dsl/season "Winter" :months ["--12" "--01" "--02"])
               (dsl/season "Special" :yearly-recurrence false)]]
      (is (nil? (coerce/explain schema/Season s))))))

(deftest season-bad-month-throws-with-explanation
  (testing "out-of-range month surfaces as ex-info with the Malli explanation
            attached — validates the error UX for the broader DSL"
    (let [thrown (try (dsl/season "Summer" :months [13])
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
      (is (some? thrown))
      (is (re-find #"invalid urpx:Season" (.getMessage ^Throwable thrown)))
      (is (some? (:explanation (ex-data thrown))))
      (is (= [13] (get-in (ex-data thrown) [:input :urpx/month]))))))

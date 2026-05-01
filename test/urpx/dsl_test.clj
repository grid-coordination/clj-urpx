(ns urpx.dsl-test
  (:require [clojure.test :refer [deftest is testing]]
            [urpx.coerce :as coerce]
            [urpx.dsl :as dsl]
            [urpx.schema :as schema]))

;; ---------------------------------------------------------------------------
;; entity-type introspection (URPX-0cq)
;; ---------------------------------------------------------------------------

(deftest entity-type-extracts-jsonld-type-literal
  (testing "URPX-0cq: entity-type pulls the @type literal from the schema's
            :jsonld/type [:= ...] entry"
    (is (= "urpx:Season"       (dsl/entity-type schema/Season)))
    (is (= "urpx:MetricInput"  (dsl/entity-type schema/MetricInput)))
    (is (= "urpx:Ledger"       (dsl/entity-type schema/Ledger)))
    (is (= "urpx:Holiday"      (dsl/entity-type schema/Holiday)))
    (is (= "urpx:RatePlan"     (dsl/entity-type schema/RatePlan)))
    (is (= "urpx:URPXDocument" (dsl/entity-type schema/URPXDocument)))))

(deftest entity-type-returns-nil-on-non-entity-schema
  (testing "schemas without a :jsonld/type entry return nil (Decimal is a
            primitive :fn schema, not an entity)"
    (is (nil? (dsl/entity-type schema/Decimal)))))

;; ---------------------------------------------------------------------------
;; make-builder mechanics (URPX-0cq)
;; ---------------------------------------------------------------------------

(deftest make-builder-rejects-non-entity-schema
  (testing "make-builder throws ex-info if the schema can't supply a @type"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"must have a :jsonld/type"
                          (dsl/make-builder schema/Decimal)))))

(deftest make-builder-on-the-fly
  (testing "the generator works for any entity schema, not just the ones
            with named builders bound at ns load time"
    (let [build-tou-period (dsl/make-builder schema/TOUPeriod)
          period (build-tou-period :urpx/name "Summer Peak"
                                   :urpx/touPeriodNumber 1
                                   :urpx/hasTimeBracket [])]
      (is (= "urpx:TOUPeriod" (:jsonld/type period)))
      (is (= "Summer Peak" (:urpx/name period)))
      (is (= 1 (:urpx/touPeriodNumber period))))))

;; ---------------------------------------------------------------------------
;; Season builder via make-builder (URPX-0cq replaces the URPX-4wa spike)
;; ---------------------------------------------------------------------------

(deftest season-minimal
  (testing "name-only — produces a valid Season with @type set automatically"
    (let [s (dsl/season :urpx/seasonName "Summer")]
      (is (= "urpx:Season" (:jsonld/type s)))
      (is (= "Summer"      (:urpx/seasonName s)))
      (is (nil? (coerce/explain schema/Season s))))))

(deftest season-with-int-months
  (testing "ints pass through the MonthNumber decoder unchanged"
    (let [s (dsl/season :urpx/seasonName "Summer" :urpx/month [5 6 7 8 9 10])]
      (is (= [5 6 7 8 9 10] (:urpx/month s)))
      (is (every? int? (:urpx/month s))))))

(deftest season-auto-coerces-gMonth-strings
  (testing "URPX-4wa/0cq: '--MM' xsd:gMonth strings auto-coerce via the
            schema's :decode/urpx-jsonld decoder — no per-property handling
            needed in the builder"
    (let [s (dsl/season :urpx/seasonName "Summer"
                        :urpx/month ["--05" "--06" "--07" "--08" "--09" "--10"])]
      (is (= [5 6 7 8 9 10] (:urpx/month s))))))

(deftest season-mixed-input-types
  (testing "auto-coercion is per-leaf — int + string can be mixed"
    (let [s (dsl/season :urpx/seasonName "Summer"
                        :urpx/month [5 "--06" 7 "--08"])]
      (is (= [5 6 7 8] (:urpx/month s))))))

(deftest season-trailing-map-kwargs
  (testing "Clojure 1.12 trailing-map-as-kwargs unification — both call
            shapes produce identical output"
    (let [a (dsl/season :urpx/seasonName "Summer"
                        :urpx/month [5 6]
                        :urpx/yearlyRecurrence true)
          b (dsl/season {:urpx/seasonName "Summer"
                         :urpx/month [5 6]
                         :urpx/yearlyRecurrence true})]
      (is (= a b)))))

(deftest season-handles-optional-id
  (let [s (dsl/season :jsonld/id "x:summer"
                      :urpx/seasonName "Summer"
                      :urpx/month [5 6 7 8 9 10])]
    (is (= "x:summer" (:jsonld/id s)))))

(deftest season-yearly-recurrence-false-is-preserved
  (testing "explicit false isn't stripped — only nil values are dropped"
    (let [s (dsl/season :urpx/seasonName "Special"
                        :urpx/yearlyRecurrence false)]
      (is (false? (:urpx/yearlyRecurrence s))))))

(deftest season-nil-kwargs-are-stripped
  (testing "URPX-0cq: nil kwarg values drop out, so optional fields can be
            supplied conditionally without producing present-but-nil entries"
    (let [s (dsl/season :urpx/seasonName "Summer"
                        :urpx/month nil
                        :jsonld/id nil)]
      (is (not (contains? s :urpx/month)))
      (is (not (contains? s :jsonld/id)))
      (is (= "Summer" (:urpx/seasonName s))))))

(deftest season-equals-hand-built
  (testing "URPX-0cq acceptance: generator output equals a hand-built coerced
            Season — same shape, same values, same types"
    (let [hand-built {:jsonld/type "urpx:Season"
                      :jsonld/id "x:summer"
                      :urpx/seasonName "Summer"
                      :urpx/month [5 6 7 8 9 10]
                      :urpx/yearlyRecurrence true}
          dsl-built  (dsl/season :jsonld/id "x:summer"
                                 :urpx/seasonName "Summer"
                                 :urpx/month [5 6 7 8 9 10]
                                 :urpx/yearlyRecurrence true)]
      (is (= hand-built dsl-built)))))

(deftest season-validates-against-schema
  (testing "every legal call yields a Season that validates"
    (doseq [s [(dsl/season :urpx/seasonName "Summer")
               (dsl/season :urpx/seasonName "Summer" :urpx/month [6 7 8])
               (dsl/season :urpx/seasonName "Winter" :urpx/month ["--12" "--01" "--02"])
               (dsl/season :urpx/seasonName "Special" :urpx/yearlyRecurrence false)]]
      (is (nil? (coerce/explain schema/Season s))))))

(deftest season-bad-month-throws-with-explanation
  (testing "out-of-range month surfaces as ex-info with the Malli explanation
            attached — validates the error UX for the broader DSL"
    (let [thrown (try (dsl/season :urpx/seasonName "Summer" :urpx/month [13])
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
      (is (some? thrown))
      (is (re-find #"invalid urpx:Season" (.getMessage ^Throwable thrown)))
      (is (some? (:explanation (ex-data thrown))))
      (is (= [13] (get-in (ex-data thrown) [:input :urpx/month]))))))

;; ---------------------------------------------------------------------------
;; Other named builders (URPX-0cq) — demonstrate the generator works
;; across entity-shape variety
;; ---------------------------------------------------------------------------

(deftest metric-input-with-nested-spec
  (testing "URPX-0cq: nested-entity coercion — urpx:hasMetricSpecification
            takes a raw MetricSpecification map; coerce/coerce traverses it"
    (let [mi (dsl/metric-input
              :urpx/name "Energy consumed (kWh)"
              :urpx/metricInputType {:jsonld/id "urpx:profileMetricInput"}
              :urpx/hasMetricSpecification
              {:jsonld/type "urpx:MetricSpecification"
               :urpx/measurementKind {:jsonld/id "urpx:energy"}
               :urpx/unitOfMeasure   {:jsonld/id "urpx:kWh"}})]
      (is (= "urpx:MetricInput" (:jsonld/type mi)))
      (is (= "urpx:MetricSpecification"
             (get-in mi [:urpx/hasMetricSpecification :jsonld/type])))
      (is (nil? (coerce/explain schema/MetricInput mi))))))

(deftest ledger-with-refs
  (testing "URPX-0cq: Ref-bearing fields work — urpx:referencesMetricInput
            and urpx:ledgerType are passed as :jsonld/id maps"
    (let [l (dsl/ledger :urpx/name "Commodity"
                        :urpx/ledgerType {:jsonld/id "urpx:supplyLedger"}
                        :urpx/referencesMetricInput [{:jsonld/id "x:mi-energy"}])]
      (is (= "urpx:Ledger" (:jsonld/type l)))
      (is (nil? (coerce/explain schema/Ledger l))))))

(deftest holiday-with-date-coercion
  (testing "URPX-0cq: typed-leaf coercion — Date strings auto-coerce into
            LocalDate via the schema's :decode/urpx-jsonld decoder"
    (let [h (dsl/holiday :urpx/holidayName "Christmas"
                         :urpx/holidayDate "2026-12-25")]
      (is (= "urpx:Holiday" (:jsonld/type h)))
      (is (instance? java.time.LocalDate (:urpx/holidayDate h)))
      (is (= (java.time.LocalDate/of 2026 12 25) (:urpx/holidayDate h))))))

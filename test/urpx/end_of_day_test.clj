(ns urpx.end-of-day-test
  "xsd:time `24:00:00`, the end of a day, which names the same value as
  `00:00:00`."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [urpx.coerce :as coerce]
            [urpx.core :as core]
            [urpx.dsl :as dsl]
            [urpx.emit :as emit]
            [urpx.price :as price]
            [urpx.schema :as schema]
            [urpx.test-fixtures :as fixtures])
  (:import [java.time LocalTime OffsetTime ZoneId ZoneOffset ZonedDateTime]))

(def ^:private la (ZoneId/of "America/Los_Angeles"))

(defn- at [y m d h mi] (ZonedDateTime/of y m d h mi 0 0 la))

(deftest end-of-day-decodes-to-midnight
  (doseq [lex ["24:00:00" "24:00:00.0" "24:00:00.000" "24:00"]]
    (is (= LocalTime/MIDNIGHT (coerce/coerce schema/Time lex)) lex))
  (testing "with an offset, midnight at that offset"
    (is (= (OffsetTime/of LocalTime/MIDNIGHT ZoneOffset/UTC)
           (coerce/coerce schema/Time "24:00:00Z")))
    (is (= (OffsetTime/of LocalTime/MIDNIGHT (ZoneOffset/ofHours 5))
           (coerce/coerce schema/Time "24:00:00.00+05:00")))))

(deftest other-hour-24-forms-refuse
  (doseq [lex ["24:00:01" "24:00:00.5" "24:01:00" "24:01" "x24:00:00" "24:0"]]
    (let [e (try (coerce/coerce schema/Time lex) nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (= :urpx.schema/malformed-time (:reason (ex-data e))) lex)
      (is (= lex (:value (ex-data e))) lex))))

(deftest end-of-day-encodes-as-midnight
  (is (= "00:00:00" (coerce/encode schema/Time (coerce/coerce schema/Time "24:00:00")))))

(deftest a-dsl-time-bracket-accepts-end-of-day
  (let [tb (dsl/time-bracket :start-time "21:00:00"
                             :end-time "24:00:00"
                             :day-type {:jsonld/id "urpx:allDays"})]
    (is (= LocalTime/MIDNIGHT (:urpx/endTime tb)))
    (is (nil? (coerce/explain schema/TimeBracket tb)))))

(defn- bracket [id start end]
  {:jsonld/type "urpx:TimeBracket"
   :jsonld/id (str "test:bracket-" id)
   :urpx/appliesInSeason [{:jsonld/id "pge:e-tou-d-2026-03-27-season-summer"}
                          {:jsonld/id "pge:e-tou-d-2026-03-27-season-winter"}]
   :urpx/startTime start
   :urpx/endTime end
   :urpx/dayType {:jsonld/id "urpx:allDays"}})

(defn- period [n name brackets]
  {:jsonld/type "urpx:TOUPeriod"
   :jsonld/id (str "test:period-" n)
   :urpx/name name
   :urpx/touPeriodNumber n
   :urpx/hasTimeBracket brackets})

(def ^:private raw-plan
  "Raw E-TOU-D whose one TOU tier is Evening [21:00, 24:00:00) and Day
   [00:00, 21:00), round-tripped through JSON so every time is a lexical
   string."
  (-> (core/load-rate-plan (.getPath (fixtures/fixture-resource "pge-e-tou-d-2026-03-27")))
      (assoc-in [:urpx/hasRatePlanVersion :urpx/hasPlanElements :urpx/hasBillingModel
                 :urpx/hasTOUSchedule :urpx/hasTOUTier]
                [{:jsonld/type "urpx:TOUTier"
                  :urpx/touTierNumber 1
                  :urpx/hasTOUPeriod [(period 1 "Evening" [(bracket "evening" "21:00:00" "24:00:00")])
                                      (period 2 "Day" [(bracket "day" "00:00:00" "21:00:00")])]}])))

(defn- period-at [plan zdt]
  (:urpx.resolved/tou-period-number (price/resolve-prices plan zdt)))

(deftest a-bracket-ending-at-end-of-day-prices-to-midnight
  (let [plan (coerce/coerce-rate-plan raw-plan)]
    (is (nil? (coerce/explain schema/RatePlan plan)))
    (is (= 1 (period-at plan (at 2026 7 13 21 0))))
    (is (= 1 (period-at plan (at 2026 7 13 23 59))))
    (is (= 2 (period-at plan (at 2026 7 14 0 0))))
    (is (= 2 (period-at plan (at 2026 7 14 20 59))))
    (testing "the emitted end is 00:00:00"
      (let [emitted (json/read-str (emit/write-rate-plan plan))
            ends    (for [schedule (get-in emitted ["urpx:hasRatePlanVersion" "urpx:hasPlanElements"
                                                    "urpx:hasBillingModel" "urpx:hasTOUSchedule"])
                          tier     (get schedule "urpx:hasTOUTier")
                          period   (get tier "urpx:hasTOUPeriod")
                          bracket  (get period "urpx:hasTimeBracket")]
                      (get bracket "urpx:endTime"))]
        (is (= #{"00:00:00" "21:00:00"} (set ends)))))))

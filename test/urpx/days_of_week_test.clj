(ns urpx.days-of-week-test
  "urpx:daysOfWeek on a urpx:TimeBracket whose urpx:dayType is
  urpx:customDays (urpx-org/urpx#71).

  The schedule is Seattle City Light's RTC/RTS shape grafted onto PG&E
  E-TOU-D, whose embedded calendar derives Labor Day: Peak 5-9pm Monday
  through Saturday except holidays; Mid-Peak the rest of 6am-midnight Monday
  through Saturday except holidays, and 6am-midnight on Sundays and holidays;
  Off-Peak midnight-6am every day. \"Sundays and holidays\" is two brackets,
  because a bracket matches only when its day test and its holiday test both
  pass."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [urpx.coerce :as coerce]
            [urpx.core :as core]
            [urpx.emit :as emit]
            [urpx.price :as price]
            [urpx.schedule :as schedule]
            [urpx.schema :as schema]
            [urpx.test-fixtures :as fixtures])
  (:import [java.time ZoneId ZonedDateTime]))

(def ^:private la (ZoneId/of "America/Los_Angeles"))

(defn- at [y m d h] (ZonedDateTime/of y m d h 0 0 0 la))

(def ^:private mon-sat
  (mapv #(hash-map :jsonld/id (str "urpx:" %))
        ["monday" "tuesday" "wednesday" "thursday" "friday" "saturday"]))

(defn- bracket [id start end day-type & {:as extra}]
  (merge {:jsonld/type "urpx:TimeBracket"
          :jsonld/id (str "test:bracket-" id)
          :urpx/appliesInSeason [{:jsonld/id "pge:e-tou-d-2026-03-27-season-summer"}
                                 {:jsonld/id "pge:e-tou-d-2026-03-27-season-winter"}]
          :urpx/startTime start
          :urpx/endTime end
          :urpx/dayType {:jsonld/id (str "urpx:" day-type)}}
         extra))

(defn- period [n name brackets]
  {:jsonld/type "urpx:TOUPeriod" :jsonld/id (str "test:period-" n)
   :urpx/name name :urpx/touPeriodNumber n :urpx/hasTimeBracket brackets})

(def ^:private scl-periods
  [(period 1 "Peak"
           [(bracket "peak" "17:00:00" "21:00:00" "customDays"
                     :urpx/daysOfWeek mon-sat :urpx/includeHolidays false)])
   (period 2 "Mid-Peak"
           [(bracket "mid-day" "06:00:00" "17:00:00" "customDays"
                     :urpx/daysOfWeek mon-sat :urpx/includeHolidays false)
            (bracket "mid-late" "21:00:00" "00:00:00" "customDays"
                     :urpx/daysOfWeek mon-sat :urpx/includeHolidays false)
            (bracket "mid-sunday" "06:00:00" "00:00:00" "customDays"
                     :urpx/daysOfWeek [{:jsonld/id "urpx:sunday"}])
            (bracket "mid-holiday" "06:00:00" "00:00:00" "allDays"
                     :urpx/includeNonHolidays false)])
   (period 3 "Off-Peak"
           [(bracket "off" "00:00:00" "06:00:00" "allDays")])])

(defn- raw-plan
  "Raw (uncoerced) E-TOU-D with its TOU tiers replaced by one tier of `periods`."
  [periods]
  (assoc-in (core/load-rate-plan (.getPath (fixtures/fixture-resource "pge-e-tou-d-2026-03-27")))
            [:urpx/hasRatePlanVersion :urpx/hasPlanElements :urpx/hasBillingModel
             :urpx/hasTOUSchedule :urpx/hasTOUTier]
            [{:jsonld/type "urpx:TOUTier" :urpx/touTierNumber 1 :urpx/hasTOUPeriod periods}]))

(def ^:private plan (coerce/coerce-rate-plan (raw-plan scl-periods)))

(defn- period-at [plan zdt]
  (:urpx.resolved/tou-period-number (price/resolve-prices plan zdt)))

(deftest the-plan-is-valid
  (is (nil? (coerce/explain schema/RatePlan plan))))

(deftest peak-runs-monday-through-saturday-and-not-sunday
  (testing "Sun 2026-07-12"
    (is (= 2 (period-at plan (at 2026 7 12 18))))
    (is (= 2 (period-at plan (at 2026 7 12 7))))
    (is (= 3 (period-at plan (at 2026 7 12 5)))))
  (testing "Mon 2026-07-13"
    (is (= 1 (period-at plan (at 2026 7 13 18))))
    (is (= 2 (period-at plan (at 2026 7 13 7))))
    (is (= 2 (period-at plan (at 2026 7 13 22)))))
  (testing "Sat 2026-07-11"
    (is (= 1 (period-at plan (at 2026 7 11 18))))))

(deftest a-holiday-monday-is-mid-peak
  (testing "Mon 2026-09-07, Labor Day in the embedded calendar"
    (is (= 2 (period-at plan (at 2026 9 7 18))))
    (is (= 2 (period-at plan (at 2026 9 7 7))))
    (is (= 3 (period-at plan (at 2026 9 7 5)))))
  (testing "the same Monday when the caller says it is no holiday"
    (is (= 1 (:urpx.resolved/tou-period-number
              (price/resolve-prices plan (at 2026 9 7 18) {:holiday? (constantly false)})))))
  (testing "the Sunday bracket alone leaves a holiday Monday uncovered"
    (let [sunday-only (coerce/coerce-rate-plan
                       (raw-plan (update-in scl-periods [1 :urpx/hasTimeBracket] #(vec (take 3 %)))))]
      (is (= :urpx.price/no-tou-period
             (:reason (try (price/resolve-prices sunday-only (at 2026 9 7 18)) nil
                           (catch clojure.lang.ExceptionInfo e (ex-data e))))))
      (is (= 2 (period-at sunday-only (at 2026 7 12 18)))))))

(deftest a-bare-single-day-decodes-to-a-vector
  (let [bare  (assoc-in scl-periods [1 :urpx/hasTimeBracket 2 :urpx/daysOfWeek]
                        {:jsonld/id "urpx:sunday"})
        raw   (raw-plan bare)
        typed (coerce/coerce-rate-plan raw)]
    (is (= [{:jsonld/id "urpx:sunday"}]
           (get-in typed [:urpx/hasRatePlanVersion :urpx/hasPlanElements :urpx/hasBillingModel
                          :urpx/hasTOUSchedule 0 :urpx/hasTOUTier 0 :urpx/hasTOUPeriod 1
                          :urpx/hasTimeBracket 2 :urpx/daysOfWeek])))
    (is (= 2 (period-at typed (at 2026 7 12 18))))
    (testing "and reads the same uncoerced"
      (let [uncoerced (assoc-in plan [:urpx/hasRatePlanVersion :urpx/hasPlanElements
                                      :urpx/hasBillingModel :urpx/hasTOUSchedule 0
                                      :urpx/hasTOUTier 0 :urpx/hasTOUPeriod 1
                                      :urpx/hasTimeBracket 2 :urpx/daysOfWeek]
                                {:jsonld/id "urpx:sunday"})]
        (is (= 2 (period-at uncoerced (at 2026 7 12 18))))))))

(deftest days-outside-the-enumeration-do-not-validate
  (let [bad (coerce/coerce-rate-plan
             (raw-plan (assoc-in scl-periods [1 :urpx/hasTimeBracket 2 :urpx/daysOfWeek]
                                 [{:jsonld/id "urpx:funday"}])))]
    (is (some? (coerce/explain schema/RatePlan bad)))
    (is (nil? (coerce/explain schema/TimeBracket
                              (get-in plan [:urpx/hasRatePlanVersion :urpx/hasPlanElements
                                            :urpx/hasBillingModel :urpx/hasTOUSchedule 0
                                            :urpx/hasTOUTier 0 :urpx/hasTOUPeriod 0
                                            :urpx/hasTimeBracket 0]))))))

(deftest custom-days-without-days-of-week-refuses
  (let [unstated (coerce/coerce-rate-plan
                  (raw-plan (update-in scl-periods [1 :urpx/hasTimeBracket 2]
                                       dissoc :urpx/daysOfWeek)))
        refusal  (fn [zdt]
                   (try (price/resolve-prices unstated zdt) nil
                        (catch clojure.lang.ExceptionInfo e (ex-data e))))]
    (testing "at an instant the bracket covers, and at one it does not"
      (doseq [zdt [(at 2026 7 12 18) (at 2026 7 13 3)]]
        (is (= :urpx.price/custom-days-unspecified (:reason (refusal zdt))))
        (is (= [{:tou-period-id "test:period-2" :time-bracket-id "test:bracket-mid-sunday"}]
               (:brackets (refusal zdt))))))
    (testing "an empty list states no days either"
      (let [empty-list (coerce/coerce-rate-plan
                        (raw-plan (assoc-in scl-periods [1 :urpx/hasTimeBracket 2 :urpx/daysOfWeek] [])))]
        (is (= :urpx.price/custom-days-unspecified
               (:reason (try (price/resolve-prices empty-list (at 2026 7 13 18)) nil
                             (catch clojure.lang.ExceptionInfo e (ex-data e))))))))))

(deftest segments-split-at-the-day-boundary
  (let [segs (schedule/price-segments plan [] (at 2026 7 11 17) (at 2026 7 13 22))]
    (is (= [[(at 2026 7 11 17) (at 2026 7 11 21) 1]
            [(at 2026 7 11 21) (at 2026 7 12 0) 2]
            [(at 2026 7 12 0) (at 2026 7 12 6) 3]
            [(at 2026 7 12 6) (at 2026 7 13 0) 2]
            [(at 2026 7 13 0) (at 2026 7 13 6) 3]
            [(at 2026 7 13 6) (at 2026 7 13 17) 2]
            [(at 2026 7 13 17) (at 2026 7 13 21) 1]
            [(at 2026 7 13 21) (at 2026 7 13 22) 2]]
           (mapv (juxt :tick/beginning :tick/end
                       (comp :urpx.resolved/tou-period-number :urpx.interval/resolved))
                 segs)))))

(deftest days-of-week-round-trip-through-emit
  (let [doc (json/read-str (emit/write-rate-plan plan))
        brackets (-> doc
                     (get-in ["urpx:hasRatePlanVersion" "urpx:hasPlanElements"
                              "urpx:hasBillingModel" "urpx:hasTOUSchedule"])
                     (#(if (sequential? %) (first %) %))
                     (get-in ["urpx:hasTOUTier" 0 "urpx:hasTOUPeriod" 1 "urpx:hasTimeBracket"]))]
    (is (= [{"@id" "urpx:sunday"}] (get-in brackets [2 "urpx:daysOfWeek"])))
    (is (= (dissoc plan :jsonld/context)
           (-> (emit/write-rate-plan plan {:context nil})
               java.io.StringReader.
               core/parse-rate-plan
               coerce/coerce-rate-plan)))))

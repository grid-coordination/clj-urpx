(ns urpx.schedule-test
  (:require [clojure.test :refer [deftest is testing]]
            [urpx.price :as price]
            [urpx.schedule :as schedule]
            [urpx.test-fixtures :as fixtures])
  (:import [java.math BigDecimal]
           [java.time Duration Instant LocalDate ZoneId ZonedDateTime]))

(def la-zone (ZoneId/of "America/Los_Angeles"))

(defn- start-of-day
  ^Instant [year month day]
  (-> (LocalDate/of year month day)
      (.atStartOfDay la-zone)
      .toInstant))

(deftest hourly-schedule-summer-day-cpau-tou
  (testing "a 24h CPAU TOU summer day collapses into 4 contiguous TOU intervals"
    (let [plan  (fixtures/load-rate-plan "cpau-e-1-tou-2026-01-01")
          start (start-of-day 2026 7 15)
          end   (start-of-day 2026 7 16)
          sch   (schedule/price-schedule plan start end la-zone)]
      (testing "intervals cover the full 24 hours with no gaps"
        (is (= start (:tick/beginning (first sch))))
        (is (= end (:tick/end (last sch))))
        (loop [[a b & rest] sch]
          (when b
            (is (= (:tick/end a) (:tick/beginning b)))
            (recur (cons b rest)))))
      (testing "Summer day boundary breakdown:
         00:00-09:00 Off-Peak  (wraps from previous day's 21:00)
         09:00-15:00 Super Off-Peak
         15:00-16:00 Off-Peak
         16:00-21:00 Peak
         21:00-24:00 Off-Peak"
        (is (= 5 (count sch)))
        (let [period-names (mapv #(get-in % [:urpx.interval/resolved
                                             :urpx.resolved/tou-period-name])
                                 sch)]
          (is (= ["Summer Off-Peak"
                  "Summer Super Off-Peak"
                  "Summer Off-Peak"
                  "Summer Peak"
                  "Summer Off-Peak"]
                 period-names))))
      (testing "Each merged interval's begin/end matches the bracket boundary"
        (let [zdt-of (fn [^Instant i] (.atZone i la-zone))
              hours-of (fn [^Instant i] (.getHour (zdt-of i)))]
          (is (= [0 9 15 16 21]
                 (mapv #(hours-of (:tick/beginning %)) sch)))
          (is (= [9 15 16 21 0]
                 (mapv #(hours-of (:tick/end %)) sch))))))))

(deftest hourly-schedule-cpau-e1-no-tou
  (testing "a non-TOU plan collapses into a single 24h interval"
    (let [plan  (fixtures/load-rate-plan "cpau-e-1-2025-07-01")
          start (start-of-day 2025 8 1)
          end   (start-of-day 2025 8 2)
          sch   (schedule/price-schedule plan start end la-zone)]
      (is (= 1 (count sch)) "no period changes -> one merged span")
      (let [iv (first sch)]
        (is (= start (:tick/beginning iv)))
        (is (= end   (:tick/end iv)))
        (testing "marginal rate matches independent resolve-prices"
          (let [zdt (ZonedDateTime/of 2025 8 1 12 0 0 0 la-zone)
                expected (price/marginal-unit-rate (price/resolve-prices plan zdt))]
            (is (= expected
                   (price/marginal-unit-rate (:urpx.interval/resolved iv))))))))))

(deftest schedule-days-convenience
  (let [plan (fixtures/load-rate-plan "cpau-e-1-tou-2026-01-01")
        start (start-of-day 2026 7 15)
        sch1  (schedule/price-schedule-days plan start 1 la-zone)
        sch2  (schedule/price-schedule plan start (.plus start (Duration/ofDays 1)) la-zone)]
    (is (= sch1 sch2))))

(deftest no-empty-intervals-or-overlapping
  (let [plan (fixtures/load-rate-plan "cpau-e-1-tou-2026-01-01")
        sch  (schedule/price-schedule-days plan (start-of-day 2026 7 15) 1 la-zone)]
    (testing "every interval has a positive duration"
      (doseq [{b :tick/beginning e :tick/end} sch]
        (is (.isBefore ^Instant b ^Instant e))))
    (testing "every interval surfaces a BigDecimal marginal rate"
      (doseq [iv sch]
        (let [rate (price/marginal-unit-rate (:urpx.interval/resolved iv))]
          (is (instance? BigDecimal rate))
          (is (pos? rate)))))))

;; ---------------------------------------------------------------------------
;; URPX-8lf — sub-hour step configuration
;; ---------------------------------------------------------------------------

(deftest sub-hour-step-still-merges-equivalent-steps
  (testing "a 15-minute step on CPAU TOU still collapses to the same 5 TOU intervals"
    (let [plan  (fixtures/load-rate-plan "cpau-e-1-tou-2026-01-01")
          start (start-of-day 2026 7 15)
          end   (start-of-day 2026 7 16)
          opts  {:step (Duration/ofMinutes 15)}
          hourly-sch  (schedule/price-schedule plan start end la-zone)
          quarter-sch (schedule/price-schedule plan start end la-zone opts)]
      (is (= (count hourly-sch) (count quarter-sch))
          "both step sizes resolve to the same number of merged intervals
           because all TOU bracket transitions are hour-aligned")
      (is (= (mapv :tick/beginning hourly-sch) (mapv :tick/beginning quarter-sch)))
      (is (= (mapv :tick/end hourly-sch) (mapv :tick/end quarter-sch))))))

(deftest schedule-passes-holiday-predicate-through
  (testing ":holiday? in opts reaches resolve-prices via the schedule"
    ;; Switch the first Summer Peak bracket to a holiday-only override:
    ;; weekdayDays + includeHolidays:true + includeNonHolidays:false. With no
    ;; :holiday? predicate it never matches; with the date marked as a
    ;; holiday, it does.
    (let [plan-base (fixtures/load-rate-plan "cpau-e-1-tou-2026-01-01")
          plan      (update-in plan-base
                               [:urpx/hasRatePlanVersion
                                :urpx/hasPlanElements
                                :urpx/hasBillingModel
                                :urpx/hasTOUSchedule
                                :urpx/hasTOUTier 0
                                :urpx/hasTOUPeriod 0
                                :urpx/hasTimeBracket 0]
                               #(merge % {:urpx/dayType            {:jsonld/id "urpx:weekdayDays"}
                                          :urpx/includeHolidays    true
                                          :urpx/includeNonHolidays false}))
          start (start-of-day 2026 7 15)
          end   (start-of-day 2026 7 16)
          sch-no-holiday (schedule/price-schedule plan start end la-zone)
          sch-holiday    (schedule/price-schedule plan start end la-zone
                                                  {:holiday? #{(LocalDate/of 2026 7 15)}})]
      (testing "without :holiday?, no interval reports Summer Peak"
        (is (not-any? #(= "Summer Peak"
                          (get-in % [:urpx.interval/resolved
                                     :urpx.resolved/tou-period-name]))
                      sch-no-holiday)))
      (testing "with :holiday? matching the date, an interval reports Summer Peak"
        (is (some #(= "Summer Peak"
                      (get-in % [:urpx.interval/resolved
                                 :urpx.resolved/tou-period-name]))
                  sch-holiday))))))

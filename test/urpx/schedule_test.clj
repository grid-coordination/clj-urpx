(ns urpx.schedule-test
  (:require [clojure.test :refer [deftest is testing]]
            [urpx.core :as core]
            [urpx.coerce :as coerce]
            [urpx.price :as price]
            [urpx.schedule :as schedule])
  (:import [java.math BigDecimal]
           [java.time Duration Instant LocalDate ZoneId ZonedDateTime]))

(def cpau-tou-path
  "../urpx/test-cases/cpau-e-1-tou-2026-01-01/data/urpx-rate-plan_cpau_e-1-tou_2026-01-01.jsonld")

(def cpau-e1-path
  "../urpx/test-cases/cpau-e-1-2025-07-01/data/urpx-rate-plan_cpau_e-1_2025-07-01.jsonld")

(def la-zone (ZoneId/of "America/Los_Angeles"))

(defn- start-of-day
  ^Instant [year month day]
  (-> (LocalDate/of year month day)
      (.atStartOfDay la-zone)
      .toInstant))

(deftest hourly-schedule-summer-day-cpau-tou
  (testing "a 24h CPAU TOU summer day collapses into 4 contiguous TOU intervals"
    (let [plan  (coerce/coerce-rate-plan (core/load-rate-plan cpau-tou-path))
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
    (let [plan  (coerce/coerce-rate-plan (core/load-rate-plan cpau-e1-path))
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
  (let [plan (coerce/coerce-rate-plan (core/load-rate-plan cpau-tou-path))
        start (start-of-day 2026 7 15)
        sch1  (schedule/price-schedule-days plan start 1 la-zone)
        sch2  (schedule/price-schedule plan start (.plus start (Duration/ofDays 1)) la-zone)]
    (is (= sch1 sch2))))

(deftest no-empty-intervals-or-overlapping
  (let [plan (coerce/coerce-rate-plan (core/load-rate-plan cpau-tou-path))
        sch  (schedule/price-schedule-days plan (start-of-day 2026 7 15) 1 la-zone)]
    (testing "every interval has a positive duration"
      (doseq [{b :tick/beginning e :tick/end} sch]
        (is (.isBefore ^Instant b ^Instant e))))
    (testing "every interval surfaces a BigDecimal marginal rate"
      (doseq [iv sch]
        (let [rate (price/marginal-unit-rate (:urpx.interval/resolved iv))]
          (is (instance? BigDecimal rate))
          (is (pos? rate)))))))

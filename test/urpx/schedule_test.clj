(ns urpx.schedule-test
  (:require [clojure.test :refer [deftest is testing]]
            [urpx.price :as price]
            [urpx.schedule :as schedule]
            [urpx.test-fixtures :as fixtures])
  (:import [java.math BigDecimal]
           [java.time Duration Instant LocalDate ZoneId ZonedDateTime]))

(def la-zone (ZoneId/of "America/Los_Angeles"))

(defn- start-of-day-zdt
  ^ZonedDateTime [year month day]
  (.atStartOfDay (LocalDate/of year month day) la-zone))

(defn- start-of-day
  ^Instant [year month day]
  (.toInstant (start-of-day-zdt year month day)))

(deftest hourly-schedule-summer-day-pge-e-tou-c
  (testing "a 24h PG&E E-TOU-C summer day collapses into 3 contiguous TOU intervals
            (Off-Peak / Peak / Off-Peak — no Super Off-Peak in this schedule)"
    (let [plan      (fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")
          start-zdt (start-of-day-zdt 2026 7 15)
          end-zdt   (start-of-day-zdt 2026 7 16)
          sch       (schedule/price-schedule plan
                                             (.toInstant start-zdt)
                                             (.toInstant end-zdt)
                                             la-zone)]
      (testing "intervals cover the full 24 hours with no gaps. URPX-906:
                :tick/beginning and :tick/end are ZonedDateTimes."
        (is (= start-zdt (:tick/beginning (first sch))))
        (is (= end-zdt   (:tick/end (last sch))))
        (loop [[a b & rest] sch]
          (when b
            (is (= (:tick/end a) (:tick/beginning b)))
            (recur (cons b rest)))))
      (testing "Summer day boundary breakdown (off / peak / off)"
        (is (= 3 (count sch)))
        (let [period-numbers (mapv #(get-in % [:urpx.interval/resolved
                                               :urpx.resolved/tou-period-number])
                                   sch)]
          (is (= [3 1 3] period-numbers)
              "off-peak (3), peak (1), off-peak (3)")))
      (testing "Each merged interval's begin/end matches the bracket boundary"
        (let [hours-of (fn [^ZonedDateTime z] (.getHour z))]
          (is (= [0 16 21] (mapv #(hours-of (:tick/beginning %)) sch)))
          (is (= [16 21 0] (mapv #(hours-of (:tick/end %)) sch))))))))

(deftest hourly-schedule-pge-e-1-no-tou
  (testing "a non-TOU plan collapses into a single 24h interval"
    (let [plan      (fixtures/load-rate-plan "pge-e-1-2026-03-01")
          start-zdt (start-of-day-zdt 2026 8 1)
          end-zdt   (start-of-day-zdt 2026 8 2)
          sch       (schedule/price-schedule plan
                                             (.toInstant start-zdt)
                                             (.toInstant end-zdt)
                                             la-zone)]
      (is (= 1 (count sch)) "no period changes -> one merged span")
      (let [iv (first sch)]
        (is (= start-zdt (:tick/beginning iv)))
        (is (= end-zdt   (:tick/end iv)))
        (testing "marginal rate matches independent resolve-prices"
          (let [zdt (ZonedDateTime/of 2026 8 1 12 0 0 0 la-zone)
                expected (price/marginal-unit-rate (price/resolve-prices plan zdt))]
            (is (= expected
                   (price/marginal-unit-rate (:urpx.interval/resolved iv))))))))))

(deftest schedule-days-convenience
  (let [plan (fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")
        start (start-of-day 2026 7 15)
        sch1  (schedule/price-schedule-days plan start 1 la-zone)
        sch2  (schedule/price-schedule plan start (.plus start (Duration/ofDays 1)) la-zone)]
    (is (= sch1 sch2))))

(deftest no-empty-intervals-or-overlapping
  (let [plan (fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")
        sch  (schedule/price-schedule-days plan (start-of-day 2026 7 15) 1 la-zone)]
    (testing "every interval has a positive duration"
      (doseq [{b :tick/beginning e :tick/end} sch]
        (is (.isBefore ^ZonedDateTime b ^ZonedDateTime e))))
    (testing "every interval surfaces a BigDecimal marginal rate"
      ;; PG&E E-TOU-C bundles the California Climate Credit, which is a
      ;; bill-period credit; depending on how the fixture models it the
      ;; marginal sum can be negative. Don't assert sign — just BigDecimal-ness.
      (doseq [iv sch]
        (let [rate (price/marginal-unit-rate (:urpx.interval/resolved iv))]
          (is (instance? BigDecimal rate)))))))

(deftest sub-hour-step-still-merges-equivalent-steps
  (testing "a 15-minute step on PG&E E-TOU-C still collapses to the same TOU intervals
            because all bracket transitions are hour-aligned"
    (let [plan  (fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")
          start (start-of-day 2026 7 15)
          end   (start-of-day 2026 7 16)
          opts  {:step (Duration/ofMinutes 15)}
          hourly-sch  (schedule/price-schedule plan start end la-zone)
          quarter-sch (schedule/price-schedule plan start end la-zone opts)]
      (is (= (count hourly-sch) (count quarter-sch)))
      (is (= (mapv :tick/beginning hourly-sch) (mapv :tick/beginning quarter-sch)))
      (is (= (mapv :tick/end hourly-sch) (mapv :tick/end quarter-sch))))))

(deftest schedule-passes-holiday-predicate-through
  (testing ":holiday? in opts reaches resolve-prices via the schedule.
            E-TOU-D Summer Peak (period 1) is weekdayDays + includeHolidays:false,
            so a weekday declared as a holiday must NOT see Summer Peak."
    (let [plan      (fixtures/load-rate-plan "pge-e-tou-d-2026-03-27")
          ;; Wed July 15 2026 is a normal weekday — peak window applies.
          start (start-of-day 2026 7 15)
          end   (start-of-day 2026 7 16)
          sch-no-holiday (schedule/price-schedule plan start end la-zone
                                                  {:holiday? (constantly false)})
          sch-holiday    (schedule/price-schedule plan start end la-zone
                                                  {:holiday? #{(LocalDate/of 2026 7 15)}})]
      (testing "without holiday flag, Summer Peak (period 1) appears"
        (is (some #(= 1 (get-in % [:urpx.interval/resolved
                                   :urpx.resolved/tou-period-number]))
                  sch-no-holiday)))
      (testing "with the date marked as a holiday, Summer Peak is suppressed"
        (is (not-any? #(= 1 (get-in % [:urpx.interval/resolved
                                       :urpx.resolved/tou-period-number]))
                      sch-holiday))))))

(deftest schedule-defaults-zone-from-plan-timezone-identifier
  (testing "When the rate plan declares urpx:timezoneIdentifier, callers can omit
            the zone arg and the schedule walks in that zone."
    (let [plan      (fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")
          start     (start-of-day 2026 7 15)
          end       (start-of-day 2026 7 16)
          from-plan (schedule/price-schedule plan start end)
          explicit  (schedule/price-schedule plan start end la-zone)]
      (is (= explicit from-plan)))))

(deftest schedule-throws-when-no-zone-and-plan-has-none
  (testing "Plan without urpx:timezoneIdentifier and no explicit zone raises
            a clear error rather than silently picking system default."
    (let [plan-base (fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")
          plan      (dissoc plan-base :urpx/timezoneIdentifier)
          start     (start-of-day 2026 7 15)
          end       (start-of-day 2026 7 16)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Cannot determine timezone"
                            (schedule/price-schedule plan start end))))))

(deftest schedule-days-also-defaults-zone-from-plan
  (testing "price-schedule-days mirrors price-schedule's zone defaulting"
    (let [plan  (fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")
          start (start-of-day 2026 7 15)]
      (is (= (schedule/price-schedule-days plan start 1 la-zone)
             (schedule/price-schedule-days plan start 1))))))

;; ---------------------------------------------------------------------------
;; price-schedule-with-modifiers (URPX-21n)
;; ---------------------------------------------------------------------------
;; Mirror of price-schedule that calls resolve-prices-with-modifiers per step.
;; Verifies (a) modifier ledgers are folded into each interval's resolved
;; output, (b) the merge-adjacent + step + zone-resolution semantics match
;; price-schedule, and (c) modifiers whose applicableToRatePlan doesn't
;; include the base plan are silently skipped (delegated behavior).

(deftest schedule-with-modifiers-folds-modifier-ledgers-per-step
  (testing "URPX-21n: every interval includes the vintage-PCIA modifier ledger
            appended to the base plan's"
    (let [plan     (fixtures/load-rate-plan "pge-e-1-2026-03-01")
          modifier (fixtures/load-modifier  "pge-e-1-2026-03-01")
          start    (start-of-day 2026 7 15)
          end      (start-of-day 2026 7 16)
          sch      (schedule/price-schedule-with-modifiers
                    plan [modifier] start end la-zone)]
      (testing "every interval has the Vintaged-PCIA ledger present"
        (is (every? (fn [iv]
                      (some #(= "Vintaged Power Charge Indifference Adjustment"
                                (:urpx.resolved/ledger-name %))
                            (get-in iv [:urpx.interval/resolved
                                        :urpx.resolved/ledgers])))
                    sch)))
      (testing "ledger count per interval matches resolve-prices-with-modifiers
                at the corresponding instant. URPX-906: :tick/beginning is
                already a ZonedDateTime, so resolve-prices-with-modifiers
                consumes it directly."
        (let [zdt (:tick/beginning (first sch))
              direct (price/resolve-prices-with-modifiers plan [modifier] zdt)]
          (is (= (count (:urpx.resolved/ledgers direct))
                 (count (get-in (first sch) [:urpx.interval/resolved
                                             :urpx.resolved/ledgers])))))))))

(deftest schedule-with-modifiers-merges-adjacent-intervals
  (testing "URPX-21n: same merge semantics as price-schedule. A 24h Summer day
            on PG&E E-1 (no TOU) collapses to a single interval whether or
            not the modifier is folded in (modifier is constant across the
            day, so adjacency holds)."
    (let [plan     (fixtures/load-rate-plan "pge-e-1-2026-03-01")
          modifier (fixtures/load-modifier  "pge-e-1-2026-03-01")
          start    (start-of-day 2026 7 15)
          end      (start-of-day 2026 7 16)
          sch-base (schedule/price-schedule plan start end la-zone)
          sch-mod  (schedule/price-schedule-with-modifiers
                    plan [modifier] start end la-zone)]
      (is (= (count sch-base) (count sch-mod))
          "modifier addition doesn't change the day's TOU/merge structure"))))

(deftest schedule-with-modifiers-defaults-zone-from-plan
  (testing "URPX-21n: zone defaulting via plan's urpx:timezoneIdentifier
            mirrors price-schedule"
    (let [plan     (fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")
          modifier (fixtures/load-modifier  "pge-e-tou-c-2026-03-01")
          start    (start-of-day 2026 7 15)
          end      (start-of-day 2026 7 16)]
      (is (= (schedule/price-schedule-with-modifiers plan [modifier] start end la-zone)
             (schedule/price-schedule-with-modifiers plan [modifier] start end))))))

(deftest schedule-with-modifiers-skips-non-applicable
  (testing "URPX-21n: modifiers whose applicableToRatePlan doesn't include
            the plan @id are silently skipped per step (delegated to
            resolve-prices-with-modifiers)"
    (let [plan      (fixtures/load-rate-plan "pge-e-1-2026-03-01")
          mod-base  (fixtures/load-modifier  "pge-e-1-2026-03-01")
          modifier  (assoc-in mod-base
                              [:urpx/hasRatePlanModifierVersion
                               :urpx/applicableToRatePlan]
                              [{:jsonld/id "urpx:not-this-plan"}])
          start     (start-of-day 2026 7 15)
          end       (start-of-day 2026 7 16)
          sch-with  (schedule/price-schedule-with-modifiers
                     plan [modifier] start end la-zone)
          sch-base  (schedule/price-schedule plan start end la-zone)]
      (is (= sch-base sch-with)
          "non-applicable modifier produces the same schedule as the base plan"))))

(deftest schedule-days-with-modifiers
  (testing "URPX-21n: the days-convenience wrapper composes the same as the
            base price-schedule-days"
    (let [plan     (fixtures/load-rate-plan "pge-e-1-2026-03-01")
          modifier (fixtures/load-modifier  "pge-e-1-2026-03-01")
          start    (start-of-day 2026 7 15)]
      (is (= (schedule/price-schedule-with-modifiers
              plan [modifier] start (.plus start (Duration/ofDays 1)) la-zone)
             (schedule/price-schedule-days-with-modifiers
              plan [modifier] start 1 la-zone))))))

(ns urpx.schedule-test
  (:require [clojure.test :refer [deftest is testing]]
            [urpx.price :as price]
            [urpx.schedule :as schedule]
            [urpx.test-fixtures :as fixtures])
  (:import [java.math BigDecimal]
           [java.time Duration LocalDate ZoneId ZonedDateTime]))

(def la-zone (ZoneId/of "America/Los_Angeles"))

(defn- start-of-day-zdt
  ^ZonedDateTime [year month day]
  (.atStartOfDay (LocalDate/of year month day) la-zone))

(def ^:private start-of-day start-of-day-zdt)

(deftest hourly-schedule-summer-day-pge-e-tou-c
  (testing "a 24h PG&E E-TOU-C summer day collapses into 3 contiguous TOU intervals
            (Off-Peak / Peak / Off-Peak — no Super Off-Peak in this schedule)"
    (let [plan      (fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")
          start-zdt (start-of-day-zdt 2026 7 15)
          end-zdt   (start-of-day-zdt 2026 7 16)
          sch       (schedule/price-schedule plan
                                             start-zdt end-zdt)]
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
                                             start-zdt end-zdt)]
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
        sch1  (schedule/price-schedule-days plan start 1)
        sch2  (schedule/price-schedule plan start (.plus start (Duration/ofDays 1)))]
    (is (= sch1 sch2))))

(deftest no-empty-intervals-or-overlapping
  (let [plan (fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")
        sch  (schedule/price-schedule-days plan (start-of-day 2026 7 15) 1)]
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
          hourly-sch  (schedule/price-schedule plan start end)
          quarter-sch (schedule/price-schedule plan start end opts)]
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
          sch-no-holiday (schedule/price-schedule plan start end
                                                  {:holiday? (constantly false)})
          sch-holiday    (schedule/price-schedule plan start end
                                                  {:holiday? #{(LocalDate/of 2026 7 15)}})]
      (testing "without holiday flag, Summer Peak (period 1) appears"
        (is (some #(= 1 (get-in % [:urpx.interval/resolved
                                   :urpx.resolved/tou-period-number]))
                  sch-no-holiday)))
      (testing "with the date marked as a holiday, Summer Peak is suppressed"
        (is (not-any? #(= 1 (get-in % [:urpx.interval/resolved
                                       :urpx.resolved/tou-period-number]))
                      sch-holiday))))))

(deftest schedule-throws-when-no-zone-and-plan-has-none
  (testing "Plan without urpx:timezoneIdentifier raises rather than silently
            picking a default: resolution anchors every query in the PLAN's
            declared zone, so a plan that declares none cannot be resolved in
            any."
    (let [plan-base (fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")
          plan      (dissoc plan-base :urpx/timezoneIdentifier)
          start     (start-of-day 2026 7 15)
          end       (start-of-day 2026 7 16)]
      (is (= :urpx.price/no-timezone
             (try (schedule/price-schedule plan start end) nil
                  (catch clojure.lang.ExceptionInfo e (:reason (ex-data e)))))
          "the refusal now comes from urpx.price, which needs the zone to
           resolve at all rather than only to walk: a document stating none
           states no moment"))))

;; ---------------------------------------------------------------------------
;; price-schedule-with-modifiers (URPX-21n)
;; ---------------------------------------------------------------------------
;; Mirror of price-schedule that calls resolve-prices-with-modifiers per step.
;; Verifies (a) modifier ledgers are folded into each interval's resolved
;; output, (b) the merge-adjacent + step semantics match price-schedule, and
;; (c) modifiers whose applicableToRatePlan doesn't include the base plan are
;; silently skipped (delegated behavior).

(deftest schedule-with-modifiers-folds-modifier-ledgers-per-step
  (testing "URPX-21n: every interval includes the vintage-PCIA modifier ledger
            appended to the base plan's"
    (let [plan     (fixtures/load-rate-plan "pge-e-1-2026-03-01")
          modifier (fixtures/load-modifier  "pge-e-1-2026-03-01")
          start    (start-of-day 2026 7 15)
          end      (start-of-day 2026 7 16)
          sch      (schedule/price-schedule-with-modifiers
                    plan [modifier] start end)]
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
          sch-base (schedule/price-schedule plan start end)
          sch-mod  (schedule/price-schedule-with-modifiers
                    plan [modifier] start end)]
      (is (= (count sch-base) (count sch-mod))
          "modifier addition doesn't change the day's TOU/merge structure"))))

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
                     plan [modifier] start end)
          sch-base  (schedule/price-schedule plan start end)]
      (is (= sch-base sch-with)
          "non-applicable modifier produces the same schedule as the base plan"))))

(deftest schedule-days-with-modifiers
  (testing "URPX-21n: the days-convenience wrapper composes the same as the
            base price-schedule-days"
    (let [plan     (fixtures/load-rate-plan "pge-e-1-2026-03-01")
          modifier (fixtures/load-modifier  "pge-e-1-2026-03-01")
          start    (start-of-day 2026 7 15)]
      (is (= (schedule/price-schedule-with-modifiers
              plan [modifier] start (.plus start (Duration/ofDays 1)))
             (schedule/price-schedule-days-with-modifiers
              plan [modifier] start 1))))))

(deftest intervals-are-clipped-to-the-requested-window
  (testing "the step grid is anchored to the start of `start`'s hour so interior
            boundaries fall on clock hours, but that anchoring used to leak past
            the window on both sides: a request beginning at 17:23 reported an
            interval beginning at 17:00, and the last interval ran up to a full
            step past `end`. Both attributed prices to instants the caller never
            asked about, which for a client building a 72-hour horizon means the
            horizon silently covers something else."
    (let [plan  (fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")
          beg   (ZonedDateTime/of 2026 7 15 17 23 0 0 la-zone)
          fin   (ZonedDateTime/of 2026 7 16 6 47 0 0 la-zone)
          sched (schedule/price-schedule plan beg fin)]
      (is (seq sched))
      (is (= beg (:tick/beginning (first sched)))
          "the first interval begins exactly at the requested start")
      (is (= fin (:tick/end (last sched)))
          "the last interval ends exactly at the requested end")

      (testing "no interval escapes the window at either edge"
        (is (not-any? #(.isBefore ^ZonedDateTime (:tick/beginning %) beg) sched))
        (is (not-any? #(.isAfter ^ZonedDateTime (:tick/end %) fin) sched)))

      (testing "the intervals tile the window with no gap and no overlap"
        (is (= (map :tick/beginning (rest sched))
               (map :tick/end (butlast sched)))))

      (testing "interior boundaries still fall on clock hours, so clipping did
                not cost the grid alignment the anchoring exists for"
        (let [interior (map :tick/beginning (rest sched))]
          (is (seq interior) "sanity: this window does cross a TOU boundary")
          (is (every? #(and (zero? (.getMinute ^ZonedDateTime %))
                            (zero? (.getSecond ^ZonedDateTime %)))
                      interior))))))

  (testing "a window already aligned to the grid is unchanged"
    (let [plan  (fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")
          beg   (start-of-day-zdt 2026 7 15)
          fin   (.plusDays beg 1)
          sched (schedule/price-schedule plan beg fin)]
      (is (= beg (:tick/beginning (first sched))))
      (is (= fin (:tick/end (last sched))))
      (is (= 3 (count sched)) "off-peak, peak, off-peak"))))

(defn- labels [iv]
  (select-keys (:urpx.interval/resolved iv)
               [:urpx.resolved/season-name
                :urpx.resolved/tou-period-name
                :urpx.resolved/tou-period-number]))

(deftest a-merged-interval-keeps-only-labels-true-for-all-of-it
  (testing "URPX-xqt. SCE D prices Winter and Summer identically, so May through
            September merges into one interval on ledgers. It used to carry the
            first step's Winter for all five months."
    (let [plan (fixtures/load-rate-plan "sce-d-2026-03-20")
          beg  (start-of-day 2026 5 1)
          fin  (start-of-day 2026 10 1)
          sch  (schedule/price-schedule plan beg fin)
          segs (schedule/price-segments plan [] beg fin)]
      (is (= 1 (count sch)) "still one interval: steps merge on ledgers")
      (is (not (contains? (:urpx.interval/resolved (first sch))
                          :urpx.resolved/season-name))
          "the season changes inside it, so it carries none")
      (is (= {:urpx.resolved/tou-period-name nil :urpx.resolved/tou-period-number nil}
             (labels (first sch)))
          "labels every step agrees on are kept, here the absence of a TOU period")
      (is (= [["Winter" (start-of-day-zdt 2026 5 1)]
              ["Summer" (start-of-day-zdt 2026 6 1)]]
             (mapv (juxt #(get-in % [:urpx.interval/resolved :urpx.resolved/season-name])
                         :tick/beginning)
                   segs))
          "price-segments splits on the season")))

  (testing "the modifier path follows the same rule"
    (let [sch (schedule/price-schedule-with-modifiers
               (fixtures/load-rate-plan "sce-d-2026-03-20")
               [(fixtures/load-modifier "sce-care-2026-03-20")]
               (start-of-day 2026 5 1) (start-of-day 2026 10 1))]
      (is (= 1 (count sch)))
      (is (not (contains? (:urpx.interval/resolved (first sch))
                          :urpx.resolved/season-name)))))

  (testing "a uniform season is kept"
    (let [sch (schedule/price-schedule (fixtures/load-rate-plan "sce-d-2026-03-20")
                                       (start-of-day 2026 6 1) (start-of-day 2026 7 1))]
      (is (= 1 (count sch)))
      (is (= "Summer" (get-in (first sch) [:urpx.interval/resolved
                                           :urpx.resolved/season-name])))))

  (testing "TOU labels follow the same rule. Before PG&E E-TOU-C takes effect its
            ledgers do not vary by period, so a February day merges across peak
            and off-peak: Winter holds throughout and is kept, the period does
            not and is omitted."
    (let [plan (fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")
          beg  (start-of-day 2026 2 3)
          fin  (start-of-day 2026 2 4)
          sch  (schedule/price-schedule plan beg fin)]
      (is (= 1 (count sch)))
      (is (= {:urpx.resolved/season-name "Winter"} (labels (first sch))))
      (is (< 1 (count (distinct (map labels (schedule/price-segments plan [] beg fin)))))
          "sanity: the period really does change across this day"))))

;; ---------------------------------------------------------------------------
;; ZonedDateTime bounds
;; ---------------------------------------------------------------------------

(defn- refusal [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(def ^:private horizon-fns
  "Every public walk, as `(fn [plan start end opts])`."
  [["price-schedule" schedule/price-schedule]
   ["price-schedule-with-modifiers" #(schedule/price-schedule-with-modifiers %1 [] %2 %3 %4)]
   ["price-segments" #(schedule/price-segments %1 [] %2 %3 %4)]
   ["price-schedule-days" (fn [p s _ o] (schedule/price-schedule-days p s 1 o))]
   ["price-schedule-days-with-modifiers"
    (fn [p s _ o] (schedule/price-schedule-days-with-modifiers p [] s 1 o))]])

(deftest zoned-date-time-bounds-price-the-day
  (testing "PG&E E-TOU-C 2026-03-01 over 2026-07-15..16 America/Los_Angeles,
            bounded by ZonedDateTimes"
    (let [plan (fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")
          s    (start-of-day-zdt 2026 7 15)
          e    (start-of-day-zdt 2026 7 16)]
      (doseq [[nm segs] [["price-segments" (schedule/price-segments plan [] s e)]
                         ["price-schedule" (schedule/price-schedule plan s e)]]]
        (is (= ["2026-07-15T00:00-07:00[America/Los_Angeles]"
                "2026-07-15T16:00-07:00[America/Los_Angeles]"
                "2026-07-15T21:00-07:00[America/Los_Angeles]"]
               (mapv (comp str :tick/beginning) segs))
            nm)
        (is (= e (:tick/end (last segs))) nm)
        (is (not-any? :urpx.interval/refused segs) nm)))))

(deftest bounds-in-any-zone-walk-in-the-plans-zone
  (let [plan (fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")
        s    (start-of-day-zdt 2026 7 15)
        e    (start-of-day-zdt 2026 7 16)
        in   #(.withZoneSameInstant ^ZonedDateTime %1 (ZoneId/of %2))]
    (doseq [[nm f] horizon-fns
            [zs ze] [["UTC" "UTC"] ["-07:00" "-07:00"] ["Asia/Kolkata" "Asia/Kolkata"]
                     ["UTC" "America/New_York"]]
            :let [segs (f plan (in s zs) (in e ze) nil)]]
      (is (= (f plan s e nil) segs)
          (str nm ": bounds in " zs " and " ze " are the same instants"))
      (is (every? #(= la-zone (.getZone ^ZonedDateTime (:tick/beginning %))
                      (.getZone ^ZonedDateTime (:tick/end %)))
                  segs)
          (str nm ": bounds in " zs " and " ze " give intervals in the plan's zone")))))

(deftest a-bound-that-is-not-a-zoned-date-time-is-refused
  (let [plan (fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")
        s    (start-of-day-zdt 2026 7 15)
        e    (start-of-day-zdt 2026 7 16)]
    (doseq [[nm f] horizon-fns
            [label bs be] [["an Instant start" (.toInstant s) e]
                           ["an Instant end" s (.toInstant e)]
                           ["a LocalDateTime start" (.toLocalDateTime s) e]
                           ["a nil start" nil e]]
            :when (not (and (re-find #"-days" nm) (= label "an Instant end")))]
      (is (= :urpx.schedule/bound-not-zoned-date-time
             (:reason (refusal #(f plan bs be nil))))
          (str nm ": " label)))))

(deftest opts-that-are-not-a-map-are-refused
  (testing "a ZoneId where the zone argument used to go"
    (let [plan (fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")
          s    (start-of-day-zdt 2026 7 15)
          e    (start-of-day-zdt 2026 7 16)]
      (doseq [[nm f] horizon-fns]
        (is (= :urpx.schedule/opts-not-a-map
               (:reason (refusal #(f plan s e la-zone))))
            nm)))))

(deftest a-day-is-24-hours-across-a-daylight-transition
  (let [plan (fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")
        s    (start-of-day-zdt 2026 3 8)]
    (doseq [sch [(schedule/price-schedule-days plan s 1)
                 (schedule/price-schedule-days-with-modifiers plan [] s 1)]]
      (is (= (ZonedDateTime/of 2026 3 9 1 0 0 0 la-zone) (:tick/end (last sch)))))))

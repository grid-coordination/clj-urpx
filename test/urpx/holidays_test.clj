(ns urpx.holidays-test
  (:require [clojure.test :refer [deftest is testing]]
            [urpx.holidays :as holidays]
            [urpx.test-fixtures :as fixtures])
  (:import [java.time LocalDate]))

(defn- ld [y m d] (LocalDate/of y m d))

;; ---------------------------------------------------------------------------
;; End-to-end: derive-predicate from the vendored E-TOU-D fixture
;; ---------------------------------------------------------------------------

(deftest derive-predicate-returns-nil-when-no-calendar
  (testing "PG&E E-1 has no embedded HolidayCalendar"
    (let [plan (fixtures/load-rate-plan "pge-e-1-2026-03-01")]
      (is (nil? (holidays/derive-predicate plan))))))

(deftest derive-predicate-recognizes-fixture-holidays-2026
  (let [plan (fixtures/load-rate-plan "pge-e-tou-d-2026-03-27")
        h?   (holidays/derive-predicate plan)]
    (is (some? h?) "fixture embeds a HolidayCalendar")
    (testing "Nth-weekday holidays — 2026"
      (is (h? (ld 2026 2 16))  "President's Day = 3rd Mon Feb 2026")
      (is (h? (ld 2026 5 25))  "Memorial Day = Last Mon May 2026")
      (is (h? (ld 2026 9 7))   "Labor Day = 1st Mon Sep 2026")
      (is (h? (ld 2026 11 26)) "Thanksgiving = 4th Thu Nov 2026"))
    (testing "Fixed-date holidays observed on actual date — 2026 (no weekend collision)"
      (is (h? (ld 2026 1 1))   "New Year's Day 2026 (Thu) — actual date")
      (is (h? (ld 2026 11 11)) "Veterans Day 2026 (Wed) — actual date")
      (is (h? (ld 2026 12 25)) "Christmas 2026 (Fri) — actual date"))
    (testing "Fixed-date holidays with '(legally observed)' — 2026 weekend collisions"
      ;; July 4, 2026 = Saturday → observed Friday July 3
      (is (h? (ld 2026 7 3))   "Independence Day 2026 observed Fri Jul 3")
      (is (not (h? (ld 2026 7 4))) "Sat Jul 4 is NOT the observed date"))
    (testing "Non-holiday weekday returns false"
      (is (not (h? (ld 2026 7 15)))))))

(deftest derive-predicate-handles-multiple-years
  (testing "year-specific computation: same rules, different year, different dates"
    (let [plan (fixtures/load-rate-plan "pge-e-tou-d-2026-03-27")
          h?   (holidays/derive-predicate plan)]
      (is (h? (ld 2027 2 15)) "President's Day = 3rd Mon Feb 2027")
      (is (h? (ld 2027 5 31)) "Memorial Day = Last Mon May 2027")
      (is (h? (ld 2027 9 6))  "Labor Day = 1st Mon Sep 2027")
      ;; Jan 1 2027 = Friday — actual date observance
      (is (h? (ld 2027 1 1))  "New Year's 2027 = Fri (actual)")
      ;; Christmas 2027 = Saturday → observed Friday Dec 24
      (is (h? (ld 2027 12 24)) "Christmas 2027 observed Fri Dec 24")
      (is (not (h? (ld 2027 12 25)))))))

(deftest mlk-not-in-pge-fixture
  (testing "MLK Day is NOT in PG&E E-TOU-D's holiday list — derived predicate
            correctly returns false for it"
    (let [plan (fixtures/load-rate-plan "pge-e-tou-d-2026-03-27")
          h?   (holidays/derive-predicate plan)]
      (is (not (h? (ld 2026 1 19))))
      (is (not (h? (ld 2027 1 18)))))))

;; ---------------------------------------------------------------------------
;; Hand-built calendars — exercise rule-string parsing directly
;; ---------------------------------------------------------------------------

(defn- calendar-plan
  "Wrap a vector of Holiday maps into the minimum coerced-plan shape that
   `derive-predicate` walks: a RatePlan with PlanElements.hasHolidayCalendar."
  [holidays]
  {:jsonld/type "urpx:RatePlan" :urpx/timezoneIdentifier "America/Los_Angeles"
   :urpx/hasRatePlanVersion
   {:jsonld/type "urpx:RatePlanVersion"
    :urpx/changeReason {:jsonld/id "urpx:rateCase"}
    :urpx/forRatePlan {:jsonld/id "ex:parent"}
    :urpx/hasCoverageClaim {:jsonld/type "urpx:CoverageClaim" :urpx/completenessResult {:jsonld/id "urpx:complete"}}
    :urpx/serviceRole {:jsonld/id "urpx:bundledServiceRole"}
    :urpx/hasPlanElements
    {:jsonld/type "urpx:PlanElements"
     :urpx/hasHolidayCalendar
     {:jsonld/type "urpx:HolidayCalendar"
      :urpx/hasHoliday holidays}}}})

(deftest holiday-date-applies-only-to-its-own-year
  (testing "urpx:holidayDate is year-specific — does not recur in other years"
    (let [plan (calendar-plan
                [{:jsonld/type "urpx:Holiday"
                  :urpx/holidayName "One-off Outage Holiday"
                  :urpx/holidayDate (ld 2026 8 15)}])
          h? (holidays/derive-predicate plan)]
      (is (h? (ld 2026 8 15)) "matches the literal date")
      (is (not (h? (ld 2027 8 15))) "does NOT recur next year")
      (is (not (h? (ld 2026 8 14)))))))

(deftest explicit-observance-rule-overrides-implicit
  (testing "urpx:observanceRule on the Holiday wins over rule-string defaults"
    (let [plan (calendar-plan
                [{:jsonld/type "urpx:Holiday"
                  :urpx/holidayName "July 4 (actual)"
                  :urpx/holidayRule "July 4 (legally observed)"
                  :urpx/observanceRule {:jsonld/id "urpx:actualDateObservance"}}])
          h? (holidays/derive-predicate plan)]
      (is (h? (ld 2026 7 4)) "explicit actual-date overrides nearest-weekday default")
      (is (not (h? (ld 2026 7 3)))))))

(deftest monday-if-weekend-observance
  (testing "urpx:mondayIfWeekendObservance: Sat → Mon, Sun → Mon"
    (let [plan (calendar-plan
                [{:jsonld/type "urpx:Holiday"
                  :urpx/holidayName "Independence Day"
                  :urpx/holidayRule "July 4"
                  :urpx/observanceRule {:jsonld/id "urpx:mondayIfWeekendObservance"}}])
          h? (holidays/derive-predicate plan)]
      ;; Sat Jul 4 2026 → observed Mon Jul 6
      (is (h? (ld 2026 7 6)))
      (is (not (h? (ld 2026 7 4))))
      ;; Sun Jul 4 2027 → observed Mon Jul 5
      (is (h? (ld 2027 7 5)))
      (is (not (h? (ld 2027 7 4)))))))

(deftest friday-if-weekend-observance
  (testing "urpx:fridayIfWeekendObservance: Sat → Fri, Sun → Fri"
    (let [plan (calendar-plan
                [{:jsonld/type "urpx:Holiday"
                  :urpx/holidayName "Independence Day"
                  :urpx/holidayRule "July 4"
                  :urpx/observanceRule {:jsonld/id "urpx:fridayIfWeekendObservance"}}])
          h? (holidays/derive-predicate plan)]
      ;; Sat Jul 4 2026 → observed Fri Jul 3
      (is (h? (ld 2026 7 3)))
      ;; Sun Jul 4 2027 → observed Fri Jul 2
      (is (h? (ld 2027 7 2))))))

(deftest unrecognized-rule-throws
  (testing "Unknown rule strings throw ex-info pointing at the offending value"
    (let [plan (calendar-plan
                [{:jsonld/type "urpx:Holiday"
                  :urpx/holidayName "Mystery"
                  :urpx/holidayRule "the third blue moon"}])]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Unrecognized urpx:holidayRule"
                            (holidays/derive-predicate plan))))))

(deftest unrecognized-observance-rule-throws
  (testing "An observanceRule IRI outside the recognized four throws, on a rule or a literal date"
    (doseq [holiday [{:urpx/holidayRule "July 4"}
                     {:urpx/holidayDate (ld 2027 7 4)}]]
      (let [plan (calendar-plan
                  [(merge {:jsonld/type "urpx:Holiday"
                           :urpx/holidayName "Independence Day"
                           :urpx/observanceRule {:jsonld/id "urpx:mondayIfSundayObservance"}}
                          holiday)])
            e    (try (holidays/derive-predicate plan) nil
                      (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e))
        (is (re-find #"Unrecognized urpx:observanceRule" (ex-message e)))
        (is (= "urpx:mondayIfSundayObservance" (:observance-rule (ex-data e))))
        (is (= "Independence Day" (get-in (ex-data e) [:holiday :urpx/holidayName])))))))

(deftest holiday-without-date-or-rule-throws
  (let [plan (calendar-plan
              [{:jsonld/type "urpx:Holiday"
                :urpx/holidayName "Useless"}])]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"urpx:holidayDate or urpx:holidayRule"
                          (holidays/derive-predicate plan)))))

(deftest fifth-weekday-of-month
  (testing "Some months have a fifth occurrence of a given weekday"
    (let [plan (calendar-plan
                [{:jsonld/type "urpx:Holiday"
                  :urpx/holidayName "Fifth Friday"
                  :urpx/holidayRule "Fifth Friday in January"}])
          h? (holidays/derive-predicate plan)]
      ;; January 2026 has Fridays on 2, 9, 16, 23, 30 — 5th = Jan 30
      (is (h? (ld 2026 1 30))))))

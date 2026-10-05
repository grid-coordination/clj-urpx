(ns urpx.holidays
  "Derive a holiday-date predicate from a rate plan's embedded
  urpx:HolidayCalendar.

  URPX rate plans may embed a HolidayCalendar at
    :urpx/hasRatePlanVersion → :urpx/hasPlanElements → :urpx/hasHolidayCalendar
  listing each Holiday with a urpx:holidayName plus either:
    - urpx:holidayDate    — literal xsd:date for one specific instance, OR
    - urpx:holidayRule    — free-form string like 'Third Monday in February'
                            or 'January 1 (legally observed)'.

  `derive-predicate` returns a `(java.time.LocalDate -> bool)` predicate, or
  nil when the plan has no embedded calendar. The predicate covers any year
  on demand; per-year date sets are computed once and cached internally so
  driving `price-schedule` across thousands of timestamps doesn't repeat work.

  Recognized urpx:holidayRule patterns:
    - 'First|Second|Third|Fourth|Fifth <Weekday> in <Month>'
    - 'Last <Weekday> in <Month>'
    - '<Month> <Day>'                       — observed on the actual date
    - '<Month> <Day> (legally observed)'    — observed on the nearest weekday
                                              when the actual date falls on
                                              a weekend (Sat → Fri, Sun → Mon)

  When a Holiday carries an explicit urpx:observanceRule (Ref to one of
  urpx:actualDateObservance / urpx:nearestWeekdayObservance /
  urpx:mondayIfWeekendObservance / urpx:fridayIfWeekendObservance) it
  overrides the implicit observance derived from the rule string.

  An unrecognized urpx:holidayRule or urpx:observanceRule throws ex-info.
  Pass an explicit `:holiday?` predicate in `urpx.price/resolve-prices` opts
  to override the derived calendar."
  (:require [clojure.string :as str]
            [urpx.index :as index])
  (:import [java.time DayOfWeek LocalDate Month]
           [java.time.temporal TemporalAdjusters]
           [java.util.concurrent ConcurrentHashMap]))

;; ---------------------------------------------------------------------------
;; Vocabulary tables
;; ---------------------------------------------------------------------------

(def ^:private month->m
  (into {} (map (fn [^Month m] [(str/lower-case (.name m)) m]))
        (Month/values)))

(def ^:private dow->d
  (into {} (map (fn [^DayOfWeek d] [(str/lower-case (.name d)) d]))
        (DayOfWeek/values)))

(def ^:private ordinal->n
  {"first" 1, "second" 2, "third" 3, "fourth" 4, "fifth" 5})

;; ---------------------------------------------------------------------------
;; Observance shifters — how a holiday is observed when it lands on a weekend
;; ---------------------------------------------------------------------------

(defn- observe-actual ^LocalDate [^LocalDate d] d)

(defn- observe-nearest-weekday ^LocalDate [^LocalDate d]
  (condp = (.getDayOfWeek d)
    DayOfWeek/SATURDAY (.minusDays d 1)
    DayOfWeek/SUNDAY   (.plusDays d 1)
    d))

(defn- observe-monday-if-weekend ^LocalDate [^LocalDate d]
  (condp = (.getDayOfWeek d)
    DayOfWeek/SATURDAY (.plusDays d 2)
    DayOfWeek/SUNDAY   (.plusDays d 1)
    d))

(defn- observe-friday-if-weekend ^LocalDate [^LocalDate d]
  (condp = (.getDayOfWeek d)
    DayOfWeek/SATURDAY (.minusDays d 1)
    DayOfWeek/SUNDAY   (.minusDays d 2)
    d))

(def ^:private observance-id->fn
  {"urpx:actualDateObservance"      observe-actual
   "urpx:nearestWeekdayObservance"  observe-nearest-weekday
   "urpx:mondayIfWeekendObservance" observe-monday-if-weekend
   "urpx:fridayIfWeekendObservance" observe-friday-if-weekend})

;; ---------------------------------------------------------------------------
;; Date computation helpers
;; ---------------------------------------------------------------------------

(defn- nth-weekday-of-month
  ^LocalDate [^long year ^Month month ^DayOfWeek dow ^long n]
  (.with (LocalDate/of (int year) month 1)
         (TemporalAdjusters/dayOfWeekInMonth (int n) dow)))

(defn- last-weekday-of-month
  ^LocalDate [^long year ^Month month ^DayOfWeek dow]
  (.with (LocalDate/of (int year) month 1)
         (TemporalAdjusters/lastInMonth dow)))

;; ---------------------------------------------------------------------------
;; Rule-string parsing
;; ---------------------------------------------------------------------------

(def ^:private nth-weekday-re
  #"(?i)^(First|Second|Third|Fourth|Fifth)\s+(Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday)\s+in\s+(January|February|March|April|May|June|July|August|September|October|November|December)$")

(def ^:private last-weekday-re
  #"(?i)^Last\s+(Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday)\s+in\s+(January|February|March|April|May|June|July|August|September|October|November|December)$")

(def ^:private fixed-date-re
  #"(?i)^(January|February|March|April|May|June|July|August|September|October|November|December)\s+(\d{1,2})(?:\s*\((.+)\))?$")

(defn- parse-rule
  "Parse a urpx:holidayRule string into [base-fn implicit-obs] where
   `base-fn` is `(year) -> LocalDate` and `implicit-obs` is the observance
   shifter implied by the rule (defaulting to actual-date for everything
   except '(legally observed)' fixed-date strings, which default to
   nearest-weekday). Throws ex-info for unrecognized rules."
  [^String rule]
  (let [s (str/trim rule)]
    (or (when-let [[_ ord dow mon] (re-matches nth-weekday-re s)]
          (let [n (ordinal->n (str/lower-case ord))
                d (dow->d     (str/lower-case dow))
                m (month->m   (str/lower-case mon))]
            [(fn [year] (nth-weekday-of-month year m d n))
             observe-actual]))

        (when-let [[_ dow mon] (re-matches last-weekday-re s)]
          (let [d (dow->d   (str/lower-case dow))
                m (month->m (str/lower-case mon))]
            [(fn [year] (last-weekday-of-month year m d))
             observe-actual]))

        (when-let [[_ mon day suffix] (re-matches fixed-date-re s)]
          (let [m   (month->m (str/lower-case mon))
                day (Long/parseLong day)
                obs (if (and suffix (re-find #"(?i)legally\s+observed" suffix))
                      observe-nearest-weekday
                      observe-actual)]
            [(fn [year] (LocalDate/of (int year) m (int day)))
             obs]))

        (throw (ex-info (str "Unrecognized urpx:holidayRule: " (pr-str rule))
                        {:rule rule})))))

;; ---------------------------------------------------------------------------
;; Holiday → year-fn
;; ---------------------------------------------------------------------------

(defn- holiday->year-fn
  "Compile a coerced Holiday entity into a function `(long-year -> LocalDate or nil)`.
   Returns nil for years outside the holiday's applicability (a urpx:holidayDate
   only applies to its own year). Throws ex-info when neither holidayDate nor
   holidayRule is present, or when the rule string or urpx:observanceRule
   isn't recognized."
  [holiday]
  (let [{date         :urpx/holidayDate
         rule         :urpx/holidayRule
         observance   :urpx/observanceRule} holiday
        explicit-obs  (when observance
                        (or (observance-id->fn (:jsonld/id observance))
                            (throw (ex-info (str "Unrecognized urpx:observanceRule: "
                                                 (pr-str (:jsonld/id observance)))
                                            {:observance-rule (:jsonld/id observance)
                                             :holiday (select-keys holiday [:jsonld/id :urpx/holidayName])}))))]
    (cond
      date
      (let [^LocalDate d date
            target-year  (.getYear d)
            shift        (or explicit-obs observe-actual)]
        (fn [year]
          (when (= year target-year) (shift d))))

      rule
      (let [[base-fn implicit-obs] (parse-rule rule)
            shift                  (or explicit-obs implicit-obs)]
        (fn [year] (shift (base-fn year))))

      :else
      (throw (ex-info "Holiday must carry urpx:holidayDate or urpx:holidayRule"
                      {:holiday (select-keys holiday [:jsonld/id :urpx/holidayName])})))))

;; ---------------------------------------------------------------------------
;; Calendar lookup
;; ---------------------------------------------------------------------------

(defn- plan-calendar
  "Return the urpx:HolidayCalendar for a coerced rate plan or modifier, or nil
   when none is present.

   Three arms, in precedence order. A `version` snapshot supplied by the caller
   wins: under v0.5.1 un-nesting the snapshot is a top-level sibling of the
   identity plan, so the plan itself carries no calendar to find. Otherwise the
   two v0.2.x nested paths are read as before. The property path below the
   version is identical in both shapes."
  ([plan] (plan-calendar plan nil))
  ([plan version]
   (let [calendar-of (fn [snapshot]
                       (index/single
                        (get-in snapshot [:urpx/hasPlanElements :urpx/hasHolidayCalendar])
                        "holiday calendars on one PlanElements"))]
     (if version
       ;; An explicitly supplied snapshot is the authority, not merely the first
       ;; preference: falling through to the nested path when it has no calendar
       ;; would consult a different snapshot than the one prices resolve against.
       (calendar-of version)
       (or (calendar-of (index/nested-version plan :urpx/hasRatePlanVersion))
           (calendar-of (index/nested-version plan :urpx/hasRatePlanModifierVersion)))))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn derive-predicate
  "Return a `(LocalDate -> boolean)` predicate computed from `plan`'s
   embedded urpx:HolidayCalendar, or nil when the plan has no calendar.

   The predicate computes the holiday set for any year on demand; results
   are cached internally so reusing the predicate across many dates is
   cheap. Throws ex-info if any Holiday in the calendar carries an
   unparseable urpx:holidayRule or an unrecognized urpx:observanceRule.

   The 2-arity takes a resolved version snapshot, for v0.5.1 documents that
   carry the version top-level rather than nested under the plan. Without it
   such a plan yields no calendar and every date reads as a non-holiday."
  ([plan] (derive-predicate plan nil))
  ([plan version]
  (when-let [calendar (plan-calendar plan version)]
    (let [year-fns (->> (:urpx/hasHoliday calendar)
                        (mapv holiday->year-fn))
          cache    (ConcurrentHashMap.)]
      (fn ^Boolean [^LocalDate d]
        (let [year (.getYear d)
              dates (or (.get cache year)
                        (let [s (into #{} (keep #(% year)) year-fns)]
                          (or (.putIfAbsent cache year s) s)))]
          (contains? dates d)))))))

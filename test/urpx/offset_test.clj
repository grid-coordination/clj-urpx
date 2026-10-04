(ns urpx.offset-test
  "Temporal literals written with a UTC offset.

  A value without an offset is a wall-clock reading in the zone the reader
  resolves; a value with one names its instant, and every reader keeps that
  instant."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [urpx.coerce :as coerce]
            [urpx.core :as core]
            [urpx.coverage :as cov]
            [urpx.holidays :as holidays]
            [urpx.index :as index]
            [urpx.load :as load]
            [urpx.price :as price]
            [urpx.schedule :as schedule]
            [urpx.schema :as schema]
            [urpx.test-fixtures :as fixtures])
  (:import [java.io StringReader]
           [java.time Duration LocalDate LocalDateTime LocalTime OffsetTime ZoneId
            ZoneOffset ZonedDateTime]))

(def ^:private la (ZoneId/of "America/Los_Angeles"))
(defn- la-at [y m d h mi] (ZonedDateTime/of y m d h mi 0 0 la))

(defn- parse-json [s] (core/parse-rate-plan (StringReader. s)))

;; ---------------------------------------------------------------------------
;; Decode and encode
;; ---------------------------------------------------------------------------

(deftest a-datetime-decodes-by-whether-it-carries-an-offset
  (let [z (coerce/coerce schema/DateTime "2025-01-01T00:00:00Z")
        o (coerce/coerce schema/DateTime "2025-01-01T00:00:00-05:00")
        n (coerce/coerce schema/DateTime "2025-01-01T00:00:00")]
    (testing "Z is a ZonedDateTime in UTC"
      (is (instance? ZonedDateTime z))
      (is (= ZoneOffset/UTC (.getZone ^ZonedDateTime z))))
    (testing "an explicit offset is a ZonedDateTime whose zone is that offset"
      (is (instance? ZonedDateTime o))
      (is (= (ZoneOffset/of "-05:00") (.getZone ^ZonedDateTime o)))
      (is (= (ZonedDateTime/of 2025 1 1 5 0 0 0 ZoneOffset/UTC)
             (.withZoneSameInstant ^ZonedDateTime o ZoneOffset/UTC))))
    (testing "no offset stays a LocalDateTime"
      (is (= (LocalDateTime/of 2025 1 1 0 0) n)))
    (is (every? #(coerce/validate schema/DateTime %) [z o n]))))

(deftest a-typed-literal-object-decodes-like-a-plain-string
  (testing "{\"@value\" v \"@type\" t}, as the upstream coned resolved filing
            writes urpx:evaluatedOn"
    (let [raw (parse-json "{\"@type\": \"urpx:Evaluation\",
                            \"urpx:evaluatedOn\": {\"@value\": \"2026-05-01T16:00:00Z\",
                                                   \"@type\": \"xsd:dateTime\"}}")
          e   (coerce/coerce schema/Evaluation raw)]
      (is (= (coerce/coerce schema/DateTime "2026-05-01T16:00:00Z") (:urpx/evaluatedOn e)))
      (is (coerce/validate schema/Evaluation e))
      (is (= "2026-05-01T16:00:00Z" (:urpx/evaluatedOn (coerce/encode schema/Evaluation e)))
          "and emits as a plain string")))
  (testing "for dates and times too"
    (is (= (LocalDate/of 2026 5 1)
           (coerce/coerce schema/Date {:jsonld/value "2026-05-01" :jsonld/type "xsd:date"})))
    (is (= (LocalTime/of 16 0)
           (coerce/coerce schema/Time {:jsonld/value "16:00:00" :jsonld/type "xsd:time"})))))

(deftest every-temporal-literal-round-trips-exactly
  (doseq [[s schema-name lexes]
          [[schema/DateTime "DateTime" ["2026-01-01T00:00:00" "2026-01-01T16:30:15"
                                        "2026-01-01T00:00:00Z" "2026-01-01T00:00:00-05:00"
                                        "2026-01-01T16:30:15.25+05:30"]]
           [schema/Time "Time" ["16:00:00" "00:00:00" "16:00:00Z" "16:00:00-05:00"
                                "16:30:15.5+05:30"]]
           [schema/Date "Date" ["2026-02-01" "2026-02-01Z" "2026-02-01-05:00"]]]
          lex lexes]
    (let [v (coerce/coerce s lex)]
      (is (coerce/validate s v) (str schema-name " " lex " validates"))
      (is (= lex (coerce/encode s v)) (str schema-name " " lex " encodes as read")))))

(deftest a-literal-encodes-in-xsd-canonical-form
  (doseq [[s lex canonical] [[schema/DateTime "2026-01-01T00:00:00+00:00" "2026-01-01T00:00:00Z"]
                             [schema/DateTime "2026-01-01T00:00:00.250" "2026-01-01T00:00:00.25"]
                             [schema/Time "16:00:00.000-00:00" "16:00:00Z"]
                             [schema/Date "2026-01-01+00:00" "2026-01-01Z"]]]
    (is (= canonical (coerce/encode s (coerce/coerce s lex))) lex)))

(deftest an-offset-time-is-an-offset-time
  (is (= (OffsetTime/of 16 0 0 0 (ZoneOffset/of "-05:00"))
         (coerce/coerce schema/Time "16:00:00-05:00"))))

(deftest an-offset-date-keeps-its-day-and-offset
  (testing "java.time has no OffsetDate: the start of that day in that offset"
    (is (= (ZonedDateTime/of 2026 2 1 0 0 0 0 (ZoneOffset/of "-05:00"))
           (coerce/coerce schema/Date "2026-02-01-05:00"))))
  (testing "a dateTime that is not at midnight is not a date"
    (is (not (coerce/validate schema/Date
                              (coerce/coerce schema/DateTime "2026-02-01T01:00:00Z"))))))

(deftest a-malformed-literal-refuses-with-a-reason
  (doseq [[s lex reason] [[schema/DateTime "2026-13-01T00:00:00" :urpx.schema/malformed-datetime]
                          [schema/DateTime "2026-01-01T00:00:00+5" :urpx.schema/malformed-datetime]
                          [schema/Date "2026-02-30" :urpx.schema/malformed-date]
                          [schema/Time "25:00:00" :urpx.schema/malformed-time]]]
    (let [e (try (coerce/coerce s lex) nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (= reason (:reason (ex-data e))) lex)
      (is (= lex (:value (ex-data e)))))))

(deftest load-propagates-a-malformed-literal
  (let [src (StringReader.
             "{\"@id\": \"x:v\", \"@type\": \"urpx:RatePlanVersion\",
               \"urpx:forRatePlan\": {\"@id\": \"x:p\"},
               \"urpx:effectiveDate\": \"2026-02-30\"}")
        e   (try (load/documents [src]) nil
                 (catch clojure.lang.ExceptionInfo e e))]
    (is (= :urpx.schema/malformed-date (:reason (ex-data e))))))

(deftest a-document-whose-only-temporal-is-zoned-is-coerced
  (testing "the uncoerced test counts zoned values as java.time values"
    (is (not (coerce/uncoerced?
              {:urpx/version "2026-03-20"
               :urpx/effectiveDateTime (coerce/coerce schema/DateTime "2026-03-20T00:00:00Z")})))
    (is (not (coerce/uncoerced?
              {:urpx/version "2026-03-20"
               :urpx/startTime (coerce/coerce schema/Time "16:00:00Z")})))))

;; ---------------------------------------------------------------------------
;; Version selection
;; ---------------------------------------------------------------------------

(defn- with-effective
  "`plan` with its nested version's effective bound replaced by `v`."
  [plan v]
  (update plan :urpx/hasRatePlanVersion
          #(-> % (dissoc :urpx/effectiveDate) (assoc :urpx/effectiveDateTime v))))

(defn- two-versions
  "SCE D's two filings as one plan's candidates, the newer taking effect at
   `newer-eff` and the older at `older-eff`."
  [older-eff newer-eff]
  (let [a (with-effective (fixtures/load-rate-plan "sce-d-2025-11-15") older-eff)
        b (with-effective (fixtures/load-rate-plan "sce-d-2026-03-20") newer-eff)]
    {:plan (dissoc b :urpx/hasRatePlanVersion)
     :opts {:versions-by-plan (index/versions-by-plan [a b])}}))

(deftest an-offset-effective-instant-selects-as-the-same-wall-clock
  (testing "on a Pacific plan, 08:00Z in January is 00:00 wall clock"
    (let [older (LocalDateTime/of 2025 11 15 0 0)
          rate  (fn [newer-eff at]
                  (let [{:keys [plan opts]} (two-versions older newer-eff)]
                    (price/marginal-unit-rate (price/resolve-prices plan at opts))))
          naive (LocalDateTime/of 2026 1 10 0 0)
          zoned (coerce/coerce schema/DateTime "2026-01-10T08:00:00Z")]
      (doseq [at [(la-at 2026 1 9 23 59) (la-at 2026 1 10 0 0)]]
        (is (= (rate naive at) (rate zoned at)) (str at)))
      (is (not= (rate zoned (la-at 2026 1 9 23 59)) (rate zoned (la-at 2026 1 10 0 0)))
          "sanity: the boundary is where the versions change"))))

(deftest one-instant-spelled-two-ways-is-a-tie
  (let [{:keys [plan opts]} (two-versions (LocalDateTime/of 2026 1 10 0 0)
                                          (coerce/coerce schema/DateTime "2026-01-10T08:00:00Z"))
        e (try (price/resolve-prices plan (la-at 2026 2 1 12 0) opts) nil
               (catch clojure.lang.ExceptionInfo e e))]
    (is (= :ambiguous-version (:reason (ex-data e))))))

;; ---------------------------------------------------------------------------
;; Schedules
;; ---------------------------------------------------------------------------

(deftest an-offset-time-bracket-is-a-segment-boundary
  (testing "16:00-05:00 is 14:00 Pacific in July. Rewriting every 16:00 bound of
            E-TOU-C that way moves the peak start, and the segments follow it."
    (let [plan  (walk/postwalk #(if (= (LocalTime/of 16 0) %)
                                  (OffsetTime/of 16 0 0 0 (ZoneOffset/of "-05:00"))
                                  %)
                               (fixtures/load-rate-plan "pge-e-tou-c-2026-03-01"))
          start (.toInstant (la-at 2026 7 15 0 0))
          segs  (schedule/price-segments plan [] start (.plus start (Duration/ofHours 48)) la)
          interior (set (map #(.toLocalTime ^ZonedDateTime (:tick/beginning %)) (rest segs)))]
      (is (= #{(LocalTime/of 14 0) (LocalTime/of 21 0)} interior))
      (is (not= (price/marginal-unit-rate (price/resolve-prices plan (la-at 2026 7 15 13 59)))
                (price/marginal-unit-rate (price/resolve-prices plan (la-at 2026 7 15 14 0))))))))

(deftest an-offset-datetime-literal-is-a-candidate
  (let [cands (set (#'schedule/candidate-instants
                    [{:urpx/effectiveDateTime (coerce/coerce schema/DateTime "2026-07-15T21:30:00Z")}]
                    (.toInstant (la-at 2026 7 15 0 0))
                    (.toInstant (la-at 2026 7 16 0 0))
                    la))]
    (is (contains? cands (.toInstant (la-at 2026 7 15 14 30))))))

;; ---------------------------------------------------------------------------
;; Coverage
;; ---------------------------------------------------------------------------

(deftest coverage-reads-offset-bounds-as-their-instants
  (let [raw (parse-json
             "{\"@id\": \"x:v\", \"@type\": \"urpx:RatePlanVersion\",
               \"urpx:forRatePlan\": {\"@id\": \"x:p\"},
               \"urpx:effectiveDate\": \"2026-02-01\",
               \"urpx:hasCoverageClaim\": {
                 \"@type\": \"urpx:CoverageClaim\",
                 \"urpx:completenessResult\": {\"@id\": \"urpx:partial\"},
                 \"urpx:hasTemporalScope\": {
                   \"@type\": \"urpx:TemporalScope\",
                   \"urpx:scopeKind\": {\"@id\": \"urpx:coverage\"},
                   \"urpx:startDateTime\": \"2026-02-01T08:00:00Z\",
                   \"urpx:endDate\": \"2026-06-02-04:00\"},
                 \"urpx:explainedBy\": {
                   \"@type\": \"urpx:Evaluation\",
                   \"urpx:evaluatedOn\": {\"@value\": \"2026-05-01T16:00:00Z\",
                                          \"@type\": \"xsd:dateTime\"}}}}")
        v      (coerce/coerce-doc raw)
        report (cov/of-version v)
        window (cov/coverage-interval report la)]
    (is (coerce/validate schema/CoverageClaim (:urpx/hasCoverageClaim v)))
    (is (= (la-at 2026 2 1 0 0) (:tick/beginning window)))
    (is (= (la-at 2026 6 1 21 0) (:tick/end window)))
    (is (= (coerce/coerce schema/DateTime "2026-05-01T16:00:00Z")
           (-> report :urpx.coverage/evaluations first :evaluated-on)))
    (is (instance? ZonedDateTime
                   (get-in report [:urpx.coverage/coverage-window :urpx/startDateTime])))))

;; ---------------------------------------------------------------------------
;; Dates
;; ---------------------------------------------------------------------------

(deftest an-offset-holiday-date-is-the-day-as-written
  (let [pred (holidays/derive-predicate
              {:urpx/hasRatePlanVersion
               {:urpx/hasPlanElements
                {:urpx/hasHolidayCalendar
                 {:jsonld/type "urpx:HolidayCalendar"
                  :urpx/hasHoliday [{:jsonld/type "urpx:Holiday"
                                     :urpx/holidayName "Independence Day"
                                     :urpx/holidayDate (coerce/coerce schema/Date "2026-07-04Z")}]}}}})]
    (is (pred (LocalDate/of 2026 7 4)))
    (is (not (pred (LocalDate/of 2026 7 3))))))

(deftest an-out-of-range-offset-refuses-with-a-reason
  (testing "java.time rejects an offset beyond +-18:00 with a DateTimeException
            that is not a parse exception"
    (doseq [[s lex reason] [[schema/Date "2026-02-01+19:00" :urpx.schema/malformed-date]
                            [schema/DateTime "2026-02-01T00:00:00+19:00" :urpx.schema/malformed-datetime]
                            [schema/Time "10:00:00+19:00" :urpx.schema/malformed-time]]]
      (let [e (try (coerce/coerce s lex) nil
                   (catch Exception e e))]
        (is (= reason (:reason (ex-data e))) lex)))))

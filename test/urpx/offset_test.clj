(ns urpx.offset-test
  "Temporal literals written with a UTC offset.

  A dateTime or time without an offset is a wall-clock reading in the zone the
  reader resolves; one with an offset names its instant, and every reader keeps
  that instant. A date is the calendar day it names, with or without an offset."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [urpx.coerce :as coerce]
            [urpx.core :as core]
            [urpx.coverage :as cov]
            [urpx.emit :as emit]
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
           [schema/Date "Date" ["2026-02-01"]]]
          lex lexes]
    (let [v (coerce/coerce s lex)]
      (is (coerce/validate s v) (str schema-name " " lex " validates"))
      (is (= lex (coerce/encode s v)) (str schema-name " " lex " encodes as read")))))

(deftest a-literal-encodes-in-xsd-canonical-form
  (doseq [[s lex canonical] [[schema/DateTime "2026-01-01T00:00:00+00:00" "2026-01-01T00:00:00Z"]
                             [schema/DateTime "2026-01-01T00:00:00.250" "2026-01-01T00:00:00.25"]
                             [schema/Time "16:00:00.000-00:00" "16:00:00Z"]]]
    (is (= canonical (coerce/encode s (coerce/coerce s lex))) lex)))

(deftest an-offset-time-is-an-offset-time
  (is (= (OffsetTime/of 16 0 0 0 (ZoneOffset/of "-05:00"))
         (coerce/coerce schema/Time "16:00:00-05:00"))))

(def ^:private plan-json
  "A RatePlan whose version writes urpx:effectiveDate with `offset` (\"\" for
   none) and whose own urpx:initialEffectiveDate writes none."
  (fn [offset]
    (str "{\"@type\": \"urpx:RatePlan\", \"@id\": \"x:plan\",
           \"urpx:ratePlanUniqueIdentifier\": \"P1\", \"urpx:name\": \"Plan One\",
           \"urpx:timezoneIdentifier\": \"America/New_York\",
           \"urpx:initialEffectiveDate\": \"2026-01-01\",
           \"urpx:hasRatePlanVersion\": {
             \"@type\": \"urpx:RatePlanVersion\",
             \"urpx:changeReason\": {\"@id\": \"urpx:rateCase\"},
             \"urpx:forRatePlan\": {\"@id\": \"x:plan\"},
             \"urpx:hasCoverageClaim\": {\"@type\": \"urpx:CoverageClaim\",
               \"urpx:completenessResult\": {\"@id\": \"urpx:complete\"}},
             \"urpx:serviceRole\": {\"@id\": \"urpx:bundledServiceRole\"},
             \"urpx:effectiveDate\": \"2026-03-01" offset "\",
             \"urpx:hasPlanElements\": {\"@type\": \"urpx:PlanElements\",
               \"urpx:hasMetricInput\": {\"@type\": \"urpx:MetricInput\", \"urpx:name\": \"kWh\",
                 \"urpx:metricInputType\": {\"@id\": \"urpx:measuredMetricInput\"}},
               \"urpx:hasBillingModel\": {\"@type\": \"urpx:BillingModel\",
                 \"urpx:hasPriceDefinition\": []}},
             \"urpx:hasPrices\": {\"@type\": \"urpx:Prices\"}}}")))

(defn- version-of [plan] (first (index/as-vec (:urpx/hasRatePlanVersion plan))))

(deftest an-offset-date-is-the-day-as-written
  (doseq [lex ["2026-03-01-08:00" "2026-03-01+14:00" "2026-03-01Z"]]
    (is (= (LocalDate/of 2026 3 1) (coerce/coerce schema/Date lex)) lex))
  (testing "a typed-literal object too"
    (is (= (LocalDate/of 2026 3 1)
           (coerce/coerce schema/Date {:jsonld/value "2026-03-01-08:00"
                                       :jsonld/type "xsd:date"})))))

(deftest a-date-schema-accepts-only-a-local-date
  (is (coerce/validate schema/Date (LocalDate/of 2026 3 1)))
  (doseq [v [(ZonedDateTime/of 2026 3 1 0 0 0 0 (ZoneOffset/of "-08:00"))
             (ZonedDateTime/of 2026 3 1 0 0 0 0 la)
             (LocalDateTime/of 2026 3 1 0 0)]]
    (is (not (coerce/validate schema/Date v)) (str v))))

(deftest the-written-offset-is-kept-in-the-holding-maps-metadata
  (let [with    (coerce/coerce-rate-plan (parse-json (plan-json "-08:00")))
        without (coerce/coerce-rate-plan (parse-json (plan-json "")))]
    (is (coerce/validate schema/RatePlan with))
    (is (= (LocalDate/of 2026 3 1) (:urpx/effectiveDate (version-of with))))
    (is (= {:urpx/effectiveDate {:date (LocalDate/of 2026 3 1) :offset "-08:00"}}
           (:urpx.schema/date-offsets (meta (version-of with)))))
    (testing "a map whose dates carry no offset keeps none"
      (is (nil? (:urpx.schema/date-offsets (meta with))))
      (is (nil? (:urpx.schema/date-offsets (meta (version-of without))))))
    (testing "equality ignores the offset"
      (is (= with without)))))

(deftest an-offset-date-emits-exactly-as-written
  (doseq [offset ["-08:00" "+00:00" "Z" ""]]
    (let [plan    (coerce/coerce-rate-plan (parse-json (plan-json offset)))
          emitted (emit/write-rate-plan plan {:context nil})
          out     (json/read-str emitted)]
      (is (= (str "2026-03-01" offset)
             (get-in out ["urpx:hasRatePlanVersion" "urpx:effectiveDate"]))
          (str "offset " (pr-str offset)))
      (is (= "2026-01-01" (get out "urpx:initialEffectiveDate")))
      (testing "and survives a second round trip"
        (let [again (coerce/coerce-rate-plan (parse-json emitted))]
          (is (= plan again))
          (is (= (meta (version-of plan)) (meta (version-of again)))))))))

(deftest a-replaced-date-emits-without-the-old-offset
  (let [pkg     (coerce/coerce-urpx-package
                 (parse-json "{\"@type\": \"urpx:URPXPackage\",
                               \"urpx:targetURPXVersion\": \"0.5.1\",
                               \"urpx:dateCreated\": \"2026-03-01-08:00\"}"))
        written (fn [p] (get (json/read-str (emit/write-urpx-package p {:context nil}))
                             "urpx:dateCreated"))]
    (is (= "2027-01-01" (written (assoc pkg :urpx/dateCreated (LocalDate/of 2027 1 1)))))
    (testing "the same date assoc'd back keeps its offset"
      (is (= "2026-03-01-08:00"
             (written (assoc pkg :urpx/dateCreated (LocalDate/of 2026 3 1))))))))

(deftest a-package-date-emits-exactly-as-written
  (let [pkg (coerce/coerce-urpx-package
             (parse-json "{\"@type\": \"urpx:URPXPackage\",
                           \"urpx:targetURPXVersion\": \"0.5.1\",
                           \"urpx:dateCreated\": \"2026-03-01+05:30\"}"))]
    (is (= (LocalDate/of 2026 3 1) (:urpx/dateCreated pkg)))
    (is (= "2026-03-01+05:30"
           (get (json/read-str (emit/write-urpx-package pkg {:context nil}))
                "urpx:dateCreated")))))

(deftest an-offset-date-reads-as-midnight-in-the-plan-zone
  (let [ny   (ZoneId/of "America/New_York")
        plan (coerce/coerce-rate-plan (parse-json (plan-json "-08:00")))]
    (is (= (ZonedDateTime/of 2026 3 1 0 0 0 0 ny)
           (price/version-effective-at (version-of plan) ny)))))

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

(deftest coverage-reads-offset-bounds-in-the-plan-zone
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
    (is (= (la-at 2026 6 2 0 0) (:tick/end window))
        "an offset date's bound is midnight of that day in the plan zone")
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

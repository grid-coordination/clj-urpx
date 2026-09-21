(ns urpx.segments-test
  "Exact price segments: boundaries derived from the documents rather than
  discovered by sampling.

  The safety property is that the candidate instant set must be a SUPERSET of
  true transitions. An extra candidate merges away harmlessly; a missed one is
  a silently wrong boundary. These tests pin the superset's foundations rather
  than only its outputs, because an output test on this corpus would pass even
  if the rule were wrong: every TimeBracket in the corpus except one sits on an
  hour, so hourly sampling happens to agree."
  (:require [clojure.test :refer [deftest is testing]]
            [urpx.index :as index]
            [urpx.price :as price]
            [urpx.schedule :as schedule]
            [urpx.test-fixtures :as fixtures])
  (:import [java.time Duration Instant LocalDate LocalDateTime LocalTime
            ZoneId ZonedDateTime]))

(def ^:private la (ZoneId/of "America/Los_Angeles"))
(def ^:private rules (.getRules la))
(defn- zdt [y m d h mi] (ZonedDateTime/of y m d h mi 0 0 la))

(deftest resolution-reads-only-local-time-projections
  (testing "THE PREMISE THE WHOLE DESIGN RESTS ON. Candidate instants are
            derived on the argument that resolution is a pure function of the
            LOCAL date-time, so it can only change where a comparison against a
            document literal changes. That holds only while urpx.price reads
            the query instant through .toLocalDateTime, .toLocalDate,
            .toLocalTime and .getDayOfWeek and nothing else.

            A resolver that read .getOffset or .toInstant would make the
            resolved value depend on the instant line directly, and every
            boundary this namespace derives could then be wrong without a
            single test here failing. So the premise is asserted where it can
            be seen, not left as a comment."
    (let [src   (slurp "src/urpx/price.clj")
          calls (set (map second (re-seq #"\(\.([a-zA-Z]+)\s+(?:\^ZonedDateTime\s+)?zdt\)" src)))]
      (is (= #{"toLocalDateTime" "toLocalDate" "toLocalTime" "getDayOfWeek"} calls)
          (str "urpx.price now projects the query instant differently: " (pr-str calls)
               ". If a projection onto the instant line was added, the segment "
               "candidate rule in urpx.schedule is no longer a superset."))
      (is (not (re-find #"\.toInstant|\.getOffset|\.getZone" src))
          "price.clj must not read the instant, offset or zone"))))

(deftest dst-gap-is-clamped-not-pushed-forward
  (testing "2026-03-08T02:30 does not exist in Los Angeles. ZonedDateTime/of
            pushes it forward by the gap's width, landing 30 minutes PAST the
            discontinuity and leaving the preceding bracket applying past its
            wall-clock end. Clamping to the transition instant is correct, and
            the gap is not always an hour, so no constant shift would do."
    (let [ldt (LocalDateTime/of 2026 3 8 2 30)]
      (is (zero? (count (.getValidOffsets rules ldt))) "sanity: it really is a gap")
      (is (= [(Instant/parse "2026-03-08T10:00:00Z")]
             (#'schedule/local->instants rules ldt)))
      (is (= (Instant/parse "2026-03-08T10:30:00Z")
             (.toInstant (ZonedDateTime/of ldt la)))
          "the naive call lands past the discontinuity, which is why it is not used"))))

(deftest dst-overlap-yields-both-instants
  (testing "2026-11-01T01:30 happens twice. Resolution is a pure function of
            the local date-time, so the repeated hour replays the same local
            sequence on the instant line; emitting one instant would merge the
            repeat away and misprice the second pass."
    (let [ldt (LocalDateTime/of 2026 11 1 1 30)]
      (is (= 2 (count (.getValidOffsets rules ldt))) "sanity: it really is an overlap")
      (is (= [(Instant/parse "2026-11-01T08:30:00Z")
              (Instant/parse "2026-11-01T09:30:00Z")]
             (#'schedule/local->instants rules ldt)))
      (is (= 1 (count (.getValidOffsets rules (LocalDateTime/of 2026 7 1 1 30))))
          "and an ordinary local time still yields exactly one"))))

(deftest zone-transitions-are-iterated-not-assumed
  (testing "iterated from the rules rather than computed as two dates a year,
            because real tzdb data does not oblige: America/Metlakatla has a
            spring gap in 2018 with no matching fall-back and three transitions
            in 2019, one of them in January."
    (is (= [(Instant/parse "2026-03-08T10:00:00Z") (Instant/parse "2026-11-01T09:00:00Z")]
           (#'schedule/zone-transitions rules
                                        (.toInstant (zdt 2026 1 1 0 0))
                                        (.toInstant (zdt 2027 1 1 0 0)))))
    (testing "a zone that does not observe DST contributes none"
      (is (empty? (#'schedule/zone-transitions
                   (.getRules (ZoneId/of "America/Phoenix"))
                   (.toInstant (zdt 2026 1 1 0 0))
                   (.toInstant (zdt 2027 1 1 0 0))))))))

(deftest literals-are-harvested-by-type-not-by-property-name
  (testing "by type, so a temporal property URPX adds later is picked up without
            this file changing. A name list would have to be kept in step with
            the vocabulary, and the one it missed would be a missing candidate."
    (let [h (#'schedule/harvest-literals
             {:urpx/somethingNobodyHasNamedYet (LocalTime/of 3 45)
              :nested [{:deeper {:x (LocalDate/of 2026 5 1)}}]
              :inst (LocalDateTime/of 2026 6 1 7 30)
              :irrelevant "not a date"})]
      (is (= #{(LocalTime/of 3 45)} (:times h)))
      (is (= #{(LocalDate/of 2026 5 1)} (:dates h)))
      (is (= #{(LocalDateTime/of 2026 6 1 7 30)} (:date-times h)))))

  (testing "an UNCOERCED document harvests nothing, which would silently narrow
            the candidate set to midnights, so it is refused loudly instead"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"COERCED"
                          (#'schedule/harvest-literals {:urpx/startTime "16:00:00"})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"COERCED"
                          (#'schedule/harvest-literals {:urpx/startDate "2026-03-01"})))))

(deftest a-non-hour-boundary-is-representable
  (testing "the corpus contains one time literal that is not on an hour,
            23:59:59, in the NBT filings. An hour grid cannot place a boundary
            there at all, and a 15-minute grid would cost 288 samples a day and
            still miss it. Derived candidates place it exactly."
    (let [cands (#'schedule/candidate-instants
                 [{:urpx/startTime (LocalTime/of 23 59 59)}]
                 (.toInstant (zdt 2026 7 15 0 0))
                 (.toInstant (zdt 2026 7 16 0 0))
                 la)]
      (is (contains? (set cands) (.toInstant (ZonedDateTime/of 2026 7 15 23 59 59 0 la)))
          "the odd boundary is a candidate")
      (is (contains? (set cands) (.toInstant (zdt 2026 7 15 0 0)))
          "and midnight always is"))))

(deftest every-local-midnight-is-always-a-candidate
  (testing "the single decision that absorbs every date-granularity gate at
            once: day of week, holidays including observance shifting, month and
            season boundaries, applicability month rules, and every startDate,
            effectiveDate and expirationDate literal. No holiday parsing, no
            month arithmetic, no per-property list to keep in step."
    (let [cands (set (#'schedule/candidate-instants
                      [{}]                      ; a document with no literals at all
                      (.toInstant (zdt 2026 7 15 17 23))
                      (.toInstant (zdt 2026 7 18 17 23))
                      la))]
      (doseq [d [16 17 18]]
        (is (contains? cands (.toInstant (zdt 2026 7 d 0 0)))
            (str "midnight of the " d "th must be a candidate"))))))

(deftest segments-are-exact-and-tile-the-window
  (testing "a 72-hour horizon from an off-grid start"
    (let [plan  (fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")
          beg   (zdt 2026 7 15 17 23)
          start (.toInstant beg)
          end   (.plus start (Duration/ofHours 72))
          segs  (schedule/price-segments plan [] start end la)]
      (is (seq segs))
      (is (= beg (:tick/beginning (first segs))) "begins exactly at the window start")
      (is (= (.atZone end la) (:tick/end (last segs))) "ends exactly at the window end")
      (is (= (map :tick/beginning (rest segs)) (map :tick/end (butlast segs)))
          "tiles with no gap and no overlap")
      (is (every? #(.isBefore ^ZonedDateTime (:tick/beginning %) ^ZonedDateTime (:tick/end %)) segs)
          "no zero-length or inverted segment")

      (testing "boundaries land on the tariff's own transitions, 16:00 and 21:00"
        (let [interior (map #(.toLocalTime ^ZonedDateTime (:tick/beginning %)) (rest segs))]
          (is (seq interior))
          (is (every? #{(LocalTime/of 16 0) (LocalTime/of 21 0)} interior))))

      (testing "ADJACENT segments genuinely differ, or the merge is not working.
                They alternate off-peak and peak, so the whole sequence is not
                distinct; each neighbour pair must be."
        (let [rates (map #(price/marginal-unit-rate (:urpx.interval/resolved %)) segs)]
          (is (every? (fn [[a b]] (not= a b)) (partition 2 1 rates))))))))

(deftest a-refusal-becomes-a-segment-rather-than-aborting-the-window
  (testing "resolution can throw partway through a horizon, most plausibly
            :no-version-in-effect at an instant before the plan existed. That
            must not abort the whole window, and equal refusals must merge so an
            uncovered stretch is one segment rather than one per candidate."
    (let [a    (fixtures/load-rate-plan "sce-d-2025-11-15")
          b    (fixtures/load-rate-plan "sce-d-2026-03-20")
          bare (dissoc b :urpx/hasRatePlanVersion)
          opts {:versions-by-plan (index/versions-by-plan [a b])}
          segs (schedule/price-segments bare [] (.toInstant (zdt 2025 11 13 0 0))
                                        (.toInstant (zdt 2025 11 18 0 0)) la opts)]
      (is (seq segs))
      (is (some :urpx.interval/refused segs) "the uncovered stretch is reported")
      (is (some :urpx.interval/resolved segs) "and the covered stretch still resolves")
      (is (= :no-version-in-effect (:reason (:urpx.interval/refused (first segs)))))
      (testing "the refused stretch merged into ONE segment, not one per candidate"
        (is (= 1 (count (filter :urpx.interval/refused segs)))))
      (is (= (map :tick/beginning (rest segs)) (map :tick/end (butlast segs)))
          "and it still tiles"))))

(deftest the-candidate-count-is-bounded-and-small
  (testing "derived candidates are cheaper than hourly sampling on a real plan,
            though cost is not the argument for them: exactness is."
    (let [plan  (fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")
          start (.toInstant (zdt 2026 7 15 0 0))
          end   (.plus start (Duration/ofHours 72))
          cands (#'schedule/candidate-instants [plan] start end la)]
      (is (< (count cands) 72)
          (str "expected fewer than the 72 hourly samples, got " (count cands)))
      (is (pos? (count cands))))))

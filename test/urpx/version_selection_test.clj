(ns urpx.version-selection-test
  "Which version snapshot applies at an instant.

  A URPX filing holds a urpx:RatePlanVersion, and a plan accumulates several
  over time. Selection used to take whatever the back-pointer index held first,
  which is the same class of defect as picking one of eighteen mutually
  exclusive vintage prices: a defensible-looking number with no rule behind it.

  It stayed invisible because every filing carries exactly one version, so
  loading one file gives one answer. It stops being invisible the moment a
  caller loads several versions to cover a window, which is precisely what a
  forecast client must do.

  Two corpus facts shape the rule. All 84 version snapshots carry
  urpx:effectiveDateTime and NONE carries an expiration, so a version's span is
  inferable only from the next version's start; the rule therefore needs a
  plan's whole candidate set rather than one snapshot."
  (:require [clojure.test :refer [deftest is testing]]
            [urpx.index :as index]
            [urpx.price :as price]
            [urpx.test-fixtures :as fixtures])
  (:import [java.time ZonedDateTime ZoneId]))

(def ^:private la (ZoneId/of "America/Los_Angeles"))
(defn- at [y m d] (ZonedDateTime/of y m d 12 0 0 0 la))

(def ^:private older "sce-d-2025-11-15")
(def ^:private newer "sce-d-2026-03-20")

(defn- forecast-window
  "The shape a forecast client produces: several versions of one plan indexed
   together, and a plan node carrying no nested version of its own, so the
   index is the only source."
  []
  (let [a (fixtures/load-rate-plan older)
        b (fixtures/load-rate-plan newer)]
    {:plan (dissoc b :urpx/hasRatePlanVersion)
     :opts {:versions-by-plan (index/versions-by-plan [a b])}}))

(deftest the-version-in-effect-is-the-one-selected
  (testing "two real versions of SCE D, priced differently, selected by instant"
    (let [{:keys [plan opts]} (forecast-window)
          rate  (fn [z] (price/marginal-unit-rate (price/resolve-prices plan z opts)))
          alone (fn [f z] (price/marginal-unit-rate
                           (price/resolve-prices (fixtures/load-rate-plan f) z)))]

      (testing "sanity: the two versions really do price differently, or this
                test could not tell them apart"
        (is (not= (alone older (at 2026 1 15)) (alone newer (at 2026 7 15)))))

      (testing "after the newer takes effect, the newer applies"
        (is (= (alone newer (at 2026 7 15)) (rate (at 2026 7 15)))))

      (testing "between the two, the older still applies: no version carries an
                expiration, so it stays in force until its successor starts"
        (is (= (alone older (at 2026 1 15)) (rate (at 2026 1 15)))))

      (testing "the effective instant itself is inclusive"
        (is (= (alone older (at 2026 1 15))
               (price/marginal-unit-rate
                (price/resolve-prices plan (ZonedDateTime/of 2025 11 15 0 0 0 0 la) opts)))))

      (testing "the day before the newer starts is still the older"
        (is (= (alone older (at 2026 1 15))
               (price/marginal-unit-rate
                (price/resolve-prices plan (ZonedDateTime/of 2026 3 19 23 59 0 0 la) opts))))))))

(deftest an-instant-the-plan-does-not-cover-is-refused
  (testing "before any version took effect there is no answer. Falling back to
            the earliest would report a rate that was not in force, which is
            the failure this rule removes rather than a conservative default."
    (let [{:keys [plan opts]} (forecast-window)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"none of the 2 candidate snapshot"
                            (price/resolve-prices plan (at 2025 1 1) opts)))
      (is (= :no-version-in-effect
             (try (price/resolve-prices plan (at 2025 1 1) opts) nil
                  (catch clojure.lang.ExceptionInfo e (:reason (ex-data e)))))))))

(deftest a-single-version-still-has-to-cover-the-instant
  (testing "0.17.0 routed the single-candidate case PAST the effective and
            expiration tests, reasoning that one candidate is no choice to
            make. That confused `no choice` with `applies at every instant`,
            and every one of the 40 base filings carries exactly one version,
            so it was the branch standalone resolution always took: SCE D
            2026-03-20 answered 0.31085 five weeks before its version took
            effect, byte-identical to the in-window answer.

            The earlier version of this test asserted that behavior, which is
            how it survived. It now asserts the opposite."
    (let [plan (fixtures/load-rate-plan newer)]
      (testing "in its own window it prices"
        (is (some? (price/marginal-unit-rate (price/resolve-prices plan (at 2026 7 15))))))

      (testing "before its effective instant the PRICE refuses"
        (is (= :outside-version-window
               (try (price/marginal-unit-rate (price/resolve-prices plan (at 2026 1 15))) nil
                    (catch clojure.lang.ExceptionInfo e (:reason (ex-data e)))))))

      (testing "but the resolution still answers, carrying the mark, because a
                filing can legitimately be asked which season an instant falls
                in even when it does not price that instant"
        (let [r (price/resolve-prices plan (at 2026 1 15))]
          (is (some? (:urpx.resolved/outside-version-window r)))
          (is (some? (:urpx.resolved/season-name r)))
          (is (= (:jsonld/id (:urpx/hasRatePlanVersion plan))
                 (:version-id (:urpx.resolved/outside-version-window r))))))

      (testing "and inside the window there is no mark at all"
        (is (nil? (:urpx.resolved/outside-version-window
                   (price/resolve-prices plan (at 2026 7 15))))))))

  (testing "a single version stating NO effective instant covers everything.
            It cannot be ordered against anything and nothing says it has not
            started, so refusing it would reject a conformant document over a
            case the vocabulary permits."
    (let [plan (fixtures/load-rate-plan newer)
          undated (update plan :urpx/hasRatePlanVersion
                          dissoc :urpx/effectiveDateTime :urpx/effectiveDate)]
      (testing "no mark at an instant the DATED version would have refused"
        (is (some? (:urpx.resolved/outside-version-window
                    (price/resolve-prices plan (at 2026 1 15))))
            "sanity: dated, and this instant is outside its window")
        (is (nil? (:urpx.resolved/outside-version-window
                   (price/resolve-prices undated (at 2026 1 15))))
            "undated, so nothing says it has not started"))

      (testing "the version gate is the only thing lifted: a Price whose own
                startDate has not arrived is still excluded, so an undated
                version resolved before its prices exist still refuses, for
                that reason and not this one"
        (is (= :no-energy-ledger
               (try (price/marginal-unit-rate (price/resolve-prices undated (at 2020 1 1))) nil
                    (catch clojure.lang.ExceptionInfo e (:reason (ex-data e))))))))))

(deftest two-descriptions-of-one-version-are-ambiguous-not-arbitrary
  (testing "the corpus has two CPAU e-1 filings publishing the SAME version @id
            at the SAME effective instant with DIFFERENT content: the September
            filing adds two urpx:hasModifierOptions the July one lacks. Their
            directory dates differ while the version's effective date does not,
            so anything keying on the path would get it wrong too.

            Candidates are therefore deduplicated by VALUE rather than by @id.
            The nested snapshot and its own entry in the back-pointer index are
            the same node and collapse; two genuinely different descriptions do
            not, and tie, and are refused."
    (let [plan (fixtures/load-rate-plan newer)
          v    (:urpx/hasRatePlanVersion plan)
          ;; the same version described twice, differing only in a detail,
          ;; exactly the CPAU shape
          v2   (assoc v :urpx/hasModifierOptions [{:jsonld/id "x:extra"}])
          bare (dissoc plan :urpx/hasRatePlanVersion)
          opts {:versions-by-plan {"sce:d" [v v2]}}]
      (testing "identical descriptions collapse and resolve"
        (is (some? (price/resolve-prices bare (at 2026 7 15)
                                         {:versions-by-plan {"sce:d" [v v]}}))))
      (testing "differing ones tie and are refused rather than picked"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"share the latest effective instant"
                              (price/resolve-prices bare (at 2026 7 15) opts)))))))

(deftest an-unorderable-candidate-is-refused
  (testing "urpx:effectiveDateTime is sh:minCount 0, so a conformant version may
            state none. All 84 in the corpus do state one, but a candidate that
            does not cannot be ordered against the others, and guessing its
            place is how an arbitrary pick gets reintroduced."
    (let [plan (fixtures/load-rate-plan newer)
          v    (:urpx/hasRatePlanVersion plan)
          bare (dissoc plan :urpx/hasRatePlanVersion)
          opts {:versions-by-plan {"sce:d" [v (dissoc v :urpx/effectiveDateTime
                                                :urpx/effectiveDate)]}}]
      (is (= :version-without-effective-instant
             (try (price/resolve-prices bare (at 2026 7 15) opts) nil
                  (catch clojure.lang.ExceptionInfo e (:reason (ex-data e)))))))))

(deftest an-expected-end-does-not-retire-a-version
  (testing "the version shapes carry urpx:expectedEndDate as well as
            urpx:expirationDate, and they mean different things: an expected end
            anticipates a successor rather than terminating anything. A version
            past its expected end with no successor is still the one in force,
            so only the expiration is read. Treating them alike would silently
            drop a plan that outlived its own forecast."
    (let [plan (fixtures/load-rate-plan newer)
          v    (:urpx/hasRatePlanVersion plan)
          bare (dissoc plan :urpx/hasRatePlanVersion)
          when- (at 2026 7 15)
          with (fn [extra] {:versions-by-plan {"sce:d" [(merge v extra) (assoc v :jsonld/id "sce:d-other"
                                                                              :urpx/effectiveDateTime
                                                                              (java.time.LocalDateTime/of 2020 1 1 0 0))]}})]
      (testing "an expectedEndDate in the past does NOT retire it"
        (is (some? (price/resolve-prices
                    bare when-
                    (with {:urpx/expectedEndDate (java.time.LocalDate/of 2026 4 1)})))))
      (testing "an expirationDate in the past DOES, falling back to the older"
        (let [r (price/resolve-prices
                 bare when-
                 (with {:urpx/expirationDate (java.time.LocalDate/of 2026 4 1)}))]
          (is (some? r) "the older version is still in effect and is used"))))))

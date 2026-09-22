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
            [clojure.walk]
            [urpx.index :as index]
            [urpx.price :as price]
            [urpx.schedule :as schedule]
            [urpx.test-fixtures :as fixtures])
  (:import [java.time LocalDateTime ZonedDateTime ZoneId]))

(def ^:private la (ZoneId/of "America/Los_Angeles"))
(defn- at [y m d] (ZonedDateTime/of y m d 12 0 0 0 la))
(defn- zdt [y m d h] (ZonedDateTime/of y m d h 0 0 0 la))
(defn- ldt
  ([y m d h mi] (LocalDateTime/of y m d h mi))
  ([y m d h mi _s] (LocalDateTime/of y m d h mi)))

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

;; ---------------------------------------------------------------------------
;; The same question on the MODIFIER path
;;
;; version-covers? had exactly one call site, in resolve-prices, so a modifier
;; version's window was read when CHOOSING among several candidates and never
;; when there was only one. Every filing carries one, which is why it held.
;; ---------------------------------------------------------------------------

(defn- sce-d [] (fixtures/load-rate-plan "sce-d-2026-03-20"))
(defn- care [] (fixtures/load-modifier "sce-care-2026-03-20"))
(defn- fera [] (fixtures/load-modifier "sce-fera-2026-03-20"))
(defn- summer [] (zdt 2026 7 15 17))

(defn- bound-plan-version
  "The same end bound, on a base plan's own version node."
  [doc ^java.time.LocalDateTime ldt]
  (clojure.walk/postwalk
   (fn [n] (if (and (map? n) (= "urpx:RatePlanVersion" (:jsonld/type n)))
             (assoc n :urpx/expirationDateTime ldt)
             n))
   doc))

(defn- bound-version
  "Put an end bound on the modifier's version node. urpx:expirationDateTime is
   sh:minCount 0 / sh:maxCount 1 on RatePlanModifierVersionShape at v0.5.1, so
   this is a conformant document, not a contrived one."
  [doc ^java.time.LocalDateTime ldt]
  (clojure.walk/postwalk
   (fn [n] (if (and (map? n) (= "urpx:RatePlanModifierVersion" (:jsonld/type n)))
             (assoc n :urpx/expirationDateTime ldt)
             n))
   doc))

(defn- composed-reason [plan modifiers]
  (try (price/marginal-unit-rate (price/resolve-prices-with-modifiers plan modifiers (summer)))
       (catch clojure.lang.ExceptionInfo e (:reason (ex-data e)))))

(deftest a-superseded-modifier-no-longer-applies-silently
  (testing "a RatePlanModifierVersion bound before the query instant kept
            applying its prices. Composing SCE Schedule D with a CARE modifier
            expired on 2026-04-01 returned 0.19804875 at 2026-07-15, the full
            discount, three and a half months after the filing ceased to
            describe anything. The base plan has refused the symmetric case
            since 0.22.0, so the answer depended on which side of a composition
            the superseded filing sat.

            Marked rather than dropped, as the base plan is: dropping a
            discount silently raises the bill, and a caller may legitimately
            want to read tiers off a superseded filing."
    (is (= 0.19804875M (composed-reason (sce-d) [(care)]))
        "sanity: unbounded, the discount applies")
    (is (= :outside-version-window
           (composed-reason (sce-d) [(bound-version (care) (ldt 2026 4 1 0 0))]))
        "and a version that ended in April does not price July")))

(deftest the-modifier-window-is-half-open-like-the-base-plan
  (testing "[effective, expiration), matching version-expired-by?'s
            (not (.isAfter exp t)). An instant exactly at the bound is outside."
    (is (= :outside-version-window
           (composed-reason (sce-d) [(bound-version (care) (ldt 2026 7 15 16 59))]))
        "a minute before the instant: outside")
    (is (= :outside-version-window
           (composed-reason (sce-d) [(bound-version (care) (ldt 2026 7 15 17 0))]))
        "exactly at the instant: outside, because the interval is half-open")
    (is (= 0.19804875M
           (composed-reason (sce-d) [(bound-version (care) (ldt 2026 7 15 17 1))]))
        "a minute after: inside, and the discount applies")))

(deftest one-expired-modifier-refuses-a-composition-of-several
  (testing "the mark rides on the ledger rather than the result, because a
            composition can mix an expired modifier with live ones. One stale
            contributor is enough to refuse the aggregate."
    (is (= 0.14227575M (composed-reason (sce-d) [(care) (fera)]))
        "sanity: both live")
    (is (= :outside-version-window
           (composed-reason (sce-d) [(bound-version (care) (ldt 2026 4 1 0 0)) (fera)]))
        "CARE expired, FERA live: refused")))

(deftest a-modifier-before-its-effective-instant-is-outside-its-window-too
  (testing "the symmetric half, and the one the corpus exercises today. Every
            SCE modifier takes effect 2026-03-20; composing at 2026-01-15 was
            applying prices from a filing that would not exist for two months.

            No corpus version carries an end bound, so expiry is not yet
            reachable from real data; this case is, which is why it is pinned
            against the vendored fixture rather than a constructed bound."
    (let [r (price/resolve-prices-with-modifiers (sce-d) [(care)] (zdt 2026 1 15 17))
          marked (filter :urpx.resolved/outside-version-window (:urpx.resolved/ledgers r))]
      (is (seq marked) "the modifier's ledgers are marked as outside the window")
      (is (= "sce:care-2026-03-20"
             (:version-id (:urpx.resolved/outside-version-window (first marked)))))
      (is (= :outside-version-window
             (try (price/marginal-unit-rate r) nil
                  (catch clojure.lang.ExceptionInfo e (:reason (ex-data e)))))
          "and the rate refuses, naming the version rather than the selection"))))

(deftest reading-tiers-from-a-superseded-modifier-still-works
  (testing "marking, not dropping. resolve-modifier-prices keeps every ledger
            and every tier, and says on each that the version does not cover
            the instant, so a caller reading a superseded filing deliberately
            is not blocked."
    (let [expired (bound-version (care) (ldt 2026 4 1 0 0))
          r (price/resolve-modifier-prices expired (summer))
          ls (:urpx.resolved/ledgers r)]
      (is (= 3 (count ls)) "every ledger survives")
      (is (= 5 (count (mapcat :urpx.resolved/tiers ls))) "and every tier")
      (is (every? :urpx.resolved/outside-version-window ls)
          "each carrying the mark")
      (is (= (java.time.ZonedDateTime/of 2026 4 1 0 0 0 0
                                         (java.time.ZoneId/of "America/Los_Angeles"))
             (:expires (:urpx.resolved/outside-version-window (first ls))))
          "which reports the bound that was crossed, ZONED. The document writes
           it as a naive wall clock; it names a moment only once anchored in the
           zone the plan declares, and handing back the naive form invited a
           consumer to compare it against an instant in some other frame"))))

(deftest a-superseded-modifier-that-contributes-nothing-refuses-nothing
  (testing "scoped to ledgers that actually reach the sum, like every other
            guard in marginal-unit-rate. SMUD's CPP modifier carries one energy
            price and it is event-driven, so a non-event marginal excludes it;
            an expired version of that modifier withholds nothing from the
            answer and must not refuse it.

            The same document DOES refuse with :include-dynamic-pricing? true,
            because that is precisely when its price would enter the sum. One
            fixture, two opposite outcomes, decided by whether the stale price
            is actually summed rather than by whether it exists."
    (let [plan (fixtures/load-rate-plan "smud-tod-5-8pm-2026-01-01")
          cpp  (fixtures/load-modifier "smud-cpp-2026-01-01")
          expired (bound-version cpp (ldt 2026 4 1 0 0))
          rate (fn [ms opts]
                 (try (price/marginal-unit-rate
                       (price/resolve-prices-with-modifiers plan ms (summer)) opts)
                      (catch clojure.lang.ExceptionInfo e (:reason (ex-data e)))))]
      (is (= 0.3765M (rate [cpp] {})) "sanity: live, the event charge is excluded")
      (is (= 0.3765M (rate [expired] {}))
          "expired and contributing nothing: the baseline still answers")
      (is (= :outside-version-window (rate [expired] {:include-dynamic-pricing? true}))
          "expired and now contributing: refused")
      (is (seq (filter :urpx.resolved/outside-version-window
                       (:urpx.resolved/ledgers
                        (price/resolve-prices-with-modifiers plan [expired] (summer)))))
          "and the mark is present either way, so a consumer can always see it"))))

(deftest the-ledger-mark-carries-nothing-that-varies-per-instant
  (testing "urpx.schedule merges adjacent steps by comparing
            :urpx.resolved/ledgers BY VALUE, so any field on a ledger that
            changes every step makes every step distinct and nothing merges.

            The first version of this mark carried :at, the instant resolved.
            An hourly month over a composition with an out-of-window modifier
            returned 744 intervals where 0.26.0 returns 1, and price-segments
            returned 31 where it returns 1, with every resolved value identical
            once :at was stripped. No price differed; the schedule was simply
            shattered into one interval per step.

            Neither mark carries a per-instant field now. The result-level one
            did until this change, which same-ledgers? never saw but
            price-segments did; both are one shape, pinned by
            both-version-window-marks-have-the-same-shape. :version-id,
            :effective and :expires identify the window, and the instant the
            caller asked about is the caller's own."
    (let [base (fixtures/load-rate-plan "sce-d-2025-11-15")
          care (fixtures/load-modifier "sce-care-2026-03-20")
          la   (java.time.ZoneId/of "America/Los_Angeles")
          from (.toInstant (.atStartOfDay (java.time.LocalDate/of 2026 1 1) la))
          to   (.toInstant (.atStartOfDay (java.time.LocalDate/of 2026 2 1) la))
          ivs  (schedule/price-schedule-with-modifiers base [care] from to la)
          segs (schedule/price-segments base [care] from to la)]
      (is (seq (filter :urpx.resolved/outside-version-window
                       (:urpx.resolved/ledgers
                        (price/resolve-prices-with-modifiers base [care] (zdt 2026 1 15 17)))))
          "sanity: the modifier really is out of window across this window,
           so the merge path is actually exercised")
      (is (= 1 (count ivs))
          "a whole month of an unchanging composition is ONE interval")
      (is (= 1 (count segs))
          "and one segment")
      (is (every? nil? (map :at (keep :urpx.resolved/outside-version-window
                                      (mapcat :urpx.resolved/ledgers
                                              (map :urpx.interval/resolved segs)))))
          "because the mark carries no per-instant field"))))

(deftest the-refusal-ex-data-carries-what-its-message-says
  (testing "a consumer classifying on ex-data should not be worse off than one
            reading the prose. The modifier-sourced refusal names the ledger
            and the bound that was crossed. Neither form names an instant: both
            marks carry only the window now, so the ledger clause is the whole
            difference between them."
    (let [modifier-data
          (try (price/marginal-unit-rate
                (price/resolve-prices-with-modifiers
                 (sce-d) [(bound-version (care) (ldt 2026 4 1 0 0))] (summer)))
               nil
               (catch clojure.lang.ExceptionInfo e (ex-data e)))]
      (is (= :outside-version-window (:reason modifier-data)))
      (is (= "sce:care-2026-03-20" (:version-id modifier-data)))
      (is (= (java.time.ZonedDateTime/of 2026 4 1 0 0 0 0
                                         (java.time.ZoneId/of "America/Los_Angeles"))
             (:expires modifier-data))
          "the bound that was crossed, so a caller can see WHY without the
           filing, and ZONED so the comparison it invites is between moments")
      (is (some? (:ledger-id modifier-data))
          "and which ledger carried it, since a composition can have many")
      (is (nil? (:at modifier-data))
          "no instant, because neither mark carries a per-instant field"))))

(deftest both-version-window-marks-have-the-same-shape
  (testing "the result-level mark on the base plan and the ledger-level mark on
            a modifier carry the same fields and neither carries the instant.

            The result-level one carried :at from 0.22.0 until now. That never
            reached same-ledgers?, so price-schedule was fine, but price-segments
            merges on the WHOLE resolved value: a base filing queried across a
            window it does not cover returned 31 segments over a month where it
            returns 1, each holding identical prices. Two marks with two shapes
            was also two chances to reintroduce the same defect, which is why
            they are now one shape and one rule."
    (let [base-mark (-> (price/resolve-prices
                         (bound-plan-version (sce-d) (ldt 2026 4 1 0 0)) (summer))
                        :urpx.resolved/outside-version-window)
          led-mark  (->> (:urpx.resolved/ledgers
                          (price/resolve-prices-with-modifiers
                           (sce-d) [(bound-version (care) (ldt 2026 4 1 0 0))] (summer)))
                         (keep :urpx.resolved/outside-version-window)
                         first)]
      (is (some? base-mark) "sanity: the base plan really is outside its window")
      (is (some? led-mark)  "sanity: and the modifier really is too")
      (is (= (set (keys base-mark)) (set (keys led-mark)))
          "same keys on both")
      (is (= #{:version-id :effective :expires} (set (keys base-mark))))
      (is (not-any? #{:at} (keys base-mark)) "and no per-instant field"))))

(deftest a-base-filing-outside-its-window-does-not-shatter-its-segments
  (testing "price-segments merges on the full resolved value, so the mark it
            carries must not vary per instant. Measured before this change: 31
            segments across January for a filing effective 2026-03-20, each one
            holding the same prices."
    (let [la   (java.time.ZoneId/of "America/Los_Angeles")
          from (.toInstant (.atStartOfDay (java.time.LocalDate/of 2026 1 1) la))
          to   (.toInstant (.atStartOfDay (java.time.LocalDate/of 2026 2 1) la))
          segs (schedule/price-segments (sce-d) [] from to la)]
      (is (some? (:urpx.resolved/outside-version-window
                  (price/resolve-prices (sce-d) (zdt 2026 1 15 17))))
          "sanity: this filing really is outside its window in January")
      (is (= 1 (count segs))
          "one unchanging month is one segment"))))

(deftest the-refusal-message-says-what-was-crossed-and-which-ledger
  (testing "the message is prose a human reads when a sweep stops, and it was
            the newest and least-tested code in this change: three separate
            message-only mutations survived the whole suite. ex-data assertions
            do not cover it, because nothing in the repo called ex-message on
            this refusal at all. Neither path names an instant now: both marks
            carry only the window, so both messages say the instant resolved
            rather than naming it, and the ledger clause is the difference.

            What the message has to carry: the version, the bound that was
            actually crossed, and on a composition WHICH ledgers are stale,
            since a caller may have passed several modifiers."
    (let [msg (fn [ms]
                (try (price/marginal-unit-rate
                      (price/resolve-prices-with-modifiers (sce-d) ms (summer)))
                     nil
                     (catch clojure.lang.ExceptionInfo e (ex-message e))))
          m (msg [(bound-version (care) (ldt 2026 4 1 0 0))])]
      (is (string? m) "it refuses at all")
      (is (re-find #"\"sce:care-2026-03-20\"" m)
          "names the version")
      (is (re-find #"\"sce:care-2026-03-20-ledger-energy-credits\"" m)
          "and which ledgers carried it, so a composition of several is diagnosable")
      (is (re-find #"\"sce:care-2026-03-20-ledger-bill-discount\"" m)
          "ALL of them: naming only the first costs the caller one refusal
           round per stale ledger to discover a set the library already had")
      (is (re-find #"expires 2026-04-01T00:00" m)
          "and the bound that was crossed, which is the whole reason it refused")
      (is (re-find #"takes effect 2026-03-20T00:00" m)
          "and the other end of the window")
      (is (not (re-find #"does not cover ;" m))
          "no dangling clause where an instant used to be")
      (is (re-find #":versions-by-plan" m)
          "and names an action"))))

(deftest the-base-plan-refusal-message-is-well-formed-too
  (testing "the same message serves both marks now that they are one shape, so
            the base path must read correctly without the modifier clause."
    (let [m (try (price/marginal-unit-rate
                  (price/resolve-prices
                   (bound-plan-version (sce-d) (ldt 2026 4 1 0 0)) (summer)))
                 nil
                 (catch clojure.lang.ExceptionInfo e (ex-message e)))]
      (is (string? m))
      (is (re-find #"\"sce:d-2026-03-20\"" m) "names the version")
      (is (not (re-find #"modifier ledger" m))
          "and does NOT claim a modifier ledger, because there is none")
      (is (re-find #"expires 2026-04-01T00:00" m) "names the bound crossed")
      (is (not (re-find #"does not cover ;" m)) "no dangling clause"))))

(deftest price-schedule-splits-at-a-version-boundary
  (testing "price-schedule merges adjacent steps on :urpx.resolved/ledgers, but
            a base plan's version-window mark sits on the RESULT, so a boundary
            that changes nothing else was invisible and the whole span collapsed
            into one interval carrying whichever side the first step fell on.

            Reachable on an unmodified vendored fixture with no mutation at all:
            sce-d-2026-03-20 takes effect on the 20th, so a March window
            straddles it. price-schedule returned ONE interval carrying the
            not-yet-effective mark and marginal-unit-rate refused for the whole
            month, including the twelve days the filing does cover, while
            price-segments split correctly and a direct resolve at 2026-03-25
            returned 0.31085. Two APIs disagreeing about one instant, with the
            schedule giving the wrong answer.

            An expiring version fails the other way: the covered side's RATE is
            kept for the span after expiry, which is a wrong number rather than
            a wrong refusal."
    (let [la   (java.time.ZoneId/of "America/Los_Angeles")
          from (.toInstant (.atStartOfDay (java.time.LocalDate/of 2026 3 1) la))
          to   (.toInstant (.atStartOfDay (java.time.LocalDate/of 2026 4 1) la))
          rate (fn [r] (try (price/marginal-unit-rate r)
                            (catch clojure.lang.ExceptionInfo e (:reason (ex-data e)))))
          ivs  (schedule/price-schedule (sce-d) from to la)
          segs (schedule/price-segments (sce-d) [] from to la)]
      (is (= 2 (count ivs))
          "the window splits at the effective instant, not collapsing to one")
      (is (= [:outside-version-window 0.31085M]
             (mapv #(rate (:urpx.interval/resolved %)) ivs))
          "refusing before it takes effect, answering after")
      (is (= (mapv #(rate (:urpx.interval/resolved %)) segs)
             (mapv #(rate (:urpx.interval/resolved %)) ivs))
          "and price-schedule agrees with price-segments about the same window,
           which is the invariant that was broken")
      (is (= 0.31085M (price/marginal-unit-rate
                       (price/resolve-prices (sce-d) (zdt 2026 3 25 12))))
          "matching a direct resolve inside the covered part"))))

(deftest an-expiring-version-does-not-carry-its-rate-past-the-bound
  (testing "the wrong-number direction of the same defect."
    (let [la   (java.time.ZoneId/of "America/Los_Angeles")
          from (.toInstant (.atStartOfDay (java.time.LocalDate/of 2026 7 1) la))
          to   (.toInstant (.atStartOfDay (java.time.LocalDate/of 2026 8 1) la))
          plan (bound-plan-version (sce-d) (ldt 2026 7 15 12 0))
          rate (fn [r] (try (price/marginal-unit-rate r)
                            (catch clojure.lang.ExceptionInfo e (:reason (ex-data e)))))
          ivs  (schedule/price-schedule plan from to la)]
      (is (= 2 (count ivs)))
      (is (= [0.31085M :outside-version-window]
             (mapv #(rate (:urpx.interval/resolved %)) ivs))
          "the rate stops at the bound rather than running to the end of the window"))))

(deftest several-stale-modifiers-are-all-named-and-grouped-by-version
  (testing "naming only the first costs the caller one refusal round per stale
            modifier to discover a set the library already had. And with two
            versions involved there is no single 'the version used' to name, so
            the message groups ledgers under the version that made each stale."
    (let [expire #(bound-version % (ldt 2026 4 1 0 0))
          both   [(expire (care)) (expire (fera))]
          e      (try (price/marginal-unit-rate
                       (price/resolve-prices-with-modifiers (sce-d) both (summer)))
                      nil
                      (catch clojure.lang.ExceptionInfo ex ex))
          m      (ex-message e)
          d      (ex-data e)]
      (is (= 3 (count (:stale d)))
          "every stale ledger is in ex-data, not just the one the message leads with")
      (is (re-find #"\"sce:care-2026-03-20\", which" m) "care's version named")
      (is (re-find #"\"sce:fera-2026-03-20\", which" m) "fera's too")
      (is (re-find #"2 version\(s\)" m) "and the count of versions involved")
      (is (not (re-find #"the version used" m))
          "no claim of a single version when several are stale"))))

;; ---------------------------------------------------------------------------
;; :version is scoped to the document it is a snapshot of
;; ---------------------------------------------------------------------------

(deftest a-base-plan-version-opt-does-not-drop-the-modifiers
  (testing "plan-version short-circuited on (:version opts) before anything
            looked at WHICH document was being resolved, and
            resolve-prices-with-modifiers threads one opts map to the base plan
            and to every modifier. So the base plan's snapshot was handed to
            each modifier as its version; that node carries no
            urpx:applicableToRatePlan, modifier-applies-to? therefore said no,
            and every modifier was dropped with no mark and no refusal.

            SCE Schedule D composed with CARE returned 0.31085 instead of
            0.19804875, the full undiscounted rate, 57% high, on a call the
            library's own refusals tell callers to make: :ambiguous-version and
            :version-without-effective-instant both end 'Pass :version to
            choose explicitly'.

            A version now applies only to the document it is a snapshot of, and
            is ignored elsewhere. Ignoring is not the silent failure it
            replaces: the other document resolves by its own rules, which is
            correct, where before it was discarded entirely."
    (let [plan (sce-d)
          care (care)
          pv   (:urpx/hasRatePlanVersion plan)
          mv   (:urpx/hasRatePlanModifierVersion care)
          rate (fn [opts] (price/marginal-unit-rate
                           (price/resolve-prices-with-modifiers plan [care] (summer) opts)))
          ledgers (fn [opts] (count (:urpx.resolved/ledgers
                                     (price/resolve-prices-with-modifiers
                                      plan [care] (summer) opts))))]
      (is (= 0.19804875M (rate nil)) "sanity: the discount applies with no opts")
      (is (= 8 (ledgers nil)))
      (is (= 0.19804875M (rate {:version pv}))
          "pinning the BASE version leaves the modifier alone")
      (is (= 8 (ledgers {:version pv}))
          "all eight ledgers survive; the defect left five")
      (is (= 0.19804875M (rate {:version mv}))
          "and pinning the MODIFIER version leaves the base plan alone"))))

(deftest a-version-opt-can-be-scoped-by-document-id
  (testing "the explicit form, like :versions-by-plan. A document the map does
            not name resolves normally rather than being pinned to someone
            else's snapshot."
    (let [plan (sce-d)
          care (care)
          scoped {(:jsonld/id plan) (:urpx/hasRatePlanVersion plan)
                  (:jsonld/id care) (:urpx/hasRatePlanModifierVersion care)}]
      (is (= 0.19804875M
             (price/marginal-unit-rate
              (price/resolve-prices-with-modifiers plan [care] (summer) {:version scoped}))))
      (is (= 0.19804875M
             (price/marginal-unit-rate
              (price/resolve-prices-with-modifiers
               plan [care] (summer)
               {:version {(:jsonld/id plan) (:urpx/hasRatePlanVersion plan)}})))
          "naming only the base plan leaves the modifier to resolve itself")
      (testing "and the map form is load-bearing: on a document that cannot
                choose for itself, only the map can settle it, so a caller who
                needs the scoped form has no other way through. Exercised on
                resolve-prices rather than a composition, because a composition
                also calls holidays/derive-predicate, which reads the nested
                version independently and does not honor :version at all."
        (let [pv  (:urpx/hasRatePlanVersion plan)
              two (assoc plan :urpx/hasRatePlanVersion
                         [pv (assoc pv :jsonld/id "x:other")])]
          (is (thrown? clojure.lang.ExceptionInfo (price/resolve-prices two (summer)))
              "sanity: two nested snapshots refuse without help")
          (is (= 0.31085M
                 (price/marginal-unit-rate
                  (price/resolve-prices two (summer) {:version {(:jsonld/id two) pv}})))
              "and the scoped map settles it, where the bare form would too but
               a caller composing several documents needs the scoped one"))))))

(deftest a-version-opt-that-is-not-a-version-is-refused
  (testing "passing something that is neither a snapshot nor a map from @id to
            one has no document it could apply to, so reading on would silently
            ignore what the caller asked for."
    (is (= :version-opt-not-a-version
           (try (price/resolve-prices (sce-d) (summer) {:version (sce-d)}) nil
                (catch clojure.lang.ExceptionInfo e (:reason (ex-data e)))))
        "a RatePlan is not a RatePlanVersion")))

(deftest a-version-opt-still-resolves-the-ambiguity-it-exists-for
  (testing "the case :version was added for: a document nesting two snapshots
            refuses :ambiguous-version or the multiple-nested refusal, and
            passing one explicitly settles it. version-belongs-to? must not
            call nested-version unguarded, because that is the very lookup that
            throws here."
    (let [plan (sce-d)
          pv   (:urpx/hasRatePlanVersion plan)
          two  (assoc plan :urpx/hasRatePlanVersion [pv (assoc pv :jsonld/id "x:other")])]
      (is (thrown? clojure.lang.ExceptionInfo (price/resolve-prices two (summer)))
          "sanity: two nested snapshots refuse without help")
      (is (= 0.31085M (price/marginal-unit-rate
                       (price/resolve-prices two (summer) {:version pv})))
          "and an explicit snapshot settles it")
      (testing "including when the snapshot carries NO back pointer, which is
                the branch that has to fall back to nesting. v0.5.1 makes
                urpx:forRatePlan sh:minCount 1, but a v0.2.x snapshot has none,
                and that fallback is the one lookup that throws on a document
                nesting several. Unguarded it would refuse exactly the call
                :version exists to rescue."
        (let [bare (dissoc pv :urpx/forRatePlan)
              two' (assoc plan :urpx/hasRatePlanVersion
                          [bare (assoc bare :jsonld/id "x:other")])]
          (is (nil? (:urpx/forRatePlan bare)) "sanity: no back pointer")
          (is (= 0.31085M (price/marginal-unit-rate
                           (price/resolve-prices two' (summer) {:version bare})))
              "settled through the nesting fallback rather than the pointer"))))))

(deftest the-holiday-calendar-comes-from-the-resolved-version-on-both-paths
  (testing "urpx.holidays/derive-predicate has a 2-arity taking the resolved
            snapshot, and resolve-prices has passed it since v0.5.1 un-nesting.
            resolve-prices-with-modifiers called the 1-arity, which re-derives
            the snapshot by reading the nested one. That ignores a caller's
            :version and REFUSES outright on a document nesting several, which
            is exactly the document :version exists to settle: the composed
            call threw from the holiday path after :version had already chosen,
            while the same call through resolve-prices succeeded.

            The snapshot is in hand at that call site. Re-deriving it was the
            whole defect.

            The THROW is the only observable effect I could demonstrate. The
            predicate built there does not set the composed result's season or
            TOU: that comes from the nested resolve-prices call, which derives
            its own. It is handed only to modifier-ledger-results, and no
            fixture or constructed document produced a different tier count
            under the real calendar, (constantly true) or (constantly false).
            A test asserting TOU on the composed result was written first and
            deleted, because it passed with the predicate replaced by
            (constantly false) — it was measuring the other one."
    (let [plan (sce-d)
          pv   (:urpx/hasRatePlanVersion plan)
          two  (assoc plan :urpx/hasRatePlanVersion [pv (assoc pv :jsonld/id "x:other")])]
      (is (thrown? clojure.lang.ExceptionInfo
                   (price/resolve-prices-with-modifiers two [(care)] (summer)))
          "sanity: without :version the document still cannot choose")
      (is (= 0.31085M (price/marginal-unit-rate
                       (price/resolve-prices two (summer) {:version pv})))
          "resolve-prices settled it before this fix and still does")
      (is (= 0.19804875M
             (price/marginal-unit-rate
              (price/resolve-prices-with-modifiers two [(care)] (summer) {:version pv})))
          "and the composed path now settles it too, rather than throwing from
           the holiday lookup"))))

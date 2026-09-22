(ns urpx.coverage-test
  "Reading what a version snapshot claims about its own coverage.

  Most cases are constructed. The production corpus exercises exactly one
  configuration of this vocabulary: every claim states completenessResult and
  selfContained and nothing else, no claim carries a TemporalScope, and
  confidence, stale and fallbackSourced are absent everywhere. Testing only what
  the corpus happens to contain would leave every guard here unexercised, which
  is the point urpx.pd-alternatives-test already makes: the slot has to work
  before a filing can be written against it."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [urpx.arrangements :as arr]
            [tick.alpha.interval :as ti]
            [urpx.coverage :as cov]
            [urpx.test-fixtures :as fixtures]))

(defn- version
  ([claim] (version claim nil))
  ([claim extra]
   (merge {:jsonld/id "x:p-v1" :jsonld/type "urpx:RatePlanVersion"}
          (when claim {:urpx/hasCoverageClaim
                       (merge {:jsonld/type "urpx:CoverageClaim"} claim)})
          extra)))

(defn- verdict [c] (:urpx.coverage/completeness (cov/of-version c)))

(deftest the-verdict-is-read-as-the-document-writes-it
  (testing "the IRI string rather than a keyword of this library's invention: it
            maps 1:1 to what the document says and needs no translation table to
            compare against one."
    (is (= "urpx:partial"
           (verdict (version {:urpx/completenessResult {:jsonld/id "urpx:partial"}}))))
    (doseq [m cov/completeness-results]
      (is (= m (verdict (version {:urpx/completenessResult {:jsonld/id m}})))
          (str m " is read back unchanged")))))

(deftest absent-is-not-unknown
  (testing "urpx:unknown is itself a VERDICT, meaning no evaluation has run. A
            document that asserts it has said something a document omitting the
            claim has not, so conflating them would invent a claim on the
            publisher's behalf.

            A missing claim is not refused either: hasCoverageClaim is optional
            in this library's schema because requiring it would reject every
            v0.2.x document, and v0.2.x acceptance is a contract."
    (let [none (cov/of-version (version nil))
          unk  (cov/of-version (version {:urpx/completenessResult {:jsonld/id "urpx:unknown"}}))]
      (is (false? (:urpx.coverage/stated? none)))
      (is (nil? (:urpx.coverage/completeness none)))
      (is (true? (:urpx.coverage/stated? unk)))
      (is (= "urpx:unknown" (:urpx.coverage/completeness unk)))
      (is (not= (:urpx.coverage/completeness none) (:urpx.coverage/completeness unk))))))

(deftest an-unknown-member-refuses
  (testing "reporting nil would read as 'not complete', which is a guess about a
            verdict the document did state. A future URPX adding a seventh
            member must surface as a refusal rather than as silence."
    (let [e (is (thrown? clojure.lang.ExceptionInfo
                         (verdict (version {:urpx/completenessResult
                                            {:jsonld/id "urpx:mostlyComplete"}}))))]
      (is (= :urpx.coverage/unknown-completeness-result (:reason (ex-data e))))
      (is (= "urpx:mostlyComplete" (:completeness-result (ex-data e)))))))

(deftest self-contained-disagreement-is-reported-not-refused
  (testing "the ontology SETTLES this one: selfContained 'may be materialized on
            the version itself for direct query; when both are present they MUST
            agree, and the claim is authoritative'. The answer is determined, so
            refusing would ignore a stated rule. It is still a violated MUST,
            which is urpx.validate's business rather than this reader's."
    (let [r (cov/of-version (version {:urpx/completenessResult {:jsonld/id "urpx:partial"}
                                      :urpx/selfContained true}
                                     {:urpx/selfContained false}))]
      (is (true? (:urpx.coverage/self-contained r)) "the claim wins")
      (is (= :claim (:urpx.coverage/self-contained-source r)))
      (is (true? (:urpx.coverage/self-contained-disagrees? r)) "and the conflict is visible"))))

(deftest self-contained-contradicting-its-own-derivation-refuses
  (testing "a different case, where nothing states precedence. selfContained is
            DERIVED as 'false iff any PriceDefinitionReference is
            referenceInclusion retrievedElsewhere', so a claim asserting true
            alongside such a reference contradicts its own definition and no rule
            says whether the assertion or the derivation wins.

            Cannot fire on real data: there are zero PriceDefinitionReference
            nodes in the corpus or the fixtures, which is exactly why the guard
            needs constructing."
    (let [e (is (thrown? clojure.lang.ExceptionInfo
                         (cov/of-version
                          (version {:urpx/completenessResult {:jsonld/id "urpx:complete"}
                                    :urpx/selfContained true}
                                   {:urpx/hasPriceDefinitionReference
                                    [{:jsonld/type "urpx:PriceDefinitionReference"
                                      :urpx/referenceInclusion {:jsonld/id "urpx:retrievedElsewhere"}}]}))))]
      (is (= :urpx.coverage/self-contained-contradicts-references (:reason (ex-data e))))
      (is (= 1 (:retrieved-elsewhere (ex-data e)))))))

(deftest a-scope-is-found-by-its-kind-and-several-coverage-scopes-refuse
  (testing "urpx:hasTemporalScope is unconstrained upstream: sh:path zero times
            in the shapes graph, sh:ignoredProperties 79 times. So a claim may
            carry any number of scopes of any kind. Only urpx:coverage answers
            what window the verdict covers; two of them is a question nothing in
            the documents settles."
    (let [scope (fn [kind] {:jsonld/type "urpx:TemporalScope"
                            :urpx/scopeKind {:jsonld/id (str "urpx:" kind)}})
          with  (fn [scopes] (version {:urpx/completenessResult {:jsonld/id "urpx:partial"}
                                       :urpx/hasTemporalScope scopes}))]
      (let [r (cov/of-version (with [(scope "publication") (scope "coverage")]))]
        (is (true? (:urpx.coverage/scope-stated? r)))
        (is (= "urpx:coverage" (get-in (:urpx.coverage/coverage-window r)
                                       [:urpx/scopeKind :jsonld/id]))
            "the publication scope is not mistaken for a coverage one"))
      (let [r (cov/of-version (with [(scope "publication") (scope "observation")]))]
        (is (false? (:urpx.coverage/scope-stated? r)) "no coverage scope is no window"))
      (let [e (is (thrown? clojure.lang.ExceptionInfo
                           (cov/of-version (with [(scope "coverage") (scope "coverage")]))))]
        (is (= :urpx.coverage/ambiguous-coverage-scope (:reason (ex-data e))))))))

(deftest a-scope-with-no-bounds-still-states-no-window
  (testing "urpx:scopeKind is the only required field on a TemporalScope, so a
            conformant scope may name its role and give no dates at all. A
            caller deciding whether a verdict covers a horizon must read the
            window rather than trusting scope-stated?, which is why the raw node
            is returned instead of a boolean."
    (let [r (cov/of-version (version {:urpx/completenessResult {:jsonld/id "urpx:partial"}
                                      :urpx/hasTemporalScope
                                      [{:jsonld/type "urpx:TemporalScope"
                                        :urpx/scopeKind {:jsonld/id "urpx:coverage"}}]}))]
      (is (true? (:urpx.coverage/scope-stated? r)))
      (is (every? nil? ((juxt :urpx/startDate :urpx/endDate
                              :urpx/startDateTime :urpx/endDateTime)
                        (:urpx.coverage/coverage-window r)))
          "stated, and bounding nothing"))))

(deftest an-unfollowable-rationale-is-flagged-and-an-unfollowable-verdict-refuses
  (testing "the line between the two. An explainedBy reference that goes nowhere
            leaves the VERDICT perfectly readable, so it is reported rather than
            thrown. A claim that is itself an unfollowable reference means the
            document says something this reader cannot reach, which is not the
            same as saying nothing, so it refuses."
    (let [r (cov/of-version (version {:urpx/completenessResult {:jsonld/id "urpx:partial"}
                                      :urpx/explainedBy [{:jsonld/id "x:missing"}]}))]
      (is (= "urpx:partial" (:urpx.coverage/completeness r)) "verdict still read")
      (is (= [{:unresolved-ref "x:missing"}] (:urpx.coverage/evaluations r))))
    (let [e (is (thrown? clojure.lang.ExceptionInfo
                         (cov/of-version {:jsonld/id "x:p-v1" :jsonld/type "urpx:RatePlanVersion"
                                          :urpx/hasCoverageClaim {:jsonld/id "x:cc-1"}})))]
      (is (= :urpx.coverage/claim-ref-unresolved (:reason (ex-data e))))
      (is (false? (:document-supplied? (ex-data e)))))))

(deftest nothing-is-fabricated-on-real-filings
  (testing "every field the corpus does not populate must come back nil rather
            than defaulted. Measured over the vendored fixtures, and the guard
            matters because a default here would look exactly like a filing that
            said something."
    (let [r (cov/of-version (:urpx/hasRatePlanVersion
                             (fixtures/load-rate-plan "sce-d-2026-03-20")))]
      (is (true? (:urpx.coverage/stated? r)))
      (is (contains? cov/completeness-results (:urpx.coverage/completeness r)))
      (is (every? nil? ((juxt :urpx.coverage/confidence :urpx.coverage/stale
                              :urpx.coverage/fallback-sourced
                              :urpx.coverage/coverage-window) r))
          "confidence, stale, fallbackSourced and any coverage window are absent
           on every filing in both trees"))))

(defn- transitive-requires
  "Every urpx.* namespace `root` reaches, read from the ns forms on disk.

   From disk rather than at runtime because ns-refers sees :refer only and
   ns-aliases sees :as only, so a bare (:require [urpx.coverage]) would pass
   both, and because by the time this suite runs everything is loaded anyway."
  [root]
  (let [file-of #(io/file (str "src/" (str/replace (str/replace (name %) "." "/") "-" "_") ".clj"))
        direct  (fn [ns-sym]
                  (let [f (file-of ns-sym)]
                    (when (.exists ^java.io.File f)
                      (with-open [r (java.io.PushbackReader. (io/reader f))]
                        (->> (read r) (tree-seq coll? seq)
                             (filter #(and (symbol? %) (str/starts-with? (name %) "urpx.")))
                             set)))))]
    (loop [seen #{} todo #{root}]
      (if-let [n (first todo)]
        (recur (conj seen n) (into (disj todo n) (remove seen (direct n))))
        (disj seen root)))))

(deftest optional-by-construction
  (testing "a consumer resolving prices must not pay for this namespace, which
            is what 'optional' has to mean to be worth claiming.

            Asserted over the RESOLUTION PATH rather than over all of src/. The
            blunt version, 'nothing in src/ requires it', reads stronger and is
            the wrong claim: urpx.validate is a CLI entry point that no
            price-resolving consumer loads, and forbidding it there would push
            the validator into re-deriving checks this namespace already
            implements, which is the duplication that put an
            effectiveDateTime-only filter into urpx.arrangements.

            urpx.coverage must also not reach for the pricing side. A verdict is
            what a publisher asserts about a snapshot; needing urpx.price to
            read one would mean the reading depended on resolution."
    (doseq [root ['urpx.price 'urpx.schedule 'urpx.arrangements 'urpx.emit 'urpx.dsl]]
      (is (not (contains? (transitive-requires root) 'urpx.coverage))
          (str root " must not reach urpx.coverage, directly or transitively")))
    (let [reached (transitive-requires 'urpx.coverage)]
      (is (contains? reached 'urpx.index) "sanity: the walk does find real requires")
      (is (not (contains? reached 'urpx.price)) "and it must not require urpx.price")
      (is (not (contains? reached 'urpx.arrangements)) "nor urpx.arrangements"))))

;; ---------------------------------------------------------------------------
;; Arrangements
;; ---------------------------------------------------------------------------

(defn- sce-d+care []
  (first (filter #(seq (:urpx.arrangement/modifier-version-ids %))
                 (arr/arrangements [(fixtures/load-rate-plan "sce-d-2026-03-20")
                                    (fixtures/load-modifier "sce-care-2026-03-20")]))))

(deftest there-is-no-arrangement-level-verdict
  (testing "THE ABSENCE IS THE DESIGN, so it is pinned as an exact key set
            rather than left as a comment. Adding a rolled-up
            :urpx.coverage/completeness fails here, which is the only durable
            guard against the pressure to add one: every caller who wants a
            single field will ask, and the answer has to survive them.

            Why there is none. No ordering is stated among the six completeness
            members and the absence is meaningful, since the same ontology uses
            skos:broader where it does mean a hierarchy. More decisively, every
            definition is relative to 'the claimed scope' of its own snapshot
            and no snapshot states one, so composing would need each part's
            scope and their union to equal the arrangement's. Neither is known,
            which makes composition undefined at the domain level rather than
            merely unranked.

            And a minimum would be positively wrong in a case the vocabulary
            allows: a modifier claiming urpx:noCoverage contributes nothing, so
            a complete base with a noCoverage modifier is not a noCoverage
            arrangement."
    (is (= #{:urpx.coverage/parts
             :urpx.coverage/completeness-counts
             :urpx.coverage/unstated-count
             :urpx.coverage/every-part-stated?
             :urpx.coverage/every-part-complete?
             :urpx.coverage/every-part-self-contained?
             :urpx.coverage/any-part-scope-stated?}
           (set (keys (cov/of-arrangement (sce-d+care)))))
        "exactly these keys: no verdict, no min, no fold")
    (is (not-any? #(re-find #"weakest|overall|rollup|composite|combined|arrangement-verdict"
                            (name %))
                  (keys (ns-publics 'urpx.coverage)))
        "and no public var offers one under another name. Deliberately NOT
         banning the word \"verdict\": verdict-applies? is a predicate about one
         version's own scope, and an over-broad guard that fires on a correct
         name teaches people to loosen guards.")))

(deftest a-mixed-arrangement-keeps-both-verdicts
  (testing "SCE Schedule D claims complete and the CARE modifier claims partial,
            so the report has to carry two distinct values. Any fold to one
            loses the thing a caller needs to decide with."
    (let [r (cov/of-arrangement (sce-d+care))]
      (is (= {"urpx:complete" 1 "urpx:partial" 1}
             (:urpx.coverage/completeness-counts r)))
      (is (false? (:urpx.coverage/every-part-complete? r)))
      (is (true? (:urpx.coverage/every-part-stated? r))))))

(deftest every-part-complete-is-reachable-so-the-gate-is-not-constant-false
  (testing "a conjunction that can never be true is not a gate. SCE Schedule D
            alone claims complete, so the base-only arrangement satisfies it.

            NOTE what it still does not mean: each claim covers its own
            snapshot's required elements, so every part claiming complete says
            nothing about elements a composition needs and neither part carries."
    (let [base-only (first (filter #(empty? (:urpx.arrangement/modifier-version-ids %))
                                   (arr/arrangements [(fixtures/load-rate-plan "sce-d-2026-03-20")])))]
      (is (true? (:urpx.coverage/every-part-complete? (cov/of-arrangement base-only)))))))

(deftest parts-come-in-arrangement-order-with-their-roles
  (testing "base first, then modifiers in the order the arrangement holds them,
            each tagged. A caller correlating a verdict with the document it
            came from needs the order to be the arrangement's, not this
            namespace's."
    (let [a (sce-d+care)
          r (cov/of-arrangement a)]
      (is (= [:base :modifier] (mapv :urpx.coverage/role (:urpx.coverage/parts r))))
      (is (= (into [(:urpx.arrangement/version-id a)]
                   (:urpx.arrangement/modifier-version-ids a))
             (mapv :urpx.coverage/version-id (:urpx.coverage/parts r)))
          "and the ids line up index for index with the arrangement's own"))))

(deftest each-part-is-read-against-its-own-document
  (testing "the arrangement already binds each version to the document it came
            from, so a claim recorded as a bare @id resolves against the right
            graph rather than whichever document was at hand. Reading the
            modifier's claim against the base plan's document would refuse
            :claim-ref-unresolved, so this passing IS the assertion.

            Constructed because the corpus records no claim by reference."
    (let [claim-node {:jsonld/id "x:cc-m" :jsonld/type "urpx:CoverageClaim"
                      :urpx/completenessResult {:jsonld/id "urpx:pending"}}
          mod-doc {:jsonld/id "x:m" :jsonld/type "urpx:RatePlanModifier" :urpx/timezoneIdentifier "America/Los_Angeles"
                   :urpx/theClaim claim-node
                   :urpx/hasRatePlanModifierVersion
                   {:jsonld/id "x:m-v" :jsonld/type "urpx:RatePlanModifierVersion"
                    :urpx/hasCoverageClaim {:jsonld/id "x:cc-m"}}}
          arrangement {:urpx.arrangement/version {:jsonld/id "x:p-v" :jsonld/type "urpx:RatePlanVersion"}
                       :urpx.arrangement/rate-plan {:jsonld/id "x:p" :jsonld/type "urpx:RatePlan" :urpx/timezoneIdentifier "America/Los_Angeles"}
                       :urpx.arrangement/modifier-versions [(:urpx/hasRatePlanModifierVersion mod-doc)]
                       :urpx.arrangement/modifiers [mod-doc]}
          r (cov/of-arrangement arrangement)]
      (is (= [nil "urpx:pending"]
             (mapv :urpx.coverage/completeness (:urpx.coverage/parts r)))
          "the modifier's by-reference claim resolved in its OWN document")
      (is (= 1 (:urpx.coverage/unstated-count r))
          "and the base, which genuinely has no claim, is unstated rather than refused"))))

(deftest descriptions-carry-the-prose-that-names-the-gap
  (testing "a completeness result says THAT something is missing; the linked
            Evaluation says WHAT. In this corpus the prose is specific enough to
            act on, which makes it the thing a consumer deciding whether to
            publish a partial snapshot actually reads."
    (let [ds (cov/descriptions (cov/of-arrangement (sce-d+care)))]
      (is (= 1 (count ds)) "one part carries prose")
      (is (= :modifier (:role (first ds))))
      (is (= "sce:care-2026-03-20" (:version-id (first ds))))
      (is (< 100 (count (:description (first ds))))
          "and it is prose, not a label"))))

;; ---------------------------------------------------------------------------
;; Does a verdict cover a window?
;; ---------------------------------------------------------------------------

(def ^:private la (java.time.ZoneId/of "America/Los_Angeles"))
(defn- zdt
  "A ZonedDateTime, because every boundary here is zoned. See the README's
   \"Time and timezones\" and clj-price-server's standing rule."
  [y m d]
  (java.time.ZonedDateTime/of y m d 0 0 0 0 la))
(defn- ldt [y m d] (java.time.LocalDateTime/of y m d 0 0))

(defn- scoped
  "A version whose claim carries one coverage scope with the given bounds."
  [bounds]
  (version {:urpx/completenessResult {:jsonld/id "urpx:partial"}
            :urpx/hasTemporalScope
            [(merge {:jsonld/type "urpx:TemporalScope"
                     :urpx/scopeKind {:jsonld/id "urpx:coverage"}}
                    bounds)]}))

(defn- applies? [bounds from to]
  (cov/verdict-applies? (cov/of-version (scoped bounds)) la
                        {:tick/beginning from :tick/end to}))

(deftest a-verdict-covers-a-window-or-it-does-not
  (testing "the call a price server makes before trusting a verdict over the
            horizon it is about to publish. The scope's window must CONTAIN the
            query window: a verdict covering January says nothing useful about a
            query that runs into February."
    (let [jan-to-mar {:urpx/startDateTime (ldt 2026 1 1)
                      :urpx/endDateTime   (ldt 2026 3 1)}]
      (is (true?  (applies? jan-to-mar (zdt 2026 1 1) (zdt 2026 2 1))) "inside")
      (is (true?  (applies? jan-to-mar (zdt 2026 1 1) (zdt 2026 3 1))) "exactly")
      (is (false? (applies? jan-to-mar (zdt 2025 12 1) (zdt 2026 2 1))) "starts too early")
      (is (false? (applies? jan-to-mar (zdt 2026 2 1) (zdt 2026 4 1))) "runs past the end")
      (is (false? (applies? jan-to-mar (zdt 2026 4 1) (zdt 2026 5 1))) "wholly outside"))))

(deftest the-window-is-start-inclusive-and-end-exclusive
  (testing "matching urpx.price/version-covers? exactly, which treats a version
            as covering [effective, expiration). URPX states no convention for a
            TemporalScope's bounds, and two readings of a window inside one
            library would be worse than either.

            Asserted at the boundary instants themselves, which is where an
            off-by-one lives: this library has already shipped one, an
            expirationDate read at start of day."
    (let [w {:urpx/startDateTime (ldt 2026 1 1) :urpx/endDateTime (ldt 2026 2 1)}]
      (is (true?  (applies? w (zdt 2026 1 1) (zdt 2026 2 1)))
          "a query starting exactly at the scope's start is covered")
      (is (false? (applies? w (zdt 2026 1 1) (zdt 2026 2 2)))
          "and one ending past the scope's end is not"))))

(deftest a-missing-bound-is-unbounded-on-that-side
  (testing "a scope stating only a start covers everything after it, the same
            reading urpx.price gives a version with no expiration."
    (is (true?  (applies? {:urpx/startDateTime (ldt 2026 1 1)} (zdt 2030 1 1) (zdt 2031 1 1))))
    (is (false? (applies? {:urpx/startDateTime (ldt 2026 1 1)} (zdt 2025 1 1) (zdt 2026 6 1))))
    (is (true?  (applies? {:urpx/endDateTime (ldt 2026 3 1)} (zdt 2020 1 1) (zdt 2026 3 1))))))

(deftest a-date-bound-is-read-at-start-of-day
  (testing "the same rule urpx.price/version-effective-at gives the version
            shapes' own date-or-dateTime pair. A second reading of the same kind
            of bound is the drift that put an effectiveDateTime-only filter into
            urpx.arrangements."
    (let [w {:urpx/startDate (java.time.LocalDate/of 2026 1 1)
             :urpx/endDate   (java.time.LocalDate/of 2026 2 1)}]
      (is (true?  (applies? w (zdt 2026 1 1) (zdt 2026 2 1))))
      (is (false? (applies? w (zdt 2025 12 31) (zdt 2026 2 1))))
      (testing "and a dateTime wins over a date when both are present"
        (is (false? (applies? (assoc w :urpx/startDateTime (ldt 2026 1 15))
                              (zdt 2026 1 1) (zdt 2026 2 1))))))))

(deftest no-coverage-scope-refuses-rather-than-answering
  (testing "THIS FIRES ON EVERY FILING IN THE PRODUCTION CORPUS, because zero
            TemporalScope nodes of any kind exist there or in the fixtures. The
            ontology says producers SHOULD attach one and no shape makes them.

            False would claim the verdict does not cover the window, which the
            document never said; true would be worse. The honest answer is that
            the question has none, which is what a refusal says. A caller that
            publishes anyway is choosing a policy, and catching a named reason
            is how it says so."
    (let [r (cov/of-version (version {:urpx/completenessResult {:jsonld/id "urpx:partial"}}))
          e (is (thrown? clojure.lang.ExceptionInfo
                         (cov/verdict-applies? r la {:tick/beginning (zdt 2026 1 1) :tick/end (zdt 2026 2 1)})))]
      (is (= :urpx.coverage/coverage-scope-absent (:reason (ex-data e)))))
    (testing "and on a real filing, which is the case a consumer meets first"
      (let [r (cov/of-version (:urpx/hasRatePlanVersion
                               (fixtures/load-rate-plan "sce-d-2026-03-20")))]
        (is (= :urpx.coverage/coverage-scope-absent
               (try (cov/verdict-applies? r la {:tick/beginning (zdt 2026 7 15) :tick/end (zdt 2026 7 18)}) nil
                    (catch clojure.lang.ExceptionInfo e (:reason (ex-data e))))))))))

(deftest a-scope-with-no-bounds-refuses-separately
  (testing "urpx:scopeKind is the only required field, so a scope may name its
            role and bound nothing. That asserts which clock the verdict refers
            to without saying over what span, and it is conformant.

            A distinct reason from the absent case, because the two call for
            different things: one filing needs a scope added, the other needs
            the scope it has given bounds."
    (let [e (is (thrown? clojure.lang.ExceptionInfo (applies? {} (zdt 2026 1 1) (zdt 2026 2 1))))]
      (is (= :urpx.coverage/coverage-scope-unbounded (:reason (ex-data e)))))))

(deftest the-coverage-window-is-ticks-interval-shape
  (testing "the same {:tick/beginning :tick/end} map urpx.schedule emits from
            price-schedule and price-segments, so a caller holding segments and
            a caller holding a coverage window hold the same kind of thing and
            can hand either to tick.alpha.interval.

            LOCAL, not zoned, which is the one place the two differ. A schedule
            interval carries ZonedDateTimes in the plan's resolved zone; URPX
            states a TemporalScope's bounds with no zone at all, so projecting
            one into a zone here would be inventing it. A caller compares by
            projecting the segment with .toLocalDateTime, the same reduction
            urpx.price applies to every query instant."
    (let [w (cov/coverage-interval
             (cov/of-version (scoped {:urpx/startDateTime (ldt 2026 1 1)
                                      :urpx/endDateTime   (ldt 2026 3 1)})) la)]
      (is (= #{:tick/beginning :tick/end} (set (keys w))))
      (is (= (zdt 2026 1 1) (:tick/beginning w)))
      (is (every? #(instance? java.time.ZonedDateTime %) (vals w))
          "ZONED, matching what urpx.schedule emits under the same keys. An
           unzoned window compares against a zoned segment without failing
           loudly: tick's relation throws, but contains? returns true
           regardless of the values."))
    (testing "and an unbounded side is nil rather than absent, so the shape is stable"
      (is (= {:tick/beginning (zdt 2026 1 1) :tick/end nil}
             (cov/coverage-interval
              (cov/of-version (scoped {:urpx/startDateTime (ldt 2026 1 1)})) la))))))

(deftest the-window-is-a-tick-interval-in-fact-not-only-in-shape
  (testing "tick's interval functions read any map carrying :tick/beginning and
            :tick/end, so the shape choice buys real interop rather than a
            resemblance. Pinned because the docstrings claim it.

            On bounded windows verdict-applies? IS tick.alpha.interval/contains?.
            It exists as a named predicate because the refusals are the
            interesting part; a caller wanting Allen's richer relation should
            reach for tick rather than for another function here."
    (let [r   (cov/of-version (scoped {:urpx/startDateTime (ldt 2026 1 1)
                                       :urpx/endDateTime   (ldt 2026 3 1)}))
          win (cov/coverage-interval r la)]
      (is (= :equals (ti/relation win win)) "tick reads it as an interval")
      (doseq [[rel iv] [[:during      {:tick/beginning (zdt 2026 1 15) :tick/end (zdt 2026 2 1)}]
                        [:overlaps    {:tick/beginning (zdt 2025 12 1) :tick/end (zdt 2026 2 1)}]
                        [:preceded-by {:tick/beginning (zdt 2026 4 1) :tick/end (zdt 2026 5 1)}]]]
        (is (= rel (ti/relation iv win)) (str "tick says " rel))
        (is (= (ti/contains? win iv) (cov/verdict-applies? r la iv))
            (str "and contains? agrees with verdict-applies? for " rel))))))

(deftest a-coverage-window-compares-with-a-real-schedule-segment
  (testing "THE ACTUAL USE CASE, and the reason the window is zoned. A price
            server holds segments from urpx.arrangements/segments-for and asks
            whether the verdict covers what it is about to publish. Both sides
            carry ZonedDateTime under :tick/beginning and :tick/end, so they
            compare with no conversion at the call site.

            This was LocalDateTime for one commit, and the mixture does not fail
            loudly: tick's relation throws a ClassCastException, but contains?
            returns true regardless of the values. A confident wrong answer
            about whether a verdict covers a published horizon is the worst
            thing this namespace could produce, and it is what the repo's own
            README already forbade: ZonedDateTime end-to-end at output
            boundaries."
    (let [a    (first (arr/arrangements [(fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")]))
          segs (arr/segments-for a (.toInstant (zdt 2026 7 15)) (.toInstant (zdt 2026 7 16)))
          seg  (select-keys (first segs) [:tick/beginning :tick/end])
          ;; a verdict covering all of July, stated as the publisher would
          r    (cov/of-version (scoped {:urpx/startDateTime (ldt 2026 7 1)
                                        :urpx/endDateTime   (ldt 2026 8 1)}))]
      (is (every? #(instance? java.time.ZonedDateTime %) (vals seg))
          "segments-for emits zoned, as the README says it does")
      (is (every? #(instance? java.time.ZonedDateTime %)
                  (vals (cov/coverage-interval r la)))
          "and so does coverage-interval, under the same keys")
      (is (= :during (ti/relation seg (cov/coverage-interval r la)))
          "so tick relates them directly, with no projection at the call site")
      (is (true? (cov/verdict-applies? r la seg))
          "and the verdict covers the segment"))))

(deftest a-coverage-window-refuses-without-a-zone
  (testing "URPX states a TemporalScope's bounds as wall-clock readings with no
            zone, so there is no honest default. Returning them unzoned was the
            mistake this refusal replaces: it looked like the careful choice and
            produced a map that compares against a zoned schedule interval
            without failing loudly.

            Required rather than defaulted, because any default would be a zone
            this library picked. A rate plan usually declares one as
            urpx:timezoneIdentifier, which is what a caller should pass."
    (let [r (cov/of-version (scoped {:urpx/startDateTime (ldt 2026 1 1)
                                     :urpx/endDateTime   (ldt 2026 3 1)}))]
      (doseq [[nm f] [["coverage-interval" #(cov/coverage-interval r nil)]
                      ["verdict-applies?"  #(cov/verdict-applies?
                                             r nil {:tick/beginning (zdt 2026 1 1)
                                                    :tick/end (zdt 2026 2 1)})]]]
        (let [e (is (thrown? clojure.lang.ExceptionInfo (f)) nm)]
          (is (= :urpx.coverage/zone-required (:reason (ex-data e))) nm))))))

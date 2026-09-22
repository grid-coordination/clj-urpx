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

(deftest optional-by-construction
  (testing "the same discipline urpx.arrangements is held to, asserted the same
            way: by reading the ns forms on disk rather than at runtime, because
            ns-refers sees :refer only and ns-aliases sees :as only, so a bare
            (:require [urpx.coverage]) would pass both.

            urpx.coverage must also not reach for the pricing side. A verdict is
            what a publisher asserts about a snapshot; needing urpx.price to read
            one would mean the reading depended on resolution."
    (let [ns-form (fn [f] (with-open [r (java.io.PushbackReader. (io/reader f))]
                            (read r)))
          mentions? (fn [f sym] (some #(= sym %) (tree-seq coll? seq (ns-form f))))
          srcs (->> (file-seq (io/file "src"))
                    (filter #(.isFile ^java.io.File %))
                    (filter #(str/ends-with? (.getName ^java.io.File %) ".clj")))]
      (is (= [] (->> srcs
                     (remove #(= "coverage.clj" (.getName ^java.io.File %)))
                     (filter #(mentions? % 'urpx.coverage))
                     (mapv #(.getPath ^java.io.File %))))
          "no namespace in src/ may require urpx.coverage")
      (let [f (io/file "src/urpx/coverage.clj")]
        (is (not (mentions? f 'urpx.price)) "and it must not require urpx.price")
        (is (not (mentions? f 'urpx.arrangements)) "nor urpx.arrangements")))))

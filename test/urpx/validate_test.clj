(ns urpx.validate-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [urpx.core :as core]
            [urpx.test-fixtures :as fixtures]
            [urpx.validate :as validate]))

(deftest check-file-descends-wrappers
  (testing "the whole point of this ns: load-graph returns top-level entities
            only, so a wrapper-wrapped document must be descended or the inner
            RatePlan is never checked and the file reads as ok"
    (let [path (.getPath (fixtures/fixture-resource "pge-e-1-2026-03-01"))
          {:keys [checked skipped]} (validate/check-file path)
          types (set (map :type checked))]
      (is (contains? types "urpx:RatePlan"))
      (is (contains? types "urpx:RatePlanModifier"))
      (is (every? nil? (map :error checked)) "the vendored fixture is valid")
      (testing "unmodeled types are reported rather than silently dropped, so a
                file yielding nothing recognizable is visible as such"
        (is (some #{"urpx:ProgramEnrollment"} skipped))))))

(deftest check-file-reports-the-failing-path
  (testing "a missing required key surfaces as an error naming it"
    (let [plan (fixtures/load-rate-plan "pge-e-1-2026-03-01")
          broken (dissoc plan :urpx/name)
          s (:error (first (:checked (with-redefs
                                       [core/load-graph (constantly [broken])
                                        core/expand-document-entities vector]
                                       (validate/check-file "synthetic")))))]
      (is (some? s))
      (is (some #(= [:urpx/name] (:in %)) (:errors s))))))

;; ---------------------------------------------------------------------------
;; Conformance findings
;;
;; Rules the STANDARD states that this library's Malli schemas deliberately do
;; not enforce, so a document can be schema-valid and still violate one. Each
;; case is written to a temp file, because check-file's contract is a path.
;; ---------------------------------------------------------------------------

(def ^:private ctx "\"@context\":{\"urpx\":\"https://urpx.org/ns#\"}")

(defn- tmp-doc [json]
  (let [f (java.io.File/createTempFile "urpx-validate" ".jsonld")]
    (.deleteOnExit f)
    (spit f json)
    (.getPath f)))

(defn- findings [json] (:findings (validate/check-file (tmp-doc json))))

(defn- plan-json
  "A wrapper targeting `target`, holding a plan whose version carries `version-extra`."
  [target version-extra]
  (str "{" ctx ",\"@id\":\"x:pkg\",\"@type\":\"urpx:URPXPackage\","
       (when target (str "\"urpx:targetURPXVersion\":\"" target "\","))
       "\"urpx:hasRatePlan\":{\"@id\":\"x:p\",\"@type\":\"urpx:RatePlan\","
       "\"urpx:hasRatePlanVersion\":{\"@id\":\"x:p-v\",\"@type\":\"urpx:RatePlanVersion\""
       version-extra "}}}"))

(def ^:private v051 "https://github.com/urpx-org/urpx/raw/v0.5.1/ontology/urpx-ontology.ttl")
(def ^:private v021 "https://github.com/urpx-org/urpx/raw/v0.2.1/ontology/urpx-ontology.ttl")
(def ^:private claim
  (str ",\"urpx:hasCoverageClaim\":{\"@type\":\"urpx:CoverageClaim\","
       "\"urpx:completenessResult\":{\"@id\":\"urpx:partial\"}}"))

(deftest a-v051-document-must-carry-a-coverage-claim
  (testing "urpx:CoverageClaim is sh:minCount 1 on both version shapes and its
            skos:note says it is required on every version. This library's
            schema leaves it OPTIONAL for a recorded reason: requiring it would
            reject every v0.2.x document, and v0.2.x acceptance is a contract.

            So the rule is checked against what the DOCUMENT says it targets
            rather than made unconditional. That is the only way to enforce it
            without breaking the contract, and it is why the check belongs in
            the validator rather than in the schema."
    (is (= [:missing-coverage-claim]
           (mapv :rule (findings (plan-json v051 ""))))
        "a v0.5.1 document with no claim is nonconformant")
    (is (= [] (findings (plan-json v051 claim)))
        "and with one, it is clean")
    (is (= [] (findings (plan-json v021 "")))
        "while a v0.2.x document with no claim is exactly what the contract
         promises to accept")
    (is (= [] (findings (plan-json nil "")))
        "and a document stating no target says nothing to check against")))

(deftest an-unreadable-target-version-is-said-out-loud
  (testing "if a document states a target this cannot parse, whether the claim
            rule applies is UNKNOWN. Skipping quietly would look identical to
            passing, which is the failure mode this repository keeps finding."
    (let [f (findings (plan-json "https://example.invalid/ontology.ttl" ""))]
      (is (= [:unparseable-target-version] (mapv :rule f)))
      (is (= "https://example.invalid/ontology.ttl" (:detail (first f)))))))

(deftest self-contained-must-agree-where-it-appears-twice
  (testing "urpx:selfContained may be materialized on the version alongside the
            claim, and 'when both are present they MUST agree, and the claim is
            authoritative'. urpx.coverage READS such a document without
            refusing, because that note settles which wins; the violated MUST is
            the validator's business.

            The two halves of one rule, split by what each is for: reading and
            judging are different jobs."
    (let [disagreeing (str ",\"urpx:selfContained\":false"
                           ",\"urpx:hasCoverageClaim\":{\"@type\":\"urpx:CoverageClaim\","
                           "\"urpx:completenessResult\":{\"@id\":\"urpx:partial\"},"
                           "\"urpx:selfContained\":true}")
          agreeing    (str ",\"urpx:selfContained\":true"
                           ",\"urpx:hasCoverageClaim\":{\"@type\":\"urpx:CoverageClaim\","
                           "\"urpx:completenessResult\":{\"@id\":\"urpx:partial\"},"
                           "\"urpx:selfContained\":true}")]
      (is (= [:self-contained-disagrees] (mapv :rule (findings (plan-json v051 disagreeing)))))
      (is (= [] (findings (plan-json v051 agreeing))))
      (is (= [] (findings (plan-json v051 claim)))
          "and stating it in only one place is not a disagreement"))))

(deftest a-schema-valid-file-can-still-be-nonconformant
  (testing "the whole reason these are findings rather than schema errors: they
            are different claims. One says the document does not match what this
            library models; the other that it does and still breaks a rule the
            standard states. A file that passes every schema and violates a MUST
            must not report as clean.

            Built from a real fixture rather than by hand, because hand-built
            minimal documents are not schema-valid and would test the wrong
            thing: a schema failure would mask the finding."
    (let [raw  (slurp (.getPath (fixtures/fixture-resource "sce-d-2026-03-20")))
          ;; the fixture's claim states selfContained; contradict it on the
          ;; version, which the ontology permits syntactically and forbids by MUST
          doctored (str/replace raw
                                "\"@type\": \"urpx:RatePlanVersion\","
                                "\"@type\": \"urpx:RatePlanVersion\", \"urpx:selfContained\": false,")
          path (tmp-doc doctored)
          r    (validate/check-file path)]
      (is (not= raw doctored) "the fixture was actually doctored")
      (is (empty? (filter :error (:checked r)))
          "every schema still accepts it")
      (is (= [:self-contained-disagrees] (mapv :rule (:findings r)))
          "and it is still nonconformant")
      (testing "while the untouched fixture is clean both ways"
        (let [clean (validate/check-file (.getPath (fixtures/fixture-resource "sce-d-2026-03-20")))]
          (is (empty? (filter :error (:checked clean))))
          (is (empty? (:findings clean))))))))

(deftest the-target-version-is-found-by-a-deep-walk
  (testing "urpx:targetURPXVersion sits on the urpx:URPXPackage wrapper in most
            filings and on a node inside the @graph in the rest, so a top-level
            read finds it in some documents and not others.

            A shallow search made four fixtures look as though they stated
            none, which would have silently skipped the coverage-claim rule on
            exactly the documents it applies to."
    (let [graph-shaped (validate/check-file
                        (.getPath (fixtures/fixture-resource "pge-e-1-2026-03-01")))
          wrapped      (validate/check-file
                        (.getPath (fixtures/fixture-resource "sce-d-2026-03-20")))]
      (doseq [[nm r] [["@graph" graph-shaped] ["wrapped" wrapped]]]
        (is (empty? (:unchecked r))
            (str nm ": the target version was found, so the rule was applied"))
        (is (empty? (:findings r)) (str nm ": and satisfied"))))))

(deftest a-finding-reaches-the-exit-code
  (testing "or it is decoration. This was decoration at first: -main exited 1
            only on schema errors, so a file every schema accepted and the
            standard rejected exited 0 with the violation printed above it.

            Asserted through the private reporter, which is what -main consults,
            because -main itself calls System/exit."
    (let [report #'urpx.validate/report
          clean  {:path "x" :checked [{:type "t" :id "i" :error nil}] :skipped [] :findings []}]
      (is (true? (report clean)) "a clean file reports true")
      (is (false? (report (assoc clean :findings [{:rule :self-contained-disagrees}])))
          "and a nonconformant one reports false even with no schema error"))))

(deftest check-file-validates-a-scenario-cost-model
  (let [f (java.io.File/createTempFile "scm" ".jsonld")]
    (try
      (spit f fixtures/scenario-cost-model-example)
      (let [{:keys [checked skipped]} (validate/check-file (.getPath f))]
        (is (= [{:type "urpx:ScenarioCostModel"
                 :id "sce:scm-tou-d-prime-care-cca-crs-v2020"
                 :error nil}]
               checked))
        (is (empty? skipped)))
      (spit f (str/replace fixtures/scenario-cost-model-example
                           "\"@type\""
                           "\"urpx:hasCustomerProfile\": {\"@id\": \"sce:cp\"}, \"@type\""))
      (is (some? (:error (first (:checked (validate/check-file (.getPath f)))))))
      (finally (.delete f)))))

(deftest check-file-validates-the-wrapper-itself
  (testing "a wrapper's own properties are schema-checked alongside its payloads"
    (doseq [t ["urpx:URPXPackage" "urpx:URPXDocument"]]
      (let [doc (fn [extra]
                  (str "{" ctx ",\"@id\":\"x:pkg\",\"@type\":\"" t "\"," extra
                       "\"urpx:hasRatePlan\":{\"@id\":\"x:p\",\"@type\":\"urpx:RatePlan\"}}"))
            wrapper (fn [r] (first (filter #(= t (:type %)) (:checked r))))]
        (testing (str t " missing urpx:targetURPXVersion")
          (let [w (wrapper (validate/check-file (tmp-doc (doc ""))))]
            (is (= "x:pkg" (:id w)))
            (is (some #(= [:urpx/targetURPXVersion] (:in %)) (:errors (:error w))))))
        (testing (str t " with a numeric urpx:timezoneIdentifier")
          (let [w (wrapper (validate/check-file
                            (tmp-doc (doc (str "\"urpx:targetURPXVersion\":\"" v051 "\","
                                               "\"urpx:timezoneIdentifier\":42,")))))]
            (is (some #(= [:urpx/timezoneIdentifier] (:in %)) (:errors (:error w)))))))))
  (testing "a valid wrapped fixture checks its package and passes"
    (let [{:keys [checked]} (validate/check-file
                             (.getPath (fixtures/fixture-resource "sce-d-2026-03-20")))]
      (is (= [nil] (map :error (filter #(= "urpx:URPXPackage" (:type %)) checked)))))))

(deftest a-wrapper-alone-is-refused
  (testing "a wrapper whose payload is a reference checks no plan, so -main refuses it"
    (doseq [t ["urpx:URPXPackage" "urpx:URPXDocument"]]
      (let [r (validate/check-file
               (tmp-doc (str "{" ctx ",\"@id\":\"x:pkg\",\"@type\":\"" t "\","
                             "\"urpx:targetURPXVersion\":\"" v051 "\","
                             "\"urpx:hasRatePlan\":{\"@id\":\"x:p\"}}")))]
        (is (= [t] (map :type (:checked r))))
        (is (#'validate/refused? r) t))))
  (testing "a wrapper with a plan inside is not"
    (is (not (#'validate/refused? (validate/check-file
                                   (.getPath (fixtures/fixture-resource "sce-d-2026-03-20")))))))
  (testing "a bare plan is not"
    (is (not (#'validate/refused? {:checked [{:type "urpx:RatePlan"}]})))))

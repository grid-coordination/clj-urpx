(ns urpx.load-test
  "Naming the load pipeline, and making its one silent drop assertable.

  Every test here uses a StringReader rather than a fixture file, because each
  is about a SHAPE the pipeline has to handle rather than about what any
  particular filing happens to contain. `urpx.core/load-graph` takes anything
  `clojure.java.io/reader` coerces, so a string is a source like any other."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [urpx.coerce :as coerce]
            [urpx.core :as core]
            [urpx.load :as load])
  (:import [java.io StringReader]
           [java.time LocalDate LocalDateTime]))

(defn- src
  "A source carrying `json`. New reader per call: a Reader is consumed once, and
   these tests pull the same content more than once."
  [json]
  (StringReader. json))

(def ^:private ctx "\"@context\":{\"urpx\":\"https://urpx.org/ns#\"}")

(defn- plan-json
  [plan-id version-id]
  (str "{" ctx ",\"@id\":\"" plan-id "\",\"@type\":\"urpx:RatePlan\","
       "\"urpx:hasRatePlanVersion\":{\"@id\":\"" version-id "\","
       "\"@type\":\"urpx:RatePlanVersion\","
       "\"urpx:forRatePlan\":{\"@id\":\"" plan-id "\"},"
       "\"urpx:effectiveDateTime\":\"2026-01-01T00:00:00\"}}"))

(def ^:private graph-of-two
  (str "{" ctx ",\"@graph\":["
       "{\"@id\":\"x:p\",\"@type\":\"urpx:RatePlan\"},"
       "{\"@id\":\"x:m\",\"@type\":\"urpx:RatePlanModifier\"}]}"))

(def ^:private wrapped
  (str "{" ctx ",\"@id\":\"x:pkg\",\"@type\":\"urpx:URPXPackage\","
       "\"urpx:hasRatePlan\":{\"@id\":\"x:p\",\"@type\":\"urpx:RatePlan\"}}"))

(def ^:private unmodelled
  (str "{" ctx ",\"@id\":\"x:prior\",\"@type\":\"urpx:PriorRatePlan\"}"))

(deftest document-xf-is-the-four-step-form-under-a-name
  (testing "nothing new happens here. The point is that the composition exists
            once, in the one order that is correct, rather than being rebuilt by
            every consumer from a README paragraph. The first consumer to
            rebuild it used `mapv` where `mapcat` belongs and omitted the
            coercion step, and got a plausible number instead of an error."
    (let [json (plan-json "x:p" "x:p-v1")]
      (is (= (->> [(src json)]
                  (mapcat core/load-graph)
                  (mapcat core/expand-document-entities)
                  (keep coerce/coerce-doc)
                  vec)
             (into [] load/document-xf [(src json)])
             (load/documents [(src json)]))))))

(deftest mapcat-not-map-over-load-graph
  (testing "one source can carry several top-level entities, so the first step
            has to flatten. Mapping would yield one vector per source, and a
            vector of entities survives a tree-seq walk, which is why getting
            this wrong returns an answer rather than an error."
    (is (= ["x:p" "x:m"]
           (mapv :jsonld/id (load/documents [(src graph-of-two)])))
        "an @graph of two yields two documents, not one collection")))

(deftest the-wrapper-is-descended
  (testing "urpx:URPXPackage nests the payload. Without expand-document-entities
            a wrapped filing contributes nothing at all, and an enumeration over
            a corpus of wrapped filings comes back empty rather than wrong."
    (is (= ["x:p" "x:pkg"] (mapv :jsonld/id (load/documents [(src wrapped)]))))))

(deftest a-wrapped-source-yields-the-package-shell
  (let [json  (str "{" ctx ",\"@id\":\"x:pkg\",\"@type\":\"urpx:URPXPackage\","
                   "\"urpx:targetURPXVersion\":\"x:v0.5.1\","
                   "\"urpx:timezoneIdentifier\":\"America/New_York\","
                   "\"urpx:dateCreated\":\"2026-01-01\","
                   "\"urpx:hasRatePlan\":{\"@id\":\"x:p\",\"@type\":\"urpx:RatePlan\","
                   "\"urpx:hasRatePlanVersion\":{\"@id\":\"x:p-v1\","
                   "\"@type\":\"urpx:RatePlanVersion\",\"urpx:forRatePlan\":{\"@id\":\"x:p\"},"
                   "\"urpx:effectiveDateTime\":\"2026-01-01T00:00:00\"}}}")
        [plan shell :as docs] (load/documents [(src json)])]
    (is (= ["x:p" "x:pkg"] (mapv :jsonld/id docs)))
    (is (= "urpx:RatePlan" (:jsonld/type plan)))
    (is (= [{:jsonld/id "x:p"}] (:urpx/hasRatePlan shell)) "the payload is a stub")
    (is (= "America/New_York" (:urpx/timezoneIdentifier shell)) "the package keeps its own properties")
    (is (nil? (coerce/check-coerced! [shell] "load-test")))))

(deftest a-nested-graph-reaches-the-pipeline
  (testing "a graph object inside @graph, the shape of upstream test-cases/pge-e-tou-c,
            contributes its members, and a package among them still yields its
            payload and its shell"
    (let [json (str "{" ctx ",\"@graph\":[{\"@graph\":["
                    "{\"@id\":\"x:pkg\",\"@type\":\"urpx:URPXPackage\","
                    "\"urpx:hasRatePlan\":{\"@id\":\"x:p\",\"@type\":\"urpx:RatePlan\"}},"
                    "{\"@id\":\"x:prior\",\"@type\":\"urpx:PriorRatePlan\"}]}]}")
          {:urpx.load/keys [documents uncoercible]} (load/scan [(src json)])]
      (is (= ["x:p" "x:pkg"] (mapv :jsonld/id documents)))
      (is (= [{:jsonld/id "x:p"}] (:urpx/hasRatePlan (second documents))) "the shell")
      (is (= [{:jsonld/type "urpx:PriorRatePlan" :jsonld/id "x:prior"}] uncoercible)))))

(deftest a-by-reference-package-is-already-a-shell
  (let [json (str "{" ctx ",\"@id\":\"x:pkg\",\"@type\":\"urpx:URPXPackage\","
                  "\"urpx:packages\":{\"@id\":\"x:p\"}}")]
    (is (= [(coerce/coerce-doc (first (core/load-graph (src json))))]
           (load/documents [(src json)])))))

(deftest a-by-reference-payload-is-not-a-discard
  (testing "a package naming its payloads by @id alone, the entities themselves
            being @graph siblings, discards nothing: the siblings load once each
            and the shell keeps the references"
    (let [json (str "{" ctx ",\"@graph\":["
                    "{\"@id\":\"x:pkg\",\"@type\":\"urpx:URPXPackage\","
                    "\"urpx:packages\":[{\"@id\":\"x:p\"},{\"@id\":\"x:m\"}],"
                    "\"urpx:hasRatePlan\":{\"@id\":\"x:p\"},"
                    "\"urpx:hasRatePlanModifier\":{\"@id\":\"x:m\"}},"
                    "{\"@id\":\"x:p\",\"@type\":\"urpx:RatePlan\"},"
                    "{\"@id\":\"x:m\",\"@type\":\"urpx:RatePlanModifier\"}]}")
          {:urpx.load/keys [documents uncoercible]} (load/scan [(src json)])]
      (is (= [] uncoercible))
      (is (= ["x:p" "x:m" "x:pkg"] (mapv :jsonld/id documents)))
      (is (= [{:jsonld/id "x:p"} {:jsonld/id "x:m"}] (:urpx/packages (last documents))))))
  (testing "expand-document-entities still returns the references"
    (let [json (str "{" ctx ",\"@id\":\"x:pkg\",\"@type\":\"urpx:URPXPackage\","
                    "\"urpx:packages\":{\"@id\":\"x:p\"}}")]
      (is (= [{:jsonld/id "x:p"}]
             (mapcat core/expand-document-entities (core/load-graph (src json))))))))

(deftest a-reference-no-source-states-is-reported
  (let [pkg  (str "{" ctx ",\"@id\":\"x:pkg\",\"@type\":\"urpx:URPXPackage\","
                  "\"urpx:packages\":[{\"@id\":\"x:p\"},{\"@id\":\"x:gone\"}],"
                  "\"urpx:hasRatePlanModifier\":{\"@id\":\"x:m\",\"@type\":\"urpx:RatePlanModifier\"}}")
        plan (plan-json "x:p" "x:p-v1")]
    (testing "each payload reference whose entity no source states, with the
              package and property naming it"
      (is (= [{:jsonld/id "x:gone" :package-id "x:pkg" :property :urpx/packages}]
             (:urpx.load/unresolved-references (load/scan [(src pkg) (src plan)])))))
    (testing "an entity stated in any source resolves, in either order"
      (is (= [{:jsonld/id "x:gone" :package-id "x:pkg" :property :urpx/packages}]
             (:urpx.load/unresolved-references (load/scan [(src plan) (src pkg)])))))
    (testing "with no source stating x:p, it is reported too"
      (is (= [{:jsonld/id "x:p" :package-id "x:pkg" :property :urpx/packages}
              {:jsonld/id "x:gone" :package-id "x:pkg" :property :urpx/packages}]
             (:urpx.load/unresolved-references (load/scan [(src pkg)])))))
    (testing "a scan with no unresolved reference reports an empty vector"
      (is (= [] (:urpx.load/unresolved-references (load/scan [(src plan)])))))))

(deftest an-untyped-entity-is-still-a-discard
  (testing "an embedded payload with no @type but statements of its own is an
            entity, and scan names it"
    (let [json (str "{" ctx ",\"@id\":\"x:pkg\",\"@type\":\"urpx:URPXPackage\","
                    "\"urpx:packages\":{\"@id\":\"x:q\",\"urpx:name\":\"Q\"}}")]
      (is (= [{:jsonld/type nil :jsonld/id "x:q"}]
             (:urpx.load/uncoercible (load/scan [(src json)]))))))
  (testing "a bare @id at the top of a @graph is an entity, and scan names it"
    (let [json (str "{" ctx ",\"@graph\":[{\"@id\":\"x:r\"}]}")]
      (is (= [{:jsonld/type nil :jsonld/id "x:r"}]
             (:urpx.load/uncoercible (load/scan [(src json)])))))))

(deftest an-embedded-payload-without-an-id-is-refused
  (let [json (str "{" ctx ",\"@id\":\"x:pkg\",\"@type\":\"urpx:URPXPackage\","
                  "\"urpx:hasRatePlan\":{\"@type\":\"urpx:RatePlan\"}}")]
    (doseq [f [load/documents load/scan]]
      (let [e (is (thrown? clojure.lang.ExceptionInfo (f [(src json)])))]
        (is (= {:reason :urpx.load/payload-without-id :package-id "x:pkg"
                :property :urpx/hasRatePlan}
               (select-keys (ex-data e) [:reason :package-id :property])))))))

(deftest values-arrive-coerced
  (testing "the step that bites, because skipping it does not fail loudly.
            Uncoerced, urpx:effectiveDateTime is still the string
            \"2026-01-01T00:00:00\": anything comparing dates throws from deep
            inside, and anything that does not compare them quietly answers a
            different question."
    (let [v (-> (load/documents [(src (plan-json "x:p" "x:p-v1"))])
                first :urpx/hasRatePlanVersion :urpx/effectiveDateTime)]
      (is (instance? LocalDateTime v)
          (str "expected a LocalDateTime, got " (pr-str (type v)))))))

(deftest the-order-of-sources-does-not-matter
  (testing "a client's walk order is its own business, so the document set must
            not depend on it."
    (let [a (src (plan-json "x:a" "x:a-v")) b (src (plan-json "x:b" "x:b-v"))
          a2 (src (plan-json "x:a" "x:a-v")) b2 (src (plan-json "x:b" "x:b-v"))]
      (is (= (set (map :jsonld/id (load/documents [a b])))
             (set (map :jsonld/id (load/documents [b2 a2]))))))))

(deftest nothing-is-realised-until-pulled
  (testing "a transducer so a client can stream a corpus it would rather not
            hold. Asserted by pulling ONE document from two sources and checking
            the second was never read: a consumed Reader reports ready, an
            untouched one is still pullable."
    (let [second-src (src (plan-json "x:b" "x:b-v"))
          taken      (into [] (take 1) (eduction load/document-xf
                                                 [(src (plan-json "x:a" "x:a-v"))
                                                  second-src]))]
      (is (= ["x:a"] (mapv :jsonld/id taken)))
      (is (= ["x:b"] (mapv :jsonld/id (load/documents [second-src])))
          "the second source is still unread, so it can still be loaded"))))

(deftest scan-reports-what-document-xf-discards
  (testing "THE POINT OF scan. coerce-doc returns nil for an entity type this
            library does not model, so document-xf drops it silently:
            urpx:PriorRatePlan.

            None of those is a version, which is luck rather than design. The
            unlucky form of the same thing cost a whole rate plan once: a
            version stating only urpx:effectiveDate was dropped by a filter that
            read only urpx:effectiveDateTime, and nothing said so."
    (let [{:urpx.load/keys [documents uncoercible]}
          (load/scan [(src (plan-json "x:p" "x:p-v1")) (src unmodelled)])]
      (is (= ["x:p"] (mapv :jsonld/id documents)))
      (is (= [{:jsonld/type "urpx:PriorRatePlan" :jsonld/id "x:prior"}]
             uncoercible)
          "the discard is named, with enough to identify it and no more"))))

(deftest publications-rate-cases-and-program-enrollments-are-documents
  (let [json (str "{" ctx ",\"@graph\":["
                  "{\"@id\":\"x:pub\",\"@type\":\"urpx:SourcePublication\","
                  "\"urpx:name\":\"Advice Letter\",\"urpx:effectiveDate\":\"2026-03-01\"},"
                  "{\"@id\":\"x:old\",\"@type\":\"urpx:TariffPublication\","
                  "\"urpx:dateReceived\":\"2025-12-01\"},"
                  "{\"@id\":\"x:case\",\"@type\":\"urpx:RateCase\","
                  "\"urpx:caseNumber\":\"A.24-01-001\",\"urpx:url\":\"https://example.com\"},"
                  "{\"@id\":\"x:enrol\",\"@type\":\"urpx:ProgramEnrollment\","
                  "\"urpx:programName\":\"CARE\",\"urpx:enrollmentStartDate\":\"2025-06-01\"},"
                  "{\"@id\":\"x:prior\",\"@type\":\"urpx:PriorRatePlan\"}]}")
        {:urpx.load/keys [documents uncoercible]} (load/scan [(src json)])
        by-id (into {} (map (juxt :jsonld/id identity)) documents)]
    (is (= #{"x:pub" "x:old" "x:case" "x:enrol"} (set (keys by-id))))
    (is (= (LocalDate/of 2026 3 1) (get-in by-id ["x:pub" :urpx/effectiveDate])))
    (is (= (LocalDate/of 2025 12 1) (get-in by-id ["x:old" :urpx/dateReceived])))
    (is (= "A.24-01-001" (get-in by-id ["x:case" :urpx/caseNumber])))
    (is (= (LocalDate/of 2025 6 1) (get-in by-id ["x:enrol" :urpx/enrollmentStartDate])))
    (is (= [{:jsonld/type "urpx:PriorRatePlan" :jsonld/id "x:prior"}] uncoercible))))

(deftest a-caller-can-assert-that-no-version-was-discarded
  (testing "the assertion that was unavailable before, written the way a
            consumer would write it. A version type reaching the discard list is
            the failure this exists to make visible."
    (let [no-version? #(empty? (filter (fn [e] (str/ends-with? (str (:jsonld/type e)) "Version"))
                                       (:urpx.load/uncoercible (load/scan %))))]
      (is (true? (no-version? [(src (plan-json "x:p" "x:p-v1")) (src unmodelled)]))
          "an unmodelled non-version discard does not trip it")
      (is (false? (no-version? [(src (str "{" ctx ",\"@id\":\"x:v\","
                                          "\"@type\":\"urpx:SupplyPlanVersion\"}"))]))
          "a version type this library does not model DOES trip it, which is the
           case worth catching: a future URPX version adding a snapshot type is
           indistinguishable from a corpus bug while the drop is silent"))))

(deftest scan-and-document-xf-agree-about-the-documents
  (testing "two ways to the same set, so a caller choosing scan for its report
            is not also choosing a different answer."
    (let [srcs #(vector (src (plan-json "x:p" "x:p-v1")) (src unmodelled) (src wrapped))]
      (is (= (load/documents (srcs))
             (:urpx.load/documents (load/scan (srcs))))))))

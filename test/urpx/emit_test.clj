(ns urpx.emit-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [urpx.coerce :as coerce]
            [urpx.core :as core]
            [urpx.emit :as emit]
            [urpx.schema :as schema]
            [urpx.test-fixtures :as fixtures])
  (:import [java.math BigDecimal]
           [java.time LocalDate LocalDateTime]))

;; ---------------------------------------------------------------------------
;; Smoke tests — each public emit function returns valid JSON-LD-shaped data
;; ---------------------------------------------------------------------------

(def minimal-rate-plan
  {:jsonld/type "urpx:RatePlan" :urpx/timezoneIdentifier "America/Los_Angeles"
   :jsonld/id   "x:plan"
   :urpx/ratePlanUniqueIdentifier "P1"
   :urpx/name "Plan One"
   :urpx/initialEffectiveDate (LocalDate/of 2026 1 1)
   :urpx/hasRatePlanVersion
   {:jsonld/type "urpx:RatePlanVersion"
    :urpx/changeReason {:jsonld/id "urpx:rateCase"}
    :urpx/forRatePlan {:jsonld/id "ex:parent"}
    :urpx/hasCoverageClaim {:jsonld/type "urpx:CoverageClaim" :urpx/completenessResult {:jsonld/id "urpx:complete"}}
    :urpx/serviceRole {:jsonld/id "urpx:bundledServiceRole"}
    :urpx/effectiveDateTime (LocalDateTime/of 2026 1 1 0 0)
    :urpx/hasPlanElements
    {:jsonld/type "urpx:PlanElements"
     :urpx/hasMetricInput
     [{:jsonld/type "urpx:MetricInput"
       :urpx/name "kWh"
       :urpx/metricInputType {:jsonld/id "urpx:measuredMetricInput"}}]
     :urpx/hasBillingModel
     {:jsonld/type "urpx:BillingModel"
      :urpx/hasPriceDefinition []}}
    :urpx/hasPrices {:jsonld/type "urpx:Prices"}}})

(deftest write-rate-plan-returns-valid-json
  (let [out (emit/write-rate-plan minimal-rate-plan)]
    (is (string? out))
    (let [parsed (json/read-str out)]
      (is (= "urpx:RatePlan" (get parsed "@type")))
      (is (= "x:plan"        (get parsed "@id")))
      (is (= "Plan One"      (get parsed "urpx:name")))
      (is (= "2026-01-01"    (get parsed "urpx:initialEffectiveDate"))
          "LocalDate encoded back to ISO string"))))

(deftest write-rate-plan-modifier-returns-valid-json
  (let [modifier {:jsonld/type "urpx:RatePlanModifier" :urpx/timezoneIdentifier "America/Los_Angeles"
                  :jsonld/id   "x:mod"
                  :urpx/modifierUniqueIdentifier "M1"
                  :urpx/name   "Modifier"
                  :urpx/initialEffectiveDate (LocalDate/of 2026 1 1)
                  :urpx/hasRatePlanModifierVersion
                  {:jsonld/type "urpx:RatePlanModifierVersion"
                   :urpx/changeReason {:jsonld/id "urpx:rateCase"}
                   :urpx/forRatePlanModifier {:jsonld/id "ex:parent"}
                   :urpx/hasCoverageClaim {:jsonld/type "urpx:CoverageClaim" :urpx/completenessResult {:jsonld/id "urpx:complete"}}
                   :urpx/serviceRole {:jsonld/id "urpx:bundledServiceRole"}
                   :urpx/modifierVersionNumber "1.0"
                   :urpx/effectiveDateTime (LocalDateTime/of 2026 1 1 0 0)
                   :urpx/hasPlanElements
                   {:jsonld/type "urpx:PlanElements"
                    :urpx/hasMetricInput
                    [{:jsonld/type "urpx:MetricInput"
                      :urpx/name "kWh"
                      :urpx/metricInputType {:jsonld/id "urpx:measuredMetricInput"}}]
                    :urpx/hasBillingModel
                    {:jsonld/type "urpx:BillingModel"
                     :urpx/hasPriceDefinition []}}
                   :urpx/hasPrices {:jsonld/type "urpx:Prices"}}}
        parsed (json/read-str (emit/write-rate-plan-modifier modifier))]
    (is (= "urpx:RatePlanModifier" (get parsed "@type")))
    (is (= "M1" (get parsed "urpx:modifierUniqueIdentifier")))))

(deftest write-urpx-document-returns-valid-json
  (let [doc {:jsonld/type "urpx:URPXDocument"
             :jsonld/id   "x:doc"
             :urpx/targetURPXVersion "https://example.com/urpx-v0.2.0"
             :urpx/hasRatePlan [minimal-rate-plan]}
        parsed (json/read-str (emit/write-urpx-document doc))]
    (is (= "urpx:URPXDocument" (get parsed "@type")))
    (is (= "https://example.com/urpx-v0.2.0"
           (get parsed "urpx:targetURPXVersion")))
    (is (vector? (get parsed "urpx:hasRatePlan")))))

(deftest write-urpx-package-returns-valid-json
  (let [pkg {:jsonld/type "urpx:URPXPackage"
             :jsonld/id   "x:pkg"
             :urpx/targetURPXVersion "https://example.com/urpx-v0.5.1"
             :urpx/packages [minimal-rate-plan {:jsonld/id "x:other"}]}
        parsed (json/read-str (emit/write-urpx-package pkg))]
    (is (= "urpx:URPXPackage" (get parsed "@type")))
    (is (= "https://example.com/urpx-v0.5.1"
           (get parsed "urpx:targetURPXVersion")))
    (is (vector? (get parsed "urpx:packages")))
    (testing "a urpx:RatePlan under urpx:packages is pre-encoded through its own
              schema, so its typed leaves come back as JSON-LD strings"
      (is (= "2026-01-01"
             (get-in parsed ["urpx:packages" 0 "urpx:initialEffectiveDate"]))))
    (testing "an untyped @id ref under urpx:packages passes through as-is"
      (is (= {"@id" "x:other"} (get-in parsed ["urpx:packages" 1]))))))

;; ---------------------------------------------------------------------------
;; End-to-end round-trip — parse → coerce → emit → re-parse → re-coerce
;; ---------------------------------------------------------------------------

(deftest roundtrip-minimal-rate-plan
  (testing "URPX-biu: minimal rate-plan round-trips with logical equivalence.
            `{:context nil}` suppresses the URPX-awl default @context so the
            re-coerced shape matches the original (which doesn't carry one)."
    (let [emitted (emit/write-rate-plan minimal-rate-plan {:context nil})
          re-parsed (-> emitted java.io.StringReader. core/parse-rate-plan)
          re-coerced (coerce/coerce-rate-plan re-parsed)]
      (is (= minimal-rate-plan re-coerced)))))

(deftest roundtrip-pge-fixture-rate-plan
  (testing "URPX-biu: a PG&E E-1 RatePlan (extracted from the @graph fixture)
            round-trips cleanly through emit + re-parse + re-coerce. Strongest
            available smoke test of the full encoder pipeline before URPX-739
            adds the dedicated round-trip suite over every vendored fixture."
    (let [original (fixtures/load-rate-plan "pge-e-1-2026-03-01")
          emitted (emit/write-rate-plan original {:context nil})
          re-coerced (-> emitted
                         java.io.StringReader.
                         core/parse-rate-plan
                         coerce/coerce-rate-plan)]
      (is (= original re-coerced)
          "structural equivalence end-to-end"))))

(deftest default-context-emitted-when-no-opts
  (testing "URPX-awl: write-* attaches the default @context by default — the
            output is SHACL-friendly without consumer setup"
    (let [parsed (json/read-str (emit/write-rate-plan minimal-rate-plan))
          ctx    (get parsed "@context")]
      (is (map? ctx))
      (is (= "https://urpx.org/ns/ontology/" (get ctx "urpx")))
      (is (= "http://www.w3.org/2001/XMLSchema#" (get ctx "xsd")))
      (is (= {"@type" "xsd:decimal"} (get ctx "urpx:unitPrice"))))))

(deftest default-context-urpx-prefix-matches-the-vendored-fixtures
  (testing "the default binds `urpx` to the IRI every vendored fixture declares"
    (let [dir   (java.io.File. "test/resources/urpx-fixtures")
          files (filter #(.endsWith (.getName ^java.io.File %) ".jsonld") (.listFiles dir))
          iris  (set (for [f files
                           :let [ctx (get (json/read-str (slurp f)) "@context")]
                           m (if (map? ctx) [ctx] (filter map? ctx))
                           :when (contains? m "urpx")]
                       (get m "urpx")))]
      (is (seq files))
      (is (= #{(get emit/default-context "urpx")} iris)))))

(defn- temporal-keys
  "Every map key under `root` whose value schema is schema/Date, DateTime or
   Time, to the xsd datatype it encodes as."
  [root]
  (let [xsd {(m/form schema/Date)     "xsd:date"
             (m/form schema/DateTime) "xsd:dateTime"
             (m/form schema/Time)     "xsd:time"}
        acc (volatile! {})]
    (m/walk root
            (fn [s _ _ _]
              (when (= :map (m/type s))
                (doseq [[k _ child] (m/children s)]
                  (when-let [t (xsd (m/form child))] (vswap! acc assoc k t))))
              s)
            {::m/walk-refs true ::m/walk-schema-refs true})
    @acc))

(deftest default-context-types-every-temporal-property
  (testing "an emitted temporal literal is a plain string, so the context is
            what types it, including one read from a {\"@value\" \"@type\"}
            object"
    (let [schemas (keep (fn [v] (try (m/schema @v) @v (catch Exception _ nil)))
                        (vals (ns-publics 'urpx.schema)))
          ks      (apply merge (map temporal-keys schemas))]
      (is (contains? ks :urpx/evaluatedOn))
      (doseq [[k t] ks]
        (is (= {"@type" t} (get emit/default-context (core/keyword->jsonld-key k)))
            (str k))))))

(deftest default-context-round-trips-as-an-added-key
  (testing "a plan loaded from an @graph bundle carries no :jsonld/context,
            so the default @context comes back as the only difference, which
            is why the README's round-trip passes {:context nil}"
    (let [original   (fixtures/load-rate-plan "pge-e-1-2026-03-01")
          re-coerced (-> (emit/write-rate-plan original)
                         java.io.StringReader.
                         core/parse-rate-plan
                         coerce/coerce-rate-plan)]
      (is (not (contains? original :jsonld/context)))
      (is (contains? re-coerced :jsonld/context))
      (is (= original (dissoc re-coerced :jsonld/context))))))

(deftest caller-supplied-context-overrides-default
  (testing "URPX-awl: passing :context replaces the default verbatim"
    (let [my-ctx {"urpx" "https://urpx.org/ns/ontology/"
                  "ex"   "https://example.com/test/"}
          parsed (json/read-str (emit/write-rate-plan minimal-rate-plan
                                                      {:context my-ctx}))]
      (is (= my-ctx (get parsed "@context"))))))

(deftest explicit-nil-context-suppresses-emission
  (testing "URPX-awl: :context nil emits no @context (used for clean
            round-trip tests where the original doesn't carry one)"
    (let [parsed (json/read-str (emit/write-rate-plan minimal-rate-plan
                                                      {:context nil}))]
      (is (not (contains? parsed "@context"))))))

(deftest existing-context-on-input-is-preserved
  (testing "URPX-awl: an entity carrying :jsonld/context keeps its own
            @context unchanged — the default doesn't clobber it"
    (let [bespoke-ctx {:urpx "https://urpx.org/ns/ontology/"
                       :ex   "https://example.com/preserved/"}
          plan-with-ctx (assoc minimal-rate-plan :jsonld/context bespoke-ctx)
          parsed (json/read-str (emit/write-rate-plan plan-with-ctx))]
      (is (= "https://example.com/preserved/" (get-in parsed ["@context" "ex"]))
          "the input's bespoke `:ex` prefix decl survives — default would not include it"))))

(deftest roundtrip-typed-leaves
  (testing "encoders preserve typed values across the full pipeline"
    (let [plan (assoc-in minimal-rate-plan
                         [:urpx/hasRatePlanVersion :urpx/hasPrices]
                         {:jsonld/type "urpx:Prices"
                          :urpx/hasPrice
                          [{:jsonld/type "urpx:Price"
                            :jsonld/id "x:price"
                            :urpx/unitPrice (BigDecimal. "0.15075")
                            :urpx/startDate (LocalDate/of 2026 1 1)
                            :urpx/referencesPriceDefinition
                            {:jsonld/id "x:pd"}}]})
          re-coerced (-> plan
                         emit/write-rate-plan
                         java.io.StringReader.
                         core/parse-rate-plan
                         coerce/coerce-rate-plan)
          price (-> re-coerced
                    (get-in [:urpx/hasRatePlanVersion :urpx/hasPrices :urpx/hasPrice])
                    first)]
      (is (= (BigDecimal. "0.15075") (:urpx/unitPrice price))
          "BigDecimal scale preserved")
      (is (= (LocalDate/of 2026 1 1) (:urpx/startDate price))
          "LocalDate round-trips"))))

;; ---------------------------------------------------------------------------
;; Vendored-fixture round-trip suite (URPX-739)
;; ---------------------------------------------------------------------------
;; The authoritative correctness check for the write side: every fixture
;; in `vendored-fixtures` must round-trip through
;; parse → coerce → emit → re-parse → re-coerce with structural equality.
;;
;; Each is a JSON-LD @graph bundling a base urpx:RatePlan with a
;; sibling urpx:RatePlanModifier (vintage-PCIA pattern). Both halves are
;; round-tripped independently. `:context nil` suppresses the URPX-awl
;; default @context — the inner RatePlan / RatePlanModifier entities don't
;; carry :jsonld/context (it's at the @graph wrapper level), so the default
;; would produce a present-but-not-in-original entry that breaks `=`.

(def vendored-fixtures
  ["pge-e-1-2026-03-01"
   "pge-e-tou-c-2026-03-01"
   "pge-e-tou-d-2026-03-27"
   "pge-ev2-2026-03-27"])

(defn- roundtrip-rate-plan [fixture-name]
  (let [original (fixtures/load-rate-plan fixture-name)
        emitted  (emit/write-rate-plan original {:context nil})
        re-coerced (-> emitted
                       java.io.StringReader.
                       core/parse-rate-plan
                       coerce/coerce-rate-plan)]
    [original re-coerced]))

(defn- roundtrip-rate-plan-modifier [fixture-name]
  (let [original (fixtures/load-modifier fixture-name)
        emitted  (emit/write-rate-plan-modifier original {:context nil})
        re-coerced (-> emitted
                       java.io.StringReader.
                       core/parse-rate-plan
                       coerce/coerce-rate-plan-modifier)]
    [original re-coerced]))

(deftest all-vendored-rate-plans-round-trip
  (testing "URPX-739: every vendored RatePlan round-trips through emit
            with structural equality (= original re-coerced)"
    (doseq [fixture-name vendored-fixtures]
      (testing fixture-name
        (let [[original re-coerced] (roundtrip-rate-plan fixture-name)]
          (is (= original re-coerced)
              (str "RatePlan round-trip failed for fixture " fixture-name)))))))

(deftest all-vendored-modifiers-round-trip
  (testing "URPX-739: every vendored RatePlanModifier (vintage-PCIA pattern)
            round-trips through emit with structural equality"
    (doseq [fixture-name vendored-fixtures]
      (testing fixture-name
        (let [[original re-coerced] (roundtrip-rate-plan-modifier fixture-name)]
          (is (= original re-coerced)
              (str "RatePlanModifier round-trip failed for fixture " fixture-name)))))))

(deftest the-urpx-72-scenario-cost-model-example-round-trips
  (let [raw     (core/parse-rate-plan (java.io.StringReader. fixtures/scenario-cost-model-example))
        model   (coerce/coerce-doc raw)
        reread  #(-> % java.io.StringReader. core/parse-rate-plan coerce/coerce-doc)]
    (is (nil? (coerce/explain schema/ScenarioCostModel model)))
    (is (= [{:jsonld/id "sce:cca-crs-2026-03-20-pa-vintage-2020"}]
           (:urpx/selectsProfileAlternative model)))
    (is (= model (reread (emit/write-scenario-cost-model model))))
    (testing "packaged in a urpx:URPXPackage"
      (let [pkg {:jsonld/type "urpx:URPXPackage"
                 :jsonld/id   "x:pkg"
                 :urpx/targetURPXVersion "https://example.com/urpx-v0.5.1"
                 :urpx/packages [(dissoc model :jsonld/context)]}
            back (reread (emit/write-urpx-package pkg {:context nil}))]
        (is (= pkg back))
        (is (nil? (coerce/explain schema/URPXPackage back)))))))

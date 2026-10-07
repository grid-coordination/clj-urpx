(ns urpx.coerce-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [urpx.coerce :as coerce]
            [urpx.core :as core]
            [urpx.schema :as schema]
            [urpx.test-fixtures :as fixtures])
  (:import [java.math BigDecimal]
           [java.time LocalDate LocalDateTime LocalTime]))

(def rate-plan-fixtures
  ["pge-e-1-2026-03-01"
   "pge-e-tou-c-2026-03-01"
   "pge-e-tou-d-2026-03-27"
   "pge-ev2-2026-03-27"])

(deftest coerce-primitives-via-schema
  (testing "Decimal: string -> BigDecimal"
    (is (= (BigDecimal. "0.23354")
           (coerce/coerce schema/Decimal "0.23354"))))
  (testing "Date: string -> LocalDate"
    (is (= (LocalDate/of 2026 1 1)
           (coerce/coerce schema/Date "2026-01-01"))))
  (testing "Time: string -> LocalTime"
    (is (= (LocalTime/of 16 0)
           (coerce/coerce schema/Time "16:00:00"))))
  (testing "DateTime: string -> LocalDateTime"
    (is (= (LocalDateTime/of 2026 1 1 0 0)
           (coerce/coerce schema/DateTime "2026-01-01T00:00:00"))))
  (testing "MonthNumber: '--06' -> 6"
    (is (= 6 (coerce/coerce schema/MonthNumber "--06")))
    (is (= 12 (coerce/coerce schema/MonthNumber "--12"))))
  (testing "already-typed values pass through unchanged"
    (let [bd (BigDecimal. "1.5")]
      (is (identical? bd (coerce/coerce schema/Decimal bd))))))

(deftest decimal-coerces-integral-numbers-and-exact-ratios
  (testing "an integral number, with scale 0"
    (doseq [[v bd] [[1 1M] [(biginteger 7) 7M] [3.0 3M] [(float 2.0) 2M] [-0.0 0M]]]
      (let [d (coerce/coerce schema/Decimal v)]
        (is (= [bd 0] [d (.scale ^BigDecimal d)]) (pr-str v)))))
  (testing "a terminating ratio"
    (is (= 1.5M (coerce/coerce schema/Decimal 3/2)))))

(defn- refusal [schema v]
  (try (coerce/coerce schema v)
       nil
       (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest decimal-strings-must-be-xsd-decimal-lexical-forms
  (testing "accepted"
    (doseq [[s bd] [["1" 1M] ["-1.50" -1.50M] ["+1.0" 1.0M] [".5" 0.5M] ["5." 5M]
                    ["-0.00009" -0.00009M] ["+0" 0M] ["-0.0" 0.0M] ["007.10" 7.10M]
                    ["-.5" -0.5M]]]
      (is (= bd (coerce/coerce schema/Decimal s)) s)))
  (testing "refused, naming the value"
    (doseq [s ["-9e-05" "1E3" "NaN" "INF" "-INF" " 1" "1 " "" "." "+" "1,5" "0x10" "\u0661"]]
      (is (= {:reason :urpx.schema/malformed-decimal :datatype "xsd:decimal" :value s}
             (refusal schema/Decimal s))
          (pr-str s))))
  (testing "a string in a parsed document is refused"
    (is (= :urpx.schema/malformed-decimal
           (:reason (refusal schema/Price
                             (core/parse-rate-plan
                              (java.io.StringReader.
                               "{\"@type\": \"urpx:Price\", \"urpx:unitPrice\": \"-9e-05\"}"))))))))

(deftest decimal-refuses-non-finite-doubles
  (doseq [v [##NaN ##Inf ##-Inf (float ##NaN)]]
    (let [{:keys [reason datatype value]} (refusal schema/Decimal v)]
      (is (= [:urpx.schema/malformed-decimal "xsd:decimal"] [reason datatype]) (pr-str v))
      (is (identical? v value) (pr-str v)))))

(deftest decimal-refuses-a-non-terminating-ratio
  (is (= 0.25M (coerce/coerce schema/Decimal 1/4)))
  (doseq [r [1/3 -2/7]]
    (is (= {:reason :urpx.schema/non-terminating-ratio :datatype "xsd:decimal" :value r}
           (refusal schema/Decimal r))
        (pr-str r))))

(deftest decimal-encodes-without-an-exponent
  (doseq [bd [1E-7M 1E+3M -1.5E-20M]]
    (let [s (coerce/encode schema/Decimal bd)]
      (is (re-matches #"-?[0-9]+(\.[0-9]+)?" s) (pr-str bd))
      (is (zero? (compare bd (coerce/coerce schema/Decimal s))) (pr-str bd)))))

(deftest decimal-refuses-a-number-json-ld-reads-as-xsd-double
  (testing "a non-integral number, or one of magnitude 1E21 or more"
    (doseq [v [0.1610 -0.5 (float 0.5) 1e-5 1e21 -1e22 (bigint 1e21)]]
      (is (= {:reason :urpx.schema/double-not-decimal :datatype "xsd:decimal" :value v}
             (refusal schema/Decimal v))
          (pr-str v))))
  (testing "the message gives the value as a string to write"
    (doseq [[v written] [[0.1610 "\"0.161\""] [(float 0.1) "\"0.1\""]]]
      (is (str/includes? (ex-message (try (coerce/coerce schema/Decimal v)
                                          (catch clojure.lang.ExceptionInfo e e)))
                         written)
          (pr-str v))))
  (testing "as the JSON reader gives it"
    (doseq [[s ok] [["0.1610" nil] ["1e-5" nil] ["1000000000000000000000" nil]
                    ["3" 3M] ["3.0" 3M] ["1e20" 100000000000000000000M]]]
      (if ok
        (is (= ok (coerce/coerce schema/Decimal (json/read-str s))) s)
        (is (= :urpx.schema/double-not-decimal
               (:reason (refusal schema/Decimal (json/read-str s))))
            s))))
  (testing "in a parsed document, bare and as a value object"
    (doseq [v ["0.1610" "{\"@value\": 0.1610, \"@type\": \"xsd:decimal\"}"]]
      (is (= :urpx.schema/double-not-decimal
             (:reason (refusal schema/Price
                               (core/parse-rate-plan
                                (java.io.StringReader.
                                 (str "{\"@type\": \"urpx:Price\", \"urpx:unitPrice\": " v "}"))))))
          v)))
  (testing "encoding refuses what decoding would"
    (is (= 3 (coerce/encode schema/Decimal 3)))
    (doseq [[v reason] [[0.5 :urpx.schema/double-not-decimal]
                        [{:jsonld/value 0.5 :jsonld/type "xsd:decimal"} :urpx.schema/double-not-decimal]
                        [1/3 :urpx.schema/non-terminating-ratio]]]
      (is (= reason
             (:reason (try (coerce/encode schema/Decimal v)
                           nil
                           (catch clojure.lang.ExceptionInfo e (ex-data e)))))
          (pr-str v))))
  (testing "encoding writes a ratio as a string, which JSON would write as a double"
    (is (= "0.25" (coerce/encode schema/Decimal 1/4)))
    (is (= {:jsonld/value "0.25" :jsonld/type "xsd:decimal"}
           (coerce/encode schema/Decimal {:jsonld/value 1/4 :jsonld/type "xsd:decimal"})))))

(deftest integer-strings-must-be-xsd-integer-lexical-forms
  (testing "accepted"
    (doseq [[s n] [["3" 3] ["+3" 3] ["-3" -3] ["007" 7]]]
      (is (= n (coerce/coerce schema/Int s)) s)))
  (testing "refused, naming the value"
    (doseq [s ["3.0" "1e3" " 3" "3 " "" "+" "NaN" "\u0661"]]
      (is (= {:reason :urpx.schema/malformed-integer :datatype "xsd:integer" :value s}
             (refusal schema/Int s))
          (pr-str s))))
  (testing "a valid xsd:integer outside the range of a long"
    (is (= {:reason :urpx.schema/integer-out-of-range
            :datatype "xsd:integer"
            :value "9223372036854775808"}
           (refusal schema/Int "9223372036854775808")))))

(deftest coerce-pge-e-1
  (let [plan (fixtures/load-rate-plan "pge-e-1-2026-03-01")]

    (testing "the full plan validates against the RatePlan schema"
      (is (nil? (coerce/explain schema/RatePlan plan))))

    (testing "v0.2.0: ratePlanUniqueIdentifier is the unique-id field"
      (is (= "E-1" (:urpx/ratePlanUniqueIdentifier plan))))

    (testing "top-level effective date is a LocalDate"
      (is (= (LocalDate/of 2001 1 1) (:urpx/initialEffectiveDate plan))))

    (testing "RatePlanVersion's effectiveDateTime is a LocalDateTime"
      (is (= (LocalDateTime/of 2026 3 1 0 0)
             (get-in plan [:urpx/hasRatePlanVersion :urpx/effectiveDateTime]))))

    (testing "Seasons have integer months (gMonth decoded)"
      (let [seasons (get-in plan [:urpx/hasRatePlanVersion
                                  :urpx/hasPlanElements
                                  :urpx/hasSeasons])
            summer  (first (filter #(= "Summer" (:urpx/seasonName %)) seasons))]
        (is (= [6 7 8 9] (:urpx/month summer)))))

    (testing "BillingModel.hasPriceDefinition (v0.2.0 location) is a populated vector"
      (let [pds (get-in plan [:urpx/hasRatePlanVersion
                              :urpx/hasPlanElements
                              :urpx/hasBillingModel
                              :urpx/hasPriceDefinition])]
        (is (vector? pds))
        (is (pos? (count pds)))
        (is (every? #(some? (get-in % [:urpx/referencesLedger :jsonld/id])) pds)
            "every PriceDefinition points to its parent Ledger via referencesLedger")))

    (testing "ServiceTiers (v0.2.0 plural container) holds the tier vector.
              hasServiceTiers is itself a vector — walk it via
              (mapcat :urpx/hasServiceTier ...)."
      (let [tiers (mapcat :urpx/hasServiceTier
                          (get-in plan [:urpx/hasRatePlanVersion
                                        :urpx/hasPlanElements
                                        :urpx/hasBillingModel
                                        :urpx/hasServiceTiers]))]
        (is (= 6 (count tiers))
            "PG&E E-1 has 6 service tiers (Summer/Winter × Tier 1/2/2C)")
        ;; Per upstream urpx 423e83f, bounds live on ServiceBracket(s)
        ;; attached via ServiceTier.hasServiceBracket. (first ...) reads the
        ;; bound because each of this fixture's tiers carries exactly one
        ;; bracket: a property of pge-e-1, not of the fixtures (one fixture's
        ;; tiers carry 40 brackets each) and not of the resolver, where
        ;; select-tier-bracket does the choosing.
        (is (every? #(instance? BigDecimal
                                (:urpx/lowerBound (first (:urpx/hasServiceBracket %))))
                    tiers))))

    (testing "Prices have BigDecimal unitPrice"
      (let [price-sets (get-in plan [:urpx/hasRatePlanVersion :urpx/hasPrices :urpx/hasPriceSet])
            generation (->> price-sets
                            (mapcat :urpx/hasPrice)
                            (some #(when (= "Generation" (:urpx/name %)) %)))]
        (is (= (BigDecimal. "0.12855") (:urpx/unitPrice generation)))))

    (testing "CalculationMethod intervalDuration parses to Period"
      (let [pds (get-in plan [:urpx/hasRatePlanVersion
                              :urpx/hasPlanElements
                              :urpx/hasBillingModel
                              :urpx/hasPriceDefinition])
            base-charge (some #(when (= "Base Services Charge" (:urpx/name %)) %) pds)
            calc (:urpx/hasCalculationMethod base-charge)]
        (is (some? calc))
        (is (= (java.time.Period/parse "P1D") (:urpx/intervalDuration calc)))))))

(deftest coerce-condition-expression-tree
  (testing "ConditionExpression in EligibilityRule decodes operand value types"
    (let [plan (fixtures/load-rate-plan "pge-e-1-2026-03-01")
          rules (get-in plan [:urpx/hasRatePlanVersion :urpx/hasEligibilityRule])
          rule (first rules)
          ce (:urpx/hasConditionExpression rule)
          root (:urpx/rootNode ce)]
      (is (= "urpx:ComparisonNode" (:jsonld/type root)))
      (is (= {:jsonld/id "urpx:hasCustomerKind"}
             (get-in root [:urpx/leftOperand :urpx/conditionProperty]))))))

(deftest all-rate-plan-fixtures-validate
  (testing "every URPX RatePlan fixture coerces and validates against the v0.2.0 schema"
    (doseq [name rate-plan-fixtures]
      (let [plan (fixtures/load-rate-plan name)
            err (coerce/explain schema/RatePlan plan)]
        (is (nil? err)
            (str name " should validate; got " (count (:errors err)) " errors"))))))

(deftest all-modifier-fixtures-validate
  (testing "every fixture's bundled RatePlanModifier coerces and validates"
    (doseq [name rate-plan-fixtures]
      (let [modifier (fixtures/load-modifier name)
            err (coerce/explain schema/RatePlanModifier modifier)]
        (is (nil? err)
            (str name "/modifier should validate; got "
                 (count (:errors err)) " errors"))))))

(deftest coerce-doc-dispatches-on-type
  (testing "coerce-doc handles RatePlan, RatePlanModifier, and unknowns"
    ;; v0.5.1 embeds the plan in a urpx:URPXPackage that sits inside the
    ;; @graph, so the wrapper has to be descended to reach either entity.
    (let [entities (mapcat core/expand-document-entities
                           (fixtures/load-graph "pge-e-1-2026-03-01"))
          plan (some #(when (= "urpx:RatePlan" (:jsonld/type %)) %) entities)
          mod  (some #(when (= "urpx:RatePlanModifier" (:jsonld/type %)) %) entities)]
      (is (nil? (coerce/explain schema/RatePlan (coerce/coerce-doc plan))))
      (is (nil? (coerce/explain schema/RatePlanModifier (coerce/coerce-doc mod))))
      (is (nil? (coerce/coerce-doc {:jsonld/type "urpx:Unknown"})))))

  (testing "applicableToRatePlan (single map upstream) is normalized to a vector"
    (let [m (fixtures/load-modifier "pge-e-1-2026-03-01")
          refs (get-in m [:urpx/hasRatePlanModifierVersion :urpx/applicableToRatePlan])]
      (is (vector? refs))
      (is (= "pge:e-1" (:jsonld/id (first refs)))))))

(deftest core-load-rate-plan-finds-unique-rate-plan-in-graph
  (testing "load-rate-plan picks the urpx:RatePlan out of an @graph bundle"
    (let [path (.getPath (fixtures/fixture-resource "pge-e-1-2026-03-01"))
          plan (core/load-rate-plan path)]
      (is (= "urpx:RatePlan" (:jsonld/type plan)))
      (is (= "pge:e-1" (:jsonld/id plan))))))

;; ---------------------------------------------------------------------------
;; URPXDocument wrapper (Decision #103)
;; ---------------------------------------------------------------------------

(def ^:private valid-urpx-document-raw
  {:jsonld/type "urpx:URPXDocument"
   :jsonld/id   "ex:doc"
   :urpx/targetURPXVersion
   "https://github.com/urpx-org/urpx/raw/v0.2.0/ontology/urpx-ontology.ttl"
   :urpx/hasRatePlan
   {:jsonld/type "urpx:RatePlan" :urpx/timezoneIdentifier "America/Los_Angeles" :jsonld/id "ex:plan"}})

(deftest urpx-document-validates-with-target-version
  (testing "a URPXDocument carrying urpx:targetURPXVersion validates"
    (let [coerced (coerce/coerce-urpx-document valid-urpx-document-raw)]
      (is (nil? (coerce/explain schema/URPXDocument coerced)))
      (testing "single hasRatePlan map is normalized to a vector"
        (is (vector? (:urpx/hasRatePlan coerced)))
        (is (= 1 (count (:urpx/hasRatePlan coerced))))))))

(deftest urpx-document-rejected-when-target-version-missing
  (testing "Decision #103: missing urpx:targetURPXVersion fails
            Malli validation, so a retrofitted filing must add the field."
    (let [no-version (dissoc valid-urpx-document-raw :urpx/targetURPXVersion)
          coerced (coerce/coerce-urpx-document no-version)
          err (coerce/explain schema/URPXDocument coerced)]
      (is (some? err) "validation must fail")
      (is (some #(= [:urpx/targetURPXVersion] (:in %)) (:errors err))
          "the explanation must point at urpx:targetURPXVersion"))))

(deftest urpx-document-rejected-when-target-version-not-string
  (testing "targetURPXVersion must be a string (xsd:anyURI in SHACL)"
    (let [bad (assoc valid-urpx-document-raw :urpx/targetURPXVersion 42)
          err (coerce/explain schema/URPXDocument
                              (coerce/coerce-urpx-document bad))]
      (is (some? err)))))

(deftest coerce-doc-dispatches-on-urpx-document
  (testing "coerce-doc routes urpx:URPXDocument through coerce-urpx-document"
    (let [coerced (coerce/coerce-doc valid-urpx-document-raw)]
      (is (nil? (coerce/explain schema/URPXDocument coerced))))))

;; ---------------------------------------------------------------------------
;; URPXPackage wrapper (v0.3.0 rename of URPXDocument)
;; ---------------------------------------------------------------------------

(def ^:private valid-urpx-package-raw
  {:jsonld/type "urpx:URPXPackage"
   :jsonld/id   "ex:pkg"
   :urpx/targetURPXVersion
   "https://github.com/urpx-org/urpx/raw/v0.5.1/ontology/urpx-ontology.ttl"
   :urpx/packages
   {:jsonld/type "urpx:RatePlan" :urpx/timezoneIdentifier "America/Los_Angeles" :jsonld/id "ex:plan"}})

(deftest urpx-package-validates-with-target-version
  (testing "a URPXPackage carrying urpx:targetURPXVersion validates"
    (let [coerced (coerce/coerce-urpx-package valid-urpx-package-raw)]
      (is (nil? (coerce/explain schema/URPXPackage coerced)))
      (testing "single urpx:packages map is normalized to a vector"
        (is (vector? (:urpx/packages coerced)))
        (is (= 1 (count (:urpx/packages coerced))))))))

(deftest urpx-package-rejected-when-target-version-missing
  (testing "targetURPXVersion stays required on URPXPackage (sh:minCount 1 in
            urpx-shacl:URPXPackageShape), as it was on URPXDocument"
    (let [no-version (dissoc valid-urpx-package-raw :urpx/targetURPXVersion)
          err (coerce/explain schema/URPXPackage
                              (coerce/coerce-urpx-package no-version))]
      (is (some? err) "validation must fail")
      (is (some #(= [:urpx/targetURPXVersion] (:in %)) (:errors err))
          "the explanation must point at urpx:targetURPXVersion"))))

(deftest coerce-doc-dispatches-both-wrapper-vocabularies
  (testing "coerce-doc routes urpx:URPXPackage through coerce-urpx-package while
            urpx:URPXDocument keeps routing to coerce-urpx-document, so a corpus
            part-way through a migration coerces either way"
    (is (nil? (coerce/explain schema/URPXPackage
                              (coerce/coerce-doc valid-urpx-package-raw))))
    (is (nil? (coerce/explain schema/URPXDocument
                              (coerce/coerce-doc valid-urpx-document-raw))))))

(deftest g-month-strings-must-be-xsd-g-month-lexical-forms
  (testing "accepted, the timezone dropped"
    (doseq [[s n] [["--01" 1] ["--12" 12] ["--06Z" 6] ["--06-08:00" 6] ["--06+14:00" 6]]]
      (is (= n (coerce/coerce schema/MonthNumber s)) s)))
  (testing "refused, naming the value"
    (doseq [s ["--+6" "--6" "--13" "--00" "--1e3" "6" "06" "--06 " "--06+15:00"
               "--\u0666" "--0\u0666"]]
      (is (= {:reason :urpx.schema/malformed-g-month :datatype "xsd:gMonth" :value s}
             (refusal schema/MonthNumber s))
          (pr-str s)))))

(deftest numeric-value-objects-unwrap
  (testing "a value object of the field's datatype decodes by its @value"
    (is (= 0.1M (coerce/coerce schema/Decimal {:jsonld/value "0.1" :jsonld/type "xsd:decimal"})))
    (is (= 2 (.scale ^BigDecimal (coerce/coerce schema/Decimal
                                                {:jsonld/value "0.10"
                                                 :jsonld/type "http://www.w3.org/2001/XMLSchema#decimal"}))))
    (is (= 3 (coerce/coerce schema/Int {:jsonld/value "3" :jsonld/type "xsd:integer"})))
    (is (= 3 (coerce/coerce schema/Int {:jsonld/value 3 :jsonld/type "xsd:integer"}))))
  (testing "the @value is held to the datatype's lexical space"
    (is (= :urpx.schema/malformed-decimal
           (:reason (refusal schema/Decimal {:jsonld/value "1E3" :jsonld/type "xsd:decimal"}))))
    (is (= :urpx.schema/malformed-integer
           (:reason (refusal schema/Int {:jsonld/value "3.0" :jsonld/type "xsd:integer"})))))
  (testing "a value object of another datatype, of none, or with an @language,
            stays as written and fails validation"
    (doseq [[s v] [[schema/Decimal {:jsonld/value "0.1" :jsonld/type "xsd:double"}]
                   [schema/Decimal {:jsonld/value "0.1"}]
                   [schema/Decimal {:jsonld/value "0.1" :jsonld/type "xsd:decimal" :jsonld/language "en"}]
                   [schema/Int {:jsonld/value "3" :jsonld/type "xsd:decimal"}]]]
      (is (= v (coerce/coerce s v)) (pr-str v))
      (is (not (coerce/validate s v)) (pr-str v))))
  (testing "in a parsed document"
    (let [doc (core/parse-rate-plan
               (java.io.StringReader.
                (str "{\"@type\": \"urpx:Price\", \"urpx:unitPrice\":"
                     " {\"@value\": \"0.1610\", \"@type\": \"xsd:decimal\"}}")))
          bd (:urpx/unitPrice (coerce/coerce schema/Price doc))]
      (is (= [0.1610M 4] [bd (.scale ^BigDecimal bd)])))))

;; ---------------------------------------------------------------------------
;; Encoder spike — verify malli's transformer system handles
;; bidirectional encode + decode on a single schema without duplication
;; ---------------------------------------------------------------------------

(deftest encoder-spike-decimal-forward
  (testing "encoding a typed BigDecimal yields the JSON-LD string form"
    (is (= "0.15075"
           (coerce/encode schema/Decimal (BigDecimal. "0.15075"))))))

(deftest encoder-spike-decimal-roundtrip
  (testing "decode → encode → decode is a fixed point — same schema,
            same transformer, no parallel encode-only schemas needed."
    (let [original (BigDecimal. "0.15075")
          encoded  (coerce/encode schema/Decimal original)
          decoded  (coerce/coerce schema/Decimal encoded)]
      (is (string? encoded))
      (is (instance? BigDecimal decoded))
      (is (= original decoded)
          "BigDecimal equality is scale-sensitive; .toPlainString must preserve scale"))))

(deftest encoder-spike-decimal-passthrough
  (testing "encoding an already-string value passes through unchanged
            (mirror of the decoder's already-typed pass-through)"
    (is (= "0.15075"
           (coerce/encode schema/Decimal "0.15075")))))

;; ---------------------------------------------------------------------------
;; Encoders for the rest of the schema primitives
;; ---------------------------------------------------------------------------

(deftest encode-primitives-via-schema
  (testing "Date: LocalDate → ISO date"
    (is (= "2026-01-01"
           (coerce/encode schema/Date (LocalDate/of 2026 1 1)))))
  (testing "Time: LocalTime → 'HH:mm:ss', which xsd:time requires"
    (is (= "16:00:00"
           (coerce/encode schema/Time (LocalTime/of 16 0)))))
  (testing "DateTime: LocalDateTime → 'yyyy-MM-ddTHH:mm:ss', which xsd:dateTime requires"
    (is (= "2026-01-01T00:00:00"
           (coerce/encode schema/DateTime (LocalDateTime/of 2026 1 1 0 0)))))
  (testing "MonthNumber: int → '--MM' (zero-padded)"
    (is (= "--06" (coerce/encode schema/MonthNumber 6)))
    (is (= "--12" (coerce/encode schema/MonthNumber 12))))
  (testing "already-string values pass through unchanged"
    (is (= "2026-01-01" (coerce/encode schema/Date "2026-01-01")))
    (is (= "--05"       (coerce/encode schema/MonthNumber "--05")))))

(deftest encode-roundtrip-primitives
  (testing "every primitive's decode → encode → decode is a fixed point"
    (let [cases [[schema/Decimal     (BigDecimal. "0.15075")]
                 [schema/Date        (LocalDate/of 2026 3 1)]
                 [schema/Time        (LocalTime/of 16 30)]
                 [schema/DateTime    (LocalDateTime/of 2026 3 1 16 30)]
                 [schema/MonthNumber 6]
                 [schema/IsoDuration (java.time.Duration/parse "PT1H30M")]
                 [schema/IsoDuration (java.time.Period/parse "P1M")]]]
      (doseq [[s original] cases]
        (let [encoded (coerce/encode s original)
              decoded (coerce/coerce s encoded)]
          (is (string? encoded) (str s ": encoded form is a string"))
          (is (= original decoded) (str s ": round-trip preserves the typed value")))))))

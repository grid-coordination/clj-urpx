(ns urpx.emit
  "Write-side: emit URPX JSON-LD from coerced Clojure entities.

  Symmetric to the existing parse + coerce path. Given a coerced
  urpx:RatePlan / urpx:RatePlanModifier / urpx:URPXPackage
  / urpx:ScenarioCostModel / urpx:ProgramEnrollment (or an EDN map shaped
  like one with namespaced-keyword keys + already-string values),
  emit a SHACL-valid JSON-LD string ready to write to disk or send to a
  consumer.

  Pipeline (per call):

    1. urpx.coerce/encode runs the schema's :encode/urpx-jsonld transformer
       — turns BigDecimal / java.time values / Duration / int-month back
       into the JSON-LD string forms (xsd:decimal, xsd:date, xsd:dateTime,
       xsd:time, xsd:duration, xsd:gMonth, xsd:gYear).
       Already-string values pass through unchanged.
    2. urpx.core/keys->jsonld walks the result, converting namespaced
       keyword keys back to their CURIE / @-reserved JSON-LD forms.
    3. Every object's keys are put in one order: @context, @id, @type,
       other @ keywords, then properties, each group in string order. The
       bytes written depend on the data and not on the order its maps were
       built in.
    4. clojure.data.json/write-str serializes to JSON, with any `:json`
       options from opts, e.g. {:indent true :escape-slash false}.

  Round-trip success bar: logical/RDF-graph equivalence
  (parse → coerce → write → re-parse → re-coerce yields a structurally
  equal entity). Byte-for-byte equality with the input is NOT a goal —
  key ordering, single-vs-vector property emission, and @context
  recreation may differ from the source document. urpx.emit-test pins the
  `=`-equality contract over the PG&E @graph bundles in its
  `vendored-fixtures`.

  @context emission: every write-* function accepts an optional opts map
  with `:context`. Three states:

    - opts omits :context (or doesn't pass opts) → emit `default-context`
      (URPX prefix declarations + typed-property declarations matching what
      vendored fixtures carry).
    - opts has `:context map` → emit that map verbatim as @context.
    - opts has `:context nil` → emit no @context (used by round-trip tests
      where the input doesn't carry one and `=`-equality would mismatch).

  An @context already on the input entity (`:jsonld/context`, e.g. from
  a parsed document) is preserved untouched — the default never clobbers
  a context the consumer already supplied."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [urpx.coerce :as coerce]
            [urpx.core :as core]
            [urpx.index :as index]
            [urpx.schema :as schema]))

;; ---------------------------------------------------------------------------
;; Default JSON-LD @context
;; ---------------------------------------------------------------------------

(def default-context
  "Baseline @context emitted by the write-* functions when the caller
   doesn't supply one (and the input entity doesn't already carry a
   :jsonld/context). Covers:

     - the canonical URPX prefix declarations (urpx, schema, xsd)
     - typed-property declarations for every URPX field whose schema has a
       :decode/urpx-jsonld decoder

   Mirrors the shape that vendored fixtures (PG&E, CPAU, URPX upstream
   test cases) carry, so emitted JSON-LD parses cleanly with strict
   JSON-LD processors that rely on @context for type resolution.

   Example-namespace prefixes (org, pge, ex, …) are intentionally omitted
   — those are corpus-specific. Callers who use such prefixes in @id
   strings should provide their own :context map via the write-* opts."
  {"urpx"   "https://urpx.org/ns/ontology/"
   "schema" "http://schema.org/"
   "xsd"    "http://www.w3.org/2001/XMLSchema#"

   ;; xsd:decimal
   "urpx:unitPrice"        {"@type" "xsd:decimal"}
   "urpx:amount"           {"@type" "xsd:decimal"}
   "urpx:basePrice"        {"@type" "xsd:decimal"}
   "urpx:currentPrice"     {"@type" "xsd:decimal"}
   "urpx:adder"            {"@type" "xsd:decimal"}
   "urpx:multiplier"       {"@type" "xsd:decimal"}
   "urpx:baseQuantity"     {"@type" "xsd:decimal"}
   "urpx:lowerBound"       {"@type" "xsd:decimal"}
   "urpx:upperBound"       {"@type" "xsd:decimal"}
   "urpx:lowerBoundOffset" {"@type" "xsd:decimal"}
   "urpx:upperBoundOffset" {"@type" "xsd:decimal"}
   "urpx:decimalValue"     {"@type" "xsd:decimal"}

   ;; xsd:date
   "urpx:effectiveDate"        {"@type" "xsd:date"}
   "urpx:expirationDate"       {"@type" "xsd:date"}
   "urpx:initialEffectiveDate" {"@type" "xsd:date"}
   "urpx:startDate"            {"@type" "xsd:date"}
   "urpx:endDate"              {"@type" "xsd:date"}
   "urpx:datePublished"        {"@type" "xsd:date"}
   "urpx:dateApproved"         {"@type" "xsd:date"}
   "urpx:dateCreated"          {"@type" "xsd:date"}
   "urpx:dateRetrieved"        {"@type" "xsd:date"}
   "urpx:dateReceived"         {"@type" "xsd:date"}
   "urpx:expectedEndDate"      {"@type" "xsd:date"}
   "urpx:holidayDate"          {"@type" "xsd:date"}
   "urpx:dateValue"            {"@type" "xsd:date"}
   "urpx:dateModified"         {"@type" "xsd:date"}
   "urpx:cancelledOn"          {"@type" "xsd:date"}
   "urpx:enrollmentStartDate"  {"@type" "xsd:date"}
   "urpx:enrollmentEndDate"    {"@type" "xsd:date"}

   ;; xsd:dateTime
   "urpx:effectiveDateTime"   {"@type" "xsd:dateTime"}
   "urpx:expirationDateTime"  {"@type" "xsd:dateTime"}
   "urpx:startDateTime"       {"@type" "xsd:dateTime"}
   "urpx:endDateTime"         {"@type" "xsd:dateTime"}
   "urpx:dateTimePublished"   {"@type" "xsd:dateTime"}
   "urpx:dateTimeApproved"    {"@type" "xsd:dateTime"}
   "urpx:dateTimeCreated"     {"@type" "xsd:dateTime"}
   "urpx:dateTimeReceived"    {"@type" "xsd:dateTime"}
   "urpx:expectedEndDateTime" {"@type" "xsd:dateTime"}
   "urpx:dateTimeValue"       {"@type" "xsd:dateTime"}
   "urpx:dateTimeModified"    {"@type" "xsd:dateTime"}
   "urpx:cancelledOnDateTime" {"@type" "xsd:dateTime"}
   "urpx:evaluatedOn"         {"@type" "xsd:dateTime"}
   "urpx:identityValidFrom"   {"@type" "xsd:dateTime"}
   "urpx:identityValidTo"     {"@type" "xsd:dateTime"}
   "urpx:contractEffectiveDate"  {"@type" "xsd:dateTime"}
   "urpx:contractExpirationDate" {"@type" "xsd:dateTime"}

   ;; xsd:time
   "urpx:startTime"  {"@type" "xsd:time"}
   "urpx:endTime"    {"@type" "xsd:time"}
   "urpx:timeValue"  {"@type" "xsd:time"}

   ;; xsd:duration
   "urpx:intervalDuration"   {"@type" "xsd:duration"}
   "urpx:settlementInterval" {"@type" "xsd:duration"}
   "urpx:minimumTenure"      {"@type" "xsd:duration"}
   "urpx:durationValue"      {"@type" "xsd:duration"}

   ;; xsd:gMonth
   "urpx:month"       {"@type" "xsd:gMonth"}
   "urpx:gMonthValue" {"@type" "xsd:gMonth"}

   ;; xsd:gYear
   "urpx:gYearValue" {"@type" "xsd:gYear"}

   ;; xsd:integer
   "urpx:touTierNumber"     {"@type" "xsd:integer"}
   "urpx:touPeriodNumber"   {"@type" "xsd:integer"}
   "urpx:serviceTierNumber" {"@type" "xsd:integer"}
   "urpx:integerValue"      {"@type" "xsd:integer"}
   "urpx:pageStart"         {"@type" "xsd:integer"}
   "urpx:pageEnd"           {"@type" "xsd:integer"}

   ;; xsd:anyURI
   "urpx:url"                {"@type" "xsd:anyURI"}
   "urpx:accessURL"          {"@type" "xsd:anyURI"}
   "urpx:targetURPXVersion"  {"@type" "xsd:anyURI"}
   "urpx:constraintProperty" {"@type" "xsd:anyURI"}

   ;; xsd:boolean
   "urpx:yearlyRecurrence"   {"@type" "xsd:boolean"}
   "urpx:booleanValue"       {"@type" "xsd:boolean"}
   "urpx:isDefault"          {"@type" "xsd:boolean"}
   "urpx:includeHolidays"    {"@type" "xsd:boolean"}
   "urpx:includeNonHolidays" {"@type" "xsd:boolean"}})

(defn- attach-context
  "If the entity already carries an @context (from round-tripping parsed
   data), leave it. Otherwise resolve the caller's :context preference:
   absent → default; explicit map → that; explicit nil → no @context."
  [json-keyed-map opts]
  (cond
    (contains? json-keyed-map "@context")
    json-keyed-map

    (and (contains? opts :context) (nil? (:context opts)))
    json-keyed-map

    (:context opts)
    (assoc json-keyed-map "@context" (:context opts))

    :else
    (assoc json-keyed-map "@context" default-context)))

(defn- key-order
  "@context, @id and @type first, then other @ keywords, then properties,
   each group in string order."
  [k]
  [(case k
     "@context" 0
     "@id" 1
     "@type" 2
     (if (str/starts-with? k "@") 3 4))
   k])

(defn- canonical
  "`x` with every object's keys in `key-order`."
  [x]
  (cond
    (map? x)        (into (sorted-map-by #(compare (key-order %1) (key-order %2)))
                          (map (fn [[k v]] [k (canonical v)]))
                          x)
    (sequential? x) (mapv canonical x)
    :else           x))

(defn- write-json
  "`data` as a JSON string, keys in `key-order`, with opts' `:json` options
   passed to clojure.data.json/write-str."
  [data opts]
  (apply json/write-str (canonical data) (mapcat identity (:json opts))))

(defn- emit-via-schema
  "Encode `entity` via `schema`, project keys back to JSON-LD form, attach
   @context per `opts`, and serialize to a JSON-LD string. Internal helper
   for the per-entity public functions below."
  [schema entity opts]
  (-> (coerce/encode schema entity)
      core/keys->jsonld
      (attach-context opts)
      (write-json opts)))

(defn write-rate-plan
  "Serialize a coerced urpx:RatePlan to a JSON-LD string. Inverse of the
   urpx.core/load-rate-plan + urpx.coerce/coerce-rate-plan pipeline.
   Round-trip preserves logical/RDF-graph equivalence.

   Optional `opts` keys:
     :context — JSON-LD @context map (default: `default-context`); pass
                `nil` to suppress @context emission entirely. An @context
                already present on the input entity (e.g. from a parsed
                document) is preserved untouched."
  ([rate-plan] (write-rate-plan rate-plan nil))
  ([rate-plan opts]
   (emit-via-schema schema/RatePlan rate-plan opts)))

(defn write-rate-plan-modifier
  "Serialize a coerced urpx:RatePlanModifier to a JSON-LD string. Inverse of
   urpx.core/load-rate-plan-modifier + urpx.coerce/coerce-rate-plan-modifier.

   See `write-rate-plan` for `opts`."
  ([modifier] (write-rate-plan-modifier modifier nil))
  ([modifier opts]
   (emit-via-schema schema/RatePlanModifier modifier opts)))

(defn write-scenario-cost-model
  "Serialize a coerced urpx:ScenarioCostModel to a JSON-LD string. Inverse of
   urpx.coerce/coerce-scenario-cost-model.

   See `write-rate-plan` for `opts`."
  ([model] (write-scenario-cost-model model nil))
  ([model opts]
   (emit-via-schema schema/ScenarioCostModel model opts)))

(defn write-program-enrollment
  "Serialize a coerced urpx:ProgramEnrollment to a JSON-LD string. Inverse of
   urpx.coerce/coerce-doc on a urpx:ProgramEnrollment node.

   See `write-rate-plan` for `opts`."
  ([enrollment] (write-program-enrollment enrollment nil))
  ([enrollment opts]
   (emit-via-schema schema/ProgramEnrollment enrollment opts)))

(defn- pre-encode-payloads
  "A wrapper's payload-pointer schemas are deliberately loose ([:vector :map])
   — wrapper-level validation only, per the URPXPackage schema design. That
   means malli's wrapper encoder won't recurse into inner entities to convert
   their typed leaves (BigDecimal, LocalDate, …) back to JSON-LD strings.
   Pre-encode each embedded RatePlan / RatePlanModifier through its
   own schema before handing the wrapper to the encoder. Refs
   (:jsonld/id-only maps) survive intact — encoding skips fields the entity
   doesn't carry."
  [document]
  ;; Each payload goes through index/as-vec before mapv. These are 0..N and a
  ;; wrapper writing one document writes a bare object, which every vendored
  ;; wrapper does; mapv over that map produced MapEntry pairs and emitted
  ;; "urpx:hasRatePlan":[["type","urpx:RatePlan"],["id","pge:e-tou-c"],...],
  ;; a corrupt artifact with the @ prefixes stripped and no exception. Worse
  ;; than the read-side defects of the same class because the output leaves the
  ;; process.
  (cond-> document
    (:urpx/hasRatePlan document)
    (update :urpx/hasRatePlan
            (fn [vs] (mapv #(coerce/encode schema/RatePlan %) (index/as-vec vs))))

    (:urpx/hasRatePlanModifier document)
    (update :urpx/hasRatePlanModifier
            (fn [vs] (mapv #(coerce/encode schema/RatePlanModifier %) (index/as-vec vs))))

    (:urpx/packages document)
    (update :urpx/packages
            (fn [vs] (mapv (fn [v]
                             (if-let [sch (schema/schema-for-type (:jsonld/type v))]
                               (coerce/encode sch v)
                               v))
                           (index/as-vec vs))))))

(defn write-urpx-package
  "Serialize a coerced urpx:URPXPackage wrapper to a JSON-LD string. Inverse of
   urpx.coerce/coerce-urpx-package. Embedded urpx:hasRatePlan /
   urpx:hasRatePlanModifier payloads are pre-encoded through their own schemas,
   as is each urpx:packages entry whose @type `urpx.schema` registers; other
   entries, such as bare @id references, are emitted as-is.

   See `write-rate-plan` for `opts`."
  ([package] (write-urpx-package package nil))
  ([package opts]
   (emit-via-schema schema/URPXPackage (pre-encode-payloads package) opts)))

(defn- encode-entity
  "`entity` encoded through the schema `urpx.schema` registers for its @type,
   with JSON-LD keys. Refuses an entity whose @type has no schema."
  [entity]
  (let [t   (:jsonld/type entity)
        sch (schema/schema-for-type t)]
    (when-not sch
      (throw (ex-info (str "No schema to emit a " (pr-str t))
                      {:type t :id (:jsonld/id entity)})))
    (core/keys->jsonld
     (coerce/encode sch (cond-> entity
                          (= "urpx:URPXPackage" t) pre-encode-payloads)))))

(defn write-entity
  "Serialize any coerced entity whose @type `urpx.schema` registers to a
   JSON-LD string, encoded through that type's schema. A urpx:URPXPackage has
   its payloads encoded as `write-urpx-package` does. Refuses an @type with no
   schema.

   See `write-rate-plan` for `opts`."
  ([entity] (write-entity entity nil))
  ([entity opts]
   (write-json (attach-context (encode-entity entity) opts) opts)))

(defn write-graph
  "Serialize several coerced top-level entities as one JSON-LD document,
   `{\"@context\" … \"@graph\" [ … ]}`, each encoded as `write-entity`
   encodes it. The @context is placed on the document, per `opts` as in
   `write-rate-plan`; an entity's own @context is kept on its node."
  ([entities] (write-graph entities nil))
  ([entities opts]
   (write-json (attach-context {"@graph" (mapv encode-entity entities)} opts) opts)))

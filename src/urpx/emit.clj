(ns urpx.emit
  "Write-side: emit URPX JSON-LD from coerced Clojure entities.

  Symmetric to the existing parse + coerce path. Given a coerced
  urpx:RatePlan / urpx:RatePlanModifier / urpx:URPXDocument (or an EDN
  map shaped like one with namespaced-keyword keys + already-string values),
  emit a SHACL-valid JSON-LD string ready to write to disk or send to a
  consumer.

  Pipeline (per call):

    1. urpx.coerce/encode runs the schema's :encode/urpx-jsonld transformer
       — turns BigDecimal / LocalDate / LocalDateTime / LocalTime / Duration
       / int-month back into the JSON-LD string forms (xsd:decimal,
       xsd:date, xsd:dateTime, xsd:time, xsd:duration, xsd:gMonth).
       Already-string values pass through unchanged.
    2. urpx.core/keys->jsonld walks the result, converting namespaced
       keyword keys back to their CURIE / @-reserved JSON-LD forms.
    3. clojure.data.json/write-str serializes to JSON.

  Round-trip success bar (URPX-nvo): logical/RDF-graph equivalence
  (parse → coerce → write → re-parse → re-coerce yields a structurally
  equal entity). Byte-for-byte equality with the input is NOT a goal —
  key ordering, single-vs-vector property emission, and @context
  recreation may differ from the source document.

  Out of scope here:
    - @context emission policy (URPX-awl)
    - Vendored-fixture round-trip suite (URPX-739)
    - README documentation (URPX-m9p)"
  (:require [clojure.data.json :as json]
            [urpx.coerce :as coerce]
            [urpx.core :as core]
            [urpx.schema :as schema]))

(defn- emit-via-schema
  "Encode `entity` via `schema`, project keys back to JSON-LD form, and
   serialize to a JSON-LD string. Internal helper for the per-entity
   public functions below."
  [schema entity]
  (-> (coerce/encode schema entity)
      core/keys->jsonld
      json/write-str))

(defn write-rate-plan
  "Serialize a coerced urpx:RatePlan to a JSON-LD string. Inverse of the
   urpx.core/load-rate-plan + urpx.coerce/coerce-rate-plan pipeline.
   Round-trip preserves logical/RDF-graph equivalence."
  [rate-plan]
  (emit-via-schema schema/RatePlan rate-plan))

(defn write-rate-plan-modifier
  "Serialize a coerced urpx:RatePlanModifier to a JSON-LD string. Inverse of
   urpx.core/load-rate-plan-modifier + urpx.coerce/coerce-rate-plan-modifier."
  [modifier]
  (emit-via-schema schema/RatePlanModifier modifier))

(defn- pre-encode-payloads
  "URPXDocument's payload-pointer schemas are deliberately loose ([:vector :map])
   — wrapper-level validation only, per the URPXDocument schema's design.
   That means malli's wrapper encoder won't recurse into inner entities to
   convert their typed leaves (BigDecimal, LocalDate, …) back to JSON-LD
   strings. Pre-encode each embedded RatePlan / RatePlanModifier through its
   own schema before handing the document to the wrapper encoder. Refs
   (:jsonld/id-only maps) survive intact — encoding skips fields the entity
   doesn't carry."
  [document]
  (cond-> document
    (:urpx/hasRatePlan document)
    (update :urpx/hasRatePlan
            (fn [vs] (mapv #(coerce/encode schema/RatePlan %) vs)))

    (:urpx/hasRatePlanModifier document)
    (update :urpx/hasRatePlanModifier
            (fn [vs] (mapv #(coerce/encode schema/RatePlanModifier %) vs)))))

(defn write-urpx-document
  "Serialize a coerced urpx:URPXDocument wrapper to a JSON-LD string. Inverse of
   urpx.coerce/coerce-urpx-document. The document's inner urpx:hasRatePlan /
   urpx:hasRatePlanModifier payloads are emitted as embedded entities."
  [document]
  (emit-via-schema schema/URPXDocument (pre-encode-payloads document)))

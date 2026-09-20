(ns urpx.coerce
  "Coerce raw URPX parser output into typed Clojure values.

  The parser (`urpx.core/load-rate-plan`) produces a Clojure map with
  namespaced keys but all-string leaf values. This namespace applies the
  `:decode/urpx-jsonld` decoders attached to `urpx.schema` schemas to convert
  those strings into BigDecimals, LocalDates, LocalTimes, LocalDateTimes,
  Durations, and integer month numbers — and to normalize fields that URPX
  permits as either a single map or a vector of maps."
  (:require [malli.core :as m]
            [malli.transform :as mt]
            [urpx.schema :as schema]))

(def ^:private jsonld-transformer
  (mt/transformer
   {:name :urpx-jsonld
    :default-decoder identity
    :default-encoder identity}))

(defn coerce
  "Decode a raw value against `schema` using the URPX JSON-LD transformer.
   Returns the typed value. Use `validate` afterwards if you want a strict check."
  [schema raw]
  (m/decode schema raw jsonld-transformer))

(defn encode
  "Encode a typed value against `schema` using the URPX JSON-LD transformer.
   Inverse of `coerce` — turns BigDecimal/LocalDate/etc. back into the JSON-LD
   string forms (URPX-nvo write-side). Schemas without an :encode/urpx-jsonld
   property pass values through unchanged (`:default-encoder identity`)."
  [schema typed]
  (m/encode schema typed jsonld-transformer))

(defn coerce-rate-plan
  "Coerce a raw parsed URPX rate plan into the typed shape described by
   `urpx.schema/RatePlan`."
  [raw]
  (coerce schema/RatePlan raw))

(defn coerce-rate-plan-modifier
  "Coerce a raw parsed URPX rate plan modifier into the typed shape described
   by `urpx.schema/RatePlanModifier`. Modifier docs (EEC, NSE, HRA fixtures)
   augment or override parts of an existing RatePlan; price resolution against
   a modifier alone is not (yet) supported."
  [raw]
  (coerce schema/RatePlanModifier raw))

(defn coerce-urpx-document
  "Coerce a raw parsed URPX document wrapper into the typed shape described
   by `urpx.schema/URPXDocument`. Validates the document-level metadata
   (urpx:targetURPXVersion, dates, version, …) — inner urpx:hasRatePlan /
   urpx:hasRatePlanModifier payloads are passed through as raw maps and
   should be coerced + validated against their own schemas separately."
  [raw]
  (coerce schema/URPXDocument raw))

(defn coerce-urpx-package
  "Coerce a raw parsed URPX package wrapper into the typed shape described by
   `urpx.schema/URPXPackage`, the v0.3.0 rename of urpx:URPXDocument. Same
   wrapper-level contract as `coerce-urpx-document`: inner payloads
   (urpx:hasRatePlan / urpx:hasRatePlanModifier / urpx:packages) pass through
   as raw maps and should be coerced against their own schemas separately."
  [raw]
  (coerce schema/URPXPackage raw))

(defn coerce-doc
  "Dispatch on the document's :jsonld/type and coerce as a RatePlan,
   RatePlanModifier, or wrapper (urpx:URPXDocument in v0.2.x, urpx:URPXPackage
   from v0.3.0 on). Returns nil for unknown types."
  [raw]
  (case (:jsonld/type raw)
    "urpx:RatePlan"         (coerce-rate-plan raw)
    "urpx:RatePlanModifier" (coerce-rate-plan-modifier raw)
    "urpx:URPXDocument"     (coerce-urpx-document raw)
    "urpx:URPXPackage"      (coerce-urpx-package raw)
    nil))

(defn validate
  "Validate `value` against `schema`. Returns true on success."
  [schema value]
  (m/validate schema value))

(defn explain
  "Return a Malli explanation for why `value` does not validate against `schema`,
   or nil if it does."
  [schema value]
  (m/explain schema value))

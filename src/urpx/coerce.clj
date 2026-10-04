(ns urpx.coerce
  "Coerce raw URPX parser output into typed Clojure values.

  The parser (`urpx.core/load-rate-plan`) produces a Clojure map with
  namespaced keys but all-string leaf values. This namespace applies the
  `:decode/urpx-jsonld` decoders attached to `urpx.schema` schemas to convert
  those strings into BigDecimals, java.time values, Durations, and integer
  month numbers — and to normalize fields that URPX permits as either a single
  map or a vector of maps."
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
   string forms for the write side. Schemas without an :encode/urpx-jsonld
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

(defn coerce-rate-plan-version
  "Coerce a raw top-level urpx:RatePlanVersion snapshot. v0.5.1 documents may
   carry the version as a sibling of the identity RatePlan rather than nested
   inside it; `urpx.index/versions-by-plan` finds it via urpx:forRatePlan."
  [raw]
  (coerce schema/RatePlanVersion raw))

(defn coerce-rate-plan-modifier-version
  "Coerce a raw top-level urpx:RatePlanModifierVersion snapshot. Parallel to
   `coerce-rate-plan-version`; the back pointer is urpx:forRatePlanModifier."
  [raw]
  (coerce schema/RatePlanModifierVersion raw))

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
   RatePlanModifier, wrapper (urpx:URPXDocument in v0.2.x, urpx:URPXPackage
   from v0.3.0 on), or a top-level version snapshot (urpx:RatePlanVersion /
   urpx:RatePlanModifierVersion, which v0.5.1 documents may carry as siblings
   of the identity entity rather than nested inside it). Returns nil for
   unknown types."
  [raw]
  (case (:jsonld/type raw)
    "urpx:RatePlan"         (coerce-rate-plan raw)
    "urpx:RatePlanModifier" (coerce-rate-plan-modifier raw)
    "urpx:URPXDocument"     (coerce-urpx-document raw)
    "urpx:URPXPackage"      (coerce-urpx-package raw)
    "urpx:RatePlanVersion"         (coerce-rate-plan-version raw)
    "urpx:RatePlanModifierVersion" (coerce-rate-plan-modifier-version raw)
    nil))

(def ^:private uncoerced-re #"^(\d{2}:\d{2}|\d{4}-\d{2}-\d{2})")

(defn temporal-signature
  "Walk `doc` and report what temporal literals it carries:
   `{:times #{} :dates #{} :date-times #{} :date-shaped-strings n}`.

   `:times` holds LocalTimes and OffsetTimes, `:date-times` LocalDateTimes and
   ZonedDateTimes, the latter including an xsd:date written with an offset.

   By java.time TYPE rather than by property name, deliberately. A name list
   would have to be kept in step with the vocabulary, and the one it missed
   would be a missing value rather than a loud failure.

   THE ONE IMPLEMENTATION of this walk. `urpx.schedule` harvests boundary
   candidates from it and `urpx.arrangements` uses it only to tell a coerced
   document from an uncoerced one; two copies of the test is two chances to
   disagree about what \"coerced\" means."
  [doc]
  (let [acc (volatile! {:times #{} :dates #{} :date-times #{} :date-shaped-strings 0})]
    (letfn [(walk [x]
              (cond
                (instance? java.time.LocalTime x)     (vswap! acc update :times conj x)
                (instance? java.time.OffsetTime x)    (vswap! acc update :times conj x)
                (instance? java.time.LocalDateTime x) (vswap! acc update :date-times conj x)
                (instance? java.time.ZonedDateTime x) (vswap! acc update :date-times conj x)
                (instance? java.time.LocalDate x)     (vswap! acc update :dates conj x)
                (string? x) (when (re-find uncoerced-re x)
                              (vswap! acc update :date-shaped-strings inc))
                (map? x) (run! walk (vals x))
                (sequential? x) (run! walk x)
                :else nil))]
      (walk doc))
    @acc))

(defn uncoerced?
  "True if `doc` has the SIGNATURE of a document that was never coerced: it
   carries date-shaped strings and no java.time values anywhere at all.

   It is NOT enough that a date-shaped string exists. urpx:version is
   `rdfs:range xsd:string` and filings use the effective date as the version
   LABEL, so \"2026-03-20\" is a perfectly coerced string. A shape-based test
   on strings alone rejected real modifier filings when `urpx.schedule` first
   tried it, which is why the test is the ABSENCE of real temporals rather than
   the presence of a string.

   A document with no temporal content at all is not uncoerced, merely empty of
   dates, and reports false."
  [doc]
  (let [{:keys [times dates date-times date-shaped-strings]} (temporal-signature doc)]
    (and (pos? date-shaped-strings) (empty? times) (empty? dates) (empty? date-times))))

(defn check-coerced!
  "Throw unless every document in `documents` has been coerced.

   `who` names the calling operation in the message. Reading an uncoerced
   document does not fail loudly on its own: dates stay strings, so anything
   that compares them throws deep inside, and anything that does NOT compare
   them quietly answers a different question. `urpx.arrangements/arrangements`
   returned 202 pairs instead of 144 that way, a plausible number rather than a
   refusal."
  [documents who]
  (let [bad (filter uncoerced? documents)]
    (when (seq bad)
      (throw (ex-info (str who " needs COERCED documents: " (count bad) " of "
                           (count documents) " carry date-shaped strings and no "
                           "java.time values at all, which is what an uncoerced "
                           "document looks like. Run them through urpx.coerce "
                           "first (coerce-doc dispatches on @type). Reading an "
                           "uncoerced document does not fail loudly: it answers "
                           "a different question.")
                      {:reason :uncoerced-document
                       :who who
                       :uncoerced (count bad)
                       :total (count documents)})))))

(defn validate
  "Validate `value` against `schema`. Returns true on success."
  [schema value]
  (m/validate schema value))

(defn explain
  "Return a Malli explanation for why `value` does not validate against `schema`,
   or nil if it does."
  [schema value]
  (m/explain schema value))

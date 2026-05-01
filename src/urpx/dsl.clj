(ns urpx.dsl
  "DSL for authoring URPX entities in idiomatic Clojure (URPX-cjk).

  The pattern, validated by the URPX-4wa spike: assemble a typed-shape map
  with canonical :urpx/* keys + the inferred :jsonld/type → run
  urpx.coerce/coerce against the schema (auto-coerces leaves via
  :decode/urpx-jsonld) → validate and throw ex-info with the Malli
  explanation on failure.

  This namespace currently exposes:

    - `make-builder` — the introspection-based generator (URPX-0cq). Given
      a Malli entity schema, returns a constructor that takes kwargs of
      canonical :urpx/* keys and produces a coerced + validated entity.
    - `entity-type` — the introspection helper used by `make-builder`.
    - A handful of named builders bound from `make-builder` to demonstrate
      the pattern: `season`, `metric-input`, `ledger`, `holiday`.

  Naming convention (URPX-0cq): kwargs use the canonical schema keys
  (:urpx/seasonName, :urpx/month, …). URPX-upf will add an ergonomic layer
  with short-form aliases (:name, :months) + optional positional name args
  + builders for every entity type in `urpx.schema`. Local-symbol ref
  resolution lands in URPX-8fo.

  Nil-stripping: kwargs whose value is `nil` are dropped before coercion,
  so `(builder :urpx/foo (when condition x))` cleanly omits the field
  rather than producing a present-but-nil entry that would fail validation.
  Explicit `false` is preserved (boolean fields aren't stripped)."
  (:require [malli.core :as m]
            [urpx.coerce :as coerce]
            [urpx.schema :as schema]))

(defn entity-type
  "Extract the @type literal (e.g. \"urpx:Season\") from a Malli entity
   schema by introspecting its :jsonld/type entry — which must have the
   form `[:= \"urpx:X\"]`. Returns nil if the schema doesn't carry a
   :jsonld/type entry shaped that way."
  [schema]
  (let [type-entry (some (fn [[k v]] (when (= :jsonld/type k) v))
                         (m/entries schema))]
    (when type-entry
      (let [inner (first (m/children type-entry))]
        (first (m/children inner))))))

(defn- strip-nils [m]
  (into {} (remove (comp nil? val)) m))

(defn make-builder
  "Given a urpx.schema entity schema, return a constructor function. The
   constructor:

     - Accepts kwargs (or a trailing map per Clojure 1.11+ unification) of
       canonical :urpx/* keys; the entity's :jsonld/type is set automatically
       from the schema.
     - Drops kwargs whose value is nil so optional fields can be supplied
       conditionally without producing present-but-nil entries.
     - Runs urpx.coerce/coerce against the schema — already-typed values pass
       through; raw values (numbers, strings, gMonth strings, …) are decoded
       per the schema's :decode/urpx-jsonld decoders.
     - Validates the assembled entity against the schema; throws ex-info
       (with the Malli explanation attached) on validation failure.

   The schema must carry a :jsonld/type entry with shape `[:= \"urpx:X\"]`
   so the @type can be inferred."
  [schema]
  (let [t (entity-type schema)]
    (when-not t
      (throw (ex-info "make-builder: schema must have a :jsonld/type [:= ...] entry"
                      {:schema (m/form schema)})))
    (fn build [& {:as opts}]
      (let [raw (assoc (strip-nils opts) :jsonld/type t)
            coerced (coerce/coerce schema raw)]
        (when-let [err (coerce/explain schema coerced)]
          (throw (ex-info (str "DSL builder produced an invalid " t)
                          {:type t :input raw :explanation err})))
        coerced))))

;; ---------------------------------------------------------------------------
;; Named builders (URPX-0cq)
;; ---------------------------------------------------------------------------
;; A handful of builders demonstrating that `make-builder` works across
;; entity-shape variety — flat (Season, Holiday), Ref-bearing (Ledger), and
;; nested-entity-bearing (MetricInput → MetricSpecification). URPX-upf
;; populates the full set of builders for every schema in `urpx.schema`.

(def ^{:doc "Builder for urpx:Season. See `make-builder` for semantics."}
  season
  (make-builder schema/Season))

(def ^{:doc "Builder for urpx:MetricInput. Demonstrates nested-entity coercion
            (urpx:hasMetricSpecification accepts a raw MetricSpecification map)."}
  metric-input
  (make-builder schema/MetricInput))

(def ^{:doc "Builder for urpx:Ledger. Demonstrates Ref-bearing fields
            (urpx:referencesMetricInput, urpx:ledgerType)."}
  ledger
  (make-builder schema/Ledger))

(def ^{:doc "Builder for urpx:Holiday. Demonstrates a flat shape with mixed
            optional fields."}
  holiday
  (make-builder schema/Holiday))

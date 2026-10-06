(ns urpx.dsl
  "Idiomatic Clojure authoring for URPX entities. Output of every builder is
  a coerced (typed) entity ready for `urpx.price/resolve-prices`,
  `urpx.emit/write-*`, or any other consumer of `urpx.schema`-shaped data.

  ## At a glance

    (require '[urpx.dsl :as dsl])

    (dsl/dsl-scope
      (dsl/season ::summer :name \"Summer\" :month [5 6 7 8 9 10])
      (dsl/season ::winter :name \"Winter\" :month [11 12 1 2 3 4])
      (dsl/price-definition ::pd-summer
                            :name \"Commodity Summer\"
                            :applies-in-season ::summer
                            :references-ledger ::ledger-commodity))

  ## Public API

    - `dsl-scope`             macro establishing a local-symbol-ref scope
    - `make-builder`          generator: schema → constructor function
    - `entity-type`           introspect a schema for its @type literal
    - a named builder for every entity-type schema in `urpx.schema`
                              (`season`, `metric-input`, `price-definition`,
                              `rate-plan`, `urpx-package`, `coverage-claim`, …),
                              with no exceptions. Where a schema dual-accepts
                              two @type spellings (`source-publication`), the
                              builder sets the v0.5.1 one; the schema still
                              validates the v0.2.x spelling on parse.

  ## Builder semantics

  Every builder:

    - Sets `:jsonld/type` automatically from the schema (no boilerplate).
    - Accepts kwargs (or a Clojure 1.11+ trailing map) of either canonical
      `:urpx/*` keys (`:urpx/seasonName`) or auto-derived kebab-case short
      forms (`:season-name`); both can be mixed in one call.
    - Auto-coerces raw inputs through the schema's `:decode/urpx-jsonld`
      decoders: BigDecimal, integer, exact ratio or numeric string →
      `BigDecimal` (a double, a float or an inexact ratio refuses, since
      it has already lost the written scale), ISO string → `LocalDate` /
      `LocalDateTime` / `LocalTime` (zoned forms when a dateTime or time
      carries an offset),
      `--MM` xsd:gMonth → int month-of-year,
      ISO 8601 string → `Duration` / `Period`.
    - Reads a `:urpx/X` keyword value as the vocabulary term
      `{:jsonld/id \"urpx:X\"}` (`:day-type :urpx/allDays`), element-wise
      in a vector.
    - Drops `nil`-valued kwargs (so `(builder :foo (when … x))` cleanly
      omits the field). Explicit `false` is preserved.
    - Validates the assembled entity against the schema and throws
      `ex-info` with the Malli explanation on failure.

  Inside a `dsl-scope`, builders additionally:

    - Accept an optional leading qualified-keyword arg as the entity's
      local `@id` (e.g. `(season ::summer …)` registers `::summer` in the
      scope and sets `:jsonld/id \"summer\"`).
    - Resolve other qualified-keyword kwarg values against the scope's
      registry to produce `{:jsonld/id …}` Ref maps. Vector-typed kwargs walk
      element-wise.

  Resolution is eager (declare-before-reference): an unknown ref, a
  duplicate declaration, or two declarations with the same @id throws.
  Each `dsl-scope` has its own registry — scopes don't leak."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.transform :as mt]
            [urpx.coerce :as coerce]
            [urpx.schema :as schema]))

;; ---------------------------------------------------------------------------
;; xsd:decimal inputs
;; ---------------------------------------------------------------------------

(deftype ^:private InexactDecimal [value]
  Object
  (toString [_] (str value)))

(defn- exact-decimal
  "`v` unchanged, or wrapped in an InexactDecimal when it is a number that
   cannot be an xsd:decimal exactly: a double or float, whose written scale
   is already lost, or a ratio with no terminating decimal expansion."
  [v]
  (cond
    (or (double? v) (float? v)) (InexactDecimal. v)
    (ratio? v)                  (try (bigdec v)
                                     (catch ArithmeticException _ (InexactDecimal. v)))
    :else                       v))

(def ^:private authoring-transformer
  "`coerce/jsonld-transformer`, preceded by `exact-decimal` on every
   `schema/Decimal`, so an inexact number survives decoding unconverted and
   fails validation at its own path."
  (mt/transformer
   {:name :urpx-authoring
    :decoders {:fn {:compile (fn [schema _]
                               (when (::schema/xsd-decimal (m/properties schema))
                                 exact-decimal))}}}
   coerce/jsonld-transformer))

(defn- inexact-decimal-error
  "An ex-info naming every xsd:decimal field in `explanation` that was given
   an inexact number, or nil if none was."
  [t explanation]
  (when-let [fields (seq (for [{:keys [in value]} (:errors explanation)
                               :when (instance? InexactDecimal value)]
                           {:in in :value (.-value ^InexactDecimal value)}))]
    (ex-info (str "DSL builder for " t " refuses an inexact number for xsd:decimal "
                  (str/join ", " (map (fn [{:keys [in value]}]
                                        (str (pr-str in) " (" (pr-str value) ")"))
                                      fields))
                  "; pass a BigDecimal (0.15M), an integer or a numeric string")
             {:type t
              :reason ::inexact-decimal
              :fields (vec fields)})))

;; ---------------------------------------------------------------------------
;; Local-symbol refs: dsl-scope + registry
;; ---------------------------------------------------------------------------

(def ^:dynamic ^:private *local-refs*
  "Holds the volatile map of {qualified-keyword → @id-string} for the
   currently-active `dsl-scope`; nil outside any scope. The dynamic var
   is just the indirection that lets builders find the registry without
   an explicit arg — actual mutation happens to the volatile inside it,
   which is born when the scope opens and dies when it closes (no shared
   mutable state escapes)."
  nil)

(defn- in-scope? [] (some? *local-refs*))

(defmacro dsl-scope
  "Establish a local-symbol-ref scope. Inside the body, builders accept an
   optional leading qualified-keyword argument as the entity's local @id
   (registered in the scope), and qualified-keyword values in kwarg
   positions other than `:urpx/*` vocabulary terms resolve to
   {:jsonld/id ...} maps via the registry.

   Resolution is eager (declare-before-reference): a reference to an
   undeclared keyword throws ex-info. Multiple declarations of the same
   keyword, or of two keywords with the same @id, also throw.

   Implementation: the scope binds `*local-refs*` to a fresh `volatile!`
   holding a persistent map. Builders mutate it via `vswap!` during the
   scope's evaluation; nothing outside the body's dynamic extent ever
   sees the volatile, and the scope returns a regular immutable result."
  [& body]
  `(binding [*local-refs* (volatile! {})]
     ~@body))

(defn- ->local-id
  "Project a qualified keyword to a string @id form: the keyword's local
   name (`::summer` → \"summer\")."
  [sym]
  (name sym))

(defn- register-local-id!
  "Register `sym` in the scope and return its @id. Throws if `sym` is
   already declared, or if another keyword in the scope already projects to
   the same @id, since two entities sharing an @id merge in RDF."
  [sym]
  (let [id (->local-id sym)]
    (when (contains? @*local-refs* sym)
      (throw (ex-info (str "DSL local ref already declared: " sym)
                      {:sym sym :existing (get @*local-refs* sym)})))
    (when-let [other (some (fn [[k v]] (when (= id v) k)) @*local-refs*)]
      (throw (ex-info (str "DSL local ref " sym " has the @id " (pr-str id)
                           " already declared by " other)
                      {:sym sym :id id :declared-by other})))
    (vswap! *local-refs* assoc sym id)
    id))

(defn- resolve-local-ref [sym]
  (if-let [id (get @*local-refs* sym)]
    {:jsonld/id id}
    (throw (ex-info (str "DSL local ref not declared: " sym)
                    {:sym sym :declared (keys @*local-refs*)}))))

(defn- vocabulary-keyword?
  "True if `v` names a URPX vocabulary term, such as `:urpx/allDays`."
  [v]
  (and (qualified-keyword? v)
       (= "urpx" (namespace v))))

(defn- resolve-refs-in-value
  "Walk a kwarg value and replace keyword leaves with {:jsonld/id ...} maps.
   A `:urpx/X` keyword names the vocabulary term `urpx:X`; inside a
   `dsl-scope`, any other qualified keyword is a local ref resolved from the
   registry. Vectors are walked element-wise; other shapes pass through
   unchanged."
  [v]
  (cond
    (vocabulary-keyword? v)                  {:jsonld/id (str "urpx:" (name v))}
    (and (in-scope?) (qualified-keyword? v)) (resolve-local-ref v)
    (vector? v)                              (mapv resolve-refs-in-value v)
    :else                                    v))

(defn- local-id-arg?
  "True if `v` looks like a DSL local-id: a qualified keyword whose
   namespace is NOT 'urpx' or 'jsonld' (those are reserved for kwarg keys
   in URPX schemas, e.g. :urpx/seasonName, :jsonld/id). Inside `dsl-scope`,
   a leading keyword that passes this test is treated as the entity's
   local @id rather than the first kwarg key.

   Constraint: kwarg keys outside the urpx/jsonld namespaces aren't
   supported in builder calls (use the trailing-map form to pass them)."
  [v]
  (and (qualified-keyword? v)
       (not (#{"urpx" "jsonld"} (namespace v)))))

(defn- normalize-args
  "Split a builder's varargs into [local-id-or-nil opts-map]. Local-id only
   activates inside `dsl-scope`. Trailing-map and alternating-kvs forms both
   produce the same opts map."
  [args]
  (let [[local-id args*] (if (and (in-scope?) (local-id-arg? (first args)))
                           [(first args) (rest args)]
                           [nil args])
        opts (if (and (= 1 (count args*)) (map? (first args*)))
               (first args*)
               (apply hash-map args*))]
    [local-id opts]))

(defn entity-type
  "Extract the @type literal (e.g. \"urpx:Season\") from a Malli entity
   schema by introspecting its :jsonld/type entry — which must have the
   form `[:= \"urpx:X\"]`. Schemas wrapped in `[:schema {:registry ...}
   <inner>]` (e.g. ConditionExpression) are unwrapped via `m/deref` first.
   Returns nil if the schema doesn't carry a :jsonld/type entry shaped
   that way."
  [schema]
  (let [schema* (try (m/deref schema) (catch Exception _ schema))
        type-entry (some (fn [[k v]] (when (= :jsonld/type k) v))
                         (m/entries schema*))]
    (when type-entry
      (let [inner (first (m/children type-entry))]
        (first (m/children inner))))))

(defn- strip-nils [m]
  (into {} (remove (comp nil? val)) m))

(defn- camel->kebab
  "Convert a camelCase or UpperCamelCase identifier to kebab-case.
   Handles all-caps prefixes correctly: 'URPXDocument' → 'urpx-document',
   'TOUPeriod' → 'tou-period'. Adapted from the standard two-pass camel→kebab
   regex idiom: insert a hyphen at every boundary where an uppercase letter
   either follows a lowercase letter / digit, or starts a new word in an
   all-caps run."
  [s]
  (-> s
      (str/replace #"([A-Z]+)([A-Z][a-z])" "$1-$2")
      (str/replace #"([a-z\d])([A-Z])"     "$1-$2")
      str/lower-case))

(defn- alias-map
  "Build a {short-key → canonical-key} map for `schema`'s :urpx/* entries.
   Aliases are kebab-cased local names — :urpx/seasonName → :season-name,
   :urpx/yearlyRecurrence → :yearly-recurrence. A schema wrapped in
   `[:schema {:registry ...} <inner>]` is unwrapped first, as in
   `entity-type`."
  [schema]
  (into {}
        (keep (fn [[k _]]
                (when (and (qualified-keyword? k) (= "urpx" (namespace k)))
                  [(keyword (camel->kebab (name k))) k])))
        (m/entries (m/deref schema))))

(defn- canonicalize-keys
  "Translate any short-form keys in `opts` to their canonical :urpx/* form
   via `aliases`. Other keys pass through unchanged."
  [opts aliases]
  (reduce-kv (fn [acc k v]
               (assoc acc (or (aliases k) k) v))
             {} opts))

(defn make-builder
  "Given a urpx.schema entity schema, return a constructor function. The
   constructor:

     - Accepts kwargs (or a trailing map per Clojure 1.11+ unification) of
       canonical :urpx/* keys; the entity's :jsonld/type is set automatically
       from the schema.
     - Inside a `dsl-scope`, accepts an optional leading
       qualified-keyword as the entity's local @id (registered in the scope's
       registry); qualified-keyword values in kwarg positions resolve to
       Ref maps via the same registry.
     - Reads a `:urpx/X` keyword value as the Ref `{:jsonld/id \"urpx:X\"}`,
       inside a scope or not.
     - Refuses a key that is neither a key of the schema, a short form of
       one, a `:jsonld/*` key, nor in `:passthrough`, naming the key and the
       entity type, so a misspelled key throws instead of being emitted.
     - Drops kwargs whose value is nil so optional fields can be supplied
       conditionally without producing present-but-nil entries.
     - Decodes against the schema with `urpx.coerce/jsonld-transformer`:
       already-typed values pass through; raw values (numbers, strings,
       gMonth strings, …) are decoded per the schema's :decode/urpx-jsonld
       decoders.
     - Refuses a double, a float or an inexact ratio for an xsd:decimal
       field, naming the field, under `:reason ::inexact-decimal`.
     - Validates the assembled entity against the schema; throws ex-info
       (with the Malli explanation attached) on validation failure.

   The schema must carry a :jsonld/type entry with shape `[:= \"urpx:X\"]`
   so the @type can be inferred.

   Options:

     - `:passthrough`, a collection of further keys the constructor admits,
       for a property the schema does not model:

         (make-builder schema/RatePlanVersion {:passthrough #{:urpx/offeredBy}})"
  ([schema] (make-builder schema {}))
  ([schema {:keys [passthrough]}]
   (let [t (entity-type schema)
         passthrough (set passthrough)
         aliases (alias-map schema)
         schema-keys (set (map first (m/entries (m/deref schema))))
         admitted? (fn [k]
                     (or (contains? schema-keys k)
                         (and (qualified-keyword? k) (= "jsonld" (namespace k)))
                         (contains? passthrough k)))]
     (when-not t
       (throw (ex-info "make-builder: schema must have a :jsonld/type [:= ...] entry"
                       {:schema (m/form schema)})))
     (fn build [& args]
       (let [[local-id-arg opts] (normalize-args args)
             opts (canonicalize-keys opts aliases)
             _ (when-let [unknown (seq (remove admitted? (keys opts)))]
                 (throw (ex-info (str "DSL builder for " t " refuses unknown keys "
                                      (pr-str (vec (sort-by str unknown))))
                                 {:type t :unknown-keys (set unknown)})))
             ;; Register the local-id BEFORE resolving the rest of the opts —
             ;; an entity can't reference itself, but registering early keeps
             ;; the registry consistent if any value resolution were to look
             ;; back at the in-progress entity.
             id (when local-id-arg (register-local-id! local-id-arg))
             opts* (into {}
                         (map (fn [[k v]] [k (resolve-refs-in-value v)]))
                         opts)
             raw (cond-> (assoc (strip-nils opts*) :jsonld/type t)
                   id (assoc :jsonld/id id))
             coerced (m/decode schema raw authoring-transformer)]
         (when-let [err (coerce/explain schema coerced)]
           (throw (or (inexact-decimal-error t err)
                      (ex-info (str "DSL builder produced an invalid " t)
                               {:type t :input raw :explanation err}))))
         coerced)))))

;; ---------------------------------------------------------------------------
;; Named builders for every entity in urpx.schema
;; ---------------------------------------------------------------------------

;; Each def binds a builder for one entity-type schema. Every builder
;; accepts canonical :urpx/* kwargs and the auto-derived kebab-case
;; aliases (e.g. :name for :urpx/name); see `make-builder` for semantics.

;; Plan elements
(def season               (make-builder schema/Season))
(def metric-specification (make-builder schema/MetricSpecification))
(def metric-input         (make-builder schema/MetricInput))
(def plan-elements        (make-builder schema/PlanElements))

;; TOU + time
(def time-bracket         (make-builder schema/TimeBracket))
(def tou-period           (make-builder schema/TOUPeriod))
(def tou-tier             (make-builder schema/TOUTier))
(def tou-schedule         (make-builder schema/TOUSchedule))
(def calculation-method   (make-builder schema/CalculationMethod))

;; Billing
(def ledger               (make-builder schema/Ledger))
(def price-definition     (make-builder schema/PriceDefinition))
(def service-tier         (make-builder schema/ServiceTier))
(def service-tiers        (make-builder schema/ServiceTiers))
(def billing-model        (make-builder schema/BillingModel))

;; Calendar
(def holiday              (make-builder schema/Holiday))
(def holiday-calendar     (make-builder schema/HolidayCalendar))

;; Conditions / applicability
(def object-operand-node       (make-builder schema/ObjectOperandNode))
(def datatype-operand-node     (make-builder schema/DatatypeOperandNode))
(def custom-operand-node       (make-builder schema/CustomOperandNode))
(def condition-expression      (make-builder schema/ConditionExpression))
(def applicability-group       (make-builder schema/ApplicabilityGroup))
(def applicability-rule        (make-builder schema/ApplicabilityRule))
(def customer-profile          (make-builder schema/CustomerProfile))
(def scenario                  (make-builder schema/Scenario))
(def eligibility-rule          (make-builder schema/EligibilityRule))

;; Prices
(def price                (make-builder schema/Price))
(def price-set            (make-builder schema/PriceSet))
(def price-summary        (make-builder schema/PriceSummary))
(def prices               (make-builder schema/Prices))

;; Metadata
(def organization              (make-builder schema/Organization))
(def tariff-book-reference     (make-builder schema/TariffBookReference))
(def data-source-reference     (make-builder schema/DataSourceReference))
(def rate-case                 (make-builder schema/RateCase))

;; Alternatives (declarative customer-or-configuration choices)
(def alternative-dimension     (make-builder schema/AlternativeDimension))
(def alternative-dimensions    (make-builder schema/AlternativeDimensions))
(def profile-alternative       (make-builder schema/ProfileAlternative))
(def profile-alternatives      (make-builder schema/ProfileAlternatives))
(def price-set-alternative     (make-builder schema/PriceSetAlternative))
(def price-set-alternatives    (make-builder schema/PriceSetAlternatives))
(def service-bracket           (make-builder schema/ServiceBracket))

;; Coverage machinery (v0.5.1)
(def coverage-claim            (make-builder schema/CoverageClaim))
(def evaluation                (make-builder schema/Evaluation))
(def model-exclusion           (make-builder schema/ModelExclusion))
(def temporal-scope            (make-builder schema/TemporalScope))
(def temporal-period           (make-builder schema/TemporalPeriod))
(def price-definition-reference (make-builder schema/PriceDefinitionReference))
(def tariff-filing             (make-builder schema/TariffFiling))
(def rate-plan-identifier      (make-builder schema/RatePlanIdentifier))
(def rate-plan-modifier-identifier (make-builder schema/RatePlanModifierIdentifier))
(def organization-identifier   (make-builder schema/OrganizationIdentifier))
(def source-publication        (make-builder schema/SourcePublication))

;; Rate plan + modifier + document
(def rate-plan-version              (make-builder schema/RatePlanVersion))
(def rate-plan                      (make-builder schema/RatePlan))
(def rate-plan-modifier-version     (make-builder schema/RatePlanModifierVersion))
(def rate-plan-modifier             (make-builder schema/RatePlanModifier))
(def urpx-document                  (make-builder schema/URPXDocument))
(def urpx-package                   (make-builder schema/URPXPackage))

;; Scenario cost model
(def scenario-cost-model            (make-builder schema/ScenarioCostModel))

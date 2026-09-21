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
    - 37 named builders       one per entity-type schema in `urpx.schema`
                              (`season`, `metric-input`, `price-definition`,
                              `rate-plan`, `urpx-document`, …)

  ## Builder semantics

  Every builder:

    - Sets `:jsonld/type` automatically from the schema (no boilerplate).
    - Accepts kwargs (or a Clojure 1.11+ trailing map) of either canonical
      `:urpx/*` keys (`:urpx/seasonName`) or auto-derived kebab-case short
      forms (`:season-name`); both can be mixed in one call.
    - Auto-coerces raw inputs through the schema's `:decode/urpx-jsonld`
      decoders: number → `BigDecimal`, ISO string → `LocalDate` /
      `LocalDateTime` / `LocalTime`, `--MM` xsd:gMonth → int month-of-year,
      ISO 8601 string → `Duration` / `Period`.
    - Drops `nil`-valued kwargs (so `(builder :foo (when … x))` cleanly
      omits the field). Explicit `false` is preserved.
    - Validates the assembled entity against the schema and throws
      `ex-info` with the Malli explanation on failure.

  Inside a `dsl-scope`, builders additionally:

    - Accept an optional leading qualified-keyword arg as the entity's
      local `@id` (e.g. `(season ::summer …)` registers `::summer` in the
      scope and sets `:jsonld/id \"summer\"`).
    - Resolve qualified-keyword kwarg values against the scope's registry
      to produce `{:jsonld/id …}` Ref maps. Vector-typed kwargs walk
      element-wise.

  Resolution is eager (declare-before-reference): an unknown ref or a
  duplicate declaration throws. Each `dsl-scope` has its own registry —
  scopes don't leak."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [urpx.coerce :as coerce]
            [urpx.schema :as schema]))

;; ---------------------------------------------------------------------------
;; Local-symbol refs (URPX-8fo) — dsl-scope + registry
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
   positions resolve to {:jsonld/id ...} maps via the registry.

   Resolution is eager (declare-before-reference): a reference to an
   undeclared keyword throws ex-info. Multiple declarations of the same
   keyword also throw.

   Implementation: the scope binds `*local-refs*` to a fresh `volatile!`
   holding a persistent map. Builders mutate it via `vswap!` during the
   scope's evaluation; nothing outside the body's dynamic extent ever
   sees the volatile, and the scope returns a regular immutable result."
  [& body]
  `(binding [*local-refs* (volatile! {})]
     ~@body))

(defn- ->local-id
  "Project a qualified keyword to a string @id form. The default is the
   keyword's local name (`::summer` → \"summer\"); collision with an
   existing declaration in the registry throws."
  [sym]
  (name sym))

(defn- register-local-id! [sym]
  (let [id (->local-id sym)]
    (when (contains? @*local-refs* sym)
      (throw (ex-info (str "DSL local ref already declared: " sym)
                      {:sym sym :existing (get @*local-refs* sym)})))
    (vswap! *local-refs* assoc sym id)
    id))

(defn- resolve-local-ref [sym]
  (if-let [id (get @*local-refs* sym)]
    {:jsonld/id id}
    (throw (ex-info (str "DSL local ref not declared: " sym)
                    {:sym sym :declared (keys @*local-refs*)}))))

(defn- resolve-refs-in-value
  "Walk a kwarg value and replace qualified-keyword leaves (treated as
   local-ref intents) with {:jsonld/id ...} maps from the registry. Vectors
   are walked element-wise; other shapes pass through unchanged."
  [v]
  (cond
    (qualified-keyword? v) (resolve-local-ref v)
    (vector? v)            (mapv resolve-refs-in-value v)
    :else                  v))

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
   :urpx/yearlyRecurrence → :yearly-recurrence."
  [schema]
  (into {}
        (keep (fn [[k _]]
                (when (and (qualified-keyword? k) (= "urpx" (namespace k)))
                  [(keyword (camel->kebab (name k))) k])))
        (m/entries schema)))

(defn- canonicalize-keys
  "Translate any short-form keys in `opts` to their canonical :urpx/* form
   via `aliases`. Unknown keys pass through (schema validation surfaces
   typos)."
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
     - Inside a `dsl-scope` (URPX-8fo), accepts an optional leading
       qualified-keyword as the entity's local @id (registered in the scope's
       registry); qualified-keyword values in kwarg positions resolve to
       Ref maps via the same registry.
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
  (let [t (entity-type schema)
        aliases (alias-map schema)]
    (when-not t
      (throw (ex-info "make-builder: schema must have a :jsonld/type [:= ...] entry"
                      {:schema (m/form schema)})))
    (fn build [& args]
      (let [[local-id-arg opts] (normalize-args args)
            ;; Register the local-id BEFORE resolving the rest of the opts —
            ;; an entity can't reference itself, but registering early keeps
            ;; the registry consistent if any value resolution were to look
            ;; back at the in-progress entity.
            id (when local-id-arg (register-local-id! local-id-arg))
            opts (canonicalize-keys opts aliases)
            opts* (if (in-scope?)
                    (into {} (map (fn [[k v]] [k (resolve-refs-in-value v)])) opts)
                    opts)
            raw (cond-> (assoc (strip-nils opts*) :jsonld/type t)
                  id (assoc :jsonld/id id))
            coerced (coerce/coerce schema raw)]
        (when-let [err (coerce/explain schema coerced)]
          (throw (ex-info (str "DSL builder produced an invalid " t)
                          {:type t :input raw :explanation err})))
        coerced))))

;; ---------------------------------------------------------------------------
;; Named builders for every entity in urpx.schema (URPX-upf)
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
(def prices               (make-builder schema/Prices))

;; Metadata
(def organization              (make-builder schema/Organization))
(def tariff-book-reference     (make-builder schema/TariffBookReference))
(def data-source-reference     (make-builder schema/DataSourceReference))
(def rate-case                 (make-builder schema/RateCase))
(def tariff-filing             (make-builder schema/TariffFiling))
;; No builder for schema/SourcePublication: its :jsonld/type is an :enum so
;; both the v0.5.1 and v0.2.x literals validate, and make-builder requires a
;; [:= "..."] entry (see entity-type) and throws otherwise. Build one by hand
;; if you need it, choosing the @type explicitly.

;; Rate plan + modifier + document
(def rate-plan-version              (make-builder schema/RatePlanVersion))
(def rate-plan                      (make-builder schema/RatePlan))
(def rate-plan-modifier-version     (make-builder schema/RatePlanModifierVersion))
(def rate-plan-modifier             (make-builder schema/RatePlanModifier))
(def urpx-document                  (make-builder schema/URPXDocument))
(def urpx-package                   (make-builder schema/URPXPackage))

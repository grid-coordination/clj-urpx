(ns urpx.dsl
  "Idiomatic Clojure authoring for URPX entities. Output of every builder is
  a coerced (typed) entity ready for `urpx.price/resolve-prices`,
  `urpx.emit/write-*`, or any other consumer of `urpx.schema`-shaped data.

  ## At a glance

    (require '[urpx.dsl :as dsl])

    (dsl/dsl-scope
      (dsl/season :as :ex/summer :season-name \"Summer\" :month [5 6 7 8 9 10])
      (dsl/ledger :as :ex/commodity
                  :name \"Commodity\"
                  :ledger-type :urpx/supplyLedger)
      (dsl/price-definition :as :ex/pd-summer
                            :name \"Commodity Summer\"
                            :applies-in-season :ex/summer
                            :references-ledger :ex/commodity))

  ## Public API

    - `dsl-scope`             macro naming entities by keyword
    - `curie`                 the default keyword -> @id function
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

  Inside a `dsl-scope`, builders also take `:as k` for the entity's @id
  and read other qualified keywords as references; see `dsl-scope`."
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
;; dsl-scope: keyword @ids and keyword references
;; ---------------------------------------------------------------------------

(def ^:dynamic ^:private *scope*
  "The volatile state of the innermost active `dsl-scope`, nil outside one:

     :id-fn      keyword -> @id string
     :declared   {@id {:as keyword-or-nil, :type @type}}
     :referenced {keyword #{@type of each builder that referenced it}}"
  nil)

(defn curie
  "The default `dsl-scope` `:id-fn`: `:scl/summer` -> \"scl:summer\"."
  [k]
  (str (namespace k) ":" (name k)))

(defn- local-keyword?
  "True if `v` can name an entity in a `dsl-scope`: a qualified keyword
   outside the `urpx` and `jsonld` namespaces."
  [v]
  (and (qualified-keyword? v)
       (not (#{"urpx" "jsonld"} (namespace v)))))

(defn- run-scope
  "Run `body-fn` in a fresh scope configured by `opts`, then refuse every
   keyword referenced in it whose @id no builder in it declared."
  [opts body-fn]
  (when-let [unknown (seq (remove #{:id-fn} (keys opts)))]
    (throw (ex-info (str "dsl-scope refuses unknown options "
                         (pr-str (vec unknown)))
                    {:unknown-options (set unknown)})))
  (let [scope (volatile! {:id-fn (:id-fn opts curie)
                          :declared {}
                          :referenced {}})
        result (binding [*scope* scope]
                 (body-fn))
        {:keys [id-fn declared referenced]} @scope
        unresolved (into (sorted-map-by (fn [a b]
                                          (compare (str a) (str b))))
                         (remove (fn [[k _]]
                                   (contains? declared (id-fn k))))
                         referenced)]
    (when (seq unresolved)
      (throw (ex-info (str "dsl-scope has unresolved references "
                           (str/join ", "
                                     (for [[k types] unresolved]
                                       (str k
                                            " (from "
                                            (str/join ", " (sort types))
                                            ")"))))
                      {:unresolved unresolved})))
    result))

(defmacro dsl-scope
  "Evaluate `body` in a scope where entities are named by keyword. An
   optional leading map literal holds options:

     :id-fn   keyword -> @id string; default `curie` (`:scl/summer` ->
              \"scl:summer\")

   Inside the scope, a builder:

     - takes `:as k` to set the entity's @id to `(id-fn k)`;
     - reads a qualified-keyword value outside the `urpx` and `jsonld`
       namespaces as the Ref `{:jsonld/id (id-fn k)}`;
     - refuses an @id already declared in the scope, by `:as` or by an
       explicit `:jsonld/id`.

   A reference may come before or after its declaration. When the body
   finishes, every keyword referenced whose @id no builder in the scope
   declared is refused in one ex-info naming each such keyword and the
   @type of each builder that referenced it, under `:unresolved`. A
   reference to an entity outside the scope is a Ref map,
   `{:jsonld/id \"…\"}`.

   Each scope has its own state; an inner scope does not see the outer."
  [& body]
  (let [[opts body] (if (map? (first body))
                      [(first body) (rest body)]
                      [{} body])]
    `(#'run-scope ~opts (fn [] ~@body))))

(defn- declare-id!
  "Record that a builder of @type `t` declared @id `id`, by `:as k` when `k`
   is given; refuse an @id already declared in the scope."
  [id k t]
  (when-let [{:keys [as type]} (get-in @*scope* [:declared id])]
    (throw (ex-info (str "dsl-scope: @id " (pr-str id)
                         " of " (or k t)
                         " is already declared by " (or as type))
                    {:id id
                     :as k
                     :type t
                     :declared-by {:as as :type type}})))
  (vswap! *scope* assoc-in [:declared id] {:as k :type t}))

(defn- local-id
  "The @id `:as k` gives an entity of @type `t`, declared in the scope."
  [k t]
  (when-not *scope*
    (throw (ex-info (str "DSL builder for " t " takes :as only inside a dsl-scope")
                    {:type t :as k})))
  (when-not (local-keyword? k)
    (throw (ex-info (str "DSL builder for " t
                         " needs :as to be a qualified keyword outside the urpx and jsonld namespaces, not "
                         (pr-str k))
                    {:type t :as k})))
  (let [id ((:id-fn @*scope*) k)]
    (declare-id! id k t)
    id))

(defn- reference!
  "The Ref for keyword `k`, recorded as referenced by a builder of @type `t`."
  [k t]
  (vswap! *scope* update-in [:referenced k] (fnil conj #{}) t)
  {:jsonld/id ((:id-fn @*scope*) k)})

(defn- vocabulary-keyword?
  "True if `v` names a URPX vocabulary term, such as `:urpx/allDays`."
  [v]
  (and (qualified-keyword? v)
       (= "urpx" (namespace v))))

(defn- resolve-refs-in-value
  "Walk a kwarg value given to a builder of @type `t` and replace keyword
   leaves with {:jsonld/id ...} maps. A `:urpx/X` keyword names the
   vocabulary term `urpx:X`; inside a `dsl-scope`, any other qualified
   keyword outside `jsonld` is a reference. Vectors are walked element-wise;
   other shapes pass through unchanged."
  [t v]
  (cond
    (vocabulary-keyword? v)               {:jsonld/id (str "urpx:" (name v))}
    (and *scope* (local-keyword? v))      (reference! v t)
    (vector? v)                           (mapv (partial resolve-refs-in-value t) v)
    :else                                 v))

(defn- normalize-args
  "A builder's varargs, alternating keys and values or one trailing map, as
   one opts map."
  [args]
  (if (and (= 1 (count args)) (map? (first args)))
    (first args)
    (apply hash-map args)))

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
     - Inside a `dsl-scope`, takes `:as k` for the entity's @id and reads
       other qualified-keyword values as references; see `dsl-scope`.
       Outside one, refuses `:as`.
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
       (let [opts (normalize-args args)
             as (:as opts)
             opts (canonicalize-keys (dissoc opts :as) aliases)
             _ (when-let [unknown (seq (remove admitted? (keys opts)))]
                 (throw (ex-info (str "DSL builder for " t " refuses unknown keys "
                                      (pr-str (vec (sort-by str unknown))))
                                 {:type t :unknown-keys (set unknown)})))
             _ (when (and as (some? (:jsonld/id opts)))
                 (throw (ex-info (str "DSL builder for " t " takes :as or :jsonld/id, not both")
                                 {:type t :as as :id (:jsonld/id opts)})))
             id (if as
                  (local-id as t)
                  (:jsonld/id opts))
             _ (when (and *scope* (not as) (some? id))
                 (declare-id! id nil t))
             opts* (into {}
                         (map (fn [[k v]]
                                [k (resolve-refs-in-value t v)]))
                         opts)
             raw (cond-> (assoc (strip-nils opts*) :jsonld/type t)
                   as (assoc :jsonld/id id))
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

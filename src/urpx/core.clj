(ns urpx.core
  "URPX rate plan parsing and loading.

  Parses URPX JSON-LD rate plan documents into Clojure data structures.
  Price resolution is in urpx.price."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [urpx.index :as index]))

(defn- prefixed-key->keyword
  "Convert a JSON-LD string key into a Clojure keyword.

   - JSON-LD reserved keys like '@id', '@type', '@context' become :jsonld/id, etc.
   - Prefixed CURIE keys like 'urpx:name' become :urpx/name.
   - Bare keys become unqualified keywords."
  [k]
  (cond
    (str/starts-with? k "@")
    (keyword "jsonld" (subs k 1))

    (str/includes? k ":")
    (let [[prefix local] (str/split k #":" 2)]
      (keyword prefix local))

    :else (keyword k)))

(defn- transform-keys
  "Walk a parsed JSON structure converting string map keys to keywords."
  [v]
  (cond
    (map? v)
    (persistent!
     (reduce-kv (fn [acc k val]
                  (assoc! acc
                          (if (string? k) (prefixed-key->keyword k) k)
                          (transform-keys val)))
                (transient {}) v))

    (sequential? v)
    (mapv transform-keys v)

    :else v))

(defn keyword->jsonld-key
  "Inverse of `prefixed-key->keyword`: a Clojure keyword → its JSON-LD
   string-key form.

   - `:jsonld/X` (reserved JSON-LD keys) → `\"@X\"` (covers @type, @id,
     @context, @graph).
   - Namespaced keywords → `\"ns:local\"` (CURIE form, e.g. :urpx/name → \"urpx:name\").
   - Bare keywords → `\"name\"`.

   Used by the write-side path (urpx.emit) to project a typed
   entity back into the JSON-shaped data that `clojure.data.json/write-str`
   serializes."
  [k]
  (cond
    (= "jsonld" (namespace k))  (str "@" (name k))
    (namespace k)               (str (namespace k) ":" (name k))
    :else                       (name k)))

(defn keys->jsonld
  "Walk a Clojure data structure converting namespaced-keyword map keys back
   to their JSON-LD string forms via `keyword->jsonld-key`. Inverse of the
   private `transform-keys` walker that the parse path uses to keywordize
   incoming JSON-LD.

   Output is plain JSON-friendly Clojure data (string keys, leaves
   unchanged), ready for `clojure.data.json/write-str`."
  [v]
  (cond
    (map? v)
    (persistent!
     (reduce-kv (fn [acc k val]
                  (assoc! acc
                          (if (keyword? k) (keyword->jsonld-key k) k)
                          (keys->jsonld val)))
                (transient {}) v))

    (sequential? v)
    (mapv keys->jsonld v)

    :else v))

(defn parse-rate-plan
  "Parse a URPX JSON-LD rate plan document from `src` (anything io/reader can handle:
   File, URL, Reader, InputStream, or path string).

   Returns a Clojure map with keys converted to namespaced keywords:
   prefixed CURIEs (e.g. 'urpx:name') become :urpx/name; JSON-LD reserved keys
   ('@id', '@type', '@context') become :jsonld/id, :jsonld/type, :jsonld/context.
   String values (including CURIE references) are left unchanged.

   This is a raw parser — it does not unwrap a top-level @graph container. Use
   `parse-graph` / `load-graph` for documents that bundle multiple entities."
  [src]
  (with-open [r (io/reader src)]
    (transform-keys (json/read r))))

(def ^:private graph-object-keys
  "The keys a JSON-LD 1.1 graph object may carry (section 9.4). A node with
   @graph and nothing outside these names a graph and states nothing else."
  #{:jsonld/graph :jsonld/id :jsonld/index :jsonld/context})

(defn- flatten-graph
  "`node` as the entities it contributes: its @graph members, recursively,
   preceded by `node` itself less @graph when it carries any property a graph
   object may not."
  [node]
  (if-let [g (and (map? node) (:jsonld/graph node))]
    (into (if (every? graph-object-keys (keys node)) [] [(dissoc node :jsonld/graph)])
          (mapcat flatten-graph)
          (index/as-vec g))
    [node]))

(def containment-properties
  "The properties whose bare @id reference `parse-graph` replaces with the node
   of that @id stated in the same document."
  #{:urpx/hasAlternativeDimension :urpx/hasAlternativeDimensions
    :urpx/hasApplicabilityGroup :urpx/hasApplicabilityRule :urpx/hasBillingModel
    :urpx/hasCalculationMethod :urpx/hasConditionExpression :urpx/hasCoverageClaim
    :urpx/hasCustomerProfile :urpx/hasDataSourceReference :urpx/hasDynamicPricing
    :urpx/hasEligibilityRule
    :urpx/hasGeneralRule :urpx/hasHoliday :urpx/hasHolidayCalendar :urpx/hasIdentifier
    :urpx/hasIdentity :urpx/hasLedger :urpx/hasLocationDataSet :urpx/hasMetricInput
    :urpx/hasMetricSpecification
    :urpx/hasModelExclusion :urpx/hasName :urpx/hasPlanElements :urpx/hasPossibleValues
    :urpx/hasPrice :urpx/hasPriceDefinition :urpx/hasPriceDefinitionReference :urpx/hasPriceSet
    :urpx/hasPriceSetAlternative :urpx/hasPriceSetAlternatives :urpx/hasPriceSummary
    :urpx/hasPriceUpdateRule
    :urpx/hasPrices :urpx/hasProfileAlternative :urpx/hasProfileAlternatives
    :urpx/hasProgramEnrollment :urpx/hasRatePlan :urpx/hasRatePlanModifier
    :urpx/hasRateGroup :urpx/hasRatePlanModifierVersion :urpx/hasRatePlanVersion :urpx/hasSeasons
    :urpx/hasServiceClass
    :urpx/hasServiceBracket
    :urpx/hasServiceTier :urpx/hasServiceTiers :urpx/hasTOUPeriod :urpx/hasTOUSchedule
    :urpx/hasTOUTier
    :urpx/hasTemporalPeriod :urpx/hasTemporalScope :urpx/hasTimeBracket
    :urpx/rootNode :urpx/leftOperand :urpx/rightOperand :urpx/operandNodes
    :urpx/explainedBy :urpx/packages})

(defn- bare-ref-id
  "The @id of `v` when `v` is a map holding nothing but an @id."
  [v]
  (when (and (map? v) (= 1 (count v)))
    (let [id (:jsonld/id v)]
      (when (string? id) id))))

(defn- blank-id? [id]
  (and (string? id) (str/starts-with? id "_:")))

(defn- map-entries-seq
  "Every [k v] entry of every map within `x`, @context excluded."
  [x]
  (cond
    (map? x) (mapcat (fn [[k v]]
                       (when-not (= :jsonld/context k)
                         (cons [k v] (map-entries-seq v))))
                     x)
    (sequential? x) (mapcat map-entries-seq x)
    :else nil))

(defn- maps-seq
  "Every map within `x`, `x` included, @context excluded."
  [x]
  (cond
    (map? x) (cons x (mapcat (fn [[k v]] (when-not (= :jsonld/context k) (maps-seq v))) x))
    (sequential? x) (mapcat maps-seq x)
    :else nil))

(defn- embed-graph
  "`entities` with each embeddable bare reference replaced by the top-level
   entity it names, recursively, and each embedded entity removed from the top
   level.

   A reference is embeddable when exactly one top-level entity states its @id
   and either its property is in `containment-properties` or it names a blank
   node referenced once. A node already on the current path, or kept at the top
   level, stays a reference. An entity no embedding reaches stays at the top
   level, in document order. A blank node identifier left occurring once is
   dropped."
  [entities]
  (let [targets  (->> entities
                      (filter #(and (map? %) (> (count %) 1) (string? (:jsonld/id %))))
                      (group-by :jsonld/id)
                      (into {} (keep (fn [[id es]] (when (= 1 (count es)) [id (first es)])))))
        once     (into #{} (keep (fn [[id n]] (when (and (= 1 n) (blank-id? id)) id)))
                       (frequencies (keep bare-ref-id (mapcat maps-seq entities))))
        target   (fn [k v]
                   (when-let [id (bare-ref-id v)]
                     (when (and (targets id) (or (containment-properties k) (once id)))
                       id)))
        targeted (into #{} (keep (fn [[k v]] (target k v)))
                       (for [[k v] (map-entries-seq entities)
                             x (index/as-vec v)]
                         [k x]))]
    (if (empty? targeted)
      entities
      (let [reached (volatile! #{})
            rooted  (volatile! #{})
            walk    (fn walk [x path]
                      (cond
                        (map? x)
                        (persistent!
                         (reduce-kv
                          (fn [acc k v]
                            (let [one (fn [e]
                                        (let [id (target k e)]
                                          (if (and id (not (path id)) (not (@rooted id)))
                                            (do (vswap! reached conj id)
                                                (walk (targets id) (conj path id)))
                                            (walk e path))))]
                              (assoc! acc k (cond
                                              (= :jsonld/context k) v
                                              (sequential? v) (mapv one v)
                                              :else (one v)))))
                          (transient {}) x))
                        (sequential? x) (mapv #(walk % path) x)
                        :else x))
            root    (fn [e]
                      (let [id (:jsonld/id e)]
                        (when id (vswap! rooted conj id))
                        (walk e (if id #{id} #{}))))
            initial (into {} (keep-indexed (fn [i e] (when-not (targeted (:jsonld/id e)) [i (root e)])))
                          entities)
            out     (reduce (fn [acc [i e]]
                              (if (or (contains? acc i) (@reached (:jsonld/id e)))
                                acc
                                (assoc acc i (root e))))
                            initial (map-indexed vector entities))
            out     (mapv val (sort-by key out))
            blanks  (frequencies (filter blank-id? (map :jsonld/id (mapcat maps-seq out))))
            prune   (fn prune [x]
                      (cond
                        (map? x)
                        (let [x  (into {} (map (fn [[k v]] [k (if (= :jsonld/context k) v (prune v))])) x)
                              id (:jsonld/id x)]
                          (if (and (blank-id? id) (= 1 (blanks id)) (> (count x) 1))
                            (dissoc x :jsonld/id)
                            x))
                        (sequential? x) (mapv prune x)
                        :else x))]
        (mapv prune out)))))

(defn parse-graph
  "Parse a URPX JSON-LD document and return a vector of the entities in its
   graphs, nested graphs included.

   - A single-entity document yields a one-element vector of that entity.
   - An `@graph` yields its members, e.g. a base RatePlan and its sibling
     RatePlanModifier bundled together. A member that is itself a node with an
     `@graph` is flattened the same way, at any depth.
   - A node carrying only `@graph`, `@id`, `@index` and `@context` (a JSON-LD
     1.1 graph object, or the top-level wrapper of a document) names a graph
     and is replaced by its members. A node with any other property is kept,
     less its `@graph`, ahead of its members.

   Graph names are not kept: the result is the union of the document's graphs.

   A document in JSON-LD flattened form, its nodes at the top level linked by
   bare `@id` references, reads as its nested equivalent: a reference under a
   property in `containment-properties`, or to a blank node referenced once, is
   replaced by the node it names when exactly one top-level entity states it,
   and that entity leaves the top level. Every other reference stays a
   reference.

   Always returns a vector so callers can use the same code path for every
   shape; dispatch on each entity's `:jsonld/type` to decide what to do
   with it (e.g. via `urpx.coerce/coerce-doc`)."
  [src]
  (embed-graph (flatten-graph (parse-rate-plan src))))

(defn load-graph
  "Load a URPX JSON-LD document from `src` — anything `clojure.java.io/reader`
   can coerce (File, URL, Reader, InputStream, or path string). See
   `parse-graph` for the unwrapping semantics.

   Accepting URLs lets callers pass the result of `(io/resource ...)`
   directly, including the `jar:file:...!/...` URL form that classpath
   lookups produce when the library is deployed inside an uberjar."
  [src]
  (parse-graph src))

(def wrapper-types
  "Top-level wrapper @types that `expand-document-entities` descends through."
  #{"urpx:URPXPackage"})

(defn expand-document-entities
  "Public because it is the one piece a CLI or a third-party consumer needs and
   cannot otherwise reach: `load-graph` does not descend a wrapper, so anything
   that walks a document itself would validate the wrapper metadata alone.

   If `entity` is one of `wrapper-types`, return its embedded payload
   entities: urpx:hasRatePlan, urpx:hasRatePlanModifier and urpx:packages
   (the general containment pointer), each distinct payload once. Otherwise return `[entity]` unchanged.

   Used by `load-rate-plan` and `load-rate-plan-modifier` to descend
   transparently through the wrapper so consumers can target inner entities
   by @type without caring whether the document is wrapped, bare
   single-entity, or `@graph`-bundled."
  [entity]
  (if (contains? wrapper-types (:jsonld/type entity))
    (vec (distinct (concat (index/as-vec (:urpx/hasRatePlan entity))
                           (index/as-vec (:urpx/hasRatePlanModifier entity))
                           (index/as-vec (:urpx/packages entity)))))
    [entity]))

(defn load-rate-plan
  "Load a URPX rate plan from `src` — anything `clojure.java.io/reader` can
   coerce (File, URL, Reader, InputStream, or path string; URL forms include
   the `jar:file:...!/...` produced by `(io/resource ...)` against an uberjar
   classpath). The document may carry the plan in one of three shapes — all
   three resolve to the unique inner urpx:RatePlan:

     - Single-entity doc: top-level `@type: urpx:RatePlan`.
     - `@graph` bundle: the plan appears as one of several siblings (alongside
       RatePlanModifier(s), ProgramEnrollment, …).
     - Wrapper: top-level `@type: urpx:URPXPackage`, with `urpx:hasRatePlan`
       or `urpx:packages` pointing at the plan.

   Zero or more than one urpx:RatePlan in a single document is an error —
   use `load-graph` to inspect such documents directly."
  [src]
  (let [entities (mapcat expand-document-entities (load-graph src))
        plans (filter #(= "urpx:RatePlan" (:jsonld/type %)) entities)]
    (case (count plans)
      0 (throw (ex-info "No urpx:RatePlan entity in document"
                        {:src src
                         :entity-types (mapv :jsonld/type entities)}))
      1 (first plans)
      (throw (ex-info "Multiple urpx:RatePlan entities in @graph; use load-graph to disambiguate"
                      {:src src
                       :rate-plan-ids (mapv :jsonld/id plans)})))))

(defn load-rate-plan-modifier
  "Load a URPX rate plan modifier from `src` (see `load-rate-plan` for the
   accepted shapes — File, URL, Reader, InputStream, path string, including
   `jar:file:...` URLs from `(io/resource ...)`). Parallel to `load-rate-plan`
   — the modifier may appear as a standalone single-entity doc, as one entity
   in a `@graph` bundle, or under a urpx:URPXPackage wrapper's urpx:hasRatePlanModifier or urpx:packages pointer.

   The unique urpx:RatePlanModifier is returned; zero or more than one is an
   error — use `load-graph` to inspect ambiguous documents directly."
  [src]
  (let [entities (mapcat expand-document-entities (load-graph src))
        modifiers (filter #(= "urpx:RatePlanModifier" (:jsonld/type %)) entities)]
    (case (count modifiers)
      0 (throw (ex-info "No urpx:RatePlanModifier entity in document"
                        {:src src
                         :entity-types (mapv :jsonld/type entities)}))
      1 (first modifiers)
      (throw (ex-info "Multiple urpx:RatePlanModifier entities in @graph; use load-graph to disambiguate"
                      {:src src
                       :modifier-ids (mapv :jsonld/id modifiers)})))))

(defn- load-unique-version
  "Shared body for the two version loaders: filter the document's entities by
   `type-literal` and insist on exactly one."
  [src type-literal]
  (let [entities (mapcat expand-document-entities (load-graph src))
        found    (filter #(= type-literal (:jsonld/type %)) entities)]
    (case (count found)
      0 (throw (ex-info (str "No " type-literal " entity in document")
                        {:src src
                         :entity-types (mapv :jsonld/type entities)}))
      1 (first found)
      (throw (ex-info (str "Multiple " type-literal " entities in @graph; use load-graph to disambiguate")
                      {:src src
                       :version-ids (mapv :jsonld/id found)})))))

(defn load-rate-plan-version
  "Load a top-level urpx:RatePlanVersion snapshot from `src`.

   v0.5.1 un-nests the version: a document may carry the snapshot as a sibling
   of the identity urpx:RatePlan rather than nested under
   urpx:hasRatePlanVersion, with the link stated upward as urpx:forRatePlan.

   `load-rate-plan` discards such a snapshot, since it filters for
   urpx:RatePlan; this is its counterpart. To resolve prices for such a
   document, load it with `urpx.load/documents`, then either pass
   `:versions-by-plan` from `urpx.index/versions-by-plan` to
   `urpx.price/resolve-prices`, or resolve through `urpx.scenario-cost-model`.

   Zero or more than one urpx:RatePlanVersion is an error."
  [src]
  (load-unique-version src "urpx:RatePlanVersion"))

(defn load-rate-plan-modifier-version
  "Load a top-level urpx:RatePlanModifierVersion snapshot from `src`. Parallel
   to `load-rate-plan-version`; the back pointer is urpx:forRatePlanModifier."
  [src]
  (load-unique-version src "urpx:RatePlanModifierVersion"))

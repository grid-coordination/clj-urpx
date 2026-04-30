(ns urpx.core
  "URPX rate plan loading and price resolution.

  Parses URPX JSON-LD rate plan documents into Clojure data structures
  and resolves prices for any given timestamp and timezone."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

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

(defn parse-graph
  "Parse a URPX JSON-LD document and return a vector of every top-level entity.

   - Documents with a top-level `@graph` yield the graph contents (a vector
     of entity maps), e.g. a base RatePlan + its sibling RatePlanModifier
     bundled together.
   - Single-entity documents yield a one-element vector containing the
     entity itself.

   Always returns a vector so callers can use the same code path for both
   shapes; dispatch on each entity's `:jsonld/type` to decide what to do
   with it (e.g. via `urpx.coerce/coerce-doc`)."
  [src]
  (let [parsed (parse-rate-plan src)]
    (if-let [g (:jsonld/graph parsed)]
      (vec g)
      [parsed])))

(defn load-graph
  "Load a URPX JSON-LD document from a file path. See `parse-graph`."
  [path]
  (parse-graph (io/file path)))

(defn- as-vec
  "Normalize a JSON-LD property value into a vector. Handles the
   single-object / vector / nil shapes URPX docs use interchangeably."
  [v]
  (cond
    (nil? v) []
    (sequential? v) (vec v)
    :else [v]))

(defn- expand-document-entities
  "If `entity` is a urpx:URPXDocument, return its embedded payload entities
   (urpx:hasRatePlan + urpx:hasRatePlanModifier + urpx:hasTariffPublication).
   Otherwise return `[entity]` unchanged. Used by `load-rate-plan` and
   `load-rate-plan-modifier` to descend transparently through the v0.2.0
   wrapper so consumers can target inner entities by @type without caring
   whether the filing is URPXDocument-wrapped (URPX-1ii / URPX-8hq), bare
   single-entity, or `@graph`-bundled."
  [entity]
  (if (= "urpx:URPXDocument" (:jsonld/type entity))
    (vec (concat (as-vec (:urpx/hasRatePlan entity))
                 (as-vec (:urpx/hasRatePlanModifier entity))
                 (as-vec (:urpx/hasTariffPublication entity))))
    [entity]))

(defn load-rate-plan
  "Load a URPX rate plan from a file path. The document may carry the plan in
   one of three shapes — all three resolve to the unique inner urpx:RatePlan:

     - Single-entity doc: top-level `@type: urpx:RatePlan`.
     - `@graph` bundle: the plan appears as one of several siblings (alongside
       RatePlanModifier(s), ProgramEnrollment, …).
     - URPXDocument wrapper (v0.2.0 Decision #103): top-level
       `@type: urpx:URPXDocument` with `urpx:hasRatePlan` pointing at the plan.

   Zero or more than one urpx:RatePlan in a single document is an error —
   use `load-graph` to inspect such documents directly."
  [path]
  (let [entities (mapcat expand-document-entities (load-graph path))
        plans (filter #(= "urpx:RatePlan" (:jsonld/type %)) entities)]
    (case (count plans)
      0 (throw (ex-info "No urpx:RatePlan entity in document"
                        {:path path
                         :entity-types (mapv :jsonld/type entities)}))
      1 (first plans)
      (throw (ex-info "Multiple urpx:RatePlan entities in @graph; use load-graph to disambiguate"
                      {:path path
                       :rate-plan-ids (mapv :jsonld/id plans)})))))

(defn load-rate-plan-modifier
  "Load a URPX rate plan modifier from a file path. Parallel to `load-rate-plan`
   — the modifier may appear as a standalone single-entity doc, as one entity
   in a `@graph` bundle, or under a urpx:URPXDocument wrapper's
   urpx:hasRatePlanModifier pointer.

   The unique urpx:RatePlanModifier is returned; zero or more than one is an
   error — use `load-graph` to inspect ambiguous documents directly."
  [path]
  (let [entities (mapcat expand-document-entities (load-graph path))
        modifiers (filter #(= "urpx:RatePlanModifier" (:jsonld/type %)) entities)]
    (case (count modifiers)
      0 (throw (ex-info "No urpx:RatePlanModifier entity in document"
                        {:path path
                         :entity-types (mapv :jsonld/type entities)}))
      1 (first modifiers)
      (throw (ex-info "Multiple urpx:RatePlanModifier entities in @graph; use load-graph to disambiguate"
                      {:path path
                       :modifier-ids (mapv :jsonld/id modifiers)})))))

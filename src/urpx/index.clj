(ns urpx.index
  "Build and query an entity index for URPX documents.

  URPX JSON-LD documents are graphs: most entities carry a :jsonld/id and refer
  to other entities by reference shape `{:jsonld/id \"urpx:foo\"}`. Coercion
  leaves these references intact; consumers dereference them on demand against
  an index keyed by id string.

  Note: many references point to vocabulary IRIs in the URPX ontology
  (e.g. urpx:peakTier, urpx:allDays, urpx:bundledLedger) rather than entities
  defined in the document. Those lookups return nil — callers distinguish
  internal refs (resolve to a node) from vocabulary terms (do not).")

(defn- index-walk
  "Reduce over `node`, accumulating {id -> node} for every map that carries
   both a :jsonld/id and a :jsonld/type. The :jsonld/type requirement
   distinguishes real entities from bare references ({:jsonld/id \"...\"})
   that point to vocabulary IRIs or other entities — only the entity
   definition gets indexed, not the references to it."
  [acc node]
  (cond
    (map? node)
    (let [acc (if (and (:jsonld/id node) (:jsonld/type node))
                (assoc acc (:jsonld/id node) node)
                acc)]
      (reduce-kv (fn [a _ v] (index-walk a v)) acc node))

    (sequential? node)
    (reduce index-walk acc node)

    :else acc))

(defn build-index
  "Walk a URPX document (raw or coerced) and return a map from id string to
   the entity node bearing that id. Suitable input: anything `urpx.core/load-rate-plan`
   or `urpx.coerce/coerce-rate-plan` produces."
  [doc]
  (index-walk {} doc))

(defn ref?
  "True if `v` looks like a JSON-LD @id reference — a map with a :jsonld/id
   but no :jsonld/type. (Real entities carry both; references carry only :jsonld/id.)"
  [v]
  (and (map? v)
       (contains? v :jsonld/id)
       (not (contains? v :jsonld/type))))

(defn resolve-ref
  "Given an `index` and either a Ref map ({:jsonld/id \"urpx:foo\"}) or an id string,
   return the indexed entity, or nil if not found.

   Returns nil for vocabulary IRIs that don't correspond to a node in the document."
  [index ref-or-id]
  (cond
    (string? ref-or-id) (get index ref-or-id)
    (ref? ref-or-id)    (get index (:jsonld/id ref-or-id))
    :else nil))

(defn get-entity
  "Look up an entity directly by id string. Same as `(resolve-ref index id)`
   when the input is a string, but with a clearer name when the call site
   already has the bare id."
  [index id]
  (get index id))

;; ---------------------------------------------------------------------------
;; Back-pointer index (URPX v0.5.1 un-nesting)
;; ---------------------------------------------------------------------------

(def ^:private version-back-pointer
  "Version @type -> the property naming the entity it is a snapshot of.
   urpx:forRatePlan and urpx:forRatePlanModifier are declared owl:inverseOf
   urpx:hasRatePlanVersion / urpx:hasRatePlanModifierVersion, and both are
   sh:minCount 1 sh:maxCount 1 on their version shapes, so a conformant v0.5.1
   snapshot always carries one even when the document also nests it."
  {"urpx:RatePlanVersion"         :urpx/forRatePlan
   "urpx:RatePlanModifierVersion" :urpx/forRatePlanModifier})

(defn- back-pointer-walk
  [acc node]
  (cond
    (map? node)
    (let [k   (version-back-pointer (:jsonld/type node))
          pid (when k (:jsonld/id (get node k)))
          acc (if pid (update acc pid (fnil conj []) node) acc)]
      (reduce-kv (fn [a _ v] (back-pointer-walk a v)) acc node))

    (sequential? node)
    (reduce back-pointer-walk acc node)

    :else acc))

(defn versions-by-plan
  "Walk a URPX document and return {plan-id [version-snapshot ...]}, keyed by
   the @id the snapshot points back at through urpx:forRatePlan or
   urpx:forRatePlanModifier.

   This is the v0.5.1 un-nested path: a document may state the plan-to-version
   link downward (urpx:hasRatePlanVersion, a nested map), upward (the back
   pointer, with the snapshot top-level), or both. Indexing on the back pointer
   reaches the snapshot in every one of those shapes.

   The back pointer may be a bare reference or a fully embedded RatePlan node;
   both expose :jsonld/id, so one accessor covers each. Walks the raw structure
   rather than `build-index`, so a snapshot carrying no @id of its own is still
   found."
  [doc]
  (back-pointer-walk {} doc))

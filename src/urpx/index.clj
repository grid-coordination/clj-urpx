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

(defn as-vec
  "A property URPX models 0..N, as a vector, whatever arity the document wrote.

   For a property this library ITERATES. `single` is the mirror, for one this
   library reads as a single value, and the two must stay distinct: `single`
   refuses when a document supplies several, which `as-vec` must never do.

   Necessary because a 0..N property is written either as a JSON-LD list or, when
   single-valued, as a bare object, and the two expand identically. A reader that
   iterates the bare form gets MapEntries rather than nodes and silently sees
   nothing: `(keep :jsonld/id {:jsonld/id \"x\"})` is empty where the one-element
   list gives `(\"x\")`. urpx.schema normalizes the properties it decodes, but only
   after `urpx.coerce` has run, so a reader on a raw or partly coerced document
   needs this.

   The last arm is `[v]`, not `(vec v)`: sequential values are handled above, so
   anything reaching it is a scalar, and `(vec \"urpx:foo\")` would shred a compact
   string @id into characters.

   This replaced seven private copies that disagreed about nil, three returning
   [], two nil and two [nil]. All seven call sites were checked to be
   nil-equivalent before collapsing them."
  [v]
  (cond
    (nil? v)        []
    (sequential? v) (vec v)
    :else           [v]))

(defn single
  "Normalize a property that URPX declares 0..N but that this library reads as
   one value, or nil when there is none.

     nil or absent   -> nil
     a single map    -> that map
     a 1-vector      -> its element
     a longer vector -> throws, naming `what`

   Throwing rather than taking `first` is the point. A reader that silently
   picked one would resolve against an unpredictable node and report a
   confident wrong answer; refusing says plainly that the document is beyond
   what this library decides. Where the choice is genuinely the caller's, the
   caller supplies it explicitly instead.

   Used wherever the shipped SHACL sets no sh:maxCount but exactly one reader
   here expects a single value. scripts/shacl-drift.sh lists those sites."
  [v what]
  (cond
    (map? v) v
    (sequential? v)
    (case (count v)
      0 nil
      1 (first v)
      (throw (ex-info (str "Multiple " what "; this library reads one. "
                           "Pass the intended node explicitly.")
                      {:what what
                       :count (count v)
                       :ids (mapv :jsonld/id v)})))
    :else nil))

(defn nested-version
  "Return the single version snapshot nested under `nested-key` on `plan`, or
   nil when there is none.

   urpx:hasRatePlanVersion and urpx:hasRatePlanModifierVersion are sh:minCount 0
   with NO sh:maxCount upstream, so a conformant document may nest several
   snapshots. Documents in the wild nest exactly one, and every reader in this
   library expects one, so this normalizes the single-or-vector shapes and
   REFUSES to guess when there is genuinely more than one:

     nil or absent  -> nil
     a single map   -> that map
     a 1-vector     -> its element
     a longer vector -> throws

   Throwing rather than taking `first` is deliberate. Picking arbitrarily would
   resolve prices against an unpredictable snapshot and report a confident wrong
   answer; the caller that knows which window it wants passes it explicitly
   instead (`:version` in the resolve-prices opts). Which snapshot applies to a
   given instant is a selection question the vocabulary does not yet settle:
   urpx:hasTemporalScope carries no sh:path constraints at v0.5.1."
  [plan nested-key]
  (single (get plan nested-key) "nested version snapshots"))

(defn entities-of-type
  "Walk `doc` and return {id -> node} for every entity of `type-literal` that
   carries an @id.

   Narrower than `build-index` on purpose. A resolver that needs to dereference
   one kind of reference should not pay to index the whole document, and on a
   filing with thousands of Prices that difference is the difference between a
   cheap lookup table and a walk of everything."
  [doc type-literal]
  (letfn [(walk [acc node]
            (cond
              (map? node)
              (let [acc (if (and (= type-literal (:jsonld/type node)) (:jsonld/id node))
                          (assoc acc (:jsonld/id node) node)
                          acc)]
                (reduce-kv (fn [a _ v] (walk a v)) acc node))
              (sequential? node) (reduce walk acc node)
              :else acc))]
    (walk {} doc)))

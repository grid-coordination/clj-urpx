(ns urpx.arrangements
  "Enumerate the service arrangements a set of URPX documents expresses.

  A SERVICE ARRANGEMENT is one urpx:RatePlanVersion, plus every
  urpx:RatePlanModifierVersion compatible with it, plus one
  urpx:ProfileAlternative for each dimension any of them leaves open. Fully
  determined: given an instant inside its window it resolves to prices with no
  further input. If a choice is still outstanding it is not one arrangement but
  several, and each of those is an arrangement in its own right.

  `urpx:ServiceArrangement` IS NOT A URPX CLASS. It occurs zero times in the
  ontology and zero times in the SHACL shapes. It is a name for a thing the
  standard describes the parts of and does not name the whole of, and nothing
  here writes it into a document or claims a urpx: prefix for it. The
  cardinalities this namespace relies on were checked against the shapes graph
  rather than recalled.

  WHAT THIS CAN AND CANNOT ENUMERATE. Which compatible modifiers a given
  CUSTOMER has is a fact about the customer, and URPX places customer data
  outside its scope: urpx:AccountServiceAgreement carries the skos:note
  \"Service account and agreement data lives in customer data, not URPX.\" So
  this enumerates the arrangements the DOCUMENTS EXPRESS, every combination the
  filings say is permitted. That set is larger than any one customer's and is
  what a price server can publish.

  OPTIONAL BY CONSTRUCTION. This namespace requires urpx.price; urpx.price does
  not require this. A consumer that wants prices for a composition it already
  knows how to assemble uses urpx.price directly and never loads this file.
  Nothing here changes an existing arity or return shape."
  (:require [clojure.string :as str]
            [urpx.coerce :as coerce]
            [urpx.index :as index]
            [urpx.price :as price]
            [urpx.schedule :as schedule])
  (:import [java.time Instant ZonedDateTime]))

;; ---------------------------------------------------------------------------
;; Versions and currency
;; ---------------------------------------------------------------------------

(def ^:private back-pointer
  {"urpx:RatePlanVersion"         :urpx/forRatePlan
   "urpx:RatePlanModifierVersion" :urpx/forRatePlanModifier})

(defn- versions-in
  "Every version snapshot of `kind` reachable in `doc`, as
   `[identity-id version source-doc]` triples.

   Read from the BACK POINTER rather than from nesting or from a filing's path.
   urpx:forRatePlan is sh:minCount 1 sh:maxCount 1 on RatePlanVersionShape, so
   the link is always there to read; a directory count is not a version count,
   because one version node can be published in more than one filing.

   THE SOURCE DOCUMENT TRAVELS WITH THE SNAPSHOT and is never recovered by @id
   later. Two documents may publish the same version @id, so an @id lookup
   cannot tell their snapshots apart and binds one to whichever document a map
   happened to keep, which paired a snapshot with a document that was not its
   source. That is the 0.28.0 defect class a third time, and the fix is
   structural rather than another guard.

   No filter on urpx:effectiveDateTime here either. A version stating only
   urpx:effectiveDate, or neither, is conformant (both are sh:minCount 0), and
   dropping it silently removed a whole plan from the enumeration while
   urpx.price resolved the same document normally. Currency is decided below by
   urpx.price's own readers."
  [doc kind]
  (let [k (back-pointer kind)]
    (into []
          (comp (filter #(and (map? %) (= kind (:jsonld/type %))))
                (keep (fn [v] (when-let [pid (:jsonld/id (get v k))] [pid v doc]))))
          (tree-seq coll? seq doc))))

(defn- distinct-by-node
  "Drop triples whose snapshot node is EQUAL to one already seen, keeping the
   first. Two filings carrying a byte-identical version node are one version
   published twice, which is benign; the :repeated-ids policy has already
   refused or merged the case where they differ."
  [triples]
  (->> triples (reduce (fn [[seen acc] tr]
                         (let [v (second tr)]
                           (if (contains? seen v) [seen acc] [(conj seen v) (conj acc tr)])))
                       [#{} []])
       second))

(defn- conflicting-id-groups
  "Snapshot @ids in `triples` that name MORE THAN ONE distinct snapshot.

   Publishing one @id twice is benign when the two nodes are equal: it is one
   thing appearing in two filings, and deduplicating is the whole of it. When
   they differ it is a contradiction, because an @id identifies a thing and
   there cannot be two of it."
  [triples]
  (->> (group-by #(:jsonld/id (second %)) triples)
       (keep (fn [[id ts]]
               (let [distinct-nodes (distinct (map second ts))]
                 (when (and id (> (count distinct-nodes) 1))
                   [id (count ts)]))))
       (sort-by first)
       vec))

(defn repeated-ids-refuse
  "The default policy for one @id naming more than one distinct snapshot:
   refuse, naming every offending @id at once.

   An @id identifies a thing, so two different things under one is a
   contradiction rather than a choice. Where urpx.price refuses two DIFFERENT
   snapshots tied on an effective instant it can say \"pass :version to choose\",
   because the caller has two distinguishable things to choose between; here
   both carry the same @id and there is nothing to name.

   Every conflicting @id is named in one refusal, across every kind. Called once
   per kind this threw on the base versions before the modifier versions were
   ever examined, so a corpus with three conflicting @ids reported one.

   Byte-identical repeats are NOT a conflict. One version published in two
   filings is one version, and it is deduplicated."
  [by-kind]
  (let [bad (into (sorted-map)
                  (keep (fn [[kind triples]]
                          (when-let [g (seq (conflicting-id-groups triples))]
                            [kind (vec g)])))
                  by-kind)]
    (when (seq bad)
      (throw (ex-info (str "Cannot enumerate: "
                           (reduce + (map (comp count val) bad))
                           " version @id(s) each name more than one distinct snapshot, "
                           "so an @id does not identify a version here. "
                           (str/join "; "
                                     (for [[kind gs] bad, [id n] gs]
                                       (str (pr-str id) " (" kind ") in " n " document(s)")))
                           ". Two different things cannot share one identifier, and nothing "
                           "in the documents says which is meant. Pass :repeated-ids "
                           "urpx.arrangements/repeated-ids-union to merge them instead, "
                           "which is the RDF reading of several documents describing "
                           "one IRI.")
                      {:reason  :urpx.arrangement/conflicting-version-id
                       :ids     (vec (mapcat (fn [[_ gs]] (map first gs)) bad))
                       :counts  (into {} (mapcat val bad))
                       :by-kind (into {} (map (fn [[k gs]] [k (mapv first gs)])) bad)}))))
  (into {} (map (fn [[k ts]] [k (vec (distinct-by-node ts))])) by-kind))

(defn- union-nodes
  "Merge snapshot nodes sharing one @id, property by property.

   Vector-valued properties union, preserving first-seen order. Vector rather
   than collection: a Ref is a map and so a collection, but it is one VALUE, and
   unioning two of them concatenates their entries into nonsense. Only a
   property URPX models 0..N arrives as a vector. A property whose value is not
   one and differs between occurrences cannot be merged at all: URPX gives many of those sh:maxCount 1, so two values would
   be invalid rather than combined, and nothing says which is meant. That
   refuses, with the property named."
  [id nodes kind]
  (let [ks (distinct (mapcat keys nodes))]
    (into {}
          (map (fn [k]
                 (let [vs (distinct (keep #(get % k) nodes))]
                   (cond
                     (<= (count vs) 1) [k (first vs)]
                     ;; SEQUENTIAL, not coll?. A Ref is a map and therefore a
                     ;; collection, but it is one VALUE: unioning two of them
                     ;; concatenates their entries into nonsense instead of
                     ;; refusing. Only a property URPX models 0..N arrives as a
                     ;; vector, which is exactly the set that can be merged.
                     (every? sequential? vs) [k (vec (distinct (apply concat vs)))]
                     :else
                     (throw (ex-info (str "Cannot merge " (pr-str id) " (" kind "): its "
                                          "occurrences disagree about " (pr-str k)
                                          ", which is not a collection, so the values "
                                          "cannot be combined and nothing says which "
                                          "is meant. " (pr-str (vec vs)))
                                     {:reason :urpx.arrangement/unmergeable-property
                                      :id id :kind kind :property k
                                      :values (vec vs)}))))))
          ks)))

(defn repeated-ids-union
  "A policy that MERGES snapshots sharing one @id rather than refusing them.

   The RDF reading: several documents describing one IRI contribute the union
   of their statements, and absence of a property is not a negative assertion.

   KNOWN COST, and it is not small. RDF MERGES TRIPLES, NOT MEANINGS. Merging a
   copy that states urpx:applicableToRatePlanVersion with one that omits it
   yields a node that HAS the property, which under `named-versions-narrow`
   restricts the modifier to exactly those versions. The omitting copy meant the
   opposite: no version restriction at all. So the union of the triples narrows
   where the union of the meanings would widen. Pair this with
   `named-versions-add` if the widening reading is what you want.

   It also SILENTLY RECONCILES a case that is a defect on any reading: a version
   @id whose option list differs between filings because the list is
   time-varying and the @id is not. A July filing offers one option; a
   September filing republishes the same version @id with two more, which are
   September versions that did not exist in July. The union asserts that a
   version effective in July offered them. Merging makes that enumerable; it
   does not make it true.

   Refuses `:urpx.arrangement/unmergeable-property` when occurrences disagree
   about a non-collection property, which cannot be combined."
  [by-kind]
  (into {}
        (map (fn [[kind triples]]
               [kind (vec (for [[id group] (group-by #(:jsonld/id (second %)) triples)
                                :let [nodes (distinct (map second group))]]
                            (if (= 1 (count nodes))
                              (first group)
                              ;; The merged node is in NO single document, so one
                              ;; has to be bound. Prefer the document that IS the
                              ;; identity this version points back at, which is
                              ;; derivable from the graph: a bundled copy of a
                              ;; modifier version lives inside some PLAN's filing,
                              ;; so picking a source arbitrarily can bind a
                              ;; urpx:RatePlan as a modifier. Falling back to a
                              ;; content-ordered pick keeps it independent of the
                              ;; order a caller walked its corpus, which is the
                              ;; max-key tie-break this namespace already removed
                              ;; once and would be no better here.
                              (let [pid   (first (first group))
                                    own   (first (filter #(= pid (:jsonld/id (nth % 2))) group))
                                    [_ _ doc] (or own (first (sort-by #(pr-str (nth % 2)) group)))]
                                [pid (union-nodes id nodes kind) doc]))))]))
        by-kind))

(defn- current-versions
  "For each identity, the version in force at `t`, as a vector of triples.
   Nil `t` keeps every version, which is the whole expressed space rather than
   the currently-offered one.

   A superseded version is not a service arrangement. Nobody takes service
   under it, and it does not belong in a set offered for use.

   TIES ARE REFUSED, NOT BROKEN. This read `(apply max-key ...)`, which returns
   the LAST maximal element, so two snapshots sharing the latest effective
   instant meant one was silently discarded and which one depended on the order
   the documents arrived in: reordering the same documents changed the
   enumeration, with no error either way. urpx.price refuses exactly this input
   with :ambiguous-version; a namespace sitting on top of it must not quietly
   undo that.

   An identity with no version in force at `t` contributes nothing, and that is
   NOT the same silence: a plan whose versions have all expired, or none of
   which has taken effect, is genuinely not offered then. urpx.price refuses
   there because it was asked for one plan's price; this was asked which
   arrangements exist, and the answer for that plan is none."
  [triples ^ZonedDateTime t]
  (let [by-identity (group-by first triples)]
    (if (nil? t)
      (into {} (map (fn [[pid ts]] [pid (vec ts)])) by-identity)
      (into {}
            (keep (fn [[pid ts]]
                    ;; Each version is zoned by ITS OWN document, which the
                    ;; triple carries. A corpus may span zones, and reading one
                    ;; publisher's bounds in another's frame is the defect this
                    ;; whole path was just fixed for.
                    (let [zone-of (fn [tr] (price/require-zone (nth tr 2)))
                          live (filter #(price/version-covers? (second %) t (zone-of %)) ts)]
                      (when (seq live)
                        (let [latest  (->> live (map #(price/version-effective-at (second %) (zone-of %)))
                                           (sort) (last))
                              winners (distinct-by-node
                                       (filter #(= latest (price/version-effective-at (second %) (zone-of %)))
                                               live))]
                          (when (> (count winners) 1)
                            (throw (ex-info (str "Cannot choose a version for " (pr-str pid) ": "
                                                 (count winners) " snapshots share the latest "
                                                 "effective instant " latest ", so there is no rule "
                                                 "here that picks among them.")
                                            {:reason :urpx.arrangement/ambiguous-version
                                             :plan-id pid
                                             :at t
                                             :ids (mapv #(:jsonld/id (second %)) winners)})))
                          [pid [(first winners)]])))))
            by-identity))))

;; ---------------------------------------------------------------------------
;; Compatibility
;; ---------------------------------------------------------------------------

(defn- applicability
  "The two compatibility statements on `mv`, as sets of @ids."
  [mv]
  ;; Through index/as-vec because both properties are 0..N and a document
  ;; writing the single-valued form writes a bare object. Iterating that yields
  ;; MapEntries, so `keep :jsonld/id` found nothing and BOTH shipped policies
  ;; returned false: the modifier applied to no base plan, every arrangement
  ;; came out base-only, and nothing was raised. `check-coerced!` cannot catch
  ;; it because it tests for date-shaped strings rather than Ref arity.
  [(into #{} (keep :jsonld/id) (index/as-vec (:urpx/applicableToRatePlanVersion mv)))
   (into #{} (keep :jsonld/id) (index/as-vec (:urpx/applicableToRatePlan mv)))])

;; A COMPATIBILITY POLICY is `(fn [modifier-version base-version plan-id] ->
;; boolean)`. It exists because URPX does not settle what a version-level
;; statement means alongside a plan-level one, and this library does not invent
;; the answer any more than it invents which modifiers a customer holds.
;;
;; Both properties are sh:minCount 0 with no sh:maxCount on
;; RatePlanModifierVersionShape, and BOTH state compatibility: what may
;; combine, not what anyone has. What is unstated is whether naming a version
;; NARROWS the plan-level statement or ADDS to it. urpx:applicableToRatePlanVersion
;; is a plain owl:ObjectProperty, owl:FunctionalProperty appears zero times in
;; the ontology, and sh:closed closes the property set rather than the value
;; set, so nothing in the artifacts decides it.
;;
;; WHERE THEY CAN DIFFER AT ALL is narrower than it looks. The two readings
;; disagree only when a modifier names SOME version, and only about a base
;; version it does not name whose plan it names by urpx:applicableToRatePlan.
;; A modifier that names no version gets the same answer under both.
;;
;; Which means the choice cannot be settled by testing it against documents
;; that never take that shape. It is a question about what URPX documents may
;; say.
;;
;; THERE IS ARTIFACT EVIDENCE, THOUGH: URPX Decision #090, which introduced
;; urpx:applicableToRatePlanVersion
;; (urpx-org/urpx-dev, dev-docs/decisions/urpx-design-decisions-081-120.md). It
;; cuts both ways and is recorded here rather than summarized, because a
;; one-sided reading of it would settle this on half the text.
;;
;; FOR `named-versions-add`. The decision records "Breaking Change: None - this
;; is an additive enhancement", and the alternative it selected is credited with
;; "Preserves the existing document-level properties for document-scoped
;; relationships". A property whose mere presence retracted compatibility that
;; urpx:applicableToRatePlan had established would stop documents composing that
;; composed before, which is hard to call non-breaking or preserving.
;;
;; AGAINST BOTH, AND AGAINST THIS DEFAULT IN PARTICULAR. The same Rationale says
;; the new properties "avoid ambiguity where a modifier document is broadly
;; applicable but only certain modifier versions are valid for a particular rate
;; plan version". That was read here as arguing for narrowing, and it does not:
;; the restriction runs over MODIFIER versions, scoped to one rate plan version.
;; It describes version-to-version pairing and says nothing about whether naming
;; a version of plan P withdraws the modifier from plans Q and R.
;;
;; WHICH MAKES THIS DEFAULT THE LEAST SUPPORTED OF THREE READINGS, not two:
;;
;;   (a) `named-versions-add`      naming a version retracts nothing.
;;   (b) scope-their-plan          naming version(s) of plan P restricts to
;;                                 those versions OF P, while plans named only
;;                                 at plan level keep all of theirs. NOT
;;                                 IMPLEMENTED; see below.
;;   (c) `named-versions-narrow`   naming any version retracts everything
;;                                 unnamed, other plans included. The default.
;;
;; Verified: with a modifier naming plans P and Q and one version of P,
;; `named-versions-narrow` returns nothing for Q's version. That is retraction
;; across plans, which is (c), and nothing in Decision #090 argues for it. The
;; Rationale points nearer (b); "preserves the existing document-level properties
;; for document-scoped relationships" argues against (c) specifically.
;;
;; (b) IS NOT SHIPPED because it cannot be, at this signature. A policy receives
;; `[modifier-version base-version plan-id]`, so it holds the named version @ids
;; as strings and no way to learn which plan each belongs to. Deciding "does any
;; named version belong to THIS plan" needs the documents, which means a richer
;; policy signature rather than another function. Filed rather than approximated.
;;
;; "Breaking Change: None" is also weaker than it looks: in a spec changelog that
;; usually means existing documents stay VALID, which adding an optional property
;; cannot affect either way. It is a conformance claim, not a semantic one. The
;; line that IS about meaning is the preserves-document-level one.
;;
;; So: evidence, not settlement, and the default is unchanged only because
;; changing it is a behaviour change nobody has asked for yet. It is the reading
;; with the least support in the artifacts, which is recorded here rather than
;; left for the next reader to rediscover.
;;
;; The two questions are entangled: when copies of one modifier version
;; disagree about urpx:applicableToRatePlanVersion, `repeated-ids-union` yields
;; a node that states it, and the compatibility policy in force then decides
;; what that node means. Both policies are shipped so that settling the copies
;; question does not also require settling this one silently.

(defn named-versions-narrow
  "The default policy: naming any urpx:applicableToRatePlanVersion means those
   versions and no others, overriding urpx:applicableToRatePlan.

   Reads with the ontology's own wording, \"Defines which SPECIFIC rate plan
   versions are compatible with this modifier version\", and it is the only
   reading under which the property can express a restriction at all: under
   `named-versions-add` a version statement can only widen, and then only to a
   version whose plan is not already named.

   KNOWN COST, stated because it is not small. The rule is NON-MONOTONIC.
   Merging a document that adds a version statement REMOVES compatibility with
   a plan named only by urpx:applicableToRatePlan, and RDF merge never retracts.
   A partial view of a corpus can therefore yield MORE arrangements than the
   whole of it. It is the default because changing it would change behavior,
   not because it is known right; the evidence on each side is recorded above."
  [mv bv plan-id]
  (let [[by-version by-plan] (applicability mv)]
    (if (seq by-version)
      (contains? by-version (:jsonld/id bv))
      (contains? by-plan plan-id))))

(defn named-versions-add
  "The monotone policy: a version statement ADDS to urpx:applicableToRatePlan
   and never removes from it.

   Sound under RDF, which is what URPX documents denote: merging graphs adds
   triples and never retracts, so a larger corpus can only ever yield more
   compatibility. It also stops the plan-level statement being cancelled by
   triples that never mention the plan it names.

   KNOWN COST. It leaves urpx:applicableToRatePlanVersion with almost nothing
   to do: it can only widen, and only to a version whose plan is not already
   named. A publisher wanting to say \"this modifier applies only to version X
   of plan P\" has no way to say it under this reading."
  [mv bv plan-id]
  (let [[by-version by-plan] (applicability mv)]
    (or (contains? by-version (:jsonld/id bv))
        (contains? by-plan plan-id))))

;; ---------------------------------------------------------------------------
;; Open dimensions
;; ---------------------------------------------------------------------------

(defn- open-dimensions
  "Container @id -> the urpx:ProfileAlternative @ids it offers, across every
   version in the arrangement.

   urpx:PlanElementsShape.hasProfileAlternatives is sh:maxCount 1, so one
   version reaches at most one container, but a composition reaches one per
   document. The container's urpx:hasProfileAlternative members are already the
   CELLS of whatever cross product its dimensions describe, so a choice is one
   member per container rather than one per dimension."
  [versions]
  (into {}
        (for [v versions
              [cid pas] (index/entities-of-type v "urpx:ProfileAlternatives")
              :let [alts (into [] (keep :jsonld/id) (:urpx/hasProfileAlternative pas))]
              :when (seq alts)]
          [cid alts])))

(defn- selection-combinations
  "Every way of choosing one alternative per open dimension. One combination
   when nothing is open, which is the arrangement itself rather than none."
  [dims]
  (reduce (fn [acc [cid alts]]
            (for [m acc a alts] (assoc m cid a)))
          [{}]
          (sort-by key dims)))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn plan-versions
  "Every urpx:RatePlanVersion in `documents`, as a vector of snapshots.

   `:at` restricts to the version of each plan identity in force at that
   instant. Omit it for every version the documents carry."
  ([documents] (plan-versions documents nil))
  ([documents opts]
   (let [_       (coerce/check-coerced! documents "urpx.arrangements/plan-versions")
         t       (:at opts)
         repeated (or (:repeated-ids opts) repeated-ids-refuse)
         triples (-> {"urpx:RatePlanVersion"
                      (mapcat #(versions-in % "urpx:RatePlanVersion") documents)}
                     repeated (get "urpx:RatePlanVersion"))]
     (->> (current-versions triples t)
          vals
          (mapcat #(map second %))
          (sort-by :jsonld/id)
          vec))))

(defn applicable-modifiers
  "The urpx:RatePlanModifierVersions in `documents` that MAY apply to
   `base-version`.

   This is the query a consumer actually wants, and it is the half URPX can
   answer. urpx:applicableToRatePlan states compatibility with a plan identity;
   urpx:applicableToRatePlanVersion states it against a specific version. Where
   a modifier names any versions, those are taken to be exhaustive, because the
   version-level statement is the more specific.

   WHICH READING applies is a caller policy, `:compatibility`, defaulting to
   `named-versions-narrow`. URPX does not settle whether naming a version
   narrows the plan-level statement or adds to it, so this library does not
   settle it either; see the policies for the evidence on each side.

   WHAT THIS DOES NOT ANSWER, deliberately. Which of these a given customer HAS
   is a fact about the customer, and URPX places customer data outside its
   scope: urpx:AccountServiceAgreement carries the skos:note \"Service account
   and agreement data lives in customer data, not URPX.\" Nor does URPX say
   which modifiers may be held TOGETHER: there is no property anywhere in the
   ontology expressing exclusivity or combinability between modifiers, and
   urpx:modifierCategory and urpx:modifierType, which might have served as a
   signal, are optional. So CARE and FERA are both returned for SCE
   Schedule D and nothing in the documents says a customer has at most one.
   Choosing among them is the caller's, which is why `arrangements` takes a
   combining policy rather than inventing one.

   Options:
     :at             a ZonedDateTime. Restricts to modifier versions current then.
     :repeated-ids  fn from {kind -> [[identity-id version document] ...]} to the
               same, deciding what one @id naming several distinct snapshots
               means. Defaults to `repeated-ids-refuse`; `repeated-ids-union`
               merges them, which is the RDF reading. A THIRD POLICY for the
               same reason as the other two: URPX does not say, and a client
               reading one filing and a client merging a corpus want different
               answers.
     :compatibility  fn `[modifier-version base-version plan-id] -> boolean`.
                     Defaults to `named-versions-narrow`; `named-versions-add`
                     is the monotone alternative."
  ([documents base-version] (applicable-modifiers documents base-version nil))
  ([documents base-version opts]
   (let [_       (coerce/check-coerced! documents "urpx.arrangements/applicable-modifiers")
         t       (:at opts)
         compat  (or (:compatibility opts) named-versions-narrow)
         plan-id (:jsonld/id (:urpx/forRatePlan base-version))
         repeated (or (:repeated-ids opts) repeated-ids-refuse)
         triples (-> {"urpx:RatePlanModifierVersion"
                      (mapcat #(versions-in % "urpx:RatePlanModifierVersion") documents)}
                     repeated (get "urpx:RatePlanModifierVersion"))]
     (->> (current-versions triples t)
          vals
          (mapcat #(map second %))
          (filter #(compat % base-version plan-id))
          (sort-by :jsonld/id)
          vec))))

(defn offered-by
  "Group version snapshots by the urpx:Organization @id that offers them, for a
   broader search than one plan at a time.

   urpx:applicableToRateGroup and urpx:hasServiceClass might look like
   alternatives, but both appear ZERO times in RatePlanModifierVersionShape, so
   they cannot be set on a modifier version at all."
  [versions]
  (into (sorted-map)
        (map (fn [[k vs]] [k (vec (sort-by :jsonld/id vs))]))
        (group-by #(:jsonld/id (:urpx/offeredBy %)) versions)))

(defn one-modifier-at-most
  "The default combining policy: the empty set, and each modifier on its own.

   Conservative because URPX cannot state which modifiers combine. Every
   arrangement it yields is one the documents support, and it is knowingly
   INCOMPLETE: real multi-overlay arrangements exist, and California publishes
   identifiers for some of them, so a caller that knows its own program rules
   should pass its own policy rather than accept this one."
  [modifier-versions]
  (cons [] (map vector modifier-versions)))

(defn every-subset
  "A combining policy yielding every subset, including the empty one.

   Complete and unfiltered: it will pair mutually exclusive programs, since
   nothing in the documents marks them exclusive. 2^n per base version, so use
   it on a narrowed modifier list rather than a whole corpus."
  [modifier-versions]
  (reduce (fn [acc m] (concat acc (map #(conj % m) acc)))
          [[]]
          modifier-versions))

(defn arrangement-id
  "The intrinsic identity of `arrangement`:

     [base-version-iri [modifier-version-iris sorted] [[container alternative] sorted]]

   Computable from the documents alone, stable across regeneration, external
   nothing. Two arrangements are the same when this matches and different
   otherwise, INCLUDING when they resolve to the same price: a bundled customer
   and one buying generation elsewhere may pay the same delivery rate and are
   on different arrangements."
  [arrangement]
  [(:urpx.arrangement/version-id arrangement)
   (vec (sort (:urpx.arrangement/modifier-version-ids arrangement)))
   (vec (sort-by first (:urpx.arrangement/selections arrangement)))])

(defn arrangements
  "Every service arrangement `documents` expresses, as a vector.

   `documents` is a seq of coerced urpx:RatePlan and urpx:RatePlanModifier
   documents, the same values urpx.price takes.

   Options:
     :at       a ZonedDateTime. Restricts to arrangements whose every component
               is CURRENT at that instant: the latest version of each identity
               to have taken effect and not expired. Omit to enumerate the whole
               expressed space across every version, a different and larger
               question.
     :combine  fn from the compatible modifier versions of one base version to a
               seq of the modifier SETS a customer might hold. Defaults to
               `one-modifier-at-most`.
     :compatibility  fn `[modifier-version base-version plan-id] -> boolean`
               deciding which modifiers MAY apply. Defaults to
               `named-versions-narrow`; `named-versions-add` is the monotone
               alternative. TWO POLICIES, not one, because URPX does not say
               whether naming a urpx:applicableToRatePlanVersion narrows
               urpx:applicableToRatePlan or adds to it. They differ only when
               a modifier names some version, about a base version it does not
               name whose plan it names; see the policies.

   THE COMBINING POLICY IS THE CALLER'S BECAUSE URPX CANNOT STATE IT. Nothing
   in the ontology expresses exclusivity or combinability between modifiers,
   and urpx:modifierCategory and urpx:modifierType, the only properties that
   might have served, are optional. So CARE and FERA are both compatible with
   SCE Schedule D and no document says a customer has at most one. The default
   is conservative and knowingly incomplete; `every-subset` is complete and
   knowingly wrong; a caller holding its own program rules should pass those
   instead. Use `applicable-modifiers` to see what it is choosing among.

   Each arrangement is a map:
     :urpx.arrangement/rate-plan            the coerced base document
     :urpx.arrangement/version              the base version snapshot
     :urpx.arrangement/version-id           its @id
     :urpx.arrangement/plan-id              the plan identity @id
     :urpx.arrangement/modifiers            coerced modifier documents, in order
     :urpx.arrangement/modifier-versions    their snapshots
     :urpx.arrangement/modifier-version-ids their @ids
     :urpx.arrangement/selections           {container-@id alternative-@id}

   Deterministic: sorted by `arrangement-id`, so regenerating the same
   documents gives the same order. Nothing here consults wall-clock time."
  ([documents] (arrangements documents nil))
  ([documents opts]
   (let [_       (coerce/check-coerced! documents "urpx.arrangements/arrangements")
         combine (or (:combine opts) one-modifier-at-most)
         compat  (or (:compatibility opts) named-versions-narrow)
         repeated (or (:repeated-ids opts) repeated-ids-refuse)
         t     (:at opts)
         plans (mapcat #(versions-in % "urpx:RatePlanVersion") documents)
         mods  (mapcat #(versions-in % "urpx:RatePlanModifierVersion") documents)
         merged (repeated {"urpx:RatePlanVersion"         plans
                           "urpx:RatePlanModifierVersion" mods})
         live-plans (current-versions (get merged "urpx:RatePlanVersion") t)
         live-mods  (current-versions (get merged "urpx:RatePlanModifierVersion") t)]
     ;; Base version and modifier versions are carried as [id version doc]
     ;; triples the whole way, so the document attached to an arrangement is
     ;; the one the snapshot was read out of rather than one looked up by @id.
     (->> (for [[plan-id bvs] live-plans
                [_ bv bdoc] bvs
                :let [ctriples (vec (sort-by #(:jsonld/id (second %))
                                             (for [[_ cands] live-mods
                                                   [_ mv _ :as tr] cands
                                                   :when (compat mv bv plan-id)]
                                               tr)))
                      ;; `combine` is a CALLER-SUPPLIED policy and sees version
                      ;; nodes, never the [id version doc] triples this
                      ;; namespace carries internally. Mapping the chosen nodes
                      ;; back to their triples is by NODE, not by @id: by @id is
                      ;; the lookup that bound 641 modifier snapshots to the
                      ;; wrong document. Nodes are distinct within this list -
                      ;; the :repeated-ids policy has already refused or
                      ;; merged one @id naming two different nodes, and
                      ;; deduplicated exact repeats, so the map is total.
                      by-node (into {} (map (fn [tr] [(second tr) tr])) ctriples)]
                mvs (combine (mapv second ctriples))
                :let [mvs      (vec (sort-by :jsonld/id mvs))
                      mtriples (mapv by-node mvs)
                      dims     (open-dimensions (cons bv mvs))]
                sel (selection-combinations dims)]
            {:urpx.arrangement/rate-plan            bdoc
             :urpx.arrangement/version              bv
             :urpx.arrangement/version-id           (:jsonld/id bv)
             :urpx.arrangement/plan-id              plan-id
             :urpx.arrangement/modifiers            (mapv #(nth % 2) mtriples)
             :urpx.arrangement/modifier-versions    mvs
             :urpx.arrangement/modifier-version-ids (mapv :jsonld/id mvs)
             :urpx.arrangement/selections           sel})
          (sort-by arrangement-id)
          vec))))

(defn arrangement-pairs
  "Every (base version, modifier set) pair `documents` expresses, as a set:

      #{[base-version-iri #{modifier-version-iri ...}] ...}

   The enumeration collapsed to one row per composition, with any
   ProfileAlternative choices NOT expanded. That is a coarser question than
   `arrangements` answers and a deliberately different one: an arrangement with
   an open dimension is several arrangements and one pair.

   FOR COMPARING TWO IMPLEMENTATIONS. A consumer cross-checking an independent
   enumerator against this one should compare pair SETS rather than arrangement
   counts, because the two sides can legitimately disagree about whether a
   pending choice is one row or several while agreeing completely about what
   composes with what.

   `opts` is what `arrangements` takes. PIN EVERY POLICY EXPLICITLY when the
   result is an expected value in a gate: `:at`, `:combine`, `:compatibility`
   and `:repeated-ids` each change the answer, and matching defaults across two
   implementations is agreement by coincidence rather than by construction."
  ([documents] (arrangement-pairs documents nil))
  ([documents opts]
   (into #{}
         (map (juxt :urpx.arrangement/version-id
                    (comp set :urpx.arrangement/modifier-version-ids)))
         (arrangements documents opts))))

(defn- bound-opts
  "The opts that bind `arrangement` to its own documents, merged under `opts`.

   ONE BUILDER for every entry point here. resolve-at, segments-for and
   schedule-for must hand urpx.price an identical binding or they answer
   different questions about the same arrangement, and a second copy of this
   map is the drift that put an effectiveDateTime filter in this namespace
   while urpx.price read effectiveDate too.

   The two keys the arrangement itself determines are REFUSED rather than
   merged. Silently letting a caller's `:version` win would restore the exact
   defect these functions exist to prevent; silently letting the arrangement
   win would ignore an opt the caller meant. A caller who wants a different
   version or a different selection wants a different arrangement."
  [arrangement opts]
  (when-let [bound (seq (filter (set (keys opts)) [:version :profile-alternatives]))]
    (throw (ex-info "Opt is determined by the arrangement and cannot be overridden"
                    {:reason :urpx.arrangement/opt-determined-by-arrangement
                     :keys (vec bound)
                     :arrangement-id (arrangement-id arrangement)})))
  (merge {:profile-alternatives (:urpx.arrangement/selections arrangement)
          :version (into {(:jsonld/id (:urpx.arrangement/rate-plan arrangement))
                          (:urpx.arrangement/version arrangement)}
                         (map (fn [d v] [(:jsonld/id d) v])
                              (:urpx.arrangement/modifiers arrangement)
                              (:urpx.arrangement/modifier-versions arrangement)))}
         opts))

(defn resolve-at
  "Resolve `arrangement` at `zdt`, returning what
   `urpx.price/resolve-prices-with-modifiers` returns.

   A convenience over urpx.price, not a replacement: it binds each document to
   its own version and hands the arrangement's selections through as
   `:profile-alternatives`, so the opts cannot reach a document they do not
   belong to. Both `:version` defects fixed in 0.28.0 were an opt and a document
   failing to line up: one applied the base plan's snapshot to every modifier,
   the other re-derived the snapshot on the holiday path and ignored the opt
   entirely. An arrangement makes both unrepresentable rather than repeatedly
   fixable.

   The binding does more than choose. Handed a document carrying several
   version snapshots, urpx.price refuses outright rather than picking one, so
   this is what makes such a document resolvable at all.

   Extra `opts` merge over the arrangement's own, except `:version` and
   `:profile-alternatives`, which are refused. See `bound-opts`."
  ([arrangement ^ZonedDateTime zdt] (resolve-at arrangement zdt nil))
  ([arrangement ^ZonedDateTime zdt opts]
   (price/resolve-prices-with-modifiers
    (:urpx.arrangement/rate-plan arrangement)
    (:urpx.arrangement/modifiers arrangement)
    zdt
    (bound-opts arrangement opts))))

;; ---------------------------------------------------------------------------
;; Horizons
;;
;; `resolve-at` answers one instant. A price server publishes a WINDOW: the
;; interval containing now and some number of them forward. Both functions
;; below exist because doing that through urpx.schedule directly means taking
;; an arrangement apart into its rate-plan and modifier documents and rebuilding
;; the per-document `:version` map by hand, which is the reconstruction
;; `resolve-at` exists to prevent and the defect class this library has fixed
;; three times.
;;
;; `start` and `end` are Instants and `zone` may be nil, matching urpx.schedule
;; rather than `resolve-at`'s ZonedDateTime. A caller mixing the two namespaces
;; over one horizon should not have to convert between them at every call.
;; ---------------------------------------------------------------------------

(defn segments-for
  "Exact price segments for `arrangement` across `[start, end)`, as
   `urpx.schedule/price-segments` returns them.

   PREFER THIS over `schedule-for` for publishing. Boundaries are derived from
   the documents rather than discovered by sampling, so a segment ends where
   the tariff actually changes and not where a step grid happened to land. For
   a consumer handing intervals to an optimizer that is the difference between
   a schedule that is right and one that is right only because every bracket in
   that plan sits on an hour.

   A resolution that throws inside the window becomes a segment carrying
   `:urpx.interval/refused` rather than aborting the horizon, and equal
   refusals merge, so a stretch the arrangement does not cover is one segment
   and not one per candidate instant. That is what lets a publisher emit the
   part of a window it can price and say plainly what it cannot.

   `zone` may be nil when the plan declares `urpx:timezoneIdentifier`. Extra
   `opts` are as `resolve-at`: merged, except the two the arrangement
   determines, which are refused."
  ([arrangement start end] (segments-for arrangement start end nil nil))
  ([arrangement start end zone] (segments-for arrangement start end zone nil))
  ([arrangement ^Instant start ^Instant end zone opts]
   (schedule/price-segments (:urpx.arrangement/rate-plan arrangement)
                            (:urpx.arrangement/modifiers arrangement)
                            start end zone
                            (bound-opts arrangement opts))))

(defn schedule-for
  "A stepped price schedule for `arrangement` across `[start, end)`, as
   `urpx.schedule/price-schedule-with-modifiers` returns them.

   Walks a grid, default hourly, merging adjacent steps whose resolution is
   equal. Use it when a caller genuinely wants a fixed cadence; use
   `segments-for` when they want the truth about where prices change, which for
   a price server is almost always the question.

   Same `zone` and `opts` handling as `segments-for`."
  ([arrangement start end] (schedule-for arrangement start end nil nil))
  ([arrangement start end zone] (schedule-for arrangement start end zone nil))
  ([arrangement ^Instant start ^Instant end zone opts]
   (schedule/price-schedule-with-modifiers
    (:urpx.arrangement/rate-plan arrangement)
    (:urpx.arrangement/modifiers arrangement)
    start end zone
    (bound-opts arrangement opts))))

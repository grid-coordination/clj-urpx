(ns urpx.coverage
  "Read what a version snapshot CLAIMS about its own coverage.

  Every urpx:RatePlanVersion and urpx:RatePlanModifierVersion carries a
  urpx:CoverageClaim: a verdict on what can be answered from that snapshot under
  stated conditions. The verdict is the standard's own answer to \"what am I
  holding?\".

  NOT A JUDGEMENT OF THIS LIBRARY'S. Per the ontology, CoverageClaim instances
  \"are produced by an implementation or publishing workflow\", so a claim says
  what the PUBLISHER asserts about a snapshot, not what clj-urpx found. Nothing
  here re-derives a verdict or second-guesses one; reading is the whole job.

  OPTIONAL BY CONSTRUCTION. This requires urpx.index and nothing else of the
  library: not urpx.price, not urpx.scenario-cost-model. Nothing in src/
  requires it, which a test asserts over the whole source tree.

  TWO FIELDS ARE EASY TO OVER-READ. `selfContained` is defined as \"false iff
  any PriceDefinitionReference is referenceInclusion retrievedElsewhere\", so
  `true` means \"nothing here is fetched from elsewhere\", which is not
  \"complete\" and must not be read as reassurance; on a snapshot with no
  PriceDefinitionReference it is vacuously true. And the CoverageClaim note says
  producers SHOULD attach a TemporalScope with scopeKind urpx:coverage, which no
  shape requires, so a verdict with no scope is conformant and a consumer
  publishing a horizon cannot assume a verdict covers it."
  (:require [urpx.index :as index]
            [urpx.schema :as schema])
  (:import [java.time LocalDateTime ZoneId]))

(def completeness-results
  "The six urpx:CompletenessResultValue members, from
   urpx-shacl:CompletenessResultValueShape's sh:in.

   No ordering is stated among them and none is implied here. The absence is
   meaningful rather than an oversight: the same ontology uses skos:broader
   where it does mean a hierarchy. They are distinct KINDS, not degrees:
   `partial` asserts a gap in the snapshot while what is present is usable,
   `unsupported` that the source has it and the representation cannot express
   it, `pending` that the source has not published it yet, `noCoverage` that it
   was evaluated and nothing exists anywhere, and `unknown` that no evaluation
   has run at all."
  #{"urpx:complete" "urpx:partial" "urpx:pending"
    "urpx:unsupported" "urpx:unknown" "urpx:noCoverage"})

(defn- bare-ref?
  "True if `v` is a reference and nothing else: an @id with no content of its
   own. A node carrying properties is the thing itself, however it is nested."
  [v]
  (and (map? v) (:jsonld/id v) (nil? (:jsonld/type v)) (= 1 (count v))))

(defn- deref-node
  "Resolve `v` against `doc` when it is a bare reference, else return it. Yields
   `::unresolved` when a reference cannot be followed, which callers distinguish
   from an absent one."
  [v doc]
  (cond
    (not (bare-ref? v)) v
    (nil? doc)          ::unresolved
    :else               (or (index/resolve-ref (index/build-index doc) v) ::unresolved)))

(defn claim
  "The urpx:CoverageClaim on `version`, or `::absent`.

   `doc` is the document the version came from, needed only to follow a claim
   recorded as a bare @id reference; the schema admits that arm
   (`[:or CoverageClaim ExclusiveRef]`).

   REFUSES `:urpx.coverage/claim-ref-unresolved` when the claim IS a bare
   reference and cannot be followed, because a reference that goes nowhere is
   not the same as no claim: one is a document that says something this reader
   cannot reach, the other a document that says nothing."
  ([version] (claim version nil))
  ([version doc]
   (let [c (:urpx/hasCoverageClaim version)]
     (cond
       (nil? c) ::absent
       :else
       (let [node (deref-node c doc)]
         (if (= ::unresolved node)
           (throw (ex-info (str "Coverage claim on " (pr-str (:jsonld/id version))
                                " is a reference to " (pr-str (:jsonld/id c))
                                " that cannot be followed"
                                (if doc " in the supplied document"
                                    "; no document was supplied to resolve it against")
                                ". A reference that goes nowhere is not the same as "
                                "no claim, so this refuses rather than reporting "
                                "the version as unclaimed.")
                           {:reason  :urpx.coverage/claim-ref-unresolved
                            :version-id (:jsonld/id version)
                            :claim-ref  (:jsonld/id c)
                            :document-supplied? (some? doc)}))
           node))))))

(defn- completeness-of
  [c version-id]
  (let [id (get-in c [:urpx/completenessResult :jsonld/id])]
    (cond
      (nil? id) nil
      (completeness-results id) id
      :else
      (throw (ex-info (str "Coverage claim on " (pr-str version-id) " names a "
                           "completeness result this reader does not know: "
                           (pr-str id) ". Reporting nil would read as 'not "
                           "complete', which is a guess about a verdict the "
                           "document did state.")
                      {:reason :urpx.coverage/unknown-completeness-result
                       :version-id version-id
                       :completeness-result id
                       :known completeness-results})))))

(defn- self-contained-of
  "The urpx:selfContained verdict, its source, and whether the two places it may
   appear disagree.

   The ontology settles precedence rather than leaving it open: the property
   \"may be materialized on the version itself for direct query; when both are
   present they MUST agree, and the claim is authoritative\". So a disagreement
   is reported and NOT refused: the standard says which wins. It is still a
   violated MUST, which is urpx.validate's business rather than this reader's.

   REFUSES `:urpx.coverage/self-contained-contradicts-references` for a
   different case, where nothing states precedence: the claim asserts true while
   the version carries a PriceDefinitionReference that is retrievedElsewhere.
   selfContained is DERIVED as \"false iff any PriceDefinitionReference is
   referenceInclusion retrievedElsewhere\", so the document contradicts its own
   derivation and there is no rule saying whether the assertion or the
   derivation wins."
  [c version]
  (let [claimed (:urpx/selfContained c)
        on-ver  (:urpx/selfContained version)
        elsewhere (filter #(= "urpx:retrievedElsewhere"
                              (get-in % [:urpx/referenceInclusion :jsonld/id]))
                          (index/as-vec (:urpx/hasPriceDefinitionReference version)))
        value   (if (some? claimed) claimed on-ver)]
    (when (and (true? claimed) (seq elsewhere))
      (throw (ex-info (str "Coverage claim on " (pr-str (:jsonld/id version))
                           " asserts selfContained true while the version carries "
                           (count elsewhere) " PriceDefinitionReference(s) marked "
                           "retrievedElsewhere. selfContained is derived as false "
                           "iff any such reference exists, so the document "
                           "contradicts itself and nothing states whether the "
                           "assertion or the derivation wins.")
                      {:reason :urpx.coverage/self-contained-contradicts-references
                       :version-id (:jsonld/id version)
                       :retrieved-elsewhere (count elsewhere)})))
    {:urpx.coverage/self-contained value
     :urpx.coverage/self-contained-source (cond (some? claimed) :claim
                                                (some? on-ver)  :version)
     :urpx.coverage/self-contained-disagrees?
     (and (some? claimed) (some? on-ver) (not= claimed on-ver))}))

(defn- zone-for
  "The zone `version`'s dateTimes written without an offset are read in: the
   first of the urpx:timezoneIdentifier on `version`, on `doc`, and `zone`.
   They must have the same rules. Nil when none states one.

   REFUSES `:urpx.coverage/conflicting-timezone` when their rules differ."
  ^ZoneId [version doc ^ZoneId zone]
  (let [stated (cond-> (into [] (comp (keep :urpx/timezoneIdentifier) (map #(ZoneId/of %)))
                             [version doc])
                 zone (conj zone))
        zones  (set stated)]
    (when (> (count (into #{} (map #(.getRules ^ZoneId %)) zones)) 1)
      (throw (ex-info (str "Cannot zone the coverage claim on "
                           (pr-str (:jsonld/id version)) ": "
                           (pr-str (sort (map str zones))) " are all stated for it, "
                           "and its wall-clock dateTimes name a different moment "
                           "in each.")
                      {:reason :urpx.coverage/conflicting-timezone
                       :version-id (:jsonld/id version)
                       :zones (vec (sort (map str zones)))})))
    (first stated)))

(defn- zoned
  "`v` as a ZonedDateTime in the zone `zone` delivers when it is a
   LocalDateTime, else unchanged. REFUSES `:urpx.coverage/zone-required` for a
   LocalDateTime when `zone` delivers nil."
  [v zone version-id property]
  (cond
    (not (instance? LocalDateTime v)) v
    @zone (schema/in-zone v @zone)
    :else (throw (ex-info (str "The coverage claim on " (pr-str version-id) " writes "
                               property " " v " without an offset, and neither "
                               "the version, its document nor the caller states "
                               "the zone it is read in.")
                          {:reason :urpx.coverage/zone-required
                           :version-id version-id
                           :property property}))))

(defn- evaluations-of
  "The Evaluations a claim is explained by, flattened to what a reader wants.

   An explainedBy reference that cannot be followed is NOT a refusal: the
   VERDICT is still readable without its rationale. It is reported as
   `{:unresolved-ref id}` so the gap is visible rather than silent. Refuse when
   the verdict cannot be read; flag when only the reason for it cannot."
  [c doc zone vid]
  (mapv (fn [e]
          (let [node (deref-node e doc)]
            (if (= ::unresolved node)
              {:unresolved-ref (:jsonld/id e)}
              {:kind        (get-in node [:urpx/evaluationKind :jsonld/id])
               :evaluated-on (zoned (:urpx/evaluatedOn node) zone vid "urpx:evaluatedOn")
               :description (:urpx/description node)})))
        (index/as-vec (:urpx/explainedBy c))))

(defn- zone-window
  "`window` with its dateTime bounds zoned. Its date bounds stay calendar dates."
  [window zone vid]
  (reduce (fn [w [k property]]
            (if (contains? w k) (update w k zoned zone vid property) w))
          window
          [[:urpx/startDateTime "urpx:startDateTime"]
           [:urpx/endDateTime "urpx:endDateTime"]]))

(defn- coverage-window-of
  "The TemporalScope whose scopeKind is urpx:coverage, or nil.

   urpx:hasTemporalScope is unconstrained upstream: it appears as sh:path zero
   times in the shapes graph and in sh:ignoredProperties 79 times, so a claim
   may carry any number of scopes of any kind and nothing says otherwise.

   REFUSES `:urpx.coverage/ambiguous-coverage-scope` when more than one names
   urpx:coverage. Two windows for one verdict, and nothing says which is meant
   or that they should be unioned; picking either would be the arbitrary choice
   this library exists not to make."
  [c doc version-id]
  (let [covers (filter #(= "urpx:coverage" (get-in % [:urpx/scopeKind :jsonld/id]))
                       (keep #(let [n (deref-node % doc)] (when-not (= ::unresolved n) n))
                             (index/as-vec (:urpx/hasTemporalScope c))))]
    (when (> (count covers) 1)
      (throw (ex-info (str "Coverage claim on " (pr-str version-id) " carries "
                           (count covers) " TemporalScopes with scopeKind "
                           "urpx:coverage. A verdict covers one window, and "
                           "nothing says which of these is meant or that they "
                           "should be combined.")
                      {:reason :urpx.coverage/ambiguous-coverage-scope
                       :version-id version-id
                       :count (count covers)})))
    (first covers)))

(defn of-version
  "What `version` claims about its own coverage, as a report map.

      :urpx.coverage/version-id      the snapshot's @id
      :urpx.coverage/version-type    its @type
      :urpx.coverage/stated?         whether it carries a CoverageClaim at all
      :urpx.coverage/completeness    one of `completeness-results` as the IRI
                                     STRING the document uses, or nil
      :urpx.coverage/self-contained  true / false / nil
      :urpx.coverage/self-contained-source        :claim, :version, or nil
      :urpx.coverage/self-contained-disagrees?    the two places disagree
      :urpx.coverage/confidence      IRI string or nil
      :urpx.coverage/stale           true / false / nil
      :urpx.coverage/fallback-sourced             true / false / nil
      :urpx.coverage/scope-stated?   a urpx:coverage TemporalScope is present
      :urpx.coverage/coverage-window that scope node, or nil, with its
                                     dateTimes as ZonedDateTime
      :urpx.coverage/evaluations     [{:kind :evaluated-on :description}], or
                                     {:unresolved-ref id} for one that cannot
                                     be followed. :evaluated-on is a
                                     ZonedDateTime

   `zone`, a java.time.ZoneId, is the zone a dateTime written without an
   offset is read in. Omitted, it is the urpx:timezoneIdentifier on `version`
   or on `doc`. A dateTime written with an offset keeps it.
   When such a dateTime is present, REFUSES `:urpx.coverage/zone-required` if
   no zone is stated, and `:urpx.coverage/conflicting-timezone` if `zone`, the
   version and `doc` state zones whose rules differ.

   ABSENT IS NOT UNKNOWN. A version with no claim reports `stated? false` and
   `completeness nil`, never `\"urpx:unknown\"`. urpx:unknown is itself a
   verdict, meaning evaluated-nowhere, and a document that asserts it has said
   something a document that omits the claim has not. Conflating them would
   invent a claim on the publisher's behalf.

   `completeness` is the IRI string as written, `\"urpx:partial\"`, rather than
   a keyword of this library's invention. It maps 1:1 to what the document says
   and needs no translation table to compare against one.

   SCOPE-STATED IS NOT WINDOW-STATED. urpx:scopeKind is the only required field
   on a TemporalScope, so a scope may name its role and give no bounds. A caller
   deciding whether a verdict covers a window must read `coverage-window` rather
   than trusting `scope-stated?`."
  ([version] (of-version version nil nil))
  ([version doc] (of-version version doc nil))
  ([version doc ^ZoneId zone]
   (let [vid (:jsonld/id version)
         c   (claim version doc)]
     (if (= ::absent c)
       {:urpx.coverage/version-id vid
        :urpx.coverage/version-type (:jsonld/type version)
        :urpx.coverage/stated? false
        :urpx.coverage/completeness nil
        :urpx.coverage/self-contained (:urpx/selfContained version)
        :urpx.coverage/self-contained-source (when (some? (:urpx/selfContained version)) :version)
        :urpx.coverage/self-contained-disagrees? false
        :urpx.coverage/confidence nil
        :urpx.coverage/stale nil
        :urpx.coverage/fallback-sourced nil
        :urpx.coverage/scope-stated? false
        :urpx.coverage/coverage-window nil
        :urpx.coverage/evaluations []}
       (let [zone   (delay (zone-for version doc zone))
             window (some-> (coverage-window-of c doc vid) (zone-window zone vid))]
         (merge {:urpx.coverage/version-id vid
                 :urpx.coverage/version-type (:jsonld/type version)
                 :urpx.coverage/stated? true
                 :urpx.coverage/completeness (completeness-of c vid)
                 :urpx.coverage/confidence (get-in c [:urpx/confidence :jsonld/id])
                 :urpx.coverage/stale (:urpx/stale c)
                 :urpx.coverage/fallback-sourced (:urpx/fallbackSourced c)
                 :urpx.coverage/scope-stated? (some? window)
                 :urpx.coverage/coverage-window window
                 :urpx.coverage/evaluations (evaluations-of c doc zone vid)}
                (self-contained-of c version)))))))

;; ---------------------------------------------------------------------------
;; Scenario cost models
;;
;; A model is several versions, each with its own verdict. Reporting ONE
;; verdict for the whole would presume the verdicts compose, and they do not.
;; See `of-model`.
;;
;; These read the `:urpx.scenario-cost-model/` keys of a map without requiring
;; urpx.scenario-cost-model. That is a data contract rather than a code
;; dependency, and it is what keeps both namespaces optional: a consumer can use
;; either, both, or neither.
;; ---------------------------------------------------------------------------

(defn of-model
  "What each part of `model` claims about its own coverage.

      :urpx.coverage/parts        [<of-version report> ...] in model order,
                                  base first, each carrying
                                  :urpx.coverage/role of :base or :modifier
      :urpx.coverage/completeness-counts  {\"urpx:partial\" 2, ...}
      :urpx.coverage/unstated-count       parts carrying no claim
      :urpx.coverage/every-part-stated?
      :urpx.coverage/every-part-complete?
      :urpx.coverage/every-part-self-contained?
      :urpx.coverage/any-part-scope-stated?

   THERE IS NO MODEL-LEVEL VERDICT, and the absence is the design rather
   than an omission. A test pins the key set so that adding one fails.

   Two reasons, the second decisive. No ordering is stated among the six
   completeness members, and the absence is meaningful: the same ontology uses
   skos:broader where it does mean a hierarchy. And every definition is relative
   to \"the claimed scope\" of its own snapshot, while no snapshot states one, so
   composing would require knowing each part's scope and that their union equals
   the model's. Neither is known. Composition is undefined at the DOMAIN
   level, not merely unranked, and would stay undefined even if URPX later
   imposed an order.

   A minimum would also be positively wrong in a case the vocabulary allows: a
   modifier claiming urpx:noCoverage contributes nothing to the composition, so
   a complete base with a noCoverage modifier is not a noCoverage model.

   EVERY DERIVED BOOLEAN NAMES THE PARTS, never the model. A conjunction
   of per-part facts needs no ordering; a verdict does, and the naming is what
   keeps the two from being confused at a call site. Note that
   `every-part-complete?` still does NOT mean the model is complete: each
   claim covers its own snapshot's required elements, and nothing claims the
   elements a composition needs and neither part carries.

   Each part is read against ITS OWN document, the one the model bound to
   it, so a claim recorded as a bare reference resolves correctly rather than
   against whichever document happened to be at hand, and its dateTimes are
   zoned by the urpx:timezoneIdentifier the model declared on that document."
  [model]
  (let [parts (into [(assoc (of-version (:urpx.scenario-cost-model/version model)
                                        (:urpx.scenario-cost-model/rate-plan model))
                            :urpx.coverage/role :base)]
                    (map (fn [v d] (assoc (of-version v d) :urpx.coverage/role :modifier))
                         (:urpx.scenario-cost-model/modifier-versions model)
                         (:urpx.scenario-cost-model/modifiers model)))
        stated (filter :urpx.coverage/stated? parts)]
    {:urpx.coverage/parts parts
     :urpx.coverage/completeness-counts (frequencies (keep :urpx.coverage/completeness parts))
     :urpx.coverage/unstated-count (- (count parts) (count stated))
     :urpx.coverage/every-part-stated? (= (count parts) (count stated))
     :urpx.coverage/every-part-complete?
     (every? #(= "urpx:complete" (:urpx.coverage/completeness %)) parts)
     :urpx.coverage/every-part-self-contained?
     (every? #(true? (:urpx.coverage/self-contained %)) parts)
     :urpx.coverage/any-part-scope-stated?
     (boolean (some :urpx.coverage/scope-stated? parts))}))

(defn descriptions
  "The Evaluation prose from an `of-model` report, in part order, as
   `[{:role :version-id :description} ...]`.

   THE PROSE IS THE ONLY PLACE A GAP IS NAMED. A completeness result says THAT
   something is missing; the linked Evaluation says WHAT, and that prose can be
   specific enough to act on, naming for instance the customer groups whose
   component exemptions a filing does not represent.

   So a consumer deciding whether a `partial` snapshot is publishable is reading
   this, not the verdict keyword. Entries with no description are dropped;
   `explainedBy` is optional."
  [model-report]
  (into []
        (for [p (:urpx.coverage/parts model-report)
              e (:urpx.coverage/evaluations p)
              :when (:description e)]
          {:role (:urpx.coverage/role p)
           :version-id (:urpx.coverage/version-id p)
           :description (:description e)})))

;; ---------------------------------------------------------------------------
;; Does a verdict cover a window?
;; ---------------------------------------------------------------------------

(defn- scope-bound
  "One end of a TemporalScope as a ZonedDateTime in `zone`, or nil.

   The dateTime form is preferred and the date form read at start of day, which
   is how urpx.price/version-effective-at already reads the version shapes' own
   pair. Two readings of the same kind of bound in one library would be the
   drift that put an effectiveDateTime-only filter in urpx.scenario-cost-model."
  [scope dt-key d-key ^java.time.ZoneId zone]
  (schema/in-zone (or (get scope dt-key) (get scope d-key)) zone))

(defn coverage-interval
  "The window a verdict covers, as `{:tick/beginning <ZonedDateTime>
   :tick/end <ZonedDateTime>}` in `zone`, with either end nil when unbounded on
   that side.

   ZONED, AND THE ZONE IS REQUIRED. A TemporalScope's bounds are usually
   written without an offset, so a bare bound is a wall-clock reading that means
   nothing until a zone is supplied. A bound written with an offset is moved to
   `zone` with its instant kept. Returning it unzoned looked like the honest
   choice and was the opposite: `urpx.schedule` emits
   `{:tick/beginning :tick/end}` carrying ZonedDateTimes, so an unzoned window
   is the same map with the same keys and an incompatible type, and the natural
   comparison does not fail loudly.
   Measured: `tick.alpha.interval/relation` throws a ClassCastException on the
   mixture, and `contains?` returns TRUE regardless of the values. A silently
   wrong answer about whether a verdict covers a published horizon is the worst
   thing this namespace could produce.

   `zone` is a java.time.ZoneId. A rate plan usually declares one as
   `urpx:timezoneIdentifier`, which is what `urpx.schedule` resolves for the
   same purpose; a caller that has already built a horizon has it in hand.

   TICK INTERVALS IN FACT, not only in shape. `tick.alpha.interval`'s functions
   read any map carrying those two keys, so `(ti/relation window segment)`
   answers with Allen's relations directly and `(ti/contains? window interval)`
   agrees with `verdict-applies?` case for case, with both sides now zoned. A
   caller wanting the richer relation should reach for tick rather than for
   another function here. This library requires tick nowhere.

   The dateTime form is preferred and the date form read at start of day, the
   rule `urpx.price/version-effective-at` already gives the version shapes' own
   date-or-dateTime pair.

   REFUSES `:urpx.coverage/coverage-scope-absent` and
   `:urpx.coverage/coverage-scope-unbounded`; see `verdict-applies?`."
  [version-report ^java.time.ZoneId zone]
  (let [scope (:urpx.coverage/coverage-window version-report)
        vid   (:urpx.coverage/version-id version-report)]
    (when-not zone
      (throw (ex-info (str "A coverage window needs a zone: a TemporalScope's "
                           "bounds written without an offset are wall-clock "
                           "readings, and an unzoned interval compares against a "
                           "zoned schedule interval without failing loudly.")
                      {:reason :urpx.coverage/zone-required
                       :version-id vid})))
    (when-not scope
      (throw (ex-info (str "No coverage scope on " (pr-str vid) ", so what window "
                           "its verdict covers is a question the document does "
                           "not answer. The ontology says producers should attach "
                           "a TemporalScope with scopeKind urpx:coverage; no shape "
                           "requires it.")
                      {:reason :urpx.coverage/coverage-scope-absent
                       :version-id vid})))
    (let [beginning (scope-bound scope :urpx/startDateTime :urpx/startDate zone)
          end       (scope-bound scope :urpx/endDateTime :urpx/endDate zone)]
      (when (and (nil? beginning) (nil? end))
        (throw (ex-info (str "The coverage scope on " (pr-str vid) " states its "
                             "role and no bounds, so it says which clock the "
                             "verdict refers to without saying over what span. "
                             "urpx:scopeKind is the only required field on a "
                             "TemporalScope, so this is conformant and still "
                             "unanswerable.")
                        {:reason :urpx.coverage/coverage-scope-unbounded
                         :version-id vid})))
      {:tick/beginning beginning
       :tick/end       end})))

(defn verdict-applies?
  "True if the window `version-report`'s verdict covers CONTAINS `interval`.

   `interval` is `{:tick/beginning <ZonedDateTime> :tick/end <ZonedDateTime>}`,
   exactly what `urpx.schedule`'s `price-schedule` and `price-segments` emit and
   what `urpx.scenario-cost-model/segments-for` hands back, so a caller asks
   this of a segment it is about to publish without converting anything.

   `zone` interprets the scope's own bounds, which URPX states without one. See
   `coverage-interval` for why that is required rather than defaulted.

   THE WINDOW IS [beginning, end), start inclusive and end exclusive, matching
   `urpx.price/version-covers?` exactly. URPX states no convention for a
   TemporalScope's bounds, and two readings of a window inside one library would
   be worse than either. An absent bound is unbounded on that side, so a scope
   stating only a start covers everything after it.

   On bounded windows this is exactly `tick.alpha.interval/contains?`, which
   reads the same map; it exists as a named predicate because the refusals are
   the interesting part, not the comparison.

   REFUSES `:urpx.coverage/coverage-scope-absent` for a claim with no
   urpx:coverage scope, which no shape requires,
   `:urpx.coverage/coverage-scope-unbounded` for a scope that names its role and
   bounds nothing, and `:urpx.coverage/zone-required`.

   False would claim the verdict does not cover the window, which the document
   never said; true would be worse. The honest answer is that the question has
   none, which is what a refusal says. A caller that publishes anyway is
   choosing its own policy, and catching a named reason is how it says so."
  [version-report ^java.time.ZoneId zone interval]
  (let [{:tick/keys [beginning end]} (coverage-interval version-report zone)
        ^java.time.ZonedDateTime from (:tick/beginning interval)
        ^java.time.ZonedDateTime to   (:tick/end interval)]
    (and (or (nil? beginning) (not (.isAfter ^java.time.ZonedDateTime beginning from)))
         (or (nil? end)       (not (.isAfter to ^java.time.ZonedDateTime end))))))

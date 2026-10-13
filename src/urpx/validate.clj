(ns urpx.validate
  "Command-line validator for URPX JSON-LD files.

  Checks one or more files, each on its own, which is what a conversion smoke
  test needs while a set of filings migrates utility by utility and carries two
  vocabularies at once.

      clojure -M:validate path/to/filing.jsonld [more.jsonld ...]

  Exit codes are tri-state, so a run that checked nothing cannot read as
  success:

      0  every entity in every file coerced and validated
      1  at least one entity failed validation, a file broke a rule the
         standard states, or a reference or inline node is not of the type
         its property takes (`urpx.index/mistyped-references`, judged
         within each file)
      2  refused: a path was unreadable, or a file yielded no typed entity
         beyond a wrapper

  A urpx:URPXDocument or urpx:URPXPackage is checked at its own metadata and
  through to the urpx:RatePlan inside it."
  (:require [clojure.string :as str]
            [urpx.coerce :as coerce]
            [urpx.core :as core]
            [urpx.coverage :as coverage]
            [urpx.index :as index]
            [urpx.schema :as schema])
  (:gen-class))

(def ^:private schema-for
  {"urpx:RatePlan"                 schema/RatePlan
   "urpx:RatePlanModifier"         schema/RatePlanModifier
   "urpx:RatePlanVersion"          schema/RatePlanVersion
   "urpx:RatePlanModifierVersion"  schema/RatePlanModifierVersion
   "urpx:URPXDocument"             schema/URPXDocument
   "urpx:URPXPackage"              schema/URPXPackage
   "urpx:ScenarioCostModel"        schema/ScenarioCostModel
   "urpx:SourcePublication"        schema/SourcePublication
   "urpx:TariffPublication"        schema/SourcePublication
   "urpx:RateCase"                 schema/RateCase
   "urpx:ProgramEnrollment"        schema/ProgramEnrollment})

(def ^:private version-types
  #{"urpx:RatePlanVersion" "urpx:RatePlanModifierVersion"})

(defn- target-urpx-version
  "The [major minor] of a document's urpx:targetURPXVersion, or nil when it
   states none, or ::unparseable when it states one this cannot read.

   The value is a URI naming the ontology snapshot, e.g.
   \"https://github.com/urpx-org/urpx/raw/v0.5.1/ontology/urpx-ontology.ttl\"."
  [entity]
  (when-let [uri (:urpx/targetURPXVersion entity)]
    (if-let [[_ maj min-] (re-find #"/v(\d+)\.(\d+)" uri)]
      [(parse-long maj) (parse-long min-)]
      ::unparseable)))

(defn- conformance-findings
  "Rules the STANDARD states that this library's Malli schemas deliberately do
   not enforce, so a document can be schema-valid and still violate one.

   Reported rather than thrown, and reported here rather than in urpx.coverage,
   because reading a document and judging it are different jobs. Each names the
   rule it checks.

     :missing-coverage-claim   urpx:CoverageClaim is sh:minCount 1 on both
                               version shapes from v0.5.0, and its skos:note
                               says it is \"Required on every RatePlanVersion /
                               RatePlanModifierVersion\". The schema leaves it
                               optional because requiring it would reject every
                               v0.2.x document, and v0.2.x acceptance is a
                               contract this library advertises. So it is
                               checked against what the DOCUMENT says it targets
                               rather than made unconditional.
     :self-contained-disagrees urpx:selfContained may appear on the claim and on
                               the version, and \"when both are present they MUST
                               agree\". urpx.coverage reads such a document
                               without refusing, because the same note settles
                               which wins; the violated MUST is this function's
                               business.
     :unparseable-target-version  a document states a target version this cannot
                               read, so whether the claim rule applies is
                               unknown. Said out loud rather than skipped.

   Returns `[findings unchecked]`. A rule that could not be applied is listed
   separately rather than reported as a violation or passed over: a bare @graph
   bundle need not carry urpx:targetURPXVersion anywhere, and nothing requires
   it to, so the claim rule would be UNVERIFIABLE there rather than broken.
   Reporting such a document nonconformant would be wrong; saying nothing would
   make an unapplied rule look like a passed one, which is the pattern this
   repository keeps finding. The tests construct that branch.

   `entities` must be the DEEP walk of a document, not its top-level entities.
   urpx:targetURPXVersion may sit on the urpx:URPXPackage wrapper or on a node
   inside the @graph, so a top-level read finds it in some documents and not
   others. A shallow search once made declaring filings look undeclared, which
   is the same mistake in miniature that this function exists to catch."
  [entities]
  (let [target (some target-urpx-version entities)
        v051+  (and (vector? target) (>= (compare target [0 5]) 0))
        versions (filter #(version-types (:jsonld/type %)) entities)
        unchecked (when (and (nil? target) (seq versions))
                    [{:rule :missing-coverage-claim
                      :why "the document states no urpx:targetURPXVersion, so which
                            version's rules apply is unknown"
                      :versions (count versions)}])]
    [(vec
     (concat
      (when (= ::unparseable target)
        [{:rule :unparseable-target-version
          :detail (some :urpx/targetURPXVersion entities)}])
      (when v051+
        (for [e entities
              :when (and (version-types (:jsonld/type e))
                         (nil? (:urpx/hasCoverageClaim e)))]
          {:rule :missing-coverage-claim :type (:jsonld/type e) :id (:jsonld/id e)}))
      (for [e entities
            :when (version-types (:jsonld/type e))
            :let [r (try (coverage/of-version e) (catch clojure.lang.ExceptionInfo _ nil))]
            :when (:urpx.coverage/self-contained-disagrees? r)]
        {:rule :self-contained-disagrees :type (:jsonld/type e) :id (:jsonld/id e)})))
     (vec unchecked)]))

(defn check-file
  "Validate every entity in `path` that clj-urpx models. Returns
   {:path :checked [{:type :id :error}] :skipped [types] :mistyped [...]},
   where :mistyped is `urpx.index/mistyped-references` over the file's
   top-level entities, each with the :id of the one holding it.

   Entities of unmodeled types are reported as skipped rather than silently
   ignored, so a file that yields nothing recognizable is visible as such."
  [path]
  (let [top      (core/load-graph path)
        entities (mapcat #(cond->> (core/expand-document-entities %)
                            (core/wrapper-types (:jsonld/type %)) (cons %))
                         top)
        ;; Version nodes are nested inside their identity entity, and the
        ;; target version lives on the wrapper that expansion descends past, so
        ;; the conformance walk sees the whole graph rather than the expansion.
        all      (filter map? (tree-seq coll? seq top))
        typed    (filter :jsonld/type entities)
        checked  (for [e typed
                       :let [s (schema-for (:jsonld/type e))]
                       :when s]
                   {:type  (:jsonld/type e)
                    :id    (:jsonld/id e)
                    :error (coerce/explain s (coerce/coerce s e))})
        [findings unchecked] (conformance-findings all)
        mistyped (for [m (index/mistyped-references top)]
                   (-> m
                       (assoc :holder (:jsonld/id (nth top (:document m))))
                       (dissoc :document)))]
    {:path      path
     :mistyped  (vec mistyped)
     :checked   (vec checked)
     :findings  findings
     :unchecked unchecked
     :skipped   (vec (remove schema-for (map :jsonld/type typed)))}))

(defn- report [{:keys [path checked skipped findings unchecked mistyped]}]
  (let [bad (filter :error checked)]
    (println (format "%-58s %d checked, %d failed%s"
                     path (count checked) (count bad)
                     (if (seq skipped)
                       (str ", " (count skipped) " unmodeled (" (str/join ", " (distinct skipped)) ")")
                       "")))
    (doseq [{:keys [type id error]} bad]
      (println (format "  FAIL %s %s" type (or id "")))
      (doseq [e (take 5 (:errors error))]
        (println (format "       %s" (pr-str (:in e))))))
    ;; Separate from a schema failure because they are different claims: one
    ;; says the document does not match what this library models, the other
    ;; that it does and still breaks a rule the standard states.
    (doseq [{:keys [rule type id detail]} findings]
      (println (format "  NONCONFORMANT %s %s %s" (name rule) (or type "") (or id detail ""))))
    (doseq [{:keys [holder in id types range]} mistyped]
      (println (format "  MISTYPED %s %s: %s is %s, takes %s"
                       (or holder "") (pr-str in) (or id "inline node")
                       (str/join "/" types)
                       (if (set? range) (str/join " or " (sort range)) range))))
    ;; Not a failure, and not silence either. An unapplied rule looks exactly
    ;; like a passed one unless it is named.
    (doseq [{:keys [rule why versions]} unchecked]
      (println (format "  UNCHECKED %s over %d version(s): %s" (name rule) versions
                       (str/replace why #"\s+" " "))))
    (and (empty? bad) (empty? findings) (empty? mistyped))))

(defn- refused?
  "True when `result` names an unreadable path or checked nothing beyond a
   wrapper."
  [{:keys [refused checked]}]
  (boolean (or refused (every? #(core/wrapper-types (:type %)) checked))))

(defn -main [& paths]
  (when (empty? paths)
    (println "usage: clojure -M:validate <file.jsonld> [...]")
    (System/exit 2))
  (let [results (for [p paths]
                  (try
                    (check-file p)
                    (catch Exception e
                      {:path p :checked [] :skipped [] :findings [] :mistyped [] :refused (.getMessage e)})))
        results (vec results)
        refused (filter refused? results)]
    (doseq [r results]
      (if-let [why (:refused r)]
        (println (format "%-58s REFUSED: %s" (:path r) why))
        (report r)))
    (println)
    (cond
      (seq refused)
      (do (println (format "REFUSED %d of %d file(s): unreadable, or no entity this library models beyond a wrapper."
                           (count refused) (count results)))
          (println "Refusing to report success over nothing checked.")
          (System/exit 2))

      ;; Findings count toward the exit code as much as schema errors do. They
      ;; did not at first, and the finding was therefore decoration: a file
      ;; every schema accepted and the standard rejected exited 0.
      (some #(or (some :error (:checked %)) (seq (:findings %)) (seq (:mistyped %))) results)
      (let [failed (filter #(some :error (:checked %)) results)
            nonconf (filter #(seq (:findings %)) results)
            mistyped (filter #(seq (:mistyped %)) results)]
        (println (format "FAILED: %d of %d file(s) had validation errors, %d nonconformant, %d with mistyped references."
                         (count failed) (count results) (count nonconf) (count mistyped)))
        (System/exit 1))

      :else
      (do (println (format "OK: %d file(s), %d entities, all valid."
                           (count results)
                           (reduce + (map #(count (:checked %)) results))))
          (System/exit 0)))))

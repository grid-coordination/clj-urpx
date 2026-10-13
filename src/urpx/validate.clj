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

  A urpx:URPXPackage is checked at its own metadata and
  through to the urpx:RatePlan inside it."
  (:require [clojure.string :as str]
            [urpx.coerce :as coerce]
            [urpx.core :as core]
            [urpx.coverage :as coverage]
            [urpx.index :as index]
            [urpx.schema :as schema])
  (:gen-class))

(def ^:private schema-for
  "The schema for an entity's @type: every entity type urpx.schema registers."
  schema/schema-for-type)

(def ^:private version-types
  #{"urpx:RatePlanVersion" "urpx:RatePlanModifierVersion"})

(defn- conformance-findings
  "Rules the STANDARD states that this library's Malli schemas do not enforce,
   so a document can be schema-valid and still violate one. Reported rather
   than thrown, and reported here rather than in urpx.coverage, because reading
   a document and judging it are different jobs. Each names the rule it checks.

     :self-contained-disagrees urpx:selfContained may appear on the claim and on
                               the version, and \"when both are present they MUST
                               agree\". urpx.coverage reads such a document
                               without refusing, because the same note settles
                               which wins; the violated MUST is this function's
                               business.

   `entities` must be the DEEP walk of a document, so a version nested in its
   plan is judged as well as one at the top level."
  [entities]
  (vec (for [e entities
             :when (version-types (:jsonld/type e))
             :let [r (try (coverage/of-version e) (catch clojure.lang.ExceptionInfo _ nil))]
             :when (:urpx.coverage/self-contained-disagrees? r)]
         {:rule :self-contained-disagrees :type (:jsonld/type e) :id (:jsonld/id e)})))

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
        findings (conformance-findings all)
        mistyped (for [m (index/mistyped-references top)]
                   (-> m
                       (assoc :holder (:jsonld/id (nth top (:document m))))
                       (dissoc :document)))]
    {:path      path
     :mistyped  (vec mistyped)
     :checked   (vec checked)
     :findings  findings
     :skipped   (vec (remove schema-for (map :jsonld/type typed)))}))

(defn- report [{:keys [path checked skipped findings mistyped]}]
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

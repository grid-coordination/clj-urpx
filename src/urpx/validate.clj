(ns urpx.validate
  "Command-line validator for a single URPX JSON-LD file.

  Complements, rather than replaces, the corpus gate that lives in the
  urpx-rate-plans repo: that one walks a fixed tree, takes no path argument,
  and is named for a single URPX version. This checks any one file, which is
  what a per-wave conversion smoke test needs while a corpus migrates utility
  by utility and carries two vocabularies at once.

      clojure -M:validate path/to/filing.jsonld [more.jsonld ...]

  Exit codes are tri-state, so a run that checked nothing cannot read as
  success:

      0  every entity in every file coerced and validated
      1  at least one entity failed validation
      2  refused: a path was unreadable, or a file yielded no typed entity

  Wrappers are descended, so a urpx:URPXDocument or urpx:URPXPackage is
  checked through to the urpx:RatePlan inside it rather than at the wrapper
  metadata alone."
  (:require [clojure.string :as str]
            [urpx.coerce :as coerce]
            [urpx.core :as core]
            [urpx.schema :as schema])
  (:gen-class))

(def ^:private schema-for
  {"urpx:RatePlan"                 schema/RatePlan
   "urpx:RatePlanModifier"         schema/RatePlanModifier
   "urpx:RatePlanVersion"          schema/RatePlanVersion
   "urpx:RatePlanModifierVersion"  schema/RatePlanModifierVersion
   "urpx:URPXDocument"             schema/URPXDocument
   "urpx:URPXPackage"              schema/URPXPackage})

(defn check-file
  "Validate every entity in `path` that clj-urpx models. Returns
   {:path :checked [{:type :id :error}] :skipped [types]}.

   Entities of unmodeled types are reported as skipped rather than silently
   ignored, so a file that yields nothing recognizable is visible as such."
  [path]
  (let [entities (mapcat core/expand-document-entities (core/load-graph path))
        typed    (filter :jsonld/type entities)
        checked  (for [e typed
                       :let [s (schema-for (:jsonld/type e))]
                       :when s]
                   {:type  (:jsonld/type e)
                    :id    (:jsonld/id e)
                    :error (coerce/explain s (coerce/coerce s e))})]
    {:path    path
     :checked (vec checked)
     :skipped (vec (remove schema-for (map :jsonld/type typed)))}))

(defn- report [{:keys [path checked skipped]}]
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
    (empty? bad)))

(defn -main [& paths]
  (when (empty? paths)
    (println "usage: clojure -M:validate <file.jsonld> [...]")
    (System/exit 2))
  (let [results (for [p paths]
                  (try
                    (check-file p)
                    (catch Exception e
                      {:path p :checked [] :skipped [] :refused (.getMessage e)})))
        results (vec results)
        refused (filter #(or (:refused %) (empty? (:checked %))) results)]
    (doseq [r results]
      (if-let [why (:refused r)]
        (println (format "%-58s REFUSED: %s" (:path r) why))
        (report r)))
    (println)
    (cond
      (seq refused)
      (do (println (format "REFUSED %d of %d file(s): unreadable, or no entity this library models."
                           (count refused) (count results)))
          (println "Refusing to report success over nothing checked.")
          (System/exit 2))

      (some #(some :error (:checked %)) results)
      (do (println (format "FAILED: %d of %d file(s) had validation errors."
                           (count (filter #(some :error (:checked %)) results))
                           (count results)))
          (System/exit 1))

      :else
      (do (println (format "OK: %d file(s), %d entities, all valid."
                           (count results)
                           (reduce + (map #(count (:checked %)) results))))
          (System/exit 0)))))

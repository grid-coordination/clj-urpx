(ns drift
  "Report drift between the shipped URPX SHACL shapes and what clj-urpx's Malli
  schemas actually model.

  An AUDITOR, not a generator. It does not emit schemas and nothing it produces
  is compiled or shipped; it tells a human which hand-written entries have
  fallen behind the standard. That division is deliberate: the spike that
  preceded this found that a generator's central mapping rule can be silently
  vacuous, and that neither compilation nor a fixture suite catches it, whereas
  a diff that a person reads cannot fail that way.

      scripts/shacl-drift.sh <urpx-checkout> [rev]

  Exit codes are tri-state so a run that compared nothing cannot read as clean:

      0  no drift beyond what dev/drift-baseline.edn accepts
      1  new drift
      2  refused: no shapes, no schemas, or no overlap between them

  What it deliberately does NOT do:

    - enforce sh:closed. 79 of 144 shapes are closed upstream and clj-urpx
      closes one map on purpose; adopting closedness would reject every filing
      in the corpus. Closedness is reported as information and never fails.
    - translate RDF-encoding paths. sh:path rdf:first is the idiom for
      \"non-empty rdf:List\" and has no counterpart in a compact JSON-LD tree.
      Those are listed as untranslatable rather than silently compared.
    - guess at datatypes clj-urpx has no leaf for. xsd:gYear is the live
      example. They are listed as unmodeled rather than assumed equivalent."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [schema-facts :as facts]))

(def ^:private untranslatable-prefixes
  "Path prefixes that describe the RDF encoding rather than a property a
   JSON-LD document carries. Compared against nothing; reported as skipped."
  #{"rdf:" "rdfs:" "owl:"})

(def ^:private known-datatypes
  "xsd datatypes clj-urpx has a typed leaf for. Anything else is reported as
   unmodeled rather than assumed to need no decoder."
  #{"xsd:string" "xsd:decimal" "xsd:integer" "xsd:boolean" "xsd:anyURI"
    "xsd:date" "xsd:dateTime" "xsd:time" "xsd:duration" "xsd:gMonth"})

(defn- shacl-required? [p] (and (:minCount p) (pos? (:minCount p))))
(defn- shacl-multi?    [p] (nil? (:maxCount p)))

(defn- compare-shape
  "Findings for one Malli schema against the SHACL shape for its @type."
  [schema-name {:keys [entries]} shape]
  (let [by-prop (into {} (map (juxt :property identity)) entries)
        props   (:properties shape)
        finding (fn [kind prop detail]
                  {:schema schema-name :property prop :kind kind :detail detail})]
    (concat
     ;; Untranslatable paths: named, never compared.
     (for [p props
           :when (some #(str/starts-with? (:path p) %) untranslatable-prefixes)]
       (finding :untranslatable (:path p)
                "RDF-encoding path; no counterpart in a compact JSON-LD tree"))
     (for [p props
           :let [prop (:path p)
                 mine (by-prop prop)]
           :when (not (some #(str/starts-with? prop %) untranslatable-prefixes))
           f [;; The expensive direction: we reject documents the standard allows.
              (when (and mine (:required mine) (not (shacl-required? p)))
                (finding :rejects-valid prop
                         "required here, sh:minCount 0 upstream"))
              (when (and mine (shacl-multi? p) (not (:vector mine)))
                (finding :arity prop
                         "0..N upstream (no sh:maxCount), modeled as a single value"))
              ;; We accept documents the standard rejects.
              (when (and mine (not (:required mine)) (shacl-required? p))
                (finding :under-validates prop
                         "sh:minCount 1 upstream, optional here"))
              (when (and (nil? mine) (shacl-required? p))
                (finding :missing-required prop
                         "sh:minCount 1 upstream, not modeled at all"))
              (when (nil? mine)
                (finding :missing prop "not modeled"))
              ;; Enum membership.
              (when (and mine (:in p) (:enum mine)
                         (not= (set (:in p)) (set (:enum mine))))
                (finding :enum-drift prop
                         (str "upstream " (count (:in p)) ", here " (count (:enum mine))
                              "; only-upstream "
                              (vec (sort (remove (set (:enum mine)) (:in p))))
                              " only-here "
                              (vec (sort (remove (set (:in p)) (:enum mine)))))))
              (when (and mine (:in p) (nil? (:enum mine)))
                (finding :enum-unenforced prop
                         (str "sh:in with " (count (:in p)) " members upstream, unenforced here")))
              (when (and (:datatype p) (not (known-datatypes (:datatype p))))
                (finding :unmodeled-datatype prop
                         (str (:datatype p) " has no typed leaf in urpx.schema")))]
           :when f]
       f)
     (when (and (:closed shape) (not (:closed (by-prop :closed))))
       [(finding :closed-upstream nil "sh:closed true upstream; not enforced here, by decision")]))))

(defn analyze [shapes-json malli-facts]
  (let [shapes  (:shapes shapes-json)
        schemas (:schemas malli-facts)
        pairs   (for [[nm s] schemas
                      t      (:types s)
                      :let   [shape (get shapes (keyword t))]
                      :when  shape]
                  [nm s shape t])]
    {:rev       (:rev shapes-json)
     :shapes    (count shapes)
     :schemas   (count schemas)
     :compared  (count pairs)
     :unmatched (vec (sort (for [[nm s] schemas
                                 :when (not-any? #(get shapes (keyword %)) (:types s))]
                             nm)))
     :findings  (vec (mapcat (fn [[nm s shape _]] (compare-shape nm s shape)) pairs))}))

(def ^:private severity
  [:rejects-valid :arity :missing-required :enum-drift :under-validates
   :enum-unenforced :unmodeled-datatype :missing :untranslatable :closed-upstream])

(def ^:private fails?
  "Kinds that make a run fail. Informational kinds never do."
  #{:rejects-valid :arity :missing-required :enum-drift :under-validates})

(defn- finding-key [f] [(:schema f) (:property f) (name (:kind f))])

(defn -main [& [shapes-path facts-path baseline-path]]
  (when-not (and shapes-path (.exists (io/file shapes-path)))
    (println "REFUSED: no shapes file. Run scripts/shacl-drift.sh, not this directly.")
    (System/exit 2))
  (let [shapes   (json/read-str (slurp shapes-path) :key-fn keyword)
        mf       (if (and facts-path (.exists (io/file facts-path)))
                   (edn/read-string (slurp facts-path))
                   {:schemas (facts/schema-facts)})
        baseline (if (and baseline-path (.exists (io/file baseline-path)))
                   (set (map vec (:accepted (edn/read-string (slurp baseline-path)))))
                   #{})
        {:keys [rev shapes schemas compared unmatched findings]} (analyze shapes mf)]
    (when (zero? compared)
      (println "REFUSED: no Malli schema matched any sh:targetClass.")
      (println "Refusing to report zero drift over nothing compared.")
      (System/exit 2))
    (println (format "URPX SHACL drift, shapes at %s" rev))
    (println (format "%d shapes upstream, %d entity schemas here, %d compared, %d unmatched"
                     shapes schemas compared (count unmatched)))
    (println)
    (let [new-findings (remove #(baseline (finding-key %)) findings)
          by-kind      (group-by :kind new-findings)]
      (doseq [k severity
              :let [fs (by-kind k)]
              :when (seq fs)]
        (println (format "%s  [%s] %d"
                         (if (fails? k) "FAIL" "note") (name k) (count fs)))
        (doseq [f (take 12 (sort-by (juxt :schema :property) fs))]
          (println (format "        %-28s %-34s %s"
                           (:schema f) (or (:property f) "") (:detail f))))
        (when (> (count fs) 12)
          (println (format "        ... and %d more" (- (count fs) 12))))
        (println))
      (let [failing (filter #(fails? (:kind %)) new-findings)
            accepted (- (count findings) (count new-findings))]
        (println (format "%d finding(s) accepted by the baseline." accepted))
        (if (seq failing)
          (do (println (format "NEW DRIFT: %d finding(s) need attention." (count failing)))
              (System/exit 1))
          (do (println "No new drift.")
              (System/exit 0)))))))

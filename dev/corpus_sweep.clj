;; SPDX-FileCopyrightText: 2026 Clark Communications Corporation
;; SPDX-License-Identifier: MIT
(ns corpus-sweep
  "Enumerate scenario cost models over a corpus of URPX filings.

  Development only, and deliberately NOT part of the library:
  `urpx.scenario-cost-model` takes documents that are already loaded and has no
  filesystem dependency, so walking a tree is the caller's job. This is what
  that job looks like.

      clojure -M:dev:corpus -m corpus-sweep <locator> [rev]

  NO CORPUS IS NAMED HERE. `locator` is either a filesystem path or a git URL,
  and there is no default: this library reads a standard, so privileging one
  corpus in its own source would make every other consumer's corpus the special
  case. A git URL is resolved and procured by `clojure.tools.gitlibs`, which
  caches under ~/.gitlibs and writes nothing into this repo: a corpus is
  something this library is pointed at, never something it vendors or tracks.

      clojure -M:dev:corpus -m corpus-sweep ../some-corpus
      clojure -M:dev:corpus -m corpus-sweep git@github.com:org/some-corpus.git main

  A GIT URL IS CITABLE AND A WORKING TREE IS NOT, but that does not make the
  pinned number the right one: a clone may be legitimately newer than its
  remote, and a filing committed locally and not yet pushed is just as real as
  one that was. So a path locator prints the clone's SHA, how far ahead of
  upstream it is, and how many files are dirty. A number measured on such a
  tree is still worth having; it just cannot be cited by SHA alone, and the
  banner says so rather than leaving a reader to assume the two agree."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.tools.gitlibs :as gitlibs]
            [urpx.scenario-cost-model :as scm]
            [urpx.coverage :as coverage]
            [urpx.load :as load])
  (:import [java.time ZoneId ZonedDateTime]))

(defn git-url?
  "True if `locator` names a git remote rather than a directory. Covers the
   scp-style form (git@host:org/repo.git), which has no scheme and so is not a
   URI, alongside the schemed forms."
  [locator]
  (boolean (or (re-find #"^[a-z+]+://" locator)
               (re-find #"^[^/]+@[^/:]+:" locator))))

(defn gitlibs-coords
  "Derive the `lib` symbol gitlibs caches under from `url`. gitlibs only uses
   it as a cache path, so any stable derivation will do; taking it from the URL
   keeps two corpora from colliding without this file knowing either of them."
  [url]
  (let [parts (-> url (str/replace #"\.git$" "") (str/split #"[:/]"))
        [org repo] (take-last 2 parts)]
    (symbol org repo)))

(defn- git
  "Run `args` in `dir`, returning trimmed stdout or nil if git said no. Used
   only to describe a clone, never to change one."
  [dir & args]
  (let [{:keys [exit out]} (apply shell/sh (concat args [:dir dir]))]
    (when (zero? exit) (str/trim out))))

(defn- local-provenance
  "How citable a working tree is: its SHA, how many commits it holds that its
   upstream does not, and how many files are dirty. Not a judgement that the
   tree is wrong; only a record of which thing a number came from."
  [dir]
  {:source :working-tree
   :sha    (git dir "git" "rev-parse" "HEAD")
   :ahead  (some-> (git dir "git" "rev-list" "--count" "@{upstream}..HEAD") parse-long)
   :dirty  (count (remove str/blank?
                          (some-> (git dir "git" "status" "--porcelain")
                                  str/split-lines)))})

(defn corpus-root
  "Return `[dir provenance]` for `locator`, a filesystem path or a git URL.
   A git URL is resolved at `rev` and procured into the gitlibs cache, which is
   immutable and identified by its SHA alone."
  [locator rev]
  (if (git-url? locator)
    (let [sha (gitlibs/resolve locator rev)]
      [(io/file (gitlibs/procure locator (gitlibs-coords locator) sha))
       {:source :gitlibs :sha sha :ahead 0 :dirty 0}])
    [(io/file locator) (local-provenance locator)]))

(defn provenance-line
  [root {:keys [source sha ahead dirty]}]
  (str root
       (when sha (str " @ " (subs sha 0 (min 12 (count sha)))))
       (case source
         :gitlibs " (immutable checkout, citable by SHA)"
         :working-tree
         (cond
           (nil? sha) " (not a git clone: nothing identifies this tree)"
           (and (zero? (or ahead 0)) (zero? dirty)) " (clean and level with upstream)"
           :else (str " NOT CITABLE BY SHA ALONE:"
                      (when (pos? (or ahead 0)) (str " " ahead " commit(s) ahead of upstream;"))
                      (when (pos? dirty) (str " " dirty " uncommitted change(s);"))
                      " the remote does not have what was measured")))))

(defn own-layout
  "The corpus.json at `root`, or nil."
  [root]
  (let [f (io/file root "corpus.json")]
    (when (.isFile f) f)))

(defn load-corpus
  "Every coerced RatePlan and RatePlanModifier under `root`, and whatever was
   discarded getting there.

   The pipeline itself is `urpx.load/scan`; this only supplies the sources,
   which is the whole division of labour. A corpus layout is a one-off and
   there will never be one everyone adopts, so walking a tree is this script's
   job and turning what it finds into documents is the library's.

   `layout` is a corpus.json, a directory holding one, or a walker namespace
   providing `sources`. With none, it is the root's own corpus.json, and with
   neither, every **/*.jsonld."
  ([root] (load-corpus root nil))
  ([root layout]
   (let [layout (or layout (own-layout root))]
     (load/scan
      ;; A layout knows what a glob cannot: which subtrees hold filings and
      ;; which hold something else. See corpus/README.md.
      (cond
        (nil? layout)
        (map fs/file (fs/glob root "**/*.jsonld"))

        (fs/exists? layout)
        ((requiring-resolve 'corpus-json/sources)
         root ((requiring-resolve 'corpus-json/read-layout) layout))

        :else
        (let [ns-sym (symbol layout)]
          (require ns-sym)
          ((resolve (symbol layout "sources")) root)))))))

(defn coverage-census
  "What the corpus claims about its own coverage, counted.

   Printed before the enumeration because it is about DOCUMENTS and holds even
   when the enumeration refuses. A verdict is what a publisher asserts about a
   snapshot, so this is the corpus describing itself rather than this library
   judging it.

   The two figures that matter to a consumer: what the verdicts are, and how
   many claims say what window they cover. The second has been zero everywhere
   since the machinery existed, which is why it is printed rather than assumed."
  [docs]
  (let [versions (distinct (for [d docs
                                 v (tree-seq coll? seq d)
                                 :when (and (map? v)
                                            (#{"urpx:RatePlanVersion" "urpx:RatePlanModifierVersion"}
                                             (:jsonld/type v)))]
                             [v d]))
        reports  (map (fn [[v d]] (assoc (coverage/of-version v d)
                                         :urpx.coverage/kind (:jsonld/type v)))
                      versions)]
    (println "coverage        :" (count reports) "version nodes")
    (doseq [[kind rs] (sort-by key (group-by :urpx.coverage/kind reports))]
      (println (format "  %-28s %s" kind
                       (into (sorted-map)
                             (frequencies (map #(or (:urpx.coverage/completeness %) :unstated) rs))))))
    (println "  with a coverage window     :"
             (count (filter :urpx.coverage/scope-stated? reports)) "of" (count reports))
    (println "  with an Evaluation         :"
             (count (filter #(seq (:urpx.coverage/evaluations %)) reports)))
    (println "  selfContained disagreements:"
             (count (filter :urpx.coverage/self-contained-disagrees? reports)))))

(defn -main
  [& [locator rev & more]]
  (when-not locator
    (binding [*out* *err*]
      (println "REFUSED: no corpus given. Pass a path or a git URL:")
      (println "  clojure -M:dev:corpus -m corpus-sweep ../some-corpus")
      (println "  clojure -M:dev:corpus -m corpus-sweep git@github.com:org/some-corpus.git [rev]"))
    (System/exit 2))
  (let [layout      (second (drop-while #(not= "--layout" %) (cons rev more)))
        rev         (when (and rev (not= "--layout" rev)) rev)
        [root prov] (corpus-root locator (or rev "main"))
        layout      (or layout (own-layout root))
        scanned     (load-corpus root layout)
        _           (println "layout          :" (or (some-> layout str) "(naive glob of **/*.jsonld)"))
        docs        (:urpx.load/documents scanned)
        dropped     (:urpx.load/uncoercible scanned)
        _           (println "corpus          :" (provenance-line root prov))
        _           (println "coerced docs    :" (count docs)
                             (frequencies (map :jsonld/type docs)))
        ;; Printed rather than left implicit. "This library does not model it"
        ;; and "it does not matter" are different statements, and a version type
        ;; reaching this line is a real problem rather than noise.
        _           (println "discarded       :" (count dropped)
                             (frequencies (map :jsonld/type dropped)))
        _           (when-let [vs (seq (filter #(str/ends-with? (str (:jsonld/type %)) "Version")
                                               dropped))]
                      (println "  !! VERSION TYPES DISCARDED:"
                               (frequencies (map :jsonld/type vs))))
        _           (println "unresolved refs :"
                             (count (:urpx.load/unresolved-references scanned)))
        _           (coverage-census docs)
        now         (ZonedDateTime/of 2026 7 15 17 0 0 0 (ZoneId/of "America/Los_Angeles"))
        ;; A REFUSAL IS A RESULT, not a crash. This corpus can legitimately
        ;; carry an ambiguity no reader can resolve (one @id naming two
        ;; different snapshots, say), and the useful output then is the
        ;; refusal's own reason and the identifiers it names, not a stack
        ;; trace above a blank report.
        refusal     (fn [f] (try (f) (catch clojure.lang.ExceptionInfo e e)))
        all         (refusal #(scm/enumerate docs))
        _           (when (instance? clojure.lang.ExceptionInfo all)
                      (println)
                      (println "REFUSED         :" (:reason (ex-data all)))
                      (println "  " (ex-message all))
                      (doseq [[k v] (dissoc (ex-data all) :reason)]
                        (println "  " k v))
                      (System/exit 3))
        cur         (refusal #(scm/enumerate docs {:at now}))
        ;; The instant-restricted call can refuse where the all-versions call
        ;; did not: a tie on the latest effective instant is only reachable once
        ;; currency has narrowed the candidates. The upstream test cases carry
        ;; exactly that.
        _           (when (instance? clojure.lang.ExceptionInfo cur)
                      (println)
                      (println "REFUSED at" (str now) ":" (:reason (ex-data cur)))
                      (println "  " (ex-message cur))
                      (System/exit 3))
        pairs       (distinct (map (juxt :urpx.scenario-cost-model/version-id
                                         :urpx.scenario-cost-model/modifier-version-ids)
                                   cur))]
    (println "models          :" (count all) "over all versions,"
             (count cur) "current at" (str now))
    (println "  base versions :" (count (distinct (map :urpx.scenario-cost-model/version-id cur))))
    (println "  (base, mods)  :" (count pairs) "distinct pairs")
    ;; Over BOTH sets. This printed the check for the :at set only, directly
    ;; beneath the all-versions figure for which it was false at the time: a
    ;; check run where it cannot fail, next to the number it was false for.
    (doseq [[label as] [["all versions" all] ["at instant " cur]]]
      (println (str "  ids unique (" label ") : ")
               (= (count as) (count (distinct (map scm/identity-tuple as))))))
    (println "  with a choice :" (count (filter #(seq (:urpx.scenario-cost-model/selections %)) cur)))))

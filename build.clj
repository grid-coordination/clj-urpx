(ns build
  (:refer-clojure :exclude [test])
  (:require [build-provenance.write :as bp]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.tools.build.api :as b]
            [codox.md.build :as doc]
            [deps-deploy.deps-deploy :as dd])
  (:import [java.util.zip ZipFile]))

(def lib 'energy.grid-coordination/clj-urpx)
(def version "0.25.0")
(def class-dir "target/classes")

(defn test "Run all the tests." [opts]
  (let [basis    (b/create-basis {:aliases [:test]})
        cmds     (b/java-command
                  {:basis      basis
                   :main      'clojure.main
                   :main-args ["-m" "kaocha.runner"]})
        {:keys [exit]} (b/process cmds)]
    (when-not (zero? exit) (throw (ex-info "Tests failed" {}))))
  opts)

(defn- jar-opts [opts]
  (assoc opts
         :lib lib :version version
         :jar-file (format "target/%s-%s.jar" (name lib) version)
         :description "Clojure library for URPX utility rate plan exchange"
         :url "https://github.com/grid-coordination/clj-urpx"
         :pom-data [[:licenses
                     [:license
                      [:name "MIT"]
                      [:url "https://opensource.org/licenses/MIT"]]]]
         :scm {:tag (str "v" version)
               :url "https://github.com/grid-coordination/clj-urpx"}
         :basis (b/create-basis {})
         :class-dir class-dir
         :target "target"
         :src-dirs ["src"]))

(defn ci "Run the CI pipeline of tests (and build the JAR)." [opts]
  (test opts)
  (b/delete {:path "target"})
  (let [opts (jar-opts opts)]
    (println "\nWriting pom.xml...")
    (b/write-pom opts)
    (println "\nGenerating API docs...")
    (doc/generate-docs {:lib lib :version version
                        :description (:description opts)
                        :source-uri (str (get-in opts [:scm :url])
                                         "/blob/{git-commit}/{filepath}#L{line}")})
    (println "\nCopying source...")
    (b/copy-dir {:src-dirs ["resources" "src" "target/doc-resources"]
                 :target-dir class-dir})
    (println "\nWriting build provenance...")
    (bp/write! {:lib lib :version version :class-dir class-dir})
    (println "\nBuilding JAR...")
    (b/jar opts))
  opts)

;; ---------------------------------------------------------------------------
;; Release preconditions
;;
;; Every check here exists because the thing it checks for actually happened.
;; `deploy` publishes whatever is sitting in target/, so the task is trusting
;; that a human ran `ci` first, on this commit, from a clean tree. Trusting that
;; shipped a pom from an eight-version-old build once already. These turn each
;; assumption into a refusal, and the artifact itself is the witness: the
;; build-provenance we embed records the commit the jar was built from, so the
;; jar can be checked against HEAD rather than taken on faith.
;; ---------------------------------------------------------------------------

(defn- git [& args]
  (let [{:keys [exit out err]} (apply shell/sh "git" args)]
    (when-not (zero? exit)
      ;; git writes every failure reason to stderr. Dropping it left the
      ;; recovery path these checks prescribe failing with no explanation.
      (throw (ex-info (str "git " (str/join " " args) " failed: " (str/trim (or err "")))
                      {:args args :exit exit :err (str/trim (or err ""))})))
    (str/trim out)))

(defn- refuse [msg data]
  (throw (ex-info (str "REFUSING TO DEPLOY: " msg) data)))

(defn- jar-provenance
  "The build-provenance the JAR carries, or nil when it carries none."
  [jar-file]
  (let [entry (format "META-INF/%s/%s/build-provenance.edn" (namespace lib) (name lib))
        f     (io/file jar-file)]
    (when (.isFile f)
      ;; A truncated or empty jar, or unreadable edn, means the same thing as no
      ;; provenance: this artifact cannot vouch for itself. Returning nil lets
      ;; check-deployable! say so, where an escaping ZipException would preempt
      ;; the snapshot, dirty-tree and tag refusals that name the real fix.
      (try
        (with-open [z (ZipFile. f)]
          (when-let [e (.getEntry z entry)]
            (with-open [in (.getInputStream z e)]
              (edn/read-string (slurp in)))))
        (catch Exception _ nil)))))

(defn- check-deployable!
  "Refuse unless this jar was built by `ci`, from THIS commit, on a clean tree,
   at a version that is tagged and released."
  [{:keys [jar-file]}]
  (let [head  (git "rev-parse" "HEAD")
        dirty (seq (git "status" "--porcelain"))
        prov  (jar-provenance jar-file)]
    (when (str/ends-with? version "-SNAPSHOT")
      (refuse (str "version is " version ". A snapshot is a work in progress, not a release.")
              {:version version}))
    (when dirty
      (refuse "the working tree has uncommitted changes, so the artifact would not correspond to any commit."
              {:dirty (git "status" "--porcelain")}))
    ;; `ci` copies src/ and resources/ wholesale, so a file git IGNORES is packed
    ;; into the jar while every dirty check stays quiet: `git status --porcelain`
    ;; does not list ignored files, and build-provenance reads dirtiness from
    ;; `git diff-index`, which does not either.
    (let [;; The same patterns b/copy-dir already excludes from the jar
          ;; (clojure.tools.build.tasks.copy/default-ignores). Refusing over a
          ;; .DS_Store the artifact provably never packs is a guard that fires
          ;; on legitimate releases, which is worse than no guard.
          packed?   (fn [line]
                      (let [base (-> line (str/split #"\s+" 2) second (or "") (str/split #"/") last)]
                        (not (some #(re-find % base)
                                   [#"~$" #"^#.*#$" #"^\.#" #"^\.DS_Store$"]))))
          ;; -uall so git names FILES. Without it a directory holding only
          ;; ignored files collapses to the directory ("!! resources/"), which
          ;; has no basename to test and reads as a stowaway.
          stowaways (->> (str/split-lines
                          (git "status" "--porcelain" "--ignored" "-uall" "src" "resources"))
                         (remove str/blank?)
                         (filter #(str/starts-with? % "!!"))
                         (filter packed?))]
      (when (seq stowaways)
        (refuse (str "src/ or resources/ holds " (count stowaways)
                     " file(s) git is ignoring, and the jar packs those directories whole: "
                     (pr-str (vec stowaways))
                     ". Remove them, or commit them, so the artifact matches the commit.")
                {:stowaways (vec stowaways)})))
    (when (str/blank? (git "tag" "-l" (str "v" version)))
      (refuse (str "there is no tag v" version ". Run `clojure -T:build tag`, or tag the commit "
                   "you are shipping, so the artifact can be traced back to a commit.")
              {:version version}))
    (when-not (= head (git "rev-parse" (str "v" version "^{commit}")))
      (refuse (str "tag v" version " does not point at HEAD. Tag the commit you are shipping.")
              {:head head :version version}))
    (when-not prov
      (refuse (str "no build-provenance in " jar-file
                   ". Run `clojure -T:build ci` first; `deploy` publishes what is already in target/ "
                   "and does not build it.")
              {:jar-file jar-file}))
    (when-not (= version (:version prov))
      (refuse (str "the jar was built at version " (pr-str (:version prov))
                   " but this deploy is for " version ". target/ is stale; run `ci`.")
              {:jar (:version prov) :deploying version}))
    (when-not (= head (:commit prov))
      (refuse (str "the jar was built from commit " (:commit-abbreviated prov)
                   " but HEAD is " (subs head 0 7) ". target/ is stale; run `ci`.")
              {:jar-commit (:commit prov) :head head}))
    (when (:dirty prov)
      (refuse "the jar was built from a dirty tree, so its provenance names a commit it does not match."
              {:provenance prov}))
    (when (str/blank? (System/getenv "CLOJARS_PASSWORD"))
      (refuse "CLOJARS_PASSWORD is unset. Pass the group-scoped token rather than discovering this as a 401."
              {}))
    (println (str "\nPreconditions OK: " version " @ " (:commit-abbreviated prov)
                  ", tagged, clean, built by ci."))))

(defn install "Install the JAR locally." [opts]
  (let [opts (jar-opts opts)]
    (b/install opts))
  opts)

(defn deploy
  "Deploy the JAR to Clojars, after checking it is the one this commit built.

   Pass :force true to skip the checks. There is no good reason to, and the
   refusal message names the fix in every case."
  [opts]
  (let [{:keys [jar-file] :as opts} (jar-opts opts)]
    (when-not (:force opts)
      (check-deployable! opts))
    (dd/deploy {:installer :remote :artifact (b/resolve-path jar-file)
                :pom-file (b/pom-path (select-keys opts [:lib :class-dir]))}))
  opts)

(defn tag
  "Tag HEAD as v<version>, refusing on a snapshot or a dirty tree."
  [opts]
  (when (str/ends-with? version "-SNAPSHOT")
    (throw (ex-info (str "REFUSING TO TAG: version is " version) {:version version})))
  (when (seq (git "status" "--porcelain"))
    (throw (ex-info "REFUSING TO TAG: the working tree has uncommitted changes" {})))
  (git "tag" "-a" (str "v" version) "-m" (str "Release " version))
  (println (str "\nTagged v" version " at " (git "rev-parse" "--short" "HEAD")))
  opts)

(defn release
  "The whole release, in the one order that is correct: build and test from a
   clean tagged tree, then verify the artifact, then publish.

   `ci` before `deploy` is the step that gets skipped, which is why this exists
   as one task rather than as a note in a README."
  [opts]
  (-> opts ci deploy))

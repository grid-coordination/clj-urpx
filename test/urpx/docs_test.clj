(ns urpx.docs-test
  "Execute the documentation, so its claims can fail like any other.

  Prose about code rots silently: a missing DSL builder is not a failing test,
  and a tutorial that throws is not a failing test either. Both happened here.
  The tutorial's rate-plan-version example broke the moment two properties
  became required, and nothing noticed until someone re-read it.

  Two tiers, because the two documents fail differently:

    doc/dsl-tutorial.md  every Clojure block is EVALUATED, in order, in one
                         namespace, exactly as a reader working through the page
                         would. Its blocks are complete programs.

    README.md            most blocks are illustrative snippets that reference
                         bindings they never define (`plan`, `zdt`), so running
                         them is not meaningful. What IS meaningful, and what
                         actually goes wrong, is a symbol that was renamed or
                         removed. Every urpx.*/name and dsl/name a block
                         mentions must still resolve.

    README Getting started
                         the exception: that section advertises itself as
                         running from a cold clone, and it is self-contained,
                         so it is EVALUATED like the tutorial. It broke once,
                         silently, when marginal-unit-rate began refusing to
                         pick among mutually-exclusive alternatives: the
                         walkthrough composes PG&E's vintage-PCIA modifier, so
                         the first thing a new reader ran would have thrown.
                         A symbol check cannot catch that; only running it can."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- fenced-blocks
  "Return the bodies of every ```<lang> block in `md`."
  [md lang]
  (->> (re-seq (re-pattern (str "(?s)```" lang "\\n(.*?)```")) md)
       (map second)))

(defn- doc [path]
  (let [f (io/file path)]
    (when (.exists f) (slurp f))))

(deftest dsl-tutorial-blocks-actually-run
  (testing "every Clojure block in the DSL tutorial evaluates, in order, in one
            namespace, the way a reader working through the page would hit them.
            This test exists because the rate-plan-version example silently
            became a throw when changeReason and serviceRole were made required."
    (let [md (doc "doc/dsl-tutorial.md")]
      (is (some? md) "doc/dsl-tutorial.md must exist")
      (let [blocks (fenced-blocks md "clojure")
            ns-sym (gensym "urpx-tutorial-")
            tmp    (create-ns ns-sym)]
        (is (<= 5 (count blocks))
            "sanity: the tutorial should carry several runnable blocks")
        (try
          (binding [*ns* tmp]
            (refer-clojure)
            (doseq [[i block] (map-indexed vector blocks)]
              (testing (str "block " i ": " (first (str/split-lines (str/trim block))))
                (is (nil?
                     (try
                       (load-string (str "(in-ns '" ns-sym ")"
                                         "(clojure.core/refer-clojure)"
                                         block))
                       nil
                       (catch Throwable t
                         (str (.getSimpleName (class t)) ": " (.getMessage t)))))))))
          (finally (remove-ns ns-sym)))))))

(def ^:private alias->ns
  "The alias each doc uses for a namespace of this project.

   THE ONLY SOURCE. `referenced-symbols` derives what it recognises from these
   keys rather than carrying a second copy; the two were separate lists once and
   drifted the moment a namespace was added, which is the whole failure below."
  {"core" 'urpx.core "schema" 'urpx.schema "coerce" 'urpx.coerce
   "index" 'urpx.index "price" 'urpx.price "schedule" 'urpx.schedule
   "holidays" 'urpx.holidays "tz" 'urpx.tz-inference "emit" 'urpx.emit
   "dsl" 'urpx.dsl "validate" 'urpx.validate
   "load" 'urpx.load "scm" 'urpx.scenario-cost-model "cov" 'urpx.coverage})

(defn- referenced-symbols
  "Qualified symbols a doc's Clojure blocks mention, as [alias name] pairs.
   Only aliases this project owns; clojure.core and java interop are ignored."
  [md]
  (let [owned (set (keys alias->ns))]
    (->> (fenced-blocks md "clojure")
         (mapcat #(re-seq #"\(([a-z-]+)/([a-zA-Z][a-zA-Z0-9*?<>=+-]*)" %))
         (keep (fn [[_ alias nm]] (when (owned alias) [alias nm])))
         distinct
         sort)))

(deftest every-namespace-is-registered-for-the-doc-checks
  (testing "the symbol checks below recognise only the aliases in alias->ns and
            drop the rest SILENTLY, so a namespace nobody registered is a
            namespace whose documented function names are never verified. That
            is not hypothetical: the enumerator shipped in 0.29.0 with a README
            section full of calls that this suite never looked at,
            because `owned` was a second hand-maintained copy of these keys.

            So adding a namespace to src/ now fails here until it is registered.
            An unregistered namespace is the only way the checks can go quiet."
    (let [on-disk (->> (file-seq (io/file "src/urpx"))
                       (filter #(str/ends-with? (.getName ^java.io.File %) ".clj"))
                       (map #(symbol (str "urpx." (str/replace
                                                   (str/replace (.getName ^java.io.File %) #"\.clj$" "")
                                                   "_" "-"))))
                       set)]
      (is (seq on-disk) "the source tree was found")
      (is (= #{} (set/difference on-disk (set (vals alias->ns))))
          "every urpx.* namespace must have an alias registered in alias->ns"))))

(deftest readme-references-only-symbols-that-exist
  (testing "README blocks are illustrative snippets, so running them is not
            meaningful; they reference bindings they never define. What is
            meaningful is that every function they name still exists. A rename
            that leaves the prose behind is the realistic failure here."
    (let [md (doc "README.md")]
      (is (some? md))
      (let [refs (referenced-symbols md)
            _    (doseq [[a _] refs] (require (alias->ns a)))
            bad  (remove (fn [[a nm]] (ns-resolve (alias->ns a) (symbol nm))) refs)]
        ;; A floor, not the exact count: it guards against the extractor
        ;; silently matching nothing (which would make this test vacuous)
        ;; without breaking every time a code block is edited.
        (is (<= 10 (count refs))
            (str "sanity: extractor found only " (count refs)
                 " project functions in README blocks; it may have stopped matching"))
        (is (empty? bad)
            (str "README names functions that do not resolve: "
                 (pr-str (mapv (fn [[a nm]] (str a "/" nm)) bad))))))))

(deftest tutorial-references-only-symbols-that-exist
  (testing "the same check for the tutorial, which catches a rename in a block
            that happens not to be reached by evaluation order"
    (let [md   (doc "doc/dsl-tutorial.md")
          refs (referenced-symbols md)
          _    (doseq [[a _] refs] (require (alias->ns a)))
          bad  (remove (fn [[a nm]] (ns-resolve (alias->ns a) (symbol nm))) refs)]
      (is (seq refs))
      (is (empty? bad)
          (str "tutorial names functions that do not resolve: "
               (pr-str (mapv (fn [[a nm]] (str a "/" nm)) bad)))))))

(defn- section
  "The body of the `## <title>` section of `md`, up to the next `## ` heading."
  [md title]
  (when-let [start (str/index-of md (str "## " title))]
    (let [rest- (subs md (+ start (count title) 3))
          end   (str/index-of rest- "\n## ")]
      (if end (subs rest- 0 end) rest-))))

(deftest readme-getting-started-actually-runs
  (testing "the Getting started walkthrough is self-contained and advertises
            itself as running from a cold clone, so it is evaluated rather than
            merely symbol-checked. It silently broke once: marginal-unit-rate
            began refusing to pick among mutually-exclusive price alternatives,
            and because the walkthrough composes PG&E's vintage-PCIA modifier,
            the first thing a new reader would run threw an exception. Nothing
            noticed, because README blocks were only checked for symbol
            resolution."
    (let [md (doc "README.md")]
      (is (some? md) "README.md must exist")
      (let [body   (section md "Getting started")
            blocks (fenced-blocks (or body "") "clojure")
            ns-sym (gensym "urpx-readme-")
            tmp    (create-ns ns-sym)]
        (is (some? body) "the Getting started section must exist")
        (is (<= 1 (count blocks)) "it must carry at least one Clojure block")
        (try
          (binding [*ns* tmp]
            (refer-clojure)
            (doseq [[i block] (map-indexed vector blocks)]
              (testing (str "block " i ": " (first (str/split-lines (str/trim block))))
                (is (nil?
                     (try
                       (load-string (str "(in-ns '" ns-sym ")"
                                         "(clojure.core/refer-clojure)"
                                         block))
                       nil
                       (catch Throwable t
                         (str (.getSimpleName (class t)) ": " (.getMessage t)))))))))
          (finally (remove-ns ns-sym)))))))

(deftest readme-getting-started-launch-command-has-the-classpath-it-needs
  (testing "the suite evaluates Getting started under :test, so it cannot see
            the classpath of the command the README tells a reader to run.
            That command's alias must carry every path the walkthrough loads
            from: the urpx.test-fixtures namespace and the vendored fixtures."
    (let [body    (section (doc "README.md") "Getting started")
          [_ cmd] (some->> (fenced-blocks (or body "") "bash")
                           (some #(re-find #"(?m)^clojure (-M\S+)" %)))
          aliases (when cmd
                    (map keyword (remove str/blank? (str/split (subs cmd 2) #":"))))
          deps    (edn/read-string (slurp "deps.edn"))
          paths   (into (set (:paths deps))
                        (mapcat #(get-in deps [:aliases % :extra-paths]))
                        aliases)
          on-cp?  (fn [rel] (some #(.exists (io/file % rel)) paths))]
      (is (some? cmd) "Getting started shows a `clojure -M:<alias>` command")
      (is (on-cp? "urpx/test_fixtures.clj")
          (str cmd " does not put urpx.test-fixtures on the classpath"))
      (is (on-cp? "urpx-fixtures/pge-e-1-2026-03-01.jsonld")
          (str cmd " does not put the vendored fixtures on the classpath")))))

(deftest every-vendored-fixture-records-its-provenance
  (testing "a specimen whose origin is unrecorded is a provenance gap, and this
            drifted twice while fixtures were added by hand, which is why it is
            a test rather than a convention.

            Provenance lives in each fixture's own .license sidecar: the
            upstream repository, the commit the bytes came from, and the path.
            It used to live in a table in FIXTURES.md, which made it a pin to
            keep current rather than a fact about a frozen file, and tracking
            that table is what coupled this library's suite to one client's
            editing schedule."
    (let [dir      (io/file "test/resources/urpx-fixtures")
          on-disk  (->> (.listFiles dir)
                        (map #(.getName %))
                        (filter #(str/ends-with? % ".jsonld"))
                        set)
          sidecars (->> (.listFiles dir)
                        (map #(.getName %))
                        (filter #(str/ends-with? % ".jsonld.license"))
                        (map #(subs % 0 (- (count %) 8)))
                        set)]
      (is (seq on-disk) "sanity: there are vendored fixtures to check")
      (is (empty? (set/difference on-disk sidecars))
          (str "missing .license sidecar: "
               (pr-str (sort (set/difference on-disk sidecars)))))
      (doseq [f (sort on-disk)]
        (let [side (slurp (io/file dir (str f ".license")))]
          ;; REUSE-IgnoreStart
          (is (re-find #"SPDX-License-Identifier:" side) (str f ": no SPDX licence"))
          ;; REUSE-IgnoreEnd
          (is (re-find #"Derived from https://" side) (str f ": no upstream repository"))
          (is (re-find #"(?m)^at [0-9a-f]{7,40}, path \S+" side)
              (str f ": no upstream commit and path"))))
      (testing "and FIXTURES.md no longer carries a pin table to drift"
        (let [md (doc "test/resources/urpx-fixtures/FIXTURES.md")]
          (is (some? md))
          (is (not (re-find #"\| `[^`]+\.jsonld`" md))
              "FIXTURES.md lists fixtures in a table again; provenance belongs in the sidecars"))))))

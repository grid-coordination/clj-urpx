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
  (:require [clojure.java.io :as io]
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

(defn- referenced-symbols
  "Qualified symbols a doc's Clojure blocks mention, as [alias name] pairs.
   Only aliases this project owns; clojure.core and java interop are ignored."
  [md]
  (let [owned #{"core" "schema" "coerce" "index" "price" "schedule" "holidays"
                "tz" "emit" "dsl" "validate"}]
    (->> (fenced-blocks md "clojure")
         (mapcat #(re-seq #"\(([a-z-]+)/([a-zA-Z][a-zA-Z0-9*?<>=+-]*)" %))
         (keep (fn [[_ alias nm]] (when (owned alias) [alias nm])))
         distinct
         sort)))

(def ^:private alias->ns
  {"core" 'urpx.core "schema" 'urpx.schema "coerce" 'urpx.coerce
   "index" 'urpx.index "price" 'urpx.price "schedule" 'urpx.schedule
   "holidays" 'urpx.holidays "tz" 'urpx.tz-inference "emit" 'urpx.emit
   "dsl" 'urpx.dsl "validate" 'urpx.validate})

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

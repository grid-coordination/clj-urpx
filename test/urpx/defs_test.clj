(ns urpx.defs-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [urpx.defs :as defs :refer [defurpx]]))

(defmethod defs/kind-schema ::rates
  [_]
  [:map-of :keyword decimal?])

(defmethod defs/kind-schema ::utility
  [_]
  [:map
   [:key :string]
   [:eia-id {:optional true} :string]])

(defmethod defs/kind-schema ::open
  [_]
  [:map {:closed false}
   [:key :string]])

(defurpx ::rates loaded-rates
  "Defined as the file loads."
  {:peak 16.10M})

(deftest a-definition-made-as-a-file-loads
  (is (= {:peak 16.10M} loaded-rates))
  (is (= ::rates (:urpx/kind (meta #'loaded-rates))))
  (is (= [#'loaded-rates]
         (defs/defs-of-kind ::rates ['urpx.defs-test]))))

(defn- scratch-ns
  "A fresh namespace that refers clojure.core and `defurpx`."
  []
  (let [n (create-ns (gensym "urpx.defs-test.scratch-"))]
    (binding [*ns* n]
      (refer-clojure)
      (refer 'urpx.defs :only '[defurpx]))
    n))

(defn- eval-in
  "Evaluate `form` in namespace `n` with `*file*` bound to \"corpus/x.clj\"."
  [n form]
  (binding [*ns* n
            *file* "corpus/x.clj"]
    (eval form)))

(defn- thrown
  "The ex-info `f` throws, unwrapped from the CompilerException that
   macroexpansion wraps it in."
  [f]
  (try
    (f)
    nil
    (catch clojure.lang.Compiler$CompilerException e
      (ex-cause e))
    (catch clojure.lang.ExceptionInfo e
      e)))

(defn- interned?
  [n sym]
  (contains? (ns-interns n) sym))

(deftest defurpx-defs-a-valid-value-with-its-kind-in-var-metadata
  (let [n (scratch-ns)
        v (eval-in n '(defurpx :urpx.defs-test/rates r
                        "Cents per kWh."
                        {:peak 16.10M}))]
    (is (= {:peak 16.10M} @v))
    (is (= ::rates (:urpx/kind (meta v))))
    (is (= "Cents per kWh." (:doc (meta v))))
    (is (= "corpus/x.clj" (:file (meta v))))
    (is (integer? (:line (meta v))))
    (testing "without a docstring"
      (is (= {:off-peak 8.05M}
             @(eval-in n '(defurpx :urpx.defs-test/rates r2 {:off-peak 8.05M})))))
    (remove-ns (ns-name n))))

(deftest an-invalid-value-interns-nothing
  (let [n (scratch-ns)
        form (with-meta '(defurpx :urpx.defs-test/rates bad
                           {:peak 16.9 :off-peak "8.05"})
               {:line 7})
        e (thrown #(eval-in n form))]
    (is (not (interned? n 'bad)))
    (is (= (str "Invalid :urpx.defs-test/rates " (ns-name n) "/bad (corpus/x.clj:7)")
           (first (str/split-lines (ex-message e)))))
    (is (= #{"  at [:off-peak]: \"8.05\" should be a decimal"
             "  at [:peak]: 16.9 should be a decimal"}
           (set (rest (str/split-lines (ex-message e))))))
    (is (= {:kind ::rates
            :where {:ns (ns-name n) :name 'bad :file "corpus/x.clj" :line 7}
            :errors {:peak ["should be a decimal"]
                     :off-peak ["should be a decimal"]}}
           (ex-data e)))
    (remove-ns (ns-name n))))

(deftest a-failed-redefinition-keeps-the-previous-value
  (let [n (scratch-ns)]
    (eval-in n '(defurpx :urpx.defs-test/rates r {:peak 16.10M}))
    (is (some? (thrown #(eval-in n '(defurpx :urpx.defs-test/rates r {:peak 1.0})))))
    (is (= {:peak 16.10M} @(ns-resolve n 'r)))
    (remove-ns (ns-name n))))

(deftest an-unknown-kind-throws-and-interns-nothing
  (let [n (scratch-ns)
        e (thrown #(eval-in n '(defurpx :urpx.defs-test/ratez r {:peak 16.10M})))]
    (is (= "Unknown defurpx kind :urpx.defs-test/ratez" (ex-message e)))
    (is (= {:kind ::ratez} (ex-data e)))
    (is (not (interned? n 'r)))
    (remove-ns (ns-name n))))

(deftest kind-schema-default-refuses
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Unknown defurpx kind :nope"
                        (defs/kind-schema :nope))))

(deftest the-kind-must-be-a-keyword-literal
  (let [n (scratch-ns)]
    (eval-in n '(def k :urpx.defs-test/rates))
    (let [e (thrown #(eval-in n '(defurpx k r {:peak 16.10M})))]
      (is (str/starts-with? (ex-message e) "defurpx kind must be a keyword literal"))
      (is (not (interned? n 'r))))
    (remove-ns (ns-name n))))

(deftest extra-forms-throw
  (let [n (scratch-ns)]
    (testing "a value after the value"
      (let [e (thrown #(eval-in n '(defurpx :urpx.defs-test/rates r "doc" {} {:junk 1M})))]
        (is (= "defurpx r takes a docstring and a value, got 3 forms after the name"
               (ex-message e)))
        (is (not (interned? n 'r)))))
    (testing "two values without a docstring"
      (is (some? (thrown #(eval-in n '(defurpx :urpx.defs-test/rates r {} {}))))))
    (testing "no value"
      (is (some? (thrown #(eval-in n '(defurpx :urpx.defs-test/rates r))))))
    (is (not (interned? n 'r)))
    (remove-ns (ns-name n))))

(deftest kind-schemas-are-closed
  (let [n (scratch-ns)
        e (thrown #(eval-in n '(defurpx :urpx.defs-test/utility u
                                 {:key "scl" :eia-idd "16868"})))]
    (is (= {:eia-idd ["disallowed key"]} (:errors (ex-data e))))
    (is (not (interned? n 'u)))
    (testing "a map that says {:closed false} stays open"
      (is (= {:key "scl" :extra 1}
             @(eval-in n '(defurpx :urpx.defs-test/open o {:key "scl" :extra 1})))))
    (remove-ns (ns-name n))))

(deftest defs-of-kind-reads-var-metadata-in-the-namespaces-given
  (let [a (scratch-ns)
        b (scratch-ns)]
    (eval-in a '(defurpx :urpx.defs-test/rates r2 {:peak 2M}))
    (eval-in a '(defurpx :urpx.defs-test/rates r1 {:peak 1M}))
    (eval-in a '(defurpx :urpx.defs-test/utility u {:key "scl"}))
    (eval-in a '(def plain {:peak 3M}))
    (eval-in b '(defurpx :urpx.defs-test/rates rb {:peak 4M}))
    (is (= [(ns-resolve a 'r1) (ns-resolve a 'r2)]
           (defs/defs-of-kind ::rates [a])))
    (is (= [(ns-resolve a 'u)]
           (defs/defs-of-kind ::utility [(ns-name a)]))
        "a namespace may be named by symbol")
    (is (= 3 (count (defs/defs-of-kind ::rates [a b]))))
    (testing "an unmapped var is not a definition"
      (ns-unmap a 'r2)
      (is (= [(ns-resolve a 'r1)]
             (defs/defs-of-kind ::rates [a]))))
    (remove-ns (ns-name a))
    (remove-ns (ns-name b))))

(deftest the-clj-kondo-export-names-a-hook-that-ships
  (let [dir "clj-kondo.exports/energy.grid-coordination/clj-urpx/"
        config (edn/read-string (slurp (io/resource (str dir "config.edn"))))
        hook (get-in config [:hooks :macroexpand 'urpx.defs/defurpx])]
    (is (= 'hooks.urpx.defs/defurpx hook))
    (is (some? (io/resource (str dir
                                 (-> (namespace hook)
                                     (str/replace "." "/")
                                     (str/replace "-" "_"))
                                 ".clj"))))))

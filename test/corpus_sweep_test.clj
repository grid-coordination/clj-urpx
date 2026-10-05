(ns corpus-sweep-test
  (:require [babashka.fs :as fs]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [corpus-sweep]
            [urpx.load :as load]))

(def ^:private fixture "urpx-fixtures/cpau-e-14-2025-07-01.jsonld")

(defn- layout-json [roots]
  (json/write-str {"roots"     roots
                   "kinds"     {"plan" {"include" ["*.jsonld"]}}
                   "documents" ["plan"]}))

(defn- corpus
  "A fresh directory holding the fixture at `in` and at `out`, and a
   corpus.json declaring only `in` when `layout?`."
  [layout?]
  (let [dir (fs/file (fs/create-temp-dir {:prefix "corpus-sweep"}))]
    (doseq [p ["in/plan.jsonld" "out/plan.jsonld"]]
      (io/make-parents (io/file dir p))
      (spit (io/file dir p) (slurp (io/resource fixture))))
    (when layout?
      (spit (io/file dir "corpus.json") (layout-json ["in"])))
    dir))

(defn- doc-count [scanned] (count (:urpx.load/documents scanned)))

(deftest a-root-corpus-json-is-the-default-layout
  (let [one     (doc-count (load/scan [(io/file (io/resource fixture))]))
        with    (corpus true)
        without (corpus false)
        all     (doto (io/file without "all.json") (spit (layout-json ["in" "out"])))]
    (try
      (is (pos? one))
      (testing "the root's corpus.json, with no layout given"
        (is (= one (doc-count (corpus-sweep/load-corpus with))))
        (is (= one (doc-count (corpus-sweep/load-corpus with nil)))))
      (testing "every **/*.jsonld, with no corpus.json"
        (is (= (* 2 one) (doc-count (corpus-sweep/load-corpus without)))))
      (testing "a given layout over the root's corpus.json"
        (is (= (* 2 one) (doc-count (corpus-sweep/load-corpus with all)))))
      (finally
        (run! fs/delete-tree [with without])))))

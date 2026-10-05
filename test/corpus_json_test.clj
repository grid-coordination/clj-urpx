(ns corpus-json-test
  "The dev-only corpus.json walker in corpus/, held to the walker contract of
  urpx-rate-plans' schema/corpus.schema.json, which is vendored beside the
  fixtures."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [corpus-json]
            [urpx-upstream])
  (:import [com.networknt.schema InputFormat JsonSchemaFactory SpecVersion$VersionFlag]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- tree
  "A fresh directory holding an empty file at each of `paths`."
  [paths]
  (let [dir (.toFile (Files/createTempDirectory "corpus-json" (make-array FileAttribute 0)))]
    (doseq [p paths]
      (io/make-parents (io/file dir p))
      (spit (io/file dir p) ""))
    dir))

(defn- schema-errors
  [layout-json]
  (let [schema (.getSchema (JsonSchemaFactory/getInstance SpecVersion$VersionFlag/V202012)
                           (slurp (io/resource "corpus.schema.json")))]
    (set (map str (.validate schema ^String layout-json InputFormat/JSON)))))

(deftest urpx-corpus-json-conforms
  (let [s (slurp (io/resource "urpx.corpus.json"))]
    (is (= #{} (schema-errors s)))
    (testing "the check can fail"
      (is (seq (schema-errors (json/write-str (assoc (json/read-str s) "extra" 1))))))))

(def ^:private layout
  {"roots"     ["a" "b"]
   "kinds"     {"doc"      {"include" ["**/versions/*/doc.jsonld"]}
                "manifest" {"include" ["*/versions/*/note.toml"]}}
   "documents" ["doc"]})

(def ^:private files
  ["a/x/versions/1/doc.jsonld"
   "a/x/versions/1/doc.jsonld.license"
   "a/x/versions/1/note.toml"
   "a/x/y/versions/3/doc.jsonld"
   "a/versions/2/doc.jsonld"
   "a/Z/versions/1/doc.jsonld"
   "b/x/versions/1/doc.jsonld"
   "c/x/versions/1/doc.jsonld"])

(deftest documents-by-default
  (testing "every root, a ** of zero or more segments, no other root"
    (is (= ["a/Z/versions/1/doc.jsonld"
            "a/versions/2/doc.jsonld"
            "a/x/versions/1/doc.jsonld"
            "a/x/y/versions/3/doc.jsonld"
            "b/x/versions/1/doc.jsonld"]
           (corpus-json/paths (tree files) layout)))))

(deftest named-kinds
  (testing "* stays within one segment"
    (is (= ["a/x/versions/1/note.toml"]
           (corpus-json/paths (tree (conj files "a/x/y/versions/1/note.toml"))
                              layout ["manifest"]))))
  (testing "several kinds are one sorted list"
    (is (= ["a/x/versions/1/doc.jsonld" "a/x/versions/1/note.toml"]
           (corpus-json/paths (tree ["a/x/versions/1/doc.jsonld" "a/x/versions/1/note.toml"])
                              layout ["manifest" "doc"]))))
  (testing "an undeclared kind refuses"
    (is (= :corpus-json/unknown-kind
           (try (corpus-json/paths (tree files) layout ["nope"])
                (catch clojure.lang.ExceptionInfo e (:reason (ex-data e))))))))

(defn- matches? [glob path] (boolean (re-matches (corpus-json/glob-regex glob) path)))

(deftest glob-syntax
  (is (matches? "?/f" "x/f"))
  (is (not (matches? "?/f" "xy/f")))
  (is (matches? "k[!b]/f" "ka/f"))
  (is (not (matches? "k[!b]/f" "kb/f")))
  (is (matches? "k[]a]/f" "k]/f"))
  (is (not (matches? "k[!a]f" "k/f")))
  (is (matches? "x/**" "x/a/b"))
  (is (matches? "a.b" "a.b"))
  (is (not (matches? "a.b" "axb"))))

(deftest c-locale-byte-order
  (testing "UTF-8 byte order, which differs from String order above the BMP"
    (is (= ["a/B" "a/a" "a/～" "a/😀"]
           (corpus-json/paths (tree ["a/a" "a/😀" "a/B" "a/～"])
                              {"roots" ["a"] "kinds" {"k" {"include" ["*"]}} "documents" ["k"]})))))

(deftest cli
  (let [root (tree files)
        f    (io/file root "elsewhere.json")
        run  #(with-out-str (apply corpus-json/-main "--layout" (str f) "--root" (str root) %&))]
    (spit f (json/write-str layout))
    (testing "--all is kind<TAB>path over every kind"
      (is (= (str/join (map #(str % "\n")
                            ["doc\ta/Z/versions/1/doc.jsonld"
                             "doc\ta/versions/2/doc.jsonld"
                             "doc\ta/x/versions/1/doc.jsonld"
                             "doc\ta/x/y/versions/3/doc.jsonld"
                             "doc\tb/x/versions/1/doc.jsonld"
                             "manifest\ta/x/versions/1/note.toml"]))
             (run "--all"))))
    (testing "no arguments is the documents"
      (is (= (str/join (map #(str % "\n") (corpus-json/paths root layout)))
             (run))))
    (testing "a directory reads its corpus.json"
      (spit (io/file root "corpus.json") (json/write-str layout))
      (is (= layout (corpus-json/read-layout root))))))

(deftest urpx-upstream-walks-test-case-documents
  (let [root (tree ["test-cases/t/data/d.jsonld"
                    "test-cases/t/data/d.jsonld.license"
                    "test-cases/t/assets/s.pdf"
                    "test-cases/manifest-all.json"
                    "context/urpx-context.jsonld"])]
    (is (= [(io/file root "test-cases/t/data/d.jsonld")]
           (urpx-upstream/sources root)))))

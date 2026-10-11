;; SPDX-FileCopyrightText: 2026 Clark Communications Corporation
;; SPDX-License-Identifier: MIT
(ns corpus-json
  "A walker for any corpus whose layout is declared in a corpus.json.

  The format is urpx-rate-plans' (its schema/corpus.schema.json): each kind's
  `include` globs are evaluated under every one of `roots`, and `documents`
  names the kinds a consumer loads. In a glob, `*`, `?` and `[...]` match
  within one path segment, and a `**` segment matches zero or more segments.

  As a program it follows the corpus.json walker contract:

      clojure -M:corpus -m corpus-json [--layout <corpus.json>] [--root <dir>] [<kind>... | --all]

  It prints paths relative to the corpus root, POSIX-separated, one per line in
  C-locale byte order: the documents with no arguments, the files of the named
  kinds otherwise, and every kind as kind<TAB>path with --all. The root
  defaults to the working directory and the layout to the root's corpus.json;
  `--layout` reads one kept outside the corpus, as corpus/urpx.corpus.json is."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File]
           [java.nio.charset StandardCharsets]
           [java.nio.file FileVisitOption Files LinkOption Path]
           [java.util Arrays]
           [java.util.regex Pattern]))

(defn read-layout
  "Parse a corpus.json from `source`: a directory holding one, or anything
   `slurp` reads. Keys stay strings, since kind names are data."
  [source]
  (let [dir? (and (or (string? source) (instance? File source))
                  (.isDirectory (io/file source)))]
    (json/read-str (slurp (if dir? (io/file source "corpus.json") source)))))

(defn- segment-regex
  "One glob path segment as a regex that never crosses a `/`."
  [^String seg]
  (loop [i 0, out (StringBuilder.)]
    (if (>= i (count seg))
      (str out)
      (let [c (.charAt seg i)
            ;; A class's `]` may be its first member, after an optional `!`.
            close (when (= c \[)
                    (let [j (cond-> (inc i) (= \! (get seg (inc i))) inc)]
                      (str/index-of seg "]" (inc j))))]
        (cond
          (= c \*) (recur (inc i) (.append out "[^/]*"))
          (= c \?) (recur (inc i) (.append out "[^/]"))
          close    (let [body (subs seg (inc i) close)
                         [neg body] (if (str/starts-with? body "!")
                                      [true (subs body 1)]
                                      [false body])
                         body (str/replace body #"[\\\[\]^&]" #(str "\\" %))]
                     (recur (inc close)
                            (.append out (str "[[" (when neg "^") body "]&&[^/]]"))))
          :else    (recur (inc i) (.append out (Pattern/quote (str c)))))))))

(defn glob-regex
  "`glob`, relative to a root, as a Pattern over root-relative POSIX paths."
  [glob]
  (let [segs (remove str/blank? (str/split glob #"/"))
        n    (count segs)]
    (re-pattern
     (str/join (map-indexed (fn [i seg]
                              (let [last? (= i (dec n))]
                                (cond
                                  (and (= seg "**") last?) "(?:[^/]+/)*[^/]+"
                                  (= seg "**")             "(?:[^/]+/)*"
                                  last?                    (segment-regex seg)
                                  :else                    (str (segment-regex seg) "/"))))
                            segs)))))

(defn- byte-order
  "Compare as C-locale `sort` does: by UTF-8 bytes, unsigned."
  [^String a ^String b]
  (Arrays/compareUnsigned (.getBytes a StandardCharsets/UTF_8)
                          (.getBytes b StandardCharsets/UTF_8)))

(defn- files-under
  "Every regular file below `dir`, as POSIX paths relative to it."
  [^File dir]
  (when (.isDirectory dir)
    (let [top (.toPath dir)]
      (with-open [s (Files/walk top (make-array FileVisitOption 0))]
        (->> (iterator-seq (.iterator s))
             (filter #(Files/isRegularFile % (make-array LinkOption 0)))
             (mapv #(str/replace (str (.relativize top ^Path %)) File/separator "/")))))))

(defn- include-globs
  [layout kind]
  (or (get-in layout ["kinds" kind "include"])
      (throw (ex-info (str "corpus.json declares no kind " (pr-str kind)
                           "; it declares " (str/join ", " (keys (get layout "kinds"))))
                      {:reason ::unknown-kind
                       :kind   kind
                       :kinds  (vec (keys (get layout "kinds")))}))))

(defn- kind-paths
  "Paths relative to `root` of every file of `kind`, walking the kind's own
   roots when it declares them and the layout's otherwise."
  [root layout kind]
  (let [res (mapv glob-regex (include-globs layout kind))]
    (for [r (or (get-in layout ["kinds" kind "roots"])
                (get layout "roots"))
          p (files-under (io/file root r))
          :when (some #(re-matches % p) res)]
      (str r "/" p))))

(defn paths
  "Paths relative to `root` of every file of `kinds`, or of the documents when
   `kinds` is empty, in C-locale byte order."
  ([root layout] (paths root layout nil))
  ([root layout kinds]
   (->> (or (seq kinds) (get layout "documents"))
        (mapcat #(kind-paths root layout %))
        distinct
        (sort byte-order)
        vec)))

(defn sources
  "Every document under `root`, as Files, in the order of `paths`."
  [root layout]
  (mapv #(io/file root %) (paths root layout)))

(defn lines
  "What the walker CLI prints for `args`: kind names, `[\"--all\"]`, or none."
  [root layout args]
  (if (= args ["--all"])
    (->> (for [k (keys (get layout "kinds"))
               p (paths root layout [k])]
           (str k "\t" p))
         (sort byte-order)
         vec)
    (paths root layout args)))

(defn -main
  [& args]
  (let [[opts args] (loop [opts {}, [a v & more :as as] args]
                      (if (#{"--layout" "--root"} a)
                        (recur (assoc opts a v) more)
                        [opts (vec as)]))
        root        (get opts "--root" ".")
        layout      (read-layout (get opts "--layout" (io/file root "corpus.json")))]
    (try
      (doseq [l (lines root layout args)]
        (.write *out* (str l "\n")))
      (flush)
      (catch clojure.lang.ExceptionInfo e
        (binding [*out* *err*] (println (ex-message e)))
        (System/exit 2)))))

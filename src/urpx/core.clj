(ns urpx.core
  "URPX rate plan loading and price resolution.

  Parses URPX JSON-LD rate plan documents into Clojure data structures
  and resolves prices for any given timestamp and timezone."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- prefixed-key->keyword
  "Convert a JSON-LD string key into a Clojure keyword.

   - JSON-LD reserved keys like '@id', '@type', '@context' become :jsonld/id, etc.
   - Prefixed CURIE keys like 'urpx:name' become :urpx/name.
   - Bare keys become unqualified keywords."
  [k]
  (cond
    (str/starts-with? k "@")
    (keyword "jsonld" (subs k 1))

    (str/includes? k ":")
    (let [[prefix local] (str/split k #":" 2)]
      (keyword prefix local))

    :else (keyword k)))

(defn- transform-keys
  "Walk a parsed JSON structure converting string map keys to keywords."
  [v]
  (cond
    (map? v)
    (persistent!
     (reduce-kv (fn [acc k val]
                  (assoc! acc
                          (if (string? k) (prefixed-key->keyword k) k)
                          (transform-keys val)))
                (transient {}) v))

    (sequential? v)
    (mapv transform-keys v)

    :else v))

(defn parse-rate-plan
  "Parse a URPX JSON-LD rate plan document from `src` (anything io/reader can handle:
   File, URL, Reader, InputStream, or path string).

   Returns a Clojure map with keys converted to namespaced keywords:
   prefixed CURIEs (e.g. 'urpx:name') become :urpx/name; JSON-LD reserved keys
   ('@id', '@type', '@context') become :jsonld/id, :jsonld/type, :jsonld/context.
   String values (including CURIE references) are left unchanged."
  [src]
  (with-open [r (io/reader src)]
    (transform-keys (json/read r))))

(defn load-rate-plan
  "Load a URPX rate plan from a file path. Convenience wrapper around `parse-rate-plan`."
  [path]
  (parse-rate-plan (io/file path)))

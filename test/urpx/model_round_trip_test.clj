(ns urpx.model-round-trip-test
  "Every node of each type below, as the vendored fixtures write it, coerces
  to a valid entity and round-trips through encode, JSON and parse."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [urpx.coerce :as coerce]
            [urpx.core :as core]
            [urpx.schema :as schema])
  (:import [java.io StringReader]))

(def ^:private fixture-files
  (->> (io/file (io/resource "urpx-fixtures"))
       file-seq
       (filter #(str/ends-with? (.getName ^java.io.File %) ".jsonld"))
       sort))

(defn- nodes-of
  "Every raw node of @type `t` in the vendored fixtures."
  [t]
  (for [f fixture-files
        node (tree-seq coll? seq (core/load-graph f))
        :when (and (map? node)
                   (= t (:jsonld/type node)))]
    node))

(defn- reread
  "`typed` encoded by `schema`, written as JSON, parsed and coerced again."
  [schema typed]
  (->> (coerce/encode schema typed)
       core/keys->jsonld
       json/write-str
       StringReader.
       core/parse-rate-plan
       (coerce/coerce schema)))

(def ^:private modeled
  [["urpx:ComparisonNode" schema/ComparisonNode]
   ["urpx:BooleanOperationNode" schema/BooleanOperationNode]
   ["urpx:Organization" schema/Organization]
   ["urpx:OrganizationIdentity" schema/OrganizationIdentity]
   ["urpx:OrganizationName" schema/OrganizationName]
   ["urpx:AlternativeDimension" schema/AlternativeDimension]
   ["urpx:SourcePublication" schema/SourcePublication]
   ["urpx:RateCase" schema/RateCase]])

(deftest every-fixture-node-round-trips
  (doseq [[t schema] modeled]
    (testing t
      (let [nodes (nodes-of t)]
        (is (seq nodes))
        (doseq [raw nodes
                :let [typed (coerce/coerce schema raw)]]
          (is (nil? (coerce/explain schema typed))
              (:jsonld/id raw))
          (is (= typed (reread schema typed))
              (:jsonld/id raw)))))))

(deftest offered-by-coerces-through-the-organization-schema
  (let [versions (concat (nodes-of "urpx:RatePlanVersion")
                         (nodes-of "urpx:RatePlanModifierVersion"))
        offered (for [v versions
                      :when (:urpx/offeredBy v)]
                  [v (coerce/coerce-doc v)])]
    (is (seq offered))
    (doseq [[raw typed] offered
            :let [org (:urpx/offeredBy typed)]]
      (is (nil? (coerce/explain schema/OfferingOrganization org))
          (:jsonld/id raw))
      (when (get-in raw [:urpx/offeredBy :urpx/hasIdentity])
        (is (vector? (:urpx/hasIdentity org))
            (:jsonld/id raw))))))

(ns urpx.test-fixtures
  "Classpath-based loading for vendored URPX test fixtures.

   Fixtures live at test/resources/urpx-fixtures/<name>.jsonld and are
   addressed by short name (e.g. \"pge-e-1-2026-03-01\"). Source provenance
   and SPDX attribution live alongside the fixtures in
   test/resources/urpx-fixtures/FIXTURES.md and per-file .license sidecars.

   The v0.2.0 fixtures are JSON-LD `@graph` documents that bundle a
   urpx:RatePlan with its sibling urpx:RatePlanModifier(s). Use
   `load-rate-plan` for the unique RatePlan and `load-modifier` for the
   unique sibling modifier; `load-graph` exposes the full entity list."
  (:require [clojure.java.io :as io]
            [urpx.coerce :as coerce]
            [urpx.core :as core]))

(defn fixture-resource
  "Return the classpath URL for the named URPX fixture, or throw."
  [name]
  (or (io/resource (str "urpx-fixtures/" name ".jsonld"))
      (throw (ex-info (str "Missing URPX fixture: urpx-fixtures/" name ".jsonld")
                      {:name name}))))

(defn- fixture-path
  ^String [name]
  (.getPath (fixture-resource name)))

(defn load-graph
  "Load the named fixture and return every top-level entity in its @graph."
  [name]
  (core/load-graph (fixture-path name)))

(defn load-rate-plan
  "Load the named fixture, find the unique urpx:RatePlan in its @graph,
   and return the coerced shape. Throws if the document has zero or
   multiple RatePlans — use `load-graph` for ambiguous cases."
  [name]
  (coerce/coerce-rate-plan (core/load-rate-plan (fixture-path name))))

(defn load-modifier
  "Load the named fixture and return the unique urpx:RatePlanModifier
   in its @graph (coerced). Throws if zero or multiple modifiers are
   present in the document."
  [name]
  (let [entities (load-graph name)
        mods (filter #(= "urpx:RatePlanModifier" (:jsonld/type %)) entities)]
    (case (count mods)
      0 (throw (ex-info (str "No urpx:RatePlanModifier in " name) {:name name}))
      1 (coerce/coerce-rate-plan-modifier (first mods))
      (throw (ex-info (str "Multiple urpx:RatePlanModifier entities in " name)
                      {:name name :ids (mapv :jsonld/id mods)})))))

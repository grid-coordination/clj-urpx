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
  "Load the named fixture and return the unique urpx:RatePlanModifier (coerced).
   Throws if zero or multiple modifiers are present.

   Goes through `core/load-rate-plan-modifier` rather than filtering
   `load-graph` itself: load-graph returns top-level entities only, so the
   hand-rolled filter this replaced could not see a modifier inside a
   urpx:URPXPackage and threw \"No urpx:RatePlanModifier\" on every wave-1
   fixture. `load-rate-plan` above always used the descending loader, which is
   why plans worked and modifiers did not."
  [name]
  (coerce/coerce-rate-plan-modifier (core/load-rate-plan-modifier (fixture-path name))))

(def scenario-cost-model-example
  "The urpx:ScenarioCostModel example in the description of urpx-org/urpx#72,
   verbatim."
  "{
  \"@context\": [
    \"https://urpx.org/ns/context/releases/v0.5.1/jsonld/urpx-context.jsonld\",
    { \"sce\": \"https://rate-plans.grid-coordination.energy/sce/\" }
  ],
  \"@id\": \"sce:scm-tou-d-prime-care-cca-crs-v2020\",
  \"@type\": \"urpx:ScenarioCostModel\",
  \"urpx:consumesVersion\": { \"@id\": \"sce:tou-d-prime-2026-03-20\" },
  \"urpx:consumesModifierVersion\": [
    { \"@id\": \"sce:care-2026-03-20\" },
    { \"@id\": \"sce:cca-crs-2026-03-20\" }
  ],
  \"urpx:selectsProfileAlternative\": { \"@id\": \"sce:cca-crs-2026-03-20-pa-vintage-2020\" }
}")

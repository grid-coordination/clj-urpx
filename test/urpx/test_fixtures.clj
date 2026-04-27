(ns urpx.test-fixtures
  "Classpath-based loading for vendored URPX test fixtures.

   Fixtures live at test/resources/urpx-fixtures/<name>.jsonld and are
   addressed by short name (e.g. \"cpau-e-1-tou-2026-01-01\"). Source
   provenance and SPDX attribution live alongside the fixtures in
   test/resources/urpx-fixtures/FIXTURES.md and per-file .license sidecars."
  (:require [clojure.java.io :as io]
            [urpx.coerce :as coerce]
            [urpx.core :as core]))

(defn fixture-resource
  "Return the classpath URL for the named URPX fixture, or throw."
  [name]
  (or (io/resource (str "urpx-fixtures/" name ".jsonld"))
      (throw (ex-info (str "Missing URPX fixture: urpx-fixtures/" name ".jsonld")
                      {:name name}))))

(defn load-raw
  "Parse the named fixture without coercion (raw map of namespaced keys
   with string leaf values)."
  [name]
  (core/parse-rate-plan (fixture-resource name)))

(defn load-rate-plan
  "Parse and coerce the named fixture as a urpx:RatePlan."
  [name]
  (coerce/coerce-rate-plan (load-raw name)))

(defn load-rate-plan-modifier
  "Parse and coerce the named fixture as a urpx:RatePlanModifier."
  [name]
  (coerce/coerce-rate-plan-modifier (load-raw name)))

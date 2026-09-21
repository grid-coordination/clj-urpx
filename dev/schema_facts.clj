(ns schema-facts
  "Dump what clj-urpx's Malli schemas actually model, as EDN, for the SHACL
  drift detector to diff against the shipped shapes.

  Introspects the live schemas through malli.core rather than parsing
  schema.clj as text: the schemas are the truth, and a text parse would drift
  from them silently.

      clojure -M:drift-facts > facts.edn

  Inspection is deliberately SHALLOW. Walking a child schema's full form pulls
  in whole nested registries (ConditionExpression alone is thousands of
  characters), and none of that is needed: the drift detector compares property
  presence, cardinality and enum membership, all of which are visible one or
  two levels down."
  (:require [malli.core :as m]
            [urpx.schema]))

(defn- unwrap
  "A map entry's value is a ::schema wrapper; the real child is its first child."
  [v]
  (let [c (first (m/children v))]
    (try (m/deref c) (catch Exception _ c))))

(defn- shallow-form
  "m/form, but only the top level: children are reduced to their type keyword
   so a nested registry cannot explode the output."
  [s]
  (try
    (let [t (m/type s)]
      (if (= :malli.core/val t) (shallow-form (unwrap s)) t))
    (catch Exception _ nil)))

(defn- enum-members
  "If `child` is the enum-ref shape [:map [:jsonld/id [:enum ...]]], return the
   members. Looks exactly two levels down and no further."
  [child]
  (try
    (when (= :map (m/type child))
      (some (fn [[k v]]
              (when (= :jsonld/id k)
                (let [inner (unwrap v)]
                  (when (= :enum (m/type inner))
                    (vec (m/children inner))))))
            (m/entries child)))
    (catch Exception _ nil)))

(defn- vector-valued?
  "True if the entry is 0..N: a :vector, or a combinator with a :vector arm.

   Recurs through :or and :and rather than testing the arms one level deep.
   A cardinality refinement is written [:and [:vector X] [:fn ...]] (see
   PriceSummary/includesPrice, which is 0..N upstream but must hold at least
   two members), and a check that stopped at the top level read that as a
   single value and reported drift against a schema that has none."
  [child]
  (try
    (case (m/type child)
      :vector   true
      (:or :and) (boolean (some vector-valued? (m/children child)))
      false)
    (catch Exception _ false)))

(defn- entry-facts [[k v]]
  (let [child (unwrap v)
        props (m/properties v)]
    {:property (when (qualified-keyword? k) (str (namespace k) ":" (name k)))
     :required (not (:optional props))
     :vector   (vector-valued? child)
     :decoder  (boolean (:decode/urpx-jsonld props))
     :kind     (shallow-form child)
     :enum     (enum-members child)}))

(defn- type-literals
  "The @type literals a schema admits: one for [:= \"...\"], several for an
   :enum. nil when the schema has no :jsonld/type entry, which means it is a
   value shape rather than an entity."
  [s]
  (some (fn [[k v]]
          (when (= :jsonld/type k)
            (let [inner (unwrap v)]
              (case (m/type inner)
                :=    (vec (m/children inner))
                :enum (vec (m/children inner))
                nil))))
        (m/entries s)))

(defn schema-facts []
  (into (sorted-map)
        (keep (fn [[sym v]]
                (let [val (deref v)]
                  (when (or (vector? val) (m/schema? val))
                    (try
                      (let [s (m/deref (m/schema val))]
                        (when (= :map (m/type s))
                          (when-let [types (type-literals s)]
                            [(str sym)
                             {:types   types
                              :closed  (boolean (:closed (m/properties s)))
                              :entries (vec (keep #(let [f (entry-facts %)]
                                                     (when (:property f) f))
                                                  (m/entries s)))}])))
                      (catch Exception _ nil)))))
              (ns-publics 'urpx.schema))))

(defn -main [& _]
  (binding [*print-length* nil *print-level* nil]
    (prn {:generated-by "dev/schema_facts.clj"
          :schemas (schema-facts)})))

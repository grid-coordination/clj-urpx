(ns urpx.dsl
  "DSL spike (URPX-4wa): hand-rolled builder for urpx:Season, used to validate
  the introspection-based approach for the broader DSL epic (URPX-cjk).

  The builder pattern proven here:

    1. Accept user-friendly inputs (positional name + kwargs/trailing map of
       short-form keys).
    2. Assemble a typed-shape map with the canonical urpx:* keys and the
       inferred :jsonld/type.
    3. Run urpx.coerce/coerce against the entity's Malli schema — auto-coerces
       any raw inputs (numbers, ISO strings, '--MM' xsd:gMonth strings, …) by
       routing through the schema's :decode/urpx-jsonld decoders.
    4. Validate via the same schema; throw ex-info with the Malli explanation
       on failure so the caller sees what went wrong.

  Output is a coerced entity (typed Clojure data) ready for resolve-prices,
  the urpx.emit write side, or composition into a larger DSL (rate-plan,
  price-definition, …) in URPX-upf.

  URPX-0cq generalizes this pattern to a runtime generator that derives a
  builder from any urpx.schema entity schema; URPX-upf populates the full
  set of entity-type builders."
  (:require [urpx.coerce :as coerce]
            [urpx.schema :as schema]))

(defn season
  "Construct a coerced urpx:Season entity from a human-readable name and a
   kwargs/trailing-map of optional fields.

     :months              — vector of month-of-year ints (1..12) or xsd:gMonth
                             strings ('--05'); auto-coerced.
     :yearly-recurrence   — boolean; whether the season repeats every year.
     :id                  — :jsonld/id for cross-references.

   Examples:
     (season \"Summer\")
     (season \"Summer\" :months [5 6 7 8 9 10])
     (season \"Summer\" :months [\"--05\" \"--06\" \"--07\"])     ; auto-coerced
     (season \"Summer\" :id \"x:summer\" :yearly-recurrence true)
     (season \"Summer\" {:months [5 6 7 8 9 10]})              ; trailing-map kwargs

   Throws ex-info with a Malli explanation if the assembled entity doesn't
   validate against urpx.schema/Season."
  [name & {:keys [months yearly-recurrence id]}]
  (let [raw (cond-> {:jsonld/type "urpx:Season"
                     :urpx/seasonName name}
              months                    (assoc :urpx/month months)
              (some? yearly-recurrence) (assoc :urpx/yearlyRecurrence yearly-recurrence)
              id                        (assoc :jsonld/id id))
        coerced (coerce/coerce schema/Season raw)]
    (when-let [err (coerce/explain schema/Season coerced)]
      (throw (ex-info "DSL season builder produced an invalid urpx:Season"
                      {:input raw :explanation err})))
    coerced))

<!--
SPDX-FileCopyrightText: 2026 Clark Communications Corporation
SPDX-License-Identifier: MIT
-->

# Writing URPX JSON-LD

The library also supports the inverse direction: emit URPX JSON-LD from a coerced Clojure entity. Useful for round-trip validation, programmatic generation of URPX documents, and authoring fixtures in idiomatic Clojure.

```clojure
(require '[urpx.emit :as emit])

;; Round-trip a parsed rate plan back to JSON-LD
(spit "out.jsonld" (emit/write-rate-plan plan))

;; Standalone modifier documents
(emit/write-rate-plan-modifier modifier)

;; URPXDocument-wrapped documents. urpx:URPXDocument is the v0.2.0 wrapper
;; class, renamed in v0.3.0, so a document using it declares a v0.2.x ontology.
(emit/write-urpx-document
  {:jsonld/type "urpx:URPXDocument"
   :urpx/targetURPXVersion "https://github.com/urpx-org/urpx/raw/v0.2.0/ontology/urpx-ontology.ttl"
   :urpx/hasRatePlan [plan]})

;; urpx:URPXPackage, the v0.3.0 rename of that wrapper
(emit/write-urpx-package
  {:jsonld/type "urpx:URPXPackage"
   :urpx/targetURPXVersion "https://github.com/urpx-org/urpx/raw/v0.5.1/ontology/urpx-ontology.ttl"
   :urpx/packages [plan]})
```

Each function takes a coerced (or already-typed EDN) entity, runs the schema's `:encode/urpx-jsonld` transformer to convert typed leaves (`BigDecimal`, `LocalDate`, `LocalDateTime`, `ZonedDateTime`, `Duration`, integer month numbers, …) back to JSON-LD string forms, projects namespaced-keyword keys to their CURIE / `@`-reserved JSON-LD forms, and serializes via `clojure.data.json/write-str`. A typed-literal object such as `{"@value": "2026-05-01T16:00:00Z", "@type": "xsd:dateTime"}` decodes like the plain string and emits as one, typed by `default-context`.

## Worked round-trip

```clojure
(require '[urpx.core :as core] '[urpx.coerce :as coerce] '[urpx.emit :as emit])
(import '[java.io StringReader])

;; load + coerce → typed entity
(def plan (-> "path/to/urpx-rate-plan.jsonld"
              core/load-rate-plan
              coerce/coerce-rate-plan))

;; emit → JSON-LD string → re-parse → re-coerce. {:context nil} emits only
;; the @context the plan already carries, so re-coerced gains no
;; :jsonld/context that plan lacks.
(def re-coerced (-> (emit/write-rate-plan plan {:context nil})
                    StringReader.
                    core/parse-rate-plan
                    coerce/coerce-rate-plan))

(= plan re-coerced)
;; => true (the round-trip is a fixed point)
```

## `@context` emission

By default, every write-* function emits a baseline `@context` with the canonical URPX prefix declarations (`urpx`, `schema`, `xsd`) plus typed-property declarations matching the shape vendored fixtures carry — output parses cleanly with strict JSON-LD processors out of the box. The full default context lives at [`urpx.emit/default-context`](../src/urpx/emit.clj).

```clojure
;; Default context (most use cases)
(emit/write-rate-plan plan)

;; Caller-supplied context (replaces the default verbatim)
(emit/write-rate-plan plan {:context {"urpx" "https://urpx.org/ns/ontology/"
                                      "ex"   "https://example.com/foo/"}})

;; Suppress @context entirely
(emit/write-rate-plan plan {:context nil})
```

A `@context` already present on the input entity (e.g. from a parsed document) is preserved untouched — the default never clobbers a context the consumer supplied.

## Round-trip fidelity

The emit path targets **logical / RDF-graph equivalence** on round-trip: `parse → coerce → emit → re-parse → re-coerce` yields a structurally equal Clojure entity. Byte-for-byte equality with the input is **not** a goal — key ordering, single-vs-vector property emission, and `@context` recreation may differ from the source document.

`urpx.emit-test` runs an authoritative round-trip suite over the PG&E `@graph` bundles in `urpx.emit-test/vendored-fixtures` (both the base RatePlan and the bundled vintage-PCIA modifier in each); all round-trip cleanly with plain `(= original re-coerced)`. The DSL test suite (`urpx.dsl-test`) extends this to a CPAU-E-2-shaped plan authored entirely via the DSL.

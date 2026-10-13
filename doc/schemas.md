<!--
SPDX-FileCopyrightText: 2026 Clark Communications Corporation
SPDX-License-Identifier: MIT
-->

# Schemas by type

Every entity schema is a var in `urpx.schema` and is also reachable by its `@type`:

```clojure
(require '[malli.core :as m] '[urpx.schema :as schema])

(schema/schema-for-type "urpx:Season")                    ; => schema/Season
(:urpx/Season schema/schemas)                             ; => schema/Season
(m/validate :urpx/Season season {:registry schema/registry})
```

A property the standard types with `sh:class` states that class as `:urpx.schema/range` in its entry properties, and `(schema/ranges schema/PriceDefinition)` returns them, `{:urpx/appliesInSeason "urpx:Season" …}`. A property whose shape is an `sh:or` of `sh:class` alternatives states the set, and a node of any member is in range: `urpx:basedOnPrice` takes `#{"urpx:Price" "urpx:PriceSummary"}`. Validating one entity cannot tell whether a reference points at a Season, since the target is elsewhere in the document. `urpx.index/mistyped-references` resolves every reference across a set of documents and reports each one, and each inline node, that is not an instance of its slot's class:

```clojure
(urpx.index/mistyped-references [plan modifier])
;; => [{:document 0 :in [...] :property :urpx/referencesLedger
;;      :range "urpx:Ledger" :id "ex:summer" :types ["urpx:Season"]}]
```

A reference to a node in none of the documents is not judged. `scripts/shacl-drift.sh` checks every range against the shipped `sh:class` or `sh:or`.

A property the schemas do not model carries no range, so references through it are not judged.

## Checking for drift against the standard

`scripts/shacl-drift.sh [path-to-urpx-checkout] [rev]` reports where these hand-written Malli schemas have fallen behind the shipped SHACL shapes: properties the standard requires that aren't modeled, cardinalities that disagree, enum members that moved. It reads the shapes out of a checkout you point it at, or `$URPX_DIR`, rather than vendoring them, and needs `rdflib` on `$PYTHON` (default `python3`); see [CONTRIBUTING](../CONTRIBUTING.md#configuration).

It is an auditor, not a generator. Nothing it produces is compiled or shipped. Deliberate divergence lives in [`dev/drift-baseline.edn`](../dev/drift-baseline.edn) with a written reason; a known bug does not, because silencing it there is how it stops being known. See [CONTRIBUTING](../CONTRIBUTING.md) for the finding taxonomy, and [SHACL and Malli](shacl-and-malli.md) for why the schemas are hand-written rather than generated from the shapes.

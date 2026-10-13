<!--
SPDX-FileCopyrightText: 2026 Clark Communications Corporation
SPDX-License-Identifier: MIT
-->

# SHACL and Malli: why this library audits instead of generating

URPX ships SHACL shapes. clj-urpx validates with hand-written [Malli](https://github.com/metosin/malli) schemas. Both describe shape, structure and constraints, so the obvious question is why the schemas are not simply generated from the shapes.

They could be. A generator was built and it worked. This note records why it is not what ships and what is shipped instead, so the question does not have to be re-derived from scratch.

## The shapes are an unusually compilable subset of SHACL

SHACL is a large language. URPX uses a small, regular corner of it.

In the v0.5.1 shapes, every `sh:path` is a plain predicate IRI, never a blank node holding a path expression. `sh:inversePath`, `sh:alternativePath`, `sh:zeroOrMorePath`, `sh:oneOrMorePath` and `sh:zeroOrOnePath` do not occur, and neither do `sh:sparql`, `sh:qualifiedValueShape`, `sh:not` or `sh:and`. The features that make a general SHACL compiler a research project are simply not used.

What is used is a short list, each with a clean Malli counterpart:

| Construct | Malli counterpart |
|---|---|
| `sh:minCount` / `sh:maxCount` | `{:optional true}`, scalar vs `[:vector …]` |
| `sh:datatype` | the typed leaves (`Date`, `Decimal`, `MonthNumber`, …) |
| `sh:class` | `[:or Node ExclusiveRef]` |
| `sh:node` | a schema var reference |
| `sh:closed` + `sh:ignoredProperties` | `{:closed true}` plus allowed extras |
| `sh:nodeKind sh:IRI` | `Ref` |
| `sh:in` | `[:enum …]` |
| `sh:or` / `sh:xone` | `:or` / `:multi` |
| `pattern`, `minInclusive`, `hasValue`, `maxInclusive` | `:re`, `:>=`, `:=`, `:<=` |

Every `sh:closed` shape carries `sh:targetClass`, and the closed shapes are exactly the shapes carrying `sh:ignoredProperties`.

## The hard part is not SHACL, it is that SHACL describes a graph

SHACL constrains an RDF **graph**. Malli constrains a Clojure **value**, which here is a parsed JSON-LD document **tree**. The gap is not expressiveness; it is that the two are describing different objects.

In RDF, writing a node inline and referring to it by `@id` produce the *identical graph*. There is no difference for SHACL to describe. In a parsed document they are completely different Clojure values. So a generator cannot learn from the shapes which form a document uses — from where SHACL stands, the question does not exist.

That gap has bitten this library in both directions from a single `sh:class` line:

- `[:vector ApplicabilityRule]` rejected valid bare references to rules defined elsewhere in the document.
- `[:or ApplicabilityRule Ref]` then accepted a rule with a month of 13 and arbitrary payload, because `Ref` is an open map and the permissive arm matched first.
- Only `[:or ApplicabilityRule ExclusiveRef]`, where the reference arm is closed to `@id` alone, is right — and **nothing in the SHACL says so**. It is a fact about JSON-LD serialization, not about the shape.

A second gap: SHACL validates, it does not convert. `urpx.schema` also *decodes* (`"--01"` → `1`, `"0.23"` → `BigDecimal`) and encodes back so `urpx.emit` can round-trip. SHACL says `xsd:gMonth` and stops.

## What the generator experiment found

A generator was built and run. It works at the mechanical level: Python over `rdflib`, emitting Malli that compiles for the full registry, every var building a validator.

It is not what ships, for three reasons.

**Its central rule was silently vacuous.** The generated schemas reported `valid=true` on the vendored fixtures while an embedded version snapshot was independently invalid. Neither compilation nor the fixture suite caught it, because a schema that checks nothing passes everything. A diff a person reads cannot fail that way.

**It guesses where it should refuse.** Two defects, both structural rather than incidental:

- `xsd:gYear` had no typed leaf in `urpx.schema` then, so the generator emitted `:int` as a guess, and the guess cascaded into rejecting valid filings.
- `sh:path rdf:first` is the RDF idiom for "non-empty `rdf:List`". Transliterated into a map key, it produces a schema **no compact JSON-LD document can ever satisfy**.

**A generator bug is systemic where a hand-written one is local.** One bad rule is wrong at every slot it generates. A hand-written mistake more often announces itself, because you write the test while thinking about the case.

## The differential oracle, and why its verdict was time-bound

The measurement that was supposed to settle it: across a corpus of real filings, does generated Malli agree with pyshacl (the shipped SHACL, the actual standard) more closely than the hand-written schemas do?

Getting a trustworthy answer first required noticing that the obvious run is a lie. pyshacl reported `Conforms: True` over the corpus because the filings bound `urpx:` to one namespace and the shapes targeted another, so **zero focus nodes matched** and every shape validated nothing. Rebasing the namespace in copies of the filings made the shapes match. Any SHACL run should be treated as failed until non-vacuity is proved.

Against v0.2.x filings measured against v0.5.1 shapes, no filing conformed, and the generated schemas agreed with pyshacl on every filing and the hand-written schemas on none.

**That result inverted once the filings migrated to v0.5.1, and the reason matters more than the number.** The hand-written schemas then agreed with pyshacl more often than the frozen generated schemas did. The generated schema became the *worse* of the two, partly because it is frozen, and partly because the `gYear` and `rdf:first` defects make it reject valid documents.

So the honest reading of the original agreement is narrower than it looked. Both pyshacl and the generated schema rejected everything, for overlapping but not identical reasons, and agreeing to reject is cheap. The violations also reduced to a handful of distinct `(constraint-component, path)` pairs: one version-gap event counted many times, not many findings.

## What ships instead

A drift detector, `scripts/shacl-drift.sh`. It reports where the hand-written schemas have fallen behind the shapes and refuses to guess where the shapes cannot be honestly translated.

- `dev/shacl_extract.py` reads Turtle and emits node-shape facts. It knows nothing about Malli.
- `dev/schema_facts.clj` introspects the **live** schemas through `malli.core`, rather than parsing `schema.clj` as text, which would drift from them silently.
- `dev/drift.clj` compares the two.

Findings rank by cost. `rejects-valid` and `arity` mean the library refuses documents the standard allows; `under-validates` and `missing-required` mean the reverse. Four categories are informational and never fail a run, so **"no new drift" does not mean "no divergence from upstream"** — it means nothing newly diverged outside `dev/drift-baseline.edn`.

Shapes are read from a checkout passed as an argument rather than vendored, so no URPX-derived file is committed here. Exit codes are tri-state — `0` clean, `1` new drift, `2` refused — because a run that compared nothing must not read as success. That is the same rule the vacuous-pyshacl lesson teaches.

### What it found on its first run

A `rejects-valid` defect nobody had noticed: `Price.referencesPriceDefinition` was required while `PriceShape` declares `sh:minCount 0`, so conformant documents omitting it were refused — and `PriceSetAlternative` modeled the same property optional, so the two schemas disagreed with each other. It also found cardinality mismatches and under-validations.

## Decisions this rests on

| Decision | Consequence |
|---|---|
| Keep hand-written Malli | No SHACL engine, no runtime RDF dependency. Vocabulary changes are concrete schema edits. |
| Validate the compact document tree | `urpx.emit` round-trips to authored shape. Flattening would collapse the inline-vs-reference ambiguity and make generation easier, at the cost of the authoring contract. |
| Report `sh:closed`, do not enforce it | A closed shape rejects every property it does not list, so enforcing it would reject the v0.2.x spellings this library accepts. `{:closed true}` on `ExclusiveRef` and `RdfList` tells a reference or a list object from a node; it does not mirror `sh:closed`. |
| Accept both vocabularies | Filings migrate utility by utility and carry both at once. Reference arms are `[:or X ExclusiveRef]`, never `[:or X Ref]`, because the open form is the vacuous one. |

## Where the hand-written schemas stand

A person applying a mechanical rule by hand produces inconsistencies a generator would not: `[:vector …]` map entries have been found missing the `single-or-vec->vec` decoder their neighbors carry. That is the standing argument *for* codegen, and it is why the detector exists rather than nothing: the answer to mechanical drift is a mechanical check, but it does not follow that the check should also write the code.

## Reproducing this

```bash
python3 -m pip install rdflib                 # or set PYTHON to a python3 that has it
scripts/shacl-drift.sh /path/to/urpx-checkout [rev]
```

Deliberate divergence lives in `dev/drift-baseline.edn` with a written reason. A known bug does not: silencing one there is how it stops being known.

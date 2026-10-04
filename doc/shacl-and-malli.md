<!--
SPDX-FileCopyrightText: 2026 Clark Communications Corporation
SPDX-License-Identifier: MIT
-->

# SHACL and Malli: why this library audits instead of generating

URPX ships SHACL shapes. clj-urpx validates with hand-written [Malli](https://github.com/metosin/malli) schemas. Both describe shape, structure and constraints, so the obvious question is why the schemas are not simply generated from the shapes.

They could be. A generator was built and it worked. This note records why it is not what ships, what is shipped instead, and the measurements behind both, so the question does not have to be re-derived from scratch.

Every figure here was measured against `urpx-org/urpx` at `95eb8e22` (the v0.5.1-candidate tip) and clj-urpx at the commit that introduced this file. Where a number is time-bound, it says so.

## The shapes are an unusually compilable subset of SHACL

SHACL is a large language. URPX uses a small, regular corner of it.

| | |
|---|---|
| `sh:NodeShape` declarations | 150 |
| …of which carry `sh:targetClass` | 144 |
| `sh:property` statements | 906 |
| `sh:path` statements | 906 |

Every one of those 906 paths is a plain predicate: all 906 objects are IRIs, none is a blank node holding a path expression. There are **zero** occurrences of `sh:inversePath`, `sh:alternativePath`, `sh:zeroOrMorePath`, `sh:oneOrMorePath` and `sh:zeroOrOnePath`, and **zero** of `sh:sparql`, `sh:qualifiedValueShape`, `sh:not` and `sh:and`. The features that make a general SHACL compiler a research project are simply not used.

What is used is a short list, each with a clean Malli counterpart:

| Construct | Count | Malli counterpart |
|---|---|---|
| `sh:minCount` / `sh:maxCount` | 894 / 698 | `{:optional true}`, scalar vs `[:vector …]` |
| `sh:datatype` | 452 | the typed leaves (`Date`, `Decimal`, `MonthNumber`, …) |
| `sh:class` | 296 | `[:or Node ExclusiveRef]` |
| `sh:node` | 116 | a schema var reference |
| `sh:closed` + `sh:ignoredProperties` | 79 + 79 | `{:closed true}` plus allowed extras |
| `sh:nodeKind sh:IRI` | 71 | `Ref` |
| `sh:in` | 68 | `[:enum …]` |
| `sh:or` / `sh:xone` | 19 / 6 | `:or` / `:multi` |
| `pattern`, `minInclusive`, `hasValue`, `maxInclusive` | 7, 7, 3, 2 | `:re`, `:>=`, `:=`, `:<=` |

The 79 `sh:closed` shapes are all among the 144 targeted ones, and the set of closed shapes is exactly the set carrying `sh:ignoredProperties`.

> A caution for anyone remeasuring: `grep -c 'sh:closed'` returns 82, because three `rdfs:comment` prose strings contain the text. The statement count is 79. An earlier draft of this note published 82, and the denominator 150 rather than 144; both were wrong.

## The hard part is not SHACL, it is that SHACL describes a graph

SHACL constrains an RDF **graph**. Malli constrains a Clojure **value**, which here is a parsed JSON-LD document **tree**. The gap is not expressiveness; it is that the two are describing different objects.

In RDF, writing a node inline and referring to it by `@id` produce the *identical graph*. There is no difference for SHACL to describe. In a parsed document they are completely different Clojure values. So a generator cannot learn from the shapes which form a document uses — from where SHACL stands, the question does not exist.

That gap has bitten this library in both directions from a single `sh:class` line:

- `[:vector ApplicabilityRule]` rejected 15,295 valid bare references in the production corpus.
- `[:or ApplicabilityRule Ref]` then accepted a rule with a month of 13 and arbitrary payload, because `Ref` is an open map and the permissive arm matched first.
- Only `[:or ApplicabilityRule ExclusiveRef]`, where the reference arm is closed to `@id` alone, is right — and **nothing in the SHACL says so**. It is a fact about JSON-LD serialization, not about the shape.

A second gap: SHACL validates, it does not convert. `urpx.schema` also *decodes* (`"--01"` → `1`, `"0.23"` → `BigDecimal`) and encodes back so `urpx.emit` can round-trip. SHACL says `xsd:gMonth` and stops.

## What the generator experiment found

A generator was built and run. It works at the mechanical level: 501 lines of Python over `rdflib`, emitting Malli that compiles — 31 public vars for the ten-shape spike, 144 for the full registry, every one building a validator.

It is not what ships, for three reasons.

**Its central rule was silently vacuous.** The generated schemas reported `valid=true` on all four vendored fixtures while an embedded version snapshot was independently invalid with three violations. Neither compilation nor the fixture suite caught it, because a schema that checks nothing passes everything. A diff a person reads cannot fail that way.

**It guesses where it should refuse.** Two defects, both structural rather than incidental:

- `xsd:gYear` has no typed leaf in `urpx.schema`, so the generator emitted `:int` as a guess. The failure cascaded through three schemas across five filings.
- `sh:path rdf:first` is the RDF idiom for "non-empty `rdf:List`". Transliterated into a map key, it produces a schema **no compact JSON-LD document can ever satisfy**.

**A generator bug is systemic where a hand-written one is local.** One bad rule is 296 wrong slots. A hand-written mistake more often announces itself, because you write the test while thinking about the case.

## The differential oracle, and why its verdict was time-bound

The measurement that was supposed to settle it: across the 73-filing production corpus, does generated Malli agree with pyshacl — the shipped SHACL, the actual standard — more closely than the hand-written schemas do?

Getting a trustworthy answer first required noticing that the obvious run is a lie. pyshacl reported `Conforms: True` over the corpus because the filings bound `urpx:` to one namespace and the shapes targeted another, so **zero focus nodes matched** and every shape validated nothing. Rebasing the namespace in copies of the filings took focus nodes from 0 to 22,638. Any SHACL run should be treated as failed until non-vacuity is proved.

Against the corpus *as it stood then* — v0.2.x filings measured against v0.5.1 shapes — the result was 557 violations, 0 of 73 conforming, and:

| | generated | hand-written |
|---|---|---|
| agreed with pyshacl | 73/73 | 0/73 |

**That result has since inverted, and the reason matters more than the number.** The corpus has migrated to v0.5.1. Re-running the same artifacts today: pyshacl conforms on 39 of 73, the hand-written schemas accept all 73 and so agree on 39, and the frozen generated schemas accept 0 and so agree on 34. The generated schema is now the *worse* of the two — partly because it is frozen, and partly because the `gYear` and `rdf:first` defects make it reject valid documents.

So the honest reading of the original 73/73 is narrower than it looked. Both pyshacl and the generated schema rejected everything, for overlapping but not identical reasons, and agreeing to reject is cheap. The 557 violations also reduce to **eight** distinct `(constraint-component, path)` pairs — or six once the two `sh:class` pairs are set aside as artifacts of single-document validation — which is one version-gap event counted 557 times, not 557 findings.

## What ships instead

A drift detector, `scripts/shacl-drift.sh`. It reports where the hand-written schemas have fallen behind the shapes and refuses to guess where the shapes cannot be honestly translated.

- `dev/shacl_extract.py` reads Turtle and emits node-shape facts. It knows nothing about Malli.
- `dev/schema_facts.clj` introspects the **live** schemas through `malli.core`, rather than parsing `schema.clj` as text, which would drift from them silently.
- `dev/drift.clj` compares the two.

Findings rank by cost. `rejects-valid` and `arity` mean the library refuses documents the standard allows; `under-validates` and `missing-required` mean the reverse. Four categories are informational and never fail a run, so **"no new drift" does not mean "no divergence from upstream"** — it means nothing newly diverged outside `dev/drift-baseline.edn`.

Shapes are read from a checkout passed as an argument rather than vendored, so no URPX-derived file is committed here. Exit codes are tri-state — `0` clean, `1` new drift, `2` refused — because a run that compared nothing must not read as success. That is the same rule the vacuous-pyshacl lesson teaches.

### What it found on its first run

A `rejects-valid` defect nobody had noticed: `Price.referencesPriceDefinition` was required while `PriceShape` declares `sh:minCount 0`, so conformant documents omitting it were refused — and `PriceSetAlternative` modeled the same property optional, so the two schemas disagreed with each other. Plus thirteen cardinality mismatches and four under-validations.

## Decisions this rests on

| Decision | Consequence |
|---|---|
| Keep hand-written Malli | No SHACL engine, no runtime RDF dependency. Vocabulary changes are concrete schema edits. |
| Validate the compact document tree | `urpx.emit` round-trips to authored shape. Flattening would collapse the inline-vs-reference ambiguity and make generation easier, at the cost of the authoring contract. |
| Report `sh:closed`, do not enforce it | 79 of 144 shapes are closed upstream; enforcing would reject every filing in the corpus. Exactly one map here is `{:closed true}` — `ExclusiveRef`, and its closedness is reference-vs-node discrimination, not `sh:closed` mirroring. |
| Accept both vocabularies | The corpus migrated utility by utility and carried both at once. 16 `[:or X ExclusiveRef]` sites exist; there are zero `[:or X Ref]` sites, because the open form is the vacuous one. |

## Where the hand-written schemas stand

55 public schemas carry a `:jsonld/type` entry; 54 pin a literal `@type` and 52 of those correspond to a `sh:targetClass`. 83 of the 95 `[:vector …]` map entries carry the `single-or-vec->vec` decoder; twelve do not, which is the kind of inconsistency a person applying a mechanical rule produces and a generator would not.

That last number is the standing argument *for* codegen, and it is why the detector exists rather than nothing: the answer to mechanical drift is a mechanical check, but it does not follow that the check should also write the code.

## Reproducing this

```bash
python3 -m pip install rdflib                 # or set PYTHON to a python3 that has it
scripts/shacl-drift.sh /path/to/urpx-checkout [rev]
```

Deliberate divergence lives in `dev/drift-baseline.edn` with a written reason. A known bug does not: silencing one there is how it stops being known.

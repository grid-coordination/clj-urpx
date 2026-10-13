<!--
SPDX-FileCopyrightText: 2026 Clark Communications Corporation
SPDX-License-Identifier: MIT
-->

# Enumerating scenario cost models

A **scenario cost model** is one `urpx:RatePlanVersion`, plus the compatible `urpx:RatePlanModifierVersion`s, plus one `urpx:ProfileAlternative` for each dimension any of them leaves open. It is fully determined: given an instant inside its window it resolves to prices with no further input. If a choice is still outstanding it is not one model but several, and each of those is a model in its own right.

Each model is the composition a `urpx:ScenarioCostModel` prices.

`urpx.scenario-cost-model` is **optional by construction**. It requires `urpx.price`; `urpx.price` does not require it. A consumer that already knows how to assemble a composition uses `urpx.price` directly and never loads this namespace.

```clojure
(require '[urpx.scenario-cost-model :as scm])

(def docs [schedule-d care fera])

(scm/enumerate docs {:at now})
;; => a sequence of model maps, each fully determined

(scm/resolve-at (first (scm/enumerate docs)) now)
;; => what urpx.price/resolve-prices-with-modifiers returns
```

`resolve-at` binds each document to its own version and passes the model's selections through as `:profile-alternatives`, so an opt cannot reach a document it does not belong to. Those two keys are **refused** rather than merged if a caller passes them: a caller who wants a different version or a different selection wants a different model.

Computing the set at runtime starts with walking the corpus, which is the caller's job: the corpus's own walker, or its `corpus.json`. Load what the walk finds with [`urpx.load/documents`](#loading-documents-and-who-walks-the-corpus), which takes any `clojure.java.io/reader` source including classpath URLs, and pass the documents to `scm/enumerate`; `scm/node` makes each model a `urpx:ScenarioCostModel` node, and `scm/rate-plans-iri` and `scm/rate-plans-name` give it the `@id` and `urpx:name` urpx-rate-plans uses.

## Preparing documents once

`plan-versions`, `applicable-modifiers`, `enumerate`, `enumerate-pairs` and `from-node` each read every document on every call. `scm/prepare` does that reading once, and each of them takes its value in place of the documents:

```clojure
(def prepared (scm/prepare docs))

(scm/enumerate prepared {:at now})
(mapv #(scm/from-node prepared % {:context ctx}) nodes)
```

`:repeated-ids` and `:package-zones` decide what is read, so `prepare` takes them and they are fixed there. A call may restate either with the prepared value; another value refuses `:urpx.scenario-cost-model/prepared-option-conflict`. Every other option, `:at`, `:combine`, `:compatibility` and `:context` among them, belongs to the call.

The prepared value is the caller's to keep, and so is the policy for keeping it. A caller that rereads its corpus can cache `prepare` keyed on a cheap identity of the corpus, such as a commit SHA, a tag or the files' modification times. The example takes `org.clojure/core.memoize` as the caller's own dependency:

```clojure
(require '[clojure.core.memoize :as memo])

(def prepared-at
  (memo/lru (fn [commit-sha]
              (scm/prepare (load/documents (corpus-files commit-sha))))
            :lru/threshold 4))
```

## A model has no verdict of its own

`of-model` returns every part's report, a frequency map of the verdicts, and conjunction predicates. It returns **no rolled-up `completeness`**, and the absence is the design rather than an omission — a test pins the exact key set so that adding one fails.

Two reasons, the second decisive. No ordering is stated among the six completeness members, and that absence is meaningful: the same ontology uses `skos:broader` where it *does* mean a hierarchy. More decisively, every definition is relative to "the claimed scope" of its own snapshot, and no snapshot states one, so composing would require knowing each part's scope and that their union equals the model's. Neither is known. Composition is undefined at the *domain* level, not merely unranked, and would stay so even if URPX later imposed an order.

A minimum would also be positively wrong in a case the vocabulary allows: a modifier claiming `urpx:noCoverage` contributes nothing, so a `complete` base with a `noCoverage` modifier is not a `noCoverage` model.

Every derived boolean is named for the **parts** — `every-part-complete?`, never `complete?`. A conjunction of per-part facts needs no ordering; a verdict does. Note `every-part-complete?` still does not mean the model is complete: each claim covers its own snapshot's required elements, and nothing claims the elements a composition needs and neither part carries.

What a caller does instead is decide its own publication policy over `completeness-counts` — and for that, `descriptions` is usually the thing to read rather than the verdict. A completeness result says *that* something is missing; the linked Evaluation says *what*, specifically enough to act on. One filing's prose names the five customer groups whose exemptions it does not represent and states plainly that a consumer applying it to any of them "will charge components the tariff exempts".

## Does a verdict cover the window you are publishing?

`coverage-interval` returns the window as `{:tick/beginning <ZonedDateTime> :tick/end <ZonedDateTime>}` — the same shape *and the same types* `price-schedule` and `price-segments` emit, per the rule in [Time and timezones](pricing.md#time-and-timezones) that the library uses `ZonedDateTime` end-to-end at output boundaries. `verdict-applies?` asks whether that window contains an interval, `[beginning, end)` with start inclusive and end exclusive, matching `urpx.price/version-covers?` exactly. URPX states no convention for a `TemporalScope`'s bounds, and two readings of a window inside one library would be worse than either.

**The zone is required, not defaulted.** A `TemporalScope`'s bounds are usually wall-clock readings with no offset, so there is no honest default and any default would be a zone this library picked. A rate plan usually declares one as `urpx:timezoneIdentifier`, which is what `urpx.schedule` resolves for the same purpose; pass that. Refuses `:urpx.coverage/zone-required` otherwise.

**These are tick intervals in fact, not only in shape.** `tick.alpha.interval`'s functions read any map carrying those two keys, so Allen's relations work on the value directly and `ti/contains?` agrees with `verdict-applies?` case for case:

```clojure
(ti/relation {:tick/beginning jan-15 :tick/end feb-1} window) ; => :during
(ti/relation {:tick/beginning dec-1  :tick/end feb-1} window) ; => :overlaps
```

A caller wanting the richer relation should reach for tick rather than for another function here. This library requires tick nowhere.

So a segment from `segments-for` and a coverage window compare with no conversion at the call site:

```clojure
(ti/relation segment (cov/coverage-interval report zone)) ; => :during
```

**`verdict-applies?` refuses when a claim states no coverage scope.** No shape requires one, so `:urpx.coverage/coverage-scope-absent` is what a price server should expect to meet first. That is not a bug: returning `false` would claim the verdict does not cover the window, which no document said, and `true` would be worse. A caller that publishes anyway is choosing its own policy, and catching a named reason is how it says so.

## What it refuses

Enumeration answers a question about a whole corpus, so a corpus that does not determine an answer gets a refusal rather than one of the answers it might have meant. Each carries a named `:reason` in `ex-data`.

| `:reason` | When |
|---|---|
| `:uncoerced-document` | a document still carries date-shaped strings and no `java.time` values. Reading one does not fail loudly on its own: it answers a different question |
| `:urpx.scenario-cost-model/conflicting-version-id` | one version `@id` names two snapshots that differ. An `@id` identifies a thing, so this is a contradiction, and every offending `@id` is named at once |
| `:urpx.scenario-cost-model/ambiguous-version` | two distinct snapshots of one identity share the latest effective instant, the same input `urpx.price` refuses with `:ambiguous-version` |
| `:urpx.scenario-cost-model/conflicting-timezone` | the nodes a version belongs to (the version, its plan or modifier, the document it came from, a `urpx:URPXPackage` naming any of them) declare different `urpx:timezoneIdentifier` values. One value, or several that agree, is the zone the model resolves in. `:package-zones` decides what a package's value counts for |
| `:urpx.scenario-cost-model/shared-alternative` | one `urpx:ProfileAlternative` is listed by two containers across one composition's versions. A selection names the alternative alone, so its container must follow from it. Every offending alternative is named at once |

Publishing one `@id` twice with byte-identical content is *not* refused: that is one version appearing in two filings, and it is deduplicated.

One further refusal is about the caller rather than the corpus. `resolve-at`, `segments-for` and `schedule-for` all reject `:version` and `:profile-alternatives` in their `opts` with `:reason :urpx.scenario-cost-model/opt-determined-by-model`, because the model determines both. Letting the caller's value win would restore the defect these functions exist to prevent; letting the model win silently would ignore an opt the caller meant. Every other opt is merged and passes through.

## Horizons: prices over a window

`resolve-at` answers one instant. A price server publishes a window: the interval containing now and some number of them forward. Two functions take a model and do that, binding each document to its own version the same way `resolve-at` does.

```clojure
(scm/segments-for  model start end)   ; exact boundaries, from the documents
(scm/schedule-for  model start end)   ; a stepped grid
```

`start` and `end` are `ZonedDateTime`s, as `resolve-at` takes, in any zone: UTC, a fixed offset, or a region. Their instants bound the window, and segments are computed and returned in the plan's zone, the zone `resolve-at` resolves in. A window from local midnight to local midnight is 23 or 25 hours across a daylight transition, and the segments cover exactly that.

**Prefer `segments-for`.** Its boundaries are read out of the documents rather than discovered by sampling, so a segment ends where the tariff actually changes. A stepped walk can only find a boundary it lands on: over one July day of PG&E E-TOU-C with a six-hour step, `segments-for` returns boundaries at 00:00, 16:00 and 21:00, while `schedule-for` returns 00:00 and 18:00, inventing a boundary that is not a transition and missing both that are. For a consumer handing intervals to an optimizer that is the difference between a schedule that is right and one that is right only because its step happened to be fine enough.

In both, a resolution that throws inside the window becomes an interval carrying `:urpx.interval/refused` rather than aborting the horizon, and equal refusals merge. A publisher can emit the part of a window it can price and say plainly what it cannot.

## Is an identity in force at an instant?

`identity-status-at` says why a plan or modifier identity has or lacks a price at an instant. `documents` and `opts` are as `prepare` takes them, or its value.

```clojure
(scm/identity-status-at docs "sce:care" t) ; t in February 2026
;; => {:status :not-modeled
;;     :after  "sce:care-2025-11-15"
;;     :before "sce:care-2026-03-20"
;;     :from   <ZonedDateTime 2026-01-01T00:00-08:00[America/Los_Angeles]>
;;     :until  <ZonedDateTime 2026-03-20T00:00-07:00[America/Los_Angeles]>}
```

| `:status` | Means | Other keys |
|---|---|---|
| `:in-force` | a version is in force, the one `enumerate` takes with `:at` | `:version`, `:from`, `:until` (nil when nothing ends it) |
| `:not-in-effect` | the instant is outside the identity's lifetime, from `urpx:initialEffectiveDate` to its expiration | `:from`, `:until` |
| `:not-modeled` | inside the lifetime with no version in force: a gap, or before the first version (`:after` nil), or after the last (`:before` nil) | `:after`, `:before`, `:from`, `:until` |

Bounds are `ZonedDateTime`s and periods are half-open. A modifier not modeled at `t` is absent from what `applicable-modifiers` returns with `:at t`, so a client checks each identity it composes.

## What may apply, and what does apply

The library answers the first; only the caller can answer the second. `urpx:applicableToRatePlan` states which plans a modifier may apply to. Which of them a given **customer holds** is not in any document, because URPX places customer data outside its scope (`urpx:AccountServiceAgreement` carries the note "Service account and agreement data lives in customer data, not URPX").

So the discovery functions are queryable on their own, without enumerating anything:

```clojure
(scm/plan-versions docs)                    ; every version snapshot, or {:at t} for current ones
(scm/applicable-modifiers docs plan-version) ; what may apply to this plan version
(scm/offered-by modifier-versions)           ; group by urpx:offeredBy, for a broader search
```

Nothing in URPX expresses exclusivity or combinability **between** modifiers, so how many a customer may hold at once is a caller policy, passed as `:combine`:

| `:combine` policy | Yields |
|--------|--------|
| `one-modifier-at-most` (default) | the base version alone, or with exactly one modifier |
| `every-subset` | every subset, which is complete but pairs programs no document says can be paired |

**Which modifiers a version is compatible with is a second policy**, `:compatibility`, for the same reason: URPX does not say whether naming a `urpx:applicableToRatePlanVersion` *narrows* `urpx:applicableToRatePlan` or *adds* to it. The property is a plain `owl:ObjectProperty`, `owl:FunctionalProperty` appears zero times in the ontology, and `sh:closed` closes the property set rather than the value set.

| `:compatibility` policy | Reads a named version as |
|--------|--------|
| `named-versions-add` (default) | an addition to the plan list, never a removal. Sound under RDF merge. Known cost: the property can then only widen, and only to a version whose plan is not already named |
| `named-versions-narrow` | those versions and no others, overriding the plan list for every plan the modifier names. Fits the ontology's "which **specific** versions", and is the only shipped reading under which the property can express a restriction. Known cost: **non-monotonic**, so merging a document can *remove* compatibility |

They differ only when a modifier names some version, and only about a base version it does not name whose plan it names. A modifier naming plans P and Q and one version of P applies to Q's versions under `named-versions-add` and to none of them under `named-versions-narrow`. A third reading, where naming a version of P restricts P alone, is not shipped. URPX Decision #090 introduced the property.

**What one `@id` naming several different snapshots means is a third policy**, `:repeated-ids`, because URPX does not say and a client reading one filing wants a different answer from a client merging a corpus.

| `:repeated-ids` policy | Does |
|--------|--------|
| `repeated-ids-refuse` (default) | refuses `:urpx.scenario-cost-model/conflicting-version-id`, naming every offending `@id` at once. An `@id` identifies a thing, so two different things under one is a contradiction rather than a choice |
| `repeated-ids-union` | merges them property by property, the RDF reading of several documents describing one IRI |

Byte-identical repeats are never a conflict under either: one version published in two filings is one version, and it is deduplicated.

`repeated-ids-union` has two costs worth knowing. **RDF merges triples, not meanings**: merging a copy that states `applicableToRatePlanVersion` with one that omits it yields a node that *has* the property, which under `named-versions-narrow` restricts the modifier to exactly those versions — while the omitting copy meant no restriction at all. And it silently reconciles a version `@id` whose content is time-varying, which is a defect on any reading. Only vector-valued properties union; two differing scalars refuse `:urpx.scenario-cost-model/unmergeable-property`, since URPX gives many of those `sh:maxCount 1`.

**What a zone on a package counts for is a fourth policy**, `:package-zones`, taken by `enumerate`, `plan-versions` and `applicable-modifiers`. `urpx:timezoneIdentifier` is admitted on the version, its plan or modifier, and the `urpx:URPXPackage` containing it, and URPX gives no precedence among them. A package counts when it names the version, its identity or its source document by `urpx:packages`, `urpx:hasRatePlan` or `urpx:hasRatePlanModifier`.

| `:package-zones` | A package's zone is |
|--------|--------|
| `:agree` (default) | one more declaration: any disagreement with the version, its identity or its source document refuses `:conflicting-timezone` |
| `:fallback` | read only when none of those declares a zone |
| `:ignore` | not read |

Under every mode, two packages declaring different zones for one version refuse, as do a version and its identity. Any other value refuses `:urpx.scenario-cost-model/unknown-package-zones`. Zones are read only to place a version against `:at` and to zone a model's documents, so `plan-versions` and `applicable-modifiers` without `:at` read none.

## Comparing two enumerators

```clojure
(scm/enumerate-pairs documents opts)
;; => #{["sce:d-2026-03-20" #{}]
;;      ["sce:d-2026-03-20" #{"sce:care-2026-03-20"}] ...}
```

One row per composition, with `ProfileAlternative` choices **not** expanded — a coarser question than `enumerate` answers and deliberately a different one. A model with an open dimension is several models and one pair.

It exists so two independent enumerators can be compared on *what composes with what*, without disagreeing about whether a pending choice is one row or several. **Pin every policy explicitly** when the result is a gate's expected value: `:at`, `:combine`, `:compatibility`, `:repeated-ids` and `:package-zones` each change the answer, and matching defaults across two implementations is agreement by coincidence rather than by construction.

## Loading documents, and who walks the corpus

The library takes documents. **It never walks a corpus**, and that is a division of labour rather than an omission: a corpus repository is a one-off, there will never be a layout everyone adopts, so finding filings is client-specific while turning what you find into documents is not.

`urpx.load` names the second half:

```clojure
(require '[urpx.load :as load])

(load/documents sources)                    ; => [<coerced document> ...]
(into [] load/document-xf sources)          ; the same, composable
(eduction load/document-xf sources)         ; realises nothing until pulled
```

A **source** is anything `clojure.java.io/reader` coerces: a `File`, `URL`, `Reader`, `InputStream`, or path string. Where they come from is yours.

`document-xf` is three steps, and each is a way to be wrong by hand:

- **`mapcat`, not `map`, over `load-graph`.** One source can carry several top-level entities. Mapping yields a collection per source, and a collection survives a `tree-seq` walk, so getting this wrong returns an answer rather than an error.
- **`expand-document-entities`** descends a `urpx:URPXPackage` wrapper. Without it a wrapped filing contributes nothing. The wrapper follows its payloads as a **shell**: its own properties, with each embedded payload replaced by a `{:jsonld/id id}` reference. That is how a `urpx:timezoneIdentifier` declared only on the package reaches `urpx.scenario-cost-model`. An embedded payload with no `@id` refuses `:urpx.load/payload-without-id`.
- **`coerce-doc`** turns strings into `java.time` values. Without it `urpx:effectiveDateTime` is still a string: anything comparing dates throws from deep inside, and anything that does *not* compare them quietly answers a different question.

That list is not hypothetical. A consumer composing these by hand from an earlier version of this section used `mapv` for `mapcat` and omitted the coercion step, and got a plausible count instead of a refusal. The pipeline is named so that composition is unavailable to make rather than documented against.

`documents` and `scan` both yield the shells. A caller wanting only the payloads drops them:

```clojure
(remove #(#{"urpx:URPXPackage" "urpx:URPXDocument"} (:jsonld/type %)) (load/documents sources))
```

## What gets discarded, and how to notice

`coerce-doc` returns `nil` for an entity type this library does not model, so `document-xf` drops it **silently**, for example `urpx:PriorRatePlan`.

Nothing guarantees a discarded type is never a version. `scan` does the same work in one pass and reports the discards, so a caller can assert what it could not before:

```clojure
(let [{:urpx.load/keys [documents uncoercible]} (load/scan sources)]
  (assert (empty? (filter #(str/ends-with? (str (:jsonld/type %)) "Version")
                          uncoercible)))
  documents)
```

`scan` also reports `:urpx.load/unresolved-references`: each package payload reference whose entity no source states, as `{:jsonld/id id :package-id p :property k}`.

"This library does not model it" and "it does not matter" are different statements. Whether a discard matters is yours to decide, and it cannot be decided while the discard is invisible.

## Example walkers

`corpus/` holds worked examples of the first half: `corpus-json`, a walker for any corpus whose layout is declared in a `corpus.json` in the format of [urpx-rate-plans](https://github.com/grid-coordination/urpx-rate-plans), and `urpx.corpus.json`, which declares the layout of the URPX standard's own `test-cases/` in [urpx-org/urpx](https://github.com/urpx-org/urpx). None of it is library code: `corpus/` is not on the library's `:paths`, nothing in `src/` requires it, and none of it ships in the jar.

```bash
clojure -M:dev:corpus -m corpus-sweep <path-to-urpx> --layout urpx-upstream
clojure -M:dev:corpus -m corpus-sweep <path-to-corpus-or-git-url> [rev]
```

With no `--layout`, `corpus-sweep` reads the `corpus.json` at the corpus root, else globs `**/*.jsonld`. `--layout` takes a `corpus.json`, a directory holding one, or a namespace, which it requires from the classpath and calls `<ns>/sources`; a walker for another corpus can live with that corpus, and [corpus/README.md](../corpus/README.md) shows how to add one with `-Sdeps`.

A walker exists because a glob looks equivalent to a walk and is not. Over a URPX checkout older than upstream `ebde5871`, a naive `**/*.jsonld` **crashes**, because `dev-docs/samples` carried an instant written `2026-01-01T06:00:00Z` that coercion refuses, and the error names no file. Those samples moved to urpx-org/urpx-dev at `ebde5871`.

The upstream test cases are worth walking for a second reason: they exercise shapes a single publisher's filings may never use. Three top-level `RatePlanVersion`s rather than nested ones, two base versions claiming `urpx:complete`, a `TemporalScope` with `scopeKind urpx:coverage`, and a genuine `:ambiguous-version` tie.

`dev/corpus_sweep.clj` is a full worked example, and pins a corpus to an exact revision with [tools.gitlibs](https://github.com/clojure/tools.gitlibs) rather than to whatever a sibling checkout holds. `babashka/fs` and `tools.gitlibs` are `:dev` dependencies; the library itself does no filesystem traversal and fetches nothing.

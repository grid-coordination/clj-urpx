<!--
SPDX-FileCopyrightText: 2026 Clark Communications Corporation
SPDX-License-Identifier: MIT
-->

# Changelog

Notable changes to [`energy.grid-coordination/clj-urpx`](https://clojars.org/energy.grid-coordination/clj-urpx). The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); the project is pre-1.0, so a minor bump is the strongest compatibility signal available and is used for breaking changes.

Entries begin at 0.11.0. The ten releases before it carry no summaries in their release commits, and reconstructing them from the commit log would be guesswork rather than history; see `git log` and the `v0.1.0`–`v0.10.0` tags for what actually landed.

## [0.12.0] — 2026-09-20

Ten commits. URPX's production corpus finished migrating to v0.5.1 across all six of its per-utility waves during this window, so most of what follows is the library catching up with real migrated filings rather than with the specification on paper.

### Changed

Three changes tighten validation and can reject documents 0.11.0 accepted. All three were measured against the fully migrated 73-filing corpus first and reject nothing in it, but a consumer with other filings should read them.

- `urpx:changeReason` and `urpx:serviceRole` are now **required** on `RatePlanVersion` and `RatePlanModifierVersion`, matching `sh:minCount 1`, which has been their cardinality since URPX v0.2.1. All 84 version snapshots in the corpus carry both.
- Both version pointers now accept **0..N** as the shapes declare (`sh:minCount 0`, no `sh:maxCount`). With more than one nested snapshot and no explicit choice, `resolve-prices` **throws** rather than resolving against an arbitrary one. Which snapshot applies to an instant is a selection question the vocabulary does not settle, so the library refuses instead of guessing; pass `:version` in the opts to choose.
- Three further 0..N properties are accepted as one value or many — `MetricInput.hasMetricSpecification`, `PlanElements.hasHolidayCalendar`, `TimeBracket.appliesInSeason` — with the same refusal rather than an arbitrary pick.
- A bare `@id` reference to an `ApplicabilityRule` on a `PriceDefinition` is now dereferenced before month gating. Previously such a reference carried no `urpx:month`, read as permissive, and the gate silently stopped applying.

### Added

- `scripts/shacl-drift.sh` reports where the hand-written Malli schemas have fallen behind the shipped SHACL shapes. It is an auditor, not a generator, and emits nothing that is compiled or shipped. Findings rank by cost: `rejects-valid` and `arity` mean the library refuses documents the standard allows; `under-validates` and `missing-required` mean the reverse. Exit codes are tri-state so a run that compared nothing cannot read as success.
- [`doc/shacl-and-malli.md`](doc/shacl-and-malli.md) records why the schemas are hand-written rather than generated from the shapes, with the measurements behind that decision.
- A DSL builder for every entity-type schema. Eleven were missing, including all four coverage schemas added in 0.11.0. A test now pins the claim, which had quietly become false.

### Fixed

- **`Price.referencesPriceDefinition` was required** while `urpx-shacl:PriceShape` declares `sh:minCount 0`, so conformant documents omitting it were refused. `PriceSetAlternative` already modeled the same property optional, so the two schemas had been disagreeing with each other. Found by the drift detector on its first run, along with thirteen cardinality mismatches also fixed here.
- **Timezone inference was silently dead on v0.5.1 documents.** v0.5.1 moved the EIA utility id out of `urpx:eiaId` and into the `Organization` identity chain (`hasIdentity → hasIdentifier → {urpx:scheme urpx:eiaScheme, skos:notation}`). `infer-zone` read only the old key, found nothing, and fell back to matching a display name without saying so. It now reads either location.
- A test helper could not reach a `RatePlanModifier` embedded in a `urpx:URPXPackage`, because it filtered `load-graph` rather than using the descending loader.

### Documentation

- The DSL tutorial is now **executed** as a test: every Clojure block runs, in order, in one namespace. It had silently become a throw when two properties were made required.
- README code blocks are checked for symbol resolution, which catches a rename that leaves prose behind.

### Not included

Whether to keep accepting URPX v0.2.x or tighten to v0.5.1 exactly remains open. Four wave-gated requirements and one retired enum member stay loose because tightening them would reject every v0.2.x document. That is a contract decision rather than a conformance one, and it is deferred to the next consumer upgrade.

## [0.11.0] — 2026-09-20

Retargets the library to **URPX v0.5.1** (`urpx-org/urpx` at `95eb8e22`) while continuing to accept v0.2.x in full. The production corpus migrates utility by utility and carries both vocabularies at once, so dual acceptance is the design rather than transitional debt.

### Added

- `urpx:URPXPackage` as a sibling schema to `urpx:URPXDocument`, its v0.3.0 rename, including the `urpx:packages` containment pointer. Loading, coercion, DSL builders and emitters cover both.
- The v0.5.1 coverage machinery: `CoverageClaim`, `Evaluation`, `ModelExclusion`, `PriceDefinitionReference`, plus `forRatePlan` / `forRatePlanModifier` and `hasCoverageClaim` on the version snapshots.
- The publication split: `SourcePublication` (dual-accepting the v0.2.x `urpx:TariffPublication` `@type`) and `TariffFiling`.
- Typed identity: `RatePlanIdentifier`, `RatePlanModifierIdentifier`, `OrganizationIdentifier`.
- Enum membership enforcement on six reframe vocabularies, transcribed from the shipped `sh:in` lists.
- Back-pointer resolution: `urpx.index/versions-by-plan`, and `load-rate-plan-version` / `load-rate-plan-modifier-version`.
- `urpx.validate`, a single-file CLI checker (`clojure -M:validate <file.jsonld>`) with tri-state exits. It descends wrappers, so it cannot report success over a document whose inner entity was never reached.

### Changed

- `hasApplicabilityRule`'s reference arm is closed to `@id` alone. The open form accepted a malformed inline rule as though it were a reference.
- `@context` is accepted in all three JSON-LD encodings — object, IRI string, or an array of either. Only the object form was accepted before, so the standard's own v0.5.1 reference documents did not validate.
- `hasRatePlanVersion`, `hasRatePlanModifierVersion` and `effectiveDateTime` are optional, matching `sh:minCount 0`. `effectiveDateTime` was never required upstream at any version.
- `DataSourceReference` accepts the v0.5.1 flattening (`leafStart`, `leafEnd`, `tariffStatementType` inline) as well as the v0.2.x nested `TariffBookReference`.
- The version hop resolves through `forRatePlan` back-pointers as well as the nested pointer.

### Removed

- The unused `com.apicatalog/titanium-json-ld` dependency, which was also non-functional as declared (no `jakarta.json` implementation was supplied).

[0.12.0]: https://github.com/grid-coordination/clj-urpx/releases/tag/v0.12.0
[0.11.0]: https://github.com/grid-coordination/clj-urpx/releases/tag/v0.11.0

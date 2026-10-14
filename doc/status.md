<!--
SPDX-FileCopyrightText: 2026 Clark Communications Corporation
SPDX-License-Identifier: MIT
-->

# Status

Targets the **URPX v0.5.1** release. 0.54.0 is the last release that also accepts **v0.2.x** documents.

Release history is in [CHANGELOG.md](../CHANGELOG.md).

Test fixtures are frozen specimens of filings from [urpx-rate-plans](https://github.com/grid-coordination/urpx-rate-plans) (a production rate-plan corpus), each recording where its bytes came from in its own `.license` sidecar. See [`test/resources/urpx-fixtures/FIXTURES.md`](../test/resources/urpx-fixtures/FIXTURES.md) for the shape each specimen covers and how to add one.

Covered:

- Top-level document classes parse, coerce and validate against Malli schemas: `urpx:RatePlan`, `urpx:RatePlanModifier`, both version snapshots, and the `urpx:URPXPackage` wrapper. The loader descends a wrapper transparently, so consumers target inner entities by `@type` regardless of packaging shape (bare entity, `@graph` bundle at any nesting depth, or wrapper). `urpx.core/expand-document-entities` exposes that descent for consumers walking a document themselves
- **The version hop resolves either direction.** v0.5.1 un-nests the snapshot: a document may state the plan-to-version link downward (`urpx:hasRatePlanVersion`, nested), upward (`urpx:forRatePlan` on a top-level snapshot), or both. `urpx.index/versions-by-plan` indexes the back-pointer, and `resolve-prices` takes an explicit `:version`, falls back to the nested pointer, then to that index. Both pointers are 0..N upstream and are accepted as such; with more than one nested snapshot and no explicit choice, resolution throws rather than picking arbitrarily, because which snapshot applies to an instant is a selection question the vocabulary does not yet settle
- **v0.5.1 coverage machinery**: `urpx:CoverageClaim`, `urpx:Evaluation`, `urpx:ModelExclusion` and `urpx:PriceDefinitionReference`, with `urpx:forRatePlan` and `urpx:hasCoverageClaim` on the snapshots. Both are required, as `sh:minCount 1` upstream
- **Every vocabulary is enforced:** each slot whose shape states `sh:in` takes only those members, transcribed from the shipped lists. `urpx:observanceRule` also takes `urpx:mondayIfSundayObservance` (urpx-org/urpx#77).
- **The publication split**: `urpx:SourcePublication` and `urpx:TariffFiling`
- **Typed identity**: `RatePlanIdentifier`, `RatePlanModifierIdentifier` and `OrganizationIdentifier` on plans, modifiers and their snapshots, each requiring `skos:notation` and `urpx:scheme`. `urpx:IdentifierScheme` is an open class upstream, so an implementer's own scheme IRI is accepted
- **Customers and service points**: `CustomerProfile` with its prior rate plans, `ServicePointProfile` with its `DistributedEnergyResource`s, `AccountServiceAgreement`, `BillingCycle` on a `Ledger`, and `ScenarioCostResult`
- **Organizations**: `Organization` and its subclasses `UtilityOperator`, `EnergyServiceProvider`, `RegulatoryAuthority` and `SystemOperator`, with `OrganizationEvent`, `OrganizationTransition` and `LocationDataSet`, each naming itself through `urpx:hasIdentity` → `OrganizationIdentity` → `OrganizationName` and `OrganizationIdentifier`, and `urpx:offeredBy` on both version snapshots
- **`DataSourceReference` flattening**: v0.5.1 deleted `urpx:TariffBookReference` and `urpx:TariffStatementReference` and hoisted their fields inline
- `Price.hasApplicabilityRule` and `PriceSetAlternative.hasApplicabilityRule` take either an inline `urpx:ApplicabilityRule` or a bare `@id` reference to one defined elsewhere in the document, since RDF merges a reference with its definition. The reference arm is closed to `@id` alone, so a malformed inline node cannot pass as a reference
- `@context` in all three JSON-LD encodings: a definition object, an IRI string, or an array mixing the two, which is the form every shipped v0.5.1 example uses
- Timezone inference reads `urpx:offeredBy` on a snapshot
- Modifier filings in all three packaging shapes — bundled with a base RatePlan in `@graph`, carried by a wrapper, or standalone (no in-document target linkage; consumer-side composition expected) — load via `core/load-rate-plan-modifier`
- `urpx:ConditionExpression` tree (Comparison, Boolean and arithmetic Operation nodes with Object/Datatype/Custom operand leaves) parses and coerces; recursive operands are validated through a local Malli registry shared with the `ComparisonNode` and `BooleanOperationNode` schemas
- `urpx:hasPossibleValues` as RDF list objects; `dsl/rdf-list` writes one
- `BillingModel.hasPriceDefinition` traversal (the PriceDefinition lives at the BillingModel level with `referencesLedger` pointing back) for price resolution
- TOU period resolution: seasons, ontology day-types (`urpx:allDays`, `urpx:weekdayDays`, `urpx:weekendDays`, and `urpx:customDays` with `urpx:daysOfWeek`), `urpx:includeHolidays` / `urpx:includeNonHolidays` bracket gates, time-bracket containment with midnight wrap. Multiple `urpx:TOUSchedule`s per BillingModel are supported (one per MetricInput, e.g. separate energy + demand schedules)
- Non-TOU seasonal gating via `PriceDefinition.appliesInSeason → Season.month`, alongside the existing `hasApplicabilityRule.month` path — handles filings whose seasonal partition isn't carried by a TOUPeriod indirection (CPAU E-2 shape)
- `RatePlanVersion` surfaces `urpx:hasModifierOptions` and `urpx:hasModifierVersionOptions` pointers (Decisions #084 + #090) for cross-version modifier wiring
- Block-tier surfacing via the `urpx:ServiceTiers` container: every tier surfaces with its bounds and bound operators, selected among the containers and `urpx:ServiceBracket`s declaring that tier number by `urpx:appliesInSeason` and `urpx:referencesProfileAlternative`, or withheld under `:urpx.resolved/tier-bounds-unresolved`
- Active-price selection by latest `startDate ≤ query-date`, reading `urpx:startDateTime` in preference to `urpx:startDate`, drawing from flat top-level prices, `PriceSet`-scoped prices and alternatives. A Price with no derivable date carries no start constraint rather than being discarded
- Modifier resolution alongside the base plan (vintage-PCIA pattern: many Prices share one PD, discriminated by `urpx:PriceSetAlternatives` / `urpx:referencesProfileAlternative`, and selected with `:profile-alternatives`)
- `urpx:ProfileAlternatives` + `urpx:PriceSetAlternatives`, the selection layer for declarative customer-or-configuration choices (income tier CARE/FERA/Standard, voltage configuration, vintage-PCIA departure year). Coerce + validate full schemas; resolve-prices selects the active alternative via an explicit `:profile-alternatives` opt; emit round-trips cleanly. `urpx:referencesProfileAlternative` is read on `PriceSetAlternative`, on `PriceDefinition`, and on `ServiceTiers` and `ServiceBracket` when a tier's bounds are selected; the shapes also carry it on `TOUSchedule`, which is not read. Programmatic `ConditionExpression` evaluation against a customer profile is a future enhancement
- Configurable schedule step (hourly default; sub-hour available via `:step`)
- Symmetric write-side: `urpx.emit` emits JSON-LD from any coerced `RatePlan` / `RatePlanModifier` / `URPXPackage`; the vendored-fixture round-trip suite verifies `parse → coerce → emit → re-parse → re-coerce` is a fixed point for each PG&E bundle in `urpx.emit-test/vendored-fixtures` × {RatePlan, Modifier}
- Authoring DSL (`urpx.dsl`): named builders for every entity-type schema, kebab-case kwarg aliases, auto-coercion of raw inputs (numbers → BigDecimal, ISO strings → java.time, `--MM` → int month), validation-on-construct, and keyword refs scoped via `dsl-scope`

Known gaps:

- Tier bounds on the modifier path: a `urpx:RatePlanModifier`'s `urpx:ServiceTiers` is read by nothing, so its tiers surface with no tier number and no bounds. PG&E's E-TOU-C baseline-tier modifier fixture is that shape: one container of tiers, each holding one bracket per cell of its (`baselineTerritory` x `baselineCode`) x season matrix
- `urpx:referencesServiceBracket` on `Price`, `PriceDefinition`, `PriceSet` and `PriceSetAlternative` is modeled and round-trips, but is read by no resolver: a Price naming one bracket directly while tier selection picks a different one will disagree, with nothing in the result to say so
- A container-level `urpx:referencesMetricInput` does not narrow a tier-number clash: two `urpx:ServiceTiers` declaring one `urpx:serviceTierNumber` with different bounds refuse even when each is scoped to a different metric
- Programmatic interpretation of `urpx:ConditionExpression` for active-tier selection in matrix-style modifiers: the caller picks the active value by `price-id` after walking the condition tree itself
- `County`, `Municipality`, `State` and `ServiceTerritory` are named by reference and not modeled

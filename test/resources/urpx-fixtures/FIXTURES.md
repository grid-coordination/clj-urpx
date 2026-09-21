<!--
SPDX-FileCopyrightText: 2026 Clark Communications Corporation
SPDX-License-Identifier: MIT
-->

# URPX test fixtures

This directory vendors a small set of [URPX](https://github.com/urpx-org/urpx)
JSON-LD rate-plan documents so that the clj-urpx test suite is hermetic —
`clojure -M:test` works on a fresh clone with no sibling checkouts, no
environment setup, no submodule dance.

All twenty-three fixtures are **URPX v0.5.1**. They were mixed until migration wave 6 finished and took the whole corpus to v0.5.1; the PG&E four were re-vendored at `1e4ad46` when wave 4 brought them over. Because no vendored fixture carries the v0.2.x shape any more, the dual-acceptance claim is now covered by a synthetic v0.2.x document in `urpx.v051-fixtures-test` rather than by a fixture. That is a deliberate trade: the corpus is the better source of truth, and it has moved on. Between them the fixtures exercise `urpx:URPXPackage` wrappers, `forRatePlan` back-links alongside the nested pointer, `hasCoverageClaim`, `offeredBy` in place of `publishedBy`, the flattened `DataSourceReference`, and the Organization identity chain that replaced `urpx:eiaId`.

Three of the five LADWP filings are `urpx:RatePlanModifier`s, and `nem` is export-direction rather than a discount, so it is not redundant with `ez-save` and `lifeline`. Note their `@context` is the inline object form, not the array: upstream uses an array whose first element is a remote IRI that does not yet resolve, so the corpus deliberately inlines until `urpx.org/ns/context` serves.

The SCE and SDG&E six are the corpus's first **percentage discounts**: a `urpx:Price` carrying `urpx:multiplier` over a `urpx:PriceSummary` instead of a `urpx:unitPrice`. Both utilities are vendored because neither alone shows the variation, and a fixture set carrying only one would silently sanction whichever shape it happened to contain. SCE shares **one** subtotal between CARE and FERA, which differ only in rate, because it does not carve out the Base Services Charge and its exemption credits sit inside the subtotal beside the charges they cancel. SDG&E carries **two** subtotals on one base plan with different member lists, because Schedule E-CARE excludes any applicable exemptions and FERA grants none, so FERA pays the DWR Bond Charge and the Wildfire Fund NBC and CARE does not. The SDG&E pair also differs by two members while differing in value by only one, since the DWR Bond Charge is filed at zero.

Unlike the PG&E `@graph` bundles, each of these base plans is a **separate filing from its modifiers**, which is how the corpus actually files a program that applies to many plans. Resolving a discount therefore needs both documents passed to `resolve-prices-with-modifiers`, and `urpx.care-fera-test` pins that end to end against the filed values.

The PG&E four changed structurally at wave 4, not just in prefixes: a `urpx:URPXPackage` was INSERTED INTO their existing `@graph` as a sibling rather than wrapping it, because `URPXPackageShape` is `sh:closed` and admits neither `urpx:ProgramEnrollment` nor `urpx:RateCase`, which these filings carry. The plan and its modifier are embedded inside that package, so `load-graph` alone no longer reaches them and the wrapper has to be descended. `urpx:TariffPublication` became `urpx:SourcePublication`, and two `urpx:supersedes` links were dropped because the v0.5.1 version shapes are closed and do not admit the property. The library targets v0.5.1 and accepts v0.2.x in full; because no vendored fixture carries the v0.2.x shape any more, that half is exercised by a synthetic v0.2.x document in `urpx.v051-fixtures-test` rather than by a fixture.

## Source and license

All vendored `.jsonld` files originate in the
[urpx-rate-plans](https://github.com/grid-coordination/urpx-rate-plans) project,
released under **CC0-1.0**. The accompanying `.license` sidecars carry SPDX
headers consistent with the URPX project's
[REUSE](https://reuse.software/) framework. The underlying tariff data is from
public regulatory filings (CPUC advice letters); the URPX serializations are
the work of the urpx-rate-plans contributors.

## Pinned upstream commits

Fixtures are snapshots, pinned to a specific upstream commit on the
`main` branch of urpx-rate-plans.

| Fixture                              | Upstream branch        | Pinned commit |
|--------------------------------------|------------------------|---------------|
| `pge-e-1-2026-03-01.jsonld`          | `main`               | `1e4ad46`     |
| `pge-e-tou-c-2026-03-01.jsonld`      | `main`               | `1e4ad46`     |
| `pge-e-tou-d-2026-03-27.jsonld`      | `main`               | `1e4ad46`     |
| `pge-ev2-2026-03-27.jsonld`          | `main`               | `1e4ad46`     |
| `ladwp-r-1a-2026-04-01.jsonld`       | `main`               | `6453536`     |
| `ladwp-r-1b-2026-04-01.jsonld`       | `main`               | `6453536`     |
| `ladwp-ez-save-2008-09-01.jsonld`    | `main`               | `6453536`     |
| `ladwp-lifeline-2008-09-01.jsonld`   | `main`               | `6453536`     |
| `ladwp-nem-2008-09-01.jsonld`        | `main`               | `6453536`     |
| `sce-d-2026-03-20.jsonld`            | `main`               | `384ffb1`     |
| `sce-care-2026-03-20.jsonld`         | `main`               | `384ffb1`     |
| `sce-fera-2026-03-20.jsonld`         | `main`               | `384ffb1`     |
| `sdge-dr-2026-04-01.jsonld`          | `main`               | `384ffb1`     |
| `sdge-care-2025-10-01.jsonld`        | `main`               | `384ffb1`     |
| `sdge-fera-2026-04-01.jsonld`        | `main`               | `384ffb1`     |
| `cpau-e-14-2025-07-01.jsonld`        | `main`               | `28390dc`     |
| `sce-d-2025-11-15.jsonld`            | `main`               | `28390dc`     |
| `smud-tod-5-8pm-2026-01-01.jsonld`   | `main`               | `28390dc`     |
| `smud-cpp-2026-01-01.jsonld`         | `main`               | `28390dc`     |
| `sce-tou-d-prime-2026-03-20.jsonld`  | `main`               | `28390dc`     |
| `sce-tou-d-prime-cpp-2026-03-20.jsonld` | `main`            | `28390dc`     |
| `sdge-ev-tou-2026-04-01.jsonld`      | `main`               | `28390dc`     |
| `pge-e-tou-c-baseline-tier-2026-03-01.jsonld` | `main`      | `28390dc`     |

Each fixture is a JSON-LD `@graph` document bundling a `urpx:RatePlan` with
its sibling `urpx:RatePlanModifier`(s) plus surrounding metadata
(`urpx:TariffPublication`, `urpx:ProgramEnrollment`).

The `177a761` re-vendor moved the upstream paths from `ca/<class>/<utility>/` to `us/ca/<utility>/electricity/`; the regulator-class split (`iou`/`pou`) was dropped upstream, not merely reordered. Three content changes came with it, and all three were verified rather than assumed: the `org:` and `pge:` prefix bindings moved from `example.com` to `rate-plans.grid-coordination.energy`; the unused `schema:` prefix binding was dropped (zero `schema:`-prefixed terms appear in any fixture, and its removal changes no triple); and a repo-name reference inside one `urpx:description` literal in `pge-e-1` and `pge-ev2` tracks the upstream rename. Under the IRI substitution, `pge-e-tou-c` and `pge-e-tou-d` are graph-isomorphic to their previous content, and `pge-e-1` and `pge-ev2` differ by exactly the one description triple each, at identical triple counts.

## Re-syncing from upstream

Use `scripts/sync-urpx-fixtures.sh` to refresh from a local urpx-rate-plans
checkout:

```bash
./scripts/sync-urpx-fixtures.sh /path/to/urpx-rate-plans
```

The script copies each pinned file from the appropriate branch (via
`git show <commit>:<path>`) into this directory and reports any drift.

When you bump the pinned commits, update the table above so the audit trail
stays accurate.

## Loading fixtures from tests

Tests load fixtures via `clojure.java.io/resource` rather than file paths, so
the working directory doesn't matter:

```clojure
(require '[clojure.java.io :as io]
         '[urpx.core :as core])

;; Each fixture is a @graph bundle — use load-rate-plan to extract the
;; unique urpx:RatePlan, or load-graph to access siblings (modifiers,
;; programs, tariff publication metadata).
(core/load-rate-plan
 (.getPath (io/resource "urpx-fixtures/pge-e-1-2026-03-01.jsonld")))
```

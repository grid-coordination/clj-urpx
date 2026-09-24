<!--
SPDX-FileCopyrightText: 2026 Clark Communications Corporation
SPDX-License-Identifier: MIT
-->

# URPX test fixtures

This directory vendors a small set of [URPX](https://github.com/urpx-org/urpx)
JSON-LD rate-plan documents so that the clj-urpx test suite is hermetic —
`clojure -M:test` works on a fresh clone with no sibling checkouts, no
environment setup, no submodule dance.

All twenty-three fixtures are **URPX v0.5.1**. They were mixed until migration wave 6 finished and took the whole corpus to v0.5.1; the PG&E four come from `1e4ad46`, the revision at which wave 4 brought them to v0.5.1. Because no vendored fixture carries the v0.2.x shape any more, the dual-acceptance claim is now covered by a synthetic v0.2.x document in `urpx.v051-fixtures-test` rather than by a fixture. That is a deliberate trade: the corpus is the better source of truth, and it has moved on. Between them the fixtures exercise `urpx:URPXPackage` wrappers, `forRatePlan` back-links alongside the nested pointer, `hasCoverageClaim`, `offeredBy` in place of `publishedBy`, the flattened `DataSourceReference`, and the Organization identity chain that replaced `urpx:eiaId`.

Three of the five LADWP filings are `urpx:RatePlanModifier`s, and `nem` is export-direction rather than a discount, so it is not redundant with `ez-save` and `lifeline`. Note their `@context` is the inline object form, not the array: upstream uses an array whose first element is a remote IRI that does not yet resolve, so the corpus deliberately inlines until `urpx.org/ns/context` serves.

The SCE and SDG&E six are the corpus's first **percentage discounts**: a `urpx:Price` carrying `urpx:multiplier` over a `urpx:PriceSummary` instead of a `urpx:unitPrice`. Both utilities are vendored because neither alone shows the variation, and a fixture set carrying only one would silently sanction whichever shape it happened to contain. SCE shares **one** subtotal between CARE and FERA, which differ only in rate, because it does not carve out the Base Services Charge and its exemption credits sit inside the subtotal beside the charges they cancel. SDG&E carries **two** subtotals on one base plan with different member lists, because Schedule E-CARE excludes any applicable exemptions and FERA grants none, so FERA pays the DWR Bond Charge and the Wildfire Fund NBC and CARE does not. The SDG&E pair also differs by two members while differing in value by only one, since the DWR Bond Charge is filed at zero.

Unlike the PG&E `@graph` bundles, each of these base plans is a **separate filing from its modifiers**, which is how the corpus actually files a program that applies to many plans. Resolving a discount therefore needs both documents passed to `resolve-prices-with-modifiers`, and `urpx.care-fera-test` pins that end to end against the filed values.

The PG&E four changed structurally at wave 4, not just in prefixes: a `urpx:URPXPackage` was INSERTED INTO their existing `@graph` as a sibling rather than wrapping it, because `URPXPackageShape` is `sh:closed` and admits neither `urpx:ProgramEnrollment` nor `urpx:RateCase`, which these filings carry. The plan and its modifier are embedded inside that package, so `load-graph` alone no longer reaches them and the wrapper has to be descended. `urpx:TariffPublication` became `urpx:SourcePublication`, and two `urpx:supersedes` links were dropped because the v0.5.1 version shapes are closed and do not admit the property. The library targets v0.5.1 and accepts v0.2.x in full; because no vendored fixture carries the v0.2.x shape any more, that half is exercised by a synthetic v0.2.x document in `urpx.v051-fixtures-test` rather than by a fixture.

## Why each specimen was chosen, and why some look old

These are frozen specimens, not a mirror of the upstream corpus. Each one is here because it carries a shape the library must handle, and each is left alone unless there is a stated reason to replace it. Where a specimen is older than upstream, that is usually the point: the shape it carries may be one the corpus has since stopped using, and that is exactly the coverage a current copy would lose.

**`sdge-ev-tou-2026-04-01` carries twelve `urpx:Price` nodes with `urpx:touPeriodNumber`**, a shape upstream has since collapsed onto the PriceDefinition. Mishandling that property overstated SDG&E's CARE discount 3.09x, fixed in 0.22.0, and this specimen is the only place the shape survives. `urpx.price-test/a-price-carries-its-own-tou-period` counts the twelve in its own guard assertion, so replacing this specimen with a current copy would leave that test green while testing nothing. If it is ever replaced, write the synthetic document first.

**`sce-d-2026-03-20` carries the house idiom**, `urpx:serviceTierNumber` on the PriceDefinition rather than on the Price. An earlier specimen of this filing carried the minority form, and that is precisely why the 0.22.0 summary tier fix passed here and did nothing on real filings, needing 0.23.0. `urpx.care-fera-test` now derives the minority form from this one rather than the reverse.

**Two halves of one filing must come from one upstream commit.** `pge-e-tou-c-2026-03-01` and its `pge-e-tou-c-baseline-tier-2026-03-01` overlay were once taken from different upstream moments, so the overlay named `pge:e-tou-c-2026-03-01-season-{summer,winter}` while its base plan declared `pge:season-{summer,winter}`. Each was self-consistent where it came from; the mismatch existed only here. Nothing read those refs, so it stayed invisible until URPX-sr4 wired the modifier path and it became a refusal. An inconsistent pair is not a URPX document set any consumer would ever see.

**Beware ids that share a suffix** if you ever add a specimen from a newer upstream revision. `pge:pa-vintage-2020` appears in two PG&E filings and scopes differently in each, and within one filing three nodes share the `pa-` suffix (`pa-`, `cn-pa-`, `cx-pa-`), so a substitution by suffix hits the wrong node.

## Source and license

All vendored `.jsonld` files originate in the
[urpx-rate-plans](https://github.com/grid-coordination/urpx-rate-plans) project,
released under **CC0-1.0**. The accompanying `.license` sidecars carry SPDX
headers consistent with the URPX project's
[REUSE](https://reuse.software/) framework. The underlying tariff data is from
public regulatory filings (CPUC advice letters); the URPX serializations are
the work of the urpx-rate-plans contributors.

## Adding a specimen

Provenance lives in each fixture's `.license` sidecar, which records the upstream repository, the commit the bytes came from, and the path. That is a historical fact about a frozen file, so it never needs updating.

There is deliberately no bulk re-sync. Tracking a moving corpus is what this directory used to do, and it cost more than it gave: the suite broke when a client edited their filings for reasons internal to them, and taking two halves of one filing from two moments manufactured an inconsistency no consumer would see. clj-urpx is a general-purpose URPX library, and one corpus is one client of it.

To add a specimen, copy the file out of a checkout at one commit and record that commit in its sidecar:

```bash
git -C /path/to/urpx-rate-plans show <commit>:<path> \
  > test/resources/urpx-fixtures/<name>.jsonld
```

Then write `<name>.jsonld.license` with the SPDX headers and the provenance lines, say in this file what shape the specimen covers and why the suite needs it, and take every document of one filing from the same commit.

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

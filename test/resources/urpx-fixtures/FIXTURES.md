<!--
SPDX-FileCopyrightText: 2026 Clark Communications Corporation
SPDX-License-Identifier: MIT
-->

# URPX test fixtures

This directory vendors a small set of [URPX](https://github.com/urpx-org/urpx)
JSON-LD rate-plan documents so that the clj-urpx test suite is hermetic —
`clojure -M:test` works on a fresh clone with no sibling checkouts, no
environment setup, no submodule dance.

The fixtures target **URPX v0.2.0**.

## Source and license

All vendored `.jsonld` files originate in the
[urpx-tariffs](https://github.com/grid-coordination/urpx-tariffs) project,
released under **CC0-1.0**. The accompanying `.license` sidecars carry SPDX
headers consistent with the URPX project's
[REUSE](https://reuse.software/) framework. The underlying tariff data is from
public regulatory filings (CPUC advice letters); the URPX serializations are
the work of the urpx-tariffs contributors.

## Pinned upstream commits

Fixtures are snapshots, pinned to a specific upstream commit on the
`main` branch of urpx-tariffs.

| Fixture                              | Upstream branch        | Pinned commit |
|--------------------------------------|------------------------|---------------|
| `pge-e-1-2026-03-01.jsonld`          | `main`               | `fed8ab4`     |
| `pge-e-tou-c-2026-03-01.jsonld`      | `main`               | `fed8ab4`     |
| `pge-e-tou-d-2026-03-27.jsonld`      | `main`               | `fed8ab4`     |
| `pge-ev2-2026-03-27.jsonld`          | `main`               | `fed8ab4`     |

Each fixture is a JSON-LD `@graph` document bundling a `urpx:RatePlan` with
its sibling `urpx:RatePlanModifier`(s) plus surrounding metadata
(`urpx:TariffPublication`, `urpx:ProgramEnrollment`).

## Re-syncing from upstream

Use `scripts/sync-urpx-fixtures.sh` to refresh from a local urpx-tariffs
checkout:

```bash
./scripts/sync-urpx-fixtures.sh /path/to/urpx-tariffs
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

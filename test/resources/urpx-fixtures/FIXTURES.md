<!--
SPDX-FileCopyrightText: 2026 Clark Communications Corporation
SPDX-License-Identifier: MIT
-->

# URPX test fixtures

This directory vendors a small set of [URPX](https://github.com/urpx-org/urpx)
JSON-LD rate-plan and rate-plan-modifier documents so that the clj-urpx test
suite is hermetic — `clojure -M:test` works on a fresh clone with no sibling
checkouts, no environment setup, no submodule dance.

## Source and license

All vendored `.jsonld` files originate in the upstream URPX project,
copyright Flux Tailor LLC, distributed under **Apache-2.0**. The accompanying
`.license` sidecars carry SPDX headers consistent with the URPX project's
[REUSE](https://reuse.software/) framework. Datasets are slated to transition
to CDLA-Permissive-2.0 upon URPX's acceptance as a Working Group Draft;
that transition happens upstream and we re-sync.

## Pinned upstream commits

Fixtures are snapshots, pinned to specific upstream commits.

| Fixture                                               | Upstream branch         | Pinned commit |
|-------------------------------------------------------|-------------------------|---------------|
| `cpau-e-1-2025-07-01.jsonld`                          | `cpau-rate-plans`       | `205f030`     |
| `cpau-e-1-tou-2026-01-01.jsonld`                      | `cpau-rate-plans`       | `205f030`     |
| `cpau-e-eec-1-2025-09-01.jsonld`                      | `cpau-rate-plans`       | `205f030`     |
| `cpau-e-hra-2023-01-01.jsonld`                        | `cpau-rate-plans`       | `205f030`     |
| `cpau-e-nse-1-2025-09-01.jsonld`                      | `cpau-rate-plans`       | `205f030`     |
| `pge-e-elec-2026-01-01.jsonld`                        | `cpau-rate-plans`       | `205f030`     |
| `pge-e-tou-d-tou-holiday-2026-03-01.jsonld`           | `tou-daytype-holiday`   | `02be2e4`     |

CPAU fixtures and `pge-e-elec` come from the `cpau-rate-plans` feature branch;
`pge-e-tou-d-tou-holiday` lives on `tou-daytype-holiday`. Neither branch carries
`.license` sidecars yet upstream — the sidecars in this directory are written
locally to match the pattern established on `v0.2.0-candidate` and assert the
same Apache-2.0 / Flux Tailor attribution. The substantive copyright and
license terms are upstream's; we only mirror the metadata.

## Re-syncing from upstream

Use `scripts/sync-urpx-fixtures.sh` to refresh from a local URPX checkout:

```bash
./scripts/sync-urpx-fixtures.sh /path/to/urpx
```

The script copies each pinned file from the appropriate branch (via
`git show <commit>:<path>`) into this directory and reports any drift. It
does not regenerate the `.license` sidecars — those stay stable until the
URPX licensing model itself moves.

When you bump the pinned commits, update the table above so the audit trail
stays accurate.

## Loading fixtures from tests

Tests load fixtures via `clojure.java.io/resource` rather than file paths, so
the working directory doesn't matter:

```clojure
(require '[clojure.java.io :as io]
         '[urpx.core :as core])

(core/parse-rate-plan
 (io/resource "urpx-fixtures/cpau-e-1-tou-2026-01-01.jsonld"))
```

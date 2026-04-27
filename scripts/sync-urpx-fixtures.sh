#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Clark Communications Corporation
# SPDX-License-Identifier: MIT
#
# Re-sync the vendored URPX test fixtures under
# test/resources/urpx-fixtures/ from a local URPX checkout.
#
# Usage: scripts/sync-urpx-fixtures.sh <path-to-urpx-checkout>
#
# Each fixture is fetched at its pinned upstream commit via
# `git show <commit>:<path>` so the working tree of the URPX checkout
# does not affect the result.
#
# When you bump a pinned commit here, also update
# test/resources/urpx-fixtures/FIXTURES.md so the audit trail tracks.

set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: $0 <path-to-urpx-checkout>" >&2
  exit 2
fi

urpx_dir=$1
if [[ ! -d "$urpx_dir/.git" ]]; then
  echo "error: $urpx_dir is not a git checkout" >&2
  exit 2
fi

repo_root=$(cd "$(dirname "$0")/.." && pwd)
dest=$repo_root/test/resources/urpx-fixtures

# fixture-name             upstream-commit  upstream-path
fixtures=(
  "cpau-e-1-2025-07-01                       205f030  test-cases/cpau-e-1-2025-07-01/data/urpx-rate-plan_cpau_e-1_2025-07-01.jsonld"
  "cpau-e-1-tou-2026-01-01                   205f030  test-cases/cpau-e-1-tou-2026-01-01/data/urpx-rate-plan_cpau_e-1-tou_2026-01-01.jsonld"
  "cpau-e-eec-1-2025-09-01                   205f030  test-cases/cpau-e-eec-1-2025-09-01/data/urpx-rate-plan-modifier_cpau_e-eec-1_2025-09-01.jsonld"
  "cpau-e-hra-2023-01-01                     205f030  test-cases/cpau-e-hra-2023-01-01/data/urpx-rate-plan-modifier_cpau_e-hra_2023-01-01.jsonld"
  "cpau-e-nse-1-2025-09-01                   205f030  test-cases/cpau-e-nse-1-2025-09-01/data/urpx-rate-plan-modifier_cpau_e-nse-1_2025-09-01.jsonld"
  "pge-e-elec-2026-01-01                     205f030  test-cases/pge-e-elec-2026-01-01/data/urpx-rate-plan_pge_e-elec_2026-01-01.jsonld"
  "pge-e-tou-d-tou-holiday-2026-03-01        02be2e4  test-cases/pge-e-tou-d-tou-holiday-2026-03-01/data/urpx-rate-plan_pge_e-tou-d-tou-holiday_2026-03-01.jsonld"
)

for entry in "${fixtures[@]}"; do
  read -r name commit path <<<"$entry"
  echo "syncing $name ($commit)"
  if ! git -C "$urpx_dir" cat-file -e "$commit" 2>/dev/null; then
    echo "  error: commit $commit not present in $urpx_dir — fetch the upstream branch first" >&2
    exit 1
  fi
  git -C "$urpx_dir" show "$commit:$path" > "$dest/$name.jsonld"
done

echo
echo "synced ${#fixtures[@]} fixture(s) into $dest"
echo "review with: git -C $repo_root diff -- $dest"

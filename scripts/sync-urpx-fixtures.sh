#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Clark Communications Corporation
# SPDX-License-Identifier: MIT
#
# Re-sync the vendored URPX test fixtures under
# test/resources/urpx-fixtures/ from a local urpx-tariffs checkout.
#
# Usage: scripts/sync-urpx-fixtures.sh <path-to-urpx-tariffs-checkout>
#
# Each fixture is fetched at its pinned upstream commit via
# `git show <commit>:<path>` so the working tree of the urpx-tariffs
# checkout does not affect the result.
#
# When you bump a pinned commit here, also update
# test/resources/urpx-fixtures/FIXTURES.md so the audit trail tracks.

set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: $0 <path-to-urpx-tariffs-checkout>" >&2
  exit 2
fi

src_dir=$1
if [[ ! -d "$src_dir/.git" ]]; then
  echo "error: $src_dir is not a git checkout" >&2
  exit 2
fi

repo_root=$(cd "$(dirname "$0")/.." && pwd)
dest=$repo_root/test/resources/urpx-fixtures

# fixture-name                   upstream-commit  upstream-path
fixtures=(
  "pge-e-1-2026-03-01             fed8ab4  ca/iou/pge/e-1/versions/2026-03-01/rate-plan_pge_e-1_2026-03-01.jsonld"
  "pge-e-tou-c-2026-03-01         fed8ab4  ca/iou/pge/e-tou-c/versions/2026-03-01/rate-plan_pge_e-tou-c_2026-03-01.jsonld"
  "pge-e-tou-d-2026-03-27         fed8ab4  ca/iou/pge/e-tou-d/versions/2026-03-27/rate-plan_pge_e-tou-d_2026-03-27.jsonld"
  "pge-ev2-2026-03-27             fed8ab4  ca/iou/pge/ev2/versions/2026-03-27/rate-plan_pge_ev2_2026-03-27.jsonld"
)

for entry in "${fixtures[@]}"; do
  read -r name commit path <<<"$entry"
  echo "syncing $name ($commit)"
  if ! git -C "$src_dir" cat-file -e "$commit" 2>/dev/null; then
    echo "  error: commit $commit not present in $src_dir — fetch the upstream branch first" >&2
    exit 1
  fi
  git -C "$src_dir" show "$commit:$path" > "$dest/$name.jsonld"
done

echo
echo "synced ${#fixtures[@]} fixture(s) into $dest"
echo "review with: git -C $repo_root diff -- $dest"

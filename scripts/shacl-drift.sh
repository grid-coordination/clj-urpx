#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Clark Communications Corporation
# SPDX-License-Identifier: MIT
#
# Report drift between the shipped URPX SHACL shapes and clj-urpx's Malli
# schemas. Reads the shapes out of a urpx checkout rather than vendoring them,
# the same convention sync-urpx-fixtures.sh uses for fixtures.
#
# Usage: scripts/shacl-drift.sh <path-to-urpx-checkout> [rev]
#
# Needs rdflib on the python3 that runs it. Exit codes are tri-state:
# 0 no new drift, 1 new drift, 2 refused.

set -euo pipefail

if [[ $# -lt 1 || $# -gt 2 ]]; then
  echo "usage: $0 <path-to-urpx-checkout> [rev]" >&2
  exit 2
fi

src_dir=$1
rev=${2:-95eb8e22}
repo_root=$(cd "$(dirname "$0")/.." && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

if [[ ! -d "$src_dir/.git" ]]; then
  echo "REFUSED: $src_dir is not a git checkout" >&2
  exit 2
fi

echo "Extracting SHACL shapes at $rev ..." >&2
python3 "$repo_root/dev/shacl_extract.py" "$src_dir" "$rev" > "$tmp/shapes.json"

echo "Dumping Malli schema facts ..." >&2
(cd "$repo_root" && clojure -M:drift-facts) > "$tmp/facts.edn"

cd "$repo_root"
exec clojure -M:drift "$tmp/shapes.json" "$tmp/facts.edn" "$repo_root/dev/drift-baseline.edn"

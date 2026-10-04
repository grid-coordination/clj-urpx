#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Clark Communications Corporation
# SPDX-License-Identifier: MIT
#
# Report drift between the shipped URPX SHACL shapes and clj-urpx's Malli
# schemas. Reads the shapes out of a urpx checkout rather than vendoring them.
#
# Usage: scripts/shacl-drift.sh [path-to-urpx-checkout] [rev]
#
# The checkout defaults to $URPX_DIR. $PYTHON (default python3) runs the
# extractor and needs rdflib. Exit codes are tri-state:
# 0 no new drift, 1 new drift, 2 refused.

set -euo pipefail

if [[ $# -gt 2 ]]; then
  echo "usage: $0 [path-to-urpx-checkout] [rev]   (checkout defaults to \$URPX_DIR)" >&2
  exit 2
fi

src_dir=${1:-${URPX_DIR:-}}
python=${PYTHON:-python3}
if [[ -z "$src_dir" ]]; then
  echo "REFUSED: no urpx checkout; pass a path or set URPX_DIR" >&2
  exit 2
fi
rev=${2:-95eb8e22}
repo_root=$(cd "$(dirname "$0")/.." && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

if [[ ! -d "$src_dir/.git" ]]; then
  echo "REFUSED: $src_dir is not a git checkout" >&2
  exit 2
fi

echo "Extracting SHACL shapes at $rev ..." >&2
"$python" "$repo_root/dev/shacl_extract.py" "$src_dir" "$rev" > "$tmp/shapes.json"

echo "Dumping Malli schema facts ..." >&2
(cd "$repo_root" && clojure -M:drift-facts) > "$tmp/facts.edn"

cd "$repo_root"
exec clojure -M:drift "$tmp/shapes.json" "$tmp/facts.edn" "$repo_root/dev/drift-baseline.edn"

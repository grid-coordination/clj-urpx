#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Clark Communications Corporation
# SPDX-License-Identifier: MIT
"""Write the JSON-LD flattened form of vendored fixtures, compacted with each
fixture's own inline @context.

Usage: scripts/flatten-fixtures.py <name>...

Reads test/resources/urpx-fixtures/<name>.jsonld and writes
test/resources/urpx-flattened/<name>.jsonld. Needs pyld
(python3 -m pip install pyld). Runs offline: every fixture's @context is inline.
"""
import json
import sys
from pathlib import Path

from pyld import jsonld

RESOURCES = Path(__file__).resolve().parent.parent / "test/resources"


def main(names):
    out = RESOURCES / "urpx-flattened"
    out.mkdir(exist_ok=True)
    for name in names:
        doc = json.loads((RESOURCES / "urpx-fixtures" / f"{name}.jsonld").read_text())
        flat = jsonld.flatten(doc, doc["@context"])
        (out / f"{name}.jsonld").write_text(json.dumps(flat, indent=2, ensure_ascii=False) + "\n")
        print(f"{name}: {len(flat['@graph'])} nodes")


if __name__ == "__main__":
    main(sys.argv[1:])

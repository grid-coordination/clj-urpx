#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Clark Communications Corporation
# SPDX-License-Identifier: MIT
"""Extract node-shape facts from a URPX SHACL graph as JSON on stdout.

Thin on purpose: this knows how to read Turtle and nothing about Malli or about
what clj-urpx models. The comparison lives in dev/drift.clj, in the repo's own
language. Nothing URPX-derived is written to disk here; the shapes are read out
of a urpx checkout passed in, the same convention scripts/sync-urpx-fixtures.sh
uses for fixtures.

    python3 dev/shacl_extract.py <urpx-checkout> <rev> > shapes.json

Requires rdflib (available in the p3newa venv alongside pyshacl).
"""
import json
import subprocess
import sys
from collections import defaultdict

try:
    from rdflib import Graph, RDF, Namespace
    from rdflib.namespace import SH
except ImportError:
    sys.stderr.write("REFUSED: rdflib not importable. Activate the venv that has it:\n")
    sys.stderr.write("  source ~/env/py/p3newa/bin/activate\n")
    sys.exit(2)

URPX = Namespace("https://urpx.org/ns/ontology/")


def curie(g, term):
    """Full IRI -> the CURIE form clj-urpx keys on, e.g. urpx:hasRatePlan."""
    if term is None:
        return None
    s = str(term)
    for prefix, ns in g.namespaces():
        ns = str(ns)
        if s.startswith(ns) and len(ns) > 8:
            return f"{prefix}:{s[len(ns):]}"
    return s


def shape_facts(g):
    shapes = {}
    for shape in g.subjects(RDF.type, SH.NodeShape):
        target = g.value(shape, SH.targetClass)
        if target is None:
            continue
        props = []
        for pshape in g.objects(shape, SH.property):
            path = g.value(pshape, SH.path)
            # A path that is not a plain predicate is an RDF-encoding construct
            # (sequence, inverse, or an rdf:List idiom). Flag rather than guess.
            translatable = path is not None and not isinstance(path, type(shape)) or True
            entry = {
                "path": curie(g, path),
                "minCount": g.value(pshape, SH.minCount),
                "maxCount": g.value(pshape, SH.maxCount),
                "datatype": curie(g, g.value(pshape, SH.datatype)),
                "class": curie(g, g.value(pshape, SH["class"])),
                "node": curie(g, g.value(pshape, SH.node)),
                "nodeKind": curie(g, g.value(pshape, SH.nodeKind)),
            }
            for k in ("minCount", "maxCount"):
                entry[k] = int(entry[k]) if entry[k] is not None else None
            # sh:in may sit on the property or on the sh:node it points at
            members = None
            for holder in (pshape, g.value(pshape, SH.node)):
                if holder is None:
                    continue
                lst = g.value(holder, SH["in"])
                if lst is not None:
                    from rdflib.collection import Collection
                    members = [curie(g, m) for m in Collection(g, lst)]
                    break
            entry["in"] = members
            props.append(entry)
        ignored = []
        lst = g.value(shape, SH.ignoredProperties)
        if lst is not None:
            from rdflib.collection import Collection
            ignored = [curie(g, m) for m in Collection(g, lst)]
        shapes[curie(g, target)] = {
            "shape": curie(g, shape),
            "closed": bool(g.value(shape, SH.closed)),
            "ignoredProperties": ignored,
            "properties": sorted([p for p in props if p["path"]], key=lambda p: p["path"]),
        }
    return shapes


def main():
    if len(sys.argv) != 3:
        sys.stderr.write("usage: shacl_extract.py <urpx-checkout> <rev>\n")
        sys.exit(2)
    checkout, rev = sys.argv[1], sys.argv[2]
    try:
        ttl = subprocess.run(
            ["git", "-C", checkout, "show", f"{rev}:shacl/urpx-shacl.ttl"],
            capture_output=True, text=True, check=True).stdout
    except subprocess.CalledProcessError as e:
        sys.stderr.write(f"REFUSED: cannot read shacl/urpx-shacl.ttl at {rev} in {checkout}\n")
        sys.stderr.write((e.stderr or "").strip() + "\n")
        sys.exit(2)
    g = Graph()
    g.parse(data=ttl, format="turtle")
    shapes = shape_facts(g)
    if not shapes:
        sys.stderr.write("REFUSED: parsed the shapes graph but found no sh:targetClass node shapes.\n")
        sys.stderr.write("Refusing to report zero drift over nothing.\n")
        sys.exit(2)
    json.dump({"rev": rev, "shapes": shapes}, sys.stdout, indent=None, sort_keys=True)


if __name__ == "__main__":
    main()

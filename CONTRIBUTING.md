# Contributing to clj-urpx

Thanks for your interest in contributing! This repo is a Clojure library for [URPX](https://github.com/urpx-org/urpx) — parse, coerce, resolve, emit, and author URPX rate-plan documents in idiomatic Clojure. It targets the URPX v0.5.1 ontology snapshot while continuing to accept v0.2.x in full.

## How to contribute

### Discussions

Use [Discussions](https://github.com/grid-coordination/clj-urpx/discussions) for:

- Questions about how to use the library — parsing, coercion, price resolution, schedule generation, the DSL, the write-side
- API and design judgment calls — "should clj-urpx model X?" / "is this the right shape for the DSL builder for Y?"
- URPX vocabulary gaps that affect clj-urpx — when you can't express a regulatory rule cleanly and want to scope what the library should do about it
- Coordination with the upstream [URPX](https://github.com/urpx-org/urpx) standard
- Sharing what you're building on top of clj-urpx

Discussions are open-ended — a good place to think out loud or scope something before it becomes a concrete change. Aligned outcomes from a Discussion often turn into one or more Issues.

### Issues

Use [Issues](https://github.com/grid-coordination/clj-urpx/issues) for actionable changes:

- Bugs in parsing, coercion, schema validation, or price resolution against real URPX fixtures
- SHACL classes that aren't yet modeled and that a real consumer needs (`urpx:TariffPublication`, `urpx:ProgramEnrollment`, the non-RatePlan URPXDocument subclasses, etc. — see [README §Status](README.md#status) for the running list)
- Test failures or unexpected behavior with concrete repro steps
- Schema gaps surfaced by a real filing (one that should validate but doesn't, or coerces into a shape the resolver can't handle)
- Documentation errors, unclear explanations, or stale prose in `README.md`, `doc/dsl-tutorial.md`, or namespace docstrings
- Discussion outcomes that have alignment and a clear scope

If you're not sure whether something is an Issue or a Discussion, start with a Discussion — we can convert it later.

### Pull requests

Pull requests are welcome.

- For small fixes (typos, broken links, single-test corrections backed by an existing fixture, single-decoder bug fixes), open a PR directly.
- For substantive changes (new schema classes, new DSL builders, new resolver behavior, write-side capabilities, new namespaces), open a Discussion or Issue first so we can align on scope before you invest the effort.
- All changes pass `clojure -M:test` (Kaocha) and `clj-kondo --lint src test` cleanly.
- Match the existing tone and structure. The library composes parse → coerce → {resolve, emit, dsl} as roughly orthogonal tiers; patches that fit cleanly into one tier without leaking concerns across them are the easiest to land.
- One commit per logical change is fine; we don't require squash or any particular branch naming.

## Development

```bash
clojure -M:test                 # run the Kaocha test suite (~150 tests)
clojure -M:nrepl                # nREPL on the port written to .nrepl-port
clj-kondo --lint src test       # lint
```

### Configuration

The library and its tests need nothing beyond the Clojure CLI. `scripts/shacl-drift.sh` reads two environment variables, documented with placeholders in [`.env.example`](.env.example):

| Variable | Used by | Value |
|---|---|---|
| `URPX_DIR` | `scripts/shacl-drift.sh`, when no checkout path is given | a git checkout of [urpx-org/urpx](https://github.com/urpx-org/urpx) |
| `PYTHON` | `scripts/shacl-drift.sh`, to run `dev/shacl_extract.py` | a `python3` that can import `rdflib` (`python3 -m pip install rdflib`); default `python3` |

Copy `.env.example` to `.env` (gitignored), edit it, and load it with `set -a; . ./.env; set +a`. `markdownlint-cli2` picks up the repo's `.markdownlint-cli2.jsonc` when run from the repo root.

### The documentation is executed

`urpx.docs-test` runs the docs so their claims can fail like any other test, in two tiers.

Every Clojure block in [`doc/dsl-tutorial.md`](doc/dsl-tutorial.md) is **evaluated**, in order, in one namespace, the way a reader working through the page would hit them. If you add a block there, it has to run.

README blocks are illustrative snippets that reference bindings they never define, so running them is not meaningful. Instead, every `urpx.*` function they name must still **resolve** — which is what actually goes wrong: a rename that leaves the prose behind.

Both tiers exist because prose about code rots silently. A missing DSL builder is not a failing test, and a tutorial that throws is not a failing test. Both happened here: the tutorial's `rate-plan-version` example became a throw the moment two properties were made required, and nothing noticed until someone re-read it.

### Checking for drift against the URPX standard

`scripts/shacl-drift.sh [path-to-urpx-checkout] [rev]` reports where the hand-written Malli schemas have fallen behind the shipped SHACL shapes. It reads the shapes out of the checkout you point it at, or `$URPX_DIR`, rather than vendoring them, and runs its extractor with `$PYTHON` (see [Configuration](#configuration)).

It is an auditor, not a generator: it emits no schemas and nothing it produces is compiled or shipped. That division is deliberate. A generator's central mapping rule can be silently vacuous, and neither compilation nor a passing test suite catches that; a diff a person reads cannot fail the same way.

Findings are ranked by what they cost. `rejects-valid` and `arity` mean clj-urpx refuses documents the standard allows, which is the expensive direction; `under-validates` and `missing-required` mean the reverse. `enum-unenforced`, `missing`, `unmodeled-datatype` and `closed-upstream` are informational and never fail a run. Exit codes are tri-state: `0` no new drift, `1` new drift, `2` refused, so a run that compared nothing cannot read as clean.

Deliberate divergence goes in [`dev/drift-baseline.edn`](dev/drift-baseline.edn) with a written reason. A known bug does not: silencing it there is how it stops being known.

If you are about to propose generating the schemas from the shapes instead, read [SHACL and Malli](doc/shacl-and-malli.md) first. A generator was built, it worked, and the note records the measured reasons it is not what ships.

The vendored fixtures under `test/resources/urpx-fixtures/` are frozen specimens of real URPX filings, taken from [urpx-rate-plans](https://github.com/grid-coordination/urpx-rate-plans) and left alone. Each one records where its bytes came from in its own `.license` sidecar. There is no re-sync: clj-urpx is a general-purpose URPX library and one corpus is one client of it, so tracking that corpus's edits made the suite fail for reasons that were never about this library. See [`test/resources/urpx-fixtures/FIXTURES.md`](test/resources/urpx-fixtures/FIXTURES.md) for what shape each specimen covers and how to add one. When a real filing exposes a shape clj-urpx should handle and no specimen carries it, add one; otherwise prefer a synthetic document, which can state one shape per test where a filing states many at once.

## Code of conduct

Be respectful and constructive. We're a small project and appreciate everyone who takes the time to file an issue or send a PR.

## Important notice

This library is provided on an "as-is" basis. Updates and maintenance, including responses to issues filed on GitHub, will take place on an "as time and resources permit" basis. Library output (parsed maps, resolved prices, emitted JSON-LD) is best-effort against the URPX ontology snapshot the fixtures pin via `urpx:targetURPXVersion` (v0.5.1) and is not authoritative for billing — independent verification against the source URPX specification and tariff filings is recommended for any consumer using these results for billing-correctness purposes.

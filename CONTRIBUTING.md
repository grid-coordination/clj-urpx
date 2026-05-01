# Contributing to clj-urpx

Thanks for your interest in contributing! This repo is a Clojure library for [URPX](https://github.com/urpx-org/urpx) — parse, coerce, resolve, emit, and author URPX rate-plan documents in idiomatic Clojure. It targets the v0.2.0 ontology snapshot and is the validator-of-last-resort the [urpx-tariffs](https://github.com/grid-coordination/urpx-tariffs) corpus leans on for end-to-end load → coerce → resolve checks.

## How to contribute

### Discussions

Use [Discussions](https://github.com/grid-coordination/clj-urpx/discussions) for:

- Questions about how to use the library — parsing, coercion, price resolution, schedule generation, the DSL, the write-side
- API and design judgment calls — "should clj-urpx model X?" / "is this the right shape for the DSL builder for Y?"
- URPX vocabulary gaps that affect clj-urpx — when you can't express a regulatory rule cleanly and want to scope what the library should do about it
- Coordination with the upstream [URPX](https://github.com/urpx-org/urpx) standard or the [urpx-tariffs](https://github.com/grid-coordination/urpx-tariffs) corpus
- Sharing what you're building on top of clj-urpx

Discussions are open-ended — a good place to think out loud or scope something before it becomes a concrete change. Aligned outcomes from a Discussion often turn into one or more Issues.

### Issues

Use [Issues](https://github.com/grid-coordination/clj-urpx/issues) for actionable changes:

- Bugs in parsing, coercion, schema validation, or price resolution against real URPX fixtures
- v0.2.0 SHACL classes that aren't yet modeled and that a real consumer needs (`urpx:TariffPublication`, `urpx:ProgramEnrollment`, the non-RatePlan URPXDocument subclasses, etc. — see [README §Status](README.md#status) for the running list)
- Test failures or unexpected behavior with concrete repro steps
- Schema gaps surfaced by the urpx-tariffs corpus (a filing that should validate but doesn't, or coerces into a shape the resolver can't handle)
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

The vendored fixtures under `test/resources/urpx-fixtures/` are pinned snapshots of corpus filings from [urpx-tariffs](https://github.com/grid-coordination/urpx-tariffs). See [`test/resources/urpx-fixtures/FIXTURES.md`](test/resources/urpx-fixtures/FIXTURES.md) for the table of pinned commits and the re-sync workflow (`scripts/sync-urpx-fixtures.sh <path-to-urpx-tariffs-checkout>`). When the upstream corpus exposes a new shape that clj-urpx should handle, add the fixture there with provenance and bump the pin.

## Code of conduct

Be respectful and constructive. We're a small project and appreciate everyone who takes the time to file an issue or send a PR.

## Important notice

This library is provided on an "as-is" basis. Updates and maintenance, including responses to issues filed on GitHub, will take place on an "as time and resources permit" basis. Library output (parsed maps, resolved prices, emitted JSON-LD) is best-effort against the URPX v0.2.0 spec and is not authoritative for billing — independent verification against the source URPX specification and tariff filings is recommended for any consumer using these results for billing-correctness purposes.

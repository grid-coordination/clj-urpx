<!--
SPDX-FileCopyrightText: 2026 Clark Communications Corporation
SPDX-License-Identifier: MIT
-->

# Releasing clj-urpx

```bash
clojure -T:build release
```

That is the whole thing. It runs the tests, builds the jar, verifies the artifact against the commit, and publishes. Everything below explains what it refuses and why, so that a refusal is actionable rather than mysterious.

## Before you start

Land every change, update `CHANGELOG.md` and the README Installation coordinate, set `version` in `build.clj` to the release version (drop `-SNAPSHOT`), commit, then:

```bash
clojure -T:build tag                                    # tags HEAD as v<version>
CLOJARS_PASSWORD="$CLOJARS_TOKEN_ENERGY_GRID" clojure -T:build release
```

The token is group-scoped: `CLOJARS_TOKEN_ENERGY_GRID` covers `energy.grid-coordination/*`. `deps-deploy` only reads `CLOJARS_PASSWORD`, which is why it is passed inline rather than exported.

Afterwards, bump `version` to the next `-SNAPSHOT`, commit, and push both the branch and the tag. Then publish the GitHub release from that version's CHANGELOG section:

```bash
awk '/^## \[X.Y.Z\]/{f=1; next} /^## \[/{f=0} f' CHANGELOG.md > /tmp/notes.md
gh release create vX.Y.Z --title vX.Y.Z --notes-file /tmp/notes.md --latest
```

## Why `release` exists as one task

`deploy` publishes whatever is sitting in `target/`. It does not build it. That makes `ci` a step a human has to remember, and the one time it was forgotten this library published a pom from a build eight versions old. `release` is `ci` then `deploy` in the one order that is correct, so the remembering is not load-bearing.

## What `deploy` refuses, and what to do about it

Each check exists because the thing it checks for happened, or because the artifact made it cheap to check. The jar carries its own `build-provenance.edn` recording the commit it was built from, so the artifact is the witness: `deploy` compares it against `HEAD` rather than trusting that the right task ran.

| refusal | fix |
|---|---|
| version is a `-SNAPSHOT` | set a release version in `build.clj` |
| working tree has uncommitted changes | commit or stash; an artifact that matches no commit cannot be traced |
| `src/` or `resources/` holds a git-ignored file | remove it, or commit it. `ci` copies those directories whole, and neither `git status` nor build-provenance reports ignored files, so it would ride into the jar with every other gate quiet. Files `tools.build` itself excludes (`.DS_Store`, `*~`, `#...#`, `.#...`) do not trip it |
| no tag `v<version>` | `clojure -T:build tag` |
| tag does not point at `HEAD` | tag the commit you are actually shipping |
| no build-provenance in the jar | run `clojure -T:build ci`; `deploy` does not build |
| jar was built at another version | `target/` is stale; run `ci` |
| jar was built from another commit | `target/` is stale; run `ci` |
| jar was built from a dirty tree | commit, then `ci` again |
| `CLOJARS_PASSWORD` unset | pass the group-scoped token, rather than finding out as a 401 |

`:force true` skips all of it. There is no good reason to use it; every refusal above names its own fix.

## Quality gates

`release` runs the test suite through `ci` and stops on failure. The rest are not wired into the build because they need a URPX checkout or a linter that is not a project dependency, so run them by hand before tagging:

```bash
clj-kondo --lint src test dev
clojure -M:validate test/resources/urpx-fixtures/*.jsonld
scripts/shacl-drift.sh [path-to-urpx-checkout]    # defaults to $URPX_DIR
markdownlint-cli2 README.md CHANGELOG.md 'doc/*.md'
```

`markdownlint-cli2` reads its rules from the repo's `.markdownlint-cli2.jsonc`. `shacl-drift.sh` needs `rdflib` (see [CONTRIBUTING](../CONTRIBUTING.md#configuration) for `URPX_DIR` and `PYTHON`), and exits `2` rather than `0` when it could not compare anything, so a run that checked nothing does not read as clean.

## What lands in the jar

Beyond the sources: the MIT notice at `META-INF/LICENSE`, Markdown API docs for every namespace plus the `doc/` guides and `CHANGELOG.md`, under `docs/energy.grid-coordination/clj-urpx/`, and build provenance at `META-INF/energy.grid-coordination/clj-urpx/build-provenance.{json,edn}`. Both are produced by `ci`, which is the other reason `deploy` alone is not a release.

To read the provenance of a published artifact:

```bash
unzip -p clj-urpx-X.Y.Z.jar META-INF/energy.grid-coordination/clj-urpx/build-provenance.edn
```

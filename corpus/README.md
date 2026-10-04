<!--
SPDX-FileCopyrightText: 2026 Clark Communications Corporation
SPDX-License-Identifier: MIT
-->

# Example corpus walkers

Each file here finds the URPX filings in one corpus's directory layout and returns them as sources. That is all they do. Turning sources into documents is `urpx.load`'s job and is the same everywhere; finding them is not, and never will be.

**None of this is library code.** `corpus/` is not on the library's `:paths`, nothing in `src/` requires it, and none of it ships in the jar. These are worked examples, and they are here rather than in prose because a layout is knowledge that goes stale, and code that is run goes stale loudly.

| Walker | Corpus |
|---|---|
| `urpx-upstream` | the URPX standard's own `test-cases/` in [urpx-org/urpx](https://github.com/urpx-org/urpx), which exercise shapes no single publisher does |

## Why a walker rather than a glob

Globbing every `.jsonld` under a corpus root looks equivalent and is not. In a URPX checkout older than upstream `ebde5871`, or in urpx-org/urpx-dev, a glob **crashes**: `dev-docs/samples` carries an instant written `2026-01-01T06:00:00Z`, coercion refuses it, and the error names no file. In a corpus that keeps test fixtures or deliberately invalid documents beside its filings, a glob counts them too and **nothing errors**: a plausible number rather than a refusal. Neither failure tells you the layout was the problem.

## Adding one

A walker is one function:

```clojure
(defn sources
  "Every production filing under `root`, as Files."
  [root]
  ...)
```

Name the namespace after the corpus with no prefix: `corpus/` is itself the classpath root, so a `corpus.*` namespace would have to live at `corpus/corpus/`. This matches `dev/`, whose namespaces are `drift`, `schema-facts` and `corpus-sweep`.

Return sources, not documents: anything `clojure.java.io/reader` coerces will do, and `urpx.load/document-xf` takes it from there. Sort the result so a count is reproducible. Put what you know about the layout in the docstring, including what a naive walk would wrongly include, because that is the part a reader cannot recover from the code.

Run one with:

```bash
clojure -M:dev:corpus -m corpus-sweep <path-to-urpx> --layout urpx-upstream
```

`--layout <ns>` requires `<ns>` and calls `<ns>/sources`, so a walker kept elsewhere works the same way once it is on the classpath. For a walker in a directory with its own `deps.edn` (`{:paths ["."]}` will do):

```bash
clojure -Sdeps '{:deps {my/walkers {:local/root "/path/to/walkers"}}}' \
  -M:dev:corpus -m corpus-sweep <path-to-corpus> --layout my-corpus
```

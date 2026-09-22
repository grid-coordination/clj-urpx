<!--
SPDX-FileCopyrightText: 2026 Clark Communications Corporation
SPDX-License-Identifier: MIT
-->

# Example corpus walkers

Each file here finds the URPX filings in one corpus's directory layout and returns them as sources. That is all they do. Turning sources into documents is `urpx.load`'s job and is the same everywhere; finding them is not, and never will be.

**None of this is library code.** `corpus/` is not on the library's `:paths`, nothing in `src/` requires it, and none of it ships in the jar. These are worked examples, and they are here rather than in prose because a layout is knowledge that goes stale, and code that is run goes stale loudly.

| Walker | Corpus |
|---|---|
| `urpx-rate-plans` | [urpx-rate-plans](https://github.com/grid-coordination/urpx-rate-plans), California residential electric filings |

## Why a walker rather than a glob

Globbing every `.jsonld` under a corpus root looks equivalent and is not. In urpx-rate-plans it picks up 76 files where the corpus holds 73, because `dev/fixtures/` contains documents that are not filings — one of them deliberately non-conforming, so the corpus's own gate has something to fail on. Nothing errors. The counts are simply about something other than the corpus, which is the failure mode this repository keeps finding: a plausible number rather than a refusal.

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
clojure -M:dev:corpus -m corpus-sweep <path-to-corpus> --layout urpx-rate-plans
```

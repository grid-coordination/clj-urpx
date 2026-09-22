;; SPDX-FileCopyrightText: 2026 Clark Communications Corporation
;; SPDX-License-Identifier: MIT
(ns urpx-rate-plans
  "A source walker for the urpx-rate-plans corpus layout.

  EXAMPLE, NOT LIBRARY. Nothing in src/ requires this and nothing here is
  published in the jar. The namespace is top-level rather than `corpus.*`
  because `corpus/` is itself the classpath root, the same convention `dev/`
  already follows with `drift`, `schema-facts` and `corpus-sweep`.

  It exists because the division of labour is easier to show than to state:
  `urpx.load` turns sources into documents and that part is the same
  everywhere, while finding the sources depends on a layout that is a one-off
  per corpus and always will be.

  THE LAYOUT, as of urpx-rate-plans 9deae4dbaa8b:

      us/<region>/<utility>/<commodity>/<plan>/versions/<date>/<stem>.jsonld
      dev/fixtures/*.jsonld

  Production filings live under `us/` alone: 73 files. `dev/fixtures/` holds 3
  documents that are NOT filings, and one of them, v051-missing-required.jsonld,
  is deliberately non-conforming so that the corpus's own gate has something to
  fail on.

  WHICH IS WHY A NAIVE GLOB IS WRONG, and wrong quietly. Globbing the repository
  root yields 76 files and 87 documents where the corpus holds 73 and 84, and
  the three extra include a file built to be invalid. Nothing errors: the counts
  are simply about something other than the corpus. A layout is knowledge, and
  the place to put it is next to the layout it describes."
  (:require [babashka.fs :as fs]))

(defn sources
  "Every production filing under `root`, as Files, sorted for reproducibility.

   `us/` only, deliberately. See the namespace docstring for what globbing the
   whole tree picks up instead."
  [root]
  (->> (fs/glob (fs/file root "us") "**/*.jsonld")
       (map fs/file)
       (sort-by str)
       vec))

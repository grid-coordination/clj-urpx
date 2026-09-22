;; SPDX-FileCopyrightText: 2026 Clark Communications Corporation
;; SPDX-License-Identifier: MIT
(ns urpx-upstream
  "A source walker for the URPX standard's own test-case layout.

  The upstream repository at urpx-org/urpx ships worked test cases, and they
  are a corpus like any other: a second layout, with its own conventions, which
  is the whole reason `corpus/` holds walkers rather than one function.

  THE LAYOUT:

      test-cases/<case>/data/*.jsonld     the filings
      dev-docs/samples/**/*.jsonld        price series and samples, NOT filings
      context/urpx-context.jsonld         a JSON-LD @context document

  A NAIVE GLOB DOES NOT MISCOUNT HERE, IT CRASHES. Globbing the repository root
  pulls in dev-docs/samples, and one of those carries an instant written
  \"2026-01-01T06:00:00Z\"; coercion refuses it, so the whole load fails with a
  date-parse error that names no file. The urpx-rate-plans walker exists because
  a glob quietly counts the wrong things; this one exists because a glob stops
  the run dead, and neither failure tells you the layout was the problem.

  IT EXERCISES SHAPES THE PRODUCTION CORPUS DOES NOT. Of 45 expanded entities in
  the 12 test-case files, 7 are documents this library models and 33 are not:
  urpx:Organization 11, urpx:TariffFiling 6, urpx:PricingModel, urpx:ServiceClass,
  urpx:RateGroup, urpx:RatePlanIdentifier, urpx:UtilityOperator, and 11 carrying
  NO @type at all. Three are top-level urpx:RatePlanVersions rather than versions
  nested in a plan, which is a shape the production corpus never uses.

  So this is the walker to reach for when checking whether a change handles the
  standard's own examples and not only one publisher's filings. Use
  `urpx.load/scan` rather than `documents` against it: with 33 of 45 entities
  discarded, what was dropped is the interesting half.

  READ-ONLY, and from a LOCAL CHECKOUT. Nothing here fetches anything, and
  nothing in this repository interacts with urpx-org/urpx."
  (:require [babashka.fs :as fs]))

(defn sources
  "Every test-case filing under `root`, as Files, sorted for reproducibility.

   `test-cases/*/data/` only. See the namespace docstring for what globbing the
   whole tree pulls in instead, and why it fails rather than miscounts."
  [root]
  (->> (fs/glob (fs/file root "test-cases") "*/data/*.jsonld")
       (map fs/file)
       (sort-by str)
       vec))

;; SPDX-FileCopyrightText: 2026 Clark Communications Corporation
;; SPDX-License-Identifier: MIT
(ns urpx-upstream
  "A source walker for the URPX standard's own test-case layout.

  The upstream repository at urpx-org/urpx ships worked test cases, and they
  are a corpus like any other, with a layout of their own.

  THE LAYOUT:

      test-cases/<case>/data/*.jsonld     the filings
      context/urpx-context.jsonld         a JSON-LD @context document

  A NAIVE GLOB OVER AN OLDER CHECKOUT CRASHES. Before upstream ebde5871 the
  repository also carried dev-docs/samples/**/*.jsonld, price series and samples
  rather than filings, and one of those carries an instant written
  \"2026-01-01T06:00:00Z\"; coercion refuses it, so the whole load fails with a
  date-parse error that names no file. Those samples moved to urpx-org/urpx-dev
  at that commit. A glob also reaches context/urpx-context.jsonld, which is a
  @context document rather than a filing.

  IT EXERCISES SHAPES A SINGLE PUBLISHER'S FILINGS MAY NOT. The test cases carry
  entities this library does not model (urpx:Organization, urpx:TariffFiling,
  urpx:PricingModel, urpx:ServiceClass, urpx:RateGroup,
  urpx:RatePlanIdentifier, urpx:UtilityOperator), entities carrying NO @type at
  all, and top-level urpx:RatePlanVersions rather than versions nested in a
  plan.

  So this is the walker to reach for when checking whether a change handles the
  standard's own examples and not only one publisher's filings. Use
  `urpx.load/scan` rather than `documents` against it: what it discards is the
  interesting half.

  READ-ONLY, and from a LOCAL CHECKOUT. Nothing here fetches anything, and
  nothing in this repository interacts with urpx-org/urpx."
  (:require [babashka.fs :as fs]))

(defn sources
  "Every test-case filing under `root`, as Files, sorted for reproducibility.

   `test-cases/*/data/` only. See the namespace docstring for what globbing the
   whole tree pulls in instead, and why it has failed rather than miscounted."
  [root]
  (->> (fs/glob (fs/file root "test-cases") "*/data/*.jsonld")
       (map fs/file)
       (sort-by str)
       vec))

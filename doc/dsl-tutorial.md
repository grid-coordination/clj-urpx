<!--
SPDX-FileCopyrightText: 2026 Clark Communications Corporation
SPDX-License-Identifier: MIT
-->

# Authoring URPX entities in Clojure: a worked tutorial

This page mirrors selected examples from the upstream [URPX Tutorial](https://github.com/urpx-org/urpx/blob/main/documentation/pages/urpx-tutorial.md) and shows the equivalent Clojure form using `urpx.dsl`. Each section presents the URPX JSON-LD shape (as authored upstream) followed by the same entity built via the DSL — same logical content, less typing, no `@type` boilerplate, and types that auto-coerce from raw Clojure inputs.

The point isn't to replace the upstream tutorial — it's to make the DSL feel concrete by anchoring its idioms to canonical URPX shapes a tutorial reader has already seen.

```clojure
(require '[urpx.dsl :as dsl])
```

## Example 1: a Season

URPX `Season` entities declare a season name and the months it covers ([upstream §Complete TOU Structure](https://github.com/urpx-org/urpx/blob/main/documentation/pages/urpx-tutorial.md#complete-tou-structure)). Upstream JSON-LD:

```json
{
  "@id": "ex:season-summer",
  "@type": "Season",
  "seasonName": "Summer",
  "month": ["--06", "--07", "--08", "--09"],
  "yearlyRecurrence": true
}
```

Via the DSL — auto-coercion of `--MM` strings into ints, kebab-case kwargs, `@type` inferred from the schema:

```clojure
(dsl/season
  :jsonld/id "ex:season-summer"
  :season-name "Summer"
  :month ["--06" "--07" "--08" "--09"]
  :yearly-recurrence true)
;; =>
;; {:jsonld/type "urpx:Season"
;;  :jsonld/id   "ex:season-summer"
;;  :urpx/seasonName "Summer"
;;  :urpx/month [6 7 8 9]
;;  :urpx/yearlyRecurrence true}
```

You can pass plain ints just as easily — both shapes route through the schema's `:decode/urpx-jsonld` decoders:

```clojure
(dsl/season :season-name "Summer" :month [6 7 8 9])
```

## Example 2: a MetricInput with a nested MetricSpecification

The upstream tutorial's basic plan declares an energy `MetricInput` that nests a `MetricSpecification` describing the commodity, measurement kind, and unit ([upstream §Step 2: Rate Plan Identity and Version](https://github.com/urpx-org/urpx/blob/main/documentation/pages/urpx-tutorial.md#step-2-rate-plan-identity-and-version)):

```json
{
  "@id": "ex:metric-input-energy",
  "@type": "MetricInput",
  "name": "Standard Energy Measurement",
  "metricInputType": { "@id": "urpx:measuredMetricInput" },
  "hasMetricSpecification": {
    "@id": "ex:metric-spec-energy-supply",
    "@type": "MetricSpecification",
    "commodity": {"@id": "urpx:electricity"},
    "measurementKind": {"@id": "urpx:energy"},
    "unitOfMeasure": {"@id": "urpx:kWh"}
  }
}
```

Via the DSL — nested entities are themselves builder calls, and Refs are plain `{:jsonld/id …}` maps:

```clojure
(dsl/metric-input
  :jsonld/id "ex:metric-input-energy"
  :name "Standard Energy Measurement"
  :metric-input-type {:jsonld/id "urpx:measuredMetricInput"}
  :has-metric-specification
  (dsl/metric-specification
    :jsonld/id "ex:metric-spec-energy-supply"
    :commodity        {:jsonld/id "urpx:electricity"}
    :measurement-kind {:jsonld/id "urpx:energy"}
    :unit-of-measure  {:jsonld/id "urpx:kWh"}))
```

## Example 3: a TOU structure with local-symbol refs

The upstream tutorial's TOU section nests deeply: `BillingModel → TOUSchedule → TOUTier → TOUPeriod → TimeBracket`, with the bracket referencing a `Season` declared elsewhere ([upstream §Complete TOU Structure](https://github.com/urpx-org/urpx/blob/main/documentation/pages/urpx-tutorial.md#complete-tou-structure)). Upstream JSON-LD repeats `@id` strings to wire everything together:

```json
{
  "@type": "TOUTier",
  "touTierNumber": 1,
  "touTierStandardName": {"@id": "urpx:peakTier"},
  "hasTOUPeriod": {
    "@id": "ex:period-peak",
    "@type": "TOUPeriod",
    "touPeriodNumber": 1,
    "hasTimeBracket": {
      "@type": "TimeBracket",
      "startTime": "14:00:00",
      "endTime":   "19:00:00",
      "appliesInSeason": {"@id": "ex:season-summer"},
      "dayType": {"@id": "urpx:weekdayDays"},
      "includeHolidays": false
    }
  }
}
```

Via the DSL inside a `dsl-scope`, `::summer` gets registered when the season is declared and resolves to a Ref everywhere it's used downstream — no `@id`-string repetition:

```clojure
(dsl/dsl-scope
  (dsl/season ::summer :season-name "Summer" :month [6 7 8 9])
  (dsl/tou-tier
    :tou-tier-number 1
    :tou-tier-standard-name {:jsonld/id "urpx:peakTier"}
    :has-tou-period
    [(dsl/tou-period
       :tou-period-number 1
       :name "Summer Peak"
       :has-time-bracket
       [(dsl/time-bracket
          :start-time "14:00:00"
          :end-time   "19:00:00"
          :applies-in-season ::summer            ; ← resolved against the scope
          :day-type {:jsonld/id "urpx:weekdayDays"}
          :include-holidays false)])]))
```

ISO time strings auto-coerce to `LocalTime`; the `::summer` keyword resolves to `{:jsonld/id "summer"}`. Typo a ref name (e.g. `::summers`) and the DSL throws `ex-info` with the registered keys — fail-loud is a feature.

## Example 4: a small but complete rate plan

Pulling it together: a non-TOU two-season plan modeled on the published CPAU E-2 schedule, authored entirely via the DSL ([upstream §Creating a Simple Rate Plan](https://github.com/urpx-org/urpx/blob/main/documentation/pages/urpx-tutorial.md#creating-a-simple-rate-plan) walks through the equivalent JSON-LD construction in six steps). Same shape the test suite uses to verify end-to-end round-trip ([`urpx.dsl-test/cpau-e-2-via-dsl`](../test/urpx/dsl_test.clj)).

```clojure
(dsl/dsl-scope
  (let [summer (dsl/season ::summer :season-name "Summer" :month [5 6 7 8 9 10])
        winter (dsl/season ::winter :season-name "Winter" :month [11 12 1 2 3 4])

        mi-energy (dsl/metric-input ::mi-energy
                                    :name "kWh"
                                    :metric-input-type {:jsonld/id "urpx:profileMetricInput"}
                                    :has-metric-specification
                                    (dsl/metric-specification
                                     :measurement-kind {:jsonld/id "urpx:energy"}
                                     :unit-of-measure  {:jsonld/id "urpx:kWh"}))

        l-comm (dsl/ledger ::ledger-commodity
                           :name "Commodity"
                           :ledger-type {:jsonld/id "urpx:supplyLedger"}
                           :references-metric-input [::mi-energy])

        pd-cs (dsl/price-definition ::pd-commodity-summer
                                    :name "Commodity Summer"
                                    :applies-in-season ::summer
                                    :references-ledger ::ledger-commodity)

        pd-cw (dsl/price-definition ::pd-commodity-winter
                                    :name "Commodity Winter"
                                    :applies-in-season ::winter
                                    :references-ledger ::ledger-commodity)]
    (dsl/rate-plan
     :rate-plan-unique-identifier "TUTORIAL-CPAU-E-2"
     :name "Tutorial: non-TOU two-season plan"
     :initial-effective-date "2025-07-01"          ; ← string auto-coerces to LocalDate
     :has-rate-plan-version
     (dsl/rate-plan-version
      ;; changeReason and serviceRole are sh:minCount 1 on the version shape and
      ;; have been since URPX v0.2.1, so the builder requires them; it validates
      ;; on construct, so omitting either throws here rather than downstream.
      :change-reason {:jsonld/id "urpx:rateCase"}
      :service-role {:jsonld/id "urpx:bundledServiceRole"}
      :effective-date-time "2025-07-01T00:00:00"   ; ← string auto-coerces to LocalDateTime
      :price-currency "USD"
      :has-plan-elements
      (dsl/plan-elements
       :has-seasons      [summer winter]
       :has-metric-input [mi-energy]
       :has-billing-model
       (dsl/billing-model
        :has-ledger            [l-comm]
        :has-price-definition  [pd-cs pd-cw]))
      :has-prices
      (dsl/prices
       :has-price-set
       [(dsl/price-set
         :has-price [(dsl/price :references-price-definition ::pd-commodity-summer
                                :unit-price 0.15075       ; ← number auto-coerces to BigDecimal
                                :start-date "2025-07-01")
                     (dsl/price :references-price-definition ::pd-commodity-winter
                                :unit-price 0.09334
                                :start-date "2025-07-01")])])))))
```

The result is a fully-coerced `urpx:RatePlan` ready for `urpx.price/resolve-prices` or `urpx.emit/write-rate-plan`. The test suite verifies that the round-trip — `dsl → emit → re-parse → re-coerce` — produces a structurally equal plan.

This example authors the **v0.2.x** nested shape, which URPX v0.5.1 still permits. To author the v0.5.1 shape instead, put the snapshot at the top level and state the link upward with `:for-rate-plan`, add `:has-coverage-claim`, and wrap in `dsl/urpx-package` rather than `dsl/urpx-document`. Both load through `urpx.core/load-rate-plan` without the consumer caring which was used.

## Where to look next

- **`urpx.dsl` ns docstring** — concise reference for `dsl-scope`, `make-builder`, the kwarg-alias rule, and the validation contract.
- **[Authoring URPX entities](../README.md#authoring-urpx-entities)** in the project README — the API surface in summary.
- **[`urpx.dsl-test`](../test/urpx/dsl_test.clj)** — runnable examples covering every DSL feature, including the full CPAU E-2 round-trip referenced above.
- **Upstream [URPX Tutorial](https://github.com/urpx-org/urpx/blob/main/documentation/pages/urpx-tutorial.md)** — for the canonical URPX shapes these examples mirror.

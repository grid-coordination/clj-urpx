# clj-urpx

[![Clojars Project](https://img.shields.io/clojars/v/energy.grid-coordination/clj-urpx.svg)](https://clojars.org/energy.grid-coordination/clj-urpx)
[![md-docs](https://img.shields.io/badge/md--docs-included-green)](https://github.com/dcj/codox-md)
[![build-provenance](https://img.shields.io/badge/build--provenance-included-blue)](https://github.com/dcj/build-provenance)

Clojure library for [URPX](https://lfenergy.org/projects/utility-rate-plan-exchange-urpx/) (Utility Rate Plan Exchange) — parse, validate, and resolve prices from URPX rate plan documents (JSON-LD).

## What is URPX?

URPX is an [LF Energy](https://lfenergy.org/) open standard for exchanging utility rate plan information using semantic web technologies (RDF, OWL, SHACL, JSON-LD). It enables precise, machine-readable data exchange between utilities, regulators, vendors, and researchers.

## What does this library do?

- **Parse** URPX JSON-LD rate plan documents into Clojure data structures
- **Validate & coerce** parsed data against [Malli](https://github.com/metosin/malli) schemas — string leaves become `BigDecimal`, `LocalDate`, `LocalTime`, `LocalDateTime`, `Duration`, `Period`, integer month numbers
- **Resolve** the per-ledger, per-tier prices that apply at a given instant in a given timezone — TOU period, season, day-of-week, midnight-wrapping brackets, block-tier ladders
- **Generate** contiguous price-interval schedules suitable for [OpenADR 3](https://www.openadr.org/) events
- **Emit** URPX JSON-LD from coerced Clojure entities (the inverse of parse + coerce) — useful for round-trip validation and programmatic authoring of URPX documents

## Installation

`deps.edn`:

```clojure
{:deps {energy.grid-coordination/clj-urpx
        {:git/url "https://github.com/grid-coordination/clj-urpx.git"
         :git/sha "..."}}}
```

## Usage

```clojure
(require '[urpx.core :as core]
         '[urpx.coerce :as coerce]
         '[urpx.price :as price]
         '[urpx.schedule :as schedule])
(import '[java.time LocalDate ZoneId ZonedDateTime])

;; 1. Load and coerce a URPX rate plan document. v0.2.0 documents are
;;    typically @graph bundles holding a urpx:RatePlan plus sibling
;;    urpx:RatePlanModifier and metadata entities; load-rate-plan picks
;;    the unique RatePlan from the bundle.
(def plan
  (-> "path/to/urpx-rate-plan.jsonld"
      core/load-rate-plan
      coerce/coerce-rate-plan))

;; 2. Resolve the prices that apply at a specific instant
(def la (ZoneId/of "America/Los_Angeles"))
(price/resolve-prices plan (ZonedDateTime/of 2026 7 15 17 0 0 0 la))
;; =>
;; {:urpx.resolved/season-name        "Summer"
;;  :urpx.resolved/tou-period-name    "Summer Peak Period"
;;  :urpx.resolved/tou-period-number  1
;;  :urpx.resolved/ledgers
;;  [{:urpx.resolved/ledger-name "Energy Supply Prices"
;;    :urpx.resolved/tiers
;;    [{:urpx.resolved/price-name "Generation Summer Peak"
;;      :urpx.resolved/unit-price 0.20782M ...}
;;     {:urpx.resolved/price-name "Bundled Power Charge Indifference Adjustment"
;;      :urpx.resolved/unit-price -0.01011M ...}]}
;;   {:urpx.resolved/ledger-name "Energy Delivery Prices"
;;    :urpx.resolved/tiers [{:urpx.resolved/unit-price 0.20388M ...} ...]}
;;   {:urpx.resolved/ledger-name "Adjustment Prices" ...}]}

;; 3. Marginal $/kWh — sum of the lowest-tier unit-price across all ledgers
(price/marginal-unit-rate *1)

;; 4. Generate a day's worth of natural TOU intervals
(def start (.toInstant (.atStartOfDay (LocalDate/of 2026 7 15) la)))
(schedule/price-schedule-days plan start 1 la)
;; =>
;; [{:tick/beginning #inst "..." :tick/end #inst "..."
;;   :urpx.interval/resolved {... "Summer Off-Peak Period" ...}}
;;  {... 16:00–21:00 "Summer Peak Period" ...}
;;  {... 21:00–24:00 "Summer Off-Peak Period" ...}]
```

For block-tiered plans (e.g. PG&E E-1), each ledger's `:tiers` vector exposes every tier with its `:tier-lower-bound`, `:tier-upper-bound`, and `:unit-price` — the caller selects the active tier from cumulative billing-period usage.

### Timezone

`price-schedule` and `price-schedule-days` need a `ZoneId` to walk a window in local time. When the rate plan declares `urpx:timezoneIdentifier` (an IANA string, e.g. `"America/Los_Angeles"`), the zone arg can be omitted:

```clojure
(schedule/price-schedule-days plan start 1)            ; uses plan's declared zone
(schedule/price-schedule-days plan start 1 la)         ; explicit override
```

When neither is available, an `ex-info` is thrown rather than silently falling back to the system default. `resolve-prices` takes a `ZonedDateTime` directly, so its zone always comes from the caller.

For documents that omit `urpx:timezoneIdentifier`, the optional `urpx.tz-inference/infer-zone` helper does best-effort inference from the publishing utility's `urpx:legalName` (and a caller-supplied `urpx:eiaId` table when available). It returns `nil` for unknown or multi-zone utilities so the caller fails loudly rather than silently picking one. Inference is **opt-in** — `price-schedule` does not call it automatically; compose at the call site:

```clojure
(require '[urpx.tz-inference :as tz])
(let [zone (or (some-> plan :urpx/timezoneIdentifier ZoneId/of)
               (:zone (tz/infer-zone plan))
               (throw (ex-info "no timezone for this plan" {})))]
  (schedule/price-schedule plan start end zone))
```

The shipped lookup tables only cover utilities used by the test suite; production deployments should supply their own (`:eia-id->zone` / `:legal-name->zone` opts).

### Holiday calendar

URPX `TimeBracket` carries two optional booleans — `urpx:includeHolidays` and `urpx:includeNonHolidays` — that gate whether the bracket applies on holidays, non-holidays, or both (both default to `true` when omitted, matching every day). To resolve a plan that uses these gates (e.g. PG&E E-TOU-D's "weekday peak EXCLUDING holidays" pattern), pass a `:holiday?` predicate (`LocalDate -> bool`). A plain set of `LocalDate`s works because sets are functions:

```clojure
(import '[java.time LocalDate])

(def us-federal-2026
  #{(LocalDate/of 2026 1 1)    ; New Year's Day
    (LocalDate/of 2026 1 19)   ; Martin Luther King Jr. Day
    (LocalDate/of 2026 2 16)   ; Presidents Day
    (LocalDate/of 2026 5 25)   ; Memorial Day
    (LocalDate/of 2026 6 19)   ; Juneteenth
    (LocalDate/of 2026 7 3)    ; Independence Day (observed)
    (LocalDate/of 2026 9 7)    ; Labor Day
    (LocalDate/of 2026 11 11)  ; Veterans Day
    (LocalDate/of 2026 11 26)  ; Thanksgiving
    (LocalDate/of 2026 12 25)})  ; Christmas

(price/resolve-prices plan zdt {:holiday? us-federal-2026})
(schedule/price-schedule plan start end la {:holiday? us-federal-2026})
```

You can also pass any `(LocalDate -> bool)` function — e.g. wrap a calendar service. Without `:holiday?`, every date is treated as a non-holiday — so brackets that opt out of non-holidays (`includeNonHolidays: false`, e.g. holiday-only off-peak overrides) will never match.

### Modifiers

`urpx:RatePlanModifier` documents (vintage-PCIA adders, export-compensation adjusters, etc.) augment a base plan. v0.2.0 typically bundles a base plan and its modifiers in a single `@graph` document; pull the modifier with `core/load-graph`:

```clojure
(def entities (core/load-graph "path/to/urpx-rate-plan.jsonld"))
(def modifier
  (->> entities
       (filter #(= "urpx:RatePlanModifier" (:jsonld/type %)))
       first
       coerce/coerce-rate-plan-modifier))

(price/resolve-prices-with-modifiers plan [modifier] zdt)
;; => the resolve-prices output, with each applicable modifier's ledgers
;;    appended to :urpx.resolved/ledgers
```

A modifier is skipped silently when its `:urpx/applicableToRatePlan` list does not include the base plan's `@id`. Matrix-style modifiers (e.g. PG&E vintage-PCIA — one ledger with a price per departure-vintage year) surface every active price under one ledger entry; the caller picks the active value via `:urpx.resolved/price-id` (each Price's `urpx:hasApplicabilityGroup` carries the v0.2.0 `urpx:ConditionExpression` that names the dimension being matched).

### Sub-hour granularity

`price-schedule` walks at hourly steps by default. For rate plans that define sub-hour TOU bracket transitions, pass `:step`:

```clojure
(import '[java.time Duration])
(schedule/price-schedule plan start end la {:step (Duration/ofMinutes 15)})
```

Adjacent steps with identical resolved ledgers are still merged, so a quarter-hour walk across an hour-aligned plan produces the same merged intervals as the hourly walk.

## Writing URPX JSON-LD

The library also supports the inverse direction: emit URPX JSON-LD from a coerced Clojure entity. Useful for round-trip validation, programmatic generation of URPX documents, and authoring fixtures in idiomatic Clojure.

```clojure
(require '[urpx.emit :as emit])

;; Round-trip a parsed rate plan back to JSON-LD
(spit "out.jsonld" (emit/write-rate-plan plan))

;; Standalone modifier documents
(emit/write-rate-plan-modifier modifier)

;; URPXDocument-wrapped documents (v0.2.0 wrapper class)
(emit/write-urpx-document
  {:jsonld/type "urpx:URPXDocument"
   :urpx/targetURPXVersion "https://github.com/urpx-org/urpx/raw/v0.2.0/ontology/urpx-ontology.ttl"
   :urpx/hasRatePlan [plan]})
```

Each function takes a coerced (or already-typed EDN) entity, runs the schema's `:encode/urpx-jsonld` transformer to convert typed leaves (`BigDecimal`, `LocalDate`, `LocalDateTime`, `Duration`, integer month numbers, …) back to JSON-LD string forms, projects namespaced-keyword keys to their CURIE / `@`-reserved JSON-LD forms, and serializes via `clojure.data.json/write-str`.

### Round-trip fidelity

The emit path targets **logical / RDF-graph equivalence** on round-trip: `parse → coerce → emit → re-parse → re-coerce` yields a structurally equal Clojure entity. Byte-for-byte equality with the input is **not** a goal — key ordering, single-vs-vector property emission, and `@context` recreation may differ from the source document.

The smoke suite in `urpx.emit-test` confirms the pipeline against the vendored PG&E E-1 fixture (a ~20K-character `@graph` document with tier-stratified prices and a sibling vintage-PCIA modifier) and a URPXDocument-wrapped E-ELEC document — both round-trip cleanly with `(= original re-coerced)`.

### Limitations / future work

- **`@context` emission** — emitted JSON-LD currently carries no synthesized `@context`; consumers either supply one outside the emitter or work with the bare CURIE keys. A future revision will add an opt-in default context derived from the vendored fixtures' shape.
- **Broader fixture coverage** — round-trip testing today is focused on the smoke cases above; a dedicated round-trip suite over every vendored fixture is planned. Equality is plain `=`, which is *stricter* than logical equivalence (vector ordering, BigDecimal scale all matter); a more tolerant comparator may be introduced for shapes where SHACL doesn't constrain order.

## Authoring URPX entities

For programmatic construction or test-fixture authoring, `urpx.dsl` provides idiomatic Clojure builders for every entity-type schema in `urpx.schema`. Output is a coerced (typed) entity, ready for `urpx.price/resolve-prices`, `urpx.emit/write-*`, or any other consumer.

```clojure
(require '[urpx.dsl :as dsl])

(dsl/dsl-scope
  (let [summer (dsl/season ::summer :season-name "Summer" :month [5 6 7 8 9 10])
        winter (dsl/season ::winter :season-name "Winter" :month [11 12 1 2 3 4])
        ledger-comm (dsl/ledger ::ledger-commodity
                                :name "Commodity"
                                :ledger-type {:jsonld/id "urpx:supplyLedger"})
        pd-cs (dsl/price-definition ::pd-commodity-summer
                                    :name "Commodity Summer"
                                    :applies-in-season ::summer
                                    :references-ledger ::ledger-commodity)]
    ;; ...assemble into a rate-plan via dsl/rate-plan, dsl/rate-plan-version, etc.
    ))
```

The DSL handles the boring parts so authoring code stays declarative:

- **`@type` is inferred** from the schema (no `:jsonld/type "urpx:Season"` boilerplate).
- **Auto-coercion** of raw Clojure inputs through the schema's `:decode/urpx-jsonld` decoders: numbers → `BigDecimal`, ISO strings → `LocalDate` / `LocalDateTime` / `LocalTime`, `--MM` xsd:gMonth → int.
- **Validation on construct** — every builder throws `ex-info` with the Malli explanation if the entity doesn't validate against its schema, so shape drift surfaces at the call site.
- **Two kwarg forms** — canonical `:urpx/seasonName` or auto-derived kebab-case `:season-name` (both work, can be mixed in one call).

### Local-symbol refs

Inside a `dsl-scope`, builders accept an optional leading qualified-keyword as the entity's local `@id`, and qualified-keyword values in kwarg positions resolve to `{:jsonld/id …}` Ref maps via the scope's registry:

```clojure
(dsl/dsl-scope
  (dsl/season ::summer :season-name "Summer" :month [5 6 7 8 9 10])
  (dsl/season ::winter :season-name "Winter" :month [11 12 1 2 3 4])
  (dsl/price-definition ::pd-summer
                        :applies-in-season ::summer    ; → {:jsonld/id "summer"}
                        :references-ledger ::commodity))
```

Resolution is eager (declare-before-reference); unknown refs and duplicate declarations throw. Each `dsl-scope` has its own registry — scopes don't leak.

### Building from arbitrary schemas

`make-builder` derives a constructor from any Malli schema with a `:jsonld/type [:= "urpx:X"]` entry — useful for ad-hoc construction or scripts that don't import every named builder:

```clojure
(let [build-tou-period (dsl/make-builder schema/TOUPeriod)]
  (build-tou-period :urpx/name "Summer Peak"
                    :urpx/touPeriodNumber 1
                    :urpx/hasTimeBracket []))
```

The 37 named builders (`season`, `metric-input`, `price-definition`, `rate-plan`, `urpx-document`, …) are each just `(def name (make-builder schema/X))`.

For a longer worked walkthrough that mirrors selected examples from the upstream [URPX Tutorial](https://github.com/urpx-org/urpx/blob/main/documentation/pages/urpx-tutorial.md), see [`doc/dsl-tutorial.md`](doc/dsl-tutorial.md) — each example shows the canonical URPX JSON-LD shape and the equivalent DSL form, with deep-links back to the upstream source section.

## Namespace overview

| Namespace        | Purpose |
|------------------|---------|
| `urpx.core`          | JSON-LD parsing and loading (`parse-rate-plan`, `load-rate-plan`, `parse-graph`, `load-graph`) |
| `urpx.schema`    | Malli schemas for the coerced URPX entity model |
| `urpx.coerce`    | Schema-driven coercion: strings → typed values; `validate`, `explain` |
| `urpx.index`     | `build-index`, `resolve-ref`, `get-entity` for `@id` references |
| `urpx.price`         | `resolve-prices`, `resolve-modifier-prices`, `resolve-prices-with-modifiers`, `marginal-unit-rate` |
| `urpx.schedule`      | `price-schedule`, `price-schedule-days` |
| `urpx.tz-inference`  | `infer-zone` (opt-in fallback when `urpx:timezoneIdentifier` is absent) |
| `urpx.emit`          | Write-side: `write-rate-plan`, `write-rate-plan-modifier`, `write-urpx-document` |
| `urpx.dsl`           | Authoring DSL: `dsl-scope`, `make-builder`, named builders for every entity type |

## Status

Targets **URPX v0.2.0**.

Test fixtures are vendored from [urpx-tariffs](https://github.com/grid-coordination/urpx-tariffs) (the production rate-plan corpus) at pinned commits — see [`test/resources/urpx-fixtures/FIXTURES.md`](test/resources/urpx-fixtures/FIXTURES.md) for the table and the re-sync workflow.

Currently covered:
- Both top-level document types: `urpx:RatePlan` and `urpx:RatePlanModifier` parse, coerce, and validate against v0.2.0 Malli schemas — vendored fixtures cover PG&E E-1 (block-tiered), E-TOU-C (simple TOU), E-TOU-D (TOU + holiday calendar), and EV2, each bundled with a sibling vintage-PCIA modifier in a JSON-LD `@graph`
- v0.2.0 `urpx:ConditionExpression` tree (Comparison/Boolean nodes with Object/Datatype/Custom operand leaves) parses and coerces; recursive operands are validated through a local Malli registry
- v0.2.0 `BillingModel.hasPriceDefinition` traversal (PD now lives at the BillingModel level with `referencesLedger` pointing back) for price resolution
- TOU period resolution: seasons, ontology day-types (`urpx:allDays`, `urpx:weekdayDays`, `urpx:weekendDays`, `urpx:customDays`), `urpx:includeHolidays` / `urpx:includeNonHolidays` bracket gates, time-bracket containment with midnight wrap
- Block-tier surfacing via the v0.2.0 `urpx:ServiceTiers` container — every tier surfaces with its bounds and bound operators
- Active-price selection by latest `startDate ≤ query-date`, drawing from both flat top-level prices and `PriceSet`-scoped prices
- Modifier resolution alongside the base plan (vintage-PCIA pattern: many Prices share one PD, each carrying its own `urpx:hasApplicabilityGroup`)
- Configurable schedule step (hourly default; sub-hour available via `:step`)
- Symmetric write-side: `urpx.emit` emits JSON-LD from any coerced `RatePlan` / `RatePlanModifier` / `URPXDocument`; vendored fixtures round-trip cleanly through `parse → coerce → emit → re-parse → re-coerce`
- Authoring DSL (`urpx.dsl`): named builders for every entity-type schema, kebab-case kwarg aliases, auto-coercion of raw inputs, validation-on-construct, and local-symbol refs scoped via `dsl-scope`

Known gaps:
- Programmatic interpretation of `urpx:ConditionExpression` for active-tier selection in matrix-style modifiers — currently the caller picks the active value by `price-id` after walking the condition tree itself
- Some v0.2.0 SHACL classes (`urpx:RateCase`, `urpx:Scenario`, etc.) are accepted as Refs/optional fields but not deeply modeled; expand as consumers need them

## Development

```bash
clojure -M:test                 # run tests (Kaocha)
clojure -M:nrepl                # nREPL on the port written to .nrepl-port
clj-kondo --lint src test       # lint
```

## License

Copyright (c) 2026 Clark Communications Corporation. [MIT License](LICENSE).

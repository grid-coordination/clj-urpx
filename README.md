# clj-urpx

Clojure library for [URPX](https://lfenergy.org/projects/utility-rate-plan-exchange-urpx/) (Utility Rate Plan Exchange) — parse, validate, and resolve prices from URPX rate plan documents (JSON-LD).

## What is URPX?

URPX is an [LF Energy](https://lfenergy.org/) open standard for exchanging utility rate plan information using semantic web technologies (RDF, OWL, SHACL, JSON-LD). It enables precise, machine-readable data exchange between utilities, regulators, vendors, and researchers.

## What does this library do?

- **Parse** URPX JSON-LD rate plan documents into Clojure data structures
- **Validate & coerce** parsed data against [Malli](https://github.com/metosin/malli) schemas — string leaves become `BigDecimal`, `LocalDate`, `LocalTime`, `LocalDateTime`, `Duration`, `Period`, integer month numbers
- **Resolve** the per-ledger, per-tier prices that apply at a given instant in a given timezone — TOU period, season, day-of-week, midnight-wrapping brackets, block-tier ladders
- **Generate** contiguous price-interval schedules suitable for [OpenADR 3](https://www.openadr.org/) events

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

;; 1. Load and coerce a URPX rate plan document
(def plan
  (-> "path/to/urpx-rate-plan.jsonld"
      core/load-rate-plan
      coerce/coerce-rate-plan))

;; 2. Resolve the prices that apply at a specific instant
(def la (ZoneId/of "America/Los_Angeles"))
(price/resolve-prices plan (ZonedDateTime/of 2026 7 15 17 0 0 0 la))
;; =>
;; {:urpx.resolved/season-name        "Summer"
;;  :urpx.resolved/tou-period-name    "Summer Peak"
;;  :urpx.resolved/tou-period-number  1
;;  :urpx.resolved/ledgers
;;  [{:urpx.resolved/ledger-name "Commodity (Supply)"
;;    :urpx.resolved/tiers
;;    [{:urpx.resolved/price-name "Commodity Summer Peak"
;;      :urpx.resolved/unit-price 0.23354M}]}
;;   {:urpx.resolved/ledger-name "Distribution (Delivery)"
;;    :urpx.resolved/tiers [{:urpx.resolved/unit-price 0.09351M ...}]}
;;   {:urpx.resolved/ledger-name "Public Benefits"
;;    :urpx.resolved/tiers [{:urpx.resolved/unit-price 0.00604M ...}]}]}

;; 3. Marginal $/kWh — sum of the lowest-tier unit-price across all ledgers
(price/marginal-unit-rate *1)   ;; => 0.33309M

;; 4. Generate a day's worth of natural TOU intervals
(def start (.toInstant (.atStartOfDay (LocalDate/of 2026 7 15) la)))
(schedule/price-schedule-days plan start 1 la)
;; =>
;; [{:tick/beginning #inst "2026-07-15T07:00:00Z"  ; midnight LA
;;   :tick/end       #inst "2026-07-15T16:00:00Z"  ; 09:00 LA
;;   :urpx.interval/resolved {... "Summer Off-Peak" ...}}
;;  {... 09:00–15:00 "Summer Super Off-Peak"   $0.16645/kWh ...}
;;  {... 15:00–16:00 "Summer Off-Peak"         $0.18204/kWh ...}
;;  {... 16:00–21:00 "Summer Peak"             $0.33309/kWh ...}
;;  {... 21:00–24:00 "Summer Off-Peak"         $0.18204/kWh ...}]
```

For block-tiered plans (e.g. CPAU E-1), each ledger's `:tiers` vector exposes every tier with its `:tier-lower-bound`, `:tier-upper-bound`, and `:unit-price` — the caller selects the active tier from cumulative billing-period usage.

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

`urpx:RatePlanModifier` documents (export adders, net-surplus rates, hardship/seasonal adjusters) augment a base plan. Resolve them alongside the plan with `resolve-prices-with-modifiers`:

```clojure
(def eec
  (-> "path/to/urpx-rate-plan-modifier.jsonld"
      core/load-rate-plan
      coerce/coerce-rate-plan-modifier))

(price/resolve-prices-with-modifiers plan [eec] zdt)
;; => the resolve-prices output, with each applicable modifier's ledgers
;;    appended to :urpx.resolved/ledgers
```

A modifier is skipped silently when its `:urpx/applicableToRatePlan` list does not include the base plan's `@id`; `:urpx/isOptional` is informational and the caller decides whether to include the modifier in the call. Matrix-style modifiers (e.g. CPAU E-HRA — a 4×4 reserve-level × generation-tier matrix) surface every active price under one ledger entry; the caller picks the active value via `:urpx.resolved/price-id` because URPX condition vocabulary is not yet specified in a way that's programmatically interpretable.

### Sub-hour granularity

`price-schedule` walks at hourly steps by default. For rate plans that define sub-hour TOU bracket transitions, pass `:step`:

```clojure
(import '[java.time Duration])
(schedule/price-schedule plan start end la {:step (Duration/ofMinutes 15)})
```

Adjacent steps with identical resolved ledgers are still merged, so a quarter-hour walk across an hour-aligned plan produces the same merged intervals as the hourly walk.

## Namespace overview

| Namespace        | Purpose |
|------------------|---------|
| `urpx.core`      | Raw JSON-LD parsing (`load-rate-plan`, `parse-rate-plan`) |
| `urpx.schema`    | Malli schemas for the coerced URPX entity model |
| `urpx.coerce`    | Schema-driven coercion: strings → typed values; `validate`, `explain` |
| `urpx.index`     | `build-index`, `resolve-ref`, `get-entity` for `@id` references |
| `urpx.price`     | `resolve-prices`, `resolve-modifier-prices`, `resolve-prices-with-modifiers`, `marginal-unit-rate` |
| `urpx.schedule`  | `price-schedule`, `price-schedule-days` |

## Status

Currently covered:
- Both top-level document types: `urpx:RatePlan` (CPAU E-1 TOU, CPAU E-1 non-TOU, PG&E E-ELEC, PG&E E-TOU-D) and `urpx:RatePlanModifier` (EEC, NSE, HRA) parse, coerce, and validate
- TOU period resolution: seasons, ontology day-types (`urpx:allDays`, `urpx:weekdayDays`, `urpx:weekendDays`, `urpx:customDays`), `urpx:includeHolidays` / `urpx:includeNonHolidays` bracket gates, time-bracket containment with midnight wrap
- Block-tier surfacing with bounds and bound operators
- Flat per-energy charges (distribution, public benefits)
- Active-price selection by latest `startDate ≤ query-date`, drawing from both flat top-level prices and `PriceSet`-scoped prices
- `urpx:RatePlanModifier` resolution alongside the base plan (EEC, NSE, HRA) — modifier ledgers appended to the resolved output, with matrix-style prices surfaced for caller selection
- Configurable schedule step (hourly default; sub-hour available via `:step`)

Known gaps:
- Programmatic interpretation of `urpx:Condition` operator + label `conditionValue` for matrix-style modifier price selection — currently the caller picks active values by `price-id`

## Development

```bash
clojure -M:test                 # run tests (Kaocha)
clojure -M:nrepl                # nREPL on the port written to .nrepl-port
clj-kondo --lint src test       # lint
```

## License

Copyright (c) 2026 Clark Communications Corporation. [MIT License](LICENSE).

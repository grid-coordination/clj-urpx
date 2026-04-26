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
  (-> "test-cases/cpau-e-1-tou-2026-01-01/data/urpx-rate-plan_cpau_e-1-tou_2026-01-01.jsonld"
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

## Namespace overview

| Namespace        | Purpose |
|------------------|---------|
| `urpx.core`      | Raw JSON-LD parsing (`load-rate-plan`, `parse-rate-plan`) |
| `urpx.schema`    | Malli schemas for the coerced URPX entity model |
| `urpx.coerce`    | Schema-driven coercion: strings → typed values; `validate`, `explain` |
| `urpx.index`     | `build-index`, `resolve-ref`, `get-entity` for `@id` references |
| `urpx.price`     | `resolve-prices`, `marginal-unit-rate` |
| `urpx.schedule`  | `price-schedule`, `price-schedule-days` |

## Status

Currently covered:
- Top-level `urpx:RatePlan` documents — CPAU E-1 TOU, CPAU E-1 (non-TOU, block-tiered), and PG&E E-ELEC fixtures all parse and validate
- TOU period resolution: seasons, day-types (`urpx:allDays`, `urpx:weekday`, `urpx:weekend`), time-bracket containment with midnight wrap
- Block-tier surfacing with bounds and bound operators
- Flat per-energy charges (distribution, public benefits)
- Active-price selection by latest `startDate ≤ query-date`, drawing from both flat top-level prices and `PriceSet`-scoped prices

Known gaps (tracked as open issues):
- `urpx:RatePlanModifier` documents (EEC, NSE, HRA) — distinct top-level type, not yet schema'd
- `urpx:holiday` day-type matching (would need a holiday calendar)
- Sub-hour TOU bracket transitions — schedule resolution is hourly

## Development

```bash
clojure -M:test                 # run tests (Kaocha)
clojure -M:nrepl                # nREPL on the port written to .nrepl-port
clj-kondo --lint src test       # lint
```

## License

Copyright (c) 2026 Clark Communications Corporation. [MIT License](LICENSE).

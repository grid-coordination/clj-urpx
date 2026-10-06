<!--
SPDX-FileCopyrightText: 2026 Clark Communications Corporation
SPDX-License-Identifier: MIT
-->

# Changelog

Notable changes to [`energy.grid-coordination/clj-urpx`](https://clojars.org/energy.grid-coordination/clj-urpx). The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); the project is pre-1.0, so a minor bump is the strongest compatibility signal available and is used for breaking changes.

Entries begin at 0.11.0. The ten releases before it carry no summaries in their release commits, and reconstructing them from the commit log would be guesswork rather than history; see `git log` and the `v0.1.0`–`v0.10.0` tags for what actually landed.

## [Unreleased]

### Fixed

- **xsd:time `24:00:00` decodes to midnight**, on read and in `urpx.dsl` builders, with any fraction of zeros and an optional offset, and encodes as `00:00:00`. It threw `:urpx.schema/malformed-time`. A `urpx:TimeBracket` ending `24:00:00` runs to midnight. Bare `24:00` and any other hour-24 time still refuse.
- **`urpx.dsl` builders read a `:urpx/X` keyword value as the vocabulary term `{:jsonld/id "urpx:X"}`**, inside a `dsl-scope` or not, element-wise in a vector. Inside a scope it threw `DSL local ref not declared`; outside, the keyword failed validation.
- **`urpx.dsl/condition-expression` accepts kebab-case short keys** (`:root-node`), as every other builder does.

## [0.40.0] — 2026-10-06

### Added

- **Adjusting `urpx:ServiceBracket`s** (proposed in urpx-org/urpx#80). A bracket carrying `urpx:adjustsServiceTier` and `urpx:lowerBoundOffset` or `urpx:upperBoundOffset` adds each offset to the same bound of the named tier's applicable bracket when its own season and profile-alternative qualifiers hold, across every document in a composition. Offsets on one bound sum, a bracket repeated by `@id` counts once, and operators are unchanged. An adjusted tier carries the adjusted value under `:urpx.resolved/tier-lower-bound` or `:urpx.resolved/tier-upper-bound`, with `:urpx.resolved/tier-{lower,upper}-bound-base`, `:urpx.resolved/tier-{lower,upper}-bound-offset` and `:urpx.resolved/tier-adjusting-bracket-ids`. An adjusting bracket is never a bound of the tier hosting it.
- **Refusals for adjusting brackets:** `:unresolvable-adjusted-tier`, `:adjusted-tier-not-service-tier`, `:multiple-adjusted-tiers`, `:adjusted-tier-adjusts` (a target hosting adjusting brackets, its own host included), `:adjusted-tier-unit-absent` and `:adjusted-tier-unit-mismatch` at any instant, and `:adjusted-bound-absent` and `:adjusted-bounds-inverted` where an offset applies. An adjusting bracket whose qualifiers cannot be decided withholds the tier's bounds under `:urpx.resolved/tier-bounds-unresolved` with `:reason :adjustment-unresolved`.
- **`urpx.schema/ServiceBracket` admits `urpx:adjustsServiceTier`**, decoding a bare value to a one-element vector, and both offsets as decimals; `urpx.emit/default-context` types the offsets `xsd:decimal`.
- **`urpx.core/containment-properties`**, the properties under which `parse-graph` embeds a referenced node.

### Changed

- **`urpx.core/expand-document-entities` returns each distinct payload once.** A package naming one payload under several of its payload properties returned it once per property.
- **`urpx:serviceTierNumber` on `urpx.schema/ServiceTier` and `urpx:hasPriceDefinition` on `urpx.schema/BillingModel` are optional**, so a modifier whose host tier carries no number and whose billing model only moves bounds validates.

### Fixed

- **A document in JSON-LD flattened form reads as its nested equivalent.** Its nodes sit at the top level linked by bare `@id` references, and every loader read past them: a billing model or an adjusting bracket named by reference contributed nothing, with no error. `urpx.core/parse-graph` now replaces a bare reference under a property in `containment-properties`, or to a blank node referenced once, with the node of that `@id` when exactly one top-level entity in the document states it, and that entity leaves the top level. A node already on the path stays a reference, as does a reference to a named node under another property, to a node the document does not state, or to an `@id` stated twice. A blank node identifier left occurring once is dropped.
- **`urpx.price/resolve-prices-with-modifiers` indexes the versions it resolves.** An adjusting bracket whose target tier sits in a version supplied through `:versions-by-plan`, rather than nested in a plan or modifier passed to the call, refused `:unresolvable-adjusted-tier`.

## [0.39.0] — 2026-10-06

### Removed

- **`urpx.schema/NodeIdentity`**, which no schema or function referenced.

### Changed

- **`urpx.emit/default-context` binds `urpx` to `https://urpx.org/ns/ontology/`**, the namespace URPX moved to in v0.5.0. It bound the earlier `https://urpx-org.github.io/urpx/ontology/`.
- **A `urpx:TimeBracket` with `urpx:dayType` `urpx:customDays` matches the days it lists in `urpx:daysOfWeek`.** It matched every day. `resolve-prices` refuses `:urpx.price/custom-days-unspecified` against a `urpx:TOUSchedule` holding one that lists none, naming each such bracket under `:brackets`.

### Added

- **`urpx:daysOfWeek` on a `urpx:TimeBracket`** (urpx-org/urpx#71). `urpx.schema/TimeBracket` admits it as a vector of `urpx.schema/DayOfWeek`, the seven `urpx:DayOfWeekValue` members, and decodes a bare value to a one-element vector.
- **The jar carries the MIT notice at `META-INF/LICENSE`.** `clojure -T:build ci` refuses a jar without it.
- **The pom carries `<description>` and `<url>`.** `clojure -T:build ci` refuses a jar whose pom lacks either.
- **GitHub Actions runs `clj-kondo` and the test suite** on pull requests and on pushes to `main`.

### Fixed

- **A holiday whose observed date falls in the adjacent calendar year now counts on that date.** New Year's Day 2028, a Saturday, is observed Friday 2027-12-31 under `urpx:nearestWeekdayObservance` or `urpx:fridayIfWeekendObservance`, and a Sunday December 31 is observed Monday January 1 under `urpx:nearestWeekdayObservance` or `urpx:mondayIfWeekendObservance`, for both `urpx:holidayRule` and `urpx:holidayDate` holidays.
- **The README Getting started walkthrough runs from a fresh clone.** The `:nrepl` alias now puts `test` and `test/resources` on the classpath, so `urpx.test-fixtures` and the vendored fixtures load.

## [0.38.0] — 2026-10-05

### Fixed

- **A document assembled in code without `urpx.coerce` that wrote a lone value of a 0..N property bare, rather than as a one-element list, could resolve to a different answer or throw.** Resolution, composition and holiday derivation now read the bare and list forms alike for every property `urpx.schema` decodes to a vector, including `urpx:hasPrice`, `urpx:hasPriceSet`, `urpx:hasTOUSchedule`, `urpx:hasServiceTiers`, `urpx:hasApplicabilityRule`, `urpx:referencesMetricInput` and `urpx:month`.

## [0.37.0] — 2026-10-05

### Changed

- **`urpx.schedule`'s `price-schedule`, `price-schedule-days`, `price-schedule-with-modifiers`, `price-schedule-days-with-modifiers` and `price-segments` take ZonedDateTime bounds in any zone**, as `segments-for` and `schedule-for` do. The `zone` argument is gone: each takes `[plan start end]` or `[plan start end opts]`, with `modifiers` after `plan` where it takes them, and `days` in place of `end` for the `-days` forms. The bounds' instants define the window; intervals are computed and returned in the plan's zone. `:urpx.schedule/zone-disagrees-with-plan` is retired. A bound of any other type refuses `:urpx.schedule/bound-not-zoned-date-time`, where a ZonedDateTime bound threw a ClassCastException, as do `segments-for` and `schedule-for`, and `opts` that are not a map refuse `:urpx.schedule/opts-not-a-map`.
- **`coverage/of-version` returns `:evaluated-on` and the coverage window's `urpx:startDateTime` and `urpx:endDateTime` as `ZonedDateTime`.** One written without an offset is read in the zone of the `urpx:timezoneIdentifier` on the version, else on its document, else the zone given as the new optional third argument. With none it refuses `:urpx.coverage/zone-required`; with two whose rules differ, `:urpx.coverage/conflicting-timezone`. Zones with the same rules, such as `UTC`, `Etc/UTC` and `ZoneOffset/UTC`, agree. `coverage/of-model` reads each part in the zone on its model document.
- **`price-schedule-days` and `price-schedule-days-with-modifiers` count `days` as calendar days in the plan's zone**, so the window ends at the same wall-clock time in that zone: a day is 23 or 25 hours across a daylight transition, where it was 24.
- **`resolve-prices`, `resolve-modifier-prices` and `resolve-prices-with-modifiers` refuse `:urpx.price/no-version` for a plan or modifier with no version snapshot**, neither nested nor supplied through `:version` or `:versions-by-plan`. `resolve-prices` and `resolve-modifier-prices` returned empty ledgers, and a composition dropped such a modifier. The message names both ways to supply one: `:versions-by-plan` from `urpx.index/versions-by-plan` over `urpx.load/documents`, or resolving the loaded documents through `urpx.scenario-cost-model`.
- **An unrecognized `urpx:holidayRule` refuses with `:reason :urpx.holidays/unknown-holiday-rule`, and an unrecognized `urpx:observanceRule` with `:urpx.holidays/unknown-observance-rule`.**
- **A `urpx:Holiday` carrying neither `urpx:holidayDate` nor `urpx:holidayRule` refuses with `:reason :urpx.holidays/no-date-or-rule`.**
- **`urpx.schema/Holiday` closes `urpx:observanceRule` to the `urpx:ObservanceRuleValue` members through the new `urpx.schema/ObservanceRule`**, so the validate CLI and `coerce/validate` reject any other IRI.

### Added

- **`urpx.load/scan` reports `:urpx.load/unresolved-references`**: each package payload reference whose entity no source states, as `{:jsonld/id id :package-id p :property k}`.
- **`corpus/corpus_json.clj`, a dev-only walker for any corpus whose layout is declared in a `corpus.json`**, in urpx-rate-plans' format. `corpus-sweep --layout` takes a `corpus.json` or a directory holding one, as well as a walker namespace. As a program it prints the documents, named kinds, or `--all` as `kind<TAB>path`.
- **`corpus-sweep` with no `--layout` reads the `corpus.json` at the corpus root when there is one**, including in a corpus fetched by git URL, and globs `**/*.jsonld` when there is none. The `:dev` alias carries `corpus/`, so `clojure -M:dev -m corpus-sweep` reads one.
- **`corpus/urpx.corpus.json` declares the layout of urpx-org/urpx's `test-cases/`.** The `urpx-upstream` walker reads it.
- **`scenario-cost-model/rate-plans-name`, the `urpx:name` urpx-rate-plans gives a model's `urpx:ScenarioCostModel`**, to pass `node` as its `:name`: each consumed version's plan or modifier name with the version's `@id` in brackets, then any selected alternatives' names. A component with no `urpx:name` refuses `:urpx.scenario-cost-model/unnamed-component`; one named two ways refuses `:urpx.scenario-cost-model/conflicting-name`.
- **The jar carries `CHANGELOG.md` at `docs/energy.grid-coordination/clj-urpx/guides/CHANGELOG.md`**, beside the `doc/` guides.

### Fixed

- **`:urpx.resolved/withheld-prices` and `:urpx.resolved/unselected-alternatives` listed Prices that would not apply at the instant.** A Price in an unselected `urpx:PriceSetAlternative` is now listed only when it has started, is the latest for its cell within that alternative, and has no `urpx:touPeriodNumber` or the resolved one, as on the active path. A ledger left with none is no longer marked, so `marginal-unit-rate` answers where it refused `:unselected-alternatives`.
- **A modifier's own `urpx:PriceSummary` members were gated by the base plan's TOU period, and in a composition a month rule declared in the modifier and named by bare `@id` from a member's `urpx:PriceDefinition` was not applied; resolved alone, a modifier with its own `urpx:TOUSchedule` counted no member whose `urpx:PriceDefinition` carries a `urpx:touPeriodNumber`.** They are now gated by the modifier's own period when it carries a schedule, counting outside its brackets only members with no `urpx:touPeriodNumber`, and by its own `urpx:ApplicabilityRule`s. Members in the base plan keep the base plan's gates.
- **Resolved alone, a modifier with no `urpx:TOUSchedule` left out of a `urpx:PriceSummary` subtotal every member whose `urpx:PriceDefinition` carries a `urpx:touPeriodNumber`.** A Price derived from that summary is now unvaluable with `:summary-member-unselected`.
- **A `urpx:PriceSummary` whose members in effect shared the lowest tier number of a block-tier ladder summed one of them, chosen arbitrarily.** A Price derived from it is now unvaluable with `:summary-ambiguous-tier`, and its detail names the members, as `marginal-unit-rate` refuses `:ambiguous-marginal-tier` for a ledger.
- **`clojure -M:validate` and `urpx.validate/check-file` schema-checked a `urpx:URPXPackage` or `urpx:URPXDocument` wrapper's payloads but not the wrapper.** The wrapper is now checked against its own schema too, so a package missing `urpx:targetURPXVersion` fails, and `:checked` lists it.
- **An xsd:date whose schema is `urpx.schema/Date` inside a `:maybe`, `:or`, `:orn`, `:and` or `:ref`, or named by a registry key, lost its written offset on emit.** `urpx.coerce/coerce` now keeps it in `:urpx.schema/date-offsets` and `urpx.coerce/encode` writes it back, as for a direct `Date` entry.
- **`urpx.scenario-cost-model` compared the zones declared for one version as identifiers**, so `UTC` and `Etc/UTC`, or `America/Los_Angeles` and `US/Pacific`, refused as `:urpx.scenario-cost-model/conflicting-timezone`. Zones with the same rules now agree, and the model is zoned by the declaration with the lowest `@id`.

## [0.36.0] — 2026-10-05

### Changed

- **`urpx.arrangements` is now `urpx.scenario-cost-model`**, named for `urpx:ScenarioCostModel` from urpx-org/urpx#72. No deprecated aliases remain; every other function and option keeps its name.

  | 0.35.0 | Now |
  |---|---|
  | `urpx.arrangements` | `urpx.scenario-cost-model` |
  | `arrangements` | `enumerate` |
  | `arrangement-pairs` | `enumerate-pairs` |
  | `arrangement-id` | `identity-tuple` |
  | `urpx.coverage/of-arrangement` | `urpx.coverage/of-model` |
  | `:urpx.arrangement/*` result keys and refusals | `:urpx.scenario-cost-model/*`, same local names |
  | `:urpx.arrangement/opt-determined-by-arrangement` | `:urpx.scenario-cost-model/opt-determined-by-model` |
  | `:arrangement-id` in refusal ex-data | `:identity-tuple` |

- **`identity-tuple` keys a model by its alternatives alone:** `[base-version-iri [modifier-version-iri ...] [alternative-iri ...]]`, both inner vectors sorted, the values a `urpx:ScenarioCostModel` carries. `arrangement-id` ended in `[[container alternative] ...]`. `enumerate` sorts by the new tuple, and each model's `:urpx.scenario-cost-model/selections` still maps every container to its alternative.
- **`segments-for` and `schedule-for` take ZonedDateTime bounds**, as `[model start end]` or `[model start end opts]`, in any zone. The Instant arities and the `zone` argument are gone. The bounds' instants define the window; segments are computed and returned in the plan's zone, the zone `resolve-at` resolves in.
- **`load/documents` and `load/scan` return a package shell for every `urpx:URPXPackage` or `urpx:URPXDocument` wrapper:** the wrapper's own properties, with each embedded payload under `hasRatePlan`, `hasRatePlanModifier`, `hasTariffPublication` and `packages` replaced by a `{:jsonld/id id}` reference. README gives the predicate that drops the shells. Both also return any `urpx:ScenarioCostModel` among the coerced documents.
- **New refusals.**

  | Reason | When |
  |---|---|
  | `:urpx.price/no-tou-period` | a plan has a `urpx:TOUSchedule` and no period covers the instant, including a holiday no bracket covers. The period resolved to nil and every period's Price was admitted. Every `urpx.schedule` and `scm` horizon function reports it as `:urpx.interval/refused`; `resolve-at` propagates it |
  | `:unknown-profile-alternatives-container` | a `:profile-alternatives` key names no container in any version being resolved. The prices it was meant to select were withheld. The ex-data lists the known containers under `:containers` |
  | `:unresolvable-season-ref` | a modifier's `urpx:TimeBracket` names a `urpx:Season` that no document passed declares, including a modifier resolved alone whose brackets name the base plan's Seasons. `:season-ids` lists them |
  | `:urpx.scenario-cost-model/shared-alternative` | two containers in one composition's versions list the same `urpx:ProfileAlternative`, in `enumerate` and `from-node` |
  | `:urpx.scenario-cost-model/unknown-package-zones` | a `:package-zones` value other than `:agree`, `:fallback` or `:ignore` |
  | `:urpx.load/payload-without-id` | a wrapper embeds a payload with no `@id` |
  | `:urpx.schema/malformed-datetime`, `-date`, `-time` | a malformed or out-of-range temporal literal. `load` and `scan` propagate it and the validate CLI reports REFUSED |
  | `:ambiguous-marginal-tier` | `marginal-unit-rate` finds two or more prices at the lowest tier number of an energy ledger's block-tier ladder. It took one of them. The ex-data lists each ledger with its `:tier-number` and `:price-ids` under `:ledgers` |
  | `Unrecognized urpx:observanceRule` (ex-data `:observance-rule`, `:holiday`) | a `urpx:Holiday` names an observance rule other than `actualDateObservance`, `nearestWeekdayObservance`, `mondayIfWeekendObservance` or `fridayIfWeekendObservance`. The holiday was observed by its rule string's default or on its actual date. `holidays/derive-predicate` throws it, as it does an unrecognized `urpx:holidayRule` |

- **`price-schedule`, `price-schedule-with-modifiers`, their `-days` forms and `schedule-for` report a refused step as an interval carrying `:urpx.interval/refused` and continue**, as `price-segments` and `segments-for` do, where the ExceptionInfo used to abort the whole window. Adjacent equal refusals merge into one interval.
- **A modifier's Prices carrying `urpx:touPeriodNumber` contribute nothing where its own `urpx:TOUSchedule` covers no period**, such as `smud:pev-credit` outside 00:00-06:00, where every period's Price had been admitted. Those carrying none still apply.
- **A Price stating `urpx:startDateTime` takes effect at that instant**, an offset-bearing value at the instant it names, where it had taken effect at the start of its plan-zone day. `price-segments` begins a segment there. A `urpx:startDate` still takes effect at midnight of that day in the plan zone.
- **Temporal literals with an offset decode to what they name.** An xsd:dateTime written with `Z` or an offset decodes to a ZonedDateTime in that offset, and one without stays a LocalDateTime. An xsd:time with an offset decodes to an OffsetTime. An xsd:date with an offset decodes to the LocalDate it names, so a version or coverage bound written with one starts at midnight in the plan zone; the offset is kept with its date in the map's metadata under `:urpx.schema/date-offsets` and emitted as written while the map holds that date. The `DateTime` and `Time` schemas accept the zoned forms. Times and date-times encode in XSD canonical form with seconds, where `"16:00:00"` had been written as `"16:00"`. A typed-literal object `{"@value" v "@type" t}` decodes like the plain string, and `emit/default-context` types every temporal property the schema decodes. `urpx.coverage/of-version`'s `:evaluated-on` is a ZonedDateTime when the document wrote an offset.
- **A modifier's ledgers carry its own matched season and TOU period** as `:urpx.resolved/season-name`, `tou-period-name` and `tou-period-number`. A modifier gated by the base plan's period carries no period keys, and the result-level keys remain the base plan's. `price-segments` now splits where a modifier's own season or period changes.
- **A modifier's brackets read the modifier's own `urpx:HolidayCalendar`.** `resolve-modifier-prices` treated every date as a non-holiday without a `:holiday?`, and a composition applied the base plan's calendar to every modifier. A modifier declaring none still inherits the base plan's in a composition, and a caller's `:holiday?` overrides both. A modifier's calendar refuses an unrecognized rule as a base plan's does.

### Added

- **`urpx.schema/ScenarioCostModel`**, transcribing `ScenarioCostModelShape` as amended by urpx-org/urpx#72, closed, with `urpx:name` and `urpx:description` admitted. Its 0..N properties decode a bare value to a vector of one. `coerce-doc` and the validate CLI take a model, `emit/write-scenario-cost-model` writes one, a model under `urpx:packages` is encoded through its schema, and `dsl/scenario-cost-model` builds one.
- **`scm/node`** writes a model as one `urpx:ScenarioCostModel` node with absolute `@id`s. It takes a required `iri-fn`, and `:context`, `:name` and `:description` as options. An `@id` the context cannot expand refuses `:urpx.scenario-cost-model/unexpandable-iri`, and a prefix bound two ways `conflicting-prefix`.
- **`scm/rate-plans-iri`** mints the IRI urpx-rate-plans derives from the identity tuple, for use as `iri-fn`. It refuses `:urpx.scenario-cost-model/iri-outside-namespace` and `iri-local-name`.
- **`scm/from-node`** reads a `urpx:ScenarioCostModel` node, raw or coerced, into the map `enumerate` yields for it, so `resolve-at`, `segments-for`, `schedule-for` and `node` take it unchanged. It takes `:compatibility`, `:repeated-ids`, `:package-zones` and `:context`, and refuses `not-a-reference`, `no-version`, `unknown-version`, `inapplicable-modifier`, `shared-alternative`, `unknown-alternative`, `dimension-selected-twice` and `dimension-unselected`, each under `:urpx.scenario-cost-model/`.
- **`:package-zones`** on `enumerate`, `plan-versions`, `applicable-modifiers` and `from-node` decides what a package's `urpx:timezoneIdentifier` counts for: `:agree` (default) counts it as one more declaration that must agree, `:fallback` reads it only when nothing else declares a zone, and `:ignore` reads none. Packages are indexed by `hasRatePlan` and `hasRatePlanModifier` as well as `packages`.
- **`urpx.schema/in-zone`, `wall-time`, `date-offsets-decoder` and `date-offsets-encoder`.**

### Fixed

- **A nested `@graph` was loaded as one untyped node.** `parse-graph` unwrapped only a top-level `@graph`, so upstream `test-cases/pge-e-tou-c` lost its package, plan, version, vintage-PCIA modifier and that modifier's version. It now flattens graph objects at any depth.
- **A modifier summed a superseded dated Price with the one replacing it.** The modifier path now keeps the latest started Price per cell, as the base path does. With no selection, each `urpx:PriceSetAlternative` is reduced on its own.
- **A modifier's `urpx:TimeBracket` naming a `urpx:Season` only the base plan declares matched nothing**, so every period's Price was admitted. It now resolves that Season through the composition and matches by its months.
- **`load/scan` reported a by-reference package payload as uncoercible**, as `{:jsonld/type nil}`. The package shell keeps the reference, and the referenced entity loads where it is stated.

## [0.35.0] — 2026-10-04

### Fixed

- **0..N properties stayed undecoded when written as a single value.** The schema admitted a vector with no `:decode/urpx-jsonld` for `ApplicabilityRule/month`, `Season/month`, `PlanElements/hasSeasons`, `PlanElements/hasMetricInput`, `PriceSet/hasPrice`, `Prices/hasPrice`, `Prices/hasPriceSet`, `ServiceTiers/hasServiceTier`, `TOUPeriod/hasTimeBracket`, `TOUSchedule/hasTOUTier`, `TOUTier/hasTOUPeriod` and `TimeBracket/appliesInSeason`. Malli does not descend a non-vector, so a bare value left its whole subtree untransformed: a bare `hasSeasons` kept `urpx:month` as `["--06" "--07"]` and threw on a later cast. Read-one properties (`hasRatePlanVersion`, `hasRatePlanModifierVersion`, `hasMetricSpecification`, `hasHolidayCalendar`) keep their shape and are read through `urpx.index/single`.
- **Three reads iterated a bare 0..N value as if it were a list.** `applicable-modifiers` returned `[]` under both shipped compatibility policies when `applicableToRatePlanVersion` was bare, so every arrangement came out base-only. A modifier whose target list was bare applied to no plan. `urpx.emit` mapped over a bare `hasRatePlan` and emitted key-value pairs with the `@` prefixes stripped. All three now read the bare and list forms alike, through `urpx.index/as-vec`.
- **A zone was not inferred from the EIA id when `urpx:hasIdentity` was a list.** The v0.5.1 shapes allow it as 0..N. Every identity and identifier is now read.
- **A top-level rate plan version whose zone is declared on its plan could not be enumerated or resolved.** Arrangements now take the zone from the version, its plan or modifier (embedded or in a separate identity document), the document it came from, and any package that names one of them. `resolve-at`, `segments-for` and `schedule-for` resolve such a version.
- **A merged `price-schedule` interval reported the first step's season and TOU period for its whole span.** See Changed.

### Changed

- **`price-schedule` intervals may omit labels.** A merged interval carries `:urpx.resolved/season-name`, `:urpx.resolved/tou-period-name` and `:urpx.resolved/tou-period-number` only when every merged step agrees on that value. Steps still merge on ledgers. Use `price-segments` when the labels matter, since it splits on them.
- **New refusal: `:urpx.arrangement/conflicting-timezone`.** Arrangements throw it when the nodes a version belongs to declare different zones, with or without `:at`.
- **The outside-version-window marks carry `:plan-id`.** The ex-data and each `:stale` entry name the owning plan's or modifier's `@id`, the key `:versions-by-plan` is looked up by, and the message names it too.
- **Vendored fixtures are licensed CDLA-Permissive-2.0**, copyright Clark Communications Corporation, matching their source; their sidecars had declared CC0-1.0. The repository is REUSE-compliant, with everything else MIT under `REUSE.toml`.
- **The dev-only `corpus/` ships only the urpx-upstream walker.** The urpx-rate-plans walker moved to that repository.

## [0.34.0] — 2026-09-24

### Fixed

- **A urpx:RatePlanModifier's own block tiers surfaced untiered.** `modifier-ledger-results` built its entries from `price-fields` alone and never called `tier-info`, so no entry from a modifier carried `:urpx.resolved/tier-number` or any bound, however its `urpx:ServiceTiers` were shaped. That made it a wrong NUMBER rather than a missing field: `marginal-unit-rate` reduces a block ladder to its marginal rung by that number and sums everything without one, so a modifier's three-rung ladder was added together instead of reduced. Bracket and container selection now reach that path too, reading the same season and alternative context the PriceDefinition gate there already used.

  The case it was for: PG&E's E-TOU-C baseline-tier overlay carries 120 `urpx:ServiceBracket` nodes, one per (baselineTerritory, baselineCode, season) cell, and every one of them was read by nothing. Composed with its base plan and given a baseline cell, Territory P Code B now states 0 to 13.5 and 13.5 to 54.0 kWh per day in summer and 0 to 11.0 and 11.0 to 44.0 in winter, matching PG&E's Sheet 4. With no cell named all four rungs surface with their numbers and unit prices and an `:alternative-unselected` mark, so a caller learns the ladder exists and that its bounds are the caller's to determine.

  One asymmetry recorded rather than worked around: `resolve-modifier-prices` takes no opts, so a cell selection cannot reach a modifier resolved alone and its cell-scoped bounds are always withheld there. Composing is the way to select. `resolve-prices` has taken opts all along.

- **A modifier resolved on its own ignored four caller inputs, and one of them made it answer wrongly.** `resolve-modifier-prices` was the only public resolution entry point with no opts arity, though the private function it delegates to already took one and `resolve-prices-with-modifiers` passed it. Worst was `:holiday?`: this path defaults it to `(constantly false)`, so a modifier carrying its own `urpx:TOUSchedule` with holiday-sensitive `urpx:TimeBracket`s resolved the non-holiday period on a holiday and said nothing about it. Every other gap on that path withholds or refuses; that one returned a confident wrong period. It now takes `opts` on a new 3-arity, with the 2-arity delegating unchanged.

  The other three withheld rather than lied, and are now reachable: `:profile-alternatives`, without which a cell-scoped `urpx:ServiceBracket`'s bounds could never be determined standalone; `:by-id`, which supplies a `urpx:Season` a modifier names but does not declare, as PG&E's E-TOU-C overlay does; and `:version`, for a document carrying several snapshots.

### Changed

- **The `pge-e-tou-c-baseline-tier-2026-03-01` fixture is re-vendored from the same upstream commit as its base plan.** The two halves of one filing had been taken from different upstream moments, so the modifier named `pge:e-tou-c-2026-03-01-season-*` while its base plan declared `pge:season-*`. Nothing read those refs before, so the mismatch was invisible; wiring the modifier path made it a refusal. An inconsistent pair is not a URPX document set any consumer would see, and it was an artifact of vendoring rather than anything upstream ever published.

## [0.33.0] — 2026-09-23

### Fixed

- **Three readers of `urpx:appliesInSeason` answered different questions depending on which node carried the property.** `pd-active-in-season?` skipped an unresolvable ref silently and read a `urpx:Season` carrying no `urpx:month` as excluding the month. `find-tou-period` put a `TimeBracket`'s refs through `urpx.index/single`, which refuses on several, so a legal filing naming two Seasons on one bracket was unresolvable. `node-season-status`, added in 0.32.0 for tier selection, did neither. There is now one shape normalizer (`season-refs`), one reader (`node-season-status`) and one constructor (`make-season-status`), shared by tier selection, PriceDefinition gating and `urpx:PriceSummary` member gating.

  Multiplicity is disjunctive everywhere, which is what `sh:minCount 0` with no `sh:maxCount` states on every shape carrying the property. A gate that could NOT be evaluated no longer excludes a PriceDefinition: reading a monthless Season as out-of-season made a price silently missing from the sum, and excluding on an unreachable Season would have reinstated the defect 0.32.0 fixed for summary members through another route, since a modifier resolved without the base plan that declares its Seasons would drop every seasonal definition. A `urpx:PriceSummary` member keeps the stricter treatment, because a subtotal has a third state for an unknown member and a boolean filter does not: such a member is now `:unknown` and the derived Price reports `:summary-member-unselected` rather than carrying a number.

  No vendored fixture moves, and none could: measured across the 23 vendored in this repo, no `TimeBracket` names more than one Season, none of their `PriceDefinition`, `Price` or `PriceSet` nodes carries `urpx:appliesInSeason`, and no Season lacking `urpx:month` is referenced. That is why the three readers could disagree unnoticed, and why every case is constructed. Reverting the unification fails 12 assertions across the new namespace and two existing ones.

  Those counts are a property of this repo's sample, not of URPX filings: the upstream corpus carries `urpx:appliesInSeason` on 89 PriceDefinitions across six CPAU files, reported by the urpx-rate-plans maintainers. It still moves nothing there, and the check that establishes it is reachability rather than absence: none of the 89 names a Season its own document does not declare, so no such gate is unevaluable and the behavior changed here cannot be reached from that data.

### Changed

- **`urpx:ServiceTiers.referencesMetricInput` is documented as naming the quantity a tier's BOUNDS are stated in**, which is not the quantity the ledger's charge is stated in. The two are meant to differ: LADWP R-1A's Power Access Charge is 2.30, 7.90 and 22.70 per `P1M` per meter with bounds in kWh, so its container names `mi-energy` while its ledger names `mi-meter-count`. Reading the property as a pointer to a container's ledger would strip the bounds off a monthly fixed charge tiered by consumption. No behavior change; the question was raised by 0.32.0's tier selection and is settled against it.

## [0.32.0] — 2026-09-23

### Fixed

- **A modifier's PriceDefinitions were gated by TOU period alone, so month and season applicability did nothing there.** The base path filters a ledger's definitions on `pd-active-at-tou?`, `pd-active-in-month?` and `pd-active-in-season?`; the modifier path filtered on the first only. A `urpx:RatePlanModifier` telling two cells apart by a `urpx:hasApplicabilityRule` month constraint or by `urpx:appliesInSeason` therefore surfaced BOTH at every instant, while one telling them apart by `urpx:touPeriodNumber` gated correctly, which is why it went unseen. Reported from the corpus side: SMUD's NEM1 export credit over `smud:fixed` is that shape, and only its TOU-discriminated cells were buildable. Month and season now apply unconditionally on that path, TOU stays conditional on there being a period to compare against, and a `urpx:Season` a modifier names but does not declare resolves through the composition-wide index.

  Standalone `resolve-modifier-prices` had the opposite failure in its summary-member gate, which it built with EMPTY rule and season indexes: `pd-active-in-season?` could resolve no `urpx:Season` for any ref, so a member carrying `urpx:appliesInSeason` was always inactive and silently dropped from the subtotal rather than gated by it. It is now judged against the modifier's own seasons and rules. Compositions were unaffected, since those are supplied from the base version.

  No vendored fixture moves: not one modifier `PriceDefinition` or `PriceSummary` in the 23 carries either property, so both halves are exercised by constructed documents.

- **A tier's bounds came from whichever `urpx:ServiceBracket` happened to be written first.** `tier-info` took `(first (:urpx/hasServiceBracket tier))` and indexed one `urpx:ServiceTiers` container per `urpx:serviceTierNumber`, so `urpx:appliesInSeason` and `urpx:referencesProfileAlternative` were silent no-ops at both scopes: a tier stating summer bounds and winter bounds reported the summer ones in December, and a second container declaring the same number last-won on map insertion order. Bounds are now selected among every (container, tier, bracket) candidate for that number, gated by both properties on the container and on the bracket alike. Measured on a constructed two-bracket tier: upper bound `13.5` in July and `11.0` in December, where both months read `13.5` before.

  Three refusals rather than a pick: `:ambiguous-service-bracket` (two brackets in effect stating different bounds, and `urpx:hasServiceBracket` is a set, so there is no first one to take), `:ambiguous-service-tier` (two containers declaring one number with different bounds), and `:unresolvable-season-ref` (a ref naming a `urpx:Season` that matches none of the ones reachable here, evaluated eagerly so it fires whichever cell the caller chose). That last one divides on whether any `urpx:Season` is reachable at all: if some are, a ref matching none is a typo and refuses; if none are, the document that declares it was simply not passed, so the bounds are withheld with `:season-not-evaluable` and composing or supplying `:by-id` clears it. That is the line `pd-alternative-status` already draws for an alternative no container in this document declares. Brackets stating the same bounds do not refuse: comparison goes through `.stripTrailingZeros`, so `0M` and `0.0M` agree. `resolve-prices` now carries ten `:reason` values.

  **No vendored fixture moves**: every one of the 423 tests that predate this change still passes, with no assertion altered and three added to pin the ex-data rename below; the bracket-selection namespace takes the suite to 450 tests, 0 failures. (Assertion totals are not quoted because `urpx.provenance-test` reads `target/classes/META-INF/.../build-provenance.edn` off the filesystem and asserts seven fields when a build has populated it, one trivially when it has not. `target/` is gitignored, so a fresh checkout counts six fewer than a tree that has run `ci`.) The 18 tiered rungs across the five tiered base filings carry exactly one bracket each, and neither it nor its container carries either property, so a single unqualified candidate short-circuits before the date or the selection is read. The only qualified brackets in the corpus are the 120 on PG&E's E-TOU-C baseline-tier modifier, and `tier-info` has one call site, on the base-plan path.

### Added

- **`:urpx.resolved/tier-bounds-unresolved`**, on a tier whose bounds selection cannot determine. A bound read off a bracket that does not apply is a number a consumer bills against, so it is withheld instead. Three reasons: `:alternative-unselected` when the caller named no cell in that dimension, `:season-not-evaluable` when a `urpx:Season` carries no `urpx:month` so the month gate was never evaluated, and `:no-applicable-bounds` when nothing is in effect at this instant. The mark carries `:scope` and `:ids`, plus `:alternatives` where an alternative is involved. The entry keeps its `:urpx.resolved/tier-number` and its unit price, so `marginal-unit-rate` still reduces the ladder by number and still answers: `0.20M` on a tier whose bounds are withheld.

### Changed

- **`:multiple-profile-alternatives` and `:ambiguous-profile-alternative` report `:node-id` and `:node-type` where they reported `:price-definition-id`.** Bracket selection hands `pd-alternative-status` `urpx:ServiceTiers` containers and `urpx:ServiceBracket`s as well as `PriceDefinition`s, so the old key named the wrong thing for two of the three node types.

## [0.31.0] — 2026-09-22

### Fixed

- **The same instant, written in two zones, resolved to two different prices.** `urpx.price` reduced the caller's `ZonedDateTime` with `.toLocalDateTime` and compared those wall-clock fields against document bounds that are wall-clock in the **plan's** zone. Every bound in a URPX document is naive: all 89 in the production corpus carry no offset. So the answer depended on how the caller spelled the moment.

  Measured on PG&E E-TOU-C, whose peak runs 16:00–21:00 Pacific. One instant:

  | 2026-07-15, asked at | in Pacific | in UTC |
  |---|---|---|
  | 10:00 PT | Off-Peak `0.34586` | **Peak `0.46886`** |
  | 16:00 / 17:00 / 20:00 PT | Peak `0.46886` | **Off-Peak `0.34586`** |

  Up to **36% apart**, with no error and no mark. The version-window half was reported by a consumer: SCE Schedule D's `sce:d-2025-11-15` expires at LA midnight, and `2025-12-31T20:00-08:00` priced while the identical `2026-01-01T04:00Z` refused `:outside-version-window`. The TOU half was not reported and is worse, because it fires on most of the day rather than at one boundary.

  `resolve-prices` now anchors the query in the plan's declared zone before reading anything, so resolution is a pure function of the **instant** and the plan's zone. `resolve-modifier-prices` anchors in the modifier's own; a composition anchors both halves in the base plan's, because one question about one moment should not resolve in two frames.

- **`price-schedule` and `price-segments` refuse a walk zone the plan does not declare.** Candidates must be derived in the zone resolution happens in or the candidate set stops being a superset of true transitions. Measured: a UTC walk of that LA plan over one July day put the peak boundary at 17:00 Pacific instead of 16:00 and lost the 21:00 transition entirely, returning two segments where three are right. Passing the plan's own zone, or `nil`, is unchanged.

### Changed

- **A plan declaring no `urpx:timezoneIdentifier` is refused**, `:reason :urpx.price/no-timezone`. Its bounds anchor to no moment, so whether an instant falls inside one has no answer. Every filing in both corpora declares one.

- **`version-effective-at`, `version-expired-by?` and `version-covers?` take a required `ZoneId`** and work in `ZonedDateTime`, as does the new `version-expires-at`. An optional zone would have made the unzoned call the easy one and the wrong one, which is how this namespace came to compare a caller's wall clock against a publisher's.

- **`:effective` and `:expires` in `:outside-version-window` are `ZonedDateTime`** rather than `LocalDateTime`. A naive bound handed to a consumer invites exactly the comparison that caused this.

**Upgrading:** this changes resolved values for any caller passing a `ZonedDateTime` in a zone other than the plan's — previously silently wrong, now correct. Callers already passing the plan's zone see no change. An explicit walk zone that disagrees with the plan now refuses rather than returning wrong boundaries, and a plan with no declared zone refuses rather than resolving against the caller's.

## [0.30.0] — 2026-09-22

### Added

- **`urpx.load`, naming the pipeline from sources to documents.** `document-xf` is a transducer, `documents` realises it, and `scan` does the same work in one pass while reporting what it discarded. A source is anything `clojure.java.io/reader` coerces; where sources come from is the caller's business and deliberately not this library's, because a corpus repository is a one-off and there will never be a layout everyone adopts.

  Nothing here is new behavior. It is the same three functions in the one order that is correct, and it exists because the previous arrangement — a four-step form in the README that every consumer composed by hand — produced exactly the failure it describes. A consumer following it used `mapv` where `mapcat` belongs and omitted the coercion step, and got a plausible count rather than an error. Naming the composition makes the wrong one unavailable to make rather than documented against.

  It is its own namespace because it cannot live in either it spans: `urpx.core` requires only data.json, java.io and string, `urpx.coerce` only malli and `urpx.schema`, and neither requires the other. Core would drag Malli into the parse layer; coerce would make a coercion-only consumer pull in the parser.

- **`scan` makes the one silent drop assertable.** `coerce-doc` returns nil for an entity type this library does not model, so `document-xf` discards it without a word: 42 of 129 expanded entities over the production corpus, `urpx:ProgramEnrollment` 30, `urpx:SourcePublication` 8, `urpx:RateCase` 4. None is a version today, which is luck rather than design; the unlucky form of the same thing cost a whole rate plan in 0.29.0. A caller can now assert that no version type reached the discard list, which it could not before. "This library does not model it" and "it does not matter" are different statements, and the second cannot be judged while the first is invisible.

- **`urpx.coverage`, an optional reader for what a snapshot claims about itself.** Every version carries a `urpx:CoverageClaim`, the standard's own answer to "what am I holding?", and this library modeled it and read it nowhere. `of-version` returns a report; `claim` returns the raw node. It requires `urpx.index` and nothing else: not `urpx.price`, not `urpx.arrangements`, and nothing in `src/` requires it.

  The verdict is the PUBLISHER's, not this library's: claims "are produced by an implementation or publishing workflow", so nothing here re-derives one or second-guesses one.

  Three things the report is careful about, each because the careless version would read as a fact the document never stated. **Absent is not `unknown`** — a version with no claim reports `stated? false` and `completeness nil`, never `"urpx:unknown"`, which is itself a verdict meaning no evaluation has run. **`selfContained` is reported with its source and any disagreement** rather than flattened, and it is vacuous in practice: derived as "false iff any `PriceDefinitionReference` is `retrievedElsewhere`", with zero such references in the corpus or the fixtures. **`scope-stated?` and `coverage-window` are separate**, because `scopeKind` is the only required field on a `TemporalScope`, so a scope can name its role and bound nothing.

  Four refusals: `:unknown-completeness-result`, `:claim-ref-unresolved`, `:self-contained-contradicts-references`, `:ambiguous-coverage-scope`. Two deliberate non-refusals: a missing claim (v0.2.x acceptance is a contract) and claim-versus-version `selfContained` disagreement, which the ontology settles by making the claim authoritative. Every guard carries a mutation proof; most cannot fire on real data, which is why they are constructed.

  `of-arrangement` returns every part's report in arrangement order, base first, plus `completeness-counts` and conjunction predicates; `descriptions` returns the Evaluation prose. Each part is read against ITS OWN document, the one the arrangement bound to it, so a claim recorded as a bare reference resolves in the right graph.

  `coverage-interval` returns the window a verdict covers as `{:tick/beginning :tick/end}`, the shape `urpx.schedule` already emits, and `verdict-applies?` asks whether it contains an interval. **These are tick intervals in fact, not only in shape**: `tick.alpha.interval` reads any map with those keys, so Allen's relations work on the value directly and `ti/contains?` agrees with `verdict-applies?` case for case, which a test pins. The library requires tick nowhere. The window is `[beginning, end)`, matching `urpx.price/version-covers?`, because URPX states no convention and two readings of a window inside one library would be worse than either.

  **Both ends are `ZonedDateTime` and the zone is a required argument**, per this repo's own standing rule that the library uses `ZonedDateTime` end-to-end at output boundaries. URPX states a `TemporalScope`'s bounds as wall-clock readings with no zone, but returning them unzoned is the trap rather than the careful choice: `urpx.schedule` emits the same two keys carrying `ZonedDateTime`, so an unzoned window is the same map with an incompatible type, and the natural comparison does not fail loudly. Measured: `tick.alpha.interval/relation` throws on the mixture, while `contains?` returns `true` regardless of the values. A rate plan usually declares the zone as `urpx:timezoneIdentifier`; refuses `:urpx.coverage/zone-required` when none is passed.

  **`verdict-applies?` refuses on every real filing today** with `:coverage-scope-absent`, because zero `TemporalScope` nodes of any kind exist in the corpus or the fixtures. That is the honest answer rather than a bug: `false` would claim the verdict does not cover the window, which no document said, and `true` would be worse.

  **No arrangement-level verdict, and the absence is pinned as an exact key set** so that adding one fails a test. The six completeness members carry no ordering, and the absence is meaningful rather than an oversight: the same ontology uses `skos:broader` where it does mean a hierarchy. They are distinct kinds, not degrees. More decisively, every definition is relative to "the claimed scope" and no snapshot states one, so composition is undefined at the domain level rather than merely unranked.

- **`:repeated-ids`, a third caller policy**, deciding what one version `@id` naming several distinct snapshots means. `repeated-ids-refuse` (the default, unchanged behavior) refuses `:conflicting-version-id` naming every offending `@id` at once; `repeated-ids-union` merges them property by property, the reading a corpus whose own SHACL gate validates a *merged* graph already assumes. Shipped as a policy for the same reason as `:combine` and `:compatibility`: URPX does not say, and a client reading one filing wants a different answer from a client merging a corpus.

  Two costs, both stated at the call site. **RDF merges triples, not meanings**: merging a copy that states `applicableToRatePlanVersion` with one that omits it yields a node that *has* the property, which under `named-versions-narrow` restricts the modifier to exactly those versions, while the omitting copy meant no restriction at all. Over the production corpus that is the difference between 137 pairs and 144. And it silently reconciles a version `@id` whose option list is time-varying, which is a defect on any reading.

  Only vector-valued properties union. A Ref is a map and therefore a collection, but it is one value; unioning two concatenates their entries into nonsense, so two differing scalars refuse `:unmergeable-property`. A property one occurrence states and another omits merges to the stated value, because absence is not an assertion.

  A merged node is in no single document, so the one bound to it is **the document that IS the identity the version points back at**, derivable from the graph. Picking a source arbitrarily is wrong in kind rather than merely arbitrary: a bundled copy of a modifier version lives inside some plan's filing, so an arbitrary pick can bind a `urpx:RatePlan` as the modifier document.

- **`arrangement-pairs`**, the enumeration collapsed to one row per `(base version, modifier set)`, with `ProfileAlternative` choices not expanded. For comparing two independent enumerators on what composes with what, without disagreeing about whether a pending choice is one row or several.

- **A second corpus walker, `urpx-upstream`**, for the URPX standard's own `test-cases/`. A naive glob over that repository does not miscount, it **crashes**: `dev-docs/samples` carries an instant written `2026-01-01T06:00:00Z` that coercion refuses, and the error names no file. The test cases also exercise shapes the production corpus never uses — three top-level `RatePlanVersion`s, two base versions claiming `urpx:complete`, the only `TemporalScope` with `scopeKind urpx:coverage` in either tree, and a genuine `:ambiguous-version` tie.

- **`urpx.validate` reports conformance findings, distinct from schema errors.** Two rules the standard states that this library's schemas deliberately do not enforce, so a document can be schema-valid and still violate one: `:missing-coverage-claim` (checked against what the document says it targets, since requiring it unconditionally would reject every v0.2.x document) and `:self-contained-disagrees` (a violated MUST that `urpx.coverage` reads without refusing, because the ontology settles which value wins). A third, `:unparseable-target-version`, says so when a target version cannot be read rather than skipping the rule quietly.

  **They count toward the exit code.** They did not at first, and were therefore decoration: a file every schema accepted and the standard rejected exited 0 with the violation printed above it.

  `urpx:targetURPXVersion` is found by a deep walk. It sits on the `urpx:URPXPackage` wrapper in 67 of the 73 production filings and on a node inside the `@graph` in the other 6; a top-level read finds it in some documents and not others. Every filing in the corpus and every vendored fixture declares it, all v0.5.1.

- **`dev/corpus_sweep.clj` prints a coverage census**, before the enumeration because it is about documents and holds even when the enumeration refuses: verdicts by version type, how many claims state a coverage window (zero, everywhere, since the machinery existed), how many carry an Evaluation, and any `selfContained` disagreements.

- **`urpx:TemporalScope`, `urpx:TemporalPeriod` and the `ScopeKind` enum are modeled**, with `urpx.dsl/temporal-scope` and `temporal-period` builders. Groundwork for reading a coverage verdict's window.

  The node was transcription, not judgement: `urpx-shacl:TemporalScopeShape` is fully specified, `sh:closed`, with `urpx:scopeKind` `sh:minCount 1 sh:maxCount 1` against `ScopeKindValueShape` and the four date bounds plus `hasTemporalPeriod` each 0..1. `scopeKind` is the only required field, so a conformant scope may name its role and state no bounds at all.

  The EDGE is what nothing specifies. `urpx:hasTemporalScope` appears as `sh:path` **zero** times in the shapes graph and in `sh:ignoredProperties` **79** times, so every closed shape permits it and none constrains it: nothing says which classes may carry a scope, how many, or of which kind. It is modeled on `CoverageClaim` as 0..N because that is what unconstrained means, not because any filing carries several. There are zero `TemporalScope` nodes of any kind in the production corpus or the vendored fixtures.

- **`corpus/`, worked examples of the half that is not the library's.** One walker per corpus layout, with `urpx-rate-plans` as the first. Not on the library `:paths`, never in the jar, nothing in `src/` requires it. They exist because a glob looks equivalent to a walk and is not: over urpx-rate-plans a naive `**/*.jsonld` yields 87 documents where the layout walker yields 84, and the extra three are not filings — one is deliberately non-conforming so that corpus's own gate has something to fail on. Nothing errors; the count is simply about something else.

### Fixed

- **The README symbol check had stopped covering new namespaces, silently.** `docs_test` verifies that every project function a documentation block names still resolves, but it recognised aliases from a hardcoded set that was a second copy of the alias map, and unknown aliases were dropped by a `keep`. So `urpx.arrangements` shipped in 0.29.0 with a README section full of `arr/` calls that the suite never looked at. The set is now derived from the map, and a new test fails until every `urpx.*` namespace on disk is registered, because an unregistered namespace is the only way these checks can go quiet.

**Upgrading:** additive. Three new namespaces (`urpx.load`, `urpx.coverage`, and the `corpus/` walkers, which are not in the jar), a third caller policy, and no resolved number changes. Nothing existing changes behavior.

One thing to know if you enumerate a corpus: `:repeated-ids` defaults to `repeated-ids-refuse`, which is what 0.29.0 did unconditionally. A corpus where one version `@id` names several distinct snapshots still refuses; pass `repeated-ids-union` to merge instead. And `urpx.validate` now exits 1 on a conformance finding as well as a schema error, so a document that passed before and violates a stated MUST will now fail.

## [0.29.0] — 2026-09-22

### Added

- **`urpx.arrangements`, an optional enumerator for service arrangements.** A *service arrangement* is one `urpx:RatePlanVersion`, plus the compatible `urpx:RatePlanModifierVersion`s, plus one `urpx:ProfileAlternative` for each dimension any of them leaves open: fully determined, so given an instant inside its window it resolves to prices with no further input. If a choice is still outstanding it is not one arrangement but several.

  `urpx:ServiceArrangement` is **not** a URPX class. Zero occurrences in the ontology, zero in the SHACL shapes; nothing here writes it into a document or claims a `urpx:` prefix for it.

  **Optional by construction.** The namespace requires `urpx.price`; nothing in the library requires the namespace, and a test asserts that over the whole of `src/` by reading the `ns` forms on disk. A consumer that assembles its own compositions never loads the file.

  Public: `arrangements`, `plan-versions`, `applicable-modifiers`, `offered-by`, `arrangement-id`, `resolve-at`, and the two combining policies `one-modifier-at-most` (the default) and `every-subset`.

- **Discovery of what may apply, separate from what does apply.** `applicable-modifiers` answers which modifiers a plan version may take, read from `urpx:applicableToRatePlanVersion` where present and `urpx:applicableToRatePlan` otherwise; `offered-by` groups by `urpx:offeredBy` for a broader search. Which modifiers a given **customer holds** is in no document, because URPX places customer data outside its scope, so it is a caller policy passed as `:combine` rather than an assumption made here. Nothing in the ontology expresses exclusivity or combinability between modifiers, so `every-subset` is complete but will pair programs no filing says can be paired.

- **`dev/corpus_sweep.clj`**, a worked example of walking a corpus. It takes the corpus as an argument and names none itself: a library that reads a standard should not privilege one corpus in its own source. A git URL is pinned to a SHA via `clojure.tools.gitlibs`; a path is not, and since a clone may be legitimately ahead of its remote, the path form reports the tree's SHA, commits ahead of upstream, and dirty count rather than letting the two forms be assumed equal. `babashka/fs` and `tools.gitlibs` are `:dev` dependencies only; the library does no filesystem traversal and fetches nothing.

- **`segments-for` and `schedule-for`, horizons that take an arrangement.** `resolve-at` answers one instant; a price server publishes a window. Doing that through `urpx.schedule` directly meant taking an arrangement apart and rebuilding the per-document `:version` map by hand, which is the reconstruction `resolve-at` exists to prevent and the defect class this library has fixed three times. One builder now produces the binding for all three entry points, so they cannot drift about what an arrangement means, and both refuse `:version` and `:profile-alternatives` as `resolve-at` does.

  `segments-for` is the one to publish from: its boundaries come out of the documents rather than from sampling. Over one July day of PG&E E-TOU-C at a six-hour step it returns 00:00, 16:00 and 21:00 where the stepped walk returns 00:00 and 18:00, inventing a boundary that is not a transition and missing both that are.

- **`urpx.price/version-effective-at`, `version-expired-by?` and `version-covers?` are now public**, as the single readers of a version window. They were private and `urpx.arrangements` re-derived them, which is how it came to require `urpx:effectiveDateTime` where `urpx.price` also read `urpx:effectiveDate`. A consumer asking whether a snapshot is current now gets the same answer the resolver uses.

- **`urpx.coerce/uncoerced?`, `check-coerced!` and `temporal-signature` are public**, one implementation of the test that tells a coerced document from a raw one. `urpx.schedule` had it privately and `urpx.arrangements` needed it; two copies would have been two chances to disagree about what "coerced" means.

- **`:compatibility`, a second caller policy**, because URPX does not settle whether naming a `urpx:applicableToRatePlanVersion` narrows `urpx:applicableToRatePlan` or adds to it. `named-versions-narrow` (the default) fits the ontology's "which *specific* versions" and is the only reading under which the property can express a restriction, but it is **non-monotonic**: merging a document that adds a version statement removes compatibility with a plan named only at plan level, and RDF merge never retracts. `named-versions-add` is monotone but leaves the property able only to widen. Nothing in the artifacts decides it, so the library ships both rather than picking, exactly as it does for `:combine`.

  The two are indistinguishable on the corpus this library can enumerate today. They differ only about a modifier that names some version *and* names a plan no named version belongs to: 33 of 40 modifier versions name no version at all, and of the 7 that do, 4 name a version of the only plan they name. The remaining two are both among the identities currently refused as `:conflicting-version-id`, so the two questions are entangled and shipping both policies keeps settling one from silently settling the other.

- **Three refusals, because enumeration asks a question about a whole corpus.** A corpus that does not determine an answer gets a named `:reason` rather than one of the answers it might have meant: `:uncoerced-document`, `:urpx.arrangement/conflicting-version-id` (one version `@id` naming two snapshots that differ, every offending `@id` named at once), and `:urpx.arrangement/ambiguous-version` (two distinct snapshots of one identity sharing the latest effective instant, the input `urpx.price` already refuses). One `@id` published twice with byte-identical content is deduplicated, not refused.

### Fixed

All four were found by an adversarial audit of this namespace before its first release, seven of the eight lenses independently finding the first.

- **The enumeration depended on the order the documents were passed in.** `current-versions` broke a tie on `urpx:effectiveDateTime` with `(apply max-key ...)`, which returns the *last* maximal element, so when two snapshots of one identity shared an effective instant one was silently discarded and which one depended on seq order. Over the production corpus at `889344b` that moved the answer between **739 arrangements / 144 distinct pairs** in glob order and **731 / 136** reversed, with no error either way, and the eight pairs that vanished were all CPAU. `urpx.price` refuses exactly this input with `:ambiguous-version` and says "there is no rule here that picks among them"; a namespace sitting on top of it was quietly undoing that. Ties are now refused.

- **A version snapshot was paired with a document it did not come from.** `plan-doc` / `mod-doc` were `(into {} ...)` keyed by version `@id`, so where two documents publish one `@id` the loser's snapshot was bound to the winner's document. Measured on the corpus: **69 of 1728** base bindings and **641 of 1728** modifier bindings named a document that was not the snapshot's source. This is the `:version` defect class of 0.28.0 for the third time, so the fix is structural rather than another guard: the source document now travels with the snapshot as an `[identity-id version document]` triple and is never recovered by `@id`. The caller-supplied `:combine` policy still sees version nodes, never the triples.

- **`arrangement-id` was not unique, and its docstring and the README both said it was.** 1728 arrangements collapsed to 1068 distinct ids, 272 ids were carried by more than one arrangement, and 20 were shared by arrangements that genuinely differed. Now unique: 1041 of 1041 across all versions, 712 of 712 at an instant.

- **A conformant version stating only `urpx:effectiveDate` was silently dropped**, taking its whole plan out of the enumeration, while `urpx.price` resolved the same document normally. Both properties are `sh:minCount 0`. The cause was re-deriving what `urpx.price` already read, so `version-effective-at`, `version-expired-by?` and `version-covers?` are now **public** there and are the single readers; `urpx.arrangements` calls them instead of keeping a second copy. The same de-duplication moved the uncoerced-document test into `urpx.coerce`, which `urpx.schedule` now shares.

- **Uncoerced documents were answered rather than refused.** `urpx.core/load-graph` returns parsed but untyped entities, so `urpx:effectiveDateTime` is still a string. With an instant that threw a `ClassCastException` from deep inside; with no instant nothing compares a date at all, so the documents sailed through and **202** pairs came back where the coerced form gives 144 — a plausible number, not a refusal. Found when a consumer's cross-check probe walked into it, where it would have read as an enumeration disagreement between two implementations.

- **Six guards that could be deleted with the suite green**, found by re-running a mutation battery against the tests rather than by reading them. Five were still green after the first round of fixes: `resolve-at`'s per-document `:version` map, both branches of the compatibility rule, `applicable-modifiers`' documented `:at` option, and the selections component of `arrangement-id`. Each now has a test and a mutation proof that it fails when the behavior it names is broken.

  The `:version` map was invisible for a structural reason worth recording: **no fixture carries more than one version in one document**, so `urpx.price`'s own rules could only ever reach the same snapshot the arrangement had bound. Made visible with a constructed two-version document, the binding turns out to do more than choose. Without it `urpx.price` refuses the document outright ("Multiple nested version snapshots; this library reads one"), so the map is not an optimization over urpx.price's rules, it is what makes a multi-version document resolvable at all.

  One of the six pins a **contested reading**: `urpx:applicableToRatePlanVersion` is treated as exhaustive, so a modifier naming any version combines with those and no others. The ontology's wording supports it ("which *specific* rate plan versions are compatible") but the reading is non-monotonic, and merging a document that adds a version removes compatibility with plans named only by `applicableToRatePlan`. Over the production corpus the two readings differ by exactly 7 of 166 base/modifier pairs. URPX states neither; the test exists so that changing the answer is a deliberate act.

- **`check-no-conflicting-ids!` was called once per kind**, so it threw on the base versions before the modifier versions were examined and reported one conflicting `@id` where the corpus has three. Naming them all at once was the entire purpose of the function and calling it twice defeated it. It now takes every kind together, and `ex-data` gains `:by-kind`.

- **A test that compared a value to itself.** The `resolve-at` opt pass-through assertion passed a `:holiday?` opt and compared the result to the same number the un-opted call returns, so the wrong opt, the opposite opt and a nonsense opt all satisfied it. It cannot be fixed by comparing values either: the predicate provably does not change any fixture's rate, and SCE Schedule D never consults it at all. It now counts the predicate's own invocations on the one fixture that invokes it. Every guard added here carries a mutation proof that it fails when the behavior it names is broken.

### Changed

- **`resolve-at` refuses `:version` and `:profile-alternatives` instead of merging them.** An arrangement determines both. Letting a caller's value win would restore the exact defect the function exists to prevent (an opt reaching, or failing to reach, the document it belongs to: both such defects are fixed in 0.28.0); letting the arrangement win silently would ignore an opt the caller meant. `ex-data` carries `:reason :urpx.arrangement/opt-determined-by-arrangement`. A caller who wants a different version or selection wants a different arrangement.

**Upgrading:** additive. `urpx.arrangements` is new and optional, `urpx.price` and `urpx.coerce` gain public functions that were private, and no resolved number changes.

Two things to know before pointing the enumerator at a corpus. It **refuses** rather than enumerates when one version `@id` names two snapshots that differ; the production corpus has three such identities today, so it refuses there. And the `:compatibility` reading is genuinely unsettled upstream: `named-versions-narrow` is the default because it is what the corpus and the reference implementation assume, not because it is known right, and the two readings can only disagree about a modifier that names some version *and* names a plan no named version belongs to.

## [0.28.0] — 2026-09-22

### Fixed

- **A `:version` opt for the base plan silently dropped every modifier.** `plan-version` short-circuited on `(:version opts)` before anything looked at *which* document was being resolved, and `resolve-prices-with-modifiers` threads one opts map to the base plan and to every modifier. So the base plan's snapshot was handed to each modifier as its version; that node carries no `urpx:applicableToRatePlan`, `modifier-applies-to?` therefore said no, and every modifier was dropped with no mark and no refusal.

  SCE Schedule D composed with CARE returned **`0.31085`** instead of `0.19804875` — the full undiscounted rate, **57% high**, over five ledgers instead of eight. A variant where the plan node carries no `@id` double-counted the base plan's ledgers instead, at `0.68538`. The library's own refusals routed callers straight into it: `:ambiguous-version` and `:version-without-effective-instant` both end "Pass `:version` to choose explicitly".

  A version now applies only to the document it is a snapshot of, identified by `urpx:forRatePlan` / `urpx:forRatePlanModifier` or by the document nesting it, and is ignored elsewhere. Ignoring is not the silent failure it replaces: the other document then resolves by its own rules, which is correct, where before it was discarded. Present since 0.11.0.

- **The holiday calendar on a composition comes from the resolved version.** `resolve-prices-with-modifiers` called `holidays/derive-predicate`'s 1-arity, which re-derives the snapshot by reading the nested one; that ignores a caller's `:version` and refuses outright on a document nesting several, which is exactly the document `:version` exists to settle. So a composed call threw from the holiday path after `:version` had already chosen, while the same call through `resolve-prices` succeeded. The snapshot is in hand at that call site. No resolved number changes: the predicate built there does not set the composed result's season or TOU, and no fixture or constructed document produced a different tier count under the real calendar, `(constantly true)` or `(constantly false)`.

### Added

- **`:version` accepts a map from document `@id` to snapshot**, scoped the way `:versions-by-plan` already is, so a composition can pin several documents explicitly. A document the map does not name resolves normally.

- **`:version-opt-not-a-version`**, refused when `:version` is neither a snapshot nor such a map. There is no document it could apply to, so reading on would silently ignore what the caller asked for.

**Upgrading:** no resolved number changes unless you pass `:version`. If you do, and you pass it to `resolve-prices-with-modifiers`, you were getting the base plan's rate with every modifier silently dropped; you now get the composed rate. `marginal-unit-rate` still carries six `:reason` values; `resolve-prices` now carries seven, the new one being `:version-opt-not-a-version`.

## [0.27.0] — 2026-09-22

Version windows on modifiers, and two schedule defects that made `price-schedule` disagree with `price-segments` about the same instant.

### Fixed

- **A superseded `urpx:RatePlanModifierVersion` kept applying its prices.** `version-covers?` had exactly one call site, in `resolve-prices`, so a modifier's validity window was read when choosing among several candidates and never when there was one — which every filing has. SCE Schedule D composed with a CARE modifier expired 2026-04-01 returned **`0.19804875`** at 2026-07-15, the full discount, three and a half months after the filing stopped describing anything. The base plan has refused the symmetric case since 0.22.0, so the answer depended on which side of a composition the superseded filing sat.

  Marked, not dropped: every ledger a non-covering modifier produces carries `:urpx.resolved/outside-version-window`, and `marginal-unit-rate` refuses. Dropping a superseded discount would quietly raise a bill, and a caller reading tiers off a superseded filing deliberately is not blocked. Scoped to prices that would actually be summed, so an expired modifier contributing only non-energy or already-excluded prices costs nothing and does not refuse.

  Reported from urpx-rate-plans, where end bounds prepared for three modifier versions were inert in the consumer.

- **`price-schedule` merged across a version boundary and reported one side's answer for the whole span.** `same-ledgers?` compares `:urpx.resolved/ledgers`, but a base plan's version-window mark sits on the *result*, so a boundary that changed nothing else was invisible. Both directions were wrong, and the false-refusal one was reachable on an unmodified vendored fixture with no mutation: `sce-d-2026-03-20` takes effect on the 20th, and a March window returned **one** interval carrying the not-yet-effective mark, so `marginal-unit-rate` refused for the whole month including the twelve days the filing covers, while `price-segments` split correctly and a direct resolve returned `0.31085`. A version expiring mid-window failed the other way, carrying the covered side's rate past the bound.

  `same-ledgers?` now compares the mark as well. Measured across 43 corpus plans over two month-long windows, base and composed: exactly **1 of 86** schedules changes interval count, the intended boundary, and total intervals move `3206 → 3207`. No over-splitting.

- **`price-segments` fragmented a window it should have merged.** The result-level mark carried `:at`, the instant resolved, and `price-segments` merges on the whole resolved value, so a base filing queried across a window it does not cover returned **31** segments over a month where it returns 1, each holding identical prices. Present since 0.22.0.

### Changed

- **Both version-window marks now carry the same three fields** — `:version-id`, `:effective`, `:expires` — and neither carries a per-instant field. Two marks with two shapes was two chances to reintroduce the same defect, and it had already happened once: the modifier mark was written by copying the result-level one, `:at` included, which made an hourly month over an out-of-window composition return **744** intervals where it returns 1. A test asserts the two key sets are equal rather than asserting each separately.

- **The `:outside-version-window` refusal names every stale ledger, grouped by the version that made it stale.** Naming only the first cost a caller one refusal round per stale modifier to discover a set the library already had, and with two versions involved there was no single "the version used" to name. `ex-data` gains `:expires`, `:ledger-id` and `:stale`, so a consumer classifying programmatically is not worse off than one reading the prose.

**Upgrading:** narrowly breaking. `(:at (ex-data e))` is gone from an `:outside-version-window` refusal and the message says "does not cover the instant resolved" rather than naming it — the instant is the caller's own, and `:effective`/`:expires` identify the window. `price-schedule` returns more intervals for a window straddling a version boundary, which is the fix rather than a side effect. Measured across the production corpus at 19 probe instants, **0 of 817** resolved cells change value.

## [0.26.0] — 2026-09-21

### Added

- **`:urpx.resolved/condition-gated`** on a tier whose Price, or whose PriceDefinition, carries a `urpx:ConditionExpression` this library does not evaluate. Such a price fires **unconditionally** at resolution, which is a silent assumption that its predicate holds, and it is epistemically the same position as an event-driven price. Event-driven prices have been marked since 0.19.0 and excluded from the marginal since; condition-gated ones were neither. Composing SCE TOU-D-PRIME with its CPP modifier returns **-0.13897** at summer peak and **-0.46336** off-peak, a customer being paid to consume, because a credit gated on "is a CPP event called today" and a credit gated on a grandfathered-customer attribute both apply at every instant.

  Each gate carries `:urpx.resolved/gate-id`, `:urpx.resolved/gate-name` and `:urpx.resolved/gate-on` (`:price` or `:price-definition`). Rules stated as a bare `{@id}` reference are dereferenced, for the same reason the month gate dereferences them.

  **Marking only. Nothing is excluded from any aggregate and no resolved number changes.** Measured over the 76-file production corpus at four instants, 101,992 of 105,177 resolved tiers are condition-gated, so excluding wholesale would delete the corpus rather than correct it. The population that actually reaches a per-kWh marginal is far smaller: 1,352 of 3,717 tiers on import energy ledgers, the other 100,640 being export prices that `urpx:flowDirection urpx:reverse` already keeps out of the sum.

  A rule carrying only `urpx:month` is **not** reported, because that constraint is evaluated. Conditions inside a `urpx:ProfileAlternative` (required on every alternative by the shapes) and inside a `urpx:EligibilityRule` (which answers who may take the tariff) are not reported either, since neither hangs off a Price.

### Changed

- **`marginal-unit-rate` refuses with `:condition-gated-tier` rather than summing a price whose condition it cannot evaluate.** Closes the half of [#5](https://github.com/grid-coordination/clj-urpx/issues/5) that [#9](https://github.com/grid-coordination/clj-urpx/issues/9) tracked. Pass `:assume-conditions-hold? true` for the every-condition-true upper bound, as `:include-dynamic-pricing? true` gives the during-event one.

  **Two different defects reach this guard, and the larger one is a modeling problem.** SCE's CCA-CRS files twenty PCIA vintages as twenty condition-gated prices, one per vintage year, of which exactly one applies to any customer. Summing all twenty took SCE TOU-D-PRIME from `0.60067` to **`0.95664`**, where the true range is `0.59423` to `0.62691`. That is the identical defect this function has refused since 0.20.0 when a filing states it as `urpx:PriceSetAlternatives`, which is how PG&E files the same concept: the answer used to depend on which layer a filer expressed exclusivity in, and now it does not. The other defect is a genuine unevaluable predicate: SCE's CPP credits, gated on "is a CPP event called today" and on a grandfathered-customer attribute, applied at every instant and made the composed marginal **negative**, `-0.13897` at summer peak, which says a customer is paid to consume.

  Scoped to prices that would actually be summed. 100,640 of the corpus's 101,992 gated tiers are export prices in net-billing matrices where the gate is the lookup key; `urpx:flowDirection urpx:reverse` has kept those out of the marginal since 0.21.0 and they do not refuse it either.

**Upgrading:** measured across the production corpus, **35 of 172 composed resolutions** change from a number to a refusal, on **five base plans**: PG&E E-ELEC, and SCE D, TOU-D-4-9PM, TOU-D-5-8PM and TOU-D-PRIME. Every one of those numbers was wrong, and the ones driven by CRS were wrong by more than half the base rate. The remaining 137 are unchanged.

Discounts are **not** affected. A `urpx:PriceSummary` whose members carried a gated price would have propagated the refusal into every CARE and FERA composition; measured, none does, and SCE Schedule D still returns `0.19804875` with CARE and `0.2550770` with FERA, identical with and without `:assume-conditions-hold? true`. Flagged from urpx-rate-plans before release, and checked rather than assumed.

Where the gated set is mutually exclusive, the filing wants `urpx:ProfileAlternatives` and the refusal becomes a selection you can make; where the gate is a genuine predicate, `:assume-conditions-hold? true` restores the previous value explicitly.

## [0.25.0] — 2026-09-21

`urpx:referencesProfileAlternative` on a `urpx:PriceDefinition`, and a narrower rule for when withholding costs the caller an answer.

### Added

- **`urpx:referencesProfileAlternative` is now read on `urpx:PriceDefinition`.** URPX names PriceDefinition explicitly as a place to scope applicability to a `ProfileAlternative`; clj-urpx read it only on `PriceSetAlternative`, so the slot existed in the vocabulary, passed the shapes at `sh:minCount 0`, and did nothing. Given the caller's `:profile-alternatives`, a definition that references no alternative applies as before, one that references the chosen alternative applies, one that references a different alternative in that dimension is filtered out silently, and one in a dimension nothing was chosen for has its prices withheld with the ledger saying so. The selection map is keyed by the `ProfileAlternatives` container while a definition names an alternative; `urpx:hasProfileAlternative` bridges them, so no new vocabulary is needed.

  Read on the base plan and on modifiers alike. A matrix of mutually-exclusive cells is a modifier shape, so a version that read the property only on the base plan would have been a no-op exactly where it is needed.

  This is where a multi-dimensional choice lands. `ProfileAlternativesShape` is `sh:maxCount 1` on `hasAlternativeDimensions`, so the dimensions live in one container and its alternatives enumerate the cells of the cross product: PG&E's own baseline territory and code set is 2 dimensions and 20 cells. Naming **several** alternatives on one definition refuses with `:multiple-profile-alternatives` rather than defining a combining rule for a shape no filing has.

  The corpus has zero uses today. The slot has to work before a filing can be authored against it.

- **`:urpx.resolved/withheld-prices`** on a withheld ledger, alongside the existing `:urpx.resolved/unselected-alternatives`. The latter says that something was withheld; the former says what, under the same keys a resolved tier uses.

- **Two further refusals from `resolve-prices`**, both where reading on would return a confident wrong number. `:ambiguous-profile-alternative` when the alternative a definition names is listed by two `ProfileAlternatives`, so there is no one dimension to read the caller's choice from. `:unknown-profile-alternative` when `:profile-alternatives` chooses a value the container it is keyed under does not list.

  The second is a **behavior change on a path every filing already uses**, not just on the new slot. A stale or mistyped alternative id used to be skipped silently, which dropped the whole dimension: on PG&E E-TOU-C composed with its vintage-PCIA modifier it returned 0.46886 where the correct id returns 0.49859, and the PCIA ledger was simply absent from the result. The check runs once per document, against the containers that document declares, so a key meant for a modifier is still ignored by the base plan and vice versa. A test that had pinned the silent skip as the specification was rewritten.

- **`:summary-member-unselected`**, a new `:urpx.resolved/unvaluable` reason on a derived price whose `urpx:PriceSummary` names a member in a dimension the caller chose nothing for. Excluding such a member understates the subtotal and including it overstates it, so the summary declines to be valued.

### Changed

- **Withholding refuses the marginal rate only when it would make the sum short.** `marginal-unit-rate` refused whenever a withheld energy ledger was present, whatever had been withheld. An event-driven price (`urpx:hasDynamicPricing`) or a recurring charge is already outside a non-event per-unit marginal, so hiding it changes no number; the refusal is now decided by the same predicate that excludes a tier from the sum. Asking for the during-event upper bound with `:include-dynamic-pricing? true` wants that price again, and the same document refuses again.

  Without this, moving a set of event-driven prices onto the selection layer would convert a correct rate into a refusal no caller could clear. CPAU's E-HRA cells are the utility's own reserve level and generation band, reset by council vote; a consumer has no source for either, so the selection the refusal demands could never be supplied. Vintage-PCIA is the case that keeps the refusal, being an ordinary per-kWh price and an attribute of the customer the caller does know. Raised from urpx-rate-plans before authoring against the new slot.

- **A recurring modifier price is now excluded from the per-kWh marginal.** `urpx:intervalDuration` is read from the `PriceDefinition` on the modifier path as it always was on the base plan. It was unreachable there, because the modifier path never called the function that stamps it, so the exclusion `marginal-unit-rate` documents for recurring charges could not fire for any modifier tier. No vendored fixture changes: the modifier definitions that carry an interval today all sit on non-energy ledgers.

### Fixed

- **A `urpx:PriceSummary` counted members whose PriceDefinition does not apply.** Member gating read TOU period, month and season but not `urpx:referencesProfileAlternative`, so a definition scoped to an alternative the caller chose against was correctly dropped from `:urpx.resolved/tiers` and still summed into every subtotal naming it. A derived price then multiplied a total containing a charge the customer does not pay. On SCE Schedule D composed with its CARE modifier the marginal rate came back **0.08043875 against a correct 0.11866200**, a third low, with no mark and no refusal. Gating two of the three places applicability is consulted is worse than gating none, and this release is what made the three disagree.

- **A composition indexed its alternatives once per document, so three paths could disagree about one PriceDefinition.** A composed resolution reads ONE `:profile-alternatives` map, but the base plan, each modifier, and the `urpx:PriceSummary` member gate each built their own index from their own `urpx:hasProfileAlternatives`. Two consequences, both introduced by this release: a definition scoped to a container the *other* document declares was `:unselected` for every possible selection, so the withhold could not be cleared from a composed call at all; and where one `ProfileAlternative` @id was listed by a different container in each document, the modifier's tier path answered `:selected` while the summary answered `:not-selected`, in one result, silently dropping a member the tiers kept. There is now one index over every document in the composition, merged on container @id so a shared dimension declared in both does not read as two. An alternative genuinely listed by two different containers still refuses with `:ambiguous-profile-alternative`, across a composition for the same reason it already did within one document.

- **A modifier ledger marked from two sources reported only one.** A ledger can be marked both by prices surfaced from an unselected `PriceSetAlternatives` and by a definition scoped to an unchosen alternative; `:urpx.resolved/unselected-alternatives` dropped the second whenever the first was present, so a caller who supplied the selection the error named got a second refusal about something the first never mentioned.

- **`:all-energy-tiers-excluded` blamed exclusion for prices that were withheld.** Its message told the caller to inspect fields on `:urpx.resolved/tiers`, which is empty in that case; it now names `:urpx.resolved/withheld-prices` and says a selection would give the ledger some tiers.

- **`urpx:referencesProfileAlternative` was not declared on the PriceDefinition schema.** It validated by accident, as an unknown key on a non-closed shape, and passed through coerce and emit untouched. What it did not get was the single-or-vector normalization every other reference property has, so the coerced document carried a shape the schema does not describe and `urpx.emit` round-tripped it unnormalized. The resolver itself tolerates both forms, so this is a modeling hole rather than a wrong number: authoring against a slot that validates by accident is how the next filing finds one.

### Build

- **A valid selection could be refused where a container and its price sets disagree.** `validate-selection!` checked the caller's value against the container's `urpx:hasProfileAlternative` list only, while the price path matches it against each `PriceSetAlternative`'s own `urpx:referencesProfileAlternative`. Nothing in the shapes forces those two sets to agree; all eight pairs in this corpus do, so nothing changes today, but the index now covers both, because refusing a document the library resolves correctly is the expensive direction. The fold happens where the composition index is **built**, covering every document at once: folded per path instead, the base plan's check could not see a cell only a modifier's price sets name, and the summary member gate, reading the index before any fold, saw none of them at all.

- **A container listing the same alternative twice read as two dimensions** and refused an unambiguous selection with `:ambiguous-profile-alternative`. A duplicate entry is a redundancy in the document, not a second dimension.

- **Modifiers that do not apply to the plan are no longer indexed.** The composition index was built from every modifier handed in, so one naming a different plan could contribute its containers to a resolution it takes no part in, and reading its version to get them surfaced its version errors there too. `urpx:applicableToRatePlan` now gates the index as it already gated the prices.

- **`deploy` refuses unless the artifact is the one this commit built.** It publishes whatever is in `target/`, so it was trusting that a human ran `ci` first, on this commit, from a clean tree; trusting that shipped a pom from an eight-version-old build once. The embedded build-provenance is now the witness: `deploy` checks the jar's recorded commit and version against `HEAD`, and refuses on a snapshot version, a dirty tree, a missing or misplaced `v<version>` tag, a stale or unbuilt `target/`, or an unset `CLOJARS_PASSWORD`. It also refuses when `src/` or `resources/` holds a file git IGNORES that would actually be packed, because `ci` packs those directories whole while neither `git status --porcelain` nor build-provenance's own dirty check reports ignored files, so such a file rides into the jar with every gate quiet. Files `tools.build` excludes on its own (`.DS_Store`, `*~`, `#...#`, `.#...`) do not trip it, since refusing a release over something the artifact provably never packs is a guard that fires on legitimate work. A corrupt or unreadable jar reads as "no provenance" and refuses by name rather than escaping as a `ZipException` that would preempt the refusals naming the real fix. Added `clojure -T:build tag` and `clojure -T:build release`, and `doc/RELEASING.md`.

**Upgrading:** two changes can turn a call that returned a number into a refusal, and both were returning a wrong number before. A `:profile-alternatives` value that names something its container does not list is now `:unknown-profile-alternative` rather than a silent skip; check your selection ids against `urpx:hasProfileAlternative` if you see it. And a plan whose `urpx:PriceSummary` names a member in an unchosen dimension now reports `:summary-member-unselected` rather than summing it. Neither fires on any filing that does not use `urpx:ProfileAlternatives`.

## [0.24.0] — 2026-09-21

The last two findings from the silent-drop sweep, which share one cause: the unselected-alternatives mark was computed from surviving tier entries, and a ledger with none is elided before anything can mark it.

### Fixed

- **An unselected `urpx:PriceSetAlternatives` vanished without trace on the base plan path.** `prices-from-alternatives` contributes nothing for a set the caller made no selection for, so the prices, the ledger they would have populated, and any evidence they existed all disappeared together. **Six shipped base versions** carry such a set. The ledger now survives carrying `:urpx.resolved/unselected-alternatives`, so a consumer can tell an unchosen dimension from a plan that has none.
- **Emptying an alternatives ledger deleted the refusal that guards it.** Because the mark came from surviving tiers, any route that emptied the ledger also removed the protection: dating PG&E's 18 vintage-PCIA prices into the future took `marginal-unit-rate` from refusing to answering 0.46886, silently short a component spanning 0.06391/kWh. Removing content can no longer remove a guard.

  Its originally reported trigger — stripping `urpx:startDate` from those prices — was already neutralized by the 0.22.0 start-date fix and now refuses correctly. The defect survived that; only the route to it changed.

### Changed

- The refusal is **energy-aware**. A withheld ledger refuses the per-kWh rate only when it is energy-measured, because withholding a per-day base charge cannot change a per-kWh figure. PG&E E-TOU-C exercises both shapes on one plan.

## [0.23.0] — 2026-09-21

### Fixed

- **The 0.22.0 summary tier fix could not reach the corpus.** It read `urpx:serviceTierNumber` from the member Price only, with no fallback to the Price's `PriceDefinition`. URPX puts the property on both, and **69 of 73 corpus filings carry it on the definition**, one per tier, which is the house idiom; the fixture that validated the fix is pinned at a commit predating that idiom and carries it on the Price. So the fix computed the right answer on the fixture and no answer at all on real filings: SCE Schedule D's CARE discount stayed at −0.12139725 where −0.10070125 is correct.

  `tier-info` has read both placements since long before, three hundred lines up in the same file. Writing a second, narrower rule instead of reusing it was the defect. Both placements now give −0.10070125, and a test pins both, because a test covering one placement is exactly what let the narrow version through.

  Reported from urpx-rate-plans, which declined to work around it on the data side: putting the discriminator back on the Price would have made SCE D the only filing in 73 doing so, which is the anomaly that caused the 3.09× SDG&E defect in the first place.

## [0.22.0] — 2026-09-21

Silent losses. A sweep looked for every way a SHACL-conformant document can lose content or produce a wrong number with no diagnostic; it returned seven confirmed findings, rejected nineteen candidates as correct gating or existing loud refusals, and caught four overclaims among its own results. Two more came from urpx-rate-plans. **Resolved prices change on filings that carry any of these shapes.**

### Fixed

- **An absent `urpx:startDate` discarded the Price.** Four scoping sites read the date with `when-let` and dropped anything without one, while the PriceSet gate three lines above treats a nil date as permissive. It deleted content from a shipped filing: PG&E's E-TOU-C baseline-tier modifier carries four Prices and no dates anywhere, resolved to **zero ledgers and zero tiers**, and composing it with its base plan changed no number and reported nothing. Absence now means no start constraint, which is what `sh:minCount 0` says. A Price that has not started yet is still excluded.
- **`urpx:startDateTime` was never read.** Modeled on Price, PriceSet and PriceSetAlternative, written by `urpx.emit`, ignored by the resolver: a filing stating its date that way was accepted, validated, round-tripped and then priced as though it had no date. On SDG&E DR, restating one PriceSet's date that way took 13 tiers to 3.
- **A single version was never checked against the instant.** 0.17.0 routed the one-candidate case past the effective and expiration tests, and every one of the 40 base filings carries exactly one version, so that was the branch standalone resolution always took. SCE D answered **0.31085 five weeks before its version took effect**, byte-identical to the in-window answer; LADWP R-1A answered seven years early. Results now carry `:urpx.resolved/outside-version-window` and `marginal-unit-rate` refuses with `:reason :outside-version-window`. Season and TOU period still answer, because those are structural questions about a plan's shape.
- **`urpx:touPeriodNumber` on a Price was ignored.** URPX puts it on Price as well as PriceDefinition and 43 corpus Prices use it. Two different wrong answers followed: in a summary every TOU variant of one charge summed, overstating SDG&E EV-TOU's CARE discount **3.09×** (−0.9255435 against −0.2999885); in the tier path the variants collided in the latest-wins dedupe and the survivor was reported **at every hour**, so a transmission charge read at its on-peak value around the clock.
- **A summary summed every rung of a block-tier ladder.** Those are alternatives per kWh. SCE Schedule D's discountable subtotal names both Conservation Incentive Adjustment rungs; summing both gave 0.37353, a subtotal matching no bill any customer can receive, and understated the CARE discount by **10.4%** (−0.12139725 against −0.10070125) and FERA by 4.5%. This is the defect `marginal-unit-rate` had within a ledger in 0.20.0, one level down, and the partition is now one shared function rather than two copies.

### Added

- Three fixtures pinned at shapes the live corpus no longer has: `sdge-ev-tou-2026-04-01` (12 Prices carrying `touPeriodNumber` directly — urpx-rate-plans has since collapsed these, so this is the only place the case survives), `pge-e-tou-c-baseline-tier-2026-03-01` (four Prices, no dates), and `sce-d-2025-11-15` earlier.

**Upgrading:** a filing carrying any of these shapes prices differently, generally more completely. `marginal-unit-rate` gains a fifth refusal, `:outside-version-window`, which fires when a filing is resolved outside the window its version describes — check `:reason` rather than catching broadly.

## [0.21.0] — 2026-09-21

### Fixed

- **Export ledgers were summed into the import marginal rate.** `urpx:flowDirection urpx:reverse` marks compensation for energy sent back, which is not a cost of the next kWh consumed. `marginal-unit-rate` ignored the property entirely, so an export ledger was added to an import rate as though it were a charge.

  Measured: composing PG&E E-ELEC 2026-03-27 with its NBT modifier returned **-213.15920/kWh**, because four export ledgers carrying 4,610 hourly avoided-cost values were added to a 0.56006 import rate. It now returns 0.56006, the base plan's own rate. The audit that found it counted 22 compositions summing export compensation into an import marginal, 17 of them real defects.

  `urpx:net` and `urpx:bidirectional` still contribute. Neither appears in the corpus, so excluding them would be speculation; a netted quantity in an import marginal is genuinely ambiguous and that is the line to revisit if one appears.

### Added

- `:urpx.resolved/flow-direction` on resolved ledgers, when the filing declares one. An export ledger and an import ledger are not the same kind of thing, and a consumer summing per-kWh charges needs to tell them apart.

### Changed

- `has-energy-ledger?` now agrees with `marginal-unit-rate` about what counts, so the guard cannot answer yes where the aggregate would refuse. A plan whose only energy ledger is an export ledger reports `false` and refuses with `:no-energy-ledger`.

## [0.20.0] — 2026-09-21

**`marginal-unit-rate` returns a different, larger number on most plans. Read the upgrade note.**

### Fixed

- **Additive rate components were dropped from the marginal rate.** `marginal-unit-rate` kept only the first tier of each energy ledger. That is correct for a block-tier ladder, which is what the function was written for, and wrong for an additive rate stack, which is how delivery charges are modeled throughout the corpus. A delivery ledger carrying ten components contributed one.

  Measured at 2026-07-15 18:00 PT:

  | plan | reported | correct | |
  |---|---|---|---|
  | SDG&E EV-TOU 2026-04-01 | 0.57086 | 0.86302 | 33.9% low |
  | SCE TOU-D-PRIME 2026-03-20 | 0.55208 | 0.60067 | 8.1% low |
  | SCE TOU-D-4-9PM 2026-03-20 | 0.54513 | 0.49264 | **over**stated by 0.05249 |

  The error was **not one-sided**, so no sanity bound would have caught it: SCE's overstatement comes from dropping a negative Baseline Credit. A sweep of 40 base plans at four instants found 74 resolutions dropping a nonzero remainder across 26 of the 40 plans.

  `urpx:serviceTierNumber` separates the two uses: a numbered tier belongs to a ladder, a bare one is an additive component. Across that sweep, multi-tier energy ledgers were 183 additive, 31 ladder and 3 mixed, all three being the same ledger. A mixed ledger contributes its bare components plus the lowest numbered rung; on the one real case those two policies agree exactly, and only this one stays correct if a genuine ladder ever appears beside additive components.

**Upgrading:** every consumer of `marginal-unit-rate` should expect a different number on most plans, usually larger. Reported rates moved on 26 of 40 base plans in the corpus. Anything publishing this value downstream — an OpenADR 3 interval price, for instance — was publishing an understated rate on most plans and an overstated one on some. There is no flag to keep the old behavior, because the old behavior was wrong.

### Documentation

- A README section stating what the marginal rate sums and why, and the `urpx:serviceTierNumber` signal it turns on.
- Version archaeology cut from the customer-profile-alternatives section, which now leads with the selection-versus-filtering-layer distinction URPX states explicitly, and records that `urpx:referencesProfileAlternative` is read on `PriceSetAlternative` only and not yet on the `PriceDefinition`-level slot the shapes allow.

## [0.19.0] — 2026-09-21

Event-driven prices, and a fix for a guard that shipped broken in 0.18.0.

### Fixed

- **`price-segments` rejected every modifier filing in the corpus.** Its uncoerced-document guard threw on any date-shaped string, which is incoherent next to harvesting by java.time type: after coercion a genuine temporal *is* a java.time value, so a residual date-shaped string is evidence of nothing. `urpx:version` is `rdfs:range xsd:string` and every filing uses its effective date as the version label, so `"2026-03-20"` is a perfectly coerced string. All 33 modifier filings and 3 base plans were refused, and therefore every composed horizon. The guard now tests the signature of an uncoerced document (no java.time values anywhere while date-shaped strings are present) rather than the shape of a string.

### Changed

- **`marginal-unit-rate` excludes event-driven prices by default**, closing the open half of [#5](https://github.com/grid-coordination/clj-urpx/issues/5). A Price carrying `urpx:hasDynamicPricing` applies only while its event is called: critical peak events are capped around 50 hours a summer against roughly 2,200 summer hours, so summing one unconditionally answered "what would this cost during an event" while reading as the ordinary rate. Measured on SMUD CPP, composing the modifier moved the rate from 0.3765 to 0.8765; it now stays at 0.3765, which is the non-event baseline the issue predicted. Pass `{:include-dynamic-pricing? true}` for the during-event upper bound.

  Unlike the mutually-exclusive alternatives this function also refuses on, there is a defensible default here rather than an arbitrary pick: no-event-today is the ordinary state.

- Tiers are now excluded **before** the first tier is taken rather than after, so a ledger mixing an event charge with an ordinary price contributes the ordinary one instead of dropping out entirely.
- A new refusal, `:all-energy-tiers-excluded`, for the case where exclusions empty the sum. Returning `0M` there would be the nil-reads-as-free failure one level up again.

### Added

- **Four fixtures for the event-driven pair**: SMUD TOD-5-8PM with its CPP modifier, and SCE TOU-D-PRIME with its PRIME-CPP modifier. SMUD is the clean case. SCE is vendored because it is the honest half of the story: its two credits are gated by `urpx:ConditionExpression` predicates this library does not evaluate, so excluding the event charge leaves them applying and the marginal rate goes **negative**. That number was already wrong; it is now wrong more visibly. A test pins it so the limit is not mistaken for a fix.

### Documentation

- Four stale README claims corrected, all from recent releases: that consumers wanting a non-event rate must filter the tiers themselves (now the default); a refusal-reason list missing `:all-energy-tiers-excluded`; a claim that a ledger with no `MetricInput` makes the aggregate return `0M` when since 0.15.0 it refuses; and a feature bullet predating `price-segments`.

## [0.18.0] — 2026-09-21

Horizons.

### Added

- **`urpx.schedule/price-segments`**, a piecewise-constant price schedule whose boundaries are derived from the documents rather than discovered by sampling. Built for a client that wants the price now and for the next N hours as intervals to hand a solver, rather than N point resolutions.

  Temporal literals are harvested by java.time **type** rather than by property name, so a temporal property URPX adds later is picked up without this code changing; a name list would have to be kept in step, and the one it missed would be a missing candidate. Those times are crossed with every local date the window touches, every local midnight is included unconditionally, and the zone's DST transitions are added. The candidate set is a deliberate over-approximation: an extra candidate costs one resolution and merges away, a missed one is a silently wrong boundary.

  Every local midnight being a candidate is what absorbs every date-granularity gate at once: day of week, holidays including observance shifting, season and month boundaries, applicability month rules, and every start, effective and expiration date.

- **DST handled at the instant level.** A local time inside the spring-forward gap never occurs and is clamped to the transition instant; `ZonedDateTime/of` would land past it by the gap's width, which is not always an hour. A local time in the fall-back overlap occurs twice and yields **both** instants, so the repeated hour is priced twice rather than merged away. Zone transitions are enumerated by iterating `ZoneRules.nextTransition` rather than assuming two a year, because real tzdb data does not oblige.
- **Refusals become segments.** A resolution that throws partway through a horizon is reported as `:urpx.interval/refused` rather than aborting the window, and equal refusals merge so an uncovered stretch is one segment rather than one per candidate.
- A test pinning the premise the whole design rests on: `urpx.price` reads the query instant through exactly `.toLocalDateTime`, `.toLocalDate`, `.toLocalTime` and `.getDayOfWeek`, and never through `.toInstant`, `.getOffset` or `.getZone`. Resolution is therefore a pure function of the local date-time, which is why boundaries can be derived from literals at all. A resolver that read the offset would invalidate the candidate rule without touching the file that implements it.

### Fixed

- **`price-schedule` reported prices outside the requested window.** The step grid is anchored to the start of `start`'s hour, and that anchoring leaked: a request beginning at 17:23 returned a first interval beginning at 17:00 and a last interval ending up to a full step past `end`. Intervals are now clipped to the window, and clipped before merging, while interior boundaries still fall on clock hours.

## [0.17.0] — 2026-09-21

Version selection is time-aware.

### Fixed

- **The version snapshot was chosen arbitrarily when a plan had more than one.** `plan-version` fell back to `(first ...)` over the back-pointer index, taking whatever the index happened to hold rather than what was in force. Same class as the vintage-PCIA arbitrary pick: a defensible-looking number with no rule behind it. It stayed invisible because every filing carries exactly one version, so loading one file gives one answer; six SCE plans in the corpus already carry two, and a forecast client loading several versions to cover a window is the case it was silently wrong for.

  The rule is now the latest `urpx:effectiveDateTime` at or before the instant, inclusive, skipping any version whose `urpx:expirationDate` has passed. All 84 version snapshots in the corpus carry an effective instant and **none** carries an expiration, so a version's span is inferable only from its successor's start; the rule therefore needs a plan's whole candidate set rather than one snapshot.

### Changed

- Resolving a **single** version needs no time reasoning and does none: one candidate is used as-is at any instant. Every filing in the corpus takes this path, so nothing there changes.
- Three situations are refused rather than guessed, each with a `:reason` in `ex-data`: `:no-version-in-effect` (nothing had taken effect yet), `:ambiguous-version` (two candidates share the latest effective instant), and `:version-without-effective-instant` (a candidate cannot be ordered, since `urpx:effectiveDateTime` is `sh:minCount 0`).
- `urpx:expectedEndDate` and `urpx:expectedEndDateTime` are deliberately not read. An expected end anticipates a successor rather than terminating anything, so a version past its expected end with no successor is still in force; treating it as an expiration would silently drop a plan that outlived its own forecast.
- Candidates are deduplicated **by value**, not by `@id`. Two CPAU E-1 filings publish the same version `@id` at the same effective instant while differing in `urpx:hasModifierOptions`; collapsing on `@id` would pick one silently, which is the defect being removed. As two candidates they tie and are refused.

### Added

- **An `sce-d-2025-11-15` fixture**, the prior version of a plan already vendored, so the suite holds two real versions of one plan that price differently. Every other fixture is a single version, which is the condition that hid this.

## [0.16.0] — 2026-09-21

One fix, and it changes what `resolve-prices` returns for **every** base plan in the corpus.

### Fixed

- **Recurring charges were dropped on the base-plan path and kept on the modifier path.** `pd-active-at-tou?` answered two unrelated questions, returning false for any `PriceDefinition` carrying its own `urpx:CalculationMethod`. Because the modifier path applies that predicate only when there is a TOU period to gate against, the recurring exclusion was skipped along with it, so the same shape was dropped from a base plan and kept in a modifier. The two questions are now separate and recurring charges surface on both paths.

  The asymmetry produced incoherent output on filed documents. Composing SCE D with SCE CARE, the CARE discounts **on** the Base Services Charge surfaced while the Base Services Charge itself did not: the result carried credits against a charge that was not in it. Every vendored base plan lost at least one real charge this way (minimum charges, power access, service charges, base charges), and CPAU E-14, whose eighteen PriceDefinitions are all recurring, resolved to nothing at all.

### Changed

- **Resolved tiers for recurring charges carry `:urpx.resolved/interval-duration`.** A recurring charge is priced per interval, and its ledger is typically measured by a meter or fixture count with no `urpx:measurementKind` and no unit, so the number alone is uninterpretable. Flattening a period-scoped quantity into a per-unit rate is how a monthly charge becomes a wrong daily one.
- `marginal-unit-rate` excludes any tier carrying an interval duration. No recurring ledger in the corpus is energy-kind, so this changes no current value; it is there so a filing that does pair the two cannot quietly add a per-month figure to a per-kWh rate. **Every marginal rate in the corpus is unchanged**, pinned as exact values.
- The `:no-energy-ledger` refusal now distinguishes "nothing resolved at all" from "ledgers resolved, none of them measured in a kind this library can sum". CPAU E-14 moved from the first to the second.

**Upgrading:** every base plan gains at least one ledger. Consumers that enumerate `:urpx.resolved/ledgers` will see charges they did not see before, some of them per-interval rather than per-unit; check `:urpx.resolved/interval-duration` before treating a unit price as per-unit. Marginal rates are unaffected.

## [0.15.0] — 2026-09-21

### Changed

- **`marginal-unit-rate` refuses when a plan has no energy ledger at all**, where it returned `0M`. A street lighting schedule prices per lamp per month; its marginal unit is not a kWh, so the question has no answer and `0M` was a confident wrong one, indistinguishable from a plan whose energy ledgers genuinely sum to zero. Both states are now distinguishable: the second still answers zero, because that is the answer.
- Every refusal `marginal-unit-rate` makes now carries a `:reason` in its `ex-data` (`:no-energy-ledger`, `:unvaluable-marginal-tier`, `:unselected-alternatives`), so a consumer sweeping a corpus can classify rather than catch-all.

### Added

- **`has-energy-ledger?` and `measurement-kinds`**, so the refusal above is not a tax on every caller: ask before computing. `measurement-kinds` returns the kinds a plan does price in rather than a bare boolean, so a sweeping consumer can route instead of merely skip.
- **A CPAU E-14 fixture**, pinned at `28390dc`: the only filing in the corpus with no energy ledger at all, which isolates that case with no other variable. Its ledgers carry `urpx:referencesMetricInput` naming fixture-count MetricInputs with no measurement kind, so it exercises "ledgers wired, no energy kind among them" rather than "ledgers unwired".
- **Six SCE and SDG&E fixtures**, the corpus's first percentage discounts, pinned at urpx-rate-plans `384ffb1`. Both utilities are vendored because neither alone shows the variation, and a fixture set carrying only one would silently sanction whichever shape it held. SCE shares **one** subtotal between CARE and FERA, which differ only in rate; SDG&E carries **two** subtotals on one base plan with different member lists, because Schedule E-CARE excludes any applicable exemptions and FERA grants none. Unlike the PG&E `@graph` bundles each base plan is a separate filing from its modifiers, so the cross-document dereference is exercised the way the corpus files it. `urpx.care-fera-test` pins the filed values: SDG&E CARE `-0.1450505` and FERA `-0.0756612`, SCE CARE `-0.12139725` and FERA `-0.0672354`, each checked against the summary's own member arithmetic as well as against the resolver.
- A note in the README on **two properties that are optional in the shapes but load-bearing here**, so a conformant document can still lose content: `urpx:startDate` on a `urpx:Price` with nothing enclosing it to inherit a date from (dropped silently), and `urpx:referencesMetricInput` on a `urpx:Ledger` (tiers survive, but `marginal-unit-rate` comes back quietly short, or `0M`). Both were found by authoring real filings, not by validation.

### Documentation

- **The README's Getting started walkthrough is now executed as a test.** It advertises itself as running from a cold clone, and it silently stopped doing so the moment `marginal-unit-rate` began refusing: the walkthrough composes PG&E's vintage-PCIA modifier, so the first thing a new reader ran would have thrown. README blocks were only checked for symbol resolution, which cannot catch that. The Getting started section is self-contained, so it is now evaluated like the DSL tutorial.
- The walkthrough passes `:profile-alternatives` and explains why; the refusal is described in prose rather than shown as a throwing call.
- The reasons a Price can be unvaluable are now a table, and it was missing two: `:ambiguous-basis` and `:summary-no-active-members`.
- `marginal-unit-rate` and the `PriceSummary` schema docstrings stated the old behavior; both now say what they do.

## [0.14.0] — 2026-09-21

Composition. Five defects in how a modifier resolves against a base plan, four of them wrong answers rather than errors and one a crash, plus three semantics that URPX leaves open and that this library now states rather than implies. Found by auditing the compose path to answer a question from the filings repo, not by a failing test: every one is reachable by a SHACL-conformant document, and the 73-filing corpus gate caught none of them, because it validates documents and these are resolution defects.

**Upgrading:** `marginal-unit-rate` now throws on a composed plan whose modifier carries mutually-exclusive price alternatives, where it previously returned a number. PG&E filings are the case in practice. Pass `:profile-alternatives` to select, or read `:urpx.resolved/tiers` directly, which needs no selection.

### Fixed

- **`marginal-unit-rate` silently added an arbitrary price from a matrix modifier.** PG&E's vintage-PCIA ledger carries 18 prices selected by departure vintage: mutually-exclusive alternatives, exactly one of which applies to a customer. They were treated as a block-tier ladder and the first was taken, so composing the modifier turned a 0.41784 marginal rate into 0.44757. The 18 span 0.06391/kWh and the pick was document order. Resolution still surfaces all of them (the caller-picks contract is unchanged); the aggregate now refuses. Pass `:profile-alternatives` to choose, and only the chosen alternative surfaces.
- **A modifier carrying its own TOU schedule and its own seasons threw `ClassCastException` on a holiday.** The holiday predicate's *result* was passed where the predicate was expected.
- **A v0.5.1 modifier stating its version link only through `urpx:forRatePlanModifier` was dropped from composition without a word**, giving a result indistinguishable from passing no modifier at all. Un-nesting is the shape v0.5.1 introduced.
- **Modifier tiers were never sorted**, while base tiers were, so `marginal-unit-rate` (which documents itself as taking the lowest tier) got whatever came first in the document.
- **`urpx:PriceSummary` members were summed with no season, month or TOU gating.** A summary naming a summer price and a winter price returned the sum of both in July. Members are now gated by the same predicates that gate a tier, applied to each member's own `PriceDefinition`, so one summary per base plan can name every discountable line item and stay correct in every TOU period. When no member is in effect the result is `:summary-no-active-members` rather than a confident zero.

### Changed

- **Multiple `urpx:basedOnPrice` values are read disjunctively** as candidate bases, exactly one expected to be present in the documents passed to the call; 0.13.0 threw. This lets one discount Price serve a modifier applying to many base plans. Zero resolvable candidates is still an error and two present at once reports `:ambiguous-basis`. URPX fixes the cardinality and says nothing about multiplicity; the conjunctive reading is what `urpx:PriceSummary` already provides, so the disjunctive one is taken here and recorded rather than left implicit.
- `:profile-alternatives` is now honored on the modifier path. It was read on the base plan path and discarded on the modifier path, which is why matrix modifiers had no way to be selected.
- The README's description of how matrix modifiers carry their selection dimension was wrong, in two places: it is `urpx:PriceSetAlternatives` / `urpx:referencesProfileAlternative`, not `urpx:hasApplicabilityGroup` with a `urpx:ConditionExpression`.

  Only one of the two was corrected in the 0.14.0 artifact; the second, and the rest of the documentation catch-up, landed after the tag and ships in 0.15.0.

## [0.13.0] — 2026-09-21

One feature. A `urpx:Price` may state `urpx:multiplier` with `urpx:basedOnPrice` instead of a `urpx:unitPrice`, naming the price it is derived from. That is how a percentage discount such as CARE or FERA is filed. 0.12.0 returned `nil` for every such Price, and a nil unit price reads to a consumer as free, so a 32.5% discount arrived as a 100% one.

### Added

- `urpx:PriceSummary`, which aggregates line items through `urpx:includesPrice` (`sh:minCount 2` upstream: a summary of one price is not a summary), and `urpx:hasPriceSummary` on `PriceSet` and `PriceSetAlternative`, which is where a summary attaches (it is `rdfs:domain urpx:PriceSet`, so a subtotal lives beside the line items it names). A `price-summary` DSL builder comes with it; nothing else was needed, because `make-builder` derives kwargs, aliases and validation from the schema, and a test now pins that rather than assuming it.
- `resolve-prices` values derived prices: the multiplier times the referenced Price's unit price, or times the sum of a referenced summary's members. `urpx:summaryAmount` is read but not trusted, so an author-stated total that disagrees with the members stays visible rather than becoming authoritative.
- `:urpx.resolved/unvaluable` on any tier whose Price could not be valued, carrying a reason and a detail: `:unresolved-reference`, `:summary-member-unresolved`, `:basis-not-valuable`, or `:no-unit-price`. This is the point of the release. A Price this library cannot value is now named as such instead of surfacing as a nil that reads as free.
- `resolve-prices-with-modifiers` builds one `@id` index spanning the base plan and every modifier, so a discount Price filed in a program modifier can dereference a summary defined in the base plan filing. That is the shape these discounts take in practice, the discount belonging to the program and the line items to the plan. No cross-file lookup is invented: the index covers exactly the documents the caller passed in, and a modifier resolved on its own reports `:unresolved-reference`.

### Changed

- `marginal-unit-rate` throws when a marginal energy tier could not be valued, rather than summing around it. Skipping one reports the undiscounted price as though it were the real rate.
- `urpx:basedOnPrice` is accepted as **0..N**, as `PriceShape` declares (`sh:minCount 0`, no `sh:maxCount`); it was modeled as a single value. With more than one basis stated, resolution throws rather than picking, the same refusal the 0..N properties in 0.12.0 make.
- `scripts/shacl-drift.sh` recurses through `:and` as well as `:or` when deciding whether a schema entry is 0..N. `[:and [:vector X] [:fn ...]]` is how a cardinality refinement is written, and reading it as a single value reported drift against a schema that has none.

## [0.12.0] — 2026-09-20

Ten commits. URPX's production corpus finished migrating to v0.5.1 across all six of its per-utility waves during this window, so most of what follows is the library catching up with real migrated filings rather than with the specification on paper.

### Changed

Three changes tighten validation and can reject documents 0.11.0 accepted. All three were measured against the fully migrated 73-filing corpus first and reject nothing in it, but a consumer with other filings should read them.

- `urpx:changeReason` and `urpx:serviceRole` are now **required** on `RatePlanVersion` and `RatePlanModifierVersion`, matching `sh:minCount 1`, which has been their cardinality since URPX v0.2.1. All 84 version snapshots in the corpus carry both.
- Both version pointers now accept **0..N** as the shapes declare (`sh:minCount 0`, no `sh:maxCount`). With more than one nested snapshot and no explicit choice, `resolve-prices` **throws** rather than resolving against an arbitrary one. Which snapshot applies to an instant is a selection question the vocabulary does not settle, so the library refuses instead of guessing; pass `:version` in the opts to choose.
- Three further 0..N properties are accepted as one value or many — `MetricInput.hasMetricSpecification`, `PlanElements.hasHolidayCalendar`, `TimeBracket.appliesInSeason` — with the same refusal rather than an arbitrary pick.
- A bare `@id` reference to an `ApplicabilityRule` on a `PriceDefinition` is now dereferenced before month gating. Previously such a reference carried no `urpx:month`, read as permissive, and the gate silently stopped applying.

### Added

- `scripts/shacl-drift.sh` reports where the hand-written Malli schemas have fallen behind the shipped SHACL shapes. It is an auditor, not a generator, and emits nothing that is compiled or shipped. Findings rank by cost: `rejects-valid` and `arity` mean the library refuses documents the standard allows; `under-validates` and `missing-required` mean the reverse. Exit codes are tri-state so a run that compared nothing cannot read as success.
- [`doc/shacl-and-malli.md`](doc/shacl-and-malli.md) records why the schemas are hand-written rather than generated from the shapes, with the measurements behind that decision.
- A DSL builder for every entity-type schema. Eleven were missing, including all four coverage schemas added in 0.11.0. A test now pins the claim, which had quietly become false.

### Fixed

- **`Price.referencesPriceDefinition` was required** while `urpx-shacl:PriceShape` declares `sh:minCount 0`, so conformant documents omitting it were refused. `PriceSetAlternative` already modeled the same property optional, so the two schemas had been disagreeing with each other. Found by the drift detector on its first run, along with thirteen cardinality mismatches also fixed here.
- **Timezone inference was silently dead on v0.5.1 documents.** v0.5.1 moved the EIA utility id out of `urpx:eiaId` and into the `Organization` identity chain (`hasIdentity → hasIdentifier → {urpx:scheme urpx:eiaScheme, skos:notation}`). `infer-zone` read only the old key, found nothing, and fell back to matching a display name without saying so. It now reads either location.
- A test helper could not reach a `RatePlanModifier` embedded in a `urpx:URPXPackage`, because it filtered `load-graph` rather than using the descending loader.

### Documentation

- The DSL tutorial is now **executed** as a test: every Clojure block runs, in order, in one namespace. It had silently become a throw when two properties were made required.
- README code blocks are checked for symbol resolution, which catches a rename that leaves prose behind.

### Not included

Whether to keep accepting URPX v0.2.x or tighten to v0.5.1 exactly remains open. Four wave-gated requirements and one retired enum member stay loose because tightening them would reject every v0.2.x document. That is a contract decision rather than a conformance one, and it is deferred to the next consumer upgrade.

## [0.11.0] — 2026-09-20

Retargets the library to **URPX v0.5.1** (`urpx-org/urpx` at `95eb8e22`) while continuing to accept v0.2.x in full. The production corpus migrates utility by utility and carries both vocabularies at once, so dual acceptance is the design rather than transitional debt.

### Added

- `urpx:URPXPackage` as a sibling schema to `urpx:URPXDocument`, its v0.3.0 rename, including the `urpx:packages` containment pointer. Loading, coercion, DSL builders and emitters cover both.
- The v0.5.1 coverage machinery: `CoverageClaim`, `Evaluation`, `ModelExclusion`, `PriceDefinitionReference`, plus `forRatePlan` / `forRatePlanModifier` and `hasCoverageClaim` on the version snapshots.
- The publication split: `SourcePublication` (dual-accepting the v0.2.x `urpx:TariffPublication` `@type`) and `TariffFiling`.
- Typed identity: `RatePlanIdentifier`, `RatePlanModifierIdentifier`, `OrganizationIdentifier`.
- Enum membership enforcement on six reframe vocabularies, transcribed from the shipped `sh:in` lists.
- Back-pointer resolution: `urpx.index/versions-by-plan`, and `load-rate-plan-version` / `load-rate-plan-modifier-version`.
- `urpx.validate`, a single-file CLI checker (`clojure -M:validate <file.jsonld>`) with tri-state exits. It descends wrappers, so it cannot report success over a document whose inner entity was never reached.

### Changed

- `hasApplicabilityRule`'s reference arm is closed to `@id` alone. The open form accepted a malformed inline rule as though it were a reference.
- `@context` is accepted in all three JSON-LD encodings — object, IRI string, or an array of either. Only the object form was accepted before, so the standard's own v0.5.1 reference documents did not validate.
- `hasRatePlanVersion`, `hasRatePlanModifierVersion` and `effectiveDateTime` are optional, matching `sh:minCount 0`. `effectiveDateTime` was never required upstream at any version.
- `DataSourceReference` accepts the v0.5.1 flattening (`leafStart`, `leafEnd`, `tariffStatementType` inline) as well as the v0.2.x nested `TariffBookReference`.
- The version hop resolves through `forRatePlan` back-pointers as well as the nested pointer.

### Removed

- The unused `com.apicatalog/titanium-json-ld` dependency, which was also non-functional as declared (no `jakarta.json` implementation was supplied).

[0.40.0]: https://github.com/grid-coordination/clj-urpx/releases/tag/v0.40.0
[0.39.0]: https://github.com/grid-coordination/clj-urpx/releases/tag/v0.39.0
[0.38.0]: https://github.com/grid-coordination/clj-urpx/releases/tag/v0.38.0
[0.37.0]: https://github.com/grid-coordination/clj-urpx/releases/tag/v0.37.0
[0.36.0]: https://github.com/grid-coordination/clj-urpx/releases/tag/v0.36.0
[0.35.0]: https://github.com/grid-coordination/clj-urpx/releases/tag/v0.35.0
[0.34.0]: https://github.com/grid-coordination/clj-urpx/releases/tag/v0.34.0
[0.33.0]: https://github.com/grid-coordination/clj-urpx/releases/tag/v0.33.0
[0.32.0]: https://github.com/grid-coordination/clj-urpx/releases/tag/v0.32.0
[0.31.0]: https://github.com/grid-coordination/clj-urpx/releases/tag/v0.31.0
[0.30.0]: https://github.com/grid-coordination/clj-urpx/releases/tag/v0.30.0
[0.29.0]: https://github.com/grid-coordination/clj-urpx/releases/tag/v0.29.0
[0.28.0]: https://github.com/grid-coordination/clj-urpx/releases/tag/v0.28.0
[0.27.0]: https://github.com/grid-coordination/clj-urpx/releases/tag/v0.27.0
[0.26.0]: https://github.com/grid-coordination/clj-urpx/releases/tag/v0.26.0
[0.25.0]: https://github.com/grid-coordination/clj-urpx/releases/tag/v0.25.0
[0.24.0]: https://github.com/grid-coordination/clj-urpx/releases/tag/v0.24.0
[0.23.0]: https://github.com/grid-coordination/clj-urpx/releases/tag/v0.23.0
[0.22.0]: https://github.com/grid-coordination/clj-urpx/releases/tag/v0.22.0
[0.21.0]: https://github.com/grid-coordination/clj-urpx/releases/tag/v0.21.0
[0.20.0]: https://github.com/grid-coordination/clj-urpx/releases/tag/v0.20.0
[0.19.0]: https://github.com/grid-coordination/clj-urpx/releases/tag/v0.19.0
[0.18.0]: https://github.com/grid-coordination/clj-urpx/releases/tag/v0.18.0
[0.17.0]: https://github.com/grid-coordination/clj-urpx/releases/tag/v0.17.0
[0.16.0]: https://github.com/grid-coordination/clj-urpx/releases/tag/v0.16.0
[0.15.0]: https://github.com/grid-coordination/clj-urpx/releases/tag/v0.15.0
[0.14.0]: https://github.com/grid-coordination/clj-urpx/releases/tag/v0.14.0
[0.13.0]: https://github.com/grid-coordination/clj-urpx/releases/tag/v0.13.0
[0.12.0]: https://github.com/grid-coordination/clj-urpx/releases/tag/v0.12.0
[0.11.0]: https://github.com/grid-coordination/clj-urpx/releases/tag/v0.11.0

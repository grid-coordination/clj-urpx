<!--
SPDX-FileCopyrightText: 2026 Clark Communications Corporation
SPDX-License-Identifier: MIT
-->

# Changelog

Notable changes to [`energy.grid-coordination/clj-urpx`](https://clojars.org/energy.grid-coordination/clj-urpx). The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); the project is pre-1.0, so a minor bump is the strongest compatibility signal available and is used for breaking changes.

Entries begin at 0.11.0. The ten releases before it carry no summaries in their release commits, and reconstructing them from the commit log would be guesswork rather than history; see `git log` and the `v0.1.0`–`v0.10.0` tags for what actually landed.

## [Unreleased]

### Added

- **`:urpx.resolved/condition-gated`** on a tier whose Price, or whose PriceDefinition, carries a `urpx:ConditionExpression` this library does not evaluate. Such a price fires **unconditionally** at resolution, which is a silent assumption that its predicate holds, and it is epistemically the same position as an event-driven price. Event-driven prices have been marked since 0.19.0 and excluded from the marginal since; condition-gated ones were neither. Composing SCE TOU-D-PRIME with its CPP modifier returns **-0.13897** at summer peak and **-0.46336** off-peak, a customer being paid to consume, because a credit gated on "is a CPP event called today" and a credit gated on a grandfathered-customer attribute both apply at every instant.

  Each gate carries `:urpx.resolved/gate-id`, `:urpx.resolved/gate-name` and `:urpx.resolved/gate-on` (`:price` or `:price-definition`). Rules stated as a bare `{@id}` reference are dereferenced, for the same reason the month gate dereferences them.

  **Marking only. Nothing is excluded from any aggregate and no resolved number changes.** Measured over the 76-file production corpus at four instants, 101,992 of 105,177 resolved tiers are condition-gated, so excluding wholesale would delete the corpus rather than correct it. The population that actually reaches a per-kWh marginal is far smaller: 1,352 of 3,717 tiers on import energy ledgers, the other 100,640 being export prices that `urpx:flowDirection urpx:reverse` already keeps out of the sum.

  A rule carrying only `urpx:month` is **not** reported, because that constraint is evaluated. Conditions inside a `urpx:ProfileAlternative` (required on every alternative by the shapes) and inside a `urpx:EligibilityRule` (which answers who may take the tariff) are not reported either, since neither hangs off a Price.

### Changed

- **`marginal-unit-rate` refuses with `:condition-gated-tier` rather than summing a price whose condition it cannot evaluate.** Closes the half of [#5](https://github.com/grid-coordination/clj-urpx/issues/5) that [#9](https://github.com/grid-coordination/clj-urpx/issues/9) tracked. Pass `:assume-conditions-hold? true` for the every-condition-true upper bound, as `:include-dynamic-pricing? true` gives the during-event one.

  **Two different defects reach this guard, and the larger one is a modeling problem.** SCE's CCA-CRS files twenty PCIA vintages as twenty condition-gated prices, one per vintage year, of which exactly one applies to any customer. Summing all twenty took SCE TOU-D-PRIME from `0.60067` to **`0.95664`**, where the true range is `0.59423` to `0.62691`. That is the identical defect this function has refused since 0.20.0 when a filing states it as `urpx:PriceSetAlternatives`, which is how PG&E files the same concept: the answer used to depend on which layer a filer expressed exclusivity in, and now it does not. The other defect is a genuine unevaluable predicate: SCE's CPP credits, gated on "is a CPP event called today" and on a grandfathered-customer attribute, applied at every instant and made the composed marginal **negative**, `-0.13897` at summer peak, which says a customer is paid to consume.

  Scoped to prices that would actually be summed. 100,640 of the corpus's 101,992 gated tiers are export prices in net-billing matrices where the gate is the lookup key; `urpx:flowDirection urpx:reverse` has kept those out of the marginal since 0.21.0 and they do not refuse it either.

**Upgrading:** a composition that returned a number for SCE CCA-CRS, SCE DA-CRS, SCE TOU-D-PRIME-CPP or PG&E E-RSMART now refuses. Every one of those numbers was wrong. Where the gated set is mutually exclusive, the filing wants `urpx:ProfileAlternatives` and the refusal becomes a selection you can make; where the gate is a genuine predicate, `:assume-conditions-hold? true` restores the previous value explicitly.

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

  Without this, moving a set of event-driven prices onto the selection layer would convert a correct rate into a refusal no caller could clear. CPAU's E-HRA cells are the utility's own reserve level and generation band, reset by council vote; a consumer has no source for either, so the selection the refusal demands could never be supplied. Vintage-PCIA is the case that keeps the refusal, being an ordinary per-kWh price and an attribute of the customer the caller does know. Raised by the urpx-tariffs session before authoring against the new slot.

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

  Reported by the urpx-tariffs session, who declined to work around it on the data side: putting the discriminator back on the Price would have made SCE D the only filing in 73 doing so, which is the anomaly that caused the 3.09× SDG&E defect in the first place.

## [0.22.0] — 2026-09-21

Silent losses. A sweep looked for every way a SHACL-conformant document can lose content or produce a wrong number with no diagnostic; it returned seven confirmed findings, rejected nineteen candidates as correct gating or existing loud refusals, and caught four overclaims among its own results. Two more came from the urpx-tariffs session. **Resolved prices change on filings that carry any of these shapes.**

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

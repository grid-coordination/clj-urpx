<!--
SPDX-FileCopyrightText: 2026 Clark Communications Corporation
SPDX-License-Identifier: MIT
-->

# What a snapshot claims about itself

Every `urpx:RatePlanVersion` and `urpx:RatePlanModifierVersion` carries a `urpx:CoverageClaim`: a verdict on what can be answered from that snapshot. It is the standard's own answer to "what am I holding?", and `urpx.coverage` reads it.

```clojure
(require '[urpx.coverage :as cov])

(cov/of-version version)          ; => a report map, keys under :urpx.coverage/
(cov/of-version version doc)      ; doc follows a bare @id reference and may state the zone
(cov/of-version version doc zone) ; zone reads dateTimes written without an offset
(cov/claim version)               ; => the raw CoverageClaim node, or ::absent

(cov/of-model model)            ; => every part's report, plus counts
(cov/descriptions model-report) ; => the Evaluation prose, in part order

(cov/coverage-interval report zone)        ; => {:tick/beginning .. :tick/end ..}
(cov/verdict-applies? report zone interval) ; => does the verdict cover that window?
```

The verdict is **not this library's judgement**. Per the ontology, claims "are produced by an implementation or publishing workflow", so a claim says what the *publisher* asserts. Nothing here re-derives one or second-guesses one.

`urpx.coverage` requires `urpx.index` and nothing else of the library: not `urpx.price`, not `urpx.scenario-cost-model`. Nothing in `src/` requires it.

## Three things a caller should know

**Absent is not `unknown`.** A version with no claim reports `:stated? false` and `:completeness nil`, never `"urpx:unknown"`. `urpx:unknown` is itself a verdict, meaning no evaluation has run, so a document asserting it has said something a document omitting the claim has not.

**`selfContained true` is not "complete", and can be vacuous.** It is derived as "false iff any `PriceDefinitionReference` is `retrievedElsewhere`", so on a snapshot with no such reference the existential ranges over an empty set. It means only "nothing here is fetched from elsewhere".

**A verdict states no window.** The `CoverageClaim` note says producers *should* attach a `TemporalScope` with `scopeKind urpx:coverage` saying what the verdict covers, and no shape requires one. So a consumer publishing a horizon cannot assume a verdict covers it. `:scope-stated?` and `:coverage-window` are separate for the same reason: `scopeKind` is the only required field on a scope, so one can name its role and bound nothing.

## Conformance, as distinct from validity

Every version snapshot must carry `urpx:hasCoverageClaim`; the schema requires it. `urpx.validate` also reports one rule the standard states that the schemas do not enforce, `:self-contained-disagrees`: `urpx:selfContained` may appear on the claim and the version and "MUST agree". `urpx.coverage` reads such a document without refusing, because the same note settles which wins; the violated MUST is the validator's business. It prints as `NONCONFORMANT` and counts toward the exit code.

## What it refuses

| `:reason` | When |
|---|---|
| `:urpx.coverage/unknown-completeness-result` | the claim names a member outside the six. Reporting `nil` would read as "not complete", a guess about a verdict the document did state |
| `:urpx.coverage/claim-ref-unresolved` | the claim is a bare `@id` that cannot be followed. A reference going nowhere is not the same as no claim |
| `:urpx.coverage/self-contained-contradicts-references` | the claim asserts `selfContained true` while a `PriceDefinitionReference` is `retrievedElsewhere`, contradicting the property's own derivation with no stated precedence |
| `:urpx.coverage/ambiguous-coverage-scope` | more than one scope names `urpx:coverage`. A verdict covers one window and nothing says which is meant |
| `:urpx.coverage/coverage-scope-absent` | `coverage-interval` or `verdict-applies?` asked about a claim with no `urpx:coverage` scope, which no shape requires |
| `:urpx.coverage/coverage-scope-unbounded` | a coverage scope naming its role and no bounds, which `scopeKind` being the only required field permits. It asserts a role, not a window |
| `:urpx.coverage/zone-required` | a coverage window was asked for with no `ZoneId`. URPX states the bounds without one and any default would be this library's invention |

Claim-level and version-level `selfContained` disagreeing does **not** refuse. It is reported with `:self-contained-disagrees? true` and the claim winning, because the ontology settles precedence ("when both are present they MUST agree, and the claim is authoritative"); it is a violated MUST, which is a validation concern rather than a reading one.

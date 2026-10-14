<!--
SPDX-FileCopyrightText: 2026 Clark Communications Corporation
SPDX-License-Identifier: MIT
-->

# Authoring URPX entities

For programmatic construction or test-fixture authoring, `urpx.dsl` provides idiomatic Clojure builders for every entity-type schema in `urpx.schema`. Output is a coerced (typed) entity, ready for `urpx.price/resolve-prices`, `urpx.emit/write-*`, or any other consumer.

```clojure
(require '[urpx.dsl :as dsl])

(dsl/dsl-scope
  (let [summer (dsl/season :as :ex/summer :season-name "Summer" :month [5 6 7 8 9 10])
        winter (dsl/season :as :ex/winter :season-name "Winter" :month [11 12 1 2 3 4])
        ledger-comm (dsl/ledger :as :ex/ledger-commodity
                                :name "Commodity"
                                :ledger-type {:jsonld/id "urpx:supplyLedger"})
        pd-cs (dsl/price-definition :as :ex/pd-commodity-summer
                                    :name "Commodity Summer"
                                    :applies-in-season :ex/summer
                                    :references-ledger :ex/ledger-commodity)]
    ;; ...assemble into a rate-plan via dsl/rate-plan, dsl/rate-plan-version, etc.
    ))
```

The DSL handles the boring parts so authoring code stays declarative:

- **`@type` is inferred** from the schema (no `:jsonld/type "urpx:Season"` boilerplate).
- **Auto-coercion** of raw Clojure inputs through the schema's `:decode/urpx-jsonld` decoders: a BigDecimal, integer, exact ratio or xsd:decimal string → `BigDecimal` (a double or float refuses, since it has already lost the written scale: write `0.1610M` or `"0.1610"`), ISO strings → `LocalDate` / `LocalDateTime` / `LocalTime`, or the zoned forms when a dateTime or time carries an offset, `--MM` xsd:gMonth → int.
- **Validation on construct** — every builder throws `ex-info` with the Malli explanation if the entity doesn't validate against its schema, so shape drift surfaces at the call site.
- **Two kwarg forms** — canonical `:urpx/seasonName` or auto-derived kebab-case `:season-name` (both work, can be mixed in one call).
- **Unknown keys refuse.** A key that is neither in the schema, a kebab-case form of one, nor `:jsonld/*` throws, naming the key and the entity type. `(dsl/make-builder schema/X {:passthrough #{…}})` builds a constructor that admits further keys.

## Keyword refs

Inside a `dsl-scope`, `:as k` gives an entity the `@id` `(dsl/curie k)`, and a qualified-keyword value anywhere else in the scope is a Ref to the same `@id`:

```clojure
(dsl/dsl-scope
  (dsl/season :as :ex/summer :season-name "Summer" :month [5 6 7 8 9 10])
  (dsl/season :as :ex/winter :season-name "Winter" :month [11 12 1 2 3 4])
  (dsl/ledger :as :ex/commodity :name "Commodity" :ledger-type :urpx/supplyLedger)
  (dsl/price-definition :as :ex/pd-summer
                        :name "Summer commodity"
                        :applies-in-season :ex/summer    ; → {:jsonld/id "ex:summer"}
                        :references-ledger :ex/commodity))
```

- A reference may come before or after its declaration.
- When the scope exits, every keyword referenced but not declared in it throws, in one `ex-info` naming each keyword and the builders that used it.
- Then every keyword whose entity is not of the type its slot takes throws, under `:mistyped`. `:applies-in-season :ex/commodity`, where `:ex/commodity` is a Ledger, refuses because `urpx:appliesInSeason` takes a `urpx:Season`. A subclass passes: a `UtilityOperator` where an `Organization` is taken. A builder refuses an inline node of the wrong type the same way, without a scope.
- An `@id` declared twice in a scope throws, whether by `:as` or by `:jsonld/id`, unless both builds yield the same node.
- `(dsl/dsl-scope {:id-fn f} …)` names `@id`s with `f`.

### One node in two places

A node embedded in two places, such as one utility offering both a base version and a modifier, may be built in each. Both builds yield the same node, so the scope accepts the repeated `@id`; two different nodes under one `@id` still throw.

```clojure
(let [utility #(dsl/organization :as :ex/utility :legal-name "Example Utility"
                                 :organization-type :urpx/utilityOrganization)]
  (dsl/dsl-scope [(utility) (utility)]))
```

### Nodes built elsewhere

A node from another document is named by declaring it with its `@type` under `:outside`. Its keyword then reads as a reference to `(id-fn k)` and is judged against the slot's range like a local one; a builder in the scope may not declare it.

```clojure
(dsl/dsl-scope
  {:outside {:pge/ledger-energy "urpx:Ledger"}}
  (dsl/price-definition :as :ex/pd-adder
                        :name "Medical baseline adder"
                        :references-ledger :pge/ledger-energy))   ; → {:jsonld/id "pge:ledger-energy"}
```

A raw Ref map, `{:jsonld/id "…"}`, still works anywhere and is not judged.

### Typed literals

`(dsl/literal "2009" "xsd:gYear")` writes a JSON-LD value object, `{:jsonld/value "2009" :jsonld/type "xsd:gYear"}`, as `urpx:hasPossibleValues` lists need:

```clojure
(dsl/rdf-list [(dsl/literal "2009" "xsd:gYear") (dsl/literal "2010" "xsd:gYear")])
```

### Writing what you built

`urpx.emit/write-entity` writes any built entity; `urpx.emit/write-graph` writes several as one `@graph` document. Keys come out in one order whatever order the maps were built in, and `:json` passes options to `clojure.data.json`, so generated files are stable byte for byte. See [Writing URPX JSON-LD](writing-json-ld.md).

## Building from arbitrary schemas

`make-builder` derives a constructor from any Malli schema with a `:jsonld/type [:= "urpx:X"]` entry — useful for ad-hoc construction or scripts that don't import every named builder:

```clojure
(let [build-tou-period (dsl/make-builder schema/TOUPeriod)]
  (build-tou-period :urpx/name "Summer Peak"
                    :urpx/touPeriodNumber 1
                    :urpx/hasTimeBracket []))
```

The named builders (`season`, `metric-input`, `price-definition`, `rate-plan`, `urpx-package`, `coverage-claim`, …) are each just `(def name (make-builder schema/X))`, one for every entity-type schema in `urpx.schema` with no exceptions — a test pins that.

For a longer worked walkthrough that mirrors selected examples from the upstream [URPX Tutorial](https://github.com/urpx-org/urpx/blob/main/documentation/pages/urpx-tutorial.md), see [`doc/dsl-tutorial.md`](dsl-tutorial.md) — each example shows the canonical URPX JSON-LD shape and the equivalent DSL form, with deep-links back to the upstream source section.

## Typed definitions

`urpx.defs/defurpx` defines a var whose value is validated against a schema chosen by a kind keyword. A corpus written as Clojure source uses it for the tables a filing states, and its generator finds every definition of a kind with `defs-of-kind`.

```clojure
(require '[urpx.defs :as defs :refer [defurpx]])

(defmethod defs/kind-schema :ex/rates [_]
  [:map-of :keyword decimal?])

(defurpx :ex/rates rtc-rates
  "Cents per kWh."
  {:peak 16.10M :off-peak 8.05M})

(defs/defs-of-kind :ex/rates ['my.corpus])   ; => [#'my.corpus/rtc-rates]
```

- **Validation at macroexpansion.** The value is checked before the `def`, so an invalid value or an unknown kind interns nothing and a failed re-evaluation keeps the previous value. The message names the kind, the var, its file and line, and each failing path:

  ```
  Invalid :ex/rates my.corpus/rtc-rates (my/corpus.clj:7)
    at [:peak]: 16.9 should be a decimal
  ```

  The `ex-data` carries `:kind`, `:where` and the humanized `:errors`.
- **Closed schemas.** Every map in a kind's schema is closed unless it says `{:closed false}`, so a misspelled key refuses. A map reached through a `:ref` keeps its own `:closed` property.
- **Kinds.** `kind-schema` is a multimethod; its `:default` refuses. The kind is a keyword literal. The `urpx.kind` namespace is reserved for kinds clj-urpx defines; qualify your own kinds in your namespace.
- **No registry.** The kind is in the var's metadata as `:urpx/kind`, with the docstring, `:file` and `:line`. `defs-of-kind` reads the vars interned in the namespaces you pass, so an unmapped var is not returned.

### clj-kondo

The jar carries a clj-kondo config whose hook lints `defurpx` as a `def`. Import it into a project that depends on clj-urpx:

```bash
clj-kondo --lint "$(clojure -Spath)" --copy-configs --skip-lint
```

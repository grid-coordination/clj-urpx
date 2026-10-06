(ns urpx.dsl-test
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [urpx.coerce :as coerce]
            [urpx.core :as core]
            [urpx.dsl :as dsl]
            [urpx.emit :as emit]
            [urpx.price :as price]
            [urpx.schema :as schema])
  (:import [java.time ZonedDateTime ZoneId]))

;; ---------------------------------------------------------------------------
;; entity-type introspection
;; ---------------------------------------------------------------------------

(deftest entity-type-extracts-jsonld-type-literal
  (testing "entity-type pulls the @type literal from the schema's
            :jsonld/type [:= ...] entry"
    (is (= "urpx:Season"       (dsl/entity-type schema/Season)))
    (is (= "urpx:MetricInput"  (dsl/entity-type schema/MetricInput)))
    (is (= "urpx:Ledger"       (dsl/entity-type schema/Ledger)))
    (is (= "urpx:Holiday"      (dsl/entity-type schema/Holiday)))
    (is (= "urpx:RatePlan"     (dsl/entity-type schema/RatePlan)))
    (is (= "urpx:URPXDocument" (dsl/entity-type schema/URPXDocument)))))

(deftest entity-type-returns-nil-on-non-entity-schema
  (testing "schemas without a :jsonld/type entry return nil (Decimal is a
            primitive :fn schema, not an entity)"
    (is (nil? (dsl/entity-type schema/Decimal)))))

;; ---------------------------------------------------------------------------
;; make-builder mechanics
;; ---------------------------------------------------------------------------

(deftest make-builder-rejects-non-entity-schema
  (testing "make-builder throws ex-info if the schema can't supply a @type"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"must have a :jsonld/type"
                          (dsl/make-builder schema/Decimal)))))

(deftest make-builder-on-the-fly
  (testing "the generator works for any entity schema, not just the ones
            with named builders bound at ns load time"
    (let [build-tou-period (dsl/make-builder schema/TOUPeriod)
          period (build-tou-period :urpx/name "Summer Peak"
                                   :urpx/touPeriodNumber 1
                                   :urpx/hasTimeBracket [])]
      (is (= "urpx:TOUPeriod" (:jsonld/type period)))
      (is (= "Summer Peak" (:urpx/name period)))
      (is (= 1 (:urpx/touPeriodNumber period))))))

;; ---------------------------------------------------------------------------
;; Season builder via make-builder
;; ---------------------------------------------------------------------------

(deftest season-minimal
  (testing "name-only — produces a valid Season with @type set automatically"
    (let [s (dsl/season :urpx/seasonName "Summer")]
      (is (= "urpx:Season" (:jsonld/type s)))
      (is (= "Summer"      (:urpx/seasonName s)))
      (is (nil? (coerce/explain schema/Season s))))))

(deftest season-with-int-months
  (testing "ints pass through the MonthNumber decoder unchanged"
    (let [s (dsl/season :urpx/seasonName "Summer" :urpx/month [5 6 7 8 9 10])]
      (is (= [5 6 7 8 9 10] (:urpx/month s)))
      (is (every? int? (:urpx/month s))))))

(deftest season-auto-coerces-gMonth-strings
  (testing "'--MM' xsd:gMonth strings auto-coerce via the
            schema's :decode/urpx-jsonld decoder — no per-property handling
            needed in the builder"
    (let [s (dsl/season :urpx/seasonName "Summer"
                        :urpx/month ["--05" "--06" "--07" "--08" "--09" "--10"])]
      (is (= [5 6 7 8 9 10] (:urpx/month s))))))

(deftest season-mixed-input-types
  (testing "auto-coercion is per-leaf — int + string can be mixed"
    (let [s (dsl/season :urpx/seasonName "Summer"
                        :urpx/month [5 "--06" 7 "--08"])]
      (is (= [5 6 7 8] (:urpx/month s))))))

(deftest season-trailing-map-kwargs
  (testing "Clojure 1.12 trailing-map-as-kwargs unification — both call
            shapes produce identical output"
    (let [a (dsl/season :urpx/seasonName "Summer"
                        :urpx/month [5 6]
                        :urpx/yearlyRecurrence true)
          b (dsl/season {:urpx/seasonName "Summer"
                         :urpx/month [5 6]
                         :urpx/yearlyRecurrence true})]
      (is (= a b)))))

(deftest season-handles-optional-id
  (let [s (dsl/season :jsonld/id "x:summer"
                      :urpx/seasonName "Summer"
                      :urpx/month [5 6 7 8 9 10])]
    (is (= "x:summer" (:jsonld/id s)))))

(deftest season-yearly-recurrence-false-is-preserved
  (testing "explicit false isn't stripped — only nil values are dropped"
    (let [s (dsl/season :urpx/seasonName "Special"
                        :urpx/yearlyRecurrence false)]
      (is (false? (:urpx/yearlyRecurrence s))))))

(deftest season-nil-kwargs-are-stripped
  (testing "nil kwarg values drop out, so optional fields can be
            supplied conditionally without producing present-but-nil entries"
    (let [s (dsl/season :urpx/seasonName "Summer"
                        :urpx/month nil
                        :jsonld/id nil)]
      (is (not (contains? s :urpx/month)))
      (is (not (contains? s :jsonld/id)))
      (is (= "Summer" (:urpx/seasonName s))))))

(deftest season-equals-hand-built
  (testing "generator output equals a hand-built coerced
            Season — same shape, same values, same types"
    (let [hand-built {:jsonld/type "urpx:Season"
                      :jsonld/id "x:summer"
                      :urpx/seasonName "Summer"
                      :urpx/month [5 6 7 8 9 10]
                      :urpx/yearlyRecurrence true}
          dsl-built  (dsl/season :jsonld/id "x:summer"
                                 :urpx/seasonName "Summer"
                                 :urpx/month [5 6 7 8 9 10]
                                 :urpx/yearlyRecurrence true)]
      (is (= hand-built dsl-built)))))

(deftest season-validates-against-schema
  (testing "every legal call yields a Season that validates"
    (doseq [s [(dsl/season :urpx/seasonName "Summer")
               (dsl/season :urpx/seasonName "Summer" :urpx/month [6 7 8])
               (dsl/season :urpx/seasonName "Winter" :urpx/month ["--12" "--01" "--02"])
               (dsl/season :urpx/seasonName "Special" :urpx/yearlyRecurrence false)]]
      (is (nil? (coerce/explain schema/Season s))))))

(deftest season-bad-month-throws-with-explanation
  (testing "out-of-range month surfaces as ex-info with the Malli explanation
            attached — validates the error UX for the broader DSL"
    (let [thrown (try (dsl/season :urpx/seasonName "Summer" :urpx/month [13])
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
      (is (some? thrown))
      (is (re-find #"invalid urpx:Season" (.getMessage ^Throwable thrown)))
      (is (some? (:explanation (ex-data thrown))))
      (is (= [13] (get-in (ex-data thrown) [:input :urpx/month]))))))

;; ---------------------------------------------------------------------------
;; Other named builders — demonstrate the generator works
;; across entity-shape variety
;; ---------------------------------------------------------------------------

(deftest metric-input-with-nested-spec
  (testing "nested-entity coercion — urpx:hasMetricSpecification
            takes a raw MetricSpecification map; coerce/coerce traverses it"
    (let [mi (dsl/metric-input
              :urpx/name "Energy consumed (kWh)"
              :urpx/metricInputType {:jsonld/id "urpx:profileMetricInput"}
              :urpx/hasMetricSpecification
              {:jsonld/type "urpx:MetricSpecification"
               :urpx/measurementKind {:jsonld/id "urpx:energy"}
               :urpx/unitOfMeasure   {:jsonld/id "urpx:kWh"}})]
      (is (= "urpx:MetricInput" (:jsonld/type mi)))
      (is (= "urpx:MetricSpecification"
             (get-in mi [:urpx/hasMetricSpecification :jsonld/type])))
      (is (nil? (coerce/explain schema/MetricInput mi))))))

(deftest ledger-with-refs
  (testing "Ref-bearing fields work — urpx:referencesMetricInput
            and urpx:ledgerType are passed as :jsonld/id maps"
    (let [l (dsl/ledger :urpx/name "Commodity"
                        :urpx/ledgerType {:jsonld/id "urpx:supplyLedger"}
                        :urpx/referencesMetricInput [{:jsonld/id "x:mi-energy"}])]
      (is (= "urpx:Ledger" (:jsonld/type l)))
      (is (nil? (coerce/explain schema/Ledger l))))))

(deftest holiday-with-date-coercion
  (testing "typed-leaf coercion — Date strings auto-coerce into
            LocalDate via the schema's :decode/urpx-jsonld decoder"
    (let [h (dsl/holiday :urpx/holidayName "Christmas"
                         :urpx/holidayDate "2026-12-25")]
      (is (= "urpx:Holiday" (:jsonld/type h)))
      (is (instance? java.time.LocalDate (:urpx/holidayDate h)))
      (is (= (java.time.LocalDate/of 2026 12 25) (:urpx/holidayDate h))))))

;; ---------------------------------------------------------------------------
;; Auto-coercion of raw Clojure numbers
;; ---------------------------------------------------------------------------

(deftest dsl-builders-accept-clojure-number-literals-for-decimal-fields
  (testing "a builder for an entity with a Decimal field accepts
            an idiomatic Clojure number — the user no longer has to write
            (BigDecimal. \"0.15075\") at every call site"
    (let [build-price (dsl/make-builder schema/Price)
          p (build-price :urpx/unitPrice 0.15075
                         :urpx/referencesPriceDefinition {:jsonld/id "x:pd"})]
      (is (instance? BigDecimal (:urpx/unitPrice p)))
      (is (= (BigDecimal. "0.15075") (:urpx/unitPrice p))))))

(deftest dsl-builders-pass-through-pre-typed-decimals
  (testing "callers who already have a BigDecimal in hand can pass
            it through; no double-conversion churn"
    (let [build-price (dsl/make-builder schema/Price)
          original (BigDecimal. "0.15075")
          p (build-price :urpx/unitPrice original
                         :urpx/referencesPriceDefinition {:jsonld/id "x:pd"})]
      (is (= original (:urpx/unitPrice p)))
      (is (= 5 (.scale ^BigDecimal (:urpx/unitPrice p)))
          "scale preserved (0.15075 has scale 5)"))))

(deftest dsl-builders-accept-mixed-raw-and-typed-inputs
  (testing "every leaf decoder routes its own input shape; a single
            builder call can mix raw and pre-typed values across fields"
    (let [build-price (dsl/make-builder schema/Price)
          p (build-price :urpx/unitPrice 0.15075                                  ; double
                         :urpx/startDate "2026-03-01"                             ; ISO string
                         :urpx/endDate (java.time.LocalDate/of 2026 12 31)        ; pre-typed
                         :urpx/referencesPriceDefinition {:jsonld/id "x:pd"})]
      (is (= (BigDecimal. "0.15075") (:urpx/unitPrice p)))
      (is (= (java.time.LocalDate/of 2026 3 1)  (:urpx/startDate p)))
      (is (= (java.time.LocalDate/of 2026 12 31) (:urpx/endDate p)))
      (is (nil? (coerce/explain schema/Price p))))))

;; ---------------------------------------------------------------------------
;; Local-symbol refs — dsl-scope + qualified-keyword resolution
;; ---------------------------------------------------------------------------

(deftest local-id-arg-sets-jsonld-id-inside-scope
  (testing "leading qualified-keyword arg becomes the entity's
            local @id when called inside a dsl-scope"
    (let [s (dsl/dsl-scope (dsl/season ::summer :urpx/seasonName "Summer"))]
      (is (= "summer" (:jsonld/id s))
          "default id is the keyword's local name"))))

(deftest local-id-arg-ignored-outside-scope
  (testing "outside dsl-scope, builders only accept canonical
            kwargs — a leading qualified keyword would be (mis-)parsed as
            a kwarg key, which fails because :foo expects a value"
    (is (thrown? Exception
                 (dsl/season ::summer :urpx/seasonName "Summer")))))

(deftest qualified-keyword-value-resolves-to-ref
  (testing "a kwarg value that's a qualified keyword resolves to
            a {:jsonld/id ...} Ref via the registry"
    (let [pd (dsl/dsl-scope
              (dsl/season ::summer :urpx/seasonName "Summer")
              ((dsl/make-builder schema/PriceDefinition)
               :urpx/name "Commodity Summer"
               :urpx/appliesInSeason ::summer
               :urpx/referencesLedger {:jsonld/id "x:commodity"}))]
      (is (= [{:jsonld/id "summer"}] (:urpx/appliesInSeason pd))
          "::summer resolved to a Ref vector (single-or-vec→vec normalizes)"))))

(deftest local-refs-resolve-inside-vectors
  (testing "vector-typed kwargs walk element-wise — each
            qualified keyword resolves to its own Ref"
    (let [pd (dsl/dsl-scope
              (dsl/season ::summer :urpx/seasonName "Summer")
              (dsl/season ::winter :urpx/seasonName "Winter")
              ((dsl/make-builder schema/PriceDefinition)
               :urpx/name "All-season"
               :urpx/appliesInSeason [::summer ::winter]
               :urpx/referencesLedger {:jsonld/id "x:commodity"}))]
      (is (= [{:jsonld/id "summer"} {:jsonld/id "winter"}]
             (:urpx/appliesInSeason pd))))))

(deftest unknown-local-ref-throws
  (testing "a reference to an undeclared keyword fails loud"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"DSL local ref not declared"
         (dsl/dsl-scope
          ((dsl/make-builder schema/PriceDefinition)
           :urpx/name "broken"
           :urpx/appliesInSeason ::nowhere
           :urpx/referencesLedger {:jsonld/id "x:l"}))))))

(deftest duplicate-local-id-throws
  (testing "declaring the same local sym twice fails loud"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"DSL local ref already declared"
         (dsl/dsl-scope
          (dsl/season ::summer :urpx/seasonName "Summer")
          (dsl/season ::summer :urpx/seasonName "Hot Summer"))))))

(deftest each-dsl-scope-has-its-own-registry
  (testing "scopes don't leak — the same local sym can be declared
            in two distinct scopes without collision"
    (let [a (dsl/dsl-scope (dsl/season ::summer :urpx/seasonName "Scope-A Summer"))
          b (dsl/dsl-scope (dsl/season ::summer :urpx/seasonName "Scope-B Summer"))]
      (is (= "summer" (:jsonld/id a)))
      (is (= "summer" (:jsonld/id b)))
      (is (= "Scope-A Summer" (:urpx/seasonName a)))
      (is (= "Scope-B Summer" (:urpx/seasonName b))))))

(deftest worked-local-sym-example
  (testing "seasons and a ledger declared with local syms, and a PD that
            references them by sym"
    (let [pd (dsl/dsl-scope
              (dsl/season ::summer :urpx/seasonName "Summer"
                          :urpx/month [5 6 7 8 9 10])
              (dsl/season ::winter :urpx/seasonName "Winter"
                          :urpx/month [11 12 1 2 3 4])
              (dsl/ledger ::commodity :urpx/name "Commodity"
                          :urpx/ledgerType {:jsonld/id "urpx:supplyLedger"})
              (dsl/price-definition
               ::pd-summer
               :urpx/name "Commodity Summer"
               :urpx/appliesInSeason ::summer
               :urpx/referencesLedger ::commodity))]
      (is (= "pd-summer" (:jsonld/id pd)))
      (is (= [{:jsonld/id "summer"}] (:urpx/appliesInSeason pd)))
      (is (= {:jsonld/id "commodity"} (:urpx/referencesLedger pd))))))

(deftest vocabulary-keyword-values-are-urpx-refs
  (let [build #(dsl/time-bracket :start-time "16:00:00"
                                 :end-time "21:00:00"
                                 :day-type :urpx/customDays
                                 :days-of-week [:urpx/monday {:jsonld/id "urpx:friday"}])
        want  {:urpx/dayType {:jsonld/id "urpx:customDays"}
               :urpx/daysOfWeek [{:jsonld/id "urpx:monday"} {:jsonld/id "urpx:friday"}]}]
    (testing "outside a scope"
      (is (= want (select-keys (build) (keys want)))))
    (testing "inside a scope"
      (is (= want (select-keys (dsl/dsl-scope (build)) (keys want)))))
    (testing "alongside a local ref"
      (let [tb (dsl/dsl-scope
                (dsl/season ::summer :season-name "Summer")
                (dsl/time-bracket :start-time "16:00:00"
                                  :end-time "21:00:00"
                                  :day-type :urpx/allDays
                                  :applies-in-season [::summer]))]
        (is (= {:jsonld/id "urpx:allDays"} (:urpx/dayType tb)))
        (is (= [{:jsonld/id "summer"}] (:urpx/appliesInSeason tb)))))))

;; ---------------------------------------------------------------------------
;; Kebab-case kwarg aliases
;; ---------------------------------------------------------------------------

(deftest kebab-case-aliases-resolve-to-canonical
  (testing "every builder accepts kebab-cased :urpx/* short names
            in addition to the canonical form"
    (let [s (dsl/season :season-name "Summer"
                        :month [5 6 7 8 9 10]
                        :yearly-recurrence true)]
      (is (= "Summer" (:urpx/seasonName s)))
      (is (= [5 6 7 8 9 10] (:urpx/month s)))
      (is (true? (:urpx/yearlyRecurrence s)))
      (is (nil? (coerce/explain schema/Season s))))))

(deftest canonical-and-alias-keys-can-mix
  (testing "canonical and short-form keys can be mixed in one call"
    (let [s (dsl/season :urpx/seasonName "Summer" :month [5 6 7 8 9 10])]
      (is (= "Summer" (:urpx/seasonName s)))
      (is (= [5 6 7 8 9 10] (:urpx/month s))))))

(deftest acronym-prefixes-kebab-correctly
  (testing "schema names with acronym prefixes (URPXDocument,
            TOUSchedule) kebab-case correctly — boundary between an all-caps
            run and the next CamelCase word inserts a hyphen"
    (is (some? (var-get #'urpx.dsl/urpx-document)))
    (is (some? (var-get #'urpx.dsl/urpx-package)))
    (is (some? (var-get #'urpx.dsl/tou-schedule)))
    (is (some? (var-get #'urpx.dsl/tou-period)))))

(defn- entity-schemas
  "{name → schema} for every public urpx.schema var naming an entity schema."
  []
  (into (sorted-map)
        (keep (fn [[sym v]]
                (let [val (deref v)]
                  (when (and (or (vector? val) (m/schema? val))
                             (try (dsl/entity-type val)
                                  (catch Exception _ nil)))
                    [(str sym) val]))))
        (ns-publics 'urpx.schema)))

(deftest every-builder-has-short-keys
  (let [alias-map (var-get #'urpx.dsl/alias-map)
        empty-ones (keep (fn [[n schema]]
                           (when (empty? (alias-map schema)) n))
                         (entity-schemas))]
    (is (empty? empty-ones)
        (str "builders with no kebab-case short keys: " (pr-str (vec empty-ones))))))

(deftest condition-expression-accepts-short-keys
  (let [ce (dsl/condition-expression
            :root-node {:jsonld/type "urpx:ComparisonNode"
                        :urpx/operator {:jsonld/id "urpx:equalTo"}
                        :urpx/leftOperand {:jsonld/type "urpx:CustomOperandNode"
                                           :urpx/customConditionProperty "x"}
                        :urpx/rightOperand {:jsonld/type "urpx:CustomOperandNode"
                                            :urpx/stringValue "y"}})]
    (is (= "urpx:ComparisonNode" (get-in ce [:urpx/rootNode :jsonld/type])))))

;; ---------------------------------------------------------------------------
;; Sample of new named builders — spot-check coverage across the
;; ~35 entity types
;; ---------------------------------------------------------------------------

(deftest builder-coverage-spot-check
  (testing "sample of new builders defined via defbuilders all work
            and produce valid coerced entities"
    (let [tb (dsl/time-bracket :urpx/startTime "16:00:00"
                               :urpx/endTime "21:00:00"
                               :urpx/dayType {:jsonld/id "urpx:weekdayDays"})
          cm (dsl/calculation-method :urpx/intervalDuration "P1M")
          org (dsl/organization
               :urpx/legalName "Pacific Gas & Electric"
               :urpx/organizationType {:jsonld/id "urpx:investorOwnedUtility"})]
      (is (= "urpx:TimeBracket" (:jsonld/type tb)))
      (is (nil? (coerce/explain schema/TimeBracket tb)))
      (is (= "urpx:CalculationMethod" (:jsonld/type cm)))
      (is (instance? java.time.Period (:urpx/intervalDuration cm)))
      (is (= "urpx:Organization" (:jsonld/type org)))))
  (testing "rate plan + version + URPXDocument builders all produce the
            expected @types"
    (let [rpv (dsl/rate-plan-version :urpx/forRatePlan {:jsonld/id "ex:parent"}
                                     :urpx/hasCoverageClaim
                                     {:jsonld/type "urpx:CoverageClaim"
                                      :urpx/completenessResult {:jsonld/id "urpx:complete"}}
                                     :urpx/changeReason {:jsonld/id "urpx:rateCase"}
    :urpx/forRatePlan {:jsonld/id "ex:parent"}
    :urpx/hasCoverageClaim {:jsonld/type "urpx:CoverageClaim" :urpx/completenessResult {:jsonld/id "urpx:complete"}}
                                     :urpx/serviceRole {:jsonld/id "urpx:bundledServiceRole"}
                                     :urpx/effectiveDateTime "2026-01-01T00:00:00"
                                     :urpx/hasPlanElements
                                     {:jsonld/type "urpx:PlanElements"
                                      :urpx/hasMetricInput
                                      [{:jsonld/type "urpx:MetricInput"
                                        :urpx/name "kWh"
                                        :urpx/metricInputType
                                        {:jsonld/id "urpx:measuredMetricInput"}}]
                                      :urpx/hasBillingModel
                                      {:jsonld/type "urpx:BillingModel"
                                       :urpx/hasPriceDefinition []}}
                                     :urpx/hasPrices {:jsonld/type "urpx:Prices"})
          rp (dsl/rate-plan :urpx/timezoneIdentifier "America/Los_Angeles"
                            :urpx/ratePlanUniqueIdentifier "P1"
                            :urpx/name "Plan One"
                            :urpx/initialEffectiveDate "2026-01-01"
                            :urpx/hasRatePlanVersion rpv)
          doc (dsl/urpx-document
               :urpx/targetURPXVersion "https://example.com/urpx-v0.2.0"
               :urpx/hasRatePlan rp)]
      (is (= "urpx:RatePlanVersion" (:jsonld/type rpv)))
      (is (= "urpx:RatePlan"        (:jsonld/type rp)))
      (is (= "urpx:URPXDocument"    (:jsonld/type doc)))
      (is (vector? (:urpx/hasRatePlan doc))
          "single-or-vec→vec on URPXDocument's hasRatePlan still applies")
      (let [pkg (dsl/urpx-package
                 :urpx/targetURPXVersion "https://example.com/urpx-v0.5.1"
                 :urpx/packages rp)]
        (is (= "urpx:URPXPackage" (:jsonld/type pkg)))
        (is (vector? (:urpx/packages pkg))
            "single-or-vec→vec applies to URPXPackage's packages pointer")))))

;; ---------------------------------------------------------------------------
;; End-to-end CPAU-E-2 via the DSL
;; ---------------------------------------------------------------------------
;; Strongest available demonstration of the full DSL: build a non-TOU
;; two-season rate plan idiomatically, then exercise it three ways —
;; schema validation, in-memory price resolution, and JSON-LD round-trip
;; through urpx.emit. Mirrors the hand-built `non-tou-seasonal-fixture`
;; from urpx.price-test so the resolution targets are pinned
;; to the published CPAU E-2 Summer / Winter totals.

(def la-zone (ZoneId/of "America/Los_Angeles"))

(defn- zdt
  ([y mo d h] (zdt y mo d h 0))
  ([y mo d h mi] (ZonedDateTime/of y mo d h mi 0 0 la-zone)))

(defn- cpau-e-2-via-dsl
  "Author a CPAU-E-2-shaped rate plan via the DSL — local-symbol refs,
   short-form kwargs, raw inputs that auto-coerce."
  []
  (dsl/dsl-scope
   (let [summer    (dsl/season ::summer
                               :season-name "Summer"
                               :month [5 6 7 8 9 10])
         winter    (dsl/season ::winter
                               :season-name "Winter"
                               :month [11 12 1 2 3 4])
         mi-energy (dsl/metric-input ::mi-energy
                                     :name "kWh"
                                     :metric-input-type {:jsonld/id "urpx:profileMetricInput"}
                                     :has-metric-specification
                                     (dsl/metric-specification
                                      :measurement-kind {:jsonld/id "urpx:energy"}
                                      :unit-of-measure  {:jsonld/id "urpx:kWh"}))
         l-comm    (dsl/ledger ::ledger-commodity
                               :name "Commodity"
                               :ledger-type {:jsonld/id "urpx:supplyLedger"}
                               :references-metric-input [::mi-energy])
         l-dist    (dsl/ledger ::ledger-distribution
                               :name "Distribution"
                               :ledger-type {:jsonld/id "urpx:distributionLedger"}
                               :references-metric-input [::mi-energy])
         l-pb      (dsl/ledger ::ledger-public-benefits
                               :name "Public Benefits"
                               :ledger-type {:jsonld/id "urpx:bundledLedger"}
                               :references-metric-input [::mi-energy])
         pd-cs     (dsl/price-definition ::pd-commodity-summer
                                         :name "Commodity Summer"
                                         :applies-in-season ::summer
                                         :references-ledger ::ledger-commodity)
         pd-cw     (dsl/price-definition ::pd-commodity-winter
                                         :name "Commodity Winter"
                                         :applies-in-season ::winter
                                         :references-ledger ::ledger-commodity)
         pd-ds     (dsl/price-definition ::pd-distribution-summer
                                         :name "Distribution Summer"
                                         :applies-in-season ::summer
                                         :references-ledger ::ledger-distribution)
         pd-dw     (dsl/price-definition ::pd-distribution-winter
                                         :name "Distribution Winter"
                                         :applies-in-season ::winter
                                         :references-ledger ::ledger-distribution)
         pd-pbs    (dsl/price-definition ::pd-public-benefits-summer
                                         :name "Public Benefits Summer"
                                         :applies-in-season ::summer
                                         :references-ledger ::ledger-public-benefits)
         pd-pbw    (dsl/price-definition ::pd-public-benefits-winter
                                         :name "Public Benefits Winter"
                                         :applies-in-season ::winter
                                         :references-ledger ::ledger-public-benefits)
         price-of  (fn [pd-ref unit]
                     (dsl/price :references-price-definition pd-ref
                                :unit-price unit
                                :start-date "2025-07-01"))]
     (dsl/rate-plan
      :timezone-identifier "America/Los_Angeles"
      :rate-plan-unique-identifier "TEST-CPAU-E-2"
      :name "Non-TOU two-season test plan"
      :initial-effective-date "2025-07-01"
      :has-rate-plan-version
      (dsl/rate-plan-version
       :for-rate-plan {:jsonld/id "ex:parent"}
       :has-coverage-claim {:jsonld/type "urpx:CoverageClaim"
                            :urpx/completenessResult {:jsonld/id "urpx:complete"}}
       :change-reason {:jsonld/id "urpx:rateCase"}
       :service-role {:jsonld/id "urpx:bundledServiceRole"}
       :effective-date-time "2025-07-01T00:00:00"
       :price-currency "USD"
       :has-plan-elements
       (dsl/plan-elements
        :has-seasons      [summer winter]
        :has-metric-input [mi-energy]
        :has-billing-model
        (dsl/billing-model
         :has-ledger            [l-comm l-dist l-pb]
         :has-price-definition  [pd-cs pd-cw pd-ds pd-dw pd-pbs pd-pbw]))
       :has-prices
       (dsl/prices
        :has-price-set
        [(dsl/price-set
          :has-price [(price-of ::pd-commodity-summer        0.15075)
                      (price-of ::pd-commodity-winter        0.09334)
                      (price-of ::pd-distribution-summer     0.10806)
                      (price-of ::pd-distribution-winter     0.07352)
                      (price-of ::pd-public-benefits-summer  0.00604)
                      (price-of ::pd-public-benefits-winter  0.00604)])]))))))

(deftest cpau-e-2-via-dsl-validates
  (testing "a CPAU-E-2-shaped plan authored via the DSL passes
            full RatePlan schema validation"
    (let [plan (cpau-e-2-via-dsl)]
      (is (nil? (coerce/explain schema/RatePlan plan))))))

(deftest cpau-e-2-via-dsl-resolves-summer
  (testing "the DSL plan resolves at a Summer probe instant to
            the published $0.26485/kWh CPAU E-2 Summer total"
    (let [plan (cpau-e-2-via-dsl)
          r    (price/resolve-prices plan (zdt 2026 7 15 17))]
      (is (= (BigDecimal. "0.26485") (price/marginal-unit-rate r))
          "Summer marginal = 0.15075 + 0.10806 + 0.00604"))))

(deftest cpau-e-2-via-dsl-resolves-winter
  (testing "the DSL plan resolves at a Winter probe instant to
            the published $0.17290/kWh CPAU E-2 Winter total"
    (let [plan (cpau-e-2-via-dsl)
          r    (price/resolve-prices plan (zdt 2026 1 15 12))]
      (is (= (BigDecimal. "0.17290") (price/marginal-unit-rate r))
          "Winter marginal = 0.09334 + 0.07352 + 0.00604"))))

(deftest cpau-e-2-via-dsl-round-trips
  (testing "DSL → emit → re-parse → re-coerce yields a structurally
            equal plan. End-to-end validates the DSL composes with the
            write-side without information loss. `{:context nil}` suppresses
            the default @context so the re-coerced shape matches
            the DSL-built original (which doesn't carry one)."
    (let [plan (cpau-e-2-via-dsl)
          re-coerced (-> (emit/write-rate-plan plan {:context nil})
                         java.io.StringReader.
                         core/parse-rate-plan
                         coerce/coerce-rate-plan)]
      (is (= plan re-coerced)))))

;; ---------------------------------------------------------------------------
;; Builder coverage is a claim the ns docstring makes; pin it
;; ---------------------------------------------------------------------------

(deftest every-entity-schema-has-a-builder
  (testing "urpx.dsl claims a named builder for every entity-type schema. That
            claim was false for eleven schemas before this test existed, four of
            them added the same day, so it is pinned rather than left in prose.

            The check follows the naming convention the ns documents: a schema
            named X gets a builder named (camel->kebab X). There are no
            exceptions: an earlier comment claimed SourcePublication could not
            have one because its @type is an :enum, and that was asserted
            without testing. entity-type returns the first enum member, which is
            the right default for a builder, so it builds the v0.5.1 spelling
            while the schema still validates the v0.2.x one on parse."
    (let [kebab    (var-get #'urpx.dsl/camel->kebab)
          entities (into (sorted-set)
                         (keep (fn [[sym v]]
                                 (let [val (deref v)]
                                   (when (or (vector? val) (m/schema? val))
                                     (when (try (dsl/entity-type val)
                                                (catch Exception _ nil))
                                       (str sym))))))
                         (ns-publics 'urpx.schema))
          missing  (remove #(ns-resolve 'urpx.dsl (symbol (kebab %))) entities)]
      (is (< 50 (count entities)) "sanity: urpx.schema should define many entities")
      (is (empty? missing)
          (str "entity schemas with no DSL builder: " (pr-str (vec missing))))
      (testing "the dual-accepting schema builds its v0.5.1 spelling, and the
                schema still validates the v0.2.x one"
        (is (= "urpx:SourcePublication" (:jsonld/type (dsl/source-publication :urpx/name "X"))))
        (is (m/validate schema/SourcePublication
                        {:jsonld/type "urpx:TariffPublication" :urpx/name "X"}))))))

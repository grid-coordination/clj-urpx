(ns urpx.schema
  "Malli schemas for URPX v0.2.0 entities, describing the coerced shape.

  Two-layer model (mirroring the clj-oa3 pattern):
    - urpx.core      — raw parse: JSON-LD with namespaced keys, all string values.
    - urpx.schema    — coerced shape: typed Clojure values (BigDecimal, LocalDate,
                       LocalTime, LocalDateTime, Duration, integer month numbers).

  Schemas carry `:decode/urpx-jsonld` decoders that turn the raw parser output
  (strings) into the typed shape described here. The transformer is built and
  applied in `urpx.coerce`. Reference resolution keeps :jsonld/id references
  intact; consumers dereference via an entity index.

  Schemas are bare def vars referenced by symbol — there is no central registry
  except inside ConditionExpression, where the recursive Comparison/Boolean
  operand tree uses a local Malli registry."
  (:import [java.math BigDecimal]
           [java.time Duration LocalDate LocalDateTime LocalTime Period]))

;; ---------------------------------------------------------------------------
;; Decoder helpers (consumed by `:decode/urpx-jsonld` properties below)
;; ---------------------------------------------------------------------------

(defn- ->decimal  [v] (if (string? v) (BigDecimal. ^String v) v))
(defn- ->date     [v] (if (string? v) (LocalDate/parse v) v))
(defn- ->datetime [v] (if (string? v) (LocalDateTime/parse v) v))
(defn- ->time     [v] (if (string? v) (LocalTime/parse v) v))
(defn- ->duration
  "Parse an ISO 8601 duration string. Time-based durations (containing 'T',
   e.g. PT1H30M) become java.time.Duration; date-based durations (e.g. P1M, P1Y)
   become java.time.Period."
  [v]
  (cond
    (not (string? v)) v
    (.contains ^String v "T") (Duration/parse v)
    :else (Period/parse v)))

(defn- ->g-month
  "xsd:gMonth strings look like '--06'. Strip the leading '--' and parse to int."
  [v]
  (cond
    (int? v) v
    (and (string? v) (.startsWith ^String v "--")) (Long/parseLong (subs v 2))
    (string? v) (Long/parseLong v)
    :else v))

(defn- ->long [v] (if (string? v) (Long/parseLong v) v))

(defn- single-or-vec->vec
  "URPX allows certain has-* fields to hold a single map or a vector of maps;
   normalize to a vector so downstream consumers see one shape."
  [v]
  (cond
    (nil? v)    v
    (vector? v) v
    (map? v)    [v]
    :else (vec v)))

;; ---------------------------------------------------------------------------
;; Primitive predicates
;; ---------------------------------------------------------------------------

(def Decimal
  [:fn {:error/message "should be a BigDecimal"
        :decode/urpx-jsonld ->decimal}
   #(instance? BigDecimal %)])

(def Date
  [:fn {:error/message "should be a LocalDate"
        :decode/urpx-jsonld ->date}
   #(instance? LocalDate %)])

(def DateTime
  [:fn {:error/message "should be a LocalDateTime"
        :decode/urpx-jsonld ->datetime}
   #(instance? LocalDateTime %)])

(def Time
  [:fn {:error/message "should be a LocalTime"
        :decode/urpx-jsonld ->time}
   #(instance? LocalTime %)])

(def IsoDuration
  [:fn {:error/message "should be a Duration or Period"
        :decode/urpx-jsonld ->duration}
   #(or (instance? Duration %) (instance? Period %))])

(def MonthNumber
  [:and {:decode/urpx-jsonld ->g-month}
   :int [:>= 1] [:<= 12]])

(def Int
  "An xsd:integer field — JSON parser may produce long already, but tolerate strings."
  [:and {:decode/urpx-jsonld ->long} :int])

;; ---------------------------------------------------------------------------
;; JSON-LD building blocks
;; ---------------------------------------------------------------------------

(def Ref
  "A JSON-LD @id reference: {:jsonld/id \"urpx:foo\"}.
   Resolved against the entity index built from the parsed document."
  [:map [:jsonld/id :string]])

(def NodeIdentity
  "Common JSON-LD identity fields present on most entities."
  [:map
   [:jsonld/type :string]
   [:jsonld/id {:optional true} :string]])

;; ---------------------------------------------------------------------------
;; Plan elements
;; ---------------------------------------------------------------------------

(def Season
  [:map
   [:jsonld/type [:= "urpx:Season"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/seasonName :string]
   [:urpx/month {:optional true} [:vector MonthNumber]]
   [:urpx/yearlyRecurrence {:optional true} :boolean]])

(def MetricSpecification
  [:map
   [:jsonld/type [:= "urpx:MetricSpecification"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/measurementKind {:optional true} Ref]
   [:urpx/unitOfMeasure   {:optional true} Ref]
   [:urpx/flowDirection   {:optional true} Ref]
   [:urpx/intervalDuration {:optional true} IsoDuration]])

(def MetricInput
  [:map
   [:jsonld/type [:= "urpx:MetricInput"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/name :string]
   [:urpx/metricInputType Ref]
   [:urpx/hasMetricSpecification {:optional true} MetricSpecification]
   ;; In v0.2.0 profileMetricProperty is a Ref to a vocabulary IRI
   ;; (e.g. urpx:meterCount, urpx:departureVintage).
   [:urpx/profileMetricProperty  {:optional true} Ref]])

(def TimeBracket
  [:map
   [:jsonld/type [:= "urpx:TimeBracket"]]
   [:urpx/startTime Time]
   [:urpx/endTime Time]
   ;; v0.2.0 SHACL: dayType is required (was optional in v0.1.x).
   [:urpx/dayType Ref]
   [:urpx/appliesInSeason     {:optional true} Ref]
   [:urpx/includeHolidays     {:optional true} :boolean]
   [:urpx/includeNonHolidays  {:optional true} :boolean]])

(def TOUPeriod
  [:map
   [:jsonld/type [:= "urpx:TOUPeriod"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/name :string]
   [:urpx/touPeriodNumber Int]
   [:urpx/priority   {:optional true} Int]
   [:urpx/startDate  {:optional true} Date]
   [:urpx/endDate    {:optional true} Date]
   [:urpx/hasTimeBracket [:vector TimeBracket]]])

(def TOUTier
  [:map
   [:jsonld/type [:= "urpx:TOUTier"]]
   [:urpx/tierSourceName {:optional true} :string]
   [:urpx/touTierStandardName {:optional true} Ref]
   [:urpx/touTierNumber Int]
   [:urpx/hasTOUPeriod [:vector TOUPeriod]]])

(def TOUSchedule
  [:map
   [:jsonld/type [:= "urpx:TOUSchedule"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/name {:optional true} :string]
   ;; v0.2.0 SHACL: referencesMetricInput is a required vector (min 1, no max).
   [:urpx/referencesMetricInput
    {:decode/urpx-jsonld single-or-vec->vec}
    [:vector Ref]]
   [:urpx/hasCalendar {:optional true} Ref]
   [:urpx/hasTOUTier [:vector TOUTier]]])

(def CalculationMethod
  [:map
   [:jsonld/type [:= "urpx:CalculationMethod"]]
   [:urpx/name {:optional true} :string]
   [:urpx/description {:optional true} :string]
   [:urpx/intervalDuration {:optional true} IsoDuration]])

(def Ledger
  "v0.2.0: Ledger no longer carries hasPriceDefinition. PriceDefinitions
   live at BillingModel.hasPriceDefinition with PriceDefinition.referencesLedger
   pointing back to one of the BillingModel.hasLedger entries."
  [:map
   [:jsonld/type [:= "urpx:Ledger"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/name :string]
   [:urpx/description {:optional true} :string]
   [:urpx/ledgerType {:optional true} Ref]
   [:urpx/referencesMetricInput
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector Ref]]
   [:urpx/settlementInterval {:optional true} IsoDuration]])

(def PriceDefinition
  "v0.2.0: PriceDefinition gains required referencesLedger plus many properties
   that used to live on Price (priceCurrency, primaryChargeType,
   referencesServiceTier, referencesTouPeriod, serviceTierNumber, …). One PD
   can govern many Prices via Price.referencesPriceDefinition."
  [:map
   [:jsonld/type [:= "urpx:PriceDefinition"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/name :string]
   [:urpx/description {:optional true} :string]
   [:urpx/chargeType    {:optional true} Ref]
   [:urpx/chargeCategory {:optional true} Ref]
   [:urpx/billPortion   {:optional true} Ref]
   [:urpx/commodity     {:optional true} Ref]
   [:urpx/priceCurrency {:optional true} :string]
   [:urpx/primaryChargeType {:optional true} Ref]
   ;; v0.2.0: required, single. PD points to its parent Ledger.
   [:urpx/referencesLedger Ref]
   [:urpx/referencesMetricInput
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector Ref]]
   [:urpx/referencesServiceTier {:optional true} Ref]
   [:urpx/referencesTouPeriod   {:optional true} Ref]
   [:urpx/serviceTierNumber {:optional true} Int]
   [:urpx/touPeriodNumber   {:optional true} Int]
   [:urpx/baseQuantity      {:optional true} Decimal]
   [:urpx/hasCalculationMethod {:optional true} CalculationMethod]
   [:urpx/hasRateCase
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector Ref]]
   ;; v0.2.0 SHACL: PriceDefinitionShape allows urpx:appliesInSeason → Season
   ;; (sh:minCount 0, sh:class urpx:Season). Used for non-TOU seasonal pricing
   ;; where the seasonal partition isn't carried by a TOUPeriod indirection
   ;; (e.g. CPAU E-2: flat $/kWh per Summer/Winter, no TOU within periods).
   [:urpx/appliesInSeason
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector Ref]]
   ;; ApplicabilityRule's full shape is defined further down in the Conditions
   ;; section; we inline a minimal version here so the gMonth decoder for
   ;; :urpx/month attaches without forward-ref gymnastics. The fuller shape
   ;; (with ConditionExpression) is referenced from Price.hasApplicabilityRule.
   [:urpx/hasApplicabilityRule
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector
     [:map
      [:jsonld/type {:optional true} :string]
      [:jsonld/id   {:optional true} :string]
      [:urpx/name   {:optional true} :string]
      [:urpx/month  {:optional true} [:vector MonthNumber]]
      [:urpx/hasConditionExpression {:optional true} :any]]]]])

(def ServiceTier
  "v0.2.0: lowerBound, lowerBoundOperator, upperBound, upperBoundOperator,
   serviceTierNumber are all required (were optional in v0.1.x). ServiceTier
   no longer carries description, referencesMetricInput, or
   hasCalculationMethod — those moved to the parent ServiceTiers container."
  [:map
   [:jsonld/type [:= "urpx:ServiceTier"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/serviceTierNumber Int]
   [:urpx/name {:optional true} :string]
   [:urpx/lowerBound Decimal]
   [:urpx/upperBound Decimal]
   [:urpx/lowerBoundOperator Ref]
   [:urpx/upperBoundOperator Ref]])

(def ServiceTiers
  "v0.2.0: NEW container that holds the block-tier vector. Carries the
   referencesMetricInput pointer that used to live on individual ServiceTiers
   in v0.1.x."
  [:map
   [:jsonld/type [:= "urpx:ServiceTiers"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/referencesMetricInput
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector Ref]]
   [:urpx/hasServiceTier [:vector ServiceTier]]])

(def BillingModel
  "v0.2.0: hasPriceDefinition is required (min 1) and lives at this level
   (not inside Ledger). hasServiceTiers is the new singular container — the
   v0.1.x BillingModel.hasServiceTier vector is gone.

   hasTOUSchedule is a vector (sh:maxCount unbounded per BillingModelShape):
   one TOUSchedule per MetricInput when the filing TOU-zones multiple
   metrics (e.g. CPAU E-4 TOU: separate energy + demand schedules).
   single-or-vec->vec keeps single-schedule fixtures (PG&E E-TOU-C/D/EV2)
   compatible without touching their JSON-LD."
  [:map
   [:jsonld/type [:= "urpx:BillingModel"]]
   [:urpx/prorationMethod {:optional true} :string]
   [:urpx/hasTOUSchedule
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector TOUSchedule]]
   [:urpx/hasServiceTiers {:optional true} ServiceTiers]
   [:urpx/hasLedger
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector Ledger]]
   [:urpx/hasPriceDefinition
    {:decode/urpx-jsonld single-or-vec->vec}
    [:vector PriceDefinition]]])

(def Holiday
  "v0.2.0: holidayName is required. Observance can be a literal date
   (urpx:holidayDate) or a free-form rule string (urpx:holidayRule);
   urpx:observanceRule (Ref) optionally shifts the date when it lands on a
   weekend."
  [:map
   [:jsonld/type [:= "urpx:Holiday"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/holidayName    :string]
   [:urpx/holidayDate    {:optional true} Date]
   [:urpx/holidayRule    {:optional true} :string]
   [:urpx/observanceRule {:optional true} Ref]])

(def HolidayCalendar
  [:map
   [:jsonld/type [:= "urpx:HolidayCalendar"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/hasHoliday
    {:decode/urpx-jsonld single-or-vec->vec}
    [:vector Holiday]]])

(def PlanElements
  [:map
   [:jsonld/type [:= "urpx:PlanElements"]]
   [:urpx/hasSeasons         {:optional true} [:vector Season]]
   [:urpx/hasMetricInput     [:vector MetricInput]]
   [:urpx/hasHolidayCalendar {:optional true} HolidayCalendar]
   [:urpx/hasBillingModel BillingModel]])

;; ---------------------------------------------------------------------------
;; Conditions (v0.2.0)
;;
;; Condition trees replace the v0.1.x Condition / ApplicabilityConstraint
;; vocabulary. Three operand-leaf shapes (Object, Datatype, Custom) plus
;; two recursive node shapes (Comparison, Boolean) compose into a single
;; ConditionExpression entry point.
;; ---------------------------------------------------------------------------

(def ObjectOperandNode
  "Ref-valued operand. sh:xone in SHACL constrains exactly one of
   conditionProperty, referencesValue, or referencesMetricInput to be present;
   we don't enforce that here — let pyshacl be the strict validator."
  [:map
   [:jsonld/type [:= "urpx:ObjectOperandNode"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/conditionProperty    {:optional true} Ref]
   [:urpx/referencesValue      {:optional true} Ref]
   [:urpx/referencesMetricInput {:optional true} Ref]])

(def DatatypeOperandNode
  "Typed-literal operand. Carries optional conditionProperty (a Ref) and one
   typed value field. SHACL's sh:xone enforces 'exactly one value field'; the
   coercer simply decodes whichever is present."
  [:map
   [:jsonld/type [:= "urpx:DatatypeOperandNode"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/conditionProperty    {:optional true} Ref]
   [:urpx/referencesMetricInput {:optional true} Ref]
   [:urpx/booleanValue  {:optional true} :boolean]
   [:urpx/dateValue     {:optional true} Date]
   [:urpx/dateTimeValue {:optional true} DateTime]
   [:urpx/decimalValue  {:optional true} Decimal]
   [:urpx/durationValue {:optional true} IsoDuration]
   [:urpx/gMonthValue   {:optional true} MonthNumber]
   [:urpx/integerValue  {:optional true} Int]
   [:urpx/stringValue   {:optional true} :string]
   [:urpx/timeValue     {:optional true} Time]])

(def CustomOperandNode
  "Like DatatypeOperandNode but with a free-form `customConditionProperty`
   string in lieu of a Ref-valued conditionProperty. Useful when the
   condition property isn't yet in the URPX vocabulary (e.g. PG&E's
   `departureVintage`)."
  [:map
   [:jsonld/type [:= "urpx:CustomOperandNode"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/customConditionProperty {:optional true} :string]
   [:urpx/referencesMetricInput   {:optional true} Ref]
   [:urpx/booleanValue  {:optional true} :boolean]
   [:urpx/dateValue     {:optional true} Date]
   [:urpx/dateTimeValue {:optional true} DateTime]
   [:urpx/decimalValue  {:optional true} Decimal]
   [:urpx/durationValue {:optional true} IsoDuration]
   [:urpx/gMonthValue   {:optional true} MonthNumber]
   [:urpx/integerValue  {:optional true} Int]
   [:urpx/stringValue   {:optional true} :string]
   [:urpx/timeValue     {:optional true} Time]])

(def ConditionExpression
  "Recursive condition tree. Uses a local Malli registry to express the
   ComparisonNode / BooleanOperationNode mutual recursion.

   Tree shape:
     ConditionExpression.rootNode → ComparisonNode | BooleanOperationNode
     ComparisonNode.{leftOperand,rightOperand} → any operand (recursively)
     BooleanOperationNode.operandNodes → vector of operands

   Where 'operand' is one of: ObjectOperandNode, DatatypeOperandNode,
   CustomOperandNode, ComparisonNode, BooleanOperationNode."
  [:schema
   {:registry
    {::Operand
     [:multi {:dispatch :jsonld/type}
      ["urpx:ObjectOperandNode"     ObjectOperandNode]
      ["urpx:DatatypeOperandNode"   DatatypeOperandNode]
      ["urpx:CustomOperandNode"     CustomOperandNode]
      ["urpx:ComparisonNode"        [:ref ::ComparisonNode]]
      ["urpx:BooleanOperationNode"  [:ref ::BooleanOperationNode]]]
     ::ComparisonNode
     [:map
      [:jsonld/type [:= "urpx:ComparisonNode"]]
      [:jsonld/id   {:optional true} :string]
      [:urpx/name   {:optional true} :string]
      [:urpx/description {:optional true} :string]
      [:urpx/logicExpression {:optional true} :string]
      [:urpx/operator Ref]
      [:urpx/leftOperand  [:ref ::Operand]]
      [:urpx/rightOperand [:ref ::Operand]]]
     ::BooleanOperationNode
     [:map
      [:jsonld/type [:= "urpx:BooleanOperationNode"]]
      [:jsonld/id   {:optional true} :string]
      [:urpx/logicExpression {:optional true} :string]
      [:urpx/operator Ref]
      [:urpx/operandNodes
       {:decode/urpx-jsonld single-or-vec->vec}
       [:vector [:ref ::Operand]]]]}}
   [:map
    [:jsonld/type [:= "urpx:ConditionExpression"]]
    [:jsonld/id   {:optional true} :string]
    [:urpx/rootNode
     [:multi {:dispatch :jsonld/type}
      ["urpx:ComparisonNode"       [:ref ::ComparisonNode]]
      ["urpx:BooleanOperationNode" [:ref ::BooleanOperationNode]]]]]])

(def ApplicabilityGroup
  "Container for cross-cutting applicability conditions; carried on
   Price/PriceSet to express things like income-tier eligibility.
   Extra geographic/customer fields exist in v0.2.0 SHACL but most fixtures
   only carry name + hasConditionExpression."
  [:map
   [:jsonld/type [:= "urpx:ApplicabilityGroup"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/name {:optional true} :string]
   [:urpx/applicabilityLogic    {:optional true} :string]
   [:urpx/hasConditionExpression {:optional true} ConditionExpression]])

(def ApplicabilityRule
  [:map
   [:jsonld/type [:= "urpx:ApplicabilityRule"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/name {:optional true} :string]
   [:urpx/month {:optional true} [:vector MonthNumber]]
   [:urpx/hasConditionExpression {:optional true} ConditionExpression]])

;; ---------------------------------------------------------------------------
;; Eligibility (v0.2.0 — closed shape, ConditionExpression-driven)
;; ---------------------------------------------------------------------------

(def CustomerProfile
  "v0.2.0: greatly expanded; we list the few properties we currently consume
   plus hasCustomerKind which is the canonical test-case dispatch in the
   fixtures."
  [:map
   [:jsonld/type [:= "urpx:CustomerProfile"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/hasCustomerKind    {:optional true} Ref]
   [:urpx/hasCustomerSubKind {:optional true} Ref]
   [:urpx/entityType         {:optional true} Ref]])

(def Scenario
  "v0.2.0: NEW. EligibilityRule.hasCustomerProfile moved here. SHACL's sh:or
   constraint requires either hasCustomerProfile xor hasServicePointProfile;
   we treat both as optional."
  [:map
   [:jsonld/type [:= "urpx:Scenario"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/hasCustomerProfile {:optional true} CustomerProfile]
   [:urpx/hasServicePointProfile {:optional true} Ref]])

(def EligibilityRule
  "v0.2.0: closed shape. hasCustomerProfile is gone (moved to Scenario).
   hasConditionExpression is the only required body field."
  [:map
   [:jsonld/type [:= "urpx:EligibilityRule"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/name {:optional true} :string]
   [:urpx/description {:optional true} :string]
   [:urpx/hasConditionExpression ConditionExpression]
   [:urpx/hasDataSourceReference
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector Ref]]])

;; ---------------------------------------------------------------------------
;; Prices
;; ---------------------------------------------------------------------------

(def Price
  "v0.2.0: many new optional value fields (adder, amount, basePrice,
   basedOnPrice, currentPrice, multiplier) and metadata
   (hasApplicabilityGroup, hasRateCase, …). referencesPriceDefinition is
   sh:maxCount 1 (still required in practice for any per-energy price)."
  [:map
   [:jsonld/type [:= "urpx:Price"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/name {:optional true} :string]
   [:urpx/description {:optional true} :string]
   [:urpx/unitPrice  {:optional true} Decimal]
   [:urpx/amount     {:optional true} Decimal]
   [:urpx/basePrice  {:optional true} Decimal]
   [:urpx/currentPrice {:optional true} Decimal]
   [:urpx/adder      {:optional true} Decimal]
   [:urpx/multiplier {:optional true} Decimal]
   [:urpx/baseQuantity {:optional true} Decimal]
   [:urpx/priceCurrency {:optional true} :string]
   [:urpx/referencesPriceDefinition Ref]
   [:urpx/referencesServiceTier {:optional true} Ref]
   [:urpx/referencesTouPeriod   {:optional true} Ref]
   [:urpx/serviceTierNumber {:optional true} Int]
   [:urpx/touPeriodNumber   {:optional true} Int]
   [:urpx/startDate     {:optional true} Date]
   [:urpx/startDateTime {:optional true} DateTime]
   [:urpx/endDate       {:optional true} Date]
   [:urpx/endDateTime   {:optional true} DateTime]
   [:urpx/appliesInSeason
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector Ref]]
   [:urpx/hasApplicabilityGroup {:optional true} ApplicabilityGroup]
   [:urpx/hasApplicabilityRule
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector ApplicabilityRule]]
   [:urpx/hasCalculationMethod {:optional true} CalculationMethod]
   [:urpx/hasRateCase
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector Ref]]])

(def PriceSet
  [:map
   [:jsonld/type [:= "urpx:PriceSet"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/name {:optional true} :string]
   [:urpx/description {:optional true} :string]
   [:urpx/startDate     {:optional true} Date]
   [:urpx/startDateTime {:optional true} DateTime]
   [:urpx/endDate       {:optional true} Date]
   [:urpx/endDateTime   {:optional true} DateTime]
   [:urpx/touPeriodNumber {:optional true} Int]
   [:urpx/serviceTierNumber {:optional true} Int]
   [:urpx/referencesPriceDefinition {:optional true} Ref]
   [:urpx/hasApplicabilityGroup {:optional true} ApplicabilityGroup]
   [:urpx/hasPrice [:vector Price]]])

(def Prices
  [:map
   [:jsonld/type [:= "urpx:Prices"]]
   [:urpx/hasPrice    {:optional true} [:vector Price]]
   [:urpx/hasPriceSet {:optional true} [:vector PriceSet]]])

;; ---------------------------------------------------------------------------
;; Top-level metadata
;; ---------------------------------------------------------------------------

(def Organization
  "v0.2.0: legalName is now optional; organizationType is required.
   urpx:eiaId is gone — utility identifiers now live under hasIdentity →
   urpx:OrganizationIdentity (not modeled here yet)."
  [:map
   [:jsonld/type [:= "urpx:Organization"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/legalName       {:optional true} :string]
   [:urpx/alternateName   {:optional true} :string]
   [:urpx/organizationType Ref]])

(def TariffBookReference
  [:map
   [:jsonld/type [:= "urpx:TariffBookReference"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/pageStart {:optional true} Int]
   [:urpx/pageEnd   {:optional true} Int]
   [:urpx/leafStart {:optional true} :string]
   [:urpx/leafEnd   {:optional true} :string]])

(def DataSourceReference
  "v0.2.0: many new optional fields. mediaType is now Ref-only (no longer
   string); we list the most commonly populated fields."
  [:map
   [:jsonld/type [:= "urpx:DataSourceReference"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/name {:optional true} :string]
   [:urpx/description {:optional true} :string]
   [:urpx/url       {:optional true} :string]
   [:urpx/accessURL {:optional true} :string]
   [:urpx/fileName  {:optional true} :string]
   [:urpx/mediaType {:optional true} Ref]
   [:urpx/dataSourceType  {:optional true} Ref]
   [:urpx/dateRetrieved   {:optional true} Date]
   [:urpx/dateReceived    {:optional true} Date]
   [:urpx/datePublished   {:optional true} Date]
   [:urpx/effectiveDate   {:optional true} Date]
   [:urpx/expirationDate  {:optional true} Date]
   [:urpx/pageStart {:optional true} Int]
   [:urpx/pageEnd   {:optional true} Int]
   [:urpx/hasTariffBookReference {:optional true} TariffBookReference]])

(def RateCase
  "v0.2.0: NEW. Identifies the regulatory proceeding that authorized a
   PriceDefinition / RatePlanVersion / Price / PriceSet."
  [:map
   [:jsonld/type [:= "urpx:RateCase"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/name {:optional true} :string]
   [:urpx/description {:optional true} :string]
   [:urpx/caseNumber :string]
   [:urpx/url        :string]
   [:urpx/priority   {:optional true} Int]])

;; ---------------------------------------------------------------------------
;; Plan and version
;; ---------------------------------------------------------------------------

(def RatePlanVersion
  "v0.2.0: changeReason, serviceRole, publishedBy are now required; gains
   customerChoice, isDefault, hasModifierOptions, hasRateCase, supersedes,
   plus a set of date/dateTime metadata fields. We list the fields we
   currently consume plus the headline metadata."
  [:map
   [:jsonld/type [:= "urpx:RatePlanVersion"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/version       {:optional true} :string]
   [:urpx/versionNumber {:optional true} :string]
   [:urpx/changeReason  {:optional true} Ref]
   [:urpx/changeDescription {:optional true} :string]
   [:urpx/description   {:optional true} :string]
   [:urpx/effectiveDateTime DateTime]
   [:urpx/effectiveDate     {:optional true} Date]
   [:urpx/expirationDate    {:optional true} Date]
   [:urpx/expirationDateTime {:optional true} DateTime]
   [:urpx/datePublished     {:optional true} Date]
   [:urpx/dateTimePublished {:optional true} DateTime]
   [:urpx/dateApproved      {:optional true} Date]
   [:urpx/dateTimeApproved  {:optional true} DateTime]
   [:urpx/priceCurrency     {:optional true} :string]
   [:urpx/commodity         {:optional true} Ref]
   [:urpx/serviceRole       {:optional true} Ref]
   [:urpx/customerChoice    {:optional true} Ref]
   [:urpx/isDefault         {:optional true} :boolean]
   [:urpx/minimumTenure     {:optional true} IsoDuration]
   [:urpx/url               {:optional true} :string]
   [:urpx/publishedBy {:optional true} Organization]
   [:urpx/supersedes  {:optional true} Ref]
   [:urpx/hasDataSourceReference
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector DataSourceReference]]
   [:urpx/hasEligibilityRule
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector EligibilityRule]]
   [:urpx/hasRateCase
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector Ref]]
   [:urpx/observesHoliday
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector Ref]]
   [:urpx/hasPlanElements PlanElements]
   [:urpx/hasPrices       Prices]])

(def RatePlan
  "v0.2.0: ratePlanUniqueIdentifier is required (replaces v0.1.x
   ratePlanIdentifier). RatePlan gains many optional date/metadata fields;
   we list the ones the tests / consumers care about."
  [:map
   [:jsonld/type [:= "urpx:RatePlan"]]
   [:jsonld/id {:optional true} :string]
   [:jsonld/context {:optional true} :map]
   [:urpx/ratePlanUniqueIdentifier :string]
   [:urpx/name :string]
   [:urpx/initialEffectiveDate Date]
   [:urpx/effectiveDate {:optional true} Date]
   [:urpx/expirationDate {:optional true} Date]
   [:urpx/timezoneIdentifier {:optional true} :string]
   [:urpx/priceCurrency {:optional true} :string]
   [:urpx/url           {:optional true} :string]
   [:urpx/version       {:optional true} :string]
   [:urpx/hasRatePlanVersion RatePlanVersion]])

;; ---------------------------------------------------------------------------
;; Modifier — augments / overrides parts of an existing RatePlan
;; ---------------------------------------------------------------------------

(def RatePlanModifierVersion
  "v0.2.0: changeReason, serviceRole, publishedBy, modifierVersionNumber are
   now required. modifierIdentifier is gone — it moved to RatePlanModifier as
   modifierUniqueIdentifier."
  [:map
   [:jsonld/type [:= "urpx:RatePlanModifierVersion"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/modifierVersionNumber :string]
   [:urpx/version    {:optional true} :string]
   [:urpx/changeReason  {:optional true} Ref]
   [:urpx/changeDescription {:optional true} :string]
   [:urpx/description {:optional true} :string]
   [:urpx/effectiveDateTime DateTime]
   [:urpx/effectiveDate {:optional true} Date]
   [:urpx/expirationDate {:optional true} Date]
   [:urpx/expirationDateTime {:optional true} DateTime]
   [:urpx/priceCurrency {:optional true} :string]
   [:urpx/commodity   {:optional true} Ref]
   [:urpx/serviceRole {:optional true} Ref]
   [:urpx/modifierType     {:optional true} Ref]
   [:urpx/modifierCategory {:optional true} Ref]
   [:urpx/publishedBy {:optional true} Organization]
   [:urpx/supersedes  {:optional true} Ref]
   [:urpx/hasDataSourceReference
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector DataSourceReference]]
   [:urpx/hasEligibilityRule
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector EligibilityRule]]
   [:urpx/hasRateCase
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector Ref]]
   ;; Single Ref (one base plan) or a vector of Refs (NSE/HRA-style modifiers).
   ;; Optional per SHACL (sh:minCount 0): standalone modifier filings whose
   ;; target linkage isn't yet expressed in-document (CPAU E-2-G shape, URPX-g7z)
   ;; carry no applicableToRatePlan and rely on consumer-side composition.
   [:urpx/applicableToRatePlan
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector Ref]]
   [:urpx/applicableToRatePlanVersion
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector Ref]]
   [:urpx/hasPlanElements PlanElements]
   [:urpx/hasPrices       Prices]])

(def RatePlanModifier
  "v0.2.0: modifierUniqueIdentifier is required (was per-version in v0.1.x).
   urpx:isOptional is gone."
  [:map
   [:jsonld/type [:= "urpx:RatePlanModifier"]]
   [:jsonld/id {:optional true} :string]
   [:jsonld/context {:optional true} :map]
   [:urpx/modifierUniqueIdentifier :string]
   [:urpx/name :string]
   [:urpx/initialEffectiveDate Date]
   [:urpx/timezoneIdentifier {:optional true} :string]
   [:urpx/priceCurrency      {:optional true} :string]
   [:urpx/url                {:optional true} :string]
   [:urpx/version            {:optional true} :string]
   [:urpx/hasRatePlanModifierVersion RatePlanModifierVersion]])

(ns urpx.schema
  "Malli schemas for URPX entities, describing the coerced shape.

  Two-layer model (mirroring the clj-oa3 pattern):
    - urpx.core      — raw parse: JSON-LD with namespaced keys, all string values.
    - urpx.schema    — coerced shape: typed Clojure values (BigDecimal, LocalDate,
                       LocalTime, LocalDateTime, Duration, integer month numbers).

  Coercion (URPX-p2y) attaches decoders that turn the raw layer into shapes
  conforming to these schemas. Reference resolution (URPX-ej9) keeps :jsonld/id
  references intact; consumers dereference via an entity index.

  Schemas are bare def vars referenced by symbol — there is no central registry."
  (:import [java.math BigDecimal]
           [java.time Duration LocalDate LocalDateTime LocalTime]))

;; ---------------------------------------------------------------------------
;; Primitive predicates
;; ---------------------------------------------------------------------------

(def Decimal      [:fn {:error/message "should be a BigDecimal"}    #(instance? BigDecimal %)])
(def Date         [:fn {:error/message "should be a LocalDate"}     #(instance? LocalDate %)])
(def DateTime     [:fn {:error/message "should be a LocalDateTime"} #(instance? LocalDateTime %)])
(def Time         [:fn {:error/message "should be a LocalTime"}     #(instance? LocalTime %)])
(def IsoDuration  [:fn {:error/message "should be a Duration"}      #(instance? Duration %)])
(def MonthNumber  [:and :int [:>= 1] [:<= 12]])

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
   [:urpx/month [:vector MonthNumber]]])

(def MetricSpecification
  [:map
   [:jsonld/type [:= "urpx:MetricSpecification"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/measurementKind {:optional true} Ref]
   [:urpx/unitOfMeasure   {:optional true} Ref]
   [:urpx/flowDirection   {:optional true} Ref]])

(def MetricInput
  [:map
   [:jsonld/type [:= "urpx:MetricInput"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/name :string]
   [:urpx/metricInputType Ref]
   [:urpx/hasMetricSpecification {:optional true} MetricSpecification]
   [:urpx/profileMetricProperty  {:optional true} Ref]])

(def TimeBracket
  [:map
   [:jsonld/type [:= "urpx:TimeBracket"]]
   [:urpx/startTime Time]
   [:urpx/endTime Time]
   [:urpx/appliesInSeason {:optional true} Ref]
   [:urpx/dayType         {:optional true} Ref]])

(def TOUPeriod
  [:map
   [:jsonld/type [:= "urpx:TOUPeriod"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/name :string]
   [:urpx/touPeriodNumber :int]
   [:urpx/hasTimeBracket [:vector TimeBracket]]])

(def TOUTier
  [:map
   [:jsonld/type [:= "urpx:TOUTier"]]
   [:urpx/tierSourceName :string]
   [:urpx/touTierStandardName {:optional true} Ref]
   [:urpx/touTierNumber :int]
   [:urpx/hasTOUPeriod [:vector TOUPeriod]]])

(def TOUSchedule
  [:map
   [:jsonld/type [:= "urpx:TOUSchedule"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/referencesMetricInput Ref]
   [:urpx/hasTOUTier [:vector TOUTier]]])

(def CalculationMethod
  [:map
   [:jsonld/type [:= "urpx:CalculationMethod"]]
   [:urpx/intervalDuration {:optional true} IsoDuration]])

(def PriceDefinition
  [:map
   [:jsonld/type [:= "urpx:PriceDefinition"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/name :string]
   [:urpx/chargeType    {:optional true} Ref]
   [:urpx/chargeCategory {:optional true} Ref]
   [:urpx/billPortion   {:optional true} Ref]
   [:urpx/touPeriodNumber {:optional true} :int]
   [:urpx/baseQuantity    {:optional true} Decimal]
   [:urpx/hasCalculationMethod {:optional true} CalculationMethod]])

(def Ledger
  [:map
   [:jsonld/type [:= "urpx:Ledger"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/name :string]
   [:urpx/ledgerType {:optional true} Ref]
   [:urpx/referencesMetricInput {:optional true} Ref]
   ;; URPX allows a single PriceDefinition or a vector — we normalize to a vector
   ;; during coercion (URPX-p2y).
   [:urpx/hasPriceDefinition [:vector PriceDefinition]]])

(def BillingModel
  [:map
   [:jsonld/type [:= "urpx:BillingModel"]]
   [:urpx/hasTOUSchedule {:optional true} TOUSchedule]
   [:urpx/hasLedger [:vector Ledger]]])

(def PlanElements
  [:map
   [:jsonld/type [:= "urpx:PlanElements"]]
   [:urpx/hasSeasons     {:optional true} [:vector Season]]
   [:urpx/hasMetricInput {:optional true} [:vector MetricInput]]
   [:urpx/hasBillingModel BillingModel]])

;; ---------------------------------------------------------------------------
;; Eligibility
;; ---------------------------------------------------------------------------

(def Condition
  [:map
   [:jsonld/type [:= "urpx:Condition"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/conditionType :string]
   [:urpx/operator Ref]
   [:urpx/conditionValue :string]])

(def CustomerProfile
  [:map
   [:jsonld/type [:= "urpx:CustomerProfile"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/hasCustomerKind {:optional true} Ref]])

(def EligibilityRule
  [:map
   [:jsonld/type [:= "urpx:EligibilityRule"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/hasCustomerProfile {:optional true} CustomerProfile]
   [:urpx/logicExpression    {:optional true} :string]
   [:urpx/hasCondition       {:optional true} [:vector Condition]]])

;; ---------------------------------------------------------------------------
;; Prices
;; ---------------------------------------------------------------------------

(def Price
  [:map
   [:jsonld/type [:= "urpx:Price"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/name :string]
   [:urpx/unitPrice Decimal]
   [:urpx/referencesPriceDefinition Ref]
   [:urpx/startDate {:optional true} Date]])

(def PriceSet
  [:map
   [:jsonld/type [:= "urpx:PriceSet"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/name :string]
   [:urpx/startDate Date]
   [:urpx/hasPrice [:vector Price]]])

(def Prices
  [:map
   [:jsonld/type [:= "urpx:Prices"]]
   ;; Flat prices (e.g. Customer Charge, Distribution) live here.
   [:urpx/hasPrice    {:optional true} [:vector Price]]
   ;; TOU price sets keyed by start date live here.
   [:urpx/hasPriceSet {:optional true} [:vector PriceSet]]])

;; ---------------------------------------------------------------------------
;; Top level
;; ---------------------------------------------------------------------------

(def Organization
  [:map
   [:jsonld/type [:= "urpx:Organization"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/legalName :string]
   [:urpx/eiaId            {:optional true} :string]
   [:urpx/organizationType {:optional true} Ref]])

(def DataSourceReference
  [:map
   [:jsonld/type [:= "urpx:DataSourceReference"]]
   [:urpx/url      {:optional true} :string]
   [:urpx/fileName {:optional true} :string]
   [:urpx/mediaType {:optional true} :string]])

(def RatePlanVersion
  [:map
   [:jsonld/type [:= "urpx:RatePlanVersion"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/versionNumber  :string]
   [:urpx/changeReason   {:optional true} Ref]
   [:urpx/description    {:optional true} :string]
   [:urpx/effectiveDateTime DateTime]
   [:urpx/priceCurrency  :string]
   [:urpx/commodity      {:optional true} Ref]
   [:urpx/serviceRole    {:optional true} Ref]
   [:urpx/publishedBy    {:optional true} Organization]
   [:urpx/hasDataSourceReference {:optional true} DataSourceReference]
   [:urpx/hasEligibilityRule     {:optional true} [:vector EligibilityRule]]
   [:urpx/hasPlanElements PlanElements]
   [:urpx/hasPrices       Prices]])

(def RatePlan
  [:map
   [:jsonld/type [:= "urpx:RatePlan"]]
   [:jsonld/id {:optional true} :string]
   [:jsonld/context {:optional true} :map]
   [:urpx/ratePlanIdentifier :string]
   [:urpx/name :string]
   [:urpx/initialEffectiveDate Date]
   [:urpx/hasRatePlanVersion RatePlanVersion]])

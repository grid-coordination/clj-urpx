(ns urpx.schema
  "Malli schemas for URPX entities, describing the coerced shape.

  Two-layer model (mirroring the clj-oa3 pattern):
    - urpx.core      — raw parse: JSON-LD with namespaced keys, all string values.
    - urpx.schema    — coerced shape: typed Clojure values (BigDecimal, LocalDate,
                       LocalTime, LocalDateTime, Duration, integer month numbers).

  Schemas carry `:decode/urpx-jsonld` decoders that turn the raw parser output
  (strings) into the typed shape described here. The transformer is built and
  applied in `urpx.coerce`. Reference resolution (URPX-ej9) keeps :jsonld/id
  references intact; consumers dereference via an entity index.

  Schemas are bare def vars referenced by symbol — there is no central registry."
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
   [:urpx/touPeriodNumber Int]
   [:urpx/hasTimeBracket [:vector TimeBracket]]])

(def TOUTier
  [:map
   [:jsonld/type [:= "urpx:TOUTier"]]
   [:urpx/tierSourceName :string]
   [:urpx/touTierStandardName {:optional true} Ref]
   [:urpx/touTierNumber Int]
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
   [:urpx/touPeriodNumber {:optional true} Int]
   [:urpx/baseQuantity    {:optional true} Decimal]
   [:urpx/hasCalculationMethod {:optional true} CalculationMethod]])

(def Ledger
  [:map
   [:jsonld/type [:= "urpx:Ledger"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/name :string]
   [:urpx/ledgerType {:optional true} Ref]
   [:urpx/referencesMetricInput {:optional true} Ref]
   ;; URPX allows a single PriceDefinition or a vector; the decoder normalizes
   ;; either form to a vector before descending.
   [:urpx/hasPriceDefinition
    {:decode/urpx-jsonld single-or-vec->vec}
    [:vector PriceDefinition]]])

(def ServiceTier
  "A block-usage tier (e.g. CPAU E-1's baseline tier 1 / over-baseline tier 2).
   The bounds are interpreted with the matching :urpx/...BoundOperator vocab IRIs."
  [:map
   [:jsonld/type [:= "urpx:ServiceTier"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/serviceTierNumber Int]
   [:urpx/name {:optional true} :string]
   [:urpx/description {:optional true} :string]
   [:urpx/referencesMetricInput {:optional true} Ref]
   [:urpx/lowerBound {:optional true} Decimal]
   [:urpx/upperBound {:optional true} Decimal]
   [:urpx/lowerBoundOperator {:optional true} Ref]
   [:urpx/upperBoundOperator {:optional true} Ref]
   [:urpx/hasCalculationMethod {:optional true} CalculationMethod]])

(def BillingModel
  [:map
   [:jsonld/type [:= "urpx:BillingModel"]]
   [:urpx/hasTOUSchedule {:optional true} TOUSchedule]
   [:urpx/hasServiceTier {:optional true} [:vector ServiceTier]]
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
   [:urpx/name {:optional true} :string]
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
   ;; Some publishers give the IANA media type as a string ("application/pdf"),
   ;; others use a URPX vocabulary IRI as a Ref ({:jsonld/id "urpx:applicationPdf"}).
   [:urpx/mediaType {:optional true} [:or :string Ref]]])

(def RatePlanVersion
  [:map
   [:jsonld/type [:= "urpx:RatePlanVersion"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/versionNumber  {:optional true} :string]
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

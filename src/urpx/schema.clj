(ns urpx.schema
  "Malli schemas for URPX entities, describing the coerced shape.

  Tracks the ontology snapshot the fixtures pin via urpx:targetURPXVersion,
  currently v0.5.1. scripts/shacl-drift.sh reports where these have fallen
  behind the upstream shapes.

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

(defn- ->decimal
  "Coerce raw input forms to BigDecimal:
   - BigDecimal: pass-through
   - number (long, double, ratio, BigInteger): coerce via clojure.core/bigdec,
     which uses BigDecimal/valueOf for floats (canonical short-form scale —
     `(bigdec 0.1)` → `0.1M`, NOT the binary-float garbage that `(BigDecimal. 0.1)`
     produces)
   - string: parse as xsd:decimal
   - anything else: pass-through (let validation surface the type mismatch)

   The number form lets DSL callers write `:urpx/unitPrice 0.15075`
   instead of `(BigDecimal. \"0.15075\")`."
  [v]
  (cond
    (instance? BigDecimal v) v
    (number? v)              (bigdec v)
    (string? v)              (BigDecimal. ^String v)
    :else                    v))

;; ---------------------------------------------------------------------------
;; Encoder helpers (consumed by `:encode/urpx-jsonld` properties below)
;; ---------------------------------------------------------------------------
;; Symmetric to the ->X decoders above. Used by the write-side path
;; (urpx.emit) to turn typed values back into JSON-LD strings.
;; Each helper passes through already-string values unchanged, mirroring
;; the way decoders pass through already-typed values.

(defn- <-decimal
  "BigDecimal → plain-string form, preserving scale so a round-trip yields a
   BigDecimal equal to the original. `.toPlainString` avoids scientific
   notation that JSON-LD parsers wouldn't expect for xsd:decimal."
  [v]
  (if (instance? BigDecimal v) (.toPlainString ^BigDecimal v) v))

(defn- <-date     [v] (if (instance? LocalDate v) (.toString ^LocalDate v) v))
(defn- <-datetime [v] (if (instance? LocalDateTime v) (.toString ^LocalDateTime v) v))
(defn- <-time     [v] (if (instance? LocalTime v) (.toString ^LocalTime v) v))

(defn- <-duration
  "Duration → ISO 8601 time-based duration (PT1H30M); Period → ISO 8601
   date-based duration (P1M, P1Y). Both java.time toString implementations
   already produce the canonical xsd:duration form."
  [v]
  (cond
    (instance? Duration v) (.toString ^Duration v)
    (instance? Period v)   (.toString ^Period v)
    :else v))

(defn- <-g-month
  "Month-of-year int → xsd:gMonth string '--MM' (zero-padded). The decoder
   parses both '--06' and bare numerics; the encoder emits the canonical
   '--06' form regardless."
  [v]
  (if (int? v) (format "--%02d" v) v))
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
        :decode/urpx-jsonld ->decimal
        :encode/urpx-jsonld <-decimal}
   #(instance? BigDecimal %)])

(def Date
  [:fn {:error/message "should be a LocalDate"
        :decode/urpx-jsonld ->date
        :encode/urpx-jsonld <-date}
   #(instance? LocalDate %)])

(def DateTime
  [:fn {:error/message "should be a LocalDateTime"
        :decode/urpx-jsonld ->datetime
        :encode/urpx-jsonld <-datetime}
   #(instance? LocalDateTime %)])

(def Time
  [:fn {:error/message "should be a LocalTime"
        :decode/urpx-jsonld ->time
        :encode/urpx-jsonld <-time}
   #(instance? LocalTime %)])

(def IsoDuration
  [:fn {:error/message "should be a Duration or Period"
        :decode/urpx-jsonld ->duration
        :encode/urpx-jsonld <-duration}
   #(or (instance? Duration %) (instance? Period %))])

(def MonthNumber
  [:and {:decode/urpx-jsonld ->g-month
         :encode/urpx-jsonld <-g-month}
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

(def ExclusiveRef
  "A JSON-LD @id reference used where an INLINE NODE is the alternative, as in
   [:or SomeEntity ExclusiveRef]. Closed, unlike `Ref`, and that is the whole
   point: `Ref` is an open map, so an [:or Node Ref] whose second arm is `Ref`
   matches any map carrying a :jsonld/id and the node arm is never reached.
   A malformed inline node then validates as though it were a reference.
   Closing it means a bare reference still passes while anything richer must
   satisfy the node schema.

   Closed to @id ALONE, deliberately. A node that asserts an @type is asserting
   something about itself and should be validated as a node of that type, not
   waved through as a reference; without this, an @id paired with any @type at
   all passes whatever slot it sits in.
   Every one of the 15,295 bare references in the urpx-rate-plans corpus carries
   @id and nothing else, so this costs nothing real.

   Use `Ref` for reference-only properties, where no inline form is permitted
   and openness costs nothing."
  [:map {:closed true}
   [:jsonld/id :string]])

(def Context
  "A JSON-LD @context, in any of the three forms the spec permits: an inline
   definition object, an IRI string pointing at a remote context, or an array
   mixing the two.

   The array form is not hypothetical: every v0.5.1 test case shipped with
   urpx-org/urpx carries
     [\"https://urpx.org/ns/context/releases/v0.5.1/jsonld/urpx-context.jsonld\"
      {\"prefix\": \"...\"}]
   a remote context followed by a local prefix map, so typing this as :map
   alone rejected the standard's own reference documents.

   Deliberately permissive. clj-urpx never interprets @context: it round-trips
   it untouched and urpx.emit attaches its own. There is no consumer path that
   a tighter shape would protect, so the only thing a narrower type can do here
   is reject valid input."
  [:or :map :string [:vector [:or :map :string]]])

(defn enum-ref
  "A JSON-LD @id reference restricted to a fixed vocabulary: like `Ref`, but the
   id must be one of `locals`, each given bare and prefixed with \"urpx:\" here.

   URPX encodes these as IRI references rather than strings, so a value arrives
   as {:jsonld/id \"urpx:complete\"} and this checks membership of that id.
   Upstream they are owl:oneOf classes enforced through sh:in at the property."
  [& locals]
  [:map [:jsonld/id (into [:enum] (map #(str "urpx:" %)) locals)]])

;; Members transcribed from urpx-shacl sh:in lists at urpx-org/urpx 95eb8e22
;; and counted against them, not re-derived.

(def CompletenessResult
  "urpx:CompletenessResultValue, 6 members. The coverage verdict on a
   CoverageClaim. Freshness (urpx:stale) and resolution provenance
   (urpx:fallbackSourced) are orthogonal booleans, not members here."
  (enum-ref "complete" "partial" "pending" "unsupported" "unknown" "noCoverage"))

(def ReferenceInclusion
  "urpx:ReferenceInclusionValue, 2 members."
  (enum-ref "includedHere" "retrievedElsewhere"))

(def Confidence
  "urpx:ConfidenceValue, 3 members."
  (enum-ref "verified" "estimated" "projected"))

(def EvaluationKind
  "urpx:EvaluationKindValue, 5 members."
  (enum-ref "consolidation" "coverageEvaluation" "validation" "comparison" "benchmark"))

(def ExclusionReason
  "urpx:ExclusionReasonValue, 4 members. New at v0.5.1."
  (enum-ref "pruned" "fullySuperseded" "voided" "outOfScope"))

(def ScopeKind
  "urpx:ScopeKindValue, 5 members, from urpx-shacl:ScopeKindValueShape's sh:in.

   The kinds are what make a TemporalScope readable: the same date range means
   different things depending on which clock it refers to. urpx:coverage is the
   one a consumer asking \"does this verdict apply to my window?\" needs; the
   others are about publication, the source's own lifecycle, when the facts
   apply, and when something was observed."
  (enum-ref "publication" "sourceLifecycle" "applicability" "observation" "coverage"))

(def SourcePublicationType
  "urpx:SourcePublicationTypeValue, the shipped 8 members plus one legacy.

   The class was urpx:SourcePublicationType at the epic baseline and is
   Value-suffixed at 95eb8e22, same 8 members. Do NOT reuse the retired
   10-member urpx:PublicationTypeValue, which has zero occurrences at v0.5.1.

   urpx:regulatoryFiling, a member of that retired class, was accepted here as
   a documented legacy value while the corpus still emitted it. Migration wave
   6 finished and it is gone: the corpus now models the same node as
   urpx:tariffSupplement, following upstream's own v0.5.1 test case for the
   identical Advice Letter rather than inventing a parallel convention. Zero
   occurrences remain in the corpus. The allowance is RETAINED anyway, because
   removing it would reject a v0.2.x urpx:TariffPublication that carries the old
   member, and this library still advertises v0.2.x acceptance. Drop it when
   that promise is withdrawn."
  (enum-ref "tariffBook" "tariffStatement" "tariffSupplement" "tariffAddendum"
            "supplyCharges" "derExports" "highlyDynamicPrices" "marketPriceFeed"
            ;; RETAINED pending a decision: dropping it would reject a v0.2.x
            ;; urpx:TariffPublication carrying it, and this library advertises
            ;; v0.2.x acceptance. The corpus no longer emits it.
            "regulatoryFiling"))

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
   ;; 0..N upstream (no sh:maxCount). Accepted as one or many; price.clj reads
   ;; one through urpx.index/single, which refuses rather than guessing.
   [:urpx/hasMetricSpecification {:optional true}
    [:or MetricSpecification [:vector MetricSpecification]]]
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
   ;; 0..N upstream. TOU matching in price.clj reads one via index/single.
   [:urpx/appliesInSeason     {:optional true} [:or Ref [:vector Ref]]]
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
   ;; 0..N upstream (sh:minCount 0, no sh:maxCount); nothing in src/ reads it.
   [:urpx/chargeType
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector Ref]]
   [:urpx/chargeCategory {:optional true} Ref]
   [:urpx/billPortion   {:optional true} Ref]
   [:urpx/commodity     {:optional true} Ref]
   [:urpx/priceCurrency {:optional true} :string]
   [:urpx/primaryChargeType {:optional true} Ref]
   ;; v0.2.0: required, single. PD points to its parent Ledger.
   [:urpx/referencesLedger Ref]
   ;; 0..N upstream (sh:minCount 0, no sh:maxCount), and the skos:note names
   ;; PriceDefinition explicitly: "Use on PriceSetAlternative, PriceDefinition,
   ;; TOUSchedule, ServiceTiers, or ServiceBracket to scope applicability to a
   ;; specific ProfileAlternative." This is the SELECTION layer reaching a
   ;; definition, which is how a multi-dimensional matrix declares exclusivity
   ;; readably rather than burying it in ApplicabilityRule predicates nothing
   ;; evaluates. `urpx.price` reads ONE; several refuse rather than inventing a
   ;; conjunctive reading for a shape no filing has.
   [:urpx/referencesProfileAlternative
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector Ref]]
   [:urpx/referencesMetricInput
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector Ref]]
   [:urpx/referencesServiceTier    {:optional true} Ref]
   [:urpx/referencesServiceBracket {:optional true} Ref]
   [:urpx/referencesTouPeriod      {:optional true} Ref]
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

(def ServiceBracket
  "v0.2.0 + 423e83f: contextual boundary definition for a ServiceTier.
   Carries the bounds + bound operators that previously lived inline on
   ServiceTier, plus optional appliesInSeason / referencesProfileAlternative
   qualifiers so a single logical tier (one ServiceTier, identified by
   serviceTierNumber) can carry season- or PA-specific bound variants by
   attaching multiple ServiceBrackets via ServiceTier.hasServiceBracket.

   SHACL enforces sh:or three branches (lower-only, upper-only, or both
   bounds + matching operators); Malli stays open-world, consistent with
   the customDimensionProperty / customConditionProperty pattern."
  [:map
   [:jsonld/type [:= "urpx:ServiceBracket"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/lowerBound         {:optional true} Decimal]
   [:urpx/upperBound         {:optional true} Decimal]
   [:urpx/lowerBoundOperator {:optional true} Ref]
   [:urpx/upperBoundOperator {:optional true} Ref]
   [:urpx/appliesInSeason
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector Ref]]
   [:urpx/referencesProfileAlternative {:optional true} Ref]])

(def ServiceTier
  "v0.2.0 + 423e83f: bounds moved off ServiceTier onto attached
   ServiceBracket instances (Decision #111). ServiceTier carries only the
   logical identity (serviceTierNumber + optional name); hasServiceBracket
   carries 1+ bound-bearing brackets, optionally qualified by season or
   ProfileAlternative for matrix-cell variants of the same logical tier."
  [:map
   [:jsonld/type [:= "urpx:ServiceTier"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/serviceTierNumber Int]
   [:urpx/name {:optional true} :string]
   [:urpx/hasServiceBracket
    {:decode/urpx-jsonld single-or-vec->vec}
    [:vector ServiceBracket]]])

(def ServiceTiers
  "v0.2.0 + 0994aec: container holding the block-tier vector. Carries the
   referencesMetricInput pointer that used to live on individual ServiceTiers
   in v0.1.x.

   :urpx/appliesInSeason allows seasonal grouping — a single
   ProfileAlternative-keyed ServiceTiers can be split into per-season
   containers without nesting season into serviceTierNumber.

   :urpx/referencesProfileAlternative wires a per-cell ServiceTiers to one
   ProfileAlternative within a matrix-style hasServiceTiers vector.

   :urpx/referencesMetricInput names the quantity a tier's BOUNDS are stated
   in, which is NOT the same as the quantity the ledger's charge is stated in.
   The two are meant to differ: LADWP R-1A's Power Access Charge is 2.30, 7.90
   and 22.70 per P1M per meter, with bounds in kWh, so its container names
   mi-energy while its ledger names mi-meter-count. Reading this property as a
   pointer to the ledger a container belongs to would therefore strip the
   bounds off a monthly fixed charge tiered by consumption, which is a real
   filing shape rather than a modeling error (URPX-c0d).

   No ServiceTiers in the vendored corpus carries either qualifier. The PG&E
   E-TOU-C baseline-tier modifier is not the per-cell shape: it carries one
   container of three tiers, 40 brackets each, and states its
   20 (territory, code) x 2 season matrix per urpx:ServiceBracket.

   Both qualifiers are read at resolution time, on this container and on
   urpx:ServiceBracket alike, by `urpx.price/select-tier-bracket`, and on a
   urpx:RatePlanModifier's tiers as well as a urpx:RatePlan's. The 120 above
   are the case that made it matter: composed with their base plan and given a
   baseline cell, they state 0 to 13.5 and 13.5 to 54.0 kWh per day in summer
   and 0 to 11.0 and 11.0 to 44.0 in winter."
  [:map
   [:jsonld/type [:= "urpx:ServiceTiers"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/appliesInSeason
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector Ref]]
   [:urpx/referencesMetricInput
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector Ref]]
   [:urpx/referencesProfileAlternative {:optional true} Ref]
   [:urpx/hasServiceTier [:vector ServiceTier]]])

(def BillingModel
  "v0.2.0: hasPriceDefinition is required (min 1) and lives at this level
   (not inside Ledger). The v0.1.x BillingModel.hasServiceTier flat vector
   is gone — block tiers now live under hasServiceTiers's nested
   `urpx:hasServiceTier` field.

   Both hasTOUSchedule and hasServiceTiers are vectors (sh:maxCount
   unbounded per BillingModelShape). `single-or-vec->vec` keeps
   single-container fixtures compatible without touching their JSON-LD:

     - hasTOUSchedule is a vector for filings that TOU-zone multiple
       metric inputs (CPAU E-4 TOU: separate energy + demand schedules).
     - hasServiceTiers is a vector for a filing that splits its tiers into
       per-season or per-cell containers. No fixture does: the PG&E E-TOU-C
       baseline-tier modifier carries one ServiceTiers of three tiers and
       states its 20 (territory, code) x 2 season matrix per
       urpx:ServiceBracket, 40 brackets per tier."
  [:map
   [:jsonld/type [:= "urpx:BillingModel"]]
   [:urpx/prorationMethod {:optional true} :string]
   [:urpx/hasTOUSchedule
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector TOUSchedule]]
   [:urpx/hasServiceTiers
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector ServiceTiers]]
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

;; PlanElements is defined further down — its hasProfileAlternatives entry
;; depends on ProfileAlternatives, which depends on ConditionExpression
;; (defined below).

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

;; ---------------------------------------------------------------------------
;; ProfileAlternatives — declarative customer-or-configuration choice
;; ---------------------------------------------------------------------------
;; Per URPX v0.2.0, ProfileAlternatives + PriceSetAlternatives express
;; mutually-exclusive options that require an explicit selection (income-tier
;; CARE/FERA/Standard, vintage-PCIA default tier, voltage configurations, …).
;; ProfileAlternatives lives at PlanElements.hasProfileAlternatives;
;; PriceSetAlternatives lives at Prices.hasPriceSetAlternatives and references
;; the ProfileAlternatives whose alternatives drive the per-price selection.
;; Authoritative shapes: urpx-shacl:ProfileAlternativesShape, *Shape, etc.

(def AlternativeDimension
  "One axis of a customer-profile choice — e.g. 'incomeTier' (with possible
   values CARE / FERA / Standard) or 'voltageLevel'.

   Per upstream urpx commit c9d974f (closes urpx-org/urpx#30), exactly one
   of urpx:dimensionProperty (xsd:anyURI when a suitable URPX property
   exists) or urpx:customDimensionProperty (xsd:string fallback) must be
   present. The xone is enforced by SHACL (pyshacl); we keep the Malli
   schema open-world and let SHACL catch violations, consistent with the
   existing stance for analogous customConditionProperty on operand nodes."
  [:map
   [:jsonld/type [:= "urpx:AlternativeDimension"]]
   [:jsonld/id   {:optional true} :string]
   [:urpx/dimensionDisplayName :string]
   [:urpx/dimensionProperty       {:optional true} :string]   ; xsd:anyURI
   [:urpx/customDimensionProperty {:optional true} :string]   ; xsd:string fallback
   [:urpx/hasPossibleValues
    {:decode/urpx-jsonld single-or-vec->vec}
    [:vector :any]]
   [:urpx/referencesMetricInput
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector Ref]]])

(def AlternativeDimensions
  "Container for the AlternativeDimension(s) that span a ProfileAlternatives
   choice (typically a single dimension; multi-dimension matrices possible)."
  [:map
   [:jsonld/type [:= "urpx:AlternativeDimensions"]]
   [:jsonld/id   {:optional true} :string]
   [:urpx/hasAlternativeDimension
    {:decode/urpx-jsonld single-or-vec->vec}
    [:vector AlternativeDimension]]])

(def ProfileAlternative
  "A single named alternative within a ProfileAlternatives set. The
   `hasConditionExpression` says how to determine whether this alternative
   applies to a given customer; `defaultSelectionLogic` is a free-form
   tie-breaker / default note when conditions don't disambiguate."
  [:map
   [:jsonld/type [:= "urpx:ProfileAlternative"]]
   [:jsonld/id   {:optional true} :string]
   [:urpx/name :string]
   [:urpx/defaultSelectionLogic   {:optional true} :string]
   [:urpx/hasConditionExpression  ConditionExpression]])

(def ProfileAlternatives
  "Top-level declaration of a customer-profile-driven choice. Lives at
   PlanElements.hasProfileAlternatives. PriceSetAlternatives reference one
   ProfileAlternatives via urpx:referencesProfileAlternatives, and each
   PriceSetAlternative within the set selects ONE of its sibling
   ProfileAlternatives via urpx:referencesProfileAlternative."
  [:map
   [:jsonld/type [:= "urpx:ProfileAlternatives"]]
   [:jsonld/id   {:optional true} :string]
   [:urpx/name :string]
   [:urpx/hasAlternativeDimensions AlternativeDimensions]
   [:urpx/hasProfileAlternative
    {:decode/urpx-jsonld single-or-vec->vec}
    [:vector ProfileAlternative]]])

(def PlanElements
  "v0.2.0: gains optional `urpx:hasProfileAlternatives` for declarative
   customer-or-configuration choices (income tier, voltage configuration,
   PCIA default tier, …). Each ProfileAlternatives is referenced by a
   PriceSetAlternatives at Prices.hasPriceSetAlternatives — the per-customer
   selection that determines which alternative's prices apply."
  [:map
   [:jsonld/type [:= "urpx:PlanElements"]]
   [:urpx/hasSeasons         {:optional true} [:vector Season]]
   [:urpx/hasMetricInput     [:vector MetricInput]]
   ;; 0..N upstream. holidays.clj reads one via index/single. Merging several
   ;; calendars into one holiday set looks obvious but is a semantic decision
   ;; the vocabulary does not state, so it refuses instead.
   [:urpx/hasHolidayCalendar {:optional true}
    [:or HolidayCalendar [:vector HolidayCalendar]]]
   [:urpx/hasProfileAlternatives
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector ProfileAlternatives]]
   [:urpx/hasBillingModel BillingModel]])

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
   ;; 0..N upstream (sh:minCount 0, no sh:maxCount); nothing in src/ reads it.
   [:urpx/hasCustomerKind
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector Ref]]
   ;; 0..N upstream (sh:minCount 0, no sh:maxCount); nothing in src/ reads it.
   [:urpx/hasCustomerSubKind
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector Ref]]
   ;; 0..N upstream (sh:minCount 0, no sh:maxCount); nothing in src/ reads it.
   [:urpx/entityType
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector Ref]]])

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
   ;; CALCULATION INPUT semantics upstream, distinct from includesPrice's
   ;; AGGREGATION. sh:or (urpx:Price, urpx:PriceSummary), and 0..N: sh:minCount
   ;; 0 with no sh:maxCount. Ref rather than one of those two schemas because
   ;; either may be named instead of inlined, and a bare reference says nothing
   ;; about which; ExclusiveRef would reject the inline form upstream permits.
   ;; Several values are read DISJUNCTIVELY by `urpx.price/price-value`:
   ;; candidate bases, exactly one expected to be in the documents passed to a
   ;; call. That lets one discount Price serve a modifier applying to many base
   ;; plans. URPX fixes the cardinality and says nothing about multiplicity;
   ;; the conjunctive reading is what urpx:PriceSummary already provides.
   [:urpx/basedOnPrice
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector Ref]]
   [:urpx/baseQuantity {:optional true} Decimal]
   [:urpx/priceCurrency {:optional true} :string]
   ;; sh:minCount 0 sh:maxCount 1 upstream: a conformant Price may omit it.
   ;; Required here until now, which rejected such a Price; PriceSetAlternative
   ;; already modeled it optional, so the two disagreed with each other too.
   ;; Both readers, urpx.price/scoped-prices and price-active-at-tou?,
   ;; nil-thread through get-in.
   [:urpx/referencesPriceDefinition {:optional true} Ref]
   [:urpx/referencesServiceTier    {:optional true} Ref]
   [:urpx/referencesServiceBracket {:optional true} Ref]
   [:urpx/referencesTouPeriod      {:optional true} Ref]
   [:urpx/serviceTierNumber {:optional true} Int]
   [:urpx/touPeriodNumber   {:optional true} Int]
   [:urpx/startDate     {:optional true} Date]
   [:urpx/startDateTime {:optional true} DateTime]
   [:urpx/endDate       {:optional true} Date]
   [:urpx/endDateTime   {:optional true} DateTime]
   [:urpx/appliesInSeason
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector Ref]]
   ;; 0..N upstream (sh:minCount 0, no sh:maxCount); nothing in src/ reads it.
   [:urpx/hasApplicabilityGroup
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector ApplicabilityGroup]]
   [:urpx/hasApplicabilityRule
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    ;; Inline rule or a bare @id ref to one defined elsewhere in the document
    ;; (RDF merges a reference with its definition). Richer schema first so an
    ;; inline node decodes through ApplicabilityRule; ExclusiveRef rather than
    ;; Ref so a malformed inline node cannot slip through the reference arm.
    [:vector [:or ApplicabilityRule ExclusiveRef]]]
   [:urpx/hasCalculationMethod {:optional true} CalculationMethod]
   [:urpx/hasRateCase
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector Ref]]])

(def PriceSummary
  "An aggregation of Prices into a subtotal, referenced by another Price's
   urpx:basedOnPrice to express a value derived from them (a percentage
   discount over selected line items, for instance).

   urpx:includesPrice is sh:minCount 2 upstream: a summary of one price is not
   a summary. urpx:summaryAmount is the author's own stated total, optional and
   NOT what the resolver uses; the resolver sums the members IN EFFECT at the
   instant being resolved, so an author-stated total that disagrees is visible
   rather than authoritative.

   Members are gated by the same season, month and TOU predicates that gate a
   tier, because a summary becomes a unit price at an instant and summing a
   line item that is not in effect would be the wrong number. One summary may
   therefore name every discountable line item on a plan and stay correct in
   every TOU period.

   Which Prices a summary names is entirely the author's business, and
   deliberately so: on one base plan a CARE summary and a FERA summary name
   different members, some carve the base services charge out and some do not,
   and member counts vary widely between utilities. None of that is knowable
   here, and none of it should be."
  [:map
   [:jsonld/type [:= "urpx:PriceSummary"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/name {:optional true} :string]
   [:urpx/summaryAmount {:optional true} Decimal]
   [:urpx/includesPrice
    {:decode/urpx-jsonld single-or-vec->vec}
    [:and [:vector [:or Price ExclusiveRef]]
     [:fn {:error/message "urpx:includesPrice is sh:minCount 2 upstream"}
      #(<= 2 (count %))]]]])

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
   [:urpx/referencesServiceBracket  {:optional true} Ref]
   ;; 0..N upstream (sh:minCount 0, no sh:maxCount); nothing in src/ reads it.
   [:urpx/hasApplicabilityGroup
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector ApplicabilityGroup]]
   ;; 0..N upstream. This is where a summary actually attaches: urpx:hasPriceSummary
   ;; is rdfs:domain urpx:PriceSet, so a subtotal lives beside the line items it
   ;; names rather than at the Prices container or the top of the graph.
   [:urpx/hasPriceSummary
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector [:or PriceSummary ExclusiveRef]]]
   [:urpx/hasPrice [:vector Price]]])

;; Prices is defined further down — its hasPriceSetAlternatives entry
;; depends on PriceSetAlternatives, which depends on DataSourceReference
;; (defined below).

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
  "v0.2.x only. urpx:TariffBookReference was DELETED upstream in bbaea780 and
   has zero occurrences in the v0.5.1 ontology and SHACL; its fields moved
   inline onto urpx:DataSourceReference, whose shape is now sh:closed. Retained
   to validate v0.2.x filings, which is every filing in the corpus today.
   Remove only once the last migration wave lands."
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
   ;; v0.5.1 flattening (upstream bbaea780): urpx:TariffBookReference and
   ;; urpx:TariffStatementReference were deleted and their fields hoisted
   ;; inline onto this node, which is now sh:closed with 31 paths and nothing
   ;; required. hasSelector and sourcePublication are not new, merely never
   ;; modeled here.
   [:urpx/leafStart  {:optional true} :string]
   [:urpx/leafEnd    {:optional true} :string]
   [:urpx/anchorLink {:optional true} :string]
   ;; Ref, not a typed schema: oa:Selector is an external Web Annotation class
   ;; the ontology deliberately does not import, and the shape constrains only
   ;; sh:class oa:Selector.
   ;; 0..N upstream (sh:minCount 0, no sh:maxCount); nothing in src/ reads it.
   [:urpx/hasSelector
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector Ref]]
   [:urpx/sourcePublication {:optional true} Ref]
   ;; Enum membership deliberately unenforced here; see the enum issue.
   [:urpx/tariffStatementType {:optional true} Ref]
   ;; v0.2.x nesting, kept so every corpus filing still validates. The
   ;; flattened form above is what a v0.5.1 filing carries instead.
   [:urpx/hasTariffBookReference {:optional true} TariffBookReference]])

;; ---------------------------------------------------------------------------
;; Typed identity (URPX v0.5.1)
;;
;; A plan, a modifier and their version snapshots can each carry typed external
;; identifiers: a CPUC MIDAS RIN, a utility tariff code, whatever register
;; applies. All three shapes require skos:notation and urpx:scheme and are
;; sh:closed upstream; clj-urpx does not enforce closedness.
;; ---------------------------------------------------------------------------

(def ^:private identifier-entries
  [[:skos/notation :string]
   ;; urpx:IdentifierScheme is an OPEN class, deliberately: the ontology says
   ;; "not a closed enumeration ... an implementer operating a scheme that is
   ;; not among them mints its own IRI as an IdentifierScheme instance." There
   ;; is no sh:in on this path, so a plain Ref is correct and an :enum would be
   ;; wrong. The vocabulary publishes no named scheme individuals at 95eb8e22.
   [:urpx/scheme Ref]
   [:urpx/identifierJurisdiction {:optional true} Ref]])

(def RatePlanIdentifier
  "A typed external identifier for a urpx:RatePlan, e.g. a CPUC MIDAS RIN."
  (into [:map
         [:jsonld/type [:= "urpx:RatePlanIdentifier"]]
         [:jsonld/id {:optional true} :string]]
        identifier-entries))

(def RatePlanModifierIdentifier
  "Parallel to `RatePlanIdentifier`, for a urpx:RatePlanModifier."
  (into [:map
         [:jsonld/type [:= "urpx:RatePlanModifierIdentifier"]]
         [:jsonld/id {:optional true} :string]]
        identifier-entries))

(def OrganizationIdentifier
  "A typed external identifier for an organization, hung off urpx:hasIdentity.
   v0.5.1 reshaped this: urpx:identifierScheme and urpx:utilityId are gone and
   skos:notation plus urpx:scheme are now required. An EIA id rides here as
   notation under the EIA scheme rather than as the former urpx:eiaId."
  (into [:map
         [:jsonld/type [:= "urpx:OrganizationIdentifier"]]
         [:jsonld/id {:optional true} :string]]
        identifier-entries))

;; ---------------------------------------------------------------------------
;; Coverage machinery (URPX v0.5.1)
;;
;; A version snapshot states what it does and does not cover, so a consumer can
;; tell a complete resolution from a partial one. Read from urpx-shacl at
;; urpx-org/urpx 95eb8e22. Enum-valued slots stay bare Refs here; membership
;; enforcement is a separate concern.
;; ---------------------------------------------------------------------------

(def ModelExclusion
  "Something a PricingModel deliberately leaves out, and why. New in v0.5.1.
   urpx:PricingModel is not modeled in clj-urpx, so excludedModel stays a Ref."
  [:map
   [:jsonld/type [:= "urpx:ModelExclusion"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/excludedModel Ref]
   [:urpx/exclusionReason ExclusionReason]
   [:urpx/description {:optional true} :string]])

(def TemporalPeriod
  "A named date range. urpx-shacl:TemporalPeriodShape is sh:closed with
   rdfs:label, urpx:startDate and urpx:endDate, each sh:minCount 0 sh:maxCount 1,
   so a conformant period may state none of them."
  [:map
   [:jsonld/type {:optional true} [:= "urpx:TemporalPeriod"]]
   [:jsonld/id   {:optional true} :string]
   [:rdfs/label  {:optional true} :string]
   [:urpx/startDate {:optional true} Date]
   [:urpx/endDate   {:optional true} Date]])

(def TemporalScope
  "A role-tagged time span: which clock a date range refers to.

   Transcribed from urpx-shacl:TemporalScopeShape, which IS fully specified:
   sh:closed, urpx:scopeKind sh:minCount 1 sh:maxCount 1 against
   ScopeKindValueShape, and urpx:startDate, urpx:endDate, urpx:startDateTime,
   urpx:endDateTime and urpx:hasTemporalPeriod each 0..1. scopeKind is the only
   required field, so a scope may name its role and state no bounds at all.

   WHAT IS NOT SPECIFIED IS THE EDGE TO IT. urpx:hasTemporalScope appears as
   sh:path ZERO times in the shapes graph and in sh:ignoredProperties 79 times,
   so every closed shape permits it and none constrains it: nothing says which
   classes may carry a scope, how many, or of which kind. Modeling the node is
   transcription; modeling the edge is a judgement, and it is made as 0..N
   because that is what \"unconstrained\" means rather than because any filing
   carries several."
  [:map
   [:jsonld/type {:optional true} [:= "urpx:TemporalScope"]]
   [:jsonld/id   {:optional true} :string]
   [:urpx/scopeKind ScopeKind]
   [:urpx/startDate     {:optional true} Date]
   [:urpx/endDate       {:optional true} Date]
   [:urpx/startDateTime {:optional true} DateTime]
   [:urpx/endDateTime   {:optional true} DateTime]
   [:urpx/hasTemporalPeriod {:optional true} [:or TemporalPeriod ExclusiveRef]]])

(def Evaluation
  "How a coverage claim was arrived at: who evaluated it, when, and of what
   kind. Reached from CoverageClaim via urpx:explainedBy."
  [:map
   [:jsonld/type [:= "urpx:Evaluation"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/evaluationKind {:optional true} EvaluationKind]
   [:urpx/description    {:optional true} :string]
   [:urpx/evaluatedBy    {:optional true} [:or Organization ExclusiveRef]]
   [:urpx/evaluatedOn    {:optional true} DateTime]
   [:urpx/hasModelExclusion
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector [:or ModelExclusion ExclusiveRef]]]])

(def CoverageClaim
  "What a version snapshot claims to cover. urpx:completenessResult is the one
   required field (sh:minCount 1 on urpx-shacl:CoverageClaimShape)."
  [:map
   [:jsonld/type [:= "urpx:CoverageClaim"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/completenessResult CompletenessResult]
   [:urpx/confidence       {:optional true} Confidence]
   [:urpx/selfContained    {:optional true} :boolean]
   ;; The skos:note on urpx:CoverageClaim says producers "should attach a
   ;; TemporalScope with scopeKind coverage stating the window the verdict
   ;; covers". Should, not must, and no shape enforces it: measured zero
   ;; TemporalScope nodes of any kind across the production corpus and the
   ;; vendored fixtures. A verdict that states no window is the normal case
   ;; today, which is what makes reading one worth doing carefully.
   [:urpx/hasTemporalScope
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector [:or TemporalScope ExclusiveRef]]]
   [:urpx/stale            {:optional true} :boolean]
   [:urpx/fallbackSourced  {:optional true} :boolean]
   [:urpx/explainedBy
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector [:or Evaluation ExclusiveRef]]]])

(def PriceDefinitionReference
  "A PriceDefinition a snapshot relies on, and whether its content is included
   here or has to be retrieved elsewhere. urpx:referenceInclusion is required.
   urpx:ModelCollection is not modeled, so resolvedFrom stays a Ref vector."
  [:map
   [:jsonld/type [:= "urpx:PriceDefinitionReference"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/referenceInclusion ReferenceInclusion]
   [:urpx/referencesPriceDefinition {:optional true} Ref]
   [:urpx/documentedBy
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector [:or DataSourceReference ExclusiveRef]]]
   [:urpx/resolvedFrom
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector Ref]]])

;; ---------------------------------------------------------------------------
;; PriceSetAlternative(s) — pairs with ProfileAlternatives above
;; ---------------------------------------------------------------------------

(def PriceSetAlternative
  "One Price-bearing alternative within a PriceSetAlternatives set.
   Carries Price-like top-level fields (chargeType, billPortion, …) plus
   the required `referencesProfileAlternative` Ref linking it to one of
   the sibling ProfileAlternatives, plus `hasPrice` (1..*) for the actual
   prices that apply when this alternative is selected."
  [:map
   [:jsonld/type [:= "urpx:PriceSetAlternative"]]
   [:jsonld/id   {:optional true} :string]
   [:urpx/name             {:optional true} :string]
   [:urpx/description      {:optional true} :string]
   [:urpx/alternativeLabel {:optional true} :string]
   [:urpx/billingCycleCode {:optional true} :string]
   [:urpx/priceCurrency    {:optional true} :string]
   [:urpx/priceMatchingKey {:optional true} :string]
   ;; Decimals
   [:urpx/unitPrice    {:optional true} Decimal]
   [:urpx/amount       {:optional true} Decimal]
   [:urpx/basePrice    {:optional true} Decimal]
   [:urpx/currentPrice {:optional true} Decimal]
   [:urpx/adder        {:optional true} Decimal]
   [:urpx/multiplier   {:optional true} Decimal]
   [:urpx/baseQuantity {:optional true} Decimal]
   ;; Refs
   [:urpx/billPortion       {:optional true} Ref]
   [:urpx/chargeCategory    {:optional true} Ref]
   [:urpx/chargeType
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector Ref]]
   [:urpx/commodity         {:optional true} Ref]
   [:urpx/primaryChargeType {:optional true} Ref]
   ;; Dates / times
   [:urpx/startDate     {:optional true} Date]
   [:urpx/endDate       {:optional true} Date]
   [:urpx/expectedEndDate {:optional true} Date]
   [:urpx/startDateTime {:optional true} DateTime]
   [:urpx/endDateTime   {:optional true} DateTime]
   [:urpx/expectedEndDateTime {:optional true} DateTime]
   ;; Integers
   [:urpx/serviceTierNumber {:optional true} Int]
   [:urpx/touPeriodNumber   {:optional true} Int]
   ;; Multi-valued refs
   [:urpx/appliesInSeason
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector Ref]]
   [:urpx/referencesMetricInput
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector Ref]]
   [:urpx/hasAlternative
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector Ref]]
   [:urpx/hasRateCase
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector Ref]]
   ;; Single Refs
   [:urpx/referencesPriceDefinition   {:optional true} Ref]
   [:urpx/referencesProfileAlternative Ref]   ; required (sh:minCount 1)
   [:urpx/referencesServiceTier       {:optional true} Ref]
   [:urpx/referencesServiceBracket    {:optional true} Ref]
   [:urpx/referencesTouPeriod         {:optional true} Ref]
   ;; Sub-entities
   ;; 0..N upstream (sh:minCount 0, no sh:maxCount); nothing in src/ reads it.
   [:urpx/hasApplicabilityGroup
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector ApplicabilityGroup]]
   [:urpx/hasApplicabilityRule
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    ;; Inline rule or a bare @id ref to one defined elsewhere in the document
    ;; (RDF merges a reference with its definition). Richer schema first so an
    ;; inline node decodes through ApplicabilityRule; ExclusiveRef rather than
    ;; Ref so a malformed inline node cannot slip through the reference arm.
    [:vector [:or ApplicabilityRule ExclusiveRef]]]
   [:urpx/hasCalculationMethod  {:optional true} CalculationMethod]
   [:urpx/hasDataSourceReference
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector DataSourceReference]]
   ;; 0..N upstream (no sh:maxCount). Accepted as one or many; price.clj reads
   ;; one through urpx.index/single, which refuses rather than guessing.
   [:urpx/hasMetricSpecification {:optional true}
    [:or MetricSpecification [:vector MetricSpecification]]]
   ;; 0..N upstream, same as on PriceSet: an alternative carries its own
   ;; subtotals, since which line items a summary names depends on which
   ;; alternative was selected.
   [:urpx/hasPriceSummary
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector [:or PriceSummary ExclusiveRef]]]
   [:urpx/hasPrice
    {:decode/urpx-jsonld single-or-vec->vec}
    [:vector Price]]])  ; required (sh:minCount 1)

(def PriceSetAlternatives
  "Top-level container for a customer-profile-driven price selection.
   Lives at Prices.hasPriceSetAlternatives. References one ProfileAlternatives
   (declared at PlanElements.hasProfileAlternatives) whose alternatives drive
   per-PriceSetAlternative choice."
  [:map
   [:jsonld/type [:= "urpx:PriceSetAlternatives"]]
   [:jsonld/id   {:optional true} :string]
   [:urpx/name             {:optional true} :string]
   [:urpx/priceMatchingKey {:optional true} :string]
   [:urpx/referencesProfileAlternatives Ref]
   [:urpx/hasPriceSetAlternative
    {:decode/urpx-jsonld single-or-vec->vec}
    [:vector PriceSetAlternative]]])

(def Prices
  "v0.2.0: gains optional `urpx:hasPriceSetAlternatives` for customer-profile-
   driven price selection. Each PriceSetAlternatives references a sibling
   ProfileAlternatives (declared at PlanElements.hasProfileAlternatives) and
   exposes one PriceSetAlternative per ProfileAlternative."
  [:map
   [:jsonld/type [:= "urpx:Prices"]]
   [:urpx/hasPrice    {:optional true} [:vector Price]]
   [:urpx/hasPriceSet {:optional true} [:vector PriceSet]]
   [:urpx/hasPriceSetAlternatives
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector PriceSetAlternatives]]])

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
   [:urpx/changeReason  Ref]
   [:urpx/changeDescription {:optional true} :string]
   [:urpx/description   {:optional true} :string]
   ;; sh:minCount 0 sh:maxCount 1 on both version shapes, and it was NEVER
   ;; required upstream: optional at v0.2.1, v0.3.0, v0.4.0, v0.5.0 and at
   ;; 95eb8e22. clj-urpx required it by its own choice, so a conformant
   ;; snapshot omitting it was rejected.
   [:urpx/effectiveDateTime {:optional true} DateTime]
   [:urpx/effectiveDate     {:optional true} Date]
   [:urpx/expirationDate    {:optional true} Date]
   [:urpx/expirationDateTime {:optional true} DateTime]
   [:urpx/datePublished     {:optional true} Date]
   [:urpx/dateTimePublished {:optional true} DateTime]
   [:urpx/dateApproved      {:optional true} Date]
   [:urpx/dateTimeApproved  {:optional true} DateTime]
   [:urpx/priceCurrency     {:optional true} :string]
   ;; 0..N upstream (sh:minCount 0, no sh:maxCount); nothing in src/ reads it.
   [:urpx/commodity
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector Ref]]
   [:urpx/serviceRole       Ref]
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
   ;; URPX Decisions #084 + #090: RPV-side pointers to optional Modifier(s)
   ;; and ModifierVersion(s). Both 0..None in SHACL — surfaced here so
   ;; coerce yields them as vectors of Ref maps.
   [:urpx/hasModifierOptions
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector Ref]]
   [:urpx/hasModifierVersionOptions
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector Ref]]
   [:urpx/observesHoliday
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector Ref]]
   ;; v0.5.1 coverage machinery, both sh:minCount 1 sh:maxCount 1 upstream.
   ;; STILL OPTIONAL, and no longer because of the corpus. Migration wave 6
   ;; finished and all 84 snapshots now carry both (40 forRatePlan + 44
   ;; forRatePlanModifier, 84 hasCoverageClaim), so tightening would reject
   ;; nothing there. It would reject every v0.2.x document, which has neither
   ;; term, and this library advertises v0.2.x acceptance. Requiring these ends
   ;; that promise; it is a contract decision, not a conformance one.
   [:urpx/forRatePlan {:optional true} Ref]
   [:urpx/hasCoverageClaim {:optional true} [:or CoverageClaim ExclusiveRef]]
   [:urpx/selfContained {:optional true} :boolean]
   [:urpx/hasPriceDefinitionReference
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector [:or PriceDefinitionReference ExclusiveRef]]]
   ;; v0.5.1 typed identity. hasIdentifier and hasLocationDataSet are 0..N
   ;; upstream; timezoneIdentifier is maxCount 1.
   [:urpx/hasIdentifier
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector [:or RatePlanIdentifier ExclusiveRef]]]
   [:urpx/hasLocationDataSet
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector Ref]]
   [:urpx/timezoneIdentifier {:optional true} :string]
   [:urpx/hasPlanElements PlanElements]
   [:urpx/hasPrices       Prices]])

(def RatePlan
  "v0.2.0: ratePlanUniqueIdentifier is required (replaces v0.1.x
   ratePlanIdentifier). RatePlan gains many optional date/metadata fields;
   we list the ones the tests / consumers care about."
  [:map
   [:jsonld/type [:= "urpx:RatePlan"]]
   [:jsonld/id {:optional true} :string]
   [:jsonld/context {:optional true} Context]
   [:urpx/ratePlanUniqueIdentifier :string]
   [:urpx/name :string]
   [:urpx/initialEffectiveDate Date]
   [:urpx/effectiveDate {:optional true} Date]
   [:urpx/expirationDate {:optional true} Date]
   [:urpx/timezoneIdentifier {:optional true} :string]
   [:urpx/priceCurrency {:optional true} :string]
   [:urpx/url           {:optional true} :string]
   [:urpx/version       {:optional true} :string]
   ;; v0.5.1 sh:minCount 0 on urpx-shacl:RatePlanShape. The v0.3.0 draft removed
   ;; this property outright; v0.5.1 re-admitted it as optional, so the nested
   ;; shape stays legal and an un-nested filing that states the link only as
   ;; forRatePlan on a sibling snapshot is legal too. NOT widened to 0..N yet:
   ;; SHACL sets no sh:maxCount, but urpx.index/nested-version and the callers
   ;; that go through it, urpx.price/plan-version, urpx.holidays/plan-calendar
   ;; and urpx.tz-inference/published-by, all read this as a single map, so a
   ;; vector here would
   ;; validate documents the resolver then silently misreads. Widening it means
   ;; rewriting those three readers first.
   ;; v0.5.1 typed identity. hasIdentifier and hasLocationDataSet are 0..N
   ;; upstream; timezoneIdentifier is maxCount 1.
   [:urpx/hasIdentifier
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector [:or RatePlanIdentifier ExclusiveRef]]]
   [:urpx/hasLocationDataSet
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector Ref]]
   ;; 0..N upstream: sh:minCount 0 and no sh:maxCount. Accepts a single nested
   ;; snapshot or several. Deliberately NOT normalized to a vector by a decoder:
   ;; every filing in the corpus nests exactly one, and forcing a vector would
   ;; change the coerced shape every consumer already reads. Readers go through
   ;; `urpx.index/nested-version`, which refuses to guess when there is more
   ;; than one rather than silently resolving against an arbitrary snapshot.
   [:urpx/hasRatePlanVersion {:optional true}
    [:or RatePlanVersion [:vector RatePlanVersion]]]])

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
   [:urpx/changeReason  Ref]
   [:urpx/changeDescription {:optional true} :string]
   [:urpx/description {:optional true} :string]
   ;; sh:minCount 0 sh:maxCount 1 on both version shapes, and it was NEVER
   ;; required upstream: optional at v0.2.1, v0.3.0, v0.4.0, v0.5.0 and at
   ;; 95eb8e22. clj-urpx required it by its own choice, so a conformant
   ;; snapshot omitting it was rejected.
   [:urpx/effectiveDateTime {:optional true} DateTime]
   [:urpx/effectiveDate {:optional true} Date]
   [:urpx/expirationDate {:optional true} Date]
   [:urpx/expirationDateTime {:optional true} DateTime]
   [:urpx/priceCurrency {:optional true} :string]
   ;; 0..N upstream (sh:minCount 0, no sh:maxCount); nothing in src/ reads it.
   [:urpx/commodity
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector Ref]]
   [:urpx/serviceRole Ref]
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
   ;; Optional per SHACL (sh:minCount 0): standalone urpx:RatePlanModifier
   ;; documents whose target linkage isn't yet expressed in-document (CPAU
   ;; E-2-G shape) carry no applicableToRatePlan and rely on
   ;; consumer-side composition.
   [:urpx/applicableToRatePlan
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector Ref]]
   [:urpx/applicableToRatePlanVersion
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector Ref]]
   ;; v0.5.1 coverage machinery, both sh:minCount 1 sh:maxCount 1 upstream.
   ;; STILL OPTIONAL, and no longer because of the corpus. Migration wave 6
   ;; finished and all 84 snapshots now carry both (40 forRatePlan + 44
   ;; forRatePlanModifier, 84 hasCoverageClaim), so tightening would reject
   ;; nothing there. It would reject every v0.2.x document, which has neither
   ;; term, and this library advertises v0.2.x acceptance. Requiring these ends
   ;; that promise; it is a contract decision, not a conformance one.
   [:urpx/forRatePlanModifier {:optional true} Ref]
   [:urpx/hasCoverageClaim {:optional true} [:or CoverageClaim ExclusiveRef]]
   [:urpx/selfContained {:optional true} :boolean]
   [:urpx/hasPriceDefinitionReference
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector [:or PriceDefinitionReference ExclusiveRef]]]
   ;; v0.5.1 typed identity. hasIdentifier and hasLocationDataSet are 0..N
   ;; upstream; timezoneIdentifier is maxCount 1.
   [:urpx/hasIdentifier
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector [:or RatePlanModifierIdentifier ExclusiveRef]]]
   [:urpx/hasLocationDataSet
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector Ref]]
   [:urpx/timezoneIdentifier {:optional true} :string]
   [:urpx/hasPlanElements PlanElements]
   [:urpx/hasPrices       Prices]])

(def RatePlanModifier
  "v0.2.0: modifierUniqueIdentifier is required (was per-version in v0.1.x).
   urpx:isOptional is gone."
  [:map
   [:jsonld/type [:= "urpx:RatePlanModifier"]]
   [:jsonld/id {:optional true} :string]
   [:jsonld/context {:optional true} Context]
   [:urpx/modifierUniqueIdentifier :string]
   [:urpx/name :string]
   [:urpx/initialEffectiveDate Date]
   [:urpx/timezoneIdentifier {:optional true} :string]
   [:urpx/priceCurrency      {:optional true} :string]
   [:urpx/url                {:optional true} :string]
   [:urpx/version            {:optional true} :string]
   ;; Optional for the same reason as RatePlan/hasRatePlanVersion above, and
   ;; likewise not yet widened to 0..N: urpx.holidays/plan-calendar,
   ;; urpx.tz-inference/published-by and urpx.price/plan-version read it as a
   ;; single map, the last through urpx.index/nested-version.
   ;; v0.5.1 typed identity. hasIdentifier and hasLocationDataSet are 0..N
   ;; upstream; timezoneIdentifier is maxCount 1.
   [:urpx/hasIdentifier
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector [:or RatePlanModifierIdentifier ExclusiveRef]]]
   [:urpx/hasLocationDataSet
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector Ref]]
   ;; 0..N upstream, same as RatePlan/hasRatePlanVersion above.
   [:urpx/hasRatePlanModifierVersion {:optional true}
    [:or RatePlanModifierVersion [:vector RatePlanModifierVersion]]]])

;; ---------------------------------------------------------------------------
;; Top-level wrappers carrying ontology-version metadata
;;
;; urpx:URPXDocument (v0.2.x) was renamed urpx:URPXPackage in v0.3.0. Both are
;; modeled, so a corpus part-way through a wave-by-wave migration validates
;; either way rather than having to switch over all at once.
;; ---------------------------------------------------------------------------

(def URPXDocument
  "URPX v0.2.0 Decision #103: every urpx:URPXDocument carries a required
   urpx:targetURPXVersion (xsd:anyURI) declaring the ontology version the
   document was authored against. URPXDocumentShape (SHACL) lists many
   optional metadata fields (dateCreated, version, hasRatePlan,
   hasRatePlanModifier, hasTariffPublication, …) — we model only the
   required field plus the headline payload pointers; additional metadata
   flows through unvalidated.

   Documents that aren't URPXDocument-wrapped (raw RatePlan, raw
   RatePlanModifier, or a JSON-LD `@graph` doc with no urpx:URPXDocument
   @type) bypass this shape — its target is the v0.2.0 wrapper class
   itself, not every URPX serialization."
  [:map
   [:jsonld/type [:= "urpx:URPXDocument"]]
   [:jsonld/id {:optional true} :string]
   [:jsonld/context {:optional true} Context]
   [:urpx/targetURPXVersion :string]
   [:urpx/name              {:optional true} :string]
   [:urpx/description       {:optional true} :string]
   [:urpx/version           {:optional true} :string]
   [:urpx/url               {:optional true} :string]
   [:urpx/timezoneIdentifier {:optional true} :string]
   [:urpx/priceCurrency     {:optional true} :string]
   [:urpx/dateCreated       {:optional true} Date]
   [:urpx/dateTimeCreated   {:optional true} DateTime]
   [:urpx/effectiveDate     {:optional true} Date]
   [:urpx/effectiveDateTime {:optional true} DateTime]
   [:urpx/expirationDate    {:optional true} Date]
   [:urpx/expirationDateTime {:optional true} DateTime]
   ;; Payload pointers — embedded entity (full RatePlan / RatePlanModifier)
   ;; or a Ref by @id. Inner entities should be validated against their own
   ;; schemas separately; here we only check the wrapper-level metadata.
   [:urpx/hasRatePlan
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector :map]]
   [:urpx/hasRatePlanModifier
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector :map]]
   [:urpx/hasTariffPublication
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector :map]]])

(def URPXPackage
  "v0.3.0 renamed urpx:URPXDocument to urpx:URPXPackage; v0.5.1 keeps the
   class. urpx:targetURPXVersion stays required. Against URPXDocument the
   shape drops urpx:hasTariffPublication and adds urpx:packages, the general
   containment pointer (range: RatePlan | RatePlanModifier | RatePlanVersion |
   RatePlanModifierVersion | PricingModel | TariffFiling | SourcePublication),
   while retaining urpx:hasRatePlan and urpx:hasRatePlanModifier. Read from
   urpx-shacl:URPXPackageShape at urpx-org/urpx 95eb8e22 (v0.5.1-candidate).

   As with URPXDocument, only the required field and the payload pointers are
   modeled; other wrapper metadata flows through unvalidated."
  [:map
   [:jsonld/type [:= "urpx:URPXPackage"]]
   [:jsonld/id {:optional true} :string]
   [:jsonld/context {:optional true} Context]
   [:urpx/targetURPXVersion :string]
   [:urpx/name              {:optional true} :string]
   [:urpx/description       {:optional true} :string]
   [:urpx/version           {:optional true} :string]
   [:urpx/url               {:optional true} :string]
   [:urpx/timezoneIdentifier {:optional true} :string]
   [:urpx/priceCurrency     {:optional true} :string]
   [:urpx/dateCreated       {:optional true} Date]
   [:urpx/dateTimeCreated   {:optional true} DateTime]
   [:urpx/effectiveDate     {:optional true} Date]
   [:urpx/effectiveDateTime {:optional true} DateTime]
   [:urpx/expirationDate    {:optional true} Date]
   [:urpx/expirationDateTime {:optional true} DateTime]
   ;; Payload pointers: embedded entity or a Ref by @id, as on URPXDocument.
   [:urpx/hasRatePlan
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector :map]]
   [:urpx/hasRatePlanModifier
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector :map]]
   [:urpx/packages
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector :map]]])

;; ---------------------------------------------------------------------------
;; Publications and filings
;;
;; v0.3.0 split the v0.2.x urpx:TariffPublication into urpx:SourcePublication,
;; the external published source URPX cites, and urpx:TariffFiling, the URPX
;; content unit. Both shapes are sh:closed upstream; clj-urpx does not enforce
;; closedness, so unmodeled fields flow through.
;; ---------------------------------------------------------------------------

(def SourcePublication
  "An external published source a URPX fact cites: a tariff book, a statement,
   a market price feed. Neither a super- nor a subclass of urpx:URPXPackage;
   they are siblings.

   Dual-accept: the @type admits both the v0.5.1 urpx:SourcePublication and the
   v0.2.x urpx:TariffPublication, because the corpus migrates utility by
   utility and carries both at once. That is also why this schema has no DSL
   builder: `urpx.dsl/make-builder` requires a `[:= \"...\"]` type entry and
   throws on an `:enum`.

   urpx-shacl:SourcePublicationShape has 23 paths and requires nothing. The
   entries below it are v0.2.1 TariffPublicationShape paths that v0.5.1 drops;
   the vendored PG&E fixtures carry hasRatePlan and hasRatePlanModifier, so
   omitting them would break the suite."
  [:map
   [:jsonld/type [:enum "urpx:SourcePublication" "urpx:TariffPublication"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/name             {:optional true} :string]
   [:urpx/description      {:optional true} :string]
   [:urpx/version          {:optional true} :string]
   [:urpx/url              {:optional true} :string]
   [:urpx/filingNumber     {:optional true} :string]
   [:urpx/issuingAuthority {:optional true} :string]
   [:urpx/pageReference    {:optional true} :string]
   [:urpx/publicationType  {:optional true} SourcePublicationType]
   [:urpx/publishedBy      {:optional true} [:or Organization ExclusiveRef]]
   [:urpx/dateApproved      {:optional true} Date]
   [:urpx/dateTimeApproved  {:optional true} DateTime]
   [:urpx/dateCreated       {:optional true} Date]
   [:urpx/dateTimeCreated   {:optional true} DateTime]
   [:urpx/dateModified      {:optional true} Date]
   [:urpx/dateTimeModified  {:optional true} DateTime]
   [:urpx/datePublished     {:optional true} Date]
   [:urpx/dateTimePublished {:optional true} DateTime]
   [:urpx/dateReceived      {:optional true} Date]
   [:urpx/dateTimeReceived  {:optional true} DateTime]
   [:urpx/effectiveDate     {:optional true} Date]
   [:urpx/effectiveDateTime {:optional true} DateTime]
   [:urpx/expirationDate    {:optional true} Date]
   [:urpx/expirationDateTime {:optional true} DateTime]
   ;; v0.2.1 TariffPublicationShape paths that v0.5.1 drops. Accept-if-present.
   [:urpx/priceCurrency        {:optional true} :string]
   [:urpx/timezoneIdentifier   {:optional true} :string]
   [:urpx/expectedEndDate      {:optional true} Date]
   [:urpx/expectedEndDateTime  {:optional true} DateTime]
   [:urpx/hasRatePlan
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector :map]]
   [:urpx/hasRatePlanModifier
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector :map]]
   [:urpx/hasDataSourceReference
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector :map]]])

(def TariffFiling
  "The URPX content unit of the v0.3.0 publication split, the half that carries
   pricing rather than citing a source. v0.5.1 only; there is no v0.2.x
   equivalent to dual-accept.

   urpx:containsPricingModel is the shape's only required property and is
   unbounded. urpx:PricingModel is not modeled in clj-urpx, so it and the other
   entity-valued slots stay loose maps rather than typed schemas."
  [:map
   [:jsonld/type [:= "urpx:TariffFiling"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/containsPricingModel
    {:decode/urpx-jsonld single-or-vec->vec}
    [:vector :map]]
   [:urpx/name        {:optional true} :string]
   [:urpx/description {:optional true} :string]
   [:urpx/publishedBy {:optional true} [:or Organization ExclusiveRef]]
   [:urpx/effectiveDate      {:optional true} Date]
   [:urpx/effectiveDateTime  {:optional true} DateTime]
   [:urpx/expirationDate     {:optional true} Date]
   [:urpx/expirationDateTime {:optional true} DateTime]
   [:urpx/dateModified       {:optional true} Date]
   [:urpx/dateTimeModified   {:optional true} DateTime]
   [:urpx/cancelledOn         {:optional true} Date]
   [:urpx/cancelledOnDateTime {:optional true} DateTime]
   [:urpx/cancelledBy {:optional true} :map]
   [:urpx/replacedBy  {:optional true} :map]
   [:urpx/replaces    {:optional true} :map]
   [:urpx/supersedes  {:optional true} :map]
   [:urpx/cancelsPriceDefinition
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector :map]]
   [:urpx/documentedBy
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector :map]]
   [:urpx/hasGeneralRule
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector :map]]])

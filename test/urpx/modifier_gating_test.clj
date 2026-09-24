(ns urpx.modifier-gating-test
  "Month and season applicability on a urpx:RatePlanModifier's
  PriceDefinitions.

  The base path gates a definition on three things, `pd-active-at-tou?`,
  `pd-active-in-month?` and `pd-active-in-season?`. The modifier path gated on
  the first only, so `urpx:hasApplicabilityRule` month constraints and
  `urpx:appliesInSeason` were inert there and a modifier discriminating two
  cells by either surfaced BOTH at every instant. A modifier discriminating by
  `urpx:touPeriodNumber` gated correctly, which is why this went unseen.

  Reported from the corpus side: SMUD's NEM1 export credit is that shape over
  smud:fixed, whose two energy definitions are told apart by month rule rather
  than by TOU period, so only the TOU-discriminated cells were buildable.

  No vendored fixture exercises it. Checked by walking all 23: not one
  modifier PriceDefinition or PriceSummary carries `appliesInSeason` or a month
  rule, so every case here is constructed and the corpus cannot move."
  (:require [clojure.test :refer [deftest is testing]]
            [urpx.price :as price])
  (:import [java.time LocalDate LocalDateTime ZoneId ZonedDateTime]))

(def ^:private la (ZoneId/of "America/Los_Angeles"))

(defn- at [y m d] (ZonedDateTime/of y m d 17 0 0 0 la))

(def ^:private summer
  {:jsonld/type "urpx:Season" :jsonld/id "test:season-summer"
   :urpx/seasonName "Summer" :urpx/yearlyRecurrence true :urpx/month [6 7 8 9]})

(def ^:private winter
  {:jsonld/type "urpx:Season" :jsonld/id "test:season-winter"
   :urpx/seasonName "Winter" :urpx/yearlyRecurrence true :urpx/month [10 11 12 1 2 3 4 5]})

(def ^:private metric-input
  {:jsonld/type "urpx:MetricInput" :jsonld/id "test:mi-energy" :urpx/name "kWh"
   :urpx/metricInputType {:jsonld/id "urpx:profileMetricInput"}
   :urpx/hasMetricSpecification
   {:jsonld/type "urpx:MetricSpecification"
    :urpx/measurementKind {:jsonld/id "urpx:energy"}
    :urpx/unitOfMeasure   {:jsonld/id "urpx:kWh"}}})

(defn- modifier
  "A coerced urpx:RatePlanModifier carrying `pds` and one Price per definition.
  `seasons` lets a test omit them, which is how a modifier that names the base
  plan's Seasons rather than declaring its own is built."
  [pds & {:keys [seasons tiers tier-numbers] :or {seasons [summer winter]}}]
  {:jsonld/type "urpx:RatePlanModifier"
   :jsonld/id   "test:mod"
   :urpx/timezoneIdentifier "America/Los_Angeles"
   :urpx/name "Gating test modifier"
   :urpx/initialEffectiveDate (LocalDate/of 2025 1 1)
   :urpx/hasRatePlanModifierVersion
   {:jsonld/type "urpx:RatePlanModifierVersion"
    :jsonld/id   "test:mod-v1"
    :urpx/forRatePlanModifier {:jsonld/id "test:mod"}
    ;; On the VERSION, which is where modifier-applies-to? reads it.
    :urpx/applicableToRatePlan [{:jsonld/id "test:base"}]
    :urpx/changeReason {:jsonld/id "urpx:rateCase"}
    :urpx/hasCoverageClaim {:jsonld/type "urpx:CoverageClaim"
                            :urpx/completenessResult {:jsonld/id "urpx:complete"}}
    :urpx/serviceRole {:jsonld/id "urpx:bundledServiceRole"}
    :urpx/effectiveDateTime (LocalDateTime/of 2025 1 1 0 0)
    :urpx/priceCurrency "USD"
    :urpx/hasPlanElements
    (cond-> {:jsonld/type "urpx:PlanElements"
             :urpx/hasMetricInput [metric-input]
             :urpx/hasBillingModel
             {:jsonld/type "urpx:BillingModel"
              :urpx/hasLedger [{:jsonld/type "urpx:Ledger" :jsonld/id "test:ledger-adj"
                                :urpx/name "Adjustment"
                                :urpx/ledgerType {:jsonld/id "urpx:bundledLedger"}
                                :urpx/referencesMetricInput [{:jsonld/id "test:mi-energy"}]}]
              :urpx/hasPriceDefinition pds}}
      (seq seasons) (assoc :urpx/hasSeasons seasons)
      (seq tiers)   (assoc-in [:urpx/hasBillingModel :urpx/hasServiceTiers] tiers))
    :urpx/hasPrices
    {:jsonld/type "urpx:Prices"
     :urpx/hasPrice
     (vec (map-indexed (fn [i pd]
                         (merge
                          {:jsonld/type "urpx:Price"
                          :jsonld/id   (str (:jsonld/id pd) "-price")
                          :urpx/name   (:urpx/name pd)
                          :urpx/unitPrice (BigDecimal. (str "0.0" (inc i)))
                          :urpx/referencesPriceDefinition {:jsonld/id (:jsonld/id pd)}}
                         (when-let [n (get tier-numbers i)]
                           {:urpx/serviceTierNumber n})))
                       pds))}}})

(defn- base-plan
  "A minimal urpx:RatePlan for the composed cases, declaring both Seasons so a
  modifier can name them without declaring its own."
  []
  {:jsonld/type "urpx:RatePlan"
   :jsonld/id   "test:base"
   :urpx/timezoneIdentifier "America/Los_Angeles"
   :urpx/ratePlanUniqueIdentifier "TEST-BASE"
   :urpx/name "Gating test base plan"
   :urpx/initialEffectiveDate (LocalDate/of 2025 1 1)
   :urpx/hasRatePlanVersion
   {:jsonld/type "urpx:RatePlanVersion"
    :urpx/forRatePlan {:jsonld/id "test:base"}
    :urpx/changeReason {:jsonld/id "urpx:rateCase"}
    :urpx/hasCoverageClaim {:jsonld/type "urpx:CoverageClaim"
                            :urpx/completenessResult {:jsonld/id "urpx:complete"}}
    :urpx/serviceRole {:jsonld/id "urpx:bundledServiceRole"}
    :urpx/effectiveDateTime (LocalDateTime/of 2025 1 1 0 0)
    :urpx/priceCurrency "USD"
    :urpx/hasPlanElements
    {:jsonld/type "urpx:PlanElements"
     :urpx/hasSeasons [summer winter]
     :urpx/hasMetricInput [metric-input]
     :urpx/hasBillingModel
     {:jsonld/type "urpx:BillingModel"
      :urpx/hasLedger [{:jsonld/type "urpx:Ledger" :jsonld/id "test:base-ledger"
                        :urpx/name "Base"
                        :urpx/ledgerType {:jsonld/id "urpx:supplyLedger"}
                        :urpx/referencesMetricInput [{:jsonld/id "test:mi-energy"}]}]
      :urpx/hasPriceDefinition
      [{:jsonld/type "urpx:PriceDefinition" :jsonld/id "test:base-pd" :urpx/name "Base energy"
        :urpx/referencesLedger {:jsonld/id "test:base-ledger"}}]}}
    :urpx/hasPrices
    {:jsonld/type "urpx:Prices"
     :urpx/hasPrice [{:jsonld/type "urpx:Price" :jsonld/id "test:base-price"
                      :urpx/name "Base energy" :urpx/unitPrice 0.10M
                      :urpx/referencesPriceDefinition {:jsonld/id "test:base-pd"}}]}}})

(defn- pd
  [id name- & {:keys [season months]}]
  (cond-> {:jsonld/type "urpx:PriceDefinition" :jsonld/id id :urpx/name name-
           :urpx/referencesLedger {:jsonld/id "test:ledger-adj"}}
    season (assoc :urpx/appliesInSeason [{:jsonld/id season}])
    months (assoc :urpx/hasApplicabilityRule
                  [{:jsonld/type "urpx:ApplicabilityRule"
                    :jsonld/id (str id "-rule")
                    :urpx/month months}])))

(defn- pd-ids
  "The PriceDefinition @ids that surface, standalone."
  [m inst]
  (->> (:urpx.resolved/ledgers (price/resolve-modifier-prices m inst))
       (mapcat :urpx.resolved/tiers)
       (map :urpx.resolved/price-definition-id)
       sort vec))

(defn- composed-pd-ids
  "The modifier's PriceDefinition @ids that surface in a composition."
  [plan m inst]
  (->> (:urpx.resolved/ledgers (price/resolve-prices-with-modifiers plan [m] inst))
       (mapcat :urpx.resolved/tiers)
       (keep :urpx.resolved/price-definition-id)
       (filter #(re-find #"test:adj" %))
       sort vec))

;; ---------------------------------------------------------------------------
;; Season
;; ---------------------------------------------------------------------------

(deftest a-modifier-definition-scoped-to-a-season-applies-only-there
  (testing "one definition per season, the shape SMUD's smud:fixed uses. Both
            surfaced at every instant before this gate reached the modifier
            path, so a consumer summing the ledger counted a winter credit in
            July."
    (let [m (modifier [(pd "test:adj-summer" "Summer adjustment" :season "test:season-summer")
                       (pd "test:adj-winter" "Winter adjustment" :season "test:season-winter")])]
      (is (= ["test:adj-summer"] (pd-ids m (at 2026 7 15))))
      (is (= ["test:adj-winter"] (pd-ids m (at 2026 12 15))))))

  (testing "and the same holds in a composition, where the gate is the one place
            it could have been applied and was not"
    (let [m (modifier [(pd "test:adj-summer" "Summer adjustment" :season "test:season-summer")
                       (pd "test:adj-winter" "Winter adjustment" :season "test:season-winter")])]
      (is (= ["test:adj-summer"] (composed-pd-ids (base-plan) m (at 2026 7 15))))
      (is (= ["test:adj-winter"] (composed-pd-ids (base-plan) m (at 2026 12 15)))))))

(deftest a-modifier-definition-naming-the-base-plans-season-resolves-composed
  (testing "PG&E's E-TOU-C baseline-tier modifier declares no urpx:Season of its
            own and names the base plan's. Composed, by-id spans both documents
            and the gate evaluates. Standing alone no Season is reachable at
            all, so the gate is UNEVALUATED rather than failed, and the
            definition surfaces.

            Surfacing it is the deliberate choice (URPX-8yp). Excluding it would
            silently drop every seasonal definition from a modifier resolved
            without its base plan, which is precisely the defect URPX-2a6 fixed
            for summary members when this path passed empty season indexes.
            Reinstating it by a different route would be worse than a price the
            caller can see and reject, and standalone modifier resolution is
            already documented as surfacing every applicable price for the
            caller to select from."
    (let [m (modifier [(pd "test:adj-summer" "Summer adjustment" :season "test:season-summer")]
                      :seasons [])]
      (is (= ["test:adj-summer"] (pd-ids m (at 2026 7 15)))
          "standalone in season: surfaces, gate unevaluated")
      (is (= ["test:adj-summer"] (pd-ids m (at 2026 12 15)))
          "standalone OUT of season: also surfaces, because nothing could say so")
      (is (= ["test:adj-summer"] (composed-pd-ids (base-plan) m (at 2026 7 15))))
      (is (= [] (composed-pd-ids (base-plan) m (at 2026 12 15)))
          "composed and out of season: now evaluable, so gated and not surfaced"))))

;; ---------------------------------------------------------------------------
;; Month applicability rules
;; ---------------------------------------------------------------------------

(deftest a-modifier-definition-with-a-month-rule-applies-only-in-those-months
  (testing "urpx:hasApplicabilityRule with urpx:month is the other
            discriminator, and the one smud:fixed uses to tell its two energy
            definitions apart."
    (let [m (modifier [(pd "test:adj-h1" "First half" :months [1 2 3 4 5 6])
                       (pd "test:adj-h2" "Second half" :months [7 8 9 10 11 12])])]
      (is (= ["test:adj-h1"] (pd-ids m (at 2026 3 15))))
      (is (= ["test:adj-h2"] (pd-ids m (at 2026 9 15))))
      (is (= ["test:adj-h1"] (composed-pd-ids (base-plan) m (at 2026 3 15))))
      (is (= ["test:adj-h2"] (composed-pd-ids (base-plan) m (at 2026 9 15))))))

  (testing "a rule stated as a bare @id reference is dereferenced, exactly as on
            the base path: without that step it carries no urpx:month, reads as
            permissive, and the gate silently stops applying"
    (let [rule {:jsonld/type "urpx:ApplicabilityRule" :jsonld/id "test:rule-h1"
                :urpx/month [1 2 3 4 5 6]}
          m    (-> (modifier [(assoc (pd "test:adj-h1" "First half")
                                     :urpx/hasApplicabilityRule [{:jsonld/id "test:rule-h1"}])])
                   (assoc-in [:urpx/hasRatePlanModifierVersion :urpx/hasPlanElements
                              :urpx/hasApplicabilityRule]
                             [rule]))]
      (is (= ["test:adj-h1"] (pd-ids m (at 2026 3 15))))
      (is (= [] (pd-ids m (at 2026 9 15)))))))

;; ---------------------------------------------------------------------------
;; What must not change
;; ---------------------------------------------------------------------------

(deftest an-ungated-definition-still-surfaces
  (testing "every vendored modifier, since none carries either property. TOU
            gating stays conditional on there being a period to compare
            against: a modifier with no TOUSchedule of its own, resolved with no
            base period, must still surface its definitions."
    (let [m (modifier [(pd "test:adj-flat" "Flat adjustment")])]
      (is (= ["test:adj-flat"] (pd-ids m (at 2026 7 15))))
      (is (= ["test:adj-flat"] (pd-ids m (at 2026 12 15))))
      (is (= ["test:adj-flat"] (composed-pd-ids (base-plan) m (at 2026 7 15)))))))

(deftest a-summary-member-scoped-to-a-season-is-gated-not-dropped
  (testing "standalone resolution built its summary-member gate with EMPTY rule
            and season indexes, so pd-active-in-season? could resolve no Season
            for any ref and a member carrying urpx:appliesInSeason was always
            inactive. That is the opposite failure from the one above: silently
            dropped from the subtotal rather than always counted. The member is
            now judged against the modifier's own seasons.

            Measured through the subtotal: a summary over both seasonal members
            is the July member's price in July and the December member's in
            December, where before it was neither."
    (let [m (-> (modifier [(pd "test:adj-summer" "Summer adjustment" :season "test:season-summer")
                           (pd "test:adj-winter" "Winter adjustment" :season "test:season-winter")
                           (pd "test:adj-disc" "Discount")])
                (update-in [:urpx/hasRatePlanModifierVersion :urpx/hasPrices :urpx/hasPrice]
                           (fn [ps]
                             (-> (vec (remove #(= "test:adj-disc-price" (:jsonld/id %)) ps))
                                 (conj {:jsonld/type "urpx:PriceSummary"
                                        :jsonld/id "test:summary"
                                        :urpx/name "Seasonal subtotal"
                                        :urpx/includesPrice
                                        [{:jsonld/id "test:adj-summer-price"}
                                         {:jsonld/id "test:adj-winter-price"}]})
                                 (conj {:jsonld/type "urpx:Price"
                                        :jsonld/id "test:adj-disc-price"
                                        :urpx/name "Discount"
                                        :urpx/multiplier -1M
                                        :urpx/basedOnPrice {:jsonld/id "test:summary"}
                                        :urpx/referencesPriceDefinition {:jsonld/id "test:adj-disc"}})))))
          value (fn [inst]
                  (->> (:urpx.resolved/ledgers (price/resolve-modifier-prices m inst))
                       (mapcat :urpx.resolved/tiers)
                       (filter #(= "test:adj-disc-price" (:urpx.resolved/price-id %)))
                       first))]
      (is (= -0.01M (:urpx.resolved/unit-price (value (at 2026 7 15))))
          "July: the summer member only")
      (is (= -0.02M (:urpx.resolved/unit-price (value (at 2026 12 15))))
          "December: the winter member only")
      (is (nil? (:urpx.resolved/unvaluable (value (at 2026 7 15))))
          "and it is valued rather than refused"))))

;; ---------------------------------------------------------------------------
;; A modifier's OWN urpx:ServiceTiers, which this path used to drop entirely
;; ---------------------------------------------------------------------------

(defn- bracket
  [id upper & {:keys [season alternative]}]
  (cond-> {:jsonld/type "urpx:ServiceBracket" :jsonld/id id
           :urpx/lowerBound 0M
           :urpx/lowerBoundOperator {:jsonld/id "urpx:greaterThanOrEqualOperator"}
           :urpx/upperBound upper
           :urpx/upperBoundOperator {:jsonld/id "urpx:lessThanOrEqualOperator"}}
    season      (assoc :urpx/appliesInSeason [{:jsonld/id season}])
    alternative (assoc :urpx/referencesProfileAlternative {:jsonld/id alternative})))

(defn- container
  "One urpx:ServiceTiers holding one tier numbered 1."
  [id brackets]
  {:jsonld/type "urpx:ServiceTiers" :jsonld/id id
   :urpx/referencesMetricInput [{:jsonld/id "test:mi-energy"}]
   :urpx/hasServiceTier
   [{:jsonld/type "urpx:ServiceTier" :jsonld/id (str id "-t1")
     :urpx/serviceTierNumber 1 :urpx/name "Tier 1"
     :urpx/hasServiceBracket brackets}]})

(defn- tier-1
  "The resolved tier-1 entry from the modifier, standalone.

  Deliberately calls the 2-arity, so these cases resolve with no caller input at
  all. The 3-arity that takes opts is exercised separately, in the URPX-crk
  cases at the end of this namespace."
  [m inst]
  (->> (:urpx.resolved/ledgers (price/resolve-modifier-prices m inst))
       (mapcat :urpx.resolved/tiers)
       (filter :urpx.resolved/tier-number)
       first))

(defn- composed-tier-1
  "The modifier's resolved tier-1 entry in a composition, where opts reach it."
  [plan m inst & [opts]]
  (->> (:urpx.resolved/ledgers (price/resolve-prices-with-modifiers plan [m] inst opts))
       (mapcat :urpx.resolved/tiers)
       (filter #(and (:urpx.resolved/tier-number %)
                     (re-find #"test:adj" (str (:urpx.resolved/price-definition-id %)))))
       first))

(deftest a-modifiers-own-tiers-carry-their-number-and-bounds
  (testing "URPX-sr4. This path built its entries from price-fields alone and
            never called tier-info, so a modifier holding its own
            urpx:ServiceTiers surfaced its rungs UNTIERED: no
            :urpx.resolved/tier-number and no bounds, however the tiers were
            shaped. PG&E's E-TOU-C baseline-allowance overlay is that shape, and
            so is any filing that puts its ladder in a modifier."
    (let [m (modifier [(pd "test:adj-t1" "Tier 1 adjustment")]
                      :tiers [(container "test:c" [(bracket "test:b" 13.5M)])]
                      :tier-numbers {0 1})
          t (tier-1 m (at 2026 7 15))]
      (is (= 1 (:urpx.resolved/tier-number t)))
      (is (= 0M (:urpx.resolved/tier-lower-bound t)))
      (is (= 13.5M (:urpx.resolved/tier-upper-bound t)))
      (is (= "Tier 1" (:urpx.resolved/tier-name t))))))

(deftest a-modifiers-block-ladder-is-reduced-not-summed
  (testing "why the missing tier number was a wrong NUMBER rather than a missing
            field. marginal-unit-rate reduces a block ladder to its marginal
            rung by :urpx.resolved/tier-number and sums everything without one,
            so a modifier's three-rung ladder was added together: 0.01 plus 0.02
            plus 0.03. With the numbers present only the lowest rung counts."
    (let [tiers [{:jsonld/type "urpx:ServiceTiers" :jsonld/id "test:c"
                  :urpx/referencesMetricInput [{:jsonld/id "test:mi-energy"}]
                  :urpx/hasServiceTier
                  (vec (for [n [1 2 3]]
                         {:jsonld/type "urpx:ServiceTier" :jsonld/id (str "test:c-t" n)
                          :urpx/serviceTierNumber n :urpx/name (str "Tier " n)
                          :urpx/hasServiceBracket
                          [(bracket (str "test:b" n) (* 100M n))]}))}]
          m (modifier [(pd "test:adj-t1" "Rung 1")
                       (pd "test:adj-t2" "Rung 2")
                       (pd "test:adj-t3" "Rung 3")]
                      :tiers tiers :tier-numbers {0 1, 1 2, 2 3})
          r (price/resolve-modifier-prices m (at 2026 7 15))
          ts (->> (:urpx.resolved/ledgers r) (mapcat :urpx.resolved/tiers)
                  (filter :urpx.resolved/tier-number))]
      (is (= [1 2 3] (sort (map :urpx.resolved/tier-number ts))))
      (is (= [100M 200M 300M] (sort (map :urpx.resolved/tier-upper-bound ts))))
      (testing "and the ledger is bundled rather than energy-measured here, so
                the reduction is asserted on the tiers themselves rather than
                through a per-kWh rate this ledger does not carry"
        (is (= 1 (count (filter #(= 1 (:urpx.resolved/tier-number %)) ts))))))))

(deftest a-modifiers-brackets-select-by-season
  (testing "bracket selection reaches a modifier now, so a tier carrying a
            summer bracket and a winter bracket states different bounds in each.
            The modifier declares its own Seasons here; one naming the base
            plan's resolves through the composition index instead."
    (let [m (modifier [(pd "test:adj-t1" "Tier 1 adjustment")]
                      :tiers [(container "test:c"
                                         [(bracket "test:b-s" 13.5M :season "test:season-summer")
                                          (bracket "test:b-w" 11.0M :season "test:season-winter")])]
                      :tier-numbers {0 1})]
      (is (= 13.5M (:urpx.resolved/tier-upper-bound (tier-1 m (at 2026 7 15)))))
      (is (= 11.0M (:urpx.resolved/tier-upper-bound (tier-1 m (at 2026 12 15))))))))

(deftest a-modifiers-brackets-withhold-when-no-cell-is-named
  (testing "the overlay shape: brackets scoped to cells of a ProfileAlternatives
            dimension. With no cell named the bounds are withheld and marked,
            and the rung keeps its number and unit price, so a caller learns the
            ladder exists and that its bounds are its own to determine."
    (let [m (-> (modifier [(pd "test:adj-t1" "Tier 1 adjustment")]
                          :tiers [(container "test:c"
                                             [(bracket "test:b-care" 13.5M :alternative "test:pa-care")
                                              (bracket "test:b-std" 11.0M :alternative "test:pa-std")])]
                          :tier-numbers {0 1})
                (update-in [:urpx/hasRatePlanModifierVersion :urpx/hasPlanElements]
                           assoc :urpx/hasProfileAlternatives
                           [{:jsonld/type "urpx:ProfileAlternatives" :jsonld/id "test:pas"
                             :urpx/hasProfileAlternative
                             [{:jsonld/type "urpx:ProfileAlternative" :jsonld/id "test:pa-care"}
                              {:jsonld/type "urpx:ProfileAlternative" :jsonld/id "test:pa-std"}]}]))
          none (composed-tier-1 (base-plan) m (at 2026 7 15))
          care (composed-tier-1 (base-plan) m (at 2026 7 15)
                                {:profile-alternatives {"test:pas" "test:pa-care"}})]
      (is (= 1 (:urpx.resolved/tier-number none)))
      (is (nil? (:urpx.resolved/tier-upper-bound none)))
      (is (= :alternative-unselected
             (:reason (:urpx.resolved/tier-bounds-unresolved none))))
      (is (= 13.5M (:urpx.resolved/tier-upper-bound care)))
      (is (nil? (:urpx.resolved/tier-bounds-unresolved care)))
      (testing "and standalone with no selection the bounds are withheld the same
                way. They are determinable there since URPX-crk gave
                resolve-modifier-prices an opts arity; the case that a selection
                reaches it is a-cell-selection-reaches-a-modifier-resolved-alone."
        (is (= :alternative-unselected
               (:reason (:urpx.resolved/tier-bounds-unresolved
                         (tier-1 m (at 2026 7 15))))))))))

(deftest a-modifier-without-tiers-is-unchanged
  (testing "every vendored modifier, none of which carries its own
            urpx:ServiceTiers. tier-info returns the interval and nothing else
            when the Price names no tier number, which is what this path
            produced before sr4 and must still produce."
    (let [m (modifier [(pd "test:adj-flat" "Flat adjustment")])
          t (->> (:urpx.resolved/ledgers (price/resolve-modifier-prices m (at 2026 7 15)))
                 (mapcat :urpx.resolved/tiers) first)]
      (is (= "test:adj-flat" (:urpx.resolved/price-definition-id t)))
      (is (nil? (:urpx.resolved/tier-number t)))
      (is (nil? (:urpx.resolved/tier-upper-bound t)))
      (is (nil? (:urpx.resolved/tier-bounds-unresolved t))))))

;; ---------------------------------------------------------------------------
;; Caller inputs reaching a modifier resolved on its own (URPX-crk)
;; ---------------------------------------------------------------------------

(defn- tou-modifier
  "A modifier carrying its own urpx:TOUSchedule whose peak bracket applies on
  NON-holidays only, plus an off-peak bracket for holidays. One PriceDefinition
  per period, so which one surfaces says which period resolved."
  []
  (-> (modifier [(assoc (pd "test:adj-peak" "Peak") :urpx/touPeriodNumber 1)
                 (assoc (pd "test:adj-offpeak" "Off-peak") :urpx/touPeriodNumber 2)])
      (assoc-in [:urpx/hasRatePlanModifierVersion :urpx/hasPlanElements
                 :urpx/hasBillingModel :urpx/hasTOUSchedule]
                [{:jsonld/type "urpx:TOUSchedule" :jsonld/id "test:tou"
                  :urpx/name "Own schedule"
                  :urpx/referencesMetricInput [{:jsonld/id "test:mi-energy"}]
                  :urpx/hasTOUTier
                  [{:jsonld/type "urpx:TOUTier" :urpx/touTierNumber 1
                    :urpx/hasTOUPeriod
                    [{:jsonld/type "urpx:TOUPeriod" :jsonld/id "test:p-peak"
                      :urpx/name "Peak" :urpx/touPeriodNumber 1
                      :urpx/hasTimeBracket
                      [{:jsonld/type "urpx:TimeBracket"
                        :urpx/appliesInSeason [{:jsonld/id "test:season-summer"}]
                        :urpx/dayType {:jsonld/id "urpx:allDays"}
                        :urpx/includeHolidays false
                        :urpx/includeNonHolidays true
                        :urpx/startTime (java.time.LocalTime/of 16 0)
                        :urpx/endTime   (java.time.LocalTime/of 21 0)}]}
                     {:jsonld/type "urpx:TOUPeriod" :jsonld/id "test:p-off"
                      :urpx/name "Off-peak" :urpx/touPeriodNumber 2
                      :urpx/hasTimeBracket
                      [{:jsonld/type "urpx:TimeBracket"
                        :urpx/appliesInSeason [{:jsonld/id "test:season-summer"}]
                        :urpx/dayType {:jsonld/id "urpx:allDays"}
                        :urpx/includeHolidays true
                        :urpx/includeNonHolidays false
                        :urpx/startTime (java.time.LocalTime/of 16 0)
                        :urpx/endTime   (java.time.LocalTime/of 21 0)}]}]}]}])))

(deftest a-holiday-predicate-reaches-a-modifier-resolved-alone
  (testing "URPX-crk, and the half that was a WRONG number rather than a
            withheld one. This path defaults the holiday predicate to
            (constantly false), so a modifier carrying its own TOUSchedule with
            holiday-sensitive brackets resolved the non-holiday period on a
            holiday and said nothing about it. Every other gap on this path
            withholds or refuses; this one answered incorrectly."
    (let [m (tou-modifier)
          inst (at 2026 7 15)
          ids (fn [opts] (->> (:urpx.resolved/ledgers
                               (price/resolve-modifier-prices m inst opts))
                              (mapcat :urpx.resolved/tiers)
                              (map :urpx.resolved/price-definition-id)
                              sort vec))]
      (is (= ["test:adj-peak"] (ids nil))
          "no predicate: the non-holiday bracket, which is the old behavior")
      (is (= ["test:adj-peak"] (ids {:holiday? (constantly false)}))
          "told it is not a holiday: the same, so the default was not wrong here")
      (is (= ["test:adj-offpeak"] (ids {:holiday? (constantly true)}))
          "told it IS a holiday: the holiday bracket, which no caller could reach before"))))

(deftest a-cell-selection-reaches-a-modifier-resolved-alone
  (testing "URPX-crk. A cell-scoped urpx:ServiceBracket's bounds could not be
            determined standalone at all, because no selection could arrive, so
            they were always withheld. Now they can."
    (let [m (-> (modifier [(pd "test:adj-t1" "Tier 1 adjustment")]
                          :tiers [(container "test:c"
                                             [(bracket "test:b-care" 13.5M :alternative "test:pa-care")
                                              (bracket "test:b-std" 11.0M :alternative "test:pa-std")])]
                          :tier-numbers {0 1})
                (update-in [:urpx/hasRatePlanModifierVersion :urpx/hasPlanElements]
                           assoc :urpx/hasProfileAlternatives
                           [{:jsonld/type "urpx:ProfileAlternatives" :jsonld/id "test:pas"
                             :urpx/hasProfileAlternative
                             [{:jsonld/type "urpx:ProfileAlternative" :jsonld/id "test:pa-care"}
                              {:jsonld/type "urpx:ProfileAlternative" :jsonld/id "test:pa-std"}]}]))
          tier (fn [opts] (->> (:urpx.resolved/ledgers
                                (price/resolve-modifier-prices m (at 2026 7 15) opts))
                               (mapcat :urpx.resolved/tiers)
                               (filter :urpx.resolved/tier-number)
                               first))]
      (is (= :alternative-unselected
             (:reason (:urpx.resolved/tier-bounds-unresolved (tier nil))))
          "no selection: withheld, as before")
      (is (= 13.5M (:urpx.resolved/tier-upper-bound
                    (tier {:profile-alternatives {"test:pas" "test:pa-care"}}))))
      (is (= 11.0M (:urpx.resolved/tier-upper-bound
                    (tier {:profile-alternatives {"test:pas" "test:pa-std"}})))))))

(deftest a-season-supplied-by-the-caller-reaches-a-modifier-resolved-alone
  (testing "URPX-crk. PG&E's E-TOU-C overlay declares no urpx:Season and names
            its base plan's, so standalone the gate is unevaluable and every
            season-qualified bracket's bounds are withheld. :by-id lets a caller
            supply the Season without composing the whole base plan."
    (let [m (modifier [(pd "test:adj-t1" "Tier 1 adjustment")]
                      :seasons []
                      :tiers [(container "test:c" [(bracket "test:b-s" 13.5M
                                                            :season "other:season-summer")])]
                      :tier-numbers {0 1})
          tier (fn [opts] (->> (:urpx.resolved/ledgers
                                (price/resolve-modifier-prices m (at 2026 7 15) opts))
                               (mapcat :urpx.resolved/tiers)
                               (filter :urpx.resolved/tier-number)
                               first))]
      (is (= :season-not-evaluable
             (:reason (:urpx.resolved/tier-bounds-unresolved (tier nil))))
          "nothing declares the Season, so the gate was never evaluated")
      (is (= 13.5M (:urpx.resolved/tier-upper-bound
                    (tier {:by-id {"other:season-summer"
                                   {:jsonld/type "urpx:Season"
                                    :jsonld/id   "other:season-summer"
                                    :urpx/month  [6 7 8 9]}}})))))))

(deftest the-two-arity-still-behaves-as-it-did
  (testing "the 2-arity is what every existing caller uses and must not move.
            It now delegates to the 3-arity with nil opts, which is the same
            thing it used to do inline."
    (let [m (modifier [(pd "test:adj-flat" "Flat adjustment")])]
      (is (= (price/resolve-modifier-prices m (at 2026 7 15))
             (price/resolve-modifier-prices m (at 2026 7 15) nil))))))

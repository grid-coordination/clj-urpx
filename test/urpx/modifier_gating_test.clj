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
  [pds & {:keys [seasons] :or {seasons [summer winter]}}]
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
      (seq seasons) (assoc :urpx/hasSeasons seasons))
    :urpx/hasPrices
    {:jsonld/type "urpx:Prices"
     :urpx/hasPrice
     (vec (map-indexed (fn [i pd]
                         {:jsonld/type "urpx:Price"
                          :jsonld/id   (str (:jsonld/id pd) "-price")
                          :urpx/name   (:urpx/name pd)
                          :urpx/unitPrice (BigDecimal. (str "0.0" (inc i)))
                          :urpx/referencesPriceDefinition {:jsonld/id (:jsonld/id pd)}})
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

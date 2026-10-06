(ns urpx.bound-offsets-test
  "Adjusting urpx:ServiceBrackets: a bracket carrying urpx:adjustsServiceTier
  and urpx:lowerBoundOffset or urpx:upperBoundOffset in place of bounds, whose
  offsets add to the same bound of the adjusted tier's applicable bracket
  when its own qualifiers hold.

  The synthetic base is a two-tier ladder over a (season x code) matrix, the
  shape of SCE's baseline allocation; the real-filing case is PG&E E-TOU-C
  with its baseline-tier modifier and a constructed Medical Baseline
  modifier."
  (:require [clojure.test :refer [deftest is testing]]
            [urpx.coerce :as coerce]
            [urpx.emit :as emit]
            [urpx.price :as price]
            [urpx.schema :as schema]
            [urpx.test-fixtures :as fixtures])
  (:import [java.time LocalDate LocalDateTime ZoneId ZonedDateTime]))

(def ^:private la (ZoneId/of "America/Los_Angeles"))

(defn- at [y m d] (ZonedDateTime/of y m d 17 0 0 0 la))

(def ^:private summer (at 2026 7 15))
(def ^:private winter (at 2026 12 15))

(defn- refusal
  "The ex-data of the refusal `f` throws, or nil if it returns."
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

;; ---------------------------------------------------------------------------
;; Builders
;; ---------------------------------------------------------------------------

(defn- version-common []
  {:urpx/changeReason {:jsonld/id "urpx:rateCase"}
   :urpx/hasCoverageClaim {:jsonld/type "urpx:CoverageClaim"
                           :urpx/completenessResult {:jsonld/id "urpx:complete"}}
   :urpx/serviceRole {:jsonld/id "urpx:bundledServiceRole"}
   :urpx/effectiveDateTime (LocalDateTime/of 2025 1 1 0 0)
   :urpx/priceCurrency "USD"})

(defn- header [type id]
  {:jsonld/type type :jsonld/id id :urpx/name id
   :urpx/timezoneIdentifier "America/Los_Angeles"
   :urpx/initialEffectiveDate (LocalDate/of 2025 1 1)})

(defn- metric-input [id unit]
  {:jsonld/type "urpx:MetricInput" :jsonld/id id :urpx/name id
   :urpx/metricInputType {:jsonld/id "urpx:measuredMetricInput"}
   :urpx/hasMetricSpecification {:jsonld/type "urpx:MetricSpecification"
                                 :urpx/measurementKind {:jsonld/id "urpx:energy"}
                                 :urpx/unitOfMeasure {:jsonld/id unit}}})

(defn- bracket [id lower upper & {:keys [season alternative]}]
  (cond-> {:jsonld/type "urpx:ServiceBracket" :jsonld/id id
           :urpx/lowerBound lower
           :urpx/lowerBoundOperator {:jsonld/id (if (zero? lower)
                                                  "urpx:greaterThanOrEqualOperator"
                                                  "urpx:greaterThanOperator")}}
    upper       (assoc :urpx/upperBound upper
                       :urpx/upperBoundOperator {:jsonld/id "urpx:lessThanOrEqualOperator"})
    season      (assoc :urpx/appliesInSeason [{:jsonld/id season}])
    alternative (assoc :urpx/referencesProfileAlternative {:jsonld/id alternative})))

(def ^:private cells
  "[season code tier-1-upper] for the base matrix."
  [["test:season-summer" "test:pa-basic"   10M]
   ["test:season-summer" "test:pa-allelec" 12M]
   ["test:season-winter" "test:pa-basic"    8M]
   ["test:season-winter" "test:pa-allelec" 15M]])

(defn- cell-id [prefix season alternative]
  (str prefix "-" (subs season 12) "-" (subs alternative 8)))

(def ^:private matrix-tiers
  [{:jsonld/type "urpx:ServiceTier" :jsonld/id "test:tier-1" :urpx/serviceTierNumber 1
    :urpx/hasServiceBracket (vec (for [[s a u] cells]
                                   (bracket (cell-id "test:t1" s a) 0M u :season s :alternative a)))}
   {:jsonld/type "urpx:ServiceTier" :jsonld/id "test:tier-2" :urpx/serviceTierNumber 2
    :urpx/hasServiceBracket (vec (for [[s a u] cells]
                                   (bracket (cell-id "test:t2" s a) u nil :season s :alternative a)))}])

(def ^:private flat-tiers
  [{:jsonld/type "urpx:ServiceTier" :jsonld/id "test:tier-1" :urpx/serviceTierNumber 1
    :urpx/hasServiceBracket [(bracket "test:t1" 0M 10M)]}
   {:jsonld/type "urpx:ServiceTier" :jsonld/id "test:tier-2" :urpx/serviceTierNumber 2
    :urpx/hasServiceBracket [(bracket "test:t2" 10M nil)]}])

(defn- base
  "A coerced RatePlan pricing tiers 1 and 2 of `tiers`."
  [tiers]
  (assoc (header "urpx:RatePlan" "test:base")
         :urpx/ratePlanUniqueIdentifier "TEST-BASE"
         :urpx/hasRatePlanVersion
         (merge (version-common)
                {:jsonld/type "urpx:RatePlanVersion"
                 :urpx/forRatePlan {:jsonld/id "test:base"}
                 :urpx/hasPlanElements
                 {:jsonld/type "urpx:PlanElements"
                  :urpx/hasSeasons
                  [{:jsonld/type "urpx:Season" :jsonld/id "test:season-summer" :urpx/seasonName "Summer"
                    :urpx/yearlyRecurrence true :urpx/month [6 7 8 9]}
                   {:jsonld/type "urpx:Season" :jsonld/id "test:season-winter" :urpx/seasonName "Winter"
                    :urpx/yearlyRecurrence true :urpx/month [10 11 12 1 2 3 4 5]}]
                  :urpx/hasProfileAlternatives
                  [{:jsonld/type "urpx:ProfileAlternatives" :jsonld/id "test:pas-code"
                    :urpx/hasProfileAlternative
                    [{:jsonld/type "urpx:ProfileAlternative" :jsonld/id "test:pa-basic" :urpx/name "Basic"}
                     {:jsonld/type "urpx:ProfileAlternative" :jsonld/id "test:pa-allelec" :urpx/name "All-Electric"}]}]
                  :urpx/hasMetricInput [(metric-input "test:mi-energy" "urpx:kWh")]
                  :urpx/hasBillingModel
                  {:jsonld/type "urpx:BillingModel"
                   :urpx/hasLedger [{:jsonld/type "urpx:Ledger" :jsonld/id "test:ledger-energy"
                                     :urpx/name "Energy"
                                     :urpx/ledgerType {:jsonld/id "urpx:supplyLedger"}
                                     :urpx/referencesMetricInput [{:jsonld/id "test:mi-energy"}]}]
                   :urpx/hasServiceTiers
                   [{:jsonld/type "urpx:ServiceTiers" :jsonld/id "test:tiers"
                     :urpx/referencesMetricInput [{:jsonld/id "test:mi-energy"}]
                     :urpx/hasServiceTier tiers}]
                   :urpx/hasPriceDefinition
                   [{:jsonld/type "urpx:PriceDefinition" :jsonld/id "test:pd-energy" :urpx/name "Energy"
                     :urpx/referencesLedger {:jsonld/id "test:ledger-energy"}}]}}
                 :urpx/hasPrices
                 {:jsonld/type "urpx:Prices"
                  :urpx/hasPrice
                  [{:jsonld/type "urpx:Price" :jsonld/id "test:p-t1" :urpx/name "T1"
                    :urpx/serviceTierNumber 1 :urpx/unitPrice 0.20M
                    :urpx/referencesPriceDefinition {:jsonld/id "test:pd-energy"}}
                   {:jsonld/type "urpx:Price" :jsonld/id "test:p-t2" :urpx/name "T2"
                    :urpx/serviceTierNumber 2 :urpx/unitPrice 0.30M
                    :urpx/referencesPriceDefinition {:jsonld/id "test:pd-energy"}}]}})))

(defn- adjusting [id tier & {:keys [season alternative lower upper]}]
  (cond-> {:jsonld/type "urpx:ServiceBracket" :jsonld/id id
           :urpx/adjustsServiceTier [{:jsonld/id tier}]}
    season      (assoc :urpx/appliesInSeason [{:jsonld/id season}])
    alternative (assoc :urpx/referencesProfileAlternative {:jsonld/id alternative})
    lower       (assoc :urpx/lowerBoundOffset lower)
    upper       (assoc :urpx/upperBoundOffset upper)))

(defn- modifier
  "A coerced RatePlanModifier applicable to `plan-id` whose one host tier holds
   `brackets`. `opts` may add host-tier Prices, which number the host tier 1
   and give the modifier a ledger, extra MetricInputs, or the host container's
   MetricInput."
  ([id brackets] (modifier id brackets nil))
  ([id brackets {:keys [plan-id prices metric-inputs mi]
                 :or   {plan-id "test:base" mi "test:mi-energy"}}]
   (assoc (header "urpx:RatePlanModifier" id)
          :urpx/hasRatePlanModifierVersion
          (merge (version-common)
                 {:jsonld/type "urpx:RatePlanModifierVersion"
                  :urpx/forRatePlanModifier {:jsonld/id id}
                  :urpx/applicableToRatePlan [{:jsonld/id plan-id}]
                  :urpx/hasPlanElements
                  {:jsonld/type "urpx:PlanElements"
                   :urpx/hasMetricInput (vec metric-inputs)
                   :urpx/hasBillingModel
                   (cond-> {:jsonld/type "urpx:BillingModel"
                            :urpx/hasServiceTiers
                            [{:jsonld/type "urpx:ServiceTiers" :jsonld/id (str id "-tiers")
                              :urpx/referencesMetricInput [{:jsonld/id mi}]
                              :urpx/hasServiceTier
                              [(cond-> {:jsonld/type "urpx:ServiceTier" :jsonld/id (str id "-host")
                                        :urpx/hasServiceBracket brackets}
                                 prices (assoc :urpx/serviceTierNumber 1))]}]}
                     prices
                     (assoc :urpx/hasLedger
                            [{:jsonld/type "urpx:Ledger" :jsonld/id (str id "-ledger")
                              :urpx/name "Host"
                              :urpx/ledgerType {:jsonld/id "urpx:supplyLedger"}}]
                            :urpx/hasPriceDefinition
                            [{:jsonld/type "urpx:PriceDefinition" :jsonld/id (str id "-pd")
                              :urpx/referencesLedger {:jsonld/id (str id "-ledger")}}]))}
                  :urpx/hasPrices {:jsonld/type "urpx:Prices"
                                   :urpx/hasPrice
                                   (vec (for [p prices]
                                          (assoc p :urpx/referencesPriceDefinition
                                                 {:jsonld/id (str id "-pd")})))}}))))

(def ^:private hpwh
  "SCE Preliminary Statement Part H's Heat Pump Water Heater allocation, in the
   synthetic base's matrix: summer 1.9, winter basic 2.6, winter all-electric
   3.0, on Tier 1's upper bound and Tier 2's lower bound."
  (modifier "test:hpwh"
            (vec (for [[s a d] [["test:season-summer" nil               1.9M]
                                ["test:season-winter" "test:pa-basic"   2.6M]
                                ["test:season-winter" "test:pa-allelec" 3.0M]]
                       [prefix tier k] [["test:hp-t1" "test:tier-1" :upper]
                                        ["test:hp-t2" "test:tier-2" :lower]]]
                   (adjusting (cell-id prefix s (or a "test:pa-any")) tier
                              :season s :alternative a k d)))))

(defn- code [alternative] {:profile-alternatives {"test:pas-code" alternative}})

(defn- tiers
  "{tier-number tier-entry} across the composition's ledgers named `ledger`."
  ([result] (tiers result "Energy"))
  ([result ledger]
   (into {} (for [l (:urpx.resolved/ledgers result)
                  :when (= ledger (:urpx.resolved/ledger-name l))
                  t (:urpx.resolved/tiers l)
                  :when (:urpx.resolved/tier-number t)]
              [(:urpx.resolved/tier-number t) t]))))

(defn- bounds [entry]
  [(:urpx.resolved/tier-lower-bound entry) (:urpx.resolved/tier-upper-bound entry)])

(defn- composed [plan mods inst & [opts]]
  (tiers (price/resolve-prices-with-modifiers plan mods inst opts)))

;; ---------------------------------------------------------------------------
;; PG&E E-TOU-C with Medical Baseline
;; ---------------------------------------------------------------------------

(def ^:private medical
  "PG&E Electric Rule 19's Standard Medical Baseline Quantity, 16.438 kWh/day,
   as raw JSON-LD values so the offsets go through coercion. Its host tier
   carries no serviceTierNumber and its billing model no PriceDefinition.
   Tier 2's upper bound and Tier 3's lower are left where the baseline-tier
   modifier puts them."
  (coerce/coerce-rate-plan-modifier
   {:jsonld/type "urpx:RatePlanModifier" :jsonld/id "test:pge-medical-baseline"
    :urpx/name "Medical Baseline" :urpx/timezoneIdentifier "America/Los_Angeles"
    :urpx/modifierUniqueIdentifier "MEDICAL-BASELINE"
    :urpx/initialEffectiveDate "2023-03-23"
    :urpx/hasRatePlanModifierVersion
    {:jsonld/type "urpx:RatePlanModifierVersion"
     :urpx/changeReason {:jsonld/id "urpx:rateCase"}
     :urpx/forRatePlanModifier {:jsonld/id "test:pge-medical-baseline"}
     :urpx/modifierVersionNumber "2023-03-23"
     :urpx/hasCoverageClaim {:jsonld/type "urpx:CoverageClaim"
                             :urpx/completenessResult {:jsonld/id "urpx:complete"}}
     :urpx/serviceRole {:jsonld/id "urpx:bundledServiceRole"}
     :urpx/effectiveDateTime "2023-03-23T00:00:00"
     :urpx/priceCurrency "USD"
     :urpx/applicableToRatePlan {:jsonld/id "pge:e-tou-c"}
     :urpx/hasPlanElements
     {:jsonld/type "urpx:PlanElements"
      :urpx/hasMetricInput
      {:jsonld/type "urpx:MetricInput" :jsonld/id "test:pge-mi-energy-medical"
       :urpx/name "Energy consumption (kWh)"
       :urpx/metricInputType {:jsonld/id "urpx:measuredMetricInput"}
       :urpx/hasMetricSpecification {:jsonld/type "urpx:MetricSpecification"
                                     :urpx/measurementKind {:jsonld/id "urpx:energy"}
                                     :urpx/unitOfMeasure {:jsonld/id "urpx:kWh"}}}
      :urpx/hasBillingModel
      {:jsonld/type "urpx:BillingModel"
       :urpx/hasServiceTiers
       {:jsonld/type "urpx:ServiceTiers" :jsonld/id "test:pge-medical-tiers"
        :urpx/referencesMetricInput {:jsonld/id "test:pge-mi-energy-medical"}
        :urpx/hasServiceTier
        {:jsonld/type "urpx:ServiceTier" :jsonld/id "test:pge-medical-host"
         :urpx/hasServiceBracket
         [{:jsonld/type "urpx:ServiceBracket" :jsonld/id "test:pge-medical-t1"
           :urpx/adjustsServiceTier {:jsonld/id "pge:tier-baseline-t1"}
           :urpx/upperBoundOffset "16.438"}
          {:jsonld/type "urpx:ServiceBracket" :jsonld/id "test:pge-medical-t2"
           :urpx/adjustsServiceTier {:jsonld/id "pge:tier-baseline-t2"}
           :urpx/lowerBoundOffset "16.438"}]}}}}
     :urpx/hasPrices {:jsonld/type "urpx:Prices"}}}))

(deftest medical-baseline-raises-the-pge-tier-1-and-2-boundary
  (let [plan (fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")
        bt   (fixtures/load-modifier "pge-e-tou-c-baseline-tier-2026-03-01")
        sel  {:profile-alternatives {"pge:pa-baseline-territory-code" "pge:pa-baseline-p-b"}}
        cia  "Conservation Incentive Adjustment"
        without (tiers (price/resolve-prices-with-modifiers plan [bt] summer sel) cia)
        with    (tiers (price/resolve-prices-with-modifiers plan [bt medical] summer sel) cia)]
    (testing "July, cell P/B: Tier 1 is 0 to 13.5 without the modifier"
      (is (= [0M 13.5M] (bounds (without 1)))))
    (testing "Tier 1's upper and Tier 2's lower both move by 16.438"
      (is (= [0M 29.938M] (bounds (with 1))))
      (is (= [29.938M 54.0M] (bounds (with 2))))
      (is (= [54.0M nil] (bounds (with 3)))))
    (testing "operators are unchanged"
      (doseq [n [1 2 3]
              k [:urpx.resolved/tier-lower-bound-operator :urpx.resolved/tier-upper-bound-operator]]
        (is (= (get (without n) k) (get (with n) k)))))
    (testing "the base bound, the summed offset and the contributing bracket are reported"
      (is (= {:urpx.resolved/tier-upper-bound-base   13.5M
              :urpx.resolved/tier-upper-bound-offset 16.438M
              :urpx.resolved/tier-adjusting-bracket-ids ["test:pge-medical-t1"]}
             (select-keys (with 1) [:urpx.resolved/tier-upper-bound-base
                                    :urpx.resolved/tier-upper-bound-offset
                                    :urpx.resolved/tier-lower-bound-base
                                    :urpx.resolved/tier-lower-bound-offset
                                    :urpx.resolved/tier-adjusting-bracket-ids])))
      (is (= {:urpx.resolved/tier-lower-bound-base   13.5M
              :urpx.resolved/tier-lower-bound-offset 16.438M
              :urpx.resolved/tier-adjusting-bracket-ids ["test:pge-medical-t2"]}
             (select-keys (with 2) [:urpx.resolved/tier-upper-bound-base
                                    :urpx.resolved/tier-upper-bound-offset
                                    :urpx.resolved/tier-lower-bound-base
                                    :urpx.resolved/tier-lower-bound-offset
                                    :urpx.resolved/tier-adjusting-bracket-ids]))))
    (testing "an unadjusted tier carries none of the adjustment keys"
      (is (not-any? #(contains? (with 3) %)
                    [:urpx.resolved/tier-lower-bound-base :urpx.resolved/tier-upper-bound-base
                     :urpx.resolved/tier-adjusting-bracket-ids])))))

;; ---------------------------------------------------------------------------
;; Coercion and emission
;; ---------------------------------------------------------------------------

(deftest a-modifier-that-only-moves-bounds-validates
  (is (nil? (coerce/explain schema/RatePlanModifier medical))))

(deftest offsets-coerce-to-decimals-and-emit-typed
  (let [bracket (get-in medical [:urpx/hasRatePlanModifierVersion :urpx/hasPlanElements
                                 :urpx/hasBillingModel :urpx/hasServiceTiers 0
                                 :urpx/hasServiceTier 0 :urpx/hasServiceBracket 0])]
    (testing "a bare urpx:adjustsServiceTier decodes to a one-element list, the offset to a decimal"
      (is (= [[{:jsonld/id "pge:tier-baseline-t1"}] 16.438M]
             ((juxt :urpx/adjustsServiceTier :urpx/upperBoundOffset) bracket))))
    (testing "both offsets are typed xsd:decimal in the default context"
      (is (= {"@type" "xsd:decimal"} (get emit/default-context "urpx:lowerBoundOffset")))
      (is (= {"@type" "xsd:decimal"} (get emit/default-context "urpx:upperBoundOffset"))))
    (testing "and round-trip"
      (is (= bracket (->> bracket
                          (coerce/encode schema/ServiceBracket)
                          (coerce/coerce schema/ServiceBracket)))))))

;; ---------------------------------------------------------------------------
;; Qualified offsets
;; ---------------------------------------------------------------------------

(deftest hpwh-offsets-follow-the-instant-and-the-alternative
  (let [plan (base matrix-tiers)]
    (doseq [[inst alternative t1-upper offset label]
            [[summer "test:pa-basic"   11.9M 1.9M "summer basic: 10 + 1.9"]
             [summer "test:pa-allelec" 13.9M 1.9M "summer all-electric: 12 + 1.9"]
             [winter "test:pa-basic"   10.6M 2.6M "winter basic: 8 + 2.6"]
             [winter "test:pa-allelec" 18.0M 3.0M "winter all-electric: 15 + 3.0"]]]
      (testing label
        (let [t (composed plan [hpwh] inst (code alternative))]
          (is (= [0M t1-upper] (bounds (t 1))))
          (is (= [t1-upper nil] (bounds (t 2))))
          (is (= offset (:urpx.resolved/tier-upper-bound-offset (t 1))))
          (is (= 1 (count (:urpx.resolved/tier-adjusting-bracket-ids (t 1))))))))))

(deftest offsets-from-several-adjusting-brackets-sum
  (let [plan  (base flat-tiers)
        extra (modifier "test:extra" [(adjusting "test:extra-t1" "test:tier-1" :upper 0.5M)])
        t     (composed plan [hpwh extra] summer (code "test:pa-basic"))]
    (is (= [0M 12.4M] (bounds (t 1))))
    (is (= 2.4M (:urpx.resolved/tier-upper-bound-offset (t 1))))
    (is (= 10M (:urpx.resolved/tier-upper-bound-base (t 1))))
    (is (= #{"test:hp-t1-summer-any" "test:extra-t1"}
           (set (:urpx.resolved/tier-adjusting-bracket-ids (t 1)))))))

(deftest a-negative-offset-lowers-the-bound
  (let [t (composed (base flat-tiers)
                    [(modifier "test:m" [(adjusting "test:m-t1" "test:tier-1" :upper -2M)
                                         (adjusting "test:m-t2" "test:tier-2" :lower -2M)])]
                    summer)]
    (is (= [0M 8M] (bounds (t 1))))
    (is (= [8M nil] (bounds (t 2))))))

(deftest a-duplicated-adjusting-bracket-counts-once
  (let [plan (base flat-tiers)]
    (testing "the same modifier passed twice"
      (is (= [0M 11.9M] (bounds ((composed plan [hpwh hpwh] summer (code "test:pa-basic")) 1)))))
    (testing "one bracket listed twice in its host tier"
      (let [b   (adjusting "test:dup-t1" "test:tier-1" :upper 1M)
            mod (modifier "test:dup" [b b])]
        (is (= [0M 11M] (bounds ((composed plan [mod] summer) 1))))))))

(deftest an-undecidable-adjusting-bracket-withholds-the-bounds
  (let [plan (base flat-tiers)
        t    (composed plan [hpwh] winter)]
    (testing "the base bracket is unqualified, the winter offsets need a code"
      (is (= [nil nil] (bounds (t 1))))
      (is (= {:reason       :adjustment-unresolved
              :scope        :adjusting-bracket
              :ids          ["test:hp-t1-summer-any" "test:hp-t1-winter-basic"
                             "test:hp-t1-winter-allelec"]
              :alternatives ["test:pa-basic" "test:pa-allelec"]}
             (:urpx.resolved/tier-bounds-unresolved (t 1))))
      (is (= 0.20M (:urpx.resolved/unit-price (t 1)))))
    (testing "naming the code clears it"
      (is (= [0M 12.6M] (bounds ((composed plan [hpwh] winter (code "test:pa-basic")) 1)))))))

(deftest an-adjusting-bracket-is-not-a-bound-of-its-host-tier
  (let [plan (base flat-tiers)
        mod  (modifier "test:host"
                       [(bracket "test:host-own" 0M 5M)
                        (adjusting "test:host-adj" "test:tier-1" :upper 1M
                                   :season "test:season-summer")]
                       {:prices [{:jsonld/type "urpx:Price" :jsonld/id "test:host-p1"
                                  :urpx/serviceTierNumber 1 :urpx/unitPrice 0.01M}]})
        res  (price/resolve-prices-with-modifiers plan [mod] summer)]
    (is (= [0M 5M] (bounds ((tiers res "Host") 1))))
    (is (not (contains? ((tiers res "Host") 1) :urpx.resolved/tier-adjusting-bracket-ids)))
    (is (= [0M 11M] (bounds ((tiers res) 1))))))

(deftest a-bare-single-value-reads-as-a-one-element-list
  (let [plan   (base flat-tiers)
        listed (adjusting "test:bare-t1" "test:tier-1" :upper 1M :season "test:season-summer")
        bare   (assoc listed
                      :urpx/adjustsServiceTier {:jsonld/id "test:tier-1"}
                      :urpx/appliesInSeason {:jsonld/id "test:season-summer"})]
    (is (= [0M 11M] (bounds ((composed plan [(modifier "test:m" [listed])] summer) 1))))
    (is (= (composed plan [(modifier "test:m" [listed])] summer)
           (composed plan [(modifier "test:m" [bare])] summer)))))

;; ---------------------------------------------------------------------------
;; Refusals
;; ---------------------------------------------------------------------------

(defn- refuses [mods & [inst]]
  (refusal #(price/resolve-prices-with-modifiers (base flat-tiers) mods (or inst summer))))

(deftest a-target-no-document-declares-refuses
  (testing "whatever the instant"
    (doseq [inst [summer winter]]
      (is (= {:reason :unresolvable-adjusted-tier :bracket-id "test:a" :tier-id "test:tier-9"}
             (refuses [(modifier "test:m" [(adjusting "test:a" "test:tier-9" :upper 1M
                                                      :season "test:season-summer")])]
                      inst)))))
  (testing "an offset naming no tier at all"
    (is (= :unresolvable-adjusted-tier
           (:reason (refuses [(modifier "test:m" [(-> (adjusting "test:a" "test:tier-1" :upper 1M)
                                                      (dissoc :urpx/adjustsServiceTier))])]))))))

(deftest a-target-that-is-not-a-service-tier-refuses
  (is (= {:reason :adjusted-tier-not-service-tier :bracket-id "test:a"
          :tier-id "test:t1" :node-type "urpx:ServiceBracket"}
         (refuses [(modifier "test:m" [(adjusting "test:a" "test:t1" :upper 1M)])]))))

(deftest several-adjusted-tiers-on-one-bracket-refuses
  (is (= {:reason :multiple-adjusted-tiers :bracket-id "test:a"
          :tier-ids ["test:tier-1" "test:tier-2"]}
         (refuses [(modifier "test:m" [(assoc (adjusting "test:a" "test:tier-1" :upper 1M)
                                              :urpx/adjustsServiceTier
                                              [{:jsonld/id "test:tier-1"} {:jsonld/id "test:tier-2"}])])]))))

(deftest an-offset-on-an-absent-bound-refuses
  (is (= {:reason :adjusted-bound-absent :tier-ids ["test:tier-2"]
          :bracket-id "test:t2" :adjusting-ids ["test:a"]}
         (refuses [(modifier "test:m" [(adjusting "test:a" "test:tier-2" :upper 1M)])]))))

(deftest an-adjusted-lower-bound-above-the-upper-refuses
  (is (= {:reason :adjusted-bounds-inverted :tier-ids ["test:tier-1"]
          :bracket-id "test:t1" :adjusting-ids ["test:a"]
          :lower-bound 20M :upper-bound 10M}
         (refuses [(modifier "test:m" [(adjusting "test:a" "test:tier-1" :lower 20M)])]))))

(deftest a-unit-mismatch-refuses
  (is (= {:reason :adjusted-tier-unit-mismatch :bracket-id "test:a" :tier-id "test:tier-1"
          :units ["urpx:therm"] :tier-units ["urpx:kWh"]}
         (refuses [(modifier "test:m" [(adjusting "test:a" "test:tier-1" :upper 1M)]
                             {:mi "test:mi-therm"
                              :metric-inputs [(metric-input "test:mi-therm" "urpx:therm")]})]))))

(deftest a-target-hosting-adjusting-brackets-refuses
  (testing "its own host tier"
    (is (= {:reason :adjusted-tier-adjusts :bracket-id "test:a" :tier-id "test:m-host"}
           (refuses [(modifier "test:m" [(adjusting "test:a" "test:m-host" :upper 1M)])]))))
  (testing "another modifier's host tier"
    (is (= {:reason :adjusted-tier-adjusts :bracket-id "test:b" :tier-id "test:m-host"}
           (refuses [(modifier "test:m" [(adjusting "test:a" "test:tier-1" :upper 1M)])
                     (modifier "test:n" [(adjusting "test:b" "test:m-host" :upper 1M)])])))))

(deftest a-container-stating-no-unit-refuses
  (testing "the adjusting bracket's own container"
    (is (= {:reason :adjusted-tier-unit-absent :bracket-id "test:a" :tier-id "test:tier-1"
            :units [] :tier-units ["urpx:kWh"]}
           (refuses [(modifier "test:m" [(adjusting "test:a" "test:tier-1" :upper 1M)]
                               {:mi "test:mi-unitless"
                                :metric-inputs [(dissoc (metric-input "test:mi-unitless" "urpx:kWh")
                                                        :urpx/hasMetricSpecification)]})]))))
  (testing "the adjusted tier's container"
    (is (= {:reason :adjusted-tier-unit-absent :bracket-id "test:a" :tier-id "test:tier-1"
            :units ["urpx:kWh"] :tier-units []}
           (refusal #(price/resolve-prices-with-modifiers
                      (update-in (base flat-tiers)
                                 [:urpx/hasRatePlanVersion :urpx/hasPlanElements :urpx/hasMetricInput 0]
                                 dissoc :urpx/hasMetricSpecification)
                      [(modifier "test:m" [(adjusting "test:a" "test:tier-1" :upper 1M)]
                                 {:mi "test:mi-own"
                                  :metric-inputs [(metric-input "test:mi-own" "urpx:kWh")]})]
                      summer))))))

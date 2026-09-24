(ns urpx.season-readers-test
  "One reader for urpx:appliesInSeason, across all three places it is read.

  There were three, and they disagreed about more than strictness (URPX-8yp).
  `pd-active-in-season?` skipped an unresolvable ref silently and read a Season
  carrying no urpx:month as excluding the month. `find-tou-period` put a
  TimeBracket's refs through `urpx.index/single`, which refuses on several.
  `node-season-status`, added in 0.32.0 for tier selection, refused an
  unresolvable ref where a Season was reachable and reported a monthless Season
  as unevaluable. So the same property answered a different question depending
  on which node carried it.

  `make-season-status` is now the single constructor and `node-season-status`
  the single reader; `season-refs` is the single shape normalizer. The one
  remaining difference is deliberate and tested here: a urpx:PriceSummary member
  has a third state for an unevaluable gate and a boolean filter does not.

  Every case is constructed. Measured across all 23 vendored fixtures: no
  TimeBracket names more than one Season, no urpx:PriceDefinition, urpx:Price or
  urpx:PriceSet carries urpx:appliesInSeason at all, and no Season lacking
  urpx:month is referenced. So none of this is reachable from the corpus, which
  is why the three readers could disagree unnoticed."
  (:require [clojure.test :refer [deftest is testing]]
            [urpx.price :as price])
  (:import [java.time LocalDate LocalDateTime ZoneId ZonedDateTime]))

(def ^:private la (ZoneId/of "America/Los_Angeles"))

(defn- at [y m d h] (ZonedDateTime/of y m d h 0 0 0 la))

(def ^:private summer
  {:jsonld/type "urpx:Season" :jsonld/id "test:season-summer"
   :urpx/seasonName "Summer" :urpx/yearlyRecurrence true :urpx/month [6 7 8 9]})

(def ^:private winter
  {:jsonld/type "urpx:Season" :jsonld/id "test:season-winter"
   :urpx/seasonName "Winter" :urpx/yearlyRecurrence true :urpx/month [10 11 12 1 2 3 4 5]})

(def ^:private undated
  "Valid and unreadable here: SeasonShape sets urpx:month at sh:minCount 0 and
  offers startDate/endDate instead, which this library does not implement."
  {:jsonld/type "urpx:Season" :jsonld/id "test:season-undated"
   :urpx/seasonName "Undated" :urpx/yearlyRecurrence true})

(def ^:private metric-input
  {:jsonld/type "urpx:MetricInput" :jsonld/id "test:mi-energy" :urpx/name "kWh"
   :urpx/metricInputType {:jsonld/id "urpx:profileMetricInput"}
   :urpx/hasMetricSpecification
   {:jsonld/type "urpx:MetricSpecification"
    :urpx/measurementKind {:jsonld/id "urpx:energy"}
    :urpx/unitOfMeasure   {:jsonld/id "urpx:kWh"}}})

(defn- plan
  "A coerced RatePlan. `billing-extra` merges into the BillingModel and `prices`
  replaces the price vector, so each test states only what it is about."
  [{:keys [seasons billing-extra pds prices]}]
  {:jsonld/type "urpx:RatePlan"
   :jsonld/id   "test:seasons-plan"
   :urpx/timezoneIdentifier "America/Los_Angeles"
   :urpx/ratePlanUniqueIdentifier "TEST-SEASON-READERS"
   :urpx/name "Season reader test plan"
   :urpx/initialEffectiveDate (LocalDate/of 2025 1 1)
   :urpx/hasRatePlanVersion
   {:jsonld/type "urpx:RatePlanVersion"
    :urpx/forRatePlan {:jsonld/id "test:seasons-plan"}
    :urpx/changeReason {:jsonld/id "urpx:rateCase"}
    :urpx/hasCoverageClaim {:jsonld/type "urpx:CoverageClaim"
                            :urpx/completenessResult {:jsonld/id "urpx:complete"}}
    :urpx/serviceRole {:jsonld/id "urpx:bundledServiceRole"}
    :urpx/effectiveDateTime (LocalDateTime/of 2025 1 1 0 0)
    :urpx/priceCurrency "USD"
    :urpx/hasPlanElements
    {:jsonld/type "urpx:PlanElements"
     :urpx/hasSeasons seasons
     :urpx/hasMetricInput [metric-input]
     :urpx/hasBillingModel
     (merge {:jsonld/type "urpx:BillingModel"
             :urpx/hasLedger [{:jsonld/type "urpx:Ledger" :jsonld/id "test:ledger-energy"
                               :urpx/name "Energy"
                               :urpx/ledgerType {:jsonld/id "urpx:supplyLedger"}
                               :urpx/referencesMetricInput [{:jsonld/id "test:mi-energy"}]}]
             :urpx/hasPriceDefinition pds}
            billing-extra)}
    :urpx/hasPrices {:jsonld/type "urpx:Prices" :urpx/hasPrice prices}}})

(defn- tiers [r]
  (mapcat :urpx.resolved/tiers (:urpx.resolved/ledgers r)))

(defn- price-ids [p inst]
  (->> (tiers (price/resolve-prices p inst)) (map :urpx.resolved/price-id) sort vec))

;; ---------------------------------------------------------------------------
;; Multiplicity, the divergence that was real on every node type
;; ---------------------------------------------------------------------------

(deftest a-time-bracket-naming-several-seasons-matches-in-either
  (testing "this went through urpx.index/single, which refuses on several refs.
            TimeBracketShape is sh:minCount 0 with no sh:maxCount like the rest,
            so a bracket naming two Seasons applies in either, and refusing made
            a legal filing unresolvable. The bracket here is peak 16:00-21:00 in
            BOTH seasons, which is how a schedule states one window that does
            not move with the season."
    (let [p (plan {:seasons [summer winter]
                   :billing-extra
                   {:urpx/hasTOUSchedule
                    [{:jsonld/type "urpx:TOUSchedule" :jsonld/id "test:tou"
                      :urpx/name "Both seasons"
                      :urpx/referencesMetricInput [{:jsonld/id "test:mi-energy"}]
                      ;; Periods hang off a urpx:TOUTier, which find-tou-period
                      ;; walks through: the tier hierarchy is classification and
                      ;; the bracket match is what resolves.
                      :urpx/hasTOUTier
                      [{:jsonld/type "urpx:TOUTier" :urpx/touTierNumber 1
                        :urpx/hasTOUPeriod
                        [{:jsonld/type "urpx:TOUPeriod" :jsonld/id "test:period-peak"
                          :urpx/name "Peak" :urpx/touPeriodNumber 1
                          :urpx/hasTimeBracket
                          [{:jsonld/type "urpx:TimeBracket"
                            :urpx/appliesInSeason [{:jsonld/id "test:season-summer"}
                                                   {:jsonld/id "test:season-winter"}]
                            :urpx/dayType {:jsonld/id "urpx:allDays"}
                            :urpx/startTime (java.time.LocalTime/of 16 0)
                            :urpx/endTime   (java.time.LocalTime/of 21 0)}]}]}]}]}
                   :pds [{:jsonld/type "urpx:PriceDefinition" :jsonld/id "test:pd-peak"
                          :urpx/name "Peak energy" :urpx/touPeriodNumber 1
                          :urpx/referencesLedger {:jsonld/id "test:ledger-energy"}}]
                   :prices [{:jsonld/type "urpx:Price" :jsonld/id "test:price-peak"
                             :urpx/name "Peak energy" :urpx/unitPrice 0.40M
                             :urpx/referencesPriceDefinition {:jsonld/id "test:pd-peak"}}]})]
      (testing "inside the window in both seasons, and it no longer refuses"
        (is (= ["test:price-peak"] (price-ids p (at 2026 7 15 17))))
        (is (= ["test:price-peak"] (price-ids p (at 2026 12 15 17)))))
      (testing "and the TOU period itself resolves, in each season"
        (is (= 1 (:urpx.resolved/tou-period-number (price/resolve-prices p (at 2026 7 15 17)))))
        (is (= 1 (:urpx.resolved/tou-period-number (price/resolve-prices p (at 2026 12 15 17))))))
      (testing "outside the window it does not match, so the season read is not
                the only thing carrying the match"
        (is (nil? (:urpx.resolved/tou-period-number
                   (price/resolve-prices p (at 2026 7 15 9)))))))))

;; ---------------------------------------------------------------------------
;; An unevaluable gate, and the one deliberate difference between the readers
;; ---------------------------------------------------------------------------

(deftest a-definition-whose-season-carries-no-month-still-applies
  (testing "the month list is the only season gate this library implements, so a
            Season without one leaves it unevaluated. Dropping the definition
            would be a price silently missing from the sum, which is the failure
            this library spends its refusals on, and the same reasoning
            condition-gates records for a predicate nothing here evaluates.

            It is also what keeps URPX-2a6 fixed: if an unevaluable gate
            excluded, a modifier resolved without the base plan that declares
            its Seasons would silently drop every seasonal definition, which is
            the defect 2a6 removed by a different route."
    (let [p (plan {:seasons [summer winter undated]
                   :pds [{:jsonld/type "urpx:PriceDefinition" :jsonld/id "test:pd-undated"
                          :urpx/name "Undated energy"
                          :urpx/appliesInSeason [{:jsonld/id "test:season-undated"}]
                          :urpx/referencesLedger {:jsonld/id "test:ledger-energy"}}]
                   :prices [{:jsonld/type "urpx:Price" :jsonld/id "test:price-undated"
                             :urpx/name "Undated energy" :urpx/unitPrice 0.20M
                             :urpx/referencesPriceDefinition {:jsonld/id "test:pd-undated"}}]})]
      (is (= ["test:price-undated"] (price-ids p (at 2026 7 15 17))))
      (is (= ["test:price-undated"] (price-ids p (at 2026 12 15 17)))
          "in every month, because no month was ever compared"))))

(deftest a-definition-out-of-season-is-still-excluded
  (testing "the guard on the above: making an unevaluable gate permissive must
            not make an evaluable one permissive too."
    (let [p (plan {:seasons [summer winter]
                   :pds [{:jsonld/type "urpx:PriceDefinition" :jsonld/id "test:pd-summer"
                          :urpx/name "Summer energy"
                          :urpx/appliesInSeason [{:jsonld/id "test:season-summer"}]
                          :urpx/referencesLedger {:jsonld/id "test:ledger-energy"}}]
                   :prices [{:jsonld/type "urpx:Price" :jsonld/id "test:price-summer"
                             :urpx/name "Summer energy" :urpx/unitPrice 0.20M
                             :urpx/referencesPriceDefinition {:jsonld/id "test:pd-summer"}}]})]
      (is (= ["test:price-summer"] (price-ids p (at 2026 7 15 17))))
      (is (= [] (price-ids p (at 2026 12 15 17)))))))

(deftest a-summary-member-whose-season-is-unevaluable-refuses-the-subtotal
  (testing "the deliberate difference. A boolean filter has two answers, so an
            unevaluable gate resolves to \"applies\". A subtotal has three: it
            can report that it is not defined here. Excluding the member
            understates the subtotal and including it overstates it, which is
            exactly the reasoning the unselected-alternative case already
            follows, so the member is :unknown and the derived Price says so
            rather than carrying a number."
    (let [p (plan {:seasons [summer winter undated]
                   :pds [{:jsonld/type "urpx:PriceDefinition" :jsonld/id "test:pd-undated"
                          :urpx/name "Undated component"
                          :urpx/appliesInSeason [{:jsonld/id "test:season-undated"}]
                          :urpx/referencesLedger {:jsonld/id "test:ledger-energy"}}
                         {:jsonld/type "urpx:PriceDefinition" :jsonld/id "test:pd-plain"
                          :urpx/name "Plain component"
                          :urpx/referencesLedger {:jsonld/id "test:ledger-energy"}}
                         {:jsonld/type "urpx:PriceDefinition" :jsonld/id "test:pd-disc"
                          :urpx/name "Discount"
                          :urpx/referencesLedger {:jsonld/id "test:ledger-energy"}}]
                   :prices [{:jsonld/type "urpx:Price" :jsonld/id "test:price-undated"
                             :urpx/name "Undated component" :urpx/unitPrice 0.20M
                             :urpx/referencesPriceDefinition {:jsonld/id "test:pd-undated"}}
                            {:jsonld/type "urpx:Price" :jsonld/id "test:price-plain"
                             :urpx/name "Plain component" :urpx/unitPrice 0.30M
                             :urpx/referencesPriceDefinition {:jsonld/id "test:pd-plain"}}
                            {:jsonld/type "urpx:PriceSummary" :jsonld/id "test:summary"
                             :urpx/name "Subtotal"
                             :urpx/includesPrice [{:jsonld/id "test:price-undated"}
                                                  {:jsonld/id "test:price-plain"}]}
                            {:jsonld/type "urpx:Price" :jsonld/id "test:price-disc"
                             :urpx/name "Discount" :urpx/multiplier -1M
                             :urpx/basedOnPrice {:jsonld/id "test:summary"}
                             :urpx/referencesPriceDefinition {:jsonld/id "test:pd-disc"}}]})
          disc (->> (tiers (price/resolve-prices p (at 2026 7 15 17)))
                    (filter #(= "test:price-disc" (:urpx.resolved/price-id %)))
                    first)]
      (is (some? disc) "the derived price still surfaces")
      (is (nil? (:urpx.resolved/unit-price disc))
          "but carries no number, because one member's applicability is unknown")
      (is (= :summary-member-unselected (:reason (:urpx.resolved/unvaluable disc)))
          "and names why")
      (testing "while the two components themselves both resolve, the undated one
                because an unevaluable gate does not exclude a definition"
        (is (= ["test:price-disc" "test:price-plain" "test:price-undated"]
               (price-ids p (at 2026 7 15 17))))))))

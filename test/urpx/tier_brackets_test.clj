(ns urpx.tier-brackets-test
  "Which urpx:ServiceBracket states a tier's bounds, and which urpx:ServiceTiers
  container its tiers come from.

  Upstream puts urpx:appliesInSeason and urpx:referencesProfileAlternative on
  ServiceTiers and on ServiceBracket alike, at sh:minCount 0 with no
  sh:maxCount for the first and sh:maxCount 1 for the second. clj-urpx accepted
  all four and read none of them: bounds came from the first attached bracket,
  so a tier carrying a summer bracket and a winter bracket surfaced the summer
  bounds in December, and two containers declaring one serviceTierNumber
  last-won on map insertion order.

  Every case here is constructed, which was the only option when this landed:
  the tiered base-plan fixtures carry one unqualified bracket per tier, and the
  one filing that qualifies its brackets, PG&E's E-TOU-C baseline-tier overlay
  with 120 of them, is a urpx:RatePlanModifier. `urpx.modifier-gating-test`
  holds the real-filing case; these stay constructed because a synthetic
  document can state one shape per test where a filing states many at once."
  (:require [clojure.test :refer [deftest is testing]]
            [urpx.price :as price]
            [urpx.test-fixtures :as fixtures])
  (:import [java.time LocalDate LocalDateTime ZoneId ZonedDateTime]))

(def ^:private la (ZoneId/of "America/Los_Angeles"))

(defn- at [y m d] (ZonedDateTime/of y m d 17 0 0 0 la))

(defn- bracket
  "A urpx:ServiceBracket with both bounds and both operators, optionally scoped
  to a Season and to a ProfileAlternative. Both bounds so that
  `bracket-bounds-key` is compared on all four values, not on a partial one."
  [id upper & {:keys [season alternative lower]}]
  (cond-> {:jsonld/type "urpx:ServiceBracket"
           :jsonld/id   id
           :urpx/lowerBound (or lower 0M)
           :urpx/lowerBoundOperator {:jsonld/id "urpx:greaterThanOrEqualOperator"}
           :urpx/upperBound upper
           :urpx/upperBoundOperator {:jsonld/id "urpx:lessThanOrEqualOperator"}}
    season      (assoc :urpx/appliesInSeason [{:jsonld/id season}])
    alternative (assoc :urpx/referencesProfileAlternative {:jsonld/id alternative})))

(defn- container
  "A urpx:ServiceTiers holding one urpx:ServiceTier numbered 1, optionally
  scoped to a Season or a ProfileAlternative. `brackets` may be empty, which
  ServiceTierShape forbids at sh:minCount 1 but the Malli schema accepts."
  [id brackets & {:keys [season alternative tier-name]}]
  (cond-> {:jsonld/type "urpx:ServiceTiers"
           :jsonld/id   id
           :urpx/referencesMetricInput [{:jsonld/id "test:mi-energy"}]
           :urpx/hasServiceTier
           [{:jsonld/type "urpx:ServiceTier"
             :jsonld/id   (str id "-t1")
             :urpx/serviceTierNumber 1
             :urpx/name (or tier-name "Tier 1")
             :urpx/hasServiceBracket brackets}]}
    season      (assoc :urpx/appliesInSeason [{:jsonld/id season}])
    alternative (assoc :urpx/referencesProfileAlternative {:jsonld/id alternative})))

(defn- plan
  "A coerced RatePlan carrying `containers` and one tier-1 Price, built to the
  shape `urpx.price-test`'s builders use. The header is load-bearing:
  :urpx/timezoneIdentifier since 0.31.0, and :urpx/changeReason,
  :urpx/serviceRole and :urpx/hasCoverageClaim on the version snapshot.

  Three Seasons, one of them carrying no urpx:month. SeasonShape sets
  urpx:month at sh:minCount 0 and offers startDate/endDate instead, so that
  Season is valid and this library's month-only gate cannot read it."
  [containers]
  {:jsonld/type "urpx:RatePlan"
   :jsonld/id   "urpx:test-tier-brackets"
   :urpx/timezoneIdentifier "America/Los_Angeles"
   :urpx/ratePlanUniqueIdentifier "TEST-TIER-BRACKETS"
   :urpx/name "Bracket selection test plan"
   :urpx/initialEffectiveDate (LocalDate/of 2025 1 1)
   :urpx/hasRatePlanVersion
   {:jsonld/type "urpx:RatePlanVersion"
    :urpx/changeReason {:jsonld/id "urpx:rateCase"}
    :urpx/forRatePlan {:jsonld/id "ex:parent"}
    :urpx/hasCoverageClaim {:jsonld/type "urpx:CoverageClaim"
                            :urpx/completenessResult {:jsonld/id "urpx:complete"}}
    :urpx/serviceRole {:jsonld/id "urpx:bundledServiceRole"}
    :urpx/effectiveDateTime (LocalDateTime/of 2025 1 1 0 0)
    :urpx/priceCurrency "USD"
    :urpx/hasPlanElements
    {:jsonld/type "urpx:PlanElements"
     :urpx/hasSeasons
     [{:jsonld/type "urpx:Season" :jsonld/id "test:season-summer"
       :urpx/seasonName "Summer" :urpx/yearlyRecurrence true :urpx/month [6 7 8 9]}
      {:jsonld/type "urpx:Season" :jsonld/id "test:season-winter"
       :urpx/seasonName "Winter" :urpx/yearlyRecurrence true :urpx/month [10 11 12 1 2 3 4 5]}
      {:jsonld/type "urpx:Season" :jsonld/id "test:season-undated"
       :urpx/seasonName "Undated" :urpx/yearlyRecurrence true}]
     :urpx/hasMetricInput
     [{:jsonld/type "urpx:MetricInput" :jsonld/id "test:mi-energy" :urpx/name "kWh"
       :urpx/metricInputType {:jsonld/id "urpx:profileMetricInput"}
       :urpx/hasMetricSpecification
       {:jsonld/type "urpx:MetricSpecification"
        :urpx/measurementKind {:jsonld/id "urpx:energy"}
        :urpx/unitOfMeasure   {:jsonld/id "urpx:kWh"}}}]
     :urpx/hasBillingModel
     {:jsonld/type "urpx:BillingModel"
      :urpx/hasLedger [{:jsonld/type "urpx:Ledger" :jsonld/id "test:ledger-energy"
                        :urpx/name "Energy"
                        :urpx/ledgerType {:jsonld/id "urpx:supplyLedger"}
                        :urpx/referencesMetricInput [{:jsonld/id "test:mi-energy"}]}]
      :urpx/hasServiceTiers containers
      :urpx/hasPriceDefinition
      [{:jsonld/type "urpx:PriceDefinition" :jsonld/id "test:pd-energy" :urpx/name "Energy"
        :urpx/referencesLedger {:jsonld/id "test:ledger-energy"}}]}}
    :urpx/hasPrices
    {:jsonld/type "urpx:Prices"
     :urpx/hasPrice [{:jsonld/type "urpx:Price" :jsonld/id "test:price-t1" :urpx/name "Tier 1"
                      :urpx/serviceTierNumber 1 :urpx/unitPrice 0.20M
                      :urpx/referencesPriceDefinition {:jsonld/id "test:pd-energy"}}]}}})

(defn- with-alternatives
  "Add the one ProfileAlternatives container the ProfileAlternative cases select
  from. Two cells, which is the smallest set for which choosing one is a choice."
  [p]
  (update-in p [:urpx/hasRatePlanVersion :urpx/hasPlanElements]
             assoc :urpx/hasProfileAlternatives
             [{:jsonld/type "urpx:ProfileAlternatives" :jsonld/id "test:pas-income"
               :urpx/hasProfileAlternative
               [{:jsonld/type "urpx:ProfileAlternative" :jsonld/id "test:pa-care" :urpx/name "CARE"}
                {:jsonld/type "urpx:ProfileAlternative" :jsonld/id "test:pa-std" :urpx/name "Standard"}]}]))

(defn- tier-1
  "The resolved tier-1 entry, or nil."
  [p inst & [opts]]
  (->> (:urpx.resolved/ledgers (price/resolve-prices p inst opts))
       (mapcat :urpx.resolved/tiers)
       (filter :urpx.resolved/tier-number)
       first))

(defn- refusal
  "The :reason and ex-data of the refusal `f` throws, or nil if it returns."
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(defn- select [alternative]
  {:profile-alternatives {"test:pas-income" alternative}})

;; ---------------------------------------------------------------------------
;; The unqualified case, which is every tiered fixture
;; ---------------------------------------------------------------------------

(deftest an-unqualified-bracket-supplies-its-bounds
  (testing "one container, one tier, one bracket, neither node qualified. The
            shape of all 18 tiered fixture tiers, and the path that reads
            neither the date nor the selection, so nothing this namespace
            exercises can move what those filings resolve to."
    (let [t (tier-1 (plan [(container "test:c" [(bracket "test:b" 13.5M)])]) (at 2026 7 15))]
      (is (= 0M (:urpx.resolved/tier-lower-bound t)))
      (is (= 13.5M (:urpx.resolved/tier-upper-bound t)))
      (is (= "Tier 1" (:urpx.resolved/tier-name t)))
      (is (= {:jsonld/id "urpx:greaterThanOrEqualOperator"}
             (:urpx.resolved/tier-lower-bound-operator t)))
      (is (= {:jsonld/id "urpx:lessThanOrEqualOperator"}
             (:urpx.resolved/tier-upper-bound-operator t)))
      (is (nil? (:urpx.resolved/tier-bounds-unresolved t))))))

(deftest a-tier-number-naming-no-service-tier-is-unchanged
  (testing "a Price carrying urpx:serviceTierNumber 9 where no ServiceTier is
            numbered 9. The number still surfaces, because it is read off the
            Price before any container is consulted, and nothing else does."
    (let [p (assoc-in (plan [(container "test:c" [(bracket "test:b" 13.5M)])])
                      [:urpx/hasRatePlanVersion :urpx/hasPrices :urpx/hasPrice 0
                       :urpx/serviceTierNumber]
                      9)
          t (tier-1 p (at 2026 7 15))]
      (is (= 9 (:urpx.resolved/tier-number t)))
      (is (nil? (:urpx.resolved/tier-name t)))
      (is (nil? (:urpx.resolved/tier-lower-bound t)))
      (is (nil? (:urpx.resolved/tier-upper-bound t)))
      (is (nil? (:urpx.resolved/tier-bounds-unresolved t))))))

(deftest a-tier-with-no-brackets-surfaces-no-bounds-and-no-mark
  (testing "urpx:hasServiceBracket empty. ServiceTierShape forbids it at
            sh:minCount 1, but schema.clj types it as a plain vector and
            urpx.validate is a Malli validator, so a document that never met
            SHACL reaches resolution. Nothing is in doubt here: the filing
            states no bounds, so there is nothing to withhold."
    (let [t (tier-1 (plan [(container "test:c" [])]) (at 2026 7 15))]
      (is (= 1 (:urpx.resolved/tier-number t)))
      (is (= "Tier 1" (:urpx.resolved/tier-name t)))
      (is (nil? (:urpx.resolved/tier-lower-bound t)))
      (is (nil? (:urpx.resolved/tier-upper-bound t)))
      (is (nil? (:urpx.resolved/tier-bounds-unresolved t))))))

;; ---------------------------------------------------------------------------
;; Bracket scope, season
;; ---------------------------------------------------------------------------

(deftest seasonal-brackets-select-by-month
  (testing "the defect this issue is about. One tier, a summer bracket and a
            winter bracket; before selection the first one won at every
            instant, so the summer bound was reported in December."
    (let [p (plan [(container "test:c" [(bracket "test:b-s" 13.5M :season "test:season-summer")
                                        (bracket "test:b-w" 11.0M :season "test:season-winter")])])]
      (is (= 13.5M (:urpx.resolved/tier-upper-bound (tier-1 p (at 2026 7 15)))))
      (is (= 11.0M (:urpx.resolved/tier-upper-bound (tier-1 p (at 2026 12 15)))))
      (is (nil? (:urpx.resolved/tier-bounds-unresolved (tier-1 p (at 2026 12 15))))))))

(deftest a-single-season-ref-written-as-a-bare-map-is-normalized
  (testing "the coercion decoder produces a vector, but a document assembled in
            code need not have been through it, and reading a map as a
            collection of its entries would compare a MapEntry to an @id and
            silently match nothing."
    (let [p (plan [(container "test:c"
                              [(assoc (bracket "test:b-s" 13.5M)
                                      :urpx/appliesInSeason {:jsonld/id "test:season-summer"})])])]
      (is (= 13.5M (:urpx.resolved/tier-upper-bound (tier-1 p (at 2026 7 15)))))
      (is (nil? (:urpx.resolved/tier-bounds-unresolved (tier-1 p (at 2026 7 15))))))))

(deftest a-bracket-naming-several-seasons-is-disjunctive
  (testing "appliesInSeason is sh:minCount 0 with no sh:maxCount on
            ServiceBracketShape, so one bracket may name several Seasons and
            one admitting season is enough. This is what lets a single bracket
            node serve two filings whose Seasons are declared separately."
    (let [p (plan [(container "test:c"
                              [(assoc (bracket "test:b" 13.5M)
                                      :urpx/appliesInSeason
                                      [{:jsonld/id "test:season-summer"}
                                       {:jsonld/id "test:season-winter"}])])])]
      (is (= 13.5M (:urpx.resolved/tier-upper-bound (tier-1 p (at 2026 7 15)))))
      (is (= 13.5M (:urpx.resolved/tier-upper-bound (tier-1 p (at 2026 12 15))))))))

(deftest no-bracket-in-season-marks-rather-than-omits
  (testing "the tier's only bracket is summer-scoped, resolved in December. The
            filing states no bounds for this instant. Omitting them silently
            would be indistinguishable from an untiered plan, so the entry
            keeps its number and its unit price and says what is missing."
    (let [t (tier-1 (plan [(container "test:c" [(bracket "test:b-s" 13.5M
                                                         :season "test:season-summer")])])
                    (at 2026 12 15))]
      (is (= 1 (:urpx.resolved/tier-number t)))
      (is (= 0.20M (:urpx.resolved/unit-price t)))
      (is (nil? (:urpx.resolved/tier-lower-bound t)))
      (is (nil? (:urpx.resolved/tier-upper-bound t)))
      (is (= {:reason :no-applicable-bounds
              :scope  :service-bracket
              :ids    ["test:b-s"]}
             (:urpx.resolved/tier-bounds-unresolved t))))))

(deftest a-season-with-no-months-is-not-read-as-out-of-season
  (testing "SeasonShape sets urpx:month at sh:minCount 0 and offers
            startDate/endDate instead. This library implements only the month
            gate, so such a Season leaves it unevaluated. Reporting
            :no-applicable-bounds would be a confident answer to a question
            never asked, and the distinction is the whole reason
            node-season-status returns four values rather than a boolean."
    (let [p (plan [(container "test:c" [(bracket "test:b-u" 13.5M
                                                 :season "test:season-undated")])])]
      (doseq [inst [(at 2026 7 15) (at 2026 12 15)]]
        (is (= {:reason :season-not-evaluable
                :scope  :service-bracket
                :ids    ["test:b-u"]}
               (:urpx.resolved/tier-bounds-unresolved (tier-1 p inst))))))))

(deftest a-monthless-season-beside-an-excluding-one-is-still-unevaluable
  (testing "one ref admits nothing in December and the other cannot be read at
            all. The node is not out of season: it is unknown, because the ref
            that could have admitted December was never evaluated."
    (let [p (plan [(container "test:c"
                              [(assoc (bracket "test:b" 13.5M)
                                      :urpx/appliesInSeason
                                      [{:jsonld/id "test:season-summer"}
                                       {:jsonld/id "test:season-undated"}])])])]
      (is (= :season-not-evaluable
             (:reason (:urpx.resolved/tier-bounds-unresolved (tier-1 p (at 2026 12 15))))))
      (testing "and it is in season in July, where the month-bearing ref admits"
        (is (= 13.5M (:urpx.resolved/tier-upper-bound (tier-1 p (at 2026 7 15)))))))))

;; ---------------------------------------------------------------------------
;; Bracket scope, ProfileAlternative
;; ---------------------------------------------------------------------------

(deftest the-chosen-alternatives-bracket-supplies-bounds
  (testing "two cells of one dimension, each with its own bounds. Choosing a
            cell picks its bracket; choosing the other picks the other."
    (let [p (with-alternatives
              (plan [(container "test:c" [(bracket "test:b-care" 13.5M :alternative "test:pa-care")
                                          (bracket "test:b-std" 11.0M :alternative "test:pa-std")])]))]
      (is (= 13.5M (:urpx.resolved/tier-upper-bound (tier-1 p (at 2026 7 15) (select "test:pa-care")))))
      (is (= 11.0M (:urpx.resolved/tier-upper-bound (tier-1 p (at 2026 7 15) (select "test:pa-std")))))
      (is (nil? (:urpx.resolved/tier-bounds-unresolved
                 (tier-1 p (at 2026 7 15) (select "test:pa-care"))))))))

(deftest bounds-are-withheld-when-no-alternative-is-chosen
  (testing "which cell applies is the caller's to say, so with nothing chosen
            the bounds are unknown rather than absent. Naming the cell clears
            it, which is why the mark carries the alternatives to choose from."
    (let [p (with-alternatives
              (plan [(container "test:c" [(bracket "test:b-care" 13.5M :alternative "test:pa-care")
                                          (bracket "test:b-std" 11.0M :alternative "test:pa-std")])]))
          t (tier-1 p (at 2026 7 15))]
      (is (= 1 (:urpx.resolved/tier-number t)))
      (is (= 0.20M (:urpx.resolved/unit-price t)))
      (is (nil? (:urpx.resolved/tier-upper-bound t)))
      (is (= {:reason       :alternative-unselected
              :scope        :service-bracket
              :ids          ["test:b-care" "test:b-std"]
              :alternatives ["test:pa-care" "test:pa-std"]}
             (:urpx.resolved/tier-bounds-unresolved t))))))

(deftest an-applying-bracket-does-not-answer-for-an-unknown-sibling
  (testing "an unqualified bracket beside a cell-scoped one is NOT a default.
            URPX orders nothing between them, so reading the unqualified one as
            the fallback would invent a specificity rule the shapes do not
            state. A withheld bound is cleared by naming the cell; a wrong
            bound already reported is not."
    (let [p (with-alternatives
              (plan [(container "test:c" [(bracket "test:b-any" 100M)
                                          (bracket "test:b-care" 50M :alternative "test:pa-care")])]))
          t (tier-1 p (at 2026 7 15))]
      (is (nil? (:urpx.resolved/tier-upper-bound t))
          "100 is the wrong answer here, not the safe one")
      (is (= :alternative-unselected
             (:reason (:urpx.resolved/tier-bounds-unresolved t))))
      (is (= ["test:pa-care"]
             (:alternatives (:urpx.resolved/tier-bounds-unresolved t)))))))

(deftest naming-the-cell-makes-the-disagreement-visible
  (testing "the counterpart of the above. With the cell named both brackets
            apply and disagree, which no caller can settle, so it refuses. Both
            callers are told the bounds are not determined, one by a mark and
            one by a refusal, so nothing is reported as settled that is not."
    (let [p (with-alternatives
              (plan [(container "test:c" [(bracket "test:b-any" 100M)
                                          (bracket "test:b-care" 50M :alternative "test:pa-care")])]))
          d (refusal #(tier-1 p (at 2026 7 15) (select "test:pa-care")))]
      (is (= :ambiguous-service-bracket (:reason d)))
      (is (= ["test:b-any" "test:b-care"] (:bracket-ids d))))))

;; ---------------------------------------------------------------------------
;; Container scope
;; ---------------------------------------------------------------------------

(deftest seasonal-containers-select-by-month
  (testing "the same rung declared by a summer container and a winter one. This
            is the shape that last-won on map insertion order before the index
            grouped its candidates."
    (let [p (plan [(container "test:c-s" [(bracket "test:b-s" 13.5M)] :season "test:season-summer")
                   (container "test:c-w" [(bracket "test:b-w" 11.0M)] :season "test:season-winter")])]
      (is (= 13.5M (:urpx.resolved/tier-upper-bound (tier-1 p (at 2026 7 15)))))
      (is (= 11.0M (:urpx.resolved/tier-upper-bound (tier-1 p (at 2026 12 15))))))))

(deftest the-chosen-alternatives-container-supplies-bounds
  (testing "container scope answers the selection exactly as bracket scope
            does, and the mark reports :service-tiers so a filer knows which
            node to look at."
    (let [p (with-alternatives
              (plan [(container "test:c-care" [(bracket "test:b1" 13.5M)] :alternative "test:pa-care")
                     (container "test:c-std" [(bracket "test:b2" 11.0M)] :alternative "test:pa-std")]))]
      (is (= 13.5M (:urpx.resolved/tier-upper-bound
                    (tier-1 p (at 2026 7 15) (select "test:pa-care")))))
      (is (= 11.0M (:urpx.resolved/tier-upper-bound
                    (tier-1 p (at 2026 7 15) (select "test:pa-std")))))
      (testing "and with nothing chosen the mark names the containers"
        (is (= {:reason       :alternative-unselected
                :scope        :service-tiers
                :ids          ["test:c-care" "test:c-std"]
                :alternatives ["test:pa-care" "test:pa-std"]}
               (:urpx.resolved/tier-bounds-unresolved (tier-1 p (at 2026 7 15)))))))))

(deftest a-tier-declared-only-by-an-excluded-container-is-marked
  (testing "one winter-only container, resolved in July. No container is in
            effect, so the open question is the tier rather than its bounds and
            the mark says :service-tiers."
    (let [t (tier-1 (plan [(container "test:c-w" [(bracket "test:b-w" 11.0M)]
                                      :season "test:season-winter")])
                    (at 2026 7 15))]
      (is (= {:reason :no-applicable-bounds
              :scope  :service-tiers
              :ids    ["test:c-w"]}
             (:urpx.resolved/tier-bounds-unresolved t))))))

(deftest a-tier-kept-by-one-container-is-not-marked-by-another
  (testing "a summer container carrying tiers 1 and 2 beside a winter container
            carrying only tier 1. In December tier 1 resolves the winter bounds
            with no mark, and tier 2, which only the summer container declares,
            is marked rather than dropped: its Price still resolves, so hiding
            the rung would understate the ladder."
    (let [summer (-> (container "test:c-s" [(bracket "test:b-s1" 13.5M)]
                                :season "test:season-summer")
                     (update :urpx/hasServiceTier conj
                             {:jsonld/type "urpx:ServiceTier" :jsonld/id "test:c-s-t2"
                              :urpx/serviceTierNumber 2 :urpx/name "Tier 2"
                              :urpx/hasServiceBracket [(bracket "test:b-s2" 54.0M)]}))
          winter (container "test:c-w" [(bracket "test:b-w1" 11.0M)]
                            :season "test:season-winter")
          p      (update-in (plan [summer winter])
                            [:urpx/hasRatePlanVersion :urpx/hasPrices :urpx/hasPrice]
                            conj
                            {:jsonld/type "urpx:Price" :jsonld/id "test:price-t2"
                             :urpx/name "Tier 2" :urpx/serviceTierNumber 2
                             :urpx/unitPrice 0.40M
                             :urpx/referencesPriceDefinition {:jsonld/id "test:pd-energy"}})
          tiers  (->> (:urpx.resolved/ledgers (price/resolve-prices p (at 2026 12 15)))
                      (mapcat :urpx.resolved/tiers)
                      (filter :urpx.resolved/tier-number)
                      (group-by :urpx.resolved/tier-number))
          t1     (first (tiers 1))
          t2     (first (tiers 2))]
      (is (= 11.0M (:urpx.resolved/tier-upper-bound t1)))
      (is (nil? (:urpx.resolved/tier-bounds-unresolved t1)))
      (is (= 2 (:urpx.resolved/tier-number t2)))
      (is (nil? (:urpx.resolved/tier-upper-bound t2)))
      (is (= :no-applicable-bounds
             (:reason (:urpx.resolved/tier-bounds-unresolved t2)))))))

;; ---------------------------------------------------------------------------
;; Refusals
;; ---------------------------------------------------------------------------

(deftest two-applying-brackets-refuse
  (testing "urpx:hasServiceBracket is a set, not an ordered list, so there is
            no first bracket to take. This is urpx.index/single's case one
            level down."
    (let [d (refusal #(tier-1 (plan [(container "test:c" [(bracket "test:b1" 10M)
                                                          (bracket "test:b2" 20M)])])
                              (at 2026 7 15)))]
      (is (= :ambiguous-service-bracket (:reason d)))
      (is (= 1 (:tier-number d)))
      (is (= "test:c-t1" (:service-tier-id d)))
      (is (= ["test:b1" "test:b2"] (:bracket-ids d)))
      (is (= 2 (:count d)))))

  (testing "and two season-scoped brackets whose Seasons both admit July are
            the same defect stated less obviously"
    (let [d (refusal #(tier-1 (plan [(container "test:c"
                                                [(bracket "test:b1" 10M :season "test:season-summer")
                                                 (assoc (bracket "test:b2" 20M)
                                                        :urpx/appliesInSeason
                                                        [{:jsonld/id "test:season-summer"}])])])
                              (at 2026 7 15)))]
      (is (= :ambiguous-service-bracket (:reason d))))))

(deftest brackets-agreeing-on-bounds-are-not-ambiguous
  (testing "several survivors stating the same four values determine the
            answer, so refusing would decline a question that has one. A
            filing may legitimately state one rung once per cell."
    (let [p (plan [(container "test:c" [(bracket "test:b1" 10M :season "test:season-summer")
                                        (assoc (bracket "test:b2" 10M)
                                               :urpx/appliesInSeason
                                               [{:jsonld/id "test:season-summer"}])])])]
      (is (= 10M (:urpx.resolved/tier-upper-bound (tier-1 p (at 2026 7 15)))))
      (is (nil? (:urpx.resolved/tier-bounds-unresolved (tier-1 p (at 2026 7 15)))))))

  (testing "and = on BigDecimal is scale-sensitive, so 0M against 0.0M and 10M
            against 10.0M must not read as a contradiction"
    (let [p (plan [(container "test:c" [(bracket "test:b1" 10M)
                                        (bracket "test:b2" 10.0M :lower 0.0M)])])]
      (is (= 10M (:urpx.resolved/tier-upper-bound (tier-1 p (at 2026 7 15))))))))

(deftest containers-declaring-the-same-tier-refuse
  (testing "two unqualified containers both numbering a tier 1 with different
            bounds. Before the candidate index this last-won on map insertion
            order, which is the silent pick the library refuses everywhere
            else."
    (let [d (refusal #(tier-1 (plan [(container "test:c1" [(bracket "test:b1" 10M)])
                                     (container "test:c2" [(bracket "test:b2" 20M)])])
                              (at 2026 7 15)))]
      (is (= :ambiguous-service-tier (:reason d)))
      (is (= 1 (:tier-number d)))
      (is (= ["test:c1" "test:c2"] (:container-ids d))
          "the containers, not their brackets")))

  (testing "and containers agreeing on the bounds do not refuse"
    (let [p (plan [(container "test:c1" [(bracket "test:b1" 10M)])
                   (container "test:c2" [(bracket "test:b2" 10M)])])]
      (is (= 10M (:urpx.resolved/tier-upper-bound (tier-1 p (at 2026 7 15))))))))

(deftest an-unresolvable-season-ref-refuses
  (testing "a ref naming a Season the document declares nowhere is a typo.
            Reading it as out-of-season would leave the rung with no bounds,
            which a consumer reads as unbounded."
    (let [p (plan [(container "test:c" [(bracket "test:b-x" 13.5M :season "test:season-typo")])])]
      (doseq [inst [(at 2026 7 15) (at 2026 12 15)]]
        (let [d (refusal #(tier-1 p inst))]
          (is (= :unresolvable-season-ref (:reason d)))
          (is (= "test:season-typo" (:season-id d)))
          (is (= "test:b-x" (:node-id d)))
          (is (= "urpx:ServiceBracket" (:node-type d)))))))

  (testing "a dangling ref beside one that admits the month still refuses: the
            document is wrong either way, and answering from the good ref would
            hide it"
    (let [p (plan [(container "test:c"
                              [(assoc (bracket "test:b" 13.5M)
                                      :urpx/appliesInSeason
                                      [{:jsonld/id "test:season-summer"}
                                       {:jsonld/id "test:season-typo"}])])])]
      (is (= :unresolvable-season-ref (:reason (refusal #(tier-1 p (at 2026 7 15))))))))

  (testing "a dangling ref on a cell the caller did NOT select still refuses.
            Evaluation is eager for this reason: a defect visible only to the
            caller who happens to name the broken cell can be found by no
            validator and no corpus sweep."
    (let [p (with-alternatives
              (plan [(container "test:c"
                                [(bracket "test:b-care" 13.5M :alternative "test:pa-care")
                                 (assoc (bracket "test:b-std" 11.0M :alternative "test:pa-std")
                                        :urpx/appliesInSeason [{:jsonld/id "test:season-typo"}])])]))
          d (refusal #(tier-1 p (at 2026 7 15) (select "test:pa-care")))]
      (is (= :unresolvable-season-ref (:reason d)))
      (is (= "test:b-std" (:node-id d)))))

  (testing "and a container naming an undeclared Season names itself"
    (let [d (refusal #(tier-1 (plan [(container "test:c" [(bracket "test:b" 13.5M)]
                                                :season "test:season-typo")])
                              (at 2026 7 15)))]
      (is (= :unresolvable-season-ref (:reason d)))
      (is (= "test:c" (:node-id d)))
      (is (= "urpx:ServiceTiers" (:node-type d))))))

(deftest a-bracket-naming-several-alternatives-names-itself
  (testing "pd-alternative-status is reached from a bracket now, so its two
            refusals report :node-id and :node-type where they reported
            :price-definition-id. Reachable only from a hand-built map:
            urpx.schema/ServiceBracket types referencesProfileAlternative on a
            ServiceBracket as a bare Ref, matching sh:maxCount 1, so a coerced
            document cannot carry two."
    (let [p (with-alternatives
              (plan [(container "test:c"
                                [(assoc (bracket "test:b" 13.5M)
                                        :urpx/referencesProfileAlternative
                                        [{:jsonld/id "test:pa-care"}
                                         {:jsonld/id "test:pa-std"}])])]))
          d (refusal #(tier-1 p (at 2026 7 15) (select "test:pa-care")))]
      (is (= :multiple-profile-alternatives (:reason d)))
      (is (= "urpx:ServiceBracket" (:node-type d)))
      (is (= "test:b" (:node-id d)))
      (is (not (contains? d :price-definition-id))))))

(deftest refusal-and-mark-hold-no-instant-valued-key
  (testing "urpx.schedule collapses equal refusals and equal intervals by
            value, so a key varying with the instant would split a window that
            says the same thing throughout. The mark names every node at its
            scope rather than the survivors, for this reason."
    (let [marked (plan [(container "test:c" [(bracket "test:b-s" 13.5M
                                                      :season "test:season-summer")])])
          broken (plan [(container "test:c" [(bracket "test:b-x" 13.5M
                                                      :season "test:season-typo")])])]
      (is (= (:urpx.resolved/tier-bounds-unresolved (tier-1 marked (at 2026 11 15)))
             (:urpx.resolved/tier-bounds-unresolved (tier-1 marked (at 2026 12 15))))
          "two winter instants, one mark")
      (is (= (dissoc (refusal #(tier-1 broken (at 2026 7 15))) :message)
             (dissoc (refusal #(tier-1 broken (at 2026 12 15))) :message)))
      (doseq [v (vals (refusal #(tier-1 broken (at 2026 7 15))))]
        (is (not (instance? java.time.temporal.Temporal v)))))))

;; ---------------------------------------------------------------------------
;; What the change must not reach
;; ---------------------------------------------------------------------------

(deftest a-withheld-bound-does-not-refuse-the-marginal-sum
  (testing "marginal-unit-rate reduces a ladder by urpx:serviceTierNumber and
            reads no bound key, so a withheld bound cannot make the sum short
            or turn it into a refusal. tier-number is assoc'd before any
            bracket is read, which is what guarantees it."
    (let [withheld (with-alternatives
                     (plan [(container "test:c" [(bracket "test:b-care" 13.5M
                                                          :alternative "test:pa-care")])]))
          plain    (plan [(container "test:c" [(bracket "test:b" 13.5M)])])]
      (is (= 0.20M (price/marginal-unit-rate (price/resolve-prices withheld (at 2026 7 15)))))
      (is (= (price/marginal-unit-rate (price/resolve-prices plain (at 2026 7 15)))
             (price/marginal-unit-rate (price/resolve-prices withheld (at 2026 7 15))))))))

(deftest season-refs-resolve-through-the-by-id-index
  (testing "a plan's bracket names a Season its own PlanElements does not
            declare, so the gate falls back to :by-id, type-checked. In a
            composition that index spans the base plan and every modifier,
            which is how a base-plan bracket reaches a Season a modifier
            document declares; resolving alone it covers only this document, so
            the fallback is then a no-op. The widening runs both ways: a
            modifier's bracket naming the base plan's Season resolves in a
            composition too, which is how PG&E's baseline-tier overlay is
            evaluable at all."
    (let [p (update-in (plan [(container "test:c" [(bracket "test:b" 13.5M
                                                            :season "other:season-summer")])])
                       [:urpx/hasRatePlanVersion :urpx/hasPlanElements]
                       dissoc :urpx/hasSeasons)]
      (is (= 13.5M (:urpx.resolved/tier-upper-bound
                    (tier-1 p (at 2026 7 15)
                            {:by-id {"other:season-summer"
                                     {:jsonld/type "urpx:Season"
                                      :jsonld/id   "other:season-summer"
                                      :urpx/month  [6 7 8 9]}}}))))
      (testing "and with the Season supplied by nothing, this document declares
                none at all, so the ref is not a typo: the document that
                declares it was not passed. Unknown, cleared by composing or by
                supplying :by-id, which is the line pd-alternative-status
                already draws for an alternative no container here declares."
        (let [t (tier-1 p (at 2026 7 15))]
          (is (nil? (refusal #(tier-1 p (at 2026 7 15)))) "it does not refuse")
          (is (= :season-not-evaluable
                 (:reason (:urpx.resolved/tier-bounds-unresolved t))))
          (is (nil? (:urpx.resolved/tier-upper-bound t))))))))

(deftest a-season-ref-matching-none-of-the-declared-seasons-refuses
  (testing "the other half of that split. Here the document DOES declare
            Seasons and the ref matches none of them, so it is a typo rather
            than an absent document and the refusal stands. Without this
            distinction the two cases are indistinguishable and one of them has
            to be answered wrongly."
    (let [p (plan [(container "test:c" [(bracket "test:b" 13.5M
                                                 :season "test:season-typo")])])
          d (refusal #(tier-1 p (at 2026 7 15)))]
      (is (= :unresolvable-season-ref (:reason d)))
      (is (= "test:season-typo" (:season-id d))))

    (testing "and a ref that misses while a SIBLING document supplies seasons is
              still a typo, because something was reachable to match against"
      (let [p (update-in (plan [(container "test:c" [(bracket "test:b" 13.5M
                                                              :season "test:season-typo")])])
                         [:urpx/hasRatePlanVersion :urpx/hasPlanElements]
                         dissoc :urpx/hasSeasons)]
        (is (= :unresolvable-season-ref
               (:reason (refusal
                         #(tier-1 p (at 2026 7 15)
                                  {:by-id {"other:season-summer"
                                           {:jsonld/type "urpx:Season"
                                            :jsonld/id   "other:season-summer"
                                            :urpx/month  [6 7 8 9]}}})))))))))

(deftest pge-e-1-tier-bounds-are-instant-independent
  (testing "bracket qualifier scoping does not narrow PG&E E-1 to one season,
            and must not be read as having fixed it. E-1 carries its
            seasonality in tier NAMES and in six distinct serviceTierNumbers,
            with no appliesInSeason and no referencesProfileAlternative on any
            container or bracket, so it is outside both scopes. All six tiers surface at every instant,
            summer bounds beside winter ones, exactly as before."
    (let [tiers (fn [inst]
                  (->> (:urpx.resolved/ledgers
                        (price/resolve-prices (fixtures/load-rate-plan "pge-e-1-2026-03-01") inst))
                       (mapcat :urpx.resolved/tiers)
                       (filterv :urpx.resolved/tier-number)))
          july  (tiers (at 2026 7 15))
          dec-  (tiers (at 2026 12 15))]
      (is (= july dec-) "the same six tiers, the same bounds, at both instants")
      (is (= [1 2 3 4 5 6] (mapv :urpx.resolved/tier-number july)))
      (is (= [13.5M 54.0M nil 11.0M 44.0M nil]
             (mapv :urpx.resolved/tier-upper-bound july)))
      (is (not-any? :urpx.resolved/tier-bounds-unresolved july)))))

(deftest no-vendored-fixture-carries-a-bounds-mark
  (testing "the fixture-wide net. Every tiered base-plan fixture takes the
            unqualified short-circuit, so none of them reads the date, the
            seasons or the selection to find its bounds, and none can acquire
            the new key. A failure here means a fixture gained a qualifier and
            the claim that this change moves nothing needs re-measuring."
    (doseq [name- ["pge-e-1-2026-03-01" "ladwp-r-1a-2026-04-01" "sce-d-2026-03-20"
                   "sce-d-2025-11-15" "sdge-dr-2026-04-01"]
            inst  [(at 2026 7 15) (at 2026 12 15)]]
      (let [tiers (->> (:urpx.resolved/ledgers
                        (price/resolve-prices (fixtures/load-rate-plan name-) inst))
                       (mapcat :urpx.resolved/tiers))]
        (is (not-any? :urpx.resolved/tier-bounds-unresolved tiers)
            (str name- " at " inst))
        (is (some :urpx.resolved/tier-lower-bound tiers)
            (str name- " still surfaces bounds at " inst))))))

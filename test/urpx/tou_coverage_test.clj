(ns urpx.tou-coverage-test
  "An instant no period of a urpx:TOUSchedule covers. On a base plan it refuses
  `:urpx.price/no-tou-period` (URPX-hlv). On a modifier its period-bound Prices
  contribute nothing and the rest still apply (URPX-zoq). A modifier's bracket
  may name a Season only the base plan declares (URPX-pbq).

  The period resolved to nil before either, and a nil period gates no Price
  carrying urpx:touPeriodNumber, so every period's Price surfaced at once. A
  plan or modifier with no schedule keeps resolving with no period."
  (:require [clojure.test :refer [deftest is testing]]
            [urpx.price :as price]
            [urpx.schedule :as schedule])
  (:import [java.time LocalDate LocalDateTime LocalTime ZoneId ZonedDateTime]))

(def ^:private la (ZoneId/of "America/Los_Angeles"))

(defn- at [y m d h] (ZonedDateTime/of y m d h 0 0 0 la))

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

(defn- bracket [start end & {:as extra}]
  (merge {:jsonld/type "urpx:TimeBracket"
          :urpx/appliesInSeason [{:jsonld/id "test:season-summer"}
                                 {:jsonld/id "test:season-winter"}]
          :urpx/dayType {:jsonld/id "urpx:allDays"}
          :urpx/startTime (LocalTime/of start 0)
          :urpx/endTime   (LocalTime/of end 0)}
         extra))

(def ^:private peak-bracket (bracket 16 21))
(def ^:private off-bracket  (bracket 21 16))

(defn- tou-schedule
  "Peak (1) and Off-peak (2), each with the brackets given."
  [id peak-brackets off-brackets]
  [{:jsonld/type "urpx:TOUSchedule" :jsonld/id id :urpx/name "Schedule"
    :urpx/referencesMetricInput [{:jsonld/id "test:mi-energy"}]
    :urpx/hasTOUTier
    [{:jsonld/type "urpx:TOUTier" :urpx/touTierNumber 1
      :urpx/hasTOUPeriod
      [{:jsonld/type "urpx:TOUPeriod" :jsonld/id (str id "-peak") :urpx/name "Peak"
        :urpx/touPeriodNumber 1 :urpx/hasTimeBracket peak-brackets}
       {:jsonld/type "urpx:TOUPeriod" :jsonld/id (str id "-off") :urpx/name "Off-peak"
        :urpx/touPeriodNumber 2 :urpx/hasTimeBracket off-brackets}]}]}])

(def ^:private full-schedule [[peak-bracket] [off-bracket]])

(defn- version-body
  "PlanElements and Prices for one ledger, one PriceDefinition carrying no
  period, and one Price per period carrying urpx:touPeriodNumber. With
  `flat-price`, also one Price carrying no period."
  [prefix {:keys [seasons schedule flat-price]}]
  {:urpx/changeReason {:jsonld/id "urpx:rateCase"}
   :urpx/hasCoverageClaim {:jsonld/type "urpx:CoverageClaim"
                           :urpx/completenessResult {:jsonld/id "urpx:complete"}}
   :urpx/serviceRole {:jsonld/id "urpx:bundledServiceRole"}
   :urpx/effectiveDateTime (LocalDateTime/of 2025 1 1 0 0)
   :urpx/priceCurrency "USD"
   :urpx/hasPlanElements
   (cond-> {:jsonld/type "urpx:PlanElements"
            :urpx/hasMetricInput [metric-input]
            :urpx/hasBillingModel
            (cond-> {:jsonld/type "urpx:BillingModel"
                     :urpx/hasLedger [{:jsonld/type "urpx:Ledger" :jsonld/id (str prefix "-ledger")
                                       :urpx/name "Energy"
                                       :urpx/ledgerType {:jsonld/id "urpx:bundledLedger"}
                                       :urpx/referencesMetricInput [{:jsonld/id "test:mi-energy"}]}]
                     :urpx/hasPriceDefinition
                     [{:jsonld/type "urpx:PriceDefinition" :jsonld/id (str prefix "-pd")
                       :urpx/name "Energy" :urpx/referencesLedger {:jsonld/id (str prefix "-ledger")}}]}
              schedule (assoc :urpx/hasTOUSchedule
                              (apply tou-schedule (str prefix "-tou") schedule)))}
     (seq seasons) (assoc :urpx/hasSeasons seasons))
   :urpx/hasPrices
   {:jsonld/type "urpx:Prices"
    :urpx/hasPrice
    (cond-> (vec (for [[n v] [[1 0.40M] [2 0.20M]]]
                   {:jsonld/type "urpx:Price" :jsonld/id (str prefix "-price-" n)
                    :urpx/name "Energy" :urpx/unitPrice v :urpx/touPeriodNumber n
                    :urpx/referencesPriceDefinition {:jsonld/id (str prefix "-pd")}}))
      flat-price (conj {:jsonld/type "urpx:Price" :jsonld/id (str prefix "-price-flat")
                        :urpx/name "Adder" :urpx/unitPrice 0.01M
                        :urpx/referencesPriceDefinition {:jsonld/id (str prefix "-pd")}}))}})

(defn- base-plan
  [& {:as opts}]
  {:jsonld/type "urpx:RatePlan" :jsonld/id "test:base"
   :urpx/timezoneIdentifier "America/Los_Angeles"
   :urpx/ratePlanUniqueIdentifier "TEST-BASE" :urpx/name "Base"
   :urpx/initialEffectiveDate (LocalDate/of 2025 1 1)
   :urpx/hasRatePlanVersion
   (merge {:jsonld/type "urpx:RatePlanVersion" :jsonld/id "test:base-v1"
           :urpx/forRatePlan {:jsonld/id "test:base"}}
          (version-body "test:base" (merge {:seasons [summer winter]} opts)))})

(defn- modifier
  [& {:as opts}]
  {:jsonld/type "urpx:RatePlanModifier" :jsonld/id "test:mod"
   :urpx/timezoneIdentifier "America/Los_Angeles"
   :urpx/name "Modifier" :urpx/initialEffectiveDate (LocalDate/of 2025 1 1)
   :urpx/hasRatePlanModifierVersion
   (merge {:jsonld/type "urpx:RatePlanModifierVersion" :jsonld/id "test:mod-v1"
           :urpx/forRatePlanModifier {:jsonld/id "test:mod"}
           :urpx/applicableToRatePlan [{:jsonld/id "test:base"}]}
          (version-body "test:mod" (merge {:seasons [summer winter]} opts)))})

(defn- ledger-price-ids [r ledger-id]
  (->> (:urpx.resolved/ledgers r)
       (filter #(= ledger-id (:urpx.resolved/ledger-id %)))
       (mapcat :urpx.resolved/tiers)
       (map :urpx.resolved/price-id)
       sort vec))

(defn- refusal [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(def ^:private peak-gap [[] [off-bracket]])

(deftest a-modifier-schedule-gap-contributes-nothing
  (let [m (modifier :schedule peak-gap)]
    (testing "composed: 17:00 matches no bracket of the modifier's own schedule"
      (let [r (price/resolve-prices-with-modifiers (base-plan) [m] (at 2026 7 15 17))]
        (is (= [] (ledger-price-ids r "test:mod-ledger")))
        (is (= ["test:base-price-1" "test:base-price-2"] (ledger-price-ids r "test:base-ledger"))
            "the base plan resolves as without the modifier")))
    (testing "standalone, the same"
      (is (= [] (:urpx.resolved/ledgers (price/resolve-modifier-prices m (at 2026 7 15 17))))))
    (testing "an instant the schedule covers still resolves to its one Price"
      (is (= ["test:mod-price-2"]
             (ledger-price-ids (price/resolve-prices-with-modifiers (base-plan) [m] (at 2026 7 15 22))
                               "test:mod-ledger"))))))

(deftest a-modifier-gap-keeps-prices-carrying-no-period
  (let [m (modifier :schedule peak-gap :flat-price true)]
    (testing "in the gap only the Price carrying no period applies, with no period labels"
      (let [[ledger :as ledgers] (:urpx.resolved/ledgers
                                  (price/resolve-modifier-prices m (at 2026 7 15 17)))]
        (is (= 1 (count ledgers)))
        (is (= ["test:mod-price-flat"] (map :urpx.resolved/price-id (:urpx.resolved/tiers ledger))))
        (is (nil? (:urpx.resolved/tou-period-number ledger)))
        (is (= "Summer" (:urpx.resolved/season-name ledger)))))
    (testing "where a period covers, it applies alongside that period's Price"
      (is (= ["test:mod-price-2" "test:mod-price-flat"]
             (ledger-price-ids (price/resolve-modifier-prices m (at 2026 7 15 22))
                               "test:mod-ledger"))))))

(deftest a-holiday-no-modifier-bracket-covers-contributes-nothing
  (let [m (modifier :schedule [[(bracket 16 21 :urpx/includeHolidays false)]
                               [(bracket 21 16 :urpx/includeHolidays false)]])
        hol {:holiday? #{(LocalDate/of 2026 7 3)}}]
    (is (= [] (:urpx.resolved/ledgers (price/resolve-modifier-prices m (at 2026 7 3 17) hol)))
        "a holiday that every bracket excludes")
    (is (= ["test:mod-price-1"]
           (ledger-price-ids (price/resolve-modifier-prices m (at 2026 7 2 17) hol)
                             "test:mod-ledger"))
        "the day before, a non-holiday, resolves")))

(def ^:private summer-peak-schedule
  "Peak 16:00-21:00 in Summer only; Off-peak the rest of Summer and all Winter."
  [[(bracket 16 21 :urpx/appliesInSeason [{:jsonld/id "test:season-summer"}])]
   [(bracket 21 16 :urpx/appliesInSeason [{:jsonld/id "test:season-summer"}])
    (bracket 0 0 :urpx/appliesInSeason [{:jsonld/id "test:season-winter"}])]])

(deftest a-modifier-bracket-resolves-a-base-plan-season-through-the-composition
  (let [m (modifier :seasons nil :schedule summer-peak-schedule)
        mod-ids #(ledger-price-ids (price/resolve-prices-with-modifiers (base-plan) [m] %)
                                   "test:mod-ledger")]
    (is (= ["test:mod-price-1"] (mod-ids (at 2026 7 15 17))))
    (is (= ["test:mod-price-2"] (mod-ids (at 2026 7 15 22))))
    (is (= ["test:mod-price-2"] (mod-ids (at 2026 12 15 17)))
        "in Winter, by the base plan's months")
    (is (= 1 (->> (price/resolve-prices-with-modifiers (base-plan) [m] (at 2026 7 15 17))
                  :urpx.resolved/ledgers
                  (filter #(= "test:mod-ledger" (:urpx.resolved/ledger-id %)))
                  first
                  :urpx.resolved/tou-period-number)))))

(deftest a-modifier-bracket-season-resolving-nowhere-refuses
  (testing "standalone, the base plan's Seasons are not in scope"
    (let [d (refusal #(price/resolve-modifier-prices (modifier :seasons nil :schedule full-schedule)
                                                     (at 2026 7 15 17)))]
      (is (= :unresolvable-season-ref (:reason d)))
      (is (= "test:mod" (:plan-id d)))
      (is (= "test:mod-v1" (:version-id d)))
      (is (= #{"test:season-summer" "test:season-winter"} (set (:season-ids d))))))
  (testing "composed, a ref no document declares"
    (let [m (modifier :schedule [[(bracket 16 21 :urpx/appliesInSeason [{:jsonld/id "test:season-typo"}])]
                                 [off-bracket]])
          d (refusal #(price/resolve-prices-with-modifiers (base-plan) [m] (at 2026 7 15 22)))]
      (is (= :unresolvable-season-ref (:reason d)))
      (is (= ["test:season-typo"] (:season-ids d))))))

(deftest a-base-schedule-gap-refuses
  (let [p (base-plan :schedule peak-gap)]
    (let [d (refusal #(price/resolve-prices p (at 2026 7 15 17)))]
      (is (= :urpx.price/no-tou-period (:reason d)))
      (is (= "test:base" (:plan-id d)))
      (is (= "test:base-v1" (:version-id d)))
      (is (= ["test:base-tou"] (:schedule-ids d))))
    (is (= :urpx.price/no-tou-period
           (:reason (refusal #(price/resolve-prices-with-modifiers p [] (at 2026 7 15 17)))))
        "through the composed entry point too")
    (is (= ["test:base-price-2"]
           (ledger-price-ids (price/resolve-prices p (at 2026 7 15 22)) "test:base-ledger")))))

(deftest a-gap-is-one-refused-segment
  (testing "price-segments carries the refusal as :urpx.interval/refused, and
            the gap's equal refusals merge into one segment"
    (let [segs (schedule/price-segments (base-plan :schedule peak-gap) []
                                        (.toInstant (at 2026 7 15 0))
                                        (.toInstant (at 2026 7 16 0)))]
      (is (= [[0 nil] [16 :urpx.price/no-tou-period] [21 nil]]
             (mapv (juxt #(.getHour ^ZonedDateTime (:tick/beginning %))
                         #(get-in % [:urpx.interval/refused :reason]))
                   segs))))))

(deftest no-schedule-is-unaffected
  (testing "a base plan with no schedule resolves with no period"
    (let [r (price/resolve-prices (base-plan) (at 2026 7 15 17))]
      (is (nil? (:urpx.resolved/tou-period-number r)))
      (is (= ["test:base-price-1" "test:base-price-2"]
             (ledger-price-ids r "test:base-ledger")))))
  (testing "a modifier with no schedule, standalone, surfaces every Price"
    (is (= ["test:mod-price-1" "test:mod-price-2"]
           (ledger-price-ids (price/resolve-modifier-prices (modifier) (at 2026 7 15 17))
                             "test:mod-ledger")))))

(deftest a-fully-covered-modifier-gives-one-price-per-ledger-all-year
  (let [p     (base-plan)
        hours (take-while #(.isBefore ^ZonedDateTime % (at 2027 1 1 0))
                          (iterate #(.plusHours ^ZonedDateTime % 1) (at 2026 1 1 0)))]
    (is (= 8760 (count hours)))
    (doseq [[label m] [["Seasons declared on the modifier" (modifier :schedule full-schedule)]
                       ["Seasons declared only on the base plan"
                        (modifier :seasons nil :schedule full-schedule)]]]
      (testing label
        (is (empty? (remove (fn [^ZonedDateTime z]
                              (= [(if (<= 16 (.getHour z) 20) "test:mod-price-1" "test:mod-price-2")]
                                 (ledger-price-ids (price/resolve-prices-with-modifiers p [m] z)
                                                   "test:mod-ledger")))
                            hours)))))))

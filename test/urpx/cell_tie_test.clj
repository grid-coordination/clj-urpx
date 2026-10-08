(ns urpx.cell-tie-test
  "Prices tied on cell and start: those whose every urpx:basedOnPrice candidate
  is absent from the composition are set aside, and exactly one must remain.
  Any other count leaves the cell's tier unvaluable, naming the tied Prices."
  (:require [clojure.test :refer [deftest is testing]]
            [urpx.price :as price])
  (:import [java.time LocalDate LocalDateTime ZoneId ZonedDateTime]))

(def ^:private la (ZoneId/of "America/Los_Angeles"))

(def ^:private q (ZonedDateTime/of 2026 7 15 17 0 0 0 la))

(def ^:private jan (LocalDate/of 2026 1 1))
(def ^:private apr (LocalDate/of 2026 4 1))

(defn- version-common [ledger-id pd-id prices]
  {:urpx/changeReason {:jsonld/id "urpx:rateCase"}
   :urpx/hasCoverageClaim {:jsonld/type "urpx:CoverageClaim"
                           :urpx/completenessResult {:jsonld/id "urpx:complete"}}
   :urpx/serviceRole {:jsonld/id "urpx:bundledServiceRole"}
   :urpx/effectiveDateTime (LocalDateTime/of 2025 1 1 0 0)
   :urpx/priceCurrency "USD"
   :urpx/hasPlanElements
   {:jsonld/type "urpx:PlanElements"
    :urpx/hasBillingModel
    {:jsonld/type "urpx:BillingModel"
     :urpx/hasLedger [{:jsonld/type "urpx:Ledger" :jsonld/id ledger-id
                       :urpx/name ledger-id
                       :urpx/ledgerType {:jsonld/id "urpx:bundledLedger"}}]
     :urpx/hasPriceDefinition [{:jsonld/type "urpx:PriceDefinition" :jsonld/id pd-id
                                :urpx/name pd-id
                                :urpx/referencesLedger {:jsonld/id ledger-id}}]}}
   :urpx/hasPrices {:jsonld/type "urpx:Prices" :urpx/hasPrice prices}})

(defn- base-plan
  "A RatePlan `id` with one usage Price, `<id>:usage`, at `unit`."
  [id unit]
  {:jsonld/type "urpx:RatePlan" :jsonld/id id
   :urpx/timezoneIdentifier "America/Los_Angeles"
   :urpx/ratePlanUniqueIdentifier id
   :urpx/name id
   :urpx/initialEffectiveDate (LocalDate/of 2025 1 1)
   :urpx/hasRatePlanVersion
   (merge (version-common (str id ":ledger") (str id ":pd")
                          [{:jsonld/type "urpx:Price" :jsonld/id (str id ":usage")
                            :urpx/unitPrice (BigDecimal. ^String unit)
                            :urpx/referencesPriceDefinition {:jsonld/id (str id ":pd")}
                            :urpx/startDate jan}])
          {:jsonld/type "urpx:RatePlanVersion" :jsonld/id (str id "-v1")
           :urpx/forRatePlan {:jsonld/id id}})})

(def ^:private plan-a (base-plan "a" "0.30"))
(def ^:private plan-b (base-plan "b" "0.20"))

(defn- credit
  "A credit Price on the modifier's one PriceDefinition: -1 times the first of
  `bases`, or a plain `unit` when `bases` is empty."
  ([id start bases] (credit id start bases nil))
  ([id start bases extra]
   (merge {:jsonld/type "urpx:Price" :jsonld/id id
           :urpx/referencesPriceDefinition {:jsonld/id "m:pd"}
           :urpx/startDate start}
          (if (seq bases)
            {:urpx/multiplier -1M
             :urpx/basedOnPrice (mapv #(hash-map :jsonld/id %) bases)}
            {:urpx/unitPrice -0.05M})
          extra)))

(defn- modifier [prices]
  {:jsonld/type "urpx:RatePlanModifier" :jsonld/id "m"
   :urpx/timezoneIdentifier "America/Los_Angeles"
   :urpx/name "Credit"
   :urpx/initialEffectiveDate (LocalDate/of 2025 1 1)
   :urpx/hasRatePlanModifierVersion
   (merge (version-common "m:ledger" "m:pd" prices)
          {:jsonld/type "urpx:RatePlanModifierVersion" :jsonld/id "m-v1"
           :urpx/forRatePlanModifier {:jsonld/id "m"}
           :urpx/applicableToRatePlan [{:jsonld/id "a"} {:jsonld/id "b"}]})})

(def ^:private per-base
  "The minimal repro: one Price per base plan, same cell, same start."
  (modifier [(credit "m:credit-a" jan ["a:usage"])
             (credit "m:credit-b" jan ["b:usage"])]))

(defn- credit-tiers
  "The credit tiers `mod` surfaces, composed with `base` or, when nil, alone."
  [base mod]
  (->> (:urpx.resolved/ledgers (if base
                                 (price/resolve-prices-with-modifiers base [mod] q)
                                 (price/resolve-modifier-prices mod q)))
       (filter #(= "m:ledger" (:urpx.resolved/ledger-id %)))
       (mapcat :urpx.resolved/tiers)))

(defn- credits
  "[price-id unit-price] of each credit tier."
  [base mod]
  (mapv (juxt :urpx.resolved/price-id :urpx.resolved/unit-price) (credit-tiers base mod)))

(deftest the-price-whose-base-is-composed-is-valued
  (testing "composed with the second base, the second Price is valued"
    (is (= [["m:credit-b" -0.20M]] (credits plan-b per-base))))
  (testing "composed with the first base, the first Price is valued"
    (is (= [["m:credit-a" -0.30M]] (credits plan-a per-base)))))

(deftest a-price-with-no-basis-is-always-applicable
  (let [mod (modifier [(credit "m:plain" jan [])
                       (credit "m:credit-b" jan ["b:usage"])])]
    (is (= [["m:plain" -0.05M]] (credits plan-a mod)))))

(deftest two-applicable-prices-refuse
  (let [mod        (modifier [(credit "m:plain" jan [])
                              (credit "m:credit-a" jan ["a:usage"])
                              (credit "m:credit-b" jan ["b:usage"])])
        [t :as ts] (credit-tiers plan-a mod)
        u          (:urpx.resolved/unvaluable t)]
    (is (= 1 (count ts)))
    (is (nil? (:urpx.resolved/unit-price t)))
    (is (= :ambiguous-cell (:reason u)))
    (is (= ["m:plain" "m:credit-a" "m:credit-b"] (:price-ids u)))
    (is (= ["m:plain" "m:credit-a"] (:applicable-price-ids u)))))

(deftest no-applicable-price-refuses
  (testing "resolved alone, neither base is present"
    (let [[t :as ts] (credit-tiers nil per-base)
          u          (:urpx.resolved/unvaluable t)]
      (is (= 1 (count ts)))
      (is (nil? (:urpx.resolved/unit-price t)))
      (is (= :no-applicable-price (:reason u)))
      (is (= ["m:credit-a" "m:credit-b"] (:price-ids u))))))

(deftest the-tie-break-holds-within-tier-and-period-cells
  (let [cell (fn [n] {:urpx/serviceTierNumber n :urpx/touPeriodNumber 1})
        mod  (modifier [(credit "m:t1-a" jan ["a:usage"] (cell 1))
                        (credit "m:t1-b" jan ["b:usage"] (cell 1))
                        (credit "m:t2-a" jan ["a:usage"] (cell 2))
                        (credit "m:t2-b" jan ["b:usage"] (cell 2))])]
    (is (= #{["m:t1-b" -0.20M] ["m:t2-b" -0.20M]} (set (credits plan-b mod))))
    (is (= #{["m:t1-a" -0.30M] ["m:t2-a" -0.30M]} (set (credits plan-a mod))))))

(deftest a-strictly-later-price-still-supersedes
  (let [mod (modifier [(credit "m:credit-a" jan ["a:usage"])
                       (credit "m:credit-b" jan ["b:usage"])
                       (credit "m:plain" apr [])])]
    (is (= [["m:plain" -0.05M]] (credits plan-a mod)))
    (is (= [["m:plain" -0.05M]] (credits plan-b mod))))
  (testing "and before it starts, the tie is broken by composition"
    (let [mod (modifier [(credit "m:credit-a" jan ["a:usage"])
                         (credit "m:credit-b" jan ["b:usage"])
                         (credit "m:plain" (LocalDate/of 2026 9 1) [])])]
      (is (= [["m:credit-b" -0.20M]] (credits plan-b mod))))))

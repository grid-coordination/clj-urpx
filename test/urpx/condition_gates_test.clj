(ns urpx.condition-gates-test
  "urpx:ConditionExpression gates that this library does not evaluate.

  A urpx:Price gated by a condition fires UNCONDITIONALLY at resolution,
  because nothing here evaluates the predicate. That is a silent assumption of
  true, epistemically the same position as an event-driven price: it may or may
  not apply. Event-driven prices have been marked since 0.19.0 and excluded
  from the marginal since; condition-gated ones were neither, and the two
  interact badly enough to produce a negative marginal rate.

  This marks them. It deliberately does NOT exclude them from any aggregate:
  export price matrices are gated by design, since the gate is how the matrix
  is indexed, so excluding wholesale
  would delete the filing rather than correct it. Marking costs nothing, breaks
  nothing, and lets a consumer compute a defensible number today."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.walk]
            [urpx.price :as price]
            [urpx.test-fixtures :as fixtures])
  (:import [java.time ZoneId ZonedDateTime]))

(def ^:private la (ZoneId/of "America/Los_Angeles"))
(defn- at ([] (at 7 15 17)) ([m d h] (ZonedDateTime/of 2026 m d h 0 0 0 la)))

(defn- prime [] (fixtures/load-rate-plan "sce-tou-d-prime-2026-03-20"))
(defn- prime-cpp [] (fixtures/load-modifier "sce-tou-d-prime-cpp-2026-03-20"))

(defn- tiers [r] (mapcat :urpx.resolved/tiers (:urpx.resolved/ledgers r)))
(defn- gated [r] (filter :urpx.resolved/condition-gated (tiers r)))

(deftest a-condition-gated-price-says-so
  (testing "SCE TOU-D-PRIME composed with its CPP modifier. Two credits apply at
            every instant because their gates are never evaluated: one on
            whether a CPP event was called today, one on whether the customer is
            grandfathered into dual participation. Both now say so."
    (let [r (price/resolve-prices-with-modifiers (prime) [(prime-cpp)] (at))
          g (gated r)]
      (is (= 2 (count g)))
      (is (= #{"sce:tou-d-prime-cpp-price-non-event-credit-2026-03-20"
               "sce:tou-d-prime-cpp-price-max-credit-2026-03-20"}
             (set (map :urpx.resolved/price-id g))))
      (is (every? #(seq (:urpx.resolved/condition-gated %)) g))
      (is (every? #(= :price (:urpx.resolved/gate-on %))
                  (mapcat :urpx.resolved/condition-gated g))
          "both gates hang off the Price, not its PriceDefinition")
      (is (some #(re-find #"non-CPP-Event" (str (:urpx.resolved/gate-name %)))
                (mapcat :urpx.resolved/condition-gated g))
          "and the gate carries its own name, so a consumer can see what was assumed"))))

(deftest the-negative-rate-is-refused-not-reported
  (testing "the rate itself is unchanged: marking decides nothing. What changes
            is that a consumer can now see WHY it is negative rather than having
            to reverse-engineer it from the tier list.

            0.80000 event charge, excluded as event-driven. Two credits summing
            -0.73964, applying on an assumption. Assumed in, the marginal is
            negative, which says the customer is paid to consume."
    (let [r (price/resolve-prices-with-modifiers (prime) [(prime-cpp)] (at))]
      (is (= :condition-gated-tier
             (try (price/marginal-unit-rate r) nil
                  (catch clojure.lang.ExceptionInfo e (:reason (ex-data e)))))
          "refused, rather than reporting the assumption as a number")
      (is (neg? (price/marginal-unit-rate r {:assume-conditions-hold? true}))
          "the assumed-in number is still reachable, and is still negative")
      (is (= 2 (count (gated r)))
          "and the two prices responsible are identifiable without the filing")
      (is (= (reduce + 0M (map :urpx.resolved/unit-price (gated r)))
             -0.73964M)
          "and they are exactly the credits that make it negative"))))

(deftest a-month-only-rule-is-not-a-gate
  (testing "urpx:ApplicabilityRule carries urpx:month as well as
            urpx:hasConditionExpression, and the month constraint IS evaluated,
            by pd-active-in-month?. Marking a rule that the resolver honors
            would report an assumption nobody made, which is the false positive
            that would make the flag useless on the filings that use months
            heavily. Six vendored fixtures carry month-only rules."
    (doseq [f ["ladwp-r-1a-2026-04-01" "pge-e-tou-c-2026-03-01" "sce-d-2026-03-20"]]
      (let [r (price/resolve-prices (fixtures/load-rate-plan f) (at))]
        (is (empty? (gated r))
            (str f " has month-only rules and no unevaluated condition"))))))

(deftest conditions-that-are-correct-by-construction-are-not-marked
  (testing "URPX requires a urpx:hasConditionExpression on every
            urpx:ProfileAlternative, and a urpx:EligibilityRule's condition
            answers who may take the tariff rather than which price applies.
            Neither hangs off a Price or a PriceDefinition, so reading only
            those two attachment points excludes both. Across the vendored
            fixtures that is 104 and 34 conditions respectively, none of which
            should be reported as an unevaluated gate."
    (let [r (price/resolve-prices (fixtures/load-rate-plan "pge-e-tou-c-2026-03-01") (at))]
      (is (seq (tiers r)) "sanity: the plan resolves")
      (is (empty? (gated r))
          "its ProfileAlternatives carry conditions on every alternative, and
           none of them is an unevaluated gate on a price"))))

(deftest the-gate-is-read-through-a-bare-reference
  (testing "an ApplicabilityRule may be stated inline or as a bare {@id} ref to
            one defined elsewhere, since RDF merges the two. Read without
            dereferencing, a bare ref carries no condition and the gate silently
            stops being reported, which is the same defect the month gate had."
    (let [kept "sce:tou-d-prime-cpp-2026-03-20-ar-non-event-credit"
          ;; the max-credit Price now REFERENCES the rule the other Price
          ;; defines inline, which is the shape RDF merges
          ref-form (clojure.walk/postwalk
                    (fn [n] (if (and (map? n) (= "urpx:Price" (:jsonld/type n))
                                     (= "sce:tou-d-prime-cpp-price-max-credit-2026-03-20"
                                        (:jsonld/id n)))
                              (assoc n :urpx/hasApplicabilityRule [{:jsonld/id kept}])
                              n))
                    (prime-cpp))
          r  (price/resolve-prices-with-modifiers (prime) [ref-form] (at))
          by-id (into {} (map (juxt :urpx.resolved/price-id identity)) (gated r))
          max-gate (-> by-id
                       (get "sce:tou-d-prime-cpp-price-max-credit-2026-03-20")
                       :urpx.resolved/condition-gated first)]
      (is (= 2 (count (gated r)))
          "still two, read through the reference rather than lost with it")
      (is (= kept (:urpx.resolved/gate-id max-gate)))
      (is (re-find #"non-CPP-Event" (str (:urpx.resolved/gate-name max-gate)))
          "and carries the referenced rule's own name, so the dereference
           genuinely happened rather than the bare ref being reported as-is"))))

(deftest exclusivity-filed-as-predicates-is-refused-like-exclusivity-filed-as-alternatives
  (testing "the bigger half of this, and the one that is really a modeling
            problem. SCE's CCA-CRS files twenty PCIA vintages as twenty
            condition-gated prices, 'Applies when customer's CCA Vintage
            Year = 2001' and so on. Exactly one applies to any customer.

            PG&E files the SAME concept as urpx:PriceSetAlternatives, and this
            function has refused that since 0.20.0 because the exclusivity is
            structurally visible. SCE's is invisible, so all twenty were summed
            and SCE TOU-D-PRIME went from 0.60067 to 0.95664 where the true
            range is 0.59423 to 0.62691.

            Refusing both means the answer no longer depends on which layer a
            filer happened to express exclusivity in. That filing is not
            vendored, so the case is constructed from the two gated SCE CPP
            prices, which exercise the same guard."
    (let [r (price/resolve-prices-with-modifiers (prime) [(prime-cpp)] (at))
          summed (price/marginal-unit-rate r {:assume-conditions-hold? true})
          base   (price/marginal-unit-rate (price/resolve-prices (prime) (at)))]
      (is (= :condition-gated-tier
             (try (price/marginal-unit-rate r) nil
                  (catch clojure.lang.ExceptionInfo e (:reason (ex-data e))))))
      (is (not= summed base)
          "the gated prices genuinely move the number, so the refusal is not academic")
      (is (= 2 (count (:gated (ex-data (try (price/marginal-unit-rate r)
                                            (catch clojure.lang.ExceptionInfo e e))))))
          "and the ex-data names every price the caller has to account for"))))

(deftest a-gated-export-price-does-not-refuse-an-import-rate
  (testing "the guard is scoped to tiers that would actually enter the sum.
            Export prices in NBT matrices are condition-gated because the gate is
            how the matrix is indexed by vintage, month and hour.
            urpx:flowDirection urpx:reverse has kept those out of the marginal
            since 0.21.0, so they must not refuse it either.

            Constructed by flipping the CPP modifier's own MetricSpecification
            to urpx:reverse, so its two gated prices become export while the
            base plan keeps a real import ledger. Scoping the guard to every
            tier rather than to the summed ones would refuse this, and every
            filing carrying an export matrix with it."
    (let [exported (clojure.walk/postwalk
                    (fn [n] (if (and (map? n) (= "urpx:MetricSpecification" (:jsonld/type n)))
                              (assoc n :urpx/flowDirection {:jsonld/id "urpx:reverse"})
                              n))
                    (prime-cpp))
          r (price/resolve-prices-with-modifiers (prime) [exported] (at))]
      (is (some #(= "urpx:reverse" (get-in % [:urpx.resolved/flow-direction :jsonld/id]))
                (:urpx.resolved/ledgers r))
          "sanity: the gated prices really did become export-direction")
      (is (seq (filter :urpx.resolved/condition-gated
                       (mapcat :urpx.resolved/tiers (:urpx.resolved/ledgers r))))
          "sanity: and they are still marked as gated, so the guard could see them")
      (is (= 0.60067M (price/marginal-unit-rate r))
          "the import rate answers, because no gated price enters the sum"))))

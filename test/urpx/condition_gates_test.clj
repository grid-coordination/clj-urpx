(ns urpx.condition-gates-test
  "urpx:ConditionExpression gates that this library does not evaluate.

  A urpx:Price gated by a condition fires UNCONDITIONALLY at resolution,
  because nothing here evaluates the predicate. That is a silent assumption of
  true, epistemically the same position as an event-driven price: it may or may
  not apply. Event-driven prices have been marked since 0.19.0 and excluded
  from the marginal since; condition-gated ones were neither, and the two
  interact badly enough to produce a negative marginal rate.

  This marks them. It deliberately does NOT exclude them from any aggregate:
  most of the gated population across the wider corpus is export price
  matrices, where the gate is how the matrix is indexed, so excluding wholesale
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

(deftest the-mark-is-what-explains-the-negative-rate
  (testing "the rate itself is unchanged: marking decides nothing. What changes
            is that a consumer can now see WHY it is negative rather than having
            to reverse-engineer it from the tier list.

            0.80000 event charge, excluded as event-driven. Two credits summing
            -0.73964, applying on an assumption. The marginal is negative, which
            says the customer is paid to consume, and it is wrong."
    (let [r (price/resolve-prices-with-modifiers (prime) [(prime-cpp)] (at))]
      (is (neg? (price/marginal-unit-rate r))
          "unchanged by this release: still negative, still wrong")
      (is (= 2 (count (gated r)))
          "but the two prices responsible are now identifiable without the filing")
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

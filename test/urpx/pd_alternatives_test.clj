(ns urpx.pd-alternatives-test
  "urpx:referencesProfileAlternative on a urpx:PriceDefinition.

  URPX names PriceDefinition explicitly as a place to scope applicability to a
  ProfileAlternative: \"Use on PriceSetAlternative, PriceDefinition,
  TOUSchedule, ServiceTiers, or ServiceBracket.\" clj-urpx read it only on
  PriceSetAlternative, so the slot existed in the vocabulary, was accepted by
  the shapes at sh:minCount 0 with no sh:maxCount, and did nothing.

  It matters because it is where declarative exclusivity lands. URPX separates
  a SELECTION layer (ProfileAlternatives, mutually exclusive options requiring a
  choice) from an automatic FILTERING layer (ApplicabilityRule with a
  ConditionExpression). A set of prices made exclusive only by predicates is
  invisible as exclusive to a consumer that cannot evaluate them, and gets
  summed. Scoping the definitions to alternatives makes the exclusivity
  readable without evaluating anything.

  No vendored fixture uses the slot, so every case here is constructed. That is
  the point: the slot has to work before a filing can be written against it."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.walk]
            [urpx.coerce :as coerce]
            [urpx.index :as index]
            [urpx.price :as price]
            [urpx.schema :as schema]
            [urpx.test-fixtures :as fixtures])
  (:import [java.time ZoneId ZonedDateTime]))

(def ^:private la (ZoneId/of "America/Los_Angeles"))
(defn- at [] (ZonedDateTime/of 2026 7 15 17 0 0 0 la))
(def ^:private target "pge:pd-gen-summer-peak")

(defn- fixture []
  (let [plan (fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")
        pas  (first (vals (index/entities-of-type plan "urpx:ProfileAlternatives")))]
    {:plan plan
     :dim  (:jsonld/id pas)
     :alts (mapv :jsonld/id (:urpx/hasProfileAlternative pas))}))

(defn- scope-pd
  "Point the generation PriceDefinition at `alt-ids`, changing nothing else."
  [doc alt-ids]
  (clojure.walk/postwalk
   (fn [n] (if (and (map? n) (= target (:jsonld/id n))
                    (= "urpx:PriceDefinition" (:jsonld/type n)))
             (assoc n :urpx/referencesProfileAlternative
                    (mapv (fn [a] {:jsonld/id a}) alt-ids))
             n))
   doc))

(defn- resolved [doc sel]
  (price/resolve-prices doc (at) (when sel {:profile-alternatives sel})))

(defn- tiers-of [r]
  (->> (:urpx.resolved/ledgers r)
       (mapcat :urpx.resolved/tiers)
       (filter #(= target (:urpx.resolved/price-definition-id %)))))

(defn- marked? [r]
  (boolean (some #(some #{target} (:urpx.resolved/unselected-alternatives %))
                 (:urpx.resolved/ledgers r))))

(deftest a-definition-scoped-to-the-chosen-alternative-applies
  (testing "the selection map is keyed by the ProfileAlternatives CONTAINER,
            because a container is one dimension of choice, while a
            PriceDefinition references the ALTERNATIVE it applies to. The index
            bridging them needs no new vocabulary: urpx:hasProfileAlternative
            already links a container to its cells."
    (let [{:keys [plan dim alts]} (fixture)
          unscoped (resolved plan nil)
          scoped   (resolved (scope-pd plan [(first alts)]) {dim (first alts)})]
      (is (= 1 (count (tiers-of unscoped))) "sanity: the definition prices something")
      (is (= 1 (count (tiers-of scoped))) "and still does when its cell is chosen")
      (is (false? (marked? scoped)))
      (is (= (price/marginal-unit-rate unscoped) (price/marginal-unit-rate scoped))
          "choosing the cell a definition is scoped to changes nothing"))))

(deftest choosing-a-different-alternative-filters-it-out-silently
  (testing "a definition scoped to one cell does not apply when the caller
            chose a different cell in the same dimension. That is a FILTER, not
            a refusal and not a loss: the definition genuinely does not apply,
            exactly as an out-of-season one does not, and nothing is owed to
            the caller beyond leaving it out."
    (let [{:keys [plan dim alts]} (fixture)
          other (resolved (scope-pd plan [(first alts)]) {dim (second alts)})]
      (is (zero? (count (tiers-of other))))
      (is (false? (marked? other)) "not marked: this is not an unknown, it is a no")
      (is (some? (price/marginal-unit-rate other))
          "and the rate answers, being a rate for a different customer"))))

(deftest choosing-nothing-withholds-and-says-so
  (testing "with no selection in that dimension, which definition applies is
            UNKNOWN. Resolving all of them would be the silent wrong answer, so
            the prices are withheld and the ledger carries
            :urpx.resolved/unselected-alternatives naming the definition.

            The rate refuses here because the generation ledger is
            energy-measured; a withheld non-energy ledger would not refuse a
            per-kWh figure."
    (let [{:keys [plan alts]} (fixture)
          none (resolved (scope-pd plan [(first alts)]) nil)]
      (is (zero? (count (tiers-of none))))
      (is (true? (marked? none)))
      (is (= :unselected-alternatives
             (try (price/marginal-unit-rate none) nil
                  (catch clojure.lang.ExceptionInfo e (:reason (ex-data e)))))))))

(deftest several-references-refuse-rather-than-combine
  (testing "PriceDefinitionShape permits several (sh:minCount 0, no
            sh:maxCount) and no vendored fixture uses them. A matrix does not
            need them: URPX puts multi-dimensionality in ONE
            ProfileAlternatives, whose single AlternativeDimensions wraps
            several dimensions and whose alternatives enumerate the CELLS of
            the cross product. PG&E's own baseline territory/code set is 2
            dimensions and 20 alternatives.

            So the conjunction is already inside the cell, and defining
            semantics for several references would be inventing a rule for a
            shape nothing has. Refusing keeps the door open; picking one would
            be the arbitrary pick this library keeps removing."
    (let [{:keys [plan dim alts]} (fixture)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"scopes to ONE alternative"
                            (resolved (scope-pd plan [(first alts) (second alts)])
                                      {dim (first alts)})))
      (let [d (try (resolved (scope-pd plan [(first alts) (second alts)]) {dim (first alts)}) nil
                   (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (is (= :multiple-profile-alternatives (:reason d)))
        (testing "and it names the node by type, not as a PriceDefinition by
                  assumption. Bracket selection hands pd-alternative-status
                  urpx:ServiceTiers containers and urpx:ServiceBracket nodes as
                  well, so :price-definition-id named the wrong thing for two
                  of the three and became :node-id plus :node-type."
          (is (= "urpx:PriceDefinition" (:node-type d)))
          (is (some? (:node-id d)))
          (is (not (contains? d :price-definition-id))))))))

(deftest an-unscoped-definition-is-unaffected
  (testing "every vendored fixture, since none uses the slot"
    (let [{:keys [plan]} (fixture)]
      (is (= 0.46886M (price/marginal-unit-rate (resolved plan nil))))
      (is (false? (marked? (resolved plan nil)))))))

(defn- dynamize
  "Make every Price under the generation definition event-driven, the shape all
   sixteen CPAU E-HRA prices have."
  [doc]
  (let [pids (into #{} (comp (filter #(and (map? %) (= "urpx:Price" (:jsonld/type %))
                                           (= target (get-in % [:urpx/referencesPriceDefinition
                                                                :jsonld/id]))))
                             (map :jsonld/id))
                   (tree-seq coll? seq doc))]
    (clojure.walk/postwalk
     (fn [n] (if (and (map? n) (contains? pids (:jsonld/id n)))
               (assoc n :urpx/hasDynamicPricing {:jsonld/id "x:event"
                                                 :jsonld/type "urpx:DynamicPricing"})
               n))
     doc)))

(defn- rate [r opts]
  (try (price/marginal-unit-rate r opts)
       (catch clojure.lang.ExceptionInfo e (:reason (ex-data e)))))

(deftest withholding-a-price-the-sum-would-not-have-taken-refuses-nothing
  (testing "the refusal is owed when withholding makes the sum SHORT, not
            whenever something is withheld. An event-driven price is already
            excluded from a non-event marginal, so hiding it changes no number.

            Refusing anyway would be unclearable in practice. CPAU's E-HRA cells
            are the utility's own Hydro Stabilization Reserve level and
            projected generation band, reset by City Council vote: a consumer
            has no source for either, so the selection the refusal demands could
            never be supplied, and a correct rate would become a permanent
            question. Vintage-PCIA is the case that keeps the refusal, being an
            ordinary per-kWh price and an attribute of the customer."
    (let [{:keys [plan alts]} (fixture)
          dyn  (dynamize plan)
          none (resolved (scope-pd dyn [(first alts)]) nil)]
      (is (= 0.26104M (rate (resolved dyn nil) nil))
          "sanity: unscoped, the event price is excluded and the baseline answers")
      (is (true? (marked? none))
          "still marked: the caller can still see what was withheld")
      (is (= 0.26104M (rate none nil))
          "and the same baseline answers, because nothing summable was withheld")
      (is (= :unselected-alternatives (rate none {:include-dynamic-pricing? true}))
          "but the during-event upper bound DOES want that price, so it refuses"))))

(deftest what-was-withheld-is-on-the-ledger
  (testing ":urpx.resolved/unselected-alternatives says that something was
            withheld; :urpx.resolved/withheld-prices says what, in the same
            keys a tier uses, which is what lets the refusal above be decided
            by the same predicate that excludes a tier from the sum."
    (let [{:keys [plan alts]} (fixture)
          none (resolved (scope-pd (dynamize plan) [(first alts)]) nil)
          l    (->> (:urpx.resolved/ledgers none)
                    (filter #(some #{target} (:urpx.resolved/unselected-alternatives %)))
                    first)]
      (is (= [target] (:urpx.resolved/unselected-alternatives l)))
      (is (= 1 (count (:urpx.resolved/withheld-prices l))))
      (is (= target (-> l :urpx.resolved/withheld-prices first
                        :urpx.resolved/price-definition-id)))
      (is (some? (-> l :urpx.resolved/withheld-prices first
                     :urpx.resolved/dynamic-pricing))))))

(deftest the-single-object-form-normalizes-to-a-vector
  (testing "declaring the property on PriceDefinitionShape's Malli counterpart
            is what gives it the single-or-vector decode every other reference
            property has. Undeclared, it validated as an unknown key on an open
            map and passed through coerce and emit untouched, so a definition
            written the way URPX writes any lone reference, as one object
            rather than an array, arrived in a shape nothing reads."
    (let [pd {:jsonld/id "x:pd" :jsonld/type "urpx:PriceDefinition" :urpx/name "n"
              :urpx/referencesLedger {:jsonld/id "x:led"}
              :urpx/referencesProfileAlternative {:jsonld/id "x:alt"}}
          c  (coerce/coerce schema/PriceDefinition pd)]
      (is (= [{:jsonld/id "x:alt"}] (:urpx/referencesProfileAlternative c)))
      (is (true? (coerce/validate schema/PriceDefinition c)))
      (is (= [{:jsonld/id "x:alt"}]
             (:urpx/referencesProfileAlternative
              (coerce/encode schema/PriceDefinition c)))
          "and emits in the same shape it decoded, so it survives a round trip"))))

;; ---------------------------------------------------------------------------
;; The modifier path. A matrix of mutually-exclusive cells is a MODIFIER shape,
;; so a version of this feature that ran only on the base plan would have been a
;; no-op exactly where it is needed. Every case above is constructed on a base
;; RatePlan, which is how that went unnoticed until an audit measured it.
;; ---------------------------------------------------------------------------

(def ^:private pcia "pge:pd-vintage-pcia")
(def ^:private vintage-dim "pge:pa-departure-vintage")

(defn- modifier-fixture [] (fixtures/load-modifier "pge-e-tou-c-2026-03-01"))
(defn- base-fixture [] (fixtures/load-rate-plan "pge-e-tou-c-2026-03-01"))

(defn- edit-pcia [doc f]
  (clojure.walk/postwalk
   (fn [n] (if (and (map? n) (= pcia (:jsonld/id n))) (f n) n)) doc))

(defn- composed-rate [modifier sel]
  (try (price/marginal-unit-rate
        (price/resolve-prices-with-modifiers (base-fixture) [modifier] (at)
                                             (when sel {:profile-alternatives sel})))
       (catch clojure.lang.ExceptionInfo e (:reason (ex-data e)))))

(deftest a-recurring-modifier-price-cannot-enter-a-per-kwh-sum
  (testing "urpx:intervalDuration lives on the PriceDefinition and is read on
            both paths. It was read on neither view consistently: the modifier
            path built tiers without it while the withheld record carried it, so
            marginal-unit-rate judged eighteen vintage-PCIA prices
            harmless-as-withheld and then summed all eighteen as present.

            0.24.0 refused here. The broken narrowing returned 1.08811, which is
            the base plan's 0.46886 plus the SUM of eighteen prices exactly one
            of which applies to any customer. That is worse than the arbitrary
            pick this machinery exists to remove, and it is a number no bill
            could produce."
    (let [modi      (modifier-fixture)
          recurring (edit-pcia modi #(assoc % :urpx/hasCalculationMethod
                                            {:jsonld/type "urpx:CalculationMethod"
                                             :urpx/intervalDuration (java.time.Period/ofMonths 1)}))]
      (is (= :unselected-alternatives (composed-rate modi nil))
          "sanity: ordinary per-kWh alternatives still refuse without a selection")
      (is (= 0.46886M (composed-rate recurring nil))
          "a recurring modifier price is excluded, so the base plan answers alone")
      (is (not= 1.08811M (composed-rate recurring nil))
          "and emphatically not the sum of every mutually-exclusive cell"))))

(deftest a-modifier-definition-is-scoped-by-its-alternative
  (testing "all four outcomes on the modifier path. Only the first assertion
            depends on the new scoping: the other three are also reachable
            through the pre-existing PriceSetAlternatives selection, since the
            container @id is the same key. Kept anyway, because a change that
            broke them would be a regression, but the load-bearing one is the
            :not-selected case"
    (let [modi   (modifier-fixture)
          scoped (edit-pcia modi #(assoc % :urpx/referencesProfileAlternative
                                         [{:jsonld/id "pge:pa-vintage-2009"}]))]
      (is (= 0.46886M (composed-rate scoped {vintage-dim "pge:pa-vintage-2020"}))
          "chose a different cell: the definition does not apply, base plan alone")
      (is (= 0.49859M (composed-rate scoped {vintage-dim "pge:pa-vintage-2009"}))
          "chose that cell: it applies")
      (is (= :unselected-alternatives (composed-rate scoped nil))
          "chose nothing in that dimension: withheld, and the rate refuses")
      (is (= :unselected-alternatives (composed-rate modi nil))
          "unscoped is unchanged"))))

(deftest several-references-refuse-on-the-modifier-path-too
  (let [two (edit-pcia (modifier-fixture)
                       #(assoc % :urpx/referencesProfileAlternative
                               [{:jsonld/id "pge:pa-vintage-2009"}
                                {:jsonld/id "pge:pa-vintage-2020"}]))]
    (is (= :multiple-profile-alternatives
           (try (price/resolve-modifier-prices two (at)) nil
                (catch clojure.lang.ExceptionInfo e (:reason (ex-data e))))))))

(deftest withheld-evidence-covers-prices-nested-under-an-unselected-set
  (testing "every withheld price is counted exactly once on the modifier path,
            where the two evidence sources overlap: with no selection the
            modifier's price lookup surfaces all eighteen vintages AND the
            unselected-set walk returns the same eighteen, so without the
            dedupe the ledger reports thirty-six.

            The base-plan version of this coverage question, where the two
            sources are disjoint and the union is load-bearing, is
            a-base-plan-definition-withheld-with-its-prices-nested-in-an-alternatives-set
            below."
    (let [scoped (edit-pcia (modifier-fixture)
                            #(assoc % :urpx/referencesProfileAlternative
                                    [{:jsonld/id "pge:pa-vintage-2009"}]))
          r      (price/resolve-modifier-prices scoped (at))
          l      (first (filter :urpx.resolved/unselected-alternatives
                                (:urpx.resolved/ledgers r)))]
      (is (some? l) "the ledger is marked")
      (is (= 18 (count (:urpx.resolved/withheld-prices l)))
          "and every withheld price is accounted for, not zero of them")
      (is (= :unselected-alternatives
             (try (price/marginal-unit-rate r) nil
                  (catch clojure.lang.ExceptionInfo e (:reason (ex-data e)))))))))

(deftest a-selection-the-dimension-does-not-list-is-refused
  (testing "compared for equality, a typo or a stale id reads as a deliberate
            choice of some sibling cell, drops every definition in that
            dimension, and returns a confident short number with nothing in the
            result to say so. The membership index needed to catch it is the
            same one that maps an alternative to its dimension."
    (let [{:keys [plan dim alts]} (fixture)
          scoped (scope-pd plan [(first alts)])]
      (is (= 0.46886M (price/marginal-unit-rate
                       (resolved scoped {dim (first alts)})))
          "sanity: the correct selection answers")
      (is (= :unknown-profile-alternative
             (try (resolved scoped {dim (str dim "-typo")}) nil
                  (catch clojure.lang.ExceptionInfo e (:reason (ex-data e)))))
          "a value that dimension does not list is refused, not read as a sibling")
      (is (= 0.26104M (price/marginal-unit-rate
                       (resolved scoped {dim (second alts)})))
          "while a real sibling still filters silently, as it should"))))

(deftest an-alternative-listed-by-two-containers-is-refused
  (testing "nothing in the shapes stops an alternative @id appearing under two
            ProfileAlternatives. Collapsing that last-wins would answer a
            correct selection with a refusal the caller cannot clear, or drop
            the definition silently and return short."
    (let [{:keys [plan dim alts]} (fixture)
          scoped (scope-pd plan [(first alts)])
          shared (clojure.walk/postwalk
                  (fn [n] (if (and (map? n) (= "urpx:PlanElements" (:jsonld/type n))
                                   (seq (:urpx/hasProfileAlternatives n)))
                            (update n :urpx/hasProfileAlternatives conj
                                    {:jsonld/id "x:pa-second"
                                     :jsonld/type "urpx:ProfileAlternatives"
                                     :urpx/hasProfileAlternative
                                     [{:jsonld/id (first alts)
                                       :jsonld/type "urpx:ProfileAlternative"}]})
                            n))
                  scoped)]
      (is (= :ambiguous-profile-alternative
             (try (resolved shared {dim (first alts)}) nil
                  (catch clojure.lang.ExceptionInfo e (:reason (ex-data e)))))))))

(deftest a-base-plan-definition-withheld-with-its-prices-nested-in-an-alternatives-set
  (testing "PG&E E-TOU-C files its Base Services Charge as three Prices inside a
            urpx:PriceSetAlternatives. Scope that definition to an alternative
            and choose nothing, and it becomes :unselected, which takes it out
            of the ledger's definitions entirely: the branch that reads an
            unselected SET no longer covers it, and the base plan's own price
            lookup skips the nested prices by design. Only the union of the two
            sees them, and without it the definition is named in the mark with
            zero evidence behind it.

            The entries carry :urpx.resolved/interval-duration off the
            definition's calculation method. The per-kWh rate here is unaffected
            for a simpler reason, that this ledger is not energy-measured at
            all, so the assertion below pins the energy filter rather than the
            stamp; the stamp is pinned directly, and the sum's use of it by
            a-recurring-modifier-price-cannot-enter-a-per-kwh-sum."
    (let [{:keys [plan alts]} (fixture)
          scoped (clojure.walk/postwalk
                  (fn [n] (if (and (map? n) (= "pge:pd-base-charge" (:jsonld/id n))
                                   (= "urpx:PriceDefinition" (:jsonld/type n)))
                            (assoc n :urpx/referencesProfileAlternative
                                   [{:jsonld/id (first alts)}])
                            n))
                  plan)
          l (->> (:urpx.resolved/ledgers (resolved scoped nil))
                 (filter #(some #{"pge:pd-base-charge"}
                                (:urpx.resolved/unselected-alternatives %)))
                 first)]
      (is (some? l) "the ledger is marked")
      (is (= 3 (count (:urpx.resolved/withheld-prices l)))
          "with all three nested prices as evidence, not zero of them")
      (is (every? :urpx.resolved/interval-duration (:urpx.resolved/withheld-prices l))
          "each carrying the interval that makes it per-day rather than per-kWh")
      (is (= 0.46886M (price/marginal-unit-rate (resolved scoped nil)))
          "so the per-kWh rate is unaffected and does not refuse"))))

(deftest a-marked-ledger-with-no-evidence-refuses
  (testing "the fail-safe for the whole narrowing. If a ledger is marked but
            :urpx.resolved/withheld-prices is empty, nothing is known about what
            was held back, and not knowing is not evidence that it did not
            matter. A guard that passes on missing evidence is not a guard.

            Reachable: scope a definition to an alternative and date its only
            Price into the future, so the definition is withheld and has no
            active price to enumerate. Without this clause the rate comes back
            0.42248, silently short the 0.04638 transmission charge."
    (let [{:keys [plan alts]} (fixture)
          doc (clojure.walk/postwalk
               (fn [n]
                 (cond
                   (and (map? n) (= "pge:pd-transmission" (:jsonld/id n))
                        (= "urpx:PriceDefinition" (:jsonld/type n)))
                   (assoc n :urpx/referencesProfileAlternative [{:jsonld/id (first alts)}])

                   (and (map? n) (= "pge:price-transmission" (:jsonld/id n)))
                   (assoc n :urpx/startDate (java.time.LocalDate/of 2027 1 1))

                   :else n))
               plan)
          r (resolved doc nil)
          l (->> (:urpx.resolved/ledgers r)
                 (filter #(some #{"pge:pd-transmission"}
                                (:urpx.resolved/unselected-alternatives %)))
                 first)]
      (is (some? l) "the ledger is marked")
      (is (empty? (:urpx.resolved/withheld-prices l)) "with nothing behind the mark")
      (is (= :unselected-alternatives
             (try (price/marginal-unit-rate r) nil
                  (catch clojure.lang.ExceptionInfo e (:reason (ex-data e)))))
          "so it refuses rather than answer from an absence"))))

(deftest a-selection-is-checked-against-the-document-that-declares-it
  (testing "the membership check runs once per document, not per definition,
            because the damage is not confined to the definition-level slot:
            the same typo on the PriceSetAlternatives path drops the whole
            ledger and answers short.

            It also has to run on the MODIFIER's own containers.
            pge:pa-departure-vintage is declared by the modifier, so the base
            plan's index never sees that key and only the modifier's check can
            catch it."
    (let [modi (modifier-fixture)]
      (is (= 0.49859M (composed-rate modi {vintage-dim "pge:pa-vintage-2009"}))
          "sanity: the correct id selects")
      (is (= :unknown-profile-alternative (composed-rate modi {vintage-dim "pge:pa-vintage-2009x"}))
          "a stale or mistyped id is refused, where it used to return 0.46886")
      (is (= :unselected-alternatives (composed-rate modi {"x:unrelated-dimension" "x:whatever"}))
          "while a key no document here declares is left alone, so this reads as
           no selection and withholds"))))

(deftest an-ambiguous-alternative-is-refused-on-the-modifier-path-too
  (let [doc (clojure.walk/postwalk
             (fn [n] (if (and (map? n) (= "urpx:PlanElements" (:jsonld/type n))
                              (seq (:urpx/hasProfileAlternatives n)))
                       (update n :urpx/hasProfileAlternatives conj
                               {:jsonld/id "x:pa-second" :jsonld/type "urpx:ProfileAlternatives"
                                :urpx/hasProfileAlternative
                                [{:jsonld/id "pge:pa-vintage-2009"
                                  :jsonld/type "urpx:ProfileAlternative"}]})
                       n))
             (edit-pcia (modifier-fixture)
                        #(assoc % :urpx/referencesProfileAlternative
                                [{:jsonld/id "pge:pa-vintage-2009"}])))]
    (is (= :ambiguous-profile-alternative
           (try (price/resolve-modifier-prices doc (at)) nil
                (catch clojure.lang.ExceptionInfo e (:reason (ex-data e))))))))

;; ---------------------------------------------------------------------------
;; urpx:PriceSummary members. A subtotal is the third place a PriceDefinition's
;; applicability is consulted, after the tier path and the ledger mark, and it
;; was the one left ungated. Gating two of three is worse than gating none: the
;; price vanishes from :tiers and stays in the subtotal, so a derived price
;; multiplies a total containing a charge that does not apply.
;; ---------------------------------------------------------------------------

(def ^:private sce-gen "sce:d-2026-03-20-pd-generation")

(defn- with-dimension
  "Add a two-cell ProfileAlternatives, and scope SCE D's generation definition
   to the first cell. Neither shape exists in the fixtures; the point is that a
   filing authoring the new slot on a plan with a discountable subtotal, which
   is every SCE and SDG&E plan here, hits this on the first try."
  [doc]
  (clojure.walk/postwalk
   (fn [n]
     (cond
       (and (map? n) (= "urpx:PlanElements" (:jsonld/type n)))
       (assoc n :urpx/hasProfileAlternatives
              [{:jsonld/id "x:dim" :jsonld/type "urpx:ProfileAlternatives"
                :urpx/hasProfileAlternative
                [{:jsonld/id "x:alt-a" :jsonld/type "urpx:ProfileAlternative"}
                 {:jsonld/id "x:alt-b" :jsonld/type "urpx:ProfileAlternative"}]}])

       (and (map? n) (= sce-gen (:jsonld/id n)) (= "urpx:PriceDefinition" (:jsonld/type n)))
       (assoc n :urpx/referencesProfileAlternative [{:jsonld/id "x:alt-a"}])

       :else n))
   doc))

(defn- gen-price-ids
  "The @ids of Prices governed by the generation definition.

   Summary members in this fixture are BARE REFS, {:jsonld/id ...}, with no
   :urpx/referencesPriceDefinition on them: price-value dereferences them
   through the document index. An oracle that filtered on the definition id
   read off the raw member therefore matched nothing and removed nothing, and
   the comparison against it was (= x x)."
  [doc]
  (into #{} (comp (filter #(and (map? %) (= "urpx:Price" (:jsonld/type %))
                                (= sce-gen (get-in % [:urpx/referencesPriceDefinition :jsonld/id]))))
                  (map :jsonld/id))
        (tree-seq coll? seq doc)))

(defn- without-gen-members
  "The oracle: remove the generation member from every summary by hand, which
   is what gating it correctly must be equivalent to. Matches a member by its
   own @id as well as by an inline definition reference, so it works whichever
   way the filing writes one."
  [doc]
  (let [ids (gen-price-ids doc)]
    (clojure.walk/postwalk
     (fn [n] (if (and (map? n) (= "urpx:PriceSummary" (:jsonld/type n)))
               (update n :urpx/includesPrice
                       (fn [ms] (vec (remove #(or (contains? ids (:jsonld/id %))
                                                  (= sce-gen (get-in % [:urpx/referencesPriceDefinition
                                                                        :jsonld/id])))
                                             ms))))
               n))
     doc)))

(defn- summary-member-count [doc]
  (count (for [n (tree-seq coll? seq doc)
               :when (and (map? n) (= "urpx:PriceSummary" (:jsonld/type n)))
               m (:urpx/includesPrice n)]
           m)))

(defn- sce-rate [doc sel]
  (let [care (fixtures/load-modifier "sce-care-2026-03-20")]
    (try (price/marginal-unit-rate
          (price/resolve-prices-with-modifiers doc [care] (at)
                                               (when sel {:profile-alternatives sel})))
         (catch clojure.lang.ExceptionInfo e (:reason (ex-data e))))))

(deftest a-summary-member-whose-definition-does-not-apply-is-not-counted
  (testing "choosing a DIFFERENT cell drops the generation price from the tiers
            and must drop it from the CARE discount's subtotal too. Gated in one
            place and not the other, the discount was computed over a subtotal
            containing a charge the customer does not pay: the rate came back
            0.08043875 against a correct 0.11866200, a third low, with no mark
            and no refusal anywhere in the result."
    (let [plan   (fixtures/load-rate-plan "sce-d-2026-03-20")
          scoped (with-dimension plan)
          oracle (without-gen-members scoped)]
      (is (= 0.19804875M (sce-rate plan nil))
          "sanity: unscoped and unselected, the whole plan prices as it always did")
      (is (< (summary-member-count oracle) (summary-member-count scoped))
          "the oracle must actually remove a member, or the comparison below is
           (= x x). It read the definition id off a member that is a bare @id
           ref, matched nothing, and passed for four rounds")
      (is (= (sce-rate oracle {"x:dim" "x:alt-b"})
             (sce-rate scoped {"x:dim" "x:alt-b"}))
          "and scoping to a cell the caller did not choose is exactly equivalent
           to the member not being there")
      (is (= 0.11866200M (sce-rate scoped {"x:dim" "x:alt-b"}))
          "which is the number, not 0.08043875"))))

(deftest a-summary-member-in-an-unchosen-dimension-makes-the-subtotal-unvaluable
  (testing "choosing NOTHING is not the same as choosing against. Excluding the
            member understates the subtotal and including it overstates it, so
            the summary is unvaluable and the caller gets a named reason rather
            than either number."
    (let [scoped (with-dimension (fixtures/load-rate-plan "sce-d-2026-03-20"))
          care   (fixtures/load-modifier "sce-care-2026-03-20")
          r      (price/resolve-prices-with-modifiers scoped [care] (at))
          reasons (->> (:urpx.resolved/ledgers r)
                       (mapcat :urpx.resolved/tiers)
                       (keep #(:reason (:urpx.resolved/unvaluable %)))
                       set)]
      (is (contains? reasons :summary-member-unselected)
          "the discount names why it cannot be valued")
      (is (keyword? (sce-rate scoped nil))
          "and the rate refuses rather than returning a number"))))

(deftest the-modifier-mark-reports-both-of-its-sources
  (testing "a modifier ledger can be marked twice over: by prices surfaced from
            an unselected PriceSetAlternatives, and by a definition scoped to an
            unchosen alternative. Reporting only the first hid the second, so a
            caller who supplied a vintage selection in response to the error got
            a second refusal naming something the first never mentioned."
    (let [doc (clojure.walk/postwalk
               (fn [n]
                 (if (and (map? n) (= "urpx:BillingModel" (:jsonld/type n)))
                   (update n :urpx/hasPriceDefinition conj
                           {:jsonld/id "x:pd-alt" :jsonld/type "urpx:PriceDefinition"
                            :urpx/name "scoped add-on"
                            :urpx/referencesLedger {:jsonld/id "pge:ledger-vintage-pcia"}
                            :urpx/referencesProfileAlternative [{:jsonld/id "pge:pa-vintage-2009"}]})
                   n))
               (modifier-fixture))
          l (->> (:urpx.resolved/ledgers (price/resolve-modifier-prices doc (at)))
                 (filter #(= "pge:ledger-vintage-pcia" (:urpx.resolved/ledger-id %)))
                 first)
          ids (set (:urpx.resolved/unselected-alternatives l))]
      (is (contains? ids "x:pd-alt") "the scoped definition is named")
      (is (some #(re-find #"price-vpcia" (str %)) ids) "and so are the unselected prices"))))

;; ---------------------------------------------------------------------------
;; One selection map, one index. A composition is resolved against a single
;; :profile-alternatives map, so every path that reads it has to read the same
;; containers. Indexing each document separately made three paths disagree
;; about one PriceDefinition inside one result.
;; ---------------------------------------------------------------------------

(defn- add-container [doc cid cells]
  (clojure.walk/postwalk
   (fn [n] (if (and (map? n) (= "urpx:PlanElements" (:jsonld/type n)))
             (update n :urpx/hasProfileAlternatives (fnil conj [])
                     {:jsonld/id cid :jsonld/type "urpx:ProfileAlternatives"
                      :urpx/hasProfileAlternative
                      (mapv (fn [a] {:jsonld/id a :jsonld/type "urpx:ProfileAlternative"}) cells)})
             n))
   doc))

(defn- pair-rate [plan modifier sel]
  (try (price/marginal-unit-rate
        (price/resolve-prices-with-modifiers plan [modifier] (at)
                                             (when sel {:profile-alternatives sel})))
       (catch clojure.lang.ExceptionInfo e (:reason (ex-data e)))))

(deftest a-modifier-definition-can-be-scoped-to-a-container-the-base-plan-declares
  (testing "the modifier's own index cannot see a container declared by the base
            plan, so the definition resolved :unselected for every selection
            value and the withhold could not be cleared from the composed call
            at all. The selection map spans both documents; the index has to."
    (let [plan (add-container (base-fixture) "zz:dim" ["zz:a" "zz:b"])
          modi (edit-pcia (modifier-fixture)
                          #(assoc % :urpx/referencesProfileAlternative [{:jsonld/id "zz:a"}]))
          vintage {vintage-dim "pge:pa-vintage-2020"}]
      (is (= 0.50518M (pair-rate plan modi (merge vintage {"zz:dim" "zz:a"})))
          "choosing that cell applies the definition")
      (is (= 0.46886M (pair-rate plan modi (merge vintage {"zz:dim" "zz:b"})))
          "choosing another filters it out, leaving the base plan alone")
      (is (= :unselected-alternatives (pair-rate plan modi vintage))
          "and choosing nothing in that dimension still withholds"))))

(deftest a-base-plan-definition-can-be-scoped-to-a-container-a-modifier-declares
  (testing "the same property in the other direction, which is the ordinary
            shape: pge:pa-departure-vintage is declared by the modifier,
            not the plan."
    (let [plan (scope-pd (base-fixture) ["zz:a"])
          modi (add-container (modifier-fixture) "zz:dim" ["zz:a" "zz:b"])
          vintage {vintage-dim "pge:pa-vintage-2020"}]
      (is (= 0.50518M (pair-rate plan modi (merge vintage {"zz:dim" "zz:a"})))
          "the base plan's definition applies, judged against the modifier's container")
      (is (= 0.29736M (pair-rate plan modi (merge vintage {"zz:dim" "zz:b"})))
          "and is filtered out when another cell is chosen: 0.26104 for the base
           plan without its generation definition, plus 0.03632 for vintage 2020,
           which is 0.50518 less the unscoped 0.46886"))))

(deftest one-alternative-under-two-containers-across-a-composition-is-refused
  (testing "this is the shape that made the paths disagree silently: the
            modifier's index answered :selected for its own container while the
            base plan's answered :not-selected for its own, in one result, and
            a subtotal quietly lost a member the tiers kept. Merged, the
            ambiguity is visible and refuses, exactly as it already did when
            both containers lived in one document."
    (let [plan (add-container (base-fixture) "base:dim" ["zz:a" "zz:b"])
          modi (edit-pcia (add-container (modifier-fixture) "mod:dim" ["zz:a" "zz:b"])
                          #(assoc % :urpx/referencesProfileAlternative [{:jsonld/id "zz:a"}]))]
      (is (= :ambiguous-profile-alternative
             (pair-rate plan modi {"mod:dim" "zz:a" "base:dim" "zz:b"
                                   vintage-dim "pge:pa-vintage-2020"}))))))

(deftest the-same-container-declared-by-both-documents-is-one-dimension
  (testing "merging on container @id, so a filing that repeats a shared
            dimension in both documents does not read as two dimensions and
            refuse a correct selection. This is the regression the merge has to
            avoid while still catching the case above."
    (let [plan (add-container (base-fixture) "same:dim" ["zz:a" "zz:b"])
          modi (edit-pcia (add-container (modifier-fixture) "same:dim" ["zz:a" "zz:b"])
                          #(assoc % :urpx/referencesProfileAlternative [{:jsonld/id "zz:a"}]))]
      (is (= 0.50518M (pair-rate plan modi {"same:dim" "zz:a"
                                            vintage-dim "pge:pa-vintage-2020"}))))))

(deftest a-cell-only-a-price-set-alternative-declares-is-accepted
  (testing "a container's urpx:hasProfileAlternative list and the alternatives
            its PriceSetAlternatives name are two different sets, and nothing in
            the shapes forces them to agree. The price path resolves a selection
            naming the second; refusing it would reject a document this library
            prices correctly, which is the expensive direction.

            The fold has to happen where the composition index is BUILT, not in
            each path afterwards. Folded per path, the base plan's check saw the
            modifier's container but never the modifier's price-set cells, and
            refused a selection the modifier's own check accepted."
    (let [modi (clojure.walk/postwalk
                (fn [n] (if (and (map? n) (= vintage-dim (:jsonld/id n))
                                 (= "urpx:ProfileAlternatives" (:jsonld/type n)))
                          (update n :urpx/hasProfileAlternative
                                  (fn [as] (vec (remove #(= "pge:pa-vintage-2020" (:jsonld/id %)) as))))
                          n))
                (modifier-fixture))]
      (is (= 0.50518M (pair-rate (base-fixture) (modifier-fixture)
                                 {vintage-dim "pge:pa-vintage-2020"}))
          "sanity: with the cell listed, it selects")
      (is (= 0.50518M (pair-rate (base-fixture) modi {vintage-dim "pge:pa-vintage-2020"}))
          "and with the cell absent from the list but present on its price set, unchanged")
      (is (= :unknown-profile-alternative
             (pair-rate (base-fixture) (modifier-fixture) {vintage-dim "pge:pa-vintage-2020x"}))
          "while a genuine typo, named by neither, still refuses"))))

(deftest a-modifier-that-does-not-apply-is-not-indexed
  (testing "the composition index is built from the modifiers that apply, not
            every one handed in. A modifier naming a different plan contributes
            no prices, so letting its containers into the index, or reading its
            version to get them, would surface its choices and its version
            errors in a resolution it takes no part in."
    (let [;; a definition scoped to zz:a, so the ambiguity is actually consulted:
          ;; it is checked where an alternative is REFERENCED, not merely declared
          plan    (scope-pd (add-container (base-fixture) "zz:dim" ["zz:a" "zz:b"]) ["zz:a"])
          sel     {"zz:dim" "zz:a" vintage-dim "pge:pa-vintage-2020"}
          ;; a container that WOULD make zz:a ambiguous if this modifier were
          ;; indexed, so the assertion fails rather than merely passing when an
          ;; inapplicable document is read
          with-dim (add-container (modifier-fixture) "foreign:dim" ["zz:a" "zz:b"])
          retarget (fn [doc id]
                     (clojure.walk/postwalk
                      (fn [n] (if (and (map? n) (contains? n :urpx/applicableToRatePlan))
                                (assoc n :urpx/applicableToRatePlan [{:jsonld/id id}])
                                n))
                      doc))]
      (is (= :ambiguous-profile-alternative
             (pair-rate plan (retarget with-dim (:jsonld/id (base-fixture))) sel))
          "control: pointed at this plan, the modifier IS indexed and the
              collision between its container and the plan's is caught")
      (is (= 0.46886M (pair-rate plan (retarget with-dim "x:other-plan") sel))
          "pointed at a different plan, it contributes no prices and its
           containers never reach the index, so the same document does not
           collide and the base plan answers alone"))))

(deftest a-container-listing-one-alternative-twice-is-one-dimension
  (testing "duplicate entries in urpx:hasProfileAlternative are a redundancy in
            the document, not two dimensions. Counting them made
            pd-alternative-status refuse a selection that is unambiguous."
    (let [;; a fresh container, so no urpx:PriceSetAlternatives exists to fold
          ;; its cells in and dedupe them incidentally
          doc (clojure.walk/postwalk
               (fn [n] (if (and (map? n) (= "zz:dim" (:jsonld/id n))
                                (= "urpx:ProfileAlternatives" (:jsonld/type n)))
                         (update n :urpx/hasProfileAlternative
                                 #(vec (concat % [(first %)])))
                         n))
               (add-container (scope-pd (base-fixture) ["zz:a"]) "zz:dim" ["zz:a" "zz:b"]))]
      (is (= 0.46886M (price/marginal-unit-rate (resolved doc {"zz:dim" "zz:a"})))
          "the duplicate entry is a redundancy, not a second dimension"))))

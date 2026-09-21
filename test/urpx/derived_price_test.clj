(ns urpx.derived-price-test
  "Prices whose value is derived rather than stated: urpx:multiplier over a
  urpx:basedOnPrice, which is how a percentage discount like CARE or FERA is
  expressed against a urpx:PriceSummary of the line items it applies to.

  The shape that matters in practice puts the summary in the BASE PLAN filing,
  beside the Prices it summarises, and the discount Price in the MODIFIER
  filing, because the discount belongs to the program rather than to a base
  plan that also serves customers who do not get it. That crosses documents,
  which this library otherwise does not do; `resolve-prices-with-modifiers`
  makes it work because the caller has already supplied both."
  (:require [clojure.walk]
            [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [urpx.dsl :as dsl]
            [urpx.index :as index]
            [urpx.price :as price]
            [urpx.schema :as schema]
            [urpx.test-fixtures :as fixtures])
  (:import [java.time ZonedDateTime ZoneId]))

(def ^:private la (ZoneId/of "America/Los_Angeles"))
(defn- zdt [] (ZonedDateTime/of 2026 7 15 17 0 0 0 la))

(defn- marginal-tier [resolved]
  (->> (:urpx.resolved/ledgers resolved)
       (filter #(= "urpx:energy" (get-in % [:urpx.resolved/measurement-kind :jsonld/id])))
       first :urpx.resolved/tiers first))

(defn- rewrite-price
  "Replace the Price with `pid` inside `doc` using `f`."
  [doc pid f]
  (clojure.walk/postwalk
   (fn [n] (if (and (map? n) (= pid (:jsonld/id n)) (= "urpx:Price" (:jsonld/type n))) (f n) n))
   doc))

(deftest price-summary-schema
  (testing "urpx:includesPrice is sh:minCount 2 upstream: a summary of one price
            is not a summary"
    (let [p (fn [id v] {:jsonld/type "urpx:Price" :jsonld/id id :urpx/unitPrice v})
          s (fn [members] {:jsonld/type "urpx:PriceSummary" :jsonld/id "x:sum"
                           :urpx/includesPrice members})]
      (is (m/validate schema/PriceSummary (s [(p "x:a" 1M) (p "x:b" 2M)])))
      (is (not (m/validate schema/PriceSummary (s [(p "x:a" 1M)])))
          "one member is rejected")
      (testing "members may be bare @id references as well as inline Prices"
        (is (m/validate schema/PriceSummary (s [{:jsonld/id "x:a"} {:jsonld/id "x:b"}]))))
      (testing "urpx:summaryAmount is the author's stated total and is optional"
        (is (m/validate schema/PriceSummary
                        (assoc (s [(p "x:a" 1M) (p "x:b" 2M)]) :urpx/summaryAmount 3M)))))))

(deftest derived-price-resolves-within-one-document
  (testing "a Price valued as multiplier x a PriceSummary in the same document
            resolves to that product"
    (let [plan (fixtures/load-rate-plan "pge-e-1-2026-03-01")
          base (price/resolve-prices plan (zdt))
          tier (marginal-tier base)
          pid  (:urpx.resolved/price-id tier)
          unit (:urpx.resolved/unit-price tier)
          ;; summary over two copies of the same price, so the expected value is
          ;; arithmetic rather than a guess: 0.5 x (unit + unit) = unit
          summary {:jsonld/type "urpx:PriceSummary" :jsonld/id "x:summary"
                   :urpx/includesPrice [{:jsonld/id pid} {:jsonld/id pid}]}
          plan' (-> plan
                    (rewrite-price pid #(-> % (dissoc :urpx/unitPrice)
                                            (assoc :urpx/multiplier 0.5M
                                                   :urpx/basedOnPrice {:jsonld/id "x:summary"})))
                    ;; the member reference resolves to the ORIGINAL price, so
                    ;; park an unmodified copy in the document alongside
                    (assoc-in [:urpx/hasRatePlanVersion :urpx/hasPrices :urpx/summaryHolder]
                              [summary {:jsonld/type "urpx:Price" :jsonld/id pid
                                        :urpx/unitPrice unit}]))]
      (is (some? unit) "baseline must have a valued marginal tier")
      (let [t (marginal-tier (price/resolve-prices plan' (zdt)))]
        (is (nil? (:urpx.resolved/unvaluable t))
            (str "should be valuable, got " (pr-str (:urpx.resolved/unvaluable t))))
        (is (= unit (:urpx.resolved/unit-price t))
            "0.5 x (unit + unit) = unit")))))

(deftest unresolvable-reference-is-reported-not-nil
  (testing "a Price the resolver cannot value reports itself, rather than
            surfacing a nil unit price that a tier-iterating consumer reads as
            free. That is the dangerous failure: a 20 percent discount silently
            becoming 100 percent."
    (let [plan (fixtures/load-rate-plan "pge-e-1-2026-03-01")
          pid  (:urpx.resolved/price-id (marginal-tier (price/resolve-prices plan (zdt))))
          plan' (rewrite-price plan pid
                               #(-> % (dissoc :urpx/unitPrice)
                                    (assoc :urpx/multiplier 0.8M
                                           :urpx/basedOnPrice {:jsonld/id "x:absent"})))
          t (marginal-tier (price/resolve-prices plan' (zdt)))]
      (is (some? t) "the tier still surfaces; it is not silently dropped")
      (is (nil? (:urpx.resolved/unit-price t)))
      (is (= :unresolved-reference (:reason (:urpx.resolved/unvaluable t))))
      (is (= pid (:price-id (:urpx.resolved/unvaluable t))))
      (testing "and the detail names the reference and why it could not be followed"
        (is (re-find #"x:absent" (:detail (:urpx.resolved/unvaluable t))))
        (is (re-find #"one document at a time" (:detail (:urpx.resolved/unvaluable t)))))
      (testing "marginal-unit-rate refuses rather than throwing NullPointerException
                or silently under-reporting"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Cannot compute a marginal rate"
                              (price/marginal-unit-rate (price/resolve-prices plan' (zdt)))))))))

(deftest a-plain-price-with-no-value-is-reported-too
  (testing "the reporting is not specific to derived prices: any Price the
            resolver cannot value says so"
    (let [plan (fixtures/load-rate-plan "pge-e-1-2026-03-01")
          pid  (:urpx.resolved/price-id (marginal-tier (price/resolve-prices plan (zdt))))
          plan' (rewrite-price plan pid #(dissoc % :urpx/unitPrice))
          t (marginal-tier (price/resolve-prices plan' (zdt)))]
      (is (= :no-unit-price (:reason (:urpx.resolved/unvaluable t)))))))

(deftest existing-fixtures-carry-no-unvaluable-marker
  (testing "purely additive: every vendored fixture still values every tier, so
            the marker never appears on real data today"
    (doseq [n ["pge-e-1-2026-03-01" "pge-e-tou-c-2026-03-01" "pge-e-tou-d-2026-03-27"
               "pge-ev2-2026-03-27" "ladwp-r-1a-2026-04-01" "ladwp-r-1b-2026-04-01"]]
      (let [r (price/resolve-prices (fixtures/load-rate-plan n) (zdt))
            flagged (for [l (:urpx.resolved/ledgers r)
                          t (:urpx.resolved/tiers l)
                          :when (:urpx.resolved/unvaluable t)]
                      [(:urpx.resolved/price-id t) (:reason (:urpx.resolved/unvaluable t))])]
        (is (empty? flagged) (str n " -> " (pr-str (vec flagged))))))))


;; ---------------------------------------------------------------------------
;; The shape that ships: summary in the base plan, discount in the modifier
;; ---------------------------------------------------------------------------

(defn- attach-summary
  "Hang `summary` off the PriceSet with `set-id`.

   urpx:hasPriceSummary is rdfs:domain urpx:PriceSet upstream, so this is where
   a subtotal actually lives: beside the line items it names, not at the Prices
   container and not as a top-level sibling in the graph. That placement is
   what makes the cross-document case work at all, because it travels with the
   RatePlan node the caller hands in."
  [doc set-id summary]
  (clojure.walk/postwalk
   (fn [n] (if (and (map? n) (= set-id (:jsonld/id n)) (= "urpx:PriceSet" (:jsonld/type n)))
             (assoc n :urpx/hasPriceSummary [summary])
             n))
   doc))

(deftest derived-price-resolves-across-documents
  (testing "a percentage discount authored the way CARE and FERA are: the
            urpx:PriceSummary in the BASE PLAN filing beside the Prices it
            summarises, the discount urpx:Price in the MODIFIER filing naming it
            through urpx:basedOnPrice. Crossing documents is something this
            library otherwise refuses to do; it works here only because
            resolve-prices-with-modifiers was handed both filings, so the @id
            index spans exactly what the caller supplied and nothing is
            fetched behind their back."
    (let [plan      (fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")
          modifier  (fixtures/load-modifier "pge-e-tou-c-2026-03-01")
          idx       (index/build-index plan)
          members   ["pge:price-gen-summer-peak" "pge:price-gen-summer-off-peak"]
          member-ps (mapv #(get idx %) members)
          expected  (* -0.325M (reduce + 0M (mapv :urpx/unitPrice member-ps)))
          plan'     (attach-summary plan "pge:ps-generation-charges"
                                    {:jsonld/type "urpx:PriceSummary"
                                     :jsonld/id   "pge:care-basis"
                                     :urpx/name   "CARE discountable subtotal"
                                     :urpx/includesPrice (mapv (fn [id] {:jsonld/id id}) members)})
          discount  (fn [n] (-> n
                                (dissoc :urpx/unitPrice)
                                (assoc :urpx/multiplier -0.325M
                                       :urpx/basedOnPrice {:jsonld/id "pge:care-basis"})))
          modifier' (rewrite-price modifier "pge:price-vpcia-2020" discount)
          tiers-of  (fn [r] (->> (:urpx.resolved/ledgers r)
                                 (mapcat :urpx.resolved/tiers)))
          find-tier (fn [r] (->> (tiers-of r)
                                 (filter #(= "pge:price-vpcia-2020" (:urpx.resolved/price-id %)))
                                 first))]

      (testing "the fixture really does state both members, so the expected
                value is the corpus's own arithmetic and not a constant"
        (is (= 2 (count (filter :urpx/unitPrice member-ps))))
        (is (neg? expected)))

      (testing "resolved together, the modifier's discount reaches the base
                plan's summary and values against it"
        (let [tier (find-tier (price/resolve-prices-with-modifiers plan' [modifier'] (zdt)))]
          (is (some? tier) "the rewritten Price still surfaces as a tier")
          (is (nil? (:urpx.resolved/unvaluable tier))
              (str "expected a value, got " (pr-str (:urpx.resolved/unvaluable tier))))
          (is (= expected (:urpx.resolved/unit-price tier)))))

      (testing "resolved alone, the modifier cannot see the base plan, and says
                so by name instead of returning a nil that reads as free"
        (let [tier (find-tier (price/resolve-modifier-prices modifier' (zdt)))]
          (is (some? tier))
          (is (nil? (:urpx.resolved/unit-price tier)))
          (is (= :unresolved-reference (:reason (:urpx.resolved/unvaluable tier))))))

      (testing "an unmodified plan is untouched by any of this"
        (let [before (price/resolve-prices plan (zdt))
              after  (price/resolve-prices plan' (zdt))]
          (is (= before after)
              "attaching a summary to a PriceSet changes no resolved price"))))))

(deftest basedOnPrice-refuses-to-guess-between-several-bases
  (testing "urpx:basedOnPrice carries no sh:maxCount upstream, so a document may
            state more than one basis. There is no rule here for choosing among
            them, and picking arbitrarily would produce a confident wrong price,
            so the resolver refuses out loud."
    (let [plan (-> (fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")
                   (attach-summary "pge:ps-generation-charges"
                                   {:jsonld/type "urpx:PriceSummary"
                                    :jsonld/id   "pge:a"
                                    :urpx/includesPrice [{:jsonld/id "pge:price-gen-summer-peak"}
                                                         {:jsonld/id "pge:price-gen-winter-peak"}]})
                   (rewrite-price "pge:price-gen-summer-peak"
                                  (fn [n] (-> n (dissoc :urpx/unitPrice)
                                              (assoc :urpx/multiplier 0.5M
                                                     :urpx/basedOnPrice [{:jsonld/id "pge:a"}
                                                                         {:jsonld/id "pge:b"}])))))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Multiple urpx:basedOnPrice values"
                            (price/resolve-prices plan (zdt)))))))

(deftest dsl-authors-a-summary-and-a-derived-price
  (testing "the DSL needed no new builder for any of this: urpx:hasPriceSummary
            and urpx:basedOnPrice are entries on the PriceSet and Price schemas,
            and make-builder derives its kwargs, aliases and validation from the
            schema. This test exists because that is exactly the kind of claim
            that is true until someone changes a schema, and a missing builder
            is not otherwise a failing test."
    (dsl/dsl-scope
     (let [p1 (dsl/price ::gen-peak :unit-price 0.20782M)
           p2 (dsl/price ::gen-off  :unit-price 0.10482M)
           sm (dsl/price-summary ::care-basis
                                 :name "CARE discountable subtotal"
                                 :includes-price [::gen-peak ::gen-off])
           ps (dsl/price-set ::ps-gen :has-price [p1 p2] :has-price-summary [sm])
           d  (dsl/price ::care-discount :multiplier -0.325M :based-on-price ::care-basis)]

       (testing "local-symbol refs resolve to @id refs on both new properties"
         (is (= [{:jsonld/id "gen-peak"} {:jsonld/id "gen-off"}]
                (:urpx/includesPrice sm)))
         (is (= [{:jsonld/id "care-basis"}] (:urpx/basedOnPrice d))
             "0..N upstream, so the decoder normalizes a single value to a vector"))

       (testing "the summary attaches to the PriceSet, which is where
                 urpx:hasPriceSummary is rdfs:domain'd upstream"
         (is (= [sm] (:urpx/hasPriceSummary ps))))

       (testing "a derived Price states no urpx:unitPrice, and that is legal"
         (is (nil? (:urpx/unitPrice d)))
         (is (m/validate schema/Price d)))

       (testing "the assembled PriceSet validates, summary and all"
         (is (m/validate schema/PriceSet ps)
             (pr-str (m/explain schema/PriceSet ps))))))))

;; ---------------------------------------------------------------------------
;; Where a summary may hang, and what that placement does NOT do
;;
;; urpx:hasPriceSummary is rdfs:domain urpx:PriceSet, so a summary attaches to
;; one PriceSet. A whole-bill subtotal has no single natural set to hang off: a
;; discount spanning 25 line items crosses generation, distribution, surcharge
;; and adjustment sets, and PriceSetShape puts urpx:hasPrice at sh:minCount 1,
;; so there is no empty subtotal-only set to mint either. These tests pin the
;; three resolver behaviors that decide what an author may safely do.
;; ---------------------------------------------------------------------------

(defn- add-price-set [doc new-set]
  (clojure.walk/postwalk
   (fn [n] (if (and (map? n) (vector? (:urpx/hasPriceSet n)))
             (update n :urpx/hasPriceSet conj new-set)
             n))
   doc))

(defn- tier-ids [resolved]
  (->> (:urpx.resolved/ledgers resolved)
       (mapcat :urpx.resolved/tiers)
       (map :urpx.resolved/price-id)))

(deftest summary-members-may-live-in-any-price-set
  (testing "urpx:includesPrice is resolved through the document index, not
            through the PriceSet the summary hangs off. A summary attached to
            the generation set may therefore name prices in the distribution
            and adjustment sets, which is what a whole-bill subtotal has to do:
            no single PriceSet holds all of a discount's line items, and
            PriceSetShape's sh:minCount 1 on hasPrice means an empty
            subtotal-only set cannot be minted to hold them either."
    (let [plan     (fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")
          idx      (index/build-index plan)
          ;; deliberately drawn from two sets OTHER than the one the summary
          ;; will hang off
          members  ["pge:price-dist-summer-peak" "pge:price-public-purpose"]
          expected (* -0.325M (reduce + 0M (map #(:urpx/unitPrice (get idx %)) members)))
          plan'    (-> plan
                       (attach-summary "pge:ps-generation-charges"
                                       {:jsonld/type "urpx:PriceSummary"
                                        :jsonld/id   "pge:care-basis"
                                        :urpx/name   "CARE discountable (whole bill)"
                                        :urpx/includesPrice (mapv (fn [id] {:jsonld/id id}) members)})
                       (rewrite-price "pge:price-gen-summer-peak"
                                      (fn [n] (-> n (dissoc :urpx/unitPrice)
                                                  (assoc :urpx/multiplier -0.325M
                                                         :urpx/basedOnPrice {:jsonld/id "pge:care-basis"})))))
          tier     (->> (price/resolve-prices plan' (zdt))
                        (:urpx.resolved/ledgers)
                        (mapcat :urpx.resolved/tiers)
                        (filter #(= "pge:price-gen-summer-peak" (:urpx.resolved/price-id %)))
                        first)]
      (testing "the members really are in other sets, or this proves nothing"
        (let [sets (index/entities-of-type plan "urpx:PriceSet")
              home (fn [pid] (some (fn [[sid ps]]
                                     (when (some #(= pid (:jsonld/id %)) (:urpx/hasPrice ps)) sid))
                                   sets))]
          (is (= #{"pge:ps-distribution-charges" "pge:ps-adjustment-charges"}
                 (set (map home members))))))
      (is (nil? (:urpx.resolved/unvaluable tier))
          (str "expected a value, got " (pr-str (:urpx.resolved/unvaluable tier))))
      (is (= expected (:urpx.resolved/unit-price tier))))))

(deftest attaching-a-summary-does-not-price-anything
  (testing "a summary is not a priced member of the set it hangs off. Prices are
            collected from urpx:hasPrice; urpx:hasPriceSummary is a different
            property and is never walked for tiers, so attaching a subtotal
            cannot inflate the set it attaches to. A stated urpx:summaryAmount
            does not leak in either, which is the point of not trusting it."
    (let [plan   (fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")
          plan'  (attach-summary plan "pge:ps-generation-charges"
                                 {:jsonld/type "urpx:PriceSummary"
                                  :jsonld/id   "pge:s"
                                  ;; deliberately absurd, and deliberately ignored
                                  :urpx/summaryAmount 999999M
                                  :urpx/includesPrice [{:jsonld/id "pge:price-gen-summer-peak"}
                                                       {:jsonld/id "pge:price-gen-winter-peak"}]})
          before (price/resolve-prices plan (zdt))
          after  (price/resolve-prices plan' (zdt))]
      (is (= before after) "resolution is byte-identical with the summary attached")
      (is (= (price/marginal-unit-rate before) (price/marginal-unit-rate after))))))

(deftest repeating-prices-in-a-second-set-does-not-double-count
  (testing "an author who mints a PriceSet repeating line items already priced
            elsewhere does not get them counted twice. active-prices-by-pd keys
            on [referencesPriceDefinition, serviceTierNumber] and keeps one
            entry per key, so a repeated Price collides with its original.

            The protection is that dedup, and it holds only while the repeated
            Prices reference the SAME PriceDefinition. Minting parallel
            PriceDefinitions for the repeated set defeats it, and the companion
            test below pins that so the boundary is not mistaken for a
            guarantee."
    (let [plan   (fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")
          sets   (index/entities-of-type plan "urpx:PriceSet")
          gen    (get sets "pge:ps-generation-charges")
          before (price/resolve-prices plan (zdt))
          ;; the same Price nodes again, under a second set carrying the summary
          plan'  (add-price-set plan
                                (assoc gen
                                       :jsonld/id "pge:ps-care-discountable"
                                       :urpx/name "CARE discountable"
                                       :urpx/hasPriceSummary
                                       [{:jsonld/type "urpx:PriceSummary"
                                         :jsonld/id   "pge:care-basis"
                                         :urpx/includesPrice
                                         (mapv #(select-keys % [:jsonld/id]) (:urpx/hasPrice gen))}]))
          after  (price/resolve-prices plan' (zdt))]
      (is (= (frequencies (tier-ids before)) (frequencies (tier-ids after)))
          "no price surfaces more than it did before")
      (is (every? #(= 1 %) (vals (frequencies (tier-ids after))))
          "and no price surfaces twice")
      (is (= (price/marginal-unit-rate before) (price/marginal-unit-rate after))))))

(deftest minting-parallel-price-definitions-DOES-duplicate
  (testing "the boundary of the dedup above, pinned deliberately. Repeating a
            Price is safe because it collides with its original on
            PriceDefinition; giving the repeat its OWN PriceDefinition on the
            same ledger makes it a distinct priced line and it surfaces as an
            additional tier. An author building a discountable subtotal should
            repeat the Prices, never mint parallel PriceDefinitions for them."
    (let [plan     (fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")
          sets     (index/entities-of-type plan "urpx:PriceSet")
          pds      (index/entities-of-type plan "urpx:PriceDefinition")
          gen      (get sets "pge:ps-generation-charges")
          pd-ids   (into #{} (keep #(get-in % [:urpx/referencesPriceDefinition :jsonld/id])
                                   (:urpx/hasPrice gen)))
          dup-pds  (mapv #(assoc (get pds %) :jsonld/id (str % "-care")) pd-ids)
          dup-prcs (mapv (fn [p]
                           (-> p
                               (assoc :jsonld/id (str (:jsonld/id p) "-care"))
                               (assoc-in [:urpx/referencesPriceDefinition :jsonld/id]
                                         (str (get-in p [:urpx/referencesPriceDefinition :jsonld/id]) "-care"))))
                         (:urpx/hasPrice gen))
          pw       (fn [f d] (clojure.walk/postwalk f d))
          plan'    (->> plan
                        (pw (fn [n] (if (and (map? n) (vector? (:urpx/hasPriceDefinition n)))
                                      (update n :urpx/hasPriceDefinition into dup-pds) n)))
                        (pw (fn [n] (if (and (map? n) (vector? (:urpx/hasPriceSet n)))
                                      (update n :urpx/hasPriceSet conj
                                              (assoc gen :jsonld/id "pge:ps-care"
                                                     :urpx/hasPrice dup-prcs)) n))))
          before   (count (tier-ids (price/resolve-prices plan (zdt))))
          after    (count (tier-ids (price/resolve-prices plan' (zdt))))]
      (is (< before after)
          "parallel PriceDefinitions add tiers; this is the shape to avoid"))))

(ns urpx.index-test
  (:require [clojure.test :refer [deftest is testing]]
            [urpx.index :as index]
            [urpx.test-fixtures :as fixtures]))

(deftest ref-predicate
  (is (index/ref? {:jsonld/id "urpx:foo"}))
  (is (index/ref? {:jsonld/id "urpx:foo" :other "stuff"}))
  (is (not (index/ref? "urpx:foo")))
  (is (not (index/ref? {:not-an-id 1})))
  (is (not (index/ref? nil))))

(deftest build-index-on-toy-doc
  (testing "only nodes with both :jsonld/id and :jsonld/type are indexed"
    (let [doc {:jsonld/id "root"
               :jsonld/type "Root"
               :children [{:jsonld/id "a" :jsonld/type "T" :v 1}
                          {:jsonld/id "b" :jsonld/type "T" :v 2
                           :nested {:jsonld/id "c" :jsonld/type "T" :v 3}}]
               ;; Bare ref — no :jsonld/type, must NOT be indexed
               :sibling {:jsonld/id "ref-only"}}
          idx (index/build-index doc)]
      (is (= 4 (count idx)))
      (is (= 1 (:v (idx "a"))))
      (is (= 3 (:v (idx "c"))))
      (is (= "Root" (:jsonld/type (idx "root"))))
      (is (nil? (idx "ref-only"))))))

(deftest build-index-on-pge-e-1
  (let [plan (fixtures/load-rate-plan "pge-e-1-2026-03-01")
        idx (index/build-index plan)]
    (testing "every entity that has a :jsonld/id is reachable by id, asserted
              over the whole document rather than over six chosen examples.
              The six were named as illustrations under a universal claim, so
              the test covered 6 of 92 entities while saying it covered all of
              them, and a node type that stopped being indexed would only have
              been caught if it happened to be one of the six."
      (let [typed-with-id (->> (tree-seq coll? seq plan)
                               (filter #(and (map? %) (:jsonld/id %) (:jsonld/type %))))
            missing (remove #(some? (idx (:jsonld/id %))) typed-with-id)]
        (is (< 50 (count typed-with-id))
            "guard: the walk finds the document, so an empty walk cannot pass")
        (is (empty? (map :jsonld/id missing))
            "every typed node carrying an @id is in the index")))

    (testing "and the named cases stay named, one per entity kind"
      (is (some? (idx "pge:e-1")) "top-level RatePlan")
      (is (some? (idx "pge:e-1-2026-03-01")) "RatePlanVersion")
      (is (some? (idx "pge:season-summer")) "Season")
      (is (some? (idx "pge:tier-summer-t1")) "ServiceTier")
      (is (some? (idx "pge:pd-cia")) "PriceDefinition")
      (is (some? (idx "pge:price-cia-summer-t1")) "Price"))

    (testing "vocabulary IRIs (not nodes in this doc) are not indexed"
      (is (nil? (idx "urpx:residentialKind")))
      (is (nil? (idx "urpx:allDays")))
      (is (nil? (idx "urpx:bundledLedger"))))

    (testing "resolve-ref handles both id strings and ref maps"
      (let [season-ref {:jsonld/id "pge:season-summer"}]
        (is (= "Summer" (:urpx/seasonName (index/resolve-ref idx season-ref))))
        (is (= "Summer" (:urpx/seasonName
                         (index/resolve-ref idx "pge:season-summer"))))))

    (testing "resolve-ref returns nil for unknown ids and non-refs"
      (is (nil? (index/resolve-ref idx "urpx:does-not-exist")))
      (is (nil? (index/resolve-ref idx {:jsonld/id "urpx:does-not-exist"})))
      (is (nil? (index/resolve-ref idx 42)))
      (is (nil? (index/resolve-ref idx nil))))

    (testing "v0.2.0 PD->Ledger reference resolves to the indexed Ledger"
      (let [pd (index/get-entity idx "pge:pd-cia")
            ledger (index/resolve-ref idx (:urpx/referencesLedger pd))]
        (is (= "pge:ledger-cia" (:jsonld/id ledger)))
        (is (= "Conservation Incentive Adjustment" (:urpx/name ledger)))))))

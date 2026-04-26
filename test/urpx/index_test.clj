(ns urpx.index-test
  (:require [clojure.test :refer [deftest is testing]]
            [urpx.core :as core]
            [urpx.coerce :as coerce]
            [urpx.index :as index]))

(def cpau-tou-path
  "../urpx/test-cases/cpau-e-1-tou-2026-01-01/data/urpx-rate-plan_cpau_e-1-tou_2026-01-01.jsonld")

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

(deftest build-index-on-cpau-tou
  (let [coerced (coerce/coerce-rate-plan (core/load-rate-plan cpau-tou-path))
        idx (index/build-index coerced)]
    (testing "every entity that has a :jsonld/id is reachable by id"
      (is (some? (idx "urpx:cpau-e-1-tou")) "top-level RatePlan")
      (is (some? (idx "urpx:cpau-e-1-tou-2026-01-01")) "RatePlanVersion")
      (is (some? (idx "urpx:cpau-tou-season-summer")) "Season")
      (is (some? (idx "urpx:cpau-tou-period-summer-peak")) "TOUPeriod")
      (is (some? (idx "urpx:cpau-tou-pd-commodity-summer-peak")) "PriceDefinition")
      (is (some? (idx "urpx:cpau-tou-price-commodity-summer-peak")) "Price"))

    (testing "vocabulary IRIs (not nodes in this doc) are not indexed"
      (is (nil? (idx "urpx:peakTier")))
      (is (nil? (idx "urpx:allDays")))
      (is (nil? (idx "urpx:bundledLedger"))))

    (testing "resolve-ref handles both id strings and ref maps"
      (let [season-ref {:jsonld/id "urpx:cpau-tou-season-summer"}]
        (is (= "Summer" (:urpx/seasonName (index/resolve-ref idx season-ref))))
        (is (= "Summer" (:urpx/seasonName
                         (index/resolve-ref idx "urpx:cpau-tou-season-summer"))))))

    (testing "resolve-ref returns nil for unknown ids and non-refs"
      (is (nil? (index/resolve-ref idx "urpx:does-not-exist")))
      (is (nil? (index/resolve-ref idx {:jsonld/id "urpx:does-not-exist"})))
      (is (nil? (index/resolve-ref idx 42)))
      (is (nil? (index/resolve-ref idx nil))))

    (testing "indexed entity round-trips through resolve-ref"
      (let [pd (index/get-entity idx "urpx:cpau-tou-pd-commodity-summer-peak")
            referencing-price (index/get-entity idx "urpx:cpau-tou-price-commodity-summer-peak")
            resolved (index/resolve-ref idx (:urpx/referencesPriceDefinition referencing-price))]
        (is (identical? pd resolved)
            "Price -> PriceDefinition reference resolves to the indexed entity")))))

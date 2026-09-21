(ns urpx.start-date-test
  "What an absent urpx:startDate means.

  Four scoping sites in urpx.price read the date with `when-let` and silently
  discarded any Price that stated none, while the PriceSet gate three lines
  above them treats a nil date as permissive. Two readings of the same absence,
  and the discarding one deleted content from a shipped filing.

  sh:minCount 0 on both PriceShape and PriceSetShape is the vocabulary saying
  absence is legal rather than defective, so absence now means NO START
  CONSTRAINT. A Price that has not started yet is still excluded; a Price that
  never says when it started is in effect."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.walk]
            [urpx.index :as index]
            [urpx.price :as price]
            [urpx.test-fixtures :as fixtures])
  (:import [java.time LocalDate ZoneId ZonedDateTime]))

(def ^:private la (ZoneId/of "America/Los_Angeles"))
(defn- at [y m d] (ZonedDateTime/of y m d 17 0 0 0 la))
(defn- tiers [r] (mapcat :urpx.resolved/tiers (:urpx.resolved/ledgers r)))

(deftest a-shipped-filing-of-dateless-prices-resolves
  (testing "PG&E's E-TOU-C baseline-tier modifier carries four Prices and not
            one urpx:startDate anywhere, on the Prices or above them. It
            resolved to ZERO ledgers and ZERO tiers, and composing it with its
            base plan changed no number and reported nothing. Four prices went
            in and nothing came out."
    (let [m (fixtures/load-modifier "pge-e-tou-c-baseline-tier-2026-03-01")]
      (testing "the document really does state no dates, or this proves nothing"
        (let [ps (vals (index/entities-of-type m "urpx:Price"))]
          (is (= 4 (count ps)))
          (is (not-any? :urpx/startDate ps))
          (is (not-any? :urpx/startDateTime ps))))
      (let [r (price/resolve-modifier-prices m (at 2026 7 15))]
        (is (pos? (count (:urpx.resolved/ledgers r))))
        (is (= 4 (count (tiers r))) "all four prices surface"))))

  (testing "and composing it with its base plan now moves the rate, where
            before the modifier contributed nothing at all"
    (let [plan (fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")
          m    (fixtures/load-modifier "pge-e-tou-c-baseline-tier-2026-03-01")
          base (price/marginal-unit-rate (price/resolve-prices plan (at 2026 7 15)))
          comp- (price/marginal-unit-rate
                 (price/resolve-prices-with-modifiers plan [m] (at 2026 7 15)))]
      (is (not= base comp-) "the modifier must reach the rate"))))

(deftest a-price-that-has-not-started-is-still-excluded
  (testing "the permissive reading applies to an ABSENT date, not to a future
            one. A Price effective next year must not apply today, or the fix
            would have traded a silent drop for a silent inclusion."
    (let [plan (fixtures/load-rate-plan "sdge-dr-2026-04-01")
          future- (clojure.walk/postwalk
                   (fn [n] (if (and (map? n) (= "urpx:Price" (:jsonld/type n))
                                    (:urpx/unitPrice n))
                             (assoc n :urpx/startDate (LocalDate/of 2027 1 1))
                             n))
                   plan)]
      (is (pos? (count (tiers (price/resolve-prices plan (at 2026 7 15))))))
      (is (zero? (count (tiers (price/resolve-prices future- (at 2026 7 15)))))
          "every price dated 2027 must be excluded in 2026"))))

(deftest a-price-stating-only-startDateTime-is-read
  (testing "urpx:startDateTime is modeled on Price, PriceSet and
            PriceSetAlternative, and urpx.emit writes it, but nothing in the
            resolver read it. A filing stating its date that way priced as
            though it had no date at all: on sdge-dr, restating one PriceSet's
            date as startDateTime took 13 tiers to 3.

            Accepting a property in the schema, writing it on emit, and
            ignoring it in the resolver is the part that cannot stand."
    (let [plan (fixtures/load-rate-plan "sdge-dr-2026-04-01")
          swap-to-datetime
          (fn [doc] (clojure.walk/postwalk
                     (fn [n] (if (and (map? n) (:urpx/startDate n)
                                      (#{"urpx:Price" "urpx:PriceSet"} (:jsonld/type n)))
                               (-> n (dissoc :urpx/startDate)
                                   (assoc :urpx/startDateTime
                                          (.atStartOfDay ^LocalDate (:urpx/startDate n))))
                               n))
                     doc))
          before (price/resolve-prices plan (at 2026 7 15))
          after  (price/resolve-prices (swap-to-datetime plan) (at 2026 7 15))]
      (is (= (count (tiers before)) (count (tiers after)))
          "restating the same instant in the other property must change nothing")
      (is (= (price/marginal-unit-rate before) (price/marginal-unit-rate after)))))

  (testing "and a FUTURE startDateTime still excludes, so reading it did not
            make it permissive"
    (let [plan (fixtures/load-rate-plan "sdge-dr-2026-04-01")
          future- (clojure.walk/postwalk
                   (fn [n] (if (and (map? n) (= "urpx:Price" (:jsonld/type n))
                                    (:urpx/unitPrice n))
                             (-> n (dissoc :urpx/startDate)
                                 (assoc :urpx/startDateTime
                                        (.atStartOfDay (LocalDate/of 2027 1 1))))
                             n))
                   plan)]
      (is (zero? (count (tiers (price/resolve-prices future- (at 2026 7 15)))))))))

(deftest a-dated-price-supersedes-an-undated-sibling
  (testing "the latest-wins dedupe orders on start date, so an undated Price
            needs a place in that order. It sorts BEHIND any dated one: an
            explicitly dated Price supersedes an undated sibling on the same
            PriceDefinition, which is the only ordering that reads as
            intentional rather than accidental."
    (let [plan (fixtures/load-rate-plan "sdge-dr-2026-04-01")
          idx  (index/build-index plan)
          victim "sdge:dr-2026-04-01-price-udc-transmission"
          pd   (get-in (get idx victim) [:urpx/referencesPriceDefinition :jsonld/id])
          ;; add an undated twin on the same PD carrying a different price
          twin (-> (get idx victim)
                   (assoc :jsonld/id "x:undated-twin" :urpx/unitPrice 9.99M)
                   (dissoc :urpx/startDate))
          doc  (clojure.walk/postwalk
                (fn [n] (if (and (map? n) (= "urpx:PriceSet" (:jsonld/type n))
                                 (some #(= victim (:jsonld/id %)) (:urpx/hasPrice n)))
                          (update n :urpx/hasPrice conj twin)
                          n))
                plan)
          ts (tiers (price/resolve-prices doc (at 2026 7 15)))
          on-pd (filter #(= pd (:urpx.resolved/price-definition-id %)) ts)]
      (is (some? pd))
      (is (= 1 (count on-pd)) "the PD still contributes one price, not two")
      (is (not= 9.99M (:urpx.resolved/unit-price (first on-pd)))
          "and it is the DATED one that wins"))))

(ns urpx.start-instant-test
  "urpx:startDateTime gates a Price at its instant; urpx:startDate gates from
  midnight of that day in the plan's zone."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.walk]
            [urpx.index :as index]
            [urpx.price :as price]
            [urpx.schedule :as schedule]
            [urpx.test-fixtures :as fixtures])
  (:import [java.time LocalDate LocalDateTime ZoneId ZoneOffset ZonedDateTime]))

(def ^:private la (ZoneId/of "America/Los_Angeles"))
(defn- at [y m d h mi] (ZonedDateTime/of y m d h mi 0 0 la))

(def ^:private victim "sdge:dr-2026-04-01-price-udc-transmission")

(defn- with-prices
  "`doc` with `twins`, copies of the Price `victim-id` carrying each map's
   overrides, appended after it in the container holding it."
  [doc victim-id & twins]
  (let [orig (get (index/build-index doc) victim-id)
        new  (for [t twins]
               (merge (dissoc orig :urpx/startDate :urpx/startDateTime) t))]
    (clojure.walk/postwalk
     (fn [n] (if (and (map? n) (vector? (:urpx/hasPrice n))
                      (some #(and (= victim-id (:jsonld/id %)) (:urpx/unitPrice %))
                            (:urpx/hasPrice n)))
               (update n :urpx/hasPrice into new)
               n))
     doc)))

(defn- price-on [r pd]
  (->> (:urpx.resolved/ledgers r)
       (mapcat :urpx.resolved/tiers)
       (filter #(= pd (:urpx.resolved/price-definition-id %)))
       (map :urpx.resolved/unit-price)))

(def ^:private plan (delay (fixtures/load-rate-plan "sdge-dr-2026-04-01")))
(def ^:private pd (delay (get-in (get (index/build-index @plan) victim)
                                 [:urpx/referencesPriceDefinition :jsonld/id])))

(deftest a-startDateTime-gates-at-its-instant
  (let [doc (with-prices @plan victim
              {:jsonld/id "x:from-1600" :urpx/unitPrice 9.99M
               :urpx/startDateTime (LocalDateTime/of 2026 7 15 16 0)})]
    (is (= [0.10067M] (price-on (price/resolve-prices doc (at 2026 7 15 15 0)) @pd))
        "15:00 the same day: not yet started")
    (is (= [0.10067M] (price-on (price/resolve-prices doc (at 2026 7 15 15 59)) @pd)))
    (is (= [9.99M] (price-on (price/resolve-prices doc (at 2026 7 15 16 0)) @pd))
        "16:00: started, and it supersedes the earlier price")))

(deftest an-offset-bearing-startDateTime-is-its-instant
  (testing "2026-07-16T00:00-04:00 is 2026-07-15T21:00 in Los Angeles"
    (let [start (ZonedDateTime/of 2026 7 16 0 0 0 0 (ZoneOffset/ofHours -4))
          doc   (with-prices @plan victim
                  {:jsonld/id "x:offset" :urpx/unitPrice 9.99M :urpx/startDateTime start})]
      (is (= [0.10067M] (price-on (price/resolve-prices doc (at 2026 7 15 17 0)) @pd)))
      (is (= [0.10067M] (price-on (price/resolve-prices doc (at 2026 7 15 20 59)) @pd)))
      (is (= [9.99M] (price-on (price/resolve-prices doc (at 2026 7 15 21 0)) @pd))))))

(deftest a-startDate-gates-from-local-midnight
  (let [doc (with-prices @plan victim
              {:jsonld/id "x:from-date" :urpx/unitPrice 9.99M
               :urpx/startDate (LocalDate/of 2026 7 15)})]
    (is (= [0.10067M] (price-on (price/resolve-prices doc (at 2026 7 14 23 59)) @pd)))
    (is (= [9.99M] (price-on (price/resolve-prices doc (at 2026 7 15 0 0)) @pd))))
  (testing "and orders as that midnight against a startDateTime later the same day"
    (let [doc (with-prices @plan victim
                {:jsonld/id "x:from-date" :urpx/unitPrice 8.88M
                 :urpx/startDate (LocalDate/of 2026 7 15)}
                {:jsonld/id "x:from-0800" :urpx/unitPrice 9.99M
                 :urpx/startDateTime (LocalDateTime/of 2026 7 15 8 0)})]
      (is (= [8.88M] (price-on (price/resolve-prices doc (at 2026 7 15 7 0)) @pd)))
      (is (= [9.99M] (price-on (price/resolve-prices doc (at 2026 7 15 9 0)) @pd))))))

(deftest a-modifier-price-gates-at-its-instant
  (let [m    (fixtures/load-modifier "pge-e-tou-c-baseline-tier-2026-03-01")
        v    "pge:price-baseline-credit"
        mpd  (get-in (get (index/build-index m) v) [:urpx/referencesPriceDefinition :jsonld/id])
        doc  (with-prices m v {:jsonld/id "x:mod-1600" :urpx/unitPrice 9.99M
                               :urpx/startDateTime (LocalDateTime/of 2026 7 15 16 0)})
        r    #(price-on (price/resolve-modifier-prices doc %) mpd)]
    (is (= [-0.08140M] (r (at 2026 7 15 15 0))))
    (is (= [9.99M] (r (at 2026 7 15 16 0))))))

(deftest price-segments-split-at-the-start-instant
  (let [doc  (with-prices @plan victim
               {:jsonld/id "x:from-1600" :urpx/unitPrice 9.99M
                :urpx/startDateTime (LocalDateTime/of 2026 7 15 16 0)})
        segs (schedule/price-segments doc [] (.toInstant (at 2026 7 15 0 0))
                                      (.toInstant (at 2026 7 16 0 0)) la)
        pd-price #(price-on (:urpx.interval/resolved %) @pd)]
    (is (some #(= (at 2026 7 15 16 0) (:tick/beginning %)) segs)
        "a segment begins at 16:00")
    (is (= [[0.10067M] [9.99M]]
           (map pd-price [(last (filter #(.isBefore ^ZonedDateTime (:tick/beginning %)
                                                    (at 2026 7 15 16 0))
                                        segs))
                          (first (filter #(= (at 2026 7 15 16 0) (:tick/beginning %)) segs))])))))

(deftest a-PriceSet-startDateTime-gates-its-prices-at-its-instant
  (let [doc (clojure.walk/postwalk
             (fn [n] (if (= "sdge:dr-2026-04-01-ps-udc-and-non-bypassable" (:jsonld/id n))
                       (-> n
                           (dissoc :urpx/startDate)
                           (assoc :urpx/startDateTime (LocalDateTime/of 2026 7 15 16 0)))
                       n))
             @plan)]
    (is (= [] (price-on (price/resolve-prices doc (at 2026 7 15 15 59)) @pd)))
    (is (= [0.10067M] (price-on (price/resolve-prices doc (at 2026 7 15 16 0)) @pd)))))

(deftest a-startDateTime-in-a-DST-transition
  (testing "02:30 on 2027-03-14 never occurs in Los Angeles and starts at 03:00 PDT"
    (let [doc (with-prices @plan victim
                {:jsonld/id "x:gap" :urpx/unitPrice 9.99M
                 :urpx/startDateTime (LocalDateTime/of 2027 3 14 2 30)})]
      (is (= [0.10067M] (price-on (price/resolve-prices doc (at 2027 3 14 1 59)) @pd)))
      (is (= [9.99M] (price-on (price/resolve-prices doc (at 2027 3 14 3 0)) @pd)))))
  (testing "01:30 on 2026-11-01 occurs twice and starts at the first"
    (let [doc   (with-prices @plan victim
                  {:jsonld/id "x:overlap" :urpx/unitPrice 9.99M
                   :urpx/startDateTime (LocalDateTime/of 2026 11 1 1 30)})
          pdt   (ZonedDateTime/ofLocal (LocalDateTime/of 2026 11 1 1 30) la (ZoneOffset/ofHours -7))]
      (is (= [0.10067M] (price-on (price/resolve-prices doc (.minusMinutes pdt 1)) @pd)))
      (is (= [9.99M] (price-on (price/resolve-prices doc pdt) @pd))))))

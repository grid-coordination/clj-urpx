(ns urpx.zone-test
  "One moment, however it is spelled, costs one price.

  urpx.price used to reduce the caller's ZonedDateTime with .toLocalDateTime and
  compare those wall-clock fields against document bounds that are wall-clock in
  the PLAN's zone. So the answer depended on how the caller wrote the instant,
  silently, and by up to 36%. It reached both version windows and TOU
  brackets."
  (:require [clojure.test :refer [deftest is testing]]
            [urpx.price :as price]
            [urpx.price-test :as price-test]
            [urpx.schedule :as schedule]
            [urpx.test-fixtures :as fixtures])
  (:import [java.time ZoneId ZonedDateTime]))

(def ^:private la (ZoneId/of "America/Los_Angeles"))
(def ^:private zones [la (ZoneId/of "UTC") (ZoneId/of "Asia/Tokyo")
                      (ZoneId/of "America/New_York")])

(defn- spellings
  "The same moment written in four zones."
  [^ZonedDateTime z]
  (mapv #(.withZoneSameInstant z %) zones))

(deftest one-moment-one-tou-period-however-it-is-spelled
  (testing "PG&E E-TOU-C's peak runs 16:00 to 21:00 PACIFIC. Asked in UTC, the
            old reduction read 23:00 as the wall clock and returned Off-Peak
            0.34586 where Peak 0.46886 is right, and the reverse at 10:00. Up to
            36% apart on one instant, with no error and no mark."
    (let [plan (fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")
          at   (fn [z] (let [r (price/resolve-prices plan z {:profile-alternatives {}})]
                         [(price/marginal-unit-rate r) (:urpx.resolved/tou-period-name r)]))]
      (doseq [h [10 16 17 20 23]]
        (let [answers (distinct (map at (spellings (ZonedDateTime/of 2026 7 15 h 0 0 0 la))))]
          (is (= 1 (count answers))
              (str "hour " h " Pacific resolves to " (count answers)
                   " different answers depending on the zone it was written in: "
                   (pr-str answers)))))
      (testing "and the answers are the Pacific ones, not some other zone's"
        (is (= [0.46886M "Summer Peak Period"]
               (at (ZonedDateTime/of 2026 7 15 17 0 0 0 la))))
        (is (= [0.34586M "Summer Off-Peak Period"]
               (at (ZonedDateTime/of 2026 7 15 10 0 0 0 la))))))))

(deftest one-moment-one-version-window-however-it-is-spelled
  (testing "the half that was reported. A version expiring at LA midnight
            covered 2025-12-31T20:00-08:00 and refused the identical
            2026-01-01T04:00Z, because both sides ended up as naive wall clocks
            in different places."
    (let [plan (fixtures/load-rate-plan "sce-d-2025-11-15")
          v    (:urpx/hasRatePlanVersion plan)
          out  (fn [z] (let [r (price/resolve-prices plan z {:version v})]
                         (boolean (:urpx.resolved/outside-version-window r))))
          ;; 19:00 Pacific on the last day the version covers
          moment (ZonedDateTime/of 2025 12 31 19 0 0 0 la)]
      (is (= 1 (count (distinct (map out (spellings moment)))))
          "covered, or not covered, in every spelling alike"))))

(deftest a-version-window-is-compared-as-moments
  (testing "the readers take a zone because a naive bound names no moment until
            one is supplied, and every zone supplies a different one."
    (let [v {:jsonld/id "x:v" :jsonld/type "urpx:RatePlanVersion"
             :urpx/effectiveDateTime (java.time.LocalDateTime/of 2026 1 1 0 0)}
          at-la (ZonedDateTime/of 2025 12 31 23 0 0 0 la)]
      (is (false? (price/version-covers? v at-la la))
          "23:00 Pacific is before a Pacific midnight bound")
      (is (apply = (map #(price/version-covers? v % la) (spellings at-la)))
          "and every spelling of that same moment agrees, because the bound is
           anchored in the plan's zone rather than in the caller's")
      (let [after (.plusHours at-la 2)]
        (is (true? (price/version-covers? v after la))
            "01:00 Pacific is after it")
        (is (apply = (map #(price/version-covers? v % la) (spellings after)))
            "and again every spelling agrees")))))

(deftest a-plan-declaring-no-zone-refuses
  (testing "a document that states no zone states no moment: its bounds are
            wall-clock readings with nothing to anchor them. Falling back to the
            caller's zone is what this replaces, and it is how the same instant
            came to resolve differently depending on how it was spelled.

            Every filing in both corpora declares one, so this is constructed."
    (let [plan (dissoc (fixtures/load-rate-plan "sce-d-2026-03-20")
                       :urpx/timezoneIdentifier)]
      (is (= :urpx.price/no-timezone
             (try (price/resolve-prices plan (ZonedDateTime/of 2026 7 15 17 0 0 0 la) {})
                  nil
                  (catch clojure.lang.ExceptionInfo e (:reason (ex-data e)))))))))

(deftest a-walk-happens-in-the-plans-zone
  (testing "resolution anchors in the plan's zone, so candidate boundaries must
            be derived there too or the candidate set stops being a superset of
            true transitions. Bounds in another zone name the same instants."
    (let [plan (fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")
          s    (ZonedDateTime/of 2026 7 15 0 0 0 0 la)
          e    (ZonedDateTime/of 2026 7 16 0 0 0 0 la)
          utc  #(.withZoneSameInstant ^ZonedDateTime % (ZoneId/of "UTC"))
          hours #(mapv (fn [seg] (.getHour ^ZonedDateTime (:tick/beginning seg))) %)]
      (is (= [0 16 21] (hours (schedule/price-segments plan [] s e {:profile-alternatives {}})))
          "the plan's own zone walks")
      (is (= [0 16 21] (hours (schedule/price-segments plan [] (utc s) (utc e)
                                                       {:profile-alternatives {}})))
          "and so do bounds in UTC"))))

(deftest a-modifier-resolves-in-its-own-zone-too
  (testing "the base plan is not the only document with bounds. A modifier
            carrying its own TOU schedule is just as zone-sensitive, and
            resolve-modifier-prices standing alone has only the modifier's
            declared zone to anchor in. No fixture is: zero modifier versions in
            either tree carry hasTOUSchedule, so this borrows the constructed
            one urpx.price-test already builds.

            03:00 Pacific is inside its midnight-to-06:00 bracket; 10:00Z is the
            same moment, and used to read as 10:00 and fire nothing."
    (let [m  (price-test/own-tou-modifier-fixture)
          at (fn [z] (count (mapcat :urpx.resolved/tiers
                                    (:urpx.resolved/ledgers
                                     (price/resolve-modifier-prices m z)))))
          moment (ZonedDateTime/of 2026 7 15 3 0 0 0 la)]
      (is (= 1 (at moment)) "inside the bracket in Pacific")
      (is (apply = (map at (spellings moment)))
          "and in every spelling of that same moment")
      (let [outside (ZonedDateTime/of 2026 7 15 10 0 0 0 la)]
        (is (= 0 (at outside)) "outside it in Pacific")
        (is (apply = (map at (spellings outside)))
            "and outside in every spelling")))))

(deftest a-composition-resolves-in-the-base-plans-zone
  (testing "a composition answers ONE question about one moment, so both halves
            resolve in one frame: the base plan's. A modifier may declare its
            own, but resolving the halves in different zones would reintroduce
            the disagreement the projection exists to remove."
    (let [plan {:jsonld/type "urpx:RatePlan" :jsonld/id "test:base-plan"
                :urpx/timezoneIdentifier "America/Los_Angeles"
                :urpx/hasRatePlanVersion
                {:jsonld/id "x:p-v" :jsonld/type "urpx:RatePlanVersion"
                 :urpx/forRatePlan {:jsonld/id "test:base-plan"}
                 :urpx/effectiveDateTime (java.time.LocalDateTime/of 2026 1 1 0 0)
                 :urpx/hasPlanElements {} :urpx/hasPrices {:jsonld/type "urpx:Prices"}}}
          at (fn [z] (count (mapcat :urpx.resolved/tiers
                                    (:urpx.resolved/ledgers
                                     (price/resolve-prices-with-modifiers
                                      plan [(price-test/own-tou-modifier-fixture)] z {})))))
          moment (ZonedDateTime/of 2026 7 15 3 0 0 0 la)]
      (is (= 1 (at moment)))
      (is (apply = (map at (spellings moment)))
          "every spelling of the moment gives one answer"))))

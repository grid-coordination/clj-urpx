(ns urpx.v051-fixtures-test
  "The v0.5.1 half of the fixture suite: LADWP, migration wave 1.

  These are real migrated filings, not synthetic cases, and they are the first
  end-to-end exercise of the v0.5.1 work against data someone else produced.
  The v0.2.x PG&E fixtures stay alongside them deliberately: the corpus is
  mixed-vocabulary for the whole migration, so the suite should prove both
  vocabularies load through one library rather than proving them one at a time."
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [urpx.coerce :as coerce]
            [urpx.core :as core]
            [urpx.index :as index]
            [urpx.price :as price]
            [urpx.schema :as schema]
            [urpx.test-fixtures :as fixtures]
            [urpx.tz-inference :as tz])
  (:import [java.time ZonedDateTime ZoneId]))

(def ^:private la (ZoneId/of "America/Los_Angeles"))

(def ^:private plans     ["ladwp-r-1a-2026-04-01" "ladwp-r-1b-2026-04-01"])
(def ^:private modifiers ["ladwp-ez-save-2008-09-01" "ladwp-lifeline-2008-09-01"
                          "ladwp-nem-2008-09-01"])

(defn- version-of [e]
  (or (:urpx/hasRatePlanVersion e) (:urpx/hasRatePlanModifierVersion e)))

(deftest v051-fixtures-load-coerce-and-validate
  (testing "every wave-1 filing loads through the URPXPackage wrapper and
            validates; these are URPXPackage-wrapped, which the v0.2.x
            fixtures are not"
    (doseq [n plans]
      (let [plan (fixtures/load-rate-plan n)]
        (is (= "urpx:RatePlan" (:jsonld/type plan)) n)
        (is (nil? (coerce/explain schema/RatePlan plan)) n)))
    (doseq [n modifiers]
      (let [m (fixtures/load-modifier n)]
        (is (= "urpx:RatePlanModifier" (:jsonld/type m)) n)
        (is (nil? (coerce/explain schema/RatePlanModifier
                                  (coerce/coerce-rate-plan-modifier m))) n)))))

(deftest v051-fixtures-carry-the-back-pointer-and-the-nested-form
  (testing "wave 1 states the link BOTH ways: forRatePlan on the snapshot and
            the nested pointer kept. Dual-path resolution has to handle that,
            and it is the shape the migration actually produced rather than the
            one the spec suggested"
    (doseq [n plans]
      (let [plan (fixtures/load-rate-plan n)
            v    (version-of plan)]
        (is (some? v) (str n " keeps the nested version"))
        (is (some? (get-in v [:urpx/forRatePlan :jsonld/id]))
            (str n " also carries the back-pointer"))
        (is (= (:jsonld/id plan) (get-in v [:urpx/forRatePlan :jsonld/id]))
            (str n " back-pointer names its own plan"))
        (testing "and the back-pointer index finds it"
          (is (= [v] (get (index/versions-by-plan plan) (:jsonld/id plan)))))))))

(deftest v051-fixtures-carry-coverage-claims
  (testing "hasCoverageClaim is on all five, with an enforced completenessResult
            member. clj-urpx holds it optional until the last wave, so this
            proves the modeling rather than the requirement"
    (doseq [n (concat plans modifiers)]
      (let [e (if (some #{n} plans)
                (fixtures/load-rate-plan n)
                (fixtures/load-modifier n))
            claim (:urpx/hasCoverageClaim (version-of e))]
        (is (some? claim) (str n " carries a coverage claim"))
        (is (m/validate schema/CompletenessResult (:urpx/completenessResult claim))
            (str n " completenessResult is an enforced member"))))))

(deftest v051-timezone-inference-through-the-identity-chain
  (testing "v0.5.1 removed urpx:publishedBy from the version shapes and removed
            urpx:eiaId from Organization entirely. Inference has to read
            offeredBy, and reach the EIA id through
            hasIdentity -> hasIdentifier -> {scheme urpx:eiaScheme, notation}.
            Reading only the old keys returns nil here, silently."
    (doseq [n plans]
      (let [plan (fixtures/load-rate-plan n)
            v    (version-of plan)]
        (is (nil? (:urpx/publishedBy v)) (str n " has no publishedBy, as v0.5.1 requires"))
        (is (some? (:urpx/offeredBy v))  (str n " carries offeredBy instead"))
        (let [{:keys [zone inferred-from]} (tz/infer-zone plan {:version v})]
          (is (= la zone) n)
          (is (= :eia-id (:source inferred-from))
              (str n " resolves by EIA id, which only the identity chain now carries"))
          (is (= "11208" (:value inferred-from)) n))))))

(deftest v051-prices-resolve
  (testing "the point of all of it: a migrated v0.5.1 filing resolves prices"
    (doseq [n plans]
      (let [plan (fixtures/load-rate-plan n)
            r    (price/resolve-prices plan (ZonedDateTime/of 2026 7 15 17 0 0 0 la))]
        (is (seq (:urpx.resolved/ledgers r)) (str n " resolves at least one ledger"))))))

(deftest both-vocabularies-load-through-one-library
  (testing "the mixed-vocabulary claim, demonstrated rather than asserted: a
            v0.2.x filing and a v0.5.1 filing both load and validate in the same
            process, with no switch and no configuration"
    (let [old (fixtures/load-rate-plan "pge-e-1-2026-03-01")
          new (fixtures/load-rate-plan "ladwp-r-1a-2026-04-01")]
      (is (nil? (coerce/explain schema/RatePlan old)))
      (is (nil? (coerce/explain schema/RatePlan new)))
      (testing "and they really are different vocabularies"
        (is (some? (:urpx/publishedBy (version-of old))))
        (is (some? (:urpx/offeredBy   (version-of new))))))))

(deftest v051-wrapper-is-urpxpackage
  (testing "wave 1 wraps in urpx:URPXPackage, the v0.3.0 rename, where the
            v0.2.x fixtures use urpx:URPXDocument or no wrapper at all"
    (let [entities (fixtures/load-graph "ladwp-r-1a-2026-04-01")
          wrapper  (first entities)]
      (is (= "urpx:URPXPackage" (:jsonld/type wrapper)))
      (is (nil? (coerce/explain schema/URPXPackage (coerce/coerce-doc wrapper))))
      (testing "and the loader descends it to the inner plan"
        (is (= "urpx:RatePlan"
               (:jsonld/type (core/load-rate-plan
                              (.getPath (fixtures/fixture-resource "ladwp-r-1a-2026-04-01")))))))))) 

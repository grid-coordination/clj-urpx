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
  (:import [java.time LocalDate ZonedDateTime ZoneId]))

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
  (testing "the mixed-vocabulary claim, which now needs a SYNTHETIC v0.2.x
            document to state at all: as of migration wave 6 the corpus is
            entirely v0.5.1, so no vendored fixture carries the old shape any
            more. The dual acceptance is still real and still load-bearing for
            anyone whose filings have not migrated, so it keeps a test; the
            test just cannot borrow a fixture for it.

            The v0.2.x side below uses every key v0.5.1 moved or renamed:
            urpx:publishedBy rather than offeredBy, urpx:eiaId rather than the
            identity chain, and a nested version with no forRatePlan."
    (let [v021 {:jsonld/type "urpx:RatePlan" :urpx/timezoneIdentifier "America/Los_Angeles"
                :jsonld/id "ex:legacy-plan"
                :urpx/ratePlanUniqueIdentifier "L-1"
                :urpx/name "Legacy Residential"
                :urpx/initialEffectiveDate (LocalDate/of 2020 1 1)
                :urpx/hasRatePlanVersion
                {:jsonld/type "urpx:RatePlanVersion"
                 ;; deliberately NO forRatePlan and NO hasCoverageClaim: v0.2.1
                 ;; has neither term, and a v0.2.x document not carrying them is
                 ;; exactly what this test exists to prove still validates
                 :urpx/changeReason {:jsonld/id "urpx:rateCase"}
                 :urpx/serviceRole {:jsonld/id "urpx:bundledServiceRole"}
                 :urpx/publishedBy {:jsonld/type "urpx:Organization"
                                    :urpx/legalName "Pacific Gas & Electric Company"
                                    :urpx/eiaId "14328"
                                    :urpx/organizationType {:jsonld/id "urpx:investorOwnedUtility"}}
                 :urpx/hasPlanElements
                 {:jsonld/type "urpx:PlanElements"
                  :urpx/hasMetricInput [{:jsonld/type "urpx:MetricInput"
                                         :urpx/name "kWh"
                                         :urpx/metricInputType {:jsonld/id "urpx:measuredMetricInput"}}]
                  :urpx/hasBillingModel {:jsonld/type "urpx:BillingModel"
                                         :urpx/hasPriceDefinition []}}
                 :urpx/hasPrices {:jsonld/type "urpx:Prices"}}}
          v051 (fixtures/load-rate-plan "ladwp-r-1a-2026-04-01")]
      (testing "both validate against the same schema, with no switch and no
                configuration"
        (is (nil? (coerce/explain schema/RatePlan v021)))
        (is (nil? (coerce/explain schema/RatePlan v051))))
      (testing "and they really are different vocabularies"
        (is (some? (:urpx/publishedBy (version-of v021))))
        (is (some? (:urpx/offeredBy   (version-of v051))))
        (is (nil?  (:urpx/forRatePlan (version-of v021))))
        (is (some? (:urpx/forRatePlan (version-of v051)))))
      (testing "timezone inference resolves both, through different paths: the
                legacy urpx:eiaId key and the v0.5.1 identity chain"
        (is (= la (:zone (tz/infer-zone v021))))
        (is (= la (:zone (tz/infer-zone v051 {:version (version-of v051)}))))))))

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

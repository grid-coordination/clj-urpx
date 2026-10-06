(ns urpx.service-bracket-offset-samples-test
  "The urpx-dev service bracket offset samples: a PG&E Medical Baseline
  modifier whose adjusting brackets add 16.438 kWh per day to the E-TOU-C
  baseline-tier ladder, and negatives that each break one constraint.

  Each sample is merged with the urpx test case it names, vendored beside it.
  The samples are in JSON-LD flattened form."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [urpx.index :as index]
            [urpx.load :as load]
            [urpx.price :as price]
            [urpx.validate :as validate])
  (:import [java.time ZoneId ZonedDateTime]))

(def ^:private la (ZoneId/of "America/Los_Angeles"))
(def ^:private summer (ZonedDateTime/of 2026 7 15 17 0 0 0 la))
(def ^:private winter (ZonedDateTime/of 2026 12 15 17 0 0 0 la))

(defn- sample [f]
  (.getPath (io/resource (str "urpx-samples/service-bracket-offset/" f))))

(def ^:private base (sample "urpx-rate-plan_pge_e-tou-c.jsonld"))

(def ^:private modifier-ids #{"pge:e-tou-c-baseline-tier" "pge:medical-baseline"})

(defn- resolve-with
  "The E-TOU-C composition with its baseline-tier modifier and the modifiers in
   `files`, at `zdt`, in baseline territory P."
  [files zdt]
  (let [docs (load/documents (cons base (map sample files)))]
    (price/resolve-prices-with-modifiers
     (first (filter #(= "urpx:RatePlan" (:jsonld/type %)) docs))
     (filter #(modifier-ids (:jsonld/id %)) docs)
     zdt
     {:profile-alternatives {"pge:pa-baseline-territory-code" "pge:pa-baseline-p-b"}
      :versions-by-plan     (index/versions-by-plan docs)})))

(defn- bounds
  "{tier-number [lower upper]} on the Conservation Incentive Adjustment ladder."
  [result]
  (into {} (for [l (:urpx.resolved/ledgers result)
                 :when (= "Conservation Incentive Adjustment" (:urpx.resolved/ledger-name l))
                 t (:urpx.resolved/tiers l)]
             [(:urpx.resolved/tier-number t)
              [(:urpx.resolved/tier-lower-bound t) (:urpx.resolved/tier-upper-bound t)]])))

(defn- refusal [files]
  (try (resolve-with files summer) nil
       (catch clojure.lang.ExceptionInfo e (:reason (ex-data e)))))

(defn- schema-errors
  "The paths at which `urpx.validate/check-file` finds `f` invalid."
  [f]
  (vec (for [c (:checked (validate/check-file (sample f)))
             e (:errors (:error c))]
         (:in e))))

;; ---------------------------------------------------------------------------
;; Positive
;; ---------------------------------------------------------------------------

(deftest the-medical-baseline-raises-tier-1-upper-and-tier-2-lower-by-16-438
  (doseq [f ["pge-e-tou-c-medical-baseline.jsonld"
             "pge-e-tou-c-medical-baseline-mixed-container.jsonld"]
          zdt [summer winter]]
    (let [{[l1 u1] 1 [l2 u2] 2 [l3 u3] 3} (bounds (resolve-with [] zdt))
          with (bounds (resolve-with [f] zdt))]
      (testing (str f " at " zdt)
        (is (= [l1 (+ u1 16.438M)] (with 1)))
        (is (= [(+ l2 16.438M) u2] (with 2)))
        (is (= [l3 u3] (with 3)))
        (is (= [] (schema-errors f))))))
  (is (= [0M 29.938M] ((bounds (resolve-with ["pge-e-tou-c-medical-baseline.jsonld"] summer)) 1))
      "summer, territory P: 13.5 + 16.438"))

;; ---------------------------------------------------------------------------
;; Negatives that clj-urpx refuses or finds invalid
;; ---------------------------------------------------------------------------

(deftest a-negative-refuses-on-the-constraint-its-readme-names
  (testing "adjustsServiceTier sh:node: the target holds no adjusting brackets"
    (is (= :adjusted-tier-adjusts (refusal ["negative/neg-chain.jsonld"])))
    (is (= :adjusted-tier-adjusts (refusal ["negative/neg-self-target.jsonld"]))))
  (testing "adjustsServiceTier sh:class urpx:ServiceTier"
    (is (= :adjusted-tier-not-service-tier
           (refusal ["negative/neg-target-not-tier.jsonld"]))))
  (testing "adjustsServiceTier sh:maxCount 1"
    (is (= :multiple-adjusted-tiers (refusal ["negative/neg-two-targets.jsonld"]))))
  (testing "ServiceBracketShape sh:or: an offset needs adjustsServiceTier"
    (is (= :unresolvable-adjusted-tier
           (refusal ["negative/neg-offset-no-target.jsonld"])))))

(deftest a-negative-fails-validation-on-the-constraint-its-readme-names
  (let [bracket #(vector :urpx/hasPlanElements :urpx/hasBillingModel :urpx/hasServiceTiers 0
                         :urpx/hasServiceTier 0 :urpx/hasServiceBracket % :urpx/upperBoundOffset)]
    (testing "upperBoundOffset sh:datatype xsd:decimal"
      (is (= [(bracket 2)] (schema-errors "negative/neg-offset-string.jsonld"))))
    (testing "upperBoundOffset sh:maxCount 1"
      (is (= [(bracket 0)] (schema-errors "negative/neg-two-offsets.jsonld"))))))

;; ---------------------------------------------------------------------------
;; Negatives whose constraint only SHACL checks
;; ---------------------------------------------------------------------------

(deftest a-negative-only-shacl-rejects-validates-and-resolves
  (testing "ServiceBracketShape sh:or, absolute bounds and offsets exclusive: the
            adjusting bracket's upperBound is not read as a bound"
    (is (= [] (schema-errors "negative/neg-absolute-and-offset.jsonld")))
    (is (= [0M 29.938M] ((bounds (resolve-with ["negative/neg-absolute-and-offset.jsonld"] summer)) 1))))
  (testing "ServiceTierShape sh:or, a host holds only adjusting brackets: the
            absolute bracket beside them is not a bound of the host"
    (is (= [] (schema-errors "negative/neg-host-mixed.jsonld")))
    (is (= [0M 29.938M] ((bounds (resolve-with ["negative/neg-host-mixed.jsonld"] summer)) 1))))
  (testing "ServiceTierShape sh:or, a host carries no serviceTierNumber"
    (is (= [] (schema-errors "negative/neg-host-numbered.jsonld")))
    (is (= [0M 29.938M] ((bounds (resolve-with ["negative/neg-host-numbered.jsonld"] summer)) 1))))
  (testing "ServiceBracketShape sh:or, an adjusting bracket carries an offset: a
            bracket naming a tier with no offset adjusts nothing"
    (is (= [] (schema-errors "negative/neg-target-no-offset.jsonld")))
    (is (= [0M 29.938M] ((bounds (resolve-with ["negative/neg-target-no-offset.jsonld"] summer)) 1))))
  (testing "BillingModelShape sh:or, a price definition or adjusting brackets: a
            billing model with neither contributes nothing"
    (is (= [] (schema-errors "negative/neg-billingmodel-empty.jsonld")))
    (is (= (bounds (resolve-with [] summer))
           (bounds (resolve-with ["negative/neg-billingmodel-empty.jsonld"] summer))))))

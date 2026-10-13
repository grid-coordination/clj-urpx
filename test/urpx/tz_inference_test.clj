(ns urpx.tz-inference-test
  (:require [clojure.test :refer [deftest is testing]]
            [urpx.test-fixtures :as fixtures]
            [urpx.tz-inference :as tz])
  (:import [java.time ZoneId]))

(def la (ZoneId/of "America/Los_Angeles"))
(def ny (ZoneId/of "America/New_York"))

;; The fixtures' organizations carry an EIA id in their identity chain and a
;; urpx:legalName. Inference prefers the EIA id; legalName is the fallback. See
;; plan-with-only-legal-name.

(deftest pge-zone-inferred-from-eia-id
  (testing "the PG&E fixture carries an EIA id; inference resolves via the
            eia-id table (preferred over legalName when both present)"
    (let [plan (fixtures/load-rate-plan "pge-e-1-2026-03-01")
          {:keys [zone inferred-from]} (tz/infer-zone plan)]
      (is (= la zone))
      (is (= :eia-id (:source inferred-from)))
      (is (= "14328" (:value inferred-from))))))

(deftest unknown-utility-returns-nil
  (testing "A plan whose publisher is not in either table yields no inference"
    (let [base (fixtures/load-rate-plan "pge-e-1-2026-03-01")
          plan (update-in base
                          [:urpx/hasRatePlanVersion :urpx/offeredBy]
                          #(-> %
                               (dissoc :urpx/hasIdentity)
                               (assoc :urpx/legalName "Mystery Cooperative")))]
      (is (nil? (tz/infer-zone plan))))))

(deftest inference-also-works-for-modifiers
  (testing "a RatePlanModifier's offeredBy lives under
            hasRatePlanModifierVersion; inference handles either top-level type"
    (let [m (fixtures/load-modifier "pge-e-1-2026-03-01")
          {:keys [zone inferred-from]} (tz/infer-zone m)]
      (is (= la zone))
      (is (= :eia-id (:source inferred-from))))))

(deftest opts-extend-eia-table
  (testing "Caller-supplied :eia-id->zone is merged with defaults; caller entries win."
    (let [base (fixtures/load-rate-plan "pge-e-1-2026-03-01")
          plan (assoc-in base
                         [:urpx/hasRatePlanVersion :urpx/offeredBy :urpx/hasIdentity]
                         {:jsonld/type "urpx:OrganizationIdentity"
                          :urpx/hasIdentifier {:jsonld/type "urpx:OrganizationIdentifier"
                                               :urpx/scheme {:jsonld/id "urpx:eiaScheme"}
                                               :skos/notation "12345"}})
          opts {:eia-id->zone {"12345" "America/New_York"}}
          {:keys [zone inferred-from]} (tz/infer-zone plan opts)]
      (is (= ny zone))
      (is (= :eia-id (:source inferred-from)))
      (is (= "12345" (:value inferred-from))))))

(deftest caller-overrides-default-table-entry
  (testing "When caller passes the same EIA id as a built-in but a different zone,
            the caller's entry wins (built-ins are defaults, not overrides)"
    (let [plan (fixtures/load-rate-plan "pge-e-1-2026-03-01") ;; EIA id 14328
          opts {:eia-id->zone {"14328" "America/New_York"}}
          {:keys [zone]} (tz/infer-zone plan opts)]
      (is (= ny zone)))))

(deftest plan-with-only-legal-name
  (testing "Falls through to legalName when no EIA id is reachable."
    (let [base (fixtures/load-rate-plan "pge-e-1-2026-03-01")
          plan (update-in base
                          [:urpx/hasRatePlanVersion :urpx/offeredBy]
                          dissoc :urpx/hasIdentity)
          {:keys [inferred-from]} (tz/infer-zone plan)]
      (is (= :legal-name (:source inferred-from))))))

(deftest eia-id-read-from-every-spelling-of-the-identity-chain
  (testing "urpx:hasIdentity and urpx:hasIdentifier are both 0..N, so a
            conforming document may write either as a list or bare; every spelling
            must infer the same zone from the eia id."
    (let [base     (fixtures/load-rate-plan "pge-e-1-2026-03-01")
          org-path [:urpx/hasRatePlanVersion :urpx/offeredBy]
          [identity :as identities] (get-in base (conj org-path :urpx/hasIdentity))
          other    {:jsonld/type "urpx:OrganizationIdentity"
                    :urpx/hasIdentifier {:jsonld/type "urpx:OrganizationIdentifier"
                                         :urpx/scheme {:jsonld/id "urpx:otherScheme"}
                                         :skos/notation "X-1"}}
          with-id  #(assoc-in base (conj org-path :urpx/hasIdentity) %)
          infer    (comp :inferred-from tz/infer-zone)
          baseline (infer base)]
      (is (= 1 (count identities)) "sanity: the fixture writes one identity")
      (is (= {:source :eia-id :value "14328" :iana "America/Los_Angeles"} baseline))
      (is (= baseline (infer (with-id identity))) "hasIdentity bare")
      (is (= baseline (infer (with-id [other identity])))
          "the eia identity second in a list")
      (is (= baseline (infer (with-id (update identity :urpx/hasIdentifier
                                              #(into [(:urpx/hasIdentifier other)] %)))))
          "hasIdentifier as a list, the eia identifier second")
      (is (= baseline (infer (with-id (update identity :urpx/hasIdentifier first))))
          "hasIdentifier bare"))))

(deftest no-publisher-returns-nil
  (testing "Plan with no organization at all yields no inference."
    (let [base (fixtures/load-rate-plan "pge-e-1-2026-03-01")
          plan (update-in base [:urpx/hasRatePlanVersion] dissoc :urpx/offeredBy)]
      (is (nil? (tz/infer-zone plan))))))

;; ---------------------------------------------------------------------------
;; An un-nested snapshot
;; ---------------------------------------------------------------------------

(deftest offered-by-on-an-un-nested-snapshot
  (testing "Inference reads the offeredBy of a snapshot supplied beside its
            plan, or a document carrying its snapshot un-nested yields nil."
    (let [plan     (fixtures/load-rate-plan "pge-e-1-2026-03-01")
          version  (:urpx/hasRatePlanVersion plan)
          shell    (dissoc plan :urpx/hasRatePlanVersion)
          snapshot (assoc version :urpx/forRatePlan {:jsonld/id (:jsonld/id plan)})]
      (testing "the shell alone infers nothing, since the organization left with
                the version"
        (is (nil? (tz/infer-zone shell))))
      (testing "with the snapshot supplied, offeredBy resolves the same zone the
                nested snapshot does"
        (let [{:keys [zone inferred-from]} (tz/infer-zone shell {:version snapshot})]
          (is (= la zone))
          (is (= :eia-id (:source inferred-from))))))))

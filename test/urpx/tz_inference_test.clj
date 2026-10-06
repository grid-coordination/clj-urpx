(ns urpx.tz-inference-test
  (:require [clojure.test :refer [deftest is testing]]
            [urpx.test-fixtures :as fixtures]
            [urpx.tz-inference :as tz])
  (:import [java.time ZoneId]))

(def la (ZoneId/of "America/Los_Angeles"))
(def ny (ZoneId/of "America/New_York"))

;; v0.2.0 fixtures carry both urpx:eiaId and urpx:legalName on Organization.
;; Inference prefers eiaId (the standardized identifier); legalName remains the
;; documented fallback for fixtures that omit eiaId. See
;; plan-with-only-legal-name.

(deftest pge-zone-inferred-from-eia-id
  (testing "v0.2.0 PG&E fixtures carry urpx:eiaId; inference resolves via
            the eia-id table (preferred over legalName when both present)"
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
                               (assoc :urpx/eiaId "99999"
                                      :urpx/legalName "Mystery Cooperative")))]
      (is (nil? (tz/infer-zone plan))))))

(deftest inference-also-works-for-modifiers
  (testing "RatePlanModifier publishedBy lives under hasRatePlanModifierVersion;
            inference handles either top-level type"
    (let [m (fixtures/load-modifier "pge-e-1-2026-03-01")
          {:keys [zone inferred-from]} (tz/infer-zone m)]
      (is (= la zone))
      (is (= :eia-id (:source inferred-from))))))

(deftest opts-extend-eia-table
  (testing "Caller-supplied :eia-id->zone is merged with defaults; caller entries win.
            Injects the id through the LEGACY urpx:eiaId key, which v0.5.1
            documents no longer carry, so this covers the back-compatible arm
            of the reader; the identity-chain arm is covered below and by the
            wave-1 fixtures."
    (let [base (fixtures/load-rate-plan "pge-e-1-2026-03-01")
          plan (-> base
                   (update-in [:urpx/hasRatePlanVersion :urpx/offeredBy]
                              dissoc :urpx/hasIdentity)
                   (assoc-in [:urpx/hasRatePlanVersion :urpx/offeredBy :urpx/eiaId]
                             "12345"))
          opts {:eia-id->zone {"12345" "America/New_York"}}
          {:keys [zone inferred-from]} (tz/infer-zone plan opts)]
      (is (= ny zone))
      (is (= :eia-id (:source inferred-from)))
      (is (= "12345" (:value inferred-from))))))

(deftest caller-overrides-default-table-entry
  (testing "When caller passes the same EIA id as a built-in but a different zone,
            the caller's entry wins (built-ins are defaults, not overrides)"
    (let [base (fixtures/load-rate-plan "pge-e-1-2026-03-01")
          plan (assoc-in base
                         [:urpx/hasRatePlanVersion :urpx/offeredBy :urpx/eiaId]
                         "14328")  ;; PG&E's actual EIA id
          opts {:eia-id->zone {"14328" "America/New_York"}}
          {:keys [zone]} (tz/infer-zone plan opts)]
      (is (= ny zone)))))

(deftest plan-with-only-legal-name
  (testing "Falls through to legalName when no EIA id is reachable. v0.5.1
            carries it in the identity chain rather than as urpx:eiaId, so both
            have to go to exercise the fallback."
    (let [base (fixtures/load-rate-plan "pge-e-1-2026-03-01")
          plan (update-in base
                          [:urpx/hasRatePlanVersion :urpx/offeredBy]
                          dissoc :urpx/eiaId :urpx/hasIdentity)
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
  (testing "Plan with no organization at all yields no inference. v0.5.1 states
            it as urpx:offeredBy; urpx:publishedBy left the version shapes."
    (let [base (fixtures/load-rate-plan "pge-e-1-2026-03-01")
          plan (update-in base
                          [:urpx/hasRatePlanVersion]
                          dissoc :urpx/offeredBy :urpx/publishedBy)]
      (is (nil? (tz/infer-zone plan))))))

;; ---------------------------------------------------------------------------
;; v0.5.1: urpx:publishedBy left the version shapes; urpx:offeredBy replaced it
;; ---------------------------------------------------------------------------

(deftest offered-by-on-an-un-nested-snapshot
  (testing "v0.5.1 dropped urpx:publishedBy from RatePlanVersionShape, which is
            sh:closed, and put urpx:offeredBy there instead. Inference must read
            the snapshot's offeredBy, or every v0.5.1 document silently yields
            nil."
    (let [plan     (fixtures/load-rate-plan "pge-e-1-2026-03-01")
          version  (:urpx/hasRatePlanVersion plan)
          shell    (dissoc plan :urpx/hasRatePlanVersion)
          snapshot (assoc version :urpx/forRatePlan {:jsonld/id (:jsonld/id plan)})]
      (testing "the shell alone infers nothing, since the organization left with
                the version"
        (is (nil? (tz/infer-zone shell))))
      (testing "with the snapshot supplied, offeredBy resolves the same zone the
                nested publishedBy did"
        (let [{:keys [zone inferred-from]} (tz/infer-zone shell {:version snapshot})]
          (is (= la zone))
          (is (= :eia-id (:source inferred-from)))))
      (testing "the v0.2.x urpx:publishedBy key still resolves, for a document
                that has not migrated"
        (let [legacy (-> version
                         (dissoc :urpx/offeredBy)
                         (assoc :urpx/publishedBy (:urpx/offeredBy version)))]
          (is (= la (:zone (tz/infer-zone shell {:version legacy})))))))))

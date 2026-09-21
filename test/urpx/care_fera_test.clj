(ns urpx.care-fera-test
  "The first percentage discounts in the URPX corpus, pinned against the filed
  documents rather than against synthetic ones.

  A CARE or FERA discount is a urpx:Price carrying urpx:multiplier and
  urpx:basedOnPrice instead of a urpx:unitPrice: a percentage over a
  urpx:PriceSummary of the line items the program discounts. The summary lives
  in the BASE PLAN filing beside the prices it names; the discount lives in the
  MODIFIER filing, because the program is not a property of a plan that also
  serves customers who do not qualify. Resolving one needs both documents.

  SCE and SDG&E are both vendored because neither alone shows the variation,
  and a fixture set that showed only one would silently sanction whichever
  shape it happened to contain:

    SCE    ONE subtotal shared by both programs, which differ only in rate.
           The exemption credits sit inside the subtotal beside the charges
           they cancel, and the Base Services Charge is not carved out.

    SDG&E  TWO subtotals on ONE base plan with different member lists, because
           Schedule E-CARE excludes 'any applicable exemptions' and FERA grants
           none, so FERA pays the DWR Bond Charge and the Wildfire Fund NBC and
           CARE does not."
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [urpx.index :as index]
            [urpx.price :as price]
            [urpx.test-fixtures :as fixtures])
  (:import [java.time ZonedDateTime ZoneId]))

(def ^:private la (ZoneId/of "America/Los_Angeles"))
(defn- summer-peak [] (ZonedDateTime/of 2026 7 15 17 0 0 0 la))

(defn- tier-by-price-id [resolved pid]
  (->> (:urpx.resolved/ledgers resolved)
       (mapcat :urpx.resolved/tiers)
       (filter #(= pid (:urpx.resolved/price-id %)))
       first))

(defn- summaries [plan] (index/entities-of-type plan "urpx:PriceSummary"))

(defn- subtotal
  "Sum the summary's members straight out of the document, independent of the
   resolver, so the expected value is the filing's own arithmetic.

   Untiered members all contribute; a urpx:serviceTierNumber marks a block-tier
   ladder whose rungs are alternatives per kWh, so only the lowest contributes.
   SCE Schedule D names both Conservation Incentive Adjustment rungs, -0.03740
   baseline and +0.06368 over-baseline; summing both gives 0.37353, a subtotal
   matching no bill any customer can receive."
  [plan summary-id]
  (let [idx (index/build-index plan)
        nodes (->> (get-in (summaries plan) [summary-id :urpx/includesPrice])
                   (map #(get idx (:jsonld/id %)))
                   (remove nil?))
        {tiered true bare false} (group-by #(some? (:urpx/serviceTierNumber %)) nodes)]
    (->> (cond-> (vec bare)
           (seq tiered) (conj (first (sort-by :urpx/serviceTierNumber tiered))))
         (map :urpx/unitPrice)
         (remove nil?)
         (reduce + 0M))))

;; ---------------------------------------------------------------------------
;; SDG&E: two subtotals, one base plan, different members
;; ---------------------------------------------------------------------------

(def ^:private sdge-care "sdge:dr-2026-04-01-summary-care-discountable")
(def ^:private sdge-fera "sdge:dr-2026-04-01-summary-fera-discountable")

(deftest sdge-care-and-fera-have-different-member-lists
  (testing "two summaries on ONE base plan naming different line items. This is
            the case that cannot be reconstructed from the SCE filings, and the
            reason both utilities are vendored."
    (let [plan (fixtures/load-rate-plan "sdge-dr-2026-04-01")
          ss   (summaries plan)
          mem  (fn [id] (set (map :jsonld/id (get-in ss [id :urpx/includesPrice]))))]
      (is (= 2 (count ss)) "the base plan carries exactly two subtotals")
      (is (= 10 (count (mem sdge-care))))
      (is (= 12 (count (mem sdge-fera))))
      (testing "CARE's members are a strict subset of FERA's: FERA grants no
                exemptions, so it pays two charges CARE does not"
        (is (empty? (set/difference (mem sdge-care) (mem sdge-fera))))
        (is (= #{"sdge:dr-2026-04-01-price-dwr-bc" "sdge:dr-2026-04-01-price-wf-nbc"}
               (set/difference (mem sdge-fera) (mem sdge-care)))))
      (testing "the subtotals differ by ONE member's value even though they
                differ by TWO members, because the DWR Bond Charge is filed at
                zero. Membership and arithmetic are not the same claim, and a
                test that only checked the value would miss a dropped member."
        (let [idx (index/build-index plan)]
          (is (zero? (:urpx/unitPrice (get idx "sdge:dr-2026-04-01-price-dwr-bc"))))
          (is (= (:urpx/unitPrice (get idx "sdge:dr-2026-04-01-price-wf-nbc"))
                 (- (subtotal plan sdge-fera) (subtotal plan sdge-care)))))))))

(deftest sdge-discounts-resolve-across-documents
  (testing "the discount Prices live in the CARE and FERA modifier filings and
            name summaries in the base plan filing. resolve-prices-with-modifiers
            indexes across exactly the documents it is handed, so this works
            without any cross-file lookup being invented."
    (let [plan (fixtures/load-rate-plan "sdge-dr-2026-04-01")
          care (fixtures/load-modifier "sdge-care-2025-10-01")
          fera (fixtures/load-modifier "sdge-fera-2026-04-01")
          r    (price/resolve-prices-with-modifiers plan [care fera] (summer-peak))]
      (testing "CARE at 35 percent of its own subtotal"
        (let [t (tier-by-price-id r "sdge:care-price-bill-discount-2025-10-01")]
          (is (nil? (:urpx.resolved/unvaluable t)))
          (is (= (* -0.35M (subtotal plan sdge-care)) (:urpx.resolved/unit-price t)))
          (is (= -0.1450505M (:urpx.resolved/unit-price t)))))
      (testing "FERA at 18 percent of ITS subtotal, which is a different one"
        (let [t (tier-by-price-id r "sdge:fera-price-bill-discount-2026-04-01")]
          (is (nil? (:urpx.resolved/unvaluable t)))
          (is (= (* -0.18M (subtotal plan sdge-fera)) (:urpx.resolved/unit-price t)))
          (is (= -0.0756612M (:urpx.resolved/unit-price t)))))
      (testing "the two programs reach different subtotals, so a resolver that
                confused them would be caught here"
        (is (not= (subtotal plan sdge-care) (subtotal plan sdge-fera)))))))

(deftest sdge-discount-alone-cannot-resolve
  (testing "without the base plan there is no subtotal to discount, and that is
            reported by name rather than returned as a nil that reads as free"
    (let [care (fixtures/load-modifier "sdge-care-2025-10-01")
          t    (tier-by-price-id (price/resolve-modifier-prices care (summer-peak))
                                 "sdge:care-price-bill-discount-2025-10-01")]
      (is (some? t) "the tier still surfaces")
      (is (nil? (:urpx.resolved/unit-price t)))
      (is (= :unresolved-reference (:reason (:urpx.resolved/unvaluable t)))))))

;; ---------------------------------------------------------------------------
;; SCE: one subtotal, two programs, differing only in rate
;; ---------------------------------------------------------------------------

(def ^:private sce-shared "sce:d-2026-03-20-summary-discountable")

(deftest sce-shares-one-subtotal-between-both-programs
  (testing "the opposite shape from SDG&E: SCE does not carve out the Base
            Services Charge and its exemption credits sit inside the subtotal
            beside the charges they cancel, so CARE and FERA name the SAME
            13 line items and differ only in rate."
    (let [plan (fixtures/load-rate-plan "sce-d-2026-03-20")
          care (fixtures/load-modifier "sce-care-2026-03-20")
          fera (fixtures/load-modifier "sce-fera-2026-03-20")
          ss   (summaries plan)
          r    (price/resolve-prices-with-modifiers plan [care fera] (summer-peak))
          base (subtotal plan sce-shared)]
      (is (= 1 (count ss)) "one subtotal, shared")
      (is (= 13 (count (get-in ss [sce-shared :urpx/includesPrice]))))
      (testing "two of the thirteen are the CIA ladder's rungs, which are
                alternatives per kWh rather than components, so only the lowest
                enters the subtotal: 0.37353 summing both, 0.30985 correctly"
        (let [idx (index/build-index plan)
              tiered (->> (get-in ss [sce-shared :urpx/includesPrice])
                          (map #(get idx (:jsonld/id %)))
                          (filter :urpx/serviceTierNumber))]
          (is (= 2 (count tiered)))
          (is (= 0.30985M base))))

      (testing "CARE 32.5 percent"
        (let [t (tier-by-price-id r "sce:care-price-bill-discount-2026-03-20")]
          (is (nil? (:urpx.resolved/unvaluable t)))
          (is (= (* -0.325M base) (:urpx.resolved/unit-price t)))
          (is (= -0.10070125M (:urpx.resolved/unit-price t)))))

      (testing "FERA 18 percent of the SAME subtotal"
        (let [t (tier-by-price-id r "sce:fera-price-bill-discount-2026-03-20")]
          (is (nil? (:urpx.resolved/unvaluable t)))
          (is (= (* -0.18M base) (:urpx.resolved/unit-price t)))
          (is (= -0.0557730M (:urpx.resolved/unit-price t)))))

      (testing "both discounts derive from one subtotal, which is what
                distinguishes this filing shape from SDG&E's"
        (is (= (/ (:urpx.resolved/unit-price
                   (tier-by-price-id r "sce:care-price-bill-discount-2026-03-20"))
                  0.325M)
               (/ (:urpx.resolved/unit-price
                   (tier-by-price-id r "sce:fera-price-bill-discount-2026-03-20"))
                  0.18M)))))))

(deftest discounts-are-derived-not-stated
  (testing "none of the six discount Prices states a urpx:unitPrice. Every value
            in these tests is computed by the resolver from multiplier and
            basis, which is the whole point: 0.13.0 returned nil for all of
            them, and a nil unit price reads to a consumer as free."
    (doseq [[fix pid] [["sdge-care-2025-10-01" "sdge:care-price-bill-discount-2025-10-01"]
                       ["sdge-fera-2026-04-01" "sdge:fera-price-bill-discount-2026-04-01"]
                       ["sce-care-2026-03-20"  "sce:care-price-bill-discount-2026-03-20"]
                       ["sce-fera-2026-03-20"  "sce:fera-price-bill-discount-2026-03-20"]]]
      (let [node (get (index/build-index (fixtures/load-modifier fix)) pid)]
        (is (some? node) (str pid " must be in " fix))
        (is (nil? (:urpx/unitPrice node)) "no stated unit price")
        (is (some? (:urpx/multiplier node)))
        (is (some? (:urpx/basedOnPrice node)))))))

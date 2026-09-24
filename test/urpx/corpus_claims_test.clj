(ns urpx.corpus-claims-test
  "Claims about what the vendored fixtures contain, made executable.

  Several decisions in this library rest on a measurement of the 23 fixtures
  rather than on a rule: bracket selection short-circuits because no fixture
  qualifies a container, and the season readers were unified without moving a
  number because no fixture reaches the paths that changed. Those measurements
  were written into docstrings and the CHANGELOG, where they cannot fail. A
  re-pin, and URPX-sr4's is already planned, would make them false silently
  while the prose kept asserting them.

  So they live here instead. A failure is not a regression in the library: it
  says the corpus moved and a claim somewhere else needs re-measuring. Each
  test names where the claim is written down, so the failure points at the prose
  to fix.

  What is deliberately NOT here: rationale. Why an unevaluable season gate
  admits a PriceDefinition is a decision, not a measurement, and belongs in the
  docstring that explains it with a behavior test guarding it. These are only
  the countable facts."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [urpx.index :as index]
            [urpx.price :as price]
            [urpx.test-fixtures :as fixtures])
  (:import [java.time ZoneId ZonedDateTime]))

(def ^:private all-fixtures
  "Every vendored fixture, by short name. Checked against the directory below,
  so adding one without adding it here fails rather than going unmeasured."
  ["cpau-e-14-2025-07-01"
   "ladwp-ez-save-2008-09-01" "ladwp-lifeline-2008-09-01" "ladwp-nem-2008-09-01"
   "ladwp-r-1a-2026-04-01" "ladwp-r-1b-2026-04-01"
   "pge-e-1-2026-03-01" "pge-e-tou-c-2026-03-01"
   "pge-e-tou-c-baseline-tier-2026-03-01" "pge-e-tou-d-2026-03-27" "pge-ev2-2026-03-27"
   "sce-care-2026-03-20" "sce-d-2025-11-15" "sce-d-2026-03-20" "sce-fera-2026-03-20"
   "sce-tou-d-prime-2026-03-20" "sce-tou-d-prime-cpp-2026-03-20"
   "sdge-care-2025-10-01" "sdge-dr-2026-04-01" "sdge-ev-tou-2026-04-01"
   "sdge-fera-2026-04-01"
   "smud-cpp-2026-01-01" "smud-tod-5-8pm-2026-01-01"])

(defn- nodes
  "Every map in the fixture's graph, typed or not."
  [name-]
  (filter map? (tree-seq coll? #(if (map? %) (vals %) (seq %))
                         (fixtures/load-graph name-))))

(defn- of-type [name- t]
  (filter #(= t (:jsonld/type %)) (nodes name-)))

(defn- as-nodes
  "A 0..N property of an UNCOERCED node as a sequence.

  Necessary rather than tidy: these fixtures are read through `load-graph`,
  which does not coerce, and the corpus writes a single-valued 0..N property as
  a bare object. `count` over that map returns its key count, so a tier with one
  bracket measured 6. The same class as URPX-p8f, met while writing a test about
  it."
  [v]
  (cond (nil? v) nil (sequential? v) v :else [v]))

(defn- season-refs-of [node]
  (as-nodes (:urpx/appliesInSeason node)))

(defn- brackets-of [tier]
  (as-nodes (:urpx/hasServiceBracket tier)))

(deftest the-fixture-list-is-complete
  (testing "every .jsonld under test/resources/urpx-fixtures is named above, so
            a fixture cannot be added and escape the claims below."
    (let [on-disk (->> (.listFiles (io/file
                                    (.getParentFile
                                     (io/file
                                      (.getPath (fixtures/fixture-resource "pge-e-1-2026-03-01"))))))
                       (map #(.getName %))
                       (filter #(.endsWith ^String % ".jsonld"))
                       (map #(subs % 0 (- (count %) 7)))
                       sort)]
      (is (= (sort all-fixtures) on-disk))
      (is (= 23 (count on-disk))))))

;; ---------------------------------------------------------------------------
;; Tier and bracket shape. Cited by urpx.price/qualified-tier-node?,
;; urpx.price/service-tier-candidates, urpx.schema/ServiceTiers and BillingModel,
;; the urpx.tier-brackets-test ns docstring, and the 0.33.0 CHANGELOG entry.
;; ---------------------------------------------------------------------------

(deftest six-fixtures-carry-tiers-each-with-exactly-one-container
  (testing "`service-tier-candidates` groups by serviceTierNumber ACROSS
            containers so two declaring one number refuse instead of last-winning.
            No fixture has two, which is why that path is unexercised."
    (let [with-tiers (filter #(seq (of-type % "urpx:ServiceTiers")) all-fixtures)]
      (is (= ["ladwp-r-1a-2026-04-01"
              "pge-e-1-2026-03-01"
              "pge-e-tou-c-baseline-tier-2026-03-01"
              "sce-d-2025-11-15"
              "sce-d-2026-03-20"
              "sdge-dr-2026-04-01"]
             (sort with-tiers)))
      (doseq [f with-tiers]
        (is (= 1 (count (of-type f "urpx:ServiceTiers")))
            (str f " carries one urpx:ServiceTiers container"))))))

(deftest eighteen-single-bracket-tiers-across-the-five-base-plans
  (testing "the count `qualified-tier-node?`'s docstring and the 0.33.0
            CHANGELOG both state. One unqualified bracket each is what makes
            them take the short-circuit, so they cannot depend on the date or
            the caller's selection."
    (let [base ["ladwp-r-1a-2026-04-01" "pge-e-1-2026-03-01"
                "sce-d-2025-11-15" "sce-d-2026-03-20" "sdge-dr-2026-04-01"]
          tiers (mapcat #(of-type % "urpx:ServiceTier") base)]
      (is (= 18 (count tiers)))
      (doseq [t tiers]
        (is (= 1 (count (brackets-of t)))
            (str (:jsonld/id t) " carries exactly one bracket"))))))

(deftest no-container-anywhere-carries-either-qualifier
  (testing "so container scope is exercised only by constructed documents. If
            this fails, urpx.tier-brackets-test's claim that the corpus cannot
            reach that scope is no longer true."
    (doseq [f all-fixtures
            c (of-type f "urpx:ServiceTiers")]
      (is (empty? (season-refs-of c))
          (str (:jsonld/id c) " in " f " carries no appliesInSeason"))
      (is (nil? (:urpx/referencesProfileAlternative c))
          (str (:jsonld/id c) " in " f " carries no referencesProfileAlternative")))))

(deftest the-one-qualified-filing-is-a-modifier-with-120-brackets
  (testing "PG&E E-TOU-C's baseline-tier overlay: one container, three tiers,
            40 brackets each, every bracket carrying BOTH qualifiers. It is the
            only filing that qualifies brackets and the only one that names a
            Season its own document does not declare."
    (let [f "pge-e-tou-c-baseline-tier-2026-03-01"
          tiers (of-type f "urpx:ServiceTier")
          brackets (of-type f "urpx:ServiceBracket")]
      (is (= 3 (count tiers)))
      (is (= 120 (count brackets)))
      (doseq [t tiers]
        (is (= 40 (count (brackets-of t)))))
      (doseq [b brackets]
        (is (seq (season-refs-of b)) (str (:jsonld/id b) " carries appliesInSeason"))
        (is (some? (:urpx/referencesProfileAlternative b))
            (str (:jsonld/id b) " carries referencesProfileAlternative"))))))

;; ---------------------------------------------------------------------------
;; Season reader reachability. Cited by the urpx.season-readers-test ns
;; docstring and the 0.33.0 CHANGELOG entry, which says the unification moves
;; nothing BECAUSE none of these shapes occurs.
;; ---------------------------------------------------------------------------

(deftest no-time-bracket-names-more-than-one-season
  (testing "multiplicity went from refusing (via urpx.index/single) to
            disjunctive. Nothing in the corpus names several, so nothing moved."
    (doseq [f all-fixtures
            tb (of-type f "urpx:TimeBracket")]
      (is (>= 1 (count (season-refs-of tb)))
          (str (:jsonld/id tb) " in " f " names at most one Season")))))

(deftest no-price-level-node-carries-applies-in-season
  (testing "the claim I first wrote without its scope, and the reason the
            PriceDefinition change moves nothing HERE. It is a fact about this
            sample and not about URPX filings: the upstream corpus carries the
            property on 89 PriceDefinitions across six CPAU files."
    (doseq [f all-fixtures
            t ["urpx:PriceDefinition" "urpx:Price" "urpx:PriceSet"]
            n (of-type f t)]
      (is (empty? (season-refs-of n))
          (str (:jsonld/id n) " in " f " carries no appliesInSeason")))))

(deftest every-referenced-season-carries-months
  (testing "a Season without urpx:month makes the gate unevaluable, which is the
            third branch the readers now agree on. None is referenced here, so
            that branch is reached only by constructed documents."
    (doseq [f all-fixtures
            :let [declared (into {} (map (juxt :jsonld/id identity))
                                 (of-type f "urpx:Season"))]
            n (nodes f)
            ref (season-refs-of n)
            :let [s (get declared (:jsonld/id ref))]
            :when s]
      (is (seq (:urpx/month s))
          (str (:jsonld/id s) " in " f " carries urpx:month")))))

;; ---------------------------------------------------------------------------
;; The URPX-sr4 gap, pinned so the prose citing it cannot go stale quietly
;; ---------------------------------------------------------------------------

(deftest modifier-tiers-carry-no-number-and-no-bounds
  (testing "URPX-sr4, stated as a measurement rather than as a sentence.
            `tier-info` is called from `resolve-prices` only, so a
            urpx:RatePlanModifier's tiers surface untiered however its
            ServiceTiers are shaped. The E-TOU-C overlay's 120 brackets are
            therefore inert, which is why bracket selection could land without
            reaching them.

            THIS TEST IS EXPECTED TO FAIL WHEN URPX-sr4 LANDS. That is its
            purpose: several docstrings and the peer corpus's deferrals cite
            this behavior, and a failure here is the prompt to revisit them
            rather than a regression."
    (let [la (ZoneId/of "America/Los_Angeles")
          inst (ZonedDateTime/of 2026 7 15 17 0 0 0 la)
          m (fixtures/load-modifier "pge-e-tou-c-baseline-tier-2026-03-01")
          plan (fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")
          tiers-of (fn [r] (mapcat :urpx.resolved/tiers (:urpx.resolved/ledgers r)))]
      (testing "the document really does carry tiers, or this proves nothing"
        (is (= 3 (count (of-type "pge-e-tou-c-baseline-tier-2026-03-01" "urpx:ServiceTier"))))
        (is (= 4 (count (index/entities-of-type m "urpx:Price")))))
      (doseq [[label r] [["standalone" (price/resolve-modifier-prices m inst)]
                         ["composed" (price/resolve-prices-with-modifiers plan [m] inst)]]]
        (let [ts (tiers-of r)]
          (is (seq ts) (str label ": entries do surface"))
          (is (not-any? :urpx.resolved/tier-number ts)
              (str label ": no entry carries a tier number"))
          (is (not-any? :urpx.resolved/tier-lower-bound ts)
              (str label ": no entry carries a lower bound"))
          (is (not-any? :urpx.resolved/tier-bounds-unresolved ts)
              (str label ": and none is marked, because nothing read the brackets")))))))

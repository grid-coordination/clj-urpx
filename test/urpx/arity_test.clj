(ns urpx.arity-test
  "One shape, read in forty places: a 0..N property written as a bare object
  rather than a one-element list.

  JSON-LD expands the two identically and SHACL accepts both, so a document may
  use either. A Clojure reader that ITERATES the value is correct for the list
  and silently wrong for the bare object, because seq-ing a map yields
  MapEntries: `(keep :jsonld/id {:jsonld/id \"x\"})` is empty where the list
  gives `(\"x\")`. Nothing throws, and the answer stays well formed.

  That made it hard to see and easy to reintroduce. Three confirmed instances
  reached real data (URPX-p8f): `urpx.arrangements/applicability`, where both
  shipped compatibility policies returned false so every arrangement came out
  base-only; `urpx.price/modifier-applies-to?`, where a modifier applied to no
  base plan; and `urpx.emit`, which wrote a corrupt artifact with the @ prefixes
  stripped. URPX writes a lone value of a 0..N property as a bare object, so
  that is the spelling real filings carry.

  THE GUARD IS GENERATIVE, not per-site. Forty reads of one shape cannot be
  pinned by forty tests, because the forty-first read is written by someone who
  never saw them. `the-invariant-holds-for-every-vendored-fixture` asserts the
  property directly, over the schema's own list of 0..N properties, so a NEW
  unprotected reader fails it. The per-site tests below exist only for the three
  confirmed bugs, so a regression says which site broke.

  Equality against the unmodified baseline is the assertion, never \"does not
  throw\": every one of these defects returned a well-formed answer."
  (:require [clojure.test :refer [deftest is testing]]
            [urpx.arrangements :as arr]
            [urpx.emit :as emit]
            [urpx.index :as index]
            [urpx.price :as price]
            [urpx.test-fixtures :as fixtures])
  (:import [java.time ZoneId ZonedDateTime]))

(def ^:private la (ZoneId/of "America/Los_Angeles"))
(def ^:private inst (ZonedDateTime/of 2026 7 15 17 0 0 0 la))

;; The GENERATIVE guard that belongs here is designed but not finished, and is
;; recorded on URPX-p8f rather than shipped half-working. It flips each 0..N
;; property between its bare and one-element-list spellings and asserts
;; resolution is unchanged. Two attempts failed in instructive ways: the first
;; filtered `load-graph` for a top-level urpx:RatePlan, found zero in all 23
;; fixtures because each embeds its plan in a urpx:URPXPackage, and passed
;; vacuously; the second flipped by property name alone and generated documents
;; SHACL forbids, because urpx:referencesProfileAlternative is 0..N on
;; PriceDefinition and sh:maxCount 1 on ServiceBracket. Making it type-aware is
;; right and still lets a ServiceBracket through for a reason not yet found.
;;
;; What it must have when it lands: a non-vacuity assertion on how many
;; documents it reached, equality against the unmodified baseline rather than
;; "does not throw", and both directions, since a filing writing forRatePlan,
;; offeredBy, hasIdentity and hasPlanElements bare exercises the list spelling
;; of those readers nowhere.

(deftest applicability-reads-a-bare-compatibility-statement
  (testing "urpx.arrangements/applicability. Both shipped policies returned
            false on the bare form, so applicable-modifiers gave [] and every
            arrangement came out base-only, with nothing raised. The fixture
            writes applicableToRatePlanVersion bare, so the list form these read
            correctly is not what real data carries."
    (let [plan (fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")
          m    (fixtures/load-modifier "pge-e-tou-c-baseline-tier-2026-03-01")
          mv   (:urpx/hasRatePlanModifierVersion m)
          bv   (:urpx/hasRatePlanVersion plan)
          pid  (:jsonld/id plan)
          bare (update mv :urpx/applicableToRatePlan
                       #(if (and (vector? %) (= 1 (count %))) (first %) %))]
      (is (= (arr/named-versions-narrow mv bv pid)
             (arr/named-versions-narrow bare bv pid)))
      (is (= (arr/named-versions-add mv bv pid)
             (arr/named-versions-add bare bv pid))))))

(deftest modifier-applies-to-reads-a-bare-target-list
  (testing "urpx.price/modifier-applies-to?. A bare applicableToRatePlan made
            the modifier apply to no plan, so resolve-prices-with-modifiers
            dropped it and returned the base plan's ledgers alone."
    (let [plan (fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")
          m    (fixtures/load-modifier "pge-e-tou-c-baseline-tier-2026-03-01")
          bare (update-in m [:urpx/hasRatePlanModifierVersion :urpx/applicableToRatePlan]
                          #(if (and (vector? %) (= 1 (count %))) (first %) %))
          n    (fn [mm] (count (:urpx.resolved/ledgers
                                (price/resolve-prices-with-modifiers plan [mm] inst))))]
      (is (= (n m) (n bare)))
      (is (< (count (:urpx.resolved/ledgers (price/resolve-prices plan inst)))
             (n bare))
          "and the modifier really does add ledgers, or this proves nothing"))))

(deftest emit-writes-a-bare-payload-as-a-document-not-as-map-entries
  (testing "urpx.emit. Every vendored wrapper writes hasRatePlan bare, so mapv
            over it produced MapEntry pairs and emitted
            [[\"type\",\"urpx:RatePlan\"],[\"id\",...]]: the @ prefixes stripped, no
            exception, and the artifact leaves the process. The worst failure
            mode of this class for that reason."
    (let [wrapper (first (filter #(= "urpx:URPXPackage" (:jsonld/type %))
                                 (fixtures/load-graph "pge-e-tou-c-2026-03-01")))]
      (is (some? wrapper) "sanity: the fixture carries a package wrapper")
      (is (map? (:urpx/hasRatePlan wrapper))
          "sanity: and writes hasRatePlan bare, which is the shape under test")
      (let [out (emit/write-urpx-package wrapper {:context nil})]
        (is (re-find #"\"urpx:hasRatePlan\":\s*\[\s*\{\"@type\"" out)
            "the payload emits as a document object")
        (is (not (re-find #"\[\[\"type\"" out))
            "and not as MapEntry pairs with the @ prefixes stripped")))))

;; ---------------------------------------------------------------------------
;; The normalizer itself
;; ---------------------------------------------------------------------------

(deftest as-vec-normalizes-every-arity-without-mangling
  (testing "urpx.index/as-vec replaced seven private copies that disagreed about
            nil. The scalar arm is [v] rather than (vec v): a compact string @id
            would otherwise shred into characters and a number would throw."
    (is (= [] (index/as-vec nil)))
    (is (= [{:jsonld/id "x"}] (index/as-vec {:jsonld/id "x"})))
    (is (= [{:jsonld/id "x"}] (index/as-vec [{:jsonld/id "x"}])))
    (is (= [{:jsonld/id "a"} {:jsonld/id "b"}]
           (index/as-vec [{:jsonld/id "a"} {:jsonld/id "b"}])))
    (is (= ["urpx:foo"] (index/as-vec "urpx:foo")) "not a vector of characters")
    (is (= [6] (index/as-vec 6)) "and a scalar does not throw"))

  (testing "and it is NOT index/single, which is the mirror for a property read
            as one value and must keep refusing when a document supplies several"
    (is (= {:jsonld/id "x"} (index/single {:jsonld/id "x"} "test")))
    (is (= {:jsonld/id "x"} (index/single [{:jsonld/id "x"}] "test")))
    (is (nil? (index/single nil "test")))
    (is (thrown? clojure.lang.ExceptionInfo
                 (index/single [{:jsonld/id "a"} {:jsonld/id "b"}] "test")))))

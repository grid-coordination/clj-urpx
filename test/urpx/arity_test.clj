(ns urpx.arity-test
  "One shape, read in forty places: a 0..N property written as a bare object
  rather than a one-element list.

  JSON-LD expands the two identically and SHACL accepts both, so a document may
  use either. A Clojure reader that ITERATES the value is correct for the list
  and silently wrong for the bare object, because seq-ing a map yields
  MapEntries: `(keep :jsonld/id {:jsonld/id \"x\"})` is empty where the list
  gives `(\"x\")`. Nothing throws, and the answer stays well formed.

  That made it hard to see and easy to reintroduce. Three confirmed instances
  reached real data: `urpx.scenario-cost-model/applicability`,
  where both shipped compatibility policies returned false so every model came
  out base-only; `urpx.price/modifier-applies-to?`, where a modifier applied to
  no base plan; and `urpx.emit`, which wrote a corrupt artifact with the @
  prefixes stripped. URPX writes a lone value of a 0..N property as a bare object, so
  that is the spelling real filings carry.

  THE GUARD IS GENERATIVE, not per-site. Forty reads of one shape cannot be
  pinned by forty tests, because the forty-first read is written by someone who
  never saw them. `the-invariant-holds-for-every-vendored-fixture` asserts the
  property directly, over the schema's own list of 0..N properties, so a NEW
  unprotected reader fails it. The per-site tests below exist only for the three
  confirmed bugs, so a regression says which site broke.

  Equality against the unmodified baseline is the assertion, never \"does not
  throw\": every one of these defects returned a well-formed answer."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [malli.core :as m]
            [urpx.coerce :as coerce]
            [urpx.core :as core]
            [urpx.scenario-cost-model :as scm]
            [urpx.emit :as emit]
            [urpx.index :as index]
            [urpx.load :as load]
            [urpx.price :as price]
            [urpx.test-fixtures :as fixtures])
  (:import [java.time ZoneId ZonedDateTime]))

(def ^:private la (ZoneId/of "America/Los_Angeles"))
(def ^:private inst (ZonedDateTime/of 2026 7 15 17 0 0 0 la))

;; ---------------------------------------------------------------------------
;; The generative guard
;; ---------------------------------------------------------------------------

(def ^:private instants
  [inst (ZonedDateTime/of 2026 1 15 9 0 0 0 la)])

(defn- fixed-type
  "The @type a Malli map schema pins with [:= ...], or nil."
  [map-schema]
  (some (fn [[k _ v]]
          (when (= k :jsonld/type)
            (let [v (m/deref-all v)]
              (when (= := (m/type v)) (first (m/children v))))))
        (m/children map-schema)))

(def ^:private zero-to-n
  "{@type #{property}}: every property urpx.schema decodes to a vector, on the
   node type whose map schema declares it that way."
  (delay
    (let [acc (atom {})]
      (doseq [[_ v] (ns-publics 'urpx.schema)
              :let [s (try (m/schema @v) (catch Exception _ nil))]
              :when s]
        (m/walk s
                (fn [sch _ _ _]
                  (when (= :map (m/type sch))
                    (when-let [t (fixed-type sch)]
                      (doseq [[k props _] (m/children sch)
                              :when (:decode/urpx-jsonld props)]
                        (swap! acc update t (fnil conj #{}) k))))
                  sch)
                {::m/walk-refs true ::m/walk-schema-refs true}))
      @acc)))

(defn- rewrite
  "`doc` with `f` applied to every vector value of `prop` on a `type` node, and
   how many values `f` changed. `prop` nil rewrites every 0..N property."
  [doc type prop f]
  (let [n   (atom 0)
        out (walk/prewalk
             (fn [x]
               (if (and (map? x) (not (map-entry? x))
                        (or (nil? type) (= type (:jsonld/type x))))
                 (reduce (fn [x k]
                           (let [v (get x k)]
                             (if (and (vector? v) (not= v (f v)))
                               (do (swap! n inc) (assoc x k (f v)))
                               x)))
                         x
                         (if prop [prop] (get @zero-to-n (:jsonld/type x))))
                 x))
             doc)]
    [out @n]))

(defn- bare
  "`doc` with every one-element vector value of `prop` on a `type` node written
   bare, and how many it rewrote."
  [doc type prop]
  (rewrite doc type prop #(if (= 1 (count %)) (first %) %)))

(defn- first-only
  "`doc` with every several-element vector value of `prop` on a `type` node cut
   to its first element, and how many it cut."
  [doc type prop]
  (rewrite doc type prop #(if (< 1 (count %)) [(first %)] %)))

(defn- outcomes [f]
  (mapv #(try (f %) (catch Exception e [:threw (type e) (ex-message e)]))
        instants))

(defn- fixture-docs [load coerce]
  (for [f (sort (filter #(str/ends-with? (str %) ".jsonld")
                        (file-seq (io/file (io/resource "urpx-fixtures")))))
        :let [d (try (load (.getPath ^java.io.File f))
                     (catch clojure.lang.ExceptionInfo _ nil))]
        :when d]
    [(.getName ^java.io.File f) (coerce d)]))

(defn- flips
  "Every [type prop] flip of `doc`, plus all of them at once, as
   [label baseline flipped] for each that rewrote something. A [type prop
   :first-only] flip cuts several-element values to one in both documents, so
   that a position holding several values everywhere is still flipped."
  [doc]
  (concat
   (for [[t ps] @zero-to-n, p ps
         :let [[d n] (bare doc t p)]
         :when (pos? n)]
     [[t p] doc d])
   (for [[t ps] @zero-to-n, p ps
         :let [[one n] (first-only doc t p)]
         :when (pos? n)]
     [[t p :first-only] one (first (bare one t p))])
   [[:all doc (first (bare doc nil nil))]]))

(deftest the-invariant-holds-for-every-vendored-fixture
  (testing "After coercion, writing a one-element 0..N value bare changes no
            resolution, for a plan alone and for each modifier composed with
            its base, whichever document carries the bare value."
    (let [plans     (fixture-docs core/load-rate-plan coerce/coerce-rate-plan)
          by-id     (into {} (map (fn [[_ p]] [(:jsonld/id p) p])) plans)
          modifiers (fixture-docs core/load-rate-plan-modifier
                                  coerce/coerce-rate-plan-modifier)
          composed  (for [[f m] modifiers
                          :let [base (some #(by-id (:jsonld/id %))
                                           (get-in m [:urpx/hasRatePlanModifierVersion
                                                      :urpx/applicableToRatePlan]))]
                          :when base]
                      [f base m])
          n         (atom 0)
          diffs     (atom [])
          check     (fn [label f doc flipped]
                      (swap! n inc)
                      (when (not= (outcomes #(f doc %)) (outcomes #(f flipped %)))
                        (swap! diffs conj label)))]
      (doseq [[f plan] plans
              [label d0 d] (flips plan)]
        (check [f label] price/resolve-prices d0 d))
      (doseq [[f base m] composed]
        (doseq [[label d0 d] (flips m)]
          (check [f :modifier label]
                 (fn [mm zdt] (price/resolve-prices-with-modifiers base [mm] zdt)) d0 d))
        (doseq [[label d0 d] (flips base)]
          (check [f :base label]
                 (fn [b zdt] (price/resolve-prices-with-modifiers b [m] zdt)) d0 d)))
      (is (= [] @diffs))
      (testing "and it reached enough to mean something"
        (is (<= 13 (count plans)))
        (is (<= 14 (count modifiers)))
        (is (= (count modifiers) (count composed)) "every modifier finds its base")
        (is (<= 907 @n) "resolutions compared, flipped against unflipped")))))

(deftest the-invariant-holds-for-scenario-cost-models
  (testing "Writing each fixture's urpx:hasProfileAlternative bare changes no
            model that urpx.scenario-cost-model/enumerate yields, and no model
            from-node reads back from its node. Both sides first cut each list
            to one alternative, so that every list is written bare."
    (let [files   (sort (filter #(str/ends-with? (str %) ".jsonld")
                                (file-seq (io/file (io/resource "urpx-fixtures")))))
          ctx     (mapv #(get (json/read-str (slurp %)) "@context") files)
          opts    {:compatibility scm/named-versions-add}
          t       "urpx:ProfileAlternatives"
          p       :urpx/hasProfileAlternative
          cut     (map #(first-only % t p) (load/documents files))
          listed  (mapv first cut)
          flipped (map #(bare % t p) listed)
          flat    (mapv first flipped)
          shape   (juxt scm/identity-tuple :urpx.scenario-cost-model/selections)
          models  (scm/enumerate listed opts)
          reread  (fn [m]
                    (try (shape (scm/from-node flat
                                               (scm/node m scm/rate-plans-iri {:context ctx})
                                               (assoc opts :context ctx)))
                         (catch clojure.lang.ExceptionInfo e [:threw (ex-message e)])))]
      (is (contains? (get @zero-to-n t) p) "sanity: the schema decodes it as 0..N")
      (is (= (map shape models) (map shape (scm/enumerate flat opts))))
      (is (= (map shape models) (map reread models)))
      (testing "and it reached enough to mean something"
        (is (= (reduce + (map second cut)) (reduce + (map second flipped)))
            "every cut list is written bare")
        (is (<= 9 (reduce + (map second flipped))) "lists written bare")
        (is (<= 36 (count models)))
        (is (<= 7 (count (distinct (mapcat (comp keys :urpx.scenario-cost-model/selections)
                                           models))))
            "alternative containers selected from")))))

(deftest applicability-reads-a-bare-compatibility-statement
  (testing "urpx.scenario-cost-model/applicability. Both shipped policies
            returned false on the bare form, so applicable-modifiers gave [] and
            every model came out base-only, with nothing raised. The fixture
            writes applicableToRatePlanVersion bare, so the list form these read
            correctly is not what real data carries."
    (let [plan (fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")
          m    (fixtures/load-modifier "pge-e-tou-c-baseline-tier-2026-03-01")
          mv   (:urpx/hasRatePlanModifierVersion m)
          bv   (:urpx/hasRatePlanVersion plan)
          pid  (:jsonld/id plan)
          bare (update mv :urpx/applicableToRatePlan
                       #(if (and (vector? %) (= 1 (count %))) (first %) %))]
      (is (= (scm/named-versions-narrow mv bv pid)
             (scm/named-versions-narrow bare bv pid)))
      (is (= (scm/named-versions-add mv bv pid)
             (scm/named-versions-add bare bv pid))))))

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
        (is (re-find #"\"urpx:hasRatePlan\":\s*\[\s*\{\"@(id|type)\"" out)
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

(ns urpx.scenario-cost-model-test
  "Enumerating scenario cost models.

  A scenario cost model is one RatePlanVersion, the modifier versions a
  customer holds, and one ProfileAlternative per open dimension: the
  composition a urpx:ScenarioCostModel prices.

  The split that makes it tractable is what the documents can and cannot say.
  Compatibility, which modifier MAY apply to which plan, is stated, by
  urpx:applicableToRatePlan and urpx:applicableToRatePlanVersion. Which of them
  a customer HAS is not, and URPX excludes it deliberately. So this namespace
  answers the first and takes a policy for the second."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk]
            [clojure.test :refer [are deftest is testing]]
            [urpx.scenario-cost-model :as scm]
            [urpx.coerce :as coerce]
            [urpx.core :as core]
            [urpx.emit :as emit]
            [urpx.index :as index]
            [urpx.load :as load]
            [urpx.price :as price]
            [urpx.schedule :as schedule]
            [urpx.schema :as schema]
            [urpx.test-fixtures :as fixtures])
  (:import [java.io File StringReader]
           [java.time ZoneId ZonedDateTime]))

(def ^:private la (ZoneId/of "America/Los_Angeles"))
(defn- at
  ([] (at 2026 7 15 17))
  ([y m d h] (ZonedDateTime/of y m d h 0 0 0 la)))

(defn- sce-docs []
  [(fixtures/load-rate-plan "sce-d-2026-03-20")
   (fixtures/load-modifier "sce-care-2026-03-20")
   (fixtures/load-modifier "sce-fera-2026-03-20")])

(defn- rate [a] (price/marginal-unit-rate (scm/resolve-at a (at))))

(deftest discovery-answers-what-may-apply
  (testing "the query a consumer actually wants, and the half URPX can answer.
            urpx:applicableToRatePlan states compatibility with a plan
            identity."
    (let [docs (sce-docs)
          bv   (first (scm/plan-versions docs))]
      (is (= ["sce:d-2026-03-20"] (mapv :jsonld/id (scm/plan-versions docs))))
      (is (= ["sce:care-2026-03-20" "sce:fera-2026-03-20"]
             (mapv :jsonld/id (scm/applicable-modifiers docs bv)))
          "both income-tier programs are compatible with Schedule D")
      (is (= ["org:utility-sce"]
             (keys (scm/offered-by (scm/applicable-modifiers docs bv))))
          "and a broader search by offering organization works, since
           urpx:offeredBy is populated where applicableToRateGroup and
           hasServiceClass are not"))))

(deftest compatibility-is-not-possession
  (testing "CARE and FERA are BOTH returned for Schedule D and nothing in any
            document says a customer has at most one. There is no property
            anywhere in the URPX ontology expressing exclusivity or
            combinability between modifiers, and urpx:modifierCategory and
            urpx:modifierType, which might have served, are optional.

            So the library states compatibility and the caller states
            possession. That is why `enumerate` takes a policy rather than
            inventing one: inventing it here is the arbitrary pick this library
            removes everywhere else."
    (let [docs (sce-docs)
          ids  #(mapv :urpx.scenario-cost-model/modifier-version-ids %)]
      (is (= [[] ["sce:care-2026-03-20"] ["sce:fera-2026-03-20"]]
             (ids (scm/enumerate docs)))
          "the default policy is conservative: base alone, or one modifier")
      (is (= [[] ["sce:care-2026-03-20"] ["sce:fera-2026-03-20"]
              ["sce:care-2026-03-20" "sce:fera-2026-03-20"]]
             (sort (ids (scm/enumerate docs {:combine scm/every-subset}))))
          "every-subset is complete and knowingly wrong: it pairs two
           mutually exclusive programs, because no document forbids it"))))

(deftest each-model-resolves-to-its-own-price
  (testing "a model is fully determined, so it prices with no further
            input. These are the filed CARE and FERA discounts on Schedule D."
    (let [by-mods (into {} (map (juxt :urpx.scenario-cost-model/modifier-version-ids identity))
                        (scm/enumerate (sce-docs)))]
      (is (= 0.31085M    (rate (by-mods []))) "base alone")
      (is (= 0.19804875M (rate (by-mods ["sce:care-2026-03-20"]))) "CARE")
      (is (= 0.2550770M  (rate (by-mods ["sce:fera-2026-03-20"]))) "FERA"))))

(deftest an-open-dimension-makes-several-models
  (testing "a choice still outstanding is not one model but several, and
            each is a model in its own right. PG&E E-TOU-C carries a
            three-cell income-tier container, so its base-alone model is
            three."
    (let [docs [(fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")]
          as   (scm/enumerate docs)
          sels (map :urpx.scenario-cost-model/selections as)]
      (is (= 3 (count as)))
      (is (every? #(= 1 (count %)) sels) "one choice each")
      (is (= #{"pge:pa-income-tier-care" "pge:pa-income-tier-fera"
               "pge:pa-income-tier-standard"}
             (set (mapcat vals sels)))
          "one model per cell of the container"))))

(deftest identity-is-intrinsic-and-survives-equal-prices
  (testing "the tuple is computable from the documents alone and requires
            nothing external. Two models differ when it differs EVEN IF
            they resolve to the same price."
    (let [as (scm/enumerate (sce-docs))]
      (is (= (count as) (count (distinct (map scm/identity-tuple as))))
          "distinct models have distinct ids")
      (is (= ["sce:d-2026-03-20" ["sce:care-2026-03-20"] []]
             (scm/identity-tuple
              (first (filter #(= ["sce:care-2026-03-20"]
                                 (:urpx.scenario-cost-model/modifier-version-ids %)) as))))
          "base version, sorted modifier versions, sorted alternatives"))))

(deftest currency-drops-superseded-versions
  (testing "a superseded version is not a scenario cost model: nobody takes
            service under it. SCE Schedule D holds two versions, effective
            2025-11-15 and 2026-03-20, so an instant after the later one leaves
            one base version and an instant between them leaves the earlier."
    (let [docs [(fixtures/load-rate-plan "sce-d-2025-11-15")
                (fixtures/load-rate-plan "sce-d-2026-03-20")]]
      (is (= #{"sce:d-2025-11-15" "sce:d-2026-03-20"}
             (set (map :jsonld/id (scm/plan-versions docs))))
          "both, with no instant: the whole expressed space")
      (is (= ["sce:d-2026-03-20"]
             (mapv :jsonld/id (scm/plan-versions docs {:at (at)})))
          "the later one in July")
      (is (= ["sce:d-2025-11-15"]
             (mapv :jsonld/id (scm/plan-versions docs {:at (at 2026 1 15 12)})))
          "and the earlier one in January, before its successor took effect"))))

(deftest resolve-at-binds-each-document-to-its-own-version
  (testing "both :version defects this library fixed were an opt reaching a
            document it did not belong to. A model binds each piece by
            construction, so resolve-at passes a per-document :version map and
            the mistake becomes unrepresentable rather than repeatedly fixable.

            Extra opts merge over the model's own."
    (let [a (first (filter #(= ["sce:care-2026-03-20"]
                               (:urpx.scenario-cost-model/modifier-version-ids %))
                           (scm/enumerate (sce-docs))))]
      (is (= 0.19804875M (rate a)))
      ;; Pass-through is asserted in its own deftest below, on a fixture whose
      ;; resolution actually consults the opt.
      (doseq [k [:version :profile-alternatives]]
        (is (= :urpx.scenario-cost-model/opt-determined-by-model
               (try (scm/resolve-at a (at) {k {}})
                    (catch clojure.lang.ExceptionInfo e (:reason (ex-data e))))
               )
            (str k " is determined by the model, so it is refused rather "
                 "than merged: letting the caller win restores the very defect "
                 "this function prevents, and letting the model win "
                 "ignores an opt the caller meant"))))))

(deftest caller-opts-reach-urpx-price
  (testing "the assertion that used to live in the test above passed a :holiday?
            opt and compared the result to the SAME number the un-opted call
            returns, so the wrong opt, the opposite opt and a nonsense opt all
            satisfied it. It compared a value to itself, and four audit lenses
            found it independently.

            It cannot be rescued by comparing values: measured across the
            fixtures at five instants, (constantly true) and (constantly false)
            give identical rates every time, which 0.28.0 already recorded. And
            SCE Schedule D never consults the predicate at all, so a spy there
            reads zero whether or not the opt arrived.

            So: observe the opt being USED, on the one fixture that uses it.
            PG&E E-TOU-C invokes the holiday predicate exactly once while
            resolving, so a predicate counting its own calls distinguishes an
            opt that arrived from one that was dropped, whatever the number."
    (let [a      (first (scm/enumerate [(fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")]))
          called (atom 0)]
      (price/marginal-unit-rate
       (scm/resolve-at a (at) {:holiday? (fn [_] (swap! called inc) false)}))
      (is (pos? @called)
          "resolve-at must forward caller opts: this predicate is invoked only
           if :holiday? reached urpx.price"))))

;; ---------------------------------------------------------------------------
;; Constructed documents.
;;
;; Hand-built rather than vendored, because the cases below are about SHAPES
;; real filings may or may not happen to contain today, and a test that only
;; holds while a filing stays as it is tests the filing.
;; ---------------------------------------------------------------------------

(defn- ldt [y m d] (java.time.LocalDateTime/of y m d 0 0))

(defn- plan-doc
  "A minimal coerced urpx:RatePlan carrying one version snapshot. `extra` is
   merged into the version node, which is how these tests vary one field."
  ([id vid eff] (plan-doc id vid eff nil))
  ([id vid eff extra]
   {:jsonld/id id :jsonld/type "urpx:RatePlan" :urpx/timezoneIdentifier "America/Los_Angeles"
    :urpx/hasRatePlanVersion
    (merge {:jsonld/id vid :jsonld/type "urpx:RatePlanVersion"
            :urpx/forRatePlan {:jsonld/id id}
            :urpx/effectiveDateTime eff}
           extra)}))

(deftest one-id-naming-two-snapshots-is-refused
  (testing "an @id identifies a thing, so two different things under one @id is
            a contradiction rather than a choice.

            This was silently broken by (apply max-key ...), which returns the
            LAST maximal element, so the surviving snapshot depended on the
            order the documents arrived in, with no error either way.

            NO ESCAPE HATCH, unlike urpx.price's :ambiguous-version. There the
            caller has two distinguishable snapshots and can name one; here both
            carry the same @id, so any option would be asking the caller to pick
            arbitrarily."
    (let [docs [(plan-doc "x:p" "x:p-v1" (ldt 2026 1 1))
                (plan-doc "x:p" "x:p-v1" (ldt 2026 1 1) {:urpx/version "differs"})]
          e    (is (thrown? clojure.lang.ExceptionInfo (scm/enumerate docs)))]
      (is (= :urpx.scenario-cost-model/conflicting-version-id (:reason (ex-data e))))
      (is (= ["x:p-v1"] (:ids (ex-data e))) "the message names the offending @id"))))

(deftest every-conflicting-id-is-named-at-once
  (testing "naming only the first costs a caller one refusal round per bad
            filing, which is the lesson already recorded against
            :outside-version-window in 0.27.0."
    (let [docs [(plan-doc "x:a" "x:a-v" (ldt 2026 1 1))
                (plan-doc "x:a" "x:a-v" (ldt 2026 1 1) {:urpx/version "differs"})
                (plan-doc "x:b" "x:b-v" (ldt 2026 1 1))
                (plan-doc "x:b" "x:b-v" (ldt 2026 1 1) {:urpx/version "differs"})]
          e    (is (thrown? clojure.lang.ExceptionInfo (scm/enumerate docs)))]
      (is (= ["x:a-v" "x:b-v"] (:ids (ex-data e)))
          "both, in one refusal, not one per round trip"))))

(deftest the-same-snapshot-published-twice-is-deduplicated
  (testing "two filings carrying a byte-identical version node are one version
            published twice. That is benign and must NOT refuse: only differing
            content is a contradiction. Before this deduplicated, a repeat
            produced duplicate models."
    (let [one  [(plan-doc "x:p" "x:p-v1" (ldt 2026 1 1))]
          twice (conj one (plan-doc "x:p" "x:p-v1" (ldt 2026 1 1)))]
      (is (= 1 (count (scm/enumerate one))))
      (is (= (scm/enumerate one) (scm/enumerate twice))
          "publishing it twice changes nothing at all"))))

(deftest a-tie-on-the-latest-effective-instant-is-refused
  (testing "two DISTINCT snapshots of one identity sharing the latest effective
            instant. urpx.price refuses this with :ambiguous-version and says
            nothing here picks among them; a namespace sitting on top of
            urpx.price must not quietly undo that refusal.

            Constructed, so the guard does not depend on a filing happening to
            carry the shape."
    (let [docs [(plan-doc "x:p" "x:p-v1" (ldt 2026 1 1))
                (plan-doc "x:p" "x:p-v2" (ldt 2026 1 1))]
          e    (is (thrown? clojure.lang.ExceptionInfo
                            (scm/enumerate docs {:at (at 2026 6 1 12)})))]
      (is (= :urpx.scenario-cost-model/ambiguous-version (:reason (ex-data e))))
      (is (= ["x:p-v1" "x:p-v2"] (sort (:ids (ex-data e))))))))

(deftest uncoerced-documents-are-refused-not-answered
  (testing "the defect a cross-check probe walked into. urpx.core/load-graph
            returns PARSED but UNTYPED entities, so urpx:effectiveDateTime is
            still a string. With an instant that threw a ClassCastException from
            deep inside; with no instant nothing compares a date at all, so the
            untyped documents sailed through and 202 pairs came back where the
            coerced form gives 144. A plausible number, not a refusal, and in a
            CI gate it would have read as an enumeration disagreement between
            two implementations.

            Every public entry point guards, since each is reachable first."
    (let [raw (fixtures/load-graph "sce-d-2026-03-20")]
      (doseq [[nm f] [["enumerate"            #(scm/enumerate raw)]
                      ["enumerate :at"        #(scm/enumerate raw {:at (at)})]
                      ["plan-versions"        #(scm/plan-versions raw)]]]
        (let [e (is (thrown? clojure.lang.ExceptionInfo (f)) nm)]
          (is (= :uncoerced-document (:reason (ex-data e))) nm))))))

(deftest the-answer-does-not-depend-on-document-order
  (testing "the property the max-key tie-break silently broke. Asserted on the
            FULL result rather than on counts, since the order dependence moved
            content under an unchanged count as well as moving the count."
    (let [docs (sce-docs)]
      (doseq [perm [[0 1 2] [2 1 0] [1 0 2] [1 2 0] [2 0 1]]]
        (is (= (scm/enumerate docs {:at (at)})
               (scm/enumerate (mapv docs perm) {:at (at)}))
            (str "permutation " perm " must give an identical result"))))))

(deftest at-restricts-to-current-versions
  (testing "the :at option had no assertion at all: making it a no-op left all
            346 tests green. SCE Schedule D holds two versions, so an instant
            before the later one takes effect must not see it."
    (let [docs [(fixtures/load-rate-plan "sce-d-2025-11-15")
                (fixtures/load-rate-plan "sce-d-2026-03-20")]]
      (is (= 2 (count (scm/enumerate docs)))
          "both versions with no instant: the whole expressed space")
      (is (= ["sce:d-2026-03-20"]
             (mapv :urpx.scenario-cost-model/version-id (scm/enumerate docs {:at (at)}))))
      (is (= ["sce:d-2025-11-15"]
             (mapv :urpx.scenario-cost-model/version-id
                   (scm/enumerate docs {:at (at 2026 1 15 12)})))))))

(deftest a-version-stating-only-effectiveDate-is-not-dropped
  (testing "urpx:effectiveDateTime and urpx:effectiveDate are both sh:minCount 0,
            so a version may state either. This filtered on effectiveDateTime
            and silently dropped the rest, removing a whole plan from the
            enumeration while urpx.price resolved the same document normally.

            The fix was to stop re-deriving: urpx.price/version-effective-at is
            now the single reader, and it prefers effectiveDateTime and falls
            back to effectiveDate at start of day."
    (let [docs [{:jsonld/id "x:p" :jsonld/type "urpx:RatePlan" :urpx/timezoneIdentifier "America/Los_Angeles"
                 :urpx/hasRatePlanVersion
                 {:jsonld/id "x:p-v" :jsonld/type "urpx:RatePlanVersion"
                  :urpx/forRatePlan {:jsonld/id "x:p"}
                  :urpx/effectiveDate (java.time.LocalDate/of 2026 1 1)}}]]
      (is (= ["x:p-v"] (mapv :urpx.scenario-cost-model/version-id (scm/enumerate docs)))
          "present with no instant")
      (is (= ["x:p-v"] (mapv :urpx.scenario-cost-model/version-id
                             (scm/enumerate docs {:at (at)})))
          "and in force at an instant after that date"))))

(deftest each-model-carries-the-document-its-snapshot-came-from
  (testing "the 0.28.0 defect class a third time. The document was recovered by
            version @id from an (into {} ...) map, so when two documents publish
            one @id the loser's snapshot was paired with the winner's document.

            Identity, not @id: the bound document must be the one that literally
            contains this node."
    (let [docs (sce-docs)]
      (doseq [a (scm/enumerate docs)]
        (letfn [(nodes [d k] (filter #(and (map? %) (= k (:jsonld/type %)))
                                     (tree-seq coll? seq d)))]
          (is (some #(identical? (:urpx.scenario-cost-model/version a) %)
                    (nodes (:urpx.scenario-cost-model/rate-plan a) "urpx:RatePlanVersion"))
              "base version must be a node of its own bound document")
          (is (every? true?
                      (map (fn [mv md]
                             (boolean (some #(identical? mv %)
                                            (nodes md "urpx:RatePlanModifierVersion"))))
                           (:urpx.scenario-cost-model/modifier-versions a)
                           (:urpx.scenario-cost-model/modifiers a)))
              "and so must each modifier version"))))))


(defn- modifier-doc
  "A minimal coerced urpx:RatePlanModifier carrying one version snapshot.
   `applic` is merged into the version node so each test states exactly the
   compatibility properties it is about."
  [id vid eff applic]
  {:jsonld/id id :jsonld/type "urpx:RatePlanModifier" :urpx/timezoneIdentifier "America/Los_Angeles"
   :urpx/hasRatePlanModifierVersion
   (merge {:jsonld/id vid :jsonld/type "urpx:RatePlanModifierVersion"
           :urpx/forRatePlanModifier {:jsonld/id id}
           :urpx/effectiveDateTime eff}
          applic)})

(defn- two-version-plan
  "One document carrying TWO versions of one plan identity: v1 runs
   2026-01-01 to 2026-06-01, v2 from 2026-06-01. No vendored fixture carried
   more than one RatePlanVersion when this was written, which is why the
   binding below was invisible to every test until then."
  []
  {:jsonld/id "x:p" :jsonld/type "urpx:RatePlan" :urpx/timezoneIdentifier "America/Los_Angeles"
   :urpx/hasRatePlanVersion
   [{:jsonld/id "x:p-v1" :jsonld/type "urpx:RatePlanVersion"
     :urpx/forRatePlan {:jsonld/id "x:p"}
     :urpx/effectiveDateTime  (ldt 2026 1 1)
     :urpx/expirationDateTime (ldt 2026 6 1)}
    {:jsonld/id "x:p-v2" :jsonld/type "urpx:RatePlanVersion"
     :urpx/forRatePlan {:jsonld/id "x:p"}
     :urpx/effectiveDateTime (ldt 2026 6 1)}]})

(deftest resolve-at-uses-the-models-version-not-urpx-prices-own-choice
  (testing "the per-document :version map is the whole reason this namespace
            offers resolve-at, and deleting it outright changed no number in
            1382 assertions. It was invisible because every fixture document
            carries exactly ONE version, so urpx.price's own rules could only
            ever reach the same snapshot.

            Made visible with a document carrying two: build the model at
            an instant only v1 covers, then resolve at an instant only v2
            covers.

            The binding turns out to do more than choose. Without it urpx.price
            refuses the document outright, \"Multiple nested version snapshots;
            this library reads one. Pass the intended node explicitly.\" So the
            :version map is not an optimization over urpx.price's own rules, it
            is what makes a multi-version document resolvable at all, and a
            model supplies it by construction."
    (let [docs [(two-version-plan)]
          a    (first (scm/enumerate docs {:at (at 2026 3 1 12)}))
          res  (try (scm/resolve-at a (at 2026 7 15 17))
                    (catch clojure.lang.ExceptionInfo e
                      {::refused (or (:reason (ex-data e)) (:what (ex-data e)))}))]
      (is (= "x:p-v1" (:urpx.scenario-cost-model/version-id a))
          "the March instant selects v1")
      (is (nil? (::refused res))
          "resolution must succeed: unbound, urpx.price cannot choose between
           the two nested snapshots and refuses")
      (is (= "x:p-v1" (:version-id (:urpx.resolved/outside-version-window res)))
          "and it is v1 that was used, marked out of window, rather than the
           version urpx.price would have picked for July on its own"))))

(deftest the-two-compatibility-readings-are-both-available
  (testing "URPX does not say whether naming a urpx:applicableToRatePlanVersion
            NARROWS urpx:applicableToRatePlan or ADDS to it, so neither is
            baked in. The property is a plain owl:ObjectProperty,
            owl:FunctionalProperty appears zero times in the ontology, and
            sh:closed closes the property set rather than the value set.

            The default adds, which is sound under RDF: merging adds triples
            and never retracts, so a larger corpus cannot yield fewer models.
            The narrowing reading matches the ontology's wording.

            CONSTRUCTED, because the readings differ only when a modifier names
            some version, about a base version it does not name whose plan it
            names."
    (let [plan (plan-doc "x:p" "x:p-v1" (ldt 2026 1 1))
          m    (modifier-doc "x:m" "x:m-v" (ldt 2026 1 1)
                             {:urpx/applicableToRatePlan        [{:jsonld/id "x:p"}]
                              :urpx/applicableToRatePlanVersion [{:jsonld/id "x:other-v"}]})
          bv   (:urpx/hasRatePlanVersion plan)
          ids  #(mapv :jsonld/id (scm/applicable-modifiers [plan m] bv %))]
      (is (= ["x:m-v"] (ids nil))
          "default: the version statement adds, so the plan statement still
           applies and the modifier is compatible")
      (is (= ["x:m-v"] (ids {:compatibility scm/named-versions-add}))
          "stated explicitly, same answer")
      (is (= [] (ids {:compatibility scm/named-versions-narrow}))
          "narrow: naming a version means those and only those, so naming
           x:other-v excludes this one even though its PLAN is named")
      (is (= [[]]
             (mapv :urpx.scenario-cost-model/modifier-version-ids
                   (scm/enumerate [plan m] {:compatibility scm/named-versions-narrow})))
          "and the policy reaches models, not only applicable-modifiers"))))

(deftest a-modifier-naming-no-plan-and-no-version-combines-with-nothing
  (testing "both properties are sh:minCount 0, so a conformant modifier version
            may state neither. It then expresses no compatibility at all, and
            the reading that refuses rather than guesses is that it combines
            with nothing: treating silence as universal compatibility would
            attach every such modifier to every plan in the corpus."
    (let [plan (plan-doc "x:p" "x:p-v1" (ldt 2026 1 1))
          m    (modifier-doc "x:m" "x:m-v" (ldt 2026 1 1) {})]
      (is (= [] (mapv :jsonld/id (scm/applicable-modifiers [plan m]
                                                           (:urpx/hasRatePlanVersion plan)))))
      (is (= [[]] (mapv :urpx.scenario-cost-model/modifier-version-ids
                        (scm/enumerate [plan m])))
          "so the plan has exactly one model, itself, and the modifier
           contributes none"))))

(deftest applicable-modifiers-honors-at
  (testing "its :at option is documented and had no assertion: making it a
            no-op left all 356 tests green. A modifier with a superseded
            version and a current one must offer only the current one."
    (let [plan (plan-doc "x:p" "x:p-v1" (ldt 2026 1 1))
          old  {:jsonld/id "x:m" :jsonld/type "urpx:RatePlanModifier" :urpx/timezoneIdentifier "America/Los_Angeles"
                :urpx/hasRatePlanModifierVersion
                [{:jsonld/id "x:m-old" :jsonld/type "urpx:RatePlanModifierVersion"
                  :urpx/forRatePlanModifier {:jsonld/id "x:m"}
                  :urpx/effectiveDateTime (ldt 2026 1 1)
                  :urpx/applicableToRatePlan [{:jsonld/id "x:p"}]}
                 {:jsonld/id "x:m-new" :jsonld/type "urpx:RatePlanModifierVersion"
                  :urpx/forRatePlanModifier {:jsonld/id "x:m"}
                  :urpx/effectiveDateTime (ldt 2026 6 1)
                  :urpx/applicableToRatePlan [{:jsonld/id "x:p"}]}]}
          bv   (:urpx/hasRatePlanVersion plan)]
      (is (= ["x:m-new" "x:m-old"]
             (sort (mapv :jsonld/id (scm/applicable-modifiers [plan old] bv))))
          "both, with no instant: the whole expressed space")
      (is (= ["x:m-old"] (mapv :jsonld/id (scm/applicable-modifiers [plan old] bv
                                                                    {:at (at 2026 3 1 12)})))
          "in March only the first has taken effect")
      (is (= ["x:m-new"] (mapv :jsonld/id (scm/applicable-modifiers [plan old] bv
                                                                    {:at (at 2026 7 1 12)})))
          "in July the later one supersedes it"))))

(deftest identity-tuple-distinguishes-models-that-differ-only-by-selection
  (testing "the selections component of the id was untested: dropping it left
            three genuinely different PG&E E-TOU-C models sharing one id,
            with the suite green. They have the same base version, the same
            (empty) modifier set, and differ ONLY in which income-tier
            alternative is chosen, so selections are the only thing that can
            tell them apart."
    (let [as  (scm/enumerate [(fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")])
          ids (mapv scm/identity-tuple as)]
      (is (= 3 (count as)))
      (is (= 1 (count (distinct (map :urpx.scenario-cost-model/version-id as))))
          "same base version")
      (is (= 1 (count (distinct (map :urpx.scenario-cost-model/modifier-version-ids as))))
          "and the same modifier set, so the id has nothing else to go on")
      (is (= 3 (count (distinct ids)))
          "three distinct ids, which holds only if selections are in the id"))))

(defn- containers
  "A urpx:PlanElements carrying one urpx:ProfileAlternatives per entry of
   `cells`, a map of container @id to its alternative @ids."
  [cells]
  {:urpx/hasPlanElements
   {:jsonld/type "urpx:PlanElements"
    :urpx/hasProfileAlternatives
    (vec (for [[cid alts] cells]
           {:jsonld/id cid :jsonld/type "urpx:ProfileAlternatives"
            :urpx/hasProfileAlternative
            (mapv (fn [a] {:jsonld/id a :jsonld/type "urpx:ProfileAlternative"}) alts)}))}})

(deftest identity-tuple-names-alternatives-sorted-by-their-own-iris
  (testing "the tuple is the three values a urpx:ScenarioCostModel carries:
            consumesVersion, consumesModifierVersion and
            selectsProfileAlternative. The last names alternatives without
            their containers, so the tuple sorts the alternatives themselves.
            Here container order and alternative order disagree."
    (let [plan (plan-doc "x:p" "x:p-v1" (ldt 2026 1 1)
                         (containers {"x:c1" ["x:z1" "x:z2"]}))
          m    (modifier-doc "x:m" "x:m-v" (ldt 2026 1 1)
                             (merge {:urpx/applicableToRatePlan [{:jsonld/id "x:p"}]}
                                    (containers {"x:c2" ["x:a1"]})))
          as   (scm/enumerate [plan m])]
      (is (= [["x:p-v1" []        ["x:z1"]]
              ["x:p-v1" []        ["x:z2"]]
              ["x:p-v1" ["x:m-v"] ["x:a1" "x:z1"]]
              ["x:p-v1" ["x:m-v"] ["x:a1" "x:z2"]]]
             (mapv scm/identity-tuple as))
          "base version, sorted modifier versions, sorted alternatives, and
           enumerate returns the models in tuple order")
      (is (= {"x:c1" "x:z1" "x:c2" "x:a1"}
             (:urpx.scenario-cost-model/selections (nth as 2)))
          "the selections keep each alternative's container"))))

(deftest an-alternative-listed-by-two-containers-is-refused
  (testing "a selection names the alternative alone, so an alternative two
            containers list has no single container to price under.
            urpx-rate-plans refuses the same case."
    (let [plan (plan-doc "x:p" "x:p-v1" (ldt 2026 1 1)
                         (containers {"x:c1" ["x:a" "x:shared"]}))
          m    (modifier-doc "x:m" "x:m-v" (ldt 2026 1 1)
                             (merge {:urpx/applicableToRatePlan [{:jsonld/id "x:p"}]}
                                    (containers {"x:c2" ["x:shared"]})))
          e    (is (thrown? clojure.lang.ExceptionInfo (scm/enumerate [plan m])))]
      (is (= {:reason       :urpx.scenario-cost-model/shared-alternative
              :alternatives {"x:shared" ["x:c1" "x:c2"]}}
             (ex-data e))
          "naming the alternative and both containers")
      (is (= 2 (count (scm/enumerate [plan m] {:combine (constantly [[]])})))
          "the base version alone lists it once, so a policy that never
           composes the modifier enumerates")
      (let [m2 (modifier-doc "x:m2" "x:m2-v" (ldt 2026 1 1)
                             (merge {:urpx/applicableToRatePlan [{:jsonld/id "x:p"}]}
                                    (containers {"x:c3" ["x:shared"]})))]
        (is (= {"x:shared" ["x:c1" "x:c2" "x:c3"]}
               (:alternatives (ex-data (is (thrown? clojure.lang.ExceptionInfo
                                                    (scm/enumerate [plan m m2]))))))
            "each composition shares it with a different container, and
             every container that lists it is named")))))


;; ---------------------------------------------------------------------------
;; Horizons
;; ---------------------------------------------------------------------------

(deftest segments-for-derives-boundaries-from-the-documents
  (testing "the price-server shape: the interval containing an instant and the
            ones after it, with boundaries where the tariff actually changes
            rather than where a step grid lands. PG&E E-TOU-C has TOU periods,
            so a day must come back as several segments, contiguous and
            covering the window exactly."
    (let [a    (first (scm/enumerate [(fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")]))
          segs (scm/segments-for a (at 2026 7 15 0) (at 2026 7 16 0))]
      (is (< 1 (count segs)) "a TOU day is not one segment")
      (is (= (:tick/beginning (first segs)) (at 2026 7 15 0))
          "starts where asked")
      (is (= (:tick/end (last segs)) (at 2026 7 16 0))
          "ends where asked")
      (is (every? true? (map (fn [x y] (= (:tick/end x) (:tick/beginning y)))
                             segs (rest segs)))
          "contiguous, no gaps and no overlaps: a publisher can emit these
           straight through as intervals"))))

(defn- scope-pd-to
  "Point PG&E E-TOU-C's generation PriceDefinition at `alt-ids`, changing
   nothing else. The same technique urpx.pd-alternatives-test uses, and for the
   same reason: the fixture scopes no PriceDefinition to an alternative, so
   its three income-tier cells exist while nothing is scoped to
   them and the choice between them is inert. Without this, every assertion
   about a selection mattering passes whether or not the selection arrives."
  [doc alt-ids]
  (clojure.walk/postwalk
   (fn [n] (if (and (map? n) (= "pge:pd-gen-summer-peak" (:jsonld/id n))
                    (= "urpx:PriceDefinition" (:jsonld/type n)))
             (assoc n :urpx/referencesProfileAlternative
                    (mapv (fn [a] {:jsonld/id a}) alt-ids))
             n))
   doc))

(deftest a-horizon-carries-the-models-selection
  (testing "the choice a model makes has to reach the horizon path, not
            only the instant path.

            It cannot be asserted on the bare fixture. Measured: the three
            income tiers resolve to the same marginal rate at this instant,
            with and without the baseline-tier modifier composed, and dropping
            :profile-alternatives from the binding changes nothing, because the
            fixture scopes no PriceDefinition to an alternative. The cells exist
            and nothing hangs on them.

            So scope one, exactly as urpx.pd-alternatives-test does. Then the
            selection is load-bearing: the model that chose that cell
            prices, and with no selection at all the prices are withheld and
            the rate refuses :unselected-alternatives."
    (let [plan (fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")
          pas  (first (vals (index/entities-of-type plan "urpx:ProfileAlternatives")))
          alt  (:jsonld/id (first (:urpx/hasProfileAlternative pas)))
          doc  (scope-pd-to plan [alt])
          a    (first (filter #(= {(:jsonld/id pas) alt} (:urpx.scenario-cost-model/selections %))
                              (scm/enumerate [doc])))
          segs (scm/segments-for a (at 2026 7 15 0) (at 2026 7 16 0))]
      (is (some? a) "there is a model that chose the scoped cell")
      (is (not-any? :urpx.interval/refused segs) "no segment refuses")
      (is (= [] (->> segs
                     (keep #(try (price/marginal-unit-rate (:urpx.interval/resolved %)) nil
                                 (catch clojure.lang.ExceptionInfo e (:reason (ex-data e)))))
                     distinct vec))
          "every segment yields a rate; without the selection reaching
           urpx.price each refuses :unselected-alternatives"))))

(deftest a-model-selection-naming-no-container-is-refused
  (testing "a model hands its selections to urpx.price, so a key naming no
            container in its documents refuses there, at an instant and across
            a horizon alike."
    (let [a  (update (first (scm/enumerate [(fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")]))
                     :urpx.scenario-cost-model/selections assoc "x:no-such-container" "x:whatever")
          rs (fn [f] (keep #(get-in % [:urpx.interval/refused :reason])
                           (f a (at 2026 7 15 0) (at 2026 7 16 0))))]
      (is (= :unknown-profile-alternatives-container
             (try (scm/resolve-at a (at)) nil
                  (catch clojure.lang.ExceptionInfo e (:reason (ex-data e))))))
      (doseq [[nm f] [["segments-for" scm/segments-for] ["schedule-for" scm/schedule-for]]]
        (is (= [:unknown-profile-alternatives-container] (rs f))
            (str nm " reports the whole horizon as one refused interval"))))))

(deftest a-horizon-binds-each-document-to-its-own-version
  (testing "the reason these functions exist rather than letting a caller reach
            for urpx.schedule directly. Going through urpx.schedule means
            taking the model apart and rebuilding the per-document
            :version map by hand, which is the reconstruction resolve-at exists
            to prevent.

            Same two-version document as the resolve-at test: without the
            binding urpx.price cannot choose between the nested snapshots and
            every segment comes back refused."
    (let [a    (first (scm/enumerate [(two-version-plan)] {:at (at 2026 3 1 12)}))
          segs (scm/segments-for a (at 2026 2 1 0) (at 2026 2 2 0))]
      (is (seq segs))
      (is (not-any? :urpx.interval/refused segs)
          "unbound, urpx.price refuses a document carrying two snapshots and
           every segment carries :urpx.interval/refused instead of a price"))))

(deftest a-horizon-refuses-the-opts-the-model-determines
  (testing "same discipline as resolve-at, and asserted separately because a
            second entry point is a second chance to forget it. One builder
            serves all three, so this also pins that they share it."
    (let [a (first (scm/enumerate [(fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")]))]
      (doseq [[nm f] [["segments-for" #(scm/segments-for a (at 2026 7 15 0) (at 2026 7 16 0) %)]
                      ["schedule-for" #(scm/schedule-for a (at 2026 7 15 0) (at 2026 7 16 0) %)]]
              k       [:version :profile-alternatives]]
        (let [e (is (thrown? clojure.lang.ExceptionInfo (f {k {}})) (str nm " " k))]
          (is (= :urpx.scenario-cost-model/opt-determined-by-model (:reason (ex-data e)))
              (str nm " " k)))))))

(deftest schedule-for-walks-a-grid-and-agrees-with-segments-on-price
  (testing "the stepped variant, for callers that want a fixed cadence. It must
            agree with segments-for about the price at a given instant; the two
            differ in where boundaries fall, not in what anything costs."
    (let [a     (first (scm/enumerate [(fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")]))
          sched (scm/schedule-for a (at 2026 7 15 0) (at 2026 7 16 0))
          rate  #(some-> (:urpx.interval/resolved %) price/marginal-unit-rate)]
      (is (seq sched))
      (is (= (set (keep rate sched))
             (set (keep rate (scm/segments-for a (at 2026 7 15 0) (at 2026 7 16 0)))))
          "same set of prices over the same day, reached two different ways"))))


(deftest segments-for-derives-boundaries-rather-than-sampling-for-them
  (testing "the whole reason to prefer segments-for, and it needs care to
            assert. Starting the window at a non-hour offset does NOT separate
            them: measured, both return boundaries at 00:37, 16:00 and 21:00,
            because the stepped walk merges adjacent equal steps and lands on
            the transition anyway when every bracket in this plan sits on an
            hour. A test built on that offset would have passed for both.

            :step is what separates them. The stepped walk can only find a
            boundary it samples, so a six-hour step steps straight over the
            16:00 and 21:00 transitions. Exact segmentation ignores :step
            entirely because it reads the boundaries out of the documents.

            For a consumer handing intervals to an optimizer that is the
            difference between a schedule that is right and one that is right
            only because its step happened to be fine enough."
    (let [a     (first (scm/enumerate [(fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")]))
          start (at 2026 7 15 0)
          end   (at 2026 7 16 0)
          coarse {:step (java.time.Duration/ofHours 6)}
          hhmm  (fn [xs] (mapv #(+ (* 100 (.getHour ^ZonedDateTime (:tick/beginning %)))
                                   (.getMinute ^ZonedDateTime (:tick/beginning %))) xs))]
      (is (= [0 1600 2100] (hhmm (scm/segments-for a start end coarse)))
          "the real transitions, found at a six-hour step because they are read
           rather than sampled")
      (is (= [0 1800] (hhmm (scm/schedule-for a start end coarse)))
          "while the stepped walk sees only what it lands on, putting a
           boundary at 18:00 that is not a transition and missing both that
           are"))))

(deftest a-horizon-includes-the-models-modifiers
  (testing "dropping the modifiers leaves a horizon that prices the base plan
            and publishes it as the customer's, which is the 0.28.0 defect
            reappearing one function along. SCE Schedule D composed with CARE
            is a 36% discount, so the two are not confusable."
    (let [by-mods (into {} (map (juxt :urpx.scenario-cost-model/modifier-version-ids identity))
                        (scm/enumerate (sce-docs)))
          rates   (fn [a] (set (map #(price/marginal-unit-rate (:urpx.interval/resolved %))
                                    (scm/segments-for a (at 2026 7 15 0) (at 2026 7 16 0)))))]
      (is (= #{0.31085M} (rates (by-mods []))) "base alone over the whole day")
      (is (= #{0.19804875M} (rates (by-mods ["sce:care-2026-03-20"])))
          "and CARE priced across the horizon, not just at an instant"))))


(deftest nothing-in-the-library-depends-on-this-namespace
  (testing "optional by construction, and asserted over the whole source tree
            rather than over urpx.price alone: a consumer that assembles its
            own composition never loads this file, which holds only while NO
            other namespace requires it.

            Read from the ns forms on disk. A runtime check cannot serve here:
            ns-refers sees :refer only and ns-aliases sees :as only, so a bare
            (:require [urpx.scenario-cost-model]) passes both, and by the time this
            test runs the test suite has loaded the namespace anyway."
    (let [srcs (->> (file-seq (io/file "src"))
                    (filter #(.isFile ^java.io.File %))
                    (filter #(str/ends-with? (.getName ^java.io.File %) ".clj"))
                    (remove #(= "scenario_cost_model.clj" (.getName ^java.io.File %))))
          mentions (filter (fn [f]
                             (let [form (with-open [r (java.io.PushbackReader. (io/reader f))]
                                          (read r))]
                               (some #(= 'urpx.scenario-cost-model %) (tree-seq coll? seq form))))
                           srcs)]
      (is (seq srcs) "the source tree was found, so an empty result means clean")
      (is (= [] (mapv #(.getPath ^java.io.File %) mentions))
          "no namespace in src/ may require urpx.scenario-cost-model"))))

;; ---------------------------------------------------------------------------
;; Repeated @ids, and the pair view
;; ---------------------------------------------------------------------------

(defn- twice
  "The same version @id in two documents, differing only in `extra`."
  [extra]
  [(plan-doc "x:p" "x:p-v" (ldt 2026 1 1))
   (plan-doc "x:p" "x:p-v" (ldt 2026 1 1) extra)])

(deftest repeated-ids-union-merges-what-can-be-merged
  (testing "the RDF reading: several documents describing one IRI contribute
            the union of their statements. Collection-valued properties union;
            byte-identical repeats were never a conflict either way.

            Offered as a policy rather than wired in, for the same reason as
            :combine and :compatibility: URPX does not say, and a client reading
            one filing and a client merging a corpus want different answers."
    (let [;; BOTH sides state the property, differently, so the union branch is
          ;; actually exercised. An earlier version of this test had one side
          ;; absent, which takes the single-value path and passes whether the
          ;; code unions or picks the first.
          docs [(plan-doc "x:p" "x:p-v" (ldt 2026 1 1)
                          {:urpx/hasModifierOptions [{:jsonld/id "x:m1"}]})
                (plan-doc "x:p" "x:p-v" (ldt 2026 1 1)
                          {:urpx/hasModifierOptions [{:jsonld/id "x:m2"}]})]]
      (is (= :urpx.scenario-cost-model/conflicting-version-id
             (try (scm/enumerate docs) nil
                  (catch clojure.lang.ExceptionInfo e (:reason (ex-data e)))))
          "the default still refuses")
      (let [as (scm/enumerate docs {:repeated-ids scm/repeated-ids-union})]
        (is (= 1 (count as)) "merged into one version, not two")
        (is (= ["x:m1" "x:m2"]
               (mapv :jsonld/id
                     (:urpx/hasModifierOptions (:urpx.scenario-cost-model/version (first as)))))
            "BOTH option lists survive: the union, not whichever came first")))
    (testing "and a property only one occurrence states merges to that value,
              because absence is not an assertion under RDF"
      (let [as (scm/enumerate (twice {:urpx/hasModifierOptions [{:jsonld/id "x:m2"}]})
                                 {:repeated-ids scm/repeated-ids-union})]
        (is (= ["x:m2"] (mapv :jsonld/id
                              (:urpx/hasModifierOptions (:urpx.scenario-cost-model/version (first as))))))))))

(deftest a-merged-version-binds-to-its-own-identity-document
  (testing "a merged node is in NO single document, so one has to be bound, and
            picking a source arbitrarily is wrong in KIND rather than merely
            arbitrary. A bundled copy of a modifier version lives inside some
            PLAN's filing, so an arbitrary pick can bind a urpx:RatePlan as the
            modifier document.

            The right one is derivable from the graph: the document that IS the
            identity the version points back at via urpx:forRatePlanModifier.
            That is data, unlike a file-naming convention.

            Constructed as a modifier version that appears in its own modifier
            filing and again bundled inside a plan filing.

            The plan is named so that it sorts BEFORE the modifier under any
            content ordering, so a fallback that ignores the identity link picks
            the wrong document and this fails."
    (let [mv        (fn [extra] (merge {:jsonld/id "x:m-v" :jsonld/type "urpx:RatePlanModifierVersion"
                                        :urpx/forRatePlanModifier {:jsonld/id "x:m"}
                                        :urpx/effectiveDateTime (ldt 2026 1 1)
                                        :urpx/applicableToRatePlan [{:jsonld/id "x:aaa-plan"}]}
                                       extra))
          own-doc   {:jsonld/id "x:m" :jsonld/type "urpx:RatePlanModifier" :urpx/timezoneIdentifier "America/Los_Angeles"
                     :urpx/hasRatePlanModifierVersion (mv nil)}
          bundled   {:jsonld/id "x:aaa-plan" :jsonld/type "urpx:RatePlan" :urpx/timezoneIdentifier "America/Los_Angeles"
                     :urpx/hasRatePlanVersion {:jsonld/id "x:aaa-plan-v" :jsonld/type "urpx:RatePlanVersion"
                                               :urpx/forRatePlan {:jsonld/id "x:aaa-plan"}
                                               :urpx/effectiveDateTime (ldt 2026 1 1)}
                     ;; the same modifier version, bundled, with an extra target
                     :urpx/hasRatePlanModifier
                     {:jsonld/id "x:m" :jsonld/type "urpx:RatePlanModifier" :urpx/timezoneIdentifier "America/Los_Angeles"
                      :urpx/hasRatePlanModifierVersion
                      (mv {:urpx/applicableToRatePlan [{:jsonld/id "x:aaa-plan"} {:jsonld/id "x:q"}]})}}
          bound     #(->> (scm/enumerate % {:repeated-ids scm/repeated-ids-union})
                          (filter (comp seq :urpx.scenario-cost-model/modifier-version-ids))
                          first :urpx.scenario-cost-model/modifiers first)]
      (doseq [order [[own-doc bundled] [bundled own-doc]]]
        (let [d (bound order)]
          (is (= "urpx:RatePlanModifier" (:jsonld/type d))
              "the bound document is a modifier, not the plan that bundled it")
          (is (= "x:m" (:jsonld/id d))
              "and it is the modifier's OWN filing, whichever order the documents came in"))))))

(deftest naming-a-version-of-one-plan-retracts-nothing-by-default
  (testing "a modifier naming plans P and Q, with a version of P only:

              add     (default) compatible with both, naming a version
                      retracts nothing
              narrow  compatible with neither unless the version matches, so
                      Q loses a modifier its own plan-level statement named

            The middle reading, where naming a version of P restricts P and
            leaves Q's plan-level statement standing, is not implemented."
    (let [plan (fn [pid vid] {:jsonld/id pid :jsonld/type "urpx:RatePlan"
                              :urpx/timezoneIdentifier "America/Los_Angeles"
                              :urpx/hasRatePlanVersion
                              {:jsonld/id vid :jsonld/type "urpx:RatePlanVersion"
                               :urpx/forRatePlan {:jsonld/id pid}
                               :urpx/effectiveDateTime (ldt 2026 1 1)}})
          m    (modifier-doc "x:m" "x:m-v" (ldt 2026 1 1)
                             {:urpx/applicableToRatePlan [{:jsonld/id "x:P"} {:jsonld/id "x:Q"}]
                              :urpx/applicableToRatePlanVersion [{:jsonld/id "x:P-v"}]})
          ask  (fn [p opts] (mapv :jsonld/id
                                  (scm/applicable-modifiers [p m] (:urpx/hasRatePlanVersion p)
                                                            opts)))
          narrow {:compatibility scm/named-versions-narrow}
          P    (plan "x:P" "x:P-v")
          Q    (plan "x:Q" "x:Q-v")]
      (is (= ["x:m-v"] (ask Q nil))
          "the default keeps Q, which the modifier names at plan level")
      (is (= ["x:m-v"] (ask P nil))
          "and P, whose version it names")
      (is (= [] (ask Q narrow))
          "narrow drops Q, although the modifier names Q at plan level and
           names no version of Q: retraction across plans")
      (is (= ["x:m-v"] (ask P narrow))
          "while P's named version still matches"))))

;; ---------------------------------------------------------------------------
;; A version zoned by the nodes it belongs to
;; ---------------------------------------------------------------------------

(defn- un-nest
  "`plan` split into its identity document and a top-level version document
   carrying only a back-pointer to it, as v0.5.1 permits. The zone stays on
   the identity."
  [plan]
  (let [v (:urpx/hasRatePlanVersion plan)]
    [(dissoc plan :urpx/hasRatePlanVersion)
     (-> v
         (dissoc :urpx/timezoneIdentifier)
         (assoc :urpx/forRatePlan {:jsonld/id (:jsonld/id plan)}))]))

(deftest a-top-level-version-is-zoned-by-its-separate-identity-document
  (testing "urpx:timezoneIdentifier is admitted on the version, its plan and the
            package that contains it. A top-level version whose plan document
            carries the zone was refused :urpx.price/no-timezone, because only
            the version's own document root was read."
    (let [[identity version] (un-nest (fixtures/load-rate-plan "sce-d-2026-03-20"))
          docs [identity version]
          [a :as as] (scm/enumerate docs {:at (at)})]
      (is (nil? (:urpx/timezoneIdentifier version)) "the version declares no zone")
      (is (= ["sce:d-2026-03-20"] (mapv :urpx.scenario-cost-model/version-id as))
          "enumerated at an instant")
      (is (= ["sce:d-2026-03-20"] (mapv :jsonld/id (scm/plan-versions docs {:at (at)}))))
      (is (= 0.31085M (rate a))
          "and resolve-at prices it, the same rate as the nested filing")
      (is (seq (scm/segments-for a (at 2026 7 15 0) (at 2026 7 16 0)))
          "and a horizon walks it"))))

(deftest a-top-level-modifier-version-is-zoned-by-its-identity-document
  (let [mv   {:jsonld/id "x:m-v" :jsonld/type "urpx:RatePlanModifierVersion"
              :urpx/forRatePlanModifier {:jsonld/id "x:m"}
              :urpx/applicableToRatePlan [{:jsonld/id "x:p"}]
              :urpx/effectiveDateTime (ldt 2026 1 1)}
        m    {:jsonld/id "x:m" :jsonld/type "urpx:RatePlanModifier"
              :urpx/timezoneIdentifier "America/Los_Angeles"}
        p    (plan-doc "x:p" "x:p-v" (ldt 2026 1 1))
        docs [p m mv]]
    (is (= ["x:m-v"] (mapv :jsonld/id (scm/applicable-modifiers
                                       docs (:urpx/hasRatePlanVersion p) {:at (at)}))))
    (is (= [[] ["x:m-v"]] (mapv :urpx.scenario-cost-model/modifier-version-ids
                                (scm/enumerate docs {:at (at)}))))))

(deftest zones-that-agree-across-nodes-are-the-zone
  (let [[identity version] (un-nest (fixtures/load-rate-plan "sce-d-2026-03-20"))
        pkg {:jsonld/id "x:pkg" :jsonld/type "urpx:URPXPackage"
             :urpx/timezoneIdentifier "America/Los_Angeles"
             :urpx/packages [{:jsonld/id (:jsonld/id version)}]}
        docs [identity (assoc version :urpx/timezoneIdentifier "America/Los_Angeles") pkg]]
    (is (= 0.31085M (rate (first (scm/enumerate docs {:at (at)})))))))

(deftest a-package-naming-the-plan-zones-its-versions
  (let [[identity version] (un-nest (fixtures/load-rate-plan "sce-d-2026-03-20"))
        pkg (fn [tz] {:jsonld/id "x:pkg" :jsonld/type "urpx:URPXPackage"
                      :urpx/timezoneIdentifier tz
                      :urpx/packages [{:jsonld/id (:jsonld/id identity)}]})]
    (is (= 0.31085M (rate (first (scm/enumerate
                                  [(dissoc identity :urpx/timezoneIdentifier) version
                                   (pkg "America/Los_Angeles")]
                                  {:at (at)}))))
        "the only zone is on the package carrying the plan")
    (let [e (is (thrown? clojure.lang.ExceptionInfo
                         (scm/enumerate [identity version (pkg "America/New_York")]
                                           {:at (at)})))]
      (is (= :urpx.scenario-cost-model/conflicting-timezone (:reason (ex-data e)))))))

(deftest zones-that-disagree-across-nodes-are-refused
  (testing "the version, its plan and its package each admit a zone, and the
            spec gives no precedence among them. Two different values state two
            frames for one set of bounds."
    (let [[identity version] (un-nest (fixtures/load-rate-plan "sce-d-2026-03-20"))
          ny  "America/New_York"
          pkg {:jsonld/id "x:pkg" :jsonld/type "urpx:URPXPackage"
               :urpx/timezoneIdentifier ny
               :urpx/packages [{:jsonld/id (:jsonld/id version)}]}]
      (doseq [[nm docs] [["version against plan"
                          [identity (assoc version :urpx/timezoneIdentifier ny)]]
                         ["package against plan" [identity version pkg]]
                         ["embedded back-pointer node against plan"
                          [identity (assoc version :urpx/forRatePlan
                                           {:jsonld/id (:jsonld/id identity)
                                            :jsonld/type "urpx:RatePlan"
                                            :urpx/timezoneIdentifier ny})]]]]
        (let [e (is (thrown? clojure.lang.ExceptionInfo (scm/enumerate docs {:at (at)})) nm)]
          (is (= :urpx.scenario-cost-model/conflicting-timezone (:reason (ex-data e))) nm)
          (is (= "sce:d-2026-03-20" (:version-id (ex-data e))) nm)
          (is (= #{"America/Los_Angeles" ny} (set (map second (:declared (ex-data e))))) nm)))
      (let [e (is (thrown? clojure.lang.ExceptionInfo
                           (scm/enumerate [identity (assoc version :urpx/timezoneIdentifier ny)])))]
        (is (= :urpx.scenario-cost-model/conflicting-timezone (:reason (ex-data e)))
            "without :at too, since the model must carry one zone to resolve in")))))

(deftest zones-with-the-same-rules-agree-across-nodes
  (let [[identity version] (un-nest (fixtures/load-rate-plan "sce-d-2026-03-20"))
        pkg (fn [tz] {:jsonld/id "x:pkg" :jsonld/type "urpx:URPXPackage"
                      :urpx/timezoneIdentifier tz
                      :urpx/packages [{:jsonld/id (:jsonld/id version)}]})]
    (doseq [[nm docs] [["version against plan"
                        [identity (assoc version :urpx/timezoneIdentifier "US/Pacific")]]
                       ["package against plan" [identity version (pkg "US/Pacific")]]]]
      (is (= 0.31085M (rate (first (scm/enumerate docs {:at (at)})))) nm))
    (let [utc #(assoc % :urpx/timezoneIdentifier "UTC")
          [a] (scm/enumerate [(utc identity) version (pkg "Etc/UTC")] {:at (at)})]
      (is (= "UTC" (:urpx/timezoneIdentifier (:urpx.scenario-cost-model/rate-plan a)))
          "UTC and Etc/UTC"))))

;; ---------------------------------------------------------------------------
;; A version zoned by the package carrying it, through the loader
;; ---------------------------------------------------------------------------

(def ^:private la-tz "America/Los_Angeles")
(def ^:private ny "America/New_York")

(defn- raw-plan
  "SCE Schedule D as parsed and not yet coerced, declaring no zone, or `tz`."
  ([] (raw-plan nil))
  ([tz]
   (cond-> (dissoc (core/load-rate-plan (.getPath (fixtures/fixture-resource "sce-d-2026-03-20")))
                   :urpx/timezoneIdentifier)
     tz (assoc :urpx/timezoneIdentifier tz))))

(defn- package
  "A urpx:URPXPackage `id` declaring `tz`, or no zone when nil, with `props`."
  [id tz props]
  (merge (cond-> {:jsonld/id id :jsonld/type "urpx:URPXPackage"
                  :urpx/targetURPXVersion
                  "https://github.com/urpx-org/urpx/raw/v0.5.1/ontology/urpx-ontology.ttl"}
           tz (assoc :urpx/timezoneIdentifier tz))
         props))

(defn- source
  "A source carrying `entity`, or an @graph of `entities`."
  [& entities]
  (StringReader. (json/write-str (core/keys->jsonld (if (next entities)
                                                      {:jsonld/graph (vec entities)}
                                                      (first entities))))))

(defn- refusal [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest a-zone-declared-only-on-the-package-zones-a-loaded-plan
  (let [docs (load/documents [(source (package "x:pkg" la-tz {:urpx/hasRatePlan (raw-plan)}))])
        [a :as as] (scm/enumerate docs {:at (at)})]
    (is (= ["sce:d-2026-03-20"] (mapv :urpx.scenario-cost-model/version-id as)))
    (is (= 0.31085M (rate a)) "resolve-at prices it in the package's zone")
    (is (= ["sce:d-2026-03-20"] (mapv :jsonld/id (scm/plan-versions docs {:at (at)}))))
    (is (= 0.31085M (rate (first (scm/enumerate docs {:at (at) :package-zones :fallback}))))
        ":fallback reads the package when nothing else declares a zone")
    (is (= :urpx.price/no-timezone
           (:reason (refusal #(scm/enumerate docs {:at (at) :package-zones :ignore}))))
        "with packages ignored, nothing declares a zone")))

(deftest a-shell-whose-only-date-shaped-value-is-a-label-is-accepted
  (let [docs (load/documents [(source (package "x:pkg" la-tz {:urpx/version "2026-03-20"
                                                              :urpx/hasRatePlan (raw-plan)}))])]
    (is (= 0.31085M (rate (first (scm/enumerate docs {:at (at)})))))))

(deftest a-package-naming-a-plan-by-reference-zones-it
  (let [pkg (package "x:pkg" la-tz {:urpx/packages {:jsonld/id "sce:d"}})]
    (doseq [[nm srcs] [["one source" #(vector (source (raw-plan) pkg))]
                       ["two sources" #(vector (source pkg) (source (raw-plan)))]]]
      (is (= 0.31085M (rate (first (scm/enumerate (load/documents (srcs)) {:at (at)}))))
          nm))))

(deftest a-top-level-version-whose-plan-the-package-carries
  (let [plan     (raw-plan)
        identity (dissoc plan :urpx/hasRatePlanVersion)
        version  (assoc (:urpx/hasRatePlanVersion plan) :jsonld/type "urpx:RatePlanVersion")
        docs     (load/documents [(source (package "x:pkg" la-tz {:urpx/hasRatePlan identity})
                                          version)])]
    (is (not-any? :urpx/timezoneIdentifier (remove #(= "x:pkg" (:jsonld/id %)) docs))
        "only the package declares a zone")
    (is (= 0.31085M (rate (first (scm/enumerate docs {:at (at)})))))))

(deftest an-un-nested-file-resolves-through-the-loader-and-a-model
  (let [doc  (json/read-str (slurp (fixtures/fixture-resource "sce-d-2026-03-20")))
        plan (get doc "urpx:hasRatePlan")
        f    (doto (File/createTempFile "sce-d-un-nested" ".jsonld") .deleteOnExit)
        _    (spit f (json/write-str
                      {"@context" (get doc "@context")
                       "@graph"   [(-> (dissoc doc "@context")
                                       (assoc "urpx:hasRatePlan"
                                              (dissoc plan "urpx:hasRatePlanVersion")))
                                   (get plan "urpx:hasRatePlanVersion")]}))
        docs (load/documents [f])
        identity (first (filter #(= "urpx:RatePlan" (:jsonld/type %)) docs))]
    (is (nil? (:urpx/hasRatePlanVersion identity)) "the version is not nested")
    (is (= :urpx.price/no-version
           (:reason (refusal #(price/resolve-prices identity (at)))))
        "the identity alone has no version to resolve")
    (is (= 0.31085M (rate (first (scm/enumerate docs {:at (at)}))))
        "a model resolves it, at the nested filing's rate")
    (is (= 0.31085M (price/marginal-unit-rate
                     (price/resolve-prices identity (at)
                                           {:versions-by-plan (index/versions-by-plan docs)})))
        "and so does :versions-by-plan")))

(deftest two-packages-zoning-one-plan-differently-are-refused
  (let [a (source (package "x:pkg-a" la-tz {:urpx/hasRatePlan (raw-plan)}))
        b (source (package "x:pkg-b" ny {:urpx/hasRatePlan (raw-plan)}))
        a2 (source (package "x:pkg-a" la-tz {:urpx/hasRatePlan (raw-plan)}))
        b2 (source (package "x:pkg-b" ny {:urpx/hasRatePlan (raw-plan)}))]
    (doseq [[nm docs] [["a then b" (load/documents [a b])]
                       ["b then a" (load/documents [b2 a2])]]
            mode [:agree :fallback :ignore]]
      (is (= :urpx.scenario-cost-model/conflicting-timezone
             (:reason (refusal #(scm/enumerate docs {:at (at) :package-zones mode}))))
          (str nm " " mode)))))

(deftest a-package-zone-against-the-plans-own
  (let [docs #(load/documents [(source (package "x:pkg" ny {:urpx/hasRatePlan (raw-plan la-tz)}))])]
    (is (= :urpx.scenario-cost-model/conflicting-timezone
           (:reason (refusal #(scm/enumerate (docs) {:at (at)}))))
        ":agree, the default, refuses")
    (doseq [mode [:fallback :ignore]]
      (let [[a] (scm/enumerate (docs) {:at (at) :package-zones mode})]
        (is (= la-tz (:urpx/timezoneIdentifier (:urpx.scenario-cost-model/rate-plan a))) (str mode))
        (is (= 0.31085M (rate a)) (str mode " uses the plan's zone"))))))

(deftest a-bare-and-a-wrapped-filing-of-one-plan
  (let [bare    #(source (raw-plan))
        wrapped #(source (package "x:pkg" la-tz {:urpx/hasRatePlan (raw-plan)}))]
    (doseq [[nm srcs] [["bare first" [(bare) (wrapped)]] ["wrapped first" [(wrapped) (bare)]]]]
      (let [as (scm/enumerate (load/documents srcs) {:at (at)})]
        (is (= ["sce:d-2026-03-20"] (mapv :urpx.scenario-cost-model/version-id as)) nm)
        (is (= 0.31085M (rate (first as))) nm)))))

(deftest a-horizon-is-bounded-in-the-packages-zone
  (let [docs (load/documents [(source (package "x:pkg" ny {:urpx/hasRatePlan (raw-plan)}))])
        a    (first (scm/enumerate docs))
        in   #(ZonedDateTime/of 2026 7 % 0 0 0 0 (ZoneId/of %2))
        segs (scm/segments-for a (in 15 ny) (in 16 ny))]
    (is (seq segs))
    (is (= segs (scm/segments-for a (.withZoneSameInstant ^ZonedDateTime (in 15 ny) la)
                                  (.withZoneSameInstant ^ZonedDateTime (in 16 ny) la)))
        "the same instants stated in another zone")
    (is (every? #(and (instance? ZonedDateTime (:tick/beginning %))
                      (= (ZoneId/of ny) (.getZone ^ZonedDateTime (:tick/beginning %)))
                      (= (ZoneId/of ny) (.getZone ^ZonedDateTime (:tick/end %))))
                segs))))

(deftest without-at-no-zone-is-read
  (let [docs (load/documents [(source (package "x:pkg-a" la-tz {:urpx/hasRatePlan (raw-plan)}))
                              (source (package "x:pkg-b" ny {:urpx/hasRatePlan (raw-plan)}))])
        bv   (first (scm/plan-versions docs))]
    (is (= "sce:d-2026-03-20" (:jsonld/id bv)) "plan-versions reads no zone")
    (is (= [] (scm/applicable-modifiers docs bv)) "nor does applicable-modifiers")
    (is (= :urpx.scenario-cost-model/conflicting-timezone
           (:reason (refusal #(scm/plan-versions docs {:at (at)}))))
        "the same documents refuse once an instant needs a zone")))

(deftest an-unknown-package-zones-value-is-refused
  (let [docs [(fixtures/load-rate-plan "sce-d-2026-03-20")]
        bv   (:urpx/hasRatePlanVersion (first docs))]
    (doseq [[nm f] [["enumerate" #(scm/enumerate docs {:package-zones :prefer})]
                    ["plan-versions" #(scm/plan-versions docs {:package-zones :prefer})]
                    ["applicable-modifiers" #(scm/applicable-modifiers docs bv {:package-zones :prefer})]]]
      (is (= {:reason :urpx.scenario-cost-model/unknown-package-zones :value :prefer}
             (select-keys (refusal f) [:reason :value]))
          nm))))

(deftest a-tie-is-one-instant-whatever-zone-reads-it
  (testing "two snapshots of one identity, each declaring its own zone, both
            taking effect at 05:00Z. Read in their own zones they are the same
            instant, so they tie."
    (let [z    (java.time.ZonedDateTime/of 2026 1 1 5 0 0 0 java.time.ZoneOffset/UTC)
          bare #(dissoc (plan-doc "x:p" %1 z {:urpx/timezoneIdentifier %2})
                        :urpx/timezoneIdentifier)
          docs [(bare "x:p-v1" "America/New_York")
                (bare "x:p-v2" "America/Los_Angeles")]
          e    (is (thrown? clojure.lang.ExceptionInfo
                            (scm/enumerate docs {:at (at 2026 6 1 12)})))]
      (is (= :urpx.scenario-cost-model/ambiguous-version (:reason (ex-data e)))))))

;; ---------------------------------------------------------------------------
;; urpx:ScenarioCostModel nodes
;; ---------------------------------------------------------------------------

(def ^:private pge "https://rate-plans.grid-coordination.energy/pge/")
(defn- pge-iri [local] (str pge local))

(deftest rate-plans-iri-matches-the-urpx-rate-plans-vectors
  (testing "the Vectors table of urpx-rate-plans doc/scenario-cost-models.md"
    (are [tuple iri] (= (pge-iri iri) (scm/rate-plans-iri tuple))
      [(pge-iri "e-1-2026-03-01") [] []]
      "scm-e-1-2026-03-01"

      [(pge-iri "e-1-2026-03-01") [(pge-iri "e-1-vintage-pcia-2026-03-01")] []]
      "scm-e-1-2026-03-01--e-1-vintage-pcia-2026-03-01"

      [(pge-iri "e-elec-2026-03-27") [] [(pge-iri "e-elec-2026-03-27-pa-income-tier-care")]]
      "scm-e-elec-2026-03-27--291186d914f7"

      [(pge-iri "e-1-2026-03-01")
       [(pge-iri "e-1-vintage-pcia-2026-03-01")]
       [(pge-iri "e-1-2026-03-01-pa-tc-w-b") (pge-iri "e-1-vintage-pcia-2026-03-01-pa-vintage-2020")]]
      "scm-e-1-2026-03-01--e-1-vintage-pcia-2026-03-01--deed42bdeaaa")))

(deftest rate-plans-iri-sorts-its-own-input
  (let [mods [(pge-iri "z-mod") (pge-iri "a-mod")]
        alts [(pge-iri "z-alt") (pge-iri "a-alt")]
        iri  (scm/rate-plans-iri [(pge-iri "base") mods alts])]
    (is (= iri (scm/rate-plans-iri [(pge-iri "base") (reverse mods) (reverse alts)])))
    (is (str/starts-with? iri (pge-iri "scm-base--a-mod--z-mod--")))))

(deftest rate-plans-iri-refuses-what-it-cannot-mint
  (let [reason #(:reason (refusal (fn [] (scm/rate-plans-iri %))))
        base   (pge-iri "e-1-2026-03-01")]
    (testing "a base outside the root, and a component outside the base's utility"
      (are [tuple] (= :urpx.scenario-cost-model/iri-outside-namespace (reason tuple))
        ["https://example.com/pge/e-1" [] []]
        ["pge:e-1-2026-03-01" [] []]
        [base ["https://rate-plans.grid-coordination.energy/sce/care-2026-03-20"] []]
        [base [] ["https://rate-plans.grid-coordination.energy/sce/pa-x"]]))
    (testing "a local name other than lower-case words joined by single hyphens"
      (are [tuple] (= :urpx.scenario-cost-model/iri-local-name (reason tuple))
        [(pge-iri "E-1") [] []]
        [(pge-iri "") [] []]
        [base [(pge-iri "care--2026")] []]
        [base [] [(pge-iri "pa-x/2020")]]
        [base [] [(pge-iri "pa-x\n")]]))))

(defn- fixture-files []
  (sort (filter #(str/ends-with? (str %) ".jsonld")
                (file-seq (io/file (io/resource "urpx-fixtures"))))))

(defn- fixture-models []
  (scm/enumerate (load/documents (fixture-files))
                 {:at (at) :compatibility scm/named-versions-add}))

(defn- written-contexts
  "Each fixture's @context as written, string-keyed."
  []
  (mapv #(get (json/read-str (slurp %)) "@context") (fixture-files)))

(deftest a-node-states-each-property-only-when-it-has-a-value
  (let [models (fixture-models)
        ctx    (written-contexts)
        shape  (fn [[_ mods alts]] [(boolean (seq mods)) (boolean (seq alts))])]
    (is (= #{[false false] [true false] [false true] [true true]}
           (set (map (comp shape scm/identity-tuple) models)))
        "the fixtures exercise base alone, modifiers only, alternatives only and both")
    (doseq [m models
            :let [[_ mods alts] (scm/identity-tuple m)
                  n (scm/node m scm/rate-plans-iri {:context ctx})
                  written (json/read-str (emit/write-scenario-cost-model n {:context nil}))]]
      (testing (pr-str (scm/identity-tuple m))
        (is (nil? (coerce/explain schema/ScenarioCostModel n)))
        (is (map? (get written "urpx:consumesVersion")))
        (is (= (boolean (seq mods)) (contains? written "urpx:consumesModifierVersion")))
        (is (= (boolean (seq alts)) (contains? written "urpx:selectsProfileAlternative")))
        (is (every? #(str/starts-with? % "https://rate-plans.grid-coordination.energy/")
                    (cons (:jsonld/id n)
                          (keep :jsonld/id (concat [(:urpx/consumesVersion n)]
                                                   (:urpx/consumesModifierVersion n)
                                                   (:urpx/selectsProfileAlternative n))))))
        (is (= [(count mods) (count alts)]
               [(count (:urpx/consumesModifierVersion n))
                (count (:urpx/selectsProfileAlternative n))]))))))

(deftest a-node-carries-the-pge-vintage-model-at-the-iri-urpx-rate-plans-mints
  (let [m (first (filter #(= ["pge:ev2-2026-03-27" ["pge:ev2-vintage-pcia-2026-03-27"]
                              ["pge:ev2-2026-03-27-pa-income-tier-care"
                               "pge:ev2-vintage-pcia-2026-03-27-pa-vintage-2020"]]
                             (scm/identity-tuple %))
                         (fixture-models)))]
    (is (= {:jsonld/type "urpx:ScenarioCostModel"
            :jsonld/id   (pge-iri "scm-ev2-2026-03-27--ev2-vintage-pcia-2026-03-27--d086bfc4da3c")
            :urpx/consumesVersion {:jsonld/id (pge-iri "ev2-2026-03-27")}
            :urpx/consumesModifierVersion [{:jsonld/id (pge-iri "ev2-vintage-pcia-2026-03-27")}]
            :urpx/selectsProfileAlternative
            [{:jsonld/id (pge-iri "ev2-2026-03-27-pa-income-tier-care")}
             {:jsonld/id (pge-iri "ev2-vintage-pcia-2026-03-27-pa-vintage-2020")}]}
           (scm/node m scm/rate-plans-iri {:context (written-contexts)})))))

(deftest a-node-takes-its-iri-from-the-callers-fn
  (let [m    (first (filter #(seq (second (scm/identity-tuple %))) (fixture-models)))
        seen (atom nil)
        n    (scm/node m (fn [t] (reset! seen t) "urn:x:model") {:context (written-contexts)})
        [base mods alts] (scm/identity-tuple m)]
    (is (= "urn:x:model" (:jsonld/id n)))
    (is (= [(count mods) (count alts)] [(count (second @seen)) (count (nth @seen 2))]))
    (is (every? #(str/starts-with? % "https://") (cons (first @seen) (concat (second @seen) (nth @seen 2))))
        "the tuple it is handed is absolute")
    (is (str/ends-with? (first @seen) (str "/" (second (str/split base #":" 2)))))
    (testing "and has no default"
      (is (thrown? AssertionError (scm/node m nil {:context (written-contexts)}))))))

(deftest a-node-expands-through-a-parsed-context-as-through-a-written-one
  (let [models (fixture-models)
        parsed (mapv #(:jsonld/context (core/parse-rate-plan %)) (fixture-files))]
    (is (= (mapv #(scm/node % scm/rate-plans-iri {:context (written-contexts)}) models)
           (mapv #(scm/node % scm/rate-plans-iri {:context parsed}) models)))))

(deftest a-node-refuses-an-iri-it-cannot-expand
  (let [m (first (fixture-models))]
    (is (= :urpx.scenario-cost-model/unexpandable-iri
           (:reason (refusal #(scm/node m scm/rate-plans-iri)))))
    (is (= :urpx.scenario-cost-model/unexpandable-iri
           (:reason (refusal #(scm/node m scm/rate-plans-iri
                                        {:context ["https://urpx.org/ns/context/releases/v0.5.1/jsonld/urpx-context.jsonld"]}))))
        "a remote context binds no prefix here")
    (is (= :urpx.scenario-cost-model/conflicting-prefix
           (:reason (refusal #(scm/node m scm/rate-plans-iri
                                        {:context [{"cpau" "https://a.example/"}
                                                   {"cpau" "https://b.example/"}]})))))))

(deftest a-node-carries-a-name-and-description-outside-its-identity
  (let [m   (first (fixture-models))
        ctx (written-contexts)
        n   (scm/node m scm/rate-plans-iri {:context ctx :name "E-1" :description "Residential"})]
    (is (= ["E-1" "Residential"] [(:urpx/name n) (:urpx/description n)]))
    (is (= (dissoc n :urpx/name :urpx/description) (scm/node m scm/rate-plans-iri {:context ctx})))
    (is (nil? (coerce/explain schema/ScenarioCostModel n)))))

(deftest rate-plans-name-matches-the-urpx-rate-plans-report
  (testing "urpx:name in urpx-rate-plans reports/scenario-cost-models.jsonld at d71845a"
    (let [by-tuple (into {} (map (juxt scm/identity-tuple scm/rate-plans-name)) (fixture-models))]
      (are [tuple nm] (= nm (by-tuple tuple))
        ["cpau:e-14-2025-07-01" [] []]
        "Street Lights [cpau:e-14-2025-07-01]"

        ["ladwp:r-1a-2026-04-01" ["ladwp:ez-save-2008-09-01"] []]
        "Schedule R-1(A) Residential Service — Standard [ladwp:r-1a-2026-04-01] + LADWP EZ-Save (Electric Lifeline Subsidy) [ladwp:ez-save-2008-09-01]"

        ["pge:e-tou-d-2026-03-27" [] ["pge:e-tou-d-2026-03-27-pa-income-tier-care"]]
        "Residential Time-Of-Use (Peak Pricing 5-8 p.m. Non-Holiday Weekdays) [pge:e-tou-d-2026-03-27]; Income Tier 1 — CARE-eligible"

        ["pge:e-tou-d-2026-03-27" ["pge:e-tou-d-vintage-pcia-2026-03-27"]
         ["pge:e-tou-d-2026-03-27-pa-income-tier-care" "pge:e-tou-d-vintage-pcia-2026-03-27-pa-vintage-2009"]]
        "Residential Time-Of-Use (Peak Pricing 5-8 p.m. Non-Holiday Weekdays) [pge:e-tou-d-2026-03-27] + Vintaged Power Charge Indifference Adjustment (E-TOU-D) [pge:e-tou-d-vintage-pcia-2026-03-27]; Income Tier 1 — CARE-eligible; Departure vintage 2009"))))

(defn- outcome
  "What calling `f` gives: its value, or the ex-data it throws."
  [f]
  (try [:value (f)] (catch clojure.lang.ExceptionInfo e [:refused (ex-data e)])))

(deftest from-node-reads-back-every-model-node-writes
  (let [docs   (load/documents (fixture-files))
        ctx    (written-contexts)
        opts   {:context ctx :compatibility scm/named-versions-add}
        models (fixture-models)]
    (doseq [m models
            :let [n (scm/node m scm/rate-plans-iri {:context ctx})]]
      (testing (pr-str (scm/identity-tuple m))
        (is (= m (scm/from-node docs n opts)) "a coerced node")
        (is (= m (scm/from-node docs (core/parse-rate-plan
                                      (StringReader. (emit/write-scenario-cost-model n {:context nil})))
                                opts))
            "the same node written and parsed back, raw")))))

(deftest a-model-read-from-its-node-prices-as-the-enumerated-one
  (let [docs (load/documents (fixture-files))
        ctx  (written-contexts)]
    (doseq [m (fixture-models)
            :let [read (scm/from-node docs (scm/node m scm/rate-plans-iri {:context ctx})
                                      {:context ctx :compatibility scm/named-versions-add})]]
      (testing (pr-str (scm/identity-tuple m))
        (is (= (outcome #(scm/resolve-at m (at))) (outcome #(scm/resolve-at read (at)))))))))

(defn- scm-node
  "A urpx:ScenarioCostModel node consuming `base`, the modifier versions `mods`
   and the alternatives `alts`, each written as a list only when non-empty."
  [base mods alts]
  (cond-> {:jsonld/id "x:model" :jsonld/type "urpx:ScenarioCostModel"}
    base       (assoc :urpx/consumesVersion {:jsonld/id base})
    (seq mods) (assoc :urpx/consumesModifierVersion (mapv #(hash-map :jsonld/id %) mods))
    (seq alts) (assoc :urpx/selectsProfileAlternative (mapv #(hash-map :jsonld/id %) alts))))

(defn- with-names
  "`doc` with each node whose @id is a key of `names` carrying that urpx:name."
  [names doc]
  (clojure.walk/postwalk #(if-let [n (and (map? %) (names (:jsonld/id %)))] (assoc % :urpx/name n) %)
                         doc))

(defn- naming-docs
  "Plan x:p offering {x:c2 [x:a]} and modifiers x:m1 (version x:m9-v) and x:m2
   (version x:m2-v), x:m2 offering {x:c1 [x:b]}, named by `names`."
  [names]
  (mapv (partial with-names names)
        [(plan-doc "x:p" "x:p-v1" (ldt 2026 1 1) (containers {"x:c2" ["x:a"]}))
         (modifier-doc "x:m1" "x:m9-v" (ldt 2026 1 1) {:urpx/applicableToRatePlan [{:jsonld/id "x:p"}]})
         (modifier-doc "x:m2" "x:m2-v" (ldt 2026 1 1)
                       (merge {:urpx/applicableToRatePlan [{:jsonld/id "x:p"}]}
                              (containers {"x:c1" ["x:b"]})))]))

(def ^:private component-names
  {"x:p" "Plan" "x:m1" "One" "x:m2" "Two" "x:a" "Second" "x:b" "First"})

(defn- naming-model [docs]
  (scm/from-node docs (scm-node "x:p-v1" ["x:m9-v" "x:m2-v"] ["x:b" "x:a"])))

(deftest rate-plans-name-orders-versions-and-alternatives-by-id
  (is (= "Plan [x:p-v1] + Two [x:m2-v] + One [x:m9-v]; Second; First"
         (scm/rate-plans-name (naming-model (naming-docs component-names))))))

(deftest rate-plans-name-refuses-a-component-it-cannot-name
  (testing "a plan, a modifier or an alternative with no urpx:name"
    (are [id] (= {:reason :urpx.scenario-cost-model/unnamed-component :id id}
                 (refusal #(scm/rate-plans-name (naming-model (naming-docs (dissoc component-names id))))))
      "x:p" "x:m1" "x:b"))
  (testing "one @id named two ways across the model's documents"
    (let [docs (assoc-in (naming-docs component-names)
                           [1 :urpx/hasRatePlanModifierVersion :urpx/applicableToRatePlan]
                           [{:jsonld/id "x:p" :jsonld/type "urpx:RatePlan" :urpx/name "Other"}])]
      (is (= {:reason :urpx.scenario-cost-model/conflicting-name :id "x:p" :names ["Other" "Plan"]}
             (refusal #(scm/rate-plans-name (naming-model docs)))))))
  (testing "one @id named two ways within one document"
    (let [docs (assoc-in (naming-docs component-names)
                         [0 :urpx/hasRatePlanVersion :urpx/forRatePlan]
                         {:jsonld/id "x:p" :jsonld/type "urpx:RatePlan" :urpx/name "Other"})]
      (is (= {:reason :urpx.scenario-cost-model/conflicting-name :id "x:p" :names ["Other" "Plan"]}
             (refusal #(scm/rate-plans-name (naming-model docs))))))))

(defn- from-node-docs
  "A plan whose version offers c1 {z1 z2}, and a modifier compatible with it
   offering c2 {a1}."
  []
  [(plan-doc "x:p" "x:p-v1" (ldt 2026 1 1) (containers {"x:c1" ["x:z1" "x:z2"]}))
   (modifier-doc "x:m" "x:m-v" (ldt 2026 1 1)
                 (merge {:urpx/applicableToRatePlan [{:jsonld/id "x:p"}]}
                        (containers {"x:c2" ["x:a1"]})))])

(defn- node-refusal
  ([docs n] (node-refusal docs n nil))
  ([docs n opts] (refusal #(scm/from-node docs n opts))))

(deftest from-node-keys-each-selection-by-its-container
  (let [m (scm/from-node (from-node-docs) (scm-node "x:p-v1" ["x:m-v"] ["x:z2" "x:a1"]))]
    (is (= {"x:c1" "x:z2" "x:c2" "x:a1"} (:urpx.scenario-cost-model/selections m)))
    (is (= ["x:p-v1" ["x:m-v"] ["x:a1" "x:z2"]] (scm/identity-tuple m)))
    (is (= [m] (filter #(= (scm/identity-tuple m) (scm/identity-tuple %))
                       (scm/enumerate (from-node-docs)))))))

(deftest from-node-reads-a-bare-value-as-a-list-of-one
  (let [n (-> (scm-node "x:p-v1" ["x:m-v"] ["x:z1" "x:a1"])
              (assoc :urpx/consumesVersion [{:jsonld/id "x:p-v1"}]
                     :urpx/consumesModifierVersion {:jsonld/id "x:m-v"}))]
    (is (= ["x:p-v1" ["x:m-v"] ["x:a1" "x:z1"]]
           (scm/identity-tuple (scm/from-node (from-node-docs) n))))))

(deftest from-node-refuses-a-value-that-is-not-a-reference
  (are [k v] (= {:reason :urpx.scenario-cost-model/not-a-reference :property k :value "x:v"}
                (select-keys (node-refusal (from-node-docs)
                                           (assoc (scm-node "x:p-v1" ["x:m-v"] ["x:z1" "x:a1"]) k v))
                             [:reason :property :value]))
    :urpx/consumesVersion           "x:v"
    :urpx/consumesModifierVersion   ["x:v"]
    :urpx/selectsProfileAlternative [{:jsonld/id "x:z1"} "x:v"]))

(deftest from-node-refuses-a-node-with-no-version
  (is (= :urpx.scenario-cost-model/no-version
         (:reason (node-refusal (from-node-docs) (scm-node nil [] []))))))

(deftest from-node-refuses-a-version-the-documents-do-not-carry
  (are [n id] (= {:reason :urpx.scenario-cost-model/unknown-version :id id}
                 (select-keys (node-refusal (from-node-docs) n) [:reason :id]))
    (scm-node "x:p-v9" [] ["x:z1"])         "x:p-v9"
    (scm-node "x:p-v1" ["x:m-v9"] ["x:z1"]) "x:m-v9"
    (scm-node "x:m-v" [] [])                "x:m-v"
    (scm-node "x:p-v1" ["x:p-v1"] ["x:z1"]) "x:p-v1"))

(deftest from-node-refuses-a-modifier-the-compatibility-policy-rejects
  (let [docs [(plan-doc "x:p" "x:p-v1" (ldt 2026 1 1))
              (modifier-doc "x:m" "x:m-v" (ldt 2026 1 1)
                            {:urpx/applicableToRatePlan        [{:jsonld/id "x:p"}]
                             :urpx/applicableToRatePlanVersion [{:jsonld/id "x:q-v1"}]})]
        n    (scm-node "x:p-v1" ["x:m-v"] [])]
    (is (= {:reason :urpx.scenario-cost-model/inapplicable-modifier
            :modifier-version-ids ["x:m-v"]}
           (select-keys (node-refusal docs n {:compatibility scm/named-versions-narrow})
                        [:reason :modifier-version-ids])))
    (is (= ["x:p-v1" ["x:m-v"] []]
           (scm/identity-tuple (scm/from-node docs n)))
        "named-versions-add by default")))

(deftest from-node-refuses-an-alternative-no-consumed-version-offers
  (is (= {:reason :urpx.scenario-cost-model/unknown-alternative :alternative "x:a1"}
         (select-keys (node-refusal (from-node-docs) (scm-node "x:p-v1" [] ["x:z1" "x:a1"]))
                      [:reason :alternative]))
      "x:a1 is offered by the modifier the node does not consume"))

(deftest from-node-refuses-two-alternatives-from-one-container
  (is (= {:reason :urpx.scenario-cost-model/dimension-selected-twice
          :selections {"x:c1" ["x:z1" "x:z2"]}}
         (select-keys (node-refusal (from-node-docs) (scm-node "x:p-v1" [] ["x:z2" "x:z1"]))
                      [:reason :selections]))))

(deftest from-node-refuses-a-container-left-unselected
  (is (= {:reason :urpx.scenario-cost-model/dimension-unselected :containers ["x:c2"]}
         (select-keys (node-refusal (from-node-docs) (scm-node "x:p-v1" ["x:m-v"] ["x:z1"]))
                      [:reason :containers]))))

(deftest from-node-refuses-an-alternative-two-containers-list
  (let [docs [(plan-doc "x:p" "x:p-v1" (ldt 2026 1 1) (containers {"x:c1" ["x:a" "x:shared"]}))
              (modifier-doc "x:m" "x:m-v" (ldt 2026 1 1)
                            (merge {:urpx/applicableToRatePlan [{:jsonld/id "x:p"}]}
                                   (containers {"x:c2" ["x:shared"]})))]]
    (is (= {:reason       :urpx.scenario-cost-model/shared-alternative
            :node-id      "x:model"
            :alternatives {"x:shared" ["x:c1" "x:c2"]}}
           (node-refusal docs (scm-node "x:p-v1" ["x:m-v"] ["x:shared"]))))))

;; ---------------------------------------------------------------------------
;; Zoned horizon bounds
;; ---------------------------------------------------------------------------

(defn- in-zone [^ZoneId zone y m d] (ZonedDateTime/of y m d 0 0 0 0 zone))

(deftest zoned-bounds-answer-what-urpx-schedule-answers-for-their-instants
  (testing "the horizon functions bind the model and hand the bounds' instants
            and zone to urpx.schedule, so over every fixture model they agree
            with urpx.schedule called on Instants, across an ordinary day and a
            day a daylight transition lengthens"
    (doseq [m          (fixture-models)
            :let [rp   (:urpx.scenario-cost-model/rate-plan m)
                  mods (:urpx.scenario-cost-model/modifiers m)
                  zone (price/require-zone rp)
                  opts (#'scm/bound-opts m nil)]
            [y mo d]   [[2026 7 15] [2026 11 1]]
            :let [s  (in-zone zone y mo d)
                  e  (.plusDays ^ZonedDateTime s 1)
                  id (scm/identity-tuple m)]]
      (is (= (schedule/price-segments rp mods s e opts)
             (scm/segments-for m s e))
          (str "segments-for " id " " s))
      (is (= (schedule/price-schedule-with-modifiers rp mods s e opts)
             (scm/schedule-for m s e))
          (str "schedule-for " id " " s)))))

(deftest a-horizon-across-a-daylight-transition-covers-the-wall-clock-day
  (testing "a local day from midnight to midnight is 23 hours in spring and 25
            in autumn in Los Angeles; the horizon covers exactly that, starting
            and ending on the bounds as given"
    (let [a     (first (scm/enumerate [(fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")]))
          mins  #(.toMinutes (java.time.Duration/between ^ZonedDateTime %1 ^ZonedDateTime %2))]
      (doseq [[nm f] [["segments-for" scm/segments-for] ["schedule-for" scm/schedule-for]]
              [d n]  [[(at 2026 3 8 0) 23] [(at 2026 11 1 0) 25]]
              :let [e    (.plusDays ^ZonedDateTime d 1)
                    segs (f a d e)]]
        (is (= (* 60 n) (mins d e)) "the window itself")
        (is (= d (:tick/beginning (first segs))) (str nm " starts on the bound " d))
        (is (= e (:tick/end (last segs))) (str nm " ends on the bound " e))
        (is (every? true? (map #(= (:tick/end %1) (:tick/beginning %2)) segs (rest segs)))
            (str nm " is contiguous across the transition " d))
        (is (= (* 60 n) (reduce + (map #(mins (:tick/beginning %) (:tick/end %)) segs)))
            (str nm " covers " n " hours on " d))
        (is (not-any? :urpx.interval/refused segs) (str nm " prices the whole day " d))))))

(deftest horizon-bounds-in-any-zone-walk-in-the-plans-zone
  (let [a  (first (scm/enumerate [(fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")]))
        s  (at 2026 7 15 0)
        e  (at 2026 7 16 0)
        in #(.withZoneSameInstant ^ZonedDateTime %1 (ZoneId/of %2))]
    (doseq [[nm f] [["segments-for" scm/segments-for] ["schedule-for" scm/schedule-for]]
            [zs ze] [["UTC" "UTC"] ["-07:00" "-07:00"] ["US/Pacific" "US/Pacific"]
                     ["Asia/Kolkata" "Asia/Kolkata"] ["UTC" "America/New_York"]]
            :let [segs (f a (in s zs) (in e ze))]]
      (is (= (f a s e) segs) (str nm ": bounds in " zs " and " ze " are the same instants"))
      (is (every? #(= la (.getZone ^ZonedDateTime (:tick/beginning %))
                      (.getZone ^ZonedDateTime (:tick/end %)))
                  segs)
          (str nm ": bounds in " zs " and " ze " give segments in the plan's zone")))
    (is (= ["2026-07-15T00:00-07:00[America/Los_Angeles]"
            "2026-07-15T16:00-07:00[America/Los_Angeles]"
            "2026-07-15T21:00-07:00[America/Los_Angeles]"]
           (mapv (comp str :tick/beginning) (scm/segments-for a (in s "UTC") (in e "UTC"))))
        "UTC bounds find the plan's local transitions")))

(deftest a-horizon-bound-that-is-not-a-zoned-date-time-is-refused
  (let [a (first (scm/enumerate [(fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")]))
        s (at 2026 7 15 0)
        e (at 2026 7 16 0)]
    (doseq [[nm f] [["segments-for" scm/segments-for] ["schedule-for" scm/schedule-for]]]
      (is (= :urpx.schedule/bound-not-zoned-date-time
             (:reason (refusal #(f a (.toInstant ^ZonedDateTime s) e))))
          nm))))

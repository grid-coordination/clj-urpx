(ns urpx.arrangements-test
  "Enumerating service arrangements.

  A service arrangement is one RatePlanVersion, the modifier versions a
  customer holds, and one ProfileAlternative per open dimension. The concept is
  NOT a URPX class: zero occurrences in the ontology and zero in the SHACL, and
  nothing here writes it into a document.

  The split that makes it tractable is what the documents can and cannot say.
  Compatibility, which modifier MAY apply to which plan, is stated, by
  urpx:applicableToRatePlan and urpx:applicableToRatePlanVersion. Which of them
  a customer HAS is not, and URPX excludes it deliberately. So this namespace
  answers the first and takes a policy for the second."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk]
            [clojure.test :refer [deftest is testing]]
            [urpx.arrangements :as arr]
            [urpx.index :as index]
            [urpx.price :as price]
            [urpx.test-fixtures :as fixtures])
  (:import [java.time ZoneId ZonedDateTime]))

(def ^:private la (ZoneId/of "America/Los_Angeles"))
(defn- at
  ([] (at 2026 7 15 17))
  ([y m d h] (ZonedDateTime/of y m d h 0 0 0 la)))

(defn- sce-docs []
  [(fixtures/load-rate-plan "sce-d-2026-03-20")
   (fixtures/load-modifier "sce-care-2026-03-20")
   (fixtures/load-modifier "sce-fera-2026-03-20")])

(defn- rate [a] (price/marginal-unit-rate (arr/resolve-at a (at))))

(deftest discovery-answers-what-may-apply
  (testing "the query a consumer actually wants, and the half URPX can answer.
            urpx:applicableToRatePlan states compatibility with a plan
            identity."
    (let [docs (sce-docs)
          bv   (first (arr/plan-versions docs))]
      (is (= ["sce:d-2026-03-20"] (mapv :jsonld/id (arr/plan-versions docs))))
      (is (= ["sce:care-2026-03-20" "sce:fera-2026-03-20"]
             (mapv :jsonld/id (arr/applicable-modifiers docs bv)))
          "both income-tier programs are compatible with Schedule D")
      (is (= ["org:utility-sce"]
             (keys (arr/offered-by (arr/applicable-modifiers docs bv))))
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
            possession. That is why `arrangements` takes a policy rather than
            inventing one: inventing it here is the arbitrary pick this library
            removes everywhere else."
    (let [docs (sce-docs)
          ids  #(mapv :urpx.arrangement/modifier-version-ids %)]
      (is (= [[] ["sce:care-2026-03-20"] ["sce:fera-2026-03-20"]]
             (ids (arr/arrangements docs)))
          "the default policy is conservative: base alone, or one modifier")
      (is (= [[] ["sce:care-2026-03-20"] ["sce:fera-2026-03-20"]
              ["sce:care-2026-03-20" "sce:fera-2026-03-20"]]
             (sort (ids (arr/arrangements docs {:combine arr/every-subset}))))
          "every-subset is complete and knowingly wrong: it pairs two
           mutually exclusive programs, because no document forbids it"))))

(deftest each-arrangement-resolves-to-its-own-price
  (testing "an arrangement is fully determined, so it prices with no further
            input. These are the filed CARE and FERA discounts on Schedule D."
    (let [by-mods (into {} (map (juxt :urpx.arrangement/modifier-version-ids identity))
                        (arr/arrangements (sce-docs)))]
      (is (= 0.31085M    (rate (by-mods []))) "base alone")
      (is (= 0.19804875M (rate (by-mods ["sce:care-2026-03-20"]))) "CARE")
      (is (= 0.2550770M  (rate (by-mods ["sce:fera-2026-03-20"]))) "FERA"))))

(deftest an-open-dimension-makes-several-arrangements
  (testing "a choice still outstanding is not one arrangement but several, and
            each is an arrangement in its own right. PG&E E-TOU-C carries a
            three-cell income-tier container, so its base-alone arrangement is
            three."
    (let [docs [(fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")]
          as   (arr/arrangements docs)
          sels (map :urpx.arrangement/selections as)]
      (is (= 3 (count as)))
      (is (every? #(= 1 (count %)) sels) "one choice each")
      (is (= #{"pge:pa-income-tier-care" "pge:pa-income-tier-fera"
               "pge:pa-income-tier-standard"}
             (set (mapcat vals sels)))
          "one arrangement per cell of the container"))))

(deftest identity-is-intrinsic-and-survives-equal-prices
  (testing "the tuple is computable from the documents alone and requires
            nothing external. Two arrangements differ when it differs EVEN IF
            they resolve to the same price."
    (let [as (arr/arrangements (sce-docs))]
      (is (= (count as) (count (distinct (map arr/arrangement-id as))))
          "distinct arrangements have distinct ids")
      (is (= ["sce:d-2026-03-20" ["sce:care-2026-03-20"] []]
             (arr/arrangement-id
              (first (filter #(= ["sce:care-2026-03-20"]
                                 (:urpx.arrangement/modifier-version-ids %)) as))))
          "base version, sorted modifier versions, sorted selections"))))

(deftest currency-drops-superseded-versions
  (testing "a superseded version is not a service arrangement: nobody takes
            service under it. SCE Schedule D holds two versions, effective
            2025-11-15 and 2026-03-20, so an instant after the later one leaves
            one base version and an instant between them leaves the earlier."
    (let [docs [(fixtures/load-rate-plan "sce-d-2025-11-15")
                (fixtures/load-rate-plan "sce-d-2026-03-20")]]
      (is (= #{"sce:d-2025-11-15" "sce:d-2026-03-20"}
             (set (map :jsonld/id (arr/plan-versions docs))))
          "both, with no instant: the whole expressed space")
      (is (= ["sce:d-2026-03-20"]
             (mapv :jsonld/id (arr/plan-versions docs {:at (at)})))
          "the later one in July")
      (is (= ["sce:d-2025-11-15"]
             (mapv :jsonld/id (arr/plan-versions docs {:at (at 2026 1 15 12)})))
          "and the earlier one in January, before its successor took effect"))))

(deftest resolve-at-binds-each-document-to-its-own-version
  (testing "both :version defects this library fixed were an opt reaching a
            document it did not belong to. An arrangement binds each piece by
            construction, so resolve-at passes a per-document :version map and
            the mistake becomes unrepresentable rather than repeatedly fixable.

            Extra opts merge over the arrangement's own."
    (let [a (first (filter #(= ["sce:care-2026-03-20"]
                               (:urpx.arrangement/modifier-version-ids %))
                           (arr/arrangements (sce-docs))))]
      (is (= 0.19804875M (rate a)))
      ;; Pass-through is asserted in its own deftest below, on a fixture whose
      ;; resolution actually consults the opt.
      (doseq [k [:version :profile-alternatives]]
        (is (= :urpx.arrangement/opt-determined-by-arrangement
               (try (arr/resolve-at a (at) {k {}})
                    (catch clojure.lang.ExceptionInfo e (:reason (ex-data e))))
               )
            (str k " is determined by the arrangement, so it is refused rather "
                 "than merged: letting the caller win restores the very defect "
                 "this function prevents, and letting the arrangement win "
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
    (let [a      (first (arr/arrangements [(fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")]))
          called (atom 0)]
      (price/marginal-unit-rate
       (arr/resolve-at a (at) {:holiday? (fn [_] (swap! called inc) false)}))
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
          e    (is (thrown? clojure.lang.ExceptionInfo (arr/arrangements docs)))]
      (is (= :urpx.arrangement/conflicting-version-id (:reason (ex-data e))))
      (is (= ["x:p-v1"] (:ids (ex-data e))) "the message names the offending @id"))))

(deftest every-conflicting-id-is-named-at-once
  (testing "naming only the first costs a caller one refusal round per bad
            filing, which is the lesson already recorded against
            :outside-version-window in 0.27.0."
    (let [docs [(plan-doc "x:a" "x:a-v" (ldt 2026 1 1))
                (plan-doc "x:a" "x:a-v" (ldt 2026 1 1) {:urpx/version "differs"})
                (plan-doc "x:b" "x:b-v" (ldt 2026 1 1))
                (plan-doc "x:b" "x:b-v" (ldt 2026 1 1) {:urpx/version "differs"})]
          e    (is (thrown? clojure.lang.ExceptionInfo (arr/arrangements docs)))]
      (is (= ["x:a-v" "x:b-v"] (:ids (ex-data e)))
          "both, in one refusal, not one per round trip"))))

(deftest the-same-snapshot-published-twice-is-deduplicated
  (testing "two filings carrying a byte-identical version node are one version
            published twice. That is benign and must NOT refuse: only differing
            content is a contradiction. Before this deduplicated, a repeat
            produced duplicate arrangements."
    (let [one  [(plan-doc "x:p" "x:p-v1" (ldt 2026 1 1))]
          twice (conj one (plan-doc "x:p" "x:p-v1" (ldt 2026 1 1)))]
      (is (= 1 (count (arr/arrangements one))))
      (is (= (arr/arrangements one) (arr/arrangements twice))
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
                            (arr/arrangements docs {:at (at 2026 6 1 12)})))]
      (is (= :urpx.arrangement/ambiguous-version (:reason (ex-data e))))
      (is (= ["x:p-v1" "x:p-v2"] (sort (:ids (ex-data e))))))))

(deftest uncoerced-documents-are-refused-not-answered
  (testing "the defect the peer's cross-check probe walked into. urpx.core/load-graph
            returns PARSED but UNTYPED entities, so urpx:effectiveDateTime is
            still a string. With an instant that threw a ClassCastException from
            deep inside; with no instant nothing compares a date at all, so the
            untyped documents sailed through and 202 pairs came back where the
            coerced form gives 144. A plausible number, not a refusal, and in a
            CI gate it would have read as an enumeration disagreement between
            two implementations.

            Every public entry point guards, since each is reachable first."
    (let [raw (fixtures/load-graph "sce-d-2026-03-20")]
      (doseq [[nm f] [["arrangements"         #(arr/arrangements raw)]
                      ["arrangements :at"     #(arr/arrangements raw {:at (at)})]
                      ["plan-versions"        #(arr/plan-versions raw)]]]
        (let [e (is (thrown? clojure.lang.ExceptionInfo (f)) nm)]
          (is (= :uncoerced-document (:reason (ex-data e))) nm))))))

(deftest the-answer-does-not-depend-on-document-order
  (testing "the property the max-key tie-break silently broke. Asserted on the
            FULL result rather than on counts, since the order dependence moved
            content under an unchanged count as well as moving the count."
    (let [docs (sce-docs)]
      (doseq [perm [[0 1 2] [2 1 0] [1 0 2] [1 2 0] [2 0 1]]]
        (is (= (arr/arrangements docs {:at (at)})
               (arr/arrangements (mapv docs perm) {:at (at)}))
            (str "permutation " perm " must give an identical result"))))))

(deftest at-restricts-to-current-versions
  (testing "the :at option had no assertion at all: making it a no-op left all
            346 tests green. SCE Schedule D holds two versions, so an instant
            before the later one takes effect must not see it."
    (let [docs [(fixtures/load-rate-plan "sce-d-2025-11-15")
                (fixtures/load-rate-plan "sce-d-2026-03-20")]]
      (is (= 2 (count (arr/arrangements docs)))
          "both versions with no instant: the whole expressed space")
      (is (= ["sce:d-2026-03-20"]
             (mapv :urpx.arrangement/version-id (arr/arrangements docs {:at (at)}))))
      (is (= ["sce:d-2025-11-15"]
             (mapv :urpx.arrangement/version-id
                   (arr/arrangements docs {:at (at 2026 1 15 12)})))))))

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
      (is (= ["x:p-v"] (mapv :urpx.arrangement/version-id (arr/arrangements docs)))
          "present with no instant")
      (is (= ["x:p-v"] (mapv :urpx.arrangement/version-id
                             (arr/arrangements docs {:at (at)})))
          "and in force at an instant after that date"))))

(deftest each-arrangement-carries-the-document-its-snapshot-came-from
  (testing "the 0.28.0 defect class a third time. The document was recovered by
            version @id from an (into {} ...) map, so when two documents publish
            one @id the loser's snapshot was paired with the winner's document.

            Identity, not @id: the bound document must be the one that literally
            contains this node."
    (let [docs (sce-docs)]
      (doseq [a (arr/arrangements docs)]
        (letfn [(nodes [d k] (filter #(and (map? %) (= k (:jsonld/type %)))
                                     (tree-seq coll? seq d)))]
          (is (some #(identical? (:urpx.arrangement/version a) %)
                    (nodes (:urpx.arrangement/rate-plan a) "urpx:RatePlanVersion"))
              "base version must be a node of its own bound document")
          (is (every? true?
                      (map (fn [mv md]
                             (boolean (some #(identical? mv %)
                                            (nodes md "urpx:RatePlanModifierVersion"))))
                           (:urpx.arrangement/modifier-versions a)
                           (:urpx.arrangement/modifiers a)))
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
   2026-01-01 to 2026-06-01, v2 from 2026-06-01. No fixture has this shape
   (checked: not one of the 23 carries more than one RatePlanVersion), which is
   why the binding below was invisible to every test until now."
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

(deftest resolve-at-uses-the-arrangements-version-not-urpx-prices-own-choice
  (testing "the per-document :version map is the whole reason this namespace
            offers resolve-at, and deleting it outright changed no number in
            1382 assertions. It was invisible because every fixture document
            carries exactly ONE version, so urpx.price's own rules could only
            ever reach the same snapshot.

            Made visible with a document carrying two: build the arrangement at
            an instant only v1 covers, then resolve at an instant only v2
            covers.

            The binding turns out to do more than choose. Without it urpx.price
            refuses the document outright, \"Multiple nested version snapshots;
            this library reads one. Pass the intended node explicitly.\" So the
            :version map is not an optimization over urpx.price's own rules, it
            is what makes a multi-version document resolvable at all, and an
            arrangement supplies it by construction."
    (let [docs [(two-version-plan)]
          a    (first (arr/arrangements docs {:at (at 2026 3 1 12)}))
          res  (try (arr/resolve-at a (at 2026 7 15 17))
                    (catch clojure.lang.ExceptionInfo e
                      {::refused (or (:reason (ex-data e)) (:what (ex-data e)))}))]
      (is (= "x:p-v1" (:urpx.arrangement/version-id a))
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

            The default narrows, matching the ontology's wording. The monotone
            reading is sound under RDF, where merging adds triples and never
            retracts, so a larger corpus cannot yield fewer arrangements.

            CONSTRUCTED, because the readings differ only when a modifier names
            some version, about a base version it does not name whose plan it
            names."
    (let [plan (plan-doc "x:p" "x:p-v1" (ldt 2026 1 1))
          m    (modifier-doc "x:m" "x:m-v" (ldt 2026 1 1)
                             {:urpx/applicableToRatePlan        [{:jsonld/id "x:p"}]
                              :urpx/applicableToRatePlanVersion [{:jsonld/id "x:other-v"}]})
          bv   (:urpx/hasRatePlanVersion plan)
          ids  #(mapv :jsonld/id (arr/applicable-modifiers [plan m] bv %))]
      (is (= [] (ids nil))
          "default: naming a version means those and only those, so naming
           x:other-v excludes this one even though its PLAN is named")
      (is (= [] (ids {:compatibility arr/named-versions-narrow}))
          "stated explicitly, same answer")
      (is (= ["x:m-v"] (ids {:compatibility arr/named-versions-add}))
          "monotone: the version statement adds, so the plan statement still
           applies and the modifier is compatible")
      (is (= [[] ["x:m-v"]]
             (mapv :urpx.arrangement/modifier-version-ids
                   (arr/arrangements [plan m] {:compatibility arr/named-versions-add})))
          "and the policy reaches arrangements, not only applicable-modifiers"))))

(deftest a-modifier-naming-no-plan-and-no-version-combines-with-nothing
  (testing "both properties are sh:minCount 0, so a conformant modifier version
            may state neither. It then expresses no compatibility at all, and
            the reading that refuses rather than guesses is that it combines
            with nothing: treating silence as universal compatibility would
            attach every such modifier to every plan in the corpus."
    (let [plan (plan-doc "x:p" "x:p-v1" (ldt 2026 1 1))
          m    (modifier-doc "x:m" "x:m-v" (ldt 2026 1 1) {})]
      (is (= [] (mapv :jsonld/id (arr/applicable-modifiers [plan m]
                                                           (:urpx/hasRatePlanVersion plan)))))
      (is (= [[]] (mapv :urpx.arrangement/modifier-version-ids
                        (arr/arrangements [plan m])))
          "so the plan has exactly one arrangement, itself, and the modifier
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
             (sort (mapv :jsonld/id (arr/applicable-modifiers [plan old] bv))))
          "both, with no instant: the whole expressed space")
      (is (= ["x:m-old"] (mapv :jsonld/id (arr/applicable-modifiers [plan old] bv
                                                                    {:at (at 2026 3 1 12)})))
          "in March only the first has taken effect")
      (is (= ["x:m-new"] (mapv :jsonld/id (arr/applicable-modifiers [plan old] bv
                                                                    {:at (at 2026 7 1 12)})))
          "in July the later one supersedes it"))))

(deftest arrangement-id-distinguishes-arrangements-that-differ-only-by-selection
  (testing "the selections component of the id was untested: dropping it left
            three genuinely different PG&E E-TOU-C arrangements sharing one id,
            with the suite green. They have the same base version, the same
            (empty) modifier set, and differ ONLY in which income-tier
            alternative is chosen, so selections are the only thing that can
            tell them apart."
    (let [as  (arr/arrangements [(fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")])
          ids (mapv arr/arrangement-id as)]
      (is (= 3 (count as)))
      (is (= 1 (count (distinct (map :urpx.arrangement/version-id as))))
          "same base version")
      (is (= 1 (count (distinct (map :urpx.arrangement/modifier-version-ids as))))
          "and the same modifier set, so the id has nothing else to go on")
      (is (= 3 (count (distinct ids)))
          "three distinct ids, which holds only if selections are in the id"))))


;; ---------------------------------------------------------------------------
;; Horizons
;; ---------------------------------------------------------------------------

(defn- inst [y m d h] (.toInstant (at y m d h)))

(deftest segments-for-derives-boundaries-from-the-documents
  (testing "the price-server shape: the interval containing an instant and the
            ones after it, with boundaries where the tariff actually changes
            rather than where a step grid lands. PG&E E-TOU-C has TOU periods,
            so a day must come back as several segments, contiguous and
            covering the window exactly."
    (let [a    (first (arr/arrangements [(fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")]))
          segs (arr/segments-for a (inst 2026 7 15 0) (inst 2026 7 16 0))]
      (is (< 1 (count segs)) "a TOU day is not one segment")
      (is (= (.toInstant ^ZonedDateTime (:tick/beginning (first segs))) (inst 2026 7 15 0))
          "starts where asked")
      (is (= (.toInstant ^ZonedDateTime (:tick/end (last segs))) (inst 2026 7 16 0))
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

(deftest a-horizon-carries-the-arrangements-selection
  (testing "the choice an arrangement makes has to reach the horizon path, not
            only the instant path.

            It cannot be asserted on the bare fixture. Measured: the three
            income tiers resolve to the same marginal rate at this instant,
            with and without the baseline-tier modifier composed, and dropping
            :profile-alternatives from the binding changes nothing, because the
            fixture scopes no PriceDefinition to an alternative. The cells exist
            and nothing hangs on them.

            So scope one, exactly as urpx.pd-alternatives-test does. Then the
            selection is load-bearing: the arrangement that chose that cell
            prices, and with no selection at all the prices are withheld and
            the rate refuses :unselected-alternatives."
    (let [plan (fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")
          pas  (first (vals (index/entities-of-type plan "urpx:ProfileAlternatives")))
          alt  (:jsonld/id (first (:urpx/hasProfileAlternative pas)))
          doc  (scope-pd-to plan [alt])
          a    (first (filter #(= {(:jsonld/id pas) alt} (:urpx.arrangement/selections %))
                              (arr/arrangements [doc])))
          segs (arr/segments-for a (inst 2026 7 15 0) (inst 2026 7 16 0))]
      (is (some? a) "there is an arrangement that chose the scoped cell")
      (is (not-any? :urpx.interval/refused segs) "no segment refuses")
      (is (= [] (->> segs
                     (keep #(try (price/marginal-unit-rate (:urpx.interval/resolved %)) nil
                                 (catch clojure.lang.ExceptionInfo e (:reason (ex-data e)))))
                     distinct vec))
          "every segment yields a rate; without the selection reaching
           urpx.price each refuses :unselected-alternatives"))))

(deftest a-horizon-binds-each-document-to-its-own-version
  (testing "the reason these functions exist rather than letting a caller reach
            for urpx.schedule directly. Going through urpx.schedule means
            taking the arrangement apart and rebuilding the per-document
            :version map by hand, which is the reconstruction resolve-at exists
            to prevent.

            Same two-version document as the resolve-at test: without the
            binding urpx.price cannot choose between the nested snapshots and
            every segment comes back refused."
    (let [a    (first (arr/arrangements [(two-version-plan)] {:at (at 2026 3 1 12)}))
          segs (arr/segments-for a (inst 2026 2 1 0) (inst 2026 2 2 0)
                                 (java.time.ZoneId/of "America/Los_Angeles"))]
      (is (seq segs))
      (is (not-any? :urpx.interval/refused segs)
          "unbound, urpx.price refuses a document carrying two snapshots and
           every segment carries :urpx.interval/refused instead of a price"))))

(deftest a-horizon-refuses-the-opts-the-arrangement-determines
  (testing "same discipline as resolve-at, and asserted separately because a
            second entry point is a second chance to forget it. One builder
            serves all three, so this also pins that they share it."
    (let [a (first (arr/arrangements [(fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")]))]
      (doseq [[nm f] [["segments-for" #(arr/segments-for a (inst 2026 7 15 0) (inst 2026 7 16 0) nil %)]
                      ["schedule-for" #(arr/schedule-for a (inst 2026 7 15 0) (inst 2026 7 16 0) nil %)]]
              k       [:version :profile-alternatives]]
        (let [e (is (thrown? clojure.lang.ExceptionInfo (f {k {}})) (str nm " " k))]
          (is (= :urpx.arrangement/opt-determined-by-arrangement (:reason (ex-data e)))
              (str nm " " k)))))))

(deftest schedule-for-walks-a-grid-and-agrees-with-segments-on-price
  (testing "the stepped variant, for callers that want a fixed cadence. It must
            agree with segments-for about the price at a given instant; the two
            differ in where boundaries fall, not in what anything costs."
    (let [a     (first (arr/arrangements [(fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")]))
          sched (arr/schedule-for a (inst 2026 7 15 0) (inst 2026 7 16 0))
          rate  #(some-> (:urpx.interval/resolved %) price/marginal-unit-rate)]
      (is (seq sched))
      (is (= (set (keep rate sched))
             (set (keep rate (arr/segments-for a (inst 2026 7 15 0) (inst 2026 7 16 0)))))
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
    (let [a     (first (arr/arrangements [(fixtures/load-rate-plan "pge-e-tou-c-2026-03-01")]))
          start (inst 2026 7 15 0)
          end   (inst 2026 7 16 0)
          coarse {:step (java.time.Duration/ofHours 6)}
          hhmm  (fn [xs] (mapv #(+ (* 100 (.getHour ^ZonedDateTime (:tick/beginning %)))
                                   (.getMinute ^ZonedDateTime (:tick/beginning %))) xs))]
      (is (= [0 1600 2100] (hhmm (arr/segments-for a start end nil coarse)))
          "the real transitions, found at a six-hour step because they are read
           rather than sampled")
      (is (= [0 1800] (hhmm (arr/schedule-for a start end nil coarse)))
          "while the stepped walk sees only what it lands on, putting a
           boundary at 18:00 that is not a transition and missing both that
           are"))))

(deftest a-horizon-includes-the-arrangements-modifiers
  (testing "dropping the modifiers leaves a horizon that prices the base plan
            and publishes it as the customer's, which is the 0.28.0 defect
            reappearing one function along. SCE Schedule D composed with CARE
            is a 36% discount, so the two are not confusable."
    (let [by-mods (into {} (map (juxt :urpx.arrangement/modifier-version-ids identity))
                        (arr/arrangements (sce-docs)))
          rates   (fn [a] (set (map #(price/marginal-unit-rate (:urpx.interval/resolved %))
                                    (arr/segments-for a (inst 2026 7 15 0) (inst 2026 7 16 0)))))]
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
            (:require [urpx.arrangements]) passes both, and by the time this
            test runs the test suite has loaded the namespace anyway."
    (let [srcs (->> (file-seq (io/file "src"))
                    (filter #(.isFile ^java.io.File %))
                    (filter #(str/ends-with? (.getName ^java.io.File %) ".clj"))
                    (remove #(= "arrangements.clj" (.getName ^java.io.File %))))
          mentions (filter (fn [f]
                             (let [form (with-open [r (java.io.PushbackReader. (io/reader f))]
                                          (read r))]
                               (some #(= 'urpx.arrangements %) (tree-seq coll? seq form))))
                           srcs)]
      (is (seq srcs) "the source tree was found, so an empty result means clean")
      (is (= [] (mapv #(.getPath ^java.io.File %) mentions))
          "no namespace in src/ may require urpx.arrangements"))))

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
      (is (= :urpx.arrangement/conflicting-version-id
             (try (arr/arrangements docs) nil
                  (catch clojure.lang.ExceptionInfo e (:reason (ex-data e)))))
          "the default still refuses")
      (let [as (arr/arrangements docs {:repeated-ids arr/repeated-ids-union})]
        (is (= 1 (count as)) "merged into one version, not two")
        (is (= ["x:m1" "x:m2"]
               (mapv :jsonld/id
                     (:urpx/hasModifierOptions (:urpx.arrangement/version (first as)))))
            "BOTH option lists survive: the union, not whichever came first")))
    (testing "and a property only one occurrence states merges to that value,
              because absence is not an assertion under RDF"
      (let [as (arr/arrangements (twice {:urpx/hasModifierOptions [{:jsonld/id "x:m2"}]})
                                 {:repeated-ids arr/repeated-ids-union})]
        (is (= ["x:m2"] (mapv :jsonld/id
                              (:urpx/hasModifierOptions (:urpx.arrangement/version (first as))))))))))

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
          bound     #(->> (arr/arrangements % {:repeated-ids arr/repeated-ids-union})
                          (filter (comp seq :urpx.arrangement/modifier-version-ids))
                          first :urpx.arrangement/modifiers first)]
      (doseq [order [[own-doc bundled] [bundled own-doc]]]
        (let [d (bound order)]
          (is (= "urpx:RatePlanModifier" (:jsonld/type d))
              "the bound document is a modifier, not the plan that bundled it")
          (is (= "x:m" (:jsonld/id d))
              "and it is the modifier's OWN filing, whichever order the documents came in"))))))

(deftest the-default-retracts-across-plans-which-is-worth-knowing
  (testing "naming what the default actually does, because it turned out to be
            the least supported of THREE readings rather than one of two, and
            the difference is invisible until a modifier names more than one
            plan.

            A modifier naming plans P and Q, with a version of P only:

              add     compatible with both, naming a version retracts nothing
              narrow  compatible with NEITHER unless the version matches, so Q
                      loses a modifier its own plan-level statement named

            The middle reading, where naming a version of P restricts P and
            leaves Q's plan-level statement standing, is what URPX Decision
            #090's Rationale points nearest and is NOT implemented: a policy
            receives version @ids as strings and cannot learn which plan each
            belongs to without the documents. Filed rather than approximated."
    (let [plan (fn [pid vid] {:jsonld/id pid :jsonld/type "urpx:RatePlan"
                              :urpx/timezoneIdentifier "America/Los_Angeles"
                              :urpx/hasRatePlanVersion
                              {:jsonld/id vid :jsonld/type "urpx:RatePlanVersion"
                               :urpx/forRatePlan {:jsonld/id pid}
                               :urpx/effectiveDateTime (ldt 2026 1 1)}})
          m    (modifier-doc "x:m" "x:m-v" (ldt 2026 1 1)
                             {:urpx/applicableToRatePlan [{:jsonld/id "x:P"} {:jsonld/id "x:Q"}]
                              :urpx/applicableToRatePlanVersion [{:jsonld/id "x:P-v"}]})
          ask  (fn [p pol] (mapv :jsonld/id
                                 (arr/applicable-modifiers [p m] (:urpx/hasRatePlanVersion p)
                                                           {:compatibility pol})))
          P    (plan "x:P" "x:P-v")
          Q    (plan "x:Q" "x:Q-v")]
      (is (= ["x:m-v"] (ask P arr/named-versions-narrow))
          "P's named version matches, so the default applies it")
      (is (= [] (ask Q arr/named-versions-narrow))
          "but Q loses it, although the modifier names Q at plan level and names
           no version of Q at all. That is retraction ACROSS plans, and nothing
           in Decision #090 argues for it")
      (is (= ["x:m-v"] (ask Q arr/named-versions-add))
          "the monotone reading keeps Q, which is the behaviour the
           preserves-document-level-properties line supports"))))

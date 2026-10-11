(ns urpx.identity-status-test
  "`identity-status-at`: whether a plan or modifier identity is in force,
  modeled, or in effect at an instant."
  (:require [clojure.test :refer [deftest is testing]]
            [urpx.scenario-cost-model :as scm])
  (:import [java.time LocalDate LocalDateTime ZoneId ZoneOffset ZonedDateTime]))

(def ^:private la (ZoneId/of "America/Los_Angeles"))

(defn- at
  ([y mo d] (at y mo d 0 0))
  ([y mo d h mi] (ZonedDateTime/of y mo d h mi 0 0 la)))

(defn- before [^ZonedDateTime t] (.minusNanos t 1))

(defn- version
  [plan id props]
  (merge {:jsonld/type "urpx:RatePlanVersion" :jsonld/id id
          :urpx/forRatePlan {:jsonld/id plan}}
         props))

(defn- plan
  [id props & versions]
  (merge {:jsonld/type "urpx:RatePlan" :jsonld/id id
          :urpx/timezoneIdentifier "America/Los_Angeles"
          :urpx/hasRatePlanVersion (vec versions)}
         props))

(def ^:private p
  "Lifetime from a date to a dateTime. v1 has a dateTime start and a date end,
   then a gap, then v2 with a date start, superseded by v3 before both expire,
   then nothing."
  (plan "t:p" {:urpx/initialEffectiveDate (LocalDate/of 2020 1 1)
               :urpx/expirationDateTime (LocalDateTime/of 2030 1 1 12 0)}
        (version "t:p" "t:p-v1" {:urpx/effectiveDateTime (LocalDateTime/of 2022 1 1 0 0)
                                 :urpx/expirationDate (LocalDate/of 2023 1 1)})
        (version "t:p" "t:p-v2" {:urpx/effectiveDate (LocalDate/of 2023 6 1)
                                 :urpx/expirationDateTime (LocalDateTime/of 2026 1 1 0 0)})
        (version "t:p" "t:p-v3" {:urpx/effectiveDateTime (LocalDateTime/of 2025 1 1 0 0)
                                 :urpx/expirationDateTime (LocalDateTime/of 2026 1 1 0 0)})))

(def ^:private q
  "Two versions, neither stating an end."
  (plan "t:q" {:urpx/initialEffectiveDate (LocalDate/of 2020 1 1)}
        (version "t:q" "t:q-v1" {:urpx/effectiveDateTime (LocalDateTime/of 2024 1 1 0 0)})
        (version "t:q" "t:q-v2" {:urpx/effectiveDateTime (LocalDateTime/of 2026 1 1 0 0)})))

(def ^:private r
  "A version stating no end, and a later one that expires."
  (plan "t:r" {:urpx/initialEffectiveDate (LocalDate/of 2020 1 1)}
        (version "t:r" "t:r-v1" {:urpx/effectiveDateTime (LocalDateTime/of 2022 1 1 0 0)})
        (version "t:r" "t:r-v2" {:urpx/effectiveDateTime (LocalDateTime/of 2024 1 1 0 0)
                                 :urpx/expirationDateTime (LocalDateTime/of 2025 1 1 0 0)})))

(def ^:private u
  "A version stating no effective instant, and a dated one."
  (plan "t:u" {:urpx/initialEffectiveDate (LocalDate/of 2020 1 1)}
        (version "t:u" "t:u-v0" {})
        (version "t:u" "t:u-v1" {:urpx/effectiveDateTime (LocalDateTime/of 2025 1 1 0 0)})))

(def ^:private m
  "A modifier identity with no versions."
  {:jsonld/type "urpx:RatePlanModifier" :jsonld/id "t:m"
   :urpx/timezoneIdentifier "America/Los_Angeles"
   :urpx/initialEffectiveDate (LocalDate/of 2021 1 1)})

(def ^:private docs [p q r u m])

(defn- status [id t] (scm/identity-status-at docs id t))

(defn- refusal
  [docs id t]
  (try (scm/identity-status-at docs id t) nil
       (catch clojure.lang.ExceptionInfo e (:reason (ex-data e)))))

(defn- same-instant? [^ZonedDateTime a ^ZonedDateTime b]
  (if (and a b) (.isEqual a b) (= a b)))

(defn- summary
  "`s` with the version as its @id, for comparing."
  [s]
  (cond-> s (:version s) (update :version :jsonld/id)))

(deftest each-status
  (testing "in force, ended by its own expiration"
    (is (= {:status :in-force :version "t:p-v1" :from (at 2022 1 1) :until (at 2023 1 1)}
           (summary (status "t:p" (at 2022 6 1))))))
  (testing "in force until a successor's start, before its own expiration"
    (is (= {:status :in-force :version "t:p-v2" :from (at 2023 6 1) :until (at 2025 1 1)}
           (summary (status "t:p" (at 2024 1 1))))))
  (testing "in force with no end stated, ended by a successor's start"
    (is (= {:status :in-force :version "t:q-v1" :from (at 2024 1 1) :until (at 2026 1 1)}
           (summary (status "t:q" (at 2025 1 1))))))
  (testing "in force, open-ended"
    (is (= {:status :in-force :version "t:q-v2" :from (at 2026 1 1) :until nil}
           (summary (status "t:q" (at 2027 1 1))))))
  (testing "a version stating no end is in force again once a later one expires,
            as enumerate :at takes it"
    (is (= {:status :in-force :version "t:r-v1" :from (at 2022 1 1) :until (at 2024 1 1)}
           (summary (status "t:r" (at 2023 1 1)))))
    (is (= {:status :in-force :version "t:r-v1" :from (at 2025 1 1) :until nil}
           (summary (status "t:r" (at 2025 6 1))))))
  (testing "not in effect before initialEffectiveDate"
    (is (= {:status :not-in-effect :from (at 2020 1 1) :until (at 2030 1 1 12 0)}
           (status "t:p" (at 2019 6 1)))))
  (testing "not in effect at the identity's expiration instant"
    (is (= {:status :not-in-effect :from (at 2020 1 1) :until (at 2030 1 1 12 0)}
           (status "t:p" (at 2030 1 1 12 0)))))
  (testing "not modeled before the first version"
    (is (= {:status :not-modeled :after nil :before "t:p-v1"
            :from (at 2020 1 1) :until (at 2022 1 1)}
           (status "t:p" (at 2021 1 1)))))
  (testing "not modeled in a gap"
    (is (= {:status :not-modeled :after "t:p-v1" :before "t:p-v2"
            :from (at 2023 1 1) :until (at 2023 6 1)}
           (status "t:p" (at 2023 3 1)))))
  (testing "not modeled after the last version"
    (is (= {:status :not-modeled :after "t:p-v3" :before nil
            :from (at 2026 1 1) :until (at 2030 1 1 12 0)}
           (status "t:p" (at 2027 1 1)))))
  (testing "not modeled with no versions, and not in effect before it began"
    (is (= {:status :not-modeled :after nil :before nil :from (at 2021 1 1) :until nil}
           (status "t:m" (at 2022 1 1))))
    (is (= {:status :not-in-effect :from (at 2021 1 1) :until nil}
           (status "t:m" (at 2020 1 1))))))

(deftest each-boundary-from-both-sides
  (doseq [[bound before-it at-it]
          [[(at 2020 1 1)       :not-in-effect      :not-modeled]
           [(at 2022 1 1)       :not-modeled        "t:p-v1"]
           [(at 2023 1 1)       "t:p-v1"            :not-modeled]
           [(at 2023 6 1)       :not-modeled        "t:p-v2"]
           [(at 2025 1 1)       "t:p-v2"            "t:p-v3"]
           [(at 2026 1 1)       "t:p-v3"            :not-modeled]
           [(at 2030 1 1 12 0)  :not-modeled        :not-in-effect]]
          [t want] [[(before bound) before-it] [bound at-it]]]
    (testing (str t)
      (let [s (status "t:p" t)]
        (is (= want (if (string? want) (:jsonld/id (:version s)) (:status s))))))))

(deftest one-instant-in-two-zones
  (doseq [[la-t utc-t] [[(at 2024 1 1) (ZonedDateTime/of 2024 1 1 8 0 0 0 ZoneOffset/UTC)]
                        [(at 2023 1 1) (ZonedDateTime/of 2023 1 1 8 0 0 0 ZoneOffset/UTC)]
                        [(before (at 2023 1 1))
                         (before (ZonedDateTime/of 2023 1 1 8 0 0 0 ZoneOffset/UTC))]]]
    (let [a (summary (status "t:p" la-t))
          b (summary (status "t:p" utc-t))]
      (is (= (dissoc a :from :until) (dissoc b :from :until)))
      (is (same-instant? (:from a) (:from b)))
      (is (same-instant? (:until a) (:until b))))))

(deftest a-version-with-no-effective-instant
  (testing "is in force wherever no dated version is, as enumerate :at takes it"
    (is (= {:status :in-force :version "t:u-v0" :from nil :until (at 2025 1 1)}
           (summary (status "t:u" (at 2010 1 1)))))
    (is (= "t:u-v1" (:jsonld/id (:version (status "t:u" (at 2025 1 1))))))))

(deftest version-in-force-is-the-one-plan-versions-takes
  (doseq [y (range 2018 2032)
          mo [1 3 6 9]
          :let [t (at y mo 1)]
          id ["t:p" "t:q" "t:r" "t:u"]
          :let [s (status id t)]
          :when (= :in-force (:status s))]
    (is (= [(:version s)]
           (filter #(= id (:jsonld/id (:urpx/forRatePlan %)))
                   (scm/plan-versions docs {:at t})))
        (str id " at " t))))

(deftest prepared-documents-answer-alike
  (let [prepared (scm/prepare docs)]
    (doseq [t [(at 2019 1 1) (at 2021 1 1) (at 2023 3 1) (at 2024 1 1) (at 2027 1 1)]]
      (is (= (status "t:p" t) (scm/identity-status-at prepared "t:p" t))))))

(def ^:private tie
  (plan "t:tie" {:urpx/initialEffectiveDate (LocalDate/of 2020 1 1)}
        (version "t:tie" "t:tie-a" {:urpx/effectiveDateTime (LocalDateTime/of 2024 1 1 0 0)})
        (version "t:tie" "t:tie-b" {:urpx/effectiveDateTime (LocalDateTime/of 2024 1 1 0 0)})))

(deftest refusals
  (testing "an instant that is not a ZonedDateTime"
    (is (= :urpx.scenario-cost-model/not-zoned-instant
           (refusal docs "t:p" (LocalDateTime/of 2024 1 1 0 0)))))
  (testing "an @id nothing carries"
    (is (= :urpx.scenario-cost-model/unknown-identity
           (refusal docs "t:nope" (at 2024 1 1)))))
  (testing "a tie at the instant, and beside a not-modeled period"
    (is (= :urpx.scenario-cost-model/ambiguous-version
           (refusal [tie] "t:tie" (at 2025 1 1))))
    (is (= :urpx.scenario-cost-model/ambiguous-version
           (refusal [tie] "t:tie" (at 2023 1 1)))))
  (testing "no initialEffectiveDate where the lifetime decides"
    (let [docs [(plan "t:n" {} (version "t:n" "t:n-v1"
                                        {:urpx/effectiveDateTime (LocalDateTime/of 2024 1 1 0 0)}))]]
      (is (= :urpx.scenario-cost-model/no-initial-effective-date
             (refusal docs "t:n" (at 2023 1 1))))
      (is (nil? (refusal docs "t:n" (at 2025 1 1)))
          "a version in force answers without the lifetime")))
  (testing "copies stating different lifetimes"
    (is (= :urpx.scenario-cost-model/conflicting-lifetime
           (refusal [m (assoc m :urpx/initialEffectiveDate (LocalDate/of 2022 1 1))]
                    "t:m" (at 2023 1 1)))))
  (testing "nothing zones the identity"
    (is (= :urpx.price/no-timezone
           (refusal [(dissoc m :urpx/timezoneIdentifier)] "t:m" (at 2023 1 1))))))

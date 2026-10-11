;; SPDX-FileCopyrightText: 2026 Clark Communications Corporation
;; SPDX-License-Identifier: MIT
(ns continuity-check
  "Compare `identity-status-at` over a corpus with the corpus's own
  reports/version-continuity.edn.

      clojure -M:dev:corpus -m continuity-check <corpus-dir>

  Each identity is probed at its urpx:initialEffectiveDate and at every
  expiration instant of its versions. Each :not-modeled answer with a
  `:before`, its bounds read as dates in the identity's zone, must be one of
  the report's `:gaps` (when it has an `:after`) or `:before-first-version`
  (when it has none), and every row of those must be found.

  Exit 0 when the two sets agree, 1 when they differ, 2 when refused."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [corpus-sweep]
            [urpx.price :as price]
            [urpx.scenario-cost-model :as scm])
  (:import [java.time LocalDate ZoneId ZonedDateTime]))

(def ^:private identity-types #{"urpx:RatePlan" "urpx:RatePlanModifier"})

(def ^:private back-pointer
  {"urpx:RatePlanVersion"         :urpx/forRatePlan
   "urpx:RatePlanModifierVersion" :urpx/forRatePlanModifier})

(defn- nodes [docs pred]
  (for [d docs, n (tree-seq coll? seq d) :when (and (map? n) (pred n))] [n d]))

(defn- probes
  "id -> the instants to probe it at."
  [docs]
  (let [zone-of (fn [n d] (some-> (or (:urpx/timezoneIdentifier n) (:urpx/timezoneIdentifier d))
                                  ZoneId/of))
        starts  (for [[n d] (nodes docs #(identity-types (:jsonld/type %)))
                      :let [ied (:urpx/initialEffectiveDate n) zone (zone-of n d)]
                      :when (and ied zone)]
                  [(:jsonld/id n) (.atStartOfDay ^LocalDate ied zone)])
        ends    (for [[v d] (nodes docs #(back-pointer (:jsonld/type %)))
                      :let [id (:jsonld/id (get v (back-pointer (:jsonld/type v))))
                            exp (some->> (zone-of v d) (price/version-expires-at v))]
                      :when exp]
                  [id exp])]
    (reduce (fn [m [id t]] (update m id (fnil conj #{}) t)) {} (concat starts ends))))

(defn- day [^ZonedDateTime t] (str (.toLocalDate t)))

(defn- row [id {:keys [after before from until]}]
  (cond-> {:plan id :before before :from (day from) :to (day until)}
    after (assoc :after after)))

(defn -main
  [& [root]]
  (when-not root
    (binding [*out* *err*] (println "REFUSED: no corpus directory given."))
    (System/exit 2))
  (let [docs     (:urpx.load/documents (corpus-sweep/load-corpus (io/file root)))
        prepared (scm/prepare docs)
        report   (edn/read-string (slurp (io/file root "reports" "version-continuity.edn")))
        results  (for [[id ts] (sort-by key (probes docs)), t (sort ts)]
                   [id t (try (scm/identity-status-at prepared id t)
                              (catch clojure.lang.ExceptionInfo e {:refused (ex-data e)}))])
        refused  (filter #(:refused (nth % 2)) results)
        ours     (into #{}
                       (keep (fn [[id _ s]]
                               (when (and (= :not-modeled (:status s)) (:before s))
                                 (row id s))))
                       results)
        theirs   (into (set (map #(select-keys % [:plan :after :before :from :to]) (:gaps report)))
                       (map #(select-keys % [:plan :before :from :to]))
                       (:before-first-version report))
        only-ours   (set/difference ours theirs)
        only-theirs (set/difference theirs ours)]
    (println "documents     :" (count docs))
    (println "probes        :" (count results) "over" (count (probes docs)) "identities")
    (println "refused       :" (count refused))
    (doseq [[id t {r :refused}] refused]
      (println "  " id (str t) (:reason r)))
    (println "not modeled   :" (count ours) "periods;" "report:" (count theirs) "rows;"
             "agree on" (count (set/intersection ours theirs)))
    (doseq [r (sort-by pr-str only-ours)] (println "  only here    " (pr-str r)))
    (doseq [r (sort-by pr-str only-theirs)] (println "  only report  " (pr-str r)))
    (shutdown-agents)
    (System/exit (if (and (empty? only-ours) (empty? only-theirs) (empty? refused)) 0 1))))

(ns urpx.schedule
  "Generate contiguous price-interval schedules from a coerced URPX rate plan.

  `price-schedule` walks a [start, end) instant window in the rate plan's
  local timezone, calls `urpx.price/resolve-prices` at each step, and
  merges adjacent steps whose ledger resolutions are identical.

  Output is a vector of interval maps each carrying `:tick/beginning` and
  `:tick/end` (java.time.ZonedDateTime in the resolved zone — DST-correct
  end-to-end) plus the `urpx.price` resolved structure under
  `:urpx.interval/resolved`, keeping a season or TOU label only when it holds
  for the whole interval. Callers that want plain `java.time.Instant`
  values invoke `.toInstant` themselves at the call site.

  Default step is 1 hour, anchored to the start-of-hour of `start`, so interior
  boundaries fall on clock hours. Intervals are CLIPPED to the requested
  window: the first begins at `start` and the last ends at `end`, however the
  grid falls. Pass `{:step (Duration/ofMinutes 15)}` etc. for finer granularity
  when a rate plan defines sub-hour TOU bracket transitions."
  (:require [urpx.coerce :as coerce]
            [urpx.price :as price]
            [urpx.schema :as schema])
  (:import [java.time Duration Instant LocalDate LocalDateTime LocalTime ZonedDateTime ZoneId]
           [java.time.temporal ChronoUnit]
           [java.time.zone ZoneRules]))

(defn- resolve-zone
  "THE ZONE THE WALK HAPPENS IN, which is the plan's own and not the caller's.

   It used to be the explicit `zone` if given, falling back to the plan's. That
   was coherent only while resolution was a pure function of the caller's wall
   clock. It no longer is: urpx.price anchors every query in the plan's declared
   zone, because that is the frame the document's bounds are written in.

   So a walk in any other zone derives its candidate boundaries in one frame
   while resolution happens in another, and the candidate set stops being a
   superset of true transitions. Measured on PG&E E-TOU-C over one July day: an
   explicit UTC walk put the peak boundary at 17:00 PT instead of 16:00 and lost
   the 21:00 transition entirely, returning two segments where three are right.

   An explicit `zone` that AGREES is accepted, so existing callers passing the
   plan's own zone are unaffected. One that disagrees refuses rather than
   silently returning the wrong boundaries."
  ^ZoneId [rate-plan ^ZoneId zone]
  (let [declared (price/require-zone rate-plan)]
    (when (and zone (not= zone declared))
      (throw (ex-info (str "Cannot walk " (pr-str (:jsonld/id rate-plan)) " in " zone
                           ": it declares urpx:timezoneIdentifier " declared
                           ", and urpx.price resolves every query in that zone. "
                           "Walking in another derives boundaries in one frame "
                           "while resolution happens in the other, which drops "
                           "true transitions rather than failing.")
                      {:reason :urpx.schedule/zone-disagrees-with-plan
                       :plan-id (:jsonld/id rate-plan)
                       :requested (str zone)
                       :declared (str declared)})))
    declared))

(defn- hour-start
  ^ZonedDateTime [^ZonedDateTime zdt]
  (.truncatedTo zdt ChronoUnit/HOURS))

(defn- step-zdts
  "Lazy seq of ZonedDateTimes at each `step` from `start` (truncated to its
   start-of-hour) up to but not including `end`."
  [^Instant start ^Instant end ^ZoneId zone ^Duration step]
  (let [first-zdt (hour-start (.atZone start zone))
        end-zdt   (.atZone end zone)]
    (->> (iterate #(.plus ^ZonedDateTime % step) first-zdt)
         (take-while #(.isBefore ^ZonedDateTime % end-zdt)))))

(def ^:private ledger-labels
  "Per-step labels a modifier ledger carries: its own season and TOU period."
  [:urpx.resolved/season-name :urpx.resolved/tou-period-name
   :urpx.resolved/tou-period-number])

(defn- unlabeled-ledgers
  [resolved]
  (mapv #(apply dissoc % ledger-labels) (:urpx.resolved/ledgers resolved)))

(defn- same-ledgers?
  "True when two resolved values price the same way, so the steps that produced
   them may merge into one interval.

   Ledgers are the prices, and they are most of the answer. But
   :urpx.resolved/outside-version-window sits on the RESULT for a base plan, not
   on any ledger, and it is the difference between a rate and a refusal. Reading
   ledgers alone merged straight across a version boundary and reported one
   side's answer for the whole span, in both directions: the unmodified
   sce-d-2026-03-20 fixture over March 2026 collapsed to ONE interval carrying
   the not-yet-effective mark, so marginal-unit-rate refused for the twelve days
   the filing does cover, while price-segments and a direct resolve both
   answered 0.31085. A version expiring mid-window failed the other way, keeping
   the covered side's rate for the half-month after it expired.

   price-segments never had this because it merges on the whole resolved value.
   The two APIs are supposed to agree about the same instant."
  [a b]
  (and (= (unlabeled-ledgers a) (unlabeled-ledgers b))
       (= (:urpx.resolved/outside-version-window a)
          (:urpx.resolved/outside-version-window b))))

(defn- agreeing
  "Resolved value `a` keeping only the top-level entries `b` carries with an
   equal value.

   Steps merge on what same-ledgers? compares, so those entries always survive.
   The rest are per-step labels: season, TOU period name and number. Keeping the
   earlier step's labels reported one season or period for a span that crossed
   into another; omitting a label that differs leaves the interval saying only
   what is true for all of it. A modifier ledger's own labels follow the same
   rule, ledger by ledger."
  [a b]
  (let [agree (fn [x y ks]
                (reduce (fn [m k]
                          (if (and (contains? y k) (= (get x k) (get y k))) m (dissoc m k)))
                        x ks))]
    (-> (agree a b (keys (dissoc a :urpx.resolved/ledgers)))
        (assoc :urpx.resolved/ledgers
               (mapv #(agree %1 %2 ledger-labels)
                     (:urpx.resolved/ledgers a) (:urpx.resolved/ledgers b))))))

(defn- merge-adjacent
  "Merge consecutive intervals whose resolved ledger structure matches.
   The merged interval keeps the earlier `:tick/beginning` and the later
   `:tick/end`; its resolved value keeps the entries every merged step agrees
   on."
  [intervals]
  (reduce
   (fn [acc iv]
     (let [prev (peek acc)
           a    (:urpx.interval/resolved prev)
           b    (:urpx.interval/resolved iv)]
       (if (and prev (same-ledgers? a b))
         (conj (pop acc) (assoc prev
                                :tick/end (:tick/end iv)
                                :urpx.interval/resolved (agreeing a b)))
         (conj acc iv))))
   []
   intervals))

(defn- schedule-with-resolver
  "Walk [start, end) at `step`, building one interval per step via
   `resolve-fn` (a `(ZonedDateTime -> resolved)` callback), then merge
   adjacent intervals whose ledger resolution matches. Used by both
   `price-schedule` (per-step `resolve-prices`) and
   `price-schedule-with-modifiers` (per-step `resolve-prices-with-modifiers`).

   `:tick/beginning` and `:tick/end` are emitted as `ZonedDateTime` —
   matches `urpx.schedule`'s end-to-end ZonedDateTime pipeline (DST-correct
   under daylight transitions because the wall-clock walk preserves zone).
   Callers that want plain `Instant` call `.toInstant` themselves."
  [rate-plan ^Instant start ^Instant end zone opts resolve-fn]
  (let [zone    (resolve-zone rate-plan zone)
        step    (or (:step opts) (Duration/ofHours 1))
        win-beg (.atZone start zone)
        win-end (.atZone end zone)
        steps   (step-zdts start end zone step)
        intervals (mapv (fn [^ZonedDateTime zdt]
                          ;; Clipped to the requested window. The step grid is
                          ;; anchored to the start of `start`'s hour so interior
                          ;; boundaries stay on clock hours, but that anchoring
                          ;; used to LEAK: a window beginning at 17:23 reported
                          ;; an interval beginning at 17:00, and the final
                          ;; interval ran past `end` by up to one step. Both
                          ;; attributed prices to instants the caller did not
                          ;; ask about, on either side of their window.
                          {:tick/beginning         (if (.isBefore zdt win-beg) win-beg zdt)
                           :tick/end               (let [e (.plus zdt step)]
                                                     (if (.isAfter e win-end) win-end e))
                           :urpx.interval/resolved (resolve-fn zdt)})
                        steps)]
    (merge-adjacent intervals)))

(defn price-schedule
  "Generate a vector of merged price intervals across `[start, end)`.

   Each entry is:
     {:tick/beginning           ZonedDateTime
      :tick/end                 ZonedDateTime
      :urpx.interval/resolved   <price/resolve-prices result>}

   `:tick/*` are ZonedDateTimes in the resolved zone (callers wanting
   plain Instants invoke `.toInstant` themselves). Adjacent steps with
   identical ledger resolution are merged.

   A merged interval carries `:urpx.resolved/season-name`,
   `:urpx.resolved/tou-period-name` and `:urpx.resolved/tou-period-number`
   only when that value holds for every step merged into it; a label that
   changes inside the interval is omitted. The same holds for those keys on a
   modifier ledger, which name the modifier's own season and period.
   `price-segments` splits on those labels instead, so use it when they
   matter.

   `zone` (a java.time.ZoneId) is optional when `rate-plan` declares
   `urpx:timezoneIdentifier`; pass `nil` to use the plan's declared
   timezone. An ex-info is thrown when neither is available.

   Optional `opts` map:
     :step      java.time.Duration — granularity at which the window is
                walked. Default: `(Duration/ofHours 1)`. Use a smaller
                step for plans with sub-hour TOU bracket transitions.
     :holiday?  predicate `(LocalDate -> bool)` passed through to
                `urpx.price/resolve-prices`."
  ([rate-plan start end] (price-schedule rate-plan start end nil nil))
  ([rate-plan start end zone] (price-schedule rate-plan start end zone nil))
  ([rate-plan ^Instant start ^Instant end zone opts]
   (schedule-with-resolver rate-plan start end zone opts
                           (fn [zdt] (price/resolve-prices rate-plan zdt opts)))))

(defn price-schedule-days
  "Convenience: generate a `price-schedule` covering `days` days starting from
   `start`. Accepts the same `opts` as `price-schedule` and the same
   plan-or-explicit zone resolution."
  ([rate-plan start days] (price-schedule-days rate-plan start days nil nil))
  ([rate-plan start days zone] (price-schedule-days rate-plan start days zone nil))
  ([rate-plan ^Instant start days zone opts]
   (price-schedule rate-plan start (.plus start (Duration/ofDays days)) zone opts)))

(defn price-schedule-with-modifiers
  "Like `price-schedule`, but per step calls
   `urpx.price/resolve-prices-with-modifiers` so each interval's
   `:urpx.interval/resolved` includes the modifier ledgers appended to the
   base plan's. Modifiers whose `:urpx/applicableToRatePlan` does not
   include the base plan's `@id` are silently skipped (delegated behavior).

   Same merge-adjacent / step / zone-resolution semantics as
   `price-schedule`. Same `opts` (`:step`, `:holiday?`, plus any other keys
   that `resolve-prices-with-modifiers` consumes, such as
   `:profile-alternatives`)."
  ([rate-plan modifiers start end]
   (price-schedule-with-modifiers rate-plan modifiers start end nil nil))
  ([rate-plan modifiers start end zone]
   (price-schedule-with-modifiers rate-plan modifiers start end zone nil))
  ([rate-plan modifiers ^Instant start ^Instant end zone opts]
   (schedule-with-resolver
    rate-plan start end zone opts
    (fn [zdt] (price/resolve-prices-with-modifiers rate-plan modifiers zdt opts)))))

(defn price-schedule-days-with-modifiers
  "Convenience: generate a `price-schedule-with-modifiers` covering `days`
   days starting from `start`. Same `opts` and zone resolution as
   `price-schedule-with-modifiers`."
  ([rate-plan modifiers start days]
   (price-schedule-days-with-modifiers rate-plan modifiers start days nil nil))
  ([rate-plan modifiers start days zone]
   (price-schedule-days-with-modifiers rate-plan modifiers start days zone nil))
  ([rate-plan modifiers ^Instant start days zone opts]
   (price-schedule-with-modifiers
    rate-plan modifiers start (.plus start (Duration/ofDays days)) zone opts)))

;; ---------------------------------------------------------------------------
;; Exact segments
;;
;; `price-schedule` above samples a fixed grid and merges adjacent equal
;; results, so its boundaries are snapped to the grid. That is right only when
;; every real transition happens to land on it. This section derives the
;; transition instants from the documents instead, so boundaries are exact by
;; construction.
;;
;; THE SAFETY PROPERTY. The candidate set must be a SUPERSET of true
;; transitions. An extra candidate is harmless: it resolves the same as its
;; neighbour and merges away. A MISSED candidate is a silently wrong boundary.
;; The superset argument rests on one checkable fact: `urpx.price` reads the
;; query instant through exactly four projections, `.toLocalDateTime`,
;; `.toLocalDate`, `.toLocalTime` and `.getDayOfWeek`, and never through
;; `.toInstant`, `.getOffset` or `.getZone`. Resolution is therefore a pure
;; function of the LOCAL date-time, so it can only change where some comparison
;; against a literal read out of a document changes. `urpx.schedule-test` pins
;; that projection set, because a future resolver reading the offset would
;; invalidate everything here without touching this file.
;; ---------------------------------------------------------------------------

(defn- harvest-literals
  "Walk a COERCED document and collect every temporal literal by java.time TYPE,
   as `urpx.coerce/temporal-signature` groups them.

   The walk and the uncoerced-document test both live in `urpx.coerce` now, so
   this namespace and `urpx.scenario-cost-model` cannot drift about what a
   coerced document is. Throws on a document that was never coerced: raw
   JSON-LD leaves these as strings, which would harvest nothing and yield a
   candidate set narrowed to the midnight grid, quietly."
  [doc]
  (let [sig (coerce/temporal-signature doc)]
    (when (coerce/uncoerced? doc)
      (throw (ex-info (str "price-segments needs a COERCED document: it carries "
                           (:date-shaped-strings sig) " date-shaped string(s) and no "
                           "java.time values at all, which is what an uncoerced "
                           "document looks like. Run it through urpx.coerce first; "
                           "harvesting an uncoerced document silently narrows the "
                           "candidate set to midnights.")
                      {:reason :uncoerced-document
                       :date-shaped-strings (:date-shaped-strings sig)})))
    (select-keys sig [:times :dates :date-times])))

(defn- local->instants
  "Every instant the local date-time `ldt` corresponds to in `zone`.

   Three cases, and each is a DST correctness decision:
     normal   one offset, one instant.
     GAP      spring-forward skipped this local time, so it never occurs.
              Clamp to the transition instant. `ZonedDateTime/of` would push
              forward by the gap's width instead, leaving the preceding bracket
              applying past its wall-clock end, and the width is not always an
              hour.
     OVERLAP  fall-back repeats this local time, so it occurs TWICE. Emit both.
              Resolution is a pure function of the local date-time, so the
              repeated hour replays the same local sequence on the instant line;
              emitting one instant would merge the repeat away and misprice the
              second pass."
  [^ZoneRules rules ^LocalDateTime ldt]
  (let [offs (.getValidOffsets rules ldt)]
    (case (count offs)
      0 (when-let [tr (.getTransition rules ldt)] [(.getInstant tr)])
      (mapv #(.toInstant ldt %) offs))))

(defn- zone-transitions
  "Every DST transition instant in [start, end).

   Iterated from the zone rules rather than computed as two dates a year: real
   tzdb data does not oblige. America/Metlakatla has a spring gap in 2018 with
   no matching fall-back and three transitions in 2019, one of them in January.
   This is also the only instant-line discontinuity with no local-time
   counterpart, being exactly where the earlier bracket resumes at the start of
   a repeated hour."
  [^ZoneRules rules ^Instant start ^Instant end]
  (loop [i start acc []]
    (if-let [tr (.nextTransition rules i)]
      (let [t (.getInstant tr)]
        (if (.isBefore t end) (recur t (conj acc t)) acc))
      acc)))

(defn- candidate-instants
  "Every instant in [start, end) at which the resolved prices could change.

   A superset by construction: every local midnight in the window is included
   unconditionally, which absorbs every date-granularity gate at once - day of
   week, holidays including observance shifting, month and season boundaries,
   applicability month rules, and every startDate, effectiveDate and
   expirationDate literal - with no holiday parsing, no month arithmetic and no
   per-property list to keep in step with the vocabulary."
  [docs ^Instant start ^Instant end ^ZoneId zone]
  (let [rules (.getRules zone)
        {:keys [times dates date-times]} (reduce (fn [a d]
                                                   (merge-with into a (harvest-literals d)))
                                                 {:times #{} :dates #{} :date-times #{}}
                                                 docs)
        times  (conj times LocalTime/MIDNIGHT)
        d0     (.toLocalDate (.atZone start zone))
        dN     (.toLocalDate (.atZone end zone))
        ;; LocalDate.plusDays, never ZonedDateTime.plusDays: the latter is not
        ;; round-trip safe through a gap and retains the source offset in an
        ;; overlap, which makes the walk direction-dependent.
        days   (take-while #(not (.isAfter ^LocalDate % dN))
                           (iterate #(.plusDays ^LocalDate % 1) d0))
        ;; A literal with an offset becomes the wall clock it reads in `zone`,
        ;; read the way urpx.price reads it, so its boundary is a candidate.
        locals (concat (for [d days, t times]
                         (.atTime ^LocalDate d (schema/wall-time t d zone)))
                       (map #(if (instance? ZonedDateTime %)
                               (.toLocalDateTime (.withZoneSameInstant ^ZonedDateTime % zone))
                               %)
                            date-times)
                       (map #(.atStartOfDay ^LocalDate %) dates))]
    (->> (concat (mapcat #(local->instants rules %) locals)
                 (zone-transitions rules start end)
                 [start])
         (remove nil?)
         (filter #(and (not (.isBefore ^Instant % start)) (.isBefore ^Instant % end)))
         distinct
         sort
         vec)))

(defn- refusal-key
  "The part of a refusal that identifies WHICH refusal it is, with the
   time-varying detail stripped.

   A refusal's ex-data names the instant it was raised at, and its message
   embeds it. Merging on the raw map therefore never merges: an uncovered
   stretch fragments into one segment per candidate, which reports a boundary
   at every midnight where there is really only one. Temporal values and the
   message are dropped so equal refusals collapse, while the retained keys
   still separate genuinely different ones."
  [refused]
  (when refused
    (into {} (remove (fn [[k v]]
                       (or (= :message k)
                           (instance? java.time.temporal.Temporal v)
                           (and (sequential? v)
                                (some #(instance? java.time.temporal.Temporal %) v))))
                     refused))))

(defn price-segments
  "A piecewise-constant price schedule across `[start, end)`, with EXACT
   boundaries derived from the documents rather than discovered by sampling.

   Each entry is:
     {:tick/beginning         ZonedDateTime
      :tick/end               ZonedDateTime
      :urpx.interval/resolved <full price/resolve-prices result>}

   the same shape `price-schedule` emits, except that every entry carries its
   labels, so the two are interchangeable at a call site that reads prices.
   The difference is where the boundaries fall: `price-schedule`
   snaps them to its step grid, this puts them where the tariff actually
   changes. For a client building a horizon and handing segments to an
   optimizer, that is the difference between a schedule that is right and one
   that is right only because every bracket in that plan happens to sit on an
   hour.

   Segments are merged on the FULL resolved value, not only on its ledgers: a
   change of TOU period name or season with identical prices is still a real
   boundary, and merging it away would leave the earlier value misstating the
   later part.

   Takes `modifiers` so the composed result is segmented as one thing; pass an
   empty collection for a base plan alone. Every document passed here is
   harvested for candidate instants, so a version snapshot the caller did not
   load cannot contribute a boundary; that is a property of what was supplied
   rather than a limit of this function.

   A resolution that THROWS - no version in effect at that instant, an
   ambiguous version - becomes a segment carrying `:urpx.interval/refused`
   with the ex-data, rather than aborting the window. Equal refusals merge, so
   an uncovered stretch is one segment and not one per candidate.

   Optional `opts` are passed through to the resolver, and `:holiday?` is
   worth supplying when the plan has no embedded calendar."
  ([rate-plan modifiers start end] (price-segments rate-plan modifiers start end nil nil))
  ([rate-plan modifiers start end zone] (price-segments rate-plan modifiers start end zone nil))
  ([rate-plan modifiers ^Instant start ^Instant end zone opts]
   (let [zone    (resolve-zone rate-plan zone)
         docs    (cons rate-plan (seq modifiers))
         cands   (candidate-instants docs start end zone)
         win-end (.atZone end zone)
         ends    (concat (rest cands) [end])
         segs    (mapv (fn [^Instant a ^Instant b]
                         (let [zdt (.atZone a zone)]
                           (merge {:tick/beginning zdt
                                   :tick/end       (.atZone ^Instant b zone)}
                                  (try {:urpx.interval/resolved
                                        (if (seq modifiers)
                                          (price/resolve-prices-with-modifiers rate-plan modifiers zdt opts)
                                          (price/resolve-prices rate-plan zdt opts))}
                                       (catch clojure.lang.ExceptionInfo e
                                         {:urpx.interval/refused (assoc (ex-data e) :message (.getMessage e))})))))
                       cands ends)]
     (->> segs
          (reduce (fn [acc iv]
                    (let [prev (peek acc)]
                      (if (and prev
                               (= (:urpx.interval/resolved prev) (:urpx.interval/resolved iv))
                               (= (refusal-key (:urpx.interval/refused prev))
                                  (refusal-key (:urpx.interval/refused iv))))
                        (conj (pop acc) (assoc prev :tick/end (:tick/end iv)))
                        (conj acc iv))))
                  [])
          (mapv #(cond-> % (.isAfter ^ZonedDateTime (:tick/end %) win-end)
                         (assoc :tick/end win-end)))))))

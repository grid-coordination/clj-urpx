(ns urpx.schedule
  "Generate contiguous price-interval schedules from a coerced URPX rate plan.

  `price-schedule` walks a [start, end) instant window in the rate plan's
  local timezone, calls `urpx.price/resolve-prices` at each step, and
  merges adjacent steps whose ledger resolutions are identical.

  Output is a vector of interval maps each carrying `:tick/beginning` and
  `:tick/end` (java.time.ZonedDateTime in the resolved zone — DST-correct
  end-to-end) plus the full `urpx.price` resolved structure under
  `:urpx.interval/resolved`. Callers that want plain `java.time.Instant`
  values invoke `.toInstant` themselves at the call site.

  Default step is 1 hour, anchored to the start-of-hour of `start`, so interior
  boundaries fall on clock hours. Intervals are CLIPPED to the requested
  window: the first begins at `start` and the last ends at `end`, however the
  grid falls. Pass `{:step (Duration/ofMinutes 15)}` etc. for finer granularity
  when a rate plan defines sub-hour TOU bracket transitions."
  (:require [urpx.price :as price])
  (:import [java.time Duration Instant ZonedDateTime ZoneId]
           [java.time.temporal ChronoUnit]))

(defn- plan-zone
  "Return the java.time.ZoneId declared by the rate plan's
   urpx:timezoneIdentifier (an IANA zone string, e.g. \"America/Los_Angeles\"),
   or nil when the plan does not declare one."
  ^ZoneId [rate-plan]
  (when-let [tz (:urpx/timezoneIdentifier rate-plan)]
    (ZoneId/of tz)))

(defn- resolve-zone
  "Pick a zone for `rate-plan`: the explicit `zone` if non-nil, else the plan's
   declared timezone. Throws when neither is available."
  ^ZoneId [rate-plan ^ZoneId zone]
  (or zone (plan-zone rate-plan)
      (throw (ex-info "Cannot determine timezone: no :zone arg and rate plan has no urpx:timezoneIdentifier"
                      {:plan-id (:jsonld/id rate-plan)}))))

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

(defn- same-ledgers?
  [a b]
  (= (:urpx.resolved/ledgers a) (:urpx.resolved/ledgers b)))

(defn- merge-adjacent
  "Merge consecutive intervals whose resolved ledger structure matches.
   The merged interval keeps the earlier `:tick/beginning` and the later
   `:tick/end`; the embedded resolved value comes from the earlier step."
  [intervals]
  (reduce
   (fn [acc iv]
     (let [prev (peek acc)]
       (if (and prev (same-ledgers? (:urpx.interval/resolved prev)
                                    (:urpx.interval/resolved iv)))
         (conj (pop acc) (assoc prev :tick/end (:tick/end iv)))
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
      :urpx.interval/resolved   <full price/resolve-prices result>}

   `:tick/*` are ZonedDateTimes in the resolved zone (callers wanting
   plain Instants invoke `.toInstant` themselves). Adjacent steps with
   identical ledger resolution are merged.

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
   that `resolve-prices-with-modifiers` consumes — e.g. URPX-vlf's
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

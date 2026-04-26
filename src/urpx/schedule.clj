(ns urpx.schedule
  "Generate contiguous price-interval schedules from a coerced URPX rate plan.

  `price-schedule` walks a [start, end) instant window in the rate plan's
  local timezone, calls `urpx.price/resolve-prices` at each step, and
  merges adjacent steps whose ledger resolutions are identical.

  Output is a vector of interval maps each carrying `:tick/beginning` and
  `:tick/end` (java.time.Instants) plus the full `urpx.price` resolved
  structure under `:urpx.interval/resolved`. Suitable for feeding to a
  consumer like the price-server's TariffFetcher, which can extract a
  single $/kWh value per interval via `urpx.price/marginal-unit-rate`.

  Default step is 1 hour, anchored to the start-of-hour of `start`. Pass
  `{:step (Duration/ofMinutes 15)}` etc. for finer granularity when a rate
  plan defines sub-hour TOU bracket transitions."
  (:require [urpx.price :as price])
  (:import [java.time Duration Instant ZonedDateTime ZoneId]
           [java.time.temporal ChronoUnit]))

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

(defn price-schedule
  "Generate a vector of merged price intervals across `[start, end)` in `zone`.

   Each entry is:
     {:tick/beginning           Instant
      :tick/end                 Instant
      :urpx.interval/resolved   <full price/resolve-prices result>}

   Adjacent steps with identical ledger resolution are merged.

   Optional `opts` map:
     :step      java.time.Duration — granularity at which the window is
                walked. Default: `(Duration/ofHours 1)`. Use a smaller
                step for plans with sub-hour TOU bracket transitions.
     :holiday?  predicate `(LocalDate -> bool)` passed through to
                `urpx.price/resolve-prices` (matches urpx:holiday brackets)."
  ([rate-plan start end zone] (price-schedule rate-plan start end zone nil))
  ([rate-plan ^Instant start ^Instant end ^ZoneId zone opts]
   (let [step  (or (:step opts) (Duration/ofHours 1))
         steps (step-zdts start end zone step)
         hourly (mapv (fn [^ZonedDateTime zdt]
                        {:tick/beginning         (.toInstant zdt)
                         :tick/end               (.toInstant (.plus zdt step))
                         :urpx.interval/resolved (price/resolve-prices rate-plan zdt opts)})
                      steps)]
     (merge-adjacent hourly))))

(defn price-schedule-days
  "Convenience: generate a `price-schedule` covering `days` days starting from
   `start`. Accepts the same `opts` as `price-schedule`."
  ([rate-plan start days zone] (price-schedule-days rate-plan start days zone nil))
  ([rate-plan ^Instant start days ^ZoneId zone opts]
   (price-schedule rate-plan start (.plus start (Duration/ofDays days)) zone opts)))

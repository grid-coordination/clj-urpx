(ns urpx.schedule
  "Generate contiguous price-interval schedules from a coerced URPX rate plan.

  `price-schedule` walks a [start, end) instant window in the rate plan's
  local timezone, hour by hour, calls `urpx.price/resolve-prices` at each
  step, and merges adjacent steps whose ledger resolutions are identical.

  Output is a vector of interval maps each carrying `:tick/beginning` and
  `:tick/end` (java.time.Instants) plus the full `urpx.price` resolved
  structure under `:urpx.interval/resolved`. Suitable for feeding to a
  consumer like the price-server's TariffFetcher, which can extract a
  single $/kWh value per interval via `urpx.price/marginal-unit-rate`.

  Granularity is hourly. URPX rate plans seen so far align TOU bracket
  transitions to whole hours; finer-grained transitions would need a
  smaller step (not yet exposed)."
  (:require [urpx.price :as price])
  (:import [java.time Duration Instant ZonedDateTime ZoneId]
           [java.time.temporal ChronoUnit]))

(defn- hour-start
  ^ZonedDateTime [^ZonedDateTime zdt]
  (.truncatedTo zdt ChronoUnit/HOURS))

(defn- hour-zdts
  "Lazy seq of ZonedDateTimes at the start of each hour from `start` (inclusive,
   truncated to the hour) up to but not including `end`."
  [^Instant start ^Instant end ^ZoneId zone]
  (let [first-hour (hour-start (.atZone start zone))
        end-zdt    (.atZone end zone)]
    (->> (iterate #(.plusHours ^ZonedDateTime % 1) first-hour)
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

   Adjacent hours with identical ledger resolution are merged."
  [rate-plan ^Instant start ^Instant end ^ZoneId zone]
  (let [hours (hour-zdts start end zone)
        hourly (mapv (fn [^ZonedDateTime zdt]
                       {:tick/beginning         (.toInstant zdt)
                        :tick/end               (.toInstant (.plusHours zdt 1))
                        :urpx.interval/resolved (price/resolve-prices rate-plan zdt)})
                     hours)]
    (merge-adjacent hourly)))

(defn price-schedule-days
  "Convenience: generate a `price-schedule` covering `days` days starting from
   `start`."
  [rate-plan ^Instant start days ^ZoneId zone]
  (price-schedule rate-plan start (.plus start (Duration/ofDays days)) zone))

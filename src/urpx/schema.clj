(ns urpx.schema
  "Malli schemas for URPX entities, describing the coerced shape.

  Tracks the ontology snapshot the fixtures pin via urpx:targetURPXVersion
  (v0.5.1). scripts/shacl-drift.sh reports where these have fallen behind the
  upstream shapes.

  Two-layer model (mirroring the clj-oa3 pattern):
    - urpx.core      — raw parse: JSON-LD with namespaced keys, all string values.
    - urpx.schema    — coerced shape: typed Clojure values (BigDecimal, LocalDate,
                       LocalTime, LocalDateTime, ZonedDateTime, OffsetTime,
                       Duration, integer month numbers).

  Schemas carry `:decode/urpx-jsonld` decoders that turn the raw parser output
  (strings) into the typed shape described here. The transformer is built and
  applied in `urpx.coerce`. Reference resolution keeps :jsonld/id references
  intact; consumers dereference via an entity index.

  Schemas are bare def vars referenced by symbol. `schemas` and `registry`
  name every entity schema by its @type, and `schema-for-type` looks one up.
  A property the standard types with sh:class states that class as its entry
  ::range; `mistyped-references` reports the references and inline nodes
  outside it. The registry local to the recursive operand tree of
  ComparisonNode, BooleanOperationNode and ConditionExpression is separate."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.registry :as mr])
  (:import [java.math BigDecimal]
           [java.time DateTimeException Duration LocalDate LocalDateTime LocalTime
            OffsetDateTime OffsetTime Period Year ZoneId ZoneOffset ZonedDateTime]
           [java.time.format DateTimeFormatter DateTimeFormatterBuilder]
           [java.time.temporal ChronoField]))

;; ---------------------------------------------------------------------------
;; Decoder helpers (consumed by `:decode/urpx-jsonld` properties below)
;; ---------------------------------------------------------------------------

(def ^:private xsd-decimal-lexical
  "The xsd:decimal lexical space, XSD 1.1 Part 2 section 3.3.3."
  #"[+-]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)")

(def ^:private xsd-integer-lexical
  "The xsd:integer lexical space, XSD 1.1 Part 2."
  #"[+-]?[0-9]+")

(defn- typed-literal-value
  "The @value of `v` when `v` is a JSON-LD value object whose @type is
   `datatype`, as a CURIE or the full IRI, and that has no @language, else `v`."
  [v datatype]
  (if (and (map? v)
           (contains? v :jsonld/value)
           (not (contains? v :jsonld/language))
           (contains? #{datatype (str "http://www.w3.org/2001/XMLSchema#" (subs datatype 4))}
                      (:jsonld/type v)))
    (:jsonld/value v)
    v))

(defn- malformed-decimal
  "The ex-info refusing `v` as an xsd:decimal."
  [v]
  (ex-info (str "Malformed xsd:decimal " (pr-str v)
                ": not an optional sign and digits with an optional fraction")
           {:reason :urpx.schema/malformed-decimal
            :datatype "xsd:decimal"
            :value v}))

(def ^:private double-threshold
  "The magnitude from which the JSON-LD 1.1 API's object to RDF conversion
   writes a JSON number as an xsd:double."
  (BigDecimal. "1E21"))

(defn- double-not-decimal
  "The ex-info refusing number `v`, which JSON-LD reads as an xsd:double,
   suggesting the string of `suggested`."
  [v ^BigDecimal suggested]
  (ex-info (str "Number " v " at an xsd:decimal is an xsd:double in JSON-LD;"
                " write the value as a string, such as \"" (.toPlainString suggested) "\"")
           {:reason :urpx.schema/double-not-decimal
            :datatype "xsd:decimal"
            :value v}))

(defn- json-number->decimal
  "Integer or finite floating-point `v` as a BigDecimal, as the JSON-LD 1.1
   API's object to RDF conversion reads a JSON number: one with a non-zero
   fractional part, or of magnitude 1E21 or more, is an xsd:double and throws
   `double-not-decimal`; any other is an integer."
  ^BigDecimal [v]
  (let [floating? (or (double? v) (float? v))
        exact     (if floating? (BigDecimal. (double v)) (bigdec v))]
    (if (or (not (zero? (.signum (.remainder exact BigDecimal/ONE))))
            (>= (.compareTo (.abs exact) ^BigDecimal double-threshold) 0))
      (throw (double-not-decimal v (if floating?
                                     (.stripTrailingZeros (BigDecimal. (str v)))
                                     exact)))
      (.setScale exact 0))))

(defn- ->decimal
  "Coerce raw input forms to BigDecimal:
   - BigDecimal: pass-through
   - integer, or finite double or float, as the JSON number it would be:
     an integral value of magnitude below 1E21 decodes with scale 0 (3.0 as
     3M); any other throws ex-info with reason `:urpx.schema/double-not-decimal`
     and the value, since JSON-LD reads it as an xsd:double
   - terminating ratio: its decimal expansion
   - string in the xsd:decimal lexical space: parse it
   - any other string (an exponent, NaN, INF, whitespace), or a NaN or
     infinite double or float: throw ex-info with reason
     `:urpx.schema/malformed-decimal` and the value
   - a ratio with no terminating decimal expansion, such as 1/3: throw
     ex-info with reason `:urpx.schema/non-terminating-ratio` and the value
   - a JSON-LD value object typed xsd:decimal: its @value, as above
   - anything else: pass-through (let validation surface the type mismatch)

   `urpx.dsl` builders refuse every double before this runs, since a double
   has already lost the written scale."
  [v]
  (let [v (typed-literal-value v "xsd:decimal")]
    (cond
      (instance? BigDecimal v) v
      (ratio? v)               (try
                                 (bigdec v)
                                 (catch ArithmeticException e
                                   (throw (ex-info (str "Ratio " v " is not an xsd:decimal: it has"
                                                        " no terminating decimal expansion")
                                                   {:reason :urpx.schema/non-terminating-ratio
                                                    :datatype "xsd:decimal"
                                                    :value v}
                                                   e))))
      (number? v)              (if (and (or (double? v) (float? v))
                                        (not (Double/isFinite (double v))))
                                 (throw (malformed-decimal v))
                                 (json-number->decimal v))
      (string? v)              (if (re-matches xsd-decimal-lexical v)
                                 (BigDecimal. ^String v)
                                 (throw (malformed-decimal v)))
      :else                    v)))

;; ---------------------------------------------------------------------------
;; Encoder helpers (consumed by `:encode/urpx-jsonld` properties below)
;; ---------------------------------------------------------------------------
;; Symmetric to the ->X decoders above. Used by the write-side path
;; (urpx.emit) to turn typed values back into JSON-LD strings.
;; Each helper passes through already-string values unchanged, mirroring
;; the way decoders pass through already-typed values.

(defn- <-decimal
  "BigDecimal → plain-string form, preserving scale so a round-trip yields a
   BigDecimal equal to the original. `.toPlainString` avoids scientific
   notation that JSON-LD parsers wouldn't expect for xsd:decimal. A ratio
   `->decimal` reads becomes the plain string of its decimal expansion, since
   JSON would write it as a double. Any other number passes through when
   `->decimal` would read it back, and throws as `->decimal` does when it
   would not. A value object typed xsd:decimal has its @value encoded so."
  [v]
  (cond
    (instance? BigDecimal v) (.toPlainString ^BigDecimal v)
    (ratio? v)               (.toPlainString ^BigDecimal (->decimal v))
    (number? v)              (do (->decimal v) v)
    (not= v (typed-literal-value v "xsd:decimal")) (update v :jsonld/value <-decimal)
    :else                    v))

;; xsd:time and xsd:dateTime require seconds. java.time's toString omits them
;; when they are zero, so these formatters spell them out, adding a fraction
;; only when there is one. An offset is written as Z for UTC.
(def ^:private ^DateTimeFormatter xsd-time-fmt
  (-> (DateTimeFormatterBuilder.)
      (.appendPattern "HH:mm:ss")
      (.appendFraction ChronoField/NANO_OF_SECOND 0 9 true)
      (.toFormatter)))

(def ^:private ^DateTimeFormatter xsd-datetime-fmt
  (-> (DateTimeFormatterBuilder.)
      (.append DateTimeFormatter/ISO_LOCAL_DATE)
      (.appendLiteral \T)
      (.append xsd-time-fmt)
      (.toFormatter)))

(defn- with-offset ^DateTimeFormatter [^DateTimeFormatter f]
  (-> (DateTimeFormatterBuilder.) (.append f) (.appendOffset "+HH:MM" "Z") (.toFormatter)))

(def ^:private xsd-time-offset-fmt     (with-offset xsd-time-fmt))
(def ^:private xsd-datetime-offset-fmt (with-offset xsd-datetime-fmt))

(defn- <-date [v] (if (instance? LocalDate v) (.toString ^LocalDate v) v))

(defn- <-datetime [v]
  (cond
    (instance? LocalDateTime v) (.format xsd-datetime-fmt v)
    (instance? ZonedDateTime v) (.format ^DateTimeFormatter xsd-datetime-offset-fmt v)
    :else v))

(defn- <-time [v]
  (cond
    (instance? LocalTime v)  (.format xsd-time-fmt v)
    (instance? OffsetTime v) (.format ^DateTimeFormatter xsd-time-offset-fmt v)
    :else v))

(defn- <-duration
  "Duration → ISO 8601 time-based duration (PT1H30M); Period → ISO 8601
   date-based duration (P1M, P1Y). Both java.time toString implementations
   already produce the canonical xsd:duration form."
  [v]
  (cond
    (instance? Duration v) (.toString ^Duration v)
    (instance? Period v)   (.toString ^Period v)
    :else v))

(defn- <-g-month
  "Month-of-year int → xsd:gMonth string '--MM' (zero-padded)."
  [v]
  (if (int? v) (format "--%02d" v) v))
(defn- literal-value
  "The lexical form of `v`: the @value of a JSON-LD typed-literal object
   `{\"@value\" v \"@type\" t}`, else `v`."
  [v]
  (if (and (map? v) (contains? v :jsonld/value)) (:jsonld/value v) v))

(def ^:private offset-suffix #"(Z|[+-]\d{2}:\d{2})$")

(defn- parse-temporal
  "Parse string `s` with `f`, or throw ex-info with `reason` naming the value."
  [reason datatype f ^String s]
  (try
    (f s)
    (catch DateTimeException e
      (throw (ex-info (str "Malformed " datatype " " (pr-str s) ": " (.getMessage e))
                      {:reason reason :datatype datatype :value s}
                      e)))))

(defn- ->date
  "xsd:date, as the LocalDate it names. An xsd:date is a calendar day in a
   timezone; an offset written on it names that timezone, and the day stays as
   written. `date-offsets-decoder` keeps the offset."
  [v]
  (let [v (literal-value v)]
    (if (string? v)
      (parse-temporal
       :urpx.schema/malformed-date "xsd:date"
       (fn [^String s]
         (if-let [[_ off] (re-find offset-suffix s)]
           (do (ZoneOffset/of ^String off)
               (LocalDate/parse (subs s 0 (- (count s) (count off)))))
           (LocalDate/parse s)))
       v)
      v)))

(defn- date-schema?
  "True if `schema` is `Date` or wraps it."
  [schema]
  (boolean
   (or (::xsd-date (m/properties schema))
       (case (m/type schema)
         (:maybe :or :and) (some date-schema? (m/children schema))
         :orn              (some (fn [[_ _ child]] (date-schema? child)) (m/children schema))
         (:ref :schema ::m/schema) (date-schema? (m/deref schema))
         false))))

(defn- date-keys
  "The keys of map schema `schema` whose values may be `Date`s."
  [schema]
  (into #{}
        (keep (fn [[k _ child]] (when (date-schema? child) k)))
        (m/children schema)))

(defn- offset-date
  "`{:date LocalDate :offset offset}` for xsd:date lexical `s` written with an
   offset, else nil."
  [s]
  (when-let [[_ off] (re-find offset-suffix s)]
    (try
      {:date (->date s) :offset off}
      (catch clojure.lang.ExceptionInfo _ nil))))

(def date-offsets-decoder
  "A `:map` decoder that keeps the offset written on each xsd:date the map
   holds, in the map's metadata under `:urpx.schema/date-offsets` as
   `{property {:date LocalDate :offset offset}}`, the offset exactly as
   written (\"Z\", \"-08:00\").
   A LocalDate cannot carry metadata, so the map holding it does. Map equality
   ignores metadata, so a date written with or without an offset compares
   equal."
  {:compile
   (fn [schema _]
     (let [ks (date-keys schema)]
       (when (seq ks)
         {:enter
          (fn [x]
            (if-not (map? x)
              x
              (let [written (for [k ks
                                  :let [v (literal-value (get x k))]
                                  :when (string? v)]
                              [k (offset-date v)])]
                (if (empty? written)
                  x
                  (vary-meta x (fn [md]
                                 (let [offs (reduce (fn [acc [k rec]]
                                                      (if rec (assoc acc k rec) (dissoc acc k)))
                                                    (::date-offsets md)
                                                    written)]
                                   (if (seq offs)
                                     (assoc md ::date-offsets offs)
                                     (dissoc md ::date-offsets)))))))))
          :leave
          (fn [x]
            (if-let [offs (and (map? x) (::date-offsets (meta x)))]
              (let [kept (into {}
                               (filter (fn [[k {:keys [date]}]] (= date (get x k))))
                               offs)]
                (if (= kept offs)
                  x
                  (vary-meta x (fn [md]
                                 (if (seq kept)
                                   (assoc md ::date-offsets kept)
                                   (dissoc md ::date-offsets))))))
              x))})))})

(def date-offsets-encoder
  "A `:map` encoder that writes each xsd:date back with the offset
   `date-offsets-decoder` kept for it, while the map still holds the date
   that offset was written on."
  {:compile
   (fn [schema _]
     (let [ks (date-keys schema)]
       (when (seq ks)
         {:enter
          (fn [x]
            (if-let [offs (and (map? x) (::date-offsets (meta x)))]
              (reduce-kv (fn [acc k {:keys [date offset]}]
                           (let [d (get acc k)]
                             (if (and (contains? ks k) (instance? LocalDate d) (= date d))
                               (assoc acc k (str d offset))
                               acc)))
                         x offs)
              x))})))})

(defn- ->datetime
  "xsd:dateTime. Without an offset, a LocalDateTime: a wall-clock reading in
   whatever zone the reader resolves. With one, a ZonedDateTime whose zone is
   that ZoneOffset."
  [v]
  (let [v (literal-value v)]
    (if (string? v)
      (parse-temporal
       :urpx.schema/malformed-datetime "xsd:dateTime"
       (fn [^String s]
         (if (re-find offset-suffix s)
           (.toZonedDateTime (OffsetDateTime/parse s))
           (LocalDateTime/parse s)))
       v)
      v)))

(def ^:private end-of-day #"^24:00(?::00(?:\.0+)?)?(?=$|Z|[+-])")

(defn- ->time
  "xsd:time. Without an offset, a LocalTime; with one, an OffsetTime.

   `24:00:00` (with any fraction of zeros) is the same value as `00:00:00`
   (XSD 1.1 Part 2, 3.3.8), so it decodes to midnight and encodes back as
   `00:00:00`. `24:00` decodes the same way, as `hh:mm` without seconds
   does for every other hour."
  [v]
  (let [v (literal-value v)]
    (if (string? v)
      (parse-temporal
       :urpx.schema/malformed-time "xsd:time"
       (fn [^String s]
         (let [s (str/replace-first s end-of-day "00:00:00")]
           (if (re-find offset-suffix s) (OffsetTime/parse s) (LocalTime/parse s))))
       v)
      v)))

(defn in-zone
  "`v`, a decoded xsd:date or xsd:dateTime, as a ZonedDateTime in `zone`, or
   nil for nil.

   A date reads as the start of that day in `zone`, whatever offset was written
   on it. A dateTime without an offset is a wall-clock reading in `zone`. One
   with an offset already names its instant, which is kept: it is moved to
   `zone`, not reinterpreted.

   A wall clock in a DST gap never occurs, and reads as the transition instant,
   the instant urpx.schedule's candidate rule gives it. `.atZone` pushes it
   forward by the gap's width instead, so a version taking effect inside a gap
   changed between two candidates. In an overlap, the earlier instant."
  ^ZonedDateTime [v ^ZoneId zone]
  (cond
    (nil? v)                    nil
    (instance? ZonedDateTime v) (.withZoneSameInstant ^ZonedDateTime v zone)
    (instance? LocalDateTime v) (let [rules (.getRules zone)]
                                  (if (.isEmpty (.getValidOffsets rules v))
                                    (.atZone (.getInstant (.getTransition rules v)) zone)
                                    (.atZone ^LocalDateTime v zone)))
    (instance? LocalDate v)     (.atStartOfDay ^LocalDate v zone)
    :else (throw (ex-info (str "Not a date or dateTime: " (pr-str v))
                          {:reason :urpx.schema/not-a-date-or-datetime :value v}))))

(defn wall-time
  "`t`, a decoded xsd:time, as the wall-clock time it reads in `zone` on
   `date`. A LocalTime is that already; an OffsetTime is placed on `date` at
   its offset and moved to `zone`."
  ^LocalTime [t ^LocalDate date ^ZoneId zone]
  (if (instance? OffsetTime t)
    (.toLocalTime (.atZoneSameInstant (.atDate ^OffsetTime t date) zone))
    t))
(defn- ->duration
  "Parse an ISO 8601 duration string. Time-based durations (containing 'T',
   e.g. PT1H30M) become java.time.Duration; date-based durations (e.g. P1M, P1Y)
   become java.time.Period."
  [v]
  (cond
    (not (string? v)) v
    (.contains ^String v "T") (Duration/parse v)
    :else (Period/parse v)))

(def ^:private xsd-g-month-lexical
  "The xsd:gMonth lexical space, XSD 1.1 Part 2 section 3.3.14, capturing the
   month."
  #"--(0[1-9]|1[0-2])(?:Z|[+-](?:(?:0[0-9]|1[0-3]):[0-5][0-9]|14:00))?")

(defn- ->g-month
  "An xsd:gMonth string, '--06' with an optional timezone, as its month
   number; the timezone is dropped. Any other string, or a JSON-LD value
   object typed xsd:gMonth whose @value is not such a string, throws ex-info
   with reason `:urpx.schema/malformed-g-month` and the value."
  [v]
  (let [lexical (typed-literal-value v "xsd:gMonth")]
    (if (or (string? lexical) (not= v lexical))
      (if-let [[_ mm] (and (string? lexical) (re-matches xsd-g-month-lexical lexical))]
        (Long/parseLong mm)
        (throw (ex-info (str "Malformed xsd:gMonth " (pr-str lexical)
                             ": not --MM with an optional timezone")
                        {:reason :urpx.schema/malformed-g-month
                         :datatype "xsd:gMonth"
                         :value lexical})))
      v)))


(def ^:private xsd-g-year-lexical
  "The xsd:gYear lexical space, XSD 1.1 Part 2 section 3.3.11, capturing the
   year."
  #"(-?(?:[1-9][0-9]{3,}|0[0-9]{3}))(?:Z|[+-](?:(?:0[0-9]|1[0-3]):[0-5][0-9]|14:00))?")

(defn- ->g-year
  "An xsd:gYear string, '2009' with an optional timezone, as its
   `java.time.Year`; the timezone is dropped. Any other string, or a JSON-LD
   value object typed xsd:gYear whose @value is not such a string, throws
   ex-info with reason `:urpx.schema/malformed-g-year` and the value."
  [v]
  (let [lexical (typed-literal-value v "xsd:gYear")]
    (if (or (string? lexical) (not= v lexical))
      (if-let [[_ yyyy] (and (string? lexical) (re-matches xsd-g-year-lexical lexical))]
        (try (Year/of (Integer/parseInt yyyy))
             (catch Exception _
               (throw (ex-info (str "Malformed xsd:gYear " (pr-str lexical)
                                    ": outside the range of java.time.Year")
                               {:reason :urpx.schema/malformed-g-year
                                :datatype "xsd:gYear"
                                :value lexical}))))
        (throw (ex-info (str "Malformed xsd:gYear " (pr-str lexical)
                             ": not a year of at least four digits with an optional timezone")
                        {:reason :urpx.schema/malformed-g-year
                         :datatype "xsd:gYear"
                         :value lexical})))
      v)))

(defn- <-g-year
  "`java.time.Year` -> xsd:gYear string, at least four digits."
  [v]
  (if (instance? Year v)
    (let [y (.getValue ^Year v)]
      (str (when (neg? y) "-") (format "%04d" (Math/abs y))))
    v))

(defn- integer-out-of-range
  "The ex-info refusing xsd:integer `v` as outside the range of a long."
  [v cause]
  (ex-info (str "xsd:integer " (pr-str v) " is outside the range of a long")
           {:reason :urpx.schema/integer-out-of-range
            :datatype "xsd:integer"
            :value v}
           cause))

(defn- double->long
  "Double or float `v` as the long it equals. An integral value of magnitude
   below 1E21, which the JSON-LD 1.1 API's object to RDF conversion reads as
   an xsd:integer, decodes; one outside the range of a long throws
   `integer-out-of-range`. Any other throws ex-info with reason
   `:urpx.schema/double-not-integer` and the value."
  [v]
  (let [d (double v)]
    (if (and (Double/isFinite d) (== d (Math/rint d)) (< (Math/abs d) 1e21))
      (try
        (.longValueExact (BigDecimal. d))
        (catch ArithmeticException e
          (throw (integer-out-of-range v e))))
      (throw (ex-info (str "Number " v " at an xsd:integer is an xsd:double in JSON-LD,"
                           " not an integer")
                      {:reason :urpx.schema/double-not-integer
                       :datatype "xsd:integer"
                       :value v})))))

(defn- ->long
  "An xsd:integer string as a long. A string outside the xsd:integer lexical
   space throws ex-info with reason `:urpx.schema/malformed-integer`, one
   outside the range of a long `:urpx.schema/integer-out-of-range`; both carry
   the value. A double or float decodes by `double->long`. A JSON-LD value
   object typed xsd:integer is read by its @value."
  [v]
  (let [v (typed-literal-value v "xsd:integer")]
    (cond
      (string? v)                 (if (re-matches xsd-integer-lexical v)
                                    (try
                                      (Long/parseLong v)
                                      (catch NumberFormatException e
                                        (throw (integer-out-of-range v e))))
                                    (throw (ex-info (str "Malformed xsd:integer " (pr-str v)
                                                         ": not an optional sign and digits")
                                                    {:reason :urpx.schema/malformed-integer
                                                     :datatype "xsd:integer"
                                                     :value v})))
      (or (double? v) (float? v)) (double->long v)
      :else                       v)))

(defn- single-or-vec->vec
  "URPX allows a 0..N field to hold one value or a vector of them; normalize to
   a vector so downstream consumers see one shape.

   The last arm is `[v]`, not `(vec v)`. Sequential values are already handled
   above, so anything reaching it is a scalar: `(vec \"urpx:foo\")` shredded a
   compact string @id into eight characters, and `(vec 6)` threw outright. No
   vendored fixture writes either shape, so both were latent, but a decoder
   whose failure mode is silent mangling is worse than one that passes a value
   through for the value schema to reject."
  [v]
  (cond
    (nil? v)        v
    (sequential? v) (vec v)
    :else           [v]))

;; ---------------------------------------------------------------------------
;; Primitive predicates
;; ---------------------------------------------------------------------------

(def Decimal
  [:fn {:error/message "should be a BigDecimal"
        ::xsd-decimal true
        :decode/urpx-jsonld ->decimal
        :encode/urpx-jsonld <-decimal}
   #(instance? BigDecimal %)])

(def Date
  [:fn {:error/message "should be a LocalDate"
        ::xsd-date true
        :decode/urpx-jsonld ->date
        :encode/urpx-jsonld <-date}
   #(instance? LocalDate %)])

(def DateTime
  [:fn {:error/message "should be a LocalDateTime or a ZonedDateTime"
        :decode/urpx-jsonld ->datetime
        :encode/urpx-jsonld <-datetime}
   #(or (instance? LocalDateTime %) (instance? ZonedDateTime %))])

(def Time
  [:fn {:error/message "should be a LocalTime or an OffsetTime"
        :decode/urpx-jsonld ->time
        :encode/urpx-jsonld <-time}
   #(or (instance? LocalTime %) (instance? OffsetTime %))])

(def IsoDuration
  [:fn {:error/message "should be a Duration or Period"
        :decode/urpx-jsonld ->duration
        :encode/urpx-jsonld <-duration}
   #(or (instance? Duration %) (instance? Period %))])

(def MonthNumber
  [:and {:decode/urpx-jsonld ->g-month
         :encode/urpx-jsonld <-g-month}
   :int [:>= 1] [:<= 12]])

(def GYear
  "An xsd:gYear, decoded to `java.time.Year`."
  [:and {:decode/urpx-jsonld ->g-year
         :encode/urpx-jsonld <-g-year}
   [:fn {:error/message "should be a java.time.Year"} #(instance? Year %)]])

(def Int
  "An xsd:integer field — JSON parser may produce long already, but tolerate strings."
  [:and {:decode/urpx-jsonld ->long} :int])

;; ---------------------------------------------------------------------------
;; JSON-LD building blocks
;; ---------------------------------------------------------------------------

(def Ref
  "A JSON-LD @id reference: {:jsonld/id \"urpx:foo\"}.
   Resolved against the entity index built from the parsed document."
  [:map [:jsonld/id :string]])

(def ExclusiveRef
  "A JSON-LD @id reference used where an INLINE NODE is the alternative, as in
   [:or SomeEntity ExclusiveRef]. Closed, unlike `Ref`, and that is the whole
   point: `Ref` is an open map, so an [:or Node Ref] whose second arm is `Ref`
   matches any map carrying a :jsonld/id and the node arm is never reached.
   A malformed inline node then validates as though it were a reference.
   Closing it means a bare reference still passes while anything richer must
   satisfy the node schema.

   Closed to @id ALONE, deliberately. A node that asserts an @type is asserting
   something about itself and should be validated as a node of that type, not
   waved through as a reference; without this, an @id paired with any @type at
   all passes whatever slot it sits in.

   Use `Ref` for reference-only properties, where no inline form is permitted
   and openness costs nothing."
  [:map {:closed true}
   [:jsonld/id :string]])

;; ---------------------------------------------------------------------------
;; Ranges
;;
;; A property whose SHACL shape carries sh:class states that class as the
;; entry property ::range, e.g. [:urpx/appliesInSeason {::range "urpx:Season"}
;; ...]. One whose shape is an sh:or of sh:class alternatives states the set of
;; them, and a node of any member is in range. Validation never reads it: whether a reference points at a Season
;; depends on what else is in the document, which one entity cannot show.
;; `mistyped-references` and `urpx.dsl/dsl-scope` read it.
;; ---------------------------------------------------------------------------

(def subclasses
  "rdfs:subClassOf in the URPX v0.5.1 ontology: each class to its direct
   subclasses. A node of a subclass is in a range that names the class."
  {"urpx:AdministrativeBoundary" #{"urpx:County" "urpx:Municipality" "urpx:State"}
   "urpx:ConditionNode"          #{"urpx:BooleanOperationNode" "urpx:ComparisonNode"}
   "urpx:Organization"           #{"urpx:EnergyServiceProvider" "urpx:RegulatoryAuthority"
                                   "urpx:SystemOperator" "urpx:UtilityOperator"}
   "urpx:PriceSet"               #{"urpx:PriceSetAlternative"}
   "urpx:Profile"                #{"urpx:CustomerProfile" "urpx:ServicePointProfile"}})

(defn instance-of?
  "True if a node of @type `t` is an instance of `class`: `t` is `class` or
   one of its `subclasses`, at any depth."
  [t class]
  (or (= t class)
      (boolean (some #(instance-of? t %) (get subclasses class)))))

(defn in-range?
  "True if a node of @type `t` is in range `r`: an instance of class `r`, or
   of any member when `r` is a set of classes."
  [t r]
  (if (set? r)
    (boolean (some #(instance-of? t %) r))
    (instance-of? t r)))

(def Context
  "A JSON-LD @context, in any of the three forms the spec permits: an inline
   definition object, an IRI string pointing at a remote context, or an array
   mixing the two.

   The array form is not hypothetical: every v0.5.1 test case shipped with
   urpx-org/urpx carries
     [\"https://urpx.org/ns/context/releases/v0.5.1/jsonld/urpx-context.jsonld\"
      {\"prefix\": \"...\"}]
   a remote context followed by a local prefix map, so typing this as :map
   alone rejected the standard's own reference documents.

   Deliberately permissive. clj-urpx never interprets @context: it round-trips
   it untouched and urpx.emit attaches its own. There is no consumer path that
   a tighter shape would protect, so the only thing a narrower type can do here
   is reject valid input."
  [:or :map :string [:vector [:or :map :string]]])

(defn enum-ref
  "A JSON-LD @id reference restricted to a fixed vocabulary: like `Ref`, but the
   id must be one of `locals`, each given bare and prefixed with \"urpx:\" here.

   URPX encodes these as IRI references rather than strings, so a value arrives
   as {:jsonld/id \"urpx:complete\"} and this checks membership of that id.
   Upstream they are owl:oneOf classes enforced through sh:in at the property."
  [& locals]
  [:map [:jsonld/id (into [:enum] (map #(str "urpx:" %)) locals)]])

;; Members transcribed from urpx-shacl sh:in lists at urpx-org/urpx v0.5.1
;; and counted against them, not re-derived.

(def CompletenessResult
  "urpx:CompletenessResultValue, 6 members. The coverage verdict on a
   CoverageClaim. Freshness (urpx:stale) and resolution provenance
   (urpx:fallbackSourced) are orthogonal booleans, not members here."
  (enum-ref "complete" "partial" "pending" "unsupported" "unknown" "noCoverage"))

(def AggregationMethod
  "urpx:AggregationMethodValue, 5 members."
  (enum-ref "sum" "average" "maximum" "minimum" "median"))

(def IntervalAlignment
  "urpx:IntervalAlignmentValue, 4 members."
  (enum-ref "calendarAligned" "billingPeriodAligned" "rollingAligned" "fixedAligned"))

(def IntervalSelection
  "urpx:IntervalSelectionValue, 3 members."
  (enum-ref "rollingWindowSelection" "consecutivePeriodsSelection" "fixedWindowSelection"))

(def IntervalType
  "urpx:IntervalTypeValue, 11 members."
  (enum-ref "realTimeInterval" "fiveMinuteInterval" "fifteenMinuteInterval"
            "thirtyMinuteInterval" "hourlyInterval" "dailyInterval" "weeklyInterval"
            "monthlyInterval" "quarterlyInterval" "annualInterval" "customInterval"))

(def CollectionRole
  "urpx:CollectionRoleValue, 5 members."
  (enum-ref "modelThread" "canonicalSet" "adHocSet" "submissionSet" "scenarioSet"))

(def DynamicPricingType
  "urpx:DynamicPricingTypeValue, 6 members."
  (enum-ref "criticalPeakPricing" "demandResponsePricing" "eventDrivenPricing"
            "indexBasedPricing" "realTimePricing" "timeVaryingPricing"))

(def PriceSource
  "urpx:PriceSourceValue, 7 members."
  (enum-ref "algorithmicSource" "capacityAuctionSource" "demandForecastSource"
            "gasIndexSource" "manualOverrideSource" "renewableForecastSource"
            "wholesaleMarketSource"))

(def UpdateFrequency
  "urpx:UpdateFrequencyValue, 9 members."
  (enum-ref "continuousUpdates" "dailyUpdates" "eventTriggeredUpdates" "every15Minutes"
            "every5Minutes" "hourlyUpdates" "monthlyUpdates" "onDemandUpdates"
            "weeklyUpdates"))

(def ReferenceInclusion
  "urpx:ReferenceInclusionValue, 2 members."
  (enum-ref "includedHere" "retrievedElsewhere"))

(def Confidence
  "urpx:ConfidenceValue, 3 members."
  (enum-ref "verified" "estimated" "projected"))

(def EvaluationKind
  "urpx:EvaluationKindValue, 5 members."
  (enum-ref "consolidation" "coverageEvaluation" "validation" "comparison" "benchmark"))

(def ExclusionReason
  "urpx:ExclusionReasonValue, 4 members. New at v0.5.1."
  (enum-ref "pruned" "fullySuperseded" "voided" "outOfScope"))

(def ScopeKind
  "urpx:ScopeKindValue, 5 members, from urpx-shacl:ScopeKindValueShape's sh:in.

   The kinds are what make a TemporalScope readable: the same date range means
   different things depending on which clock it refers to. urpx:coverage is the
   one a consumer asking \"does this verdict apply to my window?\" needs; the
   others are about publication, the source's own lifecycle, when the facts
   apply, and when something was observed."
  (enum-ref "publication" "sourceLifecycle" "applicability" "observation" "coverage"))

(def ObservanceRule
  "urpx:ObservanceRuleValue, with urpx:mondayIfSundayObservance from
   urpx-org/urpx#77."
  (enum-ref "actualDateObservance" "fridayIfWeekendObservance"
            "mondayIfSundayObservance" "mondayIfWeekendObservance"
            "nearestWeekdayObservance"))

(def DayOfWeek
  "urpx:DayOfWeekValue, the range of urpx:daysOfWeek, from urpx-org/urpx#71.
   ISO 8601 days 1 (Monday) through 7 (Sunday)."
  (enum-ref "monday" "tuesday" "wednesday" "thursday" "friday" "saturday" "sunday"))

(def SourcePublicationType
  "urpx:SourcePublicationTypeValue, 8 members. Do NOT reuse the retired
   10-member urpx:PublicationTypeValue, which has zero occurrences at v0.5.1."
  (enum-ref "tariffBook" "tariffStatement" "tariffSupplement" "tariffAddendum"
            "supplyCharges" "derExports" "highlyDynamicPrices" "marketPriceFeed"))

(def ProgramType
  "urpx:ProgramTypeValue, 8 members."
  (enum-ref "demandResponseProgram" "energyEfficiencyProgram" "greenEnergyProgram"
            "lowIncomeProgram" "moderateIncomeProgram" "timeOfUseProgram"
            "communityChoiceAggregation" "communitySolar"))

(def EnrollmentStatus
  "urpx:EnrollmentStatusValue, 6 members."
  (enum-ref "activeStatus" "expiredStatus" "inactiveStatus" "pendingStatus"
            "suspendedStatus" "terminatedStatus"))

;; ---------------------------------------------------------------------------
;; Program enrollment
;; ---------------------------------------------------------------------------

(def ProgramEnrollment
  "urpx-shacl:ProgramEnrollmentShape: a customer's enrollment in a utility
   program."
  [:map
   [:jsonld/type [:= "urpx:ProgramEnrollment"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/programName :string]
   [:urpx/programType         {:optional true} ProgramType]
   [:urpx/enrollmentStatus    {:optional true} EnrollmentStatus]
   [:urpx/enrollmentStartDate {:optional true} Date]
   [:urpx/enrollmentEndDate   {:optional true} Date]
   [:urpx/minimumTenure       {:optional true} IsoDuration]
   [:urpx/url                 {:optional true} :string]])

(defn- or-program-enrollment
  "`otherwise`, except that a node typed urpx:ProgramEnrollment is a
   `ProgramEnrollment`."
  [otherwise]
  [:multi {:dispatch :jsonld/type}
   ["urpx:ProgramEnrollment" ProgramEnrollment]
   [::m/default otherwise]])

(def RdfList
  "A JSON-LD list object, `{:jsonld/list [...]}`: an ordered RDF list of at
   least one member. Members are as written: a Ref, a value object such as
   `{:jsonld/value \"P\" :jsonld/type \"xsd:string\"}`, a plain literal, or
   an inline node; an inline urpx:ProgramEnrollment is a `ProgramEnrollment`."
  [:map {:closed true}
   [:jsonld/list [:vector {:min 1} (or-program-enrollment :any)]]])

;; ---------------------------------------------------------------------------
;; Plan elements
;; ---------------------------------------------------------------------------

(def Season
  [:map
   [:jsonld/type [:= "urpx:Season"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/seasonName :string]
   [:urpx/month {:optional true :decode/urpx-jsonld single-or-vec->vec} [:vector MonthNumber]]
   [:urpx/yearlyRecurrence {:optional true} :boolean]])

(def MetricSpecification
  [:map
   [:jsonld/type [:= "urpx:MetricSpecification"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/commodity       {:optional true} Ref]
   [:urpx/measurementKind {:optional true} Ref]
   [:urpx/unitOfMeasure   {:optional true} Ref]
   [:urpx/flowDirection   {:optional true} Ref]
   [:urpx/intervalDuration {:optional true} IsoDuration]
   [:urpx/intervalType      {:optional true} IntervalType]
   [:urpx/intervalAlignment {:optional true} IntervalAlignment]
   [:urpx/intervalSelection {:optional true} IntervalSelection]])

(def MetricInput
  [:map
   [:jsonld/type [:= "urpx:MetricInput"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/name :string]
   [:urpx/description {:optional true} :string]
   [:urpx/metricInputType Ref]
   ;; 0..N upstream (no sh:maxCount). Accepted as one or many; price.clj reads
   ;; one through urpx.index/single, which refuses rather than guessing.
   [:urpx/hasMetricSpecification {:optional true ::range "urpx:MetricSpecification"} [:or MetricSpecification [:vector MetricSpecification]]]
   ;; In v0.2.0 profileMetricProperty is a Ref to a vocabulary IRI
   ;; (e.g. urpx:meterCount, urpx:departureVintage).
   [:urpx/profileMetricProperty  {:optional true} Ref]])

(def TimeBracket
  [:map
   [:jsonld/type [:= "urpx:TimeBracket"]]
   [:urpx/startTime Time]
   [:urpx/endTime Time]
   ;; v0.2.0 SHACL: dayType is required (was optional in v0.1.x).
   [:urpx/dayType Ref]
   ;; 0..N, read with dayType urpx:customDays (urpx-org/urpx#71).
   [:urpx/daysOfWeek {:optional true :decode/urpx-jsonld single-or-vec->vec} [:vector DayOfWeek]]
   ;; 0..N upstream. TOU matching in price.clj reads one via index/single.
   [:urpx/appliesInSeason     {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:Season"} [:or Ref [:vector Ref]]]
   [:urpx/includeHolidays     {:optional true} :boolean]
   [:urpx/includeNonHolidays  {:optional true} :boolean]])

(def TOUPeriod
  [:map
   [:jsonld/type [:= "urpx:TOUPeriod"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/name :string]
   [:urpx/touPeriodNumber Int]
   [:urpx/priority   {:optional true} Int]
   [:urpx/startDate  {:optional true} Date]
   [:urpx/endDate    {:optional true} Date]
   [:urpx/hasTimeBracket {:decode/urpx-jsonld single-or-vec->vec ::range "urpx:TimeBracket"} [:vector TimeBracket]]])

(def TOUTier
  [:map
   [:jsonld/type [:= "urpx:TOUTier"]]
   [:urpx/tierSourceName {:optional true} :string]
   [:urpx/touTierStandardName {:optional true} Ref]
   [:urpx/touTierNumber Int]
   [:urpx/hasTOUPeriod {:decode/urpx-jsonld single-or-vec->vec ::range "urpx:TOUPeriod"} [:vector TOUPeriod]]])

(def TOUSchedule
  [:map
   [:jsonld/type [:= "urpx:TOUSchedule"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/name {:optional true} :string]
   ;; v0.2.0 SHACL: referencesMetricInput is a required vector (min 1, no max).
   [:urpx/referencesMetricInput
    {:decode/urpx-jsonld single-or-vec->vec ::range "urpx:MetricInput"}
    [:vector Ref]]
   [:urpx/hasCalendar {:optional true ::range "urpx:HolidayCalendar"} Ref]
   [:urpx/hasTOUTier {:decode/urpx-jsonld single-or-vec->vec ::range "urpx:TOUTier"} [:vector TOUTier]]])

(def CalculationMethod
  [:map
   [:jsonld/type [:= "urpx:CalculationMethod"]]
   [:urpx/name {:optional true} :string]
   [:urpx/description {:optional true} :string]
   [:urpx/intervalDuration {:optional true} IsoDuration]
   [:urpx/aggregationMethod {:optional true} AggregationMethod]
   [:urpx/intervalType      {:optional true} IntervalType]
   [:urpx/intervalAlignment {:optional true} IntervalAlignment]
   [:urpx/intervalSelection {:optional true} IntervalSelection]])

(def BillingCycle
  "The billing period a ledger settles over."
  [:map
   [:jsonld/type [:= "urpx:BillingCycle"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/cycleStartDay       {:optional true} Int]
   [:urpx/intervalDuration    {:optional true} IsoDuration]
   [:urpx/intervalType        {:optional true} IntervalType]
   [:urpx/intervalAlignment   {:optional true} IntervalAlignment]
   [:urpx/intervalSelection   {:optional true} IntervalSelection]
   [:urpx/startDate           {:optional true} Date]
   [:urpx/startDateTime       {:optional true} DateTime]
   [:urpx/endDate             {:optional true} Date]
   [:urpx/endDateTime         {:optional true} DateTime]
   [:urpx/expectedEndDate     {:optional true} Date]
   [:urpx/expectedEndDateTime {:optional true} DateTime]])

(def Ledger
  "v0.2.0: Ledger no longer carries hasPriceDefinition. PriceDefinitions
   live at BillingModel.hasPriceDefinition with PriceDefinition.referencesLedger
   pointing back to one of the BillingModel.hasLedger entries."
  [:map
   [:jsonld/type [:= "urpx:Ledger"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/name :string]
   [:urpx/description {:optional true} :string]
   [:urpx/hasCalculationMethod {:optional true ::range "urpx:CalculationMethod"}
    [:or CalculationMethod ExclusiveRef]]
   [:urpx/ledgerType {:optional true} Ref]
   [:urpx/referencesMetricInput
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:MetricInput"}
    [:vector Ref]]
   [:urpx/hasBillingCycle {:optional true ::range "urpx:BillingCycle"}
    [:or BillingCycle ExclusiveRef]]
   [:urpx/settlementInterval {:optional true} IsoDuration]])

;; ---------------------------------------------------------------------------
;; Data source references
;; ---------------------------------------------------------------------------

(def DataSourceReference
  "v0.2.0: many new optional fields. mediaType is now Ref-only (no longer
   string); we list the most commonly populated fields."
  [:map
   [:jsonld/type [:= "urpx:DataSourceReference"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/name {:optional true} :string]
   [:urpx/description {:optional true} :string]
   [:urpx/url       {:optional true} :string]
   [:urpx/accessURL {:optional true} :string]
   [:urpx/fileName  {:optional true} :string]
   [:urpx/mediaType {:optional true} Ref]
   [:urpx/dataSourceType  {:optional true} Ref]
   [:urpx/dateRetrieved   {:optional true} Date]
   [:urpx/dateReceived    {:optional true} Date]
   [:urpx/datePublished   {:optional true} Date]
   [:urpx/effectiveDate   {:optional true} Date]
   [:urpx/expirationDate  {:optional true} Date]
   [:urpx/pageStart {:optional true} Int]
   [:urpx/pageEnd   {:optional true} Int]
   ;; v0.5.1 flattening (upstream bbaea780): urpx:TariffBookReference and
   ;; urpx:TariffStatementReference were deleted and their fields hoisted
   ;; inline onto this node, which is now sh:closed with nothing required.
   ;; hasSelector and sourcePublication are not new, merely never modeled here.
   [:urpx/leafStart  {:optional true} :string]
   [:urpx/leafEnd    {:optional true} :string]
   [:urpx/anchorLink {:optional true} :string]
   ;; Ref, not a typed schema: oa:Selector is an external Web Annotation class
   ;; the ontology deliberately does not import, and the shape constrains only
   ;; sh:class oa:Selector.
   ;; 0..N upstream (sh:minCount 0, no sh:maxCount); nothing in src/ reads it.
   [:urpx/hasSelector
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "oa:Selector"}
    [:vector Ref]]
   [:urpx/sourcePublication {:optional true ::range "urpx:SourcePublication"} Ref]
   ;; Enum membership deliberately unenforced here; see the enum issue.
   [:urpx/tariffStatementType {:optional true} Ref]])

(def PriceUpdateRule
  "How and when a dynamic price is updated, hung off urpx:hasPriceUpdateRule."
  [:map
   [:jsonld/type [:= "urpx:PriceUpdateRule"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/name        {:optional true} :string]
   [:urpx/description {:optional true} :string]
   [:urpx/priority    {:optional true} Int]])

(def DynamicPricing
  "How a Price's value is set and updated, hung off urpx:hasDynamicPricing."
  [:map
   [:jsonld/type [:= "urpx:DynamicPricing"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/dynamicPricingType DynamicPricingType]
   [:urpx/priceSource
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector PriceSource]]
   [:urpx/updateFrequency {:optional true} UpdateFrequency]
   [:urpx/priceCeiling    {:optional true} Decimal]
   [:urpx/priceFloor      {:optional true} Decimal]
   [:urpx/hasPriceUpdateRule
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:PriceUpdateRule"}
    [:vector [:or PriceUpdateRule ExclusiveRef]]]])

(def PriceDefinition
  "v0.2.0: PriceDefinition gains required referencesLedger plus many properties
   that used to live on Price (priceCurrency, primaryChargeType,
   referencesServiceTier, referencesTouPeriod, serviceTierNumber, …). One PD
   can govern many Prices via Price.referencesPriceDefinition."
  [:map
   [:jsonld/type [:= "urpx:PriceDefinition"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/name :string]
   [:urpx/description {:optional true} :string]
   ;; GeneralRule, ApplicabilityGroup and PricingModel are defined further
   ;; down, so these hold an inline node or a reference as an open map; the
   ;; range still types them.
   [:urpx/pursuantTo
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:GeneralRule"}
    [:vector :map]]
   [:urpx/hasApplicabilityGroup
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:ApplicabilityGroup"}
    [:vector :map]]
   [:urpx/contributedBy
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:PricingModel"}
    [:vector :map]]
   [:urpx/hasDataSourceReference
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:DataSourceReference"}
    [:vector [:or DataSourceReference ExclusiveRef]]]
   ;; 0..N upstream (sh:minCount 0, no sh:maxCount); nothing in src/ reads it.
   [:urpx/chargeType
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector Ref]]
   [:urpx/chargeCategory {:optional true} Ref]
   [:urpx/billPortion   {:optional true} Ref]
   [:urpx/commodity     {:optional true} Ref]
   [:urpx/priceCurrency {:optional true} :string]
   [:urpx/primaryChargeType {:optional true} Ref]
   ;; v0.2.0: required, single. PD points to its parent Ledger.
   [:urpx/referencesLedger {::range "urpx:Ledger"} Ref]
   ;; 0..N upstream (sh:minCount 0, no sh:maxCount), and the skos:note names
   ;; PriceDefinition explicitly: "Use on PriceSetAlternative, PriceDefinition,
   ;; TOUSchedule, ServiceTiers, or ServiceBracket to scope applicability to a
   ;; specific ProfileAlternative." This is the SELECTION layer reaching a
   ;; definition, which is how a multi-dimensional matrix declares exclusivity
   ;; readably rather than burying it in ApplicabilityRule predicates nothing
   ;; evaluates. `urpx.price` reads ONE; several refuse rather than inventing a
   ;; conjunctive reading for a shape no vendored fixture has.
   [:urpx/referencesProfileAlternative
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:ProfileAlternative"}
    [:vector Ref]]
   [:urpx/referencesMetricInput
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:MetricInput"}
    [:vector Ref]]
   [:urpx/referencesServiceTier    {:optional true ::range "urpx:ServiceTier"} Ref]
   [:urpx/referencesServiceBracket {:optional true ::range "urpx:ServiceBracket"} Ref]
   [:urpx/referencesTouPeriod      {:optional true ::range "urpx:TOUPeriod"} Ref]
   [:urpx/serviceTierNumber {:optional true} Int]
   [:urpx/touPeriodNumber   {:optional true} Int]
   [:urpx/baseQuantity      {:optional true} Decimal]
   [:urpx/hasCalculationMethod {:optional true ::range "urpx:CalculationMethod"} CalculationMethod]
   [:urpx/hasRateCase
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:RateCase"}
    [:vector Ref]]
   ;; v0.2.0 SHACL: PriceDefinitionShape allows urpx:appliesInSeason → Season
   ;; (sh:minCount 0, sh:class urpx:Season). Used for non-TOU seasonal pricing
   ;; where the seasonal partition isn't carried by a TOUPeriod indirection
   ;; (e.g. CPAU E-2: flat $/kWh per Summer/Winter, no TOU within periods).
   [:urpx/appliesInSeason
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:Season"}
    [:vector Ref]]
   ;; ApplicabilityRule's full shape is defined further down in the Conditions
   ;; section; we inline a minimal version here so the gMonth decoder for
   ;; :urpx/month attaches without forward-ref gymnastics. The fuller shape
   ;; (with ConditionExpression) is referenced from Price.hasApplicabilityRule.
   [:urpx/hasApplicabilityRule
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:ApplicabilityRule"}
    [:vector
     [:map
      [:jsonld/type {:optional true} :string]
      [:jsonld/id   {:optional true} :string]
      [:urpx/name   {:optional true} :string]
      [:urpx/month  {:optional true} [:vector MonthNumber]]
      [:urpx/hasConditionExpression {:optional true} :any]]]]])

(def ServiceBracket
  "v0.2.0 + 423e83f: contextual boundary definition for a ServiceTier.
   Carries the bounds + bound operators that previously lived inline on
   ServiceTier, plus optional appliesInSeason / referencesProfileAlternative
   qualifiers so a single logical tier (one ServiceTier, identified by
   serviceTierNumber) can carry season- or PA-specific bound variants by
   attaching multiple ServiceBrackets via ServiceTier.hasServiceBracket.

   SHACL enforces sh:or three branches (lower-only, upper-only, or both
   bounds + matching operators); Malli stays open-world, consistent with
   the customDimensionProperty / customConditionProperty pattern.

   An adjusting bracket carries urpx:adjustsServiceTier and one or both
   offsets in place of bounds; `urpx.price` adds the offsets to the bounds
   of the tier it names."
  [:map
   [:jsonld/type [:= "urpx:ServiceBracket"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/lowerBound         {:optional true} Decimal]
   [:urpx/upperBound         {:optional true} Decimal]
   [:urpx/lowerBoundOperator {:optional true} Ref]
   [:urpx/upperBoundOperator {:optional true} Ref]
   [:urpx/adjustsServiceTier
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:ServiceTier"}
    [:vector Ref]]
   [:urpx/lowerBoundOffset   {:optional true} Decimal]
   [:urpx/upperBoundOffset   {:optional true} Decimal]
   [:urpx/appliesInSeason
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:Season"}
    [:vector Ref]]
   [:urpx/referencesProfileAlternative {:optional true ::range "urpx:ProfileAlternative"} Ref]])

(def ServiceTier
  "v0.2.0 + 423e83f: bounds moved off ServiceTier onto attached
   ServiceBracket instances (Decision #111). ServiceTier carries only the
   logical identity (serviceTierNumber + optional name); hasServiceBracket
   carries 1+ bound-bearing brackets, optionally qualified by season or
   ProfileAlternative for matrix-cell variants of the same logical tier.

   A tier hosting adjusting brackets carries no serviceTierNumber."
  [:map
   [:jsonld/type [:= "urpx:ServiceTier"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/serviceTierNumber {:optional true} Int]
   [:urpx/name {:optional true} :string]
   [:urpx/hasServiceBracket
    {:decode/urpx-jsonld single-or-vec->vec ::range "urpx:ServiceBracket"}
    [:vector ServiceBracket]]])

(def ServiceTiers
  "v0.2.0 + 0994aec: container holding the block-tier vector. Carries the
   referencesMetricInput pointer that used to live on individual ServiceTiers
   in v0.1.x.

   :urpx/appliesInSeason allows seasonal grouping — a single
   ProfileAlternative-keyed ServiceTiers can be split into per-season
   containers without nesting season into serviceTierNumber.

   :urpx/referencesProfileAlternative wires a per-cell ServiceTiers to one
   ProfileAlternative within a matrix-style hasServiceTiers vector.

   :urpx/referencesMetricInput names the quantity a tier's BOUNDS are stated
   in, which is NOT the same as the quantity the ledger's charge is stated in.
   The two are meant to differ: LADWP R-1A's Power Access Charge is 2.30, 7.90
   and 22.70 per P1M per meter, with bounds in kWh, so its container names
   mi-energy while its ledger names mi-meter-count. Reading this property as a
   pointer to the ledger a container belongs to would therefore strip the
   bounds off a monthly fixed charge tiered by consumption, which is a real
   filing shape rather than a modeling error.

   No ServiceTiers in the vendored fixtures carries either qualifier. The PG&E
   E-TOU-C baseline-tier modifier is not the per-cell shape: it carries one
   container of three tiers, 40 brackets each, and states its
   20 (territory, code) x 2 season matrix per urpx:ServiceBracket.

   Both qualifiers are read at resolution time, on this container and on
   urpx:ServiceBracket alike, by `urpx.price/select-tier-bracket`, and on a
   urpx:RatePlanModifier's tiers as well as a urpx:RatePlan's. The 120 above
   are the case that made it matter: composed with their base plan and given a
   baseline cell, they state 0 to 13.5 and 13.5 to 54.0 kWh per day in summer
   and 0 to 11.0 and 11.0 to 44.0 in winter."
  [:map
   [:jsonld/type [:= "urpx:ServiceTiers"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/appliesInSeason
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:Season"}
    [:vector Ref]]
   [:urpx/referencesMetricInput
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:MetricInput"}
    [:vector Ref]]
   [:urpx/referencesProfileAlternative {:optional true ::range "urpx:ProfileAlternative"} Ref]
   [:urpx/hasServiceTier {:decode/urpx-jsonld single-or-vec->vec ::range "urpx:ServiceTier"} [:vector ServiceTier]]])

(def BillingModel
  "v0.2.0: hasPriceDefinition lives at this level (not inside Ledger). A
   billing model hosting only adjusting brackets carries none. The v0.1.x
   BillingModel.hasServiceTier flat vector is gone — block tiers now live
   under hasServiceTiers's nested `urpx:hasServiceTier` field.

   Both hasTOUSchedule and hasServiceTiers are vectors (sh:maxCount
   unbounded per BillingModelShape). `single-or-vec->vec` keeps
   single-container fixtures compatible without touching their JSON-LD:

     - hasTOUSchedule is a vector for filings that TOU-zone multiple
       metric inputs (CPAU E-4 TOU: separate energy + demand schedules).
     - hasServiceTiers is a vector for a filing that splits its tiers into
       per-season or per-cell containers. No fixture does: the PG&E E-TOU-C
       baseline-tier modifier carries one ServiceTiers of three tiers and
       states its 20 (territory, code) x 2 season matrix per
       urpx:ServiceBracket, 40 brackets per tier."
  [:map
   [:jsonld/type [:= "urpx:BillingModel"]]
   [:urpx/prorationMethod {:optional true} :string]
   [:urpx/hasTOUSchedule
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:TOUSchedule"}
    [:vector TOUSchedule]]
   [:urpx/hasServiceTiers
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:ServiceTiers"}
    [:vector ServiceTiers]]
   [:urpx/hasLedger
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:Ledger"}
    [:vector Ledger]]
   [:urpx/hasPriceDefinition
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:PriceDefinition"}
    [:vector PriceDefinition]]])

(def Holiday
  "v0.2.0: holidayName is required. Observance can be a literal date
   (urpx:holidayDate) or a free-form rule string (urpx:holidayRule);
   urpx:observanceRule optionally shifts the date when it lands on a
   weekend."
  [:map
   [:jsonld/type [:= "urpx:Holiday"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/holidayName    :string]
   [:urpx/holidayDate    {:optional true} Date]
   [:urpx/holidayRule    {:optional true} :string]
   [:urpx/observanceRule {:optional true} ObservanceRule]])

(def HolidayCalendar
  [:map
   [:jsonld/type [:= "urpx:HolidayCalendar"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/hasHoliday
    {:decode/urpx-jsonld single-or-vec->vec ::range "urpx:Holiday"}
    [:vector Holiday]]])

;; PlanElements is defined further down — its hasProfileAlternatives entry
;; depends on ProfileAlternatives, which depends on ConditionExpression
;; (defined below).

;; ---------------------------------------------------------------------------
;; Conditions (v0.2.0)
;;
;; Condition trees replace the v0.1.x Condition / ApplicabilityConstraint
;; vocabulary. Three operand-leaf shapes (Object, Datatype, Custom) plus
;; two recursive node shapes (Comparison, Boolean) compose into a single
;; ConditionExpression entry point.
;; ---------------------------------------------------------------------------

(def ObjectOperandNode
  "Ref-valued operand. sh:xone in SHACL constrains exactly one of
   conditionProperty, referencesValue, or referencesMetricInput to be present;
   we don't enforce that here — let pyshacl be the strict validator."
  [:map
   [:jsonld/type [:= "urpx:ObjectOperandNode"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/conditionProperty    {:optional true} Ref]
   [:urpx/referencesValue      {:optional true} (or-program-enrollment Ref)]
   [:urpx/referencesMetricInput {:optional true} Ref]])

(def DatatypeOperandNode
  "Typed-literal operand. Carries optional conditionProperty (a Ref) and one
   typed value field. SHACL's sh:xone enforces 'exactly one value field'; the
   coercer simply decodes whichever is present."
  [:map
   [:jsonld/type [:= "urpx:DatatypeOperandNode"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/conditionProperty    {:optional true} Ref]
   [:urpx/referencesMetricInput {:optional true} Ref]
   [:urpx/booleanValue  {:optional true} :boolean]
   [:urpx/dateValue     {:optional true} Date]
   [:urpx/dateTimeValue {:optional true} DateTime]
   [:urpx/decimalValue  {:optional true} Decimal]
   [:urpx/durationValue {:optional true} IsoDuration]
   [:urpx/gMonthValue   {:optional true} MonthNumber]
   [:urpx/gYearValue    {:optional true} GYear]
   [:urpx/integerValue  {:optional true} Int]
   [:urpx/stringValue   {:optional true} :string]
   [:urpx/timeValue     {:optional true} Time]])

(def CustomOperandNode
  "Like DatatypeOperandNode but with a free-form `customConditionProperty`
   string in lieu of a Ref-valued conditionProperty. Useful when the
   condition property isn't yet in the URPX vocabulary (e.g. PG&E's
   `departureVintage`)."
  [:map
   [:jsonld/type [:= "urpx:CustomOperandNode"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/customConditionProperty {:optional true} :string]
   [:urpx/referencesMetricInput   {:optional true} Ref]
   [:urpx/booleanValue  {:optional true} :boolean]
   [:urpx/dateValue     {:optional true} Date]
   [:urpx/dateTimeValue {:optional true} DateTime]
   [:urpx/decimalValue  {:optional true} Decimal]
   [:urpx/durationValue {:optional true} IsoDuration]
   [:urpx/gMonthValue   {:optional true} MonthNumber]
   [:urpx/gYearValue    {:optional true} GYear]
   [:urpx/integerValue  {:optional true} Int]
   [:urpx/stringValue   {:optional true} :string]
   [:urpx/timeValue     {:optional true} Time]])

(def ^:private condition-registry
  "The local registry for the mutually recursive operand tree: an operand is an
   ObjectOperandNode, DatatypeOperandNode, CustomOperandNode, ComparisonNode or
   BooleanOperationNode, and the last two take operands."
  {::Operand
   [:multi {:dispatch :jsonld/type}
    ["urpx:ObjectOperandNode"     ObjectOperandNode]
    ["urpx:DatatypeOperandNode"   DatatypeOperandNode]
    ["urpx:CustomOperandNode"     CustomOperandNode]
    ["urpx:ComparisonNode"        [:ref ::ComparisonNode]]
    ["urpx:BooleanOperationNode"  [:ref ::BooleanOperationNode]]]
   ::ComparisonNode
   [:map
    [:jsonld/type [:= "urpx:ComparisonNode"]]
    [:jsonld/id   {:optional true} :string]
    [:urpx/name   {:optional true} :string]
    [:urpx/description {:optional true} :string]
    [:urpx/logicExpression {:optional true} :string]
    [:urpx/operator Ref]
    [:urpx/leftOperand  {::range #{"urpx:CustomOperandNode" "urpx:DatatypeOperandNode"
                                   "urpx:ObjectOperandNode" "urpx:OperationNode"}}
     [:ref ::Operand]]
    [:urpx/rightOperand {::range #{"urpx:CustomOperandNode" "urpx:DatatypeOperandNode"
                                   "urpx:ObjectOperandNode" "urpx:OperationNode"}}
     [:ref ::Operand]]]
   ::BooleanOperationNode
   [:map
    [:jsonld/type [:= "urpx:BooleanOperationNode"]]
    [:jsonld/id   {:optional true} :string]
    [:urpx/description {:optional true} :string]
    [:urpx/logicExpression {:optional true} :string]
    [:urpx/operator Ref]
    [:urpx/operandNodes
     {:decode/urpx-jsonld single-or-vec->vec
      ::range #{"urpx:BooleanOperationNode" "urpx:ComparisonNode"}}
     [:vector [:ref ::Operand]]]]})

(def ComparisonNode
  "A comparison of a left and a right operand by urpx:operator. Each operand
   is an ObjectOperandNode, DatatypeOperandNode, CustomOperandNode,
   ComparisonNode or BooleanOperationNode."
  [:schema {:registry condition-registry}
   (::ComparisonNode condition-registry)])

(def BooleanOperationNode
  "urpx:operator (and, or, not) over urpx:operandNodes, each an operand as in
   `ComparisonNode`."
  [:schema {:registry condition-registry}
   (::BooleanOperationNode condition-registry)])

(def ConditionExpression
  "A condition tree whose urpx:rootNode is a ComparisonNode or a
   BooleanOperationNode."
  [:schema {:registry condition-registry}
   [:map
    [:jsonld/type [:= "urpx:ConditionExpression"]]
    [:jsonld/id   {:optional true} :string]
    [:urpx/rootNode
     {::range #{"urpx:BooleanOperationNode" "urpx:ComparisonNode"}}
     [:multi {:dispatch :jsonld/type}
      ["urpx:ComparisonNode"       [:ref ::ComparisonNode]]
      ["urpx:BooleanOperationNode" [:ref ::BooleanOperationNode]]]]]])

;; ---------------------------------------------------------------------------
;; ProfileAlternatives — declarative customer-or-configuration choice
;; ---------------------------------------------------------------------------
;; Per URPX v0.2.0, ProfileAlternatives + PriceSetAlternatives express
;; mutually-exclusive options that require an explicit selection (income-tier
;; CARE/FERA/Standard, vintage-PCIA default tier, voltage configurations, …).
;; ProfileAlternatives lives at PlanElements.hasProfileAlternatives;
;; PriceSetAlternatives lives at Prices.hasPriceSetAlternatives and references
;; the ProfileAlternatives whose alternatives drive the per-price selection.
;; Authoritative shapes: urpx-shacl:ProfileAlternativesShape, *Shape, etc.

(def AlternativeDimension
  "One axis of a customer-profile choice — e.g. 'incomeTier' (with possible
   values CARE / FERA / Standard) or 'voltageLevel'.

   Per upstream urpx commit c9d974f (closes urpx-org/urpx#30), exactly one
   of urpx:dimensionProperty (xsd:anyURI when a suitable URPX property
   exists) or urpx:customDimensionProperty (xsd:string fallback) must be
   present. The xone is enforced by SHACL (pyshacl); we keep the Malli
   schema open-world and let SHACL catch violations, consistent with the
   existing stance for analogous customConditionProperty on operand nodes."
  [:map
   [:jsonld/type [:= "urpx:AlternativeDimension"]]
   [:jsonld/id   {:optional true} :string]
   [:urpx/dimensionDisplayName :string]
   [:urpx/dimensionProperty       {:optional true} :string]   ; xsd:anyURI
   [:urpx/customDimensionProperty {:optional true} :string]   ; xsd:string fallback
   [:urpx/hasPossibleValues
    {:decode/urpx-jsonld single-or-vec->vec}
    [:vector {:min 1} RdfList]]
   [:urpx/referencesMetricInput
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:MetricInput"}
    [:vector Ref]]])

(def AlternativeDimensions
  "Container for the AlternativeDimension(s) that span a ProfileAlternatives
   choice (typically a single dimension; multi-dimension matrices possible)."
  [:map
   [:jsonld/type [:= "urpx:AlternativeDimensions"]]
   [:jsonld/id   {:optional true} :string]
   [:urpx/hasAlternativeDimension
    {:decode/urpx-jsonld single-or-vec->vec ::range "urpx:AlternativeDimension"}
    [:vector AlternativeDimension]]])

(def ProfileAlternative
  "A single named alternative within a ProfileAlternatives set. The
   `hasConditionExpression` says how to determine whether this alternative
   applies to a given customer; `defaultSelectionLogic` is a free-form
   tie-breaker / default note when conditions don't disambiguate."
  [:map
   [:jsonld/type [:= "urpx:ProfileAlternative"]]
   [:jsonld/id   {:optional true} :string]
   [:urpx/name :string]
   [:urpx/defaultSelectionLogic   {:optional true} :string]
   [:urpx/hasConditionExpression  {::range "urpx:ConditionExpression"} ConditionExpression]])

(def ProfileAlternatives
  "Top-level declaration of a customer-profile-driven choice. Lives at
   PlanElements.hasProfileAlternatives. PriceSetAlternatives reference one
   ProfileAlternatives via urpx:referencesProfileAlternatives, and each
   PriceSetAlternative within the set selects ONE of its sibling
   ProfileAlternatives via urpx:referencesProfileAlternative."
  [:map
   [:jsonld/type [:= "urpx:ProfileAlternatives"]]
   [:jsonld/id   {:optional true} :string]
   [:urpx/name :string]
   [:urpx/hasAlternativeDimensions {::range "urpx:AlternativeDimensions"} AlternativeDimensions]
   [:urpx/hasProfileAlternative
    {:decode/urpx-jsonld single-or-vec->vec ::range "urpx:ProfileAlternative"}
    [:vector ProfileAlternative]]])

(def PlanElements
  "v0.2.0: gains optional `urpx:hasProfileAlternatives` for declarative
   customer-or-configuration choices (income tier, voltage configuration,
   PCIA default tier, …). Each ProfileAlternatives is referenced by a
   PriceSetAlternatives at Prices.hasPriceSetAlternatives — the per-customer
   selection that determines which alternative's prices apply."
  [:map
   [:jsonld/type [:= "urpx:PlanElements"]]
   [:urpx/hasSeasons         {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:Season"} [:vector Season]]
   [:urpx/hasMetricInput     {:decode/urpx-jsonld single-or-vec->vec ::range "urpx:MetricInput"} [:vector MetricInput]]
   ;; 0..N upstream. holidays.clj reads one via index/single. Merging several
   ;; calendars into one holiday set looks obvious but is a semantic decision
   ;; the vocabulary does not state, so it refuses instead.
   [:urpx/hasHolidayCalendar {:optional true ::range "urpx:HolidayCalendar"} [:or HolidayCalendar [:vector HolidayCalendar]]]
   [:urpx/hasProfileAlternatives
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:ProfileAlternatives"}
    [:vector ProfileAlternatives]]
   [:urpx/hasBillingModel {::range "urpx:BillingModel"} BillingModel]])

(def ApplicabilityGroup
  "Container for cross-cutting applicability conditions; carried on
   Price/PriceSet to express things like income-tier eligibility.
   Extra geographic/customer fields exist in v0.2.0 SHACL but most fixtures
   only carry name + hasConditionExpression."
  [:map
   [:jsonld/type [:= "urpx:ApplicabilityGroup"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/name {:optional true} :string]
   [:urpx/applicabilityLogic    {:optional true} :string]
   [:urpx/hasConditionExpression {:optional true ::range "urpx:ConditionExpression"} ConditionExpression]])

(def ApplicabilityRule
  [:map
   [:jsonld/type [:= "urpx:ApplicabilityRule"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/name {:optional true} :string]
   [:urpx/month {:optional true :decode/urpx-jsonld single-or-vec->vec} [:vector MonthNumber]]
   [:urpx/hasConditionExpression {:optional true ::range "urpx:ConditionExpression"} ConditionExpression]])

;; ---------------------------------------------------------------------------
;; Eligibility (v0.2.0 — closed shape, ConditionExpression-driven)
;; ---------------------------------------------------------------------------

(def PriorRatePlan
  "A rate plan a customer was enrolled on before, for eligibility rules that
   depend on enrollment history."
  [:map
   [:jsonld/type [:= "urpx:PriorRatePlan"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/referencesRatePlan  {:optional true ::range "urpx:RatePlan"} Ref]
   [:urpx/serviceRole         {:optional true} Ref]
   [:urpx/enrollmentStatus    {:optional true} EnrollmentStatus]
   [:urpx/enrollmentStartDate {:optional true} Date]
   [:urpx/enrollmentEndDate   {:optional true} Date]
   [:urpx/minimumTenure       {:optional true} IsoDuration]])

(def CustomerProfile
  "v0.2.0: greatly expanded; we list the few properties we consume plus
   hasCustomerKind which is the canonical test-case dispatch in the fixtures."
  [:map
   [:jsonld/type [:= "urpx:CustomerProfile"]]
   [:jsonld/id {:optional true} :string]
   ;; 0..N upstream (sh:minCount 0, no sh:maxCount); nothing in src/ reads it.
   [:urpx/hasCustomerKind
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector Ref]]
   ;; 0..N upstream (sh:minCount 0, no sh:maxCount); nothing in src/ reads it.
   [:urpx/hasCustomerSubKind
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector Ref]]
   ;; 0..N upstream (sh:minCount 0, no sh:maxCount); nothing in src/ reads it.
   [:urpx/entityType
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector Ref]]
   [:urpx/hasProgramEnrollment
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:ProgramEnrollment"}
    [:vector [:or ProgramEnrollment ExclusiveRef]]]
   [:urpx/hasPriorRatePlan
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:PriorRatePlan"}
    [:vector [:or PriorRatePlan ExclusiveRef]]]
   [:urpx/annualIncomeRange          {:optional true :decode/urpx-jsonld single-or-vec->vec} [:vector Ref]]
   [:urpx/assistanceEligibilityLevel {:optional true :decode/urpx-jsonld single-or-vec->vec} [:vector Ref]]
   [:urpx/specialNeedsStatus         {:optional true :decode/urpx-jsonld single-or-vec->vec} [:vector Ref]]
   [:urpx/taxExemptStatus            {:optional true :decode/urpx-jsonld single-or-vec->vec} [:vector Ref]]
   [:urpx/billingCycleCode           {:optional true :decode/urpx-jsonld single-or-vec->vec} [:vector :string]]
   [:urpx/creditRating               {:optional true :decode/urpx-jsonld single-or-vec->vec} [:vector :string]]
   [:urpx/householdCount             {:optional true :decode/urpx-jsonld single-or-vec->vec} [:vector Int]]
   [:urpx/householdSize              {:optional true :decode/urpx-jsonld single-or-vec->vec} [:vector Int]]
   [:urpx/isCCACustomer              {:optional true :decode/urpx-jsonld single-or-vec->vec} [:vector :boolean]]
   [:urpx/isCommunitySolarCustomer   {:optional true :decode/urpx-jsonld single-or-vec->vec} [:vector :boolean]]
   [:urpx/occupancyCount {:optional true} Int]])

(def DistributedEnergyResource
  "Generation, storage or charging equipment at a service point."
  [:map
   [:jsonld/type [:= "urpx:DistributedEnergyResource"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/derType               Ref]
   [:urpx/interconnectionStatus Ref]
   [:urpx/connectorType         {:optional true} Ref]
   [:urpx/inverterType          {:optional true} Ref]
   [:urpx/panelOrientation      {:optional true} Ref]
   [:urpx/powerRating           {:optional true} Decimal]
   [:urpx/solarCapacity         {:optional true} Decimal]
   [:urpx/windCapacity          {:optional true} Decimal]
   [:urpx/storageCapacity       {:optional true} Decimal]
   [:urpx/roundTripEfficiency   {:optional true} Decimal]
   [:urpx/evChargingCapacity    {:optional true} Decimal]
   [:urpx/smartChargingEnabled  {:optional true} :boolean]])

(def ServicePointProfile
  "The physical and locational attributes of a service point that eligibility
   and pricing can depend on."
  [:map
   [:jsonld/type [:= "urpx:ServicePointProfile"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/billedDemand          {:optional true} Decimal]
   [:urpx/buildingSquareFootage {:optional true} Decimal]
   [:urpx/nominalVoltage        {:optional true} Decimal]
   [:urpx/ratedPowerCapacity    {:optional true} Decimal]
   [:urpx/meterCount            {:optional true} Int]
   [:urpx/serviceClassPattern   {:optional true} :string]
   [:urpx/connectionKind        {:optional true} Ref]
   [:urpx/endUse                {:optional true} Ref]
   [:urpx/geographicScope       {:optional true} Ref]
   [:urpx/hasMeteringConfiguration {:optional true} Ref]
   [:urpx/loadProfile           {:optional true} Ref]
   [:urpx/phaseCodeKind         {:optional true} Ref]
   [:urpx/premiseType           {:optional true} Ref]
   [:urpx/seasonalPattern       {:optional true} Ref]
   [:urpx/voltageLevel          {:optional true} Ref]
   [:urpx/geographicZone        {:optional true :decode/urpx-jsonld single-or-vec->vec} [:vector Ref]]
   [:urpx/hasHVAC               {:optional true :decode/urpx-jsonld single-or-vec->vec} [:vector Ref]]
   [:urpx/hasCounty             {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:County"} [:vector Ref]]
   [:urpx/hasMunicipality       {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:Municipality"} [:vector Ref]]
   [:urpx/hasState              {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:State"} [:vector Ref]]
   [:urpx/hasServiceTerritory   {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:ServiceTerritory"} [:vector Ref]]
   [:urpx/hasDistributedEnergyResource
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:DistributedEnergyResource"}
    [:vector [:or DistributedEnergyResource ExclusiveRef]]]])

(def AccountServiceAgreement
  "The contract under which an account takes service."
  [:map
   [:jsonld/type [:= "urpx:AccountServiceAgreement"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/contractEffectiveDate DateTime]
   [:urpx/contractExpirationDate {:optional true} DateTime]])

(def Scenario
  "v0.2.0: NEW. EligibilityRule.hasCustomerProfile moved here. SHACL's sh:or
   constraint requires either hasCustomerProfile xor hasServicePointProfile;
   we treat both as optional."
  [:map
   [:jsonld/type [:= "urpx:Scenario"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/hasCustomerProfile {:optional true ::range "urpx:CustomerProfile"} CustomerProfile]
   [:urpx/hasServicePointProfile {:optional true ::range "urpx:ServicePointProfile"}
    [:or ServicePointProfile ExclusiveRef]]])

(def EligibilityRule
  "v0.2.0: closed shape. hasCustomerProfile is gone (moved to Scenario).
   hasConditionExpression is the only required body field."
  [:map
   [:jsonld/type [:= "urpx:EligibilityRule"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/name {:optional true} :string]
   [:urpx/description {:optional true} :string]
   [:urpx/hasConditionExpression {::range "urpx:ConditionExpression"} ConditionExpression]
   [:urpx/hasDataSourceReference
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:DataSourceReference"}
    [:vector Ref]]])

;; ---------------------------------------------------------------------------
;; Prices
;; ---------------------------------------------------------------------------

(def GeneralRule
  "A rule stated once, at the tariff book level, and cited by the pricing that
   follows it."
  [:map
   [:jsonld/type [:= "urpx:GeneralRule"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/generalRuleID :string]
   [:urpx/name        {:optional true} :string]
   [:urpx/description {:optional true} :string]
   [:urpx/hasDataSourceReference
    {:decode/urpx-jsonld single-or-vec->vec ::range "urpx:DataSourceReference"}
    [:vector {:min 1} [:or DataSourceReference ExclusiveRef]]]
   [:urpx/hasApplicabilityRule
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:ApplicabilityRule"}
    [:vector [:or ApplicabilityRule ExclusiveRef]]]
   [:urpx/contributedBy
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:PricingModel"}
    [:vector :map]]])

(def Price
  "v0.2.0: many new optional value fields (adder, amount, basePrice,
   basedOnPrice, currentPrice, multiplier) and metadata
   (hasApplicabilityGroup, hasRateCase, …). referencesPriceDefinition is
   sh:maxCount 1 (still required in practice for any per-energy price)."
  [:map
   [:jsonld/type [:= "urpx:Price"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/name {:optional true} :string]
   [:urpx/description {:optional true} :string]
   [:urpx/contributedBy
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:PricingModel"}
    [:vector :map]]
   [:urpx/hasDynamicPricing {:optional true ::range "urpx:DynamicPricing"}
    [:or DynamicPricing ExclusiveRef]]
   [:urpx/hasDataSourceReference
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:DataSourceReference"}
    [:vector [:or DataSourceReference ExclusiveRef]]]
   [:urpx/unitPrice  {:optional true} Decimal]
   [:urpx/amount     {:optional true} Decimal]
   [:urpx/basePrice  {:optional true} Decimal]
   [:urpx/currentPrice {:optional true} Decimal]
   [:urpx/adder      {:optional true} Decimal]
   [:urpx/multiplier {:optional true} Decimal]
   ;; CALCULATION INPUT semantics upstream, distinct from includesPrice's
   ;; AGGREGATION. sh:or (urpx:Price, urpx:PriceSummary), and 0..N: sh:minCount
   ;; 0 with no sh:maxCount. Ref rather than one of those two schemas because
   ;; either may be named instead of inlined, and a bare reference says nothing
   ;; about which; ExclusiveRef would reject the inline form upstream permits.
   ;; Several values are read DISJUNCTIVELY by `urpx.price/price-value`:
   ;; candidate bases, exactly one expected to be in the documents passed to a
   ;; call. That lets one discount Price serve a modifier applying to many base
   ;; plans. URPX fixes the cardinality and says nothing about multiplicity;
   ;; the conjunctive reading is what urpx:PriceSummary already provides.
   [:urpx/basedOnPrice
    {:optional true :decode/urpx-jsonld single-or-vec->vec
     ::range #{"urpx:Price" "urpx:PriceSummary"}}
    [:vector Ref]]
   [:urpx/baseQuantity {:optional true} Decimal]
   [:urpx/priceCurrency {:optional true} :string]
   ;; sh:minCount 0 sh:maxCount 1 upstream: a conformant Price may omit it,
   ;; and PriceSetAlternative models it optional too.
   ;; Both readers, urpx.price/scoped-prices and price-active-at-tou?,
   ;; nil-thread through get-in.
   [:urpx/referencesPriceDefinition {:optional true ::range "urpx:PriceDefinition"} Ref]
   [:urpx/referencesServiceTier    {:optional true ::range "urpx:ServiceTier"} Ref]
   [:urpx/referencesServiceBracket {:optional true ::range "urpx:ServiceBracket"} Ref]
   [:urpx/referencesTouPeriod      {:optional true ::range "urpx:TOUPeriod"} Ref]
   [:urpx/serviceTierNumber {:optional true} Int]
   [:urpx/touPeriodNumber   {:optional true} Int]
   [:urpx/startDate     {:optional true} Date]
   [:urpx/startDateTime {:optional true} DateTime]
   [:urpx/endDate       {:optional true} Date]
   [:urpx/endDateTime   {:optional true} DateTime]
   [:urpx/appliesInSeason
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:Season"}
    [:vector Ref]]
   ;; 0..N upstream (sh:minCount 0, no sh:maxCount); nothing in src/ reads it.
   [:urpx/hasApplicabilityGroup
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:ApplicabilityGroup"}
    [:vector ApplicabilityGroup]]
   [:urpx/hasApplicabilityRule
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:ApplicabilityRule"}
    ;; Inline rule or a bare @id ref to one defined elsewhere in the document
    ;; (RDF merges a reference with its definition). Richer schema first so an
    ;; inline node decodes through ApplicabilityRule; ExclusiveRef rather than
    ;; Ref so a malformed inline node cannot slip through the reference arm.
    [:vector [:or ApplicabilityRule ExclusiveRef]]]
   [:urpx/hasCalculationMethod {:optional true ::range "urpx:CalculationMethod"} CalculationMethod]
   [:urpx/hasRateCase
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:RateCase"}
    [:vector Ref]]])

(def PriceSummary
  "An aggregation of Prices into a subtotal, referenced by another Price's
   urpx:basedOnPrice to express a value derived from them (a percentage
   discount over selected line items, for instance).

   urpx:includesPrice is sh:minCount 2 upstream: a summary of one price is not
   a summary. urpx:summaryAmount is the author's own stated total, optional and
   NOT what the resolver uses; the resolver sums the members IN EFFECT at the
   instant being resolved, so an author-stated total that disagrees is visible
   rather than authoritative.

   Members are gated by the same season, month and TOU predicates that gate a
   tier, because a summary becomes a unit price at an instant and summing a
   line item that is not in effect would be the wrong number. One summary may
   therefore name every discountable line item on a plan and stay correct in
   every TOU period.

   Which Prices a summary names is entirely the author's business, and
   deliberately so: on one base plan a CARE summary and a FERA summary name
   different members, some carve the base services charge out and some do not,
   and member counts vary widely between utilities. None of that is knowable
   here, and none of it should be."
  [:map
   [:jsonld/type [:= "urpx:PriceSummary"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/name {:optional true} :string]
   [:urpx/summaryAmount {:optional true} Decimal]
   [:urpx/includesPrice
    {:decode/urpx-jsonld single-or-vec->vec ::range "urpx:Price"}
    [:and [:vector [:or Price ExclusiveRef]]
     [:fn {:error/message "urpx:includesPrice is sh:minCount 2 upstream"}
      #(<= 2 (count %))]]]])

(def PriceSet
  [:map
   [:jsonld/type [:= "urpx:PriceSet"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/priceCurrency {:optional true} :string]
   [:urpx/chargeType
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector Ref]]
   [:urpx/billPortion {:optional true} Ref]
   [:urpx/hasMetricSpecification {:optional true ::range "urpx:MetricSpecification"}
    [:or MetricSpecification ExclusiveRef]]
   [:urpx/hasDataSourceReference
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:DataSourceReference"}
    [:vector [:or DataSourceReference ExclusiveRef]]]
   [:urpx/name {:optional true} :string]
   [:urpx/description {:optional true} :string]
   [:urpx/startDate     {:optional true} Date]
   [:urpx/startDateTime {:optional true} DateTime]
   [:urpx/endDate       {:optional true} Date]
   [:urpx/endDateTime   {:optional true} DateTime]
   [:urpx/touPeriodNumber {:optional true} Int]
   [:urpx/serviceTierNumber {:optional true} Int]
   [:urpx/referencesPriceDefinition {:optional true ::range "urpx:PriceDefinition"} Ref]
   [:urpx/referencesServiceBracket  {:optional true ::range "urpx:ServiceBracket"} Ref]
   ;; 0..N upstream (sh:minCount 0, no sh:maxCount); nothing in src/ reads it.
   [:urpx/hasApplicabilityGroup
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:ApplicabilityGroup"}
    [:vector ApplicabilityGroup]]
   ;; 0..N upstream. This is where a summary actually attaches: urpx:hasPriceSummary
   ;; is rdfs:domain urpx:PriceSet, so a subtotal lives beside the line items it
   ;; names rather than at the Prices container or the top of the graph.
   [:urpx/hasPriceSummary
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:PriceSummary"}
    [:vector [:or PriceSummary ExclusiveRef]]]
   [:urpx/hasPrice {:decode/urpx-jsonld single-or-vec->vec ::range "urpx:Price"} [:vector Price]]])

;; Prices is defined further down — its hasPriceSetAlternatives entry
;; depends on PriceSetAlternatives (defined below).

;; ---------------------------------------------------------------------------
;; Typed identity (URPX v0.5.1)
;;
;; A plan, a modifier and their version snapshots can each carry typed external
;; identifiers: a CPUC MIDAS RIN, a utility tariff code, whatever register
;; applies. All three shapes require skos:notation and urpx:scheme and are
;; sh:closed upstream; clj-urpx does not enforce closedness.
;; ---------------------------------------------------------------------------

(def ^:private identifier-entries
  [[:skos/notation :string]
   ;; urpx:IdentifierScheme is an OPEN class, deliberately: the ontology says
   ;; "not a closed enumeration ... an implementer operating a scheme that is
   ;; not among them mints its own IRI as an IdentifierScheme instance." There
   ;; is no sh:in on this path, so a plain Ref is correct and an :enum would be
   ;; wrong. The vocabulary publishes named members, among them
   ;; urpx:caMidasRinScheme and urpx:utilityTariffCodeScheme, which it says
   ;; "cover the common registers".
   [:urpx/scheme Ref]
   [:urpx/identifierJurisdiction {:optional true} :string]])

(def RatePlanIdentifier
  "A typed external identifier for a urpx:RatePlan, e.g. a CPUC MIDAS RIN."
  (into [:map
         [:jsonld/type [:= "urpx:RatePlanIdentifier"]]
         [:jsonld/id {:optional true} :string]]
        identifier-entries))

(def RatePlanModifierIdentifier
  "Parallel to `RatePlanIdentifier`, for a urpx:RatePlanModifier."
  (into [:map
         [:jsonld/type [:= "urpx:RatePlanModifierIdentifier"]]
         [:jsonld/id {:optional true} :string]]
        identifier-entries))

(def OrganizationIdentifier
  "A typed external identifier for an organization, hung off urpx:hasIdentity.
   v0.5.1 reshaped this: urpx:identifierScheme and urpx:utilityId are gone and
   skos:notation plus urpx:scheme are now required. An EIA id rides here as
   notation under the EIA scheme rather than as the former urpx:eiaId."
  (into [:map
         [:jsonld/type [:= "urpx:OrganizationIdentifier"]]
         [:jsonld/id {:optional true} :string]]
        identifier-entries))

;; ---------------------------------------------------------------------------
;; Organizations (URPX v0.5.1)
;;
;; An organization carries 0..N urpx:hasIdentity, each naming it by one
;; urpx:OrganizationName over a validity window and listing its typed
;; identifiers. urpx:UtilityOperator is a subclass of urpx:Organization with
;; its own closed shape.
;; ---------------------------------------------------------------------------

(def OrganizationName
  "One name of an organization, hung off urpx:hasName."
  [:map
   [:jsonld/type [:= "urpx:OrganizationName"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/name         {:optional true} :string]
   [:urpx/abbreviation {:optional true} :string]
   [:urpx/isCanonical  {:optional true} :boolean]
   [:urpx/isPreferred  {:optional true} :boolean]
   [:urpx/nameType     {:optional true} Ref]])

(def OrganizationIdentity
  "An organization's name and identifiers over a validity window, hung off
   urpx:hasIdentity."
  [:map
   [:jsonld/type [:= "urpx:OrganizationIdentity"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/hasName {::range "urpx:OrganizationName"} [:or OrganizationName ExclusiveRef]]
   [:urpx/hasIdentifier
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:OrganizationIdentifier"}
    [:vector [:or OrganizationIdentifier ExclusiveRef]]]
   [:urpx/identityValidFrom {:optional true} DateTime]
   [:urpx/identityValidTo   {:optional true} DateTime]])

(def LocationDataSet
  "The time zone, daylight-saving rules and holiday calendars that apply where
   an organization operates or a plan is offered."
  [:map
   [:jsonld/type [:= "urpx:LocationDataSet"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/timezoneIdentifier  {:optional true} :string]
   [:urpx/daylightSavingRules {:optional true} :string]
   [:urpx/expectedEndDate     {:optional true} Date]
   [:urpx/expectedEndDateTime {:optional true} DateTime]
   [:urpx/hasHolidayCalendar
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:HolidayCalendar"}
    [:vector [:or HolidayCalendar ExclusiveRef]]]])

(def ^:private organization-entries
  "The properties urpx-shacl:OrganizationShape and the shapes of its
   subclasses share."
  [[:jsonld/id {:optional true} :string]
   [:urpx/legalName      {:optional true} :string]
   [:urpx/alternateName  {:optional true} :string]
   [:urpx/organizationType Ref]
   [:urpx/commodity
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector Ref]]
   [:urpx/hasIdentity
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:OrganizationIdentity"}
    [:vector [:or OrganizationIdentity ExclusiveRef]]]
   [:urpx/hasLocationDataSet
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:LocationDataSet"}
    [:vector [:or LocationDataSet ExclusiveRef]]]
   [:urpx/offers
    {:optional true :decode/urpx-jsonld single-or-vec->vec
     ::range #{"urpx:PricingModel" "urpx:RatePlan" "urpx:RatePlanModifier"
               "urpx:RatePlanModifierVersion" "urpx:RatePlanVersion"}}
    [:vector Ref]]])

(def ^:private operates-in
  "urpx:operatesIn, on every organization shape but SystemOperator's."
  [:urpx/operatesIn
   {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:SystemOperator"}
   [:vector Ref]])

(def Organization
  "An organization. urpx-shacl:OrganizationShape is open upstream; utility
   identifiers live under urpx:hasIdentity."
  (-> [:map
       [:jsonld/type [:= "urpx:Organization"]]]
      (into organization-entries)
      (conj operates-in [:urpx/url {:optional true} :string])))

(def UtilityOperator
  "A urpx:Organization that operates a utility."
  (-> [:map
       [:jsonld/type [:= "urpx:UtilityOperator"]]]
      (into organization-entries)
      (conj operates-in [:urpx/ownershipType {:optional true} Ref])))

(def EnergyServiceProvider
  "A urpx:Organization that provides energy service in a stated role, such as
   a community choice aggregator."
  (-> [:map
       [:jsonld/type [:= "urpx:EnergyServiceProvider"]]]
      (into organization-entries)
      (conj operates-in [:urpx/serviceRole Ref])))

(def RegulatoryAuthority
  "A urpx:Organization that regulates others, such as a state commission."
  (-> [:map
       [:jsonld/type [:= "urpx:RegulatoryAuthority"]]]
      (into organization-entries)
      (conj operates-in
            [:urpx/regulatoryScope Ref]
            [:urpx/approves
             {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:RatePlan"}
             [:vector Ref]]
            [:urpx/hasJurisdictionOver
             {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:Organization"}
             [:vector Ref]])))

(def SystemOperator
  "A urpx:Organization that operates a grid or market, the range of
   urpx:operatesIn."
  (-> [:map
       [:jsonld/type [:= "urpx:SystemOperator"]]]
      (into organization-entries)))

(def AnyOrganization
  "An organization of any modeled kind inline, chosen by its @type, or a
   reference: the value of a slot whose range is urpx:Organization."
  [:multi {:dispatch :jsonld/type}
   ["urpx:Organization"          Organization]
   ["urpx:UtilityOperator"       UtilityOperator]
   ["urpx:EnergyServiceProvider" EnergyServiceProvider]
   ["urpx:RegulatoryAuthority"   RegulatoryAuthority]
   ["urpx:SystemOperator"        SystemOperator]
   [::m/default                  ExclusiveRef]])

(def OfferingOrganization
  "`AnyOrganization`, under the name it had when it held two kinds."
  AnyOrganization)

(def OrganizationEvent
  "An event in an organization's history, such as a merger."
  [:map
   [:jsonld/type [:= "urpx:OrganizationEvent"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/regulatoryApproval {:optional true} :string]])

(def OrganizationTransition
  "A change from one organization to another, such as a rename."
  [:map
   [:jsonld/type [:= "urpx:OrganizationTransition"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/regulatoryApproval {:optional true} :string]])

;; ---------------------------------------------------------------------------
;; Coverage machinery (URPX v0.5.1)
;;
;; A version snapshot states what it does and does not cover, so a consumer can
;; tell a complete resolution from a partial one. Read from urpx-shacl at
;; urpx-org/urpx v0.5.1. Enum-valued slots stay bare Refs here; membership
;; enforcement is a separate concern.
;; ---------------------------------------------------------------------------

(def ModelExclusion
  "Something a PricingModel deliberately leaves out, and why. New in v0.5.1.
   `PricingModel` is defined further down, so excludedModel is a Ref, typed by
   its range."
  [:map
   [:jsonld/type [:= "urpx:ModelExclusion"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/excludedModel {::range "urpx:PricingModel"} Ref]
   [:urpx/exclusionReason ExclusionReason]
   [:urpx/description {:optional true} :string]])

(def TemporalPeriod
  "A named date range. urpx-shacl:TemporalPeriodShape is sh:closed with
   rdfs:label, urpx:startDate and urpx:endDate, each sh:minCount 0 sh:maxCount 1,
   so a conformant period may state none of them."
  [:map
   [:jsonld/type {:optional true} [:= "urpx:TemporalPeriod"]]
   [:jsonld/id   {:optional true} :string]
   [:rdfs/label  {:optional true} :string]
   [:urpx/startDate {:optional true} Date]
   [:urpx/endDate   {:optional true} Date]])

(def TemporalScope
  "A role-tagged time span: which clock a date range refers to.

   Transcribed from urpx-shacl:TemporalScopeShape, which IS fully specified:
   sh:closed, urpx:scopeKind sh:minCount 1 sh:maxCount 1 against
   ScopeKindValueShape, and urpx:startDate, urpx:endDate, urpx:startDateTime,
   urpx:endDateTime and urpx:hasTemporalPeriod each 0..1. scopeKind is the only
   required field, so a scope may name its role and state no bounds at all.

   WHAT IS NOT SPECIFIED IS THE EDGE TO IT. urpx:hasTemporalScope appears as
   sh:path ZERO times in the shapes graph and in sh:ignoredProperties 79 times,
   so every closed shape permits it and none constrains it: nothing says which
   classes may carry a scope, how many, or of which kind. Modeling the node is
   transcription; modeling the edge is a judgement, and it is made as 0..N
   because that is what \"unconstrained\" means rather than because any filing
   carries several."
  [:map
   [:jsonld/type {:optional true} [:= "urpx:TemporalScope"]]
   [:jsonld/id   {:optional true} :string]
   [:urpx/scopeKind ScopeKind]
   [:urpx/startDate     {:optional true} Date]
   [:urpx/endDate       {:optional true} Date]
   [:urpx/startDateTime {:optional true} DateTime]
   [:urpx/endDateTime   {:optional true} DateTime]
   [:urpx/hasTemporalPeriod {:optional true ::range "urpx:TemporalPeriod"} [:or TemporalPeriod ExclusiveRef]]])

(def Evaluation
  "How a coverage claim was arrived at: who evaluated it, when, and of what
   kind. Reached from CoverageClaim via urpx:explainedBy."
  [:map
   [:jsonld/type [:= "urpx:Evaluation"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/evaluationKind {:optional true} EvaluationKind]
   [:urpx/description    {:optional true} :string]
   [:urpx/evaluatedBy    {:optional true ::range "urpx:Organization"} AnyOrganization]
   [:urpx/evaluatedOn    {:optional true} DateTime]
   [:urpx/hasModelExclusion
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:ModelExclusion"}
    [:vector [:or ModelExclusion ExclusiveRef]]]])

(def CoverageClaim
  "What a version snapshot claims to cover. urpx:completenessResult is the one
   required field (sh:minCount 1 on urpx-shacl:CoverageClaimShape)."
  [:map
   [:jsonld/type [:= "urpx:CoverageClaim"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/completenessResult CompletenessResult]
   [:urpx/confidence       {:optional true} Confidence]
   [:urpx/selfContained    {:optional true} :boolean]
   ;; The skos:note on urpx:CoverageClaim says producers "should attach a
   ;; TemporalScope with scopeKind coverage stating the window the verdict
   ;; covers". Should, not must, and no shape enforces it, so a verdict that
   ;; states no window is conformant, which is what makes reading one worth
   ;; doing carefully.
   [:urpx/hasTemporalScope
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector [:or TemporalScope ExclusiveRef]]]
   [:urpx/stale            {:optional true} :boolean]
   [:urpx/fallbackSourced  {:optional true} :boolean]
   [:urpx/explainedBy
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:Evaluation"}
    [:vector [:or Evaluation ExclusiveRef]]]])

(def PriceDefinitionReference
  "A PriceDefinition a snapshot relies on, and whether its content is included
   here or has to be retrieved elsewhere. urpx:referenceInclusion is required.
   `ModelCollection` is defined further down, so resolvedFrom is a Ref vector,
   typed by its range."
  [:map
   [:jsonld/type [:= "urpx:PriceDefinitionReference"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/referenceInclusion ReferenceInclusion]
   [:urpx/referencesPriceDefinition {:optional true ::range "urpx:PriceDefinition"} Ref]
   [:urpx/documentedBy
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:DataSourceReference"}
    [:vector [:or DataSourceReference ExclusiveRef]]]
   [:urpx/resolvedFrom
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:ModelCollection"}
    [:vector Ref]]])

;; ---------------------------------------------------------------------------
;; PriceSetAlternative(s) — pairs with ProfileAlternatives above
;; ---------------------------------------------------------------------------

(def PriceSetAlternative
  "One Price-bearing alternative within a PriceSetAlternatives set.
   Carries Price-like top-level fields (chargeType, billPortion, …) plus
   the required `referencesProfileAlternative` Ref linking it to one of
   the sibling ProfileAlternatives, plus `hasPrice` (1..*) for the actual
   prices that apply when this alternative is selected."
  [:map
   [:jsonld/type [:= "urpx:PriceSetAlternative"]]
   [:jsonld/id   {:optional true} :string]
   [:urpx/name             {:optional true} :string]
   [:urpx/description      {:optional true} :string]
   [:urpx/alternativeLabel {:optional true} :string]
   [:urpx/billingCycleCode {:optional true} :string]
   [:urpx/priceCurrency    {:optional true} :string]
   [:urpx/priceMatchingKey {:optional true} :string]
   ;; Decimals
   [:urpx/unitPrice    {:optional true} Decimal]
   [:urpx/amount       {:optional true} Decimal]
   [:urpx/basePrice    {:optional true} Decimal]
   [:urpx/currentPrice {:optional true} Decimal]
   [:urpx/adder        {:optional true} Decimal]
   [:urpx/multiplier   {:optional true} Decimal]
   [:urpx/baseQuantity {:optional true} Decimal]
   ;; Refs
   [:urpx/billPortion       {:optional true} Ref]
   [:urpx/chargeCategory    {:optional true} Ref]
   [:urpx/chargeType
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector Ref]]
   [:urpx/commodity         {:optional true} Ref]
   [:urpx/primaryChargeType {:optional true} Ref]
   ;; Dates / times
   [:urpx/startDate     {:optional true} Date]
   [:urpx/endDate       {:optional true} Date]
   [:urpx/expectedEndDate {:optional true} Date]
   [:urpx/startDateTime {:optional true} DateTime]
   [:urpx/endDateTime   {:optional true} DateTime]
   [:urpx/expectedEndDateTime {:optional true} DateTime]
   ;; Integers
   [:urpx/serviceTierNumber {:optional true} Int]
   [:urpx/touPeriodNumber   {:optional true} Int]
   ;; Multi-valued refs
   [:urpx/appliesInSeason
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:Season"}
    [:vector Ref]]
   [:urpx/referencesMetricInput
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:MetricInput"}
    [:vector Ref]]
   [:urpx/hasAlternative
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:ProfileAlternative"}
    [:vector Ref]]
   [:urpx/hasRateCase
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:RateCase"}
    [:vector Ref]]
   ;; Single Refs
   [:urpx/referencesPriceDefinition   {:optional true ::range "urpx:PriceDefinition"} Ref]
   [:urpx/referencesProfileAlternative {::range "urpx:ProfileAlternative"} Ref]   ; required (sh:minCount 1)
   [:urpx/referencesServiceTier       {:optional true ::range "urpx:ServiceTier"} Ref]
   [:urpx/referencesServiceBracket    {:optional true ::range "urpx:ServiceBracket"} Ref]
   [:urpx/referencesTouPeriod         {:optional true ::range "urpx:TOUPeriod"} Ref]
   ;; Sub-entities
   ;; 0..N upstream (sh:minCount 0, no sh:maxCount); nothing in src/ reads it.
   [:urpx/hasApplicabilityGroup
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:ApplicabilityGroup"}
    [:vector ApplicabilityGroup]]
   [:urpx/hasApplicabilityRule
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:ApplicabilityRule"}
    ;; Inline rule or a bare @id ref to one defined elsewhere in the document
    ;; (RDF merges a reference with its definition). Richer schema first so an
    ;; inline node decodes through ApplicabilityRule; ExclusiveRef rather than
    ;; Ref so a malformed inline node cannot slip through the reference arm.
    [:vector [:or ApplicabilityRule ExclusiveRef]]]
   [:urpx/hasCalculationMethod  {:optional true ::range "urpx:CalculationMethod"} CalculationMethod]
   [:urpx/hasDataSourceReference
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:DataSourceReference"}
    [:vector DataSourceReference]]
   ;; 0..N upstream (no sh:maxCount). Accepted as one or many; price.clj reads
   ;; one through urpx.index/single, which refuses rather than guessing.
   [:urpx/hasMetricSpecification {:optional true ::range "urpx:MetricSpecification"} [:or MetricSpecification [:vector MetricSpecification]]]
   ;; 0..N upstream, same as on PriceSet: an alternative carries its own
   ;; subtotals, since which line items a summary names depends on which
   ;; alternative was selected.
   [:urpx/hasPriceSummary
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:PriceSummary"}
    [:vector [:or PriceSummary ExclusiveRef]]]
   [:urpx/hasPrice
    {:decode/urpx-jsonld single-or-vec->vec ::range "urpx:Price"}
    [:vector Price]]])  ; required (sh:minCount 1)

(def PriceSetAlternatives
  "Top-level container for a customer-profile-driven price selection.
   Lives at Prices.hasPriceSetAlternatives. References one ProfileAlternatives
   (declared at PlanElements.hasProfileAlternatives) whose alternatives drive
   per-PriceSetAlternative choice."
  [:map
   [:jsonld/type [:= "urpx:PriceSetAlternatives"]]
   [:jsonld/id   {:optional true} :string]
   [:urpx/name             {:optional true} :string]
   [:urpx/priceMatchingKey {:optional true} :string]
   [:urpx/referencesProfileAlternatives {::range "urpx:ProfileAlternatives"} Ref]
   [:urpx/hasPriceSetAlternative
    {:decode/urpx-jsonld single-or-vec->vec ::range "urpx:PriceSetAlternative"}
    [:vector PriceSetAlternative]]])

(def Prices
  "v0.2.0: gains optional `urpx:hasPriceSetAlternatives` for customer-profile-
   driven price selection. Each PriceSetAlternatives references a sibling
   ProfileAlternatives (declared at PlanElements.hasProfileAlternatives) and
   exposes one PriceSetAlternative per ProfileAlternative."
  [:map
   [:jsonld/type [:= "urpx:Prices"]]
   [:urpx/hasPrice    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:Price"} [:vector Price]]
   [:urpx/hasPriceSet {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:PriceSet"} [:vector PriceSet]]
   [:urpx/hasPriceSetAlternatives
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:PriceSetAlternatives"}
    [:vector PriceSetAlternatives]]])

(def RateCase
  "v0.2.0: NEW. Identifies the regulatory proceeding that authorized a
   PriceDefinition / RatePlanVersion / Price / PriceSet."
  [:map
   [:jsonld/type [:= "urpx:RateCase"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/name {:optional true} :string]
   [:urpx/description {:optional true} :string]
   [:urpx/caseNumber :string]
   [:urpx/url        :string]
   [:urpx/priority   {:optional true} Int]])

;; ---------------------------------------------------------------------------
;; Plan and version
;; ---------------------------------------------------------------------------

(def RateGroup
  "A group of rate plans within a service class."
  [:map
   [:jsonld/type [:= "urpx:RateGroup"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/rateGroupIdentifier :string]
   [:urpx/rateGroupName       :string]
   [:urpx/parentServiceClass {:optional true ::range "urpx:ServiceClass"} :map]])

(def ServiceClass
  "A utility's class of service, as its tariff names it."
  [:map
   [:jsonld/type [:= "urpx:ServiceClass"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/serviceClassName    :string]
   [:urpx/serviceClassRaw     :string]
   [:urpx/serviceClassCleaned {:optional true} :string]
   [:urpx/hasRateGroup
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:RateGroup"}
    [:vector [:or RateGroup ExclusiveRef]]]])

(def PricingModel
  "An authored, citable, possibly partial statement of pricing: the unit a
   publication carries. New in v0.3.0. Consolidated into the derived
   RatePlanVersion snapshot through urpx:consolidatedFrom."
  [:map
   [:jsonld/type [:= "urpx:PricingModel"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/confidence Confidence]
   ;; An open map, not `PlanElements`: a model is partial and may name the
   ;; metric inputs and ledgers of the plan it amends by reference, where
   ;; `PlanElements` holds them inline for the resolver.
   [:urpx/hasPlanElements {:optional true ::range "urpx:PlanElements"} :map]
   [:urpx/hasPrices {:optional true ::range "urpx:Prices"}
    [:or Prices ExclusiveRef]]
   [:urpx/hasGeneralRule
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:GeneralRule"}
    [:vector [:or GeneralRule ExclusiveRef]]]
   [:urpx/pursuantTo
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:GeneralRule"}
    [:vector [:or GeneralRule ExclusiveRef]]]
   [:urpx/documentedBy
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:DataSourceReference"}
    [:vector [:or DataSourceReference ExclusiveRef]]]
   [:urpx/offeredBy   {:optional true ::range "urpx:Organization"} AnyOrganization]
   [:urpx/publishedBy {:optional true ::range "urpx:Organization"} AnyOrganization]
   [:urpx/referencesRatePlan
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:RatePlan"}
    [:vector :map]]
   [:urpx/referencesRatePlanModifier
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:RatePlanModifier"}
    [:vector :map]]
   [:urpx/withinPublication {:optional true ::range "urpx:TariffFiling"} :map]
   [:urpx/supersedes
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:PricingModel"}
    [:vector :map]]
   [:urpx/replaces   {:optional true ::range "urpx:PricingModel"} :map]
   [:urpx/replacedBy {:optional true ::range "urpx:PricingModel"} :map]
   [:urpx/cancelledBy {:optional true ::range #{"urpx:PricingModel" "urpx:SourcePublication"}} :map]
   [:urpx/cancelledOn         {:optional true} Date]
   [:urpx/cancelledOnDateTime {:optional true} DateTime]
   [:urpx/effectiveDate       {:optional true} Date]
   [:urpx/effectiveDateTime   {:optional true} DateTime]
   [:urpx/expectedEndDate     {:optional true} Date]
   [:urpx/expectedEndDateTime {:optional true} DateTime]
   [:urpx/expirationDate      {:optional true} Date]
   [:urpx/expirationDateTime  {:optional true} DateTime]
   [:urpx/dateModified        {:optional true} Date]
   [:urpx/dateTimeModified    {:optional true} DateTime]])

(def ModelCollection
  "PricingModels grouped over time, in a role such as a model thread or a
   submission set. New in v0.3.0."
  [:map
   [:jsonld/type [:= "urpx:ModelCollection"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/name {:optional true} :string]
   [:urpx/collectionRole {:optional true} CollectionRole]
   [:urpx/collectsPricingModel
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:PricingModel"}
    [:vector [:or PricingModel ExclusiveRef]]]])

(def RatePlanVersion
  "v0.2.0: changeReason, serviceRole, publishedBy are now required; gains
   customerChoice, isDefault, hasModifierOptions, hasRateCase, supersedes,
   plus a set of date/dateTime metadata fields. We list the fields we consume
   plus the headline metadata."
  [:map
   [:jsonld/type [:= "urpx:RatePlanVersion"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/version       {:optional true} :string]
   [:urpx/versionNumber {:optional true} :string]
   [:urpx/changeReason  Ref]
   [:urpx/changeDescription {:optional true} :string]
   [:urpx/description   {:optional true} :string]
   [:urpx/name          {:optional true} :string]
   [:urpx/consolidatedFrom
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:PricingModel"}
    [:vector [:or PricingModel ExclusiveRef]]]
   ;; sh:minCount 0 sh:maxCount 1 on both version shapes, and it was NEVER
   ;; required upstream: optional at v0.2.1, v0.3.0, v0.4.0, v0.5.0 and
   ;; v0.5.1. clj-urpx required it by its own choice, so a conformant
   ;; snapshot omitting it was rejected.
   [:urpx/effectiveDateTime {:optional true} DateTime]
   [:urpx/effectiveDate     {:optional true} Date]
   [:urpx/expirationDate    {:optional true} Date]
   [:urpx/expirationDateTime {:optional true} DateTime]
   [:urpx/datePublished     {:optional true} Date]
   [:urpx/dateTimePublished {:optional true} DateTime]
   [:urpx/dateApproved      {:optional true} Date]
   [:urpx/dateTimeApproved  {:optional true} DateTime]
   [:urpx/priceCurrency     {:optional true} :string]
   ;; 0..N upstream (sh:minCount 0, no sh:maxCount); nothing in src/ reads it.
   [:urpx/commodity
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector Ref]]
   [:urpx/serviceRole       Ref]
   [:urpx/customerChoice    {:optional true} Ref]
   [:urpx/isDefault         {:optional true} :boolean]
   [:urpx/minimumTenure     {:optional true} IsoDuration]
   [:urpx/url               {:optional true} :string]
   [:urpx/offeredBy   {:optional true ::range "urpx:Organization"} AnyOrganization]
   [:urpx/hasDataSourceReference
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:DataSourceReference"}
    [:vector DataSourceReference]]
   [:urpx/hasEligibilityRule
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:EligibilityRule"}
    [:vector EligibilityRule]]
   [:urpx/hasRateCase
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:RateCase"}
    [:vector Ref]]
   ;; URPX Decisions #084 + #090: RPV-side pointers to optional Modifier(s)
   ;; and ModifierVersion(s). Both 0..None in SHACL — surfaced here so
   ;; coerce yields them as vectors of Ref maps.
   [:urpx/hasModifierOptions
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:RatePlanModifier"}
    [:vector Ref]]
   [:urpx/hasModifierVersionOptions
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:RatePlanModifierVersion"}
    [:vector Ref]]
   [:urpx/observesHoliday
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:Holiday"}
    [:vector Ref]]
   ;; v0.5.1 coverage machinery, both sh:minCount 1 sh:maxCount 1 upstream.
   [:urpx/forRatePlan {::range "urpx:RatePlan"} Ref]
   [:urpx/hasCoverageClaim {::range "urpx:CoverageClaim"} [:or CoverageClaim ExclusiveRef]]
   [:urpx/selfContained {:optional true} :boolean]
   [:urpx/hasPriceDefinitionReference
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:PriceDefinitionReference"}
    [:vector [:or PriceDefinitionReference ExclusiveRef]]]
   ;; v0.5.1 typed identity. hasIdentifier and hasLocationDataSet are 0..N
   ;; upstream; timezoneIdentifier is maxCount 1.
   [:urpx/hasIdentifier
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:RatePlanIdentifier"}
    [:vector [:or RatePlanIdentifier ExclusiveRef]]]
   [:urpx/hasLocationDataSet
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:LocationDataSet"}
    [:vector Ref]]
   [:urpx/timezoneIdentifier {:optional true} :string]
   [:urpx/hasPlanElements {::range "urpx:PlanElements"} PlanElements]
   [:urpx/hasPrices       {::range "urpx:Prices"} Prices]])

(def RatePlan
  "v0.2.0: ratePlanUniqueIdentifier is required (replaces v0.1.x
   ratePlanIdentifier). RatePlan gains many optional date/metadata fields;
   we list the ones the tests / consumers care about."
  [:map
   [:jsonld/type [:= "urpx:RatePlan"]]
   [:jsonld/id {:optional true} :string]
   [:jsonld/context {:optional true} Context]
   [:urpx/ratePlanUniqueIdentifier :string]
   [:urpx/name :string]
   [:urpx/description {:optional true} :string]
   [:urpx/serviceRole {:optional true} Ref]
   [:urpx/commodity
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector Ref]]
   [:urpx/offeredBy {:optional true ::range "urpx:Organization"} AnyOrganization]
   [:urpx/hasServiceClass
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:ServiceClass"}
    [:vector [:or ServiceClass ExclusiveRef]]]
   [:urpx/hasRateGroup
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:RateGroup"}
    [:vector [:or RateGroup ExclusiveRef]]]
   [:urpx/initialEffectiveDate Date]
   [:urpx/effectiveDate {:optional true} Date]
   [:urpx/expirationDate {:optional true} Date]
   [:urpx/expirationDateTime {:optional true} DateTime]
   [:urpx/timezoneIdentifier {:optional true} :string]
   [:urpx/priceCurrency {:optional true} :string]
   [:urpx/url           {:optional true} :string]
   [:urpx/version       {:optional true} :string]
   ;; v0.5.1 sh:minCount 0 on urpx-shacl:RatePlanShape. The v0.3.0 draft removed
   ;; this property outright; v0.5.1 re-admitted it as optional, so the nested
   ;; shape stays legal and an un-nested filing that states the link only as
   ;; forRatePlan on a sibling snapshot is legal too. NOT widened to 0..N yet:
   ;; SHACL sets no sh:maxCount, but urpx.index/nested-version and the callers
   ;; that go through it, urpx.price/plan-version, urpx.holidays/plan-calendar
   ;; and urpx.tz-inference/published-by, all read this as a single map, so a
   ;; vector here would
   ;; validate documents the resolver then silently misreads. Widening it means
   ;; rewriting those three readers first.
   ;; v0.5.1 typed identity. hasIdentifier and hasLocationDataSet are 0..N
   ;; upstream; timezoneIdentifier is maxCount 1.
   [:urpx/hasIdentifier
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:RatePlanIdentifier"}
    [:vector [:or RatePlanIdentifier ExclusiveRef]]]
   [:urpx/hasLocationDataSet
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:LocationDataSet"}
    [:vector Ref]]
   ;; 0..N upstream: sh:minCount 0 and no sh:maxCount. Accepts a single nested
   ;; snapshot or several. Deliberately NOT normalized to a vector by a decoder:
   ;; a filing nesting one snapshot coerces to a map, and forcing a vector would
   ;; change the coerced shape every consumer already reads. Readers go through
   ;; `urpx.index/nested-version`, which refuses to guess when there is more
   ;; than one rather than silently resolving against an arbitrary snapshot.
   [:urpx/hasRatePlanVersion {:optional true ::range "urpx:RatePlanVersion"} [:or RatePlanVersion [:vector RatePlanVersion]]]])

;; ---------------------------------------------------------------------------
;; Modifier — augments / overrides parts of an existing RatePlan
;; ---------------------------------------------------------------------------

(def RatePlanModifierVersion
  "v0.2.0: changeReason, serviceRole, publishedBy, modifierVersionNumber are
   now required. modifierIdentifier is gone — it moved to RatePlanModifier as
   modifierUniqueIdentifier."
  [:map
   [:jsonld/type [:= "urpx:RatePlanModifierVersion"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/modifierVersionNumber :string]
   [:urpx/version    {:optional true} :string]
   [:urpx/changeReason  Ref]
   [:urpx/changeDescription {:optional true} :string]
   [:urpx/description {:optional true} :string]
   ;; sh:minCount 0 sh:maxCount 1 on both version shapes, and it was NEVER
   ;; required upstream: optional at v0.2.1, v0.3.0, v0.4.0, v0.5.0 and
   ;; v0.5.1. clj-urpx required it by its own choice, so a conformant
   ;; snapshot omitting it was rejected.
   [:urpx/effectiveDateTime {:optional true} DateTime]
   [:urpx/effectiveDate {:optional true} Date]
   [:urpx/expirationDate {:optional true} Date]
   [:urpx/expirationDateTime {:optional true} DateTime]
   [:urpx/expectedEndDate     {:optional true} Date]
   [:urpx/expectedEndDateTime {:optional true} DateTime]
   [:urpx/priceCurrency {:optional true} :string]
   ;; 0..N upstream (sh:minCount 0, no sh:maxCount); nothing in src/ reads it.
   [:urpx/commodity
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector Ref]]
   [:urpx/serviceRole Ref]
   [:urpx/modifierType     {:optional true} Ref]
   [:urpx/modifierCategory {:optional true} Ref]
   [:urpx/offeredBy   {:optional true ::range "urpx:Organization"} AnyOrganization]
   [:urpx/hasDataSourceReference
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:DataSourceReference"}
    [:vector DataSourceReference]]
   [:urpx/hasEligibilityRule
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:EligibilityRule"}
    [:vector EligibilityRule]]
   [:urpx/hasRateCase
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:RateCase"}
    [:vector Ref]]
   ;; Single Ref (one base plan) or a vector of Refs (NSE/HRA-style modifiers).
   ;; Optional per SHACL (sh:minCount 0): standalone urpx:RatePlanModifier
   ;; documents whose target linkage isn't yet expressed in-document (CPAU
   ;; E-2-G shape) carry no applicableToRatePlan and rely on
   ;; consumer-side composition.
   [:urpx/applicableToRatePlan
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:RatePlan"}
    [:vector Ref]]
   [:urpx/applicableToRatePlanVersion
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:RatePlanVersion"}
    [:vector Ref]]
   ;; v0.5.1 coverage machinery, both sh:minCount 1 sh:maxCount 1 upstream.
   [:urpx/forRatePlanModifier {::range "urpx:RatePlanModifier"} Ref]
   [:urpx/hasCoverageClaim {::range "urpx:CoverageClaim"} [:or CoverageClaim ExclusiveRef]]
   [:urpx/selfContained {:optional true} :boolean]
   [:urpx/hasPriceDefinitionReference
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:PriceDefinitionReference"}
    [:vector [:or PriceDefinitionReference ExclusiveRef]]]
   ;; v0.5.1 typed identity. hasIdentifier and hasLocationDataSet are 0..N
   ;; upstream; timezoneIdentifier is maxCount 1.
   [:urpx/hasIdentifier
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:RatePlanModifierIdentifier"}
    [:vector [:or RatePlanModifierIdentifier ExclusiveRef]]]
   [:urpx/hasLocationDataSet
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:LocationDataSet"}
    [:vector Ref]]
   [:urpx/timezoneIdentifier {:optional true} :string]
   [:urpx/hasPlanElements {::range "urpx:PlanElements"} PlanElements]
   [:urpx/hasPrices       {::range "urpx:Prices"} Prices]])

(def RatePlanModifier
  "v0.2.0: modifierUniqueIdentifier is required (was per-version in v0.1.x).
   urpx:isOptional is gone."
  [:map
   [:jsonld/type [:= "urpx:RatePlanModifier"]]
   [:jsonld/id {:optional true} :string]
   [:jsonld/context {:optional true} Context]
   [:urpx/modifierUniqueIdentifier :string]
   [:urpx/name :string]
   [:urpx/offeredBy {:optional true ::range "urpx:Organization"} AnyOrganization]
   [:urpx/initialEffectiveDate Date]
   [:urpx/expirationDate     {:optional true} Date]
   [:urpx/expirationDateTime {:optional true} DateTime]
   [:urpx/timezoneIdentifier {:optional true} :string]
   [:urpx/priceCurrency      {:optional true} :string]
   [:urpx/url                {:optional true} :string]
   [:urpx/version            {:optional true} :string]
   ;; Optional for the same reason as RatePlan/hasRatePlanVersion above, and
   ;; likewise not yet widened to 0..N: urpx.holidays/plan-calendar,
   ;; urpx.tz-inference/published-by and urpx.price/plan-version read it as a
   ;; single map, the last through urpx.index/nested-version.
   ;; v0.5.1 typed identity. hasIdentifier and hasLocationDataSet are 0..N
   ;; upstream; timezoneIdentifier is maxCount 1.
   [:urpx/hasIdentifier
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:RatePlanModifierIdentifier"}
    [:vector [:or RatePlanModifierIdentifier ExclusiveRef]]]
   [:urpx/hasLocationDataSet
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:LocationDataSet"}
    [:vector Ref]]
   ;; 0..N upstream, same as RatePlan/hasRatePlanVersion above.
   [:urpx/hasRatePlanModifierVersion {:optional true ::range "urpx:RatePlanModifierVersion"} [:or RatePlanModifierVersion [:vector RatePlanModifierVersion]]]])

;; ---------------------------------------------------------------------------
;; Scenario cost models
;; ---------------------------------------------------------------------------

(defn- at-most-one
  "A 0..1 object property: one value, bare or as a list of at most one, the
   two forms urpx.index/single reads."
  [value-schema]
  [:or value-schema [:vector {:max 1} value-schema]])

(def ScenarioCostResult
  "The cost a scenario cost model produces."
  [:map
   [:jsonld/type [:= "urpx:ScenarioCostResult"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/name {:optional true} :string]
   [:urpx/hasCoverageClaim {:optional true ::range "urpx:CoverageClaim"}
    [:or CoverageClaim ExclusiveRef]]])

(def ScenarioCostModel
  "urpx-shacl:ScenarioCostModelShape as amended by urpx-org/urpx#72: sh:closed,
   with urpx:hasTemporalScope admitted through sh:ignoredProperties.

   :jsonld/context is admitted so a model published as a document of its own
   validates."
  [:map {:closed true}
   [:jsonld/type [:= "urpx:ScenarioCostModel"]]
   [:jsonld/id {:optional true} :string]
   [:jsonld/context {:optional true} Context]
   [:urpx/name        {:optional true} :string]
   [:urpx/description {:optional true} :string]
   [:urpx/forScenario {:optional true ::range "urpx:Scenario"} (at-most-one [:or Scenario ExclusiveRef])]
   [:urpx/hasCoverageClaim
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:CoverageClaim"}
    [:vector [:or CoverageClaim ExclusiveRef]]]
   [:urpx/consumesVersion
    {:optional true ::range "urpx:RatePlanVersion"}
    (at-most-one [:or RatePlanVersion ExclusiveRef])]
   [:urpx/consumesModifierVersion
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector [:or RatePlanModifierVersion ExclusiveRef]]]
   [:urpx/selectsProfileAlternative
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector [:or ProfileAlternative ExclusiveRef]]]
   [:urpx/producesResult {:optional true ::range "urpx:ScenarioCostResult"}
    (at-most-one [:or ScenarioCostResult ExclusiveRef])]
   [:urpx/hasTemporalScope
    {:optional true :decode/urpx-jsonld single-or-vec->vec}
    [:vector [:or TemporalScope ExclusiveRef]]]])

;; ---------------------------------------------------------------------------
;; Top-level wrappers carrying ontology-version metadata
;;
;; ---------------------------------------------------------------------------

(def URPXPackage
  "A package of URPX content, with its required urpx:targetURPXVersion.
   urpx:packages is the general containment pointer, beside urpx:hasRatePlan
   and urpx:hasRatePlanModifier. Read from urpx-shacl:URPXPackageShape at
   urpx-org/urpx v0.5.1."
  [:map
   [:jsonld/type [:= "urpx:URPXPackage"]]
   [:jsonld/id {:optional true} :string]
   [:jsonld/context {:optional true} Context]
   [:urpx/publishedBy {:optional true ::range "urpx:Organization"} AnyOrganization]
   [:urpx/publicationPlatform {:optional true} :string]
   [:urpx/dateReceived        {:optional true} Date]
   [:urpx/targetURPXVersion :string]
   [:urpx/name              {:optional true} :string]
   [:urpx/description       {:optional true} :string]
   [:urpx/version           {:optional true} :string]
   [:urpx/url               {:optional true} :string]
   [:urpx/timezoneIdentifier {:optional true} :string]
   [:urpx/priceCurrency     {:optional true} :string]
   [:urpx/dateCreated       {:optional true} Date]
   [:urpx/dateTimeCreated   {:optional true} DateTime]
   [:urpx/effectiveDate     {:optional true} Date]
   [:urpx/effectiveDateTime {:optional true} DateTime]
   [:urpx/expirationDate    {:optional true} Date]
   [:urpx/expirationDateTime {:optional true} DateTime]
   ;; Payload pointers: embedded entity or a Ref by @id.
   [:urpx/hasRatePlan
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:RatePlan"}
    [:vector :map]]
   [:urpx/hasRatePlanModifier
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:RatePlanModifier"}
    [:vector :map]]
   [:urpx/packages
    {:optional true :decode/urpx-jsonld single-or-vec->vec
     ::range #{"urpx:PricingModel" "urpx:RatePlan" "urpx:RatePlanModifier"
               "urpx:RatePlanModifierVersion" "urpx:RatePlanVersion"
               "urpx:SourcePublication" "urpx:TariffFiling"}}
    [:vector :map]]])

;; ---------------------------------------------------------------------------
;; Publications and filings
;;
;; urpx:SourcePublication is the external published source URPX cites, and
;; urpx:TariffFiling the URPX content unit. Both shapes are sh:closed upstream; clj-urpx does not enforce
;; closedness, so unmodeled fields flow through.
;; ---------------------------------------------------------------------------

(def SourcePublication
  "An external published source a URPX fact cites: a tariff book, a statement,
   a market price feed. Neither a super- nor a subclass of urpx:URPXPackage;
   they are siblings. urpx-shacl:SourcePublicationShape requires nothing."
  [:map
   [:jsonld/type [:= "urpx:SourcePublication"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/name             {:optional true} :string]
   [:urpx/description      {:optional true} :string]
   [:urpx/version          {:optional true} :string]
   [:urpx/url              {:optional true} :string]
   [:urpx/filingNumber     {:optional true} :string]
   [:urpx/issuingAuthority {:optional true} :string]
   [:urpx/pageReference    {:optional true} :string]
   [:urpx/publicationType  {:optional true} SourcePublicationType]
   [:urpx/publishedBy      {:optional true ::range "urpx:Organization"} AnyOrganization]
   [:urpx/dateApproved      {:optional true} Date]
   [:urpx/dateTimeApproved  {:optional true} DateTime]
   [:urpx/dateCreated       {:optional true} Date]
   [:urpx/dateTimeCreated   {:optional true} DateTime]
   [:urpx/dateModified      {:optional true} Date]
   [:urpx/dateTimeModified  {:optional true} DateTime]
   [:urpx/datePublished     {:optional true} Date]
   [:urpx/dateTimePublished {:optional true} DateTime]
   [:urpx/dateReceived      {:optional true} Date]
   [:urpx/dateTimeReceived  {:optional true} DateTime]
   [:urpx/effectiveDate     {:optional true} Date]
   [:urpx/effectiveDateTime {:optional true} DateTime]
   [:urpx/expirationDate    {:optional true} Date]
   [:urpx/expirationDateTime {:optional true} DateTime]])

(def TariffFiling
  "The URPX content unit of the v0.3.0 publication split, the half that carries
   pricing rather than citing a source.

   urpx:containsPricingModel is the shape's only required property and is
   unbounded."
  [:map
   [:jsonld/type [:= "urpx:TariffFiling"]]
   [:jsonld/id {:optional true} :string]
   [:urpx/containsPricingModel
    {:decode/urpx-jsonld single-or-vec->vec ::range "urpx:PricingModel"}
    [:vector [:or PricingModel ExclusiveRef]]]
   [:urpx/name        {:optional true} :string]
   [:urpx/description {:optional true} :string]
   [:urpx/publishedBy {:optional true ::range "urpx:Organization"} AnyOrganization]
   [:urpx/effectiveDate      {:optional true} Date]
   [:urpx/effectiveDateTime  {:optional true} DateTime]
   [:urpx/expirationDate     {:optional true} Date]
   [:urpx/expirationDateTime {:optional true} DateTime]
   [:urpx/dateModified       {:optional true} Date]
   [:urpx/dateTimeModified   {:optional true} DateTime]
   [:urpx/cancelledOn         {:optional true} Date]
   [:urpx/cancelledOnDateTime {:optional true} DateTime]
   [:urpx/cancelledBy {:optional true ::range #{"urpx:SourcePublication" "urpx:TariffFiling"}} :map]
   [:urpx/replacedBy  {:optional true ::range "urpx:TariffFiling"} :map]
   [:urpx/replaces    {:optional true ::range "urpx:TariffFiling"} :map]
   [:urpx/supersedes  {:optional true ::range "urpx:TariffFiling"} :map]
   [:urpx/cancelsPriceDefinition
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:PriceDefinition"}
    [:vector :map]]
   [:urpx/documentedBy
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:DataSourceReference"}
    [:vector [:or DataSourceReference ExclusiveRef]]]
   [:urpx/hasGeneralRule
    {:optional true :decode/urpx-jsonld single-or-vec->vec ::range "urpx:GeneralRule"}
    [:vector [:or GeneralRule ExclusiveRef]]]])

;; ---------------------------------------------------------------------------
;; Registry
;; ---------------------------------------------------------------------------

(defn- entity-map
  "The :map at the root of entity schema `s`, through a
   [:schema {:registry ...}] wrapper or an [:and ...] refinement; nil if
   there is none."
  [s]
  (let [s (m/deref (m/schema s))]
    (case (m/type s)
      :map s
      :and (some entity-map (m/children s))
      nil)))

(defn type-literals
  "The @type literals entity schema `s` admits: one for [:= \"urpx:X\"],
   each member of an :enum. nil for a schema with no :jsonld/type entry."
  [s]
  (when-let [mp (entity-map s)]
    (some (fn [[k _ child]]
            (when (= :jsonld/type k)
              (when (#{:= :enum} (m/type child))
                (vec (m/children child)))))
          (m/children mp))))

(defn- type->keyword
  "\"urpx:Season\" -> :urpx/Season; an @type with no prefix, :Season."
  [t]
  (let [[prefix local] (str/split t #":" 2)]
    (if local (keyword prefix local) (keyword prefix))))

(def schemas
  "Every entity schema in this namespace, keyed by its @type as a keyword:
   `:urpx/Season` -> `Season`. A schema that admits two @type spellings is
   under both."
  (into {}
        (for [[_ v] (ns-publics 'urpx.schema)
              :let [s @v]
              :when (vector? s)
              t (type-literals s)]
          [(type->keyword t) s])))

(def registry
  "Malli's default schemas plus `schemas`, for validating by name:

     (m/validate :urpx/Season season {:registry schema/registry})"
  (mr/composite-registry m/default-registry schemas))

(defn schema-for-type
  "The entity schema for @type `t` (\"urpx:Season\"), or nil if none is
   modeled or `t` is not a string."
  [t]
  (when (string? t) (get schemas (type->keyword t))))

(defn ranges
  "{property range} for each entry of entity schema `s` that states a
   ::range: a class, or a set of classes."
  [s]
  (into {}
        (keep (fn [[k props _]]
                (when-let [r (::range props)] [k r])))
        (some-> (entity-map s) m/children)))

(def ^:private ranges-for-type
  (memoize (fn [t] (some-> (schema-for-type t) ranges))))

(defn- types-of
  "The @type values of `x`: a string, a vector of them, or nil."
  [x]
  (cond (string? x)     [x]
        (sequential? x) (vec x)))

(defn- members
  "[path value] for each value `v` holds at `path`: the elements of a vector,
   the members of an RDF list, or `v` itself."
  [path v]
  (cond
    (vector? v) (into [] (map-indexed (fn [i x] [(conj path i) x])) v)
    (and (map? v) (contains? v :jsonld/list)) (members (conj path :jsonld/list)
                                                       (:jsonld/list v))
    :else [[path v]]))

(defn mistyped-references
  "Every reference and inline node in `node`, at any depth, that is not
   `in-range?` of the ::range of the property holding it, as

     {:in path :property k :range r :id @id-or-nil :types [@type ...]}

   where `r` is a class, or a set of classes.

   An inline node is judged by its own @type. A reference is judged by
   `(type-of id)`, the @type (a string or a vector) of the node the @id
   names, or nil when that node is unknown; a reference to an unknown node
   is not reported. `urpx.index/mistyped-references` supplies `type-of` from
   a set of documents."
  ([node type-of] (mistyped-references node type-of []))
  ([node type-of path]
   (let [rs (into {} (map ranges-for-type) (types-of (:jsonld/type node)))]
     (into []
           (mapcat
            (fn [[k v]]
              (when-not (= "jsonld" (namespace k))
                (mapcat
                 (fn [[p x]]
                   (when (and (map? x) (not (contains? x :jsonld/value)))
                     (let [r (get rs k)
                           ref? (and (contains? x :jsonld/id)
                                     (not (contains? x :jsonld/type)))
                           ts (if ref?
                                (types-of (type-of (:jsonld/id x)))
                                (types-of (:jsonld/type x)))]
                       (cond-> (if ref? [] (mistyped-references x type-of p))
                         (and r (seq ts) (not-any? #(in-range? % r) ts))
                         (conj {:in p :property k :range r
                                :id (:jsonld/id x) :types ts})))))
                 (members (conj path k) v)))))
           node))))

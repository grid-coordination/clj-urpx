(ns urpx.defs
  "Typed definitions. `(defurpx kind name doc? value)` defs `name` to
   `value` after validating the value against `(kind-schema kind)`; the var
   carries the kind, so `defs-of-kind` finds every definition of a kind in
   the namespaces a caller names.

     (defmethod defs/kind-schema :ex/rates [_]
       [:map-of :keyword decimal?])

     (defs/defurpx :ex/rates rtc-rates
       \"Cents per kWh.\"
       {:peak 16.10M :off-peak 8.05M})

     (defs/defs-of-kind :ex/rates ['my.corpus])   ; => [#'my.corpus/rtc-rates]

   The `urpx.kind` namespace is reserved for kinds clj-urpx defines; a
   library or corpus qualifies its kinds in its own namespace."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [malli.util :as mu]))

(defmulti kind-schema
  "The Malli schema a `defurpx` of `kind` must satisfy. A method is added
   per kind; the `:default` method refuses."
  identity)

(defmethod kind-schema :default
  [kind]
  (throw (ex-info (str "Unknown defurpx kind " (pr-str kind))
                  {:kind kind})))

(defn- location
  [{:keys [ns name file line]}]
  (str ns "/" name " (" file ":" line ")"))

(defn validate!
  "`value` if it satisfies `(kind-schema kind)` with every map in the schema
   closed (a map that says `{:closed false}` stays open, and a map reached
   through a `:ref` keeps its own `:closed` property). Otherwise throws
   ex-info whose message names `kind` and the definition at `where` (a map
   of `:ns`, `:name`, `:file` and `:line`), then one line per failing Malli
   `:in` path; the ex-data carries `:kind`, `:where` and the humanized
   `:errors`."
  [kind value where]
  (let [schema (mu/closed-schema (kind-schema kind))]
    (when-let [explanation (m/explain schema value)]
      (throw (ex-info (str "Invalid "
                           kind
                           " "
                           (location where)
                           "\n"
                           (str/join "\n"
                                     (for [{:keys [in value] :as error} (:errors explanation)]
                                       (str "  at "
                                            (pr-str in)
                                            ": "
                                            (pr-str value)
                                            " "
                                            (me/error-message error)))))
                      {:kind kind
                       :where where
                       :errors (me/humanize explanation)})))
    value))

(defn- parse-body
  "[doc value] from the forms after a `defurpx` name."
  [kind name body]
  (case (count body)
    1 [nil (first body)]
    2 (if (string? (first body))
        body
        (throw (ex-info (str "defurpx " name ": the docstring must be a string literal")
                        {:kind kind :name name})))
    (throw (ex-info (str "defurpx " name " takes a docstring and a value, got "
                         (count body)
                         " forms after the name")
                    {:kind kind :name name :forms (vec body)}))))

(defmacro defurpx
  "(defurpx kind name doc? value): def `name` to `value`, with var metadata
   `{:urpx/kind kind}`, the docstring, `:file` and `:line`.

   `kind` must be a keyword literal. At macroexpansion an unknown kind
   throws, then the value form is evaluated and validated with `validate!`,
   so an invalid value throws before the `def`, and a failed re-evaluation
   leaves an existing var as it was. The `def` evaluates the value form
   again."
  [kind name & body]
  (when-not (keyword? kind)
    (throw (ex-info (str "defurpx kind must be a keyword literal, not " (pr-str kind))
                    {:kind kind :name name})))
  (when-not (simple-symbol? name)
    (throw (ex-info (str "defurpx name must be a simple symbol, not " (pr-str name))
                    {:kind kind :name name})))
  (let [[doc value] (parse-body kind name body)
        line (:line (meta &form))
        where {:ns (ns-name *ns*)
               :name name
               :file *file*
               :line line}]
    (kind-schema kind)
    (validate! kind (eval value) where)
    `(def ~(vary-meta name
                      merge
                      (cond-> {:urpx/kind kind
                               :file *file*
                               :line line}
                        doc (assoc :doc doc)))
       ~value)))

(defn defs-of-kind
  "The vars interned in namespaces `nses` (namespaces or symbols) whose
   metadata names `kind` as their `:urpx/kind`, sorted by symbol."
  [kind nses]
  (->> nses
       (mapcat (comp vals ns-interns the-ns))
       (filter #(= kind (:urpx/kind (meta %))))
       (sort-by symbol)
       vec))

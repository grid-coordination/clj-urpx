(ns urpx.load
  "Turn SOURCES into coerced URPX documents.

  A source is anything `clojure.java.io/reader` coerces: a File, URL, Reader,
  InputStream or path string. Where those sources come from is the caller's
  business and deliberately not this library's: a corpus repository is a one-off
  and there will never be a layout everyone adopts, so walking a tree is
  client-specific while turning what it finds into documents is not.

  WHY THIS NAMESPACE EXISTS AT ALL. The pipeline it names was previously a
  four-step form in the README that every consumer composed by hand, and the
  first consumer to do so got it wrong in the one way that does not fail loudly:
  `mapv` where `mapcat` belongs, and no coercion step, which returned a
  plausible number rather than an error. Naming it makes that composition
  unavailable to make rather than documented against.

  It lives in its own namespace because it cannot live in either of the two it
  spans. `urpx.core` requires only data.json, java.io and string, and
  `urpx.coerce` requires only malli and `urpx.schema`; neither requires the
  other. Putting this in core would drag Malli into the parse layer, and putting
  it in coerce would make a coercion-only consumer pull in the parser."
  (:require [urpx.coerce :as coerce]
            [urpx.core :as core]
            [urpx.index :as index]))

(def ^:private payload-keys
  [:urpx/hasRatePlan :urpx/hasRatePlanModifier :urpx/hasTariffPublication :urpx/packages])

(defn- stub
  "`payload` as a `{:jsonld/id id}` reference. A payload with no @id cannot be
   referenced, and an empty map in its place would read as a reference to
   nothing, so that refuses."
  [package k payload]
  (if-let [id (:jsonld/id payload)]
    {:jsonld/id id}
    (throw (ex-info (str "Cannot reference the " (pr-str k) " payload of "
                         (pr-str (:jsonld/id package)) ": it has no @id, so the "
                         "package cannot name it.")
                    {:reason      :urpx.load/payload-without-id
                     :package-id  (:jsonld/id package)
                     :property    k
                     :payload-type (:jsonld/type payload)}))))

(defn- package-shell
  "The wrapper `entity` with each embedded payload replaced by a reference to
   it, keeping the wrapper's own properties, or nil when `entity` is not a
   wrapper. A wrapper whose payloads are already references is already a
   shell.

   The shell is what carries the package's own statements, urpx:timezoneIdentifier
   among them, past `expand-document-entities`, which returns the payloads alone."
  [entity]
  (when (core/wrapper-types (:jsonld/type entity))
    (reduce (fn [pkg k]
              (if-let [v (get pkg k)]
                (assoc pkg k (if (map? v)
                               (stub pkg k v)
                               (mapv #(stub pkg k %) (index/as-vec v))))
                pkg))
            entity payload-keys)))

(defn- reference?
  "True when `payload` is a bare `{:jsonld/id id}` reference: a package naming
   an entity stated elsewhere."
  [payload]
  (= #{:jsonld/id} (set (keys payload))))

(defn- expand
  "`expand-document-entities`, followed by the package shell when `entity` is a
   wrapper. A wrapper's by-reference payloads are dropped; the shell keeps the
   references."
  [entity]
  (if-let [shell (package-shell entity)]
    (conj (into [] (remove reference?) (core/expand-document-entities entity)) shell)
    (core/expand-document-entities entity)))

(def document-xf
  "A transducer from sources to coerced URPX documents.

      (into [] urpx.load/document-xf (my-file-seq))
      (eduction urpx.load/document-xf sources)   ; realises nothing until pulled

  Three steps, each load-bearing, and each one a way to be wrong by hand:

    `load-graph`                a document may hold several top-level entities,
                                so this is mapcat and not map. `load-rate-plan`
                                would be wrong here for a different reason: it
                                throws on a file carrying only a modifier, and
                                dropping those makes every composition vacuous.
    `expand-document-entities`  descends a urpx:URPXPackage wrapper. Without it
                                a wrapped filing contributes nothing. The
                                wrapper itself follows its payloads as a SHELL:
                                its own properties, with each embedded payload
                                replaced by a `{:jsonld/id id}` reference. That
                                is how a zone declared only on the package
                                reaches `urpx.arrangements`. An embedded payload
                                with no @id refuses
                                `:urpx.load/payload-without-id`. A by-reference
                                payload yields nothing beyond the shell.
    `coerce-doc`                strings become java.time values and BigDecimals.
                                Without it urpx:effectiveDateTime is still a
                                string, so anything comparing dates throws from
                                deep inside and anything that does NOT compare
                                them quietly answers a different question.

  ORDER-INDEPENDENT and lazy: the same sources in any order give the same set of
  documents, and `eduction` pulls one source at a time.

  `coerce-doc` returns nil for an entity type this library does not model, so
  this DROPS those silently. That is unavoidable in a transducer, which sees one
  element at a time, but it is not unobservable: use `scan` when a caller needs
  to assert what was discarded."
  (comp (mapcat core/load-graph)
        (mapcat expand)
        (keep coerce/coerce-doc)))

(defn documents
  "Every coerced URPX document reachable from `sources`, as a vector.
   `(into [] document-xf sources)`, named because that is what callers want."
  [sources]
  (into [] document-xf sources))

(defn scan
  "Like `documents`, but in ONE pass over `sources` and reporting what was
   discarded:

      {:urpx.load/documents   [<coerced document> ...]
       :urpx.load/uncoercible [{:jsonld/type t :jsonld/id id} ...]}

  THE DISCARD IS THE POINT. `document-xf` drops every entity this library does
  not model, such as urpx:ProgramEnrollment, urpx:SourcePublication and
  urpx:RateCase. That none of those is a version is luck rather than design,
  and the unlucky form of exactly this cost a whole rate plan once: a version
  stating only urpx:effectiveDate was dropped by a filter that read only
  urpx:effectiveDateTime, and nothing said so.

  So a caller can assert what it could not before:

      (empty? (filter #(str/ends-with? (:jsonld/type %) \"Version\")
                      (:urpx.load/uncoercible (scan sources))))

  Note that \"this library does not model it\" and \"it does not matter\" are
  different statements. urpx:ProgramEnrollment is modeled nowhere here; whether
  that matters is the caller's call, and it cannot be made at all while the
  discard is invisible.

  Entries carry `@type` and `@id` only, not the entity, so scanning a large
  corpus does not retain every graph it walked past."
  [sources]
  (reduce (fn [acc entity]
            (if-let [doc (coerce/coerce-doc entity)]
              (update acc :urpx.load/documents conj doc)
              (update acc :urpx.load/uncoercible conj
                      {:jsonld/type (:jsonld/type entity)
                       :jsonld/id   (:jsonld/id entity)})))
          {:urpx.load/documents [] :urpx.load/uncoercible []}
          (eduction (comp (mapcat core/load-graph)
                          (mapcat expand))
                    sources)))

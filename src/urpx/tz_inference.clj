(ns urpx.tz-inference
  "Best-effort timezone inference for URPX rate plans whose
  `urpx:timezoneIdentifier` is absent.

  This namespace is **opt-in** — `urpx.schedule` does not call it
  automatically. Callers that want plug-and-play timezone resolution
  compose inference with the declared field at the call site:

      (or (some-> plan :urpx/timezoneIdentifier ZoneId/of)
          (:zone (urpx.tz-inference/infer-zone plan)))

  Inference uses utility-identifying fields on the publishing
  Organization: `urpx:eiaId` (preferred — official US energy reporting
  code) and `urpx:legalName` (fallback). When the utility is unknown to
  the lookup tables, or known to operate across more than one IANA
  zone, `infer-zone` returns `nil` so the caller can fail loudly rather
  than silently pick one.

  The shipped tables only cover utilities used by the clj-urpx test
  suite; production deployments are expected to supply their own table
  (e.g. derived from EIA Form 861)."
  (:require [urpx.index :as index])
  (:import [java.time ZoneId]))

;; ---------------------------------------------------------------------------
;; Built-in lookup tables — only utilities that operate in a single IANA zone
;; ---------------------------------------------------------------------------

(def ^:private default-eia-id->zone
  {;; City of Palo Alto Utilities — California (Pacific only)
   "3203"  "America/Los_Angeles"
   ;; Pacific Gas & Electric Company — California (Pacific only)
   "14328" "America/Los_Angeles"})

(def ^:private default-legal-name->zone
  {"City of Palo Alto Utilities"     "America/Los_Angeles"
   "Pacific Gas & Electric Company"  "America/Los_Angeles"})

;; ---------------------------------------------------------------------------
;; Plan inspection
;; ---------------------------------------------------------------------------

(defn- published-by
  "Return the Organization (a map) that offers or published `plan`.

   v0.5.1 moved this key. urpx:publishedBy was sh:minCount 1 on the v0.2.x
   version shapes; at 95eb8e22 it is absent from them entirely, those shapes
   are sh:closed, and the ontology restricts urpx:publishedBy to PricingModel,
   SourcePublication, TariffFiling and URPXPackage. The successor on a version
   snapshot is urpx:offeredBy, sh:minCount 0 sh:maxCount 1.

   Precedence: offeredBy then publishedBy on a supplied `version` snapshot,
   then the two v0.2.x nested paths. Without the offeredBy arm, infer-zone
   returns nil on every v0.5.1 document and does so silently, which is why the
   snapshot arms come first."
  ([plan] (published-by plan nil))
  ([plan version]
   (let [snapshot (or version
                      (index/nested-version plan :urpx/hasRatePlanVersion)
                      (index/nested-version plan :urpx/hasRatePlanModifierVersion))]
     (or (:urpx/offeredBy   snapshot)
         (:urpx/publishedBy snapshot)))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn infer-zone
  "Best-effort timezone inference for a coerced urpx:RatePlan or
   urpx:RatePlanModifier.

   Returns nil when no reliable inference can be made (utility unknown
   to the tables, or known but multi-zone, or no identifying fields).
   Otherwise returns:

     {:zone java.time.ZoneId
      :inferred-from {:source <:eia-id|:legal-name>
                      :value  <the field value used>
                      :iana   <the IANA zone string>}}

   `opts` map (all optional):
     :eia-id->zone      map of EIA-ID string -> IANA zone string;
                        merged on top of the built-in table (caller
                        entries win over defaults).
     :legal-name->zone  map of legalName -> IANA zone string;
                        likewise merged.
     :version           a resolved version snapshot. v0.5.1 documents may
                        carry the snapshot top-level rather than nested, and
                        the organization key moved from urpx:publishedBy to
                        urpx:offeredBy on it. Without this, inference on such
                        a document finds no organization and returns nil.

   Inference does NOT consult `urpx:timezoneIdentifier` — that's the
   declared field, not inference. Callers compose:

       (or (some-> plan :urpx/timezoneIdentifier ZoneId/of)
           (:zone (infer-zone plan)))"
  ([plan] (infer-zone plan nil))
  ([plan opts]
   (let [eia-table  (merge default-eia-id->zone   (:eia-id->zone opts))
         name-table (merge default-legal-name->zone (:legal-name->zone opts))
         org        (published-by plan (:version opts))
         eia-id     (:urpx/eiaId org)
         legal-name (:urpx/legalName org)]
     (or (when-let [iana (get eia-table eia-id)]
           {:zone (ZoneId/of iana)
            :inferred-from {:source :eia-id :value eia-id :iana iana}})
         (when-let [iana (get name-table legal-name)]
           {:zone (ZoneId/of iana)
            :inferred-from {:source :legal-name :value legal-name :iana iana}})))))

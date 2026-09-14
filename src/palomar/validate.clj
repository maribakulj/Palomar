(ns palomar.validate
  "Validation gate — the *mechanism* an institution gates an ingest on: import a
   source, normalise it to the canonical floor, run the canonical `:validate` rule
   set (ADR 0003), and return the diagnostics with a pass/fail verdict under a
   failure policy. The reusable pipeline (import → normalize → validate → policy)
   is the deliverable; the rule *content* is whatever the canonical plugin ships
   (V1: one rule, `:title-required` — the set grows by justified need, ADR 0003).

   Validity is **not** loss. A record can be `VALID` here and still have lost
   fields in conversion — that is a separate, fully-modelled concern (ADR 0015,
   reported by `palomar.convert` / `palomar.loss-report`); this answers only \"does
   the canonical record satisfy the rules?\". Reads the source-spoke registry from
   `palomar.spokes` (no dependency on the export stack)."
  (:require [palomar.diagnostics :as dx]
            [palomar.plugins :as plug]
            [palomar.plugins.canonical :as canonical]
            [palomar.plugins.mapping :as mapping]
            [palomar.rules :as rules]
            [palomar.runtime :as runtime]
            [palomar.spokes :as spokes]))

(def ^:private pipeline [{:phase :normalize} {:phase :validate}])

(defn validate
  "Validate `source` (source spoke `from`) against the canonical rule set. Returns:

     {:records N :diagnostics [...] :summary {...} :failed? bool}

   `opts` is threaded to the importer (e.g. `:record-id`); `policy`
   (∈ `dx/failure-policies`, default `:errors-only`) decides `:failed?`. Throws on
   an unknown `from`. Note: this reports *validity*, not loss (see ns doc)."
  [{:keys [from source opts policy] :or {opts {} policy :errors-only}}]
  (when-not (contains? spokes/plugins from)
    (throw (ex-info "Unknown source format" {:from from :supported (spokes/source-formats)})))
  (let [plugin    (spokes/plugin from)
        registry  (-> plug/empty-registry
                      (plug/register plugin)
                      (plug/register canonical/plugin))
        compiled  (into (mapping/compile-mappings (plug/all-mappings registry)
                                                  (plug/effective-transforms registry))
                        (rules/compile-rules (plug/all-rules registry)))
        {:keys [records]} ((:importer plugin) opts source)
        validated (mapv #(:record (runtime/run-pipeline % compiled pipeline)) records)
        diags     (dx/collect-many validated)]
    {:records     (count validated)
     :diagnostics diags
     :summary     (dx/summary diags)
     :failed?     (dx/should-fail? diags policy)}))

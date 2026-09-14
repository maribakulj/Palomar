(ns palomar.convert
  "End-to-end conversion assembly (the institution-facing keystone): a source
   document in any supported spoke format → the WEMI/LRMoo pivot → a chosen target
   serialisation, with the ADR 0015 loss report over every edge.

   This is the namespace that wires the parts the rest of the system built and the
   convergence capstone proved compose: the five importers, the two projection
   rungs (INTERMARC's enriched `derive-wemi`, the floor `project` for the others), and
   the ten exporters. `convert` returns the output string plus the conversion
   loss report, in one call.

   Loss is collected at three points and merged: import-edge (a spoke's
   report-at-ingest, e.g. Dublin Core / MODS / IIIF unmodelled elements), the
   projection edge (canonical fields with no WEMI home, WEMI-derivation loss), and the
   export edge (what the chosen target cannot express). The per-edge, per-field
   account is exactly `palomar.loss-report`'s artifact."
  (:require [clojure.string :as str]
            [palomar.diagnostics :as dx]
            [palomar.loss-report :as lr]
            [palomar.plugins :as plug]
            [palomar.plugins.dc.export :as dc-export]
            [palomar.plugins.iiif.export :as iiif-export]
            [palomar.plugins.intermarc.wemi :as wemi]
            [palomar.plugins.lrmoo.crm :as crm]
            [palomar.plugins.lrmoo.export :as export]
            [palomar.plugins.lrmoo.linked-art :as linked-art]
            [palomar.plugins.lrmoo.project :as project]
            [palomar.plugins.mapping :as mapping]
            [palomar.plugins.marc21.export :as marc21-export]
            [palomar.plugins.mods.export :as mods-export]
            [palomar.runtime :as runtime]
            [palomar.spokes :as spokes]))

;; ---------------------------------------------------------------------------
;; Registries — the source spokes live in `palomar.spokes` (shared with
;; `palomar.validate`); convert owns the projection rung per spoke and the
;; target serialisations.
;; ---------------------------------------------------------------------------

(def ^:private to-pivots
  "Source format -> the projection that lifts a normalised record to WEMI.
   INTERMARC takes the *enriched* `derive-wemi` rung (the 145 $3 authority link) and
   then mints its authority-identified agent; INTERMARC-NG is already an entity-
   relation graph, so its importer builds the WEMI view and the projection is
   `identity` (ADR 0019); the floor spokes take `project`. Every spoke normalises
   to `:canon/*` first (in `to-wemi`)."
  {:intermarc    (comp wemi/with-identified-agent wemi/derive-wemi)
   :intermarc-ng identity        ; the WEMI graph is read by the importer, not projected
   :unimarc      project/project
   :dc           project/project
   :marc21       project/project
   :mods         project/project
   :iiif         project/project})

(defn- crm-only-losses [record]
  (concat (crm/crm-only-losses record) (export/export-losses record)))

(def exporters
  "Target format -> {:render <record -> string> :losses <record -> [loss]>}.
   Every renderer takes a projected WEMI record (which retains its canonical and
   native assertions, so the round-trip exporters read `:canon/*` off it)."
  {:ntriples   {:render export/->ntriples            :losses export/export-losses}
   :turtle     {:render export/->turtle              :losses export/export-losses}
   :jsonld     {:render export/->jsonld              :losses export/export-losses}
   :crm        {:render crm/->ntriples               :losses export/export-losses}
   :crm-only   {:render crm/->crm-only-ntriples      :losses crm-only-losses}
   :linked-art {:render linked-art/->jsonld          :losses linked-art/export-losses}
   :dc         {:render dc-export/->dc-xml           :losses dc-export/export-losses}
   :marc21     {:render marc21-export/->marcxml      :losses marc21-export/export-losses}
   :mods       {:render mods-export/->mods-xml       :losses mods-export/export-losses}
   :iiif       {:render iiif-export/->json           :losses iiif-export/export-losses}})

(defn source-formats [] (spokes/source-formats))
(defn target-formats [] (set (keys exporters)))

(defn- spoke-streamer [from]
  (and (contains? spokes/plugins from) (:stream-importer (spokes/plugin from))))

(defn streamable? [from] (boolean (spoke-streamer from)))

(defn streamable-sources
  "Source spokes whose importer can stream from a Reader (WP-7) — the MARC family."
  []
  (into (sorted-set) (filter streamable?) (source-formats)))

(defn stream-source
  "A **lazy** record seq for streamable spoke `from`, read from `readable` (a
   Reader) — the WP-7 bounded-memory input. The caller must keep `readable` open
   during consumption (e.g. `with-open`) and feed the seq to `convert-stream`
   (which `reduce`s it without retaining the head). Throws if `from` cannot stream."
  [from opts readable]
  (if-let [si (spoke-streamer from)]
    (si opts readable)
    (throw (ex-info "Spoke has no streaming importer"
                    {:from from :streamable (streamable-sources)}))))

;; ---------------------------------------------------------------------------
;; Pipeline
;; ---------------------------------------------------------------------------

(defn- compiled-mappings
  "The compiled normalize mappings for spoke `from` (the spoke's own mappings +
   transforms). Pure of the source — compile once, reuse per record."
  [from]
  (let [reg (plug/register plug/empty-registry (spokes/plugin from))]
    (mapping/compile-mappings (plug/all-mappings reg)
                              (plug/effective-transforms reg))))

(defn to-wemi
  "Import `source` through `spoke`, normalise to the canonical floor, then project
   each record to WEMI by the appropriate rung — INTERMARC's enriched `derive-wemi`
   (via the 145 $3 link) or the floor `project`. Both run normalize first, so
   `:canon/*` is populated for every spoke (the round-trip exporters read it).
   Returns `{:records [wemi…] :ingest [loss…]}`."
  [from opts source]
  (let [plugin   (spokes/plugin from)
        to-pivot (to-pivots from)
        {:keys [records diagnostics]} ((:importer plugin) opts source)
        compiled (compiled-mappings from)]
    {:ingest  diagnostics
     :records (mapv #(to-pivot (:record (runtime/run-phase % compiled :normalize))) records)}))

(defn convert
  "Convert `source` from spoke `from` to serialisation `to`. Returns
   `{:output String :loss <conversion-report> :records N}`.

   `opts` is threaded to the importer (e.g. `:record-id` for the single-record
   spokes). Throws with the supported sets on an unknown `from`/`to`."
  [{:keys [from to source opts] :or {opts {}}}]
  (when-not (contains? spokes/plugins from)
    (throw (ex-info "Unknown source format" {:from from :supported (source-formats)})))
  (when-not (contains? exporters to)
    (throw (ex-info "Unknown target format" {:to to :supported (target-formats)})))
  (let [{:keys [render losses]} (exporters to)
        {:keys [records ingest]} (to-wemi from opts source)
        projection-loss (dx/collect-many records)
        export-loss     (into [] (mapcat losses) records)
        output          (->> records
                             (into [] (comp (map render) (remove str/blank?)))
                             (str/join "\n"))]
    {:output  output
     :records (count records)
     :loss    (lr/conversion-report (concat ingest projection-loss export-loss)
                                    {:records (count records)})}))

(defn convert-report
  "Convenience: `convert` plus the human-readable loss report appended, for CLI /
   audit display."
  [request]
  (let [{:keys [loss] :as result} (convert request)]
    (assoc result :report (lr/format-conversion-report loss))))

(defn convert-stream
  "Streaming conversion (WP-7 / DoD #6): convert a reducible/seq `records` source —
   raw imported records of spoke `from` — to target `to`, calling `(emit doc)` with
   each rendered non-blank document and folding a **bounded** loss report. Returns
   `{:records N :loss <conversion-report>}`.

   Constant memory in the corpus size: unlike `convert`/`to-wemi` (which `mapv` the
   whole corpus into a vector), this `reduce`s the record stream, holding one record
   at a time plus a loss accumulator bounded by the distinct fields/categories/edges
   (`palomar.loss-report/accumulate`), never by N. It is sound to stream because the
   Work-id convergence across records is carried by the content-derived ids
   (ADR 0008), not by a global clustering pass — per-record conversion has no
   cross-record state (roadmap §10, 'converter → store': Palomar streams the triples,
   a store deduplicates by id).

   Edge scope: folds the per-record **projection** and **export** edges. Import-edge
   (report-at-ingest) loss is the importer's separate output, which a record stream
   does not carry; so the streamed report's per-edge / per-category / per-field
   counts equal the batch report's **iff the importer emits no ingest loss** — which
   holds for every streamable spoke (the MARC family importers report none; pinned by
   `convert-stream-test`). For a hypothetical streamable spoke with report-at-ingest
   loss the streamed report would under-count the import edge — fold the importer's
   `:diagnostics` into the same accumulator then. `:distinct-losses` (O(N)) is always
   batch-only. Throws on an unknown `from`/`to`."
  [{:keys [from to records]} emit]
  (when-not (contains? spokes/plugins from)
    (throw (ex-info "Unknown source format" {:from from :supported (source-formats)})))
  (when-not (contains? exporters to)
    (throw (ex-info "Unknown target format" {:to to :supported (target-formats)})))
  (let [to-pivot (to-pivots from)
        compiled (compiled-mappings from)
        {:keys [render losses]} (exporters to)
        result   (reduce
                  (fn [{:keys [n acc]} raw]
                    (let [wemi (to-pivot (:record (runtime/run-phase raw compiled :normalize)))
                          doc  (render wemi)]
                      (when-not (str/blank? doc) (emit doc))
                      {:n   (inc n)
                       :acc (lr/accumulate acc (concat (dx/collect wemi) (losses wemi)))}))
                  {:n 0 :acc lr/empty-acc}
                  records)]
    {:records (:n result)
     :loss    (lr/finalize (:acc result) {:records (:n result)})}))

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
            [palomar.plugins.eac :as eac]
            [palomar.plugins.ead :as ead]
            [palomar.plugins.lido :as lido]
            [palomar.plugins.events :as events]
            [palomar.plugins.oai :as oai]
            [palomar.ir :as ir]
            [palomar.loss-report :as lr]
            [palomar.model :as model]
            [palomar.plugins :as plug]
            [palomar.plugins.dc.export :as dc-export]
            [palomar.plugins.iiif.export :as iiif-export]
            [palomar.plugins.intermarc.wemi :as wemi]
            [palomar.plugins.lrmoo.crm :as crm]
            [palomar.plugins.lrmoo.dataset :as dataset]
            [palomar.plugins.lrmoo.export :as export]
            [palomar.plugins.lrmoo.linked-art :as linked-art]
            [palomar.plugins.lrmoo.project :as project]
            [palomar.plugins.lrmoo.prov :as prov]
            [palomar.plugins.mapping :as mapping]
            [palomar.plugins.marc21.archival :as marc21-archival]
            [palomar.plugins.marc21.authority :as marc21-authority]
            [palomar.plugins.marc21.object :as marc21-object]
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
   `identity` (ADR 0019); the floor spokes take `project`. The MARC family then
   adds its publication and production events (`events/add-events`, ADR 0036).
   Every spoke normalises to `:canon/*` first (in `to-wemi`)."
  {:intermarc    (comp events/add-events wemi/with-identified-agent wemi/derive-wemi)
   :intermarc-ng identity        ; the WEMI graph is read by the importer, not projected
   :unimarc      (comp events/add-events project/project)
   :dc           project/project
   :marc21       (comp events/add-events project/project)
   :mods         project/project
   :iiif         project/project
   :ead          ead/project
   :eac-cpf      eac/project
   :lido         lido/project})       ; a museum object: a CIDOC CRM E22 (ADR 0052)       ; an authority record: a RiC-O agent (ADR 0045)       ; archival units: no edition, so no Work (ADR 0039)

(defn- crm-only-losses [record]
  (concat (crm/crm-only-losses record) (export/export-losses record)))

(def exporters
  "Target format -> {:render <record -> string> :losses <record -> [loss]>}.
   Every renderer takes a projected WEMI record (which retains its canonical and
   native assertions, so the round-trip exporters read `:canon/*` off it).
   `:history? true` marks a target that records assertions rather than states
   them, and so receives the out-of-force ones too (ADR 0025): PROV-O, and the
   N-Quads/TriG datasets, which keep each status class in its own named graph
   (ADR 0029)."
  {:ntriples   {:render export/->ntriples            :losses export/export-losses}
   :turtle     {:render export/->turtle              :losses export/export-losses}
   :jsonld     {:render export/->jsonld              :losses export/export-losses}
   :crm        {:render crm/->ntriples               :losses export/export-losses}
   :crm-only   {:render crm/->crm-only-ntriples      :losses crm-only-losses}
   :linked-art {:render linked-art/->jsonld          :losses linked-art/export-losses :scopes linked-art/event-scopes
                :accepts #{:bibliographic :digital :object}}
   :dc         {:render dc-export/->dc-xml           :losses dc-export/export-losses
                :accepts #{:bibliographic :digital :archival-unit :object}
                ;; DC has no collection element of its own: one record stays a
                ;; DC document, several are wrapped to stay one XML document
                :collection ["<collection>" "</collection>"] :wrap-one? false}
   :marc21     {:render marc21-export/->marcxml      :losses marc21-export/export-losses
                :accepts #{:bibliographic :digital}
                :collection ["<collection xmlns=\"http://www.loc.gov/MARC21/slim\">" "</collection>"]}
   ;; archives (ADR 0050): every unit with its hierarchy, or the fonds alone
   :marc21-archival {:render marc21-archival/->marcxml :losses marc21-archival/export-losses
                     :accepts #{:archival-unit}
                     :collection ["<collection xmlns=\"http://www.loc.gov/MARC21/slim\">" "</collection>"]}
   :marc21-fonds    {:render marc21-archival/->fonds-marcxml :losses marc21-archival/fonds-losses
                     :accepts #{:archival-unit}
                     :collection ["<collection xmlns=\"http://www.loc.gov/MARC21/slim\">" "</collection>"]}
   ;; authorities (ADR 0051): EAC-CPF as MARC21 authority records
   :marc21-authority {:render marc21-authority/->marcxml :losses marc21-authority/export-losses
                      :accepts #{:authority}
                      :collection ["<collection xmlns=\"http://www.loc.gov/MARC21/slim\">" "</collection>"]}
   ;; museum objects (ADR 0053): a discovery record of the object
   :marc21-object {:render marc21-object/->marcxml :losses marc21-object/export-losses
                   :accepts #{:object}
                   :collection ["<collection xmlns=\"http://www.loc.gov/MARC21/slim\">" "</collection>"]}
   :mods       {:render mods-export/->mods-xml       :losses mods-export/export-losses
                :accepts #{:bibliographic :digital}
                :collection ["<modsCollection xmlns=\"http://www.loc.gov/mods/v3\">" "</modsCollection>"]}
   :iiif       {:render iiif-export/->json           :losses iiif-export/export-losses
                :accepts #{:bibliographic :digital :archival-unit}}
   :prov       {:render prov/->ntriples              :losses prov/export-losses :history? true}
   ;; datasets: every claim, each status class in its own named graph (ADR 0029)
   :nquads     {:render dataset/->nquads             :losses export/export-losses :history? true}
   :trig       {:render dataset/->trig               :losses export/export-losses :history? true}
   ;; the pivot itself, as EDN (ADR 0034): every record whole, nothing lost
   :edn        {:render ir/->edn-line                :losses (constantly [])      :history? true}})

(defn resource-type
  "What kind of resource `record` describes, as the targets see it: an
   archival unit (EAD), an archival authority — a person, a corporate body, a
   family (EAC-CPF) —, a digital presentation (a IIIF manifest) or, for every
   other spoke, a bibliographic description."
  [record]
  (case (:kind record)
    :archival-unit      :archival-unit
    :archival-authority :authority
    :iiif/manifest      :digital
    :lido/object        :object
    :bibliographic))

(defn source-resource-type
  "The `resource-type` spoke `from`'s records have (an OAI-PMH response, whose
   records have their format's, and EDN, which may hold any, are nil)."
  [from]
  (case from
    :ead     :archival-unit
    :eac-cpf :authority
    :iiif    :digital
    :lido    :object
    (:oai :edn) nil
    :bibliographic))

(def ^:private resource-names
  {:archival-unit "archival units (EAD)" :authority "authority records (EAC-CPF)"
   :digital "digital presentations (IIIF)" :object "museum objects (LIDO)"
   :bibliographic "bibliographic records"})

(defn accepts?
  "True when target `to` describes resources of `type` (`resource-type`). A
   record target (MARC21, MODS, DC, Linked Art, IIIF) states what it can say;
   an RDF, PROV-O or EDN target writes any resource's graph."
  [to type]
  (let [a (get-in exporters [to :accepts])]
    (or (nil? a) (contains? a type))))

(defn- check-accepts!
  "Throw when target `to` cannot describe one of `records`' resource types: a
   field-by-field projection of an archival unit or an authority onto a
   bibliographic format would give its fields a meaning they do not have
   (post-audit 3, E2)."
  [to records]
  (when-let [bad (seq (distinct (remove #(accepts? to %) (map resource-type records))))]
    (throw (ex-info (str (name to) " cannot describe " (str/join ", " (map resource-names bad))
                         ": its fields would say what the source does not (a cote is no LCCN, a date of"
                         " the documents no publication date). Targets that can: "
                         (str/join ", " (map name (sort (filter (fn [t] (every? #(accepts? t %) bad)) (keys exporters))))))
                    {:to to :resource-types (set bad)
                     :supported (set (filter (fn [t] (every? #(accepts? t %) bad)) (keys exporters)))}))))

(defn collection
  "`[open close]`, the element that makes several of target `to`'s XML
   documents one document (MARCXML `<collection>`, `<modsCollection>`), or nil
   for a target whose records are lines (N-Triples, JSON lines) or none
   (post-audit 3, E1: concatenated XML roots are no XML document)."
  [to]
  (get-in exporters [to :collection]))

(defn- wrap
  "`docs` (rendered, non-blank) as target `to`'s output: one XML document,
   in its collection element when it has one (a single DC record stays bare,
   `:wrap-one? false`), else one document per line."
  [to docs]
  (let [[open close] (collection to)]
    (if (and open (seq docs) (or (< 1 (count docs)) (get-in exporters [to :wrap-one?] true)))
      (str open "\n" (str/join "\n" docs) "\n" close)
      (str/join "\n" docs))))

(defn keeps-history?
  "True when target `to` receives every assertion whatever its status: it
   records them (PROV-O reifies each as a statement carrying its status) rather
   than stating them as facts (ADR 0025)."
  [to]
  (boolean (get-in exporters [to :history?])))

(defn source-formats
  "The source formats: the spokes, `:edn` (the pivot itself, ADR 0034) and
   `:oai`, an OAI-PMH response read through the spoke of its metadata format
   (ADR 0037)."
  []
  (conj (spokes/source-formats) :edn :oai))
(defn target-formats [] (set (keys exporters)))

(defn- spoke-streamer [from]
  (and (contains? spokes/plugins from) (:stream-importer (spokes/plugin from))))

(defn streamable? [from] (boolean (spoke-streamer from)))

(defn streamable-sources
  "Source spokes whose importer can stream from a Reader (WP-7) — the MARC family
   and LIDO (post-audit 5, G7)."
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

(defn- with-source-digest
  "`record` with the SHA-256 of the document it was read from on its provenance
   (`[:provenance :source :sha256]`, ADR 0031), unless it names another source
   or a digest already: every attestation inside it points into that exact
   document."
  [record source]
  (let [src (get-in record [:provenance :source])]
    (if (and (string? source) (or (nil? src) (and (map? src) (not (contains? src :sha256)))))
      (assoc-in record [:provenance :source :sha256] (model/sha256-hex source 64))
      record)))

(defn- pivot-imported
  "Normalise and project the `records` spoke `from` imported from `source`."
  [from source records]
  (let [to-pivot (to-pivots from)
        compiled (compiled-mappings from)]
    (mapv #(to-pivot (:record (runtime/run-phase (with-source-digest % source) compiled :normalize)))
          records)))

(defn- to-wemi*
  [from opts source]
  (let [{:keys [records diagnostics]} ((:importer (spokes/plugin from)) opts source)]
    {:ingest  diagnostics
     :records (pivot-imported from source records)}))

(defn- oai-to-wemi
  "An OAI-PMH response (ADR 0037): each record through the spoke of its
   metadata format (`:metadata-prefix` in `opts`, default `oai_dc`)."
  [opts source]
  (let [prefix (get opts :metadata-prefix "oai_dc")
        {:keys [records diagnostics deleted]} (oai/import-response prefix source)]
    {:ingest  diagnostics
     :records (pivot-imported (oai/formats prefix) source records)
     :deleted deleted}))

(defn to-wemi
  "Import `source` through `spoke`, normalise to the canonical floor, then project
   each record to WEMI by the appropriate rung — INTERMARC's enriched `derive-wemi`
   (via the 145 $3 link) or the floor `project`. Both run normalize first, so
   `:canon/*` is populated for every spoke (the round-trip exporters read it).
   From `:edn` the source is already the pivot (`palomar.ir`, ADR 0034): its
   records are read, validated, and returned as they were written.
   Returns `{:records [wemi…] :ingest [loss…]}`."
  [from opts source]
  (case from
    :edn {:ingest [] :records (ir/read-records source)}     ; already the pivot (ADR 0034)
    :oai (oai-to-wemi opts source)
    (to-wemi* from opts source)))

(defn- in-force-or-unstated? [a]
  (or (nil? (:status a)) (model/in-force? a)))

(defn- provenance-losses
  "The provenance a stating target does not carry (ADR 0033): where a record's
   values are anchored in its source (ADR 0031), only a history target writes
   that evidence; any other states the values without it — one `:dropped` loss
   of aspect `:provenance` per such record."
  [to record]
  (when (and (not (keeps-history? to)) (some #(seq (model/evidence-of %)) (:assertions record)))
    [(dx/loss {:category     :dropped
               :subject      (:id record)
               :edge         :export
               :aspect       :provenance
               :source-field :palomar/evidence
               :message      (str "where each value was read in the source is not written by the "
                                  (name to) " export (see --to prov, nquads or trig)")})]))

(defn scopes-of
  "The `:scopes` function of exporter `to` (`(fn [record output] …)`: where,
   in its output, each event of a record is written), or nil (post-audit 5,
   G5)."
  [to]
  (get-in exporters [to :scopes]))

(defn export-records
  "Render WEMI `records` (from `to-wemi`, possibly curated) to target `to`. Returns
   `{:output String :loss [export-loss…] :withheld n}`.

   The status policy is applied here, once, for every target (audit S08). An
   out-of-force assertion (`:rejected`, `:superseded`, `:retracted`) is never
   emitted. A target that states facts emits only facts by default (ADR 0042):
   `:asserted`/`:accepted` (or status-less) assertions. A pending claim
   (`:proposed`, `:needs-review`) — the string-keyed Work, an event, an
   alignment — has no way to say it is one in a plain RDF triple, so it is
   withheld, and `:withheld` counts them; `:with-proposals? true` emits them
   too. An entity that existed only through dropped claims is dropped with
   them (`model/filter-assertions`): an unconfirmed Work is not typed either.
   (`:in-force-only?`, which once asked for facts only, is now the default.)

   A history target (`keeps-history?`, the PROV-O record of provenance) is the
   exception: it states no fact, it records each assertion with its status, so
   it receives them all (ADR 0025)."
  ([to records] (export-records to records {}))
  ([to records {:keys [with-proposals? bare?]}]
   (when-not (contains? exporters to)
     (throw (ex-info "Unknown target format" {:to to :supported (target-formats)})))
   (check-accepts! to records)
   (let [{:keys [render losses]} (exporters to)
         facts?   (not (or (keeps-history? to) with-proposals?))
         withheld (if facts? (count (filter model/pending? (mapcat :assertions records))) 0)
         records  (mapv (cond
                          (keeps-history? to) identity
                          with-proposals?     model/without-out-of-force
                          :else               #(model/filter-assertions % in-force-or-unstated?))
                        records)]
     {:output   (let [docs (into [] (comp (map render) (remove str/blank?)) records)]
                  ;; a stream wraps once, around all its records (`collection`)
                  (if bare? (str/join "\n" docs) (wrap to docs)))
      :loss     (into [] (mapcat #(concat (losses %) (provenance-losses to %))) records)
      :withheld withheld})))

(defn convert
  "Convert `source` from spoke `from` to serialisation `to`. Returns
   `{:output String :loss <conversion-report> :records N :deleted [id…]}`,
   `:deleted` the identifiers an OAI-PMH response marks deleted (else empty).

   `opts` is threaded to the importer (e.g. `:record-id` for the single-record
   spokes). A target that states facts gets only facts unless
   `:with-proposals? true` (`export-records`, ADR 0042); the report's
   `:withheld-proposals` says how many claims that left out. A dataset target
   keeps them, each status in its graph. Throws with the supported sets on an
   unknown `from`/`to`."
  [{:keys [from to source opts with-proposals?] :or {opts {}}}]
  (when-not (contains? (source-formats) from)
    (throw (ex-info "Unknown source format" {:from from :supported (source-formats)})))
  (when-not (contains? exporters to)
    (throw (ex-info "Unknown target format" {:to to :supported (target-formats)})))
  (let [{:keys [records ingest deleted]} (to-wemi from opts source)
        projection-loss (dx/collect-many records)
        {output :output export-loss :loss withheld :withheld} (export-records to records {:with-proposals? with-proposals?})]
    {:output  output
     :records (count records)
     ;; an OAI response's deleted records (post-audit test 07): a synchroniser
     ;; must remove them, so they are part of the result, not dropped
     :deleted (vec deleted)
     :loss    (assoc (lr/conversion-report (concat ingest projection-loss export-loss)
                                           {:records (count records)})
                     :withheld-proposals withheld)}))

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

   Each document is emitted bare: for an XML target with a collection element,
   the caller writes `(collection to)` around the stream, once (post-audit 3, E1).

   Edge scope: folds the per-record **projection** and **export** edges. Import-edge
   (report-at-ingest) loss is the importer's separate output, which a record stream
   does not carry; so the streamed report's per-edge / per-category / per-field
   counts equal the batch report's **iff the importer emits no ingest loss** — which
   holds for every streamable spoke (the MARC family importers report none, and the
   LIDO stream carries its import-edge losses on its records; pinned by
   `convert-stream-test`). For a hypothetical streamable spoke with report-at-ingest
   loss the streamed report would under-count the import edge — fold the importer's
   `:diagnostics` into the same accumulator then. `:distinct-losses` (O(N)) is always
   batch-only. Throws on an unknown `from`/`to`."
  [{:keys [from to records with-proposals?]} emit]
  (when-not (contains? spokes/plugins from)
    (throw (ex-info "Unknown source format" {:from from :supported (source-formats)})))
  (when-not (contains? exporters to)
    (throw (ex-info "Unknown target format" {:to to :supported (target-formats)})))
  (let [to-pivot (to-pivots from)
        compiled (compiled-mappings from)
        result   (reduce
                  (fn [{:keys [n acc withheld]} raw]
                    (let [wemi (to-pivot (:record (runtime/run-phase raw compiled :normalize)))
                          {doc :output loss :loss w :withheld} (export-records to [wemi] {:with-proposals? with-proposals? :bare? true})]
                      (when-not (str/blank? doc) (emit doc))
                      {:n        (inc n)
                       :withheld (+ withheld w)
                       :acc      (lr/accumulate acc (concat (dx/collect wemi) loss))}))
                  {:n 0 :acc lr/empty-acc :withheld 0}
                  records)]
    {:records (:n result)
     :loss    (assoc (lr/finalize (:acc result) {:records (:n result)})
                     :withheld-proposals (:withheld result))}))

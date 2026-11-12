(ns palomar.api
  "The stable entry points of Palomar as a library (ADR 0034).

   Every other namespace is free to change between versions; these functions
   keep their names, arguments and result shapes, and a contract test pins
   them (`palomar.api-contract-test`). They are thin: each delegates to the
   namespace that does the work, whose docstring has the detail.

   The pipeline, end to end:

     (-> (api/pivot :marc21 {} xml)              ; source → pivot records
         :records
         (api/curate api/flag-all {:by \"me\"})   ; decide the pending claims
         :records
         (->> (api/export :trig)))               ; pivot → a target

   or, in one call, `convert`. The pivot can stop and resume as a file:
   `(api/->edn records)` writes it, `(api/pivot :edn {} doc)` reads it back,
   validated (`palomar.ir`)."
  (:require [palomar.convert :as convert]
            [palomar.curate :as curate]
            [palomar.fidelity :as fidelity]
            [palomar.ir :as ir]
            [palomar.loss-report :as lr]
            [palomar.manifest :as manifest]
            [palomar.model :as model]
            [palomar.reconciliation :as reconciliation]
            [palomar.validate :as validate]))

;; --- formats -----------------------------------------------------------------

(defn source-formats
  "The source formats `pivot` and `convert` read: the spokes, and `:edn`."
  []
  (convert/source-formats))

(defn target-formats
  "The target formats `export` and `convert` write."
  []
  (convert/target-formats))

(defn keeps-history?
  "True when target `to` records every claim with its status (PROV-O, the
   datasets, EDN) rather than stating the in-force ones as facts."
  [to]
  (convert/keeps-history? to))

;; --- the pipeline ------------------------------------------------------------

(defn pivot
  "Read `source` (a string) of format `from` into pivot records:
   `{:records [record…] :ingest [loss…]}` (plus `:deleted`, an OAI-PMH
   response's deleted identifiers). `opts` is threaded to the importer
   (`:record-id` for single-record sources)."
  [from opts source]
  (convert/to-wemi from opts source))

(defn export
  "Write pivot `records` to target `to`: `{:output string :loss [loss…]}`.
   `opts`: `:with-proposals? true` also states the proposed claims; by
   default a target that states facts gets facts only (ADR 0042)."
  ([to records] (convert/export-records to records))
  ([to records opts] (convert/export-records to records opts)))

(defn convert
  "Read and write in one call. `request`: `{:from :to :source :opts
   :with-proposals?}`. Returns `{:output string :records n :loss report :deleted [id…] :invalid {:records n :errors [...]}}`."
  [request]
  (convert/convert request))

(defn convert-stream
  "Convert a stream of raw `records` of a streamable spoke, calling `(emit doc)`
   per document, in bounded memory. Each document is bare: write
   `(collection to)` around the stream for an XML target. Returns `{:records n :loss report}`."
  [request emit]
  (convert/convert-stream request emit))

(defn collection
  "`[open close]`, the element that makes target `to`'s documents one XML
   document (MARCXML `<collection>`, MODS `<modsCollection>`), or nil."
  [to]
  (convert/collection to))

;; --- curation ----------------------------------------------------------------

(def accept-all "Policy: accept every pending claim." curate/accept-all)
(def reject-all "Policy: reject every pending claim." curate/reject-all)
(def flag-all "Policy: send every pending claim to review." curate/flag-all)

(defn accept-when
  "Policy: accept the pending claims satisfying `pred`, flag the rest."
  [pred]
  (curate/accept-when pred))

(defn confidence-at-least
  "A guard for `accept-when`: the claim states a confidence of at least `x`.
   A claim of no confidence never passes (review of 2026-11-09, L2)."
  [x]
  (curate/confidence-at-least x))

(defn curate
  "Decide the pending claims of `records` with `decide` (a policy, or a
   function of a claim returning `:accept`, `:reject`, `:review` or `[verdict
   reason]`), recording who and when from `context` (`{:by :at}`). Returns
   `{:records [..] :transitions [..] :summary {..}}`."
  ([records decide] (curate/curate records decide))
  ([records decide context] (curate/curate records decide context)))

(defn supersede
  "Replace the in-force claim `old` of `record` by `new`, for `reason`.
   Returns `{:record record' :transitions [..]}`."
  ([record old new reason] (curate/supersede record old new reason))
  ([record old new reason context] (curate/supersede record old new reason context)))

(defn history
  "The curation decisions recorded about claim `a` of `record`, oldest first."
  [record a]
  (curate/history record a))

;; --- checks and accounts -----------------------------------------------------

(defn validate
  "Validate a source against the canonical rules: `{:from :source :opts
   :policy}` → `{:records :diagnostics :summary :failed?}`."
  [request]
  (validate/validate request))

(defn valid-record?
  "True when `x` is a well-formed pivot record."
  [x]
  (model/valid-record? x))

(defn explain-record
  "Why `x` is not a well-formed pivot record (malli explanation), or nil."
  [x]
  (model/explain-record x))

(defn audit
  "Observe a conversion's output, value by value and relation by relation:
   `{:from :to :source :opts}` → `{:checked :present :reported :unaccounted
   :relations}`."
  [request]
  (fidelity/audit request))

(defn loss-report
  "The loss report of a set of loss diagnostics (`:by-edge`, `:by-aspect`, …)."
  [losses]
  (lr/conversion-report losses))

(defn manifest
  "The manifest of a conversion run: what its output depends on."
  [run]
  (manifest/manifest run))

;; --- reconciliation (lot N2, ADR 0058 amendment) ---------------------------

(defn read-authorities
  "The authority pool of file `path`, `{:entries :block :manifest}`: an
   authority index (`palomar authorities index`), looked up by its blocking
   keys (and kept as `:index`, which the name lane reads), or an authority file (MADS, SKOS, EAC-CPF, BnF INTERMARC
   authorities) read whole. A dump is refused: index it first."
  [path]
  (reconciliation/read-authorities path))

(defn reconciliation-model
  "The probabilistic engine's model, `{:model m :version {:path :sha256
   :bytes}}`: the shipped parameter file, or the one at `path`."
  ([] (reconciliation/read-model))
  ([path] (reconciliation/read-model path)))

(defn reconcile
  "Reconcile the agents of `records` against `pool` (`read-authorities`, a
   vector of entries, or nil). `opts`: `:engine` `:historical` (the
   default: the canonical floor's distinct names, by token-set similarity,
   as before) or `:probabilistic` (every mention of the batch, read from
   the source by `palomar.mentions`, scored by a Fellegi-Sunter model and
   decided at its thresholds); `:model`, a `reconciliation-model` result
   (default the shipped one); `:lanes` (lot O6), the probabilistic
   engine's candidate lanes (`[:name]`, the default, `[:identifier]` or
   both; the historical engine refuses any but `[:name]`). Returns `{:engine :model :pool? :agents
   :decisions [..]}`; a probabilistic decision is one per mention, and
   states a `:confidence` only where the model's calibration proves it.
   See `palomar.reconciliation/reconcile`."
  ([records pool] (reconciliation/reconcile records pool))
  ([records pool opts] (reconciliation/reconcile records pool opts)))

(defn reconcile-explain
  "Why the probabilistic engine decided mention `mention-id` of `records`
   as it did: its candidates, each with its comparison levels, the weight
   of each field, their total, the posterior, the decision and why; the
   prior and the thresholds. With `:lanes`, each candidate names the lanes
   that found it (`:generated-by`). See `palomar.reconciliation/explain`."
  ([records pool mention-id] (reconciliation/explain records pool mention-id))
  ([records pool mention-id opts] (reconciliation/explain records pool mention-id opts)))

(defn reconcile-clusters
  "The mentions of `records` clustered into persons (`palomar.agent-cluster`)
   from their probabilistic decisions against `pool`, each cluster with its
   members' occurrences and the decisions that joined them. See
   `palomar.reconciliation/clusters`."
  ([records pool] (reconciliation/clusters records pool))
  ([records pool opts] (reconciliation/clusters records pool opts)))

(defn format-reconciliation
  "The text `palomar reconcile` writes for a `reconcile` result."
  [result]
  (reconciliation/format-reconciliation result))

(defn format-explanation
  "The text `palomar reconcile explain` writes for a `reconcile-explain` result."
  [explanation]
  (reconciliation/format-explanation explanation))

(defn format-clusters
  "The text `palomar reconcile clusters` writes for a `reconcile-clusters` result."
  [result]
  (reconciliation/format-clusters result))

;; --- the pivot as a file -----------------------------------------------------

(def ir-version "The version of the EDN IR `->edn` writes." ir/version)

(defn ->edn
  "Pivot `records` as an EDN IR document (one envelope per line)."
  [records]
  (ir/->edn records))

(defn read-edn
  "The pivot records of an EDN IR document, validated."
  [doc]
  (ir/read-records doc))

(defn ir-schema
  "The published shape of a pivot record, as a malli form (data)."
  []
  (ir/schema-form))

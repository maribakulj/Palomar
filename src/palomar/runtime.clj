(ns palomar.runtime
  "Rule execution engine.

   Applies a compiled rule set to a record, collects productions
   (assertions, diagnostics, repair proposals, projection intents), and
   merges them back into the record with full provenance traceability.

   Runtime contract (ADR 0004, ADR 0020):
   - Rules execute only in their declared phase.
   - Each phase is a single pass: every rule whose :phase matches fires
     once, against the record as it entered the phase. There is no
     multi-cycle iteration and no fixpoint in V1 — chained inference is
     expressed by ordering rules across phases.
   - Productions are deduplicated at merge by structural identity
     (ADR 0008), so two rules deriving the same fact in one pass collapse.
   - Every merged assertion / diagnostic carries provenance pointing to
     the rule and phase that produced it (set by the rule compiler).

   What the runtime merges:
   - `:assertion` productions → appended to `record[:assertions]`.
   - `:diagnostic` productions → appended to `record[:diagnostics]`.
   - `:entity` productions → appended to `record[:entities]`, deduplicated by
     id (ADR 0014/0017). A re-mint of the same content-derived id is a no-op.
   - `:repair` productions → not merged into the record in V1; authors
     that want repairs attached to a diagnostic place them inline in
     the diagnostic template's `:repairs` vector. Standalone repairs
     stay accessible via the returned `:productions` trace.

   Out of scope in this engine:
   - Cross-record execution, retract semantics, convergence detection,
     projection."
  (:require [malli.core :as m]
            [palomar.rules :as rules]))

;; ---------------------------------------------------------------------------
;; Rule filtering
;; ---------------------------------------------------------------------------

(defn rules-for-phase
  "Return the subset of compiled rules whose :phase equals `phase`."
  [compiled-rules phase]
  (filterv #(= phase (:phase %)) compiled-rules))

;; ---------------------------------------------------------------------------
;; Merging productions into a record
;;
;; Productions are deduplicated at merge time by structural identity
;; (ADR 0008). The first occurrence wins; subsequent identical productions
;; are no-ops on the record but remain visible in the run's production
;; trace.
;; ---------------------------------------------------------------------------

(defn assertion-identity
  "Identity key for assertion deduplication: subject + predicate + value
   + status. Provenance and confidence are intentionally excluded — they
   describe who/how, not what (ADR 0008)."
  [a]
  [(:subject a) (:predicate a) (:value a) (:status a)])

(defn diagnostic-identity
  "Identity key for diagnostic deduplication: subject + code + severity
   + message. Repairs and provenance are excluded (ADR 0008)."
  [d]
  [(:subject d) (:code d) (:severity d) (:message d)])

(defn entity-identity
  "Identity key for entity deduplication: the entity id alone. Identity is
   content-derived (ADR 0016), so the same entity minted independently from
   two records shares an id and a re-mint is a no-op at merge (ADR 0008)."
  [e]
  (:id e))

(defn- dedup-append [record k item identity-fn]
  (let [existing      (get record k [])
        existing-keys (into #{} (map identity-fn) existing)]
    (if (contains? existing-keys (identity-fn item))
      record
      (update record k (fnil conj []) item))))

(defn- merge-production [record production]
  (case (:kind production)
    :assertion  (dedup-append record :assertions  (:value production) assertion-identity)
    :diagnostic (dedup-append record :diagnostics (:value production) diagnostic-identity)
    :entity     (dedup-append record :entities    (:value production) entity-identity)
    :repair     record  ;; standalone repairs not merged in V1
    record))

(defn merge-productions
  "Apply each production to `record` in order. Identical productions
   collapse: the first occurrence is kept, subsequent ones are no-ops on
   the record. Returns the enriched record."
  [record productions]
  (reduce merge-production record productions))

;; ---------------------------------------------------------------------------
;; Phase execution — a single pass over the rules for one phase
;; ---------------------------------------------------------------------------

(defn run-phase
  "Run every rule for `phase` against `record` exactly once — a single pass
   (ADR 0004, ADR 0020). Returns a map `{:record enriched-record
   :productions [...]}`. All matching rules fire against the record as it
   entered the phase; their productions are merged together afterwards and
   deduplicated by structural identity (ADR 0008)."
  [record compiled-rules phase]
  (let [rs          (rules-for-phase compiled-rules phase)
        productions (into [] (mapcat #(rules/apply-rule % record)) rs)]
    {:record      (merge-productions record productions)
     :productions productions}))

;; ---------------------------------------------------------------------------
;; Pipeline execution
;; ---------------------------------------------------------------------------

(def PhaseSpec
  [:map
   [:phase [:enum :ingest :normalize :validate :infer :repair]]])

(def Pipeline
  [:vector PhaseSpec])

(defn valid-pipeline?  [p] (m/validate Pipeline p))
(defn explain-pipeline [p] (m/explain Pipeline p))

(defn- validate-pipeline-or-throw! [pipeline]
  (when-let [err (explain-pipeline pipeline)]
    (throw (ex-info "Invalid pipeline" {:explanation err}))))

(defn run-pipeline
  "Run a pipeline — a vector of `{:phase ...}` maps — against the record.
   Phases execute in declared order, each as a single pass. Returns a map
   `{:record ... :productions [...]}` accumulated across every phase."
  [record compiled-rules pipeline]
  (validate-pipeline-or-throw! pipeline)
  (loop [record         record
         all-prod       []
         [spec & more]  pipeline]
    (if-not spec
      {:record record :productions all-prod}
      (let [{:keys [record productions]}
            (run-phase record compiled-rules (:phase spec))]
        (recur record (into all-prod productions) more)))))

;; Provenance is carried on every merged assertion and diagnostic
;; (`[:provenance :rule]` / `[:provenance :pass]`, set by the rule compiler).
;; Callers that need to filter or summarise by rule/phase read those keys
;; directly — there is no production consumer for a dedicated trace API.

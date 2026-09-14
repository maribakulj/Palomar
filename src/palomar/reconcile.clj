(ns palomar.reconcile
  "Cross-record agent reconciliation (ADR 0018, a first brick): given a batch of
   records, collapse the per-record identified-agent entities (`:crm/E21_Person`
   with an `:iri`) that share an authority identifier into one reconciled agent.

   This is reconcile-to-authority done *certified*: the blocking key is the
   authority id (an ISNI), so the dedup is exact and D7-`:asserted` — no string
   matching, no recall ceiling (contrast `docs/eval/entity-resolution.md`, where
   string keys under-merge). It is deliberately the *measured-true* path: where
   two records carry the same ISNI they are the same person, full stop; where they
   carry only a name string they are left unmerged (that is the ADR 0018 fuzzy
   tier, not done here).

   The output is the agent *store* — distinct agents, each with the records that
   mention it — not a rewrite of the records; merging records onto a shared agent
   node is a downstream step.

   The fuzzy tier (ADR 0018 decisions 3/4) is `propose-agent-links`: for agents
   that have only a *name* (no authority id), it proposes equivalences to an
   authority pool by name similarity — always `:proposed` (never `:asserted`,
   D7), confidence-scored and revisable. A match is `:certifiable?` only when the
   authority entry carries a determinate id; a perfect name match to an id-less
   entry (the Victor Hugo *metro station*) can never be promoted."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [palomar.text :as text]))

(defn- agent-label
  "The record's controlled agent name (`:canon/agent`), used as the reconciled
   agent's display label."
  [record]
  (some #(when (and (= :canon/agent (:predicate %)) (string? (:value %))) (:value %))
        (:assertions record)))

(defn- record-agents
  "`{:iri :id :label :record}` for each identified agent entity of `record`."
  [record]
  (let [label (agent-label record)]
    (for [e (:entities record)
          :when (and (= :crm/E21_Person (:kind e)) (:iri e))]
      {:iri (:iri e) :id (:id e) :label label :record (:id record)})))

(defn reconcile-agents
  "Reconcile identified agents across `records` by authority id (ISNI). Returns:

     {:agents   [{:iri :id :label :records [record-id…] :mentions N} …]  ; one per id
      :distinct D     ; distinct authority-identified agents
      :mentions M      ; total identified-agent mentions across the batch
      :records  R}     ; records carrying an identified agent

   Records with no identified agent (no `:crm/E21_Person` entity) contribute
   nothing — only the authority-certified agents are reconciled here."
  [records]
  (let [mentions (mapcat record-agents records)
        by-iri   (group-by :iri mentions)]
    {:agents   (vec (for [[iri ms] (sort-by key by-iri)]
                      {:iri      iri
                       :id       (:id (first ms))
                       :label    (some :label ms)
                       :records  (mapv :record ms)
                       :mentions (count ms)}))
     :distinct (count by-iri)
     :mentions (count mentions)
     :records  (count (distinct (map :record mentions)))}))

(defn format-agent-reconciliation
  "Human-readable rendering of `reconcile-agents` output, for CLI / audit."
  [{:keys [agents distinct mentions records]}]
  (if (zero? mentions)
    "Agent reconciliation: no authority-identified agents."
    (str/join
     "\n"
     (cons (str "Agent reconciliation — " mentions " mention" (when (not= 1 mentions) "s")
                " across " records " record" (when (not= 1 records) "s")
                " reconciled to " distinct " distinct agent" (when (not= 1 distinct) "s")
                " (by authority id):")
           (for [{:keys [label iri mentions]} agents]
             (str "  " (or label "?") "  <" iri ">  ×" mentions))))))

;; ---------------------------------------------------------------------------
;; Fuzzy tier (ADR 0018 decisions 3/4) — name → authority *proposals*
;; ---------------------------------------------------------------------------

(defn- tokens
  "Normalised token set of a name (`text/norm` then split on whitespace)."
  [s]
  (set (remove str/blank? (str/split (text/norm s) #" "))))

(defn- jaccard [a b]
  (let [u (count (set/union a b))]
    (if (pos? u) (/ (double (count (set/intersection a b))) u) 0.0)))

(defn- name-score
  "Best token-set similarity of `name` against a candidate's preferred label and
   its variants — order- and partial-tolerant (\"Gustave Flaubert\" ~ \"Flaubert,
   Gustave\"; \"Balzac\" ~ a \"Balzac\" variant)."
  [name candidate]
  (let [nt (tokens name)]
    (apply max 0.0 (map #(jaccard nt (tokens %))
                        (remove str/blank? (cons (:label candidate) (:variants candidate)))))))

(defn propose-agent-links
  "Fuzzy tier (ADR 0018 d.3/4): for each free agent `name` (no authority id),
   propose equivalences to entries of the `authority` pool — `[{:id :label
   :variants [..]}]` — whose label/variants match by token-set similarity ≥
   `threshold` (default 0.5). Returns proposals sorted by score desc:

     {:name :authority-id :authority-label :score :certifiable? :status :proposed}

   Every proposal is `:proposed` — never `:asserted` (D7). `:certifiable?` is true
   only when the matched entry has a determinate `:id`; a high-scoring match to an
   id-less entry can never be promoted (the metro-station guard)."
  ([names authority] (propose-agent-links names authority 0.5))
  ([names authority threshold]
   (->> (for [name names
              cand authority
              :let [score (name-score name cand)]
              :when (>= score threshold)]
          {:name            name
           :authority-id    (:id cand)
           :authority-label (:label cand)
           :score           score
           :certifiable?    (boolean (:id cand))
           :status          :proposed})
        (sort-by (comp - :score))
        vec)))

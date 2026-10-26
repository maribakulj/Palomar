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
   mention it. `apply-agent-links` is the rewrite step: it attaches an accepted,
   certifiable name→authority link to every record naming that creator, as the
   same authority-identified agent entity, so the records share one agent node
   and `reconcile-agents` sees them as one person (audit S29).

   The fuzzy tier (ADR 0018 decisions 3/4) is `propose-agent-links`: for agents
   that have only a *name* (no authority id), it proposes equivalences to an
   authority pool by name similarity — always `:proposed` (never `:asserted`,
   D7), confidence-scored and revisable. A match is `:certifiable?` only when the
   authority entry carries a determinate id; a perfect name match to an id-less
   entry (the Victor Hugo *metro station*) can never be promoted."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [palomar.model :as model]
            [palomar.plugins.agents :as agents]
            [palomar.plugins.lrmoo.ntriples :as nt]
            [palomar.text :as text]))

(defn- agent-label
  "The record's controlled creator name (`:canon/agent` with role `:creator`, or
   with no stated role), used as the reconciled agent's display label. A
   contributor or publisher never labels the identified creator."
  [record]
  (first (agents/creators record)))

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

;; ---------------------------------------------------------------------------
;; Alignments in the IR (ADR 0032) — proposals as SKOS mapping assertions
;; ---------------------------------------------------------------------------

(defn format-proposals
  "Human-readable rendering of `propose-agent-links` proposals, for the CLI:
   one line per proposal, the strongest first, saying which an alignment would
   make an exact match (ADR 0032)."
  [proposals]
  (if (empty? proposals)
    "Authority proposals: none above the threshold."
    (str/join "\n"
              (cons (str "Authority proposals: " (count proposals))
                    (for [{:keys [name authority-id authority-label score certifiable?]} proposals]
                      (str "  " (pr-str name) " → " (pr-str authority-label) " <" authority-id ">"
                           (format "  %.2f" (double score))
                           (if (and certifiable? (>= score 1.0)) "  exact" "  close")))))))

(def exact-score
  "A proposal scoring at least this is put forward as `skos:exactMatch`;
   below it, `skos:closeMatch` (ADR 0032)."
  1.0)

(defn- named-agent
  "The local agent entity standing for the name `nm` on record `rid`: a Person
   the record names, not yet identified (its id is scoped to the record, as
   two records' homonyms are not the same person by name alone)."
  [rid nm]
  (model/entity {:id   (model/mint-entity-id :crm/E21_Person [rid nm])
                 :kind :crm/E21_Person}))

(defn align-agents
  "Put `propose-agent-links` proposals into `records` as data (ADR 0032): for
   each record naming a proposal's `:name`, a local agent entity for that name
   (labelled with it), the authority entity (its `:iri` the authority id), and a
   `:proposed` assertion between them — `:skos/exactMatch` for a certifiable
   proposal scoring `exact-score`, `:skos/closeMatch` otherwise, the score as
   confidence and the authority's label among its bindings. The authority is
   the mapping's target, not an identified person: it is no `:crm/E21_Person`
   until a curator accepts the match and `apply-agent-links` applies it. Curation then accepts or rejects it like any claim; nothing is
   ever an `owl:sameAs`, and a rejected alignment stays in the record's history.
   Returns the records."
  [records proposals]
  (let [by-name (group-by :name proposals)]
    (mapv
     (fn [r]
       (let [rid   (:id r)
             hits  (for [{nm :name} (agents/occurrences r)
                         p (get by-name nm)
                         :when (and (:authority-id p) (nil? (nt/iri-problem (:authority-id p))))]
                     p)]
         (reduce
          (fn [r {:keys [name authority-id authority-label score certifiable?]}]
            (let [local (named-agent rid name)
                  ;; the authority as the alignment's target only: not an E21,
                  ;; since a proposal must not make an identified person
                  auth  (model/entity {:id (model/mint-entity-id :reconcile/authority authority-id)
                                       :kind :reconcile/authority :iri authority-id})
                  prov  (model/provenance {:pass :infer :rule :reconcile/propose-agent-link :derivation [rid]})
                  pred  (if (and certifiable? (>= score exact-score)) :skos/exactMatch :skos/closeMatch)
                  add   (fn [r e] (if (some #(= (:id e) (:id %)) (:entities r)) r (update r :entities (fnil conj []) e)))]
              (-> r
                  (add local)
                  (add auth)
                  (update :assertions (fnil into [])
                          [(model/assertion {:subject (:id local) :predicate :crm/P190_has_symbolic_content
                                             :value name :status :asserted :provenance prov})
                           (model/assertion {:subject (:id local) :predicate pred
                                             :value (model/reference (:id auth)) :status :proposed
                                             :confidence (double score)
                                             :provenance (cond-> prov authority-label (assoc :bindings {'?label authority-label}))})]))))
          r hits)))
     records)))

(defn accepted-links
  "The alignments a curator accepted in `records`, as `apply-agent-links` links:
   an `:accepted` (or `:asserted`) `:skos/exactMatch` from a record's local
   agent to an authority entity. A close match is never a link: it says the two
   are alike, not the same."
  [records]
  (vec (distinct
        (for [r records
              :let [ents  (into {} (map (juxt :id identity)) (:entities r))
                    names (into {} (for [a (:assertions r)
                                         :when (= :crm/P190_has_symbolic_content (:predicate a))]
                                     [(:subject a) (:value a)]))]
              a (:assertions r)
              :when (and (= :skos/exactMatch (:predicate a)) (#{:accepted :asserted} (:status a)))
              :let [auth (ents (:value/target (:value a)))]
              :when (and auth (:iri auth) (names (:subject a)))]
          {:name (names (:subject a)) :authority-id (:iri auth) :certifiable? true :status :accepted}))))

(defn apply-agent-links
  "Rewrite `records` onto shared agent nodes from `links`: `propose-agent-links`
   proposals that a curator has accepted (`:status :accepted`, or `:asserted`).
   For each record whose creator is a link's `:name` and that has no identified
   agent yet, add the `:crm/E21_Person` entity of the authority id (its id minted
   from the id, as the INTERMARC spoke does, so every record names the same node).

   Nothing else is applied, and every link left out is returned under `:refused`
   with a `:reason`: `:not-accepted` (a bare proposal is never applied, D7),
   `:not-certifiable` (no determinate authority id: the metro-station guard),
   `:invalid-iri` (an authority id that is no IRI, which would corrupt every RDF
   output) or `:ambiguous` (one name accepted for several authority ids). Returns
   `{:records [..] :applied [{:record :name :iri}] :refused [link…]}`."
  [records links]
  (let [accepted?   #(contains? #{:accepted :asserted} (:status %))
        certifiable #(boolean (and (:certifiable? %) (:authority-id %)))
        valid-iri?  #(nil? (nt/iri-problem (:authority-id %)))
        candidates  (filter #(and (accepted? %) (certifiable %) (valid-iri? %)) links)
        ambiguous   (set (for [[nm ls] (group-by :name candidates)
                               :when (< 1 (count (distinct (map :authority-id ls))))]
                           nm))
        usable      (into {} (for [l candidates :when (not (ambiguous (:name l)))]
                               [(:name l) l]))
        refused     (vec (for [l links
                               :let [r (cond (not (accepted? l))      :not-accepted
                                             (not (certifiable l))    :not-certifiable
                                             (not (valid-iri? l))     :invalid-iri
                                             (ambiguous (:name l))    :ambiguous)]
                               :when r]
                           (assoc l :reason r)))
        link-of     (fn [record]
                      (when-not (some #(and (= :crm/E21_Person (:kind %)) (:iri %)) (:entities record))
                        (some usable (agents/creators record))))
        results     (for [r records]
                      (if-let [{:keys [name authority-id]} (link-of r)]
                        (let [e (model/entity {:id         (model/mint-entity-id :crm/E21_Person authority-id)
                                               :kind       :crm/E21_Person
                                               :iri        authority-id
                                               :provenance (model/provenance {:pass       :repair
                                                                              :rule       :reconcile/apply-agent-link
                                                                              :derivation [(:id r)]})})]
                          [(update r :entities (fnil conj []) e) {:record (:id r) :name name :iri authority-id}])
                        [r nil]))]
    {:records (mapv first results)
     :applied (vec (keep second results))
     :refused refused}))

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
   mention it. `apply-agent-links` is the rewrite step: it links the local
   agent of each occurrence an accepted, certifiable link names to the
   authority (lot O3, ADR 0063: the local agent keeps its node, the authority
   is never typed the person), so `reconcile-agents` sees the records'
   agents as one person through the authority (audit S29).

   The fuzzy tier (ADR 0018 decisions 3/4) is `propose-agent-links`: for agents
   that have only a *name* (no authority id), it proposes equivalences to an
   authority pool by name similarity, or by a Fellegi-Sunter score given a
   model (lot M4, `palomar.agent-match`) — always `:proposed` (never
   `:asserted`, D7), scored and revisable. A match is `:certifiable?` only when the
   authority entry carries a determinate id; a perfect name match to an id-less
   entry (the Victor Hugo *metro station*) can never be promoted.

   Grouping the agents into persons across records, from these alignments,
   is `palomar.agent-cluster` (lot M5, ADR 0057): a constrained clustering,
   never a closure through the authority."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [palomar.agent-cluster :as ac]
            [palomar.agent-dates :as dates]
            [palomar.agent-events :as events]
            [palomar.agent-match :as fs]
            [palomar.agent-relations :as relations]
            [palomar.curate :as curate]
            [palomar.identity :as identity]
            [palomar.mentions :as mentions]
            [palomar.model :as model]
            [palomar.plugins.agents :as agents]
            [palomar.plugins.lrmoo.ntriples :as nt]))

(def agent-kinds
  "The CRM classes of an identified agent: a Person, or a Group — a corporate
   body or a family (C7, the post-audit plan)."
  agents/identified-kinds)

(defn agent-kind
  "The CRM class for an authority's `entity-type` (EAC-CPF `entityType`, the
   MADS name class): a Group for a corporate body or a family, else a Person."
  [entity-type]
  (if (contains? #{"corporateBody" "family"} entity-type) :crm/E74_Group :crm/E21_Person))

(defn- entity-type-of [kind] (when (= :crm/E74_Group kind) "corporateBody"))

(defn- record-agents
  "`{:iri :id :label :record}` for each identified agent entity of `record`,
   labelled with the name it identifies (`agents/identified`): two identified
   creators of one record keep two names (post-audit 2, D1)."
  [record]
  (for [{:keys [id iri name]} (agents/identified record)]
    {:iri iri :id id :label name :record (:id record)}))

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

(def ^:private tokens fs/tokens)

(def ^:private jaccard fs/jaccard)

(defn- name-score
  "Best token-set similarity of `name` against a candidate's preferred label and
   its variants — order- and partial-tolerant (\"Gustave Flaubert\" ~ \"Flaubert,
   Gustave\"; \"Balzac\" ~ a \"Balzac\" variant)."
  [name candidate]
  (let [nt (tokens name)]
    (apply max 0.0 (map #(jaccard nt (tokens %))
                        (remove str/blank? (cons (:label candidate) (:variants candidate)))))))

(defn- mention
  "A mention as the matcher reads it: `{:name :dates :record :events}`, from
   a plain name or a map whose `:dates` is a life-dates string (parsed) or a
   parsed profile, whose `:record` (optional) is the record it was read in,
   and whose `:events` (optional, lot M7) are the typed events the record
   says it took part in (`palomar.agent-events`)."
  [m]
  (if (map? m)
    (let [ds (let [d (:dates m)] (if (string? d) (dates/parse-life-dates d) d))
          ;; an event that contradicts the mention's own life is no evidence
          es (events/consistent-events (:events m) ds)]
      (cond-> {:name (:name m) :dates ds}
        (:record m) (assoc :record (:record m))
        (seq es)    (assoc :events es)
        ;; the kind the source states (lot N1): the local node's class
        (:kind m)   (assoc :kind (:kind m))))
    {:name m :dates nil}))

(defn- relational-mention
  "A mention as the relational matcher reads it (lot M8, only with
   anchors): `mention`'s, and its `:id`, `:role`, `:event` and `:works`.
   Without anchors these are not read, so a mention is M4's."
  [m]
  (cond-> (mention m)
    (and (map? m) (some? (:id m)))    (assoc :id (:id m))
    (and (map? m) (:role m))          (assoc :role (:role m))
    (and (map? m) (some? (:event m))) (assoc :event (:event m))
    (and (map? m) (seq (:works m)))   (assoc :works (vec (:works m)))))

(defn- mention-key
  "What identifies a mention beyond its name — its dates, its record and its
   events — as a proposal carries it under `:mention`; nil for a bare name."
  [m]
  (let [k (cond-> {:name (:name m)}
            (:dates m)  (assoc :dates (:dates m))
            (:record m) (assoc :record (:record m))
            (:events m) (assoc :events (:events m))
            (:kind m)   (assoc :kind (:kind m))
            (some? (:id m)) (assoc :id (:id m))
            (:role m)   (assoc :role (:role m)))]
    (when (< 1 (count k)) k)))

(defn matcher-key
  "The `:mention` that `agent-candidates` (no anchors) puts on every
   proposal, rejection and abstention it makes for mention `m`: what the
   matcher read of it. Mentions with one key were scored as one (lot N2,
   `palomar.reconciliation`, which finds each mention's decisions by it)."
  [m]
  (mention-key (mention m)))

(defn- dates-evidence
  "The dates verdict of a mention against a candidate, with the values compared."
  [mention-dates candidate-dates]
  (cond-> (dates/compatibility mention-dates candidate-dates)
    mention-dates   (assoc :mention mention-dates)
    candidate-dates (assoc :candidate candidate-dates)))

(defn- events-evidence
  "The events verdict of a mention's typed events against a candidate's life
   dates (lot M7), nil when the mention carries no events. A group candidate
   (a corporate body or a family by its `:entity-type`) has no life: no
   check. A candidate typed a person is checked (lot N2 review: any
   `:entity-type` used to skip the check, so no authority file's person was
   ever constrained by events)."
  [mention-events candidate]
  (when (seq mention-events)
    (events/compatibility mention-events (when-not (#{"corporateBody" "family"} (:entity-type candidate)) (:dates candidate)))))

(def ^:private entity-kinds
  "The kind an authority's `entity-type` states, as a mention states it."
  {"person" :person "corporateBody" :group "family" :family})

(defn- kind-evidence
  "The kinds of a mention and a candidate when both state one and they
   differ (lot N2 review): a person is not a corporate body nor a family. Nil
   otherwise."
  [mention-kind candidate]
  (let [ck (entity-kinds (:entity-type candidate))]
    (when (and mention-kind ck (not= mention-kind ck))
      {:verdict :incompatible :mention mention-kind :candidate (:entity-type candidate)})))

(defn- rejection
  "Why a candidate of evidence `ev` is rejected, or nil: its kind first (a
   scored path only), its dates, then the mention's events."
  [ev]
  (cond (= :incompatible (get-in ev [:kind :verdict]))   :kind-mismatch
        (= :incompatible (get-in ev [:dates :verdict]))  :incompatible-dates
        (= :incompatible (get-in ev [:events :verdict])) :incompatible-events))

(def ^:private sealed-keys
  "What a prepared map states and seals (`prepare`)."
  [:surname-counts :tf-mean ::fs/tf-mean :pool-size :pool-fingerprint :relations-fingerprint ::model-fingerprint])

(defn prepare
  "What the matcher reads of the whole pool `authority`, computed once per
   run (lot O6, ADR 0060 amendment) instead of once per call of
   `agent-candidates`: `{:surname-counts :tf-mean :pool-size
   :pool-fingerprint}` (`agent-match/surname-counts`, `tf-mean`, the count,
   `pool-fingerprint`). With `{:model m :anchors a}` (lot M8), also
   `:relations-fingerprint` and the pool's stated relations resolved to its
   entries. Pass it as `agent-candidates`' or `candidates/rank`'s
   `:prepared`: the result is the one computed without it.

   The map is opaque. The matcher reads the mean from a namespaced key
   only `prepare` sets (`agent-match/compare-pair`), and the map seals
   what it states: a map whose public statistics were changed since is
   refused. It is checked against the run that uses it: the pool **by
   identity** (the same object; comparing a pool by value would cost what
   preparing it saves), the model and the anchors **by value** (the model's
   fingerprint, `agent-match/model-fingerprint`, and the relations
   fingerprint, `agent-relations/relations-fingerprint`). Another pool, or
   another model or anchors, is refused."
  ([authority] (prepare authority nil))
  ([authority {:keys [model anchors]}]
   (let [counts (fs/surname-counts authority)
         size   (count authority)
         mean   (when (pos? size) (fs/tf-mean counts size))
         p      (cond-> {::pool             authority
                         :surname-counts    counts
                         :tf-mean           mean
                         ::fs/tf-mean       mean
                         :pool-size         size
                         :pool-fingerprint  (fs/pool-fingerprint authority)}
                  anchors (assoc ::model-fingerprint (fs/model-fingerprint model)
                                 :relations-fingerprint (relations/relations-fingerprint model anchors)
                                 ::resolved (zipmap authority (relations/resolve-relations authority))))]
     (assoc p ::sealed (select-keys p sealed-keys)))))

(defn- prepared-for!
  "`prepared`, refused unless `prepare` made it and nothing changed since
   (its sealed statistics), it was prepared for this
   pool (the same object) and, with anchors, for this model and these
   anchors (by value: their fingerprints)."
  [prepared authority model anchors]
  (when-not (and (map? prepared) (contains? prepared ::pool) (contains? prepared ::sealed))
    (throw (ex-info "agent-candidates: :prepared is not prepared statistics (reconcile/prepare)"
                    {:keys (when (map? prepared) (keys prepared))})))
  (when-not (identical? (::pool prepared) authority)
    (throw (ex-info "agent-candidates: these statistics were prepared for another pool" {:pool-size (:pool-size prepared)})))
  (when-not (= (::sealed prepared) (select-keys prepared sealed-keys))
    (throw (ex-info "agent-candidates: these prepared statistics were tampered with: they are not what reconcile/prepare stated"
                    {:pool-size (:pool-size prepared)})))
  (when (and anchors (not (and (contains? prepared ::model-fingerprint)
                               (= (::model-fingerprint prepared) (fs/model-fingerprint model))
                               (= (:relations-fingerprint prepared) (relations/relations-fingerprint model anchors)))))
    (throw (ex-info "agent-candidates: these statistics were not prepared with these anchors and this model" {})))
  prepared)

(defn- scored-candidates
  "The Fellegi-Sunter path of `agent-candidates` (lot M4): every entry whose
   name level is above `:none` is compared (`fs/compare-pair`, the surname
   frequencies and their mean read from `authority`, the pool matched
   against), rejected when its dates are incompatible, else weighed under
   `model`: abstained below the review threshold, for review between the
   thresholds, proposed outright above — but only when it is the mention's
   one candidate at or above the review threshold; with several, all of them
   go to review (`:review-reason :competing`). With a gap G in the model's
   thresholds (lot O4, `fs/competing-gap`), only the candidates less than G
   bits below the top one compete; with none, the top one is decided on its
   own thresholds, and any other candidate above propose is for review
   (`:review-reason :outranked`). A proposal carries
   `:confidence` and its `:calibration-proof` only when the model is
   calibrated on this pool for its decision (`fs/confidence`). With `block`
   (lot M6), a mention is compared with the entries `(block name)` returns
   instead of every entry; the surname frequencies, the pool size and the
   pool's fingerprint are still the whole pool's. An entry whose id is in
   `unnamed` (lot N5: a candidate another lane than the name brought) is
   compared and weighed even when no name level agrees."
  [mentions authority model block anchors unnamed prepared]
  (when (and (some? anchors) (not (relations/frozen? anchors)))
    (throw (ex-info "agent-candidates: :anchors must be a frozen anchor set (agent-relations/freeze-anchors), unchanged since"
                    {:anchors (if (map? anchors) (select-keys anchors [:fingerprint]) (type anchors))})))
  (let [pp     (if prepared
                 (prepared-for! prepared authority model anchors)
                 (prepare authority {:model model :anchors anchors}))
        rel-fp (when anchors (:relations-fingerprint pp))
        ;; stated relations resolved to pool entries (lot M8)
        resolved (when anchors (::resolved pp))
        fp     (:pool-fingerprint pp)
        pool   (select-keys pp [:surname-counts :pool-size ::fs/tf-mean])
        per-m  (for [m (distinct (map (if anchors relational-mention mention) mentions))]
                 (for [cand (cond->> (if block (block (:name m)) authority) resolved (map #(get resolved % %)))
                       :let [c (fs/compare-pair m cand pool)
                             lv (:levels c)]
                       :when (or (not= :none (:name lv)) (contains? unnamed (:id cand)))
                       :let [evs (events-evidence (:events m) cand)
                             kx  (kind-evidence (:kind m) cand)
                             blocked? (or kx (= :incompatible (:dates lv)) (= :incompatible (:verdict evs)))
                             ;; lot M8: relational evidence, from the frozen
                             ;; anchors only, when it says anything
                             rc (when (and anchors (not blocked?))
                                  (let [rc (relations/compare-relations anchors m cand)]
                                    (when (relations/known? rc) rc)))
                             rs (when rc (relations/score model (:levels rc)))
                             {:keys [weights match-weight]} (when-not blocked?
                                                              (cond-> (fs/score model c)
                                                                rs (-> (update :weights merge (:weights rs))
                                                                       (update :match-weight + (:weight rs)))))
                             p  (when match-weight (fs/posterior match-weight (:prior model)))
                             ev (cond-> {:name-score (:name-score c) :dates (dates-evidence (:dates m) (:dates cand))
                                         :comparison lv}
                                  evs          (assoc :events evs)
                                  kx           (assoc :kind kx)
                                  weights      (assoc :weights weights)
                                  (:surname c) (assoc :surname (:surname c))
                                  rc           (assoc :relations (assoc rc :fingerprint rel-fp)))]]
                   (cond-> {:name            (:name m)
                            :authority-id    (:id cand)
                            :authority-label (:label cand)
                            :evidence        ev}
                     p                   (assoc :score p :match-weight match-weight :decision (fs/decide model p))
                     (nil? p)            (assoc :reason (or (rejection ev) :incompatible-dates))
                     (mention-key m)     (assoc :mention (mention-key m))
                     (:entity-type cand) (assoc :entity-type (:entity-type cand)))))
        ;; lot N5: a candidate no name agrees with (only `unnamed` admits
        ;; one) is for review at most, and never competes with one a name
        ;; agrees with; without `unnamed` every candidate is named
        named? #(not= :none (get-in % [:evidence :comparison :name]))
        cap    #(if (and (not (named? %)) (= :propose (:decision %)))
                  (assoc % :decision :review :review-reason :no-name-agreement)
                  %)
        ;; lot O4: with a gap G (`fs/competing-gap`), only a candidate
        ;; less than G bits below the top competes; the top, with no close
        ;; competitor, is decided on its own thresholds, and any other
        ;; candidate is for review at most (`:outranked`)
        gap    (fs/competing-gap model)
        one    (fn [cs]
                 (let [cs    (map cap cs)
                       live? #(and (named? %) (#{:propose :review} (:decision %)))
                       live  (filter live? cs)
                       top-w (when (seq live) (apply max (map :match-weight live)))
                       close #(fs/competes? gap top-w (:match-weight %))
                       n     (count (filter close live))]
                   (for [c cs]
                     (cond (not (live? c))                    c
                           (and (< 1 n) (close c))            (assoc c :decision :review :review-reason :competing)
                           (and (= :propose (:decision c))
                                (< (:match-weight c) top-w))  (assoc c :decision :review :review-reason :outranked)
                           :else                              c))))
        ;; a decision rests on every candidate's relational evidence (a
        ;; competitor pushed down counts): its support is their union
        support (fn [cs] (let [sup (vec (sort-by str (distinct (mapcat #(get-in % [:evidence :relations :support]) cs))))]
                           (if (seq sup) (map #(assoc % :support sup) cs) cs)))
        all    (mapcat (comp support one) per-m)
        by-w   #(vec (sort-by (comp - :match-weight) %))
        ;; relational evidence is a confidence only when the calibration
        ;; measured these weights on this anchor set (lot M8)
        rel-ok (fn [c] (or (nil? (get-in c [:evidence :relations]))
                           (= rel-fp (get-in model [:calibration :relations]))))
        conf   (fn [c] (if-some [x (when (rel-ok c) (fs/confidence model fp (:score c) (:decision c)))]
                         (assoc c :confidence x :calibration-proof (fs/confidence-proof model fp))
                         c))]
    {:proposals (by-w (for [c all :when (#{:propose :review} (:decision c))]
                        (conf (assoc c :certifiable? (boolean (:authority-id c)) :status :proposed))))
     :rejected  (vec (filter :reason all))
     :abstained (by-w (filter #(= :abstain (:decision %)) all))}))

(defn agent-candidates
  "Fuzzy tier (ADR 0018 d.3/4, amended by lots M3 and M4): for each free agent
   mention — a name, or `{:name :dates :record}` whose dates are a life-dates
   string or a profile (`palomar.agent-dates`) and whose optional `:record` is
   the record it was read in — the entries of the `authority` pool —
   `[{:id :label :variants [..] :dates}]` — that may be its person.

   **Name-only (M2/M3)**, with a numeric `threshold` (default 0.5) or no
   option: the entries whose label or variants match by token-set similarity
   >= `threshold` and whose dates are not incompatible with the mention's.
   Returns `{:proposals [..] :rejected [..]}`. A proposal, best name score
   first:

     {:name :authority-id :authority-label :score :certifiable? :status :proposed
      :evidence {:name-score s :dates {:verdict :compatible|:unknown :checks [..] :mention :candidate}}}

   **Fellegi-Sunter (M4)**, with `{:model m}` (`palomar.agent-match`): every
   entry whose name level is above `:none` is compared and weighed; the
   proposal's `:score` is the posterior, `:match-weight` the total weight,
   `:decision` `:propose` (at or above the model's `:propose` threshold, and
   the mention's only candidate at or above `:review`) or `:review` (between
   the thresholds, or one of several candidates above `:review`:
   `:review-reason :competing`; with a gap G in the model's thresholds, lot
   O4, only those less than G bits below the top one compete, and another
   candidate above propose is `:review-reason :outranked`), and its
   `:evidence` adds the `:comparison`
   levels and the `:weights`. A candidate below the `:review` threshold is not
   proposed: it is listed under `:abstained`, with its evidence. The proposal
   carries `:confidence` (and its `:calibration-proof`) only when the model's
   calibration was measured on this pool and meets its bar for that decision
   (`fs/confidence`): any other posterior is a score, never a confidence (ADR
   0005 L2).
   Returns `{:proposals :rejected :abstained}`, best weight first.

   **Blocking (M6)**: with `{:block f}`, `f` a function of a mention's name
   returning the entries of `authority` to compare it with, in `authority`'s
   order — an authority index's lookup (`palomar.authority-index/block`),
   which returns every entry the matcher could score — a mention is compared
   with those alone; the proposals are those of the full scan. A name-only
   threshold of 0 or less admits every entry, which no lookup can block: the
   whole pool is then scanned.

   **Lanes (N5)**: `palomar.candidates/rank` generates a mention's
   candidates by lanes and passes them as `:block`. With a model,
   `{:score-unnamed ids}` makes the entries of those ids compared and
   weighed even at name level `:none` (a candidate an identifier found, its
   name in another script), on its names and dates as any candidate: the
   id itself weighs nothing. Such a candidate is for review at most
   (`:review-reason :no-name-agreement`), never proposed outright, and it
   never makes a candidate a name agrees with `:competing`.

   **Prepared statistics (O6)**: with `{:prepared p}`, `p` the pool's
   statistics computed once per run (`prepare`), nothing pool-wide is
   recomputed: the surname frequencies and their mean, the pool size, its
   fingerprint and, with anchors, the relations fingerprint and the
   resolved relations. The result is the one without it. Statistics
   prepared for another pool (another object), or without these anchors
   and this model, are refused. The name-only path reads none.

   **Relations (M8)**: with `{:model m :anchors a}`, `a` a frozen anchor set
   (`agent-relations/freeze-anchors`; anything else is refused), a mention
   `{:id :record :role :event :works}` is also compared with each candidate
   through its relational neighbourhood (`agent-relations/compare-relations`):
   the role it plays against the candidate's roles in validated links, and
   the validated agents of its record against those the candidate co-occurs
   with elsewhere or its authority record states. The levels weigh as the
   model's `:relations` say (`agent-relations/with-relations`), `:unknown`
   0; a candidate whose relational levels are all unknown is scored as
   without anchors, so no anchors, or anchors that say nothing of a
   mention, give M4's result exactly. Evidence never reads the mention's own
   validated link nor one whose validation used it, and a pass's proposals
   are never anchors of the same pass. The proposal's evidence states
   `:relations {:levels :support :fingerprint ..}` (the mentions read); its
   posterior is a confidence only when the model's calibration measured
   these relational weights on this anchor set.

   **Kinds** (lot N2 review), on the Fellegi-Sunter path: when the mention
   states its kind (`:person`, `:group`, `:family`) and the candidate its
   `:entity-type` (\"person\", \"corporateBody\", \"family\") and they
   differ, the candidate is rejected before it is weighed, `:reason
   :kind-mismatch`, the kinds under `:evidence :kind`. The name-only path
   reads no kind.

   Either way, a candidate whose dates cannot be the mention's
   (`dates/compatibility` is `:incompatible`) is never proposed nor weighed:
   it is listed under `:rejected`, with `:reason :incompatible-dates` and its
   evidence. Missing dates on either side are no evidence (`:unknown`).

   **Events (M7)**: a mention may carry `:events`, the typed events its
   record says it took part in (`palomar.agent-events`). A candidate whose
   life cannot hold them — a creation the mention carried out ending more
   than 10 years before the candidate's birth (never one after death: a
   posthumous cast, impression or copy names its artist) — is rejected the
   same way, `:reason :incompatible-events` (dates first, when
   both are), never weighed; the evidence holds `:events {:verdict :checks}`.
   A publication, an exhibition, an acquisition, an influence, an untyped or
   undated event, a group's event or one against a group candidate, and an
   event that contradicts the mention's own `:dates` (dropped) are no
   evidence.

   A proposal or rejection of a mention given as a map with dates, a record
   or events carries it under `:mention` (`{:name :dates :record :events}`), so `align-agents`
   keeps each proposal with the mention that made it.

   Every proposal is `:proposed` — never `:asserted` (D7). `:certifiable?` is
   true only when the matched entry has a determinate `:id`; a high-scoring
   match to an id-less entry can never be promoted (the metro-station guard)."
  ([mentions authority] (agent-candidates mentions authority 0.5))
  ([mentions authority opts]
   (if (and (map? opts) (:model opts))
     (scored-candidates mentions authority (:model opts) (:block opts) (:anchors opts) (set (:score-unnamed opts))
                        (:prepared opts))
     (let [threshold (if (map? opts) (:threshold opts 0.5) opts)
           block     (when (and (map? opts) (pos? threshold)) (:block opts))
           scored (for [m    (map mention mentions)
                        cand (if block (block (:name m)) authority)
                        :let [score (name-score (:name m) cand)]
                        :when (>= score threshold)
                        :let [evs (events-evidence (:events m) cand)
                              ev  (cond-> {:name-score score :dates (dates-evidence (:dates m) (:dates cand))}
                                    evs (assoc :events evs))]]
                    (cond-> {:name            (:name m)
                             :authority-id    (:id cand)
                             :authority-label (:label cand)
                             :score           score}
                      true                (assoc ::ev ev)
                      (mention-key m)     (assoc :mention (mention-key m))
                      (:entity-type cand) (assoc :entity-type (:entity-type cand))))
           incompatible? #(some? (rejection (::ev %)))
           by-score #(vec (sort-by (comp - :score) %))]
       {:proposals (by-score (for [c (remove incompatible? scored)]
                               (-> c
                                   (assoc :certifiable? (boolean (:authority-id c)) :status :proposed :evidence (::ev c))
                                   (dissoc ::ev))))
        :rejected  (by-score (for [c (filter incompatible? scored)]
                               (-> c (assoc :reason (rejection (::ev c)) :evidence (::ev c)) (dissoc ::ev))))}))))

(defn propose-agent-links
  "The proposals of `agent-candidates` (which see): `[{:name :authority-id
   :authority-label :score :certifiable? :status :proposed :evidence} ..]`,
   best first. A mention is a name or `{:name :dates}`; a candidate whose dates
   are incompatible with the mention's is not among them — `agent-candidates`
   reports it under `:rejected`, with the reason. With plain names, or
   undated entries, nothing is rejected and the proposals are those of the
   name match alone. With `{:model m}`, the proposals are the Fellegi-Sunter
   ones (lot M4): a candidate the model abstains on is not among them."
  ([names authority] (propose-agent-links names authority 0.5))
  ([names authority opts]
   (:proposals (agent-candidates names authority opts))))

;; ---------------------------------------------------------------------------
;; Alignments in the IR (ADR 0032) — proposals as SKOS mapping assertions
;; ---------------------------------------------------------------------------

(declare exact?)

(defn format-proposals
  "Human-readable rendering of `propose-agent-links` proposals, for the CLI:
   one line per proposal, the strongest first, saying which an alignment would
   make an exact match (ADR 0032)."
  [proposals]
  (if (empty? proposals)
    "Authority proposals: none above the threshold."
    (str/join "\n"
              (cons (str "Authority proposals: " (count proposals))
                    (for [{:keys [name authority-id authority-label score] :as p} proposals]
                      (str "  " (pr-str name) " → " (pr-str authority-label) " <" authority-id ">"
                           (format "  %.2f" (double score))
                           (if (exact? p) "  exact" "  close")))))))

(defn- exact?
  "Whether a proposal is put forward as an identity claim
   (`palomar:matchesAuthority`, ADR 0061): certifiable and
   proposed outright (`:decision :propose`, lot M4). Anything else — a
   proposal for review, a name-only proposal (which no model decided,
   whatever its score) — is a close match (ADR 0057): an exact match a
   curator has not made is only ever a matcher's outright decision. A
   curator turns a close match into an identity with `confirm-identity`."
  [{:keys [certifiable? decision]}]
  (boolean (and certifiable? (= :propose decision))))

(defn- named-agent
  "The local agent entity standing for the name `nm` on record `r`, not yet
   identified: the node `palomar.mentions/local-agent` names (lot N1, one rule
   for both) — scoped to the record (two records' homonyms are not the same
   person by name alone, ADR 0041), of the class the proposal's mention
   states (`:mention :kind`), else the authority's `entity-type`, else a
   Person; a LIDO actor's the rules' node. Nil when the record cannot tell
   which actor of that name it is."
  [r nm p]
  (when-let [{:keys [id kind]} (mentions/local-agent r nm {:kind        (get-in p [:mention :kind])
                                                           :entity-type (:entity-type p)
                                                           :mention     (get-in p [:mention :id])})]
    (model/entity {:id id :kind kind})))

(defn- evidence-bindings
  "A proposal's evidence as the bindings of its mapping's provenance: the
   authority's label, the name score, the dates verdict and the date texts
   compared — values a curator reads, and that PROV-O writes."
  [authority-label {:keys [name-score dates comparison events relations]} {:keys [score match-weight decision support] :as p}]
  (let [text (fn [p] (or (:text p)
                         (some->> (for [role [:birth :death :active] :let [t (:text (get p role))] :when t]
                                    (str (name role) " " t))
                                  seq
                                  (str/join ", "))))]
    (cond-> {}
      authority-label             (assoc '?label authority-label)
      name-score                  (assoc '?name-score name-score)
      ;; a scored proposal (lot M4): its posterior, weight, decision and levels
      match-weight                (assoc '?posterior score '?match-weight match-weight '?decision decision)
      comparison                  (assoc '?name-level (:name comparison) '?form (:form comparison)
                                         '?dates-level (:dates comparison))
      ;; an undated mention had no dates to compare: no verdict is stated
      (:mention dates)            (assoc '?dates (:verdict dates))
      (text (:mention dates))     (assoc '?mention-dates (text (:mention dates)))
      (and (:mention dates) (text (:candidate dates))) (assoc '?authority-dates (text (:candidate dates)))
      ;; a mention that carried typed events (lot M7): their verdict
      events                      (assoc '?events (:verdict events))
      ;; relational evidence (lot M8): its levels
      relations                   (assoc '?role-level (get-in relations [:levels :role])
                                         '?neighbours-level (get-in relations [:levels :neighbours]))
      ;; what an anchor made of this claim needs (lot M8): the mention, its
      ;; role, and the mentions its decision read
      (some? (get-in p [:mention :id])) (assoc '?mention (get-in p [:mention :id]))
      (get-in p [:mention :role])       (assoc '?role (get-in p [:mention :role]))
      (seq support)                     (assoc '?support support))))

(defn- proposals-for
  "The proposals among `ps` (one name's) that record `rid` takes, as groups,
   one per mention: those of each mention made in it (`:mention :record`)
   when there are some, each to its own occurrence's local agent (lot O3);
   else those of the one mention that names no record. A record that cannot
   tell which of several unscoped mentions of its name it is takes none:
   one mention's candidates and evidence never reach another's record."
  [rid ps]
  (let [key-of  #(or (:mention %) {:name (:name %)})
        groups  (group-by key-of (filter #(contains? #{nil rid} (:record (key-of %))) ps))
        scoped  (filter #(= rid (:record (key %))) groups)]
    (cond
      (seq scoped)           (map (comp distinct val) (sort-by (comp pr-str key) scoped))
      (= 1 (count groups))   [(distinct (val (first groups)))])))

(def unplaced-code
  "The diagnostic of a proposal `align-agents` puts on no local agent: the
   record gives its name twice or more, and no valid occurrence id says
   which (review of lot O3)."
  :reconcile/unplaced-proposal)

(defn align-agents
  "Put `propose-agent-links` proposals into `records` as data (ADR 0032): for
   each record naming a proposal's `:name`, a local agent entity for that name
   (labelled with it), the authority entity (its `:iri` the authority id), and a
   `:proposed` assertion between them — `identity/authority-match`
   (`palomar:matchesAuthority`, ADR 0061) for a certifiable proposal proposed
   outright (`:decision :propose`, scored), `identity/authority-candidate`
   (`palomar:resemblesAuthority`) otherwise — a proposal for review, and every name-only one (ADR 0057). The score and the
   evidence are its provenance bindings (the authority's label, the name
   score, the dates, and for a scored proposal its probability, weight,
   decision and levels); its `:confidence` is the proposal's `:confidence`
   only when the proposal carries a calibration proof that holds
   (`fs/proven-confidence`: a calibrated model's report, on the pool matched
   against, for its decision; lot M4, ADR 0005 L2): a Jaccard, an
   uncalibrated posterior or a confidence a caller put on a proposal is never
   written as a confidence. The authority is
   the mapping's target, not an identified person: it is no `:crm/E21_Person`
   until a curator accepts the match and `apply-agent-links` applies it. Curation then accepts or rejects it like any claim; the IR states no
   `owl:sameAs` (an export may write one for an accepted claim, by
   `identity/export-rule`), and a rejected alignment stays in the record's history.

   A proposal stays with the mention that made it (`:mention`, lot M3): a
   record takes the proposals of each mention scoped to it (`:record`), each
   on that occurrence's own local agent (lot O3: two occurrences of one name
   are two local agents), else of the one unscoped mention of its name; when
   several unscoped mentions of one name could be its own, it takes none,
   and a name-only proposal reaches no record that gives the name twice
   (`proposals-for`, `mentions/local-agent`), so a record never gets two
   homonyms' authorities or another mention's evidence, and a candidate
   rejected for its mention is no assertion. Returns the records."
  [records proposals]
  (let [by-name (group-by :name proposals)]
    (mapv
     (fn [r]
       (let [rid   (:id r)
             cands (for [nm (distinct (map :name (agents/occurrences r)))
                         g (proposals-for rid (get by-name nm))
                         p g
                         :when (and (:authority-id p) (nil? (nt/iri-problem (:authority-id p))))]
                     [nm p])
             hits  (for [[nm p] cands :when (named-agent r nm p)] p)
             ;; a proposal no occurrence of the record takes is reported, never
             ;; put on another (review of lot O3)
             lost  (distinct
                    (for [[nm p] cands
                          :when (and (nil? (named-agent r nm p)) (not (#{:lido :eac-cpf} (mentions/source-format r))))
                          :let [why (:reason (mentions/resolve-local r nm {:kind        (get-in p [:mention :kind])
                                                                           :entity-type (:entity-type p)
                                                                           :mention     (get-in p [:mention :id])}))]
                          :when why]
                      (model/diagnostic {:severity :warning :code unplaced-code :subject rid
                                         :message  (str "a proposal for " (pr-str nm) " reaches no occurrence of the record ("
                                                        (name why) "): not aligned")
                                         :detail   (cond-> {:reason why :name nm :authority-id (:authority-id p)}
                                                     (get-in p [:mention :id]) (assoc :mention (get-in p [:mention :id])))})))
             r     (if (seq lost) (update r :diagnostics (fnil into []) (remove (set (:diagnostics r)) lost)) r)]
         (reduce
          (fn [r {:keys [name authority-id authority-label evidence] :as p}]
            (let [local (named-agent r name p)
                  ;; the authority as the alignment's target only: not an E21,
                  ;; since a proposal must not make an identified person
                  auth  (model/entity (cond-> {:id (model/mint-entity-id :reconcile/authority authority-id)
                                               :kind :reconcile/authority :iri authority-id}
                                        ;; the type the authority states, which the export checks (ADR 0061)
                                        (string? (:entity-type p)) (assoc :entity-type (:entity-type p))))
                  prov  (model/provenance {:pass :infer :rule :reconcile/propose-agent-link :derivation [rid]})
                  pred  (if (exact? p) identity/authority-match identity/authority-candidate)
                  add   (fn [r e] (if (some #(= (:id e) (:id %)) (:entities r)) r (update r :entities (fnil conj []) e)))]
              (-> r
                  (add local)
                  (add auth)
                  (update :assertions (fnil into [])
                          [(model/assertion {:subject (:id local) :predicate :crm/P190_has_symbolic_content
                                             :value name :status :asserted :provenance prov})
                           (model/assertion {:subject (:id local) :predicate pred
                                             :value (model/reference (:id auth)) :status :proposed
                                             :confidence (some-> (fs/proven-confidence p) double)
                                             :provenance (cond-> prov
                                                           (or authority-label evidence (:support p) (get-in p [:mention :id]))
                                                           (assoc :bindings (evidence-bindings authority-label evidence p)))})]))))
          r hits)))
     records)))

(defn accepted-links
  "The alignments a curator accepted in `records`, as `apply-agent-links` links:
   an `:accepted` (or `:asserted`) `identity/authority-match` from a record's local
   agent to an authority entity. A close match is never a link: it says the two
   are alike, not the same. Each link is scoped to the record it was accepted
   in (`:records #{rid}`, ADR 0041): that this record's \"Gustave Flaubert\" is
   the authority says nothing of another record's, and to the local agent
   it is about (`:local`, lot O3): nor of another occurrence of the name in
   the same record. A link carries the
   `:dates` its mention was matched with, when the alignment states them.
   A curator's confirmed review candidate (`confirm-identity`) is an
   accepted exact match like any other.

   Each link also carries what `agent-relations/freeze-anchors` reads, so a
   curated link is an anchor (lot M8): `:id` (the claim's `assertion-id`),
   `:record`, `:mention` (the matcher's mention id, `?mention`, else the
   local agent), `:role` (`?role`) and `:support` (`?support`, the mentions
   the proposal's decision read; empty when none)."
  [records]
  (vec (distinct
        (for [r records
              :let [ents  (into {} (map (juxt :id identity)) (:entities r))
                    names (into {} (for [a (:assertions r)
                                         :when (= :crm/P190_has_symbolic_content (:predicate a))]
                                     [(:subject a) (:value a)]))]
              a (:assertions r)
              :when (and (= identity/authority-match (:predicate a)) (#{:accepted :asserted} (:status a)))
              :let [auth (ents (:value/target (:value a)))]
              :when (and auth (:iri auth) (names (:subject a)))]
          (cond-> {:name (names (:subject a)) :authority-id (:iri auth) :certifiable? true :status :accepted
                   :records #{(:id r)}
                   ;; the occurrence's local agent the claim is about (lot O3)
                   :local (:subject a)
                   ;; what `agent-relations/freeze-anchors` reads (lot M8)
                   :id (model/assertion-id a) :record (:id r)
                   :mention (let [m (get-in a [:provenance :bindings '?mention])] (if (some? m) m (:subject a)))
                   :support (set (get-in a [:provenance :bindings '?support]))}
            (get-in a [:provenance :bindings '?role]) (assoc :role (get-in a [:provenance :bindings '?role]))
            (entity-type-of (:kind (ents (:subject a)))) (assoc :entity-type (entity-type-of (:kind (ents (:subject a)))))
            ;; the dates the mention was matched with, for `apply-agent-links`' check
            (get-in a [:provenance :bindings '?mention-dates])
            (assoc :dates (get-in a [:provenance :bindings '?mention-dates])))))))

(def apply-conflict-code
  "The diagnostic of accepted claims `apply-agent-links` refuses as a
   conflict (lot O3): two links to one authority whose mentions' dates
   cannot be one person's, or whose local agents the clustering keeps
   apart. Named by `identity/conflict-codes`, so neither
   claim is `owl:sameAs` nor identifies its agent."
  :reconcile/apply-identity-conflict)

(defn- add-entity [r e] (if (some #(= (:id e) (:id %)) (:entities r)) r (update r :entities (fnil conj []) e)))

(defn- add-claim
  "`r` with assertion `a`, unless it already holds a claim of that fact."
  [r a]
  (let [id (model/assertion-id a)]
    (if (some #(= id (model/assertion-id %)) (:assertions r)) r (update r :assertions (fnil conj []) a))))

(defn- authority-entity [iri entity-type]
  (model/entity (cond-> {:id (model/mint-entity-id :reconcile/authority iri) :kind :reconcile/authority :iri iri}
                  (string? entity-type) (assoc :entity-type entity-type))))

(defn- retract-legacy
  "`r` without the nodes `ids` an older `apply-agent-links` made of an
   authority (an `E21_Person` or `E74_Group` whose `:iri` is the authority's,
   ADR 0061's known inconsistency), now that an in-force claim from a local
   agent covers it (review of lot O3): the entity is removed, so no export
   types the authority's IRI as the person, and the claims about it are
   `:retracted`, kept in the record's history."
  [r ids]
  (if (empty? ids)
    r
    (-> r
        (update :entities (partial filterv #(not (ids (:id %)))))
        (update :assertions (partial mapv #(if (and (ids (:subject %)) (not (model/out-of-force? %))) (assoc % :status :retracted) %))))))

(defn apply-agent-links
  "Link the local agents of `records` to the authorities of `links`:
   `propose-agent-links` proposals that a curator has accepted (`:status
   :accepted`, or `:asserted`; `accepted-links` reads them from records).

   **Three levels** (lot O3, ADR 0063). The occurrence is a mention; its
   local agent a hypothetical person or group of the record, one per
   occurrence (`mentions/local-agent`); the entity the person the authority
   identifies. An applied link is an in-force `identity/authority-match`
   (`palomar:matchesAuthority`) from the occurrence's local agent, which
   keeps its own node and its name (`:crm/P190_has_symbolic_content`), to
   the authority, a `:reconcile/authority` entity whose `:iri` is the
   authority id. The authority is never typed a `:crm/E21_Person` or a
   `:crm/E74_Group`: its IRI may be a catalogue record's (a BnF ARK), and a
   record is not the entity (ADR 0059). What each export writes for the
   claim is `identity/export-rule`'s: `owl:sameAs` or the internal
   relation. The local agent is then identified (`agents/identified`), and
   `reconcile-agents` groups the records by the authority.

   A link applies to the local agent it names (`:local`, in its own
   `:record`), or, by its name, to the record's one occurrence of that name
   (a name given twice says not which: `:skipped`, `:several-occurrences`).
   Only to the records it is scoped to (ADR 0041): its `:records`, a set of
   record ids, or `:all`, a curator's explicit decision that the name is one
   person across the corpus. In a record that holds the claim already: in
   force, it is applied as it is; otherwise it is never decided by a link
   (review of lot O3): pending, it is `:skipped` `:pending-here`; for
   review, `:needs-review`; rejected, `:rejected-here`; superseded or
   retracted, `:out-of-force-here`. A claim the link adds is `:accepted`,
   unsigned, so never `owl:sameAs` by itself. Once a claim to an authority
   is in force, a node an older `apply-agent-links` made of that authority
   (its IRI typed the person, ADR 0061's known inconsistency) is removed,
   and its claims retracted (`retract-legacy`).

   Nothing is left out silently. A link that cannot apply at all is returned
   under `:refused` with a `:reason`: `:unscoped` (a link that names no
   records: a name alone identifies no one), `:not-accepted` (a bare proposal
   is never applied, D7), `:not-certifiable` (no determinate authority id: the
   metro-station guard), `:invalid-iri` (an authority id that is no IRI, which
   would corrupt every RDF output), `:ambiguous` (one local agent, or one name,
   accepted for several authority ids) or `:conflict` (two links to one
   authority whose mentions' `:dates` cannot be one person's, ADR 0057, or
   whose local agents the clustering is given as a cannot-link: a rejected
   `palomar:sameAgentAs`, review of lot O3; neither is applied, each names
   the other under `:conflict` with `:because`, and each claim a record
   holds of them gets an `apply-conflict-code` diagnostic, restated on
   every run). A usable link a record does not take is listed under
   `:skipped`, `{:record :name :iri :reason}`: `:already-identified` (the
   source identifies that name, or the local agent is identified by
   another authority), `:several-occurrences`, or one of the reasons
   above. Returns `{:records [..] :applied [{:record :name :iri :local}]
   :skipped [..] :refused [link…]}`."
  [records links]
  (let [accepted?   #(contains? #{:accepted :asserted} (:status %))
        certifiable #(boolean (and (:certifiable? %) (:authority-id %)))
        valid-iri?  #(nil? (nt/iri-problem (:authority-id %)))
        scoped?     #(let [rs (:records %)] (or (= :all rs) (and (set? rs) (seq rs))))
        in-scope?   (fn [l rid] (or (= :all (:records l)) (contains? (:records l) rid)))
        candidates  (filter #(and (scoped? %) (accepted? %) (certifiable %) (valid-iri? %)) links)
        overlap?    (fn [l o] (or (= :all (:records l)) (= :all (:records o))
                                  (seq (set/intersection (:records l) (:records o)))))
        ;; one local agent (or one name, for a link that names none) accepted
        ;; for two authorities where both reach one record
        ambiguous?  (fn [l] (some #(and (= (:name l) (:name %)) (not= (:authority-id l) (:authority-id %))
                                        (or (nil? (:local l)) (nil? (:local %)) (= (:local l) (:local %)))
                                        (overlap? l %))
                                  candidates))
        profile     #(let [d (:dates %)] (if (string? d) (dates/parse-life-dates d) d))
        ;; two local agents the clustering keeps apart: a cannot-link it is
        ;; given (a rejected `sameAgentAs`, review of lot O3), `{#{a b} reason}`
        apart       (into {} (for [{:keys [a b reason]} (:cannot-link (ac/clustering-input records))
                                   :when (not= a b)]
                               [#{a b} reason]))
        ;; two links that would make two mentions one authority's whose dates
        ;; cannot be one person's (ADR 0057), or whose local agents the
        ;; clustering keeps apart: neither is applied. `[other because]`
        conflict    (fn [l] (first (for [o (remove ambiguous? candidates)
                                         :when (and (not= l o) (= (:authority-id l) (:authority-id o)))
                                         :let [why (cond
                                                     (and (:local l) (:local o) (not= (:local l) (:local o))
                                                          (apart (hash-set (:local l) (:local o))))
                                                     (apart (hash-set (:local l) (:local o)))
                                                     (and (profile l) (profile o)
                                                          (= :incompatible (:verdict (dates/compatibility (profile l) (profile o)))))
                                                     :incompatible-dates)]
                                         :when why]
                                     [o why])))
        usable      (remove #(or (ambiguous? %) (conflict %)) candidates)
        refused     (vec (for [l links
                               :let [c (when (and (accepted? l) (scoped? l) (certifiable l) (valid-iri? l)
                                                  (not (ambiguous? l)))
                                         (conflict l))
                                     r (cond (not (accepted? l))      :not-accepted
                                             (not (scoped? l))        :unscoped
                                             (not (certifiable l))    :not-certifiable
                                             (not (valid-iri? l))     :invalid-iri
                                             (ambiguous? l)           :ambiguous
                                             c                        :conflict)]
                               :when r]
                           (cond-> (assoc l :reason r)
                             c (assoc :conflict {:with (select-keys (first c) [:name :records :dates :local])
                                                 :because (second c)}))))
        ;; the claims a conflict names, by record: `{rid #{claim-id}}`
        in-conflict (reduce (fn [m l] (if (and (= :conflict (:reason l)) (:id l) (:record l))
                                        (assoc-in m [(:record l) (:id l)] (get-in l [:conflict :because]))
                                        m))
                            {} refused)
        rewrite     (fn [r]
                      (let [rid    (:id r)
                            ents   (into {} (map (juxt :id identity)) (:entities r))
                            ids    (agents/identified r)
                            ;; a node an older `apply-agent-links` made of an
                            ;; authority (its IRI typed the person) is no source's
                            legacy (fn [iri] (set (for [e (:entities r)
                                                        :when (and (agents/identified-kinds (:kind e)) (= iri (:iri e))
                                                                   (= :reconcile/apply-agent-link (get-in e [:provenance :rule])))]
                                                    (:id e))))
                            stated (fn [nm iri] (some #(and (= :source (:via %)) (= nm (:name %)) (not ((legacy iri) (:id %)))) ids))
                            by-loc (group-by :id (filter #(= :claim (:via %)) ids))
                            floor  (set (map :name (agents/occurrences r)))
                            hits   (for [l usable
                                         :when (in-scope? l rid)
                                         :let [own? (and (:local l) (= rid (:record l)) (ents (:local l)))]
                                         :when (or own? (floor (:name l)))]
                                     [l (if own?
                                          {:id (:local l) :kind (:kind (ents (:local l)))}
                                          (mentions/local-agent r (:name l) {:entity-type (:entity-type l)}))])
                            prov   (model/provenance {:pass :repair :rule :reconcile/apply-agent-link :derivation [rid]})
                            step   (fn [{:keys [record applied skipped]} [{:keys [name authority-id entity-type]} local]]
                                     (let [skip  #(assoc {:record rid :name name :iri authority-id} :reason %)
                                           auth  (authority-entity authority-id entity-type)
                                           claim (model/assertion {:subject (:id local) :predicate identity/authority-match
                                                                   :value (model/reference (:id auth)) :status :accepted
                                                                   :provenance prov})
                                           held  (first (filter #(= (model/assertion-id claim) (model/assertion-id %)) (:assertions record)))
                                           other (some #(when (not= authority-id (:iri %)) %) (get by-loc (:id local)))]
                                       (cond
                                         (nil? local)            {:record record :applied applied :skipped (conj skipped (skip :several-occurrences))}
                                         (and (stated name authority-id) (not held)) {:record record :applied applied :skipped (conj skipped (skip :already-identified))}
                                         other                   {:record record :applied applied :skipped (conj skipped (skip :already-identified))}
                                         ;; a claim a curator has not decided is never decided
                                         ;; by a link (review of lot O3)
                                         (and held (not (model/in-force? held)))
                                         {:record record :applied applied
                                          :skipped (conj skipped (skip (case (:status held)
                                                                         :proposed     :pending-here
                                                                         :needs-review :needs-review
                                                                         :rejected     :rejected-here
                                                                         :out-of-force-here)))}
                                         :else
                                         (let [r1 (cond
                                                    held record
                                                    :else (-> record
                                                              (add-entity (model/entity {:id (:id local) :kind (:kind local)}))
                                                              (add-entity auth)
                                                              (add-claim (model/assertion {:subject (:id local) :predicate :crm/P190_has_symbolic_content
                                                                                           :value name :status :asserted :provenance prov}))
                                                              (add-claim claim)))]
                                           {:record (retract-legacy r1 (legacy authority-id))
                                            :applied (conj applied {:record rid :name name :iri authority-id :local (:id local)})
                                            :skipped skipped}))))
                            out    (reduce step {:record r :applied [] :skipped []} hits)
                            cs     (get in-conflict rid)
                            kept   (vec (remove #(= apply-conflict-code (:code %)) (:diagnostics (:record out))))
                            diags  (for [a (:assertions (:record out))
                                         :let [id (model/assertion-id a)]
                                         :when (contains? cs id)]
                                     (model/diagnostic {:severity :warning :code apply-conflict-code :subject (:subject a)
                                                        :message  (str "accepted identity claims to one authority whose agents cannot be one ("
                                                                       (name (get cs id)) "): not applied, written as palomar:matchesAuthority, not owl:sameAs")
                                                        :detail   {:reason (get cs id) :claims [id]}}))
                            rec    (:record out)
                            rec    (if (or (seq diags) (not= kept (vec (:diagnostics rec))))
                                     (assoc rec :diagnostics (into kept diags))
                                     rec)]
                        (assoc out :record rec)))
        results     (map rewrite records)]
    {:records (mapv :record results)
     :applied (vec (mapcat :applied results))
     :skipped (vec (mapcat :skipped results))
     :refused refused}))

(defn confirm-identity
  "A curator confirms a review candidate (ADR 0057): the close match `close`
   (`identity/authority-candidate` from a record's local agent to an authority, pending
   or in force) is superseded by the accepted exact match between the same
   two (`curate/supersede`), with `reason` and the curator `context`. A
   pending close match is first accepted (the curator read it), then
   superseded; both decisions are in its history. The exact match keeps the
   close match's provenance bindings (its evidence; lot M8, the mention,
   role and support an anchor needs). Returns `{:record :transitions}`. Clustering and `accepted-links` read the accepted exact
   match: an accepted close match alone joins nothing."
  ([record close reason] (confirm-identity record close reason {}))
  ([record close reason context]
   (when-not (= identity/authority-candidate (:predicate close))
     (throw (ex-info "confirm-identity: not a close match" {:assertion close})))
   (let [{r1 :record t1 :transitions} (curate/decide-one record close :accept reason context)
         exact (assoc close :predicate identity/authority-match)
         {r2 :record t2 :transitions} (curate/supersede r1 (assoc close :status :accepted) exact reason context)
         ;; the exact match keeps what the close match's provenance stated (its
         ;; evidence, and for lot M8 its mention, role and support)
         bs    (get-in close [:provenance :bindings])
         xid   (model/assertion-id exact)
         r3    (if (seq bs)
                 (update r2 :assertions (partial mapv #(if (and (= xid (model/assertion-id %)) (= :accepted (:status %))
                                                                (nil? (get-in % [:provenance :bindings])))
                                                         (assoc-in % [:provenance :bindings] bs)
                                                         %)))
                 r2)]
     {:record r3 :transitions (into t1 t2)})))

(def same-agent-rule
  "The rule that proposes two occurrences of one record one agent (lot O3)."
  ac/same-agent-rule)

(def propose-same-agents
  "Two occurrences of one name in one record proposed one agent:
   `agent-cluster/propose-same-agents`, which `agent-cluster/cluster-records`
   runs first (review of lot O3)."
  ac/propose-same-agents)

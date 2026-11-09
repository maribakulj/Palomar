(ns palomar.agent-relations
  "Collective reconciliation (lot M8, ADR 0018 amendment M8): a mention's
   relational neighbourhood as evidence for or against a candidate
   authority, read from **validated links only**, never from the link being
   judged nor from a link whose own validation used it.

   **Anchors.** The relational evidence reads a set of anchors: links a
   curator accepted (an accepted alignment, a confirmed identity) or a source
   asserted, `{:id :mention :record :authority-id :role :event :works
   :support :status}` — `:mention` the mention the link is of, `:record` the
   record it is in, `:role` the mention's role there (a relator code, a LIDO
   role), `:event` a LIDO event it is named in, `:works` the works the
   mention is linked to, `:support` the mentions whose links the link's own
   validation read (what a relational proposal states under `:evidence
   :relations :support`, carried on when a curator accepts it). The set is
   **frozen before scoring** (`freeze-anchors`): validated links only (a
   proposal, whatever its score, is refused; so are two different links
   under one id, and the links of a mention linked to two authorities),
   fingerprinted, and checked again (`frozen?`) when the matcher takes it,
   so nothing a pass proposes can enter its own evidence. Curated links
   reach it through `reconcile/accepted-links`, which reads the mention,
   role and support an alignment's provenance states.

   **No circular confirmation.** For a mention m, `usable-anchors` drops the
   anchors of m itself and of every mention whose link's support reaches m,
   transitively (`excluded-mentions`): a loop m → c supporting n → d
   supporting m → c leaves neither in the other's evidence. A mention
   without an `:id` has no relational evidence. A decision's support is
   every mention its candidates' evidence read (a competitor pushed down
   counts), stated on each proposal (`:support`).

   **Neighbourhoods.** A mention's (`mention-neighbourhood`): the usable
   anchors of the other agents of its record (of the same event, when both
   name one: LIDO), the authorities they name, and its works. A candidate's
   (`authority-neighbourhood`): its roles in its validated occurrences; the
   authorities and works it co-occurs with through validated links in other
   records than the judged mention's; and what its own authority record
   states (`:relations`, e.g. INTERMARC 320/5xx, resolved to pool entries by
   `:record-number`, `resolve-relations`): a related agent is a neighbour, a
   pseudonym (`:kind :pseudonym`, INTERMARC 331) another identity of the
   same person, a competitor and not context; a relation to neither a pool
   entry nor an anchored authority is no evidence. An authority anchored in
   more than `hub-share` of the records the anchors cover is a hub, no
   neighbour on either side.

   **Features** (`compare-relations`), each with an `:unknown` level that
   weighs 0 by rule (missing context is no evidence, as missing dates):
   - `:role`: `:same-role` when the mention's role is among the candidate's
     validated roles, `:other-role` when the candidate has validated roles
     and the mention's is none of them;
   - `:neighbours`: `:shared` when an informative authority (or a work) of
     the mention's context is one the candidate co-occurs with or is
     related to, `:not-shared` when both sides have some and none is shared
     — weighing 0 by rule: most persons share no neighbour with most
     records, and its absence says nothing.

   **Weights** (`relational-weights`), as M4's: log2(m/u) of measured
   levels, a bound where one side is below `agent-match/min-observed`, 0
   where neither is measured; an agreement never weighs against, a
   disagreement never for. A model carries them under `:relations`
   (`with-relations`)."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [palomar.agent-match :as fs]))

(def validated-statuses
  "The statuses of a link that is an anchor: a curator's acceptance (an
   accepted alignment, a confirmed identity) or a source's assertion. Never
   `:proposed`."
  #{:accepted :asserted})

(def features
  "The relational features and their levels."
  {:role       [:same-role :other-role :unknown]
   :neighbours [:shared :not-shared :unknown]})

(def ^:private agreeing #{:same-role :shared})

(def ^:private disagreeing #{:other-role :not-shared})

;; ---------------------------------------------------------------------------
;; The anchor set
;; ---------------------------------------------------------------------------

(defn- anchor-fingerprint [anchors] (fs/fingerprint (vec (sort-by pr-str anchors))))

(defn freeze-anchors
  "The frozen anchor set of `links`: `{:anchors [..] :refused [{:link
   :reason}] :fingerprint}`. A link is an anchor when it is validated
   (`validated-statuses`) and names its `:id`, `:mention` and
   `:authority-id`; otherwise it is refused, `:not-validated` or
   `:incomplete`. Among those left, whatever their order: an id two
   different links share refuses them all (`:duplicate-id`; the same link
   given twice is one), and a mention linked to two different authorities
   refuses all its links (`:conflict`: which one holds, the anchors do not
   say). Anchors are kept sorted by id, `:support` as a set."
  [links]
  (let [norm     (fn [l] (update l :support (comp set seq)))
        reason   (fn [l] (cond (not (validated-statuses (:status l)))                                :not-validated
                               (not (and (some? (:id l)) (some? (:mention l)) (:authority-id l))) :incomplete))
        first-r  (vec (for [l links :let [r (reason l)] :when r] {:link l :reason r}))
        ok       (distinct (map norm (remove reason links)))
        dup-ids  (set (for [[id g] (group-by :id ok) :when (< 1 (count g))] id))
        ok       (remove #(dup-ids (:id %)) ok)
        conflict (set (for [[m g] (group-by :mention ok) :when (< 1 (count (set (map :authority-id g))))] m))
        refused  (concat first-r
                         (for [l (map norm links) :when (and (not (reason l)) (dup-ids (:id l)))] {:link l :reason :duplicate-id})
                         (for [l ok :when (conflict (:mention l))] {:link l :reason :conflict}))
        anchors  (vec (sort-by (comp str :id) (remove #(conflict (:mention %)) ok)))]
    {::frozen     true
     :anchors     anchors
     :refused     (vec (distinct refused))
     :fingerprint (anchor-fingerprint anchors)}))

(defn frozen?
  "Whether `x` is an anchor set `freeze-anchors` made and nothing changed
   since: its anchors still give its fingerprint."
  [x]
  (boolean (and (map? x) (true? (::frozen x)) (vector? (:anchors x))
                (= (:fingerprint x) (anchor-fingerprint (:anchors x))))))

(defn excluded-mentions
  "The mentions whose anchors the evidence on mention `mid` may not read:
   `mid` itself, and every mention one of whose anchors' `:support` reaches
   an excluded mention, transitively."
  [{:keys [anchors]} mid]
  (loop [ex #{mid}]
    (let [more (into ex (for [a anchors
                              :when (and (not (ex (:mention a))) (seq (set/intersection (:support a) ex)))]
                          (:mention a)))]
      (if (= more ex) ex (recur more)))))

(defn usable-anchors
  "The anchors of `frozen` the evidence on `mention` may read (see
   `excluded-mentions`)."
  [frozen mention]
  (let [ex (excluded-mentions frozen (:id mention))]
    (vec (remove #(ex (:mention %)) (:anchors frozen)))))

;; ---------------------------------------------------------------------------
;; Neighbourhoods
;; ---------------------------------------------------------------------------

(defn- together?
  "Whether `a` and `b` are agents of one record, and of one event when both
   name theirs."
  [a b]
  (and (some? (:record a)) (= (:record a) (:record b))
       (or (nil? (:event a)) (nil? (:event b)) (= (:event a) (:event b)))))

(defn mention-neighbourhood
  "The context of `mention` among `usable` anchors: `{:co-mentions [anchor
   ..] :authorities #{id} :works #{work}}` — the validated other agents of
   its record (of its event), the authorities they are linked to, and the
   mention's own works."
  [usable mention]
  (let [co (vec (filter #(and (together? mention %) (not= (:id mention) (:mention %))) usable))]
    {:co-mentions co
     :authorities (set (map :authority-id co))
     :works       (set (:works mention))}))

(defn resolve-relations
  "The entries of `pool` with their stated `:relations` resolved to pool
   entries: a relation names its target by `:id` (a pool id) or by
   `:record-number` (an INTERMARC `$3`), looked up among the entries'
   `:record-number`s; a resolved one carries the entry's `:id` and
   `:in-pool? true`. A relation to no entry of the pool keeps what it had:
   `authority-neighbourhood` reads it only if an anchor names it."
  [pool]
  (let [ids   (set (keep :id pool))
        by-rn (into {} (for [e pool :when (and (:record-number e) (:id e))] [(:record-number e) (:id e)]))
        res   (fn [r] (let [id (cond (ids (:id r)) (:id r)
                                     (:record-number r) (by-rn (:record-number r)))]
                        (if id (assoc r :id id :in-pool? true) r)))]
    (mapv (fn [e] (cond-> e (seq (:relations e)) (update :relations #(mapv res %)))) pool)))

(defn authority-neighbourhood
  "What is known of `candidate` (a pool entry, `:relations [{:id :kind
   :in-pool?}]` optional, `resolve-relations`) among `usable` anchors, for
   judging `mention`: `{:anchors [anchor ..] :roles #{role} :co-occurring
   #{id} :works #{work} :stated #{id} :identities #{id} :support #{mention}}`
   — its validated occurrences and their roles; the authorities and works
   its validated occurrences share a record (an event) with, outside the
   judged mention's record; the agents its record states it is related to
   and its other identities (pseudonyms), each only when it is an entry of
   the pool or an anchored authority (a relation to nothing the batch knows
   is no evidence); the mentions whose anchors were read."
  [usable candidate mention]
  (let [cid      (:id candidate)
        own      (filter #(= cid (:authority-id %)) usable)
        away     (remove #(= (:record mention) (:record %)) own)
        others   (for [o away a usable
                       :when (and (not= (:id o) (:id a)) (together? o a) (not= cid (:authority-id a)))]
                   a)
        anchored (set (map :authority-id usable))
        known?   (fn [r] (and (some? (:id r)) (or (:in-pool? r) (anchored (:id r)))))
        kind     (fn [k] (set (keep #(when (and (= k (:kind %)) (known? %)) (:id %)) (:relations candidate))))]
    {:anchors      (vec own)
     :roles        (set (keep #(some-> (:role %) str/trim not-empty) own))
     :co-occurring (set (map :authority-id others))
     :works        (into (set (mapcat :works away))
                         (keep #(when (= :work (:kind %)) (or (:id %) (:record-number %))) (:relations candidate)))
     :stated       (kind :agent)
     :identities   (kind :pseudonym)
     :support      (set (map :mention (concat own others)))}))

(def hub-share
  "An authority anchored in more than this share of the records the usable
   anchors cover is a hub (the publisher of most of a batch): it is no
   informative neighbour, on either side."
  0.1)

(defn hubs
  "The hub authorities of `usable` anchors (`hub-share`)."
  [usable]
  (let [n   (count (set (keep :record usable)))
        per (frequencies (map first (distinct (map (juxt :authority-id :record) usable))))]
    (set (for [[a k] per :when (> k (* hub-share n))] a))))

(defn role-level
  "`:same-role`, `:other-role` or `:unknown` for a mention's `role` against
   a candidate's validated `roles`."
  [role roles]
  (let [r (some-> role str/trim not-empty)]
    (cond (or (nil? r) (empty? roles)) :unknown
          (contains? roles r)          :same-role
          :else                        :other-role)))

(defn compare-relations
  "The relational comparison of `mention` (`{:id :record :role :event
   :works}`) with `candidate` under the `frozen` anchor set:

     {:levels {:role :neighbours} :role r :candidate-roles [..] :shared #{..}
      :shared-works #{..} :hubs #{..} :support [mention ..]}

   `:support` the mentions whose validated links the evidence read — never
   `mention`, nor one whose link's validation depended on it. A mention
   without an `:id` cannot be told from its own link: every level is
   unknown. Hubs (`hubs`) are no neighbour on either side."
  [frozen mention candidate]
  (if (nil? (:id mention))
    {:levels {:role :unknown :neighbours :unknown} :role (:role mention) :candidate-roles [] :shared #{}
     :shared-works #{} :hubs #{} :support []}
    (let [usable (usable-anchors frozen mention)
          hub    (hubs usable)
          mn     (mention-neighbourhood usable mention)
          an     (authority-neighbourhood usable candidate mention)
          ctx    (set/difference (disj (:authorities mn) (:id candidate)) hub)
          hood   (set/difference (set/union (:co-occurring an) (:stated an)) hub)
          shared (set/intersection ctx hood)
          sw     (set/intersection (:works mn) (:works an))
          nb     (cond (or (seq shared) (seq sw))                   :shared
                       (and (or (seq ctx) (seq (:works mn)))
                            (or (seq hood) (seq (:works an))))      :not-shared
                       :else                                        :unknown)
          role   (role-level (:role mention) (:roles an))
          ctx-as (filter #(contains? ctx (:authority-id %)) (:co-mentions mn))
          read   (cond-> #{}
                   (not= :unknown role) (into (map :mention (:anchors an)))
                   (not= :unknown nb)   (into (concat (:support an) (map :mention ctx-as))))]
      {:levels          {:role role :neighbours nb}
       :role            (:role mention)
       :candidate-roles (vec (sort (:roles an)))
       :shared          shared
       :shared-works    sw
       :hubs            (set/intersection hub (set/union (:authorities mn) (:co-occurring an) (:stated an)))
       :support         (vec (sort-by str read))})))

(defn known?
  "Whether a relational comparison says anything: a level not `:unknown`."
  [rc]
  (boolean (some #(not= :unknown %) (vals (:levels rc)))))

;; ---------------------------------------------------------------------------
;; Weights
;; ---------------------------------------------------------------------------

(defn level-counts
  "Per relational feature, `{:n :counts}` over relational comparisons."
  [comparisons]
  (fs/level-counts comparisons features))

(def ^:private no-evidence
  "Levels that weigh 0 by rule: missing context, and the absence of a
   shared neighbour (most persons share no neighbour with most records)."
  #{:unknown :not-shared})

(defn- constrain [ws]
  (into {} (for [[f lw] ws]
             [f (into {} (for [[l w] lw]
                           [l (cond (no-evidence l)  0.0
                                    (agreeing l)     (max 0.0 w)
                                    (disagreeing l)  (min 0.0 w)
                                    :else            w)]))])))

(defn relational-weights
  "The weight of each relational level, from `:m-counts` and `:u-counts`
   (`level-counts`) by `agent-match/measured-weight`, or from hand-set
   shares `:m` and `:u`; `:unknown` and `:not-shared` 0 by rule, an
   agreement never below 0, a disagreement never above."
  [{:keys [m u m-counts u-counts]}]
  (constrain
   (into {} (for [[f levels] features]
              [f (into {} (for [l levels]
                            [l (if (and m-counts u-counts)
                                 (fs/measured-weight (get-in m-counts [f :counts l] 0.0) (get-in m-counts [f :n] 0.0)
                                                     (get-in u-counts [f :counts l] 0.0) (get-in u-counts [f :n] 0.0))
                                 (fs/share-weight (get-in m [f l]) (get-in u [f l])))]))]))))

(defn with-relations
  "`model` (`agent-match/model`) with relational weights: `{:weights ..}`
   as given, or measured (`relational-weights`) from counts or shares;
   `:estimation` a note."
  [model {:keys [weights estimation] :as params}]
  (assoc model :relations (cond-> {:weights (if weights (constrain weights) (relational-weights params))}
                            (:m-counts params) (assoc :m-counts (:m-counts params))
                            (:u-counts params) (assoc :u-counts (:u-counts params))
                            estimation         (assoc :estimation estimation))))

(defn score
  "The relational weights of levels `levels` under `model`'s `:relations`:
   `{:weights {:role w :neighbours w} :weight sum}`, 0 for a model without."
  [model levels]
  (let [ws (into {} (for [f (keys features)] [f (double (get-in model [:relations :weights f (get levels f)] 0.0))]))]
    {:weights ws :weight (+ (:role ws) (:neighbours ws))}))

(defn relations-fingerprint
  "What a calibration must have measured for a posterior with relational
   evidence to be a confidence: the model's relational weights and the
   anchor set's fingerprint."
  [model frozen]
  (fs/fingerprint [(get-in model [:relations :weights]) (:fingerprint frozen)]))

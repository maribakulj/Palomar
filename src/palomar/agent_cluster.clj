(ns palomar.agent-cluster
  "Constrained, revisable clustering of agents into persons (lot M5, ADR 0057).

   **What it replaces.** Before this lot Palomar grouped agents across records
   in one way only: through an authority. `reconcile-agents` groups identified
   agents by their authority IRI, `apply-agent-links` gave every record whose
   accepted link names an authority that authority's node, and the eval's
   `:top-1` rules group mentions by their top proposal. Each is a closure
   through the authority with no check: two mentions linked to one authority
   are one person, whatever else is known of them. And until lot O2 the
   alignments were `skos:exactMatch`, which SKOS makes transitive (SKOS
   Reference S45; symmetric, S44; a sub-property of `closeMatch`, S42), so a
   consumer closing them got the same closure. They are now Palomar's own
   relations (`palomar.identity`, ADR 0061), declared nothing a reasoner
   closes; the guarantees below are kept by this code. A chain A~X, X~C where A and C cannot be one person
   (their dates) ends with A and C merged.

   **Inputs** (`cluster`), plain data:
   - `:items`, `{id {:authority? bool :dates profile :events [..]}}`: the agents (a
     record's local agent, a mention), the authorities, and (in the IR) the
     person nodes a curator accepted memberships in;
   - `:links`, `[{:a :b :basis :weight}]`: the pairwise decisions. `:basis`
     is `:accepted` (a curator accepted the alignment, or the membership),
     `:propose` (the matcher proposed it outright, `:decision :propose`, lot
     M4) or `:review` (for review, a close match, a name-only proposal);
     `:weight` is the match weight of a proposal;
   - `:cannot-link`, `[{:a :b :reason}]`: pairs that are never one person.
     Besides those given (an M3 date rejection, a rejected alignment), two
     are derived, item by item, when two clusters would merge:
     `:distinct-authorities` (two different authority records, unless
     their records state one entity: lot N6, ADR 0059) and
     `:incompatible-dates` (both dated, `agent-dates/compatibility`
     `:incompatible`), and `:incompatible-events` (lot M7: an item's typed
     events, `palomar.agent-events`, that the other's life dates cannot
     hold — a creation it carried out before their birth or after their
     death). Two items' events alone are no constraint: no life is known.
     A mention accepted for one authority therefore never
     joins another's: the two authorities cannot be one cluster.
   Two mentions in one record with different roles are **not** a cannot-link:
   a record's local agent is keyed by the record and the name, not by the
   field or the role, and a person may be a record's author and its
   illustrator; nothing in the IR tells the two cases apart.

   **Policy.** A curator's decision outranks the machine: `:accepted` links
   are hard must-links. Besides them, only `:propose` links join (`:join`,
   the default); a `:review` link is listed under `:candidates`, a merge for
   a curator to decide, never applied.

   **Algorithm**, in two phases.
   1. *Must-links.* The accepted links are closed into components. A
      component holding two cannot-linked items is a **conflict**: the
      curator's decisions contradict each other or a hard constraint, and
      the clustering does not choose between them — none of the component's
      accepted links is applied, all are reported under `:conflicts` with
      the pairs and reasons, and its items enter phase 2 apart. Every other
      component is merged.
   2. *Greedy constrained agglomeration* of the other joining links, in
      decreasing strength (a proposal's weight), each merging the two
      clusters it joins (union-find) unless some member of one cannot be
      linked with some member of the other: the link is then refused and
      reported, with the pair and the reason. So in a chain A~B~C with
      A ⊥ C, B goes with the side whose link is stronger. **On a tie the
      lower ids win, and it is reported**: a refused link carries `:tie?
      true` when a link of the same strength was joined before it touching
      the conflicting pair or the refused link's ends, and that joined link
      carries `:tie? true` too.
   No cluster ever holds two cannot-linked items, and the result does not
   depend on the order the links come in.

   `cluster-records` runs it on the alignments and the memberships a batch
   of records holds, and writes each local agent's cluster as a membership
   claim: `local-agent palomar:inCluster person-node`
   (`identity/cluster-membership`), the person node a
   `:reconcile/person` entity minted from the cluster's key (its authority's
   IRI, or its first agent) **and the batch** (`batch-fingerprint`), so the
   memberships of two batches never name one node. Every claim the machine
   writes is `:proposed`; a curator accepts it. Local agent ids never change,
   and an accepted claim is never superseded or rejected by the machine.
   Re-clustering revises the pending memberships only, through
   `palomar.curate`, and deletes none: one that no longer holds is rejected
   with the reason; a claim proposed again is reopened, never copied (a
   record holds one claim per fact). A refused alignment goes to
   `:needs-review` with the reason; a conflict is a diagnostic
   (`:reconcile/identity-conflict`) on each record it involves.

   Constraints are enforced **within a batch only**: there is no store
   across batches (ADR 0018 §5)."
  (:require [clojure.string :as str]
            [palomar.agent-dates :as dates]
            [palomar.agent-equivalence :as eq]
            [palomar.agent-events :as events]
            [palomar.curate :as curate]
            [palomar.identity :as identity]
            [palomar.model :as model]))

;; ---------------------------------------------------------------------------
;; The clustering, on plain data
;; ---------------------------------------------------------------------------

(def default-join
  "The link bases that may join clusters: a curator's acceptance (a hard
   must-link), an equivalence the authority records state (lot N6, ADR
   0059) and an outright proposal. A link for review never does."
  #{:accepted :equivalence :propose})

(def ^:private basis-rank {:accepted 0 :equivalence 1 :propose 2 :review 3})

(defn- link-order
  "Accepted first, then stated equivalences, then by decreasing weight (none
   last), ties by the ids."
  [l]
  [(basis-rank (:basis l) 4) (- (or (:weight l) ##-Inf)) (str (:a l)) (str (:b l))])

(defn- strength [l] (vec (take 2 (link-order l))))

(defn- constraint-index
  "The given cannot-links as `{#{a b} reason}`."
  [cannot-link]
  (into {} (for [{:keys [a b reason]} cannot-link :when (not= a b)] [#{a b} (or reason :given)])))

(defn- events-against?
  "Whether item `i`'s typed events, those consistent with its own dates,
   cannot be the life of item `j` (its dates)."
  [i j]
  (let [es (events/consistent-events (:events i) (:dates i))]
    (boolean (and (seq es) (:dates j)
                  (= :incompatible (:verdict (events/compatibility es (:dates j))))))))

(defn- one-entity?
  "Whether items `ix` and `iy` are records their statements make one entity
   (`with-equivalences`)."
  [ix iy]
  (let [e (::entity ix)] (and (some? e) (= e (::entity iy)))))

(defn cannot-link-reason
  "The reason `x` and `y` cannot be one person, or nil: a given constraint
   (`index`, `constraint-index`), two different authorities — unless the
   records state they are one entity (lot N6, ADR 0059) —, dates that
   cannot be one person's, or one's typed events the other's dates cannot
   hold (lot M7)."
  [items index x y]
  (when (not= x y)
    (let [ix (items x) iy (items y)]
      (or (index #{x y})
          (when (and (:authority? ix) (:authority? iy) (not (one-entity? ix iy))) :distinct-authorities)
          (when (and (:dates ix) (:dates iy)
                     (= :incompatible (:verdict (dates/compatibility (:dates ix) (:dates iy)))))
            :incompatible-dates)
          (when (or (events-against? ix iy) (events-against? iy ix))
            :incompatible-events)))))

(defn- first-violation
  "The first `{:a :b :reason}` across clusters `xs` and `ys`, or nil."
  [items index xs ys]
  (first (for [x (sort-by str xs) y (sort-by str ys)
               :let [r (cannot-link-reason items index x y)]
               :when r]
           {:a x :b y :reason r})))

(defn- inner-violations
  "Every cannot-linked pair among `ms`."
  [items index ms]
  (let [v (vec (sort-by str ms))]
    (vec (for [i (range (count v)) j (range (inc i) (count v))
               :let [r (cannot-link-reason items index (v i) (v j))]
               :when r]
           {:a (v i) :b (v j) :reason r}))))

(defn- with-equivalences
  "`items` with each authority the records state one entity with another
   marked so (`::entity`), and the equivalences (`eq/equivalences`) —
   only when `join` lets equivalences join: otherwise none is read, and
   two authorities are always two."
  [items join]
  (if (join :equivalence)
    (let [e (eq/equivalences items)]
      [(reduce-kv (fn [m id k] (assoc-in m [id ::entity] k)) items (:entity e)) e])
    [items {:links [] :conflicts [] :warnings []}]))

(defn- cluster-key
  "A cluster's key: its first authority when it holds one (several only
   when the records state they are one entity), else its first member."
  [items members]
  (or (first (sort-by str (filter (comp :authority? items) members)))
      (first (sort-by str members))))

(defn- components
  "The connected components of `links` over `ids`: `[[members links] ..]`,
   no constraint read."
  [ids links]
  (let [find   (fn [p x] (loop [x x] (let [y (p x)] (if (= x y) x (recur y)))))
        parent (reduce (fn [p {:keys [a b]}]
                         (let [ra (find p a) rb (find p b)]
                           (if (= ra rb) p (assoc p ra rb))))
                       (zipmap ids ids) links)
        root   (partial find parent)]
    (for [[r ms] (sort-by (comp str key) (group-by root ids))]
      [(set ms) (vec (sort-by link-order (filter #(= r (root (:a %))) links)))])))

(defn cluster
  "Cluster `:items` by `:links` under `:cannot-link` (see the ns doc). `:join`
   (default `default-join`) is the set of link bases that may join. Returns

     {:clusters   [{:key :members #{..} :authority id-or-nil}]  ; by key
      :cluster-of {item key}
      :joined     [link ..]   ; the links applied, in order (`:tie? true` when won on a tie)
      :refused    [{:link :violation {:a :b :reason} :tie?} ..]
      :conflicts  [{:links [accepted ..] :violations [{:a :b :reason} ..]} ..]
      :event-conflicts [{:links :violations} ..] ; accepted links kept despite an events constraint
      :candidates [links that may not join] ; for a curator, never applied
      :equivalence-conflicts [{:reason :members ..} ..]  ; refused, `eq/equivalences`
      :equivalence-warnings  [{:kind :members ..} ..]}   ; reported, not refused

   Every item of `:items` or of a link is in exactly one cluster.

   **Stated equivalences** (lot N6, ADR 0059): an authority item may carry
   what its record states of its entity — `:iri`, `:isni`, `:same-as`
   (`palomar.agent-equivalence`). Two authorities the records state one
   entity are not cannot-linked as two authorities, and a link `{:basis
   :equivalence :evidence}` joins them (after the accepted links, before
   the proposals; other constraints still hold). A cluster that holds such
   a link names it under `:equivalence`. The equivalence links are tried in
   id order, so when dates refuse one, the records of lower ids stay
   together; an equivalence link is never part of a tie, and one refused by
   life dates or events is listed under `:equivalence-warnings`
   (`:dates-refuse-equivalence`): the records contradict each other. A
   component `eq/equivalences` refuses is used for nothing and listed under
   `:equivalence-conflicts`; its other findings are `:equivalence-warnings`.
   None of this is read unless `join` holds `:equivalence`."
  [{:keys [items links cannot-link join] :or {join default-join}}]
  (let [ids      (distinct (concat (keys items) (mapcat (juxt :a :b) links)))
        [items equiv] (with-equivalences (merge (zipmap ids (repeat {})) items) join)
        index    (constraint-index cannot-link)
        must     (filter #(and (= :accepted (:basis %)) (join :accepted)) links)
        soft     (sort-by link-order (filter #(and (not= :accepted (:basis %)) (join (:basis %)))
                                             (concat links (:links equiv))))
        comps    (for [[ms ls] (components ids must)
                       :let [vs (inner-violations items index ms)]
                       :when (seq vs)]
                   [ls (group-by #(= :incompatible-events (:reason %)) vs)])
        ;; an events constraint never overrides a curator (lot M7, review):
        ;; the accepted links are applied, the constraint reported
        conflict (vec (for [[ls by] comps :when (seq (by false))] {:links ls :violations (by false)}))
        ev-confl (vec (for [[ls by] comps :when (and (empty? (by false)) (seq (by true)))]
                        {:links ls :violations (by true)}))
        held     (set (mapcat :links conflict))
        merge-in (fn [{:keys [root members] :as st} ra rb]
                   (let [[keep gone] (sort-by str [ra rb])
                         moved (members gone)]
                     (assoc st
                            :root (reduce #(assoc %1 %2 keep) root moved)
                            :members (-> members (update keep into moved) (dissoc gone)))))
        init     {:root (zipmap ids ids) :members (into {} (for [i ids] [i #{i}])) :joined [] :refused [] :tied #{}}
        phase-1  (reduce (fn [st l]
                           (let [ra ((:root st) (:a l)) rb ((:root st) (:b l))]
                             (update (if (= ra rb) st (merge-in st ra rb)) :joined conj l)))
                         init (sort-by link-order (remove held must)))
        n-must   (count (:joined phase-1))
        step     (fn [{:keys [root members joined] :as st} l]
                   (let [ra (root (:a l)) rb (root (:b l))]
                     (if (= ra rb)
                       (update st :joined conj l)
                       (if-let [v (first-violation items index (members ra) (members rb))]
                         (let [ends  (set [(:a v) (:b v) (:a l) (:b l)])
                               ;; an equivalence is no matter of strength: never a tie
                               rival (when-not (= :equivalence (:basis l))
                                       (first (for [[i j] (map-indexed vector joined)
                                                    :when (and (<= n-must i) (not= :equivalence (:basis j))
                                                               (= (strength j) (strength l))
                                                               (or (ends (:a j)) (ends (:b j))))]
                                                i)))]
                           (cond-> (update st :refused conj {:link l :violation v :tie? (some? rival)})
                             rival (update :tied conj rival)))
                         (update (merge-in st ra rb) :joined conj l)))))
        {:keys [members joined refused tied]} (reduce step phase-1 soft)
        eq-links (filter #(= :equivalence (:basis %)) joined)
        clusters (sort-by (comp str :key)
                          (for [ms (vals members)
                                :let [k  (cluster-key items ms)
                                      es (vec (filter #(ms (:a %)) eq-links))]]
                            (cond-> {:key k :members ms :authority (when (:authority? (items k)) k)}
                              (seq es) (assoc :equivalence es))))]
    {:clusters   (vec clusters)
     :cluster-of (into {} (for [{:keys [key members]} clusters, m members] [m key]))
     :joined     (vec (map-indexed (fn [i l] (if (contains? tied i) (assoc l :tie? true) l)) joined))
     :refused    refused
     :conflicts  conflict
     :event-conflicts ev-confl
     :candidates (vec (sort-by link-order (remove #(join (:basis %)) links)))
     :equivalence-conflicts (:conflicts equiv)
     :equivalence-warnings  (into (vec (:warnings equiv))
                                  (for [{:keys [link violation]} refused
                                        :when (and (= :equivalence (:basis link))
                                                   (#{:incompatible-dates :incompatible-events} (:reason violation)))]
                                    {:kind :dates-refuse-equivalence :members [(:a link) (:b link)] :violation violation}))}))

(defn transitive-closure
  "The naive clustering, for comparison: the connected components of the
   joining `links` (as `cluster`'s `:join`), no constraint read.
   `{item key}`, the key a component's first member."
  [{:keys [items links join] :or {join default-join}}]
  (:cluster-of (cluster {:items (update-vals (or items {}) #(dissoc % :authority? :dates :events :isni :same-as :pseudonym :real-identity))
                         :links links :join join})))

(defn violations
  "The cannot-linked pairs `clustering` (`cluster`'s result) puts in one
   cluster, under the same `items` and `cannot-link`: always empty for
   `cluster`, by construction. An events constraint among a curator's
   accepted links, kept and reported (`:event-conflicts`), is none."
  [{:keys [items cannot-link join] :or {join default-join}} clustering]
  (let [ids   (keys (:cluster-of clustering))
        items (first (with-equivalences (merge (zipmap ids (repeat {})) items) join))
        index (constraint-index cannot-link)
        kept  (set (mapcat :violations (:event-conflicts clustering)))]
    (vec (remove kept (mapcat #(inner-violations items index (:members %)) (:clusters clustering))))))

;; ---------------------------------------------------------------------------
;; In the IR: alignments and memberships in, memberships out
;; ---------------------------------------------------------------------------

(def mapping-predicates
  "The predicates of an alignment to an authority: the identity claim and
   the candidate for review (ADR 0061)."
  #{identity/authority-match identity/authority-candidate})

(def membership-rule :reconcile/cluster-agents)

(def conflict-code
  "The diagnostic code of a conflict between accepted claims (ADR 0057)."
  (identity/conflict-codes :reconcile/identity-conflict))

(def events-conflict-code
  "The diagnostic code of accepted claims kept although a mention's typed
   events cannot be the life they join (lot M7, review): the curator wins,
   the constraint is reported."
  (identity/conflict-codes :reconcile/identity-events-conflict))

(def equivalence-conflict-code
  "The diagnostic code of authority records whose stated equivalences are
   refused (lot N6, ADR 0059: an ISNI clash through a chain, a pseudonym
   and its real identity made one): the component is used for nothing,
   and reported."
  :reconcile/equivalence-conflict)

(def equivalence-warning-code
  "The diagnostic code of what the authority records' statements say
   against each other without a refusal (lot N6, ADR 0059): a direct
   `sameAs` against disjoint ISNIs, disjoint ids of another scheme, a
   duplicate in one file, an invalid ISNI, an equivalence that life dates
   refuse."
  :reconcile/equivalence-warning)

(def person-nodes-conflict-code
  "The diagnostic code of a cluster that holds accepted memberships to two
   person nodes (lot N6): a curator accepted two persons the evidence now
   makes one; neither is superseded."
  :reconcile/person-nodes-conflict)

(defn batch-fingerprint
  "The fingerprint of a batch: its records' ids. A person node is minted
   from it, so two batches clustered apart never share one."
  [records]
  (model/content-digest (vec (sort (map (comp str :id) records))) 12))

(defn person-id
  "The person node of a cluster keyed by an authority's IRI (`[:authority
   iri batch]`) or a local agent's id (`[:agent id batch]`), `batch` a
   `batch-fingerprint`."
  [[kind k batch]]
  (model/mint-entity-id :reconcile/person (cond-> [(name kind) (str k)] batch (conj batch))))

(defn- target [a] (when (model/reference-value? (:value a)) (:value/target (:value a))))

(defn- bound [a k] (get-in a [:provenance :bindings k]))

(defn- parse-dates [d] (if (string? d) (dates/parse-life-dates d) d))

(defn- batch-view
  "What `records` state that the clustering reads: the authorities, the local
   agents (subject of an alignment or a membership) and their records, the
   alignments, and the memberships (every status) by local agent."
  [records]
  (let [ents  (into {} (for [r records e (:entities r)] [(:id e) e]))
        kind  (comp :kind ents)
        align (for [r records a (:assertions r)
                    :when (and (mapping-predicates (:predicate a)) (= :reconcile/authority (kind (target a))))]
                (assoc a ::record (:id r)))
        membs (for [r records a (:assertions r)
                    :when (and (= identity/cluster-membership (:predicate a)) (= :reconcile/person (kind (target a))))]
                (assoc a ::record (:id r)))]
    {:ents        ents
     :alignments  align
     :locals      (into {} (for [a (concat align membs)] [(:subject a) (::record a)]))
     :memberships (group-by :subject membs)}))

(defn- link-of
  "An alignment as a link: accepted exact match `:accepted`, an outright
   proposal `:propose` (with its weight), any other pending one `:review`;
   nil for one out of force or an accepted close match (alike, not the
   same: a curator confirms an identity with `reconcile/confirm-identity`)."
  [a]
  (let [base {:a (:subject a) :b (target a) :record (::record a) :predicate (:predicate a)
              :assertion (model/assertion-id a)}
        w    (bound a '?match-weight)]
    (cond
      (and (model/in-force? a) (= identity/authority-match (:predicate a)))
      (assoc base :basis :accepted)
      (and (model/pending? a) (= identity/authority-match (:predicate a)) (= :propose (bound a '?decision)))
      (cond-> (assoc base :basis :propose) (number? w) (assoc :weight (double w)))
      (model/pending? a)
      (cond-> (assoc base :basis :review) (number? w) (assoc :weight (double w))))))

(defn clustering-input
  "The `cluster` input `records` state, with `extra`: `:dates`, `{agent-id
   dates}` (a string or a profile) known beyond what the alignments
   compared, `:events`, `{agent-id [typed event ..]}` (lot M7, an agent's
   events as `palomar.agent-events` types them), and `:cannot-link`, more
   `[{:a :b :reason}]`, and `:authorities`, authority entries (a pool's
   `[{:id :isni :same-as :pseudonym :real-identity :relations} ..]`, lot
   N6): an authority whose IRI names an entry's `:id` (in any spelling,
   `eq/identifier-key`) carries what that record states of its entity
   (`eq/stated`). A local agent's
   dates are those its alignments' provenance states (`?mention-dates`), an
   authority's those it was compared with (`?authority-dates`); a rejected
   alignment is a cannot-link (`:rejected-alignment`); an accepted
   membership is an accepted link from the local agent to its person node
   (an item `:person? true`), and, when the cluster it was accepted in was
   keyed by an authority of the batch (its `?cluster`), from the node to
   that authority: accepting a membership accepts the cluster it names."
  ([records] (clustering-input records {}))
  ([records extra]
   (let [{:keys [ents alignments locals memberships]} (batch-view records)
         dates-of (fn [k id] (some #(when (= id (if (= k '?mention-dates) (:subject %) (target %)))
                                      (parse-dates (bound % k)))
                                   alignments))
         auths    (distinct (map target alignments))
         accepted (filter model/in-force? (mapcat val memberships))
         items    (merge
                   (into {} (for [a accepted] [(target a) {:person? true}]))
                   (into {} (for [id auths :let [d (dates-of '?authority-dates id)]]
                              [id (cond-> {:authority? true :iri (:iri (ents id))} d (assoc :dates d))]))
                   (into {} (for [[id rid] locals :let [d (dates-of '?mention-dates id)]]
                              [id (cond-> {:record rid} d (assoc :dates d))])))
         ;; keyed by the one form of the id: any spelling of an ARK matches
         stated   (into {} (for [[iri st] (eq/stated (:authorities extra))] [(eq/identifier-key iri) st]))
         items    (reduce-kv (fn [m id it] (if (:authority? it) (assoc m id (merge it (stated (eq/identifier-key (:iri it))))) m))
                             items items)
         items    (reduce-kv (fn [m id d] (if (contains? m id) (assoc-in m [id :dates] (parse-dates d)) m))
                             items (:dates extra))
         items    (reduce-kv (fn [m id es] (if (and (contains? m id) (seq es)) (assoc-in m [id :events] (vec es)) m))
                             items (:events extra))]
     {:items       items
      :links       (-> (vec (keep link-of alignments))
                       (into (for [a accepted]
                               {:a (:subject a) :b (target a) :basis :accepted :membership? true
                                :record (::record a) :assertion (model/assertion-id a)}))
                       ;; a membership accepted in a cluster keyed by an authority
                       ;; (its `?cluster`) holds that authority too
                       (into (distinct
                              (for [a accepted
                                    :let [x (some (fn [[id it]] (when (and (:authority? it) (= (:iri it) (bound a '?cluster))) id))
                                                  items)]
                                    :when x]
                                {:a (target a) :b x :basis :accepted :anchor? true}))))
      :cannot-link (into (vec (for [a alignments :when (model/rejected? a)]
                                {:a (:subject a) :b (target a) :reason :rejected-alignment}))
                         (:cannot-link extra))})))

(defn- violation-text [{:keys [a b reason]}] (str (name reason) " between " a " and " b))

(defn- reason-for
  "Why `local`'s membership changes: the refused link's violation that
   names it, else the one of a refused link of its own, else a given
   cannot-link that names it (a rejected alignment), else nil."
  [input result local]
  (let [rs      (:refused result)
        touches #(contains? #{(:a %) (:b %)} local)]
    (or (:violation (or (first (filter (comp touches :violation) rs))
                        (first (filter (comp touches :link) rs))))
        (first (filter touches (:cannot-link input))))))

(defn- add-entity [r e]
  (if (some #(= (:id e) (:id %)) (:entities r)) r (update r :entities (fnil conj []) e)))

(defn- node-plan
  "The person node of each cluster holding a local agent: `{cluster-key
   node-id}`. An existing node (one a membership in force or pending names)
   goes, in order of preference, to the cluster that holds it as an item
   (an accepted membership), that holds its anchor (the authority, then
   the agent, its memberships' `?cluster` names), or that holds most of the
   agents whose membership names it; a cluster with none takes a node
   minted from its key and the batch."
  [items result memberships batch]
  (let [cluster-of (:cluster-of result)
        locals     (filter (comp :record items) (keys items))
        by-iri     (into {} (for [[id it] items :when (:authority? it)] [(:iri it) id]))
        by-str     (into {} (for [l locals] [(str l) l]))
        live       (for [[l ms] memberships m ms :when (not (model/out-of-force? m))] [l m])
        nodes      (distinct (map (comp target second) live))
        anchor     (into {} (for [[_ m] live
                                  :let [k (bound m '?cluster)
                                        a (or (some->> (by-iri k) (vector 2)) (some->> (by-str k) (vector 3)))]
                                  :when a]
                              [(target m) a]))
        choice     (fn [n]
                     (cond
                       (contains? items n) [1 (cluster-of n)]
                       (anchor n)          [(first (anchor n)) (cluster-of (second (anchor n)))]
                       :else (let [fq (frequencies (for [[l m] live :when (= n (target m))] (cluster-of l)))]
                               (when (seq fq)
                                 [4 (key (first (sort-by (juxt (comp - val) (comp str key)) fq)))]))))
        claimed    (group-by (comp second second) (keep #(when-let [c (choice %)] [% c]) nodes))
        existing   (set nodes)]
    (into {}
          (for [{:keys [key members authority]} (:clusters result)
                :let [ls (sort-by str (filter (comp :record items) members))]
                :when (seq ls)]
            [key (or (first (map first (sort-by (juxt (comp first second) (comp str first)) (get claimed key))))
                     (let [m (person-id (if authority [:authority (:iri (items authority)) batch] [:agent (first ls) batch]))]
                       (if (existing m) (person-id [:agent (first ls) batch]) m)))]))))

(defn- flag-refused
  "`records` with each refused alignment that is still pending put to
   `:needs-review`, with the reason, once (`curate/decide-one`)."
  [records result context]
  (let [why (into {} (for [{:keys [link violation tie?]} (:refused result) :when (:assertion link)]
                       [[(:record link) (:assertion link)]
                        (str "refused: " (violation-text violation)
                             (when tie? " (a tie of equal strength, lost to the lower id)"))]))
        one (fn [{:keys [records transitions]} i]
              (let [r    (nth records i)
                    todo (for [a (:assertions r)
                               :let [reason (why [(:id r) (model/assertion-id a)])]
                               :when (and reason (model/pending? a)
                                          (not-any? #(= reason (:reason %)) (curate/history r a)))]
                           [a reason])
                    {r' :record ts :transitions}
                    (reduce (fn [{rr :record tt :transitions} [a reason]]
                              (let [{x :record y :transitions} (curate/decide-one rr a :review reason context)]
                                {:record x :transitions (into tt y)}))
                            {:record r :transitions []} todo)]
                {:records (assoc records i r') :transitions (into transitions ts)}))]
    (reduce one {:records (vec records) :transitions []} (range (count records)))))

(defn- equivalence-text
  "What the records stated in `evidence` (between `a` and `b`), `items`
   naming each record's IRI: `A and B: both state k` for one shared id,
   `A and B: same record id k` for one record under two spellings, else
   each statement."
  ([items evidence] (equivalence-text items nil nil evidence))
  ([items a b evidence]
   (let [iri (fn [id] (or (:iri (items id)) (str id)))
         [s1 s2 :as ss] evidence]
     (cond
       (and (= 1 (count ss)) (:same-record-id s1))
       (str (str/join " and " (sort (map iri (remove nil? [a b])))) ": same record id " (:same-record-id s1))
       (and (= 2 (count ss)) (= (:states s1) (:states s2)))
       (str (str/join " and " (sort (map (comp iri :by) ss))) ": both state " (:states s1))
       :else
       (str/join "; " (for [{:keys [by states]} ss] (str (iri by) " states " states)))))))

(defn- relation-text [iri {:keys [pseudonym of by]}]
  (str (iri by) " states " (iri pseudonym) " is a pseudonym of " (iri of)))

(defn- conflict-text
  "Why a component of stated equivalences is refused (`eq/equivalences`)."
  [items {:keys [reason pairs evidence relations with]}]
  (let [iri (fn [id] (or (:iri (items id)) (str id)))]
    (case reason
      :isni-clash (str (str/join "; " (for [[a b] pairs] (str (iri a) " and " (iri b) " state different ISNIs")))
                       ", and only a chain joins them: " (equivalence-text items evidence))
      :pseudonym-in-component (str "it holds a pseudonym and its real identity: "
                                   (str/join "; " (map #(relation-text iri %) relations)))
      :pseudonym-crossed (str "it and " (str/join ", " (map iri with)) " are each other's pseudonym: "
                              (str/join "; " (map #(relation-text iri %) relations))))))

(defn- warning-text
  [items {:keys [kind members scheme file value violation]}]
  (let [iri (fn [id] (or (:iri (items id)) (str id)))
        ms  (str/join " and " (map iri members))]
    (case kind
      :isni-disagreement        (str ms " are stated one entity by a sameAs, but state different ISNIs")
      :scheme-disagreement      (str ms " are stated one entity, but state different " scheme " ids")
      :same-file                (str ms " are two records of " file " stated one entity: a duplicate")
      :invalid-identifier       (str "an ISNI whose check character is wrong is no id: " value)
      :dates-refuse-equivalence (str ms " are stated one entity, but " (violation-text violation)))))

(defn- aligned-to
  "The `[record [links ..]]` of `input` aligning one of `members`."
  [input members]
  (let [ms (set members)]
    (sort-by (comp str key) (group-by :record (filter #(and (:record %) (ms (:b %))) (:links input))))))

(defn- with-conflicts
  "`records` with their conflict diagnostics restated: those of an earlier
   run removed, one per record and conflict added, naming the pairs, the
   reason and the accepted claims involved; one per record and refused
   equivalence, or equivalence warning (lot N6), for each record aligning
   one of its authorities; and one per record whose accepted membership
   names one of two person nodes a cluster holds (lot N6)."
  [records result input]
  (let [items (:items input)
        iri   (fn [id] (or (:iri (items id)) (str id)))
        membs (:memberships (batch-view records))
        diags (group-by first
                        (concat
                         (for [{:keys [reason members isni pairs relations] :as c} (:equivalence-conflicts result)
                               [rid ls] (aligned-to input members)]
                           [rid (model/diagnostic
                                 {:severity :warning :code equivalence-conflict-code :subject (:a (first (sort-by (comp str :a) ls)))
                                  :message  (str "authority records stated one entity, refused: " (conflict-text items c)
                                                 "; no equivalence of " (str/join ", " (map iri members)) " is used")
                                  :detail   (cond-> {:reason reason :members (mapv iri members)}
                                              isni      (assoc :isni (into (sorted-map) (for [[m i] isni] [(iri m) i])))
                                              pairs     (assoc :pairs (mapv #(mapv iri %) pairs))
                                              relations (assoc :relations (mapv #(update-vals % iri) relations)))})])
                         (for [{:keys [kind members by] :as w} (:equivalence-warnings result)
                               [rid ls] (aligned-to input (or members [by]))]
                           [rid (model/diagnostic
                                 {:severity :warning :code equivalence-warning-code :subject (:a (first (sort-by (comp str :a) ls)))
                                  :message  (warning-text items w)
                                  :detail   (cond-> {:kind kind :members (mapv iri (or members [by]))}
                                              (:scheme w) (assoc :scheme (:scheme w))
                                              (:value w)  (assoc :value (:value w)))})])
                         (for [{:keys [members]} (:clusters result)
                               :let [nodes (set (filter (comp :person? items) members))]
                               :when (< 1 (count nodes))
                               [local ms] (sort-by (comp str key) membs)
                               m ms
                               :when (and (model/in-force? m) (nodes (target m)))]
                           [(::record m) (model/diagnostic
                                          {:severity :warning :code person-nodes-conflict-code :subject local
                                           :message  (str "accepted memberships to " (count nodes) " person nodes now in one cluster: "
                                                          (str/join ", " (sort (map str nodes))) "; none is superseded")
                                           :detail   {:nodes (vec (sort-by str nodes))}})])
                         (for [{:keys [links violations]} (:conflicts result)
                               [rid ls] (sort-by (comp str key) (group-by :record (filter :record links)))]
                           [rid (model/diagnostic
                                 {:severity :warning :code conflict-code :subject (:a (first ls))
                                  :message  (str "accepted identity claims that cannot all hold: "
                                                 (str/join "; " (map violation-text violations)))
                                  :detail   {:reason     (:reason (first violations))
                                             :between    [(:a (first violations)) (:b (first violations))]
                                             :violations violations
                                             :claims     (mapv :assertion ls)}})])
                         (for [{:keys [links violations]} (:event-conflicts result)
                               [rid ls] (sort-by (comp str key) (group-by :record (filter :record links)))]
                           [rid (model/diagnostic
                                 {:severity :warning :code events-conflict-code :subject (:a (first ls))
                                  :message  (str "accepted identity claims kept, though a mention's events cannot be that life: "
                                                 (str/join "; " (map violation-text violations)))
                                  :detail   {:reason     :incompatible-events
                                             :between    [(:a (first violations)) (:b (first violations))]
                                             :violations violations
                                             :claims     (mapv :assertion ls)}})])))]
    (mapv (fn [r]
            (let [kept (vec (remove #(#{conflict-code events-conflict-code equivalence-conflict-code
                                        equivalence-warning-code person-nodes-conflict-code} (:code %))
                                    (:diagnostics r)))
                  ds   (map second (get diags (:id r)))]
              (if (and (empty? ds) (= kept (vec (:diagnostics r))))
                r
                (assoc r :diagnostics (into kept ds)))))
          records)))

(defn- membership
  [local person bindings]
  (model/assertion {:subject local :predicate identity/cluster-membership :value (model/reference person)
                    :status :proposed
                    :provenance (assoc (model/provenance {:pass :infer :rule membership-rule})
                                       :bindings bindings)}))

(defn cluster-records
  "Cluster the agents `records` align (`clustering-input`, with `extra`'s
   `:dates`, `:cannot-link` and `:join`) and write the result into the
   records (see the ns doc):
   - a local agent with an accepted membership is left as it is (a pending
     membership of it naming neither one of its accepted nodes nor its
     cluster's is rejected);
   - otherwise, a pending membership naming another node than its cluster's
     is rejected through `curate`, with the reason (the cannot-link that
     split it), and the agent's membership in its cluster is proposed — the
     earlier claim reopened (`curate/reopen`) when the record holds it out of
     force, else a new `:proposed` one, its provenance naming the rule, the
     cluster's key and size — when the cluster has two or more items or the
     agent had a membership;
   - a refused alignment still pending goes to `:needs-review` with the
     reason, once; a conflict among accepted claims is a diagnostic on each
     record involved, restated on every run;
   - a re-run with the same evidence changes nothing.
   `context` (`:by` `:at`) names who ran it. Returns `{:records :clustering
   :input :transitions}`."
  ([records] (cluster-records records {}))
  ([records {:keys [context join] :as extra}]
   (let [ctx     (or context {})
         input   (cond-> (clustering-input records extra) join (assoc :join join))
         result  (cluster input)
         items   (:items input)
         {:keys [locals memberships]} (batch-view records)
         plan    (node-plan items result memberships (batch-fingerprint records))
         c-of    (:cluster-of result)
         by-key  (into {} (map (juxt :key :members)) (:clusters result))
         size    (fn [k] (count (remove (comp :person? items) (by-key k))))
         eq-of   (into {} (map (juxt :key :equivalence)) (:clusters result))
         eqs     (fn [k] (mapv #(equivalence-text items (:a %) (:b %) (:evidence %)) (eq-of k)))
         pkey    (fn [k] (if (:authority? (items k))
                           (:iri (items k))
                           (str (first (sort-by str (filter (comp :record items) (by-key k)))))))
         revise  (fn [{:keys [record transitions]} local]
                   (let [k     (c-of local)
                         node  (plan k)
                         ms    (get memberships local)
                         in    (filter model/in-force? ms)
                         pend  (filter model/pending? ms)
                         gone  (filter model/out-of-force? ms)
                         keep? (set (cons node (map target in)))
                         why   (str "re-clustered: "
                                    (if-let [v (reason-for input result local)]
                                      (violation-text v)
                                      (str "its cluster is now " (pkey k))))
                         step  (fn [{r :record ts :transitions} a]
                                 (let [{x :record y :transitions} (curate/decide-one r a :reject why ctx)]
                                   {:record x :transitions (into ts y)}))
                         {r1 :record t1 :transitions} (reduce step {:record record :transitions []}
                                                              (remove (comp keep? target) pend))
                         want? (and (empty? in) node
                                    (not-any? #(= node (target %)) pend)
                                    (or (< 1 (size k)) (seq ms)))
                         old   (first (filter #(= node (target %)) gone))
                         {r2 :record t2 :transitions}
                         (cond
                           (not want?) {:record r1 :transitions []}
                           old         (curate/reopen r1 (dissoc old ::record) "re-clustered: in this cluster again" ctx)
                           :else       {:record      (-> r1
                                                         (add-entity (model/entity {:id node :kind :reconcile/person}))
                                                         (update :assertions (fnil conj [])
                                                                 (membership local node (cond-> {'?cluster (pkey k) '?members (size k)}
                                                                                          (seq (eqs k)) (assoc '?equivalence (eqs k))))))
                                        :transitions []})]
                     {:record r2 :transitions (-> transitions (into t1) (into t2))}))
         by-rec  (group-by val locals)
         out     (map (fn [r] (reduce revise {:record r :transitions []} (sort-by str (map key (get by-rec (:id r))))))
                      records)
         flagged (flag-refused (map :record out) result ctx)]
     {:records     (with-conflicts (:records flagged) result input)
      :clustering  result
      :input       input
      :transitions (-> [] (into (mapcat :transitions out)) (into (:transitions flagged)))})))

(defn memberships
  "The membership claims of `record`, `{local-agent [assertion ..]}`, every
   status (`out-of-force` ones included: the history)."
  [record]
  (let [kind (into {} (map (juxt :id :kind)) (:entities record))]
    (group-by :subject (filter #(and (= identity/cluster-membership (:predicate %)) (= :reconcile/person (kind (target %))))
                               (:assertions record)))))

(defn format-clustering
  "A human-readable summary of a `cluster` result, for a curator."
  [{:keys [clusters refused conflicts candidates equivalence-conflicts equivalence-warnings]}]
  (str/join "\n"
            (concat [(str "Agent clusters: " (count (filter #(< 1 (count (:members %))) clusters)) " of two or more, "
                          (count refused) " link(s) refused, " (count conflicts) " conflict(s), "
                          (count candidates) " candidate merge(s) for review")]
                    (for [{:keys [link violation tie?]} refused]
                      (str "  refused " (:a link) " ~ " (:b link) " (" (name (:basis link)) "): "
                           (violation-text violation) (when tie? " (tie)")))
                    (for [{:keys [violations]} conflicts]
                      (str "  conflict among accepted claims: " (str/join "; " (map violation-text violations))))
                    (for [{:keys [members reason]} equivalence-conflicts]
                      (str "  equivalence refused among " (str/join ", " members) " (" (name reason) ")"))
                    (for [{:keys [kind members by]} equivalence-warnings]
                      (str "  equivalence warning (" (name kind) "): " (str/join ", " (or members [by])))))))

(ns palomar.agent-cluster
  "Constrained, revisable clustering of agents into persons (lot M5, ADR 0057).

   **What it replaces.** Before this lot Palomar grouped agents across records
   in one way only: through an authority. `reconcile-agents` groups identified
   agents by their authority IRI, `apply-agent-links` gave every record whose
   accepted link names an authority that authority's node, and the eval's
   `:top-1` rules group mentions by their top proposal. Each is a closure
   through the authority with no check: two mentions linked to one authority
   are one person, whatever else is known of them. And SKOS makes
   `skos:exactMatch` transitive (SKOS Reference S45; symmetric, S44; a
   sub-property of `closeMatch`, S42), so a consumer closing the alignments
   gets the same closure. A chain A~X, X~C where A and C cannot be one person
   (their dates) ends with A and C merged.

   **Inputs** (`cluster`), plain data:
   - `:items`, `{id {:authority? bool :dates profile}}`: the agents (a
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
     `:distinct-authorities` (two different authority records) and
     `:incompatible-dates` (both dated, `agent-dates/compatibility`
     `:incompatible`). A mention accepted for one authority therefore never
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
   claim: `local-agent skos:exactMatch person-node`, the person node a
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
            [palomar.curate :as curate]
            [palomar.model :as model]))

;; ---------------------------------------------------------------------------
;; The clustering, on plain data
;; ---------------------------------------------------------------------------

(def default-join
  "The link bases that may join clusters: a curator's acceptance (a hard
   must-link) and an outright proposal. A link for review never does."
  #{:accepted :propose})

(def ^:private basis-rank {:accepted 0 :propose 1 :review 2})

(defn- link-order
  "Accepted first, then by decreasing weight (none last), ties by the ids."
  [l]
  [(basis-rank (:basis l) 3) (- (or (:weight l) ##-Inf)) (str (:a l)) (str (:b l))])

(defn- strength [l] (vec (take 2 (link-order l))))

(defn- constraint-index
  "The given cannot-links as `{#{a b} reason}`."
  [cannot-link]
  (into {} (for [{:keys [a b reason]} cannot-link :when (not= a b)] [#{a b} (or reason :given)])))

(defn cannot-link-reason
  "The reason `x` and `y` cannot be one person, or nil: a given constraint
   (`index`, `constraint-index`), two different authorities, or dates that
   cannot be one person's."
  [items index x y]
  (when (not= x y)
    (let [ix (items x) iy (items y)]
      (or (index #{x y})
          (when (and (:authority? ix) (:authority? iy)) :distinct-authorities)
          (when (and (:dates ix) (:dates iy)
                     (= :incompatible (:verdict (dates/compatibility (:dates ix) (:dates iy)))))
            :incompatible-dates)))))

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

(defn- cluster-key
  "A cluster's key: its authority when it holds one (at most one, by
   construction), else its first member."
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
      :candidates [links that may not join] ; for a curator, never applied}

   Every item of `:items` or of a link is in exactly one cluster."
  [{:keys [items links cannot-link join] :or {join default-join}}]
  (let [ids      (distinct (concat (keys items) (mapcat (juxt :a :b) links)))
        items    (merge (zipmap ids (repeat {})) items)
        index    (constraint-index cannot-link)
        must     (filter #(and (= :accepted (:basis %)) (join :accepted)) links)
        soft     (sort-by link-order (filter #(and (not= :accepted (:basis %)) (join (:basis %))) links))
        conflict (vec (for [[ms ls] (components ids must)
                            :let [vs (inner-violations items index ms)]
                            :when (seq vs)]
                        {:links ls :violations vs}))
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
                               rival (first (for [[i j] (map-indexed vector joined)
                                                  :when (and (<= n-must i) (= (strength j) (strength l))
                                                             (or (ends (:a j)) (ends (:b j))))]
                                              i))]
                           (cond-> (update st :refused conj {:link l :violation v :tie? (some? rival)})
                             rival (update :tied conj rival)))
                         (update (merge-in st ra rb) :joined conj l)))))
        {:keys [members joined refused tied]} (reduce step phase-1 soft)
        clusters (sort-by (comp str :key)
                          (for [ms (vals members)
                                :let [k (cluster-key items ms)]]
                            {:key k :members ms :authority (when (:authority? (items k)) k)}))]
    {:clusters   (vec clusters)
     :cluster-of (into {} (for [{:keys [key members]} clusters, m members] [m key]))
     :joined     (vec (map-indexed (fn [i l] (if (contains? tied i) (assoc l :tie? true) l)) joined))
     :refused    refused
     :conflicts  conflict
     :candidates (vec (sort-by link-order (remove #(join (:basis %)) links)))}))

(defn transitive-closure
  "The naive clustering, for comparison: the connected components of the
   joining `links` (as `cluster`'s `:join`), no constraint read.
   `{item key}`, the key a component's first member."
  [{:keys [items links join] :or {join default-join}}]
  (:cluster-of (cluster {:items (update-vals (or items {}) #(dissoc % :authority? :dates))
                         :links links :join join})))

(defn violations
  "The cannot-linked pairs `clustering` (`cluster`'s result) puts in one
   cluster, under the same `items` and `cannot-link`: always empty for
   `cluster`, by construction."
  [{:keys [items cannot-link]} clustering]
  (let [ids   (keys (:cluster-of clustering))
        items (merge (zipmap ids (repeat {})) items)
        index (constraint-index cannot-link)]
    (vec (mapcat #(inner-violations items index (:members %)) (:clusters clustering)))))

;; ---------------------------------------------------------------------------
;; In the IR: alignments and memberships in, memberships out
;; ---------------------------------------------------------------------------

(def mapping-predicates #{:skos/exactMatch :skos/closeMatch})

(def membership-rule :reconcile/cluster-agents)

(def conflict-code
  "The diagnostic code of a conflict between accepted claims (ADR 0057)."
  :reconcile/identity-conflict)

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
                    :when (and (= :skos/exactMatch (:predicate a)) (= :reconcile/person (kind (target a))))]
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
      (and (model/in-force? a) (= :skos/exactMatch (:predicate a)))
      (assoc base :basis :accepted)
      (and (model/pending? a) (= :skos/exactMatch (:predicate a)) (= :propose (bound a '?decision)))
      (cond-> (assoc base :basis :propose) (number? w) (assoc :weight (double w)))
      (model/pending? a)
      (cond-> (assoc base :basis :review) (number? w) (assoc :weight (double w))))))

(defn clustering-input
  "The `cluster` input `records` state, with `extra`: `:dates`, `{agent-id
   dates}` (a string or a profile) known beyond what the alignments
   compared, and `:cannot-link`, more `[{:a :b :reason}]`. A local agent's
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
         items    (reduce-kv (fn [m id d] (if (contains? m id) (assoc-in m [id :dates] (parse-dates d)) m))
                             items (:dates extra))]
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

(defn- with-conflicts
  "`records` with their conflict diagnostics restated: those of an earlier
   run removed, one per record and conflict added, naming the pairs, the
   reason and the accepted claims involved."
  [records result]
  (let [diags (group-by first
                        (for [{:keys [links violations]} (:conflicts result)
                              [rid ls] (sort-by (comp str key) (group-by :record (filter :record links)))]
                          [rid (model/diagnostic
                                {:severity :warning :code conflict-code :subject (:a (first ls))
                                 :message  (str "accepted identity claims that cannot all hold: "
                                                (str/join "; " (map violation-text violations)))
                                 :detail   {:reason     (:reason (first violations))
                                            :between    [(:a (first violations)) (:b (first violations))]
                                            :violations violations
                                            :claims     (mapv :assertion ls)}})]))]
    (mapv (fn [r]
            (let [kept (vec (remove #(= conflict-code (:code %)) (:diagnostics r)))
                  ds   (map second (get diags (:id r)))]
              (if (and (empty? ds) (= kept (vec (:diagnostics r))))
                r
                (assoc r :diagnostics (into kept ds)))))
          records)))

(defn- membership
  [local person bindings]
  (model/assertion {:subject local :predicate :skos/exactMatch :value (model/reference person)
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
                                                                 (membership local node {'?cluster (pkey k) '?members (size k)})))
                                        :transitions []})]
                     {:record r2 :transitions (-> transitions (into t1) (into t2))}))
         by-rec  (group-by val locals)
         out     (map (fn [r] (reduce revise {:record r :transitions []} (sort-by str (map key (get by-rec (:id r))))))
                      records)
         flagged (flag-refused (map :record out) result ctx)]
     {:records     (with-conflicts (:records flagged) result)
      :clustering  result
      :input       input
      :transitions (-> [] (into (mapcat :transitions out)) (into (:transitions flagged)))})))

(defn memberships
  "The membership claims of `record`, `{local-agent [assertion ..]}`, every
   status (`out-of-force` ones included: the history)."
  [record]
  (let [kind (into {} (map (juxt :id :kind)) (:entities record))]
    (group-by :subject (filter #(and (= :skos/exactMatch (:predicate %)) (= :reconcile/person (kind (target %))))
                               (:assertions record)))))

(defn format-clustering
  "A human-readable summary of a `cluster` result, for a curator."
  [{:keys [clusters refused conflicts candidates]}]
  (str/join "\n"
            (concat [(str "Agent clusters: " (count (filter #(< 1 (count (:members %))) clusters)) " of two or more, "
                          (count refused) " link(s) refused, " (count conflicts) " conflict(s), "
                          (count candidates) " candidate merge(s) for review")]
                    (for [{:keys [link violation tie?]} refused]
                      (str "  refused " (:a link) " ~ " (:b link) " (" (name (:basis link)) "): "
                           (violation-text violation) (when tie? " (tie)")))
                    (for [{:keys [violations]} conflicts]
                      (str "  conflict among accepted claims: " (str/join "; " (map violation-text violations)))))))

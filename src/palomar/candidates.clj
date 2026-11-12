(ns palomar.candidates
  "Candidate generation, apart from ranking (lot N5, ADR 0060).

   Reconciling a mention is two steps. **Generation** says which authority
   records may be its person. **Ranking** (`reconcile/agent-candidates`,
   the Fellegi-Sunter score of `palomar.agent-match`) weighs each of them
   and decides. This namespace is generation.

   **Lanes.** Generation is a set of lanes. A lane is a function of a
   candidate index (`index`) and a mention. It returns the candidates it
   finds, each `{:ordinal i :id id :lane l :keys [k ..]}`: the entry, the
   lane, and the keys that matched (why). It may also return `:reports`,
   things the ranking does not need but a curator does. Two lanes exist:
   - `:name` (`name-lane`): the entries that share a blocking key with the
     mention's name (`authority-index/blocking-keys`). It is exactly the
     authority index's `block`: every entry the ranking could score by
     its name, in index order;
   - `:identifier` (`identifier-lane`): the entries whose stated ids match
     an id the mention carries (`:ids`, see `palomar.mentions`). Both sides
     are read in one form (`agent-equivalence/identifier-key`). An entry's
     ids are its IRI (`:iri`, else `:id`), its ISNIs (`:isni`) and its
     `:same-as` (`agent-equivalence/authority-keys`). A mention's id that
     matches no entry, matches several, is no valid id, or is not read, is
     reported; the mention's ids naming several entries between them are
     reported too. The lane never picks among them.

   **The union** (`generate`) is every lane's candidates, one per entry, in
   index order, each with `:by`, what each lane that found it says
   (`{:lane :keys}`, and `:values` for the identifier lane).

   **Ranking** (`rank`) scores the union, mention by mention, with
   `reconcile/agent-candidates`. It does not know the lanes: each scored
   candidate carries the lanes only as provenance (`:generated-by`). With
   the name lane alone, the result is the index block's, item for item, and
   so a full scan's. A candidate a lane other than the name lane found is
   weighed on its names and dates even when no name agrees
   (`:score-unnamed`); the model has no identifier feature, so its id adds
   no weight, and with no name agreement it is for review at most. A
   mention's `:authority-record` (a `$3`, the link already made) is read by
   no lane. Name-only ranking
   keeps M3's threshold: a candidate whose name scores below it is dropped,
   whichever lane found it.

   **Recall** (`candidate-sets`, `recall`) measures generation alone: per
   lane and for the union, how many truths are among the candidates, and
   how many candidates that took.

   Nothing here reads a network or a clock."
  (:require [palomar.agent-equivalence :as eq]
            [palomar.authority-index :as index]
            [palomar.reconcile :as reconcile]))

;; ---------------------------------------------------------------------------
;; The candidate index
;; ---------------------------------------------------------------------------

(defn- postings-of
  "`{key [ordinal ..]}` of `entries` by `keys-of`, ordinals ascending."
  [entries keys-of]
  (reduce (fn [m [i e]] (reduce (fn [m k] (update m k (fnil conj []) i)) m (keys-of e)))
          {} (map-indexed vector entries)))

(defn index
  "The candidate index of `x`: `{:entries [..] :postings {blocking-key
   [ordinal ..]} :identifiers {id-key [ordinal ..]}}`, and `:name-block`
   when one is given (`name-lane`). `x` is an authority
   index read (`authority-index/read-index`), whose postings are kept, a
   pool (a sequence of entries), indexed here in its order, or an index
   already made, returned as is."
  [x]
  (let [idx     (if (map? x) x {:entries x})
        entries (vec (:entries idx))]
    (cond-> (assoc idx :entries entries)
      (nil? (:postings idx))    (assoc :postings (postings-of entries index/entry-keys))
      (nil? (:identifiers idx)) (assoc :identifiers (postings-of entries eq/authority-keys)))))

(defn- mention-name [m] (if (map? m) (:name m) m))

;; ---------------------------------------------------------------------------
;; Lanes
;; ---------------------------------------------------------------------------

(defn name-lane
  "The `:name` lane: the entries of `idx` sharing a blocking key with the
   mention's name, in index order, each with the keys it shares. The
   entries are exactly `authority-index/block`'s. With a hand-made block
   (`:name-block f` on the index, lot O6 review: a pool `{:entries :block}`
   read from no index), the entries are those `(f name)` returns, in index
   order, each with the blocking keys it shares (none, perhaps), as the
   matcher was given them before lot O6."
  [{:keys [entries postings name-block]} m]
  (let [nm   (mention-name m)
        hits (reduce (fn [acc k] (reduce (fn [acc i] (update acc i (fnil conj []) k)) acc (get postings k)))
                     (sorted-map) (index/blocking-keys [nm]))
        hits (if name-block
               (let [in (set (name-block nm))]
                 (into (sorted-map) (for [[i e] (map-indexed vector entries) :when (in e)] [i (get hits i [])])))
               hits)]
    {:candidates (vec (for [[i ks] hits] {:ordinal i :id (:id (entries i)) :lane :name :keys ks}))
     :reports    []}))

(defn identifier-lane
  "The `:identifier` lane: the entries of `idx` whose stated ids match one
   of the mention's `:ids`, in index order, each with the matching keys and
   the mention's values. It never reads the mention's `:authority-record`
   (a `$3`: the link the cataloguer already made). Reports, in the order of
   the mention's `:ids`, then its `:unread-ids`:
   `:invalid-identifier` (an ISNI whose check character is wrong),
   `:unread-identifier` (no id form Palomar reads; every `:unread-ids`), `:no-authority` (a key
   no entry states), `:several-authorities` (a key several entries state:
   all are candidates, none is picked); then `:ids-disagree` when the
   mention's ids name several entries and no one key names them all."
  [{:keys [entries identifiers]} m]
  (let [read    (concat (for [v (when (map? m) (:ids m))] [v (eq/identifier v)])
                        ;; what the record states in an id subfield and no id form reads
                        (for [v (when (map? m) (:unread-ids m))] [v nil]))
        keyed   (for [[v r] read :when (:key r)] [v (:key r)])
        hits    (reduce (fn [acc [v k]]
                          (reduce (fn [acc i] (-> acc
                                                  (update-in [i :keys] (fnil conj []) k)
                                                  (update-in [i :values] (fnil conj []) v)))
                                  acc (get identifiers k)))
                        (sorted-map) keyed)
        id-of   #(:id (entries %))
        with    (filter #(seq (get identifiers %)) (distinct (map second keyed)))
        union   (distinct (mapcat #(get identifiers %) with))
        reports (concat
                 (for [[v r] read]
                   (cond (nil? r)      {:lane :identifier :kind :unread-identifier :value v}
                         (:invalid r)  {:lane :identifier :kind :invalid-identifier :value v}
                         (empty? (get identifiers (:key r)))
                         {:lane :identifier :kind :no-authority :key (:key r) :value v}
                         (< 1 (count (get identifiers (:key r))))
                         {:lane :identifier :kind :several-authorities :key (:key r)
                          :authorities (mapv id-of (get identifiers (:key r)))}))
                 (when (and (< 1 (count union)) (not-any? #(= (count union) (count (get identifiers %))) with))
                   [{:lane :identifier :kind :ids-disagree :keys (vec (sort with)) :authorities (mapv id-of (sort union))}]))]
    {:candidates (vec (for [[i h] hits]
                        {:ordinal i :id (id-of i) :lane :identifier
                         :keys (vec (distinct (:keys h))) :values (vec (distinct (:values h)))}))
     :reports    (vec (distinct (remove nil? reports)))}))

(def lanes
  "The lanes, by name."
  {:name name-lane :identifier identifier-lane})

(def lane-order
  "The order lanes are listed in a candidate's `:by`, whatever order they
   were asked in."
  [:name :identifier])

(def default-lanes
  "The lanes `rank` runs unless told: the name lane alone, which is M6's
   block."
  [:name])

(defn generate
  "The union of `lane-names`' candidates for mention `m` in `idx` (an
   `index`): `{:candidates [{:ordinal :id :by [{:lane :keys ..} ..]} ..]
   :reports [..]}`, one candidate per entry, in index order, `:by` in
   `lane-order`. An unknown lane is refused."
  [idx m lane-names]
  (when-let [unknown (seq (remove lanes lane-names))]
    (throw (ex-info (str "candidates: no lane " (pr-str (vec unknown))) {:lanes (vec lane-names) :known lane-order})))
  (let [idx     (index idx)
        results (for [l lane-order :when (some #{l} lane-names)] ((lanes l) idx m))
        by      (reduce (fn [acc c] (update acc (:ordinal c) (fnil conj []) (dissoc c :ordinal :id)))
                        (sorted-map) (mapcat :candidates results))]
    {:candidates (vec (for [[i bs] by] {:ordinal i :id (:id ((:entries idx) i)) :by bs}))
     :reports    (vec (mapcat :reports results))}))

;; ---------------------------------------------------------------------------
;; Ranking the union
;; ---------------------------------------------------------------------------

(def ^:private matcher-mention
  "What `agent-candidates` reads of a mention, without and with anchors:
   its own reading (`reconcile/mention`, `reconcile/relational-mention`)."
  {false @#'reconcile/mention true @#'reconcile/relational-mention})

(defn- dedupe-key
  "What makes two mentions one for ranking with a model: what the matcher
   reads of them (`agent-candidates` scores each distinct one once), and
   their `:ids` and `:unread-ids` when a lane other than the name lane
   reads them."
  [opts lane-names]
  (let [read (matcher-mention (some? (:anchors opts)))
        ids? (some #(not= :name %) lane-names)]
    (fn [m] [(read m) (when (and ids? (map? m)) [(:ids m) (:unread-ids m)])])))

(defn- distinct-by
  "`xs` without the items whose `(f x)` an earlier one had, in order."
  [f xs]
  (let [seen (volatile! #{})]
    (filter (fn [x] (let [k (f x)] (when-not (contains? @seen k) (vswap! seen conj k) true))) xs)))

(defn- mention-ref [m]
  (cond-> {:name (mention-name m)}
    (and (map? m) (some? (:id m))) (assoc :id (:id m))))

(defn- ranked
  "Each of `ms`, ranked alone on its union: `[{:result {:proposals ..}
   :generation {..}}]`, in the order of `ms`."
  [ms authority opts]
  (let [idx    (index (or (:index opts) authority))
        ls     (or (:lanes opts) default-lanes)
        model? (some? (:model opts))
        ;; the pool's statistics, once per run (lot O6): never per mention
        ropts  (cond-> (dissoc opts :lanes :index)
                 (and model? (nil? (:prepared opts)))
                 (assoc :prepared (reconcile/prepare authority (select-keys opts [:model :anchors])))
                 (not model?) (dissoc :prepared))]
    (vec (for [m ms
               :let [g       (generate idx m ls)
                     es      (mapv (:entries idx) (map :ordinal (:candidates g)))
                     unnamed (set (for [c (:candidates g) :when (some #(not= :name (:lane %)) (:by c))] (:id c)))
                     by      (into {} (map (juxt :id :by)) (:candidates g))
                     r       (reconcile/agent-candidates [m] authority
                                                         (cond-> (assoc ropts :block (constantly es))
                                                           (and model? (seq unnamed)) (assoc :score-unnamed unnamed)))
                     tag     (fn [xs] (mapv #(if-some [b (by (:authority-id %))] (assoc % :generated-by b) %) xs))]]
           {:result     (update-vals r tag)
            :generation {:mention    (mention-ref m)
                         :candidates (mapv #(select-keys % [:id :by]) (:candidates g))
                         :reports    (:reports g)}}))))

(defn- lane-mention
  "What a lane and the matcher are given of mention `m`: never its
   `:authority-record` (a `$3`, the link already made)."
  [m]
  (if (map? m) (dissoc m :authority-record) m))

(defn rank
  "`reconcile/agent-candidates` on the candidates the lanes generate:
   `(rank mentions authority opts)`, `opts` those of `agent-candidates`
   plus `:lanes` (default `default-lanes`) and `:index` (a candidate index
   or an authority index read of `authority`; default, `authority`
   indexed here). Each mention's union (`generate`) is passed as its
   `:block`; with a model, the candidates no name key found are weighed
   too (`:score-unnamed`): every candidate a lane other than the name lane
   found, so that a name key whose name level is `:none` hides none.
   With a model, the pool's statistics are computed once for the run
   (`reconcile/prepare`, lot O6), or taken from `:prepared`.
   Mentions are deduplicated as `agent-candidates` does: with a model, the
   mentions the matcher reads alike (`reconcile/mention`) are scored once,
   their ids telling them apart when the identifier lane runs; name-only,
   none is. A mention's `:authority-record` is given to no lane nor to the
   matcher. The result is `agent-candidates`', its items in
   the same order, each with `:generated-by` (the lanes that found it,
   `generate`'s `:by`), plus `:generation`, per mention scored `{:mention
   {:name :id} :candidates [{:id :by}] :reports [..]}`. A name-only
   threshold of 0 or less scans the whole pool, as `agent-candidates`
   does: its candidates outside the union carry no `:generated-by`."
  ([mentions authority] (rank mentions authority {}))
  ([mentions authority opts]
   (let [opts   (if (map? opts) opts {:threshold opts})
         ls     (or (:lanes opts) default-lanes)
         model? (some? (:model opts))
         ms     (map lane-mention mentions)
         ;; as `agent-candidates`: with a model, each distinct mention once
         ms     (if model? (distinct-by (dedupe-key opts ls) ms) ms)
         per    (ranked ms authority opts)
         all    (fn [k] (vec (mapcat (comp k :result) per)))
         ;; the order `agent-candidates` gives a batch: best first across mentions
         best   (fn [k] (vec (sort-by (if model? (comp - :match-weight) (comp - :score)) (all k))))]
     (cond-> {:proposals  (best :proposals)
              :rejected   (if model? (all :rejected) (best :rejected))
              :generation (mapv :generation per)}
       model? (assoc :abstained (best :abstained))))))

(defn rank-each
  "`rank` mention by mention: for each of `mentions`, in order, `{:mention m
   :proposals :abstained :rejected :generation}`, what `rank` gives that
   mention alone (its items `agent-candidates`' order, with
   `:generated-by`). With a model, the mentions the matcher and the lanes
   read alike are scored once and share their result. What a
   reconciliation run decides each mention by (lot O6): unlike `rank`'s
   batch, two mentions read alike keep their own `:mention`."
  [mentions authority opts]
  (let [ls     (or (:lanes opts) default-lanes)
        model? (some? (:model opts))
        ms     (mapv lane-mention mentions)
        k      (if model? (dedupe-key opts ls) identity)
        ds     (if model? (distinct-by k ms) ms)
        per    (ranked ds authority opts)
        by-key (when model? (zipmap (map k ds) per))]
    (vec (map-indexed (fn [i m]
                        (let [{:keys [result generation]} (if model? (by-key (k m)) (per i))]
                          (assoc (select-keys result [:proposals :abstained :rejected])
                                 ;; a shared result, each mention's own reference
                                 :mention (nth mentions i) :generation (assoc generation :mention (mention-ref m)))))
                      ms))))

;; ---------------------------------------------------------------------------
;; Recall of generation
;; ---------------------------------------------------------------------------

(defn candidate-sets
  "Per mention of `mentions`, the candidates of each of `lane-names` and of
   their union in `idx` (anything `index` takes): `[{:mention m :lanes {lane
   [id ..]} :union [id ..] :reports [..]}]`, ids in index order. What the
   hard benchmark (lot N3) or a ranking study (lot N4) needs per lane."
  [idx mentions lane-names]
  (let [idx (index idx)]
    (vec (for [m mentions
               :let [g (generate idx m lane-names)]]
           {:mention m
            :lanes   (into {} (for [l lane-names]
                                [l (vec (for [c (:candidates g) :when (some #(= l (:lane %)) (:by c))] (:id c)))]))
            :union   (mapv :id (:candidates g))
            :reports (:reports g)}))))

(defn- ratio [n d] (when (pos? d) (/ n d)))

(defn recall
  "The recall of generation on `mentions` in `idx`, per lane and for the
   union: `opts` `{:lanes [..] :truth f}`, `f` a mention's truth (an id, a
   set of ids, or nil: none), or `{:truths [..]}`, the truths in the order
   of `mentions`, so that no mention passed need carry its truth. A mention is judged when one of its truths is
   an entry of `idx` (`:in-index`); it is found by a lane when one of its
   truths is among that lane's candidates. Per lane and for the `:union`:
   `{:found :recall :candidates :mean :max}`, the last three the cost (the
   candidates over every mention, per mention, at most); `:only`, per lane,
   the judged truths no other lane found; `:per-mention`, each mention's
   truths and the lanes that found them."
  [idx mentions {:keys [lanes truth truths] :or {lanes default-lanes}}]
  (let [idx    (index idx)
        ids    (set (map :id (:entries idx)))
        sets   (candidate-sets idx mentions lanes)
        tset   (fn [t] (cond (nil? t) #{} (coll? t) (set t) :else #{t}))
        tvec   (when truths (vec truths))
        rows   (for [[i s] (map-indexed vector sets)
                     :let [t (tset (if tvec (get tvec i) (truth (:mention s))))]]
                 (assoc s :truth t :judged? (boolean (some ids t))
                        :found (into {} (for [l (conj lanes :union)]
                                          [l (boolean (some t (if (= :union l) (:union s) (get-in s [:lanes l]))))]))))
        judged (filter :judged? rows)
        n      (count judged)
        stat   (fn [l]
                 (let [cs    (map #(if (= :union l) (:union %) (get-in % [:lanes l])) rows)
                       found (count (filter #(get-in % [:found l]) judged))
                       total (reduce + (map count cs))]
                   {:found found :recall (ratio found n) :candidates total
                    :mean (ratio total (count rows)) :max (reduce max 0 (map count cs))}))]
    {:mentions    (count rows)
     :with-truth  (count (filter (comp seq :truth) rows))
     :in-index    n
     :lanes       (into {} (for [l lanes] [l (stat l)]))
     :union       (stat :union)
     :only        (into {} (for [l lanes]
                             [l (count (filter (fn [r] (and (get-in r [:found l])
                                                            (not-any? #(get-in r [:found %]) (remove #{l} lanes))))
                                               judged))]))
     :per-mention (vec (for [r rows] {:mention (:mention r) :truth (:truth r) :found (:found r)}))}))

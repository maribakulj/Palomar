(ns palomar.reconciliation
  "A reconciliation run: one engine over a batch of records and an
   authority pool (lot N2, ADR 0058 amendment). `palomar.api` and the CLI
   `reconcile` verb both call it, so the two decide alike.

   **Engines.**
   - `:historical` (the default) is `reconcile` as it was before this lot:
     the distinct names of the canonical floor (`agents/occurrences`)
     matched by token-set similarity at 0.5 (`reconcile/propose-agent-links`),
     beside the reconciliation by authority id (`reconcile/reconcile-agents`).
     Its output does not change.
   - `:probabilistic` reads every mention of the batch (`palomar.mentions`:
     name, dates, kind, roles, events, read from the source, not the
     floor), and decides each with `reconcile/agent-candidates` under a
     Fellegi-Sunter model (`palomar.agent-match`): propose, review or
     abstain at the model's thresholds, or rejected for its dates or
     events. A decision states a confidence only when its calibration proof
     holds (`agent-match/proven-confidence`); the shipped model is not
     calibrated, so it states none.

   **The model** is a parameter file (`read-model`): by default the
   classpath's `palomar/models/agent-match.edn`, the M4 model of the
   person-reconciliation eval. Its version is its path and the SHA-256 of
   its bytes, as the run manifest names a vocabulary.

   **Pools** are `{:entries [..] :block f}` (`read-authorities`, with
   `:index` when read from an authority index), or a plain vector of
   entries. The name lane reads the index's postings; a pool with a
   `:block` and no `:index` keeps its block, whose entries are the name
   lane's (a hand-made block, honoured as before lot O6).

   **Lanes** (lot O6, ADR 0060 amendment): the probabilistic engine ranks
   the candidates `palomar.candidates` generates (`:lanes`, default the
   name lane, whose decisions are lot N2's), the pool's statistics
   prepared once (`reconcile/prepare`). Lanes asked are stated in the
   result, and `explain` names the lanes that found each candidate.

   `explain` says why one mention was decided as it was; `clusters` groups
   the batch's mentions into persons (`agent-cluster/cluster`) from those
   decisions."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [palomar.agent-cluster :as ac]
            [palomar.agent-dates :as dates]
            [palomar.agent-match :as fs]
            [palomar.authority-index :as index]
            [palomar.candidates :as lanes]
            [palomar.manifest :as manifest]
            [palomar.mentions :as mentions]
            [palomar.model :as model]
            [palomar.plugins.agents :as agents]
            [palomar.plugins.authorities :as authorities]
            [palomar.reconcile :as reconcile]))

(def engines
  "The engines a reconciliation runs with."
  #{:historical :probabilistic})

(def default-engine :historical)

(def lane-names
  "The candidate lanes a probabilistic run may name (lot O6,
   `palomar.candidates`)."
  (set lanes/lane-order))

(def historical-model
  "What the historical engine decides by: a name's token-set similarity, at
   0.5. What a run manifest says of its model."
  {:name "token-jaccard" :threshold 0.5})

(def default-model
  "The classpath path of the shipped model's parameter file."
  "palomar/models/agent-match.edn")

;; ---------------------------------------------------------------------------
;; The model and the pool
;; ---------------------------------------------------------------------------

(defn- refuse [msg data] (throw (ex-info (str "model: " msg) data)))

(defn- check-model!
  "`model`, refused unless its prior is in (0, 1), its thresholds are
   0 < review <= propose < 1 (lot N2 review), and its gap (lot O4,
   `agent-match/competing-gap`) is absent or a finite number above 0."
  [{:keys [prior thresholds] :as model}]
  (let [{:keys [propose review gap]} thresholds]
    (when-not (or (nil? gap) (and (number? gap) (Double/isFinite (double gap)) (pos? gap)))
      (refuse (str "the gap must be a finite number of bits above 0, or absent, got " (pr-str gap)) {:gap gap}))
    (when-not (and (number? prior) (< 0.0 prior 1.0))
      (refuse (str "the prior must be a number strictly between 0 and 1, got " (pr-str prior)) {:prior prior}))
    (when-not (and (number? propose) (number? review) (< 0.0 review) (<= review propose) (< propose 1.0))
      (refuse (str "the thresholds must be 0 < review <= propose < 1, got " (pr-str thresholds)) {:thresholds thresholds}))
    model))

(defn- check-params!
  "`params`, refused unless they are a map whose `:m-counts` and `:u-counts`
   count every feature of `agent-match/features` on a positive number of
   pairs: a model file is measured, never hand-set shares."
  [params]
  (when-not (map? params)
    (refuse "a parameter file is one EDN map" {:type (type params)}))
  (doseq [side [:m-counts :u-counts]
          f    (keys fs/features)
          :let [{:keys [n counts]} (get-in params [side f])]]
    (when-not (and (number? n) (pos? n) (map? counts) (every? #(and (number? %) (not (neg? %))) (vals counts)))
      (refuse (str (name side) " must count feature " (name f) " on a positive number of pairs (:n), with its :counts")
              {:side side :feature f})))
  params)

(defn model-version
  "The version of `model`, derived from the model itself: `:sha256`, the
   SHA-256 of its parameters as the model holds them (counts, prior,
   thresholds and gap, bar, the `:rules` it states, estimation, provenance,
   calibration — not the weights, which are computed), and `:weights`, the
   fingerprint of the weights it computed. A model that states no rules
   (an N4-era file) has the version it had. A comment or a layout change in its file is no new version."
  [model]
  {:sha256  (model/content-digest (dissoc model :weights) 64)
   :weights (fs/fingerprint (:weights model))})

(defn- model-of
  "The model of parameters `params` (`agent-match/model`), checked, with
   its provenance and its calibration report when the file carries them."
  [params]
  (check-params! params)
  (check-model!
   (cond-> (fs/model params)
     (:provenance params)  (assoc :provenance (:provenance params))
     (:calibration params) (fs/with-calibration (:calibration params)))))

(defn- loaded [^bytes bytes path]
  (let [m (model-of (edn/read-string (String. bytes "UTF-8")))]
    {:model m :version (assoc (model-version m) :path path)}))

(def models-prefix
  "The classpath directory of the shipped model files."
  "palomar/models/")

(defn- from [loaded source] (assoc-in loaded [:version :source] source))

(def ^:private shipped
  (delay (from (loaded (with-open [in (io/input-stream (io/resource default-model))] (.readAllBytes in)) default-model)
               :classpath)))

(defn read-model
  "The model of a parameter file, `{:model m :version {:sha256 :weights
   :path :source}}`: the shipped one (`default-model`, from the classpath),
   or the one `path` names. A path under `models-prefix` (such as
   `palomar/models/agent-match-n3.edn`, lot N4) names a model shipped on
   the classpath when there is one; any other path is a file. `:source`
   says which was read, `:classpath` or `:file`. Refused: a classpath path
   with a `..` segment, and a file on disk that shadows a shipped model at
   the same path (name the file `./palomar/models/...` to read it). The
   parameters are those `agent-match/model` takes from counts (`:m-counts`
   and `:u-counts` for every feature; `:prior`; `:thresholds`, with an
   optional `:gap`; `:bar`; `:rules`, lot O4, the N4 rules when absent),
   plus an optional `:provenance` and `:calibration` report; anything else
   is refused (`check-params!`, `check-model!`). The version is
   `model-version`'s; `:path` is for information only."
  ([] @shipped)
  ([path]
   (if (nil? path)
     @shipped
     (let [p      (str path)
           f      (io/file p)
           under? (str/starts-with? p models-prefix)]
       (when (and under? (some #{".."} (str/split p #"[/\\]")))
         (refuse (str p ": a shipped model is named without a .. segment") {:path p}))
       (let [res (when under? (io/resource p))]
         (cond
           (and res (.exists f))
           (refuse (str "the file " p " shadows the shipped model of that name: name the file ./" p
                        " to read it, or remove it to read the shipped one")
                   {:path p})
           res   (from (loaded (with-open [in (io/input-stream res)] (.readAllBytes in)) p) :classpath)
           :else (from (loaded (java.nio.file.Files/readAllBytes (.toPath f)) p) :file)))))))

(defn in-sample?
  "Whether `model` would score what it was fitted on:
   - fitted in-sample (`:provenance :split :all-in-sample`), on pool `pool`
     (its entries, by `agent-match/pool-fingerprint`, as the eval reads it
     or as `read-authorities` does);
   - any model, on one of the source files `sources` its provenance names
     (`:data`, by their SHA-256);
   - a model fitted on one split of a benchmark (lot N4, `:train-keys`, the
     persons and no-match names of that split), on a mention whose split
     key is one of `keys`. Its pool is not refused: every split of the
     benchmark is matched against it."
  ([model pool] (in-sample? model pool nil))
  ([model pool {:keys [sources] split-keys :keys}]
   (let [{:keys [split train-keys] :as prov} (:provenance model)
         used    (set (map :sha256 (:data prov)))
         trained (set train-keys)]
     (boolean (or (and (= :all-in-sample split) (seq pool)
                       (#{(:pool prov) (:pool-as-read prov)} (fs/pool-fingerprint (vec pool))))
                  (and (seq used) (some #(used (:sha256 (manifest/fingerprint (io/file %)))) sources))
                  (and (seq trained) (some trained split-keys)))))))

(defn refuse-in-sample!
  "Refuse to score with `model` the pool, the sources or the mentions (by
   their split keys, `:keys`) it was fitted on (`in-sample?`): an eval or a
   benchmark built from its training data would measure nothing. Nil when
   it may."
  ([model pool] (refuse-in-sample! model pool nil))
  ([model pool opts]
   (when (in-sample? model pool opts)
     (throw (ex-info (str "this model was fitted in-sample on this pool, these records or these mentions ("
                          (pr-str (select-keys (:provenance model) [:split :pool])) "): it must not score them")
                     {:provenance (:provenance model)})))))

(defn- dump-file?
  "Whether `f` looks like an authority dump, which a reconciliation never
   reads (a dump is indexed first, lot M6): a compressed file (`.gz`,
   `.bz2`, `.xz`, `.zst`, `.zip`), or JSON — a first character, after a
   byte-order mark and blank lines, that opens a JSON object with a key or
   an array. Only the first 4096 characters are read."
  [^java.io.File f]
  (or (boolean (re-find #"(?i)\.(gz|bz2|xz|zst|zip)$" (.getName f)))
      (with-open [r (io/reader f :encoding "UTF-8")]
        (let [buf  (char-array 4096)
              n    (.read r buf)
              head (str/triml (str/replace-first (String. buf 0 (max 0 n)) #"^\uFEFF" ""))]
          (boolean (re-find #"^(?:\{\s*\"|\[)" head))))))

(defn read-authorities
  "The pool of authority file `path`: `{:entries :block :manifest}`, an
   authority index read with its blocking lookup (lot M6) and the index
   itself (`:index`, whose postings the name lane reads, lot O6), else an
   authority file (MADS or SKOS as RDF/XML or N-Triples, EAC-CPF, BnF
   INTERMARC authority records) read whole; `:manifest` what the run
   manifest says of it. A dump is refused: it is indexed first."
  [path]
  (let [f (io/file path)]
    (cond
      (index/index-file? f)
      (let [idx (index/read-index f)]
        {:entries (:entries idx) :block (index/block idx) :index idx :manifest (index/manifest-entry path)})

      (dump-file? f)
      (throw (ex-info (str path " is an authority dump, which is not read during a reconciliation:"
                           " build an index first with `palomar authorities index " path " --out <index>`")
                      {:path path}))

      :else
      (let [s (slurp f)]
        {:entries  (:entries (authorities/read-pool (authorities/sniff-format path s) s))
         :manifest (assoc (manifest/fingerprint f) :path path)}))))

(defn- entries-of [pool] (if (map? pool) (:entries pool) pool))

(defn- block-of [pool] (when (map? pool) (:block pool)))

(defn- model-opt
  "The `{:model :version}` of `opts`: its `:model`, either a `read-model`
   result, whose version must be its model's, or a bare model (versioned by
   `model-version`), else the shipped one. A model is checked
   (`check-model!`) before it is used."
  [{:keys [model]}]
  (cond (nil? model)     (read-model)
        (:version model) (let [{m :model v :version} model]
                           (check-model! m)
                           (when-not (= (dissoc v :path :source) (model-version m))
                             (throw (ex-info "model: this version is not its model's: a version is derived from the model it names"
                                             {:version v :expected (model-version m)})))
                           model)
        (:weights model) {:model (check-model! model) :version (model-version model)}
        :else (throw (ex-info "reconciliation: :model is a read-model result or an agent-match model" {}))))

(defn- lanes-of
  "The lanes `opts` asks for, in `candidates/lane-order`, or nil when it
   asks none. Refused: no lane, an unknown one, anything but a sequence of
   keywords."
  [{:keys [lanes] :as opts}]
  (when (contains? opts :lanes)
    (when-not (and (sequential? lanes) (seq lanes) (every? lanes/lanes lanes))
      (throw (ex-info (str "reconciliation: :lanes is a non-empty sequence of " (pr-str lanes/lane-order) ", got " (pr-str lanes))
                      {:lanes lanes :known lanes/lane-order})))
    (filterv (set lanes) lanes/lane-order)))

(defn- engine-of [opts]
  (let [e (get opts :engine default-engine)]
    (when-not (engines e)
      (throw (ex-info (str "unknown engine " (pr-str e) ": one of " (pr-str (sort engines))) {:engine e})))
    e))

;; ---------------------------------------------------------------------------
;; Deciding
;; ---------------------------------------------------------------------------

(defn- lane-mention
  "What the lanes and the matcher are given of mention `m`: neither its
   `:authority-record` (a `$3`, the link already made, ADR 0060 §3) nor its
   `:source` (whose subfields hold the `$3`). Neither reads them."
  [m]
  (dissoc m :authority-record :source))

(defn- candidates
  "Each mention of `records` with what ranking made of it under `model`
   against `pool`: `[[mention {:proposals :abstained :rejected} reports]
   ..]`, `reports` what the lanes reported of the mention. The
   candidates are those `lanes` generate (default the name lane), ranked by
   `candidates/rank-each` with the pool's statistics prepared once
   (`reconcile/prepare`, lot O6). The name lane reads the pool's authority
   index when it was read from one; else a pool's own `:block`, when it
   has one (its entries, in pool order); else the entries indexed in
   memory. By the name lane alone, the decisions are lot N2's."
  [records pool model ls]
  (let [ms (mentions/batch records)
        _  (when-let [[id] (first (filter #(< 1 (val %)) (frequencies (map :id ms))))]
             (throw (ex-info (str "the batch names mention " (pr-str id) " twice: is a record given more than once?")
                             {:mention id})))
        es (entries-of pool)
        rs (when (seq es)
             (lanes/rank-each (map lane-mention ms) es
                              {:model    model
                               :lanes    (or ls lanes/default-lanes)
                               :index    (cond (and (map? pool) (:index pool)) (:index pool)
                                               (block-of pool) {:entries es :name-block (block-of pool)}
                                               :else es)
                               :prepared (reconcile/prepare es)}))]
    (vec (for [[i m] (map-indexed vector ms)]
           [m (if rs
                (select-keys (rs i) [:proposals :abstained :rejected])
                {:proposals [] :abstained [] :rejected []})
            (if rs (get-in (rs i) [:generation :reports]) [])]))))

(defn- mention-decision
  "What a mention comes to: its best proposal's decision, else abstained,
   else rejected, else no candidate."
  [{:keys [proposals abstained rejected]}]
  (cond (seq proposals) (:decision (first proposals))
        (seq abstained) :abstain
        (seq rejected)  :rejected
        :else           :none))

(defn- decision-entry [m {:keys [proposals abstained rejected] :as cs}]
  (cond-> {:mention   (:id m)
           :name      (:name m)
           :record    (:record m)
           :type      (:type m)
           :decision  (mention-decision cs)
           :proposals (vec (for [p proposals
                                 :let [c (fs/proven-confidence p)]]
                             (cond-> (select-keys p [:authority-id :authority-label :decision :score :match-weight
                                                     :review-reason :entity-type])
                               c (assoc :confidence c))))
           :abstained (count abstained)
           :rejected  (count rejected)}
    (:kind m) (assoc :kind (:kind m))))

(defn- historical [records pool ls]
  (when (and ls (not= [:name] ls))
    (throw (ex-info (str "the historical engine reads names alone: lanes " (pr-str ls)
                         " are the probabilistic engine's (:engine :probabilistic)")
                    {:engine :historical :lanes ls})))
  (let [names (distinct (map :name (mapcat agents/occurrences records)))
        ps    (when pool
                (reconcile/propose-agent-links names (entries-of pool)
                                               (cond-> {:threshold (:threshold historical-model)}
                                                 (block-of pool) (assoc :block (block-of pool)))))]
    (cond-> {:engine    :historical
             :model     historical-model
             :pool?     (some? pool)
             :agents    (reconcile/reconcile-agents records)
             :proposals (vec ps)
             :decisions (mapv #(select-keys % [:name :authority-id :authority-label :score]) ps)}
      ls (assoc :lanes ls))))

(defn- with-provenance [result model]
  (cond-> result (:provenance model) (assoc :provenance (:provenance model))))

(defn- probabilistic [records pool opts ls]
  (let [{:keys [model version]} (model-opt opts)]
    (with-provenance
      (cond-> {:engine      :probabilistic
               :model       version
               :pool?       (some? pool)
               :agents      (reconcile/reconcile-agents records)
               :floor-names (count (distinct (map :name (mapcat agents/occurrences records))))
               :decisions   (mapv (fn [[m cs reports]] (cond-> (decision-entry m cs) ls (assoc :reports reports)))
                                  (candidates records pool model ls))}
        ls (assoc :lanes ls))
      model)))

(defn same-agent-proposals
  "The proposals that two occurrences of one name in one record of
   `records` are one agent (`mentions/same-agent-proposals`, lot O3), as
   `reconcile` shows them: `[{:record :name :occurrences [id id] :evidence}
   ..]`. They are proposals for a curator; `agent-cluster/cluster-records`
   writes them into the records."
  [records]
  (vec (for [r records p (mentions/same-agent-proposals r)]
         (select-keys p [:record :name :occurrences :evidence]))))

(defn reconcile
  "Reconcile the agents of `records` against `pool` (nil: none) with
   `(:engine opts)`, `:historical` by default (see the ns doc). Returns

     {:engine :model :pool? :agents (reconcile/reconcile-agents) :decisions [..]}

   `:model` the model's version (`historical-model`, or the parameter
   file's `{:path :sha256 :bytes}`). Historical: `:proposals` (those of
   `propose-agent-links`, whole) and `:decisions`, one per proposal,
   `{:name :authority-id :authority-label :score}`. Probabilistic: one
   decision per mention (`mentions/batch`), in order,

     {:mention id :name :record :type :kind :decision :propose|:review|:abstain|:rejected|:none
      :proposals [{:authority-id :authority-label :decision :score :match-weight :review-reason :confidence}]
      :abstained n :rejected n}

   `:confidence` only where the calibration proof holds. Both engines also
   give `:same-agent-proposals` (`same-agent-proposals`, review of lot O3):
   two occurrences of one name in one record that may be one agent. `opts`:
   `:engine`, `:model` (`read-model`'s result, default the shipped model),
   `:lanes` (lot O6): the candidate lanes of the probabilistic engine
   (`palomar.candidates`, `[:name]`, `[:identifier]` or both; default the
   name lane, whose decisions are lot N2's). Lanes asked are stated under
   `:lanes`, in `candidates/lane-order`, and each decision then carries
   its mention's `:reports` (`candidates/identifier-lane`); none asked,
   neither is stated. The
   historical engine reads names alone: it refuses any lane but `[:name]`."
  ([records pool] (reconcile records pool {}))
  ([records pool opts]
   (assoc (let [ls (lanes-of opts)]
            (case (engine-of opts)
              :historical    (historical records pool ls)
              :probabilistic (probabilistic records pool opts ls)))
          :same-agent-proposals (same-agent-proposals records))))

;; ---------------------------------------------------------------------------
;; Explaining one mention
;; ---------------------------------------------------------------------------

(defn- fmt [pattern & args] (String/format java.util.Locale/ROOT pattern (to-array args)))

(defn- dates-text [p]
  (or (:text p)
      (some->> (for [role [:birth :death :active] :let [t (:text (get p role))] :when t] (str (name role) " " t))
               seq
               (str/join ", "))))

(defn- candidate-why
  [{:keys [propose review gap]} competing {:keys [decision score reason review-reason evidence]}]
  (case (or reason decision)
    :propose  (if gap
                (fmt "posterior %.4f is at or above the propose threshold %s, and no other candidate of the mention at or above the review threshold %s is within %s bits of it"
                     score propose review gap)
                (fmt "posterior %.4f is at or above the propose threshold %s, and no other candidate of the mention is at or above the review threshold %s"
                     score propose review))
    :review   (case review-reason
                :competing
                (if gap
                  (fmt "posterior %.4f is at or above the review threshold %s, but %d candidates of the mention are, within %s bits of the top one: all go to review"
                       score review competing gap)
                  (fmt "posterior %.4f is at or above the review threshold %s, but %d candidates of the mention are: all go to review"
                       score review competing))
                :outranked
                (fmt "posterior %.4f is at or above the propose threshold %s, but another candidate of the mention weighs %s bits or more above it: outranked, for review"
                     score propose gap)
                :no-name-agreement
                (fmt "posterior %.4f is at or above the propose threshold %s, but no name agrees: a candidate a lane other than the name brought is for review at most"
                     score propose)
                (fmt "posterior %.4f is between the review threshold %s and the propose threshold %s" score review propose))
    :abstain  (fmt "posterior %.4f is below the review threshold %s: abstained, no claim" score review)
    :incompatible-dates
    (str "its dates (" (or (dates-text (get-in evidence [:dates :candidate])) "?") ") cannot be the mention's ("
         (or (dates-text (get-in evidence [:dates :mention])) "?") "): rejected, never weighed")
    :incompatible-events
    "an event the mention's record gives it cannot fall in this candidate's life: rejected, never weighed"
    :kind-mismatch
    (str "its kind (" (get-in evidence [:kind :candidate]) ") is not the mention's ("
         (some-> (get-in evidence [:kind :mention]) name) "): rejected, never weighed")
    (str "decided " (pr-str (or reason decision)))))

(defn- competing-count
  "How many of a mention's `proposals` compete (`:review-reason
   :competing`); with none, how many proposals it has."
  [proposals]
  (let [k (count (filter #(= :competing (:review-reason %)) proposals))]
    (if (pos? k) k (count proposals))))

(defn- explained [thresholds competing p]
  (let [c (fs/proven-confidence p)
        g (:generated-by p)]
    (cond-> {:authority-id    (:authority-id p)
             :authority-label (:authority-label p)
             :levels          (get-in p [:evidence :comparison])
             :why             (candidate-why thresholds competing p)}
      (:match-weight p)  (assoc :weights (get-in p [:evidence :weights]) :total (:match-weight p)
                                :posterior (:score p) :decision (:decision p))
      (:review-reason p) (assoc :review-reason (:review-reason p))
      (:reason p)        (assoc :reason (:reason p) :dates (get-in p [:evidence :dates]))
      (get-in p [:evidence :events]) (assoc :events (get-in p [:evidence :events]))
      c                  (assoc :confidence c)
      g                  (assoc :generated-by g))))

(defn explain
  "Why mention `mention-id` of `records` was decided as it was, under the
   probabilistic engine (`opts`: `:model`, as `reconcile`):

     {:engine :probabilistic :model version
      :mention {:id :name :record :type :kind :role :roles :dates :events :source}
      :prior λ :prior-weight log2(λ/(1-λ)) :thresholds {:propose :review}
      :calibrated? bool    ; whether the model's posterior is a confidence on this pool
      :decision kw :why \"..\"
      :candidates [{:authority-id :authority-label :levels {:name :form :dates}
                    :weights {:name :form :dates :tf} :total w :posterior p
                    :decision :why} ..]}

   the candidates proposed first, then abstained, then rejected (these with
   their `:reason` and the `:dates` compared, never weighed). `:total` is
   the sum of the field weights; the posterior adds the prior's weight to
   it. With `:lanes` (lot O6, as `reconcile`), each candidate also says
   which lanes found it and the keys that matched (`:generated-by`, `[{:lane
   :keys ..}]`), and the result states `:lanes` and the mention's
   `:reports` (what the identifier lane reports: an id several entries
   state, ids that disagree, an id no entry states, an invalid or an
   unread id). Refuses a mention id the
   batch does not have."
  ([records pool mention-id] (explain records pool mention-id {}))
  ([records pool mention-id opts]
   (let [ls     (lanes-of opts)
         {:keys [model version]} (model-opt opts)
         [m cs reports] (or (first (filter #(= mention-id (:id (first %))) (candidates records pool model ls)))
                            (throw (ex-info (str "no mention " (pr-str mention-id) " in the batch") {:mention mention-id})))
         {:keys [proposals abstained rejected]} cs
         th     (:thresholds model)
         n      (competing-count proposals)
         all    (mapv #(explained th n (cond-> % (not ls) (dissoc :generated-by))) (concat proposals abstained rejected))
         d      (mention-decision cs)
         prior  (:prior model)]
     (with-provenance
       (cond->
        {:engine       :probabilistic
         :model        version
         :mention      (cond-> (select-keys m [:id :name :record :type :kind :role :roles :relators :dates :events])
                         (:source m) (assoc :source (dissoc (:source m) :subfields)))
         :prior        prior
         :prior-weight (/ (StrictMath/log (/ prior (- 1.0 prior))) (StrictMath/log 2.0))
         :thresholds   th
         :calibrated?  (fs/calibrated? model (vec (entries-of pool)))
         :decision     d
         :why          (case d
                         (:propose :review) (:why (first all))
                         :abstain  (fmt "every candidate's posterior is below the review threshold %s" (:review th))
                         :rejected "every candidate was rejected: for its kind, its dates or the mention's events"
                         :none     (if (seq (entries-of pool))
                                     "no entry of the pool comes near its name (every name level is none)"
                                     "there is no authority pool to match against"))
         :candidates   all}
         ls (assoc :lanes ls :reports reports))
       model))))

;; ---------------------------------------------------------------------------
;; Clusters
;; ---------------------------------------------------------------------------

(defn- link-out [l]
  (cond-> {:mention (:a l) :authority (:b l) :basis (:basis l)}
    (:weight l) (assoc :weight (:weight l))
    (:tie? l)   (assoc :tie? true)))

(defn clusters
  "The batch's mentions grouped into persons (`agent-cluster/cluster`, ADR
   0057) from their probabilistic decisions against `pool` (`opts`:
   `:model`, as `reconcile`): an outright proposal joins its mention to its
   authority, one for review is a candidate merge for a curator, a
   rejection a cannot-link; two authorities are never one cluster. Every
   mention is in exactly one cluster.

     {:engine :probabilistic :model version
      :clusters   [{:key :authority :members [mention-id ..]
                    :mentions [{:id :name :record :source {:format :field :occurrence :evidence}}]
                    :links [{:mention :authority :basis :weight} ..]}]  ; what joined them
      :candidates [{:mention :authority :basis :review :weight} ..]
      :refused    [{:mention :authority :basis :weight :violation} ..]
      :conflicts  [..]}

   the provenance of a cluster its members' occurrences (record, field,
   occurrence, evidence) and the decisions that joined them. `:lanes` (lot
   O6) as `reconcile`'s: the result states them when asked."
  ([records pool] (clusters records pool {}))
  ([records pool opts]
   (let [ls      (lanes-of opts)
         {:keys [model version]} (model-opt opts)
         per     (candidates records pool model ls)
         ms      (into {} (for [[m] per] [(:id m) m]))
         adates  (into {} (for [e (entries-of pool) :when (:id e)] [(:id e) (:dates e)]))
         links   (vec (for [[m {:keys [proposals]}] per, p proposals :when (:authority-id p)]
                        {:a (:id m) :b (:authority-id p) :basis (if (= :propose (:decision p)) :propose :review)
                         :weight (:match-weight p)}))
         cannot  (vec (for [[m {:keys [rejected]}] per, r rejected :when (:authority-id r)]
                        {:a (:id m) :b (:authority-id r) :reason (:reason r)}))
         items   (merge (into {} (for [id (distinct (map :b (concat links cannot)))
                                       :let [d (adates id)]]
                                   [id (cond-> {:authority? true} (dates/dated? d) (assoc :dates d))]))
                        (into {} (for [[m] per]
                                   [(:id m) (cond-> {:record (:record m)}
                                              (dates/dated? (:dates m)) (assoc :dates (:dates m))
                                              (seq (:events m))         (assoc :events (:events m)))])))
         ;; two occurrences of one name in one record that may be one agent
         ;; (lot O3): a candidate merge for a curator, never a join
         same    (vec (for [{[x y] :occurrences} (same-agent-proposals records)
                            :when (and (ms x) (ms y))]
                        {:a x :b y :basis :review :same-agent? true}))
         res     (ac/cluster {:items items :links (into links same) :cannot-link cannot})]
     (with-provenance
       (cond->
        {:engine     :probabilistic
         :model      version
         :clusters   (vec (for [c (:clusters res)
                                :let [mids (sort-by pr-str (filter ms (:members c)))]
                                :when (seq mids)]
                            {:key      (:key c)
                             :authority (:authority c)
                             :members  (vec mids)
                             :mentions (vec (for [id mids :let [m (ms id)]]
                                              {:id id :name (:name m) :record (:record m)
                                               :source (dissoc (:source m) :subfields)}))
                             :links    (vec (for [l (:joined res) :when (contains? (:members c) (:a l))] (link-out l)))}))
         :candidates (mapv link-out (:candidates res))
         :refused    (vec (for [{:keys [link violation]} (:refused res)] (assoc (link-out link) :violation violation)))
         :cannot-link (mapv (fn [{:keys [a b reason]}] {:mention a :authority b :reason reason}) cannot)
         :conflicts  (:conflicts res)}
         ls (assoc :lanes ls))
       model))))

(defn manifest-entry
  "What a run manifest says of a reconciliation `result`: its engine, its
   model's version and, when asked (lot O6), its candidate lanes."
  [result]
  (select-keys result [:engine :model :provenance :lanes]))

;; ---------------------------------------------------------------------------
;; Text, for the CLI
;; ---------------------------------------------------------------------------

(defn- model-label
  "A model by its version, never its path (for information only)."
  [{:keys [sha256 weights] :as v}]
  (if sha256
    (str "model sha256 " (subs sha256 0 12) " (weights " weights ")")
    (pr-str v)))

(defn- plural [n one many] (str n " " (if (= 1 n) one many)))

(def ^:private outcome-text
  {:propose "proposed outright" :review "for review" :abstain "abstained" :rejected "rejected" :none "no candidate"})

(defn- report-line
  "One report of a lane (`candidates/identifier-lane`), as text."
  [indent {:keys [kind key value authorities keys]}]
  (str indent "report: " (name kind)
       (when (or key value) (str " " (or key value)))
       (when (seq keys) (str " " (str/join ", " keys)))
       (when (seq authorities) (str " → " (str/join ", " authorities)))))

(defn- proposal-line [{:keys [authority-id authority-label decision score match-weight review-reason confidence]}]
  (str "      → " (pr-str authority-label) " <" authority-id ">  " (name decision)
       (when review-reason (str " (" (name review-reason) ")"))
       (fmt "  p %.4f  w %.3f" score match-weight)
       (when confidence (fmt "  confidence %.4f" confidence))))

(defn- format-decisions [{:keys [model pool? decisions floor-names]}]
  (if-not pool?
    "Probabilistic decisions: no authority pool to match against."
    (let [freq (frequencies (map :decision decisions))]
      (str/join "\n"
                (concat
                 [(str "Probabilistic decisions — " (model-label model) " — "
                       (plural (count decisions) "mention" "mentions") " read from the records ("
                       (plural floor-names "distinct name" "distinct names") " on the canonical floor)"
                       (when (seq decisions)
                         (str ": " (str/join ", " (for [k [:propose :review :abstain :rejected :none] :when (freq k)]
                                                    (str (freq k) " " (outcome-text k)))))))]
                 (mapcat (fn [{:keys [mention name decision proposals reports]}]
                           (concat [(str "  " (pr-str mention) " " (pr-str name) "  " (outcome-text decision))]
                                   (map proposal-line proposals)
                                   (map #(report-line "      " %) reports)))
                         decisions))))))

(defn- format-same-agents
  "The text of `:same-agent-proposals`, nil when there are none."
  [ps]
  (when (seq ps)
    (str/join "\n" (cons (str "Same-agent proposals: " (count ps)
                              " (two occurrences of one name in one record that may be one agent; a curator decides)")
                         (for [{:keys [name occurrences evidence]} ps]
                           (str "  " (pr-str name) " " (pr-str (first occurrences)) " ~ " (pr-str (second occurrences))
                                "  dates " (clojure.core/name (:dates evidence)) ", roles " (clojure.core/name (:roles evidence))))))))

(defn format-reconciliation
  "The text of a `reconcile` result. The historical engine's is what
   `palomar reconcile` wrote before lot N2, byte for byte, when no record
   gives one name twice; else the same-agent proposals follow (lot O3)."
  [{:keys [engine agents pool? same-agent-proposals] :as result}]
  (str (case engine
         :historical    (str (reconcile/format-agent-reconciliation agents)
                             (when pool? (str "\n" (reconcile/format-proposals (:proposals result)))))
         :probabilistic (str (reconcile/format-agent-reconciliation agents) "\n" (format-decisions result)))
       (when-let [t (format-same-agents same-agent-proposals)] (str "\n" t))))

(defn- weights-line
  "Each field's level and, when the pair was weighed, its weight."
  [{:keys [levels weights]}]
  (str/join ", " (concat
                  (for [[f label] [[:name "name"] [:form "form"] [:dates "dates"]]]
                    (str label " " (name (get levels f :none)) (when weights (fmt " %+.3f" (double (get weights f 0.0))))))
                  (when weights [(fmt "surname frequency %+.3f" (double (:tf weights 0.0)))]))))

(defn format-explanation
  "The text of an `explain` result."
  [{:keys [mention model prior prior-weight thresholds calibrated? decision why candidates reports]}]
  (let [src (:source mention)]
    (str/join
     "\n"
     (concat
      [(str "Mention " (pr-str (:id mention)) " " (pr-str (:name mention))
            " — record " (:record mention)
            (when (:field src) (str ", " (some-> (:format src) name) " " (:field src) " #" (:occurrence src)))
            (when-let [t (dates-text (:dates mention))] (str ", dates " t))
            (when (seq (:roles mention)) (str ", roles " (str/join " " (:roles mention)))))
       (str "Model " (model-label model) (fmt " — prior %.4f (weight %+.3f); propose at %s, review at %s"
                                              prior prior-weight (str (:propose thresholds)) (str (:review thresholds))))
       (str "Decision: " (name decision) " — " why)
       (if calibrated?
         "Confidence: the model is calibrated on this pool; a proposal states its posterior as its confidence where its proof holds."
         "Confidence: none — the model is not calibrated on this pool, so a posterior is a score, never a confidence.")
       (str "Candidates: " (count candidates))]
      (map #(report-line "  " %) reports)
      (mapcat (fn [{:keys [authority-id authority-label total posterior reason] :as c}]
                (concat
                 [(str "  " (pr-str authority-label) " <" authority-id ">")]
                 (when (seq (:levels c)) [(str "      " (weights-line c))])
                 (when total [(fmt "      total %+.3f; with the prior's %+.3f, %+.3f → posterior %.4f  %s"
                                   total prior-weight (+ total prior-weight) posterior (name (:decision c)))])
                 (when reason [(str "      rejected: " (name reason))])
                 (when-let [g (seq (:generated-by c))]
                   [(str "      found by: " (str/join "; " (for [{:keys [lane keys]} g]
                                                             (str (name lane) " (" (str/join ", " keys) ")"))))])
                 [(str "      why: " (:why c))]))
              candidates)))))

(defn format-clusters
  "The text of a `clusters` result."
  [{:keys [model clusters candidates refused conflicts]}]
  (str/join
   "\n"
   (concat
    [(str "Clusters — " (model-label model) " — " (plural (count (mapcat :members clusters)) "mention" "mentions")
          " in " (plural (count clusters) "cluster" "clusters") "; "
          (plural (count candidates) "merge" "merges") " for review, "
          (plural (count refused) "link refused" "links refused") ", "
          (plural (count conflicts) "conflict" "conflicts"))]
    (mapcat (fn [{:keys [authority members mentions links]}]
              (concat
               [(str "  " (if authority (str "<" authority ">") "(no authority)") "  " (plural (count members) "mention" "mentions"))]
               (for [m mentions
                     :let [l (first (filter #(= (:id m) (:mention %)) links))]]
                 (str "    " (pr-str (:id m)) " " (pr-str (:name m))
                      (when l (str "  " (name (:basis l)) (when (:weight l) (fmt " w %.3f" (:weight l)))))))))
            clusters)
    (when (seq candidates)
      (cons "  For review:"
            (for [l candidates]
              (str "    " (pr-str (:mention l)) " ~ <" (:authority l) ">" (when (:weight l) (fmt "  w %.3f" (:weight l)))))))
    (when (seq refused)
      (cons "  Refused:"
            (for [l refused]
              (str "    " (pr-str (:mention l)) " ~ <" (:authority l) ">  " (name (get-in l [:violation :reason])))))))))

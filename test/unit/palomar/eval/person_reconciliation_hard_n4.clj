(ns palomar.eval.person-reconciliation-hard-n4
  "Lot N4 on the hard benchmark (`palomar.eval.person-reconciliation-hard`,
   `docs/eval/person-reconciliation-hard.md`, section N4): the engines
   compared on the same candidates, the Fellegi-Sunter model recalibrated on
   N3 train, its thresholds chosen on a cross-validation of train by a
   stated criterion (`threshold-criterion`), a role-agreement feature fitted on
   train, and the calibration of the recalibrated model against the bar.

   This namespace calls the frozen N3 namespace and never changes it: the
   mentions, the splits, the strata, the inversion of a name, the outcome
   of a case and the summary are N3's.

   **The same candidates.** Every engine reads every scored mention of a
   split against the whole N3 pool (292 entries), as N3's harness does. The
   Jaccard and names+dates engines are N3's own functions. The
   Fellegi-Sunter engines share one comparison of each (mention, entry)
   pair (`pair-comparisons`), on the name put in heading order
   (`hard/inverted`), and differ only in the weights.

   **What is fitted on what.** m, u and the prior on the train split only;
   the thresholds on the held-out folds of train (dev only confirms); the
   test split was scored once, outside the tests (`test-run`), and is
   spent. The role profiles of the candidates are read from
   the headings of the design records only, never from the mention's own
   record."
  (:require [clojure.string :as str]
            [palomar.agent-match :as fs]
            [palomar.eval.person-reconciliation :as pr]
            [palomar.eval.person-reconciliation-hard :as hard]
            [palomar.manifest :as manifest]
            [palomar.reconciliation :as rc]))

;; ---------------------------------------------------------------------------
;; The criterion, stated before any recalibrated number was seen
;; ---------------------------------------------------------------------------

(def threshold-criterion
  "How the thresholds of a recalibrated model are chosen: on the held-out
   folds of a 5-fold cross-validation of the train split, by person
   (`cv-scored`). Dev only confirms; test is not read. This is the second
   criterion (lot N4 review). The first chose on dev; its review step took
   0.1, and the single test run was made with it. This one was set after
   that run, by the lot's coordinator, before the cross-validated choice
   was computed.

   1. **Propose.** With the review threshold equal to it (no review band),
      a grid value is admissible when its cross-validated precision is at
      least 0.99 and the Wilson 95 % lower bound of that precision is at
      least 0.95. Take the admissible value with the highest recall; on a
      tie, the highest value. If none is admissible, keep the shipped
      thresholds (0.95 / 0.5). A tie at the top is an abstention (N3).
   2. **Review.** A grid value at or below the propose threshold is
      admissible when (a) the cross-validated recall of the outright links
      stays within 0.01 of step 1's (two candidates at or above review
      send a mention to review), (b) at least half the mentions sent to
      review have their truth among the candidates at or above it, and
      (c) at least 30 mentions are sent to review (the bar's
      `:min-review-pairs`). Take the highest admissible value. If none,
      keep the shipped 0.5.

   Ship: the recalibrated model without roles, whatever the role run
   gives: the engine (`reconcile/agent-candidates`) has no role feature
   from a transcription, and the CLI's mentions are headings."
  {:propose-grid     [0.5 0.6 0.7 0.8 0.9 0.95 0.97 0.98 0.99 0.995 0.999]
   :review-grid      [0.05 0.1 0.2 0.3 0.4 0.5 0.6 0.7 0.8 0.9 0.95 0.97 0.98 0.99 0.995 0.999]
   :min-precision    0.99
   :min-wilson-lower 0.95
   :wilson-z         1.96
   :max-recall-loss  0.01
   :min-review-hit   0.5
   :min-reviews      (:min-review-pairs fs/default-bar)
   :fallback         {:propose 0.95 :review 0.5}})

(def cv-folds
  "The folds of the cross-validation the thresholds are chosen on."
  5)

(defn wilson-lower
  "The lower bound of the Wilson score interval of `k` successes in `n`, at
   `z` (1.96: 95 %); nil when `n` is 0."
  ([k n] (wilson-lower k n (:wilson-z threshold-criterion)))
  ([k n z]
   (when (pos? n)
     (let [p  (/ (double k) n)
           z2 (* z z)]
       (/ (- (+ p (/ z2 (* 2 n))) (* z (Math/sqrt (+ (/ (* p (- 1 p)) n) (/ z2 (* 4 n n))))))
          (+ 1 (/ z2 n)))))))

;; ---------------------------------------------------------------------------
;; The pool and the comparisons
;; ---------------------------------------------------------------------------

(defn fs-name
  "The name a Fellegi-Sunter engine reads: the transcription put in heading
   order (`hard/inverted`), as N3's inverted run reads it."
  [m]
  (hard/inverted (:text m)))

(defn- forms-of [e] (remove str/blank? (cons (:label e) (:variants e))))

(defn pool-context
  "The pool as the Fellegi-Sunter engines read it: the dated entries
   (`pr/dated-entries`, as N3's harness gives `agent-candidates`), the
   surname counts and the size the term frequencies read, its fingerprint,
   and for each entry the tokens and surnames of its forms (for
   `could-agree?`)."
  [pool]
  (let [es (pr/dated-entries pool)]
    {:entries es
     :counts  (fs/surname-counts es)
     :size    (count es)
     :pool    (fs/pool-fingerprint es)
     :forms   (mapv (fn [e] (let [fs* (forms-of e)]
                              {:tokens (set (mapcat fs/tokens fs*)) :surnames (set (map fs/surname fs*))}))
                    es)}))

(def ^:private typo? @#'fs/typo?)

(defn could-agree?
  "Whether name `n` (its tokens `tm`, its surname `sm`) can reach a name
   level above `:none` against an entry of forms `forms`: only by sharing a
   token with one of its forms, or by a surname one edit away
   (`agent-match/name-level`). A filter, never a decision: an entry it
   keeps is compared in full."
  [tm sm {:keys [tokens surnames]}]
  (boolean (and (seq tm)
                (or (some tokens tm) (some #(typo? sm %) surnames)))))

(defn pair-comparisons
  "The comparisons of name `n` with the entries of `ctx` whose name level
   is above `:none` (`fs/compare-pair`, the pool's surname counts and
   size): `[{:id :index :cmp}]`, in pool order. Every other entry is a
   `:none` pair: its name level `:none`, its form `:heading`, its dates
   `:unknown` (a transcription has no dates)."
  [ctx n]
  (let [tm (fs/tokens n) sm (fs/surname n)
        opts {:surname-counts (:counts ctx) :pool-size (:size ctx)}]
    (vec (for [[i e f] (map vector (range) (:entries ctx) (:forms ctx))
               :when (could-agree? tm sm f)
               :let [c (fs/compare-pair {:name n :dates nil} e opts)]
               :when (not= :none (get-in c [:levels :name]))]
           {:id (:id e) :index i :cmp c}))))

(defn prepared
  "The per-mention inputs every engine reads for the mentions `ms`: each
   mention with its name comparisons against the pool of `ctx`."
  [ctx ms]
  (mapv (fn [m] {:mention m :pairs (pair-comparisons ctx (fs-name m))}) ms))

(def none-levels
  "The levels of a pair whose name level is `:none`."
  {:name :none :form :heading :dates :unknown})

;; ---------------------------------------------------------------------------
;; Roles
;; ---------------------------------------------------------------------------

(def role-levels
  "The levels of the role feature: `:agree` (the mention's role phrase is
   of a class the candidate's headings hold in another design record),
   `:disagree` (the candidate has headings in other design records, none
   of that class), `:unknown` (the phrase has no class, or the candidate no
   heading in another design record). `:unknown` weighs 0."
  [:agree :disagree :unknown])

(defn- code-class [code] (get hard/code-classes code :other))

(defn role-profiles
  "Each authority's role classes by design record: `{id {record #{class}}}`,
   from the `$4` codes of the headings of the design records of `b`
   (`hard/code-classes`, any other code `:other`). The test records are not
   read."
  [b]
  (reduce (fn [acc [rid {:keys [part headings]}]]
            (if (= :design part)
              (reduce (fn [acc {:keys [id roles]}]
                        (if id
                          (update-in acc [id rid] (fnil into #{}) (map code-class (or (seq roles) ["none"])))
                          acc))
                      acc headings)
              acc))
          {} (:records b)))

(defn role-level
  "The role level of mention `m` against authority `id` under `profiles`:
   the mention's class (`hard/role-class` of its role phrase) against the
   classes the authority's headings hold in the design records other than
   the mention's own."
  [profiles m id]
  (let [c   (hard/role-class (:role m))
        rec (dissoc (get profiles id) (:record m))]
    (cond (or (nil? c) (empty? rec)) :unknown
          (some #(contains? % c) (vals rec)) :agree
          :else :disagree)))

;; ---------------------------------------------------------------------------
;; Fitting on train
;; ---------------------------------------------------------------------------

(defn split-mentions
  "The scored mentions of `split` of `b`."
  [b split]
  (filterv #(= split (:split %)) (:mentions b)))

(defn- w [c n] (assoc c ::fs/w (double n)))

(defn fit-counts
  "What a model is fitted from on the prepared mentions `pms` (`prepared`),
   against the whole pool of `ctx`: the level counts of the true pairs (m)
   and of every other pair (u), `:none` pairs included, the number of pairs
   and of true pairs. With `profiles`, the role levels too (`:role-m`,
   `:role-u`)."
  [ctx pms profiles]
  (let [size (:size ctx)
        per  (for [{m :mention ps :pairs} pms
                   :let [hit  (some #(when (= (:truth m) (:id %)) %) ps)
                         t?   (some? (:truth m))
                         none (- size (count ps) (if (and t? (not hit)) 1 0))]]
               {:m     (cond hit [(:cmp hit)] t? [{:levels none-levels}] :else [])
                :u     (conj (vec (keep #(when-not (= (:truth m) (:id %)) (:cmp %)) ps))
                             (w {:levels none-levels} none))
                :roles (when profiles
                         (frequencies (for [e (:entries ctx)]
                                        [(= (:truth m) (:id e)) (role-level profiles m (:id e))])))
                :true  (if t? 1 0)})
        rm   (apply merge-with + (map :roles per))]
    (cond-> {:m-counts (fs/level-counts (mapcat :m per))
             :u-counts (fs/level-counts (mapcat :u per))
             :pairs    (* size (count pms))
             :true     (reduce + (map :true per))}
      profiles (assoc :role-m (into {} (for [[[t l] n] rm :when t] [l (double n)]))
                      :role-u (into {} (for [[[t l] n] rm :when (not t)] [l (double n)]))))))

(defn role-weights
  "The weights of the role levels from their counts (`fs/measured-weight`):
   `:unknown` 0, an agreement never against, a disagreement never for."
  [{:keys [role-m role-u]}]
  (let [mn (reduce + 0.0 (vals role-m)) un (reduce + 0.0 (vals role-u))
        mw (fn [l] (fs/measured-weight (get role-m l 0.0) mn (get role-u l 0.0) un))]
    {:agree (max 0.0 (mw :agree)) :disagree (min 0.0 (mw :disagree)) :unknown 0.0}))

(defn model-params
  "The parameters of the model fitted on `counts` (`fit-counts`): m and u
   the level counts, the prior the share of true pairs among all pairs,
   with `thresholds`."
  [counts thresholds]
  {:prior      (/ (double (:true counts)) (:pairs counts))
   :thresholds thresholds
   :bar        fs/default-bar
   :m-counts   (:m-counts counts)
   :u-counts   (:u-counts counts)
   :estimation {:m :supervised :u :mention-pairs :fitted-on :n3-train
                :name-input :inverted :thresholds-chosen-on :n3-train-cv5}})

(defn fit-model
  "The model of `params` (`fs/model`, as `reconciliation/read-model` builds
   it)."
  [params]
  (fs/model params))

;; ---------------------------------------------------------------------------
;; Scoring and deciding
;; ---------------------------------------------------------------------------

(defn scored
  "Each candidate of name comparisons `ps` with its posterior under `model`:
   `[{:id :index :w :p}]`, best weight first (pool order on a tie, as
   `agent-candidates` sorts). With `role-w` and `role-of` (id -> level), the
   role weight is added to each candidate's weight."
  ([model ps] (scored model ps nil nil))
  ([model ps role-w role-of]
   (vec (sort-by (juxt (comp - :w) :index)
                 (for [{:keys [id index cmp]} ps
                       :let [wt (+ (:match-weight (fs/score model cmp)) (if role-w (get role-w (role-of id) 0.0) 0.0))]]
                   {:id id :index index :w wt :p (fs/posterior wt (:prior model))})))))

(defn decide
  "The decision on one mention's `cands` (`scored`) at `thresholds`, as
   `reconcile/agent-candidates` takes it: two or more candidates at or
   above review send the mention to review; else the top candidate is
   proposed at or above propose, reviewed at or above review, else the
   mention is abstained. `{:link top-or-nil :review? bool :decision}`."
  [cands {:keys [propose review]}]
  (let [above (count (filter #(>= (:p %) review) cands))
        top   (first cands)
        d     (cond (< 1 above)                     :review
                    (and top (>= (:p top) propose)) :propose
                    (and top (>= (:p top) review))  :review
                    :else                           :abstain)]
    {:link     (when (= :propose d) {:authority-id (:id top) :score (:p top)})
     :review?  (= :review d)
     :decision d}))

;; ---------------------------------------------------------------------------
;; The engines on one split
;; ---------------------------------------------------------------------------

(defn refuse-in-sample!
  "Refuse to score with `model` any mention of `ms` whose person or
   no-match name it was fitted on (`reconciliation/refuse-in-sample!`
   with the mentions' split keys, `hard/split-key`). Nil when it may."
  [model pool ms]
  (rc/refuse-in-sample! model pool {:keys (map hard/split-key ms)}))

(defn case-of
  "One scored case, as N3's harness writes it."
  [m r labels]
  (cond-> {:id (:id m) :text (:text m) :role (:role m) :key (hard/split-key m)
           :truth (:truth m) :truth-label (labels (:truth m)) :stratum (:stratum m)
           :predicted (get-in r [:link :authority-id])
           :predicted-label (labels (get-in r [:link :authority-id]))
           :score (get-in r [:link :score])
           :outcome (hard/outcome m r :abstain)}
    (:review? r) (assoc :review? true)))

(defn fs-engine
  "A Fellegi-Sunter engine: `model` at its thresholds, with role weights
   `role-w` read through `profiles` when given."
  ([model] (fs-engine model nil nil))
  ([model role-w profiles]
   (fn [{:keys [mention pairs]}]
     (decide (scored model pairs role-w (when role-w #(role-level profiles mention %))) (:thresholds model)))))

(defn summaries
  "The summary of `cases` overall, by stratum, and the review volume."
  [cases]
  {:all        (hard/summary cases)
   :by-stratum (into (sorted-map) (for [[s xs] (group-by :stratum cases)] [s (hard/summary xs)]))})

(defn run
  "Each engine of `engines` (`{name (fn [prepared-mention pool] result)}`)
   on the prepared mentions `pms`, against `pool`: `{name {:cases :all
   :by-stratum}}`."
  [engines pms pool]
  (let [labels (into {} (map (juxt :id :label)) pool)]
    (into (sorted-map)
          (for [[k f] engines
                :let [cases (mapv #(case-of (:mention %) (f % pool) labels) pms)]]
            [k (assoc (summaries cases) :cases cases)]))))

(defn n3-engine
  "N3's own engine `k` (`hard/engines`), on the transcribed name."
  [k]
  (let [f (get hard/engines k)]
    (fn [{:keys [mention]} pool] (f (:text mention) pool))))

;; ---------------------------------------------------------------------------
;; Thresholds, by the criterion
;; ---------------------------------------------------------------------------

(defn- outcomes-at
  "The cases of `scored-ms` (`[{:mention :cands}]`) decided at
   `thresholds`, with the review hits."
  [scored-ms thresholds]
  (for [{:keys [mention cands]} scored-ms
        :let [r (decide cands thresholds)]]
    {:case (cond-> {:truth (:truth mention) :key (hard/split-key mention) :outcome (hard/outcome mention r :abstain)}
             (:review? r) (assoc :review? true))
     :review-hit? (and (:review? r) (:truth mention)
                       (boolean (some #(and (= (:truth mention) (:id %)) (>= (:p %) (:review thresholds))) cands)))}))

(defn point
  "The figures of `scored-ms` at `thresholds`: precision, its Wilson lower bound,
   recall, review volume and review hit rate."
  [scored-ms thresholds]
  (let [os    (outcomes-at scored-ms thresholds)
        s     (hard/summary (map :case os))
        links (+ (:correct s) (:wrong-link s) (:false-link s))
        rev   (:review s)]
    {:thresholds thresholds
     :precision  (:precision s)
     :wilson     (wilson-lower (:correct s) links)
     :recall     (:recall s)
     :links      links
     :review     rev
     :review-hit (when (pos? rev) (/ (count (filter :review-hit? os)) rev))}))

(defn choose-thresholds
  "The thresholds `threshold-criterion` chooses on `scored-ms` (held-out
   scored mentions, `cv-scored`): `{:thresholds :propose-point
   :review-point :grid :review-grid}`."
  [scored-ms]
  (let [{:keys [propose-grid review-grid min-precision min-wilson-lower max-recall-loss min-review-hit min-reviews fallback]}
        threshold-criterion
        grid  (mapv #(point scored-ms {:propose % :review %}) propose-grid)
        ok    (filter #(and (:precision %) (>= (:precision %) min-precision) (>= (:wilson %) min-wilson-lower)) grid)
        best  (when (seq ok) (last (sort-by (juxt :recall (comp :propose :thresholds)) ok)))
        tp    (get-in best [:thresholds :propose])
        revs  (when best
                (vec (for [r review-grid :when (<= r tp)]
                       (point scored-ms {:propose tp :review r}))))
        rbest (last (filter #(and (>= (:recall %) (- (:recall best) max-recall-loss))
                                  (:review-hit %) (>= (:review-hit %) min-review-hit)
                                  (>= (:review %) min-reviews))
                            revs))]
    {:thresholds    (cond (nil? best) fallback
                          rbest       (:thresholds rbest)
                          :else       {:propose tp :review (min tp (:review fallback))})
     :propose-point best
     :review-point  rbest
     :grid          grid
     :review-grid   (vec revs)}))

(defn scored-mentions
  "Each prepared mention of `pms` with its candidates scored under `model`
   (with role weights when given)."
  ([model pms] (scored-mentions model pms nil nil))
  ([model pms role-w profiles]
   (mapv (fn [{:keys [mention pairs]}]
           {:mention mention
            :cands   (scored model pairs role-w (when role-w #(role-level profiles mention %)))})
         pms)))

;; ---------------------------------------------------------------------------
;; Calibration
;; ---------------------------------------------------------------------------

(defn predictions
  "The held-out predictions of `scored-ms`: every scored candidate of every
   mention (linked or not), y 1 when it is the truth; a pair is distinct by
   the name read and the candidate, a mention by the name read (identical
   names give identical predictions)."
  [scored-ms]
  (vec (for [{:keys [mention cands]} scored-ms
             c cands
             :let [n (fs-name mention)]]
         {:p (:p c) :y (if (= (:truth mention) (:id c)) 1 0) :pair [n (:id c)] :mention n})))

(defn gold-fingerprint
  "The fingerprint of the gold of mentions `ms`: their ids, texts and truths."
  [ms]
  (fs/fingerprint (vec (sort-by pr-str (map (juxt :id :text :truth) ms)))))

(defn calibration-report
  "The calibration report of `model` on `scored-ms`, held out, measured on
   the pool of `ctx` and the gold of the mentions, bound to the model
   (`agent-match/model-fingerprint`)."
  [model ctx scored-ms]
  (fs/calibration (predictions scored-ms)
                  {:pool (:pool ctx) :gold (gold-fingerprint (map :mention scored-ms))
                   :model (fs/model-fingerprint model)
                   :band ((juxt :review :propose) (:thresholds model))}))

(defn fold-of
  "The cross-validation fold (0..k-1) of a mention, by its split key: a
   person is in one fold."
  [m k]
  (mod (Long/parseLong (subs (fs/fingerprint ["palomar-n4-fold" (hard/split-key m)]) 0 8) 16) k))

(defn cv-report
  "The calibration report of `k`-fold cross-validation on train+dev (never
   test), by person: each fold scored by the model fitted on the others.
   It measures the fitting procedure, not one model: it names none."
  [ctx b k]
  (let [ms    (into (split-mentions b :train) (split-mentions b :dev))
        pms   (prepared ctx ms)
        fold  #(fold-of (:mention %) k)
        preds (vec (mapcat (fn [i]
                             (let [mdl (fit-model (model-params (fit-counts ctx (remove #(= i (fold %)) pms) nil)
                                                                fs/default-thresholds))]
                               (predictions (scored-mentions mdl (filter #(= i (fold %)) pms)))))
                           (range k)))]
    (fs/calibration preds {:pool (:pool ctx) :gold (gold-fingerprint ms)})))

(defn cv-scored
  "The held-out scored mentions of a `k`-fold cross-validation of the
   prepared mentions `pms` by person (`fold-of`): each fold scored by the
   model fitted on the others (with the role weights fitted on them when
   `profiles` is given)."
  [ctx pms profiles k]
  (let [fold #(fold-of (:mention %) k)]
    (vec (mapcat (fn [i]
                   (let [counts (fit-counts ctx (remove #(= i (fold %)) pms) profiles)
                         mdl    (fit-model (model-params counts fs/default-thresholds))
                         role-w (when profiles (role-weights counts))]
                     (scored-mentions mdl (filter #(= i (fold %)) pms) role-w profiles)))
                 (range k)))))

;; ---------------------------------------------------------------------------
;; The model file
;; ---------------------------------------------------------------------------

(def model-resource "palomar/models/agent-match-n3.edn")

(def model-path (str "resources/" model-resource))

(defn train-keys
  "The split keys of the train split: the persons and no-match names the
   model was fitted on."
  [b]
  (vec (sort (distinct (map hard/split-key (split-mentions b :train))))))

(defn provenance
  "The provenance of the N3 model fitted on `b`'s train split: its data
   (the design corpora, the benchmark and the split file, which hold its
   training mentions) and the authority file with their SHA-256, the
   split, the train keys, the pool, the licence and the attribution."
  [b ctx]
  (let [sha (fn [p] (:sha256 (manifest/fingerprint (java.io.File. ^String p))))
        dir (str hard/fixture-dir (:design hard/parts) "/")]
    {:data        (vec (concat (for [f (sort (.list (java.io.File. dir))) :when (str/ends-with? f ".xml")]
                                 {:path (str dir f) :sha256 (sha (str dir f))})
                               (for [p [hard/benchmark-file hard/splits-file]]
                                 {:path p :sha256 (sha p)})))
     :authorities {:path hard/authority-file :sha256 (sha hard/authority-file)}
     :split       :n3-train
     :train-keys  (train-keys b)
     :pool        (:pool ctx)
     :licence     "Licence Ouverte / Etalab 2.0"
     :attribution "Source : Bibliothèque nationale de France"}))

(defn n3-params
  "The parameters of the N3 model: fitted on train, thresholds chosen by
   `threshold-criterion` on the cross-validated train folds, with its
   provenance. `{:params :choice}`, the choice with the chosen thresholds'
   cross-validated point (`:cv`) and their dev confirmation (`:dev`)."
  [b ctx]
  (let [train  (prepared ctx (split-mentions b :train))
        cv     (cv-scored ctx train nil cv-folds)
        choice (choose-thresholds cv)
        th     (:thresholds choice)
        counts (fit-counts ctx train nil)
        mdl    (fit-model (model-params counts th))]
    {:params (assoc (model-params counts th) :provenance (provenance b ctx))
     :choice (assoc choice
                    :cv  (point cv th)
                    :dev (point (scored-mentions mdl (prepared ctx (split-mentions b :dev))) th))}))

(def file-header
  ";; The parameters of the Fellegi-Sunter model recalibrated on the hard
;; benchmark (lot N4, docs/eval/person-reconciliation-hard.md, section N4).
;; `palomar.agent-match/model` computes the weights from them.
;;
;; m, u and the prior are fitted on the N3 train split only: 697 transcribed
;; mentions of the nine BnF design corpora, against the 292 person
;; authorities of the benchmark, each name put in heading order. The
;; thresholds were chosen on a 5-fold cross-validation of train, by person;
;; dev only confirmed them. The test split fed neither.
;;
;; IN-SAMPLE on its train split: `palomar.reconciliation/refuse-in-sample!`
;; refuses it on the persons and no-match names it was fitted on
;; (`:train-keys`), and on its design corpora, the benchmark and the split
;; file (`:data`, by their SHA-256).
;;
;; Derived from BnF data: Licence Ouverte / Etalab 2.0, \"Source :
;; Bibliothèque nationale de France\" (see NOTICE).
;;
;; Not calibrated: its held-out calibration does not meet the bar, so it
;; carries no `:calibration` report and no posterior it gives is written as
;; a confidence. Written by palomar.eval.person-reconciliation-hard-n4/write-model!.
")

(defn- provenance-text
  "The provenance map as text: one key a line, a data file a line, eight
   train keys a line."
  [prov]
  (let [pad  (fn [n] (apply str "\n" (repeat n " ")))
        line (fn [k v]
               (case k
                 :data       (str (pr-str k) " [" (str/join (pad 21) (map pr-str v)) "]")
                 :train-keys (str (pr-str k) " [" (str/join (pad 27) (map #(str/join " " (map pr-str %)) (partition-all 8 v))) "]")
                 (str (pr-str k) " " (pr-str v))))
        ks   [:data :authorities :split :train-keys :pool :licence :attribution]]
    (str "{" (str/join (pad 14) (for [k ks :when (contains? prov k)] (line k (get prov k)))) "}")))

(defn model-file-content
  "The text of the model file for `params`."
  [params]
  (let [ks [:prior :thresholds :bar :m-counts :u-counts :estimation :provenance]]
    (str file-header
         "{" (str/join "\n " (for [k ks :when (contains? params k)]
                               (str (pr-str k) " " (if (= :provenance k) (provenance-text (get params k)) (pr-str (get params k))))))
         "}\n")))

(defn write-model!
  "Write the model file from the benchmark (run by hand, never by a test)."
  []
  (let [b (hard/load-benchmark) ctx (pool-context (:pool b))]
    (spit model-path (model-file-content (:params (n3-params b ctx))))))

;; ---------------------------------------------------------------------------
;; The comparison
;; ---------------------------------------------------------------------------

(defn engines-for
  "The engines compared: N3's Jaccard and names+dates, the shipped model
   (`shipped`, at its thresholds), the N3 model (`n3`, at its thresholds),
   and the N3 model with the role weights `role-w` (at `role-thresholds`)."
  [{:keys [shipped n3 roles role-w profiles]}]
  (cond-> {:jaccard     (n3-engine :jaccard)
           :names-dates (n3-engine :names-dates)
           :fs-shipped  (fn [pm _] ((fs-engine shipped) pm))
           :fs-n3       (fn [pm _] ((fs-engine n3) pm))}
    roles (assoc :fs-n3-roles (fn [pm _] ((fs-engine roles role-w profiles) pm)))))

(defn roles-model
  "The N3 model with role weights: `{:model :role-w :role-counts :choice}`,
   the model fitted on train with the role levels, its thresholds chosen
   by the same criterion on the same cross-validated folds (role weights
   fitted per fold), its provenance the train split and keys, so
   `refuse-in-sample!` refuses it on train."
  [b ctx profiles]
  (let [train  (prepared ctx (split-mentions b :train))
        choice (choose-thresholds (cv-scored ctx train profiles cv-folds))
        counts (fit-counts ctx train profiles)
        role-w (role-weights counts)
        mdl    (fit-model (model-params counts (:thresholds choice)))]
    {:model       (assoc mdl :provenance {:split :n3-train :train-keys (train-keys b)})
     :role-w      role-w
     :role-counts (select-keys counts [:role-m :role-u])
     :choice      choice}))

(defn comparison
  "Every engine on `split` of `b`, on the same mentions and pool:
   `{:results {engine {:all :by-stratum :cases}} :n3 :roles :shipped}`.
   Each Fellegi-Sunter model is refused on the mentions it was fitted on."
  [b split {:keys [shipped n3]}]
  (let [ctx      (pool-context (:pool b))
        profiles (role-profiles b)
        roles    (roles-model b ctx profiles)
        ms       (split-mentions b split)
        pms      (prepared ctx ms)]
    (doseq [mdl [shipped n3 (:model roles)]] (refuse-in-sample! mdl (:entries ctx) ms))
    {:results (run (engines-for {:shipped shipped :n3 n3 :roles (:model roles) :role-w (:role-w roles) :profiles profiles})
                   pms (:pool b))
     :roles   roles}))

(defn shipped-model
  "The shipped model of `palomar reconcile` (`reconciliation/read-model`)."
  []
  (:model (rc/read-model)))

(defn n3-model
  "The N3 model file, as `reconciliation/read-model` reads it."
  []
  (:model (rc/read-model model-resource)))

(defn test-run
  "The single run on test was made once, with the first criterion's
   thresholds (0.95 / 0.1; roles 0.9 / 0.4), and is reported in the eval
   doc. The test split is spent: this refuses to run it again."
  []
  (throw (ex-info "the N4 test split is spent: it was run once (docs/eval/person-reconciliation-hard.md, N4)" {})))

;; ---------------------------------------------------------------------------
;; What separates the runs
;; ---------------------------------------------------------------------------

(defn exact-heading-ceilings
  "For each pool entry of `ctx`, the posterior `model` gives its own
   heading against it: the most an exact heading can reach, the pool's
   surname frequency included. `[{:id :label :p}]`."
  [model ctx]
  (vec (for [e (:entries ctx)
             :let [c (some #(when (= (:id e) (:id %)) %) (pair-comparisons ctx (:label e)))]
             :when c]
         {:id (:id e) :label (:label e) :p (fs/posterior (:match-weight (fs/score model (:cmp c))) (:prior model))})))

(def separation-thresholds
  "The threshold pairs the separation table compares: the first
   criterion's (0.95 / 0.1), the first role run's (0.9 / 0.4), and each
   threshold moved alone."
  [[0.95 0.1] [0.9 0.1] [0.95 0.4] [0.9 0.4] [0.95 0.5] [0.9 0.5]])

(defn separation
  "The N3 model and the roles model at equal thresholds, on the held-out
   train folds and on dev: `{:cv {[p r] {:n3 [correct review false-link]
   :roles [..]}} :dev {..}}`. The propose threshold, the review threshold
   and the role weight move one at a time."
  [b ctx]
  (let [profiles (role-profiles b)
        train    (prepared ctx (split-mentions b :train))
        dev      (prepared ctx (split-mentions b :dev))
        full     (fn [p] (let [c (fit-counts ctx train p)]
                           [(fit-model (model-params c fs/default-thresholds)) (when p (role-weights c))]))
        [m3 _]   (full nil)
        [mr rw]  (full profiles)
        sets     {:cv  {:n3 (cv-scored ctx train nil cv-folds) :roles (cv-scored ctx train profiles cv-folds)}
                  :dev {:n3 (scored-mentions m3 dev) :roles (scored-mentions mr dev rw profiles)}}
        fig      (fn [sms th] ((juxt :correct :review :false-link)
                               (hard/summary (map :case (outcomes-at sms th)))))]
    (into (sorted-map)
          (for [[where by] sets]
            [where (into (sorted-map)
                         (for [[p r] separation-thresholds :let [th {:propose p :review r}]]
                           [[p r] {:n3 (fig (:n3 by) th) :roles (fig (:roles by) th)}]))]))))

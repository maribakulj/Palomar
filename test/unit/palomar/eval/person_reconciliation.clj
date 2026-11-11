(ns palomar.eval.person-reconciliation
  "Readers and metrics for the person-reconciliation eval (lot M2,
   `palomar.eval.person-reconciliation-test`, `docs/eval/person-reconciliation.md`).

   The corpus is 150 BnF INTERMARC bibliographic records and the BnF person
   authority records their `100`/`700 $3` cite
   (`test/fixtures/er-gold/bnf-persons/README.md`). Both go through Palomar's own
   INTERMARC importer (`intermarc/ingest`, then the `:normalize` phase for the
   bibliographic records); this namespace only reads the field fragments the
   importer keeps (`:locator {:tag :subfields}`).

   - A **mention** is one `100` or `700` field (persons; the other 7xx are
     collectivities, languages, series… and are left out). The name the matcher
     sees is `$a, $m` of the field. Since lot N1 the mentions are the common
     extraction's (`palomar.mentions`), which reads each field alone (M2
     rebuilt a `700`'s name from its field, which the classic INTERMARC
     (SRU) mapping does not carry); every number asserted since M2 is
     unchanged by it. `$3` (the BnF authority the cataloguer
     linked) is kept as the **truth** and never reaches a matcher; neither does
     `$1` (ISNI) or `$d` (dates).
   - The **pool** is one `{:id :label :variants}` entry per authority record:
     id the BnF record number (`001` without `FRBNF` and its check character, the
     form of `$3`), label the `100` heading `$a, $m`, variants the `400`s. Since
     lot M3 it is read by Palomar's INTERMARC authority reader
     (`authorities/read-pool :intermarc`), which also gives each entry its
     `:dates` (the `100 $d`); M2's test-side reader is kept as
     `legacy-authority-pool`, and a test checks the two give the same pool.
   - Lot M3 adds a mention's **dates**: its field's `$d`, parsed
     (`palomar.agent-dates`). They live in the IR as the importer's natives
     (the field fragment's subfields, the `:intermarc/f100_d` / `f700_d`
     assertions) and are read here, as M2 reads a 700's name: the canonical
     `:canon/agent` is a name, and no canonical coordinate carries a date.
     `task-a` and `task-b`'s `:top-1` are the name-only baseline (no dates on
     either side); `task-a-dated` and `:top-1-dated` pass them.

   Lot M4 scores the same mentions with a Fellegi-Sunter model
   (`palomar.agent-match`, `task-a-fs`, `:top-1-fs`): m learnt from the
   labelled true pairs, leave-one-family-out, so no mention is scored with
   parameters learnt from its own family; u, the surname frequencies and the
   u-mean of the term-frequency adjustment from every (mention, entry) pair,
   no label read; and, for comparison, m and the prior by EM, no label read
   at all (`em-model`). The held-out predictions measure the calibration
   (`fs-calibration`), which decides whether the posterior is written as a
   confidence. A **stress set**, synthetic and labelled so (`stress-set`),
   perturbs the gold mentions' names to measure candidate recall.

   Lot M5 clusters the determined mentions from their Fellegi-Sunter
   decisions with `palomar.agent-cluster` (`task-b-constrained`), against
   the naive transitive closure of the same links, and on a synthetic set of
   chains derived from the pool (`chain-cases`).

   Lot M7 gives each mention the typed events of its record
   (`palomar.agent-events`): on this corpus, the edition's dates (INTERMARC
   260 `$d`), `:publication` events that constrain nothing (`task-a-events`,
   with a what-if that reads them as creations); a synthetic set of events
   around the pool's own authorities (`event-cases`); and, on LIDO, the
   actors of a museum's records against the authorities their ULAN ids name
   (`lido-event-run`).

   Lot M8 adds a mention's relational neighbourhood (`palomar.agent-relations`):
   the same mentions with their `$4` role, scored with anchors — validated
   links, frozen before scoring — in several settings (`anchor-settings`,
   `task-a-relational`), the leakage checks (`leakage`), and the clustering
   of the decisions (`task-b-relational`).

   The metrics are pure functions of plain maps, so they are tested on
   hand-made inputs."
  (:require [clojure.string :as str]
            [palomar.agent-cluster :as ac]
            [palomar.agent-dates :as dates]
            [palomar.agent-match :as fs]
            [palomar.agent-relations :as ar]
            [palomar.curate :as curate]
            [palomar.mentions :as mentions]
            [palomar.model :as model]
            [palomar.plugins :as plug]
            [palomar.plugins.authorities :as authorities]
            [palomar.plugins.intermarc :as intermarc]
            [palomar.plugins.mapping :as mapping]
            [palomar.reconcile :as reconcile]
            [palomar.reconciliation :as reconciliation]
            [palomar.runtime :as runtime]
            [palomar.text :as text]))

;; ---------------------------------------------------------------------------
;; Reading the corpus
;; ---------------------------------------------------------------------------

(def bib-dir "test/fixtures/documentary/intermarc/sru/intermarcXchange/")

(def bib-files
  ["bnf-sru-victor-hugo-50.xml" "bnf-sru-jules-verne-50.xml" "bnf-sru-alexandre-dumas-50.xml"])

(def authority-file "test/fixtures/er-gold/bnf-persons/authorities.xml")

(def families
  "The families reported apart, by the mention's surname (`$a`): the first
   whose name is a token of it (so Robert-Dumas is a Dumas), else `\"others\"`."
  ["Dumas" "Hugo" "Hetzel" "Verne"])

(defn heading
  "The name `$a, $m` of a field's ordered `[code value]` subfields (the first of
   each, absent ones skipped), as the INTERMARC `:map/intermarc-agent` mapping
   recombines a 100; nil without either."
  [subfields]
  (let [first-of (fn [code] (some (fn [[c v]] (when (= code c) (some-> v str/trim not-empty))) subfields))
        parts    (keep first-of ["a" "m"])]
    (when (seq parts) (str/join ", " parts))))

(defn- sub [subfields code] (some (fn [[c v]] (when (= code c) v)) subfields))

(defn- fields [record tag]
  (filter #(= tag (get-in % [:locator :tag])) (:fragments record)))

(defn family [surname]
  (let [ts (set (str/split (text/norm surname) #" "))]
    (or (some #(when (ts (text/norm %)) %) families) "others")))

(defn- bib-records []
  (let [reg      (plug/register plug/empty-registry intermarc/plugin)
        compiled (mapping/compile-mappings (plug/all-mappings reg) (plug/effective-transforms reg))]
    (vec (for [f bib-files
               r (intermarc/ingest (slurp (str bib-dir f)) {})]
           (:record (runtime/run-phase r compiled :normalize))))))

(defn mentions-of
  "A record's mentions: its `100` and `700` occurrences as the common
   extraction gives them (`palomar.mentions`, lot N1: name, dates, role,
   relator, events, id, local node, read from each field alone), 100s first,
   each with what the eval adds — the field's tag and occurrence, the
   surname (`$a`), the family, the truth (`$3`), the subfields, and
   `:dates` nil when the field has no `$d`. The
   truth never reaches a matcher: each task passes it the keys it reads."
  [record]
  (let [ms (mentions/mentions record)]
    (for [tag ["100" "700"]
          m   ms
          :when (= tag (get-in m [:source :field]))
          :let [sfs (get-in m [:source :subfields])]]
      (assoc m
             :tag        tag
             :occurrence (get-in m [:source :occurrence])
             :surname    (sub sfs "a")
             :family     (family (sub sfs "a"))
             :truth      (sub sfs "3")
             :subfields  sfs
             ;; an undated mention is passed as `{:name :dates nil}`, as M2 passed it
             :dates      (:dates m)))))

(defn legacy-authority-pool
  "M2's test-side reader of the authority records in `xml`, kept to check
   that Palomar's own reader (`authority-pool`) gives the same pool: each
   entry `{:id :label :surname :variants}`, `:surname` the `100 $a`."
  [xml]
  (vec (for [r (intermarc/ingest xml {})
             :let [f001 (get-in (first (fields r "001")) [:locator :value])
                   h    (get-in (first (fields r "100")) [:locator :subfields])]]
         {:id       (subs f001 5 13)
          :label    (heading h)
          :surname  (sub h "a")
          :variants (vec (distinct (keep #(heading (get-in % [:locator :subfields])) (fields r "400"))))})))

(defn- surname-of
  "The surname of a `$a, $m` heading: what precedes its first comma."
  [label]
  (first (str/split label #", " 2)))

(defn authority-pool
  "The pool entries of the authority records in `xml`, read by
   `authorities/read-pool :intermarc`: `{:id :label :variants :surname
   :dates}`, `:id` the record number (the form of `$3`), `:surname` the
   heading's (`$a`), `:dates` when the `100 $d` states any."
  [xml]
  (vec (for [e (:entries (authorities/read-pool (authorities/sniff-format "authorities.xml" xml) xml))]
         (cond-> {:id       (:record-number e)
                  :label    (:label e)
                  :surname  (surname-of (:label e))
                  :variants (:variants e)}
           (:dates e) (assoc :dates (:dates e))))))

(defn corpus
  "`{:records [..] :mentions [..] :pool [..]}`."
  []
  (let [records (bib-records)]
    {:records  records
     :mentions (vec (mapcat mentions-of records))
     :pool     (sort-by :id (authority-pool (slurp authority-file)))}))

;; ---------------------------------------------------------------------------
;; Task A metrics — mention -> authority
;; ---------------------------------------------------------------------------

(defn link-outcome
  "The outcome of one mention whose true authority is `truth` (nil: none
   recorded), given the matcher's `proposals` (`[{:authority-id :score}]`, best
   first), the ids in the pool and a map id -> surname:
   `{:outcome :at5? :tied?}`. `:outcome` is `:undetermined`,
   `:not-in-pool/proposed` or `:not-in-pool/abstained`, else `:abstained`,
   `:correct`, `:false-link/homonym` (the top proposal shares the truth's
   surname) or `:false-link/other`. `:at5?`: the truth is among the first five;
   `:tied?`: another proposal has the top score."
  [truth proposals pool-ids surnames]
  (let [top   (first proposals)
        ids   (map :authority-id proposals)
        tied? (boolean (and top (< 1 (count (filter #(= (:score top) (:score %)) proposals)))))
        same  (fn [x y] (and x y (= (text/norm x) (text/norm y))))]
    {:outcome (cond
                (nil? truth)                 :undetermined
                (not (pool-ids truth))       (if top :not-in-pool/proposed :not-in-pool/abstained)
                (nil? top)                   :abstained
                (= truth (:authority-id top)) :correct
                (same (surnames truth) (surnames (:authority-id top))) :false-link/homonym
                :else                        :false-link/other)
     :at5?    (boolean (and truth (some #{truth} (take 5 ids))))
     :tied?   tied?}))

(defn- ratio [n d] (when (pos? d) (/ n d)))

(defn link-summary
  "Counts and rates over `outcomes` (`link-outcome` maps). The rates are over
   the mentions whose authority is in the pool (`:in-pool`), except
   `:precision-1`: the share right among every determined mention that received
   a link — a link for a mention whose authority is not in the pool is wrong."
  [outcomes]
  (let [n       (fn [pred] (count (filter pred outcomes)))
        is      (fn [& ks] (comp (set ks) :outcome))
        in-pool (is :correct :false-link/homonym :false-link/other :abstained)
        correct (n (is :correct))
        homonym (n (is :false-link/homonym))
        other   (n (is :false-link/other))
        nip-p   (n (is :not-in-pool/proposed))
        pool-n  (n in-pool)]
    {:mentions             (count outcomes)
     :in-pool              pool-n
     :correct              correct
     :at5                  (n #(and (in-pool %) (:at5? %)))
     :false-links          (+ homonym other)
     :homonym              homonym
     :other                other
     :abstained            (n (is :abstained))
     :tied-top1            (n #(and (in-pool %) (:tied? %)))
     :not-in-pool          (n (is :not-in-pool/proposed :not-in-pool/abstained))
     :not-in-pool-proposed nip-p
     :undetermined         (n (is :undetermined))
     :recall-1             (ratio correct pool-n)
     :recall-5             (ratio (n #(and (in-pool %) (:at5? %))) pool-n)
     :precision-1          (ratio correct (+ correct homonym other nip-p))}))

;; ---------------------------------------------------------------------------
;; Task B metrics — mention <-> mention clustering
;; ---------------------------------------------------------------------------

(defn- pairs [n] (quot (* n (dec n)) 2))

(defn- sum-pairs [groups] (reduce + (map (comp pairs count) (vals groups))))

(defn pairwise
  "Pairwise scores of a clustering against the gold, over `xs`
   (`[{:truth :cluster}]`): pairs put in one cluster (`:predicted`), pairs with
   one truth (`:true`), both (`:right`); `:false-merges` are pairs merged across
   two truths. Precision is nil when nothing is merged."
  [xs]
  (let [predicted (sum-pairs (group-by :cluster xs))
        true-n    (sum-pairs (group-by :truth xs))
        right     (sum-pairs (group-by (juxt :cluster :truth) xs))
        p         (ratio right predicted)
        r         (ratio right true-n)]
    {:predicted    predicted
     :true         true-n
     :right        right
     :false-merges (- predicted right)
     :missed       (- true-n right)
     :precision    p
     :recall       r
     :f1           (when (and p r) (if (zero? (+ p r)) 0 (/ (* 2 p r) (+ p r))))}))

(defn b-cubed
  "B-cubed precision and recall of a clustering over `xs` (`[{:truth
   :cluster}]`): the mean, per mention, of the share of its cluster with its
   truth, and of the share of its truth in its cluster."
  [xs]
  (let [cs (frequencies (map :cluster xs))
        ts (frequencies (map :truth xs))
        bs (frequencies (map (juxt :cluster :truth) xs))
        n  (count xs)
        mean (fn [f] (/ (reduce + (map f xs)) n))]
    {:precision (mean #(/ (bs [(:cluster %) (:truth %)]) (cs (:cluster %))))
     :recall    (mean #(/ (bs [(:cluster %) (:truth %)]) (ts (:truth %))))}))

;; ---------------------------------------------------------------------------
;; The two tasks on the corpus
;; ---------------------------------------------------------------------------

(defn matcher-entries
  "The pool as the name-only matcher receives it: `{:id :label :variants}` only."
  [pool]
  (mapv #(select-keys % [:id :label :variants]) pool))

(defn dated-entries
  "The pool as the date-aware matcher receives it: `{:id :label :variants
   :dates}` (`:dates` when the authority states any)."
  [pool]
  (mapv #(select-keys % [:id :label :variants :dates]) pool))

(defn- run-task-a
  [{:keys [mentions pool]} {:keys [mention entries candidates]}]
  (let [ids      (set (map :id pool))
        surnames (into {} (map (juxt :id :surname)) pool)
        es       (entries pool)
        scored   (vec (for [m mentions
                            :let [{ps :proposals rs :rejected ab :abstained} (candidates [(mention m)] es)]]
                        (cond-> (merge m {:proposals ps :rejected (vec rs)} (link-outcome (:truth m) ps ids surnames))
                          ab (assoc :abstained ab))))]
    {:all       (link-summary scored)
     :by-family (into (sorted-map) (for [[f ms] (group-by :family scored)] [f (link-summary ms)]))
     :mentions  scored}))

(defn task-a
  "Task A, the name-only baseline: every mention's name through `propose` (by
   default `reconcile/propose-agent-links`, default threshold) against the
   pool, as `(propose [name] entries)`, the entries `{:id :label :variants}`
   only — the matcher of M2, which sees no date. `{:all summary :by-family
   {family summary} :mentions [mention + :proposals :outcome…]}`."
  ([c] (task-a c reconcile/propose-agent-links))
  ([c propose]
   (run-task-a c {:mention :name :entries matcher-entries
                  :candidates (fn [ms es] {:proposals (propose ms es)})})))

(defn rejection-summary
  "Over task-A `scored` mentions: the mentions with a candidate rejected for
   its dates, the candidates rejected, and the mentions whose own authority
   (the truth) was rejected — a wrong block."
  [scored]
  {:mentions-with-rejections (count (filter (comp seq :rejected) scored))
   :rejected                 (reduce + (map (comp count :rejected) scored))
   :truth-rejected           (count (filter (fn [m] (some #(= (:truth m) (:authority-id %)) (:rejected m))) scored))})

(defn task-a-dated
  "Task A with the date-aware matcher (lot M3): each mention as `{:name
   :dates}` through `candidates` (by default `reconcile/agent-candidates`)
   against the entries with their `:dates`. As `task-a`, each mention also
   carrying its `:rejected` candidates, plus `:rejections`
   (`rejection-summary`)."
  ([c] (task-a-dated c reconcile/agent-candidates))
  ([c candidates]
   (let [res (run-task-a c {:mention #(select-keys % [:name :dates]) :entries dated-entries :candidates candidates})]
     (assoc res :rejections (rejection-summary (:mentions res))))))

;; ---------------------------------------------------------------------------
;; Lot M4 — a Fellegi-Sunter score
;; ---------------------------------------------------------------------------

(def alpha
  "The pseudo-count per level inside EM only (`em-model`): a model's weights
   are read from observed counts, never from pseudo-counts (`fs/model`)."
  1)

(defn comparisons
  "Every (mention, entry) pair of corpus `c`, compared (`fs/compare-pair`) as
   the date-aware matcher sees them — the mention's `{:name :dates}`, the
   entry's `{:id :label :variants :dates}`, the surname frequencies of the
   pool: `[{:mention i :family :truth :id :cmp}]`. The truth rides along for
   the estimation of m only; it never enters a comparison."
  [{:keys [mentions pool]}]
  (let [es     (dated-entries pool)
        counts (fs/surname-counts es)
        size   (count es)]
    (vec (for [[i m] (map-indexed vector mentions)
               e     es]
           {:mention i :family (:family m) :truth (:truth m) :id (:id e)
            :cmp (fs/compare-pair (select-keys m [:name :dates]) e {:surname-counts counts :pool-size size})}))))

(defn- blocked? [p] (= :incompatible (get-in p [:cmp :levels :dates])))

(defn pool-comparisons
  "Every ordered pair of two distinct entries of `pool`, the first's heading
   and dates compared as a mention with the second
   (`fs/compare-pair`): pairs of different persons by construction — two
   authority records — with no label read."
  [pool]
  (let [es     (dated-entries pool)
        counts (fs/surname-counts es)
        size   (count es)]
    (vec (for [a es b es :when (not= (:id a) (:id b))]
           (fs/compare-pair {:name (:label a) :dates (:dates a)} b {:surname-counts counts :pool-size size})))))

(defn fs-fit-data
  "What the models are fitted from: the mention comparisons (for m), and the
   u counts from the pool against itself (`pool-comparisons`): every ordered
   pair for the name and the form, the pairs the dates do not block for the
   dates (`fs/level-counts`). u is P(level | different persons): two distinct
   authority records are that, with no label read; under the model's own
   assumption (features independent given the persons differ) the name and
   form levels need not wait for the date block. The mention pairs are not
   used for u: once the dates block, about one in eight of those left is a
   true pair, which would count a match as a non-match. This is
   transductive: the pool is the gold's own authorities."
  [c]
  (let [us (pool-comparisons (:pool c))]
    {:pairs    (comparisons c)
     :u-counts (fs/level-counts us)
     :u-shares (fs/level-frequencies us alpha)
     :u-pairs  {:all (count us) :unblocked (count (remove #(= :incompatible (get-in % [:levels :dates])) us))}}))

(defn supervised-model
  "The model fitted on the mentions of the families `train`: m the level
   counts of their true pairs (entry = `$3`), λ the share of their pairs that
   are true (the determined mentions', the dates' blocks aside); u the pool's
   (`fs-fit-data`), label-free."
  [{:keys [pairs u-counts]} train]
  (let [tr   (filter #(and (train (:family %)) (:truth %) (not (blocked? %))) pairs)
        hits (filter #(= (:truth %) (:id %)) tr)]
    (fs/model {:m-counts (fs/level-counts (map :cmp hits)) :u-counts u-counts
               :prior (/ (double (count hits)) (count tr))
               :rules fs/o4-rules
               :estimation {:m :supervised :trained-on (vec (sort train))}})))

(defn fold-models
  "Leave-one-family-out: for each family, the model fitted on the four others."
  [data]
  (let [fams (set (conj families "others"))]
    (into (sorted-map) (for [f fams] [f (supervised-model data (disj fams f))]))))

(defn em-model
  "m and λ by EM (`fs/em`) over every mention pair the dates do not block, no
   label read, u held at the pool's (smoothed inside EM only); started from
   λ = 1 / pool size. The model's weights are read from EM's expected counts."
  [{:keys [pairs u-counts u-shares]} pool-size]
  (let [{:keys [m-counts prior iterations]} (fs/em (map :cmp (remove blocked? pairs))
                                                   {:u u-shares :prior (/ 1.0 pool-size) :alpha alpha})]
    (fs/model {:m-counts m-counts :u-counts u-counts :prior prior :rules fs/o4-rules
               :estimation {:m :em :iterations iterations :alpha alpha}})))

(defn- predictions
  "The (p, y) of every candidate scored for task-A `scored` mentions that are
   determined: proposals and abstentions, y 1 when the entry is the truth;
   `:pair` and `:mention` say which are the same (mention, candidate) pair
   and the same mention (name and dates), `:family` the fold."
  [scored]
  (vec (for [m scored :when (:truth m)
             c (concat (:proposals m) (:abstained m))
             :let [mk [(:name m) (:text (:dates m))]]]
         {:p (:score c) :y (if (= (:truth m) (:authority-id c)) 1 0)
          :pair (conj mk (:authority-id c)) :mention mk :family (:family m)})))

(defn- fs-summaries
  "A scored task-A run `res` with each mention's top `:decision`, the
   rejections, the review count, the outright-only summary and the
   predictions."
  [c res]
  (let [ms  (mapv #(assoc % :decision (:decision (first (:proposals %)))) (:mentions res))
        ids      (set (map :id (:pool c)))
        surnames (into {} (map (juxt :id :surname)) (:pool c))
        out      (for [m ms :let [ps (filter #(= :propose (:decision %)) (:proposals m))]]
                   (merge m (link-outcome (:truth m) ps ids surnames)))]
    (assoc res
           :mentions ms
           :rejections (rejection-summary ms)
           :review (count (filter #(= :review (:decision %)) ms))
           :outright {:all (link-summary out)
                      :by-family (into (sorted-map) (for [[f xs] (group-by :family out)] [f (link-summary xs)]))}
           :predictions (predictions ms))))

(defn refuse-in-sample!
  "Refuse `model` when it was fitted in-sample on `pool` or on the source
   files `sources` (`palomar.reconciliation/in-sample?`): the shipped
   model of `palomar reconcile` is fitted on all of M2, so it must not score
   M2 or a benchmark built from its records (lot N2 review). A benchmark
   calls it with each model it scores and its pool (and, optionally,
   `{:sources [path ..]}`). Nil when the model may score them."
  ([model pool] (reconciliation/refuse-in-sample! model pool))
  ([model pool opts] (reconciliation/refuse-in-sample! model pool opts)))

(defn task-a-fs
  "Task A with the Fellegi-Sunter matcher (lot M4): each mention as `{:name
   :dates}` through `candidates` (by default `reconcile/agent-candidates`)
   with `{:model (model-of mention)}` against the dated entries. As
   `task-a-dated`, each mention also carrying its `:abstained` candidates and
   the `:decision` of its top proposal; plus `:outright` (the summary when only
   proposals made outright count as links: one for review is no link) and
   `:predictions` (`[{:p :y :pair :mention :family}]`, for calibration)."
  ([c model-of] (task-a-fs c model-of reconcile/agent-candidates))
  ([c model-of candidates]
   (fs-summaries c (run-task-a c {:mention identity
                                  :entries dated-entries
                                  :candidates (fn [[m] es]
                                                (let [mdl (model-of m)]
                                                  (refuse-in-sample! mdl es)
                                                  (candidates [(select-keys m [:name :dates])] es {:model mdl})))}))))

(defn gold-fingerprint
  "The fingerprint of the gold of corpus `c`: each mention's name, dates and `$3`."
  [{:keys [mentions]}]
  (fs/fingerprint (vec (sort-by pr-str (map (juxt :name (comp :text :dates) :truth) mentions)))))

(defn fs-run
  "The M4 measurement on corpus `c`: the fold models, task A scored with each
   mention's held-out model, one calibration report of those held-out
   predictions pooled across the folds (on the pool and gold fingerprinted),
   attached to every fold model, and task A again with the models carrying
   it (so a proposal states a confidence only if the bar is met). The
   per-fold reports are measured too (`:fold-reports`), for the record; only
   the pooled one is attached."
  [c]
  (let [data   (fs-fit-data c)
        models (fold-models data)
        before (task-a-fs c (comp models :family))
        opts   {:pool (fs/pool-fingerprint (dated-entries (:pool c))) :gold (gold-fingerprint c)}
        report (fs/calibration (:predictions before) opts)
        cal    (update-vals models #(fs/with-calibration % report))]
    {:data         data
     :models       cal
     :calibration  report
     :fold-reports (into (sorted-map) (for [[f ps] (group-by :family (:predictions before))]
                                        [f (fs/calibration ps opts)]))
     :task-a       (task-a-fs c (comp cal :family))}))

;; --- the stress set: synthetic, derived from the gold mentions ---------------

(defn- strip-accents [s]
  (str/replace (java.text.Normalizer/normalize s java.text.Normalizer$Form/NFD) #"\p{M}+" ""))

(defn- initials [forenames]
  (str/join " " (for [w (str/split forenames #"\s+")]
                  (str/join "-" (for [part (str/split w #"-") :when (seq part)] (str (first part) "."))))))

(defn- swap-12 [s]
  (when (and (<= 4 (count s)) (not= (nth s 1) (nth s 2)))
    (str (subs s 0 1) (nth s 2) (nth s 1) (subs s 3))))

(def stress-kinds [:initials :dropped-forename :reversed :accents-removed :typo])

(defn stress-variants
  "The synthetic variants of a heading `Surname, Forenames`: forenames as
   initials (\"Hetzel, P.-J.\"), dropped (\"Hetzel\"), the order reversed
   (\"Pierre-Jules Hetzel\"), the accents removed (only when it has some), a
   typo (the surname's second and third letters swapped). `{kind name}`, the
   kinds that apply."
  [heading]
  (let [[sur fore] (map str/trim (str/split heading #"," 2))]
    (if (str/blank? fore)
      {}
      (cond-> {:initials         (str sur ", " (initials fore))
               :dropped-forename sur
               :reversed         (str fore " " sur)}
        (not= heading (strip-accents heading)) (assoc :accents-removed (strip-accents heading))
        (swap-12 sur)                          (assoc :typo (str (swap-12 sur) ", " fore))))))

(defn stress-set
  "SYNTHETIC. The variants (`stress-variants`) of each distinct (name, dates,
   truth) of the determined mentions whose authority is in the pool: `[{:kind
   :name :dates :truth :family :source}]`, the truth and the dates the gold
   mention's. Not a sample of real cataloguing: a probe of what each matcher
   lets through."
  [{:keys [mentions pool]}]
  (let [ids (set (map :id pool))]
    (vec (for [m (distinct (map #(select-keys % [:name :dates :truth :family])
                                (filter #(ids (:truth %)) mentions)))
               [kind nm] (sort-by (comp #(.indexOf ^java.util.List stress-kinds %) key) (stress-variants (:name m)))]
           (assoc m :kind kind :name nm :source (:name m))))))

(defn stress-recall
  "Over the stress set of `c`, by kind: `:n`, the variants whose truth is a
   candidate of the M3 matcher (Jaccard >= 0.5, dates not incompatible:
   `:m3-candidate`) and of the Fellegi-Sunter one (name level above `:none`,
   dates not incompatible: `:fs-candidate`), and, for the latter, whose top
   proposal is the truth (`:fs-top-1`), outright (`:fs-outright`); a top
   proposal another one ties (the same weight) is `:fs-tied`, never a right
   top proposal: pool order decides no tie (lot O4). `:rows`, one per
   variant."
  [c model-of]
  (let [es (dated-entries (:pool c))
        rows (for [s (stress-set c)
                   :let [m   (select-keys s [:name :dates])
                         m3  (reconcile/agent-candidates [m] es)
                         r   (reconcile/agent-candidates [m] es {:model (model-of s)})
                         has (fn [cs] (boolean (some #(= (:truth s) (:authority-id %)) cs)))
                         [top second] (:proposals r)
                         tied (boolean (and second (= (:match-weight top) (:match-weight second))))]]
               {:kind         (:kind s)
                :name         (:name s)
                :m3-candidate (has (:proposals m3))
                :fs-candidate (has (concat (:proposals r) (:abstained r)))
                :fs-tied      tied
                :fs-top-1     (and (not tied) (= (:truth s) (:authority-id top)))
                :fs-outright  (and (= (:truth s) (:authority-id top)) (= :propose (:decision top)))})
        sum  (fn [xs] (into {:n (count xs)} (for [k [:m3-candidate :fs-candidate :fs-top-1 :fs-tied :fs-outright]]
                                              [k (count (filter k xs))])))]
    (assoc (into (sorted-map) (for [[k xs] (group-by :kind rows)] [k (sum xs)]))
           :all (sum rows)
           :rows (vec rows))))

(def clusterings
  "Task B's clusterings of a mention, by key. `:record-scoped` is what Palomar
   does today with a name-only agent (ADR 0041): its node is minted from the
   record and the name (`reconcile/align-agents`' local agent, the Linked Art
   actor), so two records' same names are never merged. `:same-name` is the
   simplest name rule, same `text/norm` name -> same person, which no Palomar
   code applies across records today."
  {:record-scoped (juxt :record :name)
   :same-name     (comp text/norm :name)})

(def rule-order [:record-scoped :same-name :top-1 :top-1-dated :top-1-fs])

(defn task-b
  "Task B: the determined mentions (those with a `$3`) clustered by each of
   `clusterings`, and by `:top-1` — the clustering a curator would get by
   accepting every mention's top `propose-agent-links` proposal (pool in id
   order): mentions with one top authority are one person, a mention with no
   proposal is alone — and by `:top-1-dated`, the same with the date-aware
   matcher (lot M3: the mention's dates, the entries'), and by `:top-1-fs`,
   the top Fellegi-Sunter proposal (lot M4, outright or for review; the
   mention's held-out fold model). Each is scored pairwise and by B-cubed. `:mixed` lists, for the same-name rule, the
   clusters spanning several authorities."
  [{:keys [mentions pool] :as c}]
  (let [ms      (filter :truth mentions)
        alone   (fn [m] [:alone (:record m) (:tag m) (:occurrence m)])
        entries (matcher-entries pool)
        dated   (dated-entries pool)
        top-1   (fn [m] (or (:authority-id (first (reconcile/propose-agent-links [(:name m)] entries))) (alone m)))
        top-1-d (fn [m] (or (:authority-id (first (reconcile/propose-agent-links [(select-keys m [:name :dates])] dated)))
                            (alone m)))
        models  (fold-models (fs-fit-data c))
        top-1-f (fn [m] (or (:authority-id (first (reconcile/propose-agent-links [(select-keys m [:name :dates])] dated
                                                                                 {:model (models (:family m))})))
                            (alone m)))
        rules   (assoc clusterings :top-1 top-1 :top-1-dated top-1-d :top-1-fs top-1-f)]
    (into {:mentions (count ms)
           :mixed    (into (sorted-map)
                           (for [[k g] (group-by (:same-name clusterings) ms)
                                 :let [fq (frequencies (map :truth g))]
                                 :when (< 1 (count fq))]
                             [k (into (sorted-map) fq)]))}
          (for [[nm f] rules
                :let [xs (map #(hash-map :truth (:truth %) :cluster (f %)) ms)]]
            [nm {:pairwise (pairwise xs) :b-cubed (b-cubed xs)}]))))

;; ---------------------------------------------------------------------------
;; Lot M5 — constrained clustering
;; ---------------------------------------------------------------------------

(defn fs-links
  "One mention's pairwise decisions as `agent-cluster/cluster` links and
   cannot-links: the Fellegi-Sunter `candidates` (`reconcile/agent-candidates`
   with a model) of the mention item `id`, an outright proposal a `:propose`
   link, one for review a `:review` link, each of its match weight, and a
   candidate rejected (for its dates, M3, or the mention's events, M7) a
   cannot-link, with the rejection's reason."
  [id {:keys [proposals rejected]}]
  {:links       (vec (for [p proposals]
                       {:a id :b (:authority-id p) :basis (if (= :propose (:decision p)) :propose :review)
                        :weight (:match-weight p)}))
   :cannot-link (vec (for [r rejected] {:a id :b (:authority-id r) :reason (:reason r)}))})

(defn cluster-input
  "The clustering input of the determined mentions of corpus `c` (task B's
   141): an item per mention (`[:mention i]`, its dates) and per pool entry
   (an authority, its dates), the mentions' Fellegi-Sunter decisions, each
   from its family's held-out model (`models`, `fold-models`), as links, and
   their date rejections as cannot-links (`fs-links`). `:mentions` the
   determined mentions, in item order."
  [{:keys [mentions pool]} models]
  (let [ms    (vec (filter :truth mentions))
        es    (dated-entries pool)
        per-m (for [[i m] (map-indexed vector ms)]
                (fs-links [:mention i] (reconcile/agent-candidates [(select-keys m [:name :dates])] es
                                                                   {:model (models (:family m))})))]
    {:mentions    ms
     :items       (merge (into {} (for [e es] [(:id e) (cond-> {:authority? true} (:dates e) (assoc :dates (:dates e)))]))
                         (into {} (for [[i m] (map-indexed vector ms)]
                                    [[:mention i] (cond-> {} (dates/dated? (:dates m)) (assoc :dates (:dates m)))])))
     :links       (vec (mapcat :links per-m))
     :cannot-link (vec (mapcat :cannot-link per-m))}))

(defn- score-clustering
  [ms cluster-of]
  (let [xs (map-indexed (fn [i m] {:truth (:truth m) :cluster (cluster-of [:mention i])}) ms)]
    {:pairwise (pairwise xs) :b-cubed (b-cubed xs)}))

(def m5-rule-order [:top-1-fs :closure :constrained :closure-review :constrained-review])

(defn task-b-constrained
  "Task B (lot M5) on corpus `c`: the determined mentions clustered from
   their Fellegi-Sunter decisions (`cluster-input`) — `:closure` the naive
   transitive closure of the outright proposals, `:constrained` the
   constrained clustering under its policy (only outright proposals join;
   no curated link exists in this eval), `:closure-review` and
   `:constrained-review` the same when every proposal for review joins too,
   at its weight, as a proposal would (a what-if, not curation: a review
   candidate a curator confirmed, `reconcile/confirm-identity`, would be an
   accepted must-link, and two confirmed for one mention a conflict) — each scored pairwise and by B-cubed,
   with `:top-1-fs` (M4's rule) for reference. Per rule, `:violations` (the
   cannot-linked pairs it puts in one cluster); for the constrained ones,
   `:refused` (the links refused) and `:candidates` (the review links left
   to a curator)."
  [c]
  (let [models (fold-models (fs-fit-data c))
        input  (cluster-input c models)
        ms     (:mentions input)
        as-res (fn [cl] {:cluster-of cl :clusters (for [[_ g] (group-by cl (keys cl))] {:members (set g)})})
        run    (fn [join]
                 (let [res (ac/cluster (assoc input :join join))]
                   (assoc (score-clustering ms (:cluster-of res))
                          :violations (count (ac/violations input res))
                          :refused (count (:refused res))
                          :candidates (count (:candidates res)))))
        naive  (fn [join]
                 (let [cl (ac/transitive-closure (assoc input :join join))]
                   (assoc (score-clustering ms cl) :violations (count (ac/violations input (as-res cl))))))
        top-1  (let [tops (into {} (for [[i m] (map-indexed vector ms)
                                         :let [p (first (reconcile/propose-agent-links
                                                         [(select-keys m [:name :dates])] (dated-entries (:pool c))
                                                         {:model (models (:family m))}))]]
                                     [[:mention i] (or (:authority-id p) [:alone i])]))]
                 (score-clustering ms tops))
        all    #{:accepted :propose :review}]
    {:mentions           (count ms)
     :links              (frequencies (map :basis (:links input)))
     :cannot-link        (count (:cannot-link input))
     :top-1-fs           top-1
     :closure            (naive ac/default-join)
     :constrained        (run ac/default-join)
     :closure-review     (naive all)
     :constrained-review (run all)}))

(defn chain-cases
  "SYNTHETIC, derived from the pool of corpus `c`: one chain per pair of
   authorities X, Y (X first by id) with one surname whose dates are
   incompatible. A bridge U, an undated authority record with X's heading
   (`bridge:` X); a mention mx, X's heading with X's dates; a mention my, X's
   heading with Y's dates (a homonym of X, born when Y was). Each mention is
   scored against [U] alone by `model`; mx ~ U ~ my is a chain when both are
   proposed outright, and mx ⊥ my by their dates. `[{:x :y :links :input}]`."
  [{:keys [pool]} model]
  (let [es (dated-entries pool)]
    (vec (for [x es y es
               :let [sx (surname-of (:label x)) sy (surname-of (:label y))]
               :when (and (neg? (compare (:id x) (:id y))) (= sx sy) (:dates x) (:dates y)
                          (= :incompatible (:verdict (dates/compatibility (:dates x) (:dates y)))))
               :let [u     {:id (str "bridge:" (:id x)) :label (:label x)}
                     score (fn [id dts] (fs-links id (reconcile/agent-candidates [{:name (:label x) :dates dts}] [u]
                                                                                 {:model model})))
                     lx    (score :mx (:dates x))
                     ly    (score :my (:dates y))]]
           {:x (:id x) :y (:id y) :label (:label x)
            :input {:items {:mx {:dates (:dates x)} :my {:dates (:dates y)} (:id u) {:authority? true}}
                    :links (into (:links lx) (:links ly))}}))))

(defn chain-summary
  "Over `chain-cases`: the cases, the chains (both mentions proposed
   outright), and the cases where the naive closure (`:closure`) and the
   constrained clustering (`:constrained`) put mx and my in one cluster (a
   false merge: they are X and Y, two persons), the links refused and those
   refused on a tie (`:refused-on-a-tie`: equal weights, the lower id wins),
   and the bridge's side under the constrained one (`:bridge-with`, counts
   of `:mx` / `:my`)."
  [cases]
  (let [rows (for [{:keys [input]} cases
                   :let [chain? (= #{:propose} (set (map :basis (:links input))))
                         cl     (ac/transitive-closure input)
                         res    (ac/cluster input)
                         co     (:cluster-of res)
                         bridge (first (filter string? (keys (:items input))))]]
               {:chain?      chain?
                :closure     (= (cl :mx) (cl :my))
                :constrained (= (co :mx) (co :my))
                :refused     (count (:refused res))
                :ties        (count (filter :tie? (:refused res)))
                :bridge-with (cond (= (co bridge) (co :mx)) :mx (= (co bridge) (co :my)) :my :else :alone)})]
    {:cases                (count rows)
     :chains               (count (filter :chain? rows))
     :closure-merges       (count (filter :closure rows))
     :constrained-merges   (count (filter :constrained rows))
     :refused              (reduce + (map :refused rows))
     :refused-on-a-tie     (reduce + (map :ties rows))
     :bridge-with          (frequencies (map :bridge-with (filter :chain? rows)))}))

;; ---------------------------------------------------------------------------
;; Lot M7 — typed events
;; ---------------------------------------------------------------------------

(defn with-edition-events
  "The mentions of corpus `c`, each with `:events`: its record's edition
   events (`agent-events/edition-events`), typed for its role — as the
   common extraction (`palomar.mentions`) gives every mention."
  [{:keys [mentions]}]
  (mapv #(update % :events vec) mentions))

(defn- event-rejections
  "Over task-A `scored` mentions: the candidates rejected for the mention's
   events, and the mentions whose truth was."
  [scored]
  (let [ev? #(= :incompatible-events (:reason %))]
    {:rejected       (reduce + (map #(count (filter ev? (:rejected %))) scored))
     :truth-rejected (count (filter (fn [m] (some #(and (ev? %) (= (:truth m) (:authority-id %))) (:rejected m))) scored))}))

(defn task-a-events
  "Task A (lot M7) with each mention's edition events: `:with-dates`, the
   mention `{:name :dates :events}` (to compare with `task-a-dated`);
   `:events-only`, `{:name :events}` (what the events alone reject); and
   `:as-creation`, a what-if that reads every edition date as a creation the
   mention carried out (the reading M7 refuses), `{:name :events}`. Each
   with `:rejections` and `:event-rejections`. `:events` counts the
   mentions with an edition event, the events, those with a date, and the
   determined mentions with an edition after their authority's death."
  [c]
  (let [ms     (with-edition-events c)
        c'     (assoc c :mentions ms)
        run    (fn [ks f]
                 (let [res (run-task-a c' {:mention #(f (select-keys % ks)) :entries dated-entries
                                           :candidates reconcile/agent-candidates})]
                   (assoc res :rejections (rejection-summary (:mentions res))
                          :event-rejections (event-rejections (:mentions res)))))
        as-cr  (fn [m] (update m :events (partial mapv #(assoc % :kind :creation))))
        deaths (into {} (for [e (:pool c) :let [d (:to (:death (:dates e)))] :when d] [(:id e) d]))]
    {:events      {:mentions   (count (filter (comp seq :events) ms))
                   :events     (reduce + (map (comp count :events) ms))
                   :dated      (count (filter :date (mapcat :events ms)))
                   :posthumous (count (filter (fn [m] (some #(when-let [d (deaths (:truth m))]
                                                               (some-> % :date :from (> (+ d dates/tolerance))))
                                                            (:events m)))
                                              ms))}
     :with-dates  (run [:name :dates :events] identity)
     :events-only (run [:name :events] identity)
     :as-creation (run [:name :events] as-cr)}))

(defn event-cases
  "SYNTHETIC, built from the real authorities of corpus `c`'s pool: for each
   entry stating a birth and a death, six mentions of its heading carrying
   one event each and no dates — a creation 30 years after the death, a
   creation 30 years and one 8 years before the birth, a creation in
   mid-life, an edition
   30 years after the death (posthumous) and an edition 30 years before the
   birth — each matched against the whole dated pool. `[{:case :entry
   :blocked?}]`, `:blocked?` true when the entry the event was built from is
   rejected for the events."
  [{:keys [pool]}]
  (let [es (dated-entries pool)]
    (vec (for [e es
               :let [b (:from (:birth (:dates e))) d (:to (:death (:dates e)))]
               :when (and b d)
               [k kind year] [[:creation-after-death :creation (+ d 30)]
                              [:creation-before-birth :creation (- b 30)]
                              [:creation-just-before-birth :creation (- b 8)]
                              [:creation-in-life :creation (quot (+ b d) 2)]
                              [:posthumous-edition :publication (+ d 30)]
                              [:edition-before-birth :publication (- b 30)]]
               :let [m   {:name (:label e) :events [{:kind kind :role "author" :date {:from year :to year}}]}
                     res (reconcile/agent-candidates [m] es)]]
           {:case k :entry (:id e)
            :blocked? (boolean (some #(and (= (:id e) (:authority-id %)) (= :incompatible-events (:reason %)))
                                     (:rejected res)))
            :cannot-link? (= :incompatible-events
                             (ac/cannot-link-reason {:m {:events (:events m)} :e {:authority? true :dates (:dates e)}}
                                                    {} :m :e))}))))

(defn event-case-summary
  "Per case of `event-cases`: `[cases blocked cannot-linked]`."
  [cases]
  (into (sorted-map) (for [[k rs] (group-by :case cases)]
                       [k [(count rs) (count (filter :blocked? rs)) (count (filter :cannot-link? rs))]])))

(def ulan-prefix "http://vocab.getty.edu/ulan/")

(defn lido-event-run
  "Lot M7 on LIDO `records` (a museum's objects): the person-level truth is
   the ULAN id an actor carries. The pool is one entry per ULAN id of a
   person actor, its label the name most of its occurrences give, its dates
   the vital dates they state (the most frequent). Each person actor
   occurrence with a ULAN id is a mention `{:name :events}` (its life dates
   withheld: what its events alone say), matched name-only against the pool.
   `:as-creation` reads every event as a creation (the what-if M7 refuses).
   Returns `{:mentions :pool :kinds :rejected :truth-rejected
   :as-creation {:rejected :truth-rejected :by-kind}}`."
  [records]
  (let [ms    (for [m (mentions/batch records)
                    :let [u (first (filter #(str/starts-with? % ulan-prefix) (:ids m)))]
                    :when (and u (= "person" (:actor-type m)))]
                (assoc m :truth u))
        mode  (fn [xs] (key (first (sort-by (juxt (comp - val) (comp str key)) (frequencies xs)))))
        pool  (vec (for [[u xs] (sort-by key (group-by :truth ms))
                         :let [ds (filter dates/dated? (map :dates xs))]]
                     (cond-> {:id u :label (mode (map :name xs))}
                       (seq ds) (assoc :dates (mode ds)))))
        run   (fn [f]
                (let [rs (for [m ms]
                           (assoc m :rejected (:rejected (reconcile/agent-candidates [(f (select-keys m [:name :events]))] pool))))
                      ev? #(= :incompatible-events (:reason %))
                      tr  (filter (fn [m] (some #(and (ev? %) (= (:truth m) (:authority-id %))) (:rejected m))) rs)]
                  {:rejected       (reduce + (map #(count (filter ev? (:rejected %))) rs))
                   :truth-rejected (count tr)
                   :by-kind        (frequencies (mapcat #(map :kind (:events %)) tr))}))]
    {:mentions    (count ms)
     :pool        (count pool)
     :dated-pool  (count (filter :dates pool))
     :kinds       (frequencies (map :kind (mapcat :events ms)))
     :creations   (count (filter #(and (= :creation (:kind %)) (:date %)) (mapcat :events ms)))
     :run         (run identity)
     :as-creation (run (fn [m] (update m :events (partial mapv #(assoc % :kind :creation)))))}))

;; ---------------------------------------------------------------------------
;; Lot M8 — collective reconciliation: relational evidence from anchors
;; ---------------------------------------------------------------------------

(defn mention-id
  "A mention's id: its record, its field's tag and occurrence — the common
   extraction's `:id` (`palomar.mentions`)."
  [m]
  (:id m))

(defn relational-mention
  "A mention as the relational matcher reads it: `{:id :name :dates :record
   :role}`, `:role` its field's `$4` relator code (none when the field has
   none: never the tag). No `$3`."
  [m]
  (cond-> (assoc (select-keys m [:name :dates :record]) :id (mention-id m))
    (:relator m) (assoc :role (:relator m))))

(defn relational-entries
  "The dated entries, each with the relations its authority record states,
   as Palomar's INTERMARC authority reader gives them (`:record-number`
   targets), and its own `:record-number` (the M2 id), so that
   `agent-relations/resolve-relations` resolves them as in production."
  [pool]
  (let [xml  (slurp authority-file)
        rels (into {} (for [e (:entries (authorities/read-pool :intermarc xml)) :when (:relations e)]
                        [(:record-number e) (:relations e)]))]
    (mapv #(cond-> (assoc % :record-number (:id %)) (rels (:id %)) (assoc :relations (rels (:id %))))
          (dated-entries pool))))

(def anchor-settings
  "The anchor settings measured: `:none` (no anchor); two oracles, the gold
   links of an oracle curator — `:leave-one-record-out` (every mention
   outside the judged mention's record) and `:leave-one-mention-out` (every
   other mention, the record's co-mentions included); and two realistic
   ones, built by Palomar's curation path (`curated-anchors`) —
   `:outright-correct` (the curator accepted the M4 outright proposals that
   are right) and `:outright-plus-one-confirmed` (those, and for each name
   M4 left for review, one mention chosen by a rule that reads no gold,
   confirmed to its true authority when that is one of its candidates)."
  [:none :leave-one-record-out :leave-one-mention-out :outright-correct :outright-plus-one-confirmed])

(def oracle-settings #{:leave-one-record-out :leave-one-mention-out})

(defn- gold-anchor
  "An oracle's validation of mention `m`'s gold link (`$3`)."
  [m]
  (cond-> {:id (mention-id m) :mention (mention-id m) :record (:record m) :authority-id (:truth m)
           :status :accepted :oracle? true}
    (:relator m) (assoc :role (:relator m))))

(def ^:private iri-prefix
  "The M2 ids are BnF record numbers, no IRI; the curated path, which only
   aligns to an IRI, reads them under this prefix."
  "urn:x-bnf-record:")

(defn- with-agent-names
  "Corpus `c`'s records, each also naming its 700 persons as `:canon/agent`
   (the classic INTERMARC mapping carries only the 100; the INTERMARC-NG
   spoke carries both), so that `align-agents` sees every mention."
  [{:keys [records mentions]}]
  (let [extra (group-by :record (filter #(= "700" (:tag %)) mentions))]
    (mapv (fn [r] (reduce (fn [r m] (update r :assertions conj (model/assertion {:subject (:id r) :predicate :canon/agent
                                                                                 :value (:name m)})))
                          r (extra (:id r))))
          records)))

(defn confirmation-choice
  "The mentions the curator confirms in `:outright-plus-one-confirmed`: for
   each name whose M4 top proposal is for review (`m4`'s held-out run), the
   mention whose `mention-id` prints first. The rule reads no gold."
  [m4]
  (vec (for [[_ g] (sort-by key (group-by :name (filter #(= :review (:decision %)) (:mentions (:task-a m4)))))]
         (first (sort-by (comp pr-str mention-id) g)))))

(def ^:private aligned-records
  "The records of corpus `c` with every mention's M4 proposals aligned
   (`align-agents`): what the curator reads. Memoized; `c`'s mentions are
   passed without their truth, which this step never reads."
  (memoize
   (fn [c m4]
     (let [entries (mapv #(update % :id (partial str iri-prefix)) (dated-entries (:pool c)))
           props   (vec (mapcat (fn [m] (:proposals (reconcile/agent-candidates [(relational-mention m)] entries
                                                                                {:model ((:models m4) (:family m))
                                                                                 :anchors (ar/freeze-anchors [])})))
                                (:mentions c)))]
       (reconcile/align-agents (with-agent-names c) props)))))

(defn curated-anchors
  "The realistic anchors, through Palomar's own path: every mention of
   corpus `c` matched by its fold's M4 model (the decisions of M4; an empty
   anchor set, so each proposal carries its mention's id and role),
   `align-agents` on the records, a curator who accepts the outright exact
   matches that are right (`curate/decide-one`) and, for `confirm`
   mentions, confirms the close match to the truth when it is one
   (`confirm-identity`), then `accepted-links` and `freeze-anchors`.
   `{:frozen :accepted n :confirmed [mention-id ..] :selected n}`. The
   truth is read from `c` (so a faked gold reaches the curator)."
  [c m4 confirm]
  (let [recs    (aligned-records (update c :mentions (partial mapv #(dissoc % :truth))) m4)
        truth   (into {} (for [m (:mentions c)] [(mention-id m) (some->> (:truth m) (str iri-prefix))]))
        conf    (set (map mention-id confirm))
        target  (fn [r a] (some #(when (= (:id %) (get-in a [:value :value/target])) (:iri %)) (:entities r)))
        mid     #(get-in % [:provenance :bindings '?mention])
        curate1 (fn [r]
                  (reduce (fn [r a]
                            (let [t (truth (mid a))]
                              (cond
                                (and (= :palomar/matchesAuthority (:predicate a)) (= :proposed (:status a))
                                     (not (conf (mid a))) (some? t) (= t (target r a)))
                                (:record (curate/decide-one r a :accept "right, by the gold"))
                                (and (= :palomar/resemblesAuthority (:predicate a)) (= :proposed (:status a))
                                     (conf (mid a)) (some? t) (= t (target r a)))
                                (:record (reconcile/confirm-identity r a "confirmed by the curator"))
                                :else r)))
                          r (:assertions r)))
        cur     (mapv curate1 recs)
        links   (reconcile/accepted-links cur)
        strip   #(str/replace-first % iri-prefix "")]
    {:frozen    (ar/freeze-anchors (map #(update % :authority-id strip) links))
     :accepted  (count links)
     :selected  (count confirm)
     :confirmed (vec (sort-by pr-str (distinct (for [l links :when (conf (:mention l))] (:mention l)))))}))

(defn anchors-of
  "For corpus `c` and `setting`: `{:anchors-of f :curated ..}`, `f` a
   function of a relational mention giving the frozen anchor set its
   evidence reads (the exclusion of its own link and of what depends on it
   is the matcher's), `:curated` the curated path's report in a realistic
   setting."
  [{:keys [mentions] :as c} setting m4]
  (let [det (filter :truth mentions)]
    (case setting
      :none                  {:anchors-of (constantly (ar/freeze-anchors []))}
      :leave-one-mention-out (let [fr (ar/freeze-anchors (map gold-anchor det))] {:anchors-of (constantly fr)})
      :leave-one-record-out  (let [by-r (memoize (fn [r] (ar/freeze-anchors (map gold-anchor (remove #(= r (:record %)) det)))))]
                               {:anchors-of (fn [m] (by-r (:record m)))})
      (:outright-correct :outright-plus-one-confirmed)
      (let [cur (curated-anchors c m4 (when (= :outright-plus-one-confirmed setting) (confirmation-choice m4)))]
        {:anchors-of (constantly (:frozen cur)) :curated cur}))))

(defn- family-of [c] (into {} (for [m (:mentions c)] [(mention-id m) (:family m)])))

(defn- held-out
  "`anchors-of` restricted, for training the fold of `family`, to the
   anchors of mentions of other families: the held-out family's links never
   reach the training pairs' features."
  [c anchors-of family]
  (let [fam  (family-of c)
        memo (memoize (fn [fr] (ar/freeze-anchors (remove #(= family (fam (:mention %))) (:anchors fr)))))]
    (fn [rm] (memo (anchors-of rm)))))

(defn relational-pairs
  "Every (mention, entry) pair the M4 matcher scores (name level above
   `:none`, dates not blocking) of the determined mentions of the
   `families`, with its relational comparison under `anchors-of`: `[{:family
   :truth :id :y :rel}]`. The truth labels the pair for m and u; it never
   enters a comparison."
  [{:keys [mentions]} data entries anchors-of families]
  (let [by-e (into {} (map (juxt :id identity)) (ar/resolve-relations entries))
        idx  (into {} (map-indexed vector) mentions)]
    (vec (for [p (:pairs data)
               :let [m (idx (:mention p))]
               :when (and (:truth m) (families (:family m)) (not= :none (get-in p [:cmp :levels :name])) (not (blocked? p)))
               :let [rm (relational-mention m)]]
           {:family (:family m) :truth (:truth m) :id (:id p) :y (= (:truth m) (:id p))
            :rel (ar/compare-relations (anchors-of rm) rm (by-e (:id p)))}))))

(defn relational-fold-models
  "Leave-one-family-out, truly: each fold's M4 model with relational weights
   measured on the four other families' pairs, whose features read no
   anchor of the held-out family (`held-out`): m from their true pairs, u
   from their other scored candidates (labelled: the pool against itself
   has no record context). `{family model}`, and `:pairs` the training
   pairs of each fold."
  [c m4 entries anchors-of]
  (let [fams (set (conj families "others"))]
    (into (sorted-map)
          (for [f fams
                :let [tr (relational-pairs c (:data m4) entries (held-out c anchors-of f) (disj fams f))]]
            [f {:model (ar/with-relations ((:models m4) f)
                         {:m-counts (ar/level-counts (map :rel (filter :y tr)))
                          :u-counts (ar/level-counts (map :rel (remove :y tr)))
                          :estimation {:trained-on (vec (sort (disj fams f)))}})
                :pairs tr}]))))

(defn task-a-relational
  "Task A (lot M8) under anchor `setting`: each mention as
   `relational-mention`, against the entries with their stated relations,
   with its family's relational fold model and its anchor set. Mentions the
   curator confirmed are curator work, not the machine's: they are left out
   of the measured set (`:confirmed`). As `task-a-fs`, plus `:models`,
   `:folds` (each fold's training pairs), `:anchors` (the largest anchor
   set a mention reads), `:curated` (the curated path's report) and
   `:anchors-of`."
  [c setting m4]
  (let [entries (relational-entries (:pool c))
        {f :anchors-of cur :curated} (anchors-of c setting m4)
        folds   (relational-fold-models c m4 entries f)
        conf    (set (:confirmed cur))
        c'      (update c :mentions (fn [ms] (vec (remove #(conf (mention-id %)) ms))))
        res     (run-task-a c' {:mention identity
                                :entries (fn [_] entries)
                                :candidates (fn [[m] es]
                                              (let [rm (relational-mention m)]
                                                (reconcile/agent-candidates [rm] es {:model (:model (folds (:family m))) :anchors (f rm)})))})]
    (assoc (fs-summaries c' res)
           :models (update-vals folds :model)
           :folds (update-vals folds :pairs)
           :curated cur
           :confirmed (vec (sort-by pr-str conf))
           :anchors-of f
           :anchors (reduce max 0 (map #(count (:anchors (f (relational-mention %)))) (:mentions c'))))))

(defn decisions
  "Per mention of a task-A run, what the matcher decided: each candidate's
   id, decision, score, weight and evidence, proposals then abstentions."
  [run]
  (mapv (fn [m] (mapv (juxt :authority-id :decision :score :match-weight :evidence) (concat (:proposals m) (:abstained m))))
        (:mentions run)))

(defn decision-changes
  "The measured mentions of `run` whose decision on some candidate differs
   from M4's held-out run `m4`: `{mention-id [[id m4-decision decision] ..]}`."
  [run m4]
  (let [dec  (fn [m] (into {} (map (juxt :authority-id :decision)) (concat (:proposals m) (:abstained m))))
        base (into {} (for [m (:mentions (:task-a m4))] [(mention-id m) (dec m)]))]
    (into (sorted-map-by #(compare (pr-str %1) (pr-str %2)))
          (for [m (:mentions run)
                :let [b (base (mention-id m)) d (dec m)
                      ch (vec (for [[id x] d :when (not= x (b id))] [id (b id) x]))]
                :when (seq ch)]
            [(mention-id m) ch]))))

(defn review-outcomes
  "Over a task-A run, the mentions M4 left for review (`m4-review`, a set of
   mention ids), by name: `{name {[decision right?] n}}`."
  [run m4-review]
  (into (sorted-map)
        (for [[nm ms] (group-by :name (filter #(m4-review (mention-id %)) (:mentions run)))]
          [nm (frequencies (map (fn [m] [(:decision m) (= (:truth m) (:authority-id (first (:proposals m))))]) ms))])))

(defn relational-levels
  "Over relational pairs, the levels reached, `{feature {[true? level] n}}`."
  [rpairs]
  (into (sorted-map) (for [f (keys ar/features)]
                       [f (into (sorted-map) (frequencies (map (fn [p] [(:y p) (get-in p [:rel :levels f])]) rpairs)))])))

(defn leakage
  "The leakage checks of `setting` on corpus `c`, for every determined
   mention m (or the first `n`): its gold is replaced by another authority
   at the source the setting's anchors are built from — the oracle's gold,
   or the gold the curator reads in the curated path, rebuilt end to end —
   and its relational evidence, over every entry, must be the same
   (`:changed-by-own-gold`); the support never names m (`:self-in-support`)
   nor, leaving its record out, a mention of its record; and the anchors of
   other mentions of m's own truth that m's evidence may read are counted
   (`:same-person`), with where they come from: the oracle (`:oracle`), or
   the curator's accepted claims (`:curator`)."
  ([c setting m4] (leakage c setting m4 nil))
  ([c setting m4 n]
   (let [entries (ar/resolve-relations (relational-entries (:pool c)))
         f       (:anchors-of (anchors-of c setting m4))
         det     (cond->> (filter :truth (:mentions c)) n (take n))
         rows    (for [m det
                       :let [rm   (relational-mention m)
                             fake (assoc c :mentions (mapv #(if (= (mention-id %) (mention-id m)) (assoc % :truth "00000000") %)
                                                           (:mentions c)))
                             f'   (:anchors-of (anchors-of fake setting m4))
                             rcs  (map #(ar/compare-relations (f rm) rm %) entries)
                             rcs' (map #(ar/compare-relations (f' rm) rm %) entries)
                             sup  (set (mapcat :support rcs))
                             same (filter #(and (= (:truth m) (:authority-id %)) (not= (:id rm) (:mention %)))
                                          (ar/usable-anchors (f rm) rm))]]
                   {:self   (contains? sup (:id rm))
                    :record (boolean (some #(= (:record m) (first %)) sup))
                    :change (not= rcs rcs')
                    :same   (seq same)
                    :source (set (map #(if (:oracle? %) :oracle :curator) same))})]
     {:mentions            (count rows)
      :self-in-support     (count (filter :self rows))
      :record-in-support   (count (filter :record rows))
      :changed-by-own-gold (count (filter :change rows))
      :same-person         {:mentions (count (filter :same rows)) :source (reduce into #{} (map :source rows))}})))

(defn task-b-relational
  "Task B (lot M8): the measured determined mentions clustered
   (`agent-cluster`) from their relational decisions under `run` (a
   `task-a-relational`), as `task-b-constrained` does from M4's:
   `:constrained` under the policy (outright proposals join),
   `:closure-review` and `:constrained-review` when review links join too
   (a what-if)."
  [c run]
  (let [ms    (vec (filter :truth (:mentions run)))
        es    (dated-entries (:pool c))
        per-m (for [[i m] (map-indexed vector ms)]
                (fs-links [:mention i] {:proposals (:proposals m) :rejected (:rejected m)}))
        input {:items       (merge (into {} (for [e es] [(:id e) (cond-> {:authority? true} (:dates e) (assoc :dates (:dates e)))]))
                                   (into {} (for [[i m] (map-indexed vector ms)]
                                              [[:mention i] (cond-> {} (dates/dated? (:dates m)) (assoc :dates (:dates m)))])))
               :links       (vec (mapcat :links per-m))
               :cannot-link (vec (mapcat :cannot-link per-m))}
        run-c (fn [join] (let [res (ac/cluster (assoc input :join join))]
                           (assoc (score-clustering ms (:cluster-of res)) :violations (count (ac/violations input res)))))
        all   #{:accepted :propose :review}]
    {:mentions           (count ms)
     :links              (frequencies (map :basis (:links input)))
     :constrained        (run-c ac/default-join)
     :closure-review     (score-clustering ms (ac/transitive-closure (assoc input :join all)))
     :constrained-review (run-c all)}))

;; ---------------------------------------------------------------------------
;; Printing
;; ---------------------------------------------------------------------------

(defn- fmt [x] (if (nil? x) "n/a" (format "%.3f" (double x))))

(defn print-task-a [{:keys [all by-family rejections]}]
  (println "\nTask A — mention -> authority, reconcile/propose-agent-links (Jaccard >= 0.5)"
           (if rejections (str "with dates; rejected " (pr-str rejections)) "names only"))
  (println (format "%-8s %4s %4s %6s %6s %6s %5s %5s %5s %5s %5s %5s %5s"
                   "family" "ment" "pool" "R@1" "R@5" "P@1" "false" "homon" "other" "abst" "tied" "nip" "nip+"))
  (doseq [[f s] (concat by-family [["all" all]])]
    (println (format "%-8s %4d %4d %6s %6s %6s %5d %5d %5d %5d %5d %5d %5d"
                     f (:mentions s) (:in-pool s) (fmt (:recall-1 s)) (fmt (:recall-5 s)) (fmt (:precision-1 s))
                     (:false-links s) (:homonym s) (:other s) (:abstained s) (:tied-top1 s)
                     (:not-in-pool s) (:not-in-pool-proposed s)))))

(defn print-task-b [b]
  (println "\nTask B — mention <-> mention clustering," (:mentions b) "mentions with a $3")
  (println (format "%-14s %6s %6s %6s %6s %6s %6s %6s" "rule" "pairs" "right" "false" "P" "R" "F1" "B3 P/R"))
  (doseq [k rule-order
          :let [{p :pairwise b3 :b-cubed} (get b k)]]
    (println (format "%-14s %6d %6d %6d %6s %6s %6s %s/%s" (name k) (:predicted p) (:right p) (:false-merges p)
                     (fmt (:precision p)) (fmt (:recall p)) (fmt (:f1 p)) (fmt (:precision b3)) (fmt (:recall b3)))))
  (doseq [[k fq] (:mixed b)] (println "  same-name cluster" (pr-str k) "spans" fq)))

(defn print-task-b-constrained [b]
  (println "\nTask B (M5) — constrained clustering of the FS decisions," (:mentions b) "mentions; links"
           (pr-str (:links b)) "cannot-links" (:cannot-link b))
  (println (format "%-20s %6s %6s %6s %6s %6s %6s %6s %s" "rule" "pairs" "right" "false" "P" "R" "F1" "viol" "B3 P/R"))
  (doseq [k m5-rule-order
          :let [{p :pairwise b3 :b-cubed v :violations} (get b k)]]
    (println (format "%-20s %6d %6d %6d %6s %6s %6s %6s %s/%s" (name k) (:predicted p) (:right p) (:false-merges p)
                     (fmt (:precision p)) (fmt (:recall p)) (fmt (:f1 p)) (str v) (fmt (:precision b3)) (fmt (:recall b3))))))

(defn print-fs [{:keys [calibration task-a]}]
  (print-task-a task-a)
  (println "  top proposal for review:" (:review task-a) "; outright only:")
  (print-task-a (:outright task-a))
  (println "\nCalibration (held-out, leave-one-family-out): N" (:n calibration) "positives" (:positives calibration)
           "Brier" (format "%.4f" (:brier calibration)) "ECE" (format "%.4f" (:ece calibration))
           "meets bar" (:meets-bar? calibration))
  (doseq [{:keys [bin n mean-p observed]} (:bins calibration)]
    (println (format "  [%.1f, %.1f)  n %4d  mean p %.4f  observed %.4f" (first bin) (second bin) n mean-p observed))))

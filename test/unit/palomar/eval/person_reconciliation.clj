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
     sees is `$a, $m` — for a `100`, Palomar's own `:canon/agent`; for a `700`,
     which the classic INTERMARC (SRU) mapping does not carry to `:canon/agent`, the same
     recombination from the field. `$3` (the BnF authority the cataloguer
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

   The metrics are pure functions of plain maps, so they are tested on
   hand-made inputs."
  (:require [clojure.string :as str]
            [palomar.agent-cluster :as ac]
            [palomar.agent-dates :as dates]
            [palomar.agent-match :as fs]
            [palomar.plugins :as plug]
            [palomar.plugins.agents :as agents]
            [palomar.plugins.authorities :as authorities]
            [palomar.plugins.intermarc :as intermarc]
            [palomar.plugins.mapping :as mapping]
            [palomar.reconcile :as reconcile]
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

(defn- mentions-of [record]
  (for [tag ["100" "700"]
        f   (fields record tag)
        :let [sfs (get-in f [:locator :subfields])
              nm  (if (= "100" tag) (first (agents/creators record)) (heading sfs))]
        :when nm]
    {:record    (:id record)
     :tag       tag
     :occurrence (get-in f [:locator :occurrence])
     :name      nm
     :surname   (sub sfs "a")
     :family    (family (sub sfs "a"))
     :truth     (sub sfs "3")
     :dates     (dates/parse-life-dates (sub sfs "d"))
     :subfields sfs}))

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
    (fs/model {:m-counts m-counts :u-counts u-counts :prior prior
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
   (let [res (run-task-a c {:mention identity
                            :entries dated-entries
                            :candidates (fn [[m] es]
                                          (candidates [(select-keys m [:name :dates])] es {:model (model-of m)}))})
         ms  (mapv #(assoc % :decision (:decision (first (:proposals %)))) (:mentions res))
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
            :predictions (predictions ms)))))

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
   proposal is the truth (`:fs-top-1`), outright (`:fs-outright`)."
  [c model-of]
  (let [es (dated-entries (:pool c))
        rows (for [s (stress-set c)
                   :let [m   (select-keys s [:name :dates])
                         m3  (reconcile/agent-candidates [m] es)
                         r   (reconcile/agent-candidates [m] es {:model (model-of s)})
                         has (fn [cs] (boolean (some #(= (:truth s) (:authority-id %)) cs)))
                         top (first (:proposals r))]]
               {:kind         (:kind s)
                :m3-candidate (has (:proposals m3))
                :fs-candidate (has (concat (:proposals r) (:abstained r)))
                :fs-top-1     (= (:truth s) (:authority-id top))
                :fs-outright  (and (= (:truth s) (:authority-id top)) (= :propose (:decision top)))})
        sum  (fn [xs] (into {:n (count xs)} (for [k [:m3-candidate :fs-candidate :fs-top-1 :fs-outright]]
                                              [k (count (filter k xs))])))]
    (assoc (into (sorted-map) (for [[k xs] (group-by :kind rows)] [k (sum xs)]))
           :all (sum rows))))

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
   candidate rejected for its dates (M3) a cannot-link."
  [id {:keys [proposals rejected]}]
  {:links       (vec (for [p proposals]
                       {:a id :b (:authority-id p) :basis (if (= :propose (:decision p)) :propose :review)
                        :weight (:match-weight p)}))
   :cannot-link (vec (for [r rejected] {:a id :b (:authority-id r) :reason :incompatible-dates}))})

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

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

   The metrics are pure functions of plain maps, so they are tested on
   hand-made inputs."
  (:require [clojure.string :as str]
            [palomar.agent-dates :as dates]
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
                            :let [{ps :proposals rs :rejected} (candidates [(mention m)] es)]]
                        (merge m {:proposals ps :rejected (vec rs)} (link-outcome (:truth m) ps ids surnames))))]
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

(def clusterings
  "Task B's clusterings of a mention, by key. `:record-scoped` is what Palomar
   does today with a name-only agent (ADR 0041): its node is minted from the
   record and the name (`reconcile/align-agents`' local agent, the Linked Art
   actor), so two records' same names are never merged. `:same-name` is the
   simplest name rule, same `text/norm` name -> same person, which no Palomar
   code applies across records today."
  {:record-scoped (juxt :record :name)
   :same-name     (comp text/norm :name)})

(def rule-order [:record-scoped :same-name :top-1 :top-1-dated])

(defn task-b
  "Task B: the determined mentions (those with a `$3`) clustered by each of
   `clusterings`, and by `:top-1` — the clustering a curator would get by
   accepting every mention's top `propose-agent-links` proposal (pool in id
   order): mentions with one top authority are one person, a mention with no
   proposal is alone — and by `:top-1-dated`, the same with the date-aware
   matcher (lot M3: the mention's dates, the entries'). Each is scored
   pairwise and by B-cubed. `:mixed` lists, for the same-name rule, the
   clusters spanning several authorities."
  [{:keys [mentions pool]}]
  (let [ms      (filter :truth mentions)
        alone   (fn [m] [:alone (:record m) (:tag m) (:occurrence m)])
        entries (matcher-entries pool)
        dated   (dated-entries pool)
        top-1   (fn [m] (or (:authority-id (first (reconcile/propose-agent-links [(:name m)] entries))) (alone m)))
        top-1-d (fn [m] (or (:authority-id (first (reconcile/propose-agent-links [(select-keys m [:name :dates])] dated)))
                            (alone m)))
        rules   (assoc clusterings :top-1 top-1 :top-1-dated top-1-d)]
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

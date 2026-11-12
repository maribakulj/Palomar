(ns palomar.eval.person-reconciliation-external-post
  "Lot O5, after the run: descriptive figures of the external test
   (`docs/eval/person-reconciliation-external.md`, \"After the run\").

   Everything here is computed from `run.edn` and `benchmark.edn` only. No
   mention is scored again: a case is correct when the run recorded no error
   for it. The frozen namespace (`person-reconciliation-external`) is called
   for its Wilson interval and its decision rules, never changed.

   - `concentration`: the persons with the most linked mentions;
   - `leave-one-out`: the rules' margins when one person or one corpus is
     left out;
   - `bootstrap`: intervals by resampling persons (and no-match names), not
     mentions;
   - `postrun`: the engine code the run used, recorded after the run
     (`postrun.edn`): the freeze did not hold it."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [palomar.eval.person-reconciliation-external :as ext]
            [palomar.eval.person-reconciliation-hard :as hard])
  (:import (java.security MessageDigest)
           (java.util Random)))

(def postrun-file (str ext/fixture-dir "postrun.edn"))

(def engines [:a-jaccard :b-m2 :c-n3 :d-m2-g6])

(defn run-data [] (hard/read-edn ext/run-file))

(defn bench-data [] (hard/read-edn ext/benchmark-file))

;; ---------------------------------------------------------------------------
;; The cases, from the run's errors
;; ---------------------------------------------------------------------------

(defn cases
  "Each scored mention of benchmark `b`, with, per engine, whether the run
   recorded it correct (`:correct`, a linked mention with no error) or a
   false merge (`:merge`, an error whose outcome is a wrong or a false
   link). `[{:id :truth :key :corpus :correct {engine bool} :merge {engine
   bool}}]`."
  [b run]
  (let [errs (into {} (for [e engines] [e (into {} (map (juxt :id :outcome)) (get-in run [:errors e]))]))]
    (vec (for [m (:mentions b) :when (:split m)]
           {:id      (:id m)
            :truth   (:truth m)
            :key     (hard/split-key m)
            :corpus  (:corpus m)
            :correct (into {} (for [e engines] [e (boolean (and (:truth m) (not (contains? (errs e) (:id m)))))]))
            :merge   (into {} (for [e engines] [e (contains? #{:wrong-link :false-link} (get (errs e) (:id m)))]))}))))

(defn- ratio [n d] (when (pos? d) (/ (double n) d)))

(defn clamp [[lo hi]] [(max 0.0 lo) (min 1.0 hi)])

(defn figures
  "The figures of `cs` for engine `e`, as `ext/decisions` reads them; the
   Wilson bounds clamped to [0, 1] (the frozen code gives 1.0000000000000002
   for an upper bound of 1)."
  [cs e]
  (let [linked  (count (filter :truth cs))
        correct (count (filter #(get-in % [:correct e]) cs))
        merges  (count (filter #(get-in % [:merge e]) cs))
        w       (fn [k n] (some-> (ext/wilson k n) clamp))]
    {:linked           linked
     :persons          (count (distinct (keep :truth cs)))
     :correct          correct
     :false-merges     merges
     :recall           (ratio correct linked)
     :precision        (ratio correct (+ correct merges))
     :precision-wilson (w correct (+ correct merges))
     :recall-wilson    (w correct linked)}))

(defn all-figures [cs] (into (sorted-map) (for [e engines] [e (figures cs e)])))

(defn decisions [cs] (ext/decisions (all-figures cs)))

(defn margins
  "How far `cs` is from flipping rules 3 and 4, in recall: rule 3, recall
   (c) − recall (b) − 0.02; rule 4, recall (c) − recall (a) + 0.02. The
   false-merge conditions are given apart."
  [cs]
  (let [f (all-figures cs) r #(get-in f [% :recall]) fm #(get-in f [% :false-merges])]
    {:rule-3 (- (r :c-n3) (r :b-m2) 0.02)
     :rule-3-merges [(fm :c-n3) (fm :b-m2)]
     :rule-3-wilson-lower (first (get-in f [:c-n3 :precision-wilson]))
     :rule-4 (+ (- (r :c-n3) (r :a-jaccard)) 0.02)
     :rule-4-merges [(fm :c-n3) (fm :a-jaccard)]}))

;; ---------------------------------------------------------------------------
;; Concentration
;; ---------------------------------------------------------------------------

(defn concentration
  "The `n` persons with the most linked mentions: `{:top [{:id :label :n
   :share}] :together share :linked n}`."
  [b cs n]
  (let [labels (into {} (map (juxt :id :label)) (:pool b))
        linked (filter :truth cs)
        top    (take n (sort-by (juxt (comp - val) key) (frequencies (map :truth linked))))]
    {:linked   (count linked)
     :top      (vec (for [[id k] top] {:id id :label (labels id) :n k :share (ratio k (count linked))}))
     :together (ratio (reduce + (map val top)) (count linked))}))

(defn macro-recall
  "Recall averaged per person, for each engine."
  [cs]
  (let [per (vals (group-by :truth (filter :truth cs)))]
    (into (sorted-map)
          (for [e engines]
            [e (/ (reduce + (map #(ratio (count (filter (fn [c] (get-in c [:correct e])) %)) (count %)) per))
                  (count per))]))))

(defn leave-one-out
  "The margins and decisions with each person (`:person`, by truth) or each
   corpus (`:corpus`) left out: `{:person {:min-rule-3 [margin id] :min-rule-4
   [margin id] :flips-3 [id] :flips-4 [id]} :corpus {..}}`. A flip is a
   decision that differs from the whole test's."
  [cs]
  (let [whole (decisions cs)
        side  (fn [ks drop-k]
                (let [rows (for [k ks
                                 :let [sub (remove #(= k (drop-k %)) cs)]]
                             {:k k :m (margins sub) :d (decisions sub)})]
                  {:min-rule-3 ((juxt (comp :rule-3 :m) :k) (apply min-key (comp :rule-3 :m) rows))
                   :min-rule-4 ((juxt (comp :rule-4 :m) :k) (apply min-key (comp :rule-4 :m) rows))
                   :flips-3    (vec (sort (keep #(when (not= (:n3-against-m2 whole) (get-in % [:d :n3-against-m2])) (:k %)) rows)))
                   :flips-4    (vec (sort (keep #(when (not= (get-in whole [:fs-against-jaccard :c-n3])
                                                             (get-in % [:d :fs-against-jaccard :c-n3]))
                                                   (:k %))
                                                rows)))}))]
    {:person (side (distinct (keep :truth cs)) :truth)
     :corpus (side (sort (distinct (map :corpus cs))) :corpus)}))

;; ---------------------------------------------------------------------------
;; The person-cluster bootstrap
;; ---------------------------------------------------------------------------

(def bootstrap-seed 20261112)

(def bootstrap-replicates 2000)

(defn- quantiles [xs]
  (let [v (vec (sort xs)) n (count v)]
    [(v (int (Math/floor (* 0.025 n)))) (v (dec (int (Math/ceil (* 0.975 n)))))]))

(defn bootstrap
  "Percentile 95 % intervals, resampling the clusters of `cs` (a person, by
   truth, or a no-match name: `hard/split-key`) with replacement,
   `bootstrap-replicates` times from `bootstrap-seed`: recall and
   precision per engine, and the recall differences (c) − (b) and (c) − (a).
   A replicate where a figure is undefined is left out of that figure."
  [cs]
  (let [clusters (vec (vals (group-by :key cs)))
        k        (count clusters)
        rng      (Random. bootstrap-seed)
        reps     (vec (for [_ (range bootstrap-replicates)]
                        (let [sample (mapcat (fn [_] (clusters (.nextInt rng k))) (range k))]
                          (all-figures sample))))
        q        (fn [f] (quantiles (keep f reps)))]
    {:recall    (into (sorted-map) (for [e engines] [e (q #(get-in % [e :recall]))]))
     :precision (into (sorted-map) (for [e engines] [e (q #(get-in % [e :precision]))]))
     :c-minus-b (q #(- (get-in % [:c-n3 :recall]) (get-in % [:b-m2 :recall])))
     :c-minus-a (q #(- (get-in % [:c-n3 :recall]) (get-in % [:a-jaccard :recall])))
     :clusters  k}))

;; ---------------------------------------------------------------------------
;; The hand labels that read another record
;; ---------------------------------------------------------------------------

(def other-record-labels
  "The hand labels whose note cites a record other than the mention's own
   (Pons ×2, Van Hamme ×2): beyond the stated rule."
  #{["cb32551695z" "a" 0 1] ["cb325516938" "a" 0 1] ["cb35425464q" "g" 0 0] ["cb354254652" "g" 0 0]})

(defn without-other-record-labels [cs] (vec (remove #(other-record-labels (:id %)) cs)))

;; ---------------------------------------------------------------------------
;; What the run used, recorded after it
;; ---------------------------------------------------------------------------

(def base-commit "fcc678b14dd81d5e3b7b69afc57f2da7fa123139")

(def code-files
  "The namespaces the run called besides the frozen one."
  ["test/unit/palomar/eval/person_reconciliation_hard.clj"
   "test/unit/palomar/eval/person_reconciliation_hard_n4.clj"
   "test/unit/palomar/eval/person_reconciliation_hard_o4.clj"
   "test/unit/palomar/eval/person_reconciliation.clj"])

(defn- bytes-sha [^bytes bs]
  (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest (MessageDigest/getInstance "SHA-256") bs))))

(defn file-sha [path] (bytes-sha (java.nio.file.Files/readAllBytes (.toPath (io/file path)))))

(defn tree-sha
  "The SHA-256 of a directory: of the lines `path sha256`, one per file,
   sorted by path."
  [dir]
  (let [root (io/file dir)
        fs   (sort-by str (filter #(.isFile ^java.io.File %) (file-seq root)))]
    (hard/sha-256 (str/join (for [f fs] (str (str/replace (str f) "\\" "/") " " (file-sha (str f)) "\n"))))))

(defn postrun-state []
  {:src   (tree-sha "src")
   :files (into (sorted-map) (for [f code-files] [f (file-sha f)]))})

(defn write-postrun!
  "Writes `postrun.edn` once: the base commit and the SHA-256 of `src/` and
   of the namespaces the run called."
  []
  (when (.exists (io/file postrun-file))
    (throw (ex-info "postrun.edn exists" {:file postrun-file})))
  (spit postrun-file
        (pr-str (assoc (postrun-state)
                       :base-commit base-commit
                       :recorded-at (str (java.time.Instant/now))
                       :note "Recorded after the run (lot O5 review): the freeze should have held it. The worktree had no change to src/ or to these files against the base commit when the run was made."))))

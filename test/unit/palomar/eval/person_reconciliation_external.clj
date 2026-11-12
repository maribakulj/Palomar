(ns palomar.eval.person-reconciliation-external
  "Lot O5: an external test of person reconciliation on transcribed
   mentions (`docs/eval/person-reconciliation-external.md`), pre-registered
   in `protocol.edn`.

   N3's test split is spent. This namespace builds a new one from BnF
   corpora fetched after the protocol was frozen (`freeze.edn`), with N3's
   construction rules called from the frozen namespace
   (`palomar.eval.person-reconciliation-hard`), and scores it once
   (`run-once!`) with four frozen engines: N3's Jaccard, the M2 model as
   shipped, the N3 model as shipped, and the M2 model with a gap of 6 bits.

   Order of work, each step recorded: the protocol and this namespace are
   hashed (`freeze.edn`); the corpora are fetched (`fetch-log.edn`); the
   benchmark is built and hand-labelled by N3's rule; the labels and the
   benchmark are hashed (`labels-freeze.edn`); the single run writes
   `run.edn`. No test asserts a result."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [palomar.eval.person-reconciliation :as pr]
            [palomar.eval.person-reconciliation-hard :as hard]
            [palomar.eval.person-reconciliation-hard-n4 :as n4]
            [palomar.eval.person-reconciliation-hard-o4 :as o4]
            [palomar.plugins.intermarc :as intermarc]
            [palomar.reconcile :as reconcile]
            [palomar.reconciliation :as rc])
  (:import (java.time Instant)))

;; ---------------------------------------------------------------------------
;; Files
;; ---------------------------------------------------------------------------

(def fixture-dir "test/fixtures/er-gold/bnf-external/")

(def source-file "test/unit/palomar/eval/person_reconciliation_external.clj")

(def protocol-file (str fixture-dir "protocol.edn"))
(def freeze-file (str fixture-dir "freeze.edn"))
(def fetch-log-file (str fixture-dir "fetch-log.edn"))
(def records-dir (str fixture-dir "records/"))
(def authority-file (str fixture-dir "authorities.xml"))
(def pseudonym-file (str fixture-dir "pseudonyms.xml"))
(def hand-labels-file (str fixture-dir "hand-labels.edn"))
(def benchmark-file (str fixture-dir "benchmark.edn"))
(def splits-file (str fixture-dir "splits.edn"))
(def labels-freeze-file (str fixture-dir "labels-freeze.edn"))
(def run-file (str fixture-dir "run.edn"))

(def m2-record-files
  "M2's record fixtures: a record in one of them is dropped."
  (mapv #(str "test/fixtures/documentary/intermarc/sru/intermarcXchange/bnf-sru-" % "-50.xml")
        ["victor-hugo" "jules-verne" "alexandre-dumas"]))

(def doc-file "docs/eval/person-reconciliation-external.md")

(defn protocol [] (hard/read-edn protocol-file))

(defn protocol-section
  "The text of the doc's \"Protocol\" section: from its heading to the
   next second-level heading."
  []
  (let [s (slurp doc-file)
        i (str/index-of s "\n## Protocol\n")
        j (when i (str/index-of s "\n## " (inc i)))]
    (when i (subs s (inc i) (or j (count s))))))

(defn file-sha [path] (hard/sha-256 (slurp path)))

(defn- now [] (str (Instant/now)))

;; ---------------------------------------------------------------------------
;; The freeze
;; ---------------------------------------------------------------------------

(defn freeze!
  "Writes `freeze.edn`: the SHA-256 of the protocol, of this namespace
   and of the doc's \"Protocol\" section, and the time. Run once, before any corpus is fetched."
  [note]
  (when (.exists (io/file freeze-file))
    (throw (ex-info "the O5 protocol is already frozen" {:file freeze-file})))
  (spit freeze-file
        (pr-str {:protocol {:file protocol-file :sha256 (file-sha protocol-file)}
                 :code     {:file source-file :sha256 (file-sha source-file)}
                 :doc      {:file doc-file :section "Protocol" :sha256 (hard/sha-256 (protocol-section))}
                 :frozen-at (now)
                 :note note})))

;; ---------------------------------------------------------------------------
;; The corpora
;; ---------------------------------------------------------------------------

(defn surname-of [query] (peek (str/split query #" ")))

(defn overlaps
  "The candidates of protocol `p` that are an excluded query, or share an
   excluded query's surname (its last word)."
  [p]
  (let [ex  (mapcat val (:excluded-queries p))
        sur (set (map surname-of ex))]
    (vec (filter #(or ((set ex) (:query %)) (sur (surname-of (:query %)))) (:candidates p)))))

(defn selection-order
  "The candidates' queries in the order the selection rule fetches them:
   by the SHA-256 of the salt and the query, ascending."
  [p]
  (mapv second (sort (for [{q :query} (:candidates p)]
                       [(hard/sha-256 (str (get-in p [:selection :salt]) q)) q]))))

(defn selected
  "The queries kept, given `counts` (`{query numberOfRecords}` for those
   fetched): in selection order, those with at least `:min-records`, until
   `:n` are kept."
  [p counts]
  (let [{:keys [n min-records]} (:selection p)]
    (loop [[q & more] (selection-order p) kept []]
      (cond (or (nil? q) (= n (count kept))) kept
            (nil? (counts q))               kept
            (<= min-records (counts q))     (recur more (conj kept q))
            :else                           (recur more kept)))))

(defn query-string [p q] (str/replace (get-in p [:sru :query-form]) "<query>" q))

(defn record-file-name [q] (str "bnf-sru-" (str/replace q " " "-") "-100.xml"))

(defn- ark-ids [path] (set (map second (re-seq #"id=\"ark:/12148/(cb\w+)\"" (slurp path)))))

(defn known-record-ids
  "The record ids of N3's and M2's record fixtures."
  []
  (let [dir (fn [d] (for [f (sort (.list (io/file hard/fixture-dir d))) :when (str/ends-with? f ".xml")]
                      (str hard/fixture-dir d "/" f)))]
    (into #{} (mapcat ark-ids) (concat (dir (:design hard/parts)) (dir (:test hard/parts)) m2-record-files))))

;; ---------------------------------------------------------------------------
;; The construction: hard/build's steps, from the frozen namespace
;; ---------------------------------------------------------------------------

(def ^:private labelled @#'hard/labelled)
(def ^:private pool-of @#'hard/pool-of)
(def ^:private own-record-entries @#'hard/own-record-entries)
(def ^:private assign-splits @#'hard/assign-splits)
(def ^:private corpus-of @#'hard/corpus-of)
(def ^:private authority-index @#'hard/authority-index)

(defn- index-of [authorities pseudonyms]
  (with-redefs [hard/authority-file authorities hard/pseudonym-file pseudonyms]
    (authority-index)))

(defn- merged [base p]
  (let [ids (set (map :id base))]
    (vec (sort-by :id (concat base (remove (comp ids :id) p))))))

(defn build-from
  "`hard/build`'s steps, in its order, each the frozen namespace's own
   function, on given inputs: `files` `[[part path] ..]` (INTERMARC SRU
   pages), the `authorities` and `pseudonyms` files, the `hand` labels, a
   `base-pool` merged into the pool (`merged`: its entries kept on a shared
   id), `seen` scored mentions passed to `assign-splits` as the design part
   (a mention with one of their keys is set aside `:seen-in-design`), and
   `drop-ids`, record ids dropped before labelling. With N3's inputs and no
   base pool, seen mentions or dropped ids, it is `hard/build`."
  [{:keys [files authorities pseudonyms hand base-pool seen drop-ids]}]
  (let [index  (index-of authorities pseudonyms)
        drop?  (set drop-ids)
        raw    (for [[part path] files
                     r (intermarc/ingest (slurp path) {})
                     :when (not (drop? (name (:id r))))]
                 (assoc r ::hard/corpus (corpus-of (.getName (io/file path))) ::hard/part part))
        recs   (mapv #(labelled % index) raw)
        pool0  (merged base-pool (pool-of recs (:served index) {}))
        ms     (hard/resolve-hand (mapcat :mentions recs) pool0 hand)
        linked (reduce (fn [acc m] (update acc (:truth m) (fnil conj #{}) (:record m))) {} (filter :truth ms))
        pool   (merged base-pool (pool-of recs (:served index) linked))
        seen*  (mapv #(assoc % :part :design ::seen true) seen)
        ms     (->> (-> ms (own-record-entries pool) hard/dedupe-mentions)
                    (concat seen*) assign-splits (remove ::seen))
        by-id  (into {} (map (juxt :id identity)) pool)]
    {:pool     pool
     :records  (into (sorted-map)
                     (for [r recs :when (seq (:mentions r))]
                       [(:record r) {:corpus   (:corpus r)
                                     :part     (:part r)
                                     :headings (mapv #(select-keys % [:tag :name :id :roles]) (concat (:persons r) (:groups r)))}]))
     :mentions (mapv #(cond-> % (:split %) (assoc :stratum (hard/stratum % pool) :token-exact
                                                  (boolean (some->> (:truth %) (get by-id) (hard/token-exact? (:text %))))))
                     ms)}))

(defn n3-inputs
  "N3's own inputs, for `build-from`: it then rebuilds N3's benchmark."
  []
  {:files       (for [[part dir] [[:design (:design hard/parts)] [:test (:test hard/parts)]]
                      f (sort (.list (io/file hard/fixture-dir dir)))
                      :when (str/ends-with? f ".xml")]
                  [part (str hard/fixture-dir dir "/" f)])
   :authorities hard/authority-file
   :pseudonyms  hard/pseudonym-file
   :hand        (:labels (hard/read-edn hard/hand-labels-file))})

(defn record-files []
  (let [d (io/file records-dir)]
    (if (.isDirectory d) (vec (sort (filter #(str/ends-with? % ".xml") (.list d)))) [])))

(defn inputs
  "The external test's inputs: its record pages (part `:test`), its
   authorities and pseudonyms, its hand labels, N3's pool as the base pool,
   N3's scored mentions as seen, and N3's and M2's record ids dropped."
  []
  (let [n3 (hard/read-edn hard/benchmark-file)]
    {:files       (mapv #(vector :test (str records-dir %)) (record-files))
     :authorities authority-file
     :pseudonyms  pseudonym-file
     :hand        (if (.exists (io/file hand-labels-file)) (:labels (hard/read-edn hand-labels-file)) {})
     :base-pool   (:pool n3)
     :seen        (filterv :split (:mentions n3))
     :drop-ids    (known-record-ids)}))

(defn build [] (build-from (inputs)))

(defn dropped-records
  "The ids of the external pages' records that N3 or M2 already hold."
  []
  (let [known (known-record-ids)]
    (vec (sort (filter known (mapcat #(ark-ids (str records-dir %)) (record-files)))))))

(defn- lines [open xs close] (str open (str/join "\n  " (map pr-str xs)) close))

(defn write!
  "Writes `benchmark.edn` and `splits.edn` from the fixtures (run by hand;
   a test checks they are what `build` gives)."
  []
  (let [b (build)]
    (spit benchmark-file
          (str ";; The external test of the hard person benchmark (lot O5), built by\n"
               ";; palomar.eval.person-reconciliation-external/write! from the fixtures of this directory.\n"
               ";; Source : Bibliotheque nationale de France. Licence Ouverte / Etalab 2.0.\n"
               "{:pool\n " (lines "[" (:pool b) "]")
               "\n :records\n " (lines "{" (mapcat identity (:records b)) "}")
               "\n :mentions\n " (lines "[" (:mentions b) "]") "}\n"))
    (spit splits-file (pr-str (hard/split-file-content b)))))

(defn load-benchmark [] (hard/load-benchmark benchmark-file splits-file))

(defn to-label
  "The mentions of `b` the hand rule leaves for a hand: residual candidates
   unverified and no-match cases whose surname the pool holds."
  [b]
  (filterv #(#{:unverified :surname-in-pool} (:reason %)) (:mentions b)))

;; ---------------------------------------------------------------------------
;; The labels freeze
;; ---------------------------------------------------------------------------

(defn labels-state
  "The SHA-256 of the hand labels, the benchmark and the split file."
  []
  {:hand-labels (file-sha hand-labels-file)
   :benchmark   (file-sha benchmark-file)
   :splits      (file-sha splits-file)})

(defn freeze-labels!
  "Writes `labels-freeze.edn`: the hand labels', the benchmark's and the
   split file's SHA-256, and the time. Refused once the run exists."
  [note]
  (when (.exists (io/file run-file))
    (throw (ex-info "the run exists: the labels can no longer be frozen" {:file run-file})))
  (spit labels-freeze-file (pr-str (assoc (labels-state) :frozen-at (now) :note note))))

;; ---------------------------------------------------------------------------
;; The engines
;; ---------------------------------------------------------------------------

(defn- resource-params [path] (hard/read-edn (io/resource path)))

(defn engine-models
  "The Fellegi-Sunter models of the protocol, `{id {:model :version
   :file-sha256}}`: the shipped files read by `reconciliation/read-model`;
   (d) the M2 file's parameters with the gap, read by the same checks."
  [p]
  (into (array-map)
        (for [{:keys [id model thresholds]} (:engines p)
              :when model
              :let [shipped (rc/read-model model)
                    m       (if (= thresholds (get-in shipped [:model :thresholds]))
                              (:model shipped)
                              (#'rc/model-of (assoc (resource-params model) :thresholds thresholds)))]]
          [id {:model m :version (rc/model-version m) :file-sha256 (file-sha (io/resource model))}])))

(defn check-versions!
  "Refuses unless every model's file SHA-256, model SHA-256 and weights
   are the protocol's."
  [p models]
  (doseq [{:keys [id model] :as e} (:engines p) :when model
          :let [got (get models id)]]
    (when-not (and (= (:file-sha256 e) (:file-sha256 got))
                   (= (:sha256 e) (get-in got [:version :sha256]))
                   (= (:weights e) (get-in got [:version :weights])))
      (throw (ex-info "a model is not the one the protocol froze" {:engine id :expected e :got (dissoc got :model)})))))

(defn fs-row
  "One mention `m` scored by `model` through `reconcile/agent-candidates`
   on its name in heading order (`hard/inverted`). A tied top is no link
   and no review."
  [model entries m]
  (let [r     (reconcile/agent-candidates [{:name (hard/inverted (:text m)) :dates nil}] entries {:model model})
        ps    (:proposals r)
        top   (first ps)
        tied? (boolean (and (second ps) (= (:match-weight top) (:match-weight (second ps)))))
        ids   (fn [cs] (mapv (fn [c] {:id (:authority-id c)}) cs))]
    {:mention      m
     :result       {:link    (when (and (not tied?) (= :propose (:decision top))) top)
                    :review? (and (not tied?) (= :review (:decision top)))
                    :tied?   tied?}
     :cands        (ids (concat ps (:abstained r)))
     :review-cands (ids ps)}))

(defn rows
  "Every engine's rows on the mentions `ms` against `pool`: `{id rows}`."
  [models ms pool]
  (let [entries (pr/dated-entries pool)]
    (into (array-map :a-jaccard (vec (o4/jaccard-rows ms pool)))
          (for [[id {:keys [model]}] models]
            [id (mapv #(fs-row model entries %) ms)]))))

;; ---------------------------------------------------------------------------
;; The figures
;; ---------------------------------------------------------------------------

(def z 1.96)

(defn wilson
  "The Wilson score interval of `k` successes in `n` at `z`: `[lower
   upper]`, nil when `n` is 0."
  [k n]
  (when (pos? n)
    (let [p (/ (double k) n) z2 (* z z)
          c (/ (+ p (/ z2 (* 2 n))) (+ 1 (/ z2 n)))
          h (/ (* z (Math/sqrt (+ (/ (* p (- 1 p)) n) (/ z2 (* 4 n n))))) (+ 1 (/ z2 n)))]
      [(- c h) (+ c h)])))

(defn- num* [x] (if (ratio? x) (double x) x))

(defn figures
  "O4's figures of `rows` (`o4/figures`), as doubles, with the ties, the
   persons, and the Wilson intervals of precision and recall."
  [rows]
  (let [f     (o4/figures rows)
        links (+ (:correct f) (:false-merges f))]
    (-> (into (sorted-map) (for [[k v] f] [k (num* v)]))
        (assoc :ties (count (filter (comp :tied? :result) rows))
               :persons (count (distinct (keep (comp :truth :mention) rows)))
               :precision-wilson (wilson (:correct f) links)
               :recall-wilson (wilson (:correct f) (:linked f))))))

(defn by-stratum
  "The `figures` of `rows` overall (`:all`) and per stratum."
  [rows]
  (into (sorted-map :all (figures rows))
        (for [[s xs] (group-by (comp :stratum :mention) rows)] [s (figures xs)])))

(defn errors
  "The cases of `rows` that are not a correct link nor a right abstention."
  [rows]
  (vec (for [{:keys [mention result]} rows
             :let [o (hard/outcome mention result :abstain)]
             :when (or (not (#{:correct :abstained} o)) (:review? result))]
         (cond-> {:id (:id mention) :text (:text mention) :truth (:truth mention) :stratum (:stratum mention)
                  :predicted (get-in result [:link :authority-id]) :outcome o}
           (:review? result) (assoc :review? true)))))

;; ---------------------------------------------------------------------------
;; The decision rules
;; ---------------------------------------------------------------------------

(defn decisions
  "The protocol's decision rules applied to the overall figures `all`
   (`{engine figures}`)."
  [all]
  (let [{:keys [linked persons]} (:a-jaccard all)
        fm  (fn [e] (get-in all [e :false-merges]))
        rec (fn [e] (or (get-in all [e :recall]) 0.0))
        low (fn [e] (or (first (get-in all [e :precision-wilson])) 0.0))
        eps 1e-9]
    (if (or (< linked 100) (< persons 20))
      {:power :insufficient :gap-for-m2 :inconclusive :n3-against-m2 :inconclusive
       :fs-against-jaccard {:b-m2 :inconclusive :c-n3 :inconclusive :d-m2-g6 :inconclusive}}
      {:power :sufficient
       :gap-for-m2 (if (and (<= (fm :d-m2-g6) (fm :b-m2)) (>= (+ (rec :d-m2-g6) eps) (+ (rec :b-m2) 0.02)))
                     :adopt-gap-6 :keep-no-gap)
       :n3-against-m2 (cond (and (<= (fm :c-n3) (fm :b-m2)) (>= (+ (rec :c-n3) eps) (+ (rec :b-m2) 0.02))
                                 (>= (low :c-n3) 0.95))
                            :n3-better
                            (or (> (fm :c-n3) (fm :b-m2)) (< (+ (rec :c-n3) eps) (- (rec :b-m2) 0.02)))
                            :n3-worse
                            :else :no-difference)
       :fs-against-jaccard (into (sorted-map)
                                 (for [e [:b-m2 :c-n3 :d-m2-g6]]
                                   [e (if (and (<= (fm e) (fm :a-jaccard)) (>= (+ (rec e) eps) (- (rec :a-jaccard) 0.02)))
                                        :matches-jaccard :jaccard-more-complete)]))})))

;; ---------------------------------------------------------------------------
;; The single run
;; ---------------------------------------------------------------------------

(defn check-frozen!
  "Refuses unless the protocol and this namespace are the frozen ones,
   and the labels, the benchmark and the split file are those
   `labels-freeze.edn` recorded."
  []
  (let [f  (hard/read-edn freeze-file)
        lf (when (.exists (io/file labels-freeze-file)) (hard/read-edn labels-freeze-file))]
    (when-not (= (get-in f [:protocol :sha256]) (file-sha protocol-file))
      (throw (ex-info "the protocol is not the frozen one" {})))
    (when-not (= (get-in f [:code :sha256]) (file-sha source-file))
      (throw (ex-info "this namespace is not the frozen one" {})))
    (when-not (and lf (= (select-keys lf [:hand-labels :benchmark :splits]) (labels-state)))
      (throw (ex-info "the labels, the benchmark or the split file are not the frozen ones" {})))
    lf))

(defn score
  "Every engine on the scored mentions of `b`: `{:results {engine
   by-stratum} :errors {engine [case]} :decisions}`. Each model is refused
   on what it was fitted on."
  [p b]
  (let [models (engine-models p)
        ms     (filterv :split (:mentions b))
        _      (check-versions! p models)
        _      (doseq [[_ {:keys [model]}] models] (n4/refuse-in-sample! model (pr/dated-entries (:pool b)) ms))
        rs     (rows models ms (:pool b))
        res    (into (array-map) (for [[k r] rs] [k (by-stratum r)]))]
    {:versions  (into (sorted-map) (for [[k v] models] [k (assoc (:version v) :file-sha256 (:file-sha256 v))]))
     :results   res
     :errors    (into (sorted-map) (for [[k r] rs] [k (errors r)]))
     :decisions (decisions (into {} (for [[k r] res] [k (:all r)])))}))

(defn run-once!
  "The single run: refused when `run-file` exists; else checks the freeze,
   scores the test once, and writes `run-file` with the start time, the
   labels' SHA-256 and the results."
  ([] (run-once! run-file))
  ([path]
   (when (.exists (io/file path))
     (throw (ex-info "the O5 external test was run once: it is spent" {:file path})))
   (let [lf      (check-frozen!)
         started (now)
         out     (score (protocol) (load-benchmark))]
     (spit path (pr-str (assoc out :started-at started :finished-at (now)
                               :labels (select-keys lf [:hand-labels :benchmark :splits]))))
     out)))

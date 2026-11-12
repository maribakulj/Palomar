(ns palomar.eval.person-reconciliation-external-test
  "Lot O5, the external test (`docs/eval/person-reconciliation-external.md`):
   the protocol frozen before the fetch, corpora with no overlap with N3 or
   M2, the construction by N3's frozen namespace, the labels frozen before
   the single run, and the refusal of a second run. Only counts are pinned:
   no test asserts a result of the run.

   After the run (the O5 review): the raw response headers against the
   fetch log, the raw responses' SHA-256 against the fixtures, and the
   guards of the run path on synthetic cases (tampered copies, a synthetic
   models map, a synthetic pool, synthetic records). This file was written
   after the run, as were the README and the results; no test here asserts
   a result."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.eval.person-reconciliation :as pr]
            [palomar.eval.person-reconciliation-external :as ext]
            [palomar.eval.person-reconciliation-hard :as hard]
            [palomar.plugins.intermarc :as intermarc]
            [palomar.reconcile :as reconcile])
  (:import (java.time Instant ZonedDateTime)
           (java.time.format DateTimeFormatter)))

(def ^:private bench (delay (ext/load-benchmark)))

(defn- instant [s] (Instant/parse s))

(defn- http-date [s] (.toInstant (ZonedDateTime/parse s DateTimeFormatter/RFC_1123_DATE_TIME)))

;; ---------------------------------------------------------------------------
;; The freeze
;; ---------------------------------------------------------------------------

(deftest the-protocol-and-its-code-are-the-frozen-ones
  (let [f (hard/read-edn ext/freeze-file)]
    (is (= (get-in f [:protocol :sha256]) (ext/file-sha ext/protocol-file)))
    (is (= (get-in f [:code :sha256]) (ext/file-sha ext/source-file)))
    (is (= (get-in f [:doc :sha256]) (hard/sha-256 (ext/protocol-section))))))

(deftest the-freeze-predates-the-fetch
  (let [frozen (instant (:frozen-at (hard/read-edn ext/freeze-file)))
        log    (hard/read-edn ext/fetch-log-file)
        dates  (map (comp http-date :http-date) (concat (:queries log) (:authorities log) (:pseudonyms log)))]
    (is (seq dates))
    (testing "every SRU response is dated by the server after the freeze"
      (is (every? #(.isBefore frozen %) dates)))))

;; ---------------------------------------------------------------------------
;; The corpora
;; ---------------------------------------------------------------------------

(deftest the-corpora-have-no-overlap-with-n3-or-m2
  (let [p (ext/protocol)]
    (testing "no candidate is an N3 or M2 query, or shares its surname"
      (is (= [] (ext/overlaps p)))
      (is (= 13 (count (get-in p [:excluded-queries :n3]))))
      (is (= 3 (count (get-in p [:excluded-queries :m2])))))
    (testing "a synthetic candidate that shares a surname is caught"
      (is (= [{:query "maurice simenon"}]
             (ext/overlaps (assoc p :candidates [{:query "maurice simenon"} {:query "nobody"}])))))
    (testing "no record of N3 or M2 is scored: each is dropped before labelling"
      (let [known (ext/known-record-ids)]
        (is (not-any? known (keys (:records @bench))))))))

(deftest the-corpora-are-those-the-selection-rule-gives
  (let [p      (ext/protocol)
        log    (hard/read-edn ext/fetch-log-file)
        counts (into {} (map (juxt :query :number-of-records)) (:queries log))]
    (is (= (ext/selected p counts) (mapv :query (filter :kept? (:queries log)))))
    (is (= (sort (map ext/record-file-name (ext/selected p counts))) (ext/record-files)))
    (testing "queries were fetched in selection order, none beyond the last kept"
      (is (= (map :query (:queries log)) (take (count (:queries log)) (ext/selection-order p)))))
    (testing "the rule skips a query with fewer than 100 records"
      (let [p' (assoc p :candidates [{:query "a"} {:query "b"} {:query "c"} {:query "d"}] :selection {:salt "" :n 2 :min-records 100})
            [q1 q2 q3] (ext/selection-order p')]
        (is (= [q2 q3] (ext/selected p' {q1 99 q2 100 q3 300})))
        (is (= [q2] (ext/selected p' {q1 99 q2 100})) "a query not fetched ends the selection")))))

;; ---------------------------------------------------------------------------
;; The construction
;; ---------------------------------------------------------------------------

(deftest the-construction-is-n3-s-frozen-namespace-unchanged
  (testing "the N3 namespace is the one its freeze and the protocol name"
    (let [sha (hard/sha-256 (slurp hard/source-file))]
      (is (= (:sha256 (hard/read-edn hard/freeze-file)) sha))
      (is (= (get-in (ext/protocol) [:construction :frozen-namespace :sha256]) sha))))
  (testing "the composition, on N3's own inputs, rebuilds N3's committed benchmark"
    (is (= (hard/read-edn hard/benchmark-file) (ext/build-from (ext/n3-inputs))))))

(deftest the-benchmark-is-rebuilt-identically-from-the-fixtures
  (let [built (ext/build)]
    (is (= (hard/read-edn ext/benchmark-file) built))
    (is (= (hard/split-file-content built) (hard/read-edn ext/splits-file)))))

(deftest the-pool-is-n3-s-plus-the-new-authorities
  (let [n3   (:pool (hard/read-edn hard/benchmark-file))
        pool (:pool (hard/read-edn ext/benchmark-file))
        ids  (set (map :id pool))]
    (is (every? (set pool) n3) "every N3 entry, unchanged")
    (is (every? ids (map :id n3)))
    (is (= (sort (map :id pool)) (map :id pool)))))

(deftest no-person-or-name-of-n3-is-scored
  (let [n3-keys (set (map hard/split-key (filter :split (:mentions (hard/read-edn hard/benchmark-file)))))
        scored  (filter :split (:mentions @bench))]
    (is (seq scored))
    (is (every? #(= :test (:split %)) scored))
    (is (not-any? n3-keys (map hard/split-key scored)))))

;; ---------------------------------------------------------------------------
;; The labels, before the run
;; ---------------------------------------------------------------------------

(deftest every-hand-label-is-used-and-nothing-is-left-unsettled
  (let [labels (:labels (hard/read-edn ext/hand-labels-file))
        by-id  (into {} (map (juxt :id identity)) (:mentions @bench))]
    (is (= [] (ext/to-label @bench)))
    (is (every? (fn [[id l]] (= (:text l) (:text (by-id id)))) labels))))

(deftest the-labels-were-frozen-before-the-run
  (let [lf  (hard/read-edn ext/labels-freeze-file)
        run (hard/read-edn ext/run-file)]
    (testing "the files are those the labels freeze recorded"
      (is (= (select-keys lf [:hand-labels :benchmark :splits]) (ext/labels-state))))
    (testing "the run read those very files, after the labels freeze"
      (is (= (select-keys lf [:hand-labels :benchmark :splits]) (:labels run)))
      (is (.isBefore (instant (:frozen-at lf)) (instant (:started-at run)))))
    (testing "the labels freeze is after the protocol freeze"
      (is (.isBefore (instant (:frozen-at (hard/read-edn ext/freeze-file))) (instant (:frozen-at lf)))))))

;; ---------------------------------------------------------------------------
;; A single run
;; ---------------------------------------------------------------------------

(deftest a-second-run-is-refused
  (is (.exists (io/file ext/run-file)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"run once" (ext/run-once!)))
  (let [f (java.io.File/createTempFile "o5-run" ".edn")]
    (try
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"run once" (ext/run-once! (.getPath f))))
      (finally (.delete f))))
  (testing "the labels can no longer be frozen"
    (is (thrown? clojure.lang.ExceptionInfo (ext/freeze-labels! "again")))))

;; ---------------------------------------------------------------------------
;; After the run: the raw evidence of the fetch
;; ---------------------------------------------------------------------------

(def ^:private headers-dir (str ext/fixture-dir "raw-headers/"))

(defn- header-date [path]
  (some #(second (re-matches #"(?i)date: (.*)" %)) (str/split-lines (str/replace (slurp path) "\r" ""))))

(deftest the-fetch-log-dates-are-the-raw-headers
  (let [log (hard/read-edn ext/fetch-log-file)
        bib (fn [q] (str headers-dir "bib/" (str/replace q " " "-") ".headers"))]
    (is (= 5 (count (:queries log))))
    (doseq [{:keys [query http-date]} (:queries log)]
      (is (= http-date (header-date (bib query))) query))
    (doseq [[k d] [[:authorities "aut"] [:pseudonyms "pseudo"]]
            {:keys [id http-date]} (get log k)]
      (is (= http-date (header-date (str headers-dir d "/" id ".headers"))) id))
    (testing "one header file per request, no more"
      (is (= (+ 5 (count (:authorities log)) (count (:pseudonyms log)))
             (count (filter #(str/ends-with? (str %) ".headers") (file-seq (io/file headers-dir)))))))))

(deftest the-raw-responses-are-recorded-by-their-sha-256
  (let [raw (hard/read-edn (str ext/fixture-dir "raw-sha256.edn"))
        log (hard/read-edn ext/fetch-log-file)]
    (testing "the trimmed pages and the assembled files are the committed fixtures"
      (doseq [{:keys [trimmed trimmed-sha256]} (:pages raw)]
        (is (= trimmed-sha256 (ext/file-sha (str ext/fixture-dir trimmed))) trimmed))
      (is (= (get-in raw [:assembled :authorities]) (ext/file-sha ext/authority-file)))
      (is (= (get-in raw [:assembled :pseudonyms]) (ext/file-sha ext/pseudonym-file)))
      (is (= (get-in raw [:assembled :fetch-log]) (ext/file-sha ext/fetch-log-file))))
    (testing "one raw SHA-256 per request"
      (is (= (map :id (:authorities log)) (map :id (:authorities raw))))
      (is (= (map :id (:pseudonyms log)) (map :id (:pseudonyms raw))))
      (is (every? #(= 64 (count (:sha256 %))) (concat (:pages raw) (:authorities raw) (:pseudonyms raw)))))))

;; ---------------------------------------------------------------------------
;; After the run: the guards of the run path, on synthetic cases
;; ---------------------------------------------------------------------------

(defn- tampered [path]
  (let [f (java.io.File/createTempFile "o5-tampered" ".edn")]
    (spit f (str (slurp path) "\n"))
    (.getPath f)))

(deftest check-frozen-refuses-a-tampered-protocol-or-label-file
  (is (map? (ext/check-frozen!)) "the committed files pass")
  (let [p (tampered ext/protocol-file)]
    (try (with-redefs [ext/protocol-file p]
           (is (thrown-with-msg? clojure.lang.ExceptionInfo #"protocol is not the frozen one" (ext/check-frozen!))))
         (finally (io/delete-file p true))))
  (let [l (tampered ext/hand-labels-file)]
    (try (with-redefs [ext/hand-labels-file l]
           (is (thrown-with-msg? clojure.lang.ExceptionInfo #"labels, the benchmark or the split file" (ext/check-frozen!))))
         (finally (io/delete-file l true)))))

(deftest check-versions-refuses-a-model-that-is-not-the-protocol-s
  (let [p    (ext/protocol)
        good (into {} (for [{:keys [id model file-sha256 sha256 weights]} (:engines p) :when model]
                        [id {:file-sha256 file-sha256 :version {:sha256 sha256 :weights weights}}]))]
    (is (nil? (ext/check-versions! p good)))
    (doseq [path [[:c-n3 :file-sha256] [:c-n3 :version :sha256] [:d-m2-g6 :version :weights]]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not the one the protocol froze"
                            (ext/check-versions! p (assoc-in good path "0000"))) (pr-str path)))
    (is (thrown? clojure.lang.ExceptionInfo (ext/check-versions! p (dissoc good :b-m2))))))

(def ^:private n3-model (delay (:model (:c-n3 (ext/engine-models (ext/protocol))))))

(deftest fs-row-reads-the-inverted-name-and-a-tie-is-neither-link-nor-review
  (let [seen (atom [])
        call reconcile/agent-candidates
        spy  (fn [ms es opts] (swap! seen conj (:name (first ms))) (call ms es opts))
        m    {:id ["r" "f" 0 0] :text "Jean Dupont" :truth "1"}]
    (with-redefs [reconcile/agent-candidates spy]
      (testing "one entry: linked, the name read in heading order"
        (let [r (ext/fs-row @n3-model (pr/dated-entries [{:id "1" :label "Dupont, Jean" :variants []}]) m)]
          (is (= "1" (get-in r [:result :link :authority-id])))
          (is (= ["Dupont, Jean"] @seen))))
      (testing "two duplicate entries: a tie, no link, no review"
        (let [r (ext/fs-row @n3-model (pr/dated-entries [{:id "1" :label "Dupont, Jean" :variants []}
                                                         {:id "2" :label "Dupont, Jean" :variants []}]) m)]
          (is (= {:link nil :review? false :tied? true} (:result r)))
          (is (= #{"1" "2"} (set (map :id (:cands r))))))))))

(deftest score-refuses-a-model-on-what-it-was-fitted-on
  (let [k (first (get-in @n3-model [:provenance :train-keys]))
        b {:pool [{:id k :label "Dupont, Jean" :variants []}]
           :mentions [{:id ["r" "f" 0 0] :text "Jean Dupont" :truth k :split :test :stratum :easy :label :linked}]}]
    (with-redefs [ext/engine-models  (fn [_] {:c-n3 {:model @n3-model}})
                  ext/check-versions! (fn [_ _] nil)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"in-sample" (ext/score (ext/protocol) b))))))

(deftest build-from-drops-the-given-records
  (let [file (str hard/fixture-dir (:test hard/parts) "/bnf-sru-stendhal-100.xml")
        in   {:files [[:test file]] :authorities hard/authority-file :pseudonyms hard/pseudonym-file :hand {}}
        rid  "cb37515130t"
        kept (ext/build-from in)
        drop (ext/build-from (assoc in :drop-ids #{rid}))]
    (is (contains? (:records kept) rid))
    (is (not (contains? (:records drop) rid)))
    (is (not-any? #(= rid (:record %)) (:mentions drop)))
    (is (= (dec (count (:records kept))) (count (:records drop))))))

(defn- synthetic-build
  "`build-from` on three synthetic records, the importer, the authority
   index and the labeller stubbed; the frozen pool, hand, own-record,
   duplicate, split and stratum rules run as they are. r1 links \"Jean
   Tee\" to t, which r2 also cites; r3 holds \"Paul Uu\" twice, linked to
   u, which only r3 cites."
  [base-pool]
  (let [person (fn [id nm] {:tag "700" :key ["700" 0] :id id :name nm :surname (first (str/split nm #",")) :roles []})
        ment   (fn [rid i text truth] {:id [rid "f" 0 i] :record rid :corpus "x" :part :test :subfield "f" :occurrence 0
                                       :piece i :text text :role nil :label :linked :reason :surname :truth truth})
        recs   {"r1" {:persons [(person "t" "Tee, Jean")] :mentions [(ment "r1" 0 "Jean Tee" "t")]}
                "r2" {:persons [(person "t" "Tee, Jean")] :mentions []}
                "r3" {:persons [(person "u" "Uu, Paul")] :mentions [(ment "r3" 0 "Paul Uu" "u") (ment "r3" 1 "Paul Uu" "u")]}}]
    (with-redefs-fn {#'intermarc/ingest (fn [_ _] (map #(hash-map :id (keyword %)) ["r1" "r2" "r3"]))
                     #'ext/index-of    (fn [_ _] {:served {} :pseudonyms {}})
                     #'ext/labelled    (fn [r _] (let [rid (name (:id r))]
                                                   (assoc (recs rid) :record rid :corpus "x" :part :test :groups [])))}
      #(ext/build-from {:files [[:test ext/protocol-file]] :base-pool base-pool :hand {}}))))

(deftest the-composition-s-order-matters-on-a-synthetic-case
  (let [b  (synthetic-build [])
        by (into {} (map (juxt :id (juxt :label :reason))) (:mentions b))]
    (testing "pool-of reads the linked mentions: t's entry is built from r2, so r1's link stands"
      (is (= "r2" (:from-record (first (filter #(= "t" (:id %)) (:pool b))))))
      (is (= [:linked :surname] (by ["r1" "f" 0 0]))))
    (testing "the own-record rule runs before the duplicate rule: both copies are own-record"
      (is (= [:set-aside :entry-from-own-record] (by ["r3" "f" 0 0])))
      (is (= [:set-aside :entry-from-own-record] (by ["r3" "f" 0 1])))))
  (testing "on a shared id, the base pool's entry is kept"
    (let [b  (synthetic-build [{:id "u" :label "Uu, Paul" :variants [] :source :authority}])
          by (into {} (map (juxt :id (juxt :label :reason))) (:mentions b))]
      (is (= {:id "u" :label "Uu, Paul" :variants [] :source :authority} (first (filter #(= "u" (:id %)) (:pool b)))))
      (is (= [:linked :surname] (by ["r3" "f" 0 0])))
      (is (= [:set-aside :duplicate] (by ["r3" "f" 0 1]))))))

(deftest the-error-list-keeps-every-review
  (let [nm {:id ["r" "f" 0 0] :text "Jean Dupont" :stratum :no-namesake}]
    (is (= [{:id ["r" "f" 0 0] :text "Jean Dupont" :truth nil :stratum :no-namesake :predicted nil :outcome :abstained :review? true}]
           (ext/errors [{:mention nm :result {:link nil :review? true}}])))
    (is (= [] (ext/errors [{:mention nm :result {:link nil}}])))))

;; ---------------------------------------------------------------------------
;; The metrics and the rules, on synthetic figures
;; ---------------------------------------------------------------------------

(deftest the-wilson-interval
  (is (nil? (ext/wilson 0 0)))
  (let [[lo hi] (ext/wilson 9 10)]
    (is (< 0.595 lo 0.596))
    (is (< 0.982 hi 0.983))))

(defn- fig [fm r lo] {:false-merges fm :recall r :precision-wilson [lo 1.0] :linked 400 :persons 60})

(deftest the-decision-rules
  (testing "the gap is adopted only with no more false merges and 0.02 more recall"
    (is (= :adopt-gap-6 (:gap-for-m2 (ext/decisions {:a-jaccard (fig 3 0.98 0.97) :b-m2 (fig 1 0.70 0.97) :c-n3 (fig 1 0.70 0.97) :d-m2-g6 (fig 1 0.72 0.97)}))))
    (is (= :keep-no-gap (:gap-for-m2 (ext/decisions {:a-jaccard (fig 3 0.98 0.97) :b-m2 (fig 1 0.70 0.97) :c-n3 (fig 1 0.70 0.97) :d-m2-g6 (fig 1 0.719 0.97)}))))
    (is (= :keep-no-gap (:gap-for-m2 (ext/decisions {:a-jaccard (fig 3 0.98 0.97) :b-m2 (fig 1 0.70 0.97) :c-n3 (fig 1 0.70 0.97) :d-m2-g6 (fig 2 0.90 0.97)})))))
  (testing "the N3 model against M2"
    (let [d #(:n3-against-m2 (ext/decisions {:a-jaccard (fig 3 0.98 0.97) :b-m2 (fig 1 0.70 0.97) :c-n3 % :d-m2-g6 (fig 1 0.70 0.97)}))]
      (is (= :n3-better (d (fig 1 0.75 0.96))))
      (is (= :no-difference (d (fig 1 0.75 0.94))) "the Wilson bound")
      (is (= :n3-worse (d (fig 2 0.90 0.97))))
      (is (= :n3-worse (d (fig 0 0.67 0.97))))
      (is (= :no-difference (d (fig 1 0.71 0.97))))))
  (testing "against the Jaccard"
    (is (= {:b-m2 :jaccard-more-complete :c-n3 :matches-jaccard :d-m2-g6 :jaccard-more-complete}
           (:fs-against-jaccard (ext/decisions {:a-jaccard (fig 3 0.98 0.97) :b-m2 (fig 1 0.70 0.97) :c-n3 (fig 3 0.96 0.97) :d-m2-g6 (fig 4 0.99 0.97)})))))
  (testing "too few linked mentions or persons: inconclusive"
    (is (= :inconclusive (:gap-for-m2 (ext/decisions {:a-jaccard (assoc (fig 3 0.98 0.97) :linked 99) :b-m2 (fig 1 0.70 0.97) :c-n3 (fig 1 0.70 0.97) :d-m2-g6 (fig 1 0.80 0.97)}))))
    (is (= :inconclusive (:n3-against-m2 (ext/decisions {:a-jaccard (assoc (fig 3 0.98 0.97) :persons 19) :b-m2 (fig 1 0.70 0.97) :c-n3 (fig 1 0.80 0.97) :d-m2-g6 (fig 1 0.80 0.97)}))))))

;; ---------------------------------------------------------------------------
;; Counts
;; ---------------------------------------------------------------------------

(deftest the-counts
  (testing "five pages of 100 records, none dropped"
    (let [log (hard/read-edn ext/fetch-log-file)]
      (is (= [100 100 100 100 100] (map :records (filter :kept? (:queries log)))))
      (is (= 165 (count (:authorities log))))
      (is (= 117 (count (filter #(= 1 (:number-of-records %)) (:authorities log)))))
      (is (= 3 (count (:pseudonyms log)))))
    (is (= [] (ext/dropped-records))))
  (is (= {:mentions  {:test 640}
          :records   406
          :by-label  {:linked 519 :no-match 47 :set-aside 74}
          :by-reason {[:linked :hand] 15 [:linked :hyphen] 1 [:linked :shared-surname] 2 [:linked :surname] 474
                      [:linked :variant] 27 [:no-match :hand-negative] 2 [:no-match :no-heading] 45
                      [:set-aside :ambiguous] 3 [:set-aside :duplicate] 2 [:set-aside :entry-from-own-record] 31
                      [:set-aside :group] 20 [:set-aside :hand-rejected] 1 [:set-aside :incomplete] 3
                      [:set-aside :seen-in-design] 3 [:set-aside :unexplained-heading] 11}
          :per-split {:test {:linked 519 :no-match 47 :persons 106 :names 40
                             :strata {:easy 275 :hand 15 :hard-negative 2 :homonym 201 :no-namesake 45
                                      :residual 1 :variant 27}}}
          :pool      {:authority 322 :heading 135}}
         (hard/counts @bench))))

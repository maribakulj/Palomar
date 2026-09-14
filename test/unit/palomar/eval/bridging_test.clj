(ns palomar.eval.bridging-test
  "Bridging eval (DoD #2 / ADR 0018) on a Work gold *independent of f145*. The C2
   Bovary score is a transcription check (gold `workManifested` and input `f145 $3`
   are the same BnF link); this measures the real question — can `(author + title)`
   clustering recover the Work grouping when the source carries **no** link? — against
   data.bnf's `workManifested` grouping of Palomar's own INTERMARC fixtures
   (`test/fixtures/er-gold/bridging/`, Licence Ouverte).

   The records **without** `f145` are the non-circular test (their gold is not a
   re-serialisation of a link they carry); the f145-bearing records (Bovary) are a
   circular control that illustrates the variant-title recall ceiling. See the
   fixture README."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.plugins.intermarc :as intermarc]
            [palomar.text :as text]))

(def ^:private fixtures-dir
  "test/fixtures/documentary/intermarc/sru/intermarcXchange/")

(defn- field [record pred]
  (some #(when (= pred (:predicate %)) (:value %)) (:assertions record)))

(defn- records-by-ark
  "ark → {:f145? :author :title} over every bibliographic INTERMARC fixture."
  []
  (into {}
        (for [f (.listFiles (io/file fixtures-dir))
              :when (re-find #"^(bib|bnf)" (.getName f))
              r (intermarc/ingest (slurp f) {})
              :let [ark (:source r)] :when ark]
          [ark {:f145?  (boolean (some #(= :intermarc/f145_3 (:predicate %)) (:assertions r)))
                :author (or (field r :intermarc/f100_a) (field r :intermarc/f700_a))
                :title  (field r :intermarc/f245_a)}])))

(defn- gold-rows
  "data.bnf workManifested gold: `[sourceArk … work …]` (plain CSV, no dep)."
  []
  (->> (slurp "test/fixtures/er-gold/bridging/gold_workmanifested.csv")
       str/split-lines rest (remove str/blank?)
       (map #(mapv (fn [s] (str/replace s #"^\"|\"$" "")) (str/split % #"\",\"")))))

(def ^:private joined
  "Gold ARKs joined to the records we hold: `[{:ark :work :f145? :author :title}…]`."
  (delay
    (let [by-ark (records-by-ark)]
      (vec (for [[ark _ _ work _] (gold-rows) :let [r (by-ark ark)] :when r]
             (assoc r :ark ark :work work))))))

;; --- pairwise precision/recall (cluster-key vs gold work) -------------------

(defn- combos2 [v] (for [i (range (count v)) j (range (inc i) (count v))] [(nth v i) (nth v j)]))
(defn- pairs [keyfn items]
  (set (for [[a b] (combos2 (vec items)) :when (= (keyfn a) (keyfn b))] #{(:ark a) (:ark b)})))
(defn- cluster-key [m] [(some-> (:author m) text/norm) (some-> (:title m) text/norm)])

(defn- prec-rec [items]
  (let [g  (pairs :work items)
        p  (pairs cluster-key items)
        tp (count (set/intersection g p))]
    {:n (count items) :tp tp :fp (- (count p) tp) :fneg (- (count g) tp)}))

(deftest the-gold-joins-to-our-records
  (testing "data.bnf grouped 43 of our manifestation ARKs into Works"
    (is (= 43 (count @joined)))
    (is (some :f145? @joined))           ; the Bovary/L'île control
    (is (some (complement :f145?) @joined)))) ; the non-f145 bridging cases

(deftest exact-clustering-is-precise-everywhere
  (testing "no over-merge — exact (author+title) never joins two distinct Works (P = 1.0)"
    (is (zero? (:fp (prec-rec @joined))))))

(deftest non-f145-is-the-independent-bridging-measurement
  (let [non145 (remove :f145? @joined)
        {:keys [n fp fneg]} (prec-rec non145)]
    (testing "the non-circular subset: identical-title editions are bridged exactly (P = R = 1.0)"
      (is (= 12 n))
      (is (zero? fp))                    ; precision 1.0
      (is (zero? fneg)))             ; recall 1.0 — the first non-circular bridging number
    (testing "the two multi-edition Works present, both with no f145"
      (let [multi (->> non145 (group-by :work) (filter #(>= (count (val %)) 2)))]
        (is (= 2 (count multi)))
        (is (every? (fn [[_ ms]] (not-any? :f145? ms)) multi))))))

(deftest the-f145-control-shows-the-variant-title-recall-ceiling
  (let [with145 (filter :f145? @joined)
        {:keys [fp fneg]} (prec-rec with145)]
    (testing "Bovary (gold = 1 Work, but its gold IS the f145 link — circular control)"
      (is (zero? fp))                    ; still precise
      (is (pos? fneg)))              ; but under-merges — recall < 1
    (testing "its 28 editions shatter into several (author,title) clusters by variant title"
      (let [bovary (filter #(str/includes? (:work %) "cb11938746n") with145)]
        (is (>= (count bovary) 20))
        (is (>= (count (distinct (map cluster-key bovary))) 5))))))  ; variant-title under-merge

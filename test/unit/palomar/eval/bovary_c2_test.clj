(ns palomar.eval.bovary-c2-test
  "C2 fidelity eval (ADR 0016): does INTERMARC WEMI derivation reproduce
   data.bnf.fr's own Work grouping? Scored as pairwise precision/recall against a gold
   derived from data.bnf's `rdarel:workManifested`
   (see test/fixtures/c2-gold/bovary/README.md).

   Honest scope: the gold (`workManifested`) and our input (`f145 $3`) are two
   serialisations of the *same* BnF work-authority link, so a high score confirms
   *faithful transcription* — stable content-derived ids, no false split across the
   28 editions, no false merge with the 2 unlinked records — and **not** inference.
   Records that lack the link are the showcase-boundary eval's subject; their recall
   is ~0 and is measured there, not here."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.plugins.intermarc :as intermarc]
            [palomar.plugins.intermarc.wemi :as wemi]
            [palomar.plugins.lrmoo.view :as view]))

(def fixture
  "test/fixtures/documentary/intermarc/sru/intermarcXchange/bib-flaubert-madame-bovary-start1-max30.xml")
(def gold-csv "test/fixtures/c2-gold/bovary/workmanifested.csv")

(def records (intermarc/ingest (slurp fixture) {}))
(def derived (mapv wemi/derive-wemi records))

(defn- parse-gold
  "`sourceArk -> work-URI` from the gold CSV (header dropped). The CSV is quoted
   and none of its titles contain the `\",\"` field delimiter, so a split on it is
   safe for this fixture."
  [csv]
  (->> (str/split-lines (slurp csv))
       rest
       (remove str/blank?)
       (map (fn [line]
              (let [cells (mapv #(str/replace % #"^\"|\"$" "")
                                (str/split line #"\",\""))]
                [(first cells) (nth cells 3)])))
       (into {})))

(def gold (parse-gold gold-csv))

;; --- clustering ------------------------------------------------------------

(defn- our-key
  "The id our WEMI derivation groups `r` under: its Work, else its Expression, else its
   Manifestation (a singleton). All content-derived, so equal across records that
   share the same authority link (ADR 0008)."
  [r]
  (or (:id (first (view/works r)))
      (:id (first (view/expressions r)))
      (:id (first (view/manifestations r)))
      (:id r)))

(defn- gold-key
  "The Work URI the gold assigns `ark`, else the ark itself (a singleton)."
  [ark]
  (get gold ark ark))

;; --- pairwise precision / recall -------------------------------------------

(defn- pairs [coll]
  (let [v (vec coll)]
    (for [i (range (count v)) j (range (inc i) (count v))]
      [(nth v i) (nth v j)])))

(defn- pairwise
  "Pairwise precision/recall/F1 of `pred-of` vs `gold-of` over `items`."
  [items gold-of pred-of]
  (let [ps   (pairs items)
        join (fn [keyfn [a b]] (= (keyfn a) (keyfn b)))
        tp   (count (filter #(and (join gold-of %) (join pred-of %)) ps))
        fp   (count (filter #(and (not (join gold-of %)) (join pred-of %)) ps))
        fn*  (count (filter #(and (join gold-of %) (not (join pred-of %))) ps))
        prec (if (pos? (+ tp fp)) (/ tp (+ tp fp)) 1)
        rec  (if (pos? (+ tp fn*)) (/ tp (+ tp fn*)) 1)]
    {:tp tp :fp fp :fn fn* :precision prec :recall rec
     :f1 (if (pos? (+ prec rec)) (/ (* 2 prec rec) (+ prec rec)) 0)}))

;; --- tests -----------------------------------------------------------------

(deftest gold-is-the-28-edition-bovary-cluster
  (testing "the committed fixture carries every gold ARK (plus 2 unlinked extras)"
    (let [arks (set (map :source records))]
      (is (= 30 (count records)))
      (is (= 28 (count gold)))
      (is (every? arks (keys gold)))                     ; gold ⊆ fixture
      (is (= 1 (count (distinct (vals gold)))))          ; ...all one Work
      (is (str/includes? (first (vals gold)) "cb11938746n")))))

(deftest our-clustering-matches-the-gold-work-grouping
  (let [by-ark  (into {} (map (juxt :source identity)) derived)
        arks    (keys by-ark)
        gold-of gold-key
        ours-of (fn [ark] (our-key (by-ark ark)))
        m       (pairwise arks gold-of ours-of)]
    (testing "no false split across editions, no false merge -> precision = recall = 1"
      (is (== 1 (:precision m)))
      (is (== 1 (:recall m)))
      (is (== 1 (:f1 m))))
    (testing "the one non-trivial cluster is exactly the 28 gold editions"
      (let [big (->> arks (group-by ours-of) vals (map set) (apply max-key count))]
        (is (= 28 (count big)))
        (is (= (set (keys gold)) big))))
    (println (format "C2 Bovary: tp=%d fp=%d fn=%d  precision=%.3f recall=%.3f f1=%.3f"
                     (:tp m) (:fp m) (:fn m)
                     (double (:precision m)) (double (:recall m)) (double (:f1 m))))))

(deftest the-link-we-read-is-the-link-the-gold-encodes
  (testing "our `f145 $3` and the gold Work ARK stem are the same BnF id (the caveat, made concrete)"
    (let [r  (first (filter #(= "ark:/12148/cb304403926" (:source %)) records))
          x3 (->> (:assertions r) (filter #(= :intermarc/f145_3 (:predicate %))) first :value)]
      (is (= "11938746" x3))
      (is (str/includes? (gold-key "ark:/12148/cb304403926") (str "cb" x3 "n"))))))

(deftest the-two-unlinked-records-are-singletons-in-both
  (testing "records without f145_3 are not in the gold cluster, and we don't invent one"
    (doseq [ark ["ark:/12148/cb32056819r"   ; \"Le Réalisme. Madame Bovary…\" — a different Work
                 "ark:/12148/cb48756313f"]] ; \"Madame Bovary\" — no link in source nor gold
      (let [r (first (filter #(= ark (:source %)) derived))]
        (is (nil? (get gold ark)))                ; absent from the gold cluster
        (is (empty? (view/works r)))              ; ...and we mint no Work
        (is (= 1 (count (view/manifestations r))))))))

(ns palomar.eval.openlibrary-er-test
  "Entity-resolution eval on an INDEPENDENT corpus (OpenLibrary Work/Edition, CC0 —
   see test/fixtures/er-gold/openlibrary/README.md). Scores the string-key
   clustering strategies the floor projection relies on (author + title) against
   OpenLibrary's own work grouping, on ~4.5k editions / ~328 works.

   It corroborates, on a BnF-independent corpus 13× the Madame Bovary fixture, the
   precision/recall trade-off the offline spike measured: exact title is precise
   but low-recall; loosening trades precision for recall; author-only over-merges.

   Honest framing baked into the assertions: OpenLibrary's gold is noisy (it splits
   single works across many work_keys — Frankenstein x14), so precision *against
   this gold* is understated and the honest signal is recall. Numbers are printed
   for the record; the assertions pin only the robust, directional facts."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.text :as text]))

(def ^:private csv-path "test/fixtures/er-gold/openlibrary/work-editions.csv")

(defn- parse-line
  "Parse one RFC-4180 CSV line (fields may be quoted, with \"\" escaping a quote).
   The corpus has no embedded newlines, so line-splitting upstream is safe."
  [line]
  (loop [cs (seq line), cur (StringBuilder.), out [], q? false]
    (if-let [c (first cs)]
      (cond
        q?       (cond
                   (and (= c \") (= (first (next cs)) \")) (do (.append cur \") (recur (next (next cs)) cur out true))
                   (= c \")                                (recur (next cs) cur out false)
                   :else                                   (do (.append cur c) (recur (next cs) cur out true)))
        (= c \") (recur (next cs) cur out true)
        (= c \,) (recur (next cs) (StringBuilder.) (conj out (str cur)) false)
        :else    (do (.append cur c) (recur (next cs) cur out false)))
      (conj out (str cur)))))

(def ^:private rows
  (->> (str/split-lines (str/replace (slurp csv-path) #"^﻿" ""))   ; strip BOM
       rest
       (remove str/blank?)
       (mapv (fn [line]
               (let [[w e t a] (parse-line line)]
                 {:work w :edition e :title t :author a})))))

(defn- k-gold      [r] (:work r))
(defn- k-authtitle [r] [(:author r) (text/norm (:title r))])         ; the exact key the floor projection uses
(defn- k-pfx2      [r] [(:author r) (vec (take 2 (str/split (text/norm (:title r)) #" ")))])
(defn- k-author    [r] (:author r))

(defn- c2 [n] (quot (* n (dec n)) 2))

(defn- pairwise
  "Pairwise precision/recall/F1 of `pred` vs `gold` over `rows`, computed from the
   gold×pred contingency (O(n), not O(n²))."
  [rows gold pred]
  (let [gsize (frequencies (map gold rows))
        psize (frequencies (map pred rows))
        cell  (frequencies (map (juxt gold pred) rows))
        tp    (reduce + (map c2 (vals cell)))
        fp    (- (reduce + (map c2 (vals psize))) tp)
        fnn   (- (reduce + (map c2 (vals gsize))) tp)
        p     (if (pos? (+ tp fp)) (/ tp (+ tp fp)) 1)
        r     (if (pos? (+ tp fnn)) (/ tp (+ tp fnn)) 1)]
    {:tp tp :fp fp :fn fnn :precision (double p) :recall (double r)
     :f1 (double (if (pos? (+ p r)) (/ (* 2 p r) (+ p r)) 0))}))

(deftest corpus-is-multi-edition-and-independent
  (is (>= (count rows) 4000))
  (is (>= (count (distinct (map :work rows))) 300))
  (is (every? #(and (seq (:work %)) (seq (:title %)) (seq (:author %))) rows))
  (testing "many works carry several editions (there is something to cluster)"
    (let [sizes (vals (frequencies (map :work rows)))]
      (is (every? #(>= % 3) sizes))
      (is (>= (apply max sizes) 20)))))

(deftest string-clustering-trades-precision-for-recall
  (let [exact (pairwise rows k-gold k-authtitle)
        pfx   (pairwise rows k-gold k-pfx2)
        auth  (pairwise rows k-gold k-author)]
    (println "OpenLibrary ER (4.5k editions / ~328 works, vs OpenLibrary gold):")
    (doseq [[n m] [["author+title" exact] ["author+pfx2" pfx] ["author-only" auth]]]
      (println (format "  %-13s P=%.3f R=%.3f F1=%.3f" n (:precision m) (:recall m) (:f1 m))))
    (testing "exact title is low-recall — the intrinsic title-variance ceiling"
      (is (< (:recall exact) 0.5)))
    (testing "loosening the title key raises recall"
      (is (> (:recall pfx) (:recall exact))))
    (testing "author-only over-merges catastrophically — precision floor"
      (is (< (:precision auth) 0.15)))))

(deftest the-gold-itself-is-noisy
  (testing "OpenLibrary splits single works across many work_keys, so precision vs this gold is understated"
    (let [splits (->> (group-by k-authtitle rows)
                      (filter (fn [[_ rs]] (> (count (distinct (map :work rs))) 1))))]
      (is (>= (count splits) 30))
      (testing "the worst offenders fragment one work into many (Frankenstein-style)"
        (is (some (fn [[_ rs]] (>= (count (distinct (map :work rs))) 5)) splits))))))

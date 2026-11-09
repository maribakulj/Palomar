(ns palomar.authority-blocking-property-test
  "The blocking keys of an authority index never lose a candidate (lot M6):
   for any mention and any entry, when the matcher's name level is above
   `:none`, the entry shares a blocking key with the mention. Names are drawn
   from a small alphabet so that shared tokens, one-edit surnames and
   initials are frequent, with punctuation, particles, hyphens, ligatures, ß
   and combining marks among them."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [palomar.agent-match :as fs]
            [palomar.authority-index :as index]))

(def ^:private plain-word (gen/fmap str/join (gen/vector (gen/elements ["a" "b" "é" "d" "e"]) 1 6)))

(def ^:private hard-letter
  "Letters and marks that normalisation folds, splits or drops: a combining
   acute, a ligature, ß and ẞ, a dotted and a dotless I, an apostrophe, a
   hyphen, a period, a digit."
  (gen/elements ["a" "b" "d" "e" "e\u0301" "\uFB01" "\u00DF" "\u1E9E" "\u0130" "\u0131" "I" "\u0152" "'" "-" "." "1"]))

(def ^:private hard-word (gen/fmap str/join (gen/vector hard-letter 1 6)))

(def ^:private particle (gen/elements ["de" "von" "van der" "d'" "Mac" "al-"]))

(def ^:private word
  (gen/frequency [[3 plain-word]
                  [2 hard-word]
                  [1 (gen/fmap (fn [[p w]] (str p (when-not (re-find #"['-]$" p) " ") w)) (gen/tuple particle plain-word))]]))

(def ^:private a-name
  (gen/one-of [(gen/fmap (fn [[s fs]] (str s ", " (str/join " " fs))) (gen/tuple word (gen/vector word 0 3)))
               (gen/fmap (fn [ws] (str/join " " ws)) (gen/vector word 1 3))
               (gen/fmap (fn [[s f]] (str s ", " (first f) ".")) (gen/tuple word word))
               (gen/fmap (fn [[a b]] (str a "-" b ", " a)) (gen/tuple word word))]))

(def ^:private an-entry (gen/fmap (fn [[l vs]] {:label l :variants vs}) (gen/tuple a-name (gen/vector a-name 0 3))))

(defspec a-candidate-above-none-shares-a-blocking-key 300
  (prop/for-all [m a-name
                 e an-entry]
                (let [level (get-in (fs/compare-pair {:name m} e) [:levels :name])]
                  (or (= :none level)
                      (boolean (seq (set/intersection (set (index/blocking-keys [m])) (set (index/entry-keys e)))))))))

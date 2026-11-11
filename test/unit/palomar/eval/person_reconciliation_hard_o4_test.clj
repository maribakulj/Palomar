(ns palomar.eval.person-reconciliation-hard-o4-test
  "Lot O4 on the hard benchmark (`docs/eval/person-reconciliation-hard.md`,
   section O4): the three rules measured one by one and together on the
   same candidates, thresholds and gap chosen on a cross-validation of
   train; dev confirms. The test split is never read. And the relative
   competing rule on the M2 gold, held out by family (the M2 model's
   evidence)."
  (:require [clojure.test :refer [deftest is testing]]
            [palomar.agent-match :as fs]
            [palomar.eval.person-reconciliation :as pr]
            [palomar.eval.person-reconciliation-hard :as hard]
            [palomar.eval.person-reconciliation-hard-n4 :as n4]
            [palomar.eval.person-reconciliation-hard-o4 :as o4]
            [palomar.reconcile :as reconcile]))

(def ^:private bench (delay (hard/load-benchmark)))

(def ^:private seen-splits (atom #{}))

(def ^:private grid
  (delay (let [split n4/split-mentions]
           (with-redefs [n4/split-mentions (fn [b s] (swap! seen-splits conj s) (split b s))]
             (o4/grid @bench)))))

(defn- row [x] ((juxt :correct :false-merges :review :abstained) x))

(def expected-grid
  "[correct false-merges review abstained] overall, on the held-out folds of
   train (697 mentions) and on dev (206), each configuration at the
   thresholds the N4 criterion (and, with the relative rule, the gap
   criterion) chose on the folds."
  {:a-current    {:thresholds {:propose 0.8 :review 0.5} :cv [554 2 15 126] :dev [165 0 5 36]}
   :b-form       {:thresholds {:propose 0.5 :review 0.5} :cv [554 2 15 126] :dev [165 0 5 36]}
   :b-chain      {:thresholds {:propose 0.95 :review 0.5} :cv [404 1 179 113] :dev [99 0 74 33]}
   :b-relative   {:thresholds {:propose 0.8 :review 0.5} :cv [554 2 15 126] :dev [165 0 5 36]}
   :b-form-chain {:thresholds {:propose 0.5 :review 0.5} :cv [553 2 26 116] :dev [165 0 5 36]}
   :c-all        {:thresholds {:propose 0.5 :review 0.5 :gap 12.0} :cv [554 2 25 116] :dev [165 0 5 36]}
   :d-jaccard    {:thresholds nil :cv [565 4 0 128] :dev [171 1 0 34]}})

(def expected-strata
  "Per stratum, [correct false-merges review abstained]."
  {[:a-current :cv]  {:easy [272 0 0 12] :homonym [233 0 15 9] :variant [32 0 0 10] :hand [17 0 0 4]
                      :hard-negative [0 2 0 8] :no-namesake [0 0 0 83]}
   [:a-current :dev] {:easy [28 0 0 1] :homonym [122 0 3 2] :variant [5 0 0 11] :hand [10 0 0 4]
                      :hard-negative [0 0 2 1] :no-namesake [0 0 0 17]}
   [:c-all :cv]      {:easy [272 0 0 12] :homonym [233 0 15 9] :variant [32 0 10 0] :hand [17 0 0 4]
                      :hard-negative [0 2 0 8] :no-namesake [0 0 0 83]}
   [:c-all :dev]     {:easy [28 0 0 1] :homonym [122 0 3 2] :variant [5 0 0 11] :hand [10 0 0 4]
                      :hard-negative [0 0 2 1] :no-namesake [0 0 0 17]}
   [:d-jaccard :cv]  {:easy [270 0 0 14] :homonym [233 1 0 23] :variant [42 0 0 0] :hand [20 0 0 1]
                      :hard-negative [0 1 0 9] :no-namesake [0 2 0 81]}
   [:d-jaccard :dev] {:easy [29 0 0 0] :homonym [122 0 0 5] :variant [6 0 0 10] :hand [14 0 0 0]
                      :hard-negative [0 1 0 2] :no-namesake [0 0 0 17]}})

(deftest the-grid-on-the-folds-of-train-and-on-dev
  (let [g @grid]
    (is (= expected-grid
           (into {} (for [[k r] g] [k {:thresholds (:thresholds r) :cv (row (get-in r [:cv :all])) :dev (row (get-in r [:dev :all]))}]))))
    (is (= expected-strata
           (into {} (for [[k w] (keys expected-strata)] [[k w] (into {} (for [s o4/strata] [s (row (get-in g [k w s]))]))]))))
    (testing "the test split is never read"
      (is (= #{:train :dev} @seen-splits)))))

(deftest what-the-three-rules-change-on-the-hard-benchmark
  (let [g @grid]
    (testing "the chain alone, under the absolute competing rule, loses links: N4's 165 to 99 on dev"
      (is (= 99 (get-in g [:b-chain :dev :all :correct]))))
    (testing "all three: the same outright links as N4 on the folds and on dev, no new false merge"
      (is (= (get-in g [:a-current :cv :all :correct]) (get-in g [:c-all :cv :all :correct])))
      (is (= (get-in g [:a-current :dev :all]) (get-in g [:c-all :dev :all]))))
    (testing "10 variant cases N4 abstained on the folds go to review, each with its truth among the reviewed: 10 copies of one mention, \"Pierre P. Loti-Viaud\", three namesakes tied at :forename-overlap"
      (is (= [10 1] ((juxt :review :review-truth) (get-in g [:c-all :cv :variant])))))
    (testing "the measured name weights now weigh: :forename-overlap +4.80, :surname-only capped by it, :surname-typo raised to it; :same-tokens, unmeasured and no surname agreement, stays 0"
      (let [w (get-in g [:c-all :weights :name])]
        (is (< 4.79 (:forename-overlap w) 4.80))
        (is (= (:forename-overlap w) (:surname-only w) (:surname-typo w)))
        (is (= 0.0 (:same-tokens w)))
        (is (= 0.0 (:forenames-differ w)) "a disagreement, never for")
        (is (= 0.0 (get-in g [:a-current :weights :name :surname-only])))))
    (testing "every Fellegi-Sunter configuration reads the same candidates: the truth among them for every train mention, 179 of 186 on dev"
      (doseq [k (keys o4/configurations)]
        (is (= 1 (get-in g [k :cv :all :generation])) (str k))
        (is (= 179/186 (get-in g [k :dev :all :generation])) (str k))))
    (testing "the gap: 0.5 to 2, 8 and 12 bits tie at 556 links (3 to 6 take review 0.2 and keep 551); no gap loses one link; the tie rule takes 12"
      (let [per (get-in g [:c-all :per-gap])]
        (is (every? :admissible? per))
        (is (= [556 556 556 551 551 551 556 556 555] (map (comp :links :point) per)))
        (is (= 12.0 (get-in g [:c-all :thresholds :gap])))))))

(deftest the-figures-of-a-set-of-decisions
  (let [m  (fn [truth stratum] {:truth truth :stratum stratum :text "x"})
        rs [{:mention (m "a" :easy) :result {:link {:authority-id "a"}} :cands [{:id "a"}] :review-cands []}
            {:mention (m "b" :easy) :result {:link {:authority-id "c"}} :cands [{:id "c"} {:id "b"}] :review-cands []}
            {:mention (m "d" :homonym) :result {:review? true} :cands [{:id "d"} {:id "e"}] :review-cands [{:id "d"} {:id "e"}]}
            {:mention (m "f" :hand) :result {:review? true} :cands [{:id "g"}] :review-cands [{:id "g"}]}
            {:mention (m nil :no-namesake) :result {:link {:authority-id "h"}} :cands [{:id "h"}] :review-cands []}
            {:mention (m nil :no-namesake) :result {} :cands [] :review-cands []}
            {:mention (m "i" :variant) :result {} :cands [] :review-cands []}]
        f  (o4/figures rs)]
    (is (= {:mentions 7 :linked 5 :correct 1 :false-merges 2 :precision 1/3 :recall 1/5 :review 2
            :reviews-per-mention 2/7 :review-truth 1/2 :abstained 2 :abstention 2/7 :no-match-abstention 1/2
            :generation 3/5}
           f))))

(deftest the-relative-rule-on-the-m2-gold-held-out-by-family
  (let [c      (pr/corpus)
        models (:models (pr/fs-run c))
        es     (pr/dated-entries (:pool c))
        at     (fn [g] (let [a (pr/task-a-fs c (comp #(cond-> % g (assoc-in [:thresholds :gap] g)) models :family))]
                         [((juxt :correct :abstained :false-links) (:all (:outright a))) (:review a)]))]
    (testing "the mentions with two candidates at or above review, by their gap in bits: four steps, 6 names among the 25 links a gap can free"
      (let [gaps (for [m (:mentions c)
                       :let [ps (:proposals (reconcile/agent-candidates [(select-keys m [:name :dates])] es {:model (models (:family m))}))
                             [a b] ps]
                       :when (and a b)]
                   [(/ (Math/round (* 1000 (- (:match-weight a) (:match-weight b)))) 1000.0) (:name m)])]
        (is (= {0.0 1 7.699 1 8.699 1 9.998 23} (frequencies (map first gaps))))
        (is (= 6 (count (distinct (map second (remove #(zero? (first %)) gaps))))))))
    (testing "outright links [correct abstained false-links] and reviews by gap: below 7.699 all 25 are freed; then Hetzel and Stahl compete again in turn; at 9.998 Jules Verne holds the 20 Férat and 3 Víctor Hugo mentions again"
      (is (= {nil [[111 25 0] 26] 7.0 [[136 0 0] 1] 8.0 [[135 1 0] 2] 9.0 [[134 2 0] 3] 10.0 [[111 25 0] 26]}
             (into {} (for [g [nil 7.0 8.0 9.0 10.0]] [g (at g)])))))
    (testing "the shipped M2 model's file states no gap: the absolute rule"
      (is (nil? (fs/competing-gap (n4/shipped-model)))))))

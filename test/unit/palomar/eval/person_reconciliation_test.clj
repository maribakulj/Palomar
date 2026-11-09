(ns palomar.eval.person-reconciliation-test
  "Person-level reconciliation, measured (lot M2): Palomar's *current* agent
   reconciliation scored on 150 real BnF INTERMARC records against the BnF
   cataloguers' own links (`100`/`700 $3`). The numbers asserted here are the
   baseline later lots must move; they are not a guarantee. Corpus and gold:
   `test/fixtures/er-gold/bnf-persons/README.md`; write-up:
   `docs/eval/person-reconciliation.md`. The corpus readers and the metrics live
   in `palomar.eval.person-reconciliation`; the metrics are tested here on
   hand-made inputs first."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.eval.person-reconciliation :as pr]
            [palomar.plugins.agents :as agents]
            [palomar.plugins.intermarc :as intermarc]
            [palomar.reconcile :as reconcile]))

;; ---------------------------------------------------------------------------
;; The metrics, on hand-made inputs
;; ---------------------------------------------------------------------------

(def ^:private surnames {"pere" "Dumas" "fils" "Dumas" "adolphe" "Dumas" "hugo" "Hugo"})

(defn- props [& id-scores] (vec (for [[id s] (partition 2 id-scores)] {:authority-id id :score s})))

(deftest link-outcome-classifies-one-mention
  (let [pool #{"pere" "fils" "adolphe" "hugo"}
        out  #(pr/link-outcome %1 %2 pool surnames)]
    (testing "no truth: undetermined, whatever is proposed"
      (is (= :undetermined (:outcome (out nil (props "pere" 1.0))))))
    (testing "a truth outside the pool: any proposal is necessarily wrong"
      (is (= :not-in-pool/proposed (:outcome (out "elsewhere" (props "hugo" 0.5)))))
      (is (= :not-in-pool/abstained (:outcome (out "elsewhere" [])))))
    (testing "in the pool"
      (is (= :abstained (:outcome (out "pere" []))))
      (is (= {:outcome :correct :at5? true :tied? false} (out "pere" (props "pere" 1.0 "adolphe" 0.5))))
      (testing "a tie at the top is flagged, whichever side it falls"
        (is (= {:outcome :false-link/homonym :at5? true :tied? true} (out "pere" (props "fils" 1.0 "pere" 1.0))))
        (is (true? (:tied? (out "pere" (props "pere" 1.0 "fils" 1.0))))))
      (is (= :false-link/other (:outcome (out "pere" (props "hugo" 0.6 "pere" 0.5)))))
      (testing "recall@5: the truth sixth is a miss"
        (is (false? (:at5? (out "pere" (props "fils" 1 "adolphe" 1 "hugo" 1 "a" 1 "b" 1 "pere" 1)))))
        (is (true? (:at5? (out "pere" (props "fils" 1 "adolphe" 1 "hugo" 1 "a" 1 "pere" 1)))))))))

(deftest link-summary-aggregates-outcomes
  (let [o (fn [outcome & {:keys [at5 tied] :or {at5 false tied false}}] {:outcome outcome :at5? at5 :tied? tied})
        s (pr/link-summary [(o :correct :at5 true) (o :correct :at5 true :tied true)
                            (o :false-link/homonym :at5 true :tied true) (o :false-link/other)
                            (o :abstained) (o :not-in-pool/proposed) (o :not-in-pool/abstained)
                            (o :undetermined)])]
    (is (= {:mentions 8 :in-pool 5 :correct 2 :at5 3 :false-links 2 :homonym 1 :other 1 :abstained 1
            :tied-top1 2 :not-in-pool 2 :not-in-pool-proposed 1 :undetermined 1
            :recall-1 2/5 :recall-5 3/5
            ;; 2 right among the 5 determined mentions that received a link
            :precision-1 2/5}
           s)))
  (testing "nothing proposed: precision is undefined, not 0 or 1"
    (is (nil? (:precision-1 (pr/link-summary [{:outcome :abstained}]))))
    (is (= 0 (:recall-1 (pr/link-summary [{:outcome :abstained}]))))))

(deftest pairwise-counts-merges-against-the-gold
  ;; truth: a a a b b ; clusters: {1 1 2} for the a's, {2 2} for the b's
  (let [xs [{:truth :a :cluster 1} {:truth :a :cluster 1} {:truth :a :cluster 2}
            {:truth :b :cluster 2} {:truth :b :cluster 2}]
        p  (pr/pairwise xs)]
    ;; predicted pairs: C(2,2)+C(3,2) = 4 ; true pairs: C(3,2)+C(2,2) = 4 ;
    ;; right: (a1 a2) and (b1 b2) = 2
    (is (= {:predicted 4 :true 4 :right 2 :false-merges 2 :missed 2
            :precision 1/2 :recall 1/2 :f1 1/2}
           p)))
  (testing "no merge at all: precision undefined, recall 0"
    (let [p (pr/pairwise [{:truth :a :cluster 1} {:truth :a :cluster 2}])]
      (is (= {:predicted 0 :true 1 :right 0 :false-merges 0 :missed 1 :precision nil :recall 0 :f1 nil} p)))))

(deftest b-cubed-averages-per-mention
  (let [xs [{:truth :a :cluster 1} {:truth :a :cluster 1} {:truth :a :cluster 2}
            {:truth :b :cluster 2} {:truth :b :cluster 2}]]
    ;; precision: a1 a2 -> 1, a3 -> 1/3, b1 b2 -> 2/3 ; mean (1+1+1/3+2/3+2/3)/5 = 11/15
    ;; recall:    a1 a2 -> 2/3, a3 -> 1/3, b1 b2 -> 1 ; mean (2/3+2/3+1/3+1+1)/5 = 11/15
    (is (= {:precision 11/15 :recall 11/15} (pr/b-cubed xs)))
    (is (= {:precision 1 :recall 1/2} (pr/b-cubed [{:truth :a :cluster 1} {:truth :a :cluster 2}])))))

(deftest heading-reads-surname-and-forename-as-the-intermarc-mapping-does
  (is (= "Dumas, Alexandre" (pr/heading [["w" "x"] ["a" "Dumas"] ["m" "Alexandre"] ["d" "1802-1870"]])))
  (is (= "Aramis" (pr/heading [["a" "Aramis"] ["d" "1802-1870"]])))
  (is (nil? (pr/heading [["d" "1802-1870"]]))))

;; ---------------------------------------------------------------------------
;; The corpus
;; ---------------------------------------------------------------------------

(def ^:private c (delay (pr/corpus)))

(deftest the-corpus-is-what-the-readme-says
  (let [{:keys [records mentions pool]} @c]
    (is (= 150 (count records)))
    (is (= 142 (count mentions)))
    (is (= {"100" 90 "700" 52} (frequencies (map :tag mentions))))
    (is (= 141 (count (filter :truth mentions))))
    (is (= 37 (count (distinct (keep :truth mentions)))))
    (is (= 33 (count pool)))
    (testing "the four cited authorities the BnF SRU does not return"
      (is (= #{"10018888" "12612031" "14380255" "16814325"}
             (set (remove (set (map :id pool)) (keep :truth mentions))))))
    (testing "the pool reads the 100 heading and the 400 variants"
      (let [pere (first (filter #(= "11901063" (:id %)) pool))]
        (is (= "Dumas, Alexandre" (:label pere)))
        (is (some #{"Dumas père, Alexandre"} (:variants pere)))
        (is (some #{"Aramis"} (:variants pere)))))))

(deftest a-main-entry-mention-is-the-agent-palomar-produces
  (testing "for every 100, the name the eval matches is Palomar's own :canon/agent"
    (let [{:keys [records mentions]} @c
          by-id (into {} (map (juxt :id identity)) records)]
      (doseq [m (filter #(= "100" (:tag %)) mentions)]
        (is (= [(:name m)] (agents/creators (by-id (:record m)))) (str (:record m)))))))

(defn- gold-strings
  "Every value a matcher must not see: each mention's `$3`, `$1` (and its ISNI
   digits) and `$d`; each authority's number, ISNI (`031 $a`) and dates (`$d`
   of its 100 and 400s)."
  [{:keys [mentions pool]}]
  (let [auth (intermarc/ingest (slurp pr/authority-file) {})
        sfs  (fn [r tags codes] (for [f (:fragments r)
                                      :when (tags (get-in f [:locator :tag]))
                                      [code v] (get-in f [:locator :subfields])
                                      :when (codes code)]
                                  v))]
    (->> (concat (for [m mentions [code v] (:subfields m) :when (#{"3" "1" "d"} code)] v)
                 (for [m mentions [code v] (:subfields m) :when (= "1" code)] (str/replace v #"[^0-9X]" ""))
                 (map :id pool)
                 (mapcat #(sfs % #{"031"} #{"a"}) auth)
                 (mapcat #(sfs % #{"100" "400"} #{"d"}) auth))
         (map str/trim)
         (remove str/blank?)
         set)))

(deftest the-matcher-never-sees-the-gold
  (let [calls (atom [])
        spy   (fn [names entries]
                (swap! calls conj [names entries])
                (reconcile/propose-agent-links names entries))
        res   (pr/task-a @c spy)
        gold  (gold-strings @c)
        texts (distinct (concat (mapcat first @calls)
                                (for [[_ es] @calls e es t (cons (:label e) (:variants e))] t)))]
    (testing "task A calls the matcher once per mention, one name at a time, and reports what it returned"
      (is (= 142 (count @calls)))
      (is (every? #(= 1 (count (first %))) @calls))
      (is (= (:all (pr/task-a @c)) (:all res))))
    (testing "the pool entries passed carry :id :label :variants and nothing else"
      (is (every? #(= #{:id :label :variants} (set (keys %))) (mapcat second @calls))))
    (testing "no $3, $1, ISNI, date or authority number appears in a name, a label or a variant passed"
      (is (< 100 (count gold)))
      (is (some #{"11901063"} gold))
      (is (some #{"1802-1870"} gold))
      (is (empty? (for [t texts g gold :when (str/includes? t g)] [t g]))))))

;; ---------------------------------------------------------------------------
;; Task A — mention -> authority (reconcile/propose-agent-links)
;; ---------------------------------------------------------------------------

(def ^:private a (delay (pr/task-a @c)))

(defn- summary [m] (merge {:mentions 0 :in-pool 0 :correct 0 :at5 0 :false-links 0 :homonym 0 :other 0
                           :abstained 0 :tied-top1 0 :not-in-pool 0 :not-in-pool-proposed 0 :undetermined 0}
                          m))

(deftest task-a-baseline
  (pr/print-task-a @a)
  (testing "all 142 mentions, the pool in id order"
    (is (= (summary {:mentions 142 :in-pool 136 :correct 101 :at5 136 :false-links 35 :homonym 35
                     :tied-top1 36 :not-in-pool 5 :not-in-pool-proposed 2 :undetermined 1
                     :recall-1 101/136 :recall-5 1 :precision-1 101/138})
           (:all @a))))
  (testing "per family"
    (is (= {"Dumas"  (summary {:mentions 51 :in-pool 50 :correct 15 :at5 50 :false-links 35 :homonym 35
                               :tied-top1 36 :undetermined 1 :recall-1 3/10 :recall-5 1 :precision-1 3/10})
            "Hetzel" (summary {:mentions 2 :in-pool 2 :correct 2 :at5 2 :recall-1 1 :recall-5 1 :precision-1 1})
            "Hugo"   (summary {:mentions 8 :in-pool 8 :correct 8 :at5 8 :recall-1 1 :recall-5 1 :precision-1 1})
            "Verne"  (summary {:mentions 29 :in-pool 29 :correct 29 :at5 29 :recall-1 1 :recall-5 1 :precision-1 1})
            "others" (summary {:mentions 52 :in-pool 47 :correct 47 :at5 47 :not-in-pool 5 :not-in-pool-proposed 2
                               :recall-1 1 :recall-5 1 :precision-1 47/49})}
           (:by-family @a))))
  (testing "Dumas père and fils score alike (\"Dumas, Alexandre\", 1.0): the pool order decides"
    (let [m (first (filter #(= "11901063" (:truth %)) (:mentions @a)))]
      (is (= [["11901062" 1.0] ["11901063" 1.0]] (map (juxt :authority-id :score) (take 2 (:proposals m))))))
    (let [rev (pr/task-a (update @c :pool reverse))]
      (is (= (summary {:mentions 142 :in-pool 136 :correct 135 :at5 136 :false-links 1 :homonym 1
                       :tied-top1 36 :not-in-pool 5 :not-in-pool-proposed 2 :undetermined 1
                       :recall-1 135/136 :recall-5 1 :precision-1 45/46})
             (:all rev)))))
  (testing "a mention whose authority is not in the pool is linked to Victor Hugo"
    (is (= #{["Paltsits, Victor Hugo" "11907966"]}
           (set (for [m (:mentions @a) :when (= :not-in-pool/proposed (:outcome m))]
                  [(:name m) (:authority-id (first (:proposals m)))]))))))

;; ---------------------------------------------------------------------------
;; Task B — mention <-> mention clustering, no pool
;; ---------------------------------------------------------------------------

(def ^:private b (delay (pr/task-b @c)))

(deftest task-b-baseline
  (pr/print-task-b @b)
  (is (= 141 (:mentions @b)))
  (testing "Palomar today: a name-only agent is scoped to its record, so nothing is merged"
    (is (= {:pairwise {:predicted 0 :true 1114 :right 0 :false-merges 0 :missed 1114
                       :precision nil :recall 0 :f1 nil}
            :b-cubed  {:precision 1 :recall 37/141}}
           (:record-scoped @b))))
  (testing "same normalised name: every true pair, and 35 false merges, all père x fils"
    (is (= {:pairwise {:predicted 1149 :true 1114 :right 1114 :false-merges 35 :missed 0
                       :precision 1114/1149 :recall 1 :f1 2228/2263}
            :b-cubed  {:precision 2503/2538 :recall 1}}
           (:same-name @b)))
    (is (= {"dumas alexandre" {"11901062" 1 "11901063" 35}} (:mixed @b))))
  (testing "accepting every top proposal: père x fils, and Paltsits x Victor Hugo"
    ;; 35 père x fils pairs, and 2 Paltsits x Victor Hugo pairs
    (is (= {:pairwise {:predicted 1151 :true 1114 :right 1114 :false-merges 37 :missed 0
                       :precision 1114/1151 :recall 1 :f1 2228/2265}
            :b-cubed  {:precision 2479/2538 :recall 1}}
           (:top-1 @b)))))

(ns palomar.eval.person-reconciliation-test
  "Person-level reconciliation, measured (lot M2): Palomar's agent
   reconciliation scored on 150 real BnF INTERMARC records against the BnF
   cataloguers' own links (`100`/`700 $3`). The name-only numbers are the M2
   baseline (\"before\"), still asserted by running the name-only matcher; lot
   M3's date-aware numbers are asserted next to them, and lot M4's
   Fellegi-Sunter numbers (with their calibration, an EM comparison and a
   synthetic stress set) after those, and lot M5's constrained clustering
   (with a synthetic chain set), and lot M7's typed events (the editions of
   the same records, a synthetic set built on the pool's authorities, and the
   actors of five LIDO records) last. They are
   measurements, not a guarantee. Corpus and gold:
   `test/fixtures/er-gold/bnf-persons/README.md`; write-up:
   `docs/eval/person-reconciliation.md`. The corpus readers and the metrics live
   in `palomar.eval.person-reconciliation`; the metrics are tested here on
   hand-made inputs first."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.agent-dates :as dates]
            [palomar.agent-match :as fs]
            [palomar.eval.person-reconciliation :as pr]
            [palomar.plugins.agents :as agents]
            [palomar.plugins.intermarc :as intermarc]
            [palomar.plugins.oai :as oai]
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

(deftest the-pool-is-read-by-palomar-s-intermarc-authority-reader
  (testing "Palomar's reader gives the pool M2's test-side reader gave, entry for entry"
    (is (= (sort-by :id (pr/legacy-authority-pool (slurp pr/authority-file)))
           (map #(dissoc % :dates) (:pool @c)))))
  (testing "and each entry's 100 $d as its dates"
    (is (= 29 (count (filter :dates (:pool @c)))) "four headings state no $d")
    (is (= {"11901063" (dates/parse-life-dates "1802-1870") "11901062" (dates/parse-life-dates "1824-1895")}
           (into {} (for [e (:pool @c) :when (#{"11901063" "11901062"} (:id e))] [(:id e) (:dates e)]))))))

(deftest a-mention-s-dates-are-its-field-s-d
  (let [{:keys [mentions]} @c]
    (is (= 131 (count (filter #(dates/dated? (:dates %)) mentions))) "11 mentions state no $d")
    (is (every? (fn [m] (= (:dates m) (dates/parse-life-dates (some (fn [[k v]] (when (= "d" k) v)) (:subfields m)))))
                mentions))
    (is (= {(dates/parse-life-dates "1802-1870") 35 (dates/parse-life-dates "1824-1895") 1 nil 1}
           (frequencies (map :dates (filter #(= "Dumas, Alexandre" (:name %)) mentions))))
        "the 36 Alexandre Dumas mentions with a $3 carry their dates; the undetermined one has none")))

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

(deftest the-date-aware-matcher-sees-the-dates-and-no-other-gold
  (let [calls (atom [])
        spy   (fn [ms entries]
                (swap! calls conj [ms entries])
                (reconcile/agent-candidates ms entries))
        res   (pr/task-a-dated @c spy)
        gold  (set (remove (set (for [m (:mentions @c) [code v] (:subfields m) :when (= "d" code)] (str/trim v)))
                           (gold-strings @c)))
        texts (distinct (concat (map :name (mapcat first @calls))
                                (for [[_ es] @calls e es t (cons (:label e) (:variants e))] t)))]
    (testing "one mention per call, as {:name :dates}; the result is what the default matcher returns"
      (is (= 142 (count @calls)))
      (is (every? #(and (= 1 (count (first %))) (= #{:name :dates} (set (keys (ffirst %))))) @calls))
      (is (= (:all (pr/task-a-dated @c)) (:all res))))
    (testing "the entries carry :id :label :variants, and :dates when the authority states any, nothing else"
      (is (every? #(= (cond-> #{:id :label :variants} (:dates %) (conj :dates)) (set (keys %))) (mapcat second @calls)))
      (is (= 29 (count (filter :dates (second (first @calls)))))))
    (testing "the dates are the parsed $d of the mention and of the authority's 100, nothing else"
      (is (= (set (map :dates (:mentions @c))) (set (map :dates (mapcat first @calls)))))
      (is (= (set (map :dates (:pool @c))) (set (map :dates (second (first @calls)))))))
    (testing "no $3, $1, ISNI or authority number appears in a name, a label or a variant"
      (is (some #{"11901063"} gold))
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

(def ^:private ad (delay (pr/task-a-dated @c)))

(deftest task-a-with-dates
  (pr/print-task-a @ad)
  (testing "all 142 mentions: no false link, no tie, whatever the pool order"
    (let [after (summary {:mentions 142 :in-pool 136 :correct 136 :at5 136 :not-in-pool 5 :not-in-pool-proposed 2
                          :undetermined 1 :recall-1 1 :recall-5 1 :precision-1 68/69})]
      (is (= after (:all @ad)))
      (is (= after (:all (pr/task-a-dated (update @c :pool reverse)))))))
  (testing "per family: Dumas goes from 15/50 to 50/50; the others do not move"
    (is (= {"Dumas"  (summary {:mentions 51 :in-pool 50 :correct 50 :at5 50 :undetermined 1
                               :recall-1 1 :recall-5 1 :precision-1 1})
            "Hetzel" (summary {:mentions 2 :in-pool 2 :correct 2 :at5 2 :recall-1 1 :recall-5 1 :precision-1 1})
            "Hugo"   (summary {:mentions 8 :in-pool 8 :correct 8 :at5 8 :recall-1 1 :recall-5 1 :precision-1 1})
            "Verne"  (summary {:mentions 29 :in-pool 29 :correct 29 :at5 29 :recall-1 1 :recall-5 1 :precision-1 1})
            "others" (summary {:mentions 52 :in-pool 47 :correct 47 :at5 47 :not-in-pool 5 :not-in-pool-proposed 2
                               :recall-1 1 :recall-5 1 :precision-1 47/49})}
           (:by-family @ad))))
  (testing "166 candidates rejected for their dates, on 93 mentions; never a mention's own authority"
    (is (= {:mentions-with-rejections 93 :rejected 166 :truth-rejected 0} (:rejections @ad))))
  (testing "Dumas père's mention: the son is rejected, with the births compared"
    (let [m (first (filter #(= "11901063" (:truth %)) (:mentions @ad)))
          r (first (filter #(= "11901062" (:authority-id %)) (:rejected m)))]
      (is (= ["11901063" 1.0] ((juxt :authority-id :score) (first (:proposals m)))))
      (is (= :incompatible-dates (:reason r)))
      (is (= {:compared [:birth :birth] :a {:from 1802 :to 1802 :text "1802"} :b {:from 1824 :to 1824 :text "1824"} :ok? false}
             (first (get-in r [:evidence :dates :checks]))))))
  (testing "what dates cannot change: the undetermined, undated Dumas mention still ties père and fils"
    (let [m (first (filter #(nil? (:truth %)) (:mentions @ad)))]
      (is (nil? (:dates m)))
      (is (= #{"11901062" "11901063"} (set (map :authority-id (take 2 (:proposals m))))))
      (is (:tied? m))))
  (testing "nor the mentions outside the pool: undated, Paltsits is still linked to Victor Hugo"
    (is (= #{["Paltsits, Victor Hugo" "11907966" nil]}
           (set (for [m (:mentions @ad) :when (= :not-in-pool/proposed (:outcome m))]
                  [(:name m) (:authority-id (first (:proposals m))) (:dates m)]))))))

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
           (:top-1 @b))))
  (testing "with dates (M3): père and fils apart; only the two Paltsits x Victor Hugo pairs remain"
    (is (= {:pairwise {:predicted 1116 :true 1114 :right 1114 :false-merges 2 :missed 0
                       :precision 557/558 :recall 1 :f1 1114/1115}
            :b-cubed  {:precision 419/423 :recall 1}}
           (:top-1-dated @b))))
  (testing "with the Fellegi-Sunter score (M4): no false merge; the two Paltsits, abstained, are no longer joined"
    (is (= {:pairwise {:predicted 1113 :true 1114 :right 1113 :false-merges 0 :missed 1
                       :precision 1 :recall 1113/1114 :f1 2226/2227}
            :b-cubed  {:precision 1 :recall 140/141}}
           (:top-1-fs @b)))))

;; ---------------------------------------------------------------------------
;; Lot M4 — a Fellegi-Sunter score, its calibration, and a stress set
;; ---------------------------------------------------------------------------

(def ^:private fsr (delay (pr/fs-run @c)))

(defn- strings-in [x] (filter string? (tree-seq coll? seq x)))

(deftest the-fs-matcher-never-sees-the-gold
  (let [calls  (atom [])
        models (:models @fsr)
        spy    (fn [ms entries opts]
                 (swap! calls conj [ms entries opts])
                 (reconcile/agent-candidates ms entries opts))
        res    (pr/task-a-fs @c (comp models :family) spy)
        gold   (set (remove (set (for [m (:mentions @c) [code v] (:subfields m) :when (= "d" code)] (str/trim v)))
                            (gold-strings @c)))
        texts  (distinct (concat (map :name (mapcat first @calls))
                                 (for [[_ es] @calls e es t (cons (:label e) (:variants e))] t)))]
    (testing "one mention per call, as {:name :dates}, the dated entries, and a model; the result is the default's"
      (is (= 142 (count @calls)))
      (is (every? #(and (= 1 (count (first %))) (= #{:name :dates} (set (keys (ffirst %))))) @calls))
      (is (every? #(= (cond-> #{:id :label :variants} (:dates %) (conj :dates)) (set (keys %))) (mapcat second @calls)))
      (is (every? #(= #{:model} (set (keys (nth % 2)))) @calls))
      (is (= (:all (:task-a @fsr)) (:all res))))
    (testing "no $3, $1, ISNI or authority number appears in a name, a label or a variant"
      (is (some #{"11901063"} gold))
      (is (empty? (for [t texts g gold :when (str/includes? t g)] [t g]))))
    (testing "a comparison is levels, a score and the surname: no id, no record, no ISNI is a feature"
      (is (empty? (remove #(set/subset? (set (keys (:cmp %))) #{:levels :name-score :dates :surname :surname-u :tf-mean}) (:pairs (:data @fsr)))))
      (is (every? (fn [p] (every? (set (mapcat val fs/features)) (vals (dissoc (get-in p [:cmp :levels]) :dates))))
                  (:pairs (:data @fsr)))))
    (testing "a model is numbers and levels: the only strings it holds are the names of the families it was trained on"
      (doseq [[fam m] models]
        (is (= (set (get-in m [:estimation :trained-on])) (set (strings-in (dissoc m :calibration)))))
        (testing "and leave-one-family-out: a mention is never scored by a model trained on its family"
          (is (not (some #{fam} (get-in m [:estimation :trained-on])))))))))

(defn- r4 [x] (/ (Math/round (* 1e4 (double x))) 1e4))

(deftest task-a-with-a-fellegi-sunter-score
  (let [a (:task-a @fsr)]
    (pr/print-fs @fsr)
    (testing "all 142 mentions, each scored by its held-out family model: every in-pool top proposal right, every out-of-pool one abstained"
      (is (= (summary {:mentions 142 :in-pool 136 :correct 136 :at5 136 :not-in-pool 5 :undetermined 1
                       :recall-1 1 :recall-5 1 :precision-1 1})
             (:all a))))
    (testing "per family"
      (is (= {"Dumas"  (summary {:mentions 51 :in-pool 50 :correct 50 :at5 50 :undetermined 1 :recall-1 1 :recall-5 1 :precision-1 1})
              "Hetzel" (summary {:mentions 2 :in-pool 2 :correct 2 :at5 2 :recall-1 1 :recall-5 1 :precision-1 1})
              "Hugo"   (summary {:mentions 8 :in-pool 8 :correct 8 :at5 8 :recall-1 1 :recall-5 1 :precision-1 1})
              "Verne"  (summary {:mentions 29 :in-pool 29 :correct 29 :at5 29 :recall-1 1 :recall-5 1 :precision-1 1})
              "others" (summary {:mentions 52 :in-pool 47 :correct 47 :at5 47 :not-in-pool 5 :recall-1 1 :recall-5 1 :precision-1 1})}
             (:by-family a))))
    (testing "the matcher abstains: the 5 mentions whose authority is not in the pool, Paltsits included, get no proposal"
      (is (= #{"Rafina, Gesner" "Paltsits, Victor Hugo" "Hayat, Eric" "Harispe, Patrice"}
             (set (for [m (:mentions a) :when (= :not-in-pool/abstained (:outcome m))] (:name m)))))
      (is (every? (fn [m] (every? #(< (:score %) 0.5) (:abstained m)))
                  (filter #(= :not-in-pool/abstained (:outcome %)) (:mentions a)))))
    (testing "26 top proposals are for review, every one because another candidate of its mention is above 0.5"
      (is (= 26 (:review a)))
      (is (every? #(= :competing (:review-reason (first (:proposals %)))) (filter #(= :review (:decision %)) (:mentions a))))
      (is (= {"Férat, Jules" 20} (select-keys (frequencies (map :name (filter #(= :review (:decision %)) (:mentions a)))) ["Férat, Jules"]))
          "Jules Férat (1829-1906) against Jules Verne (1828-1905): dates agree, the surnames differ")
      (testing "the undetermined, undated Dumas: père and fils tied, both for review"
        (let [m (first (filter #(nil? (:truth %)) (:mentions a)))]
          (is (= [:review :review] (map :decision (:proposals m))))
          (is (apply = (map :score (:proposals m))))
          (is (:tied? m)))))
    (testing "an undated exact heading is not abstained for being undated: the 5 undated in-pool mentions are proposed outright"
      (is (= #{:propose} (set (for [m (:mentions a) :when (and (:truth m) (nil? (:dates m)) (= :correct (:outcome m)))]
                                (:decision m))))))
    (testing "counting only outright proposals as links: 111 right, 25 abstained, still no false link"
      (is (= (summary {:mentions 142 :in-pool 136 :correct 111 :at5 111 :abstained 25 :not-in-pool 5 :undetermined 1
                       :recall-1 111/136 :recall-5 111/136 :precision-1 1})
             (:all (:outright a))))
      (is (= {"Dumas" [50 0] "Hetzel" [1 1] "Hugo" [8 0] "Verne" [29 0] "others" [23 24]}
             (update-vals (:by-family (:outright a)) (juxt :correct :abstained)))))
    (testing "at most one outright proposal per mention"
      (is (every? #(<= (count (filter (comp #{:propose} :decision) (:proposals %))) 1) (:mentions a))))
    (testing "the dates still block, never weigh: 577 candidates rejected, never a mention's own authority"
      (is (= {:mentions-with-rejections 117 :rejected 577 :truth-rejected 0} (:rejections a))))))

(deftest the-held-out-calibration-misses-the-bar-so-no-confidence-is-written
  (let [{:keys [calibration models task-a fold-reports]} @fsr
        pool (pr/dated-entries (:pool @c))]
    (testing "351 held-out predictions, but 125 distinct (mention, candidate) pairs and 33 distinct true mentions"
      (is (= {:n 351 :distinct-pairs 125 :positives 136 :distinct-positives 33 :review-pairs 7}
             (select-keys calibration [:n :distinct-pairs :positives :distinct-positives :review-pairs])))
      (is (= [0.036 0.1082 0.7401] (map (comp r4 calibration) [:brier :ece :max-bin-gap]))))
    (testing "the reliability table, as measured"
      (is (= [[[0.0 0.1] 113 0.0697 0.0] [[0.1 0.2] 53 0.1285 0.0] [[0.3 0.4] 23 0.3716 0.0] [[0.5 0.6] 24 0.5397 0.0]
              [[0.6 0.7] 1 0.6517 0.0] [[0.7 0.8] 1 0.7401 0.0] [[0.9 1.0] 136 0.9969 1.0]]
             (map (juxt :bin :n (comp r4 :mean-p) (comp r4 :observed)) (:bins calibration)))))
    (testing "the report names the pool and the gold it was measured on"
      (is (= (fs/pool-fingerprint pool) (:pool calibration)))
      (is (= (pr/gold-fingerprint @c) (:gold calibration))))
    (testing "the bar is missed — too few distinct true mentions, too high an ECE and bin gap, the review band empty"
      (is (= fs/default-bar (:bar calibration)))
      (is (false? (:meets-bar? calibration)))
      (is (false? (:review-covered? calibration)))
      (is (not-any? #(fs/calibrated? % pool) (vals models))))
    (testing "so no proposal states a confidence"
      (is (= 164 (count (mapcat :proposals (:mentions task-a)))))
      (is (not-any? #(contains? % :confidence) (mapcat :proposals (:mentions task-a)))))
    (testing "per fold, no report meets it either; one pooled report is what is attached"
      (is (= {"Dumas" [17 6] "Hetzel" [3 2] "Hugo" [12 3] "Verne" [3 2] "others" [90 20]}
             (update-vals fold-reports (juxt :distinct-pairs :distinct-positives))))
      (is (not-any? :meets-bar? (vals fold-reports)))
      (is (every? #(= calibration (:calibration %)) (vals models))))))

(deftest the-supervised-weights-and-the-term-frequency-adjustment
  (let [data (:data @fsr)
        full (pr/supervised-model data (set (conj pr/families "others")))
        w    (fn [f l] (r4 (get-in full [:weights f l])))]
    (testing "u from the 1 056 ordered pool pairs (264 the dates do not block, for the dates)"
      (is (= {:all 1056 :unblocked 264} (:u-pairs data))))
    (testing "every true pair is an exact heading"
      (is (= {{:name :exact :form :heading :dates :agree} 131 {:name :exact :form :heading :dates :unknown} 5}
             (frequencies (for [p (:pairs data) :when (= (:truth p) (:id p))] (get-in p [:cmp :levels]))))))
    (testing "only what was measured weighs: an exact heading, seen twice among different persons, weighs its bound log2(1 / (5/1056))"
      (is (= 7.7225 (w :name :exact)))
      (is (= (repeat 7 0.0) (map #(w :name %) [:initials :same-tokens :surname-typo :forename-overlap :surname-only
                                               :forenames-differ :jaccard-high])))
      (is (= [-1.911 -4.4626] (map #(w :name %) [:jaccard-low :none])) "bounds: m <= 5/136")
      (is (= [0.0193 0.0] (map #(w :form %) [:heading :variant])))
      (is (= [3.6684 0.0 0.0] (map #(w :dates %) [:agree :compatible :unknown])) "unknown 0 by rule"))
    (testing "the name weights never increase along the order of agreement, and no disagreement is evidence for"
      (is (apply >= (map #(get-in full [:weights :name %]) fs/name-levels)))
      (is (every? #(<= (get-in full [:weights :name %]) 0.0) fs/disagreeing-levels)))
    (testing "the surname adjustment of the M2 pool: u-mean 2/34; 3 headings 0.63 less, one 0.96 more"
      (let [pool   (pr/dated-entries (:pool @c))
            counts (fs/surname-counts pool)
            tf     (fn [nm] (r4 (get-in (fs/score full (fs/compare-pair {:name nm} {:label nm} {:surname-counts counts :pool-size 33}))
                                        [:weights :tf])))]
        (is (= 0.0588 (r4 (fs/tf-mean counts 33))))
        (is (= [-0.628 0.9569] [(tf "Dumas, Alexandre") (tf "Schlienger, Paul")]))))))

(deftest initials-and-different-forenames-are-ordered-and-never-evidence-for
  (let [m  (get (:models @fsr) "Hugo")
        es (pr/dated-entries (:pool @c))
        by (fn [nm] (into {} (for [x (let [r (reconcile/agent-candidates [{:name nm}] es {:model m})] (concat (:proposals r) (:abstained r)))]
                               [(:authority-label x) x])))]
    (testing "\"Hugo, V.\": Victor (initials) weighs no less than Georges-Victor (different forenames), which weighs nothing for"
      (let [h (by "Hugo, V.")]
        (is (= :initials (get-in h ["Hugo, Victor" :evidence :comparison :name])))
        (is (= :forenames-differ (get-in h ["Hugo, Georges-Victor" :evidence :comparison :name])))
        (is (>= (:match-weight (h "Hugo, Victor")) (:match-weight (h "Hugo, Georges-Victor"))))
        (is (<= (get-in h ["Hugo, Georges-Victor" :evidence :weights :name]) 0.0))
        (is (not-any? #(= :propose (:decision %)) (vals h)) "neither is proposed outright on initials alone")))
    (testing "same surname, different forenames: \"Dumas, Adolphe\" gets Adolphe, never Alexandre"
      (let [h (by "Dumas, Adolphe")]
        (is (= :propose (get-in h ["Dumas, Adolphe" :decision])))
        (is (= :forenames-differ (get-in h ["Dumas, Alexandre" :evidence :comparison :name])))
        (is (= 0.0 (get-in h ["Dumas, Alexandre" :evidence :weights :name])))
        (is (= 0.0 (get-in h ["Dumas, Alexandre" :evidence :weights :tf])) "a common surname adds nothing to a disagreement")
        (is (= :abstain (get-in h ["Dumas, Alexandre" :decision])))))))

(deftest em-without-labels-for-comparison
  (let [em (pr/em-model (:data @fsr) (count (:pool @c)))
        a  (pr/task-a-fs @c (constantly em))
        k  (fs/calibration (:predictions a))]
    (testing "EM converges in 36 steps, to a prior of 0.162; its weights obey the same rules"
      (is (= 36 (get-in em [:estimation :iterations])))
      (is (= 0.1619 (r4 (:prior em))))
      (is (= 7.3511 (r4 (get-in em [:weights :name :exact]))))
      (is (= 0.0 (get-in em [:weights :dates :unknown]))))
    (testing "the same top proposals as the supervised model, fewer outright"
      (is (= (:all (:task-a @fsr)) (:all a)))
      (is (= [88 48 0] ((juxt :correct :abstained :false-links) (:all (:outright a))))))
    (testing "and worse calibrated"
      (is (= [0.0832 0.1776] [(r4 (:brier k)) (r4 (:ece k))]))
      (is (false? (:meets-bar? k))))))

(deftest a-synthetic-stress-set-measures-candidate-recall
  (testing "SYNTHETIC: each distinct gold heading perturbed five ways"
    (is (= {:initials "Hetzel, P.-J." :dropped-forename "Hetzel" :reversed "Pierre-Jules Hetzel" :typo "Htezel, Pierre-Jules"}
           (pr/stress-variants "Hetzel, Pierre-Jules")))
    (is (= "Perez, Victor" (:accents-removed (pr/stress-variants "Pérez, Víctor"))))
    (is (= {:initials 33 :dropped-forename 33 :reversed 33 :accents-removed 11 :typo 33}
           (frequencies (map :kind (pr/stress-set @c))))))
  (let [s (pr/stress-recall @c (comp (:models @fsr) :family))]
    (testing "the truth among the candidates: M3's Jaccard >= 0.5 lets 97 of 143 through, the model's blocking all"
      (is (= {:n 143 :m3-candidate 97 :fs-candidate 143 :fs-top-1 127 :fs-outright 10} (:all s)))
      (is (= {:initials         {:n 33 :m3-candidate 7  :fs-candidate 33 :fs-top-1 29 :fs-outright 1}
              :dropped-forename {:n 33 :m3-candidate 29 :fs-candidate 33 :fs-top-1 29 :fs-outright 1}
              :reversed         {:n 33 :m3-candidate 33 :fs-candidate 33 :fs-top-1 29 :fs-outright 0}
              :accents-removed  {:n 11 :m3-candidate 11 :fs-candidate 11 :fs-top-1 11 :fs-outright 8}
              :typo             {:n 33 :m3-candidate 17 :fs-candidate 33 :fs-top-1 29 :fs-outright 0}}
             (dissoc s :all))))))

;; ---------------------------------------------------------------------------
;; Lot M5 — constrained clustering
;; ---------------------------------------------------------------------------

(def ^:private bc (delay (pr/task-b-constrained @c)))

(deftest task-b-with-constrained-clustering
  (pr/print-task-b-constrained @bc)
  (is (= 141 (:mentions @bc)))
  (testing "the inputs: 111 outright proposals, 51 for review, 577 date rejections as cannot-links"
    (is (= {:propose 111 :review 51} (:links @bc)))
    (is (= 577 (:cannot-link @bc))))
  (testing "M4's top-1 rule, for reference (task-b-baseline's :top-1-fs)"
    (is (= (:top-1-fs @b) (:top-1-fs @bc))))
  (testing "under the policy (only outright proposals join), the closure and the constrained clustering agree:
            a mention has at most one outright proposal, so no chain forms; the 25 mentions for review stay alone"
    (let [expected {:pairwise {:predicted 923 :true 1114 :right 923 :false-merges 0 :missed 191
                               :precision 1 :recall 923/1114 :f1 1846/2037}
                    :b-cubed  {:precision 1 :recall 121/141}}]
      (is (= (assoc expected :violations 0) (:closure @bc)))
      (is (= (assoc expected :violations 0 :refused 0 :candidates 51) (:constrained @bc)))))
  (testing "letting every proposal for review join: the naive closure chains Férat to Verne, Stahl to Hetzel,
            and three \"Víctor Hugo\" authors, through their competing candidates — 464 false merges, 8 broken constraints"
    (is (= {:pairwise {:predicted 1577 :true 1114 :right 1113 :false-merges 464 :missed 1
                       :precision 1113/1577 :recall 1113/1114 :f1 742/897}
            :b-cubed  {:precision 5014/6063 :recall 140/141}
            :violations 8}
           (:closure-review @bc))))
  (testing "the constrained clustering of the same links refuses the 26 weaker ones, and gives M4's top-1 numbers"
    (is (= {:pairwise {:predicted 1113 :true 1114 :right 1113 :false-merges 0 :missed 1
                       :precision 1 :recall 1113/1114 :f1 2226/2227}
            :b-cubed  {:precision 1 :recall 140/141}
            :violations 0 :refused 26 :candidates 0}
           (:constrained-review @bc)))))

(deftest a-synthetic-chain-is-merged-by-the-closure-and-not-by-the-constrained-clustering
  (let [data  (pr/fs-fit-data @c)
        model (pr/supervised-model data (set (conj pr/families "others")))
        cases (pr/chain-cases @c model)]
    (testing "SYNTHETIC: the 9 pairs of same-surname authorities whose dates are incompatible"
      (is (= [["11901062" "11901063"] ["11901062" "12459839"] ["11901063" "12459839"]
              ["11907962" "11907966"] ["11907962" "14940601"] ["11907966" "14940601"]
              ["11928016" "12644615"] ["12008284" "14616004"] ["12112416" "13001793"]]
             (map (juxt :x :y) cases))))
    (testing "every one is a chain mx ~ U ~ my, both proposed outright at the same weight"
      (is (every? #(and (= [:propose :propose] (map :basis (get-in % [:input :links])))
                        (apply = (map :weight (get-in % [:input :links]))))
                  cases)))
    (is (= {:cases 9 :chains 9 :closure-merges 9 :constrained-merges 0 :refused 9 :refused-on-a-tie 9 :bridge-with {:mx 9}}
           (pr/chain-summary cases))
        "the closure merges X's and Y's mentions every time, the constrained clustering never; each refusal is a tie, won by the lower id, and says so")))

;; ---------------------------------------------------------------------------
;; Lot M7 — typed events
;; ---------------------------------------------------------------------------

(def ^:private te (delay (pr/task-a-events @c)))

(deftest a-bibliographic-record-gives-edition-events-and-no-new-evidence
  (testing "each of the 142 mentions carries its record's 260 $d, a :publication: 135 of them dated"
    (is (= {:mentions 142 :events 142 :dated 135 :posthumous 62} (:events @te)))
    (is (= #{:publication} (set (map :kind (mapcat :events (pr/with-edition-events @c)))))))
  (testing "with the dates and the events, task A is M3's: the events reject nothing, and no truth"
    (is (= (:all (pr/task-a-dated @c)) (:all (:with-dates @te))))
    (is (= {:mentions-with-rejections 93 :rejected 166 :truth-rejected 0} (:rejections (:with-dates @te))))
    (is (= {:rejected 0 :truth-rejected 0} (:event-rejections (:with-dates @te)))))
  (testing "the events alone reject nothing: the name-only numbers"
    (is (= (:all (pr/task-a @c)) (:all (:events-only @te))))
    (is (= {:rejected 0 :truth-rejected 0} (:event-rejections (:events-only @te)))))
  (testing "what-if: an edition read as the author's creation; since only a creation long before a birth blocks, it would block no mention's own authority (62 editions are posthumous), only 20 other candidates"
    (is (= {:rejected 20 :truth-rejected 0} (:event-rejections (:as-creation @te))))
    (is (= 101 (get-in @te [:as-creation :all :correct])) "the name-only baseline's recall@1")))

(deftest a-synthetic-set-of-events-on-real-authorities
  (testing "SYNTHETIC: on the 21 pool authorities that state a birth and a death, one event per mention, no dates"
    (is (= {:creation-after-death       [21 0 0]
            :creation-before-birth      [21 21 21]
            :creation-just-before-birth [21 0 0]
            :creation-in-life           [21 0 0]
            :posthumous-edition         [21 0 0]
            :edition-before-birth       [21 0 0]}
           (pr/event-case-summary (pr/event-cases @c)))
        "[cases, authority rejected, cannot-link]: a creation 30 years before the birth is blocked, and a cannot-link; one 8 years before (within the 10-year margin), one after death (a cast, a reissue) and an edition never")))

(deftest the-actors-of-lido-records-against-their-ulan-ids
  (let [recs (:records (oai/import-response "lido" (slurp "test/fixtures/documentary/lido/ycba-oai-lido.xml")))
        run  (pr/lido-event-run recs)]
    (testing "the five YCBA records: 8 person occurrences with a ULAN id, 8 ULAN persons, all dated"
      (is (= {:mentions 8 :pool 8 :dated-pool 8 :creations 4} (select-keys run [:mentions :pool :dated-pool :creations])))
      (is (= {:creation 4 :attribution 2 :influence 1 :activity 1} (:kinds run))))
    (testing "no rejection by events, and no truth blocked"
      (is (= {:rejected 0 :truth-rejected 0 :by-kind {}} (:run run))))))


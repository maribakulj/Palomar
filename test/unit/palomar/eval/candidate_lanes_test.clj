(ns palomar.eval.candidate-lanes-test
  "Candidate generation per lane on the benchmarks (lot N5). First: with
   the name lane alone, generation then ranking gives every M2–M6 result
   exactly, mention by mention, so no number of the eval moves. Then the
   recall of each lane and of their union, and the cost. Measurements, not
   guarantees; write-up in `docs/eval/person-reconciliation.md`, section
   N5."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.agent-equivalence :as eq]
            [palomar.authority-index :as index]
            [palomar.candidates :as candidates]
            [palomar.eval.candidate-lanes :as cl]
            [palomar.eval.gnd-person-reconciliation-test :as gnd]
            [palomar.eval.person-reconciliation :as pr]
            [palomar.plugins.oai :as oai]
            [palomar.reconcile :as reconcile]))

(def ^:private c (delay (pr/corpus)))

(def ^:private gnd-idx
  (delay (let [f (doto (java.io.File/createTempFile "palomar-lanes-eval-" ".edn") (.deleteOnExit))]
           (index/build! gnd/extract f {:built "2026-11-09"})
           (index/read-index f))))

(defn- strip
  "A ranking result without what generation adds: the lanes of each
   candidate and the per-mention generation report."
  [r]
  (-> (dissoc r :generation)
      (update-vals (fn [xs] (mapv #(dissoc % :generated-by) xs)))))

(defn- name-lane
  "`reconcile/agent-candidates`' signature, through generation by the name
   lane alone, then ranking."
  ([ms es] (strip (candidates/rank ms es {:lanes [:name]})))
  ([ms es opts] (strip (candidates/rank ms es (assoc opts :lanes [:name])))))

;; ---------------------------------------------------------------------------
;; The name lane alone changes nothing
;; ---------------------------------------------------------------------------

(deftest the-name-lane-alone-gives-the-m2-to-m4-results-exactly
  (testing "M2 name-only (propose-agent-links) and M3 (dates): every mention's result"
    (is (= (pr/task-a @c) (pr/task-a @c (fn [ns es] (:proposals (name-lane ns es))))))
    (is (= (pr/task-a-dated @c) (pr/task-a-dated @c name-lane))))
  (testing "M4: every mention scored by its held-out family model"
    (let [models (pr/fold-models (pr/fs-fit-data @c))]
      (is (= (pr/task-a-fs @c (comp models :family)) (pr/task-a-fs @c (comp models :family) name-lane))))))

(deftest the-name-lane-alone-gives-the-m6-results-exactly
  (let [model (pr/supervised-model (pr/fs-fit-data @c) (set (conj pr/families "others")))
        ci    (candidates/index @gnd-idx)
        es    (:entries @gnd-idx)
        old   (for [m (:mentions @c)]
                (reconcile/agent-candidates [(select-keys m [:name :dates])] es {:model model :block (index/block @gnd-idx)}))
        new   (for [m (:mentions @c)]
                (candidates/rank [(select-keys m [:name :dates])] es {:model model :lanes [:name] :index ci}))]
    (is (= 142 (count new)))
    (is (= old (map strip new)) "proposals, reviews, abstentions and rejections, mention by mention")
    (testing "and the lanes' provenance is the name lane's, for every candidate"
      (is (every? #(= [:name] (map :lane (:generated-by %))) (mapcat #(concat (:proposals %) (:abstained %) (:rejected %)) new)))
      (is (= 4764 (reduce + (map #(count (:candidates (first (:generation %)))) new)))
          "the block's 4 764 (mention, entry) pairs"))))

;; ---------------------------------------------------------------------------
;; Recall per lane
;; ---------------------------------------------------------------------------

(defn- row
  "What the table reports of a recall: the judged mentions (truth in the
   pool); per lane and for the union, the truths found and the candidates
   over every mention (the cost); the judged truths one lane alone finds."
  [r]
  {:in-index (:in-index r)
   :name     ((juxt :found :candidates) (get-in r [:lanes :name]))
   :id       ((juxt :found :candidates) (get-in r [:lanes :identifier]))
   :union    ((juxt :found :candidates) (:union r))
   :only     (:only r)})

(defn- report-kinds [sets] (frequencies (map :kind (mapcat :reports sets))))

(deftest recall-per-lane-on-m2
  (let [r (cl/m2-recall @c)]
    (cl/print-recall "M2 BnF" r)
    (testing "the name lane finds every truth in the pool; the ISNI $1 finds 135 of 136, one candidate each"
      (is (= {:in-index 136 :name [136 935] :id [135 135] :union [136 935] :only {:name 1 :identifier 0}} (row r))))
    (testing "every ISNI a field states names its authority: no report"
      (is (= {} (report-kinds (candidates/candidate-sets (cl/lane-entries (:pool @c)) (map cl/lane-mention (:mentions @c))
                                                         [:identifier])))))))

(deftest recall-per-lane-on-m6
  (let [t    (gnd/truth (:entries @gnd-idx))
        r    (cl/gnd-recall @c @gnd-idx t)
        sets (candidates/candidate-sets (candidates/index @gnd-idx) (map cl/lane-mention (:mentions @c)) [:identifier])]
    (cl/print-recall "M6 GND" r)
    (testing "the name lane finds the 110 bridged truths; the ISNI 90 of them, at 92 candidates"
      (is (= {:in-index 110 :name [110 4764] :id [90 92] :union [110 4764] :only {:name 20 :identifier 0}} (row r))))
    (testing "the 2 identifier candidates that are no bridged truth: Hetzel's and Stahl's crossed ISNIs"
      (is (= #{["Hetzel, Pierre-Jules" "https://d-nb.info/gnd/111910582X"] ["Stahl, P.-J." "https://d-nb.info/gnd/118949047"]}
             (set (for [[m s] (map vector (:mentions @c) sets)
                        id (get-in s [:lanes :identifier])
                        :when (not (some #{id} (cl/gnd-truth t m)))]
                    [(:name m) id])))))
    (testing "no id names two GND records; 43 name none of the extract"
      (is (= {:no-authority 43} (report-kinds sets))))))

(deftest recall-per-lane-on-the-synthetic-sets
  (testing "SYNTHETIC: the stress set: the name lane finds every truth, the ISNI the 138 variants whose gold carries one"
    (let [r (update-vals (cl/stress-recall @c) row)]
      (is (= {:in-index 143 :name [143 792] :id [138 138] :union [143 792] :only {:name 5 :identifier 0}} (:all r)))
      (is (= {:initials [33 134] :dropped-forename [33 100] :reversed [33 233] :accents-removed [11 95] :typo [33 230]}
             (update-vals (dissoc r :all) :name)))))
  (testing "SYNTHETIC: the gold headings in Cyrillic letters: the identifier lane brings 30 truths the name lane misses"
    (let [r (cl/script-recall @c)]
      (is (= {:in-index 33 :name [2 14] :id [32 32] :union [32 44] :only {:name 0 :identifier 30}} (row r)))
      (is (= #{"Хуго, Виктор" "Верне, Йулес"} (set (for [x (:per-mention r) :when (get-in x [:found :name])] (:name (:mention x)))))
          "Hugo and Verne: their BnF records hold a Cyrillic variant (Виктор, Верне); the ISNI finds them too")))
  (let [model (pr/supervised-model (pr/fs-fit-data @c) (set (conj pr/families "others")))
        es    (cl/lane-entries (:pool @c))
        ci    (candidates/index es)
        out   (for [m (cl/script-set @c)
                    :let [r (candidates/rank [(cl/lane-mention m)] es {:model model :lanes [:name :identifier] :index ci})
                          t (some #(when (= (:truth m) (:authority-id %)) %) (concat (:proposals r) (:abstained r)))]]
                (if t [(:decision t) (:review-reason t)] :truth-not-weighed))]
    (testing "SYNTHETIC: ranked, every truth an id brings is weighed on its names and dates and abstained: no id makes a link"
      (is (= {[:abstain nil] 32 :truth-not-weighed 1} (frequencies out))
          "the one not weighed has no ISNI and no name key: no lane finds it"))
    (testing "SYNTHETIC: Verne in Cyrillic (probe p3): a name key finds him at name level :none, his ISNI too; he is weighed, not hidden"
      (let [m (first (filter #(= "Верне, Йулес" (:name %)) (cl/script-set @c)))
            r (candidates/rank [(cl/lane-mention m)] es {:model model :lanes [:name :identifier] :index ci})
            v (first (filter #(= (:truth m) (:authority-id %)) (:abstained r)))]
        (is (= [:name :identifier] (mapv :lane (:generated-by v))))
        (is (= :none (get-in v [:evidence :comparison :name])))))))

(deftest recall-per-lane-on-lido
  (let [recs (:records (oai/import-response "lido" (slurp "test/fixtures/documentary/lido/ycba-oai-lido.xml")))]
    (testing "M7's YCBA actors: both lanes find all 8, the identifier lane by construction (the pool is their ULAN ids)"
      (is (= {:in-index 8 :name [8 12] :id [8 8] :union [8 12] :only {:name 0 :identifier 0}} (row (cl/lido-recall recs)))))))

;; ---------------------------------------------------------------------------
;; Leakage: no gold-derived id reaches a lane or the matcher (N5 review)
;; ---------------------------------------------------------------------------

(defn- gold-of
  "What is derived from the gold of corpus `c`: each `$3` record number, the
   ARK it names, that ARK's key."
  [c]
  (let [ns (set (keep :truth (:mentions c)))]
    {:numbers ns
     :arks    (set (mapcat (fn [n] (let [a (eq/bnf-ark n)] [a (eq/identifier-key a)])) ns))
     :cb      (set (map #(str "cb" %) ns))}))

(defn- leaks
  "The gold-derived strings, and the gold-bearing keys, of the mentions
   `ms` passed to a lane or the matcher."
  [{:keys [numbers arks cb]} ms]
  (vec (distinct (concat
                  (for [m ms :when (map? m) k [:authority-record :truth :subfields :source] :when (contains? m k)] [:key k])
                  (for [x (tree-seq coll? seq ms)
                        :when (string? x)
                        :when (or (numbers x) (arks x) (some #(str/includes? x %) cb))]
                    [:string x])))))

(deftest no-gold-derived-id-reaches-a-lane-or-the-matcher
  (let [gold  (gold-of @c)
        seen  (atom [])
        ;; the mentions: the first argument of the matcher and of rank, the second of a lane run
        spy   (fn [f pick] (fn [& args] (swap! seen conj (pick args)) (apply f args)))
        ac    reconcile/agent-candidates
        rk    candidates/rank
        gen   candidates/generate]
    (testing "the check can fail: a $3, its ARK, or a mention's authority record is seen"
      (is (seq (leaks gold [{:name "Hugo, Victor" :ids ["http://catalogue.bnf.fr/ark:/12148/cb11907966z"]}])))
      (is (seq (leaks gold [{:name "Hugo, Victor" :ids ["11907966"]}])))
      (is (seq (leaks gold [{:name "Hugo, Victor" :authority-record "x"}])))
      (is (empty? (leaks gold [{:name "Hugo, Victor" :ids ["ISNI0000000121200982"]}]))))
    (with-redefs [reconcile/agent-candidates (spy ac first)
                  candidates/rank            (spy rk first)
                  candidates/generate        (spy gen (comp vector second))]
      (let [fsr   (pr/fs-run @c)
            model (pr/supervised-model (pr/fs-fit-data @c) (set (conj pr/families "others")))
            es    (:entries @gnd-idx)]
        ;; M2–M4, the stress set, M7
        (pr/task-a @c)
        (pr/task-a-dated @c)
        (pr/stress-recall @c (comp (:models fsr) :family))
        (pr/task-a-events @c)
        ;; M8, every anchor setting
        (doseq [st pr/anchor-settings] (pr/task-a-relational @c st fsr))
        ;; M6, as its eval runs it
        (doseq [m (:mentions @c)]
          (reconcile/agent-candidates [(select-keys m [:name :dates])] es {:model model :block (index/block @gnd-idx)}))
        ;; N5: every lane run and the ranking of the script set
        (cl/m2-recall @c)
        (cl/gnd-recall @c @gnd-idx (gnd/truth es))
        (cl/stress-recall @c)
        (cl/script-recall @c)
        (doseq [m (cl/script-set @c)]
          (candidates/rank [(cl/lane-mention m)] (cl/lane-entries (:pool @c)) {:model model :lanes [:name :identifier]}))))
    (testing "every call was seen, and none passed a $3, its ARK, a truth or a field's subfields"
      (is (< 1000 (count @seen)))
      (is (= [] (leaks gold (apply concat @seen)))))))

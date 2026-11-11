(ns palomar.eval.person-reconciliation-hard-n4-test
  "Lot N4 on the hard benchmark (`docs/eval/person-reconciliation-hard.md`,
   section N4): the engines on the same candidates, the model recalibrated
   on N3 train, its thresholds chosen by cross-validation on train under
   the stated criterion (each condition on a synthetic case), the
   role feature, the calibration against the bar, the shipped model file
   and the leakage guards. Dev numbers are pinned; the test split is never
   scored here."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [palomar.agent-match :as fs]
            [palomar.api :as api]
            [palomar.eval.person-reconciliation :as pr]
            [palomar.eval.person-reconciliation-hard :as hard]
            [palomar.eval.person-reconciliation-hard-n4 :as n4]
            [palomar.eval.person-reconciliation-hard-test :as n3t]
            [palomar.reconcile :as reconcile]
            [palomar.reconciliation :as rc]))

(def ^:private bench (delay (hard/load-benchmark)))
(def ^:private ctx (delay (n4/pool-context (:pool @bench))))
(def ^:private fitted (delay (n4/n3-params @bench @ctx)))
(def ^:private n3 (delay (fs/model (:params @fitted))))
(def ^:private dev-pms (delay (n4/prepared @ctx (n4/split-mentions @bench :dev))))
(def ^:private dev-run (delay (n4/comparison @bench :dev {:shipped (n4/shipped-model) :n3 @n3})))

(defn- close? [x y] (< (Math/abs (- (double x) (double y))) 1e-9))

;; ---------------------------------------------------------------------------
;; The same candidates
;; ---------------------------------------------------------------------------

(deftest the-filter-keeps-every-pair-a-full-comparison-scores
  (let [{:keys [entries counts size]} @ctx
        opts {:surname-counts counts :pool-size size}]
    (doseq [m (take 60 (n4/split-mentions @bench :dev))
            :let [n    (n4/fs-name m)
                  full (vec (for [[i e] (map-indexed vector entries)
                                  :let [c (fs/compare-pair {:name n :dates nil} e opts)]]
                              {:id (:id e) :index i :cmp c}))]]
      (is (= (filterv #(not= :none (get-in % [:cmp :levels :name])) full) (n4/pair-comparisons @ctx n)) n)
      (is (every? #(= n4/none-levels (get-in % [:cmp :levels])) (filter #(= :none (get-in % [:cmp :levels :name])) full)) n))))

(deftest every-engine-reads-the-same-mentions-against-the-same-pool
  (let [{:keys [results]} @dev-run]
    (is (= #{:jaccard :names-dates :fs-shipped :fs-n3 :fs-n3-roles} (set (keys results))))
    (is (apply = (map #(mapv :id (:cases %)) (vals results))))
    (is (= (mapv :id (n4/split-mentions @bench :dev)) (mapv :id (get-in results [:jaccard :cases]))))))

(deftest a-decision-is-the-matchers
  (testing "the shipped model on these candidates decides as `agent-candidates` does in N3's run"
    (is (= (:fellegi-sunter-inverted n3t/expected-dev) (get-in @dev-run [:results :fs-shipped :all]))))
  (testing "two candidates at or above review send the mention to review"
    (is (= :review (:decision (n4/decide [{:id "a" :p 0.99} {:id "b" :p 0.6}] {:propose 0.95 :review 0.5}))))
    (is (= {:link {:authority-id "a" :score 0.99} :review? false :decision :propose}
           (n4/decide [{:id "a" :p 0.99} {:id "b" :p 0.4}] {:propose 0.95 :review 0.5})))
    (is (= :abstain (:decision (n4/decide [] {:propose 0.95 :review 0.5}))))))

;; ---------------------------------------------------------------------------
;; The engines on dev
;; ---------------------------------------------------------------------------

(def ^:private fs-dev
  (:fellegi-sunter-inverted n3t/expected-dev))

(def expected-dev
  "The engines on dev, a tie read as an abstention. The N3 model and the
   N3 model with roles make the shipped model's 165 links, no wrong one;
   they send 5 mentions to review, not 3."
  {:jaccard     (:jaccard n3t/expected-dev)
   :names-dates (:names-dates n3t/expected-dev)
   :fs-shipped  fs-dev
   :fs-n3       (assoc fs-dev :review 5)
   :fs-n3-roles (assoc fs-dev :review 5)})

(def expected-strata
  "Per stratum: correct, wrong link, missed (tied included), false link,
   review."
  (let [fs {:easy [28 0 1 0 0] :hand [10 0 4 0 0] :hard-negative [0 0 0 0 0] :homonym [122 0 5 0 3]
            :no-namesake [0 0 0 0 0] :variant [5 0 11 0 0]}
        jc {:easy [29 0 0 0 0] :hand [14 0 0 0 0] :hard-negative [0 0 0 1 0] :homonym [122 0 5 0 0]
            :no-namesake [0 0 0 0 0] :variant [6 0 10 0 0]}
        n3 (assoc fs :hard-negative [0 0 0 0 2])]
    {:jaccard jc :names-dates jc :fs-shipped fs :fs-n3 n3 :fs-n3-roles n3}))

(deftest the-engines-on-dev
  (let [{:keys [results]} @dev-run]
    (is (= expected-dev (update-vals results :all)))
    (is (= expected-strata
           (update-vals results (fn [r] (update-vals (:by-stratum r) (juxt :correct :wrong-link :missed :false-link :review))))))
    (testing "every no-match mention, hard negatives included, is abstained by every Fellegi-Sunter engine"
      (doseq [k [:fs-shipped :fs-n3 :fs-n3-roles] s [:hard-negative :no-namesake]]
        (is (= 1 (get-in results [k :by-stratum s :no-match-abstention])) (str k " " s))))))

;; ---------------------------------------------------------------------------
;; Recalibration on train, thresholds on dev
;; ---------------------------------------------------------------------------

(def expected-choice
  "No review value holds 30 reviews on the folds: the shipped 0.5 stays."
  {:thresholds {:propose 0.8 :review 0.5}})

(def expected-cv-point {:precision 277/278 :recall 277/302 :links 556 :review 15})

(def expected-dev-point {:precision 1 :recall 55/62 :links 165 :review 5})

(def expected-roles-thresholds {:propose 0.8 :review 0.5})

(def expected-separation
  "[correct review false-link] of the N3 model and the roles model at equal
   thresholds: on the folds, propose 0.9 against 0.95 is 28 links; review
   0.1 against 0.4 is about 300; the role weight is 1 to 3."
  {:cv  {[0.9 0.1]  {:n3 [243 360 0] :roles [244 359 0]}
         [0.9 0.4]  {:n3 [548 22 1] :roles [549 21 1]}
         [0.9 0.5]  {:n3 [548 22 1] :roles [550 20 1]}
         [0.95 0.1] {:n3 [242 361 0] :roles [243 360 0]}
         [0.95 0.4] {:n3 [520 50 1] :roles [522 48 1]}
         [0.95 0.5] {:n3 [520 50 1] :roles [523 47 1]}}
   :dev {[0.9 0.1]  {:n3 [165 5 0] :roles [154 17 0]}
         [0.9 0.4]  {:n3 [165 5 0] :roles [165 5 0]}
         [0.9 0.5]  {:n3 [165 5 0] :roles [165 5 0]}
         [0.95 0.1] {:n3 [165 5 0] :roles [152 19 0]}
         [0.95 0.4] {:n3 [165 5 0] :roles [162 8 0]}
         [0.95 0.5] {:n3 [165 5 0] :roles [162 8 0]}}})

(deftest the-wilson-lower-bound
  (is (close? 0.7224598312333834 (n4/wilson-lower 10 10)))
  (is (close? 0.9772473134582945 (n4/wilson-lower 165 165)))
  (is (nil? (n4/wilson-lower 0 0))))

(deftest the-model-is-fitted-on-train
  (let [p (:params @fitted)]
    (testing "m from the 604 true pairs of train, u from every other train pair, the prior their share"
      (is (= 604.0 (get-in p [:m-counts :name :n])))
      (is (= 555.0 (get-in p [:m-counts :name :counts :exact])))
      (is (= (- (* 697 292) 604.0) (get-in p [:u-counts :name :n])))
      (is (= 197699.0 (get-in p [:u-counts :name :counts :none])))
      (is (close? (/ 604.0 (* 697 292)) (:prior p))))
    (testing "a transcription has no dates: every dates level is unknown and weighs 0"
      (is (= {:unknown 604.0} (get-in p [:m-counts :dates :counts])))
      (is (every? zero? (vals (get-in @n3 [:weights :dates])))))))

(deftest the-thresholds-are-chosen-by-cross-validation-on-train
  (let [{:keys [thresholds cv dev]} (:choice @fitted)
        train-pms (n4/prepared @ctx (n4/split-mentions @bench :train))]
    (testing "the choice is the criterion's on the held-out folds of train"
      (is (= thresholds (:thresholds (n4/choose-thresholds (n4/cv-scored @ctx train-pms nil n4/cv-folds)))))
      (is (= thresholds (:thresholds (:params @fitted)))))
    (testing "the criterion reads train mentions only; dev is a confirmation"
      (let [seen  (atom #{})
            choose n4/choose-thresholds]
        (with-redefs [n4/choose-thresholds (fn [sms] (swap! seen into (map (comp :split :mention) sms)) (choose sms))]
          (n4/n3-params @bench @ctx))
        (is (= #{:train} @seen))))
    (testing "every fold scores persons the model of that fold never saw"
      (let [folds (group-by #(n4/fold-of (:mention %) n4/cv-folds) train-pms)]
        (is (= (set (range n4/cv-folds)) (set (keys folds))))
        (is (every? (fn [[i ms]] (not-any? (set (map (comp hard/split-key :mention) ms))
                                           (map (comp hard/split-key :mention) (mapcat val (dissoc folds i)))))
                    folds))))
    (is (= expected-choice (select-keys (:choice @fitted) [:thresholds])))
    (is (= expected-cv-point (select-keys cv [:precision :recall :links :review])))
    (is (= expected-dev-point (select-keys dev [:precision :recall :links :review])))))

;; --- the criterion on synthetic cases: each condition decides one ----------

(defn- ms
  "`n` mentions of truth `truth` (nil: a no-match), each with candidates
   `cands` ([id p] pairs)."
  [n truth cands]
  (vec (for [i (range n)]
         {:mention {:truth truth :text (str "M" truth i) :id [truth i]}
          :cands   (mapv (fn [[id p]] {:id id :p p}) cands)})))

(deftest the-precision-condition-decides
  (let [sms (concat (ms 200 "a" [["a" 0.99]]) (ms 10 "x" [["y" 0.6]]) (ms 20 "b" [["b" 0.65]]))]
    (is (= 0.99 (get-in (n4/choose-thresholds sms) [:thresholds :propose]))
        "0.6 links 20 more, but at a precision of 220/230")))

(deftest the-wilson-condition-decides
  (testing "40 links, all right, are too few: no value is admissible, the shipped thresholds stay"
    (let [sms (concat (ms 40 "a" [["a" 0.999]]) (ms 60 "x" [["y" 0.6]]))]
      (is (= {:propose 0.95 :review 0.5} (:thresholds (n4/choose-thresholds sms)))))))

(deftest the-review-hit-condition-decides
  (let [sms (concat (ms 200 "a" [["a" 0.99]]) (ms 40 "z" [["w" 0.7]]) (ms 40 "b" [["b" 0.3]]))]
    (is (= {:propose 0.99 :review 0.3} (:thresholds (n4/choose-thresholds sms)))
        "0.7 and 0.4 send 40 mentions to review, none with its truth")))

(deftest the-minimum-review-count-decides
  (let [sms (concat (ms 200 "a" [["a" 0.99]]) (ms 10 "c" [["c" 0.7] ["d" 0.7]]) (ms 40 "b" [["b" 0.3] ["e" 0.3]]))]
    (is (= {:propose 0.99 :review 0.3} (:thresholds (n4/choose-thresholds sms)))
        "0.7 holds 10 reviews, fewer than the bar's 30")))

(deftest the-highest-admissible-review-value-is-chosen
  (let [sms (concat (ms 200 "a" [["a" 0.99]]) (ms 40 "c" [["c" 0.7] ["d" 0.7]]) (ms 40 "b" [["b" 0.3] ["e" 0.3]]))]
    (is (= {:propose 0.99 :review 0.7} (:thresholds (n4/choose-thresholds sms))))))

(deftest the-recall-condition-decides
  (let [sms (concat (ms 200 "a" [["a" 0.99]]) (ms 100 "b" [["b" 0.99] ["e" 0.2]]))]
    (is (= {:propose 0.99 :review 0.5} (:thresholds (n4/choose-thresholds sms)))
        "0.2 holds 100 reviews, all hits, but the competing rule loses 100 links: none admissible, 0.5 stays")))

;; --- what separates the runs ----------------------------------------------

(deftest the-simenon-ceiling-on-the-pool
  (testing "an exact heading reaches 0.95 for every pool entry but the 11 Simenons, held at 0.922 by their surname's frequency"
    (let [below (filter #(< (:p %) 0.95) (n4/exact-heading-ceilings (n4/n3-model) @ctx))]
      (is (= 11 (count below)))
      (is (= #{"simenon"} (set (map (comp fs/surname :label) below))))
      (is (every? #(< 0.921 (:p %) 0.923) below)))))

(deftest propose-review-and-roles-are-separated-at-equal-thresholds
  (is (= expected-separation (n4/separation @bench @ctx))))

(deftest the-role-feature-is-fitted-on-train
  (let [{:keys [role-w role-counts choice]} (:roles @dev-run)]
    (testing "the transcribed role phrase against the heading's relator codes in the other design records"
      (is (= {:role-m {:unknown 538.0 :agree 46.0 :disagree 20.0}
              :role-u {:unknown 183106.0 :agree 2874.0 :disagree 16940.0}}
             role-counts))
      (is (< 2.4 (:agree role-w) 2.5))
      (is (< -1.4 (:disagree role-w) -1.3))
      (is (zero? (:unknown role-w))))
    (is (= expected-roles-thresholds (:thresholds choice)))
    (testing "the roles model refuses its training split too"
      (is (thrown? clojure.lang.ExceptionInfo
                   (n4/refuse-in-sample! (:model (:roles @dev-run)) (:entries @ctx) (n4/split-mentions @bench :train)))))
    (testing "a level reads the other records only, and only the design records"
      (let [profiles {"a" {"r1" #{:translator} "r2" #{:other}}}]
        (is (= :agree (n4/role-level profiles {:role "trad. de" :record "r2"} "a")))
        (is (= :unknown (n4/role-level profiles {:role "trad. de" :record "r1"} "b")))
        (is (= :disagree (n4/role-level {"a" {"r1" #{:translator} "r2" #{:other}}} {:role "préface de" :record "r3"} "a")))
        (is (= :unknown (n4/role-level {"a" {"r1" #{:translator}}} {:role "trad. de" :record "r1"} "a")) "its own record")
        (is (= :unknown (n4/role-level profiles {:role "par" :record "r3"} "a")) "a phrase of no class"))
      (is (not-any? #(= :test (get-in @bench [:records % :part])) (mapcat (comp keys val) (n4/role-profiles @bench)))))))

;; ---------------------------------------------------------------------------
;; Calibration, against the bar
;; ---------------------------------------------------------------------------

(deftest the-calibration-does-not-meet-the-bar-so-no-confidence-is-written
  (let [model (n4/n3-model)
        dev   (n4/calibration-report model @ctx (n4/scored-mentions model @dev-pms))]
    (testing "on dev, the model itself: too few distinct true mentions, a bin far off, no review band"
      (is (= [1674 329 179 43 2 false false]
             ((juxt :n :distinct-pairs :positives :distinct-positives :review-pairs :meets-bar? :review-covered?) dev)))
      (is (close? 0.006918504310373037 (:ece dev)))
      (is (< 0.71 (:max-bin-gap dev) 0.72))
      (is (= (fs/model-fingerprint model) (:model dev)) "bound to the model as the file gives it")
      (is (= ((juxt :review :propose) (:thresholds model)) (:band dev)) "the review band is the model's own")
      (is (= (:pool @ctx) (:pool dev))))
    (testing "five folds of train+dev by person: enough pairs and true mentions, but a bin 0.40 off"
      (let [cv (n4/cv-report @ctx @bench 5)]
        (is (= [7499 1641 164 13 false] ((juxt :n :distinct-pairs :distinct-positives :review-pairs :meets-bar?) cv)))
        (is (< 0.0079 (:ece cv) 0.0080))
        (is (< 0.40 (:max-bin-gap cv) 0.41))
        (is (nil? (:model cv)) "it measures the procedure, not one model")))
    (testing "the model file carries no calibration, so no proposal states a confidence"
      (is (not (contains? (edn/read-string (slurp n4/model-path)) :calibration)))
      (let [ps (mapcat #(:proposals (reconcile/agent-candidates [{:name (n4/fs-name %) :dates nil}]
                                                                (:entries @ctx) {:model model}))
                       (take 40 (n4/split-mentions @bench :dev)))]
        (is (seq ps))
        (is (not-any? :confidence ps))))))

;; ---------------------------------------------------------------------------
;; The model file
;; ---------------------------------------------------------------------------

(deftest the-model-file-is-the-fit-on-train-at-the-thresholds-cross-validation-chose
  (let [file (edn/read-string (slurp n4/model-path))]
    (is (= (:params @fitted) file))
    (testing "its provenance: the design corpora and their SHA-256, the split, the licence and the attribution"
      (let [prov (:provenance file)]
        (is (= :n3-train (:split prov)))
        (is (= 11 (count (:data prov))) "the nine design corpora, the benchmark and the split file")
        (is (every? #(= 64 (count (:sha256 %))) (:data prov)))
        (is (= (n4/train-keys @bench) (:train-keys prov)))
        (is (= "Licence Ouverte / Etalab 2.0" (:licence prov)))
        (is (= "Source : Bibliothèque nationale de France" (:attribution prov)))))))

(deftest the-n3-model-is-read-from-the-classpath-and-versioned
  (let [{:keys [model version]} (rc/read-model n4/model-resource)
        shipped (rc/read-model)]
    (is (= n4/model-resource (:path version)))
    (is (= :classpath (:source version)))
    (is (= (rc/model-version model) (dissoc version :path :source)))
    (is (not= (:sha256 (:version shipped)) (:sha256 version)))
    (testing "the shipped M2 model is unchanged and stays the default"
      (is (= "palomar/models/agent-match.edn" (:path (:version shipped)))))
    (testing "a reconciliation and its manifest name it by its version and its provenance"
      (let [records (:records (api/pivot :intermarc {} (slurp "test/fixtures/documentary/intermarc/sru/intermarcXchange/bnf-sru-jules-verne-50.xml")))
            pool    (api/read-authorities "test/fixtures/er-gold/bnf-persons/authorities.xml")
            res     (api/reconcile records pool {:engine :probabilistic :model (rc/read-model n4/model-resource)})]
        (is (= version (:model res)))
        (is (= :n3-train (get-in (rc/manifest-entry res) [:provenance :split])))
        (is (not-any? :confidence (mapcat :proposals (:decisions res))))))))

;; ---------------------------------------------------------------------------
;; Leakage
;; ---------------------------------------------------------------------------

(deftest the-n3-model-refuses-its-own-training-split
  (let [model (n4/n3-model)
        es    (:entries @ctx)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"in-sample"
                          (n4/refuse-in-sample! model es (n4/split-mentions @bench :train))))
    (is (thrown? clojure.lang.ExceptionInfo (n4/refuse-in-sample! model es (take 1 (n4/split-mentions @bench :train)))))
    (is (nil? (n4/refuse-in-sample! model es (n4/split-mentions @bench :dev))))
    (is (nil? (n4/refuse-in-sample! model es (n4/split-mentions @bench :test))))
    (testing "nor the design corpora it was fitted on, by their SHA-256"
      (is (thrown? clojure.lang.ExceptionInfo
                   (rc/refuse-in-sample! model [] {:sources [(str hard/fixture-dir "records/bnf-sru-bronte-100.xml")]})))
      (is (nil? (rc/refuse-in-sample! model [] {:sources [(str hard/fixture-dir "records-test/bnf-sru-simenon-100.xml")]}))))
    (testing "nor the benchmark and split files that hold its training mentions"
      (is (thrown? clojure.lang.ExceptionInfo (rc/refuse-in-sample! model [] {:sources [hard/benchmark-file]})))
      (is (thrown? clojure.lang.ExceptionInfo (rc/refuse-in-sample! model [] {:sources [hard/splits-file]}))))
    (testing "the comparison refuses to score train"
      (is (thrown? clojure.lang.ExceptionInfo (n4/comparison @bench :train {:shipped (n4/shipped-model) :n3 model}))))
    (testing "the shipped M2 model may score the N3 pool"
      (is (nil? (n4/refuse-in-sample! (n4/shipped-model) es (n4/split-mentions @bench :dev)))))))

(deftest no-test-mention-fed-the-fit-or-the-thresholds
  (testing "the fit and the threshold choice read train mentions only"
    (let [fit-splits (atom #{}) choice-splits (atom #{})
          fit        n4/fit-counts
          choose     n4/choose-thresholds]
      (with-redefs [n4/fit-counts        (fn [c pms p] (swap! fit-splits into (map (comp :split :mention) pms)) (fit c pms p))
                    n4/choose-thresholds (fn [sms] (swap! choice-splits into (map (comp :split :mention) sms)) (choose sms))]
        (n4/n3-params @bench @ctx))
      (is (= #{:train} @fit-splits))
      (is (= #{:train} @choice-splits))))
  (testing "removing the test mentions and the test records, or changing them, changes nothing"
    (let [b        @bench
          dropped  (-> b
                       (update :mentions #(filterv (comp not #{:test} :part) %))
                       (update :records #(into {} (remove (comp #{:test} :part val)) %)))
          changed  (update b :mentions (fn [ms] (mapv #(if (= :test (:split %)) (assoc % :text "Zzz Test" :truth (:id (first (:pool b)))) %) ms)))]
      (is (= @fitted (n4/n3-params dropped @ctx)))
      (is (= @fitted (n4/n3-params changed @ctx)))
      (is (= (n4/role-profiles b) (n4/role-profiles dropped))))))

;; ---------------------------------------------------------------------------
;; The N3 model on the M2 headings (out of sample for it)
;; ---------------------------------------------------------------------------

(deftest the-n3-model-on-the-m2-headings
  (let [c (pr/corpus)
        a (pr/task-a-fs c (constantly (n4/n3-model)))]
    (testing "every in-pool heading proposed outright and right, no false link, one review"
      (is (= [136 136 0 1] ((juxt :in-pool :correct :false-links :precision-1) (:all (:outright a)))))
      (is (= 1 (:review a))))))

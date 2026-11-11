(ns palomar.agent-match-test
  "The Fellegi-Sunter agent matcher (lot M4, ADR 0018 amendment), on hand-made
   inputs: comparison levels, weights from a tiny table, the term-frequency
   adjustment, the posterior, the calibration metrics and the confidence rule."
  (:require [clojure.test :refer [deftest is testing]]
            [palomar.agent-dates :as dates]
            [palomar.agent-match :as fs]))

(defn- close? [x y] (< (Math/abs (- (double x) (double y))) 1e-9))

;; ---------------------------------------------------------------------------
;; Comparison levels
;; ---------------------------------------------------------------------------

(deftest name-levels-read-the-structure-of-a-heading
  (testing "the same heading, accents, case and punctuation aside"
    (is (= :exact (fs/name-level "Hugo, Victor" "Hugo, Victor")))
    (is (= :exact (fs/name-level "Hugo, Victor" "hugo victor")))
    (is (= :exact (fs/name-level "Pérez, Víctor" "Perez, Victor"))))
  (testing "same surname, forenames compatible as initials"
    (is (= :initials (fs/name-level "Hetzel, P.-J." "Hetzel, Pierre-Jules")))
    (is (= :initials (fs/name-level "Verne, J." "Verne, Jules")))
    (is (= :initials (fs/name-level "Verne, Jules" "Verne, J.")))
    (is (not= :initials (fs/name-level "Verne, J." "Verne, Jean Jules")) "a different number of forenames"))
  (testing "the same tokens in another order"
    (is (= :same-tokens (fs/name-level "Pierre-Jules Hetzel" "Hetzel, Pierre-Jules"))))
  (testing "a surname one edit away, forenames alike"
    (is (= :surname-typo (fs/name-level "Dmuas, Alexandre" "Dumas, Alexandre")))
    (is (= :none (fs/name-level "Dmuas, Adolphe" "Dumas, Alexandre")) "forenames differ too: nothing is shared")
    (is (not= :surname-typo (fs/name-level "Bon, Jean" "Ban, Jean")) "too short to call a typo"))
  (testing "same surname, forenames that differ"
    (is (= :forename-overlap (fs/name-level "Verne, Jules" "Verne, Jean Jules")))
    (is (= :forename-overlap (fs/name-level "Hetzel, Pierre-Jules" "Hetzel, Louis-Jules")))
    (is (= :surname-only (fs/name-level "Hetzel" "Hetzel, Pierre-Jules")))
    (is (= :forenames-differ (fs/name-level "Dumas, Adolphe" "Dumas, Alexandre"))))
  (testing "different surnames: the Jaccard bands"
    (is (= :jaccard-high (fs/name-level "Paltsits, Victor Hugo" "Hugo, Victor")))
    (is (= :jaccard-low (fs/name-level "Hugo, Georges-Victor" "Pérez, Víctor")))
    (is (= :none (fs/name-level "Verne, Jules" "Dumas, Alexandre")))))

(def ^:private pere {:label "Dumas, Alexandre" :variants ["Dumas père, Alexandre" "Aramis"]
                     :dates (dates/parse-life-dates "1802-1870")})

(deftest a-comparison-is-a-vector-of-levels
  (testing "the best form decides the name level; the form says whether it was a variant"
    (is (= {:name :exact :form :heading :dates :agree}
           (:levels (fs/compare-pair {:name "Dumas, Alexandre" :dates (dates/parse-life-dates "1802-1870")} pere))))
    (is (= {:name :exact :form :variant :dates :unknown}
           (:levels (fs/compare-pair {:name "Aramis"} pere)))))
  (testing "the dates levels: agree within tolerance, compatible, unknown, and incompatible as a block"
    (let [lv #(get-in (fs/compare-pair {:name "Dumas, Alexandre" :dates (dates/parse-life-dates %)} pere) [:levels :dates])]
      (is (= :agree (lv "1801-1870")))
      (is (= :compatible (lv "ca. 1800-")))
      (is (= :unknown (lv nil)))
      (is (= :incompatible (lv "1824-1895")))))
  (testing "the comparison keeps the Jaccard and, for a surname agreement, the surname's frequency in the pool"
    (let [c (fs/compare-pair {:name "Dumas, Alexandre"} pere {:surname-counts {"dumas" 3} :pool-size 30})]
      (is (= 1.0 (:name-score c)))
      (is (= "dumas" (:surname c)))
      (is (close? 0.1 (:surname-u c)))
      (is (close? (fs/tf-mean {"dumas" 3} 30) (:tf-mean c)) "and the u-mean of the same pool"))
    (testing "a surname the pool headings do not hold counts once"
      (is (close? (/ 1.0 30) (:surname-u (fs/compare-pair {:name "Aramis"} pere {:surname-counts {"dumas" 3} :pool-size 30})))))
    (testing "no surname agreement, no frequency"
      (is (nil? (:surname-u (fs/compare-pair {:name "Verne, Jules"} pere {:surname-counts {} :pool-size 30})))))))

(deftest surname-counts-read-the-pool-headings
  (is (= {"dumas" 2 "verne" 1 "aramis" 1}
         (fs/surname-counts [{:label "Dumas, Alexandre"} {:label "Dumas, Adolphe"} {:label "Verne, Jules"}
                             {:label "Aramis"}]))))

;; ---------------------------------------------------------------------------
;; Weights
;; ---------------------------------------------------------------------------

(defn- cmp [n f d] {:levels {:name n :form f :dates d}})

(deftest level-frequencies-on-a-tiny-table
  (let [cs [(cmp :exact :heading :agree) (cmp :exact :heading :unknown)
            (cmp :none :heading :unknown) (cmp :none :variant :incompatible)]]
    (testing "without smoothing, the shares; an incompatible pair is blocked and counts in no dates level"
      (let [f (fs/level-frequencies cs 0)]
        (is (close? 0.5 (get-in f [:name :exact])))
        (is (close? 0.0 (get-in f [:name :initials])))
        (is (close? 0.75 (get-in f [:form :heading])))
        (is (close? (/ 1.0 3) (get-in f [:dates :agree])))
        (is (nil? (get-in f [:dates :incompatible])))))
    (testing "with a pseudo-count alpha per level: (n + alpha) / (N + alpha K)"
      (let [f (fs/level-frequencies cs 1)]
        (is (close? (/ 3.0 14) (get-in f [:name :exact])) "10 name levels")
        (is (close? (/ 1.0 14) (get-in f [:name :initials])))
        (is (close? (/ 4.0 6) (get-in f [:form :heading])))
        (is (close? (/ 2.0 6) (get-in f [:dates :agree])) "3 dates levels")))))

(deftest a-weight-is-the-log-ratio-of-m-to-u
  (is (close? 3.0 (fs/weight 0.8 0.1)))
  (is (close? -2.0 (fs/weight 0.1 0.4)))
  (testing "a model's weights for a comparison"
    (let [model (fs/model {:m {:name {:exact 0.8 :none 0.2} :form {:heading 0.5 :variant 0.5} :dates {:agree 0.5 :unknown 0.5}}
                           :u {:name {:exact 0.1 :none 0.9} :form {:heading 0.5 :variant 0.5} :dates {:agree 0.125 :unknown 0.875}}
                           :prior 0.5})
          s     (fs/score model {:levels {:name :exact :form :heading :dates :agree}})]
      (is (= {:name 3.0 :form 0.0 :dates 2.0 :tf 0.0} (update-vals (:weights s) #(/ (Math/round (* 1e9 %)) 1e9))))
      (is (close? 5.0 (:match-weight s))))))

(defn- counts [n m] {:n (double n) :counts (update-vals m double)})

(deftest unmeasured-levels-carry-no-weight
  (let [mc    {:name (counts 50 {:exact 48 :initials 2}) :form (counts 50 {:heading 50}) :dates (counts 50 {:agree 45 :unknown 5})}
        uc    {:name (counts 1000 {:exact 2 :initials 1 :forenames-differ 15 :jaccard-low 80 :none 902})
               :form (counts 1000 {:heading 990 :variant 10}) :dates (counts 200 {:agree 20 :unknown 180})}
        w     (:weights (fs/model {:m-counts mc :u-counts uc :prior 0.1}))]
    (testing "measured on both sides: log2(m/u)"
      (is (close? (fs/weight 0.9 0.1) (get-in w [:dates :agree]))))
    (testing "an agreement seen among the same persons, almost never among different ones: the bound u <= 5/N"
      (is (close? (fs/weight 0.96 0.005) (get-in w [:name :exact]))))
    (testing "seen on neither side often enough: 0, not log2 of pseudo-counts"
      (is (= 0.0 (get-in w [:name :initials])))
      (is (= 0.0 (get-in w [:name :surname-typo]))))
    (testing "seen among different persons only: at most the bound m <= 5/N, and never above 0"
      (is (close? (fs/weight 0.1 0.902) (get-in w [:name :none])))
      (is (= 0.0 (get-in w [:name :forenames-differ])) "m <= 0.1 against u = 0.015 proves nothing")
      (is (= 0.0 (get-in w [:form :variant])) "a bound above u is no evidence either"))))

(deftest weights-follow-the-order-of-agreement
  (let [w (:weights (fs/model {:m {:name {:exact 0.5 :initials 0.01 :forename-overlap 0.2 :forenames-differ 0.2
                                          :jaccard-low 0.05 :none 0.04}
                                   :form {:heading 1.0} :dates {:agree 0.5 :unknown 0.5}}
                               :u {:name {:exact 0.001 :initials 0.001 :forename-overlap 0.002 :forenames-differ 0.01
                                          :jaccard-low 0.01 :none 0.9}
                                   :form {:heading 1.0} :dates {:agree 0.05 :unknown 0.95}}
                               :prior 0.1}))]
    (testing "along name-levels, a weight never exceeds the one before it"
      (is (apply >= (map #(get-in w [:name %]) fs/name-levels)))
      (is (close? (fs/weight 0.01 0.001) (get-in w [:name :initials])))
      (is (= 0.0 (get-in w [:name :forename-overlap])) "estimated log2 100, capped by the levels above it, which weigh 0"))
    (testing "a disagreeing level never weighs more than 0, whatever was estimated"
      (is (= 0.0 (get-in w [:name :forenames-differ])) "estimated log2 20")
      (is (= 0.0 (get-in w [:name :jaccard-low])) "estimated log2 5")
      (is (every? #(<= (get-in w [:name %]) 0.0) fs/disagreeing-levels)))
    (testing "missing dates are no evidence: :unknown weighs 0 by rule, whatever the estimate (here log2 0.53)"
      (is (= 0.0 (get-in w [:dates :unknown]))))))

(deftest a-rare-surname-weighs-more-than-a-common-one
  (let [model (fs/model {:m {:name {:exact 0.9 :forenames-differ 0.05 :none 0.05} :form {:heading 1.0} :dates {:unknown 1.0}}
                         :u {:name {:exact 0.1 :forenames-differ 0.1 :none 0.8} :form {:heading 1.0} :dates {:unknown 1.0}}
                         :prior 0.5})
        w     (fn [su] (:match-weight (fs/score model {:levels {:name :exact :form :heading :dates :unknown}
                                                       :surname-u su :tf-mean 0.1})))]
    (testing "the adjustment is log2(u-mean / u_s), both carried by the comparison: zero for a surname at the mean"
      (is (close? (w 0.1) (fs/weight 0.9 0.1))))
    (testing "a surname a tenth as frequent weighs log2 10 more; ten times as frequent, log2 10 less"
      (is (close? (+ (w 0.1) (/ (Math/log 10) (Math/log 2))) (w 0.01)))
      (is (close? (- (w 0.1) (/ (Math/log 10) (Math/log 2))) (w 1.0))))
    (testing "no adjustment for a level that weighs nothing positive: a rare surname never makes different forenames evidence"
      (is (= 0.0 (get-in (fs/score model {:levels {:name :forenames-differ :form :heading :dates :unknown}
                                          :surname-u 0.0001 :tf-mean 0.1}) [:weights :tf]))))
    (testing "no surname agreement, no adjustment"
      (is (close? 0.0 (get-in (fs/score model {:levels {:name :none :form :heading :dates :unknown}}) [:weights :tf])))))
  (testing "u_s and the u-mean come from the same pool: an all-distinct pool of 5 000 adjusts nothing"
    (let [pool   (cons {:label "Verne, Jules"} (for [i (range 4999)] {:label (str "Filler" i ", Anne")}))
          counts (fs/surname-counts pool)
          c      (fs/compare-pair {:name "Verne, Jules"} {:label "Verne, Jules"} {:surname-counts counts :pool-size 5000})]
      (is (close? (:tf-mean c) (:surname-u c))))))
(deftest the-u-mean-is-the-expected-frequency-of-an-agreeing-surname
  (testing "two headings drawn at random agree on s with odds n_s^2: the mean u_s is sum n^3 / (N sum n^2)"
    ;; counts 2 and 1 in a pool of 3: (8 + 1) / (3 x (4 + 1))
    (is (close? (/ 9.0 15) (fs/tf-mean {"dumas" 2 "verne" 1} 3)))
    (is (close? 0.1 (fs/tf-mean {"a" 1 "b" 1 "c" 1 "d" 1 "e" 1 "f" 1 "g" 1 "h" 1 "i" 1 "j" 1} 10)) "all distinct: 1/N"))
  (is (nil? (fs/tf-mean {} 0))))

(deftest the-posterior-takes-the-prior
  (is (close? 0.5 (fs/posterior 0.0 0.5)))
  (is (close? (/ 8.0 9) (fs/posterior 3.0 0.5)) "odds 1 x 2^3")
  (is (close? (/ 1.0 2) (fs/posterior 3.0 (/ 1.0 9))) "odds 1/8 x 2^3")
  (testing "the decision: propose above one threshold, abstain below another, review between"
    (let [model (fs/model {:m {} :u {} :prior 0.5 :thresholds {:propose 0.9 :review 0.5}})]
      (is (= :propose (fs/decide model 0.95)))
      (is (= :review (fs/decide model 0.6)))
      (is (= :abstain (fs/decide model 0.4))))))

;; ---------------------------------------------------------------------------
;; EM
;; ---------------------------------------------------------------------------

(deftest em-learns-m-without-labels
  (let [matches (repeat 10 (cmp :exact :heading :agree))
        others  (concat (repeat 80 (cmp :none :heading :unknown)) (repeat 10 (cmp :forenames-differ :heading :unknown)))
        cs      (concat matches others)
        u       (fs/level-frequencies others 0.01)
        {:keys [m prior]} (fs/em cs {:u u :prior 0.5 :alpha 0.01})]
    (is (< 0.95 (get-in m [:name :exact])))
    (is (< 0.95 (get-in m [:dates :agree])))
    (is (< 0.09 prior 0.11))))

;; ---------------------------------------------------------------------------
;; Calibration, and the confidence rule
;; ---------------------------------------------------------------------------

(def ^:private preds
  [{:p 0.05 :y 0} {:p 0.05 :y 0} {:p 0.15 :y 1} {:p 0.95 :y 1} {:p 0.95 :y 0}])

(deftest calibration-metrics-on-hand-made-predictions
  (testing "Brier: the mean squared error"
    ;; 0.0025 + 0.0025 + 0.7225 + 0.0025 + 0.9025 = 1.6325
    (is (close? (/ 1.6325 5) (fs/brier preds))))
  (testing "the reliability table: ten equal bins, the mean predicted and the observed rate in each"
    (let [bins (fs/reliability preds 10)]
      (is (= 3 (count bins)) "only the bins that hold a prediction")
      (is (= [[0.0 0.1] 2] ((juxt :bin :n) (first bins))))
      (is (close? 0.05 (:mean-p (first bins))))
      (is (close? 0.0 (:observed (first bins))))
      (is (= [[0.9 1.0] 2] ((juxt :bin :n) (last bins))))
      (is (close? 0.5 (:observed (last bins))))
      (testing "a probability of 1.0 is in the last bin"
        (is (= [0.9 1.0] (:bin (first (fs/reliability [{:p 1.0 :y 1}] 10))))))))
  (testing "ECE: the gaps of the bins, weighted by their share"
    ;; 2/5 x 0.05 + 1/5 x 0.85 + 2/5 x 0.45
    (is (close? (+ 0.02 0.17 0.18) (fs/ece preds 10)))))

(def ^:private bar {:max-ece 0.05 :min-positives 2 :min-pairs 4 :bins 10})

(deftest the-calibration-report-says-whether-the-bar-is-met
  (let [good (concat (repeat 10 {:p 0.99 :y 1}) (repeat 10 {:p 0.01 :y 0}))]
    (is (true? (:meets-bar? (fs/calibration good {:bar bar}))))
    (is (false? (:meets-bar? (fs/calibration preds {:bar bar}))) "an ECE of 0.37")
    (is (false? (:meets-bar? (fs/calibration (take 3 good) {:bar bar}))) "too few pairs, however well calibrated")
    (is (= {:n 20 :positives 10 :distinct-pairs 20 :distinct-positives 10}
           (select-keys (fs/calibration good {:bar bar}) [:n :positives :distinct-pairs :distinct-positives])))))

(deftest the-bar-counts-distinct-pairs-and-distinct-true-mentions
  (let [same  (fn [i y p] {:p p :y y :pair [:m i :a y] :mention [:m i]})
        preds (concat (repeat 35 (same 1 1 0.99)) (repeat 35 (same 1 0 0.01)) [(same 2 1 0.99) (same 3 0 0.6)])
        r     (fs/calibration preds)]
    (testing "35 identical mentions are one mention, their 70 pairs two pairs"
      (is (= {:n 72 :positives 36 :distinct-pairs 4 :distinct-positives 2 :review-pairs 1}
             (select-keys r [:n :positives :distinct-pairs :distinct-positives :review-pairs]))))
    (testing "the measured facts stay over every prediction"
      (is (close? (fs/brier preds) (:brier r))))
    (testing "the largest gap of any bin"
      (is (close? 0.6 (:max-bin-gap r))))))

(def ^:private pool-a [{:id "http://ex.org/a" :label "Dumas, Alexandre"}])
(def ^:private pool-b [{:id "http://ex.org/a" :label "Dumas, Alexandre"} {:id "http://ex.org/b" :label "Verne, Jules"}])

(defn- well-calibrated
  "Predictions that meet `default-bar`: 100 true and 200 false distinct pairs
   at the extremes, and 40 in the review band, 30 of them true."
  []
  (concat (for [i (range 100)] {:p 0.99 :y 1 :pair [:t i] :mention [:t i]})
          (for [i (range 200)] {:p 0.01 :y 0 :pair [:f i] :mention [:f i]})
          (for [i (range 40)] {:p 0.75 :y (if (< i 30) 1 0) :pair [:r i] :mention [:r i]})))

(def ^:private tiny
  (fs/model {:m {:name {:exact 0.9 :none 0.1} :form {:heading 1.0} :dates {:unknown 1.0}}
             :u {:name {:exact 0.1 :none 0.9} :form {:heading 1.0} :dates {:unknown 1.0}}
             :prior 0.5}))

(deftest the-review-band-of-a-calibration-is-a-parameter
  (let [preds (for [[i p] (map-indexed vector [0.05 0.2 0.3 0.6 0.7 0.96])] {:p p :y 0 :pair [i] :mention [i]})]
    (testing "by default, the band of default-thresholds, [0.5, 0.95)"
      (is (= 2 (:review-pairs (fs/calibration preds))))
      (is (= [0.5 0.95] (:band (fs/calibration preds)))))
    (testing "given, the model's own band"
      (is (= 4 (:review-pairs (fs/calibration preds {:band [0.1 0.95]}))))
      (is (= [0.1 0.95] (:band (fs/calibration preds {:band [0.1 0.95]})))))))

(deftest a-probability-is-a-confidence-only-when-calibration-measured-it
  (let [fp-a (fs/pool-fingerprint pool-a)
        good (fs/calibration (well-calibrated) {:pool fp-a :gold "g" :model (fs/model-fingerprint tiny)})]
    (testing "a fitted model is not calibrated: it states no confidence"
      (is (false? (fs/calibrated? tiny pool-a)))
      (is (nil? (fs/confidence tiny pool-a 0.99 :propose))))
    (testing "a calibration that misses the bar changes nothing"
      (let [m (fs/with-calibration tiny (fs/calibration preds {:pool fp-a}))]
        (is (false? (fs/calibrated? m pool-a)))
        (is (nil? (fs/confidence m pool-a 0.99 :propose)))))
    (testing "one that meets it makes the posterior a confidence, on the pool it was measured on"
      (let [m (fs/with-calibration tiny good)]
        (is (true? (:meets-bar? good)))
        (is (true? (fs/calibrated? m pool-a)))
        (is (true? (fs/calibrated? m fp-a)) "the pool or its fingerprint")
        (is (= 0.99 (fs/confidence m pool-a 0.99 :propose)))
        (testing "and on no other pool"
          (is (false? (fs/calibrated? m pool-b)))
          (is (nil? (fs/confidence m pool-b 0.99 :propose))))))
    (testing "a review decision needs the review band covered"
      (let [thin (fs/calibration (remove #(= :r (first (:pair %))) (well-calibrated)) {:pool fp-a :model (fs/model-fingerprint tiny)})
            m    (fs/with-calibration tiny thin)]
        (is (true? (:meets-bar? thin)))
        (is (false? (:review-covered? thin)))
        (is (= 0.99 (fs/confidence m pool-a 0.99 :propose)))
        (is (nil? (fs/confidence m pool-a 0.7 :review)))
        (is (= 0.7 (fs/confidence (fs/with-calibration tiny good) pool-a 0.7 :review)))))
    (testing "the flag is read from the report against the model's pinned bar, never from a boolean or the report's own bar"
      (is (false? (fs/calibrated? (assoc tiny :calibrated? true) pool-a)))
      (is (false? (fs/calibrated? (fs/with-calibration tiny {:meets-bar? true :pool fp-a}) pool-a)))
      (let [lax    {:max-ece 1 :max-bin-gap 1 :min-pairs 0 :min-positives 0 :min-review-pairs 0 :bins 10}
            forged (fs/with-calibration tiny (fs/calibration [{:p 0.6 :y 0} {:p 0.9 :y 1} {:p 0.1 :y 1}] {:bar lax :pool fp-a}))]
        (is (true? (:meets-bar? (:calibration forged))) "the report says it meets its own bar")
        (is (false? (fs/calibrated? forged pool-a)))
        (is (false? (fs/calibrated? (fs/with-calibration (fs/model {:m {} :u {} :prior 0.5 :bar lax}) (:calibration forged)) pool-a))
            "a model built with a laxer bar keeps the default's")))))

(deftest a-confidence-is-written-only-with-its-proof
  (let [fp-a (fs/pool-fingerprint pool-a)
        m    (fs/with-calibration tiny (fs/calibration (well-calibrated) {:pool fp-a :model (fs/model-fingerprint tiny)}))
        p    {:score 0.99 :confidence 0.99 :decision :propose :calibration-proof (fs/confidence-proof m pool-a)}]
    (is (= 0.99 (fs/proven-confidence p)))
    (testing "a confidence put on a proposal by hand, with no proof, is none"
      (is (nil? (fs/proven-confidence (dissoc p :calibration-proof)))))
    (testing "a proof whose report misses the bar, a confidence that is not the score, a proof for another pool: none"
      (is (nil? (fs/proven-confidence (assoc p :calibration-proof (fs/confidence-proof tiny pool-a)))))
      (is (nil? (fs/proven-confidence (assoc p :confidence 0.999))))
      (is (nil? (fs/proven-confidence (assoc-in p [:calibration-proof :pool] (fs/pool-fingerprint pool-b))))))))

(deftest a-calibration-proves-only-the-model-it-was-computed-on
  (let [fp-a  (fs/pool-fingerprint pool-a)
        other (fs/model {:m {:name {:exact 0.99 :none 0.01} :form {:heading 1.0} :dates {:unknown 1.0}}
                         :u {:name {:exact 0.01 :none 0.99} :form {:heading 1.0} :dates {:unknown 1.0}}
                         :prior 0.9})
        own   (fs/calibration (well-calibrated) {:pool fp-a :model (fs/model-fingerprint tiny)})]
    (testing "a report names the model it measured, by the fingerprint of the model without its calibration"
      (is (= (fs/model-fingerprint tiny) (:model own)))
      (is (= (fs/model-fingerprint tiny) (fs/model-fingerprint (fs/with-calibration tiny own)))))
    (testing "on its own model, it proves the confidence"
      (let [m (fs/with-calibration tiny own)]
        (is (true? (fs/calibrated? m pool-a)))
        (is (= 0.99 (fs/proven-confidence {:score 0.99 :confidence 0.99 :decision :propose
                                           :calibration-proof (fs/confidence-proof m pool-a)})))))
    (testing "a report computed on another model proves nothing"
      (let [m (fs/with-calibration other own)]
        (is (false? (fs/calibrated? m pool-a)))
        (is (nil? (fs/confidence m pool-a 0.99 :propose)))
        (is (nil? (fs/proven-confidence {:score 0.99 :confidence 0.99 :decision :propose
                                         :calibration-proof (fs/confidence-proof m pool-a)})))))
    (testing "a forged report, written by hand or naming no model, proves nothing"
      (doseq [forged [{:pool fp-a :ece 0.0 :max-bin-gap 0.0 :distinct-pairs 1000000 :distinct-positives 1000000 :review-pairs 1000}
                      (dissoc own :model)]]
        (let [m (fs/with-calibration tiny forged)]
          (is (false? (fs/calibrated? m pool-a)))
          (is (nil? (fs/proven-confidence {:score 0.99 :confidence 0.99 :decision :propose
                                           :calibration-proof (fs/confidence-proof m pool-a)}))))))))

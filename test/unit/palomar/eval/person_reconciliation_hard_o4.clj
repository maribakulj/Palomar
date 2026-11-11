(ns palomar.eval.person-reconciliation-hard-o4
  "Lot O4 on the hard benchmark (`docs/eval/person-reconciliation-hard.md`,
   section O4): the three rules of `palomar.agent-match` (the form on an
   exact name, the chain over measured levels, the relative competing
   rule) measured one by one and together, on the same candidates, against
   the N4 rules and the Jaccard.

   **The grid.** Every configuration is the N3 model fitted on train under
   its rules (`configurations`), its thresholds (and its gap, for the
   relative rule) chosen on a 5-fold cross-validation of train by person
   with the N4 criterion (`n4/threshold-criterion`, `n4/gap-criterion`).
   The figures are those of the held-out folds of train (`:cv`) and, for
   confirmation only, of dev (`:dev`), the model then fitted on all of
   train. The test split is never read: it is spent (`n4/test-run`).

   This namespace calls the frozen N3 namespace and N4's harness, and
   changes neither."
  (:require [palomar.agent-match :as fs]
            [palomar.eval.person-reconciliation :as pr]
            [palomar.eval.person-reconciliation-hard :as hard]
            [palomar.eval.person-reconciliation-hard-n4 :as n4]
            [palomar.reconcile :as reconcile]))

;; ---------------------------------------------------------------------------
;; The configurations
;; ---------------------------------------------------------------------------

(def configurations
  "The configurations compared, on the same candidates: `:rules` the weight
   rules (`agent-match/rules-of`), `:gap?` whether the relative competing
   rule is on (its G chosen by `n4/gap-criterion`). `:a-current` is N4's
   model; `:c-all` the three fixes; `:b-form-chain` the two weight fixes
   under the absolute competing rule (the N4 note: lifting the cap alone
   loses links)."
  (array-map
   :a-current    {:rules fs/n4-rules :gap? false}
   :b-form       {:rules {:form :exact-name :chain :every-level} :gap? false}
   :b-chain      {:rules {:form :every-level :chain :measured-levels} :gap? false}
   :b-relative   {:rules fs/n4-rules :gap? true}
   :b-form-chain {:rules fs/o4-rules :gap? false}
   :c-all        {:rules fs/o4-rules :gap? true}))

(def strata
  "The strata reported, in order."
  [:easy :homonym :variant :hand :hard-negative :no-namesake])

;; ---------------------------------------------------------------------------
;; The figures of one set of decided mentions
;; ---------------------------------------------------------------------------

(defn- ratio [n d] (when (pos? d) (/ n d)))

(defn figures
  "The figures of decided mentions `rows` (`[{:mention :result :cands}]`,
   `:result` an engine's `{:link :tied? :review?}`, `:cands` the candidates
   it generated, `[{:id ..}]`, and at or above review for a review hit):

   - `:precision` correct / outright links, `:recall` correct / linked;
   - `:false-merges` outright links to the wrong person or on a no-match;
   - `:abstained` mentions with no link and no review, `:abstention` their
     share; `:no-match-abstention` the share of no-match mentions with no
     link and no review;
   - `:review` mentions sent to review, `:reviews-per-mention`, and
     `:review-truth` the share of reviews whose truth is among the
     candidates at or above review (a no-match review holds none);
   - `:generation` the share of linked mentions whose truth is among the
     candidates generated."
  [rows]
  (let [os       (for [{:keys [mention result] :as r} rows]
                   (assoc r :outcome (hard/outcome mention result :abstain) :review? (boolean (:review? result))))
        n        (count os)
        linked   (filter (comp :truth :mention) os)
        nomatch  (remove (comp :truth :mention) os)
        cnt      (fn [pred xs] (count (filter pred xs)))
        correct  (cnt #(= :correct (:outcome %)) os)
        merges   (cnt #(#{:wrong-link :false-link} (:outcome %)) os)
        quiet?   #(and (not (#{:correct :wrong-link :false-link} (:outcome %))) (not (:review? %)))
        reviews  (filter :review? os)
        holds?   (fn [{:keys [mention review-cands]}] (boolean (and (:truth mention) (some #(= (:truth mention) (:id %)) review-cands))))]
    {:mentions            n
     :linked              (count linked)
     :correct             correct
     :false-merges        merges
     :precision           (ratio correct (+ correct merges))
     :recall              (ratio correct (count linked))
     :review              (count reviews)
     :reviews-per-mention (ratio (count reviews) n)
     :review-truth        (ratio (cnt holds? reviews) (count reviews))
     :abstained           (cnt quiet? os)
     :abstention          (ratio (cnt quiet? os) n)
     :no-match-abstention (ratio (cnt quiet? nomatch) (count nomatch))
     :generation          (ratio (cnt (fn [{:keys [mention cands]}] (some #(= (:truth mention) (:id %)) cands)) linked)
                                 (count linked))}))

(defn by-stratum
  "The `figures` of `rows`, overall (`:all`) and per stratum."
  [rows]
  (into {:all (figures rows)}
        (for [[s xs] (group-by (comp :stratum :mention) rows)] [s (figures xs)])))

;; ---------------------------------------------------------------------------
;; The Fellegi-Sunter configurations
;; ---------------------------------------------------------------------------

(defn fs-rows
  "The rows of `scored-ms` (`[{:mention :cands}]`, `n4/scored-mentions`)
   decided at `thresholds` (`n4/decide`, its `:gap` when it has one)."
  [scored-ms thresholds]
  (for [{:keys [mention cands]} scored-ms
        :let [r (n4/decide cands thresholds)]]
    {:mention mention :result r :cands cands
     :review-cands (filter #(>= (:p %) (:review thresholds)) cands)}))

(defn fold-counts
  "The fit counts of each fold of the cross-validation of the prepared
   mentions `pms` (`n4/fit-counts` on the other folds), and the held-out
   mentions of each: `[[counts held-out] ..]`. Computed once, read by every
   configuration: the rules change the weights, not the counts."
  [ctx pms]
  (let [fold #(n4/fold-of (:mention %) n4/cv-folds)]
    (vec (for [i (range n4/cv-folds)]
           [(n4/fit-counts ctx (remove #(= i (fold %)) pms) nil) (filterv #(= i (fold %)) pms)]))))

(defn cv-scored
  "The held-out scored mentions of `folds` (`fold-counts`) under `rules`, as
   `n4/cv-scored` gives them."
  [folds rules]
  (vec (mapcat (fn [[counts held]]
                 (n4/scored-mentions (n4/fit-model (n4/model-params counts fs/default-thresholds rules)) held))
               folds)))

(defn choose
  "The thresholds of configuration `cfg` on the held-out `scored-ms`: by
   `n4/gap-criterion` with the relative rule, else by
   `n4/threshold-criterion` with no gap."
  [{:keys [gap?]} scored-ms]
  (if gap?
    (let [c (n4/choose-gapped scored-ms)] {:thresholds (:thresholds c) :per-gap (:per-gap c)})
    {:thresholds (:thresholds (n4/choose-thresholds scored-ms nil))}))

(defn run-configuration
  "Configuration `cfg` (`configurations`): its thresholds chosen on the
   cross-validated train folds, then its figures there (`:cv`) and on dev,
   the model fitted on all of train (`:dev`)."
  [cfg folds train-counts dev-pms]
  (let [cv     (cv-scored folds (:rules cfg))
        choice (choose cfg cv)
        th     (:thresholds choice)
        model  (n4/fit-model (n4/model-params train-counts th (:rules cfg)))]
    {:thresholds th
     :per-gap    (:per-gap choice)
     :weights    (:weights model)
     :cv         (by-stratum (fs-rows cv th))
     :dev        (by-stratum (fs-rows (n4/scored-mentions model dev-pms) th))}))

;; ---------------------------------------------------------------------------
;; The Jaccard
;; ---------------------------------------------------------------------------

(defn jaccard-rows
  "N3's Jaccard engine (`hard/engines`, token-set similarity at 0.5) on the
   transcribed names of `ms`: its candidates the entries at or above 0.5. It
   fits nothing, so its train figures are the train mentions themselves."
  [ms pool]
  (let [entries (pr/matcher-entries pool)
        f       (get hard/engines :jaccard)]
    (for [m ms
          :let [ps (reconcile/propose-agent-links [(:text m)] entries)
                cs (map (fn [p] {:id (:authority-id p)}) ps)]]
      {:mention m :result (f (:text m) pool) :cands cs :review-cands []})))

;; ---------------------------------------------------------------------------
;; The grid
;; ---------------------------------------------------------------------------

(defn grid
  "Every configuration on benchmark `b`: `{config {:thresholds :per-gap
   :weights :cv :dev}}`, and `:d-jaccard` `{:cv :dev}`. Train and dev only:
   the test split is never read."
  [b]
  (let [ctx    (n4/pool-context (:pool b))
        train  (n4/prepared ctx (n4/split-mentions b :train))
        dev    (n4/prepared ctx (n4/split-mentions b :dev))
        folds  (fold-counts ctx train)
        counts (n4/fit-counts ctx train nil)]
    (assoc (into (array-map) (for [[k cfg] configurations] [k (run-configuration cfg folds counts dev)]))
           :d-jaccard {:cv  (by-stratum (jaccard-rows (n4/split-mentions b :train) (:pool b)))
                       :dev (by-stratum (jaccard-rows (n4/split-mentions b :dev) (:pool b)))})))

;; ---------------------------------------------------------------------------
;; Printing
;; ---------------------------------------------------------------------------

(defn- f3 [x] (if (nil? x) "n/a" (format "%.3f" (double x))))

(defn table
  "The grid as text: one line per configuration and stratum, for `where`
   (`:cv` or `:dev`)."
  [g where]
  (with-out-str
    (println (format "%-13s %-13s %5s %5s %5s %6s %6s %5s %6s %6s %6s %6s %6s"
                     "config" "stratum" "n" "corr" "merge" "prec" "recall" "rev" "rev/m" "revtru" "abst" "nm-abs" "gen"))
    (doseq [[k r] g
            s (cons :all strata)
            :let [x (get-in r [where s])]
            :when x]
      (println (format "%-13s %-13s %5d %5d %5d %6s %6s %5d %6s %6s %6s %6s %6s"
                       (name k) (name s) (:mentions x) (:correct x) (:false-merges x) (f3 (:precision x)) (f3 (:recall x))
                       (:review x) (f3 (:reviews-per-mention x)) (f3 (:review-truth x)) (f3 (:abstention x))
                       (f3 (:no-match-abstention x)) (f3 (:generation x)))))))

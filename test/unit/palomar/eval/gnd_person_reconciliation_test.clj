(ns palomar.eval.gnd-person-reconciliation-test
  "The M2 mentions linked to a GND snapshot (lot M6): the 142 BnF person
   mentions of `palomar.eval.person-reconciliation` matched, with the M4
   Fellegi-Sunter model fitted on M2 (all five families), against an
   authority index built from a small real GND extract
   (`test/fixtures/er-gold/gnd-persons/README.md`), by its blocking keys.

   What is measured is what the matcher does — proposals, reviews,
   abstentions — and, where a bridge says which GND record is the BnF
   authority a mention's `$3` cites, whether it agrees. The bridges are data
   the matcher never sees: the GND record's `sameAs` to the BnF ARK, an ISNI
   both records carry, and Wikidata's BnF (P268) and GND (P227) ids
   (`bnf-gnd-wikidata.csv`). A BnF authority the bridges send to two GND
   records, or a GND record they give two BnF authorities, is **contested**
   and scored for nothing. No calibration: the model was not measured on
   this pool, so no confidence is written. Measurements, not guarantees;
   write-up in `docs/eval/person-reconciliation.md`, section M6."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.agent-match :as fs]
            [palomar.authority-index :as index]
            [palomar.eval.person-reconciliation :as pr]
            [palomar.plugins.authorities :as authorities]
            [palomar.reconcile :as reconcile]))

(def extract "test/fixtures/er-gold/gnd-persons/gnd-extract.jsonl")
(def wikidata "test/fixtures/er-gold/gnd-persons/bnf-gnd-wikidata.csv")
(def ^:private gnd-prefix "https://d-nb.info/gnd/")

(def ^:private c (delay (pr/corpus)))

(def ^:private idx
  (delay (let [f (doto (java.io.File/createTempFile "palomar-gnd-eval-" ".edn") (.deleteOnExit))]
           (index/build! extract f {:built "2026-11-09"})
           (index/read-index f))))

(def ^:private model
  "The M4 model fitted on every M2 family: m from the BnF gold's true pairs —
   the true pairs of these same 142 mentions, so in-sample here — u and the
   prior from the BnF pool, uncalibrated."
  (delay (pr/supervised-model (pr/fs-fit-data @c) (set (conj pr/families "others")))))

;; ---------------------------------------------------------------------------
;; The truth, by bridges
;; ---------------------------------------------------------------------------

(defn- wikidata-pairs
  "`[[bnf-record-number gnd-uri]…]` of the Wikidata extract."
  []
  (for [line (rest (str/split-lines (slurp wikidata)))
        :let [[bnf gnd] (str/split line #"," -1)]
        :when (seq gnd)]
    [(subs bnf 0 8) (str gnd-prefix gnd)]))

(defn truth
  "BnF record number → `{:gnd #{uri} :by {:ark #{} :isni #{} :wikidata #{}}
   :contested? bool}`, for the BnF authorities of the M2 pool."
  [gnd-entries]
  (let [bnf  (:entries (authorities/read-pool :intermarc (slurp pr/authority-file)))
        wd   (reduce (fn [m [b g]] (update m b (fnil conj #{}) g)) {} (wikidata-pairs))
        has  (fn [iri] (set (keep #(when (some #{iri} (:same-as %)) (:id %)) gnd-entries)))
        by   (into {} (for [b bnf
                            :let [isni (some-> (:isni b) (str/replace #"\s" ""))]]
                        [(:record-number b) {:ark      (has (:id b))
                                             :isni     (if isni (has (str "https://isni.org/isni/" isni)) #{})
                                             :wikidata (get wd (:record-number b) #{})}]))
        gnd  (update-vals by #(apply set/union (vals %)))
        owners (frequencies (mapcat seq (vals gnd)))]
    (into (sorted-map)
          (for [[rn g] gnd]
            [rn {:gnd g :by (by rn)
                 :contested? (boolean (or (< 1 (count g)) (some #(< 1 (owners %)) g)))}]))))

;; ---------------------------------------------------------------------------
;; The run
;; ---------------------------------------------------------------------------

(defn- run-mentions [entries opts]
  (vec (for [m (:mentions @c)]
         (assoc m :result (reconcile/agent-candidates [(select-keys m [:name :dates])] entries opts)))))

(def ^:private runs (delay (run-mentions (:entries @idx) {:model @model :block (index/block @idx)})))

(def ^:private folds
  "Leave-one-family-out, as in M4: each family's mentions scored by the model
   fitted on the four other families."
  (delay (pr/fold-models (pr/fs-fit-data @c))))

(def ^:private lofo-runs
  (delay (vec (for [m (:mentions @c)]
                (assoc m :result (reconcile/agent-candidates [(select-keys m [:name :dates])] (:entries @idx)
                                                             {:model (@folds (:family m)) :block (index/block @idx)}))))))

(defn outcome
  "What the matcher did for one mention, and whether the bridged truth
   agrees: `{:did :propose|:review|:abstain|:nothing :verdict …}`."
  [m truths]
  (let [{ps :proposals ab :abstained} (:result m)
        top (first ps)
        did (cond top          (:decision top)
                  (seq ab)     :abstain
                  :else        :nothing)
        t   (get truths (:truth m))]
    {:did did
     :verdict (cond
                (nil? (:truth m))        :undetermined
                (:contested? t)          :contested
                (empty? (:gnd t))        :unbridged
                (nil? top)               :no-link
                ((:gnd t) (:authority-id top)) :right
                :else                    :wrong)}))

(defn summary [rs truths]
  (let [os (map #(outcome % truths) rs)]
    {:mentions (count rs)
     :did      (into (sorted-map) (frequencies (map :did os)))
     :verdicts (into (sorted-map) (frequencies (map :verdict os)))
     :by       (into (sorted-map) (frequencies (map (juxt :verdict :did) os)))}))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest the-gnd-extract-holds-the-m2-persons-it-can-bridge
  (let [t (truth (:entries @idx))]
    (is (= 197 (count (:entries @idx))))
    (testing "the BnF authorities a bridge sends to the GND, and those it sends nowhere"
      (is (= 19 (count (filter (comp seq :gnd) (vals t)))))
      (is (= 14 (count (remove (comp seq :gnd) (vals t))))))
    (testing "Hetzel and his pseudonym Stahl are contested: the ISNIs and Wikidata pair them crosswise"
      (is (= #{"12008284" "16492882"} (set (keep (fn [[k v]] (when (:contested? v) k)) t))))
      (is (= {:ark #{} :isni #{"https://d-nb.info/gnd/111910582X"} :wikidata #{"https://d-nb.info/gnd/118949047"}}
             (:by (t "12008284")))))
    (testing "every bridged GND id is in the extract"
      (let [ids (set (map :id (:entries @idx)))]
        (is (every? ids (mapcat :gnd (vals t))))))))

(deftest blocking-gives-the-full-scan-s-proposals-on-the-m2-mentions
  (is (= (map :result @runs) (map :result (run-mentions (:entries @idx) {:model @model}))))
  (testing "the blocks hold 4 764 (mention, entry) pairs of the full scan's 142 x 197 = 27 974"
    (is (= 4764 (reduce + (map #(count ((index/block @idx) (:name %))) (:mentions @c)))))))

(deftest the-matcher-sees-no-same-as
  (testing "the sameAs links are data: without them the proposals are the same"
    (let [bare (mapv #(dissoc % :same-as) (:entries @idx))]
      (is (= (map :result @runs) (map :result (run-mentions bare {:model @model})))))))

(deftest the-m2-mentions-on-the-gnd
  (let [rs @runs
        t  (truth (:entries @idx))
        s  (summary rs t)]
    (is (= 142 (:mentions s)))
    (testing "what the matcher did: the mention's top candidate proposed, for review, abstained, or none at all"
      (is (= {:abstain 11 :nothing 7 :propose 112 :review 12} (:did s))))
    (testing "against the bridged truth: every bridged mention's top proposal is its GND record"
      (is (= {:contested 2 :right 109 :unbridged 30 :undetermined 1} (:verdicts s)))
      (is (= {[:contested :review] 2
              [:right :propose] 101 [:right :review] 8
              [:unbridged :abstain] 11 [:unbridged :nothing] 7 [:unbridged :propose] 11 [:unbridged :review] 1
              [:undetermined :review] 1}
             (:by s))))
    (testing "no bridged mention's GND record is rejected for its dates or abstained"
      (is (not-any? (fn [m] (let [g (:gnd (t (:truth m)))]
                              (some #(and g (g (:authority-id %))) (concat (:rejected (:result m)) (:abstained (:result m))))))
                    rs)))
    (testing "the candidates: proposals, abstentions and date rejections"
      (is (= 138 (reduce + (map (comp count :proposals :result) rs))))
      (is (= 2280 (reduce + (map (comp count :abstained :result) rs))))
      (is (= 2346 (reduce + (map (comp count :rejected :result) rs)))))))

(deftest the-m2-mentions-on-the-gnd-held-out
  (testing "with each family's mentions scored by the model fitted on the other four"
    (let [rs @lofo-runs
          s  (summary rs (truth (:entries @idx)))]
      (is (= {:abstain 8 :nothing 7 :propose 91 :review 36} (:did s)))
      (is (= {:contested 2 :right 109 :unbridged 30 :undetermined 1} (:verdicts s)))
      (is (= {[:contested :review] 2
              [:right :propose] 80 [:right :review] 29
              [:unbridged :abstain] 8 [:unbridged :nothing] 7 [:unbridged :propose] 11 [:unbridged :review] 4
              [:undetermined :review] 1}
             (:by s)))
      (is (= 173 (reduce + (map (comp count :proposals :result) rs))))
      (is (not-any? :confidence (mapcat (comp :proposals :result) rs))))))

(deftest no-confidence-is-written-on-a-pool-the-model-was-not-calibrated-on
  (is (not (fs/calibrated? @model (fs/pool-fingerprint (:entries @idx)))))
  (is (not-any? :confidence (mapcat (comp :proposals :result) @runs)))
  (is (not-any? :calibration-proof (mapcat (comp :proposals :result) @runs))))

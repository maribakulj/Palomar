(ns palomar.eval.person-reconciliation-external-post-test
  "Lot O5, after the run (`docs/eval/person-reconciliation-external.md`,
   \"After the run\"): descriptive figures computed from `run.edn` and
   `benchmark.edn` only, never re-scored, pinned so the doc cannot drift
   from them; and the code the run used, recorded after it in
   `postrun.edn`, a record checked against the base commit by
   `dev/eval/bnf-external/verify-postrun.sh`, not against the working tree."
  (:require [clojure.test :refer [deftest is testing]]
            [palomar.eval.person-reconciliation-external :as ext]
            [palomar.eval.person-reconciliation-external-post :as post]
            [palomar.eval.person-reconciliation-hard :as hard]))

(def ^:private cs (delay (post/cases (post/bench-data) (post/run-data))))

(defn- r3 [x] (when x (/ (Math/round (* 1000.0 x)) 1000.0)))

(defn- r3v [xs] (mapv r3 xs))

;; ---------------------------------------------------------------------------
;; The code the run used
;; ---------------------------------------------------------------------------

(deftest the-record-of-the-code-the-run-used-is-well-formed
  ;; A record, not a live check: dev/eval/bnf-external/verify-postrun.sh compares its
  ;; hashes with the base commit (git show). Only the frozen namespaces are live checks.
  (let [p   (hard/read-edn post/postrun-file)
        sha #(and (string? %) (re-matches #"[0-9a-f]{64}" %))]
    (is (= #{:src :files :base-commit :recorded-at :note} (set (keys p))))
    (is (= post/base-commit (:base-commit p)))
    (is (re-matches #"[0-9a-f]{40}" (:base-commit p)))
    (testing "it names src/ and the files the run called, each with a SHA-256"
      (is (sha (:src p)))
      (is (= (set post/code-files) (set (keys (:files p)))))
      (is (every? sha (vals (:files p)))))
    (testing "the doc's Protocol section, which check-frozen! does not read, is the frozen one"
      (is (= (get-in (hard/read-edn ext/freeze-file) [:doc :sha256]) (hard/sha-256 (ext/protocol-section)))))
    (testing "postrun.edn was written after the run"
      (is (.isBefore (java.time.Instant/parse (:finished-at (post/run-data)))
                     (java.time.Instant/parse (:recorded-at p)))))))

;; ---------------------------------------------------------------------------
;; The figures recompute from the run's errors
;; ---------------------------------------------------------------------------

(deftest the-run-s-figures-recompute-from-its-errors
  (let [f   (post/all-figures @cs)
        run (post/run-data)]
    (doseq [e post/engines
            k [:correct :false-merges :linked :persons :recall :precision]]
      (is (= (double (get-in run [:results e :all k])) (double (get-in f [e k]))) [e k]))
    (is (= (:decisions run) (post/decisions @cs)))
    (testing "the Wilson upper bound is clamped to 1 (the frozen code stored 1.0000000000000002)"
      (is (< 1.0 (second (get-in run [:results :c-n3 :all :precision-wilson]))))
      (is (= 1.0 (second (get-in f [:c-n3 :precision-wilson])))))))

;; ---------------------------------------------------------------------------
;; Concentration and its effect on the rules
;; ---------------------------------------------------------------------------

(deftest four-persons-hold-most-linked-mentions
  (let [c (post/concentration (post/bench-data) @cs 4)]
    (is (= [["11917290" 90] ["11920939" 88] ["11914482" 76] ["11896834" 67]] (mapv (juxt :id :n) (:top c))))
    (is (= 0.618 (r3 (:together c))))
    (is (= {:a-jaccard 0.889 :b-m2 0.739 :c-n3 0.882 :d-m2-g6 0.739}
           (update-vals (post/macro-recall @cs) r3)))))

(deftest rule-3-is-robust-and-rule-4-is-fragile
  (let [m   (post/margins @cs)
        loo (post/leave-one-out @cs)]
    (is (= [0.142 0.01] [(r3 (:rule-3 m)) (r3 (:rule-4 m))]))
    (testing "rule 3 (N3 better than M2) holds with any one person or corpus left out"
      (is (= [] (get-in loo [:person :flips-3]) (get-in loo [:corpus :flips-3])))
      (is (= [0.052 "11914482"] (update (get-in loo [:person :min-rule-3]) 0 r3)))
      (is (= [0.055 "mardrus"] (update (get-in loo [:corpus :min-rule-3]) 0 r3))))
    (testing "rule 4 (N3 matches the Jaccard) flips without Mardrus, or without one of two corpora"
      (is (= ["11914482"] (get-in loo [:person :flips-4])))
      (is (= ["christine-de-pizan" "mardrus"] (get-in loo [:corpus :flips-4])))
      (is (= [-0.097 "11914482"] (update (get-in loo [:person :min-rule-4]) 0 r3)))
      (is (= [-0.109 "mardrus"] (update (get-in loo [:corpus :min-rule-4]) 0 r3))))))

(deftest the-person-cluster-bootstrap
  (let [bs (post/bootstrap @cs)]
    (is (= 146 (:clusters bs)))
    (is (= {:a-jaccard [0.657 0.963] :b-m2 [0.482 0.862] :c-n3 [0.657 0.943] :d-m2-g6 [0.482 0.862]}
           (update-vals (:recall bs) r3v)))
    (is (= [0.687 0.995] (r3v (get-in bs [:precision :a-jaccard]))))
    (is (= [1.0 1.0] (r3v (get-in bs [:precision :c-n3]))))
    (testing "(c) − (b) stays above the rule's 0.02; (c) − (a) straddles 0"
      (is (= [0.036 0.359] (r3v (:c-minus-b bs))))
      (is (= [-0.271 0.245] (r3v (:c-minus-a bs)))))))

(deftest the-four-labels-that-read-another-record-change-no-decision
  (let [sub (post/without-other-record-labels @cs)]
    (is (= 4 (- (count @cs) (count sub))))
    (is (= (post/decisions @cs) (post/decisions sub)))
    (is (= [0.139 0.012] [(r3 (:rule-3 (post/margins sub))) (r3 (:rule-4 (post/margins sub)))]))))

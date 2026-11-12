(ns palomar.prepared-pool-property-test
  "Prepared pool statistics change no result (lot O6, ADR 0060 amendment).
   `reconcile/prepare` computes once per run what the matcher reads of the
   whole pool: the surname frequencies and their mean, the pool size, the
   pool's fingerprint. Given to `agent-candidates` or `candidates/rank`, the
   result is the one computed without it, on random pools and mentions.
   Names come from a small alphabet so that shared surnames, near names and
   ids that match are frequent."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [palomar.agent-dates :as dates]
            [palomar.agent-match :as fs]
            [palomar.agent-relations :as ar]
            [palomar.candidates :as candidates]
            [palomar.reconcile :as reconcile]))

(def ^:private model
  "A hand-set model (as `palomar.candidates-test`'s), with a review band and
   a gap, so that proposals, reviews, abstentions and competition all occur."
  (assoc-in
   (fs/model {:m {:name  {:exact 0.9 :initials 0.03 :same-tokens 0.02 :surname-typo 0.01 :forename-overlap 0.01
                          :surname-only 0.01 :forenames-differ 0.005 :jaccard-high 0.005 :jaccard-low 0.005 :none 0.005}
                  :form  {:heading 0.95 :variant 0.05}
                  :dates {:agree 0.8 :compatible 0.1 :unknown 0.1}}
              :u {:name  {:exact 0.01 :initials 0.002 :same-tokens 0.002 :surname-typo 0.001 :forename-overlap 0.01
                          :surname-only 0.005 :forenames-differ 0.05 :jaccard-high 0.02 :jaccard-low 0.2 :none 0.7}
                  :form  {:heading 0.9 :variant 0.1}
                  :dates {:agree 0.05 :compatible 0.15 :unknown 0.8}}
              :prior 0.05})
   [:thresholds :gap] 3.0))

(def ^:private word
  "Surnames, a few of them frequent (so that the surname frequencies and
   their mean weigh), with near names among them."
  (gen/frequency [[6 (gen/return "Dumas")] [4 (gen/return "Verne")]
                  [3 (gen/elements ["Hugo" "Hugi" "Vernes" "Stahl" "Sand" "Sant" "Dupont" "Durand"])]]))
(def ^:private forename (gen/elements ["Victor" "Alexandre" "Jules" "A." "J." "Pierre-Jules" "George"]))

(def ^:private a-name
  (gen/one-of [(gen/fmap (fn [[s f]] (str s ", " f)) (gen/tuple word forename))
               (gen/fmap (fn [[f s]] (str f " " s)) (gen/tuple forename word))
               word]))

(def ^:private life
  (gen/one-of [(gen/return nil)
               (gen/fmap (fn [[b d]] (str b "-" (+ b d))) (gen/tuple (gen/choose 1790 1830) (gen/choose 30 80)))]))

(def ^:private isni
  "Valid ISNIs (their check characters hold), so that an entry states them."
  (gen/elements ["0000000121200982" "0000000080766583" "0000000121012885" "0000000418677351" "0000000123205891"]))

(def ^:private an-entry
  (gen/fmap (fn [[l vs d is]]
              (cond-> {:label l :variants vs}
                d         (assoc :dates (dates/parse-life-dates d))
                (seq is)  (assoc :isni is)))
            (gen/tuple a-name (gen/vector a-name 0 2) life (gen/vector isni 0 1))))

(def ^:private a-pool
  "Up to about 40 entries; each may be repeated as a homonym (same heading,
   another id, its own dates or none)."
  (gen/fmap (fn [[es twins]]
              (let [es (into (vec es) (for [[i d] twins :when (seq es)]
                                        (cond-> (dissoc (nth es (mod i (count es))) :dates :isni)
                                          d (assoc :dates (dates/parse-life-dates d)))))]
                (vec (map-indexed (fn [i e] (assoc e :id (str "a" i))) es))))
            (gen/tuple (gen/vector an-entry 0 30) (gen/vector (gen/tuple gen/nat life) 0 10))))

(def ^:private a-mention
  (gen/one-of [a-name
               (gen/fmap (fn [[n d is]] (cond-> {:name n} d (assoc :dates d) (seq is) (assoc :ids (mapv #(str "ISNI" %) is))))
                         (gen/tuple a-name life (gen/vector isni 0 2)))]))

(def ^:private some-lanes (gen/elements [[:name] [:identifier] [:name :identifier]]))

(defspec prepared-agent-candidates-is-unprepared-agent-candidates 200
  (prop/for-all [pool a-pool
                 ms   (gen/vector a-mention 0 6)]
                (let [p (reconcile/prepare pool)]
                  (and (= (reconcile/agent-candidates ms pool {:model model})
                          (reconcile/agent-candidates ms pool {:model model :prepared p}))
                       (= (reconcile/agent-candidates ms pool {:model model :block (constantly (take 3 pool))})
                          (reconcile/agent-candidates ms pool {:model model :block (constantly (take 3 pool)) :prepared p}))))))

(defspec prepared-rank-is-unprepared-rank 200
  (prop/for-all [pool  a-pool
                 ms    (gen/vector a-mention 0 6)
                 lanes some-lanes]
                (let [p (reconcile/prepare pool)]
                  (and (= (candidates/rank ms pool {:model model :lanes lanes})
                          (candidates/rank ms pool {:model model :lanes lanes :prepared p}))
                       ;; name-only: nothing pool-wide is read
                       (= (candidates/rank ms pool {:lanes lanes})
                          (candidates/rank ms pool {:lanes lanes :prepared p}))))))

(defn- strip [r]
  (-> r
      (dissoc :generation)
      (update-vals (fn [xs] (mapv #(dissoc % :generated-by) xs)))))

(defspec prepared-rank-by-name-is-the-full-scan 200
  (prop/for-all [pool a-pool
                 ms   (gen/vector a-mention 0 6)]
                ;; the name lane with prepared statistics against the matcher
                ;; scanning the whole pool, unprepared, as before lot O6
                (= (reconcile/agent-candidates ms pool {:model model})
                   (strip (candidates/rank ms pool {:model model :prepared (reconcile/prepare pool)})))))

(defspec a-prepared-mean-is-the-computed-mean 300
  (prop/for-all [pool a-pool
                 m    a-name
                 e    an-entry]
                (let [counts (fs/surname-counts pool)
                      n      (count pool)
                      opts   {:surname-counts counts :pool-size n}]
                  (= (fs/compare-pair {:name m} e opts)
                     (fs/compare-pair {:name m} e (reconcile/prepare pool))))))

(defn- distinct-by-id [ms] (vals (into (sorted-map) (map (juxt (comp second :id) identity)) ms)))

(def ^:private an-identified-mention
  "A mention with an id, as `mentions/batch` gives one: two mentions read
   alike are still two mentions."
  (gen/fmap (fn [[i m]] (assoc (if (map? m) m {:name m}) :id [:m i])) (gen/tuple gen/nat a-mention)))

(defspec rank-each-gives-each-mention-its-own-decisions 100
  (prop/for-all [pool  a-pool
                 ms    (gen/fmap #(vec (distinct-by-id %)) (gen/vector an-identified-mention 0 6))
                 lanes some-lanes]
                ;; each mention ranked alone is what `rank-each` gives it
                (let [p    (reconcile/prepare pool)
                      each (candidates/rank-each ms pool {:model model :lanes lanes :prepared p})]
                  (and (= (count ms) (count each))
                       (every? true?
                               (map (fn [m e]
                                      (let [alone (candidates/rank [m] pool {:model model :lanes lanes})]
                                        (and (= (select-keys alone [:proposals :abstained :rejected])
                                                (select-keys e [:proposals :abstained :rejected]))
                                             ;; its own generation, its own mention (review of O6)
                                             (= (first (:generation alone)) (:generation e)))))
                                    ms each))))))

;; ---------------------------------------------------------------------------
;; With frozen anchors (lot M8): the relations fingerprint and the resolved
;; relations prepared once give the unprepared result
;; ---------------------------------------------------------------------------

(def ^:private rel-model
  (ar/with-relations model {:weights {:role {:same-role 1.0 :other-role -4.0 :unknown 0.0}
                                      :neighbours {:shared 1.0 :not-shared 0.0 :unknown 0.0}}}))

(def ^:private roles (gen/elements ["0070" "0440" "0330"]))

(defn- fillers [n]
  (for [i (range n)]
    {:id (keyword (str "f" i)) :mention (keyword (str "fm" i)) :record (keyword (str "rf" i))
     :authority-id (str "http://x/f" i) :role "0070" :status :accepted}))

(defspec prepared-anchors-are-unprepared-anchors 100
  (prop/for-all [pool  (gen/such-that seq a-pool)
                 links (gen/vector (gen/tuple gen/nat (gen/choose 0 3) roles) 0 8)
                 ms    (gen/vector (gen/tuple a-name (gen/choose 0 3) roles life) 1 5)
                 lanes some-lanes]
                (let [anchors (ar/freeze-anchors
                               (concat (for [[j [i r role]] (map-indexed vector links)]
                                         {:id (keyword (str "l" j)) :mention (keyword (str "lm" j)) :record (keyword (str "r" r))
                                          :authority-id (:id (nth pool (mod i (count pool)))) :role role :status :accepted})
                                       (fillers 30)))
                      ms      (vec (for [[k [n r role d]] (map-indexed vector ms)]
                                     (cond-> {:id (keyword (str "m" k)) :name n :record (keyword (str "r" r)) :role role}
                                       d (assoc :dates d))))
                      opts    {:model rel-model :anchors anchors}
                      p       (reconcile/prepare pool opts)]
                  (and (= (reconcile/agent-candidates ms pool opts)
                          (reconcile/agent-candidates ms pool (assoc opts :prepared p)))
                       (= (candidates/rank ms pool (assoc opts :lanes lanes))
                          (candidates/rank ms pool (assoc opts :lanes lanes :prepared p)))))))

(ns palomar.prepared-pool-test
  "Prepared pool statistics (lot O6, ADR 0060 amendment): what the matcher
   reads of the whole pool is computed once per run, not once per mention.
   The property that it changes no result is in
   `palomar.prepared-pool-property-test`."
  (:require [clojure.test :refer [deftest is testing]]
            [palomar.agent-dates :as dates]
            [palomar.agent-match :as fs]
            [palomar.agent-relations :as ar]
            [palomar.candidates :as candidates]
            [palomar.reconcile :as reconcile]))

(def ^:private pool
  [{:id "hugo" :label "Hugo, Victor" :variants [] :isni ["0000000121200982"] :dates (dates/parse-life-dates "1802-1885")}
   {:id "dumas" :label "Dumas, Alexandre" :variants [] :dates (dates/parse-life-dates "1802-1870")}
   {:id "dumas-fils" :label "Dumas, Alexandre" :variants ["Dumas fils"] :dates (dates/parse-life-dates "1824-1895")}
   {:id "verne" :label "Verne, Jules" :variants [] :dates (dates/parse-life-dates "1828-1905")}])

(def ^:private model
  (fs/model {:m {:name  {:exact 0.9 :initials 0.03 :same-tokens 0.02 :surname-typo 0.01 :forename-overlap 0.01
                         :surname-only 0.01 :forenames-differ 0.005 :jaccard-high 0.005 :jaccard-low 0.005 :none 0.005}
                 :form  {:heading 0.95 :variant 0.05}
                 :dates {:agree 0.8 :compatible 0.1 :unknown 0.1}}
             :u {:name  {:exact 0.01 :initials 0.002 :same-tokens 0.002 :surname-typo 0.001 :forename-overlap 0.01
                         :surname-only 0.005 :forenames-differ 0.05 :jaccard-high 0.02 :jaccard-low 0.2 :none 0.7}
                 :form  {:heading 0.9 :variant 0.1}
                 :dates {:agree 0.05 :compatible 0.15 :unknown 0.8}}
             :prior 0.05}))

(def ^:private mentions
  [{:name "Hugo, Victor" :dates "1802-1885"} {:name "Dumas, Alexandre"} {:name "Verne, J."}
   {:name "Гюго, Виктор" :ids ["ISNI0000000121200982"]} "Dumas fils"])

(deftest the-prepared-statistics-are-the-pool-s
  (let [p (reconcile/prepare pool)]
    (is (= (fs/surname-counts pool) (:surname-counts p)))
    (is (= (fs/tf-mean (fs/surname-counts pool) 4) (:tf-mean p)))
    (is (= 4 (:pool-size p)))
    (is (= (fs/pool-fingerprint pool) (:pool-fingerprint p)))
    (testing "an empty pool has no mean"
      (is (= {:pool-size 0 :tf-mean nil} (select-keys (reconcile/prepare []) [:pool-size :tf-mean]))))))

(deftest a-ranking-computes-the-pool-s-statistics-once
  ;; a regression guard: before lot O6, every mention recomputed them
  (let [calls (atom {})
        count! (fn [k f] (fn [& args] (swap! calls update k (fnil inc 0)) (apply f args)))]
    (with-redefs [fs/surname-counts   (count! :surname-counts fs/surname-counts)
                  fs/pool-fingerprint (count! :pool-fingerprint fs/pool-fingerprint)
                  fs/tf-mean          (count! :tf-mean fs/tf-mean)]
      (candidates/rank mentions pool {:model model :lanes [:name :identifier]}))
    (is (= {:surname-counts 1 :pool-fingerprint 1 :tf-mean 1} @calls)
        (str "once per run, for " (count mentions) " mentions"))
    (testing "and not at all when the run is given them"
      (let [p (reconcile/prepare pool)]
        (reset! calls {})
        (with-redefs [fs/surname-counts   (count! :surname-counts fs/surname-counts)
                      fs/pool-fingerprint (count! :pool-fingerprint fs/pool-fingerprint)
                      fs/tf-mean          (count! :tf-mean fs/tf-mean)]
          (candidates/rank mentions pool {:model model :lanes [:name :identifier] :prepared p})
          (reconcile/agent-candidates mentions pool {:model model :prepared p}))
        (is (= {} @calls))))))

(deftest statistics-prepared-for-another-pool-are-refused
  (let [p (reconcile/prepare pool)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"another pool"
                          (reconcile/agent-candidates mentions (vec (take 3 pool)) {:model model :prepared p})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"another pool"
                          (candidates/rank mentions (vec (take 3 pool)) {:model model :prepared p})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not prepared"
                          (reconcile/agent-candidates mentions pool {:model model :prepared {:pool-size 4}})))))

;; ---------------------------------------------------------------------------
;; With anchors (lot M8): the relations fingerprint and the resolved pool
;; ---------------------------------------------------------------------------

(def ^:private rel-model
  (ar/with-relations model {:weights {:role {:same-role 1.0 :other-role -4.0 :unknown 0.0}
                                      :neighbours {:shared 1.0 :not-shared 0.0 :unknown 0.0}}}))

(def ^:private anchors
  (ar/freeze-anchors
   (cons {:id :a1 :mention :x :record :r1 :authority-id "verne" :role "0070" :status :accepted}
         (for [i (range 30)]
           {:id (keyword (str "f" i)) :mention (keyword (str "fm" i)) :record (keyword (str "rf" i))
            :authority-id (str "http://x/f" i) :role "0070" :status :accepted}))))

(defn- relations-fp [] (ar/relations-fingerprint rel-model anchors))

(deftest prepared-anchors-give-the-unprepared-result
  (let [ms  [{:id :m1 :name "Verne, Jules" :record :r2 :role "0070"} {:id :m2 :name "Dumas, Alexandre" :record :r1 :role "0070"}]
        p   (reconcile/prepare pool {:model rel-model :anchors anchors})]
    (is (= (relations-fp) (:relations-fingerprint p)))
    (is (= (reconcile/agent-candidates ms pool {:model rel-model :anchors anchors})
           (reconcile/agent-candidates ms pool {:model rel-model :anchors anchors :prepared p})))
    (is (= (candidates/rank ms pool {:model rel-model :anchors anchors})
           (candidates/rank ms pool {:model rel-model :anchors anchors :prepared p})))
    (testing "statistics prepared without these anchors, or with another model, are refused"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"anchors"
                            (reconcile/agent-candidates ms pool {:model rel-model :anchors anchors :prepared (reconcile/prepare pool)})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"anchors"
                            (reconcile/agent-candidates ms pool {:model (assoc rel-model :prior 0.2) :anchors anchors :prepared p}))))
    (testing "statistics prepared with anchors serve a run without them"
      (is (= (reconcile/agent-candidates ms pool {:model model})
             (reconcile/agent-candidates ms pool {:model model :prepared p}))))))

;; ---------------------------------------------------------------------------
;; Review of lot O6: the prepared map is opaque; model and anchors by value
;; ---------------------------------------------------------------------------

(deftest a-tampered-prepared-map-is-refused
  (let [p (reconcile/prepare pool)]
    (doseq [[what t] [["a mean and a fingerprint" (assoc p :tf-mean 0.9 :pool-fingerprint "0000")]
                      ["a size" (assoc p :pool-size 99)]
                      ["surname counts" (assoc p :surname-counts {"hugo" 40})]
                      ["a fingerprint" (assoc p :pool-fingerprint "0000")]]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"tampered|not what"
                            (reconcile/agent-candidates mentions pool {:model model :prepared t}))
          what))))

(deftest compare-pair-reads-the-mean-only-from-prepared-statistics
  (let [counts (fs/surname-counts pool)
        e      (second pool)
        base   {:surname-counts counts :pool-size 4}
        c      (fs/compare-pair {:name "Dumas, Alexandre"} e base)]
    (is (some? (:tf-mean c)))
    (testing "a plain :tf-mean is not read: the mean is computed"
      (is (= c (fs/compare-pair {:name "Dumas, Alexandre"} e (assoc base :tf-mean 0.9))))
      (is (= c (fs/compare-pair {:name "Dumas, Alexandre"} e (assoc base :tf-mean nil)))))
    (testing "the prepared statistics give the same comparison"
      (is (= c (fs/compare-pair {:name "Dumas, Alexandre"} e (reconcile/prepare pool)))))
    (testing "an explicit nil mean beside surname counts is refused, never silently dropped"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"mean"
                            (fs/compare-pair {:name "Dumas, Alexandre"} e (assoc (reconcile/prepare pool) :palomar.agent-match/tf-mean nil)))))))

(deftest model-and-anchors-are-checked-by-value-the-pool-by-identity
  (let [ms [{:id :m1 :name "Verne, Jules" :record :r2 :role "0070"}]
        p  (reconcile/prepare pool {:model rel-model :anchors anchors})
        r  (reconcile/agent-candidates ms pool {:model rel-model :anchors anchors})]
    (testing "an equal model and equal anchors, other objects: accepted"
      (is (= r (reconcile/agent-candidates ms pool {:model (into {} rel-model) :anchors (into {} anchors) :prepared p}))))
    (testing "another model's relation weights: refused"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"anchors"
                            (reconcile/agent-candidates ms pool {:model (assoc-in rel-model [:relations :weights :role :same-role] 2.0)
                                                                 :anchors anchors :prepared p}))))
    (testing "an equal pool that is another object: refused (by identity, so no run pays a pool-wide comparison)"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"another pool"
                            (reconcile/agent-candidates ms (vec (seq pool)) {:model rel-model :anchors anchors :prepared p}))))))

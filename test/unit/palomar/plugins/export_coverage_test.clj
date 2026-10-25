(ns palomar.plugins.export-coverage-test
  (:require [clojure.test :refer [deftest is testing]]
            [palomar.diagnostics :as dx]
            [palomar.model :as model]
            [palomar.plugins.export-coverage :as cov]))

(defn- rec [& triples]
  (model/record {:id :doc/r :kind :test
                 :assertions (vec (for [[s p v pass] triples]
                                    (model/assertion (cond-> {:subject s :predicate p :value v}
                                                       pass (assoc :provenance {:pass pass})))))}))

(defn- dropped [record written]
  (set (map dx/loss-source-field (cov/unexpressed record written "T"))))

(deftest a-native-written-through-its-canonical-field-is-not-lost
  (let [r (rec [:doc/r :dc/title "T"] [:doc/r :canon/title "T"] [:doc/r :dc/subject "S"])]
    (testing ":dc/title reaches the target as :canon/title"
      (is (= #{:dc/subject} (dropped r #{:canon/title}))))
    (testing "with no canonical target written, the native is reported"
      (is (= #{:dc/title :dc/subject :canon/title} (dropped r #{}))))))

(deftest a-coord-is-reported-only-when-its-field-is-written
  (let [r (rec [:frag/f :canon/title "T"] [:frag/f :canon/lang "en"])]
    (is (= #{:canon/lang} (dropped r #{:canon/title})))
    (is (= #{:canon/title} (dropped r #{})) "the title's own loss covers its language")))

(deftest derived-assertions-are-not-source-content
  (let [r (rec [:ent/w :lrmoo/R33_has_string "T" :infer])]
    (is (empty? (dropped r #{})))))

(deftest the-targets-own-namespace-is-left-to-the-exporter
  (let [r (rec [:doc/r :marc21/f650 "x"])]
    (is (empty? (cov/unexpressed r #{} "marc21" "MARC21")))))

(deftest combined-natives-are-indexed
  (is (contains? cov/combined-natives :intermarc/f100_a)))

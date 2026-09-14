(ns palomar.runtime-entity-test
  "WP-1 slice 2: runtime entity minting (ADR 0014 / 0017). The :entity
   production kind, dedup-by-id at merge, and the idempotency property — a
   re-run mints nothing (ADR 0008)."
  (:require [clojure.test :refer [deftest is testing]]
            [palomar.model :as model]
            [palomar.rules :as rules]
            [palomar.runtime :as runtime]))

(deftest merge-production-merges-entity
  (let [e (model/entity {:id :ent/w :kind :lrmoo/work})
        r (runtime/merge-productions (model/record {:id :record/r1 :kind :book})
                                     [{:kind :entity :value e}])]
    (is (= [e] (:entities r)))))

(deftest entity-merge-dedups-by-id
  (testing "two productions with the same entity id collapse to one (ADR 0008)"
    (let [e1 (model/entity {:id :ent/w :kind :lrmoo/work})
          e2 (model/entity {:id :ent/w :kind :lrmoo/work
                            :provenance (model/provenance {:pass :infer})})
          r  (runtime/merge-productions (model/record {:id :record/r1 :kind :book})
                                        [{:kind :entity :value e1}
                                         {:kind :entity :value e2}])]
      (is (= 1 (count (:entities r)))))))

;; A realistic WEMI-derivation-style minting rule: a compiled-rule whose runner
;; computes a content-based id (mint-entity-id) and emits the Work entity plus
;; a claim about it. This is the path WP-3 WEMI-derivation rules will use.
(defn- minting-rule [key]
  (rules/compiled-rule
   {:id    :test/mint-work
    :phase :infer
    :runner
    (fn [_record]
      (let [wid (model/mint-entity-id :lrmoo/work key)]
        [{:kind  :entity
          :value (model/entity {:id wid :kind :lrmoo/work
                                :provenance (model/provenance {:pass :infer})})}
         {:kind  :assertion
          :value (model/assertion {:subject wid :predicate :lrmoo/title
                                   :value "Madame Bovary" :status :proposed
                                   :provenance (model/provenance {:pass :infer})})}]))}))

(deftest minting-rule-end-to-end
  (let [rule (minting-rule "flaubert|madame bovary")
        {:keys [record]} (runtime/run-phase (model/record {:id :record/r1 :kind :book})
                                            [rule] :infer)]
    (testing "the Work entity is minted into :entities"
      (is (= 1 (count (:entities record))))
      (is (= :lrmoo/work (:kind (first (:entities record))))))
    (testing "the claim about the Work is merged, subject = the minted id"
      (is (= (:id (first (:entities record)))
             (:subject (first (:assertions record))))))
    (testing "the record stays consistent (the entity is a known subject)"
      (is (model/record-consistent? record))
      (is (nil? (model/explain-consistency record))))))

(deftest minting-is-idempotent
  (testing "re-running the infer phase mints nothing new (ADR 0008)"
    (let [rule  (minting-rule "flaubert|madame bovary")
          base  (model/record {:id :record/r1 :kind :book})
          once  (:record (runtime/run-phase base [rule] :infer))
          twice (:record (runtime/run-phase once [rule] :infer))]
      (is (= 1 (count (:entities once))))
      (is (= (:entities once) (:entities twice)))
      (is (= 1 (count (:assertions once))))
      (is (= (:assertions once) (:assertions twice))))))

(ns palomar.model-entity-test
  "WP-1 slice 1: synthesized entities in the IR (ADR 0014 / 0016 / 0017).
   Covers content-based identity, the :entities collection, and the
   consistency contract extension."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.model :as model]))

(deftest mint-entity-id-deterministic-and-content-based
  (testing "same kind + key => same id (idempotency seed, ADR 0008)"
    (is (= (model/mint-entity-id :lrmoo/work "flaubert|madame bovary")
           (model/mint-entity-id :lrmoo/work "flaubert|madame bovary"))))
  (testing "different key => different id"
    (is (not= (model/mint-entity-id :lrmoo/work "flaubert|madame bovary")
              (model/mint-entity-id :lrmoo/work "hugo|les miserables"))))
  (testing "same key, different kind => different id"
    (is (not= (model/mint-entity-id :lrmoo/work "x|y")
              (model/mint-entity-id :lrmoo/expression "x|y"))))
  (testing "shape: an :ent-namespaced keyword carrying the kind name"
    (let [id (model/mint-entity-id :lrmoo/work "k")]
      (is (keyword? id))
      (is (= "ent" (namespace id)))
      (is (str/starts-with? (name id) "work.")))))

(deftest mint-entity-id-rejects-bad-input
  (is (thrown? clojure.lang.ExceptionInfo (model/mint-entity-id "not-kw" "k")))
  (is (thrown? clojure.lang.ExceptionInfo (model/mint-entity-id :lrmoo/work "")))
  (is (thrown? clojure.lang.ExceptionInfo (model/mint-entity-id :lrmoo/work nil))))

(deftest entity-constructor-and-schema
  (let [e (model/entity {:id (model/mint-entity-id :lrmoo/work "k")
                         :kind :lrmoo/work})]
    (is (model/valid-entity? e))
    (is (= :lrmoo/work (:kind e))))
  (testing "provenance optional and validated"
    (is (model/valid-entity?
         (model/entity {:id :ent/x :kind :lrmoo/work
                        :provenance (model/provenance {:pass :infer})})))))

(deftest known-subjects-includes-entities
  (let [wid (model/mint-entity-id :lrmoo/work "k")
        r   (model/record {:id :record/r1 :kind :book
                           :fragments [(model/fragment {:id :frag/x :source :s})]
                           :entities  [(model/entity {:id wid :kind :lrmoo/work})]})]
    (is (= #{:record/r1 :frag/x wid} (model/known-subjects r)))))

(deftest record-consistent-with-entity-subjects
  (testing "an assertion whose subject is a minted entity is consistent"
    (let [wid (model/mint-entity-id :lrmoo/work "k")
          r   (model/record
               {:id :record/r1 :kind :book
                :entities   [(model/entity {:id wid :kind :lrmoo/work
                                            :provenance (model/provenance {:pass :infer})})]
                :assertions [(model/assertion
                              {:subject wid :predicate :lrmoo/title
                               :value "Madame Bovary"
                               :provenance (model/provenance {:pass :infer})})]})]
      (is (model/record-consistent? r))
      (is (nil? (model/explain-consistency r)))
      (is (model/valid-record? r))))
  (testing "an assertion on an unknown entity id is inconsistent"
    (let [r (model/record
             {:id :record/r1 :kind :book
              :assertions [(model/assertion {:subject :ent/ghost
                                             :predicate :lrmoo/title :value "x"})]})]
      (is (not (model/record-consistent? r)))
      (is (= 1 (count (:bad-assertions (model/explain-consistency r))))))))

(deftest entities-collection-is-optional
  (testing "records without entities stay valid (the change is additive)"
    (let [r (model/record {:id :record/r1 :kind :book})]
      (is (model/valid-record? r))
      (is (= #{:record/r1} (model/known-subjects r))))))

(ns palomar.plugins.lrmoo.view-test
  "Unit tests for the typed LRMoo traversal (WP-2 slice 2, ADR 0013): kind
   selectors and WEMI navigation up and down the chain, on a hand-built graph."
  (:require [clojure.test :refer [deftest is testing]]
            [palomar.model :as model]
            [palomar.plugins.lrmoo.view :as view]))

(def wid :ent/work)
(def eid :ent/expr)
(def mid :ent/manif)
(def iid :ent/item)

(def rec
  (model/record
   {:id :record/r1 :kind :book
    :entities [(model/entity {:id wid :kind :lrmoo/F1_Work})
               (model/entity {:id eid :kind :lrmoo/F2_Expression})
               (model/entity {:id mid :kind :lrmoo/F3_Manifestation})
               (model/entity {:id iid :kind :lrmoo/F5_Item})]
    :assertions [(model/assertion {:subject wid :predicate :lrmoo/R3_is_realised_in
                                   :value (model/reference eid)})
                 (model/assertion {:subject mid :predicate :lrmoo/R4_embodies
                                   :value (model/reference eid)})
                 (model/assertion {:subject iid :predicate :lrmoo/R7_exemplifies
                                   :value (model/reference mid)})]}))

(deftest the-graph-is-consistent-and-valid
  (is (model/valid-record? rec))
  (is (model/record-consistent? rec)))

(deftest kind-selectors
  (is (= [wid] (mapv :id (view/works rec))))
  (is (= [eid] (mapv :id (view/expressions rec))))
  (is (= [mid] (mapv :id (view/manifestations rec))))
  (is (= [iid] (mapv :id (view/items rec))))
  (is (= :lrmoo/F1_Work (:kind (view/entity-by-id rec wid))))
  (is (view/lrmoo-entity? rec wid))
  (is (not (view/lrmoo-entity? rec :ent/nope))))

(deftest navigate-down-the-chain
  (is (= [eid] (view/expressions-of rec wid)))
  (is (= [mid] (view/manifestations-of rec eid)))
  (is (= [iid] (view/items-of rec mid))))

(deftest navigate-up-the-chain
  (is (= [wid] (view/work-of rec eid)))
  (is (= [eid] (view/expression-of rec mid)))
  (is (= [mid] (view/manifestation-of rec iid))))

(deftest full-chain-work-to-item
  (testing "Work →* Item by composing down-navigators"
    (is (= [iid]
           (->> (view/expressions-of rec wid)
                (mapcat #(view/manifestations-of rec %))
                (mapcat #(view/items-of rec %))
                vec)))))

(deftest navigators-ignore-non-reference-and-foreign-predicates
  (let [r (update rec :assertions conj
                  (model/assertion {:subject wid :predicate :lrmoo/R3_is_realised_in
                                    :value "a literal, not a reference"}))]
    (testing "a non-reference value on the same predicate is not followed"
      (is (= [eid] (view/expressions-of r wid))))))

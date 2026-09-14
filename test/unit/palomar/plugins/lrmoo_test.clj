(ns palomar.plugins.lrmoo-test
  "Unit tests for the LRMoo rich-pivot vocabulary plugin (WP-2 slice 1,
   ADR 0013/0017): the WEMI vocabulary, IRI faithfulness to LRMoo v1.0, the
   WEMI chain, and the plugin shape."
  (:require [clojure.test :refer [deftest is testing]]
            [palomar.plugins :as plugins]
            [palomar.plugins.lrmoo :as lrmoo]))

(deftest wemi-classes-are-the-four
  (is (= #{:lrmoo/F1_Work :lrmoo/F2_Expression
           :lrmoo/F3_Manifestation :lrmoo/F5_Item}
         lrmoo/entity-kinds))
  (doseq [k lrmoo/entity-kinds] (is (lrmoo/entity-kind? k)))
  (is (not (lrmoo/entity-kind? :lrmoo/R3_is_realised_in)))  ; a property, not a class
  (is (not (lrmoo/entity-kind? :canon/title))))             ; another vocabulary

(deftest vocabulary-is-the-wemi-links
  (is (= #{:lrmoo/R3_is_realised_in :lrmoo/R4_embodies :lrmoo/R7_exemplifies}
         lrmoo/vocabulary))
  (doseq [p lrmoo/vocabulary] (is (lrmoo/vocabulary? p)))
  (is (not (lrmoo/vocabulary? :lrmoo/F1_Work))))

(deftest iri-is-faithful-to-the-spec
  (is (= "http://iflastandards.info/ns/lrm/lrmoo/F1_Work"
         (lrmoo/iri :lrmoo/F1_Work)))
  (is (= "http://iflastandards.info/ns/lrm/lrmoo/R3_is_realised_in"
         (lrmoo/iri :lrmoo/R3_is_realised_in))))

(deftest wemi-links-are-well-formed
  (testing "every link is [entity-kind vocabulary-predicate entity-kind]"
    (doseq [[from prop to] lrmoo/wemi-links]
      (is (lrmoo/entity-kind? from))
      (is (lrmoo/vocabulary? prop))
      (is (lrmoo/entity-kind? to))))
  (testing "the chain touches all four WEMI classes"
    (let [kinds (into #{} (mapcat (fn [[f _ t]] [f t])) lrmoo/wemi-links)]
      (is (= lrmoo/entity-kinds kinds)))))

(deftest plugin-is-schema-valid-and-registerable
  (is (plugins/valid-plugin? lrmoo/plugin))
  (let [reg (plugins/register plugins/empty-registry lrmoo/plugin)]
    (is (= lrmoo/plugin (plugins/lookup reg :palomar/lrmoo)))))

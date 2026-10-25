(ns palomar.model-identity-test
  "Assertion identity and annotations (ADR 0027)."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [palomar.model :as model]))

(deftest equal-values-have-one-canonical-form
  (testing "map key order, namespace-map printing and hash order do not change the digest"
    (let [a {:value/kind :reference :value/target :x}
          b (array-map :value/target :x :value/kind :reference)
          h (into (hash-map) (map vector (range 20) (range 20)))
          h' (into (sorted-map) h)]
      (is (= a b))
      (is (= (model/content-digest a 16) (model/content-digest b 16)))
      (is (= (model/content-digest h 16) (model/content-digest h' 16)))
      (is (= (binding [*print-namespace-maps* true] (model/content-digest a 16))
             (binding [*print-namespace-maps* false] (model/content-digest a 16))))))
  (testing "values of different types stay apart"
    (is (= 3 (count (into #{} (map #(model/content-digest % 16)) ["1" 1 :1]))))))

(deftest an-assertion-keeps-its-id-through-curation
  (let [a (model/assertion {:subject :r/s :predicate :canon/title :value "T" :status :proposed})]
    (is (= (model/assertion-id a) (model/assertion-id (assoc a :status :accepted :confidence 0.4))))
    (is (not= (model/assertion-id a) (model/assertion-id (assoc a :value "U"))))
    (is (= "asr" (namespace (model/assertion-id a))))))

(deftest annotations-are-about-assertions-and-may-outlive-them
  (let [a   (model/assertion {:subject :r/s :predicate :canon/title :value "T"})
        n   (model/annotation {:about a :predicate :x/supported-by :value "an agent"})
        rec (model/record {:id :r/s :kind :doc :assertions [a] :annotations [n]})]
    (is (model/valid-record? rec))
    (is (model/record-consistent? rec))
    (is (= [n] (model/annotations-of rec a)))
    (is (= a (model/assertion-by-id rec (:about n))))
    (is (empty? (model/orphan-annotations rec)))
    (testing "dropping the claim leaves the annotation as an orphan, not an error"
      (let [dropped (assoc rec :assertions [])]
        (is (model/valid-record? dropped))
        (is (= [n] (model/orphan-annotations dropped)))))))

(deftest an-assertion-id-reads-back-as-edn
  (testing "whatever its digest, the id is a keyword EDN reads back (ADR 0034)"
    (doseq [i (range 64)]
      (let [id (model/assertion-id {:subject :rec/one :predicate :x/y :value i})]
        (is (= id (edn/read-string (str id))))
        (is (re-matches #"h[0-9a-f]{16}" (name id)))))))

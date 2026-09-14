(ns palomar.plugins.lrmoo.crm-import-test
  "CRM → LRM round-trip (ADR 0019): the directionality rule, demonstrated on our
   own exports rather than asserted. A WEMI record (F1 Work, F2 Expression, F3
   Manifestation + R3/R4) is exported two ways and read back:

   - via the **additive** `:crm` view (which keeps the LRMoo F-classes) → the
     downcast recovers F1/F2/F3 *exactly* — lossless;
   - via the **pure** `:crm-only` view (F2/F3 both flattened to E73) → the Expression
     and Manifestation can no longer be distinguished — the downcast collapses into
     `:ambiguity-collapsed`, precisely the loss `crm/crm-only-losses` reported on the
     way down.

   No external data: the CRM is produced by our own exporter."
  (:require [clojure.test :refer [deftest is testing]]
            [palomar.diagnostics :as dx]
            [palomar.model :as model]
            [palomar.plugins.lrmoo :as lrmoo]
            [palomar.plugins.lrmoo.crm :as crm]
            [palomar.plugins.lrmoo.crm-import :as crm-import]))

(def ^:private work-iri  "http://example.org/work/bovary")
(def ^:private expr-iri  "http://example.org/expression/bovary-fr")
(def ^:private manif-iri "http://data.bnf.fr/ark:/12148/cb11938746n")

(defn- wemi-record
  "A hand-built WEMI graph (the entity-relation shape an INTERMARC-NG / F-typed CRM
   source would carry), each entity given an explicit authority `:iri` so the export
   uses stable IRIs."
  []
  (let [w :ent/work e :ent/expr m :ent/manif]
    (model/record
     {:id       :rec/bovary :kind :book
      :entities [(model/entity {:id m :kind :lrmoo/F3_Manifestation :iri manif-iri})
                 (model/entity {:id e :kind :lrmoo/F2_Expression    :iri expr-iri})
                 (model/entity {:id w :kind :lrmoo/F1_Work          :iri work-iri})]
      :assertions [(model/assertion {:subject m :predicate :lrmoo/R4_embodies
                                     :value (model/reference e)})
                   (model/assertion {:subject w :predicate :lrmoo/R3_is_realised_in
                                     :value (model/reference e)})]})))

(defn- original-typing [record]
  (into {} (for [e (:entities record)] [(:iri e) (:kind e)])))

(defn- original-relations [record]
  (let [idx (into {} (for [e (:entities record)] [(:id e) (:iri e)]))]
    (set (for [a (:assertions record)
               :when (and (lrmoo/vocabulary? (:predicate a))
                          (model/reference-value? (:value a)))]
           [(idx (:subject a)) (:predicate a) (idx (:value/target (:value a)))]))))

(deftest f-typed-crm-downcasts-to-lrm-losslessly
  (testing "the additive :crm view keeps the F-classes, so CRM → LRM recovers them exactly"
    (let [rec       (wemi-record)
          recovered (crm-import/recover (crm/->ntriples rec))]
      (is (= (original-typing rec) (:typed recovered)))      ; F1/F2/F3 recovered, by IRI
      (is (= {manif-iri :lrmoo/F3_Manifestation
              expr-iri  :lrmoo/F2_Expression
              work-iri  :lrmoo/F1_Work}
             (:typed recovered)))
      (is (empty? (:ambiguous recovered)))                   ; nothing collapsed
      (is (= (original-relations rec) (:relations recovered))))))

(deftest pure-crm-cannot-downcast-the-e73-collapse
  (testing "the pure :crm-only view flattens F2 and F3 to E73 — the downcast cannot tell them apart"
    (let [rec       (wemi-record)
          recovered (crm-import/recover (crm/->crm-only-ntriples rec))]
      (testing "the Work survives (E89 is F1's super-class, 1:1)"
        (is (= :lrmoo/F1_Work (get (:typed recovered) work-iri))))
      (testing "the Expression and Manifestation are irrecoverably ambiguous (both E73)"
        (is (= #{:lrmoo/F2_Expression :lrmoo/F3_Manifestation} (get (:ambiguous recovered) expr-iri)))
        (is (= #{:lrmoo/F2_Expression :lrmoo/F3_Manifestation} (get (:ambiguous recovered) manif-iri)))
        (is (not (contains? (:typed recovered) expr-iri)))
        (is (not (contains? (:typed recovered) manif-iri))))
      (testing "the WEMI relations are gone too (R3/R4 became generic P-properties)"
        (is (empty? (:relations recovered))))))
  (testing "the collapse the importer hits is exactly the one the down-projection reported"
    (let [losses (crm/crm-only-losses (wemi-record))]
      (is (some #(and (= :under-specified (dx/loss-category %))
                      (= :lrmoo/F3_Manifestation (dx/loss-source-field %))) losses)))))

(deftest recovered-ir-mints-entities-and-flags-ambiguity-as-loss
  (testing ":crm → ->record mints the three WEMI entities and the links, no loss"
    (let [{:keys [record diagnostics]} (crm-import/->record (crm/->ntriples (wemi-record)) :rec/back)]
      (is (= #{:lrmoo/F1_Work :lrmoo/F2_Expression :lrmoo/F3_Manifestation}
             (set (map :kind (:entities record)))))
      (is (= 2 (count (:assertions record))))                ; R4 + R3
      (is (empty? diagnostics))))
  (testing ":crm-only → ->record mints only the Work, and reports the E73 collapse as :ambiguity-collapsed"
    (let [{:keys [record diagnostics]} (crm-import/->record (crm/->crm-only-ntriples (wemi-record)) :rec/back)]
      (is (= [:lrmoo/F1_Work] (map :kind (:entities record))))
      (is (= 2 (count diagnostics)))                          ; the Expression + the Manifestation
      (is (every? #(= :ambiguity-collapsed (dx/loss-category %)) diagnostics)))))

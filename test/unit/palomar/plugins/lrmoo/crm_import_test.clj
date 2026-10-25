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
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.diagnostics :as dx]
            [palomar.model :as model]
            [palomar.plugins.lrmoo :as lrmoo]
            [palomar.plugins.lrmoo.crm :as crm]
            [palomar.plugins.lrmoo.crm-import :as crm-import]
            [palomar.plugins.lrmoo.export :as export]
            [palomar.plugins.lrmoo.ntriples]))

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
      (testing "the Work is not recovered: E89 is F1's super-class, but an E89 need not be a Work"
        (is (not (contains? (:typed recovered) work-iri)))
        (is (= #{:lrmoo/F1_Work} (get (:super-class-only recovered) work-iri))))
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

(deftest external-crm-super-classes-are-not-downcast
  (testing "an E89 or E24 from outside Palomar is not assumed to be a Work or an Item"
    (let [crm   "http://www.cidoc-crm.org/cidoc-crm/"
          rdf   "<http://www.w3.org/1999/02/22-rdf-syntax-ns#type>"
          nt    (str "<urn:ext:theory> " rdf " <" crm "E89_Propositional_Object> .\n"
                     "<urn:ext:sculpture> " rdf " <" crm "E24_Physical_Human-Made_Thing> .\n")
          recovered (crm-import/recover nt)
          {:keys [record diagnostics]} (crm-import/->record nt :rec/ext)]
      (is (empty? (:typed recovered)))
      (is (= {"urn:ext:theory"    #{:lrmoo/F1_Work}
              "urn:ext:sculpture" #{:lrmoo/F5_Item}}
             (:super-class-only recovered)))
      (is (empty? (:entities record)))                       ; nothing fabricated
      (is (= 2 (count diagnostics)))
      (is (every? #(= :under-specified (dx/loss-category %)) diagnostics))
      (is (= #{:crm/E89_Propositional_Object :crm/E24_Physical_Human-Made_Thing}
             (set (map dx/loss-source-field diagnostics)))))))

(deftest recovered-ir-mints-entities-and-flags-ambiguity-as-loss
  (testing ":crm → ->record mints the three WEMI entities and the links, no loss"
    (let [{:keys [record diagnostics]} (crm-import/->record (crm/->ntriples (wemi-record)) :rec/back)]
      (is (= #{:lrmoo/F1_Work :lrmoo/F2_Expression :lrmoo/F3_Manifestation}
             (set (map :kind (:entities record)))))
      (is (= 2 (count (:assertions record))))                ; R4 + R3
      (is (empty? diagnostics))))
  (testing ":crm-only → ->record mints no entity: E89 is under-specified, the E73 pair ambiguous"
    (let [{:keys [record diagnostics]} (crm-import/->record (crm/->crm-only-ntriples (wemi-record)) :rec/back)]
      (is (empty? (:entities record)))
      (is (= 5 (count diagnostics)))  ; the Work + the Expression + the Manifestation, and their two links
      (is (= {:under-specified 1 :ambiguity-collapsed 2 :dropped 2}
             (frequencies (map dx/loss-category diagnostics))))
      (is (= #{:crm/E89_Propositional_Object :crm/E73_Information_Object
               (keyword "rdf" "http://www.cidoc-crm.org/cidoc-crm/P165_incorporates")
               (keyword "rdf" "http://www.cidoc-crm.org/cidoc-crm/P130_shows_features_of")}
             (set (map dx/loss-source-field diagnostics))))
      (testing "the generic P links between unrecovered nodes are reported, not silently dropped (audit S17)"
        (is (every? #(str/includes? (:message %) "not recovered")
                    (filter #(= :dropped (dx/loss-category %)) diagnostics)))))))

(deftest strings-are-recovered-and-everything-else-is-reported
  (testing "an F-typed node gets its R33 string back; an unknown predicate is reported, not dropped silently"
    (let [lrm "http://iflastandards.info/ns/lrm/lrmoo/"
          rdf "<http://www.w3.org/1999/02/22-rdf-syntax-ns#type>"
          nt  (str "<urn:m> " rdf " <" lrm "F3_Manifestation> .\n"
                   "<urn:m> <" lrm "R33_has_string> \"Les Mis\\u00E9rables\"@fr .\n"
                   "<urn:m> <http://purl.org/dc/terms/extent> \"300 p.\" .\n")
          {:keys [record diagnostics]} (crm-import/->record nt :rec/s)]
      (is (= ["Les Misérables"] (keep #(when (= :lrmoo/R33_has_string (:predicate %)) (:value %))
                                      (:assertions record))))
      (is (some #(and (= :coerced (dx/loss-category %))
                      (= :lrmoo/R33_has_string (dx/loss-source-field %))) diagnostics))  ; @fr not carried
      (is (some #(= (keyword "rdf" "http://purl.org/dc/terms/extent") (dx/loss-source-field %)) diagnostics)))))

(deftest a-title-round-trips-through-its-appellation
  (testing "strings exported via P1 -> E41 Appellation -> P190 come back on their entity, with nothing reported lost"
    (let [rec (-> (wemi-record)
                  (update :assertions conj
                          (model/assertion {:subject :ent/manif :predicate :lrmoo/R33_has_string
                                            :value "Madame Bovary : mœurs de province"})))
          {:keys [record diagnostics]} (crm-import/->record (crm/->ntriples rec) :rec/back)]
      (is (= [[manif-iri "Madame Bovary : mœurs de province"]]
             (for [a (:assertions record) :when (= :lrmoo/R33_has_string (:predicate a))]
               [(:subject a) (:value a)])))
      (is (empty? diagnostics)))))

(deftest the-earlier-nomen-form-is-still-read
  (testing "entity -P1-> F12 Nomen -R33-> \"s\", as exports before ADR 0022's revision wrote it"
    (let [lrm "http://iflastandards.info/ns/lrm/lrmoo/"
          rdf "<http://www.w3.org/1999/02/22-rdf-syntax-ns#type>"
          nt  (str "<urn:m> " rdf " <" lrm "F3_Manifestation> .\n"
                   "<urn:m> <http://www.cidoc-crm.org/cidoc-crm/P1_is_identified_by> <urn:n> .\n"
                   "<urn:n> " rdf " <" lrm "F12_Nomen> .\n"
                   "<urn:n> <" lrm "R33_has_string> \"T\" .\n")
          {:keys [record diagnostics]} (crm-import/->record nt :rec/n)]
      (is (= [["urn:m" "T"]] (for [a (:assertions record) :when (= :lrmoo/R33_has_string (:predicate a))]
                               [(:subject a) (:value a)])))
      (is (empty? diagnostics)))))

(deftest another-type-on-a-typed-node-is-reported
  (testing "a typed Manifestation that is also a schema:Book: the extra type is not silently absorbed (audit L4)"
    (let [lrm "http://iflastandards.info/ns/lrm/lrmoo/"
          rdf "<http://www.w3.org/1999/02/22-rdf-syntax-ns#type>"
          nt  (str "<urn:m> " rdf " <" lrm "F3_Manifestation> .\n"
                   "<urn:m> " rdf " <http://schema.org/Book> .\n")
          {:keys [diagnostics]} (crm-import/->record nt :rec/t)]
      (is (= [(keyword "rdf" "http://www.w3.org/1999/02/22-rdf-syntax-ns#type")]
             (map dx/loss-source-field diagnostics))))))

(deftest a-blank-node-entity-gets-a-minted-id-not-a-fake-iri
  (testing "_:b0 typed F3 becomes an entity with a keyword id and no :iri, so re-export stays valid N-Triples"
    (let [nt  (str "_:b0 <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> "
                   "<http://iflastandards.info/ns/lrm/lrmoo/F3_Manifestation> .\n")
          {:keys [record]} (crm-import/->record nt :rec/b)
          e   (first (:entities record))]
      (is (keyword? (:id e)))
      (is (nil? (:iri e)))
      (is (seq (palomar.plugins.lrmoo.ntriples/parse (export/->ntriples record)))))))

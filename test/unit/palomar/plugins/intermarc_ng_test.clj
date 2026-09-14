(ns palomar.plugins.intermarc-ng-test
  "INTERMARC-NG entity-relation spoke (ADR 0019), on a spec-faithful synthetic OEMI
   corpus. Proves the graph→graph path end to end: NG entity-records import to LRMoo
   entities + WEMI relations (no floor, no inference), serialise through the existing
   LRMoo / CIDOC-CRM exporters, and round-trip back via `crm-import` — the flagship
   BnF Transition-bibliographique conversion, demonstrated pending real native data."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.convert :as convert]
            [palomar.model :as model]
            [palomar.plugins.intermarc-ng :as ng]
            [palomar.plugins.lrmoo :as lrmoo]
            [palomar.plugins.lrmoo.crm :as crm]
            [palomar.plugins.lrmoo.crm-import :as crm-import]
            [palomar.plugins.lrmoo.export :as export]
            [palomar.plugins.lrmoo.linked-art :as linked-art]))

(def ^:private fixture
  (slurp "test/fixtures/documentary/intermarc-ng/examples/fleurs-du-mal-oemi.xml"))

(def ^:private work-iri  "http://data.bnf.fr/ark:/12148/cb11947964w")
(def ^:private expr-iri  "http://data.bnf.fr/ark:/12148/cb11947965v")
(def ^:private manif-iri "http://data.bnf.fr/ark:/12148/cb44496975d")
(def ^:private baudelaire-isni "https://isni.org/isni/0000000121221863")

(defn- import1 [] (first (ng/ingest fixture {})))

(defn- wemi-iri->kind [rec]
  (into {} (for [e (:entities rec) :when (lrmoo/entity-kind? (:kind e))] [(:iri e) (:kind e)])))

(defn- canon-vals [rec pred]
  (set (for [a (:assertions rec) :when (= pred (:predicate a))] (:value a))))

(defn- label-of [rec iri]
  (let [id (some #(when (= iri (:iri %)) (:id %)) (:entities rec))]
    (some #(when (and (= id (:subject %)) (= :lrmoo/R33_has_string (:predicate %))) (:value %))
          (:assertions rec))))

(defn- relations [rec]
  (let [idx (into {} (map (juxt :id :iri)) (:entities rec))]
    (set (for [a (:assertions rec)
               :when (and (lrmoo/vocabulary? (:predicate a)) (model/reference-value? (:value a)))]
           [(idx (:subject a)) (:predicate a) (idx (:value/target (:value a)))]))))

(deftest imports-the-oemi-graph-as-lrmoo-entities-and-relations
  (testing "each NG OEMI entity-record becomes the right LRMoo entity, keyed by its ARK IRI"
    (let [rec (import1)]
      (is (= {work-iri  :lrmoo/F1_Work
              expr-iri  :lrmoo/F2_Expression
              manif-iri :lrmoo/F3_Manifestation}
             (wemi-iri->kind rec)))
      (testing "the Person entity-record becomes an authority-identified :crm/E21_Person"
        (let [person (first (filter #(= :crm/E21_Person (:kind %)) (:entities rec)))]
          (is (= baudelaire-isni (:iri person)))))
      (testing "the 700 'A pour créateur' relation surfaces the controlled creator as :canon/agent"
        (is (= #{"Baudelaire, Charles"} (canon-vals rec :canon/agent))))
      (testing "the Manifestation floor projection (title + ARK identifier) is emitted too"
        (is (= #{"Les fleurs du mal"} (canon-vals rec :canon/title)))
        (is (= #{"ark:/12148/cb44496975d"} (canon-vals rec :canon/identifier))))
      (testing "the access-point labels (150/140/245 $a) become :lrmoo/R33_has_string"
        (is (= "Les fleurs du mal" (label-of rec work-iri)))
        (is (= "Les fleurs du mal (texte, français)" (label-of rec expr-iri)))
        (is (= "Les fleurs du mal" (label-of rec manif-iri))))
      (testing "the fundamental relations (740 Matérialise, 750 Réalise) become R4/R3, direction-correct"
        (is (= #{[manif-iri :lrmoo/R4_embodies       expr-iri]    ; 740, F3→F2
                 [work-iri  :lrmoo/R3_is_realised_in expr-iri]}   ; 750 flipped to F1→F2
               (relations rec)))))))

(deftest serialises-through-the-lrmoo-and-crm-exporters-unchanged
  (testing "the imported graph renders as LRMoo RDF — F-classes + WEMI relations"
    (let [nt (export/->ntriples (import1))]
      (is (str/includes? nt (lrmoo/iri :lrmoo/F1_Work)))
      (is (str/includes? nt (lrmoo/iri :lrmoo/F3_Manifestation)))
      (is (str/includes? nt (lrmoo/iri :lrmoo/R4_embodies)))
      (is (str/includes? nt manif-iri))))
  (testing "and down-projects to additive CIDOC-CRM (the museum view of the BnF entity graph)"
    (let [nt (crm/->ntriples (import1))]
      (is (str/includes? nt (str crm/crm-base "E89_Propositional_Object")))   ; F1 super-type
      (is (str/includes? nt (str crm/crm-base "E73_Information_Object"))))))   ; F2/F3 super-type

(deftest crm-to-lrm-round-trips-the-bnf-entity-graph
  (testing "NG → LRMoo → CRM → LRMoo: the additive CRM recovers the OEMI tree losslessly"
    (let [recovered (crm-import/recover (crm/->ntriples (import1)))]
      (is (= {work-iri  :lrmoo/F1_Work
              expr-iri  :lrmoo/F2_Expression
              manif-iri :lrmoo/F3_Manifestation}
             (:typed recovered)))
      (is (empty? (:ambiguous recovered)))
      (is (= #{[manif-iri :lrmoo/R4_embodies       expr-iri]
               [work-iri  :lrmoo/R3_is_realised_in expr-iri]}
             (:relations recovered))))))

(deftest the-museum-view-carries-the-identified-creator
  (testing "NG → Linked Art: the Manifestation carries the Expression, created_by the identified Baudelaire"
    (let [ld (linked-art/->jsonld (import1))]
      (is (str/includes? ld "\"type\":\"HumanMadeObject\""))
      (is (str/includes? ld "Les fleurs du mal"))
      (is (str/includes? ld "\"created_by\""))
      (is (str/includes? ld "Baudelaire, Charles"))
      (is (str/includes? ld baudelaire-isni)))))            ; the ISNI -> an *identified* creator

(deftest intermarc-ng-is-a-first-class-convert-source
  (testing "convert lists :intermarc-ng as a source format"
    (is (contains? (convert/source-formats) :intermarc-ng)))
  (testing "convert :intermarc-ng -> :ntriples reaches the WEMI pivot"
    (let [{:keys [output records loss]} (convert/convert {:from :intermarc-ng :to :ntriples :source fixture})]
      (is (pos? records))
      (is (str/includes? output "lrmoo/F3_Manifestation"))
      (is (str/includes? output "lrmoo/R4_embodies"))
      (is (pos? (:total loss)))))                            ; the :canon floor predicates are export loss in RDF
  (testing "convert :intermarc-ng -> :linked-art / :crm-only also serialise the entity graph"
    (is (str/includes? (:output (convert/convert {:from :intermarc-ng :to :linked-art :source fixture}))
                       "HumanMadeObject"))
    (is (str/includes? (:output (convert/convert {:from :intermarc-ng :to :crm-only :source fixture}))
                       "E73_Information_Object"))))

(def ^:private concept-iri "http://data.bnf.fr/ark:/12148/cb11933776c")

(deftest subjects-and-agents-reach-the-crm-graph
  (testing "the Concept vedette-matière record becomes a :crm/E55_Type subject entity"
    (let [rec (import1)]
      (is (= :crm/E55_Type (some #(when (= concept-iri (:iri %)) (:kind %)) (:entities rec))))
      (testing "the Work P129_is_about the Concept (92x $3 → the subject entity)"
        (let [work-id (some #(when (= work-iri (:iri %)) (:id %)) (:entities rec))]
          (is (some #(and (= work-id (:subject %)) (= :crm/P129_is_about (:predicate %)))
                    (:assertions rec)))))))
  (testing "NG → CRM RDF now carries the whole entity graph — agent + subject, not just WEMI"
    (let [nt (crm/->ntriples (import1))]
      (is (str/includes? nt "E21_Person"))            ; the agent is now typed in RDF/CRM
      (is (str/includes? nt baudelaire-isni))
      (is (str/includes? nt "E55_Type"))              ; the subject concept
      (is (str/includes? nt "P129_is_about"))         ; Work → subject
      (is (str/includes? nt "Poésie française")))))   ; the concept label (P190)

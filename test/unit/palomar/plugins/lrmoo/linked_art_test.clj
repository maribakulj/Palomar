(ns palomar.plugins.lrmoo.linked-art-test
  "Unit tests for the Linked Art JSON-LD export (museum spoke). The WEMI→Linked Art
   mapping is verified against the official Linked Art model examples
   (docs/eval/linked-art.md): F3→HumanMadeObject carries F2→LinguisticObject
   part_of F1→PropositionalObject, with AAT-classified Names/Identifiers."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.model :as model]
            [palomar.plugins :as plug]
            [palomar.plugins.dc :as dc]
            [palomar.plugins.lrmoo.linked-art :as la]
            [palomar.plugins.lrmoo.project :as project]
            [palomar.plugins.mapping :as mapping]
            [palomar.runtime :as runtime]))

(def rec
  (model/record
   {:id :record/r1 :kind :book
    :entities [(model/entity {:id :ent/work :kind :lrmoo/F1_Work})
               (model/entity {:id :ent/expr :kind :lrmoo/F2_Expression})
               (model/entity {:id :ent/manif :kind :lrmoo/F3_Manifestation
                              :iri "http://data.bnf.fr/ark:/12148/cbX"})]
    :assertions [(model/assertion {:subject :ent/work :predicate :lrmoo/R3_is_realised_in
                                   :value (model/reference :ent/expr)})
                 (model/assertion {:subject :ent/manif :predicate :lrmoo/R4_embodies
                                   :value (model/reference :ent/expr)})
                 (model/assertion {:subject :ent/manif :predicate :lrmoo/R33_has_string :value "Madame Bovary"})
                 (model/assertion {:subject :ent/expr :predicate :lrmoo/R33_has_string :value "Madame Bovary"})
                 (model/assertion {:subject :ent/work :predicate :lrmoo/R33_has_string :value "Madame Bovary"})
                 (model/assertion {:subject :record/r1 :predicate :canon/agent :value "Flaubert"})
                 (model/assertion {:subject :record/r1 :predicate :canon/note :value "a novel"})
                 (model/assertion {:subject :record/r1 :predicate :canon/digital-object :value "http://img/1.jpg"})
                 (model/assertion {:subject :record/r1 :predicate :canon/date :value "1857"})]}))

(deftest builds-the-wemi-chain-as-linked-art
  (let [doc (json/read-str (la/->jsonld rec))]
    (testing "root is the Manifestation as a HumanMadeObject identified by the ARK"
      (is (= "https://linked.art/ns/v1/linked-art.json" (get doc "@context")))
      (is (= "http://data.bnf.fr/ark:/12148/cbX" (get doc "id")))
      (is (= "HumanMadeObject" (get doc "type")))
      (is (= "Madame Bovary" (get doc "_label"))))
    (testing "identified_by: a Primary Name and a System-Assigned Number (the ARK)"
      (let [ib    (get doc "identified_by")
            named (first (filter #(= "Name" (get % "type")) ib))
            ident (first (filter #(= "Identifier" (get % "type")) ib))]
        (is (= "Madame Bovary" (get named "content")))
        (is (= "http://vocab.getty.edu/aat/300404670" (get-in named ["classified_as" 0 "id"])))
        (is (= "http://data.bnf.fr/ark:/12148/cbX" (get ident "content")))
        (is (= "http://vocab.getty.edu/aat/300435704" (get-in ident ["classified_as" 0 "id"])))))
    (testing "carries the Expression (LinguisticObject), created_by the author, part_of the Work"
      (let [expr (first (get doc "carries"))]
        (is (= "LinguisticObject" (get expr "type")))
        (is (= "Creation" (get-in expr ["created_by" "type"])))
        (is (= "Flaubert" (get-in expr ["created_by" "carried_out_by" 0 "_label"])))
        (is (= "Person" (get-in expr ["created_by" "carried_out_by" 0 "type"])))
        (is (= "PropositionalObject" (get-in expr ["part_of" 0 "type"])))))
    (testing "the note is a referred_to_by LinguisticObject; the image a representation DigitalObject"
      (is (= "a novel" (get-in doc ["referred_to_by" 0 "content"])))
      (is (= "VisualItem" (get-in doc ["representation" 0 "type"])))
      (is (= "http://img/1.jpg"
             (get-in doc ["representation" 0 "digitally_shown_by" 0 "access_point" 0 "id"])))
      (is (= "http://vocab.getty.edu/aat/300215302"
             (get-in doc ["representation" 0 "digitally_shown_by" 0 "classified_as" 0 "id"]))))))

(deftest reports-unexpressed-predicates-as-export-loss
  (testing "fields the profile does not express (date, …) are :export :dropped"
    (let [ls     (la/export-losses rec)
          fields (set (map #(get-in % [:detail :loss/source-field]) ls))]
      (is (contains? fields :canon/date))
      (is (not (contains? fields :canon/agent)))     ; agent IS expressed
      (is (not (contains? fields :lrmoo/R33_has_string)))
      (is (every? #(= :loss/dropped (:code %)) ls))
      (is (every? #(= :export (get-in % [:detail :loss/edge])) ls)))))

(deftest empty-record-and-exporter-contract
  (testing "a record with no Manifestation yields the empty string"
    (is (= "" (la/->jsonld (model/record {:id :record/x :kind :book})))))
  (testing "the ADR 0007 exporter renders and reports loss"
    (let [{:keys [output diagnostics]} (la/exporter {} [rec])]
      (is (str/includes? output "HumanMadeObject"))
      (is (seq diagnostics)))))

(deftest a-creatorless-floor-record-omits-created-by-and-work
  (testing "IIIF-style: title but no creator -> LinguisticObject without created_by, no PropositionalObject"
    (let [reg   (plug/register plug/empty-registry dc/plugin)
          comp  (mapping/compile-mappings (plug/all-mappings reg) (plug/effective-transforms reg))
          ;; a DC record with a title but no creator
          rec*  (project/project
                 (:record (runtime/run-phase
                           (first (:records (dc/importer {:record-id :dc/t}
                                                         "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:title>Anonymous Chronicle</dc:title></metadata>")))
                           comp :normalize)))
          doc   (json/read-str (la/->jsonld rec*))
          expr  (first (get doc "carries"))]
      (is (= "Anonymous Chronicle" (get doc "_label")))
      (is (= "LinguisticObject" (get expr "type")))
      (is (nil? (get expr "created_by")))           ; no creator
      (is (nil? (get expr "part_of"))))))           ; no Work

(deftest an-identified-agent-entity-puts-its-iri-on-the-person
  (testing "a :crm/E21_Person entity with an ISNI iri -> created_by Person carries that id (D7)"
    (let [r (model/record
             {:id :record/r1 :kind :book
              :entities [(model/entity {:id :ent/m :kind :lrmoo/F3_Manifestation})
                         (model/entity {:id :ent/e :kind :lrmoo/F2_Expression})
                         (model/entity {:id :ent/a :kind :crm/E21_Person
                                        :iri "https://isni.org/isni/0000000122762442"})]
              :assertions [(model/assertion {:subject :ent/m :predicate :lrmoo/R4_embodies
                                             :value (model/reference :ent/e)})
                           (model/assertion {:subject :ent/m :predicate :lrmoo/R33_has_string
                                             :value "Madame Bovary"})
                           (model/assertion {:subject :record/r1 :predicate :canon/agent
                                             :value "Flaubert, Gustave"})]})
          person (-> (la/->jsonld r) json/read-str
                     (get-in ["carries" 0 "created_by" "carried_out_by" 0]))]
      (is (= "Person" (get person "type")))
      (is (= "Flaubert, Gustave" (get person "_label")))
      (is (= "https://isni.org/isni/0000000122762442" (get person "id"))))))

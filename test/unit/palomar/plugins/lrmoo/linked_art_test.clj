(ns palomar.plugins.lrmoo.linked-art-test
  "Unit tests for the Linked Art JSON-LD export (museum spoke). The WEMI→Linked Art
   mapping is verified against the official Linked Art model examples
   (docs/eval/linked-art.md, model/document): F3 (+ its F2) → LinguisticObject
   part_of F1 → PropositionalObject; only an F5 Item is a HumanMadeObject, which
   carries the text. AAT-classified Names/Identifiers."
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
    (testing "root is the Manifestation's text, a LinguisticObject (an edition is not a physical object, audit S01)"
      (is (= "https://linked.art/ns/v1/linked-art.json" (get doc "@context")))
      (is (= "http://data.bnf.fr/ark:/12148/cbX" (get doc "id")))
      (is (= "LinguisticObject" (get doc "type")))
      (is (nil? (get doc "carries")))
      (is (= "Madame Bovary" (get doc "_label"))))
    (testing "identified_by: a Primary Name and a System-Assigned Number (the ARK)"
      (let [ib    (get doc "identified_by")
            named (first (filter #(= "Name" (get % "type")) ib))
            ident (first (filter #(= "Identifier" (get % "type")) ib))]
        (is (= "Madame Bovary" (get named "content")))
        (is (= "http://vocab.getty.edu/aat/300404670" (get-in named ["classified_as" 0 "id"])))
        (is (= "http://data.bnf.fr/ark:/12148/cbX" (get ident "content")))
        (is (= "http://vocab.getty.edu/aat/300435704" (get-in ident ["classified_as" 0 "id"])))))
    (testing "the text is created_by the author and part_of the Work"
      (is (= "Creation" (get-in doc ["created_by" "type"])))
      (is (= "Flaubert" (get-in doc ["created_by" "carried_out_by" 0 "_label"])))
      (is (= "Person" (get-in doc ["created_by" "carried_out_by" 0 "type"])))
      (is (= "PropositionalObject" (get-in doc ["part_of" 0 "type"]))))
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
      (is (every? #(= :export (get-in % [:detail :loss/edge])) ls))
      (testing "the Expression folded into the Manifestation's text is :under-specified"
        (is (= [:lrmoo/F2_Expression]
               (map #(get-in % [:detail :loss/source-field])
                    (filter #(= :loss/under-specified (:code %)) ls))))))))

(deftest empty-record-and-exporter-contract
  (testing "a record with no Manifestation yields the empty string"
    (is (= "" (la/->jsonld (model/record {:id :record/x :kind :book})))))
  (testing "the ADR 0007 exporter renders and reports loss"
    (let [{:keys [output diagnostics]} (la/exporter {} [rec])]
      (is (str/includes? output "LinguisticObject"))
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
          doc   (json/read-str (la/->jsonld rec*))]
      (is (= "Anonymous Chronicle" (get doc "_label")))
      (is (= "LinguisticObject" (get doc "type")))
      (is (nil? (get doc "created_by")))            ; no creator
      (is (nil? (get doc "part_of"))))))            ; no Work

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
                     (get-in ["created_by" "carried_out_by" 0]))]
      (is (= "Person" (get person "type")))
      (is (= "Flaubert, Gustave" (get person "_label")))
      (is (= "https://isni.org/isni/0000000122762442" (get person "id"))))))

(defn- dc->wemi
  "Import a DC `xml` document, normalise it and project it to the WEMI view."
  [xml]
  (let [reg  (plug/register plug/empty-registry dc/plugin)
        comp (mapping/compile-mappings (plug/all-mappings reg) (plug/effective-transforms reg))]
    (project/project
     (:record (runtime/run-phase (first (:records (dc/importer {:record-id :dc/p} xml)))
                                 comp :normalize)))))

(deftest a-publisher-is-not-the-creator
  (testing "dc:creator + dc:publisher: the creator is created_by, the publisher carries out the Publishing"
    (let [rec*    (dc->wemi (str "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">"
                                 "<dc:title>Les Misérables</dc:title>"
                                 "<dc:publisher>Gallimard</dc:publisher>"
                                 "<dc:creator>Victor Hugo</dc:creator></metadata>"))
          doc     (json/read-str (la/->jsonld rec*))
          names   (map #(get % "_label") (get-in doc ["created_by" "carried_out_by"]))
          pub     (first (get doc "used_for"))
          dropped (filter #(= :canon/agent (get-in % [:detail :loss/source-field]))
                          (la/export-losses rec*))]
      (is (= ["Victor Hugo"] names))
      (is (= "http://vocab.getty.edu/aat/300054686" (get-in pub ["classified_as" 0 "id"])))
      (is (= [{"type" "Group" "_label" "Gallimard"}] (get pub "carried_out_by")))
      (is (empty? dropped))))
  (testing "a contributor is neither creator nor publisher: export loss"
    (let [rec*    (dc->wemi (str "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">"
                                 "<dc:title>Les Misérables</dc:title>"
                                 "<dc:creator>Victor Hugo</dc:creator>"
                                 "<dc:contributor>Gustave Brion</dc:contributor></metadata>"))
          dropped (filter #(= :canon/agent (get-in % [:detail :loss/source-field]))
                          (la/export-losses rec*))]
      (is (= 1 (count dropped)))
      (is (str/includes? (:message (first dropped)) "Gustave Brion"))))
  (testing "a publisher alone: no created_by, no Work (audit test 1)"
    (let [rec* (dc->wemi (str "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">"
                              "<dc:title>Les Misérables</dc:title>"
                              "<dc:publisher>Gallimard</dc:publisher></metadata>"))
          doc  (json/read-str (la/->jsonld rec*))]
      (is (nil? (get doc "created_by")))
      (is (nil? (get doc "part_of")))
      (is (= "Gallimard" (get-in doc ["used_for" 0 "carried_out_by" 0 "_label"]))))))

(def ^:private two-editions-and-a-copy
  "A graph record (INTERMARC-NG style): one Work, two Expressions, a Manifestation
   of each, and an Item of the second Manifestation."
  (model/record
   {:id :record/g :kind :lrmoo/graph
    :entities (mapv model/entity [{:id :ent/w :kind :lrmoo/F1_Work}
                                  {:id :ent/e1 :kind :lrmoo/F2_Expression}
                                  {:id :ent/e2 :kind :lrmoo/F2_Expression}
                                  {:id :ent/m1 :kind :lrmoo/F3_Manifestation :iri "http://ex/m1"}
                                  {:id :ent/m2 :kind :lrmoo/F3_Manifestation :iri "http://ex/m2"}
                                  {:id :ent/i2 :kind :lrmoo/F5_Item :iri "http://ex/i2"}
                                  {:id :ent/orphan :kind :lrmoo/F2_Expression}])
    :assertions (mapv model/assertion
                      [{:subject :ent/w :predicate :lrmoo/R3_is_realised_in :value (model/reference :ent/e1)}
                       {:subject :ent/w :predicate :lrmoo/R3_is_realised_in :value (model/reference :ent/e2)}
                       {:subject :ent/m1 :predicate :lrmoo/R4_embodies :value (model/reference :ent/e1)}
                       {:subject :ent/m2 :predicate :lrmoo/R4_embodies :value (model/reference :ent/e2)}
                       {:subject :ent/i2 :predicate :lrmoo/R7_exemplifies :value (model/reference :ent/m2)}
                       {:subject :ent/m1 :predicate :lrmoo/R33_has_string :value "Les Misérables"}
                       {:subject :ent/m2 :predicate :lrmoo/R33_has_string :value "Les Misérables (trad.)"}
                       {:subject :record/g :predicate :canon/note :value "a note"}])}))

(deftest every-manifestation-and-item-gets-its-own-document
  (testing "two Manifestations -> two LinguisticObjects, each part_of the Work; the Item is a HumanMadeObject (audit S10)"
    (let [docs (mapv json/read-str (str/split-lines (la/->jsonld two-editions-and-a-copy)))]
      (is (= ["LinguisticObject" "LinguisticObject" "HumanMadeObject"] (map #(get % "type") docs)))
      (is (= ["http://ex/m1" "http://ex/m2" "http://ex/i2"] (map #(get % "id") docs)))
      (is (= ["Les Misérables" "Les Misérables (trad.)"] (map #(get % "_label") (take 2 docs))))
      (is (every? #(= "PropositionalObject" (get-in % ["part_of" 0 "type"])) (take 2 docs)))
      (testing "the copy carries the text of the Manifestation it exemplifies"
        (is (= "http://ex/m2" (get-in (nth docs 2) ["carries" 0 "id"]))))
      (testing "a record-level note cannot be attributed to one of two Manifestations"
        (is (not-any? #(contains? % "referred_to_by") docs)))))
  (testing "the losses name the unreached Expression and the unattributed note"
    (let [ls (la/export-losses two-editions-and-a-copy)]
      (is (some #(and (= :loss/dropped (:code %)) (= :ent/orphan (:subject %))) ls))
      (is (some #(and (= :loss/under-specified (:code %))
                      (= :canon/note (get-in % [:detail :loss/source-field]))) ls)))))

(ns palomar.inferences-test
  "Every relation a projection states as a fact is declared, transcribed or
   inferred, by its rule (post-audit 7, I3; ADR 0055)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.convert :as convert]
            [palomar.fidelity :as fidelity]
            [palomar.inferences :as inferences]
            [palomar.model :as model]
            [palomar.plugins.lido-test]))

(def ^:private corpora
  [[:intermarc    "test/fixtures/documentary/intermarc/sru/intermarcXchange/bib-flaubert-madame-bovary-start1-max30.xml"]
   [:intermarc-ng "test/fixtures/documentary/intermarc-ng/examples/fleurs-du-mal-oemi.xml"]
   [:unimarc      "test/fixtures/documentary/unimarc/sru/bnf-sru-verne-unimarc.xml"]
   [:dc           "test/fixtures/documentary/dublin-core/w3c_dc_example1.xml"]
   [:marc21       "test/fixtures/documentary/marc21/marcxml/loc_collection.xml"]
   [:mods         "test/fixtures/documentary/mods/loc_mods_book.xml"]
   [:iiif         "test/fixtures/documentary/iiif/manifest_book_simple.json"]
   [:ead          "test/fixtures/documentary/ead/ead3/ead3_multi_level_optimum.xml"]
   [:eac-cpf      "test/fixtures/documentary/eac-cpf/example_eac_cpf.xml"]
   [:lido         "test/fixtures/documentary/lido/staedel-lido-wrap.xml"]
   [:oai          "test/fixtures/documentary/lido/museum-digital-hessen-lido.xml"]
   [:oai          "test/fixtures/documentary/lido/museum-digital-westfalen-lido.xml"]
   [:oai          "test/fixtures/documentary/lido/staedel-oai-lido-graphics.xml"]])

(defn- records [from f]
  (:records (convert/to-wemi from (case from (:dc :iiif :eac-cpf) {:record-id :rec/x} :oai {:metadata-prefix "lido"} {}) (slurp f))))

(deftest every-stated-relation-is-declared
  (testing "no corpus states a relation its rule does not declare"
    (doseq [[from f] corpora :when (.exists (io/file f))]
      (is (= [] (inferences/undeclared (records from f))) (str from " " f))))
  (testing "the audit tells what was inferred and what no rule declares"
    (let [{:keys [inferences]} (fidelity/audit {:from :lido :to :ntriples :source (slurp "test/fixtures/documentary/lido/staedel-lido-wrap.xml")})]
      (is (= [] (:undeclared inferences)))
      (is (pos? (reduce + 0 (for [[[r p] n] (:inferred inferences) :when (and (= :crm/P62_depicts p) (= "rule.lido.subject" (namespace r)))] n)))
          "a picture's subjects are read as what it depicts, by the rules")
      (is (zero? (get (:inferred inferences) [:rule.lido/project :crm/P62_depicts] 0)) "never by code, a person neither (N7)"))))

(deftest every-lido-relation-is-declared
  (testing "a record of every kind of event and role LIDO's projection knows states only what its rule declares"
    (let [rec @#'palomar.plugins.lido-test/events-record
          xml (rec "sculpture" ["lido00224" "Rodin" "designer" "1890"] ["lido00007" "Rudier" "founder" "1902"]
                   ["lido00007" "Musée" "commissioner" "1902"] ["lido00001" "Dealer" "seller" "1950"] ["lido00001" "Museum" "Käufer" "1950"]
                   ["Restaurierung" "Atelier" "restorer" "1990"] ["lido00226" "Musée" nil "1898"] ["Ausstellung" "Kurator" "curator" "2000"]
                   ["Gefunden" "Finder" nil "1800"])]
      (is (= [] (inferences/undeclared (:records (convert/to-wemi :lido {} xml)))))
      (testing "with a material employed in a modification and a former keeper (I3 review)"
        (let [xml (-> xml
                      (str/replace #"(<lido:term>Restaurierung</lido:term></lido:eventType>)"
                                   "$1<lido:eventMaterialsTech><lido:materialsTech><lido:termMaterialsTech><lido:conceptID>http://vocab.getty.edu/aat/300011914</lido:conceptID><lido:term>Firnis</lido:term></lido:termMaterialsTech></lido:materialsTech></lido:eventMaterialsTech>")
                      (str/replace "</lido:titleWrap>"
                                   "</lido:titleWrap><lido:repositoryWrap><lido:repositorySet lido:type=\"former\"><lido:repositoryName><lido:legalBodyName><lido:appellationValue>Old</lido:appellationValue></lido:legalBodyName></lido:repositoryName></lido:repositorySet></lido:repositoryWrap>"))
              ps  (set (map :predicate (mapcat :assertions (:records (convert/to-wemi :lido {} xml)))))]
          (is (contains? ps :crm/P126_employed))
          (is (contains? ps :crm/P49_has_former_or_current_keeper))
          (is (= [] (inferences/undeclared (:records (convert/to-wemi :lido {} xml))))))))))

(deftest an-undeclared-relation-is-found
  (testing "a rule that starts to state a relation it does not declare is caught"
    (let [a (model/assertion {:subject :x/a :predicate :crm/P46_is_composed_of :value (model/reference :x/b)
                              :provenance (model/provenance {:pass :infer :rule :rule.lido/project})})]
      (is (= [{:subject :x/a :predicate :crm/P46_is_composed_of :rule :rule.lido/project}]
             (inferences/undeclared [{:assertions [a]}])))
      (is (= [] (inferences/undeclared [{:assertions [(assoc a :status :proposed)]}])) "a proposal states nothing")
      (let [p2 (assoc a :predicate :crm/P2_has_type)]
        (is (= [] (inferences/undeclared [{:assertions [(assoc-in p2 [:provenance :also] [{:pass :infer :rule :rule.lido/project}])]}]))
            "a relation declared by its rule")
        (is (seq (inferences/undeclared [{:assertions [(assoc-in p2 [:provenance :also] [{:pass :infer :rule :rule.unknown/x}])]}]))
            "every rule that derives it must declare it")))))

(deftest every-declared-inference-has-its-reason
  (doseq [[rule {:keys [infers]}] inferences/declared [p why] infers]
    (is (and (string? why) (< 20 (count why))) (str rule " " p))))

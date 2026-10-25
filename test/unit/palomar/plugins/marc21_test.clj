(ns palomar.plugins.marc21-test
  "Unit tests for the MARC21 (MARCXML) spoke (WP-4) on the real LoC collection
   fixture (two bibliographic records): native `:marc21/*` ingest (lossless),
   the bibliographic subset onto the canonical floor, the WEMI projection, and
   the projection-edge loss for retained-but-unprojected fields."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.diagnostics :as dx]
            [palomar.plugins :as plug]
            [palomar.plugins.lrmoo.export :as export]
            [palomar.plugins.lrmoo.project :as project]
            [palomar.plugins.lrmoo.view :as view]
            [palomar.plugins.mapping :as mapping]
            [palomar.plugins.marc21 :as marc21]
            [palomar.runtime :as runtime]))

(def ^:private fixture
  (slurp "test/fixtures/documentary/marc21/marcxml/loc_collection.xml"))

(def ^:private records (marc21/ingest fixture {}))

(defn- by-id [id] (first (filter #(= id (:id %)) records)))

(defn- normalize [record]
  (let [reg      (plug/register plug/empty-registry marc21/plugin)
        compiled (mapping/compile-mappings (plug/all-mappings reg)
                                           (plug/effective-transforms reg))]
    (:record (runtime/run-phase record compiled :normalize))))

(defn- literals [record pred]
  (->> (:assertions record)
       (filter #(and (= pred (:predicate %)) (string? (:value %))))
       (map :value)
       set))

(deftest parses-the-collection-into-records-with-control-number-ids
  (testing "two <record>s, ids derived from the 001 control number (digit-safe)"
    (is (= 2 (count records)))
    (is (= #{:marc/r5637241 :marc/r12149120} (set (map :id records))))
    (is (every? #(= :marc21/bibliographic (:kind %)) records))))

(deftest ingest-is-lossless-every-field-retained-as-native
  (testing "even unmapped fields survive ingest as :marc21/* (loss is deferred)"
    (let [r1 (by-id :marc/r5637241)]
      (is (= #{"The Great Ray Charles"} (literals r1 :marc21/f245_a)))   ; mapped later
      (is (contains? (literals r1 :marc21/f650_a) "Jazz"))               ; unmapped, retained (2 subjects)
      (is (contains? (literals r1 :marc21/f300_a) "1 sound disc :")))))  ; unmapped, retained

(deftest maps-the-bibliographic-subset-onto-the-canonical-floor
  (testing "245/700/260/010 of the Ray Charles record reach canonical (trimmed)"
    (let [canon (normalize (by-id :marc/r5637241))]
      (is (= #{"The Great Ray Charles"} (literals canon :canon/title)))
      (is (= #{"Charles, Ray,"} (literals canon :canon/agent)))         ; 700 $a
      (is (= #{"[1957?]"} (literals canon :canon/date)))                ; 260 $c
      (is (= #{"91758335"} (literals canon :canon/identifier)))         ; 010 $a, padding trimmed
      (is (= 3 (count (literals canon :canon/note))))                   ; 500 + 505 + 511
      (is (contains? (literals canon :canon/note) "Brief record.")))))

(deftest the-856-url-becomes-a-canonical-digital-object
  (testing "the White House record's 856 $u maps to :canon/digital-object"
    (let [canon (normalize (by-id :marc/r12149120))]
      (is (= #{"The White House"} (literals canon :canon/title)))
      (is (= #{"White House Web Team."} (literals canon :canon/agent)))  ; 710 $a
      (is (contains? (literals canon :canon/digital-object) "http://www.whitehouse.gov")))))

(deftest projects-to-the-wemi-pivot-and-rdf
  (testing "the canonical floor projects to Manifestation + Expression and serialises"
    (let [wemi (project/project (normalize (by-id :marc/r5637241)))
          nt   (export/->ntriples wemi)]
      (is (= 1 (count (view/manifestations wemi))))
      (is (= 1 (count (view/expressions wemi))))
      (is (str/includes? nt "The Great Ray Charles"))
      (testing "no Work: the record has no 1XX main entry, and its 700 performer is not a creator"
        (is (empty? (view/works wemi)))))))

(def ^:private main-and-added-entry
  "One MARCXML record with a 100 main entry and a 700 added entry (audit test 3)."
  (str "<collection xmlns=\"http://www.loc.gov/MARC21/slim\"><record>"
       "<controlfield tag=\"001\">42</controlfield>"
       "<datafield tag=\"700\" ind1=\"1\" ind2=\" \"><subfield code=\"a\">Doré, Gustave</subfield></datafield>"
       "<datafield tag=\"100\" ind1=\"1\" ind2=\" \"><subfield code=\"a\">Hugo, Victor</subfield></datafield>"
       "<datafield tag=\"245\" ind1=\"1\" ind2=\"0\"><subfield code=\"a\">Notre-Dame de Paris</subfield></datafield>"
       "</record></collection>"))

(deftest the-main-entry-keys-the-work-not-the-added-entry
  (testing "100 is a creator, 700 a contributor: only the 100 keys the Work, whatever the field order"
    (let [canon     (normalize (first (marc21/ingest main-and-added-entry {})))
          wemi      (project/project canon)
          hugo-only (project/project (normalize (first (marc21/ingest
                                                        (str/replace main-and-added-entry
                                                                     #"<datafield tag=\"700\".*?</datafield>" "")
                                                        {}))))]
      (is (= 1 (count (view/works wemi))))
      (is (= (:id (first (view/works hugo-only))) (:id (first (view/works wemi)))))
      (testing "both agents stay on the floor, each with its role"
        (is (= #{"Hugo, Victor" "Doré, Gustave"} (literals canon :canon/agent)))
        (is (= #{:creator :contributor} (set (keep #(when (= :canon/role (:predicate %)) (:value %))
                                                   (:assertions canon)))))))))

(deftest canonical-fields-outside-wemi-are-projection-loss
  (testing "identifier/date/note are on the floor but not in WEMI -> :import :dropped"
    (let [wemi   (project/project (normalize (by-id :marc/r5637241)))
          fields (set (map #(get-in % [:detail :loss/source-field])
                           (dx/losses (:diagnostics wemi))))]
      (is (contains? fields :canon/identifier))
      (is (contains? fields :canon/date))
      (is (contains? fields :canon/note))
      (is (not (contains? fields :canon/title))))))

(deftest the-plugin-conforms-and-its-mapping-compiles
  (testing "MARC21 is a well-formed ADR 0007 plugin whose mapping compiles cleanly"
    (is (= :palomar/marc21 (:id marc21/plugin)))
    (is (fn? (:importer marc21/plugin)))
    (is (= 18 (count (:mapping marc21/plugin))))           ; +240 uniform-title (bridging)
    (let [reg (plug/register plug/empty-registry marc21/plugin)]
      (is (some? (mapping/compile-mappings (plug/all-mappings reg)
                                           (plug/effective-transforms reg)))))))

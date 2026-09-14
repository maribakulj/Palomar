(ns palomar.multi-spoke-convergence-integration-test
  "WP-4 capstone — four real spokes converge on one pivot.

   INTERMARC (BnF MARC, real Madame Bovary), MARC21 (LoC MARCXML, real Ray
   Charles), Dublin Core (W3C XML) and MODS (LoC, real book) are registered in
   **one** registry, each reaching the **same** LRMoo pivot (WEMI) and the **same**
   loss model (ADR 0015) — one unified loss report spanning all four native
   vocabularies.

   The two-rung ladder (ADR 0013) is visible here: INTERMARC takes the *enriched*
   projection (`derive-wemi`, via the 145 $3 authority link), so its Work identity is
   authority-based; DC, MARC21 and MODS take the *floor* projection (string key),
   so floor records of the same creator+title collapse to the **same** Work id —
   content-addressed identity is format-independent on the floor. Converging
   INTERMARC's authority identity with the floor's string key would need name-form
   reconciliation, the entity-resolution recall ceiling measured in
   `docs/eval/entity-resolution.md`; that is deliberately not claimed here."
  (:require [clojure.test :refer [deftest is testing]]
            [palomar.diagnostics :as dx]
            [palomar.loss-report :as lr]
            [palomar.plugins :as plug]
            [palomar.plugins.dc :as dc]
            [palomar.plugins.intermarc :as intermarc]
            [palomar.plugins.intermarc.wemi :as wemi]
            [palomar.plugins.lrmoo.export :as export]
            [palomar.plugins.lrmoo.project :as project]
            [palomar.plugins.lrmoo.view :as view]
            [palomar.plugins.mapping :as mapping]
            [palomar.plugins.marc21 :as marc21]
            [palomar.plugins.mods :as mods]
            [palomar.runtime :as runtime]))

;; --- real fixtures ----------------------------------------------------------

(def ^:private intermarc-fixture
  "test/fixtures/documentary/intermarc/sru/intermarcXchange/bib-flaubert-madame-bovary-start1-max30.xml")
(def ^:private marc21-fixture
  (slurp "test/fixtures/documentary/marc21/marcxml/loc_collection.xml"))
(def ^:private dc-fixture
  (slurp "test/fixtures/documentary/dublin-core/w3c_dc_example1.xml"))
(def ^:private mods-fixture
  (slurp "test/fixtures/documentary/mods/loc_mods_book.xml"))

(defn- intermarc-showcase []
  (->> (:records (intermarc/importer {} {:source/kind :file :source/value intermarc-fixture}))
       (filter #(= "ark:/12148/cb304403926" (:source %)))
       first
       wemi/derive-wemi))

(defn- floor-project
  "The floor route a host takes for a mapping-bearing spoke: register, compile its
   mapping, ingest one record, normalise to the canonical floor, project to WEMI.
   Returns `{:wemi :ingest}`."
  [plugin opts src]
  (let [reg      (plug/register plug/empty-registry plugin)
        compiled (mapping/compile-mappings (plug/all-mappings reg)
                                           (plug/effective-transforms reg))
        {:keys [records diagnostics]} ((:importer plugin) opts src)
        canon    (:record (runtime/run-phase (first records) compiled :normalize))]
    {:wemi (project/project canon) :ingest diagnostics}))

;; --- Part 1: four formats, one registry, one pivot, one loss report ---------

(deftest four-spokes-coexist-in-one-registry
  (testing "INTERMARC + MARC21 + DC + MODS register together; their mappings compile as one set"
    (let [reg (reduce plug/register plug/empty-registry
                      [intermarc/plugin marc21/plugin dc/plugin mods/plugin])]
      (is (= #{:palomar/intermarc :palomar/marc21 :palomar/dublin-core :palomar/mods}
             (set (plug/registered-ids reg))))
      (is (some? (mapping/compile-mappings (plug/all-mappings reg)
                                           (plug/effective-transforms reg)))))))

(deftest every-format-reaches-the-same-pivot
  (testing "all four real records yield the same LRMoo WEMI shape (F3 + F2 + F1)"
    (let [im   (intermarc-showcase)
          m21  (:wemi (floor-project marc21/plugin {} marc21-fixture))     ; Ray Charles (first)
          dc   (:wemi (floor-project dc/plugin {:record-id :dc/example1} dc-fixture))
          mds  (:wemi (floor-project mods/plugin {} mods-fixture))]        ; Sound and fury
      (doseq [[label w] [["intermarc" im] ["marc21" m21] ["dc" dc] ["mods" mds]]]
        (is (= 1 (count (view/manifestations w))) (str label " manifestation"))
        (is (= 1 (count (view/expressions w)))    (str label " expression"))
        (is (= 1 (count (view/works w)))          (str label " work"))))))

(deftest one-loss-report-spans-all-four-vocabularies
  (testing "a single ADR 0015 report aggregates loss from INTERMARC, MARC21, DC and MODS"
    (let [im   (intermarc-showcase)
          m21  (:wemi (floor-project marc21/plugin {} marc21-fixture))
          dcr  (floor-project dc/plugin {:record-id :dc/example1} dc-fixture)
          mdr  (floor-project mods/plugin {} mods-fixture)
          dc   (:wemi dcr)
          mds  (:wemi mdr)
          import-loss (concat (dx/collect im) (dx/collect m21) (dx/collect dc) (dx/collect mds)
                              (:ingest dcr) (:ingest mdr))              ; DC's + MODS's report-at-ingest
          export-loss (mapcat export/export-losses [im m21 dc mds])     ; LRMoo can't express natives
          report (lr/conversion-report (concat import-loss export-loss) {:records 4})
          nss    (set (map namespace (:source-fields report)))]
      (testing "one report, four native vocabularies present"
        (is (contains? nss "intermarc"))
        (is (contains? nss "marc21"))
        (is (contains? nss "dc"))         ; DC's unmodelled-element ingest loss
        (is (contains? nss "mods"))       ; MODS's unmodelled-element ingest loss
        (is (contains? nss "canon")))     ; floor fields dropped at projection
      (testing "both edges accounted, over the four records"
        (is (pos? (get-in report [:by-edge :import :total])))
        (is (pos? (get-in report [:by-edge :export :total])))
        (is (= 4 (:records report)))))))

;; --- Part 2: content-addressed identity is format-independent (floor) -------

(def ^:private dc-hugo
  (str "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">"
       "<dc:title>Les Misérables</dc:title>"
       "<dc:creator>Victor Hugo</dc:creator>"
       "</metadata>"))

(def ^:private marc21-hugo
  (str "<record xmlns=\"http://www.loc.gov/MARC21/slim\">"
       "<controlfield tag=\"001\">hugo-lm-1</controlfield>"
       "<datafield tag=\"100\" ind1=\"1\" ind2=\" \"><subfield code=\"a\">Victor Hugo</subfield></datafield>"
       "<datafield tag=\"245\" ind1=\"1\" ind2=\"0\"><subfield code=\"a\">Les Misérables</subfield></datafield>"
       "</record>"))

(def ^:private mods-hugo
  (str "<mods xmlns=\"http://www.loc.gov/mods/v3\">"
       "<titleInfo><title>Les Misérables</title></titleInfo>"
       "<name type=\"personal\"><namePart>Victor Hugo</namePart></name>"
       "<identifier>hugo-lm-mods</identifier>"
       "</mods>"))

(deftest floor-formats-content-converge-on-the-same-work
  (testing "DC, MARC21 and MODS records of the same creator+title mint the SAME Work + Expression"
    (let [works (fn [w] (:id (first (view/works w))))
          exprs (fn [w] (:id (first (view/expressions w))))
          manis (fn [w] (:id (first (view/manifestations w))))
          dc-w  (:wemi (floor-project dc/plugin {:record-id :dc/hugo} dc-hugo))
          m21-w (:wemi (floor-project marc21/plugin {} marc21-hugo))
          mds-w (:wemi (floor-project mods/plugin {} mods-hugo))]
      (is (some? (works dc-w)))
      (testing "one Work id across all three floor formats (content-addressed identity)"
        (is (= (works dc-w) (works m21-w) (works mds-w)))
        (is (= (exprs dc-w) (exprs m21-w) (exprs mds-w))))
      (testing "...yet three distinct Manifestations (distinct source records)"
        (is (= 3 (count (distinct [(manis dc-w) (manis m21-w) (manis mds-w)]))))))))

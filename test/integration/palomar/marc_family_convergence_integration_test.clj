(ns palomar.marc-family-convergence-integration-test
  "Capstone — the whole MARC family **and** the entity-relation rung converge on one
   hub. MARC21 (LoC), INTERMARC and UNIMARC (BnF) and INTERMARC-NG (NOEMI, an
   entity-relation graph) all reach the same LRMoo/WEMI pivot. The honest structure
   is the two-rung ladder (ADR 0013/0019):

   - the **flat** dialects (UNIMARC, MARC21, with DC/MODS) take the floor projection
     (string key), so records of the same creator+title — *whatever the dialect* —
     mint the **same** content-addressed Work id;
   - the **entity** rungs (INTERMARC via `derive-wemi`'s 145 link, INTERMARC-NG read
     graph→graph) reach the same WEMI *shape* but keep their own authority/ARK
     identity. They are **not** collapsed onto the floor's string key — doing so is
     name-form reconciliation, the ADR 0018 recall ceiling, deliberately not claimed.

   So: convergence on the hub *vocabulary* is universal; convergence on *identity* is
   the floor's content-addressing, with the entity rungs kept honestly distinct."
  (:require [clojure.test :refer [deftest is testing]]
            [palomar.plugins :as plug]
            [palomar.plugins.intermarc :as intermarc]
            [palomar.plugins.intermarc-ng :as intermarc-ng]
            [palomar.plugins.intermarc.wemi :as wemi]
            [palomar.plugins.lrmoo.project :as project]
            [palomar.plugins.lrmoo.view :as view]
            [palomar.plugins.mapping :as mapping]
            [palomar.plugins.marc21 :as marc21]
            [palomar.plugins.mods :as mods]
            [palomar.plugins.dc :as dc]
            [palomar.plugins.unimarc :as unimarc]
            [palomar.runtime :as runtime]))

(defn- floor-project
  "Floor route for a mapping-bearing spoke: ingest one record, normalise to the
   canonical floor, project to WEMI."
  [plugin opts src]
  (let [reg      (plug/register plug/empty-registry plugin)
        compiled (mapping/compile-mappings (plug/all-mappings reg)
                                           (plug/effective-transforms reg))
        {:keys [records]} ((:importer plugin) opts src)]
    (project/project (:record (runtime/run-phase (first records) compiled :normalize)))))

(defn- wemi-shape [w] [(count (view/works w)) (count (view/expressions w)) (count (view/manifestations w))])
(defn- agent-of [w] (some #(when (= :canon/agent (:predicate %)) (:value %)) (:assertions w)))

;; --- synthetic same-item records across dialects ----------------------------

(def ^:private unimarc-miserables
  (str "<mxc:record xmlns:mxc=\"info:lc/xmlns/marcxchange-v2\" format=\"UNIMARC\" id=\"ark:/12148/cbU1\">"
       "<mxc:datafield tag=\"200\" ind1=\"1\" ind2=\" \"><mxc:subfield code=\"a\">Les Misérables</mxc:subfield></mxc:datafield>"
       "<mxc:datafield tag=\"700\" ind1=\" \" ind2=\" \"><mxc:subfield code=\"a\">Victor Hugo</mxc:subfield></mxc:datafield>"
       "</mxc:record>"))

(def ^:private marc21-miserables
  (str "<record xmlns=\"http://www.loc.gov/MARC21/slim\">"
       "<controlfield tag=\"001\">m21-lm</controlfield>"
       "<datafield tag=\"100\" ind1=\"1\" ind2=\" \"><subfield code=\"a\">Victor Hugo</subfield></datafield>"
       "<datafield tag=\"245\" ind1=\"1\" ind2=\"0\"><subfield code=\"a\">Les Misérables</subfield></datafield>"
       "</record>"))

(def ^:private dc-miserables
  (str "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">"
       "<dc:title>Les Misérables</dc:title><dc:creator>Victor Hugo</dc:creator></metadata>"))

(def ^:private mods-miserables
  (str "<mods xmlns=\"http://www.loc.gov/mods/v3\"><titleInfo><title>Les Misérables</title></titleInfo>"
       "<name type=\"personal\"><namePart>Victor Hugo</namePart></name><identifier>mods-lm</identifier></mods>"))

;; A flat UNIMARC of *the same work as the NG fixture* (Les Fleurs du mal / Baudelaire),
;; so the floor rung and the entity rung can be compared on one item.
(def ^:private unimarc-fleurs
  (str "<mxc:record xmlns:mxc=\"info:lc/xmlns/marcxchange-v2\" format=\"UNIMARC\" id=\"ark:/12148/cbU2\">"
       "<mxc:datafield tag=\"200\" ind1=\"1\" ind2=\" \"><mxc:subfield code=\"a\">Les fleurs du mal</mxc:subfield></mxc:datafield>"
       "<mxc:datafield tag=\"700\" ind1=\" \" ind2=\" \"><mxc:subfield code=\"a\">Baudelaire, Charles</mxc:subfield></mxc:datafield>"
       "</mxc:record>"))

(def ^:private ng-fixture
  (slurp "test/fixtures/documentary/intermarc-ng/examples/fleurs-du-mal-oemi.xml"))
(def ^:private intermarc-fixture
  "test/fixtures/documentary/intermarc/sru/intermarcXchange/bib-flaubert-madame-bovary-start1-max30.xml")

;; ---------------------------------------------------------------------------

(deftest the-family-and-the-entity-rung-coexist-and-reach-the-pivot
  (testing "MARC21 + INTERMARC + UNIMARC + INTERMARC-NG register in one set; mappings compile"
    (let [reg (reduce plug/register plug/empty-registry
                      [marc21/plugin intermarc/plugin unimarc/plugin intermarc-ng/plugin])]
      (is (= #{:palomar/marc21 :palomar/intermarc :palomar/unimarc :palomar/intermarc-ng}
             (set (plug/registered-ids reg))))
      (is (some? (mapping/compile-mappings (plug/all-mappings reg) (plug/effective-transforms reg))))))
  (testing "every MARC-family route reaches a Manifestation in the LRMoo pivot"
    (let [uni (floor-project unimarc/plugin {} unimarc-miserables)
          m21 (floor-project marc21/plugin {} marc21-miserables)
          im  (wemi/derive-wemi (->> (intermarc/importer {} {:source/kind :file :source/value intermarc-fixture})
                                     :records (filter #(= "ark:/12148/cb304403926" (:source %))) first))
          ng  (first (intermarc-ng/ingest ng-fixture {}))]
      (doseq [[label w] [["unimarc" uni] ["marc21" m21] ["intermarc" im] ["intermarc-ng" ng]]]
        (is (= 1 (count (view/manifestations w))) (str label " reaches a Manifestation"))))))

(deftest the-flat-marc-family-content-converges-on-the-floor
  (testing "UNIMARC + MARC21 + DC + MODS of 'Victor Hugo / Les Misérables' mint the SAME Work id"
    (let [uni (floor-project unimarc/plugin {} unimarc-miserables)
          m21 (floor-project marc21/plugin {} marc21-miserables)
          dcr (floor-project dc/plugin {:record-id :dc/lm} dc-miserables)
          mds (floor-project mods/plugin {} mods-miserables)
          work-id #(:id (first (view/works %)))]
      (is (some? (work-id uni)))
      (is (= (work-id uni) (work-id m21) (work-id dcr) (work-id mds))
          "content-addressed Work identity is dialect-independent across the flat family")
      (is (= (:id (first (view/expressions uni))) (:id (first (view/expressions m21))))))
    (testing "yet each keeps its own Manifestation (distinct source records)"
      (let [mani #(:id (first (view/manifestations %)))]
        (is (= 2 (count (distinct [(mani (floor-project unimarc/plugin {} unimarc-miserables))
                                   (mani (floor-project marc21/plugin {} marc21-miserables))]))))))))

(deftest the-two-rungs-share-the-shape-but-keep-their-identity
  (testing "a flat UNIMARC and the entity-relation NG of the SAME work reach the same WEMI shape + agent"
    (let [uni (floor-project unimarc/plugin {} unimarc-fleurs)        ; floor (string-key) rung
          ng  (first (intermarc-ng/ingest ng-fixture {}))]            ; entity-relation rung
      (is (= [1 1 1] (wemi-shape uni) (wemi-shape ng)))               ; same F1/F2/F3 shape
      (is (= "Baudelaire, Charles" (agent-of uni) (agent-of ng)))     ; same agent *content*
      (testing "...but their Work IDENTITY differs — floor string-key vs NG ARK (two-rung, ADR 0019)"
        (is (not= (:id (first (view/works uni))) (:id (first (view/works ng)))))
        (testing "the NG Work is the authority ARK entity; the floor Work is content-addressed"
          (is (= "http://data.bnf.fr/ark:/12148/cb11947964w" (:iri (first (view/works ng)))))
          (is (nil? (:iri (first (view/works uni)))))))))             ; floor mints no authority iri
  (testing "INTERMARC's derive-wemi rung likewise keeps authority identity (the 145 link), not a string key"
    (let [im (wemi/derive-wemi (->> (intermarc/importer {} {:source/kind :file :source/value intermarc-fixture})
                                    :records (filter #(= "ark:/12148/cb304403926" (:source %))) first))]
      (is (= 1 (count (view/works im))))
      (is (some? (:iri (first (view/manifestations im))))))))         ; the ARK, not a urn:palomar string key

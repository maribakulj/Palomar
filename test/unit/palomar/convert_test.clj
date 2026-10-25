(ns palomar.convert-test
  "Tests for the end-to-end conversion assembly: every source spoke reaches the
   pivot, every target serialisation renders, and the loss report accounts all
   edges — proving the parts compose into one `convert` call."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.convert :as cv]
            [palomar.curate :as curate]))

(def ^:private fixtures
  {:intermarc    "test/fixtures/documentary/intermarc/sru/intermarcXchange/bib-flaubert-madame-bovary-start1-max30.xml"
   :intermarc-ng "test/fixtures/documentary/intermarc-ng/examples/fleurs-du-mal-oemi.xml"
   :unimarc      "test/fixtures/documentary/unimarc/sru/bnf-sru-verne-unimarc.xml"
   :dc           "test/fixtures/documentary/dublin-core/w3c_dc_example1.xml"
   :marc21       "test/fixtures/documentary/marc21/marcxml/loc_collection.xml"
   :mods         "test/fixtures/documentary/mods/loc_mods_book.xml"
   :iiif         "test/fixtures/documentary/iiif/manifest_image_simple.json"})

(defn- src [k] (slurp (fixtures k)))

(deftest every-spoke-reaches-the-rdf-pivot
  (testing "all seven importers convert to the LRMoo N-Triples pivot"
    (doseq [from [:intermarc :intermarc-ng :unimarc :dc :marc21 :mods :iiif]]
      (let [{:keys [output records loss]}
            (cv/convert {:from from :to :ntriples :source (src from)
                         :opts (if (= from :dc) {:record-id :dc/x} {})})]
        (is (pos? records) (str from " produced records"))
        (is (str/includes? output "lrmoo/F3_Manifestation") (str from " reached WEMI"))
        (is (pos? (:total loss)) (str from " reported loss"))
        (is (contains? (:by-edge loss) :export) (str from " has an export edge"))))))

(deftest the-pivot-renders-to-every-target
  (testing "one source (MARC21 Ray Charles) serialises to all ten targets"
    (let [s (src :marc21)]
      (doseq [[to needle] [[:ntriples   "lrmoo/F3_Manifestation"]
                           [:turtle     "@prefix lrmoo:"]
                           [:jsonld     "@graph"]
                           [:crm        "cidoc-crm/E73_Information_Object"]
                           [:crm-only   "cidoc-crm/E73_Information_Object"]
                           [:linked-art "\"type\":\"LinguisticObject\""]
                           [:dc         "<dc:title"]
                           [:marc21     "<controlfield tag=\"001\""]
                           [:mods       "<mods xmlns="]]]
        (let [{:keys [output loss]} (cv/convert {:from :marc21 :to to :source s})]
          (is (str/includes? output needle) (str "target " to))
          (is (pos? (:total loss)) (str "target " to " reported loss"))))
      (testing "target :iiif — a MARC record is not a valid manifest (no URI id, no canvas extent): refused, and said so"
        (let [{:keys [output loss]} (cv/convert {:from :marc21 :to :iiif :source s})]
          (is (= "" output))
          (is (contains? (set (:source-fields loss)) :iiif/manifest)))))))

(deftest the-marc21-to-linked-art-path-is-faithful
  (testing "MARC21 -> pivot -> Linked Art carries the title onto the edition's text (a LinguisticObject, not a physical object)"
    (let [{:keys [output]} (cv/convert {:from :marc21 :to :linked-art :source (src :marc21)})]
      (is (str/includes? output "\"type\":\"LinguisticObject\""))
      (is (not (str/includes? output "HumanMadeObject")))
      (is (str/includes? output "The Great Ray Charles"))
      (is (str/includes? output "https://linked.art/ns/v1/linked-art.json")))))

(deftest loss-report-merges-all-three-edges
  (testing "DC -> Linked Art: ingest (DC unmodelled) + projection + export loss in one report"
    (let [{:keys [loss]} (cv/convert {:from :dc :to :linked-art :source (src :dc)
                                      :opts {:record-id :dc/x}})
          fields (set (:source-fields loss))]
      (is (pos? (get-in loss [:by-edge :import :total])))
      (is (pos? (get-in loss [:by-edge :export :total])))
      (is (contains? fields :dc/subject))      ; DC report-at-ingest
      (is (contains? fields :canon/date)))))   ; not on the Linked Art profile

(deftest convert-report-adds-rendered-text
  (let [r (cv/convert-report {:from :dc :to :turtle :source (src :dc) :opts {:record-id :dc/x}})]
    (is (string? (:report r)))
    (is (str/includes? (:report r) "Loss report"))
    (is (str/includes? (:report r) "import edge"))))

(deftest rejects-unknown-formats
  (is (= #{:intermarc :intermarc-ng :unimarc :dc :marc21 :mods :iiif} (cv/source-formats)))
  (is (contains? (cv/target-formats) :linked-art))
  (is (thrown? Exception (cv/convert {:from :bogus :to :ntriples :source "x"})))
  (is (thrown? Exception (cv/convert {:from :dc :to :bogus :source "x" :opts {:record-id :r}}))))

;; --- the export status policy (audit S08) -----------------------------------

(defn- dc-wemi [] (:records (cv/to-wemi :dc {:record-id :dc/x} (src :dc))))

(deftest a-rejected-claim-is-never-exported
  (testing "after rejecting the inferred WEMI links, no target emits them"
    (let [rejected (:records (curate/curate (dc-wemi) curate/reject-all))
          accepted (:records (curate/curate (dc-wemi) curate/accept-all))]
      (is (not (str/includes? (:output (cv/export-records :ntriples rejected)) "R4_embodies")))
      (is (str/includes? (:output (cv/export-records :ntriples accepted)) "R4_embodies"))
      (testing "the entities themselves (not claims) are still typed"
        (is (str/includes? (:output (cv/export-records :ntriples rejected)) "lrmoo/F3_Manifestation"))))))

(deftest in-force-only-keeps-uncurated-inference-out-of-the-output
  (testing "by default the :proposed WEMI chain is exported; with :in-force-only? it is not, until accepted"
    (let [uncurated (dc-wemi)
          accepted  (:records (curate/curate (dc-wemi) curate/accept-all))]
      (is (str/includes? (:output (cv/export-records :ntriples uncurated)) "R4_embodies"))
      (is (not (str/includes? (:output (cv/export-records :ntriples uncurated {:in-force-only? true}))
                              "R4_embodies")))
      (is (str/includes? (:output (cv/export-records :ntriples accepted {:in-force-only? true}))
                         "R4_embodies")))))

(deftest an-entity-that-only-a-rejected-claim-made-is-not-exported
  (testing "after reject-all, the inferred Expression is neither linked nor typed"
    (let [rejected (:records (curate/curate (dc-wemi) curate/reject-all))
          nt       (:output (cv/export-records :ntriples rejected))]
      (is (not (str/includes? nt "lrmoo/F2_Expression")))
      (is (str/includes? nt "lrmoo/F3_Manifestation"))))     ; the record's own Manifestation stays
  (testing "with :in-force-only? an uncurated Work and Expression are not typed"
    (let [nt (:output (cv/export-records :ntriples (dc-wemi) {:in-force-only? true}))]
      (is (not (str/includes? nt "lrmoo/F1_Work")))
      (is (not (str/includes? nt "lrmoo/F2_Expression"))))))

(ns palomar.convert-test
  "Tests for the end-to-end conversion assembly: every source spoke reaches the
   pivot, every target serialisation renders, and the loss report accounts all
   edges — proving the parts compose into one `convert` call."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.convert :as cv]))

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
                           [:linked-art "HumanMadeObject"]
                           [:dc         "<dc:title"]
                           [:marc21     "<controlfield tag=\"001\""]
                           [:mods       "<mods xmlns="]
                           [:iiif       "\"type\":\"Manifest\""]]]
        (let [{:keys [output loss]} (cv/convert {:from :marc21 :to to :source s})]
          (is (str/includes? output needle) (str "target " to))
          (is (pos? (:total loss)) (str "target " to " reported loss")))))))

(deftest the-marc21-to-linked-art-path-is-faithful
  (testing "MARC21 -> pivot -> Linked Art carries the title onto a HumanMadeObject"
    (let [{:keys [output]} (cv/convert {:from :marc21 :to :linked-art :source (src :marc21)})]
      (is (str/includes? output "\"type\":\"HumanMadeObject\""))
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

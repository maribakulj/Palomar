(ns palomar.convert-matrix-integration-test
  "Hardening: the full 5×8 conversion matrix on real fixtures. Proves the
   assembly composes universally — every source spoke × every target serialisation
   runs without throwing and yields an ADR 0015 loss report — and documents the
   one honest degenerate case.

   The WEMI/CRM/Linked-Art targets read `:lrmoo/*` and work for every spoke; the
   round-trip exporters (`dc`, `marc21`) read `:canon/*`, which every spoke —
   INTERMARC included, since it now normalises to the floor alongside `derive-wemi` —
   populates."
  (:require [clojure.test :refer [deftest is testing]]
            [palomar.convert :as cv]))

(def ^:private fixtures
  {:intermarc "test/fixtures/documentary/intermarc/sru/intermarcXchange/bib-flaubert-madame-bovary-start1-max30.xml"
   :dc        "test/fixtures/documentary/dublin-core/w3c_dc_example1.xml"
   :marc21    "test/fixtures/documentary/marc21/marcxml/loc_collection.xml"
   :mods      "test/fixtures/documentary/mods/loc_mods_book.xml"
   :iiif      "test/fixtures/documentary/iiif/manifest_image_simple.json"})

(def ^:private record-counts {:intermarc 30 :dc 1 :marc21 2 :mods 1 :iiif 1})

(def ^:private froms [:intermarc :dc :marc21 :mods :iiif])
(def ^:private targets [:ntriples :turtle :jsonld :crm :crm-only :linked-art :dc :marc21])

(defn- run [from to]
  (cv/convert {:from from :to to :source (slurp (fixtures from))
               :opts (if (= from :dc) {:record-id :dc/x} {})}))

(deftest every-path-composes-and-reports-loss
  (testing "all 40 source×target paths run without throwing and return a loss report"
    (doseq [from froms, to targets]
      (let [{:keys [records loss]} (run from to)]
        (is (= (record-counts from) records) (str from "->" to " record count"))
        (is (map? loss) (str from "->" to " has a loss report"))
        (is (pos? (:total loss)) (str from "->" to " reports loss"))
        (is (seq (:by-edge loss)) (str from "->" to " has at least one loss edge"))
        (is (= records (:records loss)) (str from "->" to " loss record count"))))))

(deftest the-pivot-and-museum-targets-render-for-every-spoke
  (testing "ntriples/turtle/jsonld/crm/crm-only/linked-art read :lrmoo/* -> non-empty for all five spokes"
    (doseq [from froms
            to   [:ntriples :turtle :jsonld :crm :crm-only :linked-art]]
      (is (pos? (count (:output (run from to)))) (str from "->" to " is non-empty")))))

(deftest intermarc-populates-the-floor-and-round-trips
  (testing "INTERMARC normalises to :canon/* (alongside derive-wemi's enriched WEMI), so it round-trips"
    (let [dc (:output (run :intermarc :dc))]
      (is (pos? (count dc)) "intermarc->dc is now non-empty (was the degenerate case)")
      (is (re-find #"<dc:title>Madame Bovary</dc:title>" dc))
      (is (re-find #"<dc:creator>Flaubert, Gustave</dc:creator>" dc)))   ; controlled 100 -> :canon/agent
    (is (re-find #"<datafield tag=\"245\"" (:output (run :intermarc :marc21)))
        "intermarc->marc21 now carries the title datafield, not just the 001")
    (testing "all five spokes round-trip to DC non-empty"
      (doseq [from froms]
        (is (pos? (count (:output (run from :dc)))) (str from "->dc"))))))

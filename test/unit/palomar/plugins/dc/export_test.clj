(ns palomar.plugins.dc.export-test
  "Round-trip tests (WP-4): DC → canonical → DC. The real W3C fixture imports to
   the canonical floor, exports back to Dublin Core XML, and the test pins down
   exactly what survived and what the floor cost (ADR 0015): the six unmodelled
   elements gone, the agent roles and relation/source collapsed — then proves the
   trip is stable on a second pass."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.loss-report :as lr]
            [palomar.plugins :as plug]
            [palomar.plugins.dc :as dc]
            [palomar.plugins.dc.export :as dce]
            [palomar.plugins.mapping :as mapping]
            [palomar.runtime :as runtime]))

(def ^:private fixture
  (slurp "test/fixtures/documentary/dublin-core/w3c_dc_example1.xml"))

(defn- ->canonical
  "Import a DC `xml` string and normalise it to the canonical floor."
  [xml rid]
  (let [reg      (plug/register plug/empty-registry dc/plugin)
        compiled (mapping/compile-mappings (plug/all-mappings reg)
                                           (plug/effective-transforms reg))
        {:keys [records diagnostics]} (dc/importer {:record-id rid} xml)]
    {:canon  (:record (runtime/run-phase (first records) compiled :normalize))
     :ingest diagnostics}))

(deftest exports-the-mapped-fields-back-to-dc
  (testing "the nine mapped DC elements re-appear (values preserved, trimmed)"
    (let [{:keys [canon]} (->canonical fixture :dc/rt)
          out             (dce/->dc-xml canon)]
      (is (str/includes? out "xmlns:dc=\"http://purl.org/dc/elements/1.1/\""))
      (is (str/includes? out "<dc:title xml:lang=\"en\">Dublin Core Tutorial</dc:title>"))
      (is (str/includes? out "<dc:date>2007-01-06T00:00:00.00</dc:date>"))
      (is (str/includes? out "<dc:identifier>http://example.org/DC_example1.jpg</dc:identifier>"))
      (is (str/includes? out "<dc:description"))
      (testing "the three agents all come back, but as dc:creator (roles collapsed)"
        (is (= 3 (count (re-seq #"<dc:creator>" out))))
        (is (str/includes? out "Alan Kelsey"))
        (is (str/includes? out "The Dublin Core Task Force"))
        (is (not (str/includes? out "<dc:contributor>")))
        (is (not (str/includes? out "<dc:publisher>"))))
      (testing "relation + source both come back as dc:relation"
        (is (= 2 (count (re-seq #"<dc:relation>" out))))
        (is (not (str/includes? out "<dc:source>")))))))

(deftest the-six-unmodelled-elements-do-not-survive-the-trip
  (testing "subject/type/format/language/coverage/rights are gone from the output"
    (let [{:keys [canon]} (->canonical fixture :dc/rt)
          out             (dce/->dc-xml canon)]
      (doseq [el ["subject" "type" "format" "language" "coverage" "rights"]]
        (is (not (str/includes? out (str "<dc:" el))) (str "dc:" el " should be dropped"))))))

(deftest export-reports-the-collapse-as-under-specified
  (testing "agent-role and relation/source collapses are :export :under-specified loss"
    (let [{:keys [canon]} (->canonical fixture :dc/rt)
          ls (dce/export-losses canon)]
      (is (= 2 (count ls)))
      (is (every? #(= :loss/under-specified (:code %)) ls))
      (is (every? #(= :export (get-in % [:detail :loss/edge])) ls))
      (is (= #{:canon/agent :canon/relation}
             (set (map #(get-in % [:detail :loss/source-field]) ls)))))))

(deftest the-round-trip-is-stable-after-the-first-pass
  (testing "DC -> canon -> DC -> canon -> DC: the loss happens once, then fixes"
    (let [{c1 :canon} (->canonical fixture :dc/rt)
          out1        (dce/->dc-xml c1)
          {c2 :canon} (->canonical out1 :dc/rt)
          out2        (dce/->dc-xml c2)]
      (is (= out1 out2))
      (testing "the re-imported record keeps the surviving canonical values"
        (let [agents (->> (:assertions c2)
                          (filter #(and (= :canon/agent (:predicate %)) (string? (:value %))))
                          (map :value) set)]
          (is (= #{"Alan Kelsey" "The Dublin Core Task Force" "Alan Kelsey, Ltd."} agents)))))))

(deftest the-round-trip-loss-report-accounts-for-the-whole-gap
  (testing "import (6 dropped) + export (2 under-specified) is the round-trip's full loss"
    (let [{:keys [canon ingest]} (->canonical fixture :dc/rt)
          export-loss (dce/export-losses canon)
          report      (lr/conversion-report (concat ingest export-loss) {:records 1})]
      (is (= 6 (get-in report [:by-edge :import :total])))
      (is (= 2 (get-in report [:by-edge :export :total])))
      (is (= 2 (get-in report [:by-category :under-specified])))
      (is (= 6 (get-in report [:by-category :dropped])))
      (testing "renders for an institutional audit"
        (let [text (lr/format-conversion-report report)]
          (is (str/includes? text "import edge"))
          (is (str/includes? text "export edge")))))))

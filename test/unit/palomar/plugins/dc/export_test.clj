(ns palomar.plugins.dc.export-test
  "Round-trip tests (WP-4): DC → canonical → DC. The real W3C fixture imports to
   the canonical floor, exports back to Dublin Core XML, and the test pins down
   exactly what survived and what the floor cost (ADR 0015): the five unmodelled
   elements gone, relation/source collapsed, the agent roles kept — then proves
   the trip is stable on a second pass."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.loss-report :as lr]
            [palomar.model :as model]
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
      (testing "the three agents come back under their own DC element (roles kept)"
        (is (str/includes? out "<dc:creator>Alan Kelsey</dc:creator>"))
        (is (str/includes? out "<dc:contributor>The Dublin Core Task Force</dc:contributor>"))
        (is (str/includes? out "<dc:publisher>Alan Kelsey, Ltd.</dc:publisher>"))
        (is (= 1 (count (re-seq #"<dc:creator>" out)))))
      (testing "relation + source both come back as dc:relation"
        (is (= 2 (count (re-seq #"<dc:relation>" out))))
        (is (not (str/includes? out "<dc:source>")))))))

(deftest the-five-unmodelled-elements-do-not-survive-the-trip
  (testing "subject/type/format/language/coverage are gone from the output"
    (let [{:keys [canon]} (->canonical fixture :dc/rt)
          out             (dce/->dc-xml canon)]
      (doseq [el ["subject" "type" "format" "language" "coverage"]]
        (is (not (str/includes? out (str "<dc:" el))) (str "dc:" el " should be dropped"))))))

(deftest rights-come-back-as-dc-rights
  (testing "dc:rights reaches the floor as :canon/rights and is written back (M1)"
    (let [{:keys [canon]} (->canonical fixture :dc/rt)
          out             (dce/->dc-xml canon)]
      (is (str/includes? out "<dc:rights>Copyright 2007, Alan Kelsey, Ltd. All rights reserved.</dc:rights>"))))
  (testing "a provider of a digital surrogate is not written: DC has no element that states no role in the work (M1 review)"
    (let [r (model/record {:id :dc/p :kind :document
                           :fragments  [(model/fragment {:id :frag/p.0 :source [:test]})]
                           :assertions [(model/assertion {:subject :dc/p :predicate :canon/title :value "T"})
                                        (model/assertion {:subject :dc/p :predicate :canon/agent
                                                          :value (model/reference :frag/p.0)})
                                        (model/assertion {:subject :frag/p.0 :predicate :canon/agent :value "Example Herbarium"})
                                        (model/assertion {:subject :frag/p.0 :predicate :canon/role :value :provider})]})]
      (is (not (str/includes? (dce/->dc-xml r) "Example Herbarium")))
      (is (some #(and (= :canon/agent (get-in % [:detail :loss/source-field]))
                      (= "Example Herbarium" (get-in % [:detail :loss/value]))
                      (= :loss/dropped (:code %)))
                (dce/export-losses r))))))

(deftest rights-given-as-a-uri-attribute-are-read
  (testing "dcterms:license / dc:rights with rdf:resource carry the URI; text and URI are both kept; an empty element is reported (M1 review)"
    (let [xml (str "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\""
                   " xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"><dc:title>T</dc:title>"
                   "<dcterms:license rdf:resource=\"https://creativecommons.org/licenses/by/4.0/\"/>"
                   "<dc:rights rdf:resource=\"http://rightsstatements.org/vocab/InC/1.0/\">In Copyright</dc:rights>"
                   "<dc:rights>   </dc:rights></metadata>")
          {:keys [canon ingest]} (->canonical xml :dc/u)
          rights (set (keep #(when (and (= :canon/rights (:predicate %)) (string? (:value %))) (:value %)) (:assertions canon)))]
      (is (= #{"https://creativecommons.org/licenses/by/4.0/" "http://rightsstatements.org/vocab/InC/1.0/" "In Copyright"} rights))
      (is (some #(= :dc/rights (get-in % [:detail :loss/source-field])) ingest) "the empty dc:rights is reported")
      (is (str/includes? (dce/->dc-xml canon) "<dc:rights>https://creativecommons.org/licenses/by/4.0/</dc:rights>")))))

(deftest export-reports-the-collapse-as-under-specified
  (testing "the relation/source collapse is :export :under-specified loss; role-qualified agents are not"
    (let [{:keys [canon]} (->canonical fixture :dc/rt)
          ls (dce/export-losses canon)]
      (is (= 1 (count ls)))
      (is (every? #(= :loss/under-specified (:code %)) ls))
      (is (every? #(= :export (get-in % [:detail :loss/edge])) ls))
      (is (= #{:canon/relation}
             (set (map #(get-in % [:detail :loss/source-field]) ls))))))
  (testing "an agent with no stated role is still emitted as dc:creator and reported"
    (let [r  (model/record {:id :dc/x :kind :document
                            :assertions [(model/assertion {:subject :dc/x :predicate :canon/agent
                                                           :value "Anonyme"})]})
          ls (dce/export-losses r)]
      (is (str/includes? (dce/->dc-xml r) "<dc:creator>Anonyme</dc:creator>"))
      (is (= [:canon/agent] (map #(get-in % [:detail :loss/source-field]) ls)))))
  (testing "so is an agent whose role has no DC element (audit S11)"
    (let [r  (model/record {:id :dc/y :kind :document
                            :fragments  [(model/fragment {:id :frag/y.0 :source [:test]})]
                            :assertions [(model/assertion {:subject :dc/y :predicate :canon/agent
                                                           :value (model/reference :frag/y.0)})
                                         (model/assertion {:subject :frag/y.0 :predicate :canon/agent :value "Doré"})
                                         (model/assertion {:subject :frag/y.0 :predicate :canon/role :value :illustrator})]})]
      (is (str/includes? (dce/->dc-xml r) "<dc:creator>Doré</dc:creator>"))
      (is (some #(= :canon/agent (get-in % [:detail :loss/source-field])) (dce/export-losses r))))))

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
  (testing "import (5 dropped) + export (1 under-specified) is the round-trip's full loss"
    (let [{:keys [canon ingest]} (->canonical fixture :dc/rt)
          export-loss (dce/export-losses canon)
          report      (lr/conversion-report (concat ingest export-loss) {:records 1})]
      (is (= 5 (get-in report [:by-edge :import :total])))
      (is (= 1 (get-in report [:by-edge :export :total])))
      (is (= 1 (get-in report [:by-category :under-specified])))
      (is (= 5 (get-in report [:by-category :dropped])))
      (testing "renders for an institutional audit"
        (let [text (lr/format-conversion-report report)]
          (is (str/includes? text "import edge"))
          (is (str/includes? text "export edge")))))))

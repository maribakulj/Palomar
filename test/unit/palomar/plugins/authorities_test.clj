(ns palomar.plugins.authorities-test
  "Authority files as reconciliation pools (ADR 0038)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.cli :as cli]
            [palomar.plugins.authorities :as authorities]
            [palomar.reconcile :as reconcile]))

(def ^:private mads-person
  (str "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\""
       " xmlns:madsrdf=\"http://www.loc.gov/mads/rdf/v1#\">"
       "<madsrdf:PersonalName rdf:about=\"http://id.loc.gov/authorities/names/n79021164\">"
       "<rdf:type rdf:resource=\"http://www.loc.gov/mads/rdf/v1#Authority\"/>"
       "<madsrdf:authoritativeLabel>Kelsey, Alan</madsrdf:authoritativeLabel>"
       "<madsrdf:hasVariant><madsrdf:PersonalName><madsrdf:variantLabel>A. Kelsey</madsrdf:variantLabel></madsrdf:PersonalName></madsrdf:hasVariant>"
       "</madsrdf:PersonalName></rdf:RDF>"))

(deftest a-mads-authority-is-an-entry-with-its-variants
  (let [{:keys [entries]} (authorities/read-pool :rdfxml mads-person)]
    (is (= [{:id "http://id.loc.gov/authorities/names/n79021164" :label "Kelsey, Alan" :variants ["A. Kelsey"]}]
           entries))))

(deftest a-deleted-heading-is-no-entry-but-says-what-replaced-it
  (let [{:keys [entries deprecated]} (authorities/read-pool :rdfxml (slurp "test/fixtures/documentary/mads/loc_mads_authority.rdf"))]
    (is (empty? entries))
    (is (= [{:id          "http://id.loc.gov/authorities/lcsh/sh2002012502"
             :label       "Film and video adaptations"
             :replaced-by ["http://id.loc.gov/authorities/lcsh/sh2010004769"
                           "http://id.loc.gov/authorities/lcsh/sh2010004770"]}]
           deprecated))))

(deftest a-skos-concept-in-n-triples
  (let [nt (str "<http://ex.org/c1> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://www.w3.org/2004/02/skos/core#Concept> .\n"
                "<http://ex.org/c1> <http://www.w3.org/2004/02/skos/core#prefLabel> \"Breton, André\"@fr .\n"
                "<http://ex.org/c1> <http://www.w3.org/2004/02/skos/core#prefLabel> \"Andre Breton\"@en .\n"
                "<http://ex.org/c1> <http://www.w3.org/2004/02/skos/core#altLabel> \"André Breton\" .\n")]
    (is (= {:id "http://ex.org/c1" :label "Andre Breton" :variants ["Breton, André" "André Breton"]}
           (first (:entries (authorities/read-pool :ntriples nt {:lang "en"})))))
    (testing "a pool feeds the reconciliation"
      (let [pool (:entries (authorities/read-pool :ntriples nt))]
        (is (= "http://ex.org/c1" (:authority-id (first (reconcile/propose-agent-links ["André Breton"] pool)))))))))

(deftest reconcile-matches-a-source-against-an-authority-file
  (let [f (java.io.File/createTempFile "authorities" ".rdf")]
    (try
      (spit f mads-person)
      (let [{:keys [exit out]} (cli/run ["reconcile" "test/fixtures/documentary/dublin-core/w3c_dc_example1.xml"
                                         "--from" "dc" "--authorities" (str f)])]
        (is (= 0 exit))
        (is (str/includes? out "\"Alan Kelsey\" → \"Kelsey, Alan\""))
        (is (str/includes? out "exact")))
      (finally (.delete f)))))

(deftest an-eac-cpf-record-is-an-entry
  (let [xml (slurp "test/fixtures/documentary/eac-cpf/example_eac_cpf.xml")
        {:keys [entries unread]} (authorities/read-pool :eac-cpf xml)]
    (is (= [{:id nil :label "Fictional Testing Center" :variants ["Det påhittade testcentret"] :entity-type "corporateBody"}]
           entries)
        "a local recordId is no id: a match to it is never certifiable")
    (is (= "Det påhittade testcentret" (:label (first (:entries (authorities/read-pool :eac-cpf xml {:lang "swe"}))))))
    (is (= ["functions" "relations"] unread))
    (is (= :eac-cpf (authorities/sniff-format "x.xml" xml)))
    (is (false? (:certifiable? (first (reconcile/propose-agent-links ["Fictional Testing Center"] entries)))))))

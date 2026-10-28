(ns palomar.plugins.eac-test
  "EAC-CPF authority records as RiC-O agents (ADR 0045)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.convert :as convert]
            [palomar.plugins.eac :as eac]))

(def ^:private sample (slurp "test/fixtures/documentary/eac-cpf/example_eac_cpf.xml"))

(defn- of-kind [r k] (filter #(= k (:kind %)) (:entities r)))

(deftest an-authority-record-is-a-ric-o-agent
  (let [{:keys [records ingest]} (convert/to-wemi :eac-cpf {} sample)
        r  (first records)
        cb (first (of-kind r :rico/CorporateBody))
        names (keep #(when (and (= (:id cb) (:subject %)) (= :rico/name (:predicate %))) (:value %)) (:assertions r))]
    (testing "corporateBody is a rico:CorporateBody with its names, authorized first"
      (is (some? cb))
      (is (= ["Fictional Testing Center" "Det påhittade testcentret"] names)))
    (testing "a local recordId names the record, not the agent: no IRI"
      (is (nil? (:iri cb))))
    (testing "relations and unread elements are reported, not dropped"
      (is (some #(= :eac/relation (get-in % [:detail :loss/source-field])) ingest))
      (is (some #(= :eac/functions (get-in % [:detail :loss/source-field])) ingest)))
    (testing "its id comes from the agency and the record id"
      (is (= :eac/US-TSEAS_-_3001 (:id r))))))

(deftest an-entity-id-that-is-an-iri-identifies-the-agent
  (let [xml (str/replace sample "<entityType value=\"corporateBody\"/>"
                         "<entityId>http://id.loc.gov/authorities/names/n00000001</entityId><entityType value=\"corporateBody\"/>")
        r   (first (:records (convert/to-wemi :eac-cpf {} xml)))]
    (is (= "http://id.loc.gov/authorities/names/n00000001" (:iri (first (of-kind r :rico/CorporateBody)))))))

(deftest the-agent-is-written-in-ric-o
  (let [nt (:output (convert/convert {:from :eac-cpf :to :ntriples :source sample}))]
    (is (str/includes? nt "RiC/ontology#CorporateBody"))
    (is (str/includes? nt "Fictional Testing Center"))))

(deftest not-an-eac-document-is-refused
  (is (thrown? clojure.lang.ExceptionInfo (eac/importer {} "<ead/>"))))

(deftest a-record-without-a-record-id-says-its-id-is-not-durable
  (testing "with no recordId, the record is named by its digest, and the importer says so (post-audit 2, D4)"
    (let [xml (str/replace (slurp "test/fixtures/documentary/eac-cpf/example_eac_cpf.xml") #"<recordId>001</recordId>" "")
          {:keys [records diagnostics]} (eac/importer {} xml)]
      (is (= 1 (count records)))
      (is (some #(and (= :eac/recordId (get-in % [:detail :loss/source-field]))
                      (= (:id (first records)) (:subject %)))
                diagnostics)))))

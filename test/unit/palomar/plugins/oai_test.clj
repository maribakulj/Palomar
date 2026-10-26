(ns palomar.plugins.oai-test
  "OAI-PMH 2.0 responses (ADR 0037): the envelope is unwrapped, each record
   goes through the spoke of its format, its header stays on its provenance,
   and a harvest follows the resumption tokens through an injected fetch."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [palomar.convert :as convert]
            [palomar.plugins.oai :as oai]))

(defn- envelope [verb body]
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
       "<OAI-PMH xmlns=\"http://www.openarchives.org/OAI/2.0/\">"
       "<responseDate>2026-10-25T00:00:00Z</responseDate>"
       "<request verb=\"" verb "\">https://example.org/oai</request>"
       "<" verb ">" body "</" verb ">"
       "</OAI-PMH>"))

(defn- dc-record [id title]
  (str "<record><header><identifier>" id "</identifier><datestamp>2026-01-01</datestamp>"
       "<setSpec>books</setSpec></header>"
       "<metadata><oai_dc:dc xmlns:oai_dc=\"http://www.openarchives.org/OAI/2.0/oai_dc/\""
       " xmlns:dc=\"http://purl.org/dc/elements/1.1/\">"
       "<dc:title>" title "</dc:title><dc:creator>Breton, André</dc:creator><dc:date>1928</dc:date>"
       "</oai_dc:dc></metadata></record>"))

(def ^:private deleted
  "<record><header status=\"deleted\"><identifier>oai:example.org:gone</identifier><datestamp>2026-01-02</datestamp></header></record>")

(deftest a-response-is-read-record-by-record
  (let [xml (envelope "ListRecords" (str (dc-record "oai:example.org:1" "Nadja") deleted
                                         "<resumptionToken>page-2</resumptionToken>"))
        {:keys [records resumption-token]} (oai/read-response xml)]
    (is (= 2 (count records)))
    (is (= "page-2" resumption-token))
    (is (= {:identifier "oai:example.org:1" :datestamp "2026-01-01" :sets ["books"]} (:header (first records))))
    (is (str/includes? (:metadata (first records)) "Nadja"))
    (is (:deleted? (:header (second records))))
    (is (nil? (:metadata (second records))))))

(deftest each-record-goes-through-the-spoke-of-its-format
  (let [xml (envelope "ListRecords" (str (dc-record "oai:example.org:1" "Nadja")
                                         (dc-record "oai:example.org:2" "Les Champs magnétiques")
                                         deleted))
        {:keys [records deleted]} (oai/import-response "oai_dc" xml)]
    (is (= ["oai:example.org:1" "oai:example.org:2"] (mapv (comp oai/record-identifier :id) records)))
    (is (= ["oai:example.org:gone"] deleted))
    (is (= "oai:example.org:1" (get-in (first records) [:provenance :source :oai :identifier])))
    (testing "the record keeps where it came from at three levels: response, record, metadata document"
      (let [r (first (:records (convert/to-wemi :oai {} xml)))]
        (is (some? (get-in r [:provenance :source :sha256])) "the response")
        (is (= "oai:example.org:1" (get-in r [:provenance :source :oai :identifier])) "the harvested record")
        (is (re-matches #"[0-9a-f]{64}" (get-in r [:provenance :source :oai :metadata-sha256])) "the document its XPaths point into")))
    (testing "a deleted record reaches convert's result, for a synchroniser to remove (post-audit test 07)"
      (is (= ["oai:example.org:gone"] (:deleted (convert/convert {:from :oai :to :ntriples :source xml})))))
    (testing "and on through the pivot to any target"
      (let [{:keys [output records]} (convert/convert {:from :oai :to :ntriples :with-proposals? true :source xml})]
        (is (= 2 records))
        (is (str/includes? output "Nadja"))
        (is (str/includes? output "Les Champs magn"))))))

(deftest a-marcxml-record-goes-through-marc21
  (let [marc (str "<record xmlns=\"http://www.loc.gov/MARC21/slim\"><leader>00000nam a2200000 a 4500</leader>"
                  "<controlfield tag=\"001\">42</controlfield>"
                  "<datafield tag=\"245\" ind1=\"0\" ind2=\"0\"><subfield code=\"a\">Nadja</subfield></datafield>"
                  "</record>")
        xml  (envelope "GetRecord" (str "<record><header><identifier>oai:x:42</identifier>"
                                        "<datestamp>2026-01-01</datestamp></header><metadata>" marc "</metadata></record>"))
        {:keys [records]} (convert/to-wemi :oai {:metadata-prefix "marcxml"} xml)]
    (is (= 1 (count records)))
    (is (some #(= "Nadja" (:value %)) (:assertions (first records))))
    (is (some? (get-in (first records) [:provenance :source :sha256])) "the response's digest is kept beside the header")))

(deftest repository-errors-are-not-swallowed
  (is (empty? (:records (oai/import-response "oai_dc" (str "<OAI-PMH xmlns=\"http://www.openarchives.org/OAI/2.0/\">"
                                                           "<error code=\"noRecordsMatch\">none</error></OAI-PMH>"))))
      "no record is an empty harvest, not a failure")
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"badArgument"
                        (oai/import-response "oai_dc" (str "<OAI-PMH xmlns=\"http://www.openarchives.org/OAI/2.0/\">"
                                                           "<error code=\"badArgument\">bad</error></OAI-PMH>"))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no spoke reads"
                        (oai/import-response "lido" (envelope "ListRecords" "")))))

(deftest a-harvest-follows-the-resumption-tokens
  (let [pages {"https://example.org/oai?verb=ListRecords&metadataPrefix=oai_dc&set=books"
               (envelope "ListRecords" (str (dc-record "oai:x:1" "A") "<resumptionToken>t2</resumptionToken>"))
               "https://example.org/oai?verb=ListRecords&resumptionToken=t2"
               (envelope "ListRecords" (str (dc-record "oai:x:2" "B") "<resumptionToken/>"))}
        asked (atom [])
        fetch (fn [url] (swap! asked conj url) (or (pages url) (throw (ex-info "unexpected url" {:url url}))))
        res   (oai/harvest fetch "https://example.org/oai" "oai_dc" {:set "books"})]
    (is (empty? @asked) "lazy: nothing is fetched until a page is read")
    (is (= [["oai:x:1"] ["oai:x:2"]] (map #(map (comp :identifier :header) (:records %)) res)))
    (is (= 2 (count @asked)) "an empty token ends the harvest")))

(deftest an-oai-identifier-makes-a-readable-record-id
  (is (= :oai/oai_3Aabc_2Fa (oai/record-id "oai:abc/a")))
  (testing "distinct identifiers never share a record id (post-audit test 02)"
    (is (not= (oai/record-id "oai:abc/a") (oai/record-id "oai:abc:a")))
    (is (not= (oai/record-id "a_3A") (oai/record-id "a:"))))
  (testing "the id reads back as EDN and gives back its identifier"
    (doseq [s ["oai:bnf.fr:gallica/ark:/12148/bpt6k9410570f" "123" "é_x" "-a" "a b"]]
      (let [k (oai/record-id s)]
        (is (= k (edn/read-string (str k))) s)
        (is (= s (oai/record-identifier k)) s)))))

(defspec record-ids-are-injective 200
  (prop/for-all [a gen/string b gen/string]
                (= (= a b) (= (oai/record-id a) (oai/record-id b)))))

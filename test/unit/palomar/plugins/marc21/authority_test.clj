(ns palomar.plugins.marc21.authority-test
  "MARC21 Authority for EAC-CPF (ADR 0051): an authority record, its heading
   tag chosen by the entity type."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.convert :as convert]))

(def ^:private sample (slurp "test/fixtures/documentary/eac-cpf/example_eac_cpf.xml"))

(defn- dom [^String s]
  (.parse (.newDocumentBuilder (doto (javax.xml.parsers.DocumentBuilderFactory/newInstance) (.setNamespaceAware true)))
          (java.io.ByteArrayInputStream. (.getBytes s "UTF-8"))))

(defn- xp [doc ^String path]
  (let [nl (.evaluate (.newXPath (javax.xml.xpath.XPathFactory/newInstance)) path doc javax.xml.xpath.XPathConstants/NODESET)]
    (vec (for [i (range (.getLength ^org.w3c.dom.NodeList nl))] (str/trim (.getTextContent (.item ^org.w3c.dom.NodeList nl i)))))))

(defn- sf [tag code] (str "//*[local-name()='datafield'][@tag='" tag "']/*[local-name()='subfield'][@code='" code "']"))

(defn- out [xml] (dom (:output (convert/convert {:from :eac-cpf :to :marc21-authority :source xml}))))

(deftest a-corporate-body-is-a-110-authority
  (let [doc (out sample)]
    (is (every? #(= 24 (count %)) (xp doc "//*[local-name()='leader']")) "a leader is 24 characters")
    (testing "an authority record (Leader/06 z) with its control number and agency"
      (is (= \z (nth (first (xp doc "//*[local-name()='leader']")) 6)))
      (is (= ["US-TSEAS"] (xp doc "//*[local-name()='controlfield'][@tag='003']")))
      (is (= 1 (count (xp doc "//*[local-name()='controlfield'][@tag='001']")))))
    (testing "the authorized name heads the record as a corporate name, the others are see-from tracings"
      (is (= ["Fictional Testing Center"] (xp doc (sf "110" "a"))))
      (is (= ["Det påhittade testcentret"] (xp doc (sf "410" "a"))))
      (is (empty? (xp doc (sf "100" "a")))))))

(deftest a-person-and-a-family-take-100
  (let [person (out (str/replace sample "value=\"corporateBody\"" "value=\"person\""))
        family (out (str/replace sample "value=\"corporateBody\"" "value=\"family\""))]
    (is (= ["Fictional Testing Center"] (xp person (sf "100" "a"))))
    (is (= ["1"] (xp person "//*[local-name()='datafield'][@tag='100']/@ind1")))
    (is (= ["3"] (xp family "//*[local-name()='datafield'][@tag='100']/@ind1")))
    (is (= ["Det påhittade testcentret"] (xp person (sf "400" "a"))))))

(deftest an-entity-id-is-a-024-and-history-a-678
  (let [xml (-> sample
                (str/replace "<entityType value=\"corporateBody\"/>"
                             "<entityId>http://id.loc.gov/authorities/names/n00000001</entityId><entityType value=\"corporateBody\"/>")
                (str/replace "<description>" "<description><existDates><dateRange><fromDate>1919</fromDate><toDate>1999</toDate></dateRange></existDates><biogHist><p>Founded in 1919.</p></biogHist>"))
        doc (out xml)]
    (is (= ["http://id.loc.gov/authorities/names/n00000001"] (xp doc (sf "024" "a"))))
    (is (= ["uri"] (xp doc (sf "024" "2"))))
    (is (= ["Founded in 1919."] (xp doc (sf "678" "a"))))
    (testing "existence dates of a corporate body are its dates of establishment and termination"
      (is (= ["1919"] (xp doc (sf "046" "s"))))
      (is (= ["1999"] (xp doc (sf "046" "t")))))))

(deftest only-an-authority-is-an-authority-record
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot describe archival units"
                        (convert/convert {:from :ead :to :marc21-authority
                                          :source (slurp "test/fixtures/documentary/ead/ead3/ead3_multi_level_optimum.xml")}))))

(deftest qualified-dates-are-not-coded
  (testing "046 takes plain years only; a qualified date stays as written, in 678 (post-audit 4, F6)"
    (let [xml (str/replace sample "<description>" "<description><existDates><date>vers 1800, après 1850</date></existDates>")
          doc (out xml)]
      (is (empty? (xp doc (sf "046" "s"))))
      (is (= ["vers 1800, après 1850"] (xp doc (sf "678" "a")))))))

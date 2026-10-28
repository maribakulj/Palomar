(ns palomar.plugins.marc21.archival-test
  "MARC21 for archives (ADR 0050): an archival unit as an archival-control
   record (Leader/08 `a`), its fields chosen by what they mean."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.convert :as convert]))

(def ^:private ead3 (slurp "test/fixtures/documentary/ead/ead3/ead3_multi_level_optimum.xml"))

(defn- dom [^String s]
  (.parse (.newDocumentBuilder (doto (javax.xml.parsers.DocumentBuilderFactory/newInstance) (.setNamespaceAware true)))
          (java.io.ByteArrayInputStream. (.getBytes s "UTF-8"))))

(defn- xp [doc ^String path]
  (let [nl (.evaluate (.newXPath (javax.xml.xpath.XPathFactory/newInstance)) path doc javax.xml.xpath.XPathConstants/NODESET)]
    (vec (for [i (range (.getLength ^org.w3c.dom.NodeList nl))] (str/trim (.getTextContent (.item ^org.w3c.dom.NodeList nl i)))))))

(defn- sf [tag code] (str "//*[local-name()='datafield'][@tag='" tag "']/*[local-name()='subfield'][@code='" code "']"))

(deftest a-unit-is-an-archival-record
  (let [{:keys [output records]} (convert/convert {:from :ead :to :marc21-archival :source ead3})
        doc (dom output)]
    (testing "every unit is a record, in one collection, under archival control"
      (is (= records (count (xp doc "//*[local-name()='record']"))))
      (is (every? #(= \a (nth % 8)) (xp doc "//*[local-name()='leader']"))))
    (testing "the fonds is a collection, a component a subunit (Leader/07)"
      (is (= \c (nth (first (xp doc "//*[local-name()='leader']")) 7)))
      (is (= \d (nth (second (xp doc "//*[local-name()='leader']")) 7))))
    (testing "the cote is a local call number, not an LCCN; the dates are inclusive dates, not a publication date"
      (is (empty? (xp doc (sf "010" "a"))))
      (is (empty? (xp doc (sf "260" "c"))))
      (is (= "MA-1" (first (xp doc (sf "099" "a")))))
      (is (= "1789/1996" (first (xp doc (sf "245" "f"))))))
    (testing "the level is 351 $c, the hierarchy a 773 $w to the parent's control number"
      (is (some #{"collection"} (xp doc (sf "351" "c"))))
      (let [ctrl (xp doc "//*[local-name()='controlfield'][@tag='001']")
            up   (xp doc (sf "773" "w"))]
        (is (= (dec records) (count up)))
        (is (every? (set ctrl) up) "each 773 $w names a record of the collection")))
    (testing "the notes the importer reads have their own fields: extent, access, biography"
      (is (seq (xp doc (sf "300" "a"))))
      (is (seq (xp doc (sf "506" "a"))))
      (is (seq (xp doc (sf "545" "a")))))
    (testing "the creator's element says personal or corporate name"
      (is (seq (xp doc (sf "100" "a")))))))

(deftest a-fonds-summary-is-one-record
  (testing "the fonds mode writes the fonds alone, its components reported as summarized"
    (let [{:keys [output loss]} (convert/convert {:from :ead :to :marc21-fonds :source ead3})
          doc (dom output)]
      (is (= 1 (count (xp doc "//*[local-name()='record']"))))
      (is (= "Lowell Thomas Papers" (first (xp doc (sf "245" "a")))))
      (is (empty? (xp doc (sf "773" "w"))))
      (is (seq (get-in loss [:by-edge :export]))))))

(deftest a-bibliographic-record-is-not-an-archival-unit
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot describe bibliographic"
                        (convert/convert {:from :marc21 :to :marc21-archival
                                          :source (slurp "test/fixtures/documentary/marc21/marcxml/loc_collection.xml")}))))

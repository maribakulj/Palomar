(ns palomar.plugins.marc21.object-test
  "MARC21 for museum objects (ADR 0053): a LIDO object as a discovery record of
   a two-dimensional graphic or a three-dimensional artefact."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.convert :as convert]))

(def ^:private lido (slurp "test/fixtures/documentary/lido/staedel-oai-lido-sample.xml"))

(defn- dom [^String s]
  (.parse (.newDocumentBuilder (doto (javax.xml.parsers.DocumentBuilderFactory/newInstance) (.setNamespaceAware true)))
          (java.io.ByteArrayInputStream. (.getBytes s "UTF-8"))))

(defn- xp [doc ^String path]
  (let [nl (.evaluate (.newXPath (javax.xml.xpath.XPathFactory/newInstance)) path doc javax.xml.xpath.XPathConstants/NODESET)]
    (vec (for [i (range (.getLength ^org.w3c.dom.NodeList nl))] (str/trim (.getTextContent (.item ^org.w3c.dom.NodeList nl i)))))))

(defn- sf [rec tag code] (str "(//*[local-name()='record'])[" rec "]/*[local-name()='datafield'][@tag='" tag "']/*[local-name()='subfield'][@code='" code "']"))

(deftest a-painting-is-a-two-dimensional-graphic
  (let [{:keys [output records]} (convert/convert {:from :oai :opts {:metadata-prefix "lido"} :to :marc21-object :source lido})
        doc (dom output)]
    (is (= records (count (xp doc "//*[local-name()='record']"))))
    (is (every? #(= 24 (count %)) (xp doc "//*[local-name()='leader']")) "a leader is 24 characters")
    (testing "Leader/06 k: a painting or a drawing is a two-dimensional nonprojectable graphic"
      (is (every? #(= \k (nth % 6)) (xp doc "//*[local-name()='leader']"))))
    (testing "the first record says what the LIDO record says, field by field"
      (is (= ["Bildnis einer Dame in einem schwarzen Kleid"] (xp doc (sf 1 "245" "a"))))
      (is (= ["Portrait of a Woman Wearing a Black Dress"] (xp doc (sf 1 "246" "a"))))
      (is (= ["Maes, Nicolaes"] (xp doc (sf 1 "100" "a"))))
      (is (= ["1634-1693"] (xp doc (sf 1 "100" "d"))))
      (is (= ["http://d-nb.info/gnd/122131924"] (xp doc (sf 1 "100" "0"))))
      (is (= ["ca. 1668 - 1670"] (xp doc (sf 1 "264" "c"))) "the production date, not a publication")
      (is (= ["0"] (xp doc "(//*[local-name()='record'])[1]/*[local-name()='datafield'][@tag='264']/@ind2")))
      (is (= ["13"] (xp doc (sf 1 "099" "a"))) "the inventory number")
      (is (some #{"Oil on canvas"} (xp doc (sf 1 "340" "a"))))
      (is (some #(str/includes? % "85.6") (xp doc (sf 1 "300" "c"))))
      (is (= ["Städel Museum"] (xp doc (sf 1 "852" "a"))))
      (is (some #(str/includes? % "1816") (xp doc (sf 1 "541" "a"))) "the acquisition")
      (is (some #{"http://iconclass.org/42E411"} (xp doc (sf 1 "650" "0"))) "a subject, by its Iconclass URI")
      (is (= "http://d-nb.info/gnd/4122164-3" (first (xp doc (sf 1 "655" "0")))) "the work type first, then the classifications")
      (is (seq (xp doc (sf 1 "856" "u")))))))

(deftest only-an-object-is-an-object-record
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot describe bibliographic"
                        (convert/convert {:from :marc21 :to :marc21-object
                                          :source (slurp "test/fixtures/documentary/marc21/marcxml/loc_collection.xml")}))))

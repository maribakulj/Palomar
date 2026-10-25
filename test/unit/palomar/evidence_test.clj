(ns palomar.evidence-test
  "Every value keeps where it was read (ADR 0031): the selector an importer
   records finds that very value in the source document, the evidence survives
   normalisation and projection, and PROV-O writes it as a Web Annotation."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.convert :as convert]
            [palomar.model :as model]
            [palomar.plugins.lrmoo.ntriples :as nt]
            [palomar.plugins.lrmoo.prov :as prov])
  (:import [javax.xml.parsers DocumentBuilderFactory]
           [javax.xml.xpath XPathConstants XPathFactory]
           [org.w3c.dom Element]))

(defn- dom [path]
  (let [f (doto (DocumentBuilderFactory/newInstance)
            (.setNamespaceAware true)
            (.setFeature "http://apache.org/xml/features/disallow-doctype-decl" true))]
    (.parse (.newDocumentBuilder f) (java.io.File. ^String path))))

(defn- records-named [doc local]
  (let [^org.w3c.dom.NodeList nl (.evaluate (.newXPath (XPathFactory/newInstance))
                                            (str "//*[local-name()='" local "']") doc XPathConstants/NODESET)]
    (for [i (range (.getLength nl))] (.item nl (int i)))))

(defn- select-text [^Element ctx xpath]
  (str/trim (.evaluate (.newXPath (XPathFactory/newInstance)) ^String xpath ctx XPathConstants/STRING)))

(defn- checked-natives
  "`[[value xpath] …]` for the native assertions of `record` carrying an XPath."
  [record ns]
  (for [a (:assertions record)
        :when (and (= ns (namespace (:predicate a))) (string? (:value a)))
        ev (model/evidence-of a)
        :when (= :xpath (get-in ev [:selector :type]))]
    [(:value a) (get-in ev [:selector :value])]))

(deftest every-xpath-finds-the-value-it-attests
  (doseq [[from path rec-local ns] [[:marc21 "test/fixtures/documentary/marc21/marcxml/loc_collection.xml" "record" "marc21"]
                                    [:mods "test/fixtures/documentary/mods/loc_mods_book.xml" "mods" "mods"]
                                    [:dc "test/fixtures/documentary/dublin-core/w3c_dc_example1.xml" "metadata" "dc"]]]
    (testing (name from)
      (let [{:keys [records]} (convert/to-wemi from (if (= from :dc) {:record-id :dc/x} {}) (slurp path))
            ctxs  (vec (records-named (dom path) rec-local))]
        (is (= (count records) (count ctxs)))
        (doseq [[rec ctx] (map vector records ctxs)
                :let [pairs (checked-natives rec ns)]]
          (is (seq pairs))
          (doseq [[v xp] pairs
                  :let [found (select-text ctx xp)
                        ws    #(str/replace (str/trim %) #"\s+" " ")]]
            ;; MARC and DC values are an element's text; a MODS name joins its
            ;; nameParts, so its element holds the value among other text
            (if (= :mods from)
              (is (str/includes? (ws found) (ws v)) (str from " " xp))
              (is (= (str/trim v) found) (str from " " xp)))))))))

(deftest evidence-survives-normalisation-and-projection
  (let [rec (first (:records (convert/to-wemi :marc21 {} (slurp "test/fixtures/documentary/marc21/marcxml/loc_collection.xml"))))
        ev  (fn [p] (some #(when (= p (:predicate %)) (model/evidence-of %)) (:assertions rec)))]
    (testing "the canonical title keeps the 245 $a it was mapped from"
      (is (some #(str/includes? (get-in % [:selector :value]) "@tag='245'") (ev :canon/title))))
    (testing "and the Manifestation's projected title keeps it too"
      (is (some #(str/includes? (get-in % [:selector :value] "") "@tag='245'") (ev :lrmoo/R33_has_string))))
    (testing "the record names the exact document"
      (is (= 64 (count (get-in rec [:provenance :source :sha256])))))))

(deftest a-iiif-image-keeps-its-canvas
  (let [rec (first (:records (convert/to-wemi :iiif {} (slurp "test/fixtures/documentary/iiif/manifest_book_simple.json"))))
        evs (mapcat model/evidence-of (filter #(= :canon/digital-object (:predicate %)) (:assertions rec)))]
    (is (seq evs))
    (is (every? #(str/includes? (get-in % [:source :uri]) "/canvas/") evs))))

(deftest prov-writes-the-attestation-as-a-web-annotation
  (let [rec (first (:records (convert/to-wemi :marc21 {} (slurp "test/fixtures/documentary/marc21/marcxml/loc_collection.xml"))))
        ts  (nt/parse (prov/->ntriples rec))
        oa  "http://www.w3.org/ns/oa#"]
    (is (some #(= {:iri (str oa "SpecificResource")} (nth % 2)) ts))
    (is (some #(= {:iri (str oa "XPathSelector")} (nth % 2)) ts))
    (is (some #(= (str oa "hasSource") (:iri (second %))) ts))
    (is (some #(= "urn:palomar:vocab#sha256" (:iri (second %))) ts))))

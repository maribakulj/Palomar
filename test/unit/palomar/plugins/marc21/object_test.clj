(ns palomar.plugins.marc21.object-test
  "MARC21 for museum objects (ADR 0053): a LIDO object as a discovery record of
   a two-dimensional graphic or a three-dimensional artefact."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.convert :as convert]
            [palomar.fidelity]))

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

(deftest a-concept-of-no-known-vocabulary-is-not-said-to-have-one
  (testing "650/655 #7 only with the vocabulary in $2; otherwise #4, the URI kept in $0 (post-audit 4, F4)"
    (let [xml (str "<lido:lido xmlns:lido=\"http://www.lido-schema.org\"><lido:lidoRecID>S-1</lido:lidoRecID><lido:descriptiveMetadata>"
                   "<lido:objectClassificationWrap><lido:objectWorkTypeWrap><lido:objectWorkType><lido:term>Gemälde</lido:term></lido:objectWorkType></lido:objectWorkTypeWrap></lido:objectClassificationWrap>"
                   "<lido:objectIdentificationWrap><lido:titleWrap><lido:titleSet><lido:appellationValue>T</lido:appellationValue></lido:titleSet></lido:titleWrap></lido:objectIdentificationWrap>"
                   "<lido:objectRelationWrap><lido:subjectWrap><lido:subjectSet><lido:subject>"
                   "<lido:subjectConcept><lido:term>Hund</lido:term></lido:subjectConcept>"
                   "<lido:subjectConcept><lido:conceptID>http://vocab.example.org/c/42</lido:conceptID><lido:term>Katze</lido:term></lido:subjectConcept>"
                   "<lido:subjectConcept><lido:conceptID>http://iconclass.org/34B11</lido:conceptID><lido:term>dog</lido:term></lido:subjectConcept>"
                   "<lido:subjectConcept><lido:conceptID>http://id.loc.gov/authorities/subjects/sh85038796</lido:conceptID><lido:term>Dogs</lido:term></lido:subjectConcept>"
                   "<lido:subjectConcept><lido:conceptID>http://id.loc.gov/authorities/names/n79021164</lido:conceptID><lido:term>Twain</lido:term></lido:subjectConcept>"
                   "</lido:subject></lido:subjectSet></lido:subjectWrap></lido:objectRelationWrap></lido:descriptiveMetadata></lido:lido>")
          doc (dom (:output (convert/convert {:from :lido :to :marc21-object :source xml})))
          ind (fn [label] (xp doc (str "//*[local-name()='datafield'][*[@code='a']='" label "']/@ind2")))]
      (is (= ["4"] (ind "Hund")) "a term with no URI: source not specified")
      (is (= ["4"] (ind "Katze")) "a URI of no known vocabulary: source not specified")
      (is (= ["http://vocab.example.org/c/42"] (xp doc "//*[local-name()='datafield'][*[@code='a']='Katze']/*[@code='0']")))
      (is (= ["7"] (ind "dog")))
      (is (= ["iconclass"] (xp doc "//*[local-name()='datafield'][*[@code='a']='dog']/*[@code='2']")))
      (is (= ["4"] (ind "Gemälde")) "the work type with no URI")
      (is (= ["0"] (ind "Dogs")) "LCSH is ind2 0, with no $2 (F4 review)")
      (is (empty? (xp doc "//*[local-name()='datafield'][*[@code='a']='Dogs']/*[@code='2']")))
      (is (= ["4"] (ind "Twain")) "an LC name is no subject heading: no lcsh code")
      (testing "no #7 field lacks its $2"
        (is (empty? (xp doc "//*[local-name()='datafield'][@ind2='7'][not(*[@code='2'])]")))))))

(deftest prints-drawings-and-photographs-are-graphics
  (testing "the Städel's graphic arts (real, CC0) are two-dimensional graphics, their creators main entries (post-audit 4, F7)"
    (let [{:keys [records]} (convert/to-wemi :oai {:metadata-prefix "lido"} (slurp "test/fixtures/documentary/lido/staedel-oai-lido-graphics.xml"))
          {:keys [output loss]} (convert/export-records :marc21-object records)
          doc (dom output)]
      (is (= 6 (count (xp doc "//*[local-name()='leader']"))))
      (is (every? #(= \k (nth % 6)) (xp doc "//*[local-name()='leader']")) "a drawing, a print, a photograph")
      (is (some #{"inventor"} (xp doc "//*[local-name()='datafield'][@tag='100'][*[@code='a']='Dürer, Albrecht']/*[@code='e']")))
      (is (some #{"engraver"} (xp doc "//*[local-name()='datafield'][@tag='100'][*[@code='a']='Dürer, Albrecht']/*[@code='e']")))
      (testing "the titles' languages, which MARC has no subfield for, are reported"
        (is (some #(and (= :lido/title-name (get-in % [:detail :loss/source-field])) (str/includes? (:message %) "lang")) loss))))))

(defn- rec42 [src]
  (str "<lido:lido xmlns:lido=\"http://www.lido-schema.org\"><lido:lidoRecID lido:source=\"" src "\">42</lido:lidoRecID>"
       "<lido:descriptiveMetadata><lido:objectIdentificationWrap><lido:titleWrap><lido:titleSet><lido:appellationValue>T</lido:appellationValue>"
       "</lido:titleSet></lido:titleWrap></lido:objectIdentificationWrap></lido:descriptiveMetadata></lido:lido>"))

(deftest whose-001-it-is-is-said
  (testing "the 003 names the organization whose 001 it is, from the lidoRecID's source when it is an ISIL (post-audit 5, G3)"
    (let [out (fn [src] (:output (convert/convert {:from :lido :to :marc21-object :source (rec42 src)})))
          lss (fn [src] (let [{:keys [records]} (convert/to-wemi :lido {} (rec42 src))] (:loss (convert/export-records :marc21-object records))))]
      (is (str/includes? (out "DE-Mb112") "<controlfield tag=\"003\">DE-Mb112</controlfield>"))
      (is (str/includes? (out "http://ld.zdb-services.de/resource/organisations/DE-MUS-048017") "<controlfield tag=\"003\">DE-MUS-048017</controlfield>")
          "the ISIL of a ZDB organisation URI")
      (testing "a source that is no organization code: no 003, and the ambiguity is reported"
        (is (not (str/includes? (out "http://museum-b.example/") "tag=\"003\"")))
        (is (some #(and (= :lido/rec-id-source (get-in % [:detail :loss/source-field])) (str/includes? (:message %) "003")) (lss "http://museum-b.example/"))))
      (testing "two institutions' records with the same local id are told apart"
        (is (not= (out "DE-Mb112") (out "DE-Mb113"))))
      (testing "the Städel's records say whose they are"
        (is (str/includes? (:output (convert/convert {:from :oai :opts {:metadata-prefix "lido"} :to :marc21-object :source lido}))
                           "<controlfield tag=\"003\">DE-MUS-048017</controlfield>")))
      (doseq [src ["DE-Mb112" "http://museum-b.example/" "http://ld.zdb-services.de/resource/organisations/DE-MUS-048017"]]
        (is (empty? (:unaccounted (palomar.fidelity/audit {:from :lido :to :marc21-object :source (rec42 src)}))) src)))))

(deftest no-false-isil-is-written
  (testing "only an ISIL is a 003: not a licence, an encoding, a GND id (G3 review)"
    (doseq [src ["CC-BY" "UTF-8" "ISO-8601" "GND-118540238" "DE-ABCDEFGHIJKLMNOP"]]
      (is (not (str/includes? (:output (convert/convert {:from :lido :to :marc21-object :source (rec42 src)})) "tag=\"003\"")) src))
    (is (str/includes? (:output (convert/convert {:from :lido :to :marc21-object :source (rec42 "https://ld.zdb-services.de/resource/organisations/DE-Mb112/")}))
                       "<controlfield tag=\"003\">DE-Mb112</controlfield>"))))

(deftest an-unknown-type-is-not-said-three-dimensional-silently
  (testing "Leader/06 r for a three-dimensional artefact or a natural object; a type it cannot tell is reported (post-audit 5, G4)"
    (let [rec (fn [t] (str "<lido:lido xmlns:lido=\"http://www.lido-schema.org\"><lido:lidoRecID>K-1</lido:lidoRecID><lido:descriptiveMetadata>"
                           "<lido:objectClassificationWrap><lido:objectWorkTypeWrap><lido:objectWorkType><lido:term>" t "</lido:term></lido:objectWorkType></lido:objectWorkTypeWrap></lido:objectClassificationWrap>"
                           "<lido:objectIdentificationWrap><lido:titleWrap><lido:titleSet><lido:appellationValue>T</lido:appellationValue></lido:titleSet></lido:titleWrap></lido:objectIdentificationWrap>"
                           "<lido:eventWrap><lido:eventSet><lido:event><lido:eventType><lido:term>production</lido:term></lido:eventType></lido:event></lido:eventSet></lido:eventWrap>"
                           "</lido:descriptiveMetadata></lido:lido>"))
          ldr (fn [t] (nth (second (re-find #"<leader>([^<]+)</leader>" (:output (convert/convert {:from :lido :to :marc21-object :source (rec t)})))) 6))
          undetermined? (fn [t] (let [{:keys [records]} (convert/to-wemi :lido {} (rec t))]
                                  (some #(str/includes? (str (:message %)) "Leader/06") (:loss (convert/export-records :marc21-object records)))))]
      (is (= \k (ldr "Zeichnung")))
      (is (= \r (ldr "Skulptur")))
      (is (not (undetermined? "Skulptur")))
      (is (= \r (ldr "Ding")))
      (is (undetermined? "Ding") "a type it cannot tell is reported, not silently three-dimensional"))))

(deftest a-book-is-language-material
  (testing "Leader/06 a for a printed book, t for a manuscript: language material, not an artefact (post-audit 6, H3)"
    (let [rec (fn [t] (str "<lido:lido xmlns:lido=\"http://www.lido-schema.org\"><lido:lidoRecID>B-1</lido:lidoRecID><lido:descriptiveMetadata>"
                           "<lido:objectClassificationWrap><lido:objectWorkTypeWrap><lido:objectWorkType><lido:term>" t "</lido:term></lido:objectWorkType></lido:objectWorkTypeWrap></lido:objectClassificationWrap>"
                           "<lido:objectIdentificationWrap><lido:titleWrap><lido:titleSet><lido:appellationValue>T</lido:appellationValue></lido:titleSet></lido:titleWrap></lido:objectIdentificationWrap>"
                           "</lido:descriptiveMetadata></lido:lido>"))
          ldr (fn [t] (nth (second (re-find #"<leader>([^<]+)</leader>" (:output (convert/convert {:from :lido :to :marc21-object :source (rec t)})))) 6))]
      (is (= \a (ldr "book")))
      (is (= \a (ldr "Buch")))
      (is (= \t (ldr "manuscript")))
      (is (= \t (ldr "Handschrift")))
      (is (= \t (ldr "manuscrit")))
      (is (= \k (ldr "Zeichnung")) "a drawing stays a graphic")
      (is (= \r (ldr "Skulptur")))
      (testing "a graphic stays one, whatever book or manuscript it shows or illustrates (H3 review)"
        (doseq [t ["Holzschnitt, Buchillustration" "Exlibris (bookplate), etching" "photograph of a manuscript"
                   "Zeichnung, Handschrift" "book illustration" "Buchmalerei"]]
          (is (= \k (ldr t)) t)))
      (testing "a printed book stays a book"
        (doseq [t ["printed book" "gedrucktes Buch" "Druckschrift" "livre imprimé" "incunabula" "Bücher"]]
          (is (= \a (ldr t)) t)))
      (testing "a book's fitting is an artefact, a word with buch inside no book"
        (doseq [t ["Bucheinband" "Bucheinbände" "Buchdeckel" "Buchschließe" "Buchstütze" "bookcase" "bookend" "book cover" "reliure" "Handschriftenkasten"]]
          (is (= \r (ldr t)) t))
        (doseq [t ["Buchse" "tissu imprimé" "autographed photograph"]]
          (is (not (#{\a \t} (ldr t))) t)))
      (testing "a book of hours is a manuscript; music and maps are their own MARC types"
        (doseq [t ["livre d'heures" "Book of Hours" "Stundenbuch"]]
          (is (= \t (ldr t)) t))
        (is (= \d (ldr "Musikhandschrift")))
        (is (= \d (ldr "music manuscript")))
        (is (= \f (ldr "manuscript map")))
        (is (= \c (ldr "Notenbuch, gedruckt")) "printed music")
        (is (= \e (ldr "atlas, printed book")) "a printed map")))))

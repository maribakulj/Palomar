(ns palomar.plugins.authorities-test
  "Authority files as reconciliation pools (ADR 0038)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.agent-dates :as dates]
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
    (is (= [{:id "http://id.loc.gov/authorities/names/n79021164" :label "Kelsey, Alan" :variants ["A. Kelsey"]
             :entity-type "person"}]
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
        ;; a name-only proposal is a close match, whatever its score (ADR 0057)
        (is (str/includes? out "1.00  close")))
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

;; --- authority profiles: dates, kind (lot M3) --------------------------------

(def ^:private dumas-pere-life (dates/parse-life-dates "1802-1870"))

(deftest a-mads-authority-reads-its-birth-and-death
  (testing "madsrdf:birthDate / deathDate on the authority, as literals"
    (let [xml (str/replace mads-person "</madsrdf:PersonalName></rdf:RDF>"
                           "<madsrdf:birthDate>1802-07-24</madsrdf:birthDate><madsrdf:deathDate>1870</madsrdf:deathDate></madsrdf:PersonalName></rdf:RDF>")]
      (is (= {:birth {:from 1802 :to 1802 :text "1802-07-24"} :death {:from 1870 :to 1870 :text "1870"}}
             (:dates (first (:entries (authorities/read-pool :rdfxml xml))))))))
  (testing "on the real-world object it identifies (identifiesRWO), as a node with a label, as id.loc.gov writes them"
    (let [xml (str/replace mads-person "</madsrdf:PersonalName></rdf:RDF>"
                           (str "<madsrdf:identifiesRWO><madsrdf:RWO rdf:about=\"http://id.loc.gov/rwo/agents/n79021164\">"
                                "<madsrdf:birthDate><rdf:Description><rdfs:label xmlns:rdfs=\"http://www.w3.org/2000/01/rdf-schema#\">ca. 1830</rdfs:label></rdf:Description></madsrdf:birthDate>"
                                "</madsrdf:RWO></madsrdf:identifiesRWO></madsrdf:PersonalName></rdf:RDF>"))]
      (is (= {:birth {:from 1810 :to 1850 :text "ca. 1830" :approximate? true}}
             (:dates (first (:entries (authorities/read-pool :rdfxml xml))))))))
  (testing "no dates stated, no :dates key: SKOS has none"
    (is (not (contains? (first (:entries (authorities/read-pool :rdfxml mads-person))) :dates)))))

(def ^:private eac-person
  (str "<eac-cpf xmlns=\"urn:isbn:1-931666-33-4\"><control><recordId>dumas</recordId></control>"
       "<cpfDescription><identity><entityType>person</entityType>"
       "<nameEntry><part>Dumas, Alexandre</part><authorizedForm>AFNOR</authorizedForm></nameEntry></identity>"
       "<description>EXIST<biogHist><p>Romancier.</p></biogHist></description></cpfDescription></eac-cpf>"))

(deftest an-eac-cpf-record-reads-its-exist-dates
  (testing "a dateRange: the from date is the birth, the to date the death; @standardDate wins over the text"
    (let [xml (str/replace eac-person "EXIST" (str "<existDates><dateRange><fromDate standardDate=\"1802-07-24\">24 juillet 1802</fromDate>"
                                                   "<toDate>1870</toDate></dateRange></existDates>"))
          {:keys [entries unread]} (authorities/read-pool :eac-cpf xml)]
      (is (= {:text "24 juillet 1802 1870" :birth {:from 1802 :to 1802 :text "1802-07-24"} :death {:from 1870 :to 1870 :text "1870"}}
             (:dates (first entries))))
      (is (= ["biogHist"] unread) "existDates is read now: no longer listed as unread")))
  (testing "a single date written as a span"
    (let [xml (str/replace eac-person "EXIST" "<existDates><date>1802-1870</date></existDates>")]
      (is (= dumas-pere-life (:dates (first (:entries (authorities/read-pool :eac-cpf xml))))))))
  (testing "dates the parser cannot read are kept as text, contribute nothing, and stay listed as unread"
    (let [xml (str/replace eac-person "EXIST" "<existDates><date>vers 1800, après 1850</date></existDates>")
          {:keys [entries unread]} (authorities/read-pool :eac-cpf xml)]
      (is (= {:text "vers 1800, après 1850"} (:dates (first entries))))
      (is (= ["existDates" "biogHist"] unread)))))

(defn- eac-dates [exist]
  (let [{:keys [entries unread]} (authorities/read-pool :eac-cpf (str/replace eac-person "EXIST" exist))]
    [(:dates (first entries)) unread]))

(deftest eac-cpf-dates-are-never-narrower-than-the-source
  (testing "notBefore / notAfter bound the interval"
    (is (= {:from 1480 :to 1510 :text "1500"}
           (:birth (first (eac-dates "<existDates><dateRange><fromDate standardDate=\"1500\" notBefore=\"1480\" notAfter=\"1510\"/><toDate>1560</toDate></dateRange></existDates>")))))
    (is (= {:from 1480 :to 1520}
           (select-keys (:birth (first (eac-dates "<existDates><dateRange><fromDate notBefore=\"1480\" notAfter=\"1510\">vers 1500</fromDate><toDate>1560</toDate></dateRange></existDates>")))
                        [:from :to]))
        "the text's approximation (1480-1520) is wider than the bounds: never narrower than either"))
  (testing "an approximate text keeps its approximation when @standardDate is exact"
    (is (= {:from 1782 :to 1822 :text "1802" :approximate? true}
           (:birth (first (eac-dates "<existDates><dateRange><fromDate standardDate=\"1802\">ca. 1802</fromDate><toDate>1870</toDate></dateRange></existDates>")))))
    (is (= {:from 1480 :to 1520 :text "1500" :approximate? true}
           (:birth (first (eac-dates (str "<existDates><dateRange><fromDate standardDate=\"1500\" notBefore=\"1480\" notAfter=\"1510\">ca. 1500</fromDate>"
                                          "<toDate>1560</toDate></dateRange></existDates>")))))
        "the approximation and the bounds, whichever is wider"))
  (testing "a dateSet's members are alternatives: each role is the interval holding them all"
    (let [[d unread] (eac-dates (str "<existDates><dateSet><dateRange><fromDate standardDate=\"1802\"/><toDate standardDate=\"1870\"/></dateRange>"
                                     "<dateRange><fromDate>1803</fromDate><toDate>1871</toDate></dateRange></dateSet></existDates>"))]
      (is (= {:birth [1802 1803] :death [1870 1871]} (into {} (for [r [:birth :death]] [r [(:from (r d)) (:to (r d))]]))))
      (is (= ["biogHist"] unread)))
    (testing "a member that states no birth leaves the birth open: nothing is read, existDates stays unread"
      (let [[d unread] (eac-dates "<existDates><dateSet><dateRange><fromDate standardDate=\"1802\"/><toDate standardDate=\"1870\"/></dateRange><date>1803</date></dateSet></existDates>")]
        (is (not (dates/dated? d)))
        (is (= ["existDates" "biogHist"] unread))))))

(def ^:private bnf-authorities (delay (slurp "test/fixtures/er-gold/bnf-persons/authorities.xml")))

(deftest an-intermarc-authority-record-is-an-entry
  (let [{:keys [entries unread]} (authorities/read-pool :intermarc @bnf-authorities)
        by-n (into {} (map (juxt :record-number identity)) entries)]
    (is (= :intermarc (authorities/sniff-format "authorities.xml" @bnf-authorities)))
    (is (= 33 (count entries)))
    (testing "Dumas père: the 100 heading, the 400 variants, the 100 $d dates, the ISNI, the kind"
      (is (= {:id            "http://catalogue.bnf.fr/ark:/12148/cb119010630"
              :record-number "11901063"
              :label         "Dumas, Alexandre"
              :variants      ["Dumas, Alejandro" "Dumas père, Alexandre" "Aramis" "Davy de La Pailleterie, Alexandre"
                              "Da Zhong ma" "大仲馬"]
              :dates         dumas-pere-life
              :entity-type   "person"
              :isni          ["0000000121012885"]
              :relations     [{:record-number "16258491" :tag "320" :kind :agent}
                              {:record-number "12306195" :tag "320" :kind :agent}
                              {:record-number "11901062" :tag "320" :kind :agent}]}
             (by-n "11901063"))))
    (testing "an ISNI the record cancels ($z) is not the ISNI; a VIAF number is none"
      (is (= ["0000000444583295"] (:isni (by-n "11901062"))))
      (is (nil? (:isni (by-n "11105601")))))
    (testing "every ISNI the record states, in its order (lot N6): two 031 $a"
      (let [x   @bnf-authorities
            f31 "<mxc:subfield code=\"a\">0000000121012885</mxc:subfield>"
            x'  (str/replace-first x f31 (str "<mxc:subfield code=\"a\">0000000121012885</mxc:subfield><mxc:subfield code=\"2\">ISNI</mxc:subfield></mxc:datafield>"
                                              "<mxc:datafield tag=\"031\" ind1=\" \" ind2=\" \"><mxc:subfield code=\"z\">0000000444583295</mxc:subfield><mxc:subfield code=\"2\">ISNI</mxc:subfield></mxc:datafield>"
                                              "<mxc:datafield tag=\"031\" ind1=\" \" ind2=\" \"><mxc:subfield code=\"a\">0000000368528808</mxc:subfield>"))
            e   (some #(when (= "11901063" (:record-number %)) %) (:entries (authorities/read-pool :intermarc x')))]
        (is (str/includes? x f31))
        (is (= ["0000000121012885" "0000000368528808"] (:isni e)) "the cancelled one ($z) is still skipped")))
    (testing "a death left open is no death"
      (is (= #{:text :birth} (set (keys (:dates (first (filter #(= "1933-...." (get-in % [:dates :text])) entries))))))))
    (testing "what it does not read is listed"
      (is (some #{"600"} unread))
      (is (not-any? #{"001" "003" "031" "100" "400"} unread)))
    (testing "its dates block a homonym: the son's mention is proposed the son first, and the father is rejected"
      (let [{:keys [proposals rejected]} (reconcile/agent-candidates [{:name "Dumas, Alexandre" :dates "1824-1895"}] entries)]
        (is (= "http://catalogue.bnf.fr/ark:/12148/cb11901062n" (:authority-id (first proposals))))
        (is (some #{"http://catalogue.bnf.fr/ark:/12148/cb119010630"} (map :authority-id rejected)))
        (is (every? #(= :incompatible-dates (:reason %)) rejected))))))

(deftest an-intermarc-authority-states-its-relations
  (let [{:keys [entries unread]} (authorities/read-pool :intermarc @bnf-authorities)
        by-n (into {} (map (juxt :record-number identity)) entries)]
    (testing "a 331 is another identity of the person (Hetzel's pseudonym Stahl, and back); a 5xx a related agent; a 321 a work"
      (is (= [{:record-number "16492882" :tag "331" :kind :pseudonym :target :pseudonym} {:record-number "15072723" :tag "511" :kind :agent}]
             (:relations (by-n "12008284"))) "a 331 of second indicator 1 names a pseudonym of the record (lot N6)")
      (is (= [{:record-number "12008284" :tag "331" :kind :pseudonym :target :real-identity}] (:relations (by-n "16492882")))
          "of second indicator 2, the record's real identity")
      (is (= {:record-number "18021044" :tag "321" :kind :work}
             (first (filter #(= "321" (:tag %)) (:relations (by-n "11928016")))))))
    (testing "a 320 links an agent only with its relation code: Verne's museum, holding his manuscripts, is none"
      (is (not-any? #(= "17855010" (:record-number %)) (:relations (by-n "11928016"))))
      (is (= ["11901063"] (map :record-number (filter #(= "320" (:tag %)) (:relations (by-n "11901062")))))
          "the Académie française (no $3) links nothing; the father, with code 6, does"))
    (testing "a relation field read only in part (no $3, no relation code) stays unread; the others are read"
      (is (some #{"320"} unread))
      (is (not-any? #{"321" "331" "504" "511"} unread)))
    (testing "an entry that states none has no :relations"
      (is (not (contains? (by-n "11902539") :relations))))))

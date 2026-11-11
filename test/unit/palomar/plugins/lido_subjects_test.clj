(ns palomar.plugins.lido-subjects-test
  "The persons and places a work represents (lot N7, ADR 0056 amendment):
   read like its concepts, with the type of their subject set, and decided by
   the same rules: depicted (`P62`, `represents`) when described or
   identified, else what the visual item is about (`P65` → `E36` → `P129`,
   `about`). Never `P62` by code."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.convert :as convert]
            [palomar.fidelity :as fidelity]
            [palomar.plugins.lrmoo.ntriples :as nt]))

(defn- subject-record
  "A painting, produced, with one subject set typed `type` (or not) holding
   `subjects` (LIDO XML), its record id `rid`."
  ([type subjects] (subject-record "S-1" type subjects))
  ([rid type subjects]
   (str "<lido:lido xmlns:lido=\"http://www.lido-schema.org\" xmlns:gml=\"http://www.opengis.net/gml\"><lido:lidoRecID>" rid "</lido:lidoRecID>"
        "<lido:descriptiveMetadata xml:lang=\"en\">"
        "<lido:objectClassificationWrap><lido:objectWorkTypeWrap><lido:objectWorkType><lido:term>painting</lido:term></lido:objectWorkType></lido:objectWorkTypeWrap></lido:objectClassificationWrap>"
        "<lido:objectIdentificationWrap><lido:titleWrap><lido:titleSet><lido:appellationValue>T</lido:appellationValue></lido:titleSet></lido:titleWrap></lido:objectIdentificationWrap>"
        "<lido:eventWrap><lido:eventSet><lido:event><lido:eventType><lido:conceptID>http://terminology.lido-schema.org/lido00007</lido:conceptID></lido:eventType>"
        "<lido:eventDate><lido:displayDate>1800</lido:displayDate></lido:eventDate></lido:event></lido:eventSet></lido:eventWrap>"
        "<lido:objectRelationWrap><lido:subjectWrap><lido:subjectSet><lido:subject" (when type (str " lido:type=\"" type "\"")) ">"
        (apply str subjects)
        "</lido:subject></lido:subjectSet></lido:subjectWrap></lido:objectRelationWrap></lido:descriptiveMetadata></lido:lido>")))

(defn- actor
  "A subjectActor named `nm`, of LIDO type `t`, with `ids` (`[source code]` or an IRI)."
  [nm t & ids]
  (str "<lido:subjectActor><lido:displayActor>" nm "</lido:displayActor><lido:actor" (when t (str " lido:type=\"" t "\"")) ">"
       (apply str (for [i ids] (if (vector? i)
                                 (str "<lido:actorID lido:source=\"" (first i) "\">" (second i) "</lido:actorID>")
                                 (str "<lido:actorID>" i "</lido:actorID>"))))
       "<lido:nameActorSet><lido:appellationValue>" nm "</lido:appellationValue></lido:nameActorSet>"
       "<lido:vitalDatesActor><lido:earliestDate>1757</lido:earliestDate><lido:latestDate>1827</lido:latestDate></lido:vitalDatesActor>"
       "</lido:actor></lido:subjectActor>"))

(defn- place
  "A subjectPlace named `nm`, with `ids` (`[source code]` or an IRI) and, if
   given, gml `coords`."
  [nm coords & ids]
  (str "<lido:subjectPlace><lido:displayPlace>" nm "</lido:displayPlace><lido:place lido:geographicalEntity=\"geographic location\">"
       (apply str (for [i ids] (if (vector? i)
                                 (str "<lido:placeID lido:source=\"" (first i) "\">" (second i) "</lido:placeID>")
                                 (str "<lido:placeID>" i "</lido:placeID>"))))
       "<lido:namePlaceSet><lido:appellationValue>" nm "</lido:appellationValue></lido:namePlaceSet>"
       (when coords (str "<lido:gml><gml:Point><gml:coordinates>" coords "</gml:coordinates></gml:Point></lido:gml>"))
       "</lido:place></lido:subjectPlace>"))

(defn- wemi [xml] (convert/to-wemi :lido {} xml))
(defn- record [xml] (first (:records (wemi xml))))
(defn- fields [r p] (keep #(when (= p (:predicate %)) (:value/fields (:value %))) (:assertions r)))

(defn- said
  "`#{[predicate kind label]}`: what the CRM view says the object depicts
   (`P62`) or its visual item is about (`P129`), with the node's class and name."
  [r]
  (let [as    (:assertions r)
        kinds (into {} (map (juxt :id :kind)) (:entities r))
        label (into {} (for [a as :when (= :crm/P190_has_symbolic_content (:predicate a))] [(:subject a) (:value a)]))]
    (set (for [a as
               :when (and (#{:crm/P62_depicts :crm/P129_is_about} (:predicate a)) (= :asserted (:status a)))
               :let [t (get-in a [:value :value/target])]]
           [(:predicate a) (kinds t) (label t)]))))

(defn- docs [from opts to src]
  (map json/read-str (remove str/blank? (str/split-lines (:output (convert/convert {:from from :opts opts :to to :source src}))))))

(defn- visual [xml] (first (filter #(= "VisualItem" (get % "type")) (docs :lido {} :linked-art xml))))

(deftest a-represented-person-and-place-are-read
  (testing "each subjectActor and subjectPlace with its ids, its type and its subject's type (N7)"
    (let [r  (record (subject-record "description" [(actor "Blake, William" "constituent" ["ULAN" "500012489"] ["YCBA" "ycba_term_1"])
                                                    (place "Dorset" "50.7830, -2.3160" ["TGN" "7011961"] ["YCBA" "ycba_term_2"])]))
          [p] (fields r :lido/subject-person)
          [l] (fields r :lido/subject-place)]
      (is (= {:name "Blake, William" :type "constituent" :uri "http://vocab.getty.edu/ulan/500012489" :subject-type "description"
              :birth "1757" :death "1827"}
             p) "an id by source and code is its authority's IRI (K1)")
      (is (= {:name "Dorset" :uri "http://vocab.getty.edu/tgn/7011961" :subject-type "description" :coordinates "50.7830, -2.3160"} l))
      (testing "and nothing of them is left unread"
        (is (empty? (filter #(re-find #"(?i)place|gml|coordinates|actor" (str (get-in % [:detail :loss/source-field])))
                            (filter #(= :import (get-in % [:detail :loss/edge])) (:ingest (wemi (subject-record "description" [(place "Dorset" "50.7830, -2.3160" ["TGN" "7011961"])])))))))))))

(deftest a-represented-person-is-decided-by-the-rules
  (testing "described or identified, or of no type (a named individual, LIDO's Identification): depicted (P62); interpreted: what the visual item is about (P129)"
    (is (= #{[:crm/P62_depicts :crm/E21_Person "Blake"]} (said (record (subject-record "description" [(actor "Blake" nil "http://vocab.getty.edu/ulan/500012489")])))))
    (is (= #{[:crm/P62_depicts :crm/E21_Person "Blake"]} (said (record (subject-record "identification" [(actor "Blake" nil)])))))
    (is (= #{[:crm/P129_is_about :crm/E21_Person "Blake"]} (said (record (subject-record "interpretation" [(actor "Blake" nil)])))))
    (is (= #{[:crm/P62_depicts :crm/E21_Person "Janauschek"]} (said (record (subject-record nil [(actor "Janauschek" "person" "http://d-nb.info/gnd/117077879")]))))
        "of no type, a named person is depicted, whatever names it (N7 review)")
    (is (= #{[:crm/P62_depicts :crm/E53_Place "Olympia"]} (said (record (subject-record nil [(place "Olympia" nil "https://d-nb.info/gnd/4043508-8")])))))
    (is (= #{[:crm/P129_is_about :crm/E53_Place "Olympia"]} (said (record (subject-record "interpretation" [(place "Olympia" nil "https://d-nb.info/gnd/4043508-8")]))))
        "typed an interpretation, still what it is about"))
  (testing "a group is a group; a place a place"
    (is (= #{[:crm/P62_depicts :crm/E74_Group "Guild"]} (said (record (subject-record "description" [(actor "Guild" "organization")])))))
    (is (= #{[:crm/P62_depicts :crm/E53_Place "Dorset"]} (said (record (subject-record "description" [(place "Dorset" nil ["TGN" "7011961"])])))))
    (is (= #{[:crm/P129_is_about :crm/E53_Place "Arkadia"]} (said (record (subject-record "interpretation" [(place "Arkadia" nil)]))))))
  (testing "an object not said to be made depicts nothing, and that is reported"
    (let [xml (-> (subject-record "description" [(actor "Blake" nil) (place "Dorset" nil)])
                  (str/replace #"<lido:objectClassificationWrap>.*</lido:objectClassificationWrap>" "")
                  (str/replace #"<lido:eventWrap>.*</lido:eventWrap>" ""))
          {:keys [records]} (wemi xml)]
      (is (empty? (said (first records))))
      (is (= #{:lido/subject-person :lido/subject-place}
             (set (keep #(let [d (:detail %)] (when (= :projection (:loss/edge d)) (#{:lido/subject-person :lido/subject-place} (:loss/source-field d))))
                        (mapcat :diagnostics records))))))))

(deftest a-represented-person-s-identity
  (testing "an authority's IRI names the node; else a node of the record, never one of another record by its name"
    (let [ts  (fn [xml] (nt/parse (:output (convert/convert {:from :lido :to :ntriples :source xml}))))
          dep (fn [xml] (set (keep (fn [[_ p o]] (when (str/ends-with? (:iri p) "P62_depicts") (:iri o))) (ts xml))))]
      (is (= #{"http://vocab.getty.edu/ulan/500012489"} (dep (subject-record "description" [(actor "Blake" nil ["ULAN" "500012489"])]))))
      (is (= #{"http://vocab.getty.edu/tgn/7011961"} (dep (subject-record "description" [(place "Dorset" nil ["TGN" "7011961"])]))))
      (let [a (dep (subject-record "A-1" "description" [(actor "John Smith" nil)]))
            b (dep (subject-record "B-1" "description" [(actor "John Smith" nil)]))]
        (is (= 1 (count a) (count b)))
        (is (not= a b) "two records' John Smiths are two"))
      (is (= 1 (count (dep (subject-record "description" [(actor "John Smith" nil) (actor "John Smith" nil)]))))
          "in one record, one name is one node, as a local concept is"))))

(deftest a-represented-person-in-each-export
  (let [xml (subject-record "description" [(actor "Blake" nil ["ULAN" "500012489"]) (place "Dorset" "50.7830, -2.3160" ["TGN" "7011961"])
                                           (actor "John Smith" nil)])]
    (testing "Linked Art: the VisualItem represents them, as the CRM view says; the local person by its CRM node"
      (let [vi  (visual xml)
            crm (some (fn [[_ p o]] (when (and (str/ends-with? (:iri p) "P62_depicts") (not (str/starts-with? (:iri o) "http"))) (:iri o)))
                      (nt/parse (:output (convert/convert {:from :lido :to :ntriples :source xml}))))]
        (is (= #{["http://vocab.getty.edu/ulan/500012489" "Person" "Blake"] ["http://vocab.getty.edu/tgn/7011961" "Place" "Dorset"] [crm "Person" "John Smith"]}
               (set (map (juxt #(get % "id") #(get % "type") #(get % "_label")) (get vi "represents")))))
        (is (nil? (get vi "about"))))
      (let [vi (visual (subject-record "interpretation" [(actor "Blake" nil ["ULAN" "500012489"]) (place "Arkadia" nil)]))]
        (is (= #{"Blake" "Arkadia"} (set (map #(get % "_label") (get vi "about")))))
        (is (nil? (get vi "represents")))))
    (testing "MARC21: a person in 600, a group in 610, a place in 651, with their authority"
      (let [m (:output (convert/convert {:from :lido :to :marc21-object :source xml}))
            g (:output (convert/convert {:from :lido :to :marc21-object :source (subject-record "description" [(actor "Guild" "organization")])}))]
        (is (re-find #"tag=\"600\" ind1=\"0\" ind2=\"7\">\s*<subfield code=\"a\">Blake</subfield>\s*<subfield code=\"d\">1757-1827</subfield>\s*<subfield code=\"0\">http://vocab.getty.edu/ulan/500012489</subfield>\s*<subfield code=\"2\">ulan</subfield>" m) m)
        (is (re-find #"tag=\"651\" ind1=\" \" ind2=\"7\">\s*<subfield code=\"a\">Dorset</subfield>\s*<subfield code=\"0\">http://vocab.getty.edu/tgn/7011961</subfield>\s*<subfield code=\"2\">tgn</subfield>" m) m)
        (is (re-find #"tag=\"610\" ind1=\"2\"" g) g)))
    (testing "Dublin Core, whose floor has no subject, reports them"
      (let [{:keys [loss]} (convert/export-records :dc (:records (wemi xml)))]
        (is (= #{:lido/subject-person :lido/subject-place}
               (set (keep #(#{:lido/subject-person :lido/subject-place} (get-in % [:detail :loss/source-field])) loss))))))
    (testing "coordinates no view carries are reported, in the CRM view and in Linked Art"
      (let [r (record xml)]
        (is (some #(and (= :lido/subject-place (get-in % [:detail :loss/source-field])) (= :projection (get-in % [:detail :loss/edge]))
                        (str/includes? (str (:message %)) "50.7830, -2.3160"))
                  (:diagnostics r)))
        (is (some #(and (= :lido/subject-place (get-in % [:detail :loss/source-field])) (some #{:coordinates} (get-in % [:detail :loss/fields])))
                  (:loss (convert/export-records :linked-art [r]))))))
    (testing "every value is written or reported, on every target"
      (doseq [to [:ntriples :crm :linked-art :dc :marc21-object]]
        (is (empty? (:unaccounted (fidelity/audit {:from :lido :to to :source xml}))) (str to))))))

(deftest real-records-represent-their-persons-and-places
  (testing "YCBA types every subject set a description: its persons and places are represented (N7)"
    (let [la  (docs :oai {:metadata-prefix "lido"} :linked-art (slurp "test/fixtures/documentary/lido/ycba-oai-lido.xml"))
          rep (set (for [v la :when (= "VisualItem" (get v "type")) s (get v "represents") :when (#{"Person" "Place"} (get s "type"))]
                     [(get s "type") (get s "id")]))]
      (is (contains? rep ["Person" "http://vocab.getty.edu/ulan/500012489"]) "William Blake, by ULAN")
      (is (contains? rep ["Person" "https://doi.org/10.1093/ref:odnb/37150"]) "David Barclay, by the ODNB")
      (is (contains? rep ["Place" "http://vocab.getty.edu/tgn/7011543"]) "the Stour, by TGN")
      (is (= 7 (count rep)) "the 2 persons and 5 distinct places of the fixture")
      (is (not-any? #(and (= "VisualItem" (get % "type")) (some (fn [s] (#{"Person" "Place"} (get s "type"))) (get % "about"))) la))))
  (testing "Städel types no subject set: its named persons are depicted (N7 review)"
    (let [vis (fn [f] (filter #(= "VisualItem" (get % "type")) (docs :oai {:metadata-prefix "lido"} :linked-art (slurp (str "test/fixtures/documentary/lido/" f)))))
          rep (fn [f] (set (for [v (vis f) s (get v "represents") :when (#{"Person" "Place"} (get s "type"))] (get s "_label"))))]
      (is (contains? (rep "staedel-oai-lido-sample.xml") "Janauschek, Fanny"))
      (is (every? (rep "staedel-oai-lido-graphics.xml") ["Bottom, Nick" "Pyramus" "Nemesis"]))
      (is (contains? (rep "museum-digital-westfalen-lido.xml") "Olympia (Griechenland)"))
      (testing "but a GND subject heading filed as a place (Berg, Gebäude) is the concept the record names by it: one IRI, one class"
        (is (not-any? (rep "staedel-oai-lido-sample.xml") ["mountain" "Berg" "Gebäude" "building"]))
        (is (not-any? #(= "Place" (get % "type"))
                      (for [v (vis "staedel-oai-lido-sample.xml") k ["represents" "about"] s (get v k)
                            :when (re-find #"4144619-7|4156127-2" (get s "id"))] s))))))
  (testing "museum-digital: a place by its GND, its point read"
    (let [r  (filter #(seq (fields % :lido/subject-place)) (:records (convert/to-wemi :oai {:metadata-prefix "lido"} (slurp "test/fixtures/documentary/lido/museum-digital-westfalen-lido.xml"))))
          ps (set (mapcat #(fields % :lido/subject-place) r))]
      (is (some #(= "https://d-nb.info/gnd/4000554-9" (:uri %)) ps))
      (is (some #(= "37.725399017334 23.489599227905" (:coordinates %)) ps) "gml:pos is read too")))
  (testing "and every value of these records is written or reported, whatever the target"
    (doseq [f ["test/fixtures/documentary/lido/ycba-oai-lido.xml" "test/fixtures/documentary/lido/museum-digital-westfalen-lido.xml"]
            to [:ntriples :linked-art :dc :marc21-object :crm]]
      (is (empty? (:unaccounted (fidelity/audit {:from :oai :opts {:metadata-prefix "lido"} :to to :source (slurp f)}))) (str f " " to)))))

(deftest no-person-is-depicted-by-code
  (testing "the projection's code states no P62: every depiction is a rule's, which cites the rule"
    (let [r (record (subject-record nil [(actor "Blake" nil)]))]
      (is (not-any? #(and (= :crm/P62_depicts (:predicate %)) (= :rule.lido/project (get-in % [:provenance :rule]))) (:assertions r)))
      (is (every? #(= "rule.lido.subject" (namespace (get-in % [:provenance :rule])))
                  (filter #(#{:crm/P62_depicts :crm/P129_is_about :crm/P65_shows_visual_item} (:predicate %)) (:assertions r)))))))

;; --- the review of N7 ----------------------------------------------------------

(defn- typed-actor
  "A subjectActor named `nm` whose ids are `[[type text]…]` (`lido:type` of the id)."
  [nm & ids]
  (str "<lido:subjectActor><lido:actor>"
       (apply str (for [[t v] ids] (str "<lido:actorID lido:type=\"" t "\">" v "</lido:actorID>")))
       "<lido:nameActorSet><lido:appellationValue>" nm "</lido:appellationValue></lido:nameActorSet></lido:actor></lido:subjectActor>"))

(defn- concept [label & ids]
  (str "<lido:subjectConcept>" (apply str (for [i ids] (str "<lido:conceptID>" i "</lido:conceptID>"))) "<lido:term>" label "</lido:term></lido:subjectConcept>"))

(defn- node-iri [xml p label]
  (let [r (record xml)]
    (:uri (first (filter #(= label (:name %)) (fields r p))))))

(deftest an-id-typed-about-outweighs-its-set
  (testing "YCBA types Shakespeare's id subjectActorAbout in a set typed description: what the work is about (subject-id-types.edn)"
    (let [xml (subject-record "description" [(typed-actor "Shakespeare, William" ["subjectActorAbout" "http://vocab.getty.edu/ulan/500272240"])
                                             (typed-actor "Blake, William" ["subjectActor" "http://vocab.getty.edu/ulan/500012489"])])]
      (is (= #{[:crm/P129_is_about :crm/E21_Person "Shakespeare, William"] [:crm/P62_depicts :crm/E21_Person "Blake, William"]} (said (record xml))))
      (is (= ["Shakespeare, William"] (map #(get % "_label") (get (visual xml) "about")))))))

(deftest the-lido-terminology-names-a-subject-s-type
  (testing "lido00525 Description, lido00136 Identification, lido00745 Iconclass notation: depicted; lido00524 Interpretation: about"
    (doseq [[t p] [["http://terminology.lido-schema.org/lido00525" :crm/P62_depicts]
                   ["http://terminology.lido-schema.org/lido00136" :crm/P62_depicts]
                   ["http://terminology.lido-schema.org/lido00745" :crm/P62_depicts]
                   ["http://terminology.lido-schema.org/subject_type/description" :crm/P62_depicts]
                   ["Description" :crm/P62_depicts]
                   ["http://terminology.lido-schema.org/lido00524" :crm/P129_is_about]
                   ["http://terminology.lido-schema.org/subject_type/interpretation" :crm/P129_is_about]]]
      (is (= #{[p :crm/E55_Type "dog"]} (said (record (subject-record t [(concept "dog" "http://vocab.getty.edu/aat/300249395")])))) t))))

(deftest every-id-of-a-subject-is-read
  (testing "a concept of no type named by Iconclass in its second id is depicted"
    (is (= #{[:crm/P62_depicts :crm/E55_Type "Zeus"]}
           (said (record (subject-record nil [(concept "Zeus" "http://d-nb.info/gnd/118772635" "http://iconclass.org/92B1")]))))))
  (testing "a person's or a place's IRI is chosen by the sources' order (ULAN, GND, TGN, Wikidata, LoC, VIAF), never alphabetically"
    (is (= "https://d-nb.info/gnd/118540238"
           (node-iri (subject-record nil [(actor "Goethe" nil "https://term.museum-digital.de/md-de/persinst/1" "http://viaf.org/viaf/24602065" "https://d-nb.info/gnd/118540238")])
                     :lido/subject-person "Goethe")))
    (is (= "http://vocab.getty.edu/tgn/7000874"
           (node-iri (subject-record nil [(place "Rom" nil "https://www.wikidata.org/wiki/Q220" ["TGN" "7000874"])]) :lido/subject-place "Rom"))))
  (testing "never an Iconclass notation, a catalogue's page or a Wikipedia article: reported, the node the record's"
    (let [xml (subject-record nil [(actor "Zeus" nil "https://catalogue.bnf.fr/ark:/12148/cb119505426" "http://iconclass.org/92B1")
                                   (place "Place des Vosges" nil "https://en.wikipedia.org/wiki/Place_des_Vosges")])
          r   (record xml)]
      (is (nil? (node-iri xml :lido/subject-person "Zeus")))
      (is (nil? (node-iri xml :lido/subject-place "Place des Vosges")))
      (is (= #{[:crm/P62_depicts :crm/E21_Person "Zeus"] [:crm/P62_depicts :crm/E53_Place "Place des Vosges"]} (said r)))
      (is (= 3 (count (filter #(re-find #"is (an Iconclass notation|a page of the BnF catalogue|a Wikipedia article)" (str (:message %))) (:diagnostics r)))))
      (is (empty? (:unaccounted (fidelity/audit {:from :lido :to :linked-art :source xml}))))))
  (testing "a Wikidata page is its entity; an Iconclass notation with a space is an IRI"
    (is (= "http://www.wikidata.org/entity/Q90" (node-iri (subject-record nil [(place "Paris" nil "https://www.wikidata.org/wiki/Q90")]) :lido/subject-place "Paris")))
    (is (= "http://www.wikidata.org/entity/Q90" (node-iri (subject-record nil [(place "Paris" nil "https://www.wikidata.org/entity/Q90")]) :lido/subject-place "Paris")))
    (is (= "http://iconclass.org/61BB2(JANAUSCHEK,%20Fanny)1"
           (:uri (first (fields (record (subject-record nil [(concept "Janauschek portrayed" "http://iconclass.org/61BB2(JANAUSCHEK, Fanny)1")])) :lido/subject-concept)))))))

(deftest a-person-named-only-as-displayed-is-the-record-s
  (let [xml (subject-record "description" ["<lido:subjectActor><lido:displayActor>Ein Mann</lido:displayActor></lido:subjectActor>"])]
    (is (= #{[:crm/P62_depicts :crm/E21_Person "Ein Mann"]} (said (record xml))))
    (is (empty? (:unaccounted (fidelity/audit {:from :lido :to :ntriples :source xml}))))))

(deftest one-node-depicted-and-about-is-written-in-both
  (testing "two subject sets, one depicts Blake, one interprets him: Linked Art says both, as the CRM view does"
    (let [xml (str/replace (subject-record "description" [(actor "Blake" nil ["ULAN" "500012489"])])
                           "</lido:subjectSet>"
                           (str "</lido:subjectSet><lido:subjectSet><lido:subject lido:type=\"interpretation\">" (actor "Blake" nil ["ULAN" "500012489"])
                                "</lido:subject></lido:subjectSet>"))
          vi  (visual xml)]
      (is (= #{[:crm/P62_depicts :crm/E21_Person "Blake"] [:crm/P129_is_about :crm/E21_Person "Blake"]} (said (record xml))))
      (is (= ["http://vocab.getty.edu/ulan/500012489"] (map #(get % "id") (get vi "represents"))))
      (is (= ["http://vocab.getty.edu/ulan/500012489"] (map #(get % "id") (get vi "about")))))))

(def ^:private fixtures
  [[:lido "staedel-lido-wrap.xml"] [:oai "staedel-oai-lido-graphics.xml"] [:oai "staedel-oai-lido-sample.xml"]
   [:oai "museum-digital-hessen-lido.xml"] [:oai "museum-digital-westfalen-lido.xml"] [:oai "ycba-oai-lido.xml"]])

(deftest one-iri-is-one-class
  (testing "on every LIDO fixture, no IRI of a record is two of a type, a place, a person and a group; Linked Art lists an id once"
    (doseq [[from f] fixtures
            r (:records (convert/to-wemi from {:metadata-prefix "lido"} (slurp (str "test/fixtures/documentary/lido/" f))))
            :let [nt  (nt/parse (:output (convert/export-records :ntriples [r])))
                  cls #{"E55_Type" "E53_Place" "E21_Person" "E74_Group"}
                  ts  (reduce (fn [m [s p o]] (if (and (str/ends-with? (:iri p) "#type") (cls (str/replace (str (:iri o)) #"^.*/" "")))
                                                (update m (:iri s) (fnil conj #{}) (:iri o)) m))
                              {} nt)
                  la  (map json/read-str (remove str/blank? (str/split-lines (str (:output (convert/export-records :linked-art [r]))))))]]
      (is (empty? (filter #(< 1 (count (val %))) ts)) (str f " " (:id r)))
      (doseq [v la k ["represents" "about"] :let [ids (map #(get % "id") (get v k))]]
        (is (= (count ids) (count (distinct ids))) (str f " " k))))))

(deftest one-place-of-two-names-is-written-once
  (testing "Wales and Cymru, one TGN place: one reference, under the first name, the other reported"
    (let [xml (subject-record "description" [(place "Wales" nil ["TGN" "7002443"]) (place "Cymru" nil ["TGN" "7002443"])])]
      (is (= [["http://vocab.getty.edu/tgn/7002443" "Wales"]] (map (juxt #(get % "id") #(get % "_label")) (get (visual xml) "represents"))))
      (is (empty? (:unaccounted (fidelity/audit {:from :lido :to :linked-art :source xml})))))))

(deftest marc-names-a-person-by-its-heading-source
  (let [m (:output (convert/convert {:from :lido :to :marc21-object
                                     :source (subject-record "description" [(actor "Twain, Mark" nil "http://id.loc.gov/authorities/names/n79021164")
                                                                            (concept "dog" "http://iconclass.org/34B11")])}))]
    (is (re-find #"tag=\"600\" ind1=\"1\" ind2=\"0\"><subfield code=\"a\">Twain, Mark</subfield>.*<subfield code=\"0\">http://id.loc.gov/authorities/names/n79021164</subfield></datafield>" m)
        "an LC name is a 600's heading, ind2 0, no $2")
    (is (re-find #"tag=\"084\" ind1=\" \" ind2=\" \"><subfield code=\"a\">34B11</subfield><subfield code=\"2\">iconclass</subfield>" m)
        "an Iconclass notation is a classification of source iconclass")))

(ns palomar.plugins.lido-test
  "LIDO museum objects (ADR 0052): events keep their own parts, LIDO 1.1
   terminology is read, keepers keep their own ids, and values that are no
   number or no four-digit year do not break the export."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.convert :as convert]
            [palomar.fidelity :as fidelity]
            [palomar.plugins.lido :as lido]
            [palomar.ontology :as onto]
            [palomar.plugins.lrmoo.export :as export]
            [palomar.plugins.lrmoo.ntriples :as nt]
            [palomar.validate :as validate]))

(def ^:private lido-1-1
  "A LIDO 1.1 record: an eventSet with no event, a production typed by its
   1.1 code with a German term only, an exhibition, two repositories (the
   first with no id), and measurements that are no number."
  (str "<lido:lidoWrap xmlns:lido=\"http://www.lido-schema.org\"><lido:lido>"
       "<lido:lidoRecID lido:type=\"local\">T-1</lido:lidoRecID>"
       "<lido:descriptiveMetadata xml:lang=\"de\">"
       "<lido:objectClassificationWrap><lido:objectWorkTypeWrap><lido:objectWorkType><lido:term>Skulptur</lido:term></lido:objectWorkType></lido:objectWorkTypeWrap></lido:objectClassificationWrap>"
       "<lido:objectIdentificationWrap><lido:titleWrap><lido:titleSet><lido:appellationValue>Kopf</lido:appellationValue></lido:titleSet></lido:titleWrap>"
       "<lido:repositoryWrap>"
       "<lido:repositorySet><lido:repositoryName><lido:legalBodyName><lido:appellationValue>Former Owner Museum</lido:appellationValue></lido:legalBodyName></lido:repositoryName></lido:repositorySet>"
       "<lido:repositorySet><lido:repositoryName><lido:legalBodyID>http://ld.zdb-services.de/resource/organisations/DE-MUS-048017</lido:legalBodyID>"
       "<lido:legalBodyName><lido:appellationValue>Staedel</lido:appellationValue></lido:legalBodyName></lido:repositoryName></lido:repositorySet>"
       "</lido:repositoryWrap>"
       "<lido:objectMeasurementsWrap><lido:objectMeasurementsSet><lido:displayObjectMeasurements>ca. 5 cm</lido:displayObjectMeasurements><lido:objectMeasurements>"
       "<lido:measurementsSet><lido:measurementType>height</lido:measurementType><lido:measurementUnit>cm</lido:measurementUnit><lido:measurementValue>NaN</lido:measurementValue></lido:measurementsSet>"
       "<lido:measurementsSet><lido:measurementType>width</lido:measurementType><lido:measurementUnit>cm</lido:measurementUnit><lido:measurementValue>ca. 5</lido:measurementValue></lido:measurementsSet>"
       "</lido:objectMeasurements></lido:objectMeasurementsSet></lido:objectMeasurementsWrap>"
       "</lido:objectIdentificationWrap>"
       "<lido:eventWrap>"
       "<lido:eventSet><lido:displayEvent>a display only</lido:displayEvent></lido:eventSet>"
       "<lido:eventSet><lido:event><lido:eventType><lido:conceptID>http://terminology.lido-schema.org/lido00007</lido:conceptID><lido:term>Herstellung</lido:term></lido:eventType>"
       "<lido:eventActor><lido:actorInRole><lido:actor lido:type=\"http://terminology.lido-schema.org/lido00413\">"
       "<lido:actorID>http://d-nb.info/gnd/2000001-1</lido:actorID><lido:nameActorSet><lido:appellationValue>Werkstatt</lido:appellationValue></lido:nameActorSet></lido:actor></lido:actorInRole></lido:eventActor>"
       "<lido:eventDate><lido:displayDate>um 800</lido:displayDate><lido:date><lido:earliestDate>800</lido:earliestDate><lido:latestDate>850</lido:latestDate></lido:date></lido:eventDate>"
       "<lido:eventPlace><lido:place><lido:placeID>http://example.org/place/P</lido:placeID></lido:place></lido:eventPlace>"
       "</lido:event></lido:eventSet>"
       "<lido:eventSet><lido:event><lido:eventType><lido:conceptID>http://terminology.lido-schema.org/lido00225</lido:conceptID><lido:term>Ausstellung</lido:term></lido:eventType>"
       "<lido:eventPlace><lido:place><lido:placeID>http://example.org/place/EXH</lido:placeID></lido:place></lido:eventPlace>"
       "<lido:eventDescriptionSet><lido:descriptiveNoteValue>Exhibited in Paris</lido:descriptiveNoteValue></lido:eventDescriptionSet>"
       "</lido:event></lido:eventSet>"
       "</lido:eventWrap></lido:descriptiveMetadata></lido:lido></lido:lidoWrap>"))

(defn- nt [] (:output (convert/convert {:from :lido :to :ntriples :source lido-1-1})))
(defn- la [] (map json/read-str (str/split-lines (:output (convert/convert {:from :lido :to :linked-art :source lido-1-1})))))

(deftest each-event-keeps-its-own-parts
  (testing "the place of the production and of the exhibition, though an eventSet before them holds no event (post-audit 3, E5 review)"
    (let [hmo (first (la))]
      (is (= ["http://example.org/place/P"] (map #(get % "id") (get-in hmo ["produced_by" "took_place_at"]))))
      (is (not (str/includes? (json/write-str (get hmo "produced_by")) "EXH")))))
  (testing "in CIDOC CRM, the exhibition takes its own place and description"
    (let [out (nt)
          lines (str/split-lines out)
          ev (some #(second (re-find #"^(<[^>]+>) <[^>]+rdf-syntax-ns#type> <[^>]+E7_Activity>" %)) lines)]
      (is ev)
      (is (some #(and (str/starts-with? % ev) (str/includes? % "P7_took_place_at") (str/includes? % "place/EXH")) lines))
      (is (some #(and (str/starts-with? % ev) (str/includes? % "Exhibited in Paris")) lines))
      (is (not (some #(and (str/starts-with? % ev) (str/includes? % "place/P>")) lines))))))

(deftest lido-1-1-terminology-is-read
  (let [hmo (first (la))]
    (testing "a production typed by its LIDO 1.1 code, with a German term only, is a Production"
      (is (= "Production" (get-in hmo ["produced_by" "type"]))))
    (testing "an actor typed organization (lido00413) is a Group"
      (is (= "Group" (get-in hmo ["produced_by" "carried_out_by" 0 "type"]))))
    (testing "and its actor is the object's creator in Dublin Core"
      (is (str/includes? (:output (convert/convert {:from :lido :to :dc :source lido-1-1})) "<dc:creator>Werkstatt</dc:creator>")))))

(deftest a-keeper-keeps-its-own-id
  (let [hmo (first (la))]
    (is (= [{"id" "http://ld.zdb-services.de/resource/organisations/DE-MUS-048017" "type" "Group" "_label" "Staedel"}]
           (get hmo "current_custodian")))))

(deftest values-that-are-no-number-or-no-year-do-not-break-the-export
  (let [hmo (first (la))]
    (testing "NaN and \"ca. 5\" make no Dimension, and are reported"
      (is (nil? (get hmo "dimension")))
      (is (some #{:lido/dimension} (:source-fields (:loss (convert/convert {:from :lido :to :linked-art :source lido-1-1}))))))
    (testing "a three-digit year is a four-digit xsd:dateTime year"
      (is (= "0800-01-01T00:00:00Z" (get-in hmo ["produced_by" "timespan" "begin_of_the_begin"])))
      (is (= "0850-12-31T23:59:59Z" (get-in hmo ["produced_by" "timespan" "end_of_the_end"]))))
    (testing "and no concept with no URI leaves an empty classified_as"
      (is (not (contains? hmo "classified_as"))))))

(deftest every-value-is-written-or-reported
  (doseq [to [:linked-art :ntriples :crm :dc :marc21-object]]
    (is (empty? (:unaccounted (fidelity/audit {:from :lido :to to :source lido-1-1}))) (str to))))

(defn- one-event
  "A LIDO record with one event of type `code` (a LIDO 1.1 code), `term` its
   term (nil: none) and `actor` its actor's name (nil: none)."
  [code term actor]
  (str "<lido:lido xmlns:lido=\"http://www.lido-schema.org\"><lido:lidoRecID>E-" code "</lido:lidoRecID><lido:descriptiveMetadata>"
       "<lido:objectIdentificationWrap><lido:titleWrap><lido:titleSet><lido:appellationValue>T</lido:appellationValue></lido:titleSet></lido:titleWrap></lido:objectIdentificationWrap>"
       "<lido:eventWrap><lido:eventSet><lido:event><lido:eventType>"
       (when code (str "<lido:conceptID>http://terminology.lido-schema.org/" code "</lido:conceptID>"))
       (when term (str "<lido:term>" term "</lido:term>")) "</lido:eventType>"
       (when actor (str "<lido:eventActor><lido:actorInRole><lido:actor><lido:nameActorSet><lido:appellationValue>" actor
                        "</lido:appellationValue></lido:nameActorSet></lido:actor></lido:actorInRole></lido:eventActor>"))
       "</lido:event></lido:eventSet></lido:eventWrap></lido:descriptiveMetadata></lido:lido>"))

(def ^:private sch (delay (onto/schema onto/vendored)))

(defn- violations [xml]
  (let [out (:output (convert/convert {:from :lido :to :ntriples :source xml}))]
    (onto/violations @sch (for [[s p o] (nt/parse out)] [(nt/term-key s) (:iri p) o]))))

(deftest every-event-respects-cidoc-crm
  (testing "every event, with or without an actor, respects CIDOC CRM's domains (post-audit 4, F1)"
    (doseq [[code term] [["lido00225" "Ausstellung"] ["lido00003" nil] ["lido00034" "Restaurierung"] ["lido00226" nil]
                         ["lido00007" nil] ["lido00001" nil] ["lido00009" "Verlust"]]
            actor       [nil "Kurator"]]
      (is (empty? (violations (one-event code term actor))) (str code " " term " " actor))))
  (testing "an exhibition with a curator: E7_Activity carried out by them; with no one, no P14"
    (let [nt-out (:output (convert/convert {:from :lido :to :ntriples :source (one-event "lido00225" "Ausstellung" "Kurator")}))]
      (is (str/includes? nt-out "cidoc-crm/E7_Activity"))
      (is (str/includes? nt-out "P14_carried_out_by")))
    (let [nt-out (:output (convert/convert {:from :lido :to :ntriples :source (one-event "lido00009" nil nil)}))]
      (is (not (str/includes? nt-out "P14_carried_out_by"))))))

(deftest an-event-type-with-no-term-is-read
  (testing "an eventType that gives only a LIDO 1.1 code imports (post-audit 4, F1)"
    (is (= 1 (:records (convert/convert {:from :lido :to :ntriples :source (one-event "lido00007" nil "Maes")}))))
    (is (str/includes? (:output (convert/convert {:from :lido :to :dc :source (one-event "lido00007" nil "Maes")}))
                       "<dc:creator>Maes</dc:creator>"))))

(defn- production-with
  "A LIDO record whose production has actors `[name role-en]` (nil role: none)."
  [& actors]
  (str "<lido:lido xmlns:lido=\"http://www.lido-schema.org\"><lido:lidoRecID>R-1</lido:lidoRecID><lido:descriptiveMetadata>"
       "<lido:objectIdentificationWrap><lido:titleWrap><lido:titleSet><lido:appellationValue>Bronze</lido:appellationValue></lido:titleSet></lido:titleWrap></lido:objectIdentificationWrap>"
       "<lido:eventWrap><lido:eventSet><lido:event><lido:eventType><lido:conceptID>http://terminology.lido-schema.org/lido00007</lido:conceptID></lido:eventType>"
       (apply str (for [[nm role] actors]
                    (str "<lido:eventActor><lido:actorInRole><lido:actor><lido:nameActorSet><lido:appellationValue>" nm
                         "</lido:appellationValue></lido:nameActorSet></lido:actor>"
                         (when role (str "<lido:roleActor><lido:term xml:lang=\"en\">" role "</lido:term></lido:roleActor>"))
                         "</lido:actorInRole></lido:eventActor>")))
       "</lido:event></lido:eventSet></lido:eventWrap></lido:descriptiveMetadata></lido:lido>"))

(deftest only-a-creator-is-a-creator
  (let [xml (production-with ["Rodin" "sculptor"] ["Rudier" "founder"] ["Musée" "commissioner"] ["Anonyme" nil])]
    (testing "a sculptor, and an actor whose role the record does not state, create the object; a founder or a commissioner take part (post-audit 4, F2)"
      (let [dc (:output (convert/convert {:from :lido :to :dc :source xml}))]
        (is (str/includes? dc "<dc:creator>Rodin</dc:creator>"))
        (is (str/includes? dc "<dc:creator>Anonyme</dc:creator>"))
        (is (not (str/includes? dc "<dc:creator>Rudier")))
        (is (not (str/includes? dc "<dc:creator>Musée")))))
    (testing "in MARC21 the creator is the 100, the participants are 700 with their role"
      (let [m (:output (convert/convert {:from :lido :to :marc21-object :source xml}))]
        (is (re-find #"tag=\"100\"[^\n]*>Rodin<" m))
        (is (re-find #"tag=\"700\"[^\n]*>Rudier</subfield><subfield code=\"e\">founder<" m))
        (is (re-find #"tag=\"700\"[^\n]*>Musée</subfield><subfield code=\"e\">commissioner<" m))
        (is (not (re-find #"tag=\"100\"[^\n]*>(Rudier|Musée)<" m)))))
    (testing "in Linked Art the creators carry out the production, each other who carried it out a part of it in its role; a commissioner, who only took part, is reported (post-audit 8, K5)"
      (let [hmo (first (map json/read-str (str/split-lines (:output (convert/convert {:from :lido :to :linked-art :source xml})))))]
        (is (= #{"Rodin" "Anonyme"} (set (map #(get % "_label") (get-in hmo ["produced_by" "carried_out_by"])))))
        (is (= #{"Rudier"} (set (for [p (get-in hmo ["produced_by" "part"]) a (get p "carried_out_by")] (get a "_label")))))
        (is (empty? (:unaccounted (fidelity/audit {:from :lido :to :linked-art :source xml}))))))))

(deftest a-production-without-a-creator-has-no-main-entry
  (let [m (:output (convert/convert {:from :lido :to :marc21-object :source (production-with ["Rudier" "founder"])}))]
    (is (not (str/includes? m "tag=\"100\"")))
    (is (str/includes? m "tag=\"245\" ind1=\"0\""))))

(deftest a-creator-s-role-is-told-by-its-head
  (testing "a role names a creator by what the actor is, not by a word it contains (post-audit 4, F2 review)"
    (doseq [r ["painter" "Maler" "Kupferstecher" "Hofmaler" "Glasmaler" "KÜNSTLER" "cabinetmaker" "illustrator"
               "woodcutter" "Holzschneider" "Formschneider" "Goldschmied" "Töpfer" "Entwerfer" "manufacturer"
               "Hersteller" "sculptor" "peintre" "photographer"]]
      (is (lido/creator? [r] nil) r))
    (doseq [r ["lithographic printer" "Lithographische Anstalt" "Engraving printer" "Etching publisher"
               "photographic studio" "Photograph collector" "Artist's estate" "authorized by"
               "after a painting by" "author of the original" "printer" "Drucker" "publisher" "Verleger"
               "commissioner" "founder" "Werkstatt"]]
      (is (not (lido/creator? [r] nil)) r)))
  (testing "a role given by its URI alone is a stated role: a creator only when it is a creator's"
    (is (not (lido/creator? [] "http://vocab.getty.edu/aat/300025574")) "printers")
    (is (lido/creator? [] "http://vocab.getty.edu/aat/300025136") "painters")
    (is (lido/creator? [] nil) "no role at all")))

(deftest every-role-of-an-actor-is-read
  (let [xml (str "<lido:lido xmlns:lido=\"http://www.lido-schema.org\"><lido:lidoRecID>R-3</lido:lidoRecID><lido:descriptiveMetadata>"
                 "<lido:objectIdentificationWrap><lido:titleWrap><lido:titleSet><lido:appellationValue>T</lido:appellationValue></lido:titleSet></lido:titleWrap></lido:objectIdentificationWrap>"
                 "<lido:eventWrap><lido:eventSet><lido:event><lido:eventType><lido:conceptID>http://terminology.lido-schema.org/lido00007</lido:conceptID></lido:eventType>"
                 "<lido:eventActor><lido:actorInRole><lido:actor><lido:nameActorSet><lido:appellationValue>Pub C</lido:appellationValue></lido:nameActorSet></lido:actor>"
                 "<lido:roleActor><lido:term>publisher</lido:term></lido:roleActor><lido:roleActor><lido:term>painter</lido:term></lido:roleActor></lido:actorInRole></lido:eventActor>"
                 "<lido:eventActor><lido:actorInRole><lido:actor><lido:nameActorSet><lido:appellationValue>Printer B</lido:appellationValue></lido:nameActorSet></lido:actor>"
                 "<lido:roleActor><lido:conceptID>http://vocab.getty.edu/aat/300025574</lido:conceptID></lido:roleActor></lido:actorInRole></lido:eventActor>"
                 "</lido:event></lido:eventSet></lido:eventWrap></lido:descriptiveMetadata></lido:lido>")
        m   (:output (convert/convert {:from :lido :to :marc21-object :source xml}))
        dc  (:output (convert/convert {:from :lido :to :dc :source xml}))
        {:keys [ingest]} (convert/to-wemi :lido {} xml)]
    (testing "an actor who is publisher and painter is a creator, with both roles"
      (is (re-find #"tag=\"100\"[^\n]*>Pub C</subfield><subfield code=\"e\">publisher</subfield><subfield code=\"e\">painter<" m))
      (is (not-any? #(= :lido/term (get-in % [:detail :loss/source-field])) ingest) "the second role is read"))
    (testing "an actor whose role is a printers' URI is not a creator"
      (is (not (str/includes? dc "<dc:creator>Printer B")))
      (is (re-find #"tag=\"700\"[^\n]*>Printer B<" m)))))

(deftest no-value-is-skipped-silently
  (testing "every text a real record holds is read, or its element is reported (post-audit 4, F3)"
    (let [xml   (slurp "test/fixtures/documentary/lido/staedel-oai-lido-sample.xml")
          {:keys [ingest records]} (convert/to-wemi :oai {:metadata-prefix "lido"} xml)
          lost  (set (keep #(get-in % [:detail :loss/source-field]) ingest))
          vals  (set (mapcat (fn [r] (keep #(when (string? (:value %)) (:value %)) (:assertions r))) records))
          ;; every element's own text, as served (an entity left aside)
          texts (for [[_ nm t] (re-seq #"<lido:(\w+)[^>]*>([^<]+)</lido:\1>" xml)
                      :let [t (str/replace (str/trim t) #"\s+" " ")] :when (and (seq t) (not (str/includes? t "&")))]
                  [nm t])
          norm  #(str/replace % #"\s+" " ")
          vals  (set (map norm vals))]
      (is (= 5 (count records)))
      (testing "the rights holder, which was skipped, is read"
        (is (contains? vals "Städel Museum"))
        (is (some #(= :lido/rights-holder (:predicate %)) (mapcat :assertions records))))
      (testing "related works, which are not read, are reported"
        (is (contains? lost :lido/objectNote)))
      (testing "every text is a value, or its element is reported"
        (is (empty? (remove (fn [[nm t]] (or (vals t) (lost (keyword "lido" nm)))) texts)))))))

(deftest mixed-content-is-one-value
  (testing "a note with markup inside is read whole, and none of its parts is reported (post-audit 4, F3 review)"
    (let [xml (str "<lido:lido xmlns:lido=\"http://www.lido-schema.org\"><lido:lidoRecID>M-1</lido:lidoRecID><lido:descriptiveMetadata>"
                   "<lido:objectIdentificationWrap><lido:objectDescriptionWrap><lido:objectDescriptionSet>"
                   "<lido:descriptiveNoteValue>A <lido:b>bold</lido:b> note</lido:descriptiveNoteValue>"
                   "</lido:objectDescriptionSet></lido:objectDescriptionWrap></lido:objectIdentificationWrap></lido:descriptiveMetadata></lido:lido>")
          {:keys [records ingest]} (convert/to-wemi :lido {} xml)]
      (is (some #(= "A bold note" (:value %)) (:assertions (first records))))
      (is (empty? (filter #(= :lido/b (get-in % [:detail :loss/source-field])) ingest))))))

(deftest a-depicted-group-is-a-group
  (let [xml (str "<lido:lido xmlns:lido=\"http://www.lido-schema.org\"><lido:lidoRecID>G-1</lido:lidoRecID><lido:descriptiveMetadata>"
                 "<lido:objectClassificationWrap><lido:objectWorkTypeWrap><lido:objectWorkType><lido:term>painting</lido:term></lido:objectWorkType></lido:objectWorkTypeWrap></lido:objectClassificationWrap>"
                 "<lido:objectRelationWrap><lido:subjectWrap><lido:subjectSet><lido:subject><lido:subjectActor>"
                 "<lido:actor lido:type=\"organization\"><lido:nameActorSet><lido:appellationValue>Guild of St Luke</lido:appellationValue></lido:nameActorSet></lido:actor>"
                 "</lido:subjectActor></lido:subject></lido:subjectSet></lido:subjectWrap></lido:objectRelationWrap></lido:descriptiveMetadata></lido:lido>")
        out (:output (convert/convert {:from :lido :to :ntriples :source xml}))]
    (is (str/includes? out "E74_Group"))
    (is (not (str/includes? out "E21_Person")))))

(deftest a-record-id-is-scoped-by-its-source
  (testing "two institutions' records with the same local lidoRecID are two records (post-audit 4, F6)"
    (let [rec (fn [src] (str "<lido:lido xmlns:lido=\"http://www.lido-schema.org\"><lido:lidoRecID lido:source=\"" src "\">42</lido:lidoRecID></lido:lido>"))
          id  #(:id (first (:records (convert/to-wemi :lido {} (rec %)))))]
      (is (not= (id "http://museum-a.example/") (id "http://museum-b.example/"))))))

(deftest a-dimension-that-is-no-number-is-no-p90
  (testing "P90_has_value takes a number; a value that is none is a note on the object (post-audit 4, F6)"
    (let [out (:output (convert/convert {:from :lido :to :ntriples :source lido-1-1}))]
      (is (not (re-find #"P90_has_value> \"(NaN|ca\. 5)\"" out)))
      (is (str/includes? out "ca. 5")))))

(deftest a-record-keeps-its-id-as-written
  (testing "the source identifier (a MARC 001) is the lidoRecID as written, not the scoped key (F6 review)"
    (let [xml (str "<lido:lido xmlns:lido=\"http://www.lido-schema.org\"><lido:lidoRecID lido:source=\"DE-Mb112\">obj-42</lido:lidoRecID>"
                   "<lido:descriptiveMetadata><lido:objectIdentificationWrap><lido:titleWrap><lido:titleSet><lido:appellationValue>T</lido:appellationValue>"
                   "</lido:titleSet></lido:titleWrap></lido:objectIdentificationWrap></lido:descriptiveMetadata></lido:lido>")]
      (is (str/includes? (:output (convert/convert {:from :lido :to :marc21-object :source xml}))
                         "<controlfield tag=\"001\">obj-42</controlfield>")))))

(deftest a-thousands-separator-is-no-decimal-mark
  (testing "\"1,234\" may be a thousand: no number; \"12,5\" is twelve and a half (F6 review)"
    (is (= "12.5" (lido/number-value "12,5")))
    (is (= "85.6" (lido/number-value " 85.6 ")))
    (is (nil? (lido/number-value "1,234")))
    (is (nil? (lido/number-value "ca. 5")))
    (let [xml (str "<lido:lido xmlns:lido=\"http://www.lido-schema.org\"><lido:lidoRecID>D-1</lido:lidoRecID><lido:descriptiveMetadata>"
                   "<lido:objectIdentificationWrap><lido:objectMeasurementsWrap><lido:objectMeasurementsSet><lido:objectMeasurements>"
                   "<lido:measurementsSet><lido:measurementType>height</lido:measurementType><lido:measurementUnit>mm</lido:measurementUnit>"
                   "<lido:measurementValue>1,234</lido:measurementValue></lido:measurementsSet>"
                   "</lido:objectMeasurements></lido:objectMeasurementsSet></lido:objectMeasurementsWrap></lido:objectIdentificationWrap></lido:descriptiveMetadata></lido:lido>")]
      (is (not (str/includes? (:output (convert/convert {:from :lido :to :ntriples :source xml})) "P90_has_value")))
      (is (not (str/includes? (:output (convert/convert {:from :lido :to :linked-art :source xml})) "\"dimension\""))))))
(def ^:private graphics
  "Real prints, drawings and a photograph of the Städel (CC0), in an OAI-PMH response."
  "test/fixtures/documentary/lido/staedel-oai-lido-graphics.xml")

(deftest the-prints-and-drawings-are-read-whole
  (testing "every text of the graphic arts is read, or its element is reported; nothing is lost silently (post-audit 4, F7)"
    (let [xml  (slurp graphics)
          {:keys [ingest records]} (convert/to-wemi :oai {:metadata-prefix "lido"} xml)
          lost (set (keep #(get-in % [:detail :loss/source-field]) ingest))
          norm #(str/replace % #"\s+" " ")
          vals (set (map norm (mapcat (fn [r] (keep #(when (string? (:value %)) (:value %)) (:assertions r))) records)))
          texts (for [[_ nm t] (re-seq #"<lido:(\w+)[^>]*>([^<]+)</lido:\1>" xml)
                      :let [t (norm (str/trim t))] :when (and (seq t) (not (str/includes? t "&")))]
                  [nm t])
          actors (mapcat (fn [r] (keep #(when (= :lido/actor (:predicate %)) (:value/fields (:value %))) (:assertions r))) records)]
      (is (= 6 (count records)))
      (is (empty? (remove (fn [[nm t]] (or (vals t) (lost (keyword "lido" nm)))) texts)))
      (testing "an inventor and engraver is a creator, with both roles; so is each of two draughtsmen"
        (is (some #(and (= "Dürer, Albrecht" (:name %)) (= "inventor; engraver" (:role %)) (= "creator" (:responsibility %))) actors))
        (is (= #{"Anonym" "Roche, Odilon"}
               (set (keep #(when (and (= "draughtsman" (:role %)) (= "creator" (:responsibility %))) (:name %)) actors))))
        (is (some #(and (= "Rodin, Auguste" (:name %)) (= "fraglich" (:qualifier %)) (= "uncertain" (:attribution %))) actors)
            "a doubtful (fraglich) attribution is no stated creator (post-audit 8, K2)"))
      (doseq [to [:linked-art :ntriples :crm :dc :marc21-object]]
        (is (empty? (:unaccounted (fidelity/audit {:from :oai :opts {:metadata-prefix "lido"} :to to :source xml}))) (str to))))))

(deftest a-title-keeps-its-language
  (testing "each title with the language its record states, its own or the descriptive metadata's (post-audit 4, F7)"
    (let [xml (str "<lido:lido xmlns:lido=\"http://www.lido-schema.org\"><lido:lidoRecID>L-1</lido:lidoRecID><lido:descriptiveMetadata xml:lang=\"de\">"
                   "<lido:objectIdentificationWrap><lido:titleWrap><lido:titleSet>"
                   "<lido:appellationValue>Ein Fisch</lido:appellationValue><lido:appellationValue xml:lang=\"en\">A Fish</lido:appellationValue>"
                   "</lido:titleSet></lido:titleWrap></lido:objectIdentificationWrap></lido:descriptiveMetadata></lido:lido>")
          {:keys [records]} (convert/to-wemi :lido {} xml)]
      (is (= #{{:content "Ein Fisch" :lang "de"} {:content "A Fish" :lang "en"}}
             (set (keep #(when (= :lido/title-name (:predicate %)) (:value/fields (:value %))) (:assertions (first records)))))))))

(defn- missing [xml]
  (set (keep #(when (= :lido/missing-required (:code %)) (get-in % [:detail :lido/element]))
             (:diagnostics (validate/validate {:from :lido :source xml :policy :errors-and-warnings})))))

(deftest what-lido-requires-is-checked
  (testing "a record that lacks what the LIDO schema requires says so, element by element (post-audit 4, F7)"
    (is (= #{"descriptiveMetadata" "administrativeMetadata"}
           (missing "<lido:lido xmlns:lido=\"http://www.lido-schema.org\"><lido:lidoRecID>V-1</lido:lidoRecID></lido:lido>")))
    (is (= #{"lidoRecID" "descriptiveMetadata/@xml:lang" "descriptiveMetadata/objectClassificationWrap/objectWorkTypeWrap/objectWorkType"
             "administrativeMetadata/@xml:lang" "administrativeMetadata/recordWrap/recordType"
             "administrativeMetadata/recordWrap/recordSource"}
           (missing (str "<lido:lido xmlns:lido=\"http://www.lido-schema.org\"><lido:descriptiveMetadata>"
                         "<lido:objectIdentificationWrap><lido:titleWrap><lido:titleSet><lido:appellationValue>T</lido:appellationValue></lido:titleSet></lido:titleWrap></lido:objectIdentificationWrap>"
                         "</lido:descriptiveMetadata><lido:administrativeMetadata><lido:recordWrap><lido:recordID>r</lido:recordID></lido:recordWrap></lido:administrativeMetadata></lido:lido>")))))
  (testing "the Städel's records, valid LIDO, are said to lack nothing"
    (doseq [f ["test/fixtures/documentary/lido/staedel-oai-lido-sample.xml" graphics]]
      (is (empty? (filter #(= :lido/missing-required (:code %)) (mapcat :diagnostics (:records (convert/to-wemi :oai {:metadata-prefix "lido"} (slurp f)))))) f))))

(deftest what-lido-requires-is-checked-everywhere
  (testing "every descriptive metadata needs its language, every event its type, every actor its name (F7 review)"
    (is (= #{"descriptiveMetadata/@xml:lang" "descriptiveMetadata/eventWrap/eventSet/event/eventType"
             "descriptiveMetadata/eventWrap/eventSet/event/eventActor/actorInRole/actor/nameActorSet"}
           (set (filter #(re-find #"lang|event" %)
                        (missing (str "<lido:lido xmlns:lido=\"http://www.lido-schema.org\"><lido:lidoRecID>V-2</lido:lidoRecID>"
                                      "<lido:descriptiveMetadata xml:lang=\"de\"><lido:objectIdentificationWrap><lido:titleWrap><lido:titleSet><lido:appellationValue>T</lido:appellationValue></lido:titleSet></lido:titleWrap></lido:objectIdentificationWrap></lido:descriptiveMetadata>"
                                      "<lido:descriptiveMetadata><lido:eventWrap><lido:eventSet><lido:event>"
                                      "<lido:eventActor><lido:actorInRole><lido:actor><lido:actorID>x</lido:actorID></lido:actor></lido:actorInRole></lido:eventActor>"
                                      "</lido:event></lido:eventSet></lido:eventWrap></lido:descriptiveMetadata></lido:lido>")))))))
  (testing "an empty xml:lang says no language"
    (let [xml (str "<lido:lido xmlns:lido=\"http://www.lido-schema.org\"><lido:lidoRecID>L-3</lido:lidoRecID><lido:descriptiveMetadata xml:lang=\"de\">"
                   "<lido:objectIdentificationWrap><lido:titleWrap><lido:titleSet><lido:appellationValue xml:lang=\"\">X</lido:appellationValue>"
                   "</lido:titleSet></lido:titleWrap></lido:objectIdentificationWrap></lido:descriptiveMetadata></lido:lido>")]
      (is (empty? (filter #(= :lido/title-name (:predicate %)) (:assertions (first (:records (convert/to-wemi :lido {} xml))))))))))

(def ^:private restored
  "A bronze, restored with a solvent: the bronze is what it is made of, the
   solvent only what its restoration employed."
  (str "<lido:lido xmlns:lido=\"http://www.lido-schema.org\"><lido:lidoRecID>R-9</lido:lidoRecID><lido:descriptiveMetadata xml:lang=\"en\">"
       "<lido:objectIdentificationWrap><lido:titleWrap><lido:titleSet><lido:appellationValue>Bust</lido:appellationValue></lido:titleSet></lido:titleWrap></lido:objectIdentificationWrap>"
       "<lido:eventWrap><lido:eventSet><lido:event><lido:eventType><lido:conceptID>http://terminology.lido-schema.org/lido00007</lido:conceptID><lido:term>production</lido:term></lido:eventType>"
       "<lido:eventMaterialsTech><lido:displayMaterialsTech>bronze, cast</lido:displayMaterialsTech><lido:materialsTech><lido:termMaterialsTech lido:type=\"http://terminology.lido-schema.org/lido00132\">"
       "<lido:conceptID>http://vocab.getty.edu/aat/300010957</lido:conceptID><lido:term>bronze</lido:term></lido:termMaterialsTech></lido:materialsTech></lido:eventMaterialsTech>"
       "</lido:event></lido:eventSet><lido:eventSet><lido:event><lido:eventType><lido:term>restoration</lido:term></lido:eventType>"
       "<lido:eventMaterialsTech><lido:displayMaterialsTech>cleaned with white spirit</lido:displayMaterialsTech><lido:materialsTech><lido:termMaterialsTech lido:type=\"http://terminology.lido-schema.org/lido00132\">"
       "<lido:conceptID>http://vocab.getty.edu/aat/300014711</lido:conceptID><lido:term>white spirit</lido:term></lido:termMaterialsTech></lido:materialsTech></lido:eventMaterialsTech>"
       "</lido:event></lido:eventSet></lido:eventWrap></lido:descriptiveMetadata></lido:lido>"))

(deftest a-restoration-s-material-is-no-constituent
  (testing "only the production's materials are what the object consists of; a restoration employs its own (post-audit 5, G1)"
    (let [nt  (:output (convert/convert {:from :lido :to :ntriples :source restored}))
          la  (json/read-str (first (str/split-lines (:output (convert/convert {:from :lido :to :linked-art :source restored})))))
          mrc (:output (convert/convert {:from :lido :to :marc21-object :source restored}))
          p45 (set (keep (fn [[_ p o]] (when (str/ends-with? (:iri p) "P45_consists_of") o)) (nt/parse nt)))]
      (is (re-find #"P45_consists_of> <http://vocab.getty.edu/aat/300010957>" nt) "the bronze")
      (is (not (re-find #"P45_consists_of> <http://vocab.getty.edu/aat/300014711>" nt)) "never the solvent")
      (is (re-find #"P126_employed> <http://vocab.getty.edu/aat/300014711>" nt) "the restoration employed it")
      (is (str/includes? nt "E11_Modification"))
      (is (= 1 (count p45)))
      (is (empty? (violations restored)))
      (is (= ["http://vocab.getty.edu/aat/300010957"] (map #(get % "id") (get la "made_of"))))
      (is (not-any? #(= "cleaned with white spirit" (get % "content")) (get la "referred_to_by"))
          "nor is the restoration's statement the object's material statement")
      (is (not (str/includes? mrc ">cleaned with white spirit<")) "nor its 340")
      (doseq [to [:linked-art :ntriples :crm :dc :marc21-object]]
        (is (empty? (:unaccounted (fidelity/audit {:from :lido :to to :source restored}))) (str to))))))

(deftest every-production-s-materials-are-the-object-s
  (testing "a record with no production converts; with a designing before its production, the production's bronze stays the object's (G1 review)"
    (let [noprod (str/replace restored #"<lido:conceptID>http://terminology.lido-schema.org/lido00007</lido:conceptID><lido:term>production</lido:term>" "<lido:term>Produktion?</lido:term>")
          design (str/replace restored "<lido:eventWrap>"
                              (str "<lido:eventWrap><lido:eventSet><lido:event><lido:eventType><lido:conceptID>http://terminology.lido-schema.org/lido00224</lido:conceptID></lido:eventType>"
                                   "</lido:event></lido:eventSet>"))]
      (doseq [to [:linked-art :marc21-object :ntriples]]
        (is (string? (:output (convert/convert {:from :lido :to to :source noprod}))) (str to))
        (is (empty? (:unaccounted (fidelity/audit {:from :lido :to to :source noprod}))) (str to)))
      (let [la (json/read-str (first (str/split-lines (:output (convert/convert {:from :lido :to :linked-art :source design})))))]
        (is (= ["http://vocab.getty.edu/aat/300010957"] (map #(get % "id") (get la "made_of"))))
        (is (some #(= "bronze, cast" (get % "content")) (get la "referred_to_by"))))
      (is (str/includes? (:output (convert/convert {:from :lido :to :marc21-object :source design})) ">bronze, cast<")))))

(deftest taking-part-is-not-carrying-out
  (testing "an actor of an event that is no activity took part in it (P11); only an activity is carried out (P14) (post-audit 5, G2)"
    (doseq [[code term] [["lido00009" "Verlust"] ["lido00002" nil] ["lido00026" nil] ["lido00003" nil] [nil "Ereignis"]]]
      (let [xml (one-event code term "Zeuge")
            out (:output (convert/convert {:from :lido :to :ntriples :source xml}))]
        (is (str/includes? out "cidoc-crm/E5_Event") (str code " " term))
        (is (str/includes? out "P11_had_participant") (str code " " term))
        (is (not (str/includes? out "P14_carried_out_by")) (str code " " term))
        (is (not (str/includes? out "cidoc-crm/E7_Activity")) (str code " " term))
        (is (empty? (violations xml)) (str code " " term))))
    (testing "an exhibition, by its code or its term alone, is carried out by its curator"
      (doseq [[code term] [["lido00225" nil] [nil "Ausstellung"] [nil "exhibition"] [nil "Gebrauch"] [nil "Sammeln"]]]
        (let [out (:output (convert/convert {:from :lido :to :ntriples :source (one-event code term "Kurator")}))]
          (is (str/includes? out "cidoc-crm/E7_Activity") (str code " " term))
          (is (str/includes? out "P14_carried_out_by") (str code " " term)))))))

(def ^:private fossil
  "A fossil, found by a collector: nothing says anyone made it."
  (str "<lido:lido xmlns:lido=\"http://www.lido-schema.org\"><lido:lidoRecID>N-1</lido:lidoRecID><lido:descriptiveMetadata xml:lang=\"en\">"
       "<lido:objectClassificationWrap><lido:objectWorkTypeWrap><lido:objectWorkType><lido:term>fossil</lido:term></lido:objectWorkType></lido:objectWorkTypeWrap></lido:objectClassificationWrap>"
       "<lido:objectIdentificationWrap><lido:titleWrap><lido:titleSet><lido:appellationValue>Ammonite</lido:appellationValue></lido:titleSet></lido:titleWrap></lido:objectIdentificationWrap>"
       "<lido:eventWrap><lido:eventSet><lido:event><lido:eventType><lido:conceptID>http://terminology.lido-schema.org/lido00002</lido:conceptID></lido:eventType>"
       "<lido:eventActor><lido:actorInRole><lido:actor><lido:nameActorSet><lido:appellationValue>Mary Anning</lido:appellationValue></lido:nameActorSet></lido:actor></lido:actorInRole></lido:eventActor>"
       "</lido:event></lido:eventSet></lido:eventWrap>"
       "<lido:objectRelationWrap><lido:subjectWrap><lido:subjectSet><lido:subject><lido:subjectConcept><lido:conceptID>http://vocab.getty.edu/aat/300011074</lido:conceptID><lido:term>shell</lido:term></lido:subjectConcept></lido:subject></lido:subjectSet></lido:subjectWrap></lido:objectRelationWrap>"
       "</lido:descriptiveMetadata></lido:lido>"))

(deftest only-what-was-made-is-human-made
  (testing "an object is an E22_Human-Made_Object only when its record says it was made; else an E19_Physical_Object (post-audit 5, G4)"
    (let [nt  (:output (convert/convert {:from :lido :to :ntriples :source fossil}))
          {:keys [records]} (convert/to-wemi :lido {} fossil)
          prj (mapcat :diagnostics records)
          la  (convert/export-records :linked-art records)]
      (is (str/includes? nt "cidoc-crm/E19_Physical_Object"))
      (is (not (str/includes? nt "E22_Human-Made_Object")))
      (is (not (str/includes? nt "P62_depicts")) "only a human-made thing depicts (P62's domain is E24)")
      (is (empty? (violations fossil)))
      (is (some #(= :class (get-in % [:detail :loss/aspect])) prj) "that it is not said to be human-made is reported")
      (testing "Linked Art, which has only HumanMadeObject, does not call it one"
        (is (str/blank? (:output la)))
        (is (some #(= :class (get-in % [:detail :loss/aspect])) (:loss la))))
      (doseq [to [:ntriples :crm :dc :marc21-object :linked-art]]
        (is (empty? (:unaccounted (fidelity/audit {:from :lido :to to :source fossil}))) (str to)))))
  (testing "a painting with no production event is human-made by its work type"
    (let [xml (str/replace fossil #"<lido:term>fossil</lido:term>" "<lido:term>Gemälde</lido:term>")]
      (is (str/includes? (:output (convert/convert {:from :lido :to :ntriples :source xml})) "E22_Human-Made_Object"))))
  (testing "every Städel record is human-made"
    (is (not (str/includes? (:output (convert/convert {:from :oai :opts {:metadata-prefix "lido"} :to :ntriples :source (slurp graphics)}))
                            "E19_Physical_Object")))))

(deftest what-is-made-is-told-in-many-tongues
  (testing "German compounds and other artefacts are human-made; a natural term vetoes; an object of no stated kind stays in Linked Art (G4 review)"
    (let [rec  (fn [t] (str/replace (str/replace fossil #"<lido:term>fossil</lido:term>" (str "<lido:term>" t "</lido:term>"))
                                    #"lido00002" "lido00001"))
          crm  (fn [t] (:output (convert/convert {:from :lido :to :ntriples :source (rec t)})))]
      (doseq [t ["Kupferstich" "Ölgemälde" "Keramik" "Handschrift" "poster" "Farbholzschnitt"]]
        (is (str/includes? (crm t) "E22_Human-Made_Object") t))
      (doseq [t ["porcelain crab specimen" "Mineral" "fossil shell"]]
        (is (str/includes? (crm t) "E19_Physical_Object") t))
      (testing "a type no list knows: an E19 in CIDOC CRM, but still a HumanMadeObject in Linked Art, its class reported"
        (let [{:keys [records]} (convert/to-wemi :lido {} (rec "Ding"))
              {:keys [output loss]} (convert/export-records :linked-art records)]
          (is (str/includes? (crm "Ding") "E19_Physical_Object"))
          (is (str/includes? output "HumanMadeObject"))
          (is (some #(and (= :class (get-in % [:detail :loss/aspect])) (= :lido/object (get-in % [:detail :loss/source-field]))) loss)))))))

(def ^:private museum-digital
  "Real records of two other institutions' aggregator, museum-digital (Hessen,
   Westfalen; CC0): prints, a portfolio, Greek vases, plaster casts, a model."
  ["test/fixtures/documentary/lido/museum-digital-hessen-lido.xml"
   "test/fixtures/documentary/lido/museum-digital-westfalen-lido.xml"])

(deftest another-institution-s-records-are-read-whole
  (testing "museum-digital's practice: its event terms, its materials in an unspecified event, its casts (post-audit 5, G6)"
    (doseq [f museum-digital]
      (let [xml (slurp f)
            {:keys [ingest records]} (convert/to-wemi :oai {:metadata-prefix "lido"} xml)
            lost (set (keep #(get-in % [:detail :loss/source-field]) ingest))
            norm #(str/replace % #"\s+" " ")
            vals (set (map norm (mapcat (fn [r] (keep #(when (string? (:value %)) (:value %)) (:assertions r))) records)))
            texts (for [[_ nm t] (re-seq #"<lido:(\w+)[^>]*>([^<]+)</lido:\1>" xml)
                        :let [t (norm (str/trim t))] :when (and (seq t) (not (str/includes? t "&")))]
                    [nm t])
            nt (:output (convert/convert {:from :oai :opts {:metadata-prefix "lido"} :to :ntriples :source xml}))]
        (is (empty? (remove (fn [[nm t]] (or (vals t) (lost (keyword "lido" nm)))) texts)) f)
        (is (not (str/includes? nt "E19_Physical_Object")) (str f ": every one of these objects was made"))
        (is (str/includes? nt "E12_Production") (str f ": \"Hergestellt\" is a production"))
        (when (str/includes? f "westfalen")
          (is (str/includes? (:output (convert/convert {:from :oai :opts {:metadata-prefix "lido"} :to :linked-art :source xml}))
                             "\"content\":\"Gips\"")
              "the materials of the unspecified event are the object's material statement"))
        (is (empty? (mapcat :diagnostics (filter #(= :lido/missing-required (:code %)) (mapcat :diagnostics records)))) f)
        (doseq [to [:linked-art :ntriples :crm :dc :marc21-object]]
          (is (empty? (:unaccounted (fidelity/audit {:from :oai :opts {:metadata-prefix "lido"} :to to :source xml}))) (str f " " to)))))))

(defn- schema-errors [xml]
  (keep #(when (= :lido/schema-invalid (:code %)) %) (mapcat :diagnostics (:records (convert/to-wemi :lido {} xml)))))

(deftest a-record-is-checked-against-the-lido-schema
  (testing "every record is validated against the official LIDO XSD, vendored, offline (post-audit 5, G6)"
    (doseq [f (concat ["test/fixtures/documentary/lido/staedel-oai-lido-sample.xml" graphics] museum-digital)]
      (is (empty? (filter #(= :lido/schema-invalid (:code %)) (mapcat :diagnostics (:records (convert/to-wemi :oai {:metadata-prefix "lido"} (slurp f))))))
          (str f " is valid LIDO")))
    (testing "an element out of place, or an unknown one, is an error naming it"
      (let [bad (str "<lido:lido xmlns:lido=\"http://www.lido-schema.org\"><lido:lidoRecID lido:type=\"local\">X-1</lido:lidoRecID><lido:frobnicate/></lido:lido>")
            [e] (schema-errors bad)]
        (is (= :error (:severity e)))
        (is (re-find #"frobnicate" (:message e)))))
    (testing "the validate gate fails on it"
      (is (:failed? (validate/validate {:from :lido :source (str "<lido:lido xmlns:lido=\"http://www.lido-schema.org\"><lido:frobnicate/></lido:lido>")}))))
    (testing "and it can be turned off"
      (is (empty? (filter #(= :lido/schema-invalid (:code %))
                          (mapcat :diagnostics (:records (convert/to-wemi :lido {:validate? false} "<lido:lido xmlns:lido=\"http://www.lido-schema.org\"><lido:frobnicate/></lido:lido>")))))))))

(deftest no-record-escapes-the-schema
  (testing "a record in no namespace, or the wrong one, is an error; an error stays on its own record (G6 review)"
    (doseq [xml ["<lido><lidoRecID>X-1</lidoRecID><frobnicate/></lido>"
                 "<lido:lido xmlns:lido=\"http://www.lido-schema.org/\"><lido:lidoRecID>X-2</lido:lidoRecID></lido:lido>"]]
      (is (seq (schema-errors xml)) xml))
    (let [wrap (str "<lido:lidoWrap xmlns:lido=\"http://www.lido-schema.org\" xmlns:x=\"urn:x\">"
                    "<x:ext><lido:lido><lido:lidoRecID>Z</lido:lidoRecID><lido:frobnicate/></lido:lido></x:ext>"
                    "<lido:lido><lido:lidoRecID>A</lido:lidoRecID><lido:frobnicate/></lido:lido>"
                    "<lido:lido><lido:lidoRecID>B</lido:lidoRecID></lido:lido></lido:lidoWrap>")
          recs (:records (convert/to-wemi :lido {} wrap))
          bad  (fn [id] (some #(and (= :lido/schema-invalid (:code %)) (some (fn [m] (re-find #"frobnicate" m)) (get-in % [:detail :lido/errors])))
                              (:diagnostics (first (filter #(= id (:source %)) recs)))))]
      (is (bad "A"))
      (is (not (bad "B")) "B is not given A's error, nor A Z's"))))

(deftest a-restoration-in-disguise-is-no-constituent
  (testing "an unspecified event that has a date, an actor or a description is no bare list of materials (G6 review)"
    (let [xml (str/replace restored "<lido:term>restoration</lido:term>" "<lido:term>Event (non-specified)</lido:term>")
          nt  (:output (convert/convert {:from :lido :to :ntriples :source (str/replace xml "</lido:eventMaterialsTech></lido:event></lido:eventSet></lido:eventWrap>"
                                                                                        "</lido:eventMaterialsTech><lido:eventDate><lido:displayDate>1987</lido:displayDate></lido:eventDate></lido:event></lido:eventSet></lido:eventWrap>")}))]
      (is (not (re-find #"P45_consists_of> <http://vocab.getty.edu/aat/300014711>" nt))))))

(defn- object-iri [xml]
  (second (re-find #"<([^>]+)> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://www.cidoc-crm.org/cidoc-crm/(?:E22_Human-Made_Object|E19_Physical_Object)>"
                   (:output (convert/convert {:from :lido :to :ntriples :source xml})))))

(deftest an-object-keeps-its-iri-when-its-record-is-enriched
  (testing "the object's IRI does not depend on its class: an E19 enriched into an E22 is the same node (post-audit 6, H1)"
    (let [v1 (str "<lido:lido xmlns:lido=\"http://www.lido-schema.org\"><lido:lidoRecID lido:source=\"DE-Mb112\">INV-42</lido:lidoRecID><lido:descriptiveMetadata xml:lang=\"en\">"
                  "<lido:objectClassificationWrap><lido:objectWorkTypeWrap><lido:objectWorkType><lido:term>Ding</lido:term></lido:objectWorkType></lido:objectWorkTypeWrap></lido:objectClassificationWrap>"
                  "<lido:objectIdentificationWrap><lido:titleWrap><lido:titleSet><lido:appellationValue>T</lido:appellationValue></lido:titleSet></lido:titleWrap></lido:objectIdentificationWrap>"
                  "</lido:descriptiveMetadata></lido:lido>")
          v2 (str/replace v1 "<lido:term>Ding</lido:term>" "<lido:term>sculpture</lido:term>")
          nt (fn [x] (:output (convert/convert {:from :lido :to :ntriples :source x})))]
      (is (str/includes? (nt v1) "E19_Physical_Object"))
      (is (str/includes? (nt v2) "E22_Human-Made_Object"))
      (is (some? (object-iri v1)))
      (is (= (object-iri v1) (object-iri v2)) "the same object, the same IRI")
      (is (not (re-find #"E22|E19" (object-iri v1))) "the IRI says nothing of a class"))))

(deftest a-duplicate-record-id-is-reported
  (testing "two records of one source with the same lidoRecID would merge: each after the first is flagged (post-audit 6, H1)"
    (let [rec  (fn [t] (str "<lido:lido><lido:lidoRecID lido:source=\"DE-Mb112\">42</lido:lidoRecID><lido:descriptiveMetadata xml:lang=\"en\">"
                            "<lido:objectIdentificationWrap><lido:titleWrap><lido:titleSet><lido:appellationValue>" t "</lido:appellationValue></lido:titleSet></lido:titleWrap></lido:objectIdentificationWrap>"
                            "</lido:descriptiveMetadata></lido:lido>"))
          wrap (str "<lido:lidoWrap xmlns:lido=\"http://www.lido-schema.org\">" (rec "A") (rec "B") (str/replace (rec "C") ">42<" ">43<") "</lido:lidoWrap>")
          dup? (fn [r] (some #(= :lido/duplicate-id (:code %)) (:diagnostics r)))
          batch (:records (convert/to-wemi :lido {:validate? false} wrap))
          strm  (with-open [rd (java.io.StringReader. wrap)] (doall (convert/stream-source :lido {:validate? false} rd)))]
      (doseq [rs [batch strm]]
        (is (= [false true false] (mapv (comp boolean dup?) rs)))))))

(defn- events-record
  "A record with `events`, each `[type-code-or-term actor role date]`, and work type `wt`."
  [wt & events]
  (str "<lido:lido xmlns:lido=\"http://www.lido-schema.org\"><lido:lidoRecID>H-2</lido:lidoRecID><lido:descriptiveMetadata xml:lang=\"en\">"
       "<lido:objectClassificationWrap><lido:objectWorkTypeWrap><lido:objectWorkType><lido:term>" wt "</lido:term></lido:objectWorkType></lido:objectWorkTypeWrap></lido:objectClassificationWrap>"
       "<lido:objectIdentificationWrap><lido:titleWrap><lido:titleSet><lido:appellationValue>T</lido:appellationValue></lido:titleSet></lido:titleWrap></lido:objectIdentificationWrap>"
       "<lido:eventWrap>"
       (apply str (for [[t actor role date] events]
                    (str "<lido:eventSet><lido:event><lido:eventType>"
                         (if (str/starts-with? t "lido") (str "<lido:conceptID>http://terminology.lido-schema.org/" t "</lido:conceptID>") (str "<lido:term>" t "</lido:term>"))
                         "</lido:eventType>"
                         (when actor (str "<lido:eventActor><lido:actorInRole><lido:actor><lido:nameActorSet><lido:appellationValue>" actor
                                          "</lido:appellationValue></lido:nameActorSet></lido:actor>"
                                          (when role (str "<lido:roleActor><lido:term>" role "</lido:term></lido:roleActor>"))
                                          "</lido:actorInRole></lido:eventActor>"))
                         (when date (str "<lido:eventDate><lido:displayDate>" date "</lido:displayDate></lido:eventDate>"))
                         "</lido:event></lido:eventSet>")))
       "</lido:eventWrap></lido:descriptiveMetadata></lido:lido>"))

(deftest a-design-is-no-production
  (testing "designing in 1890, making in 1902: one production, one creation of a design it used (post-audit 6, H2)"
    (let [xml (events-record "sculpture" ["lido00224" "Rodin" "designer" "1890"] ["lido00007" "Rudier" "founder" "1902"])
          nt  (:output (convert/convert {:from :lido :to :ntriples :source xml}))]
      (is (= 1 (count (re-seq #"E12_Production> \." nt))) "one production")
      (is (str/includes? nt "E65_Creation"))
      (is (str/includes? nt "E29_Design_or_Procedure"))
      (is (str/includes? nt "P94_has_created"))
      (is (not (str/includes? nt "P33_used_specific_technique")) "that it used the design is no fact (post-audit 7, I1)")
      (is (str/includes? (:output (convert/convert {:from :lido :to :ntriples :source xml :with-proposals? true})) "P33_used_specific_technique")
          "it is a proposal")
      (is (empty? (violations xml)))
      (doseq [to [:linked-art :ntriples :crm :dc :marc21-object]]
        (is (empty? (:unaccounted (fidelity/audit {:from :lido :to to :source xml}))) (str to))))))

(deftest a-cast-of-a-fossil-was-made
  (testing "a production attested outweighs a natural term: an E22, the contradiction reported (post-audit 6, H2)"
    (let [xml (events-record "fossil cast" ["lido00007" "Atelier" nil "1900"])
          {:keys [records]} (convert/to-wemi :lido {:validate? false} xml)
          nt  (:output (convert/convert {:from :lido :to :ntriples :source xml}))]
      (is (str/includes? nt "E22_Human-Made_Object"))
      (is (not (str/includes? nt "E19_Physical_Object")))
      (is (empty? (violations xml)))
      (is (some #(= :class (get-in % [:detail :loss/aspect])) (mapcat :diagnostics records)) "the natural term contradicted is reported"))))

(deftest a-commissioner-took-part-and-did-not-make-it
  (testing "a commissioner of the production took part in it (P11); the sculptor and the founder carried it out (P14) (post-audit 6, H2)"
    (let [xml (str/replace (events-record "sculpture" ["lido00007" "Rodin" "sculptor" "1902"])
                           "</lido:eventActor>"
                           (str "</lido:eventActor><lido:eventActor><lido:actorInRole><lido:actor><lido:nameActorSet><lido:appellationValue>Musée</lido:appellationValue></lido:nameActorSet></lido:actor>"
                                "<lido:roleActor><lido:term>commissioner</lido:term></lido:roleActor></lido:actorInRole></lido:eventActor>"))
          nt  (:output (convert/convert {:from :lido :to :ntriples :source xml}))
          ts  (nt/parse nt)
          who (fn [p] (set (keep (fn [[_ pr o]] (when (str/ends-with? (:iri pr) p) (:iri o))) ts)))
          lit   (into {} (keep (fn [[s pr o]] (when (str/ends-with? (:iri pr) "P190_has_symbolic_content") [(:iri s) (:lit o)])) ts))
          app   (into {} (keep (fn [[s pr o]] (when (str/ends-with? (:iri pr) "P1_is_identified_by") [(:iri s) (:iri o)])) ts))
          named (fn [iri] (or (lit iri) (lit (app iri))))]
      (is (= #{"Rodin"} (set (map named (who "P14_carried_out_by")))))
      (is (= #{"Musée"} (set (map named (who "P11_had_participant")))))
      (is (empty? (violations xml))))))

(deftest a-design-alone-and-other-review-cases
  (testing "a design with no production: no object said made, every value written or reported (H2 review, post-audit 7, I1)"
    (let [xml (events-record "thing" ["lido00224" "Rodin" "designer" "1890"])]
      (is (str/includes? (:output (convert/convert {:from :lido :to :ntriples :source xml})) "E19_Physical_Object")
          "designed is not made: a design alone attests no physical object (post-audit 7, I1)")
      (doseq [to [:linked-art :ntriples :crm :dc :marc21-object]]
        (is (empty? (:unaccounted (fidelity/audit {:from :lido :to to :source xml}))) (str to)))))
  (testing "a contractor (Auftragnehmer) carried the production out"
    (is (not (lido/passive? "Auftragnehmer")))
    (is (lido/passive? "Auftraggeber")))
  (testing "a cast of a fossil, made, is a HumanMadeObject in Linked Art"
    (is (str/includes? (:output (convert/convert {:from :lido :to :linked-art :source (events-record "fossil cast" ["lido00007" "Atelier" nil "1900"])}))
                       "HumanMadeObject"))))

(deftest the-terms-of-other-portals-are-read
  (testing "museum-digital outside Germany: English and Hungarian event terms (post-audit 6, H3)"
    (doseq [t ["Created" "Made" "Felvétel készítése" "Készítés"]]
      (let [nt (:output (convert/convert {:from :lido :to :ntriples :source (events-record "thing" [t "A" nil "1900"])}))]
        (is (str/includes? nt "E12_Production") (str t " is a production"))
        (is (str/includes? nt "E22_Human-Made_Object") (str t ": the object was made"))))
    (doseq [t ["Was used" "Used" "Gyűjtés" "Kiadás" "Collected" "Published" "Exhibited"]]
      (let [nt (:output (convert/convert {:from :lido :to :ntriples :source (events-record "thing" [t "A" nil "1900"])}))]
        (is (str/includes? nt "E7_Activity") (str t " is an activity"))
        (is (not (str/includes? nt "E12_Production")) (str t " is no production"))))))

(deftest a-design-is-not-said-to-be-used-by-a-production
  (testing "LIDO links no design to a production: that one used the other is a proposal, never a fact (post-audit 7, I1)"
    (let [xml   (events-record "sculpture" ["lido00224" "Rodin" "designer" "1890"] ["lido00224" "Rodin" "designer" "1895"]
                               ["lido00007" "Rudier" "founder" "1902"] ["lido00007" "Susse" "founder" "1920"])
          facts (:output (convert/convert {:from :lido :to :ntriples :source xml}))
          all   (:output (convert/convert {:from :lido :to :ntriples :source xml :with-proposals? true}))]
      (is (= 2 (count (re-seq #"E65_Creation> \." facts))) "two designs")
      (is (= 2 (count (re-seq #"E12_Production> \." facts))) "two productions")
      (is (not (str/includes? facts "P33_used_specific_technique")) "no design is stated used by a production")
      (is (= 4 (count (re-seq #"P33_used_specific_technique" all))) "each pairing is a proposal")
      (is (every? #(str/ends-with? % "<urn:palomar:graph:proposals> .")
                  (filter #(re-find #"^\S+ <[^>]+P33_used_specific_technique>" %)
                          (str/split-lines (:output (convert/convert {:from :lido :to :nquads :source xml})))))
          "a dataset keeps each in the graph of its status")
      (is (empty? (violations xml)))))
  (testing "Linked Art states no part of a production LIDO does not give: a design is reported, never a part (post-audit 7, I1 review)"
    (doseq [design [["lido00224" "Rodin" "designer" "1890"] ["Entwurf" "Rodin" "designer" "1890"]]]
      (let [xml  (events-record "sculpture" design ["lido00007" "Rudier" "founder" "1902"] ["lido00007" "Susse" "founder" "1920"])
            la   (json/read-str (:output (convert/convert {:from :lido :to :linked-art :source xml})))
            pb   (get la "produced_by")]
        (is (not (str/includes? (json/write-str pb) "Rodin")) "the designer is in no production")
        (is (not (str/includes? (json/write-str pb) "1890")))
        (is (empty? (:unaccounted (fidelity/audit {:from :lido :to :linked-art :source xml}))) "and is reported"))))
  (testing "a proposal is below any promotion threshold, one production or many; and the design's unlinking is reported"
    (let [xml  (events-record "sculpture" ["lido00224" "Rodin" "designer" "1890"] ["lido00007" "Rudier" "founder" "1902"])
          recs (:records (convert/to-wemi :lido {} xml))
          p33  (filter #(= :crm/P33_used_specific_technique (:predicate %)) (mapcat :assertions recs))]
      (is (= [0.5] (map :confidence p33)))
      (is (some #(re-find #"linked to the object by no fact" (str (get-in % [:detail :message]) (:message %))) (mapcat :diagnostics recs))))))

(defn- triples-of [xml]
  (let [ts (nt/parse (:output (convert/convert {:from :lido :to :ntriples :source xml})))]
    (set (for [[_ p _] ts] (last (str/split (:iri p) #"/"))))))

(deftest what-the-record-does-not-say-is-not-stated
  (testing "a former repository is a former keeper (P49), not the current one (post-audit 7, I3)"
    (let [kept (fn [attr] (str/replace (events-record "sculpture" ["lido00007" "Rodin" "sculptor" "1902"])
                                       "</lido:titleWrap>"
                                       (str "</lido:titleWrap><lido:repositoryWrap><lido:repositorySet" attr "><lido:repositoryName><lido:legalBodyID>http://d-nb.info/gnd/1-1</lido:legalBodyID><lido:legalBodyName>"
                                            "<lido:appellationValue>Old Museum</lido:appellationValue></lido:legalBodyName></lido:repositoryName></lido:repositorySet></lido:repositoryWrap>")))
          xml  (kept " lido:type=\"former\"")
          ps   (triples-of xml)]
      (is (contains? (triples-of (kept "")) "P50_has_current_keeper") "a repository of no type is the current keeper")
      (is (contains? ps "P49_has_former_or_current_keeper"))
      (is (not (contains? ps "P50_has_current_keeper")))
      (is (empty? (violations xml)))
      (is (not (str/includes? (:output (convert/convert {:from :lido :to :linked-art :source xml})) "current_custodian")))
      (is (not (str/includes? (:output (convert/convert {:from :lido :to :marc21-object :source xml})) "tag=\"852\"")) "no current location in MARC")
      (doseq [to [:linked-art :marc21-object :ntriples :dc]]
        (is (empty? (:unaccounted (fidelity/audit {:from :lido :to to :source xml}))) (str "former keeper, " to)))
      (testing "a current repository that was formerly another stays current; a former one is told by a whole word"
        (is (contains? (triples-of (kept " lido:type=\"current (formerly Old Museum)\"")) "P50_has_current_keeper"))
        (is (contains? (triples-of (kept " lido:type=\"vorheriger Standort\"")) "P49_has_former_or_current_keeper")))))
  (testing "the object was not present at its own commissioning or planning: no P12i, reported"
    (doseq [t ["lido00226" "Commissioning" "Auftrag" "Planung"]]
      (let [xml (events-record "sculpture" [t "Musée" nil "1898"] ["lido00007" "Rodin" "sculptor" "1902"])
            ts  (nt/parse (:output (convert/convert {:from :lido :to :ntriples :source xml})))]
        (is (not (some (fn [[_ p o]] (and (str/ends-with? (:iri p) "P12i_was_present_at") (str/includes? (str (:iri o)) "E7_Activity"))) ts)) t)
        (doseq [to [:ntriples :linked-art]]
          (is (empty? (:unaccounted (fidelity/audit {:from :lido :to to :source xml}))) (str t " " to))))))
  (testing "a seller and a buyer took part; that they gave and received the title is proposed"
    (let [xml (str/replace (events-record "painting" ["lido00001" "Dealer" "seller" "1950"])
                           "</lido:eventActor>"
                           (str "</lido:eventActor><lido:eventActor><lido:actorInRole><lido:actor><lido:nameActorSet><lido:appellationValue>Museum</lido:appellationValue></lido:nameActorSet></lido:actor>"
                                "<lido:roleActor><lido:term>Käufer</lido:term></lido:roleActor></lido:actorInRole></lido:eventActor>"))
          ps  (triples-of xml)
          all (set (for [[_ p _] (nt/parse (:output (convert/convert {:from :lido :to :ntriples :source xml :with-proposals? true})))]
                     (last (str/split (:iri p) #"/"))))]
      ;; the role's words do not establish who held the title, and P22/P23
      ;; imply P14: both took part, the transfer is proposed (post-audit 8, K5)
      (is (contains? ps "P11_had_participant"))
      (is (not-any? ps ["P23_transferred_title_from" "P22_transferred_title_to" "P14_carried_out_by"]))
      (is (contains? all "P23_transferred_title_from"))
      (is (contains? all "P22_transferred_title_to"))
      (is (empty? (violations xml)))))
  (testing "a role is told by its whole words: an auction house for its client receives nothing; who bequeaths gives"
    (is (nil? (lido/title-role "auction house acting for their client")))
    (is (= :from (lido/title-role "Vererber")))
    (is (= :from (lido/title-role "Verkäufer; Auktionator")) "a seller who also auctioned gave the title")
    (is (nil? (lido/title-role "Verkäufer; Käufer")) "both: which is not said")))

(def ^:private ycba
  "Five records of the Yale Center for British Art (Data Source: Yale Center
   for British Art), which give authority ids by source and code."
  "test/fixtures/documentary/lido/ycba-oai-lido.xml")

(deftest an-id-given-by-its-source-and-code-is-an-iri
  (testing "`lido:source` AAT and `300033618` is http://vocab.getty.edu/aat/300033618; ULAN, TGN likewise (post-audit 8, K1)"
    (let [xml (slurp ycba)
          nt  (:output (convert/convert {:from :oai :opts {:metadata-prefix "lido"} :to :ntriples :source xml}))
          la  (map json/read-str (remove str/blank? (str/split-lines (:output (convert/convert {:from :oai :opts {:metadata-prefix "lido"} :to :linked-art :source xml})))))
          hmo (filter #(= "HumanMadeObject" (get % "type")) la)]
      (is (str/includes? nt "<http://vocab.getty.edu/aat/300033618>") "the work type painting")
      (is (= 5 (count hmo)))
      (is (every? #(some (fn [t] (= "http://vocab.getty.edu/aat/300033618" (get t "id"))) (get % "classified_as"))
                  (filter #(re-find #"(?i)painting" (str (get % "classified_as") (get % "_label"))) hmo))
          "a painting is classified as one")
      (is (= 4 (count (filter #(seq (get % "made_of")) hmo))) "its materials; a record that gives only its technique has none (K3)")
      (is (every? #(some (fn [g] (= "http://vocab.getty.edu/ulan/500303557" (get g "id"))) (get % "current_custodian")) hmo)
          "its custodian, the YCBA by its ULAN id")
      (is (not (str/includes? nt "ycba_term_")) "a local id of no known source is no IRI")
      (testing "a venue's name authority is the institution's, not a place: no node is both (K1 review)"
        (let [types (reduce (fn [m [s p o]] (if (str/ends-with? (:iri p) "#type") (update m (:iri s) (fnil conj #{}) (:iri o)) m)) {} (nt/parse nt))]
          (is (empty? (filter (fn [[_ ts]] (and (some #(str/ends-with? % "E53_Place") ts) (some #(str/ends-with? % "E74_Group") ts))) types)))))
      (testing "the keeper's id given by source and code is not reported as no IRI, it is written"
        (is (not-any? #(re-find #"keeper's id .* is no IRI" (str (get-in % [:detail :message]) (:message %)))
                      (:loss (convert/export-records :linked-art (:records (convert/to-wemi :oai {:metadata-prefix "lido"} xml)))))))
      (testing "and every value of these records is written or reported, whatever the target"
        (doseq [to [:ntriples :linked-art :dc :marc21-object :crm]]
          (is (empty? (:unaccounted (fidelity/audit {:from :oai :opts {:metadata-prefix "lido"} :to to :source xml}))) (str to)))))))

(deftest a-technique-is-no-material-wherever-its-type-is-given
  (testing "YCBA types a materials term on its conceptID, not on termMaterialsTech: a technique is still one (post-audit 8, K3)"
    (let [xml  (slurp ycba)
          la   (map json/read-str (remove str/blank? (str/split-lines (:output (convert/convert {:from :oai :opts {:metadata-prefix "lido"} :to :linked-art :source xml})))))
          made (set (for [o la t (get o "made_of")] (get t "id")))
          nt   (:output (convert/convert {:from :oai :opts {:metadata-prefix "lido"} :to :ntriples :source xml}))]
      (is (not (contains? made "http://vocab.getty.edu/aat/300054216")) "painting (technique) is no material")
      (is (contains? made "http://vocab.getty.edu/aat/300015050") "oil paint is")
      (is (re-find #"P32_used_general_technique> <http://vocab.getty.edu/aat/300054216>" nt) "the production used it"))))

(deftest an-unknown-source-or-a-code-of-the-wrong-form-is-no-iri
  (let [rec (fn [src code] (str "<lido:lido xmlns:lido=\"http://www.lido-schema.org\"><lido:lidoRecID>K-1</lido:lidoRecID><lido:descriptiveMetadata>"
                                "<lido:objectClassificationWrap><lido:objectWorkTypeWrap><lido:objectWorkType><lido:conceptID lido:source=\"" src "\">" code "</lido:conceptID>"
                                "<lido:term>painting</lido:term></lido:objectWorkType></lido:objectWorkTypeWrap></lido:objectClassificationWrap>"
                                "<lido:objectIdentificationWrap><lido:titleWrap><lido:titleSet><lido:appellationValue>T</lido:appellationValue></lido:titleSet></lido:titleWrap></lido:objectIdentificationWrap>"
                                "</lido:descriptiveMetadata></lido:lido>"))
        nt  #(:output (convert/convert {:from :lido :to :ntriples :source %}))]
    (is (str/includes? (nt (rec "aat" "300033618")) "aat/300033618") "the source in any case")
    (is (str/includes? (nt (rec "http://vocab.getty.edu/aat" "300033618")) "aat/300033618") "or as its URI")
    (is (not (str/includes? (nt (rec "AAT" "painting")) "vocab.getty.edu")) "an AAT id is a number")
    (is (str/includes? (nt (rec "GND" "4113779-6")) "d-nb.info/gnd/4113779-6"))
    (is (str/includes? (nt (rec "GND" "118540238")) "d-nb.info/gnd/118540238"))
    (is (not (str/includes? (nt (rec "GND" "12")) "d-nb.info")) "a GND id has its form")
    (is (not (str/includes? (nt (rec "Museum X" "4711")) "4711>")) "an unknown source names no IRI")))

(defn- doings-by-name
  "`{name #{predicate…}}`: what each named actor is linked to its events by, in N-Triples `nt`."
  [nt]
  (let [ts    (nt/parse nt)
        lit   (into {} (keep (fn [[s p o]] (when (str/ends-with? (:iri p) "P190_has_symbolic_content") [(:iri s) (:lit o)])) ts))
        app   (into {} (keep (fn [[s p o]] (when (str/ends-with? (:iri p) "P1_is_identified_by") [(:iri s) (:iri o)])) ts))
        named (fn [iri] (or (lit iri) (lit (app iri))))
        ps    #{"P14_carried_out_by" "P11_had_participant" "P15_was_influenced_by"}]
    (reduce (fn [m [_ p o]] (let [k (last (str/split (:iri p) #"/"))]
                              (if (and (ps k) (named (:iri o))) (update m (named (:iri o)) (fnil conj #{}) k) m)))
            {} ts)))

(deftest an-attribution-qualifier-says-who-made-it
  (testing "after, follower of, formerly: never the maker; print made by: the maker; text by, published by: took part (post-audit 8, K2)"
    (let [xml (slurp ycba)
          conv #(:output (convert/convert {:from :oai :opts {:metadata-prefix "lido"} :to % :source xml}))
          by   (doings-by-name (conv :ntriples))
          dc   (conv :dc)
          creators (set (map second (re-seq #"<dc:creator>([^<]*)</dc:creator>" dc)))]
      (is (= #{"P15_was_influenced_by"} (by "Thomas Phillips")) "a print after Phillips was influenced by him")
      (is (= #{"P14_carried_out_by"} (by "Luigi Schiavonetti")) "who made the print carried it out")
      (is (= #{"P11_had_participant"} (by "Robert Blair")) "the text's author took part")
      (is (= #{"P11_had_participant"} (by "Robert Hartley Cromek")) "the publisher took part")
      (is (= #{"P15_was_influenced_by"} (by "John Varley I")) "a follower's work was influenced by Varley, not made by him")
      (is (nil? (by "Constable, John, 1776-1837")) "a former attribution is stated nowhere as a fact")
      (is (some #(str/includes? % "Schiavonetti") creators))
      (is (not-any? #(re-find #"Phillips|Varley|Constable, John|Blair|Cromek" %) creators) "only the maker is a creator")
      (let [la   (map json/read-str (remove str/blank? (str/split-lines (conv :linked-art))))
            prod (some #(when (re-find #"Schiavonetti" (json/write-str (get % "produced_by"))) (get % "produced_by")) la)]
        (is (re-find #"Phillips" (json/write-str (get prod "influenced_by"))) "Linked Art: influenced_by")
        (is (not (re-find #"Phillips" (json/write-str (get prod "carried_out_by"))))))
      (testing "the former attribution is kept in the history, superseded"
        (is (str/includes? (conv :prov) "superseded")))
      (testing "MARC: only the maker is a main or added creator; the one a print is after says so"
        (let [marc (conv :marc21-object)]
          (is (re-find #"tag=\"100\"[^>]*>\s*<subfield code=\"a\">Luigi Schiavonetti" marc))
          (is (re-find #"<subfield code=\"a\">Thomas Phillips</subfield>(?:\s*<subfield code=\"[d0]\">[^<]*</subfield>)*\s*<subfield code=\"e\">after</subfield>" marc))))
      (doseq [to [:ntriples :linked-art :dc :marc21-object]]
        (is (empty? (:unaccounted (fidelity/audit {:from :oai :opts {:metadata-prefix "lido"} :to to :source xml}))) (str to)))))
  (testing "the qualifiers of CDWA and their German and French counterparts (K2 review)"
    (doseq [[q at] [["copy after" :influence] ["Kopie nach" :influence] ["after (?)" :influence] ["possibly after" :influence]
                    ["engraved after" :influence] ["nach dem Original von" :influence] ["copyist of" :influence] ["d'après" :influence]
                    ["studio" :related] ["workshop" :related] ["assistant to" :related] ["atelier of" :related] ["student of" :related]
                    ["Umfeld" :related] ["Kreis um" :related] ["Richtung des" :related] ["Werkstattkopie" :related] ["suiveur de" :related]
                    ["formerly attributed to" :former] ["previously attributed to" :former] ["attributed to workshop of" :related]
                    ["attributed to" :uncertain] ["zugeschrieben" :uncertain] ["?" :uncertain]
                    ["Print made by" :maker-role] ["Published by" :other-role]
                    ["Landscape by" :maker-role] ["figures by" :maker-role] ["and stag possibly by" :uncertain]
                    ["gesichert" nil] ["nach schriftlicher Mitteilung" nil] ["previously unknown" nil] ["secure attribution" nil]]]
      (is (= at (lido/attribution q)) q)))
  (testing "a maker's qualifier outweighs a passive role in every target"
    (let [xml (str/replace (events-record "print" ["lido00007" "Engraver" "commissioner" "1800"])
                           "</lido:actor>" "</lido:actor><lido:attributionQualifierActor>Print made by</lido:attributionQualifierActor>")]
      (is (= #{"P14_carried_out_by"} (get (doings-by-name (:output (convert/convert {:from :lido :to :ntriples :source xml}))) "Engraver")))
      (is (str/includes? (:output (convert/convert {:from :lido :to :dc :source xml})) "<dc:creator>Engraver"))))
  (testing "attributed to: the maker is proposed, not stated"
    (let [xml (str/replace (events-record "painting" ["lido00007" "Rembrandt" nil "1650"])
                           "</lido:actor>" "</lido:actor><lido:attributionQualifierActor>attributed to</lido:attributionQualifierActor>")
          facts (:output (convert/convert {:from :lido :to :ntriples :source xml}))
          all   (:output (convert/convert {:from :lido :to :ntriples :source xml :with-proposals? true}))]
      (is (nil? (get (doings-by-name facts) "Rembrandt")))
      (is (= #{"P14_carried_out_by"} (get (doings-by-name all) "Rembrandt")))
      (is (not (str/includes? (:output (convert/convert {:from :lido :to :dc :source xml})) "<dc:creator>Rembrandt"))))))

(def ^:private framed
  "A painting measured twice: the image, of no extent stated, and its frame."
  (str "<lido:lido xmlns:lido=\"http://www.lido-schema.org\"><lido:lidoRecID>F-1</lido:lidoRecID><lido:descriptiveMetadata xml:lang=\"en\">"
       "<lido:objectClassificationWrap><lido:objectWorkTypeWrap><lido:objectWorkType><lido:term>painting</lido:term></lido:objectWorkType></lido:objectWorkTypeWrap></lido:objectClassificationWrap>"
       "<lido:objectIdentificationWrap><lido:titleWrap><lido:titleSet><lido:appellationValue>T</lido:appellationValue></lido:titleSet></lido:titleWrap>"
       "<lido:objectMeasurementsWrap>"
       "<lido:objectMeasurementsSet><lido:objectMeasurements>"
       "<lido:measurementsSet><lido:measurementType>height</lido:measurementType><lido:measurementUnit>cm</lido:measurementUnit><lido:measurementValue>41.9</lido:measurementValue></lido:measurementsSet>"
       "<lido:extentMeasurements></lido:extentMeasurements></lido:objectMeasurements></lido:objectMeasurementsSet>"
       "<lido:objectMeasurementsSet><lido:objectMeasurements>"
       "<lido:measurementsSet><lido:measurementType>height</lido:measurementType><lido:measurementUnit>cm</lido:measurementUnit><lido:measurementValue>56.5</lido:measurementValue></lido:measurementsSet>"
       "<lido:extentMeasurements>Frame</lido:extentMeasurements></lido:objectMeasurements></lido:objectMeasurementsSet>"
       "</lido:objectMeasurementsWrap></lido:objectIdentificationWrap></lido:descriptiveMetadata></lido:lido>"))

(deftest a-dimension-says-what-it-measures
  (testing "the frame's height is the frame's, in CIDOC CRM and in Linked Art; a dimension of no stated extent says none (review of 2026-11-09, L4: YCBA)"
    (let [r     (first (:records (convert/to-wemi :lido {} framed)))
          as    (:assertions r)
          label (into {} (for [a as :when (= :crm/P190_has_symbolic_content (:predicate a))] [(:subject a) (:value a)]))
          value (into {} (for [a as :when (= :crm/P90_has_value (:predicate a))] [(:subject a) (:value a)]))
          types (fn [d] (set (for [a as :when (and (= d (:subject a)) (= :crm/P2_has_type (:predicate a)))]
                               (label (:value/target (:value a))))))]
      (is (= {"41.9" #{"height"} "56.5" #{"height" "Frame"}}
             (into {} (for [[d v] value] [(str v) (types d)])))))
    (let [obj  (first (filter #(= "HumanMadeObject" (get % "type"))
                              (map json/read-str (str/split-lines (:output (convert/convert {:from :lido :to :linked-art :source framed}))))))
          dims (into {} (for [d (get obj "dimension")] [(get d "value") (set (map #(get % "_label") (get d "classified_as")))]))]
      (is (= {41.9 #{"Height"} 56.5 #{"Height" "Frame"}} dims)))))

(defn- measured
  "A record of one objectMeasurements: `extents` (`[term lang]`…) and one dimension."
  [extents type value]
  (str "<lido:lido xmlns:lido=\"http://www.lido-schema.org\"><lido:lidoRecID>M-1</lido:lidoRecID><lido:descriptiveMetadata xml:lang=\"en\">"
       "<lido:objectClassificationWrap><lido:objectWorkTypeWrap><lido:objectWorkType><lido:term>height</lido:term></lido:objectWorkType></lido:objectWorkTypeWrap></lido:objectClassificationWrap>"
       "<lido:objectIdentificationWrap><lido:titleWrap><lido:titleSet><lido:appellationValue>T</lido:appellationValue></lido:titleSet></lido:titleWrap>"
       "<lido:objectMeasurementsWrap><lido:objectMeasurementsSet><lido:objectMeasurements>"
       "<lido:measurementsSet><lido:measurementType>" type "</lido:measurementType><lido:measurementUnit>cm</lido:measurementUnit><lido:measurementValue>" value "</lido:measurementValue></lido:measurementsSet>"
       (apply str (for [[t l] extents] (str "<lido:extentMeasurements" (when l (str " xml:lang=\"" l "\"")) ">" t "</lido:extentMeasurements>")))
       "</lido:objectMeasurements></lido:objectMeasurementsSet></lido:objectMeasurementsWrap></lido:objectIdentificationWrap></lido:descriptiveMetadata></lido:lido>"))

(defn- rid-of [xml] (:id (first (:records (convert/to-wemi :lido {} xml)))))

(defn- dimension-types
  "`[crm la]`: the P2 type ids of the record's dimension in CIDOC CRM and the
   classified_as ids of its Linked Art dimension."
  [xml]
  (let [as  (:assertions (first (:records (convert/to-wemi :lido {} xml))))
        d   (some #(when (= :crm/P43_has_dimension (:predicate %)) (:value/target (:value %))) as)
        obj (first (filter #(= "HumanMadeObject" (get % "type"))
                           (map json/read-str (str/split-lines (:output (convert/convert {:from :lido :to :linked-art :source xml}))))))]
    [(set (for [a as :when (and (= d (:subject a)) (= :crm/P2_has_type (:predicate a)))] (:value/target (:value a))))
     (set (for [dm (get obj "dimension") t (get dm "classified_as")] (get t "id")))]))

(deftest an-extent-is-a-part-of-its-own
  (testing "what a dimension measures is its own node, the same in both views; a placeholder names no part; English first; a value that is no number keeps it in its note (L4 review)"
    (let [xml      (measured [["Frame"]] "height" "5")
          [crm la] (dimension-types xml)
          frame    (lido/extent-node (rid-of xml) "Frame")]
      (is (contains? crm frame))
      (is (contains? la (export/entity-iri frame)) "the Linked Art type is the CIDOC CRM node"))
    (let [[crm _] (dimension-types (measured [["height"]] "height" "5"))]
      (is (= 2 (count crm)) "an extent named as a measurement type is not merged with it"))
    (let [[crm la] (dimension-types (measured [["Dimensions"]] "Höhe" "5"))]
      (is (= 1 (count crm)) "\"Dimensions\" is no part: only the measurement type")
      (is (empty? la)))
    (let [xml     (measured [["Blatt" "de"] ["Sheet" "en"]] "height" "5")
          [crm _] (dimension-types xml)]
      (is (contains? crm (lido/extent-node (rid-of xml) "Sheet"))))
    (let [as (:assertions (first (:records (convert/to-wemi :lido {} (measured [["Frame"]] "height" "ca. 5")))))]
      (is (some #(= "Frame: height: ca. 5 cm" (:value %)) as)))))

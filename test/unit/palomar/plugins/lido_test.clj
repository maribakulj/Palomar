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
       "<lido:eventWrap><lido:eventSet><lido:event><lido:eventType><lido:conceptID>http://terminology.lido-schema.org/" code "</lido:conceptID>"
       (when term (str "<lido:term>" term "</lido:term>")) "</lido:eventType>"
       (when actor (str "<lido:eventActor><lido:actorInRole><lido:actor><lido:nameActorSet><lido:appellationValue>" actor
                        "</lido:appellationValue></lido:nameActorSet></lido:actor></lido:actorInRole></lido:eventActor>"))
       "</lido:event></lido:eventSet></lido:eventWrap></lido:descriptiveMetadata></lido:lido>"))

(def ^:private sch (delay (onto/schema onto/vendored)))

(defn- violations [xml]
  (let [out (:output (convert/convert {:from :lido :to :ntriples :source xml}))]
    (onto/violations @sch (for [[s p o] (nt/parse out)] [(nt/term-key s) (:iri p) o]))))

(deftest every-event-respects-cidoc-crm
  (testing "an event carried out by someone is an activity, whatever its type (post-audit 4, F1)"
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
    (testing "in Linked Art the creators carry out the production, each other participant a part of it in its role"
      (let [hmo (first (map json/read-str (str/split-lines (:output (convert/convert {:from :lido :to :linked-art :source xml})))))]
        (is (= #{"Rodin" "Anonyme"} (set (map #(get % "_label") (get-in hmo ["produced_by" "carried_out_by"])))))
        (is (= #{"Rudier" "Musée"} (set (for [p (get-in hmo ["produced_by" "part"]) a (get p "carried_out_by")] (get a "_label")))))))))

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
        (is (= #{"Anonym" "Rodin, Auguste" "Roche, Odilon"}
               (set (keep #(when (and (= "draughtsman" (:role %)) (= "creator" (:responsibility %))) (:name %)) actors)))))
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

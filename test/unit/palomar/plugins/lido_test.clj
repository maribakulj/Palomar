(ns palomar.plugins.lido-test
  "LIDO museum objects (ADR 0052): events keep their own parts, LIDO 1.1
   terminology is read, keepers keep their own ids, and values that are no
   number or no four-digit year do not break the export."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.convert :as convert]
            [palomar.fidelity :as fidelity]))

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
          ev (some #(second (re-find #"^(<[^>]+>) <[^>]+rdf-syntax-ns#type> <[^>]+E5_Event>" %)) lines)]
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
  (doseq [to [:linked-art :ntriples :crm :dc]]
    (is (empty? (:unaccounted (fidelity/audit {:from :lido :to to :source lido-1-1}))) (str to))))

(ns palomar.plugins.lido-rules-test
  "Who did what in an event, and what a made object shows, written as rules
   (post-audit 8, rules study): against the code that computed it, on every
   LIDO corpus, and on the cases the code got wrong."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.convert :as convert]
            [palomar.fidelity :as fidelity]
            [palomar.plugins.lido-rules :as lido-rules]
            [palomar.plugins.lido-test]
            [palomar.plugins.lrmoo.ntriples :as nt]
            [palomar.runtime :as runtime]))

(def ^:private corpora
  [[:lido {} "test/fixtures/documentary/lido/staedel-lido-wrap.xml"]
   [:oai {:metadata-prefix "lido"} "test/fixtures/documentary/lido/staedel-oai-lido-graphics.xml"]
   [:oai {:metadata-prefix "lido"} "test/fixtures/documentary/lido/staedel-oai-lido-sample.xml"]
   [:oai {:metadata-prefix "lido"} "test/fixtures/documentary/lido/museum-digital-hessen-lido.xml"]
   [:oai {:metadata-prefix "lido"} "test/fixtures/documentary/lido/museum-digital-westfalen-lido.xml"]
   ;; attribution qualifiers and ids by source and code (post-audit 8, K1, K2)
   [:oai {:metadata-prefix "lido"} "test/fixtures/documentary/lido/ycba-oai-lido.xml"]])

(def ^:private actor-ps
  #{:crm/P14_carried_out_by :crm/P11_had_participant :crm/P22_transferred_title_to :crm/P23_transferred_title_from
    :crm/P15_was_influenced_by})

(defn- ruled
  "The assertions the rules produce on `record` (some the code made already,
   which the merge of a phase keeps once)."
  [record]
  (distinct (keep #(when (= :assertion (:kind %)) (:value %)) (:productions (runtime/run-phase record lido-rules/compiled :infer)))))

(defn- doings
  "`#{[event predicate status name]}` of `assertions`, and how many agents."
  [assertions]
  (let [nm (into {} (for [a assertions :when (= :crm/P190_has_symbolic_content (:predicate a))] [(:subject a) (:value a)]))
        as (filter #(actor-ps (:predicate %)) assertions)]
    {:doings (set (for [a as] [(:subject a) (:predicate a) (:status a) (nm (get-in a [:value :value/target]))]))
     :agents (count (distinct (map #(get-in % [:value :value/target]) as)))}))

(defn- records [xml] (:records (convert/to-wemi :lido {} xml)))

(def ^:private events-record @#'palomar.plugins.lido-test/events-record)

(deftest the-rules-do-what-the-code-did
  (testing "on every LIDO corpus, each actor of each event did what the code said, the same agents"
    (doseq [[from opts f] corpora
            r (:records (convert/to-wemi from opts (slurp f)))]
      (is (= (doings (:assertions r)) (doings (ruled r))) (str (:id r))))))

(deftest two-actors-of-one-name-are-two
  (testing "the maker in 1850 and the restorer in 1920, both Jean Martin, no authority: two people (post-audit 8)"
    (let [xml (events-record "painting" ["lido00007" "Jean Martin" "painter" "1850"] ["lido00034" "Jean Martin" "restorer" "1920"])
          as  (ruled (first (records xml)))]
      (is (= 2 (:agents (doings as))))
      (is (= 2 (count (filter #(= "Jean Martin" (:value %)) as))) "each named")))
  (testing "an authority says they are one"
    (let [xml (-> (events-record "painting" ["lido00007" "Jean Martin" "painter" "1850"] ["lido00034" "Jean Martin" "restorer" "1920"])
                  (str/replace "<lido:actor><lido:nameActorSet>" "<lido:actor><lido:actorID>http://d-nb.info/gnd/1-1</lido:actorID><lido:nameActorSet>"))]
      (is (= 1 (:agents (doings (ruled (first (records xml))))))))))

(deftest a-role-is-read-in-its-vocabulary
  (testing "a commissioner took part; a seller took part and is only proposed to have given up the title"
    (let [xml (str/replace (events-record "painting" ["lido00001" "Dealer" "seller" "1950"])
                           "</lido:eventActor>"
                           (str "</lido:eventActor><lido:eventActor><lido:actorInRole><lido:actor><lido:nameActorSet><lido:appellationValue>Museum</lido:appellationValue></lido:nameActorSet></lido:actor>"
                                "<lido:roleActor><lido:term>commissioner</lido:term></lido:roleActor></lido:actorInRole></lido:eventActor>"))
          ds  (set (map (fn [[_ p s n]] [p s n]) (:doings (doings (ruled (first (records xml)))))))]
      (is (contains? ds [:crm/P11_had_participant :asserted "Dealer"]))
      (is (contains? ds [:crm/P23_transferred_title_from :proposed "Dealer"]))
      (is (contains? ds [:crm/P11_had_participant :asserted "Museum"]))
      (is (not-any? #(= :crm/P14_carried_out_by (first %)) ds)))))

(defn- subject-xml
  "A painting with one subject concept, typed `type` (or not), its URI `uri`."
  [type uri label]
  (str/replace (events-record "painting" ["lido00007" "A" nil "1900"])
               "<lido:eventWrap>"
               (str "<lido:objectRelationWrap><lido:subjectWrap><lido:subjectSet><lido:subject" (when type (str " lido:type=\"" type "\"")) ">"
                    "<lido:subjectConcept>" (when uri (str "<lido:conceptID>" uri "</lido:conceptID>")) "<lido:term>" label "</lido:term></lido:subjectConcept>"
                    "</lido:subject></lido:subjectSet></lido:subjectWrap></lido:objectRelationWrap><lido:eventWrap>")))

(defn- shown [xml]
  (set (keep #(#{:crm/P62_depicts :crm/P129_is_about} (:predicate %)) (ruled (first (records xml))))))

(deftest what-a-work-shows-is-not-what-it-means
  (testing "a described or identified subject is depicted; an interpreted one is what the work is about (post-audit 8)"
    (is (= #{:crm/P62_depicts} (shown (subject-xml "description" nil "woman"))))
    (is (= #{:crm/P62_depicts} (shown (subject-xml "identification" nil "Saint Jerome"))))
    (is (= #{:crm/P129_is_about} (shown (subject-xml "interpretation" nil "melancholy")))))
  (testing "a subject of no type: depicted when a vocabulary of images names it, else only what the work is about"
    (is (= #{:crm/P62_depicts} (shown (subject-xml nil "http://iconclass.org/31A231" "woman"))))
    (is (= #{:crm/P129_is_about} (shown (subject-xml nil "http://d-nb.info/gnd/4037758-1" "Melancholie"))))
    (is (= #{:crm/P129_is_about} (shown (subject-xml nil nil "woman"))))))

(defn- nt-of [xml] (nt/parse (:output (convert/convert {:from :lido :to :ntriples :source xml}))))

(deftest the-crm-and-linked-art-say-the-same
  (testing "an authority's node is its IRI, in the CRM view the rules write (post-audit 8, K5)"
    (let [xml (str/replace (events-record "painting" ["lido00007" "Rodin" "painter" "1890"])
                           "<lido:actor><lido:nameActorSet>" "<lido:actor><lido:actorID>http://d-nb.info/gnd/118603426</lido:actorID><lido:nameActorSet>")]
      (is (some (fn [[_ p o]] (and (str/ends-with? (:iri p) "P14_carried_out_by") (= "http://d-nb.info/gnd/118603426" (:iri o)))) (nt-of xml)))))
  (testing "a commissioner took part in the CRM view; Linked Art does not say it carried the production out"
    (let [xml (str/replace (events-record "sculpture" ["lido00007" "Rodin" "sculptor" "1902"])
                           "</lido:eventActor>"
                           (str "</lido:eventActor><lido:eventActor><lido:actorInRole><lido:actor><lido:nameActorSet><lido:appellationValue>Musée</lido:appellationValue></lido:nameActorSet></lido:actor>"
                                "<lido:roleActor><lido:term>commissioner</lido:term></lido:roleActor></lido:actorInRole></lido:eventActor>"))
          la  (json/read-str (:output (convert/convert {:from :lido :to :linked-art :source xml})))]
      (is (not (str/includes? (json/write-str (get la "produced_by")) "Musée")))
      (is (empty? (:unaccounted (fidelity/audit {:from :lido :to :linked-art :source xml}))) "and reports it")))
  (testing "a local actor is one node in both views"
    (let [xml (events-record "painting" ["lido00007" "Jean Martin" "painter" "1850"])
          crm (some (fn [[_ p o]] (when (str/ends-with? (:iri p) "P14_carried_out_by") (:iri o))) (nt-of xml))
          la  (json/read-str (:output (convert/convert {:from :lido :to :linked-art :source xml})))]
      (is (= crm (get-in la ["produced_by" "carried_out_by" 0 "id"])))))
  (testing "what a work depicts it represents, what it is about it is about, in Linked Art as in the CRM view"
    (let [la (fn [type uri label] (first (filter #(= "VisualItem" (get % "type"))
                                                 (map json/read-str (remove str/blank? (str/split-lines (:output (convert/convert {:from :lido :to :linked-art :source (subject-xml type uri label)}))))))))]
      (is (= ["http://iconclass.org/31A231"] (map #(get % "id") (get (la nil "http://iconclass.org/31A231" "woman") "represents"))))
      (is (= ["http://d-nb.info/gnd/4037758-1"] (map #(get % "id") (get (la nil "http://d-nb.info/gnd/4037758-1" "Melancholie") "about"))))
      (is (= ["http://d-nb.info/gnd/4037758-1"] (map #(get % "id") (get (la "description" "http://d-nb.info/gnd/4037758-1" "Melancholie") "represents")))))))

(deftest what-an-actor-did-is-said-event-by-event
  (testing "one artist, by authority, influenced one production and carried out another: Linked Art says each (K5 review)"
    (let [xml (-> (events-record "print" ["lido00007" "Rubens" nil "1620"] ["lido00007" "Rubens" "engraver" "1630"])
                  (str/replace "<lido:actor><lido:nameActorSet>" "<lido:actor><lido:actorID>http://d-nb.info/gnd/118603426</lido:actorID><lido:nameActorSet>")
                  (str/replace-first "</lido:actor>" "</lido:actor><lido:attributionQualifierActor>after</lido:attributionQualifierActor>"))
          la  (json/read-str (:output (convert/convert {:from :lido :to :linked-art :source xml})))
          pb  (get la "produced_by")
          all (cons pb (get pb "part"))]
      (is (= 1 (count (filter #(seq (get % "influenced_by")) all))) "influenced one")
      (is (some #(some (fn [a] (= "http://d-nb.info/gnd/118603426" (get a "id"))) (get % "carried_out_by")) all) "carried out the other"))))

(deftest one-node-is-written-once
  (testing "an authority named twice in a production, after and as the text's author, influenced it once (K5 review)"
    (let [actor (fn [q] (str "<lido:eventActor><lido:actorInRole><lido:actor><lido:actorID>http://vocab.getty.edu/ulan/500017706</lido:actorID>"
                             "<lido:nameActorSet><lido:appellationValue>Cumberland</lido:appellationValue></lido:nameActorSet></lido:actor>"
                             "<lido:attributionQualifierActor>" q "</lido:attributionQualifierActor></lido:actorInRole></lido:eventActor>"))
          xml (str/replace (events-record "print" ["lido00007" "Engraver" "engraver" "1800"])
                           "</lido:eventActor>" (str "</lido:eventActor>" (actor "after") (actor "Text by")))
          la  (json/read-str (:output (convert/convert {:from :lido :to :linked-art :source xml})))]
      (is (every? #(= (count %) (count (distinct %)))
                  (for [x (cons (get la "produced_by") (get-in la ["produced_by" "part"])) k ["influenced_by" "carried_out_by"]] (get x k)))))))

(deftest the-review-cases-of-k5
  (testing "an event named an activity by a term in another language is one node, its actor on it (K5 review)"
    (let [xml (str/replace (events-record "painting" ["Show" "Kurator" "curator" "1950"])
                           "<lido:term>Show</lido:term>" "<lido:term xml:lang=\"en\">Show</lido:term><lido:term xml:lang=\"de\">Ausstellung</lido:term>")
          ts  (nt-of xml)
          typed (fn [k] (set (keep (fn [[s p o]] (when (and (str/ends-with? (:iri p) "#type") (str/ends-with? (:iri o) k)) (:iri s))) ts)))]
      (is (= 1 (count (typed "E7_Activity"))))
      (is (empty? (typed "E5_Event")) "no second, orphan node")))
  (testing "a donor took part, and gave up the title, proposed (K5 review)"
    (let [xml (events-record "painting" ["lido00001" "Collector" "donor" "1950"])
          all (:output (convert/convert {:from :lido :to :ntriples :source xml :with-proposals? true}))]
      (is (str/includes? all "P23_transferred_title_from"))
      (is (str/includes? all "P11_had_participant"))))
  (testing "an authority's IRI of any scheme names its node, in both views (K5 review)"
    (let [xml (str/replace (events-record "painting" ["lido00007" "Rodin" "painter" "1890"])
                           "<lido:actor><lido:nameActorSet>" "<lido:actor><lido:actorID>urn:nbn:de:101:1-2014</lido:actorID><lido:nameActorSet>")]
      (is (some (fn [[_ p o]] (and (str/ends-with? (:iri p) "P14_carried_out_by") (= "urn:nbn:de:101:1-2014" (:iri o)))) (nt-of xml))))))

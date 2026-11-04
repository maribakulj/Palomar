(ns palomar.plugins.lido-rules-test
  "Who did what in an event, and what a made object shows, written as rules
   (post-audit 8, rules study): against the code that computed it, on every
   LIDO corpus, and on the cases the code got wrong."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.convert :as convert]
            [palomar.plugins.lido-rules :as lido-rules]
            [palomar.plugins.lido-test]
            [palomar.runtime :as runtime]))

(def ^:private corpora
  [[:lido {} "test/fixtures/documentary/lido/staedel-lido-wrap.xml"]
   [:oai {:metadata-prefix "lido"} "test/fixtures/documentary/lido/staedel-oai-lido-graphics.xml"]
   [:oai {:metadata-prefix "lido"} "test/fixtures/documentary/lido/staedel-oai-lido-sample.xml"]
   [:oai {:metadata-prefix "lido"} "test/fixtures/documentary/lido/museum-digital-hessen-lido.xml"]
   [:oai {:metadata-prefix "lido"} "test/fixtures/documentary/lido/museum-digital-westfalen-lido.xml"]])

(def ^:private actor-ps
  #{:crm/P14_carried_out_by :crm/P11_had_participant :crm/P22_transferred_title_to :crm/P23_transferred_title_from})

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

(ns palomar.mentions-test
  "Agent mentions, one per occurrence (lot N1, ADR 0058): each format's
   occurrences read from themselves alone — two 700s that differ in every
   subfield, or where one lacks a subfield, never share one — subjects typed
   apart and left out by default, the local node the rest of Palomar mints,
   and ids that do not depend on other records."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.agent-dates :as dates]
            [palomar.mentions :as mentions]
            [palomar.model :as model]
            [palomar.plugins :as plug]
            [palomar.plugins.agents :as agents]
            [palomar.plugins.dc :as dc]
            [palomar.plugins.eac :as eac]
            [palomar.plugins.intermarc :as intermarc]
            [palomar.plugins.lido :as lido]
            [palomar.plugins.lido-rules :as lido-rules]
            [palomar.plugins.mapping :as mapping]
            [palomar.plugins.marc21 :as marc21]
            [palomar.plugins.mods :as mods]
            [palomar.plugins.oai :as oai]
            [palomar.plugins.unimarc :as unimarc]
            [palomar.reconcile :as reconcile]
            [palomar.runtime :as runtime]))

(defn- sf [& kvs] (apply str (for [[c v] (partition 2 kvs)] (str "<mxc:subfield code=\"" c "\">" v "</mxc:subfield>"))))

(defn- mxc-df [tag & kvs] (str "<mxc:datafield tag=\"" tag "\" ind1=\" \" ind2=\" \">" (apply sf kvs) "</mxc:datafield>"))

(defn- mxc [fmt ark & fields]
  (str "<mxc:record xmlns:mxc=\"info:lc/xmlns/marcxchange-v2\" format=\"" fmt "\" id=\"ark:/12148/" ark "\" type=\"Bibliographic\">"
       "<mxc:controlfield tag=\"001\">FRBN" ark "</mxc:controlfield>" (apply str fields) "</mxc:record>"))

(defn- intermarc-record [ark & fields] (first (intermarc/ingest (apply mxc "INTERMARC" ark fields) {})))

(def ^:private im
  "An INTERMARC record whose 700s differ in every subfield, the second
   lacking `$d` and `$4`, the third lacking `$m`; a 710, a 600 subject and an
   edition date."
  (delay (intermarc-record "cb1"
                           (mxc-df "100" "3" "11901063" "a" "Dumas" "m" "Alexandre" "d" "1802-1870" "4" "0070")
                           (mxc-df "260" "d" "1990")
                           (mxc-df "600" "a" "Napoléon" "m" "Ier" "d" "1769-1821")
                           (mxc-df "700" "a" "Hetzel" "m" "Pierre-Jules" "d" "1814-1886" "4" "0340")
                           (mxc-df "700" "a" "Férat" "m" "Jules")
                           (mxc-df "700" "a" "Aramis" "d" "1802-1870" "4" "0440")
                           (mxc-df "710" "a" "Club Verne" "d" "2010" "4" "0360"))))

(defn- by-name [ms] (into {} (map (juxt :name identity)) ms))

(deftest intermarc-occurrences-are-read-each-from-its-own-field
  (let [ms (mentions/mentions @im)
        m  (by-name ms)]
    (testing "the 100, each 700 and the 710, in document order; the 600 is a subject, left out"
      (is (= ["Dumas, Alexandre" "Hetzel, Pierre-Jules" "Férat, Jules" "Aramis" "Club Verne"] (map :name ms))))
    (testing "each 700 its own name, dates and role: never another's"
      (is (= ["1814-1886" "0340" "0340"] ((juxt (comp :text :dates) :role :relator) (m "Hetzel, Pierre-Jules"))))
      (is (= [nil nil nil] ((juxt :dates :role :relator) (m "Férat, Jules"))) "no $d, no $4: none, not its neighbour's")
      (is (= ["1802-1870" "0440"] ((juxt (comp :text :dates) :role) (m "Aramis"))) "no $m: the name is $a alone"))
    (testing "the dates are the parsed $d, its text as given"
      (is (= (dates/parse-life-dates "1814-1886") (:dates (m "Hetzel, Pierre-Jules")))))
    (testing "kinds and responsibilities by tag; a group's $d (a meeting) is no life"
      (is (= [:person :creator] ((juxt :kind :responsibility) (m "Dumas, Alexandre"))))
      (is (= [:person :contributor] ((juxt :kind :responsibility) (m "Férat, Jules"))))
      (is (= [:group :contributor nil "0360"] ((juxt :kind :responsibility :dates :role) (m "Club Verne")))))
    (testing "stable ids: the record, the tag, the occurrence among the record's fields of that tag"
      (is (= [[(:id @im) "100" 0] [(:id @im) "700" 0] [(:id @im) "700" 1] [(:id @im) "700" 2] [(:id @im) "710" 0]]
             (map :id ms)))
      (is (every? #(= (:id @im) (:record %)) ms)))
    (testing "the source: the field, its occurrence, its subfields and its evidence (the fragment)"
      (let [s (:source (m "Férat, Jules"))
            frag (some #(when (= (:fragment (first (:evidence s))) (:id %)) %) (:fragments @im))]
        (is (= [:intermarc "700" 1 [["a" "Férat"] ["m" "Jules"]]] ((juxt :format :field :occurrence :subfields) s)))
        (is (= {:tag "700" :occurrence 1} (select-keys (:locator frag) [:tag :occurrence])))))
    (testing "each agent mention carries its record's edition, typed for its own role"
      (is (= [[:publication "0340" 1990]] (map (juxt :kind :role (comp :from :date)) (:events (m "Hetzel, Pierre-Jules")))))
      (is (= [[:publication nil]] (map (juxt :kind :role) (:events (m "Férat, Jules"))))))
    (testing "the local node: align-agents' for the record and the name, a Group for a group"
      (is (= (model/mint-entity-id :crm/E21_Person [(:id @im) "Hetzel, Pierre-Jules"]) (:local (m "Hetzel, Pierre-Jules"))))
      (is (= (model/mint-entity-id :crm/E74_Group [(:id @im) "Club Verne"]) (:local (m "Club Verne")))))))

(deftest a-subject-is-typed-apart-and-left-out-by-default
  (let [subj (filter #(= :subject (:type %)) (mentions/mentions @im {:subjects? true}))]
    (is (= [["Napoléon, Ier" "1769-1821" [] [(:id @im) "600" 0]]] (map (juxt :name (comp :text :dates) :events :id) subj))
        "a subject carries no edition: it is not its subject's act")
    (is (nil? (:responsibility (first subj))))
    (is (not-any? #(= :subject (:type %)) (mentions/mentions @im)))))

(deftest the-local-node-is-the-one-align-agents-mints
  (testing "a proposal aligned on the record names the mention's local node"
    (let [r    (update @im :assertions conj (model/assertion {:subject (:id @im) :predicate :canon/agent :value "Hetzel, Pierre-Jules"}))
          m    (first (filter #(= "Hetzel, Pierre-Jules" (:name %)) (mentions/mentions r)))
          [r'] (reconcile/align-agents [r] [{:name "Hetzel, Pierre-Jules" :authority-id "http://example.org/hetzel" :score 1.0}])]
      (is (some #(= (:local m) (:id %)) (:entities r'))))))

(deftest a-mention-is-what-the-matcher-reads
  (let [m   (first (filter #(= "Hetzel, Pierre-Jules" (:name %)) (mentions/mentions @im)))
        res (reconcile/agent-candidates [m] [{:id "hetzel" :label "Hetzel, Pierre-Jules" :dates (dates/parse-life-dates "1814-1886")}
                                             {:id "fils" :label "Hetzel, Pierre-Jules" :dates (dates/parse-life-dates "1900-1980")}])]
    (is (= ["hetzel"] (map :authority-id (:proposals res))))
    (is (= ["fils"] (map :authority-id (:rejected res))) "its own dates reject the namesake")))

;; ---------------------------------------------------------------------------
;; MARC21
;; ---------------------------------------------------------------------------

(defn- marcxml [& fields]
  (str "<collection xmlns=\"http://www.loc.gov/MARC21/slim\"><record><leader>00000nam a2200000 a 4500</leader>"
       "<controlfield tag=\"001\">x1</controlfield>" (apply str fields) "</record></collection>"))

(defn- df [tag ind1 & kvs]
  (str "<datafield tag=\"" tag "\" ind1=\"" ind1 "\" ind2=\" \">"
       (apply str (for [[c v] (partition 2 kvs)] (str "<subfield code=\"" c "\">" v "</subfield>")))
       "</datafield>"))

(deftest marc21-occurrences-are-read-each-from-its-own-field
  (let [r  (first (marc21/ingest (marcxml (df "100" "1" "a" "Hugo, Victor," "d" "1802-1885." "e" "author." "4" "aut")
                                          (df "260" " " "c" "1990")
                                          (df "600" "1" "a" "Napoleon" "b" "I," "c" "Emperor of the French," "d" "1769-1821")
                                          (df "700" "1" "a" "Doré, Gustave," "d" "1832-1883," "e" "illustrator." "4" "ill")
                                          (df "700" "1" "a" "Smith, J." "q" "(John)" "4" "trl")
                                          (df "700" "3" "a" "Medici (Family)")
                                          (df "710" "2" "a" "Gallimard," "b" "Service des archives," "e" "publisher.")
                                          (df "711" "2" "a" "Congrès Hugo" "d" "(2002 :" "j" "organizer."))
                                 {}))
        ms (mentions/mentions r)
        m  (by-name ms)]
    (is (= ["Hugo, Victor," "Doré, Gustave," "Smith, J." "Medici (Family)" "Gallimard," "Congrès Hugo"] (map :name ms))
        "$a, as the canonical floor carries it")
    (testing "the role the term ($e, a meeting's $j) else the code ($4); the relator the code"
      (is (= ["author." "aut" "1802-1885."] ((juxt :role :relator (comp :text :dates)) (m "Hugo, Victor,"))))
      (is (= ["illustrator." "ill" "1832-1883,"] ((juxt :role :relator (comp :text :dates)) (m "Doré, Gustave,"))))
      (is (= ["trl" "trl" nil] ((juxt :role :relator :dates) (m "Smith, J."))) "no $e: the code; no $d: no dates")
      (is (= ["organizer." nil nil] ((juxt :role :relator :dates) (m "Congrès Hugo"))) "a meeting's $d is no life"))
    (testing "the name parts kept apart; the kinds by tag and indicator"
      (is (= {"q" "(John)"} (:name-parts (m "Smith, J."))))
      (is (= {"b" "Service des archives,"} (:name-parts (m "Gallimard,"))))
      (is (= [:person :family :group :group] (map (comp :kind m) ["Hugo, Victor," "Medici (Family)" "Gallimard," "Congrès Hugo"])))
      (is (= [:creator :contributor] (map (comp :responsibility m) ["Hugo, Victor," "Doré, Gustave,"]))))
    (testing "the subject 600, with its own parts, only on request"
      (is (= [["Napoleon" {"b" "I," "c" "Emperor of the French,"} :subject]]
             (map (juxt :name :name-parts :type) (filter #(= "600" (get-in % [:source :field])) (mentions/mentions r {:subjects? true}))))))
    (is (= [[(:id r) "700" 0] [(:id r) "700" 1] [(:id r) "700" 2]] (keep #(when (= "700" (get-in % [:source :field])) (:id %)) ms)))))

;; ---------------------------------------------------------------------------
;; UNIMARC
;; ---------------------------------------------------------------------------

(deftest unimarc-occurrences-pair-each-surname-with-its-own-forename
  (let [r  (first (unimarc/ingest (mxc "UNIMARC" "cb2"
                                       (mxc-df "700" "a" "Verne" "b" "Jules" "f" "1828-1905" "4" "070")
                                       (mxc-df "701" "a" "Hetzel" "b" "Pierre-Jules" "f" "1814-1886" "4" "340")
                                       (mxc-df "702" "a" "Riou" "4" "440")
                                       (mxc-df "702" "a" "Férat" "b" "Jules")
                                       (mxc-df "712" "a" "Hachette" "4" "650")
                                       (mxc-df "722" "a" "Rothschild" "f" "17..-...."))
                                  {}))
        m  (by-name (mentions/mentions r))]
    (is (= ["Verne, Jules" "Hetzel, Pierre-Jules" "Riou" "Férat, Jules" "Hachette" "Rothschild"] (map :name (mentions/mentions r)))
        "the floor joins no forename (the first $b of the record would go to every 7XX); each field its own")
    (is (= ["1828-1905" "070" :creator] ((juxt (comp :text :dates) :role :responsibility) (m "Verne, Jules"))))
    (is (= [nil "440" :contributor] ((juxt :dates :role :responsibility) (m "Riou"))))
    (is (= [nil nil] ((juxt :dates :role) (m "Férat, Jules"))))
    (is (= [:group :contributor] ((juxt :kind :responsibility) (m "Hachette"))))
    (is (= [:family "17..-...."] ((juxt :kind (comp :text :dates)) (m "Rothschild"))))
    (is (= [(:id r) "702" 1] (:id (m "Férat, Jules"))))))

;; ---------------------------------------------------------------------------
;; LIDO
;; ---------------------------------------------------------------------------

(def ^:private lido-xml
  (str "<lido:lido xmlns:lido=\"http://www.lido-schema.org\"><lido:lidoRecID>E-1</lido:lidoRecID><lido:descriptiveMetadata><lido:eventWrap>"
       "<lido:eventSet><lido:event><lido:eventType><lido:conceptID>http://terminology.lido-schema.org/eventType/production</lido:conceptID><lido:term>production</lido:term></lido:eventType>"
       "<lido:eventActor><lido:actorInRole><lido:actor lido:type=\"person\"><lido:actorID>http://vocab.getty.edu/ulan/500028095</lido:actorID>"
       "<lido:nameActorSet><lido:appellationValue>Rodin, Auguste</lido:appellationValue></lido:nameActorSet>"
       "<lido:vitalDatesActor><lido:earliestDate>1840</lido:earliestDate><lido:latestDate>1917</lido:latestDate></lido:vitalDatesActor></lido:actor>"
       "<lido:roleActor><lido:conceptID>http://vocab.getty.edu/aat/300025181</lido:conceptID><lido:term>sculptor</lido:term></lido:roleActor></lido:actorInRole></lido:eventActor>"
       "<lido:eventActor><lido:actorInRole><lido:actor lido:type=\"corporation\"><lido:nameActorSet><lido:appellationValue>Alexis Rudier</lido:appellationValue></lido:nameActorSet></lido:actor>"
       "<lido:roleActor><lido:term>founder</lido:term></lido:roleActor></lido:actorInRole></lido:eventActor>"
       "<lido:eventDate><lido:displayDate>1925</lido:displayDate></lido:eventDate></lido:event></lido:eventSet>"
       "<lido:eventSet><lido:event><lido:eventType><lido:conceptID>http://terminology.lido-schema.org/eventType/acquisition</lido:conceptID><lido:term>acquisition</lido:term></lido:eventType>"
       "<lido:eventActor><lido:actorInRole><lido:actor lido:type=\"person\"><lido:nameActorSet><lido:appellationValue>Rodin, Auguste</lido:appellationValue></lido:nameActorSet></lido:actor>"
       "</lido:actorInRole></lido:eventActor><lido:eventDate><lido:displayDate>1950</lido:displayDate></lido:eventDate></lido:event></lido:eventSet>"
       "</lido:eventWrap></lido:descriptiveMetadata></lido:lido>"))

(deftest a-lido-actor-is-its-occurrence-in-its-event
  (let [r  (first (:records (lido/importer {:validate? false} lido-xml)))
        ms (mentions/mentions r)
        [rodin rudier rodin2] ms]
    (is (= ["Rodin, Auguste" "Alexis Rudier" "Rodin, Auguste"] (map :name ms)))
    (testing "two occurrences of one name are two mentions, each with its own event, dates and ids"
      (is (= [:creation :activity :acquisition] (map (comp :kind first :events) ms)))
      (is (= ["1840-1917" nil nil] (map (comp :text :dates) ms)))
      (is (= [["http://vocab.getty.edu/ulan/500028095"] [] []] (map :ids ms)))
      (is (not= (:id rodin) (:id rodin2))))
    (testing "the role its terms, the relator its concept id, the kind its type"
      (is (= ["sculptor" "http://vocab.getty.edu/aat/300025181" :person :creator]
             ((juxt :role :relator :kind :responsibility) rodin)))
      (is (= ["founder" nil :group :participant] ((juxt :role :relator :kind :responsibility) rudier))))
    (testing "the id the record, the event and the position; the local node the rules' and Linked Art's"
      (is (= [(:id r) "actorInRole" (:event rodin) (:position rodin)] (:id rodin)))
      (is (= (lido-rules/agent-node (:id r) {:uri "http://vocab.getty.edu/ulan/500028095" :type "person"}) (:local rodin)))
      (is (= (lido-rules/agent-node (:id r) {:event (:event rudier) :position (:position rudier) :type "corporation"}) (:local rudier))))))

;; ---------------------------------------------------------------------------
;; EAC-CPF, Dublin Core, MODS
;; ---------------------------------------------------------------------------

(deftest an-eac-cpf-record-describes-one-agent
  (let [r  (first (:records (eac/importer {} (slurp "test/fixtures/documentary/eac-cpf/example_eac_cpf.xml"))))
        ms (mentions/mentions r)]
    (is (= [["Fictional Testing Center" :described :group [(:id r) "identity" 0]]] (map (juxt :name :type :kind :id) ms)))
    (is (= (eac/agent-node r) (:local (first ms))) "the projection's agent")))

(deftest dublin-core-and-mods-name-their-agents
  (let [d (first (:records (dc/importer {:record-id :t/dc1} (slurp "test/fixtures/documentary/dublin-core/w3c_dc_example1.xml"))))
        m (first (:records (mods/importer {} (slurp "test/fixtures/documentary/mods/loc_mods_book.xml"))))]
    (is (= [["Alan Kelsey" "creator" [:t/dc1 "dc:creator" 0]] ["Alan Kelsey, Ltd." "publisher" [:t/dc1 "dc:publisher" 0]]
            ["The Dublin Core Task Force" "contributor" [:t/dc1 "dc:contributor" 0]]]
           (map (juxt :name :role :id) (mentions/mentions d))))
    (is (= [["Alterman, Eric" nil [(:id m) "mods:name" 0]]] (map (juxt :name :role :id) (mentions/mentions m))))))

;; ---------------------------------------------------------------------------
;; Stable ids
;; ---------------------------------------------------------------------------

(deftest a-mention-does-not-depend-on-other-records
  (let [a  @im
        b  (intermarc-record "cb9" (mxc-df "700" "a" "Hetzel" "m" "Pierre-Jules" "d" "1814-1886" "4" "0340"))
        ab (mentions/batch [a b])
        ba (mentions/batch [b a])]
    (is (= (set ab) (set ba)) "the same mentions whatever the order")
    (is (= (mentions/mentions a) (filterv #(= (:id a) (:record %)) ba)))
    (testing "two records' same names are two mentions, two ids and two local nodes"
      (let [hs (filter #(= "Hetzel, Pierre-Jules" (:name %)) ab)]
        (is (= 2 (count (set (map :id hs)))))
        (is (= 2 (count (set (map :local hs)))))))))

(deftest no-occurrence-takes-another-s-subfield
  (testing "on every agent field of a real INTERMARC corpus, a mention's name, dates and role are its own field's"
    (let [recs (mapcat #(intermarc/ingest (slurp (str "test/fixtures/documentary/intermarc/sru/intermarcXchange/" %)) {})
                       ["bnf-sru-victor-hugo-50.xml" "bnf-sru-jules-verne-50.xml" "bnf-sru-alexandre-dumas-50.xml"])
          ms   (mentions/batch recs)]
      (is (= {"100" 90 "101" 1 "700" 52 "110" 27 "710" 29} (frequencies (map (comp :field :source) ms)))
          "the M2 eval's 90 + 52 person fields, a main performer, and the groups")
      (doseq [m ms
              :let [sfs (get-in m [:source :subfields])
                    own (set (keep (fn [[_ v]] (some-> v str/trim)) sfs))]]
        (is (every? own (remove nil? [(get-in m [:dates :text]) (:role m) (:relator m)])) (pr-str (:id m)))
        (is (every? own (str/split (:name m) #", ")) (pr-str (:id m)))))))

;; ---------------------------------------------------------------------------
;; Review of lot N1
;; ---------------------------------------------------------------------------

(defn- normalized
  "`records` through `plugin`'s mapping (the canonical floor, as a conversion
   gives it)."
  [plugin records]
  (let [reg (plug/register plug/empty-registry plugin)
        c   (mapping/compile-mappings (plug/all-mappings reg) (plug/effective-transforms reg))]
    (mapv #(:record (runtime/run-phase % c :normalize)) records)))

(defn- aligned-local
  "The local node `align-agents` mints in `r` for proposal `p`: the entity
   it adds that is not an authority."
  [r p]
  (let [[r'] (reconcile/align-agents [r] [p])
        old  (set (map :id (:entities r)))]
    (first (for [e (:entities r') :when (and (not (old (:id e))) (not= :reconcile/authority (:kind e)))] (:id e)))))

(def ^:private local-cases
  (delay
    (let [m21 (first (normalized marc21/plugin
                                 (marc21/ingest (marcxml (df "100" "1" "a" "Hugo, Victor," "d" "1802-1885.")
                                                         (df "700" "3" "a" "Medici (Family)")
                                                         (df "710" "2" "a" "Gallimard,"))
                                                {})))
          um  (first (normalized unimarc/plugin
                                 (unimarc/ingest (mxc "UNIMARC" "cb3" (mxc-df "712" "a" "Hachette" "4" "650")) {})))
          im1 (first (normalized intermarc/plugin [(intermarc-record "cb4" (mxc-df "100" "a" "Dumas" "m" "Alexandre" "d" "1802-1870"))]))
          li  (normalized lido/plugin (:records (oai/import-response "lido" (slurp "test/fixtures/documentary/lido/ycba-oai-lido.xml"))))
          d   (normalized dc/plugin (:records (dc/importer {:record-id :t/dc1} (slurp "test/fixtures/documentary/dublin-core/w3c_dc_example1.xml"))))
          mo  (normalized mods/plugin (:records (mods/importer {} (slurp "test/fixtures/documentary/mods/loc_mods_book.xml"))))]
      (concat [im1 m21 um] li d mo))))

(deftest one-local-node-rule-for-mentions-and-align-agents
  (testing "wherever align-agents reaches a mention (its name on the floor), it mints the mention's :local, given the mention, with or without the authority's entity type"
    (let [seen (atom #{})]
      (doseq [r @local-cases
              :let [floor (set (map :name (agents/occurrences r)))]
              m (mentions/mentions r)
              :when (floor (:name m))
              et [nil "corporateBody" "person"]
              :let [cand {:id "http://example.org/a1" :label (:name m) :entity-type et}
                    p    (first (:proposals (reconcile/agent-candidates [m] [cand])))
                    got  (aligned-local r p)]]
        (swap! seen conj [(mentions/source-format r) (:kind m)])
        (if (or (:kind m) (nil? et) (= :lido (mentions/source-format r)))
          (is (= (:local m) got) (pr-str [(:id m) et]))
          (is (= (mentions/local-node r (:name m) {:entity-type et}) got)
              "a mention stating no kind takes the authority's: a Group for a corporate body")))
      (is (every? @seen [[:intermarc :person] [:marc21 :person] [:marc21 :family] [:marc21 :group] [:unimarc :group]
                         [:lido :person] [:dc nil] [:mods nil]])
          (pr-str @seen))))
  (testing "a bare name proposal (no mention): the authority's entity type, as before"
    (let [r (second @local-cases)]
      (is (= (model/mint-entity-id :crm/E74_Group [(:id r) "Hugo, Victor,"])
             (aligned-local r {:name "Hugo, Victor," :authority-id "http://example.org/a1" :score 1.0 :entity-type "corporateBody"})))
      (is (= (model/mint-entity-id :crm/E21_Person [(:id r) "Hugo, Victor,"])
             (aligned-local r {:name "Hugo, Victor," :authority-id "http://example.org/a1" :score 1.0})))))
  (testing "a LIDO actor's node is the rules' and Linked Art's, whoever mints it"
    (let [r (first (filter #(= :lido (mentions/source-format %)) @local-cases))
          m (first (filter #(= "Dominic Serres" (:name %)) (mentions/mentions r)))]
      (is (= (:local m) (aligned-local r {:name (:name m) :authority-id "http://example.org/a1" :score 1.0})))))
  (testing "an EAC-CPF record's local node is its projection's agent"
    (let [r (first (:records (eac/importer {} (slurp "test/fixtures/documentary/eac-cpf/example_eac_cpf.xml"))))]
      (is (= (eac/agent-node r) (mentions/local-node r "Fictional Testing Center") (:local (first (mentions/mentions r))))))))

(deftest intermarc-fields-read-as-the-bnf-sheets-define-them
  (let [r  (intermarc-record "cb5"
                             (mxc-df "100" "a" "Rothschild" "m" "famille")
                             (mxc-df "260" "d" "1990")
                             (mxc-df "701" "a" "Callas" "m" "Maria" "d" "1923-1977" "4" "1060")
                             (mxc-df "702" "a" "Dior" "m" "Christian" "4" "2086")
                             (mxc-df "703" "a" "Gracq" "m" "Julien" "d" "1910-2007" "4" "0920")
                             (mxc-df "700" "a" "Owner" "m" "Old" "4" "4010")
                             (mxc-df "711" "a" "Orchestre de Paris" "4" "1230")
                             (mxc-df "712" "a" "Atelier X" "4" "2010")
                             (mxc-df "720" "a" "Pauvert" "m" "Jean-Jacques" "4" "3250")
                             (mxc-df "721" "a" "Diffuseur" "m" "D." "4" "3210")
                             (mxc-df "722" "a" "Producer" "m" "P." "4" "3160")
                             (mxc-df "723" "a" "Decca")
                             (mxc-df "730" "a" "Vogue" "4" "3250")
                             (mxc-df "731" "a" "INA" "4" "3210")
                             (mxc-df "732" "a" "France-Culture" "4" "3160"))
        ;; the family indicator is the second, 5 (INTERMARC (B) 100/700/701)
        r  (update r :fragments (partial mapv #(if (= "100" (get-in % [:locator :tag])) (assoc-in % [:locator :ind2] "5") %)))
        ms (mentions/mentions r)
        m  (by-name ms)]
    (testing "every agent field read, with its kind and responsibility"
      (is (= {"Rothschild, famille" [:family :creator] "Callas, Maria" [:person :performer] "Dior, Christian" [:person :collaborator]
              "Orchestre de Paris" [:group :performer] "Atelier X" [:group :collaborator]
              "Pauvert, Jean-Jacques" [:person :publisher] "Diffuseur, D." [:person :distributor] "Producer, P." [:person :producer]
              "Decca" [:group :label] "Vogue" [:group :publisher] "INA" [:group :distributor] "France-Culture" [:group :producer]}
             (update-vals m (juxt :kind :responsibility)))))
    (testing "703, and any 7XX whose function is a copy's (4010-4990, an owner), are provenance: left out by default"
      (is (not-any? #{"Gracq, Julien" "Owner, Old"} (map :name ms)))
      (let [prov (filter #(= :provenance (:type %)) (mentions/mentions r {:provenance? true}))]
        (is (= [["Gracq, Julien" "1910-2007" []] ["Owner, Old" nil []]] (map (juxt :name (comp :text :dates) :events) prov))
            "a provenance carries no publication event: it is a copy's")))))

(deftest unimarc-provenance-fields-are-a-copy-s
  (let [r (first (unimarc/ingest (mxc "UNIMARC" "cb6"
                                      (mxc-df "210" "d" "1890")
                                      (mxc-df "700" "a" "Hugo" "b" "Victor" "4" "070")
                                      (mxc-df "703" "a" "Chenay" "b" "Julie" "f" "1822-1905" "4" "390")
                                      (mxc-df "713" "a" "Maison de Victor Hugo" "4" "390"))
                                 {}))]
    (is (= ["Hugo, Victor"] (map :name (mentions/mentions r))))
    (is (= [["Chenay, Julie" :person :provenance []] ["Maison de Victor Hugo" :group :provenance []]]
           (map (juxt :name :kind :type :events) (filter #(= :provenance (:type %)) (mentions/mentions r {:provenance? true})))))))

(deftest an-authority-record-describes-its-heading-and-names-no-agent
  (let [ia (first (intermarc/ingest (str "<mxc:record xmlns:mxc=\"info:lc/xmlns/marcxchange-v2\" format=\"INTERMARC\" id=\"ark:/12148/cbA\" type=\"Authority\">"
                                         "<mxc:controlfield tag=\"001\">FRBNFA</mxc:controlfield>"
                                         (mxc-df "100" "a" "Hugo" "m" "Victor" "d" "1802-1885")
                                         (mxc-df "400" "a" "Hugo" "m" "V.")
                                         (mxc-df "600" "a" "Notes" "m" "Bio")
                                         (mxc-df "700" "a" "Other" "m" "Form")
                                         "</mxc:record>") {}))
        ua (first (unimarc/ingest (str "<mxc:record xmlns:mxc=\"info:lc/xmlns/marcxchange-v2\" format=\"UNIMARC\" id=\"ark:/12148/cbB\" type=\"Authority\">"
                                       "<mxc:controlfield tag=\"001\">B</mxc:controlfield>"
                                       (mxc-df "210" "a" "Maison de Victor Hugo")
                                       (mxc-df "700" "a" "X" "b" "Y")
                                       "</mxc:record>") {}))
        ma (first (marc21/ingest (str "<collection xmlns=\"http://www.loc.gov/MARC21/slim\"><record><leader>00000nz  a2200000n  4500</leader>"
                                      "<controlfield tag=\"001\">n1</controlfield>"
                                      (df "100" "3" "a" "Medici (Family)") (df "670" " " "a" "Source")
                                      "</record></collection>")
                                 {}))]
    (is (= [["Hugo, Victor" :described :person "1802-1885" [(:id ia) "100" 0]]]
           (map (juxt :name :type :kind (comp :text :dates) :id) (mentions/mentions ia {:subjects? true :provenance? true}))))
    (is (= [["Maison de Victor Hugo" :described :group]] (map (juxt :name :type :kind) (mentions/mentions ua {:subjects? true}))))
    (is (= [["Medici (Family)" :described :family]] (map (juxt :name :type :kind) (mentions/mentions ma {:subjects? true}))))
    (is (every? #(= [] (:events %)) (mentions/batch [ia ua ma])))))

(deftest a-curated-dc-or-mods-value-renumbers-nothing
  (let [d  (first (:records (dc/importer {:record-id :t/dc2}
                                         (str "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:title>T</dc:title>"
                                              "<dc:creator>Alpha</dc:creator><dc:creator>Beta</dc:creator></metadata>"))))
        i  (first (keep-indexed (fn [k a] (when (and (= :dc/creator (:predicate a)) (= "Alpha" (:value a))) k)) (:assertions d)))
        d' (assoc-in d [:assertions i :status] (first model/out-of-force-statuses))]
    (is (= [[[:t/dc2 "dc:creator" 0] "Alpha"] [[:t/dc2 "dc:creator" 1] "Beta"]] (map (juxt :id :name) (mentions/mentions d))))
    (is (= [[[:t/dc2 "dc:creator" 1] "Beta"]] (map (juxt :id :name) (mentions/mentions d')))
        "Beta keeps its index once Alpha is out of force")))

(deftest every-relator-is-kept-in-source-order
  (let [r (intermarc-record "cb7" (mxc-df "700" "a" "X" "m" "Y" "4" "0070" "4" "0340"))
        m (first (mentions/mentions r))
        r21 (first (marc21/ingest (marcxml (df "700" "1" "a" "Doré, Gustave," "e" "illustrator," "e" "engraver." "4" "ill" "4" "egr")) {}))
        m21 (first (mentions/mentions r21))]
    (is (= ["0070" "0070" ["0070" "0340"] ["0070" "0340"]] ((juxt :role :relator :roles :relators) m)))
    (is (= ["illustrator," "ill" ["illustrator," "engraver."] ["ill" "egr"]] ((juxt :role :relator :roles :relators) m21)))
    (testing "a repeated $a: the first is the heading's"
      (is (= "A1, M" (:name (first (mentions/mentions (intermarc-record "cb8" (mxc-df "700" "a" "A1" "a" "A2" "m" "M"))))))))))

(deftest a-lido-group-or-an-undated-actor-has-no-life-dates
  (let [xml (str "<lido:lido xmlns:lido=\"http://www.lido-schema.org\"><lido:lidoRecID>E-2</lido:lidoRecID><lido:descriptiveMetadata><lido:eventWrap>"
                 "<lido:eventSet><lido:event><lido:eventType><lido:term>production</lido:term></lido:eventType>"
                 "<lido:eventActor><lido:actorInRole><lido:actor lido:type=\"corporation\"><lido:nameActorSet><lido:appellationValue>Firm</lido:appellationValue></lido:nameActorSet>"
                 "<lido:vitalDatesActor><lido:earliestDate>1850</lido:earliestDate><lido:latestDate>1950</lido:latestDate></lido:vitalDatesActor></lido:actor></lido:actorInRole></lido:eventActor>"
                 "<lido:eventActor><lido:actorInRole><lido:actor lido:type=\"person\"><lido:nameActorSet><lido:appellationValue>Nobody</lido:appellationValue></lido:nameActorSet>"
                 "<lido:vitalDatesActor><lido:earliestDate>0</lido:earliestDate><lido:latestDate>0</lido:latestDate></lido:vitalDatesActor></lido:actor></lido:actorInRole></lido:eventActor>"
                 "</lido:event></lido:eventSet></lido:eventWrap></lido:descriptiveMetadata></lido:lido>")
        ms  (mentions/mentions (first (:records (lido/importer {:validate? false} xml))))]
    (is (= [["Firm" false] ["Nobody" false]] (map (juxt :name #(contains? % :dates)) ms)))))

(defn- golden-records [file]
  (cond
    (str/includes? file "/lido/")     (:records (oai/import-response "lido" (slurp file)))
    (str/includes? file "/unimarc/")  (unimarc/ingest (slurp file) {})
    (str/includes? file "/marc21/")   (marc21/ingest (slurp file) {})
    :else                             (intermarc/ingest (slurp file) {})))

(deftest the-mentions-of-real-records-are-those-an-independent-reader-finds
  (testing "test/fixtures/mentions/golden-mentions.edn, written by a reader of the XML that is not Palomar's"
    (let [golden (edn/read-string (slurp "test/fixtures/mentions/golden-mentions.edn"))]
      (is (= 15 (count golden)))
      (doseq [{:keys [file record mentions]} golden
              :let [r   (nth (golden-records file) record)
                    got (mentions/mentions r {:provenance? true})]]
        (is (= (for [g mentions]
                 (cond-> (select-keys g [:type :name :kind :dates :relators])
                   (:field g) (assoc :id [(:id r) (:field g) (:occurrence g)])
                   (:event g) (assoc :id [(:id r) "actorInRole" (:event g) (:position g)])))
               (for [m got]
                 {:id (:id m) :type (:type m) :name (:name m) :kind (:kind m)
                  :dates (get-in m [:dates :text]) :relators (vec (:relators m))}))
            (str file " " record))))))

(deftest dates-and-roles-come-from-their-own-subfields
  (let [recs (mapcat #(intermarc/ingest (slurp (str "test/fixtures/documentary/intermarc/sru/intermarcXchange/" %)) {})
                     ["bnf-sru-victor-hugo-50.xml" "bnf-sru-jules-verne-50.xml" "bnf-sru-alexandre-dumas-50.xml"
                      "bib-music-recordings-start1-max30.xml" "bib-julien-gracq-start1-max30.xml"])
        ms   (mentions/batch recs {:subjects? true :provenance? true})
        of   (fn [sfs code] (vec (keep (fn [[c v]] (when (= code c) (some-> v str/trim not-empty))) sfs)))]
    (is (< 300 (count ms)))
    (doseq [m ms
            :let [sfs (get-in m [:source :subfields])]]
      (is (= (when (#{:person :family} (:kind m)) (first (of sfs "d"))) (get-in m [:dates :text]))
          (str "a person's dates are the first $d; a group's $d (a meeting's) is none " (pr-str (:id m))))
      (is (= (of sfs "4") (vec (:relators m))) (str "the relators every $4 " (pr-str (:id m))))
      (is (= (first (of sfs "4")) (:role m) (:relator m)) (pr-str (:id m)))
      (is (= (str/join ", " (keep #(first (of sfs %)) ["a" "m"])) (:name m)) (pr-str (:id m))))))

(deftest the-extraction-reads-no-canonical-floor-assertion
  (let [r  (first (normalized intermarc/plugin [@im]))
        r' (update r :assertions (fn [as] (conj (vec (remove #(= "canon" (namespace (:predicate %))) as))
                                                (model/assertion {:subject (:id r) :predicate :canon/agent :value "Someone Else"}))))]
    (is (some #(= "canon" (namespace (:predicate %))) (:assertions r)) "the floor is there to be ignored")
    (is (= (mentions/mentions @im) (mentions/mentions r) (mentions/mentions r')))))

;; ---------------------------------------------------------------------------
;; Identifiers (lot N5)
;; ---------------------------------------------------------------------------

(deftest a-mention-carries-the-ids-its-occurrence-states
  (testing "INTERMARC: $1, the ISNI as written, is an id; $3 is the authority control the cataloguer stated, apart"
    (let [r  (intermarc-record "cb3"
                               (mxc-df "100" "3" "11907966" "1" "ISNI0000000121200982" "a" "Hugo" "m" "Victor")
                               (mxc-df "700" "a" "Férat" "m" "Jules")
                               (mxc-df "710" "3" "11865012" "a" "Hetzel et Cie"))
          ms (mentions/mentions r)]
      (is (= [["ISNI0000000121200982"] nil nil] (map :ids ms)))
      (is (= ["http://catalogue.bnf.fr/ark:/12148/cb11907966z" nil "http://catalogue.bnf.fr/ark:/12148/cb118650124"]
             (map :authority-record ms))
          "the BnF record number, as that record's ARK")))
  (testing "an invalid ISNI stays an id, so that it is reported; a value no id form reads is kept apart, as unread"
    (let [[m] (mentions/mentions (intermarc-record "cb5" (mxc-df "700" "1" "ISNI0000000121200983" "1" "n/a" "a" "Hugo" "m" "Victor")))]
      (is (= ["ISNI0000000121200983"] (:ids m)))
      (is (= ["n/a"] (:unread-ids m)))))
  (testing "MARC21: $0 and $1, a URI as given, a source code or an organisation code (any case) as the IRI it names"
    (let [r (first (marc21/ingest (marcxml (df "100" "1" "a" "Hugo, Victor," "0" "(DE-588)118554700" "1" "http://www.wikidata.org/entity/Q535")
                                           (df "700" "1" "a" "Doré, Gustave," "0" "(DLC)n  79021164" "0" "(OCoLC)fst00034567")
                                           (df "700" "1" "a" "Smith, J." "0" "http://id.loc.gov/authorities/names/n79021165")
                                           (df "700" "1" "a" "Verne, Jules," "0" "(de-588)118626620" "0" "(isni)0000000121400562"
                                               "0" "(viaf)51700132" "0" "(orcid)0000-0002-1825-0097" "0" "(uri)http://example.org/verne")
                                           (df "700" "1" "a" "Férat, Jules," "0" "(DE-588a)15521666X" "0" "(DLC)sh 85082139"))
                                  {}))
          ms (mentions/mentions r)]
      (is (= [["https://d-nb.info/gnd/118554700" "http://www.wikidata.org/entity/Q535"]
              ["http://id.loc.gov/authorities/names/n79021164"]
              ["http://id.loc.gov/authorities/names/n79021165"]
              ["https://d-nb.info/gnd/118626620" "ISNI 0000000121400562" "http://viaf.org/viaf/51700132"
               "https://orcid.org/0000-0002-1825-0097" "http://example.org/verne"]
              ["http://id.loc.gov/authorities/subjects/sh85082139"]]
             (map :ids ms)))
      (is (= [nil ["(OCoLC)fst00034567"] nil nil ["(DE-588a)15521666X"]] (map :unread-ids ms))
          "an old GND number names no current record; an LC subject heading is read as LCSH, never as a name")))
  (testing "UNIMARC: $o, the ISNI; $3 the authority control, a BnF ARK only in a BnF record"
    (let [bnf (first (unimarc/ingest (mxc "UNIMARC" "cb4"
                                          (mxc-df "700" "3" "11928016" "o" "ISNI0000000121400562" "a" "Verne" "b" "Jules")
                                          (mxc-df "801" "a" "FR" "b" "FR-751131015"))
                                     {}))
          other (first (unimarc/ingest (str/replace (mxc "UNIMARC" "cb4" (mxc-df "700" "3" "026927608" "o" "ISNI0000000121400562" "a" "Verne" "b" "Jules"))
                                                    #"<mxc:controlfield tag=\"001\">[^<]*</mxc:controlfield>"
                                                    "<mxc:controlfield tag=\"001\">026927608X</mxc:controlfield>")
                                       {}))]
      (is (= [["ISNI0000000121400562"] "http://catalogue.bnf.fr/ark:/12148/cb11928016k"]
             ((juxt :ids :authority-record) (first (mentions/mentions bnf)))))
      (is (= [["ISNI0000000121400562"] nil] ((juxt :ids :authority-record) (first (mentions/mentions other))))
          "a record number of a file the record does not name is no ARK")))
  (testing "UNIMARC: the BnF's Verne and Hugo records, read whole"
    (doseq [f ["test/fixtures/documentary/unimarc/sru/bnf-sru-verne-unimarc.xml" "test/fixtures/documentary/unimarc/sru/bnf-sru-hugo-unimarc.xml"]]
      (let [ms   (mentions/batch (unimarc/ingest (slurp f) {}))
            sfs  (comp :subfields :source)
            with (fn [c] (filter (fn [m] (some #(= c (first %)) (sfs m))) ms))]
        (is (seq (with "o")) f)
        (is (every? #(seq (:ids %)) (with "o")) "every $o is an id")
        (is (every? #(str/starts-with? (:authority-record %) "http://catalogue.bnf.fr/ark:/12148/cb") (with "3")) "every $3 a BnF ARK")
        (is (not-any? #(some (fn [i] (str/includes? i "catalogue.bnf.fr")) (:ids %)) ms) "no $3 among the ids"))))
  (testing "EAC-CPF: its entityId and its otherRecordIds, when each is an id"
    (let [r (first (:records (eac/importer {} (str "<eac xmlns=\"https://archivists.org/ns/eac/v2\"><control><recordId>r1</recordId>"
                                                   "<otherRecordId>ISNI 0000 0001 2120 0982</otherRecordId><otherRecordId>local-42</otherRecordId>"
                                                   "<maintenanceAgency><agencyCode>X</agencyCode></maintenanceAgency></control>"
                                                   "<cpfDescription><identity><entityId>http://viaf.org/viaf/9847974</entityId><entityType value=\"person\"/>"
                                                   "<nameEntry status=\"authorized\"><part>Victor Hugo</part></nameEntry></identity></cpfDescription></eac>"))))]
      (is (= [["http://viaf.org/viaf/9847974" "ISNI 0000 0001 2120 0982"]] (map :ids (mentions/mentions r))))
      (is (= [["local-42"]] (map :unread-ids (mentions/mentions r))))
      (is (= ["ISNI 0000 0001 2120 0982" "local-42"]
             (keep #(when (= :eac/other-record-id (:predicate %)) (:value %)) (:assertions r)))
          "the importer keeps every otherRecordId as a native"))))

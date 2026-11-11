(ns palomar.agent-events-test
  "Typed events as contrary evidence on a person's identity (lot M7, ADR 0018
   amendment M7): each source's events typed, each constraint, posthumous
   events allowed, an edition never a creation, missing or untyped events no
   evidence, and the readings that would block wrongly."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.agent-cluster :as ac]
            [palomar.agent-dates :as dates]
            [palomar.agent-events :as ae]
            [palomar.agent-match :as fs]
            [palomar.mentions :as mentions]
            [palomar.model :as model]
            [palomar.plugins.intermarc :as intermarc]
            [palomar.plugins.lido :as lido]
            [palomar.plugins.marc21 :as marc21]
            [palomar.plugins.oai :as oai]
            [palomar.plugins.unimarc :as unimarc]
            [palomar.reconcile :as rec]))

;; ---------------------------------------------------------------------------
;; LIDO: who carried out what, when
;; ---------------------------------------------------------------------------

(def ^:private ycba
  "Five records of the Yale Center for British Art (Data Source: Yale Center
   for British Art)."
  "test/fixtures/documentary/lido/ycba-oai-lido.xml")

(def ^:private ycba-mentions
  (delay (vec (mapcat mentions/mentions (:records (oai/import-response "lido" (slurp ycba)))))))

(defn- of [nm] (filter #(= nm (:name %)) @ycba-mentions))

(defn- one-event [nm] (let [[m] (of nm)] (first (:events m))))

(deftest a-lido-actor-s-event-is-typed-by-what-the-record-says-it-did
  (testing "a production carried out by the actor in a maker's role is a creation, dated by the event"
    (let [e (one-event "Luigi Schiavonetti")]
      (is (= :creation (:kind e)))
      (is (= "creator" (:role e)))
      (is (= {:from 1808 :to 1808} (select-keys (:date e) [:from :to])))
      (testing "and it cites the event and the actor it was read from"
        (is (= "1.1" (get-in e [:source :event])))
        (is (some #(str/includes? (get-in % [:selector :value]) "actorInRole") (get-in e [:source :evidence])))
        (is (some #(str/includes? (get-in % [:selector :value]) "event") (get-in e [:source :evidence]))))))
  (testing "a production dated \"ca. 1775\" (1770-1780) is widened by the approximate margin"
    (is (= {:from 1750 :to 1800} (select-keys (:date (one-event "Francis Wheatley")) [:from :to]))))
  (testing "\"after\" Thomas Phillips: influence, no activity of his"
    (is (= :influence (:kind (one-event "Thomas Phillips")))))
  (testing "\"Text by\" Robert Blair, \"Published by\" Cromek: they took part, they did not make it — an activity"
    (is (= :activity (:kind (one-event "Robert Blair"))))
    (is (= :activity (:kind (one-event "Robert Hartley Cromek")))))
  (testing "\"Formerly\" John Constable, \"follower of\" Varley: an attribution to another hand"
    (is (= :attribution (:kind (one-event "Constable, John, 1776-1837"))))
    (is (= :attribution (:kind (one-event "John Varley I")))))
  (testing "acquisitions, exhibitions and publications about the object are typed so"
    (let [kinds (set (map :kind (mapcat :events @ycba-mentions)))]
      (is (= #{:creation :influence :activity :attribution :acquisition :exhibition :publication} kinds))))
  (testing "an actor is its occurrence: one event, its own; the mention carries its ids and its life dates"
    (let [[m] (of "Luigi Schiavonetti")]
      (is (= 1 (count (:events m))))
      (is (some #(str/starts-with? % "https://viaf.org/viaf/") (:ids m)) "its authority ids, every one")
      (is (= {:from 1765 :to 1765} (select-keys (get-in m [:dates :birth]) [:from :to])))))
  (testing "a YCBA vital date of 0 is no date"
    (let [[m] (of "Washington, D.C., National Gallery of Art")]
      (is (not (dates/dated? (:dates m)))))))

(defn- lido-record
  "A one-event LIDO record: event type `type` (a LIDO 1.0 name), one actor
   `nm` in role `role` (nil: none) with attribution `q`, dated by
   `display` / `earliest` / `latest` (each optional)."
  [{:keys [type nm role q display earliest latest]}]
  (let [xml (str "<lido:lido xmlns:lido=\"http://www.lido-schema.org\"><lido:lidoRecID>E-1</lido:lidoRecID><lido:descriptiveMetadata>"
                 "<lido:eventWrap><lido:eventSet><lido:event><lido:eventType><lido:conceptID>http://terminology.lido-schema.org/eventType/" type "</lido:conceptID><lido:term>" type "</lido:term></lido:eventType>"
                 "<lido:eventActor><lido:actorInRole><lido:actor><lido:nameActorSet><lido:appellationValue>" nm "</lido:appellationValue></lido:nameActorSet></lido:actor>"
                 (when role (str "<lido:roleActor><lido:term>" role "</lido:term></lido:roleActor>"))
                 (when q (str "<lido:attributionQualifierActor>" q "</lido:attributionQualifierActor>"))
                 "</lido:actorInRole></lido:eventActor>"
                 (when (or display earliest latest)
                   (str "<lido:eventDate>"
                        (when display (str "<lido:displayDate>" display "</lido:displayDate>"))
                        (when (or earliest latest)
                          (str "<lido:date>" (when earliest (str "<lido:earliestDate>" earliest "</lido:earliestDate>"))
                               (when latest (str "<lido:latestDate>" latest "</lido:latestDate>")) "</lido:date>"))
                        "</lido:eventDate>"))
                 "</lido:event></lido:eventSet></lido:eventWrap></lido:descriptiveMetadata></lido:lido>")]
    (first (:records (lido/importer {:validate? false} xml)))))

(defn- event-of [spec] (first (:events (first (mentions/mentions (lido-record (merge {:type "production" :nm "X"} spec)))))))

(deftest a-lido-event-date-is-read-or-it-is-no-date
  (testing "earliest and latest, else the display date"
    (is (= {:from 1880 :to 1885} (select-keys (:date (event-of {:earliest "1880" :latest "1885"})) [:from :to])))
    (is (= {:from 1880 :to 1880} (select-keys (:date (event-of {:display "1880"})) [:from :to])))
    (is (= {:from 1880 :to 1885} (select-keys (:date (event-of {:display "1880-1885"})) [:from :to]))))
  (testing "one end only: the other is open"
    (is (= {:from 1880 :to nil} (select-keys (:date (event-of {:earliest "1880"})) [:from :to]))))
  (testing "no date, an unread one, one before the common era, or a self-contradicting one: no date"
    (is (nil? (:date (event-of {}))))
    (is (nil? (:date (event-of {:display "Regency period"}))))
    (is (nil? (:date (event-of {:display "500 B.C." :earliest "500" :latest "500"}))) "never a year of the common era")
    (is (nil? (:date (event-of {:earliest "-0500" :latest "-0450"}))))
    (is (nil? (:date (event-of {:earliest "1885" :latest "1850"}))))
    (is (nil? (:date (event-of {:earliest "0" :latest "0"}))) "YCBA's 0 is no year")))

(deftest a-design-is-a-creation-and-an-actor-alone-in-a-production-is-its-maker
  (is (= :creation (:kind (event-of {:type "designing" :role "designer" :display "1900"}))))
  (is (= :creation (:kind (event-of {:display "1900"}))) "no role stated: the maker (ADR 0054)")
  (is (= :activity (:kind (event-of {:role "Drucker" :display "1900"}))) "a printer took part")
  (is (= :activity (:kind (event-of {:type "use" :role "painter" :display "1900"}))) "a painter in another event: an activity"))

;; ---------------------------------------------------------------------------
;; Bibliographic records: the edition's date, not the work's
;; ---------------------------------------------------------------------------

(defn- marcxml [& fields]
  (str "<collection xmlns=\"http://www.loc.gov/MARC21/slim\"><record><leader>00000nam a2200000 a 4500</leader>"
       "<controlfield tag=\"001\">x1</controlfield>" (apply str fields) "</record></collection>"))

(defn- df [tag ind2 & sfs]
  (str "<datafield tag=\"" tag "\" ind1=\" \" ind2=\"" ind2 "\">"
       (apply str (for [[c v] (partition 2 sfs)] (str "<subfield code=\"" c "\">" v "</subfield>")))
       "</datafield>"))

(deftest an-edition-s-dates-are-publication-events
  (testing "MARC21 260 $c, 264 $c (publication and production) and 008 Date 1: the manifestation's"
    (let [r  (first (marc21/ingest (marcxml "<controlfield tag=\"008\">900101s1990    fr            000 0 fre d</controlfield>"
                                            (df "100" " " "a" "Hugo, Victor," "d" "1802-1885.")
                                            (df "260" " " "a" "Paris :" "b" "Gallimard," "c" "[1990].")
                                            (df "264" "0" "c" "1989")) {}))
          es (ae/edition-events r "author")]
      (is (= [["260" :publication 1990] ["264" :production 1989] ["008" :publication 1990]]
             (map (juxt (comp :field :source) :manifestation (comp :from :date)) es)))
      (is (every? #(= :publication (:kind %)) es) "an edition, whatever the field: never a creation")
      (is (every? #(= "author" (:role %)) es) "the mention's role, as given")
      (is (every? (comp seq :evidence :source) es) "each cites its subfield")))
  (testing "INTERMARC 260 $d and UNIMARC 210 $d likewise"
    (let [im (first (intermarc/ingest (str "<mxc:record xmlns:mxc=\"info:lc/xmlns/marcxchange-v2\" format=\"INTERMARC\" id=\"ark:/12148/cb1\" type=\"Bibliographic\">"
                                           "<mxc:controlfield tag=\"001\">FRBNF1</mxc:controlfield>"
                                           "<mxc:datafield tag=\"260\" ind1=\" \" ind2=\" \"><mxc:subfield code=\"d\">1975</mxc:subfield></mxc:datafield>"
                                           "</mxc:record>") {}))
          um (first (unimarc/ingest (str "<mxc:record xmlns:mxc=\"info:lc/xmlns/marcxchange-v2\" format=\"UNIMARC\" id=\"ark:/12148/cb2\" type=\"Bibliographic\">"
                                         "<mxc:controlfield tag=\"001\">u1</mxc:controlfield>"
                                         "<mxc:datafield tag=\"210\" ind1=\" \" ind2=\" \"><mxc:subfield code=\"d\">cop. 2001</mxc:subfield></mxc:datafield>"
                                         "</mxc:record>") {}))]
      (is (= [[:publication 1975]] (map (juxt :kind (comp :from :date)) (ae/edition-events im "author"))))
      (is (= [[:publication 2001]] (map (juxt :kind (comp :from :date)) (ae/edition-events um "author"))))))
  (testing "a date not read is kept as text, no date"
    (let [r (first (marc21/ingest (marcxml (df "260" " " "c" "s.d.")) {}))]
      (is (= [["s.d." nil]] (map (juxt :text :date) (ae/edition-events r "author")))))))

;; ---------------------------------------------------------------------------
;; The constraints
;; ---------------------------------------------------------------------------

(def ^:private hugo (dates/parse-life-dates "1802-1885"))

(defn- ev [kind from to] {:kind kind :role "creator" :date {:from from :to to}})

(deftest a-creation-long-before-birth-blocks
  (testing "a creation that ends more than 10 years before the birth: incompatible, with the values compared"
    (let [{:keys [verdict checks]} (ae/compatibility [(ev :creation 1785 1790)] hugo)]
      (is (= :incompatible verdict))
      (is (= [{:compared [:creation :birth] :a {:from 1785 :to 1790} :b {:from 1802 :to 1802 :text "1802"} :ok? false}]
             (map #(dissoc % :event) checks)))))
  (testing "within the 10-year margin, at birth or in life: compatible"
    (is (= :compatible (:verdict (ae/compatibility [(ev :creation 1792 1792)] hugo))))
    (is (= :compatible (:verdict (ae/compatibility [(ev :creation 1850 1850)] hugo)))))
  (testing "an interval that can reach the life, or an open end: compatible"
    (is (= :compatible (:verdict (ae/compatibility [(ev :creation 1700 1800)] hugo))))
    (is (= :compatible (:verdict (ae/compatibility [(ev :creation nil 1900)] hugo)))))
  (testing "an approximate date, widened by 20, reaches forward"
    (is (= :compatible (:verdict (ae/compatibility [{:kind :creation :date (dates/parse-point "ca. 1775")}] hugo))))))

(deftest a-creation-after-death-is-never-evidence
  (testing "a cast, an impression, a reissue, a copy dated after the artist's death: the authority is not blocked"
    (doseq [y [1890 1915 1985 2020]]
      (is (= :compatible (:verdict (ae/compatibility [(ev :creation y y)] hugo))) (str y)))
    (is (= :unknown (:verdict (ae/compatibility [(ev :creation 1950 1950)] (dates/parse-life-dates "....-1885"))))
        "a death alone bounds nothing")))

(deftest a-posthumous-publication-exhibition-or-acquisition-is-no-evidence
  (doseq [k [:publication :exhibition :acquisition :activity :influence :attribution]]
    (testing (str k " a century after death")
      (is (= :unknown (:verdict (ae/compatibility [(ev k 1990 1990)] hugo))))))
  (testing "nor long before birth: an edition date is never a creation date"
    (is (= :unknown (:verdict (ae/compatibility [(ev :publication 1700 1700)] hugo))))))

(deftest missing-events-or-dates-are-no-evidence
  (is (= :unknown (:verdict (ae/compatibility [] hugo))))
  (is (= :unknown (:verdict (ae/compatibility nil hugo))))
  (is (= :unknown (:verdict (ae/compatibility [{:kind :creation}] hugo))) "an undated creation")
  (is (= :unknown (:verdict (ae/compatibility [(ev :creation 1990 1990)] nil))) "an undated candidate")
  (is (= :unknown (:verdict (ae/compatibility [(ev :creation 1990 1990)] (dates/parse-life-dates "fl. 1830-1850"))))
      "a floruit bounds no life")
  (is (= :unknown (:verdict (ae/compatibility [{:kind :mystery :date {:from 1990 :to 1990}}] hugo))) "an untyped event"))

(deftest a-birth-alone-bounds-the-creation
  (is (= :incompatible (:verdict (ae/compatibility [(ev :creation 1750 1750)] (dates/parse-life-dates "1802-....")))))
  (is (= :compatible (:verdict (ae/compatibility [(ev :creation 1950 1950)] (dates/parse-life-dates "1802-...."))))))

(deftest a-wrong-reading-in-a-real-record-does-not-block
  (testing "\"Text by\" Robert Blair (1699-1746) on a print of 1808: his text was printed after his death"
    (let [[m] (of "Robert Blair")]
      (is (= :activity (:kind (first (:events m)))))
      (is (= :unknown (:verdict (ae/compatibility (:events m) (:dates m)))))))
  (testing "every creation of the five records is within its maker's stated life"
    (is (every? #(not= :incompatible (:verdict (ae/compatibility (:events %) (:dates %)))) @ycba-mentions))))

;; ---------------------------------------------------------------------------
;; In the matcher and the clustering
;; ---------------------------------------------------------------------------

(def ^:private pool
  [{:id "http://ex.org/hugo" :label "Hugo, Victor" :dates hugo}
   {:id "http://ex.org/hugo2" :label "Hugo, Victor" :dates (dates/parse-life-dates "1950-2010")}])

(deftest a-mention-that-carries-a-creation-outside-a-life-does-not-get-that-authority
  (let [m {:name "Hugo, Victor" :events [(ev :creation 1930 1930)]}
        {:keys [proposals rejected]} (rec/agent-candidates [m] pool)]
    (testing "the one born in 1950 is rejected for a creation of 1930, with the reason and the values compared"
      (is (= ["http://ex.org/hugo"] (map :authority-id proposals)) "a creation after the writer's death is no evidence against him")
      (is (= [["http://ex.org/hugo2" :incompatible-events]] (map (juxt :authority-id :reason) rejected)))
      (is (= :incompatible (get-in (first rejected) [:evidence :events :verdict])))
      (is (= [(ev :creation 1930 1930)] (get-in (first rejected) [:mention :events])) "the mention it was"))
    (testing "the proposal states the events verdict too"
      (is (= :compatible (get-in (first proposals) [:evidence :events :verdict])) "after the writer's birth: compatible, whatever his death")))
  (testing "a posthumous edition of 1990 rejects nobody"
    (let [{:keys [proposals rejected]} (rec/agent-candidates [{:name "Hugo, Victor" :events [(ev :publication 1990 1990)]}] pool)]
      (is (= 2 (count proposals)))
      (is (empty? rejected))
      (is (every? #(= :unknown (get-in % [:evidence :events :verdict])) proposals))))
  (testing "dates first: incompatible dates are the reason when both are"
    (let [{:keys [rejected]} (rec/agent-candidates [{:name "Hugo, Victor" :dates "1950-2010" :events [(ev :creation 1785 1785)]}] pool)]
      (is (= [["http://ex.org/hugo" :incompatible-dates]] (map (juxt :authority-id :reason) rejected)))))
  (testing "a mention without events is as before: no events evidence"
    (is (not (contains? (:evidence (first (rec/propose-agent-links ["Hugo, Victor"] pool))) :events)))))

(def ^:private fs-model
  (fs/model {:m {:name  {:exact 0.9 :initials 0.02 :same-tokens 0.02 :surname-typo 0.01 :forename-overlap 0.01
                         :surname-only 0.01 :forenames-differ 0.01 :jaccard-high 0.01 :jaccard-low 0.005 :none 0.005}
                 :form  {:heading 0.9 :variant 0.1}
                 :dates {:agree 0.8 :compatible 0.1 :unknown 0.1}}
             :u {:name  {:exact 0.01 :initials 0.002 :same-tokens 0.002 :surname-typo 0.001 :forename-overlap 0.01
                         :surname-only 0.005 :forenames-differ 0.05 :jaccard-high 0.02 :jaccard-low 0.2 :none 0.7}
                 :form  {:heading 0.9 :variant 0.1}
                 :dates {:agree 0.05 :compatible 0.15 :unknown 0.8}}
             :prior 0.05}))

(deftest the-scored-matcher-rejects-on-events-and-never-weighs-them
  (let [m {:name "Hugo, Victor" :events [(ev :creation 1930 1930)]}
        {:keys [proposals rejected]} (rec/agent-candidates [m] pool {:model fs-model})
        plain (rec/agent-candidates ["Hugo, Victor"] pool {:model fs-model})]
    (is (= [["http://ex.org/hugo2" :incompatible-events]] (map (juxt :authority-id :reason) rejected)))
    (is (nil? (:score (first rejected))) "never weighed")
    (testing "the remaining candidate weighs what it would without events"
      (is (= ["http://ex.org/hugo"] (map :authority-id proposals)))
      (is (= (:match-weight (first (filter #(= "http://ex.org/hugo" (:authority-id %)) (:proposals plain))))
             (:match-weight (first proposals)))))))

(deftest events-reach-the-ir-as-the-proposal-s-evidence
  (let [r  (model/record {:id :doc/a :kind :book
                          :assertions [(model/assertion {:subject :doc/a :predicate :canon/agent :value "Hugo, Victor"})]})
        ps (rec/propose-agent-links [{:name "Hugo, Victor" :record :doc/a :events [(ev :creation 1930 1930)]}] pool)
        [out] (rec/align-agents [r] ps)
        a  (first (filter #(#{:palomar/matchesAuthority :palomar/resemblesAuthority} (:predicate %)) (:assertions out)))]
    (is (= "http://ex.org/hugo" (:iri (first (filter #(= :reconcile/authority (:kind %)) (:entities out))))))
    (is (= :compatible (get-in a [:provenance :bindings '?events])))))

(deftest a-mention-s-creation-is-a-cannot-link-in-the-clustering
  (let [items {:m1 {:events [(ev :creation 1930 1930)]}
               :m2 {:dates (dates/parse-life-dates "1950-2010")}
               :m3 {:events [(ev :publication 1995 1995)]}}]
    (testing "a creation outside the other's life: they are never one cluster"
      (is (= :incompatible-events (ac/cannot-link-reason items {} :m1 :m2)))
      (is (= :incompatible-events (ac/cannot-link-reason items {} :m2 :m1))))
    (testing "an edition is none; two mentions' events alone are none (no life is known)"
      (is (nil? (ac/cannot-link-reason items {} :m3 :m2)))
      (is (nil? (ac/cannot-link-reason items {} :m1 :m3))))
    (testing "a link that would join them is refused, with the reason"
      (let [res (ac/cluster {:items items :links [{:a :m1 :b :m2 :basis :propose :weight 5.0}]})]
        (is (= [:incompatible-events] (map (comp :reason :violation) (:refused res))))
        (is (empty? (ac/violations {:items items} res)))))
    (testing "and from the IR: a local agent's events, given beside the batch, are its item's"
      (let [r     (model/record {:id :doc/a :kind :book
                                 :assertions [(model/assertion {:subject :doc/a :predicate :canon/agent :value "Hugo, Victor"})]})
            [out] (rec/align-agents [r] (rec/propose-agent-links ["Hugo, Victor"] pool))
            local (:subject (first (filter #(= :palomar/resemblesAuthority (:predicate %)) (:assertions out))))
            input (ac/clustering-input [out] {:events {local [(ev :creation 1930 1930)] :elsewhere [(ev :creation 1 1)]}})]
        (is (= [(ev :creation 1930 1930)] (get-in input [:items local :events])))
        (is (not (contains? (:items input) :elsewhere)) "only for an agent the batch holds")))))

;; ---------------------------------------------------------------------------
;; The contradictor's cases (review of M7): posthumous making, self-
;; contradiction, the curator, groups, roles, dates read no narrower
;; ---------------------------------------------------------------------------

(defn- lido-actors
  "A one-event LIDO record of event `type` (a LIDO 1.0 name, or none) and
   `term`, its `actors` `[{:nm :role :q :type}]`, dated by `display`,
   `earliest`, `latest`."
  [{:keys [type term actors display earliest latest]}]
  (let [xml (str "<lido:lido xmlns:lido=\"http://www.lido-schema.org\"><lido:lidoRecID>E-1</lido:lidoRecID><lido:descriptiveMetadata>"
                 "<lido:eventWrap><lido:eventSet><lido:event><lido:eventType>"
                 (when type (str "<lido:conceptID>http://terminology.lido-schema.org/eventType/" type "</lido:conceptID>"))
                 "<lido:term>" (or term type) "</lido:term></lido:eventType><lido:eventActor>"
                 (apply str (for [{:keys [nm role q] t :type} actors]
                              (str "<lido:actorInRole><lido:actor lido:type=\"" (or t "person") "\"><lido:nameActorSet><lido:appellationValue>" nm "</lido:appellationValue></lido:nameActorSet></lido:actor>"
                                   (when role (str "<lido:roleActor><lido:term>" role "</lido:term></lido:roleActor>"))
                                   (when q (str "<lido:attributionQualifierActor>" q "</lido:attributionQualifierActor>"))
                                   "</lido:actorInRole>")))
                 "</lido:eventActor>"
                 (when (or display earliest latest)
                   (str "<lido:eventDate>"
                        (when display (str "<lido:displayDate>" display "</lido:displayDate>"))
                        (when (or earliest latest)
                          (str "<lido:date>" (when earliest (str "<lido:earliestDate>" earliest "</lido:earliestDate>"))
                               (when latest (str "<lido:latestDate>" latest "</lido:latestDate>")) "</lido:date>"))
                        "</lido:eventDate>"))
                 "</lido:event></lido:eventSet></lido:eventWrap></lido:descriptiveMetadata></lido:lido>")]
    (mentions/mentions (first (:records (lido/importer {:validate? false} xml))))))

(def ^:private artists
  [{:id "rodin" :label "Rodin, Auguste" :dates (dates/parse-life-dates "1840-1917")}
   {:id "degas" :label "Degas, Edgar" :dates (dates/parse-life-dates "1834-1917")}
   {:id "rembrandt" :label "Rembrandt van Rijn" :dates (dates/parse-life-dates "1606-1669")}
   {:id "atget" :label "Atget, Eugène" :dates (dates/parse-life-dates "1857-1927")}
   {:id "thonet" :label "Thonet, Michael" :dates (dates/parse-life-dates "1796-1871")}
   {:id "raphael" :label "Raphael" :dates (dates/parse-life-dates "1483-1520")}
   {:id "barlach" :label "Barlach, Ernst" :dates (dates/parse-life-dates "1870-1938")}
   {:id "mackintosh" :label "Mackintosh, Charles Rennie" :dates (dates/parse-life-dates "1868-1928")}])

(def ^:private posthumous-cases
  "The contradictor's p2: posthumous casts, later impressions, reissues and
   copies as museums catalogue them, each with the artist it names."
  [["rodin" {:type "production" :actors [{:nm "Rodin, Auguste" :role "sculptor"} {:nm "Alexis Rudier" :role "founder"}] :display "modelled 1880, cast 1925" :earliest "1925" :latest "1925"}]
   ["rodin" {:type "production" :actors [{:nm "Rodin, Auguste" :role "Bildhauer"}] :display "1925"}]
   ["rodin" {:type "production" :actors [{:nm "Rodin, Auguste" :role "Bildhauer"}] :display "Guss 1925" :earliest "1925" :latest "1925"}]
   ["rodin" {:term "Hergestellt" :actors [{:nm "Auguste Rodin"}] :display "1925" :earliest "1925-01-01" :latest "1925-12-31"}]
   ["degas" {:type "production" :actors [{:nm "Degas, Edgar" :role "sculptor"}] :display "Modell um 1881, Guss 1922" :earliest "1922" :latest "1922"}]
   ["degas" {:type "production" :term "casting" :actors [{:nm "Degas, Edgar" :role "sculptor"}] :display "1922"}]
   ["rembrandt" {:type "production" :actors [{:nm "Rembrandt van Rijn" :role "etcher"} {:nm "Basan" :role "printer"}] :display "1906 (impression)" :earliest "1906" :latest "1906"}]
   ["rembrandt" {:term "Herstellung" :actors [{:nm "Rembrandt van Rijn" :role "Radierer"}] :display "um 1906"}]
   ["atget" {:type "production" :actors [{:nm "Atget, Eugène" :role "photographer"} {:nm "Berenice Abbott" :role "printer"}] :display "1925, printed 1956" :earliest "1956" :latest "1956"}]
   ["thonet" {:type "production" :actors [{:nm "Thonet, Michael" :role "designer"}] :display "1985"}]
   ["thonet" {:term "Herstellung" :actors [{:nm "Michael Thonet" :role "Entwerfer"}] :display "1985"}]
   ["mackintosh" {:type "production" :actors [{:nm "Mackintosh, Charles Rennie" :role "designer"} {:nm "Cassina" :role "manufacturer"}] :display "1973"}]
   ["raphael" {:type "production" :actors [{:nm "Raphael" :role "painter"}] :display "19th century" :earliest "1800" :latest "1899"}]
   ["raphael" {:type "production" :actors [{:nm "Raphael" :role "Maler" :q "Fälschung"}] :display "1850"}]
   ["raphael" {:type "production" :actors [{:nm "Raphael" :role "Maler" :q "forgery"}] :display "1850"}]
   ["raphael" {:type "production" :actors [{:nm "Raphael" :role "Maler" :q "replica"}] :display "1850"}]
   ["raphael" {:type "production" :actors [{:nm "Raphael" :role "Maler" :q "reproduction"}] :display "1850"}]
   ["raphael" {:type "production" :actors [{:nm "Raphael" :role "Maler" :q "posthumous"}] :display "1850"}]
   ["barlach" {:term "Hergestellt" :actors [{:nm "Ernst Barlach" :role "Bildhauer"}] :display "Nachguss 1985" :earliest "1985-01-01" :latest "1985-12-31"}]
   ["barlach" {:term "Hergestellt" :actors [{:nm "Ernst Barlach" :q "posthumer Guss"}] :earliest "1985-01-01" :latest "1985-12-31"}]])

(deftest posthumous-making-never-blocks-the-artist
  (doseq [[truth spec] posthumous-cases
          m (lido-actors spec)
          :let [{:keys [rejected]} (rec/agent-candidates [(select-keys m [:name :events])] artists)]]
    (testing (str (:name m) " " (pr-str (select-keys spec [:display :earliest])))
      (is (not-any? #(= truth (:authority-id %)) rejected) "the artist it names is never rejected")
      (is (empty? rejected) "nor anyone else, by a date after death"))))

(deftest a-creation-that-contradicts-the-record-s-own-life-is-no-evidence
  (testing "a record that dates its maker's creation 30 years before the birth it states for them: dropped"
    (let [own [(ev :creation 1770 1770)]
          m   {:name "Hugo, Victor" :dates "1802-1885" :events own}]
      (is (= [] (ae/consistent-events own (dates/parse-life-dates "1802-1885"))))
      (is (= own (ae/consistent-events own nil)) "no stated life: kept")
      (is (empty? (:rejected (rec/agent-candidates [m] [{:id "h" :label "Hugo, Victor" :dates (dates/parse-life-dates "1802-1885")}]))))
      (testing "nor in the clustering"
        (is (nil? (ac/cannot-link-reason {:m1 {:events own :dates (dates/parse-life-dates "1802-1885")}
                                          :m2 {:dates (dates/parse-life-dates "1802-1885")}}
                                         {} :m1 :m2))))))
  (testing "a creation consistent with its own life still blocks another's"
    (is (= :incompatible-events
           (ac/cannot-link-reason {:m1 {:events [(ev :creation 1930 1930)] :dates (dates/parse-life-dates "....-2010")}
                                   :m2 {:dates (dates/parse-life-dates "1950-2010")}}
                                  {} :m1 :m2)))))

(deftest a-curator-s-link-outranks-an-events-constraint
  (let [cr    [(ev :creation 1930 1930)]
        life  (dates/parse-life-dates "1950-2010")
        items {:m1 {:events cr} :ulan {:authority? true :dates life} :m2 {:dates life}}]
    (testing "an accepted link stays applied: no conflict, the events constraint reported beside it"
      (let [res (ac/cluster {:items items :links [{:a :m1 :b :ulan :basis :accepted}]})]
        (is (= ((:cluster-of res) :m1) ((:cluster-of res) :ulan)))
        (is (empty? (:conflicts res)))
        (is (= [{:a :m1 :b :ulan :reason :incompatible-events}] (mapcat :violations (:event-conflicts res))))
        (is (empty? (ac/violations {:items items} res)) "an accepted events conflict is reported, not a violation")))
    (testing "a proposal is still refused"
      (let [res (ac/cluster {:items items :links [{:a :m1 :b :ulan :basis :propose :weight 10.0}]})]
        (is (= [:incompatible-events] (map (comp :reason :violation) (:refused res))))))
    (testing "an accepted link with a dates conflict is still a conflict (M5)"
      (let [res (ac/cluster {:items {:m2 {:dates life} :x {:authority? true :dates hugo}} :links [{:a :m2 :b :x :basis :accepted}]})]
        (is (seq (:conflicts res)))))
    (testing "in the IR: an accepted alignment kept, the events conflict a diagnostic on its record"
      (let [r     (model/record {:id :doc/a :kind :book
                                 :assertions [(model/assertion {:subject :doc/a :predicate :canon/agent :value "Hugo, Victor"})]})
            [out] (rec/align-agents [r] (rec/propose-agent-links ["Hugo, Victor"] [{:id "http://ex.org/x" :label "Hugo, Victor"}]))
            a     (first (filter #(= :palomar/resemblesAuthority (:predicate %)) (:assertions out)))
            acc   (:record (rec/confirm-identity out a "curator"))
            local (:subject a)
            auth  (:value/target (:value a))
            {:keys [records clustering]} (ac/cluster-records [acc] {:events {local cr} :dates {auth "1950-2010"}})
            diags (filter #(= ac/events-conflict-code (:code %)) (:diagnostics (first records)))]
        (is (empty? (:conflicts clustering)))
        (is (= 1 (count diags)))
        (is (= :incompatible-events (get-in (first diags) [:detail :reason])))
        (is (some #(and (= :palomar/matchesAuthority (:predicate %)) (= :accepted (:status %))) (:assertions (first records)))
            "the curator's claim stays in force")))))

(deftest a-group-has-no-life-to-constrain
  (testing "a corporation, an institution, a family, a manufacturer: their creations constrain nothing"
    (doseq [[t role] [["corporation" nil] ["institution" nil] ["family" nil] ["person" "manufacturer"] ["person" "Hersteller"]]]
      (let [[m] (lido-actors {:type "production" :actors [{:nm "Hugo, Victor" :type t :role role}] :display "1700"})]
        (testing (str t " " role)
          (is (= :unknown (:verdict (ae/compatibility (:events m) hugo))))
          (is (empty? (:rejected (rec/agent-candidates [(select-keys m [:name :events])] pool)))))))))

(deftest roles-and-qualifiers-that-do-not-make-the-work
  (testing "who colours, prints, publishes, casts, pulls or reissues it took part: an activity"
    (doseq [role ["Hand colorist" "colourist" "printer" "publisher" "caster" "founder" "Gießer" "Erzgießer" "fondeur"]]
      (is (= :activity (:kind (event-of {:role role :display "1900"}))) role)))
  (testing "a qualifier naming such a role is another role's"
    (doseq [q ["Hand colored by" "Hand coloured by" "Cast by" "Impression by" "Reissued by" "Printed by"]]
      (is (= :other-role (lido/attribution q)) q)
      (is (= :activity (:kind (event-of {:role "Artist" :q q :display "1900"}))) q)))
  (testing "a later making from the actor's work, or a false one: not the actor's creation"
    (doseq [q ["posthumous" "posthumous cast" "posthumer Guss" "Posthumous impression" "restrike" "replica" "reproduction"
               "Reproduktion" "forgery" "fake" "Fälschung" "Nachguss" "version"]]
      (is (= :later-making (lido/attribution q)) q)
      (is (= :attribution (:kind (event-of {:role "Maler" :q q :display "1850"}))) q))))

(deftest an-event-date-is-read-no-narrower-than-the-source
  (testing "a span whose second part is two digits after the first's last two"
    (is (= {:from 1880 :to 1885} (select-keys (:date (event-of {:display "1880-85"})) [:from :to])))
    (is (= {:from 1880 :to 1885} (select-keys (dates/parse-point "1880-85") [:from :to])))
    (is (= {:from 1965 :to 1965} (select-keys (dates/parse-point "1965-04") [:from :to])) "a month of 1965")
    (is (= {:from 1965 :to 1965} (select-keys (dates/parse-point "1965-04-15") [:from :to]))))
  (testing "an open \"after\" or \"before\" in the display opens that end, whatever earliest and latest say"
    (is (= {:from 1752 :to nil} (select-keys (:date (event-of {:display "after 1752" :earliest "1752" :latest "1762"})) [:from :to])))
    (is (= {:from 1752 :to nil} (select-keys (:date (event-of {:display "nach 1752" :earliest "1752" :latest "1762"})) [:from :to])))
    (is (= {:from nil :to 1762} (select-keys (:date (event-of {:display "before 1762" :earliest "1752" :latest "1762"})) [:from :to])))
    (is (= {:from nil :to 1762} (select-keys (:date (event-of {:display "vor 1762" :earliest "1752" :latest "1762"})) [:from :to]))))
  (testing "approximate markers widen, with or without earliest and latest"
    (doseq [d ["c1775" "c 1775" "1775 c." "gegen 1775" "etwa 1775"]]
      (is (= {:from 1755 :to 1795} (select-keys (:date (event-of {:display d})) [:from :to])) d)
      (is (= {:from 1755 :to 1795} (select-keys (:date (event-of {:display d :earliest "1775" :latest "1775"})) [:from :to])) d)))
  (testing "a display naming two dates (a model and its cast) leaves the event unread"
    (doseq [d ["Modell um 1881, Guss 1922" "modelled 1880, cast 1925" "1925, printed 1956" "1880/1925"]]
      (is (nil? (:date (event-of {:display d :earliest "1922" :latest "1922"}))) d))
    (is (some? (:date (event-of {:display "1880-1925" :earliest "1880" :latest "1925"}))) "a span is one date")))

(deftest an-undated-display-is-no-date-whatever-earliest-and-latest-say
  (testing "\"undated\" with bounds the museum derived from the artist's life (1760-1811): no date, no evidence"
    (doseq [d ["undated" "Undated" "n.d." "o.J." "s.d." "ohne Jahr" "sans date" "undatiert"]]
      (is (nil? (:date (event-of {:display d :earliest "1760" :latest "1811"}))) d)))
  (testing "so a work of Bunbury (1750-1811) displayed undated blocks no namesake born in 1828"
    (let [[m] (lido-actors {:type "production" :actors [{:nm "Henry William Bunbury" :role "Artist"}]
                            :display "undated" :earliest "1760" :latest "1811"})]
      (is (empty? (:rejected (rec/agent-candidates [(select-keys m [:name :events])]
                                                   [{:id "millais" :label "William Henry Millais"
                                                     :dates (dates/parse-life-dates "1828-1912")}])))))))

(ns palomar.plugins.lido
  "LIDO importer spoke (ADR 0052): a museum object record — LIDO 1.0 or 1.1,
   and so the French LIDO-MC profile, which is LIDO 1.1 — read as a CIDOC CRM
   human-made object, not as an edition.

   One record per `lido:lido` (bare, or in a `lidoWrap`, as an OAI-PMH
   `lido` record carries it), named by its `lidoRecID`. Every value it reads
   is a native `:lido/*` with the absolute XPath it was read at:
   - identification: the titles, the inventory number (a `workID` typed
     inventory number) and the other work ids, the repository and its location;
   - classification: the object work types and classifications, each a term and,
     when the record gives one, a concept URI (GND, AAT, Iconclass…);
   - measurements: as displayed, and each measured dimension (type, value, unit);
   - events: each event's type, date (as displayed, and its earliest and latest
     years), place, description, materials and techniques, and its actors with
     their role, identifier and life dates;
   - subjects, rights (of the work, the record, each resource), the record's
     own id, type, source and link, and each digital resource;
   - the object's description and inscriptions;
   - each title in its language (`:lido/title-name`).
   What the LIDO schema requires and a record lacks is a warning on the
   record (`missing-required`); LIDO-MC's own rules are not checked.
   Whatever it does not read is reported, occurrence by occurrence: every
   element holding text whose value no native was read from (related works,
   for one), not a list of names it knows (post-audit 4, F3).

   Terms come in several languages; each is a native string, and each concept
   is also kept whole (`{:uri :label}`, the English label else the first) for
   the views that link to it.

   The canonical floor gets what a generic description can say of an object
   (the titles, the production's actors as creators, its date, the inventory
   number, the descriptions, the images), so Dublin Core can describe one.
   `project` makes the CIDOC CRM object graph (an `E22_Human-Made_Object`, its
   production, dimensions, types, keeper and owner, subjects and images)."
  (:require [clojure.string :as str]
            [palomar.diagnostics :as dx]
            [palomar.model :as model]
            [palomar.plugins.lrmoo.ntriples :as nt]
            [palomar.rules :as rules]
            [palomar.runtime :as runtime]
            [palomar.xml :as rx]))

(defn- local [tag] (when tag (name tag)))

(defn- kids
  "`[[child path] …]`: the element children of `el` (at XPath `path`) named
   `nm`, each with its absolute path, by rank among its same-named siblings."
  [[el path] nm]
  (map-indexed (fn [i c] [c (str path "/*[local-name()='" nm "'][" (inc i) "]")])
               (filter #(= nm (local (:tag %))) (filter map? (:content el)))))

(defn- kid [node nm] (first (kids node nm)))

(defn- descend
  "The nodes reached from `node` through the element names `steps`."
  [node & steps]
  (reduce (fn [nodes nm] (mapcat #(kids % nm) nodes)) [node] steps))

(defn- text [[el]]
  (some-> (str/join " " (map str/trim (filter string? (tree-seq map? :content el))))
          (str/replace #"\s+" " ") str/trim not-empty))

(defn- attr [[el] nm] (some (fn [[k v]] (when (= nm (name k)) v)) (:attrs el)))

(defn- lang [[el]] (some (fn [[k v]] (when (= "lang" (name k)) v)) (:attrs el)))

(defn- concept
  "The concept of a LIDO concept element (`objectWorkType`, `roleActor`…):
   `{:uri :label}`, its preferred URI `conceptID` and its English term, else
   its first."
  [node]
  (let [ids   (descend node "conceptID")
        uri   (or (some #(let [v (text %)] (when (and v (= "preferred" (attr % "pref")) (nil? (nt/iri-problem v))) v)) ids)
                  (some #(let [v (text %)] (when (and v (nil? (nt/iri-problem v))) v)) ids))
        terms (descend node "term")
        label (or (some #(when (= "en" (lang %)) (text %)) terms) (some text terms))]
    (when (or uri label) (cond-> {} uri (assoc :uri uri) label (assoc :label label)))))

(def ^:private lido-terms
  "LIDO 1.1 terminology codes → the name LIDO 1.0 gave the same concept as the
   last segment of its URI (`http://terminology.lido-schema.org/lido00007` is
   `…/eventType/production`), from the LIDO Terminology (CC BY 4.0)."
  {"lido00001" "acquisition" "lido00002" "finding" "lido00003" "unspecified_event"
   "lido00005" "transfer_of_custody" "lido00006" "modification" "lido00007" "production"
   "lido00008" "part_addition" "lido00009" "loss" "lido00010" "collecting" "lido00011" "use"
   "lido00012" "creation" "lido00013" "type_creation" "lido00021" "part_removal"
   "lido00023" "type_assignment" "lido00024" "attribute_assignment" "lido00025" "completion"
   "lido00026" "destruction" "lido00027" "end_of_existence" "lido00028" "beginning_of_existence"
   "lido00029" "transformation" "lido00030" "performance" "lido00031" "execution_of_design"
   "lido00032" "planning" "lido00033" "excavation" "lido00034" "restoration"
   "lido00223" "move_of_object" "lido00224" "designing" "lido00225" "exhibition"
   "lido00226" "commissioning" "lido00228" "publication" "lido00484" "expression_creation"
   "lido00486" "work_conception" "lido00487" "carrier_production"
   "lido00131" "technique" "lido00132" "material"
   "lido00163" "person" "lido00165" "group_of_persons" "lido00166" "family" "lido00413" "organization"})

(defn term-name
  "The LIDO concept a type URI (or a bare `@type` value) names, by the last
   segment of a 1.0 URI or the code of a 1.1 one: `\"production\"`, `\"person\"`…"
  [v]
  (when v
    (let [seg (str/replace (str v) #"^.*/" "")]
      (get lido-terms seg seg))))

(def ^:private event-kinds
  "A LIDO event type → the event's kind: the events that make the object are
   its production; an acquisition changes its ownership."
  {"production" :production "creation" :production "designing" :production "execution_of_design" :production
   "carrier_production" :production "acquisition" :acquisition})

(def ^:private kind-labels
  "Event type terms, in the languages LIDO records use, when no URI says it."
  {"production" :production "herstellung" :production "entstehung" :production "création" :production
   "creation" :production "acquisition" :acquisition "erwerb" :acquisition "erwerbung" :acquisition
   "zugang" :acquisition})

(defn- event-kind [c terms]
  (or (some-> (:uri c) term-name event-kinds)
      (some #(get kind-labels (str/lower-case (str/trim %))) (remove nil? (cons (:label c) terms)))
      :event))

(def ^:private activity-types
  "LIDO event types that are activities, carried out by someone (CIDOC CRM
   `E7_Activity`), even when the record names no actor."
  #{"exhibition" "commissioning" "restoration" "excavation" "collecting" "use" "performance"
    "publication" "move_of_object" "transfer_of_custody" "planning" "modification" "part_addition"
    "part_removal" "type_assignment" "attribute_assignment" "type_creation"})

(def ^:private creator-heads
  "The words a creator's role ends with, in the languages LIDO records use:
   the artist, author or maker. German compounds end with them too
   (Kupferstecher, Hofmaler, Holzschneider)."
  #"(painter|painters|maler|malerin|peintre|draughtsman|draftsman|zeichner|zeichnerin|dessinateur|dessinatrice|sculptor|sculptress|bildhauer|bildhauerin|sculpteur|artist|artists|künstler|künstlerin|artiste|author|autor|autorin|auteur|engraver|stecher|graveur|etcher|radierer|lithographer|printmaker|photographer|fotograf|fotografin|photographe|architect|architekt|architecte|maker|potter|töpfer|keramiker|ceramist|céramiste|goldsmith|silversmith|goldschmied|silberschmied|orfèvre|carver|schnitzer|weaver|weber|illuminator|illustrator|illustrateur|illustratorin|designer|entwerfer|woodcutter|schneider|creator|urheber|schöpfer|créateur|manufacturer|hersteller|fabricant)$")

(def ^:private not-creator
  "Words that make a role something else than the creator's, whatever word
   it ends with: who printed, published, sold, owned or kept it, a copy, a
   circle, an estate (\"lithographic printer\", \"after a painting by\")."
  #"\b(print(er|ing)?|drucker|druckerei|publish(er|ing)?|verleger|verlag|éditeur|imprimeur|studio|atelier|anstalt|estate|nachlass|collector|sammler|dealer|händler|owner|besitzer|after|nach|by|of|von|workshop|werkstatt|circle|kreis|school|schule|follower|copy|kopie|copie|attributed|zugeschrieben)\b")

(def ^:private creator-role-uris
  "Role URIs that name a creator: AAT artists, painters, sculptors and
   photographers, and the GND Maler the Städel records use."
  #{"http://vocab.getty.edu/aat/300025103" "http://vocab.getty.edu/aat/300025136"
    "http://vocab.getty.edu/aat/300025181" "http://vocab.getty.edu/aat/300025687"
    "http://d-nb.info/gnd/4037215-7"})

(defn creator?
  "True when an actor of a production, in role `terms` (the terms of all its
   roles, in every language) and role URIs `uris`, created the object
   (ADR 0054):
   - no role stated at all (neither term nor URI): an actor named alone in a
     production is its maker;
   - else, a term whose last word is a creator's (`creator-heads`, German
     compounds included) and which says nothing else (`not-creator`), or a
     creator's role URI."
  [terms uris]
  (let [terms (remove str/blank? terms)
        uris  (remove str/blank? (if (string? uris) [uris] uris))
        lower #(.toLowerCase (str/trim ^String %) java.util.Locale/ROOT)]
    (if (and (empty? terms) (empty? uris))
      true
      (boolean
       (or (some creator-role-uris uris)
           (some #(let [t (lower %)]
                    (and (not (re-find not-creator t))
                         (re-find creator-heads (last (str/split t #"[\s\-/]+")))))
                 terms))))))

(def group-types
  "The LIDO actor types that are a group (CIDOC CRM `E74_Group`), not a person."
  #{"organization" "corporation" "group" "group_of_persons" "family"})

(def ^:private event-path #"local-name\(\)='eventSet'\]\[(\d+)\]/\*\[local-name\(\)='event'\]\[(\d+)\]")

(defn event-key
  "The key of the LIDO event an XPath `path` (or a native, by its evidence)
   is in: `\"n.m\"`, its `eventSet`'s rank and its rank in it, or nil."
  [x]
  (let [path (if (string? x) x (some->> (model/evidence-of x) first :selector :value))]
    (when-let [[_ n m] (some->> path (re-find event-path))] (str n "." m))))

(defn event-order
  "A sort key for event keys `\"n.m\"`, in document order."
  [k]
  (mapv parse-long (str/split (str k) #"\.")))

(defn- text-leaves
  "`[[local-name path] …]` for every element under `node` that holds text of
   its own, with its absolute path (the same ranks `kids` gives)."
  [[el path]]
  (let [own (some->> (filter string? (:content el)) (map str/trim) (remove str/blank?) seq)]
    ;; an element with text of its own is one value, its markup included
    ;; (mixed content: `text` reads it whole), so its children are not leaves
    (if own
      [[(local (:tag el)) path]]
      (mapcat (fn [nm] (mapcat text-leaves (kids [el path] nm)))
              (distinct (keep (comp local :tag) (filter map? (:content el))))))))

(defn- unread
  "`{local-name count}` of the text elements under `node` whose value no
   native was read from (post-audit 4, F3): the check is on the occurrences
   the importer consumed, by their path, not on a list of element names it
   knows, so a known element it skips (a `rightsHolder`) is reported too."
  [node natives]
  (let [read (set (map #(nth % 2) natives))]
    (frequencies (keep (fn [[nm path]] (when-not (read path) nm)) (text-leaves node)))))

(defn number-value
  "The measurement `s` as a decimal number with a dot, or nil when it is none:
   a comma is a decimal mark only before one or two digits (\"12,5\"); before
   three it may separate thousands (\"1,234\"), so such a value is no number
   (F6 review)."
  [s]
  (let [t (str/trim (str s))]
    (cond (re-matches #"-?\d+(\.\d+)?" t)  t
          (re-matches #"-?\d+,\d{1,2}" t)  (str/replace t "," "."))))

(defn- inventory? [n] (re-find #"(?i)invent|accession" (or (attr n "type") "")))

(defn- object-natives
  "The natives of one `lido:lido` node, as `[predicate value path]`."
  [node]
  (let [dm      (kid node "descriptiveMetadata")
        am      (kid node "administrativeMetadata")
        ident   (some-> dm (kid "objectIdentificationWrap"))
        strings (fn [p nodes] (keep #(when-let [t (text %)] [p t (second %)]) nodes))
        ;; a concept's terms, and its identifiers (`p`-id), each as read
        terms   (fn [p cnode] (concat (strings p (descend cnode "term"))
                                      (strings (keyword "lido" (str (name p) "-id")) (descend cnode "conceptID"))))
        whole   (fn [p cnode] (when-let [c (concept cnode)] [[p (model/structured c) (second cnode)]]))
        cnodes  (fn [& steps] (apply descend dm steps))
        events  (descend dm "eventWrap" "eventSet" "event")]
    (concat
     (strings :lido/rec-id (kids node "lidoRecID"))
     (strings :lido/object-id (kids node "objectPublishedID"))
     ;; classification
     (mapcat (fn [c] (concat (terms :lido/work-type c) (whole :lido/work-type-concept c)))
             (cnodes "objectClassificationWrap" "objectWorkTypeWrap" "objectWorkType"))
     (mapcat (fn [c] (concat (terms :lido/classification c) (whole :lido/classification-concept c)))
             (cnodes "objectClassificationWrap" "classificationWrap" "classification"))
     ;; identification
     (when ident
       (concat
        (strings :lido/title (descend ident "titleWrap" "titleSet" "appellationValue"))
        ;; each title in its language: its own, else its set's, else the
        ;; descriptive metadata's (post-audit 4, F7)
        (for [ts (descend ident "titleWrap" "titleSet")
              t  (kids ts "appellationValue")
              ;; an empty xml:lang says no language (F7 review)
              :let [v (text t) l (some-> (some lang [t ts dm]) str/trim not-empty)]
              :when (and v l)]
          [:lido/title-name (model/structured {:content v :lang l}) (second t)])
        (let [repos (descend ident "repositoryWrap" "repositorySet")]
          (concat
           (strings :lido/repository (mapcat #(descend % "repositoryName" "legalBodyName" "appellationValue") repos))
           (strings :lido/repository-id (mapcat #(descend % "repositoryName" "legalBodyID") repos))
           (strings :lido/repository-weblink (mapcat #(descend % "repositoryName" "legalBodyWeblink") repos))
           ;; each keeper with its own id, set by set
           (for [r repos
                 :let [nm (some-> (first (descend r "repositoryName" "legalBodyName" "appellationValue")) text)
                       id (some #(let [v (text %)] (when (and v (nil? (nt/iri-problem v))) v)) (descend r "repositoryName" "legalBodyID"))]
                 :when nm]
             [:lido/keeper (model/structured (cond-> {:name nm} id (assoc :uri id))) (second r)])
           (for [w (mapcat #(kids % "workID") repos) :let [t (text w)] :when t]
             [(if (inventory? w) :lido/inventory-number :lido/work-id) t (second w)])
           (strings :lido/location (mapcat #(descend % "repositoryLocation" "namePlaceSet" "appellationValue") repos))
           (strings :lido/location-id (mapcat #(descend % "repositoryLocation" "placeID") repos))
           (mapcat #(terms :lido/location-note %) (mapcat #(descend % "repositoryLocation" "placeClassification") repos))))
        (let [ms (descend ident "objectMeasurementsWrap" "objectMeasurementsSet")]
          (concat
           (strings :lido/measurements (mapcat #(kids % "displayObjectMeasurements") ms))
           (strings :lido/extent (mapcat #(descend % "objectMeasurements" "extentMeasurements") ms))
           (for [s (mapcat #(descend % "objectMeasurements" "measurementsSet") ms)
                 :let [types (kids s "measurementType")
                       t     (or (some #(when (= "en" (lang %)) (text %)) types) (some text types))
                       v     (some-> (kid s "measurementValue") text)
                       u     (some-> (kid s "measurementUnit") text)]
                 :when v]
             [:lido/dimension (model/structured (cond-> {:value v} t (assoc :type t) u (assoc :unit u))) (second s)])
           (mapcat (fn [s] (concat (strings :lido/measurement-type (kids s "measurementType"))
                                   (strings :lido/measurement-unit (kids s "measurementUnit"))
                                   (strings :lido/measurement-value (kids s "measurementValue"))))
                   (mapcat #(descend % "objectMeasurements" "measurementsSet") ms))))
        (strings :lido/description (descend ident "objectDescriptionWrap" "objectDescriptionSet" "descriptiveNoteValue"))
        (strings :lido/inscription (descend ident "inscriptionsWrap" "inscriptions" "inscriptionDescription" "descriptiveNoteValue"))))
     ;; events: each with its rank, so its parts stay together
     (mapcat
      (fn [ev]
        (let [tc   (concept (kid ev "eventType"))
              kind (event-kind tc (keep text (descend ev "eventType" "term")))
              n    (event-key (second ev))
              ed   (kid ev "eventDate")
              lat  (fn [nm] (some-> ed (kid "date") (kid nm) text))]
          (concat
           [[:lido/event (model/structured (cond-> {:index n :kind (name kind)}
                                             (:uri tc) (assoc :type (:uri tc))
                                             (:label tc) (assoc :label (:label tc))
                                             (some-> ed (kid "displayDate") text) (assoc :date (text (kid ed "displayDate")))
                                             (lat "earliestDate") (assoc :earliest (lat "earliestDate"))
                                             (lat "latestDate") (assoc :latest (lat "latestDate"))))
             (second ev)]]
           (terms :lido/event-type (kid ev "eventType"))
           (strings :lido/event-date (concat (some-> ed (kids "displayDate")) (descend ev "eventDate" "date" "earliestDate")
                                             (descend ev "eventDate" "date" "latestDate")))
           (strings :lido/event-place (concat (descend ev "eventPlace" "displayPlace")
                                              (descend ev "eventPlace" "place" "namePlaceSet" "appellationValue")))
           (for [p (descend ev "eventPlace" "place") :let [c (some-> (kid p "placeID") text)] :when c]
             [:lido/event-place-id c (second (kid p "placeID"))])
           (strings :lido/event-description (descend ev "eventDescriptionSet" "descriptiveNoteValue"))
           (strings :lido/materials (descend ev "eventMaterialsTech" "displayMaterialsTech"))
           (mapcat (fn [m] (concat (terms :lido/material-term m)
                                   (when-let [c (concept m)]
                                     [[:lido/material (model/structured (assoc c :event n :role (or (term-name (attr m "type")) "material")))
                                       (second m)]])))
                   (descend ev "eventMaterialsTech" "materialsTech" "termMaterialsTech"))
           (mapcat (fn [ea]
                     (concat
                      (strings :lido/actor-display (kids ea "displayActorInRole"))
                      (for [air (kids ea "actorInRole")
                            :let [a     (kid air "actor")
                                  names (when a (descend a "nameActorSet" "appellationValue"))
                                  id    (some #(let [v (text %)] (when (and v (nil? (nt/iri-problem v))) v)) (when a (kids a "actorID")))
                                  ;; every role it has, not only the first
                                  roles  (keep concept (kids air "roleActor"))
                                  rterms (keep text (descend air "roleActor" "term"))
                                  ruris  (keep :uri roles)
                                  vd    (fn [nm] (some-> a (kid "vitalDatesActor") (kid nm) text))
                                  nm    (some text names)]
                            :when nm]
                        [:lido/actor (model/structured (cond-> {:event n :name nm
                                                                :type (or (term-name (attr a "type")) "")
                                                                :responsibility (if (and (= :production kind) (creator? rterms ruris))
                                                                                  "creator" "participant")}
                                                         id (assoc :uri id)
                                                         (seq (keep :label roles)) (assoc :role (str/join "; " (keep :label roles)))
                                                         (seq ruris) (assoc :role-uri (first ruris))
                                                         (vd "earliestDate") (assoc :birth (vd "earliestDate"))
                                                         (vd "latestDate") (assoc :death (vd "latestDate"))))
                         (second air)])
                      (mapcat (fn [air]
                                (let [a (kid air "actor")]
                                  (concat (strings :lido/actor-name (when a (descend a "nameActorSet" "appellationValue")))
                                          (strings :lido/actor-id (when a (kids a "actorID")))
                                          (strings :lido/actor-dates (when a (concat (descend a "vitalDatesActor" "earliestDate")
                                                                                     (descend a "vitalDatesActor" "latestDate"))))
                                          (mapcat #(terms :lido/actor-role %) (kids air "roleActor"))
                                          ;; a production's actor in a creator's role (or in none
                                          ;; stated) is the object's creator; any other took part
                                          (when (and (= :production kind) (creator? (keep text (descend air "roleActor" "term"))
                                                                                    (keep #(:uri (concept %)) (kids air "roleActor"))))
                                            (strings :lido/creator (take 1 (when a (descend a "nameActorSet" "appellationValue"))))))))
                              (kids ea "actorInRole"))))
                   (kids ev "eventActor"))
           (when (= :production kind)
             (strings :lido/production-date (take 1 (some-> ed (kids "displayDate"))))))))
      events)
     ;; subjects
     (mapcat (fn [c] (concat (terms :lido/subject c) (whole :lido/subject-concept c)))
             (cnodes "objectRelationWrap" "subjectWrap" "subjectSet" "subject" "subjectConcept"))
     ;; the people a work depicts
     (mapcat (fn [sa]
               (let [a  (kid sa "actor")
                     nm (some-> a (descend "nameActorSet" "appellationValue") first text)
                     id (some #(let [v (text %)] (when (and v (nil? (nt/iri-problem v))) v)) (when a (kids a "actorID")))]
                 (concat (strings :lido/subject-actor-display (kids sa "displayActor"))
                         (strings :lido/subject-actor (when a (descend a "nameActorSet" "appellationValue")))
                         (strings :lido/subject-actor-id (when a (kids a "actorID")))
                         (strings :lido/subject-actor-dates (when a (concat (descend a "vitalDatesActor" "earliestDate")
                                                                            (descend a "vitalDatesActor" "latestDate"))))
                         (when nm [[:lido/subject-person (model/structured (cond-> {:name nm :type (or (term-name (attr a "type")) "")}
                                                                             id (assoc :uri id)))
                                    (second sa)]]))))
             (cnodes "objectRelationWrap" "subjectWrap" "subjectSet" "subject" "subjectActor"))
     ;; administrative metadata
     (when am
       (concat
        (mapcat (fn [r] (concat (whole :lido/rights-work-type r) (terms :lido/rights-work r)))
                (descend am "rightsWorkWrap" "rightsWorkSet" "rightsType"))
        (strings :lido/credit-line (descend am "rightsWorkWrap" "rightsWorkSet" "creditLine"))
        (strings :lido/record-id (descend am "recordWrap" "recordID"))
        (mapcat #(terms :lido/record-type %) (descend am "recordWrap" "recordType"))
        (strings :lido/record-source (descend am "recordWrap" "recordSource" "legalBodyName" "appellationValue"))
        (strings :lido/record-source-id (descend am "recordWrap" "recordSource" "legalBodyID"))
        (mapcat (fn [r] (concat (whole :lido/resource-rights-type r) (terms :lido/resource-rights r)))
                (descend am "resourceWrap" "resourceSet" "rightsResource" "rightsType"))
        (mapcat (fn [r] (concat (whole :lido/record-rights-type r) (terms :lido/record-rights r)))
                (descend am "recordWrap" "recordRights" "rightsType"))
        (strings :lido/record-link (descend am "recordWrap" "recordInfoSet" "recordInfoLink"))
        ;; who holds the rights, of the work, the record and each resource
        (let [holders (concat (descend am "rightsWorkWrap" "rightsWorkSet" "rightsHolder")
                              (descend am "recordWrap" "recordRights" "rightsHolder")
                              (descend am "resourceWrap" "resourceSet" "rightsResource" "rightsHolder"))]
          (concat (strings :lido/rights-holder (mapcat #(descend % "legalBodyName" "appellationValue") holders))
                  (strings :lido/rights-holder-id (mapcat #(descend % "legalBodyID") holders))))
        (for [rs (descend am "resourceWrap" "resourceSet")
              rr (kids rs "resourceRepresentation")
              lr (kids rr "linkResource")
              :let [u (text lr)] :when u]
          [:lido/resource (model/structured (cond-> {:url u}
                                              (attr lr "formatResource") (assoc :format (attr lr "formatResource"))
                                              (attr rr "type") (assoc :type (attr rr "type"))))
           (second lr)])
        (strings :lido/resource-url (descend am "resourceWrap" "resourceSet" "resourceRepresentation" "linkResource"))
        (strings :lido/resource-id (descend am "resourceWrap" "resourceSet" "resourceID"))
        (strings :lido/resource-credit (descend am "resourceWrap" "resourceSet" "rightsResource" "creditLine")))))))

(def ^:private required
  "Elements the LIDO schema (1.0 and 1.1) requires, as `[context child]`:
   every node reached by the element names `context` from `lido:lido` must
   have `child` (a path of element names, or `@xml:lang`). These are the
   schema's main requirements, not all of it."
  (let [dm  ["descriptiveMetadata"]
        am  ["administrativeMetadata"]
        ev  (conj dm "eventWrap" "eventSet" "event")
        air (conj ev "eventActor" "actorInRole")
        ms  (conj dm "objectIdentificationWrap" "objectMeasurementsWrap" "objectMeasurementsSet" "objectMeasurements" "measurementsSet")]
    [[[] ["lidoRecID"]]
     [[] dm]
     [dm ["@xml:lang"]]
     [dm ["objectClassificationWrap" "objectWorkTypeWrap" "objectWorkType"]]
     [dm ["objectIdentificationWrap" "titleWrap" "titleSet"]]
     [(conj dm "objectIdentificationWrap" "titleWrap" "titleSet") ["appellationValue"]]
     [ev ["eventType"]]
     [air ["actor"]]
     [(conj air "actor") ["nameActorSet"]]
     [ms ["measurementType"]]
     [ms ["measurementUnit"]]
     [ms ["measurementValue"]]
     [[] am]
     [am ["@xml:lang"]]
     [am ["recordWrap" "recordID"]]
     [am ["recordWrap" "recordType"]]
     [am ["recordWrap" "recordSource"]]]))

(defn missing-required
  "The paths (`context/child`) of `required` that the `lido:lido` `node`
   lacks, each once: a child is required of every node of its context (each
   descriptive metadata, each event), and checked only where that context is
   there. The rest of the schema, and LIDO-MC's own rules (its required
   elements and vocabularies), are not checked."
  [node]
  (distinct
   (for [[ctx child] required
         c (apply descend node ctx)
         :when (if (= ["@xml:lang"] child)
                 (str/blank? (lang c))
                 (empty? (apply descend c child)))]
     (str/join "/" (concat ctx child)))))

(defn importer
  "ADR 0007 importer: one record per `lido:lido` of `source` (see the ns doc)."
  [_opts source]
  (let [s     (if (string? source) source (slurp source))
        root  (rx/parse-str s)
        rn    (local (:tag root))
        _     (when-not (#{"lido" "lidoWrap"} rn)
                (throw (ex-info "lido: not a LIDO document" {:root rn})))
        top   [root (str "/*[local-name()='" rn "'][1]")]
        lidos (if (= "lido" rn) [top] (kids top "lido"))
        out   (for [node lidos
                    :let [natives (vec (object-natives node))
                          rec-id (some-> (kid node "lidoRecID") text)
                          ;; a local id is unique only within its source: the
                          ;; institution the lidoRecID's lido:source names is part
                          ;; of the key (post-audit 4, F6)
                          src    (some-> (kid node "lidoRecID") (attr "source") str/trim not-empty)
                          rid    (keyword "lido" (cond
                                                   (and rec-id src) (str (model/encode-name src) "_-" (model/encode-name rec-id))
                                                   rec-id           (model/encode-name rec-id)
                                                   :else            (str "h" (model/sha256-hex (pr-str (first node)) 12))))
                          as     (for [[p v path] natives]
                                   (model/assertion {:subject rid :predicate p :value v
                                                     :provenance (model/provenance {:pass :ingest
                                                                                    :evidence [{:selector {:type :xpath :value path}}]})}))]]
                ;; its source identifier is the lidoRecID as written (F6 review);
                ;; what the LIDO schema requires and the record lacks is a
                ;; warning on the record, which `validate` reports (post-audit 4, F7)
                {:record (model/record {:id rid :kind :lido/object :source (or rec-id (name rid)) :assertions (vec as)
                                        :diagnostics (for [m (missing-required node)]
                                                       (model/diagnostic {:severity :warning :code :lido/missing-required :subject rid
                                                                          :message (str "the record lacks " m ", which the LIDO schema requires")
                                                                          :detail {:lido/element m}}))})
                 :losses (concat
                          (when-not rec-id
                            [(dx/loss {:category :under-specified :subject rid :edge :import :source-field :lido/lidoRecID
                                       :message "the record states no lidoRecID: it is named by its content's digest, and changes whenever it does"})])
                          (for [[u n] (sort (unread node natives))]
                            (dx/loss {:category :dropped :subject rid :edge :import :source-field (keyword "lido" u)
                                      :message (str n " " u " value(s) not read by the LIDO importer (ADR 0052)")})))})]
    {:records     (mapv :record out)
     :diagnostics (vec (mapcat :losses out))}))

(def mapping
  "LIDO natives → the canonical floor: what a generic description says of an object."
  [{:mapping/id :map/lido-title :mapping/from :lido/title :mapping/to :canon/title}
   {:mapping/id :map/lido-creator :mapping/from :lido/creator :mapping/to :canon/agent
    :mapping/qualifier {:value :creator :as :canon/role}}
   {:mapping/id :map/lido-date :mapping/from :lido/production-date :mapping/to :canon/date}
   {:mapping/id :map/lido-inventory :mapping/from :lido/inventory-number :mapping/to :canon/identifier}
   {:mapping/id :map/lido-description :mapping/from :lido/description :mapping/to :canon/note}
   {:mapping/id :map/lido-resource :mapping/from :lido/resource-url :mapping/to :canon/digital-object}])

(def plugin
  "The LIDO importer plugin (ADR 0007)."
  {:plugin/spec-version 1
   :id                  :palomar/lido
   :input-format        :xml
   :mapping             mapping
   :importer            importer
   :doc                 "LIDO 1.0 / 1.1 (and LIDO-MC) museum object records, as CIDOC CRM objects."})

;; --- projection: a CIDOC CRM object (ADR 0052) ---------------------------------

(defn- fields [a] (when (model/structured-value? (:value a)) (:value/fields (:value a))))

(defn- runner
  "An object's CIDOC CRM view: an `E22_Human-Made_Object` with its titles,
   types (`P2_has_type`, an `E55_Type` named by its concept URI), inventory
   number, dimensions (`P43` → `E54_Dimension`, `P90` value, `P91` unit),
   production (`P108i` → `E12_Production`, `P14` its actors, `P4` its
   time-span, `P32` its techniques, and `P45` the object's materials), keeper
   (`P50` → the repository's `E74_Group`), subjects (`P62_depicts`), images
   (`P138i_has_representation` → `E36_Visual_Item`) and rights (`P104`), all
   `:asserted`: the record's own statements."
  [record]
  (let [rid    (:id record)
        as     (remove model/out-of-force? (:assertions record))
        of     (fn [p] (filter #(= p (:predicate %)) as))
        prov   (fn [a] (cond-> {:pass :infer :rule :rule.lido/project :derivation [rid]}
                         (seq (some-> a model/evidence-of)) (assoc :evidence (model/evidence-of a))))
        claim  (fn [s p v a] {:kind :assertion :value (model/assertion {:subject s :predicate p :value v :status :asserted
                                                                        :provenance (model/provenance (prov a))})})
        entity (fn [id kind & [iri]] {:kind :entity :value (model/entity (cond-> {:id id :kind kind :provenance (model/provenance (prov nil))}
                                                                           iri (assoc :iri iri)))})
        obj    (model/mint-entity-id :crm/E22_Human-Made_Object [(str rid)])
        typed  (fn [s p kind a {:keys [uri label]}]
                 (let [t (model/mint-entity-id kind (if uri uri [(str rid) label]))]
                   (concat [(entity t kind uri) (claim s p (model/reference t) a)]
                           (when label [(claim t :crm/P190_has_symbolic_content label a)]))))
        events (into {} (for [a (of :lido/event) :let [f (fields a)]] [(:index f) [f a]]))]
    (concat
     [(entity obj :crm/E22_Human-Made_Object)]
     (for [a (of :lido/title)] (claim obj :crm/P190_has_symbolic_content (:value a) a))
     (mapcat #(typed obj :crm/P2_has_type :crm/E55_Type % (fields %)) (of :lido/work-type-concept))
     (mapcat #(typed obj :crm/P2_has_type :crm/E55_Type % (fields %)) (of :lido/classification-concept))
     (mapcat (fn [a] (let [i (model/mint-entity-id :crm/E42_Identifier [(str rid) (:value a)])]
                       [(entity i :crm/E42_Identifier) (claim obj :crm/P1_is_identified_by (model/reference i) a)
                        (claim i :crm/P190_has_symbolic_content (:value a) a)]))
             (of :lido/inventory-number))
     (mapcat (fn [a]
               (let [{:keys [type value unit]} (fields a)
                     d (model/mint-entity-id :crm/E54_Dimension [(str rid) (str (some-> (model/evidence-of a) first :selector :value))])]
                 ;; P90 takes a number (E60); a value that is none is said as text
                 (if-let [n (number-value value)]
                   (concat [(entity d :crm/E54_Dimension) (claim obj :crm/P43_has_dimension (model/reference d) a)
                            (claim d :crm/P90_has_value n a)]
                           (when unit (typed d :crm/P91_has_unit :crm/E58_Measurement_Unit a {:label unit}))
                           (when type (typed d :crm/P2_has_type :crm/E55_Type a {:label type})))
                   [(claim obj :crm/P3_has_note (str/join " " (remove nil? [(some-> type (str ":")) value unit])) a)])))
             (of :lido/dimension))
     (for [a (of :lido/measurements)] (claim obj :crm/P3_has_note (:value a) a))
     (for [a (of :lido/description)] (claim obj :crm/P3_has_note (:value a) a))
     ;; every event: a production (P108i), an acquisition (P24i) or another
     ;; event the object was present at (P12i), with its type, time-span,
     ;; place, actors, description and, for a production, its techniques
     ;; and the object's materials
     (mapcat
      (fn [[n [f a]]]
        (let [in (fn [p] (filter #(= n (:event (fields %))) (of p)))
              ;; P14 and P32 have E7 as domain: an event someone carried out,
              ;; or one with a technique, or of an activity type, is an
              ;; E7_Activity; any other stays an E5_Event (post-audit 4, F1)
              activity? (or (seq (in :lido/actor))
                            (some #(= "technique" (:role (fields %))) (in :lido/material))
                            (activity-types (term-name (:type f))))
              [kind via] (case (:kind f)
                           "production"  [:crm/E12_Production :crm/P108i_was_produced_by]
                           "acquisition" [:crm/E8_Acquisition :crm/P24i_changed_ownership_through]
                           (if activity?
                             [:crm/E7_Activity :crm/P12i_was_present_at]
                             [:crm/E5_Event :crm/P12i_was_present_at]))
              e  (model/mint-entity-id kind [(str rid) n])
              ts (model/mint-entity-id :crm/E52_Time-Span [(str rid) n])]
          (concat
           [(entity e kind) (claim obj via (model/reference e) a)]
           (when (:type f) (typed e :crm/P2_has_type :crm/E55_Type a {:uri (:type f) :label (:label f)}))
           (when (or (:date f) (:earliest f) (:latest f))
             (concat [(entity ts :crm/E52_Time-Span) (claim e :crm/P4_has_time-span (model/reference ts) a)]
                     (when (:date f) [(claim ts :crm/P82_at_some_time_within (:date f) a)])
                     (when (:earliest f) [(claim ts :crm/P82a_begin_of_the_begin (:earliest f) a)])
                     (when (:latest f) [(claim ts :crm/P82b_end_of_the_end (:latest f) a)])))
           (mapcat (fn [a] (let [{:keys [name uri type]} (fields a)
                                 kind (if (group-types type) :crm/E74_Group :crm/E21_Person)
                                 ag   (model/mint-entity-id kind (if uri uri [(str rid) name]))]
                             [(entity ag kind uri) (claim e :crm/P14_carried_out_by (model/reference ag) a)
                              (claim ag :crm/P190_has_symbolic_content name a)]))
                   (in :lido/actor))
           (mapcat (fn [a] (let [f (fields a)]
                             (if (= "technique" (:role f))
                               (typed e :crm/P32_used_general_technique :crm/E55_Type a f)
                               (typed obj :crm/P45_consists_of :crm/E57_Material a f))))
                   (in :lido/material))
           (for [a (of :lido/event-description) :when (= n (event-key a))] (claim e :crm/P3_has_note (:value a) a))
           (mapcat (fn [a] (typed e :crm/P7_took_place_at :crm/E53_Place a {:uri (:value a)}))
                   (filter #(= n (event-key %)) (of :lido/event-place-id))))))
      (sort-by (comp event-order key) events))
     (for [a (of :lido/materials)] (claim obj :crm/P3_has_note (:value a) a))
     (mapcat (fn [a] (let [{:keys [name uri]} (fields a)
                           g (model/mint-entity-id :crm/E74_Group (or uri [(str rid) name]))]
                       [(entity g :crm/E74_Group uri)
                        (claim obj :crm/P50_has_current_keeper (model/reference g) a)
                        (claim g :crm/P190_has_symbolic_content name a)]))
             (of :lido/keeper))
     (mapcat #(typed obj :crm/P62_depicts :crm/E55_Type % (fields %)) (of :lido/subject-concept))
     (mapcat (fn [a] (let [{:keys [name uri type]} (fields a)
                           kind (if (group-types type) :crm/E74_Group :crm/E21_Person)
                           pe   (model/mint-entity-id kind (or uri [(str rid) name]))]
                       [(entity pe kind uri) (claim obj :crm/P62_depicts (model/reference pe) a)
                        (claim pe :crm/P190_has_symbolic_content name a)]))
             (of :lido/subject-person))
     (mapcat (fn [a] (let [{:keys [url]} (fields a)
                           v (model/mint-entity-id :crm/E36_Visual_Item url)]
                       [(entity v :crm/E36_Visual_Item url) (claim obj :crm/P138i_has_representation (model/reference v) a)]))
             (of :lido/resource))
     (mapcat #(typed obj :crm/P104_is_subject_to :crm/E30_Right % (fields %)) (of :lido/rights-work-type)))))

(def rule
  "The compiled `:infer` projection of a museum object (ADR 0052)."
  (rules/compiled-rule {:id :rule.lido/project :phase :infer :runner (comp vec runner)}))

(defn project
  "`record` with its CIDOC CRM object view."
  [record]
  (:record (runtime/run-phase record [rule] :infer)))

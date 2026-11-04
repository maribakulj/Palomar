(ns palomar.lido-rules-property-test
  "Generated LIDO records: whatever the event's type, terms, techniques, and
   whatever its actors' roles and attribution qualifiers, the rules hang each
   actor on an event node the code declared, of the class the code gave it,
   and no node is declared twice with two classes (post-audit 8, K6). The
   code and the rules decide an event's class each; this holds them to one
   decision (the K5 review found a term in another language split one event
   into two nodes)."
  (:require [clojure.string :as str]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [palomar.convert :as convert]))

(def ^:private event-types
  ["lido00007" "lido00225" "lido00226" "lido00001" "lido00034" "lido00224" "lido00002" "lido00032" "lido00010"
   "Show" "Ausstellung" "Planung" "Gefunden" "Restaurierung" "Hergestellt" "Created" "Was used" "Auftrag"
   "Event (non-specified)" "Erwerb" "Ereignis" "Gebrauch"])

(def ^:private roles [nil "painter" "commissioner" "seller" "Käufer" "donor" "founder" "Stifter" "Auftraggeber; Maler" "curator"])
(def ^:private qualifiers [nil nil nil "after" "attributed to" "formerly" "Print made by" "follower of" "Text by"])

(def ^:private gen-actor
  (gen/hash-map :name (gen/elements ["Rodin" "Martin" "Musée" "Dealer" "Atelier"])
                :role (gen/elements roles)
                :qualifier (gen/elements qualifiers)
                :authority (gen/frequency [[3 (gen/return nil)] [1 (gen/elements ["http://d-nb.info/gnd/118603426" "urn:nbn:de:101:1-2014"])]])))

(def ^:private gen-event
  (gen/hash-map :type (gen/elements event-types)
                :other-term (gen/elements [nil "Ausstellung" "Planung" "Show" "Herstellung"])
                :technique (gen/frequency [[3 (gen/return false)] [1 (gen/return true)]])
                :actors (gen/vector gen-actor 0 3)))

(defn- event-xml [{:keys [type other-term technique actors]}]
  (str "<lido:eventSet><lido:event><lido:eventType>"
       (if (str/starts-with? type "lido")
         (str "<lido:conceptID>http://terminology.lido-schema.org/" type "</lido:conceptID>")
         (str "<lido:term xml:lang=\"en\">" type "</lido:term>"))
       (when other-term (str "<lido:term xml:lang=\"de\">" other-term "</lido:term>"))
       "</lido:eventType>"
       (apply str (for [{:keys [name role qualifier authority]} actors]
                    (str "<lido:eventActor><lido:actorInRole><lido:actor>"
                         (when authority (str "<lido:actorID>" authority "</lido:actorID>"))
                         "<lido:nameActorSet><lido:appellationValue>" name "</lido:appellationValue></lido:nameActorSet></lido:actor>"
                         (when role (str "<lido:roleActor><lido:term>" role "</lido:term></lido:roleActor>"))
                         (when qualifier (str "<lido:attributionQualifierActor>" qualifier "</lido:attributionQualifierActor>"))
                         "</lido:actorInRole></lido:eventActor>")))
       (when technique
         (str "<lido:eventMaterialsTech><lido:materialsTech><lido:termMaterialsTech lido:type=\"technique\">"
              "<lido:conceptID>http://vocab.getty.edu/aat/300053225</lido:conceptID><lido:term>etching</lido:term>"
              "</lido:termMaterialsTech></lido:materialsTech></lido:eventMaterialsTech>"))
       "</lido:event></lido:eventSet>"))

(defn- record-xml [events]
  (str "<lido:lido xmlns:lido=\"http://www.lido-schema.org\"><lido:lidoRecID>G-1</lido:lidoRecID><lido:descriptiveMetadata xml:lang=\"en\">"
       "<lido:objectClassificationWrap><lido:objectWorkTypeWrap><lido:objectWorkType><lido:term>painting</lido:term></lido:objectWorkType></lido:objectWorkTypeWrap></lido:objectClassificationWrap>"
       "<lido:objectIdentificationWrap><lido:titleWrap><lido:titleSet><lido:appellationValue>T</lido:appellationValue></lido:titleSet></lido:titleWrap></lido:objectIdentificationWrap>"
       "<lido:eventWrap>" (apply str (map event-xml events)) "</lido:eventWrap></lido:descriptiveMetadata></lido:lido>"))

(def ^:private event-classes
  #{:crm/E12_Production :crm/E8_Acquisition :crm/E11_Modification :crm/E65_Creation :crm/E7_Activity :crm/E5_Event})

(def ^:private doings
  #{:crm/P14_carried_out_by :crm/P11_had_participant :crm/P15_was_influenced_by :crm/P22_transferred_title_to :crm/P23_transferred_title_from})

(defspec every-actor-is-on-an-event-the-code-declared 150
  (prop/for-all [events (gen/vector gen-event 1 3)]
                (let [r      (first (:records (convert/to-wemi :lido {} (record-xml events))))
                      kinds  (reduce (fn [m e] (update m (:id e) (fnil conj #{}) (:kind e))) {} (:entities r))
          ;; the event nodes the code declared: an actor the rules hang on a
          ;; node only they declare is on a second, orphan node
                      events (set (keep #(when (and (event-classes (:kind %)) (= :rule.lido/project (get-in % [:provenance :rule]))) (:id %))
                                        (:entities r)))]
                  (and (every? #(= 1 (count %)) (vals kinds))
                       (every? #(contains? events (:subject %)) (filter #(doings (:predicate %)) (:assertions r)))))))

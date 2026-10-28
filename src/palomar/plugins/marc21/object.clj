(ns palomar.plugins.marc21.object
  "MARC21 for museum objects (ADR 0053): a LIDO object as a MARC21
   bibliographic record that describes it, for a library catalogue, written
   from its LIDO values.

   | LIDO                                   | MARC21                                     |
   |----------------------------------------|--------------------------------------------|
   | the object                             | Leader/06 `k` (a painting, a drawing, a print, a photograph: a two-dimensional graphic) or `r` (a three-dimensional artefact), /07 `m` |
   | its stable id                          | `001`                                      |
   | the inventory number                   | `099 $a` local call number                 |
   | the production's actors                | `100` 1# / `110` 2# (then `700`/`710`) `$a`, `$d` life dates, `$e` role, `$0` authority URI |
   | the first title, the others            | `245` 10 `$a`, `246` 1# `$a`               |
   | the production's place and date        | `264` #0 `$a`, `$c` (a production, not a publication) |
   | the measurements as displayed          | `300 $c`                                   |
   | the materials as displayed             | `340 $a`                                   |
   | the inscriptions                       | `500 $a`                                   |
   | the description                        | `520 $a`                                   |
   | the work's rights                      | `540 $a`, `$u`                             |
   | the acquisitions                       | `541 $a`                                   |
   | the provenance and other events        | `561 $a`                                   |
   | the subjects                           | `650` #7 `$a`, `$0`, `$2` (#0 for LCSH, #4 for a vocabulary with no MARC code) |
   | the work types and classifications     | `655` #7 `$a`, `$0`, `$2` (likewise #4)     |
   | the repository                         | `852 $a`                                   |
   | the images, the record's web page      | `856` 4# `$u`, `856` 42 `$u`               |

   Anything else of the object is reported."
  (:require [clojure.string :as str]
            [palomar.diagnostics :as dx]
            [palomar.model :as model]
            [palomar.plugins.lido :as lido]
            [palomar.plugins.record-id :as record-id]))

(defn- xml-escape [s]
  (-> (str s) (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;")))

(defn- in-force [record p] (filter #(and (= p (:predicate %)) (model/in-force? %)) (:assertions record)))

(defn- strings [record p] (keep #(when (string? (:value %)) (:value %)) (in-force record p)))

(defn- fields [record p]
  (keep #(when (model/structured-value? (:value %)) (:value/fields (:value %))) (in-force record p)))

(defn- event-strings [record p k] (keep #(when (and (string? (:value %)) (= k (lido/event-key %))) (:value %)) (in-force record p)))

(defn- datafield [tag ind1 ind2 subfields]
  (str "  <datafield tag=\"" tag "\" ind1=\"" ind1 "\" ind2=\"" ind2 "\">"
       (apply str (for [[c v] subfields :when v] (str "<subfield code=\"" c "\">" (xml-escape v) "</subfield>")))
       "</datafield>"))

(def ^:private two-dimensional
  #"(?i)paint|drawing|print|photograph|watercolou?r|pastel|gouache|engraving|etching|lithograph|woodcut|Gemälde|Zeichnung|Druck|peinture|dessin|estampe")

(defn- leader [record]
  (let [types (concat (strings record :lido/work-type) (strings record :lido/classification))]
    (str "00000n" (if (some #(re-find two-dimensional %) types) "k" "r") "m a2200000   4500")))

(defn- source
  "The MARC second indicator and source code (`$2`) of a concept URI, by its
   vocabulary: `0` for LCSH, which needs no code, `7` with the code of any
   other vocabulary MARC lists, else `4`, source not specified."
  [uri]
  (condp re-find (str uri)
    #"d-nb\.info/gnd"                    ["7" "gnd"]
    #"iconclass\.org"                    ["7" "iconclass"]
    #"vocab\.getty\.edu/aat"             ["7" "aat"]
    #"id\.loc\.gov/authorities/subjects" ["0" nil]
    #"id\.loc\.gov/authorities/genreForms" ["7" "lcgft"]
    ["4" nil]))

(def written
  "The LIDO natives this profile writes."
  #{:lido/inventory-number :lido/actor :lido/title :lido/event :lido/event-place :lido/measurements
    :lido/materials :lido/inscription :lido/description :lido/rights-work-type :lido/event-description
    :lido/subject-concept :lido/work-type-concept :lido/classification-concept :lido/repository
    :lido/resource-url :lido/record-link})

(defn ->marcxml
  "`record`, a LIDO object, as a MARC21slim `<record>` describing it."
  [record]
  (let [events  (sort-by (comp lido/event-order :index) (fields record :lido/event))
        prod    (first (filter #(= "production" (:kind %)) events))
        in-prod (if prod (filter #(= (:index prod) (:event %)) (fields record :lido/actor)) [])
        ;; the creators first (the first is the main entry), then whoever
        ;; else took part, never as a main entry (ADR 0054)
        creators (filter #(= "creator" (:responsibility %)) in-prod)
        others   (remove #(= "creator" (:responsibility %)) in-prod)
        titles  (distinct (strings record :lido/title))
        name-f  (fn [main? {:keys [name uri type role birth death]}]
                  (let [group? (lido/group-types type)
                        tag    (if group? (if main? "110" "710") (if main? "100" "700"))]
                    (datafield tag (if group? "2" "1") " "
                               (concat [["a" name] ["d" (when (or birth death) (str birth "-" death))]]
                                       ;; each role its own $e
                                       (for [r (when role (str/split role #"; "))] ["e" r])
                                       [["0" uri]]))))
        ;; #7 says the source is in $2: only for a vocabulary MARC has a code
        ;; for; LCSH is #0; any other concept is #4, source not specified,
        ;; its URI in $0
        concept (fn [tag {:keys [uri label]}]
                  (let [[ind2 code] (source uri)]
                    (datafield tag " " ind2 [["a" label] ["0" uri] ["2" code]])))
        fields* (concat
                 (for [v (strings record :lido/inventory-number)] (datafield "099" " " "9" [["a" v]]))
                 (map-indexed (fn [i a] (name-f (zero? i) a)) creators)
                 (map #(name-f false %) others)
                 (when (seq titles) [(datafield "245" (if (seq creators) "1" "0") "0" [["a" (first titles)]])])
                 (for [t (rest titles)] (datafield "246" "1" " " [["a" t]]))
                 (when prod
                   (let [places (distinct (event-strings record :lido/event-place (:index prod)))]
                     (when (or (seq places) (:date prod))
                       [(datafield "264" " " "0" (concat (for [pl places] ["a" pl]) [["c" (:date prod)]]))])))
                 (for [v (strings record :lido/measurements)] (datafield "300" " " " " [["c" v]]))
                 (for [v (strings record :lido/materials)] (datafield "340" " " " " [["a" v]]))
                 (for [v (strings record :lido/inscription)] (datafield "500" " " " " [["a" v]]))
                 (for [v (strings record :lido/description)] (datafield "520" " " " " [["a" v]]))
                 (for [{:keys [uri label]} (fields record :lido/rights-work-type)] (datafield "540" " " " " [["a" label] ["u" uri]]))
                 (for [ev events :when (= "acquisition" (:kind ev))
                       d  (event-strings record :lido/event-description (:index ev))]
                   (datafield "541" " " " " [["a" d]]))
                 (for [ev events :when (not (#{"production" "acquisition"} (:kind ev)))
                       d  (event-strings record :lido/event-description (:index ev))]
                   (datafield "561" " " " " [["a" d]]))
                 (map #(concept "650" %) (fields record :lido/subject-concept))
                 (map #(concept "655" %) (concat (fields record :lido/work-type-concept) (fields record :lido/classification-concept)))
                 (for [v (strings record :lido/repository)] (datafield "852" " " " " [["a" v]]))
                 (for [v (distinct (strings record :lido/resource-url))] (datafield "856" "4" " " [["u" v]]))
                 (for [v (strings record :lido/record-link)] (datafield "856" "4" "2" [["u" v]])))
        ctrl    (record-id/source-identifier record)]
    (str "<record xmlns=\"http://www.loc.gov/MARC21/slim\">\n"
         "  <leader>" (leader record) "</leader>\n"
         (when ctrl (str "  <controlfield tag=\"001\">" (xml-escape ctrl) "</controlfield>\n"))
         ;; in tag order, as MARC has its fields
         (str/join "\n" (sort-by #(re-find #"tag=\"\d+\"" %) fields*))
         "\n</record>")))

(defn export-losses
  "Every LIDO native this profile does not write, and the places of events
   other than the production, which have no field of their own."
  [record]
  (let [prod (first (filter #(= "production" (:kind %)) (fields record :lido/event)))]
    (concat
     (for [p (distinct (keep #(let [p (:predicate %)] (when (and (= "lido" (namespace p)) (not (written p))) p))
                             (:assertions record)))]
       (dx/loss {:category :dropped :subject (:id record) :edge :export :source-field p
                 :message (str (pr-str p) " has no field in the MARC21 object profile (ADR 0053)")}))
     (when (some #(not= (:index prod) (lido/event-key %)) (in-force record :lido/event-place))
       [(dx/loss {:category :dropped :subject (:id record) :edge :export :source-field :lido/event-place
                  :message "the place of an event other than the production has no field in the MARC21 object profile"})]))))

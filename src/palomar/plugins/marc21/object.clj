(ns palomar.plugins.marc21.object
  "MARC21 for museum objects (ADR 0053): a LIDO object as a MARC21
   bibliographic record that describes it, for a library catalogue, written
   from its LIDO values.

   | LIDO                                   | MARC21                                     |
   |----------------------------------------|--------------------------------------------|
   | the object                             | Leader/06 `k` (a painting, a drawing, a print, a photograph: a two-dimensional graphic), `r` (a three-dimensional artefact), `a` or `t` (a printed book, a manuscript; `c`/`d` music, `e`/`f` a map), /07 `m` |
   | its stable id                          | `001`, and `003` the ISIL of its source    |
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
            [palomar.plugins.export-coverage :as cov]
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
  "Types of a two-dimensional graphic: Leader/06 `k`. A print, a drawing or a
   photograph stays one whatever book or manuscript it shows or illustrates;
   a printed book is no print (post-audit 6, H3 review)."
  #"(?i)(?<!\p{L})(?:paint|drawing|print(?!ed)|photograph(?!ed)|watercolou?r|pastel|gouache|engraving|etching|lithograph|woodcut|mezzotint|aquatint|poster|collage|peinture|dessin|estampe|gravure|tableau|affiche|bookplate|ex-?libris|illustration|illumination|enluminure)|(?:gemälde|malerei|zeichnung|aquarell|grafik|graphik|(?<!ge)druck(?!schrift)|stich|radierung|holzschnitt|lithographie|lithografie|fotografie|photographie|plakat|skizze|illustration|exlibris)")

(def ^:private three-dimensional
  "Types of a three-dimensional artefact, or a naturally occurring object:
   Leader/06 `r` (post-audit 5, G4)."
  #"(?i)(?<!\p{L})(?:sculpt|statue|bust|relief|vessel|vase|bowl|jug|furniture|chair|cabinet|textile|tapestry|carpet|costume|garment|coin|medal|jewel|ceramic|pottery|porcelain|faience|glass|tool|weapon|armou?r|clock|instrument|model|installation|object|objet|meuble|céramique|monnaie)|(?:skulptur|plastik|büste|gefäß|keramik|porzellan|möbel|textil|teppich|gewand|kostüm|münze|medaille|schmuck|werkzeug|waffe|uhr|modell|objekt)")

(def ^:private book-fitting
  "A book's binding, cover or furniture, or a box for manuscripts: an
   artefact, Leader/06 `r`, not the book (post-audit 6, H3 review)."
  #"(?i)(?<!\p{L})(?:book ?(?:covers?|cases?|ends?|bindings?)|bindings?|reliures?)(?!\p{L})|einb(?:and|ände)|buchdeckel|buchschließe|buchstütze|handschriftenkasten")

(def ^:private manuscript
  "Types of a manuscript, a book of hours included: Leader/06 `t`,
   manuscript language material (post-audit 6, H3)."
  #"(?i)(?<!\p{L})(?:manuscripts?|manuscrits?|codex|codices|book of hours|livres? d'heures|\p{L}*handschrift(?:en)?|stundenb(?:uch|ücher))(?!\p{L})")

(def ^:private printed-book
  "Types of a printed book, a German compound in -buch included: Leader/06
   `a`, language material (post-audit 6, H3)."
  #"(?i)(?<!\p{L})(?:books?|livres?|incunab\p{L}*|inkunabel\p{L}*|druckschrift(?:en)?|\p{L}*buch|\p{L}*bücher)(?!\p{L})")

(def ^:private music
  "Notated music: a manuscript of it is Leader/06 `d`, a printed one `c`."
  #"(?i)musi[ck]|musique|(?<!\p{L})(?:scores?|partitions?)(?!\p{L})|noten")

(def ^:private cartographic
  "A map or an atlas: a manuscript one is Leader/06 `f`, a printed one `e`."
  #"(?i)(?<!\p{L})(?:maps?|cartes?|karten?|landkarten?|atlas|atlanten)(?!\p{L})")

(defn- graphic
  "Leader/06 of `record`, by its work types and classifications (post-audit
   6, H3 and its review): `r` for a natural object; `k` when a type names a
   two-dimensional graphic, whatever book it illustrates; `r` for a book's
   fitting; `t` for a manuscript, `a` for a printed book (`d` and `c` for
   music, `f` and `e` for a map), the language material MARC types them as;
   `r` when a type names a three-dimensional artefact; nil when none says."
  [record]
  (let [types (concat (strings record :lido/work-type) (strings record :lido/classification))
        any?  (fn [re] (some #(re-find re %) types))]
    (cond (lido/natural? record)    "r"
          (any? two-dimensional)    "k"
          (any? book-fitting)       "r"
          (any? manuscript)         (cond (any? music) "d" (any? cartographic) "f" :else "t")
          (any? printed-book)       (cond (any? music) "c" (any? cartographic) "e" :else "a")
          (any? three-dimensional)  "r")))

(defn- leader [record]
  ;; a type it cannot tell is r, and that is reported (`export-losses`)
  (str "00000n" (or (graphic record) "r") "m a2200000   4500"))

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

(defn org-code
  "The MARC organization code (`003`) a lidoRecID's source `src` gives: the
   source itself when it is an ISIL (\"DE-Mb112\", which MARC accepts for
   the countries that use it: a two-letter country prefix, a hyphen, at least
   three characters, sixteen in all), or the ISIL a ZDB organisation URI ends
   with; else nil. Any other URI, a bare MARC code (\"DLC\") included, is no
   code this profile vouches for (post-audit 5, G3)."
  [src]
  (let [s (str/trim (str src))]
    (some #(when (and % (<= (count %) 16) (re-matches #"[A-Z]{2}-[A-Za-z0-9:/\-]{3,}" %)) %)
          [s (second (re-find #"^https?://ld\.zdb-services\.de/resource/organisations/([^/]+)/?$" s))])))

(def written
  "The LIDO natives this profile writes."
  #{:lido/inventory-number :lido/actor :lido/title :lido/event :lido/event-place :lido/measurements
    :lido/materials :lido/inscription :lido/description :lido/rights-work-type :lido/event-description
    :lido/subject-concept :lido/work-type-concept :lido/classification-concept :lido/repository
    :lido/resource-url :lido/record-link :lido/title-name})

(defn ->marcxml
  "`record`, a LIDO object, as a MARC21slim `<record>` describing it."
  [record]
  (let [events  (sort-by (comp lido/event-order :index) (fields record :lido/event))
        prod    (first (filter #(= "production" (:kind %)) events))
        ;; the actors of every production, the first production's first: a
        ;; portfolio's other sheets have creators too (post-audit 5, G6)
        ;; and of every design, whose creators are the object's too (H2)
        pkeys   (into (lido/production-keys events) (lido/design-keys events))
        in-prod (sort-by #(if (= (:index prod) (:event %)) 0 1) (filter #(pkeys (:event %)) (fields record :lido/actor)))
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
                 ;; the production's materials, or those of no event: never a
                 ;; restoration's (post-audit 5, G1)
                 (for [a (in-force record :lido/materials)
                       :when (and (string? (:value a)) (lido/of-production? (lido/material-keys record) (lido/event-key a)))]
                   (datafield "340" " " " " [["a" (:value a)]]))
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
        ctrl    (record-id/source-identifier record)
        org     (some org-code (strings record :lido/rec-id-source))]
    (str "<record xmlns=\"http://www.loc.gov/MARC21/slim\">\n"
         "  <leader>" (leader record) "</leader>\n"
         (when ctrl (str "  <controlfield tag=\"001\">" (xml-escape ctrl) "</controlfield>\n"))
         ;; whose 001 it is (post-audit 5, G3)
         (when (and ctrl org) (str "  <controlfield tag=\"003\">" (xml-escape org) "</controlfield>\n"))
         ;; in tag order, as MARC has its fields
         (str/join "\n" (sort-by #(re-find #"tag=\"\d+\"" %) fields*))
         "\n</record>")))

(defn export-losses
  "Every LIDO native this profile does not write, and the places of events
   other than the production, which have no field of their own."
  [record]
  (let [prod (first (filter #(= "production" (:kind %)) (fields record :lido/event)))
        srcs (strings record :lido/rec-id-source)]
    (concat
     ;; whose id the 001 is: an ISIL is written as such, a URI's ISIL only
     ;; as a code, any other source not at all (post-audit 5, G3)
     (for [s srcs :let [c (org-code s)] :when (not= c s)]
       (dx/loss {:category (if c :under-specified :dropped) :subject (:id record) :edge :export :source-field :lido/rec-id-source :value s
                 :message (if c
                            (str "the lidoRecID's source " (pr-str s) " is written as its ISIL, " c ", in 003")
                            (str "the lidoRecID's source " (pr-str s) " is no MARC organization code: no 003 says whose the 001 is, and another institution's record with the same id has the same 001"))}))
     (when-not (graphic record)
       [(dx/loss {:category :under-specified :subject (:id record) :edge :export :source-field :lido/object :aspect :class
                  :message "no work type says whether the object is a two-dimensional graphic or a three-dimensional artefact: Leader/06 r is assumed"})])
     (when (empty? srcs)
       [(dx/loss {:category :under-specified :subject (:id record) :edge :export :source-field :lido/rec-id-source
                  :message "the record names no source of its lidoRecID: no 003 says whose the 001 is"})])
     (for [p (distinct (keep #(let [p (:predicate %)] (when (and (= "lido" (namespace p)) (not (written p)) (not= :lido/rec-id-source p)) p))
                             (:assertions record)))]
       (dx/loss {:category :dropped :subject (:id record) :edge :export :source-field p
                 :message (str (pr-str p) " has no field in the MARC21 object profile (ADR 0053)")}))
     (cov/unwritten-fields record
                           {:lido/actor #{:name :uri :role :birth :death}
                            :lido/event #{:date}
                            :lido/title-name #{:content}}
                           "in the MARC21 object profile")
     (for [a (in-force record :lido/materials)
           :when (and (string? (:value a)) (not (lido/of-production? (lido/material-keys record) (lido/event-key a))))]
       (dx/loss {:category :dropped :subject (:id record) :edge :export :source-field :lido/materials :value (:value a)
                 :message (str "materials " (pr-str (:value a)) " of another event than the production are not the object's: no field")}))
     ;; only the production's actors and date have a field (F5 review)
     (for [a (fields record :lido/actor) :when (not (contains? (into (lido/production-keys (fields record :lido/event)) (lido/design-keys (fields record :lido/event))) (:event a)))]
       (dx/loss {:category :dropped :subject (:id record) :edge :export :source-field :lido/actor :value a
                 :message (str "actor " (pr-str (:name a)) " of an event other than the production has no field in the MARC21 object profile")}))
     (for [ev (fields record :lido/event) :when (and (not= (:index prod) (:index ev)) (some ev [:date :earliest :latest]))]
       (dx/loss {:category :dropped :subject (:id record) :edge :export :source-field :lido/event :value ev :fields [:date :earliest :latest]
                 :message (str "the date of the " (:kind ev) " event, other than the production, has no field in the MARC21 object profile")}))
     ;; the canonical date of a production other than the first: 264 $c has
     ;; the first's alone (post-audit 5, G6)
     (for [ev (fields record :lido/event) :when (and (= "production" (:kind ev)) (not= (:index prod) (:index ev)) (:date ev))]
       (dx/loss {:category :dropped :subject (:id record) :edge :export :source-field :canon/date :value (:date ev)
                 :message (str "the date " (pr-str (:date ev)) " of another production than the first has no field in the MARC21 object profile")}))
     (when (some #(not= (:index prod) (lido/event-key %)) (in-force record :lido/event-place))
       [(dx/loss {:category :dropped :subject (:id record) :edge :export :source-field :lido/event-place
                  :message "the place of an event other than the production has no field in the MARC21 object profile"})]))))

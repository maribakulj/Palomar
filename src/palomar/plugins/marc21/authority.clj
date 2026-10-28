(ns palomar.plugins.marc21.authority
  "MARC21 Authority for EAC-CPF (ADR 0051): an archival authority record — a
   person, a corporate body, a family — as a MARC21 authority record (Leader/06
   `z`), written from its EAC-CPF values.

   | EAC-CPF                      | MARC21 Authority                              |
   |------------------------------|-----------------------------------------------|
   | `recordId`, its agency       | `001`, `003`                                   |
   | `entityId` (an IRI)          | `024` 7# `$a`, `$2 uri`                        |
   | `existDates`                 | `046`: `$f`/`$g` birth and death of a person, `$s`/`$t` start and end of a body or family; `678` when not years |
   | the authorized name          | `100` 1# (person), `100` 3# (family), `110` 2# (corporate body) |
   | every other name             | `400` / `410` see-from tracing                 |
   | `biogHist`                   | `678` 0# (biography) or 1# (administrative history) |

   Relations (their targets are file names in the samples, not authority
   records) and every description element the importer does not read are
   reported, as at import."
  (:require [clojure.string :as str]
            [palomar.diagnostics :as dx]
            [palomar.model :as model]
            [palomar.plugins.lrmoo.ntriples :as nt]
            [palomar.plugins.record-id :as record-id]))

(defn- xml-escape [s]
  (-> (str s) (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;")))

(defn- strings [record p]
  (keep #(when (and (= p (:predicate %)) (model/in-force? %) (string? (:value %))) (:value %)) (:assertions record)))

(defn- datafield [tag ind1 ind2 subfields]
  (str "  <datafield tag=\"" tag "\" ind1=\"" ind1 "\" ind2=\"" ind2 "\">"
       (apply str (for [[c v] subfields] (str "<subfield code=\"" c "\">" (xml-escape v) "</subfield>")))
       "</datafield>"))

(defn- heading
  "`[tag ind1]` of the heading for entity type `t`, `tracing?` for a see-from."
  [t tracing?]
  (let [[tag ind1] (case t
                     "corporateBody" ["110" "2"]
                     "family"        ["100" "3"]
                     ["100" "1"])]
    [(if tracing? (str "4" (subs tag 1)) tag) ind1]))

(def written
  "The EAC-CPF natives this profile writes."
  #{:eac/record-id :eac/agency-code :eac/entity-id :eac/entity-type :eac/exist-dates
    :eac/authorized-name :eac/name :eac/biog-hist})

(defn- dates-046
  "The `046` subfields of existence dates `d` of an entity of type `t` (the
   first and second four-digit years), or nil when `d` names no year."
  [t d]
  (let [[from to] (re-seq #"\b\d{4}\b" d)
        [a b]     (if (= "person" t) ["f" "g"] ["s" "t"])]
    (when from (cond-> [[a from]] to (conj [b to])))))

(defn ->marcxml
  "`record`, an EAC-CPF authority, as a MARC21slim authority `<record>`."
  [record]
  (let [t      (first (strings record :eac/entity-type))
        ctrl   (or (first (strings record :eac/record-id)) (record-id/source-identifier record))
        agency (first (strings record :eac/agency-code))
        eid    (first (filter #(nil? (nt/iri-problem %)) (strings record :eac/entity-id)))
        dates  (first (strings record :eac/exist-dates))
        d046   (when dates (dates-046 t dates))
        fields (concat
                (when eid [(datafield "024" "7" " " [["a" eid] ["2" "uri"]])])
                (when d046 [(datafield "046" " " " " d046)])
                (for [n (strings record :eac/authorized-name) :let [[tag i1] (heading t false)]] (datafield tag i1 " " [["a" n]]))
                (for [n (strings record :eac/name) :let [[tag i1] (heading t true)]] (datafield tag i1 " " [["a" n]]))
                (when (and dates (nil? d046)) [(datafield "678" "0" " " [["a" dates]])])
                (for [h (strings record :eac/biog-hist)]
                  (datafield "678" (if (= "person" t) "0" "1") " " [["a" h]])))]
    (str "<record xmlns=\"http://www.loc.gov/MARC21/slim\">\n"
         "  <leader>00000nz  a2200000n  4500</leader>\n"
         (when ctrl (str "  <controlfield tag=\"001\">" (xml-escape ctrl) "</controlfield>\n"))
         (when agency (str "  <controlfield tag=\"003\">" (xml-escape agency) "</controlfield>\n"))
         (str/join "\n" fields)
         "\n</record>")))

(defn export-losses
  "Every EAC-CPF native of `record` this profile does not write (its relations),
   and an `entityId` that is no IRI."
  [record]
  (concat
   (for [p (distinct (keep #(let [p (:predicate %)] (when (and (= "eac" (namespace p)) (not (written p))) p))
                           (:assertions record)))]
     (dx/loss {:category :dropped :subject (:id record) :edge :export :source-field p
               :message (str (pr-str p) " has no field in the MARC21 Authority profile (ADR 0051)")}))
   ;; the type is the heading's tag and indicator, not a value of its own
   (for [t (strings record :eac/entity-type)]
     (dx/loss {:category :under-specified :subject (:id record) :edge :export :source-field :eac/entity-type
               :message (str "entity type " (pr-str t) " is written as the heading's tag (100/110) and indicator, not as text")}))
   (for [e (strings record :eac/entity-id) :when (nt/iri-problem e)]
     (dx/loss {:category :dropped :subject (:id record) :edge :export :source-field :eac/entity-id
               :message (str "entityId " (pr-str e) " is no IRI: not written as a 024 URI")}))))

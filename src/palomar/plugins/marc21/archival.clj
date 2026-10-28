(ns palomar.plugins.marc21.archival
  "MARC21 for archives (ADR 0050): an EAD unit as a MARC21 bibliographic
   record under archival control, written from the unit's own EAD values, not
   from the generic canonical floor, so each value lands in the field that
   means what it means.

   | EAD                         | MARC21                                        |
   |-----------------------------|-----------------------------------------------|
   | the unit                    | Leader/06 `p` mixed materials, /07 `c` collection (the fonds) or `d` subunit, /08 `a` archival control |
   | its stable id               | `001`                                         |
   | `unitid` (the *cote*)       | `099 $a` local call number (not `010`, an LCCN) |
   | `unittitle`                 | `245 $a`                                      |
   | `unitdate`                  | `245 $f` inclusive dates (not `260 $c`, a publication date) |
   | `origination` (persname, famname / corpname) | `100` 1# / 3# or `110` 2# `$a`, `$e creator`; later ones `700`/`710` |
   | `@level`                    | `351 $c` hierarchical level                   |
   | `physdesc` (extent)         | `300 $a`                                      |
   | `abstract`                  | `520` 3# (abstract)                           |
   | `scopecontent`              | `520` 2# (scope and content)                  |
   | `accessrestrict`            | `506 $a`                                      |
   | `bioghist`                  | `545 $a`                                      |
   | the parent unit             | `773` 0# `$w` its control number             |
   | `dao`                       | `856` 4# `$u`                                 |

   Two modes: `:marc21-archival` writes every unit, the hierarchy carried by
   `773 $w`; `:marc21-fonds` writes the fonds alone, a discovery record for a
   library catalogue, and reports each component as summarized by it.
   Anything of a unit the profile does not write is reported."
  (:require [clojure.string :as str]
            [palomar.diagnostics :as dx]
            [palomar.model :as model]
            [palomar.plugins.record-id :as record-id]))

(defn- xml-escape [s]
  (-> (str s) (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;")))

(defn- values [record p]
  (keep #(when (and (= p (:predicate %)) (model/in-force? %)) (:value %)) (:assertions record)))

(defn- strings [record p] (filter string? (values record p)))

(defn- parent [record] (first (filter keyword? (values record :ead/parent))))

(defn- creators
  "`[name element]` for each creator, in document order: its EAD element
   (persname, corpname, famname, or origination itself) says what kind of
   name it is."
  [record]
  (distinct (for [v (values record :ead/creator)
                  :when (model/structured-value? v)
                  :let [{:keys [name element]} (:value/fields v)]
                  :when name]
              [name element])))

(defn- name-field
  "`[tag ind1]` for a creator named in EAD `element`, main entry or added."
  [element main?]
  (case element
    "corpname" [(if main? "110" "710") "2"]
    "famname"  [(if main? "100" "700") "3"]
    [(if main? "100" "700") "1"]))

(def written
  "The EAD natives this profile writes."
  #{:ead/unitid :ead/unittitle :ead/unitdate :ead/creator :ead/origination :ead/level
    :ead/abstract :ead/scopecontent :ead/parent :ead/dao :ead/physdesc :ead/accessrestrict :ead/bioghist})

(defn- datafield [tag ind1 ind2 subfields]
  (str "  <datafield tag=\"" tag "\" ind1=\"" ind1 "\" ind2=\"" ind2 "\">"
       (apply str (for [[c v] subfields] (str "<subfield code=\"" c "\">" (xml-escape v) "</subfield>")))
       "</datafield>"))

(defn- leader [record]
  (str "00000np" (if (parent record) "d" "c") "aa2200000   4500"))

(defn ->marcxml
  "`record`, an EAD unit, as a MARC21slim `<record>` under archival control."
  [record]
  (let [ctrl   (record-id/source-identifier record)
        title  (first (strings record :ead/unittitle))
        date   (first (strings record :ead/unitdate))
        cs     (creators record)
        fields (concat
                (for [v (strings record :ead/unitid)] (datafield "099" " " "9" [["a" v]]))
                (map-indexed (fn [i [n el]] (let [[tag ind1] (name-field el (zero? i))]
                                              (datafield tag ind1 " " [["a" n] ["e" "creator"]])))
                             cs)
                (when (or title date)
                  [(datafield "245" (if (seq cs) "1" "0") "0"
                              (cond-> [] title (conj ["a" title]) date (conj ["f" date])))])
                (for [v (strings record :ead/physdesc)] (datafield "300" " " " " [["a" v]]))
                (for [v (strings record :ead/level)] (datafield "351" " " " " [["c" v]]))
                (for [v (strings record :ead/accessrestrict)] (datafield "506" " " " " [["a" v]]))
                (for [v (strings record :ead/abstract)] (datafield "520" "3" " " [["a" v]]))
                (for [v (strings record :ead/scopecontent)] (datafield "520" "2" " " [["a" v]]))
                (for [v (strings record :ead/bioghist)] (datafield "545" " " " " [["a" v]]))
                (when-let [p (parent record)] [(datafield "773" "0" " " [["w" (name p)]])])
                (for [v (strings record :ead/dao)] (datafield "856" "4" " " [["u" v]])))]
    (str "<record xmlns=\"http://www.loc.gov/MARC21/slim\">\n"
         "  <leader>" (leader record) "</leader>\n"
         (when ctrl (str "  <controlfield tag=\"001\">" (xml-escape ctrl) "</controlfield>\n"))
         (str/join "\n" fields)
         "\n</record>")))

(defn export-losses
  "Every EAD native of `record` this profile does not write."
  [record]
  (for [p (distinct (keep #(let [p (:predicate %)] (when (and (= "ead" (namespace p)) (not (written p))) p))
                          (:assertions record)))]
    (dx/loss {:category :dropped :subject (:id record) :edge :export :source-field p
              :message (str (pr-str p) " has no field in the MARC21 archival profile (ADR 0050)")})))

(defn ->fonds-marcxml
  "The fonds-mode record: the fonds itself, or nothing for a component."
  [record]
  (when-not (parent record) (->marcxml record)))

(defn fonds-losses
  "The fonds mode's losses: a component is summarized by its fonds record, so
   each of its values is reported, under the predicate it was read as."
  [record]
  (if (parent record)
    (for [p (distinct (map :predicate (:assertions record)))]
      (dx/loss {:category :dropped :subject (:id record) :edge :export :source-field p
                :message "a component is summarized by its fonds record in the MARC21 fonds mode (ADR 0050)"}))
    (export-losses record)))

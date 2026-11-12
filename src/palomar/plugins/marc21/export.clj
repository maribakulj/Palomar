(ns palomar.plugins.marc21.export
  "Canonical → MARC21 (MARCXML) exporter (WP-4) — the reverse of
   `palomar.plugins.marc21`, closing the MARC21↔LRMoo round-trip (a V1 acceptance
   criterion) and, with it, the loss-aware account of what the *pivot* costs MARC.

   The pivot is the canonical floor, not MARC. MARC21 ingest is lossless (every
   field retained as `:marc21/*`), but only the bibliographic subset is lifted to
   `:canon/*`; so a round-trip *through the pivot* reconstructs only that subset.
   This exporter rebuilds MARCXML from `:canon/*` alone, and the loss report
   accounts the whole gap (ADR 0015):

   - **import edge, `:dropped`** — every native MARC field the floor never carried
     (subjects 6xx, physical 300, the leader/008, …): `pivot-dropped-*`. This is
     the floor's MARC coverage gap, measured per field against the retained
     natives — the MARC analogue of the Dublin Core spoke's report-at-ingest loss.
   - **export edge, `:under-specified`** — the many-to-one collapses the floor
     imposed (5 agent fields → one `:canon/agent`; 010/020/022/035 → one
     `:canon/identifier`; …): the original MARC tag is not recoverable.

   Agents follow their `:canon/role`: the first creator is the 100 main entry,
   other creators and contributors are 700 added entries, a publisher is 260 $b.
   A role-less agent counts as a creator, as in the Work key, so a record that
   keyed a Work still keys it after a MARC round-trip. Personal vs corporate vs
   meeting names (100/110/111, 700/710) are not on the floor, so all come back
   as personal-name fields — part of the agent collapse loss.

   Output is a `<record>` string in the MARC21slim namespace (correct XML
   escaping), re-importable through `palomar.plugins.marc21`; `001` is emitted
   from the record's `:source` (its original control number) so the trip is
   id-stable. The floor carries no indicators (ingest keeps them only on the
   native field fragments), so blanks are emitted. Like
   the LRMoo/DC exporters this is kept out of the plugin `:exporter` slot to avoid
   a require cycle — wiring is a later assembly step."
  (:require [clojure.string :as str]
            [palomar.diagnostics :as dx]
            [palomar.mentions :as mentions]
            [palomar.plugins.agents :as agents]
            [palomar.plugins.export-coverage :as cov]
            [palomar.plugins.marc21 :as marc21]
            [palomar.plugins.qualified :as qualified]
            [palomar.plugins.record-id :as record-id]))

(def canon->marc
  "Ordered canonical→MARC `[predicate tag subfield]`. Several MARC fields
   collapsed onto each multi-source canonical predicate at import; export picks
   one tag back (the most generic) and reports the collapse (see ns doc)."
  [[:canon/title          "245" "a"]
   [:canon/uniform-title  "240" "a"]
   [:canon/agent          "700" "a"]    ; per :canon/role, see `agent-fields`
   [:canon/date           "260" "c"]    ; 264 also -> date
   [:canon/identifier     "010" "a"]    ; 020/022/035 also -> identifier
   [:canon/digital-object "856" "u"]
   [:canon/note           "500" "a"]    ; per :canon/note-kind, see `note-fields`
   [:canon/rights         "540" "a"]])  ; a URI in $u, see `rights-fields`

(defn- canon-literals
  "Record floor literal values for canonical `pred` (record- or fragment-level
   string assertions). The MARC mapping is flat, so these are record-level."
  [record pred]
  (->> (:assertions record)
       (filter #(and (= pred (:predicate %)) (string? (:value %))))
       (map :value)))

(defn- repeat-relators
  "The subfields a 7XX that repeats the main entry's name `nm` carries after
   its `$a`, so the two fields say which is which (review of lot O3): from a
   MARC21 source, the `$e` and `$4` of the occurrence it is (`palomar.mentions`,
   the first agent occurrence of that name and `role` past the main entry);
   from any other, `$e` the floor's role (`contributor`), the source's codes
   being of another scheme."
  [record nm role]
  (let [ms (filter #(and (= :agent (:type %)) (= nm (:name %)) (= role (:responsibility %))) (mentions/mentions record))
        m  (first ms)
        sf (get-in m [:source :subfields])]
    (if (and m (= :marc21 (get-in m [:source :format])))
      (vec (concat (for [[c v] sf :when (and (= "e" c) (not (str/blank? v)))] ["e" (str/trim v)])
                   (for [[c v] sf :when (and (= "4" c) (not (str/blank? v)))] ["4" (str/trim v)])))
      (if role [["e" (name role)]] []))))

(defn- agent-fields
  "`[tag code value]` for each of `record`'s agents, by role: the first creator
   (stated, else role-less, see `agents/creators`) is the 100 main entry; a
   publisher is 260 $b; every other agent is a 700 added entry. One field per
   tag and name: a creator who is also the publisher is 100 and 260 $b. The
   main entry is one occurrence (lot O3): another agent of its name, a
   contributor, is a 700, not dropped as the main entry's double, with its
   relator (`repeat-relators`), so that the two fields say which is which."
  [record]
  (let [occs  (agents/occurrences record)
        main  (first (agents/creators record))
        main? (fn [{nm :name role :role}]
                (and (= nm main) (if (some #(= :creator (:role %)) occs) (= :creator role) (nil? role))))]
    (distinct
     (concat
      (when main [["100" "a" main]])
      ;; a provider of a digital surrogate is not written
      ;; (`export-coverage/provider-losses`)
      (for [{nm :name role :role :as o} occs
            :when (and (not= :provider role) (or (= :publisher role) (not (main? o))))]
        (cond
          (= :publisher role) ["260" "b" nm]
          (= nm main)         ["700" "a" nm nil (repeat-relators record nm role)]
          :else               ["700" "a" nm]))))))

(def ^:private note-kind->tag
  "MARC field for a note of a known `:canon/note-kind`; a general note is 500."
  {:abstract "520" :contents "505" :credits "511"})

(defn- note-fields
  "`[tag code value]` for each of `record`'s notes, by `:canon/note-kind`."
  [record]
  (for [{:keys [value coord]} (qualified/values record :canon/note :canon/note-kind)]
    [(get note-kind->tag coord "500") "a" value]))

(defn- rights-fields
  "`[tag code value pre]` for each of `record`'s rights statements: 540, the
   terms governing use and reproduction, `$u` for a URI and `$a` for a text;
   rights of the digital surrogate only are preceded by `$3 digital
   reproduction` (materials specified)."
  [record]
  (for [{v :value scope :canon/rights-scope} (qualified/occurrences record :canon/rights [:canon/rights-scope])]
    (cond-> ["540" (if (re-matches #"(?i)https?://\S+" v) "u" "a") v]
      scope (conj [["3" "digital reproduction"]]))))

(defn- xml-escape [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- datafield-line [tag subfields]
  (str "  <datafield tag=\"" tag "\" ind1=\" \" ind2=\" \">"
       (apply str (for [[code value] subfields]
                    (str "<subfield code=\"" code "\">" (xml-escape value) "</subfield>")))
       "</datafield>"))

(defn- datafields
  "`[tag [[code value] …]]` for `record`, sorted by tag (MARC field order). Each
   field is its own datafield except 260, whose publishers ($b) and dates ($c)
   make one imprint field, as MARC has them."
  [record]
  (let [fields (for [[p tag code] canon->marc
                     f (case p
                         :canon/agent (agent-fields record)
                         :canon/note  (note-fields record)
                         :canon/rights (rights-fields record)
                         (for [v (canon-literals record p)] [tag code v]))]
                 f)
        imprint (->> fields (filter #(= "260" (first %))) (sort-by second) (mapv (fn [[_ c v]] [c v])))]
    (sort-by first
             (cond-> (vec (for [[tag code v pre post] fields :when (not= "260" tag)] [tag (into (conj (vec pre) [code v]) post)]))
               (seq imprint) (conj ["260" imprint])))))

(defn ->marcxml
  "Render `record`'s canonical content as a MARC21slim `<record>` document, or
   `nil` if it carries nothing exportable. `001` comes from `:source` (the
   original control number) for an id-stable round-trip. The floor carries no
   indicators, so every datafield has blank indicators (`ind1`/`ind2` \" \"),
   which MARC reads as undefined, not as a stated value."
  [record]
  (let [ctrl  (record-id/source-identifier record)
        lines (cond-> []
                ctrl    (conj (str "  <controlfield tag=\"001\">"
                                   (xml-escape ctrl) "</controlfield>"))
                :always (into (for [[tag subfields] (datafields record)]
                                (datafield-line tag subfields))))]
    (when (seq lines)
      (str "<record xmlns=\"http://www.loc.gov/MARC21/slim\">\n"
           (str/join "\n" lines)
           "\n</record>"))))

;; --- loss accounting --------------------------------------------------------

(def collapsed-predicates
  "Canonical predicates several MARC fields collapsed onto at import (derived from
   the marc21 mapping's many-to-one structure) — their MARC tag/flavour is not
   recoverable on export. `:canon/note` is not among them any more: each note
   field's kind travels as `:canon/note-kind`, so 500/505/511/520 come back;
   nor is `:canon/rights`: a URI is written to 540 $u, a text to 540 $a."
  (->> marc21/mapping
       (group-by :mapping/to)
       (keep (fn [[to rules]]
               (when (> (count (distinct (map :mapping/from rules))) 1) to)))
       (remove #{:canon/note :canon/rights})
       set))

(def ^:private mapped-natives
  (into #{} (map :mapping/from) marc21/mapping))

(def ^:private otherwise-reconstructed
  "Native MARC fields the exporter rebuilds other than from the canonical floor —
   currently just `001`, emitted from `:source` as the control number, so it does
   round-trip and must not be reported as a floor-coverage drop."
  #{:marc21/f001})

(defn pivot-dropped-fields
  "Distinct native `:marc21/f*` predicates on `record` that no canonical mapping
   lifts and the exporter does not otherwise reconstruct — the MARC fields a
   round-trip through the floor actually drops. Sorted."
  [record]
  (->> (:assertions record)
       (map :predicate)
       (filter #(= "marc21" (namespace %)))
       (remove mapped-natives)
       (remove otherwise-reconstructed)
       distinct
       (sort-by str)))

(def ^:private written-canon
  "The canonical fields and coords the MARC21 export writes (`canon->marc`; the
   role picks 100/700/260 $b)."
  (into #{:canon/role :canon/note-kind :canon/rights-scope} (map first) canon->marc))

(defn- unplaced-note-kinds
  "One `:dropped` loss per note kind MARC21 has no field for (an attribution):
   such a note is written as a 500 general note, its kind is not."
  [record]
  (for [k (distinct (keep :coord (qualified/values record :canon/note :canon/note-kind)))
        :when (not (contains? note-kind->tag k))]
    (dx/loss {:category :dropped :subject (:id record) :edge :export :source-field :canon/note-kind
              :value k :aspect :qualifier
              :message (str "a " (name k) " note is written as a 500 general note; its kind is not")})))

(defn export-losses
  "Loss for the canonical→MARC round-trip (ADR 0015): the floor-coverage drops
   (`:import :dropped`, per native field the floor never carried), the stated
   indicators written blank (`:export :dropped`), the
   many-to-one collapses (`:export :under-specified`), and one `:export :dropped`
   per other predicate the export does not write (`export-coverage/unexpressed`:
   a relation, a language, another spoke's native fields)."
  [record]
  (concat
   (cov/unexpressed record written-canon "marc21" "MARC21")
   (unplaced-note-kinds record)
   (when (some #(some (fn [k] (not (str/blank? (get-in % [:locator k])))) [:ind1 :ind2])
               (:fragments record))
     [(dx/loss {:category     :dropped
                :subject      (:id record)
                :edge         :export
                :source-field :marc21/indicators
                :aspect       :qualifier
                :message      "field indicators are not carried by the floor; every datafield is written with blank indicators"})])
   (cov/provider-losses record "MARC21")
   (cov/occurrence-losses record "MARC21")
   (for [f (pivot-dropped-fields record)]
     (dx/loss {:category     :dropped
               :subject      (:id record)
               :edge         :import
               :source-field f
               :message      (str (name f) " not carried onto the canonical floor — dropped on a pivot round-trip")}))
   (for [p (sort-by str collapsed-predicates)
         :when (seq (canon-literals record p))]
     (dx/loss {:category     :under-specified
               :subject      (:id record)
               :edge         :export
               :source-field p
               :aspect       :granularity
               :message      (str (name p) " collapsed several MARC fields at import; the original MARC tag is not recoverable")}))))

(defn exporter
  "ADR 0007 exporter: render the canonical view of `records` as MARC21slim XML and
   report the round-trip loss (ADR 0015).
   `(fn [opts records] -> {:output String :diagnostics [...]})`."
  [_opts records]
  {:output      (->> records (keep ->marcxml) (str/join "\n"))
   :diagnostics (into [] (mapcat export-losses) records)})

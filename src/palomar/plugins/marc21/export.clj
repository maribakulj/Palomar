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

   Output is a `<record>` string in the MARC21slim namespace (correct XML
   escaping), re-importable through `palomar.plugins.marc21`; `001` is emitted
   from the record's `:source` (its original control number) so the trip is
   id-stable. Indicators were not captured at ingest, so blanks are emitted. Like
   the LRMoo/DC exporters this is kept out of the plugin `:exporter` slot to avoid
   a require cycle — wiring is a later assembly step."
  (:require [clojure.string :as str]
            [palomar.diagnostics :as dx]
            [palomar.plugins.marc21 :as marc21]))

(def canon->marc
  "Ordered canonical→MARC `[predicate tag subfield]`. Several MARC fields
   collapsed onto each multi-source canonical predicate at import; export picks
   one tag back (the most generic) and reports the collapse (see ns doc)."
  [[:canon/title          "245" "a"]
   [:canon/uniform-title  "240" "a"]
   [:canon/agent          "700" "a"]    ; 100/110/111/710 also -> agent (role lost)
   [:canon/date           "260" "c"]    ; 264 also -> date
   [:canon/identifier     "010" "a"]    ; 020/022/035 also -> identifier
   [:canon/digital-object "856" "u"]
   [:canon/note           "500" "a"]])  ; 505/511/520 also -> note

(defn- canon-literals
  "Record floor literal values for canonical `pred` (record- or fragment-level
   string assertions). The MARC mapping is flat, so these are record-level."
  [record pred]
  (->> (:assertions record)
       (filter #(and (= pred (:predicate %)) (string? (:value %))))
       (map :value)))

(defn- xml-escape [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- datafield-line [tag code value]
  (str "  <datafield tag=\"" tag "\" ind1=\" \" ind2=\" \">"
       "<subfield code=\"" code "\">" (xml-escape value) "</subfield>"
       "</datafield>"))

(defn ->marcxml
  "Render `record`'s canonical content as a MARC21slim `<record>` document, or
   `nil` if it carries nothing exportable. `001` comes from `:source` (the
   original control number) for an id-stable round-trip."
  [record]
  (let [ctrl  (:source record)
        lines (cond-> []
                ctrl    (conj (str "  <controlfield tag=\"001\">"
                                   (xml-escape ctrl) "</controlfield>"))
                :always (into (for [[p tag code] canon->marc
                                    v            (canon-literals record p)]
                                (datafield-line tag code v))))]
    (when (seq lines)
      (str "<record xmlns=\"http://www.loc.gov/MARC21/slim\">\n"
           (str/join "\n" lines)
           "\n</record>"))))

;; --- loss accounting --------------------------------------------------------

(def collapsed-predicates
  "Canonical predicates several MARC fields collapsed onto at import (derived from
   the marc21 mapping's many-to-one structure) — their MARC tag/flavour is not
   recoverable on export."
  (->> marc21/mapping
       (group-by :mapping/to)
       (keep (fn [[to rules]]
               (when (> (count (distinct (map :mapping/from rules))) 1) to)))
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

(defn export-losses
  "Loss for the canonical→MARC round-trip (ADR 0015): the floor-coverage drops
   (`:import :dropped`, per native field the floor never carried) and the
   many-to-one collapses (`:export :under-specified`)."
  [record]
  (concat
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
               :message      (str (name p) " collapsed several MARC fields at import; the original MARC tag is not recoverable")}))))

(defn exporter
  "ADR 0007 exporter: render the canonical view of `records` as MARC21slim XML and
   report the round-trip loss (ADR 0015).
   `(fn [opts records] -> {:output String :diagnostics [...]})`."
  [_opts records]
  {:output      (->> records (keep ->marcxml) (str/join "\n"))
   :diagnostics (into [] (mapcat export-losses) records)})

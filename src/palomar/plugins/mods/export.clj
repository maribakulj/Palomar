(ns palomar.plugins.mods.export
  "Canonical → MODS (LoC) exporter (WP-4) — the reverse of `palomar.plugins.mods`,
   closing the **third** spoke round-trip (source MODS → canonical floor → MODS),
   after Dublin Core and MARC21. With the importer it lets the loss model be
   exercised as a real round-trip on a *nested* format.

   The pivot is the canonical floor, not MODS. MODS ingest lifts only the
   bibliographic core to `:canon/*`; a round-trip *through the floor* therefore
   reconstructs only that core, and the loss report accounts the gap (ADR 0015):

   - **export edge, `:under-specified`** — the many-to-one collapses the floor
     imposes. MODS distinguishes a `name` from an `originInfo/publisher` and an
     `abstract` from a `note`, but both pairs collapse onto one `:canon/agent` /
     one `:canon/note` at import; on export every agent is emitted as `<name>` and
     every note as `<note>`, and the distinction is unrecoverable.
   - **import edge, `:dropped`** — `titleInfo/subTitle` is captured natively
     (`:mods/subtitle`) but the floor has no home for it, so it never reaches
     `:canon/*` and does not survive the trip. (The unmodelled top-level elements —
     subject, language, physicalDescription… — were already reported as loss by the
     *importer* at ingest; they are not retained as assertions, so they surface
     there, not here.)

   Output is a `<mods>` document in the MODS v3 namespace (correct XML escaping),
   re-importable through `palomar.plugins.mods`; `recordInfo/recordIdentifier` is
   emitted from the record's `:source` so the trip is id-stable. Like the LRMoo/DC/
   MARC21 exporters this is kept out of the plugin `:exporter` slot to avoid a
   require cycle — wiring is the `palomar.convert` assembly step."
  (:require [clojure.string :as str]
            [palomar.diagnostics :as dx]
            [palomar.plugins.mods :as mods]))

(defn- xml-escape [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(def canon->mods
  "Ordered canonical→MODS `[predicate open-tags close-tags]`. MODS is nested, so
   each value is wrapped in a small element tree rather than a flat tag. `name` and
   `originInfo/publisher` both collapsed onto `:canon/agent` at import, and
   `abstract`/`note` onto `:canon/note`; export emits the generic element and
   reports the collapse (see ns doc). Ordered for a deterministic serialisation."
  [[:canon/title          "<titleInfo><title>"        "</title></titleInfo>"]
   [:canon/uniform-title  "<titleInfo type=\"uniform\"><title>" "</title></titleInfo>"]
   [:canon/agent          "<name><namePart>"          "</namePart></name>"]          ; publisher also -> agent
   [:canon/date           "<originInfo><dateIssued>"   "</dateIssued></originInfo>"]
   [:canon/identifier     "<identifier>"               "</identifier>"]
   [:canon/note           "<note>"                     "</note>"]                     ; abstract also -> note
   [:canon/digital-object "<location><url>"            "</url></location>"]])

(defn- canon-literals
  "Record floor literal values for canonical `pred` (string assertions). The MODS
   mapping is flat at the floor, so these are record-level."
  [record pred]
  (->> (:assertions record)
       (filter #(and (= pred (:predicate %)) (string? (:value %))))
       (map :value)))

(defn- element-line [open close value]
  (str "  " open (xml-escape value) close))

(defn ->mods-xml
  "Render `record`'s canonical content as a MODS v3 `<mods>` document, or `nil` if
   it carries nothing exportable. `recordInfo/recordIdentifier` comes from `:source`
   (the original record id) for an id-stable round-trip."
  [record]
  (let [rid   (:source record)
        body  (for [[p open close] canon->mods
                    v              (canon-literals record p)]
                (element-line open close v))
        lines (cond-> (vec body)
                rid (conj (element-line "<recordInfo><recordIdentifier>"
                                        "</recordIdentifier></recordInfo>" rid)))]
    (when (seq lines)
      (str "<mods xmlns=\"http://www.loc.gov/mods/v3\" version=\"3.7\">\n"
           (str/join "\n" lines)
           "\n</mods>"))))

;; --- loss accounting --------------------------------------------------------

(def collapsed-predicates
  "Canonical predicates several MODS elements collapsed onto at import (derived from
   the mods mapping's many-to-one structure: name+publisher → agent, abstract+note
   → note) — the original MODS element is not recoverable on export."
  (->> mods/mapping
       (group-by :mapping/to)
       (keep (fn [[to rules]]
               (when (> (count (distinct (map :mapping/from rules))) 1) to)))
       set))

(def ^:private mapped-natives
  (into #{} (map :mapping/from) mods/mapping))

(defn pivot-dropped-fields
  "Distinct native `:mods/*` predicates on `record` that no canonical mapping lifts
   — the captured-but-unmapped natives a round-trip through the floor drops
   (`:mods/subtitle`). Sorted."
  [record]
  (->> (:assertions record)
       (map :predicate)
       (filter #(= "mods" (namespace %)))
       (remove mapped-natives)
       distinct
       (sort-by str)))

(defn export-losses
  "Loss for the canonical→MODS round-trip (ADR 0015): the captured-but-unmapped
   natives dropped through the floor (`:import :dropped`), and the many-to-one
   collapses MODS can express but the floor cannot (`:export :under-specified`)."
  [record]
  (concat
   (for [f (pivot-dropped-fields record)]
     (dx/loss {:category     :dropped
               :subject      (:id record)
               :edge         :import
               :source-field f
               :message      (str (name f) " captured natively but not lifted to the floor — dropped on a pivot round-trip")}))
   (for [p (sort-by str collapsed-predicates)
         :when (seq (canon-literals record p))]
     (dx/loss {:category     :under-specified
               :subject      (:id record)
               :edge         :export
               :source-field p
               :message      (str (name p) " collapsed several MODS elements at import; the original element is not recoverable")}))))

(defn exporter
  "ADR 0007 exporter: render the canonical view of `records` as MODS v3 XML and
   report the round-trip loss (ADR 0015).
   `(fn [opts records] -> {:output String :diagnostics [...]})`."
  [_opts records]
  {:output      (->> records (keep ->mods-xml) (str/join "\n"))
   :diagnostics (into [] (mapcat export-losses) records)})

(ns palomar.plugins.mods.export
  "Canonical → MODS (LoC) exporter (WP-4) — the reverse of `palomar.plugins.mods`,
   closing the **third** spoke round-trip (source MODS → canonical floor → MODS),
   after Dublin Core and MARC21. With the importer it lets the loss model be
   exercised as a real round-trip on a *nested* format.

   The pivot is the canonical floor, not MODS. MODS ingest lifts only the
   bibliographic core to `:canon/*`; a round-trip *through the floor* therefore
   reconstructs only that core, and the loss report accounts the gap (ADR 0015):

   - **export edge, `:under-specified`** — the many-to-one collapses the floor
     imposes. An `abstract` and a `note` both become `:canon/note`, but the
     abstract keeps a `:canon/note-kind`, so each comes back as its own element
     (as do a MARC 505 contents note, `<tableOfContents>`, and a 511 credits
     note, `<note type=\"performers\">`).
     Agents keep their `:canon/role`: a publisher comes back as
     `originInfo/publisher`, every other agent as `<name>`. A `<name>` does not
     carry its creator/contributor role here, so that distinction (from another
     spoke) is reported as `:under-specified` when present.
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
            [palomar.plugins.agents :as agents]
            [palomar.plugins.export-coverage :as cov]
            [palomar.plugins.mods :as mods]
            [palomar.plugins.qualified :as qualified]))

(defn- xml-escape [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(def canon->mods
  "Ordered canonical→MODS `[predicate open-tags close-tags]`. MODS is nested, so
   each value is wrapped in a small element tree rather than a flat tag.
   `abstract`/`note` collapsed onto `:canon/note` at import; export emits the
   generic element and reports the collapse (see ns doc). Agents are placed by
   role (`agent-elements`). Ordered for a deterministic serialisation."
  [[:canon/title          "<titleInfo><title>"        "</title></titleInfo>"]
   [:canon/uniform-title  "<titleInfo type=\"uniform\"><title>" "</title></titleInfo>"]
   [:canon/agent          "<name><namePart>"          "</namePart></name>"]          ; publisher -> originInfo, see `agent-elements`
   [:canon/date           "<originInfo><dateIssued>"   "</dateIssued></originInfo>"]
   [:canon/identifier     "<identifier>"               "</identifier>"]
   [:canon/note           "<note>"                     "</note>"]                     ; per :canon/note-kind, see `note-elements`
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

(def ^:private note-kind->element
  "MODS element for a note of a known `:canon/note-kind`; a general note is `<note>`."
  {:abstract ["<abstract>" "</abstract>"]
   :contents ["<tableOfContents>" "</tableOfContents>"]
   :credits  ["<note type=\"performers\">" "</note>"]})

(defn- note-elements
  "`[open close value]` for each of `record`'s notes, by `:canon/note-kind`."
  [record open close]
  (for [{:keys [value coord]} (qualified/values record :canon/note :canon/note-kind)]
    (let [[o c] (get note-kind->element coord [open close])] [o c value])))

(defn- agent-elements
  "`[open close value]` for each of `record`'s agents: a publisher is
   `originInfo/publisher`, every other agent a `name` (`open`/`close`)."
  [record open close]
  (for [{nm :name role :role} (distinct (agents/occurrences record))]
    (if (= :publisher role)
      ["<originInfo><publisher>" "</publisher></originInfo>" nm]
      [open close nm])))

(defn ->mods-xml
  "Render `record`'s canonical content as a MODS v3 `<mods>` document, or `nil` if
   it carries nothing exportable. `recordInfo/recordIdentifier` comes from `:source`
   (the original record id) for an id-stable round-trip."
  [record]
  (let [rid   (:source record)
        body  (for [[p open close] canon->mods
                    [open close v] (case p
                                     :canon/agent (agent-elements record open close)
                                     :canon/note  (note-elements record open close)
                                     (for [v (canon-literals record p)] [open close v]))]
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

(defn- name-role-lost?
  "True when an agent exported as a bare `<name>` had a stated creator or
   contributor role — the one agent distinction this export does not carry."
  [record]
  (boolean (some #(#{:creator :contributor} (:role %)) (agents/occurrences record))))

(def ^:private written-canon
  "The canonical fields and coords the MODS export writes (`canon->mods`; the role
   picks name vs originInfo/publisher)."
  (into #{:canon/role :canon/note-kind} (map first) canon->mods))

(defn- expressed?
  "What the MODS export accounts for itself: the canonical fields it writes, and
   every `:mods/*` native (`pivot-dropped-fields` reports the unmapped ones)."
  [p]
  (or (contains? written-canon p) (= "mods" (namespace p))))

(defn export-losses
  "Loss for the canonical→MODS round-trip (ADR 0015): the captured-but-unmapped
   natives dropped through the floor (`:import :dropped`), the many-to-one
   collapses MODS can express but the floor cannot (`:export :under-specified`),
   and one `:export :dropped` per other predicate it does not write
   (`export-coverage/unexpressed`)."
  [record]
  (concat
   (cov/unexpressed record expressed? "MODS")
   (for [f (pivot-dropped-fields record)]
     (dx/loss {:category     :dropped
               :subject      (:id record)
               :edge         :import
               :source-field f
               :message      (str (name f) " captured natively but not lifted to the floor — dropped on a pivot round-trip")}))
   (for [p (sort-by str collapsed-predicates)
         :when (and (seq (canon-literals record p))
                    (case p
                      :canon/agent (name-role-lost? record)
                      :canon/note  false              ; the kind brings the element back
                      true))]
     (dx/loss {:category     :under-specified
               :subject      (:id record)
               :edge         :export
               :source-field p
               :message      (if (= :canon/agent p)
                               "creator/contributor role not carried by MODS <name> on export"
                               (str (name p) " collapsed several MODS elements at import; the original element is not recoverable"))}))))

(defn exporter
  "ADR 0007 exporter: render the canonical view of `records` as MODS v3 XML and
   report the round-trip loss (ADR 0015).
   `(fn [opts records] -> {:output String :diagnostics [...]})`."
  [_opts records]
  {:output      (->> records (keep ->mods-xml) (str/join "\n"))
   :diagnostics (into [] (mapcat export-losses) records)})

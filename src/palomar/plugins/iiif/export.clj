(ns palomar.plugins.iiif.export
  "Canonical → IIIF Presentation 3.0 manifest exporter (WP-4) — the reverse of
   `palomar.plugins.iiif`, closing the **fifth and final** spoke round-trip (source
   manifest → canonical floor → manifest), after DC, MARC21 and MODS.

   IIIF describes a *digitised object*, so its floor mapping is the narrowest of the
   spokes: only `:canon/title` (→ `label`), `:canon/identifier` (→ the manifest
   `id`), `:canon/digital-object` (→ the `Image` bodies painted on canvases) and
   `:canon/note` (→ `summary`). The exporter rebuilds exactly that manifest tree,
   and the loss report (ADR 0015) accounts the gap:

   - **export edge, `:dropped`** — the floor predicates IIIF has no home for. A
     manifest carries no bibliographic creator or date, so `:canon/agent` and
     `:canon/date` (and any other non-IIIF floor predicate) cannot be expressed and
     are dropped. This is IIIF's floor-coverage gap, the converse of the importer's
     report-at-ingest.
   - **export edge, `:under-specified`** — `summary` and `metadata` both collapsed
     onto `:canon/note` at import; on export notes are emitted as `summary` (a
     single, display-only field the importer reads first-language), so the
     summary/metadata distinction — and, for more than one note, the surplus — is
     not recoverable.

   Output is a Presentation 3.0 manifest (`clojure.data.json`, slashes unescaped so
   URIs stay readable), re-importable through `palomar.plugins.iiif`; the manifest
   `id` comes from `:source` (the original manifest URI) so the trip is id-stable.
   Synthesised canvas/annotation ids are derived from that base and are ignored by
   the importer (which reads only the `Image` body ids), so the trip is idempotent.
   Kept out of the plugin `:exporter` slot to avoid a require cycle — wired by
   `palomar.convert`."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [palomar.diagnostics :as dx]))

(def ^:private context "http://iiif.io/api/presentation/3/context.json")

(def ^:private emitted-predicates
  "The canonical floor predicates the IIIF manifest can express (title, the id,
   the digitised images, a display summary). Any other floor content predicate is
   dropped on export — IIIF has no home for it."
  #{:canon/title :canon/identifier :canon/digital-object :canon/note})

(defn- canon-literals [record pred]
  (->> (:assertions record)
       (filter #(and (= pred (:predicate %)) (string? (:value %))))
       (map :value)))

(defn- lang-map
  "A IIIF 3.0 language map with the undetermined-language key (the floor carries no
   language qualifier)."
  [strings]
  (array-map "none" (vec strings)))

(defn- manifest-base
  "Strip a trailing `/manifest(.json)` from the manifest URI to form a base for the
   synthesised canvas/annotation ids (which the importer ignores)."
  [id]
  (str/replace (str id) #"/manifest(\.json)?$" ""))

(defn- canvas [base i image-id]
  (array-map
   "id"    (str base "/canvas/" i)
   "type"  "Canvas"
   "items" [(array-map
             "id"    (str base "/canvas/" i "/page")
             "type"  "AnnotationPage"
             "items" [(array-map
                       "id"         (str base "/canvas/" i "/annotation")
                       "type"       "Annotation"
                       "motivation" "painting"
                       "body"       (array-map "id" image-id "type" "Image"))])]))

(defn manifest
  "Build the IIIF Presentation 3.0 manifest map for `record` from its canonical
   floor, or `nil` if it carries nothing exportable. `id` comes from `:source` (the
   original manifest URI), falling back to the first `:canon/identifier`."
  [record]
  (let [id     (or (:source record) (first (canon-literals record :canon/identifier)))
        title  (first (canon-literals record :canon/title))
        images (canon-literals record :canon/digital-object)
        notes  (canon-literals record :canon/note)
        base   (manifest-base id)]
    (when (or title (seq images) id)
      (cond-> (array-map "@context" context)
        id           (assoc "id" id)
        :always      (assoc "type" "Manifest")
        title        (assoc "label" (lang-map [title]))
        (seq notes)  (assoc "summary" (lang-map notes))
        (seq images) (assoc "items" (vec (map-indexed (fn [i img] (canvas base (inc i) img))
                                                      images)))))))

(defn ->json
  "Render `record`'s canonical content as a IIIF Presentation 3.0 manifest JSON
   string, or `nil` if it carries nothing exportable."
  [record]
  (when-let [m (manifest record)]
    (json/write-str m :escape-slash false)))

;; --- loss accounting --------------------------------------------------------

(defn- present-content-predicates
  "Distinct `:canon/*` content predicates on `record` (excluding the language
   qualifier and the loss marker)."
  [record]
  (->> (:assertions record)
       (map :predicate)
       (filter #(= "canon" (namespace %)))
       (remove #{:canon/lang :canon/loss-marker})
       distinct))

(defn export-losses
  "Loss for the canonical→IIIF projection (ADR 0015): floor predicates IIIF cannot
   express (`:export :dropped`, e.g. agent/date), the surplus identifiers beyond the
   single manifest `id` (`:export :dropped`), and the note collapse onto a
   display-only `summary` (`:export :under-specified`)."
  [record]
  (concat
   (for [p     (present-content-predicates record)
         :when (not (contains? emitted-predicates p))]
     (dx/loss {:category     :dropped
               :subject      (:id record)
               :edge         :export
               :source-field p
               :message      (str (name p) " has no home in a IIIF manifest — dropped on export")}))
   (when (> (count (canon-literals record :canon/identifier)) 1)
     [(dx/loss {:category     :dropped
                :subject      (:id record)
                :edge         :export
                :source-field :canon/identifier
                :message      "a IIIF manifest carries a single id; surplus identifiers are dropped"})])
   (when (seq (canon-literals record :canon/note))
     [(dx/loss {:category     :under-specified
                :subject      (:id record)
                :edge         :export
                :source-field :canon/note
                :message      "notes emitted as a display-only summary; summary/metadata distinction (and any surplus) not recoverable"})])))

(defn exporter
  "ADR 0007 exporter: render the canonical view of `records` as IIIF Presentation
   3.0 manifests and report the projection loss (ADR 0015).
   `(fn [opts records] -> {:output String :diagnostics [...]})`."
  [_opts records]
  {:output      (->> records (keep ->json) (str/join "\n"))
   :diagnostics (into [] (mapcat export-losses) records)})

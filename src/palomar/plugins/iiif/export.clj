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
     onto `:canon/note` at import; on export notes are emitted as `summary`, so
     the summary/metadata distinction is not recoverable.

   A manifest is emitted only when it meets the Presentation 3.0 MUSTs: an HTTP(S)
   `id`, a `label` with at least one entry, and `items` with at least one Canvas;
   and each Canvas MUST have an extent, so an image is placed only when its
   `:canon/width`/`:canon/height` are known (the IIIF importer keeps them; a MARC
   856 URL has none). Otherwise nothing is emitted for the record and a warning
   loss names what is missing (`missing-required`) — no structurally plausible but
   invalid manifest. Titles and notes keep their `:canon/lang` in the language
   maps (`none` when the floor has no language).

   Output is a Presentation 3.0 manifest (`clojure.data.json`, slashes unescaped so
   URIs stay readable), re-importable through `palomar.plugins.iiif`; the manifest
   `id` comes from `:source` (the original manifest URI) so the trip is id-stable.
   Synthesised canvas/annotation ids are derived from that base and are ignored by
   the importer (which reads only the `Image` body ids), so the trip is idempotent.
   Kept out of the plugin `:exporter` slot to avoid a require cycle — wired by
   `palomar.convert`."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [palomar.diagnostics :as dx]
            [palomar.model :as model]))

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

(defn- occurrences
  "Record-level occurrences of canonical `pred` as `{:value …coords}`, resolving a
   fragment reference to its literal and its qualifier coords (`:lang`, `:width`,
   `:height`). Flat literals carry no coords."
  [record pred]
  (let [rid (:id record)
        by  (group-by :subject (:assertions record))
        crd (fn [fas k] (some #(when (= k (:predicate %)) (:value %)) fas))]
    (for [a     (:assertions record)
          :when (and (= pred (:predicate a)) (= rid (:subject a)))
          :let  [v (:value a)
                 fas (when (model/reference-value? v) (get by (:value/target v)))
                 lit (if fas (some #(when (and (= pred (:predicate %)) (string? (:value %))) (:value %)) fas) v)]
          :when (string? lit)]
      {:value  lit
       :lang   (crd fas :canon/lang)
       :width  (crd fas :canon/width)
       :height (crd fas :canon/height)})))

(defn- lang-map
  "A IIIF 3.0 language map of `occs`, grouped by `:lang` in first-seen order; an
   occurrence with no language goes under `none`."
  [occs]
  (reduce (fn [m {:keys [value lang]}] (update m (or lang "none") (fnil conj []) value))
          (array-map) occs))

(defn- placeable? [{:keys [width height]}] (and (pos-int? width) (pos-int? height)))

(defn- manifest-base
  "Strip a trailing `/manifest(.json)` from the manifest URI to form a base for the
   synthesised canvas/annotation ids (which the importer ignores)."
  [id]
  (str/replace (str id) #"/manifest(\.json)?$" ""))

(defn- canvas [base i {image-id :value :keys [width height]}]
  (array-map
   "id"     (str base "/canvas/" i)
   "type"   "Canvas"
   "height" height
   "width"  width
   "items"  [(array-map
              "id"    (str base "/canvas/" i "/page")
              "type"  "AnnotationPage"
              "items" [(array-map
                        "id"         (str base "/canvas/" i "/annotation")
                        "type"       "Annotation"
                        "motivation" "painting"
                        "body"       (array-map "id" image-id "type" "Image"))])]))

(defn- manifest-id
  "The manifest `id`: `:source` (the original manifest URI), falling back to the
   first `:canon/identifier`."
  [record]
  (or (:source record) (first (canon-literals record :canon/identifier))))

(defn missing-required
  "The Presentation 3.0 MUSTs `record`'s manifest would miss, as a sorted vector of
   property names (`[]` when it can be emitted): an HTTP(S) `id`, a `label` with at
   least one entry, `items` with at least one Canvas (an image with a known extent)."
  [record]
  (let [id (manifest-id record)]
    (cond-> []
      (not (and (string? id) (re-matches #"(?i)https?://\S+" id))) (conj "id")
      (empty? (occurrences record :canon/title))                     (conj "label")
      (not-any? placeable? (occurrences record :canon/digital-object)) (conj "items"))))

(defn manifest
  "Build the IIIF Presentation 3.0 manifest map for `record` from its canonical
   floor, or `nil` when it would not be a valid manifest (`missing-required`)."
  [record]
  (when (empty? (missing-required record))
    (let [id     (manifest-id record)
          titles (occurrences record :canon/title)
          images (filter placeable? (occurrences record :canon/digital-object))
          notes  (occurrences record :canon/note)
          base   (manifest-base id)]
      (cond-> (array-map "@context" context
                         "id"       id
                         "type"     "Manifest"
                         "label"    (lang-map titles))
        (seq notes) (assoc "summary" (lang-map notes))
        :always     (assoc "items" (vec (map-indexed (fn [i img] (canvas base (inc i) img)) images)))))))

(defn ->json
  "Render `record`'s canonical content as a IIIF Presentation 3.0 manifest JSON
   string, or `nil` if it carries nothing exportable."
  [record]
  (when-let [m (manifest record)]
    (json/write-str m :escape-slash false)))

;; --- loss accounting --------------------------------------------------------

(defn- present-content-predicates
  "Distinct `:canon/*` content predicates on `record` (excluding the qualifier
   coords and the loss marker)."
  [record]
  (->> (:assertions record)
       (map :predicate)
       (filter #(= "canon" (namespace %)))
       (remove #{:canon/lang :canon/role :canon/width :canon/height :canon/loss-marker})
       distinct))

(defn export-losses
  "Loss for the canonical→IIIF projection (ADR 0015): a record whose manifest is
   not emitted (`:dropped` warning naming the missing MUSTs), images with no
   Canvas extent, floor predicates IIIF cannot express (`:export :dropped`, e.g.
   agent/date), the surplus identifiers beyond the single manifest `id`
   (`:export :dropped`), and the note collapse onto `summary`
   (`:export :under-specified`)."
  [record]
  (concat
   (when-let [missing (seq (missing-required record))]
     [(dx/loss {:category     :dropped
                :severity     :warning
                :subject      (:id record)
                :edge         :export
                :source-field :iiif/manifest
                :message      (str "no IIIF manifest emitted: Presentation 3.0 requires "
                                   (str/join ", " missing))})])
   (when-let [n (seq (remove placeable? (occurrences record :canon/digital-object)))]
     [(dx/loss {:category     :dropped
                :subject      (:id record)
                :edge         :export
                :source-field :canon/digital-object
                :message      (str (count n) " digital object(s) with no canvas extent (width/height) — "
                                   "a IIIF Canvas must have one, so they are not placed")})])
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

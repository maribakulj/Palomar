(ns palomar.plugins.iiif.export
  "Canonical → IIIF Presentation 3.0 manifest exporter (WP-4) — the reverse of
   `palomar.plugins.iiif`, closing the **fifth and final** spoke round-trip (source
   manifest → canonical floor → manifest), after DC, MARC21 and MODS.

   IIIF describes a *digitised object*, so its floor mapping is the narrowest of the
   spokes: only `:canon/title` (→ `label`), `:canon/identifier` (→ the manifest
   `id`), `:canon/digital-object` (→ the `Image` bodies painted on canvases, the
   Image's own `rights`, `:canon/image-rights`, on the body and its Canvas's,
   `:canon/canvas-rights`, on the Canvas), `:canon/note` (→ `summary`; an
   attribution note with a label → `requiredStatement`, its label and value
   each in its own language), `:canon/rights` (→ `rights`) and the agents of
   role `:provider` with a URI id (→ `provider`). The exporter
   rebuilds exactly that manifest tree, and the loss report (ADR 0015) accounts
   the gap:

   - **export edge, `:dropped`** — the floor predicates IIIF has no home for. A
     manifest carries no bibliographic creator or date, so an agent that is not
     a provider and `:canon/date` (and any other non-IIIF floor predicate) cannot
     be expressed and are dropped. This is IIIF's floor-coverage gap, the
     converse of the importer's report-at-ingest. So is a rights statement that
     is no URI (IIIF `rights` MUST be one), any rights beyond the first, and a
     provider with no URI id (an Agent MUST have one).
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
   `id` is the first HTTP(S) URI among `:source` (the original manifest URI) and
   the identifiers, so the trip is id-stable.
   Synthesised canvas/annotation ids are derived from that base and are ignored by
   the importer (which reads only the `Image` body ids), so the trip is idempotent.
   Kept out of the plugin `:exporter` slot to avoid a require cycle — wired by
   `palomar.convert`."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [palomar.diagnostics :as dx]
            [palomar.plugins.export-coverage :as cov]
            [palomar.plugins.qualified :as qualified]))

(def ^:private context "http://iiif.io/api/presentation/3/context.json")

(def ^:private emitted-predicates
  "The canonical floor predicates the IIIF manifest can express (title, the id,
   the digitised images, a display summary, the rights, the providers among the
   agents). Any other floor content predicate is dropped on export — IIIF has no
   home for it; the agents that are not providers are reported one by one."
  #{:canon/title :canon/identifier :canon/digital-object :canon/note :canon/rights :canon/agent})

(def ^:private written-coords
  "The coords the IIIF export writes: languages, the Canvas extent, the rights
   of the Image body and of its Canvas, a note's kind and label (an attribution
   is the `requiredStatement`; another kind is reported, `export-losses`), an
   agent's role and id (a provider's), a rights statement's scope (one with
   none is reported, as the resource's own rights written as the manifest's)."
  #{:canon/lang :canon/width :canon/height :canon/image-rights :canon/canvas-rights :canon/note-kind
    :canon/note-label :canon/note-label-lang :canon/role :canon/agent-id :canon/rights-scope})

(defn- canon-literals [record pred]
  (->> (:assertions record)
       (filter #(and (= pred (:predicate %)) (string? (:value %))))
       (map :value)))

(defn- occurrences
  "`{:value …coords}` for each `pred` literal of `record`, with the coords the
   IIIF export reads (`palomar.plugins.qualified`)."
  [record pred]
  (qualified/occurrences record pred (vec written-coords)))

(defn- lang-map
  "A IIIF 3.0 language map of `occs`, grouped by `:lang` in first-seen order; an
   occurrence with no language goes under `none`."
  [occs]
  (reduce (fn [m {value :value lang :canon/lang}] (update m (or lang "none") (fnil conj []) value))
          (array-map) occs))

(defn- placeable? [{width :canon/width height :canon/height}] (and (pos-int? width) (pos-int? height)))

(defn- manifest-base
  "Strip a trailing `/manifest(.json)` from the manifest URI to form a base for the
   synthesised canvas/annotation ids (which the importer ignores)."
  [id]
  (str/replace (str id) #"/manifest(\.json)?$" ""))

(defn- http-uri? [s] (and (string? s) (boolean (re-matches #"(?i)https?://\S+" s))))

(defn- canvas
  "A Canvas painting one image: the Canvas's `rights` (`:canon/canvas-rights`)
   on the Canvas, the Image's own (`:canon/image-rights`) on its body."
  [base i {image-id :value width :canon/width height :canon/height
           rights :canon/image-rights canvas-rights :canon/canvas-rights}]
  (cond->
   (array-map
    "id"     (str base "/canvas/" i)
    "type"   "Canvas"
    "height" height
    "width"  width)
    (http-uri? canvas-rights) (assoc "rights" canvas-rights)
    :always
    (assoc
     "items"  [(array-map
                "id"    (str base "/canvas/" i "/page")
                "type"  "AnnotationPage"
                "items" [(array-map
                          "id"         (str base "/canvas/" i "/annotation")
                          "type"       "Annotation"
                          "motivation" "painting"
                          "body"       (cond-> (array-map "id" image-id "type" "Image")
                                         (http-uri? rights) (assoc "rights" rights)))])])))

(defn- manifest-id
  "The manifest `id`: the first HTTP(S) URI among `:source` (the original
   manifest URI) and the `:canon/identifier`s — a MARC control number or an
   LCCN in `:source` is not a manifest id — else `:source`, for the message."
  [record]
  (or (first (filter http-uri? (cons (:source record) (canon-literals record :canon/identifier))))
      (:source record)))

(defn missing-required
  "The Presentation 3.0 MUSTs `record`'s manifest would miss, as a sorted vector of
   property names (`[]` when it can be emitted): an HTTP(S) `id`, a `label` with at
   least one entry, `items` with at least one Canvas (an image with a known extent)."
  [record]
  (let [id (manifest-id record)]
    (cond-> []
      (not (http-uri? id))                                             (conj "id")
      (empty? (occurrences record :canon/title))                     (conj "label")
      (not-any? placeable? (occurrences record :canon/digital-object)) (conj "items"))))

(defn- attribution
  "The note of `record` written as its `requiredStatement`: the first
   attribution note with a label (Presentation 3.0 allows one statement, and it
   MUST have a label: none is invented)."
  [record]
  (first (filter #(and (= :attribution (:canon/note-kind %)) (:canon/note-label %))
                 (occurrences record :canon/note))))

(defn- required-statement
  "The `requiredStatement` of an attribution: its label in the label's own
   language, its value in the value's."
  [{:keys [value] lang :canon/lang label :canon/note-label label-lang :canon/note-label-lang}]
  (array-map "label" {(or label-lang "none") [label]} "value" {(or lang "none") [value]}))

(defn- rights
  "The manifest `rights`: the first `:canon/rights` that is a URI."
  [record]
  (first (filter http-uri? (canon-literals record :canon/rights))))

(defn- rights-scope
  "The `:canon/rights-scope` of `record`'s rights statement `r`, if any."
  [record r]
  (some #(when (= r (:value %)) (:canon/rights-scope %)) (occurrences record :canon/rights)))

(defn- provider? [o] (and (= :provider (:canon/role o)) (http-uri? (:canon/agent-id o))))

(defn- providers
  "The `provider` Agents: each agent of role `:provider` with a URI id, as an
   Agent MUST have, its names a language map."
  [record]
  (let [ps (filter provider? (occurrences record :canon/agent))]
    (for [[id occs] (group-by :canon/agent-id ps)]
      (array-map "id" id "type" "Agent" "label" (lang-map occs)))))

(defn manifest
  "Build the IIIF Presentation 3.0 manifest map for `record` from its canonical
   floor, or `nil` when it would not be a valid manifest (`missing-required`)."
  [record]
  (when (empty? (missing-required record))
    (let [id     (manifest-id record)
          titles (occurrences record :canon/title)
          images (filter placeable? (occurrences record :canon/digital-object))
          attrib (attribution record)
          notes  (remove #(= attrib %) (occurrences record :canon/note))
          rts    (rights record)
          provs  (vec (providers record))
          base   (manifest-base id)]
      ;; built from its pairs: an array map keeps its key order only up to
      ;; eight keys when assoc'ed to
      (apply array-map
             (cond-> ["@context" context
                      "id"       id
                      "type"     "Manifest"
                      "label"    (lang-map titles)]
               (seq notes) (conj "summary" (lang-map notes))
               rts         (conj "rights" rts)
               attrib      (conj "requiredStatement" (required-statement attrib))
               (seq provs) (conj "provider" provs)
               :always     (conj "items" (vec (map-indexed (fn [i img] (canvas base (inc i) img)) images))))))))

(defn ->json
  "Render `record`'s canonical content as a IIIF Presentation 3.0 manifest JSON
   string, or `nil` if it carries nothing exportable."
  [record]
  (when-let [m (manifest record)]
    (json/write-str m :escape-slash false)))

;; --- loss accounting --------------------------------------------------------

(defn- value-loss [record field value message]
  (dx/loss {:category :dropped :subject (:id record) :edge :export :source-field field :value value
            :message message}))

(defn- rights-losses
  "One `:dropped` per rights value not written: a manifest rights that is no URI
   or beyond the first, an image's rights that is no URI (`rights` MUST be one)."
  [record]
  (let [written (rights record)]
    (concat
     (for [r (distinct (canon-literals record :canon/rights)) :when (not= r written)]
       (value-loss record :canon/rights r
                   (str "rights " (pr-str r) (if (http-uri? r) " is not the first rights URI" " is no URI")
                        ": a IIIF manifest has one rights URI")))
     (when (and written (nil? (rights-scope record written)))
       [(dx/loss {:category :under-specified :subject (:id record) :edge :export :source-field :canon/rights
                  :value written :aspect :qualifier
                  :message (str "rights " (pr-str written) " are the described resource's own; written as the rights of the IIIF manifest's content")})])
     (for [o (filter placeable? (occurrences record :canon/digital-object))
           [k what] [[:canon/image-rights "image"] [:canon/canvas-rights "Canvas"]]
           :let  [r (get o k)]
           :when (and (some? r) (not (http-uri? r)))]
       (value-loss record k r (str what " rights " (pr-str r) " is no URI: an Image or Canvas rights MUST be one"))))))

(defn- agent-losses
  "One `:dropped` per agent not written: one that is not a provider (a manifest
   names no creator or publisher), or a provider with no URI id."
  [record]
  (for [o (occurrences record :canon/agent) :when (not (provider? o))]
    (value-loss record :canon/agent (:value o)
                (if (= :provider (:canon/role o))
                  (str "provider " (pr-str (:value o)) " has no URI id: a IIIF Agent MUST have one")
                  (str "agent " (pr-str (:value o)) " (" (if-let [r (:canon/role o)] (name r) "no role")
                       ") has no IIIF home: a manifest names its providers only")))))

(defn- note-kind-losses
  "A note of a kind written to the `summary` keeps its text but not its kind."
  [record]
  (let [attrib (attribution record)]
    (for [o (occurrences record :canon/note)
          :when (and (:canon/note-kind o) (not= attrib o))]
      (dx/loss {:category :dropped :subject (:id record) :edge :export :source-field :canon/note-kind
                :value (:canon/note-kind o) :aspect :qualifier
                :message (str "a " (name (:canon/note-kind o)) " note is written in the summary; its kind is not")}))))

(defn export-losses
  "Loss for the canonical→IIIF projection (ADR 0015). A record whose manifest is
   not emitted has one `:dropped` warning naming the missing MUSTs, and every
   predicate it carries is unexpressed (`export-coverage/unexpressed`) — nothing
   else, as nothing was written. Otherwise: images with no Canvas extent, floor
   predicates IIIF cannot express (`:export :dropped`, e.g. date), each agent
   that is no provider with a URI id and each rights value not written
   (`:export :dropped`), the surplus identifiers beyond the single manifest `id`
   (`:export :dropped`), and the note collapse onto `summary`
   (`:export :under-specified`)."
  [record]
  (if-let [missing (seq (missing-required record))]
    (cons (dx/loss {:category     :dropped
                    :severity     :warning
                    :subject      (:id record)
                    :edge         :export
                    :source-field :iiif/manifest
                    :message      (str "no IIIF manifest emitted: Presentation 3.0 requires "
                                       (str/join ", " missing))})
          (cov/unexpressed record #{} "IIIF"))
    (concat
     (when-let [n (seq (remove placeable? (occurrences record :canon/digital-object)))]
       [(dx/loss {:category     :dropped
                  :subject      (:id record)
                  :edge         :export
                  :source-field :canon/digital-object
                  :message      (str (count n) " digital object(s) with no canvas extent (width/height) — "
                                     "a IIIF Canvas must have one, so they are not placed")})])
     (cov/unexpressed record (into emitted-predicates written-coords) "IIIF")
     (rights-losses record)
     (agent-losses record)
     (note-kind-losses record)
     (when (> (count (canon-literals record :canon/identifier)) 1)
       [(dx/loss {:category     :dropped
                  :subject      (:id record)
                  :edge         :export
                  :source-field :canon/identifier
                  :message      "a IIIF manifest carries a single id; surplus identifiers are dropped"})])
     ;; one per note written to the summary, naming it: an attribution written
     ;; as the requiredStatement is in no summary and is not covered (M1 review)
     (for [v (distinct (map :value (remove #(= (attribution record) %) (occurrences record :canon/note))))]
       (dx/loss {:category     :under-specified
                 :subject      (:id record)
                 :edge         :export
                 :source-field :canon/note
                 :value        v
                 :message      (str "note " (pr-str v) " emitted in the display-only summary; summary/metadata distinction not recoverable")})))))

(defn exporter
  "ADR 0007 exporter: render the canonical view of `records` as IIIF Presentation
   3.0 manifests and report the projection loss (ADR 0015).
   `(fn [opts records] -> {:output String :diagnostics [...]})`."
  [_opts records]
  {:output      (->> records (keep ->json) (str/join "\n"))
   :diagnostics (into [] (mapcat export-losses) records)})

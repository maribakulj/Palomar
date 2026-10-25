(ns palomar.plugins.iiif
  "IIIF Presentation 3.0 manifest importer spoke (WP-4, ADR 0007/0009).

   The fifth importer and the first JSON one. A IIIF manifest describes a
   *digitised object* (a book, an image) as a tree: a language-mapped `label`, and
   `items` (Canvases) that nest AnnotationPage → Annotation → `body` (the Image).
   That nesting is past the two-segment shape adapter, so — like MODS — this is a
   focused parser: it flattens the manifest's core to native `:iiif/*` and a
   declarative IIIF→canonical mapping (ADR 0009) lifts it to the floor.

   ## Mapping to the canonical floor

   | IIIF                                   | canonical               |
   |----------------------------------------|-------------------------|
   | `label`, every string, per language    | `:canon/title` + `:canon/lang` |
   | `id` (the manifest URI)                | `:canon/identifier`     |
   | each painted Image body (see below)    | `:canon/digital-object` + `:canon/width`/`:canon/height` |
   | `summary`, every string, per language  | `:canon/note` + `:canon/lang` |
   | `metadata` (label: value)              | `:canon/note`           |

   Language maps are kept, not flattened: each string becomes a fragment
   carrying its `:canon/lang` (the IIIF `none` key means no language), the same
   shape a DC `xml:lang` title takes (ADR 0011). An image is read only where the
   spec paints it — Canvas → AnnotationPage → Annotation with motivation
   `painting` → body (a `Choice`'s items included) — and keeps its Canvas's
   `width`/`height`, the extent a Canvas MUST have, so the exporter can rebuild
   a valid Canvas. Thumbnails and other Images elsewhere in the tree are not
   images of the object.

   A IIIF manifest rarely carries a bibliographic creator (the cookbook fixtures
   carry none), so a creator-less manifest projects to a Manifestation + Expression
   (its title) and the digitised images, with **no Work** — the honest floor result
   for a digital surrogate (ADR 0013). `metadata` pairs are free-form display data
   (the spec is explicit they are not machine-actionable), so they map to notes,
   not to structured agent/date.

   ## Loss (no silent drop)

   Unmodelled top-level manifest keys (`behavior`, `rights`, `provider`, `navDate`,
   `seeAlso`, `structures`, …) are reported as import-edge `:loss/dropped`
   (ADR 0015); JSON-LD machinery (`@context`, `type`) is not content and is
   ignored. Inside `items`, every unmodelled Canvas key (`label`, `thumbnail`,
   `annotations`, `duration`, …), every non-painting Annotation and every painted
   body that is not an Image (Text, Sound, Video) is reported too, one loss per
   distinct kind, so the flattening of the Canvas tree is never silent.
   `metadata` keeps its first string per label and value; a pair with several
   languages is reported as `:coerced`.

   ## Scope (V1)

   - IIIF Presentation 3.0, one Record per Manifest; id from `:record-id`, else a
     slug of the manifest URI.
   - Import only; 2.x manifests and Collections are out of scope."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [palomar.diagnostics :as dx]
            [palomar.model :as model]
            [palomar.plugins.record-id :as record-id]))

;; ---------------------------------------------------------------------------
;; JSON-LD navigation
;; ---------------------------------------------------------------------------

(defn- lang-entries
  "`[[lang string] …]` for a IIIF value, in document order. A 3.0 language map
   `{\"en\" [\"…\"]}` gives each string with its language (the `none` key, no
   language, gives nil); a plain string or array (2.x style) has no language."
  [v]
  (cond
    (string? v)     [[nil v]]
    (sequential? v) (for [s v :when (string? s)] [nil s])
    (map? v)        (for [[lang ss] v
                          s        (if (sequential? ss) ss [ss])
                          :when    (string? s)]
                      [(when (not= "none" lang) lang) s])
    :else           []))

(defn- first-lang [v] (second (first (lang-entries v))))

(defn- several-strings?
  "True when IIIF value `v` holds more than one string (several languages, or
   several strings in one language or a plain array): `first-lang` keeps one."
  [v]
  (< 1 (count (lang-entries v))))

(defn- as-seq [v] (cond (sequential? v) v (some? v) [v] :else []))

(defn- painting? [anno]
  (some #{"painting"} (as-seq (get anno "motivation"))))

(defn- bodies
  "The body resources of an Annotation, a `Choice`'s alternatives included."
  [anno]
  (mapcat #(if (= "Choice" (get % "type")) (filter map? (get % "items")) [%])
          (filter map? (as-seq (get anno "body")))))

(defn- canvases [m] (filter #(and (map? %) (= "Canvas" (get % "type"))) (get m "items")))

(defn- annotations [canvas]
  (for [page (filter map? (get canvas "items"))
        anno (filter map? (get page "items"))]
    anno))

(defn- painted-images
  "`{:id :width :height}` for every Image painted on the manifest's canvases, in
   canvas order, first occurrence of an id kept. `:width`/`:height` are the
   Canvas's extent."
  [m]
  (->> (for [canvas (canvases m)
             anno   (annotations canvas)
             :when  (painting? anno)
             body   (bodies anno)
             :when  (and (= "Image" (get body "type")) (string? (get body "id")))]
         {:id (get body "id") :width (get canvas "width") :height (get canvas "height")})
       (reduce (fn [acc img] (if (some #(= (:id img) (:id %)) acc) acc (conj acc img))) [])))

;; ---------------------------------------------------------------------------
;; Extraction to native :iiif/* assertions
;; ---------------------------------------------------------------------------

(def ^:private machinery-keys
  "JSON-LD keys that are structure, not content — never reported as loss."
  #{"@context" "type" "@type" "@id"})

(def ^:private handled-keys
  "Top-level manifest keys this V1 parser reads."
  #{"id" "label" "items" "summary" "metadata"})

(def ^:private handled-canvas-keys
  "Canvas keys this V1 parser reads (the extent and the painted content)."
  #{"id" "items" "width" "height"})

(defn- assertion [rid pred v]
  (model/assertion {:subject    rid
                    :predicate  (keyword "iiif" pred)
                    :value      v
                    :provenance (model/provenance {:pass :ingest})}))

(defn- qualified
  "One occurrence of `pred` as a fragment (ADR 0011/0012): the record-level
   reference, the value on the fragment, and one coord per `coords` entry
   (`{\"lang\" \"en\"}` → `:iiif/lang`). Returns `{:fragment :assertions}`."
  [rid pred idx v coords]
  (let [fid (model/mint-fragment-id rid [(keyword "iiif" pred) idx])]
    {:fragment   (model/fragment {:id fid :source [:json (keyword "iiif" pred) idx]})
     :assertions (into [(assertion rid pred (model/reference fid))
                        (assertion fid pred v)]
                       (for [[k cv] coords :when (some? cv)] (assertion fid k cv)))}))

(defn- manifest-content
  "`{:fragments :assertions}` — the native `:iiif/*` content of manifest `m`."
  [rid m]
  (let [langs  (fn [pred v] (map-indexed (fn [i [lang s]] (qualified rid pred i s {"lang" lang}))
                                         (lang-entries v)))
        images (map-indexed (fn [i {:keys [id width height]}]
                              (qualified rid "image" i id
                                         {"width"  (when (pos-int? width) width)
                                          "height" (when (pos-int? height) height)}))
                            (painted-images m))
        quals  (concat (langs "label" (get m "label")) (langs "summary" (get m "summary")) images)
        flat   (concat
                (when-let [id (get m "id")] [(assertion rid "id" id)])
                (for [pair (get m "metadata")
                      :let  [l (first-lang (get pair "label"))
                             v (first-lang (get pair "value"))]
                      :when (and l v)]
                  (assertion rid "metadata" (str l ": " v))))]
    {:fragments  (mapv :fragment quals)
     :assertions (vec (concat (mapcat :assertions quals) flat))}))

(defn- loss [rid field message]
  (dx/loss {:category :dropped :subject rid :edge :import :source-field field :message message}))

(defn- canvas-losses
  "Import-edge losses inside `items`: one per distinct unmodelled Canvas key, per
   distinct kind of non-painting Annotation, and per distinct type of painted
   body that is not an Image."
  [rid m]
  (let [cs (canvases m)]
    (concat
     (for [k     (distinct (mapcat keys cs))
           :when (not (or (contains? machinery-keys k) (contains? handled-canvas-keys k)))]
       (loss rid (keyword "iiif" (str "canvas-" k)) (str "Canvas " k " not modelled by the IIIF floor mapping")))
     (for [mv (distinct (for [c cs, a (annotations c) :when (not (painting? a))]
                          (str/join "," (as-seq (get a "motivation")))))]
       (loss rid :iiif/annotation (str "a non-painting annotation (motivation " (pr-str mv) ") is not carried")))
     (for [t (distinct (for [c cs, a (annotations c) :when (painting? a)
                             b (bodies a) :when (not= "Image" (get b "type"))]
                         (str (get b "type"))))]
       (loss rid :iiif/painting-body (str "a painted " t " body is not a digital image; not carried")))
     (when (some #(= "Choice" (get % "type"))
                 (for [c cs, a (annotations c) :when (painting? a), b (as-seq (get a "body"))] b))
       [(dx/loss {:category :under-specified :subject rid :edge :import :source-field :iiif/image
                  :message "a Choice's alternatives are carried as separate images; that they are alternatives is not"})])
     (when (some #(and (= "Image" (get % "type")) (not (string? (get % "id"))))
                 (for [c cs, a (annotations c) :when (painting? a), b (bodies a)] b))
       [(loss rid :iiif/image "a painted Image with no id cannot be carried")])
     (when (some (fn [c] (some #(and (some? (get c %)) (not (pos-int? (get c %)))) ["width" "height"])) cs)
       [(loss rid :iiif/canvas-extent "a Canvas width or height that is not a positive integer is not carried")]))))

(defn- ingest-losses
  "Import-edge losses (ADR 0015): one `:dropped` per unmodelled top-level content
   key, the losses inside `items` (`canvas-losses`), and one `:coerced` when a
   `metadata` label or value holds several strings, of which one is kept."
  [rid m]
  (concat
   (for [k (keys m)
         :when (and (not (contains? machinery-keys k))
                    (not (contains? handled-keys k)))]
     (loss rid (keyword "iiif" k) (str k " not modelled by the IIIF floor mapping (ADR 0003)")))
   (canvas-losses rid m)
   (when (some #(or (several-strings? (get % "label")) (several-strings? (get % "value"))) (get m "metadata"))
     [(dx/loss {:category :coerced :subject rid :edge :import :source-field :iiif/metadata
                :message "metadata kept one string per label and value; the others are not carried"})])))

;; ---------------------------------------------------------------------------
;; Record identity
;; ---------------------------------------------------------------------------

(defn- label
  "A short, readable label for a manifest URI: the last path segment, or the one
   before a trailing `manifest`/`manifest.json`."
  [uri]
  (let [segs (remove str/blank? (str/split (str uri) #"/"))
        segs (if (and (seq segs) (re-matches #"manifest(\.json)?" (last segs)))
               (butlast segs)
               segs)]
    (str/replace (record-id/slug (last segs)) #"^-+|-+$" "")))

(defn- record-id-of
  "`:iiif/m-<label>-h<digest>`: the label alone drops the host and most of the
   path, so two manifests ending in the same segment would collide; the digest
   of the full URI keeps the id injective (audit S20)."
  [opts m]
  (or (:record-id opts)
      (when-let [id (get m "id")] (record-id/from-uri "iiif" "m-" (label id) id))
      (throw (ex-info "IIIF manifest needs a :record-id or an id" {}))))

;; ---------------------------------------------------------------------------
;; Importer (ADR 0007) + mapping + plugin
;; ---------------------------------------------------------------------------

(defn- source->string [source]
  (cond
    (string? source) source
    (map? source)    (case (:source/kind source)
                       :string (:source/value source)
                       :file   (slurp (:source/value source))
                       (throw (ex-info "Unsupported source kind for IIIF"
                                       {:kind (:source/kind source)})))
    :else (throw (ex-info "IIIF importer expects a string or tagged source"
                          {:source source}))))

(defn- manifest [json-string]
  (let [m (json/read-str json-string)]
    (when-not (map? m)
      (throw (ex-info "IIIF importer expects a JSON object (a Manifest)" {})))
    m))

(defn- record-of [opts m]
  (let [rid                          (record-id-of opts m)
        {:keys [fragments assertions]} (manifest-content rid m)]
    (model/record {:id         rid
                   :kind       (or (:kind opts) :iiif/manifest)
                   :source     (get m "id")
                   :fragments  fragments
                   :assertions assertions})))

(defn ingest
  "Parse a IIIF Presentation 3.0 manifest `json-string` into a one-Record vector
   carrying native `:iiif/*` assertions. `opts` may carry `:record-id` and `:kind`."
  [json-string opts]
  [(record-of opts (manifest json-string))])

(defn importer
  "ADR 0007 importer: `(fn [opts source] -> {:records [...] :diagnostics [...]})`.
   Reports unmodelled top-level manifest keys as import loss."
  [opts source]
  (let [m (manifest (source->string source))]
    {:records     [(record-of opts m)]
     :diagnostics (vec (ingest-losses (record-id-of opts m) m))}))

(def mapping
  "Declarative IIIF→canonical mapping for the manifest core. An image has two
   coords (width, height) and a rule carries one qualifier, so it takes two rules;
   their identical value renames collapse at merge (ADR 0008)."
  [{:mapping/id :map/iiif-label :mapping/from :iiif/label :mapping/to :canon/title
    :mapping/transform [:trim] :mapping/qualifier {:from :iiif/lang :as :canon/lang}}
   {:mapping/id :map/iiif-id :mapping/from :iiif/id :mapping/to :canon/identifier
    :mapping/transform [:trim]}
   {:mapping/id :map/iiif-image :mapping/from :iiif/image :mapping/to :canon/digital-object
    :mapping/transform [:trim] :mapping/qualifier {:from :iiif/width :as :canon/width}}
   {:mapping/id :map/iiif-image-height :mapping/from :iiif/image :mapping/to :canon/digital-object
    :mapping/transform [:trim] :mapping/qualifier {:from :iiif/height :as :canon/height}}
   {:mapping/id :map/iiif-summary :mapping/from :iiif/summary :mapping/to :canon/note
    :mapping/transform [:trim] :mapping/qualifier {:from :iiif/lang :as :canon/lang}}
   {:mapping/id :map/iiif-metadata :mapping/from :iiif/metadata :mapping/to :canon/note
    :mapping/transform [:trim]}])

(def plugin
  "The IIIF Presentation 3.0 manifest importer plugin (ADR 0007)."
  {:plugin/spec-version 1
   :id                  :palomar/iiif
   :input-format        :json
   :mapping             mapping
   :importer            importer
   :doc                 "IIIF Presentation 3.0 manifest importer — label/id/images onto the canonical floor; unmodelled top-level keys reported as import loss."})

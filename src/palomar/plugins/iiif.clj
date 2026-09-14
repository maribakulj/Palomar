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
   | `label` (first language string)        | `:canon/title`          |
   | `id` (the manifest URI)                | `:canon/identifier`     |
   | each Image `body.id` under `items`     | `:canon/digital-object` |
   | `summary`, `metadata` (label: value)   | `:canon/note`           |

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
   ignored. Canvas-level detail (per-canvas labels, image dimensions, services) is
   a documented V1 limitation.

   ## Scope (V1)

   - IIIF Presentation 3.0, one Record per Manifest; id from `:record-id`, else a
     slug of the manifest URI.
   - Import only; 2.x manifests and Collections are out of scope."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [palomar.diagnostics :as dx]
            [palomar.model :as model]))

;; ---------------------------------------------------------------------------
;; JSON-LD navigation
;; ---------------------------------------------------------------------------

(defn- lang-strings
  "All strings inside a IIIF value: a plain string, an array, or a language map
   `{\"en\" [\"…\"]}` (3.0). Flattens language maps to their strings."
  [v]
  (cond
    (string? v)     [v]
    (sequential? v) (filter string? v)
    (map? v)        (mapcat lang-strings (vals v))
    :else           []))

(defn- first-lang [v] (first (lang-strings v)))

(defn- image-ids
  "Every Image resource id reachable under `node` (depth-first) — the digitised
   surrogates painted onto the manifest's canvases."
  [node]
  (cond
    (map? node)        (concat (when (and (= "Image" (get node "type"))
                                          (string? (get node "id")))
                                 [(get node "id")])
                               (mapcat image-ids (vals node)))
    (sequential? node) (mapcat image-ids node)
    :else              nil))

;; ---------------------------------------------------------------------------
;; Extraction to native :iiif/* assertions
;; ---------------------------------------------------------------------------

(def ^:private machinery-keys
  "JSON-LD keys that are structure, not content — never reported as loss."
  #{"@context" "type" "@type" "@id"})

(def ^:private handled-keys
  "Top-level manifest keys this V1 parser reads."
  #{"id" "label" "items" "summary" "metadata"})

(defn- assertion [rid pred v]
  (model/assertion {:subject    rid
                    :predicate  (keyword "iiif" pred)
                    :value      v
                    :provenance (model/provenance {:pass :ingest})}))

(defn- manifest-assertions [rid m]
  (concat
   (when-let [t (first-lang (get m "label"))] [(assertion rid "label" t)])
   (when-let [id (get m "id")] [(assertion rid "id" id)])
   (when-let [s (first-lang (get m "summary"))] [(assertion rid "summary" s)])
   (for [img (distinct (image-ids (get m "items")))]
     (assertion rid "image" img))
   (for [pair (get m "metadata")
         :let  [l (first-lang (get pair "label"))
                v (first-lang (get pair "value"))]
         :when (and l v)]
     (assertion rid "metadata" (str l ": " v)))))

(defn- ingest-losses
  "One import-edge `:loss/dropped` per unmodelled top-level content key."
  [rid m]
  (for [k (keys m)
        :when (and (not (contains? machinery-keys k))
                   (not (contains? handled-keys k)))]
    (dx/loss {:category     :dropped
              :subject      rid
              :edge         :import
              :source-field (keyword "iiif" k)
              :message      (str k " not modelled by the IIIF floor mapping (ADR 0003)")})))

;; ---------------------------------------------------------------------------
;; Record identity
;; ---------------------------------------------------------------------------

(defn- sanitize [s]
  (-> (str s) str/trim (str/replace #"[^A-Za-z0-9]+" "-") (str/replace #"^-+|-+$" "")))

(defn- slug
  "A short, stable id from a manifest URI: the last path segment, or the one
   before a trailing `manifest`/`manifest.json`."
  [uri]
  (let [segs (remove str/blank? (str/split (str uri) #"/"))
        segs (if (and (seq segs) (re-matches #"manifest(\.json)?" (last segs)))
               (butlast segs)
               segs)]
    (sanitize (last segs))))

(defn- record-id-of [opts m]
  (or (:record-id opts)
      (when-let [id (get m "id")] (keyword "iiif" (str "m-" (slug id))))
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
  (let [rid (record-id-of opts m)]
    (model/record {:id         rid
                   :kind       (or (:kind opts) :iiif/manifest)
                   :source     (get m "id")
                   :assertions (vec (manifest-assertions rid m))})))

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
  "Declarative IIIF→canonical mapping for the manifest core."
  [{:mapping/id :map/iiif-label :mapping/from :iiif/label :mapping/to :canon/title
    :mapping/transform [:trim]}
   {:mapping/id :map/iiif-id :mapping/from :iiif/id :mapping/to :canon/identifier
    :mapping/transform [:trim]}
   {:mapping/id :map/iiif-image :mapping/from :iiif/image :mapping/to :canon/digital-object
    :mapping/transform [:trim]}
   {:mapping/id :map/iiif-summary :mapping/from :iiif/summary :mapping/to :canon/note
    :mapping/transform [:trim]}
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

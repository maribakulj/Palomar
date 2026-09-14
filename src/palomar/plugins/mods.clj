(ns palomar.plugins.mods
  "MODS (Metadata Object Description Schema, LoC) importer spoke (WP-4,
   ADR 0007/0009).

   MODS is nested (`mods > titleInfo > title`, `mods > name > namePart`,
   `mods > originInfo > dateIssued`), so — unlike the flat Dublin Core spoke — the
   two-segment shape adapter cannot read it; this is a focused parser, like
   INTERMARC/MARC21, that knows the MODS tree and flattens the bibliographic core
   to native `:mods/*` assertions. A declarative MODS→canonical mapping (ADR 0009)
   then lifts that core to the floor at `:normalize`.

   ## Reads the DIRECT children of <mods> only

   A chapter/article wraps its host (book/journal) in `<relatedItem type=\"host\">`
   with its *own* titleInfo/name/originInfo. Reading only the direct children of
   `<mods>` keeps the record's own title/creator and treats the host relationship
   as unmodelled (the floor has no part-whole vocabulary — that is LRMoo R5, out of
   scope here). `nonSort` (\"The\") is prepended to the title; a `name`'s `namePart`s
   are joined in document order.

   ## Mapping to the canonical floor

   | MODS (direct child path)        | canonical            |
   |---------------------------------|----------------------|
   | `titleInfo/title` (+ nonSort)   | `:canon/title`       |
   | `titleInfo[@type=uniform]/title`| `:canon/uniform-title` |
   | `name` (joined nameParts)       | `:canon/agent`       |
   | `originInfo/publisher`          | `:canon/agent`       |
   | `originInfo/dateIssued`         | `:canon/date`        |
   | `identifier`                    | `:canon/identifier`  |
   | `abstract`, `note`              | `:canon/note`        |
   | `location/url`                  | `:canon/digital-object` |

   `titleInfo/subTitle` is captured as native `:mods/subtitle` but not mapped (it
   surfaces as loss downstream). Every rule trims.

   ## Loss (no silent drop)

   Unmodelled top-level MODS elements (subject, genre, typeOfResource,
   classification, language, physicalDescription, relatedItem, …) are reported as
   import-edge `:loss/dropped` (ADR 0015) — report-at-ingest, like Dublin Core.
   Within handled containers only the core leaves are lifted; finer sub-element
   loss (originInfo/place, name/role) is a documented V1 limitation.

   ## Scope (V1)

   - One Record per `<mods>` (a bare `<mods>` or each `<mods>` of a
     `<modsCollection>`); id from `:record-id`, else `recordInfo/recordIdentifier`,
     else the first top-level `identifier`.
   - Import only; element-level core, no DCMI/relatedItem structure."
  (:require [clojure.string :as str]
            [palomar.diagnostics :as dx]
            [palomar.model :as model]
            [palomar.xml :as rx]))

;; ---------------------------------------------------------------------------
;; XML tree navigation (MODS is nested)
;; ---------------------------------------------------------------------------

(defn- local-name [e] (when (and (map? e) (:tag e)) (name (:tag e))))
(defn- text-of [e] (let [ss (filter string? (:content e))] (when (seq ss) (apply str ss))))
(defn- child-elems [e] (filter map? (:content e)))
(defn- children-named [e nm] (filter #(= nm (local-name %)) (child-elems e)))
(defn- child-named [e nm] (first (children-named e nm)))
(defn- elements [tree] (when (map? tree) (cons tree (mapcat elements (:content tree)))))

(defn- name-string
  "A `name` element's display string: its `namePart`s, trimmed and joined in
   document order (\"Ash\" + \"Amin\" -> \"Ash Amin\"; a single \"Alterman, Eric\"
   passes through)."
  [name-elem]
  (->> (children-named name-elem "namePart")
       (keep text-of)
       (map str/trim)
       (remove str/blank?)
       (str/join " ")))

(defn- title-string
  "A `titleInfo`'s title with its `nonSort` prefix (\"The\") restored."
  [title-info title-elem]
  (let [ns (some-> (child-named title-info "nonSort") text-of str/trim)
        tt (some-> (text-of title-elem) str/trim)]
    (if (and (seq ns) tt) (str ns " " tt) tt)))

(defn- uniform-title-info?
  "True for a `<titleInfo type=\"uniform\">` — the work's controlled title, kept
   apart from the transcribed title proper (MODS distinguishes them by `@type`)."
  [title-info]
  (= "uniform" (get-in title-info [:attrs :type])))

;; ---------------------------------------------------------------------------
;; Extraction to native :mods/* assertions
;; ---------------------------------------------------------------------------

(def handled-elements
  "Top-level MODS elements this V1 parser reads; every other top-level element is
   reported as import loss rather than silently dropped."
  #{"titleInfo" "name" "originInfo" "identifier" "abstract" "note" "location" "recordInfo"})

(defn- assertion [rid pred v]
  (model/assertion {:subject    rid
                    :predicate  (keyword "mods" pred)
                    :value      v
                    :provenance (model/provenance {:pass :ingest})}))

(defn- record-assertions [rid mods]
  (concat
   ;; the transcribed title proper — every titleInfo EXCEPT the uniform one
   (for [ti (children-named mods "titleInfo")
         :when (not (uniform-title-info? ti))
         t  (children-named ti "title")
         :let [v (title-string ti t)] :when v]
     (assertion rid "title" v))
   ;; the work's controlled title — <titleInfo type="uniform"> (the WEMI-derivation key)
   (for [ti (children-named mods "titleInfo")
         :when (uniform-title-info? ti)
         t  (children-named ti "title")
         :let [v (title-string ti t)] :when v]
     (assertion rid "uniform-title" v))
   (for [ti (children-named mods "titleInfo")
         st (children-named ti "subTitle")
         :let [v (text-of st)] :when v]
     (assertion rid "subtitle" v))
   (for [n (children-named mods "name")
         :let [nm (name-string n)] :when (seq nm)]
     (assertion rid "name" nm))
   (for [oi (children-named mods "originInfo")
         p  (children-named oi "publisher")
         :let [v (text-of p)] :when v]
     (assertion rid "publisher" v))
   (for [oi (children-named mods "originInfo")
         d  (children-named oi "dateIssued")
         :let [v (text-of d)] :when v]
     (assertion rid "date-issued" v))
   (for [i (children-named mods "identifier")
         :let [v (text-of i)] :when v]
     (assertion rid "identifier" v))
   (for [a (children-named mods "abstract")
         :let [v (text-of a)] :when v]
     (assertion rid "abstract" v))
   (for [nt (children-named mods "note")
         :let [v (text-of nt)] :when v]
     (assertion rid "note" v))
   (for [loc (children-named mods "location")
         u   (children-named loc "url")
         :let [v (text-of u)] :when v]
     (assertion rid "url" v))))

(defn- ingest-losses
  "One import-edge `:loss/dropped` per distinct unmodelled top-level MODS element."
  [rid mods]
  (for [nm (distinct (map local-name (child-elems mods)))
        :when (not (contains? handled-elements nm))]
    (dx/loss {:category     :dropped
              :subject      rid
              :edge         :import
              :source-field (keyword "mods" nm)
              :message      (str nm " not modelled by the MODS floor mapping (ADR 0003)")})))

;; ---------------------------------------------------------------------------
;; Record identity
;; ---------------------------------------------------------------------------

(defn- sanitize [s]
  (-> (str s) str/trim (str/replace #"[^A-Za-z0-9]+" "-")))

(defn- record-identifier [mods]
  (some-> (child-named mods "recordInfo") (child-named "recordIdentifier") text-of))

(defn- record-id-of [opts mods]
  (or (:record-id opts)
      (when-let [ri (record-identifier mods)] (keyword "mods" (str "r" (sanitize ri))))
      (when-let [id (some-> (child-named mods "identifier") text-of)]
        (keyword "mods" (str "r" (sanitize id))))
      (throw (ex-info "MODS record needs :record-id, a recordIdentifier, or an identifier"
                      {:title (some-> (child-named mods "titleInfo") (child-named "title") text-of)}))))

;; ---------------------------------------------------------------------------
;; Importer (ADR 0007) + mapping + plugin
;; ---------------------------------------------------------------------------

(defn- source->string [source]
  (cond
    (string? source) source
    (map? source)    (case (:source/kind source)
                       :string (:source/value source)
                       :file   (slurp (:source/value source))
                       (throw (ex-info "Unsupported source kind for MODS"
                                       {:kind (:source/kind source)})))
    :else (throw (ex-info "MODS importer expects a string or tagged source"
                          {:source source}))))

(defn- mods-elements [xml-string]
  (filter #(= "mods" (local-name %)) (elements (rx/parse-str xml-string))))

(defn- record-of [opts mods]
  (let [rid (record-id-of opts mods)]
    (model/record {:id         rid
                   :kind       (or (:kind opts) :mods/record)
                   :source     (record-identifier mods)
                   :assertions (vec (record-assertions rid mods))})))

(defn ingest
  "Parse a MODS `xml-string` (a bare `<mods>` or a `<modsCollection>`) into Records
   carrying native `:mods/*` assertions. `opts` may carry `:record-id` and `:kind`."
  [xml-string opts]
  (mapv #(record-of opts %) (mods-elements xml-string)))

(defn importer
  "ADR 0007 importer: `(fn [opts source] -> {:records [...] :diagnostics [...]})`.
   Reports unmodelled top-level MODS elements as import loss. Parses once."
  [opts source]
  (let [ms (mods-elements (source->string source))]
    {:records     (mapv #(record-of opts %) ms)
     :diagnostics (vec (mapcat #(ingest-losses (record-id-of opts %) %) ms))}))

(def mapping
  "Declarative MODS→canonical mapping for the bibliographic core."
  [{:mapping/id :map/mods-title :mapping/from :mods/title :mapping/to :canon/title
    :mapping/transform [:trim]}
   {:mapping/id :map/mods-uniform :mapping/from :mods/uniform-title :mapping/to :canon/uniform-title
    :mapping/transform [:trim]}
   {:mapping/id :map/mods-name :mapping/from :mods/name :mapping/to :canon/agent
    :mapping/transform [:trim]}
   {:mapping/id :map/mods-publisher :mapping/from :mods/publisher :mapping/to :canon/agent
    :mapping/transform [:trim]}
   {:mapping/id :map/mods-date :mapping/from :mods/date-issued :mapping/to :canon/date
    :mapping/transform [:trim]}
   {:mapping/id :map/mods-identifier :mapping/from :mods/identifier :mapping/to :canon/identifier
    :mapping/transform [:trim]}
   {:mapping/id :map/mods-abstract :mapping/from :mods/abstract :mapping/to :canon/note
    :mapping/transform [:trim]}
   {:mapping/id :map/mods-note :mapping/from :mods/note :mapping/to :canon/note
    :mapping/transform [:trim]}
   {:mapping/id :map/mods-url :mapping/from :mods/url :mapping/to :canon/digital-object
    :mapping/transform [:trim]}])

(def plugin
  "The MODS (LoC) XML importer plugin (ADR 0007)."
  {:plugin/spec-version 1
   :id                  :palomar/mods
   :input-format        :xml
   :mapping             mapping
   :importer            importer
   :doc                 "MODS (LoC) importer — bibliographic core onto the canonical floor; unmodelled top-level elements reported as import loss."})

(ns palomar.plugins.dc
  "Dublin Core (DCMES 1.1) XML importer spoke (WP-4, ADR 0007/0009).

   The first *real* non-MARC spoke: a packaged plugin (like
   `palomar.plugins.intermarc`) that ingests Dublin Core XML — the
   `http://purl.org/dc/elements/1.1/` element set — onto the canonical floor,
   so it reaches the LRMoo pivot through the generic projection (ADR 0013). It
   reuses the generic shape adapter (`palomar.plugins.shape`) for the walk and a
   declarative DC→canonical mapping (ADR 0009); it adds nothing format-specific
   to the walker.

   ## Mapping to the canonical floor

   The canonical documentary vocabulary is a deliberately small set (ADR 0003);
   nine DCMES elements have a genuine home in it:

   | DC element                         | canonical        |
   |------------------------------------|------------------|
   | `dc:title`        (xml:lang)       | `:canon/title`   |
   | `dc:creator` / `contributor` / `publisher` | `:canon/agent`   |
   | `dc:date`                          | `:canon/date`    |
   | `dc:identifier`                    | `:canon/identifier` |
   | `dc:relation` / `source`           | `:canon/relation` |
   | `dc:description`  (xml:lang)       | `:canon/note`    |

   Every rule carries `:trim` — XML preserves indentation whitespace verbatim at
   ingest (`palomar.plugins.shape` §XML convention), and `:normalize` is the home
   for whitespace normalisation.

   ## No silent loss (the audit's lesson)

   The shape walker is mapping-driven: it emits assertions only for predicates the
   mapping names, so the six DCMES elements with **no canonical home** —
   `subject`, `type`, `format`, `language`, `coverage`, `rights` — would otherwise
   vanish without trace. The V1 canonical set does not model them, and ADR 0003's
   growth discipline forbids inventing predicates by anticipation; so instead of
   silently dropping them, the importer reports each present-but-unmodelled DC
   element as an import-edge `:loss/dropped` diagnostic (ADR 0015). Unmodelled is
   *documented abstention*, not hidden loss.

   ## Scope (V1)

   - One `<metadata>`/record root per document; the caller supplies `:record-id`
     and `:kind` (deriving an id from `dc:identifier` is a later refinement, as
     identifiers here are arbitrary URLs/strings).
   - Import only; a canonical→DC exporter is a later slice.
   - Element-level only; DC Terms refinements / encoding schemes are out of scope."
  (:require [palomar.diagnostics :as dx]
            [palomar.plugins.shape :as shape]
            [palomar.xml :as rx]))

;; ---------------------------------------------------------------------------
;; Namespaces
;; ---------------------------------------------------------------------------

(def dc-uri "http://purl.org/dc/elements/1.1/")
(def xml-uri "http://www.w3.org/XML/1998/namespace")

(def aliases
  "Short-prefix aliases for `palomar.plugins.shape/rewrite-tags`, so
   `clojure.data.xml`'s URI-encoded namespaces become `:dc/*` and `:xml/lang`."
  {:dc dc-uri :xml xml-uri})

;; ---------------------------------------------------------------------------
;; DC → canonical mapping (ADR 0009)
;; ---------------------------------------------------------------------------

(def mapping
  "Declarative DC→canonical mapping for the nine DCMES elements with a canonical
   home. `dc:title` and `dc:description` are qualified by `xml:lang` (→
   `:canon/lang` on a fragment, ADR 0011). Every rule trims (XML whitespace)."
  [{:mapping/id :map/dc-title :mapping/from :dc/title :mapping/to :canon/title
    :mapping/transform [:trim] :mapping/qualifier {:from :xml/lang :as :canon/lang}}
   {:mapping/id :map/dc-creator :mapping/from :dc/creator :mapping/to :canon/agent
    :mapping/transform [:trim]}
   {:mapping/id :map/dc-contributor :mapping/from :dc/contributor :mapping/to :canon/agent
    :mapping/transform [:trim]}
   {:mapping/id :map/dc-publisher :mapping/from :dc/publisher :mapping/to :canon/agent
    :mapping/transform [:trim]}
   {:mapping/id :map/dc-date :mapping/from :dc/date :mapping/to :canon/date
    :mapping/transform [:trim]}
   {:mapping/id :map/dc-identifier :mapping/from :dc/identifier :mapping/to :canon/identifier
    :mapping/transform [:trim]}
   {:mapping/id :map/dc-relation :mapping/from :dc/relation :mapping/to :canon/relation
    :mapping/transform [:trim]}
   {:mapping/id :map/dc-source :mapping/from :dc/source :mapping/to :canon/relation
    :mapping/transform [:trim]}
   {:mapping/id :map/dc-description :mapping/from :dc/description :mapping/to :canon/note
    :mapping/transform [:trim] :mapping/qualifier {:from :xml/lang :as :canon/lang}}])

(def dc-elements
  "The 15 DCMES 1.1 element names (as `:dc/*` keywords). Used to scope the
   unmodelled-element loss report to Dublin Core, ignoring wrapper/`xsi` noise."
  #{:dc/title :dc/creator :dc/subject :dc/description :dc/publisher
    :dc/contributor :dc/date :dc/type :dc/format :dc/identifier
    :dc/source :dc/language :dc/relation :dc/coverage :dc/rights})

(def ^:private mapped-elements
  (into #{} (map :mapping/from) mapping))

(def unmodelled-elements
  "DCMES elements with no home in the V1 canonical vocabulary (ADR 0003): they
   are reported as import-edge `:loss/dropped`, never silently skipped."
  (into #{} (remove mapped-elements) dc-elements))

;; ---------------------------------------------------------------------------
;; Importer (ADR 0007)
;; ---------------------------------------------------------------------------

(defn- source->string
  "Accept either a raw string or an ADR 0007 tagged source map."
  [source]
  (cond
    (string? source) source
    (map? source)    (case (:source/kind source)
                       :string (:source/value source)
                       :file   (slurp (:source/value source))
                       (throw (ex-info "Unsupported source kind for Dublin Core"
                                       {:kind (:source/kind source)})))
    :else (throw (ex-info "Dublin Core importer expects a string or tagged source"
                          {:source source}))))

(defn- present-elements
  "Distinct `:dc/*` child tags actually present in the rewritten record root."
  [root]
  (->> (:content root) (filter map?) (map :tag) distinct))

(defn- ingest-losses
  "One import-edge `:loss/dropped` per DC element present in the source but with
   no canonical home — so unmodelled DC content is documented, not silently
   dropped by the mapping-driven shape walker (ADR 0015)."
  [record-id present]
  (for [tag present
        :when (contains? unmodelled-elements tag)]
    (dx/loss {:category     :dropped
              :subject      record-id
              :edge         :import
              :source-field tag
              :message      (str (name tag)
                                 " has no canonical home in the V1 vocabulary (ADR 0003)")})))

(defn importer
  "ADR 0007 importer: `(fn [opts source] -> {:records [...] :diagnostics [...]})`.
   `opts` must carry `:record-id`; `:kind` defaults to `:document`. Parses the DC
   XML once, walks it via the shape adapter onto native `:dc/*` (renamed to
   `:canon/*` at `:normalize`), and reports unmodelled DC elements as import loss."
  [{:keys [record-id kind source]} source-in]
  (when-not (and (keyword? record-id) (namespace record-id))
    (throw (ex-info (str "Dublin Core requires a namespaced-keyword :record-id in opts "
                         "(a single-record spoke has no id of its own; the CLI derives one "
                         "from the filename).")
                    {:spoke :dc :record-id record-id})))
  (let [s      (source->string source-in)
        root   (-> (rx/parse-str s) (shape/rewrite-tags aliases))
        record (shape/ingest-xml root mapping
                                 {:record-id record-id
                                  :kind      (or kind :document)
                                  :source    (or source record-id)})]
    {:records     [record]
     :diagnostics (vec (ingest-losses record-id (present-elements root)))}))

(def plugin
  "The Dublin Core (DCMES 1.1) XML importer plugin (ADR 0007). Exposes `:mapping`
   as data (compiled to `:normalize` rules) and the loss-aware `:importer`."
  {:plugin/spec-version 1
   :id                  :palomar/dublin-core
   :input-format        :xml
   :mapping             mapping
   :importer            importer
   :doc                 "Dublin Core (DCMES 1.1) XML importer — DC elements onto the canonical floor; unmodelled elements reported as import loss."})

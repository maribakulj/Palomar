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
   ten DCMES elements have a genuine home in it:

   | DC element                         | canonical        |
   |------------------------------------|------------------|
   | `dc:title`        (xml:lang)       | `:canon/title`   |
   | `dc:creator` / `contributor` / `publisher` | `:canon/agent`   |
   | `dc:date`                          | `:canon/date`    |
   | `dc:identifier`                    | `:canon/identifier` |
   | `dc:relation` / `source`           | `:canon/relation` |
   | `dc:description`  (xml:lang)       | `:canon/note`    |
   | `dc:rights`       (xml:lang)       | `:canon/rights`  |

   Every rule carries `:trim` — XML preserves indentation whitespace verbatim at
   ingest (`palomar.plugins.shape` §XML convention), and `:normalize` is the home
   for whitespace normalisation.

   ## No silent loss (the audit's lesson)

   The shape walker is mapping-driven: it emits assertions only for predicates the
   mapping names, so the five DCMES elements with **no canonical home** —
   `subject`, `type`, `format`, `language`, `coverage` — would otherwise
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
   - DCMI Terms (`dcterms:`) are read too (`dcterms-mapping`, ADR 0037); their
     encoding schemes (`xsi:type=\"dcterms:W3CDTF\"`) are not.
   - An OAI-PMH `oai_dc` record is read through `palomar.plugins.oai`."
  (:require [clojure.string :as str]
            [palomar.diagnostics :as dx]
            [palomar.model :as model]
            [palomar.plugins.shape :as shape]
            [palomar.xml :as rx]))

;; ---------------------------------------------------------------------------
;; Namespaces
;; ---------------------------------------------------------------------------

(def dc-uri "http://purl.org/dc/elements/1.1/")
(def dcterms-uri "http://purl.org/dc/terms/")
(def xml-uri "http://www.w3.org/XML/1998/namespace")
(def rdf-uri "http://www.w3.org/1999/02/22-rdf-syntax-ns#")

(def aliases
  "Short-prefix aliases for `palomar.plugins.shape/rewrite-tags`, so
   `clojure.data.xml`'s URI-encoded namespaces become `:dc/*` and `:xml/lang`."
  {:dc dc-uri :dcterms dcterms-uri :xml xml-uri :rdf rdf-uri})

;; ---------------------------------------------------------------------------
;; DC → canonical mapping (ADR 0009)
;; ---------------------------------------------------------------------------

(def mapping
  "Declarative DC→canonical mapping for the ten DCMES elements with a canonical
   home. `dc:title`, `dc:description` and `dc:rights` are qualified by `xml:lang` (→
   `:canon/lang` on a fragment, ADR 0011). `dc:creator`, `dc:contributor` and
   `dc:publisher` all map to `:canon/agent` with a constant `:canon/role`
   (`:creator`, `:contributor`, `:publisher`), so the role survives the floor.
   Every rule trims (XML whitespace)."
  [{:mapping/id :map/dc-title :mapping/from :dc/title :mapping/to :canon/title
    :mapping/transform [:trim] :mapping/qualifier {:from :xml/lang :as :canon/lang}}
   {:mapping/id :map/dc-creator :mapping/from :dc/creator :mapping/to :canon/agent
    :mapping/transform [:trim]
    :mapping/qualifier {:value :creator :as :canon/role}}
   {:mapping/id :map/dc-contributor :mapping/from :dc/contributor :mapping/to :canon/agent
    :mapping/transform [:trim]
    :mapping/qualifier {:value :contributor :as :canon/role}}
   {:mapping/id :map/dc-publisher :mapping/from :dc/publisher :mapping/to :canon/agent
    :mapping/transform [:trim]
    :mapping/qualifier {:value :publisher :as :canon/role}}
   {:mapping/id :map/dc-date :mapping/from :dc/date :mapping/to :canon/date
    :mapping/transform [:trim]}
   {:mapping/id :map/dc-identifier :mapping/from :dc/identifier :mapping/to :canon/identifier
    :mapping/transform [:trim]}
   {:mapping/id :map/dc-relation :mapping/from :dc/relation :mapping/to :canon/relation
    :mapping/transform [:trim]}
   {:mapping/id :map/dc-source :mapping/from :dc/source :mapping/to :canon/relation
    :mapping/transform [:trim]}
   {:mapping/id :map/dc-description :mapping/from :dc/description :mapping/to :canon/note
    :mapping/transform [:trim] :mapping/qualifier {:from :xml/lang :as :canon/lang}}
   {:mapping/id :map/dc-rights :mapping/from :dc/rights :mapping/to :canon/rights
    :mapping/transform [:trim] :mapping/qualifier {:from :xml/lang :as :canon/lang}}])

(def dcterms-mapping
  "DCMI Terms (ADR 0037): the `dcterms:` properties that restate a DCMES element
   map as it does; of their refinements, those the floor has a home for —
   `created`/`issued` (a date), `isPartOf`/`hasPart`/`isVersionOf`/`hasVersion`
   (a relation), `abstract` and `tableOfContents` (a note, of that kind),
   `rights` and `license` (a rights statement, its text or its
   `rdf:resource` URI). Any other `dcterms:` property
   present is reported as import loss."
  (into
   [{:mapping/id :map/dcterms-title :mapping/from :dcterms/title :mapping/to :canon/title
     :mapping/transform [:trim] :mapping/qualifier {:from :xml/lang :as :canon/lang}}
    {:mapping/id :map/dcterms-description :mapping/from :dcterms/description :mapping/to :canon/note
     :mapping/transform [:trim] :mapping/qualifier {:from :xml/lang :as :canon/lang}}
    {:mapping/id :map/dcterms-abstract :mapping/from :dcterms/abstract :mapping/to :canon/note
     :mapping/transform [:trim] :mapping/qualifier {:value :abstract :as :canon/note-kind}}
    {:mapping/id :map/dcterms-toc :mapping/from :dcterms/tableOfContents :mapping/to :canon/note
     :mapping/transform [:trim] :mapping/qualifier {:value :contents :as :canon/note-kind}}
    {:mapping/id :map/dcterms-rights :mapping/from :dcterms/rights :mapping/to :canon/rights
     :mapping/transform [:trim] :mapping/qualifier {:from :xml/lang :as :canon/lang}}
    {:mapping/id :map/dcterms-license :mapping/from :dcterms/license :mapping/to :canon/rights
     :mapping/transform [:trim] :mapping/qualifier {:from :xml/lang :as :canon/lang}}]
   (concat
    (for [[el role] [["creator" :creator] ["contributor" :contributor] ["publisher" :publisher]]]
      {:mapping/id (keyword "map" (str "dcterms-" el)) :mapping/from (keyword "dcterms" el) :mapping/to :canon/agent
       :mapping/transform [:trim] :mapping/qualifier {:value role :as :canon/role}})
    (for [[el to] [["date" :canon/date] ["created" :canon/date] ["issued" :canon/date]
                   ["identifier" :canon/identifier]
                   ["relation" :canon/relation] ["source" :canon/relation] ["isPartOf" :canon/relation]
                   ["hasPart" :canon/relation] ["isVersionOf" :canon/relation] ["hasVersion" :canon/relation]]]
      {:mapping/id (keyword "map" (str "dcterms-" el)) :mapping/from (keyword "dcterms" el) :mapping/to to
       :mapping/transform [:trim]}))))

(def dc-elements
  "The 15 DCMES 1.1 element names (as `:dc/*` keywords). Used to scope the
   unmodelled-element loss report to Dublin Core, ignoring wrapper/`xsi` noise."
  #{:dc/title :dc/creator :dc/subject :dc/description :dc/publisher
    :dc/contributor :dc/date :dc/type :dc/format :dc/identifier
    :dc/source :dc/language :dc/relation :dc/coverage :dc/rights})

(def ^:private mapped-elements
  (into #{} (map :mapping/from) (concat mapping dcterms-mapping)))

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
        :when (or (contains? unmodelled-elements tag)
                  (and (keyword? tag) (= "dcterms" (namespace tag)) (not (contains? mapped-elements tag))))]
    (dx/loss {:category     :dropped
              :subject      record-id
              :edge         :import
              :source-field tag
              :message      (str (name tag)
                                 " has no canonical home in the V1 vocabulary (ADR 0003)")})))

(def ^:private rights-elements
  "The elements whose value is a rights statement, given as text or as an
   `rdf:resource` URI (`<dcterms:license rdf:resource=\"…\"/>`)."
  [:dc/rights :dcterms/rights :dcterms/license])

(defn- rights-values
  "The record `record` read from `root` by the shape adapter, its rights
   elements corrected: a blank text is no value, and an `rdf:resource` URI is
   one (beside the text when both are given), with the XPath of the attribute.
   Returns `{:record :losses}`, a loss per rights element stating nothing."
  [record root]
  (let [rid (:id record)
        occ (for [t rights-elements
                  [i e] (map-indexed vector (filter #(= t (:tag %)) (filter map? (:content root))))]
              {:tag t :idx i :elem e :fid (model/mint-fragment-id rid [t i])})
        fids (set (map :fid occ))
        blank? (fn [a] (and (fids (:subject a)) (some #{(:predicate a)} rights-elements)
                            (string? (:value a)) (str/blank? (:value a))))
        uris (for [{:keys [tag idx elem fid]} occ
                   :let [u (some-> (get-in elem [:attrs :rdf/resource]) str/trim not-empty)]
                   :when u]
               (model/assertion {:subject fid :predicate tag :value u
                                 :provenance (model/provenance
                                              {:pass :ingest
                                               :evidence [{:selector {:type :xpath
                                                                      :value (str "*[local-name()='" (name tag) "'][" (inc idx)
                                                                                  "]/@*[local-name()='resource']")}
                                                           :fragment fid}]})}))
        empty-elems (filter (fn [{:keys [elem]}]
                              (and (str/blank? (apply str (filter string? (:content elem))))
                                   (str/blank? (get-in elem [:attrs :rdf/resource]))))
                            occ)]
    {:record (update record :assertions #(into (vec (remove blank? %)) uris))
     :losses (for [t (distinct (map :tag empty-elems))]
               (dx/loss {:category :dropped :subject rid :edge :import :source-field t
                         :message (str (name t) " with neither text nor rdf:resource states no rights; not carried")}))}))

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
        {:keys [record losses]}
        (rights-values (shape/ingest-xml root (into mapping dcterms-mapping)
                                         {:record-id record-id
                                          :kind      (or kind :document)
                                          :source    (or source record-id)})
                       root)]
    {:records     [record]
     :diagnostics (vec (concat (ingest-losses record-id (present-elements root)) losses))}))

(def plugin
  "The Dublin Core (DCMES 1.1) XML importer plugin (ADR 0007). Exposes `:mapping`
   as data (compiled to `:normalize` rules) and the loss-aware `:importer`."
  {:plugin/spec-version 1
   :id                  :palomar/dublin-core
   :input-format        :xml
   :mapping             (into mapping dcterms-mapping)
   :importer            importer
   :doc                 "Dublin Core (DCMES 1.1) XML importer — DC elements onto the canonical floor; unmodelled elements reported as import loss."})

(ns palomar.model
  "Canonical internal representation (IR).

   Defines schemas and constructors for Record, Assertion, Value, Provenance,
   Diagnostic, Repair and Fragment. No logic lives here — only data shapes,
   constructors, and shape-level predicates.

   The model knows only the structural vocabulary (`:meta/*`). Documentary
   predicates (title, agent, date, ...) live in `palomar.plugins.canonical`
   or in format-specific plugins.

   See ADR 0001 (assertion-based IR), ADR 0003 (vocabulary layering) and
   ADR 0005 (status model)."
  (:require [clojure.string :as str]
            [malli.core :as m]))

;; ---------------------------------------------------------------------------
;; Structural vocabulary
;;
;; The closed set of predicates the core itself recognizes. Anything else
;; comes from plugins. See ADR 0003.
;; ---------------------------------------------------------------------------

(def meta-id         :meta/id)
(def meta-kind       :meta/kind)
(def meta-source     :meta/source)
(def meta-fragment   :meta/fragment)
(def meta-diagnostic :meta/diagnostic)
(def meta-provenance :meta/provenance)

(def structural-vocabulary
  #{meta-id meta-kind meta-source meta-fragment meta-diagnostic meta-provenance})

(defn structural?
  "True if `predicate` belongs to the core's structural vocabulary."
  [predicate]
  (contains? structural-vocabulary predicate))

;; ---------------------------------------------------------------------------
;; Statuses (ADR 0005)
;;
;; Two disjoint families: machine truth and human workflow.
;; ---------------------------------------------------------------------------

(def machine-statuses  #{:asserted :proposed :retracted :superseded})
(def workflow-statuses #{:accepted :rejected :needs-review})
(def statuses          (into machine-statuses workflow-statuses))

(defn machine-status? [s] (contains? machine-statuses s))
(defn workflow-status? [s] (contains? workflow-statuses s))

;; ---------------------------------------------------------------------------
;; Scalar schemas
;; ---------------------------------------------------------------------------

(def Severity
  [:enum :error :warning :info])

(def Phase
  "The five phases of the pipeline: ingest → normalize → validate → infer →
   repair (ADR 0004, ADR 0020). `:infer` and `:repair` emit proposals
   (ADR 0005); the `:repair` phase additionally carries repair operations
   that `apply-repairs` surfaces for human curation — never auto-applied in
   V1. Projection to target formats is done by exporters after the pipeline,
   not as a phase."
  [:enum :ingest :normalize :validate :infer :repair])

(def Status
  (into [:enum] statuses))

(def Confidence
  [:and :double [:>= 0.0] [:<= 1.0]])

(def Id
  "A subject or record identifier. Opaque to the core."
  [:or :keyword :string :uuid])

(def Predicate
  "An assertion predicate. Always a keyword; the core does not interpret
   namespaces."
  :keyword)

;; ---------------------------------------------------------------------------
;; Provenance
;; ---------------------------------------------------------------------------

(def Provenance
  "Where an assertion came from. All fields optional; a bare `{}` is valid
   (e.g. for hand-constructed test data)."
  [:map
   [:source {:optional true} :any]
   [:pass {:optional true} Phase]
   [:rule {:optional true} :keyword]
   [:derivation {:optional true} [:vector Id]]
   [:timestamp {:optional true} inst?]])

;; ---------------------------------------------------------------------------
;; Fragment (pointer into raw source)
;; ---------------------------------------------------------------------------

(def Fragment
  [:map
   [:id Id]
   [:source :any]
   [:locator {:optional true} :any]
   [:raw {:optional true} :string]])

;; ---------------------------------------------------------------------------
;; Entity (synthesized subject — ADR 0014 / 0016 / 0017)
;;
;; A minted entity is a subject the pipeline *synthesizes* (a Work, an
;; Expression…) that exists in no single source — unlike a Fragment, which
;; points into raw source. Entities are first-class subjects (see
;; `known-subjects`); their identity is content-based (`mint-entity-id`), so the
;; same entity minted independently from two records collapses by id at merge
;; (ADR 0008). The `:kind` is an opaque keyword supplied by a plugin (e.g.
;; `:lrmoo/work`); the core never interprets it.
;; ---------------------------------------------------------------------------

(def Entity
  [:map
   [:id Id]
   [:kind :keyword]
   ;; Optional *external* authority IRI for this entity (e.g. its data.bnf.fr
   ;; ARK), distinct from `:id` which is the *internal* content-derived key used
   ;; for clustering/dedup (`mint-entity-id`). Set only when a faithful IRI is
   ;; known from the source (transcription) or reconciliation; RDF export prefers
   ;; it over a synthetic `urn:palomar:` IRI. See ADR 0013 (interoperable output).
   [:iri {:optional true} :string]
   [:provenance {:optional true} Provenance]])

;; ---------------------------------------------------------------------------
;; Fragment identity (ADR 0012)
;;
;; Fragments minted by importers need stable, distinct, reproducible ids.
;; The canonical scheme encodes the owning record id and a locator path
;; into a `:frag`-namespaced keyword. The single sanctioned constructor
;; is `mint-fragment-id`; plugins do not roll their own.
;; ---------------------------------------------------------------------------

(defn- encode-locator-segment
  "Encode one locator segment as a string. Predicate segments are
   namespaced keywords (`:ns/name` → `\"ns-name\"`); index segments are
   non-negative integers (decimal string)."
  [seg]
  (cond
    (and (keyword? seg) (namespace seg))
    (let [seg-ns (namespace seg)
          seg-nm (name seg)]
      (when (str/includes? seg-ns "-")
        (throw (ex-info "Locator predicate namespace must not contain '-' — it is the ns/name separator in fragment ids, so a hyphen there breaks injectivity (ADR 0012)"
                        {:segment seg})))
      (when (or (str/includes? seg-ns ".") (str/includes? seg-nm "."))
        (throw (ex-info "Locator predicate must not contain '.' — it is the path separator in fragment ids (ADR 0012)"
                        {:segment seg})))
      (str seg-ns "-" seg-nm))

    (and (int? seg) (not (neg? seg)))
    (str seg)

    (keyword? seg)
    (throw (ex-info "Locator predicate segments must be namespaced keywords (ADR 0001)"
                    {:segment seg}))

    :else
    (throw (ex-info "Locator segments must be namespaced keywords or non-negative integers"
                    {:segment seg}))))

(defn- validate-locator!
  "Reject locators that do not match the ADR 0012 schema: a non-empty
   sequence alternating namespaced predicate keywords and non-negative
   integer occurrence indices, with even total length."
  [locator]
  (when-not (sequential? locator)
    (throw (ex-info "Locator must be a sequential collection" {:locator locator})))
  (when (empty? locator)
    (throw (ex-info "Locator must be non-empty" {:locator locator})))
  (when (odd? (count locator))
    (throw (ex-info "Locator must alternate predicate keywords and integer indices (even length)"
                    {:locator locator})))
  (doseq [[i seg] (map-indexed vector locator)]
    (if (even? i)
      (when-not (and (keyword? seg) (namespace seg))
        (throw (ex-info "Locator predicate position must be a namespaced keyword"
                        {:locator locator :position i :segment seg})))
      (when-not (and (int? seg) (not (neg? seg)))
        (throw (ex-info "Locator index position must be a non-negative integer"
                        {:locator locator :position i :segment seg})))))
  locator)

(defn mint-fragment-id
  "Construct a fragment id from a record id and a locator path. Returns
   a keyword in the `:frag` namespace whose name encodes the record id
   followed by the locator segments — see ADR 0012 for the scheme.

   The record-id must be a namespaced keyword; the locator must be a
   non-empty vector alternating namespaced predicate keywords with
   non-negative integer occurrence indices.

   Examples:

     (mint-fragment-id :record/r42 [:dc/title 0])
     ;; => :frag/record.r42.dc-title.0

     (mint-fragment-id :record/r42 [:crm/P108 0 :crm/P14 0])
     ;; => :frag/record.r42.crm-P108.0.crm-P14.0

   The record-id and every predicate must be free of '.', and predicate
   namespaces must be free of '-' (these are the fragment-id path and
   ns/name separators; a stray one would break injectivity or round-trip).
   Predicate *names* may contain hyphens. Throws ex-info if the record-id
   is not a namespaced keyword, if it or a predicate violates the separator
   rule, or if the locator does not match the schema."
  [record-id locator]
  (when-not (and (keyword? record-id) (namespace record-id))
    (throw (ex-info "mint-fragment-id requires a namespaced keyword record-id"
                    {:record-id record-id})))
  (when (or (str/includes? (namespace record-id) ".")
            (str/includes? (name record-id) "."))
    (throw (ex-info "Record-id must not contain '.' — it is the path separator in fragment ids (ADR 0012)"
                    {:record-id record-id})))
  (validate-locator! locator)
  (let [record-prefix (str (namespace record-id) "." (name record-id))
        segments      (mapv encode-locator-segment locator)]
    (keyword "frag" (str/join "." (cons record-prefix segments)))))

(defn mint-entity-id
  "Construct a content-based identifier for a synthesized entity. Returns a
   keyword in the `:ent` namespace whose name is the entity `kind` followed by a
   stable 64-bit hash of the canonical key — see ADR 0016 / 0017.

   Unlike `mint-fragment-id` (which encodes a source locator), an entity id is a
   pure function of *content*: `kind` plus an opaque, plugin-supplied canonical
   `key` string. Two records that imply the same entity therefore mint the same
   id independently — no shared state — and collapse by id at merge (ADR 0008).
   The core does not interpret `kind` or `key`; it only hashes them.

   Example:

     (mint-entity-id :lrmoo/work \"flaubert|madame bovary\")
     ;; => :ent/work.<16 hex chars>

   `kind` must be a keyword; `key` must be a non-empty string."
  [kind key]
  (when-not (keyword? kind)
    (throw (ex-info "mint-entity-id requires a keyword kind" {:kind kind})))
  (when-not (and (string? key) (seq key))
    (throw (ex-info "mint-entity-id requires a non-empty string key" {:key key})))
  (let [canonical (pr-str [kind key])
        digest    (.digest (java.security.MessageDigest/getInstance "SHA-256")
                           (.getBytes canonical "UTF-8"))
        hex       (apply str (map #(format "%02x" (bit-and (int %) 0xff))
                                  (take 8 digest)))]
    (keyword "ent" (str (name kind) "." hex))))

;; ---------------------------------------------------------------------------
;; Value
;;
;; Values are either bare primitives (the 90% case — strings, numbers,
;; keywords, etc.) or tagged maps for references, structured values and
;; uncertain values. Tagged maps carry a `:value/kind` discriminator.
;;
;; The schema is recursive: structured and uncertain values contain other
;; values, so we use Malli's local registry + :ref mechanism.
;; ---------------------------------------------------------------------------

(defn finite-double?
  "True if `x` is a double that is neither NaN nor an infinity. Used by the
   Primitive schema to forbid non-finite doubles at validate time. Public
   so tests and importers can guard inputs before constructing assertions."
  [x]
  (and (double? x)
       (not (Double/isNaN x))
       (not (Double/isInfinite x))))

(def Primitive
  "Bare primitive values. Doubles are constrained to finite values: NaN
   and the infinities are rejected at validate time *and* at generation
   time. A NaN-valued assertion would be a silent foot-gun (NaN ≠ NaN
   breaks dedup, equality, and EDN round-trip), so it is forbidden by
   the schema rather than left as an importer concern."
  (let [finite-double [:and
                       [:double {:gen/fmap (fn [d]
                                             (if (or (Double/isNaN d)
                                                     (Double/isInfinite d))
                                               0.0
                                               d))}]
                       [:fn {:error/message "must be finite (not NaN, not Infinity)"}
                        finite-double?]]]
    [:or :string :int finite-double :boolean :keyword :uuid inst?]))

(def Value
  [:schema
   {:registry
    {::value [:or
              Primitive
              [:ref ::reference]
              [:ref ::structured]
              [:ref ::uncertain]]

     ::reference [:map
                  [:value/kind [:= :reference]]
                  [:value/target Id]
                  [:value/role {:optional true} :keyword]]

     ::structured [:map
                   [:value/kind [:= :structured]]
                   [:value/fields [:map-of :keyword [:ref ::value]]]]

     ::uncertain [:map
                  [:value/kind [:= :uncertain]]
                  [:value/alternatives [:vector [:ref ::value]]]
                  [:value/basis {:optional true} :any]]}}
   [:ref ::value]])

(defn reference-value? [x]
  (and (map? x) (= :reference (:value/kind x))))

(defn structured-value? [x]
  (and (map? x) (= :structured (:value/kind x))))

(defn uncertain-value? [x]
  (and (map? x) (= :uncertain (:value/kind x))))

(defn primitive-value? [x]
  (or (string? x) (int? x) (double? x) (boolean? x)
      (keyword? x) (uuid? x) (inst? x)))

;; ---------------------------------------------------------------------------
;; Assertion
;; ---------------------------------------------------------------------------

(def Assertion
  [:map
   [:subject Id]
   [:predicate Predicate]
   [:value Value]
   [:provenance {:optional true} Provenance]
   [:confidence {:optional true} Confidence]
   [:status {:optional true} Status]])

;; ---------------------------------------------------------------------------
;; Repair
;; ---------------------------------------------------------------------------

(def Repair
  [:map
   [:description :string]
   [:operation :keyword]
   [:basis {:optional true} :any]
   [:applicable? {:optional true} :boolean]
   [:safe? {:optional true} :boolean]])

;; ---------------------------------------------------------------------------
;; Diagnostic
;; ---------------------------------------------------------------------------

(def Diagnostic
  [:map
   [:severity Severity]
   [:code :keyword]
   [:subject Id]
   [:message {:optional true} :string]
   ;; Generic structured payload for diagnostics that need more than a
   ;; message — e.g. loss diagnostics carry their edge and source field here
   ;; (ADR 0015). Opaque to the core; keys are namespaced by the producer.
   [:detail {:optional true} [:map-of :keyword :any]]
   [:repairs {:optional true} [:vector Repair]]
   [:provenance {:optional true} Provenance]])

;; ---------------------------------------------------------------------------
;; Record (top-level IR envelope)
;; ---------------------------------------------------------------------------

(def Record
  [:map
   [:id Id]
   [:kind :keyword]
   [:source {:optional true} :any]
   [:fragments {:optional true} [:vector Fragment]]
   [:entities {:optional true} [:vector Entity]]
   [:assertions {:optional true} [:vector Assertion]]
   [:diagnostics {:optional true} [:vector Diagnostic]]
   [:provenance {:optional true} Provenance]])

;; ---------------------------------------------------------------------------
;; Validators
;; ---------------------------------------------------------------------------

(defn valid-record?     [x] (m/validate Record x))
(defn valid-assertion?  [x] (m/validate Assertion x))
(defn valid-diagnostic? [x] (m/validate Diagnostic x))
(defn valid-value?      [x] (m/validate Value x))
(defn valid-repair?     [x] (m/validate Repair x))
(defn valid-fragment?   [x] (m/validate Fragment x))
(defn valid-entity?     [x] (m/validate Entity x))
(defn valid-provenance? [x] (m/validate Provenance x))

(defn explain-record     [x] (m/explain Record x))
(defn explain-assertion  [x] (m/explain Assertion x))
(defn explain-diagnostic [x] (m/explain Diagnostic x))
(defn explain-value      [x] (m/explain Value x))

;; ---------------------------------------------------------------------------
;; Constructors
;;
;; Constructors return plain maps with sensible defaults applied. They do NOT
;; validate — callers that want validation call `valid-*?` or `explain-*`.
;; This keeps constructors cheap on the hot path.
;; ---------------------------------------------------------------------------

(defn reference
  "Construct a reference value pointing to `target` (an Id), optionally
   qualified by a `role` keyword."
  ([target] {:value/kind :reference :value/target target})
  ([target role] {:value/kind :reference :value/target target :value/role role}))

(defn structured
  "Construct a structured value from a map of keyword → Value."
  [fields]
  {:value/kind :structured :value/fields fields})

(defn uncertain
  "Construct an uncertain value from a vector of alternatives, optionally
   annotated with `basis`."
  ([alternatives] {:value/kind :uncertain :value/alternatives (vec alternatives)})
  ([alternatives basis] {:value/kind :uncertain
                         :value/alternatives (vec alternatives)
                         :value/basis basis}))

(defn provenance
  "Construct a Provenance map. All fields optional; missing ones are omitted."
  [{:keys [source pass rule derivation timestamp]}]
  (cond-> {}
    source     (assoc :source source)
    pass       (assoc :pass pass)
    rule       (assoc :rule rule)
    derivation (assoc :derivation (vec derivation))
    timestamp  (assoc :timestamp timestamp)))

(defn fragment
  "Construct a Fragment. `id` and `source` are required."
  [{:keys [id source locator raw]}]
  (cond-> {:id id :source source}
    locator (assoc :locator locator)
    raw     (assoc :raw raw)))

(defn entity
  "Construct a synthesized Entity. Required: :id, :kind. :iri and :provenance
   optional. `id` is normally produced by `mint-entity-id`; `:kind` is a plugin
   keyword (e.g. :lrmoo/work); `:iri` is the external authority IRI when known.
   See ADR 0017."
  [{:keys [id kind iri provenance]}]
  (cond-> {:id id :kind kind}
    iri        (assoc :iri iri)
    provenance (assoc :provenance provenance)))

(defn assertion
  "Construct an Assertion. Required: :subject, :predicate, :value.
   Defaults: :confidence 1.0, :status :asserted. :provenance is optional."
  [{:keys [subject predicate value provenance confidence status]
    :or   {confidence 1.0 status :asserted}}]
  (cond-> {:subject subject
           :predicate predicate
           :value value
           :confidence confidence
           :status status}
    provenance (assoc :provenance provenance)))

(defn repair
  "Construct a Repair proposal. Required: :description, :operation."
  [{:keys [description operation basis applicable? safe?]}]
  (cond-> {:description description :operation operation}
    (some? basis)       (assoc :basis basis)
    (some? applicable?) (assoc :applicable? applicable?)
    (some? safe?)       (assoc :safe? safe?)))

(defn diagnostic
  "Construct a Diagnostic. Required: :severity, :code, :subject.
   :message, :detail, :repairs and :provenance are optional."
  [{:keys [severity code subject message detail repairs provenance]}]
  (cond-> {:severity severity :code code :subject subject}
    message    (assoc :message message)
    detail     (assoc :detail detail)
    repairs    (assoc :repairs (vec repairs))
    provenance (assoc :provenance provenance)))

(defn record
  "Construct a Record. Required: :id, :kind. Collections default to empty."
  [{:keys [id kind source fragments entities assertions diagnostics provenance]}]
  (cond-> {:id id :kind kind}
    (some? source) (assoc :source source)
    fragments      (assoc :fragments (vec fragments))
    entities       (assoc :entities (vec entities))
    assertions     (assoc :assertions (vec assertions))
    diagnostics    (assoc :diagnostics (vec diagnostics))
    provenance     (assoc :provenance provenance)))

;; ---------------------------------------------------------------------------
;; Status predicates
;; ---------------------------------------------------------------------------

(defn- status= [expected]
  (fn [x] (= expected (:status x))))

(def asserted?     (status= :asserted))
(def proposed?     (status= :proposed))
(def retracted?    (status= :retracted))
(def superseded?   (status= :superseded))
(def accepted?     (status= :accepted))
(def rejected?     (status= :rejected))
(def needs-review? (status= :needs-review))

(defn in-force?
  "Assertions the pipeline treats as currently true: :asserted or :accepted."
  [x]
  (let [s (:status x)]
    (or (= :asserted s) (= :accepted s))))

(defn pending?
  "Assertions that represent unresolved proposals awaiting human action
   or further machine processing."
  [x]
  (let [s (:status x)]
    (or (= :proposed s) (= :needs-review s))))

;; ---------------------------------------------------------------------------
;; Simple queries
;; ---------------------------------------------------------------------------

(defn assertions-for
  "Return the subset of a record's assertions whose predicate equals `p`."
  [record p]
  (filterv #(= p (:predicate %)) (:assertions record)))

(defn has-assertion?
  "True if the record contains at least one assertion with predicate `p`."
  [record p]
  (boolean (some #(= p (:predicate %)) (:assertions record))))

;; ---------------------------------------------------------------------------
;; Cross-field consistency
;;
;; Shape validity (Malli) and consistency are different things. A record can
;; be shape-valid but logically incoherent — most commonly when assertion
;; subjects refer to ids that don't exist on the record. The matcher will
;; silently fail to bind those, which is one of the most frustrating bugs
;; for plugin authors. `record-consistent?` makes that contract explicit.
;; ---------------------------------------------------------------------------

(defn known-subjects
  "Set of identifiers a record's assertions may legitimately address: the
   record itself, every fragment id, and every synthesized entity id it carries
   (ADR 0017)."
  [record]
  (into #{(:id record)}
        (concat (map :id (:fragments record))
                (map :id (:entities record)))))

(defn record-consistent?
  "True if every assertion's `:subject` is either the record's `:id` or
   one of its fragment ids. Diagnostics' `:subject` is checked the same
   way. This is the contract every importer must guarantee — silently
   inconsistent records fail to match any rule and produce no
   diagnostic, which is the worst possible failure mode."
  [record]
  (let [allowed (known-subjects record)]
    (and (every? #(contains? allowed (:subject %)) (:assertions record))
         (every? #(contains? allowed (:subject %)) (:diagnostics record)))))

(defn explain-consistency
  "Return a map describing every assertion or diagnostic whose subject is
   not in `known-subjects`, or nil if the record is consistent. Intended
   for importer test suites and CLI diagnostics — *not* for hot-path use."
  [record]
  (let [allowed   (known-subjects record)
        bad-asrt  (filterv #(not (contains? allowed (:subject %)))
                           (:assertions record))
        bad-diag  (filterv #(not (contains? allowed (:subject %)))
                           (:diagnostics record))]
    (when (or (seq bad-asrt) (seq bad-diag))
      {:record-id     (:id record)
       :known-subjects allowed
       :bad-assertions  bad-asrt
       :bad-diagnostics bad-diag})))

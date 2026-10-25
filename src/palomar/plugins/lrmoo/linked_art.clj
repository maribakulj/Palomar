(ns palomar.plugins.lrmoo.linked-art
  "Linked Art (https://linked.art) JSON-LD export of the WEMI view (museum-sector
   output, the Louvre target). Linked Art is a profile of CIDOC-CRM as structured
   JSON-LD; unlike `lrmoo.export`'s triple-based JSON-LD this builds the nested
   resource tree Linked Art consumers expect.

   The WEMI→Linked Art mapping follows the official Linked Art model for textual
   documents (https://linked.art/model/document/, see `docs/eval/linked-art.md`):

     F3 Manifestation (+ its F2 Expression) ->  LinguisticObject  --part_of-->  PropositionalObject
     F1 Work                                 ->  PropositionalObject
     F5 Item                                 ->  HumanMadeObject   --carries-->  LinguisticObject

   A Manifestation is an edition — an abstract publication — not a physical
   object: in Linked Art the text is a LinguisticObject, which the physical
   copies (HumanMadeObjects) `carry`. Only an F5 Item is a HumanMadeObject
   (audit S01; before 2026-10-25 the Manifestation was wrongly rooted as one).
   Linked Art deliberately has no edition level between the text and the
   abstract work, so each Expression folds into its Manifestation's
   LinguisticObject, reported as `:under-specified`. One document is emitted per
   Manifestation, each following its own R4/R3 links, and one per Item (its R7).

   Field patterns (each from a Getty AAT-classified example):
   - title (R33)            -> `identified_by` Name, classified_as aat:300404670 (Primary Name)
   - ARK / `:canon/identifier` -> `identified_by` Identifier, aat:300435704 (System-Assigned Number)
   - creator (`:canon/agent`, role `:creator` or unstated) -> `created_by` Creation,
     carried_out_by Person.
   - publisher (`:canon/agent`, role `:publisher`) -> `used_for` Activity classified
     aat:300054686 (Publishing), carried_out_by Group.
   - a contributor is neither, and is reported as export loss.
   - note (`:canon/note`)      -> `referred_to_by` LinguisticObject (Description / Brief Text)
   - digital (`:canon/digital-object`) -> `representation` VisualItem digitally_shown_by DigitalObject

   Honest scope (V1): a Linked Art-*profile* serialisation, **not** conformance-
   validated against a Linked Art processor. INTERMARC's creator lives in
   `:intermarc/f100_a` (not lifted to `:canon/agent`), so `created_by` is emitted
   only when a `:canon/agent` is present (the floor spokes). Dates, relations and
   native predicates are not expressed and are reported as export loss (ADR 0015).
   Like the sibling exporters this is kept out of the plugin `:exporter` slot to
   avoid a require cycle."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [palomar.diagnostics :as dx]
            [palomar.plugins.agents :as agents]
            [palomar.plugins.lrmoo.export :as export]
            [palomar.plugins.lrmoo.view :as view]))

(def context "https://linked.art/ns/v1/linked-art.json")

;; --- Getty AAT terms (verified against the Linked Art model examples) --------
(def ^:private aat-primary-name  "http://vocab.getty.edu/aat/300404670")
(def ^:private aat-system-number "http://vocab.getty.edu/aat/300435704")
(def ^:private aat-description   "http://vocab.getty.edu/aat/300435416")
(def ^:private aat-brief-text    "http://vocab.getty.edu/aat/300418049")
(def ^:private aat-digital-image "http://vocab.getty.edu/aat/300215302")
(def ^:private aat-publishing    "http://vocab.getty.edu/aat/300054686")

(defn- type-obj [aat label] {"id" aat "type" "Type" "_label" label})
(defn- name-obj [content] {"type" "Name"
                           "classified_as" [(type-obj aat-primary-name "Primary Name")]
                           "content" content})
(defn- identifier-obj [content] {"type" "Identifier"
                                 "classified_as" [(type-obj aat-system-number "System-Assigned Number")]
                                 "content" content})
(defn- note-obj [content] {"type" "LinguisticObject"
                           "classified_as" [(assoc (type-obj aat-description "Description")
                                                   "classified_as" [(type-obj aat-brief-text "Brief Text")])]
                           "content" content})
(defn- person-obj
  "A Linked Art Person. `iri` (an authority id, e.g. an ISNI URI) is attached as
   `id` when present — an *identified* creator (D7-certified), not a bare label."
  [label iri]
  (cond-> {"type" "Person" "_label" label}
    iri (assoc "id" iri)))

(defn- agent-iri
  "The authority IRI of the record's identified agent entity (`:crm/E21_Person`,
   V1: the INTERMARC ISNI Person), or nil."
  [record]
  (some #(when (and (= :crm/E21_Person (:kind %)) (:iri %)) (:iri %)) (:entities record)))

(defn- persons
  "Linked Art Person objects for the record's `names`. When there is exactly one
   agent and an identified agent entity, that one Person carries the authority id."
  [record names]
  (let [iri (agent-iri record)]
    (if (and iri (= 1 (count names)))
      [(person-obj (first names) iri)]
      (mapv #(person-obj % nil) names))))
(defn- publishing
  "A Publishing `Activity` carried out by the publishers (Groups) — the Linked Art
   pattern for an edition's publication (model/document, Creation and Publication)."
  [names]
  {"type"           "Activity"
   "classified_as"  [(type-obj aat-publishing "Publishing")]
   "carried_out_by" (mapv (fn [n] {"type" "Group" "_label" n}) names)})

(defn- digital-obj [url] {"type" "VisualItem"
                          "digitally_shown_by" [{"type" "DigitalObject"
                                                 "classified_as" [(type-obj aat-digital-image "Digital Image")]
                                                 "access_point" [{"id" url "type" "DigitalObject"}]}]})

;; --- reads off the WEMI record ----------------------------------------------

(def ^:private expressed
  "Predicates the Linked Art profile expresses (everything else is export loss)."
  #{:lrmoo/R33_has_string :lrmoo/R3_is_realised_in :lrmoo/R4_embodies :lrmoo/R7_exemplifies
    :canon/agent :canon/role :canon/note :canon/digital-object :canon/identifier})

(defn- title-of [record subject]
  (->> (:assertions record)
       (filter #(and (= subject (:subject %))
                     (= :lrmoo/R33_has_string (:predicate %))
                     (string? (:value %))))
       first :value))

(defn- canon-vals [record pred]
  (->> (:assertions record)
       (filter #(and (= pred (:predicate %)) (string? (:value %))))
       (map :value) distinct vec))

(defn- text-iri [m] (or (:iri m) (export/entity-iri (:id m))))

(defn- texts
  "One `{:manif :expr :work}` per Manifestation of `record`, each following its own
   R4 (to its Expression) and that Expression's R3 (to its Work) — never the
   record's first Expression or Work."
  [record]
  (for [m (view/manifestations record)
        :let [e (first (view/expression-of record (:id m)))
              w (when e (first (view/work-of record e)))]]
    {:manif m :expr e :work w}))

(defn- text-doc
  "The Linked Art `LinguisticObject` for one Manifestation: the text as published
   (Linked Art has no edition level, so the Expression folds into it). With
   `record-fields?`, the record-level canonical fields (creators, publishers,
   notes, images, identifiers) attach to it."
  [record {:keys [manif expr work]} record-fields?]
  (let [expr-title (when expr (title-of record expr))
        work-title (when work (title-of record work))
        title      (or (title-of record (:id manif)) expr-title work-title)
        ;; the Expression folds into this text, so its own title, when it differs,
        ;; is a further Name of it rather than a silent loss
        other-name (when (and expr-title (not= expr-title title)) (name-obj expr-title))
        creators   (when record-fields? (agents/creators record))
        publishers (when record-fields? (agents/with-role record :publisher))
        notes      (when record-fields? (canon-vals record :canon/note))
        digis      (when record-fields? (canon-vals record :canon/digital-object))
        idents     (cond-> []
                     (:iri manif)   (conj (identifier-obj (:iri manif)))
                     record-fields? (into (map identifier-obj (canon-vals record :canon/identifier))))]
    (cond-> {"@context" context
             "id"       (text-iri manif)
             "type"     "LinguisticObject"
             "_label"   (or title (text-iri manif))}
      (or title (seq idents)) (assoc "identified_by" (-> (if title [(name-obj title)] [])
                                                         (cond-> other-name (conj (dissoc other-name "classified_as")))
                                                         (into idents)))
      (seq creators)          (assoc "created_by" {"type" "Creation"
                                                   "carried_out_by" (persons record creators)})
      (seq publishers)        (assoc "used_for" [(publishing publishers)])
      work                    (assoc "part_of" [(cond-> {"id" (export/entity-iri work)
                                                         "type" "PropositionalObject"}
                                                  (or work-title title) (assoc "_label" (or work-title title)))])
      (seq notes)             (assoc "referred_to_by" (mapv note-obj notes))
      (seq digis)             (assoc "representation" (mapv digital-obj digis)))))

(defn- item-doc
  "The Linked Art `HumanMadeObject` for one F5 Item: a physical copy that
   `carries` the text of the Manifestation it exemplifies (R7)."
  [record item]
  (let [m     (some->> (first (view/manifestation-of record (:id item))) (view/entity-by-id record))
        title (or (title-of record (:id item)) (when m (title-of record (:id m))))
        iri   (or (:iri item) (export/entity-iri (:id item)))]
    (cond-> {"@context" context
             "id"       iri
             "type"     "HumanMadeObject"
             "_label"   (or title iri)}
      title (assoc "identified_by" [(name-obj title)])
      m     (assoc "carries" [(cond-> {"id" (text-iri m) "type" "LinguisticObject"}
                                title (assoc "_label" title))]))))

(defn documents
  "The Linked Art JSON-LD documents for `record`'s WEMI view: one
   `LinguisticObject` per Manifestation (a Manifestation is an edition, not a
   physical object), then one `HumanMadeObject` per F5 Item. The record-level
   canonical fields attach to the text when the record has exactly one
   Manifestation; with several they cannot be attributed and are reported as loss
   (`export-losses`). Empty for a record with neither."
  [record]
  (let [ts (texts record)]
    (into (mapv #(text-doc record % (= 1 (count ts))) ts)
          (map #(item-doc record %) (view/items record)))))

(defn ->jsonld
  "Render `record`'s WEMI view as Linked Art JSON-LD, one document per line
   (`documents`), or \"\" when there is none."
  [record]
  (str/join "\n" (map #(json/write-str % :escape-slash false) (documents record))))

(defn- non-creator-losses
  "One `:dropped` loss per agent the profile expresses neither as a creator
   (`created_by`) nor as a publisher (`used_for` Publishing): a contributor
   (Linked Art would need a distinct part of the Creation for it)."
  [record]
  (let [expressed (into (set (agents/creators record)) (agents/with-role record :publisher))]
    (for [{nm :name role :role} (distinct (agents/occurrences record))
          :when (not (contains? expressed nm))]
      (dx/loss {:category     :dropped
                :subject      (:id record)
                :edge         :export
                :source-field :canon/agent
                :message      (str "agent " (pr-str nm) " (" (if role (name role) "no role")
                                   ") is neither creator nor publisher; not expressed in the Linked Art profile (V1)")}))))

(defn- structure-losses
  "Losses of the WEMI → Linked Art structure: each Expression folded into its
   Manifestation's LinguisticObject (`:under-specified` — Linked Art has no
   edition level), each WEMI entity no document reaches (`:dropped`), and, when
   the record has several Manifestations, its record-level canonical fields,
   which cannot be attributed to one of them (`:under-specified`)."
  [record]
  (let [ts      (texts record)
        reached (into (set (mapcat (fn [{:keys [manif expr work]}] [(:id manif) expr work]) ts))
                      (map :id (view/items record)))
        loss    (fn [cat subject field msg]
                  (dx/loss {:category cat :subject subject :edge :export :source-field field :message msg}))]
    (concat
     (for [{:keys [manif expr]} ts :when expr]
       (loss :under-specified (:id manif) :lrmoo/F2_Expression
             "Expression and Manifestation are one LinguisticObject in Linked Art (no edition level)"))
     (for [e (:entities record)
           :when (and (#{:lrmoo/F1_Work :lrmoo/F2_Expression :lrmoo/F3_Manifestation :lrmoo/F5_Item} (:kind e))
                      (not (contains? reached (:id e))))]
       (loss :dropped (:id e) (:kind e)
             (str (name (:kind e)) " is reached by no Manifestation or Item; not exported")))
     (when (< 1 (count ts))
       (for [p [:canon/agent :canon/note :canon/digital-object :canon/identifier]
             :when (seq (canon-vals record p))]
         (loss :under-specified (:id record) p
               (str (name p) " is record-level but the record has " (count ts)
                    " Manifestations; not attributed to any of them")))))))

(defn export-losses
  "Export-edge loss (ADR 0015): every predicate the Linked Art profile does not
   express (dates, relations, native source predicates), one per distinct
   [subject predicate]; one per agent left out (`non-creator-losses`); and the
   structural losses (`structure-losses`)."
  [record]
  (-> (->> (:assertions record)
           (remove #(contains? expressed (:predicate %)))
           (remove #(= :canon/loss-marker (:predicate %)))
           (map (juxt :subject :predicate))
           distinct
           (mapv (fn [[s p]]
                   (dx/loss {:category     :dropped
                             :subject      s
                             :edge         :export
                             :source-field p
                             :message      (str (pr-str p) " not expressed in the Linked Art profile (V1)")}))))
      (into (non-creator-losses record))
      (into (structure-losses record))))

(defn exporter
  "ADR 0007 exporter: render the Linked Art view of `records` (JSON-LD documents,
   newline-separated, see `documents`) and report what the profile cannot express.
   `(fn [opts records] -> {:output String :diagnostics [...]})`."
  [_opts records]
  {:output      (->> records (keep #(let [s (->jsonld %)] (when (seq s) s))) (str/join "\n"))
   :diagnostics (into [] (mapcat export-losses) records)})

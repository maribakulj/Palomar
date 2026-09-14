(ns palomar.plugins.lrmoo.linked-art
  "Linked Art (https://linked.art) JSON-LD export of the WEMI view (museum-sector
   output, the Louvre target). Linked Art is a profile of CIDOC-CRM as structured
   JSON-LD; unlike `lrmoo.export`'s triple-based JSON-LD this builds the nested
   resource tree Linked Art consumers expect.

   The WEMI→Linked Art mapping is grounded in the official Linked Art model
   examples (verified, not guessed — see `docs/eval/linked-art.md`), and it lines
   up with the CRM down-projection (`lrmoo.crm`: F1→E89):

     F3 Manifestation  ->  HumanMadeObject   --carries-->  LinguisticObject
     F2 Expression     ->  LinguisticObject  --part_of-->  PropositionalObject
     F1 Work           ->  PropositionalObject

   The three LRM levels stay three distinct Linked Art resources (no collapse —
   Linked Art is *more* precise here than plain CRM, which makes F2/F3 both E73).

   Field patterns (each from a Getty AAT-classified example):
   - title (R33)            -> `identified_by` Name, classified_as aat:300404670 (Primary Name)
   - ARK / `:canon/identifier` -> `identified_by` Identifier, aat:300435704 (System-Assigned Number)
   - creator (`:canon/agent`)  -> on the Expression: `created_by` Creation, carried_out_by Person
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
            [palomar.plugins.lrmoo.export :as export]
            [palomar.plugins.lrmoo.view :as view]))

(def context "https://linked.art/ns/v1/linked-art.json")

;; --- Getty AAT terms (verified against the Linked Art model examples) --------
(def ^:private aat-primary-name  "http://vocab.getty.edu/aat/300404670")
(def ^:private aat-system-number "http://vocab.getty.edu/aat/300435704")
(def ^:private aat-description   "http://vocab.getty.edu/aat/300435416")
(def ^:private aat-brief-text    "http://vocab.getty.edu/aat/300418049")
(def ^:private aat-digital-image "http://vocab.getty.edu/aat/300215302")

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
  "Linked Art Person objects for the record's `agents`. When there is exactly one
   agent and an identified agent entity, that one Person carries the authority id."
  [record agents]
  (let [iri (agent-iri record)]
    (if (and iri (= 1 (count agents)))
      [(person-obj (first agents) iri)]
      (mapv #(person-obj % nil) agents))))
(defn- digital-obj [url] {"type" "VisualItem"
                          "digitally_shown_by" [{"type" "DigitalObject"
                                                 "classified_as" [(type-obj aat-digital-image "Digital Image")]
                                                 "access_point" [{"id" url "type" "DigitalObject"}]}]})

;; --- reads off the WEMI record ----------------------------------------------

(def ^:private expressed
  "Predicates the Linked Art profile expresses (everything else is export loss)."
  #{:lrmoo/R33_has_string :lrmoo/R3_is_realised_in :lrmoo/R4_embodies
    :canon/agent :canon/note :canon/digital-object :canon/identifier})

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

(defn linked-art
  "The Linked Art JSON-LD resource tree for `record`'s WEMI view, rooted at the
   Manifestation (HumanMadeObject). Returns nil for a record with no Manifestation."
  [record]
  (when-let [manif (first (view/manifestations record))]
    (let [expr   (first (view/expressions record))
          work   (first (view/works record))
          title  (or (title-of record (:id manif))
                     (and expr (title-of record (:id expr)))
                     (and work (title-of record (:id work))))
          agents (canon-vals record :canon/agent)
          notes  (canon-vals record :canon/note)
          digis  (canon-vals record :canon/digital-object)
          ids    (canon-vals record :canon/identifier)
          names  (when title [(name-obj title)])
          idents (cond-> []
                   (:iri manif) (conj (identifier-obj (:iri manif)))
                   :always      (into (map identifier-obj ids)))
          expr-node (when expr
                      (cond-> {"id" (export/entity-iri (:id expr)) "type" "LinguisticObject"}
                        title        (assoc "_label" title "identified_by" [(name-obj title)])
                        (seq agents) (assoc "created_by" {"type" "Creation"
                                                          "carried_out_by" (persons record agents)})
                        work         (assoc "part_of" [(cond-> {"id" (export/entity-iri (:id work))
                                                                "type" "PropositionalObject"}
                                                         title (assoc "_label" title))])))]
      (cond-> {"@context" context
               "id"       (or (:iri manif) (export/entity-iri (:id manif)))
               "type"     "HumanMadeObject"}
        title           (assoc "_label" title)
        (seq names)     (assoc "identified_by" (into (vec names) idents))
        (and (empty? names) (seq idents)) (assoc "identified_by" idents)
        expr-node       (assoc "carries" [expr-node])
        (seq notes)     (assoc "referred_to_by" (mapv note-obj notes))
        (seq digis)     (assoc "representation" (mapv digital-obj digis))))))

(defn ->jsonld
  "Render `record`'s WEMI view as a Linked Art JSON-LD document (\"\" when empty)."
  [record]
  (if-let [r (linked-art record)] (json/write-str r :escape-slash false) ""))

(defn export-losses
  "Export-edge loss (ADR 0015): every predicate the Linked Art profile does not
   express (dates, relations, native source predicates), one per distinct
   [subject predicate]."
  [record]
  (->> (:assertions record)
       (remove #(contains? expressed (:predicate %)))
       (remove #(= :canon/loss-marker (:predicate %)))
       (map (juxt :subject :predicate))
       distinct
       (mapv (fn [[s p]]
               (dx/loss {:category     :dropped
                         :subject      s
                         :edge         :export
                         :source-field p
                         :message      (str (pr-str p) " not expressed in the Linked Art profile (V1)")})))))

(defn exporter
  "ADR 0007 exporter: render the Linked Art view of `records` (one JSON-LD document
   per record, newline-separated) and report what the profile cannot express.
   `(fn [opts records] -> {:output String :diagnostics [...]})`."
  [_opts records]
  {:output      (->> records (keep #(let [s (->jsonld %)] (when (seq s) s))) (str/join "\n"))
   :diagnostics (into [] (mapcat export-losses) records)})

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
   - a publication event (ADR 0036) -> a Publishing `used_for` Activity with its
     actors, `took_place_at` Place and `timespan` (named by the date as
     transcribed); with events, they replace the publishers' bare activity.
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
            [palomar.model :as model]
            [palomar.plugins.agents :as agents]
            [palomar.plugins.export-coverage :as cov]
            [palomar.plugins.lido :as lido]
            [palomar.plugins.lrmoo.export :as export]
            [palomar.plugins.lrmoo.ntriples :as nt]
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
(def ^:private aat-languages
  "The AAT terms of the languages museum records are written in, by their
   ISO 639-1 code (post-audit 4, F7)."
  {"en" ["http://vocab.getty.edu/aat/300388277" "English"]
   "de" ["http://vocab.getty.edu/aat/300388344" "German"]
   "fr" ["http://vocab.getty.edu/aat/300388306" "French"]
   "nl" ["http://vocab.getty.edu/aat/300388256" "Dutch"]
   "it" ["http://vocab.getty.edu/aat/300388474" "Italian"]
   "es" ["http://vocab.getty.edu/aat/300389311" "Spanish"]})

(defn- language-obj
  "The Linked Art `Language` of an `xml:lang` tag, by its primary subtag;
   nil when it has no AAT term here."
  [tag]
  (when-let [[aat label] (aat-languages (str/lower-case (first (str/split (str tag) #"[-_]"))))]
    {"id" aat "type" "Language" "_label" label}))

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

(defn- persons
  "Linked Art Person objects for the record's `names`, each carrying the
   authority id of the identified agent that names it (`agents/identified`):
   two identified creators keep two ids (post-audit 2, D1)."
  [record names]
  (let [iris (into {} (keep (fn [{:keys [name iri]}] (when name [name iri]))) (agents/identified record))]
    (mapv #(person-obj % (iris %)) names)))
(defn- publishing
  "A Publishing `Activity` carried out by the publishers (Groups) — the Linked Art
   pattern for an edition's publication (model/document, Creation and Publication)."
  [names]
  {"type"           "Activity"
   "classified_as"  [(type-obj aat-publishing "Publishing")]
   "carried_out_by" (mapv (fn [n] {"type" "Group" "_label" n}) names)})

(defn- event-activity
  "A publication event (ADR 0036) as a Linked Art Publishing `Activity`: who
   carried it out, where, and its time-span named by the date as transcribed
   (the source gives no ISO date to bound it)."
  [{:keys [agents places dates]}]
  (cond-> {"type"          "Activity"
           "classified_as" [(type-obj aat-publishing "Publishing")]}
    (seq agents) (assoc "carried_out_by" (mapv (fn [[id n]] {"id" (export/entity-iri id) "type" "Group" "_label" n}) agents))
    (seq places) (assoc "took_place_at" (mapv (fn [[id n]] {"id" (export/entity-iri id) "type" "Place" "_label" n}) places))
    (seq dates)  (assoc "timespan" {"type" "TimeSpan"
                                    "identified_by" (mapv (fn [d] {"type" "Name" "content" d}) dates)})))

(defn- digital-obj [url] {"type" "VisualItem"
                          "digitally_shown_by" [{"type" "DigitalObject"
                                                 "classified_as" [(type-obj aat-digital-image "Digital Image")]
                                                 "access_point" [{"id" url "type" "DigitalObject"}]}]})

;; --- reads off the WEMI record ----------------------------------------------

(def ^:private expressed
  "Predicates the Linked Art profile expresses (everything else is export loss)."
  #{:lrmoo/R33_has_string :lrmoo/R3_is_realised_in :lrmoo/R4_embodies :lrmoo/R7_exemplifies
    :canon/agent :canon/role :canon/note :canon/digital-object :canon/identifier})

(defn- titles-of
  "The distinct R33 strings of `subject`, in assertion order."
  [record subject]
  (->> (:assertions record)
       (filter #(and (= subject (:subject %))
                     (= :lrmoo/R33_has_string (:predicate %))
                     (string? (:value %))))
       (map :value)
       distinct
       vec))

(defn- title-of [record subject] (first (titles-of record subject)))

(defn- canon-vals [record pred]
  (->> (:assertions record)
       (filter #(and (= pred (:predicate %)) (string? (:value %))))
       (map :value) distinct vec))

(def ^:private event-predicates
  "The predicates of a publication event (ADR 0036), which `event-activity` writes."
  #{:lrmoo/R24_created :crm/P4_has_time-span :crm/P82_at_some_time_within
    :crm/P7_took_place_at :crm/P14_carried_out_by})

(defn- objects [record s p]
  (keep #(when (and (= s (:subject %)) (= p (:predicate %))) (:value %)) (:assertions record)))

(defn- targets [record s p] (keep #(when (model/reference-value? %) (:value/target %)) (objects record s p)))

(defn- events-of
  "The publication events whose R24 created Manifestation `manif-id`, each as
   `{:id :agents :places :dates :nodes}`: the actors and places as `[id name]`
   (a minted node has an IRI, so Linked Art can reference it), and `:nodes`,
   the time-span, place and actor nodes it reaches."
  [record manif-id]
  (for [a (:assertions record)
        :when (and (= :lrmoo/R24_created (:predicate a)) (= manif-id (get-in a [:value :value/target])))
        :let [ev  (:subject a)
              ts  (targets record ev :crm/P4_has_time-span)
              pl  (targets record ev :crm/P7_took_place_at)
              ac  (targets record ev :crm/P14_carried_out_by)
              nm  (fn [ids] (vec (for [id (distinct ids)
                                       :let [n (first (filter string? (objects record id :crm/P190_has_symbolic_content)))]
                                       :when n]
                                   [id n])))]]
    {:id     ev
     :agents (nm ac)
     :places (nm pl)
     :dates  (vec (distinct (mapcat #(filter string? (objects record % :crm/P82_at_some_time_within)) ts)))
     :nodes  (set (concat [ev] ts pl ac))}))

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
  (let [work-title (when work (title-of record work))
        ;; every string of the Manifestation, then of the Expression that folds
        ;; into it, is a Name of this text; the first is its primary name
        names      (distinct (concat (titles-of record (:id manif)) (when expr (titles-of record expr))))
        title      (or (first names) work-title)
        others     (map #(dissoc (name-obj %) "classified_as") (rest names))
        creators   (when record-fields? (agents/creators record))
        publishers (when record-fields? (agents/with-role record :publisher))
        events     (events-of record (:id manif))
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
                                                         (into others)
                                                         (into idents)))
      (seq creators)          (assoc "created_by" {"type" "Creation"
                                                   "carried_out_by" (persons record creators)})
      ;; the publication events say who published, where and when; without
      ;; them the record's publishers make one Publishing activity
      (seq events)            (assoc "used_for" (mapv event-activity events))
      (and (empty? events) (seq publishers)) (assoc "used_for" [(publishing publishers)])
      work                    (assoc "part_of" [(cond-> {"id" (text-iri (or (view/entity-by-id record work) {:id work}))
                                                         "type" "PropositionalObject"}
                                                  work-title (assoc "_label" work-title))])
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

(def ^:private iiif-presentation "http://iiif.io/api/presentation/3/context.json")

(defn- manifest? [record] (= :iiif/manifest (:kind record)))

(defn- manifest-doc
  "The Linked Art `DigitalObject` for a IIIF manifest (post-audit 3, E2): the
   manifest is a digital presentation, which conforms to the IIIF Presentation
   API and is retrieved at its own URL. What it presents — a book, a painting,
   a sculpture — the manifest does not say, so no `LinguisticObject` or
   `HumanMadeObject` is asserted; its labels are the presentation's names, its
   summary a statement about it, its images its representations. Its
   metadata's agents are the presented thing's, not the manifest's, and are
   reported as loss (`export-losses`)."
  [record]
  (let [ms     (view/manifestations record)
        names  (distinct (mapcat #(titles-of record (:id %)) ms))
        url    (let [s (:source record)] (when (and (string? s) (re-find #"^https?://" s)) s))
        notes  (canon-vals record :canon/note)
        digis  (canon-vals record :canon/digital-object)
        idents (canon-vals record :canon/identifier)]
    (cond-> {"@context"    context
             "id"          (or url (some-> (first ms) text-iri) (export/entity-iri (:id record)))
             "type"        "DigitalObject"
             "_label"      (or (first names) url "IIIF manifest")
             "conforms_to" [{"id" iiif-presentation "type" "InformationObject" "_label" "IIIF Presentation API 3"}]}
      (or (seq names) (seq idents)) (assoc "identified_by" (-> (if (seq names) [(name-obj (first names))] [])
                                                               (into (map #(dissoc (name-obj %) "classified_as") (rest names)))
                                                               (into (map identifier-obj idents))))
      url             (assoc "access_point" [{"id" url "type" "DigitalObject"}])
      (seq notes)     (assoc "referred_to_by" (mapv note-obj notes))
      (seq digis)     (assoc "representation" (mapv digital-obj digis)))))

;; --- a museum object (LIDO, ADR 0052) -----------------------------------------

(def ^:private aat-accession   "http://vocab.getty.edu/aat/300312355")
(def ^:private aat-materials   "http://vocab.getty.edu/aat/300435429")
(def ^:private aat-dimensions  "http://vocab.getty.edu/aat/300435430")
(def ^:private aat-provenance  "http://vocab.getty.edu/aat/300444174")
(def ^:private aat-inscription "http://vocab.getty.edu/aat/300028702")
(def ^:private aat-credit      "http://vocab.getty.edu/aat/300026687")

(def ^:private dimension-types
  "A LIDO measurement type → its AAT dimension type."
  {"height" ["http://vocab.getty.edu/aat/300055644" "Height"]
   "width"  ["http://vocab.getty.edu/aat/300055647" "Width"]
   "depth"  ["http://vocab.getty.edu/aat/300072633" "Depth"]
   "weight" ["http://vocab.getty.edu/aat/300056240" "Weight"]
   "diameter" ["http://vocab.getty.edu/aat/300055624" "Diameter"]})

(def ^:private units
  "A LIDO measurement unit → its AAT unit."
  {"cm" ["http://vocab.getty.edu/aat/300379098" "centimeters"]
   "mm" ["http://vocab.getty.edu/aat/300379097" "millimeters"]
   "m"  ["http://vocab.getty.edu/aat/300379099" "meters"]
   "in" ["http://vocab.getty.edu/aat/300379100" "inches"]
   "kg" ["http://vocab.getty.edu/aat/300379226" "kilograms"]
   "g"  ["http://vocab.getty.edu/aat/300379225" "grams"]})

(defn- object? [record] (= :lido/object (:kind record)))

(defn- native-strings [record p]
  (keep #(when (and (= p (:predicate %)) (string? (:value %))) (:value %)) (:assertions record)))

(defn- native-fields [record p]
  (keep #(when (and (= p (:predicate %)) (model/structured-value? (:value %))) (:value/fields (:value %)))
        (:assertions record)))

(defn- event-strings
  "The `p` strings of `record` read in its LIDO event `k` (`lido/event-key`)."
  [record p k]
  (keep #(when (and (= p (:predicate %)) (string? (:value %)) (= k (lido/event-key %))) (:value %))
        (:assertions record)))

(defn- production-strings
  "The `p` strings of `record` read in a production or in no event: what is
   said of the object itself (post-audit 5, G1)."
  [record p]
  (let [ks (lido/production-keys (native-fields record :lido/event))]
    (keep #(when (and (= p (:predicate %)) (string? (:value %)) (lido/of-production? ks (lido/event-key %))) (:value %))
          (:assertions record))))

(defn- event-descriptions [record n] (event-strings record :lido/event-description n))

(defn- provenance-event?
  "True for a LIDO provenance event (`lido00227`, or so termed)."
  [{:keys [type label]}]
  (boolean (or (some-> type (str/ends-with? "lido00227"))
               (#{"provenance" "provenienz"} (some-> label str/lower-case)))))

(defn- statement [content aat label]
  {"type" "LinguisticObject" "content" content "classified_as" [(type-obj aat label)]})

(defn- type-ref [{:keys [uri label]} kind]
  (cond-> {"id" uri "type" kind} label (assoc "_label" label)))

(defn- instant
  "A LIDO year or date as an `xsd:dateTime`, the start (`end?` false) or the end
   of the period it names, or nil when it is neither. A year has at least four
   digits in `xsd:dateTime`: 800 is `0800`, 50 BCE `-0050`."
  [s end?]
  (when s
    (let [t (str/trim s)]
      (if-let [[_ sign y] (re-matches #"(-?)(\d{1,4})" t)]
        (format (if end? "%s%04d-12-31T23:59:59Z" "%s%04d-01-01T00:00:00Z") sign (parse-long y))
        (when (re-matches #"\d{4}-\d{2}-\d{2}" t)
          (str t (if end? "T23:59:59Z" "T00:00:00Z")))))))

(defn- dimension-obj
  "The Linked Art `Dimension` of a LIDO measurement, or nil when its value is
   no number (\"ca. 5\", \"NaN\") or its unit has no AAT term (`object-losses`)."
  [{:keys [type value unit]}]
  (let [v (some-> (lido/number-value value) parse-double)
        [uid ulabel] (get units (some-> unit str/lower-case))
        [tid tlabel] (get dimension-types (some-> type str/lower-case))]
    (when (and v uid)
      (cond-> {"type" "Dimension" "value" v "unit" {"id" uid "type" "MeasurementUnit" "_label" ulabel}}
        tid (assoc "classified_as" [(type-obj tid tlabel)])))))

(defn- object-id [record]
  (export/entity-iri (or (some #(when (= :crm/E22_Human-Made_Object (:kind %)) (:id %)) (:entities record))
                         (:id record))))

(defn- visual-iri [record] (str (object-id record) "/visual"))

(defn- object-docs
  "The Linked Art documents of a museum object (ADR 0052): its
   `HumanMadeObject` and, when its record names subjects or rights for the
   work's image content, the `VisualItem` it shows. They are read from the
   LIDO values: titles as names, the inventory number as an accession number,
   work types and classifications, dimensions (with their AAT type and unit),
   materials, its production (actors, time-span, place, techniques), its
   acquisitions, its keeper, its images, and statements for what Linked Art
   carries only as text (materials and measurements as displayed, the
   description, inscriptions, provenance)."
  [record]
  (let [strs     #(native-strings record %)
        flds     #(native-fields record %)
        titles   (distinct (strs :lido/title))
        ;; a title's languages, each a Linked Art Language
        in-langs (fn [nm t] (let [ls (distinct (keep #(when (= t (:content %)) (language-obj (:lang %))) (flds :lido/title-name)))]
                              (cond-> nm (seq ls) (assoc "language" (vec ls)))))
        events   (sort-by (comp lido/event-order :index) (flds :lido/event))
        actors   (flds :lido/actor)
        mats     (flds :lido/material)
        prod     (first (filter #(= "production" (:kind %)) events))
        pkeys    (lido/production-keys events)
        acqs     (filter #(= "acquisition" (:kind %)) events)
        in-event (fn [n xs] (filter #(= n (:event %)) xs))
        ;; an actor with no authority id is the record's own node, as in the
        ;; CRM view (scoped to the record, ADR 0041): a reference, not an identity
        actor    (fn [{:keys [name uri type]}]
                   (let [group? (lido/group-types type)
                         kind   (if group? :crm/E74_Group :crm/E21_Person)]
                     {"id"     (or uri (export/entity-iri (model/mint-entity-id kind [(str (:id record)) name])))
                      "type"   (if group? "Group" "Person")
                      "_label" name}))
        timespan (fn [{:keys [date earliest latest]}]
                   (let [b (instant earliest false) e (instant latest true)]
                     (when (or date b e)
                       (cond-> {"type" "TimeSpan"}
                         date (assoc "identified_by" [(dissoc (name-obj date) "classified_as")])
                         b    (assoc "begin_of_the_begin" b)
                         e    (assoc "end_of_the_end" e)))))
        place-ids (fn [ev] (filter #(nil? (nt/iri-problem %)) (event-strings record :lido/event-place-id (:index ev))))
        subjects (flds :lido/subject-concept)
        rights   (flds :lido/rights-work-type)
        keepers  (flds :lido/keeper)
        notes    (concat (map #(statement % aat-materials "Material Statement") (production-strings record :lido/materials))
                         (map #(statement % aat-dimensions "Dimension Statement") (strs :lido/measurements))
                         (map #(note-obj %) (strs :lido/description))
                         (map #(statement % aat-inscription "Inscription") (strs :lido/inscription))
                         (map #(statement % aat-credit "Credit Line") (strs :lido/credit-line)))
        ;; another event's description: a provenance statement for a
        ;; provenance event, else a description of the event
        prov     (for [ev events :when (not (#{"production" "acquisition"} (:kind ev)))
                       d  (event-descriptions record (:index ev))]
                   (if (provenance-event? ev)
                     (statement d aat-provenance "Provenance Statement")
                     (cond-> (note-obj d) (:label ev) (assoc "_label" (:label ev)))))
        visual?  (or (seq subjects) (seq rights))
        hmo      (cond-> {"@context" context
                          "id"       (object-id record)
                          "type"     "HumanMadeObject"
                          "_label"   (or (first titles) (first (strs :lido/inventory-number)) (str (:id record)))}
                   (or (seq titles) (seq (strs :lido/inventory-number)))
                   (assoc "identified_by" (vec (concat (when (seq titles) [(in-langs (name-obj (first titles)) (first titles))])
                                                       (map #(in-langs (dissoc (name-obj %) "classified_as") %) (rest titles))
                                                       (map (fn [n] {"type" "Identifier" "content" n
                                                                     "classified_as" [(type-obj aat-accession "Accession Number")]})
                                                            (strs :lido/inventory-number)))))
                   (some :uri (concat (flds :lido/work-type-concept) (flds :lido/classification-concept)))
                   (assoc "classified_as" (vec (keep #(when (:uri %) (type-ref % "Type"))
                                                     (concat (flds :lido/work-type-concept) (flds :lido/classification-concept)))))
                   (seq (keep dimension-obj (flds :lido/dimension)))
                   (assoc "dimension" (vec (keep dimension-obj (flds :lido/dimension))))
                   (seq (filter #(and (:uri %) (not= "technique" (:role %)) (pkeys (:event %))) mats))
                   ;; what it is made of is what its production used (post-audit 5, G1)
                   (assoc "made_of" (vec (for [m mats :when (and (:uri m) (not= "technique" (:role m)) (pkeys (:event m)))]
                                           (type-ref m "Material"))))
                   prod
                   (assoc "produced_by"
                          (let [n  (:index prod)
                                as (in-event n actors)
                                ;; the creators carry out the production; anyone else
                                ;; takes part in it, in a part classified by its role
                                cs (filter #(= "creator" (:responsibility %)) as)
                                os (remove #(= "creator" (:responsibility %)) as)
                                ts (timespan prod)
                                ps (place-ids prod)
                                tq (filter #(and (:uri %) (= "technique" (:role %))) (in-event n mats))]
                            (cond-> {"type" "Production"}
                              (seq cs) (assoc "carried_out_by" (mapv actor cs))
                              (seq os) (assoc "part" (mapv (fn [a] (cond-> {"type" "Production" "carried_out_by" [(actor a)]}
                                                                     (:role a) (assoc "_label" (:role a))
                                                                     (:role-uri a) (assoc "classified_as" [(type-ref {:uri (:role-uri a) :label (:role a)} "Type")])))
                                                           os))
                              ts       (assoc "timespan" ts)
                              (seq ps) (assoc "took_place_at" (mapv (fn [u] {"id" u "type" "Place"}) ps))
                              (seq tq) (assoc "technique" (mapv #(type-ref % "Type") tq)))))
                   (seq acqs)
                   (assoc "changed_ownership_through"
                          (vec (for [ev acqs
                                     :let [ts (timespan ev)
                                           ds (event-descriptions record (:index ev))]]
                                 (cond-> {"type" "Acquisition"}
                                   (:label ev) (assoc "_label" (:label ev))
                                   ts          (assoc "timespan" ts)
                                   (seq ds)    (assoc "referred_to_by" (mapv #(statement % aat-provenance "Provenance Statement") ds))))))
                   (seq (filter :uri keepers))
                   (assoc "current_custodian" (vec (for [{n :name i :uri} keepers :when i] {"id" i "type" "Group" "_label" n})))
                   (seq (concat notes prov)) (assoc "referred_to_by" (vec (concat notes prov)))
                   (seq (flds :lido/resource))
                   (assoc "representation" (vec (distinct (map #(digital-obj (:url %)) (filter #(re-find #"(?i)image" (str (:format %))) (flds :lido/resource))))))
                   visual? (assoc "shows" [{"id" (visual-iri record) "type" "VisualItem"}]))
        vi       (when visual?
                   (cond-> {"@context" context "id" (visual-iri record) "type" "VisualItem"
                            "_label" (str "Visual content of " (get hmo "_label"))}
                     (seq (filter :uri subjects)) (assoc "about" (vec (for [c subjects :when (:uri c)] (type-ref c "Type"))))
                     (seq rights) (assoc "subject_to" (vec (for [r rights]
                                                             (cond-> {"type" "Right"}
                                                               (:label r) (assoc "_label" (:label r))
                                                               (:uri r) (assoc "classified_as" [(type-ref r "Type")])))))))]
    (cond-> [(cond-> hmo (empty? (get hmo "representation")) (dissoc "representation"))]
      vi (conj vi))))

(defn documents
  "The Linked Art JSON-LD documents for `record`'s WEMI view: one
   `LinguisticObject` per Manifestation (a Manifestation is an edition, not a
   physical object), then one `HumanMadeObject` per F5 Item. The record-level
   canonical fields attach to the text when the record has exactly one
   Manifestation; with none or several they cannot be attributed and are
   reported as loss (`export-losses`). Empty for a record with neither."
  [record]
  (cond
    (manifest? record) [(manifest-doc record)]
    (object? record)   (object-docs record)
    :else
    (let [ts (texts record)]
      (into (mapv #(text-doc record % (= 1 (count ts))) ts)
            (map #(item-doc record %) (view/items record))))))

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
          :when (and (= 1 (count (texts record)))       ; else no agent is attributed (`structure-losses`)
                     (not (contains? expressed nm)))]
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
   the record has no Manifestation or several, its record-level canonical
   fields, which no text carries (`:dropped`, none) or which cannot be
   attributed to one of them (`:under-specified`, several)."
  [record]
  (let [ts      (texts record)
        reached (into (set (mapcat (fn [{:keys [manif expr work]}] [(:id manif) expr work]) ts))
                      (map :id (view/items record)))
        loss    (fn [cat subject field msg]
                  (dx/loss {:category cat :subject subject :edge :export :source-field field :message msg
                            :aspect (if (and (= :under-specified cat) (= :lrmoo/F2_Expression field)) :class :value)}))]
    (concat
     (for [{:keys [manif expr]} ts :when expr]
       (loss :under-specified (:id manif) :lrmoo/F2_Expression
             "Expression and Manifestation are one LinguisticObject in Linked Art (no edition level)"))
     (for [e (:entities record)
           :when (and (#{:lrmoo/F1_Work :lrmoo/F2_Expression :lrmoo/F3_Manifestation :lrmoo/F5_Item} (:kind e))
                      (not (contains? reached (:id e))))]
       (loss :dropped (:id e) (:kind e)
             (str (name (:kind e)) " is reached by no Manifestation or Item; not exported")))
     (when (not= 1 (count ts))
       (for [p [:canon/agent :canon/role :canon/note :canon/digital-object :canon/identifier]
             :when (some #(= p (:predicate %)) (:assertions record))]
         (loss (if (empty? ts) :dropped :under-specified) (:id record) p
               (str (name p) " is record-level but the record has " (count ts)
                    " Manifestations; not attributed to any of them")))))))

(defn- manifest-losses
  "What a IIIF manifest's `DigitalObject` leaves out: each agent of its
   metadata (the presented thing's creator or publisher, not the
   manifest's), and the WEMI entities the projection made, which the
   presentation is not."
  [record]
  (concat
   (for [nm (distinct (map :name (agents/occurrences record)))]
     (dx/loss {:category :dropped :subject (:id record) :edge :export :source-field :canon/agent
               :message (str "agent " (pr-str nm) " is the presented thing's, not the IIIF manifest's;"
                             " not attributed to the DigitalObject")}))
   (for [e (:entities record)
         :when (#{:lrmoo/F1_Work :lrmoo/F2_Expression :lrmoo/F3_Manifestation :lrmoo/F5_Item} (:kind e))]
     (dx/loss {:category :dropped :subject (:id e) :edge :export :source-field (:kind e) :aspect :class
               :message (str (name (:kind e)) ": a IIIF manifest is a digital presentation; what it presents is not asserted")}))))

(def ^:private object-written
  "The LIDO natives a museum object's documents write (ADR 0052)."
  #{:lido/title :lido/inventory-number :lido/work-type-concept :lido/classification-concept :lido/dimension
    :lido/material :lido/event :lido/actor :lido/event-description :lido/event-place-id :lido/subject-concept
    :lido/rights-work-type :lido/keeper :lido/repository :lido/repository-id :lido/materials :lido/measurements
    :lido/description :lido/inscription :lido/credit-line :lido/resource :lido/title-name})

(defn- object-losses
  "What a museum object's documents leave out: each LIDO native they do not
   write, each measurement that makes no `Dimension` (its value is no number,
   or its unit has no AAT term), and each keeper with no IRI, which a Linked
   Art reference cannot name."
  [record]
  (let [ps    (distinct (map :predicate (:assertions record)))
        lido? #(= "lido" (namespace %))
        loss  (fn [cat p msg] (dx/loss {:category cat :subject (:id record) :edge :export :source-field p :message msg}))]
    (concat
     (for [p ps :when (and (lido? p) (not (object-written p)))]
       (loss :dropped p (str (pr-str p) " is not expressed in the Linked Art object profile (ADR 0052)")))
     (for [d (native-fields record :lido/dimension) :when (nil? (dimension-obj d))]
       (dx/loss {:category :under-specified :subject (:id record) :edge :export :source-field :lido/dimension :value d
                 :message (str "measurement " (pr-str (:value d)) " " (:unit d) " is no number in a known unit: written only in the measurements statement")}))
     ;; only the production has actors in this profile, and only it and the
     ;; acquisitions a time-span (F5 review)
     (let [evs  (native-fields record :lido/event)
           prod (:index (first (filter #(= "production" (:kind %)) evs)))]
       (concat
        (for [a (native-fields record :lido/actor) :when (not= prod (:event a))]
          (dx/loss {:category :dropped :subject (:id record) :edge :export :source-field :lido/actor :value a
                    :message (str "actor " (pr-str (:name a)) " of an event other than the production is not written in the Linked Art object profile")}))
        (for [ev evs :when (and (not (#{"production" "acquisition"} (:kind ev))) (some ev [:date :earliest :latest]))]
          (dx/loss {:category :dropped :subject (:id record) :edge :export :source-field :lido/event :value ev :fields [:date :earliest :latest]
                    :message (str "the date of the " (:kind ev) " event is not written in the Linked Art object profile")}))))
     ;; what the documents write of each structured value, and what they do not
     (cov/unwritten-fields record
                           {:lido/actor            #(if (= "creator" (:responsibility %)) #{:name :uri} #{:name :uri :role :role-uri})
                            :lido/event            #{:date :earliest :latest}
                            :lido/dimension        #{:value}
                            :lido/resource         #{:url}
                            :lido/subject-concept  #{:uri :label}
                            :lido/material         #{:uri :label}
                            :lido/rights-work-type #{:uri :label}
                            :lido/keeper           #{:name :uri}
                            :lido/title-name       #{:content}}
                           "in the Linked Art object profile (types and units are AAT terms there)")
     (for [p [:lido/work-type-concept :lido/classification-concept]
           c (native-fields record p) :when (nil? (:uri c))]
       (dx/loss {:category :dropped :subject (:id record) :edge :export :source-field p :value c
                 :message (str "type " (pr-str (:label c)) " has no URI: classified_as cannot name it")}))
     ;; each loss names its own value, so it accounts for no other (F7 review)
     (for [t (native-fields record :lido/title-name) :when (nil? (language-obj (:lang t)))]
       (dx/loss {:category :dropped :subject (:id record) :edge :export :source-field :lido/title-name :aspect :qualifier :value t :fields [:lang]
                 :message (str "title language " (pr-str (:lang t)) " has no AAT term here: " (pr-str (:content t)) " is written with no language")}))
     ;; a material is made_of, a technique the production's, only by a URI
     (let [prod  (:index (first (filter #(= "production" (:kind %)) (native-fields record :lido/event))))
           pkeys (lido/production-keys (native-fields record :lido/event))]
       (for [m (native-fields record :lido/material)
             :let [tq? (= "technique" (:role m))]
             :when (or (nil? (:uri m)) (if tq? (not= prod (:event m)) (not (pkeys (:event m)))))]
         (dx/loss {:category :dropped :subject (:id record) :edge :export :source-field :lido/material :value m
                   :message (str (if tq? "technique " "material ") (pr-str (:label m))
                                 (if (:uri m)
                                   (if tq? " is not the production's: no event of this profile has a technique"
                                       " is not the production's: not what the object is made of, not written")
                                   (str " has no URI: " (if tq? "technique" "made_of") " cannot name it")))})))
     (let [pkeys (lido/production-keys (native-fields record :lido/event))]
       (for [a (:assertions record)
             :when (and (= :lido/materials (:predicate a)) (string? (:value a)) (not (lido/of-production? pkeys (lido/event-key a))))]
         (dx/loss {:category :dropped :subject (:id record) :edge :export :source-field :lido/materials :value (:value a)
                   :message (str "materials " (pr-str (:value a)) " of another event than the production are not the object's: not written")})))
     (for [k (native-fields record :lido/keeper) :when (nil? (:uri k))]
       (dx/loss {:category :dropped :subject (:id record) :edge :export :source-field :lido/keeper :value k
                 :message (str "keeper " (pr-str (:name k)) " has no IRI: a Linked Art reference cannot name it")}))
     (for [c (native-fields record :lido/subject-concept) :when (nil? (:uri c))]
       (dx/loss {:category :dropped :subject (:id record) :edge :export :source-field :lido/subject-concept :value c
                 :message (str "subject " (pr-str (:label c)) " has no URI: a VisualItem cannot be about it")}))
     (for [k (native-fields record :lido/keeper) :when (nil? (:uri k))]
       (loss :dropped :lido/repository (str "keeper " (pr-str (:name k)) " has no IRI: a Linked Art reference cannot name it")))
     ;; only the production says where it took place in this profile
     (let [prod (:index (first (filter #(= "production" (:kind %)) (native-fields record :lido/event))))]
       (when (some #(and (= :lido/event-place-id (:predicate %)) (not= prod (lido/event-key %))) (:assertions record))
         [(loss :dropped :lido/event-place-id "the place of an event other than the production is not written in the Linked Art object profile")])))))

(declare non-object-losses)

(defn export-losses
  "Export-edge loss (ADR 0015): every predicate the Linked Art profile does not
   express (dates, relations, native source predicates), one per distinct
   [subject predicate]; one per agent left out (`non-creator-losses`); and the
   structural losses (`structure-losses`)."
  [record]
  (if (object? record)
    (object-losses record)
    (non-object-losses record)))

(defn- non-object-losses
  "The losses of a bibliographic record or a IIIF manifest (see `export-losses`)."
  [record]
  (-> (->> (:assertions record)
           (remove #(contains? expressed (:predicate %)))
           (remove (let [written (into #{} (comp (mapcat #(events-of record (:id (:manif %)))) (mapcat :nodes))
                                       (texts record))]
                     #(and (written (:subject %))
                           (or (event-predicates (:predicate %)) (= :crm/P190_has_symbolic_content (:predicate %))))))
           (remove #(= :canon/loss-marker (:predicate %)))
           (map (juxt :subject :predicate))
           distinct
           (mapv (fn [[s p]]
                   (dx/loss {:category     :dropped
                             :subject      s
                             :edge         :export
                             :source-field p
                             :message      (str (pr-str p) " not expressed in the Linked Art profile (V1)")}))))
      (into (if (manifest? record) (manifest-losses record) (non-creator-losses record)))
      (into (when-not (manifest? record) (structure-losses record)))))

(defn exporter
  "ADR 0007 exporter: render the Linked Art view of `records` (JSON-LD documents,
   newline-separated, see `documents`) and report what the profile cannot express.
   `(fn [opts records] -> {:output String :diagnostics [...]})`."
  [_opts records]
  {:output      (->> records (keep #(let [s (->jsonld %)] (when (seq s) s))) (str/join "\n"))
   :diagnostics (into [] (mapcat export-losses) records)})

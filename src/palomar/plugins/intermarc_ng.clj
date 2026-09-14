(ns palomar.plugins.intermarc-ng
  "INTERMARC-NG importer — the **entity-relation spoke** (ADR 0019). INTERMARC-NG
   (the BnF's NOEMI / Transition-bibliographique production format) is not a flat
   documentary record but a *graph*: each record describes one OEMI entity (Œuvre /
   Expression / Manifestation / Item / agents…), and the fundamental `7xx` relations
   link them by the `$3` identifier of the entity in relation. So this importer maps
   **graph → graph** straight onto the LRMoo view — no string floor, no WEMI-derivation
   inference (the distinctions are given), the least-lossy path in the system.

   It reuses the shared `marcxml` parse (NG is still marcxchange field/subfield XML);
   the NG-specific layer is reading the entity *type* and the OEMI relations:

   | NG field (kitcat manual)                    | LRMoo                              |
   |---------------------------------------------|------------------------------------|
   | record entity type                          | `:lrmoo/F1_Work…F5_Item` (entity)  |
   | `150 $a` Œuvre / `140 $a` Expression / `245 $a` Manifestation | `:lrmoo/R33_has_string` (label) |
   | `730 Exemplifie  $3` (Item→Manifestation)   | `R7_exemplifies`  (F5→F3)          |
   | `740 Matérialise $3` (Manifestation→Expression) | `R4_embodies` (F3→F2)          |
   | `750 Réalise     $3` (Expression→Œuvre)     | `R3_is_realised_in` (F1→F2, *flipped*) |

   The result is one IR record holding all the entities + their WEMI relations — the
   exact shape `derive-wemi` synthesises, but *read*, not inferred. It serialises through
   the existing LRMoo / CIDOC-CRM / Linked Art exporters unchanged, and round-trips
   back via `lrmoo.crm-import` (ADR 0019).

   **Scope / honesty (V1).** Validated on a *spec-faithful synthetic* corpus
   (`test/fixtures/documentary/intermarc-ng/examples/`): public BnF SRU does not yet
   serve native NG entity exports (they live in NOEMI), so there are no real native
   records to test against. The OEMI relation/label codes are the kitcat manual's;
   the record-level entity-type encoding (the `type` attribute here) is the one
   synthetic convention, to be reconciled with a real native export when available.
   Agents (Person entity-records + the `700 A pour créateur` relation) surface as the
   identified Linked Art creator, and subjects (`92x` vedette matière → a Concept /
   Place entity, `:crm/P129_is_about`) reach the CRM graph; collectivities, events
   and finer attributes are a later slice."
  (:require [clojure.string :as str]
            [palomar.model :as model]
            [palomar.plugins.marcxml :as marcxml]
            [palomar.xml :as rx]))

(def ^:private type->kind
  "INTERMARC-NG record entity type → LRMoo WEMI class."
  {"Work"          :lrmoo/F1_Work
   "Oeuvre"        :lrmoo/F1_Work
   "Expression"    :lrmoo/F2_Expression
   "Manifestation" :lrmoo/F3_Manifestation
   "Item"          :lrmoo/F5_Item})

(def ^:private label-tag
  "The access-point field carrying each entity's authorised label."
  {:lrmoo/F1_Work          "150"
   :lrmoo/F2_Expression    "140"
   :lrmoo/F3_Manifestation "245"
   :lrmoo/F5_Item          "200"})

(def ^:private subject-type->kind
  "Subject entity types → their CIDOC-CRM class (the subject is a first-class
   entity, the Work `P129_is_about` it)."
  {"Concept" :crm/E55_Type
   "Place"   :crm/E53_Place})

(def ^:private subject-label-tag
  {:crm/E55_Type "166"     ; 166 Point d'accès autorisé pour un Concept
   :crm/E53_Place "170"})  ; 17x Point d'accès autorisé pour un lieu

(def ^:private subject-heading-tags
  "The 92x vedette-matière fields whose `$3` links the holder to a subject entity."
  ["920" "921" "922" "925" "926" "927"])

(def ^:private relation-tag
  "Fundamental OEMI relation field → `{:pred LRMoo-R-property :flip?}`. `:flip?`
   is true when the NG relation runs opposite to the LRMoo domain→range direction
   (750 Réalise is Expression→Work, but LRMoo R3 is Work→Expression)."
  {"730" {:pred :lrmoo/R7_exemplifies    :flip? false}   ; Item → Manifestation (F5→F3)
   "740" {:pred :lrmoo/R4_embodies       :flip? false}   ; Manifestation → Expression (F3→F2)
   "750" {:pred :lrmoo/R3_is_realised_in :flip? true}})  ; Expression → Œuvre ⇒ Work R3 Expression

;; --- field access (over the shared marcxml element helpers) -----------------

(defn- record-elems [xml-string]
  (filter #(= "record" (marcxml/local-name %))
          (marcxml/elements (rx/parse-str xml-string))))

(defn- datafields [record-elem tag]
  (filter #(and (= "datafield" (marcxml/local-name %)) (= tag (marcxml/attr % "tag")))
          (marcxml/elements record-elem)))

(defn- subfield [field code]
  (some (fn [sf] (when (and (= "subfield" (marcxml/local-name sf)) (= code (marcxml/attr sf "code")))
                   (marcxml/text-of sf)))
        (:content field)))

(defn- first-sub [record-elem tag code]
  (some-> (first (datafields record-elem tag)) (subfield code)))

(defn- ark->iri [ark] (str "http://data.bnf.fr/" ark))

(defn- isni->uri
  "ISNI authority URI from a `100 $1` value like \"ISNI0000000121221863\"."
  [isni]
  (str "https://isni.org/isni/" (str/replace (str isni) #"[^0-9X]" "")))

(defn- person-name
  "The Person access point (`100 $a` surname + `$m` forename) as \"Surname, Forename\"."
  [record-elem]
  (let [a (first-sub record-elem "100" "a")
        m (first-sub record-elem "100" "m")]
    (when a (if m (str a ", " m) a))))

;; --- ingest -----------------------------------------------------------------

(defn ingest
  "Parse an INTERMARC-NG marcxchange `xml-string` (a corpus of entity-records) into
   a one-element vector holding a single IR record:
   - one LRMoo entity per OEMI entity-record (Œuvre/Expression/Manifestation/Item),
     with its `7xx $3` fundamental relations as `:lrmoo/*` assertions;
   - one `:crm/E21_Person` per Person entity-record (ISNI `:iri` from `100 $1`), and
     each `700 A pour créateur $3` as a record-level `:canon/agent` (the controlled
     name) — so the Linked Art export carries the *identified* creator;
   - a floor projection of the Manifestation (`:canon/title`, `:canon/identifier`),
     so the entity graph also converts to the floor targets (DC/MARC21/…).

   `opts` may carry `:record-id` for the graph record id."
  [xml-string opts]
  (let [prov  (model/provenance {:pass :import})
        rid   (or (:record-id opts) :intermarc-ng/graph)
        recs  (record-elems xml-string)
        wemi  (for [r recs
                    :let [ark (marcxml/attr r "id") kind (type->kind (marcxml/attr r "type"))]
                    :when (and ark kind)]
                {:elem r :ark ark :kind kind :id (model/mint-entity-id kind ark)})
        persons (for [r recs
                      :let [ark (marcxml/attr r "id")]
                      :when (and ark (= "Person" (marcxml/attr r "type")))]
                  {:ark ark :id (model/mint-entity-id :crm/E21_Person ark)
                   :name (person-name r) :iri (some-> (first-sub r "100" "1") isni->uri)})
        subjects (for [r recs
                       :let [ark (marcxml/attr r "id") kind (subject-type->kind (marcxml/attr r "type"))]
                       :when (and ark kind)]
                   {:elem r :ark ark :kind kind :id (model/mint-entity-id kind ark)})
        wemi-by-ark    (into {} (map (juxt :ark :id)) wemi)
        person-by-ark  (into {} (map (juxt :ark identity)) persons)
        subject-by-ark (into {} (map (juxt :ark identity)) subjects)
        entities (concat
                  (for [c wemi]
                    (model/entity {:id (:id c) :kind (:kind c) :iri (ark->iri (:ark c)) :provenance prov}))
                  (for [p persons]
                    (model/entity (cond-> {:id (:id p) :kind :crm/E21_Person :provenance prov}
                                    (:iri p) (assoc :iri (:iri p)))))
                  (for [s subjects]
                    (model/entity {:id (:id s) :kind (:kind s) :iri (ark->iri (:ark s)) :provenance prov})))
        labels (for [c wemi
                     :let [lbl (first-sub (:elem c) (label-tag (:kind c)) "a")] :when lbl]
                 (model/assertion {:subject (:id c) :predicate :lrmoo/R33_has_string
                                   :value lbl :status :asserted :provenance prov}))
        links (for [c   wemi
                    [tag {:keys [pred flip?]}] relation-tag
                    f   (datafields (:elem c) tag)
                    :let [tgt (wemi-by-ark (subfield f "3"))] :when tgt
                    :let [[s o] (if flip? [tgt (:id c)] [(:id c) tgt])]]
                (model/assertion {:subject s :predicate pred :value (model/reference o)
                                  :status :asserted :provenance prov}))
        creators (for [c wemi
                       f (datafields (:elem c) "700")
                       :let [p (person-by-ark (subfield f "3"))] :when (and p (:name p))]
                   (model/assertion {:subject rid :predicate :canon/agent
                                     :value (:name p) :status :asserted :provenance prov}))
        subject-labels (for [s subjects
                             :let [lbl (first-sub (:elem s) (subject-label-tag (:kind s)) "a")] :when lbl]
                         (model/assertion {:subject (:id s) :predicate :crm/P190_has_symbolic_content
                                           :value lbl :status :asserted :provenance prov}))
        subject-links (for [c wemi
                            f (mapcat #(datafields (:elem c) %) subject-heading-tags)
                            :let [s (subject-by-ark (subfield f "3"))] :when s]
                        (model/assertion {:subject (:id c) :predicate :crm/P129_is_about
                                          :value (model/reference (:id s)) :status :asserted :provenance prov}))
        manif (first (filter #(= :lrmoo/F3_Manifestation (:kind %)) wemi))
        floor (when manif
                (cond-> [(model/assertion {:subject rid :predicate :canon/identifier
                                           :value (:ark manif) :provenance prov})]
                  (first-sub (:elem manif) "245" "a")
                  (conj (model/assertion {:subject rid :predicate :canon/title
                                          :value (first-sub (:elem manif) "245" "a") :provenance prov}))))]
    [(model/record {:id         rid
                    :kind       :intermarc-ng/graph
                    :entities   (vec entities)
                    :assertions (vec (concat labels links creators subject-labels subject-links floor))})]))

(defn importer
  "ADR 0007 importer: `(fn [opts source] -> {:records [...] :diagnostics []})`."
  [opts source]
  {:records     (ingest source opts)
   :diagnostics []})

(def plugin
  "INTERMARC-NG entity-relation importer plugin (ADR 0007/0019). No `:mapping`: the
   importer emits the LRMoo entities/relations and the floor projection directly,
   so the `:normalize` phase has nothing to do (the WEMI view is *read*, not
   inferred)."
  {:plugin/spec-version 1
   :id                  :palomar/intermarc-ng
   :input-format        :xml
   :mapping             []
   :importer            importer
   :doc                 "INTERMARC-NG (BnF NOEMI) entity-relation importer — OEMI entity-records onto the LRMoo view, agents + a Manifestation floor projection."})

(ns palomar.plugins.lrmoo.crm
  "Additive CIDOC-CRM down-projection of the LRMoo view (museum spoke, ADR 0013;
   scoping in docs/museum-spoke-scoping.md).

   LRMoo's F-classes are subclasses of CRM E-classes and its WEMI relations are
   sub-properties of CRM P-properties, so a CRM serialisation is, *additively*,
   free: for every LRMoo type / relation triple we also emit its CRM super-type /
   super-property triple. The LRMoo triples are kept, so nothing is lost — an LRMoo
   consumer reads F/R, a plain-CRM consumer reads E/P.

   Honest limits (verified against LRMoo_v1.0.owl, hence why we do *not* replace):
   - `F2_Expression` and `F3_Manifestation` both subclass `E73_Information_Object`,
     so a CRM-only consumer cannot tell Expression from Manifestation by type;
   - the WEMI relations map to *generic* CRM properties (`P130_shows_features_of`,
     `P165_incorporates`, `P128_carries`), losing their WEMI-specific meaning.
   A lossy pure-CRM *replacement* (and the export-edge loss it would report) is a
   later slice; this one is the lossless additive view."
  (:require [clojure.string :as str]
            [palomar.diagnostics :as dx]
            [palomar.model :as model]
            [palomar.plugins.lrmoo :as lrmoo]
            [palomar.plugins.lrmoo.export :as export]))

(def crm-base "http://www.cidoc-crm.org/cidoc-crm/")

(def class-superclass
  "LRMoo WEMI F-class -> its CIDOC-CRM super-class (verified vs LRMoo_v1.0.owl)."
  {:lrmoo/F1_Work          "E89_Propositional_Object"
   :lrmoo/F2_Expression    "E73_Information_Object"
   :lrmoo/F3_Manifestation "E73_Information_Object"
   :lrmoo/F5_Item          "E24_Physical_Human-Made_Thing"})

(def event-superclass
  "The LRMoo event classes (ADR 0036) -> their CIDOC-CRM super-class (verified
   vs LRMoo_v1.0.owl). Kept apart from `class-superclass`, which `crm-import`
   reads back to recover WEMI classes."
  {:lrmoo/F30_Manifestation_Creation "E12_Production"})

(def ^:private also-typed
  "The further CRM classes a class of `event-superclass` falls under, which a
   pure-CRM type must also state for its properties' domains: F30 is an E65
   Creation as well as an E12 Production (the domain of P94)."
  {:lrmoo/F30_Manifestation_Creation ["E65_Creation"]})

(def event-superproperty
  "The LRMoo event properties (ADR 0036) -> their CIDOC-CRM super-property."
  {:lrmoo/R24_created "P94_has_created"})

(def replaced-classes
  "Every LRMoo class the pure-CRM serialisation writes as its CRM super-class."
  (merge class-superclass event-superclass))

(def property-superproperty
  "LRMoo WEMI R-property -> its CIDOC-CRM super-property (verified vs the OWL)."
  {:lrmoo/R3_is_realised_in "P130_shows_features_of"
   :lrmoo/R4_embodies       "P165_incorporates"
   :lrmoo/R7_exemplifies    "P128_carries"})

(def replaced-properties
  "Every LRMoo property the pure-CRM serialisation writes as its CRM super-property."
  (merge property-superproperty event-superproperty))

;; Indexes keyed by the *IRI strings* as they appear in `export/triples`. An
;; entity's string is already CRM (E41 Appellation, P190), so it has no twin.
(def ^:private class-iri->crm
  (into {} (for [[k e] replaced-classes] [(lrmoo/iri k) (str crm-base e)])))

(def ^:private class-iri->also
  (into {} (for [[k es] also-typed] [(lrmoo/iri k) (mapv #(str crm-base %) es)])))

(def ^:private prop-iri->crm
  (into {} (for [[k p] replaced-properties] [(lrmoo/iri k) (str crm-base p)])))

(defn crm-triples
  "`export/triples` of `record`, each augmented additively with its CIDOC-CRM
   super-type / super-property triple. Lossless only because the LRMoo triples are
   retained alongside: the CRM part alone is not (see `crm-only-losses`)."
  [record]
  (mapcat
   (fn [[s p o :as triple]]
     (cond
       ;; an rdf:type triple onto an LRMoo F-class -> also assert the CRM super-type
       (and (= p export/rdf-type) (class-iri->crm (:iri o)))
       (into [triple [s p {:iri (class-iri->crm (:iri o))}]]
             (for [c (class-iri->also (:iri o))] [s p {:iri c}]))

       ;; a WEMI relation -> also assert its CRM super-property (same subject/object)
       (prop-iri->crm p)
       [triple [s (prop-iri->crm p) o]]

       :else [triple]))
   (export/triples record)))

(defn ->ntriples
  "Render `record`'s additive LRMoo+CRM view as N-Triples. \"\" when empty."
  [record]
  (export/render-ntriples (crm-triples record)))

(defn ->turtle
  "Render `record`'s additive LRMoo+CRM view as Turtle (crm:/lrmoo: prefixed). \"\" when empty."
  [record]
  (or (export/render-turtle (crm-triples record)) ""))

(defn ->jsonld
  "Render `record`'s additive LRMoo+CRM view as compacted JSON-LD. \"\" when empty."
  [record]
  (or (export/render-jsonld (crm-triples record)) ""))

(defn exporter
  "ADR 0007 exporter: the additive LRMoo+CRM N-Triples of `records`. Loss is the
   same as the LRMoo exporter's (this view adds CRM triples, drops nothing), so it
   reuses `export/export-losses`."
  [_opts records]
  {:output      (->> records
                     (into [] (comp (map ->ntriples) (remove str/blank?)))
                     (str/join "\n"))
   :diagnostics (into [] (mapcat export/export-losses) records)})

;; ---------------------------------------------------------------------------
;; Pure-CRM (replacement) — lossy, with the loss reported (slice 2)
;;
;; Replaces each LRMoo triple with its CRM equivalent and drops the LRMoo
;; original. Two losses follow directly from the OWL and are reported at the
;; export edge (ADR 0015):
;;   - F2_Expression and F3_Manifestation both become E73_Information_Object, so
;;     their type distinction is lost              -> :under-specified
;;   - each WEMI relation becomes a generic CRM property (R3->P130, R4->P165, …)
;;                                                 -> :coerced
;; The dropped non-:lrmoo/* predicates are reported by `export/export-losses`,
;; exactly as for the additive view.
;; ---------------------------------------------------------------------------

(defn crm-only-triples
  "`export/triples` with each LRMoo type/relation *replaced* by its CRM
   super-type/-property (the LRMoo triples dropped). Pure CRM — lossy; the loss is
   reported by `crm-only-losses`."
  [record]
  (mapcat
   (fn [[s p o :as triple]]
     (cond
       ;; RiC-O is not CRM: the archival view is left out (`crm-only-losses`)
       (or (str/starts-with? p export/rico-base) (str/starts-with? (str (:iri o)) export/rico-base))
       []

       (and (= p export/rdf-type) (class-iri->crm (:iri o)))
       (into [[s p {:iri (class-iri->crm (:iri o))}]]
             (for [c (class-iri->also (:iri o))] [s p {:iri c}]))

       (prop-iri->crm p)
       [[s (prop-iri->crm p) o]]

       :else [triple]))
   (export/triples record)))

(defn ->crm-only-ntriples
  "Render `record` as pure CIDOC-CRM N-Triples (no LRMoo F/R). \"\" when empty."
  [record]
  (export/render-ntriples (crm-only-triples record)))

(defn ->crm-only-turtle
  "Render `record` as pure CIDOC-CRM Turtle (crm: prefixed, no LRMoo). \"\" when empty."
  [record]
  (or (export/render-turtle (crm-only-triples record)) ""))

(defn ->crm-only-jsonld
  "Render `record` as pure CIDOC-CRM compacted JSON-LD (no LRMoo). \"\" when empty."
  [record]
  (or (export/render-jsonld (crm-only-triples record)) ""))

(defn- has-kind? [record kind]
  (boolean (some #(= kind (:kind %)) (:entities record))))

(defn crm-only-losses
  "Export-edge loss (ADR 0015) the pure-CRM replacement incurs: the E73 type
   collapse (`:under-specified`, when both an Expression and a Manifestation are
   present), the Work and Item typed only by their super-class (`:under-specified`:
   an E89 or E24 does not say it is a Work or an Item, so `crm-import` will not
   recover it), and each WEMI relation's generalisation to a generic CRM property
   (`:coerced`)."
  [record]
  (let [generic  (for [kind [:lrmoo/F1_Work :lrmoo/F5_Item :lrmoo/F30_Manifestation_Creation]
                       :when (has-kind? record kind)]
                   (dx/loss {:category     :under-specified
                             :subject      (:id record)
                             :edge         :export
                             :source-field kind
                             :aspect       :class
                             :message      (str (name kind) " serialises as "
                                                (replaced-classes kind)
                                                ", which does not identify it as a "
                                                (name kind) " in pure CRM")}))
        collapse (when (and (has-kind? record :lrmoo/F2_Expression)
                            (has-kind? record :lrmoo/F3_Manifestation))
                   [(dx/loss {:category     :under-specified
                              :subject      (:id record)
                              :edge         :export
                              :source-field :lrmoo/F3_Manifestation
                              :aspect       :class
                              :message      (str "F2_Expression and F3_Manifestation both serialise as "
                                                 "E73_Information_Object; the WEMI type distinction is "
                                                 "lost in pure CRM")})])
        coerced  (for [p (distinct (map :predicate (:assertions record)))
                       :when (contains? replaced-properties p)]
                   (dx/loss {:category     :coerced
                             :subject      (:id record)
                             :edge         :export
                             :source-field p
                             :aspect       :relation
                             :message      (str (name p) " serialised as the generic "
                                                (replaced-properties p))}))
        rico     (for [[s p] (distinct (keep #(when (= "rico" (namespace (:predicate %))) [(:subject %) (:predicate %)])
                                             (:assertions record)))]
                   (dx/loss {:category     :dropped
                             :subject      s
                             :edge         :export
                             :source-field p
                             :aspect       (if (some #(and (= s (:subject %)) (= p (:predicate %)) (model/reference-value? (:value %)))
                                                     (:assertions record))
                                             :relation :value)
                             :message      (str (pr-str p) " is RiC-O, which pure CIDOC CRM does not express")}))]
    (vec (concat generic collapse coerced rico))))

(defn crm-only-exporter
  "ADR 0007 exporter for the *pure* CRM serialisation: only E/P triples, with the
   replacement's loss reported (E73 collapse + relation generalisation) on top of
   the dropped-native-predicate loss the LRMoo export already accounts."
  [_opts records]
  {:output      (->> records
                     (into [] (comp (map ->crm-only-ntriples) (remove str/blank?)))
                     (str/join "\n"))
   :diagnostics (into [] (mapcat (fn [r] (concat (export/export-losses r)
                                                 (crm-only-losses r))))
                      records)})

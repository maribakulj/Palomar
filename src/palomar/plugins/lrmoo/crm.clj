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
            [palomar.plugins.lrmoo :as lrmoo]
            [palomar.plugins.lrmoo.export :as export]))

(def crm-base "http://www.cidoc-crm.org/cidoc-crm/")

(def class-superclass
  "LRMoo WEMI F-class -> its CIDOC-CRM super-class (verified vs LRMoo_v1.0.owl)."
  {:lrmoo/F1_Work          "E89_Propositional_Object"
   :lrmoo/F2_Expression    "E73_Information_Object"
   :lrmoo/F3_Manifestation "E73_Information_Object"
   :lrmoo/F5_Item          "E24_Physical_Human-Made_Thing"})

(def property-superproperty
  "LRMoo WEMI R-property -> its CIDOC-CRM super-property (verified vs the OWL)."
  {:lrmoo/R3_is_realised_in "P130_shows_features_of"
   :lrmoo/R4_embodies       "P165_incorporates"
   :lrmoo/R7_exemplifies    "P128_carries"
   :lrmoo/R33_has_string    "P3_has_note"})

;; Indexes keyed by the *IRI strings* as they appear in `export/triples`.
(def ^:private class-iri->crm
  (into {} (for [[k e] class-superclass] [(lrmoo/iri k) (str crm-base e)])))

(def ^:private prop-iri->crm
  (into {} (for [[k p] property-superproperty] [(lrmoo/iri k) (str crm-base p)])))

(defn crm-triples
  "`export/triples` of `record`, each augmented additively with its CIDOC-CRM
   super-type / super-property triple. Lossless: the LRMoo triples are retained."
  [record]
  (mapcat
   (fn [[s p o :as triple]]
     (cond
       ;; an rdf:type triple onto an LRMoo F-class -> also assert the CRM super-type
       (and (= p export/rdf-type) (class-iri->crm (:iri o)))
       [triple [s p {:iri (class-iri->crm (:iri o))}]]

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
  (map
   (fn [[s p o :as triple]]
     (cond
       (and (= p export/rdf-type) (class-iri->crm (:iri o)))
       [s p {:iri (class-iri->crm (:iri o))}]

       (prop-iri->crm p)
       [s (prop-iri->crm p) o]

       :else triple))
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
   present) and each WEMI relation's generalisation to a generic CRM property
   (`:coerced`)."
  [record]
  (let [collapse (when (and (has-kind? record :lrmoo/F2_Expression)
                            (has-kind? record :lrmoo/F3_Manifestation))
                   [(dx/loss {:category     :under-specified
                              :subject      (:id record)
                              :edge         :export
                              :source-field :lrmoo/F3_Manifestation
                              :message      (str "F2_Expression and F3_Manifestation both serialise as "
                                                 "E73_Information_Object; the WEMI type distinction is "
                                                 "lost in pure CRM")})])
        coerced  (for [p (distinct (map :predicate (:assertions record)))
                       :when (contains? property-superproperty p)]
                   (dx/loss {:category     :coerced
                             :subject      (:id record)
                             :edge         :export
                             :source-field p
                             :message      (str (name p) " serialised as the generic "
                                                (property-superproperty p))}))]
    (vec (concat collapse coerced))))

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

(ns palomar.plugins.lrmoo
  "LRMoo rich-pivot vocabulary plugin (ADR 0013 / 0017).

   The *rich* sibling of `palomar.plugins.canonical`: where canonical is the
   thin, domain-neutral floor (the flat `:canon/*` predicates), this plugin is
   the rich ceiling — the bibliographic WEMI structure from IFLA LRMoo v1.0
   (an extension of CIDOC-CRM). It is the vocabulary the rich pivot view is
   expressed in (ADR 0013); a source mapped only to canonical yields a valid
   but under-specified LRMoo view, and that gap is measured loss (ADR 0015).

   This slice ships the **WEMI core** only — Work / Expression / Manifestation
   / Item and the three object properties that chain them — grown by
   justification (D2). The CRM object core (for the museum spokes), attribute
   properties, the projection rules that *derive* the typed view, and the RDF
   exporter are later WP-2 slices.

   Like every plugin (ADR 0007), this is plain data: the core never sees
   `:lrmoo/*` (opaque keywords to it; the one-way dependency is guarded by
   `palomar.architecture-test`). Local names are the exact LRMoo v1.0
   identifiers, so `iri` round-trips a term to its canonical IRI and the future
   RDF exporter is a one-liner.")

(def lrmoo-iri
  "Namespace IRI of LRMoo v1.0 (from the spec header)."
  "http://iflastandards.info/ns/lrm/lrmoo/")

(def entity-kinds
  "The WEMI classes (LRMoo v1.0), used as entity `:kind` values:
   F1 Work, F2 Expression, F3 Manifestation, F5 Item."
  #{:lrmoo/F1_Work
    :lrmoo/F2_Expression
    :lrmoo/F3_Manifestation
    :lrmoo/F5_Item})

(def vocabulary
  "The LRMoo predicates this slice ships — the three WEMI-chain object
   properties (domain/range verified against LRMoo_v1.0.owl). Reference-valued:
   each links one entity to another."
  #{:lrmoo/R3_is_realised_in   ;; F1_Work          -> F2_Expression
    :lrmoo/R4_embodies         ;; F3_Manifestation -> F2_Expression
    :lrmoo/R7_exemplifies})    ;; F5_Item          -> F3_Manifestation

(def wemi-links
  "The WEMI chain as [from-kind property to-kind] triples, exactly as LRMoo
   v1.0 declares domain/range. The skeleton the projection rules and the
   typed-traversal API (later slices) build on."
  [[:lrmoo/F1_Work          :lrmoo/R3_is_realised_in :lrmoo/F2_Expression]
   [:lrmoo/F3_Manifestation :lrmoo/R4_embodies       :lrmoo/F2_Expression]
   [:lrmoo/F5_Item          :lrmoo/R7_exemplifies    :lrmoo/F3_Manifestation]])

(defn entity-kind?
  "True if `k` is an LRMoo WEMI class (a valid entity `:kind`)."
  [k]
  (contains? entity-kinds k))

(defn vocabulary?
  "True if `p` is an LRMoo predicate shipped by this slice."
  [p]
  (contains? vocabulary p))

(defn iri
  "The canonical LRMoo IRI for an `:lrmoo/*` term — faithful to v1.0, since the
   local names are the spec's own. e.g. `:lrmoo/F1_Work` →
   \"http://iflastandards.info/ns/lrm/lrmoo/F1_Work\"."
  [term]
  (str lrmoo-iri (name term)))

(def plugin
  "The LRMoo rich-pivot vocabulary plugin (ADR 0007 / 0013). Plain data: ships
   the WEMI vocabulary as values; no importer, mapping, or rules yet
   (projection rules are a later WP-2 slice). Register alongside the format
   spokes that target the rich pivot."
  {:plugin/spec-version 1
   :id                  :palomar/lrmoo
   :doc                 "LRMoo rich pivot vocabulary — WEMI core (ADR 0013/0017)."})

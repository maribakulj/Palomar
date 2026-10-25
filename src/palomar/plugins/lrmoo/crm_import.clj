(ns palomar.plugins.lrmoo.crm-import
  "CRM/LRMoo RDF → LRMoo view — the *upward* path (ADR 0019). The exporters
   `palomar.plugins.lrmoo.export` / `…lrmoo.crm` project the WEMI view *down* to
   RDF; this reads such RDF *back* into the LRMoo entities + WEMI relations.

   The whole point (ADR 0019): CRM → LRM is a **downcast**, and a downcast succeeds
   exactly when the specific type is still present. So for each node we take the
   **most specific** rdf:type available:

   - an LRMoo **F-class** IRI (`F1_Work`, `F2_Expression`, `F3_Manifestation`,
     `F5_Item`) ⇒ recovered unambiguously — the additive `:crm`/`:lrmoo` export keeps
     these, so the trip is lossless;
   - failing that, a node typed only by a CRM **E-class** super-class is *not*
     downcast: sub-classing does not invert. `E89_Propositional_Object` is F1's
     super-class, but an `E89` may as well be a theory or a programme, and an `E24`
     any physical made thing. Such a node is reported as an `:under-specified` loss
     (ADR 0015) and no entity is minted. `E73_Information_Object` is the super-class
     of *both* `F2` and `F3`, so a node typed only `E73` is reported as an
     `:ambiguity-collapsed` loss. This is the collapse the pure-CRM (`:crm-only`)
     export warns about in `crm/crm-only-losses`: the loss report of the
     down-projection *is* the spec of what the up-projection cannot recover.

   This is not wired as a documentary `convert` source — CRM is the hub-as-output,
   not a spoke. It exists to demonstrate the directionality rule on real data
   (`crm-import-test`): our own F-typed CRM round-trips to LRM; our flattened CRM
   does not."
  (:require [clojure.string :as str]
            [palomar.diagnostics :as dx]
            [palomar.model :as model]
            [palomar.plugins.lrmoo :as lrmoo]
            [palomar.plugins.lrmoo.crm :as crm]
            [palomar.plugins.lrmoo.export :as export]))

;; --- inverse indices (forward maps live in lrmoo / lrmoo.crm) ---------------

(def ^:private lrmoo-class->kind
  "LRMoo F-class IRI → WEMI kind — the specific (unambiguous) recovery."
  (into {} (for [k lrmoo/entity-kinds] [(lrmoo/iri k) k])))

(def ^:private lrmoo-prop->pred
  "LRMoo R-property IRI → predicate keyword."
  (into {} (for [p lrmoo/vocabulary] [(lrmoo/iri p) p])))

(def ^:private crm-class->kinds
  "CRM E-class IRI → the set of WEMI kinds that specialise it. `E73` maps to BOTH
   `F2_Expression` and `F3_Manifestation` — that is the irreducible ambiguity."
  (reduce-kv (fn [m kind e] (update m (str crm/crm-base e) (fnil conj #{}) kind))
             {} crm/class-superclass))

;; --- N-Triples parse --------------------------------------------------------

(def ^:private iri-triple-re #"^<([^>]+)> <([^>]+)> <([^>]+)> \.$")
(def ^:private lit-triple-re #"^<([^>]+)> <([^>]+)> \"(.*)\" \.$")

(defn parse-ntriples
  "Parse an N-Triples string into `[s p o]` triples (`o` = `{:iri _}` | `{:lit _}`),
   the same shape `lrmoo.export` renders. Skips blank lines."
  [nt]
  (for [line (str/split-lines nt)
        :when (not (str/blank? line))]
    (if-let [[_ s p o] (re-matches iri-triple-re line)]
      [s p {:iri o}]
      (if-let [[_ s p l] (re-matches lit-triple-re line)]
        [s p {:lit l}]
        (throw (ex-info "Unparseable N-Triple line" {:line line}))))))

;; --- recovery ---------------------------------------------------------------

(defn- node-kind
  "Recover the WEMI kind of a node from its set of rdf:type IRIs. Only an LRMoo
   F-class types a node. A CRM super-class alone yields the candidate kinds it
   *could* specialise to, never a kind. Returns `[:typed kind]`,
   `[:super-class-only #{kind}]` (one candidate), `[:ambiguous #{kinds}]`, or
   `[:unknown iris]`."
  [type-iris]
  (if-let [specific (some lrmoo-class->kind type-iris)]
    [:typed specific]
    (let [cands (reduce into #{} (keep crm-class->kinds type-iris))]
      (cond
        (= 1 (count cands)) [:super-class-only cands]
        (seq cands)         [:ambiguous cands]
        :else               [:unknown type-iris]))))

(defn- super-class-field
  "The CRM super-class shared by candidate `kinds`, as a `:crm/*` source field."
  [kinds]
  (keyword "crm" (crm/class-superclass (first kinds))))

(defn recover
  "Recover the LRMoo view from CRM/LRMoo N-Triples `nt`. Returns:

     {:typed            {node-iri kind}     ; F-typed, unambiguously recovered WEMI entities
      :super-class-only {node-iri #{kind}}  ; a CRM super-class with one candidate — not downcast
      :ambiguous        {node-iri #{kinds}} ; a CRM super-class with several candidates
      :relations        #{[from-iri R-pred to-iri]}}  ; WEMI links (from LRMoo R-properties)"
  [nt]
  (let [triples   (parse-ntriples nt)
        by-subj   (group-by first (filter (fn [[_ p _]] (= p export/rdf-type)) triples))
        resolved  (into {} (for [[s trs] by-subj]
                             [s (node-kind (set (map (comp :iri #(nth % 2)) trs)))]))
        relations (set (for [[s p o] triples
                             :let  [pred (lrmoo-prop->pred p)]
                             :when (and pred (:iri o))]
                         [s pred (:iri o)]))]
    {:typed            (into {} (for [[s [tag k]] resolved :when (= tag :typed)]            [s k]))
     :super-class-only (into {} (for [[s [tag k]] resolved :when (= tag :super-class-only)] [s k]))
     :ambiguous        (into {} (for [[s [tag k]] resolved :when (= tag :ambiguous)]        [s k]))
     :relations        relations}))

(defn ->record
  "Mint an IR record from recovered CRM/LRMoo RDF: one `model/entity` per
   F-typed node (keyed by its IRI), a relation assertion per WEMI link, and one
   loss (ADR 0015) per node that carries only a CRM super-class:
   `:under-specified` when the super-class has a single WEMI specialisation
   (`E89`, `E24`), `:ambiguity-collapsed` when it has several (the `E73`
   Expression/Manifestation collapse). No entity is minted for either.
   `(… -> {:record :diagnostics})`."
  [nt rid]
  (let [{:keys [typed super-class-only ambiguous relations]} (recover nt)
        prov   (model/provenance {:pass :import})
        ents   (for [[iri kind] typed]
                 (model/entity {:id iri :kind kind :iri iri :provenance prov}))
        rels   (for [[from pred to] relations]
                 (model/assertion {:subject from :predicate pred
                                   :value (model/reference to) :provenance prov}))
        loss   (fn [category iri cands]
                 (dx/loss {:category     category
                           :subject      iri
                           :edge         :import
                           :source-field (super-class-field cands)
                           :message      (str iri " carries only a CRM super-class; cannot downcast to "
                                              (str/join " | " (sort (map name cands))))}))
        losses (concat (for [[iri cands] super-class-only] (loss :under-specified iri cands))
                       (for [[iri cands] ambiguous]        (loss :ambiguity-collapsed iri cands)))]
    {:record      (model/record {:id rid :kind :lrmoo/recovered
                                 :entities (vec ents) :assertions (vec rels)})
     :diagnostics (vec losses)}))

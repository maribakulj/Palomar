(ns palomar.plugins.lrmoo.crm-import
  "CRM/LRMoo RDF → LRMoo view — the *upward* path (ADR 0019). The exporters
   `palomar.plugins.lrmoo.export` / `…lrmoo.crm` project the WEMI view *down* to
   RDF; this reads such RDF *back* into the LRMoo entities + WEMI relations.

   The whole point (ADR 0019): CRM → LRM is a **downcast**, and a downcast succeeds
   exactly when the specific type is still present. So for each node we take the
   **most specific** rdf:type available:

   - an LRMoo **F-class** IRI (`F1_Work`, `F2_Expression`, `F3_Manifestation`,
     `F5_Item`) ⇒ recovered unambiguously — the additive `:crm`/`:lrmoo` export keeps
     these, so the WEMI typing, links and entity strings round-trip; anything else
     in the graph is reported as loss, not dropped silently;
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
            [palomar.plugins.lrmoo.export :as export]
            [palomar.plugins.lrmoo.ntriples :as nt]))

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

(defn parse-ntriples
  "Parse an N-Triples string into `[s p o]` (`s` the subject IRI or `_:label`,
   `p` the predicate IRI, `o` = `{:iri _}` | `{:bnode _}` | `{:lit _ :lang :datatype}`)
   with the RDF 1.1 reader (`palomar.plugins.lrmoo.ntriples`): escapes, language
   tags, datatypes, blank nodes and comments are handled, and a syntax error
   throws with its line and column (audit S16)."
  [nt]
  (for [[subj pred obj] (nt/parse nt)]
    [(nt/term-key subj) (:iri pred) obj]))

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

(def ^:private r33-iri (lrmoo/iri :lrmoo/R33_has_string))

(def ^:private legacy-r33-twin
  "The CRM twin earlier `:crm` exports wrote beside each R33 string."
  (str crm/crm-base "P3_has_note"))

(def ^:private appellation-types
  "rdf:types of the node an entity's string is written on: `E41_Appellation`
   (P1's range), and `F12_Nomen`, which earlier exports used."
  #{export/appellation-class (lrmoo/iri :lrmoo/F12_Nomen)})

(def ^:private crm-super-property
  "LRMoo R-property IRI → its CRM super-property IRI (the additive `:crm` view's twin)."
  (into {} (for [[k p] crm/property-superproperty] [(lrmoo/iri k) (str crm/crm-base p)])))

(def ^:private kind->type-iris
  "WEMI kind → the rdf:type IRIs that type it: its F-class and, in the additive
   `:crm` view, the CRM super-class twin."
  (into {} (for [k lrmoo/entity-kinds]
             [k (cond-> #{(lrmoo/iri k)}
                  (crm/class-superclass k) (conj (str crm/crm-base (crm/class-superclass k))))])))

(defn recover
  "Recover the LRMoo view from CRM/LRMoo N-Triples `nt`. Returns:

     {:typed            {node-iri kind}     ; F-typed, unambiguously recovered WEMI entities
      :super-class-only {node-iri #{kind}}  ; a CRM super-class with one candidate — not downcast
      :ambiguous        {node-iri #{kinds}} ; a CRM super-class with several candidates
      :relations        #{[from-iri R-pred to-iri]}  ; WEMI links (from LRMoo R-properties)
      :strings          [[node-iri literal]]          ; strings of typed nodes, in order
      :unrecovered      [[s p o]]}                    ; every other triple (see `->record`)

   A triple is recovered when it types a node by its F-class or that class's CRM
   twin, links two typed nodes by an R-property, or gives a typed node its
   string. A string is read as `lrmoo.export` writes it, entity
   —P1_is_identified_by→ E41 Appellation —P190_has_symbolic_content→ \"s\", and
   in the two forms earlier exports used: through an F12 Nomen with R33, and a
   direct entity —R33→ \"s\" (with its `P3_has_note` twin). Another rdf:type
   on a typed node (`schema:Book`) is not recovered, and is reported. Everything
   else is `:unrecovered` and reported by `->record` (audit S17)."
  [nt]
  (let [triples   (parse-ntriples nt)
        types     (group-by first (filter (fn [[_ p o]] (and (= p export/rdf-type) (:iri o))) triples))
        resolved  (into {} (for [[s trs] types]
                             [s (node-kind (set (map (comp :iri #(nth % 2)) trs)))]))
        by-tag    (reduce-kv (fn [m s [t k]] (assoc-in m [t s] k)) {} resolved)
        typed     (get by-tag :typed {})
        sco       (get by-tag :super-class-only {})
        amb       (get by-tag :ambiguous {})
        node      (fn [o] (nt/term-key o))
        relations (set (for [[s p o] triples
                             :let  [pred (lrmoo-prop->pred p)]
                             :when (and pred (not= p r33-iri) (node o) (typed s) (typed (node o)))]
                         [s pred (node o)]))
        string-of (group-by first (filter (fn [[_ p o]] (and (#{export/symbolic-content r33-iri} p) (:lit o)))
                                          triples))
        appel-of  (for [[s p o] triples
                        :when (and (= p export/identified-by) (typed s) (string-of (node o)))]
                    [s (node o)])
        appels    (set (map second appel-of))
        strings   (vec (concat
                        (for [s (filter typed (keys string-of))
                              [_ _ o] (string-of s)]
                          [s o])
                        (for [[s n] appel-of, [_ _ o] (string-of n)] [s o])))
        rel-twins (set (for [[s pred o] relations] [s (crm-super-property (lrmoo/iri pred)) o]))
        str-twins (set (for [s (filter typed (keys string-of))
                             [_ p o] (string-of s) :when (= p r33-iri)]
                         [s legacy-r33-twin o]))
        recovered? (fn [[s p o]]
                     (cond
                       (= p export/rdf-type)
                       (cond
                         (typed s)        (contains? (kind->type-iris (typed s)) (:iri o))
                         (appels s)       (contains? appellation-types (:iri o))
                         (or (sco s) (amb s)) (contains? crm-class->kinds (:iri o))  ; reported as a downcast loss
                         :else            false)
                       (= p export/identified-by) (and (typed s) (contains? appels (node o)))
                       (#{export/symbolic-content r33-iri} p) (and (:lit o) (or (typed s) (contains? appels s)))
                       (lrmoo-prop->pred p)  (contains? relations [s (lrmoo-prop->pred p) (node o)])
                       (:lit o)              (contains? str-twins [s p o])
                       :else                 (contains? rel-twins [s p (node o)])))]
    {:typed            typed
     :super-class-only sco
     :ambiguous        amb
     :relations        relations
     :strings          strings
     :unrecovered      (vec (remove recovered? triples))}))

(defn ->record
  "Mint an IR record from recovered CRM/LRMoo RDF: one `model/entity` per
   F-typed node (keyed by its IRI; a blank node gets an id minted from the record
   and its label, and no `:iri`), a relation assertion per WEMI link, and one
   loss (ADR 0015) per node that carries only a CRM super-class:
   `:under-specified` when the super-class has a single WEMI specialisation
   (`E89`, `E24`), `:ambiguity-collapsed` when it has several (the `E73`
   Expression/Manifestation collapse). No entity is minted for either.
   `(… -> {:record :diagnostics})`."
  [nt rid]
  (let [{:keys [typed super-class-only ambiguous relations strings unrecovered]} (recover nt)
        prov   (model/provenance {:pass :import})
        ;; an IRI node is keyed by its IRI, which it also carries; a blank node
        ;; (`_:b0`) has no IRI, so it gets a minted id, scoped to this record
        bnode? #(str/starts-with? % "_:")
        id-of  (into {} (for [[k kind] typed]
                          [k (if (bnode? k) (model/mint-entity-id kind (str rid "|" k)) k)]))
        ents   (for [[k kind] typed]
                 (model/entity (cond-> {:id (id-of k) :kind kind :provenance prov}
                                 (not (bnode? k)) (assoc :iri k))))
        rels   (concat
                (for [[from pred to] relations]
                  (model/assertion {:subject (id-of from) :predicate pred
                                    :value (model/reference (id-of to)) :provenance prov}))
                (for [[s o] strings]
                  (model/assertion {:subject (id-of s) :predicate :lrmoo/R33_has_string
                                    :value (:lit o) :provenance prov})))
        coerced (when (some (fn [[_ o]] (or (:lang o)
                                            (and (:datatype o)
                                                 (not= "http://www.w3.org/2001/XMLSchema#string" (:datatype o)))))
                            strings)
                  [(dx/loss {:category     :coerced
                             :subject      rid
                             :edge         :import
                             :source-field :lrmoo/R33_has_string
                             :message      "an entity string's language tag or datatype is not carried"})])
        dropped (for [[p ts] (group-by second unrecovered)]
                  (dx/loss {:category     :dropped
                            :subject      rid
                            :edge         :import
                            :source-field (keyword "rdf" p)
                            :message      (str (count ts) " triple(s) with predicate <" p
                                               "> not recovered into the LRMoo view")}))
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
     :diagnostics (vec (concat losses coerced dropped))}))

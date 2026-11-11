(ns palomar.identity
  "Palomar's own identity relations between agents, and the one rule that
   says what each export writes for them (lot O2, ADR 0061).

   **Why not SKOS.** SKOS makes `skos:exactMatch` and `skos:closeMatch`
   sub-properties of `skos:mappingRelation`, itself a sub-property of
   `skos:semanticRelation` (SKOS Reference S39, S40, S42), whose domain and
   range are `skos:Concept` (S19, S20). An RDFS reasoner reading a SKOS
   mapping between two persons types both as concepts. So the IR states
   identity between agents with relations of its own, in the `palomar:`
   vocabulary (`vocab`, `urn:palomar:vocab#`, where the dataset graph
   classes and the PROV-O terms already are). Concepts keep SKOS.

   **Three relations, kept apart** (`relations`):
   - (a) a reconciliation link from a record's local agent (or a mention's
     node) to an authority: `authority-match` (`palomar:matchesAuthority`),
     the identity claim, and `authority-candidate`
     (`palomar:resemblesAuthority`), a candidate for review that says alike,
     not the same;
   - (b) two authority records that state one entity (lot N6, ADR 0059):
     `same-entity` (`palomar:sameEntityAs`). It names the clustering's
     equivalence links; the statement itself stays in the authority files;
   - (c) the clustering's result: `cluster-membership`
     (`palomar:inCluster`), from a local agent to its batch's person node.

   Each is a claim of the IR like any other: a status (`:asserted`,
   `:proposed`, `:needs-review`, `:accepted`, `:rejected`, ...), evidence
   and provenance in its `:provenance`, decisions as annotations.

   **The export rule** (`export-rule`) is data, read by every exporter
   through `rdf-predicate` and `linked-art-equivalent?`; nothing else
   decides it. The status policy upstream still applies first
   (`convert/export-records`): a plain export states no proposal.

   **Old records** (`upgrade`): a record written before this lot states
   the same claims as `:skos/exactMatch` and `:skos/closeMatch`. `upgrade`
   rewrites them, and moves the curation decisions about them to the new
   claims. `palomar.ir/read-records` applies it, so an old IR document,
   decisions included, still loads."
  (:require [palomar.curate :as curate]
            [palomar.model :as model]))

(def vocab
  "The `palomar:` vocabulary namespace."
  "urn:palomar:vocab#")

(def authority-match
  "(a) A local agent is the entity an authority record describes."
  :palomar/matchesAuthority)

(def authority-candidate
  "(a) A local agent may be the entity an authority record describes: a
   candidate for review, never an identity."
  :palomar/resemblesAuthority)

(def same-entity
  "(b) Two authority records describe one entity, by their own statements."
  :palomar/sameEntityAs)

(def cluster-membership
  "(c) A local agent belongs to its batch's person node (a cluster)."
  :palomar/inCluster)

(def relations
  "What each relation links and what Palomar guarantees of it. Not one is
   declared transitive for a reasoner: each guarantee is kept by the code,
   and pinned by the tests named in ADR 0061."
  {authority-match
   {:iri       (str vocab "matchesAuthority")
    :kind      :reconciliation
    :between   [:agent :reconcile/authority]
    :identity? true
    :closure   (str "never closed by Palomar's code; through one authority, two accepted claims whose "
                    "agents cannot be one person are a reported conflict (agent-cluster) and are never "
                    "joined on the authority's node (apply-agent-links)")}
   authority-candidate
   {:iri       (str vocab "resemblesAuthority")
    :kind      :reconciliation
    :between   [:agent :reconcile/authority]
    :identity? false
    :closure   "not transitive: a candidate joins nothing until a curator confirms it (confirm-identity)"}
   same-entity
   {:iri       (str vocab "sameEntityAs")
    :kind      :equivalence
    :between   [:reconcile/authority :reconcile/authority]
    :identity? true
    :closure   (str "symmetric; the union of what the records state, refused as a whole on an ISNI clash "
                    "through a chain or a pseudonym in the component (ADR 0059)")}
   cluster-membership
   {:iri       (str vocab "inCluster")
    :kind      :cluster
    :between   [:agent :reconcile/person]
    :identity? true
    :closure   (str "the closure of a batch's memberships, through their person nodes, is the batch's "
                    "clusters and nothing more: no cluster holds two cannot-linked agents")}})

(def relation-iris
  "Relation keyword -> its IRI."
  (into {} (map (fn [[k v]] [k (:iri v)])) relations))

(defn relation?
  "Whether `p` is one of Palomar's identity relations."
  [p]
  (contains? relations p))

(def owl-same-as "http://www.w3.org/2002/07/owl#sameAs")

;; ---------------------------------------------------------------------------
;; The export rule, by destination profile
;; ---------------------------------------------------------------------------

(def entity-iri-schemes
  "IRIs that name a person or a group itself (a real-world object), not a
   record or a concept about it. `owl:sameAs` needs one: a BnF catalogue
   ARK, a LoC name authority or a ULAN subject is a record or a
   `skos:Concept`, and the person is not the same thing as either
   (ADR 0059: record identity is not entity identity). `:agents-only?`
   says the scheme names agents and nothing else; a scheme that also names
   works, places or subjects (GND, VIAF, Wikidata, data.bnf.fr) needs the
   authority's stated type before an `owl:sameAs`."
  [{:scheme :isni     :pattern #"^https?://(www\.)?isni\.org/isni/[0-9X]{16}$"               :agents-only? true}
   {:scheme :viaf     :pattern #"^https?://viaf\.org/viaf/[0-9]+$"                           :agents-only? false}
   {:scheme :wikidata :pattern #"^https?://(www\.)?wikidata\.org/entity/Q[0-9]+$"            :agents-only? false}
   {:scheme :gnd      :pattern #"^https?://d-nb\.info/gnd/[0-9X-]+$"                         :agents-only? false}
   {:scheme :loc-rwo  :pattern #"^https?://id\.loc\.gov/rwo/agents/[a-z0-9]+$"               :agents-only? true}
   {:scheme :bnf      :pattern #"^https?://data\.bnf\.fr/ark:/12148/cb[0-9a-z]+#about$"      :agents-only? false}
   {:scheme :ulan     :pattern #"^https?://vocab\.getty\.edu/ulan/[0-9]+-agent$"             :agents-only? true}])

(defn entity-scheme
  "The `entity-iri-schemes` entry `iri` falls under, or nil."
  [iri]
  (when (string? iri) (first (filter #(re-matches (:pattern %) iri) entity-iri-schemes))))

(defn entity-iri?
  "Whether `iri` names the entity itself (`entity-iri-schemes`)."
  [iri]
  (some? (entity-scheme iri)))

(def agent-types
  "The authority types each agent kind may be the same as. A person agent
   is a person; a group a corporate body or a family. An undifferentiated
   person (a GND Tn), a work or any other stated type is none of them."
  {:crm/E21_Person #{"person"}
   :crm/E74_Group  #{"corporateBody" "family"}})

(defn kind-agrees?
  "Whether an agent of kind `kind` may be an authority of stated type
   `entity-type`: when the type is stated, it is one `agent-types` allows
   for the kind; when it is not, any (the type is unknown, so it cannot
   disagree; `:entity-iri` then asks for an agents-only scheme)."
  [kind entity-type]
  (boolean (and (agent-types kind)
                (or (nil? entity-type) (contains? (agent-types kind) entity-type)))))

(def conflict-codes
  "The diagnostics that name identity claims which cannot all hold:
   accepted claims the last clustering found in conflict (ADR 0057; lot
   M7), and those the export finds itself (`palomar.identity-check`).
   `owl:sameAs` would close them."
  #{:reconcile/identity-conflict :reconcile/identity-events-conflict :reconcile/export-identity-conflict})

(def export-rule
  "What each destination profile writes for an identity claim. For each
   profile and relation: `:fact`, the term written when every condition
   in `:when` holds; `:else`, what is written otherwise (`:internal`, the
   relation's own IRI, or nothing when absent). A profile not named here
   (MARC21, DC, MODS, IIIF) writes none of them.

   Conditions:
   - `:in-force`    the claim is `:accepted` or `:asserted`;
   - `:curated`     it is `:asserted`, or the decision that last put it in
                    force is a curator's: signed (`:by`) and made one claim
                    at a time (`:curated`, which only `curate/decide-one`
                    and `curate/supersede` write; `curated-claims`);
   - `:no-conflict` no conflict diagnostic of its record names it
                    (`conflict-codes`): those of the last clustering, and
                    those the export finds over the records it writes;
   - `:kind-agrees` the authority's stated type, when stated, is the
                    agent's kind (`kind-agrees?`);
   - `:entity-iri`  the authority's IRI names the entity, not a record
                    (`entity-iri?`), and, for a scheme that also names
                    other things, its type is stated.

   The status policy comes first (`convert/export-records`): a plain
   export holds no proposal, so `:internal` there only ever writes an
   accepted claim that fails a condition. With proposals, or in a
   dataset's proposals and history graphs, the internal relation carries
   them."
  {:rdf        {authority-match     {:fact owl-same-as :when [:in-force :curated :no-conflict :kind-agrees :entity-iri]
                                     :else :internal}
                authority-candidate {:else :internal}
                same-entity         {:else :internal}
                cluster-membership  {:else :internal}}
   :linked-art {authority-match {:fact "equivalent" :when [:in-force :curated :no-conflict :kind-agrees]}}})

(defn conflicted-claims
  "The assertion ids `record`'s conflict diagnostics name."
  [record]
  (into #{} (comp (filter #(conflict-codes (:code %))) (mapcat #(get-in % [:detail :claims])))
        (:diagnostics record)))

(defn curated-claims
  "The ids of `record`'s claims whose last coming into force is a curator's
   decision: the last decision about the claim that accepted it, or the
   last supersession naming it the replacement, is marked `:curated`
   (made one claim at a time) and signed (`:by`). A policy's acceptance
   (`curate/curate`, `--policy accept`) is never marked, whatever its
   context says."
  [record]
  (let [events (for [n (:annotations record)
                     :when (= curate/decision-predicate (:predicate n))
                     :let [f (get-in n [:value :value/fields])
                           t (cond (= :supersede (:verdict f)) (:replaced-by f)
                                   (= :accepted (:to f))       (:about n))]
                     :when t]
                 [t f])
        last-of (reduce (fn [m [t f]] (assoc m t f)) {} events)]
    (into #{} (keep (fn [[t f]] (when (and (true? (:curated f)) (some? (:by f))) t))) last-of)))

(defn context
  "What the export rule reads of `record`: its entities, the claims its
   conflict diagnostics name, and its curated claims."
  [record]
  {:entities   (into {} (map (juxt :id identity)) (:entities record))
   :conflicted (conflicted-claims record)
   :curated    (curated-claims record)})

(defn- holds?
  [condition a {:keys [entities conflicted curated]}]
  (let [target (get entities (when (model/reference-value? (:value a)) (:value/target (:value a))))
        id     (model/assertion-id a)]
    (case condition
      :in-force    (model/in-force? a)
      :curated     (or (model/asserted? a) (contains? (or curated #{}) id))
      :no-conflict (not (contains? (or conflicted #{}) id))
      :kind-agrees (kind-agrees? (:kind (get entities (:subject a))) (:entity-type target))
      :entity-iri  (let [scheme (entity-scheme (:iri target))]
                     (boolean (and scheme (or (:agents-only? scheme) (some? (:entity-type target)))))))))

(defn- term-for
  [profile a ctx]
  (when-let [{:keys [fact else] :as rule} (get-in export-rule [profile (:predicate a)])]
    (if (and fact (every? #(holds? % a ctx) (:when rule)))
      fact
      (when (= :internal else) (relation-iris (:predicate a))))))

(defn rdf-predicate
  "The predicate IRI the RDF profiles (N-Triples, Turtle, JSON-LD, CRM,
   pure CRM, N-Quads, TriG) write for identity claim `a`, or nil for none.
   `ctx` is its record's `context`."
  [a ctx]
  (term-for :rdf a ctx))

(defn linked-art-equivalent?
  "Whether Linked Art writes identity claim `a` as an `equivalent` of its
   actor. `ctx` as `rdf-predicate`."
  [a ctx]
  (= "equivalent" (term-for :linked-art a ctx)))

(defn output-iris
  "Every IRI the RDF profiles may write relation `p` as."
  [p]
  (into #{} (keep identity) [(relation-iris p) (get-in export-rule [:rdf p :fact])]))

;; ---------------------------------------------------------------------------
;; Records written before this lot
;; ---------------------------------------------------------------------------

(def ^:private legacy
  "`[old predicate, the kind of its object]` -> the relation it stated."
  {[:skos/exactMatch :reconcile/authority] authority-match
   [:skos/closeMatch :reconcile/authority] authority-candidate
   [:skos/exactMatch :reconcile/person]    cluster-membership})

(def collision-code
  "The diagnostic of an old and a new claim of one fact whose statuses
   differ and whose decisions cannot be ordered by time."
  :palomar/identity-upgrade-collision)

(defn- remap-value
  "A structured decision value with its `:replaced-by` id moved."
  [moved v]
  (if (and (model/structured-value? v) (moved (get-in v [:value/fields :replaced-by])))
    (update-in v [:value/fields :replaced-by] moved)
    v))

(defn- collapse
  "`record` with each identity claim it holds twice (an old claim upgraded
   onto a new one of the same fact) made one. The two share an id, so
   their decisions are already one history. When they agree (one status),
   one is kept, the in-force one first. When they differ, the status is
   the one the latest decision by time (`:at`) gave; when a decision has
   no time, the claim goes to `:needs-review`, and a `collision-code`
   diagnostic names it."
  [record]
  (let [groups (group-by model/assertion-id (filter #(relation? (:predicate %)) (:assertions record)))
        twice  (into {} (filter #(< 1 (count (val %)))) groups)]
    (if (empty? twice)
      record
      (let [decisions (fn [id] (for [n (:annotations record)
                                     :when (and (= id (:about n)) (= curate/decision-predicate (:predicate n)))]
                                 (get-in n [:value :value/fields])))
            resolve   (fn [id as]
                        (let [kept (or (first (filter model/in-force? as)) (first as))
                              ss   (distinct (map :status as))
                              ds   (decisions id)]
                          (cond
                            (= 1 (count ss))          [kept nil]
                            (and (seq ds) (every? :at ds)) [(assoc kept :status (:to (last (sort-by :at ds)))) nil]
                            :else                     [(assoc kept :status :needs-review)
                                                       (model/diagnostic
                                                        {:severity :warning :code collision-code :subject (:subject kept)
                                                         :message  (str "an old and a new claim of one identity, " (pr-str (vec ss))
                                                                        ", whose decisions have no time to order them: to review")
                                                         :detail   {:claims [id] :statuses (vec ss)}})])))
            out       (into {} (for [[id as] twice] [id (resolve id as)]))
            seen      (volatile! #{})
            as        (into [] (keep (fn [a]
                                       (let [id (model/assertion-id a)]
                                         (cond
                                           (not (and (relation? (:predicate a)) (twice id))) a
                                           (@seen id) nil
                                           :else (do (vswap! seen conj id) (first (out id)))))))
                            (:assertions record))
            ds        (keep second (vals out))]
        (cond-> (assoc record :assertions as)
          (seq ds) (update :diagnostics (fnil into []) ds))))))

(defn upgrade
  "`record` with the identity claims an older Palomar wrote as SKOS
   mappings (`:skos/exactMatch` or `:skos/closeMatch` to an authority,
   `:skos/exactMatch` to a person node) restated with Palomar's relations:
   the same subject, value, status, confidence and provenance. The
   annotations about them (the curation decisions, ADR 0028), the
   replacement a decision names and the claims a conflict diagnostic names
   follow them. An old claim that becomes a claim the record already holds
   is made one with it (`collapse`), so the record holds one claim per
   fact. A SKOS mapping to anything else is left as it is. A record with
   none is returned unchanged."
  [record]
  (let [kind  (into {} (map (juxt :id :kind)) (:entities record))
        new-p (fn [a] (when (model/reference-value? (:value a))
                        (legacy [(:predicate a) (kind (:value/target (:value a)))])))
        olds  (filter new-p (:assertions record))]
    (if (empty? olds)
      record
      (let [moved (into {} (for [a olds] [(model/assertion-id a) (model/assertion-id (assoc a :predicate (new-p a)))]))
            ann   (fn [n] (-> n
                              (update :about #(get moved % %))
                              (update :value #(remap-value moved %))))
            diag  (fn [d] (if (seq (get-in d [:detail :claims]))
                            (update-in d [:detail :claims] (partial mapv #(get moved % %)))
                            d))]
        (collapse
         (cond-> (update record :assertions (partial mapv #(if-let [p (new-p %)] (assoc % :predicate p) %)))
           (:annotations record) (update :annotations (partial mapv ann))
           (:diagnostics record) (update :diagnostics (partial mapv diag))))))))

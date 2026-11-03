(ns palomar.inferences
  "The relations a projection states as facts, rule by rule (ADR 0055).

   A projection (`:pass :infer`) asserts relations between the nodes it
   makes. Most transcribe what the source says in another model: a LIDO
   production is the object's `P108i`. Some are inferences: the source does
   not say them, a rule of this projection reads them so, and that rule is
   a choice worth knowing. A relation the source does not support at all is
   no fact: it is a proposal (ADR 0042), as is a design's use by a
   production (post-audit 7, I1).

   `declared` lists, for each rule, the relations it transcribes and those it
   infers, each with the reason it may state it. `undeclared` finds, in
   records, an asserted relation no rule declares: the fidelity audit reports
   it, and the test suite holds every corpus to none. A new rule, or a rule
   that starts to state a new relation, must say which it is."
  (:require [palomar.model :as model]))

(def declared
  "Rule → `{:transcribes #{predicate…} :infers {predicate reason}}`."
  {:rule.lido/project
   {:transcribes #{:crm/P108i_was_produced_by :crm/P24i_changed_ownership_through :crm/P31i_was_modified_by
                   :crm/P2_has_type :crm/P1_is_identified_by :crm/P4_has_time-span :crm/P7_took_place_at
                   :crm/P43_has_dimension :crm/P91_has_unit :crm/P104_is_subject_to :crm/P138i_has_representation
                   :crm/P32_used_general_technique :crm/P126_employed :crm/P94_has_created
                   :crm/P49_has_former_or_current_keeper}
    :infers
    {:crm/P12i_was_present_at
     "an event of the object that is no production, acquisition, modification or design, nor precedes it (a commissioning, a planning), is one it was present at"
     :crm/P14_carried_out_by
     "an actor of an activity whose role is not passive (no commissioner, patron, donor), nor gave or received the title, carried it out"
     :crm/P11_had_participant
     "an actor of an event that is no activity, or whose role is passive, took part in it"
     :crm/P22_transferred_title_to
     "an acquisition's actor whose role names a buyer or a new owner, and none who gave the title, received it"
     :crm/P23_transferred_title_from
     "an acquisition's actor whose role names a seller, a donor or a former owner, and none who received the title, gave it up"
     :crm/P45_consists_of
     "the object consists of its production's materials, and of those of an event of no stated kind that says nothing else (museum-digital's practice)"
     :crm/P50_has_current_keeper
     "a repository set not typed as a former one names the current keeper"
     :crm/P62_depicts
     "a made object's subject concepts are what it depicts: LIDO's subject sets say what a work is about, which for a picture is what it shows"}}

   :rule.ead/project
   {:transcribes #{:rico/hasRecordSetType :rico/isOrWasIncludedIn}
    :infers
    {:rico/hasCreator
     "every name of an origination is read as a creator, whatever its label or relator: RiC-O tells a creator from an accumulator or a collector, EAD's origination need not"}}

   :rule.eac/project
   {:transcribes #{}}

   :rule.events/manifestation-creation
   {:transcribes #{:lrmoo/R24_created :crm/P4_has_time-span :crm/P7_took_place_at :crm/P14_carried_out_by}}

   ;; the LRMoo floor proposes its WEMI links, it states none
   :rule.lrmoo/project
   {}

   :rule.intermarc/derive-wemi
   {:infers
    {:lrmoo/R3_is_realised_in
     "the Work is Palomar's, minted from the author's authority (100 $3) and the uniform title (145): the record identifies no Work, it realises one"
     :lrmoo/R4_embodies
     "the record's manifestation embodies the one expression of the Work its 145 names (ADR 0013)"}}})

(defn- relation? [a]
  (and (= :asserted (:status a))
       (= :infer (get-in a [:provenance :pass]))
       (model/reference-value? (:value a))
       (not (model/out-of-force? a))))

(defn- kind-of [rule predicate]
  (let [{:keys [transcribes infers]} (declared rule)]
    (cond (contains? transcribes predicate) :transcribes
          (contains? infers predicate)      :infers)))

(defn- rules-of
  "The projection rules that derive `a`: its own, and those of its
   alternative justifications (`:also`)."
  [a]
  (let [p (:provenance a)]
    (distinct (for [q (cons p (:also p)) :when (= :infer (:pass q))] (:rule q)))))

(defn undeclared
  "The asserted relations of `records` a projection stated with a rule that
   does not declare them: `[{:subject :predicate :rule}…]`, distinct, one per
   rule that derives the relation (its own and its `:also`). What a curator
   accepted is a decision of theirs, not a rule's, and is not looked at;
   nor are entities and literal values: this is about relations."
  [records]
  (vec (distinct (for [r records a (:assertions r) :when (relation? a)
                       rule (rules-of a)
                       :when (nil? (kind-of rule (:predicate a)))]
                   {:subject (:subject a) :predicate (:predicate a) :rule rule}))))

(defn inferred
  "How many of the asserted relations of `records` each declared inference
   stated: `{[rule predicate] n}`. What the source does not say in so many
   words, and a rule reads into it."
  [records]
  (frequencies (for [r records a (:assertions r) :when (relation? a)
                     :let [rule (get-in a [:provenance :rule])]
                     :when (= :infers (kind-of rule (:predicate a)))]
                 [rule (:predicate a)])))

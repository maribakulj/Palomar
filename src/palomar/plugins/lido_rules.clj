(ns palomar.plugins.lido-rules
  "Part of the CIDOC CRM projection of a LIDO record written as rules: data a
   museum can read and change, not code (post-audit 8, rules study; ADR 0035).

   Two parts:
   - **who did what in an event**: each actor of an event, carried out
     (`P14`), took part (`P11`), or, in an acquisition, gave up or received
     the title (`P23`, `P22`, proposed: a role's words do not establish who
     held the title, and both imply `P14`);
   - **what a made object shows**, a concept, a person, a group or a place
     (lot N7): a subject typed as a description or an
     identification, or of no type but named by a vocabulary of images
     (Iconclass), is what it depicts (`P62`); any other, an interpretation or
     a subject of no type in another vocabulary, is what the visual item it
     shows is about (`P65` → `E36` → `P129`), never said to be depicted. A
     person or a place of no type is a named individual, LIDO's
     identification: depicted (N7 review).

   An actor is its occurrence: the record, the event and its position there,
   unless an authority URI names it. Two actors of one name are two.

   The words that decide (roles, event and subject types) are vocabularies,
   data under `resources/palomar/vocab/lido`, not regexes in code. The DSL has
   no disjunction and no conditional term, so a rule is written once per
   branch; `rules` generates them from the tables below, and each generated
   rule is still data (`rules` can be printed, read and diffed)."
  (:require [palomar.model :as model]
            [palomar.rules :as rules]))

;; ---------------------------------------------------------------------------
;; Events: their CRM class, by kind, else by type
;; ---------------------------------------------------------------------------

(def ^:private fixed-kinds
  "The kinds of event whose class their kind says (lido/event-classes.edn)."
  ["production" "acquisition" "modification" "design"])

(def ^:private activity
  "An event of no fixed kind is an activity by its type URI or code, its
   term, a technique it used, or what it precedes (post-audit 8, K4: one
   disjunction)."
  '(any [(field ?ev :type ?type) (vocab-some? :lido/event-types ?type :event/activity)]
        [(field ?ev :label ?label) (vocab-some? :lido/event-types ?label :event/activity)]
        [(field ?ev :type ?type) (vocab-some? :lido/event-types ?type :event/prior)]
        [(field ?ev :label ?label) (vocab-some? :lido/event-types ?label :event/prior)]
        [(field ?ev :terms ?terms) (vocab-some? :lido/event-types ?terms :event/activity)]
        [(field ?ev :terms ?terms) (vocab-some? :lido/event-types ?terms :event/prior)]
        [[?r :lido/material ?m] (field ?m :event ?n) (field ?m :role "technique")]))

(def ^:private event-branches
  "Each way an event gets its class: `[label class activity? match]`."
  [["by-kind"  '(lookup :lido/event-classes ?kind) true [(list 'in? '?kind fixed-kinds)]]
   ["activity" :crm/E7_Activity true [(list 'not (list 'in? '?kind fixed-kinds)) activity]]
   ["event"    :crm/E5_Event false [(list 'not (list 'in? '?kind fixed-kinds)) (list 'not activity)]]])

;; ---------------------------------------------------------------------------
;; Actors: who they are, and what they did
;; ---------------------------------------------------------------------------

(def ^:private passive '(vocab-all? :lido/actor-roles ?role :role/passive))
(def ^:private gives   '(vocab-some? :lido/actor-roles ?role :role/gives-title))
(def ^:private gets    '(vocab-some? :lido/actor-roles ?role :role/receives-title))
(def ^:private role    '(field ?a :role ?role))
(def ^:private making  '(in? ?kind ["production" "design"]))
(def ^:private in-acq  '(= ?kind "acquisition"))

(def ^:private attributed
  "What an attribution qualifier says an actor of a making did (post-audit 8,
   K2): influenced it, was formerly held to have made it, may have made it,
   took part, or made it."
  [["after"     "influence"  [[:crm/P15_was_influenced_by :asserted]]]
   ["follower"  "related"    [[:crm/P15_was_influenced_by :asserted]]]
   ["former"    "former"     [[:crm/P14_carried_out_by :superseded]]]
   ["uncertain" "uncertain"  [[:crm/P14_carried_out_by :proposed]]]
   ["took-part" "other-role" [[:crm/P11_had_participant :asserted]]]
   ["maker"     "maker-role" [[:crm/P14_carried_out_by :asserted]]]])

(def ^:private unqualified
  "No attribution qualifier of a making says what the actor did: its roles do."
  (list 'not making '(field ?a :attribution ?att) (list 'in? '?att (mapv second attributed))))

(def ^:private activity-doings
  "What an actor of an activity did: `[label match [[predicate status]…]]`."
  (concat
   (for [[label at claims] attributed] [label [making (list 'field '?a :attribution at)] claims])
   ;; in an acquisition, giving up or receiving the title outweighs being
   ;; passive: a donor took part and gave it up, proposed (K5 review)
   [["passive" [unqualified role passive (list 'not in-acq role (list 'any [gives] [gets]))] [[:crm/P11_had_participant :asserted]]]
    ["gives" [unqualified in-acq role gives (list 'not role gets)]
     [[:crm/P11_had_participant :asserted] [:crm/P23_transferred_title_from :proposed]]]
    ["gets" [unqualified in-acq role gets (list 'not role gives)]
     [[:crm/P11_had_participant :asserted] [:crm/P22_transferred_title_to :proposed]]]
    ["carried-out" [unqualified (list 'not role passive)
                    (list 'not in-acq role gives (list 'not role gets))
                    (list 'not in-acq role gets (list 'not role gives))]
     [[:crm/P14_carried_out_by :asserted]]]]))

(def actor-rules
  "One rule per event branch and doing; who the actor is, its class and its
   identity, are terms (post-audit 8, K4): an authority's URI, else its
   occurrence, the record, the event and its position there."
  (vec
   (for [[elabel eclass activity? ematch] event-branches
         [dlabel dmatch claims]            (if activity? activity-doings [["participant" [] [[:crm/P11_had_participant :asserted]]]])
         :let [event (list 'mint eclass '(str ?r) '?n)
               agent '(mint (lookup :lido/actor-classes ?at) (or ?u (str ?r "|" ?n "|" ?pos)))]]
     {:id      (keyword "rule.lido.actor" (str elabel "-" dlabel))
      :phase   :infer
      :doc     (str "An actor of an event (" elabel "): " dlabel ".")
      :match   (vec (concat '[[?r :lido/event ?ev] (field ?ev :index ?n) (field ?ev :kind ?kind)]
                            ematch
                            '[[?r :lido/actor ?a] (field ?a :event ?n) (field ?a :name ?name) (field ?a :position ?pos)
                              (field ?a :type ?at) (optional (field ?a :uri ?u))]
                            dmatch))
      :produce {:construct (vec (concat
                                 (for [[p status] claims] [event p agent {:status status}])
                                 [[agent :crm/P190_has_symbolic_content '?name {:status :asserted}]]))}})))

;; ---------------------------------------------------------------------------
;; Subjects: depicted, or meant
;; ---------------------------------------------------------------------------

(def ^:private made
  "The record says its object was made: a production, or an artefact's type
   and no natural one. A test (`(not (not …))`: some row exists), which binds
   nothing and so multiplies no row; one disjunction (K4)."
  [["made" '[(not (not (any [[?r :lido/event ?pe] (field ?pe :kind "production")]
                            [(not (not [?r :lido/work-type ?at] (vocab-some? :lido/object-types ?at :object/artefact))
                                  (not [?r :lido/classification ?ac] (vocab-some? :lido/object-types ?ac :object/artefact)))
                             (not [?r :lido/work-type ?nt] (vocab-some? :lido/object-types ?nt :object/natural))
                             (not [?r :lido/classification ?nc] (vocab-some? :lido/object-types ?nc :object/natural))])))]]])

(def ^:private iconclass
  "Some IRI of the subject, its first or another, names it in a vocabulary of
   images (Iconclass): a test, which binds nothing (N7 review)."
  '(not (not (any [(field ?c :uri ?cs) (vocab-some? :lido/subject-schemes ?cs :subject/ofness)]
                  [(field ?c :other-ids ?co) (vocab-some? :lido/subject-schemes ?co :subject/ofness)]))))

(def ^:private ofness '[(field ?c :subject-type ?st) (vocab-all? :lido/subject-types ?st :subject/ofness)])

(def ^:private marked-about
  "An id of the subject is typed as one of what the work is about, whatever
   its set's type (YCBA's `subjectActorAbout`, `subject-id-types.edn`)."
  '[(field ?c :id-type ?it) (vocab-some? :lido/subject-id-types ?it :subject/aboutness)])

(def ^:private concept-decisions
  "A concept: depicted when typed so, or of no type but named by a vocabulary
   of images; else what the visual item is about (K5)."
  [["depicted" ofness]
   ["shown"    ['(not (field ?c :subject-type _)) iconclass]]
   ["meant"    [(list* 'not ofness) (list 'not '(not (field ?c :subject-type _)) iconclass)]]])

(def ^:private named-decisions
  "A person, a group or a place: a named individual. LIDO's subjectWrap
   defines Identification as \"the specific subject, including named
   historical, mythological, or fictional subjects\": of no type, it is one
   the work depicts, as typed a description or an identification; typed
   otherwise (an interpretation), or by an id typed so, it is what the
   visual item is about (N7 review)."
  [["depicted"     (into [(list* 'not marked-about)] ofness)]
   ["named"        [(list* 'not marked-about) '(not (field ?c :subject-type _))]]
   ["meant-marked" marked-about]
   ["meant"        [(list* 'not marked-about) '(field ?c :subject-type ?st) (list* 'not ofness)]]])

(def ^:private subject-kinds
  "What a work may show or mean, each read like a concept (lot N7): `[label
   predicate ids decisions]`, the native it is read from, how its node is
   named and classed (an authority's URI, else a node of the record, by its
   name, never one of another record; a person's or a group's class by its
   actor type), and what decides whether it is depicted. One IRI is one
   class: a person or a place whose IRI is a subject concept of the record is
   that concept, and no node of its own (N7 review). A concept's rules keep
   their ids (K5)."
  [[nil :lido/subject-concept
    [["authority" '[(field ?c :uri ?cu)] '(mint :crm/E55_Type ?cu)]
     ["local"     '[(not (field ?c :uri _)) (field ?c :label ?cl)] '(mint :crm/E55_Type (str ?r) ?cl)]]
    concept-decisions]
   ["person" :lido/subject-person
    [["authority" '[(field ?c :type ?ct) (field ?c :uri ?cu) (not [?r :lido/subject-concept ?k] (field ?k :uri ?cu))]
      '(mint (lookup :lido/actor-classes ?ct) ?cu)]
     ["local"     '[(field ?c :type ?ct) (not (field ?c :uri _))] '(mint (lookup :lido/actor-classes ?ct) (str ?r) ?cl)]]
    named-decisions]
   ["place" :lido/subject-place
    [["authority" '[(field ?c :uri ?cu) (not [?r :lido/subject-concept ?k] (field ?k :uri ?cu))] '(mint :crm/E53_Place ?cu)]
     ["local"     '[(not (field ?c :uri _))] '(mint :crm/E53_Place (str ?r) ?cl)]]
    named-decisions]])

(def subject-rules
  "What a made object shows, its concepts, and the persons and places it
   represents (lot N7): one rule per kind, identity, decision and test of
   making. Depicted (`P62`), or what the visual item is about (`P65` → `E36`
   → `P129`), by `concept-decisions` and `named-decisions`. A person is never
   depicted by code."
  (vec
   (for [[kind pred ids decisions] subject-kinds
         [mlabel mmatch]           made
         [clabel cmatch ctype]     ids
         [slabel smatch]           decisions
         :let [obj '(mint :lido/object (str ?r))
               vi  '(mint :crm/E36_Visual_Item (str ?r))
               nm  (if kind :name :label)]]
     {:id      (keyword "rule.lido.subject" (str (some-> kind (str "-")) slabel "-" clabel "-" mlabel))
      :phase   :infer
      :doc     (str "A subject " (or kind "concept") " (" clabel ") of a made object (" mlabel "): " slabel ".")
      ;; the record first: what is said of it (made) is tested once, not once a subject
      :match   (vec (concat '[[?r :meta/id ?r]] mmatch [['?r pred '?c] (if kind (list 'field '?c nm '?cl) (list 'optional (list 'field '?c nm '?cl)))]
                            cmatch smatch))
      :produce {:construct (vec (concat
                                 (if (#{"depicted" "shown" "named"} slabel)
                                   [[obj :crm/P62_depicts ctype {:status :asserted}]]
                                   [[obj :crm/P65_shows_visual_item vi {:status :asserted}]
                                    [vi :crm/P129_is_about ctype {:status :asserted}]])
                                 [[ctype :crm/P190_has_symbolic_content '?cl {:status :asserted}]]))}})))

(def all-rules (into actor-rules subject-rules))

(defn agent-node
  "The node the rules make of an actor of record `rid`: its authority URI's,
   else its occurrence's (the record, the event, its position there), of the
   class its type names. What Linked Art references it by, so that both
   views name one node (post-audit 8, K5)."
  [rid {:keys [uri event position type]}]
  (model/mint-entity-id (rules/lookup-value :lido/actor-classes (or type ""))
                        [(or uri (str rid "|" event "|" position))]))

(defn concept-node
  "The node the rules make of a subject concept of record `rid`."
  [rid {:keys [uri label]}]
  (if uri (model/mint-entity-id :crm/E55_Type [uri]) (model/mint-entity-id :crm/E55_Type [(str rid) label])))

(defn subject-class
  "The class of the node the rules make of a subject `fields` read as native
   `p`: a concept's `E55_Type`, a person's or a group's by its actor type, a
   place's `E53_Place` (lot N7)."
  [p fields]
  (case p
    :lido/subject-person (rules/lookup-value :lido/actor-classes (or (:type fields) ""))
    :lido/subject-place  :crm/E53_Place
    :crm/E55_Type))

(defn subject-node
  "The node the rules make of a subject `fields` of record `rid`, read as
   native `p` (a concept, a person, a place): its authority's, else the
   record's own, by its name (lot N7)."
  [rid p {:keys [uri label name] :as fields}]
  (let [k (subject-class p fields)]
    (if uri (model/mint-entity-id k [uri]) (model/mint-entity-id k [(str rid) (or name label)]))))

(defn declared
  "What each rule states, as `palomar.inferences` reads it (ADR 0055): the
   rule's own predicates, each an inference whose reason is the rule's
   `:doc`, but for a name it writes (`P190`), a transcription. Derived from
   the rules, so the registry cannot drift from them (post-audit 8, K5)."
  []
  (into {} (for [{:keys [id doc produce]} all-rules
                 :let [ps (set (for [t (:construct produce) :when (vector? t)] (second t)))]]
             [id {:transcribes (set (filter #{:crm/P190_has_symbolic_content} ps))
                  :infers      (into {} (for [p ps :when (not= p :crm/P190_has_symbolic_content)] [p doc]))}])))

(def compiled
  "`all-rules`, compiled."
  (rules/compile-rules all-rules))

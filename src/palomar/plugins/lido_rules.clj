(ns palomar.plugins.lido-rules
  "Part of the CIDOC CRM projection of a LIDO record written as rules: data a
   museum can read and change, not code (post-audit 8, rules study; ADR 0035).

   Two parts:
   - **who did what in an event**: each actor of an event, carried out
     (`P14`), took part (`P11`), or, in an acquisition, gave up or received
     the title (`P23`, `P22`, proposed: a role's words do not establish who
     held the title, and both imply `P14`);
   - **what a made object shows**: a subject typed as a description or an
     identification, or of no type but named by a vocabulary of images
     (Iconclass), is what it depicts (`P62`); any other, an interpretation or
     a subject of no type in another vocabulary, is what the visual item it
     shows is about (`P65` → `E36` → `P129`), never said to be depicted.

   An actor is its occurrence: the record, the event and its position there,
   unless an authority URI names it. Two actors of one name are two.

   The words that decide (roles, event and subject types) are vocabularies,
   data under `resources/palomar/vocab/lido`, not regexes in code. The DSL has
   no disjunction and no conditional term, so a rule is written once per
   branch; `rules` generates them from the tables below, and each generated
   rule is still data (`rules` can be printed, read and diffed)."
  (:require [palomar.rules :as rules]))

;; ---------------------------------------------------------------------------
;; Events: their CRM class, by kind, else by type
;; ---------------------------------------------------------------------------

(def ^:private kind-classes
  "The events whose class their kind says, and whether they are activities."
  [["production"   :crm/E12_Production]
   ["acquisition"  :crm/E8_Acquisition]
   ["modification" :crm/E11_Modification]
   ["design"       :crm/E65_Creation]])

(def ^:private fixed-kinds (set (map first kind-classes)))

(def ^:private other-kind
  "An event of none of the kinds that say its class."
  (vec (for [k fixed-kinds] (list 'not= '?kind k))))

(def ^:private event-branches
  "Each way an event gets its class: `[label class activity? match]`. An event
   of no fixed kind is an activity by its type URI or code, its term, a
   technique it used, or what it precedes; else an `E5_Event`. Each disjunct
   is its own branch, the later ones excluding the earlier."
  (let [by-type  '(vocab-some? :lido/event-types ?type :event/activity)
        by-label '(vocab-some? :lido/event-types ?label :event/activity)
        prior-t  '(vocab-some? :lido/event-types ?type :event/prior)
        prior-l  '(vocab-some? :lido/event-types ?label :event/prior)
        tech     '([?r :lido/material ?m] (field ?m :event ?n) (field ?m :role "technique"))
        no       (fn [& cs] (list* 'not cs))
        typed    '[(field ?ev :type ?type)]
        labelled '[(field ?ev :label ?label)]]
    (concat
     (for [[k c] kind-classes] [k c true [(list 'field '?ev :kind k)]])
     [["activity-by-type"  :crm/E7_Activity true (concat other-kind typed [by-type])]
      ["prior-by-type"     :crm/E7_Activity true (concat other-kind typed [prior-t (no '(field ?ev :type ?type) by-type)])]
      ["activity-by-label" :crm/E7_Activity true (concat other-kind labelled [by-label
                                                                              (no '(field ?ev :type ?type) by-type)
                                                                              (no '(field ?ev :type ?type) prior-t)])]
      ["prior-by-label"    :crm/E7_Activity true (concat other-kind labelled [prior-l
                                                                              (no '(field ?ev :label ?label) by-label)
                                                                              (no '(field ?ev :type ?type) by-type)
                                                                              (no '(field ?ev :type ?type) prior-t)])]
      ["activity-by-technique" :crm/E7_Activity true
       (concat other-kind tech
               [(no '(field ?ev :type ?type) by-type) (no '(field ?ev :type ?type) prior-t)
                (no '(field ?ev :label ?label) by-label) (no '(field ?ev :label ?label) prior-l)])]
      ["event" :crm/E5_Event false
       (concat other-kind
               [(no '(field ?ev :type ?type) by-type) (no '(field ?ev :type ?type) prior-t)
                (no '(field ?ev :label ?label) by-label) (no '(field ?ev :label ?label) prior-l)
                (apply list 'not tech)])]])))

;; ---------------------------------------------------------------------------
;; Actors: who they are, and what they did
;; ---------------------------------------------------------------------------

(def ^:private identities
  "An actor named by an authority is that node; any other is its occurrence."
  [["authority"  '[(field ?a :uri ?u)] '?u]
   ["occurrence" '[(not (field ?a :uri _))] '(str ?r "|" ?n "|" ?pos)]])

(def ^:private agent-kinds
  [["person" :crm/E21_Person '[(not (field ?a :type ?at) (vocab-some? :lido/actor-types ?at :actor/group))]]
   ["group"  :crm/E74_Group  '[(field ?a :type ?at) (vocab-some? :lido/actor-types ?at :actor/group)]]])

(def ^:private passive '(vocab-all? :lido/actor-roles ?role :role/passive))
(def ^:private gives   '(vocab-some? :lido/actor-roles ?role :role/gives-title))
(def ^:private gets    '(vocab-some? :lido/actor-roles ?role :role/receives-title))

(defn- doings
  "What an actor of an event of class `activity?` and kind `k` did:
   `[label match [[predicate status]…]]`."
  [activity? k]
  (let [role '(field ?a :role ?role)]
    (if-not activity?
      [["participant" [] [[:crm/P11_had_participant :asserted]]]]
      (concat
       [["passive" [role passive] [[:crm/P11_had_participant :asserted]]]]
       (when (= k "acquisition")
         [["gives" [role gives (list 'not role gets) (list 'not role passive)]
           [[:crm/P11_had_participant :asserted] [:crm/P23_transferred_title_from :proposed]]]
          ["gets" [role gets (list 'not role gives) (list 'not role passive)]
           [[:crm/P11_had_participant :asserted] [:crm/P22_transferred_title_to :proposed]]]])
       [["carried-out" (concat [(list 'not role passive)]
                               (when (= k "acquisition")
                                 [(list 'not role gives (list 'not role gets))
                                  (list 'not role gets (list 'not role gives))]))
         [[:crm/P14_carried_out_by :asserted]]]]))))

(def actor-rules
  "One rule per event branch, identity, kind of agent and doing."
  (vec
   (for [[elabel eclass activity? ematch] event-branches
         [ilabel imatch ikey]              identities
         [klabel kclass kmatch]            agent-kinds
         [dlabel dmatch claims]            (doings activity? (some #(when (= [(list 'field '?ev :kind %)] ematch) %) fixed-kinds))
         :let [event (list 'mint eclass '(str ?r) '?n)
               agent (list 'mint kclass ikey)]]
     {:id      (keyword "rule.lido.actor" (str elabel "-" ilabel "-" klabel "-" dlabel))
      :phase   :infer
      :doc     (str "An actor of an event (" elabel "), " ilabel ", a " klabel ": " dlabel ".")
      :match   (vec (concat '[[?r :lido/event ?ev] (field ?ev :index ?n) (field ?ev :kind ?kind)]
                            ematch
                            '[[?r :lido/actor ?a] (field ?a :event ?n) (field ?a :name ?name) (field ?a :position ?pos)]
                            imatch kmatch dmatch))
      :produce {:construct (vec (concat
                                 (for [[p status] claims] [event p agent {:status status}])
                                 [[agent :crm/P190_has_symbolic_content '?name {:status :asserted}]]))}})))

;; ---------------------------------------------------------------------------
;; Subjects: depicted, or meant
;; ---------------------------------------------------------------------------

(def ^:private made
  "The record says its object was made: a production, or an artefact's type
   and no natural one. Each is a test (`(not (not …))`: some row exists),
   which binds nothing and so multiplies no row."
  [["produced" '[(not (not [?r :lido/event ?pe] (field ?pe :kind "production")))]]
   ["artefact" '[(not [?r :lido/event ?pe] (field ?pe :kind "production"))
                 (not (not [?r :lido/work-type ?at] (vocab-some? :lido/object-types ?at :object/artefact))
                      (not [?r :lido/classification ?ac] (vocab-some? :lido/object-types ?ac :object/artefact)))
                 (not [?r :lido/work-type ?nt] (vocab-some? :lido/object-types ?nt :object/natural))
                 (not [?r :lido/classification ?nc] (vocab-some? :lido/object-types ?nc :object/natural))]]])

(def ^:private concept-ids
  [["authority" '[(field ?c :uri ?cu)] '(mint :crm/E55_Type ?cu)]
   ["local"     '[(not (field ?c :uri _)) (field ?c :label ?cl)] '(mint :crm/E55_Type (str ?r) ?cl)]])

(def subject-rules
  (vec
   (for [[mlabel mmatch]      made
         [clabel cmatch ctype] concept-ids
         [slabel smatch]      [["depicted" '[(field ?c :subject-type ?st) (vocab-all? :lido/subject-types ?st :subject/ofness)]]
                               ;; no type, but a vocabulary of images: depicted
                               ["shown"    '[(not (field ?c :subject-type _))
                                             (field ?c :uri ?cs) (vocab-some? :lido/subject-schemes ?cs :subject/ofness)]]
                               ["meant"    '[(not (field ?c :subject-type ?st) (vocab-all? :lido/subject-types ?st :subject/ofness))
                                             (not (not (field ?c :subject-type _))
                                                  (field ?c :uri ?cs) (vocab-some? :lido/subject-schemes ?cs :subject/ofness))]]]
         :let [obj '(mint :lido/object (str ?r))
               vi  '(mint :crm/E36_Visual_Item (str ?r))]]
     {:id      (keyword "rule.lido.subject" (str slabel "-" clabel "-" mlabel))
      :phase   :infer
      :doc     (str "A subject concept (" clabel ") of a made object (" mlabel "): " slabel ".")
      :match   (vec (concat '[[?r :lido/subject-concept ?c] (optional (field ?c :label ?cl))] mmatch cmatch smatch))
      :produce {:construct (vec (concat
                                 (if (#{"depicted" "shown"} slabel)
                                   [[obj :crm/P62_depicts ctype {:status :asserted}]]
                                   [[obj :crm/P65_shows_visual_item vi {:status :asserted}]
                                    [vi :crm/P129_is_about ctype {:status :asserted}]])
                                 [[ctype :crm/P190_has_symbolic_content '?cl {:status :asserted}]]))}})))

(def all-rules (into actor-rules subject-rules))

(def compiled
  "`all-rules`, compiled."
  (rules/compile-rules all-rules))

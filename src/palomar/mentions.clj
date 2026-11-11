(ns palomar.mentions
  "Agent mentions, one per occurrence, whatever the source format (lot N1,
   ADR 0058): what a record says of each agent it names, read from that
   occurrence alone — never from another field, another event, or the
   record's canonical floor.

   **A mention** is

     {:id       [record-id field n] ; the record, the field, its occurrence
      :record   record-id
      :local    node-id             ; the record's local agent node (`local-agent`)
      :type     :agent | :subject | :provenance | :described
      :name     \"Dumas, Alexandre\" ; the heading, trimmed
      :role     \"0070\"             ; the first role, verbatim (code or term)
      :roles    [\"0070\" ..]        ; every one, in source order
      :relator  \"0070\"             ; the first relator code, when there is one
      :relators [\"0070\" ..]        ; every one, in source order
      :kind     :person | :group | :family   ; when the source states it
      :responsibility :creator | :contributor | :performer | :collaborator
                | :publisher | :distributor | :producer | :manufacturer
                | :label | :participant
      :dates    profile             ; `agent-dates/parse-life-dates` of the text given
      :events   [typed event ..]    ; `palomar.agent-events`, typed for this occurrence
      :source   {:format :field :occurrence :evidence ..}}

   a key absent when the source does not say. `:dates` is the parsed
   profile of the life-dates text the occurrence gives, its `:text` that
   text verbatim (trimmed): what `reconcile/agent-candidates` reads; a
   group has none (a MARC group's `$d` dates a meeting; a LIDO corporation's
   vital dates are no life), nor has a LIDO actor whose dates read as none.
   A mention is a plain map: it can be passed as is to
   `reconcile/agent-candidates`, which reads its `:name`, `:dates`,
   `:record`, `:events` and `:kind` (and, with anchors, its `:id` and
   `:role`).

   **The local node** is `local-agent`'s, the one rule
   `reconcile/align-agents` uses too: for a MARC field, DC or MODS, the
   record and the name (ADR 0041), of the class the mention's kind states,
   else the authority's entity type, else a Person; for a LIDO actor, the
   rules' and Linked Art's node (`lido-rules/agent-node`); for EAC-CPF, the
   projection's agent (`eac/agent-node`). A mention's `:local` is computed
   with no authority: where it states no kind (DC, MODS, a LIDO actor of no
   type), an authority typed a corporate body makes `align-agents` mint the
   Group node instead. `align-agents` reaches a record's agents only through
   the canonical floor's names: an INTERMARC 7XX, 110 or 710 heading, a
   UNIMARC `$a, $b` heading, none of which the floor carries, gets no
   alignment. The probabilistic engine (`palomar.reconciliation`, lot N2)
   reads the mentions, so it reaches them; `align-agents` does not yet.

   **Per occurrence, never mixed.** A MARC-family mention reads the field
   fragment the importer keeps (`:locator {:tag :occurrence :subfields}`,
   ADR 0011): its name, dates and roles are those *of that field*, the
   first of a repeated name or dates subfield (a repeated `$a` gives its
   first). Two 700s never share a subfield, and a subfield missing from one
   is missing from its mention. The canonical floor is not read.

   **Sources** (`field-specs`, `heading-specs`):
   - INTERMARC (B), as the BnF's zone sheets and function tables define
     them (see `field-specs`): persons 100, 101, 700–702, 720–722, 725–727
     (`$a, $m`, `$d`, `$4`; a family when the second indicator is 5),
     groups 110, 111, 710–712, 723 (a label, inferred from the data),
     730–732, 735–737 (`$a`, `$4`);
   - MARC21: persons 100, 700 (a family when the first indicator is 3)
     (`$a`, `$d`, the roles `$e` else `$4`, the relators `$4`; `$b $c $q`
     under `:name-parts`), groups 110, 710 (`$e`) and meetings 111, 711
     (`$j`);
   - UNIMARC: persons 700–702 (`$a, $b`, `$f`, `$4`), groups 710–712,
     families 720–722; 7X0/7X1 the primary and alternative responsibility,
     7X2 the secondary;
   - LIDO: each `actorInRole` of each event (its name, its type as kind,
     its role terms, the role's concept IRI as relator, its vital dates,
     its authority ids, the one event it is named in, typed, ADR 0056 §3);
   - EAC-CPF, and a MARC-family authority record (INTERMARC, UNIMARC: its
     `type`; MARC21: leader 06 `z`): the described entity (`:type
     :described`), its heading (INTERMARC/MARC21 100, 110, 111; UNIMARC 200,
     210, 220) and nothing else: its variants, related headings and 6XX are
     no mentions;
   - Dublin Core: each `dc:creator`, `dc:contributor`, `dc:publisher`, its
     element the role; MODS: each `name` (its joined `namePart`s; the
     importer does not read `role`, so no role).

   **Not agents of the work, left out by default.** Subjects (MARC21
   600/610/611, INTERMARC 600, UNIMARC 600–602) are typed `:subject`
   (`{:subjects? true}`); provenance — a relation to a copy, a former
   owner, a donor, a binder: INTERMARC 703 and any INTERMARC agent field
   whose first function code is a copy's (4010–4990), UNIMARC 703/713/723 —
   is typed `:provenance` (`{:provenance? true}`). Neither carries an event:
   an edition is not its subject's act, nor its owner's. LIDO's
   `subjectActor` is not read at all in this lot.

   **Events** (lot M7): a MARC-family `:agent` mention carries its record's
   edition events, typed for its role (`agent-events/edition-events`); a
   LIDO actor the one event it is named in (`agent-events/lido-kind`,
   `lido-date`); every other mention none.

   **Ids are positional.** An id is the record's id, the field (a MARC tag,
   LIDO `\"actorInRole\"` with the event and the position, `\"dc:creator\"`…)
   and the occurrence's index among the record's fields of that tag (DC,
   MODS: among its values of that element, out-of-force ones counted, so
   that curation renumbers none). It depends on no other record nor on the
   order records are read in; an inserted field shifts the indices of the
   later fields of its tag, as an edited source would."
  (:require [clojure.string :as str]
            [palomar.agent-dates :as dates]
            [palomar.agent-events :as ae]
            [palomar.model :as model]
            [palomar.plugins.eac :as eac]
            [palomar.plugins.lido-rules :as lido-rules]
            [palomar.plugins.marcxml :as marcxml]
            [palomar.rules :as rules]))

(defn- not-blank [s] (some-> s str/trim not-empty))

(def types
  "What a mention is of the record: an agent of the resource it describes
   (an author, a performer, a publisher, an actor of an event), its
   subject, a relation to a copy of it (a former owner: `:provenance`), or
   the entity an authority record describes."
  #{:agent :subject :provenance :described})

;; ---------------------------------------------------------------------------
;; The local node: one rule, shared with `reconcile/align-agents`
;; ---------------------------------------------------------------------------

(declare source-format lido-actors)

(defn node-class
  "The CRM class of a local agent node: the mention's stated kind (a Group
   for a group or a family, a Person for a person); when it states none,
   the authority's `entity-type` (a Group for a corporate body or a
   family); else a Person."
  [kind entity-type]
  (cond (#{:group :family} kind)                    :crm/E74_Group
        (= :person kind)                            :crm/E21_Person
        (#{"corporateBody" "family"} entity-type)   :crm/E74_Group
        :else                                       :crm/E21_Person))

(defn local-agent
  "The local agent node of the name `nm` on `record`, as `{:id :kind}`: the
   one rule `palomar.mentions` (`:local`) and `reconcile/align-agents` share.
   - a LIDO record: the node the rules and Linked Art make of the actor of
     that name (`lido-rules/agent-node`: its authority URI's, else its
     occurrence's), of the class its type names; the actor is the mention's
     (`:mention`, its id) when given, else the record's actors of that name
     when they make one node; nil when they make several (which one, the
     name does not say);
   - an EAC-CPF record: the projection's agent (`eac/agent-node`);
   - any other: the record and the name (ADR 0041), of `node-class` of the
     mention's `:kind` and the authority's `:entity-type`."
  [record nm {:keys [kind entity-type mention]}]
  (case (source-format record)
    :lido    (let [fs    (filter #(and (= (some-> (:name %) str/trim) (some-> nm str/trim))
                                       (or (nil? mention) (= mention [(:id record) "actorInRole" (:event %) (:position %)])))
                                 (lido-actors record))
                   nodes (distinct (for [f fs]
                                     {:id   (lido-rules/agent-node (:id record) f)
                                      :kind (rules/lookup-value :lido/actor-classes (or (:type f) ""))}))]
               (when (= 1 (count nodes)) (first nodes)))
    :eac-cpf {:id (eac/agent-node record) :kind (eac/agent-class record)}
    (let [k (node-class kind entity-type)]
      {:id (model/mint-entity-id k [(:id record) nm]) :kind k})))

(defn local-node
  "The id of `local-agent`."
  ([record nm] (local-node record nm {}))
  ([record nm opts] (:id (local-agent record nm opts))))

;; ---------------------------------------------------------------------------
;; MARC family
;; ---------------------------------------------------------------------------

(defn- spec [kind resp & {:as m}] (merge {:kind kind :responsibility resp :type :agent} m))

(def field-specs
  "Per MARC format and tag, how an occurrence of a bibliographic record is
   read: its `:kind` (`:person-or-family`: a family by the indicator
   `:family-ind`), `:responsibility`, `:type`, the name subfields (`:name`,
   the first of each, joined by \", \"), the life-dates subfield (`:dates`),
   the role subfields (`:role`, the first code present gives `:role` and
   `:roles`), the relator subfield (`:relator`, `:relators`), and the name
   parts kept apart (`:parts`).

   INTERMARC follows the BnF's INTERMARC (B) sheets (version 10.0, March
   2014, zones 700, 701, 702, 710, 711, 712, 720, 721, 722, 730) and its
   function code tables (\"Référentiel : Fonctions\", July 2015, which also
   name 725-727 and 731-737): 70X persons and 71X groups, X00 author, X01
   performer, X02 technical and artistic collaborator; 720/730 commercial
   publisher, 721/731 distributor, 722/732 sound producer, 725/735 producer,
   726/736 audiovisual producer, 727/737 manufacturer; a person field's
   second indicator 5 a family. 703 is a provenance or possession relation
   to a copy (the BnF's 2022 format notes); 723 is read as a label, a group,
   from the data alone (the corpus's 723s are record labels: Toccata
   classics, Webmusic, Decca), no sheet being at hand; 713 is not read."
  (let [im-person (fn [resp & kv] (apply spec :person-or-family resp :family-ind :ind2 :name ["a" "m"] :dates "d"
                                         :role ["4"] :relator "4" kv))
        im-group  (fn [resp & kv] (apply spec :group resp :name ["a"] :role ["4"] :relator "4" kv))
        m21-pers  (fn [resp & kv] (apply spec :person-or-family resp :family-ind :ind1 :name ["a"] :dates "d"
                                         :role ["e" "4"] :relator "4" :parts ["b" "c" "q"] kv))
        m21-group (fn [resp & kv] (apply spec :group resp :name ["a"] :role ["e" "4"] :relator "4" :parts ["b"] kv))
        m21-meet  (fn [resp & kv] (apply spec :group resp :name ["a"] :role ["j" "4"] :relator "4" :parts ["e"] kv))
        um-person (fn [resp & kv] (apply spec :person resp :name ["a" "b"] :dates "f" :role ["4"] :relator "4" kv))
        um-group  (fn [resp & kv] (apply spec :group resp :name ["a"] :role ["4"] :relator "4" kv))
        um-family (fn [resp & kv] (apply spec :family resp :name ["a"] :dates "f" :role ["4"] :relator "4" kv))]
    {:intermarc {"100" (im-person :creator) "101" (im-person :performer)
                 "700" (im-person :contributor) "701" (im-person :performer) "702" (im-person :collaborator)
                 "703" (im-person nil :type :provenance)
                 "720" (im-person :publisher) "721" (im-person :distributor) "722" (im-person :producer)
                 "725" (im-person :producer) "726" (im-person :producer) "727" (im-person :manufacturer)
                 "110" (im-group :creator) "111" (im-group :performer)
                 "710" (im-group :contributor) "711" (im-group :performer) "712" (im-group :collaborator)
                 "723" (im-group :label)
                 "730" (im-group :publisher) "731" (im-group :distributor) "732" (im-group :producer)
                 "735" (im-group :producer) "736" (im-group :producer) "737" (im-group :manufacturer)
                 "600" (im-person nil :type :subject)}
     :marc21    {"100" (m21-pers :creator) "700" (m21-pers :contributor)
                 "110" (m21-group :creator) "710" (m21-group :contributor)
                 "111" (m21-meet :creator) "711" (m21-meet :contributor)
                 "600" (m21-pers nil :type :subject) "610" (m21-group nil :type :subject)
                 "611" (m21-meet nil :type :subject)}
     :unimarc   {"700" (um-person :creator) "701" (um-person :creator) "702" (um-person :contributor)
                 "710" (um-group :creator) "711" (um-group :creator) "712" (um-group :contributor)
                 "720" (um-family :creator) "721" (um-family :creator) "722" (um-family :contributor)
                 ;; provenance or possession, a copy's (the BnF's 2022 notes)
                 "703" (um-person nil :type :provenance) "713" (um-group nil :type :provenance)
                 "723" (um-family nil :type :provenance)
                 "600" (um-person nil :type :subject) "601" (um-group nil :type :subject)
                 "602" (um-family nil :type :subject)}}))

(def heading-specs
  "Per MARC format and tag, the heading of an authority record: the entity
   it describes (`:type :described`). Its other fields (variants, related
   headings, notes, 6XX) are no mentions."
  {:intermarc {"100" (spec :person-or-family nil :family-ind :ind2 :name ["a" "m"] :dates "d" :type :described)
               "110" (spec :group nil :name ["a"] :type :described)}
   :marc21    {"100" (spec :person-or-family nil :family-ind :ind1 :name ["a"] :dates "d" :type :described)
               "110" (spec :group nil :name ["a"] :type :described)
               "111" (spec :group nil :name ["a"] :type :described)}
   :unimarc   {"200" (spec :person nil :name ["a" "b"] :dates "f" :type :described)
               "210" (spec :group nil :name ["a"] :type :described)
               "220" (spec :family nil :name ["a"] :dates "f" :type :described)}})

(defn- copy-function?
  "Whether an INTERMARC function code is a copy's (\"Intervenant sur
   l'exemplaire\", 4010-4990: a former owner, a binder, a donor…)."
  [code]
  (boolean (and code (re-matches #"4\d\d\d" code) (<= 4010 (parse-long code) 4990))))

(defn- all-of
  "Every value of subfield `code` among `sfs` (`[[code value] ..]`), trimmed,
   blanks skipped, in source order."
  [sfs code]
  (vec (keep (fn [[c v]] (when (= code c) (not-blank v))) sfs)))

(defn- first-of
  "The first value of subfield `code` among `sfs`, trimmed; nil when absent
   or blank."
  [sfs code]
  (first (all-of sfs code)))

(defn heading
  "The heading of a field's ordered `[code value]` subfields `sfs`: the
   first of each of the `codes` (a repeated `$a` gives its first), trimmed,
   absent ones skipped, joined by \", \" (INTERMARC `$a, $m`, UNIMARC `$a,
   $b`); nil without any."
  [sfs codes]
  (let [parts (keep #(first-of sfs %) codes)]
    (when (seq parts) (str/join ", " parts))))

(defn- marc-mention
  [record fmt {{:keys [tag occurrence subfields] :as loc} :locator fid :id} sp]
  (let [rid   (:id record)
        nm    (heading subfields (:name sp))
        kind  (if (= :person-or-family (:kind sp))
                (if (= (if (= :ind1 (:family-ind sp)) "3" "5") (some-> (get loc (:family-ind sp)) str/trim)) :family :person)
                (:kind sp))
        rcode (some #(when (seq (all-of subfields %)) %) (:role sp))
        roles (if rcode (all-of subfields rcode) [])
        rels  (if (:relator sp) (all-of subfields (:relator sp)) [])
        role  (first roles)
        rel   (first rels)
        dts   (some->> (:dates sp) (first-of subfields) dates/parse-life-dates)
        parts (into (sorted-map) (keep (fn [c] (when-let [v (first-of subfields c)] [c v]))) (:parts sp))
        typ   (if (and (= :intermarc fmt) (= :agent (:type sp)) (copy-function? rel)) :provenance (:type sp))]
    (when nm
      (cond-> {:id     [rid tag occurrence]
               :record rid
               :local  (local-node record nm {:kind kind})
               :type   typ
               :name   nm
               :kind   kind
               :events (if (= :agent typ) (ae/edition-events record role) [])
               :source {:format     fmt
                        :field      tag
                        :occurrence occurrence
                        :subfields  subfields
                        :evidence   [{:fragment fid
                                      :selector {:type :xpath :value (marcxml/xpath-step "datafield" "tag" tag (inc occurrence))}}]}}
        role                 (assoc :role role :roles roles)
        rel                  (assoc :relator rel :relators rels)
        (and (:responsibility sp) (= :agent typ)) (assoc :responsibility (:responsibility sp))
        dts                  (assoc :dates dts)
        (seq parts)          (assoc :name-parts parts)))))

(defn authority-record?
  "Whether a MARC-family `record` is an authority record: its kind's name
   says so (INTERMARC, UNIMARC: the record's `type`), or its leader's type
   of record (06) is `z` (MARC21)."
  [record]
  (boolean (or (some-> (:kind record) name (str/includes? "authority"))
               (some #(and (= "leader" (some-> (:predicate %) name)) (string? (:value %))
                           (< 6 (count (:value %))) (= \z (nth (:value %) 6)))
                     (:assertions record)))))

(defn- marc-mentions [record fmt]
  (let [auth? (authority-record? record)
        specs (if auth? (heading-specs fmt) (field-specs fmt))
        ms    (for [f (:fragments record)
                    :let [sp (specs (get-in f [:locator :tag]))]
                    :when (and sp (vector? (get-in f [:locator :subfields])))
                    :let [m (marc-mention record fmt f sp)]
                    :when m]
                m)]
    ;; an authority record describes one entity: its (first) heading
    (if auth? (vec (take 1 ms)) (vec ms))))

;; ---------------------------------------------------------------------------
;; LIDO
;; ---------------------------------------------------------------------------

(defn- fields [a] (when (model/structured-value? (:value a)) (:value/fields (:value a))))

(defn- xpath [a] (some->> (model/evidence-of a) first :selector :value))

(defn- lido-kind
  "The kind an actor's type states: a person, a family, a group; nil when
   it states none."
  [t]
  (when-let [t (not-blank t)]
    (cond (re-find #"(?i)^person" t)          :person
          (re-find #"(?i)famil" t)            :family
          (ae/group? {:type t})               :group)))

(defn- lido-actors
  "The fields of `record`'s in-force LIDO actors that have a name."
  [record]
  (for [a (remove model/out-of-force? (:assertions record))
        :when (= :lido/actor (:predicate a))
        :let [f (fields a)]
        :when (not-blank (:name f))]
    f))

(defn- lido-mentions
  [record]
  (let [rid    (:id record)
        live   (remove model/out-of-force? (:assertions record))
        evs    (into {} (for [a live :when (= :lido/event (:predicate a)) :let [f (fields a)]]
                          [(:index f) [f a]]))
        id-nat (for [a live :when (and (= :lido/actor-id (:predicate a)) (string? (:value a)))]
                 [(xpath a) (str/trim (:value a))])]
    (vec (for [a live
               :when (= :lido/actor (:predicate a))
               :let [f       (fields a)
                     nm      (not-blank (:name f))]
               :when nm
               :let [[e ea]  (evs (:event f))
                     path    (xpath a)
                     ids     (distinct (keep not-blank (cons (:uri f)
                                                             (for [[p v] id-nat
                                                                   :when (and path p (str/starts-with? p (str path "/"))
                                                                              (re-find #"^[a-z][a-z0-9+.-]*:" v))]
                                                               v))))
                     grp     (ae/group? f)
                     ;; a group has no life; an undated profile is no dates
                     life    (when (and (not grp) (or (not-blank (:birth f)) (not-blank (:death f))))
                               (dates/parse-life-dates (str (or (not-blank (:birth f)) "....") "-"
                                                            (or (not-blank (:death f)) "...."))))
                     date    (when e (ae/lido-date e))
                     kind    (lido-kind (:type f))
                     typed   (when e
                               (cond-> {:kind   (ae/lido-kind e f)
                                        :role   (:responsibility f)
                                        :source {:record   rid
                                                 :event    (:event f)
                                                 :evidence (vec (concat (model/evidence-of ea) (model/evidence-of a)))}}
                                 (:role f)        (assoc :role-terms (:role f))
                                 (:attribution f) (assoc :attribution (:attribution f))
                                 grp              (assoc :group? true)
                                 date             (assoc :date date)))]]
           (cond-> {:id         [rid "actorInRole" (:event f) (:position f)]
                    :record     rid
                    :local      (lido-rules/agent-node rid f)
                    :type       :agent
                    :name       (:name f)
                    :event      (:event f)
                    :position   (:position f)
                    :actor-type (:type f)
                    :ids        (vec ids)
                    :events     (if typed [typed] [])
                    :source     {:format     :lido
                                 :field      "actorInRole"
                                 :event      (:event f)
                                 :occurrence (:position f)
                                 :evidence   (vec (model/evidence-of a))}}
             (not-blank (:role f))     (assoc :role (:role f))
             (not-blank (:role-uri f)) (assoc :relator (:role-uri f) :relators [(:role-uri f)])
             (:responsibility f)       (assoc :responsibility (keyword (:responsibility f)))
             kind                      (assoc :kind kind)
             grp                       (assoc :group? true)
             (dates/dated? life)       (assoc :dates life))))))

;; ---------------------------------------------------------------------------
;; EAC-CPF, Dublin Core, MODS
;; ---------------------------------------------------------------------------

(def ^:private eac-kinds {"person" :person "corporateBody" :group "family" :family})

(defn- eac-mentions [record]
  (let [rid  (:id record)
        live (remove model/out-of-force? (:assertions record))
        of   (fn [p] (first (filter #(and (= p (:predicate %)) (string? (:value %))) live)))
        nm   (of :eac/authorized-name)
        kind (some-> (of :eac/entity-type) :value str/trim eac-kinds)
        eid  (some-> (of :eac/entity-id) :value not-blank)
        dts  (some-> (of :eac/exist-dates) :value dates/parse-life-dates)]
    (if-let [n (some-> nm :value not-blank)]
      [(cond-> {:id     [rid "identity" 0]
                :record rid
                :local  (eac/agent-node record)
                :type   :described
                :name   n
                :events []
                :source {:format :eac-cpf :field "identity" :occurrence 0 :evidence (vec (model/evidence-of nm))}}
         kind (assoc :kind kind)
         dts  (assoc :dates dts)
         (and eid (re-find #"^[a-z][a-z0-9+.-]*:" eid)) (assoc :ids [eid]))]
      [])))

(def ^:private dc-roles
  {:dc/creator "creator" :dc/contributor "contributor" :dc/publisher "publisher"})

(defn- indexed-mentions
  "The mentions of the string assertions of `record` whose predicate is a key
   of `roles` (to the role it states, or nil), each numbered among its
   predicate's in source order, out-of-force ones counted (a curated value
   renumbers no other), then the out-of-force ones left out."
  [record fmt roles]
  (let [rid  (:id record)
        all  (filter #(and (contains? roles (:predicate %)) (string? (:value %))) (:assertions record))]
    (vec (keep
          (fn [[a n]]
            (when-let [nm (and (not (model/out-of-force? a)) (not-blank (:value a)))]
              (let [p    (:predicate a)
                    role (roles p)
                    fld  (str (namespace p) ":" (name p))]
                (cond-> {:id     [rid fld n]
                         :record rid
                         :local  (local-node record nm {})
                         :type   :agent
                         :name   nm
                         :events []
                         :source {:format fmt :field fld :occurrence n :evidence (vec (model/evidence-of a))}}
                  role (assoc :role role :responsibility (keyword role))))))
          (first (reduce (fn [[acc seen] a]
                           (let [n (get seen (:predicate a) 0)]
                             [(conj acc [a n]) (assoc seen (:predicate a) (inc n))]))
                         [[] {}] all))))))

;; ---------------------------------------------------------------------------
;; Any record
;; ---------------------------------------------------------------------------

(def ^:private formats
  {"intermarc" :intermarc "marc21" :marc21 "unimarc" :unimarc "lido" :lido
   "eac" :eac-cpf "dc" :dc "mods" :mods})

(defn source-format
  "The source format of `record`, by its native predicates: `:intermarc`,
   `:marc21`, `:unimarc`, `:lido`, `:eac-cpf`, `:dc`, `:mods`; nil for any
   other."
  [record]
  (some (fn [{p :predicate}] (when (keyword? p) (formats (namespace p)))) (:assertions record)))

(defn mentions
  "The agent mentions of `record`, one per occurrence, in document order
   (see the ns doc). Subjects are left out unless `{:subjects? true}`,
   provenance (a copy's former owner…) unless `{:provenance? true}`. An
   occurrence that names no one is no mention."
  ([record] (mentions record {}))
  ([record {:keys [subjects? provenance?]}]
   (let [fmt (source-format record)
         ms  (case fmt
               (:intermarc :marc21 :unimarc) (marc-mentions record fmt)
               :lido                         (lido-mentions record)
               :eac-cpf                      (eac-mentions record)
               :dc                           (indexed-mentions record :dc dc-roles)
               :mods                         (indexed-mentions record :mods {:mods/name nil})
               [])]
     (filterv #(case (:type %) :subject subjects? :provenance provenance? true) ms))))

(defn batch
  "The mentions of every record of `records`, in order: each record's are
   its own (`mentions`), whatever the others."
  ([records] (batch records {}))
  ([records opts] (vec (mapcat #(mentions % opts) records))))

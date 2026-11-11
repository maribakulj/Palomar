(ns palomar.mentions
  "Agent mentions, one per occurrence, whatever the source format (lot N1,
   ADR 0058): what a record says of each agent it names, read from that
   occurrence alone — never from another field, another event, or the
   record's canonical floor.

   **A mention** is

     {:id       [record-id field n] ; the record, the field, its occurrence
      :record   record-id
      :local    node-id             ; this occurrence's local agent node (`local-agent`)
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
      :ids      [\"…\" ..]           ; the ids the occurrence states (lot N5, `field-ids`)
      :unread-ids [\"…\" ..]         ; the values of id subfields no id form reads
      :authority-record \"…\"        ; the authority the cataloguer linked (`$3`), apart
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
   `reconcile/align-agents` uses too: one local agent per occurrence (lot
   O3, ADR 0063); for a MARC field, DC or MODS, the first occurrence of a
   name keeps the record and the name (ADR 0041), a later one its
   occurrence id (`local-key`), of the class the mention's kind states,
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
            [palomar.agent-equivalence :as eq]
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

(declare occurrences resolve-local)

(defn- by-name
  "The occurrences of `record` (`occurrences`) whose name is `nm`, trimmed."
  [record nm]
  (let [n (some-> nm str/trim)]
    (filter #(= n (:name %)) (occurrences record))))

(defn local-agent
  "The local agent node of the name `nm` on `record`, as `{:id :kind}`: the
   one rule `palomar.mentions` (`:local`) and `reconcile/align-agents` share.
   A local agent is a hypothetical person or group, one per occurrence
   (lot O3, ADR 0063): two occurrences of one name are two local agents,
   never one on the strength of the name.
   - a LIDO record: the node the rules and Linked Art make of the actor of
     that name (`lido-rules/agent-node`: its authority URI's, else its
     occurrence's), of the class its type names; the actor is the mention's
     (`:mention`, its id) when given, else the record's actors of that name
     when they make one node; nil when they make several (which one, the
     name does not say);
   - an EAC-CPF record: the projection's agent (`eac/agent-node`);
   - any other: the occurrence's local agent (`resolve-local`), of
     `node-class` of the mention's `:kind` and the authority's
     `:entity-type`, its id `local-key`'s: a name given once keeps the
     record and the name, a repeated one is keyed by its content. A name
     the record names on its floor but in no occurrence keeps the record
     and the name. Nil when the record gives the name twice or more and no
     valid occurrence id says which.

   A local agent's IRI is no stable identifier across re-imports: an edit
   of the record may orphan it. It must not be published as an identity;
   the identity is what curation links it to (ADR 0063)."
  [record nm {:keys [mention] :as opts}]
  (case (source-format record)
    :lido    (let [fs    (filter #(and (= (some-> (:name %) str/trim) (some-> nm str/trim))
                                       (or (nil? mention) (= mention [(:id record) "actorInRole" (:event %) (:position %)])))
                                 (lido-actors record))
                   nodes (distinct (for [f fs]
                                     {:id   (lido-rules/agent-node (:id record) f)
                                      :kind (rules/lookup-value :lido/actor-classes (or (:type f) ""))}))]
               (when (= 1 (count nodes)) (first nodes)))
    :eac-cpf {:id (eac/agent-node record) :kind (eac/agent-class record)}
    (:local (resolve-local record nm opts))))

(defn resolve-local
  "What `local-agent` finds for the name `nm` on a MARC-family, DC or MODS
   `record`, and why: `{:local {:id :kind}}`, or `{:reason r}` when it
   finds none, `r` one of `:several-occurrences` (the record gives the name
   as an agent twice or more, and no valid occurrence id says which) and
   `:no-name`. A given occurrence id (`:mention`) is taken only when that
   occurrence's name is `nm`, trimmed, and it is an agent's or the
   described entity's (review of lot O3): an id an earlier version of the
   record gave may name another field now. Otherwise the name decides."
  [record nm {:keys [kind entity-type mention]}]
  (let [k      (node-class kind entity-type)
        n      (some-> nm str/trim)
        os     (by-name record nm)
        agents (filter #(#{:agent :described} (:type %)) os)
        given  (when (some? mention)
                 (first (filter #(and (= mention (:id %)) (= n (:name %)) (#{:agent :described} (:type %)))
                                (occurrences record))))
        one    (cond given                      given
                     (= 1 (count agents))       (first agents)
                     (and (empty? agents) (= 1 (count os))) (first os))]
    (cond
      one                    {:local {:id (model/mint-entity-id k (::key one)) :kind k}}
      (and (empty? os) n)    {:local {:id (model/mint-entity-id k [(:id record) nm]) :kind k}}
      (nil? n)               {:reason :no-name}
      :else                  {:reason :several-occurrences})))

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
   `:roles`), the relator subfield (`:relator`, `:relators`), the name
   parts kept apart (`:parts`), the id subfields (`:ids`, lot N5, read by
   `field-ids`) and the authority-control subfield (`:control`, the record
   number of the authority the cataloguer linked: `authority-record`).

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
                                         :role ["4"] :relator "4" :ids ["1"] :control "3" kv))
        im-group  (fn [resp & kv] (apply spec :group resp :name ["a"] :role ["4"] :relator "4" :ids ["1"] :control "3" kv))
        m21-pers  (fn [resp & kv] (apply spec :person-or-family resp :family-ind :ind1 :name ["a"] :dates "d"
                                         :role ["e" "4"] :relator "4" :parts ["b" "c" "q"] :ids ["0" "1"] kv))
        m21-group (fn [resp & kv] (apply spec :group resp :name ["a"] :role ["e" "4"] :relator "4" :parts ["b"] :ids ["0" "1"] kv))
        m21-meet  (fn [resp & kv] (apply spec :group resp :name ["a"] :role ["j" "4"] :relator "4" :parts ["e"] :ids ["0" "1"] kv))
        um-person (fn [resp & kv] (apply spec :person resp :name ["a" "b"] :dates "f" :role ["4"] :relator "4" :ids ["o"] :control "3" kv))
        um-group  (fn [resp & kv] (apply spec :group resp :name ["a"] :role ["4"] :relator "4" :ids ["o"] :control "3" kv))
        um-family (fn [resp & kv] (apply spec :family resp :name ["a"] :dates "f" :role ["4"] :relator "4" :ids ["o"] :control "3" kv))]
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

(def ^:private marc-sources
  "How a MARC21 `$0` `(code)value` is read, by its code, any case: a source
   code (`isni`, `viaf`, `orcid`, `uri`, MARC Source Codes) or an
   organisation code (the GND's `DE-588`, the Library of Congress's `DLC`:
   a name heading as LCNAF, a subject heading as LCSH). A code not here
   (`DE-588a`, an old GND number that names no current record; `OCoLC`) is
   not read: the value is kept as unread."
  {"isni"   (fn [v] (str "ISNI " v))
   "viaf"   (fn [v] (str "http://viaf.org/viaf/" (str/trim v)))
   "orcid"  (fn [v] (str "https://orcid.org/" (str/trim v)))
   "uri"    str/trim
   "de-588" (fn [v] (str "https://d-nb.info/gnd/" (str/trim v)))
   "dlc"    (fn [v] (let [n (str/replace v #"\s" "")]
                      (if (str/starts-with? n "sh")
                        (str "http://id.loc.gov/authorities/subjects/" n)
                        (str "http://id.loc.gov/authorities/names/" n))))})

(defn field-ids
  "The ids a MARC-family field's `sfs` state in the subfields `codes`, in
   source order (lot N5): `{:ids [..] :unread [..]}`. A MARC21 `(code)value`
   is read by its code (`marc-sources`); any other value as given, when it
   is an id by itself (an IRI, an ISNI, an `ISNI…` string). An ISNI whose
   check character is wrong is an id still, so that the identifier lane
   reports it. A value read as neither is `:unread`."
  [sfs codes]
  (let [read (for [c codes
                   v (all-of sfs c)
                   :let [org (re-matches #"\(([^)]+)\)\s*(.+)" v)
                         id  (if org
                               (when-let [f (marc-sources (str/lower-case (second org)))] (f (nth org 2)))
                               (when (eq/identifier v) v))]]
               [v id])]
    {:ids    (vec (distinct (keep second read)))
     :unread (vec (distinct (for [[v id] read :when (nil? id)] v)))}))

(defn- bnf-record?
  "Whether a MARC-family `record` is the BnF's: its `001` an `FRBNF`
   number, its `003` a BnF ARK, or an `801 $b` the BnF's ISIL."
  [record]
  (boolean (some (fn [{{:keys [tag value subfields]} :locator}]
                   (case tag
                     "001" (some-> value (str/starts-with? "FRBNF"))
                     "003" (some-> value (str/starts-with? "http://catalogue.bnf.fr/ark:/12148/"))
                     "801" (some #(= ["b" "FR-751131015"] %) subfields)
                     nil))
                 (:fragments record))))

(defn authority-record
  "The authority record a field's control subfield links (lot N5 review):
   INTERMARC `$3`, and UNIMARC `$3` in a BnF record (`bnf-record?`), the
   BnF record number, as that record's ARK (`agent-equivalence/bnf-ark`).
   It is the link the cataloguer already made, not an id to reconcile:
   never an `:ids`, never read by a lane. Nil when the field has none, or
   when the record does not say whose number it is."
  [record fmt sfs code]
  (when-let [v (and code (first-of sfs code))]
    (when (or (= :intermarc fmt) (bnf-record? record))
      (eq/bnf-ark v))))

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
        ids   (field-ids subfields (:ids sp))
        arec  (authority-record record fmt subfields (:control sp))
        typ   (if (and (= :intermarc fmt) (= :agent (:type sp)) (copy-function? rel)) :provenance (:type sp))]
    (when nm
      (cond-> {:id     [rid tag occurrence]
               :record rid
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
        (seq parts)          (assoc :name-parts parts)
        (seq (:ids ids))     (assoc :ids (:ids ids))
        (seq (:unread ids))  (assoc :unread-ids (:unread ids))
        arec                 (assoc :authority-record arec)))))

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
        vals (distinct (keep identity (cons eid (keep #(when (= :eac/other-record-id (:predicate %)) (some-> (:value %) not-blank))
                                                      live))))
        ids  (vec (filter eq/identifier vals))
        unread (vec (remove eq/identifier vals))
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
         (seq ids)    (assoc :ids ids)
         (seq unread) (assoc :unread-ids unread))]
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

(defn- raw-mentions
  "Every occurrence of `record`, subjects and provenance included, in
   document order, before their local agents are named."
  [record]
  (let [fmt (source-format record)]
    (case fmt
      (:intermarc :marc21 :unimarc) (marc-mentions record fmt)
      :lido                         (lido-mentions record)
      :eac-cpf                      (eac-mentions record)
      :dc                           (indexed-mentions record :dc dc-roles)
      :mods                         (indexed-mentions record :mods {:mods/name nil})
      [])))

(defn- content-of
  "What an occurrence states besides its name: its field, its relators, its
   life-dates text and its name parts."
  [m]
  [(get-in m [:source :field]) (vec (:relators m)) (or (get-in m [:dates :text]) "")
   (vec (sort (:name-parts m)))])

(defn local-key
  "The key the local agent of each of `ms` (one record's occurrences, in
   document order) is minted from (lot O3, and its review). A name and
   class (`node-class` of its kind) the record gives once keeps `[record-id
   name]` (ADR 0041), the id it always had. A name and class it gives more
   than once, subjects and provenance included: each occurrence is keyed by
   its content, `[record-id name field relators dates-text name-parts n]`,
   `n` an ordinal only among occurrences of exactly that content, in
   document order. So inserting, removing or moving another field changes
   no other occurrence's key, and an edit of an occurrence orphans its old
   id instead of giving it to another occurrence. Returns `ms` with the key
   under `::key`."
  [ms]
  (let [group (fn [m] [(node-class (:kind m) nil) (:name m)])
        sizes (frequencies (map group ms))]
    (first (reduce (fn [[acc seen] m]
                     (if (= 1 (sizes (group m)))
                       [(conj acc (assoc m ::key [(:record m) (:name m)])) seen]
                       (let [c (into [(:record m) (:name m)] (content-of m))
                             i (get seen [(group m) c] 0)]
                         [(conj acc (assoc m ::key (conj c i))) (assoc seen [(group m) c] (inc i))])))
                   [[] {}] ms))))

(defn- occurrences
  "Every occurrence of `record` with its local agent (`:local`) and the key
   it is minted from (`::key`)."
  [record]
  (case (source-format record)
    (:lido :eac-cpf) (raw-mentions record)
    (mapv #(assoc % :local (model/mint-entity-id (node-class (:kind %) nil) (::key %)))
          (local-key (raw-mentions record)))))

(defn mentions
  "The agent mentions of `record`, one per occurrence, in document order
   (see the ns doc). Subjects are left out unless `{:subjects? true}`,
   provenance (a copy's former owner…) unless `{:provenance? true}`. An
   occurrence that names no one is no mention."
  ([record] (mentions record {}))
  ([record {:keys [subjects? provenance?]}]
   (let [ms (map #(dissoc % ::key) (occurrences record))]
     (filterv #(case (:type %) :subject subjects? :provenance provenance? true) ms))))

(defn batch
  "The mentions of every record of `records`, in order: each record's are
   its own (`mentions`), whatever the others."
  ([records] (batch records {}))
  ([records opts] (vec (mapcat #(mentions % opts) records))))

;; ---------------------------------------------------------------------------
;; Two occurrences of one name in one record: a proposal, never a fact
;; ---------------------------------------------------------------------------

(defn- date-text
  "An occurrence's life-dates text, its trailing punctuation dropped, or nil."
  [m]
  (some-> (get-in m [:dates :text]) (str/replace #"[\s.,;:]+$" "") str/trim not-empty))

(defn- dates-verdict
  "Whether two occurrences' life dates can be one life (review of lot O3):
   their parsed profiles compared by `agent-dates/compatibility`.
   `:incompatible` only when they cannot overlap (two births years apart:
   two persons); `:compatible` when they can (\"1802-1870\", \"1802 -
   1870\", \"1802–1870\", \"18..-1870\"); `:unknown` when one gives
   none."
  [x y]
  (let [a (:dates x) b (:dates y)]
    (if (and (dates/dated? a) (dates/dated? b))
      (case (:verdict (dates/compatibility a b))
        :incompatible :incompatible
        :compatible   :compatible
        :unknown)
      :unknown)))

(def marc-relator-terms
  "MARC21 relator terms (`$e`, the Library of Congress's MARC Code List for
   Relators) to their codes, for the terms Palomar reads; lowercased, final
   punctuation dropped. A term not here is a free term: it never
   conflicts."
  {"actor" "act" "adapter" "adp" "annotator" "ann" "arranger" "arr" "artist" "art" "author" "aut"
   "author of introduction" "aui" "binder" "bnd" "book designer" "bkd" "calligrapher" "cll"
   "cartographer" "ctg" "cinematographer" "cng" "collector" "col" "colorist" "clr" "commentator" "cmm"
   "compiler" "com" "composer" "cmp" "conductor" "cnd" "contributor" "ctb" "copyright holder" "cph"
   "costume designer" "cst" "curator" "cur" "dedicatee" "dte" "designer" "dsr" "director" "drt"
   "distributor" "dst" "donor" "dnr" "draftsman" "drm" "editor" "edt" "engraver" "egr" "etcher" "etr"
   "former owner" "fmo" "founder" "fnd" "honoree" "hnr" "host" "hst" "illuminator" "ilu" "illustrator" "ill"
   "instrumentalist" "itr" "interviewee" "ive" "interviewer" "ivr" "librettist" "lbt" "lithographer" "ltg"
   "lyricist" "lyr" "narrator" "nrt" "organizer" "orm" "painter" "pnt" "performer" "prf" "photographer" "pht"
   "printer" "prt" "printmaker" "prm" "producer" "pro" "publisher" "pbl" "reviewer" "rev" "screenwriter" "aus"
   "sculptor" "scl" "singer" "sng" "speaker" "spk" "translator" "trl" "transcriber" "trc" "writer of preface" "wpr"})

(defn- relator-set
  "The relators an occurrence states: its relator codes, and, for MARC21,
   its `$e` terms that `marc-relator-terms` holds."
  [m]
  (into (set (:relators m))
        (when (= :marc21 (get-in m [:source :format]))
          (keep #(marc-relator-terms (some-> % str/lower-case (str/replace #"[\s.,;:]+$" "") str/trim)) (:roles m)))))

(defn- field-of
  "The field roles are compared within: a MARC tag, a DC element; for LIDO,
   the event (two actors of one event are two actors of one act)."
  [m]
  (if (= :lido (get-in m [:source :format])) [:event (:event m)] (get-in m [:source :field])))

(defn- part-conflict?
  "Whether two occurrences state one name part (`$b`, `$c`, `$q`) with two
   values: \"Dumas, Alexandre, $c père\" and \"$c fils\" are two
   persons. A part only one states is no conflict."
  [x y]
  (let [n #(some-> % str/lower-case (str/replace #"[\s.,;:()]+$" "") (str/replace #"^[\s(]+" "") str/trim)
        a (:name-parts x) b (:name-parts y)]
    (boolean (some (fn [[k v]] (and (contains? b k) (not= (n v) (n (get b k))))) a))))

(defn- roles-verdict
  "`:incompatible` when two occurrences state different name parts
   (`part-conflict?`), or when, in one field (`field-of`: one MARC tag; one
   LIDO event), both state relators (`relator-set`: codes, role concept
   IRIs, MARC21 `$e` terms the table holds), their sets differ, and they do
   not both give the same life dates. Within one field, a record that
   names one agent twice with two functions gives the same heading twice,
   dates included (the BnF's 700s: \"Bernard, Roland\", 1927-2019, 0610
   and 0570); one undated heading twice with two functions is all a record
   has to tell two homonyms apart, so it is read as two. Else
   `:compatible`. Across fields (a 100 and a 700; INTERMARC's 723, 730,
   731, 732; two LIDO events) one agent's functions are split by the
   format, so their relators say nothing against one agent. A role that is
   no relator (a Dublin Core element, a free term) never conflicts."
  [x y]
  (let [a (relator-set x) b (relator-set y)
        d (date-text x)]
    (if (or (part-conflict? x y)
            (and (= (field-of x) (field-of y))
                 (seq a) (seq b) (not= a b)
                 (not (and d (= d (date-text y))))))
      :incompatible
      :compatible)))

(defn same-agent-proposals
  "The proposals that two occurrences of `record` are one agent (lot O3,
   ADR 0058 amendment): every pair of its agent occurrences (`:type
   :agent`) with the same name, exactly, two local agents of one class,
   whose roles and dates are compatible (`roles-verdict`, `dates-verdict`):
   life dates that can overlap, no two values of one name part, and, in one
   field, no two different relator sets unless both give the same dates.
   Each is `{:a :b :record :name :kind :occurrences [id id] :evidence
   {:same-record true :same-name true :roles :relators :name-parts :dates
   :date-texts}}`,
   `:a` the earlier occurrence's local agent. It is a proposal, never a
   fact: nothing joins the two until a curator accepts it. Subjects,
   provenance and described entities are not proposed."
  [record]
  (let [v (filterv #(= :agent (:type %)) (mentions record))]
    (vec (for [i (range (count v)) j (range (inc i) (count v))
               :let [x (v i) y (v j)]
               :when (and (= (:name x) (:name y)) (:local x) (:local y) (not= (:local x) (:local y))
                          (= (node-class (:kind x) nil) (node-class (:kind y) nil)))
               :let [roles (roles-verdict x y) dts (dates-verdict x y)]
               :when (and (not= :incompatible roles) (not= :incompatible dts))]
           {:a (:local x) :b (:local y) :record (:id record) :name (:name x)
            :kind (node-class (:kind x) nil)
            :occurrences [(:id x) (:id y)]
            :evidence {:same-record true :same-name true
                       :roles roles :relators [(vec (sort (relator-set x))) (vec (sort (relator-set y)))]
                       :name-parts [(or (:name-parts x) {}) (or (:name-parts y) {})]
                       :dates dts :date-texts [(get-in x [:dates :text]) (get-in y [:dates :text])]}}))))

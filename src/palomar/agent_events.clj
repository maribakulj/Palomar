(ns palomar.agent-events
  "Typed events as contrary evidence on a person's identity (lot M7, ADR 0018
   amendment M7): what a record says a person did, and when, typed by what
   the source says it was, and whether it can be the life of an authority.

   **A typed event** is `{:kind :role :date :source}`, `:date` an interval of
   years (`palomar.agent-dates`, absent when not read) and `:source` what it
   was read from (`:record`, the field or event, the evidence). `:kind` is one
   of `kinds`; only a kind `constraints` names says anything of a life.

   **Where the kinds come from.** Palomar already types both sources' events;
   this namespace reads those types, it does not decide them again:
   - **LIDO** (`lido-mentions`): the importer's `:lido/event` (its `:kind`,
     `palomar.plugins.lido/event-kinds`) and `:lido/actor` (its
     `:responsibility`, creator or participant, by `creator?` and the
     attribution qualifier, ADR 0054, K2). A production or a design an actor
     carried out as its maker is a `:creation`; an actor it was made
     \"after\" an `:influence` (no activity of theirs); a former, related or
     uncertain attribution, or a later making (a posthumous cast or
     impression, a restrike, a replica, a reproduction, a version, a
     forgery), an `:attribution` (not the actor's act); any other actor of it
     (a printer, a publisher, a colourist, a caster, the author of a text)
     an `:activity`. A group (a corporation, an institution, a family, a
     manufacturer) has no life: its events are flagged `:group?`.
     Another event is an `:acquisition` by its kind, an `:exhibition` or a
     `:publication` (about the object) by the event-types vocabulary
     (`resources/palomar/vocab/lido/event-types.edn`, `:event/exhibition`,
     `:event/publication`), else an `:activity`. An actor is its occurrence
     (ADR 0056 §3): a mention carries the one event it is named in.
   - **MARC21, INTERMARC, UNIMARC** (`edition-events`): the fields
     `palomar.plugins.events/field-events` says state a publication or a
     production of the Manifestation (260/264 `$c`, INTERMARC 260 `$d`,
     UNIMARC 210/214 `$d`), and MARC21 008 Date 1 for a published
     resource. Each is a `:publication`: the edition's date, never the
     work's. No importer reads a work's creation date (046, F28) yet, so no
     bibliographic record gives a creation.

   **The constraints** (`compatibility`), conservative: a false block is
   worse than a missed one.
   - A `:creation` cannot end more than `birth-margin` (10) years before the
     birth of who it names. An approximate date is widened by
     `agent-dates/margin` (20). **The rule is one-sided**: a creation after
     death is never evidence. Museums catalogue posthumous making under the
     artist's name, as the production's maker — a Rodin bronze cast in
     1925, a Rembrandt impression pulled in 1906, a Thonet chair reissued in
     1985, a 19th-century copy under Raphael — and no field reliably says
     so. Making before one's birth has no such reading.
   - An event that contradicts the mention's own stated life
     (`consistent-events`) is dropped: the record contradicts itself.
   - A group's event constrains nothing.
   - Nothing else constrains: a publication, an exhibition, an acquisition
     after death is common (posthumous); an edition's date is no creation's
     (a 1990 Hugo is no evidence against 1802-1885), even before a birth (a
     facsimile, a reproduction's date); an influence (\"after X\") is no
     activity of X; an `:activity` constrains nothing either (a text printed
     after its author's death, a firm publishing under a dead founder's
     name, an heir in an acquisition).
   - A floruit bounds no life; an undated event, an undated candidate, an
     untyped event: no check, `:unknown`. Missing is never incompatible."
  (:require [clojure.string :as str]
            [palomar.agent-dates :as dates]
            [palomar.model :as model]
            [palomar.plugins.events :as events]
            [palomar.plugins.lido :as lido]
            [palomar.rules :as rules]))

(def kinds
  "The kinds of a typed event."
  #{:creation :publication :exhibition :acquisition :activity :influence :attribution})

(def constraints
  "What an event of each kind says of the life of who it is typed for: only
   a creation carried out by them, and only that it does not end long before
   their birth. Every other kind says nothing, nor does a creation after
   death (see the ns doc)."
  {:creation :not-before-birth})

(def birth-margin
  "Years a creation may end before the birth of who it names and still be
   no evidence (lot M7, review): a creation dated by the source before its
   maker's birth is rarely the maker's, but a date read from a model, a
   plate or a design may precede a namesake's birth by a few years; 10, not
   the 2 years of `agent-dates/tolerance`."
  10)

;; ---------------------------------------------------------------------------
;; Dates of events
;; ---------------------------------------------------------------------------

(defn- not-blank [s] (some-> s str/trim not-empty))

(defn- sane
  "`iv` when it bounds something and does not end before it begins (beyond
   the tolerance), else nil."
  [iv]
  (let [{:keys [from to]} iv]
    (when (and iv (or from to) (not (and from to (> from (+ to dates/tolerance)))))
      iv)))

(defn- read-interval
  "The interval a date `s` names: a point (`agent-dates/parse-point`), or a
   span of two points (`1880-1885`, `1965-04-15 - 1965-06-20`) as the
   interval holding both; nil when it is not read."
  [s]
  (or (dates/parse-point s)
      (let [[a b] (or (next (re-matches #"(.+?)\s+[-–—]\s+(.+)" s))
                      (next (re-matches #"([^-–—]+)[-–—]([^-–—]+)" s)))
            pa (some-> a dates/parse-point)
            pb (some-> b dates/parse-point)]
        (when (and pa pb)
          (sane (assoc (dates/hull [pa pb]) :text s))))))

(def ^:private open-after #"(?i)(?:^|[^\p{L}])(?:after|nach|après|apres|ap\.|post|not before|nicht vor)\s*\d")
(def ^:private open-before #"(?i)(?:^|[^\p{L}])(?:before|vor|avant|av\.|ante|not after|nicht nach|bis)\s*\d")

(def ^:private undated
  "A display date that says there is none: its earliest and latest, when
   given, are the museum's derivation (from the artist's life), circular
   as evidence on who the artist is (lot M7, review)."
  #"(?i)^\W*(?:undated|undatiert|n\.\s?d\.?|o\.\s?j\.?|s\.\s?d\.?|ohne jahr|sans date|no date)\W*$")

(defn- two-dates?
  "Whether a display date `d` names two dates it does not read as one span
   (\"Modell um 1881, Guss 1922\", \"1925, printed 1956\"): which one the
   event is, it does not say."
  [d]
  (and (< 1 (count (distinct (re-seq #"\d{3,4}" d))))
       (nil? (read-interval d))))

(defn lido-date
  "The interval of a `:lido/event`'s `:earliest`, `:latest` and display
   `:date`: the earliest and latest when given (either end open when only
   one is), widened when the display date says it is approximate, an end
   opened when it says \"after\" or \"before\" one; else the display date.
   Nil when a date given is not read, when any carries an era marker before
   the common era, when the display names two dates (a model and its
   cast) or says the work is undated (\"undated\", \"n.d.\", \"o.J.\":
   whatever bounds come with it are derived), or when it ends before it
   begins: read no narrower than the
   source."
  [{:keys [date earliest latest]}]
  (let [[d e l] (map not-blank [date earliest latest])]
    (when (and (or d e l)
               (not-any? dates/before-common-era? (remove nil? [d e l]))
               (not (and d (re-find undated d)))
               (not (and d (two-dates? d))))
      (let [iv (if (or e l)
                 (let [pe (some-> e read-interval)
                       pl (some-> l read-interval)]
                   (when (and (or (nil? e) pe) (or (nil? l) pl))
                     (dates/qualify (cond-> {:from (:from pe) :to (:to pl)} d (assoc :text d)) d)))
                 (some-> d read-interval))]
        (sane (cond-> iv
                (and iv d (re-find open-after d))  (assoc :to nil)
                (and iv d (re-find open-before d)) (assoc :from nil)))))))

(def ^:private edition-prefix
  "What precedes a year in an edition's date: a copyright, a printing, a
   legal deposit, a phonogram (`c1990`, `cop. 1990`, `impr. 1990`, `DL
   1990`, `©1990`, `p1990`)."
  #"(?i)^(?:copyright|cop\.?|impr\.?|dl|c|©|℗|p)\s*(?=\d)")

(defn edition-date
  "The interval an edition's date `s` (260 `$c`…) names, its brackets and a
   copyright or printing prefix dropped; nil when it is not read."
  [s]
  (when-let [t (some-> s (str/replace #"[\[\]]" "") not-blank)]
    (some-> (str/replace t edition-prefix "") not-blank read-interval sane)))

;; ---------------------------------------------------------------------------
;; LIDO
;; ---------------------------------------------------------------------------

(defn- fields [a] (when (model/structured-value? (:value a)) (:value/fields (:value a))))

(defn- xpath [a] (some->> (model/evidence-of a) first :selector :value))

(defn- vocab? [concept terms]
  (boolean (some #(some (fn [cs] (contains? cs concept)) (rules/vocab-concepts :lido/event-types %))
                 (remove str/blank? terms))))

(defn lido-kind
  "The kind of the event `event` (a `:lido/event`'s fields) for the actor
   `actor` (a `:lido/actor`'s fields), by what the record says (see the ns
   doc)."
  [event actor]
  (let [k     (:kind event)
        terms (concat [(:type event) (:label event)] (some-> (:terms event) (str/split #"; ")))]
    (cond
      (#{"production" "design"} k)
      (cond (= "creator" (:responsibility actor))                                  :creation
            (= "influence" (:attribution actor))                                   :influence
            (#{"former" "related" "uncertain" "later-making"} (:attribution actor)) :attribution
            :else                                                                  :activity)
      (= "acquisition" k)                 :acquisition
      (vocab? :event/exhibition terms)    :exhibition
      (vocab? :event/publication terms)   :publication
      :else                               :activity)))

(def ^:private group-type #"(?i)corporat|institution|organi[sz]ation|company|firm|family|group")

(def ^:private group-role
  "A role that names a firm, whatever the actor's type: a manufacturer
   outlives its founder (Thonet, Cassina)."
  #"(?i)manufactur|hersteller|fabricant|fabrik|manufaktur|\bfirm\b|company")

(defn group?
  "Whether the LIDO actor `actor` (a `:lido/actor`'s fields) is a group,
   which has no life to constrain: its type a group's
   (`resources/palomar/vocab/lido/actor-classes.edn`) or an institution's,
   or its role a manufacturer's."
  [{t :type role :role}]
  (boolean (or (and (not-blank t) (or (lido/group-types t) (re-find group-type t)))
               (and role (re-find group-role role)))))

(defn lido-mentions
  "The actors `record` (a LIDO object) names, each as a mention the matcher
   reads: `{:name :record :event :position :type :ids :dates :events}`,
   `:type` the actor's (person, corporation…), `:ids` the
   authority ids its record gives it (every `actorID` that is an IRI),
   `:dates` its vital dates as the record states them (a profile, absent
   when none is read), `:events` the one event it is named in, typed for it
   (`lido-kind`, `lido-date`), citing the evidence of the event and of the
   actor."
  [record]
  (let [live   (remove model/out-of-force? (:assertions record))
        evs    (into {} (for [a live :when (= :lido/event (:predicate a)) :let [f (fields a)]]
                          [(:index f) [f a]]))
        id-nat (for [a live :when (and (= :lido/actor-id (:predicate a)) (string? (:value a)))]
                 [(xpath a) (str/trim (:value a))])]
    (vec (for [a live
               :when (= :lido/actor (:predicate a))
               :let [f       (fields a)
                     [e ea]  (evs (:event f))
                     path    (xpath a)
                     ids     (distinct (keep not-blank (cons (:uri f)
                                                             (for [[p v] id-nat
                                                                   :when (and path p (str/starts-with? p (str path "/"))
                                                                              (re-find #"^[a-z][a-z0-9+.-]*:" v))]
                                                               v))))
                     life    (when (or (:birth f) (:death f))
                               (dates/parse-life-dates (str (or (not-blank (:birth f)) "....") "-"
                                                            (or (not-blank (:death f)) "...."))))
                     date    (when e (lido-date e))
                     grp     (group? f)
                     typed   (when e
                               (cond-> {:kind   (lido-kind e f)
                                        :role   (:responsibility f)
                                        :source {:record   (:id record)
                                                 :event    (:event f)
                                                 :evidence (vec (concat (model/evidence-of ea) (model/evidence-of a)))}}
                                 (:role f)        (assoc :role-terms (:role f))
                                 (:attribution f) (assoc :attribution (:attribution f))
                                 grp              (assoc :group? true)
                                 date             (assoc :date date)))]
               :when (not-blank (:name f))]
           (cond-> {:name     (:name f)
                    :record   (:id record)
                    :event    (:event f)
                    :position (:position f)
                    :type     (:type f)
                    :ids      (vec ids)
                    :events   (if typed [typed] [])}
             grp                 (assoc :group? true)
             (dates/dated? life) (assoc :dates life))))))

;; ---------------------------------------------------------------------------
;; MARC family
;; ---------------------------------------------------------------------------

(def ^:private published-008
  "MARC21 008/06 types of date whose Date 1 is the resource's publication
   (or production) date: not `i`/`k` (a collection's inclusive or bulk
   dates), `b`, `n` (none)."
  #{\s \m \r \t \p \e \q \c \d \u})

(defn- date-008
  "The interval of a MARC21 008's Date 1 (07-10), `u` an unknown digit, when
   its type of date (06) is a publication's; else nil."
  [v]
  (when (and (string? v) (<= 11 (count v)) (published-008 (nth v 6)))
    (let [d (subs v 7 11)]
      (when (and (re-matches #"[0-9u]{4}" d) (not= "9999" d))
        (some-> (str/replace d "u" "X") read-interval sane)))))

(defn edition-events
  "The edition events of a bibliographic `record` (MARC21, INTERMARC,
   UNIMARC), typed for a mention of it in role `role`: one `:publication`
   per date subfield of a field that states a publication or a production
   of the Manifestation (`palomar.plugins.events/field-events`, its kind
   under `:manifestation`), and, for MARC21, one for 008 Date 1. Each keeps
   its text, and its `:date` when read (`edition-date`)."
  [record role]
  (let [frag  (into {} (map (juxt :id :locator)) (:fragments record))
        live  (remove model/out-of-force? (:assertions record))
        event (fn [kind field text date ev]
                (cond-> {:kind :publication :manifestation kind :role role :text text
                         :source {:record (:id record) :field field :evidence (vec ev)}}
                  date (assoc :date date)))
        field (for [a live
                    :let [p (:predicate a)
                          [_ tag code] (when (keyword? p) (re-matches #"f(\d{3})_(.)" (name p)))
                          spec (get-in events/field-events [(namespace p) tag])
                          ev   (model/evidence-of a)
                          k    (:kind spec)
                          kind (if (map? k) (get k (:ind2 (frag (some :fragment ev)))) k)]
                    :when (and spec (= code (:date spec)) (string? (:value a)) (events/event-kinds kind))]
                (event kind tag (str/trim (:value a)) (edition-date (:value a)) ev))
        f008  (for [a live
                    :when (= :marc21/f008 (:predicate a))
                    :let [d (date-008 (:value a))]
                    :when d]
                (event :publication "008" (subs (:value a) 7 11) d (model/evidence-of a)))]
    (vec (concat field f008))))

;; ---------------------------------------------------------------------------
;; Compatibility
;; ---------------------------------------------------------------------------

(defn compatibility
  "Whether the typed `events` of a mention can be those of a person whose
   life dates are `profile` (`agent-dates`):
   `{:verdict :compatible|:incompatible|:unknown :checks [{:event :compared [kind role] :a :b :ok?}]}`.
   Only a dated event of a kind `constraints` names, of no group, is
   checked, and only against the birth: a creation does not end more than
   `birth-margin` years before the birth begins. A creation after death is
   never checked (a posthumous cast, impression, reissue or copy names its
   artist). Incompatible when a check fails; unknown when none applies."
  [events profile]
  (let [birth  (:birth profile)
        checks (vec (for [e     events
                          :when (and (= :not-before-birth (constraints (:kind e))) (:date e)
                                     (not (:group? e)) birth)]
                      {:event    (select-keys e [:kind :role :date :source])
                       :compared [(:kind e) :birth]
                       :a        (:date e)
                       :b        birth
                       :ok?      (let [from (:from birth) to (:to (:date e))]
                                   (boolean (or (nil? from) (nil? to) (<= from (+ to birth-margin)))))}))]
    {:verdict (cond (empty? checks)       :unknown
                    (every? :ok? checks)  :compatible
                    :else                 :incompatible)
     :checks  checks}))

(defn consistent-events
  "The `events` that do not contradict the mention's own stated life `own`
   (a profile, nil when it states none): a record that dates its actor's
   creation long before the birth it states for them contradicts itself,
   and its event is no evidence on anyone (lot M7, review)."
  [events own]
  (if (dates/dated? own)
    (vec (remove #(= :incompatible (:verdict (compatibility [%] own))) events))
    (vec events)))

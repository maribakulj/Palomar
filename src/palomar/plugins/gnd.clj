(ns palomar.plugins.gnd
  "GND authority records as reconciliation pool entries (lot M6, ADR 0038
   amendment): the lobid GND JSON-LD *lines* format — one complete, compacted
   JSON-LD record per line, as lobid's search API serves it
   (`https://lobid.org/gnd/search?q=…&format=jsonl`, every hit of the query).
   GND data is CC0.

   That format is read because each line is a whole record: a file of any
   size is read one line at a time, in bounded memory, with no need to group
   triples by subject (an N-Triples dump is not sorted by subject, so
   building an entry from it would need the whole graph or an external sort
   first). **The DNB's own bulk files are not read**: its JSON-LD dump
   (`authorities-gnd-person_lds.jsonld.gz`) is expanded JSON-LD in arrays, not one
   compacted record per line, and its RDF dumps are RDF/XML, Turtle and
   N-Triples; a whole GND in lines is had by paging lobid's search
   (`format=jsonl`), not from a DNB file.

   An agent record (a `Person`, `CorporateBody` or `Family` in its `type`) is
   one entry; an `UndifferentiatedPerson` (a Tn record, one name for several
   people) is none, since a match to it identifies no one:
   - `:id`, its GND URI (`https://d-nb.info/gnd/…`);
   - `:label`, its `preferredName`; `:variants`, its `variantName`s;
   - `:dates`, a `palomar.agent-dates` profile from `dateOfBirth`,
     `dateOfDeath` and `periodOfActivity` (else `dateOfBirthAndDeath`), with
     a `:text` that states them; a date the parser does not read contributes
     nothing, and a birth after the death (beyond the tolerance) keeps the
     text alone;
   - `:entity-type`, `\"person\"`, `\"corporateBody\"` or `\"family\"`;
   - `:same-as`, the sorted IRIs of its `sameAs` links to VIAF, ISNI,
     Wikidata, the Library of Congress and the BnF — **data, never a
     feature**: `palomar.agent-match` compares names and dates only.
   A record of another kind (a subject heading, a place, a work) is no entry,
   nor is an undifferentiated person.
   Nothing here opens a network connection: a reader is given."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [palomar.agent-dates :as dates]))

(def ^:private same-as-hosts
  "The authority files whose `sameAs` links an entry keeps."
  #"^https?://(?:www\.)?(?:viaf\.org/viaf/|isni\.org/isni/|wikidata\.org/entity/|id\.loc\.gov/|catalogue\.bnf\.fr/ark:|data\.bnf\.fr/)")

(defn- strs
  "The strings of a JSON-LD value: a string, or a vector of them."
  [v]
  (vec (distinct (keep #(some-> % str str/trim not-empty) (if (sequential? v) v [v])))))

(defn- entity-type
  "The kind of agent a record's `type` says, or nil when it is no agent."
  [types]
  (let [ts (set (strs types))]
    (cond
      (ts "UndifferentiatedPerson")                                         nil
      (some ts ["Person" "DifferentiatedPerson"])                           "person"
      (some ts ["CorporateBody" "Company" "OrganOfCorporateBody" "ReligiousAdministrativeUnit"]) "corporateBody"
      (ts "Family")                                                        "family")))

(defn- point
  "The interval holding every date of `texts` the parser reads (never
   narrower than any of them), or nil."
  [texts]
  (dates/hull (keep dates/parse-point texts)))

(defn- profile
  "The life-dates profile of record `r` (see the ns doc), or nil when it
   states no date."
  [r]
  (let [born   (strs (get r "dateOfBirth"))
        died   (strs (get r "dateOfDeath"))
        active (strs (get r "periodOfActivity"))
        both   (strs (get r "dateOfBirthAndDeath"))
        text   (str/join ", " (concat (map #(str "b. " %) born) (map #(str "d. " %) died)
                                      (map #(str "fl. " %) active)
                                      (when (and (empty? born) (empty? died)) both)))
        b      (point born)
        d      (point died)
        a      (dates/hull (keep #(:active (dates/parse-life-dates (str "fl. " %))) active))
        span   (when (and (nil? b) (nil? d) (seq both)) (dates/parse-life-dates (first both)))
        read   (cond-> {}
                 b                (assoc :birth b)
                 d                (assoc :death d)
                 a                (assoc :active a)
                 (:birth span)    (assoc :birth (:birth span))
                 (:death span)    (assoc :death (:death span)))
        sane?  (let [bf (:from (:birth read)) dt (:to (:death read))]
                 (not (and bf dt (> bf (+ dt dates/tolerance)))))]
    (when-not (str/blank? text)
      (merge {:text text} (when sane? read)))))

(defn entry
  "The pool entry of one lobid GND record `r` (a parsed JSON object, string
   keys), or nil when it is no agent or has no id or preferred name."
  [r]
  (let [id    (some-> (get r "id") str str/trim not-empty)
        label (first (strs (get r "preferredName")))
        kind  (entity-type (get r "type"))]
    (when (and id label kind)
      (let [life (profile r)
            same (vec (sort (distinct (filter #(re-find same-as-hosts %)
                                              (keep #(when (map? %) (get % "id")) (let [s (get r "sameAs")] (if (sequential? s) s [s])))))))]
        (cond-> {:id          id
                 :label       label
                 :variants    (vec (remove #{label} (strs (get r "variantName"))))
                 :entity-type kind}
          life        (assoc :dates life)
          (seq same)  (assoc :same-as same))))))

(defn read-line-entry
  "The entry of one line of a lobid GND JSON lines file: `{:entry e}`, or
   `{:skipped reason}` for a blank line (`:blank`), a line that is no JSON
   object (`:not-json`), an undifferentiated person
   (`:undifferentiated-person`) or another record that is no agent
   (`:not-an-agent`). A byte-order mark before the line is dropped."
  [line]
  (let [line (str/replace-first (str line) #"^\uFEFF" "")]
    (if (str/blank? line)
      {:skipped :blank}
      (let [r (try (json/read-str line) (catch Exception _ ::bad))]
        (cond
          (not (map? r)) {:skipped :not-json}
          (some #{"UndifferentiatedPerson"} (strs (get r "type"))) {:skipped :undifferentiated-person}
          :else          (if-let [e (entry r)] {:entry e} {:skipped :not-an-agent}))))))

(defn entries
  "The entries of `lines` (a lazy sequence, read one at a time), skipping
   what is no agent record."
  [lines]
  (keep (comp :entry read-line-entry) lines))

(defn pool
  "The pool of lobid GND JSON lines text `s`: `{:entries [..]}` in the order of
   the lines. For a dump, build an index instead (`palomar.authority-index`)."
  [s]
  {:entries (vec (entries (str/split-lines s)))})

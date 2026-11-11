(ns palomar.no-skos-on-agents-property-test
  "No person and no group ever gets a SKOS mapping property (lot O2, ADR
   0061). SKOS gives `skos:semanticRelation` and every sub-property of it
   (`mappingRelation`, `exactMatch`, `closeMatch`, ...) the domain and range
   `skos:Concept` (SKOS Reference S19, S20, S39–S42): one such triple on a
   person makes it a concept for any RDFS reasoner.

   Over every source fixture that converts, and over generated
   reconciliations of the fixtures' agents (proposals outright and for
   review, to an entity's IRI and to a record's, accepted, rejected,
   flagged, confirmed, clustered), every RDF export — N-Triples, Turtle,
   JSON-LD, CRM, pure CRM, N-Quads, TriG, PROV-O — and Linked Art, with and
   without proposals: no subject or object typed `E21_Person`, `E74_Group`,
   `E39_Actor` (or a sub-class Palomar writes: `F11_Corporate_Body`, a
   RiC-O agent) appears in a triple whose predicate is a SKOS semantic
   relation, and no Linked Art `Person` or `Group` holds a SKOS-mapped key
   (`exact_match`, `close_match`, ...)."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.walk :as walk]
            [palomar.agent-cluster :as ac]
            [palomar.convert :as convert]
            [palomar.curate :as curate]
            [palomar.identity :as identity]
            [palomar.identity-check :as identity-check]
            [palomar.model :as model]
            [palomar.plugins.agents :as agents]
            [palomar.plugins.lrmoo.export :as export]
            [palomar.plugins.lrmoo.ntriples :as nt]
            [palomar.reconcile :as rec]))

;; ---------------------------------------------------------------------------
;; What may not touch an agent
;; ---------------------------------------------------------------------------

(def ^:private skos "http://www.w3.org/2004/02/skos/core#")

(def semantic-relations
  "`skos:semanticRelation` and every sub-property SKOS declares (S18, S22,
   S38–S41): all have the domain and range `skos:Concept`."
  (set (map #(str skos %) ["semanticRelation" "broader" "narrower" "related" "broaderTransitive"
                           "narrowerTransitive" "mappingRelation" "closeMatch" "exactMatch"
                           "broadMatch" "narrowMatch" "relatedMatch"])))

(def ^:private crm "http://www.cidoc-crm.org/cidoc-crm/")
(def ^:private rico "https://www.ica.org/standards/RiC/ontology#")

(def agent-classes
  "The classes of a person or a group Palomar writes."
  (into #{(str crm "E21_Person") (str crm "E74_Group") (str crm "E39_Actor") (str crm "E40_Legal_Body")
          "http://iflastandards.info/ns/lrm/lrmoo/F11_Corporate_Body"}
        (map #(str rico %) ["Agent" "Person" "Group" "CorporateBody" "Family"])))

(def ^:private rdf-type "http://www.w3.org/1999/02/22-rdf-syntax-ns#type")

(def linked-art-skos-keys
  "The Linked Art context's keys that map to SKOS semantic relations."
  #{"exact_match" "close_match" "broad_match" "narrow_match" "related_match" "broader" "narrower" "related"})

;; ---------------------------------------------------------------------------
;; Reading the outputs back: [s p o] IRI triples
;; ---------------------------------------------------------------------------

(defn- nt-triples [doc quads?]
  (for [[s p o] (if quads? (nt/parse-quads doc) (nt/parse doc))]
    [(nt/term-key s) (:iri p) (nt/term-key o)]))

(defn- expander
  "A function from a compact IRI (`pfx:local`), a `<full>` IRI or a full IRI
   to the full IRI, under `prefixes`."
  [prefixes]
  (fn [t]
    (let [t (str/trim (str t))]
      (cond
        (re-matches #"<.*>" t) (subs t 1 (dec (count t)))
        :else (let [[_ pfx local] (re-matches #"([A-Za-z][\w-]*):(.*)" t)]
                (if (and pfx (prefixes pfx) (not (str/starts-with? local "//"))) (str (prefixes pfx) local) t))))))

(defn- jsonld-triples
  "The IRI triples of the compacted JSON-LD documents `doc` (one per line)
   Palomar's RDF view writes."
  [doc]
  (for [line (str/split-lines doc) :when (not (str/blank? line))
        :let [{ctx "@context" g "@graph"} (json/read-str line)
              x (expander ctx)
              many #(if (sequential? %) % [%])]
        node g
        :let [s (get node "@id")]
        [k v] node :when (not= "@id" k)
        o (many v)
        :let [p (if (= "@type" k) rdf-type (x k))
              o (cond (= "@type" k) (x o) (and (map? o) (get o "@id")) (x (get o "@id")) :else nil)]
        :when o]
    [s p o]))

(defn- turtle-triples
  "The IRI triples of Palomar's Turtle or TriG `doc` whose predicate is
   `rdf:type` or a SKOS term: those lines hold IRIs only, so they read
   without a full Turtle parser. Statements are grouped by subject, the
   predicates indented below it (four more spaces inside a TriG graph)."
  [doc]
  (let [prefixes (into {} (for [[_ p i] (re-seq #"@prefix ([\w-]+): <([^>]*)> \." doc)] [p i]))
        x        (expander prefixes)]
    (loop [lines (str/split-lines doc) subj nil depth 0 acc []]
      (if-let [l (first lines)]
        (let [indent (count (re-find #"^ *" l))
              t      (str/trim l)]
          (cond
            (or (str/blank? t) (str/starts-with? t "@prefix") (= "}" t)) (recur (rest lines) subj depth acc)
            (str/ends-with? t "{")                                         (recur (rest lines) subj (+ indent 4) acc)
            (= indent depth)                                               (recur (rest lines) (x (first (str/split t #"\s+"))) depth acc)
            :else
            (let [[_ p os] (re-matches #"(\S+)\s+(.*?)\s*[;.]?$" t)
                  p        (if (= "a" p) rdf-type (x p))]
              (recur (rest lines) subj depth
                     (if (or (= rdf-type p) (str/starts-with? (str p) skos) (= "http://www.w3.org/2002/07/owl#sameAs" p))
                       (into acc (for [o (str/split os #"\s*,\s*")] [subj p (x o)]))
                       acc)))))
        acc))))

(defn- triples-of [to doc]
  (case to
    (:ntriples :crm :crm-only :prov) (nt-triples doc false)
    :nquads                          (nt-triples doc true)
    :jsonld                          (jsonld-triples doc)
    (:turtle :trig)                  (turtle-triples doc)))

(def ^:private triples-of-all triples-of)

(defn rdf-violations
  "The triples of `triples` putting a SKOS semantic relation on an agent:
   a subject or an object typed by one of `agent-classes`."
  [triples]
  (let [agents (into #{} (keep (fn [[s p o]] (when (and (= rdf-type p) (agent-classes o)) s))) triples)]
    (vec (filter (fn [[s p o]] (and (semantic-relations p) (or (agents s) (agents o)))) triples))))

(defn linked-art-violations
  "The Linked Art `Person` and `Group` objects of `doc` holding a SKOS key."
  [doc]
  (let [hits (atom [])]
    (doseq [line (str/split-lines doc) :when (not (str/blank? line))]
      (walk/postwalk (fn [m]
                       (when (and (map? m) (#{"Person" "Group"} (get m "type"))
                                  (some linked-art-skos-keys (keys m)))
                         (swap! hits conj m))
                       m)
                     (json/read-str line)))
    @hits))

(def rdf-targets [:ntriples :turtle :jsonld :crm :crm-only :nquads :trig :prov])

(defn export-violations
  "Every violation in every export of `records`, with and without
   proposals: `[[target with-proposals? violation] ..]`."
  [records]
  (vec (for [to (conj rdf-targets :linked-art)
             :let [rs (filter #(convert/accepts? to (convert/resource-type %)) records)]
             :when (seq rs)
             wp [false true]
             :let [out (:output (convert/export-records to (vec rs) {:with-proposals? wp}))]
             v (if (= :linked-art to) (linked-art-violations out) (rdf-violations (triples-of to out)))]
         [to wp v])))

;; ---------------------------------------------------------------------------
;; owl:sameAs soundness: each one from a curator's identity to an entity
;; ---------------------------------------------------------------------------

(def ^:private owl-same-as "http://www.w3.org/2002/07/owl#sameAs")

(defn- acceptance
  "The decision that last put claim `id` in force on `r`: its acceptance,
   or a supersession naming it the replacement."
  [r id]
  (last (for [n (:annotations r)
              :let [f (get-in n [:value :value/fields])]
              :when (or (and (= id (:about n)) (= :accepted (:to f)))
                        (and (= :supersede (:verdict f)) (= id (:replaced-by f))))]
          f)))

(defn- sound-same-as?
  "Whether `records` hold, for the sameAs `[s o]`, an in-force
   `palomar:matchesAuthority` from the agent written `s` to the authority
   written `o`: asserted, or accepted by a curator's signed, per-claim
   decision; `o` naming the entity; the stated type agreeing with the
   agent's kind; and no conflict naming it, stored or found over the
   records exported."
  [records conflicts [s o]]
  (some (fn [r]
          (let [ents (into {} (map (juxt :id identity)) (:entities r))
                iri  #(or (:iri (ents %)) (export/entity-iri %))]
            (some (fn [a]
                    (let [id   (model/assertion-id a)
                          auth (ents (get-in a [:value :value/target]))
                          kind (:kind (ents (:subject a)))
                          t    (:entity-type auth)
                          acc  (acceptance r id)]
                      (and (= :palomar/matchesAuthority (:predicate a))
                           (= s (iri (:subject a))) (= o (:iri auth))
                           (or (= :asserted (:status a))
                               (and (= :accepted (:status a)) (:curated acc) (some? (:by acc))))
                           (identity/entity-iri? o)
                           (or (nil? t) (if (= :crm/E74_Group kind) (#{"corporateBody" "family"} t) (= "person" t)))
                           (not (contains? (identity/conflicted-claims r) id))
                           (not (contains? conflicts id)))))
                  (:assertions r))))
        records))

(defn same-as-violations
  "The `owl:sameAs` triples of every RDF export of `records` that no sound
   claim gives (`sound-same-as?`)."
  [records]
  (let [conflicts (set (keys (identity-check/conflicts records)))]
    (vec (for [to (remove #{:prov} rdf-targets)
               wp [false true]
               :let [out (:output (convert/export-records to (vec records) {:with-proposals? wp}))]
               [s p o] (triples-of-all to out)
               :when (= owl-same-as p)
               :when (not (sound-same-as? records conflicts [s o]))]
           [to wp s o]))))

(defn- same-as-count [records]
  (count (filter #(= owl-same-as (second %)) (triples-of-all :ntriples (:output (convert/export-records :ntriples (vec records) {}))))))

;; ---------------------------------------------------------------------------
;; The fixtures, and reconciliations of their agents
;; ---------------------------------------------------------------------------

(def ^:private spoke-dirs
  {:intermarc "intermarc" :intermarc-ng "intermarc-ng" :unimarc "unimarc" :marc21 "marc21" :dc "dublin-core"
   :mods "mods" :iiif "iiif" :ead "ead" :eac-cpf "eac-cpf" :lido "lido"})

(def ^:private per-fixture
  "The records of each fixture the sweep reconciles and exports."
  4)

(def fixtures
  "`[[from path records] ..]`: every source fixture of a spoke that converts
   (through its spoke, or as an OAI-PMH response), its first records as the
   pivot."
  (delay
    (vec (for [[from dir] (sort spoke-dirs)
               f (sort-by str (file-seq (io/file "test/fixtures/documentary" dir)))
               :when (and (.isFile ^java.io.File f) (re-find #"\.(xml|json)$" (.getName ^java.io.File f)))
               :let [path (str f)
                     read-as (fn [fmt] (try (:records (convert/to-wemi fmt (if (= fmt :dc) {:record-id :dc/x} {}) (slurp path)))
                                            (catch Exception _ nil)))
                     ;; an OAI-PMH response carries its records (LIDO harvests)
                     rs   (or (seq (read-as from)) (read-as :oai))]
               :when (seq rs)]
           [from path (vec (take per-fixture rs))]))))

(def ^:private authorities
  "Authority IRIs of both kinds: one naming the person (ISNI, a BnF
   `#about`), one naming a record (a BnF catalogue ARK, a LoC name)."
  ["https://isni.org/isni/0000000122762442"
   "http://catalogue.bnf.fr/ark:/12148/cb11901063m"
   "http://data.bnf.fr/ark:/12148/cb11901063m#about"
   "http://id.loc.gov/authorities/names/n79091479"
   "https://d-nb.info/gnd/118528068"
   "https://d-nb.info/gnd/1012345678"
   "https://isni.org/isni/0000000121032683"])

(def ^:private stated-types
  "The entity type each authority states, when it states one: a GND person,
   a GND undifferentiated person (Tn), a corporate body's ISNI."
  {"http://data.bnf.fr/ark:/12148/cb11901063m#about" "person"
   "https://d-nb.info/gnd/118528068"                 "person"
   "https://d-nb.info/gnd/1012345678"                "UndifferentiatedPerson"
   "https://isni.org/isni/0000000121032683"          "corporateBody"})

(defn- names-of [r] (vec (distinct (map :name (agents/occurrences r)))))

(defn- proposal [nm iri decision weight]
  (cond-> {:name nm :authority-id iri :authority-label nm :certifiable? true :status :proposed
           :decision decision :score 0.9 :match-weight weight}
    (stated-types iri) (assoc :entity-type (stated-types iri))))

(def ^:private curator {:by "curator" :at #inst "2026-11-11T10:00:00Z"})

(defn- identity-claims [r] (filter #(identity/relation? (:predicate %)) (:assertions r)))

(defn- decide
  "Apply `verdict` to `r`'s pending identity claim `a`: `:accept` and
   `:confirm` are a curator's, one claim at a time and signed; `:accept-anon`
   one claim at a time, unsigned."
  [r a verdict]
  (cond
    (not (model/pending? a))  r
    (= :leave verdict)        r
    (= :confirm verdict)      (if (= identity/authority-candidate (:predicate a))
                                (:record (rec/confirm-identity r a "confirmed" curator))
                                (:record (curate/decide-one r a :accept "accepted" curator)))
    (= :accept verdict)       (:record (curate/decide-one r a :accept "accepted" curator))
    (= :accept-anon verdict)  (:record (curate/decide-one r a :accept "accepted"))
    :else                     (:record (curate/decide-one r a verdict (name verdict)))))

(def ^:private verdicts [:accept :leave :reject :confirm :review :accept-anon])

(defn reconcile-both
  "`records` with their agents aligned by `plan`, a fn of (record index,
   name index) -> `[authority-iri decision verdict]`, clustered, curated by
   the verdicts, memberships accepted for even records, and clustered
   again: `[clustered-last bulk-accepted]`, the second the first after a
   bulk `accept-all` of every pending claim (a policy, no curator)."
  [records plan]
  (let [props   (for [[i r] (map-indexed vector records)
                      [j nm] (map-indexed vector (names-of r))
                      :let [[iri decision] (plan i j)]]
                  (assoc (proposal nm iri decision (double (+ 2 (mod (+ i j) 7)))) :mention {:name nm :record (:id r)}))
        aligned (rec/align-agents records props)
        {c1 :records} (ac/cluster-records aligned)
        curated (vec (map-indexed (fn [i r]
                                    (reduce (fn [r [j a]] (decide r a (nth (plan i j) 2)))
                                            r (map-indexed vector (identity-claims r))))
                                  c1))
        {c2 :records} (ac/cluster-records curated)
        members (vec (map-indexed (fn [i r] (if (even? i)
                                              (reduce (fn [r a] (if (= identity/cluster-membership (:predicate a))
                                                                  (:record (curate/decide-one r a :accept "member" curator))
                                                                  r))
                                                      r (:assertions r))
                                              r))
                                  c2))
        {c3 :records} (ac/cluster-records members)]
    [c3 (:records (curate/curate c3 curate/accept-all {:by "a policy"}))]))

(defn reconcile
  "The records `reconcile-both` leaves clustered last."
  [records plan]
  (first (reconcile-both records plan)))

(defn- sweep-plan
  "A fixed plan cycling through every authority kind, decision and verdict."
  [i j]
  [(nth authorities (mod (+ i j) (count authorities)))
   (if (even? j) :propose :review)
   (nth verdicts (mod (+ (* 3 i) j) (count verdicts)))])

(deftest the-check-sees-a-skos-mapping-on-an-agent-in-every-export
  (testing "an identity claim as an older Palomar held it (a SKOS mapping, not upgraded) is caught in every RDF export"
    (let [[_ _ records] (first (filter #(some (comp seq names-of) (nth % 2)) @fixtures))
          rs  (reconcile records (constantly ["https://isni.org/isni/0000000122762442" :propose :accept]))
          old (mapv (fn [r] (update r :assertions (partial mapv #(cond-> % (identity/relation? (:predicate %)) (assoc :predicate :skos/exactMatch)))))
                    rs)]
      (is (= (set (remove #{:prov} rdf-targets)) (set (map first (export-violations old)))))))
  (testing "a SKOS key on a Linked Art Person is caught"
    (is (seq (linked-art-violations (json/write-str {"type" "Person" "exact_match" [{"id" "x"}]}))))))

(deftest the-soundness-check-sees-a-same-as-no-curated-identity-gives
  (let [[_ _ records] (first (filter #(some (comp seq names-of) (nth % 2)) @fixtures))
        isni          "https://isni.org/isni/0000000122762442"]
    (testing "a review candidate a curator accepted (not confirmed) is no identity: no sameAs from it"
      (is (empty? (same-as-violations (reconcile records (constantly [isni :review :accept]))))))
    (testing "an identity a curator accepted is a sound sameAs"
      (let [rs (reconcile records (constantly [isni :propose :accept]))]
        (is (pos? (same-as-count rs)))
        (is (empty? (same-as-violations rs)))))
    (testing "an unsigned acceptance is no sameAs"
      (is (zero? (same-as-count (reconcile records (constantly [isni :propose :accept-anon]))))))))

(deftest no-agent-gets-a-skos-mapping-in-any-fixture-or-export
  (testing "the fixtures convert"
    (is (= 41 (count @fixtures)) (str "fixtures that convert: " (count @fixtures))))
  (let [claims (atom 0) same-as (atom 0)]
    (doseq [[_ path records] @fixtures]
      (testing (str path ", as converted")
        (is (empty? (export-violations records))))
      (testing (str path ", its agents reconciled, curated and clustered")
        (let [[rs bulk] (reconcile-both records sweep-plan)]
          (swap! claims + (count (mapcat identity-claims rs)))
          (swap! same-as + (same-as-count rs))
          (is (empty? (export-violations rs)))
          (testing "every owl:sameAs is a curator's identity to an entity, kind agreeing, in no conflict: clustered last, and after a bulk acceptance"
            (is (empty? (same-as-violations rs)))
            (is (empty? (same-as-violations bulk)))
            (is (<= (same-as-count bulk) (same-as-count rs)) "a bulk acceptance adds no sameAs")))))
    (testing "the sweep reconciles agents: it checks identity claims and sameAs, not empty exports"
      (is (< 100 @claims) (str @claims " identity claims"))
      (is (< 10 @same-as) (str @same-as " owl:sameAs")))))

(def ^:private gen-step
  (gen/tuple (gen/elements authorities) (gen/elements [:propose :review]) (gen/elements verdicts)))

(def ^:private sample-fixtures
  "One fixture per spoke for the generated reconciliations: the first that
   converts with agents."
  (delay (vec (for [[_ fs] (group-by first @fixtures)
                    :let [f (first (filter #(some (comp seq names-of) (nth % 2)) fs))]
                    :when f]
                f))))

(defspec no-agent-gets-a-skos-mapping-whatever-the-curation 20
  (prop/for-all [k     gen/nat
                 steps (gen/vector gen-step 1 12)]
                (let [[_ _ records] (nth @sample-fixtures (mod k (count @sample-fixtures)))
                      plan          (fn [i j] (nth steps (mod (+ (* 5 i) j) (count steps))))
                      [rs bulk]     (reconcile-both records plan)]
                  (and (empty? (export-violations rs))
                       (empty? (same-as-violations rs))
                       (empty? (same-as-violations bulk))))))

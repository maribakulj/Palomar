(ns palomar.plugins.authorities
  "Authority files as reconciliation pools (ADR 0038): a MADS/RDF or SKOS
   vocabulary, as RDF/XML or N-Triples, read into the `[{:id :label :variants}]`
   entries `palomar.reconcile/propose-agent-links` matches names against.

   An entry is a resource the file says is an authority — a `madsrdf:Authority`
   (either MADS namespace: the published `http://www.loc.gov/mads/rdf/v1#` and
   id.loc.gov's `http://id.loc.gov/ontologies/mads/2010/11#`) or a
   `skos:Concept`:
   - `:id`, its IRI;
   - `:label`, its `madsrdf:authoritativeLabel` or `skos:prefLabel` (the first,
     preferring one with no language or the requested one);
   - `:variants`, its other labels: `skos:altLabel`, `skos:hiddenLabel`,
     `madsrdf:variantLabel`, and the `madsrdf:variantLabel` of the nodes it
     `madsrdf:hasVariant` or `hasEarlierEstablishedForm`.

   A MADS `Variant` that is no Authority — a deleted heading — is no entry: it
   is listed under `:deprecated` with the headings that replaced it
   (`hasLaterEstablishedForm`), so a caller can follow it rather than match a
   retired name. Turtle is not read (ADR 0038).

   An EAC-CPF record (ADR 0039) is one entry: its authorized name (in the
   requested language, else the first) and its other names as variants. Its
   id is its `entityId` when that is an IRI, else none — a local `recordId`
   such as `001` identifies nothing outside the file, so a match to it is
   never certifiable (ADR 0018). What the entry leaves out (functions,
   relations, biography…) is listed under `:unread`.

   A BnF INTERMARC authority record (`format=\"INTERMARC\" type=\"Authority\"`,
   as the SRU serves it) is one entry: its `003` ARK as `:id`, its record
   number (the `001` without `FRBNF` and its check character, the form a
   bibliographic `$3` cites) as `:record-number`, the `100` `$a, $m` as label,
   the `400`s as variants, the `100 $d` as dates, every `031 $a` that
   passes the ISNI check as `:isni` (a vector, in the record's order; a
   cancelled `$z` is not read), its `$2` kept as where it came from
   (`:isni-sources [{:isni :source}]`: the BnF writes \"VIAF\" or \"ISNI
   chargé à partir de VIAF\" there, never another scheme; lot N5 review),
   and the leader's position 9 as its kind (`p`: a person).
   The tags it does not read are listed under `:unread`.

   **Profiles** (lot M3, ADR 0038 amendment): an entry carries `:dates`, a
   `palomar.agent-dates` life-dates profile, when its source states dates —
   EAC-CPF `existDates`, MADS `birthDate`/`deathDate` (on the authority or the
   real-world object it `identifiesRWO`), INTERMARC `100 $d`; SKOS has none —
   and `:entity-type` (\"person\", \"corporateBody\", \"family\") when its source
   states the kind. A date the parser cannot read is kept as text and
   contributes nothing."
  (:require [clojure.string :as str]
            [palomar.agent-dates :as dates]
            [palomar.agent-equivalence :as eq]
            [palomar.plugins.intermarc :as intermarc]
            [palomar.plugins.lrmoo.ntriples :as nt]
            [palomar.rdfxml :as rdfxml]
            [palomar.xml :as rx]))

(def ^:private rdf-type "http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
(def ^:private skos "http://www.w3.org/2004/02/skos/core#")
(def ^:private mads-namespaces ["http://www.loc.gov/mads/rdf/v1#" "http://id.loc.gov/ontologies/mads/2010/11#"])

(defn- mads [local] (set (map #(str % local) mads-namespaces)))

(defn- index
  "`triples` as subject-key → predicate IRI → [objects]."
  [triples]
  (reduce (fn [m [s p o]] (update-in m [(nt/term-key s) (:iri p)] (fnil conj []) o)) {} triples))

(defn- objs [idx s preds] (mapcat #(get-in idx [s %]) preds))

(defn- lits [idx s preds] (vec (distinct (keep :lit (objs idx s preds)))))

(defn- typed? [idx s classes] (some #(contains? classes (:iri %)) (get-in idx [s rdf-type])))

(defn- pick-label
  "The label among literal terms `ls` in language `lang`, else one with no
   language, else the first."
  [ls lang]
  (or (some #(when (and lang (= lang (:lang %))) (:lit %)) ls)
      (some #(when (nil? (:lang %)) (:lit %)) ls)
      (:lit (first ls))))

(def ^:private rdfs-label "http://www.w3.org/2000/01/rdf-schema#label")

(defn- date-values
  "The date texts of `s`'s `preds`: a literal, or a node's `rdfs:label` (as
   id.loc.gov writes the dates of a real-world object)."
  [idx s preds]
  (distinct (for [o (objs idx s preds)
                  t (if (:lit o) [(:lit o)] (lits idx (nt/term-key o) [rdfs-label]))]
              t)))

(defn- mads-dates
  "The life-dates profile of MADS authority `s`: its `birthDate` and
   `deathDate`, else those of the real-world objects it `identifiesRWO`; nil
   when it states none."
  [idx s]
  (let [subjects (cons s (map nt/term-key (objs idx s (mads "identifiesRWO"))))
        point    (fn [p] (some #(dates/parse-point %) (mapcat #(date-values idx % (mads p)) subjects)))
        texts    (mapcat #(date-values idx % (into (mads "birthDate") (mads "deathDate"))) subjects)
        profile  (cond-> {}
                   (point "birthDate") (assoc :birth (point "birthDate"))
                   (point "deathDate") (assoc :death (point "deathDate")))]
    (cond
      (seq profile) profile
      (seq texts)   {:text (str/join ", " texts)})))

(def ^:private same-as-p
  "The predicates by which an authority record says another id names its
   entity (lot N6, ADR 0059): `owl:sameAs`, `skos:exactMatch` and a MADS
   exact external authority. A close match, a MADS close external
   authority, says alike, not the same: it is not read."
  (into #{"http://www.w3.org/2002/07/owl#sameAs" (str skos "exactMatch")} (mads "hasExactExternalAuthority")))

(defn- same-as
  "The IRIs record `s` states name its entity (`same-as-p`), itself left
   out, sorted."
  [idx s]
  (vec (sort (distinct (remove #{s} (keep :iri (objs idx s same-as-p)))))))

(defn pool
  "The reconciliation pool of an authority graph `triples` (see the ns doc):
   `{:entries [{:id :label :variants}] :deprecated [{:id :label :replaced-by}]}`;
   an entry carries `:same-as` when its record states other ids of its
   entity (`same-as-p`).
   `opts` may name the preferred label `:lang`."
  ([triples] (pool triples {}))
  ([triples {:keys [lang]}]
   (let [idx        (index triples)
         authority  (into (mads "Authority") [(str skos "Concept")])
         label-p    (into (mads "authoritativeLabel") [(str skos "prefLabel")])
         variant-p  (into (mads "variantLabel") [(str skos "altLabel") (str skos "hiddenLabel")])
         via-p      (into (mads "hasVariant") (mads "hasEarlierEstablishedForm"))
         iri-subj   (filter #(re-find #"^[A-Za-z][A-Za-z0-9+.-]*:" %) (keys idx))]
     {:entries
      (vec (for [s iri-subj
                 :when (typed? idx s authority)
                 :let [label (pick-label (filter :lit (objs idx s label-p)) lang)]
                 :when label]
             (cond-> {:id       s
                      :label    label
                      :variants (vec (remove #{label}
                                             (distinct (concat (lits idx s label-p)
                                                               (lits idx s variant-p)
                                                               (mapcat #(lits idx (nt/term-key %) (mads "variantLabel"))
                                                                       (objs idx s via-p))))))}
               ;; the MADS name class says what kind of agent it is (C7)
               (typed? idx s (into (mads "CorporateName") (mads "ConferenceName"))) (assoc :entity-type "corporateBody")
               (typed? idx s (mads "FamilyName"))                                  (assoc :entity-type "family")
               (typed? idx s (mads "PersonalName"))                                (assoc :entity-type "person")
               (mads-dates idx s)                                                  (assoc :dates (mads-dates idx s))
               ;; what the record states of its entity (lot N6, ADR 0059)
               (seq (same-as idx s))                                               (assoc :same-as (same-as idx s)))))
      :deprecated
      (vec (for [s iri-subj
                 :when (and (typed? idx s (mads "Variant")) (not (typed? idx s authority)))]
             {:id          s
              :label       (first (lits idx s (mads "variantLabel")))
              :replaced-by (vec (keep :iri (objs idx s (mads "hasLaterEstablishedForm"))))}))})))

(defn- local [tag] (when tag (name tag)))

(defn- kids
  ([el] (filter map? (:content el)))
  ([el nm] (filter #(= nm (local (:tag %))) (kids el))))

(defn- xtext [el]
  (some-> (str/join " " (map str/trim (filter string? (tree-seq map? :content el)))) (str/replace #"\s+" " ") str/trim not-empty))

(defn- xattr [el nm] (some (fn [[k v]] (when (= nm (name k)) v)) (:attrs el)))

(defn- eac-interval
  "The interval of an EAC-CPF date element: its `@standardDate` (else its
   text) as a point, kept approximate when its text says so, widened to its
   `@notBefore` / `@notAfter` — never narrower than any of them."
  [el]
  (when el
    (let [txt (xtext el)
          std (xattr el "standardDate")
          p   (if std (dates/qualify (dates/parse-point std) txt) (some-> txt dates/parse-point))
          nb  (some-> (xattr el "notBefore") dates/parse-point)
          na  (some-> (xattr el "notAfter") dates/parse-point)]
      (cond
        p           (dates/hull [p nb na])
        (or nb na)  (cond-> {:from (:from nb) :to (:to na)} (or std txt) (assoc :text (or std txt)))))))

(defn- eac-member
  "The profile of one member of an `existDates`: a `dateRange`'s `fromDate` is
   the birth (a body's start), its `toDate` the death (its end); a `date` is
   read as a span (`1802-1870`)."
  [el]
  (case (local (:tag el))
    "dateRange" (let [b (eac-interval (first (kids el "fromDate")))
                      d (eac-interval (first (kids el "toDate")))]
                  (cond-> {} b (assoc :birth b) d (assoc :death d)))
    "date"      (dissoc (dates/parse-life-dates (or (xattr el "standardDate") (xtext el))) :text)
    nil))

(defn- eac-dates
  "The life-dates profile of an EAC-CPF `existDates` element (see
   `eac-member`); a `dateSet`'s members are alternatives
   (`dates/alternatives`). Nil without `existDates`; `{:text}` alone when
   nothing is read."
  [el]
  (when el
    (let [inner   (or (first (kids el "dateSet")) el)
          members (keep eac-member (kids inner))
          read    (if (seq (kids el "dateSet")) (dates/alternatives members) (first members))]
      (merge (when-let [t (xtext el)] {:text t}) read))))

(defn eac-pool
  "The reconciliation pool of EAC-CPF document `xml` (EAC-CPF 1.x or 2.0):
   `{:entries [{:id :label :variants :entity-type}] :unread [element…]}`."
  ([xml] (eac-pool xml {}))
  ([xml {:keys [lang]}]
   (let [root     (rx/parse-str xml)
         _        (when-not (#{"eac" "eac-cpf"} (local (:tag root)))
                    (throw (ex-info "authorities: not an EAC-CPF document" {:root (local (:tag root))})))
         cpf      (first (kids root "cpfDescription"))
         identity (first (kids cpf "identity"))
         entries  (concat (kids identity "nameEntry") (mapcat #(kids % "nameEntry") (kids identity "nameEntryParallel")))
         named    (for [n entries
                        :let [parts (kids n "part")
                              t     (if (seq parts) (str/join ", " (keep xtext parts)) (xtext n))]
                        :when t]
                    {:name       t
                     :authorized (or (= "authorized" (xattr n "status")) (seq (kids n "authorizedForm")))
                     :lang       (or (xattr n "languageOfElement") (some #(xattr % "languageOfElement") parts) (xattr n "lang"))})
         auth     (filter :authorized named)
         label    (:name (or (some #(when (and lang (= lang (:lang %))) %) auth) (first auth) (first named)))
         eid      (some xtext (kids identity "entityId"))
         desc     (first (kids cpf "description"))
         life     (eac-dates (first (kids desc "existDates")))]
     {:entries (if label
                 [(cond-> {:id          (when (and eid (nil? (nt/iri-problem eid))) eid)
                           :label       label
                           :variants    (vec (remove #{label} (distinct (map :name named))))
                           :entity-type (some #(or (xattr % "value") (xtext %)) (kids identity "entityType"))}
                    life (assoc :dates life))]
                 [])
      ;; existDates is read when the parser reads a date of it
      :unread  (vec (concat (remove #(and (= "existDates" %) (dates/dated? life))
                                    (map (comp local :tag) (kids desc)))
                            (when (seq (kids cpf "relations")) ["relations"])))})))

(defn- subfields-of [fragment] (get-in fragment [:locator :subfields]))

(defn- first-sub [sfs code] (some (fn [[c v]] (when (= code c) (some-> v str/trim not-empty))) sfs))

(defn- heading
  "The name `$a, $m` of an INTERMARC heading's subfields (the first of each),
   as the bibliographic `:map/intermarc-agent` mapping recombines a 100."
  [sfs]
  (let [parts (keep #(first-sub sfs %) ["a" "m"])]
    (when (seq parts) (str/join ", " parts))))

(def intermarc-read-tags
  "The INTERMARC authority tags an entry reads (and the leader); the
   relation fields (`intermarc-relation-kind`) are read too."
  #{"001" "003" "031" "100" "400"})

(defn intermarc-relation-kind
  "The kind of relation an INTERMARC authority field states to another
   authority record (lot M8): `:pseudonym` for a 331 (another identity of
   the same person), `:work` for a 321 (a work), `:agent` for a 320 or a 5xx
   (a related person, family or body); nil for any other tag."
  [tag]
  (cond (= "331" tag)                                 :pseudonym
        (= "321" tag)                                 :work
        (or (= "320" tag) (re-matches #"5\d\d" (str tag))) :agent))

(defn- intermarc-relation
  "The relation INTERMARC authority field `f` states, `{:record-number :tag
   :kind}`, or nil: a relation field (`intermarc-relation-kind`) that links
   a record by `$3`; a 320 only with its `$0` relation code, a relation
   between two agents (a 320 without one, such as a museum holding the
   person's manuscripts, says where, not who). A 331 says by its second
   indicator what the linked record is (lot N6): `:target :pseudonym` (1, a
   pseudonym of this record's person) or `:target :real-identity` (2, the
   real identity whose pseudonym this record is)."
  [f]
  (let [tag (get-in f [:locator :tag])
        sfs (subfields-of f)
        k   (intermarc-relation-kind tag)
        n   (when k (first-sub sfs "3"))
        dir (when (= :pseudonym k) ({"1" :pseudonym "2" :real-identity} (get-in f [:locator :ind2])))]
    (when (and n (or (not= "320" tag) (first-sub sfs "0")))
      (cond-> {:record-number n :tag tag :kind k}
        dir (assoc :target dir)))))

(defn- intermarc-relations
  "The relations a record's `fragments` state to other authority records,
   by their `$3` record number (`intermarc-relation`)."
  [fragments]
  (vec (distinct (keep intermarc-relation fragments))))

(def ^:private intermarc-kinds
  "The kinds the INTERMARC authority leader's position 9 states, as entity
   types: only a person here. A kind it does not name is left unstated."
  {\p "person"})

(defn intermarc-pool
  "The reconciliation pool of BnF INTERMARC authority records `xml` (see the ns
   doc): `{:entries [{:id :record-number :label :variants :dates :entity-type
   :isni :relations}] :unread [tag…]}`. A record without a `100` is no
   entry. `:relations` (lot M8), when the record states any, are the other
   authority records its 320, 321, 331 and 5xx fields link by `$3`
   (`intermarc-relation`). A relation field that states none (no `$3`, a
   320 with no relation code) stays listed as unread."
  [xml]
  (let [records (intermarc/ingest xml {})
        fields  (fn [r tag] (filter #(= tag (get-in % [:locator :tag])) (:fragments r)))
        value   (fn [r tag] (some-> (first (fields r tag)) (get-in [:locator :value]) str/trim))
        leader  (fn [r] (some #(when (= :intermarc/leader (:predicate %)) (:value %)) (:assertions r)))]
    {:entries
     (vec (for [r records
                :let [h     (subfields-of (first (fields r "100")))
                      label (heading h)]
                :when label
                :let [ark   (value r "003")
                      n     (some->> (value r "001") (re-find #"^FRBNF(\d{8})") second)
                      life  (dates/parse-life-dates (first-sub h "d"))
                      ;; `$2` names where the ISNI came from (ISNI, VIAF…), not
                      ;; its scheme: every `$a` that passes the check is one
                      srcs  (vec (distinct (keep #(let [sfs (subfields-of %)
                                                        a   (first-sub sfs "a")]
                                                    (when (some-> (eq/identifier-key a) (str/starts-with? "isni:"))
                                                      (cond-> {:isni a} (first-sub sfs "2") (assoc :source (first-sub sfs "2")))))
                                                 (fields r "031"))))
                      isni  (vec (distinct (map :isni srcs)))
                      kind  (some-> (leader r) (as-> l (when (< 9 (count l)) (intermarc-kinds (nth l 9)))))
                      rels  (intermarc-relations (:fragments r))]]
            (cond-> {:id       (when (and ark (nil? (nt/iri-problem ark))) ark)
                     :label    label
                     :variants (vec (distinct (keep #(heading (subfields-of %)) (fields r "400"))))}
              n    (assoc :record-number n)
              life (assoc :dates life)
              kind (assoc :entity-type kind)
              (seq isni) (assoc :isni isni :isni-sources srcs)
              (seq rels) (assoc :relations rels))))
     :unread (vec (sort (distinct (for [r records
                                        f (:fragments r)
                                        :let [t (get-in f [:locator :tag])]
                                        :when (and t (not (intermarc-read-tags t)) (not (intermarc-relation f)))]
                                    t))))}))

(defn read-pool
  "The reconciliation pool of authority file `s` in `format` (`:rdfxml` or
   `:ntriples`), an EAC-CPF record (`:eac-cpf`), or BnF INTERMARC authority
   records (`:intermarc`)."
  ([format s] (read-pool format s {}))
  ([format s opts]
   (case format
     :rdfxml   (pool (rdfxml/parse s) opts)
     :ntriples (pool (nt/parse s) opts)
     :eac-cpf  (eac-pool s opts)
     :intermarc (intermarc-pool s)
     (throw (ex-info (str "authorities: cannot read " (pr-str format) " (rdfxml, ntriples, eac-cpf or intermarc)")
                     {:format format})))))

(defn sniff-format
  "The format of authority file `s` named `path`: N-Triples by its `.nt`
   extension, EAC-CPF by its root element, INTERMARC authority records by a
   MARCXChange record of format INTERMARC and type Authority, else RDF/XML."
  [path s]
  (let [head (subs s 0 (min 4000 (count s)))]
    (cond
      (str/ends-with? (str path) ".nt")                                    :ntriples
      (re-find #"<(eac|eac-cpf)[\s>]" head)                                :eac-cpf
      (re-find #"<(?:\w+:)?record\b[^>]*format=\"INTERMARC\"[^>]*type=\"Authority\"" s) :intermarc
      :else                                                                :rdfxml)))

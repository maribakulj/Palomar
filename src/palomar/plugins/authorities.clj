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
   relations, biography…) is listed under `:unread`."
  (:require [clojure.string :as str]
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

(defn pool
  "The reconciliation pool of an authority graph `triples` (see the ns doc):
   `{:entries [{:id :label :variants}] :deprecated [{:id :label :replaced-by}]}`.
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
               (typed? idx s (mads "PersonalName"))                                (assoc :entity-type "person"))))
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
         eid      (some xtext (kids identity "entityId"))]
     {:entries (if label
                 [{:id          (when (and eid (nil? (nt/iri-problem eid))) eid)
                   :label       label
                   :variants    (vec (remove #{label} (distinct (map :name named))))
                   :entity-type (some #(or (xattr % "value") (xtext %)) (kids identity "entityType"))}]
                 [])
      :unread  (vec (concat (map (comp local :tag) (kids (first (kids cpf "description"))))
                            (when (seq (kids cpf "relations")) ["relations"])))})))

(defn read-pool
  "The reconciliation pool of authority file `s` in `format` (`:rdfxml` or
   `:ntriples`), or an EAC-CPF record (`:eac-cpf`)."
  ([format s] (read-pool format s {}))
  ([format s opts]
   (case format
     :rdfxml   (pool (rdfxml/parse s) opts)
     :ntriples (pool (nt/parse s) opts)
     :eac-cpf  (eac-pool s opts)
     (throw (ex-info (str "authorities: cannot read " (pr-str format) " (rdfxml, ntriples or eac-cpf)")
                     {:format format})))))

(defn sniff-format
  "The format of authority file `s` named `path`: N-Triples by its `.nt`
   extension, EAC-CPF by its root element, else RDF/XML."
  [path s]
  (cond
    (str/ends-with? (str path) ".nt")                     :ntriples
    (re-find #"<(eac|eac-cpf)[\s>]" (subs s 0 (min 4000 (count s)))) :eac-cpf
    :else                                                 :rdfxml))

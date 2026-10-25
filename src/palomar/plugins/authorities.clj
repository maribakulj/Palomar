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
   retired name. Turtle is not read (ADR 0038)."
  (:require [palomar.plugins.lrmoo.ntriples :as nt]
            [palomar.rdfxml :as rdfxml]))

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
             {:id       s
              :label    label
              :variants (vec (remove #{label}
                                     (distinct (concat (lits idx s label-p)
                                                       (lits idx s variant-p)
                                                       (mapcat #(lits idx (nt/term-key %) (mads "variantLabel"))
                                                               (objs idx s via-p))))))}))
      :deprecated
      (vec (for [s iri-subj
                 :when (and (typed? idx s (mads "Variant")) (not (typed? idx s authority)))]
             {:id          s
              :label       (first (lits idx s (mads "variantLabel")))
              :replaced-by (vec (keep :iri (objs idx s (mads "hasLaterEstablishedForm"))))}))})))

(defn read-pool
  "The reconciliation pool of authority file `s` in `format` (`:rdfxml` or
   `:ntriples`)."
  ([format s] (read-pool format s {}))
  ([format s opts]
   (pool (case format
           :rdfxml   (rdfxml/parse s)
           :ntriples (nt/parse s)
           (throw (ex-info (str "authorities: cannot read " (pr-str format) " (rdfxml or ntriples)")
                           {:format format})))
         opts)))

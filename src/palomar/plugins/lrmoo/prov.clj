(ns palomar.plugins.lrmoo.prov
  "PROV-O export of a record's computational provenance (audit S35): which
   activity produced each assertion, from what, with which status.

   Every assertion of the record (source, canonical, WEMI) is written as a
   reified `rdf:Statement` — its subject, predicate and object — carrying its
   `palomar:status`. The statement `prov:wasGeneratedBy` an activity: one per
   (record, phase, rule) that produced it, typed `prov:Activity`, with
   `palomar:phase` and `prov:wasAssociatedWith` the rule (a `prov:Plan`). A
   statement derived from other subjects `prov:wasDerivedFrom` them; one that
   several rules derived carries every activity (`:provenance :also`); the
   match that fired the rule is kept as `palomar:bindings`.

   A record's curation decisions (its annotations, ADR 0028) become PROV
   activities that used, and may invalidate, the statement they decided; any
   other annotation is written as a triple about the statement.

   Reification keeps the output plain N-Triples (RDF-star is not in RDF 1.1).
   A statement IRI is the assertion's `model/assertion-id` (ADR 0027), so the
   same assertion always names the same statement, before and after a curator
   changes its status."
  (:require [palomar.model :as model]
            [palomar.plugins.lrmoo :as lrmoo]
            [palomar.plugins.lrmoo.export :as export]
            [palomar.plugins.lrmoo.ntriples :as nt]))

(def ^:private rdf  "http://www.w3.org/1999/02/22-rdf-syntax-ns#")
(def ^:private prov "http://www.w3.org/ns/prov#")
(def ^:private pal  "urn:palomar:vocab#")
(def ^:private xsd  "http://www.w3.org/2001/XMLSchema#")

(defn- digest
  "16 hex digits over `x`'s canonical form (`model/content-digest`)."
  [x]
  (model/content-digest x 16))

(defn- term-iri
  "IRI of a predicate keyword: LRMoo and CRM terms by their own namespaces,
   any other by a `urn:palomar:pred:` IRI."
  [k]
  (case (namespace k)
    "lrmoo" (lrmoo/iri k)
    "crm"   (str export/crm-base (name k))
    (str "urn:palomar:pred:" (namespace k) ":" (name k))))

(defn- node-iri [idx id] (or (get idx id) (export/entity-iri id)))

(defn- object [idx v]
  (cond
    (model/reference-value? v) {:iri (node-iri idx (:value/target v))}
    (string? v)                {:lit v}
    (int? v)                   {:lit (str v) :datatype (str xsd "integer")}
    (keyword? v)               {:lit (str v)}
    :else                      {:lit (pr-str v)}))

(defn- activity-iri [rid {:keys [pass rule]}]
  (str "urn:palomar:activity:" (digest [rid pass rule])))

(def ^:private engine
  "The software agent every activity is associated with: the Palomar engine."
  "urn:palomar:agent:engine")

(defn- activity-triples
  "The activity of (record `rid`, phase, rule) `p`. A rule is not an agent (the
   range of `prov:wasAssociatedWith`) but the plan the engine followed, so it
   rides on a qualified association: activity —qualifiedAssociation→
   Association —agent→ engine (`prov:SoftwareAgent`), —hadPlan→ rule
   (`prov:Plan`)."
  [rid p]
  (let [a (activity-iri rid p)]
    (cond-> [[a (str rdf "type") {:iri (str prov "Activity")}]
             [a (str prov "wasAssociatedWith") {:iri engine}]
             [engine (str rdf "type") {:iri (str prov "SoftwareAgent")}]]
      (:pass p) (conj [a (str pal "phase") {:lit (name (:pass p))}])
      (:rule p) (into (let [plan  (str "urn:palomar:rule:" (subs (str (:rule p)) 1))
                            assoc (str "urn:palomar:association:" (digest [rid (:pass p) (:rule p)]))]
                        [[a (str prov "qualifiedAssociation") {:iri assoc}]
                         [assoc (str rdf "type") {:iri (str prov "Association")}]
                         [assoc (str prov "agent") {:iri engine}]
                         [assoc (str prov "hadPlan") {:iri plan}]
                         [plan (str rdf "type") {:iri (str prov "Plan")}]])))))

(defn- statement-iri
  "The IRI of the statement reifying the assertion whose id is `asr-id`."
  [asr-id]
  (str "urn:palomar:stmt:" (name asr-id)))

(def ^:private decision-predicate
  "The annotation predicate `palomar.curate` records a decision under; named as
   data so this plugin does not depend on the curation namespace."
  :palomar.curation/decision)

(defn- agent-iri
  "An agent's IRI: the curator `by` itself when it is an IRI, else a
   `urn:palomar:agent:` IRI made from it."
  [by]
  (if (nil? (nt/iri-problem by)) by (str "urn:palomar:agent:" (digest by))))

(defn- decision-triples
  "A curation decision about statement `st`, as a PROV activity (ADR 0028): it
   used the statement, was carried out by the curator, ended at the decision's
   time, and invalidated the statement when it took it out of force; a
   supersession also makes the replacement a revision of it."
  [st {:keys [verdict from to reason by at replaced-by] :as fields}]
  (let [d (str "urn:palomar:decision:" (digest [st fields]))]
    (concat
     [[d (str rdf "type") {:iri (str prov "Activity")}]
      [d (str prov "used") {:iri st}]
      [d (str pal "verdict") {:lit (name verdict)}]
      [d (str pal "to") {:lit (name to)}]]
     (when from [[d (str pal "from") {:lit (name from)}]])
     (when reason [[d (str pal "reason") {:lit (str reason)}]])
     (when by [[d (str prov "wasAssociatedWith") {:iri (agent-iri by)}]
               [(agent-iri by) (str rdf "type") {:iri (str prov "Agent")}]
               [(agent-iri by) (str pal "label") {:lit (str by)}]])
     (when (inst? at) [[d (str prov "endedAtTime") {:lit (str (.toInstant ^java.util.Date at))
                                                    :datatype (str xsd "dateTime")}]])
     (when (model/out-of-force? {:status to}) [[st (str prov "wasInvalidatedBy") {:iri d}]])
     (when replaced-by [[(statement-iri replaced-by) (str prov "wasRevisionOf") {:iri st}]
                        [(statement-iri replaced-by) (str rdf "type") {:iri (str prov "Entity")}]]))))

(defn- annotation-triples
  "An annotation about statement `st`: a curation decision as an activity
   (`decision-triples`); any other as a triple `st —predicate→ value`, the
   predicate in the vocabulary its writer chose."
  [idx {:keys [about predicate value]}]
  (let [st (statement-iri about)]
    (if (and (= decision-predicate predicate) (model/structured-value? value))
      (decision-triples st (:value/fields value))
      [[st (term-iri predicate) (object idx value)]])))

(defn triples
  "The PROV-O graph of `record` as `[s p o]` triples (see the namespace doc)."
  [record]
  (let [idx (into {} (keep (fn [e] (when-let [i (:iri e)] [(:id e) i]))) (:entities record))
        rid (:id record)]
    (distinct
     (concat
      (mapcat
       (fn [a]
         (let [st    (statement-iri (model/assertion-id a))
               p     (:provenance a)
               provs (cons (dissoc p :also) (:also p))]
           (concat
            [[st (str rdf "type") {:iri (str rdf "Statement")}]
            ;; a statement is what an activity generates, so a prov:Entity (the
            ;; domain of wasGeneratedBy), and so is what it derives from
             [st (str rdf "type") {:iri (str prov "Entity")}]
             [st (str rdf "subject") {:iri (node-iri idx (:subject a))}]
             [st (str rdf "predicate") {:iri (term-iri (:predicate a))}]
             [st (str rdf "object") (object idx (:value a))]]
            (when (:status a) [[st (str pal "status") {:lit (name (:status a))}]])
            (when (:bindings p) [[st (str pal "bindings") {:lit (pr-str (:bindings p))}]])
            (mapcat (fn [d] [[st (str prov "wasDerivedFrom") {:iri (node-iri idx d)}]
                             [(node-iri idx d) (str rdf "type") {:iri (str prov "Entity")}]])
                    (:derivation p))
            (for [q provs :when (or (:pass q) (:rule q))
                  t (cons [st (str prov "wasGeneratedBy") {:iri (activity-iri rid q)}]
                          (activity-triples rid q))]
              t))))
       (:assertions record))
      (mapcat #(annotation-triples idx %) (:annotations record))))))

(defn ->ntriples
  "Render `record`'s PROV-O graph as N-Triples (\"\" when it has no assertion)."
  [record]
  (export/render-ntriples (triples record)))

(defn export-losses
  "The PROV-O export writes every assertion, with its status and provenance, so
   it loses none of them: `convert` hands it the out-of-force ones too, as it
   records rather than states them (ADR 0025). A record's diagnostics are not
   assertions and are not written; that is not content loss."
  [_record]
  [])

(defn statement-count
  "How many statements `->ntriples` writes for `record`: one per distinct
   assertion (its `model/assertion-id`, the statement's identity)."
  [record]
  (count (filter #(and (= (str rdf "type") (second %)) (= {:iri (str rdf "Statement")} (nth % 2)))
                 (triples record))))

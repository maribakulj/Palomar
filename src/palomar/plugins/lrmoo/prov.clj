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

   Reification keeps the output plain N-Triples (RDF-star is not in RDF 1.1).
   A statement IRI is a digest of its subject, predicate, value and status, so
   the same assertion always names the same statement."
  (:require [palomar.model :as model]
            [palomar.plugins.lrmoo :as lrmoo]
            [palomar.plugins.lrmoo.export :as export]))

(def ^:private rdf  "http://www.w3.org/1999/02/22-rdf-syntax-ns#")
(def ^:private prov "http://www.w3.org/ns/prov#")
(def ^:private pal  "urn:palomar:vocab#")
(def ^:private xsd  "http://www.w3.org/2001/XMLSchema#")

(defn- digest [s]
  (let [d (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes (str s) "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) (take 8 d)))))

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
  (str "urn:palomar:activity:" (digest (pr-str [rid pass rule]))))

(defn- activity-triples [rid p]
  (let [a (activity-iri rid p)]
    (cond-> [[a (str rdf "type") {:iri (str prov "Activity")}]]
      (:pass p) (conj [a (str pal "phase") {:lit (name (:pass p))}])
      (:rule p) (into [[a (str prov "wasAssociatedWith") {:iri (str "urn:palomar:rule:" (subs (str (:rule p)) 1))}]
                       [(str "urn:palomar:rule:" (subs (str (:rule p)) 1)) (str rdf "type") {:iri (str prov "Plan")}]]))))

(defn triples
  "The PROV-O graph of `record` as `[s p o]` triples (see the namespace doc)."
  [record]
  (let [idx (into {} (keep (fn [e] (when-let [i (:iri e)] [(:id e) i]))) (:entities record))
        rid (:id record)]
    (distinct
     (mapcat
      (fn [a]
        (let [st    (str "urn:palomar:stmt:" (digest (pr-str [(:subject a) (:predicate a) (:value a) (:status a)])))
              p     (:provenance a)
              provs (cons (dissoc p :also) (:also p))]
          (concat
           [[st (str rdf "type") {:iri (str rdf "Statement")}]
            [st (str rdf "subject") {:iri (node-iri idx (:subject a))}]
            [st (str rdf "predicate") {:iri (term-iri (:predicate a))}]
            [st (str rdf "object") (object idx (:value a))]]
           (when (:status a) [[st (str pal "status") {:lit (name (:status a))}]])
           (when (:bindings p) [[st (str pal "bindings") {:lit (pr-str (:bindings p))}]])
           (for [d (:derivation p)] [st (str prov "wasDerivedFrom") {:iri (node-iri idx d)}])
           (for [q provs :when (or (:pass q) (:rule q))
                 t (cons [st (str prov "wasGeneratedBy") {:iri (activity-iri rid q)}]
                         (activity-triples rid q))]
             t))))
      (:assertions record)))))

(defn ->ntriples
  "Render `record`'s PROV-O graph as N-Triples (\"\" when it has no assertion)."
  [record]
  (export/render-ntriples (triples record)))

(defn export-losses
  "The PROV-O export writes every assertion, with its status and provenance, so
   it loses none of them. A record's diagnostics are not assertions and are not
   written; that is not content loss."
  [_record]
  [])

(defn statement-count
  "How many statements `->ntriples` writes for `record` (one per assertion)."
  [record]
  (count (filter #(and (= (str rdf "type") (second %)) (= {:iri (str rdf "Statement")} (nth % 2)))
                 (triples record))))

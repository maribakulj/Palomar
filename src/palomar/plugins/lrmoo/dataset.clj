(ns palomar.plugins.lrmoo.dataset
  "The LRMoo view of a record as an RDF dataset with named graphs (plan step 5,
   ADR 0029): facts, proposals, history and provenance, each in its own graph,
   so a consumer never reads a proposal or a rejected claim as a fact.

   - `facts-graph` — the in-force claims (`:asserted`, `:accepted`, or
     status-less): what the conversion states;
   - `proposals-graph` — the pending claims (`:proposed`, `:needs-review`): what
     an inference suggests and nobody has accepted;
   - `history-graph` — the claims taken out of force (`:rejected`,
     `:superseded`, `:retracted`): kept so their history can be read;
   - `provenance-graph` — the PROV-O record of every claim and decision
     (`palomar.plugins.lrmoo.prov`).

   A triple inside a named graph is not asserted in the default graph: the
   proposal and history graphs *quote* claims. Which graph means what is stated
   in the default graph (`rdf:type` of each graph, `palomar:` classes), not left
   to the graph's name. Entities are typed in each graph whose claims mention
   them (`model/filter-assertions`)."
  (:require [palomar.model :as model]
            [palomar.plugins.lrmoo.export :as export]
            [palomar.plugins.lrmoo.prov :as prov]))

(def facts-graph      "urn:palomar:graph:facts")
(def proposals-graph  "urn:palomar:graph:proposals")
(def history-graph    "urn:palomar:graph:history")
(def provenance-graph "urn:palomar:graph:provenance")

(def ^:private pal "urn:palomar:vocab#")

(def ^:private graph-classes
  "What each graph holds, stated in the default graph."
  {facts-graph      "FactGraph"
   proposals-graph  "ProposalGraph"
   history-graph    "HistoryGraph"
   provenance-graph "ProvenanceGraph"})

(defn- in-force-or-unstated? [a] (or (nil? (:status a)) (model/in-force? a)))

(defn graphs
  "`[[graph-iri triples] …]` for `record`: the default graph (nil) describing
   the named ones, then facts, proposals, history and provenance. Empty graphs
   are left out."
  [record]
  (let [named [[facts-graph      (export/triples (model/filter-assertions record in-force-or-unstated?))]
               [proposals-graph  (export/triples (model/filter-assertions record model/pending?))]
               [history-graph    (export/triples (model/filter-assertions record model/out-of-force?))]
               [provenance-graph (prov/triples record)]]
        named (filterv (comp seq second) named)]
    (cond->> named
      (seq named) (cons [nil (for [[g _] named]
                               [g export/rdf-type {:iri (str pal (graph-classes g))}])]))))

(defn ->nquads
  "`record` as an N-Quads dataset (\"\" when it has no LRMoo content)."
  [record]
  (export/render-nquads (graphs record)))

(defn ->trig
  "`record` as a TriG dataset (\"\" when it has no LRMoo content)."
  [record]
  (or (export/render-trig (graphs record)) ""))

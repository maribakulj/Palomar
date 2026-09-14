(ns palomar.universal-pivot-integration-test
  "Format-agnostic pivot, demonstrated off-MARC (ADR 0013): a *non-MARC* source
   reaches the LRMoo pivot.

   Two hand-built Dublin Core XML records — the same documentary content under
   distinct record ids — each run the full chain: shape-adapter ingest (ADR 0007)
   -> mapping to the canonical floor (ADR 0009) -> canonical→WEMI projection
   (ADR 0013) -> RDF, with no INTERMARC anywhere. They converge on the *same* Work
   and Expression by content alone (ADR 0008 / 0012 at the WEMI level), showing the
   projection reads only the canonical floor — on this evidence a real hub, not an
   INTERMARC converter in disguise.

   Honest scope: this *demonstrates* format-agnosticism on Dublin Core (the one
   non-MARC spoke wired so far), via hand-built records. What it pins down: the
   pivot has no INTERMARC dependency, and content-derived identity clusters
   independent records."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.diagnostics :as dx]
            [palomar.plugins :as plug]
            [palomar.plugins.lrmoo.export :as export]
            [palomar.plugins.lrmoo.project :as project]
            [palomar.plugins.lrmoo.view :as view]
            [palomar.plugins.mapping :as mapping]
            [palomar.plugins.shape :as shape]
            [palomar.runtime :as runtime]
            [palomar.xml :as rx]))

(def ^:private dc-uri "http://purl.org/dc/elements/1.1/")
(def ^:private xml-uri "http://www.w3.org/XML/1998/namespace")

(def ^:private xml-mapping
  [{:mapping/id :map/title-xml :mapping/from :dc/title :mapping/to :canon/title
    :mapping/qualifier {:from :xml/lang :as :canon/lang}}
   {:mapping/id :map/creator-xml :mapping/from :dc/creator :mapping/to :canon/agent}
   {:mapping/id :map/date-xml :mapping/from :dc/date :mapping/to :canon/date}])

(defn- xml-plugin
  "An inline Dublin Core XML plugin (rewrite-tags + ingest-xml, the way
   palomar.plugins.dc wires one), parameterised by id so two records get
   distinct source identities while sharing the mapping."
  [id]
  {:plugin/spec-version 1
   :id                  id
   :input-format        :xml
   :mapping             xml-mapping
   :importer            (fn [{:keys [record-id kind]} src]
                          {:records     [(shape/ingest-xml
                                          (shape/rewrite-tags (rx/parse-str src)
                                                              {:dc dc-uri :xml xml-uri})
                                          xml-mapping
                                          {:record-id record-id :kind (or kind :document)})]
                           :diagnostics []})})

(def ^:private xml-source
  (str "<record xmlns:dc=\"" dc-uri "\">"
       "<dc:title xml:lang=\"fr\">Les Misérables</dc:title>"
       "<dc:creator>Victor Hugo</dc:creator>"
       "<dc:date>1862</dc:date>"
       "</record>"))

(defn- project-source
  "Full non-MARC vertical for one `plugin`/`source`: ingest -> normalize to
   canonical -> canonical→WEMI projection."
  [plugin opts source]
  (let [reg      (plug/register plug/empty-registry plugin)
        compiled (mapping/compile-mappings (plug/all-mappings reg)
                                           (plug/effective-transforms reg))
        rec      (-> ((:importer plugin) opts source) :records first)
        canon    (:record (runtime/run-phase rec compiled :normalize))]
    (project/project canon)))

(def ^:private wa
  (project-source (xml-plugin :plugin/dc-a)
                  {:record-id :record/a :kind :document} xml-source))

(def ^:private wb
  (project-source (xml-plugin :plugin/dc-b)
                  {:record-id :record/b :kind :document} xml-source))

(deftest a-non-marc-source-reaches-the-wemi-pivot
  (testing "a Dublin Core record (no INTERMARC) projects to a full WEMI chain"
    (doseq [w [wa wb]]
      (is (= 1 (count (view/manifestations w))))
      (is (= 1 (count (view/expressions w))))
      (is (= 1 (count (view/works w)))))))

(deftest two-records-converge-on-the-same-work-and-expression
  (testing "content-deterministic identity makes independent records cluster (ADR 0008 / 0012)"
    (is (= (:id (first (view/works wa)))       (:id (first (view/works wb)))))
    (is (= (:id (first (view/expressions wa))) (:id (first (view/expressions wb)))))
    (testing "...yet they are distinct Manifestations (distinct source records)"
      (is (not= (:id (first (view/manifestations wa)))
                (:id (first (view/manifestations wb))))))))

(deftest the-projection-reports-dropped-canonical-loss
  (testing ":canon/date is on the canonical floor but not in WEMI -> :import loss (ADR 0015)"
    (let [ls     (dx/losses (:diagnostics wa))
          fields (set (map #(get-in % [:detail :loss/source-field]) ls))]
      (is (seq ls))
      (is (contains? fields :canon/date))
      (is (every? #(= :import (get-in % [:detail :loss/edge])) ls)))))

(deftest projected-wemi-exports-to-rdf
  (testing "the canonical-derived WEMI graph serialises as N-Triples"
    (let [nt (export/->ntriples wa)]
      (is (str/includes? nt "lrmoo/F1_Work"))
      (is (str/includes? nt "lrmoo/F2_Expression"))
      (is (str/includes? nt "lrmoo/R4_embodies")))))

(def ^:private xml-bilingual
  (str "<record xmlns:dc=\"" dc-uri "\">"
       "<dc:title xml:lang=\"fr\">Les Misérables</dc:title>"
       "<dc:title xml:lang=\"en\">The Wretched</dc:title>"
       "<dc:creator>Victor Hugo</dc:creator>"
       "</record>"))

(deftest parallel-language-titles-under-specify-the-expression-end-to-end
  (testing "a bilingual DC record through the real vertical -> one Expression -> :under-specified"
    (let [w  (project-source (xml-plugin :plugin/dc-bi)
                             {:record-id :record/bi :kind :document} xml-bilingual)
          us (filterv #(= :loss/under-specified (:code %)) (:diagnostics w))]
      (is (= 1 (count (view/expressions w))))
      (is (= 1 (count us)))
      (is (= :canon/lang (get-in (first us) [:detail :loss/source-field]))))))

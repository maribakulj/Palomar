(ns palomar.canonical-integration-test
  "End-to-end ingest → normalize → validate → report for the canonical
   vocabulary plugin (ADR 0003).

   A Dublin Core XML record carries the chain through the surfaces
   Sprint 6 adds — validation and the diagnostics report:

     raw XML
       → shape importer        (rewrite-tags + ingest-xml, wired inline
                                 the way palomar.plugins.dc does)
       → native-vocabulary Record
       → :normalize rules       (compiled from :mapping, ADR 0009)
       → :validate rules        (palomar.plugins.canonical)
       → diagnostics            (palomar.diagnostics: report + policy)

   The whole pipeline is registry-driven: the `:normalize` rules come
   from `plugins/all-mappings` and the `:validate` rules from
   `plugins/all-rules`, so the registry is the single source of truth a
   real CLI would compile from. Every component is production code.

   See ADR 0003 (canonical layer), ADR 0007 (plugins as data), ADR 0009
   (mapping schema)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.diagnostics :as diag]
            [palomar.model :as model]
            [palomar.plugins :as plugins]
            [palomar.plugins.canonical :as canonical]
            [palomar.plugins.mapping :as mapping]
            [palomar.plugins.shape :as shape]
            [palomar.rules :as rules]
            [palomar.runtime :as runtime]
            [palomar.xml :as rx]))

;; ---------------------------------------------------------------------------
;; Fixtures
;;
;; A flat Dublin-Core → canonical mapping with two documentary
;; predicates, so a record can carry an identifier yet still lack a
;; title — the case `title-required` exists to catch.
;; ---------------------------------------------------------------------------

(def ^:private dc-uri "http://purl.org/dc/elements/1.1/")

(def biblio-mapping
  [{:mapping/id        :map/title
    :mapping/from      :dc/title
    :mapping/to        :canon/title
    :mapping/transform [:trim]}
   {:mapping/id   :map/identifier
    :mapping/from :dc/identifier
    :mapping/to   :canon/identifier}])

(defn- xml-importer
  "An inline Dublin Core XML importer, wired the way `palomar.plugins.dc`
   does (rewrite-tags + ingest-xml), so the test drives a real ingest
   path through its own flat mapping."
  [mapping]
  (fn [{:keys [record-id kind]} src]
    {:records     [(shape/ingest-xml (shape/rewrite-tags (rx/parse-str src) {:dc dc-uri})
                                     mapping
                                     {:record-id record-id :kind (or kind :biblio)})]
     :diagnostics []}))

(def biblio-plugin
  {:plugin/spec-version 1
   :id                  :plugin/biblio-xml
   :input-format        :xml
   :mapping             biblio-mapping
   :importer            (xml-importer biblio-mapping)})

(def titled-xml
  (str "<record xmlns:dc=\"" dc-uri "\">"
       "<dc:title> Les Misérables </dc:title>"
       "<dc:identifier>MS-001</dc:identifier>"
       "</record>"))

(def untitled-xml
  (str "<record xmlns:dc=\"" dc-uri "\"><dc:identifier>MS-002</dc:identifier></record>"))

;; ---------------------------------------------------------------------------
;; Registry-driven pipeline
;; ---------------------------------------------------------------------------

(def registry
  "Holds the documentary (shape) plugin and the canonical plugin:
   `:normalize` rules derive from the former's `:mapping`, `:validate`
   rules from the latter's `:rules`."
  (-> plugins/empty-registry
      (plugins/register biblio-plugin)
      (plugins/register canonical/plugin)))

(def pipeline [{:phase :normalize} {:phase :validate}])

(defn- compiled-rules
  "Compile the full rule set out of the registry: mapping-derived
   `:normalize` rules followed by canonical `:validate` rules."
  []
  (let [stdlib (plugins/effective-transforms registry)]
    (into (mapping/compile-mappings (plugins/all-mappings registry) stdlib)
          (rules/compile-rules (plugins/all-rules registry)))))

(defn- ingest+run
  "Ingest one XML document through the registry's importer under
   `record-id`, run the normalize+validate pipeline, and return the
   enriched record."
  [record-id xml-src]
  (let [importer          (:importer (plugins/lookup registry :plugin/biblio-xml))
        {:keys [records]} (importer {:record-id record-id :kind :biblio} xml-src)
        record            (first records)]
    (:record (runtime/run-pipeline record (compiled-rules) pipeline))))

;; ---------------------------------------------------------------------------
;; Titled record: normalizes and passes validation
;; ---------------------------------------------------------------------------

(deftest titled-record-normalizes-and-passes-validation
  (let [record (ingest+run :record/titled titled-xml)]
    (testing "normalize produced a trimmed canonical title and an identifier"
      (let [titles (model/assertions-for record :canon/title)]
        (is (= 1 (count titles)))
        (is (= "Les Misérables" (:value (first titles)))))
      (is (= 1 (count (model/assertions-for record :canon/identifier)))))
    (testing "validate finds the title — the record carries no diagnostics"
      (is (empty? (:diagnostics record))))
    (testing "every canonical predicate produced is a recognized documentary term"
      (is (every? canonical/documentary?
                  (->> (:assertions record)
                       (map :predicate)
                       (filter #(= "canon" (namespace %)))))))))

;; ---------------------------------------------------------------------------
;; Untitled record: validation attaches a diagnostic
;; ---------------------------------------------------------------------------

(deftest untitled-record-triggers-validation-diagnostic
  (let [record (ingest+run :record/untitled untitled-xml)]
    (testing "normalize produced the identifier but no title"
      (is (= 1 (count (model/assertions-for record :canon/identifier))))
      (is (empty? (model/assertions-for record :canon/title))))
    (testing "validate attached exactly one :missing-title warning to the record"
      (let [diags (:diagnostics record)]
        (is (= 1 (count diags)))
        (let [d (first diags)]
          (is (= :missing-title (:code d)))
          (is (= :warning (:severity d)))
          (is (= :record/untitled (:subject d)))
          (testing "with provenance attributing it to the canonical rule and the validate phase"
            (is (= :rule.canonical/title-required (get-in d [:provenance :rule])))
            (is (= :validate (get-in d [:provenance :pass])))))))))

;; ---------------------------------------------------------------------------
;; Diagnostics report + failure policy across the batch
;; ---------------------------------------------------------------------------

(deftest diagnostics-report-and-failure-policy
  (let [records [(ingest+run :record/titled titled-xml)
                 (ingest+run :record/untitled untitled-xml)]
        all     (diag/collect-many records)]
    (testing "one warning across the two-record batch"
      (is (= 1 (count all)))
      (is (= 1 (get-in (diag/summary all) [:by-severity :warning])))
      (is (= :warning (diag/max-severity all))))
    (testing "format-report renders the warning section"
      (let [report (diag/format-report all)]
        (is (str/includes? report "WARN"))
        (is (str/includes? report "missing-title"))))
    (testing "failure policy: warnings pass by default, fail under :errors-and-warnings"
      (is (false? (diag/should-fail? all)))                      ; :errors-only (default)
      (is (true?  (diag/should-fail? all :errors-and-warnings)))
      (is (false? (diag/should-fail? all :never))))))

(ns palomar.integration-test
  "End-to-end integration tests: build a small set of input records, define
   rules across multiple phases, run the pipeline, and verify the resulting
   IR end-to-end (assertions, diagnostics, status, provenance, repairs,
   trace, dedup, consistency).

   The scenario is a tiny library-catalog ingestion. It exercises the pieces
   the V1 core ships: record construction, rule compilation, multi-phase
   pipeline, dedup at merge (ADR 0008), :proposed status on infer-phase
   assertions (ADR 0005), provenance deep-merge (engine fields authoritative),
   and the diagnostics query API.

   Importer / exporter / mapping integration is deliberately out of scope
   here — those will land with the plugin layer. When they do, this test
   grows a sibling that begins with `(plugins/import ...)` and ends with
   `(plugins/export ...)`."
  (:require [clojure.test :refer [deftest is testing]]
            [palomar.diagnostics :as diag]
            [palomar.model :as model]
            [palomar.rules :as rules]
            [palomar.runtime :as rt]))

;; ---------------------------------------------------------------------------
;; Fixtures: three records describing a tiny catalog
;; ---------------------------------------------------------------------------

(def ^:private complete-book
  (model/record
   {:id :r/book-1
    :kind :book
    :assertions
    [(model/assertion {:subject :r/book-1
                       :predicate :canon/title
                       :value "Les Misérables"})
     (model/assertion {:subject :r/book-1
                       :predicate :canon/agent
                       :value "Victor Hugo"})
     (model/assertion {:subject :r/book-1
                       :predicate :canon/year
                       :value 1862})]}))

(def ^:private titleless-book
  (model/record {:id :r/book-2 :kind :book}))

(def ^:private journal
  (model/record
   {:id :r/journal-1
    :kind :journal
    :assertions
    [(model/assertion {:subject :r/journal-1
                       :predicate :canon/title
                       :value "Annales de l'École"})]}))

(def ^:private records [complete-book titleless-book journal])

;; ---------------------------------------------------------------------------
;; Rules: four rules across four phases
;;
;; Each rule is a representative of the kind of work its phase does. Together
;; they exercise the full V1 pipeline shape.
;; ---------------------------------------------------------------------------

(def ^:private tag-seen
  "Normalize: every record gets a :canon/seen marker so downstream code
   can tell what the pipeline has visited."
  {:id :rule/tag-seen
   :phase :normalize
   :match '[[?r :meta/id ?_id]]
   :produce {:assert {:subject '?r
                      :predicate :canon/seen
                      :value true}}})

(def ^:private title-required
  "Validate: a book without a title is an error."
  {:id :rule/title-required
   :phase :validate
   :match '[[?r :meta/kind :book]
            (absent? ?r :canon/title)]
   :produce {:diagnostic {:severity :error
                          :code :missing-title
                          :subject '?r
                          :message "Book record has no title."}}})

(def ^:private infer-french
  "Infer: a title starting with 'Les ' is presumptive French. Per ADR 0005,
   assertions produced in :infer phase are :proposed until reviewed."
  {:id :rule/infer-french
   :phase :infer
   :match '[[?r :canon/title ?t]
            (matches? ?t "^Les ")]
   :produce {:assert {:subject '?r
                      :predicate :canon/language
                      :value :fr
                      :provenance {:source :heuristic/title-prefix}}}})

(def ^:private propose-title-repair
  "Repair: a book with no title gets an info diagnostic carrying a repair
   proposal. Repairs stay :proposed in V1 — the apply-repairs CLI workflow
   is what would surface them for human acceptance."
  {:id :rule/propose-title-repair
   :phase :repair
   :match '[[?r :meta/kind :book]
            (absent? ?r :canon/title)]
   :produce {:diagnostic
             {:severity :info
              :code :title-repair-proposed
              :subject '?r
              :message "No title; consider deriving one from the record id."
              :repairs [{:description "Use (str id) as fallback title"
                         :operation :title-from-id
                         :applicable? true
                         :safe? false}]}}})

(def ^:private compiled-rules
  (mapv rules/compile-rule
        [tag-seen title-required infer-french propose-title-repair]))

(def ^:private pipeline
  [{:phase :normalize}
   {:phase :validate}
   {:phase :infer}
   {:phase :repair}])

;; ---------------------------------------------------------------------------
;; Run helpers
;; ---------------------------------------------------------------------------

(defn- run-once [record]
  (rt/run-pipeline record compiled-rules pipeline))

(defn- final-records []
  (mapv (comp :record run-once) records))

;; ---------------------------------------------------------------------------
;; Pre-conditions: input records are themselves consistent
;; ---------------------------------------------------------------------------

(deftest input-records-are-consistent
  (testing "every fixture is shape-valid and cross-field consistent"
    (doseq [r records]
      (is (model/valid-record? r)
          (str "input invalid: " (:id r)))
      (is (model/record-consistent? r)
          (str "input inconsistent: " (:id r))))))

;; ---------------------------------------------------------------------------
;; Per-phase outcomes
;; ---------------------------------------------------------------------------

(deftest normalize-tags-every-record
  (testing "tag-seen fires once per record, regardless of kind"
    (doseq [r (final-records)]
      (is (some #(and (= :canon/seen (:predicate %))
                      (= true (:value %)))
                (:assertions r))
          (str ":canon/seen missing on " (:id r))))))

(deftest validate-flags-only-the-titleless-book
  (let [outs    (final-records)
        by-id   (into {} (map (juxt :id identity)) outs)
        b1      (:r/book-1 by-id)
        b2      (:r/book-2 by-id)
        journal (:r/journal-1 by-id)]
    (testing "complete book has no missing-title diagnostic"
      (is (empty? (diag/by-code (diag/collect b1) :missing-title))))
    (testing "titleless book has exactly one error diagnostic"
      (let [errs (diag/errors (diag/collect b2))]
        (is (= 1 (count errs)))
        (is (= :missing-title (:code (first errs))))
        (is (= "Book record has no title." (:message (first errs))))))
    (testing "journal is not subject to book-only validation"
      (is (empty? (diag/errors (diag/collect journal)))))))

(deftest infer-flags-french-titled-book
  (let [b1 (-> complete-book run-once :record)
        langs (filterv #(= :canon/language (:predicate %)) (:assertions b1))]
    (testing "exactly one language assertion on the French-titled book"
      (is (= 1 (count langs)))
      (is (= :fr (:value (first langs)))))
    (testing "infer-phase assertions carry :proposed status (ADR 0005)"
      (is (= :proposed (:status (first langs)))))
    (testing "template-supplied provenance fields are preserved alongside engine ones"
      (let [prov (:provenance (first langs))]
        (is (= :rule/infer-french (:rule prov))
            "engine-supplied :rule wins")
        (is (= :infer (:pass prov))
            "engine-supplied :pass wins")
        (is (= :heuristic/title-prefix (:source prov))
            "template :source preserved (deep-merge invariant)")))))

(deftest repair-attaches-proposal-to-titleless-book
  (let [b2     (-> titleless-book run-once :record)
        infos  (diag/infos (diag/collect b2))
        with-repairs (diag/with-repairs infos)]
    (testing "a single info-severity diagnostic carries the repair proposal"
      (is (= 1 (count with-repairs)))
      (let [d (first with-repairs)]
        (is (= :title-repair-proposed (:code d)))
        (is (= 1 (count (:repairs d))))
        (is (= :title-from-id (:operation (first (:repairs d)))))))))

;; ---------------------------------------------------------------------------
;; Cross-cutting properties of the result
;; ---------------------------------------------------------------------------

(deftest engine-produced-items-have-full-provenance
  ;; Input fixture assertions are constructed without provenance, so they
  ;; act as a control for what *isn't* engine-produced. The contract under
  ;; test: every item the engine produced carries both :rule and :pass.
  (testing "every assertion tagged with :rule also carries :pass"
    (doseq [r (final-records)
            a (:assertions r)
            :let [prov (:provenance a)]
            :when (:rule prov)]
      (is (some? (:pass prov))
          (str "engine-produced assertion missing :pass: " (pr-str a)))))
  (testing "every diagnostic carries both :rule and :pass"
    ;; All diagnostics on the result are engine-produced — input has none.
    (doseq [r (final-records)
            d (:diagnostics r)
            :let [prov (:provenance d)]]
      (is (some? (:rule prov))
          (str "diagnostic missing :rule provenance: " (pr-str d)))
      (is (some? (:pass prov))
          (str "diagnostic missing :pass provenance: " (pr-str d))))))

(deftest pipeline-preserves-record-consistency
  (testing "ADR-0001 cross-field consistency is preserved end-to-end"
    (doseq [r (final-records)]
      (is (model/record-consistent? r)
          (str "pipeline broke consistency on " (:id r) ": "
               (pr-str (model/explain-consistency r)))))))

(deftest pipeline-is-idempotent-under-rerun
  ;; ADR 0008 dedup means running the pipeline a second time on its own
  ;; output adds no new facts. This is the convergence story without a
  ;; fixpoint detector.
  (testing "rerunning the pipeline on its output is a no-op"
    (doseq [r records]
      (let [first-pass  (:record (run-once r))
            second-pass (:record (rt/run-pipeline first-pass compiled-rules pipeline))]
        (is (= (count (:assertions first-pass))
               (count (:assertions second-pass)))
            (str "assertion count grew on rerun for " (:id r)))
        (is (= (count (:diagnostics first-pass))
               (count (:diagnostics second-pass)))
            (str "diagnostic count grew on rerun for " (:id r)))))))

;; ---------------------------------------------------------------------------
;; Provenance and aggregations across the whole batch
;; ---------------------------------------------------------------------------

(deftest trace-names-every-rule-that-fired
  (let [outs   (final-records)
        traced (into #{} (mapcat (fn [r]
                                   (map #(get-in % [:provenance :rule])
                                        (concat (:assertions r) (:diagnostics r))))
                                 outs))]
    (testing "every authored rule appears at least once in the batch provenance"
      (is (contains? traced :rule/tag-seen))
      (is (contains? traced :rule/title-required))
      (is (contains? traced :rule/infer-french))
      (is (contains? traced :rule/propose-title-repair)))))

(deftest batch-summary-totals-make-sense
  (let [outs       (final-records)
        all-diag   (diag/collect-many outs)
        sum        (diag/summary all-diag)]
    (testing "exactly one error in the batch (the titleless book)"
      (is (= 1 (get-in sum [:by-severity :error])))
      (is (= :error (:max-severity sum))))
    (testing "exactly one info — the repair proposal"
      (is (= 1 (get-in sum [:by-severity :info]))))
    (testing "the failure policy reports the run as failing under :errors-only"
      (is (true?  (diag/should-fail? all-diag :errors-only)))
      (is (false? (diag/should-fail? all-diag :never))))))

;; ---------------------------------------------------------------------------
;; Productions trace vs merged record (ADR 0008 surface)
;; ---------------------------------------------------------------------------

(deftest productions-trace-counts-firings-not-merged-facts
  ;; Two rules deriving the same fact in a single normalize pass: the
  ;; production trace records both firings, but dedup-at-merge (ADR 0008)
  ;; collapses them to one :canon/seen assertion on the record.
  (let [tag-seen-again (assoc tag-seen :id :rule/tag-seen-again)
        rs     (mapv rules/compile-rule [tag-seen tag-seen-again])
        result (rt/run-phase complete-book rs :normalize)]
    (testing "trace records every firing"
      (is (= 2 (count (:productions result))))
      (is (every? #(= :canon/seen (get-in % [:value :predicate]))
                  (:productions result))))
    (testing "record carries one deduplicated assertion (ADR 0008)"
      (is (= 1 (count (filterv #(= :canon/seen (:predicate %))
                               (:assertions (:record result)))))))))

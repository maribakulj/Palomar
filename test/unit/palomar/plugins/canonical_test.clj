(ns palomar.plugins.canonical-test
  "Unit tests for the canonical vocabulary plugin (ADR 0003): the
   documentary vocabulary, the `documentary?` membership predicate, the
   plugin's shape, and the `title-required` validation rule in
   isolation. The end-to-end ingest → normalize → validate → report path
   lives in `palomar.canonical-integration-test`."
  (:require [clojure.test :refer [deftest is testing]]
            [palomar.model :as model]
            [palomar.plugins :as plugins]
            [palomar.plugins.canonical :as canonical]
            [palomar.rules :as rules]
            [palomar.runtime :as runtime]))

;; ---------------------------------------------------------------------------
;; Documentary vocabulary
;; ---------------------------------------------------------------------------

(deftest documentary-vocabulary-is-the-adr-0003-set
  (testing "exactly the documentary predicates from ADR 0003 §Decision (eight + the disciplined uniform-title addition)"
    (is (= #{:canon/title :canon/uniform-title :canon/identifier :canon/agent
             :canon/date :canon/relation :canon/note :canon/digital-object :canon/loss-marker}
           canonical/documentary-vocabulary))
    (is (= 9 (count canonical/documentary-vocabulary))))
  (testing "uniform-title is documentary (the work's controlled title), distinct from the transcribed title"
    (is (canonical/documentary? :canon/uniform-title))))

(deftest lang-is-not-a-documentary-predicate
  (testing ":canon/lang is a fragment qualifier (ADR 0011), not a documentary predicate"
    (is (not (contains? canonical/documentary-vocabulary :canon/lang)))
    (is (not (canonical/documentary? :canon/lang)))))

(deftest documentary?-membership
  (testing "true for each of the eight"
    (doseq [p canonical/documentary-vocabulary]
      (is (canonical/documentary? p))))
  (testing "false for structural, native-source, qualifier, and unknown predicates"
    (is (not (canonical/documentary? :meta/id)))         ; structural
    (is (not (canonical/documentary? :dc/title)))        ; native source
    (is (not (canonical/documentary? :canon/lang)))      ; qualifier coord
    (is (not (canonical/documentary? :canon/unknown))))) ; not in the set

;; ---------------------------------------------------------------------------
;; Plugin shape + registration
;; ---------------------------------------------------------------------------

(deftest plugin-is-schema-valid-and-registerable
  (testing "the plugin conforms to the closed Plugin schema and registers under its id"
    (is (plugins/valid-plugin? canonical/plugin))
    (let [reg (plugins/register plugins/empty-registry canonical/plugin)]
      (is (= canonical/plugin (plugins/lookup reg :palomar/canonical))))))

(deftest plugin-rules-pool-and-compile
  (testing "all-rules surfaces the canonical rules, which deep-compile cleanly"
    (let [reg    (plugins/register plugins/empty-registry canonical/plugin)
          pooled (plugins/all-rules reg)]
      (is (= canonical/rules pooled))
      (is (seq pooled))
      ;; compile-rules deep-validates each rule and throws on a malformed
      ;; one — this is the test-time check that replaces a load-time guard.
      (is (= (count pooled) (count (rules/compile-rules pooled)))))))

;; ---------------------------------------------------------------------------
;; title-required rule (in isolation)
;; ---------------------------------------------------------------------------

(defn- validate
  "Run the canonical rules' :validate phase against `record`, returning
   the enriched record."
  [record]
  (:record (runtime/run-phase record (rules/compile-rules canonical/rules) :validate)))

(deftest title-required-fires-when-title-absent
  (testing "a record with no :canon/title gets exactly one :missing-title warning"
    (let [enriched (validate (model/record {:id :record/r1 :kind :book}))
          diags    (:diagnostics enriched)]
      (is (= 1 (count diags)))
      (let [d (first diags)]
        (is (= :missing-title (:code d)))
        (is (= :warning (:severity d)))
        (is (= :record/r1 (:subject d)))
        (is (= :rule.canonical/title-required (get-in d [:provenance :rule])))
        (is (= :validate (get-in d [:provenance :pass])))))))

(deftest title-required-silent-when-title-present
  (testing "a record carrying :canon/title produces no diagnostic"
    (let [enriched (validate
                    (model/record
                     {:id :record/r1 :kind :book
                      :assertions [(model/assertion
                                    {:subject   :record/r1
                                     :predicate :canon/title
                                     :value     "Les Misérables"})]}))]
      (is (empty? (:diagnostics enriched))))))

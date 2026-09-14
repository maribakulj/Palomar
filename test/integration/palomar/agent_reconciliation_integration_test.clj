(ns palomar.agent-reconciliation-integration-test
  "ADR 0018 demonstration on real BnF data: the 30 Madame Bovary manifestations
   carry Flaubert's ISNI (100 $1), so cross-record agent reconciliation collapses
   them to ONE certified agent — the authority-id path, exact and D7-`:asserted`,
   in contrast to the string-key recall ceiling measured for works
   (`docs/eval/entity-resolution.md`)."
  (:require [clojure.test :refer [deftest is testing]]
            [palomar.plugins :as plug]
            [palomar.plugins.intermarc :as intermarc]
            [palomar.plugins.intermarc.wemi :as wemi]
            [palomar.plugins.mapping :as mapping]
            [palomar.reconcile :as rec]
            [palomar.runtime :as runtime]))

(def ^:private fixture
  "test/fixtures/documentary/intermarc/sru/intermarcXchange/bib-flaubert-madame-bovary-start1-max30.xml")

(defn- wemi-records []
  (let [reg      (plug/register plug/empty-registry intermarc/plugin)
        compiled (mapping/compile-mappings (plug/all-mappings reg)
                                           (plug/effective-transforms reg))]
    (->> (intermarc/ingest (slurp fixture) {})
         (mapv #(wemi/with-identified-agent
                  (wemi/derive-wemi (:record (runtime/run-phase % compiled :normalize))))))))

(deftest madame-bovary-reconciles-to-one-certified-flaubert
  (let [{:keys [agents distinct mentions records]} (rec/reconcile-agents (wemi-records))]
    (testing "every ISNI-bearing manifestation collapses to a single reconciled agent"
      (is (= 1 distinct))
      (is (>= mentions 28))                       ; the manifestations carrying 100 $1
      (is (= mentions records))                   ; one identified agent per record
      (let [flaubert (first agents)]
        (is (= "https://isni.org/isni/0000000122762442" (:iri flaubert)))
        (is (= "Flaubert, Gustave" (:label flaubert)))
        (is (= mentions (:mentions flaubert)))
        (is (= 1 (count agents)))))))             ; one reconciled agent, shared across the records

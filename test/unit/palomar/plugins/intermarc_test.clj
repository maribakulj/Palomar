(ns palomar.plugins.intermarc-test
  "Unit tests for the INTERMARC-SRU importer (WP-4 slice 1, ADR 0007), run
   against the real BnF Madame Bovary InterMARCXChange fixture."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.model :as model]
            [palomar.plugins :as plugins]
            [palomar.plugins.intermarc :as intermarc]
            [palomar.plugins.mapping :as mapping]
            [palomar.runtime :as runtime]))

(def fixture
  "test/fixtures/documentary/intermarc/sru/intermarcXchange/bib-flaubert-madame-bovary-start1-max30.xml")

(def records (intermarc/ingest (slurp fixture) {}))

(defn- by-id [recs id] (first (filter #(= id (:id %)) recs)))
(defn- vals-for [record pred]
  (mapv :value (filter #(= pred (:predicate %)) (:assertions record))))
(defn- one [record pred] (first (vals-for record pred)))

(deftest parses-every-record-cleanly
  (is (= 30 (count records)))
  (is (every? model/valid-record? records))
  (is (every? model/record-consistent? records)))

(deftest record-id-from-ark-and-kind
  (is (some #(= :bnf/cb304403926 (:id %)) records))
  (is (every? #(= :intermarc/bibliographic (:kind %)) records))
  (testing ":source keeps the original ARK"
    (is (= "ark:/12148/cb304403926" (:source (by-id records :bnf/cb304403926))))))

(deftest extracts-wemi-relevant-subfields
  (let [r (by-id records :bnf/cb304403926)]
    (is (= "Flaubert"             (one r :intermarc/f100_a)))
    (is (= "11902894"             (one r :intermarc/f100_3)))    ; author authority id
    (is (= "ISNI0000000122762442" (one r :intermarc/f100_1)))   ; author ISNI
    (is (= "11938746"             (one r :intermarc/f145_3)))    ; the embedded Work link
    (is (= "Madame Bovary"        (one r :intermarc/f245_a)))
    (testing "controlfield 001 is captured as :intermarc/f001"
      (is (str/starts-with? (one r :intermarc/f001) "FRBNF")))))

(deftest repeated-subfields-keep-multiplicity
  (testing "cb304403926's 245 carries two $f — both survive as assertions"
    (is (<= 2 (count (vals-for (by-id records :bnf/cb304403926) :intermarc/f245_f))))))

(deftest importer-honours-the-adr-0007-contract
  (testing ":file source"
    (let [{:keys [records diagnostics]}
          (intermarc/importer {} {:source/kind :file :source/value fixture})]
      (is (= 30 (count records)))
      (is (= [] diagnostics))))
  (testing ":string source"
    (is (= 30 (count (:records (intermarc/importer
                                {} {:source/kind :string :source/value (slurp fixture)})))))))

(deftest plugin-is-valid-and-registerable
  (is (plugins/valid-plugin? intermarc/plugin))
  (let [reg (plugins/register plugins/empty-registry intermarc/plugin)]
    (is (= intermarc/plugin (plugins/lookup reg :palomar/intermarc)))))

(deftest normalises-the-bibliographic-core-to-the-canonical-floor
  (testing "the INTERMARC->canonical mapping lifts 245/260/001/003 onto :canon/* (for round-trip export)"
    (let [reg      (plugins/register plugins/empty-registry intermarc/plugin)
          compiled (mapping/compile-mappings (plugins/all-mappings reg)
                                             (plugins/effective-transforms reg))
          showcase (first (filter #(= "ark:/12148/cb304403926" (:source %)) records))
          canon    (:record (runtime/run-phase showcase compiled :normalize))
          vals     (fn [p] (->> (:assertions canon)
                                (filter #(and (= p (:predicate %)) (string? (:value %))))
                                (map :value) set))]
      (is (= 5 (count intermarc/mapping)))
      (is (= #{"Madame Bovary"} (vals :canon/title)))
      (is (= #{"Flaubert, Gustave"} (vals :canon/agent)))          ; controlled 100 $a + $m
      (is (seq (vals :canon/date)))                                 ; 260 $d (varies by edition)
      (is (seq (vals :canon/identifier)))                           ; 001 / 003
      (testing "the native :intermarc/* assertions are retained (derive-wemi still reads them)"
        (is (some #(= :intermarc/f145_3 (:predicate %)) (:assertions canon)))))))

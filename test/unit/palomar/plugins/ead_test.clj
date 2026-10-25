(ns palomar.plugins.ead-test
  "EAD finding aids (ADR 0039): one record per unit, read in either EAD
   namespace, with what is not read reported, and an archival view that keeps
   the hierarchy and mints no Work."
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [palomar.convert :as convert]
            [palomar.plugins.ead :as ead]
            [palomar.plugins.lrmoo.view :as view]))

(def ^:private ead3 "test/fixtures/documentary/ead/ead3/ead3_multi_level_optimum.xml")
(def ^:private ead2002 "test/fixtures/documentary/ead/ead2002/tnah_ead_devoir_genero.xml")

(defn- canon [r p] (keep #(when (and (= p (:predicate %)) (string? (:value %))) (:value %)) (:assertions r)))

(deftest every-unit-is-a-record
  (let [{:keys [records diagnostics]} (ead/importer {} (slurp ead3))
        fonds (first records)]
    (is (= 6 (count records)) "the archdesc and its five components")
    (is (= "Lowell Thomas Papers" (some #(when (= :ead/unittitle (:predicate %)) (:value %)) (:assertions fonds))))
    (is (every? #(= (:id fonds) (some (fn [a] (when (= :ead/parent (:predicate a)) (:value a))) (:assertions %)))
                (filter #(re-matches #"d[0-9a-f]+-0-\d+" (name (:id %))) records))
        "a series names the fonds as its parent")
    (testing "what the importer does not read is reported"
      (is (contains? (set (map #(get-in % [:detail :loss/source-field]) diagnostics)) :ead/controlaccess)))
    (testing "each value says where it was read"
      (is (re-find #"archdesc'\]\[1\]/\*\[local-name\(\)='did'\]\[1\]/\*\[local-name\(\)='unittitle'\]"
                   (get-in (first (filter #(= :ead/unittitle (:predicate %)) (:assertions fonds)))
                           [:provenance :evidence 0 :selector :value]))))))

(deftest ead-2002-reads-too
  (let [{:keys [records]} (convert/to-wemi :ead {} (slurp ead2002))]
    (is (= 32 (count records)))
    (is (= ["Cité internationale universitaire de Paris (CIUP)"] (canon (first records) :canon/agent)))
    (is (= ["20090013"] (canon (first records) :canon/identifier)))))

(deftest a-unit-id-does-not-depend-on-the-finding-aid-s-own-identifier
  (testing "the three EAD3 samples all say recordid 1.1, yet their units are distinct"
    (let [ids (fn [f] (set (map :id (:records (ead/importer {} (slurp f))))))]
      (is (empty? (set/intersection (ids ead3) (ids "test/fixtures/documentary/ead/ead3/ead3_single_level_optimum.xml")))))))

(deftest the-archival-view
  (let [{:keys [records]} (convert/to-wemi :ead {} (slurp ead3))
        [fonds series] records
        m (comp :id first view/manifestations)]
    (is (every? #(empty? (view/works %)) records) "a unit is no edition: no Work")
    (is (every? #(empty? (view/expressions %)) records))
    (is (some #(and (= (m series) (:subject %)) (= :crm/P106i_forms_part_of (:predicate %))
                    (= (m fonds) (get-in % [:value :value/target])) (= :asserted (:status %)))
              (:assertions series))
        "a series' Manifestation forms part of the fonds'")
    (testing "what the view leaves out is a projection loss"
      (is (some #(and (= :projection (get-in % [:detail :loss/edge])) (= :canon/agent (get-in % [:detail :loss/source-field])))
                (:diagnostics fonds))))))

(deftest a-finding-aid-converts-to-every-format
  (doseq [to [:ntriples :crm-only :linked-art :dc :marc21 :mods :trig]]
    (is (string? (:output (convert/convert {:from :ead :to to :source (slurp ead3)}))) (str to))))

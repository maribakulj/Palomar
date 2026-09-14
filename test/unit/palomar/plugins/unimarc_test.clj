(ns palomar.plugins.unimarc-test
  "UNIMARC spoke (WP-4) on real BnF SRU data — the third MARC-family importer.
   Imports the diffusion-format records, normalises the bibliographic core to the
   canonical floor, and checks the UNIMARC-specific tag mapping (200/7xx/210/010)
   on a concrete record plus corpus-level aggregates."
  (:require [clojure.test :refer [deftest is testing]]
            [palomar.plugins :as plug]
            [palomar.plugins.lrmoo.project :as project]
            [palomar.plugins.lrmoo.view :as view]
            [palomar.plugins.mapping :as mapping]
            [palomar.plugins.unimarc :as unimarc]
            [palomar.runtime :as runtime]))

(def ^:private verne "test/fixtures/documentary/unimarc/sru/bnf-sru-verne-unimarc.xml")
(def ^:private hugo  "test/fixtures/documentary/unimarc/sru/bnf-sru-hugo-unimarc.xml")
(def ^:private flaubert "test/fixtures/documentary/unimarc/sru/bnf-sru-flaubert-unimarc.xml")

(defn- normalized [path]
  (let [reg      (plug/register plug/empty-registry unimarc/plugin)
        compiled (mapping/compile-mappings (plug/all-mappings reg)
                                           (plug/effective-transforms reg))]
    (mapv #(:record (runtime/run-phase % compiled :normalize))
          (unimarc/ingest (slurp path) {}))))

(defn- canon [record pred]
  (set (for [a (:assertions record)
             :when (and (= pred (:predicate a)) (string? (:value a)))]
         (:value a))))

(defn- all-canon [records pred] (set (mapcat #(canon % pred) records)))

(deftest imports-the-verne-corpus-to-the-canonical-floor
  (let [recs (normalized verne)]
    (testing "50 records, id from the ARK (:bnf/<cb>)"
      (is (= 50 (count recs)))
      (is (every? #(= "bnf" (namespace (:id %))) recs)))
    (testing "a concrete record maps the UNIMARC core (200/700/210)"
      (let [r (first (filter #(= :bnf/cb34781444r (:id %)) recs))]
        (is (some? r))
        (is (contains? (canon r :canon/title) "Le Pilote du Danube"))   ; 200 $a
        (is (contains? (canon r :canon/agent) "Verne"))                 ; 700 $a (entry element)
        (is (contains? (canon r :canon/date)  "1985"))))                ; 210 $d
    (testing "corpus-level: titles, the Verne agent and dates are populated"
      (is (pos? (count (all-canon recs :canon/title))))
      (is (contains? (all-canon recs :canon/agent) "Verne"))
      (is (some #(re-matches #"\d{4}" %) (all-canon recs :canon/date))))))

(deftest the-mapping-is-unimarc-shaped-not-marc21-shaped
  (testing "native assertions use UNIMARC tags (200/700/210), not MARC21's (245/100/260)"
    (let [recs   (unimarc/ingest (slurp hugo) {})
          preds  (set (mapcat #(map :predicate (:assertions %)) recs))]
      (is (contains? preds :unimarc/f200_a))                ; UNIMARC title
      (is (contains? preds :unimarc/f700_a))                ; UNIMARC author entry element
      (is (not (contains? preds :unimarc/f245_a))))))       ; not the MARC21 title tag

(deftest the-plugin-mapping-ids-are-distinctive
  (testing "every UNIMARC mapping id is namespaced :map/unimarc-* (composable spokes, ADR 0009)"
    (is (every? #(= "map" (namespace (:mapping/id %))) unimarc/mapping))
    (is (every? #(re-find #"^unimarc-" (name (:mapping/id %))) unimarc/mapping))))

(deftest uniform-title-500-bridges-editions-and-a-translation-to-one-work
  (testing "UNIMARC 500 (titre uniforme) maps to :canon/uniform-title and unifies variants"
    (let [recs   (normalized flaubert)
          idiot  (filter #(contains? (canon % :canon/uniform-title) "L'idiot de la famille") recs)]
      (is (<= 10 (count idiot)))                                  ; 11 editions carry the uniform title
      (testing "their transcribed 200 titles genuinely vary (incl. a German translation)"
        (let [titles (all-canon idiot :canon/title)]
          (is (contains? titles "L'Idiot de la famille"))
          (is (some #(re-find #"(?i)idiot der familie" %) titles))))   ; the German translation
      (testing "projected, those varying editions collapse to a single Work via the uniform title"
        (let [works (distinct (keep #(:id (first (view/works (project/project %)))) idiot))]
          (is (= 1 (count works))))))))

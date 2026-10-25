(ns palomar.plugins.lrmoo.wemi-rules-test
  "The declarative WEMI chain (ADR 0035) against `project`, the code that
   computes it: on every fixture record with one title, at most one creator
   and no title language, the two build the same graph."
  (:require [clojure.test :refer [deftest is testing]]
            [palomar.convert :as convert]
            [palomar.plugins.agents :as agents]
            [palomar.plugins.lrmoo.project :as project]
            [palomar.plugins.lrmoo.wemi-rules :as wemi-rules]
            [palomar.runtime :as runtime]))

(def ^:private fixtures
  {:intermarc    "test/fixtures/documentary/intermarc/sru/intermarcXchange/bib-flaubert-madame-bovary-start1-max30.xml"
   :intermarc-ng "test/fixtures/documentary/intermarc-ng/examples/fleurs-du-mal-oemi.xml"
   :unimarc      "test/fixtures/documentary/unimarc/sru/bnf-sru-verne-unimarc.xml"
   :unimarc-2    "test/fixtures/documentary/unimarc/sru/bnf-sru-flaubert-unimarc.xml"
   :dc           "test/fixtures/documentary/dublin-core/w3c_dc_example1.xml"
   :marc21       "test/fixtures/documentary/marc21/marcxml/loc_collection.xml"
   :mods         "test/fixtures/documentary/mods/loc_mods_book.xml"
   :iiif         "test/fixtures/documentary/iiif/manifest_book_simple.json"})

(defn- wemi? [k] (= "lrmoo" (namespace k)))

(defn- floor
  "`record` without its WEMI view: what the projection starts from."
  [record]
  (let [ents (set (keep #(when (wemi? (:kind %)) (:id %)) (:entities record)))]
    (-> record
        (update :entities (partial filterv #(not (wemi? (:kind %)))))
        (update :assertions (partial filterv #(not (or (wemi? (:predicate %)) (ents (:subject %))))))
        (dissoc :diagnostics))))

(defn- graph
  "The WEMI graph of `record`: its entities and claims, provenance aside."
  [record]
  {:entities   (set (keep #(when (wemi? (:kind %)) [(:id %) (:kind %)]) (:entities record)))
   :assertions (set (keep #(when (wemi? (:predicate %)) [(:subject %) (:predicate %) (:value %) (:status %)])
                          (:assertions record)))})

(defn- in-scope?
  "One string title, flat on the record, at most one uniform title, at most one
   creator, and no title language: the records `project` does not choose for."
  [r]
  (let [lits (fn [p] (distinct (keep #(when (and (= p (:predicate %)) (string? (:value %))) [(:subject %) (:value %)])
                                     (:assertions r))))]
    (and (<= (count (lits :canon/title)) 1)
         (every? #(= (:id r) (first %)) (lits :canon/title))
         (seq (lits :canon/title))
         (<= (count (lits :canon/uniform-title)) 1)
         (every? #(= (:id r) (first %)) (lits :canon/uniform-title))
         (<= (count (agents/creators r)) 1)
         (not-any? #(= :canon/lang (:predicate %)) (:assertions r)))))

(deftest the-rules-build-the-graph-project-builds
  (let [checked (atom 0)]
    (doseq [[k path] fixtures
            :let [from (if (= :unimarc-2 k) :unimarc k)]
            r (:records (convert/to-wemi from (if (= :dc k) {:record-id :dc/x} {}) (slurp path)))
            :let [f (floor r)]
            :when (in-scope? f)]
      (swap! checked inc)
      (testing (str k " " (:id r))
        (is (= (graph (project/project f))
               (graph (:record (runtime/run-phase f wemi-rules/compiled :infer)))))))
    (is (< 100 @checked) "the equivalence covers most of the fixture corpus")))

(deftest the-creator-rules-follow-the-role
  (let [base {:id :rec/one :kind :test
              :assertions [{:subject :rec/one :predicate :canon/title :value "Les Misérables"}]}
        run  #(graph (:record (runtime/run-phase % wemi-rules/compiled :infer)))
        with (fn [& agents] (update base :assertions into agents))]
    (testing "an agent of no role keys the Work when no agent is the creator"
      (is (= 1 (count (filter #(= :lrmoo/F1_Work (second %)) (:entities (run (with {:subject :rec/one :predicate :canon/agent :value "Hugo"})))))))
      (is (= (graph (project/project (with {:subject :rec/one :predicate :canon/agent :value "Hugo"})))
             (run (with {:subject :rec/one :predicate :canon/agent :value "Hugo"})))))
    (testing "a publisher alone keys no Work"
      (let [rec (with {:subject :rec/one :predicate :canon/agent :value {:value/kind :reference :value/target :frag/p}}
                      {:subject :frag/p :predicate :canon/agent :value "Gosselin"}
                      {:subject :frag/p :predicate :canon/role :value :publisher})]
        (is (empty? (filter #(= :lrmoo/F1_Work (second %)) (:entities (run rec)))))
        (is (= (graph (project/project rec)) (run rec)))))))

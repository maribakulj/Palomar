(ns palomar.ontology-test
  "Every RDF output respects the domains and ranges of the vocabularies it uses
   (plan step 2), checked against their own files (CIDOC CRM 7.1.3 RDFS, LRMoo
   1.0 OWL) and the PROV-O Recommendation."
  (:require [clojure.test :refer [deftest is testing]]
            [palomar.convert :as convert]
            [palomar.curate]
            [palomar.model :as model]
            [palomar.plugins.lrmoo.export]
            [palomar.reconcile :as reconcile]
            [palomar.ontology :as onto]
            [palomar.plugins.lrmoo.ntriples :as nt]))

(def ^:private sch (delay (onto/with (onto/schema onto/vendored) onto/prov-o)))

(def ^:private crm "http://www.cidoc-crm.org/cidoc-crm/")
(def ^:private lrm "http://iflastandards.info/ns/lrm/lrmoo/")
(def ^:private T onto/rdf-type)

(defn- problems [ts] (set (map :problem (onto/violations @sch ts))))

(deftest the-checker-is-not-vacuous
  (testing "it reads both vocabularies"
    (is (contains? (:classes @sch) (str crm "E41_Appellation")))
    (is (contains? (:properties @sch) (str lrm "R4_embodies"))))
  (testing "it catches the three domain errors the 2026-10-25 review fixed by hand"
    (is (= #{:range} (problems [["w" T {:iri (str lrm "F1_Work")}] ["n" T {:iri (str lrm "F12_Nomen")}]
                                ["w" (str crm "P1_is_identified_by") {:iri "n"}]]))
        "a Nomen at the end of P1, whose range is E41")
    (is (= #{:domain} (problems [["t" T {:iri (str crm "E55_Type")}]
                                 ["t" (str crm "P190_has_symbolic_content") {:lit "x"}]]))
        "P190 on a Type, which is no E90")
    (is (= #{:domain :range} (problems [["e" T {:iri (str lrm "F2_Expression")}] ["m" T {:iri (str lrm "F3_Manifestation")}]
                                        ["e" (str lrm "R4_embodies") {:iri "m"}]]))
        "R4 the wrong way round"))
  (testing "and an unknown class, an untyped subject, a literal where a class is due"
    (is (= #{:unknown-class} (problems [["x" T {:iri (str crm "E99_Nope")}]])))
    (is (= #{:untyped} (problems [["x" (str crm "P1_is_identified_by") {:iri "y"}]])))
    (is (contains? (problems [["w" T {:iri (str lrm "F1_Work")}] ["w" (str crm "P1_is_identified_by") {:lit "T"}]])
                   :range)))
  (testing "the appellation path is accepted"
    (is (empty? (problems [["w" T {:iri (str lrm "F1_Work")}] ["a" T {:iri (str crm "E41_Appellation")}]
                           ["w" (str crm "P1_is_identified_by") {:iri "a"}]
                           ["a" (str crm "P190_has_symbolic_content") {:lit "T"}]])))))

(def ^:private fixtures
  {:intermarc    "test/fixtures/documentary/intermarc/sru/intermarcXchange/bib-flaubert-madame-bovary-start1-max30.xml"
   :intermarc-ng "test/fixtures/documentary/intermarc-ng/examples/fleurs-du-mal-oemi.xml"
   :unimarc      "test/fixtures/documentary/unimarc/sru/bnf-sru-verne-unimarc.xml"
   :dc           "test/fixtures/documentary/dublin-core/w3c_dc_example1.xml"
   :marc21       "test/fixtures/documentary/marc21/marcxml/loc_collection.xml"
   :mods         "test/fixtures/documentary/mods/loc_mods_book.xml"
   :iiif         "test/fixtures/documentary/iiif/manifest_book_simple.json"})

(deftest every-rdf-output-respects-its-vocabularies
  (testing "for each spoke, the LRMoo, CRM, pure-CRM and PROV-O outputs have no domain or range violation"
    (doseq [from (keys fixtures)
            to   [:ntriples :crm :crm-only :prov]]
      (let [out (:output (convert/convert {:from from :to to :source (slurp (fixtures from))
                                           :opts (if (= from :dc) {:record-id :dc/x} {})}))
            ts  (for [[s p o] (nt/parse out)] [(nt/term-key s) (:iri p) o])
            vs  (onto/violations @sch ts)]
        (is (seq ts) (str from " -> " to " wrote triples"))
        (is (empty? vs) (str from " -> " to ": " (pr-str (take 3 vs))))))))

(deftest the-curation-history-respects-prov-o
  (testing "decisions exported to PROV (used, wasInvalidatedBy, wasRevisionOf, endedAtTime) have no violation"
    (let [rec  (first (:records (convert/to-wemi :dc {:record-id :dc/x} (slurp (fixtures :dc)))))
          old  (first (filter #(= :canon/date (:predicate %)) (:assertions rec)))
          cur  (-> (palomar.curate/curate [rec] palomar.curate/reject-all {:by "curator-1" :at #inst "2026-10-25T12:00:00Z"})
                   :records first
                   (palomar.curate/supersede old (assoc old :value "2007") "date normalised" {:by "curator-1"})
                   :record)
          ts   (for [[s p o] (nt/parse (:output (convert/export-records :prov [cur])))] [(nt/term-key s) (:iri p) o])]
      (is (empty? (onto/violations @sch ts)) (pr-str (take 3 (onto/violations @sch ts)))))))

(deftest an-aligned-record-respects-its-vocabularies
  (let [r  (model/record {:id :doc/a :kind :doc
                          :assertions [(model/assertion {:subject :doc/a :predicate :canon/agent :value "Gustave Flaubert"})]})
        [a] (reconcile/align-agents [r] [{:name "Gustave Flaubert" :authority-id "https://isni.org/isni/0000000122762442"
                                          :score 1.0 :certifiable? true}])
        ts  (for [[s p o] (nt/parse (palomar.plugins.lrmoo.export/->ntriples a))] [(nt/term-key s) (:iri p) o])]
    (is (seq ts))
    (is (empty? (onto/violations @sch ts)))))

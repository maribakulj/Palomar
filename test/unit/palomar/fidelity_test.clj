(ns palomar.fidelity-test
  "The occurrence-level fidelity audit (audit S11/S12, test 15): every value a
   conversion does not write must be reported, and a silent omission is caught."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.convert :as convert]
            [palomar.fidelity :as fidelity]
            [palomar.plugins.lrmoo.export :as export]))

(def ^:private fixtures
  {:intermarc    "test/fixtures/documentary/intermarc/sru/intermarcXchange/bib-flaubert-madame-bovary-start1-max30.xml"
   :intermarc-ng "test/fixtures/documentary/intermarc-ng/examples/fleurs-du-mal-oemi.xml"
   :unimarc      "test/fixtures/documentary/unimarc/sru/bnf-sru-verne-unimarc.xml"
   :dc           "test/fixtures/documentary/dublin-core/w3c_dc_example1.xml"
   :marc21       "test/fixtures/documentary/marc21/marcxml/loc_collection.xml"
   :mods         "test/fixtures/documentary/mods/loc_mods_book.xml"
   :iiif         "test/fixtures/documentary/iiif/manifest_book_simple.json"
   :ead          "test/fixtures/documentary/ead/ead3/ead3_multi_level_optimum.xml"
   :eac-cpf      "test/fixtures/documentary/eac-cpf/example_eac_cpf.xml"
   :lido         "test/fixtures/documentary/lido/staedel-lido-wrap.xml"})

(defn- audit [from to]
  (fidelity/audit {:from from :to to :source (slurp (fixtures from))
                   :opts (if (= from :dc) {:record-id :dc/x} {})}))

(deftest every-conversion-accounts-for-every-value
  (testing "for each spoke and each decodable target, every absent value is reported"
    (doseq [from (keys fixtures)
            to   (sort (fidelity/decodable-targets))
            :when (convert/accepts? to (convert/source-resource-type from))]
      (let [{:keys [checked present unaccounted]} (audit from to)]
        (is (pos? checked) (str from " -> " to " checked something"))
        (is (empty? unaccounted)
            (str from " -> " to ": silent loss of " (pr-str (take 5 unaccounted))))
        (is (<= present checked))))))

(deftest a-silent-omission-is-caught
  (testing "an exporter that drops one note without reporting it fails the audit (audit test 15)"
    (let [real   @#'convert/exporters
          broken (update real :dc assoc :render
                         (fn [r] (-> ((get-in real [:dc :render]) r)
                                     (str/replace #"(?s)<dc:description[^>]*>[^<]*</dc:description>" ""))))
          {:keys [unaccounted]} (with-redefs-fn {#'convert/exporters broken}
                                  #(audit :dc :dc))]
      (is (seq unaccounted))
      (is (every? #(contains? #{:canon/note :dc/description} (:predicate %)) unaccounted)))))

(deftest turtle-is-reported-as-unchecked
  (is (:unchecked (audit :dc :turtle))))

(deftest the-prov-audit-checks-out-of-force-assertions-too
  (testing "PROV must record a rejected claim, so the audit counts it and finds it present"
    (let [rec      (first (:records (convert/to-wemi :dc {:record-id :dc/x} (slurp (fixtures :dc)))))
          rejected (update rec :assertions
                           (partial mapv #(if (= :dc/date (:predicate %)) (assoc % :status :rejected) %)))
          strict   (fidelity/audit-record :prov rejected)
          plain    (fidelity/audit-record :jsonld rejected)]
      (is (empty? (:unaccounted strict)))
      (is (> (:checked strict) (:checked plain))))))

(deftest every-relation-survives-in-the-rdf-outputs
  (testing "for each spoke and each N-Triples-family target, every WEMI link is written or reported (ADR 0033)"
    (doseq [from (keys fixtures)
            to   (sort (fidelity/relation-targets))]
      (let [{:keys [checked unaccounted]} (:relations (audit from to))]
        ;; an EAC-CPF agent projects no relation yet (ADR 0045): they are reported at import
        (is (or (pos? checked) (= :eac-cpf from)) (str from " -> " to " checked some relation"))
        (is (empty? unaccounted)
            (str from " -> " to ": silent relation loss " (pr-str (take 3 unaccounted))))))))

(deftest a-dropped-relation-is-caught
  (testing "an export that keeps every title but drops the Work→Expression link fails the relation audit"
    (let [real   @#'convert/exporters
          broken (update real :ntriples assoc :render
                         (fn [r] (->> (str/split-lines ((get-in real [:ntriples :render]) r))
                                      (remove #(str/includes? % "R3_is_realised_in"))
                                      (str/join "\n"))))
          {:keys [relations unaccounted]} (with-redefs-fn {#'convert/exporters broken}
                                            #(audit :intermarc :ntriples))]
      (is (empty? unaccounted) "no string value is lost: the string check alone sees nothing")
      (is (seq (:unaccounted relations)))
      (is (every? #(= :lrmoo/R3_is_realised_in (:predicate %)) (:unaccounted relations))))))

(deftest the-pure-crm-export-carries-relations-by-their-super-property
  (let [{:keys [checked present unaccounted]} (:relations (audit :intermarc :crm-only))]
    (is (pos? checked))
    (is (= checked present) "R3/R4/R7 are written as P130/P165/P128, which counts as present")
    (is (empty? unaccounted))))

(deftest the-right-text-on-the-wrong-node-is-caught
  (testing "an exporter that writes a Work's title on the Manifestation's node fails the RDF audit (post-audit)"
    (let [real   @#'convert/exporters
          moved  (fn [r] (let [doc   ((get-in real [:ntriples :render]) r)
                               work  (some #(when (= :lrmoo/F1_Work (:kind %)) (export/entity-iri (:id %))) (:entities r))
                               manif (some #(when (= :lrmoo/F3_Manifestation (:kind %)) (export/entity-iri (:id %))) (:entities r))]
                           (if (and work manif) (str/replace doc (str "<" work "> <http://www.cidoc-crm.org/cidoc-crm/P1_is_identified_by>")
                                                             (str "<" manif "> <http://www.cidoc-crm.org/cidoc-crm/P1_is_identified_by>"))
                               doc)))
          broken (update real :ntriples assoc :render moved)
          {:keys [unaccounted]} (with-redefs-fn {#'convert/exporters broken} #(audit :intermarc :ntriples))]
      (is (seq unaccounted) "the title still appears in the file, but not on its Work")
      (is (every? #(= :lrmoo/R33_has_string (:predicate %)) unaccounted)))))

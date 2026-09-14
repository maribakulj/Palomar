(ns palomar.lrmoo-vertical-integration-test
  "The sibling promised in `integration_test` (\"begins with import, ends with
   export\"): the full vertical on real BnF data —

     INTERMARC-SRU import (ADR 0007)
       -> WEMI derivation (ADR 0016)
         -> LRMoo RDF export (ADR 0007 / 0013)

   with loss reported at *both* edges (ADR 0015) and the Manifestation exported as
   its real data.bnf ARK. Exercises the plugin *contracts* (importer / exporter
   closures), not just internal fns, against the Madame Bovary fixture."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.diagnostics :as dx]
            [palomar.loss-report :as lr]
            [palomar.model :as model]
            [palomar.plugins.intermarc :as intermarc]
            [palomar.plugins.intermarc.wemi :as wemi]
            [palomar.plugins.lrmoo.crm :as crm]
            [palomar.plugins.lrmoo.export :as export]
            [palomar.plugins.lrmoo.view :as view]))

(def fixture
  "test/fixtures/documentary/intermarc/sru/intermarcXchange/bib-flaubert-madame-bovary-start1-max30.xml")

;; import (ADR 0007 contract) -> derive WEMI (ADR 0016): the vertical, per record.
(def imported (intermarc/importer {} {:source/kind :file :source/value fixture}))
(def derived (mapv wemi/derive-wemi (:records imported)))
(defn- showcase [] (first (filter #(= "ark:/12148/cb304403926" (:source %)) derived)))

(deftest import-honours-the-contract-and-parses-cleanly
  (testing "the importer returns the ADR 0007 shape and consistent records"
    (is (= 30 (count (:records imported))))
    (is (= [] (:diagnostics imported)))
    (is (every? model/valid-record? (:records imported)))
    (is (every? model/record-consistent? (:records imported)))))

(deftest wemi-derivation-builds-and-links-the-wemi-graph
  (let [r (showcase)]
    (is (= 1 (count (view/manifestations r))))
    (is (= 1 (count (view/expressions r))))
    (is (= 1 (count (view/works r))))
    (testing "Work -R3-> Expression <-R4- Manifestation, traversable"
      (let [w (:id (first (view/works r)))
            e (:id (first (view/expressions r)))
            m (:id (first (view/manifestations r)))]
        (is (= [e] (view/expressions-of r w)))
        (is (= [e] (view/expression-of r m)))))
    (testing "the vertical stays consistent and reports import-edge loss"
      (is (model/record-consistent? r))
      (let [imp (dx/losses (:diagnostics r))]
        (is (seq imp))
        (is (every? #(= :import (get-in % [:detail :loss/edge])) imp))))))

(deftest export-emits-real-iris-and-reports-export-loss
  (let [{:keys [output diagnostics]} (export/exporter {} derived)]
    (testing "the RDF carries the WEMI types and both links"
      (is (str/includes? output "http://iflastandards.info/ns/lrm/lrmoo/F3_Manifestation"))
      (is (str/includes? output "http://iflastandards.info/ns/lrm/lrmoo/R4_embodies"))
      (is (str/includes? output "http://iflastandards.info/ns/lrm/lrmoo/R3_is_realised_in")))
    (testing "the showcase Manifestation node is its real data.bnf ARK (not a urn)"
      (is (str/includes? output "<http://data.bnf.fr/ark:/12148/cb304403926>")))
    (testing "export-edge loss is reported for the dropped native predicates (ADR 0015)"
      (is (seq diagnostics))
      (is (every? #(= :loss/dropped (:code %)) diagnostics))
      (is (every? #(= :export (get-in % [:detail :loss/edge])) diagnostics))
      (is (contains? (set (map #(get-in % [:detail :loss/source-field]) diagnostics))
                     :intermarc/f245_a)))))

(deftest the-vertical-is-idempotent
  (testing "re-deriving WEMI over the output mints nothing new (ADR 0008)"
    (let [r1 (showcase)
          r2 (wemi/derive-wemi r1)]
      (is (= (:entities r1) (:entities r2)))
      (is (= (:assertions r1) (:assertions r2))))))

(deftest the-vertical-also-down-projects-to-cidoc-crm
  (testing "the WEMI-derived manifestation emits CRM-compatible triples, additively (museum spoke)"
    (let [nt (crm/->ntriples (showcase))]
      (is (str/includes? nt "iflastandards.info/ns/lrm/lrmoo/F3_Manifestation"))  ; LRMoo kept
      (is (str/includes? nt "cidoc-crm/E89_Propositional_Object"))                ; Work  -> E89
      (is (str/includes? nt "cidoc-crm/E73_Information_Object"))                   ; E/M   -> E73
      (is (str/includes? nt "cidoc-crm/P165_incorporates")))))                    ; R4    -> P165

(deftest a-conversion-loss-report-accounts-for-both-edges
  (testing "the institution-facing report aggregates import + export loss per source field (ADR 0015)"
    (let [import-loss (dx/collect-many derived)
          export-loss (:diagnostics (export/exporter {} derived))
          report      (lr/conversion-report (concat import-loss export-loss)
                                            {:records (count derived)})
          text        (lr/format-conversion-report report)]
      (is (pos? (:total report)))
      (is (= 30 (:records report)))
      (testing "both edges are accounted, with the native field that was dropped"
        (is (pos? (get-in report [:by-edge :import :total])))
        (is (pos? (get-in report [:by-edge :export :total])))
        (is (contains? (set (:source-fields report)) :intermarc/f100_a)))
      (testing "distinct losses collapse the cross-edge double-count (audit R3)"
        ;; on the real corpus the same native field is lost at both edges, so the
        ;; deduped count is strictly below the field×edge event total.
        (is (< (:distinct-losses report) (:total report)))
        (is (str/includes? text (str (:distinct-losses report) " distinct losses"))))
      (testing "the rendering names both edges"
        (is (str/includes? text "import edge"))
        (is (str/includes? text "export edge"))))))

(ns palomar.plugins.intermarc.wemi-test
  "End-to-end on real BnF data (WP-3, ADR 0016): INTERMARC import → WEMI derivation
   → typed view → RDF export, plus lookup-based clustering and idempotency."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.diagnostics :as dx]
            [palomar.model :as model]
            [palomar.plugins.intermarc :as intermarc]
            [palomar.plugins.intermarc.wemi :as wemi]
            [palomar.plugins.lrmoo.export :as export]
            [palomar.plugins.lrmoo.view :as view]))

(def fixture
  "test/fixtures/documentary/intermarc/sru/intermarcXchange/bib-flaubert-madame-bovary-start1-max30.xml")
(def records (intermarc/ingest (slurp fixture) {}))

(defn- by-id [recs id] (first (filter #(= id (:id %)) recs)))
(defn- has-145? [r] (some #(= :intermarc/f145_3 (:predicate %)) (:assertions r)))

(deftest manifestation-and-expression-minted-and-linked
  (let [r (wemi/derive-wemi (by-id records :bnf/cb304403926))]
    (is (model/valid-record? r))
    (is (model/record-consistent? r))
    (is (= 1 (count (view/manifestations r))))
    (is (= 1 (count (view/expressions r))))
    (testing "the Manifestation embodies the Expression (R4), both directions"
      (let [m (:id (first (view/manifestations r)))
            e (:id (first (view/expressions r)))]
        (is (= [e] (view/expression-of r m)))
        (is (= [m] (view/manifestations-of r e)))))))

(deftest clustering-by-the-embedded-expression-id
  (testing "manifestations sharing f145_3 collapse to ONE Expression and Work (no batch state)"
    (let [derived (map wemi/derive-wemi (filter has-145? records))
          exprs   (->> derived (map #(:id (first (view/expressions %)))) distinct)
          works   (->> derived (keep #(:id (first (view/works %)))) distinct)]
      (is (<= 20 (count (filter has-145? records))))  ; most of the 30 carry the link
      (is (= 1 (count exprs)))                          ; ...one Expression (by f145_3)
      (is (= 1 (count works))))))                       ; ...one Work (by author + title)

(deftest work-minted-and-realised-in-expression
  (let [r (wemi/derive-wemi (by-id records :bnf/cb304403926))]
    (is (= 1 (count (view/works r))))
    (testing "the Work is realised in the Expression (R3), both directions"
      (let [w (:id (first (view/works r)))
            e (:id (first (view/expressions r)))]
        (is (= [e] (view/expressions-of r w)))
        (is (= [w] (view/work-of r e)))))))

(deftest fallback-record-gets-manifestation-only
  (testing "a record without f145 yields a Manifestation but no Expression (bridging is future)"
    (let [r (wemi/derive-wemi (first (remove has-145? records)))]
      (is (= 1 (count (view/manifestations r))))
      (is (empty? (view/expressions r))))))

(deftest wemi-derivation-is-idempotent
  (testing "re-running mints nothing new (ADR 0008)"
    (let [r1 (wemi/derive-wemi (by-id records :bnf/cb304403926))
          r2 (wemi/derive-wemi r1)]
      (is (= (:entities r1) (:entities r2)))
      (is (= (:assertions r1) (:assertions r2)))
      (is (= (:diagnostics r1) (:diagnostics r2))))))

(deftest exports-to-rdf
  (testing "the WEMI-derived manifestation exports as N-Triples (F3 type, F2 type, R4 link)"
    (let [nt (export/->ntriples (wemi/derive-wemi (by-id records :bnf/cb304403926)))]
      (is (str/includes? nt "lrmoo/F3_Manifestation"))
      (is (str/includes? nt "lrmoo/F2_Expression"))
      (is (str/includes? nt "lrmoo/R4_embodies")))))

(deftest manifestation-carries-its-real-ark-iri
  (testing "the Manifestation node exports as its data.bnf ARK, not a urn:palomar id"
    (let [r    (wemi/derive-wemi (by-id records :bnf/cb304403926))
          manif (first (view/manifestations r))
          nt   (export/->ntriples r)]
      (is (= "http://data.bnf.fr/ark:/12148/cb304403926" (:iri manif)))
      (testing "the ARK is the subject of the F3 type and R4 triples; no urn for the manifestation"
        (is (str/includes? nt "<http://data.bnf.fr/ark:/12148/cb304403926> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://iflastandards.info/ns/lrm/lrmoo/F3_Manifestation>"))
        (is (str/includes? nt "<http://data.bnf.fr/ark:/12148/cb304403926> <http://iflastandards.info/ns/lrm/lrmoo/R4_embodies>")))
      (testing "the Expression has no authority IRI yet (needs check-char resolution) -> urn fallback"
        (is (nil? (:iri (first (view/expressions r)))))))))

(deftest wemi-derivation-reports-loss
  (let [r  (wemi/derive-wemi (by-id records :bnf/cb304403926))
        ls (dx/losses (:diagnostics r))]
    (is (seq ls))
    (is (every? #(= :loss/dropped (:code %)) ls))
    (is (every? #(= :import (get-in % [:detail :loss/edge])) ls))
    (testing "mapped fields are NOT reported as loss, dropped ones are"
      (let [fields (set (map #(get-in % [:detail :loss/source-field]) ls))]
        (is (not (contains? fields :intermarc/f145_3)))   ; projected -> Expression id
        (is (not (contains? fields :intermarc/f145_a)))   ; projected -> R33 title
        (is (contains? fields :intermarc/f100_a))))       ; author name -> dropped (for now)
    (testing "loss-summary is a per-category / per-edge breakdown"
      (let [s (dx/loss-summary (:diagnostics r))]
        (is (pos? (:total s)))
        (is (= (:total s) (get-in s [:by-category :dropped])))
        (is (pos? (get-in s [:by-edge :import])))))
    (testing "loss diagnostics keep the record consistent (subject = record id)"
      (is (model/record-consistent? r)))))

(deftest coverage-grows-with-the-projection
  (let [c (wemi/coverage (by-id records :bnf/cb304403926))]
    (is (= 4 (:mapped c)))     ; f145_3, f145_a, f100_3, f245_a (was 2 last slice)
    (is (> (:total c) 10))     ; many INTERMARC fields are present
    (is (< (:pct c) 50))))     ; still partial — the loss report tracks the gap

(deftest commit-policy-asserts-proof-backed-claims
  (testing "the f145-linked, f100_3-backed showcase record: every WEMI claim is :asserted (certified)"
    (let [r        (wemi/derive-wemi (by-id records :bnf/cb304403926))
          lrmoo-as (filter #(= "lrmoo" (namespace (:predicate %))) (:assertions r))]
      (is (seq lrmoo-as))
      (is (every? model/asserted? lrmoo-as))))
  (testing "145 link but creator only a name string (no 100$3) -> Work claims stay :proposed"
    (let [synth (model/record
                 {:id :bnf/synth :kind :intermarc/bibliographic :source "ark:/12148/cbsynth"
                  :assertions [(model/assertion {:subject :bnf/synth :predicate :intermarc/f145_3 :value "999"})
                               (model/assertion {:subject :bnf/synth :predicate :intermarc/f145_a :value "Some Work"})
                               (model/assertion {:subject :bnf/synth :predicate :intermarc/f245_a :value "A Title"})
                               (model/assertion {:subject :bnf/synth :predicate :intermarc/f100_a :value "Anonymous"})]})
          by-pred (group-by :predicate (:assertions (wemi/derive-wemi synth)))]
      (testing "the R4 link (from 145$3) is asserted"
        (is (every? model/asserted? (:lrmoo/R4_embodies by-pred))))
      (testing "the R3 realisation (name-string creator) is proposed"
        (is (seq (:lrmoo/R3_is_realised_in by-pred)))
        (is (every? model/proposed? (:lrmoo/R3_is_realised_in by-pred)))))))

(deftest mints-an-identified-agent-from-the-isni
  (testing "with-identified-agent adds a :crm/E21_Person carrying the ISNI URI (D7-certified id)"
    (let [rec (model/record
               {:id :bnf/x :kind :intermarc/bibliographic
                :assertions [(model/assertion {:subject :bnf/x :predicate :intermarc/f100_1
                                               :value "ISNI0000000122762442"})
                             (model/assertion {:subject :bnf/x :predicate :canon/agent
                                               :value "Flaubert, Gustave"})]})
          out (wemi/with-identified-agent rec)
          agent (first (filter #(= :crm/E21_Person (:kind %)) (:entities out)))]
      (is (some? agent))
      (is (= "https://isni.org/isni/0000000122762442" (:iri agent)))
      (testing "the same ISNI mints the same agent id (content-addressed, ADR 0008)"
        (is (= (:id agent)
               (:id (first (filter #(= :crm/E21_Person (:kind %))
                                   (:entities (wemi/with-identified-agent rec))))))))
      (testing "a record with no ISNI is a no-op"
        (is (empty? (filter #(= :crm/E21_Person (:kind %))
                            (:entities (wemi/with-identified-agent
                                         (model/record {:id :bnf/y :kind :book}))))))))))

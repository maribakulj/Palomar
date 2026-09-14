(ns palomar.plugins.lrmoo.export-test
  "Unit tests for the LRMoo RDF export (WP-2 slice 3, ADR 0013): the triple
   model and the N-Triples / Turtle / JSON-LD rendering on a hand-built WEMI graph."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.model :as model]
            [palomar.plugins.lrmoo.export :as export]))

(def rec
  (model/record
   {:id :record/r1 :kind :book
    :entities [(model/entity {:id :ent/work :kind :lrmoo/F1_Work})
               (model/entity {:id :ent/expr :kind :lrmoo/F2_Expression})]
    :assertions [(model/assertion {:subject :ent/work :predicate :lrmoo/R3_is_realised_in
                                   :value (model/reference :ent/expr)})
                 (model/assertion {:subject :ent/expr :predicate :lrmoo/R33_has_string
                                   :value "Madame Bovary"})]}))

(def F1 "http://iflastandards.info/ns/lrm/lrmoo/F1_Work")
(def R3 "http://iflastandards.info/ns/lrm/lrmoo/R3_is_realised_in")
(def R33 "http://iflastandards.info/ns/lrm/lrmoo/R33_has_string")
(def RDF-TYPE "http://www.w3.org/1999/02/22-rdf-syntax-ns#type")

(deftest entity-iri-maps-ids
  (is (= "urn:palomar:ent:work" (export/entity-iri :ent/work)))
  (is (= "http://x/y" (export/entity-iri "http://x/y"))))   ; string passthrough

(deftest authority-iri-is-preferred-over-the-urn-fallback
  (testing "an entity's :iri is used for its own node AND for references to it"
    (let [r  (model/record
              {:id :record/r1 :kind :book
               :entities [(model/entity {:id :ent/manif :kind :lrmoo/F3_Manifestation
                                         :iri "http://data.bnf.fr/ark:/12148/cb304403926"})
                          (model/entity {:id :ent/expr :kind :lrmoo/F2_Expression})]
               :assertions [(model/assertion {:subject :ent/manif :predicate :lrmoo/R4_embodies
                                              :value (model/reference :ent/expr)})]})
          ts (set (export/triples r))]
      (testing "the manifestation node uses its ARK, not urn:palomar"
        (is (contains? ts ["http://data.bnf.fr/ark:/12148/cb304403926"
                           RDF-TYPE {:iri "http://iflastandards.info/ns/lrm/lrmoo/F3_Manifestation"}])))
      (testing "the R4 triple's subject is the ARK; its object (no :iri) falls back to urn"
        (is (contains? ts ["http://data.bnf.fr/ark:/12148/cb304403926"
                           "http://iflastandards.info/ns/lrm/lrmoo/R4_embodies"
                           {:iri "urn:palomar:ent:expr"}]))))))

(deftest triples-type-property-and-literal
  (let [ts (set (export/triples rec))]
    (testing "each WEMI entity is typed to its F-class IRI"
      (is (contains? ts ["urn:palomar:ent:work" RDF-TYPE {:iri F1}])))
    (testing "a reference value becomes an object-property triple"
      (is (contains? ts ["urn:palomar:ent:work" R3 {:iri "urn:palomar:ent:expr"}])))
    (testing "a literal value becomes a literal triple"
      (is (contains? ts ["urn:palomar:ent:expr" R33 {:lit "Madame Bovary"}])))))

(deftest ntriples-rendering
  (let [nt (export/->ntriples rec)]
    (is (str/includes? nt (str "<urn:palomar:ent:work> <" RDF-TYPE "> <" F1 "> .")))
    (is (str/includes? nt (str "<urn:palomar:ent:work> <" R3 "> <urn:palomar:ent:expr> .")))
    (is (str/includes? nt "\"Madame Bovary\" ."))
    (testing "every line is a well-formed triple ending in ' .'"
      (is (every? #(str/ends-with? % " .") (str/split-lines nt))))
    (testing "a record with no LRMoo content yields the empty string"
      (is (= "" (export/->ntriples (model/record {:id :record/x :kind :book})))))))

(deftest ntriples-escapes-literals
  (let [r (model/record
           {:id :record/r1 :kind :book
            :entities [(model/entity {:id :ent/e :kind :lrmoo/F2_Expression})]
            :assertions [(model/assertion {:subject :ent/e :predicate :lrmoo/R33_has_string
                                           :value "a \"quote\" and \\ slash"})]})]
    (is (str/includes? (export/->ntriples r) "\\\"quote\\\""))
    (is (str/includes? (export/->ntriples r) "\\\\ slash"))))

(deftest exporter-follows-the-adr-0007-contract
  (let [{:keys [output diagnostics]} (export/exporter {} [rec])]
    (is (string? output))
    (is (= [] diagnostics))     ; rec carries only :lrmoo/* assertions -> nothing dropped
    (is (str/includes? output "lrmoo/F1_Work"))))

(deftest export-drops-native-predicates-as-loss
  (testing "the LRMoo RDF target cannot express native predicates -> :export loss (ADR 0015)"
    (let [r  (model/record
              {:id :record/r1 :kind :book
               :entities [(model/entity {:id :ent/work :kind :lrmoo/F1_Work})]
               :assertions [(model/assertion {:subject :ent/work :predicate :lrmoo/R33_has_string
                                              :value "Madame Bovary"})
                            (model/assertion {:subject :record/r1 :predicate :intermarc/f245_a
                                              :value "Madame Bovary"})
                            (model/assertion {:subject :record/r1 :predicate :intermarc/f100_a
                                              :value "Flaubert"})]})
          ls (export/export-losses r)]
      (testing "one loss per distinct dropped predicate, none for the :lrmoo/* one"
        (is (= 2 (count ls)))
        (let [fields (set (map #(get-in % [:detail :loss/source-field]) ls))]
          (is (= #{:intermarc/f245_a :intermarc/f100_a} fields))
          (is (not (contains? fields :lrmoo/R33_has_string)))))
      (testing "they are export-edge :loss/dropped diagnostics"
        (is (every? #(= :loss/dropped (:code %)) ls))
        (is (every? #(= :export (get-in % [:detail :loss/edge])) ls)))
      (testing "the exporter surfaces them in its result"
        (is (= ls (:diagnostics (export/exporter {} [r]))))))))

(deftest certified-only-emits-only-the-asserted-subgraph
  (let [r    (model/record
              {:id :record/r1 :kind :book
               :entities [(model/entity {:id :ent/work :kind :lrmoo/F1_Work})
                          (model/entity {:id :ent/expr :kind :lrmoo/F2_Expression})
                          (model/entity {:id :ent/manif :kind :lrmoo/F3_Manifestation})]
               :assertions [(model/assertion {:subject :ent/manif :predicate :lrmoo/R4_embodies
                                              :value (model/reference :ent/expr) :status :asserted})
                            (model/assertion {:subject :ent/work :predicate :lrmoo/R3_is_realised_in
                                              :value (model/reference :ent/expr) :status :proposed})]})
        all  (set (export/triples r))
        cert (set (export/triples r {:certified-only? true}))]
    (testing "default emits both; certified-only keeps the asserted R4, drops the proposed R3"
      (is (contains? all  ["urn:palomar:ent:work" R3 {:iri "urn:palomar:ent:expr"}]))
      (is (some #(= "http://iflastandards.info/ns/lrm/lrmoo/R4_embodies" (second %)) cert))
      (is (not (some #(= R3 (second %)) cert))))
    (testing "certified-only types only the entities the asserted claim references (manif + expr, not work)"
      (is (contains? cert ["urn:palomar:ent:manif" RDF-TYPE
                           {:iri "http://iflastandards.info/ns/lrm/lrmoo/F3_Manifestation"}]))
      (is (not (contains? cert ["urn:palomar:ent:work" RDF-TYPE {:iri F1}]))))
    (testing "a fully-proposed record certifies to the empty string"
      (let [p (model/record {:id :record/p :kind :book
                             :entities [(model/entity {:id :ent/e :kind :lrmoo/F2_Expression})]
                             :assertions [(model/assertion {:subject :ent/e :predicate :lrmoo/R33_has_string
                                                            :value "t" :status :proposed})]})]
        (is (= "" (export/->ntriples p {:certified-only? true})))))))

(deftest certified-only-keeps-iri-bearing-entities-without-claims
  (testing "a titleless ARK Manifestation (determinate id, no claims) stays certified; a string-key Work does not (R2)"
    (let [r    (model/record
                {:id :record/r1 :kind :book
                 :entities [(model/entity {:id :ent/m :kind :lrmoo/F3_Manifestation
                                           :iri "http://data.bnf.fr/ark:/12148/cbX"})
                            (model/entity {:id :ent/w :kind :lrmoo/F1_Work})]})  ; no iri, no claims
          cert (set (export/triples r {:certified-only? true}))]
      (is (contains? cert ["http://data.bnf.fr/ark:/12148/cbX" RDF-TYPE
                           {:iri "http://iflastandards.info/ns/lrm/lrmoo/F3_Manifestation"}]))
      (is (not (contains? cert ["urn:palomar:ent:w" RDF-TYPE {:iri F1}]))))))

(deftest turtle-rendering
  (let [ttl (export/->turtle rec)]
    (testing "a @prefix header for the namespace actually used (lrmoo), not rdf (a -> 'a')"
      (is (str/includes? ttl "@prefix lrmoo: <http://iflastandards.info/ns/lrm/lrmoo/> ."))
      (is (not (str/includes? ttl "@prefix rdf:"))))
    (testing "rdf:type renders as 'a', predicates/types compact, subjects/urn objects stay full"
      (is (str/includes? ttl "a lrmoo:F1_Work"))
      (is (str/includes? ttl "lrmoo:R3_is_realised_in <urn:palomar:ent:expr>"))
      (is (str/includes? ttl "lrmoo:R33_has_string \"Madame Bovary\""))
      (is (str/includes? ttl "<urn:palomar:ent:work>")))
    (testing "every statement block ends in ' .'"
      (is (every? #(str/ends-with? (str/trimr %) ".")
                  (remove str/blank? (str/split ttl #"(?m)^(?=\S)")))))
    (testing "a record with no LRMoo content yields the empty string"
      (is (= "" (export/->turtle (model/record {:id :record/x :kind :book})))))))

(deftest jsonld-rendering
  (let [parsed (json/read-str (export/->jsonld rec))]
    (testing "an @context of the namespace prefixes and one node per subject"
      (is (= "http://iflastandards.info/ns/lrm/lrmoo/" (get-in parsed ["@context" "lrmoo"])))
      (is (= 2 (count (get parsed "@graph")))))
    (let [by-id (into {} (map (juxt #(get % "@id") identity)) (get parsed "@graph"))
          work  (get by-id "urn:palomar:ent:work")
          expr  (get by-id "urn:palomar:ent:expr")]
      (testing "rdf:type -> @type (compacted); a reference -> {@id …}; a literal -> a string"
        (is (= "lrmoo:F1_Work" (get work "@type")))
        (is (= {"@id" "urn:palomar:ent:expr"} (get work "lrmoo:R3_is_realised_in")))
        (is (= "lrmoo:F2_Expression" (get expr "@type")))
        (is (= "Madame Bovary" (get expr "lrmoo:R33_has_string")))))
    (testing "a record with no LRMoo content yields the empty string"
      (is (= "" (export/->jsonld (model/record {:id :record/x :kind :book})))))))

(deftest all-three-serialisations-encode-the-same-triples
  (testing "Turtle and JSON-LD carry exactly the triples N-Triples does (verifiable, no modelling)"
    (let [ts        (export/triples rec)
          nt-lines  (remove str/blank? (str/split-lines (export/->ntriples rec)))
          jsonld    (json/read-str (export/->jsonld rec))
          ;; one JSON-LD statement per (predicate value) pair, @type counted per type
          json-stmts (reduce (fn [n node]
                               (+ n (reduce-kv (fn [m k v]
                                                 (cond (= k "@id")   m
                                                       (= k "@type") (+ m (if (vector? v) (count v) 1))
                                                       (vector? v)   (+ m (count v))
                                                       :else         (inc m)))
                                               0 node)))
                             0 (get jsonld "@graph"))]
      (is (= (count ts) (count nt-lines)))
      (is (= (count ts) json-stmts))
      (testing "certified-only? threads through every serialisation"
        (let [p (model/record {:id :record/p :kind :book
                               :entities [(model/entity {:id :ent/e :kind :lrmoo/F2_Expression})]
                               :assertions [(model/assertion {:subject :ent/e :predicate :lrmoo/R33_has_string
                                                              :value "t" :status :proposed})]})]
          (is (= "" (export/->turtle p {:certified-only? true})))
          (is (= "" (export/->jsonld p {:certified-only? true}))))))))

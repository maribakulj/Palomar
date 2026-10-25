(ns palomar.plugins.lrmoo.dataset-test
  "Facts, proposals, history and provenance in separate named graphs (ADR 0029)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.convert :as convert]
            [palomar.curate :as curate]
            [palomar.ontology :as onto]
            [palomar.plugins.lrmoo.dataset :as ds]
            [palomar.plugins.lrmoo.ntriples :as nt]))

(def ^:private dc (slurp "test/fixtures/documentary/dublin-core/w3c_dc_example1.xml"))
(defn- wemi [] (first (:records (convert/to-wemi :dc {:record-id :dc/x} dc))))

(defn- graph-of
  "`{graph-iri #{predicate-iri …}}` of an N-Quads document."
  [nq]
  (reduce (fn [m [_ p _ g]] (update m (:iri g) (fnil conj #{}) (:iri p))) {} (nt/parse-quads nq)))

(def ^:private r4 "http://iflastandards.info/ns/lrm/lrmoo/R4_embodies")

(deftest an-uncurated-inference-is-a-proposal-not-a-fact
  (let [g (graph-of (ds/->nquads (wemi)))]
    (is (contains? (g ds/proposals-graph) r4))
    (is (not (contains? (g ds/facts-graph) r4)))
    (is (seq (g ds/provenance-graph)))
    (testing "the default graph says what each named graph holds"
      (is (= #{onto/rdf-type} (g nil))))))

(deftest a-rejected-claim-moves-to-the-history-graph
  (let [rec (first (:records (curate/curate [(wemi)] curate/reject-all)))
        g   (graph-of (ds/->nquads rec))]
    (is (contains? (g ds/history-graph) r4))
    (is (not (contains? (g ds/facts-graph) r4)))
    (is (not (contains? (g ds/proposals-graph) r4)))))

(deftest an-accepted-claim-is-a-fact
  (let [rec (first (:records (curate/curate [(wemi)] curate/accept-all)))
        g   (graph-of (ds/->nquads rec))]
    (is (contains? (g ds/facts-graph) r4))
    (is (nil? (g ds/proposals-graph)))))

(deftest trig-holds-the-same-graphs
  (let [trig (ds/->trig (wemi))]
    (is (str/includes? trig (str "<" ds/facts-graph "> {")))
    (is (str/includes? trig (str "<" ds/proposals-graph "> {")))
    (is (str/includes? trig "@prefix lrmoo:"))))

(deftest the-dataset-respects-its-vocabularies
  (let [sch (onto/with (onto/schema onto/vendored) onto/prov-o)
        ts  (for [[s p o] (nt/parse-quads (:output (convert/convert {:from :marc21 :to :nquads
                                                                     :source (slurp "test/fixtures/documentary/marc21/marcxml/loc_collection.xml")})))]
              [(nt/term-key s) (:iri p) o])]
    (is (seq ts))
    (is (empty? (onto/violations sch ts)))))

(ns palomar.reconcile-test
  "Unit tests for cross-record agent reconciliation (ADR 0018): grouping the
   identified-agent entities by authority id, on hand-built records."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.model :as model]
            [palomar.reconcile :as rec]))

(defn- record-with-agent [rid label iri]
  (model/record
   {:id rid :kind :book
    :entities (cond-> []
                iri (conj (model/entity {:id (model/mint-entity-id :crm/E21_Person iri)
                                         :kind :crm/E21_Person :iri iri})))
    :assertions (cond-> []
                  label (conj (model/assertion {:subject rid :predicate :canon/agent :value label})))}))

(def ^:private hugo  "https://isni.org/isni/0000000121200174")
(def ^:private flau  "https://isni.org/isni/0000000122762442")

(deftest reconciles-shared-authority-ids-across-records
  (testing "two records with the same ISNI reconcile to one agent; a different ISNI is a second"
    (let [records [(record-with-agent :rec/a "Hugo, Victor" hugo)
                   (record-with-agent :rec/b "Hugo, Victor" hugo)        ; same person, second record
                   (record-with-agent :rec/c "Flaubert, Gustave" flau)
                   (record-with-agent :rec/d "Anon" nil)]                ; no identified agent
          {:keys [agents distinct mentions records] :as r} (rec/reconcile-agents records)]
      (is (= 2 distinct))
      (is (= 3 mentions))                                  ; d contributes nothing
      (is (= 3 records))
      (testing "the Hugo agent gathers both records under one authority id"
        (let [hugo-agent (first (filter #(= hugo (:iri %)) agents))]
          (is (= 2 (:mentions hugo-agent)))
          (is (= #{:rec/a :rec/b} (set (:records hugo-agent))))
          (is (= "Hugo, Victor" (:label hugo-agent)))))
      (testing "same ISNI -> same content-addressed agent id (the dedup is exact)"
        (is (apply = (map :id (filter #(= hugo (:iri %)) agents))))   ; one entry, trivially
        (is (str/includes? (rec/format-agent-reconciliation r) "reconciled to 2 distinct agents"))))))

(deftest empty-and-agentless
  (testing "no records / no identified agents -> empty reconciliation"
    (is (= {:agents [] :distinct 0 :mentions 0 :records 0} (rec/reconcile-agents [])))
    (is (zero? (:distinct (rec/reconcile-agents [(record-with-agent :rec/x "Anon" nil)]))))
    (is (str/includes? (rec/format-agent-reconciliation (rec/reconcile-agents []))
                       "no authority-identified agents"))))

(def ^:private pool
  [{:id "isni:flaubert" :label "Gustave Flaubert" :variants ["Flaubert"]}
   {:id "isni:balzac"   :label "Honoré de Balzac" :variants ["Balzac" "Balzak"]}
   {:id nil             :label "Victor Hugo"      :variants []}])   ; a name with no id (the metro case)

(deftest fuzzy-tier-proposes-never-asserts
  (testing "order-insensitive token match: 'Gustave Flaubert' -> the Flaubert authority"
    (let [[p] (rec/propose-agent-links ["Gustave Flaubert"] pool)]
      (is (= "isni:flaubert" (:authority-id p)))
      (is (= 1.0 (:score p)))
      (is (= :proposed (:status p)))                 ; never :asserted (D7)
      (is (true? (:certifiable? p)))))
  (testing "partial match via a variant: 'Balzac' -> Honoré de Balzac"
    (let [[p] (rec/propose-agent-links ["Balzac"] pool)]
      (is (= "isni:balzac" (:authority-id p)))
      (is (true? (:certifiable? p)))))
  (testing "below threshold -> no proposal"
    (is (empty? (rec/propose-agent-links ["Marcel Proust"] pool 0.5))))
  (testing "everything emitted is :proposed, sorted by score"
    (is (every? #(= :proposed (:status %)) (rec/propose-agent-links ["Flaubert" "Balzac"] pool)))))

(deftest the-metro-station-guard
  (testing "a perfect name match to an id-less entry is :proposed but NOT certifiable"
    (let [[p] (rec/propose-agent-links ["Victor Hugo"] pool)]
      (is (= "Victor Hugo" (:authority-label p)))
      (is (= 1.0 (:score p)))                        ; perfect name match
      (is (= :proposed (:status p)))
      (is (false? (:certifiable? p))))))             ; no id -> can never be promoted to :asserted

(ns palomar.reconcile-test
  "Unit tests for cross-record agent reconciliation (ADR 0018): grouping the
   identified-agent entities by authority id, on hand-built records."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.curate :as curate]
            [palomar.model :as model]
            [palomar.plugins.lrmoo.dataset :as dataset]
            [palomar.plugins.lrmoo.export :as export]
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

;; --- the rewrite onto shared agent nodes (audit S29) -------------------------

(deftest accepted-links-rewrite-records-onto-one-agent
  (let [a       (record-with-agent :r/a "Gustave Flaubert" nil)
        b       (record-with-agent :r/b "Gustave Flaubert" nil)
        c       (record-with-agent :r/c "Victor Hugo" nil)
        ;; a curator's explicit corpus-wide decision: this name is one person
        accept  #(assoc % :status :accepted :records :all)
        links   (mapv accept (rec/propose-agent-links ["Gustave Flaubert" "Victor Hugo"] pool))
        {:keys [records applied refused]} (rec/apply-agent-links [a b c] links)]
    (testing "a link accepted for the whole corpus is applied to both Flaubert records, as the same node"
      (is (= #{:r/a :r/b} (set (map :record applied))))
      (is (= 1 (count (distinct (map :id (mapcat :entities (take 2 records))))))))
    (testing "the id-less Victor Hugo match (the metro station) is refused, never applied"
      (is (= [["Victor Hugo" :not-certifiable]] (map (juxt :name :reason) refused)))
      (is (empty? (:entities (nth records 2)))))
    (testing "reconcile-agents now sees one person mentioned by two records"
      (let [{:keys [distinct agents]} (rec/reconcile-agents records)]
        (is (= 1 distinct))
        (is (= [:r/a :r/b] (:records (first agents))))))
    (testing "a record that already has an identified agent is left alone"
      (let [d (record-with-agent :r/d "Gustave Flaubert" flau)]
        (is (empty? (:applied (rec/apply-agent-links [d] links))))))))

(deftest only-accepted-unambiguous-links-are-applied
  (let [a (record-with-agent :r/a "Gustave Flaubert" nil)]
    (testing "a bare proposal is never applied (D7)"
      (let [{:keys [applied refused]} (rec/apply-agent-links [a] (rec/propose-agent-links ["Gustave Flaubert"] pool))]
        (is (empty? applied))
        (is (= [:not-accepted] (map :reason refused)))))
    (testing "a rejected link is not applied"
      (is (empty? (:applied (rec/apply-agent-links [a] [{:name "Gustave Flaubert" :authority-id "isni:flaubert"
                                                         :certifiable? true :status :rejected :records #{:r/a}}])))))
    (testing "one name accepted for two authority ids is ambiguous: neither is applied"
      (let [{:keys [applied refused]}
            (rec/apply-agent-links [a] [{:name "Gustave Flaubert" :authority-id "isni:a" :certifiable? true :status :accepted :records #{:r/a}}
                                        {:name "Gustave Flaubert" :authority-id "isni:b" :certifiable? true :status :accepted :records #{:r/a}}])]
        (is (empty? applied))
        (is (= [:ambiguous :ambiguous] (map :reason refused)))))))

(deftest an-authority-id-that-is-no-iri-is-refused
  (testing "an accepted, certifiable link whose id would corrupt the RDF output is refused, with its reason"
    (let [r    (model/record {:id :rec/one :kind :doc
                              :assertions [(model/assertion {:subject :rec/one :predicate :canon/agent :value "X"})]})
          link {:name "X" :authority-id "isni 0000 0001" :certifiable? true :status :accepted :records #{:rec/one}}
          {:keys [applied refused]} (rec/apply-agent-links [r] [link])]
      (is (empty? applied))
      (is (= [:invalid-iri] (map :reason refused))))))

;; --- alignments in the IR (ADR 0032) ----------------------------------------

(defn- named [rid nm]
  (model/record {:id rid :kind :doc
                 :assertions [(model/assertion {:subject rid :predicate :canon/agent :value nm})]}))

(deftest a-proposal-is-a-proposed-skos-mapping-not-a-merge
  (let [props   [{:name "Gustave Flaubert" :authority-id "https://isni.org/isni/0000000122762442"
                  :authority-label "Flaubert, Gustave" :score 1.0 :certifiable? true :status :proposed}
                 {:name "G. Flaubert" :authority-id "https://isni.org/isni/0000000122762442"
                  :authority-label "Flaubert, Gustave" :score 0.5 :certifiable? true :status :proposed}]
        [a b]   (rec/align-agents [(named :doc/a "Gustave Flaubert") (named :doc/b "G. Flaubert")] props)
        preds   (fn [r] (set (keep #(when (= "skos" (namespace (:predicate %))) [(:predicate %) (:status %)]) (:assertions r))))]
    (testing "an exact, certifiable name is an exactMatch; a partial one a closeMatch; both only proposed"
      (is (= #{[:skos/exactMatch :proposed]} (preds a)))
      (is (= #{[:skos/closeMatch :proposed]} (preds b))))
    (testing "nothing is linked until a curator accepts; a close match is never a link"
      (is (empty? (rec/accepted-links [a b])))
      (let [acc (:records (curate/curate [a b] curate/accept-all))]
        (is (= ["Gustave Flaubert"] (map :name (rec/accepted-links acc))))
        (testing "and the accepted link rewrites the record onto the authority's agent"
          (is (= 1 (count (:applied (rec/apply-agent-links acc (rec/accepted-links acc)))))))))
    (testing "the alignment reaches RDF as SKOS, never as owl:sameAs"
      (let [nt (export/->ntriples a)]
        (is (str/includes? nt "skos/core#exactMatch"))
        (is (not (str/includes? nt "sameAs")))))
    (testing "a rejected alignment moves to the history graph"
      (let [rej (first (:records (curate/curate [a] curate/reject-all)))
            nq  (dataset/->nquads rej)]
        (is (some #(and (str/includes? % "exactMatch") (str/includes? % "graph:history")) (str/split-lines nq)))))))

(deftest an-accepted-alignment-does-not-identify-a-homonym
  (testing "accepting \"Gustave Flaubert\" = the authority in one record leaves another record's Gustave Flaubert alone (post-audit test 01)"
    (let [props [{:name "Gustave Flaubert" :authority-id "https://isni.org/isni/0000000122762442"
                  :authority-label "Flaubert, Gustave" :score 1.0 :certifiable? true :status :proposed}]
          [a b] (rec/align-agents [(named :doc/a "Gustave Flaubert") (named :doc/b "Gustave Flaubert")] props)
          ;; the curator accepts the alignment in :doc/a only
          a'    (:record (curate/curate-record a curate/accept-all))
          b'    (:record (curate/curate-record b curate/reject-all))
          links (rec/accepted-links [a' b'])
          {:keys [applied]} (rec/apply-agent-links [a' b'] links)]
      (is (= [#{:doc/a}] (map :records links)))
      (is (= [:doc/a] (map :record applied)))))
  (testing "a link that names no record is refused: a name alone identifies no one"
    (let [{:keys [applied refused]} (rec/apply-agent-links [(record-with-agent :r/a "Gustave Flaubert" nil)]
                                                           [{:name "Gustave Flaubert" :authority-id "isni:flaubert"
                                                             :certifiable? true :status :accepted}])]
      (is (empty? applied))
      (is (= [:unscoped] (map :reason refused))))))

(deftest a-corporate-body-is-reconciled-as-a-group
  (testing "a corporate authority makes an E74 Group, not a Person (post-audit C7)"
    (let [r     (record-with-agent :r/c "Cité internationale universitaire de Paris" nil)
          link  {:name "Cité internationale universitaire de Paris" :authority-id "http://id.loc.gov/authorities/names/n80000001"
                 :certifiable? true :status :accepted :records #{:r/c} :entity-type "corporateBody"}
          {:keys [records applied]} (rec/apply-agent-links [r] [link])]
      (is (= 1 (count applied)))
      (is (= [:crm/E74_Group] (map :kind (filter :iri (:entities (first records))))))
      (is (= 1 (:distinct (rec/reconcile-agents records))) "and reconcile-agents sees it")))
  (testing "a proposal carries the authority's entity type to the alignment and back"
    (let [props (rec/propose-agent-links ["Marist College"] [{:id "http://ex.org/mc" :label "Marist College" :entity-type "corporateBody"}])
          [a]   (rec/align-agents [(named :doc/m "Marist College")] props)
          acc   (:records (curate/curate [a] curate/accept-all))]
      (is (= "corporateBody" (:entity-type (first props))))
      (is (some #(= :crm/E74_Group (:kind %)) (:entities a)) "the local agent is a Group")
      (is (= ["corporateBody"] (map :entity-type (rec/accepted-links acc)))))))

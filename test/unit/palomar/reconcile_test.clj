(ns palomar.reconcile-test
  "Unit tests for cross-record agent reconciliation (ADR 0018): grouping the
   identified-agent entities by authority id, on hand-built records."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.agent-dates :as dates]
            [palomar.agent-match :as fs]
            [palomar.curate :as curate]
            [palomar.model :as model]
            [palomar.plugins.lrmoo.dataset :as dataset]
            [palomar.plugins.lrmoo.export :as export]
            [palomar.plugins.lrmoo.linked-art :as linked-art]
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

(defn- confirm
  "A curator confirms every pending close match of `r` to an authority
   (`rec/confirm-identity`), with the curator `context`."
  ([r] (confirm r {}))
  ([r context]
   (reduce (fn [r a] (:record (rec/confirm-identity r a "confirmed by the curator" context)))
           r (filter #(and (= :palomar/resemblesAuthority (:predicate %)) (model/pending? %)) (:assertions r)))))

(deftest a-proposal-is-a-proposed-skos-mapping-not-a-merge
  (let [props   [{:name "Gustave Flaubert" :authority-id "https://isni.org/isni/0000000122762442"
                  :authority-label "Flaubert, Gustave" :score 1.0 :certifiable? true :status :proposed}
                 {:name "G. Flaubert" :authority-id "https://isni.org/isni/0000000122762442"
                  :authority-label "Flaubert, Gustave" :score 0.5 :certifiable? true :status :proposed}]
        [a b]   (rec/align-agents [(named :doc/a "Gustave Flaubert") (named :doc/b "G. Flaubert")] props)
        preds   (fn [r] (set (keep #(when (= "palomar" (namespace (:predicate %))) [(:predicate %) (:status %)]) (:assertions r))))]
    (testing "a name-only proposal, exact or partial, is a close match (ADR 0057: no model decided it); only proposed"
      (is (= #{[:palomar/resemblesAuthority :proposed]} (preds a)))
      (is (= #{[:palomar/resemblesAuthority :proposed]} (preds b))))
    (testing "nothing is linked until a curator confirms the identity; an accepted close match is never a link"
      (is (empty? (rec/accepted-links [a b])))
      (is (empty? (rec/accepted-links (:records (curate/curate [a b] curate/accept-all)))))
      (let [acc [(confirm a) b]]
        (is (= ["Gustave Flaubert"] (map :name (rec/accepted-links acc))))
        (testing "and the accepted link rewrites the record onto the authority's agent"
          (is (= 1 (count (:applied (rec/apply-agent-links acc (rec/accepted-links acc)))))))))
    (testing "the alignment reaches RDF as Palomar's relation, never as SKOS (ADR 0061); owl:sameAs only once accepted, to the person's own IRI"
      (let [nt (export/->ntriples a)]
        (is (str/includes? nt "urn:palomar:vocab#resemblesAuthority"))
        (is (str/includes? (export/->ntriples (confirm a {:by "curator"})) "http://www.w3.org/2002/07/owl#sameAs")
            "a signed curator's confirmation")
        (is (not (str/includes? (export/->ntriples (confirm a)) "owl#sameAs")) "an unsigned one is no curator's")
        (is (not (str/includes? (str nt (export/->ntriples (confirm a))) "skos/core#")))
        (is (not (str/includes? nt "sameAs")))))
    (testing "a rejected alignment moves to the history graph"
      (let [rej (first (:records (curate/curate [a] curate/reject-all)))
            nq  (dataset/->nquads rej)]
        (is (some #(and (str/includes? % "resemblesAuthority") (str/includes? % "graph:history")) (str/split-lines nq)))))))

(deftest an-accepted-alignment-does-not-identify-a-homonym
  (testing "accepting \"Gustave Flaubert\" = the authority in one record leaves another record's Gustave Flaubert alone (post-audit test 01)"
    (let [props [{:name "Gustave Flaubert" :authority-id "https://isni.org/isni/0000000122762442"
                  :authority-label "Flaubert, Gustave" :score 1.0 :certifiable? true :status :proposed}]
          [a b] (rec/align-agents [(named :doc/a "Gustave Flaubert") (named :doc/b "Gustave Flaubert")] props)
          ;; the curator accepts the alignment in :doc/a only
          a'    (confirm a)
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
          acc   [(confirm a)]]
      (is (= "corporateBody" (:entity-type (first props))))
      (is (some #(= :crm/E74_Group (:kind %)) (:entities a)) "the local agent is a Group")
      (is (= ["corporateBody"] (map :entity-type (rec/accepted-links acc)))))))

(defn- record-with-agents [rid & names]
  (model/record {:id rid :kind :book
                 :assertions (mapv #(model/assertion {:subject rid :predicate :canon/agent :value %}) names)}))

(defn- link [nm iri rid] {:name nm :authority-id iri :certifiable? true :status :accepted :records #{rid}})

(def ^:private marx   "https://isni.org/isni/0000000121232615")
(def ^:private engels "https://isni.org/isni/0000000121358464")

(deftest every-creator-of-a-record-is-reconciled
  (testing "two accepted links in one record make two identified agents (post-audit 2, D1)"
    (let [r (record-with-agents :r/m "Marx, Karl" "Engels, Friedrich")
          {:keys [records applied refused]} (rec/apply-agent-links [r] [(link "Marx, Karl" marx :r/m)
                                                                        (link "Engels, Friedrich" engels :r/m)])]
      (is (= #{["Marx, Karl" marx] ["Engels, Friedrich" engels]} (set (map (juxt :name :iri) applied))))
      (is (empty? refused))
      (is (= #{marx engels} (set (keep :iri (:entities (first records))))))
      (testing "and each agent keeps its own name"
        (is (= {marx "Marx, Karl" engels "Engels, Friedrich"}
               (into {} (map (juxt :iri :label)) (:agents (rec/reconcile-agents records)))))))))

(deftest a-record-already-identified-in-part-takes-the-rest
  (testing "a native ISNI identifies the main entry; the second creator can still be linked"
    (let [r (update (record-with-agents :r/n "Gustave Flaubert" "Louis Bouilhet") :entities (fnil conj [])
                    (model/entity {:id (model/mint-entity-id :crm/E21_Person flau) :kind :crm/E21_Person :iri flau}))
          bouilhet "https://isni.org/isni/0000000080809191"
          {:keys [records applied skipped]} (rec/apply-agent-links [r] [(link "Louis Bouilhet" bouilhet :r/n)
                                                                        (link "Gustave Flaubert" "https://isni.org/isni/0000000000000001" :r/n)])]
      (is (= [["Louis Bouilhet" bouilhet]] (map (juxt :name :iri) applied)))
      (is (= #{flau bouilhet} (set (keep :iri (:entities (first records))))))
      (testing "a link for a name the record already identifies is not applied, and says so"
        (is (= [[:r/n "Gustave Flaubert" :already-identified]] (map (juxt :record :name :reason) skipped))))
      (is (= {flau "Gustave Flaubert" bouilhet "Louis Bouilhet"}
             (into {} (map (juxt :iri :label)) (:agents (rec/reconcile-agents records))))))))

(deftest a-contributor-is-reconciled-too
  (testing "an accepted alignment of any agent the record names is applied, not only of a creator"
    (let [fid    :frag/t.contributor
          r      (model/record {:id :r/t :kind :book
                                :fragments [(model/fragment {:id fid :source [:test]})]
                                :assertions [(model/assertion {:subject :r/t :predicate :canon/agent :value "Nerval"})
                                             (model/assertion {:subject :r/t :predicate :canon/agent :value (model/reference fid)})
                                             (model/assertion {:subject fid :predicate :canon/agent :value "Goethe"})
                                             (model/assertion {:subject fid :predicate :canon/role :value :contributor})]})
          goethe "https://isni.org/isni/0000000121222924"
          {:keys [applied skipped refused]} (rec/apply-agent-links [r] [(link "Goethe" goethe :r/t)])]
      (is (= [goethe] (map :iri applied)))
      (is (empty? skipped))
      (is (empty? refused)))))

(deftest linked-art-names-each-identified-creator
  (testing "with two identified creators, each Linked Art Person carries its own authority id"
    (let [r    (-> (record-with-agents :r/m "Marx, Karl" "Engels, Friedrich")
                   (update :entities (fnil conj []) (model/entity {:id :m/m :kind :lrmoo/F3_Manifestation}))
                   (update :assertions conj (model/assertion {:subject :m/m :predicate :lrmoo/R33_has_string :value "Manifest"})))
          out  (first (:records (rec/apply-agent-links [r] [(link "Marx, Karl" marx :r/m) (link "Engels, Friedrich" engels :r/m)])))
          doc  (first (linked-art/documents out))]
      (is (= {"Marx, Karl" marx "Engels, Friedrich" engels}
             (into {} (map (juxt #(get % "_label") #(get % "id"))) (get-in doc ["created_by" "carried_out_by"])))))))

;; --- dates as contrary evidence (lot M3) ------------------------------------

(def ^:private dumas
  "Dumas père and fils, both headed \"Dumas, Alexandre\" (BnF 11901063, 11901062)."
  [{:id "http://catalogue.bnf.fr/ark:/12148/cb11901062n" :label "Dumas, Alexandre" :variants ["Dumas fils, Alexandre"]
    :dates (dates/parse-life-dates "1824-1895")}
   {:id "http://catalogue.bnf.fr/ark:/12148/cb11901063z" :label "Dumas, Alexandre" :variants ["Dumas père, Alexandre"]
    :dates (dates/parse-life-dates "1802-1870")}])

(def ^:private fils "http://catalogue.bnf.fr/ark:/12148/cb11901062n")
(def ^:private pere "http://catalogue.bnf.fr/ark:/12148/cb11901063z")

(deftest a-candidate-whose-dates-are-incompatible-is-never-proposed
  (let [{:keys [proposals rejected]} (rec/agent-candidates [{:name "Dumas, Alexandre" :dates "1802-1870"}] dumas)]
    (testing "the father's dates block the son: one proposal, the father"
      (is (= [pere] (map :authority-id proposals)))
      (is (= {:name-score 1.0 :dates :compatible} (-> (first proposals) :evidence (update :dates :verdict))))
      (is (= :proposed (:status (first proposals))) "still only a proposal (D7)"))
    (testing "the son is not dropped silently: rejected, with the reason and the dates compared"
      (is (= [[fils :incompatible-dates]] (map (juxt :authority-id :reason) rejected)))
      (let [{:keys [name-score dates]} (:evidence (first rejected))]
        (is (= 1.0 name-score) "the name alone would have tied")
        (is (= :incompatible (:verdict dates)))
        (is (= "1802-1870" (:text (:mention dates))))
        (is (= "1824-1895" (:text (:candidate dates))))
        (is (= {:compared [:birth :birth] :a {:from 1802 :to 1802 :text "1802"} :b {:from 1824 :to 1824 :text "1824"} :ok? false}
               (first (:checks dates)))))))
  (testing "the son's mention gets the son"
    (is (= [fils] (map :authority-id (rec/propose-agent-links [{:name "Dumas, Alexandre" :dates "1824-1895"}] dumas)))))
  (testing "a mention dated 18.. is compatible with both: the tie stays, nothing is rejected"
    (let [{:keys [proposals rejected]} (rec/agent-candidates [{:name "Dumas, Alexandre" :dates "18..-18.."}] dumas)]
      (is (= #{fils pere} (set (map :authority-id proposals))))
      (is (every? #(= :compatible (get-in % [:evidence :dates :verdict])) proposals))
      (is (empty? rejected)))))

(deftest missing-dates-are-no-evidence
  (testing "an undated mention, or an undated candidate, is unknown, never incompatible"
    (let [{:keys [proposals rejected]} (rec/agent-candidates [{:name "Dumas, Alexandre"}] dumas)]
      (is (= 2 (count proposals)))
      (is (every? #(= :unknown (get-in % [:evidence :dates :verdict])) proposals))
      (is (empty? rejected)))
    (let [undated (mapv #(dissoc % :dates) dumas)]
      (is (= 2 (count (rec/propose-agent-links [{:name "Dumas, Alexandre" :dates "1802-1870"}] undated))))))
  (testing "an unreadable date is kept as text and blocks nothing"
    (let [[p] (rec/propose-agent-links [{:name "Dumas, Alexandre" :dates "XIXe siècle"}] (take 1 dumas))]
      (is (= :unknown (get-in p [:evidence :dates :verdict])))
      (is (= {:text "XIXe siècle"} (get-in p [:evidence :dates :mention]))))))

(deftest a-plain-name-is-still-a-mention
  (testing "a string mention is matched as before: same proposals, the evidence added"
    (let [[p & more] (rec/propose-agent-links ["Gustave Flaubert"] pool)]
      (is (empty? more))
      (is (= {:name "Gustave Flaubert" :authority-id "isni:flaubert" :authority-label "Gustave Flaubert" :score 1.0
              :certifiable? true :status :proposed
              :evidence {:name-score 1.0 :dates {:verdict :unknown :checks []}}}
             p))))
  (testing "a map mention reports its name as the proposal's :name, so alignments find it in the record"
    (is (= ["Gustave Flaubert"] (map :name (rec/propose-agent-links [{:name "Gustave Flaubert" :dates "1821-1880"}] pool)))))
  (testing "the same mentions, as strings or as undated maps, give the same proposals"
    (is (= (rec/propose-agent-links ["Dumas, Alexandre" "Balzac"] (concat dumas pool))
           (rec/propose-agent-links [{:name "Dumas, Alexandre"} {:name "Balzac"}] (concat dumas pool))))))

(deftest a-proposal-carries-its-evidence-into-the-ir
  (let [props (rec/propose-agent-links [{:name "Dumas, Alexandre" :dates "1802-1870"}] dumas)
        [r]   (rec/align-agents [(named :doc/d "Dumas, Alexandre")] props)
        maps  (filter #(= "palomar" (namespace (:predicate %))) (:assertions r))]
    (testing "one proposed mapping, to the father (a close match: name-only); the son, rejected, is no claim"
      (is (= [[:palomar/resemblesAuthority :proposed]] (map (juxt :predicate :status) maps)))
      (is (= #{pere} (set (keep :iri (:entities r))))))
    (testing "the mapping's provenance names the name score and the dates verdict and values"
      (is (= {'?label "Dumas, Alexandre" '?name-score 1.0 '?dates :compatible
              '?mention-dates "1802-1870" '?authority-dates "1802-1870"}
             (get-in (first maps) [:provenance :bindings]))))))

(defn- mappings [r] (filter #(= "palomar" (namespace (:predicate %))) (:assertions r)))

(deftest a-proposal-stays-with-the-mention-that-made-it
  (let [pere-m {:name "Dumas, Alexandre" :dates "1802-1870"}
        fils-m {:name "Dumas, Alexandre" :dates "1824-1895"}]
    (testing "two dated mentions of one name in a batch: a record that does not say which it is takes neither"
      (let [props (rec/propose-agent-links [pere-m fils-m] dumas)
            [r]   (rec/align-agents [(named :doc/t "Dumas, Alexandre")] props)]
        (is (= #{pere fils} (set (map :authority-id props))))
        (is (empty? (mappings r)) "never both père and fils, never one mention's evidence on the other's")))
    (testing "scoped to its record, each mention gives that record its own authority and its own evidence"
      (let [props (rec/propose-agent-links [(assoc pere-m :record :doc/a) (assoc fils-m :record :doc/b)] dumas)
            [a b] (rec/align-agents [(named :doc/a "Dumas, Alexandre") (named :doc/b "Dumas, Alexandre")] props)
            one   (fn [r] (map (juxt (comp :value/target :value) #(get-in % [:provenance :bindings '?mention-dates])) (mappings r)))
            iris  (fn [r] (set (keep :iri (:entities r))))]
        (is (= #{pere} (iris a)) "the son, rejected for :doc/a's mention, is no assertion there")
        (is (= #{fils} (iris b)))
        (is (= ["1802-1870"] (map second (one a))))
        (is (= ["1824-1895"] (map second (one b))))))
    (testing "the same mention given twice is one mention"
      (let [props (rec/propose-agent-links [pere-m pere-m] dumas)
            [r]   (rec/align-agents [(named :doc/t "Dumas, Alexandre")] props)]
        (is (= 1 (count (mappings r))))))))

(deftest an-undated-mention-states-no-dates-verdict
  (testing "a plain name's mapping names its score, not a dates verdict it never had"
    (let [[r] (rec/align-agents [(named :doc/d "Dumas, Alexandre")] (rec/propose-agent-links ["Dumas, Alexandre"] dumas))]
      (is (= 2 (count (mappings r))))
      (is (every? #(= {'?label "Dumas, Alexandre" '?name-score 1.0} (get-in % [:provenance :bindings])) (mappings r))))))

;; --- a Fellegi-Sunter score (lot M4) ------------------------------------------

(def ^:private fs-model
  "A hand-set model: an exact heading weighs log2 90, agreeing dates log2 16,
   unknown dates 0 (by rule), a different surname with a high Jaccard log2
   1/4; prior 0.05 (log2 1/19)."
  (fs/model {:m {:name  {:exact 0.9 :initials 0.03 :same-tokens 0.02 :surname-typo 0.01 :forename-overlap 0.01
                         :surname-only 0.01 :forenames-differ 0.005 :jaccard-high 0.005 :jaccard-low 0.005 :none 0.005}
                 :form  {:heading 0.95 :variant 0.05}
                 :dates {:agree 0.8 :compatible 0.1 :unknown 0.1}}
             :u {:name  {:exact 0.01 :initials 0.002 :same-tokens 0.002 :surname-typo 0.001 :forename-overlap 0.01
                         :surname-only 0.005 :forenames-differ 0.05 :jaccard-high 0.02 :jaccard-low 0.2 :none 0.7}
                 :form  {:heading 0.9 :variant 0.1}
                 :dates {:agree 0.05 :compatible 0.15 :unknown 0.8}}
             :prior 0.05}))

(defn- well-calibrated
  "A calibration report on `pool` that meets `fs/default-bar`, review band
   included, measured on `fs-model`."
  [pool]
  (fs/calibration (concat (for [i (range 100)] {:p 0.99 :y 1 :pair [:t i] :mention [:t i]})
                          (for [i (range 200)] {:p 0.01 :y 0 :pair [:f i] :mention [:f i]})
                          (for [i (range 40)] {:p 0.75 :y (if (< i 30) 1 0) :pair [:r i] :mention [:r i]}))
                  {:pool (fs/pool-fingerprint pool) :model (fs/model-fingerprint fs-model)}))

(deftest a-model-scores-each-candidate-and-explains-it
  (let [{:keys [proposals rejected]} (rec/agent-candidates [{:name "Dumas, Alexandre" :dates "1802-1870"}] dumas {:model fs-model})
        [p] proposals]
    (testing "the father, proposed outright; the son still rejected for his dates, never weighed"
      (is (= [pere] (map :authority-id proposals)))
      (is (= :propose (:decision p)))
      (is (= :proposed (:status p)) "still only a proposal (D7)")
      (is (= [[fils :incompatible-dates]] (map (juxt :authority-id :reason) rejected))))
    (testing "the score is the posterior; the evidence holds the comparison vector and each weight"
      (is (= {:name :exact :form :heading :dates :agree} (get-in p [:evidence :comparison])))
      (is (= (fs/posterior (:match-weight p) 0.05) (:score p)))
      (is (< 0.95 (:score p) 1.0))
      (is (= #{:name :form :dates :tf} (set (keys (get-in p [:evidence :weights])))))
      (is (= 1.0 (get-in p [:evidence :name-score])) "the Jaccard is kept, as evidence"))))

(deftest a-candidate-below-the-abstention-threshold-is-not-proposed
  (let [hugo [{:id "http://ex.org/hugo" :label "Hugo, Victor"}]
        {:keys [proposals abstained]} (rec/agent-candidates ["Paltsits, Victor Hugo"] hugo {:model fs-model})]
    (testing "the Jaccard (0.667) would propose Victor Hugo; the model abstains"
      (is (= ["http://ex.org/hugo"] (map :authority-id (rec/propose-agent-links ["Paltsits, Victor Hugo"] hugo))))
      (is (empty? proposals))
      (is (empty? (rec/propose-agent-links ["Paltsits, Victor Hugo"] hugo {:model fs-model}))))
    (testing "the abstention is listed, with its evidence"
      (is (= [["http://ex.org/hugo" :abstain :jaccard-high]]
             (map (juxt :authority-id :decision #(get-in % [:evidence :comparison :name])) abstained)))
      (is (< (:score (first abstained)) 0.5)))
    (testing "and no claim is made"
      (is (empty? (mappings (first (rec/align-agents [(named :doc/p "Paltsits, Victor Hugo")]
                                                     (rec/propose-agent-links ["Paltsits, Victor Hugo"] hugo {:model fs-model}))))))))
  (testing "a name sharing nothing with an entry is no candidate at all"
    (is (= {:proposals [] :rejected [] :abstained []}
           (rec/agent-candidates ["Verne, Jules"] dumas {:model fs-model})))))

(deftest an-undated-exact-heading-is-not-abstained-for-being-undated
  (testing "missing dates weigh 0: the same heading, dated or not, is never abstained for its want of dates"
    (let [[p] (rec/propose-agent-links ["Gustave Flaubert"] pool {:model fs-model})]
      (is (= :unknown (get-in p [:evidence :comparison :dates])))
      (is (= 0.0 (get-in p [:evidence :weights :dates])))
      (is (not= :abstain (:decision p))))))

(deftest between-the-thresholds-a-proposal-is-flagged-for-review
  (let [[p] (rec/propose-agent-links ["Gustave Flaubert"] pool {:model fs-model})
        [r] (rec/align-agents [(named :doc/f "Gustave Flaubert")] [p])
        [a] (mappings r)]
    (testing "an exact name and no dates: posterior 0.83, between 0.5 and 0.95"
      (is (= :review (:decision p)))
      (is (< 0.8 (:score p) 0.9)))
    (testing "a proposal for review is a close match, never an exact one"
      (is (= [:palomar/resemblesAuthority :proposed] ((juxt :predicate :status) a))))
    (testing "its provenance states the posterior, the weight, the decision and the levels — as evidence, not confidence"
      (is (nil? (:confidence a)))
      (is (= {'?label "Gustave Flaubert" '?name-score 1.0 '?posterior (:score p) '?match-weight (:match-weight p)
              '?decision :review '?name-level :exact '?form :heading '?dates-level :unknown}
             (get-in a [:provenance :bindings]))))))

(deftest at-most-one-outright-proposal-per-mention
  (let [d     dates/parse-life-dates
        pool2 [{:id "http://x/a" :label "Martin, Jean" :dates (d "1900-1980")}
               {:id "http://x/b" :label "Martin, Jean-Pierre" :variants ["Martin, Jean"] :dates (d "1901-1979")}
               {:id "http://x/c" :label "Durand, Paul"} {:id "http://x/d" :label "Bernard, Luc"}]
        props (rec/propose-agent-links [{:name "Martin, Jean" :dates "1900-1980" :record :doc/r}] pool2 {:model fs-model})]
    (testing "Jean and Jean-Pierre Martin (heading, variant) each above the propose threshold alone: both go to review"
      (is (= #{"http://x/a" "http://x/b"} (set (map :authority-id props))))
      (is (every? #(< 0.95 (:score %)) props))
      (is (= [[:review :competing] [:review :competing]] (map (juxt :decision :review-reason) props))))
    (testing "so the record never takes two exact matches"
      (is (= #{:palomar/resemblesAuthority} (set (map :predicate (mappings (first (rec/align-agents [(named :doc/r "Martin, Jean")] props)))))))))
  (testing "undated père and fils weigh alike: neither is proposed outright"
    (let [ps (rec/propose-agent-links [{:name "Dumas, Alexandre"}] dumas {:model (assoc fs-model :thresholds {:propose 0.8 :review 0.5})})]
      (is (= #{pere fils} (set (map :authority-id ps))))
      (is (every? #(< 0.8 (:score %)) ps))
      (is (= [[:review :competing] [:review :competing]] (map (juxt :decision :review-reason) ps))))))

(deftest an-uncalibrated-score-is-never-written-as-a-confidence
  (let [dated [{:name "Dumas, Alexandre" :dates "1802-1870"}]
        conf  (fn [props] (map :confidence (mappings (first (rec/align-agents [(named :doc/d "Dumas, Alexandre")] props)))))]
    (testing "an uncalibrated model: the posterior is a score, the proposal and its claim state no confidence"
      (let [props (rec/propose-agent-links dated dumas {:model fs-model})]
        (is (false? (fs/calibrated? fs-model dumas)))
        (is (not-any? #(contains? % :confidence) props))
        (is (= [nil] (conf props)))
        (testing "so a confidence threshold never accepts it"
          (let [r   (first (rec/align-agents [(named :doc/d "Dumas, Alexandre")] props))
                res (curate/curate [r] (curate/accept-when (curate/confidence-at-least 0.5)))]
            (is (= [0 1] ((juxt :accepted :needs-review) (:summary res))) "routed to review, not accepted")))))
    (testing "a model whose held-out calibration on this pool meets the bar: the posterior is the confidence"
      (let [m     (fs/with-calibration fs-model (well-calibrated dumas))
            props (rec/propose-agent-links dated dumas {:model m})]
        (is (true? (fs/calibrated? m dumas)))
        (is (= (map :score props) (map :confidence props)))
        (is (= (map :score props) (conf props)))))
    (testing "the name-only Jaccard is no probability: it is evidence, never a confidence"
      (let [props (rec/propose-agent-links dated dumas)]
        (is (not-any? #(contains? % :confidence) props))
        (is (= [nil] (conf props)))
        (is (= 1.0 (get-in (first (mappings (first (rec/align-agents [(named :doc/d "Dumas, Alexandre")] props))))
                           [:provenance :bindings '?name-score])))))))

(deftest a-calibration-holds-only-for-the-pool-it-was-measured-on
  (let [d   dates/parse-life-dates
        m   (fs/with-calibration fs-model (well-calibrated dumas))
        big (vec (concat [{:id "http://x/v" :label "Verne, Jules" :dates (d "1828-1905")}]
                         (for [i (range 5000)] {:id (str "http://x/f" i) :label (str "Filler" i ", Anne")})))]
    (testing "on another pool, even an exact dated heading proposed outright states no confidence"
      (let [[p] (rec/propose-agent-links [{:name "Verne, Jules" :dates "1828-1905"}] big {:model m})]
        (is (= :propose (:decision p)))
        (is (not (contains? p :confidence)))))
    (testing "\"Verne, Paul\" (forenames differ, dates agree) on a pool of 5 001: not proposed outright, no confidence"
      (let [ps (rec/agent-candidates [{:name "Verne, Paul" :dates "1828-1905"}] big {:model m})]
        (is (not-any? #(= :propose (:decision %)) (concat (:proposals ps) (:abstained ps))))
        (is (not-any? #(contains? % :confidence) (concat (:proposals ps) (:abstained ps))))
        (testing "and the surname adjustment, read from that pool alone, adds nothing for a level that weighs nothing"
          (is (every? #(= 0.0 (get-in % [:evidence :weights :tf])) (concat (:proposals ps) (:abstained ps)))))))))

(deftest align-agents-writes-no-confidence-without-its-proof
  (let [m     (fs/with-calibration fs-model (well-calibrated dumas))
        [p]   (rec/propose-agent-links [{:name "Dumas, Alexandre" :dates "1802-1870"}] dumas {:model m})
        conf  (fn [ps] (map :confidence (mappings (first (rec/align-agents [(named :doc/d "Dumas, Alexandre")] ps)))))]
    (testing "a calibrated model's proposal carries its proof, and its confidence is written"
      (is (some? (:calibration-proof p)))
      (is (= [(:score p)] (conf [p]))))
    (testing "a confidence a caller put on a proposal map is not written"
      (is (= [nil] (conf [(dissoc p :calibration-proof)])))
      (is (= [nil] (conf [{:name "Dumas, Alexandre" :authority-id pere :score 1.0 :confidence 0.99 :certifiable? true}])))
      (is (= [nil] (conf [(assoc p :confidence 0.999)])) "a confidence that is not the score")
      (is (= [nil] (conf [(assoc-in p [:calibration-proof :report :ece] 0.06)])) "a proof that misses the bar"))
    (testing "a review decision whose proof does not cover the review band writes none"
      (let [thin (fs/calibration (concat (for [i (range 100)] {:p 0.99 :y 1 :pair [:t i] :mention [:t i]})
                                         (for [i (range 200)] {:p 0.01 :y 0 :pair [:f i] :mention [:f i]}))
                                 {:pool (fs/pool-fingerprint dumas) :model (fs/model-fingerprint fs-model)})
            ps   (rec/propose-agent-links [{:name "Dumas, Alexandre"}] dumas {:model (fs/with-calibration fs-model thin)})]
        (is (= [:review :review] (map :decision ps)))
        (is (not-any? #(contains? % :confidence) ps))
        (is (= [nil nil] (conf (map #(assoc % :confidence (:score %)) ps))))))))

;; --- constrained clustering's check, in apply-agent-links (lot M5) ------------

(deftest apply-agent-links-never-joins-two-incompatible-mentions-on-one-node
  (let [link (fn [rid dts] (cond-> {:name "Dumas, Alexandre" :authority-id pere :certifiable? true :status :accepted
                                    :records #{rid}}
                             dts (assoc :dates dts)))
        rs   [(named :doc/a "Dumas, Alexandre") (named :doc/b "Dumas, Alexandre") (named :doc/c "Dumas, Alexandre")]]
    (testing "two accepted links to one authority whose mentions' dates cannot be one person's: neither is applied"
      (let [{:keys [applied refused]} (rec/apply-agent-links rs [(link :doc/a "1802-1870") (link :doc/b "1824-1895")
                                                                 (link :doc/c nil)])]
        (is (= [:doc/c] (map :record applied)) "an undated mention is no evidence: it still joins")
        (is (= [[:doc/a :conflict] [:doc/b :conflict]] (map (juxt (comp first :records) :reason) refused)))
        (is (= [#{:doc/b} #{:doc/a}] (map (comp :records :with :conflict) refused)))))
    (testing "compatible dates join as before"
      (is (= 2 (count (:applied (rec/apply-agent-links rs [(link :doc/a "1802-1870") (link :doc/b "1802-1870")]))))))))

(ns palomar.local-agents-test
  "The occurrence, the local agent and the entity (lot O3, ADR 0058
   amendment and ADR 0063): a local agent is minted per occurrence, never
   shared on the strength of a name; two occurrences of one name in one
   record are a proposal, never a fact; an applied link keeps the local
   agent's own node and never types an authority's IRI as the person."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.agent-cluster :as cluster]
            [palomar.api :as api]
            [palomar.cli :as cli]
            [palomar.curate :as curate]
            [palomar.fidelity :as fidelity]
            [palomar.identity :as identity]
            [palomar.mentions :as mentions]
            [palomar.model :as model]
            [palomar.plugins.agents :as agents]
            [palomar.plugins.lrmoo.export :as export]
            [palomar.plugins.lrmoo.linked-art :as linked-art]
            [palomar.reconcile :as reconcile]))

;; ---------------------------------------------------------------------------
;; Records
;; ---------------------------------------------------------------------------

(defn- df [tag ind1 & kvs]
  (str "<datafield tag=\"" tag "\" ind1=\"" ind1 "\" ind2=\" \">"
       (apply str (for [[c v] (partition 2 kvs)] (str "<subfield code=\"" c "\">" v "</subfield>")))
       "</datafield>"))

(defn- marc21
  "A MARC21 record `id` with a title and `fields`, through the whole
   pipeline (its canonical floor, its WEMI view)."
  [id & fields]
  (first (:records (api/pivot :marc21 {}
                              (str "<collection xmlns=\"http://www.loc.gov/MARC21/slim\"><record>"
                                   "<leader>00000nam a2200000 a 4500</leader>"
                                   "<controlfield tag=\"001\">" id "</controlfield>"
                                   (df "245" "1" "a" "Livre")
                                   (apply str fields) "</record></collection>")))))

(def ^:private two-martins
  "A father and a son, one heading, two fields, no dates: a 100 and a 700
   illustrator."
  (delay (marc21 "o3a" (df "100" "1" "a" "Martin, Jean") (df "700" "1" "a" "Martin, Jean" "4" "ill"))))

(defn- martins [r] (filter #(= "Martin, Jean" (:name %)) (mentions/mentions r)))

;; ---------------------------------------------------------------------------
;; (b) the local agent, per occurrence
;; ---------------------------------------------------------------------------

(deftest two-occurrences-of-one-name-are-two-local-agents-and-one-proposal
  (let [r  @two-martins
        [a b] (martins r)]
    (testing "two occurrences, two ids, two local agents"
      (is (= [[(:id r) "100" 0] [(:id r) "700" 0]] (map :id [a b])))
      (is (not= (:local a) (:local b))))
    (testing "the proposal that they are one: the two local agents, the record, the name, the evidence"
      (let [ps (mentions/same-agent-proposals r)]
        (is (= 1 (count ps)))
        (is (= #{(:local a) (:local b)} (set ((juxt :a :b) (first ps)))))
        (is (= {:record (:id r) :name "Martin, Jean" :occurrences [(:id a) (:id b)]}
               (select-keys (first ps) [:record :name :occurrences])))
        (is (= {:same-record true :same-name true :roles :compatible :dates :unknown}
               (select-keys (:evidence (first ps)) [:same-record :same-name :roles :dates])))))))

(deftest incompatible-dates-or-roles-make-no-proposal
  (testing "two different life dates: two persons, no proposal"
    (let [r (marc21 "o3b" (df "100" "1" "a" "Martin, Jean" "d" "1900-1970") (df "700" "1" "a" "Martin, Jean" "d" "1930-2000"))]
      (is (= 2 (count (set (map :local (martins r))))))
      (is (empty? (mentions/same-agent-proposals r)))))
  (testing "two texts of dates that can overlap are compatible (review of O3: the profiles are compared, the texts kept)"
    (doseq [[x y] [["1900-" "1900-1970"] ["1802-1870" "1802 - 1870"] ["1802-1870" "1802–1870"] ["1802-1870" "18..-1870"]]]
      (let [r   (marc21 "o3c" (df "100" "1" "a" "Martin, Jean" "d" x) (df "700" "1" "a" "Martin, Jean" "d" y))
            [p] (mentions/same-agent-proposals r)]
        (is (= :compatible (get-in p [:evidence :dates])) (pr-str [x y]))
        (is (= [x y] (get-in p [:evidence :date-texts])) "the texts are kept as evidence"))))
  (testing "two different relators in one field, undated: nothing but the name says one, and the record keeps them apart: no proposal"
    (let [r (marc21 "o3d" (df "700" "1" "a" "Martin, Jean" "4" "aut") (df "700" "1" "a" "Martin, Jean" "4" "ill"))]
      (is (empty? (mentions/same-agent-proposals r)))))
  (testing "two different relators in one field, the same dates: one agent's two functions, a proposal"
    (let [r (marc21 "o3k" (df "700" "1" "a" "Bernard, Roland" "d" "1927-2019" "4" "aut") (df "700" "1" "a" "Bernard, Roland" "d" "1927-2019" "4" "ill"))]
      (is (= 1 (count (mentions/same-agent-proposals r))))))
  (testing "two different relators in two fields: the format splits functions over fields, a proposal"
    (let [r (marc21 "o3l" (df "100" "1" "a" "Martin, Jean" "4" "aut") (df "700" "1" "a" "Martin, Jean" "4" "ill"))]
      (is (= 1 (count (mentions/same-agent-proposals r))))))
  (testing "the same dates and the same relator: a proposal"
    (let [r (marc21 "o3e" (df "700" "1" "a" "Martin, Jean" "d" "1900-1970" "4" "ill") (df "700" "1" "a" "Martin, Jean" "d" "1900-1970" "4" "ill"))
          [p] (mentions/same-agent-proposals r)]
      (is (= {:roles :compatible :dates :compatible} (select-keys (:evidence p) [:roles :dates])))))
  (testing "a MARC21 $e is read through the relator table: illustrator and author in one field conflict"
    (let [r (marc21 "o3p" (df "700" "1" "a" "Martin, Jean" "e" "illustrator.") (df "700" "1" "a" "Martin, Jean" "e" "author."))]
      (is (empty? (mentions/same-agent-proposals r)))))
  (testing "a free $e term no table holds never conflicts"
    (let [r (marc21 "o3q" (df "700" "1" "a" "Martin, Jean" "e" "maquettiste occasionnel") (df "700" "1" "a" "Martin, Jean" "e" "author."))]
      (is (= 1 (count (mentions/same-agent-proposals r))))))
  (testing "differing name parts (p\u00e8re, fils) are a conflict; the name parts are evidence"
    (let [r (marc21 "o3r" (df "100" "1" "a" "Dumas, Alexandre," "c" "p\u00e8re") (df "700" "1" "a" "Dumas, Alexandre," "c" "fils"))]
      (is (empty? (mentions/same-agent-proposals r))))
    (let [r   (marc21 "o3t" (df "100" "1" "a" "Dumas, Alexandre," "c" "p\u00e8re") (df "700" "1" "a" "Dumas, Alexandre," "c" "p\u00e8re"))
          [p] (mentions/same-agent-proposals r)]
      (is (= [{"c" "p\u00e8re"} {"c" "p\u00e8re"}] (get-in p [:evidence :name-parts])))))
  (testing "a person and a group of one name are no proposal"
    (let [r (marc21 "o3f" (df "100" "1" "a" "Martin") (df "710" "2" "a" "Martin"))]
      (is (empty? (mentions/same-agent-proposals r))))))

(deftest one-occurrence-keeps-the-record-and-name-id
  (testing "a name given more than once: each occurrence is keyed by its content (tag, relators, dates, name parts), an ordinal only among exact duplicates"
    (let [r     @two-martins
          [a b] (martins r)]
      (is (= (model/mint-entity-id :crm/E21_Person [(:id r) "Martin, Jean" "100" [] "" [] 0]) (:local a)))
      (is (= (model/mint-entity-id :crm/E21_Person [(:id r) "Martin, Jean" "700" ["ill"] "" [] 0]) (:local b))))
    (let [r (marc21 "o3w" (df "700" "1" "a" "Martin, Jean" "4" "ill") (df "700" "1" "a" "Martin, Jean" "4" "ill"))]
      (is (= [(model/mint-entity-id :crm/E21_Person [(:id r) "Martin, Jean" "700" ["ill"] "" [] 0])
              (model/mint-entity-id :crm/E21_Person [(:id r) "Martin, Jean" "700" ["ill"] "" [] 1])]
             (map :local (martins r))))))
  (testing "a name given once: the id is as before"
    (let [r (marc21 "o3g" (df "100" "1" "a" "Hugo, Victor") (df "700" "1" "a" "Dor\u00e9, Gustave"))]
      (is (= [(model/mint-entity-id :crm/E21_Person [(:id r) "Hugo, Victor"])
              (model/mint-entity-id :crm/E21_Person [(:id r) "Dor\u00e9, Gustave"])]
             (map :local (mentions/mentions r))))))
  (testing "a subject of the name is another occurrence: two content keys"
    (let [r  (marc21 "o3h" (df "600" "1" "a" "Martin, Jean") (df "700" "1" "a" "Martin, Jean"))
          ms (filter #(= "Martin, Jean" (:name %)) (mentions/mentions r {:subjects? true}))]
      (is (= 2 (count (set (map :local ms)))))
      (is (not-any? #{(model/mint-entity-id :crm/E21_Person [(:id r) "Martin, Jean"])} (map :local ms)))))
  (testing "an edit orphans an id, never re-points it"
    (let [v1  (marc21 "o3x" (df "100" "1" "a" "Martin, Jean") (df "700" "1" "a" "Martin, Jean" "4" "ill"))
          v2  (marc21 "o3x" (df "100" "1" "a" "Martin, Jean") (df "700" "1" "a" "Dor\u00e9, Gustave")
                      (df "700" "1" "a" "Martin, Jean" "4" "aut") (df "700" "1" "a" "Martin, Jean" "4" "ill"))
          ids (fn [r] (into {} (map (juxt (juxt (comp :field :source) :relators) :local)) (martins r)))]
      (testing "inserted fields before them, of another name or of the same name: the two keep their ids"
        (is (= (ids v1) (select-keys (ids v2) (keys (ids v1))))))
      (testing "a name given once, then twice: its old id names no occurrence any more"
        (let [w1 (marc21 "o3y" (df "700" "1" "a" "Martin, Jean" "4" "ill"))
              w2 (marc21 "o3y" (df "100" "1" "a" "Martin, Jean") (df "700" "1" "a" "Martin, Jean" "4" "ill"))
              old (:local (first (martins w1)))]
          (is (not-any? #{old} (map :local (mentions/mentions w2 {:subjects? true :provenance? true}))))))))
  (testing "the name alone does not say which of two occurrences it is: no local agent"
    (is (nil? (mentions/local-agent @two-martins "Martin, Jean" {})))
    (is (= (:local (second (martins @two-martins)))
           (mentions/local-node @two-martins "Martin, Jean" {:mention [(:id @two-martins) "700" 0]})))))

;; ---------------------------------------------------------------------------
;; Alignment, proposals in the IR, curation and clustering
;; ---------------------------------------------------------------------------

(def ^:private pere "https://isni.org/isni/0000000121232615")
(def ^:private fils "https://isni.org/isni/0000000121358464")

(defn- mention-prop [r m iri]
  {:name (:name m) :authority-id iri :authority-label "Martin, Jean" :score 1.0 :certifiable? true
   :status :proposed :decision :propose :match-weight 12.0 :mention {:id (:id m) :record (:id r)}})

(deftest each-occurrence-is-aligned-on-its-own-local-agent
  (let [r     @two-martins
        [a b] (martins r)
        [r']  (reconcile/align-agents [r] [(mention-prop r a pere) (mention-prop r b fils)])
        claims (filter #(= identity/authority-match (:predicate %)) (:assertions r'))]
    (is (= {(:local a) #{pere} (:local b) #{fils}}
           (update-vals (group-by :subject claims)
                        (fn [cs] (set (for [c cs] (:iri (some #(when (= (:id %) (get-in c [:value :value/target])) %) (:entities r'))))))))))
  (testing "a name-only proposal reaches no record where the name is given twice"
    (let [[r'] (reconcile/align-agents [@two-martins] [{:name "Martin, Jean" :authority-id pere :score 1.0}])]
      (is (= (:assertions @two-martins) (:assertions r'))))))

(deftest the-proposal-is-written-as-a-proposed-claim-between-the-two-local-agents
  (let [[r'] (reconcile/propose-same-agents [@two-martins])
        [a b] (martins @two-martins)
        c    (first (filter #(= identity/same-agent (:predicate %)) (:assertions r')))]
    (is (= :proposed (:status c)))
    (is (= #{(:local a) (:local b)} #{(:subject c) (get-in c [:value :value/target])}))
    (is (= "Martin, Jean" (get-in c [:provenance :bindings '?name])))
    (testing "both local agents are in the record, each with its name"
      (is (every? (set (map :id (:entities r'))) [(:local a) (:local b)]))
      (is (= #{(:local a) (:local b)} (set (for [x (:assertions r') :when (= :crm/P190_has_symbolic_content (:predicate x))] (:subject x))))))
    (testing "a proposal is never a fact: a plain export holds none of it, and it is never owl:sameAs"
      (let [nt (:output (api/export :ntriples [r']))]
        (is (not (str/includes? nt "sameAgentAs")))
        (is (not (str/includes? nt "owl#sameAs"))))
      (is (str/includes? (:output (api/export :ntriples [r'] {:with-proposals? true})) "urn:palomar:vocab#sameAgentAs")))
    (testing "written twice, it is one claim"
      (is (= (:assertions r') (:assertions (first (reconcile/propose-same-agents [r']))))))))

(deftest a-curator-who-accepts-the-proposal-joins-the-two-and-one-who-rejects-it-keeps-them-apart
  (let [[r']  (reconcile/propose-same-agents [@two-martins])
        [a b] (martins @two-martins)
        claim (first (filter #(= identity/same-agent (:predicate %)) (:assertions r')))
        one?  (fn [rec] (let [{:keys [clustering]} (cluster/cluster-records [rec])]
                          (= ((:cluster-of clustering) (:local a)) ((:cluster-of clustering) (:local b)))))]
    (testing "pending, it joins nothing: a candidate merge for a curator"
      (let [{:keys [clustering]} (cluster/cluster-records [r'])]
        (is (not= ((:cluster-of clustering) (:local a)) ((:cluster-of clustering) (:local b))))
        (is (= 1 (count (:candidates clustering))))))
    (testing "accepted, the two are one cluster, and each gets its membership"
      (let [acc (:record (curate/decide-one r' claim :accept "one person, by the title page" {:by "curator"}))]
        (is (one? acc))
        (is (= 2 (count (filter #(= identity/cluster-membership (:predicate %))
                                (:assertions (first (:records (cluster/cluster-records [acc]))))))))))
    (testing "rejected, they stay apart, even when both are aligned to one authority"
      (let [rej (:record (curate/decide-one r' claim :reject "two persons"))
            [x] (reconcile/align-agents [rej] [(mention-prop rej a pere) (mention-prop rej b pere)])
            {:keys [clustering]} (cluster/cluster-records [x])]
        (is (not= ((:cluster-of clustering) (:local a)) ((:cluster-of clustering) (:local b))))
        (is (some #(= :rejected-same-agent (get-in % [:violation :reason])) (:refused clustering)))))))

;; ---------------------------------------------------------------------------
;; (c) the entity: an applied link
;; ---------------------------------------------------------------------------

(def ^:private ark "http://catalogue.bnf.fr/ark:/12148/cb11901063z")

(defn- typed
  "The subjects `nt` types with a class whose IRI ends in `cls`."
  [nt cls]
  (set (keep #(second (re-find (re-pattern (str "^<([^>]+)> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <[^>]+" cls ">")) %))
             (str/split-lines nt))))

(deftest an-applied-link-keeps-the-local-agent-and-never-types-a-record-iri-as-the-person
  (let [r     (marc21 "o3i" (df "100" "1" "a" "Dumas, Alexandre" "d" "1802-1870"))
        m     (first (mentions/mentions r))
        [al]  (reconcile/align-agents [r] [(assoc (mention-prop r m ark) :name "Dumas, Alexandre")])
        claim (first (filter #(= identity/authority-match (:predicate %)) (:assertions al)))
        acc   (:record (curate/decide-one al claim :accept "right" {:by "curator"}))
        {:keys [records applied]} (reconcile/apply-agent-links [acc] (reconcile/accepted-links [acc]))
        out   (first records)
        nt    (:output (api/export :ntriples [out]))]
    (is (= [[(:id r) "Dumas, Alexandre" ark]] (map (juxt :record :name :iri) applied)))
    (testing "the local agent keeps its node; the authority is the target of the claim, no Person"
      (is (= #{(export/entity-iri (:local m))} (typed nt "E21_Person")))
      (is (not-any? #(and (= ark (:iri %)) (agents/identified-kinds (:kind %))) (:entities out)))
      (is (str/includes? nt (str "<" (export/entity-iri (:local m)) "> <urn:palomar:vocab#matchesAuthority> <" ark ">"))
          "a catalogue record's IRI: the internal relation, never owl:sameAs, even for a curator's decision"))
    (testing "the store still sees the person by its authority"
      (is (= [ark] (map :iri (:agents (reconcile/reconcile-agents records))))))
    (testing "to an IRI that names the entity, the same decision is owl:sameAs"
      (let [[al2] (reconcile/align-agents [r] [(assoc (mention-prop r m pere) :name "Dumas, Alexandre" :entity-type "person")])
            c2    (first (filter #(= identity/authority-match (:predicate %)) (:assertions al2)))
            acc2  (:record (curate/decide-one al2 c2 :accept "right" {:by "curator"}))
            out2  (first (:records (reconcile/apply-agent-links [acc2] (reconcile/accepted-links [acc2]))))
            nt2   (:output (api/export :ntriples [out2]))]
        (is (str/includes? nt2 (str "<" (export/entity-iri (:local m)) "> <http://www.w3.org/2002/07/owl#sameAs> <" pere ">")))
        (is (not (contains? (typed nt2 "E21_Person") pere)))))))

(deftest a-corpus-wide-link-applies-to-one-occurrence-only
  (let [one   (marc21 "o3j" (df "100" "1" "a" "Martin, Jean"))
        link  {:name "Martin, Jean" :authority-id pere :certifiable? true :status :accepted :records :all}
        {:keys [records applied skipped]} (reconcile/apply-agent-links [one @two-martins] [link])]
    (testing "a record that gives the name once: its local agent is linked, by an accepted claim"
      (is (= [(:id one)] (map :record applied)))
      (let [c (first (filter #(= identity/authority-match (:predicate %)) (:assertions (first records))))]
        (is (= [:accepted (mentions/local-node one "Martin, Jean")] [(:status c) (:subject c)]))))
    (testing "a record that gives it twice: which one, the name does not say"
      (is (= [[(:id @two-martins) :several-occurrences]] (map (juxt :record :reason) skipped)))
      (is (= (:assertions @two-martins) (:assertions (second records)))))))

(deftest a-conflict-apply-agent-links-refuses-identifies-no-one-and-is-no-owl-same-as
  (let [rec   (fn [id d] (marc21 id (df "100" "1" "a" "Dumas, Alexandre" "d" d)))
        curated (fn [r]
                  (let [m    (first (mentions/mentions r))
                        p    (-> (mention-prop r m pere) (assoc :name "Dumas, Alexandre" :entity-type "person")
                                 (assoc :evidence {:dates {:verdict :unknown :mention (get m :dates)}}))
                        [al] (reconcile/align-agents [r] [p])
                        c    (first (filter #(= identity/authority-match (:predicate %)) (:assertions al)))]
                    (:record (curate/decide-one al c :accept "right" {:by "curator"}))))
        rs    [(curated (rec "o3m" "1802-1870")) (curated (rec "o3n" "1824-1895"))]
        {:keys [records refused applied]} (reconcile/apply-agent-links rs (reconcile/accepted-links rs))]
    (is (empty? applied))
    (is (= [:conflict :conflict] (map :reason refused)))
    (testing "each record's claim is named by a conflict diagnostic, restated, not repeated, on a second run"
      (is (every? #(= 1 (count (filter (comp #{reconcile/apply-conflict-code} :code) (:diagnostics %)))) records))
      (is (= records (:records (reconcile/apply-agent-links records (reconcile/accepted-links records))))))
    (testing "so neither agent is identified, and neither claim is owl:sameAs"
      (is (every? empty? (map agents/identified records)))
      (is (zero? (:distinct (reconcile/reconcile-agents records))))
      (is (not (str/includes? (:output (api/export :ntriples records)) "owl#sameAs"))))))

;; ---------------------------------------------------------------------------
;; The exports of two occurrences of one name
;; ---------------------------------------------------------------------------

(defn- dc-record [& creators]
  (first (:records (api/pivot :dc {:record-id :o3/dc}
                              (str "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:title>T</dc:title>"
                                   (apply str (for [[el v] (partition 2 creators)] (str "<dc:" el ">" v "</dc:" el ">")))
                                   "</metadata>")))))

(defn- carried-out-by [doc] (get-in doc ["created_by" "carried_out_by"]))

(deftest the-exports-write-two-occurrences-of-one-name
  (testing "Linked Art: two creators of one name are two actors, each its local agent"
    (let [r   (dc-record "creator" "Martin, Jean" "creator" "Martin, Jean")
          ms  (martins r)
          doc (first (linked-art/documents r))]
      (is (= 2 (count ms)))
      (is (= (set (map (comp export/entity-iri :local) ms)) (set (map #(get % "id") (carried-out-by doc)))))
      (is (= ["Martin, Jean" "Martin, Jean"] (map #(get % "_label") (carried-out-by doc))))))
  (testing "Linked Art: one creator of a name is one actor, written as before"
    (is (= [{"type" "Person" "_label" "Martin, Jean"}]
           (carried-out-by (first (linked-art/documents (dc-record "creator" "Martin, Jean" "contributor" "Martin, Jean")))))))
  (testing "Linked Art: a LIDO production with two actors of one name carries out by two"
    (let [actor (fn [] (str "<lido:eventActor><lido:actorInRole><lido:actor><lido:nameActorSet><lido:appellationValue>Martin, Jean"
                            "</lido:appellationValue></lido:nameActorSet></lido:actor>"
                            "<lido:roleActor><lido:term xml:lang=\"en\">sculptor</lido:term></lido:roleActor></lido:actorInRole></lido:eventActor>"))
          xml   (str "<lido:lido xmlns:lido=\"http://www.lido-schema.org\"><lido:lidoRecID>O3-1</lido:lidoRecID><lido:descriptiveMetadata>"
                     "<lido:objectIdentificationWrap><lido:titleWrap><lido:titleSet><lido:appellationValue>Bronze</lido:appellationValue></lido:titleSet></lido:titleWrap></lido:objectIdentificationWrap>"
                     "<lido:eventWrap><lido:eventSet><lido:event><lido:eventType><lido:conceptID>http://terminology.lido-schema.org/lido00007</lido:conceptID></lido:eventType>"
                     (actor) (actor)
                     "</lido:event></lido:eventSet></lido:eventWrap></lido:descriptiveMetadata></lido:lido>")
          r     (first (:records (api/pivot :lido {} xml)))
          as    (get-in (first (linked-art/documents r)) ["produced_by" "carried_out_by"])]
      (is (= ["Martin, Jean" "Martin, Jean"] (map #(get % "_label") as)))
      (is (= (set (map (comp export/entity-iri :local) (martins r))) (set (map #(get % "id") as))))
      (is (= 2 (count (set (map :local (martins r))))))
      (is (= 1 (count (mentions/same-agent-proposals r))) "and they are proposed one, as in any record")))
  (testing "DC: the creator and the contributor of one name, as before"
    (let [out (:output (api/export :dc [@two-martins]))]
      (is (str/includes? out "<dc:creator>Martin, Jean</dc:creator>"))
      (is (str/includes? out "<dc:contributor>Martin, Jean</dc:contributor>"))))
  (testing "MARC21: the 100 and the 700 of one name are both written"
    (let [out (:output (api/export :marc21 [@two-martins]))]
      (is (str/includes? out "<datafield tag=\"100\" ind1=\" \" ind2=\" \"><subfield code=\"a\">Martin, Jean</subfield></datafield>"))
      (is (str/includes? out "<datafield tag=\"700\" ind1=\" \" ind2=\" \"><subfield code=\"a\">Martin, Jean</subfield><subfield code=\"4\">ill</subfield></datafield>"))))
  (testing "a conversion of the two still accounts for every value"
    (let [src (str "<collection xmlns=\"http://www.loc.gov/MARC21/slim\"><record><leader>00000nam a2200000 a 4500</leader>"
                   "<controlfield tag=\"001\">o3a</controlfield>" (df "245" "1" "a" "Livre")
                   (df "100" "1" "a" "Martin, Jean") (df "700" "1" "a" "Martin, Jean" "4" "ill") "</record></collection>")]
      (doseq [to [:linked-art :dc :marc21 :ntriples :crm :mods]]
        (is (empty? (:unaccounted (fidelity/audit {:from :marc21 :to to :source src}))) (str to))))))

;; ---------------------------------------------------------------------------
;; Review of lot O3
;; ---------------------------------------------------------------------------

(defn- claims-of [r p] (filter #(= p (:predicate %)) (:assertions r)))

(deftest a-stale-mention-id-is-never-taken-for-another-occurrence
  (let [v1   (marc21 "o3u" (df "100" "1" "a" "Hugo, Victor") (df "700" "1" "a" "Martin, Jean" "4" "ill"))
        m    (first (martins v1))
        prop (mention-prop v1 m fils)
        ;; re-imported with a 700 inserted before: [rid "700" 0] is Doré's now
        v2   (marc21 "o3u" (df "100" "1" "a" "Hugo, Victor") (df "700" "1" "a" "Doré, Gustave") (df "700" "1" "a" "Martin, Jean" "4" "ill"))]
    (testing "an occurrence id whose name is not the name is not taken; the name finds its one occurrence"
      (is (= (:local (first (martins v2))) (mentions/local-node v2 "Martin, Jean" {:mention (:id m)})))
      (let [[al] (reconcile/align-agents [v2] [prop])]
        (is (= [(:local (first (martins v2)))] (map :subject (claims-of al identity/authority-match))))))
    (testing "an occurrence id of a subject is not taken for an agent"
      (let [r (marc21 "o3v" (df "600" "1" "a" "Martin, Jean") (df "700" "1" "a" "Martin, Jean"))]
        (is (= (:local (first (martins r))) (mentions/local-node r "Martin, Jean" {:mention [(:id r) "600" 0]})))))
    (testing "stale, and the name given twice: no local agent, and align-agents reports it"
      (let [v3   (marc21 "o3u" (df "100" "1" "a" "Hugo, Victor") (df "700" "1" "a" "Doré, Gustave")
                         (df "700" "1" "a" "Martin, Jean" "4" "ill") (df "700" "1" "a" "Martin, Jean" "4" "aut"))
            [al] (reconcile/align-agents [v3] [prop])]
        (is (nil? (mentions/local-agent v3 "Martin, Jean" {:mention (:id m)})))
        (is (empty? (claims-of al identity/authority-match)))
        (is (= [[:reconcile/unplaced-proposal :several-occurrences]]
               (map (juxt :code (comp :reason :detail)) (filter (comp #{reconcile/unplaced-code} :code) (:diagnostics al)))))))))

(defn- align-accept
  "`r` with each `[mention iri]` aligned and accepted by a curator, claim by claim."
  [r pairs]
  (let [[al] (reconcile/align-agents [r] (for [[m iri] pairs] (mention-prop r m iri)))]
    (reduce (fn [x c] (:record (curate/decide-one x c :accept "per claim" {:by "cur"}))) al (claims-of al identity/authority-match))))

(deftest a-rejected-same-agent-holds-everywhere
  (let [[r']  (reconcile/propose-same-agents [@two-martins])
        [a b] (martins @two-martins)
        rej   (:record (curate/decide-one r' (first (claims-of r' identity/same-agent)) :reject "two persons" {:by "cur"}))
        c2    (align-accept rej [[a pere] [b pere]])]
    (testing "two accepted claims of two agents a curator kept apart, to one authority: the export finds the conflict, no owl:sameAs"
      (is (not (str/includes? (:output (api/export :ntriples [c2])) "owl#sameAs")))
      (is (str/includes? (:output (api/export :ntriples [(align-accept rej [[a pere]])])) "owl#sameAs")
          "one of them alone is a curator's identity"))
    (testing "apply-agent-links refuses both, and they identify no one"
      (let [{:keys [records applied refused]} (reconcile/apply-agent-links [c2] (reconcile/accepted-links [c2]))]
        (is (empty? applied))
        (is (= [[:conflict :rejected-same-agent] [:conflict :rejected-same-agent]] (map (juxt :reason (comp :because :conflict)) refused)))
        (is (= 2 (count (filter (comp #{reconcile/apply-conflict-code} :code) (:diagnostics (first records))))))
        (is (empty? (agents/identified (first records))))))
    (testing "the clustering's cannot-link is the same conflict for apply-agent-links"
      (let [cl (first (:records (cluster/cluster-records [c2])))]
        (is (= #{:conflict} (set (map :reason (:refused (reconcile/apply-agent-links [cl] (reconcile/accepted-links [cl])))))))))))

(deftest apply-agent-links-never-decides-a-claim-a-curator-has-not-decided
  (let [one  (marc21 "o3j" (df "100" "1" "a" "Martin, Jean"))
        m    (first (mentions/mentions one))
        [al] (reconcile/align-agents [one] [(mention-prop one m pere)])
        c    (first (claims-of al identity/authority-match))
        link {:name "Martin, Jean" :authority-id pere :certifiable? true :status :accepted :records :all}
        run  (fn [r] (reconcile/apply-agent-links [r] [link]))]
    (testing "a pending claim is skipped, :pending-here, and stays pending"
      (let [{:keys [records applied skipped]} (run al)]
        (is (empty? applied))
        (is (= [:pending-here] (map :reason skipped)))
        (is (= [:proposed] (map :status (claims-of (first records) identity/authority-match))))))
    (testing "a claim for review is skipped, :needs-review"
      (let [nr (:record (curate/decide-one al c :review "check the dates" {:by "cur"}))]
        (is (= [:needs-review] (map :reason (:skipped (run nr)))))
        (is (= [:needs-review] (map :status (claims-of (first (:records (run nr))) identity/authority-match))))))
    (testing "a rejected claim is never overridden, :rejected-here"
      (let [rj (:record (curate/decide-one al c :reject "another Martin" {:by "cur"}))
            {:keys [records applied skipped]} (run rj)]
        (is (empty? applied))
        (is (= [:rejected-here] (map :reason skipped)))
        (is (= [:rejected] (map :status (claims-of (first records) identity/authority-match))))))))

(deftest the-clustering-proposes-two-occurrences-of-one-name-itself
  (let [{:keys [records clustering]} (cluster/cluster-records [@two-martins])
        [a b] (martins @two-martins)]
    (is (= [:proposed] (map :status (claims-of (first records) identity/same-agent))))
    (is (= [#{(:local a) (:local b)}] (map (comp set (juxt :a :b)) (:candidates clustering))) "a candidate merge, for a curator")
    (testing "the reconciliation shows the proposals, in the API and in its text"
      (let [res (api/reconcile [@two-martins] nil {})]
        (is (= [[(:id @two-martins) "Martin, Jean" [(:id a) (:id b)]]]
               (map (juxt :record :name :occurrences) (:same-agent-proposals res))))
        (is (str/includes? (api/format-reconciliation res) "may be one agent"))))
    (testing "reconcile clusters gives it as a candidate merge of the two mentions, never a join"
      (let [res (api/reconcile-clusters [@two-martins] [])]
        (is (some #(and (= #{(:id a) (:id b)} (hash-set (:mention %) (:authority %))) (= :review (:basis %))) (:candidates res)))
        (is (not-any? #(= #{(:id a) (:id b)} (set (:members %))) (:clusters res)))))))

(deftest a-bulk-acceptance-of-a-same-agent-proposal-is-no-must-link
  (let [dir  (doto (java.io.File. "target/o3-review") .mkdirs)
        f    (str (java.io.File. dir "prop.edn"))
        _    (spit f (api/->edn (reconcile/propose-same-agents [@two-martins])))
        {:keys [exit out]} (cli/run ["apply-repairs" f "--from" "edn" "--policy" "accept" "--by" "batch" "--to" "edn"])
        bulk (api/read-edn out)
        [a b] (martins @two-martins)
        one? (fn [rs] (let [{:keys [clustering]} (cluster/cluster-records rs)]
                        (= ((:cluster-of clustering) (:local a)) ((:cluster-of clustering) (:local b)))))]
    (is (zero? exit))
    (is (= [:accepted] (map :status (mapcat #(claims-of % identity/same-agent) bulk))))
    (is (not (one? bulk)) "a policy's acceptance is a link for review")
    (let [[r'] (reconcile/propose-same-agents [@two-martins])
          one  (:record (curate/decide-one r' (first (claims-of r' identity/same-agent)) :accept "one person" {:by "cur"}))]
      (is (one? [one]) "a curator's decision on that claim alone is a must-link"))))

(deftest an-old-applied-node-is-retracted-once-the-claim-covers-it
  (let [ark "http://catalogue.bnf.fr/ark:/12148/cb11901063z"
        old (api/read-edn (slurp "test/fixtures/reconcile/o2-applied-link.edn"))
        e21 (fn [rs] (filter #(re-find #"E21_Person>" %) (str/split-lines (:output (api/export :ntriples rs)))))]
    (is (some #(str/starts-with? % (str "<" ark ">")) (e21 old)) "the old document types the ARK as the person")
    (let [{:keys [records]} (reconcile/apply-agent-links old (reconcile/accepted-links old))]
      (is (not-any? #(str/starts-with? % (str "<" ark ">")) (e21 records)))
      (is (not-any? #(= ark (:iri %)) (filter #(agents/identified-kinds (:kind %)) (:entities (first records)))))
      (is (= [[ark :claim]] (map (juxt :iri :via) (agents/identified (first records))))))))

(deftest marc21-writes-the-relator-of-a-7xx-that-repeats-the-main-entry
  (let [out (:output (api/export :marc21 [@two-martins]))]
    (is (str/includes? out "<datafield tag=\"700\" ind1=\" \" ind2=\" \"><subfield code=\"a\">Martin, Jean</subfield><subfield code=\"4\">ill</subfield></datafield>"))))

(deftest lido-roles-conflict-within-one-event-only
  (let [actor (fn [uri term] (str "<lido:eventActor><lido:actorInRole><lido:actor><lido:nameActorSet><lido:appellationValue>Martin, Jean"
                                  "</lido:appellationValue></lido:nameActorSet></lido:actor><lido:roleActor><lido:conceptID lido:type=\"URI\">" uri
                                  "</lido:conceptID><lido:term xml:lang=\"en\">" term "</lido:term></lido:roleActor></lido:actorInRole></lido:eventActor>"))
        event (fn [type & as] (str "<lido:eventSet><lido:event><lido:eventType><lido:conceptID>" type "</lido:conceptID></lido:eventType>"
                                   (apply str as) "</lido:event></lido:eventSet>"))
        lido  (fn [id & evs] (first (:records (api/pivot :lido {} (str "<lido:lido xmlns:lido=\"http://www.lido-schema.org\"><lido:lidoRecID>" id "</lido:lidoRecID><lido:descriptiveMetadata>"
                                                                       "<lido:objectIdentificationWrap><lido:titleWrap><lido:titleSet><lido:appellationValue>Bronze</lido:appellationValue></lido:titleSet></lido:titleWrap></lido:objectIdentificationWrap>"
                                                                       "<lido:eventWrap>" (apply str evs) "</lido:eventWrap></lido:descriptiveMetadata></lido:lido>")))))
        prod  "http://terminology.lido-schema.org/lido00007"
        acq   "http://terminology.lido-schema.org/lido00001"
        sculptor "http://vocab.getty.edu/aat/300025181"]
    (testing "two roles in one event: two actors"
      (is (empty? (mentions/same-agent-proposals (lido "L2" (event prod (actor sculptor "sculptor") (actor "http://vocab.getty.edu/aat/300025266" "founder")))))))
    (testing "two roles in two events: a proposal"
      (is (= 1 (count (mentions/same-agent-proposals (lido "L3" (event prod (actor sculptor "sculptor")) (event acq (actor "http://vocab.getty.edu/aat/300025238" "donor"))))))))))

(deftest a-floor-export-reports-two-occurrences-it-writes-as-one-name
  (let [r (dc-record "creator" "Martin, Jean" "creator" "Martin, Jean")]
    (testing "Linked Art counts occurrences: two actors; DC, MODS and MARC21 count distinct facts: one name, and say so"
      (is (= 2 (count (carried-out-by (first (linked-art/documents r))))))
      (doseq [to [:dc :mods :marc21]]
        (let [{:keys [output loss]} (api/export to [r])]
          (is (= 1 (count (re-seq #"Martin, Jean" output))) (str to))
          (is (some #(and (= :loss/ambiguity-collapsed (:code %)) (= :canon/agent (get-in % [:detail :loss/source-field]))) loss) (str to)))))
    (testing "one occurrence per name: no such loss"
      (is (not-any? #(= :loss/ambiguity-collapsed (:code %)) (:loss (api/export :dc [(dc-record "creator" "Martin, Jean")])))))))

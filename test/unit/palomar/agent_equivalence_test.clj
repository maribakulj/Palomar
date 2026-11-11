(ns palomar.agent-equivalence-test
  "Record identity is not entity identity (lot N6, ADR 0059): two authority
   records are one entity only when the records state it — a shared ISNI or
   VIAF id, or a `sameAs` one gives to the other — and then the clustering's
   `:distinct-authorities` constraint is lifted for them, with the
   equivalence as the cluster's provenance. An equivalence chain that would
   join two records stating different ISNIs is refused and reported."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.agent-cluster :as ac]
            [palomar.agent-dates :as dates]
            [palomar.agent-equivalence :as eq]
            [palomar.curate :as curate]
            [palomar.eval.gnd-person-reconciliation-test :as gt]
            [palomar.model :as model]
            [palomar.plugins.authorities :as authorities]
            [palomar.plugins.gnd :as gnd]
            [palomar.reconcile :as rec]))

(def ^:private bnf-iri "http://catalogue.bnf.fr/ark:/12148/cb119010630")
(def ^:private bnf2-iri "http://catalogue.bnf.fr/ark:/12148/cb11901062n")
(def ^:private gnd-iri "https://d-nb.info/gnd/118528408")
(def ^:private loc-iri "http://id.loc.gov/authorities/names/n79091479")
(def ^:private isni "0000000121012885")
(def ^:private isni-url (str "https://isni.org/isni/" isni))

(defn- members-of [result] (set (map :members (:clusters result))))

(defn- two-mentions
  "Mention m1 proposed outright for authority :x, m2 for :y, the two
   authorities given as `x` and `y`."
  [x y]
  {:items {:m1 {} :m2 {} :x (assoc x :authority? true) :y (assoc y :authority? true)}
   :links [{:a :m1 :b :x :basis :propose :weight 9.0}
           {:a :m2 :b :y :basis :propose :weight 8.0}]})

;; ---------------------------------------------------------------------------
;; Identifiers
;; ---------------------------------------------------------------------------

(deftest an-identifier-is-read-in-one-form-whatever-its-spelling
  (is (= "isni:0000000121012885" (eq/identifier-key "0000 0001 2101 2885")) "an ISNI as a record writes it")
  (is (= "isni:0000000121012885" (eq/identifier-key "https://isni.org/isni/0000000121012885")))
  (is (= "isni:000000010000005X" (eq/identifier-key "http://www.isni.org/isni/000000010000005x")) "X in either case")
  (is (= "viaf:44300868" (eq/identifier-key "http://viaf.org/viaf/44300868")))
  (is (= "ark:/12148/cb119010630" (eq/identifier-key bnf-iri)))
  (is (= "ark:/12148/cb119010630" (eq/identifier-key "https://data.bnf.fr/ark:/12148/cb119010630#about"))
      "the BnF catalogue and data.bnf.fr name one record by one ARK")
  (is (= "gnd:118528408" (eq/identifier-key gnd-iri)))
  (is (= "gnd:118528408" (eq/identifier-key "http://d-nb.info/gnd/118528408/about/html")))
  (is (= "lcnaf:n79091479" (eq/identifier-key loc-iri)))
  (is (= "wikidata:Q38337" (eq/identifier-key "http://www.wikidata.org/entity/Q38337")))
  (is (nil? (eq/identifier-key "0000 0001")) "a number that is no ISNI is none")
  (is (nil? (eq/identifier-key " ")))
  (is (= "http://example.org/a" (eq/identifier-key "http://example.org/a")) "another IRI is itself"))

(deftest every-spelling-of-an-id-is-read-and-each-id-ends-where-it-ends
  (doseq [[k ss] {"isni:0000000121012885"  ["ISNI 0000 0001 2101 2885" "ISNI:0000 0001 2101 2885" "isni:0000000121012885"
                                            "ISNI0000000121012885" "0000-0001-2101-2885" "isni.org/isni/0000000121012885"
                                            "https://isni.org/isni/0000 0001 2101 2885" "http://isni-url.oclc.nl/isni/0000000121012885"]
                  "viaf:44300868"          ["http://viaf.org/viaf/44300868/" "https://viaf.org/viaf/44300868/viaf.xml"
                                            "https://viaf.org/en/viaf/44300868" "HTTP://VIAF.ORG/VIAF/44300868" "http://viaf.org/viaf/44300868 "]
                  "ark:/12148/cb119010630" ["ark:/12148/cb119010630" "http://catalogue.bnf.fr/ark:/12148/cb119010630/PUBLIC"
                                            "https://data.bnf.fr/ark:/12148/cb119010630/" "http://catalogue.bnf.fr/ark:/12148/CB119010630"
                                            "https://n2t.net/ark:/12148/cb119010630" "ark:12148/cb119010630"]
                  "bnf-record:11901063"    ["http://data.bnf.fr/11901063/alexandre_dumas/" "https://data.bnf.fr/fr/11901063/alexandre_dumas/"]
                  "gnd:118528408"          ["http://d-nb.info/gnd/118528408" "https://lobid.org/gnd/118528408" "https://lobid.org/gnd/118528408.json"]
                  "gnd:111910582X"         ["https://d-nb.info/gnd/111910582x" "https://d-nb.info/gnd/111910582X"]
                  "gnd:111910582"          ["https://d-nb.info/gnd/111910582"]
                  "gnd:4005728-8"          ["https://d-nb.info/gnd/4005728-8"]
                  "lcnaf:n79091479"        ["http://id.loc.gov/authorities/names/n79091479.html" "http://id.loc.gov/rwo/agents/n79091479"
                                            "info:lc/authorities/n79091479"]
                  "wikidata:Q38337"        ["https://www.wikidata.org/wiki/Q38337" "http://www.wikidata.org/entity/q38337"
                                            "https://www.wikidata.org/wiki/Special:EntityPage/Q38337"]}
          s ss]
    (is (= k (eq/identifier-key s)) (pr-str s)))
  (testing "an id is not a prefix of a longer one"
    (is (not= (eq/identifier-key "http://viaf.org/viaf/443008681") (eq/identifier-key "http://viaf.org/viaf/44300868")))
    (is (= "http://www.wikidata.org/entity/Q38337x" (eq/identifier-key "http://www.wikidata.org/entity/Q38337x"))))
  (testing "another file's ARK is another id; a host's path that only looks like one is itself"
    (is (= "ark:/99999/cb119010630" (eq/identifier-key "http://other.org/ark:/99999/cb119010630")))
    (is (= "http://example.org/x#isni.org/isni/0000000121012885" (eq/identifier-key "http://example.org/x#isni.org/isni/0000000121012885")))
    (is (= "http://id.loc.gov/authorities/subjects/sh85123456" (eq/identifier-key "http://id.loc.gov/authorities/subjects/sh85123456"))))
  (testing "an ISNI whose check character is wrong is no id, and is reported"
    (is (nil? (eq/identifier-key "1234567890123456")))
    (is (nil? (eq/identifier-key "https://isni.org/isni/9999999999999999")))
    (is (= [{:kind :invalid-identifier :by :x :value "1234567890123456"}
            {:kind :invalid-identifier :by :x :value "https://isni.org/isni/9999999999999999"}]
           (:warnings (eq/equivalences {:x {:authority? true :iri "a:1" :isni ["1234567890123456"]
                                            :same-as ["https://isni.org/isni/9999999999999999"]}}))))))

;; ---------------------------------------------------------------------------
;; The constraint, lifted only by a stated equivalence
;; ---------------------------------------------------------------------------

(deftest two-authorities-stating-one-isni-are-one-entity
  (let [input (two-mentions {:iri bnf-iri :isni "0000 0001 2101 2885"} {:iri gnd-iri :same-as [isni-url]})
        res   (ac/cluster input)]
    (testing "the two records and both mentions are one cluster; nothing is refused"
      (is (= #{#{:m1 :m2 :x :y}} (members-of res)))
      (is (empty? (:refused res))))
    (testing "the cluster says which equivalence lifted the constraint, and who stated it"
      (is (= [{:a :x :b :y :basis :equivalence
               :evidence [{:by :x :states (str "isni:" isni)} {:by :y :states (str "isni:" isni)}]}]
             (:equivalence (first (:clusters res))))))
    (testing "the equivalence is a joining link, after a curator's and before any proposal"
      (is (= [:equivalence :propose :propose] (map :basis (:joined res)))))
    (testing "a mention accepted for both records is no conflict"
      (let [res (ac/cluster {:items (:items input)
                             :links [{:a :m1 :b :x :basis :accepted} {:a :m1 :b :y :basis :accepted}]})]
        (is (empty? (:conflicts res)))
        (is (= #{#{:m1 :x :y} #{:m2}} (members-of res)))))
    (testing "no violation is left in any cluster"
      (is (empty? (ac/violations input res))))))

(deftest a-shared-viaf-id-or-a-same-as-is-an-equivalence
  (testing "VIAF"
    (let [res (ac/cluster (two-mentions {:iri gnd-iri :same-as ["http://viaf.org/viaf/44300868"]}
                                        {:iri loc-iri :same-as ["https://viaf.org/viaf/44300868"]}))]
      (is (= #{#{:m1 :m2 :x :y}} (members-of res)))))
  (testing "a sameAs one record gives to the other, in another spelling of its IRI"
    (let [res (ac/cluster (two-mentions {:iri bnf-iri} {:iri gnd-iri :same-as ["https://data.bnf.fr/ark:/12148/cb119010630"]}))]
      (is (= #{#{:m1 :m2 :x :y}} (members-of res)))
      (is (= [[{:by :y :states "ark:/12148/cb119010630"}]] (map :evidence (:equivalence (first (:clusters res))))))))
  (testing "two records both sameAs a third: the union of what they state"
    (let [res (ac/cluster (two-mentions {:iri bnf-iri :same-as ["http://www.wikidata.org/entity/Q38337"]}
                                        {:iri gnd-iri :same-as ["http://www.wikidata.org/entity/Q38337"]}))]
      (is (= #{#{:m1 :m2 :x :y}} (members-of res))))))

(deftest without-a-stated-equivalence-two-authorities-stay-distinct
  (testing "different ISNIs"
    (let [input (two-mentions {:iri bnf-iri :isni isni} {:iri gnd-iri :same-as ["https://isni.org/isni/0000000121400183"]})
          res   (ac/cluster (update input :links conj {:a :m1 :b :y :basis :propose :weight 7.0}))]
      (is (= #{#{:m1 :x} #{:m2 :y}} (members-of res)))
      (is (= [:distinct-authorities] (map (comp :reason :violation) (:refused res))))))
  (testing "no identifier at all, however alike the two records"
    (let [input (two-mentions {:iri bnf-iri} {:iri gnd-iri})
          res   (ac/cluster (update input :links conj {:a :m1 :b :y :basis :propose :weight 7.0}))]
      (is (= #{#{:m1 :x} #{:m2 :y}} (members-of res)))
      (is (= [:distinct-authorities] (map (comp :reason :violation) (:refused res))))
      (is (empty? (:equivalence-conflicts res)))))
  (testing "a close link is no equivalence: only what `:same-as` and `:isni` state is read"
    (let [res (ac/cluster (two-mentions {:iri bnf-iri :close-match [gnd-iri]} {:iri gnd-iri}))]
      (is (= #{#{:m1 :x} #{:m2 :y}} (members-of res))))))

(deftest a-chain-joining-two-records-with-different-isnis-is-refused-and-reported
  (let [input {:items {:m1 {} :m2 {} :m3 {}
                       :b1 {:authority? true :iri bnf-iri :isni isni}
                       :b2 {:authority? true :iri bnf2-iri :isni "0000000444583295"}
                       :g  {:authority? true :iri gnd-iri
                            :same-as ["https://data.bnf.fr/ark:/12148/cb119010630" "https://data.bnf.fr/ark:/12148/cb11901062n"]}}
               :links [{:a :m1 :b :b1 :basis :propose :weight 9.0}
                       {:a :m2 :b :b2 :basis :propose :weight 9.0}
                       {:a :m3 :b :g :basis :propose :weight 9.0}
                       {:a :m3 :b :b1 :basis :propose :weight 5.0}]}
        res   (ac/cluster input)]
    (testing "no equivalence of the chain lifts the constraint: every record stays its own entity"
      (is (= #{#{:m1 :b1} #{:m2 :b2} #{:m3 :g}} (members-of res)))
      (is (= [:distinct-authorities] (map (comp :reason :violation) (:refused res))))
      (is (not-any? #(= :equivalence (:basis %)) (:joined res))))
    (testing "the conflict is reported: the records, the ISNIs each states, the pair that cannot be one"
      (is (= [{:reason   :isni-clash
               :members  [:b1 :b2 :g]
               :isni     {:b1 [(str "isni:" isni)] :b2 ["isni:0000000444583295"]}
               :pairs    [[:b1 :b2]]
               :evidence [{:by :g :states "ark:/12148/cb119010630"} {:by :g :states "ark:/12148/cb11901062n"}]}]
             (:equivalence-conflicts res))))
    (testing "and named in the curator's summary"
      (is (str/includes? (ac/format-clustering res) "equivalence refused")))))

(deftest a-stated-equivalence-lifts-only-the-authorities-constraint
  (let [d   dates/parse-life-dates
        res (ac/cluster (two-mentions {:iri bnf-iri :isni isni :dates (d "1802-1870")}
                                      {:iri gnd-iri :same-as [isni-url] :dates (d "1824-1895")}))]
    (is (= #{#{:m1 :x} #{:m2 :y}} (members-of res)) "dates that cannot be one life still keep them apart")
    (is (= [[:equivalence :incompatible-dates]] (map (juxt (comp :basis :link) (comp :reason :violation)) (:refused res))))))

(deftest the-result-does-not-depend-on-the-order-of-the-items-or-links
  (let [input (two-mentions {:iri bnf-iri :isni isni} {:iri gnd-iri :same-as [isni-url]})
        res   (ac/cluster input)]
    (is (= (:cluster-of res) (:cluster-of (ac/cluster (update input :links reverse)))))
    (is (= res (ac/cluster (update input :items #(into {} (reverse (seq %)))))))))

;; ---------------------------------------------------------------------------
;; In the IR: the authorities' statements read from their entries
;; ---------------------------------------------------------------------------

(defn- named [rid nm]
  (model/record {:id rid :kind :doc
                 :assertions [(model/assertion {:subject rid :predicate :canon/agent :value nm})]}))

(defn- prop [rid nm iri w]
  {:name nm :authority-id iri :authority-label nm :certifiable? true :status :proposed
   :score 0.97 :match-weight w :decision :propose :mention {:name nm :record rid}
   :evidence {:name-score 1.0 :comparison {:name :exact :form :heading :dates :unknown}}})

(defn- aligned []
  (rec/align-agents [(named :doc/a "Dumas, Alexandre") (named :doc/b "Dumas, Alexander")]
                    [(prop :doc/a "Dumas, Alexandre" bnf-iri 9.0)
                     (prop :doc/b "Dumas, Alexander" gnd-iri 8.0)]))

(def ^:private bnf-entry {:id bnf-iri :label "Dumas, Alexandre" :isni isni})
(def ^:private gnd-entry {:id gnd-iri :label "Dumas, Alexandre" :same-as [isni-url]})

(defn- person-of [r]
  (some #(when (= :reconcile/cluster-agents (get-in % [:provenance :rule])) %) (:assertions r)))

(deftest the-ir-reads-equivalences-from-the-authority-entries
  (testing "without the entries, a BnF and a GND record are two persons"
    (let [{out :records} (ac/cluster-records (aligned))]
      (is (apply not= (map (comp :value/target :value person-of) out)))))
  (testing "with them, one person, whose memberships name the equivalence"
    (let [{out :records res :clustering} (ac/cluster-records (aligned) {:authorities [bnf-entry gnd-entry]})
          ms (map person-of out)]
      (is (= 1 (count (set (map (comp :value/target :value) ms)))))
      (is (= 1 (count (filter #(< 1 (count (:members %))) (:clusters res)))))
      (is (= [(str bnf-iri " and " gnd-iri ": both state isni:" isni)]
             (get-in (first ms) [:provenance :bindings '?equivalence])))
      (is (every? model/valid-record? out))
      (testing "a re-run changes nothing"
        (let [again (ac/cluster-records out {:authorities [bnf-entry gnd-entry]})]
          (is (= out (:records again)))
          (is (empty? (:transitions again))))))))

(deftest a-refused-equivalence-is-a-diagnostic-on-the-records-involved
  (let [records (rec/align-agents [(named :doc/a "Dumas, Alexandre") (named :doc/b "Dumas, Alexandre") (named :doc/c "Dumas, Alexander")]
                                  [(prop :doc/a "Dumas, Alexandre" bnf-iri 9.0)
                                   (assoc (prop :doc/b "Dumas, Alexandre" bnf2-iri 9.0) :mention {:name "Dumas, Alexandre" :record :doc/b})
                                   (prop :doc/c "Dumas, Alexander" gnd-iri 8.0)])
        entries [bnf-entry {:id bnf2-iri :label "Dumas, Alexandre" :isni "0000000444583295"}
                 {:id gnd-iri :label "Dumas, Alexandre" :same-as [bnf-iri bnf2-iri]}]
        {out :records} (ac/cluster-records records {:authorities entries})]
    (is (= 3 (count (set (keep (comp :value/target :value person-of) out)))) "three persons")
    (is (every? (fn [r] (some #(= :reconcile/equivalence-conflict (:code %)) (:diagnostics r))) out))
    (testing "the evidence withdrawn, the diagnostic goes"
      (is (not-any? #(= :reconcile/equivalence-conflict (:code %))
                    (mapcat :diagnostics (:records (ac/cluster-records out {:authorities (take 2 entries)}))))))))

;; ---------------------------------------------------------------------------
;; Where the statements come from
;; ---------------------------------------------------------------------------

(deftest a-mads-or-skos-record-states-its-equivalences
  (let [nt (str "<http://id.loc.gov/authorities/names/n79091479> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://www.w3.org/2004/02/skos/core#Concept> .\n"
                "<http://id.loc.gov/authorities/names/n79091479> <http://www.w3.org/2004/02/skos/core#prefLabel> \"Dumas, Alexandre, 1802-1870\" .\n"
                "<http://id.loc.gov/authorities/names/n79091479> <http://www.w3.org/2004/02/skos/core#exactMatch> <http://viaf.org/viaf/44300868> .\n"
                "<http://id.loc.gov/authorities/names/n79091479> <http://www.w3.org/2002/07/owl#sameAs> <info:lc/authorities/n79091479> .\n"
                "<http://id.loc.gov/authorities/names/n79091479> <http://www.loc.gov/mads/rdf/v1#hasExactExternalAuthority> <https://d-nb.info/gnd/118528408> .\n"
                "<http://id.loc.gov/authorities/names/n79091479> <http://www.w3.org/2004/02/skos/core#closeMatch> <http://example.org/alike> .\n")
        e  (first (:entries (authorities/read-pool :ntriples nt)))]
    (is (= ["http://viaf.org/viaf/44300868" "https://d-nb.info/gnd/118528408" "info:lc/authorities/n79091479"] (:same-as e))
        "exactMatch, sameAs and an exact external authority; a close match is none")))

(defn- fixture-items []
  (let [bnf (:entries (authorities/read-pool :intermarc (slurp "test/fixtures/er-gold/bnf-persons/authorities.xml")))
        gnd (with-open [r (io/reader "test/fixtures/er-gold/gnd-persons/gnd-extract.jsonl")]
              (vec (gnd/entries (line-seq r))))]
    {:bnf bnf :gnd gnd
     :items (into {} (for [[iri st] (eq/stated (concat bnf gnd))]
                       [iri (assoc st :authority? true :iri iri)]))}))

(deftest the-gnd-and-bnf-extracts-state-which-of-their-records-are-one-person
  (let [{:keys [bnf gnd items]} (fixture-items)
        {:keys [links conflicts]} (eq/equivalences items)
        bnf?    #(str/includes? (str %) "bnf.fr")
        pairs   (set (for [{:keys [a b]} links] (if (bnf? a) [a b] [b a])))
        ark     (into {} (map (juxt :record-number :id)) bnf)
        truth   (gt/truth gnd)
        bridged (fn [ks] (set (for [[rn v] truth k ks g (get-in v [:by k])] [(ark rn) g])))
        hetzel  "http://catalogue.bnf.fr/ark:/12148/cb12008284s"
        stahl   "http://catalogue.bnf.fr/ark:/12148/cb16492882d"
        crossed #{[hetzel "https://d-nb.info/gnd/111910582X"] [stahl "https://d-nb.info/gnd/118949047"]}]
    (testing "the records state exactly the eval's ARK and ISNI bridges, but for the two Hetzel/Stahl pairs refused"
      (is (= 14 (count (bridged [:ark :isni]))))
      (is (= (set/difference (bridged [:ark :isni]) crossed) pairs))
      (is (= 12 (count links)))
      (is (= 3 (count (set/intersection (bridged [:ark]) (bridged [:isni])))) "3 pairs are bridged by both"))
    (testing "Hetzel and Stahl: the ISNIs pair each BnF record with the other's GND record, and both files state which is the pseudonym of which; both components are refused"
      (is (= #{[:pseudonym-crossed #{hetzel "https://d-nb.info/gnd/111910582X"}]
               [:pseudonym-crossed #{stahl "https://d-nb.info/gnd/118949047"}]}
             (set (map (juxt :reason (comp set :members)) conflicts))))
      (is (every? #(seq (:relations %)) conflicts) "each names the relation statements")
      (is (some #{{:by "https://d-nb.info/gnd/118949047" :pseudonym "https://d-nb.info/gnd/111910582X" :of "https://d-nb.info/gnd/118949047"}}
                (mapcat :relations conflicts))))
    (testing "no two records of one file are made one"
      (is (every? (fn [{:keys [a b]}] (not= (bnf? a) (bnf? b))) links)))))

;; ---------------------------------------------------------------------------
;; Review (N6, second pass): direct sameAs, pseudonyms, constraints, joins, ties
;; ---------------------------------------------------------------------------

(deftest a-direct-same-as-is-kept-against-disjoint-isnis-and-reported
  (let [res (ac/cluster (two-mentions {:iri bnf-iri :isni isni}
                                      {:iri gnd-iri :same-as ["https://data.bnf.fr/ark:/12148/cb119010630" "https://isni.org/isni/0000000444583295"]}))]
    (is (= #{#{:m1 :m2 :x :y}} (members-of res)) "one record names the other: ISNI duplicates are common")
    (is (empty? (:equivalence-conflicts res)))
    (is (= [{:kind :isni-disagreement :members [:x :y]
             :isni {:x [(str "isni:" isni)] :y ["isni:0000000444583295"]}}]
           (filter #(= :isni-disagreement (:kind %)) (:equivalence-warnings res))))))

(deftest a-pseudonym-and-its-real-identity-are-never-one-entity
  (testing "one component holding a record and the record it states is its pseudonym: refused"
    (let [res (ac/cluster (two-mentions {:iri bnf-iri :isni isni :pseudonym [gnd-iri]} {:iri gnd-iri :same-as [isni-url]}))]
      (is (= #{#{:m1 :x} #{:m2 :y}} (members-of res)))
      (is (= [[:pseudonym-in-component [:x :y] [{:by :x :pseudonym :y :of :x}]]]
             (map (juxt :reason :members :relations) (:equivalence-conflicts res))))))
  (testing "two components, each the other's pseudonym by the records' statements: both refused"
    (let [r   {:authority? true}
          res (eq/equivalences {:h1 (assoc r :iri "http://catalogue.bnf.fr/ark:/12148/cb12008284s" :isni "0000000121326905"
                                           :pseudonym ["http://catalogue.bnf.fr/ark:/12148/cb16492882d"])
                                :s1 (assoc r :iri "https://d-nb.info/gnd/111910582X" :same-as ["https://isni.org/isni/0000000121326905"]
                                           :real-identity ["https://d-nb.info/gnd/118949047"])
                                :s2 (assoc r :iri "http://catalogue.bnf.fr/ark:/12148/cb16492882d" :isni "0000000449168859"
                                           :real-identity ["http://catalogue.bnf.fr/ark:/12148/cb12008284s"])
                                :h2 (assoc r :iri "https://d-nb.info/gnd/118949047" :same-as ["https://isni.org/isni/0000000449168859"]
                                           :pseudonym ["https://d-nb.info/gnd/111910582X"])})]
      (is (empty? (:links res)))
      (is (= #{[:h1 :s1] [:h2 :s2]} (set (map :members (:conflicts res)))))
      (is (every? #(= :pseudonym-crossed (:reason %)) (:conflicts res)))))
  (testing "the same four records, the ISNIs not crossed: each person is one entity, the pseudonym another"
    (let [r   {:authority? true}
          res (eq/equivalences {:h1 (assoc r :iri "http://catalogue.bnf.fr/ark:/12148/cb12008284s" :isni "0000000121326905"
                                           :pseudonym ["http://catalogue.bnf.fr/ark:/12148/cb16492882d"])
                                :h2 (assoc r :iri "https://d-nb.info/gnd/118949047" :same-as ["https://isni.org/isni/0000000121326905"]
                                           :pseudonym ["https://d-nb.info/gnd/111910582X"])
                                :s2 (assoc r :iri "http://catalogue.bnf.fr/ark:/12148/cb16492882d" :isni "0000000449168859"
                                           :real-identity ["http://catalogue.bnf.fr/ark:/12148/cb12008284s"])
                                :s1 (assoc r :iri "https://d-nb.info/gnd/111910582X" :same-as ["https://isni.org/isni/0000000449168859"]
                                           :real-identity ["https://d-nb.info/gnd/118949047"])})]
      (is (= #{[:h1 :h2] [:s1 :s2]} (set (map (juxt :a :b) (:links res)))))
      (is (empty? (:conflicts res))))))

(deftest a-given-cannot-link-or-a-rejected-alignment-still-keeps-equivalent-records-apart
  (let [input (two-mentions {:iri bnf-iri :isni isni} {:iri gnd-iri :same-as [isni-url]})]
    (testing "a given cannot-link between the two records"
      (let [res (ac/cluster (assoc input :cannot-link [{:a :x :b :y :reason :given}]))]
        (is (= #{#{:m1 :x} #{:m2 :y}} (members-of res)))
        (is (= [[:equivalence :given]] (map (juxt (comp :basis :link) (comp :reason :violation)) (:refused res))))))
    (testing "a mention accepted for one record and rejected for the other: the curator's decisions keep the records apart"
      (let [res (ac/cluster (-> input
                                (assoc-in [:links 0 :basis] :accepted)
                                (assoc :cannot-link [{:a :m1 :b :y :reason :rejected-alignment}])))]
        (is (= #{#{:m1 :x} #{:m2 :y}} (members-of res)))
        (is (= [[:equivalence :rejected-alignment]] (map (juxt (comp :basis :link) (comp :reason :violation)) (:refused res))))))
    (testing "a mention only proposed for one record and rejected for the other: the stated equivalence comes first, and the mention stays out"
      (let [res (ac/cluster (assoc input :cannot-link [{:a :m1 :b :y :reason :rejected-alignment}]))]
        (is (= #{#{:m1} #{:m2 :x :y}} (members-of res)))
        (is (= [[:propose :rejected-alignment]] (map (juxt (comp :basis :link) (comp :reason :violation)) (:refused res))))))))

(deftest the-constraint-is-lifted-only-when-equivalences-may-join
  (let [input (-> (two-mentions {:iri bnf-iri :isni isni} {:iri gnd-iri :same-as [isni-url]})
                  (update :links conj {:a :m1 :b :y :basis :propose :weight 7.0})
                  (assoc :join #{:accepted :propose}))
        res   (ac/cluster input)]
    (is (= #{#{:m1 :x} #{:m2 :y}} (members-of res)))
    (is (= [:distinct-authorities] (map (comp :reason :violation) (:refused res))))
    (is (empty? (ac/violations input res)))))

(deftest an-equivalence-refused-by-dates-is-no-tie-and-is-reported
  (let [d   dates/parse-life-dates
        r   {:authority? true :isni isni}
        res (ac/cluster {:items {:a (assoc r :iri "a:1") :b (assoc r :iri "b:1" :dates (d "1802-1870"))
                                 :c (assoc r :iri "c:1" :dates (d "1900-1950"))}
                         :links []})]
    (is (= #{#{:a :b} #{:c}} (members-of res)) "the equivalence links go in id order: a~b joins first, c stays apart")
    (is (= [false false] (map :tie? (:refused res))))
    (is (= [[:dates-refuse-equivalence [:a :c]] [:dates-refuse-equivalence [:b :c]]]
           (map (juxt :kind :members) (filter #(= :dates-refuse-equivalence (:kind %)) (:equivalence-warnings res)))))))

(deftest one-record-under-two-spellings-is-the-same-record-id
  (let [res (ac/cluster (two-mentions {:iri bnf-iri} {:iri "https://data.bnf.fr/ark:/12148/cb119010630#about"}))]
    (is (= #{#{:m1 :m2 :x :y}} (members-of res)))
    (is (= [[{:same-record-id "ark:/12148/cb119010630"}]] (map :evidence (mapcat :equivalence (:clusters res)))))))

(deftest other-schemes-and-one-file-are-reported-not-refused
  (testing "two VIAF clusters, joined by a Wikidata id both records state"
    (let [res (ac/cluster (two-mentions {:iri bnf-iri :same-as ["http://viaf.org/viaf/1" "http://www.wikidata.org/entity/Q1"]}
                                        {:iri gnd-iri :same-as ["http://viaf.org/viaf/2" "http://www.wikidata.org/entity/Q1"]}))]
      (is (= #{#{:m1 :m2 :x :y}} (members-of res)))
      (is (= [{:kind :scheme-disagreement :scheme "viaf" :members [:x :y] :ids {:x ["viaf:1"] :y ["viaf:2"]}}]
             (:equivalence-warnings res)))))
  (testing "two records of one file, made one by what they state: a duplicate, reported"
    (let [res (ac/cluster (two-mentions {:iri bnf-iri :same-as ["http://www.wikidata.org/entity/Q1"]}
                                        {:iri bnf2-iri :same-as ["http://www.wikidata.org/entity/Q1"]}))]
      (is (= #{#{:m1 :m2 :x :y}} (members-of res)))
      (is (= [{:kind :same-file :file "ark:/12148" :members [:x :y]}] (:equivalence-warnings res))))))

(deftest the-ir-matches-an-entry-by-its-id-whatever-its-spelling
  (let [records (rec/align-agents [(named :doc/a "Dumas, Alexandre") (named :doc/b "Dumas, Alexander")]
                                  [(prop :doc/a "Dumas, Alexandre" "https://data.bnf.fr/ark:/12148/cb119010630" 9.0)
                                   (prop :doc/b "Dumas, Alexander" gnd-iri 8.0)])
        {res :clustering} (ac/cluster-records records {:authorities [bnf-entry gnd-entry]})]
    (is (= 1 (count (filter #(< 1 (count (:members %))) (:clusters res)))))))

(deftest equivalence-warnings-and-two-person-nodes-are-diagnostics-in-the-ir
  (testing "a direct sameAs against disjoint ISNIs"
    (let [{out :records} (ac/cluster-records (aligned) {:authorities [bnf-entry (assoc gnd-entry :same-as [bnf-iri "https://isni.org/isni/0000000444583295"])]})]
      (is (every? (fn [r] (some #(and (= :reconcile/equivalence-warning (:code %)) (= :isni-disagreement (get-in % [:detail :kind])))
                                (:diagnostics r)))
                  out))))
  (testing "two accepted memberships to two person nodes, which a stated equivalence now puts in one cluster"
    (let [{o1 :records} (ac/cluster-records (aligned))
          acc (:records (curate/curate o1 (fn [a] (if (= :reconcile/cluster-agents (get-in a [:provenance :rule])) :accept :review)) {:by "curator"}))
          {o2 :records} (ac/cluster-records acc {:authorities [bnf-entry gnd-entry]})]
      (is (every? (fn [r] (some #(= :reconcile/person-nodes-conflict (:code %)) (:diagnostics r))) o2))
      (is (not-any? #(= :reconcile/person-nodes-conflict (:code %)) (mapcat :diagnostics (:records (ac/cluster-records o2))))
          "the evidence withdrawn, the diagnostic goes"))))

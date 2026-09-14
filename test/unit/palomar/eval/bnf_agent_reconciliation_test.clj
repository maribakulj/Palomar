(ns palomar.eval.bnf-agent-reconciliation-test
  "Eval (ADR 0018, certified tier) on real BnF data: 100 INTERMARC records returned
   by `bib.author all \"victor hugo\"` / `\"jules verne\"`, reconciled by ISNI.

   The headline is not a low agent count — it is *precision by construction*. The
   search string matches a dozen distinct people (the novelist Jules Verne vs his
   biographer Jean Jules-Verne; Victor Hugo's grandson and son; Latin-American
   authors named \"Víctor Hugo …\"); keying on the authority id keeps them apart,
   where a name match would conflate them. And a name-only \"Victor Hugo\" — the
   namesake / Paris metro-station case — never enters the certified set.
   See `test/fixtures/er-gold/bnf-agents/README.md`."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.model :as model]
            [palomar.plugins :as plug]
            [palomar.plugins.intermarc :as intermarc]
            [palomar.plugins.intermarc.wemi :as wemi]
            [palomar.plugins.mapping :as mapping]
            [palomar.reconcile :as reconcile]
            [palomar.runtime :as runtime]))

(def ^:private base "test/fixtures/documentary/intermarc/sru/intermarcXchange/")
(def ^:private verne-novelist "https://isni.org/isni/0000000121400562")
(def ^:private verne-biographer "https://isni.org/isni/0000000083439748")

(defn- records []
  (let [reg      (plug/register plug/empty-registry intermarc/plugin)
        compiled (mapping/compile-mappings (plug/all-mappings reg)
                                           (plug/effective-transforms reg))
        load     (fn [f] (->> (intermarc/ingest (slurp (str base f)) {})
                              (mapv #(wemi/with-identified-agent
                                       (:record (runtime/run-phase % compiled :normalize))))))]
    (into (load "bnf-sru-victor-hugo-50.xml") (load "bnf-sru-jules-verne-50.xml"))))

(def ^:private result (delay (reconcile/reconcile-agents (records))))

(defn- agent-by-iri [iri] (first (filter #(= iri (:iri %)) (:agents @result))))

(deftest reconciles-100-records-to-distinct-certified-agents
  (testing "43 of 100 records carry a main-entry ISNI -> 12 distinct reconciled agents"
    (is (= 100 (count (records))))
    (is (= 12 (:distinct @result)))
    (is (= 43 (:mentions @result)))
    (is (= (:mentions @result) (:records @result)))         ; one main-entry agent per record
    (testing "the novelist Jules Verne is gathered across his editions by one ISNI"
      (is (= 21 (:mentions (agent-by-iri verne-novelist))))
      (is (re-find #"Verne, Jules" (:label (agent-by-iri verne-novelist)))))))

(deftest same-name-distinct-people-are-kept-apart-by-id
  (testing "the novelist and his near-namesake biographer are TWO agents, not merged"
    (is (some? (agent-by-iri verne-novelist)))
    (is (some? (agent-by-iri verne-biographer)))                   ; \"Verne, Jean Jules\"
    (is (not= verne-novelist verne-biographer))
    (is (re-find #"Jean Jules" (:label (agent-by-iri verne-biographer)))))
  (testing "no false merge: every distinct ISNI is its own agent (precision by construction)"
    (is (= (:distinct @result) (count (distinct (map :iri (:agents @result)))))))
  (testing "the writer Victor Hugo (Q535) is absent here — honest coverage, not a merge"
    (is (nil? (agent-by-iri "https://isni.org/isni/0000000121200174")))))

(deftest reconciled-isni-cross-checks-the-authority-gold
  (testing "the novelist's reconciled ISNI matches Jules Verne in the authority file"
    (let [authority (json/read-str (slurp "test/fixtures/er-gold/bnf-agents/authority.json"))
          verne     (first (filter #(= "Jules Verne" (get % "label")) authority))]
      (is (= "0000000121400562" (get verne "isni")))
      (is (str/ends-with? verne-novelist (get verne "isni"))))))

(deftest a-name-only-victor-hugo-never-enters-the-certified-set
  (testing "the namesake / metro-station case: a bare name with no authority id is not reconciled"
    (let [name-only (model/record
                     {:id :rec/metro :kind :book
                      :assertions [(model/assertion {:subject :rec/metro :predicate :canon/agent
                                                     :value "Victor Hugo"})]})
          augmented (reconcile/reconcile-agents (conj (records) name-only))]
      (is (= (:distinct @result) (:distinct augmented)))         ; adds no agent
      (is (not-any? #(= "Victor Hugo" (:label %)) (:agents augmented))))
    (testing "the authority gold itself shows the collision: its \"Victor Hugo\" lookup hit a non-person"
      (let [authority (json/read-str (slurp "test/fixtures/er-gold/bnf-agents/authority.json"))
            vh        (first (filter #(= "Victor Hugo" (get % "searched_name")) authority))]
        (is (nil? (get vh "isni")))                              ; Q1459231 = Paris metro station
        (is (str/includes? (get vh "source_entitydata") "Q1459231"))))))

(defn- authority-pool []
  (->> (json/read-str (slurp "test/fixtures/er-gold/bnf-agents/authority.json"))
       (mapv (fn [e] {:id       (get e "isni")
                      :label    (get e "label")
                      :variants (remove str/blank? (map str/trim (str/split (or (get e "variants") "") #"\|")))}))))

(deftest fuzzy-tier-on-the-real-authority-pool
  (let [pool    (authority-pool)
        propose (fn [nm] (first (reconcile/propose-agent-links [nm] pool)))]
    (testing "a free name resolves to the certified author by token-set match — still :proposed"
      (let [p (propose "Gustave Flaubert")]
        (is (= "0000000122762442" (:authority-id p)))    ; the very ISNI the certified tier uses
        (is (= :proposed (:status p)))
        (is (true? (:certifiable? p)))))
    (testing "partial / variant match on real variants: 'Balzac' -> Honoré de Balzac"
      (let [p (propose "Balzac")]
        (is (= "Honoré de Balzac" (:authority-label p)))
        (is (true? (:certifiable? p)))))
    (testing "the metro lesson on real data: 'Victor Hugo' matches the id-less Q1459231 entry"
      (let [p (propose "Victor Hugo")]
        (is (= "Victor Hugo" (:authority-label p)))
        (is (= 1.0 (:score p)))                          ; perfect name match…
        (is (false? (:certifiable? p)))                  ; …but no ISNI (metro station) -> never :asserted
        (is (= :proposed (:status p)))))))

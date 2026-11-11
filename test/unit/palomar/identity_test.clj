(ns palomar.identity-test
  "Palomar's own identity relations between agents, and the export rule by
   destination profile (lot O2, ADR 0061)."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [palomar.agent-cluster :as ac]
            [palomar.agent-dates :as dates]
            [palomar.cli :as cli]
            [palomar.convert :as convert]
            [palomar.curate :as curate]
            [palomar.identity :as identity]
            [palomar.ir :as ir]
            [palomar.model :as model]
            [palomar.plugins.agents :as agents]
            [palomar.plugins.lrmoo.dataset :as dataset]
            [palomar.plugins.lrmoo.export :as export]
            [palomar.plugins.lrmoo.linked-art :as linked-art]
            [palomar.reconcile :as rec]))

(def ^:private isni "https://isni.org/isni/0000000122762442")
(def ^:private ark "http://catalogue.bnf.fr/ark:/12148/cb11901063m")
(def ^:private same-as "http://www.w3.org/2002/07/owl#sameAs")
(def ^:private skos "http://www.w3.org/2004/02/skos/core#")

(defn- named [rid nm]
  (model/record {:id rid :kind :doc
                 :assertions [(model/assertion {:subject rid :predicate :canon/agent :value nm})]}))

(defn- prop
  ([rid nm iri] (prop rid nm iri :propose nil))
  ([rid nm iri decision dt]
   {:name nm :authority-id iri :authority-label nm :certifiable? true :status :proposed
    :score 0.97 :match-weight 9.0 :decision decision
    :mention (cond-> {:name nm :record rid} dt (assoc :dates (dates/parse-life-dates dt)))
    :evidence (cond-> {:name-score 1.0}
                dt (assoc :dates {:verdict :unknown :mention (dates/parse-life-dates dt)}))}))

(defn- claims [r p] (filter #(= p (:predicate %)) (:assertions r)))

(defn- lines [s] (str/split-lines (str s)))

(defn- accept-all [records] (:records (curate/curate records curate/accept-all)))

(def ^:private curator {:by "curator" :at #inst "2026-11-11T10:00:00Z"})

(defn- curate-all
  "Every pending identity claim of `records` accepted by a curator, one
   claim at a time and signed (`curate/decide-one`)."
  [records]
  (mapv (fn [r] (reduce (fn [r a] (if (and (identity/relation? (:predicate a)) (model/pending? a))
                                    (:record (curate/decide-one r a :accept "a curator's decision" curator))
                                    r))
                        r (:assertions r)))
        records))

(defn- nt [to records opts] (:output (convert/export-records to records opts)))

(defn- same-as-lines [to records] (filter #(str/includes? % "sameAs") (lines (nt to records {}))))

;; ---------------------------------------------------------------------------
;; The relations
;; ---------------------------------------------------------------------------

(deftest the-three-identity-relations-are-palomar-s-own-and-kept-apart
  (testing "four relations, each with its own IRI in the palomar: vocabulary"
    (is (= {identity/authority-match     "urn:palomar:vocab#matchesAuthority"
            identity/authority-candidate "urn:palomar:vocab#resemblesAuthority"
            identity/same-entity         "urn:palomar:vocab#sameEntityAs"
            identity/cluster-membership  "urn:palomar:vocab#inCluster"}
           identity/relation-iris)))
  (testing "(a) a link to an authority, (b) two authority records, (c) a cluster: three kinds"
    (is (= {:reconciliation 2 :equivalence 1 :cluster 1} (frequencies (map :kind (vals identity/relations))))))
  (testing "an outright proposal is a matchesAuthority, a review candidate a resemblesAuthority"
    (let [[a b] (rec/align-agents [(named :doc/a "Dumas, Alexandre") (named :doc/b "Dumas, Alexandre")]
                                  [(prop :doc/a "Dumas, Alexandre" isni) (prop :doc/b "Dumas, Alexandre" isni :review nil)])]
      (is (= [:proposed] (map :status (claims a identity/authority-match))))
      (is (= [:proposed] (map :status (claims b identity/authority-candidate))))
      (testing "and the membership a cluster writes is an inCluster"
        (let [{[ca] :records} (ac/cluster-records [a b])]
          (is (= 1 (count (claims ca identity/cluster-membership))))))
      (testing "no SKOS predicate is left in the IR"
        (let [{out :records} (ac/cluster-records [a (:record (rec/confirm-identity b (first (claims b identity/authority-candidate)) "same"))])]
          (is (not-any? #(= "skos" (namespace (:predicate %))) (mapcat :assertions out)))))))
  (testing "(b) the clustering's equivalence links name sameEntityAs"
    (let [res (ac/cluster {:items {:x {:authority? true :iri "http://example.org/x" :isni ["0000000122762442"]}
                                   :y {:authority? true :iri "http://example.org/y" :isni ["0000000122762442"]}}
                           :links [] :cannot-link []})]
      (is (= [identity/same-entity] (distinct (map :predicate (mapcat :equivalence (:clusters res)))))))))

;; ---------------------------------------------------------------------------
;; The export rule
;; ---------------------------------------------------------------------------

(deftest the-export-rule-is-data-in-one-place
  (is (= #{:rdf :linked-art} (set (keys identity/export-rule)))
      "MARC21, DC, MODS, IIIF write none")
  (is (= {:fact same-as :when [:in-force :curated :no-conflict :kind-agrees :entity-iri] :else :internal}
         (get-in identity/export-rule [:rdf identity/authority-match])))
  (is (= {:fact "equivalent" :when [:in-force :curated :no-conflict :kind-agrees]}
         (get-in identity/export-rule [:linked-art identity/authority-match]))))

(deftest owl-same-as-only-for-an-accepted-identity-to-the-entity-itself
  (let [records (rec/align-agents [(named :doc/a "Dumas, Alexandre") (named :doc/b "Dumas, Alexandre")]
                                  [(prop :doc/a "Dumas, Alexandre" isni) (prop :doc/b "Dumas, Alexandre" ark)])
        [a b]   (curate-all records)]
    (testing "a proposal is no fact: a plain export writes nothing of it"
      (is (not-any? #(or (str/includes? % "matchesAuthority") (str/includes? % "sameAs") (str/includes? % skos))
                    (lines (nt :ntriples records {})))))
    (testing "with proposals, a proposal is the internal relation, never SKOS or owl:sameAs"
      (let [out (nt :ntriples records {:with-proposals? true})]
        (is (some #(str/includes? % "urn:palomar:vocab#matchesAuthority") (lines out)))
        (is (not (str/includes? out "sameAs")))
        (is (not (str/includes? out skos)))))
    (testing "accepted, to an IRI naming the person (an ISNI): owl:sameAs"
      (is (some #(and (str/includes? % same-as) (str/includes? % isni)) (lines (nt :ntriples [a] {})))))
    (testing "accepted, to an IRI naming a record (a catalogue ARK): the internal relation, no owl:sameAs"
      (let [out (nt :ntriples [b] {})]
        (is (not (str/includes? out "sameAs")))
        (is (some #(and (str/includes? % "matchesAuthority") (str/includes? % ark)) (lines out)))))
    (testing "every RDF profile follows the one rule"
      (doseq [to [:ntriples :crm :crm-only :turtle :jsonld :trig]]
        (is (str/includes? (nt to [a] {}) (if (#{:turtle :trig :jsonld} to) "owl:sameAs" same-as)) (str to))))
    (testing "in a dataset, the fact is owl:sameAs in the facts graph; a rejected claim the internal relation in the history"
      (is (some #(and (str/includes? % same-as) (str/includes? % "graph:facts")) (lines (dataset/->nquads a))))
      (let [rej (first (:records (curate/curate [(first records)] curate/reject-all)))]
        (is (some #(and (str/includes? % "matchesAuthority") (str/includes? % "graph:history")) (lines (dataset/->nquads rej))))
        (is (not (str/includes? (dataset/->nquads rej) "sameAs")))))
    (testing "a review candidate is never owl:sameAs, even accepted"
      (let [[r] (curate-all (rec/align-agents [(named :doc/r "Dumas, Alexandre")] [(prop :doc/r "Dumas, Alexandre" isni :review nil)]))
            out (nt :ntriples [r] {})]
        (is (not (str/includes? out "sameAs")))
        (is (str/includes? out "urn:palomar:vocab#resemblesAuthority"))))))

(deftest an-accepted-identity-in-conflict-is-no-owl-same-as
  (let [records (rec/align-agents [(named :doc/a "Dumas, Alexandre") (named :doc/b "Dumas, Alexandre")]
                                  [(prop :doc/a "Dumas, Alexandre" isni :propose "1802-1870")
                                   (prop :doc/b "Dumas, Alexandre" isni :propose "1824-1895")])
        {out :records} (ac/cluster-records (curate-all records))]
    (is (every? #(seq (identity/conflicted-claims %)) out) "the clustering reports the conflict on both records")
    (testing "the two claims stay facts, as the internal relation: owl:sameAs would close two persons into one"
      (doseq [r out]
        (let [s (nt :ntriples [r] {})]
          (is (not (str/includes? s "sameAs")))
          (is (str/includes? s "matchesAuthority")))))))

(deftest a-membership-is-the-internal-relation-in-every-rdf-profile
  (let [records (rec/align-agents [(named :doc/a "Dumas, Alexandre") (named :doc/b "Dumas, Alexandre")]
                                  [(prop :doc/a "Dumas, Alexandre" isni) (prop :doc/b "Dumas, Alexandre" isni)])
        {out :records} (ac/cluster-records records)
        acc     (accept-all out)
        person  (export/entity-iri (ac/person-id [:authority isni (ac/batch-fingerprint records)]))]
    (is (some #(and (str/includes? % "urn:palomar:vocab#inCluster") (str/includes? % person))
              (lines (nt :ntriples acc {}))))
    (is (not-any? #(and (str/includes? % "sameAs") (str/includes? % person)) (lines (nt :ntriples acc {}))))))

(def ^:private dc-fixture "test/fixtures/documentary/dublin-core/w3c_dc_example1.xml")

(defn- dc-record []
  (first (:records (convert/to-wemi :dc {:record-id :dc/x} (slurp dc-fixture)))))

(defn- persons [doc]
  (let [acc (atom [])]
    (walk/postwalk #(do (when (and (map? %) (= "Person" (get % "type"))) (swap! acc conj %)) %) doc)
    @acc))

(deftest linked-art-writes-an-accepted-identity-as-equivalent
  (let [r       (dc-record)
        nm      (:name (first (agents/occurrences r)))
        [p]     (rec/align-agents [r] [(prop (:id r) nm ark)])
        [acc]   (curate-all [p])
        la      (fn [x opts] (mapv json/read-str (lines (nt :linked-art [x] opts))))]
    (is (some? nm) "the fixture names an agent")
    (testing "accepted: the Person carries the authority as its equivalent (a record's IRI is enough for Linked Art)"
      (is (some #(= [{"id" ark "type" "Person" "_label" nm}] (get % "equivalent")) (mapcat persons (la acc {})))))
    (testing "a proposal, even exported with proposals, is no equivalent"
      (is (not-any? #(contains? % "equivalent") (mapcat persons (la p {:with-proposals? true})))))
    (testing "no SKOS key on a Person"
      (is (not-any? #(some #{"exact_match" "close_match"} (keys %)) (mapcat persons (la acc {})))))
    (testing "the equivalent written is no loss"
      (is (not-any? #(= identity/authority-match (get-in % [:detail :loss/source-field])) (linked-art/export-losses acc))))
    (testing "a bulk acceptance is no equivalent"
      (is (not-any? #(contains? % "equivalent") (mapcat persons (la (first (accept-all [p])) {})))))
    (testing "an authority whose stated type is not the actor's is no equivalent"
      (let [[q] (curate-all (rec/align-agents [r] [(-> (prop (:id r) nm ark) (assoc :entity-type "corporateBody")
                                                       (assoc-in [:mention :kind] :person))]))]
        (is (not-any? #(contains? % "equivalent") (mapcat persons (la q {}))))))))

(def ^:private homonym-dc
  "A DC record whose creator, subject and publisher share one name."
  (str "<?xml version=\"1.0\"?>\n<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">"
       "<dc:title>Lettres de Dumas</dc:title><dc:creator>Dumas, Alexandre</dc:creator>"
       "<dc:subject>Dumas, Alexandre</dc:subject><dc:publisher>Dumas, Alexandre</dc:publisher>"
       "<dc:contributor>Hugo, Victor</dc:contributor><dc:date>1870</dc:date></metadata>"))

(deftest linked-art-attaches-an-equivalent-only-to-an-actor-of-the-claim-s-type
  (let [r     (first (:records (convert/to-wemi :dc {:record-id :dc/x} homonym-dc)))
        [acc] (curate-all (rec/align-agents [r] [(prop :dc/x "Dumas, Alexandre" isni)]))
        docs  (mapv json/read-str (lines (nt :linked-art [acc] {})))
        actors (let [xs (atom [])]
                 (walk/postwalk #(do (when (and (map? %) (#{"Person" "Group"} (get % "type")) (= "Dumas, Alexandre" (get % "_label")))
                                       (swap! xs conj %))
                                     %)
                                docs)
                 @xs)]
    (is (= #{"Person" "Group"} (set (map #(get % "type") actors))) "one name, a Person (the creator) and a Group (the publisher)")
    (testing "the name maps to actors of two types: no equivalent on either"
      (is (not-any? #(contains? % "equivalent") actors)))
    (testing "and it is reported"
      (is (some #(and (= identity/authority-match (get-in % [:detail :loss/source-field]))
                      (str/includes? (str (:message %)) "actors of different types"))
                (linked-art/export-losses acc))))))

(deftest the-non-rdf-exports-are-unchanged-by-an-identity-claim
  (let [r     (dc-record)
        nm    (:name (first (agents/occurrences r)))
        [acc] (accept-all (rec/align-agents [r] [(prop (:id r) nm isni)]))]
    (doseq [to [:dc :marc21 :mods :iiif]]
      (is (= (nt to [r] {}) (nt to [acc] {})) (str to)))))

;; ---------------------------------------------------------------------------
;; Old records still load
;; ---------------------------------------------------------------------------

(defn- legacy
  "`r` as an older Palomar wrote it: every identity claim a SKOS mapping,
   the decisions about them under the old ids."
  [r]
  (let [old  {identity/authority-match :skos/exactMatch identity/authority-candidate :skos/closeMatch
              identity/cluster-membership :skos/exactMatch}
        back (fn [a] (if-let [p (old (:predicate a))] (assoc a :predicate p) a))
        ids  (into {} (for [a (:assertions r) :when (old (:predicate a))] [(model/assertion-id a) (model/assertion-id (back a))]))]
    (cond-> (-> r
                (update :assertions (partial mapv back))
                (update :annotations (partial mapv (fn [n] (cond-> (update n :about #(get ids % %))
                                                             (get-in n [:value :value/fields :replaced-by])
                                                             (update-in [:value :value/fields :replaced-by] #(get ids % %)))))))
      (:diagnostics r) (update :diagnostics (partial mapv (fn [d] (cond-> d
                                                                    (get-in d [:detail :claims])
                                                                    (update-in [:detail :claims] (partial mapv #(get ids % %))))))))))

(deftest an-old-ir-document-with-skos-identity-claims-still-loads
  (let [[a b]  (rec/align-agents [(named :doc/a "Dumas, Alexandre") (named :doc/b "Dumas, Alexandre")]
                                 [(prop :doc/a "Dumas, Alexandre" isni) (prop :doc/b "Dumas, Alexandre" isni :review nil)])
        a'     (first (accept-all [a]))
        b'     (:record (rec/confirm-identity b (first (claims b identity/authority-candidate)) "same person" {:by "curator"}))
        {now :records} (ac/cluster-records [a' b'])
        old    (mapv legacy now)
        back   (ir/read-records (ir/->edn old))]
    (is (some #(= :skos/exactMatch (:predicate %)) (mapcat :assertions old)) "the document is an old one")
    (testing "read back, the records are those the new code writes"
      (is (= now back)))
    (testing "the curation decisions follow their claims"
      (let [close (first (claims (second back) identity/authority-candidate))]
        (is (= [:accept :supersede] (map :verdict (curate/history (second back) close))))
        (is (= "same person" (:reason (last (curate/history (second back) close)))))))
    (testing "accepted links, clustering and export read them"
      (is (= 2 (count (rec/accepted-links back))))
      (is (= (:clustering (ac/cluster-records now)) (:clustering (ac/cluster-records back))))
      (is (not (str/includes? (nt :ntriples back {:with-proposals? true}) skos))))
    (testing "upgrade leaves a record with no SKOS identity claim alone, and is idempotent"
      (is (= now (mapv identity/upgrade now)))
      (is (= back (mapv identity/upgrade back)))))
  (testing "a document with a conflict diagnostic, refused claims flagged for review and rejected memberships"
    (let [rs   (rec/align-agents (mapv #(named % "Dumas, Alexandre") [:doc/a :doc/b :doc/c])
                                 [(prop :doc/a "Dumas, Alexandre" isni :propose "1802-1870")
                                  (prop :doc/b "Dumas, Alexandre" isni :propose nil)
                                  (prop :doc/c "Dumas, Alexandre" isni :propose "1824-1895")])
          {c1 :records} (ac/cluster-records rs)
          {c2 :records} (ac/cluster-records (curate-all c1))
          {now :records} (ac/cluster-records (:records (curate/curate c2 curate/reject-all)))
          old  (mapv legacy now)
          back (ir/read-records (ir/->edn old))]
      (is (seq (mapcat identity/conflicted-claims now)) "the records hold a conflict diagnostic naming claims")
      (is (some #(= :review (:verdict %)) (for [r c1 a (:assertions r) h (curate/history r a)] h)) "a refused claim was flagged for review")
      (is (not= (mapcat :diagnostics now) (mapcat :diagnostics old)) "the old document names the old claim ids")
      (is (= now back) "read back, the conflict names the new claims")
      (is (empty? (same-as-lines :ntriples back)) "the conflicting claims are no sameAs"))))

(deftest an-upgrade-collision-of-an-old-and-a-new-claim-is-one-claim
  (let [[r]  (rec/align-agents [(named :doc/a "Dumas, Alexandre")] [(prop :doc/a "Dumas, Alexandre" isni)])
        a    (first (claims r identity/authority-match))
        at   (fn [d] {:by "curator" :at d})
        decided (fn [verdict ctx] (:record (curate/decide-one r a verdict "decided" ctx)))
        mix  (fn [new old] (-> new
                               (update :assertions into (filter #(= :skos/exactMatch (:predicate %)) (:assertions (legacy old))))
                               (update :annotations into (:annotations (legacy old)))))
        rd   (fn [x] (first (ir/read-records (ir/->edn [x]))))
        fact (fn [x] (claims x identity/authority-match))]
    (testing "the two agree: one claim, both histories"
      (let [b (rd (mix (decided :accept (at #inst "2026-11-01")) (decided :accept (at #inst "2026-10-01"))))]
        (is (model/one-claim-per-fact? b))
        (is (= [:accepted] (map :status (fact b))))
        (is (= 2 (count (curate/history b (first (fact b))))))))
    (testing "they conflict: the latest decision by time wins, both histories kept"
      (let [b (rd (mix (decided :accept (at #inst "2026-11-01")) (decided :reject (at #inst "2026-10-01"))))
            c (rd (mix (decided :accept (at #inst "2026-10-01")) (decided :reject (at #inst "2026-11-01"))))]
        (is (every? model/one-claim-per-fact? [b c]))
        (is (= [:accepted] (map :status (fact b))))
        (is (= [:rejected] (map :status (fact c))))
        (is (= #{:accept :reject} (set (map :verdict (curate/history c (first (fact c)))))))))
    (testing "they conflict and a decision has no time: the claim goes to review, with a diagnostic"
      (let [b (rd (mix (decided :accept {:by "curator"}) (decided :reject (at #inst "2026-10-01"))))]
        (is (model/one-claim-per-fact? b))
        (is (= [:needs-review] (map :status (fact b))))
        (is (some #(= :palomar/identity-upgrade-collision (:code %)) (:diagnostics b)))))))

;; ---------------------------------------------------------------------------
;; owl:sameAs is a curator's decision, checked at export
;; ---------------------------------------------------------------------------

(deftest owl-same-as-only-for-a-curator-s-per-claim-decision
  (let [records (rec/align-agents [(named :doc/a "Dumas, Alexandre")] [(prop :doc/a "Dumas, Alexandre" isni)])
        a       (first (claims (first records) identity/authority-match))]
    (testing "a bulk acceptance, even signed, stays palomar:matchesAuthority"
      (let [acc (:records (curate/curate records curate/accept-all {:by "curator"}))]
        (is (empty? (same-as-lines :ntriples acc)))
        (is (str/includes? (nt :ntriples acc {}) "matchesAuthority"))
        (is (not-any? :curated (curate/history (first acc) a)) "a policy writes no :curated marker")))
    (testing "a policy returning [verdict reason] cannot forge it either"
      (let [acc (:records (curate/curate records (constantly [:accept "by policy"]) {:by "curator" :curated true}))]
        (is (empty? (same-as-lines :ntriples acc)))))
    (testing "one claim at a time but unsigned: no owl:sameAs"
      (is (empty? (same-as-lines :ntriples [(:record (curate/decide-one (first records) a :accept "ok"))]))))
    (testing "one claim at a time, signed: owl:sameAs"
      (is (seq (same-as-lines :ntriples [(:record (curate/decide-one (first records) a :accept "ok" curator))]))))
    (testing "a review candidate confirmed by a signed curator: owl:sameAs; unsigned: none"
      (let [[r] (rec/align-agents [(named :doc/r "Dumas, Alexandre")] [(prop :doc/r "Dumas, Alexandre" isni :review nil)])
            c   (first (claims r identity/authority-candidate))]
        (is (seq (same-as-lines :ntriples [(:record (rec/confirm-identity r c "same" curator))])))
        (is (empty? (same-as-lines :ntriples [(:record (rec/confirm-identity r c "same"))])))))
    (testing "the CLI's apply-repairs --policy accept --by is a bulk policy: no owl:sameAs"
      (let [f (java.io.File/createTempFile "palomar-o2" ".edn")]
        (try
          (spit f (ir/->edn records))
          (let [{:keys [exit out]} (cli/run ["apply-repairs" (str f) "--from" "edn" "--policy" "accept" "--by" "curator" "--to" "ntriples"])]
            (is (= 0 exit))
            (is (str/includes? out "matchesAuthority"))
            (is (not (str/includes? out "sameAs"))))
          (finally (.delete f)))))))

(deftest the-export-checks-identity-conflicts-itself
  (let [records (rec/align-agents [(named :doc/a "Dumas, Alexandre") (named :doc/b "Dumas, Alexandre")]
                                  [(prop :doc/a "Dumas, Alexandre" isni :propose "1802-1870")
                                   (prop :doc/b "Dumas, Alexandre" isni :propose "1824-1895")])]
    (testing "two curated claims to one authority whose dates cannot be one person's, never clustered: no owl:sameAs"
      (let [acc (curate-all records)
            res (convert/export-records :ntriples acc {})]
        (is (empty? (filter #(str/includes? % "sameAs") (lines (:output res)))))
        (is (= 2 (count (filter #(str/includes? % "matchesAuthority") (lines (:output res))))))
        (is (= #{:incompatible-dates} (set (map #(get-in % [:detail :reason]) (filter #(= :reconcile/export-identity-conflict (:code %)) (:loss res))))))
        (is (= 2 (count (filter #(= :reconcile/export-identity-conflict (:code %)) (:loss res)))) "one diagnostic per claim")
        (testing "the conversion report names them"
          (is (= 2 (count (get-in (convert/convert {:from :edn :to :ntriples :source (ir/->edn acc)}) [:loss :identity-conflicts])))))
        (testing "exported alone, each record has nothing to compare with"
          (is (seq (same-as-lines :ntriples [(first acc)]))))))
    (testing "clustered first, curated after, exported without a new clustering: no owl:sameAs"
      (let [{c :records} (ac/cluster-records records)]
        (is (empty? (same-as-lines :ntriples (curate-all c))))))
    (testing "compatible dates: both are owl:sameAs"
      (let [ok (curate-all (rec/align-agents [(named :doc/a "Dumas, Alexandre") (named :doc/b "Dumas, Alexandre")]
                                             [(prop :doc/a "Dumas, Alexandre" isni :propose "1802-1870")
                                              (prop :doc/b "Dumas, Alexandre" isni :propose "1802-1870")]))]
        (is (= 2 (count (same-as-lines :ntriples ok))))))
    (testing "one local agent accepted for two authorities: neither"
      (let [two (curate-all (rec/align-agents [(named :doc/a "Dumas, Alexandre")]
                                              [(prop :doc/a "Dumas, Alexandre" isni)
                                               (prop :doc/a "Dumas, Alexandre" "https://isni.org/isni/0000000121032683")]))
            res (convert/export-records :ntriples two {})]
        (is (empty? (filter #(str/includes? % "sameAs") (lines (:output res)))))
        (is (= #{:two-authorities} (set (map #(get-in % [:detail :reason]) (filter #(= :reconcile/export-identity-conflict (:code %)) (:loss res))))))))))

(deftest the-gates-of-owl-same-as
  (let [one (fn [iri & {:as more}]
              (curate-all (rec/align-agents [(named :doc/a "Dumas, Alexandre")]
                                            [(merge-with merge (prop :doc/a "Dumas, Alexandre" iri) more)])))
        sa? (fn [rs] (boolean (seq (same-as-lines :ntriples rs))))]
    (testing "BnF: only data.bnf.fr's #about names the person; the catalogue form is no entity IRI"
      (is (not (sa? (one "http://catalogue.bnf.fr/ark:/12148/cb11901063m#about"))))
      (is (sa? (one "http://data.bnf.fr/ark:/12148/cb11901063m#about" :entity-type "person"))))
    (testing "a scheme that also names works or places needs a stated type: data.bnf.fr, GND"
      (is (not (sa? (one "http://data.bnf.fr/ark:/12148/cb11901063m#about"))))
      (is (not (sa? (one "https://d-nb.info/gnd/118528068")))))
    (testing "GND: a person yes; an undifferentiated person (Tn) or another type no"
      (is (sa? (one "https://d-nb.info/gnd/118528068" :entity-type "person")))
      (is (not (sa? (one "https://d-nb.info/gnd/118528068" :entity-type "UndifferentiatedPerson"))))
      (is (not (sa? (one "https://d-nb.info/gnd/118528068" :entity-type "work")))))
    (testing "the kind must agree: a person agent to a corporate body, no; a group to a corporate body, yes"
      (is (not (sa? (one isni :entity-type "corporateBody" :mention {:kind :person}))))
      (is (sa? (one isni :entity-type "corporateBody")))
      (is (not (sa? (one isni :entity-type "person" :mention {:kind :group})))))
    (testing "the authority entity carries its stated type"
      (is (= "corporateBody" (some #(when (= :reconcile/authority (:kind %)) (:entity-type %))
                                   (:entities (first (one isni :entity-type "corporateBody")))))))))

;; ---------------------------------------------------------------------------
;; ADR 0057's transitivity guarantees, on the internal relation
;; ---------------------------------------------------------------------------

(deftest the-memberships-close-to-the-clusters-and-nothing-more
  (let [records (rec/align-agents (mapv #(named % "Dumas, Alexandre") [:doc/a :doc/b :doc/c])
                                  [(prop :doc/a "Dumas, Alexandre" isni :propose "1802-1870")
                                   (prop :doc/b "Dumas, Alexandre" isni :propose nil)
                                   (prop :doc/c "Dumas, Alexandre" isni :propose "1824-1895")])
        {out :records res :clustering} (ac/cluster-records records)
        ms      (for [r out a (claims r identity/cluster-membership) :when (not (model/out-of-force? a))]
                  [(:subject a) (get-in a [:value :value/target])])
        closure (vals (group-by second ms))
        clusters (set (for [{:keys [members]} (:clusters res)
                            :let [ls (set (filter #(some (fn [[l _]] (= l %)) ms) members))]
                            :when (seq ls)]
                        ls))]
    (is (= clusters (set (map #(set (map first %)) closure)))
        "grouping the agents by the person node they are in gives the clusters")
    (is (empty? (ac/violations (:input (ac/cluster-records records)) res))
        "and no cluster holds two cannot-linked agents: 1802 and 1824 stay apart")))

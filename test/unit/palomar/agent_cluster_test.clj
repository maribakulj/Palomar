(ns palomar.agent-cluster-test
  "Constrained, revisable clustering of agents into persons (lot M5, ADR 0057):
   the constraints, the chain case, ties, the policy (only accepted links and
   outright proposals join; a curator's acceptance is a hard must-link),
   revision through curation in the IR, conflicts recorded in the IR, batches
   kept apart, and the identity relation each output writes."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.agent-cluster :as ac]
            [palomar.agent-dates :as dates]
            [palomar.curate :as curate]
            [palomar.model :as model]
            [palomar.plugins.lrmoo.dataset :as dataset]
            [palomar.plugins.lrmoo.export :as export]
            [palomar.plugins.lrmoo.linked-art :as linked-art]
            [palomar.reconcile :as rec]))

(def ^:private d dates/parse-life-dates)

(defn- members-of [result]
  (set (map :members (:clusters result))))

;; ---------------------------------------------------------------------------
;; The clustering, on plain data
;; ---------------------------------------------------------------------------

(deftest a-chain-goes-with-its-stronger-side
  (let [input {:items {:a {} :b {} :c {}}
               :links [{:a :a :b :b :basis :propose :weight 10.0}
                       {:a :b :b :c :basis :propose :weight 6.0}]
               :cannot-link [{:a :a :b :c :reason :incompatible-dates}]}
        res   (ac/cluster input)]
    (testing "the naive closure merges A, B and C"
      (is (= 1 (count (set (vals (ac/transitive-closure input)))))))
    (testing "the constrained clustering keeps A and C apart; B goes with A, the stronger link"
      (is (= #{#{:a :b} #{:c}} (members-of res)))
      (is (= [{:link {:a :b :b :c :basis :propose :weight 6.0}
               :violation {:a :a :b :c :reason :incompatible-dates}
               :tie? false}]
             (:refused res)) "and the decision is reported, as no tie"))
    (testing "with the weights the other way round, B goes with C"
      (let [res (ac/cluster (assoc input :links [{:a :a :b :b :basis :propose :weight 6.0}
                                                 {:a :b :b :c :basis :propose :weight 10.0}]))]
        (is (= #{#{:a} #{:b :c}} (members-of res)))
        (is (= [false] (map :tie? (:refused res))))))
    (testing "an accepted link outweighs any proposal"
      (let [res (ac/cluster (assoc input :links [{:a :a :b :b :basis :propose :weight 10.0}
                                                 {:a :b :b :c :basis :accepted}]))]
        (is (= #{#{:a} #{:b :c}} (members-of res)))
        (is (empty? (:conflicts res)))))))

(deftest a-tie-is-won-by-the-lower-id-and-reported
  (let [input {:items {:a {} :b {} :c {}}
               :links [{:a :c :b :b :basis :propose :weight 8.0}
                       {:a :a :b :b :basis :propose :weight 8.0}]
               :cannot-link [{:a :a :b :c :reason :incompatible-dates}]}
        res   (ac/cluster input)]
    (is (= #{#{:a :b} #{:c}} (members-of res)) "equal weights: the link with the lower ids goes first")
    (is (= [true] (map :tie? (:refused res))) "the refused link says it lost a tie")
    (is (= [true] (map :tie? (filter #(= :a (:a %)) (:joined res)))) "the joined link says it won one")))

(deftest a-constraint-that-appears-only-after-two-merges
  (testing "A~B, C~D, then B~C: A and D meet only through two merged clusters; B~C is refused"
    (let [res (ac/cluster {:items {:a {} :b {} :c {} :d {}}
                           :links [{:a :a :b :b :basis :propose :weight 10.0}
                                   {:a :c :b :d :basis :propose :weight 9.0}
                                   {:a :b :b :c :basis :propose :weight 8.0}]
                           :cannot-link [{:a :a :b :d :reason :given}]})]
      (is (= #{#{:a :b} #{:c :d}} (members-of res)))
      (is (= [[{:a :b :b :c :basis :propose :weight 8.0} {:a :a :b :d :reason :given}]]
             (map (juxt :link :violation) (:refused res)))))))

(deftest only-accepted-links-and-outright-proposals-join
  (let [res (ac/cluster {:items {:m1 {} :m2 {} :x {:authority? true}}
                         :links [{:a :m1 :b :x :basis :propose :weight 9.0}
                                 {:a :m2 :b :x :basis :review :weight 12.0}]})]
    (is (= #{#{:m1 :x} #{:m2}} (members-of res)))
    (is (= [{:a :m2 :b :x :basis :review :weight 12.0}] (:candidates res))
        "a review link is a candidate merge for a curator, never applied, however strong")
    (is (= :x (get-in res [:cluster-of :m1])) "a cluster with an authority is keyed by it")))

(deftest two-authorities-are-never-one-person
  (testing "a mention accepted for X and proposed for Y stays with X"
    (let [res (ac/cluster {:items {:m {} :x {:authority? true} :y {:authority? true}}
                           :links [{:a :m :b :y :basis :propose :weight 30.0}
                                   {:a :m :b :x :basis :accepted}]})]
      (is (= #{#{:m :x} #{:y}} (members-of res)))
      (is (= [:distinct-authorities] (map (comp :reason :violation) (:refused res))))))
  (testing "two accepted links to two authorities: neither wins, both are held back and reported as one conflict"
    (let [res (ac/cluster {:items {:m {} :x {:authority? true} :y {:authority? true}}
                           :links [{:a :m :b :x :basis :accepted} {:a :m :b :y :basis :accepted}]})]
      (is (= #{#{:m} #{:x} #{:y}} (members-of res)))
      (is (= [{:links [{:a :m :b :x :basis :accepted} {:a :m :b :y :basis :accepted}]
               :violations [{:a :x :b :y :reason :distinct-authorities}]}]
             (:conflicts res))))))

(deftest dates-are-a-cannot-link
  (testing "père and fils both compatible with an undated authority: the stronger mention keeps it"
    (let [input {:items {:pere {:dates (d "1802-1870")} :fils {:dates (d "1824-1895")} :x {:authority? true}}
                 :links [{:a :fils :b :x :basis :propose :weight 7.0}
                         {:a :pere :b :x :basis :propose :weight 9.0}]}
          res   (ac/cluster input)]
      (is (= #{#{:pere :x} #{:fils}} (members-of res)))
      (is (= [{:a :fils :b :pere :reason :incompatible-dates}] (map :violation (:refused res))))
      (is (= 1 (count (set (vals (ac/transitive-closure input))))) "the naive closure makes them one")))
  (testing "missing dates are no constraint"
    (is (= #{#{:m1 :m2 :x}}
           (members-of (ac/cluster {:items {:m1 {:dates (d "1802-1870")} :m2 {} :x {:authority? true}}
                                    :links [{:a :m1 :b :x :basis :propose :weight 9.0}
                                            {:a :m2 :b :x :basis :propose :weight 7.0}]}))))))

(defn- random-input
  "A small random graph from `seed`: 7 items (2 of them authorities, some
   dated, some stating an ISNI), random links of every basis, random
   cannot-links."
  [seed]
  (let [rnd   (java.util.Random. seed)
        n     7
        ids   (mapv #(keyword (str "i" %)) (range n))
        dts   [nil (d "1802-1870") (d "1824-1895") nil (d "1700-1750")]
        isnis [nil "0000000121012885" "0000000121012885" "0000000444583295"]
        items (into {} (for [[i id] (map-indexed vector ids)
                             :let [dt (nth dts (.nextInt rnd (count dts)))
                                   isni (nth isnis (.nextInt rnd (count isnis)))]]
                         [id (cond-> {}
                               (< i 2)            (assoc :authority? true)
                               (and (< i 2) isni) (assoc :isni isni)
                               dt                 (assoc :dates dt))]))
        pick  #(ids (.nextInt rnd n))]
    {:items       items
     :links       (vec (for [_ (range 12)
                             :let [a (pick) b (pick)]
                             :when (not= a b)]
                         {:a a :b b :basis ([:accepted :propose :propose :review] (.nextInt rnd 4))
                          :weight (double (.nextInt rnd 20))}))
     :cannot-link (vec (for [_ (range 3) :let [a (pick) b (pick)] :when (not= a b)] {:a a :b b :reason :given}))}))

(defn- oracle-violations
  "An independent oracle: the pairs of `cluster-of` in one cluster that the
   input forbids — a given pair, two authorities, or two dates that
   `agent-dates/compatibility` calls incompatible — computed here, not by the
   clustering's own check."
  [{:keys [items cannot-link]} cluster-of]
  (let [given (set (map (juxt :a :b) cannot-link))]
    (for [[x kx] cluster-of [y ky] cluster-of
          :when (and (= kx ky) (neg? (compare (str x) (str y))))
          :let [ix (get items x) iy (get items y)]
          :when (or (given [x y]) (given [y x])
                    ;; two authorities, unless both state one ISNI (lot N6)
                    (and (:authority? ix) (:authority? iy)
                         (not (and (:isni ix) (= (:isni ix) (:isni iy)))))
                    (and (:dates ix) (:dates iy)
                         (= :incompatible (:verdict (dates/compatibility (:dates ix) (:dates iy))))))]
      [x y])))

(deftest two-cannot-linked-items-are-never-in-one-cluster
  (doseq [seed (range 300)
          :let [input (random-input seed)
                res   (ac/cluster input)]]
    (is (empty? (oracle-violations input (:cluster-of res))) (str "seed " seed))
    (is (empty? (ac/violations input res)) (str "seed " seed))
    (testing "every item is in exactly one cluster"
      (is (= (count (:cluster-of res)) (reduce + (map (comp count :members) (:clusters res))))))
    (testing "the result does not depend on the order the links come in"
      (is (= (:cluster-of res) (:cluster-of (ac/cluster (update input :links reverse)))) (str "seed " seed))))
  (testing "some graphs join the two authorities, by the ISNI they both state"
    (is (pos? (count (filter #(let [c (:cluster-of (ac/cluster (random-input %)))] (= (c :i0) (c :i1))) (range 300))))))
  (testing "while the naive closure, on the same graphs, does merge cannot-linked items"
    (is (pos? (count (filter #(let [input (random-input %)]
                                (seq (oracle-violations input (ac/transitive-closure input))))
                             (range 300)))))))

;; ---------------------------------------------------------------------------
;; In the IR
;; ---------------------------------------------------------------------------

(def ^:private dumas-iri "http://example.org/authority/dumas")

(defn- named [rid nm]
  (model/record {:id rid :kind :doc
                 :assertions [(model/assertion {:subject rid :predicate :canon/agent :value nm})]}))

(defn- prop
  "An outright (or `decision`) proposal of the mention named `nm` in record
   `rid`, dated `dt` (or not), for the Dumas authority (undated, or dated
   `auth-dt`), of weight `w`."
  ([rid nm dt w] (prop rid nm dt w :propose))
  ([rid nm dt w decision] (prop rid nm dt w decision nil))
  ([rid nm dt w decision auth-dt]
   (let [m (cond-> {:name nm :record rid} dt (assoc :dates (d dt)))]
     {:name nm :authority-id dumas-iri :authority-label "Dumas, Alexandre" :certifiable? true :status :proposed
      :score 0.97 :match-weight w :decision decision :mention m
      :evidence (cond-> {:name-score 1.0 :comparison {:name :exact :form :heading :dates :unknown}}
                  dt (assoc :dates (cond-> {:verdict :unknown :mention (d dt)} auth-dt (assoc :candidate (d auth-dt)))))})))

(defn- aligned []
  (rec/align-agents [(named :doc/a "Dumas, Alexandre") (named :doc/b "Dumas, Alexandre") (named :doc/c "Hugo, Victor")]
                    [(prop :doc/a "Dumas, Alexandre" "1802-1870" 9.0)
                     (prop :doc/b "Dumas, Alexandre" nil 7.0)]))

(defn- local-of [r] (some #(when (#{:palomar/matchesAuthority :palomar/resemblesAuthority} (:predicate %)) (:subject %)) (:assertions r)))

(defn- membership-of [r]
  (let [ms (ac/memberships r)] (first (vals ms))))

(defn- alignment-of [r]
  (first (filter #(and (#{:palomar/matchesAuthority :palomar/resemblesAuthority} (:predicate %))
                       (str/includes? (str (get-in % [:value :value/target])) "authority"))
                 (:assertions r))))

(defn- membership? [records]
  (let [persons (set (for [r records e (:entities r) :when (= :reconcile/person (:kind e))] (:id e)))]
    (fn [a] (contains? persons (get-in a [:value :value/target])))))

(defn- distinct-ids? [r] (apply distinct? (map model/assertion-id (:assertions r))))

(deftest a-cluster-is-proposed-identity-claims-and-local-ids-stay
  (let [records (aligned)
        {out :records res :clustering} (ac/cluster-records records)
        [a b c] out
        person  (ac/person-id [:authority dumas-iri (ac/batch-fingerprint records)])]
    (testing "both Dumas mentions and the authority are one cluster"
      (is (= #{#{(local-of a) (local-of b) (model/mint-entity-id :reconcile/authority dumas-iri)}}
             (set (filter #(< 1 (count %)) (members-of res))))))
    (testing "each local agent keeps its id and states its membership as a proposed skos:exactMatch to the person node"
      (doseq [r [a b]]
        (let [[m] (membership-of r)]
          (is (= (local-of r) (:subject m)))
          (is (= [:palomar/inCluster :proposed person] [(:predicate m) (:status m) (get-in m [:value :value/target])]))
          (is (= :reconcile/cluster-agents (get-in m [:provenance :rule])))
          (is (= {'?cluster dumas-iri '?members 3} (get-in m [:provenance :bindings])))
          (is (some #(= {:id person :kind :reconcile/person} %) (:entities r))))))
    (testing "the records stay valid and consistent"
      (is (every? model/valid-record? out))
      (is (every? model/record-consistent? out)))
    (testing "a record with no alignment is untouched"
      (is (= c (nth records 2))))
    (testing "the person node identifies no one: accepted-links reads only alignments to an authority"
      (let [acc (:records (curate/curate out curate/accept-all))]
        (is (= #{dumas-iri} (set (map :authority-id (rec/accepted-links acc)))))))
    (testing "a re-run changes nothing"
      (let [again (ac/cluster-records out)]
        (is (= out (:records again)))
        (is (empty? (:transitions again)))))))

(deftest a-review-proposal-is-a-candidate-merge-never-a-membership
  (let [records (rec/align-agents [(named :doc/a "Dumas, Alexandre") (named :doc/b "Dumas, Alexandre")]
                                  [(prop :doc/a "Dumas, Alexandre" nil 9.0)
                                   (prop :doc/b "Dumas, Alexandre" nil 12.0 :review)])
        {out :records res :clustering} (ac/cluster-records records)]
    (is (= 1 (count (membership-of (first out)))))
    (is (nil? (membership-of (second out))))
    (is (= [:review] (map :basis (:candidates res))))))

(deftest a-curator-s-acceptance-outranks-the-machine
  (let [{built :records} (ac/cluster-records (aligned))
        m?      (membership? built)
        ;; the curator accepts the cluster (the memberships), leaving the alignments pending
        curated (:records (curate/curate built (fn [a] (if (m? a) :accept :review)) {:by "curator"}))
        ;; a new record: a mention dated 1824, proposed outright at a higher weight than either
        [e]     (rec/align-agents [(named :doc/e "Dumas, Alexandre")] [(prop :doc/e "Dumas, Alexandre" "1824-1895" 12.0)])
        {out :records ts :transitions res :clustering} (ac/cluster-records (conj curated e) {:context {:by "re-cluster"}})
        [a' b' _ e'] out]
    (testing "the accepted memberships stay in force, untouched"
      (is (= (membership-of (first curated)) (membership-of a')))
      (is (= (membership-of (second curated)) (membership-of b'))))
    (testing "the accepted cluster held, with its authority: the newcomer, born in 1824, is kept out, though its link is the strongest"
      (is (some #(and (contains? % (local-of a')) (contains? % (local-of b'))
                      (contains? % (model/mint-entity-id :reconcile/authority dumas-iri))
                      (not (contains? % (local-of e'))))
                (members-of res)))
      (is (= [(local-of e')] (map (comp :a :link) (:refused res)))))
    (testing "the newcomer's alignment goes to review, with the reason; nothing accepted moves"
      (is (= :needs-review (:status (alignment-of e'))))
      (is (= [[:proposed :needs-review]] (map (juxt :from :to) ts)))
      (is (nil? (membership-of e')) "alone, it is no cluster"))))

(deftest every-claim-the-machine-writes-is-proposed-with-its-cluster-provenance
  (let [{built :records} (ac/cluster-records (aligned))
        lb   (local-of (second built))
        {r2 :records} (ac/cluster-records built {:dates {lb "1824-1895"}})]
    (doseq [r r2 [_ ms] (ac/memberships r) m ms
            :when (not= :rejected (:status m))]
      (is (= [:proposed :reconcile/cluster-agents] [(:status m) (get-in m [:provenance :rule])]))
      (is (= #{'?cluster '?members} (set (keys (get-in m [:provenance :bindings]))))))))

(deftest a-refused-link-is-recorded-in-the-ir
  (let [records (rec/align-agents [(named :doc/a "Dumas, Alexandre") (named :doc/b "Dumas, Alexandre")]
                                  [(prop :doc/a "Dumas, Alexandre" "1802-1870" 9.0)
                                   (prop :doc/b "Dumas, Alexandre" "1824-1895" 7.0)])
        {out :records} (ac/cluster-records records)
        [a b] out]
    (testing "the weaker alignment goes to review, with the reason, in its history"
      (is (= :proposed (:status (alignment-of a))))
      (is (= :needs-review (:status (alignment-of b))))
      (is (str/starts-with? (:reason (last (curate/history b (alignment-of b)))) "refused: incompatible-dates between")))
    (testing "a re-run records it once"
      (is (= out (:records (ac/cluster-records out)))))
    (testing "accepting everything does not make the conflict silent"
      (let [acc (:records (curate/curate out curate/accept-all {:by "curator"}))
            {out2 :records res :clustering} (ac/cluster-records acc)
            [a2 b2] out2]
        (testing "two accepted alignments that cannot be one person: neither wins, both stay in force, the conflict is reported"
          (is (= [:accepted :accepted] (map (comp :status alignment-of) out2)))
          (is (not-any? #(and (contains? % (local-of a2)) (contains? % (local-of b2))) (members-of res)))
          (is (= 1 (count (:conflicts res)))))
        (testing "and recorded in the IR, as a diagnostic on each record naming the pair and the reason"
          (doseq [r out2]
            (is (= [[:reconcile/identity-conflict :incompatible-dates]]
                   (map (juxt :code (comp :reason :detail)) (filter #(= :reconcile/identity-conflict (:code %)) (:diagnostics r))))))
          (is (= out2 (:records (ac/cluster-records out2))) "once"))
        (testing "apply-agent-links refuses to join them on the authority's node"
          (let [{:keys [applied refused]} (rec/apply-agent-links out2 (rec/accepted-links out2))]
            (is (empty? applied))
            (is (= [:conflict :conflict] (map :reason refused)))))))))

(deftest a-split-and-a-re-merge-reuse-the-claim
  (let [{r1 :records} (ac/cluster-records (aligned))
        lb   (local-of (second r1))
        [old] (membership-of (second r1))
        {r2 :records} (ac/cluster-records r1 {:dates {lb "1824-1895"}})
        {r3 :records} (ac/cluster-records r2)
        {r4 :records} (ac/cluster-records r3 {:dates {lb "1824-1895"}})]
    (testing "split: the pending membership is rejected through curation, with the reason; its replacement is proposed"
      (is (= #{:rejected :proposed} (set (map :status (membership-of (second r2))))))
      (is (str/starts-with? (:reason (last (curate/history (second r2) old))) "re-clustered: incompatible-dates between")))
    (testing "re-merge: the old claim is reopened, never copied"
      (let [b3 (second r3)]
        (is (distinct-ids? b3))
        (is (model/one-claim-per-fact? b3))
        (is (model/record-consistent? b3))
        (is (= :proposed (:status (first (filter #(= (model/assertion-id old) (model/assertion-id %)) (:assertions b3))))))
        (is (= [:reject :reopen] (map :verdict (curate/history b3 old))) "its history is its own")))
    (testing "split again: the same claims, no copy"
      (is (distinct-ids? (second r4)))
      (is (= [:reject :reopen :reject] (map :verdict (curate/history (second r4) old)))))))

(deftest an-accepted-cluster-contradicted-is-a-conflict-not-a-split
  (let [{built :records} (ac/cluster-records (aligned))
        m?       (membership? built)
        curated  (:records (curate/curate built (fn [a] (if (m? a) :accept :review)) {:by "curator"}))
        lb       (local-of (second curated))
        {out :records ts :transitions res :clustering} (ac/cluster-records curated {:dates {lb "1824-1895"}})]
    (is (every? #(= :needs-review (:to %)) ts)
        "no accepted claim is superseded or rejected by the machine: only the refused alignment goes to review")
    (is (= (map membership-of curated) (map membership-of out)))
    (is (= 1 (count (:conflicts res))))
    (is (every? (fn [r] (some #(= :reconcile/identity-conflict (:code %)) (:diagnostics r))) (take 2 out)))
    (testing "the evidence withdrawn, the conflict diagnostic goes"
      (is (not-any? #(= :reconcile/identity-conflict (:code %)) (mapcat :diagnostics (:records (ac/cluster-records out))))))))

(deftest a-dated-authority-is-a-constraint-in-the-ir
  (let [records (rec/align-agents [(named :doc/a "Dumas, Alexandre") (named :doc/b "Dumas, Alexandre")]
                                  [(prop :doc/a "Dumas, Alexandre" "1802-1870" 7.0 :propose "1802-1870")
                                   (prop :doc/b "Dumas, Alexandre" nil 9.0)])
        lb (local-of (second records))
        {res :clustering} (ac/cluster-records records {:dates {lb "1824-1895"}})
        x  (model/mint-entity-id :reconcile/authority dumas-iri)]
    (is (= {:dates (d "1802-1870") :authority? true :iri dumas-iri}
           (get-in (ac/clustering-input records) [:items x])) "the authority's dates are read from the alignment's provenance")
    (is (= [[lb x :incompatible-dates]] (map (comp (juxt :a :b :reason) :violation) (:refused res)))
        "the stronger link is refused for the authority's own dates, not for another mention's")))

(deftest two-batches-never-share-a-person-node
  (let [b1  (rec/align-agents [(named :doc/a "Dumas, Alexandre")] [(prop :doc/a "Dumas, Alexandre" "1802-1870" 9.0)])
        b2  (rec/align-agents [(named :doc/c "Dumas, Alexandre") (named :doc/d "Dumas, Alexandre")]
                              [(prop :doc/c "Dumas, Alexandre" "1824-1895" 9.0) (prop :doc/d "Dumas, Alexandre" nil 8.0)])
        t   (fn [rs] (set (for [r rs m (mapcat val (ac/memberships r))] (get-in m [:value :value/target]))))
        o1  (:records (ac/cluster-records b1))
        o2  (:records (ac/cluster-records b2))]
    (is (= 1 (count (t o1)) (count (t o2))))
    (is (empty? (set/intersection (t o1) (t o2)))
        "each batch mints its own person node for one authority: Palomar's memberships never close 1802 and 1824")
    (testing "clustered together after a curator accepted both, the two batches' clusters are a reported conflict"
      (let [acc (:records (curate/curate (concat o1 o2) (let [m? (membership? (concat o1 o2))] (fn [a] (if (m? a) :accept :review)))))]
        (is (= [:incompatible-dates] (distinct (map :reason (mapcat :violations (:conflicts (:clustering (ac/cluster-records acc))))))))))))

(deftest a-curator-confirms-a-review-candidate-by-superseding-it
  (testing "a name-only proposal scoring 1.0 is a close match: it is no decision to propose"
    (let [[r] (rec/align-agents [(named :doc/a "Dumas, Alexandre")]
                                [{:name "Dumas, Alexandre" :authority-id dumas-iri :certifiable? true :status :proposed :score 1.0}])]
      (is (= [:palomar/resemblesAuthority] (map :predicate (filter #(= "palomar" (namespace (:predicate %))) (:assertions r)))))))
  (let [records (rec/align-agents [(named :doc/a "Dumas, Alexandre") (named :doc/b "Dumas, Alexandre")]
                                  [(prop :doc/a "Dumas, Alexandre" nil 9.0)
                                   (prop :doc/b "Dumas, Alexandre" nil 3.0 :review)])
        b       (second records)
        close   (alignment-of b)
        b'      (:record (rec/confirm-identity b close "same person: the title page says so" {:by "curator"}))
        exact   (first (filter #(= :palomar/matchesAuthority (:predicate %)) (:assertions b')))]
    (is (= :palomar/resemblesAuthority (:predicate close)))
    (testing "the close match is superseded by an accepted exact match, with the reason, in its history"
      (is (= :superseded (:status (first (filter #(= :palomar/resemblesAuthority (:predicate %)) (:assertions b'))))))
      (is (= [:accepted (:value close)] [(:status exact) (:value exact)]))
      (is (= "same person: the title page says so" (:reason (last (curate/history b' close))))))
    (testing "clustering reads the accepted exact match: the confirmed agent joins"
      (let [{out :records} (ac/cluster-records [(first records) b'])]
        (is (some? (membership-of (second out))))))))

(deftest a-record-never-holds-two-claims-with-one-id
  (testing "the check"
    (let [a (model/assertion {:subject :r :predicate :p :value 1})
          r (model/record {:id :r :kind :doc :assertions [a (assoc a :status :rejected)]})]
      (is (not (model/one-claim-per-fact? r)))
      (is (= [(model/assertion-id a)] (model/duplicate-assertion-ids r)))))
  (testing "every record the clustering and curation write here holds one claim per fact"
    (let [{r1 :records} (ac/cluster-records (aligned))
          lb            (local-of (second r1))
          runs          (reductions (fn [rs dts] (:records (ac/cluster-records rs {:dates dts})))
                                    r1 [{lb "1824-1895"} {} {lb "1824-1895"} {}])
          acc           (:records (curate/curate (last runs) curate/accept-all))]
      (is (every? model/one-claim-per-fact? (apply concat acc (:records (ac/cluster-records acc)) runs))))))

(deftest the-identity-relation-is-what-adr-0057-decides
  (let [{out :records} (ac/cluster-records (aligned))
        a       (first out)
        person  (export/entity-iri (ac/person-id [:authority dumas-iri (ac/batch-fingerprint (aligned))]))
        nq      (str/split-lines (dataset/->nquads a))
        line    (fn [lines] (filter #(and (str/includes? % "urn:palomar:vocab#inCluster") (str/includes? % person)) lines))]
    (testing "an outright proposal is a proposed palomar:matchesAuthority to the authority; a membership a palomar:inCluster to the person node (ADR 0061)"
      (is (some #(str/includes? % "graph:proposals") (line nq)))
      (is (some #(and (str/includes? % "urn:palomar:vocab#matchesAuthority") (str/includes? % dumas-iri) (str/includes? % "graph:proposals")) nq)))
    (testing "accepted, both are facts"
      (let [acc (first (:records (curate/curate [a] curate/accept-all)))
            nq  (str/split-lines (dataset/->nquads acc))]
        (is (some #(str/includes? % "graph:facts") (line nq)))))
    (testing "a review proposal is a close match, never exact"
      (let [[r] (rec/align-agents [(named :doc/r "Dumas, Alexandre")] [(prop :doc/r "Dumas, Alexandre" nil 3.0 :review)])]
        (is (= [:palomar/resemblesAuthority] (map :predicate (filter #(= "palomar" (namespace (:predicate %))) (:assertions r)))))))
    (testing "no SKOS mapping, no owl:sameAs for a proposal, and no identity claim between two records' local agents"
      (let [nt (str (export/->ntriples a) (export/->ntriples (second out)))]
        (is (not (str/includes? nt "sameAs")))
        (is (not (str/includes? nt "skos/core#")))
        (is (every? #(or (str/includes? % dumas-iri) (str/includes? % person))
                    (filter #(str/includes? % "urn:palomar:vocab#") (str/split-lines nt))))))
    (testing "Linked Art states no proposal and no membership: an accepted identity alone is an equivalent (ADR 0061)"
      (let [doc (str (vec (linked-art/documents a)))]
        (is (not (str/includes? doc person)))
        (is (not (str/includes? doc "equivalent")))))))

(deftest a-growing-batch-keeps-its-person-nodes
  (let [{built :records} (ac/cluster-records (aligned))
        [e] (rec/align-agents [(named :doc/e "Dumas, Alexandre")] [(prop :doc/e "Dumas, Alexandre" nil 5.0)])
        {out :records ts :transitions} (ac/cluster-records (conj built e))]
    (is (empty? ts) "no pending membership is revised because the batch changed")
    (is (= (take 3 built) (take 3 out)))
    (is (= (get-in (first (membership-of (first out))) [:value :value/target])
           (get-in (first (membership-of (last out))) [:value :value/target]))
        "the newcomer joins the cluster's existing node")))

(ns palomar.agent-relations-test
  "Collective reconciliation (lot M8, ADR 0018 amendment M8): a mention's
   relational neighbourhood as evidence, read from a frozen set of validated
   links only, never from the link being judged or from a link whose own
   validation used it."
  (:require [clojure.test :refer [deftest is testing]]
            [palomar.curate :as curate]
            [palomar.agent-dates :as dates]
            [palomar.agent-match :as fs]
            [palomar.agent-relations :as ar]
            [palomar.model :as model]
            [palomar.reconcile :as rec]))

;; ---------------------------------------------------------------------------
;; Fixtures: Jules Férat (illustrator) and Jules Verne (author), born a year
;; apart, and Pierre-Jules Hetzel (publisher)
;; ---------------------------------------------------------------------------

(def ^:private ferat "http://x/ferat")
(def ^:private verne "http://x/verne")
(def ^:private hetzel "http://x/hetzel")
(def ^:private stahl "http://x/stahl")

(def ^:private pool
  [{:id ferat :label "Férat, Jules" :dates (dates/parse-life-dates "1829-1906")}
   {:id verne :label "Verne, Jules" :dates (dates/parse-life-dates "1828-1905")}
   {:id hetzel :label "Hetzel, Pierre-Jules" :dates (dates/parse-life-dates "1814-1886")
    :relations [{:id stahl :kind :pseudonym} {:id "http://x/firm" :kind :agent} {:id verne :kind :agent}]}])

(def ^:private fs-model
  "A hand-set model where a different surname with a shared forename
   (`:jaccard-low`) weighs 0, so with dates that agree Jules Verne is above
   the review threshold for \"Férat, Jules\": the M2 case, two candidates
   competing."
  (fs/model {:m {:name  {:exact 0.9 :initials 0.02 :same-tokens 0.01 :surname-typo 0.01 :forename-overlap 0.01
                         :surname-only 0.01 :forenames-differ 0.05 :jaccard-high 0.05 :jaccard-low 0.05 :none 0.01}
                 :form  {:heading 0.95 :variant 0.05}
                 :dates {:agree 0.8 :compatible 0.1 :unknown 0.1}}
             :u {:name  {:exact 0.01 :initials 0.01 :same-tokens 0.01 :surname-typo 0.01 :forename-overlap 0.01
                         :surname-only 0.01 :forenames-differ 0.05 :jaccard-high 0.05 :jaccard-low 0.05 :none 0.8}
                 :form  {:heading 0.9 :variant 0.1}
                 :dates {:agree 0.05 :compatible 0.15 :unknown 0.8}}
             :prior 0.1}))

(def ^:private rel-weights
  {:role {:same-role 1.0 :other-role -4.0 :unknown 0.0} :neighbours {:shared 1.0 :not-shared 0.0 :unknown 0.0}})

(def ^:private model (ar/with-relations fs-model {:weights rel-weights}))

(def ^:private m-ferat
  {:id :m :name "Férat, Jules" :dates "1829-1906" :record :r1 :role "0440"})

(defn- anchor [id mention record authority role & {:as more}]
  (merge {:id id :mention mention :record record :authority-id authority :role role :status :accepted} more))

(defn- fillers
  "`n` validated links of `n` other records, each to an authority of its
   own: the rest of a batch, so that three records of thirty-three are not
   a hub's share."
  [n]
  (for [i (range n)] (anchor (keyword (str "f" i)) (keyword (str "fm" i)) (keyword (str "rf" i)) (str "http://x/f" i) "0070")))

(def ^:private anchors
  "r1 is the judged mention's record: its author validated as Verne. r2 and
   r3 are other records; thirty more hold other persons."
  (vec (concat [(anchor :a-n :n :r1 verne "0070")
                (anchor :a-p :p :r2 ferat "0440")
                (anchor :a-q :q :r2 verne "0070")
                (anchor :a-s :s :r3 verne "0070")
                (anchor :a-t :t :r3 hetzel "0330")]
               (fillers 30))))

(defn- by-id [res] (into {} (for [c (concat (:proposals res) (:abstained res))] [(:authority-id c) c])))

(defn- named [rid nm]
  (model/record {:id rid :kind :doc :assertions [(model/assertion {:subject rid :predicate :canon/agent :value nm})]}))

(defn- claims [r pred] (filter #(= pred (:predicate %)) (:assertions r)))

;; ---------------------------------------------------------------------------
;; The neighbourhood
;; ---------------------------------------------------------------------------

(deftest a-mention-s-neighbourhood-is-its-record-s-validated-agents
  (let [frozen (ar/freeze-anchors anchors)
        usable (ar/usable-anchors frozen m-ferat)]
    (testing "the other agents of the mention's record, as validated: the author of r1, Verne"
      (is (= #{verne} (:authorities (ar/mention-neighbourhood usable m-ferat))))
      (is (= [:a-n] (map :id (:co-mentions (ar/mention-neighbourhood usable m-ferat))))))
    (testing "an unvalidated co-mention is no neighbour: nothing anchored in r9"
      (is (= #{} (:authorities (ar/mention-neighbourhood usable (assoc m-ferat :record :r9))))))
    (testing "LIDO: the actors of the same event of the record, when both name their event"
      (let [lido (ar/freeze-anchors [(anchor :x :mx :obj ferat "Illustrator" :event 1)
                                     (anchor :y :my :obj hetzel "Publisher" :event 2)
                                     (anchor :z :mz :obj verne "Author")])
            m    {:id :m :name "Férat, Jules" :record :obj :event 1}]
        (is (= #{ferat verne} (:authorities (ar/mention-neighbourhood (ar/usable-anchors lido m) m))))))
    (testing "the works a mention is linked to, where it has them"
      (is (= #{:w1} (:works (ar/mention-neighbourhood usable (assoc m-ferat :works [:w1]))))))))

(deftest a-candidate-s-neighbourhood-is-what-validated-links-and-its-own-record-state
  (let [frozen (ar/freeze-anchors anchors)
        usable (ar/usable-anchors frozen m-ferat)
        rpool  (ar/resolve-relations pool)
        hood   (fn [e] (ar/authority-neighbourhood usable e m-ferat))
        [fe ve he] rpool]
    (testing "its roles in every validated occurrence, the judged mention's record included"
      (is (= #{"0070"} (:roles (hood ve))))
      (is (= #{"0440"} (:roles (hood fe)))))
    (testing "the authorities it co-occurs with, through validated links, in other records only"
      (is (= #{ferat hetzel} (:co-occurring (hood ve))) "r2 and r3; r1, the judged mention's record, is not read")
      (is (= #{verne} (:co-occurring (hood fe)))))
    (testing "what its authority record states, when it names an entry of the pool or an anchored authority"
      (is (= #{verne} (:stated (hood he))) "the firm is neither: no evidence")
      (is (= #{} (:identities (hood he))) "Stahl is neither either")
      (is (= #{stahl} (:identities (ar/authority-neighbourhood (conj usable (anchor :st :st :r4 stahl "0070")) he m-ferat)))))))

(deftest stated-relations-resolve-to-pool-entries-by-record-number
  (let [p [{:id "ark:/a" :record-number "1" :label "Hetzel, Pierre-Jules"
            :relations [{:record-number "2" :kind :pseudonym} {:record-number "9" :kind :agent}]}
           {:id "ark:/b" :record-number "2" :label "Stahl, P.-J."}]
        [h] (ar/resolve-relations p)]
    (is (= [{:record-number "2" :kind :pseudonym :id "ark:/b" :in-pool? true} {:record-number "9" :kind :agent}]
           (:relations h)))
    (testing "through the matcher: Stahl, by its record number, is Hetzel's other identity"
      (is (= #{"ark:/b"} (:identities (ar/authority-neighbourhood [] h {:id :x :record :r}))))
      (is (= #{} (:stated (ar/authority-neighbourhood [] h {:id :x :record :r}))) "record 9 is not in the pool"))))

(deftest a-relation-to-nothing-the-batch-knows-is-no-evidence
  (let [a     "http://x/dupont-1" b "http://x/dupont-2" h "http://x/hetzel"
        pool3 [{:id a :label "Dupont, Jean" :dates (dates/parse-life-dates "1820-1890") :relations [{:id "http://x/firm" :kind :agent}]}
               {:id b :label "Dupont, Jean" :dates (dates/parse-life-dates "1820-1890")}]
        m     {:id :m :name "Dupont, Jean" :dates "1820-1890" :record :r1 :role "0070"}
        fr    (ar/freeze-anchors (cons (anchor 1 :x1 :r1 h "0330") (fillers 30)))]
    (is (= :unknown (get-in (ar/compare-relations fr m (first (ar/resolve-relations pool3))) [:levels :neighbours]))
        "never :not-shared: the firm is in neither the pool nor the anchors")
    (is (= (map (juxt :authority-id :decision :score) (:proposals (rec/agent-candidates [m] pool3 {:model fs-model})))
           (map (juxt :authority-id :decision :score) (:proposals (rec/agent-candidates [m] pool3 {:model model :anchors fr})))))))

(deftest role-compatibility-compares-the-mention-s-role-with-the-candidate-s-validated-roles
  (is (= :same-role (ar/role-level "0440" #{"0440" "0070"})))
  (is (= :other-role (ar/role-level "0440" #{"0070"})))
  (testing "no role on the mention, or no validated occurrence of the candidate: no evidence"
    (is (= :unknown (ar/role-level nil #{"0070"})))
    (is (= :unknown (ar/role-level " " #{"0070"})))
    (is (= :unknown (ar/role-level "0440" #{}))))
  (testing "the comparison of a pair"
    (let [frozen (ar/freeze-anchors anchors)
          [fe ve] pool]
      (is (= {:role :same-role :neighbours :shared} (:levels (ar/compare-relations frozen m-ferat fe))))
      (is (= {:role :other-role :neighbours :unknown} (:levels (ar/compare-relations frozen m-ferat ve)))
          "Verne's neighbours are Férat and Hetzel; r1's context, Verne himself aside, is empty")
      (is (= #{verne} (:shared (ar/compare-relations frozen m-ferat fe))))))
  (testing "a mention without an id cannot be told from its own link: no relational evidence"
    (is (= {:role :unknown :neighbours :unknown}
           (:levels (ar/compare-relations (ar/freeze-anchors anchors) (dissoc m-ferat :id) (first pool)))))))

;; ---------------------------------------------------------------------------
;; Neighbours: only informative ones
;; ---------------------------------------------------------------------------

(deftest a-hub-is-no-neighbour-and-the-namesake-is-not-promoted
  (let [a  "http://x/dupond-1" b "http://x/dupond-2" h "http://x/hetzel" l "http://x/lemerre"
        p2 [{:id a :label "Dupond, Jean" :dates (dates/parse-life-dates "1820-1890")}
            {:id b :label "Dupond, Jean" :dates (dates/parse-life-dates "1820-1890")}]
        ;; Hetzel publishes the judged record and eleven others; the true
        ;; Dupond once, in a Lemerre book; a namesake in a Hetzel one
        as (concat [(anchor 1 :x1 :r1 h "0330")
                    (anchor 2 :a1 :r5 a "0070") (anchor 3 :l1 :r5 l "0330")
                    (anchor 4 :b1 :r6 b "0070") (anchor 5 :h6 :r6 h "0330")]
                   (for [i (range 10)] (anchor (+ 10 i) (keyword (str "h" i)) (keyword (str "rh" i)) h "0330"))
                   (fillers 20))
        fr (ar/freeze-anchors as)
        m  {:id :m :name "Dupond, Jean" :dates "1820-1890" :record :r1 :role "0070"}
        rc (ar/compare-relations fr m (second p2))
        r  (by-id (rec/agent-candidates [m] p2 {:model model :anchors fr}))]
    (testing "Hetzel, in 12 of 33 records, is a hub: not a neighbour of the namesake"
      (is (= #{h} (ar/hubs (ar/usable-anchors fr m))))
      (is (= #{h} (:hubs rc)))
      (is (= :unknown (get-in rc [:levels :neighbours]))))
    (testing "so the namesake is not promoted over the true Dupond"
      (is (= (:score (r a)) (:score (r b))))
      (is (= #{:review} (set (map :decision (vals r))))))))

;; ---------------------------------------------------------------------------
;; The anchor set: validated links only, frozen before scoring
;; ---------------------------------------------------------------------------

(deftest only-validated-links-are-anchors
  (let [frozen (ar/freeze-anchors (concat anchors
                                          [(anchor :prop :u :r2 verne "0070" :status :proposed)
                                           (anchor :rej :v :r2 verne "0070" :status :rejected)
                                           (dissoc (anchor :bad :w :r2 verne "0070") :authority-id)]))]
    (is (= 35 (count (:anchors frozen))))
    (is (= {:prop :not-validated :rej :not-validated :bad :incomplete}
           (into {} (map (juxt (comp :id :link) :reason)) (:refused frozen))))
    (testing "a proposal is never an anchor, whatever its score: the matcher's own output is refused"
      (let [props (:proposals (rec/agent-candidates [m-ferat] pool {:model model :anchors frozen}))]
        (is (seq props))
        (is (empty? (:anchors (ar/freeze-anchors (for [p props] (assoc p :id (:authority-id p) :mention :m :record :r1)))))))))
  (testing "a curator's accepted link and a confirmed identity are; a source's asserted link too"
    (is (= 2 (count (:anchors (ar/freeze-anchors [(anchor :a :n :r1 verne "0070")
                                                  (anchor :b :o :r1 hetzel "0330" :status :asserted)])))))))

(deftest two-links-of-one-mention-or-one-id-are-refused-whatever-the-order
  (let [l1 (anchor 1 :x :r ferat "0440")
        l2 (anchor 1 :x :r verne "0440")
        l3 (anchor 2 :y :r ferat "0440")
        l4 (anchor 3 :y :r verne "0440" :status :asserted)]
    (testing "one id for two different links: both refused, in either order"
      (doseq [ls [[l1 l2] [l2 l1]]]
        (is (empty? (:anchors (ar/freeze-anchors ls))))
        (is (= #{:duplicate-id} (set (map :reason (:refused (ar/freeze-anchors ls))))))))
    (testing "one mention linked to two authorities: a conflict, both refused, in either order"
      (doseq [ls [[l3 l4] [l4 l3]]]
        (is (empty? (:anchors (ar/freeze-anchors ls))))
        (is (= #{:conflict} (set (map :reason (:refused (ar/freeze-anchors ls))))))))
    (testing "the same link given twice is one anchor"
      (is (= [l3] (map #(dissoc % :support) (:anchors (ar/freeze-anchors [l3 l3]))))))))

(deftest the-anchor-set-is-frozen-before-scoring
  (let [frozen (ar/freeze-anchors anchors)
        m-n2   {:id :n2 :name "Férat, Jules" :dates "1829-1906" :record :r4 :role "0440"}]
    (testing "the matcher takes a frozen set only: a list of links, or a set changed after freezing, is refused"
      (is (thrown? clojure.lang.ExceptionInfo (rec/agent-candidates [m-ferat] pool {:model model :anchors anchors})))
      (is (thrown? clojure.lang.ExceptionInfo
                   (rec/agent-candidates [m-ferat] pool {:model model :anchors (update frozen :anchors conj (anchor :x :x :r1 ferat "0440"))}))))
    (testing "a pass's outputs never feed back: two mentions scored together are scored as each alone"
      (let [together (rec/agent-candidates [m-ferat m-n2] pool {:model model :anchors frozen})
            alone    (map #(rec/agent-candidates [%] pool {:model model :anchors frozen}) [m-ferat m-n2])]
        (is (= (set (:proposals together)) (set (mapcat :proposals alone))))
        (is (= (set (:abstained together)) (set (mapcat :abstained alone))))))))

;; ---------------------------------------------------------------------------
;; No circular confirmation
;; ---------------------------------------------------------------------------

(deftest relational-evidence-never-uses-the-link-being-judged-or-what-depends-on-it
  (testing "the judged mention's own validated link is not its evidence"
    (let [frozen (ar/freeze-anchors (conj anchors (anchor :a-m :m :r1 ferat "0440")))
          rc     (ar/compare-relations frozen m-ferat (first pool))]
      (is (not (contains? (set (:support rc)) :m)))
      (is (= (ar/compare-relations (ar/freeze-anchors anchors) m-ferat (first pool)) rc))))
  (testing "a loop m -> Férat supports n -> Férat which supports m -> Férat: neither link reaches the other's evidence"
    (let [loop   [(anchor :a-m :m :r1 ferat "0440" :support #{:n})
                  (anchor :a-n :n :r2 ferat "0440" :support #{:m})
                  (anchor :a-o :o :r5 ferat "0440" :support #{:n}) ; validated using n, so using m through it
                  (anchor :a-p :p :r3 verne "0070")]
          frozen (ar/freeze-anchors loop)
          n      {:id :n :name "Férat, Jules" :dates "1829-1906" :record :r2 :role "0440"}]
      (is (= #{:m :n :o} (ar/excluded-mentions frozen :m)))
      (is (= #{:m :n :o} (ar/excluded-mentions frozen :n)))
      (is (= [:a-p] (map :id (ar/usable-anchors frozen m-ferat))))
      (doseq [x [m-ferat n]
              e pool]
        (is (empty? (filter #{:m :n :o} (:support (ar/compare-relations frozen x e))))))
      (testing "so Férat's own candidacy has no relational evidence; only Verne's validated role as an author speaks"
        (let [r (by-id (rec/agent-candidates [m-ferat] pool {:model model :anchors frozen}))]
          (is (nil? (get-in r [ferat :evidence :relations])))
          (is (= :other-role (get-in r [verne :evidence :relations :levels :role])))
          (is (= [:p] (get-in r [verne :evidence :relations :support]))))))))

(deftest a-decision-s-support-is-every-mention-its-candidates-read
  (let [r (rec/agent-candidates [m-ferat] pool {:model model :anchors (ar/freeze-anchors anchors)})
        f (get (by-id r) ferat)]
    (is (= :propose (:decision f)))
    (is (= [:n :p :q] (get-in f [:evidence :relations :support])) "what Férat's own evidence read")
    (is (= [:n :p :q :s] (:support f)) "and what demoted Verne, the competitor: :s is Verne's validation in r3")
    (is (= (:support f) (:support (get (by-id r) verne))))))

(deftest a-loop-through-curation-is-excluded-end-to-end
  ;; pass 1: p (Verne, author, r2) is validated; m (Férat, illustrator, r1) is
  ;; judged; Verne is pushed down by p's role, so m's decision rests on p
  (let [p-anchor (anchor :a-p :p :r2 verne "0070")
        base     (fillers 30)
        r1       (rec/agent-candidates [m-ferat] pool {:model model :anchors (ar/freeze-anchors (cons p-anchor base))})
        prop     (first (filter #(= :propose (:decision %)) (:proposals r1)))
        aligned  (first (rec/align-agents [(named :r1 "Férat, Jules")] (:proposals r1)))
        exact    (first (claims aligned :palomar/matchesAuthority))
        curated  (:record (curate/decide-one aligned exact :accept "the curator agrees"))
        [link]   (rec/accepted-links [curated])]
    (testing "the proposal states its support, the alignment writes it, the accepted claim keeps it"
      (is (= [:p] (:support prop)))
      (is (= {'?mention :m '?role "0440" '?support [:p]}
             (select-keys (get-in exact [:provenance :bindings]) ['?mention '?role '?support])))
      (is (= {:mention :m :role "0440" :support #{:p} :record :r1 :authority-id ferat :status :accepted}
             (select-keys link [:mention :role :support :record :authority-id :status])))
      (is (some? (:id link))))
    (testing "pass 2: the curated link is an anchor through the real path, and p, re-judged, cannot read it"
      (let [fr2 (ar/freeze-anchors (concat [p-anchor link] base))
            p   {:id :p :name "Verne, Jules" :dates "1828-1905" :record :r2 :role "0070"}
            r2  (by-id (rec/agent-candidates [p] pool {:model model :anchors fr2}))]
        (is (= 32 (count (:anchors fr2))))
        (is (= #{:p :m} (ar/excluded-mentions fr2 :p)))
        (is (nil? (get-in r2 [ferat :evidence :relations])) "Férat's role as m's illustrator does not reach p")
        (testing "without the support carried, m's link would have pushed Férat down for p: the loop"
          (let [bare (ar/freeze-anchors (concat [p-anchor (assoc link :support #{})] base))]
            (is (= :other-role (get-in (by-id (rec/agent-candidates [p] pool {:model model :anchors bare}))
                                       [ferat :evidence :relations :levels :role])))))))))

(deftest a-confirmed-identity-keeps-its-support
  (let [soft  (ar/with-relations fs-model {:weights {:role {:same-role 0.0 :other-role -0.5 :unknown 0.0}
                                                     :neighbours {:shared 0.0 :not-shared 0.0 :unknown 0.0}}})
        fr    (ar/freeze-anchors (cons (anchor :a-p :p :r2 verne "0070") (fillers 30)))
        r     (rec/agent-candidates [(assoc m-ferat :record :r7 :id :m7)] pool {:model soft :anchors fr})
        _     (is (= #{:review} (set (map :decision (:proposals r)))) "still competing: both for review")
        al    (first (rec/align-agents [(named :r7 "Férat, Jules")] (:proposals r)))
        close (first (filter #(= ferat (:iri (some (fn [e] (when (= (:id e) (get-in % [:value :value/target])) e)) (:entities al))))
                             (claims al :palomar/resemblesAuthority)))
        conf  (:record (rec/confirm-identity al close "the curator knows the illustrator"))
        [l]   (rec/accepted-links [conf])]
    (is (= {:mention :m7 :role "0440" :support #{:p} :authority-id ferat}
           (select-keys l [:mention :role :support :authority-id])))
    (is (= [l] (:anchors (ar/freeze-anchors [l]))))))

;; ---------------------------------------------------------------------------
;; The relational evidence in the score
;; ---------------------------------------------------------------------------

(def ^:private dumas "http://x/dumas")

(deftest without-anchors-the-proposals-and-alignments-are-m4-s
  (let [ms    [m-ferat {:name "Verne, Jules" :dates "1828-1905" :record :r1 :role "0070" :id :n} "Hetzel, P.-J."]
        plain (map #(if (map? %) (dissoc % :id :role) %) ms)
        recs  [(named :r1 "Férat, Jules")]]
    (testing "no anchor set: the M4 result, whole proposals, whatever the mentions carry for M8"
      (is (= (rec/agent-candidates plain pool {:model fs-model}) (rec/agent-candidates ms pool {:model model})))
      (is (= (rec/align-agents recs (rec/propose-agent-links plain pool {:model fs-model}))
             (rec/align-agents recs (rec/propose-agent-links ms pool {:model model})))))
    (testing "one name twice in one record, as author and as translator: the alignments M4 gave"
      (let [p1   [{:id dumas :label "Dumas, Alexandre" :dates (dates/parse-life-dates "1802-1870")}]
            two  [{:id [:r1 "100" 0] :name "Dumas, Alexandre" :dates "1802-1870" :record :r1 :role "0070"}
                  {:id [:r1 "700" 0] :name "Dumas, Alexandre" :dates "1802-1870" :record :r1 :role "0680"}]
            m4   [{:name "Dumas, Alexandre" :dates "1802-1870" :record :r1} {:name "Dumas, Alexandre" :dates "1802-1870" :record :r1}]
            out  (fn [ms] (rec/align-agents [(named :r1 "Dumas, Alexandre")] (rec/propose-agent-links ms p1 {:model fs-model})))]
        (is (= (out m4) (out two)))
        (is (= 1 (count (claims (first (out two)) :palomar/matchesAuthority))))))
    (testing "an empty anchor set, or anchors that say nothing of these mentions: M4's decisions, scores and weights"
      (let [dec (fn [res] (map (juxt :name :authority-id :decision :score :match-weight :evidence) (concat (:proposals res) (:abstained res))))]
        (is (= (dec (rec/agent-candidates ms pool {:model fs-model}))
               (dec (rec/agent-candidates ms pool {:model model :anchors (ar/freeze-anchors [])}))
               (dec (rec/agent-candidates ms pool {:model model :anchors (ar/freeze-anchors [(anchor :z :z :r8 "http://x/other" "0070")])}))))))))

(deftest role-separates-the-illustrator-from-the-author
  (let [before (rec/agent-candidates [m-ferat] pool {:model fs-model})
        after  (rec/agent-candidates [m-ferat] pool {:model model :anchors (ar/freeze-anchors anchors)})]
    (testing "M4: Férat and Verne compete, both for review"
      (is (= {ferat :review verne :review} (update-vals (by-id before) :decision))))
    (testing "with the validated roles: Verne, an author elsewhere, weighs less for an illustrator; Férat is proposed outright"
      (is (= {ferat :propose verne :abstain} (update-vals (by-id after) :decision)))
      (let [v (get (by-id after) verne)]
        (is (= -4.0 (get-in v [:evidence :weights :role])))
        (is (= (+ (:match-weight (get (by-id before) verne)) -4.0) (:match-weight v)))
        (is (= #{"0070"} (set (get-in v [:evidence :relations :candidate-roles]))))))
    (testing "the evidence is stated in the alignment's provenance"
      (let [[r] (rec/align-agents [(named :r1 "Férat, Jules")] (:proposals after))
            b   (get-in (first (claims r :palomar/matchesAuthority)) [:provenance :bindings])]
        (is (= :same-role (get b '?role-level)))
        (is (= :shared (get b '?neighbours-level)))))))

(deftest relational-weights-are-measured-or-zero
  (let [lv     (fn [r n k] (repeat k {:levels {:role r :neighbours n}}))
        m-c    (ar/level-counts (concat (lv :same-role :shared 40) (lv :unknown :unknown 50) (lv :other-role :not-shared 2)))
        u-c    (ar/level-counts (concat (lv :same-role :unknown 10) (lv :other-role :not-shared 60) (lv :unknown :unknown 100)
                                        (lv :unknown :shared 1)))
        w      (ar/relational-weights {:m-counts m-c :u-counts u-c})]
    (testing "unknown weighs 0 by rule, and so does the absence of a shared neighbour"
      (is (= 0.0 (get-in w [:role :unknown])))
      (is (= 0.0 (get-in w [:neighbours :unknown])))
      (is (= 0.0 (get-in w [:neighbours :not-shared])) "measured at log2((5/92)/(60/171)) < 0, and still 0")
      (is (= 0.0 (get-in (ar/with-relations fs-model {:weights {:neighbours {:not-shared -3.0}}}) [:relations :weights :neighbours :not-shared]))))
    (testing "a level seen on both sides weighs log2(m/u); on one side, its bound"
      (is (= (fs/weight 40/92 10/171) (get-in w [:role :same-role])))
      (is (= (fs/weight 5/92 60/171) (get-in w [:role :other-role])) "m bounded by 5/92")
      (is (= (fs/weight 40/92 5/171) (get-in w [:neighbours :shared])) "u bounded by 5/171"))
    (testing "a disagreement never weighs for, an agreement never against"
      (let [w2 (ar/relational-weights {:m {:role {:same-role 0.1 :other-role 0.5 :unknown 0.4}
                                           :neighbours {:shared 0.1 :not-shared 0.5 :unknown 0.4}}
                                       :u {:role {:same-role 0.5 :other-role 0.1 :unknown 0.4}
                                           :neighbours {:shared 0.5 :not-shared 0.1 :unknown 0.4}}})]
        (is (= {:role {:same-role 0.0 :other-role 0.0 :unknown 0.0} :neighbours {:shared 0.0 :not-shared 0.0 :unknown 0.0}} w2))))))

(deftest a-relational-score-is-no-confidence-unless-calibrated-with-it
  (let [cal    (fs/calibration (concat (for [i (range 100)] {:p 0.99 :y 1 :pair [:t i] :mention [:t i]})
                                       (for [i (range 200)] {:p 0.01 :y 0 :pair [:f i] :mention [:f i]})
                                       (for [i (range 40)] {:p 0.75 :y (if (< i 30) 1 0) :pair [:r i] :mention [:r i]}))
                               {:pool (fs/pool-fingerprint pool) :model (fs/model-fingerprint fs-model)})
        frozen (ar/freeze-anchors anchors)
        m4cal  (ar/with-relations (fs/with-calibration fs-model cal) {:weights rel-weights})
        props  (fn [mdl] (:proposals (rec/agent-candidates [m-ferat] pool {:model mdl :anchors frozen})))]
    (testing "a model calibrated without the relational evidence: its posterior with it is a score, not a confidence"
      (is (fs/calibrated? m4cal pool))
      (is (not-any? :confidence (props m4cal)))
      (is (nil? (fs/proven-confidence (assoc (first (props m4cal)) :confidence (:score (first (props m4cal)))
                                             :calibration-proof (fs/confidence-proof m4cal pool))))))
    (testing "calibrated with these relational weights and this anchor set, it is one"
      (let [fp  (ar/relations-fingerprint m4cal frozen)
            mdl (fs/with-calibration m4cal (assoc cal :relations fp))
            p   (first (props mdl))]
        (is (= (:score p) (:confidence p)))
        (is (= (:score p) (fs/proven-confidence p)))
        (testing "and not with another anchor set"
          (is (not-any? :confidence (:proposals (rec/agent-candidates [m-ferat] pool
                                                                      {:model mdl :anchors (ar/freeze-anchors (butlast anchors))})))))))))

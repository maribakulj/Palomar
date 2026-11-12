(ns palomar.reconciliation-test
  "A reconciliation run from the public API (lot N2, ADR 0058 amendment):
   the historical engine, unchanged and the default; the probabilistic one,
   fed every mention of the batch (`palomar.mentions`) and scored by the
   shipped Fellegi-Sunter model; `explain` for one mention; the clusters of
   the batch's mentions."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.agent-dates :as dates]
            [palomar.agent-match :as fs]
            [palomar.api :as api]
            [palomar.eval.person-reconciliation :as pr]
            [palomar.mentions :as mentions]
            [palomar.plugins.agents :as agents]
            [palomar.reconcile :as reconcile]
            [palomar.reconciliation :as rc]))

(def bib "test/fixtures/documentary/intermarc/sru/intermarcXchange/bnf-sru-jules-verne-50.xml")
(def persons "test/fixtures/er-gold/bnf-persons/authorities.xml")
(def groups "test/fixtures/reconcile/verne-groups-mads.rdf")

(def ^:private records (delay (:records (api/pivot :intermarc {} (slurp bib)))))

(def lido "test/fixtures/documentary/lido/staedel-lido-wrap.xml")

(def ^:private folds
  "The M4 leave-one-family-out models of the M2 eval: `{family model}`, each
   fitted on the four other families, so a test of reach never scores a
   mention with parameters learnt from its own family."
  (delay (pr/fold-models (pr/fs-fit-data (pr/corpus)))))

(defn- held-out [family] (get @folds family))

(defn- close? [x y] (< (Math/abs (- (double x) (double y))) 1e-12))

(defn- tmp-model
  "A temporary model file holding the EDN `params` (or the text, if a string)."
  [params]
  (let [f (doto (java.io.File/createTempFile "palomar-model-" ".edn") (.deleteOnExit))]
    (spit f (if (string? params) params (pr-str params)))
    (str f)))

(def ^:private shipped-params
  (delay (edn/read-string (slurp (io/resource "palomar/models/agent-match.edn")))))

(defn- mention-of [field name]
  (first (filter #(and (= field (get-in % [:source :field])) (= name (:name %))) (mentions/batch @records))))

(defn- well-calibrated
  "A calibration report on `pool`, measured on `model`, that meets
   `fs/default-bar`, review band included (as in `palomar.reconcile-test`)."
  [model pool]
  (fs/calibration (concat (for [i (range 100)] {:p 0.99 :y 1 :pair [:t i] :mention [:t i]})
                          (for [i (range 200)] {:p 0.01 :y 0 :pair [:f i] :mention [:f i]})
                          (for [i (range 40)] {:p 0.75 :y (if (< i 30) 1 0) :pair [:r i] :mention [:r i]}))
                  {:pool (fs/pool-fingerprint pool) :model (fs/model-fingerprint model)}))

(defn- decision-of [result id]
  (first (filter #(= id (:mention %)) (:decisions result))))

(deftest the-historical-engine-is-the-default-and-unchanged
  (let [pool  (api/read-authorities persons)
        names (distinct (map :name (mapcat agents/occurrences @records)))
        old   (reconcile/propose-agent-links names (:entries pool) {:threshold 0.5})
        res   (api/reconcile @records pool)]
    (testing "no engine named is the historical engine"
      (is (= :historical (:engine res)))
      (is (= res (api/reconcile @records pool {:engine :historical}))))
    (testing "it matches the canonical floor's distinct names, as before lot N2"
      (is (seq old))
      (is (= old (:proposals res)))
      (is (= (reconcile/reconcile-agents @records) (:agents res))))
    (testing "its decisions are its proposals, name by name"
      (is (= (mapv #(select-keys % [:name :authority-id :authority-label :score]) old) (:decisions res))))))

(deftest the-probabilistic-engine-decides-every-mention
  (let [pool  (api/read-authorities persons)
        ms    (mentions/batch @records)
        {:keys [model]} (api/reconciliation-model)
        res   (api/reconcile @records pool {:engine :probabilistic})]
    (is (= :probabilistic (:engine res)))
    (testing "one decision per mention of the batch, in order, with its id"
      (is (= (mapv :id ms) (mapv :mention (:decisions res)))))
    (testing "each is agent-candidates' with the shipped model, the mention passed whole"
      (doseq [m ms
              :let [{:keys [proposals abstained rejected]} (reconcile/agent-candidates [m] (:entries pool) {:model model})
                    d (decision-of res (:id m))]]
        (is (= (mapv :authority-id proposals) (mapv :authority-id (:proposals d))) (pr-str (:id m)))
        (is (= (mapv :decision proposals) (mapv :decision (:proposals d))) (pr-str (:id m)))
        (is (= (mapv :score proposals) (mapv :score (:proposals d))) (pr-str (:id m)))
        (is (= [(count abstained) (count rejected)] ((juxt :abstained :rejected) d)) (pr-str (:id m)))
        (is (= (cond (seq proposals) (:decision (first proposals))
                     (seq abstained) :abstain
                     (seq rejected)  :rejected
                     :else           :none)
               (:decision d)))))
    (testing "the shipped model is not calibrated: no decision states a confidence"
      (is (not-any? #(contains? % :confidence) (mapcat :proposals (:decisions res)))))))

(deftest a-confidence-is-only-the-one-the-calibration-proves
  (let [pool  (api/read-authorities persons)
        {:keys [model]} (api/reconciliation-model)
        good  (well-calibrated model (:entries pool))
        cal   (fs/with-calibration model good)
        res   (api/reconcile @records pool {:engine :probabilistic :model cal})
        ps    (mapcat :proposals (:decisions res))]
    (testing "a model calibrated on this pool: an outright proposal states its posterior as its confidence"
      (is (seq (filter #(= :propose (:decision %)) ps)))
      (is (every? #(= (:score %) (:confidence %)) (filter #(= :propose (:decision %)) ps))))
    (testing "the same model on another pool states none"
      (let [other (api/reconcile @records (api/read-authorities groups) {:engine :probabilistic :model cal})]
        (is (not-any? #(contains? % :confidence) (mapcat :proposals (:decisions other))))))
    (testing "a report computed on another model proves nothing"
      (let [other (fs/with-calibration (held-out "others") good)]
        (is (not-any? #(contains? % :confidence)
                      (mapcat :proposals (:decisions (api/reconcile @records pool {:engine :probabilistic :model other})))))))
    (testing "a forged report, naming no model, proves nothing"
      (let [forged (fs/with-calibration model {:pool (fs/pool-fingerprint (:entries pool)) :ece 0.0 :max-bin-gap 0.0
                                               :distinct-pairs 1000000 :distinct-positives 1000000 :review-pairs 1000})]
        (is (not-any? #(contains? % :confidence)
                      (mapcat :proposals (:decisions (api/reconcile @records pool {:engine :probabilistic :model forged})))))))
    (testing "a calibration a parameter file carries is read with it, and proves its confidence when it measured that model"
      (let [m0   (:model (api/reconciliation-model (tmp-model @shipped-params)))
            file (tmp-model (assoc @shipped-params :calibration (well-calibrated m0 (:entries pool))))
            ps   (mapcat :proposals (:decisions (api/reconcile @records pool {:engine :probabilistic
                                                                              :model (api/reconciliation-model file)})))]
        (is (seq (filter :confidence ps)))
        (is (every? #(= (:score %) (:confidence %)) (filter :confidence ps)))))))

(deftest intermarc-700-and-710-headings-reach-a-candidate
  (let [ferat  (mention-of "700" "Férat, Jules")
        centre (mention-of "710" "Centre international Jules Verne")
        floor  (set (map :name (mapcat agents/occurrences @records)))]
    (testing "neither is on the canonical floor: the historical engine cannot reach them"
      (is (some? ferat))
      (is (some? centre))
      (is (not (floor "Férat, Jules")))
      (is (not (floor "Centre international Jules Verne"))))
    (testing "the probabilistic engine reads the mentions, so the 700 reaches its BnF authority (Férat's family held out)"
      (let [d (decision-of (api/reconcile @records (api/read-authorities persons)
                                          {:engine :probabilistic :model (held-out (pr/family "Férat"))})
                           (:id ferat))]
        (is (= "others" (pr/family "Férat")))
        (is (some #{"http://catalogue.bnf.fr/ark:/12148/cb11902539r"} (map :authority-id (:proposals d))))
        (is (#{:propose :review} (:decision d)))))
    (testing "and the 710 a corporate body, by its heading alone (its family held out)"
      (let [d (decision-of (api/reconcile @records (api/read-authorities groups)
                                          {:engine :probabilistic :model (held-out (pr/family "Centre international Jules Verne"))})
                           (:id centre))]
        (is (= ["http://isni.org/isni/0000000122005134"] (map :authority-id (:proposals d))))
        (is (= :propose (:decision d)))
        (is (= :group (:kind d)))))))

(deftest a-unimarc-surname-and-forename-heading-reaches-a-candidate
  (let [recs  (:records (api/pivot :unimarc {} (slurp "test/fixtures/documentary/unimarc/sru/bnf-sru-verne-unimarc.xml")))
        m     (first (filter #(= "Compère, Daniel" (:name %)) (mentions/batch recs)))
        floor (set (map :name (mapcat agents/occurrences recs)))
        d     (decision-of (api/reconcile recs (api/read-authorities persons)
                                          {:engine :probabilistic :model (held-out (pr/family "Compère"))})
                           (:id m))]
    (testing "the floor carries the surname alone; the mention its field's $a, $b"
      (is (floor "Compère"))
      (is (not (floor "Compère, Daniel")))
      (is (= "702" (get-in m [:source :field]))))
    (is (some #{"http://catalogue.bnf.fr/ark:/12148/cb11897474w"} (map :authority-id (:proposals d))))
    (is (#{:propose :review} (:decision d)))))

(deftest explain-says-why-a-mention-was-decided
  (let [pool (api/read-authorities persons)
        m    (mention-of "700" "Férat, Jules")
        {:keys [model]} (api/reconciliation-model)
        x    (api/reconcile-explain @records pool (:id m))
        top  (first (:candidates x))]
    (testing "the mention, the model, its prior and thresholds"
      (is (= (:id m) (get-in x [:mention :id])))
      (is (= "Férat, Jules" (get-in x [:mention :name])))
      (is (= (:thresholds model) (:thresholds x)))
      (is (= (:prior model) (:prior x))))
    (testing "the decision is the one reconcile made"
      (is (= (:decision (decision-of (api/reconcile @records pool {:engine :probabilistic}) (:id m))) (:decision x)))
      (is (string? (:why x))))
    (testing "each candidate: its comparison levels, the weight of each field, their total, the posterior, the decision and why"
      (is (= "http://catalogue.bnf.fr/ark:/12148/cb11902539r" (:authority-id top)))
      (is (= {:name :exact :form :heading :dates :agree} (:levels top)))
      (is (= #{:name :form :dates :tf} (set (keys (:weights top)))))
      (is (= (reduce + 0.0 (vals (:weights top))) (:total top)))
      (is (= (fs/posterior (:total top) (:prior x)) (:posterior top)))
      (is (close? (/ (Math/log (/ (:prior x) (- 1.0 (:prior x)))) (Math/log 2.0)) (:prior-weight x))
          "the prior's weight is log2(λ/(1-λ))")
      (is (close? (/ 1.0 (+ 1.0 (Math/pow 2.0 (- (+ (:total top) (:prior-weight x)))))) (:posterior top))
          "the posterior is 1/(1+2^-(total + prior weight))")
      (is (= :propose (:decision top)))
      (is (str/includes? (:why top) "0.95")))
    (testing "an abstained candidate is explained too, and so is a rejected one"
      (is (some #(= :abstain (:decision %)) (:candidates x)))
      (is (every? :why (:candidates x)))
      (let [rej (filter :reason (:candidates x))]
        (is (seq rej))
        (is (every? #(nil? (:posterior %)) rej) "a rejected candidate is never weighed")))
    (testing "the mention's roles are reported; without anchors they weigh nothing"
      (is (= ["0440"] (get-in x [:mention :roles])))
      (is (not-any? #(contains? (:weights %) :role) (:candidates x))))
    (testing "the shipped model is not calibrated"
      (is (false? (:calibrated? x))))
    (testing "an unknown mention id is refused"
      (is (thrown? clojure.lang.ExceptionInfo (api/reconcile-explain @records pool [:bnf/nothing "700" 9]))))))

(deftest the-clusters-of-the-batch-s-mentions
  (let [pool (api/read-authorities persons)
        ms   (mentions/batch @records)
        res  (api/reconcile-clusters @records pool)
        cs   (:clusters res)
        ferats (set (map :id (filter #(= "Férat, Jules" (:name %)) ms)))]
    (testing "every mention is in exactly one cluster"
      (is (= (sort-by pr-str (map :id ms)) (sort-by pr-str (mapcat :members cs)))))
    (testing "the mentions proposed outright for one authority are one cluster, keyed by it"
      (let [c (first (filter #(= "http://catalogue.bnf.fr/ark:/12148/cb11902539r" (:authority %)) cs))]
        (is (= ferats (set (:members c))))
        (is (every? #(= :propose (:basis %)) (:links c)))))
    (testing "with provenance: each member's record, field, occurrence and evidence"
      (doseq [c cs, m (:mentions c)]
        (is (= #{:format :field :occurrence :evidence} (set (keys (:source m)))))
        (is (seq (get-in m [:source :evidence])))))
    (testing "a proposal for review joins no one: it is a candidate merge, for a curator"
      (let [review (for [d (:decisions (api/reconcile @records pool {:engine :probabilistic}))
                         p (:proposals d) :when (= :review (:decision p))]
                     [(:mention d) (:authority-id p)])
            joined (set (for [c cs l (:links c)] [(:mention l) (:authority l)]))]
        (is (seq review))
        (is (not-any? joined review))
        (is (= (set review) (set (map (juxt :mention :authority) (:candidates res)))))))))

(deftest the-model-version-is-derived-from-the-model
  (let [{:keys [version model]} (api/reconciliation-model)]
    (testing "the sha256 of the parameters as the model holds them, the fingerprint of its weights, and an informational path"
      (is (= #{:sha256 :weights :path :source} (set (keys version))))
      (is (= :classpath (:source version)))
      (is (= 64 (count (:sha256 version))))
      (is (= (fs/fingerprint (:weights model)) (:weights version)))
      (is (= (rc/model-version model) (dissoc version :path :source)))
      (is (= "palomar/models/agent-match.edn" (:path version)) "classpath-relative"))
    (testing "a comment or a change of layout is no new version"
      (let [text (slurp (io/resource "palomar/models/agent-match.edn"))
            f    (tmp-model (str ";; another comment\n\n" (str/replace text "\n " "\n     ")))]
        (is (= (dissoc version :path :source) (dissoc (:version (api/reconciliation-model f)) :path :source)))))
    (testing "a changed parameter is"
      (let [v (:version (api/reconciliation-model (tmp-model (assoc @shipped-params :prior 0.2))))]
        (is (not= (:sha256 version) (:sha256 v)))
        (is (= (:weights version) (:weights v)) "the prior is no weight"))
      (let [v (:version (api/reconciliation-model (tmp-model (assoc-in @shipped-params [:u-counts :name :counts :exact] 9.0))))]
        (is (not= (:weights version) (:weights v)))))
    (testing "a bare model is versioned by itself: two models, two versions"
      (let [a (held-out "Dumas") b (held-out "Hugo")]
        (is (not= (rc/model-version a) (rc/model-version b)))
        (is (= (rc/model-version a) (:model (api/reconcile @records nil {:engine :probabilistic :model a}))))))
    (testing "a version that is not its model's is refused"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"version"
                            (api/reconcile @records nil {:engine :probabilistic
                                                         :model {:model (held-out "Dumas") :version version}}))))))

(deftest a-shipped-model-is-named-by-its-classpath-path-and-nothing-else
  (testing "a path under palomar/models/ with no file there is read from the classpath, and says so"
    (let [{:keys [version]} (rc/read-model "palomar/models/agent-match.edn")]
      (is (= :classpath (:source version)))
      (is (= (:sha256 (:version (rc/read-model))) (:sha256 version)))))
  (testing "a file is a file"
    (is (= :file (:source (:version (rc/read-model (tmp-model @shipped-params)))))))
  (testing "a path outside palomar/models/ is never read from the classpath"
    (is (thrown? Exception (rc/read-model "agent-match.edn")))
    (is (thrown? Exception (rc/read-model "palomar/vocab/../models/agent-match.edn"))))
  (testing "a .. segment is refused"
    (doseq [p ["palomar/models/../models/agent-match.edn" "palomar/models/../../palomar/models/agent-match.edn"]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"\.\." (rc/read-model p)) p)))
  (testing "a file that shadows a shipped model is refused, never read in its place"
    (let [f (io/file "palomar/models/agent-match.edn")
          made (not (.exists (io/file "palomar")))]
      (try
        (io/make-parents f)
        (spit f (pr-str (assoc @shipped-params :thresholds {:propose 0.6 :review 0.05})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"shadows" (rc/read-model "palomar/models/agent-match.edn")))
        (finally
          (.delete f)
          (when made (.delete (io/file "palomar/models")) (.delete (io/file "palomar"))))))))

(deftest a-model-file-is-checked-before-it-is-used
  (doseq [[why params] [["a prior of 1" {:prior 1.0}]
                        ["a prior of 0" {:prior 0}]
                        ["a prior above 1" {:prior 2.5}]
                        ["no counts" {:prior 0.5}]
                        ["thresholds inverted" (assoc @shipped-params :thresholds {:propose 0.1 :review 0.9})]
                        ["a propose threshold of 1" (assoc @shipped-params :thresholds {:propose 1.0 :review 0.5})]
                        ["a review threshold of 0" (assoc @shipped-params :thresholds {:propose 0.9 :review 0.0})]
                        ["hand-set shares, no counts" {:prior 0.5 :m {:name {:exact 0.99 :none 0.01}} :u {:name {:exact 0.01 :none 0.99}}}]
                        ["a feature without counts" (update @shipped-params :m-counts dissoc :dates)]
                        ["a feature counted on no pair" (assoc-in @shipped-params [:u-counts :form :n] 0.0)]
                        ["not a map" [1 2 3]]]]
    (testing why
      (is (thrown? clojure.lang.ExceptionInfo (api/reconciliation-model (tmp-model params))))))
  (testing "a bare model is checked too"
    (is (thrown? clojure.lang.ExceptionInfo
                 (api/reconcile @records nil {:engine :probabilistic :model (assoc (held-out "Dumas") :prior 1.0)}))))
  (testing "the shipped parameters pass"
    (is (some? (api/reconciliation-model (tmp-model @shipped-params))))))

(deftest the-shipped-model-states-where-it-comes-from
  (let [{:keys [model]} (api/reconciliation-model)
        prov (:provenance model)
        c    (pr/corpus)]
    (testing "its data, its split, its pool, its licence and attribution"
      (is (= :all-in-sample (:split prov)))
      (is (= (set (map #(str pr/bib-dir %) pr/bib-files)) (set (map :path (:data prov)))))
      (is (every? #(= 64 (count (:sha256 %))) (:data prov)))
      (is (= (fs/pool-fingerprint (pr/dated-entries (:pool c))) (:pool prov)))
      (is (= (fs/pool-fingerprint (:entries (api/read-authorities pr/authority-file))) (:pool-as-read prov)))
      (is (= "Licence Ouverte / Etalab 2.0" (:licence prov)))
      (is (= "Source : Bibliothèque nationale de France" (:attribution prov))))
    (testing "a reconciliation states it"
      (is (= prov (:provenance (api/reconcile @records nil {:engine :probabilistic})))))
    (testing "it refuses to score its own training pool, as the eval reads it or as the CLI reads it"
      (is (thrown? clojure.lang.ExceptionInfo (rc/refuse-in-sample! model (pr/dated-entries (:pool c)))))
      (is (thrown? clojure.lang.ExceptionInfo (pr/refuse-in-sample! model (pr/dated-entries (:pool c)))))
      (is (thrown? clojure.lang.ExceptionInfo (rc/refuse-in-sample! model (:entries (api/read-authorities persons)))))
      (is (thrown? clojure.lang.ExceptionInfo (rc/refuse-in-sample! model [] {:sources [bib]})) "nor the records it was fitted on"))
    (testing "another pool, or a held-out model, passes"
      (is (nil? (rc/refuse-in-sample! model (:entries (api/read-authorities groups)))))
      (is (nil? (rc/refuse-in-sample! (held-out "Dumas") (pr/dated-entries (:pool c))))))
    (testing "the M-cycle eval refuses it"
      (is (thrown? clojure.lang.ExceptionInfo (pr/task-a-fs c (constantly model)))))))

(def ^:private model-readers
  "The eval files that read a shipped model on purpose, by exact name: N4's
   harness and its test (the hard benchmark), and the O5 external test's
   frozen namespace. Any other person-reconciliation eval file must not."
  #{"person_reconciliation_hard_n4.clj" "person_reconciliation_hard_n4_test.clj" "person_reconciliation_external.clj"})

(deftest the-m-cycle-eval-never-reads-the-shipped-model
  (testing "every allowed reader exists"
    (doseq [n model-readers]
      (is (.isFile (io/file "test/unit/palomar/eval" n)) n)))
  (testing "its sources name neither the resource nor the function that reads it"
    (doseq [f (.listFiles (io/file "test/unit/palomar/eval"))
            :let [n (.getName ^java.io.File f)]
            :when (and (str/includes? n "person_reconciliation") (not (model-readers n)))]
      (let [src (slurp f)]
        (is (not (str/includes? src "agent-match.edn")) n)
        (is (not (str/includes? src "reconciliation-model")) n)
        (is (not (str/includes? src "read-model")) n))))
  (testing "a run of its M4 task never calls it"
    (with-redefs [rc/read-model (fn [& _] (throw (ex-info "the shipped model was read" {})))]
      (let [c (pr/corpus)
            models (pr/fold-models (pr/fs-fit-data c))]
        (is (= 1 (:recall-1 (:all (pr/task-a-fs c #(models (:family %)))))))))))

(defn- kind-of [e] ({"person" :person "corporateBody" :group "family" :family} (:entity-type e)))

(deftest a-candidate-of-another-kind-is-rejected-never-weighed
  (let [P     (:entries (api/read-authorities persons))
        mixed (vec (concat P (:entries (api/read-authorities groups))))
        kinds (into {} (map (juxt :id kind-of)) mixed)
        {:keys [model]} (api/reconciliation-model)
        ms    (mentions/batch @records)]
    (testing "against a persons-only pool, no group mention is abstained: it has no candidate, or is rejected"
      (let [res (api/reconcile @records P {:engine :probabilistic})]
        (is (seq (filter #(= :group (:kind %)) (:decisions res))))
        (is (every? #{:none :rejected} (map :decision (filter #(= :group (:kind %)) (:decisions res)))))))
    (testing "a rejection for its kind says so, and is not weighed"
      (let [{:keys [rejected proposals abstained]} (reconcile/agent-candidates ms mixed {:model model})
            krej (filter #(= :kind-mismatch (:reason %)) rejected)]
        (is (seq krej))
        (is (every? #(not= (get-in % [:mention :kind]) (kinds (:authority-id %))) krej))
        (is (not-any? :match-weight krej))
        (is (every? #(= (get-in % [:mention :kind]) (kinds (:authority-id %))) (concat proposals abstained)))))
    (testing "no recall is lost: every candidate of the mention's own kind proposed without the check still is"
      (let [res (api/reconcile @records mixed {:engine :probabilistic})]
        (doseq [m ms
                :let [before (->> (:proposals (reconcile/agent-candidates [(dissoc m :kind)] mixed {:model model}))
                                  (filter #(= (:kind m) (kinds (:authority-id %))))
                                  (map :authority-id) set)
                      after  (set (map :authority-id (:proposals (decision-of res (:id m)))))]]
          (is (every? after before) (pr-str (:id m))))))
    (testing "explain says why"
      (let [g (first (filter #(= :group (:kind %)) ms))
            x (api/reconcile-explain @records mixed (:id g))
            k (filter #(= :kind-mismatch (:reason %)) (:candidates x))]
        (is (seq k))
        (is (every? #(str/includes? (:why %) "kind") k))))))

(deftest a-stated-event-rejects-a-candidate
  (let [xml  (str/replace (slurp lido) #"<lido:vitalDatesActor>.*?</lido:vitalDatesActor>" "")
        recs (:records (api/pivot :lido {} xml))
        m    (first (filter #(= "Böcklin, Arnold" (:name %)) (mentions/batch recs)))
        pool [{:id "http://example.org/agents/boecklin-later" :label "Böcklin, Arnold"
               :dates (dates/parse-life-dates "1880-1950") :entity-type "person"}]
        d    (decision-of (api/reconcile recs pool {:engine :probabilistic}) (:id m))]
    (testing "the actor states no life dates, only the painting it made (1861)"
      (is (nil? (:dates m)))
      (is (= [:creation] (map :kind (:events m)))))
    (testing "a namesake born in 1880 cannot have made it: rejected for the mention's event, never weighed"
      (is (= :rejected (:decision d)))
      (is (empty? (:proposals d)))
      (is (= [:incompatible-events] (map :reason (:candidates (api/reconcile-explain recs pool (:id m)))))))))

(deftest explain-with-another-model
  (let [pool (api/read-authorities persons)
        m    (mention-of "700" "Férat, Jules")
        strict (api/reconciliation-model (tmp-model (assoc @shipped-params :thresholds {:propose 0.999 :review 0.4})))
        x    (api/reconcile-explain @records pool (:id m) {:model strict})]
    (testing "its thresholds and its decision"
      (is (= {:propose 0.999 :review 0.4} (:thresholds x)))
      (is (= :review (:decision x)) "0.9987 is below 0.999")
      (is (= (:decision (decision-of (api/reconcile @records pool {:engine :probabilistic :model strict}) (:id m))) (:decision x)))
      (is (= (:version strict) (:model x))))
    (testing "a model calibrated on this pool says so"
      (let [{:keys [model]} (api/reconciliation-model)
            cal (fs/with-calibration model (well-calibrated model (:entries pool)))]
        (is (true? (:calibrated? (api/reconcile-explain @records pool (:id m) {:model cal}))))))))

(deftest a-rejection-is-a-cannot-link-of-the-clustering
  (let [pool (api/read-authorities persons)
        m    (mention-of "700" "Férat, Jules")
        res  (api/reconcile-clusters @records pool)
        rej  (set (map :authority-id (filter :reason (:candidates (api/reconcile-explain @records pool (:id m))))))
        cl   (set (for [c (:cannot-link res) :when (= (:id m) (:mention c))] (:authority c)))]
    (is (seq rej))
    (is (= rej cl))
    (is (every? :reason (:cannot-link res)))
    (testing "and no cluster holds a mention with an authority it was rejected against"
      (doseq [c (:clusters res) :when (:authority c)]
        (is (not-any? #(and (= (:authority c) (:authority %)) ((set (:members c)) (:mention %))) (:cannot-link res)))))))

(deftest a-batch-that-names-a-mention-twice-is-refused
  (let [pool (api/read-authorities persons)
        r    (first (filter #(seq (mentions/mentions %)) @records))]
    (doseq [f [#(api/reconcile [r r] pool {:engine :probabilistic})
               #(api/reconcile-clusters [r r] pool)
               #(api/reconcile-explain [r r] pool (:id (first (mentions/mentions r))))]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"twice" (f))))))

(deftest the-model-is-a-versioned-parameter-file
  (let [{:keys [model]} (api/reconciliation-model)]
    (testing "its parameters are the M4 model fitted on all five families of the M2 gold"
      (let [full (pr/supervised-model (pr/fs-fit-data (pr/corpus)) (set (conj pr/families "others")))]
        (is (= (select-keys full [:prior :thresholds :bar :weights :m-counts :u-counts])
               (select-keys model [:prior :thresholds :bar :weights :m-counts :u-counts])))))
    (testing "uncalibrated"
      (is (nil? (:calibration model))))))

;; ---------------------------------------------------------------------------
;; Lot O4 — the gap, the rules a file states, the explanations
;; ---------------------------------------------------------------------------

(deftest a-gap-is-a-finite-number-above-0-or-absent
  (doseq [[why g] [["0" 0.0] ["negative" -3.0] ["a string" "12"] ["NaN" ##NaN] ["infinite" ##Inf] ["a keyword" :wide]]]
    (testing why
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"gap"
                            (api/reconciliation-model (tmp-model (assoc-in @shipped-params [:thresholds :gap] g)))))))
  (testing "a bare model is checked too"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"gap"
                          (api/reconcile @records nil {:engine :probabilistic
                                                       :model  (assoc-in (held-out "Dumas") [:thresholds :gap] 0.0)}))))
  (testing "a finite gap above 0, or none, passes"
    (is (= 12.0 (get-in (api/reconciliation-model (tmp-model (assoc-in @shipped-params [:thresholds :gap] 12.0))) [:model :thresholds :gap])))
    (is (some? (api/reconciliation-model (tmp-model @shipped-params))))))

(deftest a-file-that-states-no-rules-predates-o4-and-is-read-as-it-was
  (let [n3     (edn/read-string (slurp (io/resource "palomar/models/agent-match-n3.edn")))
        n4-era (-> n3 (dissoc :rules) (assoc :thresholds {:propose 0.8 :review 0.5}))
        {:keys [model version]} (rc/read-model (tmp-model n4-era))]
    (testing "the N3 model as N4 shipped it: the same version, to the bit, and the N4 rules"
      (is (= {:sha256 "6547f220552925540192ac805c06ec8bc16b6f9ae5d53265154375c958c6dd81" :weights "b3454bd60a2ca517"}
             (select-keys version [:sha256 :weights])))
      (is (= fs/n4-rules (fs/rules-of model)))
      (is (= (:weights (fs/model (assoc n4-era :rules fs/n4-rules))) (:weights model)))
      (is (nil? (fs/competing-gap model))))
    (testing "the shipped files state their rules"
      (is (= fs/o4-rules (:rules @shipped-params)))
      (is (= fs/o4-rules (:rules n3))))))

(deftest explain-says-when-the-gap-decided
  (let [why (fn [th n p] (#'rc/candidate-why th n p))
        th  {:propose 0.8 :review 0.5 :gap 6.0}]
    (testing "an outright proposal under a gap names it"
      (is (str/includes? (why th 0 {:decision :propose :score 0.99}) "within 6.0 bits")))
    (testing "competing candidates under a gap name it"
      (is (str/includes? (why th 2 {:decision :review :review-reason :competing :score 0.9}) "2 candidates of the mention are, within 6.0 bits")))
    (testing "an outranked candidate says another weighs G bits or more above it"
      (let [t (why th 2 {:decision :review :review-reason :outranked :score 0.9})]
        (is (str/includes? t "outranked") t)
        (is (str/includes? t "6.0 bits or more above it") t)))
    (testing "without a gap, the N4 texts"
      (is (str/includes? (why (dissoc th :gap) 0 {:decision :propose :score 0.99}) "no other candidate of the mention is at or above the review threshold 0.5"))))
  (testing "the competing count is of the competing candidates, not of every proposal"
    (is (= 2 (#'rc/competing-count [{:review-reason :competing} {:review-reason :competing} {:review-reason :outranked}
                                    {:review-reason :no-name-agreement}])))
    (is (= 2 (#'rc/competing-count [{:decision :propose} {:decision :review}])) "with none competing, every proposal")))

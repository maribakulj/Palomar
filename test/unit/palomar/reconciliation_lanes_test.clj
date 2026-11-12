(ns palomar.reconciliation-lanes-test
  "Candidate lanes in a reconciliation run (lot O6, ADR 0060 amendment): the
   probabilistic engine ranks the candidates the lanes generate
   (`candidates/rank`, with the pool's statistics prepared once); the API
   takes `:lanes`, the CLI `--lanes`; the default, the name lane, decides as
   before, byte for byte; the historical engine refuses any lane but the
   name; explain says which lanes found each candidate; the manifest names
   the lanes; and a candidate an identifier alone brings is for review at
   most."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.api :as api]
            [palomar.authority-index :as index]
            [palomar.candidates :as candidates]
            [palomar.cli :as cli]
            [palomar.manifest :as manifest]
            [palomar.mentions :as mentions]
            [palomar.reconcile :as reconcile]
            [palomar.reconciliation :as rc]))

(def bib "test/fixtures/documentary/intermarc/sru/intermarcXchange/bnf-sru-jules-verne-50.xml")
(def dumas "test/fixtures/documentary/intermarc/sru/intermarcXchange/bnf-sru-alexandre-dumas-50.xml")
(def persons "test/fixtures/er-gold/bnf-persons/authorities.xml")
(def groups "test/fixtures/reconcile/verne-groups-mads.rdf")
(def lido "test/fixtures/documentary/lido/staedel-lido-wrap.xml")
(def extract "test/fixtures/er-gold/gnd-persons/gnd-extract.jsonl")

(defn- tmp [suffix] (doto (java.io.File/createTempFile "palomar-o6-" suffix) (.deleteOnExit)))

(def ^:private gnd-index
  (delay (let [f (tmp ".edn")] (index/build! extract f {:built "2026-11-09"}) (str f))))

(defn- read-edn [s]
  (edn/read-string {:readers {'inst #(java.util.Date/from (java.time.Instant/parse %))}} s))

(defn- records [path from] (:records (api/pivot from {} (slurp path))))

(def ^:private verne (delay (records bib :intermarc)))

(defn- run [& args] (cli/run (vec args)))

(defn- cli-edn [& args]
  (let [res (cli/run (vec args))]
    (is (zero? (:exit res)) (:err res))
    (read-edn (:out res))))

;; A MARC21 record whose 100 names Paul Schlienger in Cyrillic letters, with
;; his life dates and his ISNI: no name key reaches him, his ISNI does.
(def ^:private cyrillic-xml
  (str "<collection xmlns=\"http://www.loc.gov/MARC21/slim\"><record><leader>00000nam a2200000 a 4500</leader>"
       "<controlfield tag=\"001\">o6cyr</controlfield>"
       "<datafield tag=\"100\" ind1=\"1\" ind2=\" \"><subfield code=\"a\">Шлингер, Пауль,</subfield>"
       "<subfield code=\"d\">1869-1956.</subfield><subfield code=\"0\">(isni)0000000418677351</subfield></datafield>"
       "<datafield tag=\"245\" ind1=\"0\" ind2=\"0\"><subfield code=\"a\">Un titre</subfield></datafield>"
       "</record></collection>"))

(def ^:private schlienger "http://catalogue.bnf.fr/ark:/12148/cb104157513")

(def ^:private cyrillic
  (delay (let [f (tmp ".xml")] (spit f cyrillic-xml :encoding "UTF-8") (str f))))

(def ^:private cyrillic-mention "[:marc/ro6cyr \"100\" 0]")

(defn- eager-model-file
  "The shipped parameters with a prior so high that dates alone carry a
   candidate above the propose threshold: what the review cap is for."
  []
  (let [f (tmp ".edn")]
    (spit f (pr-str (assoc (edn/read-string (slurp (io/resource "palomar/models/agent-match.edn"))) :prior 0.999999)))
    (str f)))

;; ---------------------------------------------------------------------------
;; The default is unchanged
;; ---------------------------------------------------------------------------

(defn- unlaned
  "A result with lanes asked, without what asking adds: `:lanes`, and each
   decision's `:reports`."
  [r]
  (cond-> (dissoc r :lanes)
    (:decisions r) (update :decisions (partial mapv #(dissoc % :reports)))))

(defn- legacy-decisions
  "The decisions of the probabilistic engine as lot N2 made them: every
   mention scored at once by `agent-candidates`, against the pool's block or
   the whole pool, each finding its own by the key the matcher put on them."
  [recs pool]
  (let [model (:model (rc/read-model))
        ms    (mentions/batch recs)
        es    (:entries pool)
        r     (when (seq es) (reconcile/agent-candidates ms es (cond-> {:model model} (:block pool) (assoc :block (:block pool)))))
        by    (into {} (for [k [:proposals :abstained :rejected]] [k (group-by :mention (get r k))]))]
    (vec (for [m ms :let [k (reconcile/matcher-key m)]]
           (#'rc/decision-entry m (into {} (for [x [:proposals :abstained :rejected]] [x (vec (get-in by [x k]))])))))))

(deftest the-name-lane-decides-as-before
  (doseq [[path from pool] [[bib :intermarc persons] [bib :intermarc groups] [dumas :intermarc persons]
                            [lido :lido persons] [bib :intermarc @gnd-index]]]
    (testing (str path " against " pool)
      (let [recs (records path from)
            p    (api/read-authorities pool)
            res  (api/reconcile recs p {:engine :probabilistic})]
        (is (= (legacy-decisions recs p) (:decisions res)))
        (is (not (contains? res :lanes)) "no lane asked, none stated")
        (testing "naming the name lane decides alike, and says so"
          (let [named (api/reconcile recs p {:engine :probabilistic :lanes [:name]})]
            (is (= (:decisions res) (:decisions (unlaned named))))
            (is (every? #(= [] (:reports %)) (:decisions named)) "the name lane reports nothing")
            (is (= [:name] (:lanes named)))
            (is (= res (unlaned named)))))))))

(deftest the-default-cli-output-is-unchanged
  (let [base ["reconcile" bib "--from" "intermarc" "--authorities" persons]]
    (doseq [extra [["--engine" "probabilistic"] ["--engine" "probabilistic" "--format" "edn"]
                   ["--format" "edn"] []]]
      (testing (pr-str extra)
        (let [a (apply run (concat base extra))
              b (apply run (concat base extra ["--lanes" "name"]))]
          (is (zero? (:exit a)) (:err a))
          (is (zero? (:exit b)) (:err b))
          (if (some #{"edn"} extra)
            (is (= (read-edn (:out a)) (unlaned (read-edn (:out b)))))
            (is (= (:out a) (:out b)) "the text says nothing new")))))
    (testing "the probabilistic text is the decisions of lot N2"
      (let [recs (records bib :intermarc)
            p    (api/read-authorities persons)
            old  (rc/format-reconciliation (assoc (api/reconcile recs p {:engine :probabilistic}) :decisions (legacy-decisions recs p)))]
        (is (= old (:out (run "reconcile" bib "--from" "intermarc" "--authorities" persons "--engine" "probabilistic"))))))))

;; ---------------------------------------------------------------------------
;; The CLI decides what the API decides, with lanes
;; ---------------------------------------------------------------------------

(deftest cli-results-are-api-results-with-lanes
  (let [pool (api/read-authorities persons)
        id   "[:bnf/cb35721876c \"700\" 0]"
        both [:name :identifier]]
    (doseq [lanes ["name,identifier" "identifier" "identifier,name"]
            :let [ls (if (= "identifier" lanes) [:identifier] both)]]
      (testing lanes
        (let [api (api/reconcile @verne pool {:engine :probabilistic :lanes ls})]
          (is (seq (:decisions api)))
          (is (= ls (:lanes api)))
          (is (= api (cli-edn "reconcile" bib "--from" "intermarc" "--authorities" persons "--engine" "probabilistic"
                              "--lanes" lanes "--format" "edn")))
          (is (= (api/format-reconciliation api)
                 (:out (run "reconcile" bib "--from" "intermarc" "--authorities" persons "--engine" "probabilistic" "--lanes" lanes)))))
        (let [api (api/reconcile-explain @verne pool (edn/read-string id) {:lanes ls})]
          (is (= api (cli-edn "reconcile" "explain" bib "--from" "intermarc" "--authorities" persons "--mention" id
                              "--lanes" lanes "--format" "edn")))
          (is (= (api/format-explanation api)
                 (:out (run "reconcile" "explain" bib "--from" "intermarc" "--authorities" persons "--mention" id "--lanes" lanes)))))
        (let [api (api/reconcile-clusters @verne pool {:lanes ls})]
          (is (= api (cli-edn "reconcile" "clusters" bib "--from" "intermarc" "--authorities" persons
                              "--lanes" lanes "--format" "edn")))
          (is (= (api/format-clusters api)
                 (:out (run "reconcile" "clusters" bib "--from" "intermarc" "--authorities" persons "--lanes" lanes)))))))
    (testing "the order lanes are named in changes nothing"
      (is (= (api/reconcile @verne pool {:engine :probabilistic :lanes [:identifier :name]})
             (api/reconcile @verne pool {:engine :probabilistic :lanes both}))))
    (testing "with an authority index"
      (let [p (api/read-authorities @gnd-index)]
        (is (= (api/reconcile @verne p {:engine :probabilistic :lanes both})
               (cli-edn "reconcile" bib "--from" "intermarc" "--authorities" @gnd-index "--engine" "probabilistic"
                        "--lanes" "name,identifier" "--format" "edn")))))))

;; ---------------------------------------------------------------------------
;; What is refused
;; ---------------------------------------------------------------------------

(deftest the-historical-engine-refuses-every-lane-but-the-name
  (let [pool (api/read-authorities persons)]
    (testing "from the CLI: exit 2, nothing written"
      (doseq [args [["--lanes" "identifier"] ["--lanes" "name,identifier"] ["--engine" "historical" "--lanes" "identifier"]]]
        (let [{:keys [exit out err]} (apply run "reconcile" bib "--from" "intermarc" "--authorities" persons args)]
          (is (= 2 exit) (pr-str args))
          (is (= "" out))
          (is (str/includes? err "historical")))))
    (testing "the name lane is what it reads: accepted, its output unchanged"
      (is (= (:out (run "reconcile" bib "--from" "intermarc" "--authorities" persons))
             (:out (run "reconcile" bib "--from" "intermarc" "--authorities" persons "--lanes" "name"))))
      (is (= (assoc (api/reconcile @verne pool) :lanes [:name]) (api/reconcile @verne pool {:lanes [:name]}))))
    (testing "from the API"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"historical" (api/reconcile @verne pool {:lanes [:identifier]})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"historical"
                            (api/reconcile @verne pool {:engine :historical :lanes [:name :identifier]}))))))

(deftest an-unknown-lane-is-refused
  (doseq [lanes ["phonetic" "" "name,,identifier" "name,phonetic"]]
    (let [{:keys [exit out err]} (run "reconcile" bib "--from" "intermarc" "--authorities" persons "--engine" "probabilistic" "--lanes" lanes)]
      (is (= 2 exit) lanes)
      (is (= "" out))
      (is (str/includes? err "--lanes") err)))
  (let [pool (api/read-authorities persons)]
    (doseq [ls [[] [:phonetic] :name "name"]]
      (is (thrown? clojure.lang.ExceptionInfo (api/reconcile @verne pool {:engine :probabilistic :lanes ls})) (pr-str ls)))))

;; ---------------------------------------------------------------------------
;; Provenance: explain and the manifest
;; ---------------------------------------------------------------------------

(deftest explain-says-which-lanes-found-each-candidate
  (let [pool (api/read-authorities persons)
        recs (records @cyrillic :marc21)
        x    (api/reconcile-explain recs pool (edn/read-string cyrillic-mention) {:lanes [:name :identifier]})
        c    (first (filter #(= schlienger (:authority-id %)) (:candidates x)))]
    (testing "a candidate the ISNI alone found names the identifier lane and the key that matched"
      (is (= [{:lane :identifier :keys ["isni:0000000418677351"] :values ["ISNI 0000000418677351"]}] (:generated-by c)))
      (is (= :none (get-in c [:levels :name]))))
    (testing "every candidate of a Verne mention names the lanes that found it, with their keys"
      (let [id  [:bnf/cb35721876c "700" 0]
            v   (api/reconcile-explain @verne pool id {:lanes [:name :identifier]})]
        (is (seq (:candidates v)))
        (is (every? #(and (seq (:generated-by %))
                          (every? (fn [b] (and (#{:name :identifier} (:lane b)) (seq (:keys b)))) (:generated-by %)))
                    (:candidates v)))))
    (testing "the text says it"
      (let [t (api/format-explanation x)]
        (is (str/includes? t "found by: identifier (isni:0000000418677351)"))))
    (testing "no lane asked: explain is as before, no provenance"
      (let [d (api/reconcile-explain @verne pool [:bnf/cb35721876c "700" 0])]
        (is (not-any? :generated-by (:candidates d)))
        (is (not (contains? d :lanes)))
        (is (not (str/includes? (api/format-explanation d) "found by")))))))

(deftest the-manifest-names-the-lanes
  (doseq [[args expected] [[["--lanes" "identifier,name"] [:name :identifier]] [[] nil]]
          sub [nil "clusters"]]
    (let [mf  (tmp ".edn")
          res (apply run (concat ["reconcile"] (when sub [sub]) [bib "--from" "intermarc" "--authorities" persons]
                                 (when-not sub ["--engine" "probabilistic"]) ["--manifest" (str mf)] args))
          m   (read-edn (slurp mf))]
      (is (zero? (:exit res)) (:err res))
      (is (= :probabilistic (get-in m [:reconcile :engine])))
      (is (= expected (get-in m [:reconcile :lanes])) (pr-str sub args))))
  (testing "manifest/diff tells a default run from a --lanes name run: their outputs differ too"
    (let [run-m (fn [& args] (let [mf (tmp ".edn")]
                               (apply run (concat ["reconcile" bib "--from" "intermarc" "--authorities" persons
                                                   "--engine" "probabilistic" "--format" "edn" "--manifest" (str mf)] args))
                               (read-edn (slurp mf))))
          a (run-m) b (run-m "--lanes" "name")
          d (manifest/diff a b)]
      (is (= [nil [:name]] (map :lanes (get d :reconcile))))
      (is (contains? d :outputs)))))

;; ---------------------------------------------------------------------------
;; An identifier alone never proposes
;; ---------------------------------------------------------------------------

(deftest an-identifier-only-candidate-from-the-cli-is-for-review-at-most
  (let [mf (eager-model-file)
        out (fn [& lanes] (let [r (cli-edn "reconcile" @cyrillic "--from" "marc21" "--authorities" persons "--engine" "probabilistic"
                                           "--model" mf "--format" "edn" "--lanes" (str/join "," lanes))]
                            (first (:decisions r))))]
    (testing "by name, no candidate: the name is in Cyrillic letters"
      (is (= :none (:decision (out "name")))))
    (testing "by its ISNI, Schlienger, whose dates agree, scores above the propose threshold yet is for review"
      (doseq [ls [["identifier"] ["name" "identifier"]]]
        (let [d (apply out ls)
              p (first (:proposals d))]
          (is (= :review (:decision d)) (pr-str ls))
          (is (= [schlienger :review :no-name-agreement] ((juxt :authority-id :decision :review-reason) p)))
          (is (<= 0.99 (:score p))))))
    (testing "explain says why"
      (let [x (cli-edn "reconcile" "explain" @cyrillic "--from" "marc21" "--authorities" persons "--model" mf
                       "--mention" cyrillic-mention "--lanes" "name,identifier" "--format" "edn")]
        (is (= :review (:decision x)))
        (is (str/includes? (:why x) "no name agrees"))))
    (testing "with the shipped model it is abstained"
      (let [d (first (:decisions (cli-edn "reconcile" @cyrillic "--from" "marc21" "--authorities" persons "--engine" "probabilistic"
                                          "--format" "edn" "--lanes" "name,identifier")))]
        (is (#{:abstain :review} (:decision d)))))))

;; ---------------------------------------------------------------------------
;; No $3 reaches a lane or the matcher on this path
;; ---------------------------------------------------------------------------

(deftest no-authority-record-reaches-a-lane-or-the-matcher
  (let [ms    (mentions/batch @verne)
        arecs (set (keep :authority-record ms))
        nums  (set (for [m ms [c v] (get-in m [:source :subfields]) :when (= "3" c)] v))
        seen  (atom [])
        spy   (fn [f pick] (fn [& args] (swap! seen into (pick args)) (apply f args)))
        gen   candidates/generate
        ac    reconcile/agent-candidates]
    (is (seq arecs) "the batch's mentions state $3s")
    (is (seq nums))
    (with-redefs [candidates/generate        (spy gen (comp vector second))
                  reconcile/agent-candidates (spy ac first)]
      (let [pool (api/read-authorities persons)]
        (api/reconcile @verne pool {:engine :probabilistic :lanes [:name :identifier]})
        (api/reconcile-explain @verne pool [:bnf/cb35721876c "700" 0] {:lanes [:name :identifier]})
        (api/reconcile-clusters @verne pool {:lanes [:name :identifier]})))
    (is (seq @seen))
    (is (not-any? #(and (map? %) (or (contains? % :authority-record) (contains? % :source))) @seen))
    (is (empty? (for [x (tree-seq coll? seq @seen) :when (string? x) :when (or (arecs x) (nums x))] x)))))

;; ---------------------------------------------------------------------------
;; The O1 path guard: --lanes names no file
;; ---------------------------------------------------------------------------

(deftest lanes-name-no-file
  (let [{:strs [reconcile]} (var-get (resolve 'palomar.cli/commands))
        flags {"authorities" "a.xml" "model" "m.edn" "manifest" "out.edn"}]
    (doseq [sub [nil "explain" "clusters"]
            :let [pos (if sub [sub "in.xml"] ["in.xml"])]]
      (is (= ((:roles reconcile) flags pos) ((:roles reconcile) (assoc flags "lanes" "name,identifier") pos))))
    (testing "a --lanes value that is a file's name is no file read or written"
      (let [f (tmp ".edn")]
        (spit f "keep")
        (is (= 2 (:exit (run "reconcile" bib "--from" "intermarc" "--authorities" persons "--engine" "probabilistic" "--lanes" (str f)))))
        (is (= "keep" (slurp f)))))))

;; ---------------------------------------------------------------------------
;; Review of lot O6
;; ---------------------------------------------------------------------------

(deftest a-hand-made-block-is-honoured
  (let [es    (:entries (api/read-authorities persons))
        verne? #(re-find #"(?i)verne" (str (:label %)))
        pools {:none      {:entries es :block (fn [_] [])}
               :no-verne  {:entries es :block (fn [_] (vec (remove verne? es)))}}]
    (doseq [[k p] pools]
      (testing (name k)
        (is (= (legacy-decisions @verne p) (:decisions (api/reconcile @verne p {:engine :probabilistic}))))
        (is (= (legacy-decisions @verne p) (:decisions (unlaned (api/reconcile @verne p {:engine :probabilistic :lanes [:name]})))))))
    (testing "the block bounds the name lane only: an id still finds its entry"
      (let [p   {:entries es :block (fn [_] [])}
            rs  (api/reconcile @verne p {:engine :probabilistic :lanes [:name :identifier]})
            x   (api/reconcile-explain @verne p [:bnf/cb35721876c "700" 0] {:lanes [:name :identifier]})]
        (is (seq (:decisions rs)))
        (is (every? (fn [c] (not-any? #(= :name (:lane %)) (:generated-by c))) (:candidates x)))))))

(deftest the-name-lane-reads-the-index-postings
  (let [p     (api/read-authorities @gnd-index)
        seen  (atom [])
        gen   candidates/generate]
    (with-redefs [candidates/generate (fn [idx m ls] (swap! seen conj (identical? (:postings idx) (get-in p [:index :postings]))) (gen idx m ls))]
      (api/reconcile @verne p {:engine :probabilistic}))
    (is (seq @seen))
    (is (every? true? @seen) "the index file's own postings, not postings rebuilt from the entries")
    (testing "postings that say nothing give no name candidate"
      (let [d (:decisions (api/reconcile @verne (assoc-in p [:index :postings] {}) {:engine :probabilistic}))]
        (is (every? #(= :none (:decision %)) d))))))

;; Two entries state Schlienger's ISNI; the 700 states an ISNI with a wrong
;; check character, an id no form reads and an ISNI no entry states.
(def ^:private twin-xml
  (str "<collection xmlns=\"http://www.loc.gov/MARC21/slim\"><record><leader>00000nam a2200000 a 4500</leader>"
       "<controlfield tag=\"001\">o6twin</controlfield>"
       "<datafield tag=\"100\" ind1=\"1\" ind2=\" \"><subfield code=\"a\">Шлингер, Пауль,</subfield>"
       "<subfield code=\"d\">1869-1956.</subfield><subfield code=\"0\">(isni)0000000418677351</subfield></datafield>"
       "<datafield tag=\"700\" ind1=\"1\" ind2=\" \"><subfield code=\"a\">Иванов, Иван,</subfield>"
       "<subfield code=\"0\">(isni)0000000418677350</subfield><subfield code=\"0\">(OCoLC)12345</subfield>"
       "<subfield code=\"0\">(isni)0000000123205891</subfield></datafield>"
       "<datafield tag=\"245\" ind1=\"0\" ind2=\"0\"><subfield code=\"a\">Un titre</subfield></datafield>"
       "</record></collection>"))

(deftest identifier-lane-reports-reach-the-user
  (let [es    (:entries (api/read-authorities persons))
        schl  (first (filter #(= schlienger (:id %)) es))
        pool  (conj (vec es) (assoc schl :id "http://example.org/twin" :label "Autre, Personne"))
        recs  (:records (api/pivot :marc21 {} twin-xml))
        ms    (mentions/batch recs)
        idx   (candidates/index pool)
        res   (api/reconcile recs pool {:engine :probabilistic :lanes [:name :identifier]})
        kinds (fn [rs] (set (map :kind rs)))]
    (testing "each decision carries its mention's reports, those of generation"
      (is (= (for [m ms] (:reports (candidates/generate idx (dissoc m :authority-record :source) [:name :identifier])))
             (map :reports (:decisions res))))
      (is (= #{:several-authorities} (kinds (:reports (first (:decisions res))))))
      (is (= #{:invalid-identifier :unread-identifier} (set/intersection #{:invalid-identifier :unread-identifier}
                                                                         (kinds (:reports (second (:decisions res))))))))
    (testing "the text names them"
      (let [t (api/format-reconciliation res)]
        (is (str/includes? t "report: several-authorities"))
        (is (str/includes? t "report: invalid-identifier"))))
    (testing "explain carries them, in EDN and in text"
      (let [x (api/reconcile-explain recs pool [:marc/ro6twin "100" 0] {:lanes [:name :identifier]})]
        (is (= #{:several-authorities} (kinds (:reports x))))
        (is (str/includes? (api/format-explanation x) "report: several-authorities"))))
    (testing "no lane asked: no reports, the output as before"
      (let [d (api/reconcile recs pool {:engine :probabilistic})]
        (is (not-any? #(contains? % :reports) (:decisions d)))
        (is (not (str/includes? (api/format-reconciliation d) "report:")))
        (is (not (contains? (api/reconcile-explain recs pool [:marc/ro6twin "100" 0]) :reports)))))))

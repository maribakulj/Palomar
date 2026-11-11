(ns palomar.reconcile-cli-test
  "`palomar reconcile` from the command line (lot N2): `--engine historical`
   (the default, its output byte for byte what it was) or `probabilistic`,
   `reconcile explain` and `reconcile clusters`; the CLI decides what the
   API decides; the run manifest names the engine and the model."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.api :as api]
            [palomar.cli :as cli]
            [palomar.manifest :as manifest]
            [palomar.plugins.agents :as agents]
            [palomar.reconcile :as reconcile]))

(def bib "test/fixtures/documentary/intermarc/sru/intermarcXchange/bnf-sru-jules-verne-50.xml")
(def persons "test/fixtures/er-gold/bnf-persons/authorities.xml")

(defn- tmp [suffix] (doto (java.io.File/createTempFile "palomar-n2-" suffix) (.deleteOnExit)))

(defn- read-edn [s]
  (edn/read-string {:readers {'inst #(java.util.Date/from (java.time.Instant/parse %))}} s))

(defn- records [] (:records (api/pivot :intermarc {} (slurp bib))))

(deftest the-default-engine-writes-what-reconcile-wrote-before
  (let [recs  (records)
        pool  (api/read-authorities persons)
        names (distinct (map :name (mapcat agents/occurrences recs)))
        old   (str (reconcile/format-agent-reconciliation (reconcile/reconcile-agents recs)) "\n"
                   (reconcile/format-proposals (reconcile/propose-agent-links names (:entries pool) {:threshold 0.5})))
        a     (cli/run ["reconcile" bib "--from" "intermarc" "--authorities" persons])
        b     (cli/run ["reconcile" bib "--from" "intermarc" "--authorities" persons "--engine" "historical"])]
    (is (zero? (:exit a)) (:err a))
    (is (= old (:out a)) "byte for byte the output of lot M8")
    (is (= (:out a) (:out b)) "--engine historical is the default")
    (testing "and without --authorities, the reconciliation by authority id alone"
      (is (= (reconcile/format-agent-reconciliation (reconcile/reconcile-agents recs))
             (:out (cli/run ["reconcile" bib "--from" "intermarc"])))))))

(def dc "test/fixtures/documentary/dublin-core/w3c_dc_example1.xml")

(defn- model-file
  "A model file holding the shipped parameters with other thresholds."
  []
  (let [f (tmp ".edn")]
    (spit f (pr-str (assoc (edn/read-string (slurp (io/resource "palomar/models/agent-match.edn")))
                           :thresholds {:propose 0.999 :review 0.4})))
    (str f)))

(defn- cli-edn [& args]
  (let [res (cli/run (vec args))]
    (is (zero? (:exit res)) (:err res))
    (read-edn (:out res))))

(deftest cli-results-are-api-results
  (let [pool (api/read-authorities persons)
        mf   (model-file)
        mdl  (api/reconciliation-model mf)
        id   "[:bnf/cb35721876c \"700\" 0]"]
    (testing "reconcile, each engine: the whole result, and its text"
      (doseq [engine [:historical :probabilistic]]
        (let [api (api/reconcile (records) pool {:engine engine})]
          (is (seq (:decisions api)))
          (is (= api (cli-edn "reconcile" bib "--from" "intermarc" "--authorities" persons "--engine" (name engine) "--format" "edn")))
          (is (= (api/format-reconciliation api)
                 (:out (cli/run ["reconcile" bib "--from" "intermarc" "--authorities" persons "--engine" (name engine)])))))))
    (testing "with --model, for the three verbs"
      (is (= (api/reconcile (records) pool {:engine :probabilistic :model mdl})
             (cli-edn "reconcile" bib "--from" "intermarc" "--authorities" persons "--engine" "probabilistic" "--model" mf "--format" "edn")))
      (is (= (api/reconcile-explain (records) pool (edn/read-string id) {:model mdl})
             (cli-edn "reconcile" "explain" bib "--from" "intermarc" "--authorities" persons "--mention" id "--model" mf "--format" "edn")))
      (is (= (api/reconcile-clusters (records) pool {:model mdl})
             (cli-edn "reconcile" "clusters" bib "--from" "intermarc" "--authorities" persons "--model" mf "--format" "edn"))))
    (testing "with --record-id, for the three verbs"
      (let [recs (:records (api/pivot :dc {:record-id :doc/x} (slurp dc)))
            m    (first (filter #(= :doc/x (:record %)) (:decisions (api/reconcile recs pool {:engine :probabilistic}))))]
        (is (some? m))
        (is (= (api/reconcile recs pool {:engine :probabilistic})
               (cli-edn "reconcile" dc "--from" "dc" "--authorities" persons "--engine" "probabilistic" "--record-id" "doc/x" "--format" "edn")))
        (is (= (api/reconcile-explain recs pool (:mention m))
               (cli-edn "reconcile" "explain" dc "--from" "dc" "--authorities" persons "--record-id" "doc/x"
                        "--mention" (pr-str (:mention m)) "--format" "edn")))
        (is (= (api/reconcile-clusters recs pool)
               (cli-edn "reconcile" "clusters" dc "--from" "dc" "--authorities" persons "--record-id" "doc/x" "--format" "edn")))))))

(deftest the-probabilistic-engine-reaches-every-mention-from-the-cli
  (let [{:keys [exit out err]} (cli/run ["reconcile" bib "--from" "intermarc" "--authorities" persons
                                         "--engine" "probabilistic"])
        header (first (filter #(str/starts-with? % "Probabilistic decisions") (str/split-lines out)))]
    (is (zero? exit) err)
    (is (str/includes? out "\"Férat, Jules\"") "a 700, which the floor does not carry")
    (testing "the counts say what they count"
      (is (str/includes? header "83 mentions read from the records"))
      (is (str/includes? header "3 distinct names on the canonical floor")))
    (testing "the model by its version, not its path"
      (is (str/includes? header (subs (:sha256 (:version (api/reconciliation-model))) 0 12)))
      (is (not (str/includes? out "palomar/models/agent-match.edn"))))))

(deftest an-empty-batch-reads-cleanly
  (let [f (tmp ".xml")]
    (spit f (str "<?xml version=\"1.0\"?><srw:searchRetrieveResponse xmlns:srw=\"http://www.loc.gov/zing/srw/\">"
                 "<srw:numberOfRecords>0</srw:numberOfRecords><srw:records/></srw:searchRetrieveResponse>"))
    (let [{:keys [exit out]} (cli/run ["reconcile" (str f) "--from" "intermarc" "--authorities" persons "--engine" "probabilistic"])
          header (first (filter #(str/starts-with? % "Probabilistic decisions") (str/split-lines out)))]
      (is (zero? exit))
      (is (str/includes? header "0 mentions"))
      (is (not (re-find #":\s*$" header)) "no trailing colon"))))

(deftest reconcile-explain-from-the-cli
  (let [id  "[:bnf/cb35721876c \"700\" 0]"
        res (cli/run ["reconcile" "explain" bib "--from" "intermarc" "--authorities" persons "--mention" id])
        x   (api/reconcile-explain (records) (api/read-authorities persons) (edn/read-string id))]
    (is (zero? (:exit res)) (:err res))
    (is (= (api/format-explanation x) (:out res)))
    (is (str/includes? (:out res) "Férat, Jules"))
    (is (str/includes? (:out res) "posterior")))
  (testing "usage errors"
    (is (= 2 (:exit (cli/run ["reconcile" "explain" bib "--from" "intermarc" "--authorities" persons]))) "no --mention")
    (is (= 2 (:exit (cli/run ["reconcile" "explain" bib "--from" "intermarc" "--authorities" persons "--mention" "[:x/y \"700\" 0]"])))
        "an unknown mention")
    (is (= 2 (:exit (cli/run ["reconcile" "explain" bib "--from" "intermarc" "--mention" "[:x/y \"700\" 0]"]))) "no --authorities")
    (is (= 2 (:exit (cli/run ["reconcile" "explain" bib "--from" "intermarc" "--authorities" persons
                              "--mention" "[:bnf/cb35721876c \"700\" 0]" "--engine" "historical"])))
        "explain is the probabilistic engine's")))

(deftest reconcile-clusters-from-the-cli
  (let [res (cli/run ["reconcile" "clusters" bib "--from" "intermarc" "--authorities" persons])
        cs  (api/reconcile-clusters (records) (api/read-authorities persons))]
    (is (zero? (:exit res)) (:err res))
    (is (= (api/format-clusters cs) (:out res)))
    (is (str/includes? (:out res) "cb11902539r"))))

(deftest reconcile-refuses-what-it-does-not-know
  (is (= 2 (:exit (cli/run ["reconcile" bib "--from" "intermarc" "--engine" "fuzzy"]))))
  (is (= 2 (:exit (cli/run ["reconcile" bib "--from" "intermarc" "--format" "xml"]))))
  (is (= 2 (:exit (cli/run ["reconcile" bib "--from" "intermarc" "--engine" "historical" "--model" persons])))
      "a model is the probabilistic engine's")
  (testing "--mention is explain's"
    (let [{:keys [exit err]} (cli/run ["reconcile" bib "--from" "intermarc" "--authorities" persons "--engine" "probabilistic"
                                       "--mention" "[:bnf/cb35721876c \"700\" 0]"])]
      (is (= 2 exit))
      (is (str/includes? err "explain"))))
  (testing "an unknown source format"
    (let [{:keys [exit err]} (cli/run ["reconcile" bib "--from" "bogus"])]
      (is (= 2 exit))
      (is (str/includes? err "unknown format"))))
  (testing "a batch that names a mention twice"
    (let [f (tmp ".xml")
          x (slurp bib)
          r (re-find #"(?s)<srw:record>.*?</srw:record>" x)]
      (spit f (str/replace-first x r (str r r)))
      (let [{:keys [exit err]} (cli/run ["reconcile" (str f) "--from" "intermarc" "--authorities" persons "--engine" "probabilistic"])]
        (is (= 2 exit))
        (is (str/includes? err "twice"))))))

(deftest a-model-file-is-refused-when-it-cannot-be-trusted
  (let [params (edn/read-string (slurp (io/resource "palomar/models/agent-match.edn")))
        run    (fn [p] (let [f (tmp ".edn")]
                         (spit f (pr-str p))
                         (cli/run ["reconcile" bib "--from" "intermarc" "--authorities" persons "--engine" "probabilistic" "--model" (str f)])))]
    (testing "a calibration in a model file is refused, for now"
      (let [{:keys [exit err]} (run (assoc params :calibration {:pool "1fcf8f926254f5d8" :ece 0.0 :max-bin-gap 0.0
                                                                :distinct-pairs 1000000 :distinct-positives 1000000 :review-pairs 1000}))]
        (is (= 2 exit))
        (is (str/includes? err "calibration"))))
    (testing "and so is a model that is no model"
      (doseq [p [{:prior 1.0} {:prior 0} {:prior 2.5} {:prior 0.5}
                 (assoc params :thresholds {:propose 0.1 :review 0.9})
                 (assoc-in params [:thresholds :gap] 0.0)
                 (assoc-in params [:thresholds :gap] -2.0)
                 (assoc-in params [:thresholds :gap] ##NaN)
                 (assoc params :rules [:o4])
                 {:prior 0.5 :m {:name {:exact 0.99 :none 0.01}} :u {:name {:exact 0.01 :none 0.99}}}]]
        (let [{:keys [exit err]} (run p)]
          (is (= 2 exit) (pr-str p))
          (is (str/includes? err "model") (pr-str p)))))))

(deftest the-help-names-the-engines-and-the-subcommands
  (let [{:keys [err]} (cli/run ["--help"])
        lines (str/split-lines err)]
    (is (str/includes? err "--engine"))
    (doseq [sub ["reconcile explain" "reconcile clusters"]]
      (let [l (first (filter #(str/includes? % sub) lines))]
        (is (str/includes? l "--manifest") sub)
        (is (str/includes? l "--record-id") sub)))))

(deftest the-manifest-names-the-engine-and-the-model
  (let [mh  (tmp ".edn")
        mp  (tmp ".edn")
        run (fn [m & more] (apply vector "reconcile" bib "--from" "intermarc" "--authorities" persons "--manifest" (str m) more))
        shipped (api/reconciliation-model)]
    (is (zero? (:exit (cli/run (run mh)))))
    (is (zero? (:exit (cli/run (run mp "--engine" "probabilistic")))))
    (let [h (read-edn (slurp mh)) p (read-edn (slurp mp))]
      (testing "the historical engine and its name-only rule"
        (is (= :historical (get-in h [:reconcile :engine])))
        (is (= {:name "token-jaccard" :threshold 0.5} (get-in h [:reconcile :model]))))
      (testing "the probabilistic engine, its model's version and its provenance"
        (is (= :probabilistic (get-in p [:reconcile :engine])))
        (is (= (:version shipped) (get-in p [:reconcile :model])))
        (is (= (:provenance (:model shipped)) (get-in p [:reconcile :provenance])))
        (is (= "Licence Ouverte / Etalab 2.0" (get-in p [:reconcile :provenance :licence]))))
      (testing "diff names the engine, and the output it changed"
        (is (= #{:reconcile :outputs} (set (keys (manifest/diff h p)))))))
    (testing "the same model at a relative or an absolute path: the same manifest"
      (let [rel "target/palomar-n2-test-model.edn"
            f   (io/file rel)
            ma  (tmp ".edn")
            mb  (tmp ".edn")]
        (io/make-parents f)
        (spit f (slurp (io/resource "palomar/models/agent-match.edn")))
        (try
          (is (zero? (:exit (cli/run (run ma "--engine" "probabilistic" "--model" rel)))))
          (is (zero? (:exit (cli/run (run mb "--engine" "probabilistic" "--model" (.getAbsolutePath f))))))
          (let [a (read-edn (slurp ma)) b (read-edn (slurp mb))]
            (is (= rel (get-in a [:reconcile :model :path])) "the path is kept, for information")
            (is (empty? (manifest/diff a b)))
            (is (= (dissoc (:version shipped) :path :source) (dissoc (get-in a [:reconcile :model]) :path :source)))
            (is (= [:classpath :file] [(get-in (read-edn (slurp mp)) [:reconcile :model :source]) (get-in a [:reconcile :model :source])])
                "the manifest says where the model was read, for information")
            (is (empty? (manifest/diff (read-edn (slurp mp)) a)) "and the same as the shipped model's"))
          (finally (.delete f)))))
    (testing "explain and clusters write a manifest too"
      (doseq [sub [["explain" "--mention" "[:bnf/cb35721876c \"700\" 0]"] ["clusters"]]]
        (let [m (tmp ".edn")]
          (is (zero? (:exit (cli/run (vec (concat ["reconcile" (first sub) bib "--from" "intermarc" "--authorities" persons
                                                   "--manifest" (str m)] (rest sub)))))))
          (is (= :probabilistic (get-in (read-edn (slurp m)) [:reconcile :engine]))))))))

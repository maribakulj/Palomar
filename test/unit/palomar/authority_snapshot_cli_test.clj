(ns palomar.authority-snapshot-cli-test
  "Authority snapshots from the command line (lot M6): `palomar authorities
   index` builds the index; `reconcile --authorities <index>` uses it, offline;
   its run manifest names the index, the dump it was built from and the
   build date, and `manifest/diff` shows a changed index."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.authority-index :as index]
            [palomar.cli :as cli]
            [palomar.convert :as convert]
            [palomar.manifest :as manifest]
            [palomar.plugins.agents :as agents]
            [palomar.reconcile :as reconcile]))

(def extract "test/fixtures/er-gold/gnd-persons/gnd-extract.jsonl")
(def bib "test/fixtures/documentary/intermarc/sru/intermarcXchange/bnf-sru-alexandre-dumas-50.xml")

(defn- tmp [suffix] (doto (java.io.File/createTempFile "palomar-snap-" suffix) (.deleteOnExit)))

(defn- read-manifest [f]
  (edn/read-string {:readers {'inst #(java.util.Date/from (java.time.Instant/parse %))}} (slurp f)))

(defn- index! [built]
  (let [out (tmp ".edn")
        res (cli/run ["authorities" "index" extract "--out" (str out) "--built" built])]
    (is (zero? (:exit res)) (:err res))
    out))

(deftest authorities-index-builds-the-index
  (let [out (index! "2026-11-09")]
    (is (= (slurp out) (let [o (tmp ".edn")] (index/build! extract o {:built "2026-11-09"}) (slurp o))))
    (testing "the usage errors"
      (is (= 2 (:exit (cli/run ["authorities" "index" extract]))))
      (is (= 2 (:exit (cli/run ["authorities" "index" "no/such/dump.jsonl" "--out" (str (tmp ".edn"))]))))
      (is (= 2 (:exit (cli/run ["authorities" "index" extract "--out" (str (tmp ".edn")) "--built" "yesterday"]))))
      (is (= 2 (:exit (cli/run ["authorities" "frobnicate"])))))
    (testing "without --built, the date of the day (UTC) is recorded"
      (let [o (tmp ".edn")]
        (is (zero? (:exit (cli/run ["authorities" "index" extract "--out" (str o)]))))
        (is (= (str (java.time.LocalDate/now java.time.ZoneOffset/UTC)) (:built (index/read-header o))))))))

(deftest reconcile-reads-an-index-and-proposes-as-a-full-scan-would
  (let [out   (index! "2026-11-09")
        {:keys [exit] :as res} (cli/run ["reconcile" bib "--from" "intermarc" "--authorities" (str out)])
        recs  (:records (convert/to-wemi :intermarc {} (slurp bib)))
        names (distinct (map :name (mapcat agents/occurrences recs)))
        full  (reconcile/propose-agent-links names (:entries (index/read-index out)))]
    (is (zero? exit) (:err res))
    (is (seq full))
    (is (str/includes? (:out res) (reconcile/format-proposals full)))))

(deftest a-dump-is-not-read-during-a-reconciliation
  (let [res (cli/run ["reconcile" bib "--from" "intermarc" "--authorities" extract])]
    (is (= 2 (:exit res)))
    (is (str/includes? (:err res) "palomar authorities index"))))

(deftest the-manifest-names-the-authority-index
  (let [a   (index! "2026-11-09")
        b   (index! "2026-11-10")
        ma  (tmp ".edn")
        mb  (tmp ".edn")
        run (fn [idx m] (cli/run ["reconcile" bib "--from" "intermarc" "--authorities" (str idx) "--manifest" (str m)]))]
    (is (zero? (:exit (run a ma))))
    (is (zero? (:exit (run b mb))))
    (let [x (read-manifest ma) y (read-manifest mb)
          [auth] (:authorities x)]
      (testing "the index, by its fingerprint, the dump it was built from and its build date"
        (is (= (str a) (:path auth)))
        (is (= (manifest/fingerprint a) (select-keys auth [:sha256 :bytes])))
        (is (= "2026-11-09" (get-in auth [:index :built])))
        (is (= (assoc (manifest/fingerprint (java.io.File. extract)) :name "gnd-extract.jsonl") (get-in auth [:index :source])))
        (is (= 197 (get-in auth [:index :entries]))))
      (testing "the input of the reconciliation is fingerprinted"
        (is (= (:sha256 (manifest/fingerprint (java.io.File. bib))) (get-in x [:inputs 0 :sha256]))))
      (testing "diff shows a changed index"
        (is (contains? (manifest/diff x y) :authorities))
        (is (empty? (dissoc (manifest/diff x y) :authorities)))))
    (testing "the same index, the same manifest but the times"
      (let [mc (tmp ".edn")]
        (run a mc)
        (is (empty? (manifest/diff (read-manifest ma) (read-manifest mc))))))))

(deftest an-empty-dump-makes-no-index
  (let [d (tmp ".jsonl") o (tmp ".edn")]
    (spit d "")
    (.delete o)
    (let [res (cli/run ["authorities" "index" (str d) "--out" (str o) "--built" "2026-11-09"])]
      (is (= 2 (:exit res)))
      (is (str/includes? (:err res) "no entry"))
      (is (not (.exists o))))))

(deftest a-dump-is-recognised-after-a-bom-or-blank-lines-or-by-its-compression
  (let [l (first (str/split-lines (slurp extract)))]
    (doseq [[suffix content] [[".txt" (str "\uFEFF\n\n  \n" l "\n")]
                              [".txt" (str "\r\n" l)]
                              [".bz2" "BZh91AY&SY"]
                              [".gz" "\u001f\u008b"]]]
      (let [f (tmp suffix)]
        (spit f content)
        (let [res (cli/run ["reconcile" bib "--from" "intermarc" "--authorities" (str f)])]
          (is (= 2 (:exit res)) suffix)
          (is (str/includes? (:err res) "palomar authorities index") suffix))))))

(ns palomar.authority-index-test
  "Authority snapshots (lot M6): a dump prepared once into a local index,
   deterministic, whose blocking keys give the matcher exactly the
   candidates a full scan gives."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.agent-match :as fs]
            [palomar.authority-index :as index]
            [palomar.manifest :as manifest]
            [palomar.plugins.gnd :as gnd]
            [palomar.reconcile :as reconcile]))

(def extract "test/fixtures/er-gold/gnd-persons/gnd-extract.jsonl")

(defn- tmp [suffix] (doto (java.io.File/createTempFile "palomar-index-" suffix) (.deleteOnExit)))

(defn- build [dump & [opts]]
  (let [out (tmp ".edn")]
    (index/build! dump out (merge {:built "2026-11-09"} opts))
    out))

(def ^:private built (delay (build extract)))
(def ^:private idx (delay (index/read-index @built)))

;; ---------------------------------------------------------------------------
;; The index file
;; ---------------------------------------------------------------------------

(deftest the-same-dump-gives-a-byte-identical-index
  (let [a (slurp @built)]
    (testing "built twice"
      (is (= a (slurp (build extract)))))
    (testing "built through an external sort of many small chunks (bounded memory)"
      (is (= a (slurp (build extract {:chunk-lines 7})))))
    (testing "a dump compressed with gzip gives the same entries and keys; its header names the bytes read"
      (let [gz (tmp ".jsonl.gz")]
        (with-open [o (java.util.zip.GZIPOutputStream. (io/output-stream gz))] (io/copy (io/file extract) o))
        (let [b (slurp (build gz))]
          (is (= (rest (str/split-lines a)) (rest (str/split-lines b))))
          (is (= (:sha256 (manifest/fingerprint gz)) (get-in (index/read-header (io/file (build gz))) [:source :sha256]))))))
    (testing "the same records in another order give the same body"
      (let [shuffled (tmp ".jsonl")]
        (spit shuffled (str (str/join "\n" (reverse (str/split-lines (slurp extract)))) "\n"))
        (is (= (rest (str/split-lines a)) (rest (str/split-lines (slurp (build shuffled))))))))
    (testing "another build date changes the header alone"
      (let [c (slurp (build extract {:built "2026-11-10"}))]
        (is (not= a c))
        (is (= (rest (str/split-lines a)) (rest (str/split-lines c))))))))

(deftest the-header-names-the-dump-and-the-build
  (let [h (:header @idx)]
    (is (= 1 (:palomar/authority-index h)))
    (is (= "2026-11-09" (:built h)))
    (is (= :lobid-gnd-jsonl (:format h)))
    (is (= (assoc (manifest/fingerprint (io/file extract)) :name "gnd-extract.jsonl") (:source h)))
    (is (= 197 (:entries h)))
    (is (= index/blocking-scheme (:blocking h)))
    (is (= (count (:postings @idx)) (:keys h)))))

(deftest the-index-is-sorted-line-oriented-edn
  (let [ls    (str/split-lines (slurp @built))
        body  (rest ls)
        kinds (map #(second (re-find #"^\[(:\w+) " %)) body)]
    (is (str/starts-with? (first ls) "{:"))
    (is (= body (sort body)) "the body lines are in sorted order")
    (is (= (count body) (count (distinct body))))
    (is (every? #{":entry" ":key"} kinds))
    (is (= 197 (count (filter #{":entry"} kinds))))))

(deftest the-index-holds-the-dump-s-entries
  (is (= (sort-by :id (:entries (gnd/pool (slurp extract)))) (:entries @idx)))
  (testing "every entry is reached by each of its keys"
    (doseq [[ordinal e] (map-indexed vector (:entries @idx))
            k (index/entry-keys e)]
      (is (some #{ordinal} (get (:postings @idx) k)) (str (:id e) " " k)))))

(deftest an-index-is-built-explicitly-and-refuses-what-it-cannot-be-sure-of
  (testing "a dump holding one id twice is refused, nothing written"
    (let [dup (tmp ".jsonl") out (tmp ".edn")
          l   (first (str/split-lines (slurp extract)))]
      (spit dup (str l "\n" l "\n"))
      (.delete out)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"twice" (index/build! dup out {:built "2026-11-09"})))
      (is (not (.exists out)))))
  (testing "a build date is given, never read from a clock"
    (is (thrown? clojure.lang.ExceptionInfo (index/build! extract (tmp ".edn") {})))
    (is (thrown? clojure.lang.ExceptionInfo (index/build! extract (tmp ".edn") {:built "9 Nov"}))))
  (testing "a file that is no index is not read as one"
    (is (not (index/index-file? extract)))
    (is (index/index-file? @built))
    (is (thrown? clojure.lang.ExceptionInfo (index/read-index extract)))))

;; ---------------------------------------------------------------------------
;; Blocking
;; ---------------------------------------------------------------------------

(deftest blocking-keys-are-the-tokens-and-the-surname-s-deletions
  (is (= #{"t:dumas" "t:alexandre" "s:dumas" "s:umas" "s:dmas" "s:duas" "s:dums" "s:duma"}
         (set (index/blocking-keys ["Dumas, Alexandre"]))))
  (testing "a surname shorter than four letters has no typo keys (the matcher's typo needs four)"
    (is (= #{"t:li" "t:wei"} (set (index/blocking-keys ["Li, Wei"])))))
  (testing "a name with no letters has no key"
    (is (empty? (index/blocking-keys ["--" nil ""])))))

(def ^:private model
  "A hand-set model in which every level weighs, so that a candidate missed
   by the blocking would change the decisions."
  (fs/model {:prior 0.1
             :m {:name (zipmap fs/name-levels [0.4 0.12 0.1 0.08 0.08 0.08 0.04 0.04 0.03 0.03])
                 :form {:heading 0.8 :variant 0.2}
                 :dates {:agree 0.6 :compatible 0.3 :unknown 0.1}}
             :u {:name (zipmap fs/name-levels [0.001 0.002 0.002 0.005 0.01 0.02 0.05 0.1 0.2 0.61])
                 :form {:heading 0.9 :variant 0.1}
                 :dates {:agree 0.05 :compatible 0.15 :unknown 0.8}}}))

(defn- perturb
  "Mentions made from an entry's heading: as written, initials, the surname
   alone, reversed, a typo in the surname, the surname's first letter
   dropped."
  [{:keys [label dates]}]
  (let [[sur fore] (map str/trim (str/split label #"," 2))
        swap       (fn [s] (if (< 3 (count s)) (str (subs s 0 1) (subs s 2 3) (subs s 1 2) (subs s 3)) s))]
    (for [nm (distinct (remove str/blank? [label
                                           (when fore (str sur ", " (str/join " " (map #(str (first %) ".") (str/split fore #"[ -]")))))
                                           sur
                                           (when fore (str fore " " sur))
                                           (str (swap sur) (when fore (str ", " fore)))
                                           (str (subs sur (min 1 (count sur))) (when fore (str ", " fore)))]))]
      {:name nm :dates dates})))

(defn- mentions []
  (concat (mapcat perturb (take-nth 3 (:entries @idx)))
          [{:name "Hugo, V."} {:name "Victor Hugo"} {:name "Dumas"} {:name "Verne, Jules" :dates "1828-1905"}
           {:name "Nobody, Atall"} {:name ""} {:name "Ümlaut, Ögé"}]))

(deftest blocking-gives-the-proposals-of-a-full-scan
  (let [es    (:entries @idx)
        block (index/block @idx)
        ms    (mentions)]
    (testing "with a Fellegi-Sunter model: proposals, rejections and abstentions alike"
      (let [full (reconcile/agent-candidates ms es {:model model})
            fast (reconcile/agent-candidates ms es {:model model :block block})]
        (is (seq (:proposals full)))
        (is (seq (:abstained full)))
        (is (= full fast))))
    (testing "name-only, at the default and at a low threshold"
      (is (= (reconcile/agent-candidates ms es) (reconcile/agent-candidates ms es {:block block})))
      (is (= (reconcile/agent-candidates ms es {:threshold 0.1}) (reconcile/agent-candidates ms es {:threshold 0.1 :block block}))))
    (testing "propose-agent-links too"
      (is (= (reconcile/propose-agent-links (map :name ms) es) (reconcile/propose-agent-links (map :name ms) es {:block block}))))
    (testing "the block is smaller than the pool"
      (is (< (count (block "Dumas, Alexandre")) (count es))))))

(deftest a-zero-threshold-scans-the-whole-pool
  (testing "at threshold 0 every entry is a candidate, which no key can block: the block is not used"
    (let [es (:entries @idx)]
      (is (= (reconcile/agent-candidates ["Nobody"] es {:threshold 0.0})
             (reconcile/agent-candidates ["Nobody"] es {:threshold 0.0 :block (index/block @idx)}))))))

;; ---------------------------------------------------------------------------
;; Review of M6: what an index refuses, bounded merging, locale, line endings
;; ---------------------------------------------------------------------------

(defn- body [f] (rest (str/split-lines (slurp f))))

(deftest an-index-of-nothing-is-refused
  (testing "a dump from which no entry is read"
    (let [empty-dump (tmp ".jsonl") out (tmp ".edn")]
      (spit empty-dump "\n\n")
      (.delete out)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no entry" (index/build! empty-dump out {:built "2026-11-09"})))
      (is (not (.exists out)))))
  (testing "a dump most of whose lines are no JSON object: the counts are said, nothing is written"
    (let [junk (tmp ".jsonl") out (tmp ".edn")]
      (spit junk (str (first (str/split-lines (slurp extract))) "\nnot json\n<xml/>\n[1]\n"))
      (.delete out)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"3 of 4 lines" (index/build! junk out {:built "2026-11-09"})))
      (is (not (.exists out))))))

(deftest the-dnb-s-expanded-json-ld-is-not-read
  (testing "the DNB's bulk JSON-LD (an expanded array, its first lines as in authorities-gnd-person_lds.jsonld.gz) is refused, by name"
    (let [dnb (tmp ".jsonld") out (tmp ".edn")]
      (spit dnb (str "[ [ {\n"
                     "  \"@id\" : \"_:node1k0rmm5k0x343944578\",\n"
                     "  \"https://d-nb.info/standards/elementset/gnd#nameAddition\" : [ {\n"
                     "    \"@value\" : \"a Sancto Bartholomaeo\"\n"
                     "  } ],\n"
                     "  \"https://d-nb.info/standards/elementset/gnd#personalName\" : [ {\n"
                     "    \"@value\" : \"Paulus\"\n"
                     "  } ]\n"
                     "} ] ]\n"))
      (.delete out)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"expanded JSON-LD array" (index/build! dnb out {:built "2026-11-09"})))
      (is (not (.exists out))))))

(deftest an-undifferentiated-person-is-no-entry-and-is-counted
  (let [dump (tmp ".jsonl")
        l    (first (str/split-lines (slurp extract)))]
    (spit dump (str l "\n"
                    "{\"id\":\"https://d-nb.info/gnd/1000000000\",\"type\":[\"UndifferentiatedPerson\",\"Person\",\"AuthorityResource\"],\"preferredName\":\"Müller, Hans\"}\n"))
    (let [h (index/read-header (build dump))]
      (is (= 1 (:entries h)))
      (is (= {:undifferentiated-person 1} (:skipped h))))))

(deftest a-merge-never-opens-more-runs-than-its-fan-in
  (let [open (atom 0) most (atom 0)
        real @#'index/open-run]
    (with-redefs [index/open-run (fn [f]
                                   (swap! most max (swap! open inc))
                                   (proxy [java.io.BufferedReader] [(real f)]
                                     (close [] (swap! open dec) (proxy-super close))))]
      (let [b (slurp (build extract {:chunk-lines 1 :fan-in 3}))]
        (is (= (slurp @built) b) "several passes give the bytes of one")))
    (is (<= 1 @most 3))
    (is (zero? @open) "every run is closed")))

(deftest the-index-does-not-depend-on-the-default-locale
  (let [old (java.util.Locale/getDefault)
        names ["IBSEN, Ingrid" "İnönü, İsmet" "Işık, Ilhan"]
        here  [(slurp @built) (map index/blocking-keys (map vector names))]
        there (try (java.util.Locale/setDefault (java.util.Locale/forLanguageTag "tr-TR"))
                   [(slurp (build extract)) (map index/blocking-keys (map vector names))]
                   (finally (java.util.Locale/setDefault old)))]
    (is (= (first here) (first there)))
    (is (= (second here) (second there)))
    (testing "a dotted capital I folds to a plain i"
      (is (some #{"t:inonu"} (index/blocking-keys ["İnönü, İsmet"]))))))

(deftest the-body-does-not-depend-on-line-endings-or-a-bom
  (let [a     (body @built)
        lines (str/split-lines (slurp extract))
        crlf  (tmp ".jsonl")
        bom   (tmp ".jsonl")]
    (spit crlf (str (str/join "\r\n" lines) "\r\n"))
    (spit bom (str "\uFEFF" (str/join "\n" lines) "\n"))
    (is (= a (body (build crlf))))
    (is (= a (body (build bom))))))

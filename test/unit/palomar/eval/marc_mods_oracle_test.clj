(ns palomar.eval.marc-mods-oracle-test
  "Convergence eval via the LoC MARC→MODS oracle (DoD #1 / the hub-and-spoke thesis).
   Not a differential of element coverage (that is the DC oracle); this asks the
   convergence question directly: *the same MARC record, routed two independent
   ways into the canonical hub, should land in the same place on the documentary
   spine.*

   - **Path A** — MARC21 → Palomar's MARC21 importer → canonical floor.
   - **Path B** — MARC21 → the Library of Congress' own `MARC21slim2MODS3-1.xsl`
     (run as an oracle, JDK XSLT 1.0) → real MODS → Palomar's MODS importer →
     canonical floor.

   Path B is the strong test: the intermediary is a *third party's* standard
   (authoritative MODS, not our hand-written fixtures), and it exercises the MODS
   importer against `nonSort`/`title` splits, typed `namePart`s, a `modsCollection`
   wrapper, and dual `dateIssued` — shapes our fixtures only approximated.

   ## What converges (the spine survives the detour)

   - **title — exactly, both records.** This is non-trivial: MODS splits the article
     into `<nonSort>The </nonSort><title>Great Ray Charles</title>`, and the importer's
     nonSort restoration reconstructs precisely the inline MARC 245 \"The Great Ray
     Charles\". Without that restoration the two routes would *not* converge.
   - **the bibliographic identifier (LCCN)** and **the transcribed date** appear in
     both routes, both records.
   - **digital objects** (the 856 access URLs) converge exactly for the web record.

   ## What diverges (MODS carries more, and flattens differently) — asserted, not hidden

   - **agent.** Path B folds the date `namePart` into the name (\"Charles, Ray 1930-\")
     *and* promotes `originInfo/publisher` to a second agent (\"Atlantic\"); Path A
     keeps only the 700 $a name. Two defensible flattenings of one MARC record.
   - **date.** Path B also carries the MARC-*coded* normalised form (\"1957\";
     \"1994\"/\"9999\") that the LoC stylesheet emits as a second `dateIssued`; Path A
     has only the transcribed 260 $c.
   - **identifier.** Path B adds the 028 issue number (\"1259 Atlantic\", MODS
     `type=\"issue number\"`) and the 856 URLs (MODS `type=\"uri\"`); Path A keys on the
     control numbers (035) and routes 856 $u to a digital object, not an identifier.
   - **note.** Path A keeps the 505 track listing; the LoC stylesheet routes it to
     `<tableOfContents>`, which the MODS floor does not map (ADR 0003) → dropped in B.

   Honest framing: this is convergence on the *documentary spine through an external
   standard*, **not** round-trip equality — the two importers have different scopes
   and MODS is the richer interchange format. The eval asserts both the convergence
   and the divergences, on real LoC output."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.eval.loc-xslt :as loc-xslt]
            [palomar.plugins :as plug]
            [palomar.plugins.mapping :as mapping]
            [palomar.plugins.marc21 :as marc21]
            [palomar.plugins.mods :as mods]
            [palomar.runtime :as runtime]))

(def ^:private marc-source
  (slurp "test/fixtures/documentary/marc21/marcxml/loc_collection.xml"))

(defn- canon-floor
  "Run `plugin`'s importer output through `:normalize`, then return, per record, a
   map `{canonical-predicate #{string values…}}` of the record's own canonical
   assertions — the documentary floor each route lands on."
  [plugin importer-out]
  (let [reg      (plug/register plug/empty-registry plugin)
        compiled (mapping/compile-mappings (plug/all-mappings reg)
                                           (plug/effective-transforms reg))]
    (mapv (fn [rec]
            (let [r   (:record (runtime/run-phase rec compiled :normalize))
                  rid (:id r)]
              (->> (:assertions r)
                   (filter #(and (= rid (:subject %))
                                 (= "canon" (namespace (:predicate %)))
                                 (string? (:value %))))
                   (reduce (fn [m a] (update m (:predicate a) (fnil conj #{}) (:value a))) {}))))
          (:records importer-out))))

(def ^:private path-a (delay (canon-floor marc21/plugin (marc21/importer {} marc-source))))
(def ^:private mods-oracle (delay (loc-xslt/run-stylesheet "loc-MARC21slim2MODS3-1.xsl" marc-source)))
(def ^:private path-b (delay (canon-floor mods/plugin (mods/importer {} @mods-oracle))))

(defn- a [i k] (get (nth @path-a i) k))
(defn- b [i k] (get (nth @path-b i) k))

(deftest the-loc-mods-oracle-runs-and-the-importer-ingests-real-mods
  (testing "the LoC MARC→MODS stylesheet produces a 2-record modsCollection"
    (is (str/includes? @mods-oracle "<modsCollection"))
    (is (= 2 (count (re-seq #"<mods\b" @mods-oracle)))))
  (testing "Palomar's MODS importer ingests the real LoC MODS — both records, spine populated"
    (is (= 2 (count @path-b)))
    (doseq [i [0 1]]
      (is (seq (b i :canon/title)) (str "record " i " has a title"))
      (is (seq (b i :canon/agent)) (str "record " i " has an agent"))
      (is (seq (b i :canon/date))  (str "record " i " has a date")))))

(deftest the-documentary-spine-converges-through-mods
  (testing "title converges EXACTLY through the MODS detour (depends on nonSort restoration)"
    (is (= (a 0 :canon/title) (b 0 :canon/title) #{"The Great Ray Charles"}))
    (is (= (a 1 :canon/title) (b 1 :canon/title) #{"The White House"})))
  (testing "the bibliographic LCCN survives both routes, both records"
    (is (contains? (a 0 :canon/identifier) "91758335"))
    (is (contains? (b 0 :canon/identifier) "91758335"))
    (is (contains? (a 1 :canon/identifier) "00530046"))
    (is (contains? (b 1 :canon/identifier) "00530046")))
  (testing "the transcribed date survives both routes"
    (is (contains? (a 0 :canon/date) "[1957?]"))
    (is (contains? (b 0 :canon/date) "[1957?]"))
    (is (contains? (a 1 :canon/date) "1994-"))
    (is (contains? (b 1 :canon/date) "1994-")))
  (testing "the digital objects (856 access URLs) converge exactly for the web record"
    (is (= (a 1 :canon/digital-object) (b 1 :canon/digital-object)
           #{"http://www.whitehouse.gov" "http://lcweb.loc.gov/staff/wpp/whitehouse.html"}))))

(deftest mods-carries-more-and-flattens-differently
  (testing "agent: MODS promotes the publisher to an agent (and folds the date namePart); MARC keeps only the 700 name"
    (is (contains? (b 0 :canon/agent) "Atlantic"))            ; originInfo/publisher -> agent
    (is (not (contains? (a 0 :canon/agent) "Atlantic")))
    (is (contains? (b 0 :canon/agent) "Charles, Ray 1930-"))  ; date namePart joined in
    (is (contains? (a 0 :canon/agent) "Charles, Ray,")))      ; MARC 700 $a, with its comma
  (testing "date: MODS also carries the MARC-coded normalised form that the direct route omits"
    (is (contains? (b 0 :canon/date) "1957"))
    (is (not (contains? (a 0 :canon/date) "1957"))))
  (testing "identifier: MODS adds the 028 issue number; the direct route does not treat 028 as an identifier"
    (is (contains? (b 0 :canon/identifier) "1259 Atlantic"))
    (is (not (contains? (a 0 :canon/identifier) "1259 Atlantic"))))
  (testing "note: the direct route keeps the 505 track listing; MODS routes it to tableOfContents (unmapped) -> dropped in B"
    (is (some #(str/includes? % "My melancholy baby") (a 0 :canon/note)))
    (is (not-any? #(str/includes? % "My melancholy baby") (b 0 :canon/note)))))

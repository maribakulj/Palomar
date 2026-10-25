(ns palomar.plugins.marc21.export-test
  "Round-trip tests (WP-4): MARC21 → canonical floor → MARC21. The real LoC Ray
   Charles record imports, normalises to the floor, and exports back to MARCXML;
   the test pins down what the *pivot* preserves (the bibliographic subset) and
   what it costs (20 native fields the floor never carried + 4 many-to-one
   collapses, ADR 0015) — then proves the trip is id-stable and idempotent."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.loss-report :as lr]
            [palomar.plugins :as plug]
            [palomar.plugins.mapping :as mapping]
            [palomar.plugins.marc21 :as marc21]
            [palomar.plugins.lrmoo.project :as project]
            [palomar.plugins.lrmoo.view :as view]
            [palomar.plugins.marc21.export :as mex]
            [palomar.runtime :as runtime]))

(def ^:private fixture
  (slurp "test/fixtures/documentary/marc21/marcxml/loc_collection.xml"))

(defn- compiled []
  (let [reg (plug/register plug/empty-registry marc21/plugin)]
    (mapping/compile-mappings (plug/all-mappings reg) (plug/effective-transforms reg))))

(defn- ->canonical
  "Import MARCXML `xml`, return the normalised floor record whose id matches `id`
   (or the first record when `id` is nil)."
  [xml id]
  (let [recs (marc21/ingest xml {})
        rec  (if id (first (filter #(= id (:id %)) recs)) (first recs))]
    (:record (runtime/run-phase rec (compiled) :normalize))))

(defn- floor-literals [record pred]
  (->> (:assertions record)
       (filter #(and (= pred (:predicate %)) (string? (:value %))))
       (map :value)
       set))

(deftest reconstructs-the-floor-subset-as-marcxml
  (testing "the pivot rebuilds 001 + the mapped bibliographic fields"
    (let [out (mex/->marcxml (->canonical fixture :marc/r5637241))]
      (is (str/includes? out "xmlns=\"http://www.loc.gov/MARC21/slim\""))
      (is (str/includes? out "<controlfield tag=\"001\">5637241</controlfield>"))  ; from :source
      (is (str/includes? out "tag=\"245\""))
      (is (str/includes? out "The Great Ray Charles"))
      (is (str/includes? out "tag=\"700\""))
      (is (str/includes? out "Charles, Ray,"))
      (is (str/includes? out "tag=\"260\""))
      (is (str/includes? out "[1957?]"))
      (is (str/includes? out "tag=\"010\""))
      (testing "the 3 notes (500/505/511) all come back as 500 (note type collapsed)"
        (is (= 3 (count (re-seq #"tag=\"500\"" out)))))
      (testing "& is XML-escaped"
        (is (str/includes? out "piano &amp; celeste"))))))

(deftest fields-off-the-floor-do-not-survive-the-trip
  (testing "subjects (650) and physical description (300) are gone — the floor never carried them"
    (let [out (mex/->marcxml (->canonical fixture :marc/r5637241))]
      (is (not (str/includes? out "tag=\"650\"")))
      (is (not (str/includes? out "tag=\"300\"")))
      (is (not (str/includes? out "tag=\"040\""))))))

(deftest the-round-trip-is-id-stable-and-idempotent
  (testing "MARC21 -> floor -> MARC21 -> floor -> MARC21: 001 stable, output fixes"
    (let [c1   (->canonical fixture :marc/r5637241)
          out1 (mex/->marcxml c1)
          c2   (->canonical out1 nil)
          out2 (mex/->marcxml c2)]
      (is (= :marc/r5637241 (:id c2)))                ; 001 round-trips the id
      (is (= out1 out2))                              ; stable after the first pass
      (testing "the surviving floor values are unchanged"
        (is (= #{"The Great Ray Charles"} (floor-literals c2 :canon/title)))
        (is (= #{"Charles, Ray,"} (floor-literals c2 :canon/agent)))
        (is (= #{"91758335"} (floor-literals c2 :canon/identifier)))))))

(deftest pivot-dropped-fields-excludes-the-reconstructed-001
  (testing "001 round-trips via :source, so it is not a floor-coverage drop"
    (let [canon (->canonical fixture :marc/r5637241)]
      (is (not (contains? (set (mex/pivot-dropped-fields canon)) :marc21/f001)))
      (is (contains? (set (mex/pivot-dropped-fields canon)) :marc21/f650_a))   ; subject
      (is (contains? (set (mex/pivot-dropped-fields canon)) :marc21/f300_a))))) ; physical

(deftest the-round-trip-loss-report-quantifies-the-whole-gap
  (testing "20 floor-coverage drops (import) + 4 collapses (export) = the trip's loss"
    (let [canon  (->canonical fixture :marc/r5637241)
          report (lr/conversion-report (mex/export-losses canon) {:records 1})]
      (is (= 20 (get-in report [:by-edge :import :total])))   ; the leader is now captured, hence reported
      (is (= 4 (get-in report [:by-edge :export :total])))
      (is (= 20 (get-in report [:by-category :dropped])))
      (is (contains? (set (mex/pivot-dropped-fields canon)) :marc21/leader))
      (is (= 4 (get-in report [:by-category :under-specified])))
      (testing "the collapses name the multi-source canonical predicates"
        (is (= #{:canon/agent :canon/date :canon/identifier :canon/note}
               mex/collapsed-predicates)))
      (testing "renders for an institutional audit"
        (let [text (lr/format-conversion-report report)]
          (is (str/includes? text "import edge"))
          (is (str/includes? text "export edge")))))))

(def ^:private main-and-added-entry
  (str "<collection xmlns=\"http://www.loc.gov/MARC21/slim\"><record>"
       "<controlfield tag=\"001\">42</controlfield>"
       "<datafield tag=\"100\" ind1=\"1\" ind2=\" \"><subfield code=\"a\">Hugo, Victor</subfield></datafield>"
       "<datafield tag=\"245\" ind1=\"1\" ind2=\"0\"><subfield code=\"a\">Notre-Dame de Paris</subfield></datafield>"
       "<datafield tag=\"700\" ind1=\"1\" ind2=\" \"><subfield code=\"a\">Doré, Gustave</subfield></datafield>"
       "</record></collection>"))

(deftest agent-roles-survive-the-marc-round-trip
  (testing "the creator comes back as the 100 main entry, the contributor as a 700"
    (let [c1  (->canonical main-and-added-entry nil)
          out (mex/->marcxml c1)
          c2  (->canonical out nil)]
      (is (re-find #"tag=\"100\"[^>]*><subfield code=\"a\">Hugo, Victor<" out))
      (is (re-find #"tag=\"700\"[^>]*><subfield code=\"a\">Doré, Gustave<" out))
      (testing "so the Work keyed on the creator is the same after the trip"
        (let [w1 (view/works (project/project c1))
              w2 (view/works (project/project c2))]
          (is (= 1 (count w1)))
          (is (= (map :id w1) (map :id w2)))))))
  (testing "a publisher (from another spoke) is written as 260 $b, not as an added entry"
    (let [rid :marc/p
          fid (keyword "frag" "marc.p.dc-publisher.0")
          rec {:id rid :kind :document
               :fragments  [{:id fid :source [:test]}]
               :assertions [{:subject rid :predicate :canon/title :value "X"}
                            {:subject rid :predicate :canon/agent :value {:value/kind :reference :value/target fid}}
                            {:subject fid :predicate :canon/agent :value "Gallimard"}
                            {:subject fid :predicate :canon/role :value :publisher}]}
          out (mex/->marcxml rec)]
      (is (re-find #"tag=\"260\"[^>]*><subfield code=\"b\">Gallimard<" out))
      (is (not (str/includes? out "tag=\"100\"")))
      (is (not (str/includes? out "tag=\"700\""))))))

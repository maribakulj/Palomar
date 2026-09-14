(ns palomar.plugins.marc21.export-test
  "Round-trip tests (WP-4): MARC21 → canonical floor → MARC21. The real LoC Ray
   Charles record imports, normalises to the floor, and exports back to MARCXML;
   the test pins down what the *pivot* preserves (the bibliographic subset) and
   what it costs (19 native fields the floor never carried + 4 many-to-one
   collapses, ADR 0015) — then proves the trip is id-stable and idempotent."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.loss-report :as lr]
            [palomar.plugins :as plug]
            [palomar.plugins.mapping :as mapping]
            [palomar.plugins.marc21 :as marc21]
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
  (testing "19 floor-coverage drops (import) + 4 collapses (export) = the trip's loss"
    (let [canon  (->canonical fixture :marc/r5637241)
          report (lr/conversion-report (mex/export-losses canon) {:records 1})]
      (is (= 19 (get-in report [:by-edge :import :total])))
      (is (= 4 (get-in report [:by-edge :export :total])))
      (is (= 19 (get-in report [:by-category :dropped])))
      (is (= 4 (get-in report [:by-category :under-specified])))
      (testing "the collapses name the multi-source canonical predicates"
        (is (= #{:canon/agent :canon/date :canon/identifier :canon/note}
               mex/collapsed-predicates)))
      (testing "renders for an institutional audit"
        (let [text (lr/format-conversion-report report)]
          (is (str/includes? text "import edge"))
          (is (str/includes? text "export edge")))))))

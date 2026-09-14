(ns palomar.plugins.mods.export-test
  "Round-trip tests (WP-4): MODS → canonical floor → MODS, the third spoke
   round-trip and the first on a *nested* format. The LoC `Sound and fury` record
   imports, normalises to the floor, and exports back to MODS; the test pins what
   the pivot preserves (the bibliographic core) and what it costs (the subTitle the
   floor never carried + the name/publisher and abstract/note collapses, ADR 0015),
   then proves the trip is id-stable and idempotent."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.diagnostics :as dx]
            [palomar.model :as model]
            [palomar.plugins :as plug]
            [palomar.plugins.mapping :as mapping]
            [palomar.plugins.mods :as mods]
            [palomar.plugins.mods.export :as mex]
            [palomar.runtime :as runtime]))

(def ^:private fixture (slurp "test/fixtures/documentary/mods/loc_mods_book.xml"))

(defn- compiled []
  (let [reg (plug/register plug/empty-registry mods/plugin)]
    (mapping/compile-mappings (plug/all-mappings reg) (plug/effective-transforms reg))))

(defn- ->canonical
  "Import MODS `xml`, return the normalised floor record whose id matches `id`
   (or the first record when `id` is nil)."
  [xml id]
  (let [recs (mods/ingest xml {})
        rec  (if id (first (filter #(= id (:id %)) recs)) (first recs))]
    (:record (runtime/run-phase rec (compiled) :normalize))))

(defn- floor-literals [record pred]
  (->> (:assertions record)
       (filter #(and (= pred (:predicate %)) (string? (:value %))))
       (map :value)
       set))

(deftest reconstructs-the-floor-core-as-mods
  (testing "the pivot rebuilds recordIdentifier + the nested bibliographic core"
    (let [out (mex/->mods-xml (->canonical fixture :mods/r11761548))]
      (is (str/includes? out "xmlns=\"http://www.loc.gov/mods/v3\""))
      (is (str/includes? out "<recordInfo><recordIdentifier>11761548</recordIdentifier></recordInfo>")) ; from :source
      (is (str/includes? out "<titleInfo><title>Sound and fury</title></titleInfo>"))
      (is (str/includes? out "<name><namePart>Alterman, Eric</namePart></name>"))
      (is (str/includes? out "<name><namePart>Cornell University Press</namePart></name>")) ; publisher -> name
      (is (str/includes? out "<originInfo><dateIssued>c1999</dateIssued></originInfo>"))
      (is (str/includes? out "<identifier>99042030</identifier>"))
      (testing "both notes come back as <note> (the note-type collapsed)"
        (is (= 2 (count (re-seq #"<note>" out))))))))

(deftest the-subtitle-does-not-survive-the-trip
  (testing "subTitle is captured natively but the floor has no home for it — gone on the round-trip"
    (let [out (mex/->mods-xml (->canonical fixture :mods/r11761548))]
      (is (not (str/includes? out "punditocracy")))
      (is (not (str/includes? out "subTitle"))))))

(deftest the-round-trip-is-id-stable-and-idempotent
  (testing "MODS -> floor -> MODS -> floor -> MODS: recordIdentifier stable, output fixes"
    (let [c1   (->canonical fixture :mods/r11761548)
          out1 (mex/->mods-xml c1)
          c2   (->canonical out1 nil)
          out2 (mex/->mods-xml c2)]
      (is (= :mods/r11761548 (:id c2)))                       ; recordIdentifier round-trips the id
      (is (= out1 out2))                                      ; stable after the first pass
      (testing "the surviving floor values are unchanged (sets preserved)"
        (is (= #{"Sound and fury"} (floor-literals c2 :canon/title)))
        (is (= #{"Alterman, Eric" "Cornell University Press"} (floor-literals c2 :canon/agent)))
        (is (= #{"c1999" "1999"} (floor-literals c2 :canon/date)))
        (is (contains? (floor-literals c2 :canon/identifier) "99042030"))
        (is (= 2 (count (floor-literals c2 :canon/note))))))))

(deftest loss-report-accounts-the-pivot-cost
  (testing "subTitle is a dropped native; agent and note are export-edge collapses"
    (let [canon  (->canonical fixture :mods/r11761548)
          losses (mex/export-losses canon)]
      (is (contains? (set (mex/pivot-dropped-fields canon)) :mods/subtitle))
      (is (some #(and (= :dropped (dx/loss-category %)) (= :mods/subtitle (dx/loss-source-field %))) losses))
      (testing "name/publisher -> :canon/agent and abstract/note -> :canon/note are the collapses"
        (is (= #{:canon/agent :canon/note} mex/collapsed-predicates))
        (is (some #(and (= :under-specified (dx/loss-category %))
                        (= :canon/agent (dx/loss-source-field %))) losses))
        (is (some #(and (= :under-specified (dx/loss-category %))
                        (= :canon/note (dx/loss-source-field %))) losses))))))

(deftest a-canonless-record-renders-nothing
  (testing "a record with no canonical content and no :source renders nil"
    (is (nil? (mex/->mods-xml (model/record {:id :mods/empty :kind :mods/record}))))))

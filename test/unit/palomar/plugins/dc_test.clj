(ns palomar.plugins.dc-test
  "Unit tests for the Dublin Core spoke (WP-4) on the *real* W3C DC fixture:
   ingest → canonical floor → WEMI projection → RDF, plus the loss-aware ingest
   that refuses to silently drop unmodelled DC elements (ADR 0003/0015)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.diagnostics :as dx]
            [palomar.plugins :as plug]
            [palomar.plugins.dc :as dc]
            [palomar.plugins.lrmoo.export :as export]
            [palomar.plugins.lrmoo.project :as project]
            [palomar.plugins.lrmoo.view :as view]
            [palomar.plugins.mapping :as mapping]
            [palomar.runtime :as runtime]))

(def ^:private fixture
  (slurp "test/fixtures/documentary/dublin-core/w3c_dc_example1.xml"))

(defn- ingest+normalize
  "Run the real DC plugin: ingest the fixture, then compile its mapping and run
   the `:normalize` phase — exactly the registry path a host would take."
  []
  (let [reg      (plug/register plug/empty-registry dc/plugin)
        compiled (mapping/compile-mappings (plug/all-mappings reg)
                                           (plug/effective-transforms reg))
        {:keys [records diagnostics]} (dc/importer {:record-id :dc/example1} fixture)
        canon    (:record (runtime/run-phase (first records) compiled :normalize))]
    {:canon canon :ingest-diagnostics diagnostics}))

(defn- literals
  "Set of string `:value`s for `pred` anywhere in the record (record- or
   fragment-level)."
  [record pred]
  (->> (:assertions record)
       (filter #(and (= pred (:predicate %)) (string? (:value %))))
       (map :value)
       set))

(deftest dc-maps-onto-the-canonical-floor
  (testing "the nine DCMES elements with a canonical home arrive, whitespace trimmed"
    (let [{:keys [canon]} (ingest+normalize)]
      (is (= #{"Dublin Core Tutorial"} (literals canon :canon/title)))   ; :trim fired
      (is (= #{"Alan Kelsey" "The Dublin Core Task Force" "Alan Kelsey, Ltd."}
             (literals canon :canon/agent)))                              ; creator+contributor+publisher
      (is (= #{"2007-01-06T00:00:00.00"} (literals canon :canon/date)))
      (is (= #{"http://example.org/DC_example1.jpg"} (literals canon :canon/identifier)))
      (is (= #{"TutorialOnline.info" "Dublin Core metadata image"}
             (literals canon :canon/relation)))                          ; relation + source
      (is (contains? (literals canon :canon/note)
                     "Learning Advanced Web Design can be fun and easy! Learn how to design web pages with proper tags, styles, and scripting.")))))

(deftest the-xml-lang-qualifier-rides-onto-a-fragment
  (testing "dc:title's xml:lang becomes :canon/lang on the title fragment (ADR 0011)"
    (let [{:keys [canon]} (ingest+normalize)]
      (is (contains? (literals canon :canon/lang) "en")))))

(deftest unmodelled-dc-elements-are-reported-not-silently-dropped
  (testing "the six DCMES elements with no canonical home become :import :dropped loss"
    (let [{:keys [ingest-diagnostics]} (ingest+normalize)
          ls (dx/losses ingest-diagnostics)]
      (is (= 6 (count ls)))
      (is (every? #(= :loss/dropped (:code %)) ls))
      (is (every? #(= :import (get-in % [:detail :loss/edge])) ls))
      (is (= #{:dc/subject :dc/type :dc/format :dc/language :dc/coverage :dc/rights}
             (set (map #(get-in % [:detail :loss/source-field]) ls))))
      (testing "and the mapped elements are NOT reported as loss"
        (is (not-any? #(contains? #{:dc/title :dc/creator :dc/date}
                                  (get-in % [:detail :loss/source-field])) ls))))))

(deftest dc-reaches-the-wemi-pivot-and-rdf
  (testing "the canonical floor projects to a full WEMI chain and serialises"
    (let [{:keys [canon]} (ingest+normalize)
          wemi (project/project canon)
          nt   (export/->ntriples wemi)]
      (is (= 1 (count (view/manifestations wemi))))
      (is (= 1 (count (view/expressions wemi))))
      (is (= 1 (count (view/works wemi))))
      (is (str/includes? nt "lrmoo/F1_Work"))
      (is (str/includes? nt "lrmoo/F3_Manifestation"))
      (is (str/includes? nt "Dublin Core Tutorial")))))

(deftest canonical-fields-outside-wemi-are-projection-loss
  (testing "identifier/date/relation/note are on the floor but not in WEMI -> :import :dropped"
    (let [{:keys [canon]} (ingest+normalize)
          wemi   (project/project canon)
          fields (set (map #(get-in % [:detail :loss/source-field])
                           (dx/losses (:diagnostics wemi))))]
      (is (contains? fields :canon/identifier))
      (is (contains? fields :canon/date))
      (is (contains? fields :canon/relation))
      (is (contains? fields :canon/note))
      (testing "the WEMI-bearing canonical fields are not dropped"
        (is (not (contains? fields :canon/title)))
        (is (not (contains? fields :canon/agent)))))))

(deftest the-plugin-conforms-and-its-mapping-compiles
  (testing "DC is a well-formed ADR 0007 plugin whose mapping compiles cleanly"
    (is (= :palomar/dublin-core (:id dc/plugin)))
    (is (fn? (:importer dc/plugin)))
    (is (= 9 (count (:mapping dc/plugin))))
    (is (= #{:dc/subject :dc/type :dc/format :dc/language :dc/coverage :dc/rights}
           dc/unmodelled-elements))
    (let [reg (plug/register plug/empty-registry dc/plugin)]
      (is (some? (mapping/compile-mappings (plug/all-mappings reg)
                                           (plug/effective-transforms reg)))))))

(deftest importer-requires-a-namespaced-record-id
  (testing "missing/bare :record-id throws a clear error, not the opaque mint-fragment-id crash"
    (let [src "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:title>X</dc:title></metadata>"]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires a namespaced-keyword :record-id"
                            (dc/importer {} src)))                       ; nil record-id
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires a namespaced-keyword :record-id"
                            (dc/importer {:record-id :bare} src))))))    ; un-namespaced keyword

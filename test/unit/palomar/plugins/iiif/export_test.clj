(ns palomar.plugins.iiif.export-test
  "Round-trip tests (WP-4): IIIF Presentation 3.0 → canonical floor → IIIF, the
   fifth and final spoke round-trip (and the first on JSON). The cookbook
   single-image manifest imports, normalises to the floor, and exports back; the
   test pins what the pivot preserves (label/id/image — the digitised-surrogate
   core) and proves the trip is id-stable and idempotent, then checks the
   floor-coverage loss on a richer synthetic record (agent/date have no IIIF home)."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.diagnostics :as dx]
            [palomar.model :as model]
            [palomar.plugins :as plug]
            [palomar.plugins.iiif :as iiif]
            [palomar.plugins.iiif.export :as iex]
            [palomar.plugins.mapping :as mapping]
            [palomar.runtime :as runtime]))

(def ^:private fixture (slurp "test/fixtures/documentary/iiif/manifest_image_simple.json"))

(defn- compiled []
  (let [reg (plug/register plug/empty-registry iiif/plugin)]
    (mapping/compile-mappings (plug/all-mappings reg) (plug/effective-transforms reg))))

(defn- ->canonical
  "Import IIIF `json`, return the normalised floor record (the single manifest)."
  [json]
  (:record (runtime/run-phase (first (iiif/ingest json {})) (compiled) :normalize)))

(defn- floor-literals [record pred]
  (->> (:assertions record)
       (filter #(and (= pred (:predicate %)) (string? (:value %))))
       (map :value)
       set))

(deftest reconstructs-the-surrogate-core-as-a-manifest
  (testing "the pivot rebuilds a Presentation 3.0 manifest: id, label, the painted Image"
    (let [out (iex/->json (->canonical fixture))
          m   (json/read-str out)]
      (is (= "http://iiif.io/api/presentation/3/context.json" (get m "@context")))
      (is (= "Manifest" (get m "type")))
      (is (= "https://iiif.io/api/cookbook/recipe/0001-mvm-image/manifest.json" (get m "id"))) ; from :source
      (is (= ["Single Image Example"] (get-in m ["label" "en"])))   ; the language survives
      (testing "the Image body id is painted onto a canvas (read back by the importer)"
        (is (str/includes? out "http://iiif.io/api/presentation/2.1/example/fixtures/resources/page1-full.png"))
        (is (str/includes? out "\"type\":\"Image\"")))
      (testing "the Canvas has the extent it MUST have (kept from the source canvas)"
        (let [c (first (get m "items"))]
          (is (= 1800 (get c "height")))
          (is (= 1200 (get c "width"))))))))

(deftest the-round-trip-is-id-stable-and-idempotent
  (testing "IIIF -> floor -> IIIF -> floor -> IIIF: manifest id stable, output fixes"
    (let [c1   (->canonical fixture)
          out1 (iex/->json c1)
          c2   (->canonical out1)
          out2 (iex/->json c2)]
      (is (= :iiif/m-0001-mvm-image (:id c2)))           ; the manifest id round-trips the record id
      (is (= out1 out2))                                 ; stable after the first pass
      (testing "the surviving floor values are unchanged"
        (is (= #{"Single Image Example"} (floor-literals c2 :canon/title)))
        (is (= #{"https://iiif.io/api/cookbook/recipe/0001-mvm-image/manifest.json"}
               (floor-literals c2 :canon/identifier)))
        (is (= #{"http://iiif.io/api/presentation/2.1/example/fixtures/resources/page1-full.png"}
               (floor-literals c2 :canon/digital-object)))))))

(defn- with-image
  "`assertions` plus one `:canon/digital-object` on a fragment carrying the extent
   `w`×`h` (the shape the IIIF importer gives an image), on record `rid`."
  [rid assertions url w h]
  (let [fid (model/mint-fragment-id rid [:iiif/image 0])]
    {:fragments  [(model/fragment {:id fid :source [:test]})]
     :assertions (into assertions
                       (cond-> [(model/assertion {:subject rid :predicate :canon/digital-object
                                                  :value (model/reference fid)})
                                (model/assertion {:subject fid :predicate :canon/digital-object :value url})]
                         w (conj (model/assertion {:subject fid :predicate :canon/width :value w}))
                         h (conj (model/assertion {:subject fid :predicate :canon/height :value h}))))}))

(deftest floor-predicates-iiif-cannot-express-are-dropped
  (testing "a record with an agent and a date: IIIF has no home for either -> :dropped"
    (let [rec    (model/record
                  (merge
                   {:id :iiif/rich :kind :iiif/manifest :source "https://ex/iiif/rich/manifest.json"}
                   (with-image :iiif/rich
                     [(model/assertion {:subject :iiif/rich :predicate :canon/title :value "T"})
                      (model/assertion {:subject :iiif/rich :predicate :canon/agent :value "Hugo, Victor"})
                      (model/assertion {:subject :iiif/rich :predicate :canon/date :value "1862"})
                      (model/assertion {:subject :iiif/rich :predicate :canon/note :value "n1"})
                      (model/assertion {:subject :iiif/rich :predicate :canon/note :value "n2"})]
                     "https://ex/img/1.jpg" 1000 800)))
          losses (iex/export-losses rec)
          dropped (set (map dx/loss-source-field (filter #(= :dropped (dx/loss-category %)) losses)))]
      (is (contains? dropped :canon/agent))
      (is (contains? dropped :canon/date))
      (testing "the title still renders (it has a IIIF home)"
        (is (str/includes? (iex/->json rec) "\"label\"")))
      (testing "notes collapse onto a display-only summary (:under-specified)"
        (is (some #(and (= :under-specified (dx/loss-category %))
                        (= :canon/note (dx/loss-source-field %))) losses))
        (testing "and on a round-trip every note survives, as summary strings"
          (is (= #{"n1" "n2"} (floor-literals (->canonical (iex/->json rec)) :canon/note))))))))

(deftest an-invalid-manifest-is-refused-with-a-diagnostic
  (testing "a manifest missing a MUST is not emitted; the loss names what is missing (audit test 13)"
    (doseq [[label rec missing]
            [["no label"
              (model/record (merge {:id :iiif/a :kind :iiif/manifest :source "https://ex/a/manifest.json"}
                                   (with-image :iiif/a [] "https://ex/a.jpg" 10 10)))
              ["label"]]
             ["no URI id (a MARC LCCN is not one)"
              (model/record (merge {:id :iiif/b :kind :iiif/manifest :source "91758335"}
                                   (with-image :iiif/b [(model/assertion {:subject :iiif/b :predicate :canon/title :value "T"})]
                                     "https://ex/b.jpg" 10 10)))
              ["id"]]
             ["an image with no canvas extent"
              (model/record (merge {:id :iiif/c :kind :iiif/manifest :source "https://ex/c/manifest.json"}
                                   (with-image :iiif/c [(model/assertion {:subject :iiif/c :predicate :canon/title :value "T"})]
                                     "https://ex/c.jpg" nil nil)))
              ["items"]]]]
      (testing label
        (is (= missing (iex/missing-required rec)))
        (is (nil? (iex/->json rec)))
        (let [w (first (filter #(= :iiif/manifest (dx/loss-source-field %)) (iex/export-losses rec)))]
          (is (= :warning (:severity w)))
          (is (str/includes? (:message w) (first missing))))))))

(deftest a-multilingual-label-keeps-every-language
  (testing "label/summary language maps survive the round-trip, every string with its language (audit test 11)"
    (let [src  (json/write-str
                {"@context" "http://iiif.io/api/presentation/3/context.json"
                 "id" "https://ex/ml/manifest.json" "type" "Manifest"
                 "label"   {"fr" ["Les Misérables"] "en" ["The Miserable Ones" "Les Mis"]}
                 "summary" {"none" ["1862"]}
                 "items" [{"id" "https://ex/ml/c1" "type" "Canvas" "height" 20 "width" 10
                           "items" [{"id" "https://ex/ml/p1" "type" "AnnotationPage"
                                     "items" [{"id" "https://ex/ml/a1" "type" "Annotation" "motivation" "painting"
                                               "target" "https://ex/ml/c1"
                                               "body" {"id" "https://ex/ml/1.jpg" "type" "Image"}}]}]}]})
          m    (json/read-str (iex/->json (->canonical src)))]
      (is (= {"fr" ["Les Misérables"] "en" ["The Miserable Ones" "Les Mis"]} (get m "label")))
      (is (= {"none" ["1862"]} (get m "summary"))))))

(deftest a-canonless-record-renders-nothing
  (testing "a record with no canonical content and no :source renders nil"
    (is (nil? (iex/->json (model/record {:id :iiif/empty :kind :iiif/manifest}))))))

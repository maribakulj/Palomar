(ns palomar.plugins.iiif-test
  "Unit tests for the IIIF Presentation 3.0 spoke (WP-4) on the real cookbook
   fixtures: nested JSON extraction of label/id/images onto the canonical floor,
   the creator-less WEMI shape (Manifestation + Expression, no Work), the
   report-at-ingest loss, and composition with the other spokes."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.diagnostics :as dx]
            [palomar.model :as model]
            [palomar.plugins :as plug]
            [palomar.plugins.dc :as dc]
            [palomar.plugins.iiif :as iiif]
            [palomar.plugins.lrmoo.export :as export]
            [palomar.plugins.lrmoo.project :as project]
            [palomar.plugins.lrmoo.view :as view]
            [palomar.plugins.mapping :as mapping]
            [palomar.plugins.marc21 :as marc21]
            [palomar.plugins.mods :as mods]
            [palomar.runtime :as runtime]))

(defn- fixture [name] (slurp (str "test/fixtures/documentary/iiif/" name ".json")))

(defn- ingest+normalize [name]
  (let [reg      (plug/register plug/empty-registry iiif/plugin)
        compiled (mapping/compile-mappings (plug/all-mappings reg)
                                           (plug/effective-transforms reg))
        {:keys [records diagnostics]} (iiif/importer {} (fixture name))]
    {:record (first records)
     :canon  (:record (runtime/run-phase (first records) compiled :normalize))
     :ingest diagnostics}))

(defn- literals [record pred]
  (->> (:assertions record)
       (filter #(and (= pred (:predicate %)) (string? (:value %))))
       (map :value)
       set))

(deftest image-manifest-maps-label-id-and-image-onto-the-floor
  (testing "a single-image manifest: label->title, id->identifier, body->digital-object"
    (let [{:keys [record canon]} (ingest+normalize "manifest_image_simple")]
      (is (= :iiif/m-0001-mvm-image-h207087643c (:id record)))                     ; slug of the manifest URI
      (is (= "https://iiif.io/api/cookbook/recipe/0001-mvm-image/manifest.json" (:source record)))
      (is (= #{"Single Image Example"} (literals canon :canon/title)))
      (is (= #{"https://iiif.io/api/cookbook/recipe/0001-mvm-image/manifest.json"}
             (literals canon :canon/identifier)))
      (is (= 1 (count (literals canon :canon/digital-object))))
      (is (str/includes? (first (literals canon :canon/digital-object)) "page1-full.png")))))

(deftest book-manifest-collects-every-canvas-image
  (testing "a multi-canvas book manifest yields one digital-object per painted image"
    (let [{:keys [canon ingest]} (ingest+normalize "manifest_book_simple")]
      (is (= #{"Simple Manifest - Book"} (literals canon :canon/title)))
      (is (= 5 (count (literals canon :canon/digital-object))))         ; five canvases
      (testing "the unmodelled top-level key and the per-canvas labels are reported, not silently dropped"
        (is (= #{:iiif/behavior :iiif/canvas-label}
               (set (map #(get-in % [:detail :loss/source-field]) (dx/losses ingest)))))))))

(deftest only-painted-images-are-digital-objects-and-the-rest-is-reported
  (testing "a thumbnail, a commenting annotation and a painted Text body are not images of the object (audit S14/S15)"
    (let [m  {"@context" "http://iiif.io/api/presentation/3/context.json"
              "id" "https://ex/t/manifest.json" "type" "Manifest" "label" {"en" ["T"]}
              "items" [{"id" "https://ex/t/c1" "type" "Canvas" "height" 20 "width" 10
                        "thumbnail" [{"id" "https://ex/t/thumb.jpg" "type" "Image"}]
                        "items" [{"id" "https://ex/t/p1" "type" "AnnotationPage"
                                  "items" [{"id" "https://ex/t/a1" "type" "Annotation" "motivation" "painting"
                                            "body" {"type" "Choice"
                                                    "items" [{"id" "https://ex/t/colour.jpg" "type" "Image"}
                                                             {"id" "https://ex/t/grey.jpg" "type" "Image"}]}}
                                           {"id" "https://ex/t/a2" "type" "Annotation" "motivation" "commenting"
                                            "body" {"id" "https://ex/t/note.jpg" "type" "Image"}}
                                           {"id" "https://ex/t/a3" "type" "Annotation" "motivation" "painting"
                                            "body" {"type" "TextualBody" "value" "OCR"}}]}]}]}
          {:keys [records diagnostics]} (iiif/importer {} (json/write-str m))
          rec    (first records)
          images (set (keep #(when (= :iiif/image (:predicate %)) (when (string? (:value %)) (:value %)))
                            (:assertions rec)))
          fields (set (map #(get-in % [:detail :loss/source-field]) (dx/losses diagnostics)))]
      (is (model/record-consistent? rec))
      (is (= #{"https://ex/t/colour.jpg" "https://ex/t/grey.jpg"} images))   ; both Choice alternatives
      (is (contains? fields :iiif/canvas-thumbnail))
      (is (contains? fields :iiif/annotation))
      (is (contains? fields :iiif/painting-body))
      (testing "each image keeps its canvas extent"
        (is (= #{10} (set (keep #(when (= :iiif/width (:predicate %)) (:value %)) (:assertions rec)))))
        (is (= #{20} (set (keep #(when (= :iiif/height (:predicate %)) (:value %)) (:assertions rec)))))))))

(deftest a-creatorless-manifest-projects-to-manifestation-and-expression-no-work
  (testing "IIIF carries a title but no creator -> Manifestation + Expression, no Work (the honest floor result)"
    (let [{:keys [canon]} (ingest+normalize "manifest_book_simple")
          wemi (project/project canon)
          nt   (export/->ntriples wemi)]
      (is (= 1 (count (view/manifestations wemi))))
      (is (= 1 (count (view/expressions wemi))))
      (is (= 0 (count (view/works wemi))))                             ; no agent -> no Work key
      (is (str/includes? nt "lrmoo/F3_Manifestation"))
      (is (str/includes? nt "Simple Manifest - Book")))))

(deftest the-corrupt-newspaper-fixture-is-a-saved-404-not-a-manifest
  (testing "manifest_newspaper_ocr.json is HTML (a saved 404), so it fails to parse as JSON"
    (is (thrown? Exception
                 (iiif/ingest (fixture "manifest_newspaper_ocr") {})))))

(deftest the-plugin-conforms-and-composes-with-the-other-spokes
  (testing "IIIF is a well-formed plugin; its mapping ids don't collide with the XML spokes"
    (is (= :palomar/iiif (:id iiif/plugin)))
    (is (= :json (:input-format iiif/plugin)))
    (is (= 6 (count (:mapping iiif/plugin))))
    (let [reg (reduce plug/register plug/empty-registry
                      [iiif/plugin dc/plugin marc21/plugin mods/plugin])]
      (is (some? (mapping/compile-mappings (plug/all-mappings reg)
                                           (plug/effective-transforms reg)))))))

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
            [palomar.convert :as convert]
            [palomar.plugins.agents :as agents]
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
      (is (= :iiif/m-0001-mvm-image-h207087643c (:id c2)))           ; the manifest id round-trips the record id
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
          (is (str/includes? (:message w) (first missing))))
        (testing "and nothing about a manifest that was not written: only the warning and the unexpressed predicates"
          (is (every? #(or (= :iiif/manifest (dx/loss-source-field %))
                           (re-find #"is not written by the IIIF export" (:message %)))
                      (iex/export-losses rec))))))))

(deftest the-manifest-id-is-the-first-http-uri
  (testing "a MARC control number in :source gives way to an HTTP(S) identifier (audit S11)"
    (let [rec (model/record (merge {:id :iiif/d :kind :iiif/manifest :source "91758335"}
                                   (with-image :iiif/d [(model/assertion {:subject :iiif/d :predicate :canon/title :value "T"})
                                                        (model/assertion {:subject :iiif/d :predicate :canon/identifier
                                                                          :value "https://ex/d/manifest.json"})]
                                     "https://ex/d.jpg" 10 10)))]
      (is (empty? (iex/missing-required rec)))
      (is (str/includes? (iex/->json rec) "\"id\":\"https://ex/d/manifest.json\"")))))

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

(def ^:private rights-fixture (slurp "test/fixtures/documentary/iiif/manifest_rights.json"))

(deftest rights-attribution-and-provider-round-trip
  (testing "IIIF -> floor -> IIIF keeps rights, requiredStatement, provider and a Canvas's rights (M1)"
    (let [c1   (->canonical rights-fixture)
          out1 (iex/->json c1)
          m    (json/read-str out1)
          out2 (iex/->json (->canonical out1))]
      (is (= "http://creativecommons.org/licenses/by/4.0/" (get m "rights")))
      (is (= {"label" {"en" ["Attribution"]} "value" {"en" ["Provided by the Example Herbarium"]}}
             (get m "requiredStatement")))
      (is (= [{"id" "https://example.org/about" "type" "Agent" "label" {"en" ["Example Herbarium"]}}]
             (get m "provider")))
      (testing "the attribution is not repeated in the summary"
        (is (nil? (get m "summary"))))
      (testing "the Canvas of the second image keeps its rights, the first has none"
        (is (= [nil "http://rightsstatements.org/vocab/InC/1.0/"] (mapv #(get % "rights") (get m "items")))))
      (testing "the trip is idempotent"
        (is (= out1 out2)))
      (testing "the provider's homepage was never on the floor: reported at import, not here"
        (is (not (str/includes? out1 "homepage")))))))

(deftest a-rights-text-is-no-iiif-rights
  (testing "IIIF rights MUST be a URI: a text statement is reported, not written (M1)"
    (let [rec (model/record
               (merge {:id :iiif/rt :kind :iiif/manifest :source "https://ex/rt/manifest.json"}
                      (with-image :iiif/rt
                        [(model/assertion {:subject :iiif/rt :predicate :canon/title :value "T"})
                         (model/assertion {:subject :iiif/rt :predicate :canon/rights :value "All rights reserved."})]
                        "https://ex/rt/1.jpg" 10 10)))
          m   (json/read-str (iex/->json rec))]
      (is (nil? (get m "rights")))
      (is (some #(and (= :canon/rights (dx/loss-source-field %)) (= :dropped (dx/loss-category %)))
                (iex/export-losses rec))))))

(defn- variant
  "A one-canvas manifest with `extra` top-level keys, `canvas-extra` Canvas
   keys and `body-extra` Image body keys."
  [extra canvas-extra body-extra]
  (json/write-str
   (merge {"@context" "http://iiif.io/api/presentation/3/context.json"
           "id" "https://ex/v/manifest.json" "type" "Manifest" "label" {"en" ["V"]}
           "items" [(merge {"id" "https://ex/v/c1" "type" "Canvas" "height" 20 "width" 10
                            "items" [{"id" "https://ex/v/p1" "type" "AnnotationPage"
                                      "items" [{"id" "https://ex/v/a1" "type" "Annotation" "motivation" "painting"
                                                "target" "https://ex/v/c1"
                                                "body" (merge {"id" "https://ex/v/1.jpg" "type" "Image"} body-extra)}]}]}
                           canvas-extra)]}
          extra)))

(deftest a-required-statement-comes-back-as-it-was
  (testing "label and value, each in its own language, are written back exactly — no joined string is split (M1 review)"
    (doseq [[label rs] [["the label's language is not the value's"
                         {"label" {"en" ["Attribution"]} "value" {"fr" ["Fourni par X"]}}]
                        ["a label holding \": \""
                         {"label" {"en" ["Credit: line"]} "value" {"en" ["X"]}}]
                        ["a label of no language"
                         {"label" {"none" ["Attribution"]} "value" {"en" ["X"]}}]]]
      (testing label
        (let [out (iex/->json (->canonical (variant {"requiredStatement" rs} {} {})))]
          (is (= rs (get (json/read-str out) "requiredStatement")))
          (is (= out (iex/->json (->canonical out))) "idempotent")))))
  (testing "a statement with no label is never written with an invented one: its value goes to the summary, reported"
    (let [rec (->canonical (variant {"requiredStatement" {"value" {"en" ["Provided by: X"]}}} {} {}))
          m   (json/read-str (iex/->json rec))]
      (is (nil? (get m "requiredStatement")))
      (is (= {"en" ["Provided by: X"]} (get m "summary")))
      (is (some #(= :canon/note-kind (dx/loss-source-field %)) (iex/export-losses rec))))))

(deftest a-body-s-rights-go-back-on-the-body
  (testing "the Image body's rights on the body, its Canvas's on the Canvas (M1 review)"
    (let [src (variant {} {"rights" "http://rightsstatements.org/vocab/InC/1.0/"}
                       {"rights" "http://creativecommons.org/publicdomain/zero/1.0/"})
          c   (first (get (json/read-str (iex/->json (->canonical src))) "items"))]
      (is (= "http://rightsstatements.org/vocab/InC/1.0/" (get c "rights")))
      (is (= "http://creativecommons.org/publicdomain/zero/1.0/" (get-in c ["items" 0 "items" 0 "body" "rights"]))))))

(deftest a-manifest-s-rights-reach-the-bibliographic-targets
  (testing "the manifest's rights are dc:rights, a MODS accessCondition, a MARC 540, each saying, or reporting,
            that they are the digital reproduction's (M1 review)"
    (let [run (fn [to] (convert/convert {:from :iiif :to to :source rights-fixture}))
          cc  "http://creativecommons.org/licenses/by/4.0/"]
      (is (str/includes? (:output (run :dc)) (str "<dc:rights>" cc "</dc:rights>")))
      (is (some #{:canon/rights-scope} (get-in (run :dc) [:loss :source-fields])) "DC cannot say the scope: reported")
      (is (str/includes? (:output (run :mods)) (str "<accessCondition type=\"use and reproduction\" displayLabel=\"digital reproduction\">" cc "</accessCondition>")))
      (is (str/includes? (:output (run :marc21)) (str "<datafield tag=\"540\" ind1=\" \" ind2=\" \"><subfield code=\"3\">digital reproduction</subfield><subfield code=\"u\">" cc "</subfield></datafield>")))
      (doseq [to [:dc :mods :marc21]]
        (testing (str to ": a Canvas's rights and the provider have no field there: reported")
          (is (some #{:canon/canvas-rights} (get-in (run to) [:loss :source-fields])))
          (is (some #{:canon/agent} (get-in (run to) [:loss :source-fields])))
          (is (not (str/includes? (:output (run to)) ">Example Herbarium<")) "the provider is not written")))
      (doseq [to [:mods :marc21]]
        (testing (str to ": the attribution is a general note, its kind reported")
          (is (some #{:canon/note-kind} (get-in (run to) [:loss :source-fields]))))))))

(deftest a-provider-never-becomes-an-agent-of-the-work
  (testing "after IIIF -> DC, MODS or MARC21 -> re-import, the provider is no creator, publisher or contributor,
            keys no Work and is no Linked Art creator (M1 review)"
    (let [recs (:records (convert/to-wemi :iiif {} rights-fixture))]
      (doseq [to [:dc :mods :marc21]]
        (testing to
          (let [out  (:output (convert/export-records to recs {}))
                back (:records (convert/to-wemi to {:record-id :x/back} out))]
            (is (seq back))
            (doseq [r back]
              (is (not-any? #(= "Example Herbarium" (:name %)) (agents/occurrences r))))
            (is (not (str/includes? (str (:output (convert/export-records :linked-art back {}))) "created_by")))))))))

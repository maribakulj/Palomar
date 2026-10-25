(ns palomar.plugins.record-id-test
  "Record ids stay readable but never collide (audit S20, test 14)."
  (:require [clojure.test :refer [deftest is testing]]
            [palomar.plugins.iiif :as iiif]
            [palomar.plugins.marc21 :as marc21]
            [palomar.plugins.record-id :as record-id]))

(deftest a-clean-identifier-keeps-its-readable-id
  (is (= :marc/r5637241 (record-id/from-identifier "marc" "r" "5637241")))
  (is (= :marc/r5637241 (record-id/from-identifier "marc" "r" " 5637241 "))))  ; whitespace is not significant

(deftest identifiers-that-slug-alike-stay-distinct
  (let [ids (map #(record-id/from-identifier "marc" "r" %) ["ocm 123" "ocm-123" "ocm.123" "ocm123"])]
    (is (= 4 (count (set ids))))
    (testing "an identifier that already is a slug keeps it; one that slugging changed gets a digest"
      (is (= [:marc/rocm-123 :marc/rocm123] [(nth ids 1) (nth ids 3)]))
      (is (every? #(re-matches #"rocm-123-h[0-9a-f]{10}" (name %)) [(nth ids 0) (nth ids 2)])))))

(defn- marc [cn]
  (str "<collection xmlns=\"http://www.loc.gov/MARC21/slim\"><record>"
       "<controlfield tag=\"001\">" cn "</controlfield>"
       "<datafield tag=\"245\" ind1=\"0\" ind2=\"0\"><subfield code=\"a\">T</subfield></datafield>"
       "</record></collection>"))

(deftest two-marc-control-numbers-with-divergent-punctuation-are-two-records
  (is (not= (:id (first (marc21/ingest (marc "CF 91000008") {})))
            (:id (first (marc21/ingest (marc "CF-91000008") {}))))))

(defn- manifest [uri]
  (str "{\"@context\":\"http://iiif.io/api/presentation/3/context.json\",\"id\":\"" uri "\","
       "\"type\":\"Manifest\",\"label\":{\"en\":[\"T\"]},\"items\":[]}"))

(deftest two-manifests-ending-in-the-same-segment-are-two-records
  (testing "same last path segment, different hosts"
    (let [a (:id (first (iiif/ingest (manifest "https://a.example/iiif/book1/manifest") {})))
          b (:id (first (iiif/ingest (manifest "https://b.example/iiif/book1/manifest") {})))]
      (is (not= a b))
      (is (every? #(re-matches #"m-book1-h[0-9a-f]{10}" (name %)) [a b])))))

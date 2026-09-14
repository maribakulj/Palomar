(ns palomar.plugins.shape-test
  "Unit tests for the XML shape adapter (Sprint 5 M5). Covers flat and
   qualified XML ingest (the text-content / attribute convention),
   mapping-driven filtering, fragment id stability and output
   consistency, plus `rewrite-tags` — the bridge from `clojure.data.xml`'s
   URI-encoded namespaces to clean prefixes."
  (:require [clojure.test :refer [deftest is testing]]
            [palomar.model :as model]
            [palomar.plugins.shape :as shape]))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(def title-flat-mapping
  [{:mapping/id   :map/dc-title
    :mapping/from :dc/title
    :mapping/to   :canon/title}])

(def opts
  {:record-id :record/r1 :kind :test})

(defn- assertions-on
  "Pluck the assertions of `record` whose subject equals `s`."
  [record s]
  (filterv #(= s (:subject %)) (:assertions record)))

;; ---------------------------------------------------------------------------
;; XML walker
;;
;; Tests construct already-aliased element maps directly (mirroring what a
;; caller would obtain after `clojure.data.xml/alias-uri` + `parse-str`),
;; so we exercise the walker without depending on the parser's URI
;; encoding behaviour. The Dublin Core spoke (M6) is what wires aliasing
;; (via `rewrite-tags`).
;; ---------------------------------------------------------------------------

(def title-xml-qualified-mapping
  [{:mapping/id        :map/dc-title
    :mapping/from      :dc/title
    :mapping/to        :canon/title
    :mapping/qualifier {:from :xml/lang :as :canon/lang}}])

(defn- xml-element [tag attrs & content]
  {:tag tag :attrs (or attrs {}) :content (vec content)})

(deftest xml-flat-text-content-becomes-one-assertion
  (let [record (shape/ingest-xml
                (xml-element :record nil
                             (xml-element :dc/title nil "Les Misérables"))
                title-flat-mapping opts)]
    (is (= 1 (count (:assertions record))))
    (is (= []  (:fragments record)))
    (let [a (-> record :assertions first)]
      (is (= :record/r1 (:subject a)))
      (is (= :dc/title  (:predicate a)))
      (is (= "Les Misérables" (:value a))))))

(deftest xml-flat-repeated-tags-become-multiple-assertions
  (let [record (shape/ingest-xml
                (xml-element :record nil
                             (xml-element :dc/title nil "A")
                             (xml-element :dc/title nil "B")
                             (xml-element :dc/title nil "C"))
                title-flat-mapping opts)]
    (is (= 3 (count (:assertions record))))
    (is (= ["A" "B" "C"] (mapv :value (:assertions record))))))

(deftest xml-flat-empty-element-emits-no-assertion
  (testing "an empty element produces nil text content, which is skipped"
    (let [record (shape/ingest-xml
                  (xml-element :record nil
                               (xml-element :dc/title nil))
                  title-flat-mapping opts)]
      (is (= [] (:assertions record))))))

(deftest xml-flat-multiple-text-children-concatenate
  (testing "text children of :content concatenate into the value"
    (let [record (shape/ingest-xml
                  (xml-element :record nil
                               {:tag :dc/title :attrs {} :content ["Hello, " "world!"]})
                  title-flat-mapping opts)]
      (is (= "Hello, world!" (-> record :assertions first :value))))))

(deftest xml-mixed-content-keeps-strings-drops-nested
  (testing "V1 lossy: text retained, nested elements skipped in :content"
    (let [record (shape/ingest-xml
                  (xml-element :record nil
                               {:tag :dc/title :attrs {}
                                :content ["Les " (xml-element :em nil "Misérables") " (1862)"]})
                  title-flat-mapping opts)]
      (is (= "Les  (1862)" (-> record :assertions first :value))))))

(deftest xml-qualified-element-with-attribute
  (let [record (shape/ingest-xml
                (xml-element :record nil
                             (xml-element :dc/title {:xml/lang "fr"} "Les Misérables"))
                title-xml-qualified-mapping opts)
        frag-id (-> record :fragments first :id)]
    (is (= 1 (count (:fragments record))))
    (testing "record reference"
      (is (some #(and (= :dc/title (:predicate %))
                      (= (model/reference frag-id) (:value %)))
                (assertions-on record :record/r1))))
    (testing "fragment value coord"
      (is (some #(and (= :dc/title (:predicate %))
                      (= "Les Misérables" (:value %)))
                (assertions-on record frag-id))))
    (testing "fragment qualifier coord"
      (is (some #(and (= :xml/lang (:predicate %))
                      (= "fr" (:value %)))
                (assertions-on record frag-id))))))

(deftest xml-qualified-multiplicity-preserves-document-order
  (let [record (shape/ingest-xml
                (xml-element :record nil
                             (xml-element :dc/title {:xml/lang "fr"} "Les Misérables")
                             (xml-element :dc/title {:xml/lang "en"} "The Wretched"))
                title-xml-qualified-mapping opts)
        frag-ids (mapv :id (:fragments record))]
    (is (= 2 (count (:fragments record))))
    (is (= [:frag/record.r1.dc-title.0 :frag/record.r1.dc-title.1] frag-ids))
    (is (= "Les Misérables"
           (-> (assertions-on record :frag/record.r1.dc-title.0)
               (->> (filterv #(= :dc/title (:predicate %))))
               first :value)))
    (is (= "en"
           (-> (assertions-on record :frag/record.r1.dc-title.1)
               (->> (filterv #(= :xml/lang (:predicate %))))
               first :value)))))

(deftest xml-qualified-element-without-attribute-no-qualifier-coord
  (testing "qualified mapping + element without the attribute mints a fragment with just the value coord"
    (let [record (shape/ingest-xml
                  (xml-element :record nil
                               (xml-element :dc/title nil "Anonymous"))
                  title-xml-qualified-mapping opts)
          frag-id (-> record :fragments first :id)]
      (is (= 1 (count (:fragments record))))
      (is (some #(= :dc/title (:predicate %))
                (assertions-on record frag-id)))
      (is (not (some #(= :xml/lang (:predicate %))
                     (assertions-on record frag-id)))))))

(deftest xml-qualified-empty-element-emits-reference-and-qualifier-only
  (let [record (shape/ingest-xml
                (xml-element :record nil
                             (xml-element :dc/title {:xml/lang "fr"}))
                title-xml-qualified-mapping opts)
        frag-id (-> record :fragments first :id)]
    (is (= 1 (count (:fragments record))))
    (testing "no value coord (element had no text)"
      (is (not (some #(= :dc/title (:predicate %))
                     (assertions-on record frag-id)))))
    (testing "qualifier coord still present"
      (is (some #(= :xml/lang (:predicate %))
                (assertions-on record frag-id))))))

(deftest xml-predicates-not-in-mapping-are-ignored
  (let [record (shape/ingest-xml
                (xml-element :record nil
                             (xml-element :dc/title nil "X")
                             (xml-element :dc/creator nil "Hugo")
                             (xml-element :dc/nope nil "skip me"))
                title-flat-mapping opts)]
    (is (= 1 (count (:assertions record))))
    (is (= :dc/title (-> record :assertions first :predicate)))))

(deftest xml-empty-record-element-produces-empty-record
  (let [record (shape/ingest-xml (xml-element :record nil)
                                 title-flat-mapping opts)]
    (is (= [] (:assertions record)))
    (is (= [] (:fragments record)))))

(deftest xml-non-element-input-throws
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"XML element"
                        (shape/ingest-xml "not an element" title-flat-mapping opts)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"XML element"
                        (shape/ingest-xml {:not :element} title-flat-mapping opts))))

(deftest xml-output-record-is-consistent
  (let [record (shape/ingest-xml
                (xml-element :record nil
                             (xml-element :dc/title {:xml/lang "fr"} "A")
                             (xml-element :dc/title {:xml/lang "en"} "B"))
                title-xml-qualified-mapping opts)]
    (is (model/record-consistent? record))
    (is (nil? (model/explain-consistency record)))))

;; ---------------------------------------------------------------------------
;; rewrite-tags
;;
;; The helper bridges real `clojure.data.xml/parse-str` output (which
;; carries URI-encoded namespaces) and `ingest-xml`'s clean-keyword
;; contract. Tested in isolation here; the Dublin Core spoke wires it up
;; as a closure step before calling `ingest-xml`.
;; ---------------------------------------------------------------------------

(def ^:private dc-uri  "http://purl.org/dc/elements/1.1/")
(def ^:private xml-uri "http://www.w3.org/XML/1998/namespace")

(defn- encoded-ns [uri] (str "xmlns." (java.net.URLEncoder/encode uri "UTF-8")))

(deftest rewrite-tags-replaces-known-uri-namespaces-with-short-prefixes
  (let [encoded-tag (keyword (encoded-ns dc-uri) "title")
        tree        {:tag encoded-tag
                     :attrs {}
                     :content ["Hugo"]}
        rewritten   (shape/rewrite-tags tree {:dc dc-uri})]
    (is (= :dc/title (:tag rewritten)))
    (is (= "Hugo"    (-> rewritten :content first)))))

(deftest rewrite-tags-rewrites-attribute-keys
  (let [encoded-attr (keyword (encoded-ns xml-uri) "lang")
        tree         {:tag :record
                      :attrs {encoded-attr "fr"}
                      :content []}
        rewritten    (shape/rewrite-tags tree {:xml xml-uri})]
    (is (= "fr" (get-in rewritten [:attrs :xml/lang])))))

(deftest rewrite-tags-leaves-unknown-namespaces-untouched
  (let [encoded-tag (keyword (encoded-ns "http://other.example/") "thing")
        tree        {:tag encoded-tag :attrs {} :content []}
        rewritten   (shape/rewrite-tags tree {:dc dc-uri})]
    (is (= encoded-tag (:tag rewritten)))))

(deftest rewrite-tags-recurses-into-content
  (let [child-tag (keyword (encoded-ns dc-uri) "title")
        tree      {:tag :record :attrs {}
                   :content [{:tag child-tag :attrs {} :content ["X"]}]}
        rewritten (shape/rewrite-tags tree {:dc dc-uri})]
    (is (= :dc/title (-> rewritten :content first :tag)))))

(deftest rewrite-tags-passes-non-element-content-through
  (let [tree     {:tag :record :attrs {} :content ["plain text"]}
        rewritten (shape/rewrite-tags tree {:dc dc-uri})]
    (is (= "plain text" (-> rewritten :content first)))))

(deftest rewrite-tags-empty-aliases-is-identity
  (let [tree {:tag :something :attrs {:foo "bar"} :content ["text"]}]
    (is (= tree (shape/rewrite-tags tree {})))))

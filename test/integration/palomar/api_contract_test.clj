(ns palomar.api-contract-test
  "The library contract (ADR 0034): the public surface of `palomar.api`, the
   shape of its results, and the EDN IR — written, read back equal, and in step
   with its published schema. A change that breaks one of these breaks callers."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.api :as api]
            [palomar.convert :as convert]
            [palomar.ir :as ir]
            [palomar.model :as model]))

(def ^:private fixtures
  {:intermarc    "test/fixtures/documentary/intermarc/sru/intermarcXchange/bib-flaubert-madame-bovary-start1-max30.xml"
   :intermarc-ng "test/fixtures/documentary/intermarc-ng/examples/fleurs-du-mal-oemi.xml"
   :unimarc      "test/fixtures/documentary/unimarc/sru/bnf-sru-verne-unimarc.xml"
   :dc           "test/fixtures/documentary/dublin-core/w3c_dc_example1.xml"
   :marc21       "test/fixtures/documentary/marc21/marcxml/loc_collection.xml"
   :mods         "test/fixtures/documentary/mods/loc_mods_book.xml"
   :iiif         "test/fixtures/documentary/iiif/manifest_book_simple.json"
   :ead          "test/fixtures/documentary/ead/ead3/ead3_multi_level_optimum.xml"
   :eac-cpf      "test/fixtures/documentary/eac-cpf/example_eac_cpf.xml"
   :lido         "test/fixtures/documentary/lido/staedel-lido-wrap.xml"})

(defn- opts [from] (if (= from :dc) {:record-id :dc/x} {}))

(defn- pivot [from] (:records (api/pivot from (opts from) (slurp (fixtures from)))))

(deftest the-public-surface-is-pinned
  (is (= '#{source-formats target-formats keeps-history?
            pivot export convert convert-stream collection
            accept-all reject-all flag-all accept-when curate supersede history
            validate valid-record? explain-record audit loss-report manifest
            ir-version ->edn read-edn ir-schema}
         (set (keys (ns-publics 'palomar.api))))
      "adding to the API is a minor change; removing or renaming is a breaking one"))

(deftest every-spoke-gives-valid-pivot-records
  (doseq [from (keys fixtures)
          r    (pivot from)]
    (is (api/valid-record? r) (str from " " (:id r) ": " (pr-str (api/explain-record r))))))

(deftest the-pivot-round-trips-through-edn
  (doseq [from (keys fixtures)]
    (testing (str from)
      (let [records (pivot from)
            doc     (api/->edn records)]
        (is (= records (api/read-edn doc)) "read back equal")
        (is (= doc (api/->edn (api/read-edn doc))) "written byte for byte the same again")
        (is (= (count records) (count (str/split-lines doc))) "one envelope per line")))))

(deftest a-conversion-can-resume-from-the-pivot
  (testing "source → edn → target writes what source → target writes"
    (doseq [from (keys fixtures)
            to   [:ntriples :marc21 :linked-art :trig]
            :when (convert/accepts? to (convert/source-resource-type from))]
      (let [direct  (api/convert {:from from :to to :source (slurp (fixtures from)) :opts (opts from)})
            edn     (:output (api/convert {:from from :to :edn :source (slurp (fixtures from)) :opts (opts from)}))
            resumed (api/convert {:from :edn :to to :source edn})]
        (is (= (:output direct) (:output resumed)) (str from " -> " to))))))

(deftest a-curated-pivot-keeps-its-history-through-edn
  (let [{:keys [records]} (api/curate (pivot :marc21) api/flag-all {:by "curator" :at #inst "2026-10-25"})
        back              (api/read-edn (api/->edn records))
        a                 (first (filter model/needs-review? (:assertions (first back))))]
    (is (some? a))
    (is (= records back))
    (is (= [{:verdict :review :from :proposed :to :needs-review :by "curator" :at #inst "2026-10-25"}]
           (api/history (first back) a)))))

(deftest the-edn-target-loses-nothing
  (let [{:keys [loss]} (api/convert {:from :dc :to :edn :source (slurp (fixtures :dc)) :opts (opts :dc)})]
    (is (api/keeps-history? :edn))
    (is (nil? (get-in loss [:by-edge :export])))))

(deftest the-published-schema-is-the-schema
  (is (= (slurp "docs/ir/record-schema.edn") (ir/schema-edn))
      "regenerate with: clojure -M:run ir-schema > docs/ir/record-schema.edn")
  (is (= (ir/schema-form) (api/ir-schema))))

(deftest reading-refuses-what-it-cannot-trust
  (let [r (first (pivot :dc))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unsupported version"
                          (api/read-edn (pr-str {:palomar/ir 99 :record r}))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"expected an envelope"
                          (api/read-edn (pr-str r))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not a valid record"
                          (api/read-edn (pr-str {:palomar/ir 1 :record (dissoc r :id)}))))))

(deftest writing-refuses-what-it-could-not-read-back
  (let [r (model/record {:id :rec/one :kind :test
                         :assertions [(model/assertion {:subject :rec/one :predicate :x/y
                                                        :value (keyword "x" "two words")})]})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot be written as EDN"
                          (api/->edn [r])))))

(deftest result-shapes-are-stable
  (let [src    (slurp (fixtures :marc21))
        result (api/convert {:from :marc21 :to :ntriples :source src})
        report (:loss result)]
    (is (= #{:output :records :loss :deleted :invalid} (set (keys result))))
    (is (every? (set (keys report))
                [:total :distinct-losses :by-category :by-edge :by-aspect :source-fields :records]))
    (is (= #{:output :loss :withheld} (set (keys (api/export :ntriples (pivot :marc21))))))
    (is (every? (set (keys (api/audit {:from :marc21 :to :ntriples :source src})))
                [:target :records :checked :present :reported :unaccounted :relations]))
    (is (every? (set (keys (api/curate (pivot :marc21) api/flag-all)))
                [:records :transitions :summary]))
    (is (contains? (api/source-formats) :edn))
    (is (contains? (api/target-formats) :edn))
    (is (= 1 api/ir-version))))

(defn- well-formed? [^String s]
  (try (.parse (.newDocumentBuilder (doto (javax.xml.parsers.DocumentBuilderFactory/newInstance) (.setNamespaceAware true)))
               (java.io.ByteArrayInputStream. (.getBytes s "UTF-8")))
       true
       (catch Exception _ false)))

(deftest every-xml-output-is-one-xml-document
  (testing "whatever the source and the number of records, an XML target writes one well-formed document (post-audit 3, E1)"
    (doseq [from (keys fixtures)
            to   [:marc21 :mods :dc]
            :when (convert/accepts? to (convert/source-resource-type from))
            :let [{:keys [output records]} (api/convert {:from from :to to :source (slurp (fixtures from)) :opts (opts from)})]
            :when (not (str/blank? output))]
      (is (well-formed? output) (str from " -> " to " (" records " records)"))))
  (testing "a control field holds an identifier, never a printed structure"
    (doseq [from (keys fixtures)
            :when (convert/accepts? :marc21 (convert/source-resource-type from))
            :let [out (:output (api/convert {:from from :to :marc21 :source (slurp (fixtures from)) :opts (opts from)}))]
            cf  (re-seq #"<controlfield tag=\"001\">([^<]*)</controlfield>" out)]
      (is (not (re-find #"[{}\[\]#]" (second cf))) (str from ": " (second cf))))))

(ns palomar.fidelity-test
  "The occurrence-level fidelity audit (audit S11/S12, test 15): every value a
   conversion does not write must be reported, and a silent omission is caught."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.convert :as convert]
            [palomar.fidelity :as fidelity]))

(def ^:private fixtures
  {:intermarc    "test/fixtures/documentary/intermarc/sru/intermarcXchange/bib-flaubert-madame-bovary-start1-max30.xml"
   :intermarc-ng "test/fixtures/documentary/intermarc-ng/examples/fleurs-du-mal-oemi.xml"
   :unimarc      "test/fixtures/documentary/unimarc/sru/bnf-sru-verne-unimarc.xml"
   :dc           "test/fixtures/documentary/dublin-core/w3c_dc_example1.xml"
   :marc21       "test/fixtures/documentary/marc21/marcxml/loc_collection.xml"
   :mods         "test/fixtures/documentary/mods/loc_mods_book.xml"
   :iiif         "test/fixtures/documentary/iiif/manifest_book_simple.json"})

(defn- audit [from to]
  (fidelity/audit {:from from :to to :source (slurp (fixtures from))
                   :opts (if (= from :dc) {:record-id :dc/x} {})}))

(deftest every-conversion-accounts-for-every-value
  (testing "for each spoke and each decodable target, every absent value is reported"
    (doseq [from (keys fixtures)
            to   (sort (fidelity/decodable-targets))]
      (let [{:keys [checked present unaccounted]} (audit from to)]
        (is (pos? checked) (str from " -> " to " checked something"))
        (is (empty? unaccounted)
            (str from " -> " to ": silent loss of " (pr-str (take 5 unaccounted))))
        (is (<= present checked))))))

(deftest a-silent-omission-is-caught
  (testing "an exporter that drops one note without reporting it fails the audit (audit test 15)"
    (let [real   @#'convert/exporters
          broken (update real :dc assoc :render
                         (fn [r] (-> ((get-in real [:dc :render]) r)
                                     (str/replace #"(?s)<dc:description[^>]*>[^<]*</dc:description>" ""))))
          {:keys [unaccounted]} (with-redefs-fn {#'convert/exporters broken}
                                  #(audit :dc :dc))]
      (is (seq unaccounted))
      (is (every? #(contains? #{:canon/note :dc/description} (:predicate %)) unaccounted)))))

(deftest turtle-is-reported-as-unchecked
  (is (:unchecked (audit :dc :turtle))))

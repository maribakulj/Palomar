(ns palomar.validate-test
  "Tests for the validation gate: import → normalize → canonical :validate rules,
   with a policy-driven verdict."
  (:require [clojure.test :refer [deftest is testing]]
            [palomar.validate :as validate]))

(def ^:private titleless-dc
  "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:creator>Anon</dc:creator></metadata>")

(deftest a-titled-record-is-valid
  (testing "MARC21 records that carry a title pass the canonical rules"
    (let [{:keys [records diagnostics failed?]}
          (validate/validate {:from :marc21
                              :source (slurp "test/fixtures/documentary/marc21/marcxml/loc_collection.xml")})]
      (is (= 2 records))
      (is (empty? diagnostics))
      (is (false? failed?)))))

(deftest a-titleless-record-warns-and-the-policy-decides-the-verdict
  (testing "no :canon/title -> a :missing-title warning"
    (let [{:keys [diagnostics summary failed?]}
          (validate/validate {:from :dc :source titleless-dc :opts {:record-id :doc/x}})]
      (is (= 1 (count diagnostics)))
      (is (= :missing-title (:code (first diagnostics))))
      (is (= 1 (get-in summary [:by-severity :warning])))
      (testing "a warning does not fail under the default :errors-only policy"
        (is (false? failed?)))))
  (testing "...but it does under :errors-and-warnings"
    (is (true? (:failed? (validate/validate {:from :dc :source titleless-dc
                                             :opts {:record-id :doc/x}
                                             :policy :errors-and-warnings}))))))

(deftest intermarc-validates-now-that-it-populates-the-floor
  (testing "INTERMARC normalises to :canon/title, so it passes validation (previously it could not)"
    (let [{:keys [failed? diagnostics]}
          (validate/validate {:from :intermarc
                              :source (slurp "test/fixtures/documentary/intermarc/sru/intermarcXchange/bib-flaubert-madame-bovary-start1-max30.xml")})]
      (is (false? failed?))
      (is (empty? diagnostics)))))

(deftest unknown-source-format-throws
  (is (thrown? Exception (validate/validate {:from :bogus :source "x"}))))

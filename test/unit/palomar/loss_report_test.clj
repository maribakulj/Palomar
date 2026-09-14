(ns palomar.loss-report-test
  "Unit tests for the conversion loss report (ADR 0015): aggregation by edge,
   category and source field, plus the human-readable rendering."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.diagnostics :as dx]
            [palomar.loss-report :as lr]
            [palomar.model :as model]))

(def ^:private diags
  [(dx/loss {:category :dropped :subject :record/r1 :edge :import :source-field :intermarc/f100_a})
   (dx/loss {:category :dropped :subject :record/r1 :edge :import :source-field :intermarc/f260_c})
   (dx/loss {:category :coerced :subject :record/r1 :edge :import :source-field :intermarc/f100_a})
   (dx/loss {:category :dropped :subject :record/r1 :edge :export :source-field :intermarc/f100_a})
   (dx/loss {:category :under-specified :subject :record/r1 :edge :export :source-field :canon/lang})])

(deftest diagnostics-source-field-aggregation
  (testing "dx exposes a native-field view of loss (ADR 0015)"
    (is (= {:intermarc/f100_a 3 :intermarc/f260_c 1 :canon/lang 1}
           (dx/count-by-source-field diags)))
    (is (= {:intermarc/f100_a 3 :intermarc/f260_c 1 :canon/lang 1}
           (:by-source-field (dx/loss-summary diags))))))

(deftest aggregates-by-edge-category-and-source-field
  (let [r (lr/conversion-report diags {:records 1})]
    (is (= 5 (:total r)))
    (is (= 1 (:records r)))
    (testing "distinct losses dedupe [subject source-field] across edges (audit R3)"
      ;; f100_a is lost 3× (2 import + 1 export) but is one distinct field-loss,
      ;; so :total 5 events collapse to 3 distinct losses.
      (is (= 3 (:distinct-losses r))))
    (testing "aggregate by category (zeros for unused categories kept)"
      (is (= 3 (get-in r [:by-category :dropped])))
      (is (= 1 (get-in r [:by-category :coerced])))
      (is (= 1 (get-in r [:by-category :under-specified])))
      (is (= 0 (get-in r [:by-category :ambiguity-collapsed]))))
    (testing "split per edge"
      (is (= 3 (get-in r [:by-edge :import :total])))
      (is (= 2 (get-in r [:by-edge :export :total])))
      (is (= 2 (get-in r [:by-edge :import :by-source-field :intermarc/f100_a])))
      (is (= 1 (get-in r [:by-edge :export :by-source-field :canon/lang]))))
    (testing "the distinct native fields lost"
      (is (= #{:canon/lang :intermarc/f100_a :intermarc/f260_c} (set (:source-fields r)))))))

(deftest non-loss-diagnostics-are-ignored
  (testing "an ordinary error diagnostic is not counted as loss"
    (let [noise (conj diags (model/diagnostic {:severity :error :code :missing-title
                                               :subject :record/r1}))]
      (is (= 5 (:total (lr/conversion-report noise)))))))

(deftest renders-human-readable
  (let [s (lr/format-conversion-report (lr/conversion-report diags {:records 1}))]
    (is (str/includes? s "Loss report"))
    (is (str/includes? s "3 distinct losses"))   ; deduped headline, not the 5 events (R3)
    (is (str/includes? s "5 field×edge events"))
    (is (str/includes? s "1 record"))
    (is (str/includes? s "import edge"))
    (is (str/includes? s "export edge"))
    (is (str/includes? s "f100_a")))
  (testing "a lossless conversion says so"
    (is (str/includes? (lr/format-conversion-report (lr/conversion-report [])) "lossless"))))

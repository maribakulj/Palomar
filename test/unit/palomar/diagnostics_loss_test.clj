(ns palomar.diagnostics-loss-test
  "WP-1 slice 3: loss as a first-class diagnostic category (ADR 0015 / D9)."
  (:require [clojure.test :refer [deftest is]]
            [palomar.diagnostics :as dx]
            [palomar.model :as model]))

(deftest loss-constructor-builds-a-valid-diagnostic
  (let [d (dx/loss {:category :dropped :subject :record/r1
                    :edge :import :source-field "852$a"
                    :message "shelfmark dropped"})]
    (is (model/valid-diagnostic? d))
    (is (= :loss/dropped (:code d)))
    (is (= :info (:severity d)))
    (is (= :import (get-in d [:detail :loss/edge])))
    (is (= "852$a" (get-in d [:detail :loss/source-field])))
    (is (dx/loss? d))
    (is (= :dropped (dx/loss-category d)))))

(deftest loss-rejects-unknown-category-or-edge
  (is (thrown? clojure.lang.ExceptionInfo
               (dx/loss {:category :nope :subject :record/r1 :edge :import})))
  (is (thrown? clojure.lang.ExceptionInfo
               (dx/loss {:category :dropped :subject :record/r1 :edge :sideways}))))

(deftest severity-is-overridable
  (is (= :warning (:severity (dx/loss {:category :coerced :subject :record/r1
                                       :edge :export :severity :warning})))))

(deftest losses-filter-and-non-loss-untouched
  (let [loss-d (dx/loss {:category :coerced :subject :record/r1 :edge :export})
        plain  (model/diagnostic {:severity :error :code :missing-title
                                  :subject :record/r1})
        ds     [loss-d plain]]
    (is (= [loss-d] (dx/losses ds)))
    (is (false? (dx/loss? plain)))
    (is (nil? (dx/loss-category plain)))
    ;; the existing by-code/group-by-code aggregations see loss codes too
    (is (contains? (dx/group-by-code ds) :loss/coerced))
    (is (= [loss-d] (dx/by-code ds :loss/coerced)))))

(deftest loss-summary-is-a-breakdown
  (let [ds [(dx/loss {:category :dropped :subject :record/r1 :edge :import})
            (dx/loss {:category :dropped :subject :record/r2 :edge :export})
            (dx/loss {:category :coerced :subject :record/r3 :edge :export})]
        s  (dx/loss-summary ds)]
    (is (= 3 (:total s)))
    (is (= 2 (get-in s [:by-category :dropped])))
    (is (= 1 (get-in s [:by-category :coerced])))
    (is (= 0 (get-in s [:by-category :under-specified])))
    (is (= {:import 1 :export 2} (:by-edge s)))))

(deftest loss-categories-and-edges-are-closed
  (is (= #{:dropped :coerced :under-specified :ambiguity-collapsed}
         dx/loss-categories))
  (is (= #{:import :export} dx/loss-edges)))

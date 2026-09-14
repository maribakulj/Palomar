(ns palomar.plugins.transforms-test
  "Unit tests for the core transform stdlib (Sprint 5 M3).
   Each transform is total: it returns the transformed value, or nil
   for inputs it doesn't accept. No exceptions thrown for type
   mismatches — that contract is what makes transform chains
   compose-safely via nil-short-circuit."
  (:require [clojure.test :refer [deftest is testing]]
            [palomar.plugins.transforms :as tx]))

;; ---------------------------------------------------------------------------
;; Per-transform behavior
;; ---------------------------------------------------------------------------

(deftest trim-trims-strings
  (let [trim (:trim tx/core-transforms)]
    (is (= "abc"   (trim "  abc  ")))
    (is (= "a b c" (trim "  a b c  ")))
    (is (= ""      (trim "   ")))
    (is (= "abc"   (trim "abc")))))

(deftest trim-returns-nil-for-non-strings
  (let [trim (:trim tx/core-transforms)]
    (is (nil? (trim 42)))
    (is (nil? (trim nil)))
    (is (nil? (trim [1 2 3])))
    (is (nil? (trim :keyword)))))

(deftest lowercase-folds-case
  (let [lower (:lowercase tx/core-transforms)]
    (is (= "abc"     (lower "ABC")))
    (is (= "abc def" (lower "AbC dEf")))
    (is (= "été"     (lower "ÉTÉ"))))) ; locale-insensitive but unicode-aware

(deftest lowercase-returns-nil-for-non-strings
  (is (nil? ((:lowercase tx/core-transforms) 42)))
  (is (nil? ((:lowercase tx/core-transforms) nil))))

(deftest uppercase-folds-case
  (let [upper (:uppercase tx/core-transforms)]
    (is (= "ABC"     (upper "abc")))
    (is (= "ABC DEF" (upper "AbC dEf")))))

(deftest uppercase-returns-nil-for-non-strings
  (is (nil? ((:uppercase tx/core-transforms) 42)))
  (is (nil? ((:uppercase tx/core-transforms) nil))))

(deftest parse-int-on-strings
  (let [pi (:parse-int tx/core-transforms)]
    (is (= 42  (pi "42")))
    (is (= 42  (pi "  42  "))                    "trims before parsing")
    (is (= -7  (pi "-7")))
    (is (= 0   (pi "0")))
    (is (nil?  (pi "abc"))                       "rejects garbage")
    (is (nil?  (pi "3.14"))                      "rejects decimals (use parse-double)")
    (is (nil?  (pi "")))))

(deftest parse-int-passes-through-ints
  (let [pi (:parse-int tx/core-transforms)]
    (is (= 42 (pi 42)))
    (is (= 0  (pi 0)))
    (is (= -5 (pi -5)))))

(deftest parse-int-returns-nil-for-other-types
  (let [pi (:parse-int tx/core-transforms)]
    (is (nil? (pi 3.14)))   ; double, not int
    (is (nil? (pi nil)))
    (is (nil? (pi :42)))
    (is (nil? (pi [42])))))

(deftest parse-double-on-strings
  (let [pd (:parse-double tx/core-transforms)]
    (is (= 3.14   (pd "3.14")))
    (is (= 3.14   (pd "  3.14  ")))
    (is (= -2.5   (pd "-2.5")))
    (is (= 42.0   (pd "42")))
    (is (nil?     (pd "abc")))
    (is (nil?     (pd "")))))

(deftest parse-double-on-numbers
  (let [pd (:parse-double tx/core-transforms)]
    (is (= 3.14 (pd 3.14)))
    (is (= 42.0 (pd 42))   "ints widen to double")))

(deftest parse-double-returns-nil-for-other-types
  (is (nil? ((:parse-double tx/core-transforms) nil)))
  (is (nil? ((:parse-double tx/core-transforms) :3.14))))

(deftest parse-iso-date-accepts-canonical-forms
  (let [pid (:parse-iso-date tx/core-transforms)]
    (testing "full date"
      (is (= "1823-04-12" (pid "1823-04-12"))))
    (testing "year-month"
      (is (= "1823-04"    (pid "1823-04"))))
    (testing "year only"
      (is (= "1823"       (pid "1823"))))
    (testing "BCE years (leading minus)"
      (is (= "-0044-03-15" (pid "-0044-03-15"))))
    (testing "leading and trailing whitespace tolerated"
      (is (= "1823-04-12" (pid "  1823-04-12  "))))))

(deftest parse-iso-date-rejects-non-canonical-forms
  (let [pid (:parse-iso-date tx/core-transforms)]
    (is (nil? (pid "12 April 1823"))   "freeform rejected")
    (is (nil? (pid "1823/04/12"))      "slash separator rejected")
    (is (nil? (pid "823-04-12"))       "3-digit year rejected")
    (is (nil? (pid "1823-4-12"))       "non-zero-padded month rejected")
    (is (nil? (pid "1823-04-12T00:00")) "time component rejected")
    (is (nil? (pid "")))
    (is (nil? (pid nil)))
    (is (nil? (pid 1823))              "non-string rejected")))

;; ---------------------------------------------------------------------------
;; Stdlib shape
;; ---------------------------------------------------------------------------

(deftest core-transforms-has-six-entries
  (is (= #{:trim :lowercase :uppercase
           :parse-int :parse-double :parse-iso-date}
         (set (keys tx/core-transforms)))))

(deftest core-transforms-values-are-functions
  (doseq [[name f] tx/core-transforms]
    (is (fn? f) (str "transform " name " must be a function"))))

(deftest lossy-classification
  (testing "case folding discards detail (ADR 0015 :coerced); trim/parse do not"
    (is (tx/lossy? :lowercase))
    (is (tx/lossy? :uppercase))
    (is (not (tx/lossy? :trim)))
    (is (not (tx/lossy? :parse-int)))
    (is (not (tx/lossy? :parse-iso-date)))))

;; ---------------------------------------------------------------------------
;; Composition
;; ---------------------------------------------------------------------------

(deftest compose-applies-left-to-right
  (let [chain (tx/compose tx/core-transforms [:trim :lowercase])]
    (is (= "abc" (chain "  ABC  "))))
  (let [chain (tx/compose tx/core-transforms [:lowercase :trim])]
    (is (= "abc" (chain "  ABC  "))
        "result equivalent in this case but stages still run in declared order"))
  (let [chain (tx/compose tx/core-transforms [:uppercase :trim])]
    (is (= "ABC" (chain "  abc  ")))))

(deftest compose-empty-chain-is-identity
  (let [chain (tx/compose tx/core-transforms [])]
    (is (= "abc" (chain "abc")))
    (is (= 42   (chain 42)))
    (is (nil?    (chain nil)))))

(deftest compose-single-stage
  (let [chain (tx/compose tx/core-transforms [:trim])]
    (is (= "abc" (chain "  abc  ")))))

(deftest compose-short-circuits-on-nil
  (let [chain (tx/compose tx/core-transforms [:trim :lowercase])]
    (testing "nil input short-circuits without running any stage"
      (is (nil? (chain nil))))
    (testing "non-string trims to nil, then lowercase short-circuits"
      (is (nil? (chain 42))))))

(deftest compose-rejects-unknown-transform
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Unknown transform"
                        (tx/compose tx/core-transforms [:trim :nonexistent])))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Unknown transform"
                        (tx/compose tx/core-transforms [:nonexistent]))))

(deftest compose-accepts-extension-transforms
  (testing "compose looks up against the stdlib map passed in, so plugin extensions work"
    (let [extended (assoc tx/core-transforms
                          :reverse (fn [v] (when (string? v)
                                             (apply str (reverse v)))))
          chain    (tx/compose extended [:trim :reverse])]
      (is (= "cba" (chain "  abc  "))))))
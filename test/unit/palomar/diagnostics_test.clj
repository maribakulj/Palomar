(ns palomar.diagnostics-test
  "Tests for the diagnostics API: severity ordering, filters, aggregations,
   reporting, and the failure policy used by the CLI/CI integration."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.diagnostics :as diag]
            [palomar.model :as model]))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(defn- d [{:keys [severity code subject message repairs rule pass]
           :or   {severity :error code :x subject :s/one}}]
  (model/diagnostic
   (cond-> {:severity severity :code code :subject subject}
     message (assoc :message message)
     repairs (assoc :repairs repairs)
     (or rule pass)
     (assoc :provenance (cond-> {}
                          rule (assoc :rule rule)
                          pass (assoc :pass pass))))))

(def ^:private err1 (d {:severity :error   :code :missing-title
                        :subject :r/one
                        :rule :rule/title-required :pass :validate}))
(def ^:private err2 (d {:severity :error   :code :missing-id
                        :subject :r/two
                        :rule :rule/id-required :pass :validate}))
(def ^:private warn1 (d {:severity :warning :code :ambiguous-date
                         :subject :r/one
                         :rule :rule/date-check :pass :infer}))
(def ^:private info1 (d {:severity :info    :code :note
                         :subject :r/three
                         :rule :rule/notes :pass :normalize}))

(def ^:private all [err1 err2 warn1 info1])

;; ---------------------------------------------------------------------------
;; Severity ordering
;; ---------------------------------------------------------------------------

(deftest severity-rank-orders-info-warning-error
  (is (< (diag/severity-rank :info) (diag/severity-rank :warning)))
  (is (< (diag/severity-rank :warning) (diag/severity-rank :error))))

(deftest severity-rank-rejects-unknown
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"Unknown severity"
       (diag/severity-rank :catastrophic))))

(deftest count-by-severity-rejects-unknown
  ;; Schema-bypass defense: even if a diagnostic skips Malli validation,
  ;; the counter throws rather than silently extending the result map.
  (let [bogus {:severity :catastrophic :code :x :subject :s/one}]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"Unknown severity"
         (diag/count-by-severity [bogus])))))

(deftest severity>=-is-reflexive-and-monotonic
  (is (diag/severity>= :error :error))
  (is (diag/severity>= :error :warning))
  (is (diag/severity>= :warning :info))
  (is (not (diag/severity>= :info :warning))))

(deftest max-severity-on-empty-is-nil
  (is (nil? (diag/max-severity []))))

(deftest max-severity-picks-most-severe
  (is (= :error   (diag/max-severity all)))
  (is (= :warning (diag/max-severity [warn1 info1])))
  (is (= :info    (diag/max-severity [info1]))))

;; ---------------------------------------------------------------------------
;; Collection helpers
;; ---------------------------------------------------------------------------

(deftest collect-returns-empty-vector-when-absent
  (is (= [] (diag/collect (model/record {:id :r/x :kind :book}))))
  (is (= [err1] (diag/collect {:diagnostics [err1]}))))

(deftest collect-many-flattens-and-preserves-order
  (let [r1 {:diagnostics [err1]}
        r2 {:diagnostics [warn1 info1]}
        r3 {:diagnostics []}]
    (is (= [err1 warn1 info1] (diag/collect-many [r1 r2 r3])))))

;; ---------------------------------------------------------------------------
;; Filters
;; ---------------------------------------------------------------------------

(deftest by-severity-filters
  (is (= [err1 err2] (diag/by-severity all :error)))
  (is (= [warn1]    (diag/by-severity all :warning)))
  (is (= [info1]    (diag/by-severity all :info))))

(deftest severity-shortcuts
  (is (= [err1 err2] (diag/errors all)))
  (is (= [warn1]    (diag/warnings all)))
  (is (= [info1]    (diag/infos all))))

(deftest at-least-includes-equal-and-higher
  (is (= [err1 err2 warn1] (diag/at-least all :warning)))
  (is (= [err1 err2]       (diag/at-least all :error)))
  (is (= all               (diag/at-least all :info))))

(deftest by-code-filters
  (is (= [err1] (diag/by-code all :missing-title)))
  (is (empty? (diag/by-code all :nope))))

(deftest by-subject-filters
  (is (= [err1 warn1] (diag/by-subject all :r/one)))
  (is (= [info1] (diag/by-subject all :r/three))))

(deftest by-rule-filters
  (is (= [warn1] (diag/by-rule all :rule/date-check)))
  (is (empty? (diag/by-rule all :rule/missing))))

(deftest by-phase-filters
  (is (= [err1 err2] (diag/by-phase all :validate)))
  (is (= [warn1] (diag/by-phase all :infer)))
  (is (= [info1] (diag/by-phase all :normalize))))

(deftest with-repairs-keeps-only-those-carrying-repairs
  (let [rep   (model/repair {:description "fix" :operation :upsert})
        with  (d {:code :fixable :repairs [rep]})
        plain (d {:code :unfixable})]
    (is (= [with] (diag/with-repairs [plain with])))))

;; ---------------------------------------------------------------------------
;; Aggregations
;; ---------------------------------------------------------------------------

(deftest count-by-severity-includes-zeros
  (is (= {:error 2 :warning 1 :info 1} (diag/count-by-severity all)))
  (is (= {:error 0 :warning 0 :info 0} (diag/count-by-severity []))))

(deftest group-by-subject-groups
  (let [g (diag/group-by-subject all)]
    (is (= 3 (count g)))
    (is (= [err1 warn1] (get g :r/one)))
    (is (= [err2]       (get g :r/two)))
    (is (= [info1]      (get g :r/three)))))

(deftest group-by-code-groups
  (let [g (diag/group-by-code all)]
    (is (= [err1]  (get g :missing-title)))
    (is (= [err2]  (get g :missing-id)))
    (is (= [warn1] (get g :ambiguous-date)))
    (is (= [info1] (get g :note)))))

(deftest summary-shape-and-content
  (let [s (diag/summary all)]
    (is (= 4 (:total s)))
    (is (= {:error 2 :warning 1 :info 1} (:by-severity s)))
    (is (= :error (:max-severity s)))
    (is (= 3 (:subjects s)))
    (is (= 4 (:codes s))))
  (testing "empty input"
    (let [s (diag/summary [])]
      (is (= 0 (:total s)))
      (is (nil? (:max-severity s)))
      (is (= 0 (:subjects s))))))

;; ---------------------------------------------------------------------------
;; Reporting
;; ---------------------------------------------------------------------------

(deftest format-diagnostic-includes-severity-code-subject-message
  (let [line (diag/format-diagnostic
              (d {:severity :warning :code :ambiguous-date
                  :subject :r/one :message "Two candidates"}))]
    (is (str/includes? line "WARN"))
    (is (str/includes? line ":ambiguous-date"))
    (is (str/includes? line ":r/one"))
    (is (str/includes? line "Two candidates"))))

(deftest format-diagnostic-omits-message-block-when-absent
  (let [line (diag/format-diagnostic err2)]
    (is (not (str/includes? line " - ")))))

(deftest format-diagnostic-expands-repairs-when-asked
  (let [rep  (model/repair {:description "Add default title"
                            :operation :upsert})
        x    (d {:severity :error :code :missing-title
                 :subject :r/one :repairs [rep]})
        flat (diag/format-diagnostic x)
        rich (diag/format-diagnostic x {:expand-repairs? true})]
    (is (= 1 (count (str/split-lines flat))))
    (is (= 2 (count (str/split-lines rich))))
    (is (str/includes? rich "Add default title"))
    (is (str/includes? rich ":upsert"))))

(deftest format-report-headers-and-sections
  (let [report (diag/format-report all)]
    (is (str/includes? report "Diagnostics: 4 total"))
    (is (str/includes? report "errors 2"))
    (is (str/includes? report "== ERROR (2) =="))
    (is (str/includes? report "== WARN (1) =="))
    (is (str/includes? report "== INFO (1) =="))))

(deftest format-report-skips-empty-sections
  (let [report (diag/format-report [info1])]
    (is (str/includes? report "== INFO"))
    (is (not (str/includes? report "== ERROR")))
    (is (not (str/includes? report "== WARN")))))

(deftest format-report-on-empty-input-still-renders-header
  (let [report (diag/format-report [])]
    (is (str/includes? report "0 total"))))

;; ---------------------------------------------------------------------------
;; Failure policy
;; ---------------------------------------------------------------------------

(deftest should-fail-defaults-to-errors-only
  (is (true?  (diag/should-fail? [err1])))
  (is (false? (diag/should-fail? [warn1])))
  (is (false? (diag/should-fail? [info1])))
  (is (false? (diag/should-fail? []))))

(deftest should-fail-policy-never
  (is (false? (diag/should-fail? all :never)))
  (is (false? (diag/should-fail? [] :never))))

(deftest should-fail-policy-errors-and-warnings
  (is (true?  (diag/should-fail? [warn1] :errors-and-warnings)))
  (is (true?  (diag/should-fail? [err1]  :errors-and-warnings)))
  (is (false? (diag/should-fail? [info1] :errors-and-warnings))))

(deftest should-fail-policy-strict
  (is (true? (diag/should-fail? [info1] :strict)))
  (is (true? (diag/should-fail? [warn1] :strict)))
  (is (true? (diag/should-fail? [err1]  :strict)))
  (is (false? (diag/should-fail? [] :strict))))

(deftest should-fail-rejects-unknown-policy
  (is (thrown? clojure.lang.ExceptionInfo
               (diag/should-fail? all :wishful-thinking))))

;; ---------------------------------------------------------------------------
;; Sanity check
;; ---------------------------------------------------------------------------

(deftest diagnostic-shape-check
  (is (diag/diagnostic? err1))
  (is (not (diag/diagnostic? {:not "a diagnostic"}))))

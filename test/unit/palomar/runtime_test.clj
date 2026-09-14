(ns palomar.runtime-test
  "Tests for the rule execution engine: phase filtering, production merging,
   single-pass phase execution, and pipeline runs."
  (:require [clojure.test :refer [deftest is testing]]
            [palomar.model :as model]
            [palomar.rules :as rules]
            [palomar.runtime :as rt]))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(defn- book [id & {:keys [title kind] :or {kind :book}}]
  (model/record
   {:id id
    :kind kind
    :assertions (cond-> []
                  title (conj (model/assertion {:subject id
                                                :predicate :canon/title
                                                :value title})))}))

(defn- compile-many [rules]
  (mapv rules/compile-rule rules))

(def ^:private tag-rule
  "Normalize rule: tag every book with :canon/seen true."
  {:id :rule/tag
   :phase :normalize
   :match '[[?r :meta/kind :book]]
   :produce {:assert {:subject '?r
                      :predicate :canon/seen
                      :value true}}})

(def ^:private title-required-rule
  "Validation rule: a book without a title produces an error diagnostic."
  {:id :rule/title-required
   :phase :validate
   :match '[[?r :meta/kind :book]
            (absent? ?r :canon/title)]
   :produce {:diagnostic {:severity :error
                          :code :missing-title
                          :subject '?r}}})

(def ^:private normalize-title-rule
  "Validate rule: for every title, emit a normalized copy under a new
   predicate. Intentionally in :validate so tests can control the phase."
  {:id :rule/normalize-title
   :phase :validate
   :match '[[?r :canon/title ?t]]
   :produce {:assert {:subject '?r
                      :predicate :canon/normalized-title
                      :value '?t}}})

;; ---------------------------------------------------------------------------
;; Rule filtering
;; ---------------------------------------------------------------------------

(deftest rules-for-phase-filters-correctly
  (let [rs (compile-many [tag-rule title-required-rule normalize-title-rule])]
    (is (= 1 (count (rt/rules-for-phase rs :normalize))))
    (is (= 2 (count (rt/rules-for-phase rs :validate))))
    (is (= 0 (count (rt/rules-for-phase rs :infer))))))

;; ---------------------------------------------------------------------------
;; Merging productions
;; ---------------------------------------------------------------------------

(deftest merge-assertion-appends-to-record
  (let [r    (book :r/m1)
        a    (model/assertion {:subject :r/m1 :predicate :canon/x :value 1})
        out  (rt/merge-productions r [{:kind :assertion :value a}])]
    (is (= 1 (count (:assertions out))))
    (is (= :canon/x (-> out :assertions first :predicate)))))

(deftest merge-diagnostic-appends-to-record
  (let [r    (book :r/m2)
        d    (model/diagnostic {:severity :error :code :x :subject :r/m2})
        out  (rt/merge-productions r [{:kind :diagnostic :value d}])]
    (is (= 1 (count (:diagnostics out))))
    (is (= :error (-> out :diagnostics first :severity)))))

(deftest merge-repair-is-a-noop
  (let [r   (book :r/m3)
        rep (model/repair {:description "x" :operation :y})
        out (rt/merge-productions r [{:kind :repair :value rep}])]
    (is (= r out))))

(deftest merge-preserves-existing-content
  (let [existing (model/assertion {:subject :r/e :predicate :canon/p :value 0})
        r        (assoc (book :r/e) :assertions [existing])
        new-a    (model/assertion {:subject :r/e :predicate :canon/p :value 1})
        out      (rt/merge-productions r [{:kind :assertion :value new-a}])]
    (is (= 2 (count (:assertions out))))
    (is (= [0 1] (mapv :value (:assertions out))))))

(deftest merge-deduplicates-identical-assertions
  ;; ADR 0008: assertions are deduplicated by [subject predicate value status].
  (let [a1  (model/assertion {:subject :r/d :predicate :canon/p :value 1
                              :provenance {:rule :rule/a}})
        a2  (model/assertion {:subject :r/d :predicate :canon/p :value 1
                              :provenance {:rule :rule/b}})
        out (rt/merge-productions (book :r/d)
                                  [{:kind :assertion :value a1}
                                   {:kind :assertion :value a2}])]
    (is (= 1 (count (:assertions out)))
        "second assertion with identical [subject predicate value status] is suppressed")
    (is (= :rule/a (get-in (first (:assertions out)) [:provenance :rule]))
        "the first occurrence wins; provenance is preserved")))

(deftest merge-distinguishes-assertions-by-status
  ;; Same fact at different statuses are distinct identities (ADR 0008).
  (let [asserted (model/assertion {:subject :r/s :predicate :canon/p :value 1
                                   :status :asserted})
        proposed (model/assertion {:subject :r/s :predicate :canon/p :value 1
                                   :status :proposed})
        out (rt/merge-productions (book :r/s)
                                  [{:kind :assertion :value asserted}
                                   {:kind :assertion :value proposed}])]
    (is (= 2 (count (:assertions out))))))

(deftest merge-deduplicates-identical-diagnostics
  ;; ADR 0008: diagnostics are deduplicated by [subject code severity message].
  (let [d1  (model/diagnostic {:severity :error :code :missing-title
                               :subject :r/d :message "no title"})
        d2  (model/diagnostic {:severity :error :code :missing-title
                               :subject :r/d :message "no title"})
        out (rt/merge-productions (book :r/d)
                                  [{:kind :diagnostic :value d1}
                                   {:kind :diagnostic :value d2}])]
    (is (= 1 (count (:diagnostics out))))))

(deftest merge-distinguishes-diagnostics-by-message
  ;; Same code + severity + subject but different messages: distinct.
  (let [d1  (model/diagnostic {:severity :error :code :bad-date
                               :subject :r/x :message "year out of range"})
        d2  (model/diagnostic {:severity :error :code :bad-date
                               :subject :r/x :message "format unparseable"})
        out (rt/merge-productions (book :r/x)
                                  [{:kind :diagnostic :value d1}
                                   {:kind :diagnostic :value d2}])]
    (is (= 2 (count (:diagnostics out))))))

;; ---------------------------------------------------------------------------
;; run-phase — a single pass over one phase's rules (ADR 0004/0020)
;; ---------------------------------------------------------------------------

(deftest run-phase-no-rules-is-identity
  (let [r (book :r/a :title "T")
        {:keys [record productions]} (rt/run-phase r [] :validate)]
    (is (= r record))
    (is (empty? productions))))

(deftest run-phase-skips-wrong-phase
  (let [r   (book :r/b)
        rs  (compile-many [tag-rule])
        {:keys [record productions]} (rt/run-phase r rs :validate)]
    (is (= r record))
    (is (empty? productions))))

(deftest run-phase-merges-productions
  (let [r   (book :r/c)
        rs  (compile-many [tag-rule])
        {:keys [record productions]} (rt/run-phase r rs :normalize)]
    (is (= 1 (count productions)))
    (is (= 1 (count (:assertions record))))
    (is (= :canon/seen (-> record :assertions first :predicate)))
    (is (= :rule/tag  (-> record :assertions first :provenance :rule)))))

(deftest run-phase-multi-rule
  (let [r   (book :r/d)
        rs  (compile-many [tag-rule title-required-rule])
        {:keys [record productions]} (rt/run-phase r rs :validate)]
    ;; Only title-required applies in :validate; tag-rule is :normalize.
    (is (= 1 (count productions)))
    (is (= :diagnostic (:kind (first productions))))
    (is (= :missing-title (get-in (first productions) [:value :code])))
    (is (= :validate (get-in (first productions) [:value :provenance :pass])))
    (is (= 1 (count (:diagnostics record))))))

(deftest run-phase-is-a-single-pass
  ;; A phase fires every matching rule once against the record as it entered
  ;; the phase; a rule does not observe facts another rule produced in the
  ;; same pass (ADR 0020 — no multi-cycle iteration). Chaining is expressed
  ;; across phases, not within one.
  (let [produce-x {:id :rule/produce-x
                   :phase :normalize
                   :match '[[?r :meta/kind :book]
                            (absent? ?r :canon/x)]
                   :produce {:assert {:subject '?r :predicate :canon/x :value 1}}}
        consume-x {:id :rule/consume-x
                   :phase :normalize
                   :match '[[?r :canon/x ?v]]
                   :produce {:assert {:subject '?r :predicate :canon/x-seen :value true}}}
        r         (book :r/i)
        rs        (compile-many [produce-x consume-x])
        {:keys [record]} (rt/run-phase r rs :normalize)]
    (is (some #(= :canon/x (:predicate %)) (:assertions record))
        "produce-x fires")
    (is (not-any? #(= :canon/x-seen (:predicate %)) (:assertions record))
        "consume-x does not see produce-x's same-pass output")))

(deftest run-phase-dedups-same-fact-from-two-rules
  ;; ADR 0008 survives the single-pass move: two rules deriving the same
  ;; [subject predicate value status] in one pass leave two trace entries
  ;; but a single merged assertion.
  (let [tag-again (assoc tag-rule :id :rule/tag-again)
        rs        (compile-many [tag-rule tag-again])
        {:keys [record productions]} (rt/run-phase (book :r/g) rs :normalize)]
    (is (= 2 (count productions))
        "trace records both firings")
    (is (= 1 (count (:assertions record)))
        "merge dedups identical assertions (ADR 0008)")))

;; ---------------------------------------------------------------------------
;; run-pipeline
;; ---------------------------------------------------------------------------

(deftest run-pipeline-sequences-phases
  (let [r        (book :r/p :title "Les Misérables")
        rs       (compile-many [tag-rule normalize-title-rule])
        pipeline [{:phase :normalize}
                  {:phase :validate}]
        {:keys [record]} (rt/run-pipeline r rs pipeline)]
    (is (some #(= :canon/seen              (:predicate %)) (:assertions record)))
    (is (some #(= :canon/normalized-title  (:predicate %)) (:assertions record)))))

(deftest run-pipeline-validates-shape
  (is (thrown? clojure.lang.ExceptionInfo
               (rt/run-pipeline (book :r/r) []
                                [{:phase :unknown-phase}]))))

;; ---------------------------------------------------------------------------
;; Provenance attribution: every production names its rule and phase
;; ---------------------------------------------------------------------------

(deftest provenance-names-the-producing-rule-and-phase
  (let [r        (book :r/t :title "T")
        rs       (compile-many [tag-rule title-required-rule normalize-title-rule])
        pipeline [{:phase :normalize} {:phase :validate}]
        {:keys [record]} (rt/run-pipeline r rs pipeline)
        rule-of  (fn [items] (frequencies (map #(get-in % [:provenance :rule]) items)))
        a-by     (rule-of (:assertions record))]
    (testing "assertions carry the rule that produced them"
      (is (contains? a-by :rule/tag))
      (is (contains? a-by :rule/normalize-title))
      (is (= 1 (get a-by :rule/tag))))
    (testing "a single :normalize rule yields one assertion attributed to it"
      (let [{:keys [record]} (rt/run-phase (book :r/u) (compile-many [tag-rule]) :normalize)]
        (is (= 1 (count (filter #(= :rule/tag (get-in % [:provenance :rule]))
                                (:assertions record)))))))
    (testing "a :validate rule attributes its diagnostic by rule and phase"
      (let [{:keys [record]} (rt/run-phase (book :r/v) (compile-many [title-required-rule]) :validate)
            d (first (:diagnostics record))]
        (is (= 1 (count (:diagnostics record))))
        (is (= :rule/title-required (get-in d [:provenance :rule])))
        (is (= :validate (get-in d [:provenance :pass])))))))

;; ---------------------------------------------------------------------------
;; Exit-gate integration test: 20 rules, 50+ productions, every production
;; traceable to its source rule.
;; ---------------------------------------------------------------------------

(defn- synthetic-rule
  "Generate a rule that asserts :canon/tag-i under :normalize."
  [i]
  {:id (keyword (str "rule/tag-" i))
   :phase :normalize
   :match '[[?r :meta/kind :book]]
   :produce {:assert {:subject '?r
                      :predicate (keyword (str "canon/tag-" i))
                      :value i}}})

(defn- synthetic-validator
  "Generate a validator rule that warns when :canon/title is absent."
  [i]
  {:id (keyword (str "rule/v-" i))
   :phase :validate
   :match '[[?r :meta/kind :book]
            (absent? ?r :canon/title)]
   :produce {:diagnostic {:severity :warning
                          :code (keyword (str "check-" i))
                          :subject '?r}}})

(deftest exit-gate-fifty-plus-rules-all-traceable
  (let [rs       (compile-many (concat
                                (map synthetic-rule (range 50))
                                (map synthetic-validator (range 5))))
        _        (is (= 55 (count rs)) "55 compiled rules")
        r        (book :r/gate)
        pipeline [{:phase :normalize}
                  {:phase :validate}]
        {:keys [record productions]} (rt/run-pipeline r rs pipeline)]
    (testing "At least 50 productions in total (exit gate)"
      (is (>= (count productions) 50)))
    (testing "Every produced assertion names its rule in provenance"
      (is (every? #(some? (get-in % [:provenance :rule]))
                  (:assertions record))))
    (testing "Every produced diagnostic names its rule in provenance"
      (is (every? #(some? (get-in % [:provenance :rule]))
                  (:diagnostics record))))
    (testing "Every rule that fired is attributable in provenance"
      (let [fired-rules (set (map #(get-in % [:provenance :rule])
                                  (concat (:assertions record) (:diagnostics record))))]
        ;; 50 tag-rules each produce 1 assertion, 5 validators each produce
        ;; 1 diagnostic — a single pass. All 55 must appear.
        (is (= 55 (count fired-rules)))))))

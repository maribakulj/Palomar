(ns palomar.rules-test
  "Tests for the rule DSL: schema validation, compilation, pattern
   matching, guards, production actions, and provenance tagging."
  (:require [clojure.test :refer [deftest is testing]]
            [palomar.model :as model]
            [palomar.rules :as rules]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- book-record [& {:keys [id title kind]
                       :or {id :r/book1 kind :book}}]
  (model/record
   {:id id
    :kind kind
    :assertions (cond-> []
                  title (conj (model/assertion {:subject id
                                                :predicate :canon/title
                                                :value title})))}))

(defn- run [rule record]
  (rules/apply-rule (rules/compile-rule rule) record))

;; ---------------------------------------------------------------------------
;; Variables and helpers
;; ---------------------------------------------------------------------------

(deftest variable-detection
  (is (rules/variable? '?r))
  (is (rules/variable? '?title))
  (is (not (rules/variable? 'r)))
  (is (not (rules/variable? :?r)))
  (is (not (rules/variable? "?r"))))

(deftest wildcard-detection
  (is (rules/wildcard? '_))
  (is (not (rules/wildcard? '?_)))
  (is (not (rules/wildcard? '-))))

;; ---------------------------------------------------------------------------
;; Triple view of a record
;; ---------------------------------------------------------------------------

(deftest record-triples-covers-struct-and-assertions
  (let [r (book-record :id :r/r1 :kind :book :title "Les Misérables")
        triples (rules/record-triples r)]
    (is (contains? (set triples) [:r/r1 :meta/id :r/r1]))
    (is (contains? (set triples) [:r/r1 :meta/kind :book]))
    (is (contains? (set triples) [:r/r1 :canon/title "Les Misérables"]))))

(deftest record-triples-exposes-source-and-provenance
  (let [prov (model/provenance {:source :xml/sample :pass :ingest})
        r    (model/record {:id :r/s :kind :book
                            :source :xml/sample
                            :provenance prov})
        ts   (set (rules/record-triples r))]
    (is (contains? ts [:r/s :meta/source :xml/sample]))
    (is (contains? ts [:r/s :meta/provenance prov]))))

(deftest record-triples-emits-one-triple-per-fragment
  (let [r  (model/record {:id :r/f :kind :book
                          :fragments [(model/fragment {:id :frag/a :source :xml})
                                      (model/fragment {:id :frag/b :source :xml})]})
        ts (set (rules/record-triples r))]
    (is (contains? ts [:r/f :meta/fragment :frag/a]))
    (is (contains? ts [:r/f :meta/fragment :frag/b]))))

(deftest record-triples-emits-one-triple-per-diagnostic
  (let [r  (model/record {:id :r/d :kind :book
                          :diagnostics [(model/diagnostic {:severity :error
                                                           :code :missing-title
                                                           :subject :r/d})
                                        (model/diagnostic {:severity :warning
                                                           :code :weak-id
                                                           :subject :r/d})]})
        ts (set (rules/record-triples r))]
    (is (contains? ts [:r/d :meta/diagnostic :missing-title]))
    (is (contains? ts [:r/d :meta/diagnostic :weak-id]))))

(deftest record-triples-omits-nil-struct-fields
  (let [r  (model/record {:id :r/n :kind :book})
        ts (set (rules/record-triples r))]
    (is (contains? ts [:r/n :meta/id :r/n]))
    (is (contains? ts [:r/n :meta/kind :book]))
    (is (not (some (fn [[_ p _]] (= :meta/source p)) ts)))
    (is (not (some (fn [[_ p _]] (= :meta/provenance p)) ts)))
    (is (not (some (fn [[_ p _]] (= :meta/fragment p)) ts)))
    (is (not (some (fn [[_ p _]] (= :meta/diagnostic p)) ts)))))

;; ---------------------------------------------------------------------------
;; Schema validation
;; ---------------------------------------------------------------------------

(deftest rule-schema-accepts-valid-rule
  (is (rules/valid-rule?
       {:id :rule/t
        :phase :validate
        :match '[[?r :meta/kind :book]]
        :produce {:diagnostic {:severity :info :code :seen-book :subject '?r}}})))

(deftest rule-schema-rejects-unknown-phase
  (is (not (rules/valid-rule?
            {:id :rule/t
             :phase :gibberish
             :match '[[?r :meta/kind :book]]
             :produce {}}))))

(deftest rule-schema-rejects-bad-match-shape
  (is (not (rules/valid-rule?
            {:id :rule/t
             :phase :validate
             :match ['(bogus-predicate ?r)]
             :produce {}}))))

;; ---------------------------------------------------------------------------
;; Compile-time checks
;; ---------------------------------------------------------------------------

(deftest compile-rejects-unbound-variable-in-produce
  (is (thrown? clojure.lang.ExceptionInfo
               (rules/compile-rule
                {:id :rule/t
                 :phase :validate
                 :match '[[?r :meta/kind :book]]
                 :produce {:diagnostic {:severity :warning
                                        :code :missing-thing
                                        :subject '?missing}}}))))

(deftest compile-rejects-unbound-variable-in-guard
  ;; Regression: guards consume bindings, they do not introduce them.
  ;; A variable referenced only by a guard must fail at compile-time.
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"unbound variables in a guard"
       (rules/compile-rule
        {:id :rule/g
         :phase :validate
         :match '[[?r :meta/kind :book]
                  (matches? ?unbound "^Le")]
         :produce {:diagnostic {:severity :info :code :x :subject '?r}}}))))

(deftest compile-rejects-guard-only-binding-leaking-to-produce
  ;; Even more subtle: a variable that appears *only* in a guard should
  ;; not be considered bound for the purpose of :produce either.
  (is (thrown? clojure.lang.ExceptionInfo
               (rules/compile-rule
                {:id :rule/h
                 :phase :validate
                 :match '[[?r :canon/title ?t]
                          (= ?t ?ghost)]
                 :produce {:diagnostic {:severity :info
                                        :code :x
                                        :subject '?ghost}}}))))

(deftest compile-accepts-guard-using-pattern-bound-variable
  ;; Sanity: when the guard variable IS bound by a triple-pattern, the
  ;; rule must still compile without complaint.
  (is (some? (rules/compile-rule
              {:id :rule/ok
               :phase :validate
               :match '[[?r :canon/title ?t]
                        (matches? ?t "^Le")]
               :produce {:diagnostic {:severity :info
                                      :code :starts-with-le
                                      :subject '?r}}}))))

(deftest compile-rejects-malformed-rule
  (is (thrown? clojure.lang.ExceptionInfo
               (rules/compile-rule {:id :rule/t
                                    :phase :validate}))))

(deftest compile-rejects-unknown-action
  (is (thrown? clojure.lang.ExceptionInfo
               (rules/compile-rule
                {:id :rule/t
                 :phase :validate
                 :match '[[?r :meta/kind :book]]
                 :produce {:emit {:foo '?r}}}))))

;; ---------------------------------------------------------------------------
;; Basic pattern matching
;; ---------------------------------------------------------------------------

(deftest match-binds-variables-from-struct
  (let [rule {:id :rule/t1
              :phase :validate
              :match '[[?r :meta/kind :book]]
              :produce {:diagnostic {:severity :info
                                     :code :saw-book
                                     :subject '?r}}}
        r    (book-record :id :r/abc)
        outs (run rule r)]
    (is (= 1 (count outs)))
    (is (= :diagnostic (:kind (first outs))))
    (is (= :r/abc (get-in (first outs) [:value :subject])))))

(deftest match-returns-no-rows-when-pattern-fails
  (let [rule {:id :rule/t2
              :phase :validate
              :match '[[?r :meta/kind :journal]]
              :produce {:diagnostic {:severity :info
                                     :code :saw-journal
                                     :subject '?r}}}
        r    (book-record :kind :book)]
    (is (empty? (run rule r)))))

(deftest match-binds-value-from-assertion
  (let [rule {:id :rule/t3
              :phase :validate
              :match '[[?r :meta/kind :book]
                       [?r :canon/title ?title]]
              :produce {:assert {:subject '?r
                                 :predicate :canon/normalized-title
                                 :value '?title}}}
        r    (book-record :id :r/x :title "Les Misérables")
        outs (run rule r)]
    (is (= 1 (count outs)))
    (is (= :assertion (:kind (first outs))))
    (is (= "Les Misérables" (get-in (first outs) [:value :value])))))

(deftest literal-position-matching
  (let [rule {:id :rule/t4
              :phase :validate
              :match '[[?r :canon/title "Les Misérables"]]
              :produce {:diagnostic {:severity :info :code :match :subject '?r}}}]
    (is (= 1 (count (run rule (book-record :id :r/y :title "Les Misérables")))))
    (is (= 0 (count (run rule (book-record :id :r/z :title "Other")))))))

(deftest wildcard-matches-without-binding
  (let [rule {:id :rule/t5
              :phase :validate
              :match '[[?r :canon/title _]]
              :produce {:diagnostic {:severity :info :code :has-title :subject '?r}}}]
    (is (= 1 (count (run rule (book-record :id :r/k :title "Any")))))))

;; ---------------------------------------------------------------------------
;; Guards (stdlib predicates)
;; ---------------------------------------------------------------------------

(deftest absent-guard-filters-rows
  (let [rule {:id :rule/title-required
              :phase :validate
              :match '[[?r :meta/kind :book]
                       (absent? ?r :canon/title)]
              :produce {:diagnostic {:severity :error
                                     :code :missing-title
                                     :subject '?r}}}]
    (testing "book without title triggers diagnostic"
      (let [outs (run rule (book-record :id :r/nt))]
        (is (= 1 (count outs)))
        (is (= :missing-title (get-in (first outs) [:value :code])))))
    (testing "book with title does not trigger"
      (is (empty? (run rule (book-record :id :r/wt :title "Something")))))))

(deftest present-guard
  (let [rule {:id :rule/t6
              :phase :validate
              :match '[[?r :meta/kind :book]
                       (present? ?r :canon/title)]
              :produce {:diagnostic {:severity :info :code :has-title :subject '?r}}}]
    (is (= 1 (count (run rule (book-record :title "X")))))
    (is (empty? (run rule (book-record))))))

(deftest absent-and-present-see-struct-fields
  ;; Regression: absent?/present? used to query :assertions only, missing
  ;; struct fields like :meta/kind, :meta/source. They now query the
  ;; unified triple-view and observe the full structural vocabulary.
  (testing "present? on :meta/kind is true for any well-formed record"
    (let [rule {:id :rule/has-kind
                :phase :validate
                :match '[[?r :meta/id ?_id]
                         (present? ?r :meta/kind)]
                :produce {:diagnostic {:severity :info :code :has-kind
                                       :subject '?r}}}]
      (is (= 1 (count (run rule (book-record :id :r/k)))))))
  (testing "absent? on :meta/source distinguishes records with and without source"
    (let [rule {:id :rule/no-source
                :phase :validate
                :match '[[?r :meta/id ?_id]
                         (absent? ?r :meta/source)]
                :produce {:diagnostic {:severity :info :code :no-source
                                       :subject '?r}}}
          with    (model/record {:id :r/with :kind :book :source :xml/x})
          without (model/record {:id :r/wo   :kind :book})]
      (is (empty? (run rule with)))
      (is (= 1 (count (run rule without)))))))

(deftest absent-on-meta-fragment-distinguishes-fragmented-records
  (let [rule {:id :rule/no-fragments
              :phase :validate
              :match '[[?r :meta/id ?_id]
                       (absent? ?r :meta/fragment)]
              :produce {:diagnostic {:severity :info :code :no-fragments
                                     :subject '?r}}}
        with    (model/record {:id :r/wf :kind :book
                               :fragments [(model/fragment {:id :frag/x
                                                            :source :xml})]})
        without (model/record {:id :r/wof :kind :book})]
    (is (empty? (run rule with)))
    (is (= 1 (count (run rule without))))))

(deftest matches-guard
  (let [rule {:id :rule/t7
              :phase :validate
              :match '[[?r :canon/title ?t]
                       (matches? ?t "^Les")]
              :produce {:diagnostic {:severity :info :code :starts-with-les :subject '?r}}}]
    (is (= 1 (count (run rule (book-record :id :r/a :title "Les Misérables")))))
    (is (empty? (run rule (book-record :id :r/b :title "Notre Dame"))))))

(deftest in-guard
  (let [rule {:id :rule/t8
              :phase :validate
              :match '[[?r :meta/kind ?k]
                       (in? ?k [:book :journal])]
              :produce {:diagnostic {:severity :info :code :known-kind :subject '?r}}}]
    (is (= 1 (count (run rule (book-record :kind :book)))))
    (is (empty? (run rule (book-record :kind :sculpture))))))

(deftest equality-guard-filters
  (let [rule {:id :rule/t9
              :phase :validate
              :match '[[?r :meta/kind ?k]
                       (= ?k :book)]
              :produce {:diagnostic {:severity :info :code :is-book :subject '?r}}}]
    (is (= 1 (count (run rule (book-record :kind :book)))))
    (is (empty? (run rule (book-record :kind :journal))))))

;; ---------------------------------------------------------------------------
;; Production actions
;; ---------------------------------------------------------------------------

(deftest assert-production-tags-provenance
  (let [rule {:id :rule/annotate
              :phase :normalize
              :match '[[?r :meta/kind :book]]
              :produce {:assert {:subject '?r
                                 :predicate :canon/seen
                                 :value true}}}
        out  (first (run rule (book-record :id :r/p)))]
    (is (= :assertion (:kind out)))
    (is (= :asserted (get-in out [:value :status])))
    (is (= :rule/annotate (get-in out [:value :provenance :rule])))
    (is (= :normalize (get-in out [:value :provenance :pass])))))

(deftest diagnostic-production-tags-provenance
  (let [rule {:id :rule/dx
              :phase :validate
              :match '[[?r :meta/kind :book]
                       (absent? ?r :canon/title)]
              :produce {:diagnostic {:severity :error
                                     :code :missing-title
                                     :subject '?r
                                     :message "No title."}}}
        out  (first (run rule (book-record :id :r/x)))]
    (is (= :diagnostic (:kind out)))
    (is (= :rule/dx (get-in out [:value :provenance :rule])))
    (is (= :validate (get-in out [:value :provenance :pass])))
    (is (= "No title." (get-in out [:value :message])))))

(deftest assert-template-provenance-fields-are-preserved
  ;; Engine fields (:rule, :pass) win, but template-supplied fields like
  ;; :source and :derivation must survive.
  (let [rule {:id :rule/derive
              :phase :infer
              :match '[[?r :meta/kind :book]]
              :produce {:assert {:subject '?r
                                 :predicate :canon/derived
                                 :value true
                                 :provenance {:source :compute/heuristic-x
                                              :derivation [:rule/upstream]}}}}
        out  (first (run rule (book-record :id :r/dp)))
        prov (get-in out [:value :provenance])]
    (is (= :rule/derive (:rule prov))             "engine-supplied :rule wins")
    (is (= :infer       (:pass prov))             "engine-supplied :pass wins")
    (is (= :compute/heuristic-x (:source prov))   "template :source preserved")
    (is (= [:rule/upstream] (:derivation prov))   "template :derivation preserved")))

(deftest assert-template-cannot-override-engine-rule-or-pass
  ;; Even if a malicious or buggy template tries to set :rule/:pass, the
  ;; engine's values must win — otherwise the trace is a lie.
  (let [rule {:id :rule/honest
              :phase :validate
              :match '[[?r :meta/kind :book]]
              :produce {:assert {:subject '?r
                                 :predicate :canon/x
                                 :value 1
                                 :provenance {:rule :rule/imposter
                                              :pass :ingest}}}}
        out  (first (run rule (book-record :id :r/lie)))
        prov (get-in out [:value :provenance])]
    (is (= :rule/honest (:rule prov)))
    (is (= :validate    (:pass prov)))))

(deftest diagnostic-template-provenance-is-also-deep-merged
  (let [rule {:id :rule/d-prov
              :phase :validate
              :match '[[?r :meta/kind :book]]
              :produce {:diagnostic {:severity :info :code :note
                                     :subject '?r
                                     :provenance {:source :hand/curated}}}}
        out  (first (run rule (book-record :id :r/dp2)))
        prov (get-in out [:value :provenance])]
    (is (= :rule/d-prov (:rule prov)))
    (is (= :validate    (:pass prov)))
    (is (= :hand/curated (:source prov)))))

(deftest repair-production
  (let [rule {:id :rule/fix
              :phase :repair
              :match '[[?r :meta/kind :book]]
              :produce {:repair {:description "Attach canonical title."
                                 :operation :copy-from
                                 :basis :dc/alternative}}}
        out  (first (run rule (book-record)))]
    (is (= :repair (:kind out)))
    (is (= :copy-from (get-in out [:value :operation])))))

(deftest multiple-actions-per-rule
  (let [rule {:id :rule/multi
              :phase :validate
              :match '[[?r :meta/kind :book]]
              :produce {:assert {:subject '?r :predicate :canon/seen :value true}
                        :diagnostic {:severity :info
                                     :code :seen-book
                                     :subject '?r}}}
        outs (run rule (book-record))]
    (is (= 2 (count outs)))
    (is (= #{:assertion :diagnostic} (set (map :kind outs))))))

;; ---------------------------------------------------------------------------
;; Joins: multiple patterns contribute shared bindings
;; ---------------------------------------------------------------------------

(deftest multiple-patterns-cartesian-join
  (let [r (model/record
           {:id :r/m :kind :book
            :assertions [(model/assertion {:subject :r/m :predicate :canon/title :value "A"})
                         (model/assertion {:subject :r/m :predicate :canon/title :value "B"})]})
        rule {:id :rule/join
              :phase :validate
              :match '[[?r :meta/kind :book]
                       [?r :canon/title ?t]]
              :produce {:assert {:subject '?r
                                 :predicate :canon/seen-title
                                 :value '?t}}}
        outs (run rule r)]
    (is (= 2 (count outs)))
    (is (= #{"A" "B"} (set (map #(get-in % [:value :value]) outs))))))

;; ---------------------------------------------------------------------------
;; The produced assertions validate against the model schema
;; ---------------------------------------------------------------------------

(deftest produced-assertions-are-schema-valid
  (let [rule {:id :rule/emit
              :phase :infer
              :match '[[?r :meta/kind :book]
                       [?r :canon/title ?t]]
              :produce {:assert {:subject '?r
                                 :predicate :canon/normalized-title
                                 :value '?t}}}
        outs (run rule (book-record :id :r/v :title "Title"))]
    (is (every? #(model/valid-assertion? (:value %))
                (filter #(= :assertion (:kind %)) outs)))))

;; ---------------------------------------------------------------------------
;; compiled-rule constructor
;;
;; The escape hatch for external compilers (currently only the mapping
;; compiler). We test the contract directly here so the validation
;; branches are not solely exercised through downstream callers.
;; ---------------------------------------------------------------------------

(deftest compiled-rule-without-doc-omits-doc-key
  (let [cr (rules/compiled-rule {:id :rule/cr1
                                 :phase :normalize
                                 :runner (fn [_record] [])})]
    (is (= :rule/cr1 (:id cr)))
    (is (= :normalize (:phase cr)))
    (is (not (contains? cr :doc)))))

(deftest compiled-rule-with-doc-carries-it-through
  (let [cr (rules/compiled-rule {:id :rule/cr2
                                 :phase :infer
                                 :doc "Hand-rolled rule."
                                 :runner (fn [_record] [])})]
    (is (= "Hand-rolled rule." (:doc cr)))))

(deftest compiled-rule-output-is-runnable-via-apply-rule
  ;; The whole point of compiled-rule is that it produces something
  ;; apply-rule accepts. If this round-trip ever breaks, the mapping
  ;; compiler breaks with it.
  (let [productions [{:kind :diagnostic
                      :value (model/diagnostic {:severity :info
                                                :code :hand-rolled
                                                :subject :r/x})}]
        cr (rules/compiled-rule {:id :rule/cr3
                                 :phase :validate
                                 :runner (fn [_record] productions)})]
    (is (= productions (rules/apply-rule cr (model/record {:id :r/x :kind :book}))))))

(deftest compiled-rule-rejects-non-keyword-id
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"keyword :id"
                        (rules/compiled-rule {:id "rule/oops"
                                              :phase :normalize
                                              :runner (fn [_] [])})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"keyword :id"
                        (rules/compiled-rule {:id nil
                                              :phase :normalize
                                              :runner (fn [_] [])}))))

(deftest compiled-rule-rejects-missing-phase
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #":phase"
                        (rules/compiled-rule {:id :rule/cr
                                              :phase nil
                                              :runner (fn [_] [])}))))

(deftest compiled-rule-rejects-non-callable-runner
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"callable :runner"
                        (rules/compiled-rule {:id :rule/cr
                                              :phase :normalize
                                              :runner nil})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"callable :runner"
                        (rules/compiled-rule {:id :rule/cr
                                              :phase :normalize
                                              :runner :not-a-fn}))))

;; ---------------------------------------------------------------------------
;; compile-rules and apply-rule error paths
;; ---------------------------------------------------------------------------

(deftest compile-rules-returns-vector-of-runnable-rules
  (let [rs   [{:id :rule/a :phase :validate
               :match '[[?r :meta/kind :book]]
               :produce {:diagnostic {:severity :info :code :a :subject '?r}}}
              {:id :rule/b :phase :validate
               :match '[[?r :meta/kind :journal]]
               :produce {:diagnostic {:severity :info :code :b :subject '?r}}}]
        crs  (rules/compile-rules rs)
        outs (mapcat #(rules/apply-rule % (book-record :id :r/k :kind :book)) crs)]
    (is (vector? crs))
    (is (= 2 (count crs)))
    (is (= [:a] (map #(get-in % [:value :code]) outs)))))

(deftest compile-rules-fails-fast-on-first-invalid
  (is (thrown? clojure.lang.ExceptionInfo
               (rules/compile-rules
                [{:id :rule/ok :phase :validate
                  :match '[[?r :meta/kind :book]]
                  :produce {:diagnostic {:severity :info :code :ok :subject '?r}}}
                 {:id :rule/bad :phase :gibberish
                  :match '[[?r :meta/kind :book]]
                  :produce {}}]))))

(deftest apply-rule-rejects-uncompiled-rule
  ;; Calling apply-rule on a raw rule (without ::runner) must fail with
  ;; a clear error rather than silently returning nothing.
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"not compiled"
                        (rules/apply-rule {:id :rule/raw :phase :validate}
                                          (book-record)))))

;; ---------------------------------------------------------------------------
;; not= guard
;; ---------------------------------------------------------------------------

(deftest not-equal-guard-filters
  ;; The `not=` stdlib predicate is the natural pair of `=`; tested here
  ;; so the symmetry isn't latently broken by a refactor.
  (let [rule {:id :rule/ne
              :phase :validate
              :match '[[?r :meta/kind ?k]
                       (not= ?k :journal)]
              :produce {:diagnostic {:severity :info :code :not-journal :subject '?r}}}]
    (is (= 1 (count (run rule (book-record :kind :book)))))
    (is (empty? (run rule (book-record :kind :journal))))))

;; ---------------------------------------------------------------------------
;; supported-actions
;; ---------------------------------------------------------------------------

(deftest supported-actions-enumerates-the-stdlib
  (is (= #{:assert :diagnostic :repair :entity} (rules/supported-actions))))

;; ---------------------------------------------------------------------------
;; Validate-rule explanation surface
;; ---------------------------------------------------------------------------

(deftest validate-rule-returns-nil-for-valid-and-explains-otherwise
  (let [ok {:id :rule/ok :phase :validate
            :match '[[?r :meta/kind :book]]
            :produce {:diagnostic {:severity :info :code :seen :subject '?r}}}
        ko {:id :rule/ko :phase :gibberish
            :match '[[?r :meta/kind :book]]
            :produce {}}]
    (is (nil? (rules/validate-rule ok)))
    (is (some? (rules/validate-rule ko)))))

;; ---------------------------------------------------------------------------
;; Phase-dependent default status (ADR 0005)
;; ---------------------------------------------------------------------------

(deftest infer-phase-defaults-to-proposed
  (let [rule {:id :rule/inf
              :phase :infer
              :match '[[?r :meta/kind :book]]
              :produce {:assert {:subject '?r
                                 :predicate :canon/guess
                                 :value true}}}
        out  (first (run rule (book-record :id :r/i)))]
    (is (= :proposed (get-in out [:value :status])))))

(deftest repair-phase-defaults-to-proposed
  (let [rule {:id :rule/rp
              :phase :repair
              :match '[[?r :meta/kind :book]]
              :produce {:assert {:subject '?r
                                 :predicate :canon/guess
                                 :value true}}}
        out  (first (run rule (book-record :id :r/q)))]
    (is (= :proposed (get-in out [:value :status])))))

;; ---------------------------------------------------------------------------
;; Produce template substitution distinguishes presence from truth
;; ---------------------------------------------------------------------------

(deftest produce-preserves-false-and-nil-bound-values
  ;; A variable legitimately bound by normal matching to a *falsey* value (false
  ;; or nil) must reach the produced assertion as that value — `substitute` must
  ;; test a binding's *presence*, not its *truth*. Exercised through the public
  ;; compile-rule / apply-rule path (no access to the private `substitute`).
  (letfn [(copy-out [v]
            (let [rec  (model/record
                        {:id :rec/a :kind :test
                         :assertions [(model/assertion {:subject :rec/a
                                                        :predicate :flag/v
                                                        :value v})]})
                  rule {:id :rule/copy :phase :normalize
                        :match '[[?s :flag/v ?v]]
                        :produce {:assert {:subject '?s :predicate :out/copy :value '?v}}}]
              (->> (run rule rec)
                   (filter #(= :out/copy (get-in % [:value :predicate])))
                   first)))]
    (testing "a variable bound to false is substituted as false (not treated as unbound)"
      (let [out (copy-out false)]
        (is (= :assertion (:kind out)))
        (is (false? (get-in out [:value :value])))))
    (testing "a variable bound to nil is substituted as nil"
      (let [out (copy-out nil)]
        (is (= :assertion (:kind out)))
        (is (nil? (get-in out [:value :value])))))
    (testing "a genuinely unbound :produce variable is still rejected (at compile time)"
      ;; substitute's runtime "Unbound variable in produce template" guard is kept
      ;; unchanged; for data-form rules it is preempted by the compile-time
      ;; bound-variable check, so an unbound produce var never reaches production.
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"unbound"
           (rules/compile-rule
            {:id :rule/bad :phase :normalize
             :match '[[?s :flag/v ?v]]
             :produce {:assert {:subject '?s :predicate :out/x :value '?missing}}}))))))

(ns palomar.model-test
  "Unit tests for the canonical model: schemas, constructors, predicates.
   Round-trip behavior is covered by palomar.model.round-trip-test."
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [palomar.model :as model]))

;; ---------------------------------------------------------------------------
;; Structural vocabulary
;; ---------------------------------------------------------------------------

(deftest structural-vocabulary-contents
  (testing "the six structural predicates are defined"
    (is (= :meta/id model/meta-id))
    (is (= :meta/kind model/meta-kind))
    (is (= :meta/source model/meta-source))
    (is (= :meta/fragment model/meta-fragment))
    (is (= :meta/diagnostic model/meta-diagnostic))
    (is (= :meta/provenance model/meta-provenance)))
  (testing "structural-vocabulary is a closed set of six predicates"
    (is (set? model/structural-vocabulary))
    (is (= 6 (count model/structural-vocabulary))))
  (testing "structural? discriminates core vs plugin predicates"
    (is (model/structural? :meta/id))
    (is (model/structural? :meta/provenance))
    (is (not (model/structural? :dc/title)))
    (is (not (model/structural? :canon/agent)))
    (is (not (model/structural? :random/keyword)))))

;; ---------------------------------------------------------------------------
;; Status families
;; ---------------------------------------------------------------------------

(deftest status-families-are-disjoint
  (is (empty? (set/intersection
               model/machine-statuses
               model/workflow-statuses)))
  (is (= 7 (count model/statuses)))
  (is (= (set/union model/machine-statuses model/workflow-statuses)
         model/statuses)))

(deftest machine-and-workflow-predicates
  (is (model/machine-status? :asserted))
  (is (model/machine-status? :superseded))
  (is (not (model/machine-status? :accepted)))
  (is (model/workflow-status? :accepted))
  (is (model/workflow-status? :needs-review))
  (is (not (model/workflow-status? :proposed))))

;; ---------------------------------------------------------------------------
;; Primitive / tagged value predicates
;; ---------------------------------------------------------------------------

(deftest primitive-value-predicate
  (is (model/primitive-value? "hello"))
  (is (model/primitive-value? 42))
  (is (model/primitive-value? 3.14))
  (is (model/primitive-value? true))
  (is (model/primitive-value? :some/keyword))
  (is (model/primitive-value? #uuid "00000000-0000-0000-0000-000000000000"))
  (is (model/primitive-value? #inst "2026-01-01"))
  (is (not (model/primitive-value? {:value/kind :reference :value/target :r/id1})))
  (is (not (model/primitive-value? [1 2 3]))))

(deftest tagged-value-predicates
  (let [r (model/reference :record/r42)
        s (model/structured {:first "Victor" :last "Hugo"})
        u (model/uncertain ["1823" "1832"])]
    (is (model/reference-value? r))
    (is (model/structured-value? s))
    (is (model/uncertain-value? u))
    (is (not (model/reference-value? s)))
    (is (not (model/structured-value? u)))
    (is (not (model/uncertain-value? r)))
    (is (not (model/reference-value? "a string")))))

;; ---------------------------------------------------------------------------
;; Value constructors
;; ---------------------------------------------------------------------------

(deftest reference-constructor
  (let [r1 (model/reference :record/r42)
        r2 (model/reference :record/r42 :creator)]
    (is (= {:value/kind :reference :value/target :record/r42} r1))
    (is (= {:value/kind :reference :value/target :record/r42 :value/role :creator} r2))
    (is (model/valid-value? r1))
    (is (model/valid-value? r2))))

(deftest structured-constructor
  (let [s (model/structured {:first "Victor" :last "Hugo"})]
    (is (= :structured (:value/kind s)))
    (is (= {:first "Victor" :last "Hugo"} (:value/fields s)))
    (is (model/valid-value? s))))

(deftest uncertain-constructor
  (let [u1 (model/uncertain ["1823" "1832"])
        u2 (model/uncertain ["1823" "1832"] :ambiguous-source)]
    (is (= :uncertain (:value/kind u1)))
    (is (= ["1823" "1832"] (:value/alternatives u1)))
    (is (= :ambiguous-source (:value/basis u2)))
    (is (model/valid-value? u1))
    (is (model/valid-value? u2))))

(deftest nested-values
  (testing "structured values can contain other tagged values"
    (let [nested (model/structured
                  {:name (model/structured {:first "Victor" :last "Hugo"})
                   :birth (model/uncertain ["1802" "1803"])})]
      (is (model/valid-value? nested)))))

;; ---------------------------------------------------------------------------
;; Assertion constructor + defaults
;; ---------------------------------------------------------------------------

(deftest assertion-constructor-applies-defaults
  (let [a (model/assertion {:subject :record/r1
                            :predicate :canon/title
                            :value "Les Misérables"})]
    (is (= :record/r1 (:subject a)))
    (is (= :canon/title (:predicate a)))
    (is (= "Les Misérables" (:value a)))
    (is (= 1.0 (:confidence a)))
    (is (= :asserted (:status a)))
    (is (not (contains? a :provenance)))
    (is (model/valid-assertion? a))))

(deftest assertion-constructor-overrides-defaults
  (let [a (model/assertion {:subject :record/r1
                            :predicate :canon/title
                            :value "Untitled"
                            :confidence 0.3
                            :status :proposed
                            :provenance {:rule :title-guess
                                         :pass :infer}})]
    (is (= 0.3 (:confidence a)))
    (is (= :proposed (:status a)))
    (is (= {:rule :title-guess :pass :infer} (:provenance a)))
    (is (model/valid-assertion? a))))

;; ---------------------------------------------------------------------------
;; Diagnostic + Repair
;; ---------------------------------------------------------------------------

(deftest diagnostic-constructor
  (let [d (model/diagnostic {:severity :error
                             :code :missing-title
                             :subject :record/r1
                             :message "Record has no title."})]
    (is (= :error (:severity d)))
    (is (= :missing-title (:code d)))
    (is (= :record/r1 (:subject d)))
    (is (= "Record has no title." (:message d)))
    (is (model/valid-diagnostic? d))))

(deftest repair-constructor
  (let [r (model/repair {:description "Copy alternative title."
                         :operation :copy-from
                         :basis :dc/alternative
                         :applicable? true
                         :safe? true})]
    (is (= :copy-from (:operation r)))
    (is (true? (:safe? r)))
    (is (model/valid-repair? r))))

(deftest diagnostic-with-repairs
  (let [d (model/diagnostic {:severity :warning
                             :code :date-normalizable
                             :subject :record/r1
                             :repairs [(model/repair {:description "Normalize to ISO-8601."
                                                      :operation :normalize-date})]})]
    (is (= 1 (count (:repairs d))))
    (is (model/valid-diagnostic? d))))

;; ---------------------------------------------------------------------------
;; Record constructor
;; ---------------------------------------------------------------------------

(deftest record-constructor-minimum
  (let [r (model/record {:id :record/r1 :kind :book})]
    (is (= :record/r1 (:id r)))
    (is (= :book (:kind r)))
    (is (not (contains? r :assertions)))
    (is (model/valid-record? r))))

(deftest record-constructor-with-content
  (let [a (model/assertion {:subject :record/r1
                            :predicate :canon/title
                            :value "Les Misérables"})
        d (model/diagnostic {:severity :info
                             :code :normalized
                             :subject :record/r1})
        r (model/record {:id :record/r1
                         :kind :book
                         :source "file:///tmp/sample.xml"
                         :assertions [a]
                         :diagnostics [d]
                         :provenance {:source "file:///tmp/sample.xml"
                                      :pass :ingest}})]
    (is (= 1 (count (:assertions r))))
    (is (= 1 (count (:diagnostics r))))
    (is (model/valid-record? r))))

;; ---------------------------------------------------------------------------
;; Status predicates
;; ---------------------------------------------------------------------------

(deftest status-predicates
  (let [mk (fn [status] (model/assertion {:subject :r/id1
                                          :predicate :p
                                          :value "v"
                                          :status status}))]
    (is (model/asserted?     (mk :asserted)))
    (is (model/proposed?     (mk :proposed)))
    (is (model/retracted?    (mk :retracted)))
    (is (model/superseded?   (mk :superseded)))
    (is (model/accepted?     (mk :accepted)))
    (is (model/rejected?     (mk :rejected)))
    (is (model/needs-review? (mk :needs-review)))
    (is (not (model/asserted? (mk :proposed))))
    (is (not (model/accepted? (mk :asserted))))))

(deftest in-force-and-pending
  (let [mk (fn [status] (model/assertion {:subject :r/id1 :predicate :p
                                          :value "v" :status status}))]
    (is (model/in-force? (mk :asserted)))
    (is (model/in-force? (mk :accepted)))
    (is (not (model/in-force? (mk :proposed))))
    (is (not (model/in-force? (mk :rejected))))
    (is (model/pending? (mk :proposed)))
    (is (model/pending? (mk :needs-review)))
    (is (not (model/pending? (mk :asserted))))
    (is (not (model/pending? (mk :rejected))))))

;; ---------------------------------------------------------------------------
;; Simple queries
;; ---------------------------------------------------------------------------

(deftest assertions-for-returns-matches
  (let [a1 (model/assertion {:subject :r/id1 :predicate :canon/title :value "A"})
        a2 (model/assertion {:subject :r/id1 :predicate :canon/title :value "B"})
        a3 (model/assertion {:subject :r/id1 :predicate :canon/agent :value "Hugo"})
        r  (model/record {:id :r/id1 :kind :book
                          :assertions [a1 a2 a3]})]
    (is (= 2 (count (model/assertions-for r :canon/title))))
    (is (= 1 (count (model/assertions-for r :canon/agent))))
    (is (= 0 (count (model/assertions-for r :canon/date))))
    (is (model/has-assertion? r :canon/title))
    (is (not (model/has-assertion? r :canon/date)))))

;; ---------------------------------------------------------------------------
;; Invalid data is rejected
;; ---------------------------------------------------------------------------

(deftest invalid-data-rejected
  (testing "record missing :kind is invalid"
    (is (not (model/valid-record? {:id :r/id1}))))
  (testing "assertion missing :value is invalid"
    (is (not (model/valid-assertion? {:subject :r/id1 :predicate :p}))))
  (testing "diagnostic with unknown severity is invalid"
    (is (not (model/valid-diagnostic? {:severity :catastrophic
                                       :code :x
                                       :subject :r/id1}))))
  (testing "confidence out of range is invalid"
    (let [bad (assoc (model/assertion {:subject :r/id1
                                       :predicate :p
                                       :value "v"})
                     :confidence 1.5)]
      (is (not (model/valid-assertion? bad)))))
  (testing "reference value without :value/target is invalid"
    (is (not (model/valid-value? {:value/kind :reference})))))

;; ---------------------------------------------------------------------------
;; Finite-double constraint on Primitive values
;; ---------------------------------------------------------------------------

(deftest finite-double-rejected-by-validator
  (let [mk-assert (fn [v] {:subject :r/id1 :predicate :p :value v
                           :confidence 1.0 :status :asserted})]
    (testing "regular doubles validate"
      (is (model/valid-assertion? (mk-assert 3.14))))
    (testing "NaN is rejected at validate time"
      (is (not (model/valid-assertion? (mk-assert ##NaN)))))
    (testing "positive infinity is rejected"
      (is (not (model/valid-assertion? (mk-assert ##Inf)))))
    (testing "negative infinity is rejected"
      (is (not (model/valid-assertion? (mk-assert ##-Inf)))))))

(deftest finite-double-predicate
  (is (model/finite-double? 0.0))
  (is (model/finite-double? -1.5))
  (is (not (model/finite-double? ##NaN)))
  (is (not (model/finite-double? ##Inf)))
  (is (not (model/finite-double? ##-Inf)))
  (is (not (model/finite-double? 42)))
  (is (not (model/finite-double? "x"))))

;; ---------------------------------------------------------------------------
;; Cross-field consistency
;; ---------------------------------------------------------------------------

(deftest record-consistent-on-record-id
  (let [a (model/assertion {:subject :r/r1 :predicate :p :value 1})
        r (model/record {:id :r/r1 :kind :book :assertions [a]})]
    (is (model/record-consistent? r))
    (is (nil? (model/explain-consistency r)))))

(deftest record-inconsistent-when-subject-mismatches-id
  (let [a (model/assertion {:subject :r/wrong :predicate :p :value 1})
        r (model/record {:id :r/right :kind :book :assertions [a]})]
    (is (not (model/record-consistent? r)))
    (let [explain (model/explain-consistency r)]
      (is (some? explain))
      (is (= :r/right (:record-id explain)))
      (is (= 1 (count (:bad-assertions explain)))))))

(deftest record-consistent-with-fragment-subjects
  (let [frag (model/fragment {:id :frag/a :source :xml})
        a    (model/assertion {:subject :frag/a :predicate :canon/note
                               :value "fragment-level"})
        r    (model/record {:id :r/main :kind :book
                            :fragments [frag]
                            :assertions [a]})]
    (is (model/record-consistent? r)
        "subject = fragment id is allowed because fragments are addressable")))

(deftest record-inconsistent-with-foreign-diagnostic-subject
  (let [d (model/diagnostic {:severity :error :code :x :subject :r/foreign})
        r (model/record {:id :r/local :kind :book :diagnostics [d]})]
    (is (not (model/record-consistent? r)))
    (is (= 1 (count (:bad-diagnostics (model/explain-consistency r)))))))

(deftest known-subjects-includes-id-and-fragments
  (let [frag (model/fragment {:id :frag/x :source :xml})
        r    (model/record {:id :r/m :kind :book :fragments [frag]})]
    (is (= #{:r/m :frag/x} (model/known-subjects r)))))

;; ---------------------------------------------------------------------------
;; Fragment identity (ADR 0012)
;; ---------------------------------------------------------------------------

(deftest mint-fragment-id-worked-examples
  (testing "single-level locator from ADR 0012"
    (is (= :frag/record.r42.dc-title.0
           (model/mint-fragment-id :record/r42 [:dc/title 0])))
    (is (= :frag/record.r42.dc-title.1
           (model/mint-fragment-id :record/r42 [:dc/title 1]))))
  (testing "nested locator (CIDOC-style)"
    (is (= :frag/record.obj1.crm-P108.0.crm-P14.0
           (model/mint-fragment-id :record/obj1
                                   [:crm/P108 0 :crm/P14 0]))))
  (testing "predicate names with hyphens round-trip safely when namespace is hyphen-free"
    (is (= :frag/record.r1.foo-bar-baz.0
           (model/mint-fragment-id :record/r1 [:foo/bar-baz 0])))))

(deftest mint-fragment-id-returns-frag-namespaced-keyword
  (let [id (model/mint-fragment-id :record/r [:p/q 0])]
    (is (keyword? id))
    (is (= "frag" (namespace id)))))

(deftest mint-fragment-id-is-deterministic
  (testing "same inputs produce same output across calls"
    (is (= (model/mint-fragment-id :record/r42 [:dc/title 0])
           (model/mint-fragment-id :record/r42 [:dc/title 0]))))
  (testing "distinct occurrence indices produce distinct ids"
    (is (not= (model/mint-fragment-id :record/r [:p/q 0])
              (model/mint-fragment-id :record/r [:p/q 1])))))

(deftest mint-fragment-id-rejects-bad-record-id
  (testing "string record-id rejected"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"namespaced keyword record-id"
                          (model/mint-fragment-id "r42" [:dc/title 0]))))
  (testing "bare keyword record-id rejected"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"namespaced keyword record-id"
                          (model/mint-fragment-id :r42 [:dc/title 0]))))
  (testing "uuid record-id rejected"
    (is (thrown? clojure.lang.ExceptionInfo
                 (model/mint-fragment-id (random-uuid) [:dc/title 0])))))

(deftest mint-fragment-id-rejects-bad-locator
  (testing "empty locator rejected"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"non-empty"
                          (model/mint-fragment-id :record/r []))))
  (testing "non-sequential locator rejected"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"sequential"
                          (model/mint-fragment-id :record/r {:dc/title 0}))))
  (testing "odd-length locator rejected"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"alternate"
                          (model/mint-fragment-id :record/r [:dc/title]))))
  (testing "bare keyword in predicate position rejected"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"namespaced keyword"
                          (model/mint-fragment-id :record/r [:title 0]))))
  (testing "non-keyword in predicate position rejected"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"namespaced keyword"
                          (model/mint-fragment-id :record/r ["dc/title" 0]))))
  (testing "negative integer in index position rejected"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"non-negative integer"
                          (model/mint-fragment-id :record/r [:dc/title -1]))))
  (testing "non-integer in index position rejected"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"non-negative integer"
                          (model/mint-fragment-id :record/r [:dc/title "0"])))))

(deftest mint-fragment-id-rejects-hyphen-in-predicate-namespace
  ;; '-' glues a predicate's namespace to its name (:dc/title -> "dc-title").
  ;; A hyphen in the *namespace* is ambiguous and breaks injectivity:
  ;; :marc-xml/title would encode to "marc-xml-title", the same string as the
  ;; legal :marc/xml-title — two distinct predicates collapsing onto one
  ;; fragment id (silent data loss). Reject the ambiguous case at mint.
  (testing "hyphen in a predicate namespace is rejected"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"namespace must not contain"
                          (model/mint-fragment-id :record/r1 [:marc-xml/title 0])))))

(deftest mint-fragment-id-rejects-dot-in-segments
  ;; '.' separates segments in the fragment-id path, so a dot in any component
  ;; injects a fake boundary and breaks both injectivity and round-trip parse.
  (testing "dot in the record-id namespace is rejected"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Record-id must not contain"
                          (model/mint-fragment-id :rec.ns/r1 [:dc/title 0]))))
  (testing "dot in the record-id name is rejected"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Record-id must not contain"
                          (model/mint-fragment-id :record/r.1 [:dc/title 0]))))
  (testing "dot in a predicate namespace is rejected"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"predicate must not contain"
                          (model/mint-fragment-id :record/r1 [:my.ns/title 0]))))
  (testing "dot in a predicate name is rejected"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"predicate must not contain"
                          (model/mint-fragment-id :record/r1 [:dc/title.x 0])))))

(deftest mint-fragment-id-allows-hyphen-in-names
  ;; Hyphens are ambiguous only in the *namespace* of a locator predicate (the
  ;; ns/name separator in a fragment id). Predicate names — and the record-id —
  ;; may carry them. Guards against over-restricting.
  (testing "a hyphen in a predicate name is accepted"
    (is (= :frag/record.r1.dc-alternative-title.0
           (model/mint-fragment-id :record/r1 [:dc/alternative-title 0]))))
  (testing "hyphens in the record-id are accepted"
    (is (= :frag/my-rec.r-1.dc-title.0
           (model/mint-fragment-id :my-rec/r-1 [:dc/title 0])))))

;; ---------------------------------------------------------------------------
;; Provenance and Fragment constructors
;;
;; Direct unit tests for the two constructors that are otherwise only
;; exercised indirectly through Record/Assertion construction. Each
;; cond-> branch is hit, so a regression in defaulting shows up here.
;; ---------------------------------------------------------------------------

(deftest provenance-constructor-empty
  (let [p (model/provenance {})]
    (is (= {} p))
    (is (model/valid-provenance? p))))

(deftest provenance-constructor-full
  (let [ts #inst "2026-01-01"
        p  (model/provenance {:source :xml/sample
                              :pass :ingest
                              :rule :rule/x
                              :derivation [:r/a :r/b]
                              :timestamp ts})]
    (is (= :xml/sample (:source p)))
    (is (= :ingest (:pass p)))
    (is (= :rule/x (:rule p)))
    (is (= [:r/a :r/b] (:derivation p)))
    (is (= ts (:timestamp p)))
    (is (model/valid-provenance? p))))

(deftest provenance-constructor-coerces-derivation-to-vector
  ;; derivation may be passed as a list; the constructor must vectorize.
  (let [p (model/provenance {:derivation (list :r/a :r/b)})]
    (is (vector? (:derivation p)))
    (is (= [:r/a :r/b] (:derivation p)))))

(deftest fragment-constructor-minimum-and-full
  (testing "minimum: id + source only"
    (let [f (model/fragment {:id :frag/x :source :xml/sample})]
      (is (= :frag/x (:id f)))
      (is (= :xml/sample (:source f)))
      (is (not (contains? f :locator)))
      (is (not (contains? f :raw)))
      (is (model/valid-fragment? f))))
  (testing "with locator and raw"
    (let [f (model/fragment {:id :frag/y :source :xml/sample
                             :locator [:dc/title 0]
                             :raw "<title>Hi</title>"})]
      (is (= [:dc/title 0] (:locator f)))
      (is (= "<title>Hi</title>" (:raw f)))
      (is (model/valid-fragment? f)))))

;; ---------------------------------------------------------------------------
;; Explain helpers
;;
;; The `explain-*` family is the user-facing complement to `valid-*?`.
;; A smoke test per shape is enough — Malli's explanation format is
;; tested upstream.
;; ---------------------------------------------------------------------------

(deftest explain-helpers-return-nil-for-valid-inputs
  (let [a (model/assertion {:subject :r/x :predicate :p :value 1})
        d (model/diagnostic {:severity :info :code :c :subject :r/x})
        r (model/record {:id :r/x :kind :book})]
    (is (nil? (model/explain-assertion a)))
    (is (nil? (model/explain-diagnostic d)))
    (is (nil? (model/explain-record r)))
    (is (nil? (model/explain-value "scalar")))))

(deftest explain-helpers-return-explanation-for-invalid-inputs
  (is (some? (model/explain-assertion {:subject :r/x})))
  (is (some? (model/explain-diagnostic {:severity :nope :code :c :subject :r/x})))
  (is (some? (model/explain-record {:id :r/x})))
  (is (some? (model/explain-value {:value/kind :reference})))) ; missing :value/target

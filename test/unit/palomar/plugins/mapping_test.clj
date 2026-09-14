(ns palomar.plugins.mapping-test
  "Unit tests for the mapping schema and compiler (Sprint 5 M4).

   Covers the MappingRule schema (including cross-field invariants),
   the flat- and qualified-mapping compilers, transform application
   and failure handling, all three `:on-empty` branches, non-primitive
   passthrough, the compiler's input validation, and the integration
   of compiled mapping rules with the runtime."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.model :as model]
            [palomar.plugins.mapping :as mapping]
            [palomar.plugins.transforms :as tx]
            [palomar.rules :as rules]
            [palomar.runtime :as runtime]))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(def stdlib tx/core-transforms)

(def minimal-mapping
  {:mapping/id   :map/x
   :mapping/from :native/x
   :mapping/to   :canon/x})

(defn- record-with
  "Build a record with one assertion per value in `values`, all on the
   given native predicate. Convenience for compiler tests."
  [predicate values]
  (model/record
   {:id         :record/r1
    :kind       :test
    :assertions (mapv (fn [v]
                        (model/assertion
                         {:subject   :record/r1
                          :predicate predicate
                          :value     v}))
                      values)}))

(defn- empty-record []
  (model/record {:id :record/r1 :kind :test}))

;; ---------------------------------------------------------------------------
;; Schema
;; ---------------------------------------------------------------------------

(deftest schema-accepts-minimal
  (is (mapping/valid-mapping? minimal-mapping))
  (is (nil? (mapping/explain-mapping minimal-mapping))))

(deftest schema-accepts-full-shape
  (let [full {:mapping/id         :map/full
              :mapping/from       :native/full
              :mapping/to         :canon/full
              :mapping/transform  [:trim :lowercase]
              :mapping/qualifier  {:from :native/lang :as :canon/lang}
              :mapping/on-empty   :diagnostic
              :mapping/confidence 0.8
              :mapping/doc        "documentation"}]
    (is (mapping/valid-mapping? full))))

(deftest schema-rejects-missing-required
  (is (not (mapping/valid-mapping? (dissoc minimal-mapping :mapping/id))))
  (is (not (mapping/valid-mapping? (dissoc minimal-mapping :mapping/from))))
  (is (not (mapping/valid-mapping? (dissoc minimal-mapping :mapping/to)))))

(deftest schema-rejects-unknown-keys
  (testing "closed shape catches arbitrary unsanctioned keys (typos)"
    (is (not (mapping/valid-mapping?
              (assoc minimal-mapping :mapping/strangelet 1))))))

(deftest schema-rejects-non-keyword-id-or-predicates
  (is (not (mapping/valid-mapping? (assoc minimal-mapping :mapping/id "string"))))
  (is (not (mapping/valid-mapping? (assoc minimal-mapping :mapping/from "string"))))
  (is (not (mapping/valid-mapping? (assoc minimal-mapping :mapping/to 42)))))

(deftest schema-rejects-bad-transform-shape
  (testing ":mapping/transform must be a vector of keywords"
    (is (not (mapping/valid-mapping?
              (assoc minimal-mapping :mapping/transform :trim))))
    (is (not (mapping/valid-mapping?
              (assoc minimal-mapping :mapping/transform ["string-name"]))))
    (is (not (mapping/valid-mapping?
              (assoc minimal-mapping :mapping/transform '(:trim)))))))

(deftest schema-rejects-bad-on-empty
  (is (not (mapping/valid-mapping?
            (assoc minimal-mapping :mapping/on-empty :bogus)))))

(deftest schema-rejects-bad-qualifier
  (testing "qualifier must carry exactly :from and :as"
    (is (not (mapping/valid-mapping?
              (assoc minimal-mapping :mapping/qualifier {:from :x}))))
    (is (not (mapping/valid-mapping?
              (assoc minimal-mapping :mapping/qualifier {:as :y}))))
    (is (not (mapping/valid-mapping?
              (assoc minimal-mapping :mapping/qualifier {:from :x :as :y :extra 1}))))
    (is (not (mapping/valid-mapping?
              (assoc minimal-mapping :mapping/qualifier {:from "x" :as :y}))))))

(deftest schema-rejects-bad-confidence
  (is (not (mapping/valid-mapping? (assoc minimal-mapping :mapping/confidence 2.0))))
  (is (not (mapping/valid-mapping? (assoc minimal-mapping :mapping/confidence -0.1))))
  (is (not (mapping/valid-mapping? (assoc minimal-mapping :mapping/confidence "0.5")))))

(deftest schema-cross-field-on-empty-default-biconditional
  (testing ":on-empty :default without :mapping/default fails"
    (is (not (mapping/valid-mapping?
              (assoc minimal-mapping :mapping/on-empty :default)))))
  (testing ":on-empty :default with :mapping/default succeeds"
    (is (mapping/valid-mapping?
         (assoc minimal-mapping
                :mapping/on-empty :default
                :mapping/default  "fallback"))))
  (testing ":mapping/default without :on-empty :default fails (biconditional)"
    (is (not (mapping/valid-mapping?
              (assoc minimal-mapping :mapping/default "fallback")))))
  (testing "nil default rejected — would emit a shape-invalid assertion (M4.C tightening)"
    (is (not (mapping/valid-mapping?
              (assoc minimal-mapping
                     :mapping/on-empty :default
                     :mapping/default  nil))))))

;; ---------------------------------------------------------------------------
;; Rule-id derivation
;; ---------------------------------------------------------------------------

(deftest mapping-rule-id-uses-name-portion
  (is (= :rule.from-mapping/dc-title
         (mapping/mapping-rule-id :map/dc-title)))
  (is (= :rule.from-mapping/x
         (mapping/mapping-rule-id :something/x)))
  (is (= :rule.from-mapping/foo
         (mapping/mapping-rule-id :foo))
      "unnamespaced mapping ids keep their name"))

(deftest mapping-rule-id-rejects-non-keyword
  (is (thrown? clojure.lang.ExceptionInfo (mapping/mapping-rule-id "string"))))

;; ---------------------------------------------------------------------------
;; Compiled-rule shape
;; ---------------------------------------------------------------------------

(deftest compile-mapping-returns-runtime-shaped-rule
  (let [cr (mapping/compile-mapping minimal-mapping stdlib)]
    (is (= :rule.from-mapping/x (:id cr)))
    (is (= :normalize (:phase cr)))
    (testing "the runner is the contract apply-rule reaches for"
      (is (vector? (rules/apply-rule cr (empty-record)))))))

(deftest compile-mapping-preserves-doc-when-present
  (let [m  (assoc minimal-mapping :mapping/doc "explain")
        cr (mapping/compile-mapping m stdlib)]
    (is (= "explain" (:doc cr))))
  (let [cr (mapping/compile-mapping minimal-mapping stdlib)]
    (is (not (contains? cr :doc)))))

;; ---------------------------------------------------------------------------
;; Flat compilation — happy paths
;; ---------------------------------------------------------------------------

(deftest flat-mapping-renames-predicate
  (let [cr     (mapping/compile-mapping minimal-mapping stdlib)
        record (record-with :native/x ["v1" "v2"])
        prods  (rules/apply-rule cr record)
        asrts  (mapv :value prods)]
    (is (= [:assertion :assertion] (mapv :kind prods)))
    (is (= [:canon/x :canon/x] (mapv :predicate asrts)))
    (is (= ["v1" "v2"] (mapv :value asrts)))
    (testing "subject is preserved"
      (is (every? #(= :record/r1 (:subject %)) asrts)))))

(deftest flat-mapping-applies-transform-chain
  (let [m       (assoc minimal-mapping :mapping/transform [:trim :lowercase])
        cr      (mapping/compile-mapping m stdlib)
        record  (record-with :native/x ["  ABC  " "DeF" "ghi"])
        prods   (rules/apply-rule cr record)
        asserts (filter #(= :assertion (:kind %)) prods)]
    (is (= ["abc" "def" "ghi"] (mapv #(:value (:value %)) asserts)))
    (testing "the lossy chain reports a :coerced loss only for values it changed (ADR 0015; R4)"
      ;; \"  ABC  \" and \"DeF\" change; \"ghi\" is already trimmed+lowercase, so
      ;; trim+lowercase is a no-op on it — no coercion, no loss. 2 of 3, not 3.
      (let [coerced (filter #(= :loss/coerced (get-in % [:value :code])) prods)]
        (is (= 2 (count coerced)))))))

(deftest lossy-transform-emits-coerced-loss
  (testing "a mapping whose chain discards detail reports :coerced/:import on the source field"
    (let [m    (assoc minimal-mapping :mapping/transform [:lowercase])
          cr   (mapping/compile-mapping m stdlib)
          d    (->> (rules/apply-rule cr (record-with :native/x ["FOO"]))
                    (filter #(= :diagnostic (:kind %)))
                    first
                    :value)]
      (is (= :loss/coerced (:code d)))
      (is (= :import     (get-in d [:detail :loss/edge])))
      (is (= :native/x   (get-in d [:detail :loss/source-field])))
      (is (= :rule.from-mapping/x (get-in d [:provenance :rule])))))
  (testing "a non-lossy chain (:trim) emits no :coerced loss"
    (let [m  (assoc minimal-mapping :mapping/transform [:trim])
          cr (mapping/compile-mapping m stdlib)
          ps (rules/apply-rule cr (record-with :native/x ["  x  "]))]
      (is (every? #(= :assertion (:kind %)) ps))))
  (testing "a lossy transform that is a no-op on the value emits no :coerced loss (R4)"
    (let [m  (assoc minimal-mapping :mapping/transform [:lowercase])
          cr (mapping/compile-mapping m stdlib)
          ps (rules/apply-rule cr (record-with :native/x ["foo"]))]  ; already lowercase
      (is (every? #(= :assertion (:kind %)) ps))
      (is (= ["foo"] (mapv #(get-in % [:value :value]) ps))))))

(deftest flat-mapping-multiplicity-preserved
  (let [cr     (mapping/compile-mapping minimal-mapping stdlib)
        record (record-with :native/x ["a" "b" "c" "d"])
        prods  (rules/apply-rule cr record)]
    (is (= 4 (count prods)))
    (testing "no deduplication at the compiler level — that's runtime's job"
      (let [record-dups (record-with :native/x ["x" "x" "x"])
            dup-prods   (rules/apply-rule cr record-dups)]
        (is (= 3 (count dup-prods)))))))

(deftest flat-mapping-confidence-respected
  (testing "explicit confidence flows through"
    (let [m    (assoc minimal-mapping :mapping/confidence 0.5)
          cr   (mapping/compile-mapping m stdlib)
          prod (first (rules/apply-rule cr (record-with :native/x ["v"])))]
      (is (= 0.5 (:confidence (:value prod))))))
  (testing "default confidence is 1.0"
    (let [cr   (mapping/compile-mapping minimal-mapping stdlib)
          prod (first (rules/apply-rule cr (record-with :native/x ["v"])))]
      (is (= 1.0 (:confidence (:value prod)))))))

(deftest flat-mapping-provenance-has-rule-and-phase
  (let [cr   (mapping/compile-mapping minimal-mapping stdlib)
        prod (first (rules/apply-rule cr (record-with :native/x ["v"])))
        prov (:provenance (:value prod))]
    (is (= :rule.from-mapping/x (:rule prov)))
    (is (= :normalize (:pass prov)))))

(deftest flat-mapping-emitted-assertion-is-asserted-status
  (let [cr   (mapping/compile-mapping minimal-mapping stdlib)
        prod (first (rules/apply-rule cr (record-with :native/x ["v"])))]
    (is (= :asserted (:status (:value prod))))))

;; ---------------------------------------------------------------------------
;; Flat compilation — transform failure
;; ---------------------------------------------------------------------------

(deftest flat-mapping-transform-failure-emits-info-diagnostic
  (let [m      (assoc minimal-mapping :mapping/transform [:parse-int])
        cr     (mapping/compile-mapping m stdlib)
        record (record-with :native/x ["42" "garbage" "7"])
        prods  (rules/apply-rule cr record)]
    (is (= [:assertion :diagnostic :assertion] (mapv :kind prods)))
    (testing "the diagnostic is informational and pins the failure"
      (let [d (:value (second prods))]
        (is (= :info (:severity d)))
        (is (= :transform-failed (:code d)))
        (is (= :record/r1 (:subject d)))
        (is (string? (:message d)))
        (is (re-find #"garbage" (:message d)))
        (is (= :rule.from-mapping/x (get-in d [:provenance :rule])))))
    (testing "successful matches still emit their canonical assertions"
      (is (= 42 (:value (:value (first prods)))))
      (is (=  7 (:value (:value (last prods))))))))

(deftest flat-mapping-truncates-large-values-in-diagnostic-message
  (let [m      (assoc minimal-mapping :mapping/transform [:parse-int])
        cr     (mapping/compile-mapping m stdlib)
        record (record-with :native/x [(apply str (repeat 500 \a))])
        d      (-> (rules/apply-rule cr record) first :value)]
    (is (= :transform-failed (:code d)))
    (is (re-find #"…" (:message d))
        "long values are truncated with an ellipsis")))

;; ---------------------------------------------------------------------------
;; Flat compilation — :on-empty branches
;; ---------------------------------------------------------------------------

(deftest on-empty-skip-by-default
  (let [cr    (mapping/compile-mapping minimal-mapping stdlib)
        prods (rules/apply-rule cr (empty-record))]
    (is (= [] prods))))

(deftest on-empty-explicit-skip
  (let [m  (assoc minimal-mapping :mapping/on-empty :skip)
        cr (mapping/compile-mapping m stdlib)]
    (is (= [] (rules/apply-rule cr (empty-record))))))

(deftest on-empty-diagnostic-emits-info-missing-predicate
  (let [m  (assoc minimal-mapping :mapping/on-empty :diagnostic)
        cr (mapping/compile-mapping m stdlib)
        prods (rules/apply-rule cr (empty-record))]
    (is (= 1 (count prods)))
    (let [d (:value (first prods))]
      (is (= :info (:severity d)))
      (is (= :missing-source-predicate (:code d)))
      (is (= :record/r1 (:subject d)))
      (is (re-find #":native/x" (:message d)))
      (is (= :rule.from-mapping/x (get-in d [:provenance :rule]))))))

(deftest on-empty-default-emits-canonical-assertion-with-default-value
  (let [m  (assoc minimal-mapping
                  :mapping/on-empty :default
                  :mapping/default  "fallback")
        cr (mapping/compile-mapping m stdlib)
        prods (rules/apply-rule cr (empty-record))]
    (is (= 1 (count prods)))
    (let [a (:value (first prods))]
      (is (= :record/r1 (:subject a)))
      (is (= :canon/x (:predicate a)))
      (is (= "fallback" (:value a)))
      (is (= :asserted (:status a))))))

(deftest on-empty-default-does-not-fire-when-source-present
  (testing "with source values present the default branch is bypassed"
    (let [m  (assoc minimal-mapping
                    :mapping/on-empty :default
                    :mapping/default  "fallback")
          cr (mapping/compile-mapping m stdlib)
          prods (rules/apply-rule cr (record-with :native/x ["real"]))]
      (is (= 1 (count prods)))
      (is (= "real" (:value (:value (first prods))))))))

(deftest on-empty-default-bypasses-transform-chain
  (testing "the default is canonical-side; transforms apply to source values only"
    (let [m  (assoc minimal-mapping
                    :mapping/on-empty :default
                    :mapping/default  "FALLBACK"
                    :mapping/transform [:lowercase])
          cr (mapping/compile-mapping m stdlib)
          prods (rules/apply-rule cr (empty-record))]
      (is (= 1 (count prods)))
      (is (= "FALLBACK" (:value (:value (first prods))))))))

;; ---------------------------------------------------------------------------
;; compile-mapping — input validation
;; ---------------------------------------------------------------------------

(deftest compile-mapping-rejects-schema-violations
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Invalid mapping rule"
                        (mapping/compile-mapping {:mapping/from :a} stdlib))))

(deftest compile-mapping-rejects-unknown-transform-eagerly
  (let [m (assoc minimal-mapping :mapping/transform [:nonexistent])]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Unknown transform"
                          (mapping/compile-mapping m stdlib))
        "unknown transforms fail at compile time, not at first record")))

;; ---------------------------------------------------------------------------
;; Flat compilation — non-primitive passthrough
;; ---------------------------------------------------------------------------

(deftest flat-mapping-passes-non-primitive-values-through-without-transform
  (testing "a transform chain skips non-primitive values rather than firing a false :transform-failed"
    (let [ref-val (model/reference :frag/some)
          m       (assoc minimal-mapping :mapping/transform [:trim])
          cr      (mapping/compile-mapping m stdlib)
          record  (model/record
                   {:id         :record/r1
                    :kind       :test
                    :assertions [(model/assertion
                                  {:subject :record/r1
                                   :predicate :native/x
                                   :value ref-val})]})
          prods   (rules/apply-rule cr record)]
      (is (= 1 (count prods)))
      (is (= :assertion (-> prods first :kind)))
      (is (= :canon/x (-> prods first :value :predicate)))
      (is (= ref-val  (-> prods first :value :value))))))

;; ---------------------------------------------------------------------------
;; Compiler purity
;; ---------------------------------------------------------------------------

(deftest compilation-is-pure-across-runs
  (testing "the same mapping compiled twice produces runners with identical outputs"
    (let [m      (assoc minimal-mapping :mapping/transform [:trim])
          cr1    (mapping/compile-mapping m stdlib)
          cr2    (mapping/compile-mapping m stdlib)
          record (record-with :native/x ["  abc  " "  def  "])]
      (is (= (rules/apply-rule cr1 record)
             (rules/apply-rule cr2 record))))))

(deftest applying-the-same-rule-twice-yields-identical-productions
  (testing "the runner is a pure function of the record"
    (let [cr     (mapping/compile-mapping minimal-mapping stdlib)
          record (record-with :native/x ["a" "b"])]
      (is (= (rules/apply-rule cr record)
             (rules/apply-rule cr record))))))

;; ---------------------------------------------------------------------------
;; compile-mappings
;; ---------------------------------------------------------------------------

(deftest compile-mappings-returns-flat-list-of-compiled-rules
  (let [ms  [{:mapping/id :map/a :mapping/from :n/a :mapping/to :c/a}
             {:mapping/id :map/b :mapping/from :n/b :mapping/to :c/b}]
        crs (mapping/compile-mappings ms stdlib)]
    (is (= 2 (count crs)))
    (is (= [:rule.from-mapping/a :rule.from-mapping/b] (mapv :id crs)))
    (is (every? #(= :normalize (:phase %)) crs))))

(deftest compile-mappings-fails-fast-on-first-bad-rule
  (let [ms [{:mapping/id :map/a :mapping/from :n/a :mapping/to :c/a}
            {:mapping/from :nope}]]   ; invalid: missing :mapping/id and :mapping/to
    (is (thrown? clojure.lang.ExceptionInfo
                 (mapping/compile-mappings ms stdlib)))))

(deftest compile-mappings-rejects-colliding-derived-rule-ids
  ;; mapping-rule-id keeps only the *name* portion of :mapping/id, so two
  ;; mappings from different plugins that share a name (here both "dc-title")
  ;; derive the same compiled rule id and would silently conflate in the
  ;; provenance trace. The batch compiler must reject this (ADR 0009 §Open V2
  ;; — cross-plugin :mapping/id uniqueness).
  (let [m1 {:mapping/id :plugin-a/dc-title :mapping/from :a/title :mapping/to :canon/title}
        m2 {:mapping/id :plugin-b/dc-title :mapping/from :b/title :mapping/to :canon/title}]
    (testing "both mappings derive the same compiled rule id"
      (is (= (mapping/mapping-rule-id (:mapping/id m1))
             (mapping/mapping-rule-id (:mapping/id m2)))))
    (testing "compiling them together is rejected loudly"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"colliding"
                            (mapping/compile-mappings [m1 m2] stdlib))))
    (testing "the error names the colliding mapping ids"
      (let [data (try (mapping/compile-mappings [m1 m2] stdlib)
                      (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (is (= #{:plugin-a/dc-title :plugin-b/dc-title}
               (set (get-in data [:collisions :rule.from-mapping/dc-title]))))))))

;; ---------------------------------------------------------------------------
;; Plugin extension transforms compose
;; ---------------------------------------------------------------------------

(deftest plugin-extension-transforms-work-end-to-end
  (testing "a compiler given an extended stdlib resolves plugin transforms"
    (let [extended (assoc stdlib :uppercase-bang
                          (fn [v] (when (string? v)
                                    (str (str/upper-case v) "!"))))
          m        (assoc minimal-mapping :mapping/transform [:uppercase-bang])
          cr       (mapping/compile-mapping m extended)
          record   (record-with :native/x ["hello"])
          prod     (first (rules/apply-rule cr record))]
      (is (= "HELLO!" (:value (:value prod)))))))

;; ---------------------------------------------------------------------------
;; Qualified compilation
;;
;; The shape adapter (M5) is what mints fragments at ingest; these tests
;; simulate that pre-state — a record with native-vocabulary triples on
;; both the record (a reference value pointing at the fragment) and the
;; fragment (the value coord plus the qualifier coord). The mapping
;; compiler's job at :normalize is to rename the native predicates to
;; canonical ones.
;; ---------------------------------------------------------------------------

(defn- multilingual-record
  "Build a record matching the shape an importer would produce for a
   qualified mapping: one fragment per `(value, qualifier)` pair, each
   referenced from the record by `predicate`, with the value coord on
   the fragment under `predicate` and the qualifier coord under
   `qualifier-predicate`."
  [predicate qualifier-predicate value-qualifier-pairs]
  (let [pairs (vec value-qualifier-pairs)
        frags (mapv (fn [i] (keyword "frag" (str "f" i)))
                    (range (count pairs)))
        record-id :record/r1]
    (model/record
     {:id         record-id
      :kind       :test
      :fragments  (mapv (fn [f] (model/fragment {:id f :source [:test]})) frags)
      :assertions (vec
                   (concat
                    ;; record-level references
                    (mapv (fn [f]
                            (model/assertion {:subject   record-id
                                              :predicate predicate
                                              :value     (model/reference f)}))
                          frags)
                    ;; fragment value coords
                    (mapv (fn [f [v _q]]
                            (model/assertion {:subject f :predicate predicate :value v}))
                          frags pairs)
                    ;; fragment qualifier coords (skipped when q is nil)
                    (keep (fn [[f [_v q]]]
                            (when q
                              (model/assertion {:subject f
                                                :predicate qualifier-predicate
                                                :value q})))
                          (map vector frags pairs))))})))

(def qualified-mapping
  {:mapping/id        :map/dc-title
   :mapping/from      :dc/title
   :mapping/to        :canon/title
   :mapping/qualifier {:from :xml/lang :as :canon/lang}})

(deftest qualified-mapping-compiles-cleanly
  (let [cr (mapping/compile-mapping qualified-mapping stdlib)]
    (is (= :rule.from-mapping/dc-title (:id cr)))
    (is (= :normalize (:phase cr)))))

(deftest qualified-mapping-renames-record-level-reference-without-transform
  (testing "the record-level :dc/title (ref ?frag) becomes :canon/title (ref ?frag), unchanged"
    (let [m      (assoc qualified-mapping :mapping/transform [:trim])
          cr     (mapping/compile-mapping m stdlib)
          record (multilingual-record :dc/title :xml/lang [["Les Misérables" "fr"]])
          prods  (rules/apply-rule cr record)
          record-prods (filterv #(= :record/r1 (-> % :value :subject)) prods)]
      (is (= 1 (count record-prods)))
      (let [a (-> record-prods first :value)]
        (is (= :canon/title (:predicate a)))
        (is (= {:value/kind :reference :value/target :frag/f0}
               (:value a)))))))

(deftest qualified-mapping-renames-fragment-value-with-transform
  (testing "the fragment's value coord goes through the transform chain"
    (let [m      (assoc qualified-mapping :mapping/transform [:trim :lowercase])
          cr     (mapping/compile-mapping m stdlib)
          record (multilingual-record :dc/title :xml/lang [["  Les Misérables  " "fr"]])
          prods  (rules/apply-rule cr record)
          frag-title-prods
          (filterv #(and (= :frag/f0 (-> % :value :subject))
                         (= :canon/title (-> % :value :predicate)))
                   prods)]
      (is (= 1 (count frag-title-prods)))
      (is (= "les misérables" (-> frag-title-prods first :value :value))))))

(deftest qualified-mapping-renames-qualifier-on-fragment
  (testing "the fragment's :xml/lang becomes :canon/lang, value unchanged"
    (let [cr     (mapping/compile-mapping qualified-mapping stdlib)
          record (multilingual-record :dc/title :xml/lang [["Les Misérables" "fr"]])
          prods  (rules/apply-rule cr record)
          lang-prods (filterv #(= :canon/lang (-> % :value :predicate)) prods)]
      (is (= 1 (count lang-prods)))
      (let [a (-> lang-prods first :value)]
        (is (= :frag/f0 (:subject a)))
        (is (= "fr" (:value a)))))))

(deftest qualified-mapping-full-round-trip-matches-adr-0009-example
  (testing "the three-rename pattern matches the canonical ADR 0009 §Qualifier example"
    (let [cr     (mapping/compile-mapping qualified-mapping stdlib)
          record (multilingual-record :dc/title :xml/lang [["Les Misérables" "fr"]
                                                           ["The Wretched"   "en"]])
          {enr :record} (runtime/run-phase record [cr] :normalize)
          asrts  (:assertions enr)
          select (fn [s p] (filterv #(and (= s (:subject %)) (= p (:predicate %))) asrts))]
      (testing "record carries two :canon/title reference assertions"
        (let [refs (select :record/r1 :canon/title)]
          (is (= 2 (count refs)))
          (is (= #{(model/reference :frag/f0) (model/reference :frag/f1)}
                 (set (mapv :value refs))))))
      (testing "each fragment carries its :canon/title value coord"
        (is (= "Les Misérables" (-> (select :frag/f0 :canon/title) first :value)))
        (is (= "The Wretched"   (-> (select :frag/f1 :canon/title) first :value))))
      (testing "each fragment carries its :canon/lang qualifier coord"
        (is (= "fr" (-> (select :frag/f0 :canon/lang) first :value)))
        (is (= "en" (-> (select :frag/f1 :canon/lang) first :value)))))))

(deftest qualified-mapping-handles-fragment-without-qualifier
  (testing "a fragment lacking the qualifier triple still gets the value rename"
    (let [cr     (mapping/compile-mapping qualified-mapping stdlib)
          record (multilingual-record :dc/title :xml/lang [["Les Misérables" "fr"]
                                                           ["The Wretched"   nil]])
          {enr :record} (runtime/run-phase record [cr] :normalize)
          asrts  (:assertions enr)]
      (testing "both fragments get their :canon/title coord"
        (is (some #(and (= :frag/f0 (:subject %)) (= :canon/title (:predicate %))) asrts))
        (is (some #(and (= :frag/f1 (:subject %)) (= :canon/title (:predicate %))) asrts)))
      (testing "only the fragment with a qualifier gets a :canon/lang coord"
        (is (= 1 (count (filterv #(= :canon/lang (:predicate %)) asrts))))
        (is (some #(and (= :frag/f0 (:subject %)) (= :canon/lang (:predicate %))) asrts))))))

(deftest qualified-mapping-multiplicity-preserved-across-renames
  (let [cr     (mapping/compile-mapping qualified-mapping stdlib)
        record (multilingual-record :dc/title :xml/lang
                                    [["v0" "fr"] ["v1" "en"] ["v2" "de"]])
        prods  (rules/apply-rule cr record)]
    (testing "3 references on the record, 3 value coords, 3 qualifier coords = 9 assertions"
      (is (= 9 (count (filterv #(= :assertion (:kind %)) prods)))))))

(deftest qualified-mapping-on-empty-skip-when-source-absent
  (let [m  (assoc qualified-mapping :mapping/on-empty :skip)
        cr (mapping/compile-mapping m stdlib)
        prods (rules/apply-rule cr (empty-record))]
    (is (= [] prods))))

(deftest qualified-mapping-on-empty-diagnostic
  (let [m  (assoc qualified-mapping :mapping/on-empty :diagnostic)
        cr (mapping/compile-mapping m stdlib)
        prods (rules/apply-rule cr (empty-record))]
    (is (= 1 (count prods)))
    (is (= :diagnostic (-> prods first :kind)))
    (is (= :missing-source-predicate (-> prods first :value :code)))))

(deftest qualified-mapping-provenance-attributes-everything-to-the-mapping-rule
  (let [cr     (mapping/compile-mapping qualified-mapping stdlib)
        record (multilingual-record :dc/title :xml/lang [["Les Misérables" "fr"]])
        prods  (rules/apply-rule cr record)
        asrts  (mapv :value prods)]
    (is (every? #(= :rule.from-mapping/dc-title (get-in % [:provenance :rule]))
                asrts))
    (is (every? #(= :normalize (get-in % [:provenance :pass]))
                asrts))))

(deftest qualified-mapping-transform-does-not-apply-to-qualifier-value
  (testing "transforms target the fragment's value coord, not the qualifier coord"
    (let [m      (assoc qualified-mapping :mapping/transform [:uppercase])
          cr     (mapping/compile-mapping m stdlib)
          record (multilingual-record :dc/title :xml/lang [["hello" "fr"]])
          prods  (rules/apply-rule cr record)
          lang   (first (filterv #(= :canon/lang  (-> % :value :predicate)) prods))
          title  (first (filterv #(and (= :frag/f0  (-> % :value :subject))
                                       (= :canon/title (-> % :value :predicate)))
                                 prods))]
      (testing "qualifier value passes through unchanged"
        (is (= "fr" (-> lang :value :value))))
      (testing "fragment value IS transformed (sanity check that the chain ran)"
        (is (= "HELLO" (-> title :value :value)))))))

(deftest qualified-mapping-transform-on-fragment-value-failure-emits-diagnostic
  (testing "a transform failure on the fragment's value coord produces a diagnostic; the record-level reference rename is unaffected"
    (let [m      (assoc qualified-mapping :mapping/transform [:parse-int])
          cr     (mapping/compile-mapping m stdlib)
          record (multilingual-record :dc/title :xml/lang [["garbage" "fr"]])
          prods  (rules/apply-rule cr record)
          diags  (filterv #(= :diagnostic (:kind %)) prods)]
      (testing "exactly one transform-failed diagnostic, on the fragment"
        (is (= 1 (count diags)))
        (is (= :transform-failed (-> diags first :value :code)))
        (is (= :frag/f0 (-> diags first :value :subject))))
      (testing "the record-level reference rename still happens"
        (is (some #(and (= :record/r1 (-> % :value :subject))
                        (= :canon/title (-> % :value :predicate)))
                  prods)))
      (testing "the qualifier rename still happens"
        (is (some #(and (= :frag/f0 (-> % :value :subject))
                        (= :canon/lang (-> % :value :predicate)))
                  prods))))))

;; ---------------------------------------------------------------------------
;; Runtime integration
;; ---------------------------------------------------------------------------

(deftest compiled-mapping-runs-through-run-phase
  (testing "mapping-compiled rules participate in run-phase like any rule"
    (let [m      (assoc minimal-mapping :mapping/transform [:trim])
          cr     (mapping/compile-mapping m stdlib)
          record (record-with :native/x ["  hello  "])
          {enr :record} (runtime/run-phase record [cr] :normalize)]
      (is (some #(and (= :canon/x (:predicate %))
                      (= "hello"  (:value %)))
                (:assertions enr))))))

(deftest compiled-mapping-rules-co-exist-with-data-form-rules
  (testing "the runtime treats both kinds uniformly"
    (let [mapping-rule (mapping/compile-mapping minimal-mapping stdlib)
          regular-rule (rules/compile-rule
                        {:id      :rule/tag
                         :phase   :normalize
                         :match   [['?r :meta/id '?r]]
                         :produce {:assert {:subject '?r
                                            :predicate :canon/tagged
                                            :value true}}})
          record       (record-with :native/x ["v"])
          {enr :record}
          (runtime/run-phase record [mapping-rule regular-rule] :normalize)]
      (is (some #(= :canon/x (:predicate %))       (:assertions enr)))
      (is (some #(= :canon/tagged (:predicate %))  (:assertions enr))))))

(deftest compiled-mapping-trace-mentions-rule-id
  (testing "provenance from a mapping names its derived rule id"
    (let [cr     (mapping/compile-mapping minimal-mapping stdlib)
          record (record-with :native/x ["v"])
          {enr :record} (runtime/run-phase record [cr] :normalize)]
      (is (= 1 (count (filter #(= :rule.from-mapping/x (get-in % [:provenance :rule]))
                              (:assertions enr))))))))

;; ---------------------------------------------------------------------------
;; Combine — multi-source `:from` joined by `:mapping/combine` (subfield recombine)
;; ---------------------------------------------------------------------------

(defn- record-of
  "Build a record from a map of `{predicate value}` (one assertion each)."
  [pred->val]
  (model/record
   {:id         :record/r1
    :kind       :test
    :assertions (mapv (fn [[p v]] (model/assertion {:subject :record/r1 :predicate p :value v}))
                      pred->val)}))

(def ^:private combine-mapping
  {:mapping/id      :map/agent
   :mapping/from    [:native/surname :native/forename]
   :mapping/combine ", "
   :mapping/to      :canon/agent})

(deftest schema-combine-cross-field-invariants
  (testing "a vector :from with a :combine separator is valid"
    (is (mapping/valid-mapping? combine-mapping)))
  (testing ":combine without a vector :from is rejected (and vice versa)"
    (is (not (mapping/valid-mapping? (assoc minimal-mapping :mapping/combine ", "))))      ; single + combine
    (is (not (mapping/valid-mapping? (dissoc combine-mapping :mapping/combine)))))          ; vector, no sep
  (testing "a qualifier cannot apply to a combine (vector :from)"
    (is (not (mapping/valid-mapping?
              (assoc combine-mapping :mapping/qualifier {:from :native/lang :as :canon/lang}))))))

(deftest combine-joins-subfields-in-order
  (testing "100 $a + $m -> \"Surname, Forename\" (the INTERMARC agent case)"
    (let [cr    (mapping/compile-mapping combine-mapping stdlib)
          rec   (record-of {:native/surname "Flaubert" :native/forename "Gustave"})
          prods (rules/apply-rule cr rec)
          asrts (mapv :value prods)]
      (is (= [:assertion] (mapv :kind prods)))
      (is (= [:canon/agent] (mapv :predicate asrts)))
      (is (= ["Flaubert, Gustave"] (mapv :value asrts)))
      (is (every? #(= :record/r1 (:subject %)) asrts)))))

(deftest combine-skips-absent-subfields-no-dangling-separator
  (testing "only the surname present -> just \"Flaubert\", no trailing separator"
    (let [cr  (mapping/compile-mapping combine-mapping stdlib)
          ps  (rules/apply-rule cr (record-of {:native/surname "Flaubert"}))]
      (is (= ["Flaubert"] (mapv #(:value (:value %)) ps)))))
  (testing "only the forename present -> just \"Gustave\""
    (let [cr (mapping/compile-mapping combine-mapping stdlib)
          ps (rules/apply-rule cr (record-of {:native/forename "Gustave"}))]
      (is (= ["Gustave"] (mapv #(:value (:value %)) ps))))))

(deftest combine-with-an-empty-separator-and-a-space
  (testing "title + GMD with a single-space separator (245 $a + $h)"
    (let [m  {:mapping/id :map/title :mapping/from [:native/a :native/h]
              :mapping/combine " " :mapping/to :canon/title :mapping/transform [:trim]}
          cr (mapping/compile-mapping m stdlib)
          ps (rules/apply-rule cr (record-of {:native/a "The Great Ray Charles"
                                              :native/h "[sound recording]"}))]
      (is (= ["The Great Ray Charles [sound recording]"] (mapv #(:value (:value %)) ps))))))

(deftest combine-runs-the-transform-chain-on-the-joined-value
  (testing "the chain applies to the join (trim of a padded combination)"
    (let [m  (assoc combine-mapping :mapping/transform [:trim])
          cr (mapping/compile-mapping m stdlib)
          ps (rules/apply-rule cr (record-of {:native/surname "  Hugo" :native/forename "Victor  "}))]
      (is (= ["Hugo, Victor"] (mapv #(:value (:value %)) ps))))))

(deftest combine-on-empty-when-no-subfield-present
  (testing "neither subfield present -> :skip yields nothing"
    (let [cr (mapping/compile-mapping combine-mapping stdlib)]
      (is (empty? (rules/apply-rule cr (empty-record))))))
  (testing ":on-empty :default fires when no source is present"
    (let [m  (assoc combine-mapping :mapping/on-empty :default :mapping/default "Anon")
          cr (mapping/compile-mapping m stdlib)
          ps (rules/apply-rule cr (empty-record))]
      (is (= ["Anon"] (mapv #(:value (:value %)) ps))))))

(deftest combine-integrates-through-the-runtime
  (testing "a combine rule populates the canonical predicate via run-phase"
    (let [cr (mapping/compile-mapping combine-mapping stdlib)
          {enr :record} (runtime/run-phase
                         (record-of {:native/surname "Verne" :native/forename "Jules"})
                         [cr] :normalize)]
      (is (some #(and (= :canon/agent (:predicate %)) (= "Verne, Jules" (:value %)))
                (:assertions enr))))))

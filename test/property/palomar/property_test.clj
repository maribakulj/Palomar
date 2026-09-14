(ns palomar.property-test
  "Property-based tests for the rule engine, runtime, model, and
   diagnostics layers. Every defspec checks an invariant that should hold
   on *any* well-formed input, not just the hand-picked fixtures used
   elsewhere in the suite.

   Generators are sourced from `malli.generator` so they stay in lock-step
   with the schemas; if the model schema gains a new shape, the property
   tests start exploring it for free."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [malli.generator :as mg]
            [palomar.diagnostics :as diag]
            [palomar.model :as model]
            [palomar.rules :as rules]
            [palomar.runtime :as rt]))

;; ---------------------------------------------------------------------------
;; Tunables
;;
;; A property check at this stage of the project is a structural cross-check,
;; not a statistical study. 50 samples per property is generous; if a
;; property fails it almost always fails on the first few samples.
;; ---------------------------------------------------------------------------

(def ^:private prop-runs 50)

;; ---------------------------------------------------------------------------
;; Shared compiled rule for determinism / provenance properties
;; ---------------------------------------------------------------------------

(def ^:private tag-rule
  "A trivial normalize rule used as the subject of determinism and
   provenance properties below. Hand-written rather than generated because
   generating a syntactically-valid rule with bound variables and a
   well-formed produce template is its own project."
  (rules/compile-rule
   {:id    :prop/tag
    :phase :normalize
    :match '[[?r :meta/kind :book]]
    :produce {:assert {:subject '?r
                       :predicate :prop/seen
                       :value true}}}))

;; ---------------------------------------------------------------------------
;; Property 1 — apply-rule is deterministic
;;
;; Pure functions over the same inputs must return equal outputs. This is
;; the most basic correctness invariant of the rule engine; if it ever
;; fails, the engine has accidentally captured external state.
;; ---------------------------------------------------------------------------

(defspec apply-rule-is-deterministic prop-runs
  (prop/for-all [id (mg/generator model/Id)]
                (let [r    (model/record {:id id :kind :book})
                      out1 (rules/apply-rule tag-rule r)
                      out2 (rules/apply-rule tag-rule r)]
                  (= out1 out2))))

;; ---------------------------------------------------------------------------
;; Property 2 — record-triples is lossless on assertions
;;
;; Every assertion in a record must appear as a [subject predicate value]
;; triple in the matcher's view. If this property ever fails, the matcher
;; cannot bind variables for an assertion that the user can see.
;; ---------------------------------------------------------------------------

(defspec record-triples-includes-every-assertion prop-runs
  (prop/for-all [r (mg/generator model/Record)]
                (let [triples (set (rules/record-triples r))]
                  (every? (fn [a]
                            (contains? triples
                                       [(:subject a) (:predicate a) (:value a)]))
                          (:assertions r)))))

;; ---------------------------------------------------------------------------
;; Property 3 — every production carries rule and pass provenance
;;
;; The runtime contract (ADR 0001) is that *every* item produced by a
;; rule names the rule that produced it and the phase that ran it. This
;; is what makes provenance a property of the IR rather than an optional
;; add-on.
;; ---------------------------------------------------------------------------

(defspec productions-carry-rule-and-pass prop-runs
  (prop/for-all [id (mg/generator model/Id)]
                (let [r          (model/record {:id id :kind :book})
                      productions (rules/apply-rule tag-rule r)]
                  (every? (fn [p]
                            (let [prov (get-in p [:value :provenance])]
                              (and (= :prop/tag  (:rule prov))
                                   (= :normalize (:pass prov)))))
                          productions))))

;; ---------------------------------------------------------------------------
;; Property 4 — disjoint productions accumulate (no silent drops)
;;
;; If two productions describe distinct facts, both end up on the merged
;; record. This is the V1 contract for merge-productions on assertions
;; that are pairwise distinct in (subject, predicate, value, status).
;; ---------------------------------------------------------------------------

(defspec merge-productions-preserves-disjoint-assertions prop-runs
  (prop/for-all [id (mg/generator model/Id)
                 n  (gen/choose 0 8)]
                (let [r           (model/record {:id id :kind :book})
                      assertions  (mapv (fn [i]
                                          (model/assertion
                                           {:subject   id
                                            :predicate (keyword "prop"
                                                                (str "p" i))
                                            :value     i}))
                                        (range n))
                      productions (mapv (fn [a] {:kind :assertion :value a}) assertions)
                      merged      (rt/merge-productions r productions)]
                  (= n (count (:assertions merged))))))

;; ---------------------------------------------------------------------------
;; Property 5 — count-by-severity totals match input size
;;
;; The aggregation must not lose or invent diagnostics. For any input
;; vector of valid diagnostics, the sum of counts across severities equals
;; the input length.
;; ---------------------------------------------------------------------------

(defspec count-by-severity-totals-input-size prop-runs
  (prop/for-all [ds (gen/vector (mg/generator model/Diagnostic) 0 20)]
                (let [counts (diag/count-by-severity ds)]
                  (= (count ds) (apply + (vals counts))))))

;; ---------------------------------------------------------------------------
;; Closed-set sanity (deftest, not a property — input set is finite)
;;
;; severity-rank gives a strict total order on the closed severity enum.
;; Checking the 9 pairs explicitly is clearer than a property; the space
;; is too small to benefit from generation.
;; ---------------------------------------------------------------------------

(deftest severity-rank-is-a-strict-total-order
  (let [severities [:info :warning :error]]
    (doseq [a severities b severities]
      (let [ra (diag/severity-rank a)
            rb (diag/severity-rank b)]
        (is (= (diag/severity>= a b) (>= ra rb))
            (str "severity>= must agree with severity-rank for "
                 a " and " b))))
    ;; strict ordering across distinct elements
    (is (< (diag/severity-rank :info)    (diag/severity-rank :warning)))
    (is (< (diag/severity-rank :warning) (diag/severity-rank :error)))))

;; ---------------------------------------------------------------------------
;; Generator sanity — every malli-generated record validates
;;
;; If the generators ever drift from the validators, every property test
;; in this file becomes meaningless. This deftest is the canary: it
;; exercises the generator/validator pair on a small batch and fails
;; loudly the moment they disagree.
;; ---------------------------------------------------------------------------

(deftest generated-records-validate
  (doseq [r (mg/sample model/Record {:size 4 :seed 42})]
    (is (model/valid-record? r)
        (str "Generator produced an invalid Record: " (pr-str r)))))

(deftest generated-diagnostics-validate
  (doseq [d (mg/sample model/Diagnostic {:size 4 :seed 42})]
    (is (model/valid-diagnostic? d)
        (str "Generator produced an invalid Diagnostic: " (pr-str d)))))

;; ---------------------------------------------------------------------------
;; Fragment identity (ADR 0012)
;;
;; mint-fragment-id is injective on inputs that meet the scheme's stated
;; preconditions: hyphen-free namespaces on both the record-id and every
;; locator predicate. The generators below are constrained to that safe
;; input space.
;; ---------------------------------------------------------------------------

(def ^:private hyphen-free-name-gen
  (gen/such-that #(and (seq %) (not (str/includes? % "-")))
                 (gen/fmap str/join
                           (gen/vector
                            (gen/elements (vec "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"))
                            1 8))))

(def ^:private safe-namespaced-keyword-gen
  (gen/fmap (fn [[ns nm]] (keyword ns nm))
            (gen/tuple hyphen-free-name-gen hyphen-free-name-gen)))

(def ^:private safe-locator-gen
  (gen/fmap (fn [pairs] (vec (mapcat identity pairs)))
            (gen/vector (gen/tuple safe-namespaced-keyword-gen
                                   gen/nat)
                        1 4)))

(defspec mint-fragment-id-always-returns-frag-keyword prop-runs
  (prop/for-all [record-id safe-namespaced-keyword-gen
                 locator   safe-locator-gen]
                (let [id (model/mint-fragment-id record-id locator)]
                  (and (keyword? id)
                       (= "frag" (namespace id))))))

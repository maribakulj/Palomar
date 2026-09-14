(ns palomar.rules
  "Declarative rule DSL.

   A rule is an EDN map with `:id`, `:phase`, `:match` and `:produce` keys.
   This namespace defines the rule schema, the rule compiler (data → function),
   and the curated predicate stdlib available in `:match` clauses.

   Rules are first-class data: inspectable, serializable, composable. The core
   never accepts arbitrary Clojure functions inside a rule; logic must pass
   through the compiler (ADR 0002).

   Match semantics:
   - Records are presented to the matcher as a set of triples derived from
     their struct fields (`:id`, `:kind`, `:source` → `:meta/*` predicates)
     combined with their explicit assertions.
   - A match clause is either a triple-pattern (3-vector) or a guard-form
     (list starting with a stdlib predicate symbol).
   - Variables are symbols beginning with `?`. The underscore `_` matches
     anything without binding.
   - Guards are pure predicates drawn from a curated stdlib. Rules cannot
     call arbitrary Clojure functions.

   Production actions: `:assert`, `:diagnostic`, `:repair`, `:entity` (mint a
   synthesized subject — ADR 0014/0017). A `:retract` / `:supersede` family is
   a planned future addition (ADR 0008)."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [malli.core :as m]
            [palomar.model :as model]))

;; ---------------------------------------------------------------------------
;; Variables
;; ---------------------------------------------------------------------------

(defn variable?
  "True if `x` is a pattern variable (symbol starting with `?`)."
  [x]
  (and (symbol? x)
       (str/starts-with? (name x) "?")))

(defn wildcard?
  "True if `x` is the underscore wildcard symbol."
  [x]
  (= '_ x))

(defn- collect-variables
  "Walk a form and return the set of variable symbols it contains."
  [form]
  (let [acc (volatile! #{})]
    (walk/postwalk
     (fn [x] (when (variable? x) (vswap! acc conj x)) x)
     form)
    @acc))

(defn- triple-pattern? [clause]
  (and (vector? clause) (= 3 (count clause))))

(defn- guard-form? [clause]
  (and (sequential? clause) (not (vector? clause))))

(defn- variables-bound-by-match
  "Variables introduced by triple-patterns in a `:match` vector. Guards
   cannot introduce bindings; they only consume them."
  [match]
  (reduce (fn [acc clause]
            (if (triple-pattern? clause)
              (into acc (collect-variables clause))
              acc))
          #{}
          match))

(defn- variables-used-in-guards
  "Variables referenced as arguments to guard-forms in `:match`."
  [match]
  (reduce (fn [acc clause]
            (if (guard-form? clause)
              (into acc (collect-variables clause))
              acc))
          #{}
          match))

;; ---------------------------------------------------------------------------
;; Triple view of a record
;;
;; Records present a flat triple view for the matcher: struct fields become
;; `:meta/*` triples, and assertions contribute their own triples. This is
;; a read-only projection; the underlying record is never mutated.
;; ---------------------------------------------------------------------------

(defn record-triples
  "Return a vector of [subject predicate value] triples representing the
   record, combining struct fields (via the structural vocabulary) and
   explicit assertions.

   Struct triples cover the full structural vocabulary (`:meta/id`,
   `:meta/kind`, `:meta/source`, `:meta/fragment`, `:meta/diagnostic`,
   `:meta/provenance`). Multi-valued struct fields produce one triple per
   item:

   - `:meta/fragment` → one triple per fragment, value = fragment id
   - `:meta/diagnostic` → one triple per diagnostic, value = diagnostic code

   This unified view is the single source of truth for both pattern
   matching and the `absent?` / `present?` guards (see ADR 0001)."
  [record]
  (let [id (:id record)
        struct-triples
        (cond-> []
          id                   (conj [id model/meta-id id])
          (:kind record)       (conj [id model/meta-kind (:kind record)])
          (some? (:source record))
          (conj [id model/meta-source (:source record)])
          (some? (:provenance record))
          (conj [id model/meta-provenance (:provenance record)]))
        fragment-triples
        (mapv (fn [f] [id model/meta-fragment (:id f)])
              (:fragments record))
        diagnostic-triples
        (mapv (fn [d] [id model/meta-diagnostic (:code d)])
              (:diagnostics record))
        assertion-triples
        (mapv (fn [a] [(:subject a) (:predicate a) (:value a)])
              (:assertions record))]
    (-> struct-triples
        (into fragment-triples)
        (into diagnostic-triples)
        (into assertion-triples))))

;; ---------------------------------------------------------------------------
;; Predicate stdlib for guard forms
;;
;; Every guard-form in a :match clause is a list whose head is a symbol in
;; this map. The value is a function `(fn [bindings record & args] boolean)`
;; where `args` is the rest of the guard-form with variables resolved.
;; ---------------------------------------------------------------------------

(defn- resolve-arg
  "Resolve a pattern argument against bindings. Variables look up in
   bindings; everything else passes through unchanged."
  [bindings arg]
  (if (variable? arg)
    (get bindings arg ::unbound)
    arg))

(defn- triple-matches-subject-pred?
  "True if `triples` contain at least one [s p _] with s = subject and
   p = predicate."
  [triples subject predicate]
  (boolean (some (fn [[s p _]] (and (= subject s) (= predicate p))) triples)))

(def predicate-stdlib
  "The closed set of predicates that may appear as the head of a guard-form
   in a rule's :match. Each entry maps a symbol to a function of
   `[bindings record & resolved-args] -> boolean`.

   `absent?` and `present?` query the unified triple-view of the record
   (struct fields + assertions), so they correctly observe both
   `(absent? ?r :meta/kind)` and `(absent? ?r :canon/title)`."
  {'=        (fn [_ _ a b] (= a b))
   'not=     (fn [_ _ a b] (not= a b))
   'absent?  (fn [_ record subject pred]
               (not (triple-matches-subject-pred? (record-triples record)
                                                  subject pred)))
   'present? (fn [_ record subject pred]
               (triple-matches-subject-pred? (record-triples record)
                                             subject pred))
   'matches? (fn [_ _ value re]
               (and (string? value)
                    (boolean (re-find (re-pattern re) value))))
   'in?      (fn [_ _ value coll]
               (boolean (some #(= value %) coll)))})

(defn stdlib-predicate? [sym] (contains? predicate-stdlib sym))

;; ---------------------------------------------------------------------------
;; Pattern matching
;;
;; A triple-pattern is a 3-vector [s p v] where any position may be a
;; variable, a wildcard, or a literal. Matching a pattern against the
;; triple view produces a sequence of bindings (maps var → value).
;; ---------------------------------------------------------------------------

(defn- position-match
  "Match one position of a pattern against one position of a triple.
   Returns either a binding-delta map (possibly empty) or ::fail."
  [current-bindings pat-val triple-val]
  (cond
    (wildcard? pat-val) {}

    (variable? pat-val)
    (if-let [[_ bound] (find current-bindings pat-val)]
      (if (= bound triple-val) {} ::fail)
      {pat-val triple-val})

    :else
    (if (= pat-val triple-val) {} ::fail)))

(defn- match-pattern-against-triple
  "Return updated bindings or ::fail."
  [bindings pattern triple]
  (reduce
   (fn [acc [pat-val triple-val]]
     (let [delta (position-match acc pat-val triple-val)]
       (if (= ::fail delta)
         (reduced ::fail)
         (merge acc delta))))
   bindings
   (map vector pattern triple)))

(defn- match-pattern
  "Match `pattern` against every triple in `triples`, returning a sequence
   of binding maps extending `current-bindings`."
  [current-bindings pattern triples]
  (keep (fn [triple]
          (let [r (match-pattern-against-triple current-bindings pattern triple)]
            (when-not (= ::fail r) r)))
        triples))

(defn- run-guard
  "Evaluate a guard-form against `bindings` and `record`. Returns true if
   the guard is satisfied."
  [bindings record guard-form]
  (let [[sym & args] guard-form
        f            (get predicate-stdlib sym)
        resolved     (map #(resolve-arg bindings %) args)]
    (when-not f
      (throw (ex-info "Unknown predicate in guard" {:symbol sym})))
    (when (some #(= ::unbound %) resolved)
      (throw (ex-info "Unbound variable in guard"
                      {:guard guard-form :bindings bindings})))
    (apply f bindings record resolved)))

(defn- match-clauses
  "Execute match clauses against `record`. Returns a sequence of fully-bound
   binding maps (variables → values) for every matching row."
  [clauses record]
  (let [triples (record-triples record)]
    (reduce
     (fn [binding-rows clause]
       (cond
         (vector? clause)
         (mapcat #(match-pattern % clause triples) binding-rows)

         (seq? clause)
         (filter #(run-guard % record clause) binding-rows)

         :else
         (throw (ex-info "Invalid match clause" {:clause clause}))))
     [{}]
     clauses)))

;; ---------------------------------------------------------------------------
;; Template substitution for productions
;; ---------------------------------------------------------------------------

(defn- substitute
  "Walk `template`, replacing every variable symbol with its bound value.
   Tests a binding's *presence*, not its truth (via `find`, as `position-match`
   does), so a variable legitimately bound to `false` or `nil` substitutes to that
   value; only a genuinely *absent* binding raises."
  [bindings template]
  (walk/postwalk
   (fn [x]
     (if (variable? x)
       (if-let [[_ bound] (find bindings x)]
         bound
         (throw (ex-info "Unbound variable in produce template"
                         {:variable x :bindings bindings})))
       x))
   template))

;; ---------------------------------------------------------------------------
;; Production actions
;; ---------------------------------------------------------------------------

(defn- engine-provenance
  "Provenance fields the engine guarantees regardless of the rule
   template: which rule produced the item, and in which phase. These
   fields are authoritative — if a rule template tries to override them,
   the engine wins (otherwise the trace would lie)."
  [rule-id phase]
  (cond-> {}
    rule-id (assoc :rule rule-id)
    phase   (assoc :pass phase)))

(defn- merge-provenance
  "Combine the engine's authoritative fields with whatever the rule
   template supplied. Template fields like `:source`, `:derivation` and
   `:timestamp` are preserved; engine fields (`:rule`, `:pass`) always
   win to keep the trace honest."
  [template-prov rule-id phase]
  (merge (or template-prov {}) (engine-provenance rule-id phase)))

(defn- default-status-for-phase
  "Per ADR 0005: assertions produced in :infer or :repair phases are
   proposals until confirmed; assertions from other phases are in force."
  [phase]
  (if (contains? #{:infer :repair} phase) :proposed :asserted))

(defn- run-assert
  [template bindings rule-id phase]
  (let [base (substitute bindings template)]
    {:kind :assertion
     :value (model/assertion
             (merge {:status (default-status-for-phase phase)
                     :confidence 1.0}
                    base
                    {:provenance (merge-provenance (:provenance base)
                                                   rule-id phase)}))}))

(defn- run-diagnostic
  [template bindings rule-id phase]
  (let [base (substitute bindings template)]
    {:kind :diagnostic
     :value (model/diagnostic
             (assoc base :provenance (merge-provenance (:provenance base)
                                                       rule-id phase)))}))

(defn- run-repair
  [template bindings _rule-id _phase]
  {:kind :repair
   :value (model/repair (substitute bindings template))})

(defn- run-entity
  "Synthesize an entity (ADR 0014/0017). The entity *declaration* is
   structural — it carries engine provenance (:rule, :pass) but no status
   (entities are subjects, not claims). Claims about the entity are separate
   `:assert` productions and follow the phase status policy."
  [template bindings rule-id phase]
  (let [base (substitute bindings template)]
    {:kind :entity
     :value (model/entity
             (assoc base :provenance (merge-provenance (:provenance base)
                                                       rule-id phase)))}))

(def ^:private action-runners
  {:assert     run-assert
   :diagnostic run-diagnostic
   :repair     run-repair
   :entity     run-entity})

(defn supported-actions [] (set (keys action-runners)))

(defn- run-produce
  "Apply every action in `produce-map` to each binding row, returning a
   flat sequence of production maps."
  [produce-map binding-rows rule-id phase]
  (for [bindings binding-rows
        [action template] produce-map
        :let [runner (get action-runners action)]]
    (do
      (when-not runner
        (throw (ex-info "Unknown production action"
                        {:action action :supported (supported-actions)})))
      (runner template bindings rule-id phase))))

;; ---------------------------------------------------------------------------
;; Schemas
;; ---------------------------------------------------------------------------

(def TriplePattern
  [:and vector? [:fn {:error/message "must be a 3-element vector"}
                 #(= 3 (count %))]])

(def GuardForm
  [:and
   [:fn {:error/message "must be a non-vector sequential form"}
    #(and (sequential? %) (not (vector? %)))]
   [:fn {:error/message "guard head must be a stdlib predicate"}
    #(stdlib-predicate? (first %))]])

(def MatchClause
  [:or TriplePattern GuardForm])

(def Produce
  [:map-of
   [:enum :assert :diagnostic :repair :entity]
   :map])

(def Rule
  [:map
   [:id :keyword]
   [:phase [:enum :ingest :normalize :validate :infer :repair]]
   [:match [:vector MatchClause]]
   [:produce Produce]
   [:doc {:optional true} :string]])

;; ---------------------------------------------------------------------------
;; Compile + apply
;; ---------------------------------------------------------------------------

(defn validate-rule
  "Validate a rule against the Rule schema. Returns nil when valid, or a
   Malli explanation map when invalid."
  [rule]
  (m/explain Rule rule))

(defn valid-rule? [rule] (m/validate Rule rule))

(defn- validate-or-throw! [rule]
  (when-let [err (validate-rule rule)]
    (throw (ex-info "Invalid rule"
                    {:rule-id (:id rule)
                     :explanation err})))
  rule)

(defn- check-bound-variables!
  "Compile-time check that every variable referenced by a guard or by the
   :produce template is bound by some triple-pattern in :match.

   Guards cannot introduce bindings — only triple-patterns can. Walking
   :match wholesale (the previous implementation) silently treated guard
   variables as bound, so `(matches? ?unbound \"re\")` would compile and
   only fail at execution time."
  [rule]
  (let [bound          (variables-bound-by-match (:match rule))
        guard-used     (variables-used-in-guards (:match rule))
        produce-used   (collect-variables (:produce rule))
        unbound-guards (set/difference guard-used bound)
        unbound-prod   (set/difference produce-used bound)]
    (when (seq unbound-guards)
      (throw (ex-info "Rule references unbound variables in a guard"
                      {:rule-id (:id rule) :unbound unbound-guards})))
    (when (seq unbound-prod)
      (throw (ex-info "Rule references unbound variables in :produce"
                      {:rule-id (:id rule) :unbound unbound-prod}))))
  rule)

(defn compile-rule
  "Validate `rule` and return a compiled rule: a map with the original
   fields plus `:palomar.rules/runner`, a function
   `(fn [record] [productions...])`.

   The runner value is opaque — callers should not inspect or
   serialize a compiled rule. Use `apply-rule` to invoke it.

   Throws ex-info at compile time if the rule is malformed or
   references unbound variables."
  [rule]
  (-> rule
      validate-or-throw!
      check-bound-variables!
      (assoc ::runner
             (fn run [record]
               (let [rows (match-clauses (:match rule) record)]
                 (vec (run-produce (:produce rule) rows (:id rule) (:phase rule))))))))

(defn apply-rule
  "Run a compiled rule against a record, returning a vector of productions."
  [compiled-rule record]
  (let [runner (::runner compiled-rule)]
    (when-not runner
      (throw (ex-info "Rule was not compiled (missing ::runner)"
                      {:rule-id (:id compiled-rule)})))
    (runner record)))

(defn compile-rules
  "Compile a collection of rules. Fails fast on the first invalid rule."
  [rules]
  (mapv compile-rule rules))

;; ---------------------------------------------------------------------------
;; Compiled-rule constructor (used by external compilers)
;;
;; A compiled rule is, from the runtime's perspective, anything with `:id`,
;; `:phase`, and a `::runner` function that takes a record and returns a
;; vector of productions. `compile-rule` produces these from data-form
;; rules; other compilers (notably the mapping compiler in
;; `palomar.plugins.mapping`) need to emit the same shape without going
;; through the data-form Rule schema, because they apply Clojure functions
;; (transforms) to bound values — something the data-form DSL deliberately
;; does not express. This constructor centralizes the contract so the
;; `::runner` key stays a `palomar.rules`-internal detail.
;; ---------------------------------------------------------------------------

(defn compiled-rule
  "Construct a compiled rule from an explicit runner function and trace
   metadata. The result is interchangeable with `compile-rule`'s output
   as far as `apply-rule` and the runtime are concerned: it carries an
   `:id`, a `:phase`, optionally a `:doc`, and a `::runner` function of
   one argument that returns a vector of production maps.

   Intended for compilers that emit runnable rules without expressible
   data-form templates — the mapping compiler is the only V1 user. Plain
   rule authors should keep using `compile-rule`."
  [{:keys [id phase doc runner]}]
  (when-not (keyword? id)
    (throw (ex-info "compiled-rule requires a keyword :id" {:id id})))
  (when-not (some? phase)
    (throw (ex-info "compiled-rule requires a :phase" {:id id :phase phase})))
  (when-not (fn? runner)
    (throw (ex-info "compiled-rule requires a callable :runner"
                    {:id id :runner runner})))
  (cond-> {:id id :phase phase ::runner runner}
    doc (assoc :doc doc)))

(ns palomar.plugins.mapping
  "Mapping schema and compiler (ADR 0009).

   A `mapping` declares how one native predicate of a source format
   becomes one canonical predicate, optionally through a transform
   chain (ADR 0010) and with a policy for how to behave when the source
   is absent. Mappings are data — a vector of `MappingRule` maps in the
   plugin's `:mapping` slot (ADR 0007). The runtime expands them into
   `:normalize`-phase compiled rules at register time.

   The compiler is a pure function `MappingRule × stdlib → compiled
   rule`. Its output is runtime-shaped: `apply-rule` cannot tell a
   mapping-compiled rule from a data-form-compiled rule. This shape
   choice — compiled rules with bespoke runners, not data-form Rule
   templates — is the V1 reconciliation between two facts: (a) the
   rule DSL deliberately disallows applying a Clojure function to a
   bound value (ADR 0002), and (b) the mapping abstraction needs
   exactly that, because transforms are functions. ADR 0009 §Schema
   spells out the consequence: mappings remain data sugar, while
   compilation goes straight to a runner rather than to a data-form
   rule.

   Qualified mappings rename the same source predicate that appears on
   both the record (as a reference value) and on the fragment (as the
   primitive value coord), plus a second predicate carrying the
   qualifier (e.g. `:xml/lang`). All three renames happen inside a
   single compiled rule's runner — one mapping = one rule entry in the
   trace, regardless of qualifier presence. The shape adapter (M5) is
   the only V1 source of fragments (ADR 0011 §Limitation forbids the
   rule layer from minting them), so the qualified-mapping compiler
   assumes the fragments already exist on the record at `:normalize`
   time."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [palomar.diagnostics :as dx]
            [palomar.model :as model]
            [palomar.plugins.transforms :as tx]
            [palomar.rules :as rules]))

;; ---------------------------------------------------------------------------
;; Schema
;;
;; Closed shape: every key is named. Extensions go through the transform
;; stdlib (ADR 0010), not through new top-level mapping keys. The
;; cross-field invariant `:mapping/on-empty :default ⇔ :mapping/default
;; present` is enforced by an `:fn` predicate — biconditional, so a stray
;; `:mapping/default` with `:on-empty :skip` is caught as a likely typo.
;; ---------------------------------------------------------------------------

(def Qualifier
  "Shape of the `:mapping/qualifier` sub-map, in one of two forms:

   - `{:from :as}` — `:from` names the source attribute carrying the
     qualifier (e.g. `:xml/lang`); the shape adapter mints the fragments
     at ingest and normalize renames the coord to `:as` (e.g. `:canon/lang`).
   - `{:value :as}` — a *constant* qualifier the rule itself knows (e.g.
     `dc:publisher` is always `:canon/role :publisher`). Normalize mints one
     fragment per record-level occurrence, moves the value onto it, and adds
     the `:as` coord with `:value`.

   All are keywords; namespacing is conventional but not enforced here —
   the canonical vocabulary imposes its own discipline at its own layer."
  [:or
   [:map {:closed true}
    [:from :keyword]
    [:as   :keyword]]
   [:map {:closed true}
    [:value :keyword]
    [:as    :keyword]]])

(def MappingRule
  "Malli schema for one mapping rule, per ADR 0009 §Decision.

   `:mapping/from` is either a single source predicate (the flat rename) or a
   *vector* of predicates to **combine** — their values on a subject are joined, in
   order, by the required `:mapping/combine` separator, into one canonical value
   (e.g. INTERMARC `100 $a` + `$m` → \"Surname, Forename\"; a title + its GMD `$h`).
   This recombines subfields the parser split, without a bespoke pre-pass."
  [:and
   [:map {:closed true}
    [:mapping/id         :keyword]
    [:mapping/from       [:or :keyword [:vector {:min 1} :keyword]]]
    [:mapping/to         :keyword]
    [:mapping/combine    {:optional true} :string]
    [:mapping/transform  {:optional true} [:vector :keyword]]
    [:mapping/qualifier  {:optional true} Qualifier]
    [:mapping/on-empty   {:optional true} [:enum :skip :diagnostic :default]]
    [:mapping/default    {:optional true}
     [:fn {:error/message ":mapping/default cannot be nil — it would emit a shape-invalid assertion"}
      some?]]
    [:mapping/confidence {:optional true} model/Confidence]
    [:mapping/doc        {:optional true} :string]]
   [:fn {:error/message
         ":mapping/on-empty :default ⇔ :mapping/default (both or neither)"}
    (fn [m]
      (= (= :default (:mapping/on-empty m))
         (contains? m :mapping/default)))]
   [:fn {:error/message
         ":mapping/combine ⇔ :mapping/from is a vector (a combine needs a separator, and vice versa)"}
    (fn [m]
      (= (vector? (:mapping/from m))
         (contains? m :mapping/combine)))]
   [:fn {:error/message
         ":mapping/qualifier :from requires a single-keyword :mapping/from (it cannot qualify a combine)"}
    (fn [m]
      (or (not (contains? (:mapping/qualifier m) :from))
          (keyword? (:mapping/from m))))]])

(defn valid-mapping?
  "True if `mapping-rule` conforms to the MappingRule schema."
  [mapping-rule]
  (m/validate MappingRule mapping-rule))

(defn explain-mapping
  "Return a Malli explanation map describing why `mapping-rule` is
   invalid, or nil if it is valid."
  [mapping-rule]
  (m/explain MappingRule mapping-rule))

(defn- validate-or-throw! [mapping-rule]
  (when-let [err (explain-mapping mapping-rule)]
    (throw (ex-info "Invalid mapping rule"
                    {:mapping-id  (:mapping/id mapping-rule)
                     :explanation err})))
  mapping-rule)

;; ---------------------------------------------------------------------------
;; Rule-id derivation
;;
;; The compiled rule's id is derived deterministically from the mapping
;; id so trace queries can route between the two. The convention:
;;
;;   :map/dc-title       → :rule.from-mapping/dc-title
;;   :anything/foo-bar   → :rule.from-mapping/foo-bar
;;
;; Only the `name` portion of the mapping id is kept; cross-plugin
;; uniqueness of compiled rule ids is the registry layer's concern
;; (plugins ship their own mapping namespaces and authors are expected
;; to use distinctive names). ADR 0009 §Consequences cites this exact
;; example.
;; ---------------------------------------------------------------------------

(defn mapping-rule-id
  "Derive the compiled-rule id from a `:mapping/id` keyword. See the
   namespace doc and ADR 0009 §Consequences for the convention.

   Cross-plugin collisions are not detected by *this* function: two
   mapping rules sharing the same `:mapping/id` name portion (across
   different plugins) produce identical compiled rule ids, which would
   conflate in provenance traces. `compile-mappings` rejects such a
   batch at compile time; plugin authors should still pick distinctive
   `:mapping/id` names. See ADR 0009 §Open V2 questions."
  [mapping-id]
  (when-not (keyword? mapping-id)
    (throw (ex-info "mapping-rule-id requires a keyword mapping id"
                    {:mapping-id mapping-id})))
  (keyword "rule.from-mapping" (name mapping-id)))

;; ---------------------------------------------------------------------------
;; Production helpers
;;
;; Productions are the contract between a rule's runner and the runtime:
;; `{:kind :assertion :value <Assertion>}` or
;; `{:kind :diagnostic :value <Diagnostic>}`. Mapping-compiled rules
;; produce the same shapes a data-form rule's `:assert` / `:diagnostic`
;; actions would.
;; ---------------------------------------------------------------------------

(defn- normalize-provenance [rule-id]
  {:rule rule-id :pass :normalize})

(defn- assertion-production
  [{:keys [subject predicate value confidence rule-id]}]
  {:kind :assertion
   :value (model/assertion
           {:subject    subject
            :predicate  predicate
            :value      value
            :confidence confidence
            :status     :asserted
            :provenance (normalize-provenance rule-id)})})

(defn- diagnostic-production
  [{:keys [severity code subject message rule-id]}]
  {:kind :diagnostic
   :value (model/diagnostic
           {:severity   severity
            :code       code
            :subject    subject
            :message    message
            :provenance (normalize-provenance rule-id)})})

(defn- coerced-production
  "An import-edge `:coerced` loss (ADR 0015) for a value represented through a
   lossy transform chain. The native predicate `from` is the lost source field."
  [{:keys [subject from chain rule-id]}]
  {:kind :diagnostic
   :value (dx/loss {:category     :coerced
                    :subject      subject
                    :edge         :import
                    :source-field from
                    :message      (str "lossy transform chain " (pr-str chain)
                                       " discarded detail")
                    :provenance   (normalize-provenance rule-id)})})

(defn- truncate
  "Trim a stringified value to at most `n` chars with an ellipsis. Used
   only in diagnostic messages so a `:transform-failed` on a 10 kB blob
   doesn't dump the blob into the diagnostic vector."
  [s n]
  (if (<= (count s) n) s (str (subs s 0 n) "…")))

(defn- transform-failed-message [chain original-value]
  (str "Transform chain " (pr-str chain)
       " yielded nil for value " (truncate (pr-str original-value) 80)))

;; ---------------------------------------------------------------------------
;; Compilation
;;
;; The runner filters the record's triples by source predicate and emits
;; productions per match. The transform chain applies to primitive values
;; only; non-primitive values (reference, structured, uncertain — see
;; `palomar.model/primitive-value?`) pass through unchanged. This split
;; matters for qualified mappings: the same source predicate appears on
;; the record carrying a reference value (which must rename through
;; untouched) and on the fragment carrying the primitive value (which the
;; transform may target). A blanket "always apply the chain" policy would
;; mis-fire on the reference value, since string transforms short-circuit
;; on maps; the early-skip avoids spurious `:transform-failed` diagnostics
;; in that case.
;;
;; When the chain is non-empty and the value is primitive but the chain
;; yields nil, the rule emits a `:transform-failed` info diagnostic per
;; ADR 0009 §"Open V2 questions" §"Confidence inheritance" ("failure
;; surfaces as a diagnostic"). When no triple matches the source
;; predicate at all, `:on-empty` decides: skip, emit a
;; `:missing-source-predicate` info diagnostic, or emit the default-valued
;; canonical assertion on the record id.
;;
;; The default value bypasses the transform chain — `:mapping/default`
;; is the canonical-side fallback, not source-side. ADR 0009 doesn't
;; spell this out explicitly but it is the only coherent reading: the
;; mapping author wrote the default in the canonical vocabulary.
;;
;; The qualifier-rename block is independent of the on-empty branch: it
;; runs whenever any triple matches `:qualifier/from`, regardless of
;; whether the main predicate is present. In a well-formed record, the
;; qualifier triples live on fragments minted via the main predicate, so
;; the two presence sets agree — but the runner doesn't enforce that
;; correlation, and a stray qualifier triple still gets renamed.
;; Qualifier values are not transformed (transforms target the value
;; coord, not the qualifier).
;; ---------------------------------------------------------------------------

(defn- distinct-placements
  "`matches` (`[s p v]` triples) without the ones whose value, once transformed
   by `transform` (when `chain` is non-empty), equals an earlier one's on the
   same subject. Keeps first-occurrence order."
  [transform chain matches]
  (let [norm (fn [[s _ v]] [s (if (and (seq chain) (model/primitive-value? v)) (transform v) v)])]
    (first (reduce (fn [[acc seen] m]
                     (let [k (norm m)]
                       (if (contains? seen k) [acc seen] [(conj acc m) (conj seen k)])))
                   [[] #{}] matches))))

(defn- compile-mapping-rule
  "Compile one mapping rule into a single compiled rule whose runner
   handles both the flat-rename case and, when `:mapping/qualifier` is
   present, the additional qualifier rename on fragments. Assumes
   `mapping-rule` is already schema-validated."
  [mapping-rule transforms-stdlib]
  (let [from       (:mapping/from mapping-rule)
        multi?     (vector? from)
        from-preds (if multi? from [from])
        from-set   (set from-preds)
        sep        (:mapping/combine mapping-rule)
        to         (:mapping/to mapping-rule)
        chain      (get mapping-rule :mapping/transform [])
        transform  (tx/compose transforms-stdlib chain)
        lossy?     (boolean (some tx/lossy? chain))
        on-empty   (get mapping-rule :mapping/on-empty :skip)
        confidence (get mapping-rule :mapping/confidence 1.0)
        default    (:mapping/default mapping-rule)
        rule-id    (mapping-rule-id (:mapping/id mapping-rule))
        doc        (:mapping/doc mapping-rule)
        qualifier  (:mapping/qualifier mapping-rule)
        q-from     (:from qualifier)
        q-as       (:as qualifier)
        constant?  (contains? qualifier :value)
        q-value    (:value qualifier)
        anchor     (first from-preds)

        assert-at  (fn [s p v] (assertion-production {:subject s :predicate p :value v
                                                      :confidence confidence :rule-id rule-id}))

        ;; Place one canonical value. Without a constant qualifier it is the plain
        ;; `[s to v]`. With one (ADR 0021), a record-level primitive moves onto a
        ;; fragment minted from the native predicate, the occurrence index and the
        ;; coord (`[anchor idx coord 0]`, ADR 0012 — the coord segment keeps these
        ;; ids apart from ingest fragments such as MARC's `[:ns/fTAG n]`), which
        ;; also carries the coord: the shape the shape adapter gives an
        ;; `{:from :as}` qualifier, so readers see one form. A value whose subject
        ;; is already a fragment gets the coord on that fragment.
        place
        (fn [rid s idx v]
          (cond
            (not (and constant? (model/primitive-value? v)))
            [(assert-at s to v)]

            (= s rid)
            (let [fid (model/mint-fragment-id s [anchor idx q-as 0])]
              [{:kind :fragment :value (model/fragment {:id fid :source [:normalize anchor idx]})}
               (assert-at s to (model/reference fid))
               (assert-at fid to v)
               (assert-at fid q-as q-value)])

            :else
            [(assert-at s to v) (assert-at s q-as q-value)]))

        ;; A match yields a seq of productions: the rename assertion, plus a
        ;; `:coerced` loss only when a lossy transform *actually changed* the
        ;; value (ADR 0015; audit R4). A lossy transform that is a no-op on this
        ;; value — lowercasing already-lowercase text — discarded nothing, so it
        ;; is no loss; reporting one anyway over-counts coercion.
        emit-rename-match
        (fn [rid idx [s _p v]]
          (cond
            (nil? v)
            []

            (or (empty? chain) (not (model/primitive-value? v)))
            (place rid s idx v)

            :else
            (let [v' (transform v)]
              (if (some? v')
                (cond-> (place rid s idx v')
                  (and lossy? (not= v' v))
                  (conj (coerced-production
                         {:subject s :from from :chain chain :rule-id rule-id})))
                [(diagnostic-production
                  {:severity :info :code :transform-failed
                   :subject s :rule-id rule-id
                   :message (transform-failed-message chain v)})]))))

        emit-qualifier-match
        (fn [[s _p v]]
          (when (some? v)
            (assertion-production
             {:subject s :predicate q-as :value v
              :confidence confidence :rule-id rule-id})))

        emit-on-empty
        (fn [record-id]
          (case on-empty
            :skip       []
            :diagnostic [(diagnostic-production
                          {:severity :info :code :missing-source-predicate
                           :subject record-id :rule-id rule-id
                           :message (str "Source predicate " from
                                         " not present on record")})]
            :default    (place record-id record-id 0 default)))

        ;; Combine: for a subject, gather each `from` predicate's (first primitive)
        ;; value in `from-preds` order, join the present ones with `sep`, then run
        ;; the transform chain on the joined string. Absent subfields are skipped
        ;; (no dangling separator); the join is the only place the chain sees a
        ;; multi-source value, so the `:coerced`/`:transform-failed` accounting
        ;; mirrors the single-source path.
        emit-combine
        (fn [rid idx s triples]
          (let [parts  (for [fp from-preds
                             :let [v (some (fn [[s2 p2 v2]]
                                             (when (and (= s2 s) (= p2 fp)
                                                        (model/primitive-value? v2))
                                               v2))
                                           triples)]
                             :when (some? v)]
                         (str v))
                joined (when (seq parts) (str/join sep parts))]
            (when joined
              (let [v' (if (empty? chain) joined (transform joined))]
                (if (some? v')
                  (cond-> (place rid s idx v')
                    (and lossy? (not= v' joined))
                    (conj (coerced-production
                           {:subject s :from (first from-preds) :chain chain :rule-id rule-id})))
                  [(diagnostic-production
                    {:severity :info :code :transform-failed
                     :subject s :rule-id rule-id
                     :message (transform-failed-message chain joined)})])))))

        runner
        (fn run [record]
          (let [triples (rules/record-triples record)
                rid     (:id record)]
            (if multi?
              (let [subjects (distinct (keep (fn [[s p _]] (when (from-set p) s)) triples))
                    prods    (into [] (apply concat
                                             (map-indexed #(emit-combine rid %1 %2 triples) subjects)))]
                (if (seq prods) prods (emit-on-empty rid)))
              (let [from-matches (cond->> (filterv (fn [[_ p _]] (= p from)) triples)
                                     ;; one fragment per distinct value, as an unqualified
                                     ;; value collapses at merge (ADR 0008)
                                   constant? (distinct-placements transform chain))
                    base-prods   (if (seq from-matches)
                                   (into [] (apply concat
                                                   (map-indexed #(emit-rename-match rid %1 %2) from-matches)))
                                   (emit-on-empty rid))]
                (if q-from
                  ;; only the coords of a subject that carries `from`: another
                  ;; field's fragment with the same coord is that field's rule's
                  (let [carriers     (into #{} (keep (fn [[s p _]] (when (= p from) s))) triples)
                        qual-matches (filterv (fn [[s p _]] (and (= p q-from) (carriers s))) triples)]
                    (into base-prods (keep emit-qualifier-match) qual-matches))
                  base-prods)))))]
    (rules/compiled-rule {:id rule-id :phase :normalize :doc doc :runner runner})))

;; ---------------------------------------------------------------------------
;; Public compiler
;; ---------------------------------------------------------------------------

(defn compile-mapping
  "Compile a single mapping rule into a runtime-shaped compiled rule.

   `transforms-stdlib` is the effective transform stdlib (typically
   `(:transforms (palomar.plugins/effective-stdlib registry))`).
   Transform-name resolution happens once at compile time; unknown
   names throw immediately rather than at first-record-time.

   Handles both flat and qualified mapping rules. A qualified mapping
   compiles to a single rule whose runner renames the source predicate
   (on every triple it appears in, primitive values transformed,
   non-primitive values passed through) and renames the qualifier
   predicate on every fragment that carries it. Throws ex-info on
   schema violations or unknown transform names.

   Non-primitive values (reference, structured, uncertain — per
   `palomar.model/primitive-value?`) bypass the transform chain
   entirely and pass through unchanged. This is essential for
   qualified mappings, where the source predicate carries both
   record-level reference values and fragment-level primitives;
   without the early skip, string transforms would short-circuit on
   the reference map and emit a spurious `:transform-failed`
   diagnostic."
  [mapping-rule transforms-stdlib]
  (validate-or-throw! mapping-rule)
  (compile-mapping-rule mapping-rule transforms-stdlib))

(defn- check-distinct-rule-ids!
  "Reject a batch in which two mapping rules derive the same compiled rule
   id. `mapping-rule-id` keeps only the *name* portion of `:mapping/id`, so
   `:plugin-a/dc-title` and `:plugin-b/dc-title` both derive
   `:rule.from-mapping/dc-title` and would conflate silently in the
   provenance trace. The ex-data maps each colliding rule id to the source
   `:mapping/id`s responsible. Non-keyword / absent ids are ignored here:
   per-rule schema validation in `compile-mapping` reports those with a full
   explanation."
  [mapping-rules]
  (let [ids        (filter keyword? (map :mapping/id mapping-rules))
        collisions (into {}
                         (keep (fn [[derived src-ids]]
                                 (when (> (count src-ids) 1)
                                   [derived (vec src-ids)])))
                         (group-by mapping-rule-id ids))]
    (when (seq collisions)
      (throw (ex-info (str "Mapping rules derive colliding compiled rule ids; "
                           "their provenance would conflate. Pick distinct "
                           ":mapping/id name portions.")
                      {:collisions collisions}))))
  mapping-rules)

(defn compile-mappings
  "Compile a vector of mapping rules, in order. Fails fast on the first
   invalid or unsupported mapping rule. Rejects the whole batch if two
   rules derive the same compiled rule id — their provenance would
   otherwise conflate silently in the trace (ADR 0009 §Open V2)."
  [mapping-rules transforms-stdlib]
  (check-distinct-rule-ids! mapping-rules)
  (mapv #(compile-mapping % transforms-stdlib) mapping-rules))

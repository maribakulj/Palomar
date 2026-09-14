(ns palomar.curate
  "Repair-application / curation engine — the ADR 0005 `apply-repairs` workflow as
   a pure, auditable function over a record's *pending* assertions.

   A pipeline's `:infer`/`:repair` phases emit assertions as `:proposed` (the
   string-key WEMI inference in `palomar.plugins.lrmoo.project`, the creator-only
   Work in `intermarc.wemi`, the fuzzy reconciliation tier of ADR 0018): claims
   the machine generated but did not auto-confirm. Curation is the human (or
   policy) step that resolves each pending claim into the *workflow* family
   (ADR 0005):

     :accept → :accepted     :reject → :rejected     :review → :needs-review

   `decide` is the curator: `(fn [assertion] -> :accept | :reject | :review)`. The
   named `accept-all` / `reject-all` / `flag-all` / `accept-when` policies are
   ordinary decision functions, so an ADR 0018 promotion guard composes —
   `(accept-when certifiable?)` accepts only the proposals safe to commit and
   routes the rest (the Victor-Hugo-metro-station match) to review, never silently.

   It is a pure function of (record, decision): no clock, no I/O, deterministic, so
   it is re-runnable and testable. Curation acts only on *pending* assertions
   (`:proposed` or `:needs-review`); everything in force or already resolved is
   left untouched. The transition log it returns is the audit record — the
   Provenance schema deliberately does not model a from→to history (ADR 0005 keeps
   the decision in `:status`), so curation *reports* transitions rather than
   stamping them.

   Out of scope here (documented, not done): supersession of an in-force assertion
   that an accepted proposal replaces (ADR 0005's conditional \"may cause the
   original to become :superseded\"). It needs replacement semantics the proposal
   does not yet carry — the core models no predicate cardinality, so the engine
   cannot tell a correction from an addition. Left to a later, explicit step."
  (:require [clojure.string :as str]
            [palomar.model :as model]))

(def ^:private verdict->status
  "The ADR 0005 workflow transition: a curation verdict to its resulting status."
  {:accept :accepted
   :reject :rejected
   :review :needs-review})

(def verdicts
  "The decision values a curator may return."
  (set (keys verdict->status)))

;; ---------------------------------------------------------------------------
;; Engine
;; ---------------------------------------------------------------------------

(defn- curate-assertion
  "Resolve one assertion. If `a` is pending, apply `decide` and transition its
   status; otherwise leave it untouched. Returns `[a' transition-or-nil]`."
  [decide a]
  (if (model/pending? a)
    (let [verdict (decide a)
          status  (verdict->status verdict)]
      (when-not status
        (throw (ex-info "Curation decision must be :accept, :reject or :review"
                        {:verdict verdict :assertion a})))
      [(assoc a :status status)
       {:subject   (:subject a)
        :predicate (:predicate a)
        :from      (:status a)
        :to        status
        :verdict   verdict}])
    [a nil]))

(defn curate-record
  "Apply curation `decide` to every *pending* assertion of `record` (ADR 0005).
   Returns `{:record record' :transitions [..]}` — `record'` with pending statuses
   resolved, and the ordered transition log (one entry per assertion decided).
   Non-pending assertions (`:asserted`, already `:accepted`/`:rejected`,
   `:retracted`, `:superseded`) pass through unchanged; a record with no
   assertions is returned untouched."
  [record decide]
  (if-let [assertions (seq (:assertions record))]
    (let [pairs (map #(curate-assertion decide %) assertions)]
      {:record      (assoc record :assertions (mapv first pairs))
       :transitions (vec (keep second pairs))})
    {:record record :transitions []}))

(defn summarise
  "Counts of a transition log by resulting status, plus the total."
  [transitions]
  (let [by-to (frequencies (map :to transitions))]
    {:accepted     (get by-to :accepted 0)
     :rejected     (get by-to :rejected 0)
     :needs-review (get by-to :needs-review 0)
     :total        (count transitions)}))

(defn curate
  "Curate a batch of `records` with one decision function `decide`. Returns
   `{:records [..] :transitions [..] :summary {..}}` — the curated records, the
   concatenated transition log, and `summarise`d counts."
  [records decide]
  (let [results     (map #(curate-record % decide) records)
        transitions (vec (mapcat :transitions results))]
    {:records     (mapv :record results)
     :transitions transitions
     :summary     (summarise transitions)}))

;; ---------------------------------------------------------------------------
;; Policies — ordinary decision functions
;; ---------------------------------------------------------------------------

(def accept-all
  "Policy: accept every pending proposal (`:proposed`/`:needs-review` → `:accepted`)."
  (constantly :accept))

(def reject-all
  "Policy: reject every pending proposal."
  (constantly :reject))

(def flag-all
  "Policy: route every pending proposal to human review (`→ :needs-review`). The
   conservative default — a machine pass hands off without auto-committing."
  (constantly :review))

(defn accept-when
  "Policy: accept proposals satisfying `pred`, route the rest to review. Composes
   a promotion guard (ADR 0018) — e.g. `(accept-when #(>= (:confidence %) 0.9))`
   auto-accepts high-confidence claims and flags the rest, never auto-rejecting."
  [pred]
  (fn [a] (if (pred a) :accept :review)))

;; ---------------------------------------------------------------------------
;; Reporting
;; ---------------------------------------------------------------------------

(defn format-curation
  "Human-readable rendering of a `curate` *or* `curate-record` result, for CLI /
   audit. `policy-label` names the policy applied (e.g. \"accept\"). Tolerates a
   single-record result (which carries `:transitions` but no `:summary`) by
   deriving the summary from the transitions, so the two public entry points are
   interchangeable here."
  [{:keys [transitions summary]} policy-label]
  (let [summary (or summary (summarise transitions))]
    (if (zero? (:total summary))
      "apply-repairs: no pending proposals to curate."
      (str/join
       "\n"
       (cons (str "apply-repairs (" policy-label "): " (:total summary) " proposal"
                  (when (not= 1 (:total summary)) "s") " curated — "
                  (:accepted summary) " accepted, "
                  (:rejected summary) " rejected, "
                  (:needs-review summary) " needs-review")
             (for [{:keys [subject predicate from to]} transitions]
               (str "  " subject "  " predicate "  " (name from) " → " (name to))))))))

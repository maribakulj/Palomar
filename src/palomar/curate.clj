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
   left untouched. The transition log it returns is the run's audit record, and
   each decision is also stamped on the record as an annotation about the
   assertion it decided (ADR 0028): the status says where a claim stands, the
   annotations (`history`) how it got there, who decided and why.

   Supersession of an in-force assertion (ADR 0005's conditional \"may cause the
   original to become :superseded\") is not inferred: the core models no
   predicate cardinality, so the engine cannot tell a correction from an
   addition. It is an explicit step instead, `supersede`, which marks the old
   assertion `:superseded`, adds its replacement and logs both with a reason.
   A decision function may likewise return `[verdict reason]` so the log keeps
   why each claim was accepted, rejected or flagged (audit S28)."
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

(def decision-predicate
  "The annotation predicate a curation decision is recorded under (ADR 0028)."
  :palomar.curation/decision)

(defn- decision-annotation
  "The annotation recording `transition` about assertion `a`: a structured value
   with the verdict, the statuses, the reason and the curator context (`:by`,
   `:at`), each field present only when known. `curated?` marks an
   acceptance or a supersession made one claim at a time (`decide-one`,
   `supersede`), never one a policy applied to a batch (`curate-record`,
   `curate`): `:curated true`. The context never sets it (ADR 0061: an
   export writes `owl:sameAs` only for a curated, signed acceptance)."
  ([a transition context] (decision-annotation a transition context false))
  ([a transition context curated?]
   (let [fields (cond-> {:verdict (:verdict transition) :to (:to transition)}
                  (and curated? (contains? #{:accept :supersede} (:verdict transition))) (assoc :curated true)
                  (:from transition)        (assoc :from (:from transition))
                  (:reason transition)      (assoc :reason (:reason transition))
                  (:replaced-by transition) (assoc :replaced-by (model/assertion-id (:replaced-by transition)))
                  (:by context)             (assoc :by (:by context))
                  (:at context)             (assoc :at (:at context)))]
     (model/annotation {:about     a
                        :predicate decision-predicate
                        :value     (model/structured fields)
                        :provenance (model/provenance {:rule :curate/decision})}))))

(defn- curate-assertion
  "Resolve one assertion. If `a` is pending, apply `decide` and transition its
   status; otherwise leave it untouched. `decide` returns a verdict, or
   `[verdict reason]` to record why (audit S28). Returns `[a' transition-or-nil]`."
  [decide a]
  (if (model/pending? a)
    (let [decision         (decide a)
          [verdict reason] (if (vector? decision) decision [decision nil])
          status           (verdict->status verdict)]
      (when-not status
        (throw (ex-info "Curation decision must be :accept, :reject or :review"
                        {:verdict verdict :assertion a})))
      [(assoc a :status status)
       (cond-> {:subject   (:subject a)
                :predicate (:predicate a)
                :from      (:status a)
                :to        status
                :verdict   verdict}
         reason (assoc :reason reason))])
    [a nil]))

(declare curate-record*)

(defn curate-record
  "Apply curation `decide` to every *pending* assertion of `record` (ADR 0005).
   Returns `{:record record' :transitions [..]}` — `record'` with pending statuses
   resolved, and the ordered transition log (one entry per assertion decided).

   Each decision is also kept on `record'` itself, as an annotation about the
   assertion it decided (`decision-predicate`, ADR 0028), so the history travels
   with the record and is never lost when the status is overwritten. `context`
   (optional) names who decided and when — `{:by \"curator\" :at #inst …}`; the
   engine reads no clock, the caller supplies the time.

   Non-pending assertions (`:asserted`, already `:accepted`/`:rejected`,
   `:retracted`, `:superseded`) pass through unchanged; a record with no
   assertions is returned untouched."
  ([record decide] (curate-record record decide {}))
  ([record decide context] (curate-record* record decide context false)))

(defn- curate-record*
  "`curate-record`, its acceptances marked `:curated` when `curated?` (one
   claim at a time, `decide-one`)."
  [record decide context curated?]
  (if-let [assertions (seq (:assertions record))]
    (let [pairs (map #(curate-assertion decide %) assertions)
          notes (for [[a [_ t]] (map vector assertions pairs) :when t]
                  (decision-annotation a t context curated?))]
      {:record      (cond-> (assoc record :assertions (mapv first pairs))
                      (seq notes) (update :annotations (fnil into []) notes))
       :transitions (vec (keep second pairs))})
    {:record record :transitions []}))

(defn history
  "The curation decisions recorded about assertion `a` (an assertion or its id)
   on `record`, oldest first, as maps (`:verdict :from :to :reason :by :at
   :replaced-by`, as known)."
  [record a]
  (for [n (model/annotations-of record a)
        :when (= decision-predicate (:predicate n))]
    (:value/fields (:value n))))

(defn summarise
  "Counts of a transition log by resulting status, plus the total."
  [transitions]
  (let [by-to (frequencies (map :to transitions))]
    {:accepted     (get by-to :accepted 0)
     :rejected     (get by-to :rejected 0)
     :needs-review (get by-to :needs-review 0)
     :superseded   (get by-to :superseded 0)
     :total        (count transitions)}))

(defn supersede
  "Replace, in `record`, the in-force assertion `old` by `new` (ADR 0005's
   supersession, an explicit step since the core models no cardinality and so
   cannot tell a correction from an addition). The record's matching assertion
   becomes `:superseded`; `new` is added `:accepted`, built through
   `model/assertion` with provenance naming this step — or, when the record
   already holds that fact out of force (a claim rejected or superseded
   earlier), that claim is put back `:accepted`, never copied: a record holds
   one claim per fact (`model/duplicate-assertion-ids`). Returns
   `{:record record' :transitions [..]}`, the log naming the replacement and the
   `reason` (ADR 0023); the decision is also kept as an annotation about `old`
   (ADR 0028), with the optional `context` (`:by`, `:at`). Throws when `old` is
   not an in-force assertion of `record`, or when `new` is the same fact."
  ([record old new reason] (supersede record old new reason {}))
  ([record old new reason context]
   (let [same? #(and (= (:subject %) (:subject old)) (= (:predicate %) (:predicate old))
                     (= (:value %) (:value old)) (model/in-force? %))
         found (first (filter same? (:assertions record)))
         new-id (model/assertion-id new)
         again (first (filter #(= new-id (model/assertion-id %)) (:assertions record)))]
     (when-not found
       (throw (ex-info "supersede: no such in-force assertion" {:assertion old})))
     (when (= (model/assertion-id old) new-id)
       (throw (ex-info "supersede: the replacement is the same fact (same subject, predicate and value)"
                       {:assertion old})))
     {:record      (-> record
                       (update :assertions (partial mapv #(if (same? %) (assoc % :status :superseded) %)))
                       (update :assertions
                               (fn [as]
                                 (if again
                                   (mapv #(if (= new-id (model/assertion-id %)) (assoc % :status :accepted) %) as)
                                   (conj as (model/assertion
                                             (assoc (select-keys new [:subject :predicate :value :confidence])
                                                    :status     :accepted
                                                    :provenance (model/provenance {:rule :curate/supersede})))))))
                       (update :annotations (fnil conj [])
                               (decision-annotation found {:verdict :supersede :from (:status found)
                                                           :to :superseded :reason reason :replaced-by new}
                                                    context true)))
      :transitions [{:subject     (:subject found)
                     :predicate   (:predicate found)
                     :from        (:status found)
                     :to          :superseded
                     :verdict     :supersede
                     :replaced-by (select-keys new [:subject :predicate :value])
                     :reason      reason}]})))

(defn decide-one
  "Apply `verdict` (`:accept`, `:reject` or `:review`), with `reason`, to the
   one pending assertion of `record` that is the fact `a` (`curate-record`'s
   transition, annotation and log), every other assertion left as it is.
   Returns `{:record :transitions}`; a fact that is not pending is left as
   it is, with no transition."
  ([record a verdict reason] (decide-one record a verdict reason {}))
  ([record a verdict reason context]
   (let [id     (model/assertion-id a)
         target (first (filter #(= id (model/assertion-id %)) (:assertions record)))]
     (if-not (and target (model/pending? target))
       {:record record :transitions []}
       (let [{cur :record ts :transitions}
             (curate-record* (assoc record :assertions [target] :annotations []) (constantly [verdict reason]) context true)]
         {:record      (-> record
                           (update :assertions (partial mapv #(if (= id (model/assertion-id %)) (first (:assertions cur)) %)))
                           (update :annotations (fnil into []) (:annotations cur)))
          :transitions ts})))))

(defn reopen
  "Put the out-of-force assertion of `record` that is the fact `a` (rejected,
   superseded or retracted) back to `:proposed`, with `reason`: a claim
   proposed again is the same claim, never a copy, and its history says it
   was reopened (`:verdict :reopen`, an annotation like any decision, with
   the optional `context`). Returns `{:record :transitions}`; throws when
   the record holds no such out-of-force assertion."
  ([record a reason] (reopen record a reason {}))
  ([record a reason context]
   (let [id    (model/assertion-id a)
         found (first (filter #(and (= id (model/assertion-id %)) (model/out-of-force? %)) (:assertions record)))]
     (when-not found
       (throw (ex-info "reopen: no such out-of-force assertion" {:assertion a})))
     (let [t {:subject (:subject found) :predicate (:predicate found) :from (:status found) :to :proposed
              :verdict :reopen :reason reason}]
       {:record      (-> record
                         (update :assertions (partial mapv #(if (= id (model/assertion-id %)) (assoc % :status :proposed) %)))
                         (update :annotations (fnil conj []) (decision-annotation found t context)))
        :transitions [t]}))))

(defn curate
  "Curate a batch of `records` with one decision function `decide`, and the
   optional curator `context` (`curate-record`). Returns `{:records [..]
   :transitions [..] :summary {..}}` — the curated records (each carrying its
   decisions as annotations), the concatenated transition log, and `summarise`d
   counts."
  ([records decide] (curate records decide {}))
  ([records decide context]
   (let [results     (map #(curate-record % decide context) records)
         transitions (vec (mapcat :transitions results))]
     {:records     (mapv :record results)
      :transitions transitions
      :summary     (summarise transitions)})))

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
   a promotion guard (ADR 0018) — e.g. `(accept-when (confidence-at-least 0.9))`
   auto-accepts high-confidence claims and flags the rest, never auto-rejecting."
  [pred]
  (fn [a] (if (pred a) :accept :review)))

(defn confidence-at-least
  "A guard for `accept-when`: the assertion states a confidence of at least
   `x`. One that states none is not scored, so it never passes: a threshold
   accepts a measure, never its absence (review of 2026-11-09, L2)."
  [x]
  (fn [a] (boolean (some-> (:confidence a) (>= x)))))

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
                  (:needs-review summary) " needs-review"
                  (when (pos? (:superseded summary 0))
                    (str ", " (:superseded summary) " superseded")))
             (for [{:keys [subject predicate from to reason]} transitions]
               (str "  " subject "  " predicate "  " (if from (name from) "-") " → " (name to)
                    (when reason (str "  (" reason ")")))))))))

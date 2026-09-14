(ns palomar.loss-report
  "The conversion loss report (ADR 0015's headline deliverable, finally
   assembled). The loss *producers* (intermarc.wemi, project, export, mapping) emit
   first-class loss diagnostics; this namespace aggregates them into the artifact
   an institution audits: a per-edge, per-category, **per-source-field** account
   of what a conversion did not carry across.

   It is the round-trip accounting ADR 0015 describes — for a source A converted
   through the pivot to a target B, *which of A's own fields survived*. It reads
   only the structural loss vocabulary (`:loss/*` detail), never documentary
   predicates, so it stays vocabulary-blind (ADR 0003).

   Honest scope: this reports the loss measured at both edges of one conversion.
   A *full* round-trip that re-imports B and diffs it against A needs a reverse
   importer (future); the per-field survival account here is the assemblable,
   institution-useful 90 %."
  (:require [clojure.string :as str]
            [palomar.diagnostics :as dx]))

(defn conversion-report
  "Assemble the loss report for a conversion from its loss diagnostics — the
   merge of import-edge loss (on the records, via `dx/collect-many`) and
   export-edge loss (from the exporter result). Returns:

     {:total           N   ; loss *events* — diagnostics, summed across edges
      :distinct-losses N   ; distinct [subject source-field], deduped across edges
      :by-category     {category -> count}         ; events, all edges
      :by-edge         {:import {:total :by-category :by-source-field}
                        :export {…}}
      :source-fields   [… distinct native fields lost, sorted …]}

   `:total` and `:distinct-losses` differ on purpose (audit R3): the same native
   field can be lost at *both* edges — unmapped on import and again unexpressible
   on export — so it raises `:total` by two events but is one distinct loss. The
   per-edge totals are clean (a field is flagged at most once per edge); read
   `:total` as field×edge events, `:distinct-losses` (and `:source-fields`) as
   the deduplicated 'which of my fields didn't survive' answer.

   `opts` may carry `:records` (count) for context; it is echoed back under
   `:records`."
  ([loss-diagnostics] (conversion-report loss-diagnostics {}))
  ([loss-diagnostics {:keys [records]}]
   (let [ls          (dx/losses loss-diagnostics)
         edge-report (fn [es]
                       {:total           (count es)
                        :by-category     (dx/count-by-loss-category es)
                        :by-source-field (dx/count-by-source-field es)})]
     (cond-> {:total           (count ls)
              :distinct-losses (count (distinct (map (juxt :subject dx/loss-source-field) ls)))
              :by-category     (dx/count-by-loss-category ls)
              :by-edge         (into {} (map (fn [[e es]] [e (edge-report es)]))
                                     (group-by dx/loss-edge ls))
              :source-fields   (vec (sort-by str (distinct (keep dx/loss-source-field ls))))}
       records (assoc :records records)))))

;; ---------------------------------------------------------------------------
;; Streaming fold (WP-7) — accumulate the report in bounded memory
;;
;; The batch `conversion-report` reads the whole loss-diagnostic vector (O(N)).
;; For a streaming corpus the same report is folded one record at a time into a
;; bounded accumulator: every component is a *count* (by-category / by-edge /
;; by-source-field) or the distinct **field** set — all bounded by the number of
;; distinct fields/categories/edges, never by the record count. The one component
;; that is genuinely O(N) — `:distinct-losses` (distinct [subject field] pairs, and
;; subject is per-record) — is omitted from the streamed report and is batch-only.
;; ---------------------------------------------------------------------------

(def empty-acc
  "The identity loss accumulator for a streaming fold."
  {:total 0 :by-category {} :by-edge {} :source-fields #{}})

(defn accumulate
  "Fold one record's `loss-diagnostics` into the bounded accumulator `acc`."
  [acc loss-diagnostics]
  (reduce (fn [a l]
            (let [cat  (dx/loss-category l)
                  edge (dx/loss-edge l)
                  f    (dx/loss-source-field l)]
              (cond-> (-> a
                          (update :total inc)
                          (update-in [:by-category cat] (fnil inc 0))
                          (update-in [:by-edge edge :total] (fnil inc 0))
                          (update-in [:by-edge edge :by-category cat] (fnil inc 0)))
                f (-> (update-in [:by-edge edge :by-source-field f] (fnil inc 0))
                      (update :source-fields conj f)))))
          acc
          (dx/losses loss-diagnostics)))

(defn finalize
  "Turn a streaming accumulator into a `conversion-report`-shaped map. Carries the
   per-edge / per-category / per-source-field counts **identical** to the batch
   report (categories are zero-padded the same way), but **not** `:distinct-losses`
   (batch-only; see ns) — so `(= (dissoc batch-report :distinct-losses) streamed)`.
   `opts` may carry `:records`."
  ([acc] (finalize acc {}))
  ([acc {:keys [records]}]
   (let [pad #(merge (zipmap dx/loss-categories (repeat 0)) %)]
     (cond-> {:total         (:total acc)
              :by-category   (pad (:by-category acc))
              :by-edge       (into {} (map (fn [[e er]] [e (update er :by-category pad)]))
                                   (:by-edge acc))
              :source-fields (vec (sort-by str (:source-fields acc)))}
       records (assoc :records records)))))

(defn- top-fields
  "The `n` most-lost source fields of a `by-source-field` map, as a string."
  [by-field n]
  (->> by-field
       (sort-by (juxt (comp - val) (comp str key)))
       (take n)
       (map (fn [[f c]] (str (pr-str f) " (" c ")")))
       (str/join ", ")))

(defn- category-line [by-cat]
  (->> by-cat
       (filter (comp pos? val))
       (sort-by (comp str key))
       (map (fn [[c n]] (str (name c) " " n)))
       (str/join ", ")))

(defn format-conversion-report
  "Human-readable rendering of a `conversion-report` (ADR 0015), for CLI /
   institutional audit. Leads with the deduplicated loss count, then the raw
   event total, then the clean per-edge breakdown (audit R3 — no merged,
   double-counting field line). Never prints; returns the empty-loss line
   when clean."
  [{:keys [total distinct-losses by-category by-edge records source-fields] :as _report}]
  (if (zero? total)
    (str "Loss report: lossless"
         (when records (str " (" records " records)")))
    (let [n-fields (count source-fields)
          head (str "Loss report — "
                    ;; :distinct-losses is batch-only; a streamed report omits it
                    ;; (it needs the O(N) [subject field] set), so lead with fields.
                    (when distinct-losses
                      (str distinct-losses (if (= 1 distinct-losses) " distinct loss" " distinct losses") " over "))
                    n-fields (if (= 1 n-fields) " native field" " native fields")
                    " (" total (if (= 1 total) " field×edge event" " field×edge events")
                    (when records (str ", " records (if (= 1 records) " record" " records")))
                    ")"
                    "\n  by category: " (category-line by-category))
          edge-block (fn [edge]
                       (when-let [er (get by-edge edge)]
                         (str "\n  " (name edge) " edge — " (:total er)
                              " (" (category-line (:by-category er)) ")"
                              "\n    fields: " (top-fields (:by-source-field er) 8))))]
      (str head (edge-block :import) (edge-block :export)))))

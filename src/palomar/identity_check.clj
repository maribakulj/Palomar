(ns palomar.identity-check
  "The identity conflicts an export finds itself, over the records it
   writes together (lot O2 review, ADR 0061 §3).

   The export rule writes `owl:sameAs` (or Linked Art's `equivalent`) only
   for an identity claim in no conflict. The conflicts the last clustering
   found are diagnostics on the records (`agent-cluster`); they still
   count. But a record may be curated after it was clustered, or never
   clustered. So the export checks the in-force `palomar:matchesAuthority`
   claims of the records it writes, with no clustering:

   - **one authority, two agents who cannot be one person**: two claims to
     one authority IRI whose local agents' life dates are incompatible
     (`agent-dates/compatibility`), or one agent's typed events cannot be
     the other's life (`agent-events/compatibility`, the events consistent
     with the agent's own dates): `:incompatible-dates`,
     `:incompatible-events`. The authority's dates, as the claim compared
     them, count as one more agent's;
   - **two agents kept apart, one authority**: two local agents the
     clustering is given as a cannot-link (a rejected `palomar:sameAgentAs`,
     `agent-cluster/clustering-input`), each with an in-force claim to one
     authority IRI (`:rejected-same-agent`, review of lot O3);
   - **one agent, two authorities**: one local agent with in-force claims
     to two authority IRIs (`:two-authorities`). Whether two authority
     records are one entity (ADR 0059) is the clustering's to say; the
     export does not read it.

   A local agent's dates are those its claim's provenance states
   (`?mention-dates`), and its events those its record's mentions give it
   (`mentions/mentions`, by its `:local` node). Each claim found is one
   `:reconcile/export-identity-conflict` diagnostic on its record. A
   streamed conversion exports one record at a time, so it checks within
   each record only."
  (:require [palomar.agent-cluster :as ac]
            [palomar.agent-dates :as dates]
            [palomar.agent-events :as events]
            [palomar.identity :as identity]
            [palomar.mentions :as mentions]
            [palomar.model :as model]))

(def code
  "The diagnostic of a conflict the export finds."
  :reconcile/export-identity-conflict)

(defn- profile [d] (if (string? d) (dates/parse-life-dates d) d))

(defn- claims
  "The in-force `matchesAuthority` claims of `records`, each with its
   record, local agent, authority IRI, and the dates its provenance states."
  [records]
  (for [r records
        :let [ents (into {} (map (juxt :id clojure.core/identity)) (:entities r))]
        a (:assertions r)
        :when (and (= identity/authority-match (:predicate a)) (model/in-force? a) (model/reference-value? (:value a)))
        :let [iri (:iri (ents (:value/target (:value a))))]
        :when iri]
    {:id    (model/assertion-id a) :record (:id r) :agent (:subject a) :iri iri
     :dates (profile (get-in a [:provenance :bindings '?mention-dates]))
     :authority-dates (profile (get-in a [:provenance :bindings '?authority-dates]))}))

(defn- events-of
  "`{local-agent [typed event ..]}` of `record`'s mentions."
  [record]
  (reduce (fn [m {:keys [local events]}] (if (and local (seq events)) (update m local (fnil into []) events) m))
          {} (mentions/mentions record)))

(defn- incompatible
  "Why two lives `x` and `y` (`{:dates :events}`) cannot be one, or nil."
  [x y]
  (let [against (fn [i j] (let [es (events/consistent-events (:events i) (:dates i))]
                            (and (seq es) (dates/dated? (:dates j))
                                 (= :incompatible (:verdict (events/compatibility es (:dates j)))))))]
    (cond
      (and (dates/dated? (:dates x)) (dates/dated? (:dates y))
           (= :incompatible (:verdict (dates/compatibility (:dates x) (:dates y)))))
      :incompatible-dates
      (or (against x y) (against y x))
      :incompatible-events)))

(defn conflicts
  "`{claim-id {:reason :with}}`: the in-force identity claims of `records`
   in conflict (see the ns doc), `:with` the other claim's id or the
   authority's IRI."
  [records]
  (let [cs     (vec (claims records))
        evs    (memoize (fn [rid] (events-of (first (filter #(= rid (:id %)) records)))))
        life   (fn [c] {:dates (:dates c) :events (get (evs (:record c)) (:agent c))})
        same   (for [[_ xs] (group-by :iri cs)
                     x xs y xs
                     :when (not= [(:record x) (:agent x)] [(:record y) (:agent y)])
                     :let [why (incompatible (life x) (life y))]
                     :when why]
                 [(:id x) {:reason why :with (:id y)}])
        auth   (for [c cs
                     :let [why (incompatible (life c) {:dates (:authority-dates c)})]
                     :when why]
                 [(:id c) {:reason why :with (:iri c)}])
        ;; two local agents the clustering keeps apart (a rejected
        ;; `sameAgentAs`, review of lot O3), each with an in-force claim to
        ;; one authority: both claims are in conflict
        apart  (into {} (for [{:keys [a b reason]} (:cannot-link (ac/clustering-input records)) :when (not= a b)]
                          [#{a b} reason]))
        kept   (for [[_ xs] (group-by :iri cs)
                     x xs y xs
                     :when (not= (:agent x) (:agent y))
                     :let [why (apart (hash-set (:agent x) (:agent y)))]
                     :when why]
                 [(:id x) {:reason why :with (:id y)}])
        two    (for [[_ xs] (group-by (juxt :record :agent) cs)
                     :when (< 1 (count (distinct (map :iri xs))))
                     x xs]
                 [(:id x) {:reason :two-authorities :with (first (remove #{(:iri x)} (sort (distinct (map :iri xs)))))}])]
    (reduce (fn [m [id v]] (if (contains? m id) m (assoc m id v))) {} (concat kept same auth two))))

(defn with-conflicts
  "`records` with a `code` diagnostic for each claim of theirs in conflict
   (`conflicts`), and those diagnostics: `{:records :diagnostics}`. A
   record with none is returned as it is."
  [records]
  (let [found (conflicts records)]
    (if (empty? found)
      {:records records :diagnostics []}
      (let [ds (for [r records a (:assertions r)
                     :let [id (model/assertion-id a) c (found id)]
                     :when (and c (identity/relation? (:predicate a)))]
                 [(:id r) (model/diagnostic
                           {:severity :warning :code code :subject (:subject a)
                            :message  (str "accepted identity claims that cannot all hold ("
                                           (name (:reason c)) "): written as palomar:matchesAuthority, not owl:sameAs")
                            :detail   {:reason (:reason c) :claims [id] :with (:with c)}})])
            by (group-by first ds)]
        {:records     (mapv (fn [r] (if-let [xs (by (:id r))] (update r :diagnostics (fnil into []) (map second xs)) r)) records)
         :diagnostics (mapv second ds)}))))

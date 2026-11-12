(ns palomar.plugins.agents
  "Role-aware reading of the canonical agents (`:canon/agent` qualified by
   `:canon/role`, see `palomar.plugins.canonical`).

   A spoke whose source states the responsibility (`dc:publisher`, MARC 700 vs
   100) puts each agent on a fragment carrying a `:canon/role` coord; a spoke
   whose source does not leaves a plain literal with no role. Consumers read
   both shapes through this namespace, so the choice of *which* agent counts as
   a creator lives in one place."
  (:require [palomar.identity :as identity]
            [palomar.model :as model]
            [palomar.plugins.qualified :as qualified]))

(defn occurrences
  "Every in-force `:canon/agent` literal of `record`, in assertion order, as
   `{:name string :role keyword-or-nil}`. `:role` is nil when the source did
   not state one."
  [record]
  (for [{:keys [value coord]} (qualified/values record :canon/agent :canon/role)]
    {:name value :role coord}))

(defn creators
  "The names of `record`'s creators: agents with role `:creator`, or, when there
   are none, agents with no role (the source did not say). A contributor or a
   publisher is never a creator."
  [record]
  (let [occs     (occurrences record)
        stated   (filter #(= :creator (:role %)) occs)
        unstated (filter #(nil? (:role %)) occs)]
    (vec (distinct (map :name (if (seq stated) stated unstated))))))

(defn with-role
  "The distinct names of `record`'s agents whose role is `role`."
  [record role]
  (vec (distinct (keep #(when (= role (:role %)) (:name %)) (occurrences record)))))

(def identified-kinds
  "The CRM classes of an identified agent: a Person, or a Group — a corporate
   body or a family."
  #{:crm/E21_Person :crm/E74_Group})

(defn identified
  "`record`'s identified agents, as `[{:id :iri :name :via}]`, each with the
   name it identifies. Two ways (lot O3, ADR 0063):
   - `:via :source`: an agent entity the source identifies, carrying an
     authority `:iri` (the INTERMARC ISNI of the 100 main entry; a record an
     older Palomar's `apply-agent-links` wrote), named by the string written
     on it (`:crm/P190_has_symbolic_content`), or, for the one with no name
     of its own, the record's first creator;
   - `:via :claim`: a local agent (an agent entity with no `:iri`) with an
     in-force `identity/authority-match` to an authority entity (`:iri`),
     which no conflict diagnostic of the record names
     (`identity/conflicted-claims`): the agent keeps its own node (`:id`),
     the authority is `:iri`, the name is the one written on the agent.
   `:name` is nil when it cannot be told (post-audit 2, D1: one name never
   stands for several agents)."
  [record]
  (let [ents    (into {} (map (juxt :id identity)) (:entities record))
        ids     (filter #(and (identified-kinds (:kind %)) (:iri %)) (:entities record))
        by-id   (into {} (map (juxt :id identity)) ids)
        live    (filter model/in-force? (:assertions record))
        names   (into {} (for [a live
                               :when (and (= :crm/P190_has_symbolic_content (:predicate a)) (string? (:value a)))]
                           [(:subject a) (:value a)]))
        written (for [a live
                      :when (and (= :crm/P190_has_symbolic_content (:predicate a))
                                 (by-id (:subject a)) (string? (:value a)))]
                  [(:subject a) (:value a)])
        named   (set (map first written))
        unnamed (remove #(named (:id %)) ids)
        lone    (when (= 1 (count unnamed)) (first (creators record)))
        held    (identity/conflicted-claims record)
        claimed (for [a live
                      :let [s (ents (:subject a))
                            t (when (model/reference-value? (:value a)) (ents (:value/target (:value a))))]
                      :when (and (= identity/authority-match (:predicate a)) s t
                                 (identified-kinds (:kind s)) (nil? (:iri s))
                                 (= :reconcile/authority (:kind t)) (:iri t)
                                 (not (contains? held (model/assertion-id a))))]
                  {:id (:id s) :iri (:iri t) :name (names (:id s)) :via :claim})]
    (vec (distinct
          (concat (for [[id nm] (distinct written)] {:id id :iri (:iri (by-id id)) :name nm :via :source})
                  (for [e unnamed] {:id (:id e) :iri (:iri e) :name lone :via :source})
                  claimed)))))

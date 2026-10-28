(ns palomar.plugins.agents
  "Role-aware reading of the canonical agents (`:canon/agent` qualified by
   `:canon/role`, see `palomar.plugins.canonical`).

   A spoke whose source states the responsibility (`dc:publisher`, MARC 700 vs
   100) puts each agent on a fragment carrying a `:canon/role` coord; a spoke
   whose source does not leaves a plain literal with no role. Consumers read
   both shapes through this namespace, so the choice of *which* agent counts as
   a creator lives in one place."
  (:require [palomar.model :as model]
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
  "`record`'s identified agents — the agent entities carrying an authority
   `:iri` — as `[{:id :iri :name}]`, each with the name it identifies: the
   string written on it (`:crm/P190_has_symbolic_content`, as
   `palomar.reconcile/apply-agent-links` writes it), or, for the one identified
   agent with no name of its own (the INTERMARC ISNI of the 100 main entry),
   the record's first creator. `:name` is nil when it cannot be told (post-audit
   2, D1: one name never stands for several agents)."
  [record]
  (let [ids     (filter #(and (identified-kinds (:kind %)) (:iri %)) (:entities record))
        by-id   (into {} (map (juxt :id identity)) ids)
        written (for [a (:assertions record)
                      :when (and (= :crm/P190_has_symbolic_content (:predicate a))
                                 (by-id (:subject a)) (string? (:value a)) (model/in-force? a))]
                  [(:subject a) (:value a)])
        named   (set (map first written))
        unnamed (remove #(named (:id %)) ids)
        lone    (when (= 1 (count unnamed)) (first (creators record)))]
    (vec (concat (for [[id nm] (distinct written)] {:id id :iri (:iri (by-id id)) :name nm})
                 (for [e unnamed] {:id (:id e) :iri (:iri e) :name lone})))))

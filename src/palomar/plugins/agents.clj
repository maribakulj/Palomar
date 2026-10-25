(ns palomar.plugins.agents
  "Role-aware reading of the canonical agents (`:canon/agent` qualified by
   `:canon/role`, see `palomar.plugins.canonical`).

   A spoke whose source states the responsibility (`dc:publisher`, MARC 700 vs
   100) puts each agent on a fragment carrying a `:canon/role` coord; a spoke
   whose source does not leaves a plain literal with no role. Consumers read
   both shapes through this namespace, so the choice of *which* agent counts as
   a creator lives in one place."
  (:require [palomar.plugins.qualified :as qualified]))

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

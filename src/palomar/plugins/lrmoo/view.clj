(ns palomar.plugins.lrmoo.view
  "Typed LRMoo traversal — the derived view's read lens (strategy C, ADR 0013).

   Reads a record's `:entities` and its reference-valued `:lrmoo/*` assertions
   as a WEMI graph and navigates it. This is the *view*, not a store: pure
   reads over the assertion substrate (ADR 0001), never a mutation. Navigators
   return entity *ids* (compose by chaining; resolve with `entity-by-id`).

   Link directions follow LRMoo v1.0 exactly (see `lrmoo/wemi-links`):
     F1_Work          --R3_is_realised_in--> F2_Expression
     F3_Manifestation --R4_embodies-------->  F2_Expression
     F5_Item          --R7_exemplifies----->  F3_Manifestation
   so going *down* the chain (Work→…→Item) is forward on R3 but inverse on
   R4/R7, and going *up* is the reverse. The navigators below bake that in."
  (:require [palomar.model :as model]
            [palomar.plugins.lrmoo :as lrmoo]))

;; ---------------------------------------------------------------------------
;; Entities by kind
;; ---------------------------------------------------------------------------

(defn entities-of
  "Entities in `record` whose `:kind` is `kind`."
  [record kind]
  (filterv #(= kind (:kind %)) (:entities record)))

(defn works          [record] (entities-of record :lrmoo/F1_Work))
(defn expressions    [record] (entities-of record :lrmoo/F2_Expression))
(defn manifestations [record] (entities-of record :lrmoo/F3_Manifestation))
(defn items          [record] (entities-of record :lrmoo/F5_Item))

(defn entity-by-id
  "The entity in `record` with id `id`, or nil."
  [record id]
  (first (filter #(= id (:id %)) (:entities record))))

;; ---------------------------------------------------------------------------
;; Reference traversal primitives
;; ---------------------------------------------------------------------------

(defn refs-out
  "Target ids of reference-valued assertions in `record` with subject `id`
   and predicate `prop` — forward along `prop`."
  [record id prop]
  (into []
        (comp (filter (fn [a] (and (= id (:subject a))
                                   (= prop (:predicate a))
                                   (model/reference-value? (:value a)))))
              (map (comp :value/target :value)))
        (:assertions record)))

(defn refs-in
  "Subject ids of reference-valued assertions in `record` with predicate
   `prop` whose value targets `id` — inverse of `prop`."
  [record id prop]
  (into []
        (comp (filter (fn [a] (and (= prop (:predicate a))
                                   (model/reference-value? (:value a))
                                   (= id (:value/target (:value a))))))
              (map :subject))
        (:assertions record)))

;; ---------------------------------------------------------------------------
;; WEMI navigators (directions per LRMoo v1.0)
;; ---------------------------------------------------------------------------

;; down the chain: Work → Expression → Manifestation → Item
(defn expressions-of    [record work-id]  (refs-out record work-id  :lrmoo/R3_is_realised_in))
(defn manifestations-of [record expr-id]  (refs-in  record expr-id  :lrmoo/R4_embodies))
(defn items-of          [record manif-id] (refs-in  record manif-id :lrmoo/R7_exemplifies))

;; up the chain: Item → Manifestation → Expression → Work
(defn work-of           [record expr-id]  (refs-in  record expr-id  :lrmoo/R3_is_realised_in))
(defn expression-of     [record manif-id] (refs-out record manif-id :lrmoo/R4_embodies))
(defn manifestation-of  [record item-id]  (refs-out record item-id  :lrmoo/R7_exemplifies))

(defn lrmoo-entity?
  "True if `id` names an entity of an LRMoo WEMI kind in `record`."
  [record id]
  (boolean (some-> (entity-by-id record id) :kind lrmoo/entity-kind?)))

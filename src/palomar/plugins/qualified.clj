(ns palomar.plugins.qualified
  "Reading a canonical value together with one of its qualifier coords.

   A qualified value lives on a fragment (ADR 0011): the record references the
   fragment, the fragment carries the literal under the same predicate and the
   coord (`:canon/role`, `:canon/note-kind`, …). A flat value has no coord. This
   reads both shapes, in assertion order (the mapping rules' order, then the
   source order within a rule), and skips out-of-force assertions (ADR 0023)."
  (:require [palomar.model :as model]))

(defn occurrences
  "Every in-force `pred` literal of `record`, in assertion order, as a map with
   the literal under `:value` and, for each coord in `coords`, its value on the
   literal's subject under the coord's own key (absent when there is none: a
   flat literal, or a fragment without that coord)."
  [record pred coords]
  (let [live  (remove model/out-of-force? (:assertions record))
        want  (set coords)
        by-s  (reduce (fn [m a]
                        (if (and (want (:predicate a)) (not (contains? (get m (:subject a)) (:predicate a))))
                          (assoc-in m [(:subject a) (:predicate a)] (:value a))
                          m))
                      {} live)]
    (for [a live
          :when (and (= pred (:predicate a)) (string? (:value a)))]
      (assoc (get by-s (:subject a)) :value (:value a)))))

(defn values
  "`occurrences` of `pred` with the one `coord`, as `{:value v :coord c}`."
  [record pred coord]
  (for [o (occurrences record pred [coord])]
    {:value (:value o) :coord (get o coord)}))

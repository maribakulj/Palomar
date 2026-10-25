(ns palomar.plugins.qualified
  "Reading a canonical value together with one of its qualifier coords.

   A qualified value lives on a fragment (ADR 0011): the record references the
   fragment, the fragment carries the literal under the same predicate and the
   coord (`:canon/role`, `:canon/note-kind`, …). A flat value has no coord. This
   reads both shapes, in assertion order (the mapping rules' order, then the
   source order within a rule), and skips out-of-force assertions (ADR 0023)."
  (:require [palomar.model :as model]))

(defn values
  "Every in-force `pred` literal of `record`, in assertion order, as
   `{:value v :coord c}` where `c` is the value of `coord` on the literal's
   subject (nil when there is none: a flat literal, or a fragment without that
   coord)."
  [record pred coord]
  (let [live   (remove model/out-of-force? (:assertions record))
        coords (into {} (for [a live
                              :when (= coord (:predicate a))]
                          [(:subject a) (:value a)]))]
    (for [a live
          :when (and (= pred (:predicate a)) (string? (:value a)))]
      {:value (:value a) :coord (coords (:subject a))})))

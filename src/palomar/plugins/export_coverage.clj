(ns palomar.plugins.export-coverage
  "What an exporter does not write, reported rather than left silent (audit S11).

   Each floor exporter (DC, MARC21, MODS, IIIF) writes a known set of predicates:
   some canonical fields and qualifier coords, and, for a same-format round
   trip, its own native fields. A record can carry far more — another spoke's
   native fields (INTERMARC → DC), the WEMI view's strings, a canonical field the
   target has no element for. `unexpressed` turns each such predicate into one
   export-edge `:dropped` loss, so the loss report names it and the fidelity
   audit (`palomar.fidelity`) finds every absent value accounted for."
  (:require [palomar.diagnostics :as dx]
            [palomar.model :as model]))

(defn unexpressed
  "One `:dropped` export loss per distinct predicate of `record` with a literal
   (non-reference) value that `expressed?` (a set or predicate fn of the
   predicate) rejects. `target` names the format in the message."
  [record expressed? target]
  (for [p (->> (:assertions record)
               (remove #(model/reference-value? (:value %)))
               (map :predicate)
               distinct
               (remove #(= :canon/loss-marker %))
               (remove expressed?))]
    (dx/loss {:category     :dropped
              :subject      (:id record)
              :edge         :export
              :source-field p
              :message      (str (pr-str p) " is not written by the " target " export")})))

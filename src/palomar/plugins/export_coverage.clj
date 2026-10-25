(ns palomar.plugins.export-coverage
  "What an exporter does not write, reported rather than left silent (audit S11).

   Each floor exporter (DC, MARC21, MODS, IIIF) writes a known set of canonical
   fields and qualifier coords. A record can carry more: a canonical field the
   target has no element for, a coord the target cannot say, another spoke's
   native fields (INTERMARC → DC). `unexpressed` turns each such predicate into
   one export-edge `:dropped` loss, so the loss report names it and the fidelity
   audit (`palomar.fidelity`) finds every absent value accounted for.

   A native field is not lost when its value is written through the canonical
   field a spoke mapping lifts it to: `:dc/title` reaches MARC as 245 through
   `:canon/title`. `native-targets` indexes every spoke mapping for that, so a
   native is reported only when none of its canonical targets is written.

   A coord is reported only when the field it qualifies is written and the coord
   is not (a MARC title loses its `:canon/lang`); when the field itself is not
   written, the field's own loss covers its coords.

   Assertions an inference or repair pass derived (the WEMI view's strings) are
   not source content and are not reported here: the projection accounts for them."
  (:require [palomar.diagnostics :as dx]
            [palomar.model :as model]
            [palomar.spokes :as spokes]))

(def native-targets
  "Native predicate → the set of canonical predicates spoke mappings lift it to:
   the mapping's `:mapping/to`, and for a `{:from :as}` qualifier its `:as`."
  (reduce (fn [idx {:mapping/keys [from to qualifier]}]
            (cond-> (reduce #(update %1 %2 (fnil conj #{}) to)
                            idx (if (vector? from) from [from]))
              (:from qualifier) (update (:from qualifier) (fnil conj #{}) (:as qualifier))))
          {}
          (mapcat :mapping (vals spokes/plugins))))

(def combined-natives
  "Native predicates a spoke mapping joins with others into one canonical value
   (`:mapping/combine`, e.g. INTERMARC 100 $a + $m → \"Flaubert, Gustave\"):
   their value is written as a part of that value, never on its own."
  (into #{} (comp (map :mapping/from) (filter vector?) cat)
        (mapcat :mapping (vals spokes/plugins))))

(def coord-bases
  "Qualifier coord → the canonical fields it qualifies. A coord is covered by
   its field's loss when the field is not written."
  {:canon/lang      #{:canon/title :canon/uniform-title :canon/note}
   :canon/role      #{:canon/agent}
   :canon/note-kind #{:canon/note}
   :canon/width     #{:canon/digital-object}
   :canon/height    #{:canon/digital-object}})

(defn derived?
  "True when assertion `a` was derived by an inference or repair pass rather
   than read from the source or mapped from it."
  [a]
  (contains? #{:infer :repair} (get-in a [:provenance :pass])))

(defn- expressed? [written own-ns p]
  (cond
    (contains? written p)            true
    (= own-ns (namespace p))         true
    (contains? coord-bases p)        (not-any? written (coord-bases p))
    (contains? native-targets p)     (boolean (some written (native-targets p)))
    :else                            false))

(defn unexpressed
  "One `:dropped` export loss per distinct predicate of `record` with a literal
   (non-reference), non-derived value the export does not write. `written` is the
   set of canonical fields and coords the export writes; `own-ns` (optional)
   names the target's own native namespace, whose fields the exporter accounts
   for itself. `target` names the format in the message."
  ([record written target] (unexpressed record written nil target))
  ([record written own-ns target]
   (for [p (->> (:assertions record)
                (remove #(model/reference-value? (:value %)))
                (remove derived?)
                (map :predicate)
                distinct
                (remove #(= :canon/loss-marker %))
                (remove #(expressed? written own-ns %)))]
     (dx/loss {:category     :dropped
               :subject      (:id record)
               :edge         :export
               :source-field p
               :message      (str (pr-str p) " is not written by the " target " export")}))))

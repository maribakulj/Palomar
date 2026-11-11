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
  (:require [clojure.string :as str]
            [palomar.diagnostics :as dx]
            [palomar.mentions :as mentions]
            [palomar.model :as model]
            [palomar.plugins.qualified :as qualified]
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
  {:canon/lang          #{:canon/title :canon/uniform-title :canon/note :canon/agent :canon/rights}
   :canon/role          #{:canon/agent}
   :canon/agent-id      #{:canon/agent}
   :canon/note-kind     #{:canon/note}
   :canon/note-label    #{:canon/note}
   :canon/note-label-lang #{:canon/note}
   :canon/width         #{:canon/digital-object}
   :canon/height        #{:canon/digital-object}
   :canon/image-rights  #{:canon/digital-object}
   :canon/canvas-rights #{:canon/digital-object}
   :canon/rights-scope  #{:canon/rights}})

(def structured-derived
  "Fields of a structured native that its importer computed rather than read
   from the source: a rank, an event's kind, an actor's responsibility, the
   element a name was read in. They are bookkeeping, not values, so the
   fidelity audit does not look for them in the output; every other string
   field of a structured value it does (post-audit 4, F5)."
  {:ead/creator   #{:element}
   :eac/relation  #{:element}
   :lido/event    #{:index :kind}
   :lido/actor    #{:event :responsibility}
   :lido/material #{:event :role}
   :lido/subject-person #{}})

(def joined-fields
  "Fields of a structured native its importer joined from several source
   values with \"; \" (an actor's roles): each is a source value of its own,
   which an exporter may write apart (one MARC `$e` per role)."
  {:lido/actor #{:role}})

(defn structured-values
  "The source values a structured native `a` holds, as `[field value]`: each
   string field of its value but those `structured-derived` names, a joined
   field once per value it joins."
  [{:keys [predicate value]}]
  (when (model/structured-value? value)
    (for [[k v] (:value/fields value)
          :when (and (string? v) (not (str/blank? v)) (not (contains? (get structured-derived predicate) k)))
          v'    (if (contains? (get joined-fields predicate) k) (str/split v #"; ") [v])]
      [k v'])))

(defn unwritten-fields
  "The losses of an exporter that writes only the fields `written`
   (`{predicate #{field…}}`, or a function of a value's fields to the set it
   writes of them) of the structured natives of `record`: one
   `:under-specified` loss per predicate whose in-force values hold another
   source field (a type, a role, life dates…), naming them, and for those
   fields only (post-audit 4, F5)."
  [record written edge-note]
  (for [[p fields] written
        :let [extra (->> (:assertions record)
                         (filter #(and (= p (:predicate %)) (model/structured-value? (:value %)) (model/in-force? %)))
                         (mapcat #(let [fs (:value/fields (:value %))
                                        w  (if (set? fields) fields (fields fs))]
                                    (for [[k v] fs
                                          :when (and (string? v) (not (str/blank? v)) (not (contains? (get structured-derived p) k))
                                                     (not (contains? w k)))]
                                      k)))
                         distinct sort)]
        :when (seq extra)]
    (dx/loss {:category :under-specified :subject (:id record) :edge :export :source-field p :fields extra
              :message (str (pr-str p) ": " (str/join ", " (map name extra)) " not written " edge-note)})))

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

(defn provider-losses
  "One `:dropped` export loss per provider of a digital surrogate (`:canon/role
   :provider`): DC, MODS and MARC21 have no agent element that does not state a
   role in the work, so it is not written."
  [record target]
  (for [nm (distinct (keep #(when (= :provider (:coord %)) (:value %)) (qualified/values record :canon/agent :canon/role)))]
    (dx/loss {:category :dropped :subject (:id record) :edge :export :source-field :canon/agent :value nm
              :message (str "the provider " (pr-str nm) " of the digital surrogate has no " target
                            " element that does not state a role in the work; not written")})))

(defn occurrence-losses
  "One `:ambiguity-collapsed` export loss per name and role the source gives
   more often than the canonical floor holds it (lot O3 review): two
   identical `dc:creator` \"Martin, Jean\" are two occurrences (two local
   agents, two Linked Art actors) but one floor value, so a floor export
   (DC, MODS, MARC21) writes one name. A name the floor does not carry at
   all is another loss's (`unexpressed`)."
  [record target]
  (let [floor (frequencies (for [{:keys [value coord]} (qualified/values record :canon/agent :canon/role)] [value coord]))
        occs  (frequencies (for [m (mentions/mentions record) :when (= :agent (:type m))] [(:name m) (:responsibility m)]))]
    (for [[[nm role :as k] n] (sort-by (comp pr-str key) occs)
          :let [f (get floor k 0)]
          :when (and (pos? f) (< f n))]
      (dx/loss {:category :ambiguity-collapsed :subject (:id record) :edge :export :source-field :canon/agent
                :message (str n " occurrences of " (pr-str nm) (when role (str " (" (name role) ")"))
                              " are " f " value of the canonical floor: " target " writes the name " f " time(s)")}))))

(defn scope-losses
  "One `:under-specified` loss per rights statement scoped to the digital
   surrogate written where the scope cannot be said."
  [record target]
  (for [o (qualified/occurrences record :canon/rights [:canon/rights-scope]) :when (:canon/rights-scope o)]
    (dx/loss {:category :under-specified :subject (:id record) :edge :export :source-field :canon/rights-scope
              :value (:value o) :aspect :qualifier
              :message (str "rights " (pr-str (:value o)) " hold for the digital surrogate only; " target " writes them as the resource's")})))

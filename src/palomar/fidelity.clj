(ns palomar.fidelity
  "Occurrence-level fidelity audit of a conversion (audit S11/S12, test 15).

   The loss report (`palomar.loss-report`) aggregates what each component
   *declares* it lost. That is not evidence of preservation: an exporter that
   silently leaves out one occurrence of a predicate it otherwise handles
   declares nothing, and the report stays clean. This namespace *observes* the
   output instead. It renders each record, decodes the document with a parser for
   its format, and classifies every string-valued assertion of the record:

   - `:present`     — the value appears in the decoded output;
   - `:reported`    — absent, but a loss diagnostic names its predicate for that
                      subject or for the record;
   - `:unaccounted` — absent and not reported: a silent loss.

   A conversion may be called lossless for a target only when nothing is
   `:unaccounted`. Values are compared whitespace-insensitively (XML text is
   trimmed by the mappings). The check is by value: a value that happens to occur elsewhere
   in the output counts as present, so this can miss a loss (false negative) but
   never invents one. Turtle has no decoder here and is reported as unchecked.

   Strings are not all a graph says. For the N-Triples family (`relation-targets`)
   the audit also checks every relation (ADR 0033): each reference assertion the
   RDF view expresses must survive as the triple `iri(subject) —p→ iri(object)`,
   `p` its predicate or the CIDOC-CRM super-property a pure-CRM export writes in
   its place. A Work the export leaves unlinked from its Expression keeps every
   title, so no string check sees it; this one does. The relations are reported
   apart, under `:relations`, with the same three classes."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [palomar.convert :as convert]
            [palomar.diagnostics :as dx]
            [palomar.model :as model]
            [palomar.plugins.export-coverage :as cov]
            [palomar.plugins.lrmoo.crm :as crm]
            [palomar.plugins.lrmoo.export :as export]
            [palomar.plugins.lrmoo.ntriples :as nt]
            [palomar.xml :as rx]))

;; --- decoders: every string value a document carries -------------------------

(defn- nt-strings [doc]
  (into #{} (mapcat (fn [[s _ o]] (keep #(or (:lit %) (:iri %)) [s o]))) (nt/parse doc)))

(defn- nq-strings [doc]
  (into #{} (mapcat (fn [[s _ o]] (keep #(or (:lit %) (:iri %)) [s o]))) (nt/parse-quads doc)))

(defn- json-values [x]
  (cond
    (string? x)     [x]
    (map? x)        (concat (keys x) (mapcat json-values (vals x)))   ; a language map's keys are content
    (sequential? x) (mapcat json-values x)
    (number? x)     [(str x)]
    :else           []))

(defn- json-strings [doc]
  (with-open [r (java.io.PushbackReader. (java.io.StringReader. doc) 64)]
    (loop [acc #{}]
      (let [v (json/read r :eof-error? false :eof-value ::eof)]
        (if (= ::eof v) acc (recur (into acc (json-values v))))))))

(defn- xml-node-strings [node]
  (when (map? node)
    (concat (vals (:attrs node))
            (let [t (str/join (filter string? (:content node)))]
              (when-not (str/blank? t) [t (str/trim t)]))
            (mapcat xml-node-strings (:content node)))))

(defn- xml-strings [doc]
  (let [body (str/replace doc #"<\?xml[^>]*\?>" "")]
    (set (xml-node-strings (rx/parse-str (str "<fidelity-wrap>" body "</fidelity-wrap>"))))))

(def ^:private decoders
  {:ntriples   nt-strings
   :crm        nt-strings
   :crm-only   nt-strings
   :jsonld     json-strings
   :linked-art json-strings
   :iiif       json-strings
   :dc         xml-strings
   :marc21     xml-strings
   :marc21-archival xml-strings
   :marc21-fonds    xml-strings
   :marc21-authority xml-strings
   :marc21-object   xml-strings
   :mods       xml-strings
   :prov       nt-strings
   :nquads     nq-strings})

(defn decodable-targets
  "The targets whose output this audit can decode."
  []
  (set (keys decoders)))

;; --- the audit ---------------------------------------------------------------

(defn- norm
  "Whitespace-insensitive form of a value: XML text is trimmed and its runs of
   whitespace are not significant to the spokes' mappings."
  [s]
  (str/replace (str/trim (str s)) #"\s+" " "))

(defn- reported?
  "True when a loss in `losses` names `predicate` for `subject` or for the record
   — or, for a qualifier coord, names a field it qualifies (a dropped title takes
   its language with it, `export-coverage/coord-bases`). A part of a
   structured value (`field` of `whole`) is reported only by a loss that names
   that field or that value, or names neither (post-audit 4, F5 review): one
   field lost does not account for the others; a loss naming one string
   value accounts for that value only (post-audit 5, G1)."
  [losses rid {:keys [subject predicate value field whole]}]
  (let [fields (into #{predicate} (cov/coord-bases predicate))]
    (some #(and (contains? fields (dx/loss-source-field %))
                (or (= subject (:subject %)) (= rid (:subject %)))
                (let [{fs :loss/fields v :loss/value} (:detail %)]
                  (and (or (nil? field) (nil? fs) (contains? fs field))
                       ;; a loss about one value (a string, or a structured
                       ;; value's fields) accounts for that value only
                       (or (nil? v) (= v (if field whole value))))))
          losses)))

(defn- present?
  "True when `value` of `predicate` is among the decoded strings `seen`. A
   combined native is written only as a part of the joined value, so for one any
   decoded string containing it counts (`export-coverage/combined-natives`)."
  [seen predicate value]
  (let [v (norm value)]
    (or (contains? seen v)
        (and (contains? cov/combined-natives predicate)
             (some #(str/includes? % v) seen)))))

;; --- relations ----------------------------------------------------------------

(def ^:private relation-parsers
  "The targets whose relations this audit checks, with the parser of their
   output into `[s p o …]` terms."
  {:ntriples nt/parse :crm nt/parse :crm-only nt/parse :nquads nt/parse-quads})

(defn relation-targets
  "The targets whose relations (reference assertions) this audit checks."
  []
  (set (keys relation-parsers)))

(defn- relation-triples
  "The `[s p o]` IRI triples of an RDF `doc` of target `to` (literal objects
   left out)."
  [to doc]
  (into #{} (keep (fn [[s p o]] (when (and (:iri s) (:iri o)) [(:iri s) (:iri p) (:iri o)])))
        ((relation-parsers to) doc)))

(defn- predicate-iris
  "The IRIs that may carry predicate `p` in the output: its own, and the
   CIDOC-CRM super-property the pure-CRM export writes in its place."
  [p]
  (cond-> #{(export/term-iri p)}
    (crm/replaced-properties p) (conj (str export/crm-base (crm/replaced-properties p)))))

(defn- relations
  "The relations of `record` the RDF view expresses, as `{:subject :predicate
   :target}`: its reference assertions on an LRMoo, CRM or SKOS predicate, under
   the export's status policy. Unlike string values, derived ones are checked:
   a WEMI link an inference pass made is exactly what the export must carry."
  [to record]
  (distinct
   (for [a (:assertions record)
         :when (and (model/reference-value? (:value a))
                    (export/viewable-predicate? (:predicate a))
                    (or (convert/keeps-history? to) (not (model/out-of-force? a))))]
     {:subject (:subject a) :predicate (:predicate a) :target (:value/target (:value a))})))

(defn- relation-audit
  "Classify the relations of `record` against the decoded `doc` (see the ns doc)."
  [to record doc losses]
  (let [idx     (into {} (keep (fn [e] (when-let [i (:iri e)] [(:id e) i]))) (:entities record))
        iri     #(or (get idx %) (export/entity-iri %))
        seen    (if (str/blank? doc) #{} (relation-triples to doc))
        rels    (relations to record)
        present (fn [{:keys [subject predicate target]}]
                  (some #(contains? seen [(iri subject) % (iri target)]) (predicate-iris predicate)))
        klass   (group-by #(cond (present %)                    :present
                                 (reported? losses (:id record) %) :reported
                                 :else                          :unaccounted)
                          rels)]
    {:checked     (count rels)
     :present     (count (:present klass))
     :reported    (count (:reported klass))
     :unaccounted (vec (:unaccounted klass))}))

(defn- literals-by-subject
  "For an RDF `doc` of target `to`: subject IRI → the normalised literals it
   carries, directly or through an appellation it is identified by (`P1` →
   `P190`, ADR 0022), so a value is found on its own node, not anywhere."
  [to doc]
  (let [ts      ((relation-parsers to) doc)
        p1      (str export/crm-base "P1_is_identified_by")
        direct  (reduce (fn [m [s _ o]] (if (and (:iri s) (:lit o)) (update m (:iri s) (fnil conj #{}) (norm (:lit o))) m))
                        {} ts)
        appells (reduce (fn [m [s p o]] (if (and (= p1 (:iri p)) (:iri o)) (update m (:iri s) (fnil conj #{}) (:iri o)) m))
                        {} ts)]
    (into {} (for [s (into (set (keys direct)) (keys appells))]
               [s (into (get direct s #{}) (mapcat direct (get appells s)))]))))

(defn audit-record
  "Audit one WEMI `record` exported to `to`, against the loss diagnostics it
   carries, the export's own losses and the extra `losses` (e.g. the import edge).
   Returns `{:target :checked :present :reported :unaccounted [{:subject
   :predicate :value} …]}` over the distinct `{:subject :predicate :value}`
   facts (so present + reported + unaccounted = checked), or `{:target
   :unchecked true}` when `to` has no decoder. For a `relation-targets` target
   it also carries `:relations`, the same account of the record's relations.

   Only the export's losses and the extra `losses` can account for an absent
   value: a projection diagnostic on the record (a Work key built from a
   role-less agent, …) says nothing about what the export wrote. Out-of-force
   assertions are not checked, as the export never emits them by design
   (`convert/export-records`); nor are those an inference or repair pass derived
   (`export-coverage/derived?`), which copy source values checked in their own
   right. A history target (`convert/keeps-history?`, PROV-O) is checked on
   its out-of-force assertions too, since it must record them (ADR 0025). An
   RDF target of `relation-targets` is checked node by node: a value is present
   only on the node of its own subject (or that node's appellation), and the
   strings the RDF view derives are checked as well.
   The audit observes the export with its proposals (`:with-proposals? true`):
   it judges the exporter, and withholding them is the export's policy."
  ([to record] (audit-record to record []))
  ([to record losses]
   (if-let [decode (decoders to)]
     ;; the audit judges the exporter, so it observes the fullest stating export,
     ;; proposals included: withholding them by default (ADR 0042) is policy,
     ;; counted in the report, not a loss of the exporter's
     (let [{:keys [output loss]} (convert/export-records to [record] {:with-proposals? true})
           rdf?    (contains? relation-parsers to)
           seen    (if (str/blank? output) #{} (into #{} (map norm) (decode output)))
           ;; an RDF output is checked node by node: a value counts as present
           ;; only on its own subject (post-audit: the right text on the wrong
           ;; resource is a loss); and the strings the view derives (a WEMI
           ;; title) are checked too, since an exporter can misplace them
           idx     (into {} (keep (fn [e] (when-let [i (:iri e)] [(:id e) i]))) (:entities record))
           on      (when (and rdf? (not (str/blank? output))) (literals-by-subject to output))
           here?   (fn [{:keys [subject value]}]
                     (contains? (get on (or (get idx subject) (export/entity-iri subject)) #{}) (norm value)))
           all     (concat loss losses)
           ;; a structured native's source values are checked as its predicate's
           ;; values, as a string native's are (post-audit 4, F5)
           checked (distinct
                    (for [a (:assertions record)
                          :when (and (or (convert/keeps-history? to) (not (model/out-of-force? a)))
                                     (or (not (cov/derived? a))
                                         (and rdf? (export/viewable-predicate? (:predicate a)))))
                          [k v] (if (string? (:value a)) [[nil (:value a)]] (cov/structured-values a))]
                      (cond-> {:subject (:subject a) :predicate (:predicate a) :value v}
                        k (assoc :part? true :field k :whole (:value/fields (:value a))))))
           ;; a part of a structured value may be written inside a larger
           ;; string (life dates in \"1634-1693\", the value printed whole in
           ;; PROV-O), so it is present when a decoded string holds it as a
           ;; word of its own: \"1634\" in \"1634-1693\", not in \"16345\"; a
           ;; number padded or not (\"850\" in \"0850-12-31\", F7 review)
           part-in (fn [{:keys [value]}]
                     (let [v  (norm value)
                           re (re-pattern (str "(?<![\\p{L}\\p{N}])" (when (re-matches #"\d+" v) "0*")
                                               (java.util.regex.Pattern/quote v) "(?![\\p{L}\\p{N}])"))]
                       (some #(re-find re %) seen)))
           klass   (group-by #(cond (if (:part? %) (part-in %) (if rdf? (here? %) (present? seen (:predicate %) (:value %)))) :present
                                    (reported? all (:id record) %)                               :reported
                                    :else                                                        :unaccounted)
                             checked)]
       (cond-> {:target      to
                :checked     (count checked)
                :present     (count (:present klass))
                :reported    (count (:reported klass))
                :unaccounted (mapv #(dissoc % :part? :whole) (:unaccounted klass))}
         (relation-parsers to) (assoc :relations (relation-audit to record output all))))
     {:target to :unchecked true})))

(defn audit
  "Audit converting `source` from spoke `from` to `to` (as `convert/convert`
   would), record by record. Returns `{:target :records :checked :present
   :reported :unaccounted [...]}`, or `{:target :unchecked true}`."
  [{:keys [from to source opts] :or {opts {}}}]
  (if-not (decoders to)
    {:target to :unchecked true}
    (let [{:keys [records ingest]} (convert/to-wemi from opts source)
          per (map #(audit-record to % ingest) records)]
      (cond-> {:target      to
               :records     (count records)
               :checked     (reduce + (map :checked per))
               :present     (reduce + (map :present per))
               :reported    (reduce + (map :reported per))
               :unaccounted (vec (mapcat :unaccounted per))}
        (relation-parsers to)
        (assoc :relations {:checked     (reduce + (map (comp :checked :relations) per))
                           :present     (reduce + (map (comp :present :relations) per))
                           :reported    (reduce + (map (comp :reported :relations) per))
                           :unaccounted (vec (mapcat (comp :unaccounted :relations) per))})))))

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
   never invents one. Turtle has no decoder here and is reported as unchecked."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [palomar.convert :as convert]
            [palomar.diagnostics :as dx]
            [palomar.model :as model]
            [palomar.plugins.lrmoo.ntriples :as nt]
            [palomar.xml :as rx]))

;; --- decoders: every string value a document carries -------------------------

(defn- nt-strings [doc]
  (into #{} (mapcat (fn [[s _ o]] (keep #(or (:lit %) (:iri %)) [s o]))) (nt/parse doc)))

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
   :mods       xml-strings})

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

(defn- reported? [losses rid {:keys [subject predicate]}]
  (some #(and (= predicate (dx/loss-source-field %))
              (or (= subject (:subject %)) (= rid (:subject %))))
        losses))

(defn audit-record
  "Audit one WEMI `record` exported to `to`, against the loss diagnostics it
   carries, the export's own losses and the extra `losses` (e.g. the import edge).
   Returns `{:target :checked :present :reported :unaccounted [{:subject
   :predicate :value} …]}`, or `{:target :unchecked true}` when `to` has no
   decoder. Out-of-force assertions are not checked: the export never emits them
   by design (`convert/export-records`)."
  ([to record] (audit-record to record []))
  ([to record losses]
   (if-let [decode (decoders to)]
     (let [{:keys [output loss]} (convert/export-records to [record])
           seen    (if (str/blank? output) #{} (into #{} (map norm) (decode output)))
           all     (concat (dx/collect record) loss losses)
           checked (for [a (:assertions record)
                         :when (and (string? (:value a)) (not (model/out-of-force? a)))]
                     (select-keys a [:subject :predicate :value]))
           klass   (group-by #(cond (contains? seen (norm (:value %)))       :present
                                    (reported? all (:id record) %)           :reported
                                    :else                                    :unaccounted)
                             checked)]
       {:target      to
        :checked     (count checked)
        :present     (count (:present klass))
        :reported    (count (:reported klass))
        :unaccounted (vec (distinct (:unaccounted klass)))})
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
      {:target      to
       :records     (count records)
       :checked     (reduce + (map :checked per))
       :present     (reduce + (map :present per))
       :reported    (reduce + (map :reported per))
       :unaccounted (vec (mapcat :unaccounted per))})))

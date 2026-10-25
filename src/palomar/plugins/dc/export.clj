(ns palomar.plugins.dc.export
  "Canonical → Dublin Core (DCMES 1.1) XML exporter (WP-4) — the reverse of
   `palomar.plugins.dc`, closing the DC round-trip that ADR 0015 marked as future.

   With the importer this is the first spoke that goes *source → pivot → same
   source*, so the loss model can finally be exercised as a real round-trip: a DC
   record imported to the canonical floor and exported back, with the report
   accounting exactly what did not survive the trip.

   Agents keep their DC element: the importer qualifies each `:canon/agent` with
   its `:canon/role` (creator / contributor / publisher), and export emits the
   matching element back. Two losses remain, surfaced at the export edge (ADR 0015):
   - an agent with no role (from a spoke whose source did not state one) is
     emitted as `dc:creator`, a guess reported as `:under-specified`;
   - `dc:relation` and `dc:source` both import to `:canon/relation`, so export
     cannot tell them apart and emits `dc:relation`.
   The six DCMES elements with no canonical home (subject, type, …) were already
   dropped at import; they simply do not reappear — the round-trip's hard loss.

   Output is built as a string (correct XML escaping, a stable `dc:` prefix, the
   predefined `xml:` prefix for `xml:lang`) rather than via `data.xml/emit`, for
   the same reason `palomar.plugins.lrmoo.export` renders N-Triples directly: full
   control over the surface form. The result re-imports through
   `palomar.plugins.dc` (whose `rewrite-tags` keys on the namespace URI, not the
   prefix). Like the LRMoo exporter, this is kept out of the plugin map's
   `:exporter` slot to avoid a require cycle — wiring is a later assembly step."
  (:require [clojure.string :as str]
            [palomar.diagnostics :as dx]
            [palomar.model :as model]
            [palomar.plugins.dc :as dc]
            [palomar.plugins.export-coverage :as cov]))

(def canon->dc
  "Ordered canonical→DC element map. Several DC elements collapsed onto one
   canonical predicate at import; export picks one DC element back and reports the
   collapse as loss (see ns doc). Ordered so the serialisation is deterministic."
  [[:canon/title      "title"]
   [:canon/agent      "creator"]        ; per :canon/role, see `role->dc`; no role -> creator (lossy)
   [:canon/date       "date"]
   [:canon/identifier "identifier"]
   [:canon/relation   "relation"]       ; source also -> relation (lossy)
   [:canon/note       "description"]])

(def ^:private role->dc
  "DC element for an agent with a stated `:canon/role`."
  {:creator "creator" :contributor "contributor" :publisher "publisher"})

(defn- occurrences
  "Record-level `{:value :lang :role}` occurrences of canonical predicate `p`,
   resolving a fragment reference (qualified value) to its literal plus its
   `:canon/lang` and `:canon/role` coords. Flat literals come back with nil coords."
  [record p]
  (let [rid (:id record)]
    (for [a (:assertions record)
          :when (and (= p (:predicate a)) (= rid (:subject a)))]
      (let [v (:value a)]
        (if (model/reference-value? v)
          (let [fid (:value/target v)
                fas (filter #(= fid (:subject %)) (:assertions record))]
            {:value (some #(when (and (= p (:predicate %)) (string? (:value %))) (:value %)) fas)
             :lang  (some #(when (= :canon/lang (:predicate %)) (:value %)) fas)
             :role  (some #(when (= :canon/role (:predicate %)) (:value %)) fas)})
          {:value v :lang nil :role nil})))))

(defn- xml-escape [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- attr-escape [s]
  (str/replace (xml-escape s) "\"" "&quot;"))

(defn- dc-line [local {:keys [value lang]}]
  (str "  <dc:" local
       (when lang (str " xml:lang=\"" (attr-escape lang) "\""))
       ">" (xml-escape value) "</dc:" local ">"))

(defn ->dc-xml
  "Render `record`'s canonical content as a Dublin Core XML `<metadata>` document,
   or `nil` if it carries no exportable canonical field."
  [record]
  (let [lines (for [[p local] canon->dc
                    occ        (occurrences record p)
                    :when      (some? (:value occ))]
                (dc-line (get role->dc (:role occ) local) occ))]
    (when (seq lines)
      (str "<metadata xmlns:dc=\"" dc/dc-uri "\">\n"
           (str/join "\n" lines)
           "\n</metadata>"))))

(def ^:private written
  "The canonical fields and coords the DC export writes."
  (into #{:canon/role :canon/lang} (map first) canon->dc))

(defn export-losses
  "Export-edge loss (ADR 0015) for the canonical→DC projection: agents whose role
   the floor does not know, and the relation/source collapse (one
   `:under-specified` per affected predicate), plus one `:dropped` per predicate
   the DC export does not write at all (`export-coverage/unexpressed`: e.g. a
   digital object, a uniform title, another spoke's native fields)."
  [record]
  (into (cond-> []
          (some #(nil? (:role %)) (occurrences record :canon/agent))
          (conj (dx/loss {:category     :under-specified
                          :subject      (:id record)
                          :edge         :export
                          :source-field :canon/agent
                          :message      "agent role not stated in the source — emitted as dc:creator"}))

          (seq (occurrences record :canon/relation))
          (conj (dx/loss {:category     :under-specified
                          :subject      (:id record)
                          :edge         :export
                          :source-field :canon/relation
                          :message      "relation/source distinction not recoverable — emitted as dc:relation"})))
        (cov/unexpressed record written "Dublin Core")))

(defn exporter
  "ADR 0007 exporter: render the canonical view of `records` as Dublin Core XML
   and report the canonical→DC under-specifications (ADR 0015).
   `(fn [opts records] -> {:output String :diagnostics [...]})`."
  [_opts records]
  {:output      (->> records (keep ->dc-xml) (str/join "\n"))
   :diagnostics (into [] (mapcat export-losses) records)})

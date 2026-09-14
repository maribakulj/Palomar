(ns palomar.plugins.lrmoo.export
  "RDF export of the LRMoo view (WP-2, ADR 0013). Renders a record's WEMI
   entities and their `:lrmoo/*` assertions as RDF — N-Triples for now (the
   canonical, line-based syntax). Turtle and JSON-LD are thin follow-ons over
   the same `triples` seq. Because local LRMoo names are the spec's own, class
   and predicate IRIs come straight from `lrmoo/iri`.

   `triples` yields the graph as data (`[s p o]`, `o` = `{:iri _}` | `{:lit _}`);
   `exporter` follows the ADR 0007 exporter contract. Three RDF serialisations
   render that one seq — N-Triples (canonical, line-based), Turtle (prefixed,
   grouped by subject) and compacted JSON-LD (web-native) — so the CRM
   down-projection and any future view reuse them by augmenting the seq first.
   The view exports the `:lrmoo/*` WEMI vocabulary **and** the `:crm/*` predicates
   the rich graph carries (typing CRM entities — agents `E21_Person`, subjects
   `E55_Type`/`E53_Place` — and their relations, e.g. `:crm/P129_is_about`), so the
   full entity graph reaches RDF, not just the WEMI chain; canonical/native
   predicates remain export loss until their own IRI mappings land. Wiring this into
   the LRMoo plugin map's `:exporter` is a later assembly step (kept out here to
   avoid a cycle with the vocabulary namespace)."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [palomar.diagnostics :as dx]
            [palomar.model :as model]
            [palomar.plugins.lrmoo :as lrmoo]))

(def rdf-type "http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
(def crm-base "http://www.cidoc-crm.org/cidoc-crm/")

(defn entity-iri
  "Fallback IRI for an entity id, used when the entity carries no authority
   `:iri`. A keyword (the minted `:ent/*` case) becomes a `urn:palomar:` IRI; a
   string id is assumed to be an IRI already and passes through."
  [id]
  (cond
    (keyword? id) (str "urn:palomar:" (namespace id) ":" (name id))
    (string? id)  id
    :else         (str "urn:palomar:" id)))

(defn- iri-index
  "Map entity id → its authority `:iri`, for the entities of `record` that carry
   one (e.g. a Manifestation's data.bnf ARK). Export prefers these over the
   synthetic `urn:palomar:` fallback, for both subject and reference-object IRIs."
  [record]
  (into {} (keep (fn [e] (when-let [i (:iri e)] [(:id e) i]))) (:entities record)))

(defn- crm-term?
  "True for a `:crm/*` keyword (a CIDOC-CRM class or property)."
  [x]
  (and (keyword? x) (= "crm" (namespace x))))

(defn- viewable-predicate?
  "Predicates the RDF view expresses: the LRMoo WEMI vocabulary *and* the CIDOC-CRM
   predicates the rich graph carries (e.g. `:crm/P129_is_about` for a subject). The
   floor (`:canon/*`) and native (`:intermarc/*`) predicates are still export loss."
  [p]
  (and (keyword? p) (contains? #{"lrmoo" "crm"} (namespace p))))

(defn- term-iri
  "IRI of an `:lrmoo/*` or `:crm/*` term (class or property)."
  [k]
  (if (crm-term? k) (str crm-base (name k)) (lrmoo/iri k)))

(defn- typed-kind?
  "True if entity kind `k` is one the RDF view types — an LRMoo WEMI class or any
   CIDOC-CRM class (so agents `E21_Person`, subjects `E55_Type`/`E53_Place`, … are
   typed, not just F1–F5)."
  [k]
  (or (lrmoo/entity-kind? k) (crm-term? k)))

(defn triples
  "The LRMoo view of `record` as RDF triples. Each WEMI entity is typed to its
   F-class; each `:lrmoo/*` assertion becomes an object-property triple (when its
   value is a reference) or a literal triple. Each item is `[s-iri p-iri o]` with
   `o` = `{:iri s}` | `{:lit v}`.

   With `{:certified-only? true}` (D7), only `:asserted` assertions are emitted —
   the proof-backed subgraph an institution can ship with no fabricated identity —
   and only the entities those certified claims reference are typed."
  ([record] (triples record {}))
  ([record {:keys [certified-only?]}]
   (let [idx     (iri-index record)
         iri     (fn [id] (or (get idx id) (entity-iri id)))
         as      (cond->> (:assertions record)
                   certified-only? (filterv model/asserted?))
         view-as (filterv #(viewable-predicate? (:predicate %)) as)
         refd    (when certified-only?
                   (into #{} (mapcat (fn [a]
                                       (cons (:subject a)
                                             (when (model/reference-value? (:value a))
                                               [(:value/target (:value a))])))
                                     view-as)))
         ;; an entity is certified if a determinate id (an authority :iri, e.g. an
         ;; ARK) fixes it, or a certified claim references it — so a titleless ARK
         ;; Manifestation is kept, but a string-key Work (no iri, only :proposed
         ;; claims) is not (audit R2).
         ents    (cond->> (:entities record)
                   certified-only? (filterv #(or (:iri %) (contains? refd (:id %)))))]
     (concat
      (for [e ents
            :when (typed-kind? (:kind e))]
        [(iri (:id e)) rdf-type {:iri (term-iri (:kind e))}])
      (for [a view-as]
        (let [v (:value a)]
          [(iri (:subject a))
           (term-iri (:predicate a))
           (if (model/reference-value? v)
             {:iri (iri (:value/target v))}
             {:lit v})]))))))

(defn- nt-escape [s]
  (-> (str s)
      (str/replace "\\" "\\\\")
      (str/replace "\"" "\\\"")
      (str/replace "\n" "\\n")
      (str/replace "\r" "\\r")
      (str/replace "\t" "\\t")))

(defn- nt-object [o]
  (if (:iri o)
    (str "<" (:iri o) ">")
    (str "\"" (nt-escape (:lit o)) "\"")))

(defn render-ntriples
  "Render a seq of `[s p o]` triples (o = `{:iri _}` | `{:lit _}`) as N-Triples,
   one `<s> <p> o .` per line. Reused by alternative projections (e.g. the CRM
   down-projection) that augment the triple seq before rendering."
  [triples]
  (str/join "\n"
            (for [[s p o] triples]
              (str "<" s "> <" p "> " (nt-object o) " ."))))

(defn ->ntriples
  "Render `record`'s LRMoo triples as N-Triples. Returns \"\" for a record with no
   LRMoo content. `opts` may carry `:certified-only?` (D7) to emit only the
   proof-backed (`:asserted`) subgraph."
  ([record] (->ntriples record {}))
  ([record opts] (render-ntriples (triples record opts))))

;; ---------------------------------------------------------------------------
;; Turtle and JSON-LD — the same triple seq, prefixed/grouped (Turtle) and
;; web-native (compacted JSON-LD). `rdf:type` renders as Turtle's `a` and as
;; JSON-LD's `@type`; IRIs under a known namespace compact to `prefix:local`.
;; ---------------------------------------------------------------------------

(def ^:private rdf-prefixes
  {"crm"   "http://www.cidoc-crm.org/cidoc-crm/"
   "lrmoo" "http://iflastandards.info/ns/lrm/lrmoo/"
   "rdf"   "http://www.w3.org/1999/02/22-rdf-syntax-ns#"})

(defn- compact-iri
  "`prefix:local` if `iri` sits under a known namespace, else nil."
  [iri]
  (some (fn [[pfx ns]] (when (str/starts-with? iri ns) (str pfx ":" (subs iri (count ns)))))
        rdf-prefixes))

(defn- compact-prefix
  "The prefix name of `iri` if it is compactable, else nil."
  [iri]
  (some (fn [[pfx ns]] (when (str/starts-with? iri ns) pfx)) rdf-prefixes))

(defn- ttl-iri [iri] (or (compact-iri iri) (str "<" iri ">")))
(defn- ttl-term [o] (if (:iri o) (ttl-iri (:iri o)) (str "\"" (nt-escape (:lit o)) "\"")))

(defn render-turtle
  "Render a `[s p o]` triple seq as Turtle: `@prefix` headers for the namespaces
   actually used, then statements grouped by subject — `a` for `rdf:type`, `;`
   between predicates and `,` between repeated objects. nil for an empty seq."
  [triples]
  (when (seq triples)
    (let [used     (into (sorted-set)
                         (comp (mapcat (fn [[_ p o]]
                                         [(when (not= p rdf-type) (compact-prefix p))
                                          (when (:iri o) (compact-prefix (:iri o)))]))
                               (remove nil?))
                         triples)
          header   (str/join "\n" (for [pfx used]
                                    (str "@prefix " pfx ": <" (rdf-prefixes pfx) "> .")))
          subjects (distinct (map first triples))
          by-subj  (group-by first triples)
          stmt     (fn [s]
                     (let [preds (distinct (map second (by-subj s)))
                           lines (for [p preds]
                                   (let [os (->> (by-subj s) (filter #(= p (second %))) (map #(nth % 2)))]
                                     (str "    " (if (= p rdf-type) "a" (ttl-iri p)) " "
                                          (str/join " , " (map ttl-term os)))))]
                       (str (ttl-iri s) "\n" (str/join " ;\n" lines) " .")))]
      (str header (when (seq used) "\n\n") (str/join "\n" (map stmt subjects))))))

(defn render-jsonld
  "Render a `[s p o]` triple seq as compacted JSON-LD: an `@context` of the
   namespace prefixes and an `@graph` of one node object per subject (`@type` for
   `rdf:type`, `{\"@id\" …}` for IRI objects). nil for an empty seq."
  [triples]
  (when (seq triples)
    (let [by-subj (group-by first triples)
          add     (fn [m k v] (update m k (fn [ex] (cond (nil? ex) v (vector? ex) (conj ex v) :else [ex v]))))
          term    (fn [o] (if (:iri o) {"@id" (or (compact-iri (:iri o)) (:iri o))} (:lit o)))
          node    (fn [s]
                    (reduce (fn [acc [_ p o]]
                              (if (= p rdf-type)
                                (add acc "@type" (or (compact-iri (:iri o)) (:iri o)))
                                (add acc (or (compact-iri p) p) (term o))))
                            {"@id" s}
                            (by-subj s)))]
      (json/write-str {"@context" rdf-prefixes
                       "@graph"   (mapv node (distinct (map first triples)))}))))

(defn ->turtle
  "Render `record`'s LRMoo triples as Turtle (\"\" when empty). `opts` may carry
   `:certified-only?` (D7)."
  ([record] (->turtle record {}))
  ([record opts] (or (render-turtle (triples record opts)) "")))

(defn ->jsonld
  "Render `record`'s LRMoo triples as compacted JSON-LD (\"\" when empty). `opts`
   may carry `:certified-only?` (D7)."
  ([record] (->jsonld record {}))
  ([record opts] (or (render-jsonld (triples record opts)) "")))

(defn export-losses
  "Export-edge loss (ADR 0015): this exporter serialises only the `:lrmoo/*`
   view, so every other predicate the record carries (native `:intermarc/*`,
   canonical, …) is *dropped* from the N-Triples target. One `:loss/dropped`
   diagnostic per distinct dropped predicate per subject, `:edge :export`."
  [record]
  (->> (:assertions record)
       (remove #(viewable-predicate? (:predicate %)))
       (map (juxt :subject :predicate))
       distinct
       (mapv (fn [[subject pred]]
               (dx/loss {:category     :dropped
                         :subject      subject
                         :edge         :export
                         :source-field pred
                         :message      (str (pr-str pred)
                                            " not expressible in the LRMoo RDF export")})))))

(defn exporter
  "ADR 0007 exporter: render the LRMoo view of `records` as one N-Triples
   document, and report what the target cannot express as export-edge loss
   (ADR 0015). `opts` may carry `:certified-only?` (D7) to ship only the
   proof-backed subgraph. `(fn [opts records] -> {:output String :diagnostics [...]})`."
  [opts records]
  {:output      (->> records
                     (into [] (comp (map #(->ntriples % opts)) (remove str/blank?)))
                     (str/join "\n"))
   :diagnostics (into [] (mapcat export-losses) records)})

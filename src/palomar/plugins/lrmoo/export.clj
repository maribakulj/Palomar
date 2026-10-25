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
            [palomar.plugins.lrmoo :as lrmoo]
            [palomar.plugins.lrmoo.ntriples :as nt]
            [palomar.plugins.qualified :as qualified]))

(def rdf-type "http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
(def crm-base "http://www.cidoc-crm.org/cidoc-crm/")

(defn- pct-encode
  "`s` with every character outside the IRI-safe set `[A-Za-z0-9._~!$&'()*+,;=@-]`
   percent-encoded from its UTF-8 bytes: a record id made from a file name
   (`:doc/my file`) still gives a valid IRI."
  [s]
  (apply str (for [ch (str s)]
               (if (re-matches #"[A-Za-z0-9._~!$&'()*+,;=@-]" (str ch))
                 ch
                 (apply str (map #(format "%%%02X" (bit-and % 0xff)) (.getBytes (str ch) "UTF-8")))))))

(defn entity-iri
  "Fallback IRI for an entity id, used when the entity carries no authority
   `:iri`. A keyword (the minted `:ent/*` case) becomes a `urn:palomar:` IRI,
   its namespace and name percent-encoded where they hold a character an IRI may
   not; a string id is assumed to be an IRI already and passes through (the
   writers refuse it if it is not one, `checked`)."
  [id]
  (cond
    (keyword? id) (str "urn:palomar:" (pct-encode (namespace id)) ":" (pct-encode (name id)))
    (string? id)  id
    :else         (str "urn:palomar:" (pct-encode id))))

(defn- check-iri! [iri]
  (when-let [problem (nt/iri-problem iri)]
    (throw (ex-info (str "refusing to write an invalid IRI <" iri ">: " problem)
                    {:iri iri :problem problem}))))

(defn checked
  "`triples` after checking every IRI they hold (subject, predicate, IRI object,
   datatype): an invalid one throws rather than corrupting the document."
  [triples]
  (doseq [[s p o] triples]
    (check-iri! s)
    (check-iri! p)
    (when (:iri o) (check-iri! (:iri o)))
    (when (:datatype o) (check-iri! (:datatype o))))
  triples)

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

(def skos-base "http://www.w3.org/2004/02/skos/core#")

(defn- viewable-predicate?
  "Predicates the RDF view expresses: the LRMoo WEMI vocabulary, the CIDOC-CRM
   predicates the rich graph carries (e.g. `:crm/P129_is_about` for a subject),
   and the SKOS mapping properties of an alignment (`:skos/exactMatch`, ADR
   0032). The floor (`:canon/*`) and native (`:intermarc/*`) predicates are
   still export loss."
  [p]
  (and (keyword? p) (contains? #{"lrmoo" "crm" "skos"} (namespace p))))

(defn- term-iri
  "IRI of an `:lrmoo/*`, `:crm/*` or `:skos/*` term (class or property)."
  [k]
  (cond (crm-term? k)               (str crm-base (name k))
        (= "skos" (namespace k))    (str skos-base (name k))
        :else                       (lrmoo/iri k)))

(defn- typed-kind?
  "True if entity kind `k` is one the RDF view types — an LRMoo WEMI class or any
   CIDOC-CRM class (so agents `E21_Person`, subjects `E55_Type`/`E53_Place`, … are
   typed, not just F1–F5)."
  [k]
  (or (lrmoo/entity-kind? k) (crm-term? k)))

(def appellation-class
  "IRI of `E41_Appellation` (CIDOC CRM), the class of the node an entity's
   string is written on."
  (str crm-base "E41_Appellation"))

(def identified-by
  "IRI of `P1_is_identified_by` (CIDOC CRM), linking an entity to its
   appellation (range E41)."
  (str crm-base "P1_is_identified_by"))

(def symbolic-content
  "IRI of `P190_has_symbolic_content` (CIDOC CRM), the string of an appellation
   (domain E90, of which E41 is a subclass)."
  (str crm-base "P190_has_symbolic_content"))

(def ^:private string-predicates
  "IR shorthands for an entity's string: an LRMoo entity's title and a CRM
   entity's label (a subject concept or place)."
  #{:lrmoo/R33_has_string :crm/P190_has_symbolic_content})

(defn appellation-iri
  "A deterministic IRI for the appellation carrying string `s` of the entity at
   `entity-iri`: the same title of the same entity always names the same node."
  [entity-iri s]
  (str "urn:palomar:appellation:" (model/sha256-hex (str entity-iri "|" s) 16)))

(defn- string-triples
  "The RDF for an entity's string `v` (`:lrmoo/R33_has_string` or
   `:crm/P190_has_symbolic_content` in the IR). Neither may sit on the entity:
   R33's domain is F12 Nomen and P190's E90 Symbolic Object, and a Work, an
   Expression or a subject concept is neither. So the string hangs on an
   appellation the entity is identified by: entity —P1_is_identified_by→
   E41_Appellation —P190_has_symbolic_content→ \"v\" (ADR 0022). P1's range is
   E41, which F12 Nomen is not a subclass of, so the node is an E41, not a Nomen."
  [s-iri v lang]
  (let [n (appellation-iri s-iri v)]
    [[s-iri identified-by {:iri n}]
     [n rdf-type {:iri appellation-class}]
     [n symbolic-content (cond-> {:lit v} lang (assoc :lang lang))]]))

(def ^:private lang-tag
  "A well-formed BCP 47 tag, by its syntax only (`fr`, `en-GB`, `zh-Hant-TW`):
   what N-Triples and Turtle accept after `@`."
  #"[a-zA-Z]{1,8}(-[a-zA-Z0-9]{1,8})*")

(defn language-tag
  "`l` as an RDF language tag (`_` read as `-`, as in `en_GB`), or nil when it is
   not one: a value that would make the literal unparseable is not written."
  [l]
  (when (string? l)
    (let [t (str/replace (str/trim l) "_" "-")]
      (when (re-matches lang-tag t) t))))

(defn- title-langs
  "String → language tag for the record's language-qualified canonical titles: a
   `:canon/title` or `:canon/uniform-title` fragment carrying a well-formed
   `:canon/lang` (`language-tag`). An entity string equal to such a title takes
   its language (audit S31); a string with two different languages takes none."
  [record]
  (let [hits (for [p     [:canon/title :canon/uniform-title]
                   {:keys [value coord]} (qualified/values record p :canon/lang)
                   :let  [l (language-tag coord)]
                   :when l]
               [value l])]
    (into {} (for [[v ls] (group-by first hits)
                   :let  [ls (distinct (map second ls))]
                   :when (= 1 (count ls))]
               [v (first ls)]))))

(defn triples
  "The LRMoo view of `record` as RDF triples. Each WEMI entity is typed to its
   F-class; each `:lrmoo/*` assertion becomes an object-property triple (when its
   value is a reference) or a literal triple, except an entity's string, which
   is written through an E41 Appellation (`string-triples`). Each item is
   `[s-iri p-iri o]` with `o` = `{:iri s}` | `{:lit v}`.

   With `{:certified-only? true}` (D7), only `:asserted` assertions are emitted —
   the proof-backed subgraph an institution can ship with no fabricated identity —
   and only the entities those certified claims reference are typed."
  ([record] (triples record {}))
  ([record {:keys [certified-only?]}]
   (let [idx     (iri-index record)
         iri     (fn [id] (or (get idx id) (entity-iri id)))
         langs   (title-langs record)
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
      (mapcat (fn [a]
                (let [v (:value a)]
                  (cond
                    (model/reference-value? v)
                    [[(iri (:subject a)) (term-iri (:predicate a)) {:iri (iri (:value/target v))}]]

                    (contains? string-predicates (:predicate a))
                    (string-triples (iri (:subject a)) v (get langs v))

                    :else
                    [[(iri (:subject a)) (term-iri (:predicate a)) {:lit v}]])))
              view-as)))))

(defn- nt-escape [s]
  (-> (str s)
      (str/replace "\\" "\\\\")
      (str/replace "\"" "\\\"")
      (str/replace "\n" "\\n")
      (str/replace "\r" "\\r")
      (str/replace "\t" "\\t")))

(defn- literal-suffix
  "A literal's language tag (`@fr`) or datatype (`^^<…>`), as N-Triples/Turtle write it."
  [o]
  (cond (:lang o)     (str "@" (:lang o))
        (:datatype o) (str "^^<" (:datatype o) ">")
        :else         ""))

(defn- nt-object [o]
  (if (:iri o)
    (str "<" (:iri o) ">")
    (str "\"" (nt-escape (:lit o)) "\"" (literal-suffix o))))

(defn render-ntriples
  "Render a seq of `[s p o]` triples (o = `{:iri _}` | `{:lit _ :lang :datatype}`) as N-Triples,
   one `<s> <p> o .` per line. Reused by alternative projections (e.g. the CRM
   down-projection) that augment the triple seq before rendering."
  [triples]
  (str/join "\n"
            (for [[s p o] (checked triples)]
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
   "rdf"   "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
   "skos"  "http://www.w3.org/2004/02/skos/core#"})

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
(defn- ttl-term [o] (if (:iri o) (ttl-iri (:iri o)) (str "\"" (nt-escape (:lit o)) "\"" (literal-suffix o))))

(defn- turtle-prefixes
  "The `@prefix` lines for the namespaces `triples` use."
  [triples]
  (let [used (into (sorted-set)
                   (comp (mapcat (fn [[_ p o]]
                                   [(when (not= p rdf-type) (compact-prefix p))
                                    (when (:iri o) (compact-prefix (:iri o)))]))
                         (remove nil?))
                   triples)]
    (for [pfx used] (str "@prefix " pfx ": <" (rdf-prefixes pfx) "> ."))))

(defn- turtle-statements
  "`triples` as Turtle statements grouped by subject, each line indented by
   `indent`: `a` for `rdf:type`, `;` between predicates, `,` between objects."
  [triples indent]
  (let [by-subj (group-by first triples)]
    (for [s (distinct (map first triples))]
      (let [preds (distinct (map second (by-subj s)))
            lines (for [p preds]
                    (let [os (->> (by-subj s) (filter #(= p (second %))) (map #(nth % 2)))]
                      (str indent "    " (if (= p rdf-type) "a" (ttl-iri p)) " "
                           (str/join " , " (map ttl-term os)))))]
        (str indent (ttl-iri s) "\n" (str/join " ;\n" lines) " .")))))

(defn render-turtle
  "Render a `[s p o]` triple seq as Turtle: `@prefix` headers for the namespaces
   actually used, then statements grouped by subject — `a` for `rdf:type`, `;`
   between predicates and `,` between repeated objects. nil for an empty seq."
  [triples]
  (when (seq (checked triples))
    (let [header (turtle-prefixes triples)]
      (str (str/join "\n" header) (when (seq header) "\n\n")
           (str/join "\n" (turtle-statements triples ""))))))

(defn render-nquads
  "Render `graphs` — `[[graph-iri triples] …]`, `graph-iri` nil for the default
   graph — as N-Quads (https://www.w3.org/TR/n-quads/), one `<s> <p> o <g> .` per
   line. Every IRI is checked, graph names included."
  [graphs]
  (str/join "\n"
            (for [[g ts] graphs
                  :when  (seq ts)
                  [s p o] (checked ts)]
              (do (when g (check-iri! g))
                  (str "<" s "> <" p "> " (nt-object o) (when g (str " <" g ">")) " .")))))

(defn render-trig
  "Render `graphs` (as `render-nquads`) as TriG (https://www.w3.org/TR/trig/): the
   prefixes of every graph, then each non-empty graph as a `<g> { … }` block of
   Turtle statements (the default graph's statements unwrapped). nil when empty."
  [graphs]
  (let [graphs (filter (comp seq second) graphs)]
    (when (seq graphs)
      (doseq [[g ts] graphs] (when g (check-iri! g)) (checked ts))
      (let [header (turtle-prefixes (mapcat second graphs))]
        (str (str/join "\n" header) (when (seq header) "\n\n")
             (str/join "\n\n"
                       (for [[g ts] graphs]
                         (if g
                           (str "<" g "> {\n" (str/join "\n" (turtle-statements ts "    ")) "\n}")
                           (str/join "\n" (turtle-statements ts ""))))))))))

(defn render-jsonld
  "Render a `[s p o]` triple seq as compacted JSON-LD: an `@context` of the
   namespace prefixes and an `@graph` of one node object per subject (`@type` for
   `rdf:type`, `{\"@id\" …}` for IRI objects). nil for an empty seq."
  [triples]
  (when (seq (checked triples))
    (let [by-subj (group-by first triples)
          add     (fn [m k v] (update m k (fn [ex] (cond (nil? ex) v (vector? ex) (conj ex v) :else [ex v]))))
          term    (fn [o] (cond
                            (:iri o)      {"@id" (or (compact-iri (:iri o)) (:iri o))}
                            (:lang o)     {"@value" (:lit o) "@language" (:lang o)}
                            (:datatype o) {"@value" (:lit o) "@type" (:datatype o)}
                            :else         (:lit o)))
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

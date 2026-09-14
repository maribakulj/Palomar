(ns palomar.plugins.shape
  "XML shape adapter — walks a parsed XML source tree and produces an
   IR Record per a plugin's mapping (ADR 0009). Sole V1 producer of
   fragments: the rule layer cannot mint them (ADR 0011 §Limitation),
   so the canonical normalization in M4 assumes the shape adapter has
   already laid down references and fragment-level coords in the
   native vocabulary.

   The walker `ingest-xml` consumes XML elements as produced by
   `clojure.data.xml`. It is mapping-driven: it acts only on the
   predicates a plugin's mapping mentions, and consults
   `:mapping/qualifier` to decide which paths mint fragments. Dublin
   Core (`palomar.plugins.dc`) is the V1 consumer; it wraps `rewrite-tags`
   + `ingest-xml` in its own importer.

   ## XML convention

   - Caller supplies an already-parsed XML element (the output of
     `clojure.data.xml/parse-str` or equivalent). The walker uses
     `:tag` and attribute keys as-is — whatever keyword form the
     parser produced is what ends up in fragment ids and assertion
     predicates.
   - `clojure.data.xml/parse-str` by default produces URI-encoded
     keyword namespaces (`:xmlns.http%3A%2F%2F.../title`). For
     human-inspectable fragment ids, callers pre-process the parsed
     tree with `rewrite-tags` to a clean namespaced form (e.g.
     `:dc/title`). The walker stays format-agnostic by accepting
     whichever keyword form the caller normalizes to.
   - The top-level element is the record root; its child elements
     are predicates. Each child's `:tag` is the predicate keyword.
   - Multiplicity: repeated child elements with the same tag
     contribute one occurrence each, in document order.
   - Flat values use the element's text content (the concatenation of
     string children of `:content`).
   - Qualified values use the element's attribute named by the
     mapping's `:mapping/qualifier :from` for the qualifier, and the
     text content for the main value. Missing attribute → fragment
     without qualifier coord; missing text → fragment without value
     coord. Mixed content (text interleaved with nested elements) is
     V1-lossy: only the string children of `:content` are kept.
   - Whitespace preservation: text content is returned verbatim,
     including leading/trailing whitespace from XML indentation.
     `<dc:title>\\n  Les Misérables\\n</dc:title>` ingests as the
     literal `\"\\n  Les Misérables\\n\"`. To normalize it, declare
     `:mapping/transform [:trim]` on the mapping; the shape adapter
     never trims at ingest (XML carries whitespace as data —
     `xml:space=\"preserve\"` is part of the spec — so the ingest
     layer stays faithful and the `:normalize` phase is the correct
     home for format-normalizing transforms, ADR 0004).

   ## Fragment minting (M1, ADR 0012)

   Fragment ids come from `palomar.model/mint-fragment-id`. The
   locator is `[predicate idx]` where `idx` is the occurrence's
   position in document order at that predicate. Within a predicate
   the order is preserved by construction (XML `:content` preserves
   SAX order).

   ## Out of scope (V1)

   - Deep nesting (CIDOC-style multi-level fragments). Locators are
     two-segment `[predicate idx]` only; richer nesting waits for a
     concrete plugin need.
   - XML root-attribute extraction. The caller sets `:record-id` and
     `:kind` explicitly.
   - Diagnostic emission for shape mismatches (a flat mapping seeing
     a sub-element, or a qualified mapping seeing an element with no
     attribute, etc.). Silent skip in V1; revisit if a plugin author
     reports confusion."
  (:require [palomar.model :as model]))

;; ---------------------------------------------------------------------------
;; Mapping inspection
;;
;; The walker is mapping-driven: it acts only on predicates the mapping
;; mentions, and consults `:mapping/qualifier` to decide which paths
;; mint fragments. These two projections are everything the walker
;; needs to know about the mapping's structure — transform chains and
;; canonical predicates are M4's concern at :normalize.
;; ---------------------------------------------------------------------------

(defn- qualified-predicate-map
  "Return `{native-predicate → qualifier-from-key}` for every mapping
   rule that declares a `:mapping/qualifier`. If two rules share the
   same `:mapping/from` with different qualifier configs, the last
   one wins; cross-rule ingest consistency is a plugin-authoring
   discipline in V1."
  [mapping-rules]
  (into {}
        (keep (fn [r]
                (when-let [q (:mapping/qualifier r)]
                  [(:mapping/from r) (:from q)])))
        mapping-rules))

(defn- mapped-predicate-set
  "Set of source predicates mentioned anywhere in `mapping-rules`."
  [mapping-rules]
  (into #{} (map :mapping/from) mapping-rules))

;; ---------------------------------------------------------------------------
;; Emit helpers
;;
;; The walker builds `model/assertion` and `model/fragment` shapes
;; directly — no transforms here, no predicate renaming. Native
;; vocabulary all the way; M4 :normalize rewrites to canonical later.
;; Every emitted assertion carries `:provenance {:pass :ingest}` so
;; trace queries can attribute it to the ingest phase.
;;
;; `build-fragment-assertions` is the format-agnostic core: given the
;; already-extracted main value and qualifier value for one
;; occurrence, it mints the fragment id and emits the reference,
;; value coord and qualifier coord. The format-specific code
;; (`extract-occurrence-xml`) is just two lines of shape-dependent
;; extraction.
;; ---------------------------------------------------------------------------

(def ^:private ingest-provenance
  {:pass :ingest})

(defn- emit-flat
  "Build assertions for a flat predicate. Each element of `values`
   yields one `[record-id predicate value]` assertion. nil values are
   skipped (an empty XML element has no text content, so emitting an
   assertion with value nil would be shape-invalid)."
  [record-id predicate values]
  (into []
        (keep (fn [v]
                (when (some? v)
                  (model/assertion {:subject    record-id
                                    :predicate  predicate
                                    :value      v
                                    :provenance ingest-provenance}))))
        values))

(defn- build-fragment-assertions
  "Mint one fragment and assemble its three potential assertions: the
   record-level reference (always emitted), the fragment-level value
   coord (emitted iff `main-value` is non-nil) and the fragment-level
   qualifier coord (emitted iff `qual-value` is non-nil).

   `source-tag` is the format-identifier keyword (`:xml`) stored in the
   Fragment's `:source` field for traceability — it does not affect the
   fragment id, which depends only on `record-id`, `predicate` and `idx`
   per ADR 0012."
  [record-id predicate qualifier-key idx main-value qual-value source-tag]
  (let [frag-id (model/mint-fragment-id record-id [predicate idx])
        base    {:fragment   (model/fragment
                              {:id     frag-id
                               :source [source-tag predicate idx]})
                 :assertions [(model/assertion
                               {:subject    record-id
                                :predicate  predicate
                                :value      (model/reference frag-id)
                                :provenance ingest-provenance})]}]
    (cond-> base
      (some? main-value)
      (update :assertions conj
              (model/assertion {:subject    frag-id
                                :predicate  predicate
                                :value      main-value
                                :provenance ingest-provenance}))

      (some? qual-value)
      (update :assertions conj
              (model/assertion {:subject    frag-id
                                :predicate  qualifier-key
                                :value      qual-value
                                :provenance ingest-provenance})))))

(defn- emit-qualified
  "Build fragments + assertions across every occurrence of a qualified
   predicate, preserving document order via the occurrence index.
   `extractor` is a two-arg fn `(occurrence, qualifier-key) →
   {:main-value :qual-value}` that the format-specific walker
   supplies."
  [record-id predicate qualifier-key occurrences extractor source-tag]
  (let [emitted (map-indexed
                 (fn [idx occ]
                   (let [{:keys [main-value qual-value]} (extractor occ qualifier-key)]
                     (build-fragment-assertions
                      record-id predicate qualifier-key idx
                      main-value qual-value source-tag)))
                 occurrences)]
    {:fragments  (mapv :fragment emitted)
     :assertions (vec (mapcat :assertions emitted))}))

;; ---------------------------------------------------------------------------
;; XML occurrence extraction
;;
;; clojure.data.xml represents an element as
;;   {:tag :pred :attrs {...} :content [strings or child elements]}
;; Text content is the concatenation of string children of `:content`;
;; nested child elements are V1-lossy (skipped). Attributes are
;; key-value pairs in `:attrs`.
;; ---------------------------------------------------------------------------

(defn- xml-text-content
  "Concatenated string content of an XML element. Returns nil if the
   element has no string children — distinguishes empty from missing
   so empty elements (`<dc:title/>`) don't fabricate a value coord.

   Whitespace is preserved verbatim: an indented `<dc:title>↵
   Les Misérables↵</dc:title>` yields `\"\\n Les Misérables\\n\"`,
   not `\"Les Misérables\"`. Normalizing it requires the mapping to
   declare `:mapping/transform [:trim]`; see this namespace's
   docstring `## XML convention` for rationale."
  [elem]
  (let [strings (filterv string? (:content elem))]
    (when (seq strings)
      (apply str strings))))

(defn- xml-attr
  "Get an attribute value (a string) from an XML element. nil if
   absent — mirrors the no-qualifier case."
  [elem attr-key]
  (get-in elem [:attrs attr-key]))

(defn- extract-occurrence-xml
  "XML shape: text content for the main value, named attribute for
   the qualifier."
  [xml-element qualifier-key]
  {:main-value (xml-text-content xml-element)
   :qual-value (xml-attr xml-element qualifier-key)})

(defn- xml-element-children
  "Map children of an XML element, grouped by tag in document order.
   String content (between or around elements) is skipped."
  [xml-element]
  (let [children (filterv map? (:content xml-element))]
    (reduce (fn [acc child]
              (update acc (:tag child) (fnil conj []) child))
            (array-map) ; preserves first-seen order
            children)))

;; ---------------------------------------------------------------------------
;; Record assembly
;; ---------------------------------------------------------------------------

(defn- assemble-record
  "Assemble a Record from the walker's accumulated fragments and
   assertions, and assert `record-consistent?` on it. A consistency
   failure throws — it indicates a bug in this namespace (every
   emitted assertion's subject is, by construction, the record id or
   a fragment id we just minted)."
  [record-id kind source fragments assertions]
  (let [record (model/record {:id         record-id
                              :kind       kind
                              :source     source
                              :fragments  fragments
                              :assertions assertions})]
    (when-not (model/record-consistent? record)
      (throw (ex-info "Shape adapter produced an inconsistent record (bug in palomar.plugins.shape)"
                      {:explanation (model/explain-consistency record)})))
    record))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn ingest-xml
  "Walk a parsed XML element (the output of
   `clojure.data.xml/parse-str`) and produce a Record per the
   plugin's mapping.

   Inputs:
   - `xml-element`: the record root, an element map with `:tag`,
     `:attrs`, `:content`. Tag keywords are used as-is; the caller is
     responsible for normalizing them to whatever form fragment ids
     and assertion predicates should carry (typically via
     `rewrite-tags`, since `data.xml` defaults to URI-encoded
     namespaces).
   - `mapping`: vector of `MappingRule` per ADR 0009.
   - `opts`: `{:record-id :kind :source}`. `:record-id` and `:kind`
     are required; `:source` is optional and carried into the Record
     for traceability.

   Output: a `Record` per `palomar.model/Record`, populated with
   fragments and native-vocabulary assertions. `record-consistent?` is
   asserted on the result."
  [xml-element mapping {:keys [record-id kind source]}]
  (when-not (and (map? xml-element) (contains? xml-element :tag))
    (throw (ex-info "ingest-xml expects an XML element map (with :tag)"
                    {:got xml-element})))
  (let [qualified-by-pred (qualified-predicate-map mapping)
        mapped-preds      (mapped-predicate-set mapping)
        by-tag            (xml-element-children xml-element)
        {:keys [fragments assertions]}
        (reduce
         (fn [acc [pred elems]]
           (cond
             (not (contains? mapped-preds pred))
             acc

             (contains? qualified-by-pred pred)
             (let [qual-key (get qualified-by-pred pred)
                   emitted  (emit-qualified record-id pred qual-key elems
                                            extract-occurrence-xml :xml)]
               (-> acc
                   (update :fragments into (:fragments emitted))
                   (update :assertions into (:assertions emitted))))

             :else
             (update acc :assertions into
                     (emit-flat record-id pred (mapv xml-text-content elems)))))
         {:fragments [] :assertions []}
         by-tag)]
    (assemble-record record-id kind source fragments assertions)))

;; ---------------------------------------------------------------------------
;; XML tag rewriting
;;
;; `clojure.data.xml/parse-str` produces tags whose namespace is the
;; URI-encoded source URI (e.g. `:xmlns.http%3A%2F%2F.../title`). For
;; inspectable fragment ids, callers want short prefixes like
;; `:dc/title`. This helper walks a parsed tree and substitutes a
;; `{short-prefix → URI}` alias map for the URI-encoded form.
;; Namespaces not in the map pass through unchanged — partial aliasing
;; is fine.
;; ---------------------------------------------------------------------------

(defn rewrite-tags
  "Rewrite the namespaces of every tag and attribute keyword in a
   parsed XML tree, mapping `clojure.data.xml`'s URI-encoded form
   back to short prefixes.

   `aliases` is `{prefix-keyword-or-symbol → URI-string}` (same shape
   as `clojure.data.xml/alias-uri` takes). Tags and attribute keys
   whose namespace does not match any alias pass through unchanged.

   Example:

     (rewrite-tags parsed
                   {:dc  \"http://purl.org/dc/elements/1.1/\"
                    :xml \"http://www.w3.org/XML/1998/namespace\"})
     ;; tags like :xmlns.http%3A%2F%2F.../title become :dc/title,
     ;; attrs like :xmlns.http%3A%2F%2F.../lang become :xml/lang"
  [xml-element aliases]
  (let [encoded-ns->prefix
        (into {}
              (for [[prefix uri] aliases]
                [(str "xmlns." (java.net.URLEncoder/encode (str uri) "UTF-8"))
                 (name prefix)]))
        rewrite-kw
        (fn [k]
          (if-let [short (and (keyword? k) (get encoded-ns->prefix (namespace k)))]
            (keyword short (name k))
            k))
        rewrite-attrs
        (fn [attrs]
          (into {} (map (fn [[k v]] [(rewrite-kw k) v])) attrs))
        rewrite
        (fn rewrite [node]
          (if (and (map? node) (contains? node :tag))
            (-> node
                (update :tag rewrite-kw)
                (update :attrs rewrite-attrs)
                (update :content (fn [c] (mapv rewrite c))))
            node))]
    (rewrite xml-element)))

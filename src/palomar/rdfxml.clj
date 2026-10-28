(ns palomar.rdfxml
  "An RDF/XML reader (https://www.w3.org/TR/rdf-syntax-grammar/), for the
   authority files institutions publish that way (MADS, SKOS; ADR 0038).

   It reads the grammar's common productions:
   - node elements, typed by their tag unless `rdf:Description`, named by
     `rdf:about` (resolved against `xml:base`), `rdf:nodeID`, or neither (a
     fresh blank node);
   - property attributes;
   - property elements whose object is `rdf:resource`, `rdf:nodeID`, a nested
     node element, or text (with `xml:lang` inherited and `rdf:datatype`);
   - `rdf:parseType` `Resource`, `Collection` (an `rdf:List`) and `Literal`
     (the content as exclusive canonical XML, markup kept).

   Reification (`rdf:ID` on a property) and `rdf:li` numbering are not read; an
   element using them throws rather than yielding a guessed triple. Parsing goes
   through `palomar.xml`, so a DTD is refused.

   Terms come back as `palomar.plugins.lrmoo.ntriples/parse` gives them:
   `{:iri s}`, `{:bnode label}` or `{:lit s :lang tag :datatype iri}`."
  (:require [clojure.string :as str]
            [palomar.xml :as rx]))

(def ^:private rdf "http://www.w3.org/1999/02/22-rdf-syntax-ns#")
(def ^:private xml-ns "http://www.w3.org/XML/1998/namespace")

(defn- qname-iri
  "The IRI a data.xml tag or attribute key stands for: its namespace URI and
   local name, or nil for an unqualified name."
  [k]
  (let [ns (namespace k)]
    (when (and ns (str/starts-with? ns "xmlns."))
      (str (java.net.URLDecoder/decode (subs ns 6) "UTF-8") (name k)))))

(defn- attr [el iri] (some (fn [[k v]] (when (= iri (qname-iri k)) v)) (:attrs el)))

(defn- resolve-iri [base ref]
  (cond
    (re-find #"^[A-Za-z][A-Za-z0-9+.-]*:" ref) ref
    (nil? base)                                 ref
    (str/starts-with? ref "#")                  (str (str/replace base #"#.*$" "") ref)
    :else                                       (str (.resolve (java.net.URI. base) ^String ref))))

(defn- elements [el] (filter map? (:content el)))

(defn- text-of [el] (apply str (filter string? (:content el))))

;; ---------------------------------------------------------------------------
;; rdf:parseType="Literal": the content as exclusive canonical XML
;; ---------------------------------------------------------------------------

(defn- escape-text [t]
  (-> t (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;") (str/replace "\r" "&#xD;")))

(defn- escape-attr [v]
  (-> v (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace "\"" "&quot;")
      (str/replace "\t" "&#x9;") (str/replace "\n" "&#xA;") (str/replace "\r" "&#xD;")))

(defn- ns-uri [k]
  (let [n (namespace k)]
    (when (and n (str/starts-with? n "xmlns.")) (java.net.URLDecoder/decode (subs n 6) "UTF-8"))))

(defn- nss [el] (get-in (meta el) [:clojure.data.xml/nss :p->u]))

(defn- prefix-of
  "The prefix element `el` most likely wrote namespace `uri` with, `ancestors`
   its ancestors, nearest first. The parser keeps the bindings in scope, not
   the prefix a name was written with, so when several prefixes bind `uri`
   the one declared nearest wins (on the element itself, then on each
   ancestor up), then the default namespace (for an element name only: an
   unprefixed attribute is in no namespace), then the first in order."
  [el ancestors uri attribute?]
  (let [ok?      #(or (not attribute?) (not= "" %))
        declared (fn [e up] (sort (for [[p u] (nss e) :when (and (= u uri) (ok? p) (not= u (get (nss up) p)))] p)))
        chain    (cons el ancestors)]
    (or (some (fn [[e up]] (first (declared e up))) (map vector chain (rest chain)))
        (when (and (not attribute?) (= uri (get (nss el) ""))) "")
        (first (sort (for [[p u] (nss el) :when (and (= u uri) (ok? p))] p))))))

(defn- qname [prefix local] (if (str/blank? prefix) local (str prefix ":" local)))

(defn- canonical
  "Element or text `x` (`ancestors` its ancestor elements, nearest first) as exclusive
   canonical XML (https://www.w3.org/TR/xml-exc-c14n/), `declared` the prefix
   → namespace bindings an output ancestor already wrote: an element declares
   the namespaces it and its attributes use and its ancestors did not;
   namespace declarations, then attributes, are sorted; an empty element is
   written with a start and an end tag."
  [x ancestors declared]
  (if (string? x)
    (escape-text x)
    (let [uri    (ns-uri (:tag x))
          prefix (if uri (or (prefix-of x ancestors uri false) "") "")
          attrs  (for [[k v] (:attrs x)
                       :let [u (ns-uri k)]]
                   {:uri u :local (name k) :value v
                    :prefix (when u (if (= u xml-ns) "xml" (or (prefix-of x ancestors u true)
                                                               (throw (ex-info "rdfxml: an attribute's namespace has no prefix"
                                                                               {:namespace u})))))})
          used   (cond-> (into {} (keep (fn [{:keys [uri prefix]}] (when (and uri (not= "xml" prefix)) [prefix uri]))) attrs)
                   true (assoc prefix (or uri "")))
          new    (sort-by key (remove (fn [[p u]] (= (get declared p (when (= "" p) "")) u)) used))
          scope  (into declared new)]
      (str "<" (qname prefix (name (:tag x)))
           (apply str (for [[p u] new] (str " " (if (= "" p) "xmlns" (str "xmlns:" p)) "=\"" (escape-attr u) "\"")))
           (apply str (for [{:keys [prefix local value]} (sort-by (juxt #(or (:uri %) "") :local) attrs)]
                        (str " " (qname prefix local) "=\"" (escape-attr value) "\"")))
           ">"
           (apply str (map #(canonical % (cons x ancestors) scope) (:content x)))
           "</" (qname prefix (name (:tag x))) ">"))))

(defn- xml-literal
  "The lexical form of a `rdf:parseType=\"Literal\"` property element's
   content: its nodes as exclusive canonical XML (RDF/XML grammar 7.2.17)."
  [pe]
  (apply str (map #(canonical % [pe] {"" ""}) (:content pe))))

(defn parse
  "The triples of RDF/XML document `s`, as `[subject predicate object]` terms."
  [s]
  (let [root    (rx/parse-str s)
        counter (atom 0)
        ;; the document's own node ids and the ones generated here live in
        ;; separate spaces, so a `rdf:nodeID=\"g1\"` never meets a fresh node
        bnode   #(hash-map :bnode (str "g" (swap! counter inc)))
        named   #(hash-map :bnode (str "n" %))
        out     (transient [])
        emit    (fn [t] (conj! out t))]
    (letfn [(ctx [el {:keys [base lang] :as c}]
              (assoc c
                     ;; a relative xml:base resolves against the base in scope
                     :base (if-let [b (attr el (str xml-ns "base"))] (resolve-iri base b) base)
                     :lang (or (attr el (str xml-ns "lang")) lang)))
            (node [el c]
              (let [c    (ctx el c)
                    about (attr el (str rdf "about"))
                    nid   (attr el (str rdf "nodeID"))
                    subj  (cond about {:iri (resolve-iri (:base c) about)}
                                nid   (named nid)
                                :else (bnode))
                    tag   (qname-iri (:tag el))]
                (when (attr el (str rdf "ID"))
                  (throw (ex-info "rdfxml: rdf:ID is not supported" {:element tag})))
                (when (and tag (not= tag (str rdf "Description")))
                  (emit [subj {:iri (str rdf "type")} {:iri tag}]))
                (doseq [[k v] (:attrs el)
                        :let [p (qname-iri k)]
                        :when (and p (not (str/starts-with? p rdf)) (not (str/starts-with? p xml-ns)))]
                  (emit [subj {:iri p} (cond-> {:lit v} (:lang c) (assoc :lang (:lang c)))]))
                (doseq [pe (elements el)] (property subj pe c))
                subj))
            (property [subj pe c]
              (let [c     (ctx pe c)
                    p     (qname-iri (:tag pe))
                    _     (when (or (nil? p) (= p (str rdf "li")) (attr pe (str rdf "ID")))
                            (throw (ex-info "rdfxml: unsupported property element" {:element (:tag pe)})))
                    res   (attr pe (str rdf "resource"))
                    nid   (attr pe (str rdf "nodeID"))
                    ptype (attr pe (str rdf "parseType"))
                    dt    (attr pe (str rdf "datatype"))
                    kids  (elements pe)
                    pred  {:iri p}]
                (cond
                  res   (emit [subj pred {:iri (resolve-iri (:base c) res)}])
                  nid   (emit [subj pred (named nid)])

                  (= "Resource" ptype)
                  (let [b (bnode)]
                    (emit [subj pred b])
                    (doseq [k kids] (property b k c)))

                  (= "Collection" ptype)
                  (let [items (mapv #(node % c) kids)
                        cells (mapv (fn [_] (bnode)) items)]
                    (emit [subj pred (if (seq cells) (first cells) {:iri (str rdf "nil")})])
                    (doseq [[i cell] (map-indexed vector cells)]
                      (emit [cell {:iri (str rdf "first")} (items i)])
                      (emit [cell {:iri (str rdf "rest")} (if (< (inc i) (count cells)) (cells (inc i)) {:iri (str rdf "nil")})])))

                  (= "Literal" ptype)
                  (emit [subj pred {:lit (xml-literal pe) :datatype (str rdf "XMLLiteral")}])

                  (seq kids)
                  (emit [subj pred (node (first kids) c)])

                  :else
                  (emit [subj pred (cond-> {:lit (text-of pe)}
                                     dt                      (assoc :datatype (resolve-iri (:base c) dt))
                                     (and (nil? dt) (:lang c)) (assoc :lang (:lang c)))]))))]
      (let [c (ctx root {})]
        (if (= (str rdf "RDF") (qname-iri (:tag root)))
          (doseq [n (elements root)] (node n c))
          (node root c))))
    (persistent! out)))

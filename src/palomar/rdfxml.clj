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
     (the content's text).

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
                  (emit [subj pred {:lit (text-of pe) :datatype (str rdf "XMLLiteral")}])

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

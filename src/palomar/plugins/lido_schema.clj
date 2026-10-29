(ns palomar.plugins.lido-schema
  "LIDO records against the LIDO schema (post-audit 5, G6): the official XSDs
   of LIDO 1.0 and 1.1, and the schemas they import (XML, GML 3.1.1, XLink),
   are vendored under `resources/palomar/schemas/`, each at the path of its
   URL, and resolved from there: validating never touches the network.

   A record is validated against the version its `xsi:schemaLocation` names;
   one that names none is valid if it is valid LIDO 1.1 or 1.0. LIDO-MC's own
   rules (its further required elements and vocabularies) are no XSD and are
   not checked."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.net URI]
           [javax.xml XMLConstants]
           [javax.xml.parsers DocumentBuilderFactory]
           [javax.xml.transform.dom DOMSource]
           [javax.xml.transform.stream StreamSource]
           [javax.xml.validation Schema SchemaFactory]
           [org.w3c.dom Element]
           [org.w3c.dom.ls DOMImplementationLS LSResourceResolver]
           [org.xml.sax ErrorHandler SAXParseException]))

(def ^:private lido-ns "http://www.lido-schema.org")

(def ^:private schema-urls
  {:v1.0 "http://www.lido-schema.org/schema/v1.0/lido-v1.0.xsd"
   :v1.1 "http://www.lido-schema.org/schema/v1.1/lido-v1.1.xsd"})

(defn- vendored
  "The classpath resource of a schema URL `u`, or nil."
  [^String u]
  (let [x (URI. u)]
    (io/resource (str "palomar/schemas/" (.getHost x) (.getPath x)))))

(def ^:private ls
  (delay (-> (DocumentBuilderFactory/newInstance) .newDocumentBuilder .getDOMImplementation
             (.getFeature "LS" "3.0"))))

(def ^:private resolver
  (reify LSResourceResolver
    (resolveResource [_ _ _ _ system-id base-uri]
      (let [abs (str (if base-uri (.resolve (URI. base-uri) (URI. system-id)) (URI. system-id)))]
        (when-let [r (vendored abs)]
          (doto (.createLSInput ^DOMImplementationLS @ls)
            (.setSystemId abs)
            (.setByteStream (io/input-stream r))))))))

(defn- compile-schema ^Schema [u]
  (let [sf (doto (SchemaFactory/newInstance XMLConstants/W3C_XML_SCHEMA_NS_URI)
             ;; only what is vendored: an import that is not fails, never fetched
             (.setProperty XMLConstants/ACCESS_EXTERNAL_SCHEMA "")
             (.setResourceResolver resolver))]
    (.newSchema sf (doto (StreamSource. (io/input-stream (vendored u))) (.setSystemId ^String u)))))

(def ^:private schemas
  (delay (into {} (for [[v u] schema-urls] [v (compile-schema u)]))))

(defn- violations
  "The messages of `el` against `schema`, first ones first (at most 5)."
  [^Schema schema ^Element el]
  (let [errs (atom [])
        v    (.newValidator schema)]
    (.setErrorHandler v (reify ErrorHandler
                          (warning [_ _])
                          (error [_ e] (swap! errs conj (.getMessage ^SAXParseException e)))
                          (fatalError [_ e] (swap! errs conj (.getMessage ^SAXParseException e)))))
    (.validate v (DOMSource. el))
    (vec (take 5 @errs))))

(defn declared-in
  "The LIDO version an `xsi:schemaLocation` value `s` names, or nil."
  [s]
  (let [s (str s)]
    (cond (re-find #"lido-schema\.org/schema/v1\.1" s) :v1.1
          (re-find #"lido-schema\.org/schema/v1\.0" s) :v1.0)))

(def ^:private xsi "http://www.w3.org/2001/XMLSchema-instance")

(defn- lido-elements
  "The `lido` elements of `root`, as the importer takes them: `root` itself,
   or its direct children, by local name in any namespace (G6 review)."
  [^Element root]
  (if (= "lido" (.getLocalName root))
    [root]
    (let [nl (.getChildNodes root)]
      (vec (for [i (range (.getLength nl))
                 :let [n (.item nl i)]
                 :when (and (instance? Element n) (= "lido" (.getLocalName ^Element n)))]
             n)))))

(defn- check
  "`{:version :errors}` of the `lido` element `el`, `version` the one its
   document declares (nil: valid as either)."
  [^Element el version]
  (cond
    (not= lido-ns (.getNamespaceURI el))
    {:version (or version :v1.1)
     :errors  [(str "the record's element is not in the LIDO namespace " lido-ns
                    (when-let [u (.getNamespaceURI el)] (str " but in " u)))]}
    version {:version version :errors (violations (get @schemas version) el)}
    :else   (let [e11 (violations (:v1.1 @schemas) el)]
              (if (empty? e11)
                {:version :v1.1 :errors []}
                (let [e10 (violations (:v1.0 @schemas) el)]
                  (if (empty? e10) {:version :v1.0 :errors []} {:version :v1.1 :errors e11}))))))

(defn errors
  "For each `lido:lido` of the LIDO document `doc` (a string) the importer
   reads (the root, or its direct children named `lido`), in order:
   `{:version v :errors [message…]}`, `:errors` empty when it is valid. The
   version is `version` when given (a stream knows it from its root), else
   the one the root's or the record's `xsi:schemaLocation` names. A record
   whose element is not in the LIDO namespace is not valid."
  ([doc] (errors doc nil))
  ([^String doc version]
   (let [dbf  (doto (DocumentBuilderFactory/newInstance)
                (.setNamespaceAware true)
                (.setExpandEntityReferences false)
                (.setFeature "http://apache.org/xml/features/disallow-doctype-decl" true))
         d    (.parse (.newDocumentBuilder dbf) (org.xml.sax.InputSource. (java.io.StringReader. doc)))
         root (.getDocumentElement d)
         loc  (fn [^Element e] (not-empty (.getAttributeNS e xsi "schemaLocation")))]
     (vec (for [el (lido-elements root)]
            (check el (or version (declared-in (or (loc el) (loc root))))))))))

(defn version-name [v] (str/replace (name v) "v" "LIDO "))

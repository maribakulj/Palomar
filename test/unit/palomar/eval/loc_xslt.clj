(ns palomar.eval.loc-xslt
  "Shared offline runner for the Library of Congress crosswalk stylesheets used as
   conversion *oracles* (`MARC21slim2OAIDC`, `MARC21slim2MODS3-1`). Runs a vendored,
   unmodified LoC stylesheet through the JDK's built-in XSLT 1.0 engine (no added
   dependency).

   One offline concession, common to both crosswalks: their absolute
   `xsl:import`/`xsl:include` of `MARC21slimUtils.xsl` (an loc.gov URL that is 403 in
   the sandbox) is redirected to the locally-vendored copy via a `URIResolver`. Two
   JDK XML processing limits are pinned so the transform behaves identically across
   the CI JDK matrix (21/24) rather than depending on a runner's defaults:

   - `accessExternalStylesheet = all` — permit the (now-local) import;
   - `jdk.xml.xpathExprOpLimit = 0` — the 99 KB MODS stylesheet contains a
     101-operator XPath union (a long `marc:datafield[@tag=…]|…` list) that trips the
     default per-expression cap of 100. These are trusted LoC stylesheets, so the
     cap is lifted rather than worked around."
  (:require [clojure.string :as str])
  (:import [java.io File StringReader StringWriter]
           [javax.xml.transform TransformerFactory URIResolver]
           [javax.xml.transform.stream StreamResult StreamSource]))

(def dir "test/fixtures/conformance/crosswalks/")

(def ^:private utils-resolver
  "Redirects either crosswalk's absolute `MARC21slimUtils.xsl` reference to the
   vendored copy; everything else resolves normally."
  (reify URIResolver
    (resolve [_ href _]
      (when (str/includes? href "slimUtils")
        (StreamSource. (File. (str dir "MARC21slimUtils.xsl")))))))

(defn run-stylesheet
  "Transform `xml` through the vendored LoC `stylesheet` (a filename under `dir`),
   returning the result as a string."
  [stylesheet xml]
  (let [tf (doto (TransformerFactory/newInstance)
             (.setAttribute "http://javax.xml.XMLConstants/property/accessExternalStylesheet" "all")
             (.setAttribute "jdk.xml.xpathExprOpLimit" "0")
             (.setURIResolver utils-resolver))
        t  (.newTransformer tf (StreamSource. (File. (str dir stylesheet))))
        w  (StringWriter.)]
    (.transform t (StreamSource. (StringReader. xml)) (StreamResult. w))
    (str w)))

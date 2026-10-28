(ns palomar.rdfxml-test
  "The RDF/XML reader (ADR 0038) on its common productions and on the vendored
   MADS and CIDOC-CRM files."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.rdfxml :as rdfxml]))

(def ^:private rdf "http://www.w3.org/1999/02/22-rdf-syntax-ns#")

(defn- doc [body]
  (str "<rdf:RDF xmlns:rdf=\"" rdf "\" xmlns:ex=\"http://example.org/\" xml:base=\"http://example.org/base/\">"
       body "</rdf:RDF>"))

(deftest node-elements-properties-and-literals
  (let [ts (set (rdfxml/parse (doc (str "<ex:Person rdf:about=\"hugo\" ex:born=\"1802\">"
                                        "<ex:name xml:lang=\"fr\">Victor Hugo</ex:name>"
                                        "<ex:age rdf:datatype=\"http://www.w3.org/2001/XMLSchema#integer\">83</ex:age>"
                                        "<ex:knows rdf:resource=\"#dumas\"/>"
                                        "<ex:wrote><ex:Book><ex:title>Les Misérables</ex:title></ex:Book></ex:wrote>"
                                        "</ex:Person>"))))
        s  {:iri "http://example.org/base/hugo"}]
    (is (contains? ts [s {:iri (str rdf "type")} {:iri "http://example.org/Person"}]) "typed by its tag, about resolved against xml:base")
    (is (contains? ts [s {:iri "http://example.org/born"} {:lit "1802"}]) "a property attribute")
    (is (contains? ts [s {:iri "http://example.org/name"} {:lit "Victor Hugo" :lang "fr"}]))
    (is (contains? ts [s {:iri "http://example.org/age"} {:lit "83" :datatype "http://www.w3.org/2001/XMLSchema#integer"}]))
    (is (contains? ts [s {:iri "http://example.org/knows"} {:iri "http://example.org/base/#dumas"}]))
    (let [book (some (fn [[s' p o]] (when (and (= s s') (= "http://example.org/wrote" (:iri p))) o)) ts)]
      (is (:bnode book) "a nested node is a blank node")
      (is (contains? ts [book {:iri "http://example.org/title"} {:lit "Les Misérables"}])))))

(deftest parse-types
  (let [ts (rdfxml/parse (doc (str "<rdf:Description rdf:about=\"http://example.org/x\">"
                                   "<ex:r rdf:parseType=\"Resource\"><ex:v>1</ex:v></ex:r>"
                                   "<ex:list rdf:parseType=\"Collection\"><rdf:Description rdf:about=\"http://example.org/a\"/></ex:list>"
                                   "</rdf:Description>")))
        ps (set (map (comp :iri second) ts))]
    (is (contains? ps "http://example.org/v"))
    (is (contains? ps (str rdf "first")))
    (is (some #(= {:iri (str rdf "nil")} (nth % 2)) ts) "a collection ends in rdf:nil")
    (is (not-any? #(= (str rdf "type") (:iri (second %))) ts) "rdf:Description adds no type")))

(deftest what-it-does-not-read-it-refuses
  (is (thrown? clojure.lang.ExceptionInfo (rdfxml/parse (doc "<rdf:Description rdf:about=\"http://e/x\"><ex:p rdf:ID=\"s1\">v</ex:p></rdf:Description>"))))
  (is (thrown? Exception (rdfxml/parse (str "<!DOCTYPE x [<!ENTITY a \"b\">]>"
                                            (doc "<rdf:Description rdf:about=\"http://e/x\"><ex:p>&a;</ex:p></rdf:Description>"))))
      "a DTD is refused (palomar.xml)"))

(deftest the-vendored-files-read
  (is (some #(str/includes? (str (:lit (nth % 2))) "Film and video adaptations")
            (rdfxml/parse (slurp "test/fixtures/documentary/mads/loc_mads_authority.rdf"))))
  (is (< 4000 (count (rdfxml/parse (str/replace (slurp "test/fixtures/documentary/cidoc-crm/CIDOC_CRM_v7.1.3.rdf") #"^﻿" ""))))))

(deftest a-document-node-id-never-meets-a-generated-node
  (testing "rdf:nodeID=\"g1\" and the first anonymous node stay two nodes (post-audit test 03)"
    (let [ts    (rdfxml/parse (doc (str "<rdf:Description rdf:nodeID=\"g1\"><ex:k>named</ex:k></rdf:Description>"
                                        "<rdf:Description><ex:k>anonymous</ex:k></rdf:Description>"
                                        "<rdf:Description rdf:nodeID=\"n1\"><ex:k>other</ex:k></rdf:Description>")))
          subjs (set (map first ts))]
      (is (= 3 (count subjs))))))

(deftest a-relative-xml-base-resolves-against-the-one-in-scope
  (testing "nested relative xml:base (post-audit test 12)"
    (let [ts (rdfxml/parse (str "<rdf:RDF xmlns:rdf=\"" rdf "\" xmlns:ex=\"http://example.org/\" xml:base=\"http://example.org/a/\">"
                                "<rdf:Description xml:base=\"b/\" rdf:about=\"c\"><ex:p rdf:resource=\"d\"/></rdf:Description>"
                                "</rdf:RDF>"))]
      (is (= [[{:iri "http://example.org/a/b/c"} {:iri "http://example.org/p"} {:iri "http://example.org/a/b/d"}]] ts)))))

(defn- literal-of [inner]
  (let [ts (rdfxml/parse (doc (str "<rdf:Description rdf:about=\"http://example.org/x\">"
                                   "<ex:d rdf:parseType=\"Literal\">" inner "</ex:d></rdf:Description>")))]
    (nth (first ts) 2)))

(deftest an-xml-literal-keeps-its-markup
  (testing "parseType Literal keeps the elements, their text and the text around them (post-audit 2, D5)"
    (is (= {:lit "avant <b xmlns=\"http://www.w3.org/1999/xhtml\">Important</b> après" :datatype (str rdf "XMLLiteral")}
           (literal-of "avant <b xmlns=\"http://www.w3.org/1999/xhtml\">Important</b> après"))))
  (testing "in exclusive canonical form: the namespaces it uses declared on the element using them, attributes sorted, empty elements written open and closed"
    (is (= (str "<ex:p xmlns:ex=\"http://example.org/\" a=\"1\" b=\"2 &amp; &quot;3&quot;\">x &lt; y<ex:br></ex:br></ex:p>")
           (:lit (literal-of "<ex:p b='2 &amp; \"3\"' a=\"1\">x &lt; y<ex:br/></ex:p>")))))
  (testing "a namespace an ancestor in the literal declares is not declared again"
    (is (= "<ex:a xmlns:ex=\"http://example.org/\"><ex:b>t</ex:b></ex:a>"
           (:lit (literal-of "<ex:a><ex:b>t</ex:b></ex:a>")))))
  (testing "a text-only literal is its text"
    (is (= "plain" (:lit (literal-of "plain"))))))

(deftest an-xml-literal-keeps-the-prefix-its-element-declares
  (testing "a namespace bound to two prefixes is written with the one the element declares (post-audit 2, D5 review)"
    (let [lit (fn [inner] (:lit (nth (first (rdfxml/parse (str "<rdf:RDF xmlns:rdf=\"" rdf "\" xmlns:ex=\"http://example.org/\" xmlns:h=\"http://h/\">"
                                                               "<rdf:Description rdf:about=\"http://example.org/x\"><ex:d rdf:parseType=\"Literal\">"
                                                               inner "</ex:d></rdf:Description></rdf:RDF>")))
                                     2)))]
      (is (= "<b xmlns=\"http://h/\">t</b>" (lit "<b xmlns=\"http://h/\">t</b>")) "the default it declares, not the root's h:")
      (is (= "<b><z:c xmlns:z=\"http://h/\"></z:c></b>" (lit "<b xmlns:z=\"http://h/\"><z:c/></b>")) "an inherited prefix it declared")
      (is (= "<b xmlns=\"http://q/\" xmlns:p=\"http://q/\" p:a=\"1\">t</b>" (lit "<b xmlns=\"http://q/\" xmlns:p=\"http://q/\" p:a=\"1\">t</b>"))
          "an attribute in a namespace keeps a prefix: unprefixed it would be in none"))))

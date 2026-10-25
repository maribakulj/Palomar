(ns palomar.plugins.lrmoo.ntriples-test
  "The RDF 1.1 N-Triples reader on RDF Palomar did not write (audit test 10):
   escaped literals, language tags, datatypes, blank nodes, comments, and
   located syntax errors."
  (:require [clojure.test :refer [deftest is testing]]
            [palomar.plugins.lrmoo.ntriples :as nt]))

(deftest reads-iris-literals-and-blank-nodes
  (is (= [[{:iri "http://ex/s"} {:iri "http://ex/p"} {:iri "http://ex/o"}]
          [{:bnode "b0"} {:iri "http://ex/p"} {:bnode "b1"}]]
         (nt/parse (str "<http://ex/s> <http://ex/p> <http://ex/o> .\n"
                        "_:b0 <http://ex/p> _:b1 .")))))

(deftest unescapes-literals
  (testing "ECHAR and UCHAR escapes become the characters they stand for"
    (is (= {:lit "a \"quoted\"\ttab\nline \\ é 𝄞"}
           (nth (first (nt/parse (str "<s:x> <p:y> \"a \\\"quoted\\\"\\ttab\\nline \\\\ "
                                      "\\u00E9 \\U0001D11E\" ."))) 2)))))

(deftest keeps-language-tags-and-datatypes
  (is (= {:lit "Les Misérables" :lang "fr-FR"}
         (nth (first (nt/parse "<s:x> <p:y> \"Les Misérables\"@fr-FR .")) 2)))
  (is (= {:lit "1862" :datatype "http://www.w3.org/2001/XMLSchema#gYear"}
         (nth (first (nt/parse "<s:x> <p:y> \"1862\"^^<http://www.w3.org/2001/XMLSchema#gYear> .")) 2))))

(deftest skips-comments-and-blank-lines
  (is (= 1 (count (nt/parse (str "# a comment\n\n"
                                 "   <s:x> <p:y> \"v\" . # trailing comment\n"
                                 "\t\n"))))))

(deftest a-blank-node-label-does-not-swallow-the-final-dot
  (is (= {:bnode "a.b"} (nth (first (nt/parse "<s:x> <p:y> _:a.b.")) 2))))

(deftest syntax-errors-are-located-never-guessed
  (doseq [[label bad col] [["no final dot"            "<s:x> <p:y> <o:z>"        18]
                           ["unterminated literal"    "<s:x> <p:y> \"open ."     13]
                           ["a literal as a subject"  "\"s\" <p:y> <o:z> ."      1]
                           ["a space inside an IRI"   "<s:x> <p:y> <o z> ."      15]
                           ["a bad escape"            "<s:x> <p:y> \"a\\qb\" ."  15]
                           ["a bad language tag"      "<s:x> <p:y> \"v\"@-x ."   16]
                           ["trailing garbage"        "<s:x> <p:y> <o:z> . x"    21]]]
    (testing label
      (let [e (try (nt/parse (str "<a:b> <c:d> <e:f> .\n" bad)) nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e))
        (is (= {:line 2 :column col} (ex-data e)))))))

(deftest the-grammar-edges-are-held
  (testing "an escape that is no Unicode scalar value is a located error, not a JVM exception (audit L7)"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not a Unicode scalar value"
                          (nt/parse "<http://ex/s> <http://ex/p> \"\\U00110000\" .")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not a Unicode scalar value"
                          (nt/parse "<http://ex/s> <http://ex/p> \"\\uD800\" ."))))
  (testing "an IRI must be absolute, and an escape cannot smuggle in a forbidden character"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not an absolute IRI"
                          (nt/parse "<s> <http://ex/p> <http://ex/o> .")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not allowed in an IRI"
                          (nt/parse "<http://ex/a\\u0020b> <http://ex/p> <http://ex/o> ."))))
  (testing "a blank node label may hold ':', a combining mark and a character beyond the BMP"
    (is (= [{:bnode "a:b\u0301\uD835\uDC00"} {:iri "http://ex/p"} {:bnode "c"}]
           (first (nt/parse "_:a:b\u0301\uD835\uDC00 <http://ex/p> _:c .")))))
  (testing "a lone CR ends a line"
    (is (= 2 (count (nt/parse "<http://ex/s> <http://ex/p> \"1\" .\r<http://ex/s> <http://ex/p> \"2\" .\r\n"))))))

(deftest term-key-names-a-node
  (is (= "http://ex/s" (nt/term-key {:iri "http://ex/s"})))
  (is (= "_:b0" (nt/term-key {:bnode "b0"})))
  (is (nil? (nt/term-key {:lit "x"}))))

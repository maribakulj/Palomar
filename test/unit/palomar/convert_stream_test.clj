(ns palomar.convert-stream-test
  "Tests for streaming conversion (WP-7 / DoD #6, `palomar.convert/convert-stream`):
   it produces the *same* output and loss report as batch `convert`, and folds the
   loss report in memory bounded by the corpus size — so a record stream of any
   length runs in constant working set. Exercised on the real 560-record BIB-R
   MARC corpus (and cycled for the scale checks)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.convert :as convert]
            [palomar.plugins.lido-schema :as lido-schema]
            [palomar.spokes :as spokes]))

(defn- slurp-gz [path]
  (with-open [in (java.util.zip.GZIPInputStream. (io/input-stream path))]
    (slurp in)))

(def ^:private marc-src (slurp-gz "test/fixtures/bibr-gold/bibrcat_marc21.xml.gz"))
(def ^:private raw (:records ((:importer (spokes/plugin :marc21)) {} marc-src)))

(defn- collect-stream [request]
  (let [out (atom [])
        res (convert/convert-stream request #(swap! out conj %))]
    (assoc res :output (str/join "\n" @out))))

(deftest streamed-conversion-equals-batch
  (testing "convert-stream emits the same documents and the same loss as batch convert"
    (let [batch  (convert/convert {:from :marc21 :to :ntriples :source marc-src})
          strm   (collect-stream {:from :marc21 :to :ntriples :records raw})]
      (is (= 560 (:records batch) (:records strm)))
      (is (= (:output batch) (:output strm)))                       ; identical serialisation
      (testing "the streamed loss report equals the batch one minus the batch-only :distinct-losses"
        (is (= (dissoc (:loss batch) :distinct-losses) (:loss strm)))
        (is (contains? (:loss batch) :distinct-losses))             ; batch has it
        (is (not (contains? (:loss strm) :distinct-losses)))))))    ; stream (bounded) does not

(deftest the-loss-accumulator-is-bounded-in-the-corpus-size
  (testing "five cycles of the corpus: the report's *size* is invariant, only counts scale"
    (let [noop  (fn [_])
          one   (convert/convert-stream {:from :marc21 :to :ntriples :records raw} noop)
          five  (convert/convert-stream {:from :marc21 :to :ntriples
                                         :records (take (* 5 560) (cycle raw))} noop)]
      (is (= (* 5 560) (:records five)))
      (testing "the distinct-field set (the accumulator's footprint) does not grow with N"
        (is (= (get-in one [:loss :source-fields]) (get-in five [:loss :source-fields])))
        (is (= (keys (get-in one [:loss :by-category])) (keys (get-in five [:loss :by-category])))))
      (testing "loss *counts* scale exactly with the corpus (same records repeated)"
        (is (= (* 5 (get-in one [:loss :total])) (get-in five [:loss :total])))))))

(deftest streams-a-large-corpus-to-completion
  (testing "a lazy stream far larger than any fixture runs through (held one record at a time)"
    (let [n   (* 20 560)                                            ; 11 200 records, lazily
          cnt (atom 0)
          res (convert/convert-stream {:from :marc21 :to :ntriples :with-proposals? true
                                       :records (take n (cycle raw))}
                                      (fn [_] (swap! cnt inc)))]
      (is (= n (:records res) @cnt))
      (is (pos? (get-in res [:loss :total]))))))

(deftest convert-stream-guards-unknown-formats
  (is (thrown? clojure.lang.ExceptionInfo
               (convert/convert-stream {:from :bogus :to :ntriples :records raw} (fn [_]))))
  (is (thrown? clojure.lang.ExceptionInfo
               (convert/convert-stream {:from :marc21 :to :bogus :records raw} (fn [_])))))

(deftest stream-source-yields-the-same-records-lazily
  (testing "lazy stream-source over a Reader equals the eager importer's records"
    (with-open [r (io/reader (java.util.zip.GZIPInputStream.
                              (io/input-stream "test/fixtures/bibr-gold/bibrcat_marc21.xml.gz")))]
      (let [streamed (convert/stream-source :marc21 {} r)]
        (is (not (vector? streamed)))                    ; a lazy seq, not a materialised vector
        (is (= raw (vec streamed)))))))                  ; same 560 records as the eager importer

(deftest streamable-sources-are-the-marc-family-and-lido
  (is (= #{:marc21 :intermarc :unimarc :lido} (set (convert/streamable-sources))))
  (is (convert/streamable? :marc21))
  (is (not (convert/streamable? :dc)))                   ; flat-shape spokes do not stream
  (is (thrown? clojure.lang.ExceptionInfo
               (convert/stream-source :dc {} (java.io.StringReader. "<x/>")))))

(deftest streamable-importers-emit-no-import-edge-loss
  ;; convert-stream folds only projection+export edges, omitting the importer's
  ;; ingest :diagnostics — sound ONLY because every streamable spoke's importer
  ;; reports no import-edge loss. Pin that premise: if a future streamable spoke
  ;; emits report-at-ingest loss, the streamed report would silently under-count,
  ;; and this test fails (add a fixture / fold the importer diagnostics then).
  (let [fixtures {:marc21    marc-src
                  :intermarc (slurp "test/fixtures/documentary/intermarc/sru/intermarcXchange/bib-flaubert-madame-bovary-start1-max30.xml")
                  :unimarc   (slurp "test/fixtures/documentary/unimarc/sru/bnf-sru-flaubert-unimarc.xml")}]
    (testing "the streamable set is the MARC family, and LIDO, whose stream carries its import-edge losses on its records (`a-lido-dump-streams-as-it-converts`)"
      (is (= #{:marc21 :intermarc :unimarc :lido} (set (convert/streamable-sources)))))
    (doseq [spoke (disj (set (convert/streamable-sources)) :lido)]
      (testing (str spoke " importer emits no import-edge loss")
        (is (empty? (:diagnostics ((:importer (spokes/plugin spoke)) {} (fixtures spoke)))))))))

(deftest convert-stream-over-a-real-reader-matches-batch
  (testing "streaming end-to-end from a real Reader (lazy pull-parse, not a pre-imported vector) equals batch"
    (with-open [r (io/reader (java.util.zip.GZIPInputStream.
                              (io/input-stream "test/fixtures/bibr-gold/bibrcat_marc21.xml.gz")))]
      (let [n     (atom 0)
            res   (convert/convert-stream {:from :marc21 :to :ntriples :with-proposals? true
                                           :records (convert/stream-source :marc21 {} r)}
                                          (fn [_] (swap! n inc)))
            batch (convert/convert {:from :marc21 :to :ntriples :with-proposals? true :source marc-src})]
        (is (= 560 (:records res) @n))
        (is (= (dissoc (:loss batch) :distinct-losses) (:loss res)))))))   ; same loss via the Reader path

(deftest convert-stream-pulls-a-large-corpus-from-a-real-reader
  (testing "a flat dump larger than any fixture streams from a real Reader (lazy parse at scale)"
    (let [blocks (re-seq #"(?s)<marc:record>.*?</marc:record>" marc-src)
          path   (str (System/getProperty "java.io.tmpdir") "/palomar-bigmarc-" (System/nanoTime) ".xml")]
      (try
        (with-open [w (io/writer path)]
          (.write w "<marc:collection xmlns:marc=\"http://www.loc.gov/MARC21/slim\">")
          (dotimes [_ 10] (doseq [b blocks] (.write w b)))            ; 10 × 560 = 5600 records
          (.write w "</marc:collection>"))
        (with-open [r (io/reader path)]
          (let [n   (atom 0)
                res (convert/convert-stream {:from :marc21 :to :ntriples :with-proposals? true
                                             :records (convert/stream-source :marc21 {} r)}
                                            (fn [_] (swap! n inc)))]
            (is (= 5600 (:records res) @n))))
        (finally (.delete (java.io.File. path)))))))

(deftest an-sru-page-streams-like-a-flat-dump
  (testing "nested SRU records stream (audit S30): the same records as the batch parse"
    (doseq [[from path] [[:unimarc "test/fixtures/documentary/unimarc/sru/bnf-sru-verne-unimarc.xml"]
                         [:intermarc "test/fixtures/documentary/intermarc/sru/intermarcXchange/bib-flaubert-madame-bovary-start1-max30.xml"]]]
      (let [batch    (:records (convert/to-wemi from {} (slurp path)))
            streamed (with-open [r (io/reader path)]
                       (doall (map :id (convert/stream-source from {} r))))]
        (is (pos? (count streamed)) (str from " streamed records"))
        (is (= (map :id batch) streamed) (str from " streamed = batch")))))
  (testing "a streamed record is identical to the batch-parsed one, fragments included"
    (let [path  "test/fixtures/documentary/unimarc/sru/bnf-sru-verne-unimarc.xml"
          plug  (spokes/plugin :unimarc)
          batch (:records ((:importer plug) {} (slurp path)))
          strm  (with-open [r (io/reader path)] (doall (convert/stream-source :unimarc {} r)))]
      (is (= batch strm)))))

(deftest a-marc21-sru-envelope-is-not-taken-for-a-record
  (testing "zs:record wraps the MARC21slim record: both paths read the inner one, with its fields"
    (let [xml   (str "<zs:searchRetrieveResponse xmlns:zs=\"http://docs.oasis-open.org/ns/search-ws/sruResponse\">"
                     "<zs:records><zs:record><zs:recordData>"
                     "<record xmlns=\"http://www.loc.gov/MARC21/slim\">"
                     "<controlfield tag=\"001\">123</controlfield>"
                     "<datafield tag=\"245\" ind1=\"0\" ind2=\"0\"><subfield code=\"a\">T</subfield></datafield>"
                     "</record></zs:recordData></zs:record></zs:records></zs:searchRetrieveResponse>")
          batch (:records ((:importer (spokes/plugin :marc21)) {} xml))
          strm  (doall (convert/stream-source :marc21 {} (java.io.StringReader. xml)))]
      (is (= [:marc/r123] (map :id batch) (map :id strm)))
      (is (= batch strm))
      (is (seq (:assertions (first strm)))))))

(deftest a-lido-dump-streams-as-it-converts
  (testing "a LIDO lidoWrap streams record by record, to the same output and loss report as batch (post-audit 5, G7)"
    (let [src (slurp "test/fixtures/documentary/lido/staedel-lido-wrap.xml")]
      (doseq [to [:ntriples :linked-art :marc21-object :dc]]
        (let [batch (convert/convert {:from :lido :to to :source src})
              strm  (with-open [r (java.io.StringReader. src)]
                      (collect-stream {:from :lido :to to :records (convert/stream-source :lido {} r)}))]
          (is (= (:records batch) (:records strm)) (str to))
          ;; a stream emits its documents bare; the caller wraps them once
          (is (= (:output (convert/export-records to (:records (convert/to-wemi :lido {} src)) {:bare? true}))
                 (:output strm))
              (str to))
          (is (= (dissoc (:loss batch) :distinct-losses) (:loss strm)) (str to))))))
  (testing "it is lazy: the first record is read before the rest of the dump is"
    (let [src (slurp "test/fixtures/documentary/lido/staedel-lido-wrap.xml")
          cut (subs src 0 (+ (str/index-of src "</lido:lido>") (count "</lido:lido>")))]
      (with-open [r (java.io.StringReader. (str cut "<lido:lido><broken"))]
        (is (= 1 (count (take 1 (convert/stream-source :lido {} r)))))))))

(deftest a-lido-stream-reads-what-batch-reads
  (testing "only the wrap's direct children are records, at the batch's XPaths (G7 review)"
    (let [r   (fn [id] (str "<lido:lido><lido:lidoRecID>" id "</lido:lidoRecID></lido:lido>"))
          doc (str "<lido:lidoWrap xmlns:lido=\"http://www.lido-schema.org\" xmlns:x=\"urn:x\">"
                   "<x:ext>" (r "NESTED") "</x:ext>" (r "A") "<!-- c -->" (r "B") "</lido:lidoWrap>")
          batch (:records ((:importer (spokes/plugin :lido)) {:validate? false} doc))
          strm  (with-open [rd (java.io.StringReader. doc)] (doall (convert/stream-source :lido {:validate? false} rd)))
          sel   (fn [rec] (set (mapcat #(map (comp :value :selector) (get-in % [:provenance :evidence])) (:assertions rec))))]
      (is (= ["A" "B"] (map :source batch) (map :source strm)))
      (is (= (map sel batch) (map sel strm)) "the same XPaths")))
  (testing "a record's own schemaLocation wins over its wrap's, as in batch"
    (is (= (lido-schema/errors (str "<lido:lido xmlns:lido=\"http://www.lido-schema.org\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\""
                                    " xsi:schemaLocation=\"http://www.lido-schema.org http://www.lido-schema.org/schema/v1.0/lido-v1.0.xsd\">"
                                    "<lido:lidoRecID>V</lido:lidoRecID></lido:lido>")
                               :v1.1)
           [(first (lido-schema/errors (str "<lido:lido xmlns:lido=\"http://www.lido-schema.org\"><lido:lidoRecID>V</lido:lidoRecID></lido:lido>") :v1.0))]))))

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
          res (convert/convert-stream {:from :marc21 :to :ntriples
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

(deftest streamable-sources-are-the-marc-family
  (is (= #{:marc21 :intermarc :unimarc} (set (convert/streamable-sources))))
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
    (testing "the streamable set is exactly the MARC family (add a fixture above if it grows)"
      (is (= #{:marc21 :intermarc :unimarc} (set (convert/streamable-sources)))))
    (doseq [spoke (convert/streamable-sources)]
      (testing (str spoke " importer emits no import-edge loss")
        (is (empty? (:diagnostics ((:importer (spokes/plugin spoke)) {} (fixtures spoke)))))))))

(deftest convert-stream-over-a-real-reader-matches-batch
  (testing "streaming end-to-end from a real Reader (lazy pull-parse, not a pre-imported vector) equals batch"
    (with-open [r (io/reader (java.util.zip.GZIPInputStream.
                              (io/input-stream "test/fixtures/bibr-gold/bibrcat_marc21.xml.gz")))]
      (let [n     (atom 0)
            res   (convert/convert-stream {:from :marc21 :to :ntriples
                                           :records (convert/stream-source :marc21 {} r)}
                                          (fn [_] (swap! n inc)))
            batch (convert/convert {:from :marc21 :to :ntriples :source marc-src})]
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
                res (convert/convert-stream {:from :marc21 :to :ntriples
                                             :records (convert/stream-source :marc21 {} r)}
                                            (fn [_] (swap! n inc)))]
            (is (= 5600 (:records res) @n))))
        (finally (.delete (java.io.File. path)))))))

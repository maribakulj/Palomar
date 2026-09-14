(ns palomar.xml-test
  "Pins the input-hardening posture of `palomar.xml` (WP-9 security): every XML
   importer parses through this façade, which refuses DTDs (`:support-dtd false`).
   That single policy closes two real holes verified against `clojure.data.xml`:

   - the `billion laughs` entity-expansion DoS (the baseline parser expands a
     tiny nested-entity payload unbounded — the JDK limit does not fire), and
   - any DTD-borne XXE (external entities are already unresolved by data.xml, but
     a DTD is their only vehicle, so refusing it removes the surface entirely).

   The throw happens while the lazy tree is *realised*, so every assertion below
   forces the whole tree (`realize`) rather than trusting the bare parse call —
   a parse that is never walked never advances past the `<!DOCTYPE>`."
  (:require [clojure.test :refer [deftest is testing]]
            [palomar.plugins.marc21 :as marc21]
            [palomar.xml :as rx]))

(defn- realize
  "Recursively force every lazy `:content` node so a parse error surfaces."
  [x]
  (when (map? x) (doseq [c (:content x)] (realize c)))
  x)

(defn- text-length
  "Total length of all text nodes in the realised tree (detects expansion)."
  [x]
  (cond (string? x) (count x)
        (map? x)    (reduce + 0 (map text-length (:content x)))
        :else       0))

;; A four-level `billion laughs`: &d; -> 10 &c; -> 100 &b; -> 1000 &a; -> 10 000
;; 'A's. The baseline parser expands this fully (verified 10 000 chars, no JDK
;; limit). A real attack nests deeper for exponential blow-up; four levels is
;; enough to prove expansion is *bounded by the document*, not refused.
(def ^:private billion-laughs
  (str "<?xml version=\"1.0\"?>"
       "<!DOCTYPE x ["
       "<!ENTITY a \"AAAAAAAAAA\">"
       "<!ENTITY b \"&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;\">"
       "<!ENTITY c \"&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;\">"
       "<!ENTITY d \"&c;&c;&c;&c;&c;&c;&c;&c;&c;&c;\">"
       "]><x>&d;</x>"))

;; An external SYSTEM entity — the classic XXE file-disclosure vehicle.
(def ^:private xxe
  (str "<?xml version=\"1.0\"?>"
       "<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/hostname\">]>"
       "<x>&e;</x>"))

(deftest parse-str-handles-benign-xml
  (testing "a DTD-free document parses normally (no regression for real input)"
    (let [root (realize (rx/parse-str "<a><b>hi</b></a>"))]
      (is (= :a (:tag root)))
      (is (= 2 (text-length root))))))                       ; "hi"

(deftest parse-str-refuses-billion-laughs
  (testing "the entity-expansion DoS payload is rejected, not expanded"
    (is (thrown? javax.xml.stream.XMLStreamException
                 (realize (rx/parse-str billion-laughs))))))

(deftest parse-str-refuses-external-entities
  (testing "a DTD-borne external-entity (XXE) payload is rejected"
    (is (thrown? javax.xml.stream.XMLStreamException
                 (realize (rx/parse-str xxe))))))

(deftest streaming-parse-refuses-dtds
  (testing "the lazy `parse` path (WP-7 streaming) refuses DTDs just like parse-str"
    (is (thrown? javax.xml.stream.XMLStreamException
                 (realize (rx/parse (java.io.StringReader. billion-laughs)))))
    (is (thrown? javax.xml.stream.XMLStreamException
                 (realize (rx/parse (java.io.StringReader. xxe)))))))

(deftest importers-reject-hostile-xml-end-to-end
  (testing "a real importer (MARC21 eager + streaming) rejects a DTD payload"
    (is (thrown? javax.xml.stream.XMLStreamException
                 (marc21/ingest billion-laughs {})))
    (is (thrown? javax.xml.stream.XMLStreamException
                 (doall (marc21/stream {} (java.io.StringReader. billion-laughs)))))))

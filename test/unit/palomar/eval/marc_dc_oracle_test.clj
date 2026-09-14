(ns palomar.eval.marc-dc-oracle-test
  "Oracle eval (DoD #1, partial): measure Palomar's `convert :marc21 → :dc` against
   the Library of Congress' own reference crosswalk — `MARC21slim2OAIDC.xsl` — run
   as an *independent oracle* over the same input. Not a unit test of our code: a
   differential against a third party's published mapping, so the coverage claim is
   external, not self-asserted.

   The oracle is the real LoC stylesheet (`test/fixtures/conformance/crosswalks/`),
   run through the JDK's XSLT 1.0 engine. Its one absolute `xsl:import`
   (`http://www.loc.gov/.../MARC21slimUtils.xsl`) is 403 in this sandbox, so a
   `URIResolver` redirects that import to the locally-vendored copy — the only
   concession to offline; the transform itself is the LoC's, unmodified.

   What the differential shows on the LoC sample collection (2 records):

   - **Shared documentary spine (5 of the oracle's 9 element types).** Both emit
     `title creator date description identifier`. On `date` the two crosswalks agree
     *exactly* (same values). title/creator agree in count.
   - **The gap is the coded / controlled axes (4 types): `language publisher
     subject type`.** This is not an accident of coverage — it is ADR 0003's scope:
     the canonical floor models *transcribed documentary statements*, not coded
     fields (`language` ← 008/35-37, `type` ← leader 06/07), controlled vocabularies
     (`subject` ← 6xx), or a publisher *role* the single `:canon/agent` cannot carry
     distinctly. Palomar drops these at import by design; the oracle decodes them.
   - **Identifier provenance diverges (and disjointly).** Palomar keys
     `dc:identifier` on the bibliographic numbers (010 LCCN, 035 control#); the
     oracle keys it on the 856 access URL and 020 ISBN. Palomar deliberately routes
     856 $u to `:canon/digital-object` (an access surrogate), not to an identifier —
     so the two id sets are *disjoint*. Neither is wrong; MARC has no single
     \"the identifier\", and the eval records the divergence rather than hiding it.
   - **Subfield discipline diverges.** The oracle's `title` keeps the GMD
     `[sound recording]` ($h) and its `creator` keeps the `prf` relator and dates
     ($d $4); Palomar strips to the proper element / controlled name. So Palomar is
     *not* a strict subset — it is cleaner on some fields, thinner on others.

   Honest headline: **5/9 element-type coverage vs the LoC oracle**, with the missing
   4 being the coded/controlled axes the floor does not model — measured, not
   claimed. Closing them is future work (a controlled-vocab / fixed-field layer),
   not a silent omission."
  (:require [clojure.data.xml :as xml]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.convert :as convert]
            [palomar.eval.loc-xslt :as loc-xslt]))

(def ^:private marc-source
  (slurp "test/fixtures/documentary/marc21/marcxml/loc_collection.xml"))

(def ^:private dcmes
  "The 15 Dublin Core Metadata Element Set local names (DCMES 1.1)."
  #{"title" "creator" "subject" "description" "publisher" "contributor" "date"
    "type" "format" "identifier" "source" "language" "relation" "coverage" "rights"})

;; --- the LoC stylesheet as an offline oracle (see palomar.eval.loc-xslt) ----

(defn- run-oracle [marcxml]
  (loc-xslt/run-stylesheet "loc-MARC21slim2OAIDC.xsl" marcxml))

;; --- DCMES extraction, sound across single- and multi-root output ----------

(defn- elements [tree]
  (when (map? tree) (cons tree (mapcat elements (:content tree)))))

(defn- text-of [el]
  (str/trim (str/join (filter string? (:content el)))))

(defn- dc-bag
  "All DCMES elements in `xml` as `[{:tag :value} …]`. Wraps in a synthetic root
   (after dropping any XML declaration) so it parses whether the producer emitted a
   single namespaced root (the oracle's `oai_dc:dcCollection`) or several
   `<metadata>` roots (Palomar's per-record output)."
  [xml]
  (->> (xml/parse-str (str "<wrap>" (str/replace xml #"(?s)<\?xml.*?\?>" "") "</wrap>"))
       elements
       (keep (fn [el]
               (let [t (some-> (:tag el) name)]
                 (when (dcmes t) {:tag t :value (text-of el)}))))))

(defn- types [bag] (set (map :tag bag)))
(defn- values [bag tag] (set (map :value (filter #(= tag (:tag %)) bag))))
(defn- freq [bag] (frequencies (map :tag bag)))

(def ^:private oracle  (delay (dc-bag (run-oracle marc-source))))
(def ^:private palomar (delay (dc-bag (:output (convert/convert {:from :marc21 :to :dc
                                                                 :source marc-source})))))

;; --- the differential ------------------------------------------------------

(deftest the-oracle-actually-runs
  (testing "the LoC stylesheet transforms (URIResolver wired) — the eval is not vacuous"
    (is (= {"creator" 2 "date" 2 "description" 7 "identifier" 2 "language" 2
            "publisher" 2 "subject" 6 "title" 2 "type" 2}
           (freq @oracle))
        "the LoC oracle emits its 9 known DC element types over the sample collection")))

(deftest shared-documentary-spine-five-of-nine
  (testing "Palomar and the LoC oracle share exactly the documentary spine"
    (is (= #{"title" "creator" "date" "description" "identifier"}
           (set/intersection (types @oracle) (types @palomar)))))
  (testing "Palomar invents no DC element the oracle does not also emit (it is a projection, not an embellishment)"
    (is (empty? (set/difference (types @palomar) (types @oracle)))))
  (testing "honest headline: 5 of the oracle's 9 element types are covered"
    (is (= 9 (count (types @oracle))))
    (is (= 5 (count (set/intersection (types @oracle) (types @palomar)))))))

(deftest the-gap-is-the-coded-and-controlled-axes
  (testing "the 4 uncovered types are precisely language/publisher/subject/type — the floor's deliberate scope (ADR 0003)"
    (is (= #{"language" "publisher" "subject" "type"}
           (set/difference (types @oracle) (types @palomar))))))

(deftest date-agrees-exactly
  (testing "on date the two crosswalks fully concur — same values, both records"
    (is (= (values @oracle "date") (values @palomar "date")))
    (is (= #{"[1957?]" "1994-"} (values @palomar "date")))))

(deftest title-and-creator-agree-in-count-but-not-subfield-discipline
  (testing "both emit one title and one creator per record (2 each)"
    (is (= 2 (get (freq @oracle) "title") (get (freq @palomar) "title")))
    (is (= 2 (get (freq @oracle) "creator") (get (freq @palomar) "creator"))))
  (testing "the oracle over-includes: GMD in the title, the relator/date in the creator"
    (is (some #(str/includes? % "[sound recording]") (values @oracle "title")))
    (is (some #(str/includes? % "prf") (values @oracle "creator"))))
  (testing "Palomar strips to the proper title and the controlled name — cleaner, not a superset"
    (is (not-any? #(str/includes? % "[sound recording]") (values @palomar "title")))
    (is (not-any? #(str/includes? % "prf") (values @palomar "creator")))
    (is (contains? (values @palomar "title") "The Great Ray Charles"))))

(deftest identifier-provenance-diverges-disjointly
  (testing "both emit identifiers, but from different MARC fields — the id sets are disjoint"
    (is (seq (values @oracle "identifier")))
    (is (seq (values @palomar "identifier")))
    (is (empty? (set/intersection (values @oracle "identifier") (values @palomar "identifier")))))
  (testing "Palomar keys on the bibliographic numbers (010 LCCN, 035 control#)"
    (is (contains? (values @palomar "identifier") "91758335"))             ; 010 LCCN, trimmed
    (is (some #(str/includes? % "OCoLC") (values @palomar "identifier")))) ; 035 control#
  (testing "the oracle keys on the 856 access URL — which Palomar routes to :canon/digital-object, not an identifier"
    (is (some #(str/includes? % "whitehouse.gov") (values @oracle "identifier")))
    (is (not-any? #(str/includes? % "http") (values @palomar "identifier")))))

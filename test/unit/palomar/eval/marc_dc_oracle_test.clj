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

   - **Shared documentary spine (4 of the oracle's 9 element types).** Both emit
     `title date description identifier`. On `date` the two crosswalks agree
     *exactly* (same values). title agrees in count.
   - **The agents agree, their role does not (deliberately).** Both sample records
     credit their agent only in an *added entry* (700 performer `prf`, 710), with no
     1XX main entry. The LoC stylesheet flattens every 1XX/7XX to `dc:creator`;
     Palomar keeps the MARC responsibility (`:canon/role`) and emits added entries as
     `dc:contributor` (DCMES: \"an entity responsible for making contributions\").
     Same two agents, same count, a different element — recorded, not hidden.
   - **The gap is the coded / controlled axes (4 types): `language publisher
     subject type`.** This is not an accident of coverage — it is ADR 0003's scope:
     the canonical floor models *transcribed documentary statements*, not coded
     fields (`language` ← 008/35-37, `type` ← leader 06/07), controlled vocabularies
     (`subject` ← 6xx), or the 260 $b publisher, which the MARC21 spoke does not map
     yet. Palomar drops these at import by design; the oracle decodes them.
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

   Honest headline: **4/9 element types shared exactly with the LoC oracle, plus its
   `creator` agents emitted as `dc:contributor`** — the missing 4 being the
   coded/controlled axes the floor does not model. Measured, not claimed. (Before
   agent roles, 2026-10-25, the agents also came out as `dc:creator`: 5/9.)
   Closing the gap is future work (a controlled-vocab / fixed-field layer), not a
   silent omission."
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
    (is (= #{"title" "date" "description" "identifier" "publisher"}
           (set/intersection (types @oracle) (types @palomar)))))
  (testing "the only element Palomar emits that the oracle does not is contributor — the oracle's creators"
    (is (= #{"contributor"} (set/difference (types @palomar) (types @oracle))))
    (is (every? (fn [c] (some #(str/starts-with? % c) (values @oracle "creator")))
                (values @palomar "contributor"))
        "each Palomar contributor is one of the oracle's creators (oracle keeps $d/$4 on top)"))
  (testing "each Palomar publisher (260 $b) is in an oracle publisher (which adds the place, $a)"
    (is (every? (fn [p] (some #(str/includes? % p) (values @oracle "publisher")))
                (values @palomar "publisher"))
        (str (values @palomar "publisher") " vs " (values @oracle "publisher"))))
  (testing "honest headline: 5 of the oracle's 9 element types are shared exactly"
    (is (= 9 (count (types @oracle))))
    (is (= 5 (count (set/intersection (types @oracle) (types @palomar)))))))

(deftest the-gap-is-the-coded-and-controlled-axes
  (testing "the uncovered types are language/subject/type — the floor's deliberate scope (ADR 0003) —
            plus creator, whose agents Palomar emits as contributor"
    (is (= #{"creator" "language" "subject" "type"}
           (set/difference (types @oracle) (types @palomar))))))

(deftest date-agrees-exactly
  (testing "on date the two crosswalks fully concur — same values, both records"
    (is (= (values @oracle "date") (values @palomar "date")))
    (is (= #{"[1957?]" "1994-"} (values @palomar "date")))))

(deftest title-and-agent-agree-in-count-but-not-subfield-discipline
  (testing "both emit one title and one agent per record (2 each): oracle creator, Palomar contributor"
    (is (= 2 (get (freq @oracle) "title") (get (freq @palomar) "title")))
    (is (= 2 (get (freq @oracle) "creator") (get (freq @palomar) "contributor"))))
  (testing "the oracle over-includes: GMD in the title, the relator/date in the creator"
    (is (some #(str/includes? % "[sound recording]") (values @oracle "title")))
    (is (some #(str/includes? % "prf") (values @oracle "creator"))))
  (testing "Palomar strips to the proper title and the controlled name — cleaner, not a superset"
    (is (not-any? #(str/includes? % "[sound recording]") (values @palomar "title")))
    (is (not-any? #(str/includes? % "prf") (values @palomar "contributor")))
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

(ns palomar.fidelity-test
  "The occurrence-level fidelity audit (audit S11/S12, test 15): every value a
   conversion does not write must be reported, and a silent omission is caught."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.convert :as convert]
            [palomar.fidelity :as fidelity]
            [palomar.plugins.export-coverage :as cov]
            [palomar.plugins.lrmoo.export :as export]))

(def ^:private fixtures
  {:intermarc    "test/fixtures/documentary/intermarc/sru/intermarcXchange/bib-flaubert-madame-bovary-start1-max30.xml"
   :intermarc-ng "test/fixtures/documentary/intermarc-ng/examples/fleurs-du-mal-oemi.xml"
   :unimarc      "test/fixtures/documentary/unimarc/sru/bnf-sru-verne-unimarc.xml"
   :dc           "test/fixtures/documentary/dublin-core/w3c_dc_example1.xml"
   :marc21       "test/fixtures/documentary/marc21/marcxml/loc_collection.xml"
   :mods         "test/fixtures/documentary/mods/loc_mods_book.xml"
   :iiif         "test/fixtures/documentary/iiif/manifest_book_simple.json"
   :ead          "test/fixtures/documentary/ead/ead3/ead3_multi_level_optimum.xml"
   :eac-cpf      "test/fixtures/documentary/eac-cpf/example_eac_cpf.xml"
   :lido         "test/fixtures/documentary/lido/staedel-lido-wrap.xml"})

(defn- audit [from to]
  (fidelity/audit {:from from :to to :source (slurp (fixtures from))
                   :opts (if (= from :dc) {:record-id :dc/x} {})}))

(deftest every-conversion-accounts-for-every-value
  (testing "for each spoke and each decodable target, every absent value is reported"
    (doseq [from (keys fixtures)
            to   (sort (fidelity/decodable-targets))
            :when (convert/accepts? to (convert/source-resource-type from))]
      (let [{:keys [checked present unaccounted]} (audit from to)]
        (is (pos? checked) (str from " -> " to " checked something"))
        (is (empty? unaccounted)
            (str from " -> " to ": silent loss of " (pr-str (take 5 unaccounted))))
        (is (<= present checked))))))

(defn- iiif-variant
  "A one-canvas manifest with `extra` top-level keys, `canvas-extra` Canvas
   keys and `body-extra` Image body keys."
  [extra canvas-extra body-extra]
  (json/write-str
   (merge {"@context" "http://iiif.io/api/presentation/3/context.json"
           "id" "https://e.org/m/manifest.json" "type" "Manifest" "label" {"en" ["L"]}
           "items" [(merge {"id" "https://e.org/m/canvas/1" "type" "Canvas" "height" 10 "width" 10
                            "items" [{"id" "https://e.org/m/canvas/1/p" "type" "AnnotationPage"
                                      "items" [{"id" "https://e.org/m/canvas/1/a" "type" "Annotation" "motivation" "painting"
                                                "target" "https://e.org/m/canvas/1"
                                                "body" (merge {"id" "https://e.org/i1.jpg" "type" "Image"} body-extra)}]}]}
                           canvas-extra)]}
          extra)))

(def ^:private rights-manifests
  "The rights fixture and variants of it an ordinary IIIF source gives (M1 review)."
  {"the fixture"                 (slurp "test/fixtures/documentary/iiif/manifest_rights.json")
   "non-URI rights"              (iiif-variant {"rights" "All rights reserved"} {"rights" "Some text"} {})
   "empty rights"                (iiif-variant {"rights" ""} {"rights" ""} {})
   "body and Canvas rights"      (iiif-variant {} {"rights" "http://rightsstatements.org/vocab/InC/1.0/"}
                                               {"rights" "http://creativecommons.org/publicdomain/zero/1.0/"})
   "provider without id"         (iiif-variant {"provider" [{"type" "Agent" "label" {"en" ["X"]}}]} {} {})
   "mixed-language statement"    (iiif-variant {"requiredStatement" {"label" {"en" ["Attribution"]} "value" {"fr" ["Fourni par X"]}}} {} {})
   "statement with no label"     (iiif-variant {"requiredStatement" {"value" {"en" ["Provided by: X"]}}} {} {})
   "label holding a colon"       (iiif-variant {"requiredStatement" {"label" {"en" ["Credit: line"]} "value" {"en" ["X"]}}} {} {})})

(deftest rights-attribution-and-provider-are-accounted-for
  (testing "manifests with rights, a requiredStatement, a provider and Canvas or body rights, valid or not: every
            value of every conversion is written or reported (M1)"
    (doseq [[label src] rights-manifests
            to (sort (fidelity/decodable-targets))
            :when (convert/accepts? to (convert/source-resource-type :iiif))]
      (let [{:keys [checked unaccounted]} (fidelity/audit {:from :iiif :to to :source src})]
        (is (pos? checked) (str label ": iiif -> " to " checked something"))
        (is (empty? unaccounted) (str label ": iiif -> " to ": silent loss of " (pr-str (take 5 unaccounted))))))))

(deftest a-required-statement-written-apart-is-caught
  (testing "an export that drops the requiredStatement's value is caught although the same string is written as the
            provider's name, and its label as well (M1 review, p5)"
    (let [src    (iiif-variant {"requiredStatement" {"label" {"en" ["Attribution"]} "value" {"en" ["Example Herbarium"]}}
                                "provider" [{"id" "https://example.org/about" "type" "Agent" "label" {"en" ["Example Herbarium"]}}]}
                               {} {})
          real   @#'convert/exporters
          broken (update real :iiif assoc :render
                         (fn [r] (json/write-str (update (json/read-str ((get-in real [:iiif :render]) r))
                                                         "requiredStatement" dissoc "value"))))
          {:keys [unaccounted]} (with-redefs-fn {#'convert/exporters broken}
                                  #(fidelity/audit {:from :iiif :to :iiif :source src}))]
      (is (seq unaccounted))
      (is (every? #(contains? #{:canon/note :iiif/required-statement :canon/note-label :iiif/required-statement-label}
                              (:predicate %))
                  unaccounted)))))

(defn- caught?
  "The audit of `from` -> `to` on `src`, with the `to` exporter's output
   stripped of the matches of `re`, finds something unaccounted."
  [from to src re]
  (let [real   @#'convert/exporters
        broken (update real to assoc :render (fn [r] (str/replace ((get-in real [to :render]) r) re "")))
        opts   (if (= from :dc) {:record-id :dc/x} {})]
    (seq (:unaccounted (with-redefs-fn {#'convert/exporters broken}
                         #(fidelity/audit {:from from :to to :source src :opts opts}))))))

(deftest a-collapse-loss-does-not-hide-a-dropped-native
  (testing "a value-less loss on a canonical field (agents collapsed, a role not carried, relation/source) does not
            account for a native value an exporter silently drops (M1 review 2, repro1; DC writes dc:source as
            dc:relation, so dropping dc:relation drops both)"
    (let [dc   (slurp (fixtures :dc))
          mods (slurp (fixtures :mods))
          marc (slurp (fixtures :marc21))
          im   (slurp (fixtures :intermarc))]
      (doseq [[label from to src re]
              [["DC -> MARC21, the 100/700 fields" :dc :marc21 dc #"(?s)<datafield tag=\"[17]00\".*?</datafield>"]
               ["DC -> MODS, every name" :dc :mods dc #"(?s)<name\b.*?</name>"]
               ["DC -> DC, dc:relation" :dc :dc dc #"(?s)<dc:relation[^>]*>.*?</dc:relation>"]
               ["MODS -> DC, dc:creator" :mods :dc mods #"(?s)<dc:creator[^>]*>.*?</dc:creator>"]
               ["MODS -> MARC21, 260/264" :mods :marc21 mods #"(?s)<datafield tag=\"26[04]\".*?</datafield>"]
               ["MARC21 -> MARC21, 700" :marc21 :marc21 marc #"(?s)<datafield tag=\"700\".*?</datafield>"]
               ["MARC21 -> MARC21, 010" :marc21 :marc21 marc #"(?s)<datafield tag=\"010\".*?</datafield>"]
               ["INTERMARC -> MODS, every name" :intermarc :mods im #"(?s)<name\b.*?</name>"]]]
        (testing label (is (caught? from to src re)))))))

(defn- iiif-broken-audit
  "The iiif -> iiif audit of `src` with the exporter's manifest passed through `f`."
  [src f]
  (let [real   @#'convert/exporters
        broken (update real :iiif assoc :render (fn [r] (json/write-str (f (json/read-str ((get-in real [:iiif :render]) r))))))]
    (:unaccounted (with-redefs-fn {#'convert/exporters broken} #(fidelity/audit {:from :iiif :to :iiif :source src})))))

(deftest a-notes-loss-does-not-hide-a-title-s-language
  (testing "a title's language rewritten away is caught although the summary's notes are reported (M1 review 2, repro3)"
    (let [src (iiif-variant {"label" {"fr" ["Titre"]} "summary" {"en" ["S"]}} {} {})]
      (is (seq (iiif-broken-audit src #(assoc % "label" {"none" ["Titre"]})))))))

(deftest a-required-statement-is-checked-beside-a-summary
  (testing "with a summary present, an attribution dropped, cut to its label or moved into the summary is caught
            (M1 review 2, repro2)"
    (let [src (iiif-variant {"requiredStatement" {"label" {"en" ["Attribution"]} "value" {"en" ["Example Herbarium"]}}
                             "summary" {"en" ["A summary"]}} {} {})]
      (is (empty? (:unaccounted (fidelity/audit {:from :iiif :to :iiif :source src}))) "the honest export accounts for all")
      (is (seq (iiif-broken-audit src #(dissoc % "requiredStatement"))) "dropped")
      (is (seq (iiif-broken-audit src #(update % "requiredStatement" dissoc "value"))) "only the label kept")
      (is (seq (iiif-broken-audit src #(-> % (dissoc "requiredStatement")
                                           (assoc "summary" {"en" ["A summary" "Attribution" "Example Herbarium"]}))))
          "label and value written into the summary"))))

(deftest a-silent-omission-is-caught
  (testing "an exporter that drops one note without reporting it fails the audit (audit test 15)"
    (let [real   @#'convert/exporters
          broken (update real :dc assoc :render
                         (fn [r] (-> ((get-in real [:dc :render]) r)
                                     (str/replace #"(?s)<dc:description[^>]*>[^<]*</dc:description>" ""))))
          {:keys [unaccounted]} (with-redefs-fn {#'convert/exporters broken}
                                  #(audit :dc :dc))]
      (is (seq unaccounted))
      (is (every? #(contains? #{:canon/note :dc/description} (:predicate %)) unaccounted)))))

(deftest turtle-is-reported-as-unchecked
  (is (:unchecked (audit :dc :turtle))))

(deftest the-prov-audit-checks-out-of-force-assertions-too
  (testing "PROV must record a rejected claim, so the audit counts it and finds it present"
    (let [rec      (first (:records (convert/to-wemi :dc {:record-id :dc/x} (slurp (fixtures :dc)))))
          rejected (update rec :assertions
                           (partial mapv #(if (= :dc/date (:predicate %)) (assoc % :status :rejected) %)))
          strict   (fidelity/audit-record :prov rejected)
          plain    (fidelity/audit-record :jsonld rejected)]
      (is (empty? (:unaccounted strict)))
      (is (> (:checked strict) (:checked plain))))))

(deftest every-relation-survives-in-the-rdf-outputs
  (testing "for each spoke and each N-Triples-family target, every WEMI link is written or reported (ADR 0033)"
    (doseq [from (keys fixtures)
            to   (sort (fidelity/relation-targets))]
      (let [{:keys [checked unaccounted]} (:relations (audit from to))]
        ;; an EAC-CPF agent projects no relation yet (ADR 0045): they are reported at import
        (is (or (pos? checked) (= :eac-cpf from)) (str from " -> " to " checked some relation"))
        (is (empty? unaccounted)
            (str from " -> " to ": silent relation loss " (pr-str (take 3 unaccounted))))))))

(deftest a-dropped-relation-is-caught
  (testing "an export that keeps every title but drops the Work→Expression link fails the relation audit"
    (let [real   @#'convert/exporters
          broken (update real :ntriples assoc :render
                         (fn [r] (->> (str/split-lines ((get-in real [:ntriples :render]) r))
                                      (remove #(str/includes? % "R3_is_realised_in"))
                                      (str/join "\n"))))
          {:keys [relations unaccounted]} (with-redefs-fn {#'convert/exporters broken}
                                            #(audit :intermarc :ntriples))]
      (is (empty? unaccounted) "no string value is lost: the string check alone sees nothing")
      (is (seq (:unaccounted relations)))
      (is (every? #(= :lrmoo/R3_is_realised_in (:predicate %)) (:unaccounted relations))))))

(deftest the-pure-crm-export-carries-relations-by-their-super-property
  (let [{:keys [checked present unaccounted]} (:relations (audit :intermarc :crm-only))]
    (is (pos? checked))
    (is (= checked present) "R3/R4/R7 are written as P130/P165/P128, which counts as present")
    (is (empty? unaccounted))))

(deftest the-right-text-on-the-wrong-node-is-caught
  (testing "an exporter that writes a Work's title on the Manifestation's node fails the RDF audit (post-audit)"
    (let [real   @#'convert/exporters
          moved  (fn [r] (let [doc   ((get-in real [:ntriples :render]) r)
                               work  (some #(when (= :lrmoo/F1_Work (:kind %)) (export/entity-iri (:id %))) (:entities r))
                               manif (some #(when (= :lrmoo/F3_Manifestation (:kind %)) (export/entity-iri (:id %))) (:entities r))]
                           (if (and work manif) (str/replace doc (str "<" work "> <http://www.cidoc-crm.org/cidoc-crm/P1_is_identified_by>")
                                                             (str "<" manif "> <http://www.cidoc-crm.org/cidoc-crm/P1_is_identified_by>"))
                               doc)))
          broken (update real :ntriples assoc :render moved)
          {:keys [unaccounted]} (with-redefs-fn {#'convert/exporters broken} #(audit :intermarc :ntriples))]
      (is (seq unaccounted) "the title still appears in the file, but not on its Work")
      (is (every? #(= :lrmoo/R33_has_string (:predicate %)) unaccounted)))))

(deftest a-silent-loss-inside-a-structured-value-is-caught
  (testing "an exporter that writes an actor's name but not its life dates, and says nothing, fails the audit (post-audit 4, F5)"
    (let [src (slurp (fixtures :lido))
          ok  (fidelity/audit {:from :lido :to :linked-art :source src})
          bad (with-redefs [cov/unwritten-fields (constantly [])]
                (fidelity/audit {:from :lido :to :linked-art :source src}))]
      (is (empty? (:unaccounted ok)))
      (is (some #(and (= :lido/actor (:predicate %)) (= "1634" (:value %))) (:unaccounted bad))
          "the life date inside the structured actor is looked for"))))

(deftest one-field-lost-does-not-account-for-another
  (testing "a seller's name and an acquisition date no exporter writes are caught, even beside a reported role URI (F5 review)"
    (let [xml (str "<lido:lido xmlns:lido=\"http://www.lido-schema.org\"><lido:lidoRecID>A-1</lido:lidoRecID><lido:descriptiveMetadata>"
                   "<lido:objectIdentificationWrap><lido:titleWrap><lido:titleSet><lido:appellationValue>T</lido:appellationValue></lido:titleSet></lido:titleWrap></lido:objectIdentificationWrap>"
                   "<lido:eventWrap><lido:eventSet><lido:event><lido:eventType><lido:term>production</lido:term></lido:eventType>"
                   "<lido:eventActor><lido:actorInRole><lido:actor><lido:nameActorSet><lido:appellationValue>Rembrandt</lido:appellationValue></lido:nameActorSet></lido:actor>"
                   "<lido:roleActor><lido:conceptID>http://vocab.getty.edu/aat/300025136</lido:conceptID><lido:term>painter</lido:term></lido:roleActor></lido:actorInRole></lido:eventActor>"
                   "</lido:event></lido:eventSet><lido:eventSet><lido:event><lido:eventType><lido:term>acquisition</lido:term></lido:eventType>"
                   "<lido:eventActor><lido:actorInRole><lido:actor><lido:nameActorSet><lido:appellationValue>Zyxwvut Seller</lido:appellationValue></lido:nameActorSet></lido:actor></lido:actorInRole></lido:eventActor>"
                   "<lido:eventDate><lido:displayDate>1899</lido:displayDate></lido:eventDate>"
                   "</lido:event></lido:eventSet></lido:eventWrap></lido:descriptiveMetadata></lido:lido>")]
      (doseq [to [:marc21-object :linked-art]]
        (let [{:keys [unaccounted reported]} (fidelity/audit {:from :lido :to to :source xml})]
          (is (empty? unaccounted) (str to ": " (pr-str unaccounted)))
          (is (pos? reported) (str to)))
        (testing "and an exporter that stops reporting them is caught"
          (let [real   @#'convert/exporters
                broken (update real to update :losses
                               (fn [f] (fn [r] (remove #(re-find #"Seller|1899|other than the production" (str (:message %))) (f r)))))
                {:keys [unaccounted]} (with-redefs-fn {#'convert/exporters broken} #(fidelity/audit {:from :lido :to to :source xml}))]
            (is (some #(= "Zyxwvut Seller" (:value %)) unaccounted) (str to))))))))

(deftest a-loss-about-one-value-does-not-cover-its-siblings
  (testing "subjects with no URI, reported, do not hide a subject with one whose label is dropped (F7 review)"
    (let [real   @#'convert/exporters
          broken (update real :linked-art assoc :render
                         (fn [r] (str/replace ((get-in real [:linked-art :render]) r) "\"_label\":\"widow\"" "\"_label\":\"\"")))
          {:keys [unaccounted]} (with-redefs-fn {#'convert/exporters broken} #(audit :lido :linked-art))]
      (is (some #(and (= :lido/subject-concept (:predicate %)) (= "widow" (:value %))) unaccounted)))))

(deftest a-short-field-is-not-found-by-chance
  (testing "a part is found as a word of the output, not inside another word (F7 review)"
    (let [xml    (str "<lido:lido xmlns:lido=\"http://www.lido-schema.org\"><lido:lidoRecID>B-1</lido:lidoRecID><lido:descriptiveMetadata>"
                      "<lido:objectIdentificationWrap><lido:titleWrap><lido:titleSet><lido:appellationValue>T</lido:appellationValue></lido:titleSet></lido:titleWrap>"
                      "<lido:repositoryWrap><lido:repositorySet><lido:workID lido:type=\"inventory number\">16345</lido:workID></lido:repositorySet></lido:repositoryWrap></lido:objectIdentificationWrap>"
                      "<lido:eventWrap><lido:eventSet><lido:event><lido:eventType><lido:term>production</lido:term></lido:eventType>"
                      "<lido:eventActor><lido:actorInRole><lido:actor><lido:nameActorSet><lido:appellationValue>Maes</lido:appellationValue></lido:nameActorSet>"
                      "<lido:vitalDatesActor><lido:earliestDate>1634</lido:earliestDate></lido:vitalDatesActor></lido:actor></lido:actorInRole></lido:eventActor>"
                      "</lido:event></lido:eventSet></lido:eventWrap></lido:descriptiveMetadata></lido:lido>")
          real   @#'convert/exporters
          broken (update real :marc21-object assoc :render
                         (fn [r] (str/replace ((get-in real [:marc21-object :render]) r) #"<subfield code=\"d\">[^<]*</subfield>" "")))
          {:keys [unaccounted]} (with-redefs-fn {#'convert/exporters broken} #(fidelity/audit {:from :lido :to :marc21-object :source xml}))]
      (is (empty? (:unaccounted (fidelity/audit {:from :lido :to :marc21-object :source xml}))))
      (is (some #(and (= :lido/actor (:predicate %)) (= "1634" (:value %))) unaccounted)
          "a birth year dropped from 100 $d is not found inside the inventory number 16345"))))

(deftest a-value-on-the-wrong-event-is-caught
  (testing "an actor's name moved off its own node, onto the object's, is not present (post-audit 5, G5)"
    (let [xml    (str "<lido:lido xmlns:lido=\"http://www.lido-schema.org\"><lido:lidoRecID>M-5</lido:lidoRecID><lido:descriptiveMetadata>"
                      "<lido:objectClassificationWrap><lido:objectWorkTypeWrap><lido:objectWorkType><lido:term>painting</lido:term></lido:objectWorkType></lido:objectWorkTypeWrap></lido:objectClassificationWrap>"
                      "<lido:objectIdentificationWrap><lido:titleWrap><lido:titleSet><lido:appellationValue>T</lido:appellationValue></lido:titleSet></lido:titleWrap></lido:objectIdentificationWrap>"
                      "<lido:eventWrap><lido:eventSet><lido:event><lido:eventType><lido:term>production</lido:term></lido:eventType>"
                      "<lido:eventActor><lido:actorInRole><lido:actor><lido:nameActorSet><lido:appellationValue>Zyx Painter</lido:appellationValue></lido:nameActorSet></lido:actor></lido:actorInRole></lido:eventActor>"
                      "<lido:eventDate><lido:displayDate>1650</lido:displayDate></lido:eventDate>"
                      "</lido:event></lido:eventSet></lido:eventWrap></lido:descriptiveMetadata></lido:lido>")
          ok     (fidelity/audit {:from :lido :to :ntriples :source xml})
          real   @#'convert/exporters
          moved  (fn [re] (update real :ntriples assoc :render
                                  (fn [r] (let [out ((get-in real [:ntriples :render]) r)
                                                obj (second (re-find #"(<[^>]+>) <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://www.cidoc-crm.org/cidoc-crm/E22_Human-Made_Object>" out))]
                                            (str/replace out re (str obj " <http://www.cidoc-crm.org/cidoc-crm/P3_has_note> $1 ."))))))
          name-m (with-redefs-fn {#'convert/exporters (moved #"<[^>]+> <http://www.cidoc-crm.org/cidoc-crm/P190_has_symbolic_content> (\"Zyx Painter\") \.")}
                   #(fidelity/audit {:from :lido :to :ntriples :source xml}))
          date-m (with-redefs-fn {#'convert/exporters (moved #"<[^>]+> <http://www.cidoc-crm.org/cidoc-crm/P82_at_some_time_within> (\"1650\") \.")}
                   #(fidelity/audit {:from :lido :to :ntriples :source xml}))]
      (is (empty? (:unaccounted ok)))
      (is (some #(= "Zyx Painter" (:value %)) (:unaccounted name-m)) "the name is not on its actor")
      (is (some #(= "1650" (:value %)) (:unaccounted date-m)) "the date is not on its event's time-span")))
  (testing "in Linked Art, an actor or a date of the production is looked for in the production, not anywhere (post-audit 5, G5)"
    (let [xml    (str "<lido:lido xmlns:lido=\"http://www.lido-schema.org\"><lido:lidoRecID>M-6</lido:lidoRecID><lido:descriptiveMetadata>"
                      "<lido:objectClassificationWrap><lido:objectWorkTypeWrap><lido:objectWorkType><lido:term>painting</lido:term></lido:objectWorkType></lido:objectWorkTypeWrap></lido:objectClassificationWrap>"
                      "<lido:objectIdentificationWrap><lido:titleWrap><lido:titleSet><lido:appellationValue>T</lido:appellationValue></lido:titleSet></lido:titleWrap></lido:objectIdentificationWrap>"
                      "<lido:eventWrap><lido:eventSet><lido:event><lido:eventType><lido:term>production</lido:term></lido:eventType>"
                      "<lido:eventActor><lido:actorInRole><lido:actor><lido:actorID>http://d-nb.info/gnd/1</lido:actorID><lido:nameActorSet><lido:appellationValue>Zyx Painter</lido:appellationValue></lido:nameActorSet></lido:actor></lido:actorInRole></lido:eventActor>"
                      "<lido:eventDate><lido:displayDate>1650</lido:displayDate></lido:eventDate></lido:event></lido:eventSet>"
                      "<lido:eventSet><lido:event><lido:eventType><lido:term>acquisition</lido:term></lido:eventType>"
                      "<lido:eventDate><lido:displayDate>1899</lido:displayDate></lido:eventDate></lido:event></lido:eventSet>"
                      "</lido:eventWrap></lido:descriptiveMetadata></lido:lido>")
          real   @#'convert/exporters
          swap   (fn [f] (update real :linked-art assoc :render (fn [r] (f ((get-in real [:linked-art :render]) r)))))
          audit* (fn [f] (:unaccounted (with-redefs-fn {#'convert/exporters (swap f)} #(fidelity/audit {:from :lido :to :linked-art :source xml}))))]
      (is (empty? (:unaccounted (fidelity/audit {:from :lido :to :linked-art :source xml}))))
      (is (some #(#{"1650" "1899"} (:value %)) (audit* #(-> % (str/replace "\"1650\"" "\"@@\"") (str/replace "\"1899\"" "\"1650\"") (str/replace "\"@@\"" "\"1899\""))))
          "the production's date and the acquisition's, swapped, are caught (G6 review: swapped, not dropped)")
      (is (some #(= "Zyx Painter" (:value %))
                (audit* #(-> % (str/replace #"\"_label\":\"Zyx Painter\"" "\"_label\":\"x\"")
                             (str/replace #"\"_label\":\"T\"" "\"_label\":\"Zyx Painter\""))))
          "the production's actor, moved onto the object's label, is caught"))))

(deftest values-swapped-between-productions-are-caught
  (testing "two productions' actors or dates exchanged in Linked Art are caught, each looked for in its own (G6 review)"
    (let [xml    (str "<lido:lido xmlns:lido=\"http://www.lido-schema.org\"><lido:lidoRecID>P-2</lido:lidoRecID><lido:descriptiveMetadata>"
                      "<lido:objectClassificationWrap><lido:objectWorkTypeWrap><lido:objectWorkType><lido:term>print</lido:term></lido:objectWorkType></lido:objectWorkTypeWrap></lido:objectClassificationWrap>"
                      "<lido:objectIdentificationWrap><lido:titleWrap><lido:titleSet><lido:appellationValue>T</lido:appellationValue></lido:titleSet></lido:titleWrap></lido:objectIdentificationWrap>"
                      "<lido:eventWrap>"
                      "<lido:eventSet><lido:event><lido:eventType><lido:term>Hergestellt</lido:term></lido:eventType>"
                      "<lido:eventActor><lido:actorInRole><lido:actor><lido:nameActorSet><lido:appellationValue>Alpha Painter</lido:appellationValue></lido:nameActorSet></lido:actor></lido:actorInRole></lido:eventActor>"
                      "<lido:eventDate><lido:displayDate>1650</lido:displayDate></lido:eventDate></lido:event></lido:eventSet>"
                      "<lido:eventSet><lido:event><lido:eventType><lido:term>Hergestellt</lido:term></lido:eventType>"
                      "<lido:eventActor><lido:actorInRole><lido:actor><lido:nameActorSet><lido:appellationValue>Beta Printer</lido:appellationValue></lido:nameActorSet></lido:actor>"
                      "<lido:roleActor><lido:term>Drucker</lido:term></lido:roleActor></lido:actorInRole></lido:eventActor>"
                      "<lido:eventDate><lido:displayDate>1700</lido:displayDate></lido:eventDate></lido:event></lido:eventSet>"
                      "</lido:eventWrap></lido:descriptiveMetadata></lido:lido>")
          real   @#'convert/exporters
          swap   (fn [a b] (update real :linked-art assoc :render
                                   (fn [r] (-> ((get-in real [:linked-art :render]) r)
                                               (str/replace a "@@") (str/replace b a) (str/replace "@@" b)))))
          audit* (fn [a b] (:unaccounted (with-redefs-fn {#'convert/exporters (swap a b)} #(fidelity/audit {:from :lido :to :linked-art :source xml}))))]
      (is (empty? (:unaccounted (fidelity/audit {:from :lido :to :linked-art :source xml}))) "the printer's role is written")
      (is (seq (audit* "\"1650\"" "\"1700\"")) "two productions' dates swapped")
      (is (seq (audit* "Alpha Painter" "Beta Printer")) "two productions' actors swapped"))))

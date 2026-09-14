(ns palomar.conformance-test
  "Tests for the WP-6 conformance mechanism (`palomar.conformance`) and the Linked
   Art (Louvre) profile. The mechanism: a profile's checks are ordinary diagnostics
   over a projected WEMI record; the verdict is policy-gated (the acceptance
   threshold). Grounded on constructed records (each check in isolation) and on the
   real pipeline (MARC21 conformant-with-warnings; a titleless record fails the name
   requirement)."
  (:require [clojure.test :refer [deftest is testing]]
            [palomar.conformance :as conf]
            [palomar.model :as model]))

(defn- codes [diags] (set (map (comp name :code) diags)))

;; ---------------------------------------------------------------------------
;; Each check, in isolation, on constructed WEMI records
;; ---------------------------------------------------------------------------

(def ^:private conformant
  (model/record
   {:id :rec/ok :kind :test
    :entities [(model/entity {:id :w/a :kind :lrmoo/F1_Work})
               (model/entity {:id :e/a :kind :lrmoo/F2_Expression})
               (model/entity {:id :m/a :kind :lrmoo/F3_Manifestation :iri "https://example.org/ark"})
               (model/entity {:id :p/a :kind :crm/E21_Person
                              :iri "https://isni.org/isni/0000000122762442"})]
    :assertions [(model/assertion {:subject :e/a :predicate :lrmoo/R33_has_string :value "A Title"})
                 (model/assertion {:subject :rec/ok :predicate :canon/agent :value "Gustave Flaubert"})]}))

(deftest a-fully-conformant-record-raises-nothing
  (testing "manifestation + title + expression + work + identified creator + identifier"
    (is (empty? (conf/check-record conf/linked-art-profile conformant)))))

(deftest the-hard-requirements-are-errors
  (testing "a bare record (no entities, no title) fails the two error checks"
    (let [diags (conf/check-record conf/linked-art-profile
                                   (model/record {:id :rec/bare :kind :test}))
          errs  (filter #(= :error (:severity %)) diags)]
      (is (= #{"root-human-made-object" "has-name"} (codes errs)))
      (testing "and the richness checks downgrade to warnings / info, not errors"
        (is (contains? (codes diags) "carries-expression"))
        (is (contains? (codes diags) "expression-realises-work"))
        (is (contains? (codes diags) "has-identifier"))))))

(deftest creator-identified-discriminates-bare-label-from-authority-id
  (let [base {:id :rec/c :kind :test
              :entities [(model/entity {:id :m/a :kind :lrmoo/F3_Manifestation})]
              :assertions [(model/assertion {:subject :m/a :predicate :lrmoo/R33_has_string :value "T"})
                           (model/assertion {:subject :rec/c :predicate :canon/agent :value "Someone"})]}]
    (testing "a bare-label creator (no E21_Person iri) warns"
      (is (contains? (codes (conf/check-record conf/linked-art-profile (model/record base)))
                     "creator-identified")))
    (testing "an authority-identified creator does not"
      (let [with-id (update base :entities conj
                            (model/entity {:id :p/a :kind :crm/E21_Person :iri "https://isni.org/x"}))]
        (is (not (contains? (codes (conf/check-record conf/linked-art-profile (model/record with-id)))
                            "creator-identified")))))))

(deftest for-profile-filters-to-the-profile
  (let [conf-diags (conf/check-record conf/linked-art-profile
                                      (model/record {:id :rec/bare :kind :test}))
        foreign    (model/diagnostic {:severity :warning :code :other/unrelated :subject :rec/x})
        mixed      (conj (vec conf-diags) foreign)]
    (is (seq conf-diags))
    (is (= (set conf-diags) (set (conf/for-profile mixed :linked-art))))
    (is (not (some #(= :other/unrelated (:code %)) (conf/for-profile mixed :linked-art))))))

;; ---------------------------------------------------------------------------
;; On the real pipeline
;; ---------------------------------------------------------------------------

(def ^:private marc21 "test/fixtures/documentary/marc21/marcxml/loc_collection.xml")

(deftest marc21-is-conformant-with-richness-warnings-and-the-policy-is-the-threshold
  (let [run (fn [policy]
              (conf/conformance {:from :marc21 :source (slurp marc21)
                                 :profile conf/linked-art-profile :policy policy}))]
    (testing "the two records pass the hard requirements (no errors) -> conformant under errors-only"
      (let [{:keys [records diagnostics failed? summary]} (run :errors-only)]
        (is (= 2 records))
        (is (false? failed?))
        (is (= :warning (:max-severity summary)))
        (testing "the floor creators are bare labels -> a creator-identified warning each"
          (is (= 2 (count (filter #(= :conformance.linked-art/creator-identified (:code %))
                                  diagnostics)))))))
    (testing "raising the acceptance threshold (the institution's knob) makes the warnings fail"
      (is (true? (:failed? (run :errors-and-warnings)))))))

(deftest a-titleless-record-fails-the-name-requirement
  (testing "no dc:title -> has-name error -> failed under errors-only"
    (let [src "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:creator>Anon</dc:creator></metadata>"
          {:keys [diagnostics failed? summary]}
          (conf/conformance {:from :dc :source src :opts {:record-id :doc/untitled}
                             :profile conf/linked-art-profile})]
      (is (true? failed?))
      (is (= :error (:max-severity summary)))
      (is (some #(= :conformance.linked-art/has-name (:code %)) diagnostics)))))

;; ---------------------------------------------------------------------------
;; The BnF INTERMARC (bibliographic) profile — over native :intermarc/* fields
;; ---------------------------------------------------------------------------

(defn- imarc
  "An INTERMARC bibliographic record carrying the given [predicate value] fields."
  [fields]
  (model/record {:id :bnf/x :kind :intermarc/bibliographic
                 :assertions (mapv (fn [[p v]] (model/assertion {:subject :bnf/x :predicate p :value v}))
                                   fields)}))

(deftest intermarc-hard-essentials-are-errors
  (testing "a record with neither 001 nor 245 fails both essentials"
    (let [errs (filter #(= :error (:severity %))
                       (conf/check-record conf/intermarc-profile (imarc [])))]
      (is (= #{"control-number" "title"} (codes errs))))))

(deftest intermarc-heading-must-be-authority-linked
  (let [check #(codes (conf/check-record conf/intermarc-profile (imarc %)))
        essentials [[:intermarc/f001 "1"] [:intermarc/f245_a "T"]]]
    (testing "a 100 heading with no $3 link warns (Transition bibliographique)"
      (is (contains? (check (conj essentials [:intermarc/f100_a "Hugo, Victor"]))
                     "heading-authority-linked")))
    (testing "a 100 heading WITH a $3 link does not"
      (is (not (contains? (check (conj essentials [:intermarc/f100_a "Hugo, Victor"] [:intermarc/f100_3 "ISNI"]))
                          "heading-authority-linked"))))
    (testing "no 100 heading at all is conformant on that check (nothing to link)"
      (is (not (contains? (check essentials) "heading-authority-linked"))))))

(def ^:private imarc-dir "test/fixtures/documentary/intermarc/sru/intermarcXchange/")

(deftest intermarc-profile-on-real-bnf-records
  (let [run (fn [file] (conf/conformance {:from :intermarc :profile conf/intermarc-profile
                                          :source (slurp (str imarc-dir file))}))
        n   (fn [diags code] (count (filter #(= code (:code %)) diags)))]
    (testing "the WEMI-derived Bovary showcase is conformant — 1 unlinked heading + 2 work-link infos"
      (let [{:keys [failed? diagnostics records]} (run "bib-flaubert-madame-bovary-start1-max30.xml")]
        (is (= 30 records))
        (is (false? failed?))
        (is (= 1 (n diagnostics :conformance.intermarc/heading-authority-linked)))
        (is (= 2 (n diagnostics :conformance.intermarc/work-authority-link)))))
    (testing "a monographs set with a record missing its 245 is non-conformant (a title error)"
      (let [{:keys [failed? diagnostics]} (run "bib-monographies-start1-max30.xml")]
        (is (true? failed?))
        (is (= 1 (n diagnostics :conformance.intermarc/title)))))
    (testing "periodicals (no personal author) are conformant — not flagged for a missing 100"
      (let [{:keys [failed? diagnostics]} (run "bib-periodiques-start1-max30.xml")]
        (is (false? failed?))
        (is (zero? (n diagnostics :conformance.intermarc/heading-authority-linked)))))))

;; ---------------------------------------------------------------------------
;; The IIIF Presentation 3.0 profile — over the canonical floor
;; ---------------------------------------------------------------------------

(defn- iiif-rec
  "A record with `source` (or none) and the given canonical [predicate value] fields."
  [source fields]
  (cond-> (model/record {:id :m/iiif :kind :test
                         :assertions (mapv (fn [[p v]] (model/assertion {:subject :m/iiif :predicate p :value v}))
                                           fields)})
    source (assoc :source source)))

(deftest iiif-label-is-the-hard-requirement
  (testing "a record with no title fails the label check (the one IIIF error)"
    (let [errs (filter #(= :error (:severity %))
                       (conf/check-record conf/iiif-profile (model/record {:id :m/bare :kind :test})))]
      (is (= #{"label"} (codes errs))))))

(deftest iiif-canvas-and-id-discriminate
  (let [check #(codes (conf/check-record conf/iiif-profile %))]
    (testing "a digitised manifest (title + http id + a digital object) is fully conformant"
      (is (empty? (conf/check-record conf/iiif-profile
                                     (iiif-rec "https://ex.org/manifest.json"
                                               [[:canon/title "A Book"] [:canon/digital-object "https://ex.org/i.jpg"]])))))
    (testing "no digital object warns has-canvas (nothing to present)"
      (is (contains? (check (iiif-rec "https://ex.org/manifest.json" [[:canon/title "A Book"]])) "has-canvas")))
    (testing "a non-HTTP id warns dereferenceable-id"
      (is (contains? (check (iiif-rec "5637241" [[:canon/title "A Book"] [:canon/digital-object "https://ex.org/i.jpg"]]))
                     "dereferenceable-id")))
    (testing "an HTTP :canon/identifier satisfies the id check even when :source is not a URI"
      (is (not (contains? (check (iiif-rec nil [[:canon/title "T"] [:canon/digital-object "https://ex.org/i.jpg"]
                                                [:canon/identifier "https://ex.org/id"]]))
                          "dereferenceable-id"))))))

(deftest iiif-profile-on-real-manifests-and-bibliographic-records
  (testing "a real IIIF manifest is fully conformant — zero diagnostics"
    (let [{:keys [failed? diagnostics records]}
          (conf/conformance {:from :iiif :profile conf/iiif-profile
                             :source (slurp "test/fixtures/documentary/iiif/manifest_book_simple.json")})]
      (is (= 1 records))
      (is (false? failed?))
      (is (empty? diagnostics))))
  (testing "MARC21 records are conformant-with-warnings — bare ids, one with no canvas"
    (let [{:keys [failed? diagnostics]}
          (conf/conformance {:from :marc21 :profile conf/iiif-profile :source (slurp marc21)})]
      (is (false? failed?))
      (is (= 2 (count (filter #(= :conformance.iiif/dereferenceable-id (:code %)) diagnostics))))
      (is (= 1 (count (filter #(= :conformance.iiif/has-canvas (:code %)) diagnostics))))))
  (testing "a titleless record fails the label requirement"
    (let [src "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:creator>Anon</dc:creator></metadata>"
          {:keys [failed? diagnostics]}
          (conf/conformance {:from :dc :source src :opts {:record-id :doc/untitled} :profile conf/iiif-profile})]
      (is (true? failed?))
      (is (some #(= :conformance.iiif/label (:code %)) diagnostics)))))

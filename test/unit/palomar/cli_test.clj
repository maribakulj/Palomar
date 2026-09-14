(ns palomar.cli-test
  "Tests for the CLI core `run` (pure: returns {:exit :out :err}, no print/exit).
   Exercises the happy paths, the format listing, the default record-id, file
   output, and the error exits."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.cli :as cli]))

(def ^:private marc21 "test/fixtures/documentary/marc21/marcxml/loc_collection.xml")
(def ^:private dc "test/fixtures/documentary/dublin-core/w3c_dc_example1.xml")
(def ^:private iiif-manifest "test/fixtures/documentary/iiif/manifest_book_simple.json")

(deftest converts-and-reports
  (testing "convert MARC21 -> Linked Art: output to :out, loss report to :err"
    (let [{:keys [exit out err]} (cli/run ["convert" marc21 "--from" "marc21" "--to" "linked-art"])]
      (is (= 0 exit))
      (is (str/includes? out "HumanMadeObject"))
      (is (str/includes? out "The Great Ray Charles"))
      (is (str/includes? err "Loss report"))
      (is (str/includes? err "2 records converted")))))

(deftest dublin-core-defaults-its-record-id-from-the-filename
  (testing "DC needs a record-id; without --record-id the CLI derives one and still converts"
    (let [{:keys [exit out]} (cli/run ["convert" dc "--from" "dc" "--to" "turtle"])]
      (is (= 0 exit))
      (is (str/includes? out "@prefix lrmoo:"))
      (is (str/includes? out "Dublin Core Tutorial")))))

(deftest writes-to-a-file-with-out
  (testing "--out writes the document to a file; stdout stays empty"
    (let [path (str (System/getProperty "java.io.tmpdir") "/palomar-cli-" (System/nanoTime) ".jsonld")]
      (try
        (let [{:keys [exit out err]} (cli/run ["convert" marc21 "--from" "marc21" "--to" "jsonld" "--out" path])]
          (is (= 0 exit))
          (is (= "" out))
          (is (str/includes? err (str "wrote " path)))
          (is (str/includes? (slurp path) "@graph")))
        (finally (.delete (java.io.File. path)))))))

(deftest formats-and-help
  (testing "formats lists the supported source and target formats"
    (let [{:keys [exit err]} (cli/run ["formats"])]
      (is (= 0 exit))
      (is (str/includes? err "linked-art"))
      (is (str/includes? err "intermarc"))))
  (testing "--help and no-args print usage with exit 0"
    (is (= 0 (:exit (cli/run ["--help"]))))
    (is (str/includes? (:err (cli/run ["--help"])) "Usage:"))
    (is (str/includes? (:err (cli/run [])) "Usage:"))))

(deftest error-exits
  (testing "a missing input file exits 2"
    (let [{:keys [exit err]} (cli/run ["convert" "nope.xml" "--from" "dc" "--to" "turtle"])]
      (is (= 2 exit))
      (is (str/includes? err "not found"))))
  (testing "an unknown format exits 2 with the supported set"
    (let [{:keys [exit err]} (cli/run ["convert" marc21 "--from" "bogus" "--to" "turtle"])]
      (is (= 2 exit))
      (is (str/includes? err "error:"))))
  (testing "missing --from exits 2 with usage"
    (let [{:keys [exit err]} (cli/run ["convert" marc21 "--to" "turtle"])]
      (is (= 2 exit))
      (is (str/includes? err "needs"))))
  (testing "an unknown command exits 2"
    (is (= 2 (:exit (cli/run ["frobnicate"]))))))

(deftest validate-command
  (testing "a titled source validates with exit 0"
    (let [{:keys [exit err]} (cli/run ["validate" marc21 "--from" "marc21"])]
      (is (= 0 exit))
      (is (str/includes? err "VALID"))
      (is (not (str/includes? err "INVALID")))))
  (testing "a titleless record warns; errors-only passes, errors-and-warnings fails (exit 1)"
    (let [path (str (System/getProperty "java.io.tmpdir") "/palomar-notitle-" (System/nanoTime) ".xml")]
      (try
        (spit path "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:creator>Anon</dc:creator></metadata>")
        (is (= 0 (:exit (cli/run ["validate" path "--from" "dc"]))))
        (let [{:keys [exit err]} (cli/run ["validate" path "--from" "dc" "--policy" "errors-and-warnings"])]
          (is (= 1 exit))
          (is (str/includes? err "INVALID"))
          (is (str/includes? err "missing-title")))
        (finally (.delete (java.io.File. path))))))
  (testing "validate needs --from"
    (is (= 2 (:exit (cli/run ["validate" marc21]))))))

;; --- report / inspect / reconcile (read-only verbs) -------------------------

(def ^:private intermarc
  "test/fixtures/documentary/intermarc/sru/intermarcXchange/bnf-sru-victor-hugo-50.xml")

(def ^:private intermarc-bovary
  "test/fixtures/documentary/intermarc/sru/intermarcXchange/bib-flaubert-madame-bovary-start1-max30.xml")

(deftest report-emits-the-loss-report-only
  (testing "report MARC21 -> DC: the loss report to stdout, no converted document"
    (let [{:keys [exit out]} (cli/run ["report" marc21 "--from" "marc21" "--to" "dc"])]
      (is (= 0 exit))
      (is (str/includes? out "Loss report"))
      (is (str/includes? out "by category"))
      (is (str/includes? out "2 records"))
      (is (not (str/includes? out "<dc:title")))))          ; the document is NOT emitted
  (testing "report needs --to"
    (let [{:keys [exit err]} (cli/run ["report" marc21 "--from" "marc21"])]
      (is (= 2 exit))
      (is (str/includes? err "report needs --to")))))

(deftest report-format-edn-emits-the-raw-machine-readable-map
  (testing "report --format edn: a parseable conversion-report map, no prose"
    (let [{:keys [exit out]} (cli/run ["report" marc21 "--from" "marc21" "--to" "dc" "--format" "edn"])
          m (edn/read-string out)]
      (is (= 0 exit))
      (is (map? m))
      (is (contains? m :by-category))                     ; the structured account, not prose
      (is (contains? m :by-edge))
      (is (= 2 (:records m)))
      (is (not (str/includes? out "Loss report")))))      ; the text rendering is suppressed
  (testing "report --format rejects an unknown format"
    (let [{:keys [exit err]} (cli/run ["report" marc21 "--from" "marc21" "--to" "dc" "--format" "xml"])]
      (is (= 2 exit))
      (is (str/includes? err "must be")))))

(deftest report-format-json-is-valid-and-namespace-preserving
  (testing "report --format json: valid JSON, the same account as edn,"
    (let [{:keys [exit out]} (cli/run ["report" marc21 "--from" "marc21" "--to" "dc" "--format" "json"])
          m (json/read-str out)]                            ; parses back, so it is valid JSON
      (is (= 0 exit))
      (is (map? m))
      (is (= 2 (get m "records")))
      (is (contains? m "by-category"))
      (is (contains? m "by-edge"))
      (is (not (str/includes? out "Loss report")))          ; no prose, like edn
      (testing "namespaces survive on both keys and vector values (no collisions)"
        ;; data.json's default key-fn is `name`, which would drop the namespace;
        ;; the report qualifies them, so :canon/date -> \"canon/date\", not \"date\".
        (is (every? #(str/starts-with? % "canon/") (get m "source-fields")))
        (is (every? #(str/includes? % "/")
                    (keys (get-in m ["by-edge" "import" "by-source-field"]))))))))

;; --- degenerate-input handling (WP-9 hardening) -----------------------------

(def ^:private mods "test/fixtures/documentary/mods/loc_mods_book.xml")

(deftest convert-warns-when-zero-records-parsed
  (testing "wrong --from (MODS fed as marc21) yields 0 records — a loud warning,"
    (testing "not a silent exit-0 success that writes nothing"
      (let [{:keys [exit out err]} (cli/run ["convert" mods "--from" "marc21" "--to" "dc"])]
        (is (= 0 exit))                                     ; empty parse is legal — warn, don't fail
        (is (= "" out))                                     ; nothing was produced
        (is (str/includes? err "0 records parsed"))
        (is (str/includes? err "--from marc21"))))))        ; actionable: points at the likely cause

(deftest convert-fails-cleanly-on-malformed-input
  (testing "garbage input exits 2 with a parse error, never an unhandled crash"
    (let [f (java.io.File/createTempFile "palomar-malformed-" ".xml")]
      (try
        (spit f "this is not xml {{")
        (let [{:keys [exit out err]} (cli/run ["convert" (str f) "--from" "marc21" "--to" "dc"])]
          (is (= 2 exit))
          (is (= "" out))
          (is (str/includes? err "error")))
        (finally (.delete f))))))

(deftest inspect-shows-the-floor-and-the-minted-entities
  (testing "inspect MARC21: the canonical floor and the WEMI entities per record"
    (let [{:keys [exit out]} (cli/run ["inspect" marc21 "--from" "marc21"])]
      (is (= 0 exit))
      (is (str/includes? out "2 records"))
      (is (str/includes? out ":marc/r5637241"))
      (is (str/includes? out ":canon/title"))
      (is (str/includes? out "The Great Ray Charles"))
      (is (str/includes? out "entities:"))
      (is (str/includes? out "lrmoo/F1_Work")))))

(deftest reconcile-groups-agents-by-authority-id
  (testing "reconcile INTERMARC (Victor Hugo 50): agents reconciled by ISNI"
    (let [{:keys [exit out]} (cli/run ["reconcile" intermarc "--from" "intermarc"])]
      (is (= 0 exit))
      (is (str/includes? out "reconciled to"))
      (is (str/includes? out "distinct agent"))
      (is (str/includes? out "isni.org"))))
  (testing "reconcile MARC21: honest 'none' (no authority-identified agents on the floor)"
    (let [{:keys [exit out]} (cli/run ["reconcile" marc21 "--from" "marc21"])]
      (is (= 0 exit))
      (is (str/includes? out "no authority-identified agents")))))

(deftest read-only-verbs-validate-their-inputs
  (testing "a missing file is an exit-2, not a crash"
    (is (= 2 (:exit (cli/run ["inspect" "/no/such/file.xml" "--from" "marc21"]))))
    (is (= 2 (:exit (cli/run ["reconcile" marc21]))))))         ; missing --from

;; --- apply-repairs (curate the inferred :proposed claims, ADR 0005) ----------

(deftest apply-repairs-curates-the-proposed-claims
  (testing "DC --policy accept: the four string-key WEMI proposals are accepted"
    (let [{:keys [exit out]} (cli/run ["apply-repairs" dc "--from" "dc" "--policy" "accept"])]
      (is (= 0 exit))
      (is (str/includes? out "4 proposals curated"))
      (is (str/includes? out "4 accepted"))
      (is (str/includes? out "proposed → accepted"))))
  (testing "the default policy is the conservative flag (route to needs-review, none accepted)"
    (let [{:keys [exit out]} (cli/run ["apply-repairs" dc "--from" "dc"])]
      (is (= 0 exit))
      (is (str/includes? out "(flag)"))
      (is (str/includes? out "4 needs-review"))))
  (testing "MARC21 (two records): eight proposals curated"
    (let [{:keys [exit out]} (cli/run ["apply-repairs" marc21 "--from" "marc21" "--policy" "reject"])]
      (is (= 0 exit))
      (is (str/includes? out "8 proposals curated"))
      (is (str/includes? out "8 rejected")))))

(deftest apply-repairs-guards-its-inputs
  (testing "an unknown --policy exits 2"
    (let [{:keys [exit err]} (cli/run ["apply-repairs" dc "--from" "dc" "--policy" "bogus"])]
      (is (= 2 exit))
      (is (str/includes? err "unknown --policy"))))
  (testing "a missing --from exits 2"
    (is (= 2 (:exit (cli/run ["apply-repairs" dc]))))))

;; --- conformance (institutional profile check, WP-6) ------------------------

(deftest conformance-checks-against-a-profile
  (testing "MARC21 vs Linked Art: conformant under errors-only (only richness warnings)"
    (let [{:keys [exit err]} (cli/run ["conformance" marc21 "--from" "marc21" "--profile" "linked-art"])]
      (is (= 0 exit))
      (is (str/includes? err "CONFORMANT"))
      (is (not (str/includes? err "NON-CONFORMANT")))
      (is (str/includes? err "Linked Art (Louvre)"))
      (is (str/includes? err "creator-identified"))))
  (testing "raising the acceptance threshold makes the warnings non-conformant (exit 1)"
    (let [{:keys [exit err]} (cli/run ["conformance" marc21 "--from" "marc21"
                                       "--profile" "linked-art" "--policy" "errors-and-warnings"])]
      (is (= 1 exit))
      (is (str/includes? err "NON-CONFORMANT")))))

(deftest conformance-fails-a-titleless-record-and-guards-its-inputs
  (testing "a titleless DC record fails the name requirement (exit 1)"
    (let [path (str (System/getProperty "java.io.tmpdir") "/palomar-conf-" (System/nanoTime) ".xml")]
      (try
        (spit path "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:creator>Anon</dc:creator></metadata>")
        (let [{:keys [exit err]} (cli/run ["conformance" path "--from" "dc" "--profile" "linked-art"])]
          (is (= 1 exit))
          (is (str/includes? err "NON-CONFORMANT"))
          (is (str/includes? err "has-name")))
        (finally (.delete (java.io.File. path))))))
  (testing "a missing --profile exits 2 with the available set"
    (let [{:keys [exit err]} (cli/run ["conformance" marc21 "--from" "marc21"])]
      (is (= 2 exit))
      (is (str/includes? err "needs --profile"))))
  (testing "an unknown --profile exits 2"
    (is (= 2 (:exit (cli/run ["conformance" marc21 "--from" "marc21" "--profile" "bogus"]))))))

(deftest conformance-supports-the-intermarc-profile
  (testing "INTERMARC Bovary showcase vs the BnF INTERMARC profile: conformant"
    (let [{:keys [exit err]} (cli/run ["conformance" intermarc-bovary "--from" "intermarc" "--profile" "intermarc"])]
      (is (= 0 exit))
      (is (str/includes? err "CONFORMANT"))
      (is (not (str/includes? err "NON-CONFORMANT")))
      (is (str/includes? err "BnF INTERMARC"))
      (is (str/includes? err "heading-authority-linked")))))

(deftest conformance-supports-the-iiif-profile
  (testing "a real IIIF manifest vs the IIIF Presentation 3.0 profile: fully conformant"
    (let [{:keys [exit err]} (cli/run ["conformance" iiif-manifest "--from" "iiif" "--profile" "iiif"])]
      (is (= 0 exit))
      (is (str/includes? err "CONFORMANT"))
      (is (not (str/includes? err "NON-CONFORMANT")))
      (is (str/includes? err "IIIF Presentation 3.0")))))

;; --- convert --stream (WP-7 bounded-memory streaming) -----------------------

(deftest convert-stream-writes-bounded-output-to-out
  (testing "convert --stream lazily parses and writes the document to --out"
    (let [path (str (System/getProperty "java.io.tmpdir") "/palomar-stream-" (System/nanoTime) ".nt")]
      (try
        (let [{:keys [exit out err]} (cli/run ["convert" marc21 "--from" "marc21"
                                               "--to" "ntriples" "--stream" "--out" path])
              written (slurp path)]
          (is (= 0 exit))
          (is (= "" out))                                   ; document went to the file, not :out
          (is (str/includes? err "streamed"))
          (is (str/includes? err "Loss report"))
          (is (str/includes? written "lrmoo/F3_Manifestation"))
          (is (str/includes? written "The Great Ray Charles")))
        (finally (.delete (java.io.File. path)))))))

(deftest convert-stream-guards-its-inputs
  (testing "--stream requires --out"
    (let [{:keys [exit err]} (cli/run ["convert" marc21 "--from" "marc21" "--to" "ntriples" "--stream"])]
      (is (= 2 exit))
      (is (str/includes? err "requires --out"))))
  (testing "--stream rejects a non-streamable spoke (Dublin Core is not a flat dump)"
    (let [{:keys [exit err]} (cli/run ["convert" dc "--from" "dc" "--to" "ntriples"
                                       "--stream" "--out" "/tmp/palomar-x.nt"])]
      (is (= 2 exit))
      (is (str/includes? err "not supported")))))

(deftest convert-stream-fails-atomically-leaving-no-partial-out
  (testing "a malformed input mid-stream: exit 2 and NO --out file is left (all-or-nothing, like batch)"
    (let [bad (str (System/getProperty "java.io.tmpdir") "/palomar-bad-" (System/nanoTime) ".xml")
          out (str (System/getProperty "java.io.tmpdir") "/palomar-out-" (System/nanoTime) ".nt")]
      (try
        ;; one good record, then a truncated/unclosed second record -> data.xml throws mid-stream
        (spit bad (str "<marc:collection xmlns:marc=\"http://www.loc.gov/MARC21/slim\">"
                       "<marc:record><marc:controlfield tag=\"001\">1</marc:controlfield>"
                       "<marc:datafield tag=\"245\"><marc:subfield code=\"a\">First</marc:subfield></marc:datafield></marc:record>"
                       "<marc:record><marc:controlfield tag=\"001\">2</marc:controlfield>UNCLOSED"))
        (let [{:keys [exit]} (cli/run ["convert" bad "--from" "marc21" "--to" "ntriples" "--stream" "--out" out])]
          (is (= 2 exit))
          (is (not (.exists (java.io.File. out)))))      ; no partial file left behind
        (finally (.delete (java.io.File. bad)) (.delete (java.io.File. out))))))
  (testing "a bad --out directory fails cleanly (exit 2), not with an uncaught crash"
    (let [{:keys [exit err]} (cli/run ["convert" marc21 "--from" "marc21" "--to" "ntriples"
                                       "--stream" "--out" "/no/such/palomar-dir/out.nt"])]
      (is (= 2 exit))
      (is (str/includes? err "error:")))))

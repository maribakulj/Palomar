(ns palomar.curate-test
  "Tests for the ADR 0005 curation engine (`palomar.curate`): pending proposals are
   resolved into the workflow family (accept/reject/review), in-force and
   already-resolved assertions are left untouched, the transition log is the audit
   record, and the policies compose. Grounded both on constructed records and on
   the real DC pipeline, whose string-key WEMI inference emits four `:proposed`
   claims per record."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.convert :as convert]
            [palomar.curate :as curate]
            [palomar.model :as model]))

(defn- prop
  "A :proposed assertion on :rec/a."
  ([predicate value] (prop predicate value 1.0))
  ([predicate value confidence]
   (model/assertion {:subject :rec/a :predicate predicate :value value
                     :confidence confidence :status :proposed})))

(defn- rec [assertions]
  (model/record {:id :rec/a :kind :test :assertions assertions}))

;; ---------------------------------------------------------------------------
;; The three transitions
;; ---------------------------------------------------------------------------

(deftest accept-promotes-proposed-leaves-in-force-untouched
  (let [asserted (model/assertion {:subject :rec/a :predicate :p/kept :value "keep"})
        {:keys [record transitions]}
        (curate/curate-record (rec [(prop :p/x "v") asserted]) curate/accept-all)
        [a0 a1] (:assertions record)]
    (testing "the :proposed assertion becomes :accepted"
      (is (model/accepted? a0))
      (is (= "v" (:value a0))))
    (testing "the in-force :asserted assertion is untouched"
      (is (= asserted a1)))
    (testing "one transition is logged, proposed -> accepted via :accept"
      (is (= [{:subject :rec/a :predicate :p/x :from :proposed :to :accepted :verdict :accept}]
             transitions)))))

(deftest reject-and-flag-transitions
  (testing "reject-all moves a proposal to :rejected"
    (let [{:keys [record]} (curate/curate-record (rec [(prop :p/x "v")]) curate/reject-all)]
      (is (model/rejected? (first (:assertions record))))))
  (testing "flag-all routes a proposal to :needs-review"
    (let [{:keys [record]} (curate/curate-record (rec [(prop :p/x "v")]) curate/flag-all)]
      (is (model/needs-review? (first (:assertions record)))))))

(deftest non-pending-assertions-are-never-touched
  (let [as [(model/assertion {:subject :rec/a :predicate :p/a :value "a" :status :asserted})
            (model/assertion {:subject :rec/a :predicate :p/b :value "b" :status :retracted})
            (model/assertion {:subject :rec/a :predicate :p/c :value "c" :status :superseded})
            (model/assertion {:subject :rec/a :predicate :p/d :value "d" :status :accepted})
            (model/assertion {:subject :rec/a :predicate :p/e :value "e" :status :rejected})]
        {:keys [record transitions]} (curate/curate-record (rec as) curate/accept-all)]
    (testing "nothing in force or already resolved is curated"
      (is (= as (:assertions record)))
      (is (empty? transitions)))))

(deftest needs-review-is-pending-and-re-curatable
  (testing "a :needs-review assertion is pending — a human accept resolves it"
    (let [a (model/assertion {:subject :rec/a :predicate :p/x :value "v" :status :needs-review})
          {:keys [record transitions]} (curate/curate-record (rec [a]) curate/accept-all)]
      (is (model/accepted? (first (:assertions record))))
      (is (= :needs-review (:from (first transitions)))))))

;; ---------------------------------------------------------------------------
;; Policies compose
;; ---------------------------------------------------------------------------

(deftest accept-when-promotes-only-the-guard-passers
  (testing "accept-when accepts proposals satisfying the predicate, flags the rest"
    (let [high (prop :p/hi "hi" 0.95)
          low  (prop :p/lo "lo" 0.40)
          decide (curate/accept-when #(>= (:confidence %) 0.9))
          {:keys [record]} (curate/curate-record (rec [high low]) decide)
          [a0 a1] (:assertions record)]
      (is (model/accepted? a0))
      (is (model/needs-review? a1)))))

;; ---------------------------------------------------------------------------
;; Shape, validity, edge cases
;; ---------------------------------------------------------------------------

(deftest curated-assertions-stay-valid
  (testing "an accepted / rejected / flagged assertion still validates against the schema"
    (doseq [policy [curate/accept-all curate/reject-all curate/flag-all]]
      (let [{:keys [record]} (curate/curate-record (rec [(prop :p/x "v")]) policy)]
        (is (model/valid-assertion? (first (:assertions record))))))))

(deftest a-record-with-no-assertions-passes-through
  (let [r (model/record {:id :rec/a :kind :test})
        {:keys [record transitions]} (curate/curate-record r curate/accept-all)]
    (is (= r record))
    (is (empty? transitions))))

(deftest an-invalid-verdict-is-rejected
  (testing "a decision that is not :accept/:reject/:review throws"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"must be :accept"
         (curate/curate-record (rec [(prop :p/x "v")]) (constantly :nonsense))))))

;; ---------------------------------------------------------------------------
;; Batch + summary
;; ---------------------------------------------------------------------------

(deftest curate-batch-aggregates-transitions-and-summary
  (let [r1 (model/record {:id :rec/a :kind :test :assertions [(prop :p/x "x")]})
        r2 (model/record {:id :rec/b :kind :test
                          :assertions [(model/assertion {:subject :rec/b :predicate :p/y
                                                         :value "y" :status :proposed})
                                       (model/assertion {:subject :rec/b :predicate :p/z
                                                         :value "z" :status :asserted})]})
        {:keys [records transitions summary]} (curate/curate [r1 r2] curate/accept-all)]
    (testing "every record's proposals are curated"
      (is (= 2 (count records)))
      (is (= 2 (count transitions))))
    (testing "the summary counts by resulting status"
      (is (= {:accepted 2 :rejected 0 :needs-review 0 :total 2} summary)))))

;; ---------------------------------------------------------------------------
;; Reporting
;; ---------------------------------------------------------------------------

(deftest format-curation-renders-summary-and-the-empty-case
  (testing "no proposals -> an honest one-liner"
    (is (= "apply-repairs: no pending proposals to curate."
           (curate/format-curation (curate/curate [] curate/accept-all) "accept"))))
  (testing "with proposals -> a summary line and per-transition lines"
    (let [result (curate/curate [(rec [(prop :p/x "v")])] curate/accept-all)
          out    (curate/format-curation result "accept")]
      (is (str/includes? out "1 proposal curated"))
      (is (str/includes? out "1 accepted"))
      (is (str/includes? out "proposed → accepted"))))
  (testing "tolerates a single-record (curate-record) result, which carries :transitions but no :summary"
    (let [single (curate/curate-record (rec [(prop :p/x "v")]) curate/accept-all)]
      (is (not (contains? single :summary)))                ; the sharp edge: no :summary
      (is (str/includes? (curate/format-curation single "accept") "1 accepted")))))

;; ---------------------------------------------------------------------------
;; On the real pipeline (DC string-key WEMI inference emits four :proposed claims)
;; ---------------------------------------------------------------------------

(def ^:private dc-fixture "test/fixtures/documentary/dublin-core/w3c_dc_example1.xml")

(deftest curates-the-proposed-claims-a-real-conversion-emits
  (let [{:keys [records]} (convert/to-wemi :dc {:record-id :doc/ex1} (slurp dc-fixture))
        before (mapcat :assertions records)
        n-prop (count (filter model/proposed? before))
        n-asrt (count (filter model/asserted? before))
        {:keys [records summary]} (curate/curate records curate/accept-all)
        after  (mapcat :assertions records)]
    (testing "the DC string-key WEMI inference produced four :proposed claims"
      (is (= 4 n-prop)))
    (testing "accept-all promotes exactly those four; the asserted floor is untouched"
      (is (= 4 (:accepted summary)))
      (is (= 4 (count (filter model/accepted? after))))
      (is (zero? (count (filter model/proposed? after))))
      (is (= n-asrt (count (filter model/asserted? after)))))))

(ns palomar.eval.bibr-work-clustering-test
  "Independent WEMI-derivation eval — Palomar's MARC21 → WEMI Work clustering measured
   against the **BIB-R** benchmark gold (a third-party 'Benchmark of FRBRization
   solutions', bib-r.github.io, CC BY-NC). See `test/fixtures/bibr-gold/README.md`
   for provenance, derivation and licence.

   Why this matters: `docs/eval/wemi-fidelity.md` records that the C2 score
   is high only because the gold (`workManifested`) and the input (`f145 $3`) are
   two serialisations of the *same* BnF link — a transcription check, not evidence
   of inference — and closes with: 'Independent evaluation of bridging will need a
   gold that is **not derived from f145/workManifested**.' BIB-R is exactly that: a
   hand-curated MARC → FRBR/RDA gold with zero dependence on any link Palomar reads.

   What it measures: Palomar projects flat MARC21 to WEMI by the *floor* rung
   (`lrmoo.project`), whose Work key is `agent + norm(uniform-title when present,
   else transcribed-245-title)` — a Work is minted only when a creator is present.
   The BIB-R gold groups by the cataloguer's *uniform* work (its work URIs are
   author+uniform-title slugs), so it unifies transcribed-title **variants**,
   translations and integral/abridged editions under one Work. So this is a genuine,
   non-circular recall test of the uniform-title bridging (the MARC 240 step):
   where editions of one Work share a uniform title, the key now unifies them; the
   residual gap is the uniform titles that are themselves inconsistent or absent.

   Honest scope (stated, not hidden): the MARC `001`s carry no link to the gold's
   title-slug URIs, so records are joined to the gold **by normalised title**, and a
   record whose title the gold spreads across several works is *excluded* (the gold
   itself fragments e.g. La Fontaine's Fables across work URIs). The metric is
   therefore over the cleanly-joinable subset; the join coverage is reported as a
   first-class number, not buried."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.convert :as convert]
            [palomar.plugins.lrmoo.view :as view]
            [palomar.text :as text]))

(def ^:private marc-gz "test/fixtures/bibr-gold/bibrcat_marc21.xml.gz")
(def ^:private gold-tsv "test/fixtures/bibr-gold/work_grouping.tsv")

(defn- slurp-gz [path]
  (with-open [in (java.util.zip.GZIPInputStream. (io/input-stream path))]
    (slurp in)))

;; --- system side: Palomar's MARC21 -> WEMI clustering -----------------------

(def ^:private records
  (:records (convert/to-wemi :marc21 {} (slurp-gz marc-gz))))

(defn- canon-title [record]
  (some #(when (and (= :canon/title (:predicate %)) (string? (:value %))) (:value %))
        (:assertions record)))

(defn- work-key
  "Palomar's cluster key for a record: the minted F1 Work id (shared by records
   with the same agent+norm-title), or a per-record singleton when no Work was
   minted (no creator), so singletons never cluster together."
  [record]
  (or (:id (first (view/works record)))
      [::singleton (:id record)]))

;; --- gold side: BIB-R Work grouping -----------------------------------------

(defn- parse-gold
  "Read the gold TSV into `norm-title -> #{work-core}`. A title the gold maps to
   more than one work-core is *ambiguous* and dropped from the join (kept here so
   the ambiguity is computed, not assumed)."
  [tsv]
  (reduce (fn [m line]
            (if (or (str/blank? line) (str/starts-with? line "#"))
              m
              (let [[core title] (str/split line #"\t" 2)]
                (if (and core title)
                  (update m (text/norm title) (fnil conj #{}) core)
                  m))))
          {}
          (str/split-lines (slurp tsv))))

(def ^:private gold-by-title (parse-gold gold-tsv))

(def ^:private gold-unambiguous
  (into {} (keep (fn [[nt cores]] (when (= 1 (count cores)) [nt (first cores)])) gold-by-title)))

;; --- the join + pairwise metric ---------------------------------------------

(def ^:private joined
  "record-id -> {:gold work-core :sys palomar-work-key} for every record whose
   normalised title maps unambiguously to a single gold Work."
  (into {}
        (keep (fn [r]
                (when-let [core (gold-unambiguous (text/norm (canon-title r)))]
                  [(:id r) {:gold core :sys (work-key r)}])))
        records))

(defn- pairwise
  "Pairwise tp/fp/fn over `entries` ({:gold :sys}): a pair is a true positive when
   gold and system agree it is the same Work."
  [entries]
  (let [v (vec entries)]
    (loop [a 0, tp 0, fp 0, fn* 0]
      (if (>= a (count v))
        (let [p (if (pos? (+ tp fp)) (/ (double tp) (+ tp fp)) 1.0)
              r (if (pos? (+ tp fn*)) (/ (double tp) (+ tp fn*)) 1.0)]
          {:tp tp :fp fp :fn fn* :precision p :recall r
           :f1 (if (pos? (+ p r)) (/ (* 2 p r) (+ p r)) 0.0)})
        (recur (inc a)
               (+ tp (count (for [b (range (inc a) (count v))
                                  :when (and (= (:gold (v a)) (:gold (v b)))
                                             (= (:sys (v a)) (:sys (v b))))] 1)))
               (+ fp (count (for [b (range (inc a) (count v))
                                  :when (and (not= (:gold (v a)) (:gold (v b)))
                                             (= (:sys (v a)) (:sys (v b))))] 1)))
               (+ fn* (count (for [b (range (inc a) (count v))
                                   :when (and (= (:gold (v a)) (:gold (v b)))
                                              (not= (:sys (v a)) (:sys (v b))))] 1))))))))

(def ^:private metric (pairwise (vals joined)))

(deftest the-corpus-and-gold-load
  (testing "the full 560-record BIB-R corpus ingests and the gold loads"
    (is (= 560 (count records)))
    (is (< 200 (count gold-unambiguous) 260))))            ; 228 titles map to a single gold Work

(deftest join-coverage-is-reported-not-hidden
  (testing "records joined to the gold by normalised title (the honest coverage number)"
    ;; ~65%: a third of records have a title the gold fragments across works, or no
    ;; gold match — stated as a first-class limitation, see ns + README.
    (is (<= 340 (count joined) 380))))

(deftest precision-is-perfect-recall-is-the-floor-ceiling
  (testing "Palomar never false-merges distinct gold Works (precision = 1.0)"
    (is (= 1.0 (:precision metric))
        (str "metric=" metric)))
  (testing "recall is bounded below 1.0 — uniform-title bridging lifts it, variants remain"
    ;; The non-circular finding: P=1.0, R<1.0 on a third corpus independent of f145,
    ;; corroborating ADR 0018's recall ceiling. Uniform-title bridging (MARC 240)
    ;; raised R from 0.775 to 0.823 (409 tp, 88 fn) at no precision cost; pinned to a
    ;; band around the measured value so a regression in the projection key is caught.
    (is (< 0.80 (:recall metric) 0.86)
        (str "metric=" metric))
    (is (pos? (:fn metric)))))                              ; uniform titles are themselves inconsistent/absent for the rest

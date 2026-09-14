(ns palomar.eval.showcase-boundary-test
  "Step-zero of the audit (ADR 0016): outside the Madame Bovary showcase, the BnF
   bibliographic records in our fixtures carry no `f145 $3` Work-authority link, so
   WEMI derivation degrades to a bare F3_Manifestation — no Expression, no Work. This
   demonstrates, on real BnF data across genres, that WEMI projection beyond a
   Manifestation is concentrated in the showcase rather than general (the audit's
   C1 point). The companion bovary-c2 eval measures fidelity where the link *is*
   present; this one establishes how rarely that is."
  (:require [clojure.test :refer [deftest is testing]]
            [palomar.plugins.intermarc :as intermarc]
            [palomar.plugins.intermarc.wemi :as wemi]
            [palomar.plugins.lrmoo.view :as view]))

(def base "test/fixtures/documentary/intermarc/sru/")

(def showcase "intermarcXchange/bib-flaubert-madame-bovary-start1-max30.xml")

(def off-showcase
  ["intermarcXchange/bib-victor-hugo-start1-max30.xml"
   "intermarcXchange/bib-julien-gracq-start1-max30.xml"
   "intermarcXchange/bib-monographies-start1-max30.xml"
   "intermarcXchange/bib-periodiques-start1-max30.xml"
   "intermarcXchange/bib-youth-start1-max30.xml"
   "intermarcXchange/bib-music-recordings-start1-max30.xml"
   "intermarcXchange-anl/bib-gracq-analytiques-start1-max20.xml"
   "intermarcXchange-anl/bib-youth-analytiques-start1-max20.xml"])

(defn- derive-all [file]
  (mapv wemi/derive-wemi (intermarc/ingest (slurp (str base file)) {})))

(deftest off-showcase-yields-only-bare-manifestations
  (doseq [file off-showcase]
    (testing file
      (let [rs (derive-all file)]
        (is (seq rs))
        (is (every? #(= 1 (count (view/manifestations %))) rs))  ; one Manifestation each
        (is (every? #(empty? (view/expressions %)) rs))          ; ...no Expression
        (is (every? #(empty? (view/works %)) rs))))))            ; ...no Work

(deftest wemi-projection-is-concentrated-in-the-showcase
  (let [off       (mapcat derive-all off-showcase)
        n-off     (count off)
        exprs-off (reduce + (map #(count (view/expressions %)) off))
        works-off (reduce + (map #(count (view/works %)) off))
        bovary    (derive-all showcase)
        n-bov     (count bovary)
        expr-bov  (count (filter #(seq (view/expressions %)) bovary))
        total     (+ n-off n-bov)]
    (testing "the off-showcase genres yield no WEMI above the Manifestation"
      (is (<= 200 n-off))      ; 8 genres, ~220 records
      (is (zero? exprs-off))
      (is (zero? works-off)))
    (testing "the showcase is the only place Expression/Work appear"
      (is (= 28 expr-bov)))
    (testing "WEMI derivation beyond a Manifestation reaches a small minority overall, all one Work"
      (is (< (/ expr-bov total) 1/8)))   ; 28 / ~250 ≈ 11%
    (println (format "Showcase boundary: off-showcase recs=%d expr=%d work=%d | showcase expr=%d/%d | enriched=%d/%d (%.1f%%)"
                     n-off exprs-off works-off expr-bov n-bov
                     expr-bov total (* 100.0 (/ expr-bov total))))))

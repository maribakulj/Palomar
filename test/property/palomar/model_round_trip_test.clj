(ns palomar.model-round-trip-test
  "Generative EDN round-trip test for the canonical model.

   For every major schema we generate N samples and assert that
   `(edn/read-string (pr-str sample))` equals `sample`. This is a
   load-bearing property of the IR: it is pure data and serializes
   without loss.

   The :double leaf of `Primitive` uses `:gen/fmap` in the model to clamp
   NaN/Infinity to 0.0 at generation time, so round-trip equality holds
   for every generated sample without extra filtering."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [malli.generator :as mg]
            [palomar.model :as model]))

(def ^:private sample-count
  "Kept modest so the suite stays fast. The contract here is structural,
   not statistical: a few hundred samples per schema is ample to catch
   serialization bugs."
  200)

(defn- round-trips? [sample]
  (= sample (edn/read-string (pr-str sample))))

(defn- samples [schema seed]
  (mg/sample schema {:size 4 :seed seed}))

(deftest value-round-trip
  (doseq [sample (take sample-count (samples model/Value 1))]
    (is (round-trips? sample)
        (str "Value did not round-trip: " (pr-str sample)))))

(deftest assertion-round-trip
  (doseq [sample (take sample-count (samples model/Assertion 2))]
    (is (round-trips? sample)
        (str "Assertion did not round-trip: " (pr-str sample)))))

(deftest provenance-round-trip
  (doseq [sample (take sample-count (samples model/Provenance 3))]
    (is (round-trips? sample)
        (str "Provenance did not round-trip: " (pr-str sample)))))

(deftest fragment-round-trip
  (doseq [sample (take sample-count (samples model/Fragment 4))]
    (is (round-trips? sample)
        (str "Fragment did not round-trip: " (pr-str sample)))))

(deftest diagnostic-round-trip
  (doseq [sample (take sample-count (samples model/Diagnostic 5))]
    (is (round-trips? sample)
        (str "Diagnostic did not round-trip: " (pr-str sample)))))

(deftest record-round-trip
  (doseq [sample (take sample-count (samples model/Record 6))]
    (is (round-trips? sample)
        (str "Record did not round-trip: " (pr-str sample)))))

;; Smoke test: every generated sample is also schema-valid. Confirms that
;; our generators and validators agree.
(deftest generated-samples-are-valid
  (doseq [sample (take 50 (samples model/Record 7))]
    (is (model/valid-record? sample)
        (str "Generator produced an invalid Record: " (pr-str sample)))))

(ns palomar.smoke-test
  "Smoke test: ensures every public namespace loads without error."
  (:require [clojure.test :refer [deftest is testing]]
            [palomar.diagnostics]
            [palomar.model]
            [palomar.plugins]
            [palomar.plugins.canonical]
            [palomar.rules]
            [palomar.runtime]))

(deftest namespaces-load
  (testing "every public namespace loads"
    (is (some? (find-ns 'palomar.model)))
    (is (some? (find-ns 'palomar.rules)))
    (is (some? (find-ns 'palomar.runtime)))
    (is (some? (find-ns 'palomar.diagnostics)))
    (is (some? (find-ns 'palomar.plugins)))
    (is (some? (find-ns 'palomar.plugins.canonical)))))

(ns palomar.junit-runner
  "Custom test entry point that runs every `palomar.*-test` namespace and
   emits a JUnit XML file at `target/junit/junit.xml` alongside normal
   stdout output. Used by CI to populate GitHub's test summary panel.

   Why a custom runner: cognitect-labs/test-runner has no JUnit reporter,
   and switching to kaocha just for this would add two Clojars deps.
   `clojure.test.junit/with-junit-output` is a built-in fixture that
   does exactly the right thing in 30 lines.

   Local invocation:
       clojure -M:test/junit
   Output:
       target/junit/junit.xml
       (plus a brief summary on stdout)
   Exit code 1 on any test failure or error; 2 if no test namespaces
   are discovered (likely a misconfigured classpath)."
  (:require [clojure.java.io :as io]
            [clojure.test :as t]
            [clojure.test.junit :as junit]
            [clojure.tools.namespace.find :as ns-find]))

(def ^:private test-roots
  ["test/unit" "test/property" "test/integration"])

(def ^:private junit-output
  "target/junit/junit.xml")

(defn- discover-test-namespaces []
  (->> test-roots
       (map io/file)
       (mapcat ns-find/find-namespaces-in-dir)
       (filter #(re-matches #"palomar\..*-test" (str %)))
       sort))

(defn- run-with-junit-output [nses]
  (let [results (atom nil)]
    (with-open [w (io/writer junit-output)]
      (binding [t/*test-out* w]
        (junit/with-junit-output
          (reset! results (apply t/run-tests nses)))))
    @results))

(defn -main [& _]
  (let [nses (discover-test-namespaces)]
    (when (empty? nses)
      (binding [*out* *err*]
        (println "No palomar.*-test namespaces discovered under" test-roots))
      (System/exit 2))
    (run! require nses)
    (io/make-parents junit-output)
    (let [{:keys [test pass fail error]} (run-with-junit-output nses)]
      (println (format "Ran %d tests: %d passed, %d failed, %d errors."
                       test pass fail error))
      (println "JUnit XML written to" junit-output)
      (when (or (pos? fail) (pos? error))
        (System/exit 1)))))

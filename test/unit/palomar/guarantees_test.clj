(ns palomar.guarantees-test
  "docs/guarantees.md stays true to the code (post-audit C5): every test it
   cites exists, and the formats it lists are exactly the registered ones."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.convert :as convert]))

(def ^:private doc (slurp "docs/guarantees.md"))

(defn- listed [label]
  (set (map keyword (str/split (second (re-find (re-pattern (str "(?m)^" label ": (.*)$")) doc)) #",\s*"))))

(deftest the-listed-formats-are-the-registered-ones
  (is (= (convert/source-formats) (listed "Source formats")))
  (is (= (convert/target-formats) (listed "Target formats"))))

(deftest every-cited-test-exists
  (let [cited (distinct (re-seq #"palomar\.[a-z0-9.-]+-test/[a-z0-9?!-]+" doc))]
    (is (< 20 (count cited)))
    (doseq [c cited
            :let [[ns-name v] (str/split c #"/")]]
      (testing c
        (is (some? (do (require (symbol ns-name)) (resolve (symbol ns-name v))))
            (str c " is cited by docs/guarantees.md but does not exist"))))))

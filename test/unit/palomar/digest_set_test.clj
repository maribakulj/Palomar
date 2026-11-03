(ns palomar.digest-set-test
  (:require [clojure.test :refer [deftest is testing]]
            [palomar.digest-set :as ds]))

(deftest a-million-ids-in-sixteen-megabytes
  (testing "a million distinct ids: none is said seen, each is the second time, in 2²¹ slots of 8 bytes (post-audit 7, I3)"
    (let [s (ds/create)
          n 1000000]
      (is (not-any? #(ds/seen! s (str "DE-MUS-000000/lido/" %)) (range n)))
      (is (= n (ds/size s)))
      (is (<= (ds/capacity s) (bit-shift-left 1 21)) "16 MB at most")
      (is (every? #(ds/seen! s (str "DE-MUS-000000/lido/" %)) (range 0 n 997)))
      (is (= n (ds/size s)) "a duplicate adds nothing"))))

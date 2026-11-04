(require '[palomar.convert :as c] '[palomar.plugins.lido-rules :as lr] '[palomar.runtime :as rt] '[clojure.java.io :as io])
(def dir (first *command-line-args*))
(doseq [d (.listFiles (io/file dir "pairs")) :when (.isDirectory d)]
  (let [src (slurp (io/file d "lido.xml"))
        out (c/convert {:from :oai :opts {:metadata-prefix "lido"} :to :linked-art :source src})
        recs (:records (c/to-wemi :oai {:metadata-prefix "lido"} src))
        ruled (distinct (for [r recs p (:productions (rt/run-phase r lr/compiled :infer)) :when (= :assertion (:kind p))] (:value p)))]
    (spit (io/file d "palomar-la.json") (:output out))
    (spit (io/file d "palomar-rules.edn") (pr-str {:depicted (count (filter #(= :crm/P62_depicts (:predicate %)) ruled))
                                                   :about (count (filter #(= :crm/P129_is_about (:predicate %)) ruled))
                                                   :hand-depicts (count (filter #(= :crm/P62_depicts (:predicate %)) (mapcat :assertions recs)))}))))
(println "done")

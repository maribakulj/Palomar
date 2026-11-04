;; Palomar's side of the YCBA comparison: each pair's LIDO converted to Linked
;; Art, written beside it as `<name>` (default palomar-la.json), so that two
;; versions of Palomar can be compared on the same pairs.
;;   clojure -M dev/eval/ycba/convert.clj <dir> [name]
(require '[palomar.convert :as c] '[clojure.java.io :as io])
(let [[dir nm] *command-line-args*
      nm (or nm "palomar-la.json")]
  (doseq [d (.listFiles (io/file dir "pairs")) :when (.isDirectory d)]
    (let [src (slurp (io/file d "lido.xml"))
          out (try (:output (c/convert {:from :oai :opts {:metadata-prefix "lido"} :to :linked-art :source src}))
                   (catch Exception e (str "{\"error\": " (pr-str (.getMessage e)) "}")))]
      (spit (io/file d nm) out)))
  (println "done"))

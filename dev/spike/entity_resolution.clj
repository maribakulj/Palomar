;; Spike (throwaway, post-WP-0): how far does *offline* entity resolution get us
;; toward the recall gap the showcase-boundary eval measured (11.2% enriched)?
;;
;; Decisive experiment: on the Madame Bovary fixture we now have data.bnf.fr
;; ground truth (28 manifestations -> 1 Work). Replay several keying strategies
;; that DO NOT use the f145_3 authority link, and score each against the gold.
;; This quantifies the precision/recall trade-off of string-based clustering and
;; tells us what an entity-resolution ADR must actually buy.
;;
;; Run: clojure -M:sandbox -i dev/spike/entity_resolution.clj
(require '[clojure.string :as str]
         '[palomar.plugins.intermarc :as im]
         '[palomar.text :as text])

(defn one [r p] (->> (:assertions r) (filter #(= p (:predicate %))) first :value))

(def norm text/norm)   ; single shared normalisation (audit R1)

(defn title-prefix [s n] (->> (str/split (norm s) #" ") (take n) (str/join " ")))

;; --- keying strategies (none uses f145_3) ----------------------------------
(defn k-link        [r] (one r :intermarc/f145_3))                        ; the authority link (T0 baseline)
(defn k-author      [r] (or (one r :intermarc/f100_3) (one r :intermarc/f100_a)))
(defn k-auth+title  [r] (when-let [a (k-author r)] (str a "|" (norm (one r :intermarc/f245_a)))))
(defn k-auth+pfx2   [r] (when-let [a (k-author r)] (str a "|" (title-prefix (one r :intermarc/f245_a) 2))))

;; --- gold + pairwise precision/recall ---------------------------------------
(defn parse-gold [csv]
  (->> (rest (str/split-lines (slurp csv)))
       (remove str/blank?)
       (map #(let [c (mapv (fn [x] (str/replace x #"^\"|\"$" "")) (str/split % #"\",\""))]
               [(first c) (nth c 3)]))
       (into {})))

(defn pairs [v] (for [i (range (count v)) j (range (inc i) (count v))] [(nth v i) (nth v j)]))

(defn pr-vs-gold [arks gold-of pred-of]
  (let [ps (pairs (vec arks))
        jn (fn [f [a b]] (= (f a) (f b)))
        tp (count (filter #(and (jn gold-of %) (jn pred-of %)) ps))
        fp (count (filter #(and (not (jn gold-of %)) (jn pred-of %)) ps))
        fnn (count (filter #(and (jn gold-of %) (not (jn pred-of %))) ps))
        p  (if (pos? (+ tp fp)) (/ tp (+ tp fp)) 1)
        rc (if (pos? (+ tp fnn)) (/ tp (+ tp fnn)) 1)]
    {:tp tp :fp fp :fn fnn :p (double p) :r (double rc)
     :f1 (double (if (pos? (+ p rc)) (/ (* 2 p rc) (+ p rc)) 0))}))

;; --- experiment 1: Bovary strategies vs gold --------------------------------
(def base "test/fixtures/documentary/intermarc/sru/")
(def bov (im/ingest (slurp (str base "intermarcXchange/bib-flaubert-madame-bovary-start1-max30.xml")) {}))
(def gold (parse-gold "test/fixtures/c2-gold/bovary/workmanifested.csv"))
(def by-ark (into {} (map (juxt :source identity)) bov))
(def arks (keys by-ark))
(defn gold-of [ark] (get gold ark ark))
(defn n-clusters [keyfn] (count (distinct (map (fn [a] (or (keyfn (by-ark a)) a)) arks))))

(println "=== Experiment 1: Madame Bovary — keying strategies vs data.bnf gold (28->1 Work) ===")
(println (format "%-22s %8s %5s %5s %5s %8s %8s %8s" "strategy" "clusters" "tp" "fp" "fn" "prec" "recall" "f1"))
(doseq [[name keyfn]
        [["link (f145_3) [T0]" k-link]
         ["author-only"        k-author]
         ["author+norm(title)" k-auth+title]
         ["author+title-pfx2"  k-auth+pfx2]]]
  (let [pred-of (fn [a] (let [k (keyfn (by-ark a))] (if k [:k k] [:singleton a])))
        m (pr-vs-gold arks gold-of pred-of)]
    (println (format "%-22s %8d %5d %5d %5d %8.3f %8.3f %8.3f"
                     name (n-clusters keyfn) (:tp m) (:fp m) (:fn m) (:p m) (:r m) (:f1 m)))))

(println "\n  gold clusters:" (count (distinct (vals gold))) " | distinct norm(f245_a) titles among the 28 linked:"
         (count (distinct (->> bov (filter #(= "11938746" (one % :intermarc/f145_3)))
                               (map #(norm (one % :intermarc/f245_a)))))))

;; --- experiment 2: off-showcase shape (no ground truth) ---------------------
(println "\n=== Experiment 2: off-showcase in-corpus clusters by author-id + norm(title) ===")
(doseq [f ["intermarcXchange/bib-victor-hugo-start1-max30.xml"
           "intermarcXchange/bib-julien-gracq-start1-max30.xml"
           "intermarcXchange-anl/bib-gracq-analytiques-start1-max20.xml"
           "intermarcXchange/bib-monographies-start1-max30.xml"]]
  (let [rs    (im/ingest (slurp (str base f)) {})
        elig  (filter #(and (one % :intermarc/f100_3) (one % :intermarc/f245_a)) rs)
        cls   (group-by k-auth+title elig)
        multi (filter #(> (count (val %)) 1) cls)]
    (println (format "%-40s n=%2d eligible=%2d clusters=%2d multi-edition=%d"
                     (str/replace f #".*/" "") (count rs) (count elig) (count cls) (count multi)))
    (doseq [[_ recs] multi]
      (println (format "     x%d  %s | author=%s"
                       (count recs) (norm (one (first recs) :intermarc/f245_a))
                       (one (first recs) :intermarc/f100_3))))))

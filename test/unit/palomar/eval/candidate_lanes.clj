(ns palomar.eval.candidate-lanes
  "Candidate generation per lane on the person-reconciliation benchmarks
   (lot N5, `palomar.eval.candidate-lanes-test`,
   `docs/eval/person-reconciliation.md`, section N5).

   **Recall of generation** is the share of mentions whose truth is among
   their candidates, before any ranking. It is measured per lane and for
   the union (`candidates/recall`), with the candidate count as the cost.

   The benchmarks:
   - **M2, BnF**: the 142 mentions of `palomar.eval.person-reconciliation`
     against the 33 BnF authorities, each entry with its ARK as `:iri` and
     its ISNIs (`lane-entries`). The truth is `$3`.
   - **M6, GND**: the same mentions against the 197 GND records of the
     authority index, the truth the bridged GND records (uncontested).
   - **Stress (synthetic)**: M4's perturbed names (`pr/stress-set`), each
     carrying the ids of the gold mention it was made from.
   - **Script (synthetic)**: each distinct gold heading written in Cyrillic
     letters, carrying its gold mention's ids.
   - **M7, LIDO**: the YCBA actors against their ULAN ids.

   **What a mention passes.** `lane-mention`: its name, its dates, its
   `:ids` and `:unread-ids`. An INTERMARC `$3` is the gold: it is the
   mention's `:authority-record`, which no lane reads and no mention passed
   here carries (N5 review). Its `:ids` are its `$1`, an ISNI the
   cataloguer wrote, matched against the ISNIs the authority's `031`
   states. The truth is passed apart (`candidates/recall`'s `:truths`),
   never on a mention. On LIDO the pool is built from the actors' ULAN
   ids: the identifier lane's recall there is circular."
  (:require [clojure.string :as str]
            [palomar.agent-dates :as dates]
            [palomar.candidates :as candidates]
            [palomar.eval.person-reconciliation :as pr]
            [palomar.mentions :as mentions]
            [palomar.plugins.authorities :as authorities]))

(defn lane-entries
  "The M2 pool as the lanes read it: `pr`'s dated entries (`{:id :label
   :variants :dates}`, `:id` the record number, the form of `$3`), in the
   same order, each with its ARK as `:iri` and its ISNIs (`031`), from
   `authorities/read-pool :intermarc`."
  [pool]
  (let [by-n (into {} (for [e (:entries (authorities/read-pool :intermarc (slurp pr/authority-file)))]
                        [(:record-number e) e]))]
    (vec (for [e (pr/dated-entries pool)
               :let [a (by-n (:id e))]]
           (cond-> (assoc e :iri (:id a))
             (seq (:isni a)) (assoc :isni (:isni a)))))))

(defn lane-mention
  "Mention `m` as the lanes read it: `{:name :dates :ids :unread-ids}`.
   Never its `:authority-record` (`$3`), its truth or its subfields."
  [m]
  (select-keys m [:name :dates :ids :unread-ids]))

(defn- run [idx ms truths]
  (candidates/recall idx (map lane-mention ms) {:lanes [:name :identifier] :truths truths}))

(defn m2-recall
  "Recall per lane on M2."
  [c]
  (run (candidates/index (lane-entries (:pool c))) (:mentions c) (map :truth (:mentions c))))

(defn gnd-truth
  "A mention's GND truth: the bridged GND ids `truths` give its `$3`
   (`gnd-person-reconciliation-test/truth`), nil when contested."
  [truths m]
  (let [t (truths (:truth m))] (when (and t (not (:contested? t))) (:gnd t))))

(defn gnd-recall
  "Recall per lane on M6: the mentions against authority index `idx` (read),
   the truth the bridged GND ids `truths` give (contested ones count for
   nothing)."
  [c idx truths]
  (run (candidates/index idx) (:mentions c) (map (partial gnd-truth truths) (:mentions c))))

(defn- gold-ids
  "The ids of the gold mentions, by `[name dates truth]`: those of the first
   that has any."
  [c]
  (reduce (fn [acc m] (let [k [(:name m) (:dates m) (:truth m)]]
                        (if (or (contains? acc k) (empty? (:ids m))) acc (assoc acc k (:ids m)))))
          {} (:mentions c)))

(defn stress-recall
  "SYNTHETIC. Recall per lane on `pr/stress-set`, each variant carrying its
   gold mention's ids: `{kind recall}`, `:all` over every kind."
  [c]
  (let [idx  (candidates/index (lane-entries (:pool c)))
        ids  (gold-ids c)
        rows (for [s (pr/stress-set c)
                   :let [i (ids [(:source s) (:dates s) (:truth s)])]]
               (cond-> (select-keys s [:name :dates :truth :kind]) i (assoc :ids i)))]
    (into (sorted-map)
          (for [[k xs] (assoc (group-by :kind rows) :all rows)]
            [k (run idx xs (map :truth xs))]))))

(def ^:private cyrillic
  "A plain letter-by-letter transliteration into Cyrillic (synthetic)."
  (merge (zipmap "abcdefghijklmnopqrstuvwxyz" (map str "абкдефгхийклмнопкрстувшксз"))
         (zipmap "ABCDEFGHIJKLMNOPQRSTUVWXYZ" (map str "АБКДЕФГХИЙКЛМНОПКРСТУВШКСЗ"))))

(defn to-cyrillic
  "SYNTHETIC. `s` with its Latin letters written in Cyrillic, accents dropped."
  [s]
  (apply str (map #(get cyrillic % %)
                  (str/replace (java.text.Normalizer/normalize s java.text.Normalizer$Form/NFD) #"\p{M}+" ""))))

(defn script-set
  "SYNTHETIC. Each distinct (name, dates, truth) of the gold mentions whose
   authority is in the pool, its name in Cyrillic (`to-cyrillic`), with its
   gold mention's ids (its ISNI `$1`): `[{:name :dates :ids :truth}]`."
  [c]
  (let [pool (set (map :id (:pool c)))
        ids  (gold-ids c)]
    (vec (for [[[n d t] _] (sort-by (comp pr-str key) (group-by (juxt :name :dates :truth) (filter #(pool (:truth %)) (:mentions c))))]
           (cond-> {:name (to-cyrillic n) :dates d :truth t}
             (seq (ids [n d t])) (assoc :ids (ids [n d t])))))))

(defn script-recall
  "SYNTHETIC. Recall per lane on `script-set`."
  [c]
  (let [rows (script-set c)]
    (run (candidates/index (lane-entries (:pool c))) rows (map :truth rows))))

(defn lido-recall
  "Recall per lane on M7's LIDO actors (`pr/lido-event-run`'s mentions and
   pool, rebuilt here): each person actor with a ULAN id, against one entry
   per ULAN id, labelled by the name most of its occurrences give. The
   truth is the ULAN id the actor carries, which is also the entry's id:
   circular for the identifier lane."
  [records]
  (let [ms   (for [m (mentions/batch records)
                   :let [u (first (filter #(str/starts-with? % pr/ulan-prefix) (:ids m)))]
                   :when (and u (= "person" (:actor-type m)))]
               (assoc (select-keys m [:name :ids]) :truth u))
        mode (fn [xs] (key (first (sort-by (juxt (comp - val) (comp str key)) (frequencies xs)))))
        pool (vec (for [[u xs] (sort-by key (group-by :truth ms))
                        :let [ds (filter dates/dated? (map :dates xs))]]
                    (cond-> {:id u :label (mode (map :name xs)) :variants []}
                      (seq ds) (assoc :dates (mode ds)))))]
    (run (candidates/index pool) ms (map :truth ms))))

(defn print-recall
  "Print recall `x` of benchmark `label`."
  [label x]
  (println (format "%s: %d judged | name %d/%d cands | id %d/%d cands | union %d/%d cands | only %s"
                   label (:in-index x)
                   (get-in x [:lanes :name :found]) (get-in x [:lanes :name :candidates])
                   (get-in x [:lanes :identifier :found]) (get-in x [:lanes :identifier :candidates])
                   (get-in x [:union :found]) (get-in x [:union :candidates]) (pr-str (:only x)))))

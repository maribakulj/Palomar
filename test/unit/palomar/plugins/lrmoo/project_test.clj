(ns palomar.plugins.lrmoo.project-test
  "Unit tests for the canonical→WEMI projection (the generic pivot, ADR 0013).
   Built on flat `:canon/*` records carrying no source-format predicate at all —
   so these tests are themselves the evidence that the projection reads only the
   canonical floor, not INTERMARC."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.diagnostics :as dx]
            [palomar.model :as model]
            [palomar.plugins.lrmoo.export :as export]
            [palomar.plugins.lrmoo.project :as project]
            [palomar.plugins.lrmoo.view :as view]))

(defn- canon
  "A canonical record `id` carrying flat literal `:canon/*` pairs."
  [id & pairs]
  (model/record {:id id :kind :document
                 :assertions (vec (for [[p v] (partition 2 pairs)]
                                    (model/assertion {:subject id :predicate p :value v})))}))

(deftest agent-and-title-yield-a-full-wemi-chain
  (let [r (project/project (canon :record/a :canon/agent "Victor Hugo"
                                  :canon/title "Les Misérables"))]
    (is (model/valid-record? r))
    (is (model/record-consistent? r))
    (is (= 1 (count (view/manifestations r))))
    (is (= 1 (count (view/expressions r))))
    (is (= 1 (count (view/works r))))
    (testing "Work -R3-> Expression <-R4- Manifestation, both directions"
      (let [w (:id (first (view/works r)))
            e (:id (first (view/expressions r)))
            m (:id (first (view/manifestations r)))]
        (is (= [e] (view/expressions-of r w)))
        (is (= [w] (view/work-of r e)))
        (is (= [e] (view/expression-of r m)))
        (is (= [m] (view/manifestations-of r e)))))))

(deftest title-only-yields-manifestation-and-expression-no-work
  (testing "without a creator there is no Work key, but the W-less E-M chain stands"
    (let [r (project/project (canon :record/t :canon/title "Anonymous Chronicle"))]
      (is (= 1 (count (view/manifestations r))))
      (is (= 1 (count (view/expressions r))))
      (is (empty? (view/works r))))))

(deftest empty-record-yields-a-bare-manifestation
  (testing "no canonical title -> only the Manifestation (the record itself)"
    (let [r (project/project (canon :record/e :canon/note "just a note"))]
      (is (= 1 (count (view/manifestations r))))
      (is (empty? (view/expressions r)))
      (is (empty? (view/works r))))))

(deftest clustering-is-content-deterministic-no-batch-state
  (testing "two records with the same creator+title collapse to one Work and Expression (ADR 0008)"
    (let [a (project/project (canon :record/a :canon/agent "Victor Hugo" :canon/title "Les Misérables"))
          b (project/project (canon :record/b :canon/agent "Victor Hugo" :canon/title "Les Misérables"))
          c (project/project (canon :record/c :canon/agent "Anonyme"     :canon/title "Les Misérables"))]
      (is (= (:id (first (view/works a))) (:id (first (view/works b)))))        ; same Work
      (is (= (:id (first (view/expressions a))) (:id (first (view/expressions b))))) ; same Expression
      (is (not= (:id (first (view/manifestations a)))                          ; distinct Manifestations
                (:id (first (view/manifestations b)))))
      (testing "a different creator is a different Work"
        (is (not= (:id (first (view/works a))) (:id (first (view/works c)))))))))

(deftest uniform-title-bridges-transcribed-variants-to-one-work
  (testing "two editions with different transcribed titles but the same uniform title cluster to one Work"
    (let [a (project/project (canon :record/a :canon/agent "Jean de La Fontaine"
                                    :canon/title "Fables"
                                    :canon/uniform-title "Les fables de La Fontaine"))
          b (project/project (canon :record/b :canon/agent "Jean de La Fontaine"
                                    :canon/title "Les plus belles fables"
                                    :canon/uniform-title "Les fables de La Fontaine"))]
      (is (= (:id (first (view/works a))) (:id (first (view/works b)))))          ; same Work (bridged)
      (testing "the Manifestation keeps its transcribed title; the Work carries the uniform title"
        (let [manif (:id (first (view/manifestations a)))
              work  (:id (first (view/works a)))]
          (is (some #(and (= manif (:subject %)) (= :lrmoo/R33_has_string (:predicate %))
                          (= "Fables" (:value %)))
                    (:assertions a)))
          (is (some #(and (= work (:subject %)) (= :lrmoo/R33_has_string (:predicate %))
                          (= "Les fables de La Fontaine" (:value %)))
                    (:assertions a)))))))
  (testing "without a uniform title the key falls back to the transcribed title — variants split (the gap bridging closes)"
    (let [a (project/project (canon :record/a :canon/agent "Hugo" :canon/title "Les Misérables"))
          b (project/project (canon :record/b :canon/agent "Hugo" :canon/title "Les Misérables : texte intégral"))]
      (is (not= (:id (first (view/works a))) (:id (first (view/works b)))))))
  (testing "the same transcribed title under different uniform titles are different Works"
    (let [a (project/project (canon :record/a :canon/agent "X" :canon/title "Contes"
                                    :canon/uniform-title "Contes de Perrault"))
          b (project/project (canon :record/b :canon/agent "X" :canon/title "Contes"
                                    :canon/uniform-title "Contes de Grimm"))]
      (is (not= (:id (first (view/works a))) (:id (first (view/works b)))))))
  (testing "uniform-title is a mapped field — not reported as dropped loss"
    (let [r      (project/project (canon :record/u :canon/agent "Hugo" :canon/title "X"
                                         :canon/uniform-title "Y"))
          fields (set (map #(get-in % [:detail :loss/source-field]) (dx/losses (:diagnostics r))))]
      (is (not (contains? fields :canon/uniform-title))))))

(deftest uniform-title-only-projects-and-keeps-loss-accounting-consistent
  (testing "a uniform title but NO transcribed title still mints a full chain (titled by uniform)"
    (let [p (project/project (canon :record/u :canon/uniform-title "Fables" :canon/agent "La Fontaine"))]
      (is (model/valid-record? p))
      (is (model/record-consistent? p))
      (is (= 1 (count (view/works p))))
      (is (= 1 (count (view/expressions p))))
      (is (= 1 (count (view/manifestations p))))
      (testing "the Manifestation has no transcribed R33 (none was given); the Work carries the uniform title"
        (let [manif (:id (first (view/manifestations p)))
              work  (:id (first (view/works p)))]
          (is (not (some #(and (= manif (:subject %)) (= :lrmoo/R33_has_string (:predicate %)))
                         (:assertions p))))
          (is (some #(and (= work (:subject %)) (= :lrmoo/R33_has_string (:predicate %))
                          (= "Fables" (:value %)))
                    (:assertions p)))))))
  (testing "language loss is reported for the minted Expression even when the title is uniform-only"
    ;; regression guard: language-losses keys on the Expression-minting condition
    ;; (work-title), not on the transcribed :canon/title — else this loss is dropped.
    (let [p  (project/project (canon :record/m :canon/uniform-title "Fables" :canon/agent "LF"
                                     :canon/lang "fr" :canon/lang "en"))
          us (filterv #(= :loss/under-specified (:code %)) (:diagnostics p))]
      (is (= 1 (count us)))
      (is (= :canon/lang (get-in (first us) [:detail :loss/source-field])))))
  (testing "a uniform title pre-empts an uncertain transcribed title — no FALSE :ambiguity-collapsed"
    (let [r (model/record {:id :record/a :kind :document
                           :assertions [(model/assertion {:subject :record/a :predicate :canon/agent :value "LF"})
                                        (model/assertion {:subject :record/a :predicate :canon/uniform-title :value "Fables"})
                                        (model/assertion {:subject :record/a :predicate :canon/title
                                                          :value (model/uncertain ["Fable" "Fables choisies"])})]})
          p (project/project r)]
      (is (empty? (filterv #(= :loss/ambiguity-collapsed (:code %)) (:diagnostics p))))
      (is (some #(and (= :lrmoo/R33_has_string (:predicate %)) (= "Fables" (:value %)))
                (:assertions p))))))

(deftest reads-a-title-that-lives-on-a-qualified-fragment
  (testing "the projection finds the title literal whether flat or on a fragment (shape+mapping shape)"
    (let [r (model/record
             {:id :record/q :kind :document
              :fragments  [(model/fragment {:id :frag/q.title :source "dc:title[0]"})]
              :assertions [(model/assertion {:subject :record/q :predicate :canon/title
                                             :value (model/reference :frag/q.title)})
                           (model/assertion {:subject :frag/q.title :predicate :canon/title
                                             :value "Notre-Dame de Paris"})
                           (model/assertion {:subject :record/q :predicate :canon/agent
                                             :value "Victor Hugo"})]})
          p (project/project r)
          w (first (view/works p))]
      (is (some? w))
      (is (some #(and (= (:id w) (:subject %))
                      (= :lrmoo/R33_has_string (:predicate %))
                      (= "Notre-Dame de Paris" (:value %)))
                (:assertions p))))))

(deftest unmapped-canonical-fields-are-reported-as-dropped-loss
  (let [r  (project/project (canon :record/l :canon/agent "Hugo" :canon/title "X"
                                   :canon/date "1862" :canon/note "a note"))
        ls (dx/losses (:diagnostics r))]
    (is (seq ls))
    (is (every? #(= :loss/dropped (:code %)) ls))
    (is (every? #(= :import (get-in % [:detail :loss/edge])) ls))
    (let [fields (set (map #(get-in % [:detail :loss/source-field]) ls))]
      (is (contains? fields :canon/date))
      (is (contains? fields :canon/note))
      (is (not (contains? fields :canon/title)))   ; mapped -> not loss
      (is (not (contains? fields :canon/agent))))
    (testing "coverage reflects mapped vs total canonical fields"
      (let [c (project/coverage (canon :record/l :canon/agent "Hugo" :canon/title "X"
                                       :canon/date "1862" :canon/note "a note"))]
        (is (= 2 (:mapped c)))
        (is (= 4 (:total c)))))))

(defn- multilingual
  "A canonical record with `langs` parallel-language titles on fragments."
  [id agent & title+langs]
  (let [frags (map-indexed (fn [i _] (keyword "frag" (str (name id) "." i)))
                           (partition 2 title+langs))]
    (model/record
     {:id id :kind :document
      :fragments  (mapv #(model/fragment {:id % :source "dc:title"}) frags)
      :assertions (into [(model/assertion {:subject id :predicate :canon/agent :value agent})]
                        (mapcat (fn [frag [title lang]]
                                  [(model/assertion {:subject frag :predicate :canon/title :value title})
                                   (model/assertion {:subject frag :predicate :canon/lang :value lang})])
                                frags (partition 2 title+langs)))})))

(deftest parallel-languages-under-specify-the-single-expression
  (testing "two language titles imply two Expressions; the floor mints one -> :under-specified"
    (let [p  (project/project (multilingual :record/m "Victor Hugo"
                                            "Les Misérables" "fr" "The Wretched" "en"))
          us (filterv #(= :loss/under-specified (:code %)) (:diagnostics p))]
      (is (= 1 (count (view/expressions p))))       ; the floor collapses to one
      (is (= 1 (count us)))
      (is (= :canon/lang (get-in (first us) [:detail :loss/source-field])))
      (is (= :import     (get-in (first us) [:detail :loss/edge]))))))

(deftest a-single-language-is-a-plain-drop-not-under-specified
  (testing "one language is not carried onto the Expression -> :dropped, not :under-specified"
    (let [p     (project/project (multilingual :record/s "Hugo" "Les Misérables" "fr"))
          codes (set (map :code (dx/losses (:diagnostics p))))]
      (is (contains? codes :loss/dropped))
      (is (not (contains? codes :loss/under-specified)))
      (is (some #(and (= :loss/dropped (:code %))
                      (= :canon/lang (get-in % [:detail :loss/source-field])))
                (dx/losses (:diagnostics p)))))))

(deftest uncertain-title-collapses-with-an-ambiguity-loss
  (testing "an uncertain :canon/title (ADR 0001 multiplicity) is collapsed to one -> :ambiguity-collapsed"
    (let [r (model/record
             {:id :record/u :kind :document
              :assertions [(model/assertion {:subject :record/u :predicate :canon/agent
                                             :value "Victor Hugo"})
                           (model/assertion {:subject :record/u :predicate :canon/title
                                             :value (model/uncertain ["Les Misérables" "Les Miserables"])})]})
          p  (project/project r)
          ac (filterv #(= :loss/ambiguity-collapsed (:code %)) (:diagnostics p))]
      (testing "the title is no longer silently skipped — an Expression is minted"
        (is (= 1 (count (view/expressions p)))))
      (is (= 1 (count ac)))
      (is (= :canon/title (get-in (first ac) [:detail :loss/source-field])))
      (is (= :import (get-in (first ac) [:detail :loss/edge])))
      (testing "the chosen alternative lands on the Expression as R33"
        (is (some #(and (= :lrmoo/R33_has_string (:predicate %))
                        (= "Les Misérables" (:value %)))
                  (:assertions p)))))))

(deftest exports-to-rdf
  (testing "the projected WEMI graph serialises as N-Triples (F3/F2/F1 + R4/R3)"
    (let [nt (export/->ntriples
              (project/project (canon :record/x :canon/agent "Hugo" :canon/title "Les Misérables")))]
      (is (str/includes? nt "lrmoo/F3_Manifestation"))
      (is (str/includes? nt "lrmoo/F2_Expression"))
      (is (str/includes? nt "lrmoo/F1_Work"))
      (is (str/includes? nt "lrmoo/R4_embodies"))
      (is (str/includes? nt "lrmoo/R3_is_realised_in")))))

(deftest the-floor-certifies-only-transcription
  (testing "the Manifestation title is transcription (:asserted); the string-key WEMI is :proposed (D7)"
    (let [r         (project/project (canon :record/p :canon/agent "Hugo" :canon/title "Les Misérables"))
          manif     (:id (first (view/manifestations r)))
          manif-r33 (filter #(and (= :lrmoo/R33_has_string (:predicate %)) (= manif (:subject %)))
                            (:assertions r))
          inferred  (filter #(and (= "lrmoo" (namespace (:predicate %))) (not= manif (:subject %)))
                            (:assertions r))]
      (is (seq manif-r33))
      (is (every? model/asserted? manif-r33))      ; the record's own title is certified
      (is (seq inferred))
      (is (every? model/proposed? inferred)))      ; Expression/Work string-key inference is proposed
    (testing "certified-only export keeps the Manifestation + its title, drops the proposed Work/Expression"
      (let [nt (export/->ntriples (project/project (canon :record/p :canon/agent "Hugo" :canon/title "Les Misérables"))
                                  {:certified-only? true})]
        (is (str/includes? nt "lrmoo/F3_Manifestation"))
        (is (str/includes? nt "Les Mis"))
        (is (not (str/includes? nt "lrmoo/F1_Work")))
        (is (not (str/includes? nt "lrmoo/F2_Expression")))))))

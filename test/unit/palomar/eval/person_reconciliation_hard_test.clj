(ns palomar.eval.person-reconciliation-hard-test
  "The hard person-reconciliation benchmark (lot N3,
   `docs/eval/person-reconciliation-hard.md`): transcribed mentions (INTERMARC
   245 `$f`/`$g`, and role clauses of `$a`) against the authority set of the
   whole corpus. Pins the splitter on real 245 strings of train records
   (a record none of whose scored mentions is in dev; a string marked
   synthetic where train has none), the labelling rule, the hand labels,
   the split by person, the committed benchmark and split files, the freeze
   of the rules, and the current engines' numbers on dev. The test split
   is never scored here."
  (:require [clojure.test :refer [deftest is testing]]
            [palomar.eval.person-reconciliation-hard :as hard]))

(defn- texts [ms] (mapv (juxt :text :role :status) ms))

(defn- read-sfs [sfs] (texts (hard/statement-mentions sfs)))

;; ---------------------------------------------------------------------------
;; The splitter, on real 245 strings of train records
;; ---------------------------------------------------------------------------

(deftest the-splitter-cuts-a-statement-into-transcribed-mentions
  (testing "a title sentence is no mention; an English role phrase is one (cb35215179c)"
    (is (= [["Emily Brontë" nil :mention] ["Anne Brontë" nil :mention]]
           (read-sfs [["a" "Wuthering Heights"] ["f" "Emily Brontë. Agnes Grey"] ["f" "Anne Brontë"]]))))
  (testing "a forename alone before \"et\" shares a surname: incomplete (cb443320192)"
    (is (= [["R." nil :incomplete] ["R. Borel-Rosny" nil :mention]]
           (read-sfs [["f" "R. et R. Borel-Rosny"]]))))
  (testing "a leading \"par\", a trailing \",...\" (cb312476408), a [sic] and a foreign role (cb35578421c)"
    (is (= [["J.-H. Rosny aîné" "par" :mention]] (read-sfs [["f" "par J.-H. Rosny aîné,..."]])))
    (is (= [["Edgard Poe" nil :mention] ["Matteu Cirnensi" "trad. di" :mention]]
           (read-sfs [["a" "U corbu"] ["f" "Edgard [sic] Poe"] ["g" "trad. di Matteu Cirnensi"]]))))
  (testing "$a: role clauses after the title (cb32536184q); a \"de\" in the title is no role (cb47502920w)"
    (is (= [["Ch. Baudelaire" "Traduction de" :mention] ["Carlo Farneti" "Eaux-fortes à la manière noire de" :mention]
            ["Edgar Allan Poe" nil :mention]]
           (read-sfs [["a" "Histoires extraordinaires. Traduction de Ch. Baudelaire. Eaux-fortes à la manière noire de Carlo Farneti"]
                      ["f" "Edgar Allan Poe"]])))
    (is (= [] (read-sfs [["a" "Les Bretagnes de Pierre Loti"]]))))
  (testing "an inverted SURNAME (Forename) is put in natural order (cb378857920)"
    (is (= [["Lewis CARROLL" nil :mention] ["Carlo COLLODI" nil :mention]]
           (read-sfs [["a" "ALICE AU PAYS DES MERVEILLES"] ["f" "CARROLL (Lewis)"] ["f" "COLLODI (Carlo)"]]))))
  (testing "each mention says where it was read"
    (is (= [["f" 0 0] ["f" 1 0]]
           (mapv (juxt :subfield :occurrence :piece)
                 (hard/statement-mentions [["f" "CARROLL (Lewis)"] ["f" "COLLODI (Carlo)"]]))))))

(deftest the-splitter-s-failure-classes-are-fixed
  (testing "honorifics are dropped wherever they stand (cb349458628, cb32433537d, cb33158988n)"
    (is (= [["Pierre Loti" nil :mention] ["Yves La Prairie" "préf. de" :mention] ["Pierre Loti-Viaud" "introd. de" :mention]]
           (read-sfs [["f" "Pierre Loti"] ["g" "préf. de M. Yves La Prairie..."] ["g" "introd. de M. Pierre Loti-Viaud,..."]])))
    (is (= [["Simone de Caillavet" nil :mention] ["Anatole France" "préface de" :mention]]
           (read-sfs [["f" "Simone de Caillavet"] ["g" "préface de M. Anatole France,..."]])))
    (is (= [["Robert" "par" :incomplete] ["Robert Borel-Rosny" "par" :mention]]
           (read-sfs [["g" "[par Robert et Mme Robert Borel-Rosny]"]])))
    (is (= [["Henri Marchand" nil :mention]] (read-sfs [["f" "Dr Henri Marchand"]]))))
  (testing "a role noun is a whole word: no forename is eaten (synthetic: train has no such string)"
    (is (= [["Edith Wharton" nil :mention]] (read-sfs [["f" "Edith Wharton"]])))
    (is (= [["Publius Syrus" nil :mention]] (read-sfs [["f" "Publius Syrus"]])))
    (is (= [["Withers" nil :incomplete] ["Ornella Volta" nil :mention]] (read-sfs [["f" "Withers, Ornella Volta"]]))))
  (testing "a sentence is cut before a capitalised role noun, whatever the word before (cb31126398x)"
    (is (= [["Charles Simond" "Traduction de" :mention] ["Edgar Poe" nil :mention]]
           (read-sfs [["a" "Aventures de Gordon Pym. Traduction de Charles Simond"] ["f" "Edgar Poe"]]))))
  (testing "engravers' Latin is dropped (cb44545513n, cb44545511z); \"fil.\" ends a name"
    (is (= [["Daudet fil" nil :mention]] (read-sfs [["f" "Daudet fil. fecit"]])))
    (is (= [["B." nil :incomplete] ["Daudet" nil :mention]]
           (read-sfs [["f" "B. delineavit"] ["g" "Daudet Scul. Lugd. 1731"]])))
    (testing "and \"auctore\" introduces a name (synthetic)"
      (is (= [["Petro Ramo" "auctore" :mention]] (read-sfs [["f" "auctore Petro Ramo"]])))))
  (testing "a role phrase keeps an internal comma; \"et de\" keeps the role before (synthetic)"
    (is (= [["Charles Simond" "Traduit de l'anglais, par" :mention]]
           (read-sfs [["g" "Traduit de l'anglais, par Charles Simond"]])))
    (is (= [["Alphonse Daudet" nil :mention] ["Léon Daudet" "préf. de" :mention] ["Lucien Daudet" "préf. de" :mention]]
           (read-sfs [["f" "Alphonse Daudet ; préf. de Léon Daudet et de Lucien Daudet"]]))))
  (testing "a name inside a body's name is no mention (cb41663228j, cb45001406k)"
    (is (= [["Association" nil :body]] (read-sfs [["f" "Association des amis de Pierre Loti"]])))
    (is (= [["Lewis Carroll" nil :body]] (read-sfs [["f" "Lewis Carroll society of North America"]])))
    (is (= [["Académie Goncourt" "choisis par l'" :body]] (read-sfs [["a" "Ramuz"] ["f" "choisis par l'Académie Goncourt"]]))))
  (testing "\"Signé\" opens a role, \"Fragments\" is no name (cb365635906, cb45999826n)"
    (is (= [["Léon Daudet" "Signé" :mention]] (read-sfs [["f" "[Signé : Léon Daudet.]"]])))
    (is (= [] (read-sfs [["g" "Fragments de journal intime rassemblés par son fils Samuel Viaud. I, [-II, III, IV]"]]))))
  (testing "a quoted title is dropped (cb38276610p)"
    (is (= [["Raymond Legrand" "chante accompagnée par" :mention]]
           (read-sfs [["a" "Colette Renard chante \"Paris\" accompagnée par Raymond Legrand et son orchestre"]]))))
  (testing "a trailing period is stripped (cb34802026g), encoding junk removed (cb34576616q)"
    (is (= [["Edgar Allan Poe" nil :mention] ["Charles Baudelaire" "traduction de" :mention]
            ["Stéphane Mallarmé" "traduction de" :mention]]
           (read-sfs [["a" "Le Corbeau"] ["f" "Edgar Allan Poe ; traduction de Charles Baudelaire, traduction de Stéphane Mallarmé."]])))
    (is (= [["Colette Mégret" nil :mention]] (read-sfs [["f" "M³ Colette Mégret,..."]]))))
  (testing "\"the Brontë sisters\" is a collective mention (cb41628640w)"
    (is (= [["Emily Brontë" "by" :mention] ["Anne Brontë" "by" :mention] ["Brontë" "by the" :collective]]
           (read-sfs [["f" "[by Emily Brontë]"] ["f" "[by Anne Brontë]"] ["f" "[by the Brontë sisters]"]]))))
  (testing "an incomplete name of an enumeration is completed with the shared surname (cb33158988n)"
    (is (= ["Robert Borel-Rosny"]
           (mapv :completed (filter :completed (hard/subfield-mentions "g" "[par Robert et Mme Robert Borel-Rosny]")))))))

;; ---------------------------------------------------------------------------
;; The labelling rule
;; ---------------------------------------------------------------------------

(defn- p [sur fore id & roles] {:surname sur :forename fore :id id :roles (vec roles) :key [sur fore id] :name (str sur ", " fore)})

(defn- labels [rec] (mapv (juxt :label :reason :truth) (hard/label-record rec)))

(deftest the-labelling-rule-links-only-what-is-unambiguous
  (testing "one heading whose surname ends the mention, forenames compatible: linked"
    (is (= [[:linked :surname "11894146"] [:no-match :no-heading nil] [:linked :surname "11894145"]]
           (labels {:mentions [{:text "Emily Brontë" :status :mention} {:text "Anne Brontë" :status :mention}
                               {:text "Charlotte Brontë" :role "by" :status :mention}]
                    :persons  [(p "Brontë" "Emily" "11894146") (p "Brontë" "Charlotte" "11894145")]}))))
  (testing "two headings that both fit: set aside, never guessed"
    (is (= [[:set-aside :ambiguous nil]]
           (labels {:mentions [{:text "A. Dumas" :status :mention}]
                    :persons  [(p "Dumas" "Alexandre" "11901063") (p "Dumas" "Adolphe" "12345678")]}))))
  (testing "a hyphenated surname is one word: Loti does not end \"Loti-Viaud\""
    (is (= [[:set-aside :unexplained-heading nil]]
           (labels {:mentions [{:text "Pierre P. Loti-Viaud" :status :mention}]
                    :persons  [(p "Loti" "Pierre" "11913405") (p "Pierre-Loti-Viaud" "Pierre" "12083386")]}))))
  (testing "the heading that fits has no $3: a no-match case"
    (is (= [[:no-match :heading-without-id nil]]
           (labels {:mentions [{:text "Lewis Carroll" :status :mention}] :persons [(p "Carroll" "Lewis" nil)]}))))
  (testing "a body or a collective mention, an incomplete name with no fitting completion: set aside"
    (is (= [[:set-aside :group nil] [:set-aside :group nil] [:set-aside :incomplete nil]]
           (labels {:mentions [{:text "Académie Goncourt" :role "choisis par l'" :status :body}
                               {:text "Brontë" :role "by the" :status :collective}
                               {:text "Edmond" :status :incomplete}]
                    :persons  [(p "Brontë" "Emily" "11894146")]})))))

(deftest a-mention-is-linked-by-a-variant-or-a-pseudonym-of-its-heading
  (testing "a 400 variant of the heading's authority"
    (is (= [[:linked :variant "12133148"]]
           (labels {:mentions [{:text "Michèle Daudet" :status :mention}]
                    :persons  [(assoc (p "Dautet" "Michèle" "12133148") :variants ["Daudet, Michèle"])]}))))
  (testing "a pseudonym the heading's authority links by 331 (Currer Bell)"
    (is (= [[:linked :surname "11894145"] [:linked :pseudonym "11894145"]]
           (labels {:mentions [{:text "Charlotte Brontë" :status :mention} {:text "Currer Bell" :role "by" :status :mention}]
                    :persons  [(assoc (p "Brontë" "Charlotte" "11894145") :variants ["Brontë, Carlotta"] :pseudonyms ["Bell, Currer"])]}))))
  (testing "a completed shared surname that fits one heading"
    (is (= [[:linked :shared-surname "11894144"]]
           (labels {:mentions [{:text "Anne" :completed "Anne Brontë" :status :incomplete}]
                    :persons  [(p "Brontë" "Anne" "11894144") (p "Brontë" "Emily" "11894146")]})))))

(deftest a-residual-link-needs-evidence-and-a-hand
  (testing "a unique role match is a candidate, not a link"
    (let [[poe tr] (hard/label-record {:mentions [{:text "Edgard Poe" :status :mention} {:text "M. C." :role "trad. di" :status :mention}]
                                       :persons  [(p "Poe" "Edgar Allan" "11920094" "0070") (p "Cirnensi" "Matteu" "12945429" "0680")]})]
      (is (= [:linked :surname] ((juxt :label :reason) poe)))
      (is (= [:set-aside :residual-candidate "12945429" :role] ((juxt :label :reason :candidate :basis) tr)))))
  (testing "one open mention, one unexplained heading, a shared hyphen part"
    (is (= [:residual-candidate "15604588" :hyphen]
           ((juxt :reason :candidate :basis)
            (first (hard/label-record {:mentions [{:text "Colette Lefort-Grévoz" :status :mention}]
                                       :persons  [(p "Dufour-Grévoz" "Colette" "15604588")]}))))))
  (testing "without evidence, no candidate"
    (is (= [[:set-aside :unexplained-heading nil]]
           (labels {:mentions [{:text "Simone de Caillavet" :status :mention}] :persons [(p "André-Maurois" "Simone" "13162820")]}))))
  (testing "the hand settles it: verified, rejected, or not verified"
    (let [m   {:id [:r "g" 0 0] :text "Matteu C." :label :set-aside :reason :residual-candidate :candidate "12945429" :basis :role}
          run (fn [v] ((juxt :label :reason :truth) (first (hard/resolve-hand [m] [] (if v {[:r "g" 0 0] {:verdict v}} {})))))]
      (is (= [:linked :role "12945429"] (run :correct)))
      (is (= [:set-aside :hand-rejected nil] (run :wrong)))
      (is (= [:set-aside :unverified nil] (run nil))))))

(deftest a-no-match-whose-surname-the-pool-holds-is-settled-by-hand
  (let [pool [{:id "11885918" :label "Carroll, Lewis" :variants ["Dodgson, Charles Lutwidge"]}
              {:id "11894145" :label "Brontë, Charlotte" :variants ["Bell, Currer"]}
              {:id "11929807" :label "Colette" :variants []}
              {:id "10886789" :label "Brenner, P S" :variants []}]
        ms   [{:id [:a] :text "Lewis Carroll" :label :no-match :reason :no-heading}
              {:id [:b] :text "Jacques Brenner" :label :no-match :reason :no-heading}
              {:id [:c] :text "Currer Bell" :label :no-match :reason :no-heading}
              {:id [:d] :text "C. Washburn" :label :no-match :reason :no-heading}
              {:id [:e] :text "Colette RENARD" :label :no-match :reason :no-heading}
              {:id [:f] :text "Lewis Carroll" :label :linked :reason :surname :truth "11885918"}]
        hand {[:a] {:verdict :same :truth "11885918"} [:b] {:verdict :different}}]
    (is (= [[:linked :hand "11885918"] [:no-match :hand-negative nil] [:set-aside :surname-in-pool nil]
            [:no-match :no-heading nil] [:no-match :no-heading nil] [:linked :surname "11885918"]]
           (mapv (juxt :label :reason :truth) (hard/resolve-hand ms pool hand))))))

(deftest the-match-rule-reads-surname-and-forename-initial
  (is (hard/heading-fits? "J.-H. Rosny aîné" {:surname "Rosny aîné" :forename "J.-H."}))
  (is (hard/heading-fits? "Dumas fils" {:surname "Dumas" :forename "Alexandre"}))
  (is (hard/heading-fits? "Daudet fil" {:surname "Daudet" :forename "Jean-Louis"}))
  (is (hard/heading-fits? "Edgard Poe" {:surname "Poe" :forename "Edgar Allan"}))
  (is (hard/heading-fits? "Colette" {:surname "Colette"}))
  (is (not (hard/heading-fits? "Colette Willy" {:surname "Colette"})))
  (is (not (hard/heading-fits? "Anne Brontë" {:surname "Brontë" :forename "Emily"})))
  (is (not (hard/heading-fits? "Pierre P. Loti-Viaud" {:surname "Loti" :forename "Pierre"}))))

(deftest one-mention-per-record-text-and-role
  (is (= [[:linked nil] [:set-aside :duplicate] [:linked nil] [:linked nil]]
         (mapv (juxt :label #(when (= :set-aside (:label %)) (:reason %)))
               (hard/dedupe-mentions [{:record "r" :text "Colette RENARD" :label :linked}
                                      {:record "r" :text "Colette RENARD" :label :linked}
                                      {:record "r" :text "Colette RENARD" :role "par" :label :linked}
                                      {:record "s" :text "Colette RENARD" :label :linked}])))))

;; ---------------------------------------------------------------------------
;; Splits by person
;; ---------------------------------------------------------------------------

(deftest the-split-is-a-deterministic-function-of-the-person
  (is (= (hard/split-of "11894146") (hard/split-of "11894146")))
  (testing "the design proportions over many keys are near 75/25"
    (let [fr (frequencies (map #(hard/split-of (str %)) (range 10000000 10010000)))]
      (is (= #{:train :dev} (set (keys fr))))
      (is (< 7300 (:train fr) 7700))))
  (testing "a no-match key is the surname and the first initial, so variants of one name share it"
    (is (= "name:baudelaire c" (hard/split-key {:label :no-match :text "Charles Baudelaire"})))
    (is (= (hard/split-key {:text "Ch. Baudelaire"}) (hard/split-key {:text "Charles Baudelaire"})))
    (is (= "name:alexeieff" (hard/split-key {:text "Alexeïeff"})))
    (is (= "11894146" (hard/split-key {:label :linked :truth "11894146" :text "Emily Brontë"})))))

;; ---------------------------------------------------------------------------
;; The harness's reading
;; ---------------------------------------------------------------------------

(deftest a-tie-is-an-abstention
  (let [m {:truth "a"}]
    (is (= :tied (hard/outcome m {:link {:authority-id "a"} :tied? true} :abstain)))
    (is (= :correct (hard/outcome m {:link {:authority-id "a"} :tied? true} :broken)))
    (is (= :tied (hard/outcome {} {:link {:authority-id "a"} :tied? true} :abstain)))
    (is (= :false-link (hard/outcome {} {:link {:authority-id "a"} :tied? true} :broken))))
  (let [s (hard/summary [{:truth "a" :outcome :tied} {:truth "a" :outcome :correct} {:key "n" :outcome :tied}])]
    (is (= [1 2 1 2/3 1 1] ((juxt :correct :tied :missed :abstention :precision :no-match-abstention) s)))))

(deftest a-name-is-put-in-heading-order-without-the-gold
  (is (= "Brontë, Charlotte" (hard/inverted "Charlotte Brontë")))
  (is (= "Poe, Edgar Allan" (hard/inverted "Edgar Allan Poe")))
  (is (= "Rosny aîné, J.-H." (hard/inverted "J.-H. Rosny aîné")))
  (is (= "Goncourt, Jules de" (hard/inverted "Jules de Goncourt")))
  (is (= "Van Loon, Hendrik Willem" (hard/inverted "Hendrik Willem Van Loon")))
  (is (= "Colette" (hard/inverted "Colette")))
  (is (= "Brontë, Charlotte" (hard/inverted "Brontë, Charlotte"))))

;; ---------------------------------------------------------------------------
;; The committed benchmark
;; ---------------------------------------------------------------------------

(def ^:private bench (delay (hard/load-benchmark)))

(deftest the-loader-reads-the-committed-benchmark
  (let [{:keys [mentions pool splits]} @bench]
    (is (seq mentions))
    (is (every? #(#{:linked :no-match :set-aside} (:label %)) mentions))
    (testing "every gold truth is in the pool, every pool entry is a matcher entry"
      (let [ids (set (map :id pool))]
        (is (every? ids (keep :truth mentions)))
        (is (every? #(and (:id %) (:label %) (vector? (:variants %))) pool))))
    (testing "dates are parsed on load"
      (is (some #(get-in % [:dates :birth]) pool)))
    (testing "the loader refuses a malformed file"
      (is (thrown? clojure.lang.ExceptionInfo (hard/validate {:mentions [{:label :maybe}] :pool []}))))
    (is (map? (:assignments splits)))))

(deftest the-benchmark-is-rebuilt-identically-from-the-fixtures
  (let [built (hard/build)
        file  (hard/read-edn hard/benchmark-file)]
    (is (= built file))
    (is (= (hard/split-file-content built) (hard/read-edn hard/splits-file)))))

(deftest no-person-is-in-two-splits
  (let [{:keys [mentions splits]} @bench
        scored (filter :split mentions)
        by-key (group-by hard/split-key scored)]
    (is (every? #(#{:linked :no-match} (:label %)) scored))
    (testing "no authority id and no no-match key in two splits"
      (is (every? (fn [[_ ms]] (= 1 (count (distinct (map :split ms))))) by-key)))
    (is (every? (fn [[k ms]] (= (get-in splits [:assignments k]) (:split (first ms)))) by-key))
    (testing "train and dev are the design corpora, test the corpora fetched after the freeze"
      (is (every? #(= (= :test (:split %)) (= :test (:part %))) scored)))
    (is (every? (comp nil? :split) (filter #(= :set-aside (:label %)) mentions)))))

(deftest a-pool-entry-built-from-a-heading-is-never-its-own-mention-s-gold
  (let [{:keys [mentions pool]} @bench
        from (into {} (for [e pool :when (:from-record e)] [(:id e) (:from-record e)]))]
    (is (= 87 (count from)) "the ids the SRU serves no authority record for")
    (is (not-any? #(and (:truth %) (= (:record %) (from (:truth %)))) mentions))))

(deftest the-rules-were-frozen-before-the-test-corpora
  (let [f (hard/read-edn hard/freeze-file)]
    (is (= (:sha256 f) (hard/sha-256 (slurp hard/source-file))))))

;; ---------------------------------------------------------------------------
;; The hand labels and the hand check
;; ---------------------------------------------------------------------------

(deftest every-hand-label-is-used-and-names-its-mention
  (let [labels (:labels (hard/read-edn hard/hand-labels-file))
        by-id  (into {} (map (juxt :id identity)) (:mentions @bench))]
    (is (every? (fn [[id l]] (= (:text l) (:text (by-id id)))) labels))
    (is (every? (fn [[id l]] (case (:verdict l)
                               :same      (or (= [:linked :hand (:truth l)] ((juxt :label :reason :truth) (by-id id)))
                                              (#{:duplicate :entry-from-own-record :seen-in-design} (:reason (by-id id))))
                               :different (#{:hand-negative :seen-in-design} (:reason (by-id id)))
                               :correct   (#{:role :hyphen :entry-from-own-record :duplicate} (:reason (by-id id)))
                               :wrong     (= :hand-rejected (:reason (by-id id)))))
                labels))
    (testing "no residual candidate and no design no-match with a namesake is left unverified"
      (is (not-any? #(and (= :design (:part %)) (#{:unverified :surname-in-pool} (:reason %))) (:mentions @bench))))))

(deftest the-hand-check-is-of-train-and-of-the-committed-gold
  (let [hc     (hard/read-edn hard/hand-check-file)
        b      @bench
        by-id  (into {} (map (juxt :id identity)) (:mentions b))
        sample (hard/hand-check-sample b 10)]
    (is (= (update-vals sample #(mapv :id %)) (update-vals (:sample hc) #(mapv :id %))))
    (is (every? #(= :train (:split (by-id (:id %)))) (mapcat val (:sample hc))))
    (is (every? #(= (select-keys % [:label :truth]) (select-keys (by-id (:id %)) [:label :truth])) (mapcat val (:sample hc))))
    (is (= (:verdicts hc) (frequencies (map :verdict (mapcat val (:sample hc))))))))

;; ---------------------------------------------------------------------------
;; The counts, and the engines on dev (current parameters)
;; ---------------------------------------------------------------------------

(def expected-counts
  {:mentions  {:design 1149 :test 625}
   :records   1113
   :by-label  {:linked 1191 :no-match 187 :set-aside 396}
   :by-reason {[:linked :hand] 51 [:linked :pseudonym] 10 [:linked :shared-surname] 6 [:linked :surname] 1075
               [:linked :variant] 49 [:no-match :hand-negative] 15 [:no-match :heading-without-id] 1
               [:no-match :no-heading] 171 [:set-aside :ambiguous] 29 [:set-aside :duplicate] 23
               [:set-aside :entry-from-own-record] 52 [:set-aside :group] 130 [:set-aside :incomplete] 47
               [:set-aside :seen-in-design] 88 [:set-aside :unexplained-heading] 27}
   :per-split {:dev   {:linked 186 :no-match 20 :persons 36 :names 15
                       :strata {:easy 29 :hand 14 :hard-negative 3 :homonym 127 :no-namesake 17 :variant 16}}
               :test  {:linked 401 :no-match 74 :persons 61 :names 57
                       :strata {:easy 198 :hand 16 :hard-negative 2 :homonym 186 :no-namesake 72 :variant 1}}
               :train {:linked 604 :no-match 93 :persons 82 :names 71
                       :strata {:easy 284 :hand 21 :hard-negative 10 :homonym 257 :no-namesake 83 :variant 42}}}
   :pool      {:authority 205 :heading 87}})

(deftest the-counts-of-the-benchmark
  (is (= expected-counts (hard/counts @bench))))

(def ^:private jaccard-dev
  {:mentions 206 :linked 186 :no-match 20 :persons 36 :names 15
   :correct 171 :wrong-link 0 :missed 15 :tied 3 :false-link 1 :review 0
   :precision 171/172 :recall 57/62 :macro-recall 215/236
   :abstention 17/103 :no-match-abstention 19/20 :macro-no-match-abstention 14/15})

(def expected-dev
  "The engines on dev, a tie read as an abstention. A transcription has no
   dates, so names+dates is the Jaccard exactly; the Fellegi-Sunter model,
   fed the name as transcribed (a format M4 never saw), proposes nothing."
  {:jaccard        jaccard-dev
   :names-dates    jaccard-dev
   :fellegi-sunter {:mentions 206 :linked 186 :no-match 20 :persons 36 :names 15
                    :correct 0 :wrong-link 0 :missed 186 :tied 0 :false-link 0 :review 0
                    :precision nil :recall 0 :macro-recall 0
                    :abstention 1 :no-match-abstention 1 :macro-no-match-abstention 1}
   :fellegi-sunter-inverted {:mentions 206 :linked 186 :no-match 20 :persons 36 :names 15
                             :correct 165 :wrong-link 0 :missed 21 :tied 0 :false-link 0 :review 3
                             :precision 1 :recall 55/62 :macro-recall 2989/3540
                             :abstention 41/206 :no-match-abstention 1 :macro-no-match-abstention 1}})

(deftest the-engines-on-dev
  (let [res (hard/run-engines @bench :dev)]
    (is (= expected-dev (:summaries res)))
    (testing "with ties broken by pool order, the Jaccard links one tie wrongly and two rightly"
      (is (= [173 1 12] ((juxt :correct :wrong-link :missed) (get-in res [:ties-broken :jaccard])))))
    (testing "every engine scores the same mentions against the same pool"
      (is (apply = (map (comp :mentions val) (:summaries res)))))
    (testing "per-case errors name the mention, the truth and the link"
      (is (every? #(and (:id %) (contains? % :truth) (contains? % :predicted)) (mapcat val (:errors res)))))))

(ns palomar.candidates-test
  "Candidate generation apart from ranking (lot N5, ADR 0060): lanes, each
   giving a mention's candidates with the lane and the key that found them;
   their union, deduplicated, in index order; the name lane exactly the
   authority index's block; the identifier lane by the ids a mention and an
   authority state, in one form; and ranking that scores the union and
   knows the lanes only as provenance."
  (:require [clojure.test :refer [deftest is testing]]
            [palomar.agent-dates :as dates]
            [palomar.agent-match :as fs]
            [palomar.authority-index :as index]
            [palomar.candidates :as candidates]
            [palomar.mentions :as mentions]
            [palomar.plugins.eac :as eac]
            [palomar.plugins.intermarc :as intermarc]
            [palomar.plugins.lido :as lido]
            [palomar.plugins.marc21 :as marc21]
            [palomar.plugins.unimarc :as unimarc]
            [palomar.reconcile :as reconcile]))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(def ^:private hugo-ark "http://catalogue.bnf.fr/ark:/12148/cb11907966z")
(def ^:private hugo-isni "0000000121200982")

(def ^:private pool
  "A small pool: Hugo (a BnF ARK and its ISNI), Dumas père (a GND IRI that
   states a VIAF id and an ISNI), Verne (no id), and two records stating one
   ISNI (a duplicate pair, so an ISNI that names two authorities)."
  [{:id hugo-ark :label "Hugo, Victor" :variants ["Hugo, Victor-Marie"] :isni [hugo-isni]
    :dates (dates/parse-life-dates "1802-1885")}
   {:id "https://d-nb.info/gnd/118528068" :label "Dumas, Alexandre" :variants []
    :same-as ["http://viaf.org/viaf/51688902" "https://isni.org/isni/0000000121012885"]
    :dates (dates/parse-life-dates "1802-1870")}
   {:id "verne" :iri "http://catalogue.bnf.fr/ark:/12148/cb11928016k" :label "Verne, Jules" :variants []
    :dates (dates/parse-life-dates "1828-1905")}
   {:id "http://example.org/a/stahl" :label "Stahl, P.-J." :variants [] :isni ["0000000080766583"]}
   {:id "http://example.org/b/stahl" :label "Stahl, Pierre-Jules" :variants [] :isni ["0000 0000 8076 6583"]}])

(def ^:private idx (delay (candidates/index pool)))

(def ^:private model
  "A hand-set model (as `reconcile-test`'s): an exact heading weighs log2
   90, a name with no agreement log2 (0.005/0.7), agreeing dates log2 16."
  (fs/model {:m {:name  {:exact 0.9 :initials 0.03 :same-tokens 0.02 :surname-typo 0.01 :forename-overlap 0.01
                         :surname-only 0.01 :forenames-differ 0.005 :jaccard-high 0.005 :jaccard-low 0.005 :none 0.005}
                 :form  {:heading 0.95 :variant 0.05}
                 :dates {:agree 0.8 :compatible 0.1 :unknown 0.1}}
             :u {:name  {:exact 0.01 :initials 0.002 :same-tokens 0.002 :surname-typo 0.001 :forename-overlap 0.01
                         :surname-only 0.005 :forenames-differ 0.05 :jaccard-high 0.02 :jaccard-low 0.2 :none 0.7}
                 :form  {:heading 0.9 :variant 0.1}
                 :dates {:agree 0.05 :compatible 0.15 :unknown 0.8}}
             :prior 0.05}))

(defn- ids-of [gen] (mapv :id (:candidates gen)))

(defn- lanes-of [gen] (into {} (for [c (:candidates gen)] [(:id c) (mapv :lane (:by c))])))

;; ---------------------------------------------------------------------------
;; Lanes and their provenance
;; ---------------------------------------------------------------------------

(deftest each-candidate-names-its-lane-and-the-key-that-found-it
  (testing "the name lane: the blocking keys the mention's name shares with the entry"
    (let [{cs :candidates} (candidates/name-lane @idx {:name "Hugo, Victor"})
          hugo (first (filter #(= hugo-ark (:id %)) cs))]
      (is (= [hugo-ark] (map :id cs)))
      (is (= :name (:lane hugo)))
      (is (= ["s:hgo" "s:hug" "s:hugo" "s:huo" "s:ugo" "t:hugo" "t:victor"] (:keys hugo))
          "its tokens, its surname and the surname's one-letter deletions")))
  (testing "the identifier lane: the id in one form, and the value the mention gave"
    (let [{cs :candidates} (candidates/identifier-lane @idx {:name "Гюго, Виктор" :ids [(str "ISNI " hugo-isni)]})]
      (is (= [{:id hugo-ark :ordinal 0 :lane :identifier :keys [(str "isni:" hugo-isni)] :values [(str "ISNI " hugo-isni)]}]
             cs))))
  (testing "a mention without ids: the identifier lane gives nothing, and says nothing"
    (is (= {:candidates [] :reports []} (candidates/identifier-lane @idx {:name "Hugo, Victor"})))))

(deftest the-union-of-the-lanes-is-deduplicated-in-index-order
  (let [m   {:name "Hugo, Victor" :ids [hugo-ark "http://viaf.org/viaf/51688902"]}
        gen (candidates/generate @idx m [:name :identifier])]
    (testing "Hugo, found by both lanes, is one candidate with both lanes; Dumas by its VIAF id alone"
      (is (= [hugo-ark "https://d-nb.info/gnd/118528068"] (ids-of gen)) "index order, not lane order")
      (is (= {hugo-ark [:name :identifier] "https://d-nb.info/gnd/118528068" [:identifier]} (lanes-of gen)))
      (is (= [["s:hgo" "s:hug" "s:hugo" "s:huo" "s:ugo" "t:hugo" "t:victor"] ["ark:/12148/cb11907966z"]]
             (map :keys (:by (first (:candidates gen)))))))
    (testing "the lanes in another order give the same union"
      (is (= gen (candidates/generate @idx m [:identifier :name]))))
    (testing "an unknown lane is refused"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"lane" (candidates/generate @idx m [:name :phonetic]))))))

(deftest the-name-lane-is-the-authority-index-s-block
  (let [f   (doto (java.io.File/createTempFile "palomar-cand-" ".edn") (.deleteOnExit))
        _   (index/build! "test/fixtures/er-gold/gnd-persons/gnd-extract.jsonl" f {:built "2026-11-09"})
        gnd (index/read-index f)
        ci  (candidates/index gnd)
        blk (index/block gnd)]
    (testing "on a real index: every name's candidates are the block's entries, in the block's order"
      (doseq [n ["Dumas, Alexandre" "Hugo, Victor" "Verne, Jules" "Htezel, Pierre-Jules" "Nobody" ""]]
        (is (= (blk n) (mapv (:entries ci) (map :ordinal (:candidates (candidates/name-lane ci {:name n}))))) n)))
    (testing "an index built in memory from a pool gives the pool's order; a shared token is enough (P.-J.'s J.)"
      (is (= ["verne" "http://example.org/a/stahl"] (ids-of (candidates/generate @idx {:name "Verne, J."} [:name])))))))

;; ---------------------------------------------------------------------------
;; The identifier lane, per id form
;; ---------------------------------------------------------------------------

(defn- sf [& kvs] (apply str (for [[c v] (partition 2 kvs)] (str "<mxc:subfield code=\"" c "\">" v "</mxc:subfield>"))))

(defn- mxc [fmt & fields]
  (str "<mxc:record xmlns:mxc=\"info:lc/xmlns/marcxchange-v2\" format=\"" fmt "\" id=\"ark:/12148/cb1\" type=\"Bibliographic\">"
       "<mxc:controlfield tag=\"001\">FRBNcb1</mxc:controlfield>"
       (apply str (for [[tag & kvs] fields] (str "<mxc:datafield tag=\"" tag "\" ind1=\" \" ind2=\" \">" (apply sf kvs) "</mxc:datafield>")))
       "</mxc:record>"))

(defn- marc21-record [& fields]
  (first (marc21/ingest (str "<collection xmlns=\"http://www.loc.gov/MARC21/slim\"><record><leader>00000nam a2200000 a 4500</leader>"
                             "<controlfield tag=\"001\">x1</controlfield>"
                             (apply str (for [[tag & kvs] fields]
                                          (str "<datafield tag=\"" tag "\" ind1=\"1\" ind2=\" \">"
                                               (apply str (for [[c v] (partition 2 kvs)] (str "<subfield code=\"" c "\">" v "</subfield>")))
                                               "</datafield>")))
                             "</record></collection>")
                        {})))

(defn- lido-record [actor-id]
  (first (:records (lido/importer {:validate? false}
                                  (str "<lido:lido xmlns:lido=\"http://www.lido-schema.org\"><lido:lidoRecID>E-1</lido:lidoRecID><lido:descriptiveMetadata><lido:eventWrap>"
                                       "<lido:eventSet><lido:event><lido:eventType><lido:term>production</lido:term></lido:eventType>"
                                       "<lido:eventActor><lido:actorInRole><lido:actor lido:type=\"person\"><lido:actorID>" actor-id "</lido:actorID>"
                                       "<lido:nameActorSet><lido:appellationValue>V. Hugo</lido:appellationValue></lido:nameActorSet></lido:actor>"
                                       "</lido:actorInRole></lido:eventActor></lido:event></lido:eventSet></lido:eventWrap></lido:descriptiveMetadata></lido:lido>")))))

(defn- eac-record [identity-extra control-extra]
  (first (:records (eac/importer {} (str "<eac xmlns=\"https://archivists.org/ns/eac/v2\"><control><recordId>r1</recordId>" control-extra
                                         "<maintenanceAgency><agencyCode>X</agencyCode></maintenanceAgency></control>"
                                         "<cpfDescription><identity>" identity-extra "<entityType value=\"person\"/>"
                                         "<nameEntry status=\"authorized\"><part>Victor Hugo</part></nameEntry></identity></cpfDescription></eac>")))))

(defn- found-by-id [record]
  (let [m (first (mentions/mentions record))]
    [(ids-of (candidates/identifier-lane @idx m)) (:ids m)]))

(deftest the-identifier-lane-reads-every-id-form
  (testing "INTERMARC $3 is the authority the cataloguer linked, never an id the lane reads (N5 review)"
    (let [m (first (mentions/mentions (first (intermarc/ingest (mxc "INTERMARC" ["700" "3" "11907966" "a" "Hugo" "m" "V."]) {}))))]
      (is (= hugo-ark (:authority-record m)))
      (is (= {:candidates [] :reports []} (candidates/identifier-lane @idx m)))
      (is (= {:candidates [] :reports []} (candidates/identifier-lane @idx {:name "x" :authority-record hugo-ark})))))
  (testing "INTERMARC $1, an ISNI as the BnF writes it"
    (is (= [[hugo-ark] ["ISNI0000000121200982"]]
           (found-by-id (first (intermarc/ingest (mxc "INTERMARC" ["700" "1" "ISNI0000000121200982" "a" "Hugo" "m" "V."]) {}))))))
  (testing "MARC21 $0, a URI or an (organisation code) number; $1, a real-world object URI"
    (is (= [["https://d-nb.info/gnd/118528068"] ["https://d-nb.info/gnd/118528068"]]
           (found-by-id (marc21-record ["700" "a" "Dumas" "0" "(DE-588)118528068"]))))
    (is (= [["https://d-nb.info/gnd/118528068"] ["http://viaf.org/viaf/51688902"]]
           (found-by-id (marc21-record ["100" "a" "Dumas" "0" "http://viaf.org/viaf/51688902"]))))
    (is (= [[hugo-ark] ["https://isni.org/isni/0000000121200982"]]
           (found-by-id (marc21-record ["100" "a" "Hugo" "1" "https://isni.org/isni/0000000121200982"]))))
    (is (= [[] nil] (found-by-id (marc21-record ["100" "a" "Hugo" "0" "(OCoLC)fst00034567"])))
        "a number of an organisation it cannot name is no id"))
  (testing "UNIMARC $o, the ISNI"
    (is (= [[hugo-ark] ["ISNI0000000121200982"]]
           (found-by-id (first (unimarc/ingest (mxc "UNIMARC" ["700" "3" "11907966" "o" "ISNI0000000121200982" "a" "Hugo" "b" "Victor"]) {}))))))
  (testing "a LIDO actorID"
    (is (= [[hugo-ark] [hugo-ark]] (found-by-id (lido-record hugo-ark)))))
  (testing "an EAC-CPF entityId, and its otherRecordIds"
    (is (= [[hugo-ark] [hugo-ark]] (found-by-id (eac-record (str "<entityId>" hugo-ark "</entityId>") ""))))
    (is (= [[hugo-ark "https://d-nb.info/gnd/118528068"] ["http://viaf.org/viaf/51688902" (str "ISNI " hugo-isni)]]
           (found-by-id (eac-record "" (str "<otherRecordId>http://viaf.org/viaf/51688902</otherRecordId>"
                                            "<otherRecordId localType=\"isni\">ISNI " hugo-isni "</otherRecordId>"))))))
  (testing "the authority's side: its IRI, its ISNIs, its sameAs, each in one form whatever its spelling"
    (is (= [hugo-ark] (ids-of (candidates/identifier-lane @idx {:name "x" :ids ["https://data.bnf.fr/ark:/12148/cb11907966Z"]}))))
    (is (= ["https://d-nb.info/gnd/118528068"] (ids-of (candidates/identifier-lane @idx {:name "x" :ids ["https://viaf.org/en/viaf/51688902"]}))))
    (is (= ["https://d-nb.info/gnd/118528068"] (ids-of (candidates/identifier-lane @idx {:name "x" :ids ["http://lobid.org/gnd/118528068"]}))))))

(deftest an-id-a-record-states-and-palomar-cannot-use-is-reported
  (testing "an INTERMARC $1 whose check character is wrong: an id still, reported invalid, no candidate"
    (let [m (first (mentions/mentions (first (intermarc/ingest (mxc "INTERMARC" ["700" "1" "ISNI0000000121200983" "a" "Hugo" "m" "Victor"]) {}))))]
      (is (= ["ISNI0000000121200983"] (:ids m)))
      (is (= {:candidates [] :reports [{:lane :identifier :kind :invalid-identifier :value "ISNI0000000121200983"}]}
             (candidates/identifier-lane @idx m)))))
  (testing "a value of an id subfield no id form reads: carried as unread, reported"
    (let [m (first (mentions/mentions (marc21-record ["100" "a" "Hugo" "0" "(OCoLC)fst00034567"])))]
      (is (= {:candidates [] :reports [{:lane :identifier :kind :unread-identifier :value "(OCoLC)fst00034567"}]}
             (candidates/identifier-lane @idx m))))))

(deftest an-identifier-that-names-no-authority-gives-no-candidate-and-is-reported
  (let [gen (candidates/identifier-lane @idx {:name "Hugo, Victor" :ids ["http://viaf.org/viaf/1" "ISNI 0000000121200983" "foo"]})]
    (is (= [] (:candidates gen)))
    (is (= [{:lane :identifier :kind :no-authority :key "viaf:1" :value "http://viaf.org/viaf/1"}
            {:lane :identifier :kind :invalid-identifier :value "ISNI 0000000121200983"}
            {:lane :identifier :kind :unread-identifier :value "foo"}]
           (:reports gen))
        "an ISNI whose check character is wrong is no id")))

(deftest an-identifier-that-names-two-authorities-is-reported-and-never-picks
  (let [m   {:name "Stahl, P.-J." :ids ["https://isni.org/isni/0000000080766583"]}
        gen (candidates/identifier-lane @idx m)]
    (testing "both are candidates, and the lane says so"
      (is (= ["http://example.org/a/stahl" "http://example.org/b/stahl"] (ids-of gen)))
      (is (= [{:lane :identifier :kind :several-authorities :key "isni:0000000080766583"
               :authorities ["http://example.org/a/stahl" "http://example.org/b/stahl"]}]
             (:reports gen))))
    (testing "two ids naming two authorities are reported too"
      (is (= [{:lane :identifier :kind :ids-disagree :keys ["ark:/12148/cb11907966z" "viaf:51688902"]
               :authorities [hugo-ark "https://d-nb.info/gnd/118528068"]}]
             (:reports (candidates/identifier-lane @idx {:name "x" :ids [hugo-ark "http://viaf.org/viaf/51688902"]}))))
      (is (= [hugo-ark "https://d-nb.info/gnd/118528068"]
             (ids-of (candidates/identifier-lane @idx {:name "x" :ids [hugo-ark "http://viaf.org/viaf/51688902"]})))))
    (testing "ranking decides between them by names and dates, as for any two candidates: the lane picks none"
      (let [r (candidates/rank [m] pool {:model model :lanes [:identifier]})]
        (is (= #{"http://example.org/a/stahl" "http://example.org/b/stahl"}
               (set (map :authority-id (concat (:proposals r) (:abstained r))))))
        (is (= [[{:lane :identifier :kind :several-authorities :key "isni:0000000080766583"
                  :authorities ["http://example.org/a/stahl" "http://example.org/b/stahl"]}]]
               (map :reports (:generation r))))))))

;; ---------------------------------------------------------------------------
;; Ranking: the union scored, the lanes as provenance
;; ---------------------------------------------------------------------------

(defn- strip [r]
  (-> r
      (dissoc :generation)
      (update-vals (fn [xs] (mapv #(dissoc % :generated-by) xs)))))

(deftest ranking-the-name-lane-alone-is-the-block-s-ranking
  (let [ms [{:name "Hugo, Victor" :dates "1802-1885"} {:name "Dumas, Alexandre"} {:name "Verne, J."} "Stahl"]
        blk (fn [n] (mapv pool (map :ordinal (:candidates (candidates/name-lane @idx {:name n})))))]
    (testing "with a model, and name-only"
      (is (= (reconcile/agent-candidates ms pool {:model model}) (strip (candidates/rank ms pool {:model model :lanes [:name]}))))
      (is (= (reconcile/agent-candidates ms pool {:model model :block blk}) (strip (candidates/rank ms pool {:model model}))))
      (is (= (reconcile/agent-candidates ms pool) (strip (candidates/rank ms pool {:lanes [:name]}))))
      (is (= (reconcile/agent-candidates ms pool {:threshold 0.2}) (strip (candidates/rank ms pool {:threshold 0.2})))))
    (testing "every proposal, abstention and rejection names the lanes that found it"
      (let [r (candidates/rank ms pool {:model model})]
        (is (seq (:proposals r)))
        (is (every? #(= [:name] (map :lane (:generated-by %))) (concat (:proposals r) (:abstained r) (:rejected r))))))))

(deftest an-identifier-lane-candidate-is-ranked-on-names-and-dates
  (let [m {:name "Гюго, Виктор" :dates "1802-1885" :ids [hugo-ark]}]
    (testing "the name lane finds nothing for another script"
      (is (= [] (ids-of (candidates/generate @idx m [:name])))))
    (testing "the identifier lane brings Hugo; ranking weighs it though no name agrees, and the ids add no weight"
      (let [r (candidates/rank [m] pool {:model model :lanes [:name :identifier]})
            c (first (:abstained r))]
        (is (= [] (:proposals r)) "an id alone never makes a proposal: the model has no identifier feature")
        (is (= hugo-ark (:authority-id c)))
        (is (= {:name :none :form :heading :dates :agree} (get-in c [:evidence :comparison])))
        (is (= [{:lane :identifier :keys ["ark:/12148/cb11907966z"] :values [hugo-ark]}] (:generated-by c)))
        (is (= (reconcile/agent-candidates [(dissoc m :ids)] pool {:model model :block (constantly [(first pool)]) :score-unnamed #{hugo-ark}})
               (strip r)))))
    (testing "a name the identifier lane alone brings and the name block would have dropped stays dropped without it"
      (is (= [] (:abstained (candidates/rank [m] pool {:model model :lanes [:name]})))))))

;; ---------------------------------------------------------------------------
;; Recall per lane
;; ---------------------------------------------------------------------------

(deftest recall-is-the-truth-among-the-candidates-per-lane
  (let [ms [{:name "Hugo, Victor" :ids [hugo-ark] :truth hugo-ark}
            {:name "Гюго, Виктор" :ids [(str "ISNI " hugo-isni)] :truth hugo-ark}
            {:name "Verne, Jules" :truth "verne"}
            {:name "Dumas, A." :truth "https://d-nb.info/gnd/118528068"}
            {:name "Nobody" :truth "not-in-pool"}
            {:name "Hugo, Victor"}]
        r  (candidates/recall @idx ms {:lanes [:name :identifier] :truth :truth})]
    (is (= {:mentions 6 :with-truth 5 :in-index 4} (select-keys r [:mentions :with-truth :in-index])))
    (is (= {:found 3 :recall 3/4 :candidates 5 :max 2} (select-keys (get-in r [:lanes :name]) [:found :recall :candidates :max]))
        "Verne, Jules also finds Stahl, Pierre-Jules, by the token jules: a cost, not a miss")
    (is (= {:found 2 :recall 1/2 :candidates 2 :max 1} (select-keys (get-in r [:lanes :identifier]) [:found :recall :candidates :max])))
    (is (= {:found 4 :recall 1 :candidates 6 :max 2} (select-keys (:union r) [:found :recall :candidates :max])))
    (is (= {:name 2 :identifier 1} (:only r)) "the truths one lane alone finds")
    (testing "the truths passed apart, the mentions carrying none: the same recall"
      (is (= (dissoc r :per-mention)
             (dissoc (candidates/recall @idx (map #(dissoc % :truth) ms) {:lanes [:name :identifier] :truths (map :truth ms)})
                     :per-mention))))
    (testing "the candidate sets, per mention and per lane"
      (let [sets (candidates/candidate-sets @idx ms [:name :identifier])]
        (is (= {:name [] :identifier [hugo-ark]} (:lanes (second sets))))
        (is (= [hugo-ark] (:union (second sets))))))))

;; ---------------------------------------------------------------------------
;; Review (N5): what the name lane hides, the cap, deduplication
;; ---------------------------------------------------------------------------

(deftest the-name-lane-never-hides-an-identifier-candidate
  (testing "Verne, found by a name key (a surname deletion) whose name level is :none, and by its id: it is weighed"
    (let [m   {:name "Vernes, Paul" :ids ["http://catalogue.bnf.fr/ark:/12148/cb11928016k"]}
          gen (candidates/generate @idx m [:name :identifier])
          r   (candidates/rank [m] pool {:model model :lanes [:name :identifier]})]
      (is (= [[:name :identifier]] (map #(mapv :lane (:by %)) (:candidates gen))))
      (is (= ["verne"] (map :authority-id (concat (:proposals r) (:abstained r)))))
      (is (= :none (get-in (first (:abstained r)) [:evidence :comparison :name])))
      (testing "by the name lane alone it is dropped, as the block always dropped it"
        (is (= [] (:abstained (candidates/rank [m] pool {:model model :lanes [:name]}))))))))

(def ^:private dates-model
  "A model whose agreeing dates outweigh a name with no agreement (probe p8, its dates made stronger)."
  (fs/model {:m {:name {:exact 0.8 :initials 0.02 :same-tokens 0.02 :surname-typo 0.01 :forename-overlap 0.01
                        :surname-only 0.01 :forenames-differ 0.01 :jaccard-high 0.01 :jaccard-low 0.01 :none 0.1}
                 :form {:heading 0.95 :variant 0.05} :dates {:agree 0.9 :compatible 0.05 :unknown 0.05}}
             :u {:name {:exact 0.01 :initials 0.002 :same-tokens 0.002 :surname-typo 0.001 :forename-overlap 0.01
                        :surname-only 0.005 :forenames-differ 0.05 :jaccard-high 0.02 :jaccard-low 0.2 :none 0.7}
                 :form {:heading 0.9 :variant 0.1} :dates {:agree 0.001 :compatible 0.199 :unknown 0.8}}
             :prior 0.3}))

(deftest a-candidate-with-no-name-agreement-is-at-most-for-review
  (let [pool' [{:id "right" :label "Hetzel, Pierre-Jules" :variants [] :dates (dates/parse-life-dates "1814-1886")}
               {:id "wrong" :label "Stahl, P.-J." :variants [] :isni ["0000000080766583"] :dates (dates/parse-life-dates "1814-1886")}]
        m     {:name "Hetzel, Pierre-Jules" :dates "1814-1886" :ids ["ISNI0000000080766583"]}
        dec   (fn [r] (mapv (juxt :authority-id :decision :review-reason) (:proposals r)))]
    (testing "the name lane alone: Hetzel proposed"
      (is (= [["right" :propose nil]] (dec (candidates/rank [m] pool' {:model dates-model :lanes [:name]})))))
    (testing "Stahl, which the ISNI brings with no name agreement, scores above the propose threshold yet is for review only, and does not compete with Hetzel"
      (let [r (candidates/rank [m] pool' {:model dates-model :lanes [:name :identifier]})]
        (is (< 0.95 (:score (first (filter #(= "wrong" (:authority-id %)) (:proposals r))))))
        (is (= [["right" :propose nil] ["wrong" :review :no-name-agreement]] (sort (dec r))))))
    (testing "alone, it is still for review only"
      (is (= [["wrong" :review :no-name-agreement]] (dec (candidates/rank [m] pool' {:model dates-model :lanes [:identifier]})))))))

(deftest rank-deduplicates-as-the-matcher-does
  (testing "name-only: agent-candidates scores a name given twice twice; so does rank"
    (let [ms ["Hugo, Victor" "Hugo, Victor"]]
      (is (= 2 (count (:proposals (reconcile/agent-candidates ms pool)))))
      (is (= (reconcile/agent-candidates ms pool) (strip (candidates/rank ms pool {}))))))
  (testing "with a model: two mentions the matcher reads alike are scored once, as by agent-candidates"
    (let [ms [{:name "Hugo, Victor" :source "a"} {:name "Hugo, Victor" :source "b"}]]
      (is (= 1 (count (:proposals (reconcile/agent-candidates ms pool {:model model})))))
      (is (= (reconcile/agent-candidates ms pool {:model model}) (strip (candidates/rank ms pool {:model model}))))
      (is (= 1 (count (:generation (candidates/rank ms pool {:model model})))))))
  (testing "with the identifier lane, two mentions whose ids differ are two"
    (let [ms [{:name "Hugo, Victor" :ids [hugo-ark]} {:name "Hugo, Victor"}]]
      (is (= 2 (count (:generation (candidates/rank ms pool {:model model :lanes [:name :identifier]}))))))))

(ns palomar.plugins.events-test
  "Publication and production events (ADR 0036): a MARC 260/264 or UNIMARC
   210/214 becomes an F30 Manifestation Creation with its time-span, place and
   actor, and each export says it in its own vocabulary."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.convert :as convert]
            [palomar.model :as model]
            [palomar.plugins.lrmoo.view :as view]))

(defn- marcxml [id & fields]
  (str "<collection xmlns=\"http://www.loc.gov/MARC21/slim\"><record>"
       "<leader>00000nam a2200000 a 4500</leader>"
       "<controlfield tag=\"001\">" id "</controlfield>"
       "<datafield tag=\"245\" ind1=\"0\" ind2=\"0\"><subfield code=\"a\">Nadja</subfield></datafield>"
       (apply str fields)
       "</record></collection>"))

(defn- field [tag ind2 & subfields]
  (str "<datafield tag=\"" tag "\" ind1=\" \" ind2=\"" ind2 "\">"
       (apply str (for [[c v] (partition 2 subfields)] (str "<subfield code=\"" c "\">" v "</subfield>")))
       "</datafield>"))

(defn- pivot [xml] (first (:records (convert/to-wemi :marc21 {} xml))))

(defn- of-kind [r k] (filter #(= k (:kind %)) (:entities r)))

(defn- value [r s p] (some #(when (and (= s (:subject %)) (= p (:predicate %))) (:value %)) (:assertions r)))

(deftest a-260-is-a-publication-event
  (let [r     (pivot (marcxml "1" (field "260" " " "a" "Paris :" "b" "Gallimard," "c" "1928.")))
        [ev]  (of-kind r :lrmoo/F30_Manifestation_Creation)
        manif (:id (first (view/manifestations r)))
        ts    (:value/target (value r (:id ev) :crm/P4_has_time-span))
        pl    (:value/target (value r (:id ev) :crm/P7_took_place_at))
        ac    (:value/target (value r (:id ev) :crm/P14_carried_out_by))]
    (is (some? ev))
    (is (= (model/reference manif) (value r (:id ev) :lrmoo/R24_created)))
    (is (= "1928." (value r ts :crm/P82_at_some_time_within)) "the date as transcribed")
    (is (= "Paris" (value r pl :crm/P190_has_symbolic_content)) "the place without its ISBD punctuation")
    (is (= "Gallimard" (value r ac :crm/P190_has_symbolic_content)))
    (is (every? #(= :asserted (:status %))
                (filter #(= (:id ev) (:subject %)) (:assertions r))))
    (testing "each claim keeps where it was read"
      (is (str/includes? (get-in (first (filter #(= :crm/P82_at_some_time_within (:predicate %)) (:assertions r)))
                                 [:provenance :evidence 0 :selector :value])
                         "@tag='260'")))))

(deftest the-264-second-indicator-says-which-event
  (let [r (pivot (marcxml "2"
                          (field "264" "1" "a" "Paris :" "b" "Gallimard," "c" "1928.")
                          (field "264" "4" "c" "©1928")))]
    (is (= 1 (count (of-kind r :lrmoo/F30_Manifestation_Creation))) "a copyright statement is no event")
    (testing "only the publication statement's $b is a publisher (:mapping/where on ind2)"
      (is (= #{"Gallimard,"} (set (keep #(when (and (= :canon/agent (:predicate %)) (string? (:value %))) (:value %))
                                        (:assertions r))))))
    (is (empty? (of-kind (pivot (marcxml "3" (field "264" "2" "a" "Lyon" "b" "Distrib"))) :lrmoo/F30_Manifestation_Creation))
        "a distribution is not a Manifestation Creation")))

(deftest a-place-name-is-not-a-place-across-records
  (testing "two records' \"Paris\" may be two places: each is scoped to its record (ADR 0041)"
    (let [a (pivot (marcxml "4" (field "260" " " "a" "Paris :" "b" "Gallimard" "c" "1928")))
          b (pivot (marcxml "5" (field "260" " " "a" "[Paris]" "b" "Gallimard" "c" "1930")))]
      (is (not= (map :id (of-kind a :crm/E53_Place)) (map :id (of-kind b :crm/E53_Place))))
      (is (not= (map :id (of-kind a :crm/E39_Actor)) (map :id (of-kind b :crm/E39_Actor))))))
  (let [a (pivot (marcxml "4" (field "260" " " "a" "Paris :" "c" "1928")))
        b (pivot (marcxml "5" (field "260" " " "a" "[Paris]" "c" "1930")))]
    (is (not= (map :id (of-kind a :lrmoo/F30_Manifestation_Creation))
              (map :id (of-kind b :lrmoo/F30_Manifestation_Creation))))))

(deftest unimarc-210-makes-the-same-event
  (let [rs (:records (convert/to-wemi :unimarc {} (slurp "test/fixtures/documentary/unimarc/sru/bnf-sru-verne-unimarc.xml")))
        r  (first rs)]
    (is (= 1 (count (of-kind r :lrmoo/F30_Manifestation_Creation))))
    (is (= "Amiens" (some #(value r (:id %) :crm/P190_has_symbolic_content) (of-kind r :crm/E53_Place))))))

(deftest each-target-writes-the-event-in-its-vocabulary
  (let [xml (marcxml "6" (field "260" " " "a" "Paris :" "b" "Gallimard," "c" "1928."))
        out #(:output (convert/convert {:from :marc21 :to % :source xml}))]
    (testing "LRMoo and CRM"
      (is (str/includes? (out :ntriples) "lrmoo/F30_Manifestation_Creation"))
      (is (str/includes? (out :ntriples) "lrmoo/R24_created"))
      (is (str/includes? (out :ntriples) "cidoc-crm/P82_at_some_time_within")))
    (testing "pure CRM: an E12 Production and an E65 Creation that P94 has created"
      (let [o (out :crm-only)]
        (is (str/includes? o "cidoc-crm/E12_Production"))
        (is (str/includes? o "cidoc-crm/E65_Creation"))
        (is (str/includes? o "cidoc-crm/P94_has_created"))
        (is (not (str/includes? o "lrmoo/")))))
    (testing "Linked Art: a Publishing activity with its place and time-span"
      (let [doc (json/read-str (out :linked-art))
            act (first (get doc "used_for"))]
        (is (= "Paris" (get-in act ["took_place_at" 0 "_label"])))
        (is (= "1928." (get-in act ["timespan" "identified_by" 0 "content"])))
        (is (= "Gallimard" (get-in act ["carried_out_by" 0 "_label"])))
        (is (some? (get-in act ["took_place_at" 0 "id"])))))
    (testing "Linked Art reports none of the event's claims as lost"
      (let [loss (:loss (convert/convert {:from :marc21 :to :linked-art :source xml}))]
        (is (not-any? #{:lrmoo/R24_created :crm/P4_has_time-span :crm/P7_took_place_at :crm/P14_carried_out_by}
                      (:source-fields loss)))))))

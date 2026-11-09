(ns palomar.plugins.gnd-test
  "GND authority records (lobid JSON lines) as pool entries (lot M6)."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.plugins.gnd :as gnd]))

(def extract "test/fixtures/er-gold/gnd-persons/gnd-extract.jsonl")

(def ^:private lines (delay (vec (str/split-lines (slurp extract)))))

(defn- entry-of [id] (some #(when (= id (:id %)) %) (:entries (gnd/pool (slurp extract)))))

(deftest a-gnd-person-is-one-entry
  (let [e (entry-of "https://d-nb.info/gnd/118528068")]
    (is (= "Dumas, Alexandre" (:label e)))
    (is (= "person" (:entity-type e)))
    (testing "its variant names, the preferred one aside"
      (is (some #{"Dumas, Alexandre, Vater"} (:variants e)))
      (is (not (some #{"Dumas, Alexandre"} (:variants e)))))
    (testing "its dates of birth and death as a life-dates profile"
      (is (= {:from 1802 :to 1802} (select-keys (get-in e [:dates :birth]) [:from :to])))
      (is (= {:from 1870 :to 1870} (select-keys (get-in e [:dates :death]) [:from :to])))
      (is (= "b. 1802-07-24, d. 1870-12-05" (get-in e [:dates :text]))))
    (testing "its sameAs links to the authority files, as data; no Wikipedia page, no other GND copy"
      (is (= ["http://id.loc.gov/rwo/agents/n79042162" "http://viaf.org/viaf/51688902"
              "http://www.wikidata.org/entity/Q38337" "https://isni.org/isni/0000000121012885"]
             (:same-as e))))))

(deftest every-record-of-the-extract-is-an-entry
  (let [es (:entries (gnd/pool (slurp extract)))]
    (is (= (count @lines) (count es)))
    (is (= (count es) (count (distinct (map :id es)))))
    (is (every? #(str/starts-with? (:id %) "https://d-nb.info/gnd/") es))
    (is (every? :label es))))

(deftest an-activity-is-read-and-what-is-not-an-agent-is-skipped
  (testing "a period of activity is an activity"
    (let [e (gnd/entry {"id" "https://d-nb.info/gnd/x" "type" ["Person"] "preferredName" "X, Y"
                        "periodOfActivity" ["1870-1930"]})]
      (is (= {:from 1870 :to 1930} (select-keys (get-in e [:dates :active]) [:from :to])))))
  (testing "a birth after the death keeps the text and reads nothing"
    (let [e (gnd/entry {"id" "https://d-nb.info/gnd/y" "type" ["Person"] "preferredName" "X, Y"
                        "dateOfBirth" ["1900"] "dateOfDeath" ["1800"]})]
      (is (= {:text "b. 1900, d. 1800"} (:dates e)))))
  (testing "a subject heading, a blank line and a broken line are no entries"
    (is (= {:skipped :not-an-agent}
           (gnd/read-line-entry (json/write-str {"id" "https://d-nb.info/gnd/4061414-1" "type" ["SubjectHeading"]
                                                 "preferredName" "Übersetzer"}))))
    (is (= {:skipped :blank} (gnd/read-line-entry "  ")))
    (is (= {:skipped :not-json} (gnd/read-line-entry "{\"id\": ")))
    (is (= {:skipped :not-json} (gnd/read-line-entry "[1, 2]")))))

(deftest a-corporate-body-and-a-family-say-their-kind
  (is (= "corporateBody" (:entity-type (gnd/entry {"id" "https://d-nb.info/gnd/c" "type" ["CorporateBody" "AuthorityResource"] "preferredName" "C"}))))
  (is (= "family" (:entity-type (gnd/entry {"id" "https://d-nb.info/gnd/f" "type" ["Family"] "preferredName" "F"})))))

(deftest an-undifferentiated-person-is-never-a-person-entry
  (testing "a Tn record bundles several people under one name: it is skipped, not matched"
    (is (= {:skipped :undifferentiated-person}
           (gnd/read-line-entry (json/write-str {"id" "https://d-nb.info/gnd/1000000000"
                                                 "type" ["UndifferentiatedPerson" "Person" "AuthorityResource"]
                                                 "preferredName" "Müller, Hans"}))))
    (is (nil? (gnd/entry {"id" "https://d-nb.info/gnd/1000000000" "type" ["UndifferentiatedPerson"] "preferredName" "Müller, Hans"})))))

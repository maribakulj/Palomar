(ns palomar.plugins.ead-test
  "EAD finding aids (ADR 0039): one record per unit, read in either EAD
   namespace, with what is not read reported, and an archival view that keeps
   the hierarchy and mints no Work."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.convert :as convert]
            [palomar.plugins.ead :as ead]
            [palomar.plugins.lrmoo.view :as view]))

(def ^:private ead3 "test/fixtures/documentary/ead/ead3/ead3_multi_level_optimum.xml")
(def ^:private ead2002 "test/fixtures/documentary/ead/ead2002/tnah_ead_devoir_genero.xml")

(defn- canon [r p] (keep #(when (and (= p (:predicate %)) (string? (:value %))) (:value %)) (:assertions r)))

(deftest every-unit-is-a-record
  (let [{:keys [records diagnostics]} (ead/importer {} (slurp ead3))
        fonds (first records)]
    (is (= 6 (count records)) "the archdesc and its five components")
    (is (= "Lowell Thomas Papers" (some #(when (= :ead/unittitle (:predicate %)) (:value %)) (:assertions fonds))))
    (is (every? #(= (:id fonds) (some (fn [a] (when (= :ead/parent (:predicate a)) (:value a))) (:assertions %)))
                (filter #(re-matches #"d[0-9a-f]+-0-\d+" (name (:id %))) records))
        "a series names the fonds as its parent")
    (testing "what the importer does not read is reported"
      (is (contains? (set (map #(get-in % [:detail :loss/source-field]) diagnostics)) :ead/controlaccess)))
    (testing "each value says where it was read"
      (is (re-find #"archdesc'\]\[1\]/\*\[local-name\(\)='did'\]\[1\]/\*\[local-name\(\)='unittitle'\]"
                   (get-in (first (filter #(= :ead/unittitle (:predicate %)) (:assertions fonds)))
                           [:provenance :evidence 0 :selector :value]))))))

(deftest ead-2002-reads-too
  (let [{:keys [records]} (convert/to-wemi :ead {} (slurp ead2002))]
    (is (= 32 (count records)))
    (is (= ["Cité internationale universitaire de Paris (CIUP)"] (canon (first records) :canon/agent)))
    (is (= ["20090013"] (canon (first records) :canon/identifier)))))

(deftest a-unit-is-named-by-the-source-s-own-identifiers
  (let [ids (fn [x] (mapv :id (:records (ead/importer {} x))))
        xml (slurp ead3)]
    (testing "editing a note changes no unit id (post-audit test 10)"
      (is (= (ids xml) (ids (str/replace xml "Lowell Thomas Papers" "Lowell Thomas Papers (revised)")))))
    (testing "the same fonds, by agency, recordid and unitid, has one id in two descriptions of it"
      (is (contains? (set (ids (slurp "test/fixtures/documentary/ead/ead3/ead3_single_level_optimum.xml")))
                     (first (ids xml)))))
    (testing "ids read back as EDN"
      (is (every? #(= % (edn/read-string (str %))) (ids xml)))))
  (testing "a finding aid with no identifier is named by its digest, and says so"
    (let [{:keys [records diagnostics]} (ead/importer {} (slurp ead2002))]
      (is (some #(= :ead/recordid (get-in % [:detail :loss/source-field])) diagnostics))
      (is (= 32 (count (distinct (map :id records))))))))

(defn- of-kind [r k] (filter #(= k (:kind %)) (:entities r)))

(defn- value [r s p] (some #(when (and (= s (:subject %)) (= p (:predicate %))) (:value %)) (:assertions r)))

(deftest the-archival-view-is-ric-o
  (let [{:keys [records]} (convert/to-wemi :ead {} (slurp ead3))
        [fonds series]    records
        set-of            (comp :id first #(of-kind % :rico/RecordSet))]
    (testing "a unit is no edition: no Work, Expression or Manifestation (ADR 0045)"
      (is (every? #(and (empty? (view/works %)) (empty? (view/expressions %)) (empty? (view/manifestations %))) records)))
    (testing "the collection (archdesc level=collection) is a RecordSet of type Collection, with its title, identifier and creator"
      (let [u (set-of fonds)]
        (is (= "Lowell Thomas Papers" (value fonds u :rico/title)))
        (is (= "MA-1" (value fonds u :rico/identifier)))
        (is (= "https://www.ica.org/standards/RiC/vocabularies/recordSetTypes#Collection"
               (:iri (first (of-kind fonds :rico/RecordSetType)))))
        (is (= 1 (count (of-kind fonds :rico/Person))) "persname origination is a Person")
        (is (some #(and (= u (:subject %)) (= :rico/hasCreator (:predicate %))) (:assertions fonds)))))
    (testing "a series is included in the fonds, asserted"
      (is (some #(and (= (set-of series) (:subject %)) (= :rico/isOrWasIncludedIn (:predicate %))
                      (= (set-of fonds) (get-in % [:value :value/target])) (= :asserted (:status %)))
                (:assertions series))))
    (testing "the RDF says it in RiC-O"
      (let [nt (:output (convert/convert {:from :ead :to :ntriples :source (slurp ead3)}))]
        (is (str/includes? nt "RiC/ontology#RecordSet"))
        (is (str/includes? nt "RiC/ontology#isOrWasIncludedIn"))
        (is (not (str/includes? nt "lrmoo/F3_Manifestation")))))
    (testing "pure CRM has no archival view, and says so"
      (let [{:keys [loss]} (convert/convert {:from :ead :to :crm-only :source (slurp ead3)})]
        (is (some #{:rico/isOrWasIncludedIn} (:source-fields loss)))))))

(deftest a-finding-aid-converts-to-every-format
  (doseq [to [:ntriples :crm-only :linked-art :dc :marc21 :mods :trig]]
    (is (string? (:output (convert/convert {:from :ead :to to :source (slurp ead3)}))) (str to))))

(defn- mini-ead
  "A small EAD3 finding aid with a fonds `F` and one `c01` per unitid of `uids`
   (nil: a component with no unitid)."
  [& uids]
  (str "<ead xmlns=\"http://ead3.archivists.org/schema/\"><control><recordid>FA1</recordid>"
       "<maintenanceagency><agencycode>FR-X</agencycode></maintenanceagency></control>"
       "<archdesc level=\"fonds\"><did><unitid>F</unitid><unittitle>Fonds</unittitle></did><dsc>"
       (apply str (map-indexed (fn [i u] (str "<c01><did>" (when u (str "<unitid>" u "</unitid>"))
                                              "<unittitle>c" i "</unittitle></did></c01>"))
                               uids))
       "</dsc></archdesc></ead>"))

(deftest a-duplicated-unitid-moves-only-its-duplicate
  (testing "a revision adding a second unit with unitid A leaves every existing id alone (post-audit 2, D4)"
    (let [before (mapv :id (:records (ead/importer {} (mini-ead "A" "B"))))
          {:keys [records diagnostics]} (ead/importer {} (mini-ead "A" "B" "A"))
          after  (mapv :id records)]
      (is (= before (take 3 after)) "the fonds, the first A and B keep their ids")
      (is (= 4 (count (distinct after))) "the new A has an id of its own")
      (testing "and the importer says which id is not durable, and why"
        (is (some #(and (= :ead/unitid (get-in % [:detail :loss/source-field]))
                        (= (nth after 3) (:subject %))
                        (str/includes? (:message %) "\"A\""))
                  diagnostics)))))
  (testing "a unit with no unitid is named by its position, and the importer says so"
    (let [{:keys [records diagnostics]} (ead/importer {} (mini-ead "A" nil))
          unnamed (:id (last records))]
      (is (some #(and (= :ead/unitid (get-in % [:detail :loss/source-field])) (= unnamed (:subject %))) diagnostics))
      (is (not-any? #(= (:id (second records)) (:subject %)) diagnostics) "a unit named by its unitid is durable"))))

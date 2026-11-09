(ns palomar.agent-dates-test
  "An agent's dates, as intervals that keep their uncertainty, and when two
   agents' dates cannot be one person's (lot M3). The policy errs towards no
   evidence: what is not read, or read in part, blocks nothing."
  (:require [clojure.test :refer [deftest is testing]]
            [palomar.agent-dates :as d]))

(defn- iv [p] (when p [(:from p) (:to p)]))

(defn- roles
  "The roles of `s`'s profile as `{role [from to]}`."
  [s]
  (into {} (for [[k p] (dissoc (d/parse-life-dates s) :text)] [k (iv p)])))

(deftest a-point-is-an-interval-of-years
  (testing "a year is itself"
    (is (= {:from 1802 :to 1802 :text "1802"} (d/parse-point "1802")))
    (is (= {:from 1802 :to 1802 :text "1802-07-24"} (d/parse-point "1802-07-24")) "an ISO date, its year")
    (is (= {:from 1802 :to 1802 :text "1802-07"} (d/parse-point "1802-07"))))
  (testing "a century or a decade with explicit unknown digits is every year in it"
    (is (= {:from 1800 :to 1899 :text "18.."} (d/parse-point "18..")))
    (is (= {:from 1800 :to 1899 :text "18XX"} (d/parse-point "18XX")) "EDTF unspecified digits")
    (is (= {:from 1820 :to 1829 :text "182X"} (d/parse-point "182X"))))
  (testing "an approximate or uncertain year is widened by the margin, and says so"
    (is (= 20 d/margin))
    (is (= {:from 1810 :to 1850 :text "ca. 1830" :approximate? true} (d/parse-point "ca. 1830")))
    (is (= {:from 1810 :to 1850 :text "vers 1830" :approximate? true} (d/parse-point "vers 1830")))
    (is (= {:from 1810 :to 1850 :text "1830~" :approximate? true} (d/parse-point "1830~")) "EDTF approximate")
    (is (= {:from 1782 :to 1822 :text "1802?" :uncertain? true} (d/parse-point "1802?")) "EDTF uncertain")
    (is (= {:from 1782 :to 1822 :text "1802%" :approximate? true :uncertain? true} (d/parse-point "1802%"))))
  (testing "before and after leave one end open"
    (is (= {:from nil :to 1850 :text "av. 1850"} (d/parse-point "av. 1850")))
    (is (= {:from 1850 :to nil :text "après 1850"} (d/parse-point "après 1850")))
    (is (= {:from nil :to 1850 :text "..1850"} (d/parse-point "..1850")) "EDTF open start"))
  (testing "points, as a table"
    (doseq [[s expected] [["804." nil] ["870." nil] ["1870." [1870 1870]] ["182." nil] ["2010-02" [2010 2010]]
                          ["ap. J.-C." nil] ["approx. 1830" [1810 1850]] ["after 1850" [1850 nil]] ["c1500" [1480 1520]]
                          ["~1830" [1810 1850]] ["?1830" [1810 1850]] ["[1830]" nil] ["1830 ca." [1810 1850]]
                          ["um 1500" [1480 1520]] ["1820s" [1820 1829]] ["1500s" [1500 1599]]
                          ["-0384" nil] ["384 BC" nil] ["v384" nil] ["0384 av. J.-C." nil]]]
      (is (= expected (iv (d/parse-point s))) (pr-str s))))
  (testing "an unknown or unparseable point is nil"
    (is (nil? (d/parse-point "....")))
    (is (nil? (d/parse-point "")))
    (is (nil? (d/parse-point nil)))
    (is (nil? (d/parse-point "inconnu")))
    (is (nil? (d/parse-point "182.")) "a decade, or a year 182 and an ISBD period: not read")))

(def ^:private pere {:birth [1802 1802] :death [1870 1870]})

(def ^:private life-table
  "Each string and the roles it is read as; `{}`: kept as text, no evidence."
  [["1802-1870" pere] ["1824-1895" {:birth [1824 1824] :death [1895 1895]}]
   ["18..-19.." {:birth [1800 1899] :death [1900 1999]}]
   ["ca 1500-1560" {:birth [1480 1520] :death [1560 1560]}]
   ["1490?-1543" {:birth [1470 1510] :death [1543 1543]}]
   ["fl. 1850-1870" {:active [1850 1870]}] ["active 1820s" {:active [1820 1829]}]
   ["born 1769" {:birth [1769 1769]}] ["d. 1821" {:death [1821 1821]}]
   ["16..?" {}] ["av. J.-C." {}] ["-0384--0322" {}]
   ["1933-...." {:birth [1933 1933]}] ["XVe siècle" {}]
   ["1820/1825-1890" {:birth [1820 1825] :death [1890 1890]}]
   ["1890-ca. 1950" {:birth [1890 1890] :death [1930 1970]}]
   ["um 1500" {}] ["geb. 1802" {:birth [1802 1802]}] ["1802-1870 ; 1824-1895" {}]
   ["1802–1870" pere] ["1802—1870" pere] ["1802 - 1870" pere]
   ["0384-0322 av. J.-C." {}] ["384-322 B.C." {}] ["427-347 v. Chr." {}] ["384-322 BC" {}]
   ["735-804." {:birth [735 735] :death [804 804]}] ["735-804" {:birth [735 735] :death [804 804]}]
   ["1802-1870." pere] ["1802-1870," pere] ["-1870" {}] ["1933-" {:birth [1933 1933]}]
   ["approx. 1830" {}] ["approximately 1490-1543" {:birth [1470 1510] :death [1543 1543]}]
   ["active 1850-1870" {:active [1850 1870]}] ["fl. ca. 1850" {:active [1830 1870]}]
   ["1878 or 1879-1950" {:birth [1878 1879] :death [1950 1950]}]
   ["1802-07" {}] ["1914-18" {}] ["b. 1802?" {:birth [1782 1822]}]
   ["-0063/0014" {}] ["0063 av. J.-C.-0014" {}] ["v384-v322" {}]
   ["1500?-1560?" {:birth [1480 1520] :death [1540 1580]}]
   ["15..-15.." {:birth [1500 1599] :death [1500 1599]}]
   ["c. 1500-1560" {:birth [1480 1520] :death [1560 1560]}] ["ca. 1500" {}]
   ["vers 1500-1560" {:birth [1480 1520] :death [1560 1560]}]
   ["après 1850-1870" {:birth [1850 nil] :death [1870 1870]}]
   ["av. 1850-1870" {:birth [nil 1850] :death [1870 1870]}]
   ["1802-1870 (père)" {}] ["1802-1870?" {:birth [1802 1802] :death [1850 1890]}]
   ["ca 870-930" {:birth [850 890] :death [930 930]}] ["d. 1821." {:death [1821 1821]}]
   ["1450 ca.-1516" {:birth [1430 1470] :death [1516 1516]}]
   ["1880/1890" {:birth [1880 1880] :death [1890 1890]}]
   ["1990-02/2010" {:birth [1990 1990] :death [2010 2010]}]
   ["née 1802" {:birth [1802 1802]}] ["Né en 1802" {:birth [1802 1802]}] ["1802 -" {:birth [1802 1802]}]
   ["fl. 1500s" {:active [1500 1599]}] ["1802-1870 ca." {:birth [1802 1802] :death [1850 1890]}]
   ["1802..1870" {}] ["1802-1870/1871" {:birth [1802 1802] :death [1870 1871]}]
   ["1488/90-1576" {:birth [1488 1490] :death [1576 1576]}]
   ["1488/1490-1576" {:birth [1488 1490] :death [1576 1576]}]
   ["1800-182." {}]])

(deftest life-dates-are-birth-death-and-activity
  (testing "a span is a birth and a death, keeping the source text"
    (is (= {:text "1802-1870" :birth {:from 1802 :to 1802 :text "1802"} :death {:from 1870 :to 1870 :text "1870"}}
           (d/parse-life-dates "1802-1870")))
    (is (= {:text "1802-07-24/1870-12-05" :birth {:from 1802 :to 1802 :text "1802-07-24"}
            :death {:from 1870 :to 1870 :text "1870-12-05"}}
           (d/parse-life-dates "1802-07-24/1870-12-05")) "EDTF interval of ISO dates"))
  (testing "an alternative keeps both years"
    (is (= {:from 1488 :to 1490 :text "1488/90"} (:birth (d/parse-life-dates "1488/90-1576")))))
  (testing "every string of the table"
    (doseq [[s expected] life-table]
      (is (= expected (roles s)) (pr-str s))))
  (testing "what cannot be read, or is read only in part, is kept as text and contributes nothing"
    (is (= {:text "1802"} (d/parse-life-dates "1802")) "a year alone says neither birth nor death")
    (is (= {:text "vers 1800, après 1850"} (d/parse-life-dates "vers 1800, après 1850")))
    (is (= {:text "1802-1870 (père)"} (d/parse-life-dates "1802-1870 (père)")) "one side unread: nothing read")
    (is (= {:text "384-322 B.C."} (d/parse-life-dates "384-322 B.C.")) "never a year of the common era")
    (is (= {:text "1800-182."} (d/parse-life-dates "1800-182.")) "a birth after the death is no reading")
    (is (nil? (d/parse-life-dates nil)))
    (is (nil? (d/parse-life-dates "  ")))))

(defn- v [a b] (:verdict (d/compatibility (d/parse-life-dates a) (d/parse-life-dates b))))

(deftest dates-are-incompatible-only-when-they-cannot-overlap
  (testing "Dumas père and fils: births 1802 and 1824, 22 years apart, cannot be one person's"
    (is (= :incompatible (v "1802-1870" "1824-1895")))
    (is (= :incompatible (v "ca. 1802-1870" "1824-1895")) "the deaths still part them")
    (is (= :compatible (v "1802-1870" "1802-1870"))))
  (testing "an exact year is compatible within the tolerance"
    (is (= 2 d/tolerance))
    (is (= :compatible (v "1802-1870" "1804-1872")))
    (is (= :incompatible (v "1802-1870" "1805-1870"))))
  (testing "the contradictor's pairs, as a table"
    (doseq [[a b expected] [["ca. 1500-1560" "1488-1560" :compatible] ["vers 1500-1560" "1488-1560" :compatible]
                            ["1490?-1543" "1478-1543" :compatible] ["735-804." "735-804" :compatible]
                            ["1820/1825-1890" "1825-1890" :compatible] ["fl. 1850-1870" "1802-1870" :compatible]
                            ["fl. 1850-1880" "1802-1870" :compatible] ["1933-...." "1933-2017" :compatible]
                            ["1933-" "1933-2017" :compatible] ["16..?" "1712-1780" :unknown]
                            ["d. 1821" "1769-1821" :compatible] ["b. 1769" "1769-1821" :compatible]
                            ["384-322 B.C." "-0384/-0322" :unknown] ["384-322 B.C." "0384-0322" :unknown]
                            ["ca 870-930" "870-930" :compatible] ["1802-1870" "18..-18.." :compatible]
                            ["1802-1870" "fl. 1840" :compatible] ["fl. 1840" "fl. 1840" :unknown]
                            ["1488/90-1576" "1490-1576" :compatible] ["1488/1490-1576" "1490-1576" :compatible]
                            ["1802-1870/1871" "1802-1870" :compatible] ["1802-1870/1871" "1802-1871" :compatible]
                            ["ca. 1477-1576" "1488-1576" :compatible] ["735-804." "ca. 740-804" :compatible]]]
      (is (= expected (v a b)) (str a " | " b))))
  (testing "uncertainty is kept: an open century, an approximate year"
    (is (= :compatible (v "18..-18.." "1824-1895")))
    (is (= :compatible (v "ca. 1830-ca. 1890" "1824-1895")))
    (is (= :incompatible (v "ca. 1850-1890" "1824-1895")) "1830-1870 cannot contain 1824")
    (is (= :compatible (v "1802?-1870" "1820-1870")) "1802? reaches 1822, and the tolerance 1824")
    (is (= :incompatible (v "1802?-1870" "1826-1870"))))
  (testing "an end the source leaves open is no evidence"
    (is (= :incompatible (v "1933-...." "1951-...."))))
  (testing "a birth after the other's death"
    (is (= :incompatible (v "b. 1900" "d. 1870")))
    (is (= :compatible (v "b. 1850" "d. 1870")))
    (is (= :compatible (v "1933-...." "....-2017")))
    (is (= :incompatible (v "1933-...." "....-1870"))))
  (testing "activity must fall within the other's life"
    (is (= :compatible (v "fl. 1850" "1802-1870")))
    (is (= :incompatible (v "fl. 1900" "1802-1870")))
    (is (= :unknown (v "fl. 1850" "fl. 1900")) "two activities need not overlap"))
  (testing "missing, unreadable or partly read dates on either side are no evidence, never incompatibility"
    (is (= :unknown (:verdict (d/compatibility nil (d/parse-life-dates "1802-1870")))))
    (is (= :unknown (:verdict (d/compatibility (d/parse-life-dates "1802-1870") nil))))
    (is (= :unknown (v "1802" "1824-1895")))
    (is (= :unknown (v "1824-1895 (fils)" "1802-1870")))
    (is (= :unknown (v "-1870" "1802-1830")) "a leading minus may be an era: not read"))
  (testing "the verdict names what it compared"
    (is (= {:verdict :incompatible
            :checks  [{:compared [:birth :birth] :a {:from 1802 :to 1802 :text "1802"} :b {:from 1824 :to 1824 :text "1824"} :ok? false}
                      {:compared [:death :death] :a {:from 1870 :to 1870 :text "1870"} :b {:from 1895 :to 1895 :text "1895"} :ok? false}
                      {:compared [:birth :death] :a {:from 1802 :to 1802 :text "1802"} :b {:from 1895 :to 1895 :text "1895"} :ok? true}
                      {:compared [:death :birth] :a {:from 1870 :to 1870 :text "1870"} :b {:from 1824 :to 1824 :text "1824"} :ok? true}]}
           (d/compatibility (d/parse-life-dates "1802-1870") (d/parse-life-dates "1824-1895"))))))

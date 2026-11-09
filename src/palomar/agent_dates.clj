(ns palomar.agent-dates
  "An agent's dates — birth, death, activity — as intervals of years that keep
   their uncertainty, and whether two agents' dates can be one person's (lot M3,
   ADR 0018 amendment). Pure functions of strings and maps: no clock, no locale.

   The policy errs towards no evidence: a date this grammar does not read, or
   reads only in part, is kept as text and blocks nothing. A false rejection
   (the right authority withheld) is worse than a missed one.

   A **point** is an interval `{:from :to :text}` of years, either end nil when
   open:
   - `1802`, an ISO date `1802-07-24`, a month `1802-07` → 1802–1802; a
     three-digit year `804`; `1880-85`, two digits after the year's last
     two and no month, → 1880–1885 (lot M7: no narrower than the source);
   - a century or decade with explicit unknown digits, `18..` / `18XX` →
     1800–1899, `182X` → 1820–1829; `1820s` → 1820–1829, `1800s` → 1800–1899;
     `182.` is ambiguous (a decade, or a year 182 and an ISBD period) and is
     not read;
   - approximate (`ca.`, `circa`, `c.`, `c1775`, `vers`, `env.`, `um`,
     `gegen`, `etwa`, `approx.`, a trailing `ca.` or `c.`, EDTF `~`) or uncertain (EDTF `?`; `%` is both) → widened
     by `margin` years on each side, flagged `:approximate?` / `:uncertain?`;
   - `av.`/`avant`/`before`/`vor`/EDTF `..1850` → open before,
     `ap.`/`après`/`after`/`nach` → open after;
   - inside a span, alternatives `1488/90`, `1488/1490`, `1878 or 1879` → the
     interval that holds both (a short second form takes the first's century).
   A trailing ISBD period or `,` `;` `:` is dropped. An unknown point (`....`,
   blank) or one this grammar does not read is nil.

   **Before the common era**, any era marker (`B.C.`, `BC`, `BCE`, `av.
   J.-C.`, `v. Chr.`, `a. C.`, a leading `-` as in EDTF `-0384`, GND's `v384`)
   makes the whole string unread: it is never taken for a year of the common
   era.

   **Life dates** are a profile `{:text :birth :death :active}`, each role
   present only when read: a span `1802-1870` (or EDTF `1802/1870`) is a birth
   and a death, either end left open (`1933-....`: no death known), each side
   possibly an alternative (`1488/90-1576`, `1802-1870/1871`); `fl.` /
   `floruit` / `actif` an activity; `b.`/`né`/`geb.` a birth, `d.`/`mort`/
   `gest.` a death. A year alone says neither birth nor death. A string read
   only in part (one side of a span unreadable), or whose birth falls after its
   death, is kept as `:text` only: it contributes nothing.

   **Compatibility** (`compatibility`): two profiles can be one person's unless
   an interval check fails — births overlap, deaths overlap, neither is born
   after the other's death, and an activity falls within the other's life —
   every comparison allowing `tolerance` years (sources disagree by a year or
   two, the Julian and Gregorian calendars too). With no check possible (a
   side without dates, two activities) the verdict is `:unknown`: missing
   dates are no evidence, never incompatibility."
  (:require [clojure.string :as str]))

(def margin
  "Years an approximate (`ca. 1830`) or uncertain (`1830?`) year is widened by,
   on each side. A stated policy, not a measure: \"about 1830\" can be twenty
   years off."
  20)

(def tolerance
  "Years every comparison allows between two dates, exact ones included:
   sources disagree by a year or two (a birth registered late, the Julian and
   Gregorian calendars). Dumas père (1802) and fils (1824), 22 years apart,
   stay apart."
  2)

(defn- le
  "`x` <= `y` + `tolerance`, an open end (nil) satisfying it."
  [x y]
  (or (nil? x) (nil? y) (<= x (+ y tolerance))))

(def ^:private era-re
  "An era marker of a date before the common era."
  #"(?i)\bB\.?\s?C\.?|\bav\.?\s*J\.?\s*-?\s*C|\bv\.\s*Chr|\ba\.\s?C\.|^\s*-\s*\d|/\s*-\s*\d|--\s*\d|(?:^|[\s/(\-])v\s?\d")

(defn- era? [s] (boolean (re-find era-re s)))

(defn before-common-era?
  "Whether `s` carries an era marker of a date before the common era (see
   the ns doc): a string that does is never read as a year (lot M7 reads an
   event's display date with it, whatever its earliest and latest say)."
  [s]
  (boolean (and s (era? s))))

(def ^:private approx-prefix #"^(?:circa|ca\.?(?!\p{L})|c\.?(?=\s*\d)|vers(?!\p{L})|environ|env\.?(?!\p{L})|approximately|approx\.?(?!\p{L})|um(?=\s)|gegen(?=\s)|etwa(?=\s))\s*")
(def ^:private approx-suffix #"\s*(?:ca\.?|circa|c\.?)$")
(def ^:private before-prefix #"^(?:avant|av\.?(?!\p{L})|before|bef\.?(?!\p{L})|vor(?!\p{L}))\s*")
(def ^:private after-prefix #"^(?:après|apres|ap\.?(?!\p{L})|after|aft\.?(?!\p{L})|nach(?!\p{L}))\s*")

(defn- strip [re s] (when-let [m (re-find re s)] (subs s (count (if (string? m) m (first m))))))

(defn- year-core
  "`[from to]` of a bare year form, or nil."
  [s]
  (or (when-let [[_ y] (re-matches #"(\d{3,4})(?:-\d{2}-\d{2})?" s)] (let [y (parse-long y)] [y y]))
      ;; `1965-04` is an ISO month; `1880-85`, two digits that are no month
      ;; and come after the year's last two, a span 1880-1885 (lot M7: read
      ;; no narrower than the source)
      (when-let [[_ y n] (re-matches #"(\d{4})-(\d{2})" s)]
        (let [y (parse-long y) n (parse-long n)]
          (cond (<= 1 n 12)       [y y]
                (> n (mod y 100)) [y (+ (* 100 (quot y 100)) n)])))
      (when-let [[_ c] (re-matches #"(\d{2})(?:\.\.|[xX]{2}|00s)" s)] (let [y (* 100 (parse-long c))] [y (+ y 99)]))
      (when-let [[_ c] (re-matches #"(\d{3})(?:[xX]|0s)" s)] (let [y (* 10 (parse-long c))] [y (+ y 9)]))))

(defn- clean
  "`s` without trailing `,` `;` `:` and, unless it is part of a `..`, a final
   ISBD period."
  [s]
  (let [s (str/replace s #"[\s,;:]+$" "")]
    (if (re-find #"[^.]\.$" s) (subs s 0 (dec (count s))) s)))

(defn parse-point
  "The interval of years a date `s` names (see the ns doc), or nil."
  [s]
  (let [text (some-> s str/trim not-empty)]
    (when (and text
               (not (era? text))
               ;; `182.`: a decade, or a three-digit year and an ISBD period
               (not (re-find #"(?:^|[^\d.])\d{1,3}\.$" text)))
      (let [t        (.toLowerCase ^String (clean text) java.util.Locale/ROOT)
            [t open] (cond
                       (strip before-prefix t)    [(strip before-prefix t) :before]
                       (strip after-prefix t)     [(strip after-prefix t) :after]
                       (re-find #"^\.\.\d" t)     [(subs t 2) :before]
                       :else                      [t nil])
            [t pre]  (cond
                       (strip approx-prefix t)    [(strip approx-prefix t) "~"]
                       (re-find #"^[?~%]\d" t)    [(subs t 1) (subs t 0 1)]
                       :else                      [t nil])
            [t post] (if (re-find approx-suffix t) [(str/replace t approx-suffix "") "~"] [t nil])
            [_ core suffix] (re-matches #"(.*?)([?~%]?)" (str/trim t))
            quals    (set (mapcat seq (remove nil? [pre post (not-empty suffix)])))
            approx?  (boolean (or (quals \~) (quals \%)))
            uncert?  (boolean (or (quals \?) (quals \%)))
            [lo hi]  (year-core core)]
        (when lo
          (let [w (if (or approx? uncert?) margin 0)]
            (cond-> {:from (when-not (= :before open) (- lo w))
                     :to   (when-not (= :after open) (+ hi w))
                     :text text}
              approx? (assoc :approximate? true)
              uncert? (assoc :uncertain? true))))))))

(defn- parse-alternative
  "The interval of a span side `s`: a point, or two alternatives (`1488/90`,
   `1488/1490`, `1878 or 1879`) as the interval holding both; nil when it is
   not read."
  [s]
  (let [[a b :as parts] (str/split s #"\s*/\s*|\s+(?:or|ou|oder)\s+")]
    (if (= 2 (count parts))
      (let [b  (if-let [[_ y] (and (re-matches #"\d{1,3}" b) (re-find #"(\d{4})" a))]
                 (str (subs y 0 (- 4 (count b))) b)
                 b)
            pa (parse-point a)
            pb (parse-point b)]
        (when (and pa pb (:from pa) (:to pb))
          (cond-> {:from (min (:from pa) (:from pb)) :to (max (:to pa) (:to pb)) :text s}
            (or (:approximate? pa) (:approximate? pb)) (assoc :approximate? true)
            (or (:uncertain? pa) (:uncertain? pb))     (assoc :uncertain? true))))
      (when (= 1 (count parts)) (parse-point s)))))

(defn- unknown-side? [s] (boolean (re-matches #"[.?\s]*" s)))

(defn- read-sides
  "`[a b]`, the sides of a span read by `read-side`, or nil when a side that
   says something is not read, or neither side is."
  [sides read-side]
  (let [read (map #(when-not (unknown-side? %) (read-side %)) sides)]
    (when (and (every? (fn [[s r]] (or r (unknown-side? s))) (map vector sides read))
               (some some? read))
      (vec read))))

(defn- read-span
  "The `[birth death]` intervals of a span `s` (either may be nil), or nil.
   The life-span dash first, each side an alternative; else an EDTF interval
   `a/b`."
  [s]
  (let [dashed (str/split s #"\s*[-–—]\s*" -1)
        slashed (str/split s #"\s*/\s*" -1)]
    (or (when (= 2 (count dashed)) (read-sides dashed parse-alternative))
        (when (= 2 (count slashed)) (read-sides slashed parse-point)))))

(def ^:private role-prefixes
  "A stated role and the dates after it."
  [[#"(?i)^(?:fl\.?|floruit|actif en|actif|active in|active)\s*(.*)$" :active]
   [#"(?i)^(?:b\.|born|née|né|geb\.?|naissance)(?:\s+(?:en|in))?\s*(.*)$" :birth]
   [#"(?i)^(?:d\.|died|morte|mort|gest\.?|décès)(?:\s+(?:en|in))?\s*(.*)$" :death]])

(defn- sane
  "`profile`, unless its birth falls after its death (beyond `tolerance`): a
   self-contradicting reading, which is no evidence."
  [profile]
  (let [b (:from (:birth profile)) d (:to (:death profile))]
    (when-not (and b d (> b (+ d tolerance))) profile)))

(defn- read-life [t]
  (if-let [[r rest] (some (fn [[re r]] (when-let [[_ rest] (re-matches re t)] [r rest])) role-prefixes)]
    (let [rest (str/trim rest)]
      (if-let [[a b] (read-span rest)]
        (when (and a b (le (:from a) (:to b)))
          {r {:from (:from a) :to (:to b) :text rest}})
        (when-let [p (parse-alternative rest)] {r p})))
    ;; a single point, an ISO date included, is no role
    (when-not (parse-point t)
      (when-let [[b d] (read-span t)]
        (sane (cond-> {} b (assoc :birth b) d (assoc :death d)))))))

(defn parse-life-dates
  "The life-dates profile of `s` (see the ns doc): `{:text}` plus the roles it
   reads, nil when `s` is blank."
  [s]
  (when-let [text (some-> s str/trim not-empty)]
    (merge {:text text}
           (when-not (era? text) (read-life (clean text))))))

;; ---------------------------------------------------------------------------
;; Combining what a source says (EAC-CPF's attributes and date sets)
;; ---------------------------------------------------------------------------

(def ^:private qualifier-re
  #"(?i)(?:^|[^\p{L}])(?:circa|ca\.?|c\.?(?=\s*\d)|vers|environ|env\.|approximately|approx\.?|um|gegen|etwa)(?![\p{L}])|\d\s*c\.?(?!\p{L})|[~?%]")

(defn qualify
  "`point` widened by `margin` and flagged when `text` (the source's own
   writing of it) says it is approximate or uncertain and `point`, read from
   elsewhere (a normalized attribute), does not."
  [point text]
  (if (and point text (not (:approximate? point)) (not (:uncertain? point)) (re-find qualifier-re text))
    (cond-> (assoc point :approximate? true)
      (:from point) (update :from - margin)
      (:to point)   (update :to + margin))
    point))

(defn hull
  "The interval holding every interval of `ps` (nils skipped): an open end in
   any is open in it. Flags are kept; `:text` is the first's. Nil when `ps`
   holds none."
  [ps]
  (let [ps (remove nil? ps)]
    (when (seq ps)
      (cond-> {:from (when (every? :from ps) (apply min (map :from ps)))
               :to   (when (every? :to ps) (apply max (map :to ps)))}
        (some :text ps)         (assoc :text (some :text ps))
        (some :approximate? ps) (assoc :approximate? true)
        (some :uncertain? ps)   (assoc :uncertain? true)))))

(defn alternatives
  "The profile of `profiles` taken as alternatives (one of them is the
   person's): a role is kept only when every profile states it, as the
   interval holding them all — never narrower than the source."
  [profiles]
  (into {} (for [role [:birth :death :active]
                 :let [ivs (map role profiles)]
                 :when (and (seq profiles) (every? some? ivs))]
             [role (hull ivs)])))

;; ---------------------------------------------------------------------------
;; Compatibility
;; ---------------------------------------------------------------------------

(defn- overlap? [x y]
  (and (le (:from x) (:to y)) (le (:from y) (:to x))))

(defn- life
  "The span a profile's person lived in, from its birth to its death, or nil
   when it states neither."
  [p]
  (when (or (:birth p) (:death p))
    {:from (:from (:birth p)) :to (:to (:death p))}))

(defn compatibility
  "Whether the profiles `a` and `b` can be one person's dates:
   `{:verdict :compatible|:incompatible|:unknown :checks [{:compared [ra rb] :a :b :ok?}]}`,
   each check naming the roles and the intervals it compared, each allowing
   `tolerance` years. Incompatible when a check fails; unknown when none
   applies."
  [a b]
  (let [check  (fn [ra rb ia ib ok?] (when (and ia ib) {:compared [ra rb] :a ia :b ib :ok? (boolean (ok? ia ib))}))
        before (fn [birth death] (le (:from birth) (:to death)))
        checks (vec (keep identity
                          [(check :birth :birth (:birth a) (:birth b) overlap?)
                           (check :death :death (:death a) (:death b) overlap?)
                           (check :birth :death (:birth a) (:death b) before)
                           (check :death :birth (:death a) (:birth b) (fn [d bi] (before bi d)))
                           (check :active :life (:active a) (life b) overlap?)
                           (check :life :active (life a) (:active b) overlap?)]))]
    {:verdict (cond (empty? checks)              :unknown
                    (every? :ok? checks)        :compatible
                    :else                       :incompatible)
     :checks  checks}))

(defn dated?
  "Whether profile `p` states a birth, a death or an activity."
  [p]
  (boolean (or (:birth p) (:death p) (:active p))))

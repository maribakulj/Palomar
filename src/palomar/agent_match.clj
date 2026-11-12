(ns palomar.agent-match
  "A Fellegi-Sunter score for a name mention against an authority entry (lot
   M4, ADR 0018 amendment). Pure functions of plain maps: no clock, no
   randomness, no dependency; the same comparisons and parameters give the
   same weights, to the bit (`StrictMath`).

   **Comparison vector.** A (mention, candidate) pair is compared into one
   level per feature (`compare-pair`):
   - `:name`, the best of the candidate's forms (its heading, its variants), in
     the order of `name-levels`, from most to least agreement: `:exact` (same
     heading, accents, case and punctuation aside), `:initials` (same surname,
     forenames compatible as initials: \"P.-J.\" / \"Pierre-Jules\"),
     `:same-tokens` (the same words in another order), `:surname-typo` (a
     surname one edit away, forenames alike), `:forename-overlap`,
     `:surname-only` (one side has no forename), `:forenames-differ` (same
     surname, forenames on both sides that differ), then, the surnames
     differing, the token-Jaccard bands `:jaccard-high` (>= 0.5),
     `:jaccard-low` (> 0) and `:none`. The Jaccard is one level of one
     feature, not the decision.
   - `:form`, `:heading` when the heading reaches that best level, else
     `:variant` (a `400`, a pseudonym).
   - `:dates`, from `palomar.agent-dates`: `:agree` (compatible, with a birth or
     a death stated as an exact year on both sides, within the tolerance),
     `:compatible` (compatible otherwise: approximate, open, an activity),
     `:unknown` (nothing to compare). `:incompatible` is a block, never a
     weight: such a pair is not scored.
   Nothing else is read: no authority number, no ISNI, no record id.

   **Weights** (`model`, `level-weights`). For a level, m = P(level | same
   person), u = P(level | different persons), from observed counts
   (`level-counts`), and its weight log2(m/u) — but only what was measured
   weighs:
   - a level observed fewer than `min-observed` (5) times on both sides
     weighs 0; observed on one side only, the other side is its upper bound
     5/N, and the weight is the bound it gives, kept only on the side it
     proves (an agreement seen among the same persons and almost never among
     different ones weighs at least log2(m / (5/N_u)), never less than 0; a
     level seen among different persons and almost never among the same
     weighs at most log2((5/N_m) / u), never more than 0) — never log2 of
     pseudo-counts;
   - `:dates :unknown` weighs 0 by rule: missing dates are no evidence (M3);
   - along `name-levels`, weights never increase, and a disagreeing level
     (`disagreeing-levels`) never weighs more than 0.
   Under `o4-rules` (lot O4, ADR 0062), the chain runs over the measured
   levels only (`level-measured?`): an unmeasured level caps none below it;
   it weighs 0, or, for a level that says the surnames agree
   (`surname-levels`), the next measured weight below it when that is above
   0, and never more than the measured level above it. And the form weighs
   only on an exact name (`form-weighed-levels`). Under `n4-rules`, the
   rules of a model that states none, the chain runs over every level and
   the form weighs at every level.

   **Term frequency.** An agreement on a surname weighs by that surname's
   rarity in the pool: the weight of a surname-agreeing name level with a
   positive weight is adjusted by log2(u-mean / u_s), u_s the share of the
   pool's headings with that surname (`surname-counts`, a surname no heading
   holds counting once) and u-mean (`tf-mean`) the mean u_s of an agreement
   between two random headings of the same pool — both read from the pool
   the mention is matched against (`compare-pair`), never one from the
   model and the other from the pool. A wrong candidate is drawn from the
   pool, so the chance that it shares the mention's surname is the surname's
   share of the pool, not of the mentions (where it says how often a person
   is cited, not how ambiguous the name is).

   **Decision.** The total weight w and the prior λ give a posterior
   1/(1+2^-(w + log2(λ/(1-λ)))) (`posterior`); `decide` proposes above
   `:propose`, abstains below `:review`, and flags for review between.
   With a gap G (`competing-gap`, lot O4), a second candidate competes with
   the top one only when it weighs less than G bits below it
   (`competes?`).

   **Calibration, and confidence (ADR 0005 L2).** `calibration` measures
   held-out predictions — reliability bins, Brier score, expected calibration
   error, the distinct pairs and the distinct true mentions they rest on, the
   pairs in the review band, the largest bin gap — on a pool and a gold it
   names by fingerprint. A model's posterior is a confidence only when its
   report meets the model's bar (pinned when the model is built, never laxer
   than `default-bar`, never the bar the report carries) and the pool matched
   against is the one it was measured on (`calibrated?`); for a `:review`
   decision, only when the review band was covered too (`confidence`). A
   proposal carries the proof (`confidence-proof`), which `proven-confidence`
   checks before anything writes a confidence."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [palomar.agent-dates :as dates]
            [palomar.model :as model]
            [palomar.text :as text]))

;; ---------------------------------------------------------------------------
;; Names
;; ---------------------------------------------------------------------------

(defn tokens
  "Normalised token set of a name (`text/norm` then split on whitespace)."
  [s]
  (set (remove str/blank? (str/split (text/norm s) #" "))))

(defn jaccard
  "Token-set Jaccard of two token sets; 0.0 when both are empty."
  [a b]
  (let [u (count (set/union a b))]
    (if (pos? u) (/ (double (count (set/intersection a b))) u) 0.0)))

(defn- split-heading
  "`[surname forenames]` of a heading, normalised: the surname what precedes
   its first comma (the whole name when it has none), the forenames a vector
   of tokens."
  [s]
  (let [[sur fore] (str/split (str s) #"," 2)]
    [(text/norm sur) (vec (remove str/blank? (str/split (text/norm fore) #" ")))]))

(defn surname
  "The normalised surname of heading `s`."
  [s]
  (first (split-heading s)))

(defn- osa
  "The optimal-string-alignment distance of `a` and `b` (Levenshtein plus the
   transposition of two adjacent characters)."
  [a b]
  (let [a (vec a) b (vec b) n (count a) m (count b)]
    (loop [i 1 prev2 nil prev (vec (range (inc m)))]
      (if (> i n)
        (peek prev)
        (let [row (reduce (fn [row j]
                            (let [cost (if (= (a (dec i)) (b (dec j))) 0 1)
                                  v    (min (inc (prev j)) (inc (row (dec j))) (+ cost (prev (dec j))))
                                  v    (if (and prev2 (> j 1) (= (a (dec i)) (b (- j 2))) (= (a (- i 2)) (b (dec j))))
                                         (min v (+ cost (prev2 (- j 2))))
                                         v)]
                              (conj row v)))
                          [i] (range 1 (inc m)))]
          (recur (inc i) prev row))))))

(defn- typo?
  "Two surnames one edit apart (a letter changed, added, dropped, or two
   swapped), both of at least 4 letters: shorter ones are other names."
  [a b]
  (and (not= a b) (<= 4 (count a)) (<= 4 (count b)) (<= (osa a b) 1)))

(defn- initials?
  "Forenames compatible as initials: as many on each side, each pair equal or
   one an initial of the other, at least one initial."
  [fa fb]
  (let [ok (fn [x y] (or (= x y)
                         (and (= 1 (count x)) (str/starts-with? y x))
                         (and (= 1 (count y)) (str/starts-with? x y))))]
    (and (seq fa) (= (count fa) (count fb)) (every? true? (map ok fa fb)) (not= fa fb))))

(def name-levels
  "The levels of the `:name` feature, best first."
  [:exact :initials :same-tokens :surname-typo :forename-overlap :surname-only :forenames-differ
   :jaccard-high :jaccard-low :none])

(def ^:private name-rank (zipmap name-levels (range)))

(def surname-levels
  "The name levels that say the surnames agree (a typo is the candidate's):
   those the term-frequency adjustment applies to."
  #{:exact :initials :surname-typo :forename-overlap :surname-only :forenames-differ})

(defn name-level
  "The `:name` level of mention name `m` against one form `f` of a candidate."
  [m f]
  (let [[sm fm] (split-heading m)
        [sf ff] (split-heading f)
        tm      (tokens m)
        tf      (tokens f)]
    (cond
      (or (empty? tm) (empty? tf))               :none
      (= (text/norm m) (text/norm f))            :exact
      (= tm tf)                                  :same-tokens
      (and (= sm sf) (not (str/blank? sm)))      (cond
                                                   (initials? fm ff)                  :initials
                                                   (or (empty? fm) (empty? ff))       :surname-only
                                                   (seq (set/intersection (set fm) (set ff))) :forename-overlap
                                                   :else                              :forenames-differ)
      (and (typo? sm sf) (or (= fm ff) (initials? fm ff))) :surname-typo
      :else (let [j (jaccard tm tf)]
              (cond (>= j 0.5) :jaccard-high
                    (pos? j)   :jaccard-low
                    :else      :none)))))

;; ---------------------------------------------------------------------------
;; Dates
;; ---------------------------------------------------------------------------

(defn- exact-year? [p] (and (:from p) (= (:from p) (:to p)) (not (:approximate? p)) (not (:uncertain? p))))

(defn dates-level
  "The `:dates` level of a `dates/compatibility` verdict: `:incompatible` (a
   block), `:unknown`, `:agree` when a birth or a death is an exact year on
   both sides (and agrees, within the tolerance), else `:compatible`."
  [{:keys [verdict checks]}]
  (case verdict
    :incompatible :incompatible
    :unknown      :unknown
    (if (some (fn [{[ra rb] :compared a :a b :b}]
                (and (= ra rb) (#{:birth :death} ra) (exact-year? a) (exact-year? b)))
              checks)
      :agree
      :compatible)))

(def dates-levels
  "The levels of the `:dates` feature that are weighed; `:incompatible` is a
   block, not a level."
  [:agree :compatible :unknown])

(def form-levels [:heading :variant])

(def features
  "Each feature and its levels."
  {:name name-levels :form form-levels :dates dates-levels})

;; ---------------------------------------------------------------------------
;; The comparison of one pair
;; ---------------------------------------------------------------------------

(defn surname-counts
  "How many headings (`:label`) of `pool` have each normalised surname."
  [pool]
  (frequencies (keep #(some-> (:label %) surname not-empty) pool)))

(defn tf-mean
  "The u-mean of the term-frequency adjustment: the mean u_s of a surname
   agreement between two headings of a pool drawn at random, Σ n_s³ / (N Σ n_s²)
   for `surname-counts` n_s and pool size N — label-free, and defined even when
   no pair of different persons in the pool agrees on a surname."
  [surname-counts pool-size]
  (let [ns (vals surname-counts)
        sq (reduce + 0.0 (map #(* % %) ns))]
    (when (and (pos? sq) (pos? pool-size))
      (/ (reduce + 0.0 (map #(* % % %) ns)) (* pool-size sq)))))

(defn compare-pair
  "The comparison of mention `{:name :dates}` (dates a parsed profile) with a
   candidate `{:label :variants :dates}`:

     {:levels {:name :form :dates} :name-score j :surname s :surname-u u_s
      :tf-mean ū :dates <dates/compatibility>}

   `:name-score` the best token Jaccard over the forms (M3's score, kept as
   evidence); `:surname`, and `:surname-u` and `:tf-mean` (both from the
   pool's `:surname-counts` and `:pool-size`, the pool the mention is matched
   against), only when the name level is a surname agreement and the pool is
   given. The pool's mean, when `reconcile/prepare` computed it once for
   the run (lot O6), is read from `::tf-mean`, which only `prepare` sets;
   a plain `:tf-mean` is never read. An explicit nil `::tf-mean` beside
   surname counts is refused: such a pool has a mean."
  ([mention candidate] (compare-pair mention candidate nil))
  ([{:keys [name] :as mention} candidate {:keys [surname-counts pool-size] :as pool}]
   (let [label  (:label candidate)
         forms  (remove str/blank? (cons label (:variants candidate)))
         lv     (map (fn [f] [(name-level name f) f]) forms)
         best   (if (seq lv) (apply min-key (comp name-rank first) (reverse lv)) [:none label])
         level  (first best)
         form   (if (and label (= level (name-level name label))) :heading :variant)
         tm     (tokens name)
         compat (dates/compatibility (:dates mention) (:dates candidate))
         s      (when (surname-levels level) (surname (second best)))
         mean   (when (and s pool-size (pos? pool-size)) (if (contains? pool ::tf-mean)
                                                           (or (::tf-mean pool)
                                                               (when (some pos? (vals surname-counts))
                                                                 (throw (ex-info "compare-pair: a nil mean for a pool with surname counts: prepare the pool's statistics with reconcile/prepare"
                                                                                 {:pool-size pool-size}))))
                                                           (tf-mean surname-counts pool-size)))]
     (cond-> {:levels     {:name level :form form :dates (dates-level compat)}
              :name-score (apply max 0.0 (map #(jaccard tm (tokens %)) forms))
              :dates      compat}
       s    (assoc :surname s)
       mean (assoc :surname-u (/ (double (max 1 (get surname-counts s 0))) pool-size) :tf-mean mean)))))

;; ---------------------------------------------------------------------------
;; Estimation
;; ---------------------------------------------------------------------------

(defn- counted
  "The comparisons of `comparisons` that count for feature `f` of `feats`:
   those whose level is one of the feature's — so for `:dates` those the
   dates do not block."
  [feats f comparisons]
  (filter #(some #{(get-in % [:levels f])} (feats f)) comparisons))

(defn level-counts
  "Per feature of `feats` (default `features`), `{:n N :counts {level n}}`
   over `comparisons` (fractional when a comparison carries a `::w` weight);
   a blocked pair (`:dates :incompatible`) counts in no dates level."
  ([comparisons] (level-counts comparisons features))
  ([comparisons feats]
   (into {}
         (for [f (keys feats)
               :let [cs     (counted feats f comparisons)
                     w      (fn [c] (double (get c ::w 1.0)))
                     counts (reduce (fn [acc c] (update acc (get-in c [:levels f]) (fnil + 0.0) (w c))) {} cs)]]
           [f {:n (reduce + 0.0 (vals counts)) :counts counts}]))))

(defn level-frequencies
  "Per feature, the share of each level among `comparisons` (`level-counts`),
   with `alpha` pseudo-counts per level: (n + alpha) / (N + alpha K). Used
   inside EM; a model's weights are never read from pseudo-counts (`model`)."
  [comparisons alpha]
  (into {}
        (for [[f {:keys [n counts]}] (level-counts comparisons)
              :let [levels (features f)
                    denom  (+ n (* alpha (count levels)))]]
          [f (into {} (for [l levels]
                        [l (if (pos? denom) (/ (+ (get counts l 0.0) alpha) denom) 0.0)]))])))

(def min-observed
  "A level's m or u is measured when at least this many pairs reached it on
   that side; below, it is only bounded (by `min-observed` / N)."
  5)

(def disagreeing-levels
  "The name levels that say the names disagree — different forenames on both
   sides, different surnames: never a positive weight."
  #{:forenames-differ :jaccard-high :jaccard-low :none})

(defn- log2 [x] (/ (StrictMath/log x) (StrictMath/log 2.0)))

(defn weight
  "log2(m/u)."
  [m u]
  (log2 (/ (double m) (double u))))

(defn measured-weight
  "The weight of a level observed `mc` times among `mn` same-person pairs and
   `uc` times among `un` different-person pairs (see the ns doc): log2(m/u)
   when both are measured, the bound `min-observed`/N gives when one is, in
   the direction it proves, else 0."
  [mc mn uc un]
  (let [k      min-observed
        m-ok   (and (pos? mn) (>= mc k))
        u-ok   (and (pos? un) (>= uc k))]
    (cond
      (and m-ok u-ok) (weight (/ mc mn) (/ uc un))
      (and m-ok (pos? un)) (max 0.0 (weight (/ mc mn) (/ (double k) un)))
      (and u-ok (pos? mn)) (min 0.0 (weight (/ (double k) mn) (/ uc un)))
      :else 0.0)))

(defn level-measured?
  "Whether a level observed `mc` times among `mn` same-person pairs and `uc`
   times among `un` different-person pairs was measured (lot O4): at least
   `min-observed` (5) pairs on both sides, or on one side with a bound that
   proves something (`measured-weight` not 0). A level seen fewer than 5
   times on both sides, or on one side only with a bound that proves
   nothing, is unmeasured: its weight of 0 says only that it was not
   measured."
  [mc mn uc un]
  (let [k    min-observed
        m-ok (and (pos? mn) (>= mc k))
        u-ok (and (pos? un) (>= uc k))]
    (or (and m-ok u-ok)
        (and (or m-ok u-ok) (not (zero? (measured-weight mc mn uc un)))))))

(defn share-weight
  "log2(m/u) of two hand-set shares, 0 when either is missing or 0."
  [m u] (if (and m u (pos? m) (pos? u)) (weight m u) 0.0))

(def o4-rules
  "The rules of lot O4 (ADR 0062), which a model follows when its
   parameters state them (`:rules`):
   - `:form :exact-name`: the form (heading or variant) weighs only when the
     name level is `:exact` (`form-weighed-levels`); below, it weighs 0;
   - `:chain :measured-levels`: the name weights never increase along
     `name-levels` over the measured levels (`level-measured?`). An
     unmeasured level is skipped and caps none below it: it weighs 0, or,
     for a level that says the surnames agree (`surname-levels`), the next
     measured weight below it when that is above 0; never more than the
     measured level above it.

   `n4-rules` are the rules up to lot N4: the form at every level, the chain
   over every level, an unmeasured level read as 0 and capping every level
   below it. Parameters that state no `:rules` predate O4 and follow them."
  {:form :exact-name :chain :measured-levels})

(def n4-rules
  "The rules up to lot N4 (see `o4-rules`), those of parameters that state
   none."
  {:form :every-level :chain :every-level})

(def ^:private rule-values
  {:form #{:exact-name :every-level} :chain #{:measured-levels :every-level}})

(defn rules-of
  "The rules of model parameters `params`: its `:rules` over `n4-rules` (a
   file that states none predates O4); rules that are not a map, an unknown
   rule or an unknown value are refused."
  [params]
  (let [given (:rules params)]
    (when-not (or (nil? given) (map? given))
      (throw (ex-info (str "agent-match: :rules must be a map of rules, got " (pr-str given)) {:rules given})))
    (let [r (merge n4-rules given)]
      (doseq [[k v] r]
        (when-not (contains? (get rule-values k #{}) v)
          (throw (ex-info (str "agent-match: unknown rule " (pr-str k) " " (pr-str v)
                               ", one of " (pr-str (into (sorted-map) (update-vals rule-values (comp vec sort)))))
                          {:rule k :value v}))))
      r)))

(def form-weighed-levels
  "The name levels at which the form weighs under `:form :exact-name`: the
   same name only. The form's evidence is that the mention uses that very
   form, a variant or a pseudonym. At `:initials`, a variant would win
   between equal initials matches; at `:same-tokens`, a surname and a
   forename may be swapped; below, a word differs and the form read is only
   the closest one."
  #{:exact})

(defn- chain
  "The name weights `nw` made non-increasing along `name-levels`. With
   `measured` (a set of levels), only over those: an unmeasured level weighs
   0, or, if it is one of `surname-levels`, the next measured weight below
   it when that is above 0; never more than the measured level above it.
   Without, over every level (the N4 rule)."
  [nw measured]
  (if (nil? measured)
    (zipmap name-levels (reductions min (map #(get nw % 0.0) name-levels)))
    (let [ms     (filter measured name-levels)
          capped (if (seq ms) (zipmap ms (reductions min (map #(get nw % 0.0) ms))) {})]
      (loop [[l & more] name-levels above nil out {}]
        (if (nil? l)
          out
          (if (contains? capped l)
            (recur more (capped l) (assoc out l (capped l)))
            (let [below (some capped more)
                  base  (if (and (surname-levels l) below (pos? below)) below 0.0)]
              (recur more above (assoc out l (cond-> base above (min above)))))))))))

(defn- constrain
  "The rules every model's weights obey, however they were estimated:
   `:dates :unknown` 0; disagreeing name levels at most 0; name weights
   non-increasing along `name-levels` (`chain`), over the `measured` levels
   when given."
  [ws measured]
  (-> ws
      (assoc-in [:dates :unknown] 0.0)
      (update :name (fn [nw]
                      (chain (into {} (map (fn [l] (let [w (get nw l 0.0)]
                                                     [l (if (disagreeing-levels l) (min 0.0 w) w)])))
                                   name-levels)
                             measured)))))

(defn level-weights
  "The weight of every level: from `m-counts` and `u-counts` (`level-counts`)
   by `measured-weight`, or from hand-set shares `m` and `u`; then
   `constrain`ed under the parameters' rules (`rules-of`). A name level is
   measured by counts as `level-measured?` says, by shares when both are
   above 0."
  [{:keys [m u m-counts u-counts] :as params}]
  (let [counts?  (and m-counts u-counts)
        c        (fn [side f k] (get-in side (if (= :n k) [f :n] [f :counts k]) 0.0))
        measured (when (= :measured-levels (:chain (rules-of params)))
                   (set (filter (fn [l] (if counts?
                                          (level-measured? (c m-counts :name l) (c m-counts :name :n)
                                                           (c u-counts :name l) (c u-counts :name :n))
                                          (let [a (get-in m [:name l]) b (get-in u [:name l])]
                                            (and a b (pos? a) (pos? b)))))
                                name-levels)))]
    (constrain
     (into {} (for [[f levels] features]
                [f (into {} (for [l levels]
                              [l (if counts?
                                   (measured-weight (c m-counts f l) (c m-counts f :n) (c u-counts f l) (c u-counts f :n))
                                   (share-weight (get-in m [f l]) (get-in u [f l])))]))]))
     measured)))

(def default-thresholds
  "Propose at a posterior of 0.95 or more; abstain below 0.5; review between."
  {:propose 0.95 :review 0.5})

(def default-bar
  "The least a model's held-out calibration must show for its posterior to be
   written as a confidence: an ECE of at most 0.05 over ten bins and no bin
   off by more than 0.1, on at least 300 distinct (mention, candidate) pairs
   and 100 distinct true mentions; and, for a `:review` decision, at least 30
   distinct pairs in the review band [0.5, 0.95)."
  {:max-ece 0.05 :max-bin-gap 0.1 :min-pairs 300 :min-positives 100 :min-review-pairs 30 :bins 10})

(defn- strictest
  "`bar` made no laxer than `default-bar`, key by key (`:bins` is the
   default's)."
  [bar]
  (into {} (for [[k d] default-bar
                 :let [b (get bar k)]]
             [k (cond (or (not (number? b)) (= :bins k))  d
                      (str/starts-with? (name k) "max")   (min d b)
                      :else                               (max d b))])))

(defn model
  "A model: from observed `:m-counts` and `:u-counts` (`level-counts`), or
   hand-set shares `:m` and `:u`; the `:prior` λ; `:thresholds` (default
   `default-thresholds`; an optional `:gap`, `competing-gap`); a `:bar`,
   pinned here and never laxer than `default-bar`; the `:rules` its weights
   obey (`rules-of`), which the model holds when the parameters state them,
   so its fingerprint names them (parameters that state none follow
   `n4-rules`, and their model is the one N4 built); any `:estimation`
   note. Its `:weights` are computed here (`level-weights`). It is not
   calibrated: `with-calibration` attaches a report."
  [{:keys [m u m-counts u-counts prior thresholds bar estimation] :as params}]
  (cond-> {:prior      prior
           :thresholds (or thresholds default-thresholds)
           :bar        (strictest bar)
           :weights    (level-weights params)}
    (some? (:rules params)) (assoc :rules (rules-of params))
    m-counts   (assoc :m-counts m-counts)
    u-counts   (assoc :u-counts u-counts)
    m          (assoc :m m)
    u          (assoc :u u)
    estimation (assoc :estimation estimation)))

(defn score
  "The weights of comparison `c` under `model`: `{:weights {:name :form :dates
   :tf} :match-weight w}`, w their sum; `:tf` the term-frequency adjustment
   (the comparison's own `:tf-mean` and `:surname-u`, one pool), 0 unless the
   name level is a surname agreement with a positive weight; `:form` 0 unless
   the name level is one of `form-weighed-levels`, under the model's `:form`
   rule `:exact-name` (a model without `:rules` follows `n4-rules`: the
   form at every level)."
  [{:keys [weights rules]} {:keys [levels surname-u tf-mean]}]
  (let [form? (or (not= :exact-name (:form rules)) (contains? form-weighed-levels (:name levels)))
        ws (into {} (for [f [:name :form :dates]]
                      [f (if (and (= :form f) (not form?)) 0.0 (get-in weights [f (get levels f)] 0.0))]))
        tf (if (and surname-u tf-mean (surname-levels (:name levels)) (pos? (:name ws)))
             (log2 (/ tf-mean surname-u))
             0.0)
        ws (assoc ws :tf tf)]
    {:weights ws :match-weight (reduce + 0.0 (vals ws))}))

(defn posterior
  "P(match) of a total weight `w` under prior `prior`: 1/(1+2^-(w + log2(λ/(1-λ))))."
  [w prior]
  (/ 1.0 (+ 1.0 (StrictMath/pow 2.0 (- (+ w (log2 (/ prior (- 1.0 prior)))))))))

(defn decide
  "`:propose`, `:review` or `:abstain` for posterior `p` under `model`'s thresholds."
  [{{:keys [propose review]} :thresholds} p]
  (cond (>= p propose) :propose
        (>= p review)  :review
        :else          :abstain))

(defn competing-gap
  "The gap G, in bits, of `model`'s relative competing rule (lot O4,
   `:thresholds :gap`), or nil: the N4 rule, where every other candidate at
   or above review competes."
  [model]
  (get-in model [:thresholds :gap]))

(defn competes?
  "Whether a candidate of weight `w` competes with the mention's top
   candidate, of weight `top-w`, both at or above the review threshold: when
   `gap` is nil, always (the N4 rule); else when its weight is less than
   `gap` bits below the top's. A tie always competes, whatever the gap. A candidate that does
   not compete leaves the top to be decided on its own thresholds."
  [gap top-w w]
  (or (nil? gap) (== top-w w) (< (- (double top-w) (double w)) (double gap))))

(defn em
  "m and λ by expectation-maximisation over `comparisons` (no labels), u held
   fixed (`:u`, shares): starting from `:prior` and a start m that favours the
   better levels, each step weighs every comparison by its posterior (the
   comparisons' own term frequencies, the raw log2(m/u)) and re-estimates m
   (with `:alpha`) and λ. Deterministic; stops after `:iterations` (default
   200) or when λ moves by less than 1e-12. Returns `{:m :m-counts :prior
   :iterations}`, `:m-counts` the expected counts of the last step."
  [comparisons {:keys [u prior alpha iterations] :or {alpha 1 iterations 200}}]
  (let [start (fn [levels] (let [k  (count levels)
                                 ws (map #(StrictMath/pow 2.0 (- k %)) (range k))
                                 t  (reduce + ws)]
                             (zipmap levels (map #(/ % t) ws))))
        ;; the E-step scores every feature on every pair (`n4-rules`): the
        ;; counts it re-estimates are per feature, every level and form
        ;; counted, so the likelihood it weighs by must be theirs
        raw   (fn [m] {:rules   n4-rules
                       :weights (into {} (for [[f ls] features]
                                           [f (into {} (for [l ls] [l (share-weight (get-in m [f l]) (get-in u [f l]))]))]))})
        cs    (remove #(= :incompatible (get-in % [:levels :dates])) comparisons)]
    (loop [m (into {} (for [[f ls] features] [f (start ls)])) lam (double prior) i 0]
      (let [mdl (raw m)
            g   (map #(posterior (:match-weight (score mdl %)) lam) cs)
            wcs (map #(assoc %1 ::w %2) cs g)
            m'  (level-frequencies wcs alpha)
            l'  (/ (reduce + 0.0 g) (count cs))]
        (if (or (>= (inc i) iterations) (< (Math/abs (- l' lam)) 1e-12))
          {:m m' :m-counts (level-counts wcs) :prior l' :iterations (inc i)}
          (recur m' l' (inc i)))))))

;; ---------------------------------------------------------------------------
;; Calibration
;; ---------------------------------------------------------------------------

(defn fingerprint
  "16 hex digits of the SHA-256 of `x` (`model/content-digest`)."
  [x]
  (model/content-digest x 16))

(defn model-fingerprint
  "The fingerprint of `model` as a calibration report names it: of the
   model without its `:calibration` and its relational weights (`:relations`,
   which a report names apart, by `agent-relations/relations-fingerprint`).
   A report proves the posterior of that model, no other (lot N2 review)."
  [model]
  (fingerprint (dissoc model :calibration :relations)))

(defn pool-fingerprint
  "The fingerprint of an authority pool: of its entries' ids, labels,
   variants and date texts, in a canonical order."
  [pool]
  (fingerprint (vec (sort-by pr-str (map (juxt :id :label (comp vec :variants) (comp :text :dates)) pool)))))

(defn brier
  "The mean squared error of predictions `[{:p :y}]`, y 0 or 1."
  [preds]
  (/ (reduce + 0.0 (map (fn [{:keys [p y]}] (let [d (- p y)] (* d d))) preds)) (count preds)))

(defn reliability
  "The reliability table of `preds` in `bins` equal-width bins of [0, 1]: for
   each bin that holds a prediction, `{:bin [lo hi] :n :mean-p :observed}`, a
   probability of 1.0 in the last bin."
  [preds bins]
  (let [idx (fn [p] (min (dec bins) (long (StrictMath/floor (* p bins)))))]
    (vec (for [[i ps] (sort-by key (group-by (comp idx :p) preds))]
           {:bin      [(/ (double i) bins) (/ (double (inc i)) bins)]
            :n        (count ps)
            :mean-p   (/ (reduce + 0.0 (map :p ps)) (count ps))
            :observed (/ (reduce + 0.0 (map :y ps)) (count ps))}))))

(defn ece
  "The expected calibration error of `preds` over `bins` bins: the bins' gaps
   |mean predicted - observed|, weighted by their share of the predictions."
  [preds bins]
  (let [n (count preds)]
    (reduce + 0.0 (for [{:keys [mean-p observed] k :n} (reliability preds bins)]
                    (* (/ (double k) n) (Math/abs (- mean-p observed)))))))

(defn- meets?
  "Whether report `r` meets `bar` (the decision band aside)."
  [r bar]
  (let [{:keys [ece max-bin-gap distinct-pairs distinct-positives]} r]
    (boolean (and (every? number? [ece max-bin-gap distinct-pairs distinct-positives])
                  (<= ece (:max-ece bar)) (<= max-bin-gap (:max-bin-gap bar))
                  (>= distinct-pairs (:min-pairs bar)) (>= distinct-positives (:min-positives bar))))))

(defn- review-covered?
  "Whether report `r` covers the review band as `bar` asks."
  [r bar]
  (boolean (and (number? (:review-pairs r)) (>= (:review-pairs r) (:min-review-pairs bar)))))

(defn calibration
  "The calibration report of held-out predictions `preds` (`[{:p :y :pair
   :mention}]`, `:pair` what makes a (mention, candidate) pair distinct and
   `:mention` a mention, each prediction distinct when absent), measured for
   `:bar` (default `default-bar`) on the pool and gold named by the
   fingerprints `:pool` and `:gold`:

     {:n :distinct-pairs :positives :distinct-positives :review-pairs :brier
      :ece :max-bin-gap :bins :bar :band :pool :gold :meets-bar? :review-covered?}

   The Brier score, the ECE and the bins are over every prediction; the bar's
   counts are distinct pairs, distinct true mentions and distinct pairs in
   the review band `:band` [review, propose) — by default that of
   `default-thresholds`, [0.5, 0.95); a model's own thresholds when given
   (lot N4) — which the report states. With
   `:relations` (lot M8, `agent-relations/relations-fingerprint`), the report
   says the predictions were scored with that relational evidence: the
   relational weights and the anchor set they read. With `:model`
   (`model-fingerprint`), it names the model whose posteriors `preds` are:
   a report proves the confidence of that model alone (`calibrated?`), so a
   report without it proves nothing."
  ([preds] (calibration preds nil))
  ([preds {:keys [bar pool gold relations model band]}]
   (let [bar      (merge default-bar bar)
         keyed    (map-indexed (fn [i x] (assoc x ::pair (get x :pair [::i i]) ::mention (get x :mention [::i i]))) preds)
         uniq     (vals (into {} (map (juxt ::pair identity)) keyed))
         [review propose] (or band ((juxt :review :propose) default-thresholds))
         bins     (reliability preds (:bins bar))
         r        {:n                  (count preds)
                   :distinct-pairs     (count uniq)
                   :positives          (count (filter #(= 1 (:y %)) preds))
                   :distinct-positives (count (set (map ::mention (filter #(= 1 (:y %)) keyed))))
                   :review-pairs       (count (filter #(and (>= (:p %) review) (< (:p %) propose)) uniq))
                   :brier              (brier preds)
                   :ece                (ece preds (:bins bar))
                   :max-bin-gap        (reduce max 0.0 (map #(Math/abs (- (:mean-p %) (:observed %))) bins))
                   :bins               bins
                   :bar                bar
                   :band               [review propose]
                   :pool               pool
                   :gold               gold}]
     (cond-> (assoc r :meets-bar? (meets? r bar) :review-covered? (review-covered? r bar))
       relations (assoc :relations relations)
       model     (assoc :model model)))))

(defn with-calibration
  "`model` with calibration `report` attached (`calibration`): the step that
   decides, from the held-out numbers, whether its posterior is a confidence."
  [model report]
  (assoc model :calibration report))

(defn- pool-fp [pool] (if (string? pool) pool (pool-fingerprint pool)))

(defn calibrated?
  "Whether `model`'s calibration report meets the model's own bar (pinned when
   it was built, never laxer than `default-bar`; never the bar the report
   carries), was measured on `pool` (a pool, or its fingerprint) and on this
   model (its `:model` is `model-fingerprint` of `model`): a calibration
   holds for the pool and the model it was measured on, no other."
  [model pool]
  (let [r (:calibration model)]
    (boolean (and r (:pool r) (= (:pool r) (pool-fp pool))
                  (:model r) (= (:model r) (model-fingerprint model))
                  (meets? r (strictest (:bar model)))))))

(defn confidence
  "Posterior `p` of a `decision` as a confidence: `p` when `model` is
   calibrated on `pool` and, for a `:review` decision, its report covered the
   review band; else nil — a posterior that calibration did not measure is a
   score, never a confidence (ADR 0005 L2)."
  [model pool p decision]
  (when (and (calibrated? model pool)
             (case decision
               :propose true
               :review  (review-covered? (:calibration model) (strictest (:bar model)))
               false))
    p))

(defn confidence-proof
  "What a proposal carries to show its confidence was measured: the model's
   calibration report, its bar, the fingerprint of the pool matched against
   and that of the model that scored it (`model-fingerprint`)."
  [model pool]
  {:report (:calibration model) :bar (:bar model) :pool (pool-fp pool) :model (model-fingerprint model)})

(defn proven-confidence
  "The `:confidence` of `proposal` when its `:calibration-proof` holds — a
   report that meets the proof's bar (made no laxer than `default-bar`), on the
   pool the proposal was matched against and on the model that scored it,
   for its decision (a `:review` one needs the band covered), the confidence being its `:score`, and, when
   the proposal's evidence holds relational evidence (lot M8), a report
   measured with the same relational weights and anchors (its `:relations`
   fingerprint) — else nil."
  [{:keys [confidence score decision calibration-proof evidence]}]
  (let [{:keys [report bar pool model]} calibration-proof
        bar (strictest bar)
        rel (get-in evidence [:relations :fingerprint])]
    (when (and confidence report pool
               (= pool (:pool report))
               ;; the report measured the model that scored the proposal
               model (= model (:model report))
               ;; relational evidence (lot M8): the report measured it, with
               ;; these weights and this anchor set
               (or (nil? (:relations evidence)) (and rel (= rel (:relations report))))
               (= confidence score)
               (meets? report bar)
               (case decision
                 :propose true
                 :review  (review-covered? report bar)
                 false))
      confidence)))

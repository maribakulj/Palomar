(ns palomar.plugins.transforms
  "Core transform stdlib for the mapping schema (ADR 0009) and the
   plugin extension model (ADR 0010).

   A transform is a pure function `(fn [value] -> value-or-nil)`.
   Returning `nil` signals 'couldn't transform' — the surrounding
   mapping then decides what that means (skip, diagnose, or default
   per ADR 0009 §`:on-empty`).

   This namespace ships six core transforms:

   - `:trim`           — strip leading/trailing whitespace from strings
   - `:lowercase`      — locale-insensitive case folding (lower)
   - `:uppercase`      — locale-insensitive case folding (upper)
   - `:parse-int`      — string or number → long, nil on failure
   - `:parse-double`   — string or number → double, nil on failure
   - `:parse-iso-date` — accept ISO-8601 year / year-month / full date
                          forms; pass through; reject anything else

   Plugins extend the transform stdlib via their plugin map's
   `:transforms` key; pooling and conflict rules live in
   `palomar.plugins/effective-transforms`."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Core transforms
;;
;; Every core transform is total: it accepts any input and either
;; returns a transformed value or nil. No exceptions thrown for type
;; mismatches.
;; ---------------------------------------------------------------------------

(defn- trim* [v]
  (when (string? v)
    (str/trim v)))

(defn- lowercase* [v]
  (when (string? v)
    (str/lower-case v)))

(defn- uppercase* [v]
  (when (string? v)
    (str/upper-case v)))

(defn- parse-int* [v]
  (cond
    (int? v)    v
    (string? v) (try
                  (Long/parseLong (str/trim v))
                  (catch NumberFormatException _ nil))
    :else       nil))

(defn- parse-double* [v]
  (cond
    (number? v) (double v)
    (string? v) (try
                  (Double/parseDouble (str/trim v))
                  (catch NumberFormatException _ nil))
    :else       nil))

;; ISO-8601 date subset: year, year-month, year-month-day. Leading minus
;; sign permitted for BCE years. Stricter parsing (time, timezone,
;; year-week) is intentionally out of scope for the V1 core stdlib —
;; plugins that need more reach add their own `:dc-date->iso8601`-style
;; transform.
(def ^:private iso-date-re     #"^-?\d{4}-\d{2}-\d{2}$")
(def ^:private iso-year-mon-re #"^-?\d{4}-\d{2}$")
(def ^:private iso-year-re     #"^-?\d{4}$")

(defn- parse-iso-date* [v]
  (when (string? v)
    (let [s (str/trim v)]
      (when (or (re-matches iso-date-re s)
                (re-matches iso-year-mon-re s)
                (re-matches iso-year-re s))
        s))))

(def core-transforms
  "The six core transforms shipped by the runtime. Plugin contributions
   merge into this stdlib at register time — name collisions across
   any pair of contributors (core or plugin) are rejected per ADR 0010."
  {:trim           trim*
   :lowercase      lowercase*
   :uppercase      uppercase*
   :parse-int      parse-int*
   :parse-double   parse-double*
   :parse-iso-date parse-iso-date*})

;; ---------------------------------------------------------------------------
;; Lossiness (ADR 0015 §Consequences: "transforms should declare when they are
;; lossy, so :coerced is detected automatically"). Case folding irreversibly
;; discards case detail — applying it is a `:coerced` loss. :trim (whitespace is
;; noise, and it is the documented cross-format reconciliation recipe) and the
;; numeric / date parses (which *fail* into a :transform-failed diagnostic rather
;; than silently lose detail) are not lossy. Plugin transforms are not yet
;; classified — their lossiness defaults to false.
;; ---------------------------------------------------------------------------

(def lossy-transforms
  "Core transforms whose application discards detail (ADR 0015 `:coerced`)."
  #{:lowercase :uppercase})

(defn lossy?
  "True if transform name `n` is a known lossy core transform."
  [n]
  (contains? lossy-transforms n))

;; ---------------------------------------------------------------------------
;; Composition
;;
;; ADR 0009 §Transform: `:mapping/transform` is a vector applied
;; left-to-right. `compose` builds that chain against a resolved stdlib
;; map (typically `effective-transforms` from the plugin registry). nil
;; short-circuits the chain — once a stage yields nil, downstream
;; stages are skipped.
;; ---------------------------------------------------------------------------

(defn compose
  "Build a single-arg function from a vector of transform names. Each
   stage is looked up in `transforms-stdlib`; missing names throw at
   compose time so a malformed mapping fails before the first record
   is processed.

   The returned function applies stages left-to-right, short-circuiting
   to nil as soon as any stage returns nil."
  [transforms-stdlib transform-names]
  (let [fns (mapv (fn [n]
                    (if-let [f (get transforms-stdlib n)]
                      f
                      (throw (ex-info "Unknown transform"
                                      {:transform-name n
                                       :available      (set (keys transforms-stdlib))}))))
                  transform-names)]
    (fn apply-chain [value]
      (reduce (fn [v f]
                (if (nil? v)
                  (reduced nil)
                  (f v)))
              value
              fns))))

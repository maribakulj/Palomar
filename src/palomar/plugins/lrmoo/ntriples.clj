(ns palomar.plugins.lrmoo.ntriples
  "An RDF 1.1 N-Triples reader (https://www.w3.org/TR/n-triples/), for RDF that
   Palomar did not write itself (audit S16).

   It follows the W3C grammar rather than a regular expression over Palomar's own
   output: IRIs (`<…>`, with `\\u`/`\\U` escapes), blank nodes (`_:label`),
   literals with the string escapes (`\\t \\b \\n \\r \\f \\\" \\' \\\\`, `\\u`,
   `\\U`), language tags (`@fr-CA`) and datatypes (`^^<…>`), comments (`#` to end
   of line) and blank lines. A syntax error throws `ex-info` with the line and
   column, never a partial or guessed triple.

   Terms come back as `{:iri s}`, `{:bnode label}` or
   `{:lit s :lang tag :datatype iri}` (`:lang`/`:datatype` only when present)."
  (:require [clojure.string :as str]))

(defn- fail [line col msg]
  (throw (ex-info (str "N-Triples syntax error at line " line ", column " (inc col) ": " msg)
                  {:line line :column (inc col)})))

(defn- hex-char
  "The code point of the `n` hex digits at `i` of `s`, as a string."
  [s i n line]
  (let [h (when (<= (+ i n) (count s)) (subs s i (+ i n)))]
    (when-not (and h (re-matches #"[0-9A-Fa-f]+" h))
      (fail line i (str "expected " n " hex digits")))
    (String. (Character/toChars (Long/parseLong h 16)))))

(defn- skip-ws [s i]
  (loop [i i]
    (if (and (< i (count s)) (#{\space \tab} (.charAt ^String s i))) (recur (inc i)) i)))

(defn- read-iri
  "Read an IRIREF at `i` (on `<`). Returns `[iri next-i]`."
  [^String s i line]
  (loop [j (inc i) sb (StringBuilder.)]
    (when (>= j (count s)) (fail line i "unterminated IRI"))
    (let [c (.charAt s j)]
      (cond
        (= c \>) [(str sb) (inc j)]
        (= c \\) (let [k (when (< (inc j) (count s)) (.charAt s (inc j)))]
                   (case k
                     \u (recur (+ j 6) (.append sb (hex-char s (+ j 2) 4 line)))
                     \U (recur (+ j 10) (.append sb (hex-char s (+ j 2) 8 line)))
                     (fail line j "only \\u and \\U escapes are allowed in an IRI")))
        (or (<= (int c) 0x20) (#{\< \" \{ \} \| \^ \`} c))
        (fail line j (str "character " (pr-str c) " is not allowed in an IRI"))
        :else (recur (inc j) (.append sb c))))))

(def ^:private echar
  {\t "\t" \b "\b" \n "\n" \r "\r" \f "\f" \" "\"" \' "'" \\ "\\"})

(defn- read-string-literal
  "Read a STRING_LITERAL_QUOTE at `i` (on `\"`). Returns `[string next-i]`."
  [^String s i line]
  (loop [j (inc i) sb (StringBuilder.)]
    (when (>= j (count s)) (fail line i "unterminated string literal"))
    (let [c (.charAt s j)]
      (cond
        (= c \") [(str sb) (inc j)]
        (= c \\) (let [k (when (< (inc j) (count s)) (.charAt s (inc j)))]
                   (cond
                     (= k \u)  (recur (+ j 6) (.append sb (hex-char s (+ j 2) 4 line)))
                     (= k \U)  (recur (+ j 10) (.append sb (hex-char s (+ j 2) 8 line)))
                     (echar k) (recur (+ j 2) (.append sb ^String (echar k)))
                     :else     (fail line j (str "invalid escape \\" k))))
        :else (recur (inc j) (.append sb c))))))

(defn- read-while [^String s i pred]
  (loop [j i] (if (and (< j (count s)) (pred (.charAt s j))) (recur (inc j)) j)))

(defn- bnode-char? [c] (or (Character/isLetterOrDigit (char c)) (#{\_ \- \. (char 0xB7)} c)))

(defn- read-bnode
  "Read a BLANK_NODE_LABEL at `i` (on `_`). Returns `[label next-i]`."
  [^String s i line]
  (when-not (and (< (inc i) (count s)) (= \: (.charAt s (inc i))))
    (fail line i "expected _: for a blank node"))
  (let [start (+ i 2)
        end   (read-while s start bnode-char?)
        ;; a label may contain '.' but not end with it (it is then the triple's dot)
        end   (loop [e end] (if (and (> e start) (= \. (.charAt s (dec e)))) (recur (dec e)) e))]
    (when (= end start) (fail line i "empty blank node label"))
    (when-not (or (Character/isLetterOrDigit (.charAt s start)) (= \_ (.charAt s start)))
      (fail line start "a blank node label must start with a letter, digit or _"))
    [(subs s start end) end]))

(defn- read-literal
  "Read a literal at `i` (on `\"`), with its optional language tag or datatype."
  [^String s i line]
  (let [[lex j] (read-string-literal s i line)]
    (cond
      (and (< j (count s)) (= \@ (.charAt s j)))
      (let [end (read-while s (inc j) #(or (Character/isLetterOrDigit (char %)) (= \- %)))
            tag (subs s (inc j) end)]
        (when-not (re-matches #"[a-zA-Z]+(-[a-zA-Z0-9]+)*" tag)
          (fail line j (str "invalid language tag @" tag)))
        [{:lit lex :lang tag} end])

      (and (< (inc j) (count s)) (= "^^" (subs s j (+ j 2))))
      (do (when-not (and (< (+ j 2) (count s)) (= \< (.charAt s (+ j 2))))
            (fail line (+ j 2) "expected a datatype IRI after ^^"))
          (let [[dt k] (read-iri s (+ j 2) line)]
            [{:lit lex :datatype dt} k]))

      :else [{:lit lex} j])))

(defn- read-term
  "Read the term at `i`, allowed kinds in `allowed` (#{:iri :bnode :lit})."
  [^String s i line allowed what]
  (when (>= i (count s)) (fail line i (str "expected " what)))
  (let [c (.charAt s i)]
    (cond
      (and (= c \<) (allowed :iri))   (let [[v j] (read-iri s i line)] [{:iri v} j])
      (and (= c \_) (allowed :bnode)) (let [[v j] (read-bnode s i line)] [{:bnode v} j])
      (and (= c \") (allowed :lit))   (read-literal s i line)
      :else (fail line i (str "expected " what)))))

(defn- parse-line
  "The triple on `s` (line number `line`), or nil for a blank or comment line."
  [^String s line]
  (let [i (skip-ws s 0)]
    (when-not (or (= i (count s)) (= \# (.charAt s i)))
      (let [[subj i] (read-term s i line #{:iri :bnode} "a subject (IRI or blank node)")
            [pred i] (read-term s (skip-ws s i) line #{:iri} "a predicate IRI")
            [obj i]  (read-term s (skip-ws s i) line #{:iri :bnode :lit} "an object")
            i        (skip-ws s i)]
        (when-not (and (< i (count s)) (= \. (.charAt s i)))
          (fail line i "expected '.' at the end of the triple"))
        (let [i (skip-ws s (inc i))]
          (when-not (or (= i (count s)) (= \# (.charAt s i)))
            (fail line i "unexpected content after the triple")))
        [subj pred obj]))))

(defn parse
  "Parse an N-Triples document into `[subject predicate object]` term triples
   (see the namespace doc for the term shapes), in document order."
  [nt]
  (into []
        (keep-indexed (fn [idx l] (parse-line l (inc idx))))
        (str/split-lines (str nt))))

(defn term-key
  "A string key for a subject/object node: the IRI, or `_:label` for a blank node."
  [t]
  (or (:iri t) (some->> (:bnode t) (str "_:"))))

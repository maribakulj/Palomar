(ns palomar.plugins.lrmoo.ntriples
  "An RDF 1.1 N-Triples reader (https://www.w3.org/TR/n-triples/), for RDF that
   Palomar did not write itself (audit S16).

   It follows the W3C grammar rather than a regular expression over Palomar's own
   output: IRIs (`<…>`, with `\\u`/`\\U` escapes), blank nodes (`_:label`),
   literals with the string escapes (`\\t \\b \\n \\r \\f \\\" \\' \\\\`, `\\u`,
   `\\U`), language tags (`@fr-CA`) and datatypes (`^^<…>`), comments (`#` to end
   of line) and blank lines; a line ends in LF, CR or CRLF (EOL). A syntax error
   throws `ex-info` with the line and column, never a partial or guessed triple.

   Terms come back as `{:iri s}`, `{:bnode label}` or
   `{:lit s :lang tag :datatype iri}` (`:lang`/`:datatype` only when present)."
  (:require [clojure.string :as str]))

(defn- fail [line col msg]
  (throw (ex-info (str "N-Triples syntax error at line " line ", column " (inc col) ": " msg)
                  {:line line :column (inc col)})))

(defn- hex-char
  "The code point of the `n` hex digits at `i` of `s`, as a string. A value that
   is not a Unicode scalar value (above U+10FFFF, or a surrogate) is an error."
  [s i n line]
  (let [h (when (<= (+ i n) (count s)) (subs s i (+ i n)))]
    (when-not (and h (re-matches #"[0-9A-Fa-f]+" h))
      (fail line i (str "expected " n " hex digits")))
    (let [cp (Long/parseLong h 16)]
      (when (or (> cp 0x10FFFF) (<= 0xD800 cp 0xDFFF))
        (fail line i (str "\\" (if (= 4 n) "u" "U") h " is not a Unicode scalar value")))
      (String. (Character/toChars (int cp))))))

(defn- skip-ws [s i]
  (loop [i i]
    (if (and (< i (count s)) (#{\space \tab} (.charAt ^String s i))) (recur (inc i)) i)))

(defn- iri-forbidden? [c] (or (<= (int c) 0x20) (#{\< \> \" \{ \} \| \^ \` \\} c)))

(defn iri-problem
  "Why string `iri` cannot be written as an N-Triples/Turtle IRIREF, or nil when
   it can: it must be absolute (have a scheme) and hold no character the grammar
   forbids (controls, space, `<>\"{}|^`\\`). Writers call this so an IRI from
   a caller never corrupts their output."
  [iri]
  (cond
    (not (string? iri))                             "not a string"
    (not (re-find #"^[A-Za-z][A-Za-z0-9+.-]*:" iri)) "not an absolute IRI (no scheme)"
    (some iri-forbidden? iri)                       (str "holds " (pr-str (first (filter iri-forbidden? iri)))
                                                         ", which an IRI may not")))

(defn- read-iri
  "Read an IRIREF at `i` (on `<`). Returns `[iri next-i]`. A character an IRI
   may not hold is an error whether written or produced by a `\\u` escape, and
   the IRI must be absolute (have a scheme)."
  [^String s i line]
  (loop [j (inc i) sb (StringBuilder.)]
    (when (>= j (count s)) (fail line i "unterminated IRI"))
    (let [c (.charAt s j)]
      (cond
        (= c \>) (let [iri (str sb)]
                   (when-not (re-find #"^[A-Za-z][A-Za-z0-9+.-]*:" iri)
                     (fail line i (str "<" iri "> is not an absolute IRI (no scheme)")))
                   [iri (inc j)])
        (= c \\) (let [k   (when (< (inc j) (count s)) (.charAt s (inc j)))
                       [n w] (case k \u [4 6] \U [8 10]
                                   (fail line j "only \\u and \\U escapes are allowed in an IRI"))
                       ch  (hex-char s (+ j 2) n line)]
                   (when (and (= 1 (count ch)) (iri-forbidden? (.charAt ^String ch 0)))
                     (fail line j (str "the escape produces " (pr-str (.charAt ^String ch 0))
                                       ", which is not allowed in an IRI")))
                   (recur (+ j w) (.append sb ch)))
        (iri-forbidden? c)
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

(defn- pn-chars-u?
  "PN_CHARS_U of the N-Triples grammar, approximated by Unicode letters: a
   letter, `_` or `:`."
  [cp]
  (or (Character/isLetter (int cp)) (= cp (int \_)) (= cp (int \:))))

(defn- pn-chars?
  "PN_CHARS: PN_CHARS_U, `-`, a digit, U+00B7, a combining mark (U+0300–U+036F)
   or U+203F–U+2040."
  [cp]
  (or (pn-chars-u? cp) (= cp (int \-)) (<= (int \0) cp (int \9)) (= cp 0xB7)
      (<= 0x0300 cp 0x036F) (<= 0x203F cp 0x2040)))

(defn- read-bnode
  "Read a BLANK_NODE_LABEL at `i` (on `_`), by code point (a label may hold
   characters beyond the BMP). Returns `[label next-i]`."
  [^String s i line]
  (when-not (and (< (inc i) (count s)) (= \: (.charAt s (inc i))))
    (fail line i "expected _: for a blank node"))
  (let [start (+ i 2)
        n     (count s)
        end   (loop [j start]
                (if (< j n)
                  (let [cp (.codePointAt s (int j))]
                    (if (or (pn-chars? cp) (= cp (int \.))) (recur (+ j (Character/charCount cp))) j))
                  j))
        ;; a label may contain '.' but not end with it (it is then the triple's dot)
        end   (loop [e end] (if (and (> e start) (= \. (.charAt s (dec e)))) (recur (dec e)) e))]
    (when (= end start) (fail line i "empty blank node label"))
    (let [cp (.codePointAt s (int start))]
      (when-not (or (pn-chars-u? cp) (<= (int \0) cp (int \9)))
        (fail line start "a blank node label must start with a letter, digit, _ or :")))
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
        (str/split (str nt) #"\r\n?|\n")))

(defn term-key
  "A string key for a subject/object node: the IRI, or `_:label` for a blank node."
  [t]
  (or (:iri t) (some->> (:bnode t) (str "_:"))))

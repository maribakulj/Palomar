(ns palomar.eval.person-reconciliation-hard
  "The hard person-reconciliation benchmark (lot N3,
   `palomar.eval.person-reconciliation-hard-test`,
   `docs/eval/person-reconciliation-hard.md`).

   The easy benchmark (`palomar.eval.person-reconciliation`) matches the
   BnF's controlled headings, written like the authority headings. This one
   matches **transcribed mentions**: the names of the statement of
   responsibility (INTERMARC 245 `$f`, `$g`, and the role clauses old
   records put in `$a`), as the title page gives them, against the
   **authority set of the whole corpus**. The truth is the `$3` of the
   record's own heading for that person, when the link is unambiguous.

   - `statement-mentions` cuts a 245 into transcribed mentions, each with
     the role phrase that introduces it (`:role`, verbatim).
   - `label-record` links a mention to one heading of its record (by its
     surname, a variant or a pseudonym of the heading's authority), or
     proposes a residual link for a hand to verify, or says why it cannot
     (`:set-aside`, counted, never guessed), or that the record has no
     heading for it (`:no-match`: the engine must abstain).
   - `resolve-hand` applies the committed hand labels (`hand-labels.edn`):
     a residual link is kept only when verified, and a no-match whose
     surname the pool holds is linked or kept as a hard negative only when
     a hand said which.
   - `split-of` puts every mention of one person in one split. The design
     corpora (`records/`) are train and dev; the corpora fetched after the
     rules were frozen (`records-test/`) are test.
   - `build` makes the benchmark from the fixtures; `load-benchmark` reads
     the committed EDN; `run-engines` runs the existing matchers on one
     split, against the same pool, with their current parameters.

   Everything here reads the fixtures through Palomar's own INTERMARC
   importer, the common mention extraction (`palomar.mentions`) for the
   headings and Palomar's INTERMARC authority reader for the authorities;
   the 245 is read from the field fragment the importer keeps.

   **Frozen.** The construction rules of this namespace were frozen before
   the test corpora were fetched; `freeze.edn` holds the SHA-256 of this
   file at that moment, and a test checks it."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [palomar.agent-dates :as dates]
            [palomar.eval.person-reconciliation :as pr]
            [palomar.mentions :as mentions]
            [palomar.plugins.authorities :as authorities]
            [palomar.plugins.intermarc :as intermarc]
            [palomar.reconcile :as reconcile]
            [palomar.text :as text])
  (:import (java.security MessageDigest)))

;; ---------------------------------------------------------------------------
;; Files
;; ---------------------------------------------------------------------------

(def fixture-dir "test/fixtures/er-gold/bnf-transcribed/")

(def source-file "test/unit/palomar/eval/person_reconciliation_hard.clj")

(defn- xml-files [dir]
  (let [d (io/file fixture-dir dir)]
    (if (.isDirectory d) (vec (sort (filter #(str/ends-with? % ".xml") (.list d)))) [])))

(def parts
  "The two parts of the corpus, by directory: `:design` (the corpora the
   rules were designed on: train and dev) and `:test` (fetched after the
   freeze)."
  {:design "records" :test "records-test"})

(def authority-file (str fixture-dir "authorities.xml"))
(def pseudonym-file (str fixture-dir "pseudonyms.xml"))
(def benchmark-file (str fixture-dir "benchmark.edn"))
(def splits-file (str fixture-dir "splits.edn"))
(def hand-labels-file (str fixture-dir "hand-labels.edn"))
(def hand-check-file (str fixture-dir "hand-check.edn"))
(def freeze-file (str fixture-dir "freeze.edn"))

(defn read-edn [path] (edn/read-string (slurp path)))

(defn sha-256 [^String s]
  (apply str (map #(format "%02x" (bit-and % 0xff))
                  (.digest (MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8")))))

;; ---------------------------------------------------------------------------
;; The splitter: a 245 into transcribed mentions
;; ---------------------------------------------------------------------------

(defn- fold [s]
  (-> (java.text.Normalizer/normalize (.toLowerCase (str s) java.util.Locale/ROOT) java.text.Normalizer$Form/NFKD)
      (str/replace #"\p{M}+" "")))

(defn- bare [t] (str/replace (fold t) #"\.$" ""))

(def ^:private honorifics
  "Dropped wherever they stand, in either case, with or without a period."
  #{"m" "mm" "mme" "mmes" "mlle" "mlles" "mr" "mrs" "dr" "me" "mgr"
    "madame" "mesdames" "monsieur" "messieurs" "mademoiselle" "mesdemoiselles"})

(def ^:private conjunctions #{"et" "and" "&" "und"})

(def ^:private particles
  "Lowercase words inside a name, between two of its capitalised words."
  #{"de" "d'" "du" "des" "van" "von" "der" "den" "di" "da" "del" "della" "y" "ten" "ter" "zu" "le" "la"})

(def ^:private markers
  "The words a role phrase ends with before the name it introduces."
  #{"par" "by" "de" "d'" "du" "des" "di" "da" "von" "of" "from" "auctore"})

(def ^:private name-suffixes
  "Lowercase words that end a name (\"Rosny aîné\", \"Dumas fils\", \"Daudet fil.\")."
  #{"aine" "jeune" "fils" "fil" "pere" "cadet"})

(def ^:private articles #{"l'" "la" "le" "les" "the"})

(def ^:private latin-signatures
  "Engravers' and draughtsmen's Latin, dropped: full words, and their
   abbreviations when they end with a period."
  {:words #{"sculpsit" "fecit" "delineavit" "invenit" "pinxit" "excudit"}
   :abbr  #{"sculp" "sculps" "scul" "sc" "fe" "fec" "delin" "del" "inv" "pinx" "lugd" "exc" "excud"}})

(def ^:private role-nouns
  "The whole words (lowercase, accents and a final period dropped) that open
   or end a role phrase. A capitalised one counts only at the start of a
   clause or after a comma; a forename that merely begins like one
   (Edith, Publius, Ornella) is not one."
  #{"preface" "pref" "lettre-preface" "introduction" "introd" "illustrations" "illustration" "ill" "illustre"
    "illustree" "illustres" "illustrees" "illustrated" "dessins" "dessin" "traduction" "trad" "traduit" "traduite"
    "traduits" "traduites" "translated" "translation" "notes" "note" "notice" "notices" "avant-propos" "postface"
    "commentaires" "commentaire" "compositions" "composition" "gravures" "gravure" "grave" "graves" "gravees"
    "presentation" "edition" "ed" "edite" "editee" "edited" "choix" "choisis" "adaptation" "adapte" "adaptee"
    "etude" "bois" "eaux-fortes" "eau-forte" "aquarelles" "photographies" "photos" "musique" "texte" "textes"
    "lithographies" "frontispice" "portrait" "version" "revision" "revu" "revue" "recueil" "reunis" "publie"
    "publiee" "publies" "publiees" "avec" "with" "selected" "precede" "precedee" "suivi" "suivie" "orne" "ornee"
    "decore" "couverture" "vignettes" "images" "annotations" "annote" "annotee" "collaboration" "sous"
    "realisation" "message" "signe" "signee" "introductory" "bibliographie" "traduccion" "traduzione"})

(def ^:private non-names
  "Capitalised words that are never a name."
  #{"signe" "signee" "fragments" "fragment" "anonyme" "collectif"})

(def ^:private collective-words #{"sisters" "brothers" "freres" "soeurs" "family" "famille"})

(def ^:private corporate-words
  "A name run that opens with one of these is a body's name: neither it nor
   a name inside it (\"Association des amis de Pierre Loti\") is a person
   mention."
  #{"association" "societe" "society" "academie" "academy" "amis" "comite" "club" "institut" "institute"
    "universite" "university" "lycee" "college" "fondation" "foundation" "cercle" "centre" "musee" "museum"
    "bibliotheque" "library" "union" "federation" "groupe" "compagnie" "editions" "ecole" "ligue" "conseil"})

(defn- role-noun? [t] (boolean (and t (role-nouns (bare t)))))

(defn- capitalised? [t] (boolean (re-find #"^\p{Lu}" t)))

(defn- clean
  "A subfield's text before tokenising: `[sic]`, `et al.`, `etc.`, quoted
   titles and tokens with encoding junk (\"M³\") dropped, an inverted
   `Surname (Forename)` put in natural order, brackets and ellipses
   removed, an elided `d'`/`l'` set apart from its word."
  [s]
  (-> (str s)
      (str/replace #"(?i)\[\s*[\"“]?\s*sic\s*[\"”]?\s*\]" " ")
      (str/replace #"(?i)\[?\s*\bet al\.?\s*\]?" " ")
      (str/replace #"(?i)\betc\.?" " ")
      (str/replace #"\"[^\"]*\"|“[^”]*”|«[^»]*»" " ")
      (str/replace #"\S*[\p{No}{}|~^\\]\S*" " ")
      (str/replace #"((?:\p{Lu}[\p{L}'’-]*\s)*\p{Lu}[\p{L}'’-]*)\s*\((\p{Lu}[^()]{0,40})\)" "$2 $1")
      (str/replace #"\.\.\.|…" " ")
      (str/replace #"[\[\]()]" " ")
      (str/replace #"\b([dlDL])[’'](?=\p{L})" "$1' ")
      (str/replace #"’" "'")
      (str/replace #"\s+" " ")
      str/trim))

(defn- latin? [t]
  (let [b (bare t)]
    (or ((:words latin-signatures) b) (and (str/ends-with? t ".") ((:abbr latin-signatures) b)))))

(defn- raw-tokens [s]
  (remove #(or (latin? %) (honorifics (bare %))) (re-seq #"[;,/:]|[^\s;,/:]+" s)))

(defn- clauses
  "The token clauses of cleaned text `s`: cut at `;`, `/`, `:` (not after a
   role noun: \"Signé : Léon Daudet\"), and at a sentence end — a word
   ending with `.`, `!` or `?` followed by a capitalised role noun, or a
   word of 4 letters or more so ending followed by a capitalised word (so
   \"J.-H.\" or \"Ch.\" never cut before a name)."
  [s]
  (let [ts (vec (raw-tokens s))]
    (loop [i 0 cur [] out []]
      (if (= i (count ts))
        (cond-> out (seq cur) (conj cur))
        (let [t    (ts i)
              nxt  (get ts (inc i))
              ends (and nxt (re-find #"[^.][.!?]$" t) (capitalised? nxt))
              end? (and ends (or (role-noun? nxt) (<= 4 (count (re-seq #"\p{L}" t)))))]
          (cond
            (and (= ":" t) (role-noun? (peek cur))) (recur (inc i) cur out)
            (#{";" "/" ":"} t) (recur (inc i) [] (cond-> out (seq cur) (conj cur)))
            end?               (recur (inc i) [] (conj out (conj cur (subs t 0 (dec (count t))))))
            :else              (recur (inc i) (conj cur t) out)))))))

(defn- classify
  "Each token of a clause as `[token class]`, class `:name`, `:role`,
   `:comma` or `:conj`."
  [ts]
  (let [ts (vec ts)
        base (vec (map-indexed
                   (fn [i t]
                     (let [prev (get ts (dec i))]
                       (cond
                         (= "," t)               :comma
                         (conjunctions (fold t)) :conj
                         (capitalised? t)        (cond
                                                   (non-names (bare t)) :role
                                                   (and (role-noun? t) (or (zero? i) (= "," prev))) :role
                                                   :else :name)
                         (particles t)           :particle
                         :else                   :role)))
                   ts))]
    (vec (map-indexed
          (fn [i c]
            [(ts i) (if (= :particle c)
                      (if (and (= :name (get base (dec i))) (some-> (get ts (inc i)) capitalised?)) :name :role)
                      (if (and (= :role c) (= :name (get base (dec i))) (name-suffixes (bare (ts i)))) :name c))])
          base))))

(defn- join-tokens [ts]
  (-> (str/join " " ts)
      (str/replace #" ," ",")
      (str/replace #"\b([dlDL]') " "$1")
      str/trim))

(defn- strip-period
  "A name's text without a final period, unless its last word is an
   initial (\"R.\", \"Ch.\")."
  [s]
  (if (and (str/ends-with? s ".") (not (re-find #"(?:^|[\s.-])\p{Lu}\p{Ll}?\.$" s)))
    (subs s 0 (dec (count s)))
    s))

(defn- last-word
  "The last token of a role phrase that is not an article."
  [ts]
  (last (remove #(articles (fold %)) ts)))

(defn- licensed?
  "May the role phrase `ts` introduce a name? In `$f`/`$g`: it ends with a
   marker (`par`, `de`, `by`…, articles aside) or with a role noun
   (\"introduction Louis-René Nougier\"). In `$a`'s title clause: it holds
   `par` or `by` and ends with a marker. In `$a`'s later clauses: it starts
   with a role noun or holds `par`/`by`, and ends with a marker or a role
   noun."
  [ts {:keys [a? first-clause?]}]
  (let [lw      (some-> (last-word ts) fold)
        marker? (boolean (markers lw))
        par?    (boolean (some #(#{"par" "by"} (fold %)) ts))]
    (cond
      (not a?)      (or marker? (role-noun? lw))
      first-clause? (and par? marker?)
      :else         (and (or (role-noun? (first ts)) par?) (or marker? (role-noun? lw))))))

(def ^:private qualifier-words #{"de" "d'" "du" "des" "of"})

(defn- initials-only? [ts] (every? #(re-matches #"\p{Lu}\p{Ll}?\.(?:-\p{Lu}\p{Ll}?\.)*" %) ts))

(defn- clause-pieces
  "The name pieces of one classified clause, each `{:tokens :role :ok?
   :joiner :status}`: `:role` the role phrase before it (text), `:ok?`
   whether it may be read as a mention, `:joiner` `:conj` or `:comma` when
   it continues the piece before, `:status` `:body` or `:collective` when
   it is no person's mention.

   The role phrase is the words since the previous name, after its last
   comma — or all of them at the start of a `$f`/`$g` clause, so an
   internal comma stays (\"Traduit de l'anglais, par\"). After `et`, a bare
   marker (\"préf. de X et de Y\") keeps the role before."
  [cts flags]
  (let [runs (vec (partition-by #(= :name (second %)) cts))]
    (loop [i 0 pending [] prev? false role nil ok? false body? false out []]
      (if-let [run (get runs i)]
        (if (not= :name (second (first run)))
          (recur (inc i) (into pending run) prev? role ok? body? out)
          (let [toks     (mapv first run)
                classes  (map second pending)
                sep-only (every? #{:comma :conj} classes)
                body?    (and body? (not (some #{:comma} classes)))
                after-c  (vec (reverse (take-while #(not= :comma (second %)) (reverse pending))))
                rest-c   (drop-while #(#{:conj :comma} (second %)) pending)
                [role ok? joiner]
                (cond
                  (and (empty? pending) (not prev?))
                  [nil (and (:first-clause? flags) (not (:a? flags))) nil]

                  (and sep-only prev?)
                  [role ok? (if (some #{:conj} classes) :conj :comma)]

                  (and prev? (= :conj (first classes))
                       (every? #(or (markers (fold (first %))) (articles (fold (first %)))) rest-c))
                  [role ok? :conj]

                  :else
                  (let [whole?     (and (not prev?) (not (:a? flags)))
                        src        (if whole? pending after-c)
                        rtoks      (mapv first (drop-while #(#{:conj :comma} (second %)) src))
                        rtoks      (if (= "," (peek rtoks)) (pop rtoks) rtoks)
                        qualifier? (and prev? (= :comma (second (first pending)))
                                        (qualifier-words (some-> (first rtoks) fold)))]
                    [(join-tokens rtoks) (and (not qualifier?) (seq rtoks) (licensed? rtoks flags)) nil]))
                nxt        (some-> (get runs (inc i)) first first fold)
                corporate? (or (corporate-words (bare (first toks))) (corporate-words nxt))
                collective? (or (collective-words nxt) (collective-words (some-> (last-word (map first pending)) fold)))
                status     (cond corporate? :body body? :inside-body collective? :collective)]
            (recur (inc i) [] true role ok? (or corporate? body?)
                   (conj out (cond-> {:tokens toks :role role :ok? (boolean ok?) :joiner joiner}
                               status (assoc :status status))))))
        out))))

(defn- surname-part
  "The surname of a name piece: its last word and the lowercase particles
   just before it (\"Jules de Goncourt\" -> \"de Goncourt\")."
  [tokens]
  (let [v (vec tokens)]
    (loop [j (dec (count v))]
      (if (and (pos? j) (particles (get v (dec j))) (< 1 j)) (recur (dec j)) (subvec v j)))))

(defn- with-status
  "Marks the incomplete pieces — initials only, or one word in an
   enumeration whose next pieces are one word each up to one of two words
   or more (a shared surname: \"Edmond et Jules de Goncourt\",
   \"Charlotte, Emily, Anne Brontë\") — and gives one in an enumeration its
   `:completed` name: its words, then the surname of the longer piece that
   ends the enumeration (`surname-part`)."
  [pieces]
  (let [v (vec pieces)
        same-group? (fn [pc] (#{:conj :comma} (:joiner pc)))
        long-after (fn [i] (loop [j (inc i)]
                             (let [pc (get v j)]
                               (cond
                                 (not (and pc (same-group? pc))) nil
                                 (<= 2 (count (:tokens pc)))     pc
                                 :else                           (recur (inc j))))))]
    (vec (map-indexed
          (fn [i {:keys [tokens] :as pc}]
            (let [la (long-after i)]
              (cond
                (:status pc) pc
                (and (or (= 1 (count tokens)) (initials-only? tokens)) la)
                (assoc pc :status :incomplete :completed (join-tokens (concat tokens (surname-part (:tokens la)))))
                (initials-only? tokens) (assoc pc :status :incomplete)
                :else (assoc pc :status :mention))))
          v))))

(defn subfield-mentions
  "The transcribed mentions of one 245 subfield `code` of text `value`:
   `[{:text :role :status :completed}]`, `:role` absent when no phrase
   introduces the name, `:completed` only for an incomplete name of an
   enumeration. `$f`/`$g`: the first clause may open with a name; a later
   clause (after a sentence end) only with a role phrase. `$a`: the first
   clause is the title, read only after `par`/`by`; a later clause only
   after a role phrase (`licensed?`). A name inside a body's name is
   dropped; the body itself is a `:body` mention."
  [code value]
  (let [a? (= "a" code)]
    (vec (for [[i cl] (map-indexed vector (clauses (clean value)))
               pc     (with-status (clause-pieces (classify cl) {:a? a? :first-clause? (zero? i)}))
               :when  (and (:ok? pc) (not= :inside-body (:status pc)))]
           (cond-> {:text (strip-period (join-tokens (:tokens pc))) :status (:status pc)}
             (:role pc)      (assoc :role (:role pc))
             (:completed pc) (assoc :completed (strip-period (:completed pc))))))))

(def statement-codes #{"a" "f" "g"})

(defn statement-mentions
  "The transcribed mentions of a 245's ordered `[code value]` subfields, in
   subfield order: `$f` (the first statement of responsibility), `$g` (the
   later ones), and the role clauses of `$a`. Each `{:text :role :status
   :subfield :occurrence :piece :statement}`: `:occurrence` the subfield's
   rank among those of its code, `:piece` the mention's rank in it,
   `:statement` the subfield's text verbatim."
  [subfields]
  (let [ranks (second (reduce (fn [[seen out] [code]] [(update seen code (fnil inc 0)) (conj out (get seen code 0))])
                              [{} []] subfields))]
    (vec (for [[[code value] n] (map vector subfields ranks)
               :when (statement-codes code)
               [k m] (map-indexed vector (subfield-mentions code value))]
           (assoc m :subfield code :occurrence n :piece k :statement value)))))

;; ---------------------------------------------------------------------------
;; The labelling rule
;; ---------------------------------------------------------------------------

(def ^:private generational #{"fils" "fil" "pere" "aine" "jeune" "cadet" "jr" "junior" "sr" "senior"})

(def ^:private honorific-tokens #{"m" "mm" "mme" "mmes" "mlle" "mr" "mrs" "dr" "me" "mgr" "madame" "monsieur"})

(defn match-tokens
  "The tokens of a name for the match rule: lowercase, accents dropped,
   punctuation a space except the hyphen, so a hyphenated surname is one
   word (\"loti-viaud\")."
  [s]
  (->> (str/split (str/replace (fold s) #"[^\p{L}\p{N}\s-]" " ") #"\s+")
       (map #(str/replace % #"^-+|-+$" ""))
       (remove str/blank?)
       vec))

(defn- forenames [ts] (vec (remove #(or (particles %) (particles (str % "'")) (honorific-tokens %)) ts)))

(defn heading-fits?
  "Does transcribed name `text` fit heading `{:surname :forename}`? The
   heading's surname (`$a`, its words) ends the name — or ends it before
   generational words (fils, père, aîné, jeune…) — and the forenames are
   compatible: the name has none before the surname, the heading has none,
   or their first forenames share the first letter. Particles and
   honorifics are not forenames."
  [text {:keys [surname forename]}]
  (let [m  (match-tokens text)
        s  (match-tokens surname)
        fh (forenames (match-tokens forename))
        m' (vec (reverse (drop-while generational (reverse m))))
        fits (fn [m]
               (let [k (count s)]
                 (and (pos? k) (<= k (count m)) (= s (subvec m (- (count m) k)))
                      (let [fm (forenames (subvec m 0 (- (count m) k)))]
                        (or (empty? fm) (empty? fh) (= (first (first fm)) (first (first fh))))))))]
    (boolean (or (fits m) (and (not= m m') (fits m'))))))

(defn form-fits?
  "Does `text` fit an authority form \"Surname, Forenames\" (or a form with
   no comma, all surname), as `heading-fits?` reads a heading?"
  [text form]
  (let [[s f] (str/split (str form) #",\s*" 2)]
    (heading-fits? text {:surname s :forename f})))

(defn- group-fits?
  "Does `text` name the body of group heading `name`? The body's words are
   all the mention's, or their token Jaccard is at least 0.5 (a person
   named inside a body's name, \"Pierre Loti\" in \"Association Pierre
   Loti à Paimpol\", is not the body)."
  [text name]
  (let [a (set (match-tokens text)) b (set (match-tokens name))]
    (and (seq a) (seq b)
         (or (every? a b)
             (<= 0.5 (/ (double (count (filter b a))) (count (into a b))))))))

(def role-classes
  "A role phrase's class, by its stems (lowercase, accents dropped); a
   phrase is of a class only when it is of no other."
  [[:translator #"trad|transl|tradu"]
   [:illustrator #"\bill|dessin|grav|eaux-fortes|aquarel|\bbois|lithog|frontisp|compos|image|vignette|photo"]
   [:preface #"pref|introd|avant-propos|postface|notice|present"]
   [:editor #"edit|\bed\.|publi|choisi|choix|reuni|recueil|etabli|annot|\bnotes"]])

(def code-classes
  "INTERMARC function codes (`$4`) by class, for the role evidence: 0680
   traducteur, 0440 illustrateur, 0550 préfacier, 0360 éditeur
   scientifique."
  {"0680" :translator "0440" :illustrator "0550" :preface "0360" :editor})

(defn role-class
  "The one class of role phrase `role`, nil when none or several."
  [role]
  (let [cs (keep (fn [[c re]] (when (re-find re (fold role)) c)) (when role role-classes))]
    (when (= 1 (count cs)) (first cs))))

(defn- heading-key [h] (or (:id h) (:key h)))

(defn- hyphen-parts [ts] (set (mapcat #(str/split % #"-") ts)))

(defn- shares-hyphen-part?
  "Do the mention's words and the heading's surname share a part of a
   hyphenated word (\"Colette Grévoz\" / \"Dufour-Grévoz\", \"Samuel
   Loti-Viaud\" / \"Viaud\"), one side at least hyphenated?"
  [text h]
  (let [m (match-tokens text) s (match-tokens (:surname h))]
    (boolean (and (some #(str/includes? % "-") (concat m s))
                  (seq (filter (hyphen-parts s) (hyphen-parts m)))))))

(defn label-record
  "Labels the transcribed mentions of one record against its headings:
   `rec` is `{:mentions [{:text :role :status :completed}] :persons
   [{:surname :forename :id :roles :key :variants :pseudonyms}] :groups
   [{:name}]}`, `:variants` the `400` forms of the heading's authority,
   `:pseudonyms` the forms of the authorities its `331` links. Each mention
   gains `:label`, `:reason`, and `:truth` when linked:

   1. a `:body` or `:collective` mention: `:set-aside :group`;
   2. an `:incomplete` mention whose `:completed` name fits exactly one
      heading: `:linked :shared-surname` (its `:text` the completed name);
      else `:set-aside :incomplete`;
   3. exactly one person heading fits it (`heading-fits?`; two headings
      with one `$3` are one): `:linked :surname` — or `:no-match
      :heading-without-id` when that heading has no `$3`; several:
      `:set-aside :ambiguous`;
   4. none fits: exactly one heading has a `400` variant it fits
      (`form-fits?`): `:linked :variant`; else exactly one has a `331`
      pseudonym it fits: `:linked :pseudonym`; else a group heading names
      it (`group-fits?`): `:set-aside :group`;
   5. else, a residual link with independent evidence, for a hand to
      verify (`:set-aside :residual-candidate`, `:candidate` the heading's
      `$3`, `:basis`): `:role` when its role phrase is of one class, it is
      the record's only open mention of that class and exactly one
      heading no mention explains has a `$4` of that class; `:hyphen` when
      it is the record's only open mention, one heading only is
      unexplained, and they share a part of a hyphenated surname;
   6. else some person heading is explained by no mention: `:set-aside
      :unexplained-heading` (the mention may be that person under another
      name); else `:no-match :no-heading`.

   `resolve-hand` then settles the residual candidates and the no-match
   cases whose surname the pool holds."
  [{ms :mentions persons :persons groups :groups}]
  (let [uniq       (fn [hs] (vals (into {} (for [h hs] [(heading-key h) h]))))
        text-of    (fn [m] (if (= :incomplete (:status m)) (:completed m) (:text m)))
        body?      (fn [m] (#{:body :collective} (:status m)))
        grouped    (fn [m] (some #(group-fits? (:text m) (:name %)) groups))
        fit-of     (fn [m] (when (and (#{:mention :incomplete} (:status m)) (text-of m))
                             (uniq (filter #(heading-fits? (text-of m) %) persons))))
        by-forms   (fn [k m] (when (= :mention (:status m))
                               (uniq (filter #(and (:id %) (some (partial form-fits? (:text m)) (k %))) persons))))
        fits       (mapv fit-of ms)
        variants   (mapv #(by-forms :variants %) ms)
        pseudos    (mapv #(by-forms :pseudonyms %) ms)
        explained  (set (map heading-key (concat (apply concat fits)
                                                 (apply concat (filter #(= 1 (count %)) variants))
                                                 (apply concat (filter #(= 1 (count %)) pseudos)))))
        unexpl     (uniq (remove (comp explained heading-key) persons))
        open?      (fn [i m] (and (= :mention (:status m)) (not (grouped m)) (empty? (fits i))
                                  (empty? (variants i)) (empty? (pseudos i))))
        open-n     (count (filter identity (map-indexed open? ms)))
        class-n    (frequencies (keep-indexed (fn [i m] (when (open? i m) (role-class (:role m)))) ms))
        by-role    (fn [c] (let [hs (filter #(and (:id %) (some (comp #{c} code-classes) (:roles %))) unexpl)]
                             (when (= 1 (count hs)) (first hs))))]
    (vec (map-indexed
          (fn [i m]
            (let [f (fits i)
                  c (role-class (:role m))
                  link (fn [m h reason] (if (:id h)
                                          (assoc m :label :linked :reason reason :truth (:id h) :heading (:name h))
                                          (assoc m :label :no-match :reason :heading-without-id :heading (:name h))))
                  candidate (fn [h basis] (assoc m :label :set-aside :reason :residual-candidate
                                                 :candidate (:id h) :basis basis :heading (:name h)))]
              (cond
                (body? m)                          (assoc m :label :set-aside :reason :group)
                (= :incomplete (:status m))        (if (= 1 (count f))
                                                     (link (assoc m :text (:completed m)) (first f) :shared-surname)
                                                     (assoc m :label :set-aside :reason :incomplete))
                (= 1 (count f))                    (link m (first f) :surname)
                (< 1 (count f))                    (assoc m :label :set-aside :reason :ambiguous)
                (= 1 (count (variants i)))         (link m (first (variants i)) :variant)
                (= 1 (count (pseudos i)))          (link m (first (pseudos i)) :pseudonym)
                (or (seq (variants i)) (seq (pseudos i))) (assoc m :label :set-aside :reason :ambiguous)
                (grouped m)                        (assoc m :label :set-aside :reason :group)
                (and c (= 1 (class-n c)) (by-role c)) (candidate (by-role c) :role)
                (and (= 1 open-n) (= 1 (count unexpl)) (:id (first unexpl))
                     (shares-hyphen-part? (:text m) (first unexpl)))
                (candidate (first unexpl) :hyphen)
                (seq unexpl)                       (assoc m :label :set-aside :reason :unexplained-heading)
                :else                              (assoc m :label :no-match :reason :no-heading))))
          ms))))

(defn- form-surname [form] (first (str/split (str form) #"," 2)))

(defn surname-in-pool?
  "Does some entry of `pool` hold a form (its heading or a variant) whose
   surname — what precedes the first comma, or the whole form — ends
   `text` (`heading-fits?`, forenames not read), or whose words are the
   mention's words in another order (\"Souvestre Lesbazeilles\")?"
  [text pool]
  (let [ws (set (match-tokens text))]
    (boolean (some (fn [e] (some #(or (heading-fits? text {:surname (form-surname %)})
                                      (= ws (set (match-tokens (form-surname %)))))
                                 (remove str/blank? (cons (:label e) (:variants e)))))
                   pool))))

(defn resolve-hand
  "Applies the hand labels `hand` (`{mention-id {:verdict :truth}}`) to the
   labelled mentions `ms`, against the candidate `pool`:

   - a `:residual-candidate` verified `:correct` is `:linked`, its basis the
     reason and its candidate the truth; verified `:wrong`, `:set-aside
     :hand-rejected`; not verified, `:set-aside :unverified`;
   - a `:no-match` mention whose surname some pool authority holds
     (`surname-in-pool?`) is the same person with a missing heading or a
     namesake: verified `:same` with a `:truth` in the pool, it is `:linked
     :hand`; verified `:different`, it stays `:no-match :hand-negative` (a
     hard negative); not verified, `:set-aside :surname-in-pool`.

   A no-match case is then one where abstaining is right."
  [ms pool hand]
  (let [ids (set (map :id pool))]
    (mapv (fn [m]
            (let [h (get hand (:id m))]
              (cond
                (= :residual-candidate (:reason m))
                (case (:verdict h)
                  :correct (-> m (assoc :label :linked :reason (:basis m) :truth (:candidate m)) (dissoc :candidate :basis))
                  :wrong   (assoc m :reason :hand-rejected)
                  (assoc m :reason :unverified))

                (and (= :no-match (:label m)) (surname-in-pool? (:text m) pool))
                (cond
                  (and (= :same (:verdict h)) (ids (:truth h))) (assoc m :label :linked :reason :hand :truth (:truth h))
                  (= :different (:verdict h))                   (assoc m :reason :hand-negative)
                  :else (assoc m :label :set-aside :reason :surname-in-pool))

                :else m)))
          ms)))

(defn dedupe-mentions
  "Mentions of one record with the same text and role count once: the
   later ones are `:set-aside :duplicate`."
  [ms]
  (first (reduce (fn [[out seen] m]
                   (let [k [(:record m) (:text m) (:role m)]]
                     (if (and (seen k) (not= :set-aside (:label m)))
                       [(conj out (-> m (assoc :label :set-aside :reason :duplicate) (dissoc :truth))) seen]
                       [(conj out m) (cond-> seen (not= :set-aside (:label m)) (conj k))])))
                 [[] #{}] ms)))

;; ---------------------------------------------------------------------------
;; Strata
;; ---------------------------------------------------------------------------

(defn token-exact?
  "Are the mention's tokens the tokens of its truth's heading or of one of
   its variants (`text/norm`, as sets)?"
  [text entry]
  (let [t (set (str/split (text/norm text) #" "))]
    (boolean (some #(= t (set (str/split (text/norm %) #" "))) (cons (:label entry) (:variants entry))))))

(defn stratum
  "The stratum of a scored mention against `pool` (`by-id` its entries by
   id): linked by hand `:hand`; by a variant or a pseudonym `:variant`; by
   a hand-verified residual `:residual`; by its surname, `:homonym` when
   another pool authority holds its surname, else `:easy`. A no-match case
   is `:hard-negative` when a hand found a namesake in the pool, else
   `:no-namesake`."
  [m pool]
  (case (:reason m)
    :hand                  :hand
    (:variant :pseudonym)  :variant
    (:role :hyphen)        :residual
    :hand-negative         :hard-negative
    (:no-heading :heading-without-id) :no-namesake
    (if (surname-in-pool? (:text m) (remove #(= (:truth m) (:id %)) pool)) :homonym :easy)))

;; ---------------------------------------------------------------------------
;; Splits by person
;; ---------------------------------------------------------------------------

(def split-salt "palomar-n3-design/")

(def proportions {:train 75 :dev 25})

(defn no-match-key
  "A no-match name's key: its surname (last word, generational words
   aside) and the first letter of its first word when it has more than one
   — so \"Charles Baudelaire\" and \"Ch. Baudelaire\" share one."
  [text]
  (let [ts (vec (reverse (drop-while generational (reverse (match-tokens text)))))
        ts (if (seq ts) ts (match-tokens text))]
    (str (peek ts) (when (< 1 (count ts)) (str " " (first (first ts)))))))

(defn split-key
  "The key a scored mention is split by: its truth (the person's authority
   id), else `name:` and its `no-match-key`."
  [m]
  (or (:truth m) (str "name:" (no-match-key (:text m)))))

(defn- bucket
  "0..99 from the first four bytes of the SHA-256 of the salted key."
  [k]
  (let [d (.digest (MessageDigest/getInstance "SHA-256") (.getBytes (str split-salt k) "UTF-8"))
        n (reduce (fn [acc b] (+ (* acc 256) (bit-and b 0xff))) 0 (take 4 d))]
    (mod n 100)))

(defn split-of
  "The split of a design-corpus key `k`: `:train` for buckets 0-74, `:dev`
   75-99. The test split is not hashed: it is the test corpora's keys that
   no design mention has."
  [k]
  (if (< (bucket k) 75) :train :dev))

;; ---------------------------------------------------------------------------
;; Building the benchmark from the fixtures
;; ---------------------------------------------------------------------------

(defn- sub [sfs code] (some (fn [[c v]] (when (= code c) v)) sfs))

(defn- record-id [r] (name (:id r)))

(defn- corpus-of [file] (second (re-find #"bnf-sru-(.+)-100\.xml" file)))

(defn- authority-entries [path]
  (if (.exists (io/file path))
    (let [s (slurp path)] (:entries (authorities/read-pool :intermarc s)))
    []))

(defn- authority-index
  "Served authorities by record number, and the forms (heading and
   variants) of the pseudonym authorities by record number."
  []
  (let [served (into {} (for [e (authority-entries authority-file)] [(:record-number e) e]))
        pseudo (into {} (for [e (authority-entries pseudonym-file)]
                          [(:record-number e) (vec (remove str/blank? (cons (:label e) (:variants e))))]))]
    {:served served :pseudonyms pseudo}))

(defn- headings
  "A record's headings, from the common mention extraction: persons
   `{:tag :key :surname :forename :id :roles :name :dates :variants
   :pseudonyms}` and groups `{:tag :name :id}`."
  [record {:keys [served pseudonyms]}]
  (let [ms (filter #(and (= :agent (:type %)) (= :intermarc (get-in % [:source :format]))) (mentions/mentions record))
        h  (fn [m] (let [sfs (get-in m [:source :subfields])
                         id  (sub sfs "3")
                         a   (served id)]
                     (cond-> {:tag (get-in m [:source :field]) :key [(get-in m [:source :field]) (get-in m [:source :occurrence])]
                              :name (:name m) :roles (vec (:roles m))}
                       id (assoc :id id)
                       (sub sfs "a") (assoc :surname (sub sfs "a"))
                       (sub sfs "m") (assoc :forename (sub sfs "m"))
                       (get-in m [:dates :text]) (assoc :dates (get-in m [:dates :text]))
                       a (assoc :variants (vec (:variants a))
                                :pseudonyms (vec (mapcat #(pseudonyms (:record-number %))
                                                         (filter #(= :pseudonym (:kind %)) (:relations a))))))))
        duo? #(re-find #"\s(?:et|and|&)\s" (str (:name %)))]
    ;; a person heading that names two (\"Colette et Gérard\") is read as a group
    {:persons (mapv h (filter #(and (= :person (:kind %)) (not (duo? %))) ms))
     :groups  (mapv h (filter #(or (= :group (:kind %)) (and (= :person (:kind %)) (duo? %))) ms))}))

(defn- read-records []
  (vec (for [[part dir] [[:design (:design parts)] [:test (:test parts)]]
             f (xml-files dir)
             r (intermarc/ingest (slurp (str fixture-dir dir "/" f)) {})]
         (assoc r ::corpus (corpus-of f) ::part part))))

(defn- labelled [r index]
  (let [{:keys [persons groups]} (headings r index)
        f245 (some #(when (= "245" (get-in % [:locator :tag])) (get-in % [:locator :subfields])) (:fragments r))
        rid  (record-id r)]
    {:record   rid
     :corpus   (::corpus r)
     :part     (::part r)
     :persons  persons
     :groups   groups
     :mentions (vec (for [m (label-record {:mentions (statement-mentions f245) :persons persons :groups groups})]
                      (-> m
                          (dissoc :status :completed)
                          (assoc :id [rid (:subfield m) (:occurrence m) (:piece m)] :record rid
                                 :corpus (::corpus r) :part (::part r)))))}))

(defn- pool-of
  "The candidate pool: one entry per person authority any heading of the
   corpus cites (`$3`), `{:id :label :variants :dates :source}` — read from
   the authority record (`:source :authority`), or, when the SRU serves
   none, from a heading that cites it (`:source :heading`, `:from-record`
   that heading's record: its `$a, $m`, its `$d`, no variants). The heading
   is taken from the first citing record in which no mention is linked to
   that person (`linked-in`, record ids by authority id), else from the
   first citing record. `:dates` is the life-dates text."
  [recs served linked-in]
  (let [cites (reduce (fn [acc [r h]] (update acc (:id h) (fnil conj []) [r h]))
                      {} (for [r recs h (:persons r) :when (:id h)] [(:record r) h]))]
    (vec (for [id (sort (keys cites))
               :let [e (served id)
                     [rid h] (or (first (remove (fn [[rid]] (contains? (linked-in id #{}) rid)) (cites id)))
                                 (first (cites id)))]]
           (if e
             (cond-> {:id id :label (:label e) :variants (vec (:variants e)) :source :authority}
               (get-in e [:dates :text]) (assoc :dates (get-in e [:dates :text])))
             (cond-> {:id id :label (:name h) :variants [] :source :heading :from-record rid}
               (:dates h) (assoc :dates (:dates h))))))))

(defn- own-record-entries
  "A linked mention whose truth's pool entry was built from the mention's
   own record's heading is `:set-aside :entry-from-own-record`: its pool
   entry would be its own gold."
  [ms pool]
  (let [from (into {} (for [e pool :when (:from-record e)] [(:id e) (:from-record e)]))]
    (mapv #(if (and (= :linked (:label %)) (= (:record %) (from (:truth %))))
             (-> % (assoc :label :set-aside :reason :entry-from-own-record) (dissoc :truth))
             %)
          ms)))

(defn- assign-splits
  "Each scored mention's `:split`: a design mention's by `split-of` of its
   key; a test mention's `:test`, unless a design mention has its key,
   when it is `:set-aside :seen-in-design`."
  [ms]
  (let [scored?     #(#{:linked :no-match} (:label %))
        design-keys (set (map split-key (filter #(and (scored? %) (= :design (:part %))) ms)))]
    (mapv (fn [m]
            (cond
              (not (scored? m))      m
              (= :design (:part m))  (assoc m :split (split-of (split-key m)))
              (design-keys (split-key m)) (-> m (assoc :label :set-aside :reason :seen-in-design) (dissoc :truth))
              :else                  (assoc m :split :test)))
          ms)))

(defn build
  "The benchmark, from the fixtures: `{:pool [..] :records {id {:corpus
   :part :headings}} :mentions [..]}`, mentions in part, record and
   subfield order, each scored one with its `:split` and `:stratum`."
  []
  (let [index  (authority-index)
        recs   (mapv #(labelled % index) (read-records))
        hand   (if (.exists (io/file hand-labels-file)) (:labels (read-edn hand-labels-file)) {})
        pool0  (pool-of recs (:served index) {})
        ms     (resolve-hand (mapcat :mentions recs) pool0 hand)
        linked (reduce (fn [acc m] (update acc (:truth m) (fnil conj #{}) (:record m))) {} (filter :truth ms))
        pool   (pool-of recs (:served index) linked)
        ms     (-> ms (own-record-entries pool) dedupe-mentions assign-splits)]
    {:pool     pool
     :records  (into (sorted-map)
                     (for [r recs :when (seq (:mentions r))]
                       [(:record r) {:corpus   (:corpus r)
                                     :part     (:part r)
                                     :headings (mapv #(select-keys % [:tag :name :id :roles]) (concat (:persons r) (:groups r)))}]))
     :mentions (mapv #(cond-> % (:split %) (assoc :stratum (stratum % pool) :token-exact
                                                  (boolean (some->> (:truth %) (get (into {} (map (juxt :id identity)) pool))
                                                                    (token-exact? (:text %))))))
                     ms)}))

(defn split-file-content
  "The split file of benchmark `b`: the rule, and each key's split."
  [b]
  {:rule        "design corpora: bucket < 75 :train, else :dev; bucket = first 4 bytes of SHA-256(salt + key), big-endian, mod 100. Test corpora: :test, unless a design mention has the key (then set aside). key = the truth ($3), else \"name:\" + no-match-key (surname + first initial)"
   :salt        split-salt
   :proportions proportions
   :assignments (into (sorted-map) (for [m (:mentions b) :when (:split m)] [(split-key m) (:split m)]))})

(defn- lines [open xs close] (str open (str/join "\n  " (map pr-str xs)) close))

(defn write!
  "Writes `benchmark.edn` and `splits.edn` from the fixtures (run by hand
   after a fixture changes; a test checks they are what `build` gives)."
  []
  (let [b (build)]
    (spit benchmark-file
          (str ";; The hard person-reconciliation benchmark (lot N3), built by\n"
               ";; palomar.eval.person-reconciliation-hard/write! from the fixtures of this directory.\n"
               ";; Source : Bibliotheque nationale de France. Licence Ouverte / Etalab 2.0.\n"
               "{:pool\n " (lines "[" (:pool b) "]")
               "\n :records\n " (lines "{" (mapcat identity (:records b)) "}")
               "\n :mentions\n " (lines "[" (:mentions b) "]") "}\n"))
    (spit splits-file
          (let [s (split-file-content b)]
            (str "{:rule " (pr-str (:rule s)) "\n :salt " (pr-str (:salt s))
                 "\n :proportions " (pr-str (:proportions s))
                 "\n :assignments\n " (lines "{" (mapcat identity (:assignments s)) "}") "}\n")))))

(defn freeze!
  "Writes `freeze.edn`: the SHA-256 of this namespace's source, the
   construction rules at the moment they were frozen."
  [note]
  (spit freeze-file (pr-str {:file source-file :sha256 (sha-256 (slurp source-file)) :note note})))

;; ---------------------------------------------------------------------------
;; Loading
;; ---------------------------------------------------------------------------

(defn validate
  "`b` when it is a well-formed benchmark, else an `ex-info` naming the
   first bad mention or entry."
  [b]
  (let [bad-m (first (remove (fn [m] (and (vector? (:id m)) (string? (:text m))
                                          (#{:linked :no-match :set-aside} (:label m)) (keyword? (:reason m))
                                          (= (= :linked (:label m)) (string? (:truth m)))
                                          (= (= :set-aside (:label m)) (nil? (:split m)))))
                             (:mentions b)))
        bad-e (first (remove #(and (string? (:id %)) (string? (:label %)) (vector? (:variants %))) (:pool b)))]
    (cond
      bad-m (throw (ex-info "benchmark: malformed mention" {:mention bad-m}))
      bad-e (throw (ex-info "benchmark: malformed pool entry" {:entry bad-e}))
      :else b)))

(defn load-benchmark
  "The committed benchmark, validated, its pool's dates parsed
   (`agent-dates/parse-life-dates`), with the split file under `:splits`."
  ([] (load-benchmark benchmark-file splits-file))
  ([bench-path splits-path]
   (-> (validate (read-edn bench-path))
       (update :pool (fn [es] (mapv #(cond-> % (:dates %) (update :dates dates/parse-life-dates)) es)))
       (assoc :splits (read-edn splits-path)))))

(defn counts
  "The benchmark's counts: mentions by part and by label and reason, scored
   mentions, persons, no-match names and strata per split, the pool by
   source."
  [{:keys [mentions pool]}]
  (let [scored (filter :split mentions)]
    {:mentions  (into (sorted-map) (frequencies (map :part mentions)))
     :records   (count (distinct (map :record mentions)))
     :by-label  (into (sorted-map) (frequencies (map :label mentions)))
     :by-reason (into (sorted-map) (frequencies (map (juxt :label :reason) mentions)))
     :per-split (into (sorted-map)
                      (for [[s ms] (group-by :split scored)]
                        [s {:linked   (count (filter :truth ms))
                            :no-match (count (remove :truth ms))
                            :persons  (count (distinct (keep :truth ms)))
                            :names    (count (distinct (map split-key (remove :truth ms))))
                            :strata   (into (sorted-map) (frequencies (map :stratum ms)))}]))
     :pool      (into (sorted-map) (frequencies (map :source pool)))}))

(defn duplicate-ids
  "Pool ids that are probably one person: groups of two or more entries
   with the same heading (`text/norm`) and the same dates text."
  [pool]
  (vec (for [[_ es] (group-by (juxt (comp text/norm :label) #(get-in % [:dates :text] (:dates %))) pool)
             :when (< 1 (count es))]
         (vec (sort (map :id es))))))

;; ---------------------------------------------------------------------------
;; The harness
;; ---------------------------------------------------------------------------

(def fs-model
  "M4's Fellegi-Sunter model with its current parameters: m and λ learnt on
   every labelled mention of the easy benchmark, u from its pool
   (`pr/supervised-model`, all families). Not recalibrated here (lot N4)."
  (delay (let [c (pr/corpus)]
           (pr/supervised-model (pr/fs-fit-data c) (set (conj pr/families "others"))))))

(def ^:private surname-particles
  #{"de" "d'" "du" "des" "van" "von" "der" "den" "di" "da" "del" "della" "le" "la" "ten" "ter" "mac" "mc"})

(defn inverted
  "A transcribed name in the heading's order, \"Surname, Forenames\", read
   from the name alone (no gold): the last word, with the generational
   words after it and the capitalised particles before it (\"Van Loon\"),
   is the surname; lowercase particles before it (\"de\") go after the
   forenames, as the BnF writes \"Goncourt, Jules de\". A name with a comma
   or of one word is left as it is."
  [s]
  (let [ts (str/split (str/trim (str s)) #"\s+")]
    (if (or (str/includes? s ",") (< (count ts) 2))
      s
      (let [[core gen] (split-with #(not (generational (bare %))) ts)
            core (vec core)]
        (if (< (count core) 2)
          s
          (let [sur-start (loop [j (dec (count core))]
                            (if (and (pos? (dec j)) (capitalised? (core (dec j))) (surname-particles (fold (core (dec j)))))
                              (recur (dec j))
                              j))
                lower    (loop [j sur-start acc ()]
                           (if (and (pos? j) (not (capitalised? (core (dec j)))) (surname-particles (fold (core (dec j)))))
                             (recur (dec j) (cons (core (dec j)) acc))
                             [j acc]))
                [fstart parts] lower
                fore     (concat (subvec core 0 fstart) parts)
                sur      (concat (subvec core sur-start) gen)]
            (if (empty? (subvec core 0 fstart))
              s
              (str (str/join " " sur) ", " (str/join " " fore)))))))))

(defn- tied? [ps] (< 1 (count (filter #(= (:score (first ps)) (:score %)) ps))))

(defn- fs-run [name pool]
  (let [top (first (:proposals (reconcile/agent-candidates [{:name name :dates nil}] (pr/dated-entries pool)
                                                           {:model @fs-model})))]
    {:link (when (= :propose (:decision top)) top) :review? (= :review (:decision top))}))

(def engines
  "The existing engines, each a function of a mention's transcribed name and
   the pool, returning `{:link top-proposal-or-nil :tied? :review?}`. The
   Fellegi-Sunter runs: `:fellegi-sunter` on the name as transcribed (a
   format M4 never saw: its mentions were headings), and
   `:fellegi-sunter-inverted` on the name put in heading order
   (`inverted`)."
  {:jaccard
   (fn [name pool]
     (let [ps (reconcile/propose-agent-links [name] (pr/matcher-entries pool))]
       {:link (first ps) :tied? (tied? ps)}))
   :names-dates
   (fn [name pool]
     (let [ps (:proposals (reconcile/agent-candidates [{:name name :dates nil}] (pr/dated-entries pool)))]
       {:link (first ps) :tied? (tied? ps)}))
   :fellegi-sunter          fs-run
   :fellegi-sunter-inverted (fn [name pool] (fs-run (inverted name) pool))})

(defn outcome
  "The outcome of one scored mention `m` given an engine's result: with
   `ties` `:abstain` (the main reading) a tied top proposal is `:tied`, no
   link; with `ties` `:broken` the top proposal by pool order is the link."
  [m {:keys [link tied?]} ties]
  (let [pred (:authority-id link)]
    (cond
      (and tied? (= :abstain ties))        :tied
      (and (:truth m) (= pred (:truth m))) :correct
      (and (:truth m) pred)                :wrong-link
      (:truth m)                           :missed
      pred                                 :false-link
      :else                                :abstained)))

(defn- ratio [n d] (when (pos? d) (/ n d)))

(defn- macro [groups f]
  (let [per (for [[_ xs] groups] (f xs))]
    (when (seq per) (/ (reduce + per) (count per)))))

(defn summary
  "Counts and rates over scored cases `os` (`[{:outcome :truth :key
   :review?}]`): precision = correct / links made (a link on a no-match
   case is wrong); recall = correct / linked mentions; abstention =
   mentions with no link (a tie is none) / all; no-match abstention =
   no-match mentions with no link / no-match. Macro recall is the mean over
   persons of each person's recall; macro no-match abstention the mean over
   no-match names (`no-match-key`)."
  [os]
  (let [n     (fn [& ks] (count (filter (comp (set ks) :outcome) os)))
        links (n :correct :wrong-link :false-link)
        pos   (filter :truth os)
        neg   (remove :truth os)]
    {:mentions            (count os)
     :linked              (count pos)
     :no-match            (count neg)
     :correct             (n :correct)
     :wrong-link          (n :wrong-link)
     :missed              (count (filter #(and (:truth %) (#{:missed :tied} (:outcome %))) os))
     :tied                (n :tied)
     :false-link          (n :false-link)
     :review              (count (filter :review? os))
     :precision           (ratio (n :correct) links)
     :recall              (ratio (n :correct) (count pos))
     :abstention          (ratio (n :missed :abstained :tied) (count os))
     :no-match-abstention (ratio (count (filter #(#{:abstained :tied} (:outcome %)) neg)) (count neg))
     :persons             (count (distinct (map :truth pos)))
     :macro-recall        (macro (group-by :truth pos) #(/ (count (filter (comp #{:correct} :outcome) %)) (count %)))
     :names               (count (distinct (map :key neg)))
     :macro-no-match-abstention
     (macro (group-by :key neg) #(/ (count (filter (comp #{:abstained :tied} :outcome) %)) (count %)))}))

(defn run-engines
  "Every engine on the scored mentions of `split` of benchmark `b`, against
   the whole pool: `{:summaries {engine summary} :ties-broken {engine
   summary} :by-stratum {engine {stratum summary}} :errors {engine [case]}
   :cases {engine [case]}}`, a case `{:id :text :role :truth :truth-label
   :predicted :predicted-label :score :outcome :outcome-ties-broken
   :stratum}`. The main summaries read a tie as an abstention."
  [b split]
  (let [pool   (:pool b)
        labels (into {} (map (juxt :id :label)) pool)
        ms     (filter #(= split (:split %)) (:mentions b))
        cases  (into (sorted-map)
                     (for [[k f] engines]
                       [k (vec (for [m ms
                                     :let [r (f (:text m) pool)]]
                                 (cond-> {:id (:id m) :text (:text m) :role (:role m) :key (split-key m)
                                          :truth (:truth m) :truth-label (labels (:truth m)) :stratum (:stratum m)
                                          :predicted (get-in r [:link :authority-id])
                                          :predicted-label (labels (get-in r [:link :authority-id]))
                                          :score (get-in r [:link :score])
                                          :outcome (outcome m r :abstain)
                                          :outcome-ties-broken (outcome m r :broken)}
                                   (:review? r) (assoc :review? true))))]))]
    {:summaries   (into (sorted-map) (for [[k cs] cases] [k (summary cs)]))
     :ties-broken (into (sorted-map) (for [[k cs] cases] [k (summary (map #(assoc % :outcome (:outcome-ties-broken %)) cs))]))
     :by-stratum  (into (sorted-map) (for [[k cs] cases]
                                       [k (into (sorted-map) (for [[s xs] (group-by :stratum cs)] [s (summary xs)]))]))
     :errors      (into (sorted-map) (for [[k cs] cases] [k (filterv #(not (#{:correct :abstained} (:outcome %))) cs)]))
     :cases       cases}))

;; ---------------------------------------------------------------------------
;; The hand check
;; ---------------------------------------------------------------------------

(defn hand-check-sample
  "A deterministic, stratified sample of the train split of `b`, each
   stratum ordered by the SHA-256 bucket of the mention's id: `n` one-word
   linked mentions, `n` homonym cases, `n` easy cases, every residual,
   variant and hand-linked case up to `n`, and `n` no-match cases."
  [b n]
  (let [order (fn [ms] (sort-by (juxt #(bucket (pr-str (:id %))) (comp pr-str :id)) ms))
        train (filter #(= :train (:split %)) (:mentions b))
        one?  #(and (:truth %) (= 1 (count (match-tokens (:text %)))))
        take* (fn [pred] (vec (take n (order (filter pred train)))))]
    {:one-word  (take* one?)
     :homonym   (take* #(and (= :homonym (:stratum %)) (not (one? %))))
     :easy      (take* #(and (= :easy (:stratum %)) (not (one? %))))
     :residual  (take* #(#{:residual :variant :hand} (:stratum %)))
     :no-match  (take* #(nil? (:truth %)))}))

(defn- fmt [x] (if (nil? x) "n/a" (format "%.3f" (double x))))

(defn print-summary [k s]
  (println (format "%-24s n=%d linked=%d no-match=%d correct=%d wrong=%d missed=%d (tied %d) false=%d review=%d P=%s R=%s macroR=%s abst=%s nm-abst=%s macro-nm-abst=%s"
                   (name k) (:mentions s) (:linked s) (:no-match s) (:correct s) (:wrong-link s) (:missed s) (:tied s)
                   (:false-link s) (:review s) (fmt (:precision s)) (fmt (:recall s)) (fmt (:macro-recall s))
                   (fmt (:abstention s)) (fmt (:no-match-abstention s)) (fmt (:macro-no-match-abstention s)))))

(defn print-summaries [{:keys [summaries]}]
  (doseq [[k s] summaries] (print-summary k s)))

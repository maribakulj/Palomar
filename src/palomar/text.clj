(ns palomar.text
  "Shared string normalisation for identity keys and clustering. Single source of
   truth so the WEMI-derivation / projection Work keys and the entity-resolution evals
   cannot drift apart (audit 2026-06-03, R1)."
  (:require [clojure.string :as str]))

(defn norm
  "Normalise `s` for diacritic-, case- and punctuation-insensitive matching:
   lower-case in the root locale (never the JVM's default: a Turkish locale
   would fold `I` to a dotless `ı`), NFKD-decompose, drop combining marks
   (so a dotted capital `İ`, lower-cased to `i` and a combining dot, is a
   plain `i`), drop non-letter/-digit characters (Unicode-aware, so
   non-Latin scripts survive), collapse internal whitespace, trim. Used for Work/Expression keys (`intermarc.wemi`, `lrmoo.project`)
   and the ER evals, so they stay byte-identical."
  [s]
  (-> (java.text.Normalizer/normalize (.toLowerCase (str s) java.util.Locale/ROOT) java.text.Normalizer$Form/NFKD)
      (str/replace #"\p{M}+" "")
      (str/replace #"[^\p{L}\p{N} ]" " ")
      (str/replace #"\s+" " ")
      str/trim))

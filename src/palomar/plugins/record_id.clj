(ns palomar.plugins.record-id
  "Record ids derived from a source identifier (a MARC 001, a MODS
   recordIdentifier, a IIIF manifest URI), kept readable *and* injective
   (audit S20).

   A spoke wants a short keyword it can show in a report, but slugging an
   identifier to `[A-Za-z0-9-]` is many-to-one: `ocm 123` and `ocm-123`, or
   two manifests that end in `/book1/manifest` on different hosts, would become
   the same record and silently merge. So the slug is kept only when it *is*
   the identifier; whenever slugging changed or dropped anything, a short
   SHA-256 digest of the full (trimmed) identifier is appended. Leading and
   trailing whitespace is not significant."
  (:require [clojure.string :as str]
            [palomar.model :as model]))

(defn- digest
  "The first 10 hex digits (40 bits) of the SHA-256 of `s`."
  [s]
  (model/sha256-hex s 10))

(defn slug
  "`s` trimmed, every run of characters outside `[A-Za-z0-9]` collapsed to `-`."
  [s]
  (-> (str s) str/trim (str/replace #"[^A-Za-z0-9]+" "-")))

(defn from-identifier
  "`(keyword ns (str prefix (slug raw)))` when the slug is the trimmed identifier
   itself, else the same with `-h<digest of raw>` appended, so two distinct
   identifiers share an id only on a 40-bit digest collision (or when one is
   literally spelled as the other's digested form)."
  [ns prefix raw]
  (let [trimmed (str/trim (str raw))
        s       (slug trimmed)]
    (keyword ns (if (= s trimmed)
                  (str prefix s)
                  (str prefix s "-h" (digest trimmed))))))

(defn from-uri
  "A readable id for URI `uri` from a short `label` taken from it (e.g. its last
   path segment), always with the URI's digest appended: a label drops the host
   and the rest of the path, so it alone is never injective."
  [ns prefix label uri]
  (keyword ns (str prefix (slug label) "-h" (digest (str/trim (str uri))))))

(defn source-identifier
  "The identifier `record`'s source gave it, as text for a control field (a
   MARC `001`, a MODS `recordIdentifier`): its `:source` when that is a string,
   the name of a keyword source, else nil. A structure is never printed into a
   control field (post-audit 3, E1)."
  [record]
  (let [s (:source record)]
    (cond (and (string? s) (not (str/blank? s))) s
          (keyword? s)                           (name s))))

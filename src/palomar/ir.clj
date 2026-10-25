(ns palomar.ir
  "The intermediate representation as a file (ADR 0034): records written as EDN
   and read back, validated, so a pipeline can stop at the pivot and resume
   there — import once, curate, export many times — or hand the pivot to
   another program.

   A document is a sequence of EDN forms, one per line, each an envelope
   `{:palomar/ir <version> :record <record>}`. One envelope per record lets a
   document be streamed and concatenated; the version on each lets a reader
   refuse a record it does not know how to read rather than misread it.

   Writing is deterministic: maps and sets are printed in a stable order (by
   the printed form of their keys), so the same records always give the same
   bytes, and a document can be diffed and fingerprinted (ADR 0030). Writing
   refuses a record the reader could not give back equal — one that fails the
   `model/Record` schema, or holds a keyword or symbol EDN cannot read (a name
   with a space). Reading validates every record against the schema.

   `schema-form` is the published shape of a record: the malli form of
   `model/Record` with its predicates named, as `docs/ir/record-schema.edn`
   holds it."
  (:require [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [malli.core :as m]
            [palomar.model :as model]))

(def version
  "The IR version this namespace writes and reads."
  1)

(defn- print-key
  [x]
  (binding [*print-namespace-maps* false] (pr-str x)))

(defn- by-print [a b] (compare (print-key a) (print-key b)))

(defn- stable
  "`x` with every map and set ordered by the printed form of its keys."
  [x]
  (walk/postwalk (fn [v]
                   (cond
                     (and (map? v) (not (record? v))) (into (sorted-map-by by-print) v)
                     (set? v)                         (into (sorted-set-by by-print) v)
                     :else                            v))
                 x))

(defn- unreadable
  "The first keyword or symbol in `x` that EDN would not read back as itself."
  [x]
  (let [bad (fn [k] (let [s (str k)]
                      (when-not (= k (try (edn/read-string s) (catch Exception _ ::unreadable)))
                        k)))]
    (some #(when (or (keyword? %) (symbol? %)) (bad %))
          (tree-seq coll? seq x))))

(defn- check!
  [record]
  (when-not (model/valid-record? record)
    (throw (ex-info "ir: not a valid record" {:id (:id record) :explain (model/explain-record record)})))
  (when-let [k (unreadable record)]
    (throw (ex-info (str "ir: " (pr-str k) " cannot be written as EDN and read back")
                    {:id (:id record) :value k})))
  record)

(defn ->edn-line
  "One record as an IR envelope on one line (no trailing newline)."
  [record]
  (binding [*print-namespace-maps* false *print-length* nil *print-level* nil]
    (pr-str (stable {:palomar/ir version :record (check! record)}))))

(defn ->edn
  "`records` as an IR document: one envelope per line, newline-terminated (\"\"
   when there is none)."
  [records]
  (apply str (map #(str (->edn-line %) "\n") records)))

(defn read-records
  "The records of IR document `s` (a string or a `java.io.Reader`), in order.
   Throws on a form that is not an envelope, on an unknown version and on a
   record that fails `model/Record`."
  [s]
  (let [r (java.io.PushbackReader. (if (string? s) (java.io.StringReader. s) s))]
    (loop [acc []]
      (let [form (edn/read {:eof ::eof} r)]
        (cond
          (= ::eof form) acc

          (not (and (map? form) (contains? form :palomar/ir) (contains? form :record)))
          (throw (ex-info "ir: expected an envelope {:palomar/ir <version> :record <record>}"
                          {:form (if (map? form) (keys form) (type form))}))

          (not= version (:palomar/ir form))
          (throw (ex-info (str "ir: unsupported version " (pr-str (:palomar/ir form)))
                          {:version (:palomar/ir form) :supported version}))

          (not (model/valid-record? (:record form)))
          (throw (ex-info "ir: not a valid record"
                          {:id (get-in form [:record :id]) :explain (model/explain-record (:record form))}))

          :else (recur (conj acc (:record form))))))))

(defn- name-fns
  "A malli form with its function objects named, and generator hints removed."
  [form]
  (walk/prewalk (fn [v]
                  (cond
                    (identical? v model/finite-double?) 'palomar.model/finite-double?
                    (identical? v inst?)                'clojure.core/inst?
                    (fn? v)                             (throw (ex-info "ir: unnamed predicate in the schema" {:fn v}))
                    (and (vector? v) (keyword? (first v)) (map? (second v)) (contains? (second v) :gen/fmap))
                    (let [props (dissoc (second v) :gen/fmap)]
                      (if (empty? props) (into [(first v)] (drop 2 v)) (assoc v 1 props)))
                    :else                               v))
                form))

(defn schema-form
  "The published shape of an IR record: `model/Record`'s malli form, as data."
  []
  (name-fns (m/form model/Record)))

(defn schema-edn
  "`schema-form` printed, as `docs/ir/record-schema.edn` holds it."
  []
  (binding [*print-namespace-maps* false]
    (str ";; The shape of a Palomar IR record (palomar.ir, ADR 0034), as a malli schema.\n"
         ";; Generated by `palomar ir-schema`; a contract test keeps it in sync.\n"
         (str/trim (with-out-str (pprint/pprint (schema-form))))
         "\n")))

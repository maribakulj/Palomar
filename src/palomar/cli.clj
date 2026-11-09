(ns palomar.cli
  "Command-line entry point (WP-8) — the last step from library to runnable tool.
   `convert` runs a source document through the LRMoo pivot to a target
   serialisation (document to **stdout**, ADR 0015 loss report to **stderr**);
   `validate` runs the canonical rules and exits non-zero on failure; `report`,
   `inspect` and `reconcile` are read-only verbs that write to **stdout**.

     palomar convert       <input-file> --from <fmt> --to <fmt> [--record-id <id>] [--out <file>]
     palomar validate      <input-file> --from <fmt> [--policy <p>] [--record-id <id>]
     palomar report        <input-file> --from <fmt> --to <fmt> [--record-id <id>]
     palomar inspect       <input-file> --from <fmt> [--record-id <id>]
     palomar reconcile     <input-file> --from <fmt> [--authorities <file|index>] [--manifest <file>] [--record-id <id>]
     palomar authorities index <dump> --out <index> [--built YYYY-MM-DD]
     palomar apply-repairs <input-file> --from <fmt> [--policy <p>] [--to <fmt>] [--by <curator>] [--record-id <id>]
     palomar conformance   <input-file> --from <fmt> --profile <p> [--policy <p>] [--record-id <id>]
     palomar formats
     palomar ir-schema
     palomar --help

   - `report`        — the X→Y loss report alone (the auditor's view), no document.
   - `inspect`       — what the source parses to: the canonical floor + minted
                       WEMI/agent entities (the developer's view).
   - `reconcile`     — cross-record agent reconciliation by authority id (ADR 0018).
   - `authorities index` — prepare an authority dump (lobid GND JSON lines) once
                       into a local index that `reconcile --authorities` reads
                       offline (lot M6, ADR 0038).
   - `apply-repairs` — curate the `:proposed` claims a pipeline run emits (ADR 0005):
                       accept / reject / flag-for-review the inferred WEMI proposals.
                       With `--to`, export the curated records (rejected claims are
                       never emitted) to stdout; the curation outcome goes to stderr.
   - `conformance`   — check the WEMI projection against an institutional profile
                       (WP-6); exits non-zero under the acceptance-threshold policy.

   `run` is pure — it parses args, does the work and returns `{:exit :out :err}`
   without printing or exiting — so tests drive it directly; `-main` is the thin
   shell that prints the streams and calls `System/exit`."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [palomar.conformance :as conformance]
            [palomar.convert :as convert]
            [palomar.curate :as curate]
            [palomar.diagnostics :as dx]
            [palomar.plugins.agents :as agents]
            [palomar.authority-index :as index]
            [palomar.plugins.authorities :as authorities]
            [palomar.ir :as ir]
            [palomar.loss-report :as lr]
            [palomar.manifest :as manifest]
            [palomar.reconcile :as reconcile]
            [palomar.validate :as validate]))

(def ^:private usage
  (str/join
   "\n"
   ["palomar — documentary-metadata conversion through the LRMoo pivot"
    ""
    "Usage:"
    "  palomar convert       <input-file> --from <fmt> --to <fmt> [--with-proposals] [--manifest <file>] [--record-id <id>] [--out <file>]"
    "  palomar convert       <input-file> --from <marc-fmt> --to <fmt> --stream --out <file>"
    "  palomar validate      <input-file> --from <fmt> [--policy <p>] [--record-id <id>]"
    "  palomar report        <input-file> --from <fmt> --to <fmt> [--format text|edn|json] [--record-id <id>]"
    "  palomar inspect       <input-file> --from <fmt> [--record-id <id>]"
    "  palomar reconcile     <input-file> --from <fmt> [--authorities <file.rdf|file.nt|index>] [--manifest <file>] [--record-id <id>]"
    "  palomar authorities index <dump.jsonl[.gz]> --out <index> [--built YYYY-MM-DD]"
    "  palomar apply-repairs <input-file> --from <fmt> [--policy <p>] [--to <fmt>] [--by <curator>] [--record-id <id>]"
    "  palomar conformance   <input-file> --from <fmt> --profile <p> [--policy <p>] [--record-id <id>]"
    "  palomar formats          list the supported source and target formats"
    "  palomar ir-schema        print the schema of a pivot record (the EDN IR, --from/--to edn)"
    "  palomar --help"
    ""
    "  --from        source format (a spoke; edn, the pivot written by --to edn; oai, an OAI-PMH response)"
    "  --to          target serialisation (convert, report)"
    "  --profile     conformance profile: linked-art, intermarc, iiif"
    "  --policy      validate / conformance: errors-only (default), errors-and-warnings, strict, never"
    "                apply-repairs: flag (default), accept, reject"
    "  --record-id   record id for single-record spokes (Dublin Core; default: from the filename)"
    "  --authorities reconcile: a MADS or SKOS authority file (RDF/XML, or N-Triples as .nt), an EAC-CPF record, BnF INTERMARC authority records,"
    "                or an authority index (palomar authorities index) to match names against; a dump is never read here"
    "  --built       authorities index: the build date the index records (default: today, UTC); same dump and date, same bytes"
    "  --metadata-prefix  convert --from oai: the OAI metadata format (oai_dc, marcxml, mods; default oai_dc)"
    "  --out         write the output to a file instead of stdout (convert)"
    "  --stream      convert in bounded memory, streaming a large MARC dump or LIDO lidoWrap to --out (WP-7)"
    "  --fail-on-invalid convert: refuse the whole conversion (exit 1, nothing written) when a record is not valid"
    "                    (one that carries an error: today a LIDO record its XSD rejects; the wrap is not checked)"
    "  --with-proposals  convert: also state the proposed claims (uncurated inferences) as facts; by default a"
    "                plain RDF or record export holds facts only, and trig/nquads/prov keep each claim with its status"
    "  --manifest    convert: write the run manifest (versions, digests, options, loss) as EDN;"
    "                reconcile: the same, naming each authority file or index matched against"
    "  --format      report output: text (default), edn or json (the raw loss-report map, ADR 0015)"
    ""
    "convert writes the document to stdout, the loss report to stderr;"
    "validate writes the diagnostics report to stderr and exits non-zero on failure;"
    "report writes the X→Y loss report to stdout (no document; --format edn|json for the raw map);"
    "inspect writes what the source parses to (the canonical floor + minted entities) to stdout;"
    "reconcile writes the cross-record agent reconciliation (by authority id) to stdout;"
    "authorities index writes the index to --out and a summary to stderr;"
    "apply-repairs curates the inferred :proposed claims (ADR 0005) and writes the outcome to stdout;"
    "conformance reports profile diagnostics to stderr and exits non-zero under the acceptance policy."]))

(defn- parse-args
  "Parse `args` into {:command :positional [..] :flags {str str} :help?}.
   `--flag value` pairs become flags; the first bare token is the command, the
   rest positionals."
  [args]
  (loop [as args m {:flags {}}]
    (if (empty? as)
      m
      (let [a (first as)]
        (cond
          (#{"--help" "-h"} a)      (recur (rest as) (assoc m :help? true))
          (#{"--stream"} a)         (recur (rest as) (assoc-in m [:flags "stream"] true)) ; boolean
          (#{"--facts-only"} a)     (recur (rest as) (assoc-in m [:flags "facts-only"] true)) ; boolean, now the default
          (#{"--with-proposals"} a) (recur (rest as) (assoc-in m [:flags "with-proposals"] true)) ; boolean
          (#{"--fail-on-invalid"} a) (recur (rest as) (assoc-in m [:flags "fail-on-invalid"] true)) ; boolean
          (str/starts-with? a "--") (recur (drop 2 as) (assoc-in m [:flags (subs a 2)] (second as)))
          (:command m)              (recur (rest as) (update m :positional (fnil conj []) a))
          :else                     (recur (rest as) (assoc m :command a)))))))

(defn- ->record-id
  "The `--record-id` as a keyword. It must be `ns/name` with no `.`: fragments
   and qualified values mint ids from it (ADR 0012), which an un-namespaced or
   dotted id cannot carry injectively, so it is refused here with a clear message
   instead of failing mid-conversion."
  [s]
  (let [s     (str/replace-first s #"^:" "")
        [a b] (str/split s #"/" 2)]
    (when-not (and (seq a) (seq b) (not (str/includes? s "."))
                   (re-matches #"[A-Za-z][^/\s]*" a) (re-matches #"[A-Za-z][^/\s]*" b))
      (throw (ex-info (str "--record-id must be ns/name, each starting with a letter, with no '.' or space"
                           " (e.g. doc/my-record), got " (pr-str s)) {})))
    (keyword a b)))

(defn- file-stem
  "A record name from a file path: its stem, non-alphanumerics folded to `-`,
   prefixed `d` unless it starts with a letter, so the keyword reads back as
   EDN (ADR 0034)."
  [path]
  (let [stem (-> path (str/replace #".*/" "") (str/replace #"\.[^.]*$" "") (str/replace #"[^A-Za-z0-9]+" "-"))]
    (if (re-find #"^[A-Za-z]" stem) stem (str "d" stem))))

(defn- fmt-line [label fs] (str label (str/join " " (sort (map name fs)))))

(defn- zero-records-note
  "A stderr warning appended when a parse yields no records — almost always a
   wrong `--from` or wrong file rather than a legitimately empty collection
   (which would also be 0, so this stays a warning, not an error). Empty string
   when records > 0, so the happy path is untouched."
  [records from input]
  (if (zero? records)
    (str "\nwarning: 0 records parsed from " input
         " — is --from " from " correct for this file?")
    ""))

(defn- manifest-edn
  "The run manifest of a `convert` (ADR 0030), as EDN. An input that is no
   regular file (a pipe) is not read again (post-audit 8, K6 review)."
  [{:keys [from to input out result opts started-at]}]
  (manifest/->edn
   (manifest/manifest {:from        from
                       :to          to
                       :opts        opts
                       :inputs      [{:path input :content (let [f (io/file input)] (when (.isFile f) f))}]
                       :outputs     [{:path (or out "-") :content (:output result)}]
                       :loss        (:loss result)
                       :started-at  started-at
                       :finished-at (java.util.Date.)})))

(defn- invalid-note
  "The report's line on the records that are not valid, or nil (post-audit 6, H3)."
  [{n :records es :errors}]
  (when (pos? (or n 0))
    (str "\n(" n " record" (when (not= 1 n) "s") " not valid: " (:message (first es))
         "; convert --fail-on-invalid refuses such a conversion)")))

(defn- refused-or-error
  "The exit of a conversion that threw: 1 when it refused invalid records
   (`--fail-on-invalid`), as `validate` fails on them; 2 for any other error."
  [e]
  (if (and (:records (ex-data e)) (:errors (ex-data e)))
    {:exit 1 :out "" :err (str "INVALID: " (.getMessage e) "\n"
                               (str/join "\n" (map #(str "  " (:subject %) " " (:message %)) (:errors (ex-data e)))))}
    {:exit 2 :out "" :err (str "error: " (.getMessage e) " " (pr-str (ex-data e)))}))

(declare replace-file!)

(def ^:private no-links (make-array java.nio.file.LinkOption 0))

(defn- case-folded?
  "Whether the directory of `f` folds case: a name differing only in case
   names the same file. Probed with a temp file of its own, else false."
  [^java.io.File f]
  (try
    (let [t (java.io.File/createTempFile "palomar-case-" ".probe" (.getParentFile f))]
      (try (.exists (java.io.File. (.getParentFile t) (str/upper-case (.getName t))))
           (finally (.delete t))))
    (catch java.io.IOException _ false)))

(defn- same-file?
  "Whether paths `a` and `b` name one file: by the file system when both
   exist (a symbolic link, a hard link), else by their canonical paths, and,
   where the directory folds case, by their canonical paths in any case
   (review of 2026-11-09, L1)."
  [a b]
  (let [fa (.getAbsoluteFile (io/file a))
        fb (.getAbsoluteFile (io/file b))]
    (if (and (.exists fa) (.exists fb))
      (java.nio.file.Files/isSameFile (.toPath fa) (.toPath fb))
      (let [ca (.getCanonicalPath fa) cb (.getCanonicalPath fb)]
        (or (= ca cb)
            (and (= (str/lower-case ca) (str/lower-case cb)) (case-folded? (io/file ca))))))))

(defn- path-collision
  "The refusal of a conversion two of whose paths, the input, `--out` and
   `--manifest`, name one file, or nil. The output or the manifest would
   replace the source, or one another, after a run said to succeed (L1)."
  [{:strs [out manifest]} input]
  (first (for [[an a bn b] [["--out" out "the input file" input]
                            ["--manifest" manifest "the input file" input]
                            ["--manifest" manifest "--out" out]]
               :when (and a b (same-file? a b))]
           {:exit 2 :out "" :err (str "convert: " an " is " bn " (" a "); choose another path")})))

(defn- staging
  "Where to write what will be published at `path`: `{:tmp :dst :move?}`.
   A file that is or will be a regular file, followed through its links, is
   written beside it and moved over it, so a failed run leaves it as it was.
   A device or a pipe, or a file whose directory takes no new file, is
   written into, as before L1 (L1 review)."
  [path prefix]
  (let [p        (.toPath (.getAbsoluteFile (io/file path)))
        exists?  (java.nio.file.Files/exists p (make-array java.nio.file.LinkOption 0))
        regular? (and exists? (java.nio.file.Files/isRegularFile p (make-array java.nio.file.LinkOption 0)))
        dst      (if exists? (.toRealPath p (make-array java.nio.file.LinkOption 0)) p)
        ;; a new file is written beside it or not at all: its directory
        ;; refuses it before anything is published (K6 review)
        beside   (cond
                   (not exists?) (java.io.File/createTempFile prefix ".part" (.toFile (.getParent dst)))
                   regular?      (try (java.io.File/createTempFile prefix ".part" (.toFile (.getParent dst)))
                                      (catch java.io.IOException _ nil)))]
    (if beside
      {:tmp beside :dst dst :move? true}
      {:tmp (java.io.File/createTempFile prefix ".part") :dst dst :move? false})))

(defn- keep-attributes!
  "Give `tmp` the permissions and owner of the file it replaces, so a private
   output stays private; best effort where the file system or the user
   cannot."
  [^java.nio.file.Path dst ^java.io.File tmp]
  (when (java.nio.file.Files/exists dst no-links)
    (let [t (.toPath tmp)]
      (try (java.nio.file.Files/setPosixFilePermissions t (java.nio.file.Files/getPosixFilePermissions dst no-links))
           (catch UnsupportedOperationException _ nil) (catch java.io.IOException _ nil))
      (try (java.nio.file.Files/setOwner t (java.nio.file.Files/getOwner dst no-links))
           (catch UnsupportedOperationException _ nil) (catch java.io.IOException _ nil)))))

(defn- publish-one!
  "Put the bytes staged in `tmp` at `dst`; true on success."
  [{:keys [^java.io.File tmp ^java.nio.file.Path dst move?]}]
  (if move?
    (do (keep-attributes! dst tmp) (replace-file! tmp (.toFile dst)))
    (try (with-open [o (java.nio.file.Files/newOutputStream dst (make-array java.nio.file.OpenOption 0))]
           (java.nio.file.Files/copy (.toPath tmp) o))
         true
         (catch java.io.IOException _ false))))

(defn- publish!
  "Publish the staged output `o` at `out`, then the staged manifest `m` at
   `mpath` (either may be nil). nil on success, else the error, which says
   what was published: two files cannot be replaced at once, so a manifest
   that fails after its output is reported, never a success (L1)."
  [o out m mpath]
  (cond
    (and o (not (publish-one! o)))
    (str "error: could not move the output to " out "; nothing was published")

    (and m (not (publish-one! m)))
    (str "error: " (when o (str "the output was published to " out ", but "))
         "the manifest could not be written to " mpath "; no manifest describes this run"
         " (a file already there describes an earlier one)")))

(defn- do-convert [{:strs [from to record-id out with-proposals] :as flags} input]
  (cond
    (not (and from to input))
    {:exit 2 :out "" :err (str "convert needs <input-file> --from <fmt> --to <fmt>\n\n" usage)}

    (not (.exists (java.io.File. ^String input)))
    {:exit 2 :out "" :err (str "input file not found: " input)}

    :else
    (or
     (path-collision flags input)
     (let [tmps (atom [])]
       (try
         (let [started (java.util.Date.)
               rid    (cond record-id      (->record-id record-id)
                            (= from "dc")   (keyword "doc" (file-stem input))
                            :else           nil)
               opts   (cond-> {}
                        rid                         (assoc :record-id rid)
                        (get flags "metadata-prefix") (assoc :metadata-prefix (get flags "metadata-prefix")))
               result (convert/convert {:from   (keyword from)
                                        :to     (keyword to)
                                        :source (slurp (io/file input))
                                        :with-proposals? (boolean with-proposals)
                                        :fail-on-invalid? (boolean (get flags "fail-on-invalid"))
                                        :opts   opts})
               mpath  (get flags "manifest")
              ;; written beside their paths, then moved: the manifest names
              ;; the output as it is published, byte for byte (L1)
               o      (when out (doto (staging out "palomar-out-") (#(swap! tmps conj (:tmp %))) (#(spit (:tmp %) (:output result)))))
               m      (when mpath
                        (doto (staging mpath "palomar-manifest-") (#(swap! tmps conj (:tmp %)))
                              (#(spit (:tmp %) (manifest-edn {:from (keyword from) :to (keyword to) :input input :out out
                                                              :result (cond-> result o (assoc :output (:tmp o))) :started-at started
                                                              :opts (cond-> opts with-proposals (assoc :with-proposals? true))})))))
               report (str (lr/format-conversion-report (:loss result))
                           "\n(" (:records result) " record"
                           (when (not= 1 (:records result)) "s") " converted)"
                           (when (seq (:deleted result))
                             (str "\n(" (count (:deleted result)) " record"
                                  (when (not= 1 (count (:deleted result))) "s")
                                  " deleted at the source: " (str/join " " (:deleted result)) ")"))
                           (invalid-note (:invalid result))
                           (zero-records-note (:records result) from input))]
           (if-let [err (publish! o out m mpath)]
             {:exit 2 :out "" :err err}
             (if out
               {:exit 0 :out "" :err (str "wrote " out "\n" report)}
               {:exit 0 :out (:output result) :err report})))
         (catch clojure.lang.ExceptionInfo e
           (refused-or-error e))
         (catch Exception e
           {:exit 2 :out "" :err (str "error: " (.getMessage e))})
         (finally
           (doseq [^java.io.File t @tmps :when (.exists t)] (.delete t))))))))

(defn- do-validate [{:strs [from policy record-id]} input]
  (cond
    (not (and from input))
    {:exit 2 :out "" :err (str "validate needs <input-file> --from <fmt>\n\n" usage)}

    (not (.exists (java.io.File. ^String input)))
    {:exit 2 :out "" :err (str "input file not found: " input)}

    :else
    (try
      (let [rid (cond record-id    (->record-id record-id)
                      (= from "dc") (keyword "doc" (file-stem input))
                      :else         nil)
            pol (if policy (keyword policy) :errors-only)
            {:keys [records diagnostics summary failed?]}
            (validate/validate {:from   (keyword from)
                                :source (slurp (io/file input))
                                :opts   (cond-> {} rid (assoc :record-id rid))
                                :policy pol})
            verdict (str (if failed? "INVALID" "VALID")
                         " — " records " record" (when (not= 1 records) "s") ", "
                         (get-in summary [:by-severity :error]) " error(s), "
                         (get-in summary [:by-severity :warning]) " warning(s)"
                         " [policy " (name pol) "]")]
        {:exit (if failed? 1 0)
         :out  ""
         :err  (if (seq diagnostics) (str (dx/format-report diagnostics) "\n" verdict) verdict)})
      (catch clojure.lang.ExceptionInfo e
        {:exit 2 :out "" :err (str "error: " (.getMessage e) " " (pr-str (ex-data e)))})
      (catch Exception e
        {:exit 2 :out "" :err (str "error: " (.getMessage e))}))))

;; --- shared source reading + error guard (report / inspect / reconcile) -----

(defn- read-source
  "Validate `from` + `input` for the read-only verbs. Returns `{:err msg}` on a
   problem, else `{:from kw :source str :opts {…}}` ready to feed convert/to-wemi."
  [from input record-id verb]
  (cond
    (not (and from input))
    {:err (str verb " needs <input-file> --from <fmt>\n\n" usage)}

    (not (.exists (java.io.File. ^String input)))
    {:err (str "input file not found: " input)}

    :else
    (let [rid (cond record-id    (->record-id record-id)
                    (= from "dc") (keyword "doc" (file-stem input))
                    :else         nil)]
      {:from   (keyword from)
       :source (slurp (io/file input))
       :opts   (cond-> {} rid (assoc :record-id rid))})))

(defn- guard
  "Run `thunk`, mapping the two expected exception shapes to an exit-2 result —
   the catch the convert/validate verbs use, shared by the read-only verbs."
  [thunk]
  (try (thunk)
       (catch clojure.lang.ExceptionInfo e
         {:exit 2 :out "" :err (str "error: " (.getMessage e) " " (pr-str (ex-data e)))})
       (catch Exception e
         {:exit 2 :out "" :err (str "error: " (.getMessage e))})))

(defn- replace-file!
  "Move `src` over `dst`, returning true on success. The existing `dst` is never
   deleted first: when the move fails it is left exactly as it was. An atomic
   rename is preferred; the plain replacing move is the fallback for file systems
   that refuse atomic moves."
  [^java.io.File src ^java.io.File dst]
  (let [from (.toPath src)
        to   (.toPath dst)]
    (try
      (java.nio.file.Files/move from to
                                (into-array java.nio.file.CopyOption
                                            [java.nio.file.StandardCopyOption/ATOMIC_MOVE]))
      true
      (catch java.nio.file.AtomicMoveNotSupportedException _
        (try
          (java.nio.file.Files/move from to
                                    (into-array java.nio.file.CopyOption
                                                [java.nio.file.StandardCopyOption/REPLACE_EXISTING]))
          true
          (catch java.io.IOException _ false)))
      (catch java.io.IOException _ false))))

(defn- do-convert-stream
  "Streaming convert (WP-7): lazily parse the input and write each rendered document
   to `--out` incrementally, in bounded memory (the whole corpus is never
   materialised). Requires a streamable spoke (the MARC family, LIDO) and `--out` (the
   document sink — like the batch `--out`, this writes a file). The loss report is
   returned in `:err`."
  [{:strs [from to record-id out] :as flags} input]
  (cond
    (not (and from to input))
    {:exit 2 :out "" :err (str "convert --stream needs <input-file> --from <fmt> --to <fmt> --out <file>\n\n" usage)}

    (not out)
    {:exit 2 :out "" :err "convert --stream requires --out <file> (the streamed document sink)"}

    (not (.exists (java.io.File. ^String input)))
    {:exit 2 :out "" :err (str "input file not found: " input)}

    (not (convert/streamable? (keyword from)))
    {:exit 2 :out "" :err (str "--stream is not supported for " from
                               " (streamable: " (str/join " " (map name (convert/streamable-sources))) ")")}

    ;; the stream is read while --out is written: the same file would be replaced
    ;; by the output at the end, losing the source (audit S40); nor may the
    ;; manifest name either (L1)
    :else
    (or
     (path-collision flags input)
    ;; Stream to a sibling temp file and atomically rename on success, so a
    ;; mid-stream failure (e.g. a malformed input) leaves NO partial --out — the
    ;; same all-or-nothing guarantee the batch path gives (it spits only at the end).
    ;; Everything that can throw — including creating the temp file (a bad --out
    ;; directory) — is inside the try, so the verb always returns a clean exit, and
    ;; the `finally` deletes the temp in every failure path (on success it was renamed away).
     (let [fromk (keyword from)
           opts  (cond-> {} record-id (assoc :record-id (->record-id record-id)))
           sha   (when (.isFile (java.io.File. ^String input)) (convert/source-digest (io/file input)))
           started (java.util.Date.)
           o     (atom nil)
           m     (atom nil)]
       (try
         (reset! o (staging out "palomar-stream-"))
         (let [[open close] (convert/collection (keyword to))
               res (with-open [r (io/reader (io/file input))
                               w (io/writer (:tmp @o))]
                     (when open (.write w open) (.write w "\n"))
                     (let [res (convert/convert-stream
                                {:from fromk :to (keyword to)
                                 :with-proposals? (boolean (get flags "with-proposals"))
                                 :fail-on-invalid? (boolean (get flags "fail-on-invalid"))
                                ;; one more read, in constant memory: the same
                                ;; document digest as batch (post-audit 7, I2);
                                ;; a pipe can be read but once (I2 review)
                                 :source-sha256 sha
                                 :records (convert/stream-source fromk opts r)}
                                (fn [doc] (.write w doc) (.write w "\n")))]
                       (when close (.write w close) (.write w "\n"))
                       res))]
          ;; the run manifest, as batch writes it: the output is the file
          ;; written, fingerprinted as it is read (post-audit 8, K6). It is
          ;; written beside its path first, so a manifest that cannot be
          ;; written leaves --out as it was (K6 review)
           (when-let [mpath (get flags "manifest")]
             (reset! m (staging mpath "palomar-manifest-"))
             (spit (:tmp @m) (manifest-edn {:from fromk :to (keyword to) :input input :out out
                                            :result (assoc res :output (:tmp @o)) :started-at started
                                            :opts (cond-> (assoc opts :stream? true)
                                                    (get flags "with-proposals") (assoc :with-proposals? true))})))
           (if-let [err (publish! @o out @m (get flags "manifest"))]
             {:exit 2 :out "" :err err}
             {:exit 0 :out ""
              :err  (str "wrote " out " — " (:records res) " record" (when (not= 1 (:records res)) "s")
                         " streamed (bounded memory)\n"
                         (lr/format-conversion-report (:loss res))
                         (invalid-note (:invalid res))
                         (when-not sha "\nnote: the input is no regular file (a pipe): it is read once, so no record names its document by a digest\n")
                         (zero-records-note (:records res) from input))}))
         (catch clojure.lang.ExceptionInfo e
           (refused-or-error e))
         (catch Exception e
           {:exit 2 :out "" :err (str "error: " (.getMessage e))})
         (finally
           (doseq [{t :tmp} [@o @m] :when (and t (.exists ^java.io.File t))] (.delete ^java.io.File t))))))))

(defn- jsonable
  "Render a value as JSON-safe data for the loss report: every keyword — whether a
   map key or a vector element — becomes a **namespace-qualified** string
   (`:canon/date` → `\"canon/date\"`). data.json's default key-fn is `name`, which
   drops the namespace and would collide distinct source fields; qualifying keeps
   them distinct, matching the EDN form."
  [x]
  (cond
    (keyword? x)    (subs (str x) 1)
    (map? x)        (into {} (map (fn [[k v]] [(jsonable k) (jsonable v)])) x)
    (sequential? x) (mapv jsonable x)
    :else           x))

(defn- do-report
  "The auditor's verb: the X→Y loss report to stdout, no converted document.
   `--format text` (default) renders the human report; `--format edn` emits the
   raw conversion-report map (ADR 0015), pretty-printed; `--format json` emits the
   same map as JSON with namespace-qualified keys — the machine-readable forms for
   audit tooling, run-to-run diffs and CI loss thresholds (EDN for Clojure
   consumers, JSON for everything else)."
  [{:strs [from to record-id] :as flags} input]
  (let [{:keys [err] :as r} (read-source from input record-id "report")
        fmt (or (get flags "format") "text")]
    (cond
      err      {:exit 2 :out "" :err err}
      (not to) {:exit 2 :out "" :err (str "report needs --to <fmt>\n\n" usage)}
      (not (#{"text" "edn" "json"} fmt))
      {:exit 2 :out "" :err (str "report --format must be 'text', 'edn' or 'json' (got " (pr-str fmt) ")")}
      :else
      (guard #(let [{:keys [report records loss]} (convert/convert-report (assoc r :to (keyword to)))]
                {:exit 0
                 :out  (case fmt
                         "edn"  (with-out-str (pprint/pprint (assoc loss :records records)))
                         "json" (str (json/write-str (jsonable (assoc loss :records records))
                                                     :escape-slash false) "\n")
                         "text" (str report "\n(" records " record" (when (not= 1 records) "s") ")"))
                 :err  ""})))))

(defn- canon-floor
  "The record's own `:canon/*` string assertions, grouped by predicate."
  [record]
  (->> (:assertions record)
       (filter #(and (= (:id record) (:subject %))
                     (= "canon" (namespace (:predicate %)))
                     (string? (:value %))))
       (group-by :predicate)))

(defn- format-inspect [records ingest]
  (str/join
   "\n"
   (cons (str (count records) " record" (when (not= 1 (count records)) "s")
              (when (seq ingest)
                (str ", " (count ingest) " ingest diagnostic"
                     (when (not= 1 (count ingest)) "s")))
              ":")
         (for [r records]
           (let [floor (canon-floor r)
                 ents  (map #(subs (str (:kind %)) 1) (:entities r))]
             (str "\n" (:id r) "  (" (subs (str (:kind r)) 1) ")\n"
                  (str/join "\n"
                            (for [[p vs] (sort-by str floor)]
                              (str "    " p "  " (str/join " | " (map :value vs)))))
                  (when (seq ents)
                    (str "\n    entities: " (str/join " " ents)))))))))

(defn- do-inspect
  "The developer's verb: what the source parses to — the canonical floor and the
   minted WEMI/agent entities — to stdout. No target, no conversion."
  [{:strs [from record-id]} input]
  (let [{:keys [err from source opts]} (read-source from input record-id "inspect")]
    (if err
      {:exit 2 :out "" :err err}
      (guard #(let [{:keys [records ingest]} (convert/to-wemi from opts source)]
                {:exit 0 :out (format-inspect records ingest) :err ""})))))

(defn- dump-file?
  "Whether `f` looks like an authority dump, which `reconcile` never reads (a
   dump is indexed first, lot M6): a compressed file (`.gz`, `.bz2`, `.xz`,
   `.zst`, `.zip`), or JSON — a first character, after a byte-order mark and
   blank lines, that opens a JSON object with a key or an array. Only the
   first 4096 characters are read."
  [^java.io.File f]
  (or (boolean (re-find #"(?i)\.(gz|bz2|xz|zst|zip)$" (.getName f)))
      (with-open [r (io/reader f :encoding "UTF-8")]
        (let [buf  (char-array 4096)
              n    (.read r buf)
              head (str/triml (str/replace-first (String. buf 0 (max 0 n)) #"^\uFEFF" ""))]
          (boolean (re-find #"^(?:\{\s*\"|\[)" head))))))

(defn- authority-pool
  "The pool of `--authorities` file `path`: `{:entries :block :manifest}`, an
   authority index read with its blocking lookup, else an authority file
   read whole; `:manifest` what the run manifest says of it."
  [path]
  (let [f (io/file path)]
    (cond
      (index/index-file? f)
      (let [idx (index/read-index f)]
        {:entries (:entries idx) :block (index/block idx) :manifest (index/manifest-entry path)})

      (dump-file? f)
      (throw (ex-info (str path " is an authority dump, which is not read during a reconciliation:"
                           " build an index first with `palomar authorities index " path " --out <index>`")
                      {:path path}))

      :else
      (let [s (slurp f)]
        {:entries  (:entries (authorities/read-pool (authorities/sniff-format path s) s))
         :manifest (assoc (manifest/fingerprint f) :path path)}))))

(defn- do-reconcile
  "Cross-record agent reconciliation (ADR 0018, by authority id) to stdout. Real
   for INTERMARC, whose 100 $1 ISNI mints identified agents; other spokes report
   no authority-identified agents (honest, not an error). With `--authorities`,
   the names are matched against an authority file or index (lot M6: an index
   is looked up by its blocking keys, offline); with `--manifest`, the run
   manifest names the input and each authority file or index used."
  [{:strs [from record-id authorities manifest] :as flags} input]
  (let [{:keys [err from source opts]} (read-source from input record-id "reconcile")]
    (cond
      err {:exit 2 :out "" :err err}

      (and authorities (not (.isFile (io/file authorities))))
      {:exit 2 :out "" :err (str "authority file not found: " authorities)}

      :else
      (or
       (path-collision (select-keys flags ["manifest"]) input)
       (guard (fn [] (let [started (java.util.Date.)
                           {:keys [records]} (convert/to-wemi from opts source)
                           pool  (when authorities (authority-pool authorities))
                           names (distinct (map :name (mapcat agents/occurrences records)))
                           out   (str (reconcile/format-agent-reconciliation (reconcile/reconcile-agents records))
                                      (when pool
                                        (str "\n" (reconcile/format-proposals
                                                   (reconcile/propose-agent-links names (:entries pool)
                                                                                  (cond-> {:threshold 0.5}
                                                                                    (:block pool) (assoc :block (:block pool))))))))
                           m     (when manifest
                                   (doto (staging manifest "palomar-manifest-")
                                     (#(spit (:tmp %)
                                             (manifest/->edn
                                              (manifest/manifest {:from        from
                                                                  :to          nil
                                                                  :opts        opts
                                                                  :inputs      [{:path input :content (let [f (io/file input)] (when (.isFile f) f))}]
                                                                  :outputs     [{:path "-" :content out}]
                                                                  :authorities (when pool [(:manifest pool)])
                                                                  :started-at  started
                                                                  :finished-at (java.util.Date.)}))))))]
                       (try
                         (if-let [e (publish! nil nil m manifest)]
                           {:exit 2 :out "" :err e}
                           {:exit 0 :out out :err ""})
                         (finally (when-let [^java.io.File t (:tmp m)] (when (.exists t) (.delete t))))))))))))

(defn- do-authorities
  "`authorities index <dump> --out <index> [--built YYYY-MM-DD]`: build the
   authority index of a dump (lot M6, `palomar.authority-index/build!`), once,
   before any reconciliation reads it. The build date defaults to today
   (UTC); the index is otherwise a function of the dump alone."
  [{:strs [out built]} [sub dump]]
  (cond
    (not= "index" sub)
    {:exit 2 :out "" :err (str "authorities needs a subcommand: index <dump> --out <index>\n\n" usage)}

    (not (and dump out))
    {:exit 2 :out "" :err (str "authorities index needs <dump> --out <index>\n\n" usage)}

    (not (.isFile (io/file dump)))
    {:exit 2 :out "" :err (str "dump not found: " dump)}

    (same-file? dump out)
    {:exit 2 :out "" :err (str "authorities index: --out is the dump (" out "); choose another path")}

    :else
    (guard #(let [h (index/build! dump out {:built (or built (str (java.time.LocalDate/now java.time.ZoneOffset/UTC)))})]
              {:exit 0 :out ""
               :err  (str "wrote " out " — " (:entries h) " entr" (if (= 1 (:entries h)) "y" "ies") ", "
                          (:keys h) " blocking keys, built " (:built h) " from " (get-in h [:source :name])
                          " (sha256 " (get-in h [:source :sha256]) ", " (get-in h [:source :bytes]) " bytes)"
                          (when (seq (:skipped h)) (str "; skipped " (pr-str (:skipped h)))))}))))

(def ^:private repair-policies
  "apply-repairs --policy values → curation decision functions (ADR 0005). `flag`
   is the conservative default: route every proposal to human review, none
   auto-committed."
  {"flag"   curate/flag-all
   "accept" curate/accept-all
   "reject" curate/reject-all})

(defn- do-apply-repairs
  "Curate the `:proposed` claims a pipeline run emits (ADR 0005): run the source to
   WEMI, then accept / reject / flag every pending proposal per `--policy` (default
   `flag`). Writes the curation outcome to stdout; with `--to`, writes the curated
   records in that format to stdout instead (a rejected claim is never exported,
   `convert/export-records`) and the outcome to stderr. Honest when a record
   carries no proposals (e.g. an authority-linked Work): reports nothing to curate.
   Each decision is stamped on the record with the time of the run and, with
   `--by <curator>`, who made it (ADR 0028); `--to prov` writes that history."
  [{:strs [from record-id policy to by]} input]
  (let [{:keys [err from source opts]} (read-source from input record-id "apply-repairs")
        policy-label (or policy "flag")
        decide       (repair-policies policy-label)]
    (cond
      err          {:exit 2 :out "" :err err}
      (nil? decide) {:exit 2 :out ""
                     :err (str "unknown --policy " policy-label " (use flag|accept|reject)\n\n" usage)}
      (and to (not (contains? (convert/target-formats) (keyword to))))
      {:exit 2 :out "" :err (str "unknown --to " to "\n\n" usage)}
      :else
      (guard #(let [{:keys [records]} (convert/to-wemi from opts source)
                    result            (curate/curate records decide
                                                     (cond-> {:at (java.util.Date.)} by (assoc :by by)))
                    outcome           (curate/format-curation result policy-label)]
                (if to
                  {:exit 0 :out (:output (convert/export-records (keyword to) (:records result))) :err outcome}
                  {:exit 0 :out outcome :err ""}))))))

(defn- profile-names []
  (str/join " " (sort (map name (keys conformance/profiles)))))

(defn- do-conformance
  "Check the WEMI projection against an institutional `--profile` (WP-6). Writes the
   profile diagnostics report + a CONFORMANT/NON-CONFORMANT verdict to stderr and
   exits non-zero when the acceptance `--policy` is breached (mirrors validate)."
  [{:strs [from record-id profile policy]} input]
  (let [{:keys [err from source opts]} (read-source from input record-id "conformance")
        prof (some-> profile keyword conformance/profiles)]
    (cond
      err           {:exit 2 :out "" :err err}
      (not profile) {:exit 2 :out "" :err (str "conformance needs --profile <p> (have: "
                                               (profile-names) ")\n\n" usage)}
      (nil? prof)   {:exit 2 :out "" :err (str "unknown --profile " profile " (have: "
                                               (profile-names) ")\n\n" usage)}
      :else
      (guard #(let [pol (if policy (keyword policy) :errors-only)
                    {:keys [records diagnostics summary failed? label]}
                    (conformance/conformance {:from from :source source :opts opts
                                              :profile prof :policy pol})
                    verdict (str (if failed? "NON-CONFORMANT" "CONFORMANT")
                                 " — " label " — " records " record" (when (not= 1 records) "s") ", "
                                 (get-in summary [:by-severity :error]) " error(s), "
                                 (get-in summary [:by-severity :warning]) " warning(s) "
                                 "[policy " (name pol) "]")]
                {:exit (if failed? 1 0)
                 :out  ""
                 :err  (if (seq diagnostics) (str (dx/format-report diagnostics) "\n" verdict) verdict)})))))

(defn run
  "Pure CLI core: parse `args`, run the command, return `{:exit :out :err}`.
   Never prints, never exits."
  [args]
  (let [{:keys [command help? flags positional]} (parse-args args)]
    (cond
      (or help? (nil? command)) {:exit 0 :out "" :err usage}
      (= command "formats")     {:exit 0 :out ""
                                 :err (str (fmt-line "from: " (convert/source-formats)) "\n"
                                           (fmt-line "to:   " (convert/target-formats)))}
      (= command "ir-schema")   {:exit 0 :out (ir/schema-edn) :err ""}
      (= command "convert")     (if (get flags "stream")
                                  (do-convert-stream flags (first positional))
                                  (do-convert flags (first positional)))
      (= command "validate")    (do-validate flags (first positional))
      (= command "report")      (do-report flags (first positional))
      (= command "inspect")     (do-inspect flags (first positional))
      (= command "reconcile")   (do-reconcile flags (first positional))
      (= command "authorities") (do-authorities flags positional)
      (= command "apply-repairs") (do-apply-repairs flags (first positional))
      (= command "conformance") (do-conformance flags (first positional))
      :else                     {:exit 2 :out "" :err (str "unknown command: " command "\n\n" usage)})))

(defn -main [& args]
  (let [{:keys [exit out err]} (run (vec args))]
    (when (seq out) (print out) (flush))
    (when (seq err) (binding [*out* *err*] (print err) (flush)))
    (System/exit exit)))

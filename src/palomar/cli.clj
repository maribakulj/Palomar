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
     palomar reconcile     <input-file> --from <fmt> [--authorities <file|index>] [--engine historical|probabilistic] [--lanes name,identifier] [--model <file>] [--format text|edn] [--manifest <file>] [--record-id <id>]
     palomar reconcile explain  <input-file> --from <fmt> --authorities <file|index> --mention <id> [--lanes name,identifier] [--model <file>] [--format text|edn] [--manifest <file>] [--record-id <id>]
     palomar reconcile clusters <input-file> --from <fmt> --authorities <file|index> [--lanes name,identifier] [--model <file>] [--format text|edn] [--manifest <file>] [--record-id <id>]
     palomar authorities index <dump> --out <index> [--built YYYY-MM-DD]
     palomar apply-repairs <input-file> --from <fmt> [--policy <p>] [--to <fmt>] [--by <curator>] [--record-id <id>]
     palomar conformance   <input-file> --from <fmt> --profile <p> [--policy <p>] [--record-id <id>]
     palomar formats
     palomar ir-schema
     palomar --help

   - `report`        — the X→Y loss report alone (the auditor's view), no document.
   - `inspect`       — what the source parses to: the canonical floor + minted
                       WEMI/agent entities (the developer's view).
   - `reconcile`     — cross-record agent reconciliation by authority id (ADR 0018),
                       and against an authority pool by `--engine`: `historical`
                       (the default, the floor's names) or `probabilistic` (every
                       mention, a Fellegi-Sunter score, lot N2); `reconcile explain`
                       says why one mention was decided so, `reconcile clusters`
                       groups the mentions into persons.
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
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [palomar.conformance :as conformance]
            [palomar.convert :as convert]
            [palomar.curate :as curate]
            [palomar.diagnostics :as dx]
            [palomar.authority-index :as index]
            [palomar.ir :as ir]
            [palomar.loss-report :as lr]
            [palomar.manifest :as manifest]
            [palomar.paths :as paths]
            [palomar.reconciliation :as reconciliation]
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
    "  palomar reconcile     <input-file> --from <fmt> [--authorities <file.rdf|file.nt|index>] [--engine historical|probabilistic]"
    "                        [--lanes name,identifier] [--model <file.edn>] [--format text|edn] [--manifest <file>] [--record-id <id>]"
    "  palomar reconcile explain  <input-file> --from <fmt> --authorities <file|index> --mention <id> [--lanes name,identifier] [--model <file.edn>] [--format text|edn] [--manifest <file>] [--record-id <id>]"
    "  palomar reconcile clusters <input-file> --from <fmt> --authorities <file|index> [--lanes name,identifier] [--model <file.edn>] [--format text|edn] [--manifest <file>] [--record-id <id>]"
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
    "  --engine      reconcile: historical (default; the canonical floor's names, by token-set similarity) or"
    "                probabilistic (every mention, with its dates, roles and events, scored by a Fellegi-Sunter model;"
    "                a confidence only where the model's calibration proves it). explain and clusters are probabilistic"
    "  --model       reconcile --engine probabilistic, explain, clusters: a model parameter file (EDN, counts for every"
    "                feature, 0 < prior < 1, 0 < review <= propose < 1, no :calibration); default the shipped"
    "                palomar/models/agent-match.edn, fitted in-sample on the BnF M2 records and not calibrated;"
    "                palomar/models/agent-match-n3.edn names the one fitted on the hard benchmark's train split (N4)"
    "  --lanes       reconcile --engine probabilistic, explain, clusters: the candidate lanes, comma-separated: name (default;"
    "                the decisions as before) and identifier (the ids the mention states: INTERMARC $1, MARC21 $0 and $1,"
    "                UNIMARC $o, LIDO actorID, EAC-CPF entityId and otherRecordId, against the authorities' ids; never a $3;"
    "                a candidate no name agrees with is for review at most). The historical engine reads the name lane alone"
    "  --mention     reconcile explain: the mention id, as EDN, e.g. '[:bnf/cb123 \"700\" 0]'"
    "  --built       authorities index: the build date the index records (default: today, UTC); same dump and date, same bytes"
    "  --metadata-prefix  convert --from oai: the OAI metadata format (oai_dc, marcxml, mods; default oai_dc)"
    "  --out         write the output to a file instead of stdout (convert)"
    "  --stream      convert in bounded memory, streaming a large MARC dump or LIDO lidoWrap to --out (WP-7)"
    "  --fail-on-invalid convert: refuse the whole conversion (exit 1, nothing written) when a record is not valid"
    "                    (one that carries an error: today a LIDO record its XSD rejects; the wrap is not checked)"
    "  --with-proposals  convert: also state the proposed claims (uncurated inferences) as facts; by default a"
    "                plain RDF or record export holds facts only, and trig/nquads/prov keep each claim with its status"
    "  --manifest    convert: write the run manifest (versions, digests, options, loss) as EDN;"
    "                reconcile: the same, naming each authority file or index matched against, the engine and its model"
    "  --format      report output: text (default), edn or json (the raw loss-report map, ADR 0015);"
    "                reconcile, explain, clusters: text (default) or edn (the result map palomar.api returns)"
    ""
    "convert writes the document to stdout, the loss report to stderr;"
    "validate writes the diagnostics report to stderr and exits non-zero on failure;"
    "report writes the X→Y loss report to stdout (no document; --format edn|json for the raw map);"
    "inspect writes what the source parses to (the canonical floor + minted entities) to stdout;"
    "reconcile writes the cross-record agent reconciliation (by authority id, then the engine's decisions) to stdout;"
    "reconcile explain writes why one mention was decided so; reconcile clusters, the mentions grouped into persons;"
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

(defn- present
  "The roles of `rs` that name a path: a role not given names none."
  [rs]
  (filterv (comp string? second) rs))

(defn- path-collision
  "The refusal of a run of `command` one of whose written paths names the
   same file as another of its paths, read or written, or nil (lot O1).
   `roles` is what the command declares: `{:reads [[label path] ..] :writes
   [[label path] ..]}`, a role absent (stdin, stdout) having no path. Two
   paths are one file by `paths/same-file?`: the file system's identity
   when both exist (a symbolic or a hard link), else their canonical paths,
   in NFC, which follow the links of a directory and, for a dangling link,
   name the link. Every path is compared, `-` (a file name to this CLI, not
   a stream) and a device or a pipe included. Read paths are not compared
   with one another: reading one file twice replaces nothing. Each written
   path is compared with the read paths, then with the written paths
   declared before it, and the refusal names it first (`--manifest is
   --out`). Before L1, `--manifest` naming the input replaced the source;
   before O1, `reconcile --manifest` naming `--authorities` or `--model`
   replaced them, both with exit 0."
  [command {:keys [reads writes]}]
  (let [reads  (present reads)
        writes (present writes)]
    (first (for [[i [wl w]] (map-indexed vector writes)
                 [ol o]     (concat reads (subvec writes 0 i))
                 :when (paths/same-file? w o)]
             {:exit 2 :out ""
              :err  (str command ": " wl " is " ol " (" w (when (not= w o) (str " and " o " are one file"))
                         "); choose another path")}))))

(def ^:dynamic *checked*
  "What `run` found of a command's paths when it checked them (lot O1):
   `{:reads [{:label :identity}] :absent #{path}}`, the identity of each
   read file and the absolute written paths at which nothing was there.
   Each written path is checked against it again just before it is
   published. nil outside `run`."
  nil)

(defn- check-paths
  "Check the declared `roles` of a run of `command` before it reads or
   writes: `{:refusal r}`, or `{:checked c}` for `*checked*`. A read file
   that is not there is left for the command to report as not found, as
   before O1, and its paths are not compared: the command refuses the run
   before it writes. A written path that is a directory is refused."
  [command {:keys [reads writes]}]
  (let [reads  (present reads)
        writes (present writes)
        abs    (fn [path] (.toPath (.getAbsoluteFile (io/file path))))]
    (cond
      (some #(not (java.nio.file.Files/exists (abs (second %)) no-links)) reads)
      {:checked {:reads [] :absent #{}}}

      :else
      (if-let [r (or (path-collision command {:reads reads :writes writes})
                     (first (for [[wl w] writes :when (.isDirectory (io/file w))]
                              {:exit 2 :out "" :err (str command ": " wl " (" w ") is a directory; choose a file")})))]
        {:refusal r}
        {:checked {:reads  (vec (for [[l path] reads] {:label l :identity (paths/file-identity path)}))
                   :absent (set (for [[_ w] writes
                                      :let [p (abs w)]
                                      :when (not (java.nio.file.Files/exists p (into-array [java.nio.file.LinkOption/NOFOLLOW_LINKS])))]
                                  (str p)))}}))))

(defn- staging
  "Where to write what will be published at `path`: `{:tmp :dst :move?
   :path}`. A file that is or will be a regular file, followed through its
   links, is written beside it and moved over it, so a failed run leaves
   it as it was. A device or a pipe, or a file whose directory takes no new
   file, is written into, as before L1 (L1 review); so is a link whose
   target names no path (`/dev/stdout` into a pipe, O1 review)."
  [path prefix]
  (let [p        (.toPath (.getAbsoluteFile (io/file path)))
        exists?  (java.nio.file.Files/exists p no-links)
        regular? (and exists? (java.nio.file.Files/isRegularFile p no-links))
        dst      (if exists? (try (.toRealPath p no-links) (catch java.io.IOException _ p)) p)
        ;; a new file is written beside it or not at all: its directory
        ;; refuses it before anything is published (K6 review)
        beside   (cond
                   (not exists?) (java.io.File/createTempFile prefix ".part" (.toFile (.getParent dst)))
                   regular?      (try (java.io.File/createTempFile prefix ".part" (.toFile (.getParent dst)))
                                      (catch java.io.IOException _ nil)))]
    (if beside
      {:tmp beside :dst dst :move? true :path p}
      {:tmp (java.io.File/createTempFile prefix ".part") :dst dst :move? false :path p})))

(defn- changed-under-run
  "What the staged `s` would now be published over that the check did not
   see, or nil: a file the run reads (by its real path or its file key, a
   hard link too), or a symbolic link where nothing was (O1 review)."
  [{:keys [^java.nio.file.Path path ^java.nio.file.Path dst]}]
  (when-let [{:keys [reads absent]} *checked*]
    (let [now (keep paths/file-identity [(str path) (str dst)])]
      (or (some (fn [{:keys [label identity]}]
                  (when (some #(paths/same-identity? identity %) now) (str "names the same file as " label)))
                reads)
          (when (and (contains? absent (str path)) (java.nio.file.Files/isSymbolicLink path))
            "is a symbolic link")))))

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
   that fails after its output is reported, never a success (L1). Both are
   checked again first: a path that came to name a file the run reads, or
   to be a link where nothing was, publishes nothing (O1 review)."
  [o out m mpath]
  (if-let [[role path why] (first (for [[s role path] [[o "--out" out] [m "--manifest" mpath]]
                                        :when s
                                        :let [why (changed-under-run s)]
                                        :when why]
                                    [role path why]))]
    (str "error: " role " (" path ") " why ", which it was not when the paths were checked; nothing was published")
    (cond
      (and o (not (publish-one! o)))
      (str "error: could not move the output to " out "; nothing was published")

      (and m (not (publish-one! m)))
      (str "error: " (when o (str "the output was published to " out ", but "))
           "the manifest could not be written to " mpath "; no manifest describes this run"
           " (a file already there describes an earlier one)"))))

(defn- do-convert [{:strs [from to record-id out with-proposals] :as flags} input]
  (cond
    (not (and from to input))
    {:exit 2 :out "" :err (str "convert needs <input-file> --from <fmt> --to <fmt>\n\n" usage)}

    (not (.exists (java.io.File. ^String input)))
    {:exit 2 :out "" :err (str "input file not found: " input)}

    :else
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
          (doseq [^java.io.File t @tmps :when (.exists t)] (.delete t)))))))

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

    (not (some #{(keyword from)} (convert/source-formats)))
    {:err (str "unknown format: --from " from "; one of " (str/join " " (sort (map name (convert/source-formats)))))}

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
    ;; manifest name either (L1). `run` refuses such paths before this (O1).
    :else
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
          (doseq [{t :tmp} [@o @m] :when (and t (.exists ^java.io.File t))] (.delete ^java.io.File t)))))))

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

(def ^:private reconcile-subcommands #{"explain" "clusters"})

(defn- reconcile-args
  "The subcommand of `reconcile` (or nil) and its input file."
  [positional]
  (if (reconcile-subcommands (first positional))
    [(first positional) (second positional)]
    [nil (first positional)]))

(defn- reconcile-output
  "What `reconcile [sub]` writes for `result`: its text, or the result as
   EDN (`--format edn`)."
  [sub fmt result]
  (if (= "edn" fmt)
    (str (binding [*print-namespace-maps* false] (pr-str result)) "\n")
    (case sub
      nil        (reconciliation/format-reconciliation result)
      "explain"  (reconciliation/format-explanation result)
      "clusters" (reconciliation/format-clusters result))))

(defn- parse-lanes
  "The lanes `--lanes` names, as keywords, or nil when one is no lane."
  [s]
  (let [ls (map keyword (str/split (str s) #"," -1))]
    (when (and (seq ls) (every? reconciliation/lane-names ls)) (vec ls))))

(defn- reconcile-problem
  "Why the `reconcile` flags cannot run, or nil."
  [sub {:strs [engine model format mention authorities lanes]}]
  (let [eng (some-> engine keyword)
        ls  (when lanes (parse-lanes lanes))]
    (cond
      (and engine (not (reconciliation/engines eng)))
      (str "--engine must be historical or probabilistic, got " (pr-str engine))

      (and lanes (nil? ls))
      (str "--lanes is a comma-separated list of name and identifier, got " (pr-str lanes))

      (and lanes (not sub) (not= :probabilistic eng) (not= #{:name} (set ls)))
      (str "--lanes " lanes " is the probabilistic engine's: the historical engine reads the name lane alone;"
           " give --engine probabilistic")

      (and format (not (#{"text" "edn"} format)))
      (str "reconcile --format must be text or edn, got " (pr-str format))

      (and sub (= :historical eng))
      (str "reconcile " sub " is the probabilistic engine's: the historical engine reads names, not mentions")

      (and model (not sub) (not= :probabilistic eng))
      "--model is the probabilistic engine's: give --engine probabilistic"

      (and model (not (.isFile (io/file model))))
      (str "model file not found: " model)

      (and sub (not authorities))
      (str "reconcile " sub " needs --authorities <file|index>\n\n" usage)

      (and mention (not= "explain" sub))
      "--mention is reconcile explain's: palomar reconcile explain <input-file> --mention <id> ..."

      (and (= "explain" sub) (not (vector? (try (edn/read-string mention) (catch Exception _ nil)))))
      (str "reconcile explain needs --mention <id>, a mention id as EDN (e.g. '[:bnf/cb123 \"700\" 0]'),"
           " as `reconcile --engine probabilistic` lists them"))))

(defn- do-reconcile
  "Cross-record agent reconciliation (ADR 0018) to stdout. By authority id
   (ISNI) always; with `--authorities`, the agents are matched against an
   authority file or index (lot M6: an index is looked up by its blocking
   keys, offline) by `--engine`: `historical` (the default, the canonical
   floor's names by token-set similarity, its output unchanged) or
   `probabilistic` (every mention, scored by a Fellegi-Sunter model, lot
   N2). `--lanes` (lot O6) names the probabilistic engine's candidate
   lanes (`palomar.candidates`); the historical engine refuses any but
   `name`. `reconcile explain --mention <id>` says why one mention was decided
   as it was, `reconcile clusters` groups the mentions into persons; both
   are the probabilistic engine's. The work is `palomar.reconciliation`'s,
   as `palomar.api`'s is. With `--manifest`, the run manifest names the
   input, each authority file or index used, the engine and its model."
  [{:strs [from record-id authorities manifest engine model format mention lanes] :as flags} positional]
  (let [[sub input] (reconcile-args positional)
        {:keys [err from source opts]} (read-source from input record-id (str "reconcile" (when sub (str " " sub))))
        problem (when-not err (reconcile-problem sub flags))
        loaded  (when (and model (not err) (not problem))
                  (try (reconciliation/read-model model)
                       (catch Exception e {:error (str "--model " model ": " (.getMessage e))})))]
    (cond
      err {:exit 2 :out "" :err err}

      problem {:exit 2 :out "" :err problem}

      (:error loaded) {:exit 2 :out "" :err (:error loaded)}

      (:calibration (:model loaded))
      {:exit 2 :out "" :err (str "--model " model ": a model file may not carry a :calibration report yet:"
                                 " a calibration is computed on the model it proves, not given with it")}

      (and authorities (not (.isFile (io/file authorities))))
      {:exit 2 :out "" :err (str "authority file not found: " authorities)}

      :else
      (guard (fn [] (let [started (java.util.Date.)
                          {:keys [records]} (convert/to-wemi from opts source)
                          pool   (when authorities (reconciliation/read-authorities authorities))
                          ropts  (cond-> {:engine (if engine (keyword engine) reconciliation/default-engine)}
                                   loaded (assoc :model loaded)
                                   lanes  (assoc :lanes (parse-lanes lanes)))
                          result (case sub
                                   nil        (reconciliation/reconcile records pool ropts)
                                   "explain"  (reconciliation/explain records pool (edn/read-string mention) ropts)
                                   "clusters" (reconciliation/clusters records pool ropts))
                          out    (reconcile-output sub format result)
                          m      (when manifest
                                   (doto (staging manifest "palomar-manifest-")
                                     (#(spit (:tmp %)
                                             (manifest/->edn
                                              (manifest/manifest {:from        from
                                                                  :to          nil
                                                                  :opts        opts
                                                                  :inputs      [{:path input :content (let [f (io/file input)] (when (.isFile f) f))}]
                                                                  :outputs     [{:path "-" :content out}]
                                                                  :authorities (when pool [(:manifest pool)])
                                                                  :reconcile   (reconciliation/manifest-entry result)
                                                                  :started-at  started
                                                                  :finished-at (java.util.Date.)}))))))]
                      (try
                        (if-let [e (publish! nil nil m manifest)]
                          {:exit 2 :out "" :err e}
                          {:exit 0 :out out :err ""})
                        (finally (when-let [^java.io.File t (:tmp m)] (when (.exists t) (.delete t)))))))))))

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

(defn- no-files
  "The roles of a command that reads and writes no file."
  [_ _]
  {:reads [] :writes []})

(defn- input-only
  "The roles of a command that reads its input file and writes only to
   stdout and stderr."
  [_ positional]
  {:reads [["the input file" (first positional)]] :writes []})

(def commands
  "The commands of the CLI, by name: `:run`, called with the flags and the
   positionals, and `:roles`, what files it reads and writes, as `(roles
   flags positional)` → `{:label :reads [[label path] ..] :writes [[label
   path] ..]}` (lot O1), `:label` naming the command as typed in a
   refusal. `run` checks the roles of every command before it runs
   it, so no command reads or writes before its paths are known to be
   distinct, and a command that declares no roles is not run. A command
   that reads or writes another file declares it here."
  {"formats"       {:roles no-files
                    :run   (fn [_ _] {:exit 0 :out ""
                                      :err (str (fmt-line "from: " (convert/source-formats)) "\n"
                                                (fmt-line "to:   " (convert/target-formats)))})}
   "ir-schema"     {:roles no-files
                    :run   (fn [_ _] {:exit 0 :out (ir/schema-edn) :err ""})}
   "convert"       {:roles (fn [{:strs [out manifest]} positional]
                             {:reads  [["the input file" (first positional)]]
                              :writes [["--out" out] ["--manifest" manifest]]})
                    :run   (fn [flags positional]
                             (if (get flags "stream")
                               (do-convert-stream flags (first positional))
                               (do-convert flags (first positional))))}
   "validate"      {:roles input-only :run (fn [flags positional] (do-validate flags (first positional)))}
   "report"        {:roles input-only :run (fn [flags positional] (do-report flags (first positional)))}
   "inspect"       {:roles input-only :run (fn [flags positional] (do-inspect flags (first positional)))}
   "reconcile"     {:roles (fn [{:strs [authorities model manifest]} positional]
                             (let [[sub input] (reconcile-args positional)]
                               {:label  (str "reconcile" (when sub (str " " sub)))
                                :reads  [["the input file" input] ["--authorities" authorities] ["--model" model]]
                                :writes [["--manifest" manifest]]}))
                    :run   do-reconcile}
   "authorities"   {:roles (fn [{:strs [out]} [sub dump]]
                             (if (= "index" sub)
                               {:label "authorities index" :reads [["the dump" dump]] :writes [["--out" out]]}
                               {:reads [] :writes []}))
                    :run   do-authorities}
   "apply-repairs" {:roles input-only :run (fn [flags positional] (do-apply-repairs flags (first positional)))}
   "conformance"   {:roles input-only :run (fn [flags positional] (do-conformance flags (first positional)))}})

(defn run
  "Pure CLI core: parse `args`, run the command, return `{:exit :out :err}`.
   Never prints, never exits. Before the command reads or writes a file,
   its declared roles are checked: a written path that names the same file
   as another of its paths is refused, exit 2 (lot O1)."
  [args]
  (let [{:keys [command help? flags positional]} (parse-args args)
        {:keys [roles] :as cmd} (get commands command)]
    (cond
      (or help? (nil? command)) {:exit 0 :out "" :err usage}
      (nil? cmd)                {:exit 2 :out "" :err (str "unknown command: " command "\n\n" usage)}
      (not (fn? roles))         {:exit 2 :out "" :err (str command ": declares no file roles; not run")}
      :else
      (let [{:keys [refusal checked]}
            (try (let [r (roles flags positional)] (check-paths (or (:label r) command) r))
                 (catch Exception e
                   {:refusal {:exit 2 :out "" :err (str command ": its paths could not be compared: " (.getMessage e))}}))]
        (or refusal
            (binding [*checked* checked] ((:run cmd) flags positional)))))))

(defn -main [& args]
  (let [{:keys [exit out err]} (run (vec args))]
    (when (seq out) (print out) (flush))
    (when (seq err) (binding [*out* *err*] (print err) (flush)))
    (System/exit exit)))

(ns palomar.authority-index
  "Authority snapshots (lot M6, ADR 0038 amendment): a large authority dump,
   prepared once into a local index file, then read offline and
   deterministically by reconciliation.

   **Building** (`build!`) is an explicit step — `palomar authorities index
   <dump> --out <index>` — never part of a conversion. The dump (lobid GND
   JSON lines, `palomar.plugins.gnd`, optionally gzipped) is read one line at
   a time; the index lines are sorted by an external merge sort: chunks of
   `:chunk-lines` lines sorted in memory, then merged `:fan-in` runs at a
   time, in as many passes as it takes. Memory is bounded by one chunk, the
   `:fan-in` open readers and the ids of the largest posting, not by the
   dump; disk holds the runs, about the size of the index. A dump from which
   no entry is read, or most of whose lines are no JSON object, is refused.
   The result is written beside its path and moved over it only when
   complete.

   **The file** is UTF-8 EDN, one form per line:
   - line 1, the header: `{:palomar/authority-index 1 :format :blocking :built
     :source {:name :sha256 :bytes} :entries :keys :skipped}` — the dump's
     file name and fingerprint, the build date (given by the caller, never
     read from a clock), what was skipped;
   - then, in sorted order, one `[:entry id {…}]` line per entry (the entry
     without its `:id`, map keys sorted), then one `[:key k [id…]]` line per
     blocking key, its ids sorted.
   The *body* (every line but the header) is a function of the dump's
   records alone: the same whatever their order, the line endings, a
   byte-order mark, gzip, the chunking or the default locale. The header
   names the dump file as given (its name and fingerprint) and the build
   date: the same file and date, a byte-identical index.

   **Blocking keys** (`blocking-keys`, scheme `blocking-scheme`) are chosen so
   that a lookup returns every entry the matcher could score, no fewer: a
   candidate is compared only when its name level is above `:none`
   (`palomar.agent-match/name-level`), which needs either a shared normalised
   token (every level but the surname typo; a Jaccard above 0 too) or two
   surnames of at least four letters one edit apart (`:surname-typo`). So the
   keys of a name are `t:` + each of its tokens, and `s:` + its surname (of at
   least four letters) and each string made by deleting one of its letters:
   two strings one substitution, insertion, deletion or adjacent transposition
   apart always share such a deletion (the symmetric-deletion scheme). A key
   on the surname's first letters would miss a typo there and keep every
   namesake of a common initial.

   **Reading** (`read-index`) loads the entries in index order and the
   postings; `block` is the lookup `reconcile/agent-candidates` takes as its
   `:block` option, returning a name's candidates in index order, so the
   proposals are those of a full scan of the same entries. Nothing here
   opens a network connection: files are opened as files."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [palomar.agent-match :as fs]
            [palomar.manifest :as manifest]
            [palomar.plugins.gnd :as gnd]))

(def version
  "The version of the index format."
  1)

(def blocking-scheme
  "The blocking keys an index was built with: a reader refuses another. 2:
   names are case-folded in the root locale, never the JVM's default
   (`text/norm`)."
  "tokens+surname-deletions/2")

(def ^:private magic "{:palomar/authority-index ")

;; ---------------------------------------------------------------------------
;; Blocking keys
;; ---------------------------------------------------------------------------

(defn- deletions
  "`s` and every string made by deleting one of its characters."
  [^String s]
  (cons s (for [i (range (count s))] (str (subs s 0 i) (subs s (inc i))))))

(defn blocking-keys
  "The blocking keys of `names` (see the ns doc), sorted."
  [names]
  (vec (sort (distinct (mapcat (fn [n]
                                 (concat (map #(str "t:" %) (fs/tokens n))
                                         (let [s (fs/surname n)]
                                           (when (<= 4 (count s)) (map #(str "s:" %) (deletions s))))))
                               (remove str/blank? names))))))

(defn entry-keys
  "The blocking keys of an entry: those of its heading and of its variants."
  [{:keys [label variants]}]
  (blocking-keys (cons label variants)))

;; ---------------------------------------------------------------------------
;; Lines
;; ---------------------------------------------------------------------------

(defn- canonical
  "`x` with every map sorted by key, every sequence a vector."
  [x]
  (cond
    (map? x)        (into (sorted-map-by #(compare (str %1) (str %2))) (map (fn [[k v]] [k (canonical v)])) x)
    (sequential? x) (mapv canonical x)
    :else           x))

(defn- edn-line ^String [x]
  (binding [*print-namespace-maps* false *print-length* nil *print-level* nil *print-meta* false]
    (pr-str (canonical x))))

(defn- entry-lines
  "The sort lines of one entry: its `[:entry …]` line and a `[:key k id]`
   line per blocking key."
  [e]
  (cons (edn-line [:entry (:id e) (dissoc e :id)])
        (for [k (entry-keys e)] (edn-line [:key k (:id e)]))))

(defn- write-lines! [f lines]
  (with-open [w (io/writer f :encoding "UTF-8")]
    (doseq [^String l lines] (.write w l) (.write w "\n"))))

(defn- sorted-runs
  "`lines` cut into chunks of `n`, each sorted and written to a temp file in
   `dir`: the files."
  [lines n dir]
  (vec (for [chunk (partition-all n lines)]
         (let [f (java.io.File/createTempFile "palomar-index-run-" ".edn" dir)]
           (write-lines! f (sort chunk))
           f))))

(defn- open-run
  "A reader of sorted run file `f`."
  ^java.io.BufferedReader [f]
  (io/reader f :encoding "UTF-8"))

(defn- merge-runs!
  "Call `f` on every line of the sorted `runs`, in sorted order."
  [runs f]
  (let [readers (mapv open-run runs)]
    (try
      (let [q (java.util.PriorityQueue. (max 1 (count runs))
                                        (comparator (fn [[^String a _] [^String b _]] (neg? (.compareTo a b)))))]
        (doseq [[i ^java.io.BufferedReader r] (map-indexed vector readers)]
          (when-let [l (.readLine r)] (.add q [l i])))
        (loop []
          (when-let [[l i] (.poll q)]
            (f l)
            (when-let [nl (.readLine ^java.io.BufferedReader (readers i))] (.add q [nl i]))
            (recur))))
      (finally (doseq [^java.io.Closeable r readers] (.close r))))))

(defn- merge-to-run!
  "Merge the sorted `runs` into one new sorted run file in `dir`, deleting
   them: the new file."
  [runs dir]
  (let [f (java.io.File/createTempFile "palomar-index-run-" ".edn" dir)]
    (with-open [w (io/writer f :encoding "UTF-8")]
      (merge-runs! runs (fn [^String l] (.write w l) (.write w "\n"))))
    (doseq [^java.io.File r runs] (.delete r))
    f))

(defn- merge-down!
  "`runs` merged, `fan-in` at a time, pass after pass, until at most `fan-in`
   are left: never more than `fan-in` files open at once. `track!` is given
   every new file."
  [runs fan-in dir track!]
  (loop [runs runs]
    (if (<= (count runs) fan-in)
      runs
      (recur (mapv #(doto (merge-to-run! % dir) track!) (partition-all fan-in runs))))))

;; ---------------------------------------------------------------------------
;; Building
;; ---------------------------------------------------------------------------

(defn- dump-reader
  "A UTF-8 reader of dump file `f`, gunzipped when its name ends in `.gz`."
  ^java.io.BufferedReader [^java.io.File f]
  (let [in (io/input-stream f)]
    (io/reader (if (str/ends-with? (.getName f) ".gz") (java.util.zip.GZIPInputStream. in) in) :encoding "UTF-8")))

(defn- valid-date? [s]
  (boolean (and (string? s) (re-matches #"\d{4}-\d{2}-\d{2}" s)
                (try (java.time.LocalDate/parse s) (catch java.time.format.DateTimeParseException _ nil)))))

(defn build!
  "Build the index of dump file `dump` (lobid GND JSON lines, gzipped when
   its name ends in `.gz`) at `out`. `opts`: `:built`, the build date
   (`YYYY-MM-DD`, required: no clock is read), `:chunk-lines` (default
   100 000), the lines sorted in memory at once, and `:fan-in` (default 64,
   at least 2), the sorted runs merged at once: more runs are merged in
   several passes. Memory holds one chunk of lines while it is sorted, then
   `:fan-in` open readers and one line from each, and the ids of the
   largest posting (one blocking key's entries) while it is written.
   Refuses a dump from which no entry is read, a dump more than half of
   whose lines are no JSON object (the counts are said; an expanded
   JSON-LD array, the DNB's bulk format, is named as such), and a dump
   holding one id twice; nothing is written then. Returns the header."
  [dump out {:keys [built chunk-lines fan-in] :or {chunk-lines 100000 fan-in 64}}]
  (when-not (valid-date? built)
    (throw (ex-info "authority index: :built must be a date YYYY-MM-DD" {:built built})))
  (when-not (and (integer? fan-in) (<= 2 fan-in))
    (throw (ex-info "authority index: :fan-in must be at least 2" {:fan-in fan-in})))
  (let [dump    (io/file dump)
        out     (.getAbsoluteFile (io/file out))
        dir     (.getParentFile out)
        temps   (atom [])
        tmp     (fn [] (let [f (java.io.File/createTempFile "palomar-index-" ".part" dir)] (swap! temps conj f) f))]
    (try
      (let [skipped (atom (sorted-map))
            read    (atom {:lines 0 :entries 0 :first nil})
            runs    (with-open [r (dump-reader dump)]
                      (sorted-runs (mapcat (fn [line]
                                             (let [{:keys [entry] why :skipped} (gnd/read-line-entry line)]
                                               (when-not (= :blank why)
                                                 (swap! read (fn [m] (cond-> (update m :lines inc)
                                                                       (nil? (:first m)) (assoc :first (str/trim (str/replace-first line #"^\uFEFF" "")))
                                                                       entry             (update :entries inc)))))
                                               (when (and why (not= :blank why)) (swap! skipped update why (fnil inc 0)))
                                               (when entry (entry-lines entry))))
                                           (line-seq r))
                                   chunk-lines dir))
            _       (swap! temps into runs)
            {:keys [lines entries]} @read
            bad     (get @skipped :not-json 0)
            _       (when (> (* 2 bad) lines)
                      (throw (ex-info (str "authority index: " bad " of " lines " lines of " (.getName dump)
                                           " are no JSON object"
                                           (when (some-> (:first @read) (str/starts-with? "["))
                                             " (an expanded JSON-LD array, as the DNB's bulk files are, is not read: use lobid GND JSON lines)")
                                           "; nothing written")
                                      {:lines lines :skipped @skipped})))
            _       (when (zero? entries)
                      (throw (ex-info (str "authority index: no entry read from " (.getName dump) " (" lines " lines, skipped "
                                           (pr-str @skipped) "); nothing written")
                                      {:lines lines :skipped @skipped})))
            runs    (merge-down! runs fan-in dir #(swap! temps conj %))
            body    (tmp)
            counts  (atom {:entries 0 :keys 0})
            last-id (atom nil)
            pending (atom nil)              ; [key [ids]]
            flush!  (fn [^java.io.Writer w]
                      (when-let [[k ids] @pending]
                        (.write w (edn-line [:key k ids])) (.write w "\n")
                        (swap! counts update :keys inc)
                        (reset! pending nil)))]
        (with-open [w (io/writer body :encoding "UTF-8")]
          (merge-runs! runs
                       (fn [^String l]
                         (if (str/starts-with? l "[:entry ")
                           (let [[_ id] (edn/read-string l)]
                             (when (= id @last-id)
                               (throw (ex-info (str "authority index: the dump holds " id " twice") {:id id})))
                             (reset! last-id id)
                             (swap! counts update :entries inc)
                             (.write w l) (.write w "\n"))
                           (let [[_ k id] (edn/read-string l)]
                             (if (= k (first @pending))
                               (when-not (= id (peek (second @pending))) (swap! pending update 1 conj id))
                               (do (flush! w) (reset! pending [k [id]])))))))
          (flush! w))
        (let [header {:palomar/authority-index version
                      :format                  :lobid-gnd-jsonl
                      :blocking                blocking-scheme
                      :built                   built
                      :source                  (assoc (manifest/fingerprint dump) :name (.getName dump))
                      :entries                 (:entries @counts)
                      :keys                    (:keys @counts)
                      :skipped                 @skipped}
              final  (tmp)]
          (with-open [o (io/output-stream final)]
            ;; the version first, so that a reader knows the file by its first characters
            (.write o (.getBytes (str magic version ", " (subs (edn-line (dissoc header :palomar/authority-index)) 1) "\n") "UTF-8"))
            (io/copy body o))
          (java.nio.file.Files/move (.toPath final) (.toPath out)
                                    (into-array java.nio.file.CopyOption [java.nio.file.StandardCopyOption/REPLACE_EXISTING]))
          header))
      (finally
        (doseq [^java.io.File f @temps :when (.exists f)] (.delete f))))))

;; ---------------------------------------------------------------------------
;; Reading
;; ---------------------------------------------------------------------------

(defn index-file?
  "Whether `path` is an authority index (by its first characters)."
  [path]
  (let [f (io/file path)]
    (boolean
     (and (.isFile f)
          (with-open [r (io/reader f :encoding "UTF-8")]
            (let [buf (char-array (count magic))
                  n   (.read r buf)]
              (= magic (String. buf 0 (max 0 n)))))))))

(defn read-header
  "The header of index file `path`; refuses a file that is no index of this
   version and blocking scheme."
  [path]
  (let [f (io/file path)
        h (when (index-file? f)
            (with-open [r (io/reader f :encoding "UTF-8")] (edn/read-string (.readLine ^java.io.BufferedReader r))))]
    (when-not (and (map? h) (= version (:palomar/authority-index h)) (= blocking-scheme (:blocking h)))
      (throw (ex-info (str "not a Palomar authority index of version " version ": " path)
                      {:path (str path) :header (when (map? h) (select-keys h [:palomar/authority-index :blocking]))})))
    h))

(defn read-index
  "The index file `path`: `{:header :entries [..] :postings {key [ordinal..]}}`,
   the entries in index order, each posting the ordinals of its entries."
  [path]
  (let [header (read-header path)]
    (with-open [r (io/reader (io/file path) :encoding "UTF-8")]
      (let [[_ & body] (line-seq r)
            forms   (map edn/read-string body)
            entries (vec (for [[kind id e] forms :when (= :entry kind)] (assoc e :id id)))
            ord     (into {} (map-indexed (fn [i e] [(:id e) i])) entries)
            posts   (into {} (for [[kind k ids] forms :when (= :key kind)] [k (mapv ord ids)]))]
        {:header header :entries entries :postings posts}))))

(defn block
  "The candidate lookup of index `idx` (`read-index`), as
   `reconcile/agent-candidates`' `:block`: a name → the entries sharing one of
   its blocking keys, in index order."
  [{:keys [entries postings]}]
  (fn [name]
    (mapv entries (into (sorted-set) (mapcat #(get postings %) (blocking-keys [name]))))))

(defn manifest-entry
  "What a run manifest says of authority index `path` (ADR 0030 amendment
   M6): its fingerprint, and from its header the dump it was built from, the
   build date, the format, the blocking scheme and the entry count."
  [path]
  (assoc (manifest/fingerprint (io/file path))
         :path (str path)
         :index (select-keys (read-header path) [:built :source :format :blocking :entries])))

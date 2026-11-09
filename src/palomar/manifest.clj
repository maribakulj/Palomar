(ns palomar.manifest
  "Run manifest: what a conversion was, so a result can be reproduced and two
   results that differ can be explained (plan step 6, ADR 0030).

   A manifest is a plain EDN map naming everything a conversion's output
   depends on: Palomar's version and commit, a digest of every Palomar source
   file (`code-fingerprint`), the JVM and Clojure versions, the resolved
   dependencies, the spoke and target, each plugin
   with a digest of its mapping and rules (they are data, so a changed mapping
   changes its digest), the ontology files, the SHA-256 of every input and
   output, the options, the compiled rule ids, the loss summary, and the times
   the caller supplies. `diff` names what changed between two manifests.

   A reconciliation's manifest (lot M6) also names each authority file it
   matched against under `:authorities`: its path and fingerprint and, for an
   authority index (`palomar.authority-index/manifest-entry`), the dump it
   was built from (its name and fingerprint, as the index header records
   them), the build date, the format, the blocking scheme and the entry
   count. A changed index is a changed `:authorities`.

   The engine stays pure: times, paths and the external block (e.g. the model
   and parameters an application used upstream) come from the caller; the CLI's
   `convert --manifest <file>` fills them."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [palomar.model :as model]
            [palomar.ontology :as ontology]
            [palomar.plugins.canonical :as canonical]
            [palomar.spokes :as spokes]))

(def manifest-version
  "The version of the manifest's own shape: 2 digests the engine's source tree
   (`:files`) and names the runtime and the dependencies."
  2)

(defn build-info
  "Palomar's version and commit, from the `palomar/build.edn` resource a release
   build writes (`{:version :commit}`), else `{:version \"dev\"}`."
  []
  (if-let [r (io/resource "palomar/build.edn")]
    (read-string (slurp r))
    {:version "dev"}))

(defn sources-digest
  "SHA-256 over `sources`, `[[path content]…]`, in path order: one digest for a
   set of source files."
  [sources]
  (model/sha256-hex (apply str (for [[p c] (sort-by first sources)] (str (count p) ":" p (count c) ":" c))) 64))

(defn- source-file? [path]
  (and (re-find #"\.(clj|cljc|edn)$" path) (not= "palomar/build.edn" path)))

(defn- engine-sources
  "`[[path content]…]` for every Palomar source file in the classpath root
   that holds the engine (`palomar/model.clj`): a source directory or a jar."
  []
  (let [url (io/resource "palomar/model.clj")]
    (case (.getProtocol ^java.net.URL url)
      "file" (let [root (.getParentFile (.getParentFile (io/file url)))
                   base (.toPath ^java.io.File root)]
               (for [f (file-seq (io/file root "palomar"))
                     :let [path (str/replace (str (.relativize base (.toPath ^java.io.File f))) java.io.File/separator "/")]
                     :when (and (.isFile ^java.io.File f) (source-file? path))]
                 [path (slurp f)]))
      "jar"  (let [conn (.openConnection url)]
               (with-open [jar (.getJarFile ^java.net.JarURLConnection conn)]
                 (doall (for [e (enumeration-seq (.entries jar))
                              :let [path (.getName ^java.util.jar.JarEntry e)]
                              :when (and (str/starts-with? path "palomar/") (source-file? path))]
                          [path (slurp (.getInputStream jar e))]))))
      (throw (ex-info "manifest: cannot read the engine's sources" {:url (str url)})))))

(defn code-fingerprint
  "The code that can run (post-audit test 09, post-audit 2 D6): the SHA-256 of
   every Palomar source file of the engine's classpath root, with their count.
   It digests the source tree, not the namespaces loaded so far, so loading
   one more namespace does not change it, and a changed exporter, mapping or
   engine does, which a version string alone (\"dev\") does not."
  []
  (let [srcs (engine-sources)]
    {:sha256 (sources-digest srcs) :files (count srcs)}))

(defn runtime-info
  "The runtime a conversion ran on: the JVM version and vendor, and Clojure's."
  []
  {:java        (System/getProperty "java.version")
   :java-vendor (System/getProperty "java.vendor")
   :clojure     (clojure-version)})

(defn dependencies
  "The resolved libraries on the classpath, `{lib coordinate}` (a Maven version,
   a git sha or a local root), read from the basis the Clojure CLI started the
   JVM with (`clojure.basis`); nil when there is none (an uberjar)."
  []
  (when-let [b (System/getProperty "clojure.basis")]
    (let [f (io/file b)]
      (when (.exists f)
        (into (sorted-map)
              (for [[lib coord] (:libs (edn/read-string {:default (fn [_ v] v)} (slurp f)))]
                [lib (select-keys coord [:mvn/version :git/sha :git/tag :local/root])]))))))

(defn fingerprint
  "`{:sha256 hex :bytes n}` of `content` (a String, a byte array or a File)."
  [content]
  (if (instance? java.io.File content)
    ;; a file is read as a stream, in constant memory: a streamed dump's
    ;; output may be larger than the heap (post-audit 8, K6)
    (let [md (java.security.MessageDigest/getInstance "SHA-256")
          n  (with-open [in (java.security.DigestInputStream. (io/input-stream content) md)]
               (let [buf (byte-array 65536)]
                 (loop [n 0] (let [k (.read in buf)] (if (neg? k) n (recur (+ n k)))))))]
      {:sha256 (.formatHex (java.util.HexFormat/of) (.digest md)) :bytes n})
    (let [bytes (if (bytes? content) content (.getBytes (str content) "UTF-8"))]
      {:sha256 (model/sha256-hex bytes 64) :bytes (alength ^bytes bytes)})))

(def ^:private vocab-anchor
  "A vocabulary every build carries: where the vocabularies are found."
  "palomar/vocab/lido/event-types.edn")

(defn- vocab-paths
  "The classpath paths of the vocabularies, found beside the anchor: in a
   source tree, or in the jar that carries it, under whatever prefix the
   jar's classpath root has (`WEB-INF/classes/`, `BOOT-INF/classes/`)."
  [^java.net.URL url]
  (case (.getProtocol url)
    "file" (let [root (.getParentFile (.getParentFile (io/file url)))
                 base (.toPath (.getParentFile (.getParentFile root)))]
             (for [f (file-seq root) :when (.isFile ^java.io.File f)]
               (str/replace (str (.relativize base (.toPath ^java.io.File f))) java.io.File/separator "/")))
    "jar"  (let [conn   (doto ^java.net.JarURLConnection (.openConnection url) (.setUseCaches false))
                 entry  (.getEntryName conn)
                 prefix (subs entry 0 (- (count entry) (count vocab-anchor)))]
             (with-open [jar (.getJarFile conn)]
               (doall (for [e (enumeration-seq (.entries jar))
                            :let [path (.getName ^java.util.jar.JarEntry e)]
                            :when (and (str/starts-with? path (str prefix "palomar/vocab/"))
                                       (not (.isDirectory ^java.util.jar.JarEntry e)))]
                        (subs path (count prefix))))))
    (throw (ex-info "manifest: cannot read the vocabularies" {:url (str url)}))))

(defn- vocabularies
  "The vocabularies and lookup tables the rules and the code decide by, each
   fingerprinted by its bytes and named by its classpath path: data now, so
   part of what a run ran (post-audit 8, K6). Listed from the source tree or
   from the jar that carries them (review of 2026-11-09, L3), and each read
   as the code reads it, by its path on the classpath: a file another root
   puts first is the one fingerprinted (L3 review)."
  []
  (when-let [url (io/resource vocab-anchor)]
    (let [vs (vec (for [p (sort (distinct (vocab-paths url)))]
                    (assoc (with-open [in (io/input-stream (io/resource p))] (fingerprint (.readAllBytes in))) :path p)))]
      (when (empty? vs)
        (throw (ex-info "manifest: the vocabularies were not found beside their anchor" {:url (str url)})))
      vs)))

(defn- plugin-entry [plugin]
  (cond-> {:id           (:id plugin)
           :spec-version (:plugin/spec-version plugin)}
    (:mapping plugin) (assoc :mapping-digest (model/content-digest (:mapping plugin) 64))
    (:rules plugin)   (assoc :rules-digest (model/content-digest (:rules plugin) 64))))

(defn- loss-summary [report]
  (select-keys report [:total :distinct-losses :by-category :records :source-fields]))

(defn manifest
  "The manifest of a conversion. `run` is a map:
   `{:from :to :opts :inputs [{:path :content}] :outputs [{:path :content}] :loss
   <conversion-report> :authorities [{:path :sha256 :bytes :index}] :started-at
   inst :finished-at inst :external {…}}` — everything optional but `:from`
   and `:to`. The active rules are the spoke's
   mapping rules and the canonical validation rules."
  [{:keys [from to opts inputs outputs loss authorities started-at finished-at external]}]
  (let [onto  (for [p ontology/vendored :let [f (io/file p)] :when (.exists f)]
                (assoc (fingerprint f) :path p))
        rules (concat (map :mapping/id (:mapping (spokes/plugin from))) (map :id (:rules canonical/plugin)))
        vocabs (vocabularies)]
    (cond-> {:manifest/version manifest-version
             :palomar          (cond-> (assoc (build-info) :code (code-fingerprint) :runtime (runtime-info))
                                 (dependencies) (assoc :dependencies (dependencies)))
             :from             from
             :to               to
             :plugins          (mapv plugin-entry (keep identity [(spokes/plugin from) canonical/plugin]))
             :ontologies       (vec onto)
             ;; an input that is no regular file (a pipe, stdin) is read but
             ;; once: it is named, not fingerprinted (post-audit 8, K6 review)
             :inputs           (mapv #(if (some? (:content %))
                                        (assoc (fingerprint (:content %)) :path (:path %))
                                        {:path (:path %) :fingerprinted? false})
                                     inputs)
             :outputs          (mapv #(assoc (fingerprint (:content %)) :path (:path %)) outputs)
             :options          (or opts {})}
      (seq rules)  (assoc :rules (vec (sort rules)))
      (seq vocabs) (assoc :vocabularies vocabs)
      loss         (assoc :loss (loss-summary loss))
      (seq authorities) (assoc :authorities (vec authorities))
      started-at   (assoc :started-at started-at)
      finished-at  (assoc :finished-at finished-at)
      (seq external) (assoc :external external))))

(def ^:private volatile-keys
  "Keys that differ between two runs of the same conversion."
  #{:started-at :finished-at})

(defn diff
  "What differs between manifests `a` and `b`, ignoring the run times:
   `{key [in-a in-b]}` for each top-level key whose value differs, plugins
   compared by id. Empty when the two runs are reproductions of each other."
  [a b]
  (into (sorted-map)
        (for [k (sort-by str (distinct (concat (keys a) (keys b))))
              :when (not (volatile-keys k))
              :let [va (get a k) vb (get b k)]
              :when (not= va vb)]
          [k [va vb]])))

(defn ->edn
  "`m` as readable, stably ordered EDN text."
  [m]
  (binding [*print-namespace-maps* false]
    (with-out-str (pprint/pprint (into (sorted-map-by #(compare (str %1) (str %2))) m)))))

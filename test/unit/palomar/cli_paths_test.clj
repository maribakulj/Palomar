(ns palomar.cli-paths-test
  "No command of the CLI writes over a file it reads, or over another file it
   writes (lot O1). Every command declares its file roles, what it reads and
   what it writes; one guard, run before the command, refuses a written path
   that names the same file as any other path of the run: by the same or
   another spelling, a symbolic link or a hard link. A refusal changes no
   byte and adds no file."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [palomar.cli :as cli]
            [palomar.convert]
            [palomar.reconciliation]))

(def ^:private marc21 "test/fixtures/documentary/marc21/marcxml/loc_collection.xml")
(def ^:private bib "test/fixtures/documentary/intermarc/sru/intermarcXchange/bnf-sru-jules-verne-50.xml")
(def ^:private persons "test/fixtures/er-gold/bnf-persons/authorities.xml")
(def ^:private model "resources/palomar/models/agent-match.edn")
(def ^:private extract "test/fixtures/er-gold/gnd-persons/gnd-extract.jsonl")
(def ^:private mention "[:bnf/cb35721876c \"700\" 0]")

;; --- every command declares its roles ---------------------------------------

(def ^:private expected-roles
  "Every command of the CLI, and the label of each file it reads and writes.
   A command added to the CLI is missing here, and fails the test below until
   its roles are stated."
  {"formats"       {:reads #{} :writes #{}}
   "ir-schema"     {:reads #{} :writes #{}}
   "convert"       {:reads #{"the input file"} :writes #{"--out" "--manifest"}}
   "validate"      {:reads #{"the input file"} :writes #{}}
   "report"        {:reads #{"the input file"} :writes #{}}
   "inspect"       {:reads #{"the input file"} :writes #{}}
   "reconcile"     {:reads #{"the input file" "--authorities" "--model"} :writes #{"--manifest"}}
   "authorities"   {:reads #{"the dump"} :writes #{"--out"}}
   "apply-repairs" {:reads #{"the input file"} :writes #{}}
   "conformance"   {:reads #{"the input file"} :writes #{}}})

(def ^:private every-flag
  "A value for every flag the CLI reads, so that each role a command declares
   shows."
  (into {} (for [f ["out" "manifest" "authorities" "model" "from" "to" "policy" "profile" "record-id"
                    "engine" "format" "mention" "built" "metadata-prefix" "by" "lanes"]]
             [f (str "p-" f)])))

(deftest every-command-declares-the-files-it-reads-and-writes
  (let [commands (some-> (resolve 'palomar.cli/commands) var-get)]
    (testing "the dispatch table and the declarations name the same commands"
      (is (= (set (keys expected-roles)) (set (keys commands)))))
    (doseq [[c {:keys [run roles]}] commands]
      (testing c
        (is (fn? run))
        (is (fn? roles) (str c " declares no file roles"))
        (when (fn? roles)
          (let [{:keys [reads writes]} (roles every-flag (case c
                                                           "authorities" ["index" "p-dump"]
                                                           "reconcile"   ["p-input"]
                                                           ["p-input"]))]
            (is (= (expected-roles c) {:reads (set (map first reads)) :writes (set (map first writes))}))))))
    (testing "the subcommands of reconcile and a streamed conversion declare the same roles"
      (let [{:strs [reconcile convert]} commands]
        (doseq [sub ["explain" "clusters"]]
          (is (= (dissoc ((:roles reconcile) every-flag ["p-input"]) :label)
                 (dissoc ((:roles reconcile) every-flag [sub "p-input"]) :label))))
        (is (= ((:roles convert) every-flag ["p-input"])
               ((:roles convert) (assoc every-flag "stream" true) ["p-input"])))))))

;; --- a collision is refused before any write ---------------------------------

(def ^:private scratch-dirs (atom []))

(defn- scratch-dir []
  (let [d (str (java.nio.file.Files/createTempDirectory "palomar-o1-" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (swap! scratch-dirs conj d)
    d))

(use-fixtures :each (fn [t]
                      (try (t)
                           (finally
                             (doseq [d @scratch-dirs
                                     f (reverse (sort-by #(count (str %)) (file-seq (java.io.File. ^String d))))]
                               (java.nio.file.Files/deleteIfExists (.toPath ^java.io.File f)))
                             (reset! scratch-dirs [])))))

(defn- sha256 [^java.io.File f]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")]
    (apply str (map #(format "%02x" %) (.digest md (java.nio.file.Files/readAllBytes (.toPath f)))))))

(defn- snapshot
  "Every entry of directory `d`: its name, whether it is a link, and the
   SHA-256 of the bytes it leads to."
  [d]
  (into (sorted-map)
        (for [^java.io.File f (rest (file-seq (java.io.File. ^String d)))]
          [(str (.relativize (.toPath (java.io.File. ^String d)) (.toPath f)))
           [(java.nio.file.Files/isSymbolicLink (.toPath f))
            (when (java.nio.file.Files/isRegularFile (.toPath f) (make-array java.nio.file.LinkOption 0)) (sha256 f))]])))

(def ^:private label
  {:input "the input file" :dump "the dump" :out "--out" :manifest "--manifest"
   :authorities "--authorities" :model "--model"})

(def ^:private scenarios
  "Each command that writes a file: the files it reads, their contents, the
   roles it writes, and its arguments."
  [{:name "convert" :reads {:input marc21} :writes [:out :manifest]
    :args (fn [{:keys [input out manifest]}] ["convert" input "--from" "marc21" "--to" "ntriples" "--out" out "--manifest" manifest])}
   {:name "convert --stream" :reads {:input marc21} :writes [:out :manifest]
    :args (fn [{:keys [input out manifest]}] ["convert" input "--from" "marc21" "--to" "ntriples" "--stream" "--out" out "--manifest" manifest])}
   {:name "reconcile" :reads {:input bib :authorities persons :model model} :writes [:manifest]
    :args (fn [{:keys [input authorities model manifest]}]
            ["reconcile" input "--from" "intermarc" "--authorities" authorities "--engine" "probabilistic"
             "--model" model "--manifest" manifest])}
   {:name "reconcile explain" :reads {:input bib :authorities persons :model model} :writes [:manifest]
    :args (fn [{:keys [input authorities model manifest]}]
            ["reconcile" "explain" input "--from" "intermarc" "--authorities" authorities "--mention" mention
             "--model" model "--manifest" manifest])}
   {:name "reconcile clusters" :reads {:input bib :authorities persons :model model} :writes [:manifest]
    :args (fn [{:keys [input authorities model manifest]}]
            ["reconcile" "clusters" input "--from" "intermarc" "--authorities" authorities
             "--model" model "--manifest" manifest])}
   {:name "authorities index" :reads {:dump extract} :writes [:out]
    :args (fn [{:keys [dump out]}] ["authorities" "index" dump "--out" out "--built" "2026-11-11"])}])

(defn- file-name [role] (str (name role) (case role :model ".edn" :manifest ".edn" :dump ".jsonl" :out ".out" ".xml")))

(defn- alias!
  "Make a path in `d` that names the file at `target` by `kind`, and return it."
  [d target kind]
  (let [t (java.io.File. ^String target)]
    (case kind
      :same     target
      :spelling (str d "/sub/../" (.getName t))
      :symlink  (str (java.nio.file.Files/createSymbolicLink (.toPath (java.io.File. (str d "/alias-" (.getName t))))
                                                             (.toPath t) (make-array java.nio.file.attribute.FileAttribute 0)))
      :hardlink (str (java.nio.file.Files/createLink (.toPath (java.io.File. (str d "/alias-" (.getName t)))) (.toPath t))))))

(defn- pairs
  "Every (a, b) of a scenario where b is a written role and a another role."
  [{:keys [reads writes]}]
  (concat (for [r (keys reads) w writes] [r w])
          (for [[i w1] (map-indexed vector writes) w2 (drop (inc i) writes)] [w1 w2])))

(defn- refused? [{:keys [args reads writes] :as sc} [a b] kind]
  (let [d     (scratch-dir)
        _     (.mkdirs (java.io.File. (str d "/sub")))
        files (merge (into {} (for [[r src] reads]
                                (let [p (str d "/" (file-name r))]
                                  (java.nio.file.Files/copy (.toPath (java.io.File. ^String src)) (.toPath (java.io.File. p))
                                                            ^"[Ljava.nio.file.CopyOption;" (make-array java.nio.file.CopyOption 0))
                                  [r p])))
                     (into {} (for [w writes]
                                (let [p (str d "/" (file-name w))]
                                  (spit p (str "an earlier " (name w)))
                                  [w p]))))
        paths (assoc files b (alias! d (files a) kind))
        before (snapshot d)
        {:keys [exit err]} (cli/run (args paths))]
    (testing (str (:name sc) ": " (label b) " names " (label a) ", by " (name kind))
      (is (= 2 exit) err)
      (is (str/includes? (str err) (label a)) err)
      (is (str/includes? (str err) (label b)) err)
      (is (str/includes? (str err) "choose another path") err)
      (is (= before (snapshot d)) "no byte changed, no file added"))
    (and (= 2 exit) (= before (snapshot d)))))

(deftest a-written-path-that-names-a-read-or-written-file-is-refused
  (testing "for every command that writes, every (read, write) and (write, write) pair of its roles, by the same path,
            another spelling, a symbolic link and a hard link: exit 2, and every file keeps its bytes (lot O1)"
    (doseq [sc scenarios
            pair (pairs sc)
            kind [:same :spelling :symlink :hardlink]]
      (refused? sc pair kind)))
  (testing "the pairs are those the roles declare"
    (is (= 3 (count (pairs (first scenarios)))))
    (is (= 3 (count (pairs (nth scenarios 2)))))
    (is (= 1 (count (pairs (last scenarios)))))))

(deftest reconcile-never-replaces-its-authority-file-or-its-model
  (testing "the reported case: reconcile --authorities autorites.edn --manifest autorites.edn (lot O1)"
    (let [d (scratch-dir)
          input (str d "/notices.xml")
          auth  (str d "/autorites.edn")]
      (spit input (slurp bib))
      (spit auth (slurp persons))
      (let [before (snapshot d)
            {:keys [exit err]} (cli/run ["reconcile" input "--from" "intermarc" "--authorities" auth "--manifest" auth])]
        (is (= 2 exit) err)
        (is (str/includes? (str err) "--manifest") err)
        (is (str/includes? (str err) "--authorities") err)
        (is (str/includes? (str err) auth) err)
        (is (= before (snapshot d)) "the authority file keeps its bytes"))))
  (testing "--model is --manifest"
    (let [d (scratch-dir)
          input (str d "/notices.xml")
          m     (str d "/model.edn")]
      (spit input (slurp bib))
      (spit m (slurp model))
      (let [before (snapshot d)
            {:keys [exit err]} (cli/run ["reconcile" input "--from" "intermarc" "--engine" "probabilistic"
                                         "--model" m "--manifest" m])]
        (is (= 2 exit) err)
        (is (str/includes? (str err) "--model") err)
        (is (= before (snapshot d)) "the model keeps its bytes")))))

(deftest two-outputs-not-yet-written-are-one-file-by-their-canonical-path
  (testing "--out and --manifest, neither there yet, one by another spelling or through a linked directory (lot O1)"
    (doseq [stream? [false true]
            mk [(fn [d] (str d "/sub/../new.nt"))
                (fn [d] (java.nio.file.Files/createSymbolicLink (.toPath (java.io.File. (str d "/linked")))
                                                                (.toPath (java.io.File. ^String d))
                                                                (make-array java.nio.file.attribute.FileAttribute 0))
                  (str d "/linked/new.nt"))]]
      (let [d (scratch-dir)
            _ (.mkdirs (java.io.File. (str d "/sub")))
            m (mk d)
            before (snapshot d)
            {:keys [exit err]} (cli/run (cond-> ["convert" marc21 "--from" "marc21" "--to" "ntriples"
                                                 "--out" (str d "/new.nt") "--manifest" m]
                                          stream? (conj "--stream")))]
        (is (= 2 exit) err)
        (is (str/includes? (str err) "choose another path") err)
        (is (= before (snapshot d)) "nothing written")))))

(deftest distinct-paths-still-run
  (testing "the guard refuses only what names one file: distinct files run as before (lot O1)"
    (let [d (scratch-dir)
          f (str d "/notices.xml")]
      (spit f (slurp bib))
      (let [{:keys [exit err]} (cli/run ["reconcile" f "--from" "intermarc" "--authorities" persons
                                         "--manifest" (str d "/m.edn")])]
        (is (= 0 exit) err)
        (is (.isFile (java.io.File. (str d "/m.edn"))))))))

;; --- review of O1: the paths change under the run (P7) ----------------------

(defn- link! [kind link target]
  (case kind
    :symlink  (java.nio.file.Files/createSymbolicLink (.toPath (java.io.File. ^String link)) (.toPath (java.io.File. ^String target))
                                                      (make-array java.nio.file.attribute.FileAttribute 0))
    :hardlink (java.nio.file.Files/createLink (.toPath (java.io.File. ^String link)) (.toPath (java.io.File. ^String target)))))

(defn- no-part-files? [d]
  (not-any? #(str/ends-with? (.getName ^java.io.File %) ".part") (file-seq (java.io.File. ^String d))))

(deftest a-read-file-that-a-written-path-comes-to-name-is-not-written-over
  (testing "a written path that, after the check, comes to name a file the run reads is refused at publication:
            exit 2, nothing published, no temp left, the read file keeps its bytes (O1 review, P7)"
    (doseq [[label mk] [["convert, --out becomes a link to the input"
                         (fn [d in]
                           {:args ["convert" in "--from" "marc21" "--to" "ntriples" "--out" (str d "/out.nt")]
                            :redef [#'palomar.convert/convert (fn [orig] (fn [m] (let [r (orig m)] (link! :symlink (str d "/out.nt") in) r)))]})]
                        ["convert, --manifest becomes a hard link to the input"
                         (fn [d in]
                           {:args ["convert" in "--from" "marc21" "--to" "ntriples" "--out" (str d "/o.nt") "--manifest" (str d "/m.edn")]
                            :redef [#'palomar.convert/convert (fn [orig] (fn [m] (let [r (orig m)] (link! :hardlink (str d "/m.edn") in) r)))]})]
                        ["convert --stream, --out becomes a link to the input"
                         (fn [d in]
                           {:args ["convert" in "--from" "marc21" "--to" "ntriples" "--stream" "--out" (str d "/out.nt")]
                            :redef [#'palomar.convert/convert-stream (fn [orig] (fn [m f] (link! :symlink (str d "/out.nt") in) (orig m f)))]})]
                        ["reconcile, --manifest becomes a link to the input"
                         (fn [d in]
                           {:args ["reconcile" in "--from" "marc21" "--manifest" (str d "/m.edn")]
                            :redef [#'palomar.reconciliation/reconcile (fn [orig] (fn [& xs] (link! :symlink (str d "/m.edn") in) (apply orig xs)))]})]]]
      (testing label
        (let [d  (scratch-dir)
              in (str d "/in.xml")
              _  (spit in (slurp marc21))
              before (sha256 (java.io.File. in))
              {:keys [args redef]} (mk d in)
              [v wrap] redef
              orig @v
              {:keys [exit err]} (with-redefs-fn {v (wrap orig)} #(cli/run args))]
          (is (= 2 exit) err)
          (is (str/includes? (str err) "nothing was published") err)
          (is (= before (sha256 (java.io.File. in))) "the input keeps its bytes")
          (is (no-part-files? d) "no temp left"))))))

;; --- review of O1: the guard's own failures ----------------------------------

(deftest a-guard-that-cannot-compare-refuses
  (testing "a comparison that throws is a refusal, exit 2, and nothing is written (O1 review)"
    (let [d (scratch-dir)
          out (str d "/out.nt")
          {:keys [exit err]} (with-redefs-fn {(resolve 'palomar.paths/same-file?) (fn [_ _] (throw (java.io.IOException. "probe")))}
                               #(cli/run ["convert" marc21 "--from" "marc21" "--to" "ntriples" "--out" out]))]
      (is (= 2 exit) err)
      (is (str/includes? (str err) "could not be compared") err)
      (is (= {} (snapshot d)) "nothing written"))))

(deftest a-command-without-roles-is-not-run
  (testing "a command registered without :roles is refused, never run (O1 review)"
    (let [ran (atom false)
          v   (resolve 'palomar.cli/commands)
          {:keys [exit err]} (with-redefs-fn {v (assoc @v "no-roles" {:run (fn [_ _] (reset! ran true) {:exit 0 :out "" :err ""})})}
                               #(cli/run ["no-roles" "x" "--out" "y"]))]
      (is (= 2 exit) err)
      (is (str/includes? (str err) "declares no file roles") err)
      (is (false? @ran)))))

;; --- review of O1: messages and precedence -----------------------------------

(deftest the-refusal-names-the-command-and-a-missing-file-comes-first
  (testing "the refusal names the command as typed, the later role first, as before O1 (O1 review)"
    (let [d (scratch-dir)
          dump (str d "/dump.jsonl")
          in   (str d "/in.xml")
          o    (str d "/o.nt")]
      (spit dump (slurp extract))
      (spit in (slurp bib))
      (is (= (str "authorities index: --out is the dump (" dump "); choose another path")
             (:err (cli/run ["authorities" "index" dump "--out" dump]))))
      (is (= (str "convert: --manifest is --out (" o "); choose another path")
             (:err (cli/run ["convert" marc21 "--from" "marc21" "--to" "ntriples" "--out" o "--manifest" o]))))
      (is (str/starts-with? (:err (cli/run ["reconcile" "explain" in "--from" "intermarc" "--authorities" persons
                                            "--mention" mention "--manifest" in]))
                            "reconcile explain: --manifest is the input file"))
      (is (str/starts-with? (:err (cli/run ["reconcile" "clusters" in "--from" "intermarc" "--authorities" persons
                                            "--manifest" in]))
                            "reconcile clusters: --manifest is the input file"))
      (is (str/starts-with? (:err (cli/run ["authorities" "list" dump "--out" dump])) "authorities needs a subcommand"))))
  (testing "a read file that is not there is reported as not found, before any comparison, and nothing is written"
    (let [d (scratch-dir)
          nope (str d "/nope")]
      (doseq [[args msg] [[["convert" nope "--from" "marc21" "--to" "ntriples" "--out" nope] "input file not found"]
                          [["convert" nope "--from" "marc21" "--to" "ntriples" "--stream" "--out" nope] "input file not found"]
                          [["reconcile" nope "--from" "intermarc" "--manifest" nope] "input file not found"]
                          [["reconcile" bib "--from" "intermarc" "--authorities" nope "--manifest" nope] "authority file not found"]
                          [["reconcile" bib "--from" "intermarc" "--engine" "probabilistic" "--model" nope "--manifest" nope] "model file not found"]
                          [["authorities" "index" nope "--out" nope] "dump not found"]]]
        (let [{:keys [exit err]} (cli/run args)]
          (is (= 2 exit) err)
          (is (str/includes? (str err) msg) err)
          (is (= {} (snapshot d)) "nothing written"))))))

;; --- review of O1: normalised names --------------------------------------------

(deftest names-are-compared-normalised
  (testing "canonical paths are compared in NFC, and folded in the root locale where case folds (O1 review)"
    (let [path-key (resolve 'palomar.paths/path-key)
          locale   (java.util.Locale/getDefault)]
      (is (some? path-key))
      (when path-key
        (is (= (path-key "/x/café.nt" false) (path-key "/x/café.nt" false)))
        (is (not= (path-key "/x/A.nt" false) (path-key "/x/a.nt" false)) "no folding where case does not fold")
        (try
          (java.util.Locale/setDefault (java.util.Locale/forLanguageTag "tr-TR"))
          (is (= "/x/title.nt" (path-key "/X/TITLE.NT" true)) "a Turkish default locale does not fold I to a dotless i")
          (finally (java.util.Locale/setDefault locale))))))
  (testing "two outputs not yet written, one spelled in NFC and the other in NFD, are refused"
    (let [d (scratch-dir)
          {:keys [exit err]} (cli/run ["convert" marc21 "--from" "marc21" "--to" "ntriples"
                                       "--out" (str d "/café.nt") "--manifest" (str d "/café.nt")])]
      (is (= 2 exit) err)
      (is (= {} (snapshot d)) "nothing written"))))

;; --- review of O1: a directory is no output -------------------------------------

(deftest a-directory-is-no-output
  (testing "--out or --manifest naming an existing directory is refused, exit 2, the directory left as it was (O1 review)"
    (doseq [[label mk] [["authorities index --out" (fn [dir] ["authorities" "index" extract "--out" dir "--built" "2026-11-11"])]
                        ["convert --out" (fn [dir] ["convert" marc21 "--from" "marc21" "--to" "ntriples" "--out" dir])]
                        ["convert --stream --out" (fn [dir] ["convert" marc21 "--from" "marc21" "--to" "ntriples" "--stream" "--out" dir])]
                        ["convert --manifest" (fn [dir] ["convert" marc21 "--from" "marc21" "--to" "ntriples" "--manifest" dir])]
                        ["reconcile --manifest" (fn [dir] ["reconcile" bib "--from" "intermarc" "--manifest" dir])]]]
      (testing label
        (let [d   (scratch-dir)
              dir (str d "/a-dir")
              _   (.mkdirs (java.io.File. dir))
              {:keys [exit err]} (cli/run (mk dir))]
          (is (= 2 exit) err)
          (is (str/includes? (str err) "is a directory") err)
          (is (= {"a-dir" [false nil]} (snapshot d)) "the directory is left empty, nothing beside it"))))))

;; --- review of O1: /dev/stdout through a pipe ------------------------------------

(deftest an-output-to-dev-stdout-through-a-pipe-is-written-into
  (testing "--out /dev/stdout when stdout is a pipe (its link names no file) is written into, as ADR 0030 says (O1 review)"
    (when (.exists (java.io.File. "/dev/stdout"))
      (let [java (str (System/getProperty "java.home") "/bin/java")
            pb   (ProcessBuilder. ^java.util.List [java "-cp" (System/getProperty "java.class.path") "clojure.main" "-m" "palomar.cli"
                                                   "convert" marc21 "--from" "marc21" "--to" "ntriples" "--out" "/dev/stdout"])
            p    (.start pb)
            out  (future (slurp (.getInputStream p)))
            err  (future (slurp (.getErrorStream p)))
            exit (.waitFor p)]
        (is (= 0 exit) @err)
        (is (str/includes? @out "<http") "the document reached the pipe")))))

;; --- review of O1: the dev tool that writes files ------------------------------------

(deftest the-ycba-dev-tool-does-not-write-over-its-input
  (testing "dev/eval/ycba/convert.clj refuses an output name that is the input, lido.xml, by any spelling (O1 review)"
    (doseq [nm ["lido.xml" "./lido.xml" "sub/../lido.xml"]]
      (let [d    (scratch-dir)
            pair (str d "/pairs/p1")
            lido (str pair "/lido.xml")]
        (.mkdirs (java.io.File. (str pair "/sub")))
        (spit lido "<lido/>")
        (let [before (snapshot d)
              threw  (try (binding [*command-line-args* [d nm]] (load-file "dev/eval/ycba/convert.clj")) false
                          (catch Exception _ true))]
          (is threw nm)
          (is (= before (snapshot d)) (str nm ": lido.xml keeps its bytes")))))))

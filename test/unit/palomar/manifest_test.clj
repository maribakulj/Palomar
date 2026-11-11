(ns palomar.manifest-test
  "The run manifest (ADR 0030): reproducible, and it names what changed."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.cli :as cli]
            [palomar.convert :as convert]
            [palomar.manifest :as manifest]
            [palomar.spokes :as spokes]))

(def ^:private input "test/fixtures/documentary/marc21/marcxml/loc_collection.xml")

(defn- run []
  (let [res (convert/convert {:from :marc21 :to :ntriples :source (slurp input)})]
    (manifest/manifest {:from :marc21 :to :ntriples
                        :inputs [{:path input :content (io/file input)}]
                        :outputs [{:path "-" :content (:output res)}]
                        :loss (:loss res)
                        :started-at (java.util.Date.) :finished-at (java.util.Date.)})))

(deftest a-run-names-what-its-output-depends-on
  (let [m (run)]
    (is (= :marc21 (:from m)))
    (is (= 64 (count (get-in m [:inputs 0 :sha256]))))
    (is (= (:sha256 (manifest/fingerprint (io/file input))) (get-in m [:inputs 0 :sha256])))
    (is (some #(= :palomar/marc21 (:id %)) (:plugins m)))
    (is (every? :mapping-digest (filter #(= :palomar/marc21 (:id %)) (:plugins m))))
    (is (= 3 (count (:ontologies m))))
    (is (some #{:map/marc21-title} (:rules m)))
    (is (pos? (get-in m [:loss :total])))))

(deftest the-same-conversion-reproduces-its-manifest
  (testing "two runs differ only in their times"
    (is (empty? (manifest/diff (run) (run))))))

(deftest a-changed-mapping-is-named
  (testing "editing one mapping rule changes the plugin's digest, and diff says so"
    (let [a (run)
          b (with-redefs [spokes/plugins (update spokes/plugins :marc21 update :mapping
                                                 #(vec (butlast %)))]
              (run))]
      (is (contains? (manifest/diff a b) :plugins))
      (is (contains? (manifest/diff a b) :rules)))))

(deftest convert-writes-the-manifest
  (let [f (java.io.File/createTempFile "palomar-manifest" ".edn")]
    (try
      (let [{:keys [exit]} (cli/run ["convert" input "--from" "marc21" "--to" "ntriples" "--manifest" (str f)])
            m (edn/read-string {:readers {'inst #(java.util.Date/from (java.time.Instant/parse %))}} (slurp f))]
        (is (zero? exit))
        (is (= :ntriples (:to m)))
        (is (str/ends-with? (get-in m [:inputs 0 :path]) "loc_collection.xml"))
        (is (inst? (:started-at m))))
      (finally (.delete f)))))

(deftest build-info-falls-back-to-dev
  (is (string? (:version (manifest/build-info)))))

(deftest the-manifest-names-the-code-that-ran
  (testing "the source of every Palomar file is digested (post-audit test 09)"
    (let [{:keys [sha256 files]} (get-in (manifest/manifest {:from :dc :to :ntriples}) [:palomar :code])]
      (is (re-matches #"[0-9a-f]{64}" sha256))
      (is (< 20 files))))
  (testing "whatever is loaded: the digest is of the engine's source tree, not of the namespaces loaded so far (post-audit 2, D6)"
    (let [tree (for [f (file-seq (java.io.File. "src"))
                     :when (and (.isFile f) (re-find #"\.(clj|cljc|edn)$" (.getName f)))]
                 [(subs (str/replace (.getPath f) java.io.File/separator "/") 4) (slurp f)])]
      (is (= {:sha256 (manifest/sources-digest tree) :files (count tree)} (manifest/code-fingerprint)))
      (let [before (manifest/code-fingerprint)]
        (require 'palomar.cli)
        (is (= before (manifest/code-fingerprint)) "loading a namespace changes nothing"))))
  (testing "a changed source file changes the digest, a reordering does not"
    (let [srcs [["palomar/a.clj" "(ns a)"] ["palomar/plugins/lrmoo/export.clj" "(ns export)"]]]
      (is (= (manifest/sources-digest srcs) (manifest/sources-digest (reverse srcs))))
      (is (not= (manifest/sources-digest srcs)
                (manifest/sources-digest (assoc-in srcs [1 1] "(ns export) ;; changed"))))
      (is (not= (manifest/sources-digest [["ab" "c"]]) (manifest/sources-digest [["a" "bc"]]))
          "path and content are delimited"))))

(deftest the-manifest-names-the-runtime-and-the-dependencies
  (testing "the JVM, Clojure and the resolved libraries are named (post-audit 2, D6)"
    (let [{:keys [runtime dependencies]} (:palomar (manifest/manifest {:from :dc :to :ntriples}))]
      (is (= (System/getProperty "java.version") (:java runtime)))
      (is (= (clojure-version) (:clojure runtime)))
      (is (= {:mvn/version "0.2.0-alpha9"} (get dependencies 'org.clojure/data.xml)))
      (is (every? #(or (:mvn/version %) (:git/sha %) (:local/root %)) (vals dependencies))))))

(deftest an-input-that-is-no-file-is-named-not-fingerprinted
  (testing "a pipe is read once: the manifest names it and reads it no more (post-audit 8, K6 review)"
    (is (= {:path "-" :fingerprinted? false}
           (first (:inputs (manifest/manifest {:from :dc :to :ntriples :inputs [{:path "-" :content nil}]})))))))

(defn- vocab-jar
  "A jar of `resources/palomar/vocab` under `prefix` (\"\" or a classpath
   root inside the jar, `WEB-INF/classes/`), as a packaged Palomar carries it."
  ^java.io.File [prefix]
  (let [jar  (java.io.File/createTempFile "palomar-vocab-" ".jar")
        root (java.io.File. "resources")]
    (with-open [out (java.util.jar.JarOutputStream. (java.io.FileOutputStream. jar))]
      (doseq [f (sort-by str (file-seq (java.io.File. root "palomar/vocab")))
              :when (.isFile ^java.io.File f)
              :let [p (str/replace (str (.relativize (.toPath root) (.toPath ^java.io.File f))) java.io.File/separator "/")]]
        (.putNextEntry out (java.util.jar.JarEntry. (str prefix p)))
        (.write out (java.nio.file.Files/readAllBytes (.toPath ^java.io.File f)))
        (.closeEntry out)))
    jar))

(defn- vocabularies-from
  "`manifest`'s vocabularies, read with the classpath `urls` alone."
  [urls]
  (let [t (Thread/currentThread) before (.getContextClassLoader t)]
    (with-open [cl (java.net.URLClassLoader. (into-array java.net.URL urls) nil)]
      (try (.setContextClassLoader t cl) (#'manifest/vocabularies)
           (finally (.setContextClassLoader t before))))))

(deftest the-vocabularies-are-listed-from-a-jar-as-from-a-checkout
  (testing "packaged in a jar, at its top or under a classpath root inside it, the vocabularies are fingerprinted as from the source tree: same paths, same digests (review of 2026-11-09, L3)"
    (let [checkout (:vocabularies (manifest/manifest {:from :dc :to :ntriples}))]
      (is (some #(str/ends-with? (:path %) "attribution-qualifiers.edn") checkout))
      (doseq [prefix ["" "WEB-INF/classes/"]]
        (let [jar (vocab-jar prefix)
              url (if (= "" prefix) (.toURL (.toURI jar)) (java.net.URL. (str "jar:" (.toURI jar) "!/" prefix)))]
          (try (is (= checkout (vocabularies-from [url])) prefix)
               (finally (.delete jar))))))))

(deftest a-vocabulary-is-fingerprinted-as-the-code-reads-it
  (testing "a root that puts a vocabulary first, before the jar, is the one fingerprinted (L3 review)"
    (let [jar  (vocab-jar "")
          over (.toFile (java.nio.file.Files/createTempDirectory "palomar-over-" (make-array java.nio.file.attribute.FileAttribute 0)))
          f    (io/file over "palomar/vocab/lido/actor-roles.edn")]
      (try
        (io/make-parents f)
        (spit f "{:changed true}")
        (let [vs (vocabularies-from [(.toURL (.toURI over)) (.toURL (.toURI jar))])]
          (is (= (:sha256 (manifest/fingerprint "{:changed true}"))
                 (:sha256 (first (filter #(= "palomar/vocab/lido/actor-roles.edn" (:path %)) vs)))))
          (is (= 11 (count vs)) "listed from the anchor's root, the jar"))
        (finally (.delete f) (.delete (.getParentFile f)) (.delete (.getParentFile (.getParentFile f)))
                 (.delete (io/file over "palomar")) (.delete over) (.delete jar))))))

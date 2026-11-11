(ns palomar.no-network-test
  "No network call in conversion, in reconciliation, or in building and
   reading an authority index (lot M6).

   Two checks. **By design**: the namespaces of those paths name no HTTP
   client, no socket and no URL opening; files are opened as files
   (`io/file`), never as strings that `clojure.java.io` would take for a URL.
   **At run time**: the JVM's default `ProxySelector` is replaced, for the
   test, by one that records and refuses every request. Every `http`,
   `https` and `ftp` URL connection, the default `java.net.http` client and a
   plain `java.net.Socket` connection ask it first (a test below shows it), so
   an attempt on these paths is recorded, and the test fails, even when the
   code under test swallows the refusal."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.authority-index :as index]
            [palomar.cli :as cli]))

(defn- with-network-trapped
  "Run `f` with every network request recorded and refused; the requests."
  [f]
  (let [calls (atom [])
        old   (java.net.ProxySelector/getDefault)
        trap  (proxy [java.net.ProxySelector] []
                (select [uri]
                  (swap! calls conj (str uri))
                  (throw (java.io.IOException. (str "network call refused in a test: " uri))))
                (connectFailed [_ _ _]))]
    (java.net.ProxySelector/setDefault trap)
    (try (f) (finally (java.net.ProxySelector/setDefault old)))
    @calls))

(deftest the-trap-sees-every-kind-of-request
  (let [calls (with-network-trapped
                (fn []
                  (try (slurp "https://lobid.org/gnd/118528068.json") (catch Exception _ nil))
                  (try (.send (java.net.http.HttpClient/newHttpClient)
                              (.build (java.net.http.HttpRequest/newBuilder (java.net.URI. "https://lobid.org/")))
                              (java.net.http.HttpResponse$BodyHandlers/ofString))
                       (catch Exception _ nil))
                  (try (doto (java.net.Socket.) (.connect (java.net.InetSocketAddress/createUnresolved "lobid.org" 443) 1000))
                       (catch Exception _ nil))))]
    (is (= 3 (count calls)) (pr-str calls))))

(def ^:private extract "test/fixtures/er-gold/gnd-persons/gnd-extract.jsonl")
(def ^:private bib "test/fixtures/documentary/intermarc/sru/intermarcXchange/bnf-sru-alexandre-dumas-50.xml")

(deftest conversion-and-reconciliation-make-no-network-call
  (let [idx   (doto (java.io.File/createTempFile "palomar-net-" ".edn") (.deleteOnExit))
        man   (doto (java.io.File/createTempFile "palomar-net-" ".edn") (.deleteOnExit))
        res   (atom [])
        calls (with-network-trapped
                (fn []
                  (swap! res conj (cli/run ["authorities" "index" extract "--out" (str idx) "--built" "2026-11-09"]))
                  (swap! res conj (cli/run ["reconcile" bib "--from" "intermarc" "--authorities" (str idx) "--manifest" (str man)]))
                  (swap! res conj (cli/run ["convert" bib "--from" "intermarc" "--to" "linked-art" "--manifest" (str man)]))
                  (swap! res conj (cli/run ["convert" bib "--from" "intermarc" "--to" "ntriples"]))
                  (swap! res conj (cli/run ["validate" bib "--from" "intermarc"]))
                  (index/read-index idx)))]
    (is (= [0 0 0 0 0] (map :exit @res)) (pr-str (map :err @res)))
    (is (empty? calls) (pr-str calls))))

(deftest an-input-named-like-a-url-is-read-as-a-file
  (testing "a file whose path reads as a URL (`https:/lobid.org/…` relative to the working directory) is
            opened as a file by every verb, never fetched"
    (let [dir  (io/file "https:")
          f    (io/file "https:/lobid.org/palomar-no-network-test.xml")
          name "https://lobid.org/palomar-no-network-test.xml"]
      (try
        (.mkdirs (.getParentFile f))
        (io/copy (io/file bib) f)
        (let [res   (atom [])
              calls (with-network-trapped
                      (fn []
                        (doseq [args [["validate" name "--from" "intermarc"]
                                      ["convert" name "--from" "intermarc" "--to" "ntriples"]
                                      ["convert" name "--from" "intermarc" "--to" "ntriples" "--stream" "--out"
                                       (str (doto (java.io.File/createTempFile "palomar-net-" ".nt") (.deleteOnExit)))]
                                      ["inspect" name "--from" "intermarc"]
                                      ["report" name "--from" "intermarc" "--to" "ntriples"]
                                      ["reconcile" name "--from" "intermarc"]]]
                          (swap! res conj [(first args) (:exit (cli/run args))]))))]
          (is (every? (comp zero? second) @res) (pr-str @res))
          (is (empty? calls) (pr-str calls)))
        (finally
          (.delete f) (.delete (.getParentFile f)) (.delete dir))))))

(def ^:private path-sources
  "The source files of the index, the GND reader and the matcher."
  ["src/palomar/authority_index.clj" "src/palomar/plugins/gnd.clj" "src/palomar/plugins/authorities.clj"
   "src/palomar/reconcile.clj" "src/palomar/agent_match.clj" "src/palomar/agent_dates.clj" "src/palomar/candidates.clj"
   "src/palomar/agent_equivalence.clj"])

(def ^:private import-form
  "A java.net class named in an import form, apart from its package:
   `(:import (java.net URL))`, `[java.net URI URL]`."
  #"java\.net[\s\]\)\[(]+(?:[A-Za-z]+\s+)*(?:URL|URI|Socket|HttpURLConnection)\b")

(deftest no-network-client-on-the-path-by-design
  (doseq [f path-sources
          :let [s (slurp (io/file f))]]
    (testing f
      (doseq [needle ["java.net.http" "HttpURLConnection" "HttpClient" "openConnection" "openStream"
                      "java.net.Socket" "java.net.URL" "as-url" "URI."]]
        (is (not (str/includes? s needle)) (str f " names " needle)))
      ;; an import form names the class apart from its package: (:import (java.net URL))
      (is (nil? (re-find import-form s))
          (str f " imports a java.net class")))))

(deftest the-import-needle-sees-an-import-form
  (is (re-find import-form "(:import (java.net URL))"))
  (is (re-find import-form "(:import [java.net URI URL])"))
  (is (re-find import-form "(:import (java.net InetAddress Socket))"))
  (is (nil? (re-find import-form "java.net.http is named in a docstring, not imported"))))

(ns palomar.paths
  "Whether two paths name one file, and what file a path names now (lots L1
   and O1, ADR 0030). The CLI's guard and the dev tools that write files
   compare their paths with `same-file?` before they read or write;
   `file-identity` is taken of each read file at that check and taken again
   of each written path just before it is published, so that a path that
   came to name a read file during the run is not written over."
  (:require [clojure.java.io :as io]))

(def ^:private no-links (make-array java.nio.file.LinkOption 0))

(defn path-key
  "The string by which a canonical path `s` is compared: in Unicode NFC, so
   that one name in NFC and in NFD is one, and, where the directory folds
   case (`folds?`), lower-cased in the root locale, so that no default
   locale (a Turkish one) folds a letter otherwise."
  [^String s folds?]
  (let [n (java.text.Normalizer/normalize s java.text.Normalizer$Form/NFC)]
    (if folds? (.toLowerCase n java.util.Locale/ROOT) n)))

(defn- case-folded?
  "Whether the directory of `f` folds case: a name differing only in case
   names the same file. Probed with a temp file of its own, else false."
  [^java.io.File f]
  (try
    (let [t (java.io.File/createTempFile "palomar-case-" ".probe" (.getParentFile f))]
      (try (.exists (java.io.File. (.getParentFile t) (.toUpperCase (.getName t) java.util.Locale/ROOT)))
           (finally (.delete t))))
    (catch java.io.IOException _ false)))

(defn same-file?
  "Whether paths `a` and `b` name one file: by the file system when both
   exist (a symbolic link, a hard link), else by their canonical paths
   (the links of their directories followed; a dangling link names itself,
   where it would be replaced), compared in NFC and, where the directory
   folds case, in any case (review of 2026-11-09, L1; lot O1)."
  [a b]
  (let [fa (.getAbsoluteFile (io/file a))
        fb (.getAbsoluteFile (io/file b))]
    (if (and (.exists fa) (.exists fb))
      (java.nio.file.Files/isSameFile (.toPath fa) (.toPath fb))
      (let [ca (.getCanonicalPath fa) cb (.getCanonicalPath fb)]
        (or (= (path-key ca false) (path-key cb false))
            (and (= (path-key ca true) (path-key cb true)) (case-folded? (io/file ca))))))))

(defn file-identity
  "What file `path` names now, followed through its links: `{:real :key}`,
   its real path and the file system's key of the file (a hard link has
   another path but the same key), either nil where it cannot be known (a
   pipe's link names no path); nil when nothing is there."
  [path]
  (let [p (.toPath (.getAbsoluteFile (io/file path)))]
    (when (java.nio.file.Files/exists p no-links)
      (let [real (try (str (.toRealPath p no-links)) (catch java.io.IOException _ nil))
            k    (try (.fileKey (java.nio.file.Files/readAttributes p java.nio.file.attribute.BasicFileAttributes ^"[Ljava.nio.file.LinkOption;" no-links))
                      (catch java.io.IOException _ nil))]
        (when (or real k) {:real real :key k})))))

(defn same-identity?
  "Whether two `file-identity` values name one file."
  [a b]
  (boolean (and a b (or (and (:real a) (= (:real a) (:real b)))
                        (and (:key a) (= (:key a) (:key b)))))))

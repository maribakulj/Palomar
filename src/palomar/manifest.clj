(ns palomar.manifest
  "Run manifest: what a conversion was, so a result can be reproduced and two
   results that differ can be explained (plan step 6, ADR 0030).

   A manifest is a plain EDN map naming everything a conversion's output
   depends on: Palomar's version and commit, the spoke and target, each plugin
   with a digest of its mapping and rules (they are data, so a changed mapping
   changes its digest), the ontology files, the SHA-256 of every input and
   output, the options, the compiled rule ids, the loss summary, and the times
   the caller supplies. `diff` names what changed between two manifests.

   The engine stays pure: times, paths and the external block (e.g. the model
   and parameters an application used upstream) come from the caller; the CLI's
   `convert --manifest <file>` fills them."
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [palomar.model :as model]
            [palomar.ontology :as ontology]
            [palomar.plugins.canonical :as canonical]
            [palomar.spokes :as spokes]))

(def manifest-version
  "The version of the manifest's own shape."
  1)

(defn build-info
  "Palomar's version and commit, from the `palomar/build.edn` resource a release
   build writes (`{:version :commit}`), else `{:version \"dev\"}`."
  []
  (if-let [r (io/resource "palomar/build.edn")]
    (read-string (slurp r))
    {:version "dev"}))

(defn fingerprint
  "`{:sha256 hex :bytes n}` of `content` (a String, a byte array or a File)."
  [content]
  (let [bytes (cond (bytes? content) content
                    (instance? java.io.File content) (java.nio.file.Files/readAllBytes (.toPath ^java.io.File content))
                    :else (.getBytes (str content) "UTF-8"))]
    {:sha256 (model/sha256-hex bytes 64) :bytes (alength ^bytes bytes)}))

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
   <conversion-report> :started-at inst :finished-at inst :external {…}}` —
   everything optional but `:from` and `:to`. The active rules are the spoke's
   mapping rules and the canonical validation rules."
  [{:keys [from to opts inputs outputs loss started-at finished-at external]}]
  (let [onto  (for [p ontology/vendored :let [f (io/file p)] :when (.exists f)]
                (assoc (fingerprint f) :path p))
        rules (concat (map :mapping/id (:mapping (spokes/plugin from))) (map :id (:rules canonical/plugin)))]
    (cond-> {:manifest/version manifest-version
             :palomar          (build-info)
             :from             from
             :to               to
             :plugins          (mapv plugin-entry (keep identity [(spokes/plugin from) canonical/plugin]))
             :ontologies       (vec onto)
             :inputs           (mapv #(assoc (fingerprint (:content %)) :path (:path %)) inputs)
             :outputs          (mapv #(assoc (fingerprint (:content %)) :path (:path %)) outputs)
             :options          (or opts {})}
      (seq rules)  (assoc :rules (vec (sort rules)))
      loss         (assoc :loss (loss-summary loss))
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

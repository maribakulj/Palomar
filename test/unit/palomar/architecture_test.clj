(ns palomar.architecture-test
  "Guards the core/plugin dependency boundary surfaced by the architecture
   audit: the plugin-agnostic core (`model`, `rules`, `runtime`,
   `diagnostics`) must never depend on `palomar.plugins.*`. Plugins flow
   *into* the runtime by producing compiled rules; the runtime never knows
   any plugin (ADR 0007). The dependency direction is one-way, and this test
   keeps it that way.

   The check is static: it reads each core file's leading `ns` form from the
   classpath and inspects its `:require` clauses. It deliberately avoids
   `tools.namespace` so it runs under the plain `:test` alias used by CI."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def ^:private core-namespaces
  "Classpath-relative source paths of the plugin-agnostic core. None of these
   may require a `palomar.plugins.*` namespace."
  {'palomar.model       "palomar/model.clj"
   'palomar.rules       "palomar/rules.clj"
   'palomar.runtime     "palomar/runtime.clj"
   'palomar.diagnostics "palomar/diagnostics.clj"})

(defn- ns-form
  "Read the leading `ns` form of a source file located on the classpath."
  [resource-path]
  (let [res (io/resource resource-path)]
    (when-not res
      (throw (ex-info "Core source not found on classpath"
                      {:path resource-path})))
    (binding [*read-eval* false]
      (read-string (slurp res)))))

(defn- libspec->namespaces
  "Resolve one `:require`/`:use` libspec to the namespace symbols it brings
   in. Handles plain symbols, `[ns ...]` vectors and `(prefix sub ...)`
   prefix lists."
  [libspec]
  (cond
    (symbol? libspec) #{libspec}
    (vector? libspec) #{(first libspec)}
    (and (seq? libspec) (seq libspec))
    (let [prefix (first libspec)
          subs   (rest libspec)]
      (if (seq subs)
        (into #{}
              (map (fn [s] (symbol (str prefix "." (if (coll? s) (first s) s)))))
              subs)
        #{prefix}))
    :else #{}))

(defn- required-namespaces
  "Set of namespace symbols required by an `ns` form."
  [form]
  (->> (rest form)
       (filter seq?)
       (filter #(#{:require :use} (first %)))
       (mapcat rest)
       (mapcat libspec->namespaces)
       (into #{})))

(defn- plugin-deps
  "Required namespaces of `form` that live under `palomar.plugins`."
  [form]
  (filter #(str/starts-with? (str %) "palomar.plugins")
          (required-namespaces form)))

(deftest core-namespaces-do-not-depend-on-plugins
  (testing "the plugin-agnostic core never requires palomar.plugins.* (ADR 0007)"
    (doseq [[ns-sym path] core-namespaces]
      (let [offending (plugin-deps (ns-form path))]
        (is (empty? offending)
            (str ns-sym " must not depend on plugins, but requires: "
                 (pr-str offending)))))))

(deftest dependency-parser-is-not-vacuous
  ;; A boundary test that always passes because the parser returned nothing is
  ;; worse than no test. Pin the parser against known-true dependencies so a
  ;; silent parsing regression cannot make the guard vacuous.
  (testing "the ns-form parser actually extracts requires from real source"
    (let [req (required-namespaces (ns-form "palomar/rules.clj"))]
      (is (contains? req 'palomar.model))
      (is (contains? req 'malli.core)))))

(deftest guard-detects-a-violation
  ;; Positive control: a synthetic core ns that *does* require a plugin must be
  ;; flagged. Proves the guard catches violations, not merely that none exist.
  (testing "a core namespace requiring a plugin would be caught"
    (let [synthetic '(ns palomar.fake
                       (:require [palomar.model :as model]
                                 [palomar.plugins.shape :as shape]))]
      (is (= #{'palomar.plugins.shape} (set (plugin-deps synthetic)))))))

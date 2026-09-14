(ns palomar.plugins
  "Plugin protocol and registry.

   A plugin is a plain Clojure map (ADR 0007) carrying any combination
   of:

   - an `:importer` (external source → IR records),
   - a `:stream-importer` (a `Reader` → a *lazy* record seq, for WP-7 streaming),
   - an `:exporter` (IR records → external output),
   - `:rules` — rule-DSL data, schema in `palomar.rules` (ADR 0002),
   - a `:mapping` — data-shaped sugar over rules (ADR 0009),
   - `:predicates` / `:transforms` — stdlib extensions (ADR 0010),
   - declarations (`:requires`, `:input-format`, `:output-format`).

   Only `:plugin/spec-version` and `:id` are required; every other key is
   optional. The schema is closed: unknown keys are rejected so typos
   surface immediately. New optional keys must justify themselves
   through concrete plugin needs (growth discipline per ADR 0007).

   The registry is a plain immutable map `{plugin-id → plugin-map}`.
   `register` and `unregister` return new registries; multiple registries
   coexist freely (test fixtures, isolated runs).

   This namespace covers the schema, the registry data structure, and
   the inert query helpers (lookup, filter-by-format, pooling). The
   `:requires` resolution graph and the `:input-format` dispatch logic
   live in their own namespaces (Sprint 5 M2.B)."
  (:require [clojure.set :as set]
            [malli.core :as m]
            [palomar.plugins.transforms :as tx]
            [palomar.rules :as rules]))

;; ---------------------------------------------------------------------------
;; Plugin schema
;;
;; Closed shape: every key is named here. The `:plugin/spec-version` field
;; is literal `1` for the current major revision; future incompatible
;; changes bump it, additive minor changes do not.
;;
;; `:rules` and `:mapping` are shallow-validated (`vector of maps`). Deep
;; validation lives with the relevant compiler — the rule compiler
;; (`palomar.rules`) and the mapping compiler (Sprint 5 M4). Keeping
;; this namespace independent of those compilers avoids a load-order
;; cycle and lets a plugin be inspectable without compiling anything.
;; ---------------------------------------------------------------------------

(def Plugin
  [:map {:closed true}
   ;; identity
   [:plugin/spec-version [:= 1]]
   [:id :keyword]
   [:version {:optional true} :string]

   ;; code surface (functions; closures cannot be deeply introspected,
   ;; so the schema checks only that they are callable)
   [:importer {:optional true} fn?]
   [:stream-importer {:optional true} fn?]   ; WP-7: (opts readable) -> lazy record seq
   [:exporter {:optional true} fn?]
   [:matches? {:optional true} fn?]

   ;; data surface (deep validation deferred to dedicated compilers)
   [:rules      {:optional true} [:vector :map]]
   [:mapping    {:optional true} [:vector :map]]
   [:predicates {:optional true} [:map-of :symbol fn?]]
   [:transforms {:optional true} [:map-of :keyword fn?]]

   ;; declarations
   [:requires      {:optional true} [:set :keyword]]
   [:input-format  {:optional true} :keyword]
   [:output-format {:optional true} :keyword]
   [:doc           {:optional true} :string]])

(defn valid-plugin?
  "True if `plugin` conforms to the Plugin schema (ADR 0007)."
  [plugin]
  (m/validate Plugin plugin))

(defn explain-plugin
  "Return a Malli explanation map describing why `plugin` is invalid,
   or nil if it is valid. Intended for register-time error messages
   and for plugin-authoring tooling."
  [plugin]
  (m/explain Plugin plugin))

;; ---------------------------------------------------------------------------
;; Registry
;;
;; A registry is `{plugin-id → plugin-map}`. Everything is immutable;
;; `register` and `unregister` return new registries.
;; ---------------------------------------------------------------------------

(def empty-registry
  "The empty registry: a plain `{}`. Provided as a named value so
   call-sites read declaratively."
  {})

(defn- validate-plugin-or-throw! [plugin]
  (when-let [explanation (explain-plugin plugin)]
    (throw (ex-info "Invalid plugin"
                    {:plugin-id   (:id plugin)
                     :explanation explanation}))))

(defn register
  "Register `plugin` into `registry`. Returns a new registry with the
   plugin installed under its `:id`.

   Throws if the plugin is malformed (Plugin schema violation) or if a
   plugin with the same `:id` is already present. Same-id duplicates
   are rejected rather than silently overwritten — silent overwrite
   would mask version-skew bugs (ADR 0007)."
  [registry plugin]
  (validate-plugin-or-throw! plugin)
  (let [id (:id plugin)]
    (when (contains? registry id)
      (throw (ex-info "Duplicate plugin id"
                      {:plugin-id id
                       :existing  (get registry id)})))
    (assoc registry id plugin)))

(defn unregister
  "Remove the plugin with the given `:id` from `registry`. Returns a
   new registry. No-op if the plugin is not registered."
  [registry plugin-id]
  (dissoc registry plugin-id))

(defn lookup
  "Return the plugin registered under `plugin-id`, or `nil` if absent."
  [registry plugin-id]
  (get registry plugin-id))

(defn registered-ids
  "Return the set of plugin ids currently in `registry`."
  [registry]
  (set (keys registry)))

;; ---------------------------------------------------------------------------
;; Rule + mapping pooling
;; ---------------------------------------------------------------------------

(defn all-rules
  "Concatenate the `:rules` of every plugin in `registry`. Each rule
   passes through unchanged — `palomar.rules/compile-rules` is the
   sanctioned next step."
  [registry]
  (into [] (mapcat :rules) (vals registry)))

(defn all-mappings
  "Concatenate the `:mapping` of every plugin in `registry`."
  [registry]
  (into [] (mapcat :mapping) (vals registry)))

;; ---------------------------------------------------------------------------
;; Effective stdlib (ADR 0010)
;;
;; The effective stdlib is the union of the core stdlib (shipped by the
;; runtime) and every registered plugin's `:predicates` / `:transforms`
;; contribution. Name collisions across any pair of contributors —
;; core/plugin or plugin/plugin — are rejected at this point: silent
;; first-wins resolution would make the visible stdlib depend on
;; registration order, which is the worst kind of bug.
;;
;; The core stdlib lives in `palomar.rules/predicate-stdlib` and
;; `palomar.plugins.transforms/core-transforms`. It participates in the
;; merge as a synthetic contributor with id `:core` — that name shows up
;; in collision diagnostics, telling a plugin author exactly which
;; symbol or keyword they cannot redefine.
;; ---------------------------------------------------------------------------

(def ^:private core-source :core)

(defn- merge-no-conflicts
  "Merge a sequence of `[source-id contribution-map]` pairs into one map.
   Throws ex-info naming the conflicting keys and the contributors
   responsible if any key appears in more than one contribution. `kind`
   is the human-readable noun used in the error message (\"Predicate\"
   / \"Transform\")."
  [pairs kind]
  (reduce (fn [acc [source-id contribution]]
            (let [conflicts (set/intersection (set (keys acc))
                                              (set (keys contribution)))]
              (when (seq conflicts)
                (throw (ex-info (str kind " name collision")
                                {:kind         kind
                                 :names        conflicts
                                 :incoming     source-id
                                 :already-from (into {}
                                                     (for [n conflicts]
                                                       [n (-> acc meta ::source-by-name (get n))]))})))
              (with-meta (into acc contribution)
                {::source-by-name
                 (into (or (-> acc meta ::source-by-name) {})
                       (for [k (keys contribution)] [k source-id]))})))
          (with-meta {} {::source-by-name {}})
          pairs))

(defn- plugin-contributions
  "Sequence of `[plugin-id contribution-map]` for the given top-level
   plugin key (`:predicates` or `:transforms`)."
  [registry plugin-key]
  (keep (fn [plugin]
          (when-let [m (get plugin plugin-key)]
            [(:id plugin) m]))
        (vals registry)))

(defn effective-stdlib
  "Return `{:predicates ... :transforms ...}` — the union of core
   stdlibs and every plugin's contribution. Collisions across any pair
   of contributors (core/plugin, plugin/plugin) throw an explanatory
   ex-info naming the contributors involved.

   The maps returned are plain (no metadata). The merge bookkeeping is
   internal; use `transform-source` to ask which contributor owns a
   given transform."
  [registry]
  (let [pred-contribs (cons [core-source rules/predicate-stdlib]
                            (plugin-contributions registry :predicates))
        tx-contribs   (cons [core-source tx/core-transforms]
                            (plugin-contributions registry :transforms))]
    {:predicates (with-meta (merge-no-conflicts pred-contribs "Predicate") nil)
     :transforms (with-meta (merge-no-conflicts tx-contribs "Transform")  nil)}))

(defn effective-transforms
  "Return the effective transform stdlib as a `{keyword → fn}` map.
   Convenience accessor over `effective-stdlib`."
  [registry]
  (:transforms (effective-stdlib registry)))

(defn transform-source
  "Return the contributor of the transform `kw` in the registry's
   effective stdlib: `:core` for a built-in, the plugin id for a
   plugin contribution, or `nil` if `kw` is not defined.

   Same trust caveat as `effective-stdlib`."
  [registry kw]
  (cond
    (contains? tx/core-transforms kw)
    core-source

    :else
    (some (fn [plugin]
            (when (contains? (:transforms plugin) kw)
              (:id plugin)))
          (vals registry))))

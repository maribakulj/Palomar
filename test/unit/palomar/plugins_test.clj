(ns palomar.plugins-test
  "Unit tests for the plugin protocol and registry (Sprint 5 M2.A).
   Covers the Plugin schema, registry CRUD operations, query helpers,
   and the effective-stdlib pooling. `:requires` graph resolution and
   `:input-format` dispatch live in their own test namespace
   alongside the M2.B implementation."
  (:require [clojure.test :refer [deftest is testing]]
            [palomar.plugins :as plug]))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(def minimal-plugin
  {:plugin/spec-version 1
   :id                  :plugin/minimal})

(defn- make-importer-plugin
  ([id]
   (make-importer-plugin id :xml))
  ([id format]
   {:plugin/spec-version 1
    :id                  id
    :input-format        format
    :importer            (fn [_opts _src] {:records [] :diagnostics []})}))

;; ---------------------------------------------------------------------------
;; Plugin schema
;; ---------------------------------------------------------------------------

(deftest minimal-plugin-validates
  (is (plug/valid-plugin? minimal-plugin))
  (is (nil? (plug/explain-plugin minimal-plugin))))

(deftest full-plugin-validates
  (let [full {:plugin/spec-version 1
              :id                  :plugin/full
              :version             "1.0.0"
              :importer            (fn [_ _] nil)
              :exporter            (fn [_ _] nil)
              :matches?            (fn [_ _] true)
              :rules               [{:id :r/x :phase :validate :match [] :produce {}}]
              :mapping             [{:mapping/id :m/x}]
              :predicates          {'p (fn [_ _] true)}
              :transforms          {:t (fn [v] v)}
              :requires            #{:plugin/canonical}
              :input-format        :xml
              :output-format       :json-ld
              :doc                 "Test plugin"}]
    (is (plug/valid-plugin? full))))

(deftest plugin-rejects-missing-spec-version
  (is (not (plug/valid-plugin? {:id :plugin/x}))))

(deftest plugin-rejects-wrong-spec-version
  (is (not (plug/valid-plugin? {:plugin/spec-version 2 :id :plugin/x})))
  (is (not (plug/valid-plugin? {:plugin/spec-version "1" :id :plugin/x}))))

(deftest plugin-rejects-missing-id
  (is (not (plug/valid-plugin? {:plugin/spec-version 1}))))

(deftest plugin-rejects-non-keyword-id
  (is (not (plug/valid-plugin? {:plugin/spec-version 1 :id "plugin/x"})))
  (is (not (plug/valid-plugin? {:plugin/spec-version 1 :id 42}))))

(deftest plugin-rejects-unknown-keys
  (testing "closed schema catches typos like :rule (singular) instead of :rules"
    (is (not (plug/valid-plugin?
              (assoc minimal-plugin :rule [{:id :r/x}])))))
  (testing "closed schema catches arbitrary unsanctioned keys"
    (is (not (plug/valid-plugin?
              (assoc minimal-plugin :random-key "value"))))))

(deftest plugin-rejects-non-fn-importer
  (is (not (plug/valid-plugin?
            (assoc minimal-plugin :importer "not a fn")))))

(deftest plugin-rejects-non-set-requires
  (testing "requires must be a set, not a vector"
    (is (not (plug/valid-plugin?
              (assoc minimal-plugin :requires [:plugin/canonical]))))))

(deftest plugin-rejects-non-keyword-format
  (is (not (plug/valid-plugin?
            (assoc minimal-plugin :input-format "xml")))))

;; ---------------------------------------------------------------------------
;; Registry: register / unregister / lookup
;; ---------------------------------------------------------------------------

(deftest empty-registry-is-empty-map
  (is (= {} plug/empty-registry)))

(deftest register-adds-plugin
  (let [r (plug/register plug/empty-registry minimal-plugin)]
    (is (= minimal-plugin (plug/lookup r :plugin/minimal)))))

(deftest register-is-immutable
  (let [r1 plug/empty-registry
        r2 (plug/register r1 minimal-plugin)]
    (is (= {} r1) "original registry untouched")
    (is (not= r1 r2))))

(deftest register-rejects-malformed-plugin
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Invalid plugin"
                        (plug/register plug/empty-registry
                                       {:id :plugin/no-version}))))

(deftest register-rejects-duplicate-id
  (let [r (plug/register plug/empty-registry minimal-plugin)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Duplicate plugin id"
                          (plug/register r minimal-plugin)))))

(deftest unregister-removes-plugin
  (let [r1 (plug/register plug/empty-registry minimal-plugin)
        r2 (plug/unregister r1 :plugin/minimal)]
    (is (nil? (plug/lookup r2 :plugin/minimal)))))

(deftest unregister-is-noop-on-missing
  (is (= plug/empty-registry
         (plug/unregister plug/empty-registry :plugin/nonexistent))))

(deftest lookup-returns-nil-on-missing
  (is (nil? (plug/lookup plug/empty-registry :plugin/nonexistent))))

(deftest registered-ids-returns-set
  (let [r (-> plug/empty-registry
              (plug/register (make-importer-plugin :plugin/a))
              (plug/register (make-importer-plugin :plugin/b)))]
    (is (= #{:plugin/a :plugin/b} (plug/registered-ids r)))))

;; ---------------------------------------------------------------------------
;; Queries: all-rules / all-mappings
;; ---------------------------------------------------------------------------

(deftest all-rules-concatenates-across-plugins
  (let [r (-> plug/empty-registry
              (plug/register (assoc minimal-plugin
                                    :rules [{:id :r/a :phase :validate
                                             :match [] :produce {}}]))
              (plug/register (assoc minimal-plugin
                                    :id :plugin/b
                                    :rules [{:id :r/b :phase :normalize
                                             :match [] :produce {}}
                                            {:id :r/c :phase :infer
                                             :match [] :produce {}}])))]
    (is (= 3 (count (plug/all-rules r))))
    (is (= #{:r/a :r/b :r/c}
           (set (map :id (plug/all-rules r)))))))

(deftest all-mappings-concatenates-across-plugins
  (let [r (-> plug/empty-registry
              (plug/register (assoc minimal-plugin
                                    :mapping [{:mapping/id :m/a}]))
              (plug/register (assoc minimal-plugin
                                    :id :plugin/b
                                    :mapping [{:mapping/id :m/b}
                                              {:mapping/id :m/c}])))]
    (is (= 3 (count (plug/all-mappings r))))))

(deftest all-rules-empty-when-no-plugins-contribute
  (is (= [] (plug/all-rules plug/empty-registry))))

;; ---------------------------------------------------------------------------
;; Effective stdlib (ADR 0010)
;; ---------------------------------------------------------------------------

(deftest effective-stdlib-merges-predicates-across-plugins
  (let [r (-> plug/empty-registry
              (plug/register (assoc minimal-plugin
                                    :predicates {'foo? (fn [_ _] true)}))
              (plug/register (assoc minimal-plugin
                                    :id :plugin/b
                                    :predicates {'bar? (fn [_ _] false)})))
        {:keys [predicates]} (plug/effective-stdlib r)]
    (is (contains? predicates 'foo?))
    (is (contains? predicates 'bar?))
    (is (fn? (get predicates 'foo?)))
    (is (fn? (get predicates 'bar?)))))

(deftest effective-stdlib-merges-transforms-across-plugins
  (let [r (-> plug/empty-registry
              (plug/register (assoc minimal-plugin
                                    :transforms {:tx-a (fn [v] v)}))
              (plug/register (assoc minimal-plugin
                                    :id :plugin/b
                                    :transforms {:tx-b (fn [v] v)})))
        {:keys [transforms]} (plug/effective-stdlib r)]
    (is (contains? transforms :tx-a))
    (is (contains? transforms :tx-b))))

(deftest effective-stdlib-rejects-predicate-collision
  (let [r (-> plug/empty-registry
              (plug/register (assoc minimal-plugin
                                    :predicates {'shared? (fn [_ _] true)}))
              (plug/register (assoc minimal-plugin
                                    :id :plugin/b
                                    :predicates {'shared? (fn [_ _] false)})))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Predicate name collision"
                          (plug/effective-stdlib r)))))

(deftest effective-stdlib-rejects-transform-collision
  (let [r (-> plug/empty-registry
              (plug/register (assoc minimal-plugin
                                    :transforms {:tx (fn [v] v)}))
              (plug/register (assoc minimal-plugin
                                    :id :plugin/b
                                    :transforms {:tx (fn [v] (str v))})))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Transform name collision"
                          (plug/effective-stdlib r)))))

(deftest effective-stdlib-returns-plain-maps
  (testing "no metadata leaks from the internal source-tracking"
    (let [r (plug/register plug/empty-registry
                           (assoc minimal-plugin
                                  :predicates {'p (fn [_ _] true)}
                                  :transforms {:t (fn [v] v)}))
          {:keys [predicates transforms]} (plug/effective-stdlib r)]
      (is (nil? (meta predicates)))
      (is (nil? (meta transforms))))))

(deftest effective-stdlib-empty-registry-still-has-core
  (testing "core predicate stdlib is always present"
    (let [{:keys [predicates]} (plug/effective-stdlib plug/empty-registry)]
      (is (contains? predicates 'absent?))
      (is (contains? predicates 'present?))
      (is (contains? predicates '=))))
  (testing "core transform stdlib is always present"
    (let [{:keys [transforms]} (plug/effective-stdlib plug/empty-registry)]
      (is (= #{:trim :lowercase :uppercase
               :parse-int :parse-double :parse-iso-date}
             (set (keys transforms)))))))

(deftest effective-stdlib-merges-core-and-plugins
  (let [r (plug/register plug/empty-registry
                         (assoc minimal-plugin
                                :predicates {'plugin-pred? (fn [_ _] true)}
                                :transforms {:plugin-tx (fn [v] v)}))
        {:keys [predicates transforms]} (plug/effective-stdlib r)]
    (is (contains? predicates 'absent?)
        "core predicates still present")
    (is (contains? predicates 'plugin-pred?)
        "plugin predicates merged in")
    (is (contains? transforms :trim)
        "core transforms still present")
    (is (contains? transforms :plugin-tx)
        "plugin transforms merged in")))

(deftest effective-stdlib-rejects-plugin-overriding-core-predicate
  (let [r (plug/register plug/empty-registry
                         (assoc minimal-plugin
                                :predicates {'absent? (fn [_ _] true)}))]
    (try
      (plug/effective-stdlib r)
      (is false "should have thrown on core/plugin predicate collision")
      (catch clojure.lang.ExceptionInfo ex
        (is (re-find #"Predicate name collision" (ex-message ex)))
        (is (= {'absent? :core} (:already-from (ex-data ex))))))))

(deftest effective-stdlib-rejects-plugin-overriding-core-transform
  (let [r (plug/register plug/empty-registry
                         (assoc minimal-plugin
                                :transforms {:trim (fn [v] v)}))]
    (try
      (plug/effective-stdlib r)
      (is false "should have thrown on core/plugin transform collision")
      (catch clojure.lang.ExceptionInfo ex
        (is (re-find #"Transform name collision" (ex-message ex)))
        (is (= {:trim :core} (:already-from (ex-data ex))))))))

(deftest effective-transforms-accessor
  (let [r (plug/register plug/empty-registry
                         (assoc minimal-plugin
                                :transforms {:tx (fn [v] v)}))]
    (is (contains? (plug/effective-transforms r) :tx))
    (is (contains? (plug/effective-transforms r) :trim))))

(deftest transform-source-identifies-contributor
  (let [r (plug/register plug/empty-registry
                         (assoc minimal-plugin
                                :id :plugin/p1
                                :transforms {:plugin-tx (fn [v] v)}))]
    (is (= :core      (plug/transform-source r :trim)))
    (is (= :plugin/p1 (plug/transform-source r :plugin-tx)))
    (is (nil?         (plug/transform-source r :unknown)))))

;; ---------------------------------------------------------------------------
;; Spoke dispatch (the production path) lives in `palomar.spokes` (a plain
;; `format → plugin` map) and `palomar.convert` / `palomar.validate`, not in
;; this registry; there is no dispatch surface to unit-test here.
;; ---------------------------------------------------------------------------

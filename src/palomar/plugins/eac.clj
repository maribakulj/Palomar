(ns palomar.plugins.eac
  "EAC-CPF importer spoke (ADR 0045): an archival authority record — a person,
   a corporate body or a family — read as a RiC-O agent.

   An EAC-CPF record describes an agent, not a resource, so it does not go
   through the canonical floor or the WEMI projection: it keeps its natives
   (`:eac/*`) and projects to a `rico:Person`, `rico:CorporateBody` or
   `rico:Family` (`entityType`), with:
   - its names (`rico:name`), the authorized one first;
   - its existence dates (`rico:date`, as transcribed);
   - its history (`rico:history`, the `biogHist` text);
   - its record id (`rico:identifier`).

   Its `entityId`, when it is an IRI, is the agent's IRI: identity by an
   authority identifier (ADR 0041). A local `recordId` names the record, not
   the agent. Its relations are kept as natives and reported as loss: their
   targets in the samples are file names, not IRIs, and RiC-O's relation
   types need their own mapping. Every other description element is reported
   too. EAC-CPF 1.x and 2.0 are both read, by local name.

   The record's id comes from its maintenance agency and `recordId`, encoded
   as the EAD ids are (`model/encode-name`, ADR 0044)."
  (:require [clojure.string :as str]
            [palomar.diagnostics :as dx]
            [palomar.model :as model]
            [palomar.plugins.lrmoo.ntriples :as nt]
            [palomar.rules :as rules]
            [palomar.runtime :as runtime]
            [palomar.xml :as rx]))

(defn- local [tag] (when tag (name tag)))

(defn- children
  ([el] (filter map? (:content el)))
  ([el nm] (filter #(= nm (local (:tag %))) (children el))))

(defn- child [el nm] (first (children el nm)))

(defn- text [el]
  (some-> (str/join " " (map str/trim (filter string? (tree-seq map? :content el))))
          (str/replace #"\s+" " ") str/trim not-empty))

(defn- attr [el nm] (some (fn [[k v]] (when (= nm (name k)) v)) (:attrs el)))

(def read-elements
  "The elements of `cpfDescription` the importer reads."
  #{"identity" "description" "relations"})

(def read-description
  "The elements of `description` the importer reads."
  #{"existDates" "biogHist"})

(defn importer
  "ADR 0007 importer: one record per EAC-CPF document (see the ns doc)."
  [_opts source]
  (let [s       (if (string? source) source (slurp source))
        root    (rx/parse-str s)
        _       (when-not (#{"eac" "eac-cpf"} (local (:tag root)))
                  (throw (ex-info "eac: not an EAC-CPF document" {:root (local (:tag root))})))
        control (child root "control")
        agency  (or (text (child (child control "maintenanceAgency") "agencyCode")) "")
        rec-id  (text (child control "recordId"))
        rid     (keyword "eac" (str/join "_-" (map model/encode-name
                                                   (if rec-id [agency rec-id] ["" (str "h" (model/sha256-hex s 12))]))))
        cpf     (child root "cpfDescription")
        ident   (child cpf "identity")
        desc    (child cpf "description")
        ;; an absolute XPath from the root element read (`eac` in 2.0,
        ;; `eac-cpf` in 1.x); a step is a name (its first occurrence) or
        ;; [name n] (its n-th, 1-based)
        x       (fn [& steps] [{:selector {:type  :xpath
                                           :value (apply str (for [st (cons (local (:tag root)) steps)
                                                                   :let [[nm i] (if (vector? st) st [st 1])]]
                                                               (str "/*[local-name()='" nm "'][" i "]")))}}])
        nat     (fn [p v ev] (when v (model/assertion {:subject rid :predicate p :value v
                                                       :provenance (model/provenance {:pass :ingest :evidence ev})})))
        ;; each name entry with the path to it, a parallel one's inside its group
        entries (concat (map-indexed (fn [i n] [n ["cpfDescription" "identity" ["nameEntry" (inc i)]]])
                                     (children ident "nameEntry"))
                        (apply concat (map-indexed (fn [j par]
                                                     (map-indexed (fn [k n] [n ["cpfDescription" "identity" ["nameEntryParallel" (inc j)]
                                                                                ["nameEntry" (inc k)]]])
                                                                  (children par "nameEntry")))
                                                   (children ident "nameEntryParallel"))))
        named   (for [[n path] entries
                      :let [parts (children n "part")
                            t     (if (seq parts) (str/join ", " (keep text parts)) (text n))]
                      :when t]
                  {:name t :path path :authorized (or (= "authorized" (attr n "status")) (seq (children n "authorizedForm")))})
        ;; the authorized names first; a name given twice is attested where it first is
        names   (let [ordered (concat (filter :authorized named) (remove :authorized named))
                      firsts  (reduce (fn [m e] (if (contains? m (:name e)) m (assoc m (:name e) e))) {} ordered)]
                  (map firsts (distinct (map :name ordered))))
        rel-els (children (child cpf "relations"))
        ;; each relation element's rank among its same-named siblings, for its XPath
        rel-nth (map (fn [i r] (inc (count (filter #(= (local (:tag r)) (local (:tag %))) (take i rel-els)))))
                     (range) rel-els)
        rels    (for [r rel-els
                      :let [target (or (child r "targetEntity") r)]]
                  (model/structured (cond-> {:element (local (:tag r))}
                                      (attr target "valueURI")            (assoc :target (attr target "valueURI"))
                                      (text target)                       (assoc :label (text target))
                                      (attr (child r "relationType") "valueURI") (assoc :type (attr (child r "relationType") "valueURI")))))
        as      (remove nil?
                        (concat
                         [(nat :eac/record-id rec-id (x "control" "recordId"))
                          (let [et (child ident "entityType")]
                            (if-let [v (attr et "value")]
                              (nat :eac/entity-type v (update-in (x "cpfDescription" "identity" "entityType") [0 :selector :value] str "/@value"))
                              (nat :eac/entity-type (text et) (x "cpfDescription" "identity" "entityType"))))
                          (nat :eac/entity-id (text (child ident "entityId")) (x "cpfDescription" "identity" "entityId"))
                          (nat :eac/exist-dates (text (child desc "existDates")) (x "cpfDescription" "description" "existDates"))
                          (nat :eac/biog-hist (text (child desc "biogHist")) (x "cpfDescription" "description" "biogHist"))]
                         (map-indexed (fn [i {n :name path :path}]
                                        (nat (if (zero? i) :eac/authorized-name :eac/name) n (apply x path)))
                                      names)
                         (map (fn [rel n] (nat :eac/relation rel (x "cpfDescription" "relations" [(:element (:value/fields rel)) n])))
                              rels rel-nth)))
        unread  (distinct (concat (remove read-elements (map (comp local :tag) (children cpf)))
                                  (remove read-description (map (comp local :tag) (children desc)))))]
    {:records     [(model/record {:id rid :kind :archival-authority :assertions (vec as)})]
     :diagnostics (vec (concat
                        (for [u (sort unread)]
                          (dx/loss {:category :dropped :subject rid :edge :import :source-field (keyword "eac" u)
                                    :message (str u " is not read by the EAC-CPF importer (ADR 0045)")}))
                        (when (seq rels)
                          [(dx/loss {:category :dropped :subject rid :edge :import :source-field :eac/relation
                                     :aspect :relation
                                     :message (str (count rels) " relation(s) kept as natives but not projected: RiC-O"
                                                   " relation types need their own mapping (ADR 0045)")})])))}))

(def plugin
  "The EAC-CPF importer plugin (ADR 0007): natives only, no canonical mapping."
  {:plugin/spec-version 1
   :id                  :palomar/eac-cpf
   :input-format        :xml
   :mapping             []
   :importer            importer
   :doc                 "EAC-CPF authority records — persons, corporate bodies and families as RiC-O agents."})

;; --- projection: a RiC-O agent (ADR 0045) -----------------------------------------

(def ^:private agent-kinds
  {"person" :rico/Person "corporateBody" :rico/CorporateBody "family" :rico/Family})

(defn- runner
  [record]
  (let [rid   (:id record)
        as    (remove model/out-of-force? (:assertions record))
        vals  (fn [p] (filter #(and (= p (:predicate %)) (string? (:value %))) as))
        kind  (get agent-kinds (some :value (vals :eac/entity-type)) :rico/Agent)
        eid   (some :value (vals :eac/entity-id))
        iri   (when (and eid (nil? (nt/iri-problem eid))) eid)
        ag    (model/mint-entity-id kind (if iri iri [(str rid)]))
        prov  (fn [a] (cond-> {:pass :infer :rule :rule.eac/project :derivation [rid]}
                        (seq (model/evidence-of a)) (assoc :evidence (model/evidence-of a))))
        claim (fn [p a] {:kind :assertion :value (model/assertion {:subject ag :predicate p :value (:value a) :status :asserted
                                                                   :provenance (model/provenance (prov a))})})]
    (concat
     [{:kind :entity :value (model/entity (cond-> {:id ag :kind kind :provenance (model/provenance (prov nil))}
                                            iri (assoc :iri iri)))}]
     (map #(claim :rico/name %) (concat (vals :eac/authorized-name) (vals :eac/name)))
     (map #(claim :rico/identifier %) (vals :eac/record-id))
     (map #(claim :rico/date %) (vals :eac/exist-dates))
     (map #(claim :rico/history %) (vals :eac/biog-hist)))))

(def rule
  "The compiled `:infer` projection of an authority record (ADR 0045)."
  (rules/compiled-rule {:id :rule.eac/project :phase :infer :runner (comp vec runner)}))

(defn project
  "`record` with its RiC-O agent."
  [record]
  (:record (runtime/run-phase record [rule] :infer)))

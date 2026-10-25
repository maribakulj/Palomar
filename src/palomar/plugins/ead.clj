(ns palomar.plugins.ead
  "Encoded Archival Description importer spoke (ADR 0039): EAD 2002 and EAD3
   finding aids, one record per unit of description.

   A finding aid describes a fonds and, nested in its `<dsc>`, its series,
   files and items (`<c>` or `<c01>`…`<c12>`). Each unit — the `<archdesc>` and
   every component — becomes a record, so each can be converted, curated and
   linked on its own:

   | EAD                                         | native            | canonical            |
   |---------------------------------------------|-------------------|----------------------|
   | `did/unittitle`                             | `:ead/unittitle`  | `:canon/title`       |
   | `did/unitdate`, `did/unitdatestructured`    | `:ead/unitdate`   | `:canon/date`        |
   | `did/unitid`                                | `:ead/unitid`     | `:canon/identifier`  |
   | `did/origination` (its names)               | `:ead/origination`| `:canon/agent`, role `:creator` |
   | `did/abstract`, `scopecontent`              | `:ead/abstract`, `:ead/scopecontent` | `:canon/note`, kind `:abstract` |
   | `did/dao/@href`, `dao/@xlink:href`          | `:ead/dao`        | `:canon/digital-object` |
   | `@level`                                    | `:ead/level`      | —                    |
   | the enclosing unit                          | `:ead/parent`     | —                    |

   Every other descriptive element of a unit (`controlaccess`, `bioghist`,
   `physdesc`, `accessrestrict`, `container`, …) is reported as import loss,
   never dropped silently. The namespaces of EAD 2002 and EAD3 are both read,
   and so is a document with none.

   A unit's id is `:ead/d<digest>-<path>`: twelve hex digits of the document's
   SHA-256 and the unit's position (`0` the archdesc, `0-2-1` its second
   component's first child). A finding aid's own identifier (`eadid`,
   `recordid`) is often empty or reused (`1.1` in all three EAD3 samples), so it
   cannot name the unit alone.

   Archival units are not editions: `project` gives each unit a Manifestation
   that `P106i_forms_part_of` its parent's, and mints no Expression or Work
   (ADR 0039)."
  (:require [clojure.string :as str]
            [palomar.diagnostics :as dx]
            [palomar.model :as model]
            [palomar.runtime :as runtime]
            [palomar.rules :as rules]
            [palomar.xml :as rx]))

(defn- local [tag] (when tag (name tag)))

(defn- children
  ([el] (filter map? (:content el)))
  ([el nm] (filter #(= nm (local (:tag %))) (children el))))

(defn- child [el nm] (first (children el nm)))

(defn- text
  "The text of `el` and its descendants, whitespace collapsed; `<part>`s and
   `<lb/>`s are separated."
  [el]
  (letfn [(walk [n] (cond (string? n) [n]
                          (map? n)    (cond-> (vec (mapcat walk (:content n)))
                                        (#{"part" "lb" "p"} (local (:tag n))) (conj " "))
                          :else       []))]
    (some-> (str/join (walk el)) (str/replace #"\s+" " ") str/trim not-empty)))

(defn- attr [el nm] (some (fn [[k v]] (when (= nm (name k)) v)) (:attrs el)))

(def ^:private component?
  (let [cs (into #{"c"} (map #(format "c%02d" %) (range 1 13)))]
    #(contains? cs (local (:tag %)))))

(defn- components
  "The components directly under unit `el`, each as `[component xpath-suffix]`:
   in its `<dsc>` (archdesc) or among its own children (a component); the
   suffix locates the component from `el`."
  [el]
  (let [indexed (fn [parent prefix]
                  (let [cs (filter component? (children parent))]
                    (for [c cs
                          :let [same (filter #(= (:tag c) (:tag %)) cs)
                                i    (count (take-while #(not (identical? c %)) same))]]
                      [c (str prefix "/*[local-name()='" (local (:tag c)) "'][" (inc i) "]")])))]
    (if (= "archdesc" (local (:tag el)))
      (mapcat (fn [[i dsc]] (indexed dsc (str "/*[local-name()='dsc'][" (inc i) "]")))
              (map-indexed vector (children el "dsc")))
      (indexed el ""))))

(def described
  "The elements of a unit the importer reads (`did` children and the unit's own)."
  #{"unittitle" "unitdate" "unitdatestructured" "unitid" "origination" "abstract" "dao" "daoset"
    "scopecontent" "dsc" "did" "head"})

(defn- unit-date [did]
  (or (text (child did "unitdate"))
      (when-let [s (child did "unitdatestructured")]
        (let [r (child s "daterange")]
          (or (when r (not-empty (str/join "/" (keep #(text (child r %)) ["fromdate" "todate"]))))
              (text (child s "datesingle"))
              (text s))))))

(defn- dao-hrefs [did]
  (concat (keep #(attr % "href") (children did "dao"))
          (keep #(attr % "href") (mapcat #(children % "dao") (children did "daoset")))))

(defn- unit-record
  "The record of unit `el` at `path` (its position) under parent record `parent`."
  [doc-key el path xpath parent]
  (let [rid    (keyword "ead" (str "d" doc-key "-" (str/join "-" path)))
        did    (child el "did")
        ev     (fn [sub] [{:selector {:type :xpath :value (str xpath sub)}}])
        nat    (fn [p v sub] (when v (model/assertion {:subject rid :predicate p :value v
                                                       :provenance (model/provenance {:pass :ingest :evidence (ev sub)})})))
        did-x  "/*[local-name()='did'][1]"
        names  (for [o (children did "origination")
                     n (let [ns (children o)] (if (seq ns) ns [o]))
                     :let [t (text n)] :when t]
                 t)
        notes  (fn [nm] (keep text (children el nm)))
        as     (remove nil?
                       (concat
                        [(nat :ead/unittitle (text (child did "unittitle")) (str did-x "/*[local-name()='unittitle'][1]"))
                         (nat :ead/unitdate (unit-date did) did-x)
                         (nat :ead/unitid (text (child did "unitid")) (str did-x "/*[local-name()='unitid'][1]"))
                         (nat :ead/abstract (text (child did "abstract")) (str did-x "/*[local-name()='abstract'][1]"))
                         (nat :ead/level (attr el "level") "/@level")
                         (when parent (model/assertion {:subject rid :predicate :ead/parent :value parent
                                                        :provenance (model/provenance {:pass :ingest})}))]
                        (map #(nat :ead/origination % (str did-x "/*[local-name()='origination']")) (distinct names))
                        (map #(nat :ead/scopecontent % "/*[local-name()='scopecontent']") (notes "scopecontent"))
                        (map #(nat :ead/dao % did-x) (distinct (dao-hrefs did)))))
        unread (distinct (remove (into described (map (comp local :tag) (filter component? (children el))))
                                 (concat (map (comp local :tag) (children el))
                                         (map (comp local :tag) (children did)))))]
    {:record (model/record {:id rid :kind :archival-unit :source {:ead/path path}
                            :assertions (vec as)})
     :losses (for [u (sort unread)]
               (dx/loss {:category :dropped :subject rid :edge :import :source-field (keyword "ead" u)
                         :message (str u " is not read by the EAD importer (ADR 0039)")}))}))

(defn importer
  "ADR 0007 importer: `(fn [opts source] -> {:records [...] :diagnostics [...]})`,
   one record per unit of the finding aid `source` (see the ns doc)."
  [_opts source]
  (let [s       (if (string? source) source (slurp source))
        root    (rx/parse-str s)
        _       (when-not (= "ead" (local (:tag root)))
                  (throw (ex-info "ead: not an EAD document" {:root (local (:tag root))})))
        doc-key (model/sha256-hex s 12)
        arch    (child root "archdesc")
        units   (fn units [el path xpath parent]
                  (let [{:keys [record] :as u} (unit-record doc-key el path xpath parent)]
                    (cons u (apply concat
                                   (map-indexed (fn [i [c suffix]] (units c (conj path i) (str xpath suffix) (:id record)))
                                                (components el))))))]
    (when-not arch (throw (ex-info "ead: no archdesc" {})))
    (let [us (units arch [0] "/*[local-name()='ead'][1]/*[local-name()='archdesc'][1]" nil)]
      {:records     (mapv :record us)
       :diagnostics (vec (mapcat :losses us))})))

(def mapping
  "EAD natives → the canonical floor (see the ns doc)."
  [{:mapping/id :map/ead-title :mapping/from :ead/unittitle :mapping/to :canon/title}
   {:mapping/id :map/ead-date :mapping/from :ead/unitdate :mapping/to :canon/date}
   {:mapping/id :map/ead-unitid :mapping/from :ead/unitid :mapping/to :canon/identifier}
   {:mapping/id :map/ead-origination :mapping/from :ead/origination :mapping/to :canon/agent
    :mapping/qualifier {:value :creator :as :canon/role}}
   {:mapping/id :map/ead-abstract :mapping/from :ead/abstract :mapping/to :canon/note
    :mapping/qualifier {:value :abstract :as :canon/note-kind}}
   {:mapping/id :map/ead-scopecontent :mapping/from :ead/scopecontent :mapping/to :canon/note
    :mapping/qualifier {:value :abstract :as :canon/note-kind}}
   {:mapping/id :map/ead-dao :mapping/from :ead/dao :mapping/to :canon/digital-object}])

(def plugin
  "The EAD importer plugin (ADR 0007)."
  {:plugin/spec-version 1
   :id                  :palomar/ead
   :input-format        :xml
   :mapping             mapping
   :importer            importer
   :doc                 "EAD 2002 / EAD3 finding aids — one record per unit of description."})

;; --- projection (ADR 0039) ----------------------------------------------------

(defn- manifestation-of [rid] (model/mint-entity-id :lrmoo/F3_Manifestation (str rid)))

(defn- runner
  "A unit's Manifestation, its title (`:asserted`, transcription), the link to
   its parent's Manifestation (`:asserted`, the finding aid's own structure),
   and a `:projection` loss for each canonical field the view leaves out."
  [record]
  (let [rid    (:id record)
        manif  (manifestation-of rid)
        prov   (model/provenance {:pass :infer :rule :rule.ead/project :derivation [rid]})
        title  (first (filter #(and (= :canon/title (:predicate %)) (string? (:value %))) (:assertions record)))
        parent (some #(when (= :ead/parent (:predicate %)) (:value %)) (:assertions record))
        claim  (fn [s p v pv] {:kind :assertion :value (model/assertion {:subject s :predicate p :value v
                                                                         :status :asserted :provenance pv})})
        entity (fn [id] {:kind :entity :value (model/entity {:id id :kind :lrmoo/F3_Manifestation :provenance prov})})]
    (concat
     [(entity manif)]
     (when title
       [(claim manif :lrmoo/R33_has_string (:value title)
               (cond-> prov (seq (model/evidence-of title)) (assoc :evidence (model/evidence-of title))))])
     (when parent
       [(entity (manifestation-of parent))
        (claim manif :crm/P106i_forms_part_of (model/reference (manifestation-of parent)) prov)])
     (for [p (distinct (keep #(when (and (= "canon" (namespace (:predicate %))) (not= :canon/title (:predicate %)))
                                (:predicate %))
                             (:assertions record)))
           :when (not (#{:canon/role :canon/note-kind :canon/lang} p))]
       {:kind  :diagnostic
        :value (dx/loss {:category :dropped :subject rid :edge :projection :source-field p
                         :message (str (name p) " not represented in the archival view (no Work or Expression, ADR 0039)")})}))))

(def rule
  "The compiled `:infer` projection of an archival unit (ADR 0039)."
  (rules/compiled-rule {:id :rule.ead/project :phase :infer :runner (comp vec runner)}))

(defn project
  "`record` with its archival view: a Manifestation part of its parent's."
  [record]
  (:record (runtime/run-phase record [rule] :infer)))

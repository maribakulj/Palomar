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

   A unit's id comes from the source's own identifiers (ADR 0044): the
   finding aid's maintenance agency and `recordid`/`eadid`, and the unit's
   `unitid` — with its rank from the second unit sharing it on, so adding a
   duplicate moves no existing id — else its position (`0` the archdesc,
   `0-2-1` its second component's first child). So editing a note changes no
   id. A finding aid with no identifier at all is named by its content's
   digest instead. Every id that is not durable is reported (ADR 0047).

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
    "scopecontent" "dsc" "did" "head" "bioghist" "accessrestrict" "physdesc" "physdescset" "physdescstructured"})

(defn- step [nm i] (str "/*[local-name()='" nm "'][" (inc i) "]"))

(defn- unit-date
  "The unit's date and the path, under its `did`, to the element it was read
   from: `unitdate`, or a `unitdatestructured` range (`from/to`) or single date."
  [did]
  (if-let [t (text (child did "unitdate"))]
    [t (step "unitdate" 0)]
    (when-let [s (child did "unitdatestructured")]
      (let [r  (child s "daterange")
            sx (step "unitdatestructured" 0)]
        (or (when-let [v (when r (not-empty (str/join "/" (keep #(text (child r %)) ["fromdate" "todate"]))))]
              [v (str sx (step "daterange" 0))])
            (when-let [v (text (child s "datesingle"))] [v (str sx (step "datesingle" 0))])
            (when-let [v (text s)] [v sx]))))))

(defn- dao-hrefs
  "`[href path]` for each digital object of `did`, the path to its `@href`."
  [did]
  (concat (keep-indexed (fn [i d] (when-let [h (attr d "href")] [h (str (step "dao" i) "/@href")])) (children did "dao"))
          (apply concat (map-indexed (fn [j set]
                                       (keep-indexed (fn [i d] (when-let [h (attr d "href")]
                                                                 [h (str (step "daoset" j) (step "dao" i) "/@href")]))
                                                     (children set "dao")))
                                     (children did "daoset")))))

(defn- physdesc
  "`[text path]` for each physical description of `did`: an EAD 2002 or EAD3
   `physdesc`, or an EAD3 `physdescstructured` (its quantity and unit type),
   alone or in a `physdescset`."
  [did]
  (let [structured (fn [el] (some->> (keep #(text (child el %)) ["quantity" "unittype"]) seq (str/join " ")))]
    (concat
     (keep-indexed (fn [i el] (when-let [t (text el)] [t (step "physdesc" i)])) (children did "physdesc"))
     (keep-indexed (fn [i el] (when-let [t (structured el)] [t (step "physdescstructured" i)])) (children did "physdescstructured"))
     (apply concat (map-indexed (fn [j set]
                                  (keep-indexed (fn [i el] (when-let [t (structured el)]
                                                             [t (str (step "physdescset" j) (step "physdescstructured" i))]))
                                                (children set "physdescstructured")))
                                (children did "physdescset"))))))

(defn- unit-record
  "The record `rid` of unit `el`, at `xpath` in the document, under parent
   record `parent`."
  [rid el xpath parent]
  (let [did    (child el "did")
        ev     (fn [sub] [{:selector {:type :xpath :value (str xpath sub)}}])
        nat    (fn [p v sub] (when v (model/assertion {:subject rid :predicate p :value v
                                                       :provenance (model/provenance {:pass :ingest :evidence (ev sub)})})))
        did-x  "/*[local-name()='did'][1]"
        ;; each creator name with its element and the path to it
        typed  (apply concat
                      (map-indexed (fn [i o]
                                     (let [ns (children o)]
                                       (if (seq ns)
                                         (keep-indexed (fn [k n] (when-let [t (text n)]
                                                                   [t (local (:tag n)) (str did-x (step "origination" i) "/*[" (inc k) "]")]))
                                                       ns)
                                         (when-let [t (text o)] [[t "origination" (str did-x (step "origination" i))]]))))
                                   (children did "origination")))
        ;; the first occurrence of each distinct value, in document order
        first-at (fn [k xs] (let [seen (volatile! #{})]
                              (filter #(let [v (k %)] (when-not (@seen v) (vswap! seen conj v))) xs)))
        names  (first-at first typed)
        as     (remove nil?
                       (concat
                        [(nat :ead/unittitle (text (child did "unittitle")) (str did-x "/*[local-name()='unittitle'][1]"))
                         (let [[v sub] (unit-date did)] (nat :ead/unitdate v (str did-x sub)))
                         (nat :ead/unitid (text (child did "unitid")) (str did-x "/*[local-name()='unitid'][1]"))
                         (nat :ead/abstract (text (child did "abstract")) (str did-x "/*[local-name()='abstract'][1]"))
                         (nat :ead/level (attr el "level") "/@level")
                         (when parent (model/assertion {:subject rid :predicate :ead/parent :value parent
                                                        :provenance (model/provenance {:pass :ingest})}))]
                        (map (fn [[t _ x]] (nat :ead/origination t x)) names)
                        ;; the creator with the kind its element states (persname,
                        ;; corpname, famname), for the RiC-O view (ADR 0045)
                        (map (fn [[t tag x]] (nat :ead/creator (model/structured {:name t :element tag}) x))
                             (first-at (juxt first second) typed))
                        (keep-indexed (fn [i n] (when-let [t (text n)] (nat :ead/scopecontent t (step "scopecontent" i))))
                                      (children el "scopecontent"))
                        (map (fn [[h x]] (nat :ead/dao h (str did-x x))) (first-at first (dao-hrefs did)))
                        ;; notes on the unit, without their heading (post-audit 3, E3)
                        (for [nm ["bioghist" "accessrestrict"]
                              [i n] (map-indexed vector (children el nm))
                              :let [t (some->> (remove #(= "head" (local (:tag %))) (children n)) (keep text) seq (str/join " "))]
                              :when t]
                          (nat (keyword "ead" nm) t (step nm i)))
                        (for [[v x] (physdesc did)] (nat :ead/physdesc v (str did-x x)))))
        unread (distinct (remove (into described (map (comp local :tag) (filter component? (children el))))
                                 (concat (map (comp local :tag) (children el))
                                         (map (comp local :tag) (children did)))))]
    {:record (model/record {:id rid :kind :archival-unit :source (name rid)
                            :assertions (vec as)})
     :losses (for [u (sort unread)]
               (dx/loss {:category :dropped :subject rid :edge :import :source-field (keyword "ead" u)
                         :message (str u " is not read by the EAD importer (ADR 0039)")}))}))

(defn- finding-aid-id
  "The finding aid's own identity, `[agency id]`: its maintenance agency code
   and its `recordid` (EAD3) or `eadid` (EAD 2002), else nil."
  [root]
  (let [control (or (child root "control") (child root "eadheader"))
        eadid   (child control "eadid")
        id      (or (text (child control "recordid")) (text eadid))
        agency  (or (text (child (child control "maintenanceagency") "agencycode"))
                    (attr eadid "mainagencycode") (attr eadid "countrycode") "")]
    (when id [agency id])))

(defn- unit-id
  "A unit's record id (ADR 0044, ADR 0047): the finding aid's identity and the
   unit's `unitid` — never a digest of the document, so editing a note changes
   no id (post-audit test 10). A `unitid` the document gives `rank` (1-based,
   in document order) units carries that rank from the second on, so the
   first keeps the id it had before a duplicate was added. A unit with no
   `unitid` is named by its position. Each part is `model/encode-name`d and
   joined by `_-`, which no encoded part holds."
  [doc uid rank path]
  (let [unit (cond (nil? uid) [(str "p" (str/join "-" path))]
                   (= 1 rank) [(str "u" (model/encode-name uid))]
                   :else      [(str "u" (model/encode-name uid)) (str rank)])]
    (keyword "ead" (str/join "_-" (concat (map model/encode-name doc) unit)))))

(defn- unit-id-losses
  "An `:under-specified` loss for each unit whose id is not durable (ADR 0047):
   one with no `unitid`, named by its position, and every unit of a `unitid`
   several share, the first included: its id holds only while no unit with the
   same `unitid` is inserted before it (post-audit 4, F6)."
  [units]
  (let [shared (set (keep #(when (< 1 (:rank %)) (:uid %)) units))]
    (for [{:keys [id uid rank]} units
          :when (or (nil? uid) (shared uid))]
      (dx/loss {:category :under-specified :subject id :edge :import :source-field :ead/unitid
                :message (cond
                           (nil? uid) "the unit states no unitid: it is named by its position and changes if the units are reordered"
                           (= 1 rank) (str "unitid " (pr-str uid) " is not unique in the finding aid: this unit, the first with it,"
                                           " keeps its id only while no unit with the same unitid is inserted before it")
                           :else (str "unitid " (pr-str uid) " is not unique in the finding aid: this unit is named by its rank ("
                                      rank ") among the units sharing it, and changes if they are reordered"))}))))

(defn importer
  "ADR 0007 importer: `(fn [opts source] -> {:records [...] :diagnostics [...]})`,
   one record per unit of the finding aid `source` (see the ns doc)."
  [_opts source]
  (let [s       (if (string? source) source (slurp source))
        root    (rx/parse-str s)
        _       (when-not (= "ead" (local (:tag root)))
                  (throw (ex-info "ead: not an EAD document" {:root (local (:tag root))})))
        arch    (child root "archdesc")
        _       (when-not arch (throw (ex-info "ead: no archdesc" {})))
        walk    (fn walk [el path xpath parent-path]
                  (cons {:el el :path path :xpath xpath :parent parent-path}
                        (apply concat (map-indexed (fn [i [c suffix]] (walk c (conj path i) (str xpath suffix) path))
                                                   (components el)))))
        units   (vec (walk arch [0] "/*[local-name()='ead'][1]/*[local-name()='archdesc'][1]" nil))
        fa-id   (finding-aid-id root)
        ;; with no identifier of its own, the finding aid is named by its
        ;; content: its unit ids then change whenever it does, and it says so
        doc     (or fa-id ["" (str "h" (model/sha256-hex s 12))])
        ranked  (second (reduce (fn [[seen out] u]
                                  (let [uid  (text (child (child (:el u) "did") "unitid"))
                                        rank (inc (get seen uid 0))]
                                    [(if uid (assoc seen uid rank) seen)
                                     (conj out (assoc u :uid uid :rank rank :id (unit-id doc uid rank (:path u))))]))
                                [{} []] units))
        ids     (into {} (map (juxt :path :id)) ranked)
        us      (for [u units] (unit-record (ids (:path u)) (:el u) (:xpath u) (some-> (:parent u) ids)))]
    {:records     (mapv :record us)
     :diagnostics (cond-> (into (vec (mapcat :losses us)) (unit-id-losses ranked))
                    (nil? fa-id)
                    (conj (dx/loss {:category :under-specified :subject (ids [0]) :edge :import
                                    :source-field :ead/recordid
                                    :message (str "the finding aid states no recordid or eadid: its units are named by"
                                                  " the document's digest, and change whenever it does")})))}))

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

;; --- projection: RiC-O (ADR 0045) ----------------------------------------------

(def rico-base "https://www.ica.org/standards/RiC/ontology#")
(def ^:private rst-base "https://www.ica.org/standards/RiC/vocabularies/recordSetTypes#")

(def record-set-types
  "EAD `@level` → the RiC-O record set type (`ric-rst:`); `item` is a Record,
   any other level a RecordSet of no stated type."
  {"fonds" "Fonds" "subfonds" "Fonds" "recordgrp" "Fonds" "subgrp" "Fonds"
   "series" "Series" "subseries" "Series" "file" "File" "collection" "Collection"})

(def ^:private agent-kinds
  {"persname" :rico/Person "corpname" :rico/CorporateBody "famname" :rico/Family})

(defn- unit-of
  "The RiC-O node of unit record `rid`: a Record for an item, else a RecordSet."
  [rid level]
  (let [kind (if (= "item" level) :rico/Record :rico/RecordSet)]
    [(model/mint-entity-id kind [(str rid)]) kind]))

(defn- runner
  "A unit's RiC-O view (ADR 0045): its Record or RecordSet, with its title,
   identifier, date and scope as transcribed, its record set type, the agents
   that created it, and its inclusion in its parent's RecordSet — all
   `:asserted`, the finding aid's own statements; and a `:projection` loss for
   each canonical field the view leaves out."
  [record]
  (let [rid    (:id record)
        as     (:assertions record)
        firsts (fn [p] (filter #(and (= p (:predicate %)) (not (model/out-of-force? %))) as))
        level  (some :value (firsts :ead/level))
        [u k]  (unit-of rid level)
        parent (some :value (firsts :ead/parent))
        prov   (fn [a] (cond-> {:pass :infer :rule :rule.ead/project :derivation [rid]}
                         (seq (model/evidence-of a)) (assoc :evidence (model/evidence-of a))))
        claim  (fn [s p v a] {:kind :assertion :value (model/assertion {:subject s :predicate p :value v :status :asserted
                                                                        :provenance (model/provenance (prov a))})})
        entity (fn [id kind & [iri]] {:kind :entity :value (model/entity (cond-> {:id id :kind kind
                                                                                  :provenance (model/provenance (prov nil))}
                                                                           iri (assoc :iri iri)))})
        copy   (fn [from to] (for [a (firsts from) :when (string? (:value a))] (claim u to (:value a) a)))
        rst    (get record-set-types level)]
    (concat
     [(entity u k)]
     (copy :ead/unittitle :rico/title)
     (copy :ead/unitid :rico/identifier)
     (copy :ead/unitdate :rico/date)
     (copy :ead/abstract :rico/scopeAndContent)
     (copy :ead/scopecontent :rico/scopeAndContent)
     (copy :ead/bioghist :rico/history)
     (copy :ead/accessrestrict :rico/conditionsOfAccess)
     (copy :ead/physdesc :rico/recordResourceExtent)
     (when (and rst (= k :rico/RecordSet))
       (let [t (model/mint-entity-id :rico/RecordSetType [rst])]
         [(entity t :rico/RecordSetType (str rst-base rst))
          (claim u :rico/hasRecordSetType (model/reference t) nil)]))
     (when parent
       (let [[pu pk] (unit-of parent nil)]
         [(entity pu pk)
          (claim u :rico/isOrWasIncludedIn (model/reference pu) (first (firsts :ead/parent)))]))
     ;; a creator is named, not identified: scoped to the unit (ADR 0041)
     (mapcat (fn [a]
               (let [{:keys [name element]} (:value/fields (:value a))
                     kind (get agent-kinds element :rico/Agent)
                     ag   (model/mint-entity-id kind [(str rid) name])]
                 [(entity ag kind)
                  (claim ag :rico/name name a)
                  (claim u :rico/hasCreator (model/reference ag) a)]))
             (filter #(model/structured-value? (:value %)) (firsts :ead/creator)))
     (for [p (distinct (keep #(when (= "canon" (namespace (:predicate %))) (:predicate %)) as))
           :when (not (#{:canon/title :canon/date :canon/identifier :canon/agent :canon/note
                         :canon/role :canon/note-kind :canon/lang} p))]
       {:kind  :diagnostic
        :value (dx/loss {:category :dropped :subject rid :edge :projection :source-field p
                         :message (str (name p) " not represented in the RiC-O view (ADR 0045)")})}))))

(def rule
  "The compiled `:infer` projection of an archival unit (ADR 0045)."
  (rules/compiled-rule {:id :rule.ead/project :phase :infer :runner (comp vec runner)}))

(defn project
  "`record` with its RiC-O view: a Record or RecordSet, included in its parent's."
  [record]
  (:record (runtime/run-phase record [rule] :infer)))

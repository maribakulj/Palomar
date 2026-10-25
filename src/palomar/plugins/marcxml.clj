(ns palomar.plugins.marcxml
  "Shared MARCXML parsing for the MARC-family spokes (INTERMARC, MARC21).

   MARCXChange (BnF) and MARC21slim (LoC) are the same XML shape — a `record`
   of a `leader`, `controlfield`s (`tag`) and `datafield`s (`tag` + `subfield`s
   keyed by `code`) — differing only in namespace, how a record is recognised,
   and how its id is derived. This namespace owns the common parse; each spoke
   supplies those three policies. Extracted when MARC21 became the second MARC
   parser, so the field/subfield logic lives once (audit R1's drift lesson).

   Two layers come out of a record (audit S06). The flat one, `:ns/fTAG_CODE`
   (one assertion per subfield) plus `:ns/leader`, is what the mappings read.
   It loses which subfields belong to the same field and the indicators, so
   every field occurrence is also kept as a **fragment** (ADR 0011/0012), id
   `[:ns/fTAG n]` for the n-th occurrence of TAG, whose `:locator` holds the tag,
   the occurrence, the indicators and the ordered `[code value]` subfields.
   That locator is the faithful capture: a 700 `$a Doré $e ill.` keeps its
   relator next to its name, and two 700s keep their order.

   Elements are matched by **local name** (`(name (:tag elem))`) and attributes
   by local name too, so whichever keyword form `clojure.data.xml` produced for a
   namespace — URI-encoded or aliased — is irrelevant. Predicate *names* are
   prefixed `f` (`:marc21/f245_a`): a keyword name may not start with a digit and
   an un-prefixed `:marc21/245_a` would not round-trip through EDN (ADR 0001)."
  (:require [clojure.data.xml :as xml]
            [clojure.data.xml.tree :as xml-tree]
            [clojure.string :as str]
            [palomar.model :as model]
            [palomar.xml :as rx])
  (:import [clojure.data.xml.event EmptyElementEvent EndElementEvent StartElementEvent]))

(defn local-name
  "Local name (namespace stripped) of an XML element, or nil for a non-element."
  [elem]
  (when (and (map? elem) (:tag elem))
    (name (:tag elem))))

(defn attr
  "Attribute value whose key's local name is `k` (a string), or nil."
  [elem k]
  (some (fn [[ak av]] (when (= k (name ak)) av)) (:attrs elem)))

(defn text-of
  "Concatenated string content of `elem`, or nil if it has none."
  [elem]
  (let [ss (filter string? (:content elem))]
    (when (seq ss) (apply str ss))))

(defn elements
  "Depth-first seq of every element map in a parsed data.xml `tree`."
  [tree]
  (when (map? tree)
    (cons tree (mapcat elements (:content tree)))))

(defn control-value
  "Text of the first `controlfield` with the given `tag` anywhere in `record-elem`
   (e.g. \"001\", the record control number), or nil."
  [record-elem tag]
  (some (fn [e]
          (when (and (= "controlfield" (local-name e)) (= tag (attr e "tag")))
            (text-of e)))
        (elements record-elem)))

(defn field-assertions
  "Native assertions for one control/data `field` of a record, predicate
   namespace `ns` (a string, e.g. \"intermarc\" / \"marc21\"):
   - `controlfield tag=T`            → `:ns/fT` = text;
   - `subfield code=C` of `datafield tag=T` → `:ns/fT_C` = text (one per
     occurrence, repeatable)."
  [ns record-id field]
  (let [tag (attr field "tag")]
    (case (local-name field)
      "controlfield"
      (when-let [v (text-of field)]
        [(model/assertion {:subject    record-id
                           :predicate  (keyword ns (str "f" tag))
                           :value      v
                           :provenance (model/provenance {:pass :ingest})})])

      "datafield"
      (for [sf (:content field)
            :when (= "subfield" (local-name sf))
            :let  [code (attr sf "code")
                   v    (text-of sf)]
            :when (and code v)]
        (model/assertion {:subject    record-id
                          :predicate  (keyword ns (str "f" tag "_" code))
                          :value      v
                          :provenance (model/provenance {:pass :ingest})}))

      nil)))

(defn- field-fragment
  "The fragment for the `n`-th occurrence of `field` (a control or data field):
   its `:locator` is `{:tag :occurrence}` plus, for a controlfield, `:value`, and
   for a datafield, `:ind1`/`:ind2` and the ordered `:subfields` `[[code value] …]`."
  [ns record-id field n]
  (let [tag (attr field "tag")]
    (model/fragment
     {:id      (model/mint-fragment-id record-id [(keyword ns (str "f" tag)) n])
      :source  [:marcxml tag n]
      :locator (if (= "controlfield" (local-name field))
                 {:tag tag :occurrence n :value (text-of field)}
                 {:tag        tag
                  :occurrence n
                  :ind1       (attr field "ind1")
                  :ind2       (attr field "ind2")
                  :subfields  (vec (for [sf (:content field)
                                         :when (= "subfield" (local-name sf))]
                                     [(attr sf "code") (text-of sf)]))})})))

(defn- field-fragments
  "One `field-fragment` per control/data field of `elem`, numbered per tag in
   document order."
  [ns record-id elem]
  (let [fields (filter #(and (#{"controlfield" "datafield"} (local-name %))
                             (re-matches #"[A-Za-z0-9]+" (str (attr % "tag"))))
                       (:content elem))]
    (first (reduce (fn [[acc seen] f]
                     (let [tag (attr f "tag")
                           n   (get seen tag 0)]
                       [(conj acc (field-fragment ns record-id f n)) (assoc seen tag (inc n))]))
                   [[] {}] fields))))

(defn- leader-assertion [ns record-id elem]
  (when-let [l (some #(when (= "leader" (local-name %)) (text-of %)) (:content elem))]
    [(model/assertion {:subject    record-id
                       :predicate  (keyword ns "leader")
                       :value      l
                       :provenance (model/provenance {:pass :ingest})})]))

(defn- build-record
  "Construct one IR Record from a record `elem` per the family `policies`
   (`:ns :record-id :kind :source`): the flat assertions (leader and subfields)
   and one fragment per field occurrence."
  [{:keys [ns record-id kind source]} elem]
  (let [rid (record-id elem)]
    (model/record
     {:id         rid
      :kind       (kind elem)
      :source     (when source (source elem))
      :fragments  (field-fragments ns rid elem)
      :assertions (vec (concat (leader-assertion ns rid elem)
                               (mapcat #(field-assertions ns rid %) (:content elem))))})))

(defn parse-records
  "Parse a MARCXML `xml-string` into a vector of IR Records. The caller supplies
   the family policies as fns of the record element:
     `:ns`        predicate namespace string;
     `:record?`   true for an element that is a record root;
     `:record-id` the Record id;
     `:kind`      the Record `:kind`;
     `:source`    (optional) the Record `:source`.
   Each record's fields become native `:ns/f*` assertions via `field-assertions`.
   Eager (whole tree); `stream-records` is the bounded variant for large dumps."
  [xml-string {:keys [record?] :as policies}]
  (->> (rx/parse-str xml-string)
       elements
       (filter record?)
       (mapv #(build-record policies %))))

(defn stream-records
  "Lazily parse a MARCXML `readable` (a `Reader`/`InputStream`) into a **lazy** seq
   of IR Records — every element matching `:record?`, at any depth, in document
   order — in memory bounded by one record at a time (pull-parsed: each record is
   realised, built and released as the seq is consumed). Same policies as
   `parse-records`.

   A record is not searched inside (a record never nests a record), and the
   descent into other elements is itself lazy, so an SRU page
   (`searchRetrieveResponse/records/record/recordData/record`) streams like a flat
   dump; before, only the root's direct children were read, and an SRU page
   streamed zero records (audit S30).

   The caller MUST keep `readable` open for the whole consumption (e.g. via
   `with-open`) and must not retain the seq head (a `reduce`/`run!` is fine).
   Measured bounded on a flat dump and on an SRU page (`docs/eval/scale.md`)."
  [readable {:keys [record?] :as policies}]
  ;; Read events, not a tree: in a data.xml tree an element's following sibling
  ;; is only reachable once its whole content has been parsed, so any descent
  ;; that holds the sibling buffers the container — an SRU page's every record
  ;; (measured: an OOM in 128 MB on a 100 MB page). Events are taken one record
  ;; at a time and only that record is built as a tree.
  (letfn [(start? [e] (instance? StartElementEvent e))
          (record-start? [e]
            (and (or (start? e) (instance? EmptyElementEvent e))
                 (record? (xml/element (:tag e) (:attrs e)))))
          (take-element [evs]
            (if (instance? EmptyElementEvent (first evs))
              [[(first evs)] (rest evs)]
              (loop [acc [(first evs)] depth 1 evs (rest evs)]
                (if-let [s (seq evs)]
                  (let [e (first s)]
                    (cond
                      (start? e)                          (recur (conj acc e) (inc depth) (rest s))
                      (instance? EndElementEvent e)       (if (= 1 depth)
                                                            [(conj acc e) (rest s)]
                                                            (recur (conj acc e) (dec depth) (rest s)))
                      :else                               (recur (conj acc e) depth (rest s))))
                  (throw (ex-info "XML ends inside a record" {}))))))
          (records [evs]
            (lazy-seq
             (when-let [s (seq evs)]
               (if (record-start? (first s))
                 (let [[own more] (take-element s)]
                   (cons (xml-tree/event-tree own) (records more)))
                 (records (rest s))))))]
    (map #(build-record policies %) (records (rx/event-seq readable)))))

;; ---------------------------------------------------------------------------
;; MARCXChange (BnF) family policies — shared by INTERMARC and UNIMARC, which
;; differ only in their predicate/kind namespace. (MARC21slim has a different
;; record shape and supplies its own policies.)
;; ---------------------------------------------------------------------------

(defn- mxc-record?
  "True for an `<mxc:record>` — a `record` element carrying an `id` ARK — as opposed
   to the SRU `<srw:record>` wrapper, which has none."
  [elem]
  (and (= "record" (local-name elem)) (some? (attr elem "id"))))

(defn- record-id-from-ark
  "`:bnf/<cb-number>` from an ARK like \"ark:/12148/cb304403926\"."
  [ark]
  (keyword "bnf" (last (str/split ark #"/"))))

(defn mxc-policies
  "The `parse-records`/`stream-records` policies for a BnF MARCXChange spoke under
   predicate/kind namespace `ns` (a string): a record is an `<mxc:record>`, its id is
   `:bnf/<cb-number>` from the ARK, its `:kind` is `(keyword ns (lower-case type))`
   (or `opts`'s `:kind`), and its `:source` is the ARK. INTERMARC and UNIMARC differ
   only in `ns`."
  [ns opts]
  {:ns        ns
   :record?   mxc-record?
   :record-id (fn [e] (record-id-from-ark (attr e "id")))
   :kind      (fn [e] (or (:kind opts)
                          (keyword ns (str/lower-case (or (attr e "type") "record")))))
   :source    (fn [e] (attr e "id"))})

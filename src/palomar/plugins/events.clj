(ns palomar.plugins.events
  "Publication and production events (ADR 0036): what a MARC 260/264, a
   UNIMARC 210/214 or an INTERMARC 260 states — that a Manifestation was
   published, or produced, at a place, by an agent, at a date — as CIDOC-CRM
   and LRMoo structure instead of three unrelated strings.

   One event per source field occurrence:

     F30_Manifestation_Creation —R24_created→ the record's Manifestation
       —P4_has_time-span→ E52_Time-Span —P82_at_some_time_within→ \"1994-\"
       —P7_took_place_at→ E53_Place (named)
       —P14_carried_out_by→ E39_Actor (named)

   The Place and the Actor are named, not identified: a field gives a name,
   and two records' \"Paris\" or \"Gallimard\" may be different places or
   publishers. So each is scoped to the Manifestation (keyed by it and the
   matching form of the name, ADR 0041); saying two are one is an alignment's
   job (ADR 0032), not a key's. The event and its time-span are keyed by the
   Manifestation and the field occurrence. A field states the kind of
   event by its tag, or by its second indicator (MARC 264, UNIMARC 214: 0
   production, 1 publication); a distribution, manufacture or copyright
   statement is not an F30 and makes no event. The claims are `:asserted`: the
   field states the event, and each claim is scoped to its record, so none
   guesses an identity (ADR 0042, amending ADR 0036, where they were
   `:proposed`). Each carries the source evidence of the subfield it came
   from (ADR 0031).

   The spoke's own mappings are untouched: the date still becomes `:canon/date`,
   the publisher a `:canon/agent`. The events are a view the projection adds,
   read from the native fields, so they need a spoke that keeps its field
   fragments (the MARC family). MODS, whose importer reads neither the place
   nor the event type, makes none yet."
  (:require [clojure.string :as str]
            [palomar.model :as model]
            [palomar.plugins.lrmoo.view :as view]
            [palomar.runtime :as runtime]
            [palomar.rules :as rules]
            [palomar.text :as text]))

(def field-events
  "Native namespace → field tag → how the field states an event: `:kind`, a
   keyword or a map from the second indicator to one; and the subfield codes
   of the `:place`, `:agent` and `:date`."
  {"marc21"    {"260" {:kind :publication :place "a" :agent "b" :date "c"}
                "264" {:kind {"0" :production "1" :publication} :place "a" :agent "b" :date "c"}}
   "unimarc"   {"210" {:kind :publication :place "a" :agent "c" :date "d"}
                "214" {:kind {"0" :production "1" :publication} :place "a" :agent "c" :date "d"}}
   "intermarc" {"260" {:kind :publication :place "a" :agent "c" :date "d"}}})

(def event-kinds
  "The event kinds that make an F30 Manifestation Creation."
  #{:publication :production})

(defn- clean
  "A transcribed value without its ISBD punctuation and brackets: `[Forbach ]`
   and `Washington, D.C. :` name the places Forbach and Washington, D.C."
  [s]
  (let [v (-> (str s) (str/replace #"[\[\]]" "") (str/replace #"[\s:;,/=]+$" "") str/trim)]
    (when-not (str/blank? v) v)))

(defn- occurrences
  "The event-stating field occurrences of `record`: `{:fragment id :tag t
   :locator loc :subfields {code [[value evidence]…]}}`, in fragment order."
  [record]
  (let [locs (into {} (map (juxt :id :locator)) (:fragments record))
        hits (for [a (:assertions record)
                   :let [p (:predicate a)
                         [_ tag code] (when (keyword? p) (re-matches #"f(\d{3})_(.)" (name p)))
                         spec (get-in field-events [(namespace p) tag])]
                   :when (and spec (string? (:value a)) (not (model/out-of-force? a)))
                   ev (model/evidence-of a)
                   :when (:fragment ev)]
               {:fragment (:fragment ev) :tag tag :ns (namespace p) :code code :value (:value a) :ev ev})]
    (for [[fid hs] (group-by :fragment hits)
          :let [{:keys [tag ns]} (first hs)]]
      {:fragment  fid
       :tag       tag
       :spec      (get-in field-events [ns tag])
       :locator   (locs fid)
       ;; every occurrence of a code, in order: a 260 may name two places and
       ;; two publishers, and each is part of the event (post-audit test 06)
       :subfields (reduce (fn [m {:keys [code value ev]}] (update m code (fnil conj []) [value ev]))
                          {} hs)})))

(defn- kind-of [{:keys [spec locator]}]
  (let [k (:kind spec)] (if (map? k) (get k (:ind2 locator)) k)))

(defn- event-productions
  "The productions of one field occurrence `occ` about Manifestation `manif`:
   the event, one time-span carrying every date of the field, and a Place and
   an Actor for every place and agent it names."
  [rid manif occ]
  (let [{:keys [spec subfields fragment]} occ
        prov   (fn [ev] (cond-> {:pass :infer :rule :rule.events/manifestation-creation :derivation [rid]}
                          ev (assoc :evidence [ev])))
        claim  (fn [s p v ev] {:kind  :assertion
                               :value (model/assertion {:subject s :predicate p :value v :status :asserted
                                                        :provenance (model/provenance (prov ev))})})
        entity (fn [id kind] {:kind :entity :value (model/entity {:id id :kind kind
                                                                  :provenance (model/provenance (prov nil))})})
        event  (model/mint-entity-id :lrmoo/F30_Manifestation_Creation [manif fragment])
        named  (fn [k] (distinct (for [[v ev] (get subfields (get spec k))
                                       :let [c (clean v)]
                                       :when (and c (not-empty (text/norm c)))]
                                   [c ev])))
        dates  (distinct (for [[v ev] (get subfields (:date spec))
                               :let [d (some-> v str/trim not-empty)]
                               :when d]
                           [d ev]))
        ts     (when (seq dates) (model/mint-entity-id :crm/E52_Time-Span [event (mapv first dates)]))
        node   (fn [kind link [n ev]]
                 (let [id (model/mint-entity-id kind [manif (text/norm n)])]
                   [(entity id kind)
                    (claim event link (model/reference id) ev)
                    (claim id :crm/P190_has_symbolic_content n ev)]))]
    (concat
     [(entity event :lrmoo/F30_Manifestation_Creation)
      (claim event :lrmoo/R24_created (model/reference manif) nil)]
     (when ts
       (into [(entity ts :crm/E52_Time-Span)
              (claim event :crm/P4_has_time-span (model/reference ts) (second (first dates)))]
             (for [[d ev] dates] (claim ts :crm/P82_at_some_time_within d ev))))
     (mapcat #(node :crm/E53_Place :crm/P7_took_place_at %) (named :place))
     (mapcat #(node :crm/E39_Actor :crm/P14_carried_out_by %) (named :agent)))))

(defn- runner
  "The events of `record`, when it has exactly one Manifestation to hang them on."
  [record]
  (let [ms (view/manifestations record)]
    (if (= 1 (count ms))
      (vec (mapcat #(event-productions (:id record) (:id (first ms)) %)
                   (filter #(contains? event-kinds (kind-of %)) (occurrences record))))
      [])))

(def rule
  "The compiled `:infer` rule that adds the events (ADR 0036)."
  (rules/compiled-rule {:id :rule.events/manifestation-creation :phase :infer :runner runner}))

(defn add-events
  "`record` with its publication and production events (see the ns doc)."
  [record]
  (:record (runtime/run-phase record [rule] :infer)))

(ns palomar.plugins.intermarc.wemi
  "INTERMARC → WEMI derivation (WP-3, ADR 0016). A compiled `:infer` rule that
   reads `:intermarc/*` assertions and mints LRMoo entities, using the embedded
   authority link (the `f145_3` lookup validated in the WP-0 spike) — no fuzzy
   inference for authority-controlled records.

   This slice models the link the data carries directly:

     <mxc:record>                              -> F3_Manifestation (id from ARK)
     f145 ($m language, $3 authority id)       -> F2_Expression    (id from f145_3)
     R4_embodies                               Manifestation -> Expression

   So manifestations sharing an `f145_3` collapse to one Expression by id alone
   (ADR 0008) — lookup-based clustering, no batch state. The Work level (R3,
   from author + uniform title) and the fuzzy fallback for records without
   `f145` are a later slice (the spike showed the fallback needs bridging).
   Commit policy (D7, ADR 0014/0016): claims resting on a determinate identifier
   (the ARK, the `145 $3` link) are `:asserted` (certified); the Work whose
   creator is only a name string stays `:proposed`. The string-key floor
   projection (`lrmoo.project`) proposes everything.

   A `compiled-rule` (not a data rule): it computes content-derived ids, which
   the data DSL deliberately cannot express (the mapping compiler does the same)."
  (:require [clojure.string :as str]
            [palomar.diagnostics :as dx]
            [palomar.model :as model]
            [palomar.rules :as rules]
            [palomar.runtime :as runtime]
            [palomar.text :as text]))

(defn- field
  "First value of `record`'s assertion with predicate `pred`, or nil."
  [record pred]
  (->> (:assertions record) (filter #(= pred (:predicate %))) first :value))

(defn- entity-prod
  "An `:entity` production. `iri` (optional) is the external authority IRI."
  ([id kind prov] (entity-prod id kind prov nil))
  ([id kind prov iri]
   {:kind  :entity
    :value (model/entity (cond-> {:id id :kind kind :provenance prov}
                           iri (assoc :iri iri)))}))

(defn- ark-iri
  "The data.bnf.fr IRI for `record` when its `:source` is an ARK, else nil. This
   is faithful transcription of the identifier the source already carries — not
   reconciliation — so the Manifestation node exports as its real, resolvable IRI."
  [record]
  (when-let [s (:source record)]
    (when (str/starts-with? (str s) "ark:")
      (str "http://data.bnf.fr/" s))))

(defn- assert-prod
  "An `:assertion` production. `status` is the commit decision (D7): `:asserted`
   for claims resting on a determinate identifier (the ARK, the `145 $3` link),
   `:proposed` for inferred-but-unproven claims. Defaults to `:proposed`."
  ([subject predicate value prov] (assert-prod subject predicate value prov :proposed))
  ([subject predicate value prov status]
   {:kind  :assertion
    :value (model/assertion {:subject subject :predicate predicate :value value
                             :status status :provenance prov})}))

(defn- wemi-productions
  "WEMI entity and link productions for one INTERMARC record (see ns doc).
   Manifestation always; Expression + Work when the embedded link is present.

   Commit policy (D7, ADR 0014/0016): everything here rests on a determinate
   identifier — the Manifestation is the record itself (ARK), the Expression and
   R4 come from the `145 $3` authority link — so it is `:asserted` (certified).
   The one exception is the Work whose creator is only a name string (`100 $a`,
   no `100 $3` authority id): its key is partly fuzzy, so it stays `:proposed`."
  [record]
  (let [rid   (:id record)
        prov  (model/provenance {:pass :infer :derivation [rid]})
        manif (model/mint-entity-id :lrmoo/F3_Manifestation (str rid))
        miri  (ark-iri record)
        x3    (field record :intermarc/f145_3)
        m245  (field record :intermarc/f245_a)]
    (cond-> [(entity-prod manif :lrmoo/F3_Manifestation prov miri)]
      m245 (conj (assert-prod manif :lrmoo/R33_has_string m245 prov :asserted))
      x3   (into (let [expr (model/mint-entity-id :lrmoo/F2_Expression x3)
                       ttl  (field record :intermarc/f145_a)
                       a3   (field record :intermarc/f100_3)
                       auth (or a3 (field record :intermarc/f100_a))
                       wst  (if a3 :asserted :proposed)   ; authority-backed creator?
                       work (when (and auth ttl)
                              (model/mint-entity-id :lrmoo/F1_Work
                                                    (str auth "|" (text/norm ttl))))]
                   (cond-> [(entity-prod expr :lrmoo/F2_Expression prov)
                            (assert-prod manif :lrmoo/R4_embodies (model/reference expr) prov :asserted)]
                     ttl  (conj (assert-prod expr :lrmoo/R33_has_string ttl prov :asserted))
                     work (into [(entity-prod work :lrmoo/F1_Work prov)
                                 (assert-prod work :lrmoo/R3_is_realised_in (model/reference expr) prov wst)
                                 (assert-prod work :lrmoo/R33_has_string ttl prov wst)])))))))

(def mapped-source-fields
  "INTERMARC fields the current LRMoo projection represents. Every other
   `:intermarc/*` field a record carries is reported as loss (ADR 0015); as
   WEMI derivation grows (agents, more attributes, …) this set grows and loss
   shrinks — the loss report tracks the improvement."
  #{:intermarc/f145_3   ; -> F2_Expression id
    :intermarc/f145_a   ; -> Expression / Work title
    :intermarc/f100_3   ; -> F1_Work key (author authority)
    :intermarc/f245_a}) ; -> Manifestation title

(defn- source-fields [record]
  (->> (:assertions record)
       (map :predicate)
       (filter #(= "intermarc" (namespace %)))
       distinct))

(defn- loss-productions
  "A loss diagnostic (ADR 0015) for each source field the projection drops."
  [record]
  (for [p (source-fields record)
        :when (not (contains? mapped-source-fields p))]
    {:kind  :diagnostic
     :value (dx/loss {:category     :dropped
                      :subject      (:id record)
                      :edge         :import
                      :source-field p
                      :message      (str (name p) " not represented in the LRMoo view")})}))

(defn coverage
  "How much of a record's INTERMARC fields the LRMoo projection represents:
   `{:mapped M :total T :pct P}`."
  [record]
  (let [fs (source-fields record)
        m  (count (filter mapped-source-fields fs))
        t  (count fs)]
    {:mapped m :total t :pct (if (pos? t) (quot (* 100 m) t) 0)}))

(defn runner
  "WEMI-derivation productions for one record: the WEMI projection plus a loss
   diagnostic per dropped source field (ADR 0015 / 0016)."
  [record]
  (into (wemi-productions record) (loss-productions record)))

(def rule
  "Compiled `:infer` WEMI-derivation rule (ADR 0016)."
  (rules/compiled-rule {:id :rule.intermarc/derive-wemi :phase :infer :runner runner}))

(defn derive-wemi
  "Run INTERMARC WEMI derivation over `record` (the `:infer` phase). Returns the
   enriched record — Manifestation/Expression entities and the R4 link."
  [record]
  (:record (runtime/run-phase record [rule] :infer)))

(defn- isni->uri
  "ISNI authority URI from an INTERMARC 100 $1 value like \"ISNI0000000122762442\"
   (keep the 16 ISNI characters)."
  [isni]
  (str "https://isni.org/isni/" (str/replace (str isni) #"[^0-9X]" "")))

(defn with-identified-agent
  "Mint a first-class, authority-identified agent when the INTERMARC main entry
   carries an ISNI (100 $1): a `:crm/E21_Person` entity whose `:iri` is the ISNI
   URI. Because that IRI is a *determinate* identifier the agent identity is
   certified (D7) — the gold for agent reconciliation, which the string-only
   canonical floor (ADR 0003) cannot hold. The Person's name is the controlled
   `:canon/agent` already on the record; the Linked Art export surfaces the
   identified Person.

   V1 scope, stated plainly: one agent (the 100 main entry); no explicit creation
   event (the association is implicit — one main author per record); no
   cross-record agent de-duplication (that is ADR 0018 proper, though two records
   with the same ISNI already mint the same id by content); other spokes carry no
   authority id, so they get a label-only creator. A no-op without an ISNI."
  [record]
  (if-let [isni (field record :intermarc/f100_1)]
    (let [iri  (isni->uri isni)
          id   (model/mint-entity-id :crm/E21_Person iri)
          prov (model/provenance {:pass :infer :derivation [(:id record)]})]
      (update record :entities (fnil conj [])
              (model/entity {:id id :kind :crm/E21_Person :iri iri :provenance prov})))
    record))

(ns palomar.plugins.lrmoo.project
  "Canonical → WEMI projection — the *generic* pivot (ADR 0013 §2: the LRMoo
   plugin owns projection).

   It reads only the canonical floor (`:canon/agent`, `:canon/title`) and mints a
   WEMI graph, so **any** spoke that maps to canonical (via shape + mapping) gets
   an LRMoo view — not just INTERMARC. This is the two-rung ladder's graceful
   degradation (ADR 0013) made real: where `intermarc/derive-wemi` is the *enriched*
   projection that exploits the native `145 $3` authority link, this is the
   floor projection every format shares.

   Honest limits:
   - Canonical carries **no authority link**, so identity always falls back to the
     string key (creator + normalised title). That key under-merges title variants
     (measured: recall ~0.43, `docs/spikes/entity-resolution.md`) — the price of
     projecting from the floor.
   - WEMI needs an Expression to connect Work and Manifestation (LRMoo has no
     direct Work–Manifestation link); canonical gives nothing to characterise it
     (language/form), so a minimal Expression is minted to carry the chain.
   - Loss (ADR 0015, import edge): unrepresented `:canon/*` fields are `:dropped`;
     >=2 distinct languages (parallel-language titles = multiple Expressions) make
     the floor's single Expression `:under-specified` (ADR 0013 graceful
     degradation); an `uncertain` title collapsed to one alternative is
     `:ambiguity-collapsed`. Minted claims default to `:proposed` (ADR 0005)."
  (:require [palomar.diagnostics :as dx]
            [palomar.model :as model]
            [palomar.rules :as rules]
            [palomar.runtime :as runtime]
            [palomar.text :as text]))

(defn- first-literal
  "First string-valued assertion for `pred` in `record`. Works whether the
   canonical value is a record-level literal (`:canon/agent \"Hugo\"`) or lives on
   a qualified fragment (`:canon/title` referencing a fragment that carries the
   string), so the projection is agnostic to how a spoke shaped its canonical."
  [record pred]
  (->> (:assertions record)
       (filter #(and (= pred (:predicate %)) (string? (:value %))))
       first
       :value))

(defn- uncertain-title
  "First `uncertain` (ADR 0001 multiplicity) `:canon/title` value in `record`,
   or nil. Used only when no literal title is present."
  [record]
  (->> (:assertions record)
       (filter #(= :canon/title (:predicate %)))
       (map :value)
       (filter model/uncertain-value?)
       first))

(defn- title-of
  "The title string for the Work/Expression: a literal when present, else the
   first string alternative of an uncertain `:canon/title`. Without this, an
   uncertain title would be silently skipped and no Expression minted."
  [record]
  (or (first-literal record :canon/title)
      (first (filter string? (:value/alternatives (uncertain-title record))))))

(defn- work-title-of
  "The title that keys the Work/Expression and so decides whether they are minted:
   the *uniform* title (`:canon/uniform-title`) when present, else the transcribed
   `title-of`. The single source of the 'an Expression is minted' condition — the
   loss accounting (`language-losses`/`ambiguity-losses`) keys on this too, so it
   stays consistent with what `wemi-productions` actually mints."
  [record]
  (or (first-literal record :canon/uniform-title) (title-of record)))

(defn- entity-prod [id kind prov]
  {:kind :entity :value (model/entity {:id id :kind kind :provenance prov})})

(defn- assert-prod
  "An `:assertion` production. `status` is the commit decision (D7): `:asserted`
   for transcription (the record's own title), `:proposed` for the string-key
   WEMI inference. Defaults to `:proposed` — the floor has no determinate id."
  ([subject predicate value prov] (assert-prod subject predicate value prov :proposed))
  ([subject predicate value prov status]
   {:kind  :assertion
    :value (model/assertion {:subject subject :predicate predicate :value value
                             :status status :provenance prov})}))

(defn- wemi-productions
  "WEMI productions from the canonical floor: Manifestation always; Expression
   when a title is present (the minimal connector); Work when a creator is too.

   Uniform-title bridging (the WEMI-derivation recall step, ADR 0003 growth): the
   Manifestation keeps the *transcribed* `:canon/title`, but the Work/Expression
   identity key — and their R33 string — use the *uniform* title
   (`:canon/uniform-title`, e.g. MARC 240) when the record carries one. So two
   editions whose transcribed titles differ but whose uniform title agrees mint the
   *same* Work id and cluster (measured: `docs/eval/bibr-work-clustering.md`). Records
   with no uniform title fall back to the transcribed title — unchanged behaviour."
  [record]
  (let [rid        (:id record)
        prov       (model/provenance {:pass :infer :derivation [rid]})
        manif      (model/mint-entity-id :lrmoo/F3_Manifestation (str rid))
        title      (title-of record)
        work-title (work-title-of record)
        agent      (first-literal record :canon/agent)]
    (cond-> [(entity-prod manif :lrmoo/F3_Manifestation prov)]
      ;; the Manifestation's own *transcribed* title is transcription -> certified
      ;; (D7); the Work/Expression below are string-key inference -> :proposed, and
      ;; key on the uniform title when present.
      title      (conj (assert-prod manif :lrmoo/R33_has_string title prov :asserted))
      work-title (into (let [wkey (str (or agent "") "|" (text/norm work-title))
                             expr (model/mint-entity-id :lrmoo/F2_Expression wkey)
                             work (when agent (model/mint-entity-id :lrmoo/F1_Work wkey))]
                         ;; F1 and F2 minted from the same key are distinct ids: the
                         ;; kind is part of the content hash (`mint-entity-id`).
                         (cond-> [(entity-prod expr :lrmoo/F2_Expression prov)
                                  (assert-prod manif :lrmoo/R4_embodies (model/reference expr) prov)
                                  (assert-prod expr :lrmoo/R33_has_string work-title prov)]
                           work (into [(entity-prod work :lrmoo/F1_Work prov)
                                       (assert-prod work :lrmoo/R3_is_realised_in (model/reference expr) prov)
                                       (assert-prod work :lrmoo/R33_has_string work-title prov)])))))))

(def mapped-source-fields
  "Canonical predicates the WEMI projection represents. Every other `:canon/*`
   assertion a record carries is reported as loss (ADR 0015)."
  #{:canon/title          ; -> R33 strings (Manifestation / Expression / Work)
    :canon/uniform-title  ; -> Work/Expression key + R33 (uniform-title bridging)
    :canon/agent})        ; -> F1_Work key (creator)

(defn- source-fields
  "Distinct `:canon/*` predicates the record carries, excluding the in-graph
   loss marker (which is machinery, not source content)."
  [record]
  (->> (:assertions record)
       (map :predicate)
       (filter #(= "canon" (namespace %)))
       (remove #{:canon/loss-marker})
       distinct))

(defn- dropped-losses
  "A `:dropped` / `:import` loss for each canonical field the projection does not
   represent. `:canon/lang` is handled separately (`language-losses`), since with
   multiple languages it is an *under-specification*, not a plain drop."
  [record]
  (for [p (source-fields record)
        :when (and (not (contains? mapped-source-fields p))
                   (not= :canon/lang p))]
    {:kind  :diagnostic
     :value (dx/loss {:category     :dropped
                      :subject      (:id record)
                      :edge         :import
                      :source-field p
                      :message      (str (name p) " not represented in the LRMoo view")})}))

(defn- distinct-langs
  "Distinct `:canon/lang` values the record carries (one per parallel-language
   title fragment)."
  [record]
  (->> (:assertions record)
       (filter #(= :canon/lang (:predicate %)))
       (map :value)
       distinct))

(defn- language-losses
  "Language-driven loss (ADR 0015). When a title produced an Expression and the
   canonical layer carries >=2 languages, the floor mints *one* Expression where
   the source implies several → `:under-specified`. With exactly one language, the
   language is simply not carried onto the Expression → `:dropped`."
  [record]
  (let [n (count (distinct-langs record))]
    (cond
      (not (work-title-of record)) []   ; no Expression minted -> no language loss to report
      (>= n 2) [{:kind  :diagnostic
                 :value (dx/loss {:category     :under-specified
                                  :subject      (:id record)
                                  :edge         :import
                                  :source-field :canon/lang
                                  :message      (str n " language expressions collapsed to one"
                                                     " — Expression level under-specified")})}]
      (= n 1) [{:kind  :diagnostic
                :value (dx/loss {:category     :dropped
                                 :subject      (:id record)
                                 :edge         :import
                                 :source-field :canon/lang
                                 :message      ":canon/lang not carried onto the Expression"})}]
      :else [])))

(defn coverage
  "How much of a record's canonical fields the WEMI projection represents:
   `{:mapped M :total T :pct P}`."
  [record]
  (let [fs (source-fields record)
        m  (count (filter mapped-source-fields fs))
        t  (count fs)]
    {:mapped m :total t :pct (if (pos? t) (quot (* 100 m) t) 0)}))

(defn- ambiguity-losses
  "Ambiguity loss (ADR 0015): when the Work/Expression title was taken from an
   `uncertain` `:canon/title` (no literal won, and no uniform title pre-empted it),
   the projection forced one of N candidates → `:ambiguity-collapsed`, tied to the
   assertion IR's multiplicity (ADR 0001). A uniform title, when present, becomes the
   chain's title instead — so the uncertain transcribed title is not collapsed and
   no ambiguity loss is reported."
  [record]
  (if (and (nil? (first-literal record :canon/uniform-title))
           (nil? (first-literal record :canon/title))
           (some string? (:value/alternatives (uncertain-title record))))
    [{:kind  :diagnostic
      :value (dx/loss {:category     :ambiguity-collapsed
                       :subject      (:id record)
                       :edge         :import
                       :source-field :canon/title
                       :message      "uncertain :canon/title collapsed to one alternative"})}]
    []))

(defn- runner
  "Projection productions for one record: the WEMI graph plus loss diagnostics —
   dropped canonical fields, the language under-specification, and an uncertain-
   title ambiguity collapse (ADR 0015 / 0013)."
  [record]
  (-> (wemi-productions record)
      (into (dropped-losses record))
      (into (language-losses record))
      (into (ambiguity-losses record))))

(def rule
  "Compiled `:infer` canonical→WEMI projection rule (ADR 0013)."
  (rules/compiled-rule {:id :rule.lrmoo/project :phase :infer :runner runner}))

(defn project
  "Run the canonical→WEMI projection over `record` (the `:infer` phase). Returns
   the enriched record — WEMI entities and the R3/R4 links, derived from the
   canonical floor alone."
  [record]
  (:record (runtime/run-phase record [rule] :infer)))

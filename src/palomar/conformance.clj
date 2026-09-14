(ns palomar.conformance
  "Conformance mechanism (WP-6) — does a source's WEMI projection satisfy an
   institutional *profile*? A profile is a named set of checks expressed as
   ordinary diagnostics (ADR 0001); a conformance report is those diagnostics
   filtered to the profile, plus a pass/fail verdict under the shared failure
   policy (`palomar.diagnostics`). It mirrors `palomar.validate` — the mechanism an
   institution gates an *ingest* on — one rung up: the mechanism an institution
   gates a *target conversion* on.

   Dataless by construction. A profile's checks encode the **public** model of the
   target or source format — the Linked Art object model (https://linked.art) for
   the museum/Louvre target, the BnF INTERMARC kitcat field model for the BnF
   source. The only institution-specific input is the *acceptance threshold* —
   which severities a given institution treats as failing — and that is the `policy`
   parameter, exactly as in `palomar.validate`. So the BnF/Louvre acceptance
   *criteria* are a knob, not a blocker on building the mechanism.

   Two directions, one mechanism: a *target* profile (Linked Art) checks the WEMI
   projection's fitness to serialise; a *source* profile (INTERMARC) checks the
   record's own native fields. Both run over the projected record — the native
   `:intermarc/*` assertions survive projection — so the mechanism is uniform.

   This is deliberately **not** the strict official-schema validation: that lives,
   test-only, in `palomar.eval.linked-art-conformance-test`, which validates our
   output against the official draft-2020-12 Linked Art schema (a stronger,
   external-oracle bar). A profile is the institution's *requirement set* — some
   requirements are stricter than the schema (an identified creator), some looser —
   answering 'is this good enough for THIS target?', filtered and policy-gated, in
   the project's own diagnostics vocabulary."
  (:require [clojure.string :as str]
            [palomar.convert :as convert]
            [palomar.diagnostics :as dx]
            [palomar.model :as model]
            [palomar.plugins.lrmoo.view :as view]))

;; ---------------------------------------------------------------------------
;; Profile model
;;
;; A check is a map {:id :severity :message :conformant?}. `conformant?` is a
;; predicate over a projected WEMI record; when it returns false the check emits
;; a conformance diagnostic with the given severity. A profile bundles checks
;; under an id and a human label.
;; ---------------------------------------------------------------------------

(defn- check->diagnostic
  "Run one profile `check` against `record`; return a conformance diagnostic when
   the record violates it, else nil. The diagnostic is a standard model Diagnostic
   coded `:conformance.<profile>/<check>` and tagged with the profile in `:detail`
   (so it is filterable, per the roadmap's 'diagnostics filtered to a profile')."
  [profile-id record {:keys [id severity message conformant?]}]
  (when-not (conformant? record)
    (model/diagnostic
     {:severity severity
      :code     (keyword (str "conformance." (name profile-id)) (name id))
      :subject  (:id record)
      :message  message
      :detail   {:conformance/profile profile-id}})))

(defn check-record
  "The conformance diagnostics `profile` raises against one projected `record`."
  [profile record]
  (into [] (keep #(check->diagnostic (:id profile) record %)) (:checks profile)))

(defn for-profile
  "Filter `diagnostics` to those a given `profile-id` produced (the report view)."
  [diagnostics profile-id]
  (filterv #(= profile-id (get-in % [:detail :conformance/profile])) diagnostics))

;; ---------------------------------------------------------------------------
;; The Linked Art (Louvre) profile — the public Linked Art object model
;; ---------------------------------------------------------------------------

(defn- titled?
  "The record carries a string title (`:lrmoo/R33_has_string`) — Linked Art's
   `identified_by` Primary Name."
  [record]
  (boolean (some #(and (= :lrmoo/R33_has_string (:predicate %)) (string? (:value %)))
                 (:assertions record))))

(defn- has-agent?
  [record]
  (boolean (some #(and (= :canon/agent (:predicate %)) (string? (:value %)))
                 (:assertions record))))

(defn- identified-agent?
  "An authority-identified creator entity (`:crm/E21_Person` with an `:iri`) — the
   D7-certified, reconcilable creator (ADR 0018)."
  [record]
  (boolean (some #(and (= :crm/E21_Person (:kind %)) (:iri %)) (:entities record))))

(defn- has-identifier?
  "A stable identifier: a Manifestation authority IRI, or a `:canon/identifier` —
   Linked Art's `identified_by` Identifier."
  [record]
  (boolean (or (some :iri (view/manifestations record))
               (some #(and (= :canon/identifier (:predicate %)) (string? (:value %)))
                     (:assertions record)))))

(def linked-art-profile
  "Linked Art (Louvre) — the museum target's requirement set, grounded in the
   public Linked Art object model. Two hard requirements (a HumanMadeObject root
   with a name), three richness warnings (the carried Expression, its Work, an
   identified creator), one identifier hint. The acceptance threshold is the
   caller's `policy`."
  {:id    :linked-art
   :label "Linked Art (Louvre)"
   :checks
   [{:id :root-human-made-object
     :severity :error
     :message "no Manifestation: Linked Art needs a HumanMadeObject root, none was projected"
     :conformant? #(seq (view/manifestations %))}
    {:id :has-name
     :severity :error
     :message "no title: Linked Art requires an identified_by Primary Name"
     :conformant? titled?}
    {:id :carries-expression
     :severity :warning
     :message "no Expression: the HumanMadeObject carries no LinguisticObject (thin object)"
     :conformant? #(seq (view/expressions %))}
    {:id :expression-realises-work
     :severity :warning
     :message "no Work: the Expression is not part_of a PropositionalObject (incomplete WEMI chain)"
     :conformant? #(seq (view/works %))}
    {:id :creator-identified
     :severity :warning
     :message "creator is a bare name, not an authority-identified Person (not reconcilable, ADR 0018)"
     :conformant? #(or (not (has-agent? %)) (identified-agent? %))}
    {:id :has-identifier
     :severity :info
     :message "no stable identifier (ARK / :canon/identifier) for an identified_by Identifier"
     :conformant? has-identifier?}]})

;; ---------------------------------------------------------------------------
;; The BnF INTERMARC (bibliographic) profile — the public kitcat field model
;;
;; Source-side conformance: does an INTERMARC bibliographic record carry the
;; fields the BnF requires? The checks read the native `:intermarc/*` assertions
;; (which survive projection), grounded in what real BnF SRU records carry —
;; f001/f003/f245 are near-universal (the bibliographic essentials), f100 is
;; record-type-dependent (0/30 on periodicals, so never an error), and a present
;; 100 heading is nearly always authority-linked (the Transition-bibliographique
;; discipline). As with Linked Art, the *exact* mandatory set is the BnF's private
;; acceptance criteria — the `policy` knob — not baked in here. Targets the
;; bibliographic spoke (`--from intermarc`); authority records are a separate kind.
;; ---------------------------------------------------------------------------

(defn- has-field?
  "The record carries at least one assertion under native predicate `pred`."
  [record pred]
  (boolean (some #(= pred (:predicate %)) (:assertions record))))

(def intermarc-profile
  "BnF INTERMARC (bibliographic) — the field requirements of a BnF bibliographic
   record, grounded in the public kitcat field model. Two hard essentials (a 001
   control number, a 245 title), three BnF expectations (the 003 ARK, an
   authority-linked 100 heading — Transition bibliographique, a 260 date), and the
   145 Work-link as a WEMI-derivation-readiness hint."
  {:id    :intermarc
   :label "BnF INTERMARC (bibliographic)"
   :checks
   [{:id :control-number
     :severity :error
     :message "no 001 control number (f001) — the mandatory record identifier"
     :conformant? #(has-field? % :intermarc/f001)}
    {:id :title
     :severity :error
     :message "no 245 $a title (f245_a) — mandatory for a bibliographic record"
     :conformant? #(has-field? % :intermarc/f245_a)}
    {:id :persistent-identifier
     :severity :warning
     :message "no 003 ARK (f003) — the BnF persistent identifier"
     :conformant? #(has-field? % :intermarc/f003)}
    {:id :heading-authority-linked
     :severity :warning
     :message "a 100 personal-name heading (f100_a) carries no $3 authority link (f100_3) — Transition bibliographique (ADR 0018)"
     :conformant? #(or (not (has-field? % :intermarc/f100_a)) (has-field? % :intermarc/f100_3))}
    {:id :publication-date
     :severity :warning
     :message "no 260 $d publication date (f260_d)"
     :conformant? #(has-field? % :intermarc/f260_d)}
    {:id :work-authority-link
     :severity :info
     :message "no 145 $3 Work-authority link (f145_3) — no WEMI derivation to a shared Work (ADR 0016)"
     :conformant? #(has-field? % :intermarc/f145_3)}]})

;; ---------------------------------------------------------------------------
;; The IIIF Presentation 3.0 profile — the public IIIF presentation model
;;
;; A *target* profile, like Linked Art: does the projection yield a conformant
;; IIIF Presentation 3.0 Manifest? IIIF describes a *digitised object*, so its
;; floor is the narrowest spoke — title → `label`, identifier/source → the
;; Manifest `id`, digital-object → the Canvases, note → `summary`
;; (`palomar.plugins.iiif.export`). The checks read those canonical fields; the
;; distinctive signal is the digital object (a Canvas to present).
;; ---------------------------------------------------------------------------

(defn- canon-strings
  "String values on `record` under canonical predicate `pred`."
  [record pred]
  (keep #(when (and (= pred (:predicate %)) (string? (:value %))) (:value %)) (:assertions record)))

(defn- http-uri?
  [s]
  (and (string? s) (or (str/starts-with? s "http://") (str/starts-with? s "https://"))))

(def iiif-profile
  "IIIF Presentation 3.0 — the museum/library digital-presentation target. A label
   is the one hard requirement (P3 mandates it on a Manifest); the presentable
   content (≥1 Canvas, from a digital object) and a dereferenceable HTTP(S) id are
   the expectations that separate a real digitised-object Manifest from a bare
   bibliographic record."
  {:id    :iiif
   :label "IIIF Presentation 3.0"
   :checks
   [{:id :label
     :severity :error
     :message "no title (:canon/title) — a IIIF Manifest requires a label"
     :conformant? #(seq (canon-strings % :canon/title))}
    {:id :has-canvas
     :severity :warning
     :message "no digital object (:canon/digital-object) — the Manifest has no Canvas to present"
     :conformant? #(seq (canon-strings % :canon/digital-object))}
    {:id :dereferenceable-id
     :severity :warning
     :message "no HTTP(S) URI for the Manifest id (:source / :canon/identifier) — IIIF ids must be dereferenceable"
     :conformant? #(or (http-uri? (:source %)) (boolean (some http-uri? (canon-strings % :canon/identifier))))}]})

(def profiles
  "The institutional profiles the conformance mechanism ships."
  {:linked-art linked-art-profile
   :intermarc  intermarc-profile
   :iiif       iiif-profile})

(defn conformance
  "Run institutional `profile` over `source` (source spoke `from`): import →
   normalise → project to WEMI → check. Returns

     {:profile :linked-art :label \"…\" :records N
      :diagnostics [...] :summary {...} :failed? bool}

   `opts` is threaded to the importer; single-record spokes (Dublin Core) require
   `:record-id` in `opts` (as for `convert`/`validate` — the CLI derives one from the
   filename). `policy` (∈ `dx/failure-policies`, default `:errors-only`) is the
   acceptance threshold that decides `:failed?`. Throws on an unknown spoke, or on a
   missing required `:record-id`, via `convert/to-wemi`."
  [{:keys [from source opts profile policy] :or {opts {} policy :errors-only}}]
  (let [{:keys [records]} (convert/to-wemi from opts source)
        diags (into [] (mapcat #(check-record profile %)) records)]
    {:profile     (:id profile)
     :label       (:label profile)
     :records     (count records)
     :diagnostics diags
     :summary     (dx/summary diags)
     :failed?     (dx/should-fail? diags policy)}))

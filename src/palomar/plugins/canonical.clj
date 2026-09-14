(ns palomar.plugins.canonical
  "Canonical documentary vocabulary plugin (ADR 0003).

   Two things live here, both as plain data:

   1. The `:canon/*` documentary vocabulary — the predicates that
      format plugins (Dublin Core, MARC, CIDOC, …) map their native
      terms onto so cross-source rules, validation, and projection can
      operate on a commensurable layer (ADR 0003 §Decision). The V1 set of
      eight has grown by one — `:canon/uniform-title` (see below) — under
      ADR 0003's growth discipline.
   2. The first validation rules over that layer. Sprint 5 stood up the
      ingest → normalize path (native source → `:canon/*` assertions);
      this plugin closes the loop by *enforcing* the canonical layer —
      `title-required` is the V1 rule and the template every later
      canonical rule follows.

   ## What is and isn't a documentary predicate

   The canonical predicates describe *what* a resource is. They do not
   describe *how* a value is qualified — language, script, certainty.
   Qualifiers ride on fragments (ADR 0011), not on new top-level
   predicates. `:canon/lang` is the first such case: it is a qualifier
   coord on a fragment, minted by the shape adapter and renamed by
   qualified mappings (ADR 0009 §Qualifier). `:canon/lang` is *not* a
   documentary predicate — it is absent from `documentary-vocabulary` by
   design, not by omission. A *uniform title*, by contrast, is documentary
   (it names what the work is), so it earns a predicate, not a fragment coord.

   ## The core stays vocabulary-blind

   This is a plugin like any other (ADR 0003 §Consequences). The core —
   model, rules, runtime, diagnostics — never sees `:canon/*`; to it
   these are opaque keywords. The dependency arrow points one way: the
   core never depends on this namespace (guarded by
   `palomar.architecture-test`). Everything here is plain data
   (ADR 0007): no requires, nothing to load-order. Deep rule validity is
   the compiler's job — consumers `compile-rules` these, and
   `palomar.plugins.canonical-test` asserts they do so cleanly.
   Accordingly the vocabulary is versioned with this plugin, independent
   of the core spec-version (ADR 0003 §Consequences).")

;; ---------------------------------------------------------------------------
;; Documentary vocabulary (ADR 0003 §Decision)
;;
;; The closed V1 starter set. It grows by deliberate addition justified
;; by a concrete use case, never by anticipation (ADR 0003 §Consequences,
;; growth discipline). Mirrors `palomar.model/structural-vocabulary` for
;; the `:meta/*` layer: a set plus a membership predicate.
;; ---------------------------------------------------------------------------

(def documentary-vocabulary
  "The canonical documentary predicates (ADR 0003 §Decision, grown by discipline).

   Each names *what* is described; qualifiers such as `:canon/lang` are
   excluded by design — they ride on fragments (ADR 0011), see the
   namespace doc.

   `:canon/uniform-title` is the ninth, added 2026-06-06 under the growth
   discipline for a concrete, measured use case: the cataloguer's controlled
   work title (MARC 240, MODS/UNIMARC uniform title) is the WEMI-derivation Work key
   that unifies an edition's transcribed-title variants — the floor projection
   keys the Work on it when present, raising recall against an independent gold
   (`docs/eval/bibr-work-clustering.md`). It describes *what the work is*, distinct
   from `:canon/title` (the manifestation's transcribed title)."
  #{:canon/title
    :canon/uniform-title
    :canon/identifier
    :canon/agent
    :canon/date
    :canon/relation
    :canon/note
    :canon/digital-object
    :canon/loss-marker})

(defn documentary?
  "True if `predicate` is one of the canonical documentary
   predicates (ADR 0003 §Decision).

   The parallel of `palomar.model/structural?` for the documentary
   layer. Qualifier coords (`:canon/lang`, …), structural predicates
   (`:meta/*`) and native source predicates (`:dc/title`, …) are all
   false: only the canonical set are documentary."
  [predicate]
  (contains? documentary-vocabulary predicate))

;; ---------------------------------------------------------------------------
;; Validation rules (ADR 0002 rule DSL, run in the :validate phase)
;;
;; Validation rules are first-class data: they emit `:diagnostic`
;; productions, never throw, and attach the diagnostic to its subject
;; (ADR 0001). The runtime stamps each diagnostic's provenance with this
;; rule's id and the :validate phase, so `palomar.diagnostics` can filter
;; by rule or phase downstream.
;; ---------------------------------------------------------------------------

(def title-required-rule
  "Every record should carry a `:canon/title`. The `[?r :meta/kind ?k]`
   pattern anchors `?r` to the record id (every record has exactly one
   `:meta/kind`); the `absent?` guard then queries the record's unified
   triple view for any `:canon/title` and fires only when none is found.

   Severity is `:warning`, not `:error`: a titleless record is
   incomplete, not malformed — it still round-trips and projects. Under
   the default `:errors-only` failure policy a missing title does not
   fail a run; callers wanting stricter gating pass `:errors-and-warnings`
   to `palomar.diagnostics/should-fail?`. The `:missing-title` code
   matches the diagnostic vocabulary the runtime's own examples use."
  {:id      :rule.canonical/title-required
   :phase   :validate
   :match   '[[?r :meta/kind ?k]
              (absent? ?r :canon/title)]
   :produce {:diagnostic {:severity :warning
                          :code     :missing-title
                          :subject  '?r
                          :message  "Record has no :canon/title assertion"}}
   :doc     "Warn when a record carries no :canon/title (ADR 0003)."})

(def rules
  "The V1 canonical validation rule set. One grounded rule for now —
   `title-required` — exercised end-to-end by
   `palomar.canonical-integration-test`. Date/ISO and other cross-source
   rules wait for a concrete producer of the predicates they would check
   (growth discipline, ADR 0003 §Consequences)."
  [title-required-rule])

;; ---------------------------------------------------------------------------
;; Plugin map (ADR 0007)
;;
;; A plain data plugin carrying only `:rules`. It declares no importer or
;; mapping: format plugins produce `:canon/*`; this plugin validates it.
;; `palomar.plugins/all-rules` pools these for compilation, and any
;; registry that also holds a documentary plugin gets canonical
;; validation for free.
;; ---------------------------------------------------------------------------

(def plugin
  "The canonical vocabulary plugin (ADR 0003/0007): ships the documentary
   vocabulary (as the `documentary-vocabulary` value) and the canonical
   validation `rules`. Register it alongside any documentary plugin to
   enforce the canonical layer over that plugin's normalized output."
  {:plugin/spec-version 1
   :id                  :palomar/canonical
   :rules               rules
   :doc                 "Canonical documentary vocabulary and its V1 validation rules (ADR 0003)."})

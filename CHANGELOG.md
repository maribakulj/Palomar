# Changelog

All notable changes to Palomar are recorded in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

The version line for an unreleased work-in-progress is `[Unreleased]`. When a
release is cut, that section is renamed to the version and date and a fresh
`[Unreleased]` section is opened above it.

## [Unreleased]

The pre-1.0 development line. The Sprint 0–6 substrate and the rich-pivot
work packages WP-0…WP-8 are landed (seven spokes → the LRMoo pivot → ten
targets, loss-aware; conformance; streaming; the full CLI). WP-9
(hardening + release) is in progress; see `docs/roadmap-v1.md`.

### Added

- **Graph templates in the rule language** (ADR 0035): a `:construct`
  production (a vector of `[s p o]` triples and `(mint kind part…)`
  declarations), the terms `mint` / `ref` / `norm` / `str` / `or`, and the
  match clauses `(optional …)`, `(not …)` and `(ref ?x)`, so a mapping to
  CIDOC-CRM or a WEMI chain can be written as data. `lrmoo.wemi-rules` is the
  floor WEMI chain as four such rules, shown equal to `project` on 126 fixture
  records.

- **Library API and EDN IR** (ADR 0034): `palomar.api`, the stable entry
  points (pivot, export, convert, curate, audit, manifest…), pinned by a
  contract test; `--to edn` writes the pivot records whole, one versioned
  envelope per line, deterministically, and `--from edn` reads them back,
  validated — import once, curate, export many times. `palomar ir-schema`
  prints the published record schema (`docs/ir/record-schema.edn`).

- **Loss aspects and relation fidelity** (ADR 0033): a loss may name what it
  touched (`:value`, `:relation`, `:qualifier`, `:class`, `:granularity`,
  `:provenance`), counted `:by-aspect` in the report; the WEMI projections
  report on a new `:projection` edge; a stating target reports the source
  evidence it does not write; and the fidelity audit checks, for the
  N-Triples family, that every relation survives (`:relations`).
- **Graded alignments** (ADR 0032): `reconcile/align-agents` puts agent
  proposals into the records as `:proposed` `skos:exactMatch` /
  `skos:closeMatch` claims, curated, exported (SKOS in RDF, by status graph in
  the datasets) and kept in history; `reconcile/accepted-links` feeds accepted
  exact matches to `apply-agent-links`. No `owl:sameAs` is ever written.
- **Source evidence** (ADR 0031): each value records where it was read —
  an XPath for MARC subfields and DC/MODS elements, the canvas and `xywh`
  region for IIIF images, the source document's SHA-256 — carried through
  normalisation and projection (`:provenance :evidence`,
  `model/evidence-of`) and written to PROV-O as W3C Web Annotation
  `oa:SpecificResource`s.
- **Run manifest** (ADR 0030): `convert --manifest <file>` writes an EDN record
  of the run — version, plugins with mapping and rule digests, ontology files,
  input and output SHA-256, options, active rules, loss summary, times;
  `manifest/diff` names what changed between two runs.
- **Named-graph datasets** (ADR 0029): `:nquads` and `:trig` targets write
  facts, proposals, history and provenance as separate named graphs, typed in
  the default graph; `ntriples/parse-quads` reads N-Quads; `convert
  --facts-only` exports only in-force claims to a flat target.
- **Curation history** (ADR 0028): each decision of `curate`/`supersede` is
  stamped on the record as an annotation (verdict, statuses, reason, the
  replacement's id, and the caller-supplied curator and time);
  `curate/history` reads it back; the PROV-O target writes it as activities
  (`used`, `wasInvalidatedBy`, `wasRevisionOf`, `endedAtTime`).
  `apply-repairs --by <curator>` names the curator.
- **Assertion identity and annotations** (ADR 0027): `model/assertion-id`, a
  content id over the canonical subject, predicate and value
  (`model/canonical`, `model/content-digest`) that survives curation; a
  record's `:annotations` hold statements *about* assertions (decisions, an
  application's own relations), out of reach of the matcher and the exporters.
  One fact is now one assertion: the merge keeps the stronger status, so a
  re-derived rejected claim stays rejected. PROV statement IRIs are assertion
  ids (they change once, then stay stable).
- **Ontology conformance** (ADR 0026): `palomar.ontology` checks RDF output
  against the domains and ranges declared by the vocabularies' own files (the
  vendored CIDOC CRM 7.1.3 RDFS and LRMoo 1.0 OWL, plus the PROV-O terms
  Palomar writes); every spoke's LRMoo, CRM, pure-CRM and PROV-O output is
  pinned at zero violations. PROV statements are now typed `prov:Entity`.
- **Interoperability audit follow-up (2026-10-25)** — new capabilities:
  `palomar.fidelity`, an occurrence-level audit that decodes a conversion's
  output (N-Triples, JSON, XML) and classifies every source value as present,
  reported or unaccounted (all 70 spoke × decodable-target pairs are at zero
  unaccounted); `palomar.plugins.lrmoo.ntriples`, an RDF 1.1 N-Triples reader
  (escapes, language tags, datatypes, blank nodes, located errors);
  `palomar.plugins.agents` (role-aware agent reading); `palomar.plugins.record-id`
  (readable, injective record ids); `palomar.plugins.export-coverage`;
  `convert/export-records` (one export path, one status policy, optional
  `:in-force-only?`); `palomar apply-repairs --to <fmt>`; `curate/supersede` and
  curation reasons; `:provenance :bindings` and `:provenance :also`; the
  `not-in?` rule guard; four canonical validation rules (agent role, note
  kind, blank value, date year).

- **Review of the audit follow-up (2026-10-25)** — fixes found by re-reading it:
  - Export coverage judges a native field by whether its value is written
    (through the canonical field a spoke mapping lifts it to), reports a
    qualifier coord only when its field is written, and skips derived WEMI
    strings; the fidelity audit no longer lets a projection diagnostic excuse
    an absent value.
  - A `{:from :as}` qualifier renames only the coords of its own field's
    fragments (the DC title rule was giving descriptions their `:canon/lang`).
  - MARC21: 260 $b is a publisher agent; the export writes one 260 with $b
    and $c, keeps a publisher who is also the main creator, and orders
    fields by tag. MODS: `tableOfContents` and `note[@type=performers]` are
    contents and credits notes, so they round-trip.
  - IIIF import reports a Choice, an id-less Image, a non-integer extent and
    several metadata strings; an unemitted manifest reports nothing but the
    warning and the unwritten predicates; the manifest id is the first
    HTTP(S) URI.
  - RDF: titles and concept labels go through an E41 Appellation (`P1` →
    `P190`); language tags are validated; only title languages key an
    Expression; crm-import reports a typed node's extra `rdf:type`; the
    N-Triples reader rejects non-scalar escapes, relative IRIs and escaped
    forbidden characters, and accepts the full blank-node label grammar and
    CR line ends.
  - Linked Art names a text by all its strings, uses the Work's own IRI and
    title, and reports record-level fields when there is no Manifestation;
    PROV associates activities with the engine as a `prov:SoftwareAgent` and
    the rule as its `prov:hadPlan`.
  - One SHA-256 helper (`model/sha256-hex`) replaces four copies.

- **`NOTICE`** — the Apache-2.0 copyright line (`Copyright 2026 maribakulj`)
  plus a pointer to the licences of the third-party material redistributed
  under `test/fixtures/`. `README.md` and `CONTRIBUTING.md` now name the
  licence explicitly instead of only linking to `LICENSE`.

- **Machine-readable loss report** (WP-9, loss-report UX) — `palomar report …
  --format edn` emits the raw `conversion-report` map (ADR 0015) instead of the
  human text: the complete per-edge / per-category / **per-source-field** account
  (not the text view's top-8 truncation), pretty-printed and lossless (namespaced
  keywords intact, e.g. `#:canon{:date 2}`). This is the auditor's machine
  surface — run-to-run diffs, CI loss thresholds, dashboards. `--format text`
  (the default) is unchanged; an unknown `--format` exits 2. **`--format json`**
  emits the same map for non-Clojure audit tooling, with every keyword — map key
  *and* vector element — rendered as a **namespace-qualified** string
  (`:canon/date` → `"canon/date"`), so the source-field keys that data.json's
  default `name` key-fn would otherwise collide stay distinct (`palomar.cli-test`).

- **Degenerate-input hardening** (WP-9) — `convert` now emits a stderr warning
  when a parse yields **0 records** (a wrong `--from`, or the wrong file), so a
  silent exit-0 that writes an empty output no longer masquerades as success.
  Exit stays 0 — an empty collection is legal, so this warns rather than fails.
  Malformed input (truncated / non-XML) already exits 2 with a parse error
  rather than crashing; both contracts are now pinned by `palomar.cli-test`.

- **Streaming conversion** (WP-7 / DoD #6) — `palomar.convert/convert-stream`
  converts a reducible/lazy record stream in **constant working set**: it `reduce`s
  one record at a time, emits each rendered document via a callback, and folds a
  loss report bounded by the distinct fields/categories/edges (not the record
  count), via a new `palomar.loss-report` fold (`empty-acc`/`accumulate`/
  `finalize`). Sound because Work convergence is id-collision, not a global pass
  (ADR 0008), so per-record conversion has no cross-record state — the converter
  streams its triples, a downstream store deduplicates by id (roadmap §10). The
  streamed output and loss report are byte-identical to batch `convert`, minus the
  one genuinely O(N) figure (`:distinct-losses`, batch-only). Measured budget:
  **100 000 records (MARC21→N-Triples) in a 512 MB heap, ~70 MB used, throughput
  flat ~4 000 rec/s**, the loss accumulator's footprint invariant in N
  (`docs/eval/scale.md`, `palomar.convert-stream-test`).
- **Lazy input parse + CLI `--stream`** (WP-7, the one-giant-file story closed for
  the MARC family). `marcxml/stream-records` pull-parses a `Reader`
  (`data.xml/parse`) into a **lazy** record seq — bounded to one record at a time —
  wired as a plugin `:stream-importer` on MARC21 / INTERMARC / UNIMARC
  (`convert/streamable?` / `stream-source`) and surfaced as `palomar convert <in>
  --from <marc-fmt> --to <fmt> --stream --out <file>`, which writes the document
  incrementally in bounded memory. End-to-end: a **97 MB / 56 000-record flat MARC
  dump → 64 MB N-Triples in a 256 MB heap, ~33 MB used**, where the eager
  `parse-str` path OOMs. Bounded by construction to the *flat-collection* shape
  (direct children — a deep tree-seq retains ancestors); SRU pages are small and stay
  eager (stream at page granularity), and the non-MARC single-record spokes don't
  stream (`--stream` rejects them with the streamable set). A new optional plugin key
  `:stream-importer` (`palomar.plugins` schema).
- **Uniform-title bridging** — the WEMI-derivation recall step the fidelity doc names
  ("D-series"). A ninth canonical predicate `:canon/uniform-title` (ADR 0003 growth
  discipline: the cataloguer's controlled work title, distinct from the transcribed
  `:canon/title`), mapped from MARC 240 `$a` (and emitted back on export, so the
  round-trip stays lossless). The floor projection (`lrmoo.project`) now keys the
  Work/Expression on the uniform title when a record carries one — so editions whose
  *transcribed* titles differ but whose *uniform* title agrees mint the same Work and
  cluster; the Manifestation keeps its transcribed title. Records without a uniform
  title fall back to the transcribed title (unchanged behaviour). Measured on the
  independent BIB-R gold: recall **0.775 → 0.823** at **no precision cost** (still
  1.000) — `docs/eval/bibr-work-clustering.md`.
  Wired across the floor family: MARC 240 `$a`, **UNIMARC 500 `$a`** (titre
  uniforme — on real BnF data it unifies the French editions and the German
  translation of one work into a single Work), and **MODS `<titleInfo
  type="uniform">`** (also round-tripped back on export). The MODS change is a
  latent-bug fix too: a uniform `titleInfo` was previously conflated into
  `:canon/title`; it now maps to `:canon/uniform-title`. (Dublin Core has no uniform
  title; INTERMARC keeps its richer `145 $3` authority-link rung.)
- Independent WEMI-derivation eval against the third-party **BIB-R** benchmark
  (`palomar.eval.bibr-work-clustering-test`, `test/fixtures/bibr-gold/`,
  `docs/eval/bibr-work-clustering.md`). BIB-R ("Benchmark of FRBRization solutions",
  bib-r.github.io, CC BY-NC) is a hand-curated MARC→FRBR/RDA gold with no
  dependence on the BnF `f145` link — the non-circular gold `wemi-fidelity.md`
  §3 called for, and a third corpus for ADR 0018's recall ceiling. Palomar ingests
  the 560 MARCXML records, clusters by minted F1 Work, and is scored pairwise against
  the gold Work grouping (records joined by normalised title): **P = 1.000,
  R = 0.775, F1 = 0.873** over the joinable subset (362 / 560 — the join coverage is
  asserted as a first-class number, since the MARC `001`s share no identifier with
  the gold's title-slug URIs). Precision-first with a *measured* title-variant recall
  gap, on a gold owing nothing to `f145`. The gold grouping is derived from the
  benchmark's FRBR RDF (provenance in the fixture README); the input is committed
  gzipped to respect repo footprint.
- `palomar.conformance` — the WP-6 conformance mechanism + three institutional
  profiles (Linked Art / Louvre, BnF INTERMARC, IIIF Presentation 3.0). A profile
  is a named set of checks expressed as ordinary diagnostics (ADR 0001) over a
  projected record; the report is those diagnostics filtered to the profile, plus
  a pass/fail verdict under the shared failure policy — mirroring
  `palomar.validate` one rung up. Dataless by construction: the checks encode a
  **public** model, and the only institution-specific input is the acceptance
  *threshold* (the `policy` knob). Two directions, one mechanism. The **Linked Art
  (Louvre)** *target* profile checks the WEMI projection's fitness to serialise (a
  HumanMadeObject root with a name as hard requirements; the carried Expression,
  its Work, an authority-identified creator as richness warnings); the **IIIF
  Presentation 3.0** *target* profile checks fitness to produce a Manifest (a label
  as the one hard requirement; a Canvas-bearing digital object and a dereferenceable
  HTTP id as warnings — a real IIIF manifest is fully conformant, a bibliographic
  record is flagged as having nothing to present). The **BnF INTERMARC** *source*
  profile checks a bibliographic record's own native `:intermarc/*` fields (a 001
  control number and a 245 title as essentials; the 003 ARK, an authority-linked
  100 heading — Transition bibliographique — and a 260 date as expectations; the
  145 Work-link as a WEMI-derivation-readiness hint), grounded in what real BnF SRU
  records carry. Surfaced as the CLI verb `conformance <input> --from <fmt>
  --profile <linked-art|intermarc|iiif> [--policy <p>]`, exiting non-zero when the
  threshold is breached. This is **not** the strict official-schema validation
  (that stays a test-only eval against the draft-2020-12 Linked Art schema); a
  profile is the institution's requirement set, some of it stricter
  than the schema, some looser.
- `palomar.curate` — the ADR 0005 repair-application / curation engine, the
  last dataless WP-8 gap. A pure function over a record's *pending* assertions
  (`:proposed`/`:needs-review`): a curator `(fn [assertion] -> :accept | :reject
  | :review)` resolves each into the workflow family (`:accepted` / `:rejected`
  / `:needs-review`), leaving every in-force or already-resolved assertion
  untouched. Named policies (`accept-all`, `reject-all`, `flag-all`,
  `accept-when`) are ordinary decision functions, so an ADR 0018 promotion guard
  composes (`accept-when` accepts only the proposals safe to commit, routes the
  rest to review). The returned transition log is the audit record — the
  Provenance schema is deliberately left unchanged. Surfaced as the CLI verb
  `apply-repairs <input> --from <fmt> [--policy flag|accept|reject]`, which
  curates the `:proposed` WEMI claims a real conversion emits (the DC/MARC21
  string-key inference). Supersession of a replaced in-force assertion is
  documented as out of scope (it needs replacement semantics the proposals do
  not yet carry).
- Sprint 6: canonical vocabulary plugin (`palomar.plugins.canonical`).
  Ships the eight `:canon/*` documentary predicates from ADR 0003
  §Decision as data, with a `documentary?` membership predicate, plus
  the first validation rule over the canonical layer — `title-required`,
  a `:validate`-phase rule that emits a `:missing-title` warning when a
  record carries no `:canon/title`. `:canon/lang` stays a fragment-borne
  qualifier coord (ADR 0011), not a ninth documentary predicate; no ADR
  amendment.
- End-to-end canonical integration test in
  `test/integration/palomar/canonical_integration_test.clj`: a
  registry-driven ingest → normalize → validate → diagnostics-report
  pipeline over a titled and an untitled record, exercising
  `palomar.diagnostics/format-report` and the `should-fail?`
  failure-policy surface.
- Sprint 5: plugin protocol (`palomar.plugins`) with closed schema,
  registry with `:requires` topological resolution and `:input-format`
  dispatch, transform stdlib (`palomar.plugins.transforms`), mapping
  schema and compiler (`palomar.plugins.mapping`), and the generic
  JSON/XML shape adapter (`palomar.plugins.shape`) wired as
  registrable plugins. Fragments for qualified values per ADR 0011 +
  ADR 0012 (single-place `mint-fragment-id` minted at ingest).
- End-to-end shape-adapter integration test in
  `test/integration/palomar/shape_integration_test.clj`: ingest a DC
  record in both JSON-LD and XML, run through the V1 :normalize
  phase, assert canonical convergence.
- `org.clojure/data.xml` 0.2.0-alpha9 and `org.clojure/data.json` 2.5.1
  in `:deps`. Both resolve from Maven Central and do not require
  `:sandbox` rewiring (ADR 0006).

### Fixed

- **The PROV-O record keeps the history** (ADR 0025): rejected, superseded and
  retracted assertions reach the `:prov` output, reified with their status,
  where they used to be dropped while the target declared no loss. Every other
  target still leaves them out.
- **No invalid IRI is written** (ADR 0025): the RDF writers refuse one, a keyword
  id is percent-encoded (`--record-id` from a file name with a space), a blank
  node read by crm-import gets a minted id, and `apply-agent-links` refuses an
  authority id that is no IRI.
- **Interoperability audit (2026-10-25)**, items S01–S20 and several P2/P3:
  - Linked Art: a Manifestation is exported as a `LinguisticObject` (the text of
    an edition), not a `HumanMadeObject`; only an F5 Item is a physical object.
    One document per Manifestation and per Item, each following its own links.
    Publishers are a Publishing activity; contributors are reported as loss.
  - A publisher or contributor no longer keys a Work: agents carry a
    `:canon/role` (DC, MARC21 1XX/7XX, UNIMARC, INTERMARC, MODS publisher).
  - CRM import no longer downcasts an `E89` to a Work or an `E24` to an Item.
  - Rules no longer match rejected, superseded or retracted assertions, and no
    export emits them.
  - MARC field occurrences, indicators, subfield order and the leader are kept
    at ingest (as fragments and `:ns/leader`).
  - IIIF: every label/summary language and the Canvas extent are kept; only
    painted images are digital objects; a manifest missing a Presentation 3.0
    MUST is refused with a diagnostic instead of emitted invalid.
  - Exports report every predicate they do not write; a refused IIIF manifest
    reports all of them; Linked Art keeps Expression and Work titles.
  - An Expression is keyed on its title's language (original vs translation).
  - Record ids no longer collide when slugging drops information (a 40-bit
    digest of the full identifier is appended).
  - Two rules deriving one fact keep both justifications.
  - `convert --stream` no longer deletes `--out` before the move succeeds, and
    refuses an `--out` that is its own input.
  - Entity titles and concept labels are written to RDF through an E41
    Appellation (`P1_is_identified_by` → `P190_has_symbolic_content`), as
    neither R33 nor P190 may sit on a Work or a concept; the earlier Nomen form
    is still read.
  - Eval docs (BIB-R, OpenLibrary) no longer claim more than they measure.

- Self-review remediation of this line's WP-6/7 work: (1) the WEMI projection's
  loss accounting (`lrmoo.project` `language-losses`/`ambiguity-losses`) now keys
  on the same `work-title` condition that decides whether an Expression is minted
  (a new `work-title-of`), so a uniform-title-only record no longer under-reports
  language loss nor falsely reports `:ambiguity-collapsed` (latent — introduced by
  uniform-title bridging; guarded by a test). (2) `palomar.convert/convert-stream`
  via the CLI now writes `--out` atomically (temp file + rename), so a mid-stream
  parse error leaves no partial output, matching the batch path. (3)
  `curate/format-curation` tolerates a single-record `curate-record` result (no
  `:summary`) instead of NPE-ing. Plus docstring honesty (the canonical set is
  nine, not "eight"; the streamed-report import-edge caveat; DC needs `:record-id`).
- `palomar.plugins.mapping/compile-mappings` now rejects a batch whose
  mapping rules derive the same compiled rule id (a cross-plugin
  `:mapping/id` name collision), instead of silently conflating their
  provenance in the trace (ADR 0009 §Open V2).
- `palomar.model/mint-fragment-id` now rejects inputs that could collapse
  two distinct fragments onto one id: a `-` in a locator predicate's
  namespace, or a `.` in any record-id or predicate segment. Predicate
  *names* may still contain hyphens. This enforces ADR 0012's injectivity
  preconditions at construction, so a minted id always round-trips.
- `palomar.rules` produce-template substitution no longer treats a variable
  bound to `false` or `nil` as unbound. `substitute` tested a binding's truth
  instead of its presence, so a legitimately falsey-bound `:produce` variable
  raised "Unbound variable in produce template"; it now distinguishes presence
  from truth (`find` + `if-let`, as `position-match` does). Latent (no current
  rule binds a falsey value) and guarded against regression by a behavioural test.

### Changed

- Assertion ids are spelled `:asr/h<16 hex>` (ADR 0034, amending ADR 0027):
  a name starting with a digit does not read back as EDN. PROV statement IRIs
  follow (`urn:palomar:stmt:h…`).
- The INTERMARC-NG and CRM importers stamp `:pass :ingest` (they wrote
  `:import`, which is no phase: their records failed the record schema).
- `--record-id` and the file-derived DC record id start with a letter.

- **Behaviour changes from the interoperability audit (2026-10-25):** a MARC
  record with only added entries (no 1XX) derives no Work; MARC21→DC emits 7XX
  agents as `dc:contributor` (the LoC oracle eval now reads 4/9 exact element
  types, with the divergence asserted); Linked Art roots are `LinguisticObject`;
  IIIF record ids carry a digest (`:iiif/m-<label>-h<digest>`); MARC21→IIIF
  emits no manifest (no URI id, no canvas extent) and says why; loss counts
  grow where losses used to be silent.

- **The project is now named Palomar**, and every identifier that carried the
  former name was migrated with it. Sources live under `src/palomar/` and tests
  under `test/{unit,property,integration,junit}/palomar/`; every namespace is
  `palomar.*`; the CLI is invoked as `palomar …`, with `palomar.cli` and
  `palomar.junit-runner` as the entry points. Two of the renamed identifiers
  reach the output: the synthetic fallback IRI scheme is now `urn:palomar:`
  (ADR 0013 — it applies only to minted `:ent/*` entities that carry no
  authority IRI), and the plugin ids are `:palomar/canonical`,
  `:palomar/dublin-core`, `:palomar/iiif`, `:palomar/intermarc`,
  `:palomar/intermarc-ng`, `:palomar/lrmoo`, `:palomar/marc21`, `:palomar/mods`
  and `:palomar/unimarc`, alongside the rule key `:palomar.rules/runner`.
  Consumers pinned to the previous IRI scheme or plugin ids must update.
  No mapping, rule, fixture or eval result changed: 599 tests / 2436 assertions
  pass unchanged, and the C2 Bovary, showcase-boundary and BIB-R evals return
  byte-identical numbers.

- Terminology: *FRBRisation* → *WEMI derivation (LRMoo)* across code, ADRs and
  evals; `palomar.plugins.intermarc.frbrise` → `palomar.plugins.intermarc.wemi`,
  `frbrise` → `derive-wemi`, rule `:rule.intermarc/frbrise` →
  `:rule.intermarc/derive-wemi`. No behavioural change.

- **Phases are now a single pass (ADR 0020, supersedes ADR 0004).** Each phase
  fires every matching rule once against the record as it entered the phase;
  multi-cycle execution is gone. `run-phase` loses its `{:cycles N}` options
  arity (and `run-phase-once` folds into it); `run-pipeline` / `PhaseSpec` no
  longer accept a `:cycles` key. Intra-phase chaining (rule B reacting to rule
  A's output) is now expressed across phases, not by re-running one. Dedup at
  merge (ADR 0008) is retained — it now collapses same-fact productions from
  two rules in a single pass. No pipeline used `:cycles`, so callers are
  unaffected.

- Post-Sprint-5 audit cleanup (no feature change): documentation and config
  reconciled with the tree (removed the phantom `dev.clj` / `resources`
  references); a test now guards that the core never depends on
  `palomar.plugins.*`; `palomar.plugins/topo-order` is marked `^:no-doc`
  (provisional — no consumer yet, still callable). Tracked in
  `docs/cleanup/remediation-pass.md`.

### Removed

- **The `:project` pipeline phase and multi-cycle execution (`:cycles`)** —
  both dormant (ADR 0020). `:project` appeared in every phase enum but was
  targeted by no rule: projection to the ten targets is done by exporters
  after the pipeline, and canonical→WEMI runs in `:infer`. `:cycles` (with
  `max-cycles` and `validate-cycles!`) drove a multi-pass loop no pipeline
  ever requested. The phase enum is now
  `:ingest :normalize :validate :infer :repair`. The `:repair` phase stays
  and is newly documented (ADR 0020, README, the `Phase` schema). `clj-kondo`
  and `cljfmt` stay clean.

- **Substrate right-sizing (no behaviour change): dead code with no production
  consumer.** Removed the `:requires` dependency-graph machinery from
  `palomar.plugins` — `requires-graph`, `validate-requires!`, `topo-order` and
  the private Kahn topological sort. It was documentary-only (it isolated
  nothing — no stdlib visibility or rule pools depended on it) and the real
  spoke dispatch is the `palomar.spokes` map; it was exercised only by its own
  unit tests. This resolves the provisional `^:no-doc topo-order` note above by
  removal rather than deprecation. Also removed `palomar.model/parse-fragment-id`,
  the inverse fragment-id decoder: fragments are minted by the shape adapter and
  never decoded back, so it had no caller — `mint-fragment-id` and its
  injectivity guards are unchanged. Tests for the removed code were deleted with
  it; `clj-kondo` and `cljfmt` stay clean.

- **Shape adapter reduced to its production path (XML only).** The generic shape
  adapter shipped JSON-LD *and* XML walkers plus `shape-json-plugin` /
  `shape-xml-plugin` factories, but no production spoke used them: Dublin Core
  (the sole consumer) inlines `rewrite-tags` + `ingest-xml` directly, and IIIF —
  the only JSON spoke — parses with `clojure.data.json`, not the shape JSON
  walker. Removed the JSON walker (`ingest-json` and its helpers) and both
  factories; `palomar.plugins.shape` no longer requires `palomar.xml` or
  `clojure.data.json`. The cross-format JSON↔XML equivalence (ADR 0012) was a
  tested-but-unused promise; its unit/integration tests were removed (the
  redundant `shape-integration-test` deleted; the `canonical` and
  `universal-pivot` integration tests rewritten to inline XML plugins). XML
  ingestion, fragments, the `xml:lang` qualifier and the `:trim` reconciliation
  stay fully covered by `dc-test` and the XML half of `shape-test`.

- **Plugin registry trimmed to its used surface.** Removed the unused
  `:input-format` dispatch (`select-importer` + the `:matches?` sniffing and
  "exactly one eligible" rule) — the production spoke dispatch is the
  `palomar.spokes` `format → plugin` map, not this registry — together with the
  test-only query helpers (`plugins-for-format`, `importers-for`,
  `exporters-for`) and the dead predicate-side stdlib accessors
  (`effective-predicates`, `predicate-source`): no plugin declares
  `:predicates`, so they had nothing to resolve. Kept: the Plugin schema,
  `register` / `unregister` / `lookup` / `registered-ids`, rule/mapping pooling
  (`all-rules` / `all-mappings`), the `effective-stdlib` merge with its
  collision detection, and `effective-transforms` / `transform-source` (the live
  transform side).

- **Runtime trace-query helpers removed.** `trace`, `assertions-by-rule`,
  `diagnostics-by-rule` and `productions-by-phase` were a read-side API over the
  provenance the runtime stamps, with no production consumer. The provenance
  itself is unchanged — every merged assertion/diagnostic still carries
  `[:provenance :rule]` and `[:provenance :pass]` — and the few tests that
  verified attribution now read those keys directly, so the contract stays
  covered without the helper layer.

### Security

- **XML input hardening (WP-9).** All XML importers now parse through a single
  façade, `palomar.xml`, which **refuses DTDs** (`:support-dtd false`) instead of
  calling `clojure.data.xml` directly. This closes a real `billion laughs`
  entity-expansion denial of service: `clojure.data.xml` expands internal
  general entities with no size bound and the JDK's `entityExpansionLimit` does
  not fire through its StAX path (verified — a small nested-entity payload
  expanded unbounded). Refusing DTDs also removes any DTD-borne XXE surface
  (external `SYSTEM`/`PUBLIC` entities were already unresolved by data.xml;
  `:supporting-external-entities false` is now pinned for defence in depth). No
  fixture or supported format uses a DTD, so the policy is total. Wired across
  MARC-XML (and its INTERMARC/UNIMARC/INTERMARC-NG dialects), MODS, Dublin Core
  and the generic shape adapter; pinned by `palomar.xml-test` on both the eager
  and streaming parse paths; documented in `SECURITY.md` (Hardening).

### Earlier in this line

- Property-based tests covering rule-engine determinism, triple-view
  losslessness, provenance, merge accumulation and severity ordering
  (test/palomar/property_test.clj).
- `cloverage` coverage report published as a CI workflow artifact, with
  a per-namespace plaintext summary in the run summary panel.
- `CONTRIBUTING.md`, `SECURITY.md`, GitHub issue templates and this
  changelog.
- End-to-end integration test scenario covering all four V1 phases
  (test/integration/palomar/integration_test.clj).
- Test layout reorganized into `test/unit/`, `test/property/`,
  `test/integration/` with matching `:test/unit`, `:test/property`,
  `:test/integration` aliases for category-scoped runs.
- JUnit XML output via a custom `:test/junit` runner, published to the
  GitHub check panel through `EnricoMi/publish-unit-test-result-action`.
- README badges (CI status, license, Clojure / Java version).
- Codecov integration: cloverage reports are uploaded to Codecov on
  every CI run via `codecov/codecov-action`. PRs receive a per-file
  diff coverage comment. Thresholds configured in `codecov.yml`:
  project = `auto` with 1% noise floor, patch (new code) = 80%.

### Changed

- Plugin architecture documentation: ADR 0007 revised in place to
  specify the `source` contract, reducible records, `:requires`
  topo-sort, `:input-format` dispatch, and `:plugin/spec-version`.
  ADR 0009 introduces the `:mapping` schema; ADR 0010 specifies how
  plugins extend the predicate / transform stdlib safely.
- `record-triples` now exposes the full structural vocabulary (six
  predicates, was three). `absent?` / `present?` query the unified
  triple-view rather than `:assertions` only — fixes a silent
  matcher / guard divergence.
- Productions deduplicate at merge time by structural identity
  (ADR 0008). Identical assertions or diagnostics no longer accumulate
  across cycles. `:cycles N` has clear semantics for the first time.
- `palomar.diagnostics` severity tolerance is consistent: every
  function rejects unknown severities (the schema already pins the
  enum). Format strings use uniform `pr-str` rendering across
  subjects and codes.
- `Primitive` schema rejects NaN and the infinities at validate time,
  not just at generation time.
- Rule production provenance: engine fields (`:rule`, `:pass`) are
  authoritative; template-supplied `:source`, `:derivation` and
  `:timestamp` are preserved by deep-merge.
- `compile-rule` now distinguishes pattern-bindings from guard-usages.
  A variable referenced only by a guard fails at compile time.
- `compile-rule`'s internal runner key renamed `::run` →
  `:palomar.rules/runner` for clarity in pprint / inspection.

### Added (model)

- `record-consistent?` and `explain-consistency` enforce the contract
  that assertion / diagnostic subjects match the record id or one of
  its fragment ids. Importer authors now have a first-class predicate
  to assert against.
- `finite-double?` is public so importers can guard inputs.

### Deprecated

- Nothing yet.

### Removed

- The `dev` extra-path in the `:dev` alias of `deps.edn`. The
  directory never existed; the entry was cargo-culted.

### Fixed

- See "Changed" above for the matcher / guard coherence fix and the
  no-dedup runtime drift.

### Security

- No security advisories at this time. See SECURITY.md for the
  disclosure policy.

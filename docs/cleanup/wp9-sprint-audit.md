# WP-9 — Sprint 0–6 archaeology and the keep / delete / modify plan

- Status: **analysis** (2026-06-06). The first WP-9 deliverable.
- Method: three cross-checked surveys — (1) the current `src/` tree + consumer
  map, (2) the original twelve-sprint plan + ADRs 0001–0019 + the redefinition's
  "kept / reopened / superseded" lists, (3) a dead-code / staleness hunt — then
  manual verification of every claim acted on.

This answers: *what did the first six sprints build, what survived the rich-pivot
redefinition, what is dead*, and gives a component-level **keep / delete / modify**
plan to drive the hardening pass. **Nothing is deleted or modified by this document
— it is the plan; execution waits on sign-off** (per the remediation working
agreement: surface before removing).

---

## 1. The two plans

The original V1 (`README.md`, now superseded by `docs/roadmap-v1.md`) was a
twelve-sprint plan. **Sprints 0–6 landed = the substrate; 7–12 are superseded** by
the work-package plan.

| # | Original sprint | Status today |
|---|-----------------|--------------|
| 0 | Foundations (scaffolding, CI, ADRs 0001–0006, `:sandbox`) | **KEPT** |
| 1 | Canonical model (`palomar.model`, Malli, EDN round-trip) | **KEPT** |
| 2 | Rule DSL (`palomar.rules`) | **KEPT** |
| 3 | Runtime (`palomar.runtime`, phases) | **KEPT** |
| 4 | Pipeline + diagnostics (`palomar.diagnostics`) | **KEPT** |
| 5 | Shape adapter + plugins + mapping + fragments | **KEPT** |
| 6 | Canonical vocabulary plugin (`palomar.plugins.canonical`) | **KEPT, extended (8→9 predicates)** |
| 7 | Dublin Core | **DELIVERED** (under WP-4) |
| 8 | **CSV adapter + MARC-XML-lite** | **DEAD** — never built; superseded by *full* MARC21 / UNIMARC / INTERMARC |
| 9 | Repair + apply-repairs | **DELIVERED** (`palomar.curate`, WP-8) |
| 10 | CLI | **DELIVERED** (`palomar.cli`, WP-8) |
| 11 | Hardening | **= WP-9 (this pass)** |
| 12 | Docs + release | **= WP-9 (pending)** |

**Headline: the redefinition was an extension, not a rewrite.** Every Sprint 0–6
substrate component is alive and load-bearing — `roadmap-v1 §2`'s "kept as-is" list,
confirmed by the consumer map (`model` has 20+ requirers, `diagnostics` 13+, `rules`
5, `runtime` 4). Three substrate ADRs were *reopened and amended*, not discarded:
0011→0014 (minting lifted to `infer`), 0008 (deterministic synthesized identity),
0004 (decided: bounded passes, not a fixpoint).

## 2. Sprints 0–6, component by component

| Component | ADR | Status | Evidence |
|-----------|-----|--------|----------|
| `palomar.model` (assertion IR) | 0001 | **KEPT** | ground truth; 20+ src requirers |
| `palomar.rules` (rule DSL) | 0002 | **KEPT** | 5 requirers; compiles all rules/mappings |
| `palomar.runtime` (phases) | 0004 | **KEPT** | 4 requirers; `run-phase`/`run-pipeline` everywhere |
| `palomar.diagnostics` | 0005 | **KEPT** | 13+ requirers; status model now also feeds `curate` |
| `palomar.plugins` / `.transforms` / `.mapping` / `.shape` | 0007/0009/0010 | **KEPT** | plugin spine; `shape` used by DC |
| fragments (`mint-fragment-id`) | 0011/0012 | **KEPT** | minting lifted to `infer` by 0014 |
| `palomar.plugins.canonical` | 0003 | **KEPT, EXTENDED** | required by `validate`; vocab grew 8→9 (`:canon/uniform-title`) this line |

**No Sprint 0–6 component is dead.** The only substrate thing ever removed was the
cargo-culted `dev` extra-path (CHANGELOG › Removed).

## 3. The keep / delete / modify plan

### KEEP — the live system (no action)
- **Core (4):** `model`, `rules`, `runtime`, `diagnostics`.
- **Plugin infra (6):** `plugins`, `plugins.transforms`, `plugins.mapping`, `plugins.shape`, `plugins.canonical`, `text`.
- **Spokes (8 importers):** `intermarc`, `marc21`, `unimarc`, `marcxml`, `dc`, `mods`, `iiif`, `intermarc-ng`.
- **Pivot + exporters (12):** `lrmoo`, `lrmoo.project`, `lrmoo.view`, `lrmoo.crm`, `lrmoo.export`, `lrmoo.linked-art`, `lrmoo.crm-import`, `intermarc.wemi`; `dc.export`, `marc21.export`, `mods.export`, `iiif.export`.
- **Pipeline + entry (8):** `convert`, `validate`, `conformance`, `curate`, `reconcile`, `loss-report`, `spokes`, `cli`.
- **CI:** `test/junit/palomar/junit_runner.clj` (drives the JUnit CI job — *not* dead).
- **ADRs:** all 19 (0001–0019, Accepted).
- **Docs:** `roadmap-v1`, the 7 `eval/` docs, the 2 `cleanup/` docs, the spike, `sprints/sprint-5+6`, the `wp0-*` docs, `museum-spoke-scoping`.
- **Fixtures + provenance:** everything a test reads, plus `MANIFEST.tsv` / `documentary/README.md` (the provenance record) and `c2-gold/bovary/workmanifested.rq` (the SPARQL that *built* the C2 gold — reproducibility provenance, keep).

> Two survey claims corrected by manual check: `palomar.plugins.canonical` **is**
> `:require`d by `validate.clj`; `test/junit/` holds the **CI runner** (both KEEP).

### DELETE — genuinely dead (one item)
1. **`src/palomar/app.clj`** — an empty *"Not yet implemented"* stub listing
   aspirational commands; the real CLI is `palomar.cli`. Referenced only by
   `smoke_test.clj` (a `find-ns` load-check). → remove the file **and** the
   `[palomar.app]` require + the `(find-ns 'palomar.app)` assertion in
   `test/unit/palomar/smoke_test.clj`.

### MODIFY — stale docs (the DoD #7 "README reflects V1" work)
1. **`CHANGELOG.md` `[Unreleased]` header (l.14–15)** — *"Sprint 7 (Dublin Core
   plugin) is the next milestone"* is long-stale (DC + 8 spokes + conformance +
   streaming all shipped). Rewrite to the real development-line state.
2. **`README.md`** — half-updated: it carries the redefinition note + the accurate
   scope-reversal, but still **foregrounds the old 12-sprint plan** and a
   Sprint-labelled structure diagram, and **omits** the delivered surface
   (conformance, streaming, curation, the 8 spokes, the loss report, WEMI derivation,
   the CLI verbs). DoD #7 → restructure so the **rich-pivot V1 is primary** and the
   sprint history is clearly historical.
3. **`docs/roadmap-v1.md`** — the "Current state (2026-06-03)" header is date-stale
   (content is current); refresh, and flip the WP-6/7 rows that this session closed.
4. *(low)* The Sprint-6 CHANGELOG entry says "eight `:canon/*` predicates" —
   historically accurate (Sprint 6 shipped eight); add a "(now nine — see Added)"
   pointer rather than rewrite history.

### DECIDE — unused fixtures for formats with no spoke (your call)
A cluster of committed fixtures are real third-party samples (from the earlier
data-acquisition pass) for formats Palomar has **no importer** for, so no test reads
them: **EAD** (2002/3), **EAC-CPF**, **MADS** (`.ttl`/`.rdf`), **METS** (×3),
**ALTO** (`gallica/alto`), `bnf-rdf/machiavel.json`, the **CIDOC_CRM_v7.1.3.rdf**
ontology (424 KB), and the INTERMARC **ISO-2709 `.not`** binaries (×4 — a different
encoding the MARCXChange parser doesn't read). They break nothing; they are
future-spoke seeds *or* dead weight. Two honest options:
- **(a) Keep as documented seeds** — annotate them "no spoke yet (future)" in
  `documentary/README.md` / `MANIFEST.tsv`. Zero deletion. **(recommended** — per
  the working agreement I don't delete data you gathered without sign-off, and they
  are cheap.)
- **(b) Prune for a lean 1.0.0 tree** — delete the formats explicitly out of V1
  scope (EAD/EAC-CPF/MADS/METS/ALTO/ISO-2709), reclaiming ~1 MB; the MANIFEST records
  provenance so they are re-fetchable.

## 4. Beyond this audit — the rest of WP-9
After keep/delete/modify lands: the **security statement** — plugin-trust model
(ADR 0010 trust-on-require vs institutional deployment) plus XML input hardening
(DTD refusal closing billion-laughs / XXE; **landed** in `SECURITY.md` +
`palomar.xml`) — then edge-case + golden tests, loss-report UX, and the
**v1.0.0** cut.

---

## Bilan
The codebase is **healthy**: the entire Sprint 0–6 substrate is live, there is
**exactly one dead module** (`app.clj`), **no dead namespaces or public vars** in
the core, and the cruft is stale *docs* + future-format *fixtures* — not rotten
code. The redefinition extended a deliberately forward-compatible substrate rather
than replacing it. WP-9's real work is therefore **documentation reconciliation
(DoD #7)** + one deletion + a fixture decision + the release-hardening tail.

# Palomar V1 — Redefined Roadmap (rich LRMoo pivot)

- Status: **Proposed**
- Date: 2026-05-31
- Supersedes: the twelve-sprint roadmap table in [`README.md`](../README.md) (§ Roadmap)
- Builds on (kept): ADR 0001, 0002, 0003, 0005, 0007, 0009, 0010, 0012
- Amends / reopens: ADR 0004 (fixpoint — since resolved by D8, then ADR 0020), ADR 0008 (idempotency), ADR 0011 (minting)
- Introduces (WP-0): a Pivot ADR, a Loss-model ADR, a WEMI-derivation ADR, a Conformance ADR

> How to read this. Work packages are **dependency-ordered**; each carries an
> explicit **gate** (exit criterion) and the ADRs it touches. Nothing here is
> code yet: the foundational ADRs are themselves the first deliverable (WP-0).
> Timeline ranges are honest, not optimistic — see § 5.

---

## 1. Why this redefinition

The original V1 targets a *thin* canonical vocabulary (~10 `:canon/*`
predicates) and a small set of flat-ish formats (Dublin Core, CSV, a MARC-XML
*lite* subset). The new criteria raise the bar to a **production-grade,
loss-aware conversion hub** usable by flagship institutions (BnF, Louvre):

- a **rich pivot** grounded in **LRMoo** (the object-oriented expression of
  IFLA LRM, a specialization of CIDOC-CRM) — see § 3;
- **real** MARC21 (not a lite subset), **IIIF**, **CIDOC-CRM / Linked Art**,
  and native **LRMoo / RDF** as first-class spokes;
- **loss-aware** projection: every conversion emits a measurable loss report;
- **conformance** to institutional profiles (e.g. BnF INTERMARC/UNIMARC,
  museum Linked Art/IIIF profiles).

This **reverses an explicit V1 boundary.** Today `README.md` lists
IIIF, CIDOC CRM, Linked Art **and deduplication** as *deliberately out of
scope* for V1. The redefinition pulls all four in. (Deduplication arrives
implicitly: cross-record Work clustering is the heart of WEMI derivation —
see WP-3.)

It also **cashes in an option the team already bought.** `README.md`
(§ Roadmap, "Architectural compatibility constraint") records that the V1 IR,
vocabulary layering, and mapping schema were deliberately shaped to
accommodate event-centric and nested-resource models *without rework* —
which is exactly what produced the qualified-value design of ADR 0011. The
rich pivot is the intended evolution path, not a detour.

---

## 2. What is kept, what is reopened

**Kept as-is (the substrate — Sprints 0–6, landed):**

- the assertion-based IR (ADR 0001) — ground truth stays flat assertions;
- the agnostic core / vocabulary layering (ADR 0003) — no ontology in the core;
- plugins-as-data (ADR 0007) and the stdlib-extension mechanism (ADR 0010);
- the rule DSL (ADR 0002), mapping schema (ADR 0009), status model (ADR 0005);
- the seven design principles and ADR discipline.

**Reopened or amended:**

- **ADR 0011** — lift the *ingest-only* restriction on fragment/entity minting:
  `infer`/`repair` rules must be able to **mint** synthesized entities
  (WEMI derivation needs to create Work/Expression nodes that exist in no source).
- **ADR 0008** — synthesized-entity identity must be a **deterministic
  function of source content**, so re-running an idempotent merge produces no
  duplicate entities. Minting and idempotency are reconciled here.
- **ADR 0004** — "fixed passes, no fixpoint" is under pressure: WEMI inference
  may require iterating until no new entities appear. WP-0 decides whether V1
  keeps fixed passes (with a bounded iteration count) or admits a scoped
  fixpoint for the `infer` phase. *Resolved:* WP-0 chose bounded fixed passes
  (D8); the WEMI-derivation spike then found the inference cascade unexercised
  (explicit Work links are sparse), so ADR 0020 simplified the runtime to a
  **single pass per phase** — re-introduce bounded iteration only if a future
  Work-synthesis spike forces it.
- **`README.md`** — the roadmap table and the out-of-scope list are rewritten
  (task in WP-0).

**Strategy lock (decided, see prior design discussion).** The pivot uses
**strategy C**: assertions remain ground truth; a **derived, typed LRMoo
graph view** is computed on top, and it lives **in a plugin**, not the core.
C is conditioned on ADR 0003: it is "plugins-as-data" applied to the ontology
itself — the LRMoo plugin is the rich sibling of `palomar.plugins.canonical`.
Reopening 0003 (letting the core own an ontology) is the *only* thing that
would reopen the C-vs-typed-core-IR choice; as long as 0003 stands, C stands.

---

## 3. Locked architecture (recap)

```
  spoke importers ──▶  assertions (IR, ground truth)  ──▶  spoke exporters
     (MARC21, DC,          ▲   provenance · confidence ·         (LRMoo/RDF,
      IIIF, CRM…)          │   status · diagnostics              Linked Art,
                           │                                     IIIF, MARC21…)
                  derived LRMoo typed view  (palomar.plugins.lrmoo)
                  F1 Work · F2 Expression · F3 Manifestation · F5 Item
                  + reused CIDOC-CRM classes/properties
```

- **Pivot vocabulary: LRMoo.** It is the smallest standard that subsumes both
  the bibliographic WEMI world and the event-centric CRM world, because it
  *extends* CIDOC-CRM. Plain CRM under-specifies WEMI; Linked Art / BIBFRAME
  are better as **spokes** (profiles), not the hub; generic RDF is too shallow
  to even express the distinctions whose loss we must report.
- **Down-projection is free.** LRMoo F-classes are subclasses of CRM classes,
  so the museum spoke (CRM / Linked Art) is reached by walking up the
  hierarchy. The reverse (CRM → WEMI) is lossy and needs inference — which is
  why the hub is the richer model.
- **The typed view is derived**, so it cannot drift from ground truth.

---

## 4. Work packages

Each WP lists its goal, key deliverables, dependencies, ADRs touched, and the
**gate** that must be green before downstream WPs build on it.

### WP-0 — Design lock + WEMI-derivation spike
- **Goal:** settle the load-bearing decisions in ADRs before writing engine code.
- **Deliverables:** Pivot ADR (C + LRMoo, with the 0003 dependency stated);
  ADR 0011 amendment (runtime minting); ADR 0008 reconciliation (deterministic
  synthesized identity); ADR 0004 decision (fixpoint vs bounded passes);
  Loss-model ADR; WEMI-derivation ADR; Conformance ADR; rewrite of the `README.md`
  roadmap + out-of-scope sections. **Throwaway WEMI-derivation spike** on a small
  real MARC sample to de-risk WP-3 before committing the plan.
- **Depends on:** nothing (kicks off the program).
- **Gate:** ADRs reviewed and at least Proposed; the spike demonstrates a
  plausible MARC→WEMI path with measurable, reproducible Work identity.

### WP-1 — Substrate extensions
- **Goal:** the minimal core/runtime changes strategy C requires.
- **Deliverables:** runtime minting in `infer`/`repair` with deterministic
  identity (implements the 0011/0008 amendments, extends the 0012 identity
  scheme to synthesized entities); explicit **reference-typing** primitives in
  the substrate strong enough to derive a typed view; **loss as a first-class
  diagnostic category**; a **single pass per phase** (ADR 0020 — the
  bounded-iteration/scoped-fixpoint option once planned per the WP-0 ADR 0004
  decision was dropped, the inference cascade being unexercised).
- **Depends on:** WP-0.
- **Gate:** entities mint reproducibly (idempotent re-run = zero new entities);
  all existing 0008 idempotency property tests stay green.

### WP-2 — LRMoo pivot plugin + derived typed view
- **Goal:** the rich canonical layer as a plugin (`palomar.plugins.lrmoo`).
- **Deliverables:** the LRMoo vocabulary (F1/F2/F3/F5 + reused CRM
  classes/properties); **projection rules + a view API** that materializes the
  typed graph from assertions (C2); mapping to/from `:canon/*` where sensible;
  loss-marker integration.
- **Depends on:** WP-1.
- **Gate:** assertions ↔ LRMoo view round-trips; the view is provably derived
  (regenerating it from assertions is idempotent and side-effect-free).

### WP-3 — WEMI-derivation engine  *(highest technical risk)*
- **Goal:** synthesize WEMI from flat catalogue data.
- **Deliverables:** `infer`/`repair` rule sets that derive Work/Expression/
  Manifestation/Item and their R-relations from MARC records; **cross-record
  Work clustering** (same Work across many manifestations → one minted Work,
  stable identity); loss accounting for ambiguous cases and cases that admit no
  WEMI derivation.
- **Depends on:** WP-1, WP-2; informed by the WP-0 spike.
- **Gate:** on a real MARC corpus, MARC→WEMI with a published fidelity metric
  and loss report; idempotent identity verified at scale (re-run mints nothing).

### WP-4 — Spoke plugins
- **Goal:** the real importers/exporters, ordered by value and risk.
- **Sequence (recommended):**
  1. **Dublin Core** (in/out) — simplest; the end-to-end *pipe-cleaner* that
     validates the pivot with low complexity. Keep from the original Sprint 7.
  2. **MARC21 real** (in/out) — full fields/subfields, not lite; the highest-
     value spoke (BnF). Couples to WP-3 via WEMI derivation.
  3. **IIIF Presentation** (in) — import manifests; link digital objects to
     pivot entities (`:canon/digital-object`).
  4. **CIDOC-CRM / Linked Art** (out, maybe in) — museum spoke; Linked Art as
     an export *profile* of CRM (Louvre).
  5. **LRMoo / RDF (JSON-LD, Turtle)** (out) — serialize the pivot itself.
- **Depends on:** WP-2 (all), WP-3 (MARC, partially IIIF linking).
- **Gate (per spoke):** round-trip + loss report + a coverage metric against
  the LRMoo pivot (the ADR 0003 "rated on canonical coverage" rule, lifted to
  the rich vocabulary).

### WP-5 — Loss-aware projection (productized)
- **Goal:** make loss a measurable, first-class output, not a footnote.
- **Deliverables:** a structured loss model (categories: *dropped*, *coerced*,
  *under-specified*, *ambiguity-collapsed*), computed at both edges
  (import A→pivot, export pivot→B); per-record and aggregate reports
  (machine + human-readable); a coverage percentage. Built on diagnostics +
  `:canon/loss-marker` + the WP-0 Loss-model ADR.
- **Depends on:** WP-1 (loss diagnostics), grows with each WP-4 spoke.
- **Gate:** "what did I lose converting X→Y?" answerable for every spoke pair,
  with numbers an institutional evaluator can audit.

### WP-6 — Conformance to institutional profiles
- **Goal:** "prod-ready *for this institution*" — usually underestimated.
- **Deliverables:** institutional profiles (BnF INTERMARC/UNIMARC specifics,
  IIIF Presentation 3.0, a Linked Art profile) expressed as **validation rule
  sets**; conformance reports = diagnostics filtered to a profile.
- **Depends on:** WP-4, WP-5; **external data** (see § 7).
- **Gate:** a passing conformance report for ≥1 BnF profile and ≥1 museum
  profile on real institutional samples.

### WP-7 — Scale & performance
- **Goal:** real corpora (BnF catalogues are millions of records).
- **Deliverables:** streaming end-to-end (ADR 0007 already left the door open
  via reducible records); memory/throughput budgets; the hard one —
  **cross-record Work clustering at scale**, which is inherently global/stateful
  and in tension with streaming.
- **Depends on:** WP-3, WP-4.
- **Gate:** a realistic corpus processed within a stated time/memory budget.

### WP-8 — CLI / packaging / DX
- **Goal:** drive everything from the CLI (`palomar.cli`).
- **Deliverables:** `convert`, `validate`, `report` (loss), `conformance`,
  `apply-repairs`; packaging for institutional deployment.
- **Depends on:** WP-4, WP-5, WP-6.
- **Gate:** every capability reachable from the CLI on real inputs.

### WP-9 — Hardening & release
- **Goal:** ship V1.
- **Deliverables:** edge cases, golden tests on real data, docs, loss-report
  UX, a security statement covering the plugin trust model (ADR 0010
  trust-on-require vs institutional deployment) **and** XML input hardening
  (DTD refusal — closes billion-laughs / XXE; landed, see `SECURITY.md`),
  `v1.0.0`.
- **Depends on:** all.
- **Gate:** the Definition of Done (§ 8).

### Current state (2026-06-06)

| WP | status |
|----|--------|
| WP-0 design lock + spike | ✅ |
| WP-1 substrate (minting, loss diagnostic) | ✅ |
| WP-2 LRMoo plugin + view | ✅ |
| WP-3 WEMI derivation (INTERMARC; clustering = id-collision; loss) | ✅ |
| WP-4 spokes | ◐ — **7 importers in ✅**: INTERMARC-SRU, **INTERMARC-NG** (entity-relation, ADR 0019), **UNIMARC** (BnF diffusion — the MARC family complete), MARC21 (MARCXML), Dublin Core, MODS (nested), IIIF Presentation 3.0 (JSON); **4 round-trips ✅** (DC + MARC21 + MODS + IIIF ↔ floor — every floor spoke now round-trips; loss measured, id-stable & idempotent; INTERMARC is import-only by design, the rich-pivot source); shared `marcxml` core; 4-spoke convergence capstone; canonical→WEMI floor ✅; **RDF out in all three serialisations ✅** (N-Triples · Turtle · JSON-LD, LRMoo + CRM views); **Linked Art profile out ✅** (museum/Louvre target — F3→HumanMadeObject carries F2→LinguisticObject part_of F1→PropositionalObject, mapping verified vs the official examples, `docs/eval/linked-art.md`); **Linked Art now validated against the official draft-2020-12 schema** (real `networknt` validator, `$ref`-resolved — DoD #4: our roots are schema-valid, our only deviations are `additionalProperties` from embedding, *cleaner* than Getty's own Mona Lisa example which the strict schema also rejects); both LoC XSLT oracles in (MARC→DC differential, MARC→MODS convergence); **MARC21↔LRMoo at the *floor* level** |
| WP-5 loss-aware report | ✅ (cross-edge double-count fixed in remediation R3) |
| WP-8 CLI | ✅ — `palomar convert` / **`validate`** (canonical rules, policy-driven non-zero exit) / **`report`** (X→Y loss report alone) / **`inspect`** (the parsed canonical floor + minted WEMI/agent entities) / **`reconcile`** (cross-record agent reconciliation by authority id, ADR 0018) / **`apply-repairs`** (curate the inferred `:proposed` claims — the ADR 0005 repair-application engine `palomar.curate`: a pure decision function resolves each pending proposal to `:accepted`/`:rejected`/`:needs-review`; `flag`/`accept`/`reject` policies compose an ADR 0018 promotion guard) / **`conformance`** (check the WEMI projection against an institutional profile — WP-6 mechanism `palomar.conformance`; exits non-zero under the acceptance-threshold policy) / `formats` (`palomar.cli`, `:run` alias) over the conversion assembly |
| WP-6 conformance | ◐ — **mechanism + three profiles ✅** (`palomar.conformance`: institutional profiles as diagnostic check sets over the projected record, policy-gated by the acceptance threshold; `palomar conformance --profile <linked-art\|intermarc\|iiif>`). One mechanism, two directions: the **Linked Art / Louvre** and **IIIF Presentation 3.0** *target* profiles check the projection's fitness to serialise (LA: HumanMadeObject root + name + the WEMI chain + identified creator; IIIF: a label, a Canvas-bearing digital object, a dereferenceable HTTP id — a real IIIF manifest is fully conformant); the **BnF INTERMARC** *source* profile checks a bibliographic record's native `:intermarc/*` fields (001/245 essentials, 003 ARK, an authority-linked 100 heading — Transition bibliographique, 260 date, 145 Work-link hint), grounded on real BnF SRU records. Dataless slice done; **institutional *certification*** (a passing report on real BnF/Louvre samples against their private acceptance criteria — DoD #5) stays partnership-gated (§ 7). The strict official-schema LA validation remains a separate test-only eval. |
| WP-7 scale | ◐ — **streaming end-to-end + budget ✅** (`palomar.convert/convert-stream` + lazy input). Per-record conversion is stateless — Work convergence is id-collision, not a global pass (ADR 0008) — so a record stream folds a bounded loss report in constant working set (**100 000 records in a 512 MB heap, ~70 MB**, output/loss byte-identical to batch). **Input now streams too** for the MARC family: `marcxml/stream-records` pull-parses a Reader into a lazy record seq (plugin `:stream-importer` on MARC21/INTERMARC/UNIMARC), surfaced as `palomar convert … --stream --out`. End-to-end: a **97 MB / 56 000-record flat dump → 64 MB N-Triples in a 256 MB heap, ~33 MB used**, where the eager path OOMs (`docs/eval/scale.md`). Remaining (bounded by construction): SRU pages stay eager (small, stream at page granularity), non-MARC single-record formats don't stream; the live-reconciliation/store rung (roadmap §10) is post-V1. |
| WP-9 release | ✗ |

Also delivered beyond the original WPs: ADR 0018 (entity resolution at scale,
*Accepted, partially implemented*); **ADR 0019 (conversion directionality,
*Accepted, partially implemented*)** — spokes are bidirectional, the hub is a
target, and CRM→LRM is a *downcast* that succeeds only
when the F-typing survives, demonstrated by a CRM→LRMoo round-trip
(`lrmoo.crm-import`): our additive `:crm` recovers F1/F2/F3 losslessly, our pure
`:crm-only` collapses at `E73` into `:ambiguity-collapsed` (the loss the
down-projection reported is exactly what the up-projection cannot recover); the
**entity-relation spoke** that ADR 0019 reserved is built —
`palomar.plugins.intermarc-ng` reads BnF INTERMARC-NG OEMI entity-records (the
NOEMI / Transition-bibliographique format) graph→graph onto the LRMoo view
(Œuvre/Expression/Manifestation → F1/F2/F3, `740/750 $3` → R4/R3), serialises through
the existing CRM/LA/RDF exporters and round-trips back, validated on a spec-faithful
synthetic corpus (native NG data is not yet public); the D7
commit policy (`:asserted` ⇔ proof, else `:proposed`;
`:certified-only?` export); three measured evals (C2 fidelity, showcase
boundary, OpenLibrary ER) corroborating the recall ceiling on independent data;
and a **multi-spoke convergence capstone** — INTERMARC + MARC21 + Dublin Core +
MODS in one registry reaching one LRMoo pivot with one unified loss report, and
the three floor formats (DC, MARC21, MODS) content-converging on the same Work id
(the hub property); the **Linked Art profile export** (museum/Louvre, mapping
verified vs the official examples); and the **`palomar.convert` assembly** — the
institution-facing keystone wiring the 7 importers × 10 target serialisations
through one pivot in a single call, returning the output plus the ADR 0015 loss
report over every edge. It also forced spoke mapping-ids to be globally distinctive
(the compiler keys rule ids on the name portion, ADR 0009), so the spokes are
genuinely composable.

**Remediation gate before WP-4 resumes:** a self-audit
([`cleanup/audit-2026-06-03.md`](./cleanup/audit-2026-06-03.md)) found six fix-now
defects (norm drift, D7 inconsistency, loss double-count, `:coerced` over-report,
a corrupt fixture, docstring honesty). They belong to no future WP and are cleared
first; the capability gaps above stay scheduled where they are.

---

## 5. Phasing & honest timeline

At the original small-team pace, this is an **~18–24 month program**, not a
re-skin of the twelve-sprint plan. Ranges, not points:

| Phase | Work packages | Rough span |
|------|----------------|-----------|
| 0 — Design lock + de-risk | WP-0 | 1–2 mo |
| 1 — Substrate + pivot | WP-1, WP-2 (+ DC pipe-cleaner) | 3–4 mo |
| 2 — WEMI derivation + MARC21 | WP-3, WP-4 (MARC) | 4–6 mo |
| 3 — Remaining spokes + loss | WP-4 (IIIF/CRM/LRMoo), WP-5 | 3–4 mo |
| 4 — Conformance + scale | WP-6, WP-7 | 3–4 mo |
| 5 — CLI + hardening + release | WP-8, WP-9 | 2–3 mo |

Staffing changes this materially; the table assumes the pace that landed
Sprints 0–6. "Production at two flagship institutions" is a **program**, with
an external dependency (§ 7) that no amount of engineering removes.

---

## 6. Risk register

| # | Risk | Severity | Mitigation |
|---|------|----------|-----------|
| R1 | **WEMI-derivation fidelity** — cross-record Work clustering is the hardest single problem (and was explicitly out of V1 scope as "deduplication"). | High | De-risk with the WP-0 spike *before* committing; treat fidelity as a measured metric, not a binary. |
| R2 | **Minting vs idempotency (0008)** — synthesized identity must be deterministic or merges duplicate/thrash. | High | Identity = pure function of source content; property tests in WP-1. |
| R3 | **Fixpoint pressure (0004)** — WEMI inference may need iteration; V1 principle is "no fixpoint." | Medium | *Resolved:* WP-0 chose bounded passes (D8); ADR 0020 then settled on a single pass per phase — the spike showed the inference cascade unexercised. Re-add iteration only if a Work-synthesis spike forces it. |
| R4 | **Real-data dependency** — "prod-ready" is unverifiable without institutional data + a conformance oracle. | High | Secure a data/conformance partnership early (§ 7); gates WP-3/4/6. |
| R5 | **Scale** — millions of records vs global Work clustering vs streaming. | Medium | Stream where possible; isolate the stateful clustering stage; budget in WP-7. |
| R6 | **LRMoo maturity/tooling** — recent standard, fewer reference implementations than Linked Art. | Medium | Lean on CRM tooling underneath; keep the view derived so we can adjust. |
| R7 | **Scope/timeline realism** — 18–24 months, two flagship targets. | Medium | Honest phasing; DC pipe-cleaner first; ship value per spoke. |

---

## 7. External dependency (non-engineering, but blocking)

The single thing engineering cannot manufacture: **real institutional data and
a conformance oracle.** A credible MARC21↔LRMoo path, a believable loss metric,
and a passing INTERMARC/Linked Art conformance report all require BnF/Louvre
sample data and their acceptance criteria. This gates WP-3, WP-4, and WP-6.
Securing it should start in parallel with WP-0; without it, "production-ready"
is an assertion we cannot verify.

---

## 8. Definition of Done for V1

V1 is done when, on **real institutional samples**:

1. **MARC21 ↔ LRMoo** round-trips with a published loss report and a stated
   statement-coverage percentage.
2. **WEMI derivation** yields stable WEMI identities — an idempotent re-run mints
   no new entities — verified on a real corpus.
3. **IIIF** manifests import and link to pivot entities.
4. Export to **CIDOC-CRM / Linked Art** JSON-LD validates against a museum
   profile (Louvre sample).
5. **Conformance** reports pass for ≥1 BnF profile and ≥1 museum profile.
6. The **CLI** drives convert / validate / loss-report / conformance, and
   processes a realistic corpus within a stated performance budget.
7. All touched **ADRs are Accepted**; `README.md` reflects the redefined V1.

---

## 9. Open questions for WP-0 to settle

- ~~Fixpoint vs bounded passes for `infer` (ADR 0004).~~ **Settled:** bounded passes (D8), then a single pass per phase (ADR 0020).
- The exact synthesized-entity identity key (author + uniform-title? work-key
  hashing?) and its collision behaviour (ADR 0008/0012).
- Loss-model categories and the coverage metric's precise definition.
- Whether IIIF and CIDOC/Linked Art ship import as well as export in V1, or
  export-only with import deferred.
- Two-tier pivot (CRM substrate + LRMoo overlay) as a possible alternative to
  a single LRMoo hub — recorded as the credible alternative to revisit if
  LRMoo tooling proves too thin.

---

## 10. Post-V1 horizon: from converter to store (not in V1)

V1 is a **converter** — a pure, stateless, batch function (source → IR →
target). A natural but *categorically different* successor is a **store you
query and edit** — a stateful system of record. It is recorded here so the V1
seams stay open to it, not because it is in scope.

It is a ladder, not a binary; each rung adds value *and* operational burden:

| Rung | Capability | Nature |
|---|---|---|
| 0 | Converter (V1) | stateless, ephemeral |
| 1 | Persisted output | trivial |
| 2 | Queryable (SPARQL / traversal / IIIF endpoints) | adopt a triple/graph store |
| 3 | Incremental (ingest deltas, update without full re-batch) | hard — incremental inference + identity stability |
| 4 | Editable / curated (humans accept/merge/split; decisions persist) | hard — becomes a web app with users |
| 5 | Authoritative / cross-institution convergence hub | hardest — identity lifecycle, governance |

**What it serves:** a living catalogue (continuous WEMI derivation, à la BnF
Transition bibliographique); cumulative cross-source reconciliation; curation of
the WEMI-derivation tail at scale; serving the graph (a data.bnf.fr-like platform);
audit over time. The most original niche is **rung 5 — the cross-institution
convergence layer**: each institution has its own store, but nobody owns the
convergence *between* them.

**What it implies:** statefulness (concurrency, transactions, migrations);
identity that is *authoritative*, not merely *reproducible* (merge / split /
deprecate with stable redirects — an identity-lifecycle subsystem); incremental
inference; a curation application (which V1 explicitly excludes). This is where
**A′ / a real graph-store backend (see ADR 0017)** finally earns its keep — and
the V1 converter becomes the store's **ingestion stage**, not dead weight.

**Why it does not change V1:** the substrate already plants the seeds — the dual
status model (ADR 0005) for curation, provenance for audit, entities +
references for the graph, diagnostics / loss for quality. Going there is a
*mission* decision, not a technical pivot. Until it is taken we pay nothing — we
only keep three seams clean: **identity** (deterministic → versionable toward a
lifecycle), **status / curation** (ADR 0005), and **provenance**.

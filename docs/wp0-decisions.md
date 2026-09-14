# WP-0 — Decisions to settle before any code

- Status: **Decided by recommendation (2026-05-31)** — all decisions below are
  accepted per their recommendations, by maintainer delegation; the maintainer
  keeps a veto on any. Residual tuning (key composition, exact vocab lists) is
  noted per decision and resolved during the WP-0 spike/build.
- Date: 2026-05-31
- Feeds: the WP-0 ADRs (Pivot, Loss-model, WEMI-derivation, the ADR 0011 minting
  amendment, the ADR 0008/0004 reconciliations) defined in
  [`roadmap-v1.md`](./roadmap-v1.md).

Already locked (context, not open): IR **strategy C** (assertions as ground
truth + derived typed view in a plugin) and **LRMoo** as the pivot vocabulary.
C stays valid as long as ADR 0003 (agnostic core) stands.

The decisions below are grouped: **A. Pivot shape · B. Identity & minting ·
C. WEMI-derivation control · D. Loss & institutional scope.** Each is load-bearing
for at least one WP-0 ADR, so each must be settled before that ADR is written.

> **Design rule (added 2026-05-31, per maintainer).** The test for deferring
> anything to a later version is *not* "is it valuable?" but "is its later
> addition *purely additive*?" Build the architectural **seam** in V1 even when
> we populate it minimally; defer only features that slot behind an existing
> seam as a new plugin/provider. Never defer anything whose later arrival forces
> a deep refactor. This rule reshapes D5 and the deferred list below.

## Status at a glance (2026-05-31)

| # | Decision | Status |
|---|----------|--------|
| D1 | Single LRMoo hub | ✅ accepted |
| D2 | V1 profile = WEMI core + museum CRM core | ✅ accepted (list grows by justification) |
| D3 | Typed view = in-IR minted assertions + traversal API | ✅ accepted |
| D4 | Mint in `infer`; `repair` proposes | ✅ accepted |
| D5 | Identity = resolver seam + hash + dump-based authority (V1) | ✅ accepted (key/snapshot tuned in spike) |
| D6 | Batch-local clustering + authority IRIs | ✅ accepted |
| D7 | Confidence-gated hybrid WEMI derivation | ✅ accepted |
| D8 | Bounded fixed passes | ✅ accepted (revisit only if spike forces) |
| D9 | Loss = source-native unit, both edges, categorised | ✅ accepted |
| D10 | Universal / dialect-agnostic MARC (MARC21 + UNIMARC) | ✅ accepted (first target with partner) |
| D11 | Reconcile Tier 1+2, precision-first wide net | ✅ accepted |

---

## A. Pivot shape

### D1 — Single LRMoo hub vs two-tier (CRM base + LRMoo overlay)
Is the pivot one LRMoo model, or a CIDOC-CRM base with LRMoo engaged only when
bibliographic WEMI is in play?

- **Single LRMoo hub.**
  - *Pro:* one model; WEMI native; CRM still reachable downward (F-classes are
    CRM subclasses); maximises loss-visibility (everything commensurable in one
    model).
  - *Con:* museum-only objects pass through a bibliographic lens they don't
    need; ties the hub to LRMoo's (younger) maturity.
- **Two-tier (CRM + LRMoo overlay).**
  - *Pro:* clean separation; museum objects stay pure CRM; isolates LRMoo
    maturity risk.
  - *Con:* "when do we engage the overlay?" boundary logic; splits the hub —
    displaces the WEMI-derivation complexity rather than removing it.

**Recommendation: single LRMoo hub for V1.** LRMoo *is* CRM-plus-WEMI, so one
hub subsumes both worlds and keeps loss visible in a single model. Keep two-tier
documented as the fallback if LRMoo tooling proves too thin (risk R6).
**Decision: accepted — single LRMoo hub (by recommendation, 2026-05-31); two-tier kept as the documented fallback.**

### D2 — LRMoo V1 profile (how much of LRMoo/CRM ships)
LRMoo + CRM are large. "All of it" is unbounded scope.

- **Minimal WEMI core** (F1 Work, F2 Expression, F3 Manifestation, F5 Item +
  the R-properties linking them + only the CRM classes the V1 spokes need:
  actors, appellations, time-spans, the relevant creation events).
  - *Pro:* bounded, deliverable; matches ADR 0003 growth discipline; covers
    MARC + basic museum.
  - *Con:* some records won't fully map → loss (but that loss is *reported*).
- **Broad LRMoo + large CRM swath up front.**
  - *Pro:* fewer "can't express" gaps.
  - *Con:* unbounded scope; timeline killer; much untested vocabulary.

**Recommendation: minimal WEMI core + only the CRM classes a shipped spoke
demands,** growing by ADR 0003's "justify every addition" rule. Gaps become
honest loss markers, not silent omissions. **Decision: accepted — V1 profile = the WEMI core *plus* the CRM object core that the shipped IIIF/museum spokes demand (by recommendation, 2026-05-31); the precise class/predicate list grows by justification.**

### D3 — How the typed view is realised (the concrete shape of strategy C)
"Derived typed view" must be made concrete: minted typed assertions *inside*
the IR, or a separate projected structure *outside* it?

- **In-IR minted typed assertions + a typed traversal API.** LRMoo entities are
  subjects bearing `:lrmoo/*` / `:crm/*` assertions; "the view" is a typed
  reading lens over them.
  - *Pro:* one representation; honours ADR 0001; provenance/confidence/
    diagnostics ride on typed entities unchanged; "derived" = projection rules
    emit assertions; re-projection is idempotent.
  - *Con:* the IR grows; need discipline to mark derived vs source (status/
    provenance already do this).
- **Out-of-IR projected graph structure (built on demand).**
  - *Pro:* leaner IR; a "real" graph object for traversal/export.
  - *Con:* two representations to keep coherent; provenance/diagnostics must be
    mirrored; reintroduces the drift risk C exists to avoid.

**Recommendation: in-IR minted typed assertions + a typed traversal API in the
LRMoo plugin.** It is the truest form of C — ground truth stays assertions, the
view is *derived assertions* plus a reading lens — and the existing diagnostic
machinery carries over for free. **Decision: accepted — in-IR minted typed assertions + a typed traversal API (by recommendation, 2026-05-31).**

---

## B. Identity & minting

### D4 — Minting semantics (the ADR 0011 amendment): what, and in which phases
Lifting the ingest-only restriction precisely.

- **Mint entities in `infer` only; `repair` may only *propose*.** Minted claims
  carry `:pass :infer` provenance + confidence; status defaults to `:proposed`
  (engine phase policy — precision-first), with D7 high-confidence promotion to
  `:asserted` as a future gating step; fragment rules unchanged. (No `:inferred`
  status — ADR 0005.)
  - *Pro:* tight blast radius; clear provenance; aligns with the status model
    (ADR 0005).
  - *Con:* a repair that needs a new entity must route through the proposal path
    (which is what D7 wants anyway).
- **Mint freely across normalize/infer/repair.**
  - *Pro:* maximal flexibility.
  - *Con:* entities can appear anywhere; larger idempotency surface; harder to
    reason about.

**Recommendation: mint entities in `infer` (machine truth); `repair` only
proposes** (`:proposed`, per ADR 0005). Entity-minting is the new capability;
fragment minting keeps today's rules. **Decision: accepted — mint in `infer`; `repair` only proposes (by recommendation, 2026-05-31).**

### D5 — Synthesized-entity identity scheme (the crux; ADR 0008/0012)
A minted Work needs an id that is deterministic from content (idempotency) and
equal across records describing the same Work (clustering).

- **Deterministic work-key hash.** Canonicalise a key (normalised creator +
  uniform/preferred title [+ original language]) → UUIDv5 over the canonical
  string.
  - *Pro:* deterministic, stateless, idempotent; clustering falls out (same key
    → same id); extends ADR 0012 cleanly.
  - *Con:* key choice is semantically fraught — too loose over-merges distinct
    works, too tight splits one work; sensitive to normalisation quality.
- **Match-or-mint against an external authority** (VIAF / ISNI / IdRef for
  agents; existing work ids).
  - *Pro:* higher-quality, interoperable identity; better clustering.
  - *Con:* network dependency, coverage gaps, non-determinism without caching;
    heavier; partly V2.
- **Local blank-node identity, reconcile later.**
  - *Pro:* simplest minting; no premature identity commitment.
  - *Con:* no clustering → every record gets its own Work → defeats WEMI derivation.

**Recommendation (revised per the design rule above): build an *identity-
resolver seam* in V1 — `resolve-identity(key-material) -> stable-id [+ authority
IRI]` — and ship two resolvers behind it:**
1. a deterministic **work-key hash** (always-available fallback), and
2. an **authority-anchored** resolver that reconciles creators/works against an
   *authority dataset snapshot* (e.g. the open data.bnf.fr export, or an
   IdRef / VIAF subset) — deterministic and offline because it reads a dump,
   not a live API.

The hash is the fallback when the authority misses. This keeps authority-grade
identity **in V1** — it materially improves clustering and emits real IRIs for
the RDF / Linked Art spokes — while staying deterministic and reproducible.
Only *live online* authority lookup is deferred, and it is purely additive
behind the same seam. The per-WEMI key material (Work / Expression /
Manifestation / Item) is itself a sub-decision. **Decision: accepted — resolver seam + work-key hash + dump-based authority resolver, all in V1 (by recommendation, 2026-05-31); the per-WEMI key composition and the first pinned authority snapshot are tuned in the WP-0 spike.**

### D6 — Clustering scope: batch-local vs external authority vs persistent store
Across what set do we cluster Works?

- **Batch-local only** (within one run; no persistence).
  - *Pro:* honours "Palomar is not a storage system"; deterministic per run.
  - *Con:* can't cluster against unloaded records; cross-run consistency relies
    entirely on key stability (D5).
- **External authority lookup** (online reconciliation, no local store).
  - *Pro:* better cross-institution identity.
  - *Con:* network / coverage / non-determinism; V2-ish.
- **Persistent identity store** maintained by Palomar.
  - *Pro:* true incremental cross-run clustering.
  - *Con:* violates the no-storage principle; large architectural addition.

**Recommendation: batch-local clustering, made cross-run-stable purely by the
deterministic key (D5).** Two runs that see the same Work mint the *same id*
because the id is a function of content, not of a shared store — so we keep the
no-storage principle and still get cross-run consistency. Persistent store is
out of V1; authority lookup is V2. State the limit honestly: clustering quality
is bounded by what's in a batch + key stability. **Decision: accepted — batch-local clustering, made cross-run/cross-institution stable by the deterministic key *and* the D5 authority IRIs (by recommendation, 2026-05-31); persistent store stays out of V1.**

> D5 and D6 are linked: the deterministic key (D5) is exactly what makes
> batch-local clustering (D6) sufficient without storage.

### D11 — Named-entity reconciliation scope (added 2026-05-31, per maintainer)
The D4/D5 machinery is, by construction, an **entity-reconciliation engine that
runs during conversion**: lifting a flat record into the typed pivot *is*
reconciling its named entities. Two dials must be set.

**Breadth — which entity types.** The resolver seam is generic, so every type is
the same mechanism; but each needs its own key + authority + tuning, and types
differ wildly in tractability.
- Tier 1 (V1 core): **agents** (persons/orgs) and **works** — high value, strong
  authorities (VIAF / ISNI / data.bnf.fr), tractable.
- Tier 2 (V1 if budget): **places** — Geonames is solid.
- Tier 3 (careful/partial, or defer): **subjects/concepts** — valuable but messy
  (RAMEAU / LCSH / local thesauri, polysemy, granularity mismatch).
- Tier 4 (defer): **events** — weak authorities.
- *Maximising breadth* buys more in one pass, but the hard types have low
  precision and would erode trust + burn timeline — and breadth is **additive
  behind the seam**, so "more later" is cheap (the seams-not-deferrals rule).

**Aggressiveness — how much to commit.** Precision vs recall.
- "Merge as much as possible" raises recall, **but a false merge costs far more
  than a miss**: it fabricates a false identity (fusing Dumas *père* et *fils*,
  or two distinct "Jean Martin"), is hard to detect/undo, and corrupts the very
  integrity institutions trust us for. Authority data is precision-first.

**Recommendation.**
- Breadth: ship **Tier 1 (agents + works)**, add **Tier 2 (places)** if budget
  allows; subjects/events are additive on the same rail. Prioritise by
  value × tractability — do *not* maximise.
- Aggressiveness: **cast a wide net, commit conservatively.** *Attempt*
  reconciliation broadly, but only **assert** a merge when confident; route the
  uncertain tail to **proposed / diagnostics** (the D7 hybrid, generalised to
  all entity types). Maximise *candidates*, never *asserted* merges — recall is
  preserved as proposals without polluting the asserted graph.

**Decision: accepted — Tier 1 (agents + works) + Tier 2 (places) if budget; precision-first "wide net, conservative commit" (by recommendation, 2026-05-31); subjects/events additive later.**

---

## C. WEMI-derivation control

### D7 — Automatic vs human-in-the-loop WEMI derivation
When Palomar synthesizes WEMI, is it machine truth, or proposals a cataloguer
accepts/rejects (ADR 0005)?

- **Automatic in `infer`** (minted = `:asserted` machine truth, `:pass :infer`
  provenance, exported directly).
  - *Pro:* scales to millions; fits batch conversion; confidence + loss convey
    uncertainty.
  - *Con:* a wrong WEMI derivation ships unattended; institutions may distrust it.
- **Human-in-the-loop** (WEMI derivation emits `:proposed` repairs; `apply-repairs`
  surfaces them).
  - *Pro:* control, auditability; matches cataloguer trust.
  - *Con:* doesn't scale to millions; turns conversion into a review project.
- **Confidence-gated hybrid** (high-confidence auto in `infer`; low-confidence /
  ambiguous as `:proposed`).
  - *Pro:* scales where safe, asks humans only on hard cases.
  - *Con:* needs a threshold policy + tuning; two paths.

**Recommendation: confidence-gated hybrid.** It is the only option that both
scales *and* earns institutional trust; it reuses the existing repair workflow
for the low-confidence tail. The threshold is a documented, tunable policy.
**Decision: confidence-gated hybrid — accepted 2026-05-31.**

### D8 — Fixpoint vs bounded passes for `infer` (ADR 0004)
WEMI inference can cascade (mint a Work, then link sibling Expressions).

- **Keep fixed/bounded passes,** designing WEMI derivation to converge in a small
  declared number (e.g. synthesize, then link).
  - *Pro:* preserves ADR 0004 + "explicit over implicit"; predictable cost; no
    termination risk.
  - *Con:* constrains rule authors to converge in budget.
- **Scoped fixpoint for `infer`,** with a hard iteration cap + non-convergence
  diagnostic.
  - *Pro:* expressive; natural for graph inference.
  - *Con:* reopens ADR 0004; less predictable; oscillation risk (capped).

**Recommendation: keep bounded fixed passes; design WEMI derivation to converge in a
small declared number,** and escalate to scoped-fixpoint-with-cap *only if the
WP-0 spike proves fixed passes can't express WEMI linking.* Decide empirically
from the spike, not a priori. **Decision: accepted — bounded fixed passes (by recommendation, 2026-05-31); escalate to scoped-fixpoint-with-cap only if proven necessary. Spike (2026-06-01) — corrected: explicit `145 $3` Work links are sparse in bibliographic records (essentially only the Madame Bovary showcase; the first 7% figure wrongly mixed in authority records — withdrawn); the inference path was not exercised, so bounded passes remain the default but D8 is *unconfirmed* pending a Work-synthesis spike.**

**Closed (2026-06-06, ADR 0020):** D8 is resolved in the *no-iteration* direction. With the inference cascade unexercised (Work links sparse — see the spike correction above), the runtime dropped `:cycles` (bounded multi-cycle execution) altogether and now runs a **single pass per phase**. Bounded iteration / scoped fixpoint is re-admissible only as an explicit phase mode, should a future Work-synthesis spike prove it necessary.

---

## D. Loss & institutional scope

### D9 — Loss model: unit, edges, metric
"Loss-aware" needs a precise definition.

- **Unit of loss** — *source-native field* vs *pivot assertion*.
  *Recommendation: source-native field* — the institution reasons in its own
  format's terms ("which of my subfields survived?"). Fragments/provenance
  already let us map loss back to source fields.
- **Edges measured** — import (source→pivot), export (pivot→target), or both.
  *Recommendation: both, plus a round-trip report for a format pair.*
- **Categories** — dropped · coerced (lossy transform) · under-specified (target
  coarser) · ambiguity-collapsed (chose 1 of N).
- **Metric** — single coverage % vs per-category breakdown.
  *Recommendation: per-category breakdown, not one number* — a single % hides
  which losses matter.

**Recommendation (summary): unit = source-native field; measure both edges +
round-trip; categorise; report a breakdown.** Built on diagnostics +
`:canon/loss-marker`. **Decision: accepted — unit = source-native field; both edges + round-trip; categorised breakdown (by recommendation, 2026-05-31).**

### D10 — MARC dialect for V1 (MARC21 vs UNIMARC vs INTERMARC)
A catch worth surfacing: "MARC21 réel" was the phrase, but **BnF does not
primarily use MARC21** — historically UNIMARC, internally INTERMARC. If BnF is a
flagship target, MARC21-only may miss the actual data. Coupled to the data-
partner decision (roadmap § 7).

- **MARC21 first** (LoC / anglo-american).
  - *Pro:* best-documented, most tooling, broadest applicability.
  - *Con:* not BnF's native dialect.
- **UNIMARC first** (IFLA; closer to BnF's public data).
  - *Pro:* aligns with the French ecosystem; LRM-friendly heritage.
  - *Con:* less anglophone tooling.
- **INTERMARC** (BnF internal).
  - *Pro:* exactly BnF's data.
  - *Con:* niche, limited public spec.

**Recommendation: build the MARC importer dialect-parametrised** — a shared MARC
core (record/field/subfield model) + pluggable dialect *profiles* as data
(MARC21 / UNIMARC / INTERMARC mappings). Ship **MARC21 + UNIMARC** in V1 if BnF
is the target; add INTERMARC as a profile when specs are available. This bets
the spoke on no single dialect and matches plugins-as-data (a dialect is mapping
data). **Decision: accepted — universal / dialect-agnostic: shared MARC core + dialect profiles; MARC21 + UNIMARC in V1, INTERMARC profile when specs allow (by recommendation, 2026-05-31); exact first target confirmed with the data partner.**

---

## Can be deferred (consciously — not blocking the start)

- **RDF / JSON-LD serialization library** (hand-rolled vs Jena interop vs a
  Clojure RDF lib) — decide at WP-4; watch the ADR 0006 sandbox constraint.
- **Live online authority lookup** (querying VIAF / ISNI / IdRef APIs at
  runtime) — V2, purely additive behind the D5 resolver seam. *Dump-based*
  reconciliation is **promoted into V1** (see D5), so this defers only the live
  network path, not authority-grade identity itself.
- **Museum / IIIF import direction** — export-only is acceptable for V1; decide
  per spoke at WP-4 (open question already in the roadmap).
- **Plugin sandboxing** (ADR 0010 trust-on-require) — V2 unless institutional IT
  mandates it sooner.
- **Persistent identity store** — V2, additive *because* identity is content-
  deterministic (D5): a store becomes a lookup cache/index behind the resolver
  seam, not a change to identity semantics. D6 keeps V1 storage-free.

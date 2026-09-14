# 0013 — LRMoo as the rich pivot vocabulary, via a derived typed view

- Status: Accepted
- Date: 2026-05-31
- Extends: ADR 0003 (adds a second, richer documentary-vocabulary rung)
- Builds on: ADR 0001 (assertion IR, preserved), ADR 0007 (plugins as data)
- Detailed elsewhere: runtime minting (ADR 0011 amendment, forthcoming);
  WEMI derivation, identity, reconciliation (WEMI-derivation ADR, forthcoming); loss
  model (Loss-model ADR, forthcoming)
- Decision record: [`../wp0-decisions.md`](../wp0-decisions.md) (D1, D2, D3);
  [`../roadmap-v1.md`](../roadmap-v1.md)

## Context

The redefined V1 (`docs/roadmap-v1.md`) targets a production-grade,
**loss-aware** conversion hub spanning IIIF, CIDOC-CRM / Linked Art, the full
MARC family (MARC21 / UNIMARC / INTERMARC), and Dublin Core. A loss-aware
hub-and-spoke needs a **rich pivot vocabulary**: an interlingua expressive
enough that loss occurs only at the spoke edges and is *reported*, never
silently swallowed in the hub.

The core ships only a structural vocabulary; documentary meaning lives in a
plugin (ADR 0003). The existing documentary vocabulary —
`palomar.plugins.canonical`, about ten flat `:canon/*` predicates — is
deliberately a lowest-common-denominator floor. It cannot, alone, carry the
bibliographic WEMI structure (Work / Expression / Manifestation / Item) or the
event-centric museum model the new spokes require.

So two questions must be answered together:

1. **Which** rich vocabulary becomes the pivot interlingua?
2. **How** does it relate to the assertion IR (ADR 0001) and the agnostic core
   (ADR 0003) — is it the IR, or something layered on it?

This was anticipated. The README roadmap and ADR 0011 record that the IR,
vocabulary layering, and mapping schema were shaped to accommodate
event-centric and nested-resource models *without rework* — the qualified-value
(fragment) design exists for exactly this. This ADR cashes that reserved option.

## Decision

### 1. The rich pivot vocabulary is LRMoo

LRMoo — the object-oriented expression of IFLA LRM, a specialization of
CIDOC-CRM (v1.0, 2024) — is the pivot interlingua.

The governing principle: **the hub must be the most expressive model that
subsumes the spokes**, so loss happens only at the edges and stays visible.
LRMoo is CRM *plus* a native WEMI abstraction, so a single hub subsumes both the
bibliographic world (MARC) and the event-centric / museum world (CIDOC, Linked
Art, IIIF subjects). Because LRMoo's F-classes (F1 Work, F2 Expression,
F3 Manifestation, F5 Item) are subclasses of CRM classes, **down-projection to
plain CRM / Linked Art is free** (walk up the hierarchy); the reverse — deriving
WEMI from plain CRM — is lossy and needs inference, which is why the richer
model is the hub.

(See *Alternatives* for why not CRM-alone, Linked Art, BIBFRAME, or generic RDF.)

### 2. Realisation — strategy C: a derived typed view over the assertion IR, in a plugin

The assertion IR (ADR 0001) **remains the ground truth**. LRMoo is **not** the
IR.

- The LRMoo view is **derived**: projection rules emit LRMoo-typed assertions —
  subjects bearing `:lrmoo/*` / `:crm/*` predicates — and a **typed traversal
  API** reads them. The typed entities live *in* the assertion substrate
  (decision D3: in-IR minted typed assertions), so provenance, confidence,
  status, and diagnostics ride on them unchanged, and re-projection is
  idempotent.
- It lives in a **plugin**, `palomar.plugins.lrmoo` — the rich sibling of
  `palomar.plugins.canonical` — not in the core.

This is "plugins-as-data (ADR 0007) applied to the ontology itself": the
ontological commitment is a plugin (projection rules + mapping + view +
exporter), interpreted by an agnostic core.

### The load-bearing distinction

Stated explicitly so future readers do not drift back to a typed-graph IR:

> LRMoo is the pivot **vocabulary** — the interlingua in which spokes are made
> commensurable, and which the rich exports serialise. The pivot **IR** — what
> every record physically passes through, the ground truth carrying
> provenance / confidence / diagnostics — stays the **assertion model**. LRMoo
> is a *derived view*, not the substrate. "Pivot language" means the vocabulary,
> never the carrier.

### Condition: this depends on ADR 0003 holding

Strategy C is coherent **only while the core stays vocabulary-agnostic
(ADR 0003)**. Keeping the ontology in a plugin, with a derived view, is what
lets us have a rich pivot without an ontology in the core. If ADR 0003 were
reopened to let the core own an ontology, the IR-strategy choice (C versus a
typed-graph core IR) reopens with it. As long as 0003 stands, C stands. Touching
the agnosticism of the core is the *only* thing that reopens this decision.

### Amendment to ADR 0003: a two-rung vocabulary ladder

ADR 0003 established structural vocabulary at the core and **one** documentary
vocabulary in a plugin. This ADR adds a **second, richer rung**:

- **Canonical** (`:canon/*`) — the thin, domain-neutral **floor**: cheap,
  universal commensurability for any source, including non-bibliographic ones
  (a CSV row, a museum object, an IIIF image).
- **LRMoo** (`:lrmoo/*` / `:crm/*`) — the rich **ceiling**: the bibliographic +
  museum interlingua.

Neither is privileged in the core; mapping toward either is *available, not
imposed* (ADR 0003's principle holds). The two rungs degrade gracefully: a
source mapped only to canonical yields a **valid but under-specified** LRMoo
view, and the gap is **measured loss**, not a silent omission. ADR 0003's
"format plugins are rated on their coverage of the canonical vocabulary"
generalises to coverage rated at *both* rungs.

## Alternatives considered

- **Typed-graph IR (strategy B): make the core IR an LRMoo / CRM-typed graph.**
  Rejected. It puts the ontology *in the core*, violating ADR 0003 and breaking
  the thin core/plugin boundary (ADR 0007); it forces the assertion-based rules,
  runtime, and diagnostics (Sprints 0–6) to be re-expressed; and it reopens
  *two* settled rejections at once — ADR 0001's flat-IR choice and ADR 0003's
  explicit rejection of documentary vocabulary in the core. The only thing that
  would justify B is reopening ADR 0003 — which we are not doing.
- **Extend the assertion IR with LRMoo vocabulary but no typed view
  (strategy A).** Rejected. Rule ergonomics over deep WEMI / CRM chains stay
  awkward — ADR 0001 itself conceded deep traversal "is not the design target."
  The derived view buys exactly that traversal ergonomics (for WEMI derivation and
  exporters) at low, additive cost.
- **CIDOC-CRM alone as the pivot.** Rejected. It under-specifies the
  bibliographic layer; WEMI derivation (MARC → WEMI), the hardest and
  highest-value transformation, would have to re-derive WEMI by hand — i.e.
  reinvent LRMoo informally. Better to adopt the standard that already
  harmonised them.
- **Linked Art or BIBFRAME as the pivot.** Rejected as the hub. Linked Art is a
  *profile* of CRM (no first-class WEMI → lossy on the library side); BIBFRAME
  is a competing model, not a CRM-grounded superset (→ cuts off the museum
  world). Both are excellent **spokes**, poor hubs. The hub is the maximal
  superset; profiles and competitors are spokes.
- **Generic RDF / Schema.org as the pivot.** Rejected. Too shallow to even
  express the distinctions whose loss we must report → loss becomes invisible,
  the opposite of the goal.
- **Two-tier pivot (CRM base + LRMoo overlay), engaged only for WEMI.**
  Considered (decision D1). Kept as a **documented fallback** if LRMoo tooling
  proves too thin (roadmap risk R6). Not chosen: a single LRMoo hub subsumes
  both worlds and keeps loss visible in one model, whereas two tiers displace
  the WEMI-derivation complexity rather than removing it, and split the hub.

## Consequences

- **Preserved:** the assertion IR, the agnostic core, provenance / confidence /
  diagnostics, and the rule + mapping machinery (ADR 0001 / 0003 / 0007 / 0009
  intact). No Sprint 0–6 rework.
- **New:** a plugin `palomar.plugins.lrmoo` carrying (a) the LRMoo vocabulary
  subset (D2: the WEMI core *plus* the CRM object core the shipped IIIF / museum
  spokes demand, grown by ADR 0003's justification discipline), (b) the
  projection rules that derive the typed view, (c) the typed traversal API, and
  (d) the LRMoo / RDF (JSON-LD, Turtle) exporter.
- **The view cannot drift:** it is derived from assertions; re-projection is
  idempotent and side-effect-free.
- **Canonical is re-situated**, not obsolete: the thin floor of a two-rung
  ladder. Its loss-marker and coverage machinery remain the baseline.
- **Runtime minting becomes required:** deriving Works / Expressions that exist
  in no source means lifting the ingest-only minting restriction — handled in
  the ADR 0011 amendment (forthcoming), with reproducible identity (D5) and
  merge idempotency (ADR 0008).
- **Down-projection enables the museum spokes:** CRM / Linked Art export is a
  walk up the F-class hierarchy, not a separate model.
- **Risk:** LRMoo maturity / tooling (roadmap R6) — mitigated by the view being
  derived (cheap to adjust) and the two-tier fallback (D1).

## Implementation status (2026-06-02)

The decision stands; only a slice is built. Honest current state, so the target is
not read as shipped:

- **Built:** the `palomar.plugins.lrmoo` vocabulary subset (the WEMI core —
  F1/F2/F3/F5, R3/R4/R7, R33); the derived typed traversal API (`lrmoo.view`); an
  **N-Triples** exporter (`lrmoo.export`) that emits a Manifestation's real
  data.bnf ARK (ADR 0017); and the **generic canonical→WEMI projection**
  (`lrmoo.project`) — the floor projection any spoke shares, proven cross-format
  (a Dublin Core record in JSON *and* XML converges on the same Work,
  `universal-pivot-integration-test`). The two-rung ladder degrades as decided —
  off the showcase a record yields a bare Manifestation view (measured,
  `docs/eval/wemi-fidelity.md`).
- **Not built** (named in Decision/Consequences as the *target*, not as done):
  - **Turtle / JSON-LD** — only N-Triples ships (the parenthetical "(JSON-LD,
    Turtle)" in Consequences is aspirational; they are thin follow-ons over the
    same `triples` seq);
  - **CRM down-projection** ships in both forms in `lrmoo.crm`: *additive / lossless*
    (LRMoo + its CRM super-type/-property, verified vs the OWL) and *pure-CRM
    replacement* (E/P only — lossy: F2/F3 collapse to E73, relations generalise — with
    that loss reported at the export edge). **Linked Art** and Turtle/JSON-LD are not
    built;
  - the broader **CRM object core** beyond WEMI — the shipped subset is WEMI-only;
  - the projection minted from `:canon/*` (`lrmoo.project`) is generic, but the
    *enriched* projection that exploits native authority links remains
    INTERMARC-specific (`intermarc.wemi`); other spokes get the floor view.

## What this ADR does not decide

- Runtime minting semantics — **ADR 0011 amendment** (forthcoming; D4).
- Synthesized-entity identity, clustering, and reconciliation scope —
  **WEMI-derivation ADR** (forthcoming; D5 / D6 / D11), with the identity scheme
  extending ADR 0012.
- WEMI-derivation control (automatic vs human-in-the-loop) — **WEMI-derivation
  ADR** (forthcoming; D7).
- The loss model (unit, edges, metric) — **Loss-model ADR** (forthcoming; D9).
- The exact LRMoo class / predicate subset and the exact R-properties against
  the LRMoo v1.0 specification — fixed during WP-2, grown by justification (D2).
- Fixed passes vs scoped fixpoint for the `infer` phase — note on ADR 0004
  (D8), decided empirically from the WP-0 spike.

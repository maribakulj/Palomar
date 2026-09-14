# 0019 — Conversion directionality: spokes are bidirectional, the hub is a target, and CRM→LRM is a downcast

- Status: Accepted (partially implemented — the directionality rule, the CRM→LRM
  downcast round-trip, and the INTERMARC-NG entity-relation spoke are landed;
  validated on a spec-faithful synthetic corpus pending native NG data)
- Date: 2026-06-04
- Builds on: ADR 0013 (LRMoo rich pivot — the hub), ADR 0015 (loss model — the
  receipt of irreversibility), ADR 0016 (WEMI derivation — the inference path), ADR 0017
  (entities on records — the graph IR), ADR 0001 (assertions/entities/references —
  the IR *is* a graph).
- Demonstrated, not asserted: `palomar.plugins.lrmoo.crm-import` +
  `crm-import-test` round-trip our own CRM exports back to the LRMoo view.

## Context

The plan has formats with import-only (INTERMARC), export-only (CIDOC-CRM, Linked
Art, LRMoo-RDF), and full round-trip (Dublin Core, MARC21, MODS, IIIF). That looks
like an inconsistency. It is not — it follows the **topology of the hub-and-spoke**,
and this ADR makes the rule explicit so the asymmetry is a tracked decision rather
than an accident of what got built.

Two questions forced it: *why is CIDOC-CRM export-only* (the user, on the museum
side), and *how would we convert INTERMARC-NG → CRM, since both are entity-relation
models* (the user, on the BnF Transition-bibliographique side).

## Decision

### 1. Two kinds of format, two roles

- **Documentary spokes** — the formats institutions hold records *in* (DC, MARC21,
  MODS, IIIF, INTERMARC, UNIMARC). You convert *between* them; they are **import +
  export + round-trip**, and the round-trip measures floor fidelity (≈ lossless).
- **Hub serialisations** — the rich pivot expressed as output (LRMoo-RDF, CIDOC-CRM,
  Linked Art). They are the **target**: you project *down* to them. They are
  **export-only by default**.

The rule: **a documentary spoke is bidirectional; the hub is a target.**

### 2. Import difficulty = "does the source already carry the entity distinctions?"

Whether a source is *easy* or *hard* to bring into the WEMI hub is not "import vs
export" — it is whether the source carries the WEMI/entity structure:

| source | carries WEMI? | path in |
|--------|---------------|---------|
| flat MARC21 / DC | no | infer (weak) → string floor (ADR 0003) |
| classic INTERMARC | partly (the `145 $3` link) | the `derive-wemi` rung (ADR 0016) |
| **INTERMARC-NG** (entity-relation, LRM-native) | **yes, explicitly** | **map graph→graph** to LRMoo entities, ~no inference |
| plain CIDOC-CRM (E73/E22, LRM never modelled) | no (collapsed) | hard, inferential, ambiguous |

So there is a **third spoke class — the entity-relation spoke** (INTERMARC-NG;
later BIBFRAME, native LRM/RDF). The IR already supports it: ground truth is
assertions + **entities** + **references** (ADR 0001/0017), i.e. *already a graph*.
An E-R source mints LRMoo entities + relation assertions directly and **bypasses the
string floor** — exactly the seam the roadmap reserved ("the IR was shaped to
accommodate event-centric / nested models without rework").

### 3. CRM ↔ LRM is upcast ↔ downcast (LRM is a *specialisation* of CRM)

LRMoo **extends** CIDOC-CRM: its F-classes are sub-classes of CRM E-classes
(`F1 ⊂ E89`, `F2 ⊂ E73`, `F3 ⊂ E73`, `F5 ⊂ E24`), its R-properties sub-properties of
CRM P-properties (verified vs `LRMoo_v1.0.owl`, `lrmoo.crm`). Therefore:

- **LRM → CRM = upcast (generalisation).** Always possible; loses specificity —
  `F2` and `F3` both become `E73`. Our `:crm-only` export *is* this upcast, and it
  **reports the loss** (`crm/crm-only-losses`: the `E73` collapse is
  `:under-specified`, each relation→generic-P is `:coerced`).
- **CRM → LRM = downcast (specialisation).** Succeeds **iff the specific type is
  still present**:
  - CRM that keeps the LRMoo F-classes (our additive `:crm`, an INTERMARC-NG→CRM, a
    BnF LRM graph) → recovers F1/F2/F3 **losslessly** — it is already LRM;
  - CRM flattened to E-classes only (our `:crm-only`, a museum CRM with no LRM) →
    a node typed only `E73` **cannot** be downcast (Expression or Manifestation?);
    it is reported as `:ambiguity-collapsed` (ADR 0015).

The deep tie: **the loss report of the down-projection is the specification of what
the up-projection cannot recover.** You never recover a distinction the source did
not carry — the same principle as the floor spokes.

### 4. What is principled vs a priority call

- **CRM / RDF export-only**: *principled* (importing arbitrary CRM is the hard,
  inferential downcast). Importing *F-typed* CRM/RDF, by contrast, is near-trivial
  (it is already the hub) — but it is not a documentary source format, so it is not a
  `convert` spoke; `crm-import` exists to prove the mechanism, not as a spoke.
- **Linked Art import**: a *priority* call, not impossible — LA keeps the F2/F3
  distinction, so LA→hub is feasible (the plan's "maybe in").
- **INTERMARC export**: a *priority* call — a floor-level exporter is buildable (like
  MARC21's) but low value (one does not generate the BnF's own INTERMARC).

## Consequences

- **Easier / now explicit.** The directionality of every format is a stated
  consequence of its role, not a gap. Adding an exporter to a spoke, or an importer
  for an E-R source, is a recognised move; adding a *generic* CRM importer is known
  to be the hard, lossy one.
- **Demonstrated.** `crm-import/recover` round-trips our `:crm` to F1/F2/F3
  losslessly and collapses our `:crm-only` at `E73` into `:ambiguity-collapsed` —
  the rule is tested on real output, with zero external data.
- **The E-R spoke seam is real and reserved — and now built (spec-faithful).**
  INTERMARC-NG → LRMoo/CRM is the least-lossy conversion in the system (both ends
  LRM-aligned) and probably the flagship BnF case. `palomar.plugins.intermarc-ng`
  implements it: NG entity-records (Œuvre/Expression/Manifestation) → LRMoo entities,
  the OEMI `7xx $3` relations → R3/R4/R7, reusing the `marcxml` core; agents (Person
  records + `700 A pour créateur`) surface as the identified Linked Art creator, and
  it is a **first-class `convert` source** (`:intermarc-ng`, projection `identity`).
  It round-trips NG → LRMoo → CRM → LRMoo losslessly (`intermarc-ng-test`). The hub,
  the LRMoo view and the CRM/LA/RDF exporters are unchanged — exactly as predicted.
- **Honest data limit.** The format is the public **kitcat INTERMARC-NG manual**
  (codes, OEMI relations, access points are real). But public BnF SRU does not yet
  serve **native** NG entity exports (they live in NOEMI, behind manual transfer), so
  the importer is validated on a **spec-faithful synthetic** corpus, not real native
  records. The one synthetic convention is the record-level entity-type encoding;
  when a native export appears, only the fixture changes, not the importer.

## Alternatives considered

- **Make every format import + export + round-trip.** Rejected: a round-trip through
  a down-projection (hub→CRM→hub) can only re-measure the deliberate projection loss,
  not prove fidelity. "Round-trip" means fidelity for peer spokes and loss-receipt
  for the hub — different things.
- **A direct INTERMARC-NG → CRM crosswalk (bypassing LRMoo).** Rejected as the
  spine: it would re-derive what LRMoo→CRM already does, and forfeit the convergence
  payoff (an INTERMARC-NG record also becoming convertible to IIIF/DC/… for free).
  INTERMARC-NG → LRMoo → CRM keeps the hub.
- **A generic CRM → LRM importer that guesses F-levels.** Not adopted as certified:
  the `E73` downcast is genuinely ambiguous; any guess is `:proposed`/heuristic, gated
  by the loss model — never silently asserted.

## What this ADR does not decide

- The remaining INTERMARC-NG vocabulary beyond the OEMI core (agents/`51x`,
  subjects, finer attributes) and the exact native entity-type encoding — pending a
  real native export; the OEMI Work/Expression/Manifestation core is built.
- Whether/when Linked Art and INTERMARC gain their second direction (priority calls,
  scheduled by value).
- Heuristics for downcasting *flattened* CRM (a curation/inference problem, ADR 0015
  `:ambiguity-collapsed` + ADR 0005 status), if it is ever in scope.

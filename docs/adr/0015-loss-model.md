# 0015 — Loss model: what loss is, where it is measured, how it is reported

- Status: Accepted
- Date: 2026-05-31
- Builds on: ADR 0001 (assertions + provenance), ADR 0003 / 0013 (two-rung
  vocabulary + coverage), ADR 0011 / 0012 (fragments trace loss back to source),
  the diagnostics API (Sprint 4)
- Decision record: [`../wp0-decisions.md`](../wp0-decisions.md) (D9)

## Context

The redefined V1's value proposition is **loss-aware** conversion: every
conversion must report what it lost, in terms an institution can audit.
"Loss-aware" needs a precise, testable definition, or it is marketing.

The substrate already exists: provenance on every assertion (ADR 0001),
`:canon/loss-marker` (ADR 0003), fragments that point back into the source tree
(ADR 0011 / 0012), and a diagnostics API (Sprint 4). This ADR defines *loss* on
top of them. Four sub-questions: the **unit**, the **edges**, the
**categories**, the **metric**.

## Decision

### Unit — the source-native field
Loss is counted in the **source's own terms** — its native fields / subfields
(a MARC subfield, a Dublin Core element, an IIIF property) — not in pivot
assertions. The institution asks "which of *my* subfields survived?", not "which
pivot triples?". Fragments + provenance (ADR 0011 / 0012) already trace a pivot
assertion back to the exact source node, so loss is attributable to a named
source field.

### Edges — both, plus round-trip
Loss is measured at **both** edges, reported per edge:
- **import** (source → pivot): what the source carried that the pivot did not
  capture;
- **export** (pivot → target): what the pivot held that the target cannot
  express.

For a format pair A → B, a **round-trip** report (A → pivot → B, compared to A)
is the headline an evaluator wants.

### Categories — four kinds
Every loss is classified:
- **dropped** — no target representation at all;
- **coerced** — represented via a lossy transform (a structured date flattened
  to a string; a normalisation that discards detail);
- **under-specified** — the target is coarser than the source (mapped only to
  canonical when the source had WEMI-level detail — the graceful-degradation
  case from ADR 0013);
- **ambiguity-collapsed** — N candidates existed and the target forced one
  (ties to the assertion IR's multiplicity, ADR 0001).

### Metric — a per-category breakdown, never one number alone
The report is a **breakdown by category and by source field**, plus per-edge
coverage. A single coverage percentage is allowed as a *headline* but never on
its own: it hides *which* losses matter (dropping a shelfmark ≠ coercing a
note). Coverage is rated at **both** vocabulary rungs — canonical and LRMoo
(ADR 0013).

### Representation — loss is a diagnostic, not a side channel
A loss is a first-class **diagnostic** (Sprint 4) attached to its subject,
carrying its category, the source field, and the edge. `:canon/loss-marker`
(ADR 0003) is the in-graph marker; diagnostics aggregation produces the human-
and machine-readable loss report. Loss is therefore a diagnostic *category* + an
aggregation, not a new subsystem.

## Alternatives considered

- **Unit = pivot assertion.** Rejected: meaningless to an institution ("you lost
  12 triples" vs "you lost the 852 shelfmark"). Source-native is auditable.
- **Measure only the export edge.** Rejected: import loss is real and often the
  larger share; you cannot certify fidelity while ignoring it.
- **A single coverage % as the sole metric.** Rejected: hides which
  fields / categories were lost; a high % can still drop the one field that
  mattered.
- **Loss as a separate report subsystem.** Rejected: duplicates the diagnostics
  API; loss is naturally a diagnostic attached to subjects and aggregated like
  any other.
- **A binary lossless / lossy flag.** Rejected: uselessly coarse; the categories
  + breakdown are what make loss actionable.

## Consequences

- A run emits, alongside its output, a structured **loss report** (machine data
  + human rendering) — per record and aggregate, per edge, per category, per
  source field. This is the production-credibility artifact for BnF / Louvre.
- The diagnostics API gains a loss category and roll-up aggregations;
  `:canon/loss-marker` is populated by mapping / projection rules when they
  drop / coerce / under-specify / collapse.
- Coverage becomes an objective per-plugin quality metric at **both** rungs
  (generalising ADR 0003's "rated on canonical coverage").
- Round-trip tests become a natural CI artifact (A → pivot → B → compare).
- Transforms (ADR 0009 / 0010) should declare when they are lossy, so "coerced"
  is detected automatically rather than guessed.

## Implementation status (2026-06-02)

The model is decided in full above; producers are wired incrementally. Honest
current state, so the taxonomy is not mistaken for working code:

- **`:dropped` / `:import`** — produced by INTERMARC WEMI derivation (`intermarc.wemi`) and
  the canonical→WEMI projection (`lrmoo.project`): native / canonical fields the
  WEMI view does not represent.
- **`:dropped` / `:export`** — produced by the LRMoo RDF exporter
  (`export/export-losses`): every non-`:lrmoo/*` predicate the N-Triples target
  cannot express.
- **`:under-specified` / `:import`** — produced by the canonical→WEMI projection
  (`lrmoo.project`): when the canonical layer carries >=2 languages, the floor
  mints a single Expression where the source implies several (parallel-language
  Expressions collapsed) — the ADR 0013 graceful-degradation case.
- **`:ambiguity-collapsed` / `:import`** — produced by the canonical→WEMI
  projection (`lrmoo.project`): an `uncertain` `:canon/title` (the assertion IR's
  multiplicity, ADR 0001) collapsed to one alternative.
- **`:coerced` / `:import`** — produced by the mapping compiler (`mapping`) when a
  rename applies a transform chain containing a self-declared lossy transform
  (`transforms/lossy-transforms`, e.g. case folding).

All four loss categories now have a producer, across both edges. The
institution-facing **conversion loss report** is assembled
(`palomar.loss-report`): per-edge, per-category and **per-source-field**
aggregation of both edges' loss, with a human-readable rendering — the
"which of *my* fields survived?" artifact this ADR specifies. Still outstanding:
- Plugin-contributed transforms are not yet classified for lossiness (their
  `:coerced` defaults to off); only core transforms declare it.
- A *full* round-trip (re-import the target B and diff against A) needs a reverse
  importer; the report covers the per-field survival account across one
  conversion's two edges, which is the assemblable part.

## What this ADR does not decide

- The exact coverage formula and thresholds — tuned during WP-5.
- Per-format target capability tables (which fields each spoke can express) —
  built per spoke at WP-4.
- The report's concrete serialisation / CLI surface — WP-5 / WP-8.

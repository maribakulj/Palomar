# 0014 — Runtime entity minting (amends ADR 0011)

- Status: Accepted
- Date: 2026-05-31
- Amends: ADR 0011 (lifts the ingest-only restriction, for entities)
- Builds on: ADR 0005 (status model), ADR 0008 (idempotency at merge),
  ADR 0012 (fragment identity scheme), ADR 0013 (the rich pivot it serves)
- Detailed elsewhere: the synthesized-entity identity scheme, clustering, and
  reconciliation — WEMI-derivation ADR (forthcoming; D5 / D6 / D11)
- Decision record: [`../wp0-decisions.md`](../wp0-decisions.md) (D4)

## Context

ADR 0013 makes the rich pivot a **derived LRMoo view built by inference**.
Deriving Works and Expressions that exist in *no* source record means the
`infer` phase must be able to **create entities** — mint subjects that no
importer produced.

ADR 0011 introduced minting but restricted it to **ingest** ("ingest-only").
WEMI derivation cannot honour that: it is inference *over* normalized assertions,
clustering across many records, so it must mint during `infer`.

Three constraints bound the decision:

- minted entities must not break merge idempotency (ADR 0008);
- they must fit the dual status model — machine truth vs human workflow
  (ADR 0005);
- their identity must be **reproducible**, so a second run mints nothing new.

## Decision

1. **Lift the ingest-only restriction, for entities.** The `infer` phase may
   mint entities (new subjects with their own assertions). `normalize` does not
   mint; `repair` may only **propose** minting (§3).

2. **Minted assertions are machine-produced; status follows the engine's phase
   policy.** A minting rule's claims carry `:provenance {:pass :infer …}` (the
   inferred-vs-ingested distinction lives in *provenance*, not status — ADR 0005;
   this also preserves dedup, since assertion identity excludes provenance,
   ADR 0008) plus a `:confidence`. By the engine default
   (`palomar.rules/default-status-for-phase`), `:infer` / `:repair` productions
   are **`:proposed`** — proposals until confirmed (the conservative,
   precision-first default). The D7 hybrid's *high-confidence auto-commit*
   (promotion to `:asserted`) is **now implemented** as a commit policy: a minting
   rule sets `:status :asserted` for claims resting on a determinate identifier
   (the embedded `145 $3` link, the record ARK) and leaves fuzzy claims
   `:proposed` (`intermarc.wemi`); the string-key floor projection
   (`lrmoo/project`) proposes everything; and the export can ship just the
   certified subgraph (`:certified-only?`). There is **no** `:inferred` status
   (ADR 0005). The entity
   *declaration* itself is structural — it goes into `:entities`, statusless.

3. **`repair` proposes, never commits, entities.** Per the dual status model and
   the confidence-gated WEMI-derivation decision (D7), entity creation in `repair`
   is `:proposed` and surfaced via `apply-repairs`. The high-confidence
   automatic path is `infer`; the uncertain tail is proposed.

4. **Identity is supplied, deterministic, and resolved plugin-side.** The runtime
   does **not** compute entity identity. The minting rule obtains a stable id
   from a resolver provided by the plugin / configuration (the identity-resolver
   seam, D5). The runtime only (a) permits minting and (b) merges by that id.
   Because the id is a deterministic function of content (WEMI-derivation ADR, D5),
   the existing idempotent merge (ADR 0008) deduplicates re-mints with **no new
   machinery**: same content → same id → same entity. This keeps the core change
   to a minimal, vocabulary-blind *permission* — no identity policy enters the
   core (ADR 0013).

5. **Fragments are unchanged.** Qualified-value fragment minting keeps the
   ADR 0011 / 0012 rules; this ADR adds *entity* minting only.

## Alternatives considered

- **Keep ingest-only; pre-compute entities before the pipeline.** Rejected:
  WEMI derivation is inference over normalized assertions (cross-record clustering);
  it cannot run before ingest/normalize. Minting must be a pipeline phase.
- **Mint freely in any phase (normalize / infer / repair).** Rejected: enlarges
  the idempotency surface and makes "where do entities appear?" hard to reason
  about. Confining committed minting to `infer` and proposal-minting to `repair`
  matches the status model and keeps provenance crisp.
- **Give the runtime an identity-resolver hook.** Rejected as the primary
  placement: it pulls identity *policy* toward the core. Plugin-side resolution
  (the rule calls a provided resolver) keeps the runtime change to a minimal,
  vocabulary-blind permission, consistent with ADR 0007 and ADR 0013.
- **Mutable / sequential ids (counter, gensym).** Rejected: non-reproducible;
  breaks ADR 0008 idempotency and cross-run stability. Identity must be a pure
  function of content (D5).

## Consequences

- The `infer` phase gains a new capability: a production may bind a **fresh
  subject id** (from the resolver) and attach assertions to it. The rule DSL /
  runtime must support this; the production stays idempotent by that id
  (ADR 0008).
- Idempotency is preserved **by construction** given deterministic ids — and
  becomes a property test: re-running a run mints zero new entities.
- ADR 0011's "ingest-only" line is superseded **for entities**; ADR 0011 remains
  authoritative for qualified-value *fragments*. The index is annotated.
- Provenance gains a *synthesized-from-many-records* shape (a Work's provenance
  lists its manifestations); diagnostics and reporting should render it.
- The cascade case (mint a Work, then link its Expressions in the same phase)
  interacts with ADR 0004 (fixed passes vs fixpoint, D8); decided empirically in
  the WEMI-derivation ADR / WP-0 spike.

## What this ADR does not decide

- *How* identity is computed — the work-key, the authority resolver, clustering
  — WEMI-derivation ADR (D5 / D6), extending ADR 0012.
- WEMI-derivation control thresholds (D7) and reconciliation scope (D11) —
  WEMI-derivation ADR.
- Fixed passes vs scoped fixpoint (D8) — note on ADR 0004.

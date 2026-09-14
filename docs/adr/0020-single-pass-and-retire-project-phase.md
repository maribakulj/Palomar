# 0020 — Single pass per phase; retire the `:project` phase and `:cycles`

- Status: Accepted
- Date: 2026-06-06
- Supersedes: [ADR 0004](./0004-fixed-passes-over-fixpoint.md) (fixed passes
  over fixpoint).
- Amends: [ADR 0008](./0008-idempotency-at-merge.md) — its dedup-at-merge
  decision stands; its `:cycles` motivation does not.

## Context

ADR 0004 gave the pipeline six phases — ingest → normalize → validate →
infer → repair → **project** — and made each phase run a *declared number of
cycles* (default 1, hard cap 16), with chained inference expressed by asking
for more cycles. ADR 0008 then gave `:cycles` real semantics by deduplicating
productions at merge, so re-running a converged rule set is a no-op rather
than a duplicate generator. ADR 0008 explicitly weighed — and deferred — the
alternative of dropping `:cycles` outright.

Two of those features never paid for themselves:

- **The `:project` phase has no rules.** Projection in Palomar is not a
  rule-rewriting step over the IR. The canonical→WEMI work happens in the
  `:infer` phase (`palomar.plugins.lrmoo.project`, `intermarc.wemi`), and
  emission to the ten target formats is done by **exporters** after the
  pipeline. `:project` was a slot reserved for a design that never
  materialized; every enum that lists it is the only place it appears.

- **Multi-cycle execution (`:cycles`) is used by no pipeline.** Every
  `run-phase` / `run-pipeline` caller runs the default single pass. The one
  scenario `:cycles` was meant to serve — rule B reacting to a fact rule A
  asserted earlier — is the chained-inference case, and in V1 it is expressed
  by ordering rules across *phases* (infer feeds repair), not by re-running
  one phase until it settles. The machinery (`max-cycles`, `validate-cycles!`,
  the per-phase cycle loop, the `:cycles` pipeline key) exists only to support
  a capability nothing exercises.

By contrast the **`:repair` phase earns its place**: it produces real
diagnostics carrying `Repair` proposals, and it anchors the `apply-repairs` /
curation workflow (ADR 0005) — proposals stay `:proposed` and are surfaced
for human acceptance, never auto-applied. Keeping it is deliberate.

## Decision

1. **Each phase is a single pass.** `run-phase` fires every rule whose
   `:phase` matches, once, against the record as it entered the phase, then
   merges their productions. `run-pipeline` runs the phases in order, each as
   one pass. The `:cycles` option, `max-cycles`, cycle validation, and the
   `run-phase-once` / `run-phase` split are removed; `run-phase` *is* the
   single pass.

2. **Drop the `:project` phase.** The phase enum is now
   `:ingest :normalize :validate :infer :repair` (the `Phase` schema, the
   `Rule` `:phase` enum, and `PhaseSpec`). Projection remains a real
   capability, performed by exporters outside the pipeline.

3. **Keep `:repair`.** No code change; it is documented here, in the `Phase`
   schema, and in the README as the proposal-bearing phase feeding
   `apply-repairs`.

4. **Retain dedup-at-merge (ADR 0008).** Removing `:cycles` does not remove
   dedup. Within a single pass, two different rules can still derive the same
   `[subject predicate value status]`; structural dedup at merge collapses
   them to one fact while the production trace keeps both firings. That is now
   dedup's whole job.

## Alternatives considered

- **Keep `:cycles` dormant.** Rejected: an unused, documented API with a hard
  cap and its own validation path is a maintenance and comprehension cost for
  a capability no pipeline wants. ADR 0008 already flagged removal as viable;
  the use case it was held open for (chained inference) is served by phase
  ordering in V1.

- **Keep the `:project` phase as a reserved slot.** Rejected: a phase no rule
  targets is dead surface. When a rule-driven projection step is actually
  designed, re-adding a phase to the enum is a one-line change with its own
  ADR — exactly how this one removes it.

- **Drop dedup-at-merge along with `:cycles`.** Rejected: dedup has value
  independent of cycles (two rules, same fact), and removing it would
  reintroduce the duplicate-assertion problem ADR 0008 fixed.

- **Reintroduce fixpoint instead of single pass.** Out of scope and contrary
  to the V1 posture; ADR 0004's "no fixpoint in V1" reasoning is unchanged
  and, if anything, reinforced.

## Consequences

- The runtime is smaller and its contract simpler: a phase is one pass, full
  stop — no cap, no cycle accounting, no convergence questions.
- Pipelines remain deterministic and every production is still attributable to
  its rule and phase via provenance.
- A rule can no longer observe another rule's output **within** the same pass;
  intra-phase chaining must be split across phases (or folded into one rule).
  No existing pipeline relied on the old behaviour.
- `run-phase` loses its options arity and `run-phase-once` is gone; callers
  (all already single-pass) are unaffected. `PhaseSpec` no longer accepts
  `:cycles`.
- ADR 0004 is superseded; ADR 0008's dedup decision stands, re-cast as
  within-pass convergence rather than cross-cycle convergence.
- Migrating back to multi-cycle or fixpoint later remains a localized change
  (a phase mode), should a concrete use case appear — the same door ADR 0004
  left open.

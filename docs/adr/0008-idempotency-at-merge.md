# 0008 — Idempotency at merge

- Status: Accepted (amended by [ADR 0020](./0020-single-pass-and-retire-project-phase.md) and
  [ADR 0023](./0023-justifications-and-status-at-export.md))
- Date: 2026-04-25
- Supersedes: ADR 0004 *partially* — the fixed-passes-without-dedup
  posture. The "no fixpoint detection in V1" position from ADR 0004
  still holds.
- Amended by: ADR 0020 — `:cycles` (multi-cycle execution) is removed.
  The dedup-at-merge decision below **stands**: with single-pass phases it
  now serves *within-pass* convergence (two rules deriving the same fact
  collapse to one) rather than cross-cycle convergence.

## Context

ADR 0004 chose fixed passes over fixpoint detection: a phase runs a
declared number of cycles (default 1, hard cap 16). At the time, the
runtime had no notion of structural identity for productions — each rule
firing produced a fresh item, even if it was indistinguishable from an
earlier one.

The visible cost was documented as a test:

```clojure
(deftest run-phase-idempotent-rule-does-not-grow-unboundedly-without-guard
  ;; Documenting the current (no-dedup) behaviour: 2 diagnostics from
  ;; 2 cycles. Dedup is explicitly out of scope for Sprint 3.
  ...)
```

Restated honestly, this test asserts that running a phase twice produces
twice as many diagnostics — and the user must defend against that by
writing every rule with a corresponding `(absent? ...)` guard.

That makes `:cycles N` a feature without semantics: either the rule is
idempotent by construction (guard makes the second cycle a no-op, so
cycles > 1 is wasted work) or it isn't (cycles > 1 produces duplicates,
which is a bug). The middle ground that justifies multi-cycle in the
first place — chained inference, where rule B reacts to facts asserted
by rule A in cycle N-1 — only works *if* further cycles re-running A
don't pile up duplicates of A's assertions.

So either we drop `:cycles` from the V1 API, or we make merge
deduplicating.

## Decision

Productions are **deduplicated at merge time**, by structural identity.

- An **assertion** is identified by `[:subject :predicate :value :status]`.
  Provenance and confidence are not part of the identity.
- A **diagnostic** is identified by `[:subject :code :severity :message]`.
  Repairs and provenance are not part of the identity.

If an incoming production matches the identity of an item already on the
record, the merge is a no-op. The pre-existing item — and its
provenance — is preserved; the production trace returned by
`run-phase` / `run-pipeline` still records every firing, so consumers
that want to count rule activations rather than resulting facts can do
so.

This makes idempotent rules behave as such, and gives `:cycles` a clear
semantics: cycle N+1 only adds *new* facts, so a rule set that has
converged is a no-op on subsequent cycles. The runtime still does not
*detect* convergence and stop early — that is fixpoint, deferred per
ADR 0004 — it simply doesn't grow when convergence has happened.

## Alternatives considered

- **No dedup, keep documenting it as a feature.** Rejected: the test name
  itself reveals the contradiction ("does not grow unboundedly" — but it
  does). Surfacing `:cycles N` to users with these semantics is a
  misleading API.
- **Remove `:cycles` from the V1 API.** Viable but cuts a feature with
  real use cases (chained inference inside a single phase). Reintroducing
  it later would require this same ADR.
- **Dedup including provenance / confidence in the identity key.**
  Rejected: two rules deriving the same fact would each leave an
  assertion behind, and asking "is the title set?" would return
  duplicates that the user has no way to collapse meaningfully.
  Identity should describe *what* is asserted, not *who* asserted it.
- **Dedup including the value's deep equality (e.g. uncertain values
  with reordered alternatives).** Rejected for V1: structural `=` is
  good enough, and the cost of a normalized-key computation isn't
  justified before we have benchmarks. Logged for Sprint 11.

## Consequences

- Idempotent rules become free to re-run. Multi-cycle execution
  converges naturally without fixpoint detection.
- The production trace (`:productions` in the run result) still
  contains every firing, including duplicates. Trace queries
  (`assertions-by-rule`, `productions-by-phase`, ...) operate on the
  record's deduplicated set; counts of "assertions caused by rule R"
  refer to facts that survived dedup.
- Rule authors who want to express "rule B confirms rule A's fact"
  (different provenance, same fact) cannot do so by emitting a
  redundant assertion — that one is collapsed. A future
  `:supersede` / `:retract` family of production actions, plus an
  explicit provenance-merge action, is the right place to address that.
  Out of scope for V1.
- Performance: each merge scans the existing collection to build the
  identity set, O(N) per merge, O(N²) per cycle. Acceptable at V1 batch
  sizes (single records, modest assertion counts). An incremental set
  carried alongside the record would be the natural Sprint 11
  optimization; we do not pre-emptively add it.
- The documented `run-phase-idempotent-rule-does-not-grow-unboundedly-without-guard`
  test is rewritten to assert idempotency rather than to document the
  bug.

# Architecture Decision Records

Structural decisions that shape the Palomar core. Each ADR is a standalone
document: context, decision, consequences. New major decisions get their own
ADR; supersessions are explicit, not silent edits.

**Status convention.** Status records the *decision*, not the build. An accepted
decision may be only partly implemented; where the gap is material the ADR carries
an `## Implementation status` section listing what is built vs decided-but-unbuilt,
and the index below is annotated *"partially implemented — see ADR"*. This keeps a
decision from being read as shipped code (an audit honesty rule).

## Index

| # | Title | Status |
|---|---|---|
| [0001](./0001-assertion-based-ir.md) | Assertion-based internal representation | Accepted |
| [0002](./0002-edn-as-dsl.md) | EDN as the rule DSL, no parser | Accepted |
| [0003](./0003-core-vs-canonical-vocabulary.md) | Structural vocabulary at core, documentary vocabulary in a plugin | Accepted (extended by 0013) |
| [0004](./0004-fixed-passes-over-fixpoint.md) | Fixed passes in V1, fixpoint deferred | Superseded by 0020 |
| [0005](./0005-status-model.md) | Dual status model: machine truth and human workflow | Accepted |
| [0006](./0006-deps-resolution-and-sandbox.md) | Maven coordinates by default, git override alias for restricted networks | Accepted |
| [0007](./0007-plugins-as-data.md) | Plugins as data, not protocols | Accepted |
| [0008](./0008-idempotency-at-merge.md) | Idempotency at merge: productions deduplicate by structural identity | Accepted (amended by 0020) |
| [0009](./0009-mapping-schema.md) | Mapping schema: data-shaped sugar over rules | Accepted (partially superseded by 0011) |
| [0010](./0010-stdlib-extensibility.md) | Stdlib extensibility: predicates and transforms via plugins | Accepted |
| [0011](./0011-fragments-for-qualified-values.md) | Fragments as the canonical home for qualified values | Accepted (entity-minting added by 0014) |
| [0012](./0012-fragment-identity-scheme.md) | Fragment identity scheme | Accepted (extended by 0016) |
| [0013](./0013-lrmoo-rich-pivot.md) | LRMoo as the rich pivot vocabulary, via a derived typed view | Accepted (partially implemented — see ADR) |
| [0014](./0014-runtime-entity-minting.md) | Runtime entity minting (amends 0011) | Accepted |
| [0015](./0015-loss-model.md) | Loss model: unit, edges, categories, metric | Accepted (partially implemented — see ADR) |
| [0016](./0016-wemi-derivation.md) | WEMI derivation under LRMoo: synthesizing entities, identity, and reconciliation | Accepted (partially implemented; scale layer in 0018) |
| [0017](./0017-entity-representation.md) | Synthesized entity representation (entities on records) | Accepted |
| [0018](./0018-entity-resolution-at-scale.md) | Entity resolution at scale: reconcile-to-authority, equivalence as assertion, revisability | Accepted (partially implemented — see ADR) |
| [0019](./0019-conversion-directionality.md) | Conversion directionality: spokes bidirectional, hub a target, CRM→LRM a downcast | Accepted (partially implemented — see ADR) |
| [0020](./0020-single-pass-and-retire-project-phase.md) | Single pass per phase; retire the `:project` phase and `:cycles` | Accepted |

## Template

New ADRs follow this skeleton:

```markdown
# NNNN — Title

- Status: Proposed | Accepted | Superseded by NNNN
- Date: YYYY-MM-DD

## Context
What problem are we facing? What are the constraints?

## Decision
What did we choose? Stated plainly.

## Alternatives considered
Briefly, what else was on the table and why it was rejected.

## Consequences
What becomes easier. What becomes harder. What future decisions are gated on this.
```

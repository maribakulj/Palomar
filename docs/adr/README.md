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
| [0002](./0002-edn-as-dsl.md) | EDN as the rule DSL, no parser | Accepted (extended by 0035) |
| [0003](./0003-core-vs-canonical-vocabulary.md) | Structural vocabulary at core, documentary vocabulary in a plugin | Accepted (extended by 0013, 0021) |
| [0004](./0004-fixed-passes-over-fixpoint.md) | Fixed passes in V1, fixpoint deferred | Superseded by 0020 |
| [0005](./0005-status-model.md) | Dual status model: machine truth and human workflow | Accepted (extended by 0023, 0028) |
| [0006](./0006-deps-resolution-and-sandbox.md) | Maven coordinates by default, git override alias for restricted networks | Accepted |
| [0007](./0007-plugins-as-data.md) | Plugins as data, not protocols | Accepted |
| [0008](./0008-idempotency-at-merge.md) | Idempotency at merge: productions deduplicate by structural identity | Accepted (amended by 0020, 0023, 0027) |
| [0009](./0009-mapping-schema.md) | Mapping schema: data-shaped sugar over rules | Accepted (partially superseded by 0011; extended by 0021 and 0036) |
| [0010](./0010-stdlib-extensibility.md) | Stdlib extensibility: predicates and transforms via plugins | Accepted |
| [0011](./0011-fragments-for-qualified-values.md) | Fragments as the canonical home for qualified values | Accepted (entity-minting added by 0014; normalize-minted fragments by 0021) |
| [0012](./0012-fragment-identity-scheme.md) | Fragment identity scheme | Accepted (extended by 0016) |
| [0013](./0013-lrmoo-rich-pivot.md) | LRMoo as the rich pivot vocabulary, via a derived typed view | Accepted (partially implemented — see ADR) |
| [0014](./0014-runtime-entity-minting.md) | Runtime entity minting (amends 0011) | Accepted |
| [0015](./0015-loss-model.md) | Loss model: unit, edges, categories, metric | Accepted (partially implemented — see ADR; extended by 0024 and 0033) |
| [0016](./0016-wemi-derivation.md) | WEMI derivation under LRMoo: synthesizing entities, identity, and reconciliation | Accepted (partially implemented; scale layer in 0018) |
| [0017](./0017-entity-representation.md) | Synthesized entity representation (entities on records) | Accepted |
| [0018](./0018-entity-resolution-at-scale.md) | Entity resolution at scale: reconcile-to-authority, equivalence as assertion, revisability | Accepted (partially implemented — see ADR; §2 amended by 0032) |
| [0019](./0019-conversion-directionality.md) | Conversion directionality: spokes bidirectional, hub a target, CRM→LRM a downcast | Accepted (partially implemented — see ADR; partially superseded by 0022) |
| [0020](./0020-single-pass-and-retire-project-phase.md) | Single pass per phase; retire the `:project` phase and `:cycles` | Accepted |
| [0021](./0021-responsibility-kind-and-extent-qualifiers.md) | Responsibility, note kind and extent as qualifier coords; constant qualifiers | Accepted |
| [0022](./0022-domain-faithful-serialisation.md) | Domain-faithful serialisation: no super-class downcast, titles through a Nomen, editions are texts | Accepted |
| [0023](./0023-justifications-and-status-at-export.md) | Every justification is kept; out-of-force claims are never read or written | Accepted (amended by 0025) |
| [0024](./0024-observed-loss.md) | Observed loss: occurrence-level fidelity audit, exports name what they omit | Accepted |
| [0025](./0025-history-target-and-iri-safety.md) | The provenance record keeps the history; no invalid IRI is ever written | Accepted |
| [0026](./0026-ontology-conformance.md) | RDF output is checked against the vocabularies' own files | Accepted |
| [0027](./0027-assertion-identity-and-annotations.md) | An assertion is identified by its content; statements about it are annotations | Accepted (id spelling amended by 0034) |
| [0028](./0028-curation-decisions-as-annotations.md) | Curation decisions are kept on the record and written to PROV-O | Accepted |
| [0029](./0029-named-graph-datasets.md) | Facts, proposals, history and provenance in separate named graphs | Accepted |
| [0030](./0030-run-manifest.md) | A conversion can write a manifest of everything its output depends on | Accepted |
| [0031](./0031-source-evidence.md) | Every value keeps where it was read in its source | Accepted |
| [0032](./0032-graded-alignments.md) | Alignments are graded SKOS mapping claims, never owl:sameAs | Accepted |
| [0033](./0033-loss-taxonomy-and-relation-fidelity.md) | Loss aspects, a projection edge, and relation fidelity | Accepted |
| [0034](./0034-library-api-and-edn-ir.md) | A stable library API and the pivot as an EDN file | Accepted |
| [0035](./0035-construct-templates.md) | Graph templates in the rule language: construct, mint, optional, not | Accepted |
| [0036](./0036-publication-events.md) | Publication and production events | Accepted |
| [0037](./0037-oai-pmh-and-dcmi-terms.md) | OAI-PMH responses and DCMI Terms | Accepted |
| [0038](./0038-authority-files.md) | Authority files as reconciliation pools | Accepted |
| [0039](./0039-archival-description.md) | Archival description: EAD units and EAC-CPF authorities | Accepted (amended by 0044) |
| [0040](./0040-tuple-keys.md) | Identity keys are tuples, never joined strings | Accepted |
| [0041](./0041-resemblance-identity-shared-node.md) | Resemblance is not identity, and identity alone shares a node | Accepted |
| [0042](./0042-facts-only-by-default.md) | A target that states facts states only facts, by default | Accepted |
| [0043](./0043-occurrences-and-node-fidelity.md) | Occurrences are kept, deletions reported, values checked on their node | Accepted |
| [0044](./0044-archives-toward-ric-o.md) | Archives project to RiC-O, not to WEMI; EAD ids come from the source | Accepted (direction) |
| [0045](./0045-ric-o-projection.md) | The archival view is RiC-O | Accepted |
| [0046](./0046-an-identified-agent-names-its-occurrence.md) | An identified agent names the occurrence it identifies | Accepted |
| [0047](./0047-durable-archival-ids.md) | An archival id moves only when the source gives no better | Accepted |

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

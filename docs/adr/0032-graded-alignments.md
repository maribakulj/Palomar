# 0032 — Alignments are graded SKOS mapping claims, never owl:sameAs

- Status: Accepted
- Date: 2026-10-25
- Amends: [ADR 0018](./0018-entity-resolution-at-scale.md) §2 (the equivalence predicate)

## Context

ADR 0018 §2 says a reconciliation verdict is an assertion of the IR, and names
`owl:sameAs` as one candidate predicate. The implementation kept proposals out
of the IR altogether (`propose-agent-links` returns maps), so they could not be
curated, exported or kept in a record's history. And `owl:sameAs` states
identity — every statement about one node becomes true of the other — which a
name-similarity score cannot justify.

## Decision

1. An alignment is an ordinary claim of the IR, between a record's local agent
   (an `E21_Person` scoped to that record) and the authority it may be:
   - `:skos/exactMatch` for a certifiable proposal scoring `reconcile/exact-score`
     (1.0), `:skos/closeMatch` below it; status `:proposed`, the score as
     confidence (`reconcile/align-agents`);
   - a curator accepts or rejects it like any claim (ADR 0028 keeps the
     decision); a rejected alignment stays, out of force, in the history;
   - `skos:relatedMatch` is available to an application for distinct but
     related entities.
2. Identity is attested, not inferred from a match: only an *accepted* exact
   match to a determinate authority id becomes a shared agent node, through
   `reconcile/accepted-links` and `apply-agent-links` — the authority's
   `E21_Person`. A close match is never a link. Palomar emits no `owl:sameAs`.
3. The authority is the alignment's target (`:reconcile/authority`), not an
   `E21_Person`, until then: a proposal must not create an identified person.
4. The RDF view writes `:skos/*` mapping properties (SKOS namespace); the
   dataset targets place a proposed alignment in the proposals graph, an
   accepted one in the facts graph and a rejected one in the history graph.

## Alternatives considered

- *`owl:sameAs` for accepted matches*: identity is already represented by the
  shared authority node once applied; `sameAs` would add a reasoning hazard.
- *A dedicated `:palomar/resolves-to` predicate*: SKOS mapping properties are
  the standard for graded alignments between vocabularies and are understood
  by every SKOS consumer.

## Consequences

- The five cases the review asked for are distinct: attested identity (the
  shared authority node), proposed equivalence (`exactMatch`, proposed),
  approximate correspondence (`closeMatch`), related distinct entities
  (`relatedMatch`), rejected alignment (any of these, out of force).

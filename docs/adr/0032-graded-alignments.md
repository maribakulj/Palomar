# 0032 — Alignments are graded SKOS mapping claims, never owl:sameAs

- Status: Accepted
- Date: 2026-10-25
- Amends: [ADR 0018](./0018-entity-resolution-at-scale.md) §2 (the equivalence predicate)
- Amended by: [ADR 0057](./0057-the-identity-relation-between-persons.md) (what `exactMatch` commits to; cluster memberships)

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
     (1.0; amended by ADR 0057: a name-only proposal is now always a close
     match), `:skos/closeMatch` below it; status `:proposed`, the score as
     confidence (`reconcile/align-agents`) — amended by lot M4 (ADR 0018):
     a scored proposal is an exact match only when proposed outright, and the
     score is a confidence only when a calibrated model's posterior, on the
     pool it was calibrated on, with its proof; the Jaccard is evidence in
     the provenance, never a confidence;
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

## Amendment (lot M5, ADR 0057, 2026-11-09): what `exactMatch` commits to

SKOS makes `skos:exactMatch` transitive (SKOS Reference S45): two local
agents exactMatch one authority are, for a reasoner, exactMatch each other.
ADR 0057 amends §1 and §2:

- **A name-only proposal is a `skos:closeMatch`**, whatever its score
  (`reconcile/exact-score` is gone): no model decided it. An `exactMatch`
  Palomar writes without a curator is only an outright `:decision
  :propose`. A curator makes a close match an identity with
  `reconcile/confirm-identity`, which supersedes it by an accepted
  `exactMatch` (§2 then applies to that one).
- Palomar's code never closes the alignments: persons are grouped by the
  constrained clustering of ADR 0057 (`palomar.agent-cluster`), which never
  puts two cannot-linked agents (incompatible dates, two authorities, a
  rejected alignment) in one cluster, treats accepted claims as hard
  must-links, and joins besides them only outright proposals. Its result
  is a second kind of claim, `local-agent skos:exactMatch person-node`
  (`:reconcile/person`, no IRI, one per cluster and batch), `:proposed`
  until accepted, revised by rejection or reopening with its history kept
  — the one place where `exactMatch`'s transitivity is true by
  construction. A review candidate is never a membership. §2 holds for it
  too: a person node is no authority and never becomes a shared node.
- §2's shared node is refused (`apply-agent-links`, `:conflict`) for two
  accepted links to one authority whose mentions' dates cannot be one
  person's.

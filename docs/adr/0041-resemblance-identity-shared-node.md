# 0041 — Resemblance is not identity, and identity alone shares a node

- Status: Accepted
- Date: 2026-10-26
- Amends: [ADR 0018](./0018-entity-resolution-at-scale.md) (applying a link),
  [ADR 0036](./0036-publication-events.md) (event places and actors),
  [ADR 0013](./0013-lrmoo-rich-pivot.md) (the creator-less Expression)

## Context

The post-audit review found one confusion in three places. Two things that
*look* alike (the same name, the same title) were made *one* node:
- **Reconciliation.** A curator's acceptance, in one record, that "Gustave
  Flaubert" is an ISNI was reduced to the pair name → ISNI.
  `apply-agent-links` then gave that ISNI to every record naming a Gustave
  Flaubert, homonyms included.
- **Events.** A place or a publisher was keyed by its normalised name, so
  every "Paris" (France, Texas) and every "Gallimard" in a corpus was one
  node.
- **The floor projection.** An Expression with no creator was keyed by its
  title alone, so every creator-less "Correspondance" or "Poems" in a corpus
  was one Expression.

Each yields valid RDF in which unrelated things are the same resource. Such
a merge spreads to every graph that links to it, and nothing downstream can
see that it was a guess.

## Decision

Palomar distinguishes three operations, and only the third shares a node:
1. **Resemblance**: two things look alike. It is a proposal, a
   `skos:closeMatch` or `exactMatch` with a score (ADR 0032), never a shared
   id.
2. **Identity**: two things are one, established by an authority identifier,
   by the source's own structure, or by a curator's decision, each with its
   scope.
3. **A shared node**: only what identity justifies.

Applied here:
- **Reconciliation.** A link applies only to the records it is scoped to:
  - `accepted-links` scopes each link to the record where it was accepted
    (`:records #{rid}`);
  - a curator may state `:records :all`, a deliberate corpus-wide decision;
  - a link with no scope is refused (`:unscoped`).
- **Events.** A Place and an Actor are scoped to their Manifestation. Saying
  that two are one is an alignment's job.
- **The creator-less Expression** is keyed by the record and the title. With
  a creator, the Work and Expression keys are unchanged. They are a measured,
  `:proposed` clustering (ADR 0016) that curation reviews.

## Consequences

- A corpus no longer merges homonymous people, places, publishers or
  anonymous titles by construction. Merging them is a decision that can be
  seen and reverted.
- Fewer shared nodes: a corpus-wide view of places or publishers now needs
  reconciliation against an authority (ADR 0038), which is the honest path.
- The creator-keyed Work remains a resemblance-based identity. It stays
  `:proposed` and, from C3 on, out of the default fact export.

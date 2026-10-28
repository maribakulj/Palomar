# 0047 — An archival id moves only when the source gives no better

- Status: Accepted
- Date: 2026-10-28
- Amends: [ADR 0044](./0044-archives-toward-ric-o.md) (EAD ids)

## Context

ADR 0044 named an EAD unit by the finding aid's identity and the unit's
`unitid` when that was unique, else by its position. The second post-audit
review found two gaps:
- **A duplicate moved the original.** A revision that added a second unit
  with an existing `unitid` turned **both** units positional. The unit
  already published, which had not changed, got a new id. A position also
  changes as soon as a unit is inserted before it.
- **Only EAD reported an unstable id.** EAC-CPF named a record with no
  `recordId` by the document's digest, which changes with every edit, and
  said nothing about it.

## Decision

1. **The first unit keeps its `unitid`.** Units sharing a `unitid` are
   ranked in document order. The first is named by the `unitid` alone, as if
   it were unique. Each later one adds its rank (`u<unitid>_-2`). Adding a
   duplicate therefore leaves every existing id unchanged, unless it is
   inserted *before* the original, which no rule can tell apart from a
   renaming.
2. **Importing never fails on an imperfect identifier.** A real finding aid
   with a duplicate or a missing `unitid` is still read. Refusing it would
   leave the user with nothing to curate.
3. **Every id that is not durable says so.** An `:under-specified` import
   loss is reported on the record itself:
   - an EAD unit named by its rank, with the duplicated `unitid`;
   - an EAD unit named by its position (no `unitid`);
   - a finding aid named by its digest (no `recordid`/`eadid`), as before;
   - an EAC-CPF record named by its digest (no `recordId`).

Rejected:
- Refusing the document: a fonds with one duplicate `unitid` would yield no
  records at all.
- Ranking all duplicates, including the first: that is exactly the move the
  audit found.
- Naming a unit by a digest of its own content: editing a note would change
  its id again (post-audit test 10).

## Consequences

- A diagnostic lists the units whose identity depends on the document's
  order, so a curator can fix their `unitid` at the source.
- An id is durable exactly when the source's identifiers make it so, and the
  conversion says which ids those are.

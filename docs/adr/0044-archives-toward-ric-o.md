# 0044 — Archives project to RiC-O, not to WEMI; EAD ids come from the source

- Status: Accepted (direction; the RiC-O projection is plan lot C7)
- Date: 2026-10-26
- Amends: [ADR 0039](./0039-archival-description.md)

## Context

ADR 0039 gave every EAD unit an LRMoo `F3_Manifestation` that
`crm:P106i_forms_part_of` its parent's. That passes the ontology check and
says the wrong thing. A Manifestation describes a publication, a set of
copies carrying one expression. An archival unit is unique material, defined
by its provenance (the *respect des fonds*), its level of description, its
creator or accumulator and the activity that produced it. The post-audit
review, and the owner, agreed this is a modelling debt that gets more costly
with every notice converted.

The same review found that a unit's id depended on the digest of the whole
finding aid, so correcting one note renamed every unit of the fonds.

## Decision

1. **Direction.** Archival description projects to RiC-O (Records in
   Contexts, the International Council on Archives' ontology):
   - Record Sets and Records, with their levels;
   - Agents (Person, Group, Family) with their roles;
   - the relations EAC-CPF states.

   It does not project to WEMI. LRMoo remains the pivot for bibliographic
   description. Where an archival unit holds a published item, the two are
   linked, not merged. The projection is lot C7 of the post-audit plan.
2. **Until then, the EAD spoke is experimental.** Its RDF shape (F3 +
   `P106i`) is provisional and documented as such (`docs/guarantees.md`):
   nothing should be built on it.
3. **Stable unit ids, from the source's own identifiers.** A unit's id is:
   - the finding aid's maintenance agency and `recordid` (EAD3) or `eadid`
     (EAD 2002);
   - plus the unit's `unitid` when it is unique in the document, else its
     position.

   Each part goes through `model/encode-name` (injective and readable as
   EDN, shared with the OAI ids), joined by `_-`, which no encoded part
   holds. Two descriptions of one fonds (same agency, record id and unit id)
   name it alike, which is identity by the source's identifiers (ADR 0041).
   A finding aid that states no identifier is named by its digest, and the
   importer reports it.

## Consequences

- Editing a finding aid renames none of its units. The ids will survive
  the move to RiC-O.
- The current EAD RDF output is not a contract until C7 replaces it.

# 0045 — The archival view is RiC-O

- Status: Accepted
- Date: 2026-10-26
- Implements: [ADR 0044](./0044-archives-toward-ric-o.md); supersedes the F3 +
  `P106i` view of [ADR 0039](./0039-archival-description.md)

## Decision

1. **RiC-O 1.1 is vendored** (`test/fixtures/documentary/rico/`, ICA/EGAD,
   CC BY 4.0) and joins the ontology check (ADR 0026). A vocabulary file now
   declares only the terms of its own namespace. RiC-O also states some SKOS
   terms, and these would otherwise have made the checker treat SKOS as
   closed.
2. **Each EAD unit projects to RiC-O**, `:asserted`, since the finding aid
   states it:
   - an `item` is a `rico:Record`;
   - any other level is a `rico:RecordSet`, with `rico:hasRecordSetType` the
     ICA record set type its `@level` names: `fonds`, `subfonds`,
     `recordgrp` and `subgrp` are Fonds, `series` and `subseries` Series,
     `file` File, `collection` Collection;
   - `rico:title`, `rico:identifier`, `rico:date` (as transcribed) and
     `rico:scopeAndContent` (abstract and scope note);
   - `rico:isOrWasIncludedIn` the parent's RecordSet;
   - `rico:hasCreator` an agent typed by the origination element:
     `persname` is a Person, `corpname` a CorporateBody, `famname` a Family,
     anything else an Agent. It is named (`rico:name`) and scoped to the unit
     (ADR 0041).

   No LRMoo entity is minted for a unit.
3. **EAC-CPF is a spoke** (`--from eac-cpf`). Each record is a RiC-O
   agent: a `rico:Person`, `rico:CorporateBody` or `rico:Family`
   (`entityType`), with:
   - its names, the authorized one first (`rico:name`);
   - its existence dates (`rico:date`);
   - its history (`rico:history`);
   - its record id (`rico:identifier`).

   Its `entityId` is the agent's IRI when that is an IRI; a local `recordId`
   is not. Relations are kept as natives and reported. The sample's targets
   are file names, and RiC-O relation types need their own mapping, which is
   later work. The authority pool for reconciliation (ADR 0038) still reads
   EAC-CPF too.
4. **Collectivities in reconciliation.**
   - An authority's entity type travels from the pool, through the
     proposal, the alignment and the accepted link.
   - The type comes from EAC-CPF `entityType` or the MADS name class (MADS
     `CorporateName`, `ConferenceName` and `FamilyName`).
   - A corporate body or a family is applied as a `crm:E74_Group`, a person
     as an `E21_Person`, and `reconcile-agents` counts both.
5. **Exports.**
   - The RDF views write RiC-O. Pure CIDOC CRM leaves it out and reports each
     RiC-O predicate as a loss.
   - Linked Art and the floor formats read the canonical floor and report
     the rest, as before.

## Consequences

- An archival unit is described as an archive: hierarchy, levels and
  creators.
- What is still not read stays reported: digital objects, containers,
  `controlaccess`, `bioghist`. Validation on a real corpus is still to do,
  so the EAD spoke stays marked experimental until then.

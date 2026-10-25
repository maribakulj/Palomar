# 0039 — Archival description: EAD units and EAC-CPF authorities

- Status: Accepted
- Date: 2026-10-25
- Extends: [ADR 0013](./0013-lrmoo-rich-pivot.md) (projection rungs) and
  [ADR 0038](./0038-authority-files.md)

## Context

Archives describe their holdings in EAD finding aids, hierarchies of units (a
fonds, its series, files and items), and the people and bodies behind them in
EAC-CPF. The generic projection does not fit an archival unit:
- it mints a Manifestation, an Expression keyed by creator and title, and a
  Work when there is a creator;
- a unit is unique material, not an edition;
- its title ("Correspondence", "Lowell Thomas Papers") repeats across finding
  aids, so an Expression keyed by title alone would merge unrelated units;
- its creator is stated once on the fonds, so a key made of "fonds creator +
  item title" means nothing.

## Decision

1. **`palomar.plugins.ead`** reads EAD 2002 and EAD3, in their namespaces or
   none, as one record per unit of description: the archdesc and every
   `c`/`c01`…`c12`.
   - Mapped: title, date (`unitdate`, or `unitdatestructured`'s range),
     `unitid`, origination (role `:creator`), abstract and scope (notes of kind
     `:abstract`), and digital objects.
   - `@level` and the parent unit are kept as natives.
   - Every other element of a unit is reported as import loss.
   - A unit's id is the document's digest plus the unit's position. The
     finding aid's own identifier is often empty or reused, so it cannot name
     the unit.
   - Each value carries its XPath (ADR 0031).
2. **An archival projection rung**. Each unit gets a Manifestation, keyed by
   the unit, with its title `:asserted`, and a
   `crm:P106i_forms_part_of` to its parent's Manifestation, `:asserted`: it is
   the finding aid's own structure. P106i's domain and range, E90, hold for
   F3 through E73. No Expression or Work is minted. Every other canonical
   field of the unit is a `:projection` loss.
3. **EAC-CPF as a reconciliation pool** (`authorities/eac-pool`). One entry:
   - the authorized name, in the requested language or else the first;
   - the other names as variants;
   - its entity type;
   - its `entityId` as id only when that is an IRI. A local `recordId` such as
     `001` makes no certifiable match.

   Functions, relations and descriptions are listed as unread. `reconcile
   --authorities` takes an EAC-CPF file.

## Consequences

- A finding aid converts to every target, and its hierarchy survives in RDF.
- In the floor formats (DC, MARC, MODS) the hierarchy is the `:ead/parent`
  native, reported as export loss.
- E78 Curated Holding, F5 Items, `@level` as a type, and a RiC-O view are
  deferred. They need classes no exporter writes yet, and their own ADR.
- Reconciliation matches agents as `E21_Person`. Most EAD originations and
  EAC-CPF entities are corporate bodies (`E74_Group`); widening it is its own
  step.
- The creator-less Expression of the generic projection is keyed by title
  alone, so two non-archival records with one title and no creator share it.
  That holds before this ADR and is left to its own decision.

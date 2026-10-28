# 0052 — A museum object is a CIDOC CRM object, read from LIDO

- Status: Accepted
- Date: 2026-10-28
- Builds on: [ADR 0049](./0049-a-target-says-what-it-can-describe.md), [ADR 0041](./0041-resemblance-identity-shared-node.md)

## Context

The third post-audit review found no museum chain in Palomar. No importer
read LIDO, the format museums exchange object records in. The French
Ministry of Culture adopted LIDO and its LIDO-MC profile in 2025. The only
Linked Art objects were the physical copies of editions (`F5_Item`). A
painting, a drawing or a sculpture cannot be described as an edition: it is
a human-made object, with a production, dimensions, materials, a keeper and
an acquisition history.

## Decision

1. **A LIDO spoke** (`:lido`) reads LIDO 1.0 and 1.1 by local name, and so
   LIDO-MC, which is LIDO 1.1.
   - It takes one record per `lido:lido`, bare or in a `lidoWrap`, named by
     its `lidoRecID`. With no `lidoRecID` the record is named by a digest, and
     that is reported (ADR 0047).
   - Every value is a native `:lido/*` with its absolute XPath.
   - Each concept (work type, classification, material, technique, role,
     subject, rights) is kept whole, as `{:uri :label}`, beside its terms in
     every language.
   - What it does not read is reported, for instance related works and
     subject actors.
   - OAI-PMH reads the `lido` metadata prefix through it.
2. **Resource type: object** (ADR 0049).
   - Linked Art, Dublin Core and the RDF targets accept an object.
   - MARC21, MODS and IIIF do not.
   - The canonical floor gets only what a generic description says of an
     object: titles, the production's actors as creators, its date, the
     inventory number, the description and the images.
3. **The CIDOC CRM view.** The projection makes an `E22_Human-Made_Object` with:
   - its titles;
   - its types (`P2` → `E55_Type`, named by the concept URI);
   - its inventory number (`E42_Identifier`);
   - its dimensions (`E54_Dimension`, `P90` value, `P91` unit);
   - its materials (`P45` → `E57_Material`);
   - its keeper (`P50` → the repository's `E74_Group`);
   - its subjects (`P62_depicts`), its images (`P138i` → `E36_Visual_Item`)
     and its rights (`P104` → `E30_Right`).

   **Each event:**
   - It is a production (`P108i` → `E12_Production`), an acquisition (`P24i`
     → `E8_Acquisition`) or another event the object was present at (`P12i` →
     `E5_Event`).
   - It has its type, time-span (`P82`, `P82a`, `P82b`), place (`P7`), actors
     (`P14`), description (`P3`) and, for a production, its techniques (`P32`).
   - An actor or a place with an authority URI (GND) is that node.
   - Without one, the actor or place is scoped to the record (ADR 0041).
4. **The Linked Art view.** The object becomes a `HumanMadeObject`, plus the
   `VisualItem` it `shows`, which is `about` the subjects and `subject_to` the
   work's rights. Linked Art places those there, not on the object.
   - The inventory number becomes an Accession Number.
   - Dimensions take their AAT type and unit.
   - The production and the acquisitions are embedded.
   - Materials and measurements as displayed, the description, the
     inscriptions, the credit line and the provenance become statements.
   - An actor with no authority id is referenced by its record-scoped node, as
     in the CRM view. It is a reference, not an identity.
   - Both documents validate against the official `object.json` and
     `image.json`.
5. **A real corpus.** The tests run on five Städel Museum records harvested
   over OAI-PMH (CC0 metadata), not on hand-made fixtures.

Rejected: projecting objects through LRMoo. An object is not the
manifestation of an expression, and Linked Art would make it a text.

## Consequences

- A museum can publish its LIDO export as Linked Art and CIDOC CRM, and
  describe it in Dublin Core, with every value's provenance.
- A MARC21 discovery record for an object (Leader/06 `k` or `r`) is a profile
  of its own, like the archival one (ADR 0050).
- Linking a IIIF manifest to the object it presents (`P138i`) needs a shared
  identifier between the two records, which the sources do not give yet.

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
     its `lidoRecID` and the source (`lido:source`) that issued it, since a
     local id is unique only within its institution (amended, post-audit 4,
     F6). This changes the key, and so every IRI minted from it, of a record
     that states a `lido:source`: data converted before F6 must be
     re-converted, not merged. The record's own `lidoRecID`, as written, stays
     its source identifier (a MARC `001`): two institutions' records with
     the same `lidoRecID` share that `001`, and no `003` says whose it is. With no `lidoRecID` the record is
     named by a digest, and that is reported (ADR 0047).
   - Every value is a native `:lido/*` with its absolute XPath.
   - Each concept (work type, classification, material, technique, role,
     subject, rights) is kept whole, as `{:uri :label}`, beside its terms in
     every language.
   - What it does not read is reported, for instance related works and
     subject actors.
   - OAI-PMH reads the `lido` metadata prefix through it.
   - Each title keeps its language (`:lido/title-name {:content :lang}`): its
     own `xml:lang`, else its set's, else the descriptive metadata's
     (amended, post-audit 4, F7).
   - What the LIDO schema (1.0 and 1.1) requires and a record lacks is a
     warning on the record (`:lido/missing-required`, which `validate`
     reports): the `lidoRecID`, the descriptive and administrative metadata
     and the `xml:lang` of each, an object work type, a title set and its
     name, each event's type, each actor in a role and its name, each
     measurement's type, unit and value, and the record's id, type and source
     (amended, post-audit 4, F7). This is no schema
     validation: the rest of the schema is not checked, and neither are
     LIDO-MC's own rules (its further required elements and its
     vocabularies).
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
   - its dimensions (`E54_Dimension`, `P90` value, `P91` unit), when the
     value is a number; a value that is none ("ca. 5") is a `P3` note
     (amended, post-audit 4, F6);
   - its materials (`P45` → `E57_Material`);
   - its keeper (`P50` → the repository's `E74_Group`);
   - its subjects (`P62_depicts`), its images (`P138i` → `E36_Visual_Item`)
     and its rights (`P104` → `E30_Right`).

   **Each event:**
   - It is a production (`P108i` → `E12_Production`), an acquisition (`P24i`
     → `E8_Acquisition`) or another event the object was present at (`P12i`). That
     event is an `E7_Activity` when someone carried it out, it used a technique,
     or its type is an activity (an exhibition, a restoration…), since `P14` and
     `P32` take an E7; otherwise an `E5_Event` (amended, post-audit 4, F1).
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
   - A title's names carry its `language`, an AAT term, for English, German,
     French, Dutch, Italian and Spanish; a title in any other language is
     written with none, and that is reported (amended, post-audit 4, F7).
   - Both documents validate against the official `object.json` and
     `image.json`.
5. **A real corpus.** The tests run on Städel Museum records harvested over
   OAI-PMH (CC0 metadata), not on hand-made fixtures: five paintings and
   drawings, and six of the graphic arts (drawings, prints with an inventor
   and an engraver, a photograph, two draughtsmen; amended, post-audit 4,
   F7).

Rejected: projecting objects through LRMoo. An object is not the
manifestation of an expression, and Linked Art would make it a text.

## Consequences

- A museum can publish its LIDO export as Linked Art and CIDOC CRM, and
  describe it in Dublin Core, with every value's provenance.
- A MARC21 discovery record for an object (Leader/06 `k` or `r`) is a profile
  of its own, like the archival one (ADR 0050).
- Linking a IIIF manifest to the object it presents (`P138i`) needs a shared
  identifier between the two records, which the sources do not give yet.

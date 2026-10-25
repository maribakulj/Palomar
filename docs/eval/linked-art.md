# Linked Art export — the verified WEMI → Linked Art mapping

`palomar.plugins.lrmoo.linked-art` serialises the WEMI view as
[Linked Art](https://linked.art) JSON-LD — the museum-sector output (the Louvre
target). Linked Art is a profile of CIDOC-CRM expressed as a structured JSON-LD
tree (not RDF triples-as-JSON-LD), so the export builds the resource tree Linked
Art consumers expect.

The mapping is **grounded in the official Linked Art model examples**, not guessed
— the load-bearing decision (how to represent the LRM Work/Expression/Manifestation
levels, which Linked Art has no native equivalent for) was taken from the examples below,
fetched and read directly.

## Entity mapping

*Revised 2026-10-25 (interoperability audit S01, S10).* The first version rooted
each record at the Manifestation as a `HumanMadeObject`. That was a category
error: an F3 Manifestation is an *edition*, an abstract publication, and Linked
Art's textual-document model (<https://linked.art/model/document/>) separates
"the physical or digital carrier of the text, and the text itself" — the text is
a `LinguisticObject`, which every physical copy (`HumanMadeObject`) `carries`.
The `yul_10801219` example the first version relied on is precisely a *copy*
("Yale's copy of Koot's Night Watch"), i.e. an F5 Item.

| WEMI | Linked Art type | linked by | source example |
|------|-----------------|-----------|----------------|
| **F3 Manifestation** + its **F2 Expression** (the text as published, carries the ARK) | `LinguisticObject` | `part_of` → the Work | `example/text/koot_nightwatch/1`, `/2` |
| **F1 Work** (the abstract conceptualization) | `PropositionalObject` | — | `koot_idea`; matches our CRM choice F1 → E89 |
| **F5 Item** (a physical copy) | `HumanMadeObject` | `carries` → the text | `example/object/yul_10801219/1` |

Linked Art "does not … provide FRBR or other complex conceptual hierarchies"
(model/document): there is no edition level between the text and the abstract
work. Each Expression therefore folds into its Manifestation's
`LinguisticObject`, and the export reports that fold as `:under-specified` loss
rather than inventing a level. One document is emitted per Manifestation, each
following its own R4/R3 links, and one per Item following its R7; an entity no
document reaches is reported as `:dropped`. When a record has several
Manifestations, its record-level canonical fields (agents, notes, images,
identifiers) cannot be attributed to one of them and are reported, not guessed.

## Field patterns (each AAT term verified against an example)

| Our predicate | Linked Art pattern | Getty AAT |
|---------------|--------------------|-----------|
| title (`R33`) | `identified_by` → `Name` | `aat:300404670` Primary Name |
| ARK / `:canon/identifier` | `identified_by` → `Identifier` | `aat:300435704` System-Assigned Number |
| creator (`:canon/agent`, role `:creator` or unstated) | `created_by` → `Creation` → `carried_out_by` → `Person` | — |
| publisher (`:canon/agent`, role `:publisher`) | `used_for` → `Activity` (Publishing) → `carried_out_by` → `Group` | `aat:300054686` Publishing |
| note (`:canon/note`) | `referred_to_by` → `LinguisticObject` | `aat:300435416` Description / `aat:300418049` Brief Text |
| digital (`:canon/digital-object`) | `representation` → `VisualItem` → `digitally_shown_by` → `DigitalObject` | `aat:300215302` Digital Image |

`@context`: `https://linked.art/ns/v1/linked-art.json`.

## Sources (fetched & read, 2026-06)

The reference bundle (Linked Art `@context` + curated model example JSON files)
was fetched from the official site and read directly. The decisive examples:

- **a copy → carries → text** (`HumanMadeObject` carries a `LinguisticObject`) —
  `https://linked.art/example/object/yul_10801219/1` (an F5 Item, not an edition)
- **authorship and publication of a text** (`created_by` Creation, `used_for`
  Publishing Activity carried out by a Group) —
  `https://linked.art/example/text/koot_nightwatch/2`
- **text → part_of → conceptualization** (`LinguisticObject` part_of a
  `PropositionalObject`) — `https://linked.art/example/text/koot_nightwatch/1`
- **creation of a text** (`created_by` → `Creation` → `Person`) —
  `https://linked.art/example/text/koot_nightwatch/2`
- **Name / Identifier / Statement patterns** — the Linked Art model `base` page.
- **digital image / IIIF** (`representation` → `VisualItem` → `digitally_shown_by`
  → `DigitalObject`; `subject_of` for a IIIF manifest) — the Linked Art model
  `digital` page.

## Identified agents (the first brick of agent reconciliation)

When the source carries an authority identifier for the creator, the agent is
minted as a **first-class `:crm/E21_Person` entity** whose `:iri` is that
identifier, and the Linked Art `created_by` Person carries it as `id` — an
*identified* creator, not a bare label:

```json
"created_by": { "type": "Creation", "carried_out_by": [
  { "type": "Person", "id": "https://isni.org/isni/0000000122762442",
    "_label": "Flaubert, Gustave" } ] }
```

Because the ISNI is a *determinate* identifier, the agent identity is certified
(D7) — exactly the signal real agent reconciliation needs. V1 mints it for
INTERMARC's 100 `$1` ISNI (`palomar.plugins.intermarc.wemi/with-identified-agent`);
the string-only canonical floor (ADR 0003) cannot hold an authority-linked agent,
so the floor spokes still emit a label-only creator. Cross-record agent
de-duplication is ADR 0018 proper (deliberately not done here, though two records
with the same ISNI already mint the same agent id by content).

## Honest scope (V1)

- A Linked Art-**profile** serialisation, now validated with the **real**
  draft-2020-12 validator (`com.networknt/json-schema-validator`, a test-only dep
  from Maven Central) against the official `$ref`-resolved schema set, in
  `palomar.eval.linked-art-conformance-test`. Honest calibration: that schema is
  *stricter than real Linked Art* (it models `carries`/`part_of` as id-only refs and
  is `additionalProperties:false`), so even Getty's own Mona Lisa example fails it.
  Manifestation documents are validated against `text.json`, Item documents
  against `object.json`. The MARC21 sample validates with **zero errors**. A
  richer record deviates in exactly two known ways: a Person or Group with no
  authority id lacks the `id` a schema reference requires (the floor holds no
  authority for it, and minting one would fabricate an identity), and `part_of` →
  `PropositionalObject` follows Linked Art's own published example although the
  schema only allows a LinguisticObject or VisualItem there. The remaining
  institutional piece (a **Louvre-specific** profile / acceptance criteria) is the
  only part that still needs a partnership.
- `created_by` is emitted whenever the record names a creator (a `:canon/agent`
  with role `:creator`, or with no stated role). A publisher goes to the
  Publishing activity; a contributor is reported as export loss. When the creator
  carries an authority id (INTERMARC's ISNI) the `Person` carries it as `id` (see
  *Identified agents* above); otherwise it is label-only.
- Dates, relations and native predicates are not expressed; they are reported as
  export-edge loss (ADR 0015).
- A `subject_of` link to the IIIF *manifest* (vs `representation` of the image) is
  not yet emitted — a later refinement.

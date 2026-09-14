# INTERMARC-NG examples — the entity-relation spoke (ADR 0019)

INTERMARC-NG is the BnF's NOEMI / Transition-bibliographique production format: not
a flat documentary record but a **graph of entity-records** (Œuvre / Expression /
Manifestation / Item / agents…), linked by the fundamental `7xx` relations whose
`$3` carries the identifier of the entity in relation. `palomar.plugins.intermarc-ng`
imports it **graph → graph** straight onto the LRMoo view (no string floor, no
WEMI-derivation inference — the WEMI distinctions are given).

## `fleurs-du-mal-oemi.xml` — SYNTHETIC, spec-faithful

Four entity-records of *Les Fleurs du mal* — a Work, its Expression, its
Manifestation, and the **Person** Baudelaire — linked by **740 Matérialise**
(Manifestation → Expression), **750 Réalise** (Expression → Œuvre) and **700 A pour
créateur** (Work → Baudelaire). On import this becomes
`F1_Work —R3→ F2_Expression ←R4— F3_Manifestation` with an identified
`:crm/E21_Person` creator (ISNI from `100 $1`) — the exact shape `derive-wemi`
synthesises, but *read* rather than inferred.

| NG (kitcat manual)                | LRMoo |
|-----------------------------------|-------|
| record entity type                | `F1_Work … F5_Item` |
| `150/140/245 $a` access points    | `R33_has_string` (label) |
| `730 Exemplifie $3`               | `R7_exemplifies` (F5→F3) |
| `740 Matérialise $3`              | `R4_embodies` (F3→F2) |
| `750 Réalise $3`                  | `R3_is_realised_in` (F1→F2, flipped) |
| Person `100 $a/$m/$1`             | `:crm/E21_Person` (name + ISNI `:iri`) |
| `700 A pour créateur $3`          | `:canon/agent` (→ Linked Art `created_by`) |
| Concept `166 $a` / Place `170 $a` | `:crm/E55_Type` / `:crm/E53_Place` (subject entity) |
| `92x` vedette matière `$3`        | `:crm/P129_is_about` (Work → subject) |

The fixture's fourth/fifth entity-records are Baudelaire (Person) and the RAMEAU
concept *Poésie française* (Concept), linked from the Work by `700` and `926`. The
RDF/CRM export now carries the **whole** entity graph — agent + subject, not just
the WEMI chain.

### Why synthetic, and what is real

The **format** (field codes, OEMI relations, access points) is the public **kitcat
INTERMARC-NG manual** (the `bnfintermarcng` drop, `kitcat_interMARC_NG/`). But public
BnF SRU / Z39.50 **do not yet serve native NG entity exports** — they still diffuse
*classic* INTERMARC/UNIMARC/Dublin Core (the native NG lives in NOEMI, behind manual
transfer). So there are **no real native NG records** to test against yet.

This fixture is therefore spec-faithful but hand-built. What is real: the field/
relation **codes** are the manual's, and the ARKs `cb44496975d` (Manifestation) and
`cb11947965` (Expression — the actual `145 $3` of the BnF Baudelaire record) are
real catalogue identifiers. The one synthetic **convention** is the record-level
entity-type encoding (here the `type` attribute), to be reconciled with a real
native export when one is available — at which point only this fixture changes, not
the importer's logic.

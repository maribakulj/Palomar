# 0053 — MARC21 for museum objects is a profile of its own

- Status: Accepted
- Date: 2026-10-28
- Builds on: [ADR 0052](./0052-museum-objects-from-lido.md), [ADR 0050](./0050-marc21-for-archives.md)

## Context

A museum may want its objects in a library catalogue. MARC21 provides for
that: Leader/06 `k` for a two-dimensional nonprojectable graphic (a painting,
a drawing, a print) and `r` for a three-dimensional artefact. It also has
fields for what a museum knows: production (`264` second indicator 0),
materials (`340`), acquisition (`541`), provenance (`561`) and location
(`852`). The bibliographic MARC21 target refuses an object (ADR 0049),
because its fields would say "published".

## Decision

`:marc21-object` accepts museum objects only and writes them from their
LIDO values:
- **Leader/06** is `k` when a work type or classification names a
  two-dimensional graphic (painting, drawing, print, photograph, in English,
  German or French, "Fotografie" included: amended, post-audit 4, F7); `r`
  when one names a three-dimensional artefact (a sculpture, a vessel…) or a
  natural object (a fossil, a mineral). When no type
  says which, `r` is assumed and that is reported (amended, post-audit 5,
  G4). A manuscript (a book of hours included) is `t`, a printed book `a`:
  language material, as MARC types it, whatever a museum keeps it as;
  manuscript and printed music are `d` and `c`, a manuscript and a printed
  map `f` and `e`. A graphic stays `k` whatever book it illustrates or
  shows (a woodcut book illustration, a bookplate, a photograph of a
  manuscript), and a book's binding, cover or furniture is `r`. Words are
  matched whole, a German compound in -buch or -handschrift included
  (amended, post-audit 6, H3 and its review). The `264` of a printed book
  stays the production's (`#0`), not a publication: a limit of this
  profile.
  Leader/07 is `m`.
- **`001`** is the record's stable id. The inventory number goes to `099 $a`.
- **`003`** names whose `001` it is: the `lido:source` of the `lidoRecID`
  when it is an ISIL, or the ISIL a ZDB organisation URI ends with. Any other
  source is no MARC organization code: no `003` is written, and the record is
  reported as indistinguishable from another institution's with the same
  local id (amended, post-audit 5, G3).
- **The production's actors** go to `100`/`110` (then `700`/`710`), with `$d`
  life dates, `$e` role and `$0` authority URI.
- **Titles:** the first is `245`, the others `246`.
- **The production** is `264` #0: every place in `$a`, the date as given in
  `$c`.
- **Measurements and materials** as displayed are `300 $c` and `340 $a`.
- **Notes:**
  - inscriptions `500`, description `520`;
  - work rights `540` (`$u` the licence);
  - acquisitions `541` and other events (provenance) `561`, from their
    descriptions.
- **Concepts:**
  - subjects `650` #7, work types then classifications `655` #7;
  - each with `$0` its URI and `$2` its vocabulary (gnd, iconclass, aat,
    lcgft for an LC genre or form);
  - an LCSH heading (`id.loc.gov/authorities/subjects`) is `#0`, which needs
    no `$2`; any other LC URI (a name, say) is no subject heading and is `#4`
    (amended, F4 review);
  - a concept of a vocabulary with no MARC source code, or with no URI, is
    `#4` (source not specified), never `#7` without its `$2` (amended,
    post-audit 4, F4).
- **Location and links:**
  - the repository `852 $a`;
  - the images `856` 4# and the record's web page `856` 42.
- **Losses.** Anything else is reported, including the place, the actors
  and the date of an event other than the production, and the language of
  each title, which MARC has no subfield for (amended, post-audit 4, F5
  review and F7).

## Consequences

- A LIDO export becomes MARC21 records a catalogue can index, without a
  publication statement it never made.
- The richer event structure (who acquired from whom, when) stays in Linked
  Art and CIDOC CRM. MARC21 keeps the descriptions as text.

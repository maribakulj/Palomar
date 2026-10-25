# 0036 — Publication and production events

- Status: Accepted
- Date: 2026-10-25
- Extends: [ADR 0009](./0009-mapping-schema.md) (the mapping DSL: `:mapping/where`)

## Context

A MARC 260 says one thing: *this edition was published at Paris, by
Gallimard, in 1928*. The floor kept three unrelated strings (`:canon/date`, a
`:canon/agent` with role `:publisher`, and no place at all), so no RDF or
Linked Art output could say that an event happened, where, when and by whom.
The fields that state it are also qualified by their indicators (MARC 264,
UNIMARC 214: second indicator 0 production, 1 publication, 2 distribution, 3
manufacture, 4 copyright), which a mapping could not read: every 264 $c became
a date, and no 264 $b became a publisher.

## Decision

1. **`:mapping/where`**. A mapping may restrict itself to source values read
   in a fragment whose locator has, at each named key, one of the listed
   values (`{:ind2 #{"1"}}`). The MARC21 spoke now maps 264 $b to a publisher
   when the second indicator is 1.
2. **`palomar.plugins.events`**, an `:infer` step after the projection, for
   the spokes that keep their field fragments (MARC21 260/264, UNIMARC
   210/214, INTERMARC 260; `events/field-events` is the table). One event per
   field occurrence stating a publication or a production:
   - `F30_Manifestation_Creation —R24_created→` the record's Manifestation
     (LRMoo; F30 is both an E12 Production and an E65 Creation);
   - `—P4_has_time-span→ E52 —P82_at_some_time_within→` the date as
     transcribed. The source gives no machine date, so none is invented;
   - `—P7_took_place_at→ E53_Place` and `—P14_carried_out_by→ E39_Actor`, each
     named (through E41, ADR 0022). They are keyed by the matching form of the
     name without its ISBD punctuation, so one name is one node across
     records.

   A distribution, manufacture or copyright statement is not a Manifestation
   Creation and makes no event. The claims are `:proposed` (ADR 0005) and keep
   the evidence of their subfield (ADR 0031). The spokes' own mappings are
   unchanged; the events are a view added to the pivot.
3. **Exports.**
   - The RDF views write the event, checked against the CRM and LRMoo files
     (ADR 0026).
   - The pure-CRM view types F30 as E12 *and* E65 (P94's domain) and writes
     R24 as P94; the class generalisation is reported as loss.
   - Linked Art writes each event as a Publishing `used_for` Activity: its
     Groups, its Place (by IRI, as the schema requires) and a `timespan` named
     by the date. With events, this replaces the bare publishers' activity.

## Consequences

- An institution's RDF says who published what, where and when, and the
  places and publishers of a corpus are shared nodes it can align (ADR 0032).
- Each MARC record gains about seven proposed claims; a curation run counts
  them.
- MODS has no events yet: its importer reads neither `originInfo/place` nor
  `@eventType`. Dublin Core states no event. Both are later work, and either
  can add a row to `field-events` or its own reader.
- The date stays a string. Normalising it to bounds (`P82a`/`P82b`, an EDTF
  reading) is a separate, lossy step that would need its own loss accounting.

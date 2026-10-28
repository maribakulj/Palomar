# 0049 — A target says what it can describe

- Status: Accepted
- Date: 2026-10-28
- Amends: [ADR 0015](./0015-loss-model.md) (export loss), [ADR 0045](./0045-ric-o-projection.md)

## Context

Every source spoke writes its values onto the canonical floor (`:canon/*`),
and every record exporter reads them back with its own table. The floor is
deliberately generic: an identifier, a date, an agent. The third post-audit
review showed what that genericity costs once archives and digital
presentations join the corpus:
- An EAD unit converted to MARC21 had its *cote* in `010` (an LCCN) and the
  dates of its documents in `260 $c` (a publication date). Its loss report was
  correct, but the record said something false.
- An EAC-CPF authority wrote nothing to the floor, so the MARC21 exporter
  produced an empty record, without error.
- A IIIF manifest became a Linked Art `LinguisticObject`, a text, even when it
  presents a sculpture.

No single plugin was wrong: the meaning was lost where a source's fields met
a target's table through the floor.

## Decision

1. **A record's resource type.** `convert/resource-type` says what kind of
   resource a record describes: an archival unit (EAD), an authority (EAC-CPF),
   a digital presentation (IIIF), or otherwise a bibliographic description.
2. **A record target declares the types it describes** (`:accepts` in
   `convert/exporters`):
   - MARC21 bibliographic, MODS and Linked Art: bibliographic records and
     digital presentations.
   - DC and IIIF: also archival units. DC's elements are generic (`dc:date`,
     `dc:identifier`), so they say no more than the source.
   - RDF, PROV-O and EDN targets write any resource's graph.
3. **A conversion to a target that cannot describe its records is refused.**
   The error names the resource type and the targets that can describe it.
   This replaces a record with false meanings, or an empty one. Profiles that
   describe archives and authorities are targets of their own (ADR 0050).
4. **A IIIF manifest is a `DigitalObject` in Linked Art.** It conforms to the
   IIIF Presentation API and is retrieved at its own URL. Its labels, summary
   and images are written; what it presents is not asserted, and its
   metadata's agents (the presented thing's) are reported as loss.

## Consequences

- Converting a finding aid or an authority file to MARC21 fails with an
  explanation, until a profile that can describe it is chosen.
- The fidelity guarantee is stated per describable pair.
- The LRMoo projection of a IIIF manifest is unchanged in the RDF view. A
  museum object model (LIDO, ADR to come) is the place to link a presentation
  to the object it presents.

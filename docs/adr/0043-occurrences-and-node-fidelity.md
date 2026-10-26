# 0043 — Occurrences are kept, deletions reported, values checked on their node

- Status: Accepted
- Date: 2026-10-26
- Amends: [ADR 0033](./0033-loss-taxonomy-and-relation-fidelity.md) (the
  fidelity audit), [ADR 0036](./0036-publication-events.md) and
  [ADR 0037](./0037-oai-pmh-and-dcmi-terms.md)

## Context

The post-audit review found that some repeated data was represented by its
distinct values rather than by its occurrences, with no report of the rest:
- an event field kept only the first `$a` and `$b`;
- an image painted on several IIIF canvases was kept once, with its first
  canvas, and nothing said so.

An OAI-PMH response's deleted records were listed by the adapter and dropped
by `convert`, so a synchroniser could not remove them. One digest stood for
both the response and the metadata document the XPaths point into. Finally,
the fidelity audit counted a value as preserved wherever its text appeared in
an RDF output, even on the wrong resource.

## Decision

1. **Occurrences.**
   - An event carries every place and agent of its field, and one time-span
     with every date.
   - Each repeated painting of an IIIF image is a `:dropped` loss naming its
     canvas and region. The floor still carries the image once.
2. **Deletions.** `convert` returns `:deleted`: the identifiers an OAI-PMH
   response marks deleted. The CLI prints them.
3. **Three levels of OAI provenance:**
   - the response (`[:provenance :source :sha256]`);
   - the harvested record (`:oai`, its header);
   - the metadata document (`:oai :metadata-sha256`), which every value's
     XPath points into.
4. **Node-by-node fidelity.** For the N-Triples family, a value is present
   only on the node of its own subject, or on that node's appellation. The
   strings the RDF view derives (WEMI titles, names) are checked too.
5. **The audit observes the export with its proposals.** It judges the
   exporter; withholding proposals is the export's policy (ADR 0042).

## Consequences

- "No silent loss" now means no value missing from its own node in RDF, and
  no repeated occurrence dropped without a report. It still means it on the
  fixture corpus only.
- An OAI harvest can drive an incremental store: upserts from `:records`,
  deletions from `:deleted`.

# 0025 — The provenance record keeps the history; no invalid IRI is ever written

- Status: Accepted
- Date: 2026-10-25
- Amends: [ADR 0023](./0023-justifications-and-status-at-export.md) §1 (which targets the status policy applies to)

## Context

The architecture review of 2026-10-25 found two silent failures:

- ADR 0023 has `convert/export-records` drop out-of-force assertions for every
  target. The `:prov` target, whose purpose is to record provenance and status,
  therefore lost every rejected, superseded or retracted claim, while
  `prov/export-losses` declared no loss. A research application could not read
  the history of a curated record from any output.
- The RDF writers wrote `<iri>` without checking it. An IRI supplied by a caller
  (an authority id passed to `reconcile/apply-agent-links`, a plugin's `:iri`) or
  built from a record id containing a space (`--record-id` from a file name)
  produced an unparseable document.

## Decision

1. A target may declare `:history? true` in `convert/exporters`
   (`convert/keeps-history?`). Such a target states no fact: it records each
   assertion with its status. `export-records` hands it every assertion; the
   status policy of ADR 0023 still applies to every other target. `:prov` is
   the one history target. The fidelity audit checks a history target on its
   out-of-force assertions too.
2. Reification is what keeps this safe: a PROV `rdf:Statement` describes a
   claim without asserting it, and carries its `palomar:status`.
3. Every RDF writer (`render-ntriples`, `render-turtle`, `render-jsonld`, hence
   CRM and PROV) checks each IRI with `ntriples/iri-problem` and throws on an
   invalid one. `export/entity-iri` percent-encodes a keyword id's characters
   that an IRI may not hold; crm-import gives a blank-node entity a minted id
   instead of a fake `_:` IRI; `apply-agent-links` refuses an authority id that
   is not an IRI (`:invalid-iri`).

## Alternatives considered

- *Report the out-of-force assertions as PROV losses*: honest, but leaves no
  output that holds the history, which is what the target is for.
- *Escape invalid IRIs in the writer*: silently changes an identifier the caller
  gave; refusing it is the only way the caller learns of it.

## Consequences

- A curated record's history (what was rejected or superseded) is readable from
  the PROV output. The ordinary RDF, Linked Art and format targets are unchanged.
- An export with an invalid caller IRI now fails loudly instead of writing a
  corrupt document.

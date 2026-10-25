# 0038 — Authority files as reconciliation pools

- Status: Accepted
- Date: 2026-10-25
- Extends: [ADR 0018](./0018-entity-resolution-at-scale.md) and
  [ADR 0032](./0032-graded-alignments.md)

## Context

The fuzzy tier of reconciliation (`reconcile/propose-agent-links`) matches a
record's names against a pool of `{:id :label :variants}`. Nothing filled the
pool from the files institutions actually publish their authorities in: the
Library of Congress serves MADS/RDF, and thesauri are SKOS, both most often as
RDF/XML. Palomar reads N-Triples only.

## Decision

1. **`palomar.rdfxml`**, an RDF/XML reader for the grammar's common
   productions:
   - typed node elements, `rdf:about` against `xml:base`, `rdf:nodeID`;
   - property attributes;
   - `rdf:resource`, nested nodes, and literals with `xml:lang` and
     `rdf:datatype`;
   - `parseType` `Resource`, `Collection` and `Literal`.

   `rdf:ID` and `rdf:li` throw rather than guess. It parses through
   `palomar.xml`, so a DTD is refused. It reads the vendored MADS record and
   the whole CIDOC-CRM RDFS.
2. **`palomar.plugins.authorities`** turns an authority graph (RDF/XML or
   N-Triples) into a pool.
   - An entry is a `madsrdf:Authority`, in either MADS namespace, or a
     `skos:Concept`.
   - Its label is the authoritative or preferred label, preferring a requested
     language, then none.
   - Its variants are the other labels, alternative and hidden labels, and the
     variant labels of the nodes it `hasVariant` or `hasEarlierEstablishedForm`.
   - A MADS `Variant` that is no Authority (a deleted heading) is no entry. It
     is listed as `:deprecated` with the headings that replaced it, so a
     retired name is followed, not matched.
3. **`palomar reconcile --authorities <file>`** matches a source's agents
   against such a file and prints the proposals, exact or close (ADR 0032).

## Consequences

- An institution can reconcile against id.loc.gov or its own SKOS thesaurus
  without a SPARQL endpoint or a database.
- Turtle is not read. The MADS subject fixture (`loc_mads_subject.ttl`) stays
  unread until a Turtle reader is justified. Converting it to N-Triples
  (e.g. with Jena `riot`) is the workaround.
- The pool is a list scanned per name: right for a vocabulary of thousands,
  not for the full LCNAF, which needs an index. That is outside this step.

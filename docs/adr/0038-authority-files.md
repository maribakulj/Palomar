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

## Amendment (lot M3, 2026-11-09): authority profiles, and INTERMARC authority records

A pool entry is a profile of the agent, not only its names, so that a matcher
can use what the authority says against a mention (ADR 0018 amendment, dates
as contrary evidence).

1. **`:dates`**, a life-dates profile (`palomar.agent-dates`: birth, death,
   activity as intervals of years that keep their uncertainty), present only
   when the source states dates:
   - EAC-CPF `existDates`: a `dateRange`'s `fromDate` is the birth (a body's
     start), its `toDate` the death (its end), `@standardDate` read before the
     text, yet kept approximate when the text says so (`ca. 1802`), and
     widened to `@notBefore` / `@notAfter`; a single `date` is read as a span;
     a `dateSet`'s members are alternatives, each role the interval holding
     them all, and dropped when a member does not state it — never narrower
     than the source. `existDates` leaves `:unread` only when a date of it is
     read; otherwise its text is kept under `:dates :text` and it stays listed
     as unread;
   - MADS/RDF `madsrdf:birthDate` / `deathDate`, on the authority or on the
     real-world object it `identifiesRWO`, as a literal or a node's
     `rdfs:label`;
   - SKOS states none;
   - INTERMARC `100 $d`.
2. **`:entity-type`** stays the kind key ("person", "corporateBody",
   "family"): MADS's name class, EAC-CPF's `entityType`, the INTERMARC
   authority leader's position 9 (`p`, a person). A kind the source does not
   state is left out. It is the key `align-agents` already reads; no second
   key is added.
3. **BnF INTERMARC authority records** (`read-pool :intermarc`, sniffed by a
   MARCXChange record of format INTERMARC and type Authority), read through
   the INTERMARC importer: `:id` the `003` ARK, `:record-number` the `001`
   without `FRBNF` and its check character (the form a bibliographic `$3`
   cites), label the `100 $a, $m`, variants the `400`s, `:dates` the `100 $d`,
   `:isni` the `031 $a` whose `$2` says ISNI (a cancelled `$z` is not read,
   a VIAF number is no ISNI). The tags not read are listed under `:unread`
   (among them the `008`, which also holds dates for some records).

The person-reconciliation eval reads its pool with this reader; a test checks
it gives the pool M2's test-side reader gave.

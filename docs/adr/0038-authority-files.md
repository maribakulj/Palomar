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
   `:isni` every `031 $a` that passes the ISNI check (a cancelled `$z` is
   not read). Corrected by lot N5's review (ADR 0060): this read the `$a`
   only when its `$2` said ISNI, and took a `$2` "VIAF" for a VIAF number.
   `$2` is where the ISNI came from; it is kept as `:isni-sources`. The tags not read are listed under `:unread`
   (among them the `008`, which also holds dates for some records).

The person-reconciliation eval reads its pool with this reader; a test checks
it gives the pool M2's test-side reader gave.

## Amendment (lot M6, 2026-11-09): authority snapshots — a dump indexed once, read offline

The pool was a file read whole and scanned per name: right for a vocabulary,
not for a national authority file of millions of records, and a dump read
during a conversion would make its result depend on when it ran. An
authority file of that size is now a **snapshot**: a dump prepared once into
a local index, then read offline and deterministically.

1. **The dump: lobid GND JSON lines** (`palomar.plugins.gnd`). The GND is
   CC0. lobid's search API serves it as JSON-LD, one compacted record per
   line (`https://lobid.org/gnd/search?q=…&format=jsonl`, every hit of the
   query). JSON lines is read because a line is a whole record: a file of
   any size is read one line at a time, in bounded memory. **The DNB's bulk
   files are not read**: its JSON-LD dump (`authorities-gnd-person_lds.jsonld.gz`)
   is expanded JSON-LD in arrays, with the name parts as blank nodes, and
   its RDF dumps are not sorted by subject, so an entry could be built only
   once the whole graph, or an external sort of it, is at hand; an index
   build names an expanded JSON-LD array when it refuses one. Turtle is still
   not read. An agent record (`Person` or `DifferentiatedPerson`,
   `CorporateBody`, `Family`) is one entry; an `UndifferentiatedPerson` (a
   Tn record, one name for several people) is none and is counted, since a
   match to it would identify no one: its GND URI as `:id`, `preferredName` as label,
   `variantName`s as variants, `:dates` from `dateOfBirth`, `dateOfDeath`,
   `periodOfActivity` (else `dateOfBirthAndDeath`), `:entity-type`, and
   `:same-as`, the sorted IRIs of its `sameAs` links to VIAF, ISNI, Wikidata,
   the Library of Congress and the BnF. Those links are **data, never a
   feature**: the matcher compares names and dates only (a test shows the
   proposals are the same without them). A record of another kind is skipped
   and counted.
2. **The index** (`palomar.authority-index`), built by an explicit command,
   `palomar authorities index <dump> --out <index> [--built YYYY-MM-DD]`
   (API `build!`), never during a conversion or a reconciliation; a dump
   given to `reconcile --authorities` is refused with the command to index
   it. The file is UTF-8 EDN, one form per line: a header (format version,
   blocking scheme, build date, the dump's file name, SHA-256 and size, the
   entry and key counts, what was skipped), then the `[:entry id {…}]` lines
   and the `[:key k [id…]]` lines, in sorted order, map keys sorted. The
   lines are sorted by an external merge sort: chunks of `:chunk-lines`
   sorted in memory, then merged at most `:fan-in` (64) runs at a time, in
   as many passes as needed. Memory holds a chunk, the fan-in readers and the
   largest posting; disk holds the runs. The file is written beside its path
   and moved over it when complete. Refused, with nothing written: a dump
   from which no entry is read, one more than half of whose lines are no
   JSON object (the counts are said), and one holding an id twice. **The
   body is a function of the records**: byte-identical whatever their
   order, line endings, byte-order mark, gzip, chunking, fan-in or default
   locale. The header names the dump file as given and the build date; the
   API takes the date and reads no clock, the CLI records today (UTC) unless
   `--built` is given. Same file and same date, byte-identical index.
   Names are case-folded in the root locale (`text/norm`, also for every
   other key it makes: under a Turkish default locale `I` folded to a
   dotless `ı`), and the blocking scheme is versioned (`/2`): an index
   built with another scheme is refused by the reader. `reconcile
   --authorities` refuses a dump, known by a compressed name or by JSON
   after a byte-order mark and blank lines in its first 4096 characters.
3. **Blocking keys** chosen so that a lookup returns every entry the matcher
   could score: `t:` + each normalised token of a name, and `s:` + its
   surname (four letters or more) and each string made by deleting one of its
   letters. The matcher compares a candidate only when its name level is
   above `:none`, which needs a shared token or two surnames of four letters
   or more one edit apart; two such surnames always share a one-letter
   deletion. A key on the surname's first letters, the usual choice, would
   miss a typo in them and keep every namesake of a common initial.
   `reconcile/agent-candidates` takes the index's lookup as `:block` and
   compares a mention with the entries it returns, in index order, the
   surname frequencies and the pool fingerprint still being the whole
   pool's; a name-only threshold of 0 or less admits every entry and scans
   the pool. The proposals, rejections and abstentions are those of the full
   scan of the same entries: tested on the extract with perturbed names and a
   model in which every level weighs, on the M2 mentions, and by a property
   test of the keys against `agent-match/name-level`.
4. **No network** on these paths, by design and by test: the GND reader,
   the index and the matcher name no HTTP client, socket or URL; files are
   opened as files, never as strings `clojure.java.io` would take for URLs
   (every CLI verb now opens its input that way, and the spokes their `:file`
   sources; a test runs them on a file whose path reads as a URL); and a test replaces the JVM's
   default `ProxySelector`, which every `http`/`https`/`ftp` URL connection,
   the default `java.net.http` client and a plain socket consult, with one
   that records and refuses every request, then builds an index, reconciles
   against it and converts: none is made. A raw UDP datagram or a DNS lookup
   would not be seen by that trap; nothing on these paths makes one.
5. **The run manifest names the index** (ADR 0030 amendment M6).

Consequences: the index is loaded whole into memory by `read-index` (entries
and postings): a lookup is by key, not a scan, but a dump of millions of
records needs the memory for them; reading the index lazily from disk is not
done. The blocking keys of a common token (`t:victor`) hold many entries.
Only the GND reader feeds an index; another dump format needs its reader.

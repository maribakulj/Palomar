# 0034 — A stable library API and the pivot as an EDN file

- Status: Accepted
- Date: 2026-10-25
- Amends: [ADR 0027](./0027-assertion-identity-and-annotations.md) §2 (the
  spelling of an assertion id)

## Context

Palomar is used as a CLI, but its users are as often programs: an
institution's pipeline, a curation tool, a triple store loader. They had no
stated surface — any public var of any namespace could change — and no way to
stop at the pivot: every run re-imported the source, so a curation could not
be saved and resumed, nor the pivot handed to another tool.

## Decision

1. **`palomar.api`** is the stable library surface: the formats, `pivot`,
   `export`, `convert`, `convert-stream`, the curation policies, `curate`,
   `supersede`, `history`, `validate`, `valid-record?`, `explain-record`,
   `audit`, `loss-report`, `manifest`, and the IR functions. Each delegates to
   the namespace that does the work. A contract test pins the set of names and
   the shape of the results; adding to it is a minor change, removing or
   renaming a breaking one. Other namespaces carry no such promise.
2. **The EDN IR** (`palomar.ir`). A document is one EDN form per line, each an
   envelope `{:palomar/ir 1 :record <record>}`: streamable, concatenable, and
   versioned per record so a reader refuses what it cannot read. Writing is
   deterministic (maps and sets in a stable order), so a document can be
   diffed and fingerprinted (ADR 0030). Reading validates each record against
   `model/Record`. `--to edn` writes it (a history target, ADR 0025: every
   claim, every annotation, nothing lost); `--from edn` reads it back as the
   pivot, skipping import and projection.
3. **The published schema** is `model/Record`'s malli form with its predicates
   named, `docs/ir/record-schema.edn`, printed by `palomar ir-schema`; a
   contract test keeps the file equal to the code.
4. **Everything in a record must read back.** The writer refuses a record
   that fails the schema or holds a keyword EDN cannot read, rather than write
   a file no reader can load. This found two producers to fix:
   - an assertion id `:asr/<16 hex>` (ADR 0027) starts with a digit in most
     cases, which no EDN reader reads; ids are now `:asr/h<16 hex>` (and PROV
     statement IRIs `urn:palomar:stmt:h…`). Nothing has been released with the
     old spelling;
   - the INTERMARC-NG and CRM importers stamped `:pass :import`, which is not a
     phase, so their records failed the schema; they stamp `:ingest`.
   The CLI's record ids (`--record-id`, the DC file stem) start with a letter.

A tagged literal for unreadable keywords was considered and set aside: every
non-Clojure EDN reader would need custom code for the very ids annotations
join on, and it would hide the minting bugs the refusal surfaced.

## Consequences

- A pipeline can import once, curate (`apply-repairs --to edn`), and export
  to as many targets as it needs; the contract test checks that resuming from
  the EDN writes the same output as converting directly.
- A record that holds values outside EDN (a Java object in `:source`) cannot
  be written; the schema allows `:any` there, so such a record is refused at
  write time with the offending value named.
- Loss reports of a resumed conversion carry the losses recorded on the
  records (projection) but not the import-edge losses of the original run,
  which are not part of a record.

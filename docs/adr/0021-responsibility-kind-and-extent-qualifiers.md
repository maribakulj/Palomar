# 0021 — Responsibility, note kind and extent as qualifier coords; constant qualifiers

- Status: Accepted
- Date: 2026-10-25
- Partially supersedes: [ADR 0009](./0009-mapping-schema.md) §Qualifier (adds a second qualifier form)
- Extends: [ADR 0003](./0003-core-vs-canonical-vocabulary.md) (canonical qualifier coords), [ADR 0011](./0011-fragments-for-qualified-values.md) (fragments minted at normalize)

## Context

The interoperability audit (2026-10-25, items S02–S04, S14, S21/S22) found that
the canonical floor collapsed distinctions the targets need:

- `dc:creator`, `dc:contributor`, `dc:publisher`, MARC 100/110/111/700/710 and
  MODS `originInfo/publisher` all became one `:canon/agent`. The Work key took
  the first agent, so an edition credited only to its publisher got a Work "by"
  the publisher, and Linked Art listed publishers in `created_by`.
- MODS `abstract`/`note`, MARC 500/505/511/520 and UNIMARC 327/330 all became one
  `:canon/note`, so the MARC and MODS exports could not write a note back to its
  field.
- A IIIF image lost its Canvas extent, so the IIIF export could not build a
  valid Canvas (a Canvas MUST have one).

These are qualifications of a value, not new documentary statements, so by
ADR 0003/0011 they belong on fragments, not in new top-level predicates. But
ADR 0009's qualifier reads the coord from a source attribute (`xml:lang`): a
responsibility or a note kind is not an attribute — it is what the *rule* knows
(`dc:publisher` is always a publisher).

## Decision

1. **Four new qualifier coords** join `:canon/lang` (`canonical/qualifier-coords`):
   `:canon/role` on `:canon/agent` (`:creator`, `:contributor`, `:publisher`),
   `:canon/note-kind` on `:canon/note` (`:abstract`, `:contents`, `:credits`),
   `:canon/width` and `:canon/height` on `:canon/digital-object` (the Canvas
   extent, positive integers). The documentary vocabulary itself is unchanged.
2. **A constant qualifier form**, `:mapping/qualifier {:value v :as coord}`, next
   to `{:from attr :as coord}`. Having no source attribute, it is applied at
   normalize: the rule mints one fragment per record-level occurrence (locator
   `[native-predicate idx]`, ADR 0012), moves the value onto it and adds the coord.
   The result has exactly the shape of an attribute-qualified value, so every
   reader handles both. A constant qualifier may sit on a combine rule.
3. The runtime merges a new production kind, `:fragment`, deduplicated by id.
4. **Only a creator keys a Work** (or, when the source states no role, an agent
   of unstated role); a contributor or publisher never does
   (`palomar.plugins.agents`).

## Alternatives considered

- *New predicates* (`:canon/publisher`, `:canon/contributor`, `:canon/abstract`):
  grows the documentary vocabulary for what are qualifications, against ADR 0003's
  growth discipline, and every reader of `:canon/agent` would have to learn them.
- *A role inferred from provenance* (which mapping rule produced the agent):
  couples consumers to mapping ids; invisible in the data.
- *Minting the fragments in each importer*: the flat MARC importers would all
  have to change; the role is a mapping fact, so the mapping carries it.

## Consequences

- Role-aware outputs: DC writes `dc:contributor`/`dc:publisher`; MARC writes
  the first creator as 100, others as 700, the publisher as 260 $b, notes as
  520/505/511/500; MODS writes `originInfo/publisher`, `<abstract>`,
  `<tableOfContents>`; IIIF writes the Canvas extent. Targets that cannot express
  a coord report it as export loss.
- A MARC record with only added entries (no 1XX) derives no Work. The LoC
  MARC→DC oracle comparison now reads 4/9 exact element types: the LoC
  stylesheet writes 7XX agents as `dc:creator`, Palomar as `dc:contributor`.
- MODS `name` carries no role until `role/roleTerm` is read.

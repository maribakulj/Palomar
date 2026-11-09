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

## Amendment (2026-11-09, lot M1): rights, attribution and provider

A IIIF Presentation 3.0 manifest states the rights it is under (`rights`, a
URI), what must be displayed with it (`requiredStatement`, a label and a
value) and who provides it (`provider`, Agents with an `id` and a `label`);
a Canvas, or the Image painted on it, may state rights of its own. All of it
was reported as import loss. It is now carried, at its level:

- **Manifest rights** are the documentary predicate `:canon/rights` (a URI or
  a text; ADR 0003 amendment of the same date), qualified by
  `:canon/rights-scope :digital-surrogate`: they are the rights of the
  digital reproduction, not of the work. A rights statement with no scope is
  the described resource's own — what DC (`dc:rights`, `dcterms:rights`,
  `dcterms:license`, as text or as an `rdf:resource` URI), MODS
  (`accessCondition`, use and reproduction, as text or `xlink:href`) and
  MARC21 (540 `$a` text, `$u` URI) give. A blank text is no value; an
  element stating nothing is reported.
- **Rights of one image** are coords on that image's `:canon/digital-object`
  fragment, each kept where the source put it: `:canon/image-rights` (the
  painted Image body's `rights`) and `:canon/canvas-rights` (its Canvas's).
  A Canvas's rights no carried image takes (it paints none, or only a repeat)
  are reported. These are always rights of the digital image.
- **The `requiredStatement`** is a `:canon/note` of a new note kind,
  `:attribution`: the note is the statement's value, with the value's
  language; its label is kept apart, `:canon/note-label`, with the label's
  own language, `:canon/note-label-lang`. No "label: value" string is ever
  joined, so none is ever split again. A statement with several languages
  keeps one string for each part and is reported `:coerced`; one with no label
  (invalid) is reported at import and never re-exported with an invented label.
- **A provider** is a `:canon/agent` of a new role, `:provider`, with its
  `id` in a new qualifier coord, `:canon/agent-id` (the identifier the source
  gives, not a reconciled authority: an Agent MUST have an `id`, so without it
  the IIIF export could not rebuild one). Its `homepage`, `logo` and `seeAlso`
  are not on the floor and are reported. A provider is not a creator: it keys
  no Work.

Writers:

- IIIF writes `rights` (a URI only, as the specification requires; a text or
  a second rights is reported, and rights with no scope are reported as the
  resource's own written as the manifest's), the `requiredStatement` from the
  first attribution with a label, `provider` from the providers with a URI id,
  each Canvas's `rights` on the Canvas and each Image's on its body, so a
  manifest round-trips.
- DC, MODS and MARC21 do **not** write a provider: each of their agent
  elements states a role in the work, and a provider of the digital surrogate
  has none; it is reported as dropped. (Written as `dc:publisher`, a 700 or a
  `<name>`, it came back as a publisher, a contributor or a role-less agent,
  that is a creator keying a Work.)
- Surrogate rights: MARC21 writes 540 with `$3 digital reproduction`
  (materials specified), and reads it back as the scope: a MARC field
  fragment's locator now carries its first `$3` as `:materials`, and
  `:mapping/where` takes `[:not #{…}]` beside a set, so the 540 `$a`/`$u` rules
  split on it (ADR 0009 `:mapping/where`, ADR 0036). MODS writes an
  `accessCondition` with `displayLabel="digital reproduction"`, read back as
  the scope; DC writes `dc:rights` and reports the scope as `:under-specified`
  (re-imported from DC, those rights are the resource's own).
- MARC and MODS write an attribution as a general note and report its kind
  and its label.
- Linked Art writes a text's own rights URI as a `Right` it is `subject_to`,
  classified by the URI, a rights text as a rights statement, and never a
  surrogate's rights on the text (reported). The official `digital.json`
  schema gives a `DigitalObject` (a IIIF manifest, ADR 0049) and an embedded
  `VisualItem` no `subject_to`, so there the rights are a `referred_to_by`
  statement classified aat:300435434 (Copyright/License Statement), the
  attribution a Credit Line (aat:300026687) named by its label; the provider
  has no place and is reported.

Fidelity: a IIIF attribution counts as written only when one
`requiredStatement` of the output holds its label and its value together; two
strings found anywhere do not, and a value-less loss (the summary collapse)
does not account for it: only a loss naming its value or label does. The
summary-collapse loss names each note it writes in the summary. A loss naming
a canonical field reaches a native value lifted to it
(`export-coverage/native-targets`) only when it names that very value
(trimmed); a value-less loss on the canonical field (a collapse, a role not
carried) accounts for no native.

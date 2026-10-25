# 0022 — Domain-faithful serialisation: no super-class downcast, titles through an appellation, editions are texts

- Status: Accepted
- Date: 2026-10-25
- Partially supersedes: [ADR 0019](./0019-conversion-directionality.md) §3 (the `E89 → F1`, `E24 → F5` recovery)

## Context

The interoperability audit (items S01, S05, S10, S16, S17, S32) found
serialisations that were structurally valid but said the wrong thing:

- ADR 0019 §3 treated `E89 → F1` and `E24 → F5` as 1:1 recoveries because each
  super-class has a single WEMI specialisation. Sub-classing does not invert: an
  `E89` may be a theory, an `E24` any physical made thing. External CRM was
  silently turned into Works and Items.
- `R33_has_string` has the domain `F12 Nomen` (LRMoo 1.0 and 1.1.1), but the RDF
  export wrote it directly on Works, Expressions and Manifestations. Subject
  concepts likewise carried `P190_has_symbolic_content`, whose domain is
  `E90 Symbolic Object`, directly.
- The Linked Art export rooted every record at the Manifestation as a
  `HumanMadeObject`. An F3 Manifestation is an edition; Linked Art's textual
  model separates the text (`LinguisticObject`) from the physical copies
  (`HumanMadeObject`) that carry it.

## Decision

1. **A node typed only by a CRM super-class is never downcast.** One candidate
   (`E89`, `E24`) is reported `:under-specified`, several (`E73`)
   `:ambiguity-collapsed`; no entity is minted either way. The pure-CRM export
   reports the same F1/F5 cases on its edge. Every triple crm-import does not
   recover is reported, per predicate.
2. **An entity's string is written through an appellation**: entity
   `P1_is_identified_by` → `E41_Appellation` → `P190_has_symbolic_content`
   "…", with a deterministic appellation IRI and the title's language tag when
   it is a well-formed one. `P1`'s range is `E41`; `F12 Nomen` is a subclass of
   `E89`, not of `E41`, so a Nomen at the end of `P1` would contradict the
   range. `E41` is an `E90`, the domain of `P190`. In the IR, `R33` on an LRMoo
   entity and `P190` on a CRM entity stay shorthands for this path. crm-import
   reads it, and the forms earlier exports wrote (a Nomen with `R33`, `R33` on
   the entity).
3. **Linked Art**: one `LinguisticObject` per Manifestation (its Expression folds
   in, reported `:under-specified`, since Linked Art has no edition level),
   following its own R4/R3 links; one `HumanMadeObject` per F5 Item, carrying
   the text; publishers as a Publishing activity.
4. External N-Triples are read by the RDF 1.1 grammar
   (`palomar.plugins.lrmoo.ntriples`).

## Alternatives considered

- *Downcast with a `:proposed` status*: still asserts a type the source did not
  state; a curator can add it explicitly instead.
- *Keep R33 on entities and document the deviation*: every RDF consumer that
  checks domains would reject or misread it.
- *A Nomen with `R33`, reached by `P1`* (this ADR's first wording): it keeps
  `R33`'s domain but breaks `P1`'s range, as `F12` is not an `E41`. The CRM
  path says the same thing in terms every CRM consumer reads.

## Consequences

- The `:crm-only` round-trip recovers no entity at all; that is the truth of a
  flattened graph.
- RDF outputs gain appellation nodes; consumers reading titles follow `P1` and
  `P190`. The additive `:crm` view has no twin to add for them (they are CRM
  already), and the pure-CRM view no longer reports R33 as generalised.
- LRMoo 1.1.1 was compared with the vendored 1.0 OWL: the terms used are
  unchanged, so no mapping changes with the version.

# 0033 — Loss aspects, a projection edge, and relation fidelity

- Status: Accepted
- Date: 2026-10-25
- Extends: [ADR 0015](./0015-loss-model.md) (edges, categories) and the
  occurrence-level fidelity audit (audit S11/S12)

## Context

ADR 0015 classifies a loss by *category* (dropped, coerced, under-specified,
ambiguity-collapsed) and *edge* (import, export). Three things were missing.

1. **What was touched.** A dropped title and a Work typed only as its CRM
   super-class are both `:dropped`/`:under-specified`, but one loses a value,
   the other a class. An institution auditing a conversion needs to tell a lost
   value from a lost relation, a lost qualifier (language, role), a folded
   level (Expression into Manifestation), a collapse of several source fields
   into one, or lost provenance.
2. **Where the projection loses.** The floor → WEMI projection reported its
   losses on the `:import` edge, so a report could not separate what a spoke
   failed to lift from what the LRMoo view failed to represent.
3. **Relations.** The fidelity audit checks string values only. An export that
   keeps every title but drops the link from a Work to its Expression passes
   it: no string is missing.

## Decision

1. **Aspect.** A loss may name the aspect it touched, `:loss/aspect` in its
   detail, one of `dx/loss-aspects`: `:value` (the default), `:relation`,
   `:qualifier`, `:class`, `:granularity`, `:provenance`. The aspect is
   orthogonal to the category; the conversion report counts losses `:by-aspect`
   (batch and streamed alike). The shipped producers name it where it is not a
   value: MARC indicators and the MODS role (`:qualifier`); MARC/MODS field
   collapses and the several-languages Expression (`:granularity`); the
   pure-CRM super-class typing and E73 collapse, and the Linked Art
   Expression fold (`:class`); the pure-CRM generalisation of R3/R4/R7
   (`:relation`).
2. **Projection edge.** `dx/loss-edges` gains `:projection`: the losses the
   floor and INTERMARC WEMI projections report (unrepresented fields, language
   under-specification, ambiguity collapse) are on it. `:import` is left to the
   spokes and the mappings.
3. **Provenance loss.** Where a record's values are anchored in their source
   (ADR 0031), a target that states facts (all but the history targets of
   ADR 0025) does not write the anchors: `convert/export-records` reports one
   `:dropped` loss of aspect `:provenance` per such record, naming the targets
   that do keep them.
4. **Relation fidelity.** For the N-Triples family (`fidelity/relation-targets`:
   N-Triples, CRM, CRM-only, N-Quads), the audit also checks every reference
   assertion the RDF view expresses (LRMoo, CRM, SKOS predicates; derived ones
   included, since a WEMI link is what the projection produced): it must appear
   as `iri(s) —p→ iri(o)`, `p` its predicate or the CRM super-property the
   pure-CRM export writes instead, or be named by a loss. The result is reported
   apart, under `:relations`, with the three classes of the value audit.

## Consequences

- A loss without an aspect still reads as before (`:value`); no producer has
  to change. A report gains `:by-aspect`, and a text report its line.
- Reports of a projected record now show a `projection edge` block where they
  showed an `import edge` one; their totals are unchanged.
- Every conversion of an anchored source (MARC family, DC, MODS, IIIF) to a
  stating target carries one provenance loss per record. That is honest: the
  output does not say where its values were read; `--to prov|nquads|trig` does.
- The relation audit covers the formats a triple parser reads. Turtle, JSON-LD
  and Linked Art have no triple decoder here and are checked by value only.

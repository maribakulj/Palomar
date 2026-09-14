# 0001 — Assertion-based internal representation

- Status: Accepted
- Date: 2026-04-17

## Context

Palomar ingests metadata from many heterogeneous sources (MARC, Dublin Core,
CIDOC CRM, institutional CSV, TEI, …) and normalizes it into a single internal
representation that subsequent passes (validate, infer, repair, project) operate
on. The shape of that representation is the foundational architectural choice:
everything downstream depends on it.

A field-oriented IR (records as maps of named fields) is the obvious default.
But cultural metadata routinely exhibits:

- multiplicity (two candidate titles, three creators of differing roles)
- ambiguity (date is either 1823 or 1832)
- contradiction (two sources disagree about authorship)
- provisional knowledge (a proposed repair coexisting with the original value)
- rich provenance (which source field, which rule, which pass produced this?)

Field-oriented models force these phenomena into side channels — parallel
`alt-title` fields, confidence maps on the side, provenance tables kept
separately — and these side channels accumulate bugs and inconsistencies.

## Decision

The internal representation of a record is a set of **assertions**. Each
assertion carries:

```
{subject, predicate, value, provenance, confidence, status}
```

A `Record` is a structured envelope — identity, kind, source pointer,
fragments, diagnostics, pipeline-wide provenance — whose content is an
assertion set. Records are first-class; their documentary content is not.

Predicates are namespaced keywords. The core does not interpret them; it
treats them as opaque identifiers.

## Alternatives considered

- **Field-oriented records (map of named fields).** Easier to manipulate in
  simple cases. Rejected: forces ambiguity, provenance, and proposals into
  side channels, which compound in complexity as the system grows.
- **Graph IR with typed nodes and edges.** More structured than assertions.
  Rejected for V1: re-introduces the ontological commitment we want to avoid,
  since the type vocabulary of nodes/edges would have to live in the core.
  Graph projections can emerge from assertion sets when needed.
- **RDF triples directly.** Close cousin, but RDF drags in a semantics (IRIs,
  blank nodes, reification) that doesn't fit a compilation pipeline. We keep
  RDF as a possible projection target, not as the IR.

## Consequences

- Ambiguity, contradiction, proposed repairs, and intermediate pipeline states
  are representable natively, without side channels.
- Every assertion carries its own provenance — traceability is a property of
  the IR, not an optional add-on.
- The IR is schema-independent: the core doesn't know what `:dc/title` or
  `:crm/P1` means. Plugins own their predicate namespaces.
- Projection to external formats requires an explicit reconstitution step
  (assertions → format-specific structure) — this is a feature, not a cost: it
  makes projection loss explicit and reportable.
- Writing rules that manipulate flat assertion patterns is straightforward;
  writing rules that traverse deeply nested structures is not the design
  target. The `Fragment` concept preserves pointers to the original source
  tree where deep traversal is occasionally needed.

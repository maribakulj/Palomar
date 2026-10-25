# 0029 — Facts, proposals, history and provenance in separate named graphs

- Status: Accepted
- Date: 2026-10-25
- Extends: [ADR 0023](./0023-justifications-and-status-at-export.md), [ADR 0025](./0025-history-target-and-iri-safety.md)

## Context

A flat RDF target can only state triples. By default (ADR 0023) it states the
pending claims too — the floor projection's string-key Work and Expression —
so a consumer reads an uncurated inference as a fact; `:in-force-only?`
existed only in the library. The rejected and superseded claims reached only
the PROV record. No output let a consumer take the facts, see the proposals and
consult the history at once, each for what it is.

## Decision

1. Two dataset targets, `:nquads` and `:trig`, write each record as four named
   graphs: `urn:palomar:graph:facts` (in-force or status-less claims),
   `…:proposals` (pending), `…:history` (out of force) and `…:provenance`
   (the PROV-O record, ADR 0025/0028). The default graph types each named graph
   (`palomar:FactGraph`, `ProposalGraph`, `HistoryGraph`, `ProvenanceGraph`):
   what a graph holds is stated, not implied by its name.
2. A triple in a named graph is not asserted in the default graph: the proposal
   and history graphs quote claims. A consumer that wants facts reads the facts
   graph only.
3. The flat targets keep their default (compatibility); `convert
   --facts-only` (`:in-force-only?`) exports only in-force claims from them.
4. N-Quads is read by `ntriples/parse-quads` and decoded by the fidelity audit;
   the dataset passes the ontology check (ADR 0026).

## Alternatives considered

- *Make the flat targets facts-only by default*: the safer default, but it
  silently changes every existing consumer's output; the dataset targets are
  the safe choice, and `--facts-only` the flat one.
- *RDF 1.2 triple terms*: not yet supported by the writers or most JVM stores;
  named graphs carry the same separation in RDF 1.1.

## Consequences

- An application can load the dataset into any quad store and query facts,
  proposals and their history separately.
- TriG is written but, like Turtle, not decoded by the fidelity audit.

# 0028 — Curation decisions are kept on the record and written to PROV-O

- Status: Accepted
- Date: 2026-10-25
- Extends: [ADR 0005](./0005-status-model.md), [ADR 0027](./0027-assertion-identity-and-annotations.md)

## Context

`curate` overwrote an assertion's status and returned the transition (with its
reason) in a log the caller could keep or drop. The record itself forgot that a
decision was made, by whom, when and why; no export carried it; a supersession's
link from the old claim to its replacement existed only in that log. A research
application could not answer "why is this claim rejected?" from the data.

## Decision

1. Every curation decision (`curate`, `curate-record`, `supersede`) is stamped
   on the record as an annotation (ADR 0027) about the assertion it decided,
   under `:palomar.curation/decision`, with a structured value: `:verdict`,
   `:from`, `:to`, and, when known, `:reason`, `:replaced-by` (the replacement's
   assertion id), `:by` and `:at`.
2. Who and when come from the caller (`context`, `{:by :at}`): the engine reads
   no clock and stays deterministic. The CLI's `apply-repairs` supplies the time
   of the run and `--by <curator>`.
3. `curate/history` reads them back, oldest first. The status remains the
   current state; the annotations are how it was reached.
4. The PROV-O target writes each decision as a `prov:Activity` that `used` the
   statement, `wasAssociatedWith` the curator (a `prov:Agent`) and
   `endedAtTime` its time; a decision that takes the claim out of force makes the
   statement `wasInvalidatedBy` it, and a supersession makes the replacement's
   statement `wasRevisionOf` it. Any other annotation is written as a triple
   about the statement.

## Alternatives considered

- *A `:history` vector on the assertion*: simpler, but it changes the assertion
  every exporter reads and cannot hold an application's own annotations.
- *Stamping the time inside `curate`*: makes curation impure and its output
  irreproducible.

## Consequences

- The PROV record of a curated conversion answers what was decided, by whom,
  when and why, and what replaced what (checked against PROV-O, ADR 0026).
- The transition log is unchanged; it is now a view of the same facts.

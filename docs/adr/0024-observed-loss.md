# 0024 — Observed loss: an occurrence-level fidelity audit, and exports that name what they do not write

- Status: Accepted
- Date: 2026-10-25
- Extends: [ADR 0015](./0015-loss-model.md)

## Context

ADR 0015's report aggregates the losses components *declare*. The audit (items
S11, S12, test 15) observed that this is not evidence of preservation: an
exporter that skips a value without declaring it leaves the report clean. Run
against the actual outputs, the floor exports (DC, MARC21, MODS, IIIF) and
Linked Art turned out to drop values silently — other spokes' native fields,
WEMI strings, canonical fields they have no element for.

## Decision

1. `palomar.fidelity` renders each record, decodes the output with a parser for
   its format (N-Triples, JSON, XML) and classifies every string value of the
   record as present, reported (a loss names its predicate for that subject or
   the record) or unaccounted. A conversion is lossless for a target only with
   nothing unaccounted; a test pins that for every spoke × decodable target.
2. Each floor exporter declares what it writes; `export-coverage/unexpressed`
   reports every other predicate as export `:dropped`.

## Alternatives considered

- *Per-exporter hand-written loss lists* (the previous state): they drift, which
  is what the audit found.
- *A round-trip diff (re-import and compare)*: needs a reverse importer per
  target and confuses mapping asymmetry with loss.

## Consequences

- Loss counts grow where losses used to be silent.
- The check is by value: a value that also occurs elsewhere in the output counts
  as present, so it can miss a loss, never invent one; Turtle is not decoded.

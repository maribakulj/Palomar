# 0051 — An EAC-CPF record is a MARC21 authority record

- Status: Accepted
- Date: 2026-10-28
- Builds on: [ADR 0049](./0049-a-target-says-what-it-can-describe.md), [ADR 0045](./0045-ric-o-projection.md)

## Context

An EAC-CPF record describes an agent, not a resource. The bibliographic
MARC21 target produced an empty record for it, and since ADR 0049 refuses
it. MARC21 has its own format for agents, MARC21 Authority, with a heading
whose tag says what kind of agent it is.

## Decision

`:marc21-authority` accepts authority records only and writes them from
their EAC-CPF values:
- **Leader/06 `z`.** `001` is the `recordId`, and `003` the maintenance
  agency, which the importer now reads (`:eac/agency-code`).
- **The heading by entity type.** A person is `100` 1#, a family `100` 3#,
  and a corporate body `110` 2#. The authorized name heads the record; every
  other name is a see-from tracing (`400`/`410`).
- **`024` 7# `$2 uri`** for an `entityId` that is an IRI. An `entityId` that
  is no IRI is reported, not written.
- **`046` for existence dates when they name years.** A person's are birth
  and death (`$f`/`$g`); a body's or a family's are start and end (`$s`/`$t`).
  Otherwise the dates go to `678`.
- **`678` for `biogHist`.** Indicator 0 for a biography, 1 for an
  administrative history.
- **Losses.** Relations are reported, as at import (their targets are file
  names, not authority records). The entity type is reported as
  under-specified: it is written as the heading's tag, not as text.

## Consequences

- An archival authority file reaches a MARC authority system with its
  heading in the right field.
- The `008` fixed field is not written. Its positions (e.g. the descriptive
  rules) are not stated by EAC-CPF.

# 0023 — Every justification is kept; out-of-force claims are never read or written

- Status: Accepted (amended by [ADR 0025](./0025-history-target-and-iri-safety.md): the PROV target keeps the out-of-force history)
- Date: 2026-10-25
- Amends: [ADR 0008](./0008-idempotency-at-merge.md) (what a collapsed duplicate keeps)
- Extends: [ADR 0005](./0005-status-model.md) (where statuses are enforced)

## Context

The audit (items S07–S09, S27, S28, S36) found that statuses and provenance
were recorded but not enforced:

- the rule matcher read rejected, superseded and retracted assertions, and no
  exporter looked at status, so a claim a curator rejected could reach the output
  as a fact;
- ADR 0008's merge kept the first of two identical facts and dropped the second
  production entirely, so a fact two rules derived kept one justification;
- productions named their rule but not the match that fired it;
- curation logged transitions without reasons, and supersession was not
  available at all.

## Decision

1. `palomar.model/out-of-force-statuses` (`:rejected :superseded :retracted`)
   is the one definition. The matcher never sees these assertions, and
   `convert/export-records` — the single export path, batch and streaming —
   never emits them. Pending claims are emitted by default; `:in-force-only?`
   emits only asserted, accepted or status-less ones.
2. Merging an identical fact keeps one assertion (ADR 0008 unchanged on that
   point) and adds the duplicate's provenance to `[:provenance :also]`, once per
   distinct justification.
3. Rule-DSL productions carry `[:provenance :bindings]`, the match that fired.
4. A curation decision may return `[verdict reason]`; `curate/supersede` replaces
   an in-force assertion explicitly, logging the replacement and the reason.
5. One entity id with two different `:iri`s is an `:entity-id-collision` error,
   not a silent merge.
6. The `:prov` target serialises this provenance as PROV-O.

## Alternatives considered

- *Filtering statuses in each exporter*: ten places to keep in sync; one entry
  point instead.
- *Keeping both duplicate assertions*: breaks ADR 0008's one-fact-one-assertion
  identity and every count built on it.

## Consequences

Provenance maps grow with `:also` and `:bindings`; identity (ADR 0008) is
unaffected since provenance is not part of it.

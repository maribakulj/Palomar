# 0027 — An assertion is identified by its content; statements about it are annotations

- Status: Accepted
- Date: 2026-10-25
- Amends: [ADR 0008](./0008-idempotency-at-merge.md) (assertion identity)

## Context

An assertion had no identity of its own. Deduplication keyed on
`[subject predicate value status]`, so the same fact proposed by one rule and
asserted by another stayed two assertions, and a rule re-deriving a claim a
curator had rejected brought it back as a second, `:proposed`, assertion. The
PROV statement IRI digested the same four fields with `pr-str`, which prints a
map in insertion or hash order: equal values could get different IRIs, and a
statement was renamed whenever its status changed. Nothing could be said
*about* an assertion — a curation decision, an agent supporting it, a
derivation from another — because only records, fragments and entities can be
subjects.

## Decision

1. `model/canonical` gives every value a print-stable form (maps and sets
   sorted, leaves tagged by type); `model/content-digest` hashes it. Every
   content-derived IRI uses it.
2. `model/assertion-id` is `:asr/<16 hex>` over the canonical
   `[subject predicate value]`. It is computed, never stored, and does not change
   when a curator changes the status.
3. Deduplication (`runtime/assertion-identity`) is `[subject predicate value]`:
   one fact, one assertion. When the same fact is merged with another status,
   the merged assertion keeps the stronger one — a decision (`:accepted`,
   `:rejected`, `:superseded`, `:retracted`) over `:needs-review` over
   `:asserted` over `:proposed`, ties to the existing one — and the provenance
   joins `:also` as before (ADR 0023). A rejected claim stays rejected when a
   rule derives it again.
4. Statements about an assertion are **annotations**: `{:about assertion-id
   :predicate :value :provenance}` in a record's `:annotations`, apart from its
   assertions. The rule matcher, the exporters and the loss accounting never
   read them, so a statement about a claim is never taken for a documentary
   fact. An annotation whose target is gone is an orphan
   (`model/orphan-annotations`), not an error: a decision outlives the claim it
   rejected. The predicates belong to whoever writes them (curation, step 4;
   an application).
5. A PROV statement's IRI is its assertion id. `curate/supersede` refuses a
   replacement that is the same fact.

## Alternatives considered

- *Keep status in the identity and in the id*: the id would change on every
  curation, which is what an address must not do.
- *Make assertion ids subjects of ordinary assertions*: meta-statements would
  enter the matcher and every exporter, as documentary facts, and dangle when an
  export drops their out-of-force target (reviewed and rejected before coding).

## Consequences

- A fact's count no longer depends on how many statuses it was derived under.
- PROV statement, activity and association IRIs change once (canonical
  digests); they are stable from now on.
- Step 4 records curation decisions as annotations; an application can record
  its own (who supports a claim, which passage attests it) the same way.

# 0042 — A target that states facts states only facts, by default

- Status: Accepted
- Date: 2026-10-26
- Amends: [ADR 0029](./0029-named-graph-datasets.md) (the export policy), and
  [ADR 0036](./0036-publication-events.md) (the status of events)

## Context

A plain RDF triple has no place to say "this is a guess". Every consumer of
an N-Triples, Turtle, JSON-LD, CRM or Linked Art file reads it as an
assertion: a triple store, a SPARQL query, an aggregator. Yet by default the
stating exports emitted pending claims (`:proposed`, `:needs-review`). Those
include the string-keyed Work and Expression, which is what the post-audit
review found most likely to be false. So a default conversion published
guesses as facts. Once loaded and linked, they spread, and withdrawing them
costs far more than not stating them. `--facts-only` existed, but as an
opt-in.

## Decision

1. **A stating target emits facts only by default**: `:asserted` and
   `:accepted` claims, or claims with no status. A pending claim is withheld,
   and an entity that only withheld claims made is dropped with them.
   `export-records` returns how many it withheld (`:withheld`); the
   conversion report carries it (`:withheld-proposals`) and says how to get
   them.
2. **Opting in is explicit.** `:with-proposals? true` (`--with-proposals`)
   states them too. The history targets (PROV-O, N-Quads, TriG, EDN) keep
   every claim with its status, as before (ADR 0025, 0029). They are the way
   to publish hypotheses as hypotheses. `--facts-only` is accepted and is
   now the default.
3. **`:asserted` is earned.** A rule or a projection marks a claim
   `:asserted` only when something proves it:
   - a transcription of the source;
   - an authority identifier;
   - the source's own structure.

   On that ground:
   - the publication events (ADR 0036) are asserted. A 260 states the
     event, and its place and actor are scoped to their record (ADR 0041);
   - the creator-keyed Work and Expression stay `:proposed`;
   - INTERMARC's authority-linked chain stays asserted.
4. **The fidelity audit observes the fullest stating export**, proposals
   included. It judges the exporter; withholding is policy, and it is counted.

## Consequences

- A default conversion of uncurated records is thinner: Manifestations, their
  titles, events and transcribed links, without the guessed Works. The report
  says what was withheld and how to curate or export it.
- What a default export states can be trusted to the level of its evidence.
- A pipeline that relied on the old default passes `--with-proposals`, or
  better, exports TriG.

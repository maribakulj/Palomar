# 0017 — Synthesized entity representation (entities on records)

- Status: Accepted
- Date: 2026-06-01
- Builds on: ADR 0001 (assertion IR), ADR 0013 (rich pivot), ADR 0014 (minting),
  ADR 0016 (WEMI derivation)
- Decision record: [`../wp0-decisions.md`](../wp0-decisions.md) (D3);
  [`../roadmap-v1.md`](../roadmap-v1.md) §10

## Context

ADR 0014 lets the `infer` phase **mint** synthesized entities (Works,
Expressions…). An entity is a *subject* — but the IR's consistency contract
(`palomar.model/known-subjects`, `record-consistent?`) currently blesses only
the record id and its fragment ids. So minting forces a decision: **how does a
synthesized entity live in the IR?**

Two facts of the system constrain the answer:

- the runtime is **per-record** (`run-pipeline` takes one record; rules *enrich*
  it, they do not spawn records);
- Palomar is a **converter that emits a graph, not a store that holds one**
  (README; roadmap §10).

## Decision

**A synthesized entity is a first-class member of the record that mints it** — a
new `:entities` collection on `Record`, with an `Entity` shape
`{:id :kind :provenance?}` distinct from `Fragment` (which points into raw
source). `known-subjects` includes entity ids.

Identity is **content-based and deterministic** (`mint-entity-id`, ADR 0016): the
same entity minted independently from two records yields the *same* id and
collapses at merge (ADR 0008) — so **exact cross-record clustering needs no
shared batch state.**

The `:kind` is an opaque, plugin-supplied keyword (`:lrmoo/work`); the core never
interprets it. This extends the core's **structural** vocabulary (a new
collection, exactly like `:assertions`), not its **documentary** vocabulary —
agnosticism (ADR 0003) holds, and `structural-vocabulary` is unchanged.

## Alternatives considered

- **B — each entity is its own `Record`, referenced by value.** Ontologically
  purest, inter-record by nature. **Rejected:** the runtime cannot *spawn*
  records; B requires a batch / record-spawning runtime — a deep refactor of the
  runtime, the matcher and every plugin. For a *converter* (which must *emit* a
  graph, not *hold* one) it optimises the wrong thing, and it fights streaming at
  scale (R5). B is a *store's* design (roadmap §10).
- **A′ — a subject-centric flat-assertion IR** (no privileged `Record`
  container; records, fragments, entities are peer subjects). The principled
  greenfield substrate for an entity-graph pivot, and it dissolves A's only wart
  (per-record entity redundancy). **Rejected for now:** it re-opens ADR 0001 and
  rewrites the per-record runtime / matcher + every plugin and test (~8k lines) —
  exactly the deep refactor we avoid. **A′ becomes the right substrate only if
  Palomar evolves from a converter into a queryable / editable store**
  (roadmap §10). Recorded as the conditional future, not a now-task.
- **C — declare entities via a `:meta/kind` assertion; derive `known-subjects`
  from assertions.** **Rejected:** circular (a subject must be known to carry an
  assertion, yet would become known *via* one) and fragile.

## Consequences

- Minimal, additive change to `palomar.model`: an `Entity` schema, `:entities`
  on `Record`, `mint-entity-id`, and `known-subjects` / `record-consistent?`
  extended to entities. Existing records (no `:entities`) stay valid;
  `structural-vocabulary` stays a closed six (entities are a *collection*, like
  `:assertions`, not a recognised predicate).
- Exact cross-record clustering needs **no batch state**; the batch Work-index is
  needed only for *fuzzy* bridging → WP-3.
- An entity "lives" on its minting record (a deterministic copy per minting
  record, deduped by id downstream). For a write-once conversion artifact this
  redundancy is benign — no update anomaly, because you re-run, you do not edit.
- **A′-compatibility is a standing constraint:** entities are first-class
  subjects *now*, identity is content-deterministic, edges are references — so a
  future flattening to A′ is a regrouping by id, not a rewrite.

## What this ADR does not decide

- The `:kind` vocabulary (`:lrmoo/work` …) — plugin / WP-2.
- The work-key composition feeding `mint-entity-id` — WEMI-derivation plugin / WP-3
  (D5).
- The status of inferred assertions — settled by ADR 0005 / 0014: infer
  productions default to **`:proposed`** (the precision-first engine policy,
  `rules/default-status-for-phase`), marked by `:provenance {:pass :infer …}` +
  confidence; high-confidence promotion to `:asserted` (D7) is future and
  unimplemented. There is no `:inferred` status (ADR 0005). *(Corrected
  2026-06-02: an earlier draft here said inferred assertions are `:asserted`,
  contradicting ADR 0014 and `default-status-for-phase`.)*

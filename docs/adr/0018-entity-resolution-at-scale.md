# 0018 — Entity resolution at scale: reconcile-to-authority, equivalence as assertion, revisability

- Status: Accepted (partially implemented — the certified + fuzzy agent tiers are
  landed; the equivalence-verdict store and the live-reconciliation yield are
  deferred, see below)
- Date: 2026-06-02
- Partially implemented (2026-06-04): the **certified tier** of decisions 1 and 5
  — reconcile *to an authority*, by a determinate id, never pairwise — is real for
  agents in `palomar.reconcile`: identified `:crm/E21_Person` entities (an ISNI
  minted by `intermarc.wemi/with-identified-agent`) are blocked by their
  authority `:iri` and collapsed to one reconciled agent across records (the store
  is the agent registry it returns). Exact and D7-`:asserted` — the Madame Bovary
  fixture reconciles to one Flaubert. The **fuzzy tier** (decisions 3/4) is now
  *started*: `reconcile/propose-agent-links` emits confidence-scored (token-set)
  name→authority *proposals* — `:proposed`, never `:asserted` — with a
  `:certifiable?` guard so a perfect name match to an id-less entry (the Victor
  Hugo *metro station*) can never be promoted. The equivalence-assertion verdict
  *store* and revisability over a maintained authority index remain proposed.
- Builds on: ADR 0016 (WEMI derivation — decides the *deferred* scale layer it names),
  ADR 0013 (rich pivot / strategy C — equivalence resolved in the view), ADR 0005
  (status — `:proposed` equivalence), ADR 0001 (assertions), ADR 0008 (idempotency
  — bounded here), ADR 0003 (agnostic core — equivalence predicates stay opaque)
- Empirical: grounded in the offline spike
  [`../spikes/entity-resolution.md`](../spikes/entity-resolution.md); refines the
  spike-outcome notes in ADR 0016 §2 and §5 with measured precision/recall.

## Context

The question this ADR answers, plainly: *when there are millions of records and the
same author or work is written a dozen different ways, how does Palomar decide what
is the same thing?*

ADR 0016 decided the **in-run** machinery — a resolver seam, a deterministic hash +
authority-anchored resolver, confidence-gated control, batch-local clustering — and
**explicitly deferred** the rest: "fuzzy matching of entities that share *neither*
an exact key *nor* an authority id, across separate batches, needs the deferred
store / online layer" (0016 §3). That deferred layer is exactly the scale question.
This ADR decides its shape.

It is grounded, not assumed. The offline spike replayed several clustering keys
against data.bnf.fr ground truth (the 28-edition *Madame Bovary* Work) and measured
the trade-off directly:

| strategy | precision | recall | note |
|----------|----------:|-------:|------|
| authority **link** (`f145 $3`) | 1.000 | 1.000 | but fires for ~11 % of records |
| `author-only` | 0.931 | 1.000 | **over-merges** distinct works by one author |
| `author + norm(title)` | 1.000 | 0.431 | **under-merges**: 7 title variants → 1 work |
| `author + title-prefix` | 1.000 | 0.524 | coarser title helps only modestly |

Three facts follow, and they drive every decision below:

1. **No string key wins both axes.** Loose keys over-merge; exact keys under-merge.
   Only the authority link gets precision *and* recall — because it carries the
   *uniform title*, the catalogue's own reconciliation, which variant transcribed
   titles cannot reconstruct.
2. **The agent is usually already reconciled** (an embedded `$3` authority id);
   the systematic hole is the **Work**.
3. The information needed to reconcile off-showcase (the uniform title / authority)
   is **precisely the information that is missing** there.

## Decision

### 1. Reconcile *to an authority*, never pairwise across records (the spine)
At scale we do **not** compare records to one another (O(n²), no ground truth,
unstable). We resolve each surface form against a **fixed, controlled target** — an
authority (ISNI / VIAF / IdRef / BnF), read from a pinned snapshot per ADR 0016.
"Match a string to a fixed target set" is bounded and stable; "cluster millions
among themselves" is not. The dozen ways of writing *Flaubert* already converge on
ISNI `0000000122762442`; we align to that, we do not re-derive it.

Priority is **Work reconciliation**: since the agent typically already carries a
`$3` id, the resolver's first off-showcase job is to resolve *(agent-id + title)* to
a **Work authority**, not to re-resolve the agent.

### 2. A reconciliation verdict is a provenanced, scored equivalence *assertion* — not a merge
Every decision — "this surface form ≡ authority *X*, score *s*, via *source*, on
*date*" — is an **assertion** (ADR 0001) with provenance and status **`:proposed`**
(ADR 0005), under an equivalence predicate (e.g. `:owl/sameAs`, `:reg/resolves-to`,
or an appellation link). The core carries it as an opaque key (ADR 0003); the LRMoo
view resolves it **at read time** (strategy C, ADR 0013).

The consequence is the scale payoff of assertions-as-truth: reconciliation is
**monotonic accumulation of evidence**, not destructive mutation. A wrong match is
**retracted** (supersede / remove the claim); we never surgically un-merge mangled
canonical data, because we never mangled it. The "millions of ways to name" become
millions of equivalence claims converging — or not — on authority ids.

### 3. Offline clustering is a confidence-gated recall *aid*, emitted as proposals
Per the measured curve, only the **deterministic tiers auto-commit** to the asserted
graph (`infer`): the embedded authority link, and an exact *(authority-id + uniform
title)* key. Everything looser — string-title clustering, fuzzy name/title
similarity — is emitted **`:proposed`** and routed to curation (`apply-repairs`,
ADR 0005), **never auto-committed**. This is ADR 0016 D11's "cast a wide net, commit
conservatively", now with the data behind it: `author-only` would collapse a
prolific author's whole oeuvre into one Work; `author + norm(title)` would shatter
one Work into seven.

Exact in-corpus *(agent-id + norm-title)* dedup is **kept** — it found a real
"Mon grand-père" ×3 cluster — but as a **proposal generator**, not as Work identity.

### 4. Revisability over strong idempotency for the fuzzy tier
ADR 0008 idempotency holds for the deterministic tiers: same link / same exact key →
same id, and a re-run mints nothing. But fuzzy **discovery** is *not* idempotent in
the strong sense — new data can merge previously separate clusters. We accept this
and make merges **cheap to revise** (an assertion added or retracted) rather than
pretend discovery is stable. The invariant we keep is:

> the projection is a pure function of the **current set of equivalence claims** —

not "the claim set never changes". Idempotency becomes a property of the *projection
given the claims*, not of the *discovery of claims*.

### 5. Scale mechanics: blocking + a queried resolver/index (the store boundary)
- **Blocking** (no n²): bucket candidates by cheap keys (normalised surname + date,
  title trigrams) and compare only within a bucket, or against the authority index.
- The per-record conversion pass stays **deterministic by *querying*** a resolver /
  index (the authority snapshot, or a maintained local authority store) — it never
  embeds cross-record comparison. This is exactly the **converter → store ladder**
  (roadmap §10, *"from converter to store"*): fuzzy ER at scale is the rung where
  Palomar optionally grows an indexed store. Until that rung, reconciliation stays
  **batch-local** against a pinned snapshot (ADR 0016 §3).
- **Appellation model**: the entity is keyed on its authority id; the many surface
  forms become appellation assertions (LRMoo / CIDOC `E41`-style) pointing at it.
  "Many names → one entity" already has a home in the data model.

### 6. The core stays agnostic
Equivalence predicates and appellations are **opaque keys** to the core (ADR 0003);
only the plugin / view interprets them. Entity resolution at scale adds **no**
structural vocabulary — consistent with every prior decision.

## Evidence (2026-06-03)

The premise is now corroborated on **two independent corpora** (see
`docs/eval/entity-resolution.md`): on Madame Bovary vs data.bnf (P = R = 1.0 *with*
the link, ~0.43 recall *without*) and on ~4 555 OpenLibrary editions / 328 works
*independent of the BnF* (exact `author + title`: genuine precision ≈ 1.0 but recall
≈ 0.37; loosening trades precision for recall; author-only collapses to P = 0.05).
The trade-off and the need for the authority link are measured, not asserted.

Two findings sharpen this ADR:
- §3 (offline clustering is a *recall aid*, proof-gated) is the right call: exact
  match is near-perfectly precise but low-recall — so auto-commit the exact tier
  (the D7 commit policy now does), propose the rest.
- A clean, broad work-grouping **gold does not exist** in open sources (BnF,
  Wikidata, OpenLibrary each fail differently — OpenLibrary even fragments
  *Frankenstein* across 14 works). So tail recall cannot be *certified* against an
  external authority; it must be evidence-gated and curated, never benchmarked into
  existence. The **live-reconciliation yield** therefore stays the one unmeasured
  number, gated on a curated/fetched authority (the live T1 probe).

## Alternatives considered

- **Global pairwise record linkage** (cluster millions among themselves). Rejected
  as the spine: O(n²), no ground truth, unstable under new data. Its exact-match form
  survives only as a within-block proposal generator (§3).
- **Destructive merge into canonical records.** Rejected: a wrong merge mangles data
  and is not cleanly reversible — the opposite of strategy C and the loss model.
- **Recall-first auto-merge** (loose keys auto-committed). Rejected by measurement:
  `author-only` over-merges, `author+title` under-merges; precision-first with a
  proposal tail is the data-correct posture.
- **Live-API reconciliation inside the conversion pass.** Deferred (as in ADR 0016):
  non-deterministic and network-bound; snapshot/dump first, live additive behind the
  resolver seam.
- **Strong idempotency for all tiers.** Rejected as unattainable for fuzzy discovery;
  revisability (§4) is the honest substitute.

## Consequences

- **Easier.** Scale-out reconciliation is "resolve against a fixed target, accumulate
  provenanced claims" — parallelisable, reproducible per snapshot, and correctable by
  local edits to the claim set. No n² join, no destructive state.
- **Harder (honest costs).** Needs a versioned authority snapshot as input; cross-
  batch fuzzy needs an indexed store (the §10 rung); homonyms and the ambiguous tail
  need human-in-the-loop; the fuzzy tier is revisable, not idempotent.
- **Unmeasured / gated.** The **live-reconciliation yield** — how many of the
  off-showcase Works we actually recover by resolving *(agent-id + title)* to an
  authority — is **not yet measured**; it needs a live T1 probe, which is blocked in
  the current sandbox (403 egress) and will require fetched data, as the C2 gold did.
  This ADR sets direction; the yield is future work, not a claim.
- **Refines ADR 0016.** The spike supplies the exact P/R behind 0016's qualitative
  spike notes (§2 "fuzzy ~4%", §5 "under-merges title variants"): the fallback
  under-merges at **R = 0.43** precisely because it cannot see the uniform title —
  confirming 0016's "must key on the uniform title".

## What this ADR does not decide

- The store / index technology, and whether or when Palomar climbs that rung
  (roadmap §10; V2).
- The live reconciliation source order and its measured yield (future T1 probe).
- Blocking-key design, the confidence-threshold value, and homonym-disambiguation
  policy — tuned later, on data.
- Subjects / events reconciliation (ADR 0016 D11, Tier 3 / 4).

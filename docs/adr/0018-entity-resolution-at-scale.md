# 0018 — Entity resolution at scale: reconcile-to-authority, equivalence as assertion, revisability

- Status: Accepted (partially implemented — the certified + fuzzy agent tiers are
  landed; the equivalence-verdict store and the live-reconciliation yield are
  deferred, see below; §2 amended by [ADR 0032](./0032-graded-alignments.md):
  verdicts are graded SKOS mapping claims, never `owl:sameAs`)
- Date: 2026-06-02
- Partially implemented (2026-06-04): the **certified tier** of decisions 1 and 5
  — reconcile *to an authority*, by a determinate id, never pairwise — is real for
  agents in `palomar.reconcile`: identified `:crm/E21_Person` entities (an ISNI
  minted by `intermarc.wemi/with-identified-agent`) are blocked by their
  authority `:iri` and collapsed to one reconciled agent across records (the store
  is the agent registry it returns). Exact and D7-`:asserted` — the Madame Bovary
  fixture reconciles to one Flaubert. The **fuzzy tier** (decisions 3/4) is now
  *started*: `reconcile/propose-agent-links` emits scored (token-set; since lot
  M4, optionally Fellegi-Sunter)
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

## Amendment (lot M3, 2026-11-09): dates as contrary evidence, and the evidence on a proposal

Measured on real BnF homonyms (`docs/eval/person-reconciliation.md`), the
name-only fuzzy tier could not tell Alexandre Dumas père (1802-1870) from his
son (1824-1895), both headed "Dumas, Alexandre": 35 false links on 136, all
that pair. The mentions carried the dates; the matcher did not read them.

**Policy: blocking evidence errs towards no evidence.** A false rejection —
the right authority withheld — is worse than a missed one, so everything
below widens rather than narrows, and what is not read blocks nothing.

1. **A mention is a name or `{:name :dates :record}`**, its dates a
   life-dates string or profile (`palomar.agent-dates`), its optional
   `:record` the record it was read in; a plain string is still accepted and
   matched as before. An authority entry may carry `:dates` (ADR 0038
   amendment). **No spoke and no CLI command passes dated mentions yet**:
   `palomar reconcile` passes the records' names as strings, and only the
   person-reconciliation eval passes dates (read from the INTERMARC natives).
2. **Dates are intervals that keep their uncertainty.** A point is an interval
   of years: `18..` is 1800-1899, `182X` 1820-1829; `ca. 1830` and EDTF
   `1830?`/`1830~` are widened by `agent-dates/margin` (**20 years**); `av.
   1850` and `1933-....` leave an end open; an alternative `1488/90` is
   1488-1490. A trailing ISBD period is dropped (`735-804.`). Text the parser
   does not read, a string read only in part (one side of a span), a
   self-contradicting one (birth after death) and any date before the common
   era (`B.C.`, `av. J.-C.`, `v. Chr.`, EDTF `-0384`, GND `v384`) are kept as
   text and contribute nothing: never a year of the common era.
3. **Compatibility is "can overlap", within a tolerance.** Two profiles are
   incompatible when an interval check fails: births do not overlap, deaths do
   not overlap, one is born after the other's death, or an activity falls
   outside the other's life — every comparison allowing
   `agent-dates/tolerance` (**2 years**: sources disagree, a birth is
   registered late, the Julian and Gregorian calendars differ). Dumas père and
   fils, 22 years apart, stay apart. With no check possible — dates missing on
   either side — the verdict is `:unknown`: no evidence, never
   incompatibility.
4. **Dates block; they do not score.** `reconcile/agent-candidates` returns
   `{:proposals :rejected}`. A candidate the name admits but whose dates are
   incompatible is never proposed: it is listed under `:rejected` with
   `:reason :incompatible-dates` and its evidence, so a curator sees why.
   `propose-agent-links` returns the proposals of it, as before. The name
   score is unchanged by dates; a scored combination (Fellegi-Sunter) is a
   later lot.
5. **Each proposal carries its evidence**: `:evidence {:name-score :dates
   {:verdict :checks :mention :candidate}}`, the checks naming the roles and
   intervals compared, and, for a dated or record-scoped mention, the
   `:mention` that made it. `align-agents` writes the evidence on the
   `:proposed` SKOS mapping assertion's provenance bindings (`?name-score`,
   and for a dated mention `?dates`, `?mention-dates`, `?authority-dates`,
   beside `?label`), which PROV-O writes: the same assertion as before,
   curated the same way, no parallel model.
6. **A proposal stays with its mention.** `align-agents` gives a record the
   proposals of the mention scoped to it (`:record`), else of the one
   unscoped mention of its name; when several mentions of one name (two
   dates) could be its own, the record takes none. So a record never holds
   two homonyms' authorities or another mention's evidence, and a candidate
   rejected for a mention is no assertion in that mention's record.

Measured (`palomar.eval.person-reconciliation-test`): false links 35 → 0,
recall@1 0.743 → 1.000, precision@1 0.732 → 0.986, ties 36 → 0, whatever the
pool order; abstention outside the pool unchanged (the undated Paltsits
mentions are still linked to Victor Hugo). The margin of 20 and tolerance of
2 give the same numbers as a first measure with a margin of 10 and no
tolerance: every date on that corpus is an exact year. Pinned by
`palomar.agent-dates-test` and
`palomar.reconcile-test/a-candidate-whose-dates-are-incompatible-is-never-proposed`,
`missing-dates-are-no-evidence`, `a-plain-name-is-still-a-mention`,
`a-proposal-carries-its-evidence-into-the-ir`,
`a-proposal-stays-with-the-mention-that-made-it`,
`an-undated-mention-states-no-dates-verdict`.

## Amendment (lot M4, 2026-11-09): a Fellegi-Sunter score, abstention, and a confidence only when calibrated

After M3, two things were still wrong on the BnF gold
(`docs/eval/person-reconciliation.md`): the matcher never abstained — the two
"Paltsits, Victor Hugo" mentions, whose authority is not in the pool, were
linked to Victor Hugo at a Jaccard of 0.667 — and the Jaccard was written as
an alignment's `:confidence` though nothing had measured it as a probability.
This amendment was revised after a contradictory review (probes of the first
version: a confidence carried to another pool, two outright proposals for
one mention, weights read from pseudo-counts, a forged bar accepted); the
rules below are the revised ones.

1. **An explainable score, as data** (`palomar.agent-match`, pure Clojure, no
   dependency). A (mention, candidate) pair is a comparison vector of three
   features: `:name`, the best level over the candidate's forms, ordered by
   agreement — `:exact` heading (accents, case, punctuation aside),
   `:initials` (same surname, forenames compatible as initials: "P.-J." /
   "Pierre-Jules"), `:same-tokens` (other order), `:surname-typo` (surname
   one edit away, forenames alike), `:forename-overlap`, `:surname-only`,
   `:forenames-differ`, then, surnames differing, the token-Jaccard bands
   `:jaccard-high` (>= 0.5), `:jaccard-low` (> 0), `:none` — so the Jaccard
   is one level of one feature, not the decision; `:form`, `:heading` or
   `:variant` (the best level reached only on a `400` / pseudonym); `:dates`,
   `:agree` (an exact birth or death year on both sides, within the 2-year
   tolerance), `:compatible` (approximate, open, an activity), `:unknown`.
   **Incompatible dates stay a block** (M3), never a weight. Nothing derived
   from the gold is a feature: no authority number, ISNI, `$3` or record id
   (the eval's spy tests check the matcher's inputs and that a model holds
   no string but the names of the families it was trained on).
2. **Only what was measured weighs.** For each level, m = P(level | same
   person) and u = P(level | different persons) are read from observed
   counts, and the weight is log2(m/u) — under four rules
   (`agent-match/level-weights`):
   - a level reached by fewer than **5** pairs (`min-observed`) on both sides
     weighs **0**; reached on one side only, the other side's share is
     bounded by 5/N and the weight is the bound it gives, kept only in the
     direction it proves: an agreement seen among the same persons and
     almost never among different ones weighs at least log2(m / (5/N_u)),
     never below 0; a level seen among different persons and almost never
     among the same weighs at most log2((5/N_m) / u), never above 0. No
     weight is read from pseudo-counts;
   - **`:dates :unknown` weighs 0 by rule.** Missing dates are no evidence
     (M3). The corpus estimate (m 5/136, u 244/264, log2 -4.6) measures two
     artefacts: the BnF headings carry a `$d` (so the true pairs are dated),
     and the date block removed the dated non-matches (so what is left of
     them is undated). Weighing it would abstain on every undated mention for
     being undated;
   - along the name order, **weights never increase**;
   - a **disagreeing level** (`:forenames-differ`, `:jaccard-high`,
     `:jaccard-low`, `:none`: forenames on both sides that differ, or
     surnames that differ) **never weighs more than 0**.
   **u is label-free, from the pool against itself**: every ordered pair of
   two distinct authority entries (1 056 on M2) for the name and the form,
   those the dates do not block (264) for the dates — two records being two
   persons by construction, and, under the model's own independence
   assumption, the name of different persons not depending on whether the
   date block applies. The (mention, entry) pairs are not used for u: once
   the dates block, about one in eight of those left is a true pair. This is
   **transductive**: the pool is built from the gold's own authorities.
   **m from labelled true pairs, leave-one-family-out** (Dumas, Hugo, Hetzel,
   Verne, others): a mention is scored by the model fitted on the four other
   families. The prior λ is the share of true pairs among the training
   mentions' unblocked pairs (0.111-0.179 by fold). **EM** (unsupervised, u
   fixed, m and λ re-estimated; its weights read from EM's expected counts
   under the same rules) is reported for comparison.
   On M2, every true pair is an exact heading, so the weights are: exact
   +7.72 (u bounded: 2 of 1 056 pool pairs), agreeing dates +3.67, heading
   +0.02, Jaccard > 0 with another surname at most -1.91, nothing shared at
   most -4.46 (m bounded: 0 of 136), and **0 for every other level** —
   initials, typos, reordered or partial names, forenames that differ, a high
   Jaccard with another surname, a variant are unmeasured here.
3. **Term frequency, from one pool.** A surname-agreeing name level with a
   positive weight is adjusted by log2(ū / u_s): u_s the share of the
   pool's headings with that surname (a surname no heading holds counts
   once), ū = Σ n_s³ / (N Σ n_s²), the mean u_s of an agreement between two
   random headings of the pool. **Both from the pool matched against**
   (`compare-pair` carries them), never the model's ū with another pool's
   counts: on a pool of 5 001 distinct surnames the first version added +7.2
   to every Verne. **The pool, not the mention corpus**: a wrong candidate is
   drawn from the pool, so the chance that it shares the mention's surname is
   the surname's share of the pool; its share of the mentions says how often
   a person is cited. On M2, ū = 2/34: a surname held by three headings
   (Dumas, Hugo) weighs 0.63 less, one held once 0.96 more. A disagreement
   gets no adjustment: a rare surname never makes different forenames
   evidence for.
4. **Decision.** Posterior p = 1/(1+2^-(w + log2(λ/(1-λ)))). At p >= 0.95 a
   candidate is **proposed** (`:decision :propose`, `skos:exactMatch` when
   certifiable) — **but only when it is its mention's one candidate at or
   above 0.5**; with several, all of them go to **review**
   (`:review-reason :competing`), so a mention never has two outright
   proposals. At 0.5 <= p < 0.95 a candidate is for review
   (`skos:closeMatch`); below 0.5 the matcher **abstains**: the candidate is
   listed under `:abstained` with its evidence and makes no claim. The
   thresholds are stated in the model (`default-thresholds`), set before
   measuring, not tuned. Each proposal carries `:score` (p),
   `:match-weight`, `:decision` and `:evidence {:comparison :weights
   :name-score :dates}`; `align-agents` writes them as the mapping's
   provenance bindings (`?posterior`, `?match-weight`, `?decision`,
   `?name-level`, `?form`, `?dates-level`, beside `?name-score` and M3's
   dates). The scorer runs only when a model is passed (`agent-candidates`
   with `{:model m}`); without one, the M3 name-only path is unchanged. No
   spoke and no CLI command passes a model yet; the model is computed by the
   eval from the gold, not shipped as a file.
5. **Calibration, and the confidence rule** (ADR 0005 L2). The held-out
   predictions (every scored candidate of every determined mention, from its
   family's held-out model) are measured: reliability table (10 bins), Brier
   score, ECE, the largest bin gap, and what they rest on — the distinct
   (mention, candidate) pairs, the distinct true mentions (35 identical
   "Dumas, Alexandre" mentions are one), the distinct pairs in the review
   band [0.5, 0.95). One report, pooled over the five folds, is attached to
   every fold model, naming by fingerprint the pool and the gold it was
   measured on. **The bar** (`default-bar`): ECE <= 0.05 and no bin off by
   more than 0.1, on at least 300 distinct pairs and 100 distinct true
   mentions; for a `:review` decision, at least 30 distinct pairs in the
   review band too. A posterior is written as `:confidence` only when:
   - the report meets the **model's** bar — pinned when the model is built,
     never laxer than `default-bar`, never the bar the report carries;
   - the pool matched against has the report's fingerprint: **a calibration
     holds for the pool it was measured on, no other**;
   - for a review decision, the review band was covered;
   - and the proposal carries that proof (`:calibration-proof`), which
     `align-agents` checks (`proven-confidence`) before writing a
     confidence: one a caller put on a proposal map is not written.
   Otherwise the posterior is evidence only (`?posterior`). **The name-only
   Jaccard is never written as a confidence any more** (it was, since ADR
   0032).
   **On M2 the bar is missed**: 351 predictions, but 125 distinct pairs, 33
   distinct true mentions and 7 pairs in the review band; Brier 0.0360, ECE
   0.1082, largest bin gap 0.740 (the 24 competitors at 0.54, none right).
   The fold models are not calibrated, and **no proposal states a
   confidence**. EM: Brier 0.0832, ECE 0.1776.

Measured (`palomar.eval.person-reconciliation-test`): against M3, false links
0 → 0, recall@1 1.000 → 1.000, precision@1 0.986 → 1.000; the 5 mentions
whose authority is not in the pool are all abstained (M3: 2 linked); no
in-pool tie (the undetermined, undated "Dumas, Alexandre" is still tied
between père and fils, now both for review); task B top-1 false merges 2 →
0. Counting only outright proposals, recall@1 is 111/136: 25 in-pool
mentions go to review because another candidate is above 0.5 — 20 "Férat,
Jules" (1829-1906) against Jules Verne (1828-1905), whose different surname
weighs at most -1.91 now that its bound is all that was measured. On a
synthetic stress set of 143 perturbed gold headings the truth is a candidate
143 times (M3's Jaccard: 97), the top proposal 127 times, outright 10. Pinned
by `palomar.agent-match-test` and
`palomar.reconcile-test/a-model-scores-each-candidate-and-explains-it`,
`a-candidate-below-the-abstention-threshold-is-not-proposed`,
`an-undated-exact-heading-is-not-abstained-for-being-undated`,
`between-the-thresholds-a-proposal-is-flagged-for-review`,
`at-most-one-outright-proposal-per-mention`,
`an-uncalibrated-score-is-never-written-as-a-confidence`,
`a-calibration-holds-only-for-the-pool-it-was-measured-on`,
`align-agents-writes-no-confidence-without-its-proof`.

## Amendment (lot M5, 2026-11-09): constrained clustering, and the verdict store's revisions

§2 and §4 said a verdict is an assertion and a wrong merge is retracted, not
un-merged; they did not say how mentions are grouped into persons. Until
M5, every grouping closed through an authority (`reconcile-agents`,
`apply-agent-links`, a reader accepting every top proposal) with no check,
and SKOS's transitivity of `exactMatch` closes the same way. ADR 0057
decides:

1. **Constrained clustering** (`agent-cluster/cluster`). Accepted claims
   (alignments, memberships) are hard must-links; a component of them that
   holds two cannot-linked items is a conflict, held back and reported,
   neither side winning. Then greedy agglomeration of the outright
   proposals by decreasing match weight, a merge refused when it would put
   two cannot-linked items together — a candidate rejected for its dates
   (M3), a rejected alignment, two different authorities, two incompatible
   dated items (an authority's dates included). On a tie the lower id wins,
   and it is reported. Same-record, different-role mentions are not a
   constraint (not derivable from the IR).
2. **Policy**: a curator's decision outranks the machine; besides it, only
   outright (`:propose`) decisions join; a `:review` decision is a
   candidate merge, listed, never applied. A curator confirms one with
   `reconcile/confirm-identity`.
3. **Revisable, in the IR**: the clusters are `skos:exactMatch` claims
   from each local agent to a `:reconcile/person` node minted per cluster
   and batch, `:proposed` until accepted. Re-clustering rejects (through
   `palomar.curate`, with the reason) the pending memberships that no
   longer hold and reopens a claim proposed again, never copying it (§4:
   monotonic accumulation, no deletion; a record holds one claim per
   fact). An accepted claim is never revised by the machine. A refused
   alignment goes to `:needs-review` with the reason; a conflict among
   accepted claims is a diagnostic. Local agent ids never change.
   Constraints hold within a batch only (§5: no store yet).

Measured on M2 (`docs/eval/person-reconciliation.md`, M5): the naive
closure of every proposal (outright and for review) makes 464 false
merges; the constrained clustering of the same links none (M4's top-1
numbers: 1 113 pairs, 0 false merges); under the policy alone (outright
only) both give 923 pairs, recall 0.829, the 25 mentions for review left
alone. 9 synthetic chains (same-surname authorities with incompatible
dates, bridged by an undated record): the closure merges 9, the
constrained clustering 0, each refusal a reported tie. Pinned by
`palomar.agent-cluster-test`.

## Amendment (lot M7, 2026-11-09): typed events as contrary evidence, beyond life dates

M3 compared life dates with life dates. A record says more: that its
person made a work at a date, or that an edition of it appeared at another.
Read without types, those dates would block the right authority. The rule is
the M3 one: **a false block is worse than a missed one**. This amendment
was revised after a contradictory review: its first version also blocked a
creation dated after the candidate's death, and posthumous making
catalogued under the artist's name made that rule unsafe.

1. **A typed event** is `{:kind :role :date :source}` (`palomar.agent-events`),
   `:date` an interval of years (`agent-dates`, absent when not read),
   `:source` the record, field or event and the evidence it was read from.
   A small namespace, because the types already exist in two places and
   only need reading, not deciding again:
   - **LIDO** (`lido-mentions`, since lot N1 `palomar.mentions`, ADR
     0058): the importer's event kind
     (`plugins.lido/event-kinds`, ADR 0056 §7: still code) and the actor's
     responsibility (creator or participant, ADR 0054, by role and
     attribution qualifier, K2). A production or design the actor carried
     out as its maker is a `:creation`; "after" an `:influence`; a former,
     related or uncertain attribution, or a later making (a posthumous cast
     or impression, a restrike, a replica, a reproduction, a version, a
     forgery: the qualifier vocabulary's new `:attribution/later-making`) an
     `:attribution`; any other actor of it (a printer, a publisher, a
     colourist, a caster or founder, "Text by", "Hand colored by", "Cast
     by", "Impression by", "Reissued by") an `:activity`; another event an
     `:acquisition` by its kind, an `:exhibition` or a `:publication` (about
     the object) by the LIDO event-types vocabulary, data, else an
     `:activity`. An actor is its occurrence (ADR 0056 §3): a mention
     carries the one event it is named in, and its own vital dates as
     `:dates`. A group (a group type, an institution, a manufacturer's
     role) is flagged: it has no life.
   - **MARC21, INTERMARC, UNIMARC** (`edition-events`): the fields
     `plugins.events/field-events` (ADR 0036) says state a Manifestation's
     publication or production (260/264 `$c`, INTERMARC 260 `$d`, UNIMARC
     210/214 `$d`), and MARC21 008 Date 1 for a published resource: each a
     `:publication`, the edition's date. No importer reads a work's creation
     date (046, a dated F28) yet, and the INTERMARC 008 is not read: no
     bibliographic record gives a creation.
   - **An event date is read no narrower than the source**: the earliest
     and latest (one end open when only one is given), widened by the
     20-year margin when the display date says "ca.", "c1775", "1775 c.",
     "um", "gegen" or "etwa", an end opened when it says "after" / "nach" or
     "before" / "vor"; else the display date (a point, a span, `1880-85`
     read 1880-1885). Unread when a given part is not read, when any part
     carries an era marker before the common era, when the display names
     two dates it does not read as one span ("Modell um 1881, Guss 1922":
     which is the event, it does not say), when it says the work is undated
     ("undated", "n.d.", "o.J.", "s.d.", "ohne Jahr", "sans date",
     "undatiert": the earliest and latest that come with it are the
     museum's derivation from the artist's life, circular), or when it ends
     before it begins.
2. **The constraint, one-sided** (`agent-events/compatibility`): a creation
   blocks only when it ends **more than 10 years before the candidate's
   birth** (`birth-margin`, not the 2-year tolerance: a date read from a
   model, a plate or a design may precede a namesake's birth by a few
   years). **A creation after death is never evidence.** The asymmetry is
   the corpus's: museums catalogue a bronze cast in 1925 under Rodin
   (1840-1917) as the production's sculptor, a Rembrandt impression pulled
   in 1906, an Atget printed in 1956, a Thonet chair reissued in 1985, a
   19th-century copy under Raphael, often with no qualifier and the cast's
   or impression's date as the event's; nothing reads that reliably. Making
   before one's birth has no such reading. Nothing else blocks: a
   publication, an exhibition or an acquisition (after death is common); an
   edition date, even before a birth; "after X"; an activity; a group's
   event, or any event against a group candidate; a floruit; an undated or
   untyped event; an undated candidate; two mentions' events between
   themselves (`:unknown`).
3. **Self-contradiction is no evidence.** An event incompatible with the
   mention's own stated `:dates` (a LIDO record's vital dates for its
   actor) is dropped before matching and clustering
   (`consistent-events`): a record that contradicts itself says nothing of
   anyone else.
4. **Where they apply.** `reconcile/agent-candidates` takes a mention's
   `:events`; a candidate whose birth they precede is rejected like an M3
   date block, never weighed (name-only or scored): `:reason
   :incompatible-events` (dates first when both are), the evidence `:events
   {:verdict :checks}` with the event, the interval and the birth compared;
   the proposal's evidence and `align-agents`' bindings (`?events`) state
   the verdict. In `agent-cluster`, an item's consistent `:events` against
   another's `:dates` is a derived cannot-link `:incompatible-events`
   (`clustering-input` takes the events as `extra :events`). **It
   constrains proposals only: a curator wins** (as in M5). Accepted links
   whose cluster holds an events constraint are applied, not held back as
   a conflict; the constraint is reported (`:event-conflicts`, and a
   `:reconcile/identity-events-conflict` warning on each record involved).
   A dates conflict among accepted links is still a conflict.
5. **Where a mention's events come from**: the record it is in, typed for
   its own role there. A LIDO actor's own production, carried out as its
   maker, is the only one that can constrain; a bibliographic record's
   edition dates say only that its author may be dead. No spoke and no CLI
   command passes events yet, as for dates (M3): the evals do.

Measured (`docs/eval/person-reconciliation.md`, M7): on the BnF gold every
mention carries an edition event (135 dated, 62 after its authority's
death) and they reject nothing: task A is M3's. On a synthetic set built on
the 21 pool authorities with a birth and a death, a creation 30 years
before birth is blocked 21 of 21 times (a cannot-link each); a creation 8
years before birth, in life, 30 years after death, a posthumous edition and
an edition before birth 0 of 21. On five YCBA LIDO records nothing is
blocked; on 500 (not committed, run once), none. That last measure is
weak: 72 of those records' productions are displayed "undated", their
earliest and latest derived by the museum from the artist's life, and are
now no date; the one rejection an earlier run made rested on one. The risk the narrow rule leaves: a creation dated long
before the birth of the right person, by a source that misreads a model's
or a design's date as the namesake's work. Pinned by
`palomar.agent-events-test`.

## Amendment (lot M8, 2026-11-09): collective reconciliation, from validated links only

After M4, 26 top proposals on the BnF gold were for review because another
candidate was above 0.5: 20 "Férat, Jules" (Verne's illustrator, born a
year from Verne) against Jules Verne, three "Víctor Hugo" authors against
each other, Hetzel against his pseudonym Stahl, and the undated
"Dumas, Alexandre". The record says more than the name and the dates: Férat
is the illustrator (`$4 0440`) of books whose author (`0070`) is Verne.
This amendment lets a mention's relational neighbourhood be evidence, under
one rule above the others: **a link is never supported by evidence that
depends on it**. It was revised after a contradictory review; the rules
below are the revised ones.

1. **Anchors, frozen** (`palomar.agent-relations/freeze-anchors`). The
   relational evidence reads *anchors*: links a curator accepted (an
   accepted alignment, a confirmed identity) or a source asserted, `{:id
   :mention :record :authority-id :role :event :works :support}`. A
   proposal is never one (`:not-validated`), nor a link without its
   mention or authority (`:incomplete`). Whatever their order, two
   different links under one id are both refused (`:duplicate-id`), and a
   mention linked to two authorities has all its links refused
   (`:conflict`). The set is fingerprinted, and `reconcile/agent-candidates`
   (`{:model m :anchors a}`) takes it only unchanged; it is frozen before a
   pass and never updated by the pass's outputs.
2. **No circular confirmation, carried end to end.** A decision's
   **support** is every mention that the relational evidence of any of the
   mention's candidates read (a competitor pushed down counts): each
   proposal states it (`:support`), `align-agents` writes it (`?support`,
   with `?mention` and `?role`), curator acceptance and `confirm-identity`
   keep it on the accepted claim (the exact match keeps the close match's
   provenance bindings), and `accepted-links` gives each link the `:id`,
   `:mention`, `:role` and `:support` `freeze-anchors` reads. The evidence on
   mention m reads no anchor of m, nor of any mention whose anchors'
   support reaches m, transitively (`excluded-mentions`). A loop through
   curation — m's decision rests on p's link, m is accepted, p is judged
   again — is excluded: p cannot read m's link. A mention without an `:id`
   has no relational evidence.
3. **Neighbourhoods.** A mention's: the anchored other agents of its record
   (for LIDO, of the same event), the authorities they are linked to, its
   works where the caller has them. A candidate's: its roles in its
   validated occurrences; the authorities and works it co-occurs with
   through anchors in other records; what its authority record states,
   resolved to pool entries by `:record-number` (`resolve-relations`, in the
   matcher): a related agent is a neighbour, a pseudonym another identity
   (not context). A relation to neither a pool entry nor an anchored
   authority is no evidence. **Hubs**: an authority anchored in more than
   10 % of the records the usable anchors cover (`hub-share`) is no
   neighbour, on either side — the publisher of half a batch says nothing
   of who a namesake is. The INTERMARC authority reader reads 331 (a
   pseudonym), 321 (a work), 5xx and 320 with their `$3` — a 320 only with
   its `$0` relation code (a 320 without one, a museum holding the
   person's manuscripts, is not a relation between agents); a field read
   in part stays listed as unread. GND relations are not read.
4. **Two features**: `:role` (`:same-role` when the mention's `$4` is among
   the candidate's validated roles, `:other-role` when the candidate has
   some and not this one; a field without `$4` has no role, never its tag)
   and `:neighbours` (`:shared`, `:not-shared`). `:unknown` and
   `:not-shared` **weigh 0 by rule**: missing context is no evidence, and
   nor is the absence of a shared neighbour. The other levels weigh as in
   M4 (log2(m/u) of observed counts, a bound below 5 on one side, 0 when
   neither side is measured; an agreement never below 0, a disagreement
   never above), **leave-one-family-out, held out truly**: the training
   pairs' features read no anchor of the held-out family. m from the
   training families' true pairs, u from their other scored candidates
   (labelled: the pool against itself has no record context). The weights
   are added to M4's before the posterior; the decision rules are M4's.
5. **M4 compatibility.** Without `:anchors`, the matcher reads none of a
   mention's relational keys (`:id`, `:role`, `:event`, `:works`): whole
   proposals and `align-agents` output are M4's (one name twice in one
   record, as author and translator, gives M4's alignments). With an anchor
   set that says nothing of a mention, its decisions, scores and evidence
   are M4's; its proposals carry its id and role.
6. **Confidence.** A posterior relational evidence entered is a confidence
   only when the model's calibration report was measured with the same
   relational weights and anchor set (`relations-fingerprint`, checked by
   `agent-match/proven-confidence`).
7. **Not changed**: the date and event blocks (M3, M7); the clustering (M5);
   the identity relation of ADR 0057 (not amended). No spoke and no CLI
   command passes anchors yet.

Measured (`docs/eval/person-reconciliation.md`, M8) on the 142 M2
mentions, in two oracle settings (the gold of every mention outside the
judged record, or of every other mention) and two realistic ones built by
Palomar's curation path (the curator accepts the 111 right outright
proposals; and also confirms one review mention per name, chosen by its id,
6 of 7 confirmable): **the relational evidence changes no decision of any
candidate in any setting**. Férat stays for review everywhere, even after
one of his mentions is confirmed: his family's fold, trained without any
"others" link, sees a different role once on each side, so `:other-role`
weighs 0, unmeasured. Where it is measured, in the other folds, it weighs
-0.7 to -3.1 (by fold and setting). The leakage check fakes the judged mention's gold at the
source of the anchors (the oracle's gold, or the gold the curator reads)
and finds its relational evidence unchanged, 141 of 141, in every setting.
No confidence is written. Pinned by `palomar.agent-relations-test`.

## Amendment (lot N1, 2026-11-11): one mention per occurrence, from one extraction

What M3–M8 call a mention — `{:name :dates :events :record :id :role}` —
is now produced in one place for every format: `palomar.mentions`
([ADR 0058](./0058-a-mention-is-one-occurrence.md)). Each occurrence (a
MARC-family field, a LIDO `actorInRole`, the heading of an authority
record, a DC or MODS name) is read from itself alone, with a positional id
(record, field, occurrence), its roles verbatim and relator codes (the
first as `:role`/`:relator`, every one as `:roles`/`:relators`), its stated
kind, its life dates as given, and its typed events. Subjects and
provenance (a copy's former owner) are typed apart and left out by
default. The local node has one rule, shared with `align-agents`: the
mention's stated kind, else the authority's entity type, else a Person (a
LIDO actor's node the rules'); but `align-agents` still finds agents
through the canonical floor's names, so an INTERMARC 7XX/110/710 or a
UNIMARC `$a, $b` heading gets no alignment before lot N2.

What is tested: a golden file of 15 real records written by an independent
reader of the XML; the origin of each mention's dates, roles and name in
its own field on the real INTERMARC corpus; that the floor is not read;
that `align-agents` mints each reachable mention's `:local`. The eval of
M2–M8 reads its mentions from the extraction, and every number it asserts
is unchanged (`palomar.eval.person-reconciliation-test`).

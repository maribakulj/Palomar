# Spike — offline entity resolution (post-WP-0)

**Question.** The showcase-boundary eval showed only 11.2 % of records are enriched
beyond a bare Manifestation, because WEMI derivation needs the `f145 $3` authority link
and 0/220 off-showcase records carry it. How far does *offline* clustering (no
external authority call) close that recall gap, and what must an entity-resolution
ADR actually buy?

**Method.** `dev/spike/entity_resolution.clj` (throwaway), run on the real importer.
The decisive move: on the Madame Bovary fixture we now have data.bnf.fr ground truth
(28 manifestations → 1 Work), so we can replay keying strategies that **ignore**
`f145_3` and score each against the gold (pairwise precision/recall over the 30
records). Reproduce with `clojure -M:sandbox -i dev/spike/entity_resolution.clj`.

## Experiment 1 — keying strategies vs the gold (Madame Bovary)

| strategy                | clusters | tp  | fp | fn  | precision | recall | F1   |
|-------------------------|---------:|----:|---:|----:|----------:|-------:|-----:|
| `link` (f145_3) **[T0]** | 3       | 378 | 0  | 0   | **1.000** | **1.000** | 1.000 |
| `author-only`           | 2        | 378 | 28 | 0   | 0.931     | 1.000  | 0.964 |
| `author + norm(title)`  | 9        | 163 | 0  | 215 | 1.000     | **0.431** | 0.603 |
| `author + title-prefix2`| 4        | 198 | 0  | 180 | 1.000     | 0.524  | 0.688 |

The 28 linked editions carry **7 distinct normalised `f245` titles** ("Madame
Bovary", "Madame Bovary, moeurs de province", "Madame Bovary, roman", "Madame
Bovary : texte intégral", …).

**Reading — there is no offline string key that wins both axes:**

- **`link`** gets P = R = 1.0, but fires for only ~11 % of records (it *is* the
  authority's reconciled decision; we just transcribe it).
- **`author-only`** reaches recall 1.0 but loses precision: it merges the study
  guide `cb32056819r` (also by Flaubert) into the novel → 28 false-merge pairs.
  Here that costs only 0.07; on a prolific author it is catastrophic (all of Hugo's
  works would collapse into one "Work").
- **`author + norm(title)`** keeps precision but recall craters to **0.43**: exact
  title normalisation *shatters* one Work into 8 pieces, because transcribed `245`
  titles genuinely vary. Coarsening to a 2-token prefix only lifts recall to 0.52.
- The precision of the title strategies is **optimistic** — the fixture is
  essentially one Work, so sub-clustering cannot create a cross-Work false merge.
  The real over-merge risk is the one `author-only` exhibits.

What collapses the 7 title variants into one Work is the **uniform title**, and the
uniform title travels *with* the `f145` link we don't have. That is the crux: the
information needed to reconcile is precisely the information missing off-showcase.

## Experiment 2 — off-showcase in-corpus shape (no ground truth)

| corpus                  | records | eligible (author-id + title) | clusters | multi-edition |
|-------------------------|--------:|------------------------------:|---------:|--------------:|
| Hugo (grab-bag)         | 30      | 14                            | 12       | **1**         |
| Gracq                   | 30      | 13                            | 13       | 0             |
| Gracq analytiques       | 20      | 11                            | 11       | 0             |
| monographs              | 30      | 7                             | 7        | 0             |

The "Hugo" corpus is a real search grab-bag (authors *named* Hugo, not Hugo's
works). Offline `author-id + norm(title)` recovers exactly one true multi-edition
Work — **"Mon grand-père" ×3** (author `14940601`) — at high precision. Everywhere
else, eligible records are all singletons: nothing to merge. The recoverable
in-corpus dedup is **real but narrow and corpus-dependent**.

Note the author dimension is *often already reconciled* (`f100_3` present on
14/13/11/7 records); the systematic hole is the **Work**, not the agent.

## What this dictates for the ADR

1. **Reconcile-to-authority (T1) is the spine, not in-corpus fuzzy clustering.**
   Only the authority link gets precision *and* recall, because it carries the
   uniform title that variant `245`s cannot reconstruct. The ADR's primary target is
   **Work reconciliation** (resolve *author-id + title* → a Work authority such as
   IdRef/VIAF/BnF), since the agent is frequently already identified.
2. **Offline exact `(author-id + norm-title)` is a *recall aid*, not an identity.**
   It safely merges obvious duplicate editions ("Mon grand-père" ×3) and can
   *propose* candidates, but as the primary Work key it either under-merges (exact
   title, R = 0.43) or over-merges (author-only, P < 1). It must therefore be
   **confidence-gated and revisable** — i.e. emitted as `:proposed` equivalence
   assertions, never as a destructive merge.
3. **The trade-off is intrinsic, so the model must record evidence, not verdicts.**
   Because every offline strategy sits somewhere on the P/R curve, the right design
   is to attach *provenanced equivalence claims with scores* (strategy C) and let the
   view/curator resolve them — exactly the assertion substrate we already have. This
   is the empirical justification for an ER ADR built on equivalence-as-assertion +
   revisability, with external reconciliation pushed to the boundary.

**Correction to the earlier armchair sketch.** My pre-spike 3-tier note led with
"resolve the *author* against VIAF". The data says the author is usually already
resolved; the missing piece is the **Work**, and in-corpus clustering is a narrow
dedup aid rather than a tier in its own right. The ADR is re-pointed accordingly.

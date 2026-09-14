# Entity resolution — what string clustering can and cannot do (measured)

The recall question behind ADR 0018: off the authority link, can clustering by
*(creator, title)* recover the work grouping? Measured on two independent corpora.

## OpenLibrary — independent corroboration (`palomar.eval.openlibrary-er-test`)

~4 555 editions / ~328 works / 18 prolific authors, from OpenLibrary's Work↔Edition
model (CC0) — **independent of the BnF**, so gold and input no longer share one
signal. Our string-key strategies vs OpenLibrary's `work_key`:

| strategy              | precision | recall | F1   |
|-----------------------|----------:|-------:|-----:|
| `author + title`      | 0.643     | **0.369** | 0.469 |
| `author + title-pfx2` | 0.686     | 0.597  | 0.638 |
| `author-only`         | **0.049** | 1.000  | 0.093 |

Read honestly:

- **Exact title — genuine precision ≈ 1.0, recall ≈ 0.37.** By construction every
  one of its 16 450 "false merges" is two editions with the *same author and
  identical normalised title* that OpenLibrary filed under different works — i.e. a
  gold self-split, not our error. The 0.643 is the gold's noise, not ours. The real
  limit is **recall**: variant titles (subtitles, languages, "Illustrated") are
  missed.
- **Loosening** to a 2-word prefix lifts recall to 0.60 but now makes *genuine*
  cross-work merges (precision 0.686). The classic trade.
- **author-only** collapses prolific authors' whole output into one cluster
  (precision 0.049) — the over-merge extreme.

**The gold is itself unreliable.** OpenLibrary's work-merging is incomplete: 59
same-author/same-title groups are split across >1 `work_key` here — *Frankenstein*
across **14** works, *Moby Dick* across 6. So even the best open Work/Edition source
fragments works badly: there is no clean, broad, open work-grouping gold. (Recall is
therefore the trustworthy signal; precision-vs-OpenLibrary understates us.)

It is **directional, not definitive**: 18 canonical authors, English-edition-heavy
— the opposite of the long tail. What generalises is the *shape*, not the number.

## Consistent with the rest of the arc

- **Madame Bovary, vs data.bnf gold** (`bovary-c2-test`): with the `145 $3`
  authority link, P = R = 1.0; ignoring it, the same string-key fallback recalls
  ~0.43 (`docs/spikes/entity-resolution.md`). Same shape, on BnF data.
- **data.bnf off-showcase**: of 180 records, 100 are present and only **7 %** carry
  a work link — authority links are sparse off the canonical core.
- **Wikidata via ISNI**: 2 of 22 niche authors present, none joinable to our records
  — the tail is uncovered there too.

## Conclusion (for ADR 0018)

String-only clustering **cannot** get both precision and recall: exact match is
near-perfectly precise but recalls a third to a half; loosening trades precision
away; author-only is unusable. The authority link is the only thing that buys both
— which is exactly why the certified path (`:asserted`, the embedded link /
transcription) and the proposal path (`:proposed`, everything fuzzy) are split by
the commit policy (D7), and why ADR 0018's reconcile-to-authority is the spine.

A clean, broad work-grouping gold does **not** exist in open sources (BnF, Wikidata,
OpenLibrary all fail differently) — so recall on the long tail cannot be *certified*
against an external authority. That scarcity is itself the finding: reconciliation
there is intrinsically hard and must be evidence-gated (certify the proven, propose
the rest, document the abstention), not benchmarked into existence.

## Agents — certified reconciliation on real BnF data (`palomar.eval.bnf-agent-reconciliation-test`)

The same lesson on the *agent* axis, and the cleanest demonstration yet. 100 real
INTERMARC records (`bib.author all "victor hugo"` / `"jules verne"`, BnF SRU): 43
carry a main-entry ISNI (100 `$1`), reconciled by that authority id to **12
distinct agents**.

The point is **precision by construction**. "Victor Hugo" / "Jules Verne" as a
*search string* match a dozen distinct people — the novelist **Jules Verne** vs his
biographer **Jean Jules-Verne**; Victor Hugo's grandson (the painter Georges-Victor)
and son (the translator François-Victor); a cohort of Latin-American authors *named*
"Víctor Hugo …". Keying on the ISNI keeps every one of them apart; a name match
would have conflated them into a single blob. (The writer Victor Hugo himself does
not even surface as a main-entry agent in this slice — honest coverage, not a merge.)

And the negative pole, the perfect illustration of why a *determinate* id is
non-negotiable: a naive name-search for "Victor Hugo" in the authority fallback
resolved to **`Q1459231` — the Victor Hugo *metro station* in Paris** (no ISNI). A
name string can match a namesake, a descendant, **or a non-person**. So a name-only
agent never enters the certified set; only an authority id earns `:asserted` (D7).
This is reconcile-to-authority (ADR 0018) shown true on real data.

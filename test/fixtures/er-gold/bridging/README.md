# ER gold — bridging (Work grouping *independent of f145*)

The gold the C2 caveat asked for. `docs/eval/wemi-fidelity.md` notes that the
C2 Bovary score is high because gold (`workManifested`) and input (`f145 $3`) are two
serialisations of the **same** BnF work link — a transcription check, not a test of
*bridging* (recovering the Work when the source carries **no** link). Independent
evaluation "will need a gold that is **not** derived from `f145`".

## What it is

`gold_workmanifested.csv` — data.bnf.fr's own `rdarelationships:workManifested`
grouping, queried (SPARQL, POST) for the **281 manifestation ARKs of Palomar's own
INTERMARC fixtures** (the proven C2 query structure, two `VALUES` blocks + `UNION`).
Columns: `sourceArk, manifestation, manifestationTitle, work, workTitle`. 43 of the
281 ARKs carry a data.bnf work link; the rest have none (work links are sparse —
cf. the showcase-boundary eval). Licence Ouverte.

## The non-circular subset is the real test

Of the 43, the records **without** `f145` are the genuine bridging cases — their
`workManifested` grouping is *not* a re-serialisation of a link the record carries.
data.bnf groups them anyway, often onto a `temp-work/<hash>` URI (an inferred Work
with no stable authority ARK) — exactly the off-showcase situation. The f145-bearing
records (Bovary, L'île mystérieuse, on stable Work ARKs) are a **circular control**:
their gold *is* their f145 link.

## Measured (`palomar.eval.bridging-test`)

Exact `(author + normalised title)` clustering vs the gold:

| subset | n | precision | recall | reading |
|--------|--:|----------:|-------:|---------|
| **non-f145 (independent)** | 12 | **1.00** | **1.00** | *Mon grand-père* ×3 + *Souvenirs d'un matelot* ×3 — identical-title editions, recovered exactly |
| f145=Y (circular control) | 31 | 1.00 | 0.45 | Bovary's 28 editions shatter into **7** title-clusters (variant titles) |

So, on an independent gold: **precision is perfect (no over-merge), and exact
clustering recovers identical-title editions** — the first non-circular bridging
number. The variant-title **recall ceiling** (ADR 0018 / the OpenLibrary eval) shows
on the Bovary control. Honest limit: the independent subset is small and its
multi-edition works happen to share an exact title, so it does **not** stress
variant-title recall on an independent gold — consistent with ADR 0018's finding
that a broad, clean Work gold does not exist in open sources.

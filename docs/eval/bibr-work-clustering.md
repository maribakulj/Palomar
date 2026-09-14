# Independent WEMI-derivation eval — Palomar vs the BIB-R benchmark

This measures Palomar's MARC21 → WEMI Work clustering against an **independent,
third-party** gold: the **BIB-R "Benchmark of FRBRization solutions"**
(http://bib-r.github.io/, CC BY-NC). It is the non-circular counterpart that
[`wemi-fidelity.md`](./wemi-fidelity.md) §3 says is needed, and a
**third corpus** for ADR 0018's recall ceiling — after Bovary/data.bnf and
OpenLibrary.

Reproduce: `clojure -M:sandbox:test/unit -n palomar.eval.bibr-work-clustering-test`.
Fixtures + derivation + licence: `test/fixtures/bibr-gold/README.md`.

---

## 0. Why this is not circular

The C2 eval scores P = R = 1.0, but its gold (`workManifested`) and its input
(`f145 $3`) are two serialisations of the **same** BnF link — so it is a
*transcription* check, not evidence of inference, and it says so. BIB-R removes that
circularity: it is a **hand-curated MARC → FRBR/RDA gold** with no dependence on any
link Palomar reads. Palomar sees only flat MARC; the gold is the cataloguer's
independent WEMI grouping.

## 1. What Palomar does here, and what the gold demands

- **Palomar** projects flat MARC21 by the *floor* rung (`lrmoo.project`): the Work
  key is `agent + norm(uniform title when present, else transcribed 245 title)`, and
  a Work is minted only when a creator is present. The **uniform-title bridging** (the
  MARC 240 step → `:canon/uniform-title`, ADR 0003 growth) is what lets two editions
  with *different* transcribed titles but the *same* uniform title cluster to one
  Work.
- **The gold** groups by the cataloguer's *uniform* Work (its work URIs are
  author+uniform-title slugs). It unifies **transcribed-title variants**,
  translations and integral/abridged editions of one Work — e.g. La Fontaine's
  *Fables* across `Fables`, *Les plus belles fables*, *Fables, livres I–VII…*; an
  article variant *Le capitaine Fracasse* / *Capitaine Fracasse*; a French/Spanish
  translation pair.

The remaining gap is the **title-variant recall** the bridging still cannot reach —
where the uniform title is itself inconsistent across editions (La Fontaine's *Fables*
carries three different uniform titles in this catalogue) or absent — measured here on
an independent corpus.

## 2. Result

System: ingest the 560 BIB-RCAT MARC records, cluster by minted F1 Work id (records
with no creator are singletons). Gold: the BIB-R Work grouping. Records are joined to
the gold **by normalised title**, unambiguous titles only. Scored pairwise over the
joined records.

| key | joined | tp | fp | fn | precision | recall | F1 |
|-----|-------:|---:|---:|---:|----------:|-------:|----:|
| transcribed title only | 362 / 560 | 385 | 0 | 112 | 1.000 | 0.775 | 0.873 |
| **+ uniform-title bridging** | 362 / 560 | 409 | 0 | 88 | **1.000** | **0.823** | **0.903** |

- **Precision = 1.000, before and after.** Palomar never false-merges two distinct
  gold Works — the `agent + norm-title` key is conservative, and bridging on the
  uniform title introduced **no** false merge (consistent with every prior eval).
- **Recall 0.775 → 0.823.** Uniform-title bridging recovers 24 more same-Work pairs
  (fn 112 → 88) at no precision cost: the named "D-series" recall step, measured. The
  residual 88 missed pairs are editions whose uniform title is itself inconsistent or
  absent. Higher than Bovary's 0.43 (this children's-literature catalogue repeats
  exact titles across editions far more than the 28 heterogeneous Bovary editions),
  still below 1.0 — the ceiling is real and corpus-dependent.

## 3. Honest scope — the join, stated not hidden

The MARC `001`s carry no link to the gold's title-slug URIs, so the join is
**by normalised title**, and a title the gold spreads across several work URIs is
**excluded**. The gold itself fragments some works (La Fontaine's *Fables* appears
under several work URIs), which caps clean coverage at **362 / 560 ≈ 65 %**. The
metric is therefore over the cleanly-joinable subset, and the coverage is asserted
as a first-class number, not buried. This does not bias precision (Palomar makes no
false merge on the joined set) and only narrows the recall measurement to titles the
gold resolves unambiguously.

## 4. Bilan

- **Corroborated, independently — and acted on.** P = 1.0, R = 0.82 on a third
  corpus with a gold that owes nothing to `f145`: precision-first, and the
  uniform-title bridge ADR 0018 names as the recall lever is now *built and measured*
  (0.775 → 0.823 here), not just hypothesised. The floor still cannot invent a
  grouping the source did not carry — where the uniform title is inconsistent or
  absent, recall stops.
- **Bounded by the data, not by the model.** The residual recall gap is the
  catalogue's own uniform-title inconsistency; the 65 % join coverage is a property
  of aligning two catalogues with no shared identifier. An id-aligned gold, or
  cleaner uniform titles, would raise both — neither is a projection limitation.
- **Data credit.** BIB-R (bib-r.github.io), CC BY-NC; used here for non-commercial
  evaluation with attribution.

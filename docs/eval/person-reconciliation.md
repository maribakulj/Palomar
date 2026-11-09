# Person reconciliation — the baseline, dates as contrary evidence, and a Fellegi-Sunter score (measured)

`palomar.eval.person-reconciliation-test`, on 150 real BnF records. Corpus and
gold: [`test/fixtures/er-gold/bnf-persons/`](../../test/fixtures/er-gold/bnf-persons/README.md).

## Why

The other agent evals do not measure person identity. `entity-resolution.md`
measures Work clustering; `bnf-agent-reconciliation-test` shows that grouping
by ISNI keeps people apart, which holds by construction. Nothing so far says how
Palomar's name-based reconciliation, the path for a name with no authority id,
does on real homonyms. This eval measures it as the code stands. Its numbers
are the **baseline** later lots will be measured against (dates as contrary
evidence, a Fellegi-Sunter score, constrained clustering). Lot M3 (dates as
contrary evidence) is measured in [After dates (M3)](#after-dates-m3), lot M4
(a Fellegi-Sunter score, abstention, calibration) in
[A Fellegi-Sunter score (M4)](#a-fellegi-sunter-score-m4), lot M5
(constrained clustering) in
[Constrained clustering (M5)](#constrained-clustering-m5), and the same
mentions against a GND snapshot (lot M6) in
[On a GND snapshot (M6)](#on-a-gnd-snapshot-m6), and typed events as
contrary evidence (lot M7) in [Typed events (M7)](#typed-events-m7). The
numbers are measurements, not a guarantee: no row in `guarantees.md` cites
them (the behaviour of the date block, of abstention and of the confidence
rule, of the clustering's constraints and revisions, and of the events'
constraints, is guaranteed, by unit tests).

## The corpus

- **150 INTERMARC bibliographic records** from the BnF SRU API, 50 per query:
  `bib.author all "victor hugo"`, `"jules verne"`, and
  `(bib.author all "alexandre dumas") and (bib.recordtype any "mon")`.
- **142 person mentions**: every `100` (90) and `700` (52) field. The other 7xx
  (collectivities, languages, series…) are not persons and are left out, and
  so are the person subject headings (`600`, see Limits).
- **The gold is `$3`**, the BnF authority record the cataloguer linked the field
  to. 141 mentions have one, citing 37 authorities; one mention (a `100`
  "Dumas, Alexandre") has none and is **undetermined**.
- **The pool**: the 33 person authority records that the BnF SRU returns for
  those 37 ids. Each is one `{:id :label :variants}` entry: the `100` heading
  `$a, $m` as label, the `400`s (pseudonyms and other forms) as variants. The
  four others (10018888 Rafina, Gesner; 12612031 Harispe, Patrice; 14380255
  Paltsits, Victor Hugo; 16814325 Hayat, Eric) are real BnF person authorities
  (each ARK opens a "Notice de personne" in the catalogue), but the SRU does not
  serve them for `aut.recordid`. Their 5 mentions are **not in the pool**: in
  this closed pool the right behaviour for them is to abstain; against a
  complete pool it would be to link each to its own authority.
- **Families**, by the mention's surname: Dumas 51 mentions (Alexandre père
  1802-1870 and fils 1824-1895, both catalogued "Dumas, Alexandre"; Adolphe;
  Robert-Dumas Alexandre and Charles; Dumas de Rauly), Verne 29 (Jules, Jean
  Jules), Hugo 8 (Victor, François-Victor, Georges-Victor), Hetzel 2
  (Pierre-Jules, Louis-Jules), others 52 (among them P.-J. Stahl, a pseudonym of
  Pierre-Jules Hetzel that the BnF holds as an authority of its own, and ten
  authors whose forenames are "Víctor Hugo …").

What the gold is not: an independent annotation. It is the BnF's own linking, a
human decision; where a cataloguer chose the wrong authority, this eval counts
the wrong answer as right. Nor is it a complete inventory of persons: the SRU
serves a separate "Hayat, Éric" authority (13168570), not cited here and not in
the pool, which may be a second authority for the person of 16814325.

## Method

The records go through Palomar's INTERMARC importer and its `:normalize` phase,
as in `bnf-agent-reconciliation-test`. The name a matcher sees is `$a, $m`: for
a `100`, Palomar's own `:canon/agent`; for a `700`, which the classic INTERMARC
(SRU) mapping does not carry to `:canon/agent` (`:map/intermarc-agent` reads the
main entry only; the INTERMARC-NG spoke does carry its `700 A pour créateur`),
the same
recombination, read from the field the importer keeps. `$3` is kept as the
truth and never reaches a matcher, nor does `$1` (ISNI); the name-only
baseline does not see `$d` (dates) either: a test spies on what task A passes
to `propose-agent-links` and checks that no `$3`, `$1`, ISNI, date or
authority number appears in a name, label or variant, and that pool entries
carry only `:id :label :variants`. The authority records go through the same
importer. In M2 a small reader in the test tree built the pool from them;
since M3 it is Palomar's INTERMARC authority reader
(`authorities/read-pool :intermarc`), and a test checks that it gives the
same pool, entry for entry (`the-pool-is-read-by-palomar-s-intermarc-authority-reader`).
The metrics are pure functions, tested on hand-made inputs
(`palomar.eval.person-reconciliation`).

### Task A — mention → authority

Each mention's name goes through `reconcile/propose-agent-links` (a Jaccard
over name tokens against the label and the variants, threshold 0.5) against the
pool. On the 136 mentions whose authority is in the pool:

- **recall@1 / @5**: the truth is the top proposal / among the first five;
- **false link**: the top proposal is another authority. It is a **homonym**
  link when that authority has the truth's surname;
- **abstention**: no proposal;
- **tied**: another proposal shares the top score.

**precision@1** is the share of right top proposals among every determined
mention that received one, including those whose authority is not in the pool,
where any link is wrong.

### Task B — mention ↔ mention, no pool

The 141 mentions with a `$3`, paired: 1 114 pairs share an authority.

- **record-scoped** is what Palomar does today with an agent that has only a
  name (ADR 0041): its node is minted from the record and the name
  (`reconcile/align-agents`' local agent, the Linked Art actor), so two
  records' same names are never one person.
- **same-name** is the simplest name rule, same normalised name (`text/norm`)
  → same person. No Palomar code applies it across records today.
- **top-1** is what a curator would get by accepting every mention's top
  task-A proposal (pool in id order): mentions with one top authority are one
  person; a mention with no proposal stays alone.

Scored pairwise (precision, recall, F1, **false merges**: pairs merged across
two authorities) and by B-cubed.

## Baseline

### Task A (pool in id order)

| family | mentions | in pool | recall@1 | recall@5 | precision@1 | false links (homonym) | abstained | tied top | not in pool (linked) |
|--------|---------:|--------:|---------:|---------:|------------:|----------------------:|----------:|---------:|---------------------:|
| Dumas  | 51  | 50  | 0.300 | 1.000 | 0.300 | 35 (35) | 0 | 36 | 0 (0) |
| Hugo   | 8   | 8   | 1.000 | 1.000 | 1.000 | 0       | 0 | 0  | 0 (0) |
| Hetzel | 2   | 2   | 1.000 | 1.000 | 1.000 | 0       | 0 | 0  | 0 (0) |
| Verne  | 29  | 29  | 1.000 | 1.000 | 1.000 | 0       | 0 | 0  | 0 (0) |
| others | 52  | 47  | 1.000 | 1.000 | 0.959 | 0       | 0 | 0  | 5 (2) |
| **all**| 142 | 136 | 0.743 | 1.000 | 0.732 | 35 (35) | 0 | 36 | 5 (2) |

With the pool in reverse order, the same mentions give recall@1 0.993
(135/136), precision@1 0.978, and 1 false link (Dumas: 49/50).

### Task B

| rule          | merged pairs | right | false merges | precision | recall | F1    | B-cubed P | B-cubed R |
|---------------|-------------:|------:|-------------:|----------:|-------:|------:|----------:|----------:|
| record-scoped | 0            | 0     | 0            | n/a       | 0.000  | n/a   | 1.000     | 0.262     |
| same-name     | 1 149        | 1 114 | 35           | 0.970     | 1.000  | 0.985 | 0.986     | 1.000     |
| top-1         | 1 151        | 1 114 | 37           | 0.968     | 1.000  | 0.984 | 0.977     | 1.000     |

The 35 false merges of the same-name rule are one cluster, "dumas alexandre":
35 mentions of the father, 1 of the son. Accepting every top proposal makes the
same 35 (all 36 Alexandre Dumas mentions go to the son, first in id order) and
2 more: the two "Paltsits, Victor Hugo" mentions joined to Victor Hugo.

## What the numbers show

- **The Jaccard cannot separate Dumas père and fils.** Both are catalogued
  "Dumas, Alexandre": every such mention scores 1.0 against both authorities.
  The top proposal is then the first in the pool (the sort is stable), which is
  why task A's recall@1 is 0.743 in one pool order and 0.993 in the other. The
  number that does not depend on the order is the 36 tied top proposals:
  36 of 136 mentions (26 %) are decided by nothing the matcher measured. The
  mentions carry the dates (`$d` 1802-1870, 1824-1895) that would tell them
  apart; the matcher does not see them.
- **Off that pair, the right authority is always first.** Near-namesakes in
  the controlled heading form differ by a token (Pierre-Jules / Louis-Jules
  Hetzel, Jules / Jean Jules Verne, Robert-Dumas / Dumas) and score 0.667, below
  the exact 1.0. Recall@5 is 1.0 everywhere. The pool is closed and small,
  though, and the mentions are the BnF's controlled access points, written like
  the authority headings: this is the easy case for a name matcher.
- **Outside the pool it links rather than abstains.** Of the 5 mentions whose
  authority is not in the pool, 2 ("Paltsits, Victor Hugo", twice) are linked
  to the writer Victor Hugo (0.667), where abstaining is the right behaviour in
  this closed pool. (Paltsits has a BnF authority of his own; the SRU did not
  serve it.) A threshold of 0.5 admits much: "Hugo, Victor" itself receives 12
  proposals.
- **Clustering by name merges too much in one place and nothing else here.**
  Palomar today merges no name-only agents across records: no false merge, no
  recall (B-cubed recall 0.262 = 37 people / 141 mentions). Same normalised
  name finds every true pair, since the BnF writes each person's heading one
  way. It makes 35 false merges, all father × son.
- **This contradicts an earlier claim, on these headings.** The `bnf-agents`
  README and `entity-resolution.md` said that a name matcher would have
  conflated Jules Verne and Jean Jules Verne, and the Hugo family and the
  "Víctor Hugo …" authors, "into a single blob". Measured on the catalogued
  `$a, $m` headings, it does not: same-name merges only Dumas père and fils,
  and the Jaccard's top proposal is never wrong outside that pair. Both
  sentences now point here. The claim may still hold for looser names (a
  transcribed statement of responsibility, a search string), which this eval
  does not test.

## After dates (M3)

Lot M3 gives the matcher the dates. A mention is `{:name :dates}`, its dates
the field's `$d` parsed (`palomar.agent-dates`); an authority entry carries
the `100 $d` of its record. A candidate whose dates are **incompatible** with
the mention's (no overlap of births, of deaths, a birth after the other's
death, an activity outside the other's life, each with its uncertainty) is
not proposed: `reconcile/agent-candidates` lists it under `:rejected`, with
the reason and the dates compared. Missing dates are no evidence. Dates block;
they add nothing to the name score. The test runs both matchers: the
name-only one (`task-a`, `:top-1`, the "before") and the date-aware one
(`task-a-dated`, `:top-1-dated`, the "after").

The policy errs towards no evidence, since a false rejection is worse than a
missed one: every comparison allows a **tolerance of 2 years** (sources
disagree; Julian and Gregorian calendars), an approximate or uncertain year
(`ca.`, `?`, EDTF `~`) is widened by **20 years**, and a date the parser does
not read, reads only in part, or that is before the common era, blocks
nothing. Dumas père and fils, born 22 years apart, are still separated. On
this corpus the numbers below are the same as under a first measure with a
10-year margin and no tolerance: every `$d` here is an exact year or an open
end.

Only this eval passes dated mentions. **No spoke and no CLI command does
yet**: `palomar reconcile --authorities` passes the records' names as
strings, so it rejects nothing.

Where a mention's dates live: in the IR as the importer's natives (the field
fragment's subfields, the `:intermarc/f100_d` and `f700_d` assertions), read
by the eval at match time as it reads a `700`'s name. No canonical coordinate
carries a date: `:canon/agent` is a name string, and a dated agent there
would change the canonical floor and every exporter that reads it. The
compiler is untouched; the parse is a pure function of the `$d` text. The
classic INTERMARC `700`s are still not carried to `:canon/agent`: the
`:mapping/combine` rule joins the record's first `$a` and first `$m` (the
flat natives are on the record, not on each field), so a `700` mapping would
make one contributor per record, not one per field. Doing it needs a
per-occurrence combine, a change of its own.

131 of the 142 mentions have a `$d`, and 29 of the 33 authorities. The 36
determined "Dumas, Alexandre" mentions all carry theirs (35 `1802-1870`, 1
`1824-1895`).

### Task A, before and after

| family  | R@1 before | R@1 after | P@1 before | P@1 after | false links before | after | tied top before | after |
|---------|-----------:|----------:|-----------:|----------:|-------------------:|------:|----------------:|------:|
| Dumas   | 0.300 | 1.000 | 0.300 | 1.000 | 35 | 0 | 36 | 0 |
| Hugo    | 1.000 | 1.000 | 1.000 | 1.000 | 0  | 0 | 0  | 0 |
| Hetzel  | 1.000 | 1.000 | 1.000 | 1.000 | 0  | 0 | 0  | 0 |
| Verne   | 1.000 | 1.000 | 1.000 | 1.000 | 0  | 0 | 0  | 0 |
| others  | 1.000 | 1.000 | 0.959 | 0.959 | 0  | 0 | 0  | 0 |
| **all** | 0.743 | 1.000 | 0.732 | 0.986 | 35 | 0 | 36 | 0 |

Recall@5 stays 1.000 everywhere; abstention stays 0 in the pool. The pool in
reverse order now gives the same numbers (before: recall@1 0.993): with
dates, nothing in the pool is decided by its order. 166 candidates are
rejected for their dates, on 93 mentions; none is the mention's own authority
(no wrong block on this corpus).

### Task B, before and after

| rule                  | merged pairs | right | false merges | precision | recall | F1    | B-cubed P | B-cubed R |
|-----------------------|-------------:|------:|-------------:|----------:|-------:|------:|----------:|----------:|
| top-1 (before)        | 1 151        | 1 114 | 37           | 0.968     | 1.000  | 0.984 | 0.977     | 1.000     |
| top-1 with dates      | 1 116        | 1 114 | 2            | 0.998     | 1.000  | 0.999 | 0.991     | 1.000     |

The record-scoped and same-name rules use no matcher and do not move.

### What changed, and what did not

- **The Dumas pair is resolved.** Every one of the 35 father mentions is
  proposed the father only, the son's mention the son: births 1802 and 1824
  cannot overlap. The 35 false links and the 36 ties go to 0, and task B's 35
  père × fils false merges with them.
- **Outside the pool, nothing changes.** The two "Paltsits, Victor Hugo"
  mentions carry no `$d`: no evidence, so they are still linked to Victor
  Hugo, and are task B's 2 remaining false merges. Abstention for a mention
  whose authority is not in the pool is the matcher's threshold, not dates.
- **An undated mention keeps its tie.** The one undetermined "Dumas,
  Alexandre" (no `$3`) has no `$d` either: it still scores 1.0 for both, and
  is still tied.
- **Dates block, they do not rank.** Of the 166 rejections, 36 are the
  Dumas père × fils pair (35 + 1) and change the top proposal. The other 130
  remove near-namesakes that were already below the top — Alexandre Dumas /
  Robert-Dumas Alexandre / Charles, Jules / Jean Jules Verne, the Hugo family and
  the "Víctor Hugo …" authors, Pierre-Jules / Louis-Jules Hetzel — which
  recall@1 does not see; a curator's list of candidates is shorter.
- **The easy case still.** The mentions' dates are the BnF's own controlled
  `$d`, written like the authorities'; a transcribed or approximate date
  would test the uncertainty rules, which this corpus barely does.

## A Fellegi-Sunter score (M4)

Lot M4 replaces the Jaccard decision with a Fellegi-Sunter score
(`palomar.agent-match`; ADR 0018 amendment M4), run through
`reconcile/agent-candidates` with `{:model m}` (`task-a-fs`, `:top-1-fs`). The
date block of M3 is kept as it is: an incompatible candidate is rejected,
never weighed. The numbers below are those of the revised model (after a
contradictory review of a first version, whose confidence claims did not
hold).

### Method

- **Comparison vector.** Per (mention, entry): a `:name` level (the best over
  the entry's heading and variants: exact heading; same surname with
  forenames compatible as initials; same tokens in another order; a surname
  one edit away; same surname with overlapping, missing or different
  forenames; then, surnames differing, Jaccard >= 0.5, > 0, none), a `:form`
  (heading, or a variant only), and a `:dates` level (agree within the
  tolerance, compatible, unknown). An entry whose name level is `:none` is no
  candidate.
- **u**, label-free, from the pool against itself: the 1 056 ordered pairs
  of two distinct authorities for the name and form, the 264 the dates do
  not block for the dates. Transductive: the pool is the gold's own
  authorities.
- **m**, from the true pairs (entry = `$3`), **leave-one-family-out**: a
  mention is scored by the model fitted on the four other families. The
  prior is the training folds' share of true pairs among unblocked pairs
  (0.111 to 0.179).
- **Only what was measured weighs**: a level reached by fewer than 5 pairs
  on both sides weighs 0; on one side only, its weight is the bound 5/N
  gives for the other, in the direction it proves; unknown dates weigh 0 by
  rule (the estimate, -4.6, measures that BnF headings carry `$d` and that
  the block removed dated non-matches); name weights never increase along
  the order of agreement, and a disagreement never weighs for.
- **Term frequency**, from the pool matched against: log2(ū / u_s), u_s the
  surname's share of the pool's headings, ū = Σn³/(NΣn²) (2/34 on M2: Dumas
  and Hugo, 3 headings, -0.63; a surname held once, +0.96), on a positive
  agreement only.
- **Decision**: posterior >= 0.95 proposed, 0.5 to 0.95 for review, below 0.5
  abstained; a mention with several candidates at or above 0.5 has them all
  for review.
- **EM** (m and λ unsupervised, u fixed) for comparison.

The weights of the model fitted on all five families (prior 0.129, weight
-2.76; each fold's, fitted on four, differ only in the dates and the
bounds of the last two name levels):

| feature | level | same persons (of 136) | different persons | weight |
|---------|-------|---:|---:|-------:|
| name  | exact | 136 | 2 of 1 056 | +7.72 (u bounded by 5/1 056) |
| name  | initials, same tokens, typo, surname only | 0 | 0 | 0 (unmeasured) |
| name  | forename overlap, forenames differ, Jaccard >= 0.5 other surname | 0 | 12, 10, 30 | 0 (bound proves nothing) |
| name  | Jaccard > 0, other surname | 0 | 146 | at most -1.91 |
| name  | none | 0 | 856 | at most -4.46 |
| form  | heading / variant | 136 / 0 | 1 042 / 14 | +0.02 / 0 |
| dates | agree / compatible / unknown | 131 / 0 / 5 | 20 / 0 / 244 of 264 | +3.67 / 0 / 0 (by rule) |

Every true pair on this corpus is an exact heading: the model can identify
an exact heading and nothing else.

### Task A, M3 and M4

| family  | R@1 M3 | R@1 M4 | P@1 M3 | P@1 M4 | false links M3 / M4 | in-pool ties M3 / M4 | abstained in pool M4 (outright only) | not in pool linked M3 / M4 |
|---------|------:|------:|------:|------:|:---:|:---:|:---:|:---:|
| Dumas   | 1.000 | 1.000 | 1.000 | 1.000 | 0 / 0 | 0 / 0 | 0 (0)  | 0 / 0 |
| Hugo    | 1.000 | 1.000 | 1.000 | 1.000 | 0 / 0 | 0 / 0 | 0 (0)  | 0 / 0 |
| Hetzel  | 1.000 | 1.000 | 1.000 | 1.000 | 0 / 0 | 0 / 0 | 0 (1)  | 0 / 0 |
| Verne   | 1.000 | 1.000 | 1.000 | 1.000 | 0 / 0 | 0 / 0 | 0 (0)  | 0 / 0 |
| others  | 1.000 | 1.000 | 0.959 | 1.000 | 0 / 0 | 0 / 0 | 0 (24) | 2 / 0 |
| **all** | 1.000 | 1.000 | 0.986 | 1.000 | 0 / 0 | 0 / 0 | 0 (25) | 2 / 0 |

M4 counts a proposal for review as the top proposal. Counting only outright
proposals, recall@1 is 111/136 (0.816), precision@1 still 1.000. The 26
mentions whose top proposal is for review all have a competitor above 0.5:
20 "Férat, Jules" (1829-1906) against Jules Verne (1828-1905, Jaccard > 0
with another surname, at most -1.91, against +3.67 for dates that agree);
three "Víctor Hugo" authors born 1948-1951, each against the others; Hetzel
and his pseudonym Stahl, each against the other; and the undetermined,
undated "Dumas, Alexandre", tied between père and fils (the one tie, as in
M3). The 5 in-pool undated mentions are proposed outright: missing dates
weigh nothing. The 5 mentions whose authority is not in the pool are all
abstained (Paltsits at 0.18). 577 candidates are rejected for their dates,
never a mention's own authority.

### Task B, M3 and M4

| rule             | merged pairs | right | false merges | precision | recall | F1    | B-cubed P | B-cubed R |
|------------------|-------------:|------:|-------------:|----------:|-------:|------:|----------:|----------:|
| top-1 with dates | 1 116        | 1 114 | 2            | 0.998     | 1.000  | 0.999 | 0.991     | 1.000     |
| top-1 M4         | 1 113        | 1 113 | 0            | 1.000     | 0.999  | 1.000 | 1.000     | 0.993     |

The two Paltsits mentions, abstained, stay apart: their pair is the one true
pair missed, and no false merge is left.

### Calibration

The held-out predictions: every scored candidate (proposed, for review or
abstained) of the 141 determined mentions, each from its family's held-out
model. 351 predictions, but **125 distinct (mention, candidate) pairs and
33 distinct true mentions** (35 identical "Dumas, Alexandre" mentions are
one), 7 distinct pairs in the review band. **Brier 0.0360, ECE 0.1082,
largest bin gap 0.740.**

| bin | predictions | mean predicted | observed |
|-----|------:|---------------:|---------:|
| [0.0, 0.1) | 113 | 0.0697 | 0.000 |
| [0.1, 0.2) | 53  | 0.1285 | 0.000 |
| [0.3, 0.4) | 23  | 0.3716 | 0.000 |
| [0.5, 0.6) | 24  | 0.5397 | 0.000 |
| [0.6, 0.7) | 1   | 0.6517 | 0.000 |
| [0.7, 0.8) | 1   | 0.7401 | 0.000 |
| [0.9, 1.0] | 136 | 0.9969 | 1.000 |

**The bar is not met, so no confidence is written.** The rule (ADR 0018
amendment M4, ADR 0005 L2): a posterior is an alignment's `:confidence` only
when the model's held-out calibration, on the same pool, meets ECE <= 0.05,
no bin off by more than 0.1, at least 300 distinct pairs and 100 distinct
true mentions (and, for a review decision, 30 distinct pairs in the review
band). M2 has 33 distinct true mentions, an ECE of 0.108 and an empty review
band. The five fold models are not calibrated; none of the 164 proposals
states a confidence. One report, pooled over the folds, is attached to every
fold model; the per-fold reports (distinct pairs / true mentions: Dumas
17 / 6, Hetzel 3 / 2, Hugo 12 / 3, Verne 3 / 2, others 90 / 20) meet the bar
no better.

What the table shows: the model is right where it is sure (136 at 0.997,
all true) and does not know elsewhere — its negatives sit at 0.07 to 0.54
because the weights that would push them down (different surnames, different
forenames) rest on bounds, not measures. EM, measured the same way, gives
the same top proposals, 88 outright, Brier 0.0832 and ECE 0.1776.

### Stress set (synthetic)

**Synthetic, derived from the gold**: each distinct (heading, dates, truth)
of the in-pool determined mentions (33) perturbed five ways — forenames as
initials ("Hetzel, P.-J."), forenames dropped ("Hetzel"), order reversed
("Pierre-Jules Hetzel"), accents removed (only the 11 headings that have
some), a typo (the surname's 2nd and 3rd letters swapped, "Htezel,
Pierre-Jules"); the dates kept. Not a sample of real cataloguing.

| perturbation | n | truth a candidate, M3 (Jaccard >= 0.5) | truth a candidate, M4 | top proposal right, M4 | proposed outright, M4 |
|--------------|--:|---:|---:|---:|---:|
| initials         | 33  | 7  | 33  | 29  | 1  |
| dropped forename | 33  | 29 | 33  | 29  | 1  |
| reversed         | 33  | 33 | 33  | 29  | 0  |
| accents removed  | 11  | 11 | 11  | 11  | 8  |
| typo             | 33  | 17 | 33  | 29  | 0  |
| **all**          | 143 | 97 | 143 | 127 | 10 |

The model's candidates hold every truth; the Jaccard misses initials and
typos. Every perturbed level but accents (which `text/norm` undoes) weighs
0, unmeasured: what ranks the truth first within a surname is the dates and
the surname frequency, not the name level, and with two namesakes dated
alike the model cannot choose ("Hugo, V." scores Victor and Georges-Victor
alike, both abstained). The ranking the first version reported here rested
on weights read from pseudo-counts; those are now 0.

## Constrained clustering (M5)

Lot M5 groups mentions into persons with a constrained clustering
(`palomar.agent-cluster`, ADR 0057) instead of closing through the
authority. Measured by `task-b-constrained` and `chain-cases`
(`palomar.eval.person-reconciliation-test/task-b-with-constrained-clustering`,
`a-synthetic-chain-is-merged-by-the-closure-and-not-by-the-constrained-clustering`).

### Method

- **Items**: the 141 determined mentions (task B's) and the 33 pool
  entries, each with its dates.
- **Links**: each mention's M4 decisions under its family's held-out model:
  111 outright proposals (`:propose`, weighted by their match weight) and
  51 for review. No curated link exists in this eval, so the clustering's
  first phase (accepted claims as must-links, ADR 0057) is empty here.
- **Cannot-links**: the 577 candidates rejected for their dates (M3),
  and, derived on every merge, two different authorities and two dated
  items whose dates are incompatible.
- **Rules**: `closure`, the connected components of the outright proposals
  (no constraint read); `constrained`, the clustering under its policy
  (outright proposals join, review links are candidates for a curator);
  `closure-review` and `constrained-review`, the same when every review
  link joins too, at its weight, as a proposal would. That is a what-if,
  not curation: a curator who confirmed a review candidate
  (`reconcile/confirm-identity`) would make it an accepted must-link, and
  two confirmed for one mention (Férat to Férat and to Verne) a conflict,
  held back and reported. M4's `top-1-fs` (each
  mention's top proposal, outright or for review) for reference.
  `violations` counts the cannot-linked pairs a rule puts in one cluster.

### Task B, M4 and M5

| rule               | merged pairs | right | false merges | precision | recall | F1    | B-cubed P | B-cubed R | violations |
|--------------------|-------------:|------:|-------------:|----------:|-------:|------:|----------:|----------:|-----------:|
| top-1 M4           | 1 113 | 1 113 | 0   | 1.000 | 0.999 | 1.000 | 1.000 | 0.993 | — |
| closure            | 923   | 923   | 0   | 1.000 | 0.829 | 0.906 | 1.000 | 0.858 | 0 |
| constrained        | 923   | 923   | 0   | 1.000 | 0.829 | 0.906 | 1.000 | 0.858 | 0 |
| closure + review   | 1 577 | 1 113 | 464 | 0.706 | 0.999 | 0.827 | 0.827 | 0.993 | 8 |
| constrained + review | 1 113 | 1 113 | 0 | 1.000 | 0.999 | 1.000 | 1.000 | 0.993 | 0 |

- **Under the policy, closure and constraints agree.** A mention has at
  most one outright proposal (M4), so the outright links form stars around
  authorities and no chain: nothing to refuse. The 25 mentions whose top
  proposal is for review (20 Férat, three "Víctor Hugo" authors, Hetzel and
  Stahl) stay alone, as do the two Paltsits (abstained, as in M4): 191 true
  pairs missed (190 among the Férat mentions, 1 the Paltsits pair), recall
  0.829. That is the policy's cost: what a curator has not
  decided is not merged. The 51 review links are listed as candidate
  merges.
- **With every review link joining, the closure chains.** The competing
  candidates are the bridges: each Férat mention is for review to Férat
  and to Verne, so the closure makes Férat's 20 mentions and Verne's 23
  one cluster (460 false merges), Stahl and Hetzel one (1), and the three
  "Víctor Hugo" authors one (3), breaking 8 constraints: 5 pairs of
  authorities in one cluster, and 3 pairs of incompatible dates in the
  Víctor Hugo cluster.
- **The constrained clustering of the same links makes none.** It refuses
  the 26 weaker links (each `:distinct-authorities`: 20 Férat → Verne, the
  Víctor Hugo competitors, Hetzel ↔ Stahl), and gives exactly M4's top-1
  numbers: taking the strongest link first is what the top-1 rule does
  mention by mention, and on this corpus no constraint needs more. None of
  the 26 refusals is a tie (no two competing links weigh the same).

### Chains (synthetic)

**Synthetic, derived from the pool**: for each pair of authorities X, Y
with one surname whose dates are incompatible (9 pairs: the three Dumas,
the three Hugo, the two Verne, the two Hetzel, the two Robert-Dumas), a
bridge U, an undated authority record with X's heading; a mention mx, X's
heading with X's dates; a mention my, X's heading with Y's dates (a
homonym born when Y was). Each is scored against [U] by the model fitted
on all five families: both are proposed outright, at the same weight
(exact heading, dates unknown against U), so mx ~ U ~ my is a chain, and
mx ⊥ my by their dates.

| | cases | chains | mx and my merged | links refused | of which on a tie |
|-|------:|-------:|-----------------:|--------------:|------------------:|
| closure (= accepting every top proposal) | 9 | 9 | 9 | — | — |
| constrained | 9 | 9 | 0 | 9 | 9 |

The constrained clustering puts U with mx in all 9: the two links weigh
the same, the lower id wins, and each refusal says it was a tie (`:tie?
true`, on the refused link and on the one that won). With different
weights the stronger side wins
(`agent-cluster-test/a-chain-goes-with-its-stronger-side`).

### Revision

Not measured here, guaranteed by unit tests
(`palomar.agent-cluster-test`): a cluster in the IR is a set of
`:proposed` `skos:exactMatch` claims from each local agent to a person
node minted per cluster and batch; a re-clustering after a new date or a
rejected link rejects the pending memberships that no longer hold, with
the reason, and reopens a claim proposed again rather than copying it; a
refused alignment goes to `:needs-review` with the reason; an accepted
claim is never revised by the machine, and a contradiction among accepted
claims is a conflict diagnostic on the records it involves.

## On a GND snapshot (M6)

Lot M6 makes an authority dump a **snapshot**: indexed once
(`palomar authorities index`, `palomar.authority-index`), then read offline,
its candidates looked up by blocking keys (ADR 0038 amendment M6). This
section runs the M4 matcher on the M2 mentions against such an index, built
from a small real GND extract. `palomar.eval.gnd-person-reconciliation-test`.

### Method

- **The pool**: 197 GND person records (CC0) fetched from lobid on
  2026-11-09 by the M2 authorities' headings and some near-namesakes (every
  Verne, the persons whose forenames are "Victor Hugo"…); queries in
  [`test/fixtures/er-gold/gnd-persons/`](../../test/fixtures/er-gold/gnd-persons/README.md).
  Indexed: 197 entries, 3 998 blocking keys. A closed pool again, though not
  the gold's own: it holds GND namesakes the BnF pool did not (a "Dumas,
  Alexandre" born 1988, two undated "Hugo, Victor", "Charles-Victor Hugo",
  "Verne, Jean"…) and no record bridged to 14 of the gold's 33 persons.
- **The matcher**: `reconcile/agent-candidates` with the M4 model fitted on
  all five M2 families (m from the BnF gold, u and the prior 0.129 from the
  BnF pool; no refit on the GND), the mention's `{:name :dates}`, and the
  index's `:block`. **That m was fitted on the true pairs of these same 142
  mentions**: the main table is in-sample for the mentions, though not for
  the pool. The held-out run below scores each family with the model fitted
  on the four others, as in M4. The term frequencies are the GND pool's, as always the
  pool matched against.
- **Blocking**: the blocks hold 4 764 (mention, entry) pairs of the full
  scan's 142 × 197 = 27 974 (17 %); the proposals, rejections and
  abstentions are the full scan's, mention by mention (tested).
- **The truth**, by **bridges** the matcher never sees: the GND record's
  `sameAs` to the BnF ARK (7 authorities), an ISNI both records state (10),
  and Wikidata's BnF and GND ids (17; `bnf-gnd-wikidata.csv`). 19 of the 33
  BnF authorities are bridged to a GND record of the extract; where two
  bridges speak they agree, but for one pair. **Hetzel and his pseudonym
  Stahl are contested**: the BnF's Hetzel has the ISNI of the GND's "Stahl,
  P.-J." record, the BnF's Stahl the ISNI the GND's "Hetzel, Pierre-Jules"
  carries, and Wikidata pairs Hetzel with Hetzel. Their 2 mentions are scored
  for nothing. The 14 others have no bridge, nor do the 4 authorities
  outside the M2 pool: what the matcher does for them
  is reported, not judged.

### What the matcher does

| outcome of the mention (its top candidate) | bridged, right | contested | no bridge | undetermined | all |
|---|---:|---:|---:|---:|---:|
| proposed outright | 101 | 0 | 11 | 0 | 112 |
| for review        | 8   | 2 | 1  | 1 | 12  |
| abstained         | 0   | 0 | 11 | 0 | 11  |
| no candidate left | 0   | 0 | 7  | 0 | 7   |
| **all**           | 109 | 2 | 30 | 1 | 142 |

- **Every uncontested bridged mention's top proposal is its GND record**: 109 of 109,
  101 proposed outright; no wrong link, no bridged truth rejected for its
  dates or abstained. The 8 for review have a GND namesake above 0.5: Victor
  Hugo and two undated "Hugo, Victor" records (0.983 each), François-Victor
  and Charles-Victor Hugo (0.656), 6 "Verne, Jean Jules" (the GND's "Jules-Verne,
  Jean", by a variant) and "Verne, Jean", 1890-1982 (0.656).
- **Contested**: Hetzel's mention has GND Hetzel first (1.000) and GND
  Stahl second (0.653), Stahl's mention the reverse; all four for review
  (competing).
- **No bridge** (30 mentions of 18 authorities: the 14 unbridged, and the 4
  the BnF SRU does not serve): 11 are proposed outright to a
  GND record of the same name (heading or tokens) and compatible or no dates (Robert-Dumas,
  Charles ×5; Paltsits, Victor Hugo ×2, whose BnF authority the SRU did not
  serve in M2; Wlassikoff; Martínez González; Dumas de Rauly; D'Paola, to
  "Paola, Víctor Hugo d'"), plausible but not measurable here; Escala is for
  review (an undated GND "Escala, Victor Hugo", 0.998, against a "Zwinz,
  Victor Hugo" whose dates agree, 0.656); 11 abstained, the extract holding
  no record of their heading (7 Robert-Dumas, Alexandre — the GND has a
  "Robert-Dumas, A." the queries did not reach —, four "Víctor Hugo"
  authors), their best candidates at 0.336 at most; 7 have no candidate
  left (no shared key, or, for Schlienger and Lippmann, every candidate
  rejected for its dates).
- **The undetermined** undated "Dumas, Alexandre" has three candidates for
  review at 0.975: père, fils and the Alexandre Dumas born 1988.
- 138 proposals, 2 280 candidates abstained, 2 346 rejected for their dates.
- **Held out** (leave-one-family-out, each fold's prior 0.111 to 0.179):

  | outcome | bridged, right | contested | no bridge | undetermined | all |
  |---|---:|---:|---:|---:|---:|
  | proposed outright | 80 | 0 | 11 | 0 | 91 |
  | for review        | 29 | 2 | 4  | 1 | 36 |
  | abstained         | 0  | 0 | 8  | 0 | 8  |
  | no candidate left | 0  | 0 | 7  | 0 | 7  |

  Still no wrong top proposal among the 109, but 21 more of them go to
  review: the 20 "Férat, Jules" (Férat, Jules-Descartes, and Jules Verne
  above 0.5 against them, the "others" fold having learnt from the four
  author families only) and Rascón Banda. Three unbridged "Víctor Hugo"
  authors (Acuña Ortega, Demaria Pesce, Viscarra Rodríguez), abstained
  in-sample, are now for review at 0.540 against another "…, Víctor Hugo"
  whose dates agree — a different person by every sign but the forenames.
  173 proposals, 2 245 abstentions, 2 346 rejections.
- **No confidence is written**: the model is not calibrated, and a
  calibration measured on the BnF pool would not hold for this one (ADR
  0005 L2; the code refuses it). Every posterior above is a score.

### What it shows, and what it does not

- The M4 model, learnt on BnF headings, carries to GND headings of the same
  persons where the forms agree (most do: both follow ISBD-like
  "Surname, Forenames"), and the dates keep the namesakes apart as on the
  BnF. Its abstentions are where the GND has no such person.
- It is the easy case still: controlled headings against controlled
  headings, a pool drawn around the gold's names, and a truth only for the
  19 authorities a bridge reaches (111 mentions of 141, 2 of them contested). The 11 outright
  proposals without a bridge are unscored; a wrong one among them would not
  be seen.
- The bridges are not independent of each other nor always right: the
  Hetzel/Stahl ISNIs show identity and pseudonym records crossed between two
  national files.

## Typed events (M7)

Lot M7 gives a mention the typed events of the record it is in
(`palomar.agent-events`, ADR 0018 amendment M7). It was revised after a
contradictory review. Its first version also blocked a creation dated
after the candidate's death. Posthumous making catalogued under the
artist's name made that unsafe: a Rodin cast in 1925, a Rembrandt
impression of 1906, a Thonet chair reissued in 1985, a 19th-century copy
under Raphael all blocked the artist they name.

**The rule now:**
- Only a **creation** the mention carried out (a LIDO production or design
  whose actor is its maker) can block, and only when it ends **more than
  10 years before the candidate's birth**. A creation after death is never
  evidence.
- An event that contradicts the mention's own stated life is dropped.
- A group (a corporation, an institution, a family, a manufacturer) has no
  life to constrain.
- A publication or edition, an exhibition, an acquisition, an influence
  ("after X"), an attribution to another hand or a later making (cast,
  impression, restrike, replica, reproduction, version, forgery), any
  other activity (printer, colourist, caster, "Text by"), and an undated or
  untyped event constrain nothing.

A blocked candidate is rejected as in M3, with `:reason
:incompatible-events` and the values compared. It is a cannot-link for the
clustering's proposals; a curator's accepted link is kept and the
constraint reported. Measured by
`palomar.eval.person-reconciliation-test/a-bibliographic-record-gives-edition-events-and-no-new-evidence`,
`a-synthetic-set-of-events-on-real-authorities` and
`the-actors-of-lido-records-against-their-ulan-ids`; the reviewer's cases
are `palomar.agent-events-test/posthumous-making-never-blocks-the-artist`.

### On the BnF records (M2): editions only

A mention's events are its record's edition dates (INTERMARC 260 `$d`),
typed for its role. The INTERMARC 008 is not read, and no importer reads a
work's creation date.

- **142 mentions, 142 edition events**: 135 dated. **62** mentions have an
  edition after their authority's death (beyond the tolerance): posthumous,
  allowed.
- **With the dates and the events**, task A is M3's, figure for figure.
  The events reject nothing (0 candidates, 0 truths). **With the events
  alone** (dates withheld), the numbers are the name-only baseline:
  recall@1 101/136, 35 false links, 36 ties.
- **What-if: an edition read as the author's creation**: 20 rejections
  (editions more than 10 years before another candidate's birth), none of
  them a mention's own authority. Under the first version's two-sided
  rule, the same what-if blocked 62 mentions' own authority. The
  one-sided rule makes the typing matter less here, not more.

A bibliographic corpus gives no new evidence and no false block.

### Synthetic: events around real authorities

**Synthetic, built on the pool**: for each of the 21 BnF authorities that
state a birth and a death, six mentions of its heading carry one event
each and no dates. Each is matched name-only against the whole dated pool.

| event | cases | the authority rejected | cannot-link |
|-------|------:|-----------------------:|------------:|
| creation 30 years before birth | 21 | 21 | 21 |
| creation 8 years before birth (within the 10-year margin) | 21 | 0 | 0 |
| creation in mid-life           | 21 | 0  | 0  |
| creation 30 years after death (a cast, a reissue) | 21 | 0 | 0 |
| edition 30 years after death (posthumous) | 21 | 0 | 0 |
| edition 30 years before birth  | 21 | 0  | 0  |

### On LIDO: the actors of a museum's records

The person-level truth is the ULAN id a YCBA actor carries. The pool has
one entry per ULAN id of a person actor: the name most of its occurrences
give, and the vital dates they state. These dates are the YCBA's own, from
the same records, not the ULAN's. Each occurrence is a mention with its
one event and its life dates withheld. It is matched name-only (Jaccard
>= 0.5) against the pool.

- **The five committed records** (`ycba-oai-lido.xml`, asserted): 8
  occurrences, 8 ULAN persons, all dated, 4 dated creations (the others an
  attribution ×2, an influence, an activity). No rejection, no truth
  blocked.
- **500 records** (the scratchpad harvest of `docs/eval/ycba.md`, not
  committed; run once, not asserted by a test): 615 occurrences, 276 ULAN
  persons (263 dated), 350 dated creations.
  - **No rejection, and no ULAN's own authority blocked.** Reading every
    event as a creation gives none either.
- **That measure is weak.** 72 of the 500 records' productions are
  displayed "undated". Their earliest and latest are the museum's, derived
  from the artist's life: circular as evidence on who the artist is. So an
  undated display ("undated", "n.d.", "o.J.", "s.d.", "ohne Jahr", "sans
  date", "undatiert") is now no date, whatever bounds come with it. The one
  rejection an earlier run made (a Bunbury, 1760-1811 by those bounds,
  against William Henry Millais, born 1828) rested on such an event and is
  gone. What is left is 350 dated creations, none earlier than 10 years
  before a namesake's birth.
- The YCBA holds paintings, drawings and prints. It has no casts and no
  posthumous impressions, so it cannot measure the risk the review found.
  That risk was answered by narrowing the rule, not by a measure.

### What it shows, and what it does not

- **What is left is narrow.** A creation by an actor recorded as its maker,
  dated more than 10 years before a namesake's birth. This is the one
  reading posthumous making cannot produce.
- **The risk that remains**: a source that dates a model, a plate or a
  design long before the right person's birth, and names that person as
  the work's maker.
- On both real corpora the events add almost nothing that dates do not
  already say. A LIDO actor states its vital dates on the same record, and
  a MARC edition constrains nothing.
- **No spoke and no CLI command passes events yet**, as for dates: the
  evals do.

## Limits

- One institution, 150 records from three author queries: the families are
  those the queries reach, not a sample of a catalogue.
- The gold is the BnF's own linking, not an independent annotation; its
  errors, if any, are invisible here.
- Small: 37 authorities, one hard homonym pair carrying every false link
  inside the pool, one
  undetermined mention. The order of the Dumas pair alone moves recall@1 by
  0.25.
- The pool is closed: it holds the cited authorities and nothing else, which
  flatters recall@5 and the false-link count. A real pool has distractors.
- The names are controlled headings. A transcribed name (a `245 $f` "par
  Alexandre Dumas fils") is not tested.
- Only the `100` and `700` fields are mentions. The 28 person subject headings
  (`600`, each with a `$3`) name persons too, but as what a work is about, not
  who made it; they are not measured here.
- Task A's top-1 numbers depend on pool order wherever scores tie; both orders
  are reported. With dates, no in-pool score ties any more.
- The dates compared are each heading's `$d`. The INTERMARC authority `008`
  also carries full birth and death dates for some records; the reader does
  not read it yet (it is listed as unread).
- The M4 model is learnt from 136 true pairs that are all exact headings,
  33 distinct: it measures one level of agreement, and its calibration bar
  is not met (33 distinct true mentions against 100, an empty review band).
  The thresholds (0.95, 0.5) and the bar were set before measuring, not
  tuned, and nothing here shows they are right elsewhere.
- u and the surname frequencies are read from the pool, which is the gold's
  own authorities (transductive): 33 headings compared with each other
  stand for "two different persons". A real pool, with distractors, would
  give other u, and a calibration measured on one pool says nothing of
  another (the code refuses to carry it over).
- M5's clustering numbers rest on a star topology: every link is a mention
  to an authority, and a mention has at most one outright proposal. The
  constraints only matter where links compete (the review band) or where
  a pool holds an undated duplicate (the synthetic chains). Two authority
  records for one person (Stahl, a pseudonym of Hetzel; perhaps the two
  Hayat) are a cannot-link by rule: an authority file's duplicates are the
  authority's to merge, not the clustering's.

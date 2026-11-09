# Person reconciliation — the baseline (measured)

`palomar.eval.person-reconciliation-test`, on 150 real BnF records. Corpus and
gold: [`test/fixtures/er-gold/bnf-persons/`](../../test/fixtures/er-gold/bnf-persons/README.md).

## Why

The other agent evals do not measure person identity. `entity-resolution.md`
measures Work clustering; `bnf-agent-reconciliation-test` shows that grouping
by ISNI keeps people apart, which holds by construction. Nothing so far says how
Palomar's name-based reconciliation, the path for a name with no authority id,
does on real homonyms. This eval measures it as the code stands. Its numbers
are the **baseline** later lots will be measured against (dates as contrary
evidence, a Fellegi-Sunter score, constrained clustering). It is a measurement,
not a guarantee: no row in `guarantees.md`.

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
truth and never reaches a matcher, nor does `$1` (ISNI) or `$d` (dates): a test
spies on what task A passes to `propose-agent-links` and checks that no `$3`,
`$1`, ISNI, date or authority number appears in a name, label or variant, and
that pool entries carry only `:id :label :variants`. The
authority records go through the same importer. A small reader in the test
tree builds the pool from them: a proper INTERMARC authority reader is a later
lot. The metrics are pure functions, tested on hand-made inputs
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
  are reported.

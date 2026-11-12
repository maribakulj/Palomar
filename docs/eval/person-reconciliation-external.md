# Person reconciliation, the external test — transcribed mentions (lot O5)

`palomar.eval.person-reconciliation-external-test`. The code is in
`palomar.eval.person-reconciliation-external`. The protocol is
[`protocol.edn`](../../test/fixtures/er-gold/bnf-external/protocol.edn).
The benchmark it extends is
[`person-reconciliation-hard.md`](./person-reconciliation-hard.md).

## Protocol

This section was written, and `protocol.edn` and the code were hashed in
`freeze.edn`, before any corpus was fetched. A test checks the hashes.

### Why

N3's test split is spent. It was run once, and N4 then changed the
thresholds after seeing it. So no number of the shipped engines on
transcribed mentions is out of sample. This lot builds a new test that
nobody has seen, and scores it once.

### The corpora

- **Candidates.** 22 authors, listed before any of their records was read,
  for variety: pseudonyms (Voltaire, Molière, Nerval, Vercors, Anatole
  France, Jules Romains, Apollinaire, Yourcenar, Rabelais, Grandville),
  homonyms (Musset, Rostand, Ségur, Montaigne, Mardrus, Tolstoï),
  translators (Mardrus, Nerval, Yourcenar, Conrad's and Tolstoï's),
  illustrators (Doré, Grandville, Rabier, Vercors), a duo (the Tharaud
  brothers), and periods from Christine de Pizan to the 20th century.
- **No overlap.** No candidate is one of N3's 13 corpora or M2's three
  (Verne, Hugo, Dumas), nor shares its surname. A test checks it.
- **Selection.** The candidates are ordered by the SHA-256 of
  `palomar-o5-external/` and the query, ascending. They are fetched in
  that order. A query with at least 100 records is kept; one with fewer is
  logged and skipped. The fetch stops at 5 kept corpora. That order
  starts: Voltaire, Musset, Christine de Pizan, Mardrus, Rabelais.
- **Source.** The BnF SRU (`catalogue.bnf.fr/api/SRU`, version 1.2,
  `intermarcxchange`), the first 100 records of N3's query form,
  `(bib.author all "<query>") and (bib.recordtype any "mon")`. Then the
  person authorities the records cite (`$3`), and the `331` pseudonyms of
  those, one query each, as N3 did.
- **Trim.** N3's: the leader, the control fields, and the data fields 1XX,
  245, 260 and 7XX. Before the build, only counts and well-formed XML are
  checked.
- **Licence.** Licence Ouverte / Etalab 2.0, "Source : Bibliothèque
  nationale de France".

### The construction

- **N3's rules, unchanged.** The build is `hard/build`'s steps, in its
  order, each one the frozen N3 namespace's own function: the splitter,
  the labelling rule, the hand rule, the pool rule, the own-record rule,
  the duplicate rule, the split rule, the strata. A test checks that this
  composition, given N3's own inputs, rebuilds N3's committed benchmark
  exactly.
- **All test.** Every scored mention is test. There is no train or dev,
  because nothing is fitted or tuned on it.
- **Dropped records.** A record already in an N3 or M2 fixture is dropped
  before labelling, and counted.
- **Seen names.** A mention whose key (its truth, or its no-match name) is
  the key of any scored N3 mention, in any split, is set aside
  (`:seen-in-design`, N3's own rule, with N3's mentions as the design
  part). So no N3 person is scored, and the N3 model's refusal of its
  train keys never fires.
- **The pool.** N3's committed pool (292 entries, unchanged), plus an
  entry for every authority the new records cite that N3's pool lacks,
  built by N3's pool rule. On a shared id, N3's entry is kept. The
  construction and every engine read this one pool.
- **Hand labels.** N3's rule: a no-match whose surname the pool holds is
  settled by hand (the same person, with its pool id, or a namesake), and
  a residual candidate is verified by hand. Unsettled, it is set aside. The
  hand reads the record and the pool only, never an engine's output.
- **Order.** All hand labels are written before any engine runs on these
  mentions. `labels-freeze.edn` then records the SHA-256 of the labels,
  the benchmark and the split file, with the time. The run refuses to start
  unless they match, and records them.

### The engines, frozen

| id | engine | model SHA-256 | weights | thresholds |
|---|---|---|---|---|
| (a) | N3's Jaccard, token-set similarity ≥ 0.5, the name as transcribed | — | — | 0.5 |
| (b) | Fellegi-Sunter, M2 model as shipped (`agent-match.edn`) | `d84056ed…` | `22fb57898f6f5d52` | 0.95 / 0.5, no gap |
| (c) | Fellegi-Sunter, N3 model as shipped (`agent-match-n3.edn`, O4 rules) | `3cdf97d3…` | `20d0b836a1d2ca2c` | 0.5 / 0.5, G 12 |
| (d) | Fellegi-Sunter, the M2 model with a gap of 6 bits | `e9c369d1…` | `22fb57898f6f5d52` | 0.95 / 0.5, G 6 |

- The files' own SHA-256 are in the protocol too. The run refuses to start
  if any of them, or any model version, differs.
- **Name order.** The Fellegi-Sunter engines read the name in heading
  order, by N3's `hard/inverted`, as N3 and N4 did. The Jaccard reads it as
  transcribed.
- **The call.** `reconcile/agent-candidates` with the model, the engine as
  shipped. A link is a top proposal decided `:propose`.
- **Ties.** A tied top is an abstention for every engine: no link, and no
  review. Ties are counted apart.
- **Refusal.** Each model goes through `refuse-in-sample!` with the pool
  and the mentions' keys.

### The metrics, fixed

Overall and per stratum (easy, homonym, variant, hand, residual, hard
negative, no namesake), O4's figures:

- precision (correct over outright links) and recall (correct over linked
  mentions), each with its Wilson 95 % interval;
- false merges (outright links to the wrong person or on a no-match);
- abstention (no link and no review), and the same on no-match mentions
  and on hard negatives;
- curation cost: reviews per mention, and the share of reviews whose truth
  is among the candidates at or above review;
- generation recall (the truth among the candidates generated);
- the number of ties.

### The decision rules

Written before the fetch. "Overall" figures decide.

1. **Power.** With fewer than 100 linked mentions or fewer than 20 persons,
   every rule below reads "inconclusive".
2. **The gap for M2.** (d) replaces (b) as M2's default if its false merges
   are ≤ (b)'s and its recall is ≥ (b)'s + 0.02. Else (b) stays.
3. **The N3 model against M2.** (c) is better than (b) on transcribed
   mentions if its false merges are ≤ (b)'s, its recall is ≥ (b)'s + 0.02,
   and the Wilson lower bound of its precision is ≥ 0.95. It is worse if
   its false merges are > (b)'s, or its recall is < (b)'s − 0.02. Else no
   difference is shown.
4. **Against the Jaccard.** A Fellegi-Sunter engine matches the Jaccard if
   its false merges are ≤ the Jaccard's and its recall is ≥ the Jaccard's −
   0.02. Else the Jaccard stays the more complete engine on transcriptions.
5. **No change in this lot.** If a rule says to change a default, it is
   reported. The change, if any, is the next lot's, after review.

### A single run

`run-once!` scores the test once and writes `run.edn`: the start time, the
labels' SHA-256, every figure, the errors and the decisions. It refuses to
run when `run.edn` exists. The results are reported here as they come. No
test asserts them.

## What was done when

All times are UTC, on 2026-11-11. No commit was allowed in this lot. The
record below is **consistent with** the order the protocol asks for; it
does not prove it. Every time in it was written on the build machine, and
nothing outside that machine held the freeze before the fetch.

1. **23:22:56** — `freeze.edn` written: the SHA-256 of `protocol.edn`
   (`3793e07c…`), of the code namespace (`943232e7…`) and of the
   "Protocol" section above (`77aeb98f…`). A test checks all three.
2. **23:23:09 to 23:26:01** — the fetch. `fetch-log.edn` holds every
   response's `Date` header. The raw headers are committed
   (`raw-headers/`, 173 files), and a test checks that the log's dates are
   theirs and that each is after the freeze. The headers were saved on the
   same machine: they are self-recorded, not a third party's timestamp.
3. The build, then the hand labels (20).
4. **23:27:48** — `labels-freeze.edn` written: the SHA-256 of the hand
   labels, the benchmark and the split file.
5. **23:27:59** — the single run started; `run.edn` records the labels'
   SHA-256 it read. A test checks the order and the hashes.
6. **After the run** (the O5 review, 2026-11-12): the README, the results
   below, the test file, `postrun.edn`, `raw-sha256.edn`, the raw headers,
   the scripts under `dev/eval/bnf-external/`, and the post-run namespace
   and its test. This is harmless to the result: no test asserts one.

**The raw responses.** The raw pages (2.0 MB) and authority responses
(0.8 MB) are not committed; `raw-sha256.edn` holds their SHA-256. On
2026-11-12, re-running the scripts on them gave the committed records,
`authorities.xml`, `pseudonyms.xml` and `fetch-log.edn` byte for byte. A
test checks the trimmed and assembled hashes against the fixtures. The
scripts (`dev/eval/bnf-external/`) were run with `python3 -I` from their
own directory, never from the data directory. `fetch.sh` was put together
after the run from the commands that ran.

**What the freeze did not hold.** The freeze hashed the protocol, the O5
namespace and the doc section, not the engine code. `postrun.edn`,
recorded after the run, gives the base commit (`fcc678b`) and the SHA-256
of `src/` and of the N3, N4, O4 and M-cycle namespaces the run called; the
worktree had no change to them. It is a record, not a live check: a test
checks only that it is well formed, written after the run, and names the
base commit and the files. `dev/eval/bnf-external/verify-postrun.sh`
recomputes every hash from `git show fcc678b:<path>` and compares; on
2026-11-12 every one matched. The two frozen namespaces (N3's, through its
freeze and the protocol, and O5's) stay live checks: they must never
change. The freeze should have held the engine code. `check-frozen!` (the run's own
guard) checks the protocol, the namespace and the labels, but not the doc
section's hash; the freeze test and the post-run test check it.

**For the next external test.**
- Publish the freeze hash outside the machine (as a PR comment) before any
  fetch, so a third party holds the order.
- Draw the selection salt from randomness made after the freeze, such as a
  drand round named in the protocol. Here the order was computable by the
  same hand before the freeze (the salt and the list were both chosen by
  it), and the 100-record skip never fired on real data.
- Freeze the engine code (`src/` and every namespace the run calls) with
  the protocol.

## Counts

| | |
|---|---:|
| corpora (100 records each) | 5: Voltaire, Musset, Christine de Pizan, Mardrus, Rabelais |
| records dropped (already in N3 or M2) | 0 |
| records with a mention | 406 |
| person authorities cited / served by the SRU | 165 / 117 |
| pseudonym authorities (`331`) | 3 |
| pool: N3's + new | 292 + 165 = 457 |
| transcribed mentions | 640 |
| linked by surname / variant / hand / shared surname / residual | 474 / 27 / 15 / 2 / 1 |
| no match, no heading / hard negative (hand) | 45 / 2 |
| set aside | 74 (own record 31, group 20, unexplained heading 11, seen in N3 3, ambiguous 3, incomplete 3, duplicate 2, hand-rejected 1) |
| scored: linked / no match | 519 / 47 |
| persons / no-match names | 106 / 40 |
| strata | easy 275, homonym 201, variant 27, hand 15, residual 1, hard negative 2, no namesake 45 |

The 20 hand labels: 15 no-match mentions linked as the same person, 2 kept
as hard negatives ("Hôtel Voltaire", "Réseau Voltaire"), 2 residual
links verified, 1 rejected (a splitter artefact that runs two people
together). Only 1 of the 2 verified residual links is scored
("Henry-Paté"); the other ("Bertrand Rouziès-Léonardi") falls under the
own-record rule.

**A deviation in the hand labels.** The protocol says the hand reads "the
record and the pool only". Four labels went further: "Roger Pons" and
"Pons" (Pons ×2) and "F. Van Hamme" ×2 cite another record of the same
series or edition, which names the person in a heading. Setting these 4
mentions aside changes no decision; the margins of rules 3 and 4 move from
0.142 and 0.010 to 0.139 and 0.012 (tested).

## Results of the single run

566 scored mentions: 519 linked, 47 no match. A tie is an abstention.
Abstention is no link and no review. Intervals are Wilson 95 %.

| engine | correct | false merges | reviews | ties | precision [95 %] | recall [95 %] | abstention | no-match abstention | reviews / mention | reviews holding the truth | generation recall |
|---|--:|--:|--:|--:|---|---|--:|--:|--:|--:|--:|
| (a) Jaccard | 436 | 63 | 0 | 17 | 0.874 [0.842, 0.900] | 0.840 [0.806, 0.869] | 0.118 | 0.915 | 0.000 | n/a | 0.873 |
| (b) M2 as shipped | 347 | 0 | 14 | 9 | 1.000 [0.989, 1.000] | 0.669 [0.627, 0.708] | 0.362 | 1.000 | 0.025 | 1.000 | 1.000 |
| (c) N3 as shipped | 431 | 0 | 0 | 9 | 1.000 [0.991, 1.000] | 0.830 [0.796, 0.860] | 0.239 | 1.000 | 0.000 | n/a | 1.000 |
| (d) M2, G = 6 | 347 | 0 | 14 | 9 | 1.000 [0.989, 1.000] | 0.669 [0.627, 0.708] | 0.362 | 1.000 | 0.025 | 1.000 | 1.000 |

Recall by linked stratum, `correct / n`, and its interval:

| engine | easy (275) | homonym (201) | variant (27) | hand (15) | residual (1) |
|---|---|---|---|---|---|
| (a) | 268, 0.975 [0.948, 0.988] | 127, 0.632 [0.563, 0.695] | 26, 0.963 [0.817, 0.993] | 14, 0.933 [0.702, 0.988] | 1 of 1 |
| (b) | 206, 0.749 [0.695, 0.797] | 108, 0.537 [0.468, 0.605] | 23, 0.852 [0.675, 0.941] | 10, 0.667 [0.417, 0.848] | 0 of 1 |
| (c) | 214, 0.778 [0.725, 0.823] | 180, 0.896 [0.846, 0.931] | 24, 0.889 [0.719, 0.961] | 13, 0.867 [0.621, 0.963] | 0 of 1 |
| (d) | as (b) | as (b) | as (b) | as (b) | as (b) |

Precision by linked stratum is 1.000 for (b), (c) and (d) in every
stratum. For (a): easy 1.000, homonym 0.686 [0.616, 0.749] (58 false
merges), variant 1.000, hand 0.933 (1 false merge), residual 1 of 1.

No-match strata, abstention (and false merges):

| engine | hard negative (2) | no namesake (45) |
|---|---|---|
| (a) | 2 of 2 | 41 of 45 (4) |
| (b), (c), (d) | 2 of 2 | 45 of 45 |

Curation cost: (b) and (d) send 14 mentions to review, 0.025 per mention,
and every review holds its truth. They are all Voltaire homonyms, persons
whose surname is Voltaire ("Marguerite Voltaire" ×9, "Janise Voltaire"
×2, three more). (c) sends none. That depends on the pre-registered tie
rule: each Fellegi-Sunter engine has 9 ties (BnF duplicate authorities),
which the engine itself would send to review, and the protocol counts
them as abstentions. Read as reviews, (c) would have 9. Generation recall
is 1.000 for every Fellegi-Sunter engine and 0.873 for the Jaccard.

**The errors, as they came.**

- (a) The Jaccard's 63 false merges: "J. C. Mardrus" and "J.-C. Mardrus"
  (52) linked to "Gourdon, J. C." (an N3 pool entry: the initials are
  two shared tokens); "Musset" alone (7) to Victor-Donatien de Musset;
  "Alfred de Musset Paris" (2, a no-match); "Maistre Alcofribas Orné" to
  Rabelais; "L.-F. Flutre" to "Grauvogl, F L". Its 17 ties: BnF duplicate
  authorities (Radi, Voltaire; Mardrus, Victor; Brégeon, Laurent), "Angus
  J. Kennedy" (5) and "Richard J. Moll" against "Kennedy, Jay Richard",
  and "A. de Musset" (2).
- (b) and (d) miss 172 linked mentions: 149 with no link and no review,
  14 sent to review, and 9 ties (149 + 14 + 9 = 172). They are "Rabelais"
  alone (54), "J.-C. Mardrus" in its forms (57), the Voltaire homonyms in
  review (14), "Musset" alone (7), and initials ("Angus J. Kennedy", "S.
  Solente", "A. Périer").
- (c) misses 79 and ties 9: "Rabelais" alone (54), "Musset" alone (7),
  "J.-C. Joseph-Charles Mardrus" (5), and single cases. Its 9 ties are the
  BnF duplicates.


## After the run: concentration and intervals

Not in the protocol: descriptive figures, written after the run.
`palomar.eval.person-reconciliation-external-post` computes them from
`run.edn` and `benchmark.edn` only. Nothing is scored again: a case is
correct when the run recorded no error for it. Its test pins them.

**Concentration.** Four persons hold 321 of the 519 linked mentions
(61.8 %): Alfred de Musset 90, François Rabelais 88, Joseph Charles Mardrus
76, Christine de Pizan 67. "Rabelais" alone accounts for 54 of the
Fellegi-Sunter engines' misses. Recall averaged per person is (a) 0.889,
(b) 0.739, (c) 0.882, (d) 0.739.

**Intervals.** The Wilson intervals of the results table assume
independent mentions. Mentions of one person are not independent. The
person-cluster bootstrap resamples the 146 persons and no-match names
(2 000 replicates, seed 20261112, percentile 95 %):

| engine | recall | Wilson | person-cluster | precision | Wilson | person-cluster |
|---|--:|---|---|--:|---|---|
| (a) | 0.840 | [0.806, 0.869] | [0.657, 0.963] | 0.874 | [0.842, 0.900] | [0.687, 0.995] |
| (b) | 0.669 | [0.627, 0.708] | [0.482, 0.862] | 1.000 | [0.989, 1] | [1, 1] |
| (c) | 0.830 | [0.796, 0.860] | [0.657, 0.943] | 1.000 | [0.991, 1] | [1, 1] |
| (d) | 0.669 | [0.627, 0.708] | [0.482, 0.862] | 1.000 | [0.989, 1] | [1, 1] |

The recall difference (c) − (b) has the person-cluster interval
[0.036, 0.359]: above the 0.02 rule 3 asks. The difference (c) − (a) has
[−0.271, 0.245]: it straddles 0. The frozen code stored the Wilson upper
bound of a precision of 1 as 1.0000000000000002 (rounding); the post-run
figures clamp it to 1.

**The rules' margins.** Rule 3's margin is recall (c) − recall (b) − 0.02;
rule 4's is recall (c) − recall (a) + 0.02. Both false-merge conditions
hold throughout ((c) has none).

| | rule 3 | rule 4 |
|---|--:|--:|
| whole test | +0.142 | +0.010 |
| one person left out, smallest | +0.052 (Mardrus) | −0.097 (Mardrus) |
| one corpus left out, smallest | +0.055 (Mardrus) | −0.109 (Mardrus) |
| left out and the decision flips | none | Mardrus (person); the Mardrus or the Christine de Pizan corpus |

**So rule 3 (N3 better than M2) is robust**: no single person or corpus
reverses it, and its cluster interval clears the margin. **Rule 4 (N3
matches the Jaccard) is fragile**: it flips when Mardrus is removed, whose
76 mentions the Jaccard links 52 times to "Gourdon, J. C.".

## The decisions the rules reach

1. **Power: sufficient.** 519 linked mentions, 106 persons.
2. **The gap for M2: keep no gap.** (d) gives (b)'s figures exactly: on
   this test a gap of 6 bits changes no decision. Its recall is not 0.02
   above (b)'s. (b) stays M2's default.
3. **The N3 model against M2: the N3 model is better** on transcribed
   mentions. False merges 0 against 0; recall 0.830 against 0.669 (+0.161,
   above the +0.02 the rule asks); the Wilson lower bound of its precision
   is 0.991, above 0.95.
4. **Against the Jaccard.** (c) **matches the Jaccard**: 0 false merges
   against 63, and recall 0.830 against 0.840 (within 0.02). (b) and (d)
   do not: their recall is 0.669, so the Jaccard stays the more complete
   engine against them. This decision is fragile (above): it rests on one
   person.
5. **No default changes in this lot.** Rule 3 says the N3 model is better
   than M2 on transcribed mentions. Whether it becomes a default for them
   is the next lot's question, after review. M2 stays the default of
   `--engine probabilistic`, and its file states no gap.

## Limits

- **A few names weigh heavily.** Four persons hold 61.8 % of the linked
  mentions. Rule 4's "matches" for (c) flips without Mardrus; rule 3 holds
  with any one person or corpus left out.
- **The Wilson intervals treat mentions as independent.** The
  person-cluster intervals (above) are much wider.
- **One hand.** The 20 hand labels and the protocol are by the same lot,
  and no second hand checked them.
- **Two hard negatives.** That stratum cannot be read; the no-namesake
  stratum (45) carries the no-match figures.
- **Gold seen after the run, left as it is.** "Alfred de Musset Paris" (2)
  is a splitter artefact labelled no match, though the 245 names Musset.
  "Maistre Alcofribas Orné" is labelled no match; it may be a play on
  Rabelais's pseudonym. The rules are frozen.
- **The pool is N3's plus the new authorities.** The Jaccard's largest
  error is against an N3 entry ("Gourdon, J. C."): the result depends on
  the pool, as N3 said of its calibration. The N3 model's u was measured
  against the same 292 N3 entries that are distractors here, and its prior
  was fitted for a 292-entry pool; it is used on 457. The test is external
  for persons and mentions: no scored truth is an N3 entry, and no N3
  person or no-match name is scored. It is not external for the pool.
- **The order is self-recorded.** The freeze is not a commit, nothing
  outside the machine held it before the fetch, and the selection order
  was computable before the freeze ("What was done when").
- **The freeze did not hold the engine code**; `postrun.edn` records it
  after the run.
- INTERMARC only; five authors; one SRU page each.

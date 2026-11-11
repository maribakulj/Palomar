# Person reconciliation, the hard benchmark — transcribed mentions (lot N3, measured)

`palomar.eval.person-reconciliation-hard-test`, on 1 300 real BnF records.
Corpus and gold:
[`test/fixtures/er-gold/bnf-transcribed/`](../../test/fixtures/er-gold/bnf-transcribed/README.md).

## Why

The easy benchmark ([`person-reconciliation.md`](./person-reconciliation.md))
matches the BnF's controlled headings (`100`/`700 $a, $m`). They are written
like the authority headings, so a name matcher does well on them. This
benchmark matches a name as the title page gives it ("par J.-H. Rosny
aîné,...", "trad. di Matteu Cirnensi", "Currer Bell"). The candidates are
every person authority of the corpus, not only the record's own. The engine
must pick the right one, or abstain.

This lot builds the benchmark and runs the existing engines with their
current parameters. It recalibrates nothing (that is lot N4).

## The corpus

- **Design corpora (train and dev)**: 900 INTERMARC records, 100 per query
  — André Maurois, Brontë, Colette, Daudet, Goncourt, Rosny, Edgar Poe,
  Lewis Carroll, Pierre Loti.
- **Test corpora**: 400 records, 100 per query — George Sand, Simenon,
  Stendhal, Erckmann-Chatrian. They were chosen and fetched after the rules
  were frozen (see [What was seen when](#what-was-seen-when)).
- **The pool**: the 292 person authorities that the person headings of all
  1 300 records cite (`$3`). 205 are read from their authority records by
  Palomar's INTERMARC authority reader (heading, `400` variants, dates). The
  SRU serves no record for the other 87; their entry is built from a citing
  heading (`$a, $m`, `$d`, no variants), taken from a record in which no
  mention is linked to that person when there is one. A mention whose truth
  entry could only be built from its own record's heading is set aside
  (`:entry-from-own-record`): its pool entry would be its own gold.
- **Pseudonyms**: the 22 authorities that the cited authorities link by `331`
  ("Bell, Currer" for Charlotte Brontë), read for the pseudonym rule only;
  they are not candidates.
- Licence Ouverte / Etalab 2.0, *Source : Bibliothèque nationale de France*.
  Only the `1XX`, `245`, `260` and `7XX` fields are kept (stated in the
  README).
- **INTERMARC only.** The Library of Congress SRU is not reachable from the
  build sandbox. The DNB SRU is, with CC0 MARC21 records that carry 245 `$c`
  and 100/700 `$0 (DE-588)`; Palomar has no MARC21 authority-pool reader
  yet, so a MARC21 corpus is left for later.
- **Goncourt gives nothing.** Its 100 records are the Académie Goncourt's
  anthology series ("choisis par l'Académie Goncourt"): 100 body mentions,
  set aside. The one "Edmond et Jules de Goncourt" of the corpus is a title
  (`$a`), not a statement of responsibility, so fixing the shared-surname
  rule recovers no Goncourt case. No claim here rests on Goncourt.

## Construction

### 1. Splitting the statement into mentions

`statement-mentions` reads the record's first 245: `$f` (the first statement
of responsibility), `$g` (the later ones) and the role clauses of `$a`.

1. **Clean** the text: drop `[sic]`, `et al.`, `etc.`, quoted titles, and
   any word with encoding junk ("M³"); turn "Surname (Forename)" into
   "Forename Surname"; drop brackets and ellipses; set an elided `d'`/`l'`
   apart from its word. Drop honorifics wherever they stand (M., Mme,
   Madame, Monsieur, Mademoiselle, Dr, Mgr…) and engravers' Latin (sculp.,
   scul., fecit, delin., delineavit, inv., invenit, pinx., Lugd.).
2. **Cut into clauses** at `;`, `/`, `:` (not after a role noun: "Signé :
   Léon Daudet"), and at a sentence end: a word ending with `.` followed by
   a capitalised role noun ("Gordon Pym. Traduction de…"), or a word of 4
   letters or more ending with `.` followed by a capitalised word.
3. **Names** are runs of capitalised words. Particles (`de`, `d'`, `van`,
   `von`, `di`…) join a name between two of its words; `aîné`, `jeune`,
   `fils`, `fil.`, `père`, `cadet` end one. **Role nouns** are whole words
   (traduction, trad., préface, illustrations, dessins, eaux-fortes,
   signé…); a capitalised one is a role only at the start of a clause or
   after a comma, so a forename that begins like one (Edith, Publius,
   Ornella) is a name. "Signé", "Fragments" are never names.
4. The **role phrase** of a name is the words since the previous name,
   after its last comma; at the start of a `$f`/`$g` clause, all of them, so
   an internal comma stays ("Traduit de l'anglais, par"). Names joined by
   `,`, `et`, `and`, `&` share the role before the first; after `et`, a bare
   marker ("préf. de X et de Y") keeps it.
5. A name is a **mention** when: in `$f`/`$g`, it opens the first clause, or
   its role phrase ends with a marker (`par`, `by`, `de`, `d'`, `du`, `des`,
   `di`, `da`, `von`, `of`, `auctore`, articles aside) or a role noun; in
   `$a`, after `par`/`by` in the title clause, and after a phrase that
   starts with a role noun or holds `par`/`by` in a later clause. A name
   after a comma and `de`/`du`/`of` is a qualifier, not a mention. A trailing
   period is stripped.
6. **Bodies and collectives**: a name run that opens with, or is followed
   by, a body word (Association, Académie, Société, Amis, Society,
   Lycée…) is a `:body` mention, and a name inside it ("Association des
   amis de Pierre Loti") is none. A name next to "sisters", "frères",
   "family"… ("[by the Brontë sisters]") is `:collective`.
7. **Incomplete**: initials only ("R."), or one word in an enumeration that
   ends with a longer name ("Edmond et Jules de Goncourt", "Charlotte,
   Emily, Anne Brontë"). It gets a completed name with the shared surname
   ("Anne Brontë"), used only if that fits one heading.

The golden examples of the test are real 245 strings of train records (a
record none of whose scored mentions is in dev). Four classes have no train
string; their examples are marked synthetic.

### 2. Labelling (the gold)

`label-record` links a mention to one heading of its own record (persons:
100, 101, 700–702, 720–722 as `palomar.mentions` reads them; a person heading
that names two, "Colette et Gérard", is read as a group). In order:

1. A `:body` or `:collective` mention: **set aside** (`:group`).
2. An incomplete mention whose completed name fits exactly one heading:
   **linked** (`:shared-surname`); else **set aside** (`:incomplete`).
3. A heading **fits** a mention when its surname (`$a`; a hyphenated surname
   is one word) ends the mention, or ends it before `fils`, `aîné`…, and the
   forenames are compatible (none before the surname, none in the heading,
   or the same first letter). Exactly one fits: **linked** (`:surname`), or
   **no match** (`:heading-without-id`) if it has no `$3`. Several:
   **set aside** (`:ambiguous`).
4. None fits: exactly one heading's authority has a `400` **variant** the
   mention fits: **linked** (`:variant`); else exactly one has a **`331`
   pseudonym** it fits: **linked** (`:pseudonym`). These rest on the BnF's
   authority records, not on a matcher.
5. Else a group heading names it: **set aside** (`:group`).
6. Else a **residual link** with independent evidence is proposed, never
   taken: a shared role code (its role phrase is of one class — translator,
   illustrator, preface, editor — it is the record's only open mention of
   that class, and exactly one unexplained heading has a `$4` of that class:
   0680, 0440, 0550, 0360), or a shared part of a hyphenated surname (one
   open mention, one unexplained heading). A hand verifies each one
   (`hand-labels.edn`); unverified, it is set aside. All three of the
   design corpora were verified correct, and all three are set aside
   anyway by the own-record rule above, so no residual link is scored.
7. Else, some person heading is explained by no mention: **set aside**
   (`:unexplained-heading`). Else: **no match** (`:no-heading`).
8. **Open world**: a no-match whose surname some pool authority holds (in
   its heading or a variant, or the same words in another order) may be
   that person with a missing heading, or a namesake. A hand decided every
   one (`hand-labels.edn`): the same person is **linked** to the pool
   authority (`:hand`); a namesake stays a **no match** as a hard negative
   (`:hand-negative`). Hand labelling is labelling, not tuning: it was done
   on every split, the test split included, after the freeze and before any
   engine ran on it.
9. **Duplicates**: mentions of one record with the same text and role count
   once (`:duplicate`).

### 3. Splits by person

Every scored mention has a key: its truth (the authority id), or, for a
no-match case, `name:` with its surname and first initial ("Charles
Baudelaire" and "Ch. Baudelaire" share `name:baudelaire c`). A design key's
split is the first four bytes of SHA-256("palomar-n3-design/" + key), mod
100: 0–74 train, 75–99 dev. A test-corpus mention is test, unless a design
mention has its key; then it is set aside (`:seen-in-design`, 88 of them).
So no authority id and no no-match key is in two splits. The candidate pool
is not split: every split is matched against all of it.

### 4. Strata

Every scored mention has a stratum: **easy** (linked by surname, and no other
pool authority holds its surname), **homonym** (linked by surname, and
another pool authority holds it), **variant** (a `400` variant or a `331`
pseudonym), **hand** (a hand-verified link), **residual** (none scored), and
for no-match cases **hard-negative** (a hand found a namesake in the pool)
and **no-namesake**. Most easy cases are trivial for a name matcher: 294 of
313 design easy cases (94 %) are token-exact (the mention's words are the
heading's or a variant's).

### 5. Format, loader, harness

`benchmark.edn` holds `:pool`, `:records` (each record's headings and part,
for audit) and `:mentions` (`{:id [record subfield occurrence piece] :text
:role :statement :label :reason :truth :heading :part :split :stratum
:token-exact}`). `load-benchmark` validates it and parses the pool's dates.
`run-engines` runs, on one split, against the same pool:

- **Jaccard** — `reconcile/propose-agent-links`, threshold 0.5;
- **names+dates** — `reconcile/agent-candidates`, the mention as `{:name
  :dates nil}`;
- **Fellegi-Sunter** — `reconcile/agent-candidates` with M4's model (m and λ
  learnt on every labelled mention of the easy benchmark, u from its pool).
  The raw run feeds it the name as transcribed, natural order, a format M4
  never saw (its mentions were headings, "Surname, Forename");
- **Fellegi-Sunter, inverted** — the same model on the name put in heading
  order by `inverted`, from the name alone: the last word (with `aîné`,
  `fils`… after it, and capitalised particles before it) is the surname;
  lowercase particles go after the forenames ("Goncourt, Jules de").

Only a `:propose` decision is a Fellegi-Sunter link; `:review` is counted
apart. **A tied top proposal is an abstention** in the main numbers (its own
outcome, `:tied`); the numbers with ties broken by pool order are given
too. Precision is correct links over links made (a link on a no-match case
is wrong). Recall is correct links over linked mentions. Abstention is the
share of mentions with no link. Macro recall is the mean per-person recall;
macro no-match abstention the mean over no-match names.

## What was seen when

1. **Before this rework.** The 9 design corpora were all read while the
   first rules were written (the first version split them 60/20/20, its
   "test" included). The contradictor review read them too. So none of
   them can be a test.
2. **The rework**, on the 9 design corpora only: the splitter's failure
   classes, the variant/pseudonym/residual rules, the open-world hand rule,
   the strata, the hand labels of the design corpora, and the hand check
   (train only). The golden examples were taken from train records.
3. **Freeze.** `freeze.edn` records the SHA-256 of the namespace holding
   every rule (`97bf7d84…`); a test checks it is unchanged.
4. **Then** the 4 test corpora were chosen (authors with pseudonyms, sons
   and a duo), fetched, and checked for count (100 each) and well-formed
   XML only; their authorities and pseudonyms were fetched.
5. **Labelling of the test corpora**: the 20 test no-match cases with a
   namesake in the pool were hand-labelled, and one design case whose
   namesake came with the test pool (Maggie Salcedo). No engine had run on
   test.
6. **One run** on test, reported below. After it, two probable gold errors
   were seen in its error list: "Rosine Georgin" is linked to "Georgin,
   Robert" (the forename initial rule), and "Émile Erckmann-Chatrian" to
   the duo's collective heading "Erckmann-Chatrian". They are left as they
   are: the rules are frozen.
7. Adding the test authorities to the pool changed the dev numbers of the
   Fellegi-Sunter inverted run (below); the Jaccard's did not change.

## Counts

| | mentions |
|---|---:|
| transcribed mentions (design / test) | 1 149 / 625 |
| linked by surname | 1 075 |
| linked by hand | 51 |
| linked by variant / pseudonym | 49 / 10 |
| linked by shared surname | 6 |
| no match, no heading / heading without `$3` | 171 / 1 |
| no match, hard negative (hand) | 15 |
| set aside: group | 130 |
| set aside: seen in design (test corpora) | 88 |
| set aside: entry from own record | 52 |
| set aside: incomplete | 47 |
| set aside: ambiguous | 29 |
| set aside: unexplained heading | 27 |
| set aside: duplicate | 23 |

| split | linked | no match | persons | no-match names | easy | homonym | variant | hand | hard negative |
|-------|-------:|---------:|--------:|---------------:|-----:|--------:|--------:|-----:|--------------:|
| train | 604 | 93 | 82 | 71 | 284 | 257 | 42 | 21 | 10 |
| dev   | 186 | 20 | 36 | 15 | 29  | 127 | 16 | 14 | 3  |
| test  | 401 | 74 | 61 | 57 | 198 | 186 | 1  | 16 | 2  |

## Current numbers, dev

206 mentions: 186 linked, 20 no match. Ties as abstention (main):

| engine | correct | wrong | missed (tied) | false link | review | precision | recall | macro recall | abstention | no-match abstention | macro no-match abst. |
|--------|--------:|------:|--------------:|-----------:|-------:|----------:|-------:|-------------:|-----------:|--------------------:|---------------------:|
| Jaccard        | 171 | 0 | 15 (3) | 1 | 0 | 0.994 | 0.919 | 0.911 | 0.165 | 0.950 | 0.933 |
| names+dates    | 171 | 0 | 15 (3) | 1 | 0 | 0.994 | 0.919 | 0.911 | 0.165 | 0.950 | 0.933 |
| Fellegi-Sunter, raw      | 0   | 0 | 186 | 0 | 0 | n/a   | 0.000 | 0.000 | 1.000 | 1.000 | 1.000 |
| Fellegi-Sunter, inverted | 165 | 0 | 21  | 0 | 3 | 1.000 | 0.887 | 0.844 | 0.199 | 1.000 | 1.000 |

Ties broken by pool order, the Jaccard (and names+dates) has 173 correct, 1
wrong, 12 missed: precision 0.989, recall 0.930. The 3 ties are all between
BnF duplicate authorities ("Robert, Claire" ×2, "Brodovikoff, Léon").

By stratum (Jaccard / Fellegi-Sunter inverted, recall): easy 1.000 / 0.966
(29), homonym 0.961 / 0.961 (127), variant 0.375 / 0.313 (16), hand 1.000 /
0.714 (14); hard negatives abstained on 2 of 3 / 3 of 3; no-namesake 17 of
17 / 17 of 17.

The Jaccard's misses: Currer Bell (10 mentions, a pseudonym), "Ch. Brontë"
and "Currer Bell C. Brontë" (below 0.5), and the 3 ties. Its false link:
"Loti-Viaud" (Mme Loti-Viaud) to "Pierre-Loti-Viaud, Pierre".

On the design pool, before the test authorities joined it, the Jaccard's
numbers were the same; the Fellegi-Sunter inverted run had recall 0.602
with 56 reviews. The term-frequency adjustment reads the pool: with 292
entries instead of 187, a surname is rarer, its agreement weighs more, and
more proposals clear 0.95. A calibration is the pool's, as M4 said.

## Test, reported once

The test split was run once, after the freeze and the test labelling. No
test asserts these numbers. Do not tune on them.

475 mentions: 401 linked, 74 no match. Ties as abstention:

| engine | correct | wrong | missed (tied) | false link | review | precision | recall | macro recall | abstention | no-match abstention |
|--------|--------:|------:|--------------:|-----------:|-------:|----------:|-------:|-------------:|-----------:|--------------------:|
| Jaccard        | 394 | 0 | 7 (4) | 3 | 0   | 0.992 | 0.983 | 0.966 | 0.164 | 0.959 |
| names+dates    | 394 | 0 | 7 (4) | 3 | 0   | 0.992 | 0.983 | 0.966 | 0.164 | 0.959 |
| Fellegi-Sunter, raw      | 145 | 0 | 256 | 1 | 0   | 0.993 | 0.362 | 0.032 | 0.693 | 0.986 |
| Fellegi-Sunter, inverted | 277 | 0 | 124 | 1 | 112 | 0.996 | 0.691 | 0.786 | 0.415 | 0.986 |

Ties broken by pool order, the Jaccard has 394 correct and 4 wrong:
precision 0.983. By stratum, Jaccard recall: easy 0.990 (198), homonym
0.984 (186), hand 0.875 (16), variant 1 of 1. The Jaccard's errors: "Simenon"
alone ties among the eleven Simenons (4), "A. Pécoud", "L. Royer", "Rosine
Georgin" missed, "Stendhal Club" and "Stendhal" (a body, hard negative)
linked to Stendhal, "J.-L. Beuzon" to "Bonniard, J.-L.". The raw
Fellegi-Sunter run links only names equal to a heading, here two: "Stendhal"
and "Erckmann-Chatrian" (hence its macro recall of 0.032). The test corpora have almost no
variant or pseudonym case (1), so that stratum is measured on dev only.

## What the numbers show

- **Dates do not help.** A transcription carries no life dates, so
  names+dates is the Jaccard exactly.
- **The raw Fellegi-Sunter run is fed a format M4 never saw.** Its only
  positive name weight is `:exact`; "Charlotte Brontë" against "Brontë,
  Charlotte" is `:same-tokens`, weight 0, and the posterior stays near the
  prior. Put in heading order, the same model is precise (no wrong link
  on dev or test, one false link on a test no-match) but abstains or
  reviews much more than the Jaccard. This
  is input for lot N4.
- **Most linked cases are easy.** On the design corpora, 94 % of the easy
  cases are token-exact. The Jaccard's errors are in the hard strata:
  pseudonyms (Currer Bell), initials, a surname alone among homonyms
  (Daudet, Simenon), BnF duplicate authorities, and bodies named like a
  person.
- **A tie is no decision.** Counting it as an abstention removes all the
  Jaccard's wrong links on dev; broken by pool order, it adds them back.

## Hand check

`hand-check.edn` holds a stratified sample of **train only**, drawn before
the freeze: 10 one-word linked mentions, 10 homonym cases, 10 easy cases,
10 variant/hand links, 10 no-match cases, each read against the record's 245,
its headings and the pool. **All 50 are correct in the committed gold.**
The first draw of the no-match stratum held one error ("Souvestre
Lesbazeilles", a pool translator's name in the other order, labelled no
match); the open-world rule was fixed to read that order, the mention was
hand-labelled, and the file records it. Seen: 1 error in 51 rule labels
checked, now fixed. The hand labels themselves (76) are a hand's decisions;
each has a note.

## Pool duplicates

Four groups of pool ids share a heading and dates, probably one person each:
Brodovikoff, Léon (11098123, 11511983, 12593397), Maurat, Charlotte
(11517471, 12624439), Simenon, Guillaume (10485966, 11163381), Robert,
Claire (10977863, 11477926, 12635351). All are entries built from headings.
Their effect: a mention of one ties with the others, so the Jaccard
abstains (3 dev ties); broken by pool order, it is right or wrong by
chance (1 wrong on dev).

## Limits

- **The gold is the BnF's `$3`, the BnF's authority variants and
  pseudonyms, a rule, and a hand.** A wrong cataloguer link is a wrong
  gold; two probable errors are known in test (above).
- **Set aside is not measured**: 396 mentions (22 %), among them shared
  surnames, headings the 245 does not name, and mentions whose pool entry
  would be their own heading.
- **No residual link is scored**: the three verified ones fall under the
  own-record rule.
- **The splitter is tuned on the design corpora.** Some bodies with no body
  word stay no-match mentions ("APLH"); abstaining is right for them too.
- **Dev is small by person** (36), test larger (61); both lean on a few
  authors (Rosny aîné, Charlotte Brontë; Simenon, Sand).
- The pool is closed, 292 entries; 87 have no variants.
- INTERMARC only; no MARC21 245 `$c` corpus yet.

## N4 — the engines compared, the model recalibrated

`palomar.eval.person-reconciliation-hard-n4-test`. The code is in
`palomar.eval.person-reconciliation-hard-n4`. It calls the frozen N3
namespace and does not change it. The section was revised after a
contradictory review (the thresholds, the reading of the test run, the
recommendation, the limits).

### Method

- **The same candidates.** Every engine reads every scored mention of a
  split against the whole pool (292 entries), as N3's harness does. The
  Jaccard and names+dates engines are N3's own functions. The three
  Fellegi-Sunter engines share one comparison of each (mention, entry)
  pair, on the name put in heading order (`hard/inverted`). They differ
  only in their weights and thresholds. A test checks that the shipped
  model, on these comparisons, gives N3's pinned inverted run exactly. A
  cheap filter skips pairs that share no token and no near surname; a test
  checks it drops no pair a full comparison would score.
- **Fellegi-Sunter as shipped**: the M2 model file
  (`palomar/models/agent-match.edn`), at its thresholds (0.95 / 0.5).
- **Fellegi-Sunter recalibrated on N3 train** (the N3 model): m from the
  604 true pairs of the 697 train mentions; u from every other train pair
  (202 920, `:none` pairs included); the prior is the share of true pairs
  (604 / 203 524 = 0.00297). The pool is not split, so u reads every pool
  entry, but only train mentions.
- **Fellegi-Sunter with roles**: the N3 model plus one feature. The role
  phrase of the mention gets N3's class (`hard/role-class`: translator,
  illustrator, preface, editor). The candidate's classes come from the
  `$4` codes of its headings in the other design records
  (`hard/code-classes`; any other code is "other"). The mention's own
  record is never read, and no test record is. Levels: `:agree`,
  `:disagree`, `:unknown` (no class, or no heading elsewhere). `:unknown`
  weighs 0. On train: true pairs 46 agree, 20 disagree, 538 unknown; other
  pairs 2 874 agree, 16 940 disagree, 183 106 unknown. Weights: agree
  +2.43, disagree −1.33.
- **Decisions** are the matcher's (`reconcile/agent-candidates`): two
  candidates at or above review send the mention to review; else the top
  one is proposed at or above propose. Only a proposal is a link. A tie is
  an abstention (N3's rule).

### The thresholds

**First criterion, on dev (spent).** The first version chose the
thresholds on dev, by a criterion written in the code before the dev
numbers were seen: propose 0.95, review 0.1 (the roles run: 0.9 / 0.4).
The single test run below was made with those thresholds.

**Second criterion, on train by cross-validation (the shipped one).** The
review found that dev is too small to choose on (every propose value from
0.5 to 0.95 gave the same 165 links) and that review 0.1 sat on a cliff
(below). The lot's coordinator then set this criterion, after the single
test run and before the cross-validated choice was computed. It is in the
code (`threshold-criterion`). The train split is cut into 5 folds by
person; each fold is scored by the model fitted on the other four.

1. **Propose.** With no review band, a grid value is admissible when its
   cross-validated precision is at least 0.99 and the Wilson 95 % lower
   bound of that precision is at least 0.95. Take the admissible value
   with the highest recall; on a tie, the highest value. If none, keep the
   shipped 0.95 / 0.5.
2. **Review.** A grid value at or below propose is admissible when it
   keeps the cross-validated recall within 0.01 of step 1, finds the truth
   in at least half its reviews, and sends at least 30 mentions to review
   (the bar's `:min-review-pairs`). Take the highest admissible value. If
   none, keep the shipped 0.5.
3. **Ship** the N3 model without roles, whatever the role run gives: the
   engine has no role feature for a transcription, and the CLI's mentions
   are headings.

Synthetic tests make each condition decide one case: precision, the
Wilson bound, the review hits, the 30 reviews, the highest admissible
value, and the recall.

**Chosen:** propose **0.8**, review **0.5**. On the folds, every propose
value from 0.5 to 0.8 gives 556 links, 554 right (precision 0.996, Wilson
lower bound 0.987, recall 0.917); 0.9 gives 549, 0.95 gives 521. So the
tie goes to 0.8. No review value qualifies: from 0.2 to 0.8 the band holds
13 to 15 reviews, fewer than 30; at 0.1 and 0.05 the competing rule loses
over 300 links. So the shipped 0.5 stays. The roles run, by the same
criterion on the same folds, also gets 0.8 / 0.5.

**Dev confirms:** at 0.8 / 0.5, 165 links, precision 1.000, 5 reviews.
The test split was not run with these thresholds: it is spent.

### Dev, per stratum

206 mentions: 186 linked, 20 no match. The N3 runs are at 0.8 / 0.5. They
give the same figures at the first thresholds.

| engine | correct | wrong | missed (tied) | false link | review | precision | recall | macro recall | abstention | no-match abstention |
|--------|--------:|------:|--------------:|-----------:|-------:|----------:|-------:|-------------:|-----------:|--------------------:|
| Jaccard        | 171 | 0 | 15 (3) | 1 | 0 | 0.994 | 0.919 | 0.911 | 0.165 | 0.950 |
| names+dates    | 171 | 0 | 15 (3) | 1 | 0 | 0.994 | 0.919 | 0.911 | 0.165 | 0.950 |
| FS as shipped  | 165 | 0 | 21     | 0 | 3 | 1.000 | 0.887 | 0.844 | 0.199 | 1.000 |
| FS N3          | 165 | 0 | 21     | 0 | 5 | 1.000 | 0.887 | 0.844 | 0.199 | 1.000 |
| FS N3 + roles  | 165 | 0 | 21     | 0 | 5 | 1.000 | 0.887 | 0.844 | 0.199 | 1.000 |

Recall, then macro recall, by linked stratum (precision is 1.000 in every
linked stratum, for every engine):

| engine | easy (29) | homonym (127) | variant (16) | hand (14) |
|--------|----------:|--------------:|-------------:|----------:|
| Jaccard, names+dates | 1.000 / 1.000 | 0.961 / 0.723 | 0.375 / 0.800 | 1.000 / 1.000 |
| FS as shipped        | 0.966 / 0.955 | 0.961 / 0.723 | 0.313 / 0.700 | 0.714 / 0.643 |
| FS N3                | 0.966 / 0.955 | 0.961 / 0.723 | 0.313 / 0.700 | 0.714 / 0.643 |
| FS N3 + roles        | 0.966 / 0.955 | 0.961 / 0.723 | 0.313 / 0.700 | 0.714 / 0.643 |

No-match strata, abstention (and false links):

| engine | hard negative (3) | no namesake (17) |
|--------|------------------:|-----------------:|
| Jaccard, names+dates | 2 of 3 (1 false link) | 17 of 17 |
| FS, all three        | 3 of 3                | 17 of 17 |

Review volume: the shipped model sends 3 homonym mentions to review (BnF
duplicate authorities: "Claire Robert" ×2, "Léon Brodovikoff"). The N3
model sends the same 3, and 2 hard negatives.

**On dev, recalibration changes no link.** The N3 model makes the shipped
model's 165 links and misses the same 21. The role weight changes no
decision. The misses are not a matter of weights: "Currer Bell" (10, a
pseudonym the pool does not carry as a variant), "Ch. Brontë" and "C.
Brontë Currer Bell" (a two-letter initial is no initial, so the forenames
differ), "Alexeïeff" and "Souvestre Lesbazeilles" (hand links the name
levels cannot reach), "Samuel Loti-Viaud" (a hyphenated surname), and the
BnF duplicates.

### Two model defects, for the next lot

Neither is fixed here: each changes `agent-match`, so every model.

- **The name weights are flattened.** The N3 counts measure
  `:surname-only` (9 true pairs, 35 others: log2 ratio +6.43) and
  `:forename-overlap` (12 and 145: +4.80). Both weigh 0. The weights must
  not increase along the name levels (`agent-match/constrain`), and two
  levels above them are unmeasured, so weigh 0: `:same-tokens` (1 true
  pair) and `:surname-typo` (3 true pairs, none among the others). Every
  level below is capped at 0. `:same-tokens` is an artefact of the
  inversion: as transcribed, 539 true pairs are `:same-tokens`; put in
  heading order, 1 is. Lifting the cap alone makes things worse under the
  current competing rule: on dev, 165 links fall to 99, because more
  namesakes clear the review threshold and every mention with two of them
  goes to review. The fix for the next lot is both: skip unmeasured levels
  in the non-increasing chain, and make the competing rule relative (a
  second candidate competes only when it is close to the first).
- **The `:variant` form weighs +5.18 whatever the name level.** A
  candidate reached through a variant at `:forenames-differ`,
  `:surname-only` or `:jaccard-high` gets that weight on a name weight of
  0, and a posterior of 0.0976. On dev, 89 mentions have their second
  candidate just there. The first criterion's review threshold, 0.1, sat
  right above that cliff. The form weight should apply only when the name
  agrees.

### Calibration, against the bar

The bar (`agent-match/default-bar`): ECE ≤ 0.05, no bin off by more than
0.1, at least 300 distinct pairs and 100 distinct true mentions, and 30
distinct pairs in the review band for a review decision. The band is now
the model's own, [review, propose) = [0.5, 0.8) (`fs/calibration` takes it
as `:band`; by default it is still [0.5, 0.95), so M2's reports are
unchanged). Predictions: every scored candidate of every mention, linked
or not. A pair is distinct by the name read and the candidate; a mention
by the name read.

| report | predictions | distinct pairs | distinct true mentions | review-band pairs | Brier | ECE | largest bin gap | bar |
|--------|------:|------:|-----:|---:|-------:|-------:|------:|---------|
| dev, the N3 model itself, band [0.5, 0.8) | 1 674 | 329 ✓ | 43 ✗ | 2 ✗ | 0.0118 | 0.0069 ✓ | 0.710 ✗ | not met |
| 5 folds of train+dev, by person, band [0.5, 0.95) | 7 499 | 1 641 ✓ | 164 ✓ | 13 ✗ | 0.0099 | 0.0079 ✓ | 0.401 ✗ | not met |

Dev bins: [0.0, 0.1) 1 496 predictions, mean 0.012, observed 0.007;
[0.7, 0.8) 4, mean 0.710, observed 0; [0.9, 1.0] 174, mean 0.979,
observed 0.966. The cross-validation's worst bin is [0.5, 0.6): 6
predictions, mean 0.568, observed 0.167.

**The bar is not met, so the model file carries no calibration and no
confidence is written.** The ECE is low because almost every prediction
is a near-zero non-match; the middle bins are few and wrong. The dev
report is bound to the fingerprint of the model as the file gives it
(`3d5b1089592bc496`) and to the pool's (`5e12a4b01db957fd`). The
cross-validation report names no model: it measures five fold models, not
the shipped one. Even if it met the bar, binding it to the shipped
model's fingerprint would claim what it does not show.

**If a shipped model ever meets the bar.** The CLI refuses a `--model`
file that carries a `:calibration`, and that holds for a shipped file
named by `--model` too. Nothing ships one today, so nothing changes. The
design for later: accept a calibration only from a model whose version
says `:source :classpath` (part of the release, reviewed), never from a
file on disk, and never by looking at the path string. Anyone can compute
a fingerprint, so a file on disk could forge a matching report; the
refusal stays for that. `calibrated?` still checks the report's model, its
pool and the model's own bar.

### The model file

`resources/palomar/models/agent-match-n3.edn`, written by `write-model!`;
a test checks it is the fit on train at the thresholds the
cross-validation chose. Its `:provenance`: its `:data` (the nine design
corpora, the benchmark and the split file, with their SHA-256), the
authority file, `:split :n3-train`, the train keys, the pool's
fingerprint, Licence Ouverte / Etalab 2.0, "Source : Bibliothèque
nationale de France". No `:calibration`.

- **Read it** with `--model palomar/models/agent-match-n3.edn` (or
  `reconciliation/read-model`). A path under `palomar/models/` names a
  shipped model when the classpath has one; the version then says
  `:source :classpath` (else `:file`), and the manifest shows it, for
  information (`manifest/diff` ignores it, as it ignores the path). A
  `..` segment in such a path is refused. A file on disk at the same path
  as a shipped model is refused, never read in its place: name it
  `./palomar/models/...` to read the file. Version: SHA-256 `6547f220…`,
  weights `b3454bd60a2ca517`.
- **It refuses its own training split.** `reconciliation/refuse-in-sample!`
  refuses it on a mention whose split key (person or no-match name) is one
  of its `:train-keys`, and on its `:data` files by their SHA-256 (so on
  the benchmark and split files too). The pool is not refused: every split
  is matched against it. The roles model carries the same split and keys,
  and is refused alike. The harness calls the guard for every model it
  scores.
- **The M2 model stays as it is**, and stays the default.

### Leakage

- The fit and the threshold choice read train mentions only (a test spies
  on both). Dev is read only to confirm.
- Dropping every test mention and test record, or changing their text and
  truth, gives the same model and the same thresholds (tested).
- The role profiles read design records only.

### The N3 model on the M2 headings

M2 (the easy benchmark, 142 heading mentions) is out of sample for the N3
model. At 0.8 / 0.5 it proposes all 136 in-pool mentions outright and
right, makes no false link, and sends one to review. For comparison, M4's
leave-one-family-out models make 111 outright links there, with 26
reviews. The shipped M2 model cannot be measured on M2: it is in-sample.
M2 has 33 distinct true mentions.

### Test, run once (spent)

After the first model file and code were final, the test split was run
once, with the first criterion's thresholds: **0.95 / 0.1** for the N3
model, **0.9 / 0.4** for the roles run, 0.95 / 0.5 for the shipped model.
No test asserts these numbers. The thresholds shipped now (0.8 / 0.5) were
derived after that run, from the cross-validation and dev, without test.
The test split is spent: it was not run again, and these numbers do not
measure the shipped thresholds.

475 mentions: 401 linked, 74 no match.

| engine | correct | wrong | missed (tied) | false link | review | precision | recall | macro recall | abstention | no-match abstention |
|--------|--------:|------:|--------------:|-----------:|-------:|----------:|-------:|-------------:|-----------:|--------------------:|
| Jaccard        | 394 | 0 | 7 (4) | 3 | 0   | 0.992 | 0.983 | 0.966 | 0.164 | 0.959 |
| names+dates    | 394 | 0 | 7 (4) | 3 | 0   | 0.992 | 0.983 | 0.966 | 0.164 | 0.959 |
| FS as shipped (0.95 / 0.5)  | 277 | 0 | 124 | 1 | 112 | 0.996 | 0.691 | 0.786 | 0.415 | 0.986 |
| FS N3 (0.95 / 0.1)          | 278 | 0 | 123 | 1 | 113 | 0.996 | 0.693 | 0.803 | 0.413 | 0.986 |
| FS N3 + roles (0.9 / 0.4)   | 391 | 0 | 10  | 1 | 0   | 0.997 | 0.975 | 0.957 | 0.175 | 0.986 |

Recall by stratum: easy (198) Jaccard 0.990, shipped 0.975, N3 0.975,
roles 0.980; homonym (186) 0.984, 0.435, 0.435, 0.973; hand (16) 0.875,
0.188, 0.188, 0.938; variant (1) 1, 0, 1, 1. Hard negatives (2): every
engine links "Stendhal" (a body) to Stendhal. No namesake (72): the
Jaccard makes 2 false links, the Fellegi-Sunter engines none.

The shipped model's row is N3's test row exactly, as it should be.

**What the run shows.** The N3 model's reviews are almost all Simenons
("Georges Simenon" 52, "John Simenon" 44). Two pieces of evidence, neither
from test, explain the gap with the roles run:

- **The pool's ceiling.** The pool holds 11 Simenons. The surname's
  frequency lowers an agreement on it, so an exact "Simenon, Georges"
  heading reaches only **0.922** against its own entry; every other pool
  entry reaches 0.95 or more (tested on the pool). At propose 0.95, no
  Simenon can be proposed: the best goes to review. At 0.9 it can.
- **The separation on the folds of train**, the N3 model and the roles
  model at equal thresholds, `[correct, review, false link]`:

  | thresholds | N3 | N3 + roles |
  |------------|----|------------|
  | 0.95 / 0.1 | 242, 361, 0 | 243, 360, 0 |
  | 0.9 / 0.1  | 243, 360, 0 | 244, 359, 0 |
  | 0.95 / 0.4 | 520, 50, 1  | 522, 48, 1  |
  | 0.9 / 0.4  | 548, 22, 1  | 549, 21, 1  |
  | 0.95 / 0.5 | 520, 50, 1  | 523, 47, 1  |
  | 0.9 / 0.5  | 548, 22, 1  | 550, 20, 1  |

  Lowering propose from 0.95 to 0.9 adds 28 links. Raising review from 0.1
  to 0.4 adds about 300: two candidates above 0.1 send a mention to review.
  The role weight adds 1 to 3. On dev, the role weight even costs links
  at 0.95 / 0.1 (152 against 165).

So the test gap is the thresholds, propose (the Simenon ceiling) and review
(the competing rule), not the roles.

### Recommendation

**Keep the M2 model as the default of `--engine probabilistic`.** Ship
the N3 model as a named file. The evidence is from dev, the
cross-validation and the pool:

- On dev, the N3 model makes exactly the M2 model's links (165, no wrong
  one). That is no measured gain.
- Its propose threshold must sit below the pool's ceiling: with 11
  Simenons, an exact heading reaches 0.922. A threshold chosen on one pool
  does not carry to another.
- It inherits two model defects (flattened name weights, the variant
  cliff) that the next lot should fix first.
- It learnt no date weight: a transcription has no dates. The default
  engine reads headings, which often carry dates; the M2 model weighs a
  date agreement.
- It is not calibrated either.

The Jaccard stays the more complete engine on transcriptions. The
Fellegi-Sunter engines are the more careful ones on no-match names.

### Limits of N4

- Dev is small (36 persons, 15 no-match names); its numbers tie across
  propose 0.5 to 0.95, so it only confirms.
- **The role feature is asymmetric.** It reads the relator codes of other
  design records. On dev, 21 of the 186 true pairs have a known role level
  (164 truths have a heading in another design record); in test, no
  person has one (0 of 401). The test run measured the role weight on
  almost nothing.
- The non-increasing rule and the variant form weight (above) are left as
  they are.

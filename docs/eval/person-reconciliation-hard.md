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

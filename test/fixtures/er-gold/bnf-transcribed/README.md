# ER gold — BnF transcribed mentions (the hard person benchmark)

Real BnF data for the **hard** person-reconciliation benchmark (lot N3):
does Palomar link a name *as the title page gives it* (the statement of
responsibility) to the right person authority, among all the authorities of
the corpus, or abstain? Used by `palomar.eval.person-reconciliation-hard-test`;
the write-up is
[`docs/eval/person-reconciliation-hard.md`](../../../../docs/eval/person-reconciliation-hard.md).

## Source

- **Bibliothèque nationale de France**, SRU API
  (`https://catalogue.bnf.fr/api/SRU`, `version=1.2`,
  `operation=searchRetrieve`, `recordSchema=intermarcxchange`,
  `maximumRecords=100`).
- **Licence Ouverte / Etalab 2.0.** Free reuse, including commercial, with
  attribution: *Source : Bibliothèque nationale de France*. The licence
  allows modification when it is stated; the modification is stated below.
- Fetched 2026-11-11.

## Files

- `records/` — the **design corpora** (train and dev): 9 SRU pages of 100
  INTERMARC bibliographic records each, the first 100 results of one query
  each, `(bib.author all "<name>") and (bib.recordtype any "mon")` for
  `andre maurois`, `bronte`, `colette`, `daudet`, `goncourt`, `rosny`,
  `edgar poe`, `lewis carroll`, `pierre loti`.
- `records-test/` — the **test corpora**, chosen and fetched after the
  construction rules were frozen (`freeze.edn`): 4 pages of 100, the same
  query for `george sand`, `simenon`, `stendhal`, `erckmann chatrian`. Only
  their record counts and well-formed XML were checked before they were
  built.

  **Modified:** in each record only the leader, the control fields and the
  data fields `1XX`, `245`, `260` and `7XX` are kept; every other data field
  is removed, to keep the fixture small. The SRU envelope (query, number of
  records) and the kept fields are verbatim.
- `authorities.xml` — the **205** BnF person authority records (INTERMARC
  Xchange) that the SRU serves for the 292 person authority ids the records'
  person headings cite (`$3`), one query per id (`aut.recordid any
  "<id>"`), each `<srw:record>` verbatim, concatenated in id order under one
  wrapper written for this fixture. **87 cited ids return no record**
  (`numberOfRecords` 0); their pool entry is built from a citing heading
  (the write-up says which).
- `pseudonyms.xml` — the **22** authority records that those authorities
  link by `331` (a pseudonym: "Bell, Currer" for Charlotte Brontë), fetched
  the same way. Read for the pseudonym rule only; they are not candidates.
- `benchmark.edn` — the benchmark, **derived** from the files above and the
  hand labels by `(palomar.eval.person-reconciliation-hard/write!)`: the pool,
  the records' headings, and every transcribed mention with its label,
  split and stratum. A test rebuilds it and checks it is identical.
- `splits.edn` — every scored key's split (train/dev for the design
  corpora, test for the test corpora), with the rule and the salt.
- `hand-labels.edn` — the hand's decisions, one per mention, each with a
  note: residual links verified, and no-match cases whose surname the pool
  holds settled as the same person (linked) or a namesake (a hard
  negative). Labelling, not tuning; done on every split.
- `hand-check.edn` — the hand check of the rules' gold: a stratified sample
  of train, 50 mentions, with a verdict each, and the one error the first
  draw found (fixed).
- `freeze.edn` — the SHA-256 of the namespace holding the construction
  rules, recorded before the test corpora were fetched.

## What the gold is, and is not

- The truth of a transcribed mention is the `$3` of the **one** heading of
  its own record that fits it by its surname, a `400` variant or a `331`
  pseudonym of its authority, or a hand's decision. Where the rules cannot
  decide, the mention is set aside and counted.
- It rests on the BnF cataloguers' links and authority records: where they
  are wrong, the gold is wrong.
- A **no-match** mention is one the record names but heads with no
  authority. Where some pool authority shares its surname, a hand decided:
  the same person is linked, a namesake stays a hard negative.
- Some persons have two or three BnF authorities with the same heading and
  dates (Robert, Claire; Brodovikoff, Léon; Maurat, Charlotte; Simenon,
  Guillaume): a link to the other one counts as wrong, a tie among them as
  an abstention.

## MARC21

No MARC21 corpus is included. The Library of Congress SRU
(`lx2.loc.gov:210`) is not reachable from the build sandbox. The Deutsche
Nationalbibliothek SRU (`services.dnb.de/sru/dnb`, `MARC21-xml`) is
reachable and its bibliographic data is CC0, with 245 `$c` and 100/700
`$0 (DE-588)` GND links; Palomar has no MARC21 authority-pool reader yet, so
it is left for a later lot.

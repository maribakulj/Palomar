# ER gold — BnF transcribed mentions, the external test (lot O5)

Real BnF data for the **external test** of the hard person benchmark: a
test split nobody has seen, built by N3's frozen rules and scored once. Used
by `palomar.eval.person-reconciliation-external-test`; the write-up is
[`docs/eval/person-reconciliation-external.md`](../../../../docs/eval/person-reconciliation-external.md).

## Source

- **Bibliothèque nationale de France**, SRU API
  (`https://catalogue.bnf.fr/api/SRU`, `version=1.2`,
  `operation=searchRetrieve`, `recordSchema=intermarcxchange`,
  `maximumRecords=100`, `startRecord=1`).
- **Licence Ouverte / Etalab 2.0.** Free reuse, including commercial, with
  attribution: *Source : Bibliothèque nationale de France*. The licence
  allows modification when it is stated; the modification is stated below.
- Fetched 2026-11-11, after the protocol was frozen (`freeze.edn`), as far
  as the self-recorded times show. `fetch-log.edn` gives each response's
  `Date` header.

## Files

- `protocol.edn` — the protocol, written and hashed before the fetch.
- `freeze.edn` — the SHA-256 of the protocol, of the code namespace and of
  the doc's "Protocol" section, and the time they were frozen.
- `records/` — 5 SRU pages of 100 INTERMARC bibliographic records, the first
  100 results of `(bib.author all "<query>") and (bib.recordtype any "mon")`
  for `voltaire`, `musset`, `christine de pizan`, `mardrus`, `rabelais`:
  the first five candidates of the protocol's selection order, each with at
  least 100 records. Only record counts and well-formed XML were checked
  before the build.

  **Modified:** in each record only the leader, the control fields and the
  data fields `1XX`, `245`, `260` and `7XX` are kept; every other data field
  is removed (N3's trim). The SRU envelope and the kept fields are verbatim.
- `authorities.xml` — the **117** BnF person authority records that the SRU
  serves for the 165 person authority ids the records' person headings
  cite (`$3`), one query per id (`aut.recordid any "<id>"`), each
  `<srw:record>` verbatim, concatenated in id order under one wrapper
  written for this fixture. **48 cited ids return no record**; their pool
  entry is built from a citing heading (N3's rule).
- `pseudonyms.xml` — the **3** authority records those authorities link by
  `331`, fetched the same way. Read for the pseudonym rule only.
- `fetch-log.edn` — every request, in order, with its response's `Date`
  header and record count.
- `hand-labels.edn` — the hand's 20 decisions, by N3's rule, each with a
  note, written before any engine ran.
- `benchmark.edn`, `splits.edn` — **derived** by
  `(palomar.eval.person-reconciliation-external/write!)`: the pool (N3's
  292 entries and 165 new ones), the records' headings, and every
  transcribed mention with its label and stratum. A test rebuilds them.
- `labels-freeze.edn` — the SHA-256 of the hand labels, the benchmark and
  the split file, and the time, written before the run.
- `run.edn` — the single run: its start time, the labels' SHA-256 it read,
  the model versions, every figure, the errors and the decisions.

Written after the run (the O5 review), with this README:

- `raw-headers/` — the HTTP headers of every SRU response (5 pages, 165
  authorities, 3 pseudonyms), as saved on the build machine. A test checks
  that `fetch-log.edn`'s dates are theirs.
- `raw-sha256.edn` — the SHA-256 of the raw responses, which are not
  committed (2.8 MB), and of what the scripts made from them. Re-running
  the scripts (`dev/eval/bnf-external/`) gave the fixtures byte for byte.
- `postrun.edn` — the base commit and the SHA-256 of `src/` and of the
  namespaces the run called, recorded after the run: the freeze did not
  hold them. A record, not a live check: `dev/eval/bnf-external/verify-postrun.sh`
  recomputes the hashes from the base commit (`git show`) and compares.

## What the gold is, and is not

As N3's: the `$3` of the one heading of the mention's own record that fits
it, a `400` variant or a `331` pseudonym of its authority, or a hand's
decision. Where the rules cannot decide, the mention is set aside and
counted. It rests on the BnF cataloguers' links and authority records.

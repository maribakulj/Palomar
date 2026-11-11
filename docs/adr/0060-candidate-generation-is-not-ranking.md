# 0060 — Candidate generation is not ranking: lanes find, the matcher decides

- Status: Accepted
- Date: 2026-11-11
- Amends: [ADR 0038](./0038-authority-files.md) (its M6 amendment: the
  index's blocking keys are now the name lane), [ADR 0018](./0018-entity-resolution-at-scale.md)
  (amendments M4 and M6: what the matcher compares)
- Builds on: [ADR 0058](./0058-a-mention-is-one-occurrence.md) (mentions),
  [ADR 0059](./0059-record-identity-is-not-entity-identity.md) (ids in one
  form, `agent-equivalence/identifier-key`)

## Context

Reconciling a person mention does two things. It finds the authority
records that may be the mention's person. Then it weighs each one and
decides. Until now the two were mixed. The authority index's `block`
(`palomar.authority-index`) found candidates by name keys. The matcher
(`reconcile/agent-candidates`) took a `:block` function of the name, and
also dropped every candidate whose name level was `:none`. So a candidate
could only ever be found by its name.

A name is not the only way in. Many mentions carry an id: a BnF field's
`$1` (an ISNI), a UNIMARC `$o`, a MARC21 `$0` or `$1`, a LIDO `actorID`,
an EAC-CPF `entityId` or `otherRecordId`. A BnF field's `$3` names the
authority record too, but it is a link already made, not an id to
reconcile (§3). Many
authority records state ids too: their own IRI, their ISNIs, their
`sameAs` links (ADR 0059). An id finds the record a name misses: a name in
another script, a pseudonym, a heavily variant form.

## Decision

### 1. Generation is its own step

`palomar.candidates` generates candidates. It does not score them.

Generation is a set of **lanes**. A lane is a function of a candidate
index and a mention. It returns the entries it finds. Each candidate names
its lane and the keys that matched: why it was found. A lane may also
return reports for a curator.

The **union** of the lanes is one candidate per entry, in index order.
Each candidate lists every lane that found it, with its keys. The order of
the lanes asked does not change the union.

### 2. Two lanes

- **`:name`.** The entries that share a blocking key with the mention's
  name (`authority-index/blocking-keys`: its tokens, its surname and the
  surname's one-letter deletions). It is exactly the index's old `block`,
  entry for entry and in the same order. Without an index file, a pool is
  indexed in memory, in its own order.
- **`:identifier`.** The entries whose stated ids match an id the mention
  carries. Both sides are read in one form (`identifier-key`). An entry's
  ids are its IRI (`:iri`, else `:id`), its valid ISNIs and its `sameAs`
  (`agent-equivalence/authority-keys`).

### 3. What a mention's ids are, and what is not an id

`palomar.mentions` puts a mention's ids under `:ids`, in source order:

- INTERMARC `$1`, an ISNI, as written;
- MARC21 `$0` and `$1`: a URI as given; a `(code)value` by its code, in any
  case: `(isni)`, `(viaf)`, `(orcid)`, `(uri)` (MARC source codes), a
  `(DE-588)` number as its GND IRI, a `(DLC)` number as its LoC IRI (a
  name as LCNAF, an `sh` subject heading as LCSH, never as a name);
- UNIMARC `$o`, the ISNI;
- LIDO `actorID`, as before;
- EAC-CPF `entityId`, and now each `otherRecordId`. The importer keeps
  these as natives (`:eac/other-record-id`); they are not projected.

A value is an id only when `agent-equivalence/identifier` reads it. An
ISNI whose check character is wrong is still an id, so that the lane can
report it. A value of those subfields that no id form reads is kept as
`:unread-ids`: an `(OCoLC)` number, a `(DE-588a)` number (an old GND
number: it names no current record, and the GND states it under another
id), any other string.

**A `$3` is not an id to reconcile.** It is authority control: the record
the cataloguer already linked. On the evals it is also the gold. The
mention keeps it apart, as `:authority-record`, and no lane reads it. An
INTERMARC `$3`, and a UNIMARC `$3` in a BnF record (its `001`, its `003` or
an `801 $b` says so), is the BnF record number. It is kept as that
record's ARK, whose NOID check character is computed
(`agent-equivalence/bnf-ark`); the computation was checked on 2 241 real
BnF ARKs. A UNIMARC `$3` in another record says no file (SUDOC, another
agency) and is not kept.

### 4. What the identifier lane reports, and never does

It reports an id that matches no entry (`:no-authority`), an invalid ISNI
(`:invalid-identifier`), every value of `:unread-ids`
(`:unread-identifier`), an id several entries state
(`:several-authorities`), and ids that name several entries between them
(`:ids-disagree`). In the last two cases every entry named is a
candidate. **The lane never picks one.**

### 5. Ranking stays the only decider

`candidates/rank` passes each mention's union to
`reconcile/agent-candidates` as its `:block`. The matcher scores the union
and does not know the lanes. Each scored candidate carries them only as
provenance (`:generated-by`). The result also lists, per mention, the
candidates and the reports (`:generation`). `rank` deduplicates mentions as
the matcher does: with a model, mentions the matcher reads alike are
scored once (their ids tell them apart when the identifier lane runs);
name-only, none is. So its items are `agent-candidates`' item for item.

**An identifier-lane candidate is still ranked. It does not short-circuit.**
The model (`palomar.agent-match`) has no identifier feature: it reads no
authority number, no ISNI, no record id. We do not invent weights for one.
So such a candidate is weighed on its names and dates, as any candidate.

One change in `reconcile.clj` makes that possible. The matcher drops a
candidate whose name level is `:none`. With `{:score-unnamed ids}`, the
entries of those ids are compared and weighed even then. `rank` passes
every candidate a lane other than the name lane found, even when a name
key found it too: a name key at name level `:none` would otherwise hide
it. The id itself adds no weight. A name in another script then weighs as
`:none` does. Name-only ranking keeps M3's threshold: a candidate whose
name scores below it is dropped, whichever lane found it.

**A candidate with no name agreement is for review at most.** Its dates
can outweigh its name: the BnF's Hetzel and the GND's Stahl share their
dates, and an ISNI crossed between the files brings Stahl to a Hetzel
mention. Such a candidate (name level `:none`) is never proposed outright:
above the propose threshold it is `:review`, `:review-reason
:no-name-agreement`. And it never makes a candidate a name agrees with
`:competing`: Hetzel is still proposed. Without `:score-unnamed` no
candidate has name level `:none`, so nothing changes for M2 to M8.

### 6. Recall is measured on generation

`candidates/candidate-sets` gives each mention's candidates per lane and
for the union. `candidates/recall` says, per lane and for the union, how
many truths are among the candidates, and at what cost (candidates over
every mention, the mean, the most for one mention). It also says how many
truths one lane alone finds. These are what the hard benchmark (lot N3) or
a ranking study (lot N4) can call.

## What the tests show

- With the name lane alone, generation then ranking gives every M2, M3, M4
  and M6 result, mention by mention (`palomar.eval.candidate-lanes-test`).
  No number in `docs/eval` moves.
- Each lane's candidates carry their lane and keys; the union is
  deduplicated and in index order; the name lane is the index's block on
  the real GND index (`palomar.candidates-test`).
- Each id form is read; an id that names no entry, or two, is reported, and
  both are ranked; an invalid or unread id is reported; a `$3` is never
  read by a lane; a candidate an id brings is weighed on its names and
  dates, its id adding no weight, and with no name agreement it is for
  review at most.
- No `$3`, no ARK made from one, no truth and no field subfields reach a
  lane or the matcher in any eval (M2 to M8, N5).
- On the benchmarks the name lane already finds every truth. The identifier
  lane brings no truth the name lane misses on M2, M6, the stress set or
  LIDO. On a synthetic set of the 33 gold headings in Cyrillic letters, it
  finds 32, and 30 that the name lane does not. Ranked, all 32 are
  abstained. See `docs/eval/person-reconciliation.md`, section N5.

## Alternatives considered

- **Short-circuit on an id**: propose the entry an id names, without
  ranking. An id is often right, but not always: on M6 the BnF's Hetzel and
  Stahl state ISNIs that the GND gives to each other's records (ADR 0059).
  A short-circuit would link them crosswise, with no score to doubt it.
- **Add an identifier-agreement weight.** The model's weights are measured,
  never set by hand (M4). On these benchmarks an id is the gold or close to
  it, so there is nothing honest to measure it on. Left for a benchmark
  where ids and truth are independent (N3).
- **Make the name block read ids.** It would hide which way a candidate
  came in, and the name block's guarantee (every entry the matcher could
  score by its name) would no longer be a statement about names.
- **Read `$3` as an id.** It is the link the cataloguer already made, and
  the evals' gold: a lane reading it would measure nothing and would only
  confirm what the record says. It is kept as `:authority-record`, for a
  consumer that wants the stated link, not for generation.
- **Read a bare UNIMARC `$3` outside a BnF record.** It names a record, not
  its file. Guessing the file would make ids that match the wrong records.

## Consequences

- A new lane (a phonetic key, a transliteration, a related work) is a new
  function in `palomar.candidates`, measured by `recall` before it reaches
  ranking.
- The identifier lane makes candidates visible to a curator (abstained, or
  for review) that ranking alone would never have seen. It cannot make a
  link that names and dates do not support.
- The CLI `reconcile`, `align-agents` and the API still call
  `agent-candidates` directly, with the name block. Moving them to `rank`
  is left to a later lot (N2 is changing those paths).
- A `$3` is now on the mention, as `:authority-record`, never under
  `:ids`. A test checks that the evals never pass it to a lane or the
  matcher.
- The review of this lot found that the BnF reader dropped every `031`
  ISNI whose `$2` said "VIAF". `$2` is the ISNI's source, not its scheme.
  Reading them moved the M6 truth (20 bridged authorities, not 19) and ADR
  0059's counts (19 stated pairs, 17 kept). See
  `docs/eval/person-reconciliation.md`, section M6, "Correction".

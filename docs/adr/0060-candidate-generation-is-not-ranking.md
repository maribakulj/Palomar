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

## Amendment (lot O6, 2026-11-12): lanes reach the API and the CLI; pool statistics are prepared once

### Context

Lot N5 left two things open. The CLI and the API still called
`agent-candidates` with the name block, so no run could use the
identifier lane. And `rank` called `agent-candidates` once per mention.
Each call recomputed what it reads of the whole pool: the surname
frequencies and their mean, the pool size, the pool's fingerprint and,
with anchors, the relations fingerprint and the resolved relations. The
cost grew with mentions × pool size.

### Decision

**1. Prepared statistics.** `reconcile/prepare` computes them once per
run, from the pool (and from the model and anchors, when relations are
read). `agent-candidates` and `candidates/rank` take the result as
`:prepared`. Without it they prepare it themselves, once per call; `rank`
does so once for all its mentions. The mean (`tf-mean`) is now computed
once too: before, it was computed per compared pair. The result is the
same, item for item. A prepared map names the pool it was made from (the
same object), and with anchors the model and anchors. Given with another
pool, or other anchors or another model, it is refused. The name-only
path reads none of it.

The prepared map is opaque. The matcher reads the mean only from a
namespaced key that `prepare` sets (`compare-pair` never reads a plain
`:tf-mean`, and refuses an explicit nil mean beside surname counts). The
map seals what it states: a map whose size, fingerprint, counts or mean
were changed since is refused. The rule of the check:

- the **pool by identity**: the same object. Comparing a pool by value
  costs what preparing it saves;
- the **model and the anchors by value**: the model's fingerprint
  (`agent-match/model-fingerprint`) and the relations fingerprint
  (`agent-relations/relations-fingerprint`). An equal model or equal
  anchors, other objects, are accepted.

**2. The probabilistic engine ranks the lanes' candidates.**
`palomar.reconciliation` decides each mention with
`candidates/rank-each`: `rank`, one result per mention, the pool
prepared once. The name lane reads the postings of an authority index
when the pool was read from one (`read-authorities` keeps it as
`:index`). A pool `{:entries :block}` read from no index keeps its own
block: the name lane's candidates are then the entries `(block name)`
returns, in pool order, as the matcher was given them before. Otherwise
the entries are indexed in memory. A mention reaches
the lanes and the matcher without its `:authority-record` (the `$3`) and
without its `:source` (whose subfields hold the `$3`). Neither reads them.
`reconcile/align-agents` still calls `agent-candidates` with the name
block; moving it is left open.

**3. `:lanes` and `--lanes`.** `reconcile`, `explain` and `clusters` take
`:lanes` (`[:name]`, `[:identifier]` or both). The CLI takes `--lanes
name,identifier` on `reconcile`, `reconcile explain` and `reconcile
clusters`. The default is the name lane. Its decisions are lot N2's, and
the output is byte for byte what it was. The order the lanes are named in
does not matter. An unknown or empty lane is refused (exit 2).

**4. Lanes asked are stated.** When lanes are asked, the result carries
`:lanes`, in lane order, and so does the run manifest's `:reconcile`.
`explain` then gives each candidate its `:generated-by`: the lanes that
found it and the keys that matched. Its text adds a line `found by: …`.
Each decision, and each explanation, then carries the mention's
`:reports`: what the identifier lane reports (`:several-authorities`,
`:ids-disagree`, `:no-authority`, `:invalid-identifier`,
`:unread-identifier`, §4). The text adds a line `report: …` for each.
`rank-each` gives each mention its own `:generation`, its own reference
included, even when it shares a result with a mention read alike.
With no lane asked, none of this is added: the default output does not
change. A candidate an identifier alone brings, with no name agreement,
is for review at most. `explain` says why: "no name agrees".

The manifest states `:lanes` only when lanes are asked: absent by
default, `[:name]` for `--lanes name`. So `manifest/diff` reports a
default run and a `--lanes name` run as different. That is right: their
outputs differ too (the EDN states `:lanes` and the reports).

**5. The historical engine reads names alone.** It refuses any lane but
the name lane (exit 2 in the CLI; an error from the API). `--lanes name`
is accepted: it is what the engine does already. Its text does not
change; its EDN states `:lanes [:name]`.

### What the tests show

- Prepared and unprepared statistics give equal results, on random pools
  and mentions, for `agent-candidates`, `rank` and `compare-pair`; and
  `rank` by the name lane with prepared statistics equals the unprepared
  full scan (`palomar.prepared-pool-property-test`).
- A ranking computes the pool's statistics once, whatever the number of
  mentions; prepared statistics of another pool are refused
  (`palomar.prepared-pool-test`).
- The default decisions equal lot N2's on five inputs and three pools, one
  an authority index; the CLI writes the same text; the CLI's EDN is the
  API's with lanes, for `reconcile`, `explain` and `clusters`; the
  historical engine refuses the identifier lane; explain names the lanes
  and keys; the manifest names the lanes; a Cyrillic heading reaches
  Schlienger by its ISNI and is `:review`, never `:propose`; no `$3`
  reaches a lane or the matcher on this path; `--lanes` names no file; a
  hand-made block decides as before; the name lane reads the index
  file's own postings; the identifier lane's reports reach each decision
  and each explanation (`palomar.reconciliation-lanes-test`).
- A tampered prepared map is refused; the mean is read only from
  `prepare`'s key; an equal model and equal anchors are accepted, an equal
  pool that is another object is not (`palomar.prepared-pool-test`).
  Prepared equals unprepared with random frozen anchors too, on pools of
  up to 40 entries with homonyms and frequent surnames
  (`palomar.prepared-pool-property-test`).
- No eval number moves. The evals run with the name lane alone, as before.
- Review of this lot (a contradictor's mutants). Each was killed by a test,
  except three that are equivalent, and so cannot be:
  - M4b, no stripping of `:authority-record` in `candidates/rank`: the
    reconciliation path strips it first, and no lane reads it; the strip
    in `rank` is defence in depth.
  - M4c, the reconciliation path stripping `:source` only: `rank-each`
    strips `:authority-record` itself.
  - M9, no `:prepared` passed by the run: `rank-each` then prepares the
    pool once itself. The statistics are still computed once per run.

  The mutant that reads no index postings (M8) is killed by
  `the-name-lane-reads-the-index-postings`.

### Measured

A synthetic pool, 1 000 mentions, the shipped model, `candidates/rank`:

| Pool | Before (name lane) | After (name lane) | After (name and identifier) |
|------|--------------------|-------------------|-----------------------------|
| 10 000 entries | 207 s | 2.0 s | 1.7 s |
| 50 000 entries | 1 289 s | 7.3 s | 7.5 s |

The proposals are the same before and after. The benchmark is not in the
suite.

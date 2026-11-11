# 0058 — A mention is one occurrence, read from itself alone, whatever the format

- Status: Accepted
- Date: 2026-11-11
- Amends: [ADR 0018](./0018-entity-resolution-at-scale.md) (amendments M3–M8:
  what a mention is, and where it comes from)
- Builds on: [ADR 0011](./0011-fragments-for-qualified-values.md) (field
  fragments), [ADR 0041](./0041-resemblance-identity-shared-node.md)
  (name-only agents scoped to their record), [ADR 0056](./0056-who-did-what-and-what-it-shows-are-rules.md)
  §3 (a LIDO actor is its occurrence)

## Context

The matcher (`reconcile/agent-candidates`, lots M3–M8) takes mentions
`{:name :dates :events :record :id :role}`, but no one produced them in one
way. The CLI `reconcile` passed only the distinct names of
`plugins.agents/occurrences` (the canonical floor); `agent-events` built
LIDO mentions (`lido-mentions`); the eval of lots M2–M8 rebuilt INTERMARC
700 names from field fragments, because the classic INTERMARC mapping
(`:map/intermarc-agent`) carries only the 100's `$a $m` — and its
`:mapping/combine` joins the record's *first* `$a` and `$m`, not those of
one field. The same holds for UNIMARC's forename `$b`, which the floor does
not lift for the same reason. Each consumer had its own idea of a mention's
id, role and dates.

## Decision

1. **`palomar.mentions`** extracts every agent occurrence of a record, in
   document order, as

   ```clojure
   {:id [record-id field n] :record record-id :local node-id
    :type :agent|:subject|:provenance|:described :name "…"
    :role "…" :roles ["…" …] :relator "…" :relators ["…" …]
    :kind :person|:group|:family :responsibility :creator|:performer|…
    :dates profile :events [typed-event …]
    :source {:format :field :occurrence :evidence …}}
   ```

   a key absent when the source does not say it. The namespace is
   `palomar.mentions`, beside `agent-dates`, `agent-events` and
   `agent-relations`, not under `palomar.reconcile`: it reads the importers'
   natives (it requires `plugins.lido-rules`, `plugins.eac`,
   `plugins.marcxml`), and the matcher takes mentions as plain data.

2. **Per occurrence, never mixed.** A MARC-family mention reads the field
   fragment the importer keeps (`:locator {:tag :occurrence :subfields}`):
   its name, dates, roles and relators are *that field's*. The name and the
   dates are the first of their subfields (a repeated `$a` gives its
   first); `:roles` and `:relators` keep every value in source order,
   `:role` and `:relator` the first. The canonical floor is not read and is
   not changed by this lot.

3. **Sources.**
   - **INTERMARC (B)**, by the BnF's zone sheets (*INTERMARC (B) – version
     10.0 – mars 2014*, zones 700, 701, 702, 710, 711, 712, 720, 721, 722,
     730) and function tables (*Référentiel du format INTERMARC
     Bibliographique : Fonctions*, July 2015, which also name 725–727 and
     731–737): persons 100 (author), 101 (performer), 700 (author), 701
     (performer), 702 (technical and artistic collaborator), 720 (commercial
     publisher), 721 (distributor), 722 (sound producer), 725 and 726
     (producer), 727 (manufacturer), each `$a, $m`, `$d`, `$4`, a family
     when the second indicator is 5 ("Nom générique de famille, association
     familiale"); groups 110, 111, 710, 711, 712, 730, 731, 732, 735–737,
     each `$a`, `$4`, no life dates (a group's `$d` dates a meeting). **723
     is read as a label** (a group): no sheet was at hand, and the corpus's
     723s are record labels (Toccata classics, Webmusic, Decca, Bear
     family, Grand piano). **703** is a provenance or possession relation
     to a copy (the BnF's notes on the 2022 format changes, "Relation de
     provenance ou de possession": the 703/713/723 zones), and so is any
     agent field whose first function code is a copy's (4010 "Ancien
     possesseur" to 4990 "Intervenant sur l'exemplaire"). 713 is not read.
   - **MARC21**: persons 100, 700 (a family when the first indicator is 3)
     (`$a` as the floor carries it; `$d`; roles `$e` else `$4`; relators
     `$4`; `$b $c $q` kept apart as `:name-parts`), groups 110, 710 (`$e`)
     and meetings 111, 711 (`$j`).
   - **UNIMARC**: persons 700–702 (`$a, $b`; `$f`; `$4`), groups 710–712,
     families 720–722; 7X0/7X1 the primary and alternative responsibility,
     7X2 the secondary; 703/713/723 provenance (the same 2022 notes).
   - **LIDO**: each `actorInRole` of each event, with the one event it is
     named in, typed (lot M7), its role terms, its role concept IRI as
     relator, its type as kind, its vital dates (none for a group, none
     when they read as no date), its authority ids.
   - **Authority records** — EAC-CPF, and a MARC-family authority record
     (INTERMARC and UNIMARC by their `type`, MARC21 by leader 06 `z`): one
     mention, the described entity (`:type :described`), its heading
     (INTERMARC/MARC21 100, 110, 111; UNIMARC 200, 210, 220). Its variants,
     related headings and 6XX are notes on that entity, never mentions.
   - Dublin Core `creator`, `contributor`, `publisher`; MODS `name` (no
     role: the importer does not read `role`).

4. **The name** is the heading as the source gives it, trimmed: what the
   matcher compares (its tokens drop punctuation). For INTERMARC and
   UNIMARC that is surname and forename joined `", "` from the same field;
   for MARC21, `$a`, the canonical floor's own value. **The dates** are the
   `agent-dates` profile of the life-dates text the occurrence gives, its
   `:text` that text verbatim. **The role** is the source's, verbatim (a
   code or a term); **the relator** the code when there is one.

5. **One local node rule** (`mentions/local-agent`), used by both the
   extraction (`:local`) and `reconcile/align-agents`: the record and the
   name (ADR 0041), of the class the mention's stated kind gives (a Group
   for a group or a family), else the authority's `:entity-type`, else a
   Person; for a LIDO record, the rules' and Linked Art's actor node
   (`lido-rules/agent-node`), the actor being the mention's when its id is
   given, else the one node the record's actors of that name make (none
   when they make several); for EAC-CPF, the projection's agent. A
   proposal made for a mention carries its `:kind`, so `align-agents`
   mints the mention's `:local`. A mention that states no kind (DC, MODS)
   has the Person node as `:local`; against an authority typed a corporate
   body, `align-agents` mints the Group node instead.

   **What align-agents cannot reach.** It finds a record's agents through
   the canonical floor's names. An INTERMARC 7XX, 110 or 710 heading and a
   UNIMARC `$a, $b` heading are not on the floor, so they get no alignment
   until `align-agents` is fed mentions (lot N2). The rule is shared, the
   reach is not yet.

6. **Subjects and provenance are not agents of the work.** Typed
   `:subject` (MARC21 600/610/611, INTERMARC 600, UNIMARC 600–602) or
   `:provenance` (above), they carry no event (an edition is neither its
   subject's act nor its owner's) and are left out unless `{:subjects?
   true}` or `{:provenance? true}`. LIDO's `subjectActor` is not read in
   this lot: its reading is being revised separately.

7. **Ids are positional.** An occurrence's id is its record's id, its
   field (the MARC tag; `"actorInRole"` with the LIDO event and position;
   `"dc:creator"`…) and its index among the record's fields of that tag
   (for DC and MODS, among its values of that element, out-of-force ones
   counted, so that curating one value renumbers none). It depends on no
   other record nor on the order records are read in. It is not a content
   identity: a field inserted in the source shifts the indices of the later
   fields of its tag. For INTERMARC it is the eval's M8 mention id `[record
   tag occurrence]`, so every anchor, support and confirmation choice of M8
   is unchanged.

8. **The consumers use it.** The M2–M8 eval reads its 142 mentions from
   `palomar.mentions`; M7's LIDO mentions are its own
   (`agent-events/lido-mentions` is gone; `agent-events` keeps the typing,
   `lido-kind`, `lido-date`, `edition-events`, which the extraction calls).
   `agent-relations` builds no mention from a record.

## What the tests show

- A golden file of 15 real records (INTERMARC, UNIMARC, MARC21, LIDO;
  `test/fixtures/mentions/golden-mentions.edn`) written by an independent
  reader of the XML (`golden_reader.py`, no Palomar code): the extraction
  gives exactly its ids, types, names, kinds, date texts and relators.
- On 210 real INTERMARC records, every mention's dates are its field's
  first `$d` (none for a group), its relators every `$4`, its role the
  first `$4`, its name the first `$a` and `$m`.
- A record whose floor is removed or altered gives the same mentions.
- `align-agents` mints each reachable mention's `:local` (INTERMARC 100,
  MARC21 person, family and group, UNIMARC 712, LIDO, DC, MODS), with and
  without an authority entity type.
- The eval's asserted numbers (M2–M8, M6 on the GND) are unchanged.

## Alternatives considered

- **Fix the INTERMARC mapping** so that `:canon/agent` carries every 700
  per field. It would change the floor every exporter reads (DC, MARC21,
  Linked Art creators) and the fidelity audit's accounting; the fragments
  already hold what is needed. Left for a lot that changes the floor on
  purpose, or for N2, which feeds `align-agents` mentions.
- **Read `:canon/agent` with its `:canon/role`** (`plugins.agents`): it has
  no dates, no relator, no occurrence, and UNIMARC's forename is not on it.
- **Mint ids from content** (a hash of the name and dates): two identical
  700s of one record would share an id, and an edit of a name would move
  the id of its occurrence.

## Consequences

- One mention contract for every consumer; a new format adds a reader here,
  not in each consumer.
- The role of a mention whose field has no `$4` is absent, where the M2
  eval put the tag; no number reads it.
- The CLI `reconcile` still matches the distinct canonical names
  (`agents/occurrences`); moving it to the mentions is left to a later lot.
- INTERMARC 713, INTERMARC-NG, EAD and IIIF agents are not read.

## Amendment (lot N2, 2026-11-11): the engines reach the API and the CLI

The matcher of lots M3–M8 was library code. `palomar reconcile` matched the
distinct names of the canonical floor, and no public function passed it a
mention. This lot makes the probabilistic path a choice of the public API
and of the CLI. It changes no default. It was revised after a
contradictory review (items below marked *review*).

1. **One namespace runs a reconciliation**: `palomar.reconciliation`.
   `palomar.api` (`reconcile`, `reconcile-explain`, `reconcile-clusters`,
   `read-authorities`, `reconciliation-model`, and the three `format-…`
   functions) and the CLI `reconcile` verb both call it. The CLI renders
   what the API returns, so the two give the same result (tested on both
   engines, with `--model` and with `--record-id`).

2. **Two engines.**
   - `:historical`, the default: the floor's distinct names, matched by
     token-set similarity at 0.5 (`reconcile/propose-agent-links`), beside
     the reconciliation by authority id. Its output is byte for byte what
     `palomar reconcile` wrote before. Its decisions are its proposals.
   - `:probabilistic`: every mention of the batch (`mentions/batch`) is
     passed whole to `reconcile/agent-candidates` under a Fellegi-Sunter
     model. Its name, dates, kind and events are read; its roles are
     reported (`explain`) but weigh nothing, as no anchors (lot M8) are
     passed. The mentions are scored together, as the matcher scores them;
     each finds its own results by the key the matcher put on them
     (`reconcile/matcher-key`). A batch that names one mention twice (a
     record given twice) is refused. A mention's decision is its best
     proposal's (`:propose` or `:review`), else `:abstain`, else
     `:rejected`, else `:none`.

3. **A confidence is only a proven one** (*review*). A decision states a
   `:confidence` only where `agent-match/proven-confidence` holds. A
   calibration report now names the model it was computed on
   (`agent-match/model-fingerprint`: the model without its calibration and
   its relational weights), and `calibrated?` and `proven-confidence`
   check it: a report computed on another model, or one that names no
   model, proves nothing. A `--model` file that carries a `:calibration`
   is refused, for now. Nothing else writes a confidence.

4. **Kinds** (*review*). When a mention states its kind and a candidate its
   entity type, and they differ, the candidate is rejected before it is
   weighed (`:reason :kind-mismatch`). A group mention against a pool of
   persons has no candidate, or is rejected; it is no longer abstained.
   On the Verne records, no candidate of the mention's own kind is lost.
   A candidate typed a person is now checked against the mention's events:
   before, any `:entity-type` skipped the check.

5. **The reach of §5 is lifted for the probabilistic engine.** It reads
   mentions, not the floor, so an INTERMARC 700 or 710 and a UNIMARC `$a,
   $b` heading reach their candidates. The tests use a model whose fold
   held the mention's family out, not the shipped one. The historical
   engine still reads the floor. `align-agents` is unchanged, and this lot
   writes no alignment.

6. **The model is a parameter file**, `palomar/models/agent-match.edn` on
   the classpath. It is checked (*review*): counts for every feature on a
   positive number of pairs, 0 < prior < 1, 0 < review ≤ propose < 1;
   anything else is refused. `--model <file>` reads another one.
   - **It is fitted in-sample on M2** (*review*): the M4 model fitted on
     all five families of the M2 gold. It must not score M2, nor any
     benchmark built from those records or that pool.
     `reconciliation/refuse-in-sample!` refuses it there (its pool as the
     eval reads it or as `read-authorities` does, its records by their
     SHA-256); the eval calls it, and a benchmark can
     (`palomar.eval.person-reconciliation/refuse-in-sample!`). The M-cycle
     eval never reads the shipped model (tested).
   - **Its provenance** is in the file, as data: the three SRU pages, the
     authority file, the split, the pool fingerprints, the licence
     (Licence Ouverte / Etalab 2.0) and the attribution ("Source :
     Bibliothèque nationale de France"). It is not calibrated.

7. **The version is the model's** (*review*). `model-version` derives it
   from the model used: the SHA-256 of its parameters as the model holds
   them, calibration and provenance included, and the fingerprint of its
   computed weights. A comment or a layout change is no new version. A
   `{:model :version}` whose version is not its model's is refused. The
   path is for information only: classpath-relative for the shipped file,
   ignored by `manifest/diff`, never in the text output.

8. **The manifest names the engine and the model** under `:reconcile`:
   `{:engine :historical :model {:name "token-jaccard" :threshold 0.5}}`,
   or `{:engine :probabilistic :model version :provenance {..}}`.

9. **`explain`** says why one mention was decided as it was: each
   candidate's comparison levels, the weight of each field and of the
   surname's frequency, their total, the prior's weight (log2 λ/(1-λ)),
   the posterior, the thresholds, the decision and why; a rejected
   candidate with its reason, never weighed.

10. **`clusters`** groups the batch's mentions with `agent-cluster/cluster`
    (ADR 0057): an outright proposal joins a mention to its authority, one
    for review is a candidate merge left to a curator, a rejection is a
    cannot-link (listed under `:cannot-link`), and two authorities are
    never one cluster. Each cluster names its members' occurrences and the
    decisions that joined them. It writes nothing into the records.

11. **The CLI**, in the idiom of `authorities index`, a subcommand first:

    ```
    palomar reconcile          <input> --from <fmt> [--authorities <f>] [--engine historical|probabilistic] [--model <f>] [--format text|edn] [--manifest <f>] [--record-id <id>]
    palomar reconcile explain  <input> --from <fmt> --authorities <f> --mention '<id>' [--model <f>] [--format text|edn] [--manifest <f>] [--record-id <id>]
    palomar reconcile clusters <input> --from <fmt> --authorities <f> [--model <f>] [--format text|edn] [--manifest <f>] [--record-id <id>]
    ```

    A mention id is given as EDN, as the probabilistic output prints it.
    Refused: `explain` or `clusters` with `--engine historical`, `--model`
    with the historical engine, `--mention` outside `explain`, an unknown
    `--from`, a model file that fails its checks or carries a calibration.

### Limits

- The shipped model learnt from 136 exact person headings of one
  institution, in-sample. Its numbers on M2 measure nothing.
- Roles weigh nothing without anchors, and the CLI passes none.
- The probabilistic decisions are not written into the records: no
  alignment, no membership. `align-agents` and `cluster-records` keep their
  inputs.
- With a star of links (each mention to its authorities), a rejection
  cannot change a cluster's members: a mention never has a link to an
  authority it was rejected against. It is listed as a cannot-link.

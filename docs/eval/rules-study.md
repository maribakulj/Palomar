# Interpretation as rules: a study (post-audit 8)

*2026-11-04. Should the CIDOC CRM projection of a museum object be written as
rules and vocabularies (data) rather than as Clojure code? Measured on one
slice, against the code it would replace and against an independent mapping.*

## The question

Seven audits found, each time, a new heuristic in the museum projection that
said more than the source did: a design used by a production, a commissioner
who made the object, a seller who carried out a sale, two namesakes merged.
Each was fixed, then guarded by a registry and tests written beside the
code (ADR 0055). Those guards describe the code; they are not the code, and
they can drift from it.

Palomar has a declarative rule language for this (ADR 0002, ADR 0035),
written to take rich mappings "out of what an institution can read, review or
adapt as data". The WEMI chain uses it. The museum projection does not:
`lido.clj` (1 000 lines) and the Linked Art exporter (780) interpret the
source in code, each on its own.

## What was built

- **Two extensions to the rule language**, closed like the rest (no function
  in a rule):
  - `(field ?v :key x)` reads a field of a structured value, a LIDO actor or
    event;
  - `(vocab-some? table term concept)` and `(vocab-all? …)` look a term up
    in a vocabulary table.

  The triple view of a record is built once a phase and indexed by
  predicate.
- **Six vocabulary tables**, data under `resources/palomar/vocab/lido`: event
  types, actor roles, actor types, object types, subject types and subject
  schemes. Each names its source. The regexes and term lists that were in
  `lido.clj` moved there unchanged, except subject types and schemes, which
  are new.
- **The slice as rules** (`palomar.plugins.lido-rules`, 92 rules):
  - *who did what in an event*: carried out (`P14`), took part (`P11`), or
    gave up or received the title (`P23`, `P22`, proposed, with `P11`
    asserted);
  - *what a made object shows*: what it depicts (`P62`), or what the visual
    item it shows is about (`P65` → `E36` → `P129`).
- **Two things the importer now reads**: an actor's position in its event,
  and a subject's type (`lido:type`).

The rules run beside the code. They are not wired into the conversion: that
is the decision this study informs.

## Results

### 1. The rules do what the code did, and fix what it got wrong

On the five LIDO corpora (20 records), each actor of each event is said to
have done exactly what the code says: same relations, same statuses, same
number of agents (`lido-rules-test/the-rules-do-what-the-code-did`). The
cases the code got wrong are right in the rules:
- two actors of one name in one record are two agents, unless an authority
  names them;
- a commissioner takes part and does not carry out;
- a seller takes part, and is only proposed to have given up the title.

Subjects change on purpose. Of the 702 `P62_depicts` the code states on these
corpora:
- **68 stay depicted**: their concept is an Iconclass notation, a vocabulary
  of the content of images;
- **489 become what the work is about**: no record types its subjects, and a
  GND heading ("melancholy", but also "woman") says what a work is about,
  not that it is shown;
- **145 are subject persons and places**, outside the slice.

### 2. Rules cost about ten times the code

Warm, per record, on the 20 records: **14 ms for the slice of rules, against
22 ms for the import and the whole current projection**. The code's actors
and subjects are a small part of those 22 ms.

Clause order decides the rest. The first version tested "the object was
made" as a join, and ran 14 times slower. The engine has no planner: a rule's
author orders its clauses for speed. Caching vocabulary lookups gained 15 %.

### 3. What the language lacks

- **Disjunction.** "An activity by its type, or its term, or a technique it
  used, or what it precedes" is four rules, each excluding the ones before.
  With identity (authority or occurrence), kind of agent (person or group)
  and what they did, the actor slice is 84 rules, generated from tables.
- **A computed term.** The class of an event or the property of a doing
  cannot be looked up: each needs its own rule.
- **Derived facts within a phase.** "The object was made" is tested again in
  every subject rule; a phase cannot state it once for the others.
- **Joins by position.** Natives read by XPath position, such as an event's
  type terms in other languages, are invisible to rules. On these corpora no
  decision depended on them.
- **Interpretation in the importer.** Whether an actor is a creator is
  decided at import (`creator?`), outside any rule.

### 4. An independent mapping: the Yale Center for British Art

YCBA publishes LIDO (CC0) and its own Linked Art for the same objects. Its
Linked Art comes from its collection database through its own pipeline, not
from its LIDO. Over 40 sampled pairs (`dev/eval/ycba`):

| | YCBA | Palomar |
|---|---|---|
| object types (`classified_as`) | 96 | **0** |
| materials (`made_of`) | 79 | **0** |
| custodian | 40 | **0** |
| subjects of the visual item | 466 `represents`, 4 `about` | **0** |
| dimensions | 110 | 110 |
| creators | 36 | 46, 29 in common |

Two errors that no audit found:
- **Authority ids by source and code.** YCBA writes `lido:source="AAT"` and
  `300033618`, and ULAN `500303557`, not URIs. Palomar takes only URIs, so it
  drops every type, material, subject and custodian of these records.
- **Attribution qualifiers ignored.** YCBA leaves `roleActor` empty and puts
  the role in `attributionQualifierActor`: "after", "follower of",
  "Formerly", "Published by", "Text by". Palomar does not read that element,
  and an actor of no stated role is the maker (ADR 0054). So:
  - the painter a print is *after* is called its creator;
  - "follower of John Varley" becomes John Varley;
  - a *former* attribution is stated as the creator.

On subjects, YCBA types every subject set as a `description`, and its own
Linked Art says `represents`. The rule slice says depicted (377), where the
current Linked Art exporter would have said `about`.

YCBA's mapping is not the truth. It too writes a former attribution as a
part of the production carried out by that artist. It is an independent
reading of the same sources, and where the two differ, one of them is
wrong.

## Assessment

- **Rules deliver what was promised.** The interpretation can be read and
  changed as data, the vocabularies cite their sources, and the slice's
  errors are fixed. The registry of ADR 0055 could be derived from the rules
  instead of maintained beside them.
- **They cost too much as they are.** They are about ten times slower than
  code for the same work, and the rule count is combinatorial without
  disjunction. A full port would multiply a museum dump's conversion time.
- **The architecture was not the biggest problem.** The external comparison
  found the most serious errors in what Palomar *reads*: identifiers given
  by source and code, and attribution qualifiers. Those are wrong in code and
  in rules alike, and no internal test could see them: every corpus Palomar
  had used URIs and filled `roleActor`.

## Recommendation

1. **First, read what the source says.** Resolve authority ids given by
   source and code (AAT, ULAN, TGN, …) through a vocabulary table of
   sources. Read `attributionQualifierActor`, in a vocabulary:
   - influence ("after", "nach", "d'après") becomes `P15` / `influenced_by`,
     never a creator;
   - a related unknown hand ("follower of", "circle of", "workshop of") does
     not name that artist as maker;
   - a former attribution is never the creator;
   - an uncertain one ("attributed to") is a proposal.

   Take YCBA LIDO records (CC0) as fixtures.
2. **Make the YCBA comparison an evaluation** that is rerun and published,
   on 500 objects, as `docs/eval/` does for the bibliographic side. Done in
   `docs/eval/ycba.md` (K3): after K1 and K2, Palomar's makers that YCBA
   names in no part of the production fell from 37 to 8, all of them one
   person matched as two.
3. **Then the engine, before wiring the rules.**
   - Add a disjunction clause, a lookup term (concept → class or property),
     and facts derived within a phase.
   - Order existence tests and bound joins first.
   - Target: the slice in no more than 5 ms a record.
4. **Then wire the slice**, the CRM and Linked Art both serialising the
   rules' decisions, and port the rest of the projection one part at a time,
   each with its equivalence test.

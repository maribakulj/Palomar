# 0056 — Who did what in an event, and what a work shows, are rules

- Status: Accepted
- Date: 2026-11-04
- Builds on: [ADR 0035](./0035-construct-templates.md), [ADR 0052](./0052-museum-objects-from-lido.md), [ADR 0054](./0054-who-created-a-museum-object.md), [ADR 0055](./0055-a-projection-states-what-its-rules-declare.md)

## Context

The rules study (`docs/eval/rules-study.md`) wrote two parts of the museum
projection as rules and vocabularies, beside the code that computed them:
- who did what in an event;
- what a made object shows.

The rules gave the same doings as the code on every corpus. They fixed what
the code got wrong: two actors of one name in one record merged, a subject
of no type said to be depicted, a commissioner and a publisher carrying out
the production in Linked Art. After K4 they took about 4 ms a record. They
were not used: the conversion still ran the code, and Linked Art still
decided again, from the LIDO, what the CIDOC CRM view had decided.

## Decision

1. **The CIDOC CRM view takes these two parts from the rules**
   (`palomar.plugins.lido-rules`, run in `rule.lido/project`). The code
   keeps the events, their classes, times and places, and reports what the
   rules do not say: an unnamed hand that made a work.
2. **A rule cites what it read.** Each assertion a rule produces carries:
   - the evidence of the assertions its match read (their XPaths, ADR 0031);
   - the record it derives from;
   - its bindings, but not the structured values, which that evidence
     cites.

   The PROV view writes the bindings sorted by variable, so an EDN round
   trip writes the same graph. A node the rules mint from one IRI is that
   authority's node, with that IRI.
3. **An actor is its occurrence**: the record, the event and its position
   there, unless an authority URI names it. Two of one name are two until
   an authority or a curator says otherwise (the eighth audit, P1-A). An
   authority's IRI may be of any scheme (`urn:nbn:…`, K5 review).
4. **In an acquisition**, a seller, a buyer, a donor or an heir took part
   (`P11`, asserted). That they gave up or received the title (`P23`, `P22`)
   is proposed. A donor, whose role is also a passive one, gave up the title
   too: in an acquisition the title's roles outweigh being passive (K5
   review). The role's words do not establish who held the title, and
   both properties imply `P14` (the eighth audit, P1-C).
5. **A subject concept** is depicted (`P62`) when its subject is typed as a
   description or an identification, or when its concept is an Iconclass
   notation. Otherwise it is what the visual item the object shows is about
   (`P65` → `E36_Visual_Item` → `P129`) (the eighth audit, P1-B).
6. **Linked Art writes what the CIDOC CRM view states**, read from it, not
   decided again:
   - `carried_out_by` for who carried the production out (`P14`): its
     creators at the top, any other in a part in its role;
   - `influenced_by` for whom it was made after (`P15`);
   - `represents` for what the object depicts (`P62`), `about` for any other
     subject.

   Who only took part (a commissioner, a publisher, the author of a text),
   or is only proposed or superseded, is not written, and that is
   reported. An actor is the same node in both views (the eighth audit,
   P1-D).
7. **The vocabularies are the one source** of the words these decisions
   rest on: passive and title roles, activity and prior event types, actor
   types, artefact and natural object types, subject types and schemes,
   attribution qualifiers (`resources/palomar/vocab/lido`). The code reads
   them as the rules do. The importer still holds the words of its own
   decisions, which no rule makes yet: an event's kind (`event-kinds`,
   `kind-labels`) and whether a role is a creator's (`creator-heads`,
   `not-creator`, `creator-role-uris`).

   Read through the tables, a term is trimmed and split on `;`, and a type
   URI is matched whatever its case. This changes a few readings, toward
   the source:
   - an "Exhibition" type and a "use; loss" label are now activities;
   - "Auftraggeber;Maler" is no longer wholly passive.
8. **The inference registry is derived from the rules.** Each rule declares
   the relations it states, with its `:doc` as their reason
   (`lido-rules/declared`), so the registry cannot drift from them.

## Consequences

- **Identifiers change.** An actor named by no authority now has another
  IRI, its occurrence's. Data converted before must be re-converted, not
  merged.
- **Linked Art writes less.** A museum's commissioner or publisher is no
  longer a part of its Production; this is reported.
- **Subjects change.** Against the Yale Center for British Art's own Linked
  Art, which types every subject set as a description, Palomar now writes
  `represents` where YCBA does (`docs/eval/ycba.md`).
- **What is still code.** The event classes, the object's class ("made")
  and subject persons are still code.
  - An event's terms in every language are read by the rules as by the
    code (`:terms` on the event), so that both give an event the same class
    (K5 review: a term in another language once made the rules a second
    node).
  - Porting the rest waits on two things the rule language lacks:
    - a mint whose id and class differ (the object keeps one id, ADR 0052
      and H1, while its class is E22 or E19);
    - losses written as data (a rule makes no `dx/loss` today).
  - Until then, a generative test holds the code and the rules to one
    decision (K6, `lido-rules-property-test`). It runs 150 LIDO records of
    random event types, terms in two languages, techniques, roles and
    attribution qualifiers, and checks that every actor the rules place is
    on an event node the code declared, of one class. It finds the K5
    defect again when the fix is undone, shrunk to its smallest case.
- **Cost.** The rules, and the evidence they carry, cost a LIDO record
  about 3 ms more in the whole pipeline: about 19 ms against 22, measured
  warm. PROV grows by 2 to 7 % in triples, since a claim cites the event and
  the actor it was read from.

## Amendment (lot M7, 2026-11-09): what an event says of a person's life

Reconciliation now reads who did what in an event as evidence on identity
(ADR 0018 amendment M7, `palomar.agent-events`). It reads the decisions
above. It changes three of them, after a contradictory review:
- **A later making is not the actor's.** The attribution-qualifier
  vocabulary gains `:attribution/later-making`: a posthumous cast or
  impression, a restrike, a replica, a reproduction, a version, a forgery
  (`Nachguss`, `Fälschung`, `posthumer Guss`…). Like a former attribution,
  it makes the actor no creator of the production (`maker?`).
- **More roles are someone else's.** The other-role qualifiers gain "Hand
  colored by", "Cast by", "Impression by", "Reissued by" and their
  kin. The importer's `not-creator` words gain a caster, a founder
  (`Gießer`, `fondeur`), a colourist, an impression, a reissue and a
  restrike.
- So an actor in such a role or qualifier is no longer `carried_out_by` the
  production in Linked Art, nor its `P14` in CIDOC CRM. The rules read the
  same responsibility, so the two views still agree. No fixture's actor
  role or qualifier uses these words, and the LIDO conversion tests pass
  unchanged.

Two things are unchanged:
- The event's kind is still the importer's (`event-kinds`, code).
- An exhibition and a publication about the object are told apart by the
  event-types vocabulary, which gains `:event/exhibition` and
  `:event/publication`. No rule reads them.

Only a production or a design carried out as its maker can constrain a
life, and only when it ends more than 10 years before a candidate's
birth. A posthumous cast, impression, reissue or copy names its artist as
maker as often as not, so a creation after death is never evidence.

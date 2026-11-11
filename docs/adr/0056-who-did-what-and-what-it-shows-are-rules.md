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

## Amendment (lot N7, 2026-11-11): the persons and places a work represents

Decision 5 was for concepts. A person a work showed (`subjectActor`) was
still code: stated as depicted (`P62`) whatever its subject's type, and not
written in Linked Art. A place (`subjectPlace`) was not read at all, only
reported unread. They are now read and decided like concepts.

1. **Read.** Each `subjectActor` and `subjectPlace` of a subject is read with
   the type of its subject (`lido:type`), as its concepts are:
   - a person (`:lido/subject-person`): its name, its first id that is an IRI
     (an id by source and code resolved through the id-sources vocabulary,
     K1: ULAN `500012489` is `http://vocab.getty.edu/ulan/500012489`), its
     actor type, its life dates;
   - a place (`:lido/subject-place`): its name (else its displayed name), its
     first id that is an IRI (TGN, GND, Wikidata…), and its point as given
     (`gml:coordinates` or `gml:pos`).

   Every display, name in every language, id and coordinate is also kept as
   it was read, so no text under them is reported unread.
2. **The rules decide** (`lido-rules/subject-rules`, one table of kinds):
   typed as a description or an identification it is depicted (`P62`);
   typed otherwise, what the visual item is about (`P65` → `E36` → `P129`);
   of no type, see the review below. No code states `P62` any
   more; the projection's code declares no `P62` (`palomar.inferences`). An
   object not said to be made depicts nothing, and that is reported for
   persons and places as for concepts.
3. **Their nodes.** A person is an `E21_Person`, a group an `E74_Group` (by
   the actor-classes vocabulary, as an event's actor), a place an
   `E53_Place`. An authority's URI names the node. Without one, the node is
   the record's own, by its name, as a local concept is: two records' "John
   Smith" are two, never merged by name; one record's two of one name are
   one, as its two local concepts of one label are.
4. **Exports.**
   - Linked Art writes them in the `VisualItem`, read from the CIDOC CRM
     view: `represents` what it depicts, `about` the rest, each a reference
     (`Person`, `Group`, `Place`) by its authority's IRI or the CRM node's.
     A reference has no place for life dates, a point, an actor type or the
     subject's type: these are reported.
   - MARC21 (object profile): a person in `600` (with `$d` life dates), a
     group in `610`, a place in `651`, each with its `$0` and, for a
     vocabulary MARC lists (ULAN, TGN, GND, Wikidata, AAT…), `#7` and `$2`.
     A 6XX does not say whether the work depicts or means it: reported.
   - Dublin Core: the canonical floor has no subject (nor coverage), so
     neither concepts nor persons nor places are written. Each is reported.
   - The coordinates are not projected. They are read as given, but not the
     reference system and axis order a definition of the place would need
     (YCBA's `gml:coordinates` name none; museum-digital's `srsName` is not
     read), so no `P168_place_is_defined_by` is stated. A Linked Art
     reference has no place for them either. This is reported, value by
     value, in the CIDOC CRM view and in Linked Art.

### Consequences

*As N7 was first written; its review, below, changed the first two points
(a named person or place of no type is depicted; an id typed as aboutness
outweighs its set).*

- **What is said changed.** A person of a subject typed as an
  interpretation is no longer depicted. In N7 as first written, one of a
  subject of no type, named by no vocabulary of images, was `about`.
- **YCBA**, N7 as first written: persons 0 → 131 `represents` labels
  (YCBA: 125), places 0 → 738 (YCBA: 737). See the review for the numbers
  now.
- The identity of a local depicted person is unchanged (record and name);
  one named by an authority keeps its IRI.

### The review of N7 (2026-11-11)

A contradictory review of N7 changed what follows; the decisions are the
coordinator's, each with its test (`palomar.plugins.lido-subjects-test`).

1. **A named person or place of no type is depicted.** LIDO's subjectWrap
   defines Identification as "the specific subject, including named
   historical, mythological, or fictional subjects". A `subjectActor` or a
   `subjectPlace` names an individual, so, its set typed nothing, it is an
   identification: `P62_depicts`, Linked Art `represents`. Typed an
   interpretation (or any type that is no description or identification),
   it is still what the visual item is about. The rule for concepts is
   unchanged: a concept of no type is depicted only when Iconclass names it.
   So the Städel's Fanny Janauschek, Nick Bottom, Pyramus and Nemesis, and
   museum-digital's Olympia, are depicted, which N7 had made `about`.
2. **Every id is read.** A concept is Iconclass's when any of its ids is,
   not only its first. A person's or a place's IRI is chosen by an order of
   sources, stated in `id-sources.edn` (`:entity-preference`: ULAN, GND,
   TGN, Wikidata, LoC, VIAF, then the others in the record's order), never
   alphabetically. An Iconclass notation, a page of the BnF catalogue or a
   Wikipedia article is never a person's or a place's IRI
   (`:not-an-entity`): it is reported, and with no other the node is the
   record's.
3. **One IRI, one class.** A person or a place whose IRI is a subject
   concept of the same record is that concept: no node of its own, and
   that is reported (the Städel files GND subject headings, Berg, Gebäude,
   as places). On every LIDO fixture, no IRI of a record is typed two of
   `E55`, `E53`, `E21`, `E74`, and Linked Art lists an id once in
   `represents` and once in `about`: two labels of one IRI (YCBA's "shops"
   and "stores", "Wales" and "Cymru") are written under the first, the
   other reported.
4. **An id typed as aboutness outweighs its set.** YCBA types the id of a
   person a work is about `subjectActorAbout`, in a set typed a
   description. A vocabulary says so (`subject-id-types.edn`, its source
   YCBA): such a person is what the visual item is about.
5. **LIDO Terminology URIs** name a subject's type: `lido00525`
   (Description), `lido00136` (Identification) and `lido00745` (Iconclass
   notation, a notation of what an image shows) are ofness, `lido00524`
   (Interpretation) aboutness, as are the 1.0 `…/subject_type/<name>`
   URIs (`subject-types.edn`).
6. **A node both depicted and about** (two subject sets) is written in both
   `represents` and `about`, as the CIDOC CRM view states both.
7. **IRIs as the sources write them.** A Wikidata page or entity URL is the
   entity `http://www.wikidata.org/entity/Q…`; an Iconclass notation with a
   space is percent-encoded, so the Städel's
   `61BB2(JANAUSCHEK, Fanny)1` is an IRI, and a concept with it.
8. **MARC.** An LC name or subject IRI makes a `600`, `610` or `651` ind2 `0`
   (LCNAF, LCSH), with its `$0`. Iconclass is no subject heading source
   MARC lists: its concept's `650` is ind2 `4` with its `$0`, and its
   notation is a classification, `084` with `$2 iconclass`.
9. **A person named only as displayed** (`displayActor`) is a node of the
   record, as a place is.

On YCBA (`docs/eval/ycba.md`): persons 125 `represents` and 6 `about`, as
YCBA has them (the 6 Shakespeare records now `about`); places 726
`represents`, against 737, because one IRI is written once.

# 0054 — Who created a museum object is said by the role, not by the event

- Status: Accepted
- Date: 2026-10-28
- Amends: [ADR 0052](./0052-museum-objects-from-lido.md), [ADR 0053](./0053-marc21-for-museum-objects.md)

## Context

A LIDO production event names everyone who took part in making the object:
the sculptor, and also the founder who cast the bronze, the workshop, the
commissioner, the printer of a print. Palomar made each of them a creator,
in the canonical floor (`:canon/agent`, role creator, so `dc:creator`), in
MARC21 (`100`/`700`) and in Linked Art (`carried_out_by` of the Production).
That is an attribution the source does not make.

## Decision

1. **An actor of a production is a creator when its role is a creator's.**
   Each of its roles counts, not only the first. A role term qualifies when its
   last word is a creator's (painter, draughtsman, sculptor, artist, author,
   engraver, photographer, architect, maker, illustrator…, in English, German
   and French). German compounds such as Kupferstecher, Hofmaler and
   Holzschneider end with one too. The term must also say nothing that makes
   it another role ("lithographic printer", "Etching publisher", "photographic
   studio", "after a painting by", "Artist's estate"). A role URI qualifies
   when it is a creator's: AAT artists, painters, sculptors, photographers,
   and the GND Maler.
2. **An actor with no role stated, neither term nor URI, is a creator.** An
   actor named alone in a production, as most records give the artist, is its
   maker. That is what the source means (this choice was put to the project
   owner and accepted). A role given only by a URI is stated: an actor whose
   URI names a printer is not a creator.
3. **Any other actor takes part.** It is never a creator.
   - **CIDOC CRM:** `P14` as before. Being carried out by is participation,
     whatever the role.
   - **MARC21:** a `700`/`710` with each of its roles in an `$e`, never the `100`/`110`
     main entry. A production with no creator has no main entry, and `245`
     takes indicator 0.
   - **Linked Art:** a `part` of the Production that it carries out, labelled
     and, when the role has a URI, classified by it. Who only took part (a
     commissioner, a publisher, `P11` in the CIDOC CRM view) is reported, not
     written (amended, ADR 0056).
   - **Dublin Core:** not a `dc:creator`.
4. **Each actor's responsibility is kept** on its native (`creator` or
   `participant`), with its role and role URI, for any later view.
5. **An attribution qualifier outweighs the roles** (`attributionQualifierActor`,
   read in `resources/palomar/vocab/lido/attribution-qualifiers.edn`;
   amended, post-audit 8, K2). Records such as the Yale Center for British
   Art's put the actor's role there and leave `roleActor` empty, so before
   this every such actor was the maker by rule 2.
   - *After* ("after", "nach", "d'après"): the work was made after the
     actor's, which influenced it. CIDOC CRM: `P15_was_influenced_by`;
     Linked Art: `influenced_by`; never a creator.
   - *A related hand* ("follower of", "circle of", "workshop of", "Umkreis",
     "suiveur de"): another, unnamed hand made it. It was influenced by the
     actor, and who made it is reported as not stated.
   - *Formerly*: the attribution is superseded. CIDOC CRM keeps the `P14` with
     status `:superseded`, which only the history (PROV) writes. It is no
     creator anywhere else, and Linked Art reports it.
   - *Uncertain* ("attributed to", "zugeschrieben", "fraglich", "?"): the
     `P14` is proposed (rule `:rule.lido/attributed-maker`), not stated, and
     the actor is no creator. A Städel drawing "fraglich" by Rodin has no
     stated maker now.
   - *A role* in the qualifier's place: "Print made by" makes the actor the
     maker; "Text by" or "Published by" means the actor took part (`P11`).
     The qualifier stands for the role when the record states none.

   MARC21 writes the qualifier first in the `$e` of a `700` ("after"), and
   only a maker is a `100`. A German "nach" names an influence, but not in
   "nach schriftlicher Mitteilung", which says where the attribution comes
   from.

   The words are those of the Getty's CDWA attribution qualifiers and their
   German and French counterparts ("copy after", "Kopie nach", "studio of",
   "Umfeld", "Kreis um", "assistant to"…). When a qualifier names several,
   the first wins: former, related hand, influence, uncertain, a role.
   "Attributed to workshop of" is a related hand. "Possibly after" is an
   influence, stated, not proposed: the work was not made by that actor
   either way. A qualifier the table does not name leaves the roles to
   decide (K2 review).

## Consequences

- A cast bronze is by its sculptor, not by its foundry.
- A record that names a printmaker without a role still credits them.
- The role list is a heuristic on terms. A role it does not know makes a
  participant, never a creator. The error it can make is to omit an
  attribution, not to invent one.

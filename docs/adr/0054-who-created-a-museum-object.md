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
   The role terms of `roleActor`, in any language, are matched against a
   creator's roles: painter, draughtsman, sculptor, artist, author, engraver,
   photographer, architect, maker and the like, in English, German and French.
2. **An actor whose role the record does not state is a creator.** An actor
   named alone in a production, as most records give the artist, is its
   maker. That is what the source means (this choice was put to the project
   owner and accepted).
3. **Any other actor takes part.** It is never a creator.
   - **CIDOC CRM:** `P14` as before. Being carried out by is participation,
     whatever the role.
   - **MARC21:** a `700`/`710` with its role in `$e`, never the `100`/`110`
     main entry. A production with no creator has no main entry, and `245`
     takes indicator 0.
   - **Linked Art:** a `part` of the Production that it carries out, labelled
     and, when the role has a URI, classified by it.
   - **Dublin Core:** not a `dc:creator`.
4. **Each actor's responsibility is kept** on its native (`creator` or
   `participant`), with its role and role URI, for any later view.

## Consequences

- A cast bronze is by its sculptor, not by its foundry.
- A record that names a printmaker without a role still credits them.
- The role list is a heuristic on terms. A role it does not know makes a
  participant, never a creator. The error it can make is to omit an
  attribution, not to invent one.

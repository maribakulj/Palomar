# 0050 — MARC21 for archives is a profile of its own

- Status: Accepted
- Date: 2026-10-28
- Builds on: [ADR 0049](./0049-a-target-says-what-it-can-describe.md), [ADR 0045](./0045-ric-o-projection.md)

## Context

ADR 0049 refuses an EAD unit in the bibliographic MARC21 target: through the
canonical floor its *cote* became an LCCN (`010`) and the dates of its
documents a publication date (`260 $c`). Archives still need MARC21, for
example to be found in a library catalogue. MARC21 provides for them: a
record under archival control (Leader/08 `a`), inclusive dates (`245 $f`),
the organization and arrangement of materials (`351`), and the host item of
a component (`773`).

## Decision

1. **Two targets read the EAD values themselves** (`:ead/*`, the natives the
   importer keeps), not the generic floor:
   - `:marc21-archival` writes every unit. The hierarchy rides on `773 0#
     $w` (the parent's control number), and Leader/07 says collection (`c`,
     the fonds) or subunit (`d`).
   - `:marc21-fonds` writes the fonds alone, as a discovery record. Each
     component is reported as summarized by it.
2. **Each value goes to the field that means it:**
   - the *cote* to `099 $a`, a local call number;
   - the dates to `245 $f`, inclusive dates;
   - the level to `351 $c`;
   - the abstract to `520 3#` and the scope and content to `520 2#`;
   - digital objects to `856 4# $u`.
   - Creators go to `100`/`110` by the EAD element that names them: personal
     (`1#`), family (`3#`) or corporate (`2#`), with `$e creator`. Later
     creators go to `700`/`710`.
3. **Both targets accept archival units only** (ADR 0049).
4. **The importer now reads the unit's notes** that have a MARC21 field and a
   RiC-O property:
   - `bioghist` goes to `545 $a` and `rico:history`;
   - `accessrestrict` goes to `506 $a` and `rico:conditionsOfAccess`;
   - `physdesc` and EAD3 `physdescstructured` go to `300 $a` and
     `rico:recordResourceExtent`.

   Any EAD value this profile does not write is reported at export.

Rejected: a `:profile` option on the `:marc21` target. A target is the unit
that declares what it accepts and what it loses, so two meanings of MARC21
would hide behind one name.

## Consequences

- An archive reaches a library catalogue without a field saying what the
  source does not.
- The finding aid itself (`555`) is not written: the EAD document carries no
  URL of its own to point to.

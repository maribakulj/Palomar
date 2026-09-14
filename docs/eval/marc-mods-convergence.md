# MARC21 → MODS → canonical: convergence through an external standard

**DoD #1 / the hub-and-spoke thesis.** Where the DC eval measures *element coverage*
against a LoC crosswalk, this one tests **convergence**: the same MARC record routed
two independent ways into the canonical hub should land in the same place on the
documentary spine. The intermediary on the second route is a *third party's* standard
— the LoC's own MARC→MODS — so the MODS importer is exercised against authoritative
MODS, not our hand-written fixtures.

- **Path A** — MARC21 → Palomar's MARC21 importer → canonical floor.
- **Path B** — MARC21 → LoC `MARC21slim2MODS3-1.xsl` (oracle, JDK XSLT 1.0) → real
  MODS → Palomar's MODS importer → canonical floor.
- Input: the LoC's `loc_collection.xml` (2 records). Test:
  `palomar.eval.marc-mods-oracle-test`; runner: `palomar.eval.loc-xslt`.

## What converges (the spine survives the detour)

| field | record 0 | record 1 | note |
|-------|----------|----------|------|
| `title` | ✅ exact | ✅ exact | **non-trivial**: MODS splits `<nonSort>The </nonSort><title>Great Ray Charles</title>`; the importer's nonSort restoration reconstructs the inline MARC 245 exactly. Without it the routes would *not* converge. |
| `identifier` (LCCN) | ✅ `91758335` in both | ✅ `00530046` in both | the bibliographic number survives both routes |
| `date` (transcribed) | ✅ `[1957?]` in both | ✅ `1994-` in both | the 260 $c form survives both routes |
| `digital-object` | (none) | ✅ exact (both 856 URLs) | the access URLs converge for the web record |

## What diverges (MODS carries more, and flattens differently)

Asserted in the eval, not hidden — convergence is on the spine *through a standard*,
**not** round-trip equality. The two importers have different scopes and MODS is the
richer interchange format:

- **agent.** Path B folds the date `namePart` into the name (`Charles, Ray 1930-`)
  *and* promotes `originInfo/publisher` to a second agent (`Atlantic`); Path A keeps
  only the 700 $a name (`Charles, Ray,`). Two defensible flattenings of one record.
- **date.** Path B also carries the MARC-*coded* normalised form (`1957`;
  `1994`/`9999`) the LoC stylesheet emits as a second `dateIssued`; Path A keeps only
  the transcribed 260 $c.
- **identifier.** Path B adds the 028 issue number (`1259 Atlantic`, MODS
  `type="issue number"`) and the 856 URLs (MODS `type="uri"`); Path A keys on the
  control numbers (035) and routes 856 $u to a *digital object*, not an identifier.
  (So in Path B the access URLs appear as *both* identifier and digital object — the
  LoC MODS duplicates 856 $u into `<identifier type="uri">` and `<location><url>`.)
- **note.** Path A keeps the 505 track listing; the LoC stylesheet routes it to
  `<tableOfContents>`, which the MODS floor does not map (ADR 0003) → dropped in B.

## Reading it honestly

The headline is **the documentary spine converges through an external standard** —
strongest on `title` (exact, and dependent on a real piece of importer logic), and
holding on the LCCN, the transcribed date, and the digital objects. The divergences
are not failures; they are the two importers' different scopes made visible, and a
demonstration that the MODS importer handles genuine LoC output (modsCollection,
nonSort, typed nameParts, dual dateIssued) — shapes the hand-written fixtures only
approximated.

## Offline caveat

Same as the DC oracle: the LoC stylesheet's one absolute `MARC21slimUtils.xsl`
reference (here an `xsl:include`) is redirected to the vendored copy by a
`URIResolver`; two JDK XML limits are pinned (`accessExternalStylesheet=all`,
`jdk.xml.xpathExprOpLimit=0`) so the 99 KB stylesheet runs identically across the CI
JDK matrix. The transform is the LoC's, unmodified.

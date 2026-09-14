# MARC21 → Dublin Core: a differential against the LoC oracle

**DoD #1 (faithful conversion), partial.** This eval does not unit-test Palomar's
own crosswalk against itself — it measures `convert :marc21 → :dc` against the
**Library of Congress' own** reference stylesheet, `MARC21slim2OAIDC.xsl`, run as an
independent oracle over the same input. The coverage number below is therefore
*external*: a third party's published mapping, not our own expectation.

- Oracle: the unmodified LoC `MARC21slim2OAIDC.xsl` + `MARC21slimUtils.xsl`
  (`test/fixtures/conformance/crosswalks/`, public domain), run through the JDK's
  XSLT 1.0 engine. Its one absolute `xsl:import` (loc.gov, 403 offline) is redirected
  to the vendored utility stylesheet by a `URIResolver`; nothing else is changed.
- Input: the LoC's own `loc_collection.xml` (2 records: a sound recording, a website).
- Test: `palomar.eval.marc-dc-oracle-test`.

## Result

Element types emitted over the 2-record sample (DCMES local names, with counts):

| DC element | LoC oracle | Palomar | verdict |
|------------|-----------:|--------:|---------|
| `title`        | 2 | 2 | shared — same count, different subfield discipline (see below) |
| `creator`      | 2 | 2 | shared — same count, different subfield discipline |
| `date`         | 2 | 2 | **shared — values agree exactly** (`[1957?]`, `1994-`) |
| `description`  | 7 | 5 | shared — oracle sweeps a wider 5xx block (and double-counts 520) |
| `identifier`   | 2 | 3 | shared *type*, **disjoint values** (provenance diverges) |
| `language`     | 2 | 0 | **gap** |
| `publisher`    | 2 | 0 | **gap** |
| `subject`      | 6 | 0 | **gap** |
| `type`         | 2 | 0 | **gap** |

**Element-type coverage: 5 / 9.** Shared spine = `title creator date description
identifier`. Palomar emits **no** DC element the oracle does not (it is a projection
of the same documentary core, not an embellishment).

## Reading the result honestly

The number is not "Palomar is 56 % of the LoC crosswalk." Three things are true at
once, and the eval asserts each:

1. **The gap is the coded / controlled axes, by design.** `language` (008/35-37) and
   `type` (leader 06/07) are *coded* fixed fields; `subject` (6xx) is a *controlled
   vocabulary*; `publisher` (260 $a$b) is an agent *role* the single `:canon/agent`
   floor cannot carry distinctly. ADR 0003 scopes the canonical floor to
   **transcribed documentary statements** — title, creator name, date, note,
   identifier — and deliberately omits coded/controlled fields. The oracle *decodes*
   them; Palomar drops them at import. Closing this gap is a future controlled-vocab /
   fixed-field layer, not a silent omission. It is the headline cost.

2. **Identifier provenance diverges — disjointly.** Palomar keys `dc:identifier` on
   the bibliographic numbers (010 LCCN, 035 system control number); the LoC oracle
   keys it on the 856 access URL and 020 ISBN. Palomar routes 856 $u to
   `:canon/digital-object` (an access *surrogate*, not an identifier of the work), so
   the two id-sets share *nothing*. MARC has no single "the identifier"; both choices
   are defensible, and the eval records the divergence rather than picking a winner.

3. **Subfield discipline diverges — and not always in the oracle's favour.** The
   oracle's `title` carries the GMD `[sound recording]` ($h); its `creator` carries
   the `prf` relator code and the `1930-` date ($4, $d) concatenated into the name
   string. Palomar strips to the proper title and the controlled name. So Palomar is
   **not a strict subset** of the oracle — on these fields it is cleaner; on the four
   axes above it is thinner.

## Offline caveat

The transform runs fully offline via the JDK engine + the import-redirecting
`URIResolver`; no network, no added dependency. Where loc.gov resolves, the identical
eval runs against the live import. The fixtures are exactly what such a run consumes.

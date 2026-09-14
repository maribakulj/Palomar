# Conformance fixtures — the LoC MARC21 crosswalks (oracles)

The **official** Library of Congress MARCXML crosswalk stylesheets, plus the utility
stylesheet they share, used as *independent oracles* for Palomar's MARC conversions:
`MARC21slim2OAIDC` for `convert :marc21 → :dc` (`palomar.eval.marc-dc-oracle-test`)
and `MARC21slim2MODS3-1` for the MARC→MODS→canonical convergence study
(`palomar.eval.marc-mods-oracle-test`). The shared offline runner is
`palomar.eval.loc-xslt`.

| File | Source | Licence |
|------|--------|---------|
| `loc-MARC21slim2OAIDC.xsl`    | `https://www.loc.gov/standards/marcxml/xslt/MARC21slim2OAIDC.xsl`    | public domain (US Gov work) |
| `loc-MARC21slim2MODS3-1.xsl`  | `https://www.loc.gov/standards/marcxml/xslt/MARC21slim2MODS3-1.xsl`  | public domain (US Gov work) |
| `MARC21slimUtils.xsl`         | `https://www.loc.gov/standards/marcxml/xslt/MARC21slimUtils.xsl`     | public domain (US Gov work) |

All three are **unmodified** LoC stylesheets (both crosswalks reference the *same*
`MARC21slimUtils.xsl`, vendored once). The input is the LoC's own sample
`test/fixtures/documentary/marc21/marcxml/loc_collection.xml` (2 records).

## The offline concessions

Both crosswalks reference the utility stylesheet by an **absolute** loc.gov URL that
returns 403 in the sandbox — `MARC21slim2OAIDC.xsl` via `xsl:import`,
`MARC21slim2MODS3-1.xsl` via `xsl:include`. Neither stylesheet is edited; the runner
(`palomar.eval.loc-xslt`) supplies a `javax.xml.transform.URIResolver` that redirects
the `slimUtils` reference to the vendored copy, and pins two JDK XML limits so the
transforms behave identically across the CI JDK matrix (`accessExternalStylesheet=all`;
`jdk.xml.xpathExprOpLimit=0` — the 99 KB MODS stylesheet has a 101-operator XPath
union that trips the default cap of 100). The transforms themselves are the LoC's, run
by the JDK's built-in XSLT 1.0 engine (no added dependency).

## What the oracles show

- **MARC→DC** (`docs/eval/marc-dc-crosswalk.md`). **5 of the oracle's 9 DC element
  types** covered (`title creator date description identifier`); the gap is the four
  coded/controlled axes (`language publisher subject type`) the canonical floor does
  not model (ADR 0003); `date` agrees exactly; `identifier` provenance diverges
  disjointly (010/035 control numbers vs 856 access URLs).
- **MARC→MODS→canonical** (`docs/eval/marc-mods-convergence.md`). The documentary
  spine **converges** when the same MARC is routed through the LoC's MODS and back
  into Palomar's MODS importer: `title` exactly (both records), plus the LCCN, the
  transcribed date, and the digital objects. It **diverges** where MODS carries more
  (publisher-as-agent, MARC-coded dates, the 028 issue number) — asserted, not hidden.

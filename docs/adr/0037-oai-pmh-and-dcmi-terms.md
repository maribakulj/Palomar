# 0037 — OAI-PMH responses and DCMI Terms

- Status: Accepted
- Date: 2026-10-25

## Context

Most institutional metadata does not arrive as files. It is harvested over
OAI-PMH, wrapped in an envelope that names each record, says when it changed,
which sets it belongs to and whether it was deleted. A large part of it is
qualified Dublin Core (`dcterms:`), which the Dublin Core spoke ignored, since
it read the fifteen DCMES elements only.

## Decision

1. **`palomar.plugins.oai`** reads an OAI-PMH 2.0 response (`GetRecord`,
   `ListRecords`). It is not a spoke. It unwraps each record and hands its
   `<metadata>` to the spoke of the response's metadata format (`oai/formats`:
   `oai_dc` → Dublin Core, `marcxml` → MARC21, `mods` → MODS).
   - The OAI header (identifier, datestamp, sets, deletion) is kept at
     `[:provenance :source :oai]`, beside the response's SHA-256.
   - A deleted record is listed, not imported.
   - A Dublin Core record, which has no id of its own, takes `:oai/<identifier>`.
   - A repository error throws, except `noRecordsMatch`, an empty harvest.
   - `--from oai` (`:oai`, with `:metadata-prefix`) converts a response like
     any other source.
2. **Harvesting takes the fetch as an argument.** `oai/harvest` takes
   `(fn [url] -> xml)` and returns the pages lazily, following resumption
   tokens. Palomar opens no connection of its own, imposes no HTTP client, and
   a cache, a rate limiter or a test stands in for the network.
3. **DCMI Terms.** The Dublin Core spoke reads `dcterms:` too:
   - the properties that restate a DCMES element map as that element does;
   - `created` and `issued` become dates;
   - `isPartOf`, `hasPart`, `isVersionOf` and `hasVersion` become relations;
   - `abstract` and `tableOfContents` become notes of that kind;
   - any other `dcterms:` property present is reported as import loss, as an
     unmodelled DCMES element is.

## Consequences

- A harvest is converted, curated and exported like a file, and each output
  record still names the repository item it came from.
- The evidence of a harvested value (ADR 0031) is an XPath into the record's
  `<metadata>` element, not into the response. The OAI identifier says which
  record that is.
- Encoding schemes (`xsi:type="dcterms:W3CDTF"`) are not read. The
  refinements without a floor home (`spatial`, `temporal`, `audience`, …) stay
  losses until the floor grows by its discipline (ADR 0003).

## Amendment (post-audit 8, K6): a record harvested twice

Two records of one id would merge into one.
- `import-response` reports each record whose id an earlier record of the
  same response has (`:oai/duplicate-id`, a warning).
- `import-harvest` imports a harvest's pages lazily, sharing the ids seen
  across them, so a record two pages carry is reported in the second.
- The ids are kept as 64-bit digests (`palomar.digest-set`), about 16 bytes
  each.
- The warning is a diagnostic of the record: it is in the EDN pivot
  (`--to edn`) and in the API's result; `validate` reports errors only. `import-harvest` is a function
  of the API; `convert --from oai` reads one response, so it reports a
  duplicate within that response only.


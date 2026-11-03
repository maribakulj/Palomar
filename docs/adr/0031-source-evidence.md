# 0031 — Every value keeps where it was read in its source

- Status: Accepted
- Date: 2026-10-25

## Context

Provenance said which rule and phase produced an assertion, and that a WEMI
claim was derived from a record. It did not say *where in the source* a value
was read: the MARC field fragment existed but no assertion pointed to it,
normalisation minted fragments with no link to the ingest one, the projection
kept only the record id, and no export carried a location. A research
application could not quote or check the passage a claim rests on, and two
attestations of one fact could not be told from one.

## Decision

1. `Provenance :evidence` is a vector of attestations (`model/Evidence`):
   `:source` (the document: `:uri`, `:version`, `:sha256`, `:retrieved`, as
   known), `:fragment` (the record fragment holding the value) and `:selector`
   (the place inside, a W3C Web Annotation selector in data: `{:type :xpath
   :value …}`, `{:type :fragment :value "xywh=…" :conforms-to …}`, …). The core
   checks only the shape; the vocabulary of sources and selectors is the
   producer's. `model/evidence-of` reads an assertion's evidence, its
   alternative justifications' included.
2. Importers record it: MARC (both families) an XPath to each subfield,
   relative to the record element and by local name, with the field fragment;
   Dublin Core and MODS an XPath to each element; IIIF the canvas an image is
   painted on and the `xywh` region when the annotation targets part of it.
   `convert` stamps the SHA-256 of the source document on each record.
3. It is carried: a mapping rule copies the evidence of the native value it
   maps (all parts of a combined value); a WEMI string takes the evidence of the
   title it copies; merging keeps every justification's evidence (`:also`).
4. The PROV-O target writes each attestation as a W3C Web Annotation
   `oa:SpecificResource` the statement `prov:wasDerivedFrom`: `oa:hasSource`
   the document, `oa:hasSelector` the selector, `palomar:fragment` the fragment;
   the record carries `palomar:sha256`.
5. A test evaluates every importer's XPath against the real fixture with the
   JDK's XPath engine and requires it to find the value it attests.

## Alternatives considered

- *Store evidence on fragments only*: a fragment is a field, not a value, and
  assertions derived later would still have to point at it.
- *Character offsets into the raw bytes*: fragile under re-serialisation and
  meaningless for JSON; selectors over the document structure survive both.

## Consequences

- The other targets (format exports, plain RDF) do not carry evidence; that
  is reported once the loss taxonomy can name a provenance loss (plan step 9).
- The streaming path never holds the whole document: it is given its
  digest (`:source-sha256`, from `source-digest`, one more read of the file
  in constant memory, the digest of its text's UTF-8 bytes as batch takes
  it), and stamps it on each record, so that its provenance names the same
  document as batch's; `convert --stream` passes it. A stream given none (an
  API caller's reader) names no document, nor does a stream read from a pipe
  (stdin, a FIFO), which can be read but once: the CLI says so (amended,
  post-audit 7, I2 and its review). Its selectors are recorded as in batch.
- This digest is that of the text as read, re-encoded in UTF-8: for a UTF-8
  file it is the file's own, the run manifest's; for a file in another
  encoding the two differ (the manifest hashes the bytes).

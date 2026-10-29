# LIDO fixtures

Real museum object records from the Städel Museum, Frankfurt am Main,
harvested from its public OAI-PMH endpoint
(`https://sammlung.staedelmuseum.de/api/oai`, `metadataPrefix=lido`,
set `scope:major`) on 2026-10-28.

The records' metadata is published under **CC0 1.0**
(`recordRights` of every record: <https://creativecommons.org/publicdomain/zero/1.0/>);
the works are in the public domain (Public Domain Mark 1.0).

- `staedel-oai-lido-sample.xml`: an OAI-PMH `ListRecords` response kept as
  served, cut to five live records (four paintings and a drawing) and one deleted
  record.
- `staedel-oai-lido-graphics.xml`: an OAI-PMH response of the set
  `scope:graphics` (harvested record by record with `GetRecord`, same date
  and licence), cut to six live records: a drawing with no actor, a
  photograph, an anonymous draughtsman's study, a print by its artist, a
  print with an inventor and an engraver, and a drawing by two draughtsmen.
- `museum-digital-hessen-lido.xml`, `museum-digital-westfalen-lido.xml`: two
  other institutions' records, as the museum-digital aggregator serves them
  over OAI-PMH (`https://hessen.museum-digital.de/oai`,
  `https://westfalen.museum-digital.de/oai`, `metadataPrefix=lido`, first
  `ListRecords` page) on 2026-10-29: two prints of one portfolio, the
  portfolio itself (five productions), plaster casts, a Greek vase, a
  model. Every record's `recordRights` is CC0 1.0. Their practice differs
  from the Städel's: German past participles as event types ("Hergestellt",
  "Gefunden"), the object's materials in an "Event (non-specified)".
- `staedel-lido-wrap.xml`: two of those records as a bare LIDO `lidoWrap`
  document, for the spoke read directly.

The records are LIDO 1.0 (the namespace is LIDO 1.1's: `http://www.lido-schema.org`).

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
- `staedel-lido-wrap.xml`: two of those records as a bare LIDO `lidoWrap`
  document, for the spoke read directly.

The records are LIDO 1.0 (the namespace is LIDO 1.1's: `http://www.lido-schema.org`).

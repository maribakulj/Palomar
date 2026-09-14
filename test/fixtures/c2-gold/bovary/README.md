# C2 gold — Madame Bovary work cluster (data.bnf.fr ground truth)

This is a **gold standard** for evaluating Palomar's WEMI derivation (WP-3,
ADR 0016): which of our INTERMARC manifestation records belong to the same *Work*,
according to the BnF's own published LRM data — not a hand-label.

## What it is

`workmanifested.csv` — one row per manifestation that data.bnf.fr links to a Work
via `rdarel:workManifested` (`<http://rdvocab.info/RDARelationshipsWEMI/>`),
restricted to the ARKs that appear in our committed SRU fixtures:

| column             | meaning                                             |
|--------------------|-----------------------------------------------------|
| `sourceArk`        | the manifestation ARK, as it appears in our records |
| `manifestation`    | its data.bnf.fr `#about` URI                        |
| `manifestationTitle` | dcterms:title of the manifestation                |
| `work`             | the **Work** URI it is a manifestation *of*         |
| `workTitle`        | dcterms:title of that Work                          |

`workmanifested.rq` — the exact SPARQL `SELECT` (scoped by `VALUES`) used to
produce the CSV, kept for provenance and reproducibility.

## The result it encodes

All **28** linked manifestations point to a single Work:

    http://data.bnf.fr/ark:/12148/cb11938746n#about  —  "Madame Bovary"

The Work's ARK stem is **`11938746`** — which is **exactly** the value our records
carry in INTERMARC `f145 $3` (`:intermarc/f145_3 = "11938746"`). data.bnf's
`workManifested` and our `f145_3` are two serialisations of the *same* BnF
work-authority link. (See the caveat below — this is why the eval scores high.)

Of the 30 records in `bib-flaubert-madame-bovary-start1-max30.xml`, **28** are in
this gold cluster. The two that are **not**:

- `cb32056819r` — *"Le Réalisme. Madame Bovary…"*, a study guide / critical
  companion: a **different Work**. data.bnf correctly does not link it, and neither
  do we (no `f145_3`). True negative.
- `cb48756313f` — *"Madame Bovary"* (Flaubert), plausibly the novel, but it carries
  **no** `f145_3` *and* **no** `f100_3`, and data.bnf publishes **no**
  `workManifested` for it either. Neither system links it. A shared blind spot of
  source and ground truth, not a clustering error on our side.

## Provenance and the GET → POST correction

An earlier scoped run (`SCOPED_C2_REPORT.md`, archived in the upload) reported
**0** matches and concluded data.bnf published no Work links for our ARKs. **That
was a false negative.** The `VALUES` block is several KB; sent as a GET query string
it was truncated/rejected by the endpoint, yielding an empty result. Re-issued as a
SPARQL **POST** (`SCOPED_C2_REPORT_POST.md`) the same query returns the 28 rows here.
The lesson is methodological: a long `VALUES` join must go over POST.

## Honest caveat on what this gold can measure

Because `workManifested` (gold) and `f145_3` (our input) descend from the *same*
cataloguing act, agreement between our clustering and this gold measures
**faithful transcription** — that we read the authority link, mint stable
content-derived ids, and neither split one Work across editions nor merge distinct
Works — **not** independent inference. The eval's value is ruling out
parsing/identity/clustering regressions on a real 28-edition cluster. It says
nothing about records that *lack* the link; their recall is the subject of the
showcase-boundary eval, and it is ~0.

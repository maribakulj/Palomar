# ER gold — BnF persons (person-level reconciliation)

Real BnF data for measuring **person reconciliation**: does Palomar link a
person's name in a bibliographic record to the right person authority, and does
it keep two people with one name apart? Used by
`palomar.eval.person-reconciliation-test`; the write-up is
[`docs/eval/person-reconciliation.md`](../../../../docs/eval/person-reconciliation.md).

## What it is

- **150 bibliographic records**, INTERMARC Xchange, 50 per query, from the BnF
  SRU API (`https://catalogue.bnf.fr/api/SRU`, `recordSchema=intermarcXchange`):
  - `../../documentary/intermarc/sru/intermarcXchange/bnf-sru-victor-hugo-50.xml`
    (`bib.author all "victor hugo"`) and `bnf-sru-jules-verne-50.xml`
    (`bib.author all "jules verne"`), already used by
    [`../bnf-agents/`](../bnf-agents/README.md);
  - `../../documentary/intermarc/sru/intermarcXchange/bnf-sru-alexandre-dumas-50.xml`
    (`(bib.author all "alexandre dumas") and (bib.recordtype any "mon")`, fetched
    2026-11-09), added for this eval and kept next to the other two SRU pages.
- **`authorities.xml`** — the 33 BnF person authority records (INTERMARC Xchange)
  that the records' `100`/`700 $3` cite and the SRU returns, fetched 2026-11-09,
  one query per record (`aut.recordid any "<id>"`). Each `<srw:record>` is copied
  verbatim from its response; they are concatenated, sorted by id, under one
  `searchRetrieveResponse` wrapper written for this fixture, so Palomar's own
  INTERMARC importer reads them in one call. One file rather than 33: the reader
  reads one document, and a reviewer sees the whole pool in one place.

Four cited authorities return no record from the SRU (`numberOfRecords` 0):
**10018888** (Rafina, Gesner), **12612031** (Harispe, Patrice), **14380255**
(Paltsits, Victor Hugo), **16814325** (Hayat, Eric). The records exist: each ARK
(`https://catalogue.bnf.fr/ark:/12148/cb16814325q`, `cb10018888r`, `cb12612031f`,
`cb14380255q`) opens a BnF "Notice de personne"; the SRU just does not serve them
for `aut.recordid`. They are absent from `authorities.xml` and their mentions count
as "authority not in the pool".

## Mentions

A person mention is one `100` (main entry) or `700` (other person) field: 142
mentions (90 `100`, 52 `700`). The other 7xx (`710` collectivities, `750`… for
languages, series and the like) are not persons and are left out. The 28 person
subject headings (`600`, each with a `$3`) are not mentions either: they say
whom a work is about, not who made it. Each field
carries `$a` (surname), `$m` (forename), often `$d` (dates), `$1` (ISNI) and `$3`
(the BnF authority record number). 141 mentions have a `$3`, citing 37 distinct
authorities. The families: Dumas (51 mentions), Verne (29), Hugo (8), Hetzel (2),
others (52), by the mention's surname.

Hard negatives in the corpus: Alexandre Dumas 1802-1870 (11901063) and his son
1824-1895 (11901062), both catalogued "Dumas, Alexandre"; Adolphe Dumas;
Robert-Dumas Alexandre / Charles; Dumas de Rauly; Victor / François-Victor /
Georges-Victor Hugo and ten authors whose forenames are "Víctor Hugo …"; Pierre-Jules /
Louis-Jules Hetzel, and P.-J. Stahl, Pierre-Jules Hetzel's pseudonym, which the
BnF holds as an authority of its own (16492882); Jules Verne / Jean Jules Verne.

## What the gold is, and is not

- **The truth is the BnF cataloguers' link `$3`.** It is a human decision made
  in the BnF catalogue, not an independent annotation: where a cataloguer
  linked the wrong authority, the gold is wrong, and this eval cannot tell.
- A mention **without `$3`** (one: a `100` "Dumas, Alexandre" with no link) is
  **undetermined**: it is scored for nothing.
- A `$3` whose authority the SRU does not return is **not in the pool**: in
  this closed pool the right answer for a matcher is to propose nothing, and any
  proposal is wrong. Against a complete pool the right answer would be the
  person's own authority.
- It is not a complete inventory of persons: the SRU serves a separate
  "Hayat, Éric" authority (13168570), cited by no mention here and not in the
  pool, which may be a second authority for the person of 16814325.
- The authority pool is closed: it holds exactly the authorities the mentions
  cite, so it has no distractor from outside. A real pool is larger.

## Licence

BnF data, **Licence Ouverte / Etalab 2.0** — free reuse, including commercial,
with attribution: *Source : Bibliothèque nationale de France*. The same licence
as the `bnf-agents` fixtures.

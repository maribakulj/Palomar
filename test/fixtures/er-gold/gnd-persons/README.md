# GND persons — a small real extract for authority snapshots (lot M6)

Real GND authority records for the persons of the M2 gold
([`../bnf-persons/`](../bnf-persons/README.md)) and some of their
near-namesakes, in the format of the lobid GND dumps (JSON-LD, one record per
line). Used by `palomar.plugins.gnd-test`, `palomar.authority-index-test`,
`palomar.authority-snapshot-cli-test`, `palomar.no-network-test` and
`palomar.eval.gnd-person-reconciliation-test`; the write-up is the M6 section
of [`docs/eval/person-reconciliation.md`](../../../../docs/eval/person-reconciliation.md).

## `gnd-extract.jsonl`

**197 records**, fetched **2026-11-09** from the lobid GND API, each line copied
verbatim from a response, then deduplicated by `id` and sorted by `id`. Every
record is a `Person` (`DifferentiatedPerson`), and every one says its licence
is CC0 (`describedBy.license`). 560 374 bytes.

Each query is `https://lobid.org/gnd/search?q=<q>&filter=type:Person&format=jsonl`
(the JSON lines format returns every hit), with `<q>`:

1. `preferredName:"<heading>"` for each of the 37 BnF person authorities the
   M2 mentions cite (the 33 of the M2 pool and the 4 the BnF SRU does not
   serve), with the heading as the BnF writes it (36 headings: Dumas père
   and fils share one): Schlienger, Paul; Escala,
   Víctor Hugo; Compère, Daniel; Dumas, Alexandre; Férat, Jules; Hugo,
   François-Victor; Hugo, Victor; Lacassin, Francis; Schopp, Claude; Verne,
   Jules; Hetzel, Pierre-Jules; Robert-Dumas, Charles; Demaria Pesce,
   Victor-Hugo; Viscarra Rodríguez, Víctor Hugo; Macé, Jean; Dumas, Adolphe;
   Acuña Ortega, Víctor Hugo; Greaves, Roger; Lamps, René; Verne, Jean Jules;
   Wlassikoff, Michel; Robert-Dumas, Alexandre; Hetzel, Louis-Jules; Hugo,
   Georges-Victor; Pereira, Victor Hugo Adler; Rascón Banda, Víctor Hugo;
   Martínez González, Víctor Hugo; Stahl, P.-J.; Dumas de Rauly, Alexandre;
   Martel Paredes, Victor Hugo; D'Paola, Víctor Hugo; Lippmann, Alexandre;
   Rafina, Gesner; Harispe, Patrice; Paltsits, Victor Hugo; Hayat, Eric.
   (Fourteen return nothing: the GND writes them otherwise, or holds no
   record.)
2. Near-namesakes: `preferredName:"Dumas, Charles"`, `"Hugo, Charles"`,
   `"Hugo, François"`, `"Hetzel, Jules"` (no hit), `"Dumas, Alexander"`;
   `preferredNameEntityForThePerson.surname:"Verne"` (every Verne, 30);
   `preferredNameEntityForThePerson.forename:"Victor Hugo"` and
   `preferredNameEntityForThePerson.forename:"Víctor Hugo"` (the persons whose
   forenames are Victor Hugo, as in M2's "Víctor Hugo …" authors).

Broader queries were not kept, to keep the extract small: every Dumas (265
records), every Hugo (284), every Hetzel (167).

What the extract is not: a sample of the GND. It is drawn around the gold's
names, so it holds the gold's persons where the GND has them and their
namesakes, and nothing else; a real dump has millions of other records,
whose surnames would change the term frequencies the matcher reads.

## `bnf-gnd-wikidata.csv`

A **bridge** between the BnF authorities and the GND, from Wikidata (CC0),
fetched 2026-11-09 from `https://query.wikidata.org/sparql` with

```sparql
SELECT ?bnf ?gnd ?item WHERE { VALUES ?bnf { "104157513" "11105601p" "11897474w" "11901062n" "119010630" "11902539r" "11907962k" "11907966z" "11910450q" "11924113v" "11928016k" "12008284s" "12112416s" "12228519x" "12343063d" "12356458v" "12459839k" "12493117q" "12522837r" "126180118" "12644615z" "12692700r" "13001793k" "14616004s" "149406013" "150099057" "15042936n" "15126324q" "16492882d" "16510477r" "16936616n" "171149766" "17146255d" "16814325q" "10018888r" "12612031f" "14380255q" } ?item wdt:P268 ?bnf . OPTIONAL { ?item wdt:P227 ?gnd } } ORDER BY ?bnf ?gnd
```

(the BnF ids, P268, are the record numbers with their check character, as in
the ARKs; P227 is the GND id). 21 BnF ids have an item, 17 of them with a GND
id; every GND id it gives is in `gnd-extract.jsonl`. It is the eval's truth
bridge with the two the records themselves carry (a GND `sameAs` to a BnF
ARK, an ISNI both records state); the matcher never sees any of them.

## Licence

GND data: **CC0 1.0** (Deutsche Nationalbibliothek, via lobid.org, hbz).
Wikidata: **CC0 1.0**.

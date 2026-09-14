# Scale & performance budget (WP-7 / DoD #6)

What `palomar.convert/convert-stream` does, measured, and where the remaining bound
is — stated, not hidden. Tests: `palomar.convert-stream-test`.

## Why streaming is sound here (not the roadmap's "hard" clustering)

WP-7 flagged "cross-record Work clustering at scale, inherently global/stateful and
in tension with streaming" as the hard part. It is **not** hard in Palomar's design,
because clustering is **id-collision**, not a pairwise join: a Work's id is a pure
content hash of `agent + uniform/transcribed title` (ADR 0008). Two records of the
same Work mint the *same* id **without being compared** — convergence is a property
of the ids, realised when the emitted triples are aggregated by a downstream store
(roadmap §10, "converter → store"). So per-record conversion has **no cross-record
state**, and the converter can stream its triples in constant memory; the store (or
any `sort -u`) deduplicates by id. Palomar need never hold the corpus to "cluster".

## The mechanism

`convert-stream` `reduce`s a reducible/lazy record stream — one record at a time —
rendering each to the target and **folding** a loss accumulator (`loss-report/
accumulate`) whose footprint is bounded by the distinct fields/categories/edges, never
by the record count. Batch `convert`, by contrast, `mapv`s the whole corpus into a
vector and collects every loss diagnostic (O(N)). The streamed output and loss report
are **identical** to batch (`streamed-conversion-equals-batch`), minus the one
genuinely O(N) figure, `:distinct-losses` (the cross-corpus `[subject field]` dedup),
which stays batch-only.

## Measured budget

MARC21 → N-Triples, the 560-record BIB-R corpus cycled to scale, on the dev container,
**`-Xmx512m`**:

| corpus | wall | throughput | working set | loss `source-fields` |
|-------:|-----:|-----------:|------------:|---------------------:|
| 10 000 | 2.6 s | ~3 900 rec/s | ~33 MB | 25 |
| 100 000 | 23 s | ~4 300 rec/s | ~70 MB | 25 |

The two rows are the budget evidence: **throughput is flat** (per-record work is
constant) and the **working set does not grow with N** — 100 000 records ran in a
512 MB heap using ~70 MB, and the loss accumulator's distinct-field footprint stayed
**25 regardless of N** while the loss *total* scaled linearly (the
`the-loss-accumulator-is-bounded-in-the-corpus-size` test pins exact 5× scaling at no
footprint growth). Reproduce:

```
clojure -J-Xmx512m -M:sandbox:test/unit -n palomar.convert-stream-test
```

(Numbers are machine-relative; the committed tests assert the *properties* —
streamed == batch, bounded footprint, large-corpus completion — not wall-clock.)

## Lazy input parse — the one-giant-file story (now closed for the MARC family)

The input parse is no longer eager for the MARC family. `marcxml/stream-records`
pull-parses a `Reader` (`data.xml/parse`) and yields the root collection's records as
a **lazy** seq — each record realised, built and released as it is consumed — so a
single large *flat* MARCXML dump streams in bounded memory. It is wired as a plugin
`:stream-importer` on MARC21 / INTERMARC / UNIMARC (`convert/streamable?`,
`convert/stream-source`) and surfaced as the CLI verb:

```
palomar convert <big.xml> --from marc21 --to ntriples --stream --out <file>
```

**End-to-end CLI measurement** (the 56 000-record / 97 MB flat dump → N-Triples,
**`-Xmx256m`**):

| path | result |
|------|--------|
| batch (`parse-str`, eager) | **OOM** at 256 MB |
| `convert --stream` (lazy) | 56 000 records → 64 MB output, **~33 MB used**, ~21 s |

The eager path cannot load the file in 256 MB; the streaming path converts it using
~33 MB. That is the one-giant-file story, closed for the format family where it
matters (the BnF's MARC dumps).

The committed tests assert the *properties* (lazy seq, streamed records == the eager
importer's records, the streamable set is the MARC family); the 97 MB measurement is
reproducible with the snippet above on a generated dump.

## Honest scope — what is still bounded

- **Direct-children only / SRU pages stay eager.** `stream-records` reads the root
  collection's *direct* children (the bounded pattern — a deep tree-seq retains
  ancestors). So it streams **flat** dump collections; SRU responses (records nested
  under `srw:record/recordData`) are small pages and use the eager `ingest`, streamed
  at **page granularity** if a corpus spans many pages.
- **Non-MARC spokes don't stream.** Dublin Core / MODS / IIIF are single- or
  few-record formats (no flat-collection dump shape); `--stream` rejects them with the
  streamable set, rather than pretend.
- **Distinct-loss dedup is batch-only** — by design, the one O(N) metric.

## Bilan

- **Done.** Per-record conversion is stateless and streams in constant working set
  (100 000 records folded in a 512 MB heap), and the **input** now streams too for the
  MARC family: a 97 MB flat dump converts in ~33 MB via the CLI where the eager path
  OOMs. The id-collision design is what makes the whole pipeline stream — the converter
  emits, a store deduplicates by id.
- **Bounded only where it should be.** What remains eager (SRU pages, non-MARC
  single-record formats) is small by construction; the scale path — large MARC dumps —
  is streamed end to end.

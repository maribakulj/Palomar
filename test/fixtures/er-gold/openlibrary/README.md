# ER gold — OpenLibrary Work / Edition (independent corroboration)

A larger, **BnF-independent** corpus for evaluating entity-resolution clustering:
which *editions* belong to the same *work*, from OpenLibrary's native Work↔Edition
model. Used by `palomar.eval.openlibrary-er-test`.

## What it is

`work-editions.csv` — one row per edition, columns:

| column        | meaning                                              |
|---------------|------------------------------------------------------|
| `work_key`    | OpenLibrary Work id (`/works/OL…W`) — **the gold**   |
| `edition_key` | OpenLibrary Edition id (`/books/OL…M`)               |
| `title`       | the title **as printed on that edition** (varies)    |
| `author`      | author name                                          |

~4 555 editions across ~328 works (each with ≥3 editions), ~18 prolific authors;
median 5 editions/work, max 60. OpenLibrary data is public domain (CC0).

## Why it matters — and its honest limits

It is **independent of the BnF**, so it breaks the shared-signal caveat that limited
the C2 (data.bnf) gold: here the gold (`work_key`) and our input (`title`) come from
different acts. It lets us measure string-clustering recall on a corpus 13× the
Madame Bovary fixture.

**The gold is itself noisy.** OpenLibrary's work-merging is incomplete: the same
work is often split across many `work_key`s — *Frankenstein* appears under **14**
distinct works here, *Moby Dick* under 6. So:

- precision measured *against this gold* is **understated** — by construction every
  "false merge" of the exact `author + normalised-title` strategy is two editions
  with the same author and identical title that OpenLibrary filed under different
  works, i.e. a gold self-split, not a real error. Genuine false-merge rate ≈ 0.
- the honest signal is **recall** (variant titles missed), and even that is mildly
  optimistic because gold-splitting under-counts the should-be-together pairs.

It is **directional, not definitive**: only ~18 authors, all canonical (the
opposite of the long tail), and English-edition-heavy. The conclusion it
corroborates — string clustering cannot get both precision and recall; the
authority link is what does — is what generalises, not the exact numbers.

## Integrity

UTF-8, **no BOM**, Unix newlines. The export that produced this file
double-encoded one author (`Honoré de Balzac` → `HonorÃ©…`) on 84 rows and
prepended a BOM; both were repaired in place (the eval keys on `work_key` /
`title`, and no correctly-spelled `Honoré` rows existed, so the fix changed no
cluster and no metric). If you regenerate the CSV, write UTF-8 without a BOM and
spot-check accented authors before committing.

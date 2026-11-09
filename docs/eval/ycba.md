# Palomar against the Yale Center for British Art's own Linked Art

*2026-11-04, post-audit 8, K3. 500 objects. Frozen and rerun on 2026-11-09
(review of 2026-11-09, L4): see "The frozen benchmark" below.*

## Why this comparison

The Yale Center for British Art (YCBA) publishes, for the same objects:
- LIDO records, over OAI-PMH;
- its own Linked Art, made by its own pipeline from its collection database,
  not from its LIDO.

Converting the LIDO with Palomar and comparing the result with YCBA's Linked
Art compares two independent mappings of the same sources. Neither is the
truth. Where they differ, one of them is wrong, or they made different
choices. The study that found this pair (`docs/eval/rules-study.md`)
compared 40 objects. This report measures 500, before and after the two lots
the study recommended:
- K1, ids given by source and code;
- K2, attribution qualifiers.

## Method

- **Sample.** 500 objects of the first page of YCBA's Linked Art activity
  stream (`https://ycba-lux.s3.amazonaws.com/activity_stream/page1.json`),
  shuffled with seed 8. Each object's LIDO was fetched by its TMS number with
  `GetRecord`, on 2026-11-04. All 500 were converted without error, before
  and after.
- **Before** is Palomar at `e1864fe`, the study. **After** is Palomar with K1,
  K2 and this lot's fix to techniques (below).
- **Actors are matched by identity.** A YCBA person is its own URI plus the
  equivalents its record lists (ULAN, Wikidata, the British Museum…). It
  matches the Palomar actor written with one of them. An actor Palomar names
  by no authority is matched by normalised name. In total 287 YCBA people
  were fetched.
- **Makers.**
  - On YCBA's side: the Production's part classified "Primary Artist".
  - On Palomar's side: the Production's own `carried_out_by`, its creators
    (ADR 0054).
- **Reproducing it.** The scripts are in `dev/eval/ycba`
  (`sample.py`, `people.py`, `convert.clj`, `compare.py`, `diff_makers.py`,
  and since L4 `freeze.py`, `verify.py`, `refresh.py`).
  YCBA's data is fetched, never committed, under the Center's
  [Open Data and Data Services Terms of Use](https://britishart.yale.edu/open-data-and-data-services-terms-use)
  (Data Source: Yale Center for British Art).

## Results

| | YCBA | Palomar before | Palomar after |
|---|---|---|---|
| makers (YCBA: its Primary Artist part; Palomar: `carried_out_by`) | 499 | 623 | 497 |
| YCBA's makers Palomar names makers | — | 489 | 480 |
| Palomar's makers that are not YCBA's Primary Artist | — | 124 | 12 |
| Palomar's makers YCBA names in no part of the production | — | **37** | **8** |
| influences (`influenced_by`) | 31 | 0 | 33 |
| YCBA's influences Palomar names | — | 0 | **31** |
| custodian given | 499 | **0** | 499 |
| object types (`classified_as`) | 1 210 | **0** | 1 343 (710 shared) |
| materials (`made_of`) | 1 027 | **0** | 1 027 (1 004 shared) |
| dimensions | 1 492 | 1 492 | 1 492 |
| subjects of the visual item (distinct labels) | 6 089 `represents`, 80 `about` | **0** | 4 572 `about` |

The custodian row counts that one is given. YCBA names itself by its own
group URI, Palomar by ULAN 500303557: the same institution, two ids.

Before K1 and K2:
- Palomar wrote no type, material, custodian or subject for any of these
  records, because YCBA gives its ids as source and code.
- 124 actors were stated to have made works that are not YCBA's Primary
  Artist. **37 of them YCBA names in no part of the production at all**: the
  artist a print is after, a follower's master, a former attribution. The
  other 87 YCBA names in another part: a publisher, the author of a text, the
  painter of the figures.

After:
- 12 are not YCBA's Primary Artist, and 8 are named in no part.
- Every influence YCBA states, Palomar states too.

### The makers that still differ

`diff_makers.py` lists them, record by record.

**The 12 makers Palomar names that are not YCBA's Primary Artist:**
- **One person matched as two (8).** John Varley (5 records), Joseph
  Nollekens (2), James Wyatt (1). Palomar writes the actor with the first id
  its LIDO gives, a Library of Congress name, and YCBA's record of that
  person lists other equivalents. This is the comparison's limit, not a
  wrong attribution. They are the 8 "in no part".
- **A part's maker (4).** "Landscape by", "figures by", "Figure by". Palomar
  states each as a maker of the work. YCBA states one as Primary Artist and
  the other as the maker of a part. Both say the same people made it.

**The 19 makers YCBA names that Palomar does not:**
- **"Attributed to" (9).** Palomar *proposes* the attribution (ADR 0054, K2).
  YCBA states the artist as Primary Artist.
- **"Follower of" (2).** YCBA states an agent "Follower of Samuel Prout".
  Palomar says the work was influenced by Prout, and reports that who made it
  is not stated.
- **One person matched as two (8)**, the same as above.

No maker that differs is an influence, a former attribution or a publisher
stated as maker. What remains are choices (proposing "attributed to", naming
no maker for "follower of") and the matching's limit.

The vocabulary now names a part's maker ("Landscape by", "figures by",
"portrait by", the qualifiers these records use). This changes no output
here: with no qualifier the roles already made these actors makers. It
makes them makers by what the qualifier says.

### Subjects

YCBA types each subject set as a `description`, and its own Linked Art says
`represents`: 6 089 distinct labels (6 108 statements). After K1 to K3,
Palomar's Linked Art still wrote every subject as `about` (4 572 distinct
labels). **Since K5 (ADR 0056)** the rules read the subject's type, and
Linked Art writes what the CIDOC CRM view states: the same 4 572 are
`represents`, none `about`, as YCBA has them. The makers and influences
stay as above. The makers Palomar writes fall from 497 to 492: one node
named twice in a production is written once. YCBA's 6 089
also count the persons and places a work represents (`subjectActor`,
`subjectPlace`), which Palomar does not write in the visual item.

### Materials: an error this comparison found

The first run of this comparison gave Palomar 1 419 materials against
YCBA's 1 027. 392 of the 415 extra ones were techniques: "painting
(technique)", "etching (printing process)", "line engraving". YCBA's Linked
Art writes these as `technique`, and Palomar wrote them in `made_of`, as
materials.
- **The cause.** YCBA types each materials term on its `conceptID`
  (`lido:type="technique"`), not on `termMaterialsTech` where LIDO has it.
  Palomar read the type only there.
- **The fix.** It now reads the type on either element (K3,
  `lido-test/a-technique-is-no-material-wherever-its-type-is-given`).
- **After the fix.** Materials agree on 1 004 of YCBA's 1 027, and the
  techniques are what the production used (`P32`).

### Types

Palomar writes 1 343 types against YCBA's 1 210, and 710 are shared.
- **Palomar's extra 633** are mostly genres: landscape (134), genre subject
  (92), portrait (85), architectural subject (62). Palomar writes a LIDO
  classification on the object. YCBA's Linked Art writes a genre on the
  visual item, as what the image is of.
- **YCBA's 500 that Palomar lacks** are its two top classes, "Visual Works"
  (483) and "Objects" (17). The LIDO does not give them.

## The frozen benchmark (L4)

The review of 2026-11-09 asked that this comparison be one that can be
rerun and read without overstating it.

- **The corpus is frozen.** `docs/eval/benchmarks/ycba/corpus-manifest.json`
  names the 500 pairs: each TMS number, its Linked Art object URI, when it
  was fetched (2026-11-04, 04:48 to 05:00 UTC), and the SHA-256 of its LIDO
  and of its Linked Art. It also gives the digest of the activity page and
  of `people.json`, the seed, and the Palomar commit it was frozen at. The
  data itself is not committed; its ids and digests are.
  - *Frozen mode, offline:* `verify.py <dir> <manifest>` says whether the
    pairs on disk, `people.json` and the activity page are the frozen ones,
    and `compare.py --manifest` compares only those pairs, with rows for any
    that are not. Without `people.json` makers match by name only, and
    `compare.py` says so.
  - *Refreshed mode, network:* `refresh.py <manifest> <dir>` fetches the same
    pairs and their people again (not a new sample), into a new directory
    only. `verify.py` then says which YCBA changed.
- **Matches are counted by kind.** Of the 480 YCBA makers found in Palomar:
  - 0 by YCBA's own id: Palomar never writes YCBA's URIs;
  - 465 by an id YCBA's record of the person lists (ULAN, Wikidata, LoC…);
  - 15 by name only. These are actors the LIDO names by no authority:
    Alfred Diston (3), Daniel Turner (3), Thomas Wright, William Dixon and 7
    others. Two namesakes would agree this way too, so these 15 are
    agreements to check, not agreements established.
- **Dimensions are compared by value, unit, type and what is measured**, not
  by count. By value, unit and type, 1 491 of 1 492 agreed. With what is
  measured, they agreed on **566 of 1 492**, where the count had said
  1 492 = 1 492.
  - The cause: YCBA classifies each dimension by what it measures (Sheet,
    Frame, Plate, Image, Mount…), from the LIDO's `extentMeasurements`.
    Palomar read that element apart from the measurements and wrote it
    nowhere, so a frame's height was stated as the object's height.
  - The fix: each dimension now carries the extent of its measurements, as
    a type in the CIDOC CRM view (`P2_has_type`) and in Linked Art
    (`classified_as`), one node of its own in both
    (`lido-test/a-dimension-says-what-it-measures`,
    `lido-test/an-extent-is-a-part-of-its-own`). An extent that names no
    part ("Dimensions") is not written; the English term is read first; a
    value that is no number keeps its extent in its note.
  - The one dimension left that differs (pair 548) is a frame's depth that
    Palomar types by no AAT term; not this fix's.
  - After it, **1 491 of 1 492** agree on type, unit, value and what is
    measured.

| (frozen corpus, 0 pairs not as frozen) | YCBA | Palomar before L4 | Palomar after L4 |
|---|---|---|---|
| YCBA's makers Palomar names | 499 | 480 | 480 |
| … by the same id / an equivalent id / name only | — | 0 / 465 / 15 | 0 / 465 / 15 |
| dimensions | 1 492 | 1 492 | 1 492 |
| … same type, unit and value | — | 1 491 | 1 491 |
| … and the same part measured | — | **566** | **1 491** |

All other rows are as above.

## What it shows

- Testing against an independent mapping found errors no internal test
  could: ids by source and code, attribution qualifiers, techniques written
  as materials. Each was fixed in one lot and measured here on 500 objects.
- Palomar's makers that YCBA names nowhere in the production fell from 37 to
  8, and those 8 are one person matched as two.
- The disagreements left are choices (proposing "attributed to", naming no
  maker for "follower of"), the limits of matching, or readings that differ
  without contradicting each other.
- Subjects are the one large gap left, and it is known and planned (K5).
- This is the agreement of two independent mappings, not a measure of
  correctness: YCBA's mapping is not annotated truth, and both can share an
  error. Where they disagree, the report says which and why.

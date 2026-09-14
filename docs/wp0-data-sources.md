# WP-0 — Data sources for a universal V1

- Status: **Draft** — a curated manifest of the open specs, example corpora,
  and authority datasets the universal V1 (IIIF ↔ INTERMARC) will need.
- Date: 2026-05-31
- Feeds: the WP-0 WEMI-derivation spike, the D5 identity resolver, and the WP-2/WP-4
  vocabulary + spoke work. See [`roadmap-v1.md`](./roadmap-v1.md),
  [`wp0-decisions.md`](./wp0-decisions.md).

> **Scope (per maintainer): a *universal* V1** — one product that handles IIIF,
> CIDOC/Linked Art, and the full MARC family (MARC21 / UNIMARC / INTERMARC)
> equally. That means we need exemplar data for **every spoke + the pivot +
> authorities + at least one real corpus**, not a single-format sample.
>
> **Environment note.** Automated `WebFetch` of several hosts below returns HTTP
> 403 in this sandbox; the URLs were verified via web search. Actual downloading
> happens at WP-0 with appropriate access. Respect each source's licence in any
> redistributed test fixture (summary table at the end).

---

## 1. Pivot vocabulary — LRMoo / CIDOC-CRM
The rich pivot (strategy C, decision D1) and the typed view (D3, WP-2).

| Source | What / use | Access |
|---|---|---|
| **LRMoo v1.0** (official, approved Apr 2024) | F1 Work / F2 Expression / F3 Manifestation / F5 Item + R-properties; the WEMI mapping | `cidoc-crm.org/sites/default/files/LRMoo_V1.0.pdf`, home `cidoc-crm.org/lrmoo` |
| **LRMoo short intro** + **FRBRoo→LRMoo transition** | rationale, worked relationships | `cidoc-crm.org/lrmoo/short-intro-frbroo`, transition doc on cidoc-crm.org |
| **CIDOC-CRM** (base ontology) | the classes LRMoo specialises; museum side | `cidoc-crm.org` |

## 2. MARC family — universal dialect coverage (D10 → dialect-agnostic)
A shared MARC core + pluggable dialect **profiles** (plugins-as-data).

| Dialect | Source | Notes |
|---|---|---|
| **MARC21** | LoC format `loc.gov/marc/bibliographic/`, full examples `loc.gov/marc/bibliographic/examples.html`, MARCXML schema `loc.gov/standards/marcxml/` | US-gov public domain; best-documented; richest examples |
| **UNIMARC** | IFLA manual `repository.ifla.org`, concise bib format `archive.ifla.org/VI/8/unimarc-concise-bibliographic-format-2008.pdf`, authorities format PDF | IFLA standard; BnF's public diffusion format |
| **INTERMARC** | BnF Kitcat manuals `kitcat.bnf.fr/manuel-intermarc`, "INTERMARC bibliographique de diffusion" `bnf.fr/fr/intermarc-bibliographique-de-diffusion` | BnF's *native* format; public spec is thinner than MARC21/UNIMARC — flag at WP-0 |

## 3. Real bibliographic corpora — for the spike + scale tests
| Source | What / use | Licence |
|---|---|---|
| **BnF datasets** `api.bnf.fr` | real bibliographic record products (retrospective, monthly, retroconverted) in UNIMARC; INTERMARC documentation; the **full data.bnf.fr RDF dump** | **Licence Ouverte** (commercial OK, attribution) |
| **LoC MARC examples** | small, hand-curated MARC21 records for parser/unit tests | public domain |
| **Open Library data dumps** `openlibrary.org/developers/dumps` | millions of records → WP-7 scale/perf tests | open |

## 4. IIIF — presentation spoke
| Source | What / use | Access |
|---|---|---|
| **Presentation API 3.0** | the spec | `iiif.io/api/presentation/3.0/` |
| **IIIF Cookbook** | ready example manifests: simplest image, book/multi-image, audio, video, annotations, ranges/structures (ToC), multilingual metadata | recipe list `iiif.io/api/cookbook/recipe/all/`; per-recipe `…/recipe/<id>/manifest.json` (e.g. `0009-book-1`) |

Use: IIIF importer + linking `:canon/digital-object` to pivot entities. Licence: open.

## 5. Museum — CIDOC-CRM / Linked Art spoke (Louvre side)
| Source | What / use | Access |
|---|---|---|
| **Linked Art model + cookbook** | example objects with raw JSON **and** Turtle per case; the JSON-LD context | `linked.art/model/`, `linked.art/api/1.0/json-ld/` |
| **CIDOC-CRM** | the underlying ontology for the export profile | `cidoc-crm.org` |

Use: museum export profile + JSON-LD/RDF serialisation reference (WP-4). Licence: open.

## 6. Authorities — the D5 resolver #2 (identity reconciliation)
Pinned **snapshots**, read as input (not a live service, not a maintained store).

| Source | Why | Licence / caveat |
|---|---|---|
| **data.bnf.fr** dumps + SPARQL | persons/works/concepts with **ARK** ids, aligned to VIAF/ISNI/IdRef/Wikidata; RDF-XML/NT/N3 | **Licence Ouverte** — primary for the French/BnF universe |
| **ISNI linked data** `isni.org/page/linked-data/` | persons & orgs in RDF/XML + JSON-LD, cross-links to LC/NACO, data.bnf.fr, Wikidata, MusicBrainz | **CC0** — primary because open + international |
| **VIAF** `viaf.org/en/viaf/data` | the international aggregator; good as an alignment reference | ODC-BY, but **bulk dumps are gated (need approval) and not updated since Aug 2024** — do *not* rely on as the live primary |
| **IdRef** (ABES) | French academic authorities; aligned | open |
| **Wikidata** | CC0; broad coverage; cross-linking glue | CC0 |

Use: feed the authority-anchored resolver; emit authority IRIs as `sameAs` in the
RDF/Linked Art output. For universality, the resolver is a **pluggable provider**
(ISNI/Wikidata as universal defaults; data.bnf.fr/IdRef for the French target).

## 7. Dublin Core — the pipe-cleaner spoke
| Source | Use |
|---|---|
| **DCMI Metadata Terms** `dublincore.org` | simplest spoke; first end-to-end pivot validation (WP-4 #1) |

---

## Minimal "spike kit" (smallest set to start WP-0)
Enough to measure work-key over/under-merge **across dialects** and validate the
typed view on **both** the biblio and museum sides:

- ~200 **MARC21** records (LoC examples + a public set) **and** ~200 **UNIMARC**
  records (BnF `api.bnf.fr`) — cross-dialect WEMI derivation;
- a **data.bnf.fr** authority subset (or an **ISNI** CC0 subset) — wire resolver #2;
- 5–6 **IIIF cookbook** manifests (image, book, A/V, ranges);
- 5–6 **Linked Art** cookbook objects (JSON + Turtle);
- the **LRMoo v1.0** worked examples — validate the WEMI mapping.

## Licence summary (matters for a shipped product)
| Open / safe to redistribute | Spec/doc (examples usable) | Gated / caution |
|---|---|---|
| data.bnf.fr (Licence Ouverte), ISNI (CC0), Wikidata (CC0), LoC MARC (public domain), IIIF cookbook, Linked Art | IFLA UNIMARC manual, LRMoo/CIDOC spec, INTERMARC manuals | VIAF bulk (approval + stale since 2024) |

## Open follow-ups for WP-0
- Confirm exact **data.bnf.fr full-dump** URL + format and BnF record-set
  download endpoints (blocked from automated fetch here).
- Decide the **authority snapshot** to pin first (recommend ISNI CC0 +
  data.bnf.fr) and its refresh cadence.
- Confirm INTERMARC spec depth is sufficient to build a profile, or treat
  INTERMARC as UNIMARC-via-BnF-conversion initially.

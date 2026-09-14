# WP-0 spike — WEMI derivation on real INTERMARC (findings)

- Status: **spike (throwaway)** — empirical input to ADR 0016 (D5 / D6 / D11),
  not production code.
- Date: 2026-06-01
- Inputs: `test/fixtures/documentary/intermarc/iso2709_utf8/` (BnF INTERMARC,
  149 bibliographic + 122 authority records).
- Method: `dev/spike/frbrise_intermarc.py` — parse ISO 2709, derive a naive Work
  key (author + title), cluster, probe reconciliation.

## Headline findings

1. **Parser works on real ISO 2709.** 149 bib + 122 authority records decoded
   (leader / directory / subfields), multiscript content intact.

2. **`$3` embedded authority id has 100% coverage — this is the reconciliation
   signal.** Every access point (1XX / 6XX / 7XX) carries a BnF authority record
   number in `$3`: 108/108 main authors, **346/346 access-point fields**, 370
   distinct authority ids. Meanwhile exact normalized **name** matching against
   the authority file scored **4%** (heavy transliteration: Arabic, Hebrew,
   Chinese romanisations). → For authority-controlled sources, reconciliation is
   a **lookup on `$3`**, not a fuzzy-matching problem.

3. **Multiscript parallel fields are pervasive.** Title (245), edition (250),
   publication (260) are *repeated* — one romanised, one in the original script
   (e.g. `245 $aAdūnīs muntaḥilan` alongside `245 $aأدونيس منتحلا`),
   distinguished by `$w`. A naive "first 245$a" key silently picks one script →
   the same Work splits across script variants.

4. **Title-only clustering over-merges.** 8 records titled "Li čo sil lok" with
   *no* 1XX author collapsed into a single Work on title alone — exactly the
   precision hazard. With no author and no `$3` on a work-level access point, the
   signal is too weak to *assert* a merge.

5. **Record (Manifestation) identity is already in the data.** `001` = the BnF
   record number (FRBNF…), `003` = its ARK (`cb37319050s`). Manifestation
   identity needs no synthesis — read it off the record.

6. **~27% of records have no personal author** (100 present in 108/149): title
   main entry / corporate. The Work key cannot assume a 1XX exists.

## Refinements to the accepted decisions

These *tune* what ADR 0016 deferred to the spike; none reopens a decision.

- **D5 — resolver order (refined).** The identity resolver should try, in order:
  1. **embedded authority id** (`$3`, and `$0`/`$1` in other dialects) →
     resolve to the authority IRI via the dump. *Primary; 100% on INTERMARC,
     fully deterministic.*
  2. **deterministic work-key hash** — fallback when no embedded id (CSV, some
     MARC21).
  3. fuzzy name matching — last resort only; weak (4%) and transliteration-
     fragile. Effectively deprioritised.
- **D5 — work-key (refined).** Canonicalise **multiscript**: prefer the original
  script (or a single stable romanisation) so script variants converge. Do not
  build a Work key from a single arbitrary parallel field.
- **D5 — Manifestation key (refined).** Use `001` / `003` (ARK) directly; no
  synthesis needed at the Manifestation level.
- **D11 / D7 — precision-first (confirmed on real data).** Never *assert* a merge
  on title alone (no author / no `$3`); route those to proposals. The ×8 case is
  the concrete justification.
- **D6 — clustering (confirmed).** With `$3` and ARK present, identity is
  deterministic from the data itself — batch-local clustering needs no store.

## Caveats / limits

- Small samples; the bib and authority files are **disjoint** sets, so the 4%
  name-match is partly sample mismatch — but the `$3` = 100% finding is intrinsic
  and decisive regardless.
- BnF INTERMARC is *exceptionally* authority-controlled; sources without embedded
  ids (some MARC21, CSV) still need the hash fallback — which is exactly why the
  resolver keeps it.
- The ISO-5426 variant was not exercised (charset handling still to validate).
- The spike measured the **Work key + reconciliation** only; it did **not** yet
  exercise Expression/Manifestation linking, so **D8 (fixed passes vs fixpoint)
  remains open** as planned — to be probed when WEMI linking is prototyped.
- Throwaway code; naive normalisation; illustrative multiscript handling.

## WEMI-linking iteration (InterMARCXChange, 2026-06-01)

Second spike (`dev/spike/wemi_xchange.py`) on the BnF SRU **InterMARCXChange**
fixtures (`test/fixtures/documentary/intermarc/sru/`), 30 *Madame Bovary*
manifestations:

- **28/30 carry an explicit Manifestation→Work link** — field `145 $3` = the
  Work authority id (`11938746`). 29/30 carry the author authority id (`100 $3`)
  and **29/30 carry ISNI** (`100 $1`).
- The 28 linked manifestations cluster to **one** Work via the explicit id — no
  inference, a pure **lookup**.
- The 2 unlinked records are instructive: one is a **publisher-feed ebook**
  (no `$3`) that the (author+title) fallback keys to `flaubert|madame bovary` —
  correct, but it lands in a *separate* cluster, so a **bridging** step is needed
  to merge it into the existing Work; the other is **"Le Réalisme. Madame
  Bovary…"**, a study guide — a genuinely *different* work the fallback correctly
  keeps apart.

Implications:

- **WEMI linking is largely a lookup, not iterative inference** (93% explicit
  here). → **D8 resolved: bounded fixed passes suffice; no fixpoint needed.** A
  cascade could only arise on the minority fallback-synthesis path, which is
  itself bounded (synthesise, then one bridge attempt to existing Works).
- The fallback's *correct separation* of the study guide re-confirms **D11**
  precision-first: do not merge on title similarity alone.
- **ISNI (`$1`) ≈ 97%** → interoperable `sameAs` IRIs in the RDF / Linked Art
  output essentially for free (D5).

## Fallback-bridging iteration (2026-06-01)

Third spike (`dev/spike/bridge_fallback.py`) on the same *Madame Bovary* set:
rebuild the Work from the 28 linked records, then bridge the 2 unlinked records.

- **Conservative bridge (exact normalized author + title):** bridges the
  publisher-feed **ebook** into Work `11938746` (correct) and **keeps the study
  guide** "Le Réalisme. Madame Bovary…" separate (correct). **0 false merges.**
- **Greedy bridge (title substring):** also merges the ebook, but
  **false-merges the study guide** — its title *contains* "Madame Bovary".
  **1 false merge.**

D11 made concrete: the conservative commit is right on both; the greedy net
fabricates a false identity. Design rule confirmed:

- **auto-commit only on exact / high-confidence** match;
- route **near-misses** (high but inexact similarity — e.g. subtitle variants
  the exact rule would *miss*) to **proposals** (D7), never to a greedy
  auto-merge. A miss is recoverable; a false merge is not.

**WP-1 requirement surfaced.** Bridging is *match-or-mint*: the `infer` phase
must maintain and expose a **batch-level index of Works seen/minted so far**
(keyed by work-id *and* by normalized author+title) so the resolver can match
against it. Minting is therefore not purely per-record — the runtime must
accumulate and expose batch Work-state during `infer`. Captured now so the WP-1
identity seam is built for it, not retrofitted.

Caveat: only 2 unlinked records — this demonstrates the *mechanism and the
precision principle*, not a fidelity statistic.

## Broadened evaluation — correction (2026-06-01)

Pushed by the question *"is one example enough?"*, I evaluated the fallback key
against the explicit `145 $3` link as **ground truth across all 14 bib fixtures**
(`dev/spike/keyeval.py`) and verified field coverage directly. **The earlier
WEMI / D8 claim was an artefact of a showcase example.**

- Explicit Work-link (`145 $3`) coverage: **93% on *Madame Bovary*, 0% on every
  other set** (Hugo, Gracq, monographs, music, periodicals, youth, analytics) —
  **~7% overall**. Verified: `145` (and 240/740 uniform titles) are genuinely
  *absent* elsewhere, not a parser gap. *Madame Bovary* is the BnF's flagship
  WEMI-derived example; for the **bulk of the catalogue, Works are not linked → they
  must be inferred, not looked up.**

**Caveat on this figure (verified 2026-06-01) — it is contaminated; the number
is withdrawn.** The "~7%" ran a *bibliographic* parser over the whole SRU set,
which includes **121 `type="Authority"` records** (persons, corporate, subjects,
and *musical works*) that structurally have no `245`/`145` — counting their "0%"
is a category error. Among *bibliographic* records only it is ≈12%, still
essentially just the *Madame Bovary* showcase; and even that under-counts,
because work-link fields are **material-type-specific** (monographs `145`; music
links to Work authorities via `7XX`/`730`). The *direction* (explicit Work links
are sparse in bib records outside LRM pilots) likely holds; the *rate* needs
record-type- and material-type-aware parsing. Note too: **Works do exist as
authorities** — `aut-oeuvres-musicales` are F1 Works (`144` heading + `100`
composer + ISNI) — so "Works aren't catalogued" is too strong for domains like
music. **And all of this is INTERMARC-only:** the MARC21, MODS, Dublin Core,
IIIF, EAD, EAC-CPF, MADS and METS fixtures are not yet parsed at all.

What stands, what is retracted:

- ✅ **Agent reconciliation is a lookup, robustly** — `100 $3` (and `6XX`/`7XX`
  $3) is present across *all* genres. The first spike's finding generalises.
- ❌ **"WEMI linking is a lookup → D8 resolved" is retracted.** True only for the
  ~7% already WEMI-derived. The dominant path is **inference**, which the spikes did
  *not* exercise (the showcase clustered by lookup, with no Work-synthesis
  cascade). **D8 reverts to unconfirmed.**
- ⚠️ **The fallback key is fragile.** Even within *Madame Bovary*, the one Work
  spans 4+ raw `245`-title keys ("madame bovary", "…moeurs de province",
  "…eaux-fortes originales…"): the key must use the **uniform title**, not the
  title proper, and even then variants under-merge → many true merges become
  *proposals*, not auto-commits.
- ⚠️ **Work-link fields are heterogeneous by material type** (`145` monographs;
  `730`/`7XX` name-title for music). The monograph-centric `145` assumption does
  not generalise.
- ⚠️ **Measuring WEMI-derivation fidelity is itself hard:** ground truth is sparse
  exactly on the genres that need inference. A real evaluation needs labelled
  data / BnF's own Work authorities — risk **R4** (data + oracle) biting as the
  roadmap warned.

## Net (corrected)

The substrate decisions hold and **agent reconciliation is de-risked**. But
**Work / WEMI derivation is *characterised, not de-risked*** — and it looks *harder*
than the showcase suggested: inference dominates, ground truth is sparse, the
fallback key needs uniform titles. **D8 is reopened** pending an inference-path
spike. This is the spike phase doing its job — failing cheaply now, before WP-1
builds on a false premise.

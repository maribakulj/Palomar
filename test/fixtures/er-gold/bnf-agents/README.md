# ER gold — BnF agents (authority-id reconciliation)

Real BnF data for evaluating **cross-record agent reconciliation** (ADR 0018,
certified tier): does grouping authority-identified agents by their **ISNI**
collapse the same person across records *and keep distinct people apart*?

## What it is

- `../../documentary/intermarc/sru/intermarcXchange/bnf-sru-victor-hugo-50.xml`
  and `bnf-sru-jules-verne-50.xml` — 50 + 50 real INTERMARC records from the BnF
  SRU API (`query=bib.author all "victor hugo"` / `"jules verne"`, Licence Ouverte).
- `authority.json` — ~10 authors from Wikidata (ISNI + VIAF + BnF id + name
  variants), the reconciliation reference. **9/10 are real**; entry 1 is the
  glitch below.

## The finding (used by `palomar.eval.bnf-agent-reconciliation-test`)

Of the 100 records, **43 carry a main-entry (100 `$1`) ISNI**; they reconcile to
**12 distinct certified agents** — because "Victor Hugo" / "Jules Verne" as a
*search string* match many distinct people, which the ISNI correctly separates:

- **Jules Verne** the novelist (ISNI `…121400562`, 21 editions → one agent) vs
  **Jean Jules-Verne**, his biographer (ISNI `…083439748`) — near-identical
  names, **never merged**.
- Victor Hugo's grandson **Georges-Victor Hugo** (painter), his son
  **François-Victor Hugo** (translator), and a cohort of Latin-American authors
  *named* "Víctor Hugo …" — all distinct ISNIs, all kept apart.
- The **writer** Victor Hugo (`Q535`, ISNI `0000000121200174`) does not even
  appear as a main-entry agent in this slice — an honest coverage observation
  (his works carry him elsewhere / without a 100 `$1` here).

A name-string matcher would have conflated all of these into one blob; by ISNI,
precision is perfect by construction (distinct id ⇒ distinct person). This is the
measured precision/recall lesson (`entity-resolution.md`) on the *agent* axis.

## The metro-station glitch (kept on purpose — the negative case)

`authority.json` entry 1, searched `"Victor Hugo"`, resolved (via a naive
name-search fallback) to **`Q1459231` — the *Victor Hugo metro station* in
Paris**, not the writer (`Q535`): so its `isni`/`viaf`/`bnf` are `null`. It is the
perfect concrete proof of why reconciliation must key on a *determinate id*: a
name string can match a namesake, a descendant, **or a non-person**. The eval uses
it as the negative case — a name-only "Victor Hugo" never enters the
ISNI-certified set and is never merged.

# Sprint 6 — Canonical vocabulary and its first validation rule

- Theme: enforce the canonical layer — ship the `:canon/*` documentary
  vocabulary as data and the first validation rule over it.
- Status: Landed
- Architectural anchors: ADR 0003 (core vs canonical vocabulary),
  ADR 0001 (diagnostics as first-class IR), ADR 0002 (rule DSL),
  ADR 0007 (plugins as data), ADR 0011 (fragments for qualified values).

## Goal

Close the loop ADR 0003 opened. Sprint 5 made documentary sources flow
into `:canon/*` assertions (ingest → normalize); Sprint 6 ships the
canonical vocabulary itself and the first rule that *validates* it, then
proves the whole path end to end: a raw source becomes a validated,
reportable record.

## Decisions

Two scoping calls framed the sprint.

**1. How far to assemble the rules' consumer — rules via an integration
test (not vocabulary-only, not a full CLI).**

The validation rules had no assembled consumer before this sprint
(`app.clj` is a stub; the Sprint 5 integration test stopped at
`:normalize`). Three options were on the table: ship the vocabulary
alone; ship rules exercised by a real assembled pipeline in an
integration test; or ship rules plus a `palomar validate` CLI command.

We took the middle path. The rule is exercised by a registry-driven
pipeline assembled from production components — shape importer, mapping
compiler, rule engine, diagnostics report — with the diagnostics report
(a real, already-built V1 surface) as its consumer. This is not the
"build a consumer just to eat the producer's output" trap: nothing is
mocked, and the report is independently justified. The only deferred
piece is the user-facing CLI entry point (`palomar validate`), which is
its own sprint's worth of I/O scaffolding and would have turned "Sprint 6
= canonical vocabulary" into "Sprint 6 = build the CLI."

**2. Canonical set = the eight ADR 0003 predicates, verbatim.**

`:canon/lang` is produced by real flows today (qualified mappings, 20-odd
uses) but it is a *qualifier coord that rides on a fragment* (ADR 0011),
not a documentary field. ADR 0003's documentary set is exactly eight
predicates and lang is not among them. Rather than amend an Accepted ADR
to cram a categorically different term into a flat set, we shipped the
eight verbatim and left lang where it lives, to be formalized when the
qualifier vocabulary gets its own design. No ADR amendment.

> Correction landed during implementation: earlier working notes cited
> "ADR 0003 §Layer 2 / §Status quo" as stating the lang distinction.
> Those sections do not exist — ADR 0003 simply lists eight documentary
> predicates and never mentions `:canon/lang`. The lang-is-a-qualifier
> rule is established by ADR 0011, not ADR 0003. All docstrings and tests
> cite the real sources.

## Deliverables

| Item | File |
|---|---|
| Documentary vocabulary (`documentary-vocabulary`) + `documentary?` | `src/palomar/plugins/canonical.clj` |
| `title-required` `:validate` rule + the canonical `plugin` map | `src/palomar/plugins/canonical.clj` |
| Unit tests (vocabulary, plugin shape, rule in isolation) | `test/unit/palomar/plugins/canonical_test.clj` |
| End-to-end ingest → normalize → validate → report test | `test/integration/palomar/canonical_integration_test.clj` |
| CHANGELOG entry | `CHANGELOG.md` |

Design notes:

- The plugin is pure data — no `:require`. Deep rule validity is the
  compiler's job; the unit test asserts `compile-rules` accepts the
  shipped rule, which is the codebase's chosen point for that check
  (plugins defer deep validation to compilers to avoid load-order
  coupling, per `palomar.plugins`).
- `documentary?` mirrors `palomar.model/structural?`: a vocabulary set
  plus a membership predicate, one per layer.
- `title-required` is a `:warning`, not an `:error`: a titleless record
  is incomplete, not malformed. The integration test demonstrates both
  the default `:errors-only` policy (passes) and `:errors-and-warnings`
  (fails) so the failure-policy surface is covered.

## Results

- Full suite: 386 tests, 1057 assertions, 0 failures, 0 errors
  (`clojure -M:sandbox:test`). Canonical contribution: 10 tests, 49
  assertions.
- `clj-kondo` clean (0 errors, 0 warnings) and `cljfmt` clean, run via
  the standalone binaries the SessionStart hook installs (the
  Clojars-backed `:lint` / `:fmt` aliases can't resolve in the sandbox;
  see ADR 0006).
- The core stays vocabulary-blind: `palomar.architecture-test` still
  passes — no core namespace depends on `palomar.plugins.canonical`.

## Out of scope (explicit)

- `palomar validate` CLI command and the rest of `app.clj`. The
  integration test assembles exactly the registry-driven pipeline the CLI
  will wire to file I/O; that wiring is the next natural unit of work.
- Date/ISO and other cross-source validation rules. They wait for a
  concrete producer of the predicates they would check (growth
  discipline, ADR 0003 §Consequences). The biblio fixture produces no
  `:canon/date`, so a date rule would be inert today.
- A qualifier vocabulary (formalizing `:canon/lang` and its kin). Lang
  is produced and renamed already (ADR 0009/0011); naming it as a
  first-class qualifier set is a separate, deferred design.
- Dublin Core plugin proper — Sprint 7. It will register its own mapping
  to `:canon/*` and inherit canonical validation by registering this
  plugin alongside it.

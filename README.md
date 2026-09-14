# Palomar

[![CI](https://github.com/maribakulj/palomar/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/maribakulj/palomar/actions/workflows/ci.yml)
[![codecov](https://codecov.io/gh/maribakulj/palomar/branch/main/graph/badge.svg)](https://codecov.io/gh/maribakulj/palomar)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](./LICENSE)
[![Clojure](https://img.shields.io/badge/Clojure-1.12-5881D8?logo=clojure&logoColor=white)](https://clojure.org)
[![Java](https://img.shields.io/badge/Java-21%2B-007396?logo=openjdk&logoColor=white)](https://adoptium.net/)

A documentary compiler for cultural metadata.

Palomar ingests heterogeneous metadata records, normalizes them into a stable
internal representation, runs explicit validation, inference, and repair passes
over that representation, and projects the result into one or more target
formats — carrying diagnostics and provenance throughout.

The architecture is deliberately modeled on a compiler: sources are parsed into
an IR, the IR is transformed by explicit passes, and targets are emitted from
the IR. Palomar does this for metadata rather than for code.

---

## What Palomar is

- A **metadata transformation engine** with a stable, schema-independent core.
- A **declarative rule system**: validation, inference, normalization, repair,
  and projection logic are expressed as data (EDN), not as code.
- A **plugin platform**: format-specific support (importers, exporters,
  rules, mappings) is loaded as plugins; none is privileged, none lives
  in the core.
- A **diagnostic-first system**: ambiguity, contradiction, intermediate states,
  and proposed repairs are first-class citizens of the internal representation,
  not side channels.

## What Palomar is not

- A converter tied to a single standard.
- An AI-first tool. The V1 stands on its own; LLM assistance is a deliberate
  future, never the foundation.
- A storage system, a query engine, or a web application. It is a library and
  a CLI; it batches over files.

---

## Core concepts

### The internal representation

Records in Palomar are **assertion sets**, not field maps. An assertion is:

```clojure
{:subject    :record/r42
 :predicate  :canon/title
 :value      "Les Misérables"
 :provenance {:source :xml/sample.xml
              :pass   :ingest
              :rule   :dc/map-title}
 :confidence 1.0
 :status     :asserted}
```

A record wraps an identity, a source pointer, an assertion set, a diagnostic
set, and fragment pointers back into the raw source tree.

This shape is what lets Palomar natively represent:

- multiplicity (two candidate titles)
- ambiguity (a date that is either 1823 or 1832)
- contradiction (two sources disagreeing about authorship)
- proposed repairs coexisting with the original value
- full provenance for every piece of data

See [ADR 0001](./docs/adr/0001-assertion-based-ir.md) for the reasoning.

### The rule DSL

Transformation, validation, inference, normalization, and repair logic is
expressed as **EDN data**. A rule:

```clojure
{:id      :dc/title-required
 :phase   :validate
 :match   [[?r :meta/kind :book]
           (absent? ?r :canon/title)]
 :produce {:diagnostic {:severity :error
                        :code     :missing-title
                        :subject  ?r
                        :message  "Record has no title."}}}
```

Rules are data — inspectable, serializable, composable. The compiler
(`palomar.rules`) turns them into executable functions. Rules cannot call
arbitrary Clojure; they use a curated predicate stdlib.

See [ADR 0002](./docs/adr/0002-edn-as-dsl.md).

### The pipeline

A run is a sequence of phases:

```
ingest → normalize → validate → infer → repair
```

Each phase is a pure transformation `IR → IR + diagnostics`, run as a single
pass — every matching rule fires once; there is no multi-cycle iteration or
implicit fixpoint in V1. See
[ADR 0004](./docs/adr/0004-fixed-passes-over-fixpoint.md) (superseded by
[ADR 0020](./docs/adr/0020-single-pass-and-retire-project-phase.md)).
Projection to target formats is performed by exporters after the pipeline,
not as a pipeline phase.

### Vocabulary layering

The core ships only a **structural vocabulary** (`:meta/id`, `:meta/kind`,
`:meta/source`, `:meta/fragment`, `:meta/diagnostic`, `:meta/provenance`). It
knows nothing about documentary content.

A separate, optional plugin — `palomar.plugins.canonical` — provides a
**documentary vocabulary** (`:canon/title`, `:canon/uniform-title`,
`:canon/identifier`, `:canon/agent`, `:canon/date`, `:canon/relation`,
`:canon/note`, `:canon/digital-object`, `:canon/loss-marker`) that format plugins
can map toward for cross-source rules and projection. The set grows only by
justified need — `:canon/uniform-title` (the cataloguer's controlled work title)
was added for WEMI-derivation Work-key bridging.

This split keeps the core authentically agnostic while still enabling
commensurability when it is wanted. See
[ADR 0003](./docs/adr/0003-core-vs-canonical-vocabulary.md).

### Plugins

A plugin contributes some combination of:

- an **importer** (external format → canonical IR),
- an **exporter** (canonical IR → external format),
- **rule sets** (validation, normalization, projection),
- a **mapping** from its native predicates to the canonical vocabulary.

Plugins connect the outside world to the core. They never reach into the
core's internals; they interact only through the model API and the rule DSL.

### Diagnostics and repair

A validation failure is not an exception — it is a `Diagnostic` attached to
its subject, carried alongside the data through the pipeline. A diagnostic
may suggest zero or more `Repair` operations; these are produced in the
**`:repair` phase**. Repairs remain `:proposed` and are never applied
automatically in V1; the `apply-repairs` command surfaces them for human
acceptance or rejection. See
[ADR 0005](./docs/adr/0005-status-model.md) for the status model.

---

## Architecture at a glance

```
   ┌───────────────────────────────────────────────────────────────┐
   │  palomar.cli  ·  convert · validate · conformance · curate     │
   │       command-line + the conversion / loss-report assembly      │
   └────────────────────────────┬──────────────────────────────────┘
                                │
   ┌────────────────────────────▼──────────────────────────────────┐
   │                        palomar.runtime                         │
   │             pipeline · pass execution · diagnostics            │
   └────────┬───────────────────────────────────────┬──────────────┘
            │                                       │
  ┌─────────▼────────┐                     ┌────────▼─────────┐
  │  palomar.rules   │                     │  palomar.model   │
  │   DSL compiler   │                     │  IR · vocabulary │
  └─────────┬────────┘                     └────────▲─────────┘
            │                                       │
  ┌─────────▼───────────────────────────────────────┴─────────────┐
  │  palomar.plugins  —  spoke importers/exporters (MARC21 ·        │
  │  UNIMARC · INTERMARC · INTERMARC-NG · Dublin Core · MODS ·      │
  │  IIIF) and the derived, typed LRMoo pivot view + its            │
  │  serialisers (RDF · CIDOC-CRM · Linked Art · the floor formats) │
  └────────────────────────────────────────────────────────────────┘
```

Rules and plugins flow **into** the runtime. Data flows **out** through the IR.
The core never knows any external schema; the rich LRMoo view is a *derived*
plugin (ADR 0013), never the core.

---

## Repository structure

```
.
├── deps.edn                 # Clojure deps and aliases
├── src/palomar/
│   ├── model.clj            # Canonical IR (assertions, entities, fragments)
│   ├── rules.clj            # Rule DSL + compiler
│   ├── runtime.clj          # Pass-pipeline execution engine
│   ├── diagnostics.clj      # Diagnostic + loss API
│   ├── text.clj             # Shared normalisation for identity/clustering keys
│   ├── plugins.clj          # Plugin protocol + registry
│   ├── plugins/             # canonical · transforms · mapping · shape;
│   │                        #   spokes: marc21 · unimarc · intermarc[-ng] ·
│   │                        #   marcxml · dc · mods · iiif (+ *.export);
│   │                        #   lrmoo/ (pivot view · project · crm · linked-art ·
│   │                        #   export · crm-import) · intermarc/wemi
│   ├── spokes.clj           # Source-spoke registry
│   ├── convert.clj          # Conversion assembly (source → pivot → target + loss)
│   ├── validate.clj         # Validation gate
│   ├── conformance.clj      # Institutional-profile conformance (WP-6)
│   ├── curate.clj           # apply-repairs / curation engine (ADR 0005)
│   ├── reconcile.clj        # Cross-record agent reconciliation (ADR 0018)
│   ├── loss-report.clj      # Conversion loss report (ADR 0015)
│   └── cli.clj              # Command-line entry point (all verbs)
├── test/
│   ├── unit/palomar/        # Fast, hermetic, mirrored to src/ (+ eval/ measurements)
│   ├── property/palomar/    # Generative invariants (test.check + malli.generator)
│   ├── integration/palomar/ # Multi-layer end-to-end scenarios
│   └── junit/palomar/       # JUnit XML runner (CI)
├── docs/
│   ├── adr/                 # Architecture Decision Records (0001–0019)
│   ├── eval/                # Measured evals (WEMI derivation, BIB-R, scale, …)
│   ├── cleanup/             # Audit + remediation passes
│   └── roadmap-v1.md        # The work-package roadmap + Definition of Done
├── .github/workflows/ci.yml # Lint, format, test on every push
├── .clj-kondo/config.edn    # Linter config
├── .cljfmt.edn              # Formatter config
├── LICENSE                  # Apache License 2.0
└── NOTICE                   # Copyright line + third-party data licences
```

---

## Current status

Sprints 0 through 6 are landed — the **substrate**: assertion IR
(`palomar.model`), rule DSL (`palomar.rules`), runtime (`palomar.runtime`),
diagnostics (`palomar.diagnostics`), the plugin layer + mapping schema + shape
adapter (`palomar.plugins.*`), fragments for qualified values (ADR 0011,
ADR 0012), and the canonical vocabulary plugin (`palomar.plugins.canonical`).

**V1 has since been redefined** (2026) around a rich, **loss-aware** pivot
grounded in **LRMoo** (the object-oriented IFLA LRM, a CIDOC-CRM extension), for
production use across the full IIIF ↔ MARC family ↔ museum range. The Sprint 0–6
substrate is preserved as-is; the redefinition is an *extension*, not a rewrite.

Against the work-package plan ([`docs/roadmap-v1.md`](./docs/roadmap-v1.md)), as of
2026-06 the engineering is largely landed:

- **WP-0…WP-5, WP-8 done** — design lock + WEMI-derivation spike, substrate
  extensions, the LRMoo pivot + derived view, WEMI derivation (cross-record Work
  clustering by id-collision, plus uniform-title bridging), the loss-aware report,
  and the full CLI (`convert · validate · report · inspect · reconcile ·
  apply-repairs · conformance · formats`).
- **WP-4 spokes** — seven importers (the MARC family + INTERMARC-NG + DC/MODS/IIIF)
  reach the pivot and project to ten targets (RDF · CIDOC-CRM · Linked Art ·
  the floor formats); four floor round-trips; Linked Art validated against the
  official draft-2020-12 schema.
- **WP-6 conformance** — the mechanism + three profiles (Linked Art / IIIF / BnF
  INTERMARC); institutional *certification* on real samples is partnership-gated.
- **WP-7 scale** — streaming end-to-end in constant memory (a 97 MB flat MARC dump
  converts in a 256 MB heap); true millions-scale is data-gated.
- **WP-9 (hardening + release)** — in progress: XML input hardened against
  entity-expansion (`billion laughs`) and XXE (DTDs refused, `palomar.xml` +
  [`SECURITY.md`](./SECURITY.md)); a machine-readable loss report
  (`report --format edn`) for audit tooling; degenerate-input handling (a wrong
  `--from` warns instead of silently producing nothing). Remaining: further
  edge/golden coverage and the `v1.0.0` cut.

WEMI-derivation fidelity is measured, not asserted — on real BnF data and an
independent third-party benchmark; see [`docs/eval/`](./docs/eval/). The honest
remaining gates are real institutional acceptance criteria and a real at-scale
corpus ([`docs/roadmap-v1.md`](./docs/roadmap-v1.md) §7).

---

## Development

### Requirements

- Java 21+
- Clojure CLI (https://clojure.org/guides/install_clojure)
- clj-kondo and cljfmt (installed via `:lint` / `:fmt` aliases)

### CLI

```bash
# Convert a source document to a target serialisation through the LRMoo pivot.
# The converted document goes to stdout; the loss report (ADR 0015) to stderr.
clojure -M:run convert path/to/record.xml --from marc21 --to linked-art

# Stream a large flat MARC dump in bounded memory, writing to a file (WP-7).
clojure -M:run convert big.xml --from marc21 --to ntriples --stream --out out.nt

# Validate against the canonical rules; exits non-zero on failure (CI gate).
clojure -M:run validate path/to/record.xml --from marc21 --policy errors-and-warnings

# Check an institutional profile (WP-6); non-zero under the acceptance threshold.
clojure -M:run conformance path/to/record.xml --from intermarc --profile intermarc

# Other verbs: report (X→Y loss only) · inspect (the parsed floor + minted entities)
#   · reconcile (agents by authority id, ADR 0018) · apply-repairs (curate :proposed)
clojure -M:run report path/to/record.xml --from marc21 --to dc

# List the supported source and target formats
clojure -M:run formats
#  from: dc iiif intermarc intermarc-ng marc21 mods unimarc
#  to:   crm crm-only dc iiif jsonld linked-art marc21 mods ntriples turtle
```

(In a Clojars-restricted sandbox, prepend `:sandbox` — `clojure -M:sandbox:run …`.)

### Common tasks

```bash
# Run the full test suite (unit + property + integration)
clojure -M:test

# Run only one category — useful for fast iteration
clojure -M:test/unit
clojure -M:test/property
clojure -M:test/integration

# Lint
clojure -M:lint

# Format check
clojure -M:fmt

# Format fix
clojure -M:fmt/fix

# REPL with the test paths and test.check on the classpath
clojure -M:dev
```

CI runs the first three on every push and pull request.

### Restricted networks (Clojars unreachable)

Some environments — notably the Claude Code web sandbox — block Clojars but
allow Maven Central and GitHub. The `:sandbox` alias rewrites Clojars-hosted
dependencies (Malli and its transitive deps) to git coordinates so that local
testing works there. Chain it with `:test`:

```bash
clojure -M:sandbox:test
```

Normal local development and CI use the unaliased path. See
[ADR 0006](./docs/adr/0006-deps-resolution-and-sandbox.md).

### Continuous integration

Every push and pull request triggers `.github/workflows/ci.yml`:

- `clj-kondo --lint src test`
- `cljfmt check src test`
- `clojure -M:test`

Runs are parallelized across a Java matrix (Temurin 21 and 24). Concurrent
runs on the same branch (other than `main`) cancel each other to keep CI
turnaround tight. Dependabot tracks GitHub Actions versions weekly.

After the test matrix passes, two follow-up jobs run on a single Java
slice:

- **JUnit XML** — runs `clojure -M:test/junit`, uploads
  `target/junit/junit.xml` as an artifact, and uses
  `EnricoMi/publish-unit-test-result-action` to render the per-test
  results in GitHub's check panel. The publish step uses
  `if: always()` so failing runs still surface their breakdown.
- **Coverage** — runs `cloverage`, uploads the report to Codecov via
  `codecov/codecov-action`, and also publishes it as the workflow
  artifact `coverage-report` (HTML + cobertura XML) for offline
  inspection. A per-namespace text summary is mirrored to the run
  summary. The Codecov dashboard surfaces per-PR diff coverage and the
  status check; thresholds are configured in
  [`codecov.yml`](./codecov.yml) — currently `auto` for the project
  (no regression vs the base branch, with a 1% noise floor) and 80%
  for new code added in a PR.

Local equivalents:

```bash
clojure -M:test/junit  # produces target/junit/junit.xml
clojure -M:coverage    # produces target/coverage/index.html
```

Local coverage:

Note that cloverage is hosted on Clojars, so the `:sandbox` alias does
not proxy it. Local coverage runs require Clojars access; the JUnit
runner uses only Maven Central and works under sandbox.

A separate `release.yml` workflow runs on `v*` tag pushes: it re-executes
the full CI gate, then drafts a GitHub Release with auto-generated notes
that a maintainer publishes manually.

### Branch protection (manual setup)

The "tests must pass before merge" guarantee is enforced server-side, not
in YAML. After cloning the repository on GitHub, enable a branch
protection rule for `main` under **Settings → Branches → Add rule** with:

- *Require a pull request before merging* — yes
  - *Require approvals*: 1 (or more if the team grows)
  - *Dismiss stale approvals on new commits*
  - *Require review from Code Owners* (uses `.github/CODEOWNERS`)
- *Require status checks to pass before merging* — yes
  - Required checks: `Test, lint, format (Java 21)` and
    `Test, lint, format (Java 24)`
  - *Require branches to be up to date before merging*
- *Require conversation resolution before merging* — yes
- *Require linear history* — yes (rebase / squash only, no merge commits
  in `main` history)
- *Do not allow bypassing the above settings*

Force pushes and branch deletion on `main` should be disabled.

---

## Roadmap

**V1 was redefined in 2026** around a rich, **loss-aware** pivot grounded in
**LRMoo** (the object-oriented IFLA LRM, a CIDOC-CRM extension), for production
use at flagship institutions. The detailed plan lives in
[`docs/roadmap-v1.md`](./docs/roadmap-v1.md): dependency-ordered work packages
(WP-0…WP-9) over an honest ~18–24-month horizon. The decisions behind it are in
[`docs/wp0-decisions.md`](./docs/wp0-decisions.md); the architecture is fixed by
ADRs [0013](./docs/adr/0013-lrmoo-rich-pivot.md)–[0019](./docs/adr/0019-conversion-directionality.md).

The hub-and-spoke shape: spoke importers / exporters (MARC21 · UNIMARC ·
INTERMARC · **INTERMARC-NG** · Dublin Core · MODS · IIIF · CIDOC-CRM / Linked Art)
↔ the **assertion IR (ground truth)** ↔ a **derived, typed LRMoo view** (a plugin,
never the core). Every conversion emits a measurable loss report.

As of 2026-06, **seven source spokes reach the LRMoo pivot and project to ten
targets**: the full MARC family (MARC21 / INTERMARC / UNIMARC) plus the
INTERMARC-NG **entity-relation** spoke (graph→graph, no inference — ADR 0019) and
DC / MODS / IIIF; targets span RDF (N-Triples · Turtle · JSON-LD), CIDOC-CRM,
Linked Art (validated against the official draft-2020-12 schema) and the floor
formats. Directionality follows role — spokes are bidirectional, the hub is a
target, and CRM→LRM is a *downcast* (ADR 0019). See
[`docs/roadmap-v1.md`](./docs/roadmap-v1.md) for the live work-package state.

**Scope reversal.** The original plan (below) deliberately deferred IIIF,
CIDOC CRM, Linked Art, and **deduplication**. The redefinition pulls them in:
cross-record Work clustering (WEMI derivation) is core to the rich pivot, and the
museum / presentation formats are first-class spokes. The forward compatibility
the original IR reserved — the qualified-value design,
[ADR 0011](./docs/adr/0011-fragments-for-qualified-values.md) — is what makes
this an **extension, not a rewrite**: the Sprint 0–6 substrate stands.

Still out of V1 scope: LLM integration, a web UI, persistent storage, and a live
query engine.

<details>
<summary>Superseded original twelve-sprint plan</summary>

| # | Theme |
|---|---|
| 0 | Foundations |
| 1 | Canonical model (Malli schemas, EDN round-trip) |
| 2 | Rule DSL (schema, compiler, pattern matcher) |
| 3 | Runtime (rule execution, provenance merging) |
| 4 | Pass pipeline and diagnostics |
| 5 | Generic shape adapter (JSON + XML with mapping rules) |
| 6 | Canonical vocabulary plugin |
| 7 | Dublin Core plugin (XML/JSON in, JSON-LD out) |
| 8 | CSV institutional adapter + MARC-XML-lite subset |
| 9 | Repair phase and human workflow (`apply-repairs`) |
| 10 | CLI and UX |
| 11 | Hardening and performance baseline |
| 12 | Documentation and v1.0.0 release |

Sprints 0–6 landed and form the substrate; 7–12 are superseded by the
work-package plan above.
</details>

---

## Design principles

1. **Small core, clear boundaries.** The core knows structure, not content.
2. **Data over code.** Rules are EDN. Pipelines are EDN. Mappings are EDN.
3. **Explicit over implicit.** No fixpoint, no magical dispatch, no globals.
4. **Provenance is not optional.** Every assertion answers "where did you
   come from?"
5. **Diagnostics are data.** Validation failures are attached to subjects,
   not thrown as exceptions.
6. **Plugins connect, they do not replace.** The core can stand alone.
7. **No AI at the foundation.** Future assistance is a module; it never
   replaces rules or the IR.

---

## Contributing

The full contributor workflow lives in [CONTRIBUTING.md](./CONTRIBUTING.md):
branch naming, PR review checklist, ADR discipline, code-quality bar,
and the predicate-stdlib growth rule.

Suspected vulnerabilities go through [SECURITY.md](./SECURITY.md), not
public issues.

User-visible changes are tracked in [CHANGELOG.md](./CHANGELOG.md), in
the [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) format.

---

## License

Palomar is licensed under the **Apache License, Version 2.0** — see
[LICENSE](./LICENSE) for the full terms and [NOTICE](./NOTICE) for the
copyright line.

Third-party material redistributed under `test/fixtures/` keeps its own
licence; each fixture directory's `README.md` names its source and licence.

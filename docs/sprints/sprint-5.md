# Sprint 5 — Plugin protocol and generic shape adapter

- Theme: Plugin protocol implementation + generic JSON/XML shape adapter
- Sprint length: 2 weeks
- Status: Landed
- Architectural anchors: ADR 0007 (plugins as data), ADR 0009 (mapping
  schema), ADR 0010 (stdlib extensibility), ADR 0011 (fragments for
  qualified values), ADR 0012 (fragment identity scheme).

## Progress

| Step | Status | Commit |
|---|---|---|
| PW.1 — ADR 0009 §Qualifier reconciled with ADR 0011 | ✅ | `d71e0ca` |
| M1 — Fragment minting (`mint-fragment-id`, `parse-fragment-id`) | ✅ | `d71e0ca` |
| M2.A — Plugin schema, registry, queries, effective stdlib | ✅ | `d9808b2` |
| M2.B — `:requires` topo-sort + `:input-format` dispatch | ✅ | `3cfba92` |
| M3 — Core transform stdlib + core/plugin union | ✅ | `73d5bd0` |
| M4.A — MappingRule schema + flat compiler + transforms + on-empty | ✅ | `2ff3b42` |
| M4.B — Qualified-mapping compiler | ✅ | `5991c4f` |
| M4.C — Audit follow-up: schema tightening + doc hygiene | ✅ | `81416bd` |
| PW.2 — Verify and land XML/JSON deps under :sandbox | ✅ | `e298ad5` |
| M5.A — JSON walker + shared mapping inspection helpers | ✅ | `e58c5fe` |
| M5.B — XML walker + cross-format equivalence test | ✅ | `47caddf` |
| M6 — Reference plugin + integration | ✅ | _pending commit_ |

### Resuming work in a new session

Read this file first, then:

1. ADR 0011 §Limitation and §Decision — the qualified-mapping
   compilation strategy depends on the rule layer **not** minting
   fragments. The shape adapter (M5) mints them at ingest; M4's
   compiler emits ordinary assertions whose subjects happen to be
   fragment ids. This is the single most important design constraint
   for M4.
2. ADR 0012 — fragment id scheme and `palomar.model/mint-fragment-id`,
   already in code.
3. ADR 0009 §Decision, §Qualifier (revised), and §Schema (revised) —
   the mapping schema, the qualifier semantics M4 must implement, and
   the clarification that "compiled rule-DSL rules" means
   runtime-shaped compiled rules with bespoke runners, not data-form
   `Rule` maps. M4.A's design choice (Piste 2) lives there.
4. `src/palomar/plugins/mapping.clj` — namespace docstring covers the
   compiler shape and the M4.A/M4.B split. `palomar.rules/compiled-rule`
   is the small constructor used to bridge mapping-compilation output
   into the runtime's compiled-rule contract.
5. `src/palomar/plugins.clj` — namespace docstring explains the design
   choices made during M2 (registration is order-insensitive,
   `:rules` / `:mapping` are shallow-validated, etc.).
6. The git log on `claude/epic-wozniak-osQSK` for the sequence of
   decisions; commit messages cover the why of each landing.

## Goal

Make plugin authoring possible. After Sprint 5, an external author can
write a plugin map, register it, and have its importer produce records
that flow through the existing rule engine and pipeline. The generic
JSON/XML shape adapter is the first plugin to use this contract — it
also serves as the working reference implementation that Sprint 7
(Dublin Core) will build on.

## Pre-work

**PW.1 — Reconcile ADR 0009 §Qualifier with ADR 0011.**

The original qualifier mechanism in ADR 0009 produced a structured
value (`{:value/kind :structured, :value/fields {...}}`). ADR 0011
supersedes this with the fragment mechanism. ADR 0009 §Qualifier and
§Alternatives considered ("Qualifier as a separate sub-assertion …
Rejected") need updating; ADR 0009 status becomes "Accepted (partially
superseded by 0011 on qualifier mechanism)". Small diff, no code
impact. Must land before M4 or M5 starts, since both consume the
qualifier semantics.

## Modules

### M1 — Fragment minting (`palomar.model`)

Files: extend `src/palomar/model.clj`, extend
`test/unit/palomar/model_test.clj`.

Deliverables:

- `mint-fragment-id record-id locator` → fragment id keyword, per
  ADR 0012.
- `parse-fragment-id frag-id` → `{:record-id ... :locator ...}` (used
  by diagnostics and debugging; useful enough that round-trip testing
  pays for it).
- Tests: worked example, encoding cases (namespaced predicates,
  integer indices, multi-level locators), occurrence-index stability,
  rejection of bare keywords as predicate segments, mint/parse
  round-trip property.

Dependencies: none.
Estimate: ~50 LOC + ~150 LOC tests. Under one day.

### M2 — Plugin protocol and registry (`palomar.plugins`)

Files: replace the current `src/palomar/plugins.clj` stub with a full
implementation, create `test/unit/palomar/plugins_test.clj`.

Deliverables:

- Malli `Plugin` schema per ADR 0007 §Decision, closed shape.
- Registry as a pure map `{plugin-id → plugin-map}`.
- Operations: `register`, `lookup`, `plugins-for-format`,
  `importers-for`, `exporters-for`, `all-rules`, `all-mappings`,
  `effective-stdlib`, `requires-graph`.
- `:requires` topological resolution at register time, with explicit
  errors for cycles, missing requirements, and duplicate ids.
- `:input-format` dispatch with the "exactly one eligible" rule
  (ADR 0007 §Input-format dispatch).
- Tests: shape validation, register-twice rejection, missing
  requirements, cycle detection, dispatch with zero / one / multiple
  candidates.

Dependencies: none.
Estimate: ~250 LOC + ~300 LOC tests. About two days.

### M3 — Transform stdlib (`palomar.plugins.transforms`)

Files: create `src/palomar/plugins/transforms.clj`, create
`test/unit/palomar/plugins/transforms_test.clj`.

Deliverables:

- Core transforms per ADR 0009 §Transform: `:trim`, `:lowercase`,
  `:uppercase`, `:parse-int`, `:parse-double`, `:parse-iso-date`.
- Transform registry with the extension API per ADR 0010.
- Conflict rejection on cross-plugin name collision at registration.
- Tests: each transform's happy path and edge cases, composition
  (`[:trim :lowercase]`), nil handling, failure-mode contract
  (parse-* return nil on garbage, not exceptions).

Dependencies: M2 (extension mechanism lives in the registry).
Estimate: ~120 LOC + ~150 LOC tests. About one day.

### M4 — Mapping schema and compiler (`palomar.plugins.mapping`)

Files: create `src/palomar/plugins/mapping.clj`, create
`test/unit/palomar/plugins/mapping_test.clj`.

Deliverables:

- Malli `MappingRule` schema per ADR 0009 §Decision, with
  `:mapping/qualifier` semantics redirected to fragments per ADR 0011
  and PW.1.
- Compiler `mapping → rules`: pure function emitting one or more
  `:normalize`-phase rule-DSL rules per mapping rule.
  - **Flat mapping** compiles to one `:assert` rule that renames the
    predicate from `:mapping/from` to `:mapping/to`, applies the
    transform chain, and handles `:on-empty`.
  - **Qualified mapping** compiles to (a) one `:assert` rule that
    emits a reference assertion `(:mapping/to (ref ?frag))` on the
    record, and (b) optional `:assert` rules updating coord
    assertions on the fragment when transforms target them.
- Tests: schema validation, compiler purity, all four `:on-empty`
  branches, transform-chain composition, qualified-mapping
  compilation produces the expected reference-assertion rule.

Dependencies: M1 (used by the qualified-mapping compiler's emitted
rules at runtime, indirectly via fragment ids minted at ingest), M2
(transforms resolved through the effective stdlib), M3 (transform
functions).

Estimate: ~200 LOC + ~350 LOC tests. About two days. Down from the
initial ~300 LOC estimate because mapping rules never mint fragments
themselves — that work is M5's.

### M5 — Generic shape adapter (`palomar.plugins.shape`)

Files: create `src/palomar/plugins/shape.clj`, create
`test/unit/palomar/plugins/shape_test.clj`, create
`test/integration/palomar/shape_integration_test.clj`.

Deliverables:

- JSON walker. Confirm `clojure.data.json` or `cheshire` in
  `deps.edn`; if absent, add respecting the `:sandbox` alias
  (ADR 0006).
- XML walker. Confirm `clojure.data.xml`.
- Mapping-driven ingest: reads the plugin's `:mapping` to know which
  source paths yield fragments (those with `:mapping/qualifier`).
- Fragment minting at ingest time via `mint-fragment-id` (M1). The
  shape adapter is the only V1 producer of fragments.
- Document-order preservation across both formats: occurrence indices
  must be stable across runs of the same source. This is by
  construction for both `clojure.data.xml` (SAX-ordered) and
  `clojure.data.json` (parser preserves array order).
- Cross-format equivalence: a logically identical record in XML and
  JSON produces identical fragment ids. This is the property that
  validates the format-agnostic locator design from ADR 0012.
- Tests: XML→record, JSON→record, cross-format equivalence property
  test.

Dependencies: M1 (mint-fragment-id), M4 (consumes mapping schema for
ingest config).

Estimate: ~350 LOC + ~400 LOC tests. About three days.

### M6 — Reference plugin and integration (`palomar.plugins.shape`)

Files: extend `src/palomar/plugins/shape.clj` with the plugin map
itself, extend
`test/integration/palomar/shape_integration_test.clj`.

Deliverables:

- `:plugin/shape-xml` and `:plugin/shape-json` plugin maps wrapping
  the shape adapter as `:importer` with the appropriate
  `:input-format`.
- End-to-end integration test: ingest a fixture XML record and an
  equivalent JSON record through the plugin registry; assert the
  resulting records are identical modulo serialization metadata.
- Confirms the M2 dispatch logic works for a real registered plugin.

Dependencies: M2 (registry), M5 (the shape adapter logic).
Estimate: ~50 LOC + ~250 LOC integration tests. About one day.

## Out of scope (explicit)

- Dublin Core plugin proper — Sprint 7. The shape adapter is generic;
  DC will register its own plugin that consumes the shape adapter as
  a building block.
- Canonical vocabulary plugin proper — Sprint 6. The shape adapter
  does not depend on canonical predicates; it maps source predicates
  to whatever target the mapping declares.
- Exporter implementations. The `:exporter` slot in the plugin schema
  is validated by M2, but no exporter ships this sprint.
- Streaming optimization. The reducible-records contract is honored
  by M5, but no SAX-style XML or lazy JSON in V1.
- Rules that mint fragments at `:normalize`, `:infer`, or `:repair`.
  Documented limitation in ADR 0011 §Consequences. Ingest-time minting
  is the only V1 path.
- Repair-time mapping reversal — ADR 0009 §Open V2.

## Sequencing

```
PW.1
 │
 ▼
M1, M2, M3  (parallel — no inter-deps among the three)
 │
 ▼
M4
 │
 ▼
M5
 │
 ▼
M6
```

Critical path: PW.1 → M4 → M5 → M6. Total estimate on the critical
path is about eight days; M1/M2/M3 fit alongside without extending it.

## Risks

1. **Dependencies for XML/JSON.** *Resolved by PW.2.* Both
   `org.clojure/data.xml` and `org.clojure/data.json` live on Maven
   Central, not Clojars, so the `:sandbox` alias (ADR 0006) needs no
   amendment — they resolve in both standard and sandbox modes. Deps
   landed in PW.2 before M5.A consumes them.
2. **Cross-format equivalence definition.** *Resolved by M5.B.* The
   pair fixture (one logical record, two serializations) lives in
   `palomar.plugins.shape-test/cross-format-record-pairs`, and the
   equivalence claim is split into four assertions: fragment ids
   match, record references match, fragment value coords match, and
   qualifier values match (under format-specific predicate names —
   M4 :normalize canonicalizes both). Real XML parsing produces
   URI-encoded namespaces; the walker is format-neutral on identity
   so plugin-layer tag rewriting (M6) is what aligns parsed XML with
   JSON's clean predicate form.
3. **M4 and M5 volume.** Estimates are conservative but tight. If M4
   slips, splitting flat-mapping and qualified-mapping compilers into
   two PRs is a clean fallback. If M5 slips, deferring M6 to a
   follow-up week is acceptable — the reference plugin is wiring
   work, not architecture.
4. **Mapping-driven ingest coupling.** M5 reads `:mapping/qualifier`
   from the plugin's mapping to decide what becomes a fragment.
   That introduces a tighter coupling between the shape adapter and
   the mapping schema than ADR 0009 originally implied. If the
   coupling becomes painful, an alternative is a separate
   `:shape/qualifier-paths` declaration in the plugin map — but the
   simpler path is to share `:mapping/qualifier` until we see a
   plugin that wants different ingest-vs-normalize qualifier
   semantics.

## Definition of done

- All modules ship with unit tests covering the listed cases.
- The integration test in M6 passes for both XML and JSON inputs.
- CI green: lint, format, all three test categories (unit, property,
  integration), and the `:sandbox` test job.
- README §Current status updated to list Sprint 5 as landed; §Roadmap
  marker advances to Sprint 6.
- No new lint warnings introduced.
- ADR cross-references kept consistent: PW.1 lands, and any other
  drift detected during implementation surfaces as an ADR amendment,
  not a silent code-doc divergence.

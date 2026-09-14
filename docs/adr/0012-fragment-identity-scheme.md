# 0012 — Fragment identity scheme

- Status: Accepted
- Date: 2026-05-27
- Revised: 2026-05-30 — recorded that `mint-fragment-id` now *enforces* the
  encoding's injectivity preconditions (rejects a `-` in a predicate
  namespace and a `.` in any segment) rather than leaving them implicit;
  see §Consequences (post-Sprint-5 audit cleanup).

## Context

ADR 0011 settled that qualified values are represented as fragments and
committed to specifying a fragment-identity scheme before any plugin
starts minting fragments. Sprint 5 (generic JSON/XML shape adapter) is
the first consumer: it walks heterogeneous source trees and must produce
stable fragment ids whenever it encounters a structure that maps onto a
fragment.

The scheme must guarantee three properties from ADR 0011:

- **Reproducible** — same input, same id, across runs. Idempotency at
  merge (ADR 0008) depends on this.
- **Distinct** — two distinct source occurrences get distinct ids, even
  if their contents are byte-identical.
- **Cheap** — no database, no UUID generation, no global state.

A fourth property emerges from operational use: **inspectable**. An
engineer reading a diagnostic that mentions a fragment id should be
able to trace it back to the source location without consulting a
side table.

## Decision

A fragment id is a keyword in the `:frag` namespace whose name encodes
the **owning record id** and a **locator** path from that record to the
fragment:

```
:frag/<record-ns>.<record-name>.<seg1>.<seg2>...
```

A **locator** is a vector that alternates **predicate keywords** with
**integer occurrence indices**:

```clojure
[:dc/title 0]                 ;; first DC title
[:dc/title 1]                 ;; second DC title
[:crm/P108 0 :crm/P14 0]      ;; first actor of first production event
```

Predicate segments must be **namespaced keywords** (already required by
ADR 0001). Each segment is encoded into the id's name:

- A namespaced keyword `:ns/name` becomes `"ns-name"`.
- An integer becomes its decimal string.

Concatenation uses `.` as separator.

### Worked example

A DC record with two titles in different languages:

```xml
<record id="r42">
  <dc:title xml:lang="fr">Les Misérables</dc:title>
  <dc:title xml:lang="en">The Wretched</dc:title>
</record>
```

Owning record id: `:record/r42`. Locators: `[:dc/title 0]` and
`[:dc/title 1]`. Resulting fragment ids:

```
:frag/record.r42.dc-title.0
:frag/record.r42.dc-title.1
```

### Minting helper

`palomar.model` exposes a single sanctioned constructor:

```clojure
(mint-fragment-id record-id locator) ; → :frag/...
```

Plugins do not roll their own. Centralizing the encoding in one helper
means that future scheme evolution (e.g. a hash segment, see below)
changes one function, not every plugin.

### Occurrence indices

The occurrence index is assigned by the shape adapter (and by every
importer) in **document order** within the owning record. V1 importers
(JSON, XML, CSV, MARC-XML) all preserve document order by construction,
so indices are reproducible without extra machinery.

### When a hash would be needed

ADR 0011 mentioned a content hash as a tie-breaker. V1 importers do not
need it: every supported format preserves document order, so the
occurrence index alone distinguishes duplicates. The hash slot is
**not part of the V1 scheme**. If a future plugin demonstrates it
cannot assign stable indices (e.g. an importer reading from a
triplestore whose serializer reorders triples between runs), the scheme
will be extended with an optional final hash segment at that point.
Shipping the hash slot now would be dead code that nothing in V1
exercises.

## Alternatives considered

- **Content-hash-only ids** (`:frag/<hash(predicate, value)>`).
  Reproducible and order-independent. Rejected: two genuinely distinct
  occurrences of the same value within one record collapse into the
  same id, which ADR 0011 explicitly forbids.
- **UUIDs or random ids.** Rejected: not reproducible without an
  external mapping table, which would break idempotency at merge
  (ADR 0008) and require state outside the IR.
- **Format-native paths** (XPath for XML, JSON Pointer for JSON).
  Rejected: ties fragment ids to the source serialization. Re-importing
  the same logical record from a different serialization (XML ↔ JSON,
  same data) would change every fragment id, defeating cross-format
  equivalence.
- **Map-shaped ids** (`{:record :record/r42, :locator [...]}`).
  Rejected: the `Fragment :id` schema in `palomar.model` is a keyword,
  matching every other `Id` in the model. Changing it would propagate
  through assertions, diagnostics, and provenance for no concrete gain
  over keyword encoding. Inspectability is achieved by the encoding
  scheme; structured access is achieved by a parser helper if ever
  needed (`parse-fragment-id` is straightforward but deferred until a
  caller asks for it).

## Consequences

- The shape adapter (Sprint 5) has a complete, locked spec for minting
  fragment ids. No provisional scheme, no rework when Sprint 6/7 start
  consuming it.
- ADR 0011's Sprint-6 promise is met early. ADR 0011 §Consequences is
  updated to point here.
- Plugins remain format-agnostic at the id level. An XML and a JSON
  serialization of the same DC record produce identical fragment ids
  — a property that supports cross-format equivalence tests in CI.
- Fragment ids are human-inspectable. A diagnostic mentioning
  `:frag/record.r42.dc-title.1` tells the reader which record and which
  source element are at fault, without a side lookup.
- The "two genuinely identical occurrences in the same record"
  pathology is handled by the occurrence index, not by hashing. No
  collision risk under V1 importers.
- A future plugin that cannot preserve document order has a documented
  escape hatch (optional hash segment), deferred until it has a name
  and a concrete failure mode.
- **The encoding's injectivity preconditions are enforced, not assumed.**
  `-` separates a predicate's namespace from its name and `.` separates
  path segments, so a `-` inside a predicate namespace, or a `.` inside any
  record-id or predicate segment, would let two distinct predicates collapse
  onto one id. `mint-fragment-id` rejects both at construction (predicate
  *names* may still contain hyphens — they round-trip, since the decoder
  splits on the first hyphen). A minted id therefore always parses back
  exactly; only a hand-built `:frag` keyword can violate the scheme.
- Encoding lives in one function. `palomar.model/mint-fragment-id` is
  the only thing that knows the on-the-wire format; everything else
  treats fragment ids as opaque keywords.

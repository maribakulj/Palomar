# 0007 — Plugins as data, not protocols

- Status: Accepted
- Date: 2026-04-25
- Revised: 2026-04-25 — original draft was a stub on five questions
  (source contract, streaming, requires resolution, input-format
  dispatch, spec versioning); this revision spells them out and adds
  cross-references to ADR 0009 (mapping schema) and ADR 0010 (stdlib
  extensibility).

## Context

The plugin layer is the boundary between Palomar and the outside world.
Every external metadata standard (Dublin Core, MARC, CSV, IIIF, …) reaches
the runtime through a plugin. The runtime itself never knows about any
specific format.

Per the README, a plugin contributes some combination of:

- an **importer** (external source → IR records),
- an **exporter** (IR records → external output),
- **rule sets** (validation, normalization, inference, projection),
- a **mapping** from native predicates to canonical (see ADR 0009),
- optional **predicate / transform extensions** to the rule stdlib (see
  ADR 0010).

The first two are unavoidably *code*: there is no realistic EDN-only
XML parser or CSV reader. The latter three are pure *data* — already
governed by the rule schema (ADR 0002) and the mapping schema
(ADR 0009).

So the architectural question is: how is a plugin **represented** in
Palomar? What is the shape of `palomar.plugins`?

Three families of answer were on the table.

## Decision

A plugin is a **plain Clojure map**. Registration is explicit. Dispatch
is by data lookup, not by polymorphism.

```clojure
{;; --- identity (required) ---
 :plugin/spec-version 1
 :id                  :plugin/dublin-core
 :version             "0.1.0"

 ;; --- code surface (optional) ---
 :importer    (fn [opts source]  ...)   ; -> {:records reducible :diagnostics [...]}
 :exporter    (fn [opts records] ...)   ; -> {:output Any         :diagnostics [...]}
 :matches?    (fn [opts source]  ...)   ; -> boolean (sniff for input-format dispatch)

 ;; --- data surface (optional) ---
 :rules       [...]   ; rule-DSL rules — schema in palomar.rules (ADR 0002)
 :mapping     [...]   ; mapping rules    — schema in ADR 0009
 :predicates  {...}   ; sym -> fn        — stdlib extension (ADR 0010)
 :transforms  {...}   ; kw  -> fn        — stdlib extension (ADR 0010)

 ;; --- declarations (optional) ---
 :requires       #{:plugin/canonical}
 :input-format   :xml
 :output-format  :json-ld}
```

The registry is itself a map `{plugin-id → plugin-map}`. Operations on
it are pure functions: `register`, `lookup`, `plugins-for-format`,
`importers-for`, `exporters-for`, `all-rules`, `all-mappings`,
`effective-stdlib`.

The plugin map shape is validated by a Malli schema at register time.
The `:importer` / `:exporter` / `:matches?` functions are validated
only structurally (callable, arity check) — Clojure cannot
meaningfully introspect closures, and that's accepted.

### Required spec version

`:plugin/spec-version` is **required**. Plugins authored against
version `1` carry the literal `1`. Future minor revisions of the
plugin shape are additive (new optional keys); the major version bumps
when an existing key changes meaning. Adding the field now costs
nothing; not having it would force every existing plugin to be
rewritten when we eventually break the shape.

### Source contract

`source` (the second argument to `:importer` and `:matches?`) is a
**tagged map**:

```clojure
{:source/kind  :file              ; one of :file :string :bytes :stream :resource :url
 :source/value <kind-specific>    ; path / string / byte-array / InputStream / classpath / URL
 :source/encoding "UTF-8"}        ; optional, plugin-defined defaults
```

Importers may declare which `:source/kind`s they accept; the dispatcher
filters before invocation. A plugin that opens a `:file` is responsible
for closing it. Plugins that need streaming choose `:stream`.

### Records contract: reducible, not necessarily a vector

Importers return `:records` as a **reducible value** — anything that
satisfies `clojure.core.protocols/CollReduce`. A vector, a lazy seq, an
`eduction`, or a custom reducible source all qualify.

Callers consume via `reduce` / `transduce` / `into` and never assume
size, count, or random access. This keeps the door open for
streaming-by-default in V2 without forcing every importer to commit
upfront. V1 importers that load everything into memory and return a
vector are conformant; an XML SAX-style importer that yields records
as it parses is also conformant.

### `:requires` resolution

Plugins may declare `:requires #{:other-plugin/id ...}`. Resolution is
performed at **register time**:

- Topological sort of the registry. Cycles fail with `ex-info` carrying
  the cycle.
- Missing requirements fail with `ex-info` carrying the missing ids.
- Same-id duplicates fail with `ex-info`.

Effects on visibility: for V1 the resolution is documentary only.
Stdlib extensions (predicates / transforms) from registered plugins are
pooled into a single namespace; isolation by `:requires` is V2 work and
explicitly out of scope here. Documented as a consequence below.

### `:input-format` dispatch

Plugins declare `:input-format` (keyword). Multiple plugins may share a
format keyword — XML generic, Dublin Core, MARC-XML are all `:xml`.
Resolution at import time follows this order:

1. If the caller passed `:using-plugin :plugin/foo`, use that plugin
   directly. Explicit selection always wins.
2. Otherwise, gather the plugins whose `:input-format` matches the
   caller's declared format keyword.
3. If a plugin in that set declares `:matches?`, invoke it with
   `[opts source]`. Plugins without `:matches?` are eligible candidates
   without sniffing.
4. **Exactly one** plugin must remain eligible. Zero or two-plus
   eligible plugins is a hard error: `ex-info` listing candidates.
   Silent first-wins resolution is rejected.

This means a deployment that loads both Dublin Core and the generic XML
shape adapter must either select explicitly via `:using-plugin` or
ensure the more specific plugin's `:matches?` returns true and the
generic one's returns false. Documented as a deployment concern.

## Alternatives considered

- **`defprotocol Importer / Exporter`** with `extend-type` per format.
  Idiomatic Java-ish OO. Rejected: it puts dispatch in the type system
  rather than in data, which is the opposite of every other layer of
  Palomar (rules are data, pipelines are data, mappings are data —
  ADR 0002). A protocol also obscures *what a plugin contributes*
  behind two function names; a map makes the contributions inspectable
  at a glance.
- **`defmulti` keyed on format keyword.** Less rigid than a protocol
  but still spreads a plugin's behavior across `defmethod` calls in
  many namespaces, hard to enumerate. We want to ask the registry
  "what can this plugin do?" and get back a map, not run a search.
- **Macro-based `defplugin`.** Rejected: macros generate code, plugins
  authored by us would no longer be straightforward Clojure data, and
  serialization / inspection / hot reload all become harder. The DSL
  decision (ADR 0002) explicitly rejects macros for the same reason.
- **Classpath scanning / auto-discovery** (e.g. `META-INF/services` or
  a `palomar.plugins` namespace convention). Rejected for V1: the cost
  of silent registration (mystery rules firing because a transitive
  dep was pulled in) outweighs the convenience. Plugins are loaded by
  explicit `require` + `register` calls in user code or an assembly
  namespace (e.g. `palomar.convert` / `palomar.validate`).
- **Eager record materialization in the importer contract** (return a
  `[Record ...]` vector). Rejected: forces every importer to fit the
  full input in memory and silently locks the V1 API away from
  streaming. Reducible is the same Clojure idiom, costs nothing for
  vector-using importers, and frees future ones.
- **Untagged `source` (just a path string)**. Rejected: collapses
  five distinct input modes into one argument and forces each plugin
  to reinvent format sniffing. The tagged map is two extra keys and
  removes a class of bugs.

## Consequences

- A plugin is inspectable, serializable for the parts that are data,
  and callable for the parts that aren't. Users can `pprint` a plugin
  and see exactly what they're getting.
- Registration is a pure function on a registry value; multiple
  registries can coexist (test fixtures, isolated runs).
- Plugins compose by union: loading two plugins concatenates their
  rules and mappings. Conflicts (two plugins claiming the same `:id`)
  are rejected at register time.
- The plugin schema is small (Sprint 5 deliverable): `:plugin/spec-version`
  and `:id` required, every other key optional. Growth discipline
  matches ADR 0002 — new optional keys must justify themselves
  through concrete plugin needs.
- Importer / exporter signatures cannot reach into core internals;
  they receive `opts` and `source`/`records`, return records / output.
  The core / plugin boundary remains thin.
- `:input-format` dispatch is explicit: zero or many candidates fail
  loudly. A deployment loading two plugins with the same
  `:input-format` and no `:matches?` discriminator must select one
  explicitly. This eliminates "wrong plugin silently won" bugs.
- Stdlib extensions from all registered plugins pool into a single
  namespace. A plugin's extensions are visible to every other plugin's
  rules. Per-plugin isolation is V2 work; the cost (cross-plugin name
  collisions) is mitigated by the same conflict-rejection rule.
- IDE navigation: a plain map gives Cursive / CIDER no way to jump
  from "this plugin" to "the imports it provides". Malli compensates
  at register time but not in the editor. Accepted cost — the
  data-orientation of ADR 0002 has the same trade-off everywhere.
- Reducible records keep the door open for streaming without
  retro-fitting plugin signatures.
- A future "AI-assisted authoring" feature gets the same data surface
  as humans: a plugin is a Clojure map.

## What this ADR does not decide

- The shape of mapping rules (`:mapping`). See **ADR 0009**.
- How plugins extend the predicate / transform stdlib safely. See
  **ADR 0010**.
- Per-plugin isolation of stdlib extensions (V2).
- Hot reloading and live registry mutation (V2).
- Multi-version plugin coexistence (V2).

# 0010 — Stdlib extensibility: predicates and transforms via plugins

- Status: Accepted
- Date: 2026-04-25

## Context

The rule DSL (ADR 0002) deliberately ships a closed predicate stdlib —
six entries: `=`, `not=`, `absent?`, `present?`, `matches?`, `in?`.
The mapping schema (ADR 0009) introduces a similarly closed transform
stdlib — `:trim`, `:lowercase`, `:parse-iso-date`, etc.

Both stdlibs are too small to support real format plugins. Sprint 7
(Dublin Core) needs at least:

- `iso-8601?` — predicate, validates a date string.
- `string-length-between?` — predicate.
- `lang-code?` — predicate, checks ISO 639 codes.
- `:normalize-whitespace` — transform.
- `:dc-date->iso8601` — transform.
- ... and roughly five to ten more.

The current architecture has no extension path. `predicate-stdlib` is
a `def` map in `palomar.rules`; adding `iso-8601?` requires a core PR.
ADR 0002 explicitly flags this as the chief risk to the project ("la
croissance de la stdlib est la principale façon dont le DSL pourrait
déraper") but proposed no mechanism. This ADR provides one.

The constraint that bites: any plugin can add predicates a rule
author then references by symbol. If two plugins ship `iso-8601?`
with different semantics, every rule consumer would silently get
whichever plugin loaded last. That is the failure mode this ADR must
prevent.

## Decision

Plugins extend the predicate and transform stdlibs through two keys
in the plugin map (per ADR 0007):

```clojure
{:predicates {sym -> fn}    ; rule-DSL guard predicates
 :transforms {kw  -> fn}}   ; mapping value transforms
```

### Effective stdlib at register time

For a registry of plugins, the **effective predicate stdlib** is the
union of:

1. The core stdlib (`palomar.rules/predicate-stdlib`).
2. Every registered plugin's `:predicates`.

The **effective transform stdlib** is the analogous union over
`:transforms` (core + plugins).

Compiled rules and compiled mappings resolve symbols / keywords
against the effective stdlib at the moment of compilation. Rules
compiled before a plugin registers do not see that plugin's
extensions; rules compiled after do.

### Conflict rejection

Two registered contributions to the same predicate symbol or
transform keyword **fail at register time** with `ex-info`:

```
Conflicting :predicates entry: 'iso-8601?
  declared by plugins: [:plugin/dublin-core :plugin/marc]
```

There is no "last wins" or "first wins" silent resolution. If two
plugins must coexist, one of them must rename its contribution.
Recommended convention: namespace plugin-contributed symbols (`'dc/iso-8601?`).
The convention is not enforced by the runtime — but convention works
because the rule DSL allows arbitrary qualified symbols as predicate
names.

### Predicate function shape

Plugin predicates use the same signature as core ones:

```
(fn [bindings record & resolved-args] -> boolean)
```

`bindings` is the map of currently-bound variables, `record` is the
matched record, `resolved-args` is the rest of the guard form with
variables resolved. The function returns truthy or falsy. It must be
pure.

### Transform function shape

```
(fn [value] -> value-or-nil)
```

Transforms are pure single-argument functions. Returning `nil`
signals "couldn't transform"; the surrounding mapping decides what
that means (skip, diagnose, or use default — per ADR 0009 `:on-empty`
is reused, with semantics extended to "transform-yielded-nil").

### Discoverability

The runtime exposes three helpers (Sprint 5 deliverable):

- `effective-predicates registry` → map of every available predicate.
- `effective-transforms registry` → map of every available transform.
- `predicate-source registry sym` → the plugin id that contributed
  the predicate (or `:core`).

This lets users answer "where does `iso-8601?` come from?" without
reading source.

### Trust model

Plugins are loaded by explicit `require` plus explicit `register`
calls (ADR 0007). The rule engine never sandboxes plugin functions:
if you register a plugin, you trust it. A malicious plugin's
predicate could read filesystem, mutate global state, or throw —
exactly the same risk surface as `require`-ing arbitrary Clojure
code. We accept this. Documented as a deployment concern, not an
engineering one.

## Alternatives considered

- **Keep stdlib closed, every extension is a core PR.** Rejected:
  pushes Dublin Core, MARC and CSV plugin work into the core
  release cycle. The author of a CIDOC CRM plugin would need a core
  fix to ship `:cidoc/has-class?`. Drives the worst version of
  ADR 0002's failure mode.
- **Open globals: `swap! predicate-stdlib assoc 'iso-8601? ...`.**
  Rejected: silent install, last-wins on conflicts, no register-time
  validation. Exactly the failure mode this ADR exists to prevent.
- **Sandboxing plugin functions** (eval in a restricted context, no
  classpath access). Rejected for V1: complexity well beyond what a
  trust-on-require model gives. Sandboxing is an explicit V2 question
  if and when Palomar loads untrusted plugins (e.g. from a registry).
- **Late binding: rules carry plugin-id qualifiers, predicates
  resolved per rule's owning plugin.** Per-plugin isolation. Rejected
  for V1 as more complex than necessary; conflict-at-register-time is
  a sufficient first-line defense and lets rules use bare symbols.
  Marked as a V2 candidate in ADR 0007's "open V2 questions".
- **Conflicts resolved by `:requires` priority.** Plugin A `:requires`
  B, A's contribution wins. Rejected: brittle ordering semantics,
  surprises rule authors. Hard fail is more defensible.

## Consequences

- The growth pressure that ADR 0002 named explicitly now has a
  controlled valve: plugins extend the stdlib, the core does not bloat
  per-format, and conflicts surface at register time rather than at
  rule-compile time or — worst — at runtime in production.
- Naming hygiene becomes a soft convention: namespace your symbols.
  The runtime enforces only conflict rejection, not naming. This
  follows ADR 0002's general posture of trust + closed-set discipline.
- The effective stdlib is a register-time computation, so plugin load
  order does not matter for correctness — same registered set
  produces the same effective stdlib regardless of order. Rule
  compilation order *does* matter (a rule using `iso-8601?` compiled
  before the plugin registers fails compilation), but that's the
  same as ADR 0007's "rules from a plugin register together with
  the plugin".
- Per-plugin isolation is deferred to V2. The cost is real: one
  plugin's `iso-8601?` is callable from another's rules. Mitigated
  by the conflict rule and by the namespacing convention.
- Predicates and transforms remain plain Clojure functions. They are
  not data; they cannot be serialized; they cannot be shared between
  runtimes. This is the same trade-off ADR 0007 made for importer /
  exporter functions.
- Discoverability through `effective-predicates` and
  `predicate-source` removes a large class of "where does this come
  from?" support questions before they happen.
- Test plugins (fixtures that contribute one or two predicates for a
  test suite) become natural: a plugin map with only `:id`,
  `:plugin/spec-version`, and `:predicates` is conformant.

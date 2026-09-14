# 0009 — Mapping schema: data-shaped sugar over rules

- Status: Accepted (partially superseded by ADR 0011 on qualifier mechanism)
- Date: 2026-04-25
- Revised: 2026-05-27 — §Qualifier rewritten to use fragments per
  ADR 0011; §Alternatives considered amended to record that the
  "Qualifier as separate sub-assertion" option, originally rejected
  here, was reopened and adopted by ADR 0011 once concrete plugin needs
  (Sprint 7 DC multilingual, CIDOC/Linked Art/IIIF architectural
  compatibility) emerged.
- Revised: 2026-05-27 — §Schema clarified to spell out what "compiled
  rule-DSL rules" means in the compiler's output: runtime-shaped
  compiled rules, not necessarily `Rule` data-form maps. §Consequences
  rule-id example corrected to a syntactically valid Clojure keyword.
- Revised: 2026-05-27 — §Open V2 questions extended with
  source-provenance carry-forward at `:normalize` and cross-plugin
  `:mapping/id` uniqueness, both surfaced by the M4 audit follow-up.
- Revised: 2026-05-30 — §Open V2 "cross-plugin `:mapping/id` uniqueness"
  resolved in code: `compile-mappings` rejects a batch whose rules derive
  the same compiled rule id (post-Sprint-5 audit cleanup).
- Revised: 2026-06-04 — the §Alternatives "composite mappings" rejection
  reopened and adopted as the bounded `:mapping/combine` form (a vector
  `:mapping/from` joined by a separator), replacing the bespoke
  `intermarc/with-agent-name` subfield-recombine pre-pass.

## Context

ADR 0007 (plugins as data) declared `:mapping` to be EDN data and
deferred the schema. That deferral is the largest open question
between Sprint 5 (plugin protocol) and Sprint 7 (Dublin Core plugin):
without a mapping schema, every plugin author invents one, and the
runtime cannot validate or compose them.

A mapping is the bridge between a native vocabulary and the canonical
vocabulary (ADR 0003): "the source predicate `:dc/title` corresponds to
the canonical predicate `:canon/title`, with these transformations".
It is *not* the rule layer (validation, inference, normalization);
mappings only describe the shape of the bridge.

Cultural metadata mappings exhibit four recurring features that the
schema must natively support, or else every plugin will route around
the schema:

1. **Multiplicity.** A source record commonly carries multiple
   instances of the same predicate (three creators, two titles).
2. **Language tagging.** Titles, abstracts and notes routinely carry
   `xml:lang`. A canonical predicate must be able to remember the
   language of each value, not silently drop it.
3. **Value transformations.** Dates need ISO-8601 normalization.
   Strings need trimming. Identifiers need to be split or merged.
   These are common, small, and not worth re-implementing per plugin.
4. **Default-on-empty / required-on-empty.** Some sources omit a value
   the canonical layer expects; the mapping must declare what to do
   (skip, default, diagnose).

A fifth concern was on the table — composite mappings ("`:canon/agent`
is built from `:dc/creator` plus `:dc/role`"). This is rejected from
the V1 mapping schema because it is well within reach of the rule DSL:
write a normalize-phase rule that consumes both predicates and asserts
the canonical agent. Mappings stay one-source-predicate-to-one-
canonical-predicate; the rule layer handles composition.

## Decision

A **mapping** is a vector of mapping-rule maps. Each mapping rule has
this shape:

```clojure
{:mapping/id          :map/dc-title       ; required, keyword, unique within plugin
 :mapping/from        :dc/title           ; required, native predicate
 :mapping/to          :canon/title        ; required, canonical predicate
 :mapping/transform   [:trim]             ; optional, ordered vector of transform names
 :mapping/qualifier   {:from :xml/lang    ; optional, value qualifier extraction
                       :as   :language}
 :mapping/on-empty    :skip               ; optional, :skip (default) | :diagnostic | :default
 :mapping/default     nil                 ; required iff :on-empty is :default
 :mapping/confidence  1.0                 ; optional, default 1.0
 :mapping/doc         "..."}              ; optional
```

A mapping is **data-shaped sugar over rules**. At plugin register time,
the runtime expands every mapping rule into one or more compiled
rule-DSL rules in the `:normalize` phase. The expansion is a pure
function `mapping → rules`; mappings are not a separate execution
substrate.

### Multiplicity

Multiplicity is **implicit and natural**: each native value of
`:mapping/from` produces one canonical assertion of `:mapping/to`.
A source with three `:dc/title` values produces three `:canon/title`
assertions. Collapsing, deduplication, or "first wins" are
*normalization rules*, not mapping concerns.

### Qualifier

When `:mapping/qualifier` is present, the source value is treated as
**qualified** and lifted to a **fragment** per ADR 0011. The importer
(typically the generic shape adapter) mints a fragment id via
`palomar.model/mint-fragment-id` (ADR 0012), attaches one assertion
per coordinate to the fragment id (the value and the qualifier), and
emits a reference assertion on the record:

```clojure
;; one source <dc:title xml:lang="fr">Les Misérables</dc:title>
;; under the mapping rule
;;   {:mapping/from :dc/title
;;    :mapping/to   :canon/title
;;    :mapping/qualifier {:from :xml/lang :as :canon/lang}}

(fragment {:id :frag/record.r42.dc-title.0 :source [:xml :dc:title 0]})

(assertion {:subject   :frag/record.r42.dc-title.0
            :predicate :canon/title
            :value     "Les Misérables"})

(assertion {:subject   :frag/record.r42.dc-title.0
            :predicate :canon/lang
            :value     "fr"})

(assertion {:subject   :record/r42
            :predicate :canon/title
            :value     {:value/kind   :reference
                        :value/target :frag/record.r42.dc-title.0}})
```

The `:from` key names the source attribute or sub-field that carries
the qualifier; the `:as` key names the predicate the qualifier coord
takes on the fragment.

Plugins that don't use qualifiers omit the key entirely; the canonical
value is the bare primitive on the record, no fragment minted.

This applies to **mapping-driven** qualifiers (the typical case). For
atomic compound values where neither coordinate would ever sensibly
become the subject of further assertions on its own (monetary amounts,
geographic coordinates, date ranges), use `:value/kind :structured`
directly — see ADR 0011 §Decision for the discriminating test.

### Transform

`:mapping/transform` is a vector of **transform names**. Each name is
a keyword that resolves to a transform function in the effective
stdlib (core + plugin extensions, per ADR 0010). Transforms are pure
functions of one argument: `value → value`. The vector composes
left-to-right: `[:trim :lowercase]` applies trim, then lowercase.

The core ships a small transform stdlib (final list pinned by Sprint 5):

- `:trim` — `clojure.string/trim`
- `:lowercase`, `:uppercase` — locale-insensitive case folding
- `:parse-int`, `:parse-double` — numeric parsing, returns nil on
  failure
- `:parse-iso-date` — `:dc/date` → ISO-8601 string

Plugins extend the stdlib via `:transforms` (ADR 0010); cross-plugin
collisions are rejected at register time.

### `:on-empty`

When the source predicate is absent for a record:

- `:skip` (default) — the mapping does nothing for that record.
- `:diagnostic` — emits an `:info`-severity diagnostic
  (`:code :missing-source-predicate :subject record-id`). Does not
  fail. Intended as a discovery aid during plugin development.
- `:default` — emits the canonical assertion with `:mapping/default`
  as the value. The mapping rule must include `:mapping/default`
  whenever `:on-empty` is `:default`; the schema enforces this.

### Schema

The Malli schema for a mapping rule lives in `palomar.plugins.mapping`
(Sprint 5) and is referenced by the plugin schema (ADR 0007) at
`:mapping`:

```clojure
;; pseudocode
[:vector MappingRule]   ; the :mapping value in a plugin map
```

`MappingRule` is closed (every key is named) — extensions go through
the stdlib `:transforms`, not through new top-level mapping keys.
This is the same growth discipline as ADR 0002 for the predicate
stdlib.

The compiler's output target is the **runtime-shaped compiled rule**,
not necessarily a `Rule` data-form map. A compiled rule, as produced
by `palomar.rules/compile-rule`, is a map carrying enough metadata for
the trace (`:id`, `:phase`, optionally `:doc`) plus an opaque runner
function the runtime invokes via `palomar.rules/apply-rule`. The
mapping compiler emits the same shape directly: its runner does
match-against-triples → transform-application → assertion-emission in
one step. This keeps the rule DSL free of a transform primitive (no
data-form rule needs to invoke a Clojure function on a value), while
preserving the spirit of "sugar over rules" — what the runtime
executes is, from its perspective, indistinguishable from a regular
compiled rule. Transforms are resolved against the effective stdlib
at compile time, so transform-name lookup is a register-time concern,
not a runtime one.

## Alternatives considered

- **Mapping as a separate execution substrate.** A bespoke "mapping
  engine" with its own runtime semantics. Rejected: duplicates the
  rule engine without adding power. Rule + transform-stdlib expresses
  every V1 case.
- **Mapping as inline `:rules` in the plugin map.** Authors write rule
  DSL directly. Rejected: rule DSL is too verbose for the
  one-predicate-to-one-predicate case (the 90 % case for a format
  plugin), and importers reading a plugin can't easily distinguish
  "actual rule" from "trivial mapping".
- **Composite mappings (multiple `:from` predicates → one `:to`).**
  Originally rejected for V1: "leaks toward joining and ordering across
  source fields, which is the rule layer's job; plugins that need
  composition write a normalize-phase rule." **Reopened and adopted
  (2026-06-04)** as the bounded `:mapping/combine` form: `:mapping/from`
  may be an ordered *vector* of predicates whose values are joined by a
  required separator string into one `:to` value (subfields absent are
  skipped — no dangling separator). The trigger was concrete: recombining
  catalogue subfields the parser split (INTERMARC `100 $a` surname + `$m`
  forename → "Surname, Forename"; a title + its GMD `$h`) was being done
  by a bespoke normalize-phase pre-pass (`intermarc/with-agent-name`),
  exactly the boilerplate the rejection assumed was rare. A declarative
  combine removes it. The scope stays bounded — a *static* ordered join
  with one separator, not arbitrary joining/conditionals — so the
  "ordering across fields is the rule layer's job" concern holds for
  anything richer; that still writes a rule. A `:combine` ⇔ vector-`:from`
  biconditional and a "qualifier cannot apply to a combine" guard keep the
  closed schema honest.
- **Multiplicity: collapse-by-default to a single value.** Rejected:
  silently drops information. The IR was designed (ADR 0001) to
  represent multiplicity natively; the mapping must not erase it.
  A plugin that wants single-valued canonical assertions writes a
  normalize-phase collapse rule.
- **Qualifier as a separate sub-assertion.** E.g. produce
  `[?r :canon/title "Hugo"]` plus
  `[<assertion-id> :canon/language "fr"]`. Originally rejected as
  "requires reifying the assertion identity and sharply complicates
  the IR; structured values already exist (ADR 0001) for exactly this
  case." **Reopened and adopted by ADR 0011** once concrete plugin
  needs (Sprint 7 DC multilingual, CIDOC/Linked Art/IIIF
  compatibility) forced the choice. The mechanism is not assertion
  reification but fragments: the qualifier coord lives on a fragment
  whose id, not an assertion's, carries the identity. See ADR 0011
  for the reasoning that flipped this option from rejected to
  adopted.
- **`:on-empty` as silent skip with no `:diagnostic` option.**
  Rejected: plugin authors and users need a discovery tool. The
  diagnostic is informational, not an error.

## Consequences

- A mapping is small data: one map per (native, canonical) pair.
  Plugin authors writing a Dublin Core or MARC mapping for the first
  time produce a vector of ten to fifty maps, not custom code.
- Mappings are inspectable, diff-able, generatable. The same trace
  logic that names rules in provenance names the *mapping* by its
  expanded rule id, derived from `:mapping/id` (e.g. a mapping with
  `:mapping/id :map/dc-title` compiles to a rule with id
  `:rule.from-mapping/dc-title`).
- The transform stdlib is a hot growth zone. ADR 0010 documents the
  extensibility model and the conflict-rejection discipline.
- Plugins that need composition write a rule, not a mapping. This
  keeps the mapping schema closed and tractable; extension pressure
  goes to the rule layer where it belongs.
- A future GUI for "browse all mappings" or "explain how `:canon/title`
  is populated" is straightforward: it reads the registered mappings
  by `:mapping/to`.
- Multilingual values become fragments (ADR 0011). Reports and
  projections that target a single-language output have to flatten
  the fragment-referencing assertion back to a primitive, which is
  the projection plugin's responsibility — not a hidden default in
  the mapping.
- The compile step from mapping to rules is a pure function; it is
  testable in isolation and produces the same shape of provenance as
  hand-authored rules. The runtime sees only rules.

## Open V2 questions

- **Sub-record (fragment-level) mappings.** A native record might map
  some predicates onto fragments rather than onto the record itself.
  V1 mappings target the record `:id`; fragment-level targeting waits
  for a concrete plugin need.
  *Update (ADR 0011):* the concrete need has arrived — Sprint 7
  multilingual Dublin Core requires fragment-targeted mappings — so
  this question is reopened and its schema extension is Sprint 6/7's
  to land. See [ADR 0011](./0011-fragments-for-qualified-values.md).
- **Inverse mappings for export.** ADR 0007 lists `:exporter` but the
  mapping schema is import-shaped. An export-time inverse (canonical
  → native) is V2 work; the schema does not pre-empt it.
- **Confidence inheritance.** When a transform fails (e.g.
  `:parse-iso-date` on garbage), should `:mapping/confidence` drop?
  V1: no — confidence is whatever the mapping declares. Failure
  surfaces as a diagnostic.
- **Cross-mapping ordering.** Two mappings whose effects depend on
  each other must declare order via `:requires` between plugins (ADR
  0007), not within the mapping schema.
- **Source-provenance carry-forward at `:normalize`.** When a mapping
  rule renames `[?r :native/x ?v]` to `[?r :canon/x ?v]`, the source
  assertion's `:provenance` — notably `:source`, the original source
  location set by the importer — is not carried into the renamed
  assertion. The renamed assertion has only the mapping rule's
  provenance (`:rule`, `:pass`). This is a limitation of the rule
  engine's matcher (`record-triples` projects assertions to bare
  triples without their provenance), not a mapping-specific decision,
  but it manifests most clearly here because mappings are semantically
  rename-in-place. A V2 enhancement would let the engine optionally
  carry forward source provenance into derived assertions, possibly
  via a `:derivation` chain entry; until a concrete consumer needs it,
  rule-level provenance is treated as sufficient.
- **Cross-plugin `:mapping/id` uniqueness.** `palomar.plugins.mapping`
  derives the compiled-rule id from the `name` portion of
  `:mapping/id` only: `:plugin-a/dc-title` and `:plugin-b/dc-title`
  both compile to `:rule.from-mapping/dc-title`. The mapping schema
  enforces uniqueness only within a single plugin's `:mapping` vector;
  two plugins shipping the same mapping name conflate in provenance
  traces with no compile- or register-time error. **Resolved
  (post-Sprint-5 cleanup):** `compile-mappings` now rejects a batch whose
  rules derive the same compiled rule id, with an `ex-info` naming the
  colliding `:mapping/id`s — the registry-level uniqueness-check option.
  Embedding the plugin id in the derived rule id remains available if
  same-named mappings across plugins ever become a legitimate need.

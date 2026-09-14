# 0011 — Fragments as the canonical home for qualified values

- Status: Accepted
- Date: 2026-05-27

## Context

Cultural metadata records routinely carry **qualified values**: a title with
a language tag, an agent with a role, a date with a type, an identifier with
a scheme. The internal representation must let plugins record both the
value and its qualifier without losing the link between them, and must let
rules reason about the qualifier itself — typically by binding it as a
variable rather than testing it against a known constant.

ADR 0001 already provides two mechanisms that could carry a qualified value:

1. **Structured values** (`{:value/kind :structured, :value/fields {...}}`) —
   a nested map sitting inside an assertion's `:value` slot.
2. **Fragments** — sub-record entities with their own ids, each one a
   legitimate `:subject` for further assertions.

Both can technically express `(title="Les Misérables", lang=fr)`. ADR 0009
§"Open V2 questions" deferred the question of which belongs where, on the
grounds that no concrete plugin had yet forced the choice. Two concrete
needs now force it:

1. **Dublin Core in V1.** Sprint 7 ships a Dublin Core importer. DC records
   routinely repeat the same element in multiple languages (`<dc:title
   xml:lang="fr">…</dc:title>`, `<dc:title xml:lang="en">…</dc:title>`).
   Representing them without a stable per-language identity loses
   information that the rules in Sprint 6 and the projection in Sprint 7
   both need.

2. **Architectural compatibility with event-centric formats.** CIDOC CRM,
   Linked Art, and IIIF do not ship as V1 plugins (see README scope), but
   the V1 IR is required to host them later without rework. These
   standards are built from chains of sub-entities: an object → its
   production event → that event's actor → that actor's birth event → …
   An IR that can only carry such structure inside a single deeply-nested
   value silently forecloses an entire family of standards.

A third option — encoding the qualifier into the predicate name
(`:dct/title-fr`, `:dct/title-en`) — was considered and rejected for
reasons given below.

## Decision

Qualified values are represented as **fragments**, not as structured values.

For every value that carries one or more qualifiers (language, role, type,
scheme, certainty source, …), the importing plugin mints a `Fragment` and
attaches one assertion per coordinate to the fragment's id. The record-level
predicate then references the fragment:

```clojure
;; one DC title in French
(fragment {:id :frag/r42-title-1 :source [:xml :dc:title 0]})

(assertion {:subject   :frag/r42-title-1
            :predicate :dct/text
            :value     "Les Misérables"})

(assertion {:subject   :frag/r42-title-1
            :predicate :dct/lang
            :value     :fr})

(assertion {:subject   :record/r42
            :predicate :dct/title
            :value     {:value/kind   :reference
                        :value/target :frag/r42-title-1}})
```

Rules then bind qualifiers by matching ordinary assertion patterns against
the fragment's id — the same pattern language used for every other rule, no
nested-accessor syntax:

```clojure
;; for every language present in any of the record's titles
[[?r :dct/title (ref ?f)]
 [?f :dct/text ?title]
 [?f :dct/lang ?lang]]
```

`:value/kind :structured` remains available but is **scoped explicitly** to
terminal atomic compounds where neither coordinate would ever sensibly
become the subject of further assertions on its own:

- monetary amounts `{:amount, :currency}`
- geographic coordinates `{:lat, :lon}`
- date ranges treated atomically `{:from, :to}`

The discriminating test for plugin authors and reviewers is one question:
*"Could a future rule, repair, or mapping want to attach an assertion to
either coordinate independently?"* If yes — and language tags, agent roles,
identifier schemes, certainty sources all answer yes — it is a fragment.
If no, a structured value is acceptable.

## Alternatives considered

- **Structured values for every qualified case.** Rejected. Rules that
  *extract* a qualifier (the design intent confirmed for V1) would require
  a nested-accessor syntax inside the rule DSL — a second pattern language
  embedded in the first. With fragments, qualifier extraction is the same
  flat pattern matching as everything else. Structured values also flatten
  poorly under event-centric formats: a CIDOC production event carrying an
  actor carrying a birth event becomes a three-level nested map that no
  rule can usefully traverse, in direct tension with ADR 0001's design
  target.
- **Qualifier as a predicate suffix** (`:dct/title-fr`, `:dct/title-en`).
  Rejected. The qualifier is no longer a value, so rules cannot bind it as
  a variable. Plugins would have to enumerate the qualifier space at
  mapping time — workable for closed enumerations (a fixed list of roles)
  but not for open ones (every BCP 47 language tag, every CIDOC type).
  The design intent of rules that *discover* qualifiers rather than
  *test known ones* is foreclosed by this option.
- **RDF-style reification** (an assertion-about-an-assertion). Rejected.
  Adds boilerplate at every qualified case and re-introduces the RDF
  semantics (IRIs, blank nodes, reification triples) that ADR 0001
  explicitly excluded as not fitting a compilation pipeline.
- **Defer to V2.** Rejected. DC-multilingual is a V1 sprint and event-
  centric architectural compatibility is a V1 design constraint. Either
  would force the decision now; both together make deferral untenable.
  A V1 that ships structured-value qualifiers and then introduces
  fragments in V2 would invalidate every plugin written against V1.

## Consequences

- Sprint 7 (Dublin Core) and any subsequent format plugin ship with a
  clear, uniform shape for qualified values. No format-specific
  special-casing in the IR.
- The rule DSL (ADR 0002) does not grow a nested-accessor syntax in V1.
  Qualifier-aware rules use the same matcher as every other rule. Sprint 6
  (canonical vocabulary) and Sprint 7 (DC) proceed without DSL extension.
- The mapping schema (ADR 0009) must reopen its deferred "fragment-level
  mappings" question. Plugins that import qualified values need to express
  "for each native sub-element matched, mint a fragment id and map onto
  it" rather than the V1 "map onto the record `:id`" shape. A follow-up
  ADR (or §-amendment to 0009) will spell out the schema extension. The
  present ADR establishes the obligation; the schema work is Sprint 6/7's
  to land.
- **Fragment identity becomes architecturally significant.** Two
  `"Foo"@fr` titles in the same source record need stable ids that are
  (a) reproducible across re-runs, otherwise idempotency at merge
  (ADR 0008) breaks; (b) distinct from each other when the source
  occurrences are distinct, otherwise legitimately different values
  collapse; (c) cheap to compute. The canonical scheme — record id +
  source-path locator + occurrence index — is specified in
  [ADR 0012](./0012-fragment-identity-scheme.md), landed before Sprint 5
  so the shape adapter has a complete spec. `palomar.model` exposes a
  `mint-fragment-id` helper; plugins go through it rather than rolling
  their own.
- **Event-centric formats become representable without a model change.**
  CIDOC CRM, Linked Art, and IIIF remain V2 deliverables, but the V1 IR
  no longer forecloses them. The README scope is updated to make this
  architectural constraint explicit alongside the V1 deliverable list.
- **Structured values keep a narrow, well-defined role.** The Malli
  schema for `:value/kind :structured` does not change; only the
  guidance for when to reach for it does. Importer reviews can apply
  the discriminating test mechanically.
- **Canonical vocabulary (ADR 0003) gains a small growth obligation.**
  Predicates that recur as fragment coordinates across formats —
  candidates: `:canon/lang`, `:canon/role`, `:canon/type`, `:canon/scheme`
  — will be added under the same growth discipline ADR 0003 already
  imposes: only when a concrete plugin need surfaces, never anticipated.
- This ADR refines, but does not supersede, ADR 0001's framing of
  `Fragment`. ADR 0001 introduced fragments as pointers into raw source;
  the present ADR specifies their second, equally first-class role as
  the home for qualified values. Both roles are served by the same
  schema.
- **Rule layer cannot mint new fragments in V1.** The `:produce` map in
  the rule DSL (ADR 0002) supports `:assert`, `:diagnostic`, `:repair`
  — not `:fragment`. Fragments are born at **ingest**, inside the
  importer (typically the shape adapter), driven by `:mapping/qualifier`
  declarations from the plugin's mapping. A `:normalize`, `:infer`, or
  `:repair` phase rule cannot retroactively promote a flat value into a
  qualified fragment (e.g. "detect this title's language by heuristic
  and wrap it into a `(text, lang)` fragment"). This is acceptable for
  V1: ingest-time minting covers every V1 plugin use case, including
  Sprint 7 Dublin Core multilingual. The limitation can be lifted later
  by adding a `:fragment` production action to the rule DSL; until a
  concrete rule needs it, the DSL stays unchanged. This ADR explicitly
  forecloses the alternative — runtime production of fragments by
  rules — as out-of-scope V1.

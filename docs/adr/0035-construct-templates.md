# 0035 — Graph templates in the rule language: construct, mint, optional, not

- Status: Accepted
- Date: 2026-10-25
- Extends: [ADR 0002](./0002-edn-as-dsl.md) (the rule DSL)

## Context

A declarative rule produces one assertion per `:assert` template, matches
triples only, and has no way to say "if present" or "if absent". So any
mapping richer than a rename — a MARC record to a CIDOC-CRM Production carried
out by a Person identified by a name, a WEMI chain keyed by creator and title —
is written in Clojure (the WEMI projection, `lrmoo.project`), outside what an
institution can read, review or adapt as data.

## Decision

The rule language gains, without admitting arbitrary functions (ADR 0002):

1. **`:construct`**, a production that is a graph template: a vector of
   `[s p o]` triples (optionally `[s p o {:status s}]`) and of
   `(mint kind part…)` declarations. Every triple is an assertion with the
   phase's default status (ADR 0005) unless it states one, and the rule's
   provenance (rule, phase, bindings).
2. **Terms** from a closed `rules/term-stdlib`:
   - `(mint kind part…)` — the content-derived id of an entity of `kind`, keyed
     by the parts joined with `|` (`model/mint-entity-id`, ADR 0016/0017). The
     entity is declared once with the triples that use it. In object position
     it is a reference.
   - `(ref x)` — a reference.
   - `(norm s)` — `palomar.text/norm`, the matching form keys use.
   - `(str x…)` — concatenation.
   - `(or x…)` — the first bound, non-nil part.
3. **Match clauses**:
   - `(optional clause…)`, a left join. A variable only an optional binds may
     appear in a `:construct` template, and a triple that needs it is not
     written when it is unbound; any other action refuses it at compile time.
   - `(not clause…)`, which keeps a row the inner clauses do not extend
     ("no agent has the role `:creator`").
   - in a triple pattern, `(ref ?x)`, which matches a reference value and binds
     its target, so a rule reaches a qualified fragment.
   - a `string?` guard.
4. **The WEMI chain as data.** `lrmoo.wemi-rules` writes the floor projection's
   productions as four construct rules:
   - the Manifestation;
   - the chain keyed by a stated creator;
   - the chain keyed by an agent of no role when no creator is stated;
   - the creator-less Expression.

   A test shows they build the same graph as `project` — entities, and claims
   with their statuses — on every fixture record with one title, at most one
   creator and no title language (126 records).

`project` remains the production path. What it does that the rules do not is
deliberate, not missing:
- it takes the *first* of several titles or creators;
- it adds a single title language to the Expression key;
- it falls back to an uncertain title's first alternative;
- it attaches the titles' source evidence to the strings (ADR 0031).

"First" depends on assertion order. A rule language that orders its matches
would no longer be one whose rules can be read alone. Whether several titles
should give several Expressions is a modelling question this ADR leaves open.

## Consequences

- A mapping to CIDOC-CRM can be written as data. For example, a Production
  `P14_carried_out_by` a minted Person `P1_is_identified_by` the agent's name
  (`rules-test/construct-writes-a-graph-and-declares-what-it-mints`).
- Rules stay inspectable and serialisable: every new form is data checked by
  the schema (`ConstructItem`, `OptionalForm`, `NotForm`). An unknown term
  fails loudly.
- `(not …)` and `optional` are evaluated on the record as it entered the
  phase (ADR 0004). A rule cannot see what another rule of the same phase
  produces.

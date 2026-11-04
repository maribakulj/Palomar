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

## Amendment (post-audit 8, K4): disjunction, lookups, a planner

The museum rules study (`docs/eval/rules-study.md`) found the language could
not say "or", nor look a class up, and found that a rule's speed hung on
clause order. Three additions, still closed (ADR 0002):

1. **`(any [clause…] [clause…] …)`**, a match clause: the rows each branch
   makes, deduplicated. A variable every branch binds is bound. One that only
   some branches bind may appear in a `:construct` template, like an
   `optional`'s. `(not (any …))` says none of them holds.
2. **`(lookup :table key)`**, a construct term: the value a lookup table maps
   `key` to, else its `:default`, else unbound (no triple). Tables are data
   (`resources/palomar/vocab/<ns>/<name>.edn`, `{:map … :default …}`), like
   the vocabularies `vocab-some?` and `vocab-all?` read. A `mint`'s kind may be
   a term, so a class is looked up (`(mint (lookup :lido/actor-classes ?t)
   …)`).
3. **A planner.** At compile time (`plan-rule`), each guard and `not` moves
   up to just after the last clause before it that binds, or may bind, one
   of its variables. It never moves past an `optional` or an `any` that
   binds one of them in some rows only, and it never moves down, where a
   variable free at its place would be bound. The clauses that bind keep
   their order. The rows are the same, filtered sooner (K4 review: the
   first version moved them past such clauses and changed the rows, and the
   compiled runner did not use the plan). A test that needs only the record
   runs once if the rule binds the record first (`[?r :meta/id ?r]`).

The engine also stopped doing per production what it could do once: a
phase merges its productions through an index of the record's assertions
and entities instead of scanning them, and a rule's run mints each id once.
Every minted id is hashed with one-pass hex encoding (`java.util.HexFormat`),
not one `format` a byte.

**Measured** on the museum slice (`palomar.plugins.lido-rules`), 20 records,
warm:
- 117 rules became 27;
- 14 to 17 ms a record became **4 to 5.6 ms**, about the study's 5 ms
  target, from run to run;
- the whole import and projection gained 2 to 3 ms a record (about 21 to
  19), all of Palomar benefiting from the hashing and the merge.

The gain comes from the rule count, the merge's index, the mint cache and
the hex encoding. The planner adds nothing measurable to these rules, which
were written in a good order by hand. It spares a rule's author that care.


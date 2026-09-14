# Contributing to Palomar

Thanks for considering a contribution. This document is the workflow:
how branches are named, how PRs are reviewed, how architectural changes
are tracked, and what the bar is for tests and code quality.

If something here surprises or blocks you, open a discussion or issue
before the work — fixing the process is cheaper than fixing the work.

## Getting set up

Requirements:

- Java 21+ (Temurin is the reference; CI runs both 21 and 24)
- Clojure CLI ([install instructions](https://clojure.org/guides/install_clojure))
- `clj-kondo` and `cljfmt` — installed via the `:lint` and `:fmt` aliases,
  or as native binaries on `PATH`

The commands you'll use most:

```bash
clojure -M:test              # full suite (unit + property + integration)
clojure -M:test/unit         # fast iteration on a unit test
clojure -M:test/property     # generative invariants only
clojure -M:test/integration  # end-to-end scenarios only
clojure -M:test/junit        # full suite + write JUnit XML to target/junit/
clojure -M:lint              # clj-kondo
clojure -M:fmt               # cljfmt check (read-only)
clojure -M:fmt/fix           # cljfmt rewrite (write)
```

CI runs `:test`, `:lint` and `:fmt` on every push and pull request,
plus a coverage report. See [README.md](./README.md) for the full CI
description and the manual branch-protection settings.

### Test layout

The test tree is split into three categories at the path level:

| Path                    | What lives here                                                |
|-------------------------|----------------------------------------------------------------|
| `test/unit/palomar/`    | Fast, hermetic, isolated to one namespace's behavior           |
| `test/property/palomar/`| Generative invariants via `test.check` and `malli.generator`   |
| `test/integration/palomar/` | Multi-layer scenarios that exercise the full pipeline      |

Namespaces stay flat (e.g. `palomar.model-test`, not
`palomar.unit.model-test`). The split is at the *path* level so the
test runner can scope to one category at a time without renaming.

When you write a new test:

- A bug-fix or single-feature test usually belongs in `unit/`.
- An invariant that should hold over a class of inputs (rather than
  one example) belongs in `property/`.
- A scenario that exercises three or more layers — model, rules,
  runtime, diagnostics — belongs in `integration/`.

## Workflow

### Branches

- `main` is protected: PRs only, CI must pass, reviews required from
  `CODEOWNERS`. The exact settings are in the README's "Branch
  protection" section.
- Feature work: `feat/<topic>`
- Bug fixes: `fix/<topic>`
- Docs / ADRs: `docs/<topic>`
- Test-only: `test/<topic>`
- CI / tooling: `ci/<topic>`
- Pure cleanup: `chore/<topic>`

### Pull requests

One concern per PR. Atomic PRs survive review better and are easier to
revert if a downstream surprise appears. The PR template
(`.github/pull_request_template.md`) drives the review checklist:

- A short summary that names the *why*, not just the *what*.
- The sprint or milestone the PR advances.
- Any architectural impact (link to a new or revised ADR).
- A test plan that lists the verifiable steps the reviewer can replay.
- A `predicate-stdlib growth` line if the rule DSL gained an entry.
- An out-of-scope list — what you deliberately did *not* do, so a
  reviewer doesn't have to ask.

### Architectural changes

Structural decisions (anything that changes how data flows, what a
public API guarantees, how plugins compose, or how the runtime
schedules work) are tracked as ADRs in
[`docs/adr/`](./docs/adr/). The README of that directory has the
template.

Rule of thumb: if your change would be hard to explain to a new
contributor without referring to a prior decision, write the prior
decision down.

Silent edits to accepted ADRs are not accepted. If the original
decision needs to change, add a new ADR with status `Supersedes
<NNNN>` (or `Partially supersedes <NNNN>` if only one section is
affected) and update the older ADR's status line and the index in
`docs/adr/README.md`.

## Code quality bar

Hard requirements (CI enforces):

- All tests green: `clojure -M:test`
- Lint clean: `clj-kondo --lint src test`
- Format clean: `cljfmt check src test`

Soft requirements (a reviewer will catch them):

- Public functions have docstrings — short, declarative, describing
  the contract rather than the implementation.
- Surprising decisions get a comment that explains *why*, not *what*.
- New tests cover the happy path *and* at least one boundary case.
- Property-based tests (`test/property/palomar/property_test.clj`) for
  any invariant that should hold over a class of inputs, not just the
  hand-picked ones.

### Predicate stdlib growth discipline

The rule DSL ships a closed predicate stdlib (ADR 0002). Growth there
is the chief risk to the project's goal of being data-shaped. Before
adding an entry, check that:

- A real plugin needs it now, not in some future sprint.
- It cannot be expressed as a composition of existing predicates.
- The naming makes sense for non-Palomar-experts reading rule source.

The same discipline applies to the transform stdlib introduced in
ADR 0009. A plugin can extend either via its `:predicates` /
`:transforms` keys (ADR 0010); pushing into core requires the
justification above.

## Reporting bugs

Open a GitHub issue using the `Bug report` template. The template
asks for the IR you ran, the rules / mappings you compiled, and the
output you saw — that is almost always enough to reproduce a bug
deterministically without further round-trips.

For bugs whose reproduction would expose private metadata, redact the
content and keep the *shape* (predicates, structure, status). The
shape is almost always what matters.

## Security disclosure

See [SECURITY.md](./SECURITY.md). Don't open public issues for
suspected vulnerabilities.

## License

By submitting a contribution you agree that it will be released under the
project's license, the **Apache License, Version 2.0** (see
[LICENSE](./LICENSE)).

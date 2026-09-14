# 0006 — Maven coordinates by default, git override alias for restricted networks

- Status: Accepted
- Date: 2026-04-17

## Context

Palomar depends on libraries hosted on Clojars (Malli and its transitive
dependencies). Clojars is the standard distribution channel for the Clojure
ecosystem and the natural choice for a Clojure project's `deps.edn`.

Some development environments restrict outbound network access. In particular,
the Claude Code web sandbox (where contributors may run editor sessions) can
reach Maven Central and GitHub but blocks Clojars. In such an environment,
`clojure -M:test` fails at dependency resolution before any code runs, removing
the local feedback loop.

Three options were considered:

1. **Status quo.** Keep Maven coordinates; restricted environments simply lose
   local testing and rely on CI feedback.
2. **Switch all Clojars-only deps to git coordinates** in the main `deps.edn`.
3. **Keep Maven as the default, isolate git overrides in a dedicated alias.**

## Decision

We adopt option 3.

The main `deps.edn` uses standard Maven coordinates for every dependency. This
is the canonical, expected form for a Clojure project: it is what readers,
tools, and downstream consumers expect.

A `:sandbox` alias provides `:override-deps` entries that rewrite every
Clojars-hosted dependency (including transitive ones) as git coordinates
pinned to a specific tag and SHA. In restricted environments, contributors run
`clojure -M:sandbox:test` (or any other alias chained with `:sandbox`) to
bypass Clojars entirely.

CI uses the unaliased Maven path. External consumers who depend on Palomar
get Maven coordinates only.

## Alternatives considered

- **Status quo.** Rejected: removing the local feedback loop materially harms
  the development experience for sandbox contributors over the V1 sprint
  schedule (≈11 sprints to go).
- **Git coordinates everywhere by default.** Rejected: pollutes the main
  `deps.edn` with unusual coordinates, slightly increases first-build time for
  every contributor, and adds a maintenance burden (tag + SHA pinning) to
  every routine version bump. The cost is paid by everyone for the benefit of
  the small subset using restricted environments.
- **Add a Clojars proxy or mirror.** Rejected: out of project scope. Operating
  a proxy is environmental infrastructure, not project configuration.
- **Per-developer local `deps.local.edn`.** Rejected: tools.deps does not load
  `deps.local.edn` automatically. We would need wrapper scripts. An alias is
  the idiomatic mechanism for environment-specific dep overrides.

## Consequences

- The main `deps.edn` stays canonical. External readers see what they expect.
- Restricted-environment contributors get a documented one-line workaround
  (`clojure -M:sandbox:test`).
- **Maintenance cost:** every time a Clojars-hosted dependency is bumped, the
  corresponding entry in the `:sandbox` alias must be updated with the new
  tag + SHA. This is a small but recurring discipline cost. Discipline is
  enforced by a pre-merge checklist item, not by tooling.
- The `:sandbox` alias is currently sufficient for `:test`. Extending it to
  `:lint` and `:fmt` requires git overrides for clj-kondo and cljfmt's
  transitive trees; deferred until a sandbox contributor needs them.
- If Clojars becomes reachable from the sandbox in the future, the alias can
  be removed without touching any code. The decision is reversible.

## Caveat: Leiningen-only transitive dependencies

Tools.deps git coordinates require either a `deps.edn` or a `pom.xml` at the
repository root. Some libraries in Malli's transitive tree are
Leiningen-only (`project.clj`, no `deps.edn`, no `pom.xml`):

- `fipp/fipp`
- `mvxcvi/arrangement`

Both are used by Malli solely for pretty-printing validation errors. The
`:sandbox` alias excludes them from Malli's coord. The functional consequence
is plainer (uncoloured, less formatted) error output from Malli when running
under `:sandbox`. All other Malli features behave identically. CI and normal
local development pull the full set from Clojars and are unaffected.

If a future Clojars-only dependency proves harder to exclude than to replace,
the project may need to maintain a deps-edn-rooted fork. We have not crossed
that line yet and would prefer to avoid it.

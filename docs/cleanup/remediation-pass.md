# Cleanup / Remediation Pass — post-Sprint-5 audit follow-up

- Theme: doc/config hygiene + latent-bug fixes surfaced by the architecture audit
- Status: Complete — all 6 sessions landed
- Branch: `claude/dazzling-planck-LHfT2`
- Basis: read-only architecture audit (2026-05-30). No feature work; this pass
  only reconciles documentation with reality and closes documented latent
  traps before Sprint 6/7 build on the affected code.

## Working agreement

- **One concern per session.** Each session is a single, atomic commit on the
  branch above. CI (`clj-kondo --lint src test`, `cljfmt check src test`,
  `clojure -M:sandbox:test`) must be green at every session boundary.
- **Behavioural changes are test-first.** The failing test that proves the bug
  lands in the same diff as its fix, so review is "did red become green?".
- **Nothing is deleted that still works.** Surface is trimmed by making things
  private, not by removing them — privacy is reversible pre-1.0.
- **Resuming in a new session:** read this file first; the locked decisions
  below are authoritative; continue from the first ⬜ row in Progress.

## Decisions (locked)

| # | Decision | Choice | Rationale |
|---|---|---|---|
| 1 | `dev.clj` / `resources` phantoms | **Purge references** | Not on the roadmap; the `:dev` REPL works without them; recreate properly (under `dev/`) only when a real need appears. |
| 2 | `:mapping/id` cross-plugin collision | **A — register-time guard** | Closes silent provenance/trace conflation; minimal change, no id-format churn; matches the existing fail-loud-at-register idiom (duplicate plugin id, stdlib collisions). |
| 3 | Fragment-id hyphen/dot ambiguity | **A — reject at `mint`** | Closes a `mint`-injectivity hole (two distinct predicates can collide on one fragment id = silent data loss); changes no existing id; enforces ADR 0012's stated preconditions rather than changing the scheme. |
| 4 | Speculative public API | **C — privatize orphans, delete nothing** | Trims the public contract (`parse-fragment-id`, `productions-by-phase`, `topo-order`) without freezing an API pre-1.0; keeps the product surface (`diagnostics`, `model`) intact; reversible. |
| 5 | `data.xml 0.2.0-alpha9` | **C — keep + document** | Downgrading to 0.1.0 would regress the XML shape adapter's namespace handling; the alpha is the ecosystem de-facto standard. Action is documentation, not a version change. |

## Progress

| Session | Concern | Status | Commit |
|---|---|---|---|
| 1 | Doc/config hygiene (phantoms) + this tracker | ✅ done | `ea859ae` |
| 2 | Dependency-boundary guard (`core ⇏ plugins`) | ✅ done | `b601372` |
| 3 | Fix `:mapping/id` collision (test-first) | ✅ done | `68ea584` |
| 4 | Close fragment-identity trap (test-first) | ✅ done | `5d70b50` |
| 5 | Speculative-API policy (mark `^:no-doc`) | ✅ done | `1382588`, `86f226a` |
| 6 | Final reconciliation + close tracker | ✅ done | _this commit_ |

## Per-session detail

### Session 1 — Doc/config hygiene (Decision 1)
- Remove `dev.clj` from the README repository-structure tree; mark `app.clj`
  as a stub.
- Remove `resources` from `deps.edn` `:paths` (the directory does not exist).
- Remove `dev` from `codecov.yml`'s `ignore` list.
- Reword the README "Common tasks" `:dev` line (it loads test paths +
  test.check, not "dev tools").
- Add this tracker.
- **Verify:** lint + format + full suite green; `grep` finds no `dev.clj` /
  `resources` phantom reference.

### Session 2 — Dependency-boundary guard
- Add a test asserting the core namespaces (`model`, `rules`, `runtime`,
  `diagnostics`) never `require` a `palomar.plugins.*` namespace.
- Additive only; cannot change existing behaviour.

### Session 3 — `:mapping/id` collision (Decision 2 — A)
- Test-first: two mapping rules whose derived rule-id collides must be
  rejected loudly at compile time (not silently conflated in the trace).
- Implement the guard in the mapping compiler (`compile-mappings` rejects
  duplicate derived rule-ids in its input).
- Upgrade path noted: option B (plugin-qualified rule-id) if a real need for
  same-named mappings across plugins ever appears.

### Session 4 — Fragment-identity trap (Decision 3 — A)
- Test-first: `mint-fragment-id` must reject locator predicates whose
  namespace contains a hyphen, and any segment containing a dot, with a clear
  `ex-info` (these break injectivity / round-trip).
- Document the "predicate namespaces are hyphen/dot-free" convention
  (candidate: ADR 0012 §Consequences).

### Session 5 — Speculative-API policy (Decision 4 — C) — done
- Kept the product API (`diagnostics`, `model` constructors/queries) and
  the register-time guards (`validate-requires!` / `requires-graph`).
- Mechanism: `^:no-doc` (not `defn-`) — it drops a var from the *advertised*
  public surface while keeping it callable, so no test needs `#'` rewrites
  and the change reverts in one token. Nothing deleted.
- Final scope (after review): only `topo-order` (plugins) stays `^:no-doc`
  — it has no consumer and serves a V2-only plugin load-ordering concern.
  `parse-fragment-id` (inverse of the public `mint-fragment-id`) and
  `productions-by-phase` (a trace-query-family member) were restored to the
  public API: both are legitimate product surface awaiting the CLI, not
  speculative orphans.

### Session 6 — Final reconciliation
- `CHANGELOG.md` (Unreleased): note the cleanup pass.
- Document the `data.xml` alpha choice in `deps.edn` (Decision 5 — C).
- Archive closed sprint trackers under `docs/sprints/archive/` if desired.
- Confirm README/ADR cross-references consistent; mark this tracker complete.

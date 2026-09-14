<!--
Thanks for contributing to Palomar. Please fill in the sections below.
Delete any heading that doesn't apply.
-->

## Summary

<!-- One or two sentences: what does this PR change and why? -->

## Sprint / scope

<!--
Which sprint or milestone does this advance? Reference the sprint number
from the README roadmap (e.g. "Sprint 5 — plugin protocol") or "out of
scope: opportunistic fix".
-->

## Architectural impact

<!--
Does this PR introduce, change, or supersede an architectural decision?
- If yes: link the new or updated ADR in `docs/adr/`. Per CONTRIBUTING,
  silent edits to accepted decisions are not accepted.
- If no: write "None" here.
-->

## Test plan

<!--
Bulleted list of how this was verified. Aim for verifiable steps.
Examples:
- New unit tests: `clojure -M:test` (note new file)
- Existing suite still green: 107 tests passing
- Manual REPL exercise: ...
-->

- [ ] `clojure -M:test` passes locally
- [ ] `clj-kondo --lint src test` passes
- [ ] `cljfmt check src test` passes
- [ ] New behavior covered by tests (or N/A — explain)

## Predicate stdlib growth

<!--
If this PR adds an entry to the rule predicate stdlib (`palomar.rules`),
justify it with a concrete use case per the README's growth discipline.
Otherwise: "N/A".
-->

## Out of scope

<!--
Anything you deliberately did NOT do here, that a reviewer might ask
about? Useful to head off "why didn't you also fix X?" comments.
-->

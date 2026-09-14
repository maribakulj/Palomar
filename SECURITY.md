# Security policy

## Scope

Palomar is a library and CLI that processes user-supplied metadata
records and rule files. The realistic attack surface is:

- A maliciously crafted rule file that exploits the compiler.
- A maliciously crafted source record that exploits an importer.
- A registered plugin that abuses its access to the runtime.

Plugins are loaded by explicit `require` and `register` calls; the
project's trust model assumes you trust the plugins you load (see
ADR 0010). Sandboxing of plugin code is explicitly out of scope for
V1.

## Hardening

### XML input parsing

Every XML importer — MARC-XML and its INTERMARC / UNIMARC /
INTERMARC-NG dialects, MODS, Dublin Core, and the generic shape
adapter — parses through a single façade, `palomar.xml`, and never
calls `clojure.data.xml` directly. That façade **refuses DTDs**
(`:support-dtd false`), which closes two holes in the underlying
parser's defaults:

- **Entity-expansion denial of service (`billion laughs`).**
  `clojure.data.xml` expands internal general entities with no size
  bound, and the JDK's `jdk.xml.entityExpansionLimit` does *not* fire
  through its StAX path — verified: a small nested-entity payload
  expands unbounded in memory. Refusing DTDs removes the vehicle
  entirely; a `<!DOCTYPE …>` now throws before any entity is declared.
- **XML external entity (XXE) injection.** `clojure.data.xml` does not
  resolve external `SYSTEM`/`PUBLIC` identifiers, so there is no
  file-disclosure or SSRF surface even without this change. Refusing
  DTDs removes the vehicle a second way, and
  `:supporting-external-entities false` is pinned for defence in depth.

No Palomar fixture or supported format legitimately uses a DTD, so the
policy is total rather than per-format. `palomar.xml-test` pins the
behaviour: benign XML parses unchanged, while billion-laughs and
external-entity payloads are rejected on both the eager and the
streaming (WP-7) parse paths.

## Reporting a vulnerability

**Please do not open a public GitHub issue for a suspected security
vulnerability.**

Instead, report privately by one of these channels:

- GitHub's [private vulnerability reporting](https://docs.github.com/en/code-security/security-advisories/guidance-on-reporting-and-writing-information-about-vulnerabilities/privately-reporting-a-security-vulnerability)
  feature for this repository (preferred — leaves an audit trail).
- Direct message to a maintainer listed in [CODEOWNERS](./.github/CODEOWNERS).

In your report, include:

- The version (commit SHA, tag, or release).
- A minimal reproduction: input rules, input records, command line.
- The behavior you observed and what you expected instead.
- The impact, as you understand it (information disclosure, denial of
  service, code execution, etc.).

## What to expect

- Acknowledgement within **5 working days**.
- A first assessment (severity estimate, affected versions) within
  **15 working days**.
- A coordinated disclosure timeline agreed in writing — typically 30
  to 90 days depending on severity, complexity, and whether a
  workaround exists.

If a fix lands before the disclosure date, it ships with a security
advisory. Reporters are credited unless they prefer otherwise.

## Supported versions

Until v1.0.0, only the latest tagged release on `main` is supported.
Post-1.0 the supported-versions table will be filled in here.

## Out of scope

These are *not* considered vulnerabilities for the purposes of this
policy:

- Resource exhaustion from a deliberately enormous *input* (e.g. a
  multi-gigabyte file). Palomar is a batch tool; raw input size is an
  operational concern. Note the distinction: *amplification* attacks —
  a small input that **expands** hugely, such as XML entity expansion —
  are in scope and are handled (see Hardening → XML input parsing).
- A registered plugin's misbehavior. The trust model is documented in
  ADR 0010 — registering a plugin is granting it execution.
- Issues in dependencies. Forward those to the upstream project. We
  accept reports about *our use* of a dependency that exposes its
  vulnerability needlessly (e.g. running it against untrusted input
  when we don't have to).

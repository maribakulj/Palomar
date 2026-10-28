# 0048 — A manifest names the source tree and the runtime, not what is loaded

- Status: Accepted
- Date: 2026-10-28
- Amends: [ADR 0030](./0030-run-manifest.md)

## Context

After post-audit test 09, the manifest digested the source of every
`palomar.*` namespace **loaded** in the process (`all-ns`). The second
post-audit review found that this identifies the process, not the code.
Loading one more namespace, even one the conversion never calls, changes the
digest: requiring `palomar.cli` took it from 23 to 54 namespaces. It also
said nothing of the runtime, or of the libraries a conversion goes through
(data.xml parses every source, data.json writes Linked Art).

## Decision

1. **The code fingerprint digests the engine's source tree.** It covers
   every `.clj`, `.cljc` and `.edn` file under `palomar/` in the classpath
   root holding `palomar/model.clj`, a source directory or a jar
   (`build.edn` excluded: it describes the build, it is not code). Two runs
   of the same code have the same digest, whatever either had loaded. The
   field is `:files`, and the manifest's shape is version 2.
2. **The runtime is named.** The manifest records the JVM version and
   vendor, and Clojure's version.
3. **The dependencies are named.** It records each resolved library's
   coordinate (Maven version, git sha or tag, local root), read from the
   basis the Clojure CLI started the JVM with. Under an uberjar there is no
   basis: the field is absent, and the jar's own digest is what identifies
   the run.

The commit still comes from `palomar/build.edn`, which a release build
writes (ADR 0030). The engine does not shell out to git: a development
checkout is identified by its source digest, which changes with any edit,
committed or not.

## Consequences

- `manifest/diff` of two runs on the same code and runtime is empty however
  each was invoked (CLI, REPL, tests).
- A dependency upgrade or a JVM change shows in the diff, so a changed
  output can be traced to it.
- The digest covers code the conversion may not call (another spoke). That
  is the conservative side: a change anywhere in the engine is named.

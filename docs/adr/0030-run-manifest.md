# 0030 — A conversion can write a manifest of everything its output depends on

- Status: Accepted
- Date: 2026-10-25

## Context

Palomar is deterministic — content-derived ids, no clock in the engine — but a
result carried no record of what produced it. Two outputs that differ could not
be explained: another input, another mapping, another version, another option?
For reproducible research a conversion has to say what it was.

## Decision

1. `palomar.manifest/manifest` builds an EDN map: the manifest's own version;
   Palomar's version and commit (a `palomar/build.edn` resource a release build
   writes, `{:version "dev"}` otherwise); the spoke and target; each plugin with
   its `:plugin/spec-version` and a canonical digest (`model/content-digest`) of
   its mapping and rules — they are data, so any change to a rule changes its
   digest; the vendored ontology files' SHA-256; the SHA-256 and size of every
   input and output; the options; the active rule ids; the loss summary; the run
   times; and an `:external` block for what an application did upstream (a model
   and its parameters).
2. The engine stays pure: times, paths and the external block come from the
   caller. `convert --manifest <file>` writes one.
3. `manifest/diff` names the top-level keys that differ between two manifests,
   ignoring the run times: empty means the runs reproduce each other.

## Alternatives considered

- *Embedding provenance in the output itself*: not every target can carry it
  (MARC, DC), and it would change the output it describes.
- *Recording the git commit at run time*: the commit of the working directory is
  not the code that ran when Palomar is used as a library; the build writes it.

## Consequences

- Two runs of the same conversion produce manifests whose `diff` is empty
  (pinned by `manifest-test`); a changed mapping is named.
- `build.edn` is not yet written by the release workflow; until it is, the
  version reads `"dev"`.
